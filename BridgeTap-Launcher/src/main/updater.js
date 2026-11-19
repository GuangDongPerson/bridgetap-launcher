/**
 * Auto update: GitHub Release check → download → SHA256 verify → replace → restart.
 *
 * Only the GuangDongPerson/bridgetap-launcher repo is trusted; while the repo is still empty GitHub returns
 * 404, which we treat as "no update" and handle silently, without bothering the user.
 *
 * Security floor:
 *   - The asset URL must start with this repo's releases/download, so a forged JSON can't point
 *     somewhere else
 *   - Downloads are size-capped and the connection is dropped as soon as the cap is exceeded
 *   - No published SHA256 means no update (better to skip an update than install an unverifiable exe)
 *   - A failed verification only deletes the downloaded file; it never enters the replace path
 *
 * 自动更新：GitHub Release 检查 → 下载 → SHA256 校验 → 覆盖 → 重启。
 *
 * 只认 GuangDongPerson/bridgetap-launcher 这个仓库；仓库还空着时 GitHub 返回 404，
 * 这里当成「没有更新」静默处理，不打扰用户。
 *
 * 安全底线：
 *   - 资产 URL 必须以该仓库的 releases/download 开头，避免被伪造的 JSON 引到别处
 *   - 下载有大小上限，超了立即断开
 *   - 没有公布 SHA256 就拒绝更新（宁可不让更，也不装一个无法校验的 exe）
 *   - 校验失败只删下载文件，绝不进入替换流程
 */
const { app, net } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const REPO = 'GuangDongPerson/bridgetap-launcher';
const API_LATEST = `https://api.github.com/repos/${REPO}/releases/latest`;
const UA = 'BridgeTap-Launcher-Updater';
const MAX_BYTES = 300 * 1024 * 1024;
const CONNECT_TIMEOUT_MS = 15000;
/** A transfer that receives no byte at all for this long is treated as dead · 传输连续这么久没有数据即判定失败 */
const STALL_TIMEOUT_MS = 30000;
/** Progress is pushed to the renderer at most this often · 进度推给渲染层的最小间隔 */
const PROGRESS_INTERVAL_MS = 120;

/**
 * Prefix proxies: `prefix + full original GitHub URL` is forwarded as-is.
 *
 * Only the three that measured well on raw / Release assets / archives / git at the same time are
 * kept; in the 2026-11 retest ghfast.top, gh.llkk.cc, bgithub.xyz and kkgithub.com all timed out —
 * don't add them back. Order is the default priority; probe results re-sort by throughput.
 *
 * 前缀式代理：`prefix + 原始 GitHub 完整 URL` 即可转发。
 *
 * 只放实测能同时吃下 raw / Release 资产 / Archive / git 的三家；2026-11 复测里
 * ghfast.top、gh.llkk.cc、bgithub.xyz、kkgithub.com 已经全部超时，不要再加回来。
 * 顺序即默认优先级，测速结果还会再按吞吐重排。
 */
const MIRRORS = [
  {
    id: 'gh-proxy',
    name: 'GH-Proxy',
    host: 'gh-proxy.com',
    prefix: 'https://gh-proxy.com/',
    // api = also a candidate for "check for updates". All three are flagged, but in practice only
    // gh-proxy really forwards api.github.com (one of the others answers an HTML 404, the last a
    // 403), so it ranks first; the other two are backups — they fail fast (1~4s) and don't drag the
    // check out, yet can take over the day they recover.
    // api = 也参与"查更新"的候选。三条都标了，但实测只有 gh-proxy 真的转
    // api.github.com（另外两家一个回 HTML 404、一个回 403），所以它排第一；
    // 后面两家是后备——它们失败得很快（1~4s），不会把检查拖长，但哪天恢复了就能接上。
    api: true
  },
  { id: 'imciel', name: 'GHProxy', host: 'ghproxy.imciel.com', prefix: 'https://ghproxy.imciel.com/', api: true },
  { id: 'ghproxy-net', name: 'GHProxy.net', host: 'ghproxy.net', prefix: 'https://ghproxy.net/', api: true }
];

/**
 * Total timeout for probing a single route; beyond that the route is considered unusable.
 * 单条线路测速的总超时；超过就判定这条线不可用
 */
const PROBE_TIMEOUT_MS = 12000;
/**
 * Per-mirror timeout for update checks. Every candidate must be tried, so we can't wait 15 seconds
 * each like the direct connection — three dead routes would mean 45 seconds, and the user would
 * think the UI froze. API responses are tiny; the fastest route measured returns in 1.7s.
 *
 * 查更新时单条镜像的超时。要挨个试所有候选，所以不能像直连那样等 15 秒——
 * 三条全挂就是 45 秒，用户会以为界面死了。API 响应很小，实测最快那条 1.7s 就回来。
 */
const API_MIRROR_TIMEOUT_MS = 6000;
/**
 * How many bytes each route reads to gauge speed: 1MB is enough to tell fast from slow without
 * burning real traffic.
 * 每条线路读多少字节来算速度：1MB 足够分出快慢，又不会真的耗流量
 */
const PROBE_BYTES = 1024 * 1024;
/**
 * Below this amount no trustworthy speed can be computed; mark it as "probe failed" instead of
 * reporting an absurd number.
 * 少于这个量算不出可信速度，标成"测速失败"而不是硬报一个荒谬的数字
 */
const PROBE_MIN_BYTES = 48 * 1024;

function parseVersion(value) {
  const clean = String(value || '').trim().replace(/^v/i, '');
  const dash = clean.indexOf('-');
  const core = dash >= 0 ? clean.slice(0, dash) : clean;
  const pre = dash >= 0 ? clean.slice(dash + 1) : '';
  const nums = core.split('.').map((part) => parseInt(part, 10) || 0);
  return { nums, pre };
}

/**
 * Semver-style comparison; for the same core version a pre-release ranks below the stable release.
 * 语义化比较；同核心号时预发布版本低于正式版。
 */
function compareVersions(a, b) {
  const left = parseVersion(a);
  const right = parseVersion(b);
  for (let i = 0; i < 3; i += 1) {
    const diff = (left.nums[i] || 0) - (right.nums[i] || 0);
    if (diff !== 0) {
      return diff > 0 ? 1 : -1;
    }
  }
  if (left.pre === right.pre) {
    return 0;
  }
  if (!left.pre) {
    return 1;
  }
  if (!right.pre) {
    return -1;
  }
  return left.pre > right.pre ? 1 : -1;
}

function request(url, options = {}) {
  return new Promise((resolve, reject) => {
    const req = net.request({ url, method: options.method || 'GET' });
    req.setHeader('User-Agent', UA);
    req.setHeader('Accept', options.accept || 'application/vnd.github+json');
    const timer = setTimeout(() => {
      req.abort();
      reject(new Error('timeout'));
    }, options.timeout || CONNECT_TIMEOUT_MS);

    let status = 0;
    let body = '';
    req.on('response', (res) => {
      status = res.statusCode;
      res.on('data', (chunk) => {
        body += chunk.toString('utf8');
        if (body.length > 4 * 1024 * 1024) {
          req.abort();
          clearTimeout(timer);
          reject(new Error('response too large'));
        }
      });
      res.on('end', () => {
        clearTimeout(timer);
        resolve({ status, body });
      });
      res.on('error', (error) => {
        clearTimeout(timer);
        reject(error);
      });
    });
    req.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    req.end();
  });
}

/**
 * Find the sha256 in the Release body; lines that mention the file name take priority.
 * 从 Release 正文里找 sha256；优先带文件名的行。
 */
function parseSha(text, fileName) {
  const lines = String(text || '').split(/\r?\n/);
  const hex = /\b([a-fA-F0-9]{64})\b/;
  if (fileName) {
    for (const line of lines) {
      if (line.includes(fileName)) {
        const hit = line.match(hex);
        if (hit) {
          return hit[1].toLowerCase();
        }
      }
    }
  }
  for (const line of lines) {
    const hit = line.match(hex);
    if (hit) {
      return hit[1].toLowerCase();
    }
  }
  return null;
}

function isTrustedAsset(url) {
  return typeof url === 'string'
    && url.startsWith(`https://github.com/${REPO}/releases/download/`);
}

/**
 * A speed probe is a single 1MB GET that never touches disk and plays no part in installation, so
 * validation is one notch looser than for downloads: landing anywhere on github.com is enough. That
 * still lets us probe assets of other repos, while stopping the renderer from being tricked into
 * probing arbitrary hosts (that would be SSRF, not a speed test).
 *
 * 测速只做一次 1MB 的 GET、不落盘、不参与安装，所以校验比下载松一档：
 * 只要落在 github.com 上就行。这样拿别的仓库资产也能测，同时挡住渲染层被骗去
 * 探测任意主机（那是 SSRF，不是测速）。
 */
function isGithubUrl(url) {
  if (typeof url !== 'string' || !url.startsWith('https://github.com/')) {
    return false;
  }
  if (url.includes('..') || url.length > 2048) {
    return false;
  }
  return true;
}

/* ── Mirror speed probing ───────────────────────────────── */
/* ── 镜像测速 ────────────────────────────────────────────── */

/**
 * Liveness check + speed probe for a single route.
 *
 * Range is used to fetch only the first 1MB, which yields real throughput without pulling the whole
 * installer down. The result shape is fixed and always carries ok, so the renderer never has to
 * null-check:
 *   { id, name, host, ok, code?, latency?, speed?, received?, status? }
 * code values: ok | timeout | http | html | slow | error
 *
 * 单条线路探活 + 测速。
 *
 * 用 Range 只取前 1MB，既算出真实吞吐又不把整个安装包拉下来。
 * 返回结构固定，无论成功失败都带 ok，渲染层不用再判空：
 *   { id, name, host, ok, code?, latency?, speed?, received?, status? }
 * code 取值：ok | timeout | http | html | slow | error
 */
function probeMirror(mirror, targetUrl) {
  return new Promise((resolve) => {
    const started = Date.now();
    let ttfb = 0;
    let received = 0;
    let settled = false;
    let timer = null;
    let req = null;

    const finish = (payload) => {
      if (settled) {
        return;
      }
      settled = true;
      if (timer) {
        clearTimeout(timer);
      }
      try {
        req.abort();
      } catch {
        // Aborting an already-finished request throws; ignore it
        // 已经结束的请求 abort 会抛，忽略
      }
      resolve({ id: mirror.id, name: mirror.name, host: mirror.host, ...payload });
    };

    const conclude = () => {
      const elapsed = Date.now() - started;
      // Throughput counts only the time after the first byte, otherwise a small high-latency route
      // would be scored as slow
      // 吞吐只算「首字节之后」的时间，否则延迟高的小线路会被算成慢速
      const transferMs = Math.max(1, elapsed - ttfb);
      if (received < PROBE_MIN_BYTES) {
        finish({ ok: false, code: 'slow', latency: ttfb, received });
        return;
      }
      finish({
        ok: true,
        code: 'ok',
        latency: ttfb,
        received,
        // bytes/ms ≈ kilobytes/s
        // 字节/毫秒 ≈ 千字节/秒
        speed: Math.round((received / transferMs) * 1000)
      });
    };

    try {
      req = net.request({ url: mirror.prefix + targetUrl });
    } catch {
      finish({ ok: false, code: 'error' });
      return;
    }

    req.setHeader('User-Agent', UA);
    req.setHeader('Range', `bytes=0-${PROBE_BYTES - 1}`);
    req.setHeader('Accept', '*/*');
    timer = setTimeout(() => finish({ ok: false, code: 'timeout' }), PROBE_TIMEOUT_MS);

    req.on('response', (res) => {
      // No 206 means the proxy ignores Range and streams the whole file — then measure latency
      // only, don't burn the bandwidth
      // 代理没给 206 说明它不认 Range，直接整包传——那就只量延迟，别把带宽耗光
      const partial = res.statusCode === 206;
      if (res.statusCode !== 200 && res.statusCode !== 206) {
        finish({ ok: false, code: 'http', status: res.statusCode });
        return;
      }
      const type = String(res.headers['content-type'] || '');
      if (/text\/html/i.test(type)) {
        // Some sites disguise a 404 as a 200 HTML page
        // 有些站会把 404 包装成 200 的 HTML 页面
        finish({ ok: false, code: 'html', status: res.statusCode });
        return;
      }

      res.on('data', (chunk) => {
        if (!ttfb) {
          ttfb = Date.now() - started;
        }
        received += chunk.length;
        if (received >= PROBE_BYTES || (!partial && received >= PROBE_MIN_BYTES)) {
          conclude();
        }
      });
      res.on('end', () => conclude());
      res.on('error', () => finish({ ok: false, code: 'error', latency: ttfb }));
      res.on('aborted', () => conclude());
    });
    req.on('error', () => finish({ ok: false, code: 'error' }));
    req.end();
  });
}

/**
 * Probe every route in parallel; sort by usable first → faster speed → lower latency.
 * Unusable routes go last but are still shown, so the user can see which one is down.
 *
 * @param {string} targetUrl
 * @param {(result:object)=>void} [onResult] Called once per finished route, for incremental display ·
 *   每测完一条就回调一次，用于边测边显示
 *
 * Why streaming callbacks: the three routes run in parallel and the fastest answers within a second,
 * while ghproxy.net can drag on for 10. Waiting on Promise.all means the user stares at an empty
 * list for 10 seconds — a pure loss of experience.
 *
 * 并行测所有线路，按「可用优先 → 速度降序 → 延迟升序」排序。
 * 不可用的排在后面，渲染层仍然展示，用户能看到哪条挂了。
 *
 * 为什么要流式回调：三条线路并行跑，最快的那条 1 秒就有结果，但 ghproxy.net 要
 * 拖到 10 秒。等 Promise.all 意味着用户对着空列表干等 10 秒——这是纯粹的体验损失。
 */
async function probeMirrors(targetUrl, onResult) {
  if (!isGithubUrl(targetUrl)) {
    return { ok: false, error: 'bad probe target', results: [] };
  }
  const tasks = MIRRORS.map((mirror) => probeMirror(mirror, targetUrl).then((result) => {
    if (typeof onResult === 'function') {
      try {
        onResult(result);
      } catch {
        // A failing callback (sending an event to the renderer) must not affect the probe itself
        // 回调（往渲染进程发事件）失败不能影响测速本身
      }
    }
    return result;
  }));
  const results = await Promise.all(tasks);
  results.sort((a, b) => {
    const left = rankOf(a);
    const right = rankOf(b);
    for (let i = 0; i < 3; i += 1) {
      if (left[i] !== right[i]) {
        return left[i] - right[i];
      }
    }
    return 0;
  });
  return { ok: true, results };
}

/**
 * Weight triple used for sorting: unusable routes always sink to the bottom; usable ones rank by
 * speed descending, then latency ascending.
 * 排序用的权重三元组：不可用的一律垫底，可用的按速度降序、延迟升序。
 */
function rankOf(item) {
  if (!item.ok) {
    return [1, 0, 0];
  }
  return [0, -(item.speed || 0), item.latency || 0];
}

function findMirror(id) {
  return MIRRORS.find((item) => item.id === id) || null;
}

/**
 * Routes that can proxy api.github.com, in MIRRORS order.
 * 能代理 api.github.com 的线路，按 MIRRORS 里的顺序。
 */
function apiMirrors() {
  return MIRRORS.filter((item) => item.api === true);
}

/**
 * Check for the latest Release.
 *
 * @param {{via?: 'direct'|'mirror'}} [options] With via='mirror', try every route flagged `api` in
 *   order and take the first one that gives a usable result · via='mirror' 时按顺序试所有
 *   带 `api` 标记的线路，第一条给出可用结果的即采用。
 * @returns {{ok:boolean, update?:object|null, error?:string, via?:string,
 *            mirror?:string, code?:string, attempts?:object[]}}
 *
 * code lets the UI tell "no response" apart from "responded but unsatisfying":
 *   timeout   the network layer never came back (blocked / route down) → worth telling the user to
 *             retry or switch mirrors
 *   http      received an HTTP error code
 *   network   other network errors (DNS, connection reset, etc.)
 *   reject    an unexpected response trapped by the guard (not GitHub API JSON)
 *
 * 查最新 Release。
 *
 * code 用来让界面分辨"没响应"和"响应了但不满意"：
 *   timeout   网络层没回来（可能被墙/线路挂）→ 值得提示用户重试或换镜像
 *   http      拿到了 HTTP 错误码
 *   network   其他网络错误（DNS、连接重置等）
 *   reject    守卫拦下的异常响应（不是 GitHub API 的 JSON）
 */
/**
 * There are two kinds of 404 and they must be told apart:
 *   · The GitHub API really says "this repo has no Release" → returns {"message":"Not Found",...}
 *   · The proxy doesn't recognise the URL and spits out its own HTML 404 page
 * The former is a normal state (our repo indeed hasn't shipped yet); the latter means the route
 * doesn't support it, and counting it as a successful check would show "already up to date" — a 404
 * page disguised as "you have no update" is worse than an error.
 *
 * 404 分两种，必须分清：
 *   · GitHub API 真的说"这个仓库没有 Release" → 返回 {"message":"Not Found",...}
 *   · 代理站不认这个 URL，自己吐了个 HTML 404 页面
 * 前者是正常状态（我们仓库也确实还没发版），后者是线路不支持，当成检查成功
 * 会直接显示"已是最新版本"——一个 404 页面伪装成"你没有更新"，比报错更坑。
 */
function isGithubNotFound(body) {
  try {
    const parsed = JSON.parse(body);
    return Boolean(parsed && typeof parsed === 'object' && typeof parsed.message === 'string');
  } catch {
    return false;
  }
}

/**
 * Fetch the Release JSON from one address and translate it into a check() result.
 *
 * 往一个地址要 Release JSON 并翻译成 check() 的结果。
 *
 * @param {object|null} mirror null = direct connection · null = 直连
 */
async function checkVia(mirror) {
  const via = mirror ? 'mirror' : 'direct';
  const base = { via, mirror: mirror ? mirror.id : null };
  const url = mirror ? mirror.prefix + API_LATEST : API_LATEST;

  let res;
  try {
    // Mirrors are tried one by one; waiting too long on a single one burns the whole budget on a
    // dead site
    // 镜像要挨个试，单条等太久会把总额度耗在一个死站上
    res = await request(url, mirror ? { timeout: API_MIRROR_TIMEOUT_MS } : {});
  } catch (error) {
    return {
      ...base,
      ok: false,
      code: error.message === 'timeout' ? 'timeout' : 'network',
      error: error.message
    };
  }
  if (res.status === 404) {
    if (isGithubNotFound(res.body)) {
      // The repo has no Release yet: not an error
      // 仓库还没有 Release：不是错误
      return { ...base, ok: true, update: null };
    }
    return { ...base, ok: false, code: 'reject', error: 'proxy 404' };
  }
  if (res.status !== 200) {
    return { ...base, ok: false, code: 'http', error: 'HTTP ' + res.status };
  }

  let release;
  try {
    release = JSON.parse(res.body);
  } catch {
    // Some proxies wrap a 404 in a 200 HTML page, and JSON.parse blows up halfway through
    // 有些代理站会把 404 包成 200 的 HTML 页面，JSON.parse 到一半就炸
    return { ...base, ok: false, code: 'reject', error: 'bad json' };
  }
  if (!release || typeof release !== 'object' || !release.tag_name) {
    return { ...base, ok: false, code: 'reject', error: 'no tag_name' };
  }

  const version = String(release.tag_name || '').replace(/^v/i, '');
  if (!version) {
    return { ...base, ok: false, code: 'reject', error: 'no tag_name' };
  }
  if (compareVersions(version, app.getVersion()) <= 0) {
    return { ...base, ok: true, update: null };
  }

  const assets = Array.isArray(release.assets) ? release.assets : [];
  // Releases up to 1.0.10 shipped as BridgeTap-Injector-<version>.exe; both names must stay
  // acceptable so an installed copy can still update across the rename.
  // 1.0.10 及更早版本的产物叫 BridgeTap-Injector-<version>.exe；两种名字都要认，
  // 否则已经装上的旧版本跨不过这次改名。
  const exe = assets.find((item) => /BridgeTap-(?:Injector|Launcher)-.*\.exe$/i.test(item.name || ''));
  if (!exe || !isTrustedAsset(exe.browser_download_url)) {
    return { ...base, ok: false, code: 'reject', error: 'no portable asset' };
  }
  const shaAsset = assets.find((item) => /\.sha256$/i.test(item.name || '')
    && isTrustedAsset(item.browser_download_url));

  return {
    ...base,
    ok: true,
    update: {
      version,
      tag: release.tag_name,
      notes: release.body || '',
      asset: {
        name: exe.name,
        url: exe.browser_download_url,
        size: Number(exe.size) || 0
      },
      shaUrl: shaAsset ? shaAsset.browser_download_url : null,
      sha: shaAsset ? null : parseSha(release.body, exe.name)
    }
  };
}

async function check(options = {}) {
  if (options.via !== 'mirror') {
    return checkVia(null);
  }

  // Try the routes in MIRRORS order; the first one that "gives a result" is adopted — note that
  // "gives a result" includes "the repo really has no Release" (ok:true, update:null), which means
  // the route works and we should not keep trying.
  // 按 MIRRORS 里的顺序逐条试。第一条"能给出结果"的即采用——
  // 注意"能给出结果"包括"仓库确实没有 Release"（ok:true, update:null），
  // 那是线路工作正常，不该继续往下试。
  const routes = apiMirrors();
  if (!routes.length) {
    return checkVia(null);
  }

  const attempts = [];
  for (const route of routes) {
    const result = await checkVia(route);
    attempts.push({
      mirror: route.id,
      ok: result.ok,
      code: result.code || '',
      error: result.error || ''
    });
    if (result.ok) {
      return { ...result, attempts };
    }
  }

  // Everything failed: report "timeout" in preference. The only thing the user can act on is
  // "network is down", whereas an HTTP 403 means the route doesn't accept the path — reporting that
  // gives the user something they can neither understand nor act on.
  // 全挂：优先把"超时"报上去。用户能动手的只有"网络没通"，而 HTTP 403 这种
  // 是线路自己不认路径，报给用户既看不懂也没法处理。
  const failed = attempts.find((item) => item.code === 'timeout') || attempts[attempts.length - 1];
  return {
    ok: false,
    via: 'mirror',
    mirror: failed ? failed.mirror : null,
    code: failed ? failed.code : 'network',
    error: failed ? failed.error : 'no api mirror',
    attempts
  };
}

/**
 * Download a file from a possible mirror prefix.
 *
 * Two timers, deliberately separate:
 *   · connect — how long the response headers may take (CONNECT_TIMEOUT_MS)
 *   · stall   — how long the transfer may go without receiving a single byte (STALL_TIMEOUT_MS)
 * A single timer covering the whole transfer caps a 115 MB download at 15 s, i.e. requires ~7.3 MB/s;
 * every slower connection then fails with "timeout". Measured in the field: 2.8 MB/s, aborted at 42 MB.
 *
 * 两个定时器，故意分开：
 *   · connect —— 等响应头最多多久（CONNECT_TIMEOUT_MS）
 *   · stall   —— 传输中连续多久收不到一个字节才算卡死（STALL_TIMEOUT_MS）
 * 用一个定时器罩住整个传输，115 MB 就只剩 15 秒，相当于要求 7.3 MB/s；实测现场只有 2.8 MB/s，
 * 于是每次都在 42 MB 处报 "timeout"。
 *
 * @param {string} url    Original GitHub asset URL · 原始 GitHub 资产地址
 * @param {string} dest   Target path on disk · 落盘路径
 * @param {(received:number,total:number)=>void} [onProgress] Throttled to ~8 calls/s · 约每秒 8 次
 * @param {string} [prefix] Mirror prefix; omitting it means the official host directly ·
 *   镜像前缀；不传就是直连官方
 */
function downloadFile(url, dest, onProgress, prefix) {
  return new Promise((resolve, reject) => {
    const req = net.request({ url: (prefix || '') + url });
    req.setHeader('User-Agent', UA);
    req.setHeader('Accept', 'application/octet-stream');

    let settled = false;
    let connectTimer = null;
    let stallTimer = null;
    let out = null;

    const fail = (error) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(connectTimer);
      clearTimeout(stallTimer);
      if (out) {
        out.destroy();
      }
      try {
        req.abort();
      } catch {
        // Already finished; nothing to abort
        // 已经结束，无需中断
      }
      if (!error.code && (error.message === 'timeout' || error.message === 'stalled')) {
        error.code = error.message;
      }
      reject(error);
    };

    const touch = () => {
      clearTimeout(stallTimer);
      stallTimer = setTimeout(() => fail(new Error('stalled')), STALL_TIMEOUT_MS);
    };

    connectTimer = setTimeout(() => fail(new Error('timeout')), CONNECT_TIMEOUT_MS);

    req.on('response', (res) => {
      // Headers are in: from here on the transfer is judged by the stall timer, not by the clock
      // 响应头已到：此后由「空闲计时」判断，而不是总耗时
      clearTimeout(connectTimer);
      connectTimer = null;

      if (res.statusCode !== 200) {
        fail(new Error('HTTP ' + res.statusCode));
        return;
      }

      const total = Number(res.headers['content-length'] || 0);
      let received = 0;
      let lastEmit = 0;
      out = fs.createWriteStream(dest);
      touch();

      res.on('data', (chunk) => {
        received += chunk.length;
        if (received > MAX_BYTES) {
          fail(new Error('file too large'));
          return;
        }
        touch();
        // One IPC per chunk would fire thousands of times and measurably slow the transfer down;
        // the final chunk always goes out so the bar can reach 100%.
        // 每个数据块发一次 IPC 会触发上千次、实测拖慢传输；最后一块必定发出，保证进度条能满。
        const now = Date.now();
        if (onProgress && (now - lastEmit >= PROGRESS_INTERVAL_MS || (total > 0 && received >= total))) {
          lastEmit = now;
          onProgress(received, total);
        }
      });

      res.pipe(out);

      out.on('finish', () => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(stallTimer);
        if (onProgress) {
          // A short body is not an error here — the checksum decides that later
          // 少收了字节不在这里报错，交给后面的校验去判定
          onProgress(received, total || received);
        }
        resolve({ received, total });
      });
      out.on('error', (error) => fail(error));
      res.on('error', (error) => fail(error));
    });

    req.on('error', (error) => fail(error));
    req.end();
  });
}

function sha256File(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    fs.createReadStream(file)
      .on('data', (chunk) => hash.update(chunk))
      .on('end', () => resolve(hash.digest('hex')))
      .on('error', reject);
  });
}

/**
 * Download + verify. On failure the temp file is deleted; the error.code the caller receives decides
 * which message to show.
 *
 * @param {object} update
 * @param {(received:number,total:number)=>void} [onProgress]
 * @param {string} [mirrorId] Use a mirror route; omitted or 'direct' means the official host ·
 *   走镜像线路；省略或 'direct' 就是直连官方
 *
 * The mirror only affects "where the bytes come from"; SHA256 still comes from the Release, so even
 * if a proxy injects something into the stream, the final verification step catches it — that is the
 * premise for letting users pick a route at all. The checksum value itself is still fetched from the
 * official address and never follows the mirror.
 *
 * 下载 + 校验。失败时把临时文件删掉，调用方拿到的 error.code 决定提示文案。
 *
 * 镜像只影响「从哪拿字节」；SHA256 仍以 Release 公布的值为准，所以就算代理
 * 往流里塞了东西，最后一步校验也会把它拦下来——这也是敢让用户自选线路的前提。
 * 校验值本身仍然从官方地址取，不跟着镜像走。
 */
async function download(update, onProgress, mirrorId) {
  if (!update || !update.asset || !isTrustedAsset(update.asset.url)) {
    const error = new Error('bad update payload');
    error.code = 'badPayload';
    throw error;
  }
  let prefix = '';
  if (mirrorId && mirrorId !== 'direct') {
    const mirror = findMirror(mirrorId);
    if (!mirror) {
      const error = new Error('unknown mirror');
      error.code = 'badMirror';
      throw error;
    }
    prefix = mirror.prefix;
  }

  const dir = path.join(app.getPath('temp'), 'bridgetap-update', update.version);
  fs.mkdirSync(dir, { recursive: true });
  const dest = path.join(dir, update.asset.name);

  await downloadFile(update.asset.url, dest, onProgress, prefix);

  let expected = update.sha;
  if (!expected && update.shaUrl) {
    const res = await request(update.shaUrl, { accept: 'text/plain' });
    if (res.status !== 200) {
      const error = new Error('checksum download failed');
      error.code = 'noChecksum';
      throw error;
    }
    expected = parseSha(res.body, update.asset.name);
  }
  if (!expected) {
    const error = new Error('no checksum published');
    error.code = 'noChecksum';
    throw error;
  }

  const actual = (await sha256File(dest)).toLowerCase();
  if (actual !== expected) {
    try {
      fs.unlinkSync(dest);
    } catch {
      // Failing to delete is fine: the file name carries the version, so it gets re-downloaded
      // 删不掉也无所谓：文件名带版本号，下次会重新下
    }
    const error = new Error('checksum mismatch');
    error.code = 'checksumMismatch';
    throw error;
  }
  return { file: dest, sha: actual };
}

function psQuote(value) {
  return "'" + String(value).replace(/'/g, "''") + "'";
}

/**
 * Wait for this process to exit → back up the old exe → overwrite → start; on a failed overwrite,
 * roll back from the backup. PowerShell is invoked with -EncodedCommand (UTF-16LE + base64) so paths
 * containing Chinese don't get mangled.
 *
 * 等本进程退出 → 备份旧 exe → 覆盖 → 启动；覆盖失败就用备份回滚。
 * PowerShell 用 -EncodedCommand（UTF-16LE + base64），路径里有中文也不会乱码。
 */
function buildScript(target, file, pid) {
  return [
    "$ErrorActionPreference = 'Stop'",
    `Wait-Process -Id ${pid} -Timeout 45 -ErrorAction SilentlyContinue`,
    'Start-Sleep -Milliseconds 600',
    `$target = ${psQuote(target)}`,
    `$new = ${psQuote(file)}`,
    "$bak = $target + '.old'",
    "Copy-Item -LiteralPath $target -Destination $bak -Force -ErrorAction SilentlyContinue",
    'try {',
    '  Copy-Item -LiteralPath $new -Destination $target -Force',
    '} catch {',
    '  Copy-Item -LiteralPath $bak -Destination $target -Force -ErrorAction SilentlyContinue',
    '}',
    'Start-Process -FilePath $target',
    ''
  ].join('\r\n');
}

/**
 * Replace the running executable and restart.
 *
 * @param {string} file Verified temporary exe · 已校验的临时 exe
 * @returns {{ok:boolean, error?:string}}
 */
function apply(file) {
  if (!file || !fs.existsSync(file)) {
    return { ok: false, error: 'update file missing' };
  }
  // The real portable path is in PORTABLE_EXECUTABLE_FILE; process.execPath is the unpacked copy in
  // %TEMP%
  // portable 的真实路径在 PORTABLE_EXECUTABLE_FILE；process.execPath 是 %TEMP% 里的解压副本
  const target = process.env.PORTABLE_EXECUTABLE_FILE || process.execPath;
  if (!/\.exe$/i.test(target)) {
    return { ok: false, error: 'not a portable build' };
  }
  if (path.resolve(target) === path.resolve(file)) {
    return { ok: false, error: 'same file' };
  }

  const script = buildScript(target, file, process.pid);
  const encoded = Buffer.from(script, 'utf16le').toString('base64');
  try {
    spawn('powershell.exe', ['-NoProfile', '-WindowStyle', 'Hidden', '-EncodedCommand', encoded], {
      detached: true,
      stdio: 'ignore',
      windowsHide: true
    }).unref();
  } catch (error) {
    return { ok: false, error: error.message };
  }

  setTimeout(() => app.quit(), 400);
  return { ok: true };
}

module.exports = {
  check,
  download,
  apply,
  probeMirrors,
  MIRRORS,
  compareVersions,
  parseVersion,
  parseSha
};

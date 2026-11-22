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
const logTail = require('./inject/log-tail');

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
 * 前缀式代理：`prefix + 原始 GitHub 完整 URL` 即可转发。
 *
 * 三条固定来源，不多不少。顺序即默认优先级，测速结果还会再按吞吐重排。
 *
 * 2026-11 实测（Release 资产 + api.github.com）：
 *   · gh-proxy.org      —— API 直接 200、资产直接 206（认 Range），链路最短，排第一
 *   · github.chenc.dev  —— 资产和 API 都能用，但会把 `https://` 折叠成 `https:/` 回 301，
 *                          靠客户端自动跟随，多一跳
 *   · ghp.keleyaa.com   —— 资产 302 到 release-assets 后可用；API 与别人共用出口 IP，
 *                          容易撞 403 限流
 */
const MIRRORS = [
  {
    id: 'gh-proxy-org',
    name: 'GH-Proxy.org',
    host: 'gh-proxy.org',
    prefix: 'https://gh-proxy.org/',
    // api = also a candidate for "check for updates". It answers api.github.com directly with JSON,
    // no redirect, so it ranks first.
    // api = 也参与"查更新"的候选。它直接回 api.github.com 的 JSON、不经重定向，所以排第一。
    api: true
  },
  {
    id: 'chenc',
    name: 'chenc.dev',
    host: 'github.chenc.dev',
    prefix: 'https://github.chenc.dev/',
    // Redirects `/https://x` to `/https:/x` (301) before serving; Electron follows it, at the cost
    // of one extra round trip.
    // 会先把 `/https://x` 301 到 `/https:/x` 再服务；客户端自动跟随，代价是多一跳。
    api: true
  },
  {
    id: 'keleyaa',
    name: 'keleyaa',
    host: 'ghp.keleyaa.com',
    prefix: 'https://ghp.keleyaa.com/',
    // Shares its egress IP with other users, so api.github.com often comes back 403 (rate limit);
    // asset downloads still work through a 302 to release-assets. Fails in under a second, so it
    // doesn't drag the check out.
    // 出口 IP 与他人共用，api.github.com 常回 403（限流）；资产下载走 302 到 release-assets 仍可用。
    // 失败在 1 秒内返回，不会把检查拖长。
    api: true
  }
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
 * while the slowest route can drag on for 10 seconds or more. Waiting on Promise.all means the
 * list for 10 seconds — a pure loss of experience.
 *
 * 并行测所有线路，按「可用优先 → 速度降序 → 延迟升序」排序。
 * 不可用的排在后面，渲染层仍然展示，用户能看到哪条挂了。
 *
 * 为什么要流式回调：三条线路并行跑，最快的那条 1 秒就有结果，但最慢的那条可能
 * 要 10 秒以上。等 Promise.all 意味着用户对着空列表干等——这是纯粹的体验损失。
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
    // cache: false keeps Chromium's HTTP cache out of the transfer. GitHub answers the direct route
    // with `Cache-Control: no-cache`, but every entry in MIRRORS replies `public, max-age=60` — a
    // cached 115 MB body would be "downloaded" at disk speed, leaving a progress bar that fills in
    // seconds while nothing crosses the network.
    // cache: false 把 Chromium 的 HTTP 缓存彻底排除。直连时 GitHub 给的是 no-cache，但 MIRRORS
    // 里每一条都回 `public, max-age=60`；一旦命中缓存，115 MB 就按磁盘速度"下载"完了——
    // 进度条几秒跑满，网络上却什么都没发生。
    const req = net.request({ url: (prefix || '') + url, cache: false });
    req.setHeader('User-Agent', UA);
    req.setHeader('Accept', 'application/octet-stream');
    req.setHeader('Cache-Control', 'no-cache');
    req.setHeader('Pragma', 'no-cache');

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
        // Fewer bytes than the advertised Content-Length means the body was cut short. The checksum
        // would catch it as well, but only after the bar has already reached 100% — the user would
        // read that as "download finished". Fail here so the message says truncated, not mismatch.
        // 收到的字节少于 Content-Length 说明响应被截断。校验值当然也能拦住，但那是进度条走满
        // 之后的事，用户已经当成"下载完成"了。在这里就失败，报的是截断而不是校验失败。
        if (total > 0 && received !== total) {
          fail(new Error(`truncated (${received}/${total})`));
          return;
        }
        settled = true;
        clearTimeout(stallTimer);
        if (onProgress) {
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
  const logFile = path.join(app.getPath('temp'), 'bridgetap-update', 'apply.log');

  // Start from a clean slate: a leftover file from an earlier attempt must never be mistaken for
  // this transfer's result.
  // 从干净状态开始：上一次留下的文件绝不能被当成这次的下载结果。
  try {
    fs.rmSync(dest, { force: true });
  } catch {
    // Locked or already gone; the write below truncates it anyway
    // 被占用或本来就不存在，下面的写入同样会截断
  }

  const startedAt = Date.now();
  let transferred = 0;
  appendLog(logFile, `download start route=${mirrorId || 'direct'}`);
  let stats;
  try {
    stats = await downloadFile(update.asset.url, dest, (received, total) => {
      transferred = received;
      if (onProgress) {
        onProgress(received, total);
      }
    }, prefix);
  } catch (error) {
    appendLog(logFile, `download failed ${error.code || ''} ${error.message} after ${transferred} bytes`);
    try {
      fs.rmSync(dest, { force: true });
    } catch {
      // Best effort
      // 尽力而为
    }
    throw error;
  }

  const seconds = Math.max(0.001, (Date.now() - startedAt) / 1000);
  appendLog(logFile, `download done ${stats.received}/${stats.total} bytes in ${seconds.toFixed(1)}s`
    + ` (${(stats.received / 1048576 / seconds).toFixed(2)} MB/s) route=${mirrorId || 'direct'}`);

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
    appendLog(logFile, `checksum mismatch expected=${expected} actual=${actual}`);
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
  appendLog(logFile, `checksum ok ${actual}`);
  return { file: dest, sha: actual };
}

/**
 * Append one line to the updater's own log. Best effort: a log failure must never change the
 * outcome of an update.
 *
 * 往更新日志里追加一行。尽力而为：日志写不进去也不该影响更新本身。
 */
function appendLog(file, line) {
  try {
    fs.appendFileSync(file, new Date().toISOString() + ' ' + line + '\n', 'utf8');
  } catch {
    // Ignored on purpose
    // 故意忽略
  }
}

/**
 * Builds the deferred-swap script that runs after this process is gone.
 *
 * Why a helper at all: the portable shell keeps its own exe open for as long as the app runs
 * (verified with a separate process — Windows refuses the rename with a sharing violation even
 * though renaming a *signed* running image works). So the swap has to happen after we exit.
 *
 * Why it is wrapped in cmd.exe: a bare `spawn('powershell.exe', …, {detached: true})` never
 * executed a single statement on the test machine; going through `cmd.exe /c` does. `-EncodedCommand`
 * keeps Chinese paths intact.
 *
 * 生成"退出之后才执行"的替换脚本。
 *
 * 为什么必须有助手：便携外壳在运行期间一直占着自己的 exe（用另一个进程验证过，
 * 重命名直接报共享冲突；而给已签名的运行中镜像改名是成功的）。所以替换只能等我们退出。
 *
 * 为什么外面包一层 cmd.exe：本机上裸的 `spawn('powershell.exe', …, {detached:true})`
 * 一条语句都不会执行，经 `cmd.exe /c` 转一手就能跑。`-EncodedCommand` 保证中文路径不乱码。
 */
function buildSwapScript(target, file, logFile) {
  const quote = (value) => "'" + String(value).replace(/'/g, "''") + "'";
  return [
    "$ErrorActionPreference = 'Stop'",
    `$log = ${quote(logFile)}`,
    'function Note($m) { try { Add-Content -LiteralPath $log -Value ((Get-Date).ToString("HH:mm:ss.fff") + " " + $m) } catch { } }',
    'Note "helper start"',
    'Start-Sleep -Milliseconds 900',
    `$target = ${quote(target)}`,
    `$new = ${quote(file)}`,
    "$bak = $target + '.old'",
    'Note ("target=" + $target)',
    '$done = $false',
    // The shell releases the file within a second of our exit; keep trying in case it lingers.
    // 外壳在我们退出后一秒内就会松开文件；万一慢一点也重试，而不是一次失败就放弃。
    'for ($i = 0; $i -lt 120; $i++) {',
    '  try {',
    '    if (Test-Path -LiteralPath $bak) { Remove-Item -LiteralPath $bak -Force -ErrorAction SilentlyContinue }',
    '    Move-Item -LiteralPath $target -Destination $bak -Force',
    '    $done = $true',
    '    break',
    '  } catch {',
    '    Start-Sleep -Milliseconds 500',
    '  }',
    '}',
    'if (-not $done) { Note "rename failed after 60s"; exit 1 }',
    'Note "rename ok"',
    'try {',
    '  Copy-Item -LiteralPath $new -Destination $target -Force',
    '  Note "copy ok"',
    '} catch {',
    '  Note ("copy failed: " + $_.Exception.Message)',
    '  Move-Item -LiteralPath $bak -Destination $target -Force -ErrorAction SilentlyContinue',
    '  exit 1',
    '}',
    'Note "starting new build"',
    'Start-Process -FilePath $target',
    ''
  ].join('\r\n');
}

/**
 * Launch the deferred helper and return whether it was started.
 * 启动延迟替换助手，返回是否已拉起。
 */
function spawnSwapHelper(target, file, logFile) {
  const script = buildSwapScript(target, file, logFile);
  const encoded = Buffer.from(script, 'utf16le').toString('base64');
  try {
    const child = spawn('cmd.exe',
      ['/c', 'powershell.exe', '-NoProfile', '-WindowStyle', 'Hidden', '-EncodedCommand', encoded],
      { detached: true, stdio: 'ignore', windowsHide: true });
    child.on('error', (error) => appendLog(logFile, `helper spawn failed: ${error.code || error.message}`));
    child.unref();
    appendLog(logFile, `helper spawned via cmd pid=${child.pid || '(unknown)'}`);
    return true;
  } catch (error) {
    appendLog(logFile, `helper spawn threw: ${error.message}`);
    return false;
  }
}

/**
 * Replace the running portable exe and restart.
 *
 * Two paths, in this order:
 *   1. In-process: rename the target to `.old`, copy the verified exe onto the freed path, then
 *      `app.relaunch`. Only works when nothing holds the image.
 *   2. Deferred: the portable shell keeps its own exe open while the app runs, so in practice the
 *      rename above fails with EBUSY. In that case a helper is launched (via `cmd.exe /c`,
 *      see buildSwapScript) that waits for this process to exit, retries the rename, copies the
 *      file and starts the new build.
 *
 * 两条路径，按顺序尝试：
 *   1. 进程内：把目标改名成 `.old`，把校验过的 exe 复制到腾出来的路径，再 app.relaunch。
 *      只有没人占用镜像时才会成功。
 *   2. 延迟替换：便携外壳在运行期间一直占着自己的 exe，所以实际运行时第 1 步基本都会 EBUSY。
 *      这时启动一个助手（经 `cmd.exe /c`，见 buildSwapScript），等本进程退出后重试改名、
 *      复制、拉起新版。
 *
 * @param {string} file Verified temporary exe · 已校验的临时 exe
 * @returns {{ok:boolean, error?:string}}
 */
function apply(file) {
  if (!file || !fs.existsSync(file)) {
    return { ok: false, error: 'update file missing' };
  }
  // Only a portable build knows which file to swap; running from source there is nothing to replace.
  // 只有便携版知道自己要替换哪个文件；从源码跑没有可替换的对象。
  const target = process.env.PORTABLE_EXECUTABLE_FILE || '';
  if (!target || !/\.exe$/i.test(target)) {
    return { ok: false, error: 'not a portable build' };
  }
  if (path.resolve(target) === path.resolve(file)) {
    return { ok: false, error: 'same file' };
  }

  const logDir = path.join(app.getPath('temp'), 'bridgetap-update');
  const logFile = path.join(logDir, 'apply.log');
  try {
    fs.mkdirSync(logDir, { recursive: true });
  } catch {
    // Logging still works if the directory already existed; if not, applyLog swallows the failure
    // 目录已存在时不影响；真建不出来也只是日志写不进，applyLog 会吞掉
  }
  appendLog(logFile, `apply() target=${target} new=${file} pid=${process.pid}`);

  const backup = target + '.old';

  // Fast path — only succeeds when nothing holds the image. On a normal portable launch the shell
  // owns its own exe, this throws immediately, and the deferred helper takes over.
  // 快路径——只有没人占着文件时才会成功。便携版正常启动时外壳占着自己的 exe，
  // 这里会立刻抛错，随后交给延迟替换的助手。
  try {
    if (fs.existsSync(backup)) {
      fs.rmSync(backup, { force: true });
    }
    fs.renameSync(target, backup);
    fs.copyFileSync(file, target);
    appendLog(logFile, `swapped in-process (${fs.statSync(target).size} bytes)`);
    try {
      // Drop the mutex before relaunching: the new instance asks for the same lock immediately
      // 先松开互斥量再重启：新实例一启动就要抢同一把锁
      app.releaseSingleInstanceLock();
      app.relaunch({ execPath: target, args: [] });
      appendLog(logFile, 'relaunch scheduled (in-process)');
    } catch (error) {
      appendLog(logFile, `relaunch failed: ${error.message}`);
    }
    setTimeout(() => app.quit(), 300);
    return { ok: true };
  } catch (error) {
    appendLog(logFile, `in-process swap blocked: ${error.code || error.message}`);
    try {
      if (!fs.existsSync(target) && fs.existsSync(backup)) {
        fs.renameSync(backup, target);
        appendLog(logFile, 'rolled back');
      }
    } catch {
      appendLog(logFile, 'rollback skipped');
    }
  }

  // Normal path: the file stays locked until this process is gone, so let the helper finish it.
  // 常规路径：本进程退出前文件一直锁着，交给助手在退出后完成。
  if (!spawnSwapHelper(target, file, logFile)) {
    return { ok: false, error: 'could not start the update helper' };
  }
  appendLog(logFile, 'quitting, the helper will finish the swap');
  setTimeout(() => app.quit(), 300);
  return { ok: true };
}

/**
 * Keep the last update's evidence out of the directory that is about to be deleted.
 *
 * <p>`apply.log` is the only record of a swap attempt: which step ran, what the helper said, why it
 * gave up. It lives in the cache directory, which is cleared on every launch — so the one thing a
 * user does after a failed update (close the game, reopen the launcher to look) used to erase the
 * evidence. Copying it into the log directory keeps it next to agent.log, where the exporter already
 * knows how to collect it.
 *
 * <p>Copied, not moved: the deletion below takes the original with the rest of the directory, so a
 * failed copy still leaves the file where it was until the next launch. An absent or empty log is
 * the normal case and is skipped, so a preserved copy from an earlier failure is not overwritten by
 * a run that never attempted an update.
 *
 * 把上一次更新的证据挪出即将被删的目录。
 *
 * <p>`apply.log` 是一次替换尝试的唯一记录：跑到哪一步、助手说了什么、为什么放弃。它在缓存目录里，
 * 而那个目录每次启动都被清掉——于是用户在更新失败后唯一会做的事（关掉游戏、重开启动器查看），
 * 恰好把证据抹掉。复制到日志目录就能和 agent.log 放在一起，导出器本来就收那里。
 *
 * <p>是复制而不是移动：下面的删除会连目录一起带走原件，复制失败时文件仍然留在原处直到下次启动。
 * 没有日志、或者日志是空的，才是常态，直接跳过——免得一次根本没试过更新的启动，
 * 覆盖掉上次失败留下的那份。
 */
function preserveApplyLog() {
  const source = path.join(app.getPath('temp'), 'bridgetap-update', 'apply.log');
  try {
    const stat = fs.statSync(source);
    if (!stat.isFile() || stat.size === 0) {
      return;
    }
    const target = path.join(logTail.logDir(), 'apply.log');
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(source, target);
  } catch {
    // Nothing to preserve — see above
    // 没有可保留的东西——见上
  }
}

/**
 * Delete the update download cache.
 *
 * <p>Every download lands in `%TEMP%\bridgetap-update\<version>\` and is a full installer — 115 MB
 * per version, per machine, never removed by anything. One field machine had 221 MB of stale
 * installers. Nothing needs them once `apply()` has copied the file, so the next launch clears the
 * whole directory — after rescuing the one file in it that is worth reading.
 *
 * <p>Three properties are deliberate: a missing directory is the normal case and is not an error; a
 * failed deletion is swallowed (a file held by a virus scanner or a crashed helper is not a reason
 * to disturb startup); and the call is asynchronous, so a slow disk never delays the first frame.
 *
 * 清掉更新下载缓存。
 *
 * <p>每次下载都落在 `%TEMP%\bridgetap-update\<版本>\`，是完整的安装包——每个版本 115 MB，逐台机器累积，
 * 没有任何东西会删它。现场一台机器上堆了 221 MB。`apply()` 复制完文件之后它们就没用了，
 * 所以每次启动把这整个目录清掉——先把里面唯一值得看的那份救出来。
 *
 * <p>三点是刻意的：目录不存在是常态，不算错误；删除失败一律吞掉（被杀软或崩溃的助手占住的文件，
 * 不该成为打扰启动的理由）；调用是异步的，慢盘不会拖住首帧。
 */
function clearUpdateCache() {
  preserveApplyLog();
  try {
    fs.rm(path.join(app.getPath('temp'), 'bridgetap-update'), { recursive: true, force: true }, () => {
      // Ignored on purpose: force already covers "not there", and the next launch retries the rest
      // 故意忽略：force 已经处理了"不存在"，剩下的下次启动会再试
    });
  } catch {
    // Never let cleanup reach the caller — startup owns the failure budget
    // 清理动作绝不上抛——启动阶段不承担这个风险
  }
}

module.exports = {
  check,
  download,
  apply,
  clearUpdateCache,
  probeMirrors,
  MIRRORS,
  compareVersions,
  parseVersion,
  parseSha
};

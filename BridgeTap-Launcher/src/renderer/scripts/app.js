/**
 * App assembly layer: translates "events from the injection service" into "rendering + motion".
 *
 * Dependencies flow one way:
 *   app.js  →  ui/render.js (draws DOM)
 *           →  ui/motion.js (plays animations)
 *           →  window.bridge (the only place that knows what IPC looks like)
 *
 * render and motion know neither each other nor bridge; the injection module has no idea a frontend exists.
 *
 * 应用装配层：把「注入服务发来的事件」翻译成「渲染 + 动效」。
 *
 * 依赖方向是单向的：
 *   app.js  →  ui/render.js（画 DOM）
 *           →  ui/motion.js（播动画）
 *           →  window.bridge（只有这里知道 IPC 长什么样）
 *
 * render 与 motion 互不认识，也不认识 bridge；注入模块更不知道前端存在。
 */

import * as render from './ui/render.js';
import * as motion from './ui/motion.js';
import * as settingsView from './ui/settings-view.js';
import { createTheme, nextMode } from './ui/theme.js';
import { toast } from './ui/toast.js';
import { initI18n, applyStatic, t, locale, locales } from './i18n.js';

const bridge = window.bridge;

// Theme: write <html data-mode / data-theme> as soon as the module loads, do not wait for info to come back
// 主题：模块一加载就先把 <html data-mode / data-theme> 写上，别等 info 回来才画
const theme = createTheme();

const refs = {
  grid: document.getElementById('deviceGrid'),
  empty: document.getElementById('emptyState'),
  count: document.getElementById('targetCount'),
  logBody: document.getElementById('logBody'),
  viewDevices: document.getElementById('viewDevices'),
  viewLog: document.getElementById('viewLog'),
  viewSettings: document.getElementById('viewSettings'),
  viewAbout: document.getElementById('viewAbout'),
  appSettingsBody: document.getElementById('appSettingsBody'),
  modSettingsBody: document.getElementById('modSettingsBody'),
  eyebrow: document.getElementById('pageEyebrow'),
  headline: document.getElementById('pageHeadline'),
  subhead: document.getElementById('pageSubhead'),
  segmented: document.querySelector('.segmented'),
  segmentTrack: document.querySelector('.segmented__track'),
  segments: Array.from(document.querySelectorAll('.segmented__item')),
  btnRefresh: document.getElementById('btnRefresh'),
  btnInject: document.getElementById('btnInject'),
  btnLabel: document.querySelector('#btnInject .btn__label'),
  btnTheme: document.getElementById('btnTheme'),
  btnClearLog: document.getElementById('btnClearLog'),
  btnExportLog: document.getElementById('btnExportLog'),
  btnMaximize: document.getElementById('btnMaximize'),
  btnMinimize: document.getElementById('btnMinimize'),
  btnClose: document.getElementById('btnClose'),
  toasts: document.getElementById('toasts'),
  tip: document.getElementById('footerTip'),
  updDialog: document.getElementById('updateDialog'),
  updTitle: document.getElementById('updTitle'),
  updNotes: document.getElementById('updNotes'),
  updStatus: document.getElementById('updStatus'),
  updProg: document.getElementById('updProg'),
  updFill: document.getElementById('updFill'),
  updPct: document.getElementById('updPct'),
  updBytes: document.getElementById('updBytes'),
  updSpeed: document.getElementById('updSpeed'),
  updCancel: document.getElementById('updCancel'),
  updGo: document.getElementById('updGo'),
  mirrorPanel: document.getElementById('mirrorPanel'),
  mirrorHint: document.getElementById('mirrorHint'),
  mirrorList: document.getElementById('mirrorList'),
  modal: document.getElementById('confirmModal'),
  modalScrim: document.getElementById('confirmScrim'),
  modalTitle: document.getElementById('confirmTitle'),
  modalBody: document.getElementById('confirmBody'),
  modalCancel: document.getElementById('confirmCancel'),
  modalOk: document.getElementById('confirmOk')
};

const state = {
  targets: [],
  /**
   * Fingerprint of the previous process list, used to skip repaints when nothing changed.
   *
   * 上一次进程列表的指纹，用来跳过无变化的重绘
   */
  // null rather than '': the signature of an empty list is also '', so using '' would make the first refresh
  // look like "no change" and be skipped, leaving #targetCount stuck on the hardcoded Chinese line in index.html.
  // null 而不是 ''：空列表的签名也是 ''，用 '' 会让第一次刷新被当成「没变化」直接跳过，
  // #targetCount 就永远停在 index.html 里那句写死的中文上。
  targetSignature: null,
  selectedPid: null,
  view: 'devices',
  scene: 'idle',
  log: [],
  /**
   * Maximum number of lines kept in the log view (from the injector settings).
   *
   * 日志视图最多保留多少行（来自注入器设置）
   */
  logCap: 800,
  /**
   * Settings snapshot: { injector, mod }, fetched once when the settings page is first opened.
   *
   * 设置快照：{ injector, mod }，第一次打开设置页时拉一次
   */
  settings: null,
  /**
   * Version reported by the main process, shown at the bottom of the settings page.
   *
   * 主进程报上来的版本号，设置页底部显示
   */
  version: '',
  /**
   * PID already injected. In one JVM the agent can only be defined once, so while this PID is still present it must not be injected again;
   * once it disappears from the process list (game closed / restarted) it has to be cleared, otherwise "auto inject after restarting the game" never fires.
   *
   * 已经注进去的进程号。同一个 JVM 里 agent 只能定义一次，所以这个 PID 还在就不该再注；
   * 但它一旦从进程列表里消失（游戏被关/重启），就得清掉，否则「重启游戏后自动注入」永远不触发。
   */
  injectedPid: null,
  /**
   * PID being injected this time, used to match it in the attached / failed events.
   *
   * 这一次正在注入的进程号，用来在 attached / failed 事件里对应上
   */
  attemptPid: null,
  /**
   * PID whose injection failed: the same PID is not retried automatically, so one failure does not loop forever.
   *
   * 注入失败过的进程号：同一个 PID 不自动重试，免得失败一次就无限重注
   */
  failedPid: null
};

/* ── Events: from the main process · 事件：来自主进程 ─────── */

const SCENE_OF = {
  [bridge.PHASE.SCANNING]: 'scanning',
  [bridge.PHASE.READY]: 'idle',
  [bridge.PHASE.INJECTING]: 'injecting',
  [bridge.PHASE.ATTACHED]: 'attached',
  [bridge.PHASE.FAILED]: 'failed',
  [bridge.PHASE.IDLE]: 'idle'
};

function handleEvent(message) {
  const { kind, payload } = message || {};
  if (!kind) {
    return;
  }

  if (kind === 'phase') {
    setScene(SCENE_OF[payload.phase] || 'idle');
    return;
  }

  if (kind === 'targets') {
    const targets = payload.targets || [];

    // An injected process disappearing = the player closed / restarted the game. State must be reset here:
    // otherwise scene stays on attached, the auto-inject check is always blocked by this "already injected",
    // and the visible symptom is "after restarting the game a process is found but not injected automatically".
    // 注入过的进程不见了 = 玩家关了/重启了游戏。这时候必须把状态归零：
    // 否则 scene 一直停在 attached，自动注入的判断永远被这个"已经注完"挡掉，
    // 表现出来就是"重启游戏后扫到了进程但不自动注入"。
    const alive = new Set(targets.map((item) => item.pid));
    if (state.injectedPid !== null && !alive.has(state.injectedPid)) {
      state.injectedPid = null;
      state.failedPid = null;
      if (state.scene === 'attached' || state.scene === 'failed') {
        setScene('idle');
      }
    } else if (state.failedPid !== null && !alive.has(state.failedPid)) {
      state.failedPid = null;
    }

    // The background scans every 3 seconds; if the list has not changed, do not repaint — otherwise the cards
    // re-enter every 3 seconds and it flickers unbearably
    // 后台每 3 秒扫一次，列表没变就别重画——否则卡片每 3 秒重新入场一次，闪得没法看
    const signature = targets
      .map((item) => item.pid + ':' + item.client + ':' + item.injectable + ':' + item.title)
      .join('|');
    if (signature === state.targetSignature) {
      maybeScheduleAutoInject();
      return;
    }
    state.targetSignature = signature;
    state.targets = targets;
    const stillThere = state.targets.some((t) => t.pid === state.selectedPid && t.injectable);
    if (!stillThere) {
      state.selectedPid = (state.targets.find((t) => t.injectable) || {}).pid ?? null;
    }
    paintTargets();

    // The selected client changed (Lunar ↔ BLC); the settings page must switch to that client's config file
    // 选中的客户端换了（Lunar ↔ BLC），设置页要跟着换成那个客户端的配置文件
    if (state.view === 'settings') {
      const next = pickModTarget(state.targets);
      const prev = state.settingsTarget || {};
      if (next.pid !== prev.pid) {
        void loadSettings();
      }
    }

    maybeScheduleAutoInject();
    return;
  }

  if (kind === 'output') {
    appendOutput(payload);
    return;
  }

  if (kind === 'notice') {
    toast(refs.toasts, { level: payload.level || 'info', text: payload.text });
  }
}

function paintTip(scene) {
  // Show nothing when idle: the default "join a server and wait 30 seconds" line has been removed.
  // Empty string + hidden, so the <p> margin does not leave a blank strip.
  // 空闲时不显示任何东西：默认那句「先进服务器等 30 秒」已经删了。
  // 空字符串 + hidden，避免 <p> 的 margin 留出一条空白。
  const text =
    scene === 'attached'
      ? t('tip.attached')
      : scene === 'injecting'
        ? t('tip.injecting')
        : '';
  refs.tip.textContent = text;
  refs.tip.hidden = !text;
}

/* ── Custom confirm modal · 自绘确认弹窗 ─────────────────── */

let modalResolver = null;

/**
 * @param {{title:string, body:string, okText:string, cancelText?:string, tone?:'normal'|'danger'}} options
 * @returns {Promise<boolean>} true on confirm, false on cancel · 确认 true，取消 false
 */
function openModal(options) {
  const tone = options.tone === 'danger' ? 'danger' : 'normal';
  refs.modalTitle.textContent = options.title;
  refs.modalBody.textContent = options.body;
  refs.modalOk.textContent = options.okText;
  refs.modalOk.classList.toggle('modal__btn--danger', tone === 'danger');
  refs.modalOk.classList.toggle('modal__btn--ghost', tone !== 'danger');
  refs.modalCancel.textContent = options.cancelText || '';
  refs.modalCancel.hidden = !options.cancelText;
  refs.modal.hidden = false;
  // Force a reflow; otherwise is-open is added right after hidden is removed and the transition never runs
  // 强制一次 reflow，否则 hidden 刚取消就加 is-open，过渡不会跑
  void refs.modal.offsetWidth;
  refs.modal.classList.add('is-open');
  return new Promise((resolve) => {
    modalResolver = resolve;
  });
}

function closeModal(result) {
  refs.modal.classList.remove('is-open');
  window.setTimeout(() => {
    refs.modal.hidden = true;
  }, 240);
  const resolve = modalResolver;
  modalResolver = null;
  if (resolve) {
    resolve(result);
  }
}

/* ── Update dialog · 更新弹窗 ────────────────────────────── */

let pendingUpdate = null;
let updateBusy = false;
/**
 * Currently selected route: 'direct' or a mirror id; always 'direct' while the mirror panel is closed.
 *
 * 当前选中的线路：'direct' 或镜像 id；镜像面板没打开时恒为 'direct'
 */
let chosenRoute = 'direct';
/**
 * A probe is running; prevents repeated probes from rapid clicks.
 *
 * 测速进行中，避免连点重复探测
 */
let probeBusy = false;
/**
 * Last probe result {routes, results}; reused when the dialog is reopened instead of re-probing every time.
 *
 * 最近一次的测速结果 {routes, results}，弹窗重开时复用，不必每次都重测
 */
let probeCache = null;
/**
 * Route list cache: the copy given by the main process, unchanged for the lifetime of the process.
 *
 * 线路清单缓存：主进程给的那份，整个进程生命周期内不变
 */
let routeIndex = null;
/**
 * Probe round. The user may close the dialog mid-probe (or reopen it for another version), and results from
 * that round must not be written to the UI any more. Incremented at the start of every probe; callbacks that do not match are dropped.
 *
 * 测速轮次。用户可能在测速中途关掉弹窗（或换了一个版本重开），
 * 那一轮回来的结果就不能再往界面上写。每次开测自增，回调里对不上就直接丢弃。
 */
let probeToken = 0;

function paintUpdateStatus(text) {
  refs.updStatus.textContent = text || '';
  refs.updStatus.hidden = !text;
}

/* ── Transfer readout · 下载实时读数 ──────────────────────── */

/** Last sample used to derive the transfer speed · 用来算瞬时速度的上一次采样 */
let progressState = { lastBytes: 0, lastTime: 0, speed: 0 };

function formatBytes(bytes) {
  const n = Number(bytes) || 0;
  if (n < 1024) {
    return n + ' B';
  }
  const units = ['KB', 'MB', 'GB'];
  let value = n / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return (value >= 100 ? value.toFixed(0) : value.toFixed(1)) + ' ' + units[unit];
}

/** Clear the readout before a transfer starts · 传输开始前把读数清零 */
function resetProgress(total) {
  progressState = { lastBytes: 0, lastTime: 0, speed: 0 };
  refs.updFill.style.width = '0%';
  refs.updPct.textContent = '0%';
  refs.updBytes.textContent = total ? '0 B / ' + formatBytes(total) : '';
  refs.updSpeed.textContent = '';
}

/**
 * Paint one progress sample: bar, percent, downloaded/total, current speed.
 *
 * The speed is smoothed (EMA) on purpose — raw per-sample deltas swing between 0 and 20 MB/s and
 * make the number unreadable.
 * 画一次进度：进度条、百分比、已下载/总大小、当前速度。速度做指数平滑，原始逐次差值会在
 * 0 到 20 MB/s 之间乱跳，直接显示没法看。
 */
function paintProgress(received, total) {
  const now = performance.now();
  const pct = total > 0 ? Math.min(100, (received / total) * 100) : 0;

  refs.updFill.style.width = pct.toFixed(1) + '%';
  refs.updPct.textContent = Math.round(pct) + '%';
  refs.updBytes.textContent = total > 0
    ? formatBytes(received) + ' / ' + formatBytes(total)
    : formatBytes(received);

  // Sample at most every 200 ms, and only advance the sample when a speed is actually computed —
  // advancing it on every call would keep the interval under the threshold and the speed would
  // never appear at all.
  // 最多每 200 ms 采一次样，且只有真正算出速度时才推进采样点 ——
  // 每次回调都推进的话间隔永远够不到阈值，速度就永远不会显示。
  if (progressState.lastTime) {
    const seconds = (now - progressState.lastTime) / 1000;
    if (seconds >= 0.2) {
      const delta = received - progressState.lastBytes;
      if (delta >= 0) {
        const instant = delta / seconds;
        progressState.speed = progressState.speed
          ? progressState.speed * 0.6 + instant * 0.4
          : instant;
      }
      progressState.lastBytes = received;
      progressState.lastTime = now;
    }
  } else {
    progressState.lastBytes = received;
    progressState.lastTime = now;
  }

  if (progressState.speed > 0) {
    refs.updSpeed.textContent = formatSpeed(progressState.speed);
  }
}

/**
 * Bytes/sec → human readable; below 1 MB/s show KB, which reads more intuitively.
 *
 * 字节/秒 → 人类可读；低于 1 MB/s 用 KB 显示，读数更直观
 */
function formatSpeed(bytesPerSec) {
  const value = Number(bytesPerSec) || 0;
  if (value >= 1024 * 1024) {
    return (value / 1024 / 1024).toFixed(1) + ' MB/s';
  }
  return Math.max(1, Math.round(value / 1024)) + ' KB/s';
}

/**
 * Failure reason → localised short label; these are all the shapes a user actually runs into.
 *
 * 失败原因 → 本地化短标签；这几种是用户实际会碰到的全部形态
 */
function failureText(result) {
  switch (result.code) {
    case 'timeout':
      return t('update.mirror.timeout');
    case 'http':
      return t('update.mirror.http', result.status || '');
    case 'html':
      return t('update.mirror.notProxy');
    case 'slow':
      return t('update.mirror.slow');
    default:
      return t('update.mirror.failed');
  }
}

/**
 * Draw one route row.
 * Unusable rows are drawn too (greyed out + reason); otherwise the user just sees "one row is missing" and
 * never learns why the route they picked is gone.
 *
 * 画一行线路。
 * 不可用的行也画出来（置灰 + 原因），否则用户只会看到"少了一条"，不知道自己选的那条为什么没了。
 */
function buildMirrorRow(route, result) {
  const row = document.createElement('button');
  row.type = 'button';
  row.className = 'mrow';
  row.dataset.route = route.id;
  row.setAttribute('role', 'radio');

  const radio = document.createElement('span');
  radio.className = 'mrow__radio';
  radio.setAttribute('aria-hidden', 'true');

  const main = document.createElement('span');
  main.className = 'mrow__main';
  const name = document.createElement('span');
  name.className = 'mrow__name';
  name.textContent = route.name;
  const host = document.createElement('span');
  host.className = 'mrow__host';
  host.textContent = route.host;
  main.append(name, host);

  const stats = document.createElement('span');
  stats.className = 'mrow__stats';
  if (!result) {
    const waiting = document.createElement('span');
    waiting.className = 'mrow__stat mrow__stat--bad';
    waiting.textContent = t('update.mirror.pending');
    stats.append(waiting);
    row.disabled = true;
  } else if (!result.ok) {
    const bad = document.createElement('span');
    bad.className = 'mrow__stat mrow__stat--bad';
    bad.textContent = failureText(result);
    stats.append(bad);
    row.disabled = true;
  } else if (result.latency) {
    const ping = document.createElement('span');
    ping.className = 'mrow__stat';
    ping.textContent = result.latency + ' ms';
    const speed = document.createElement('span');
    speed.className = 'mrow__stat';
    speed.textContent = formatSpeed(result.speed);
    stats.append(ping, speed);
  }
  // Direct connection is not probed, latency is empty, so the stats area is left blank by design
  // 直连不测速，latency 为空，统计区自然留白

  row.append(radio, main, stats);
  row.setAttribute('aria-checked', 'false');
  row.addEventListener('click', () => {
    if (row.disabled) {
      return;
    }
    chosenRoute = route.id;
    paintRouteSelection();
    refs.mirrorHint.textContent = t('update.mirror.chosen', route.name);
  });
  return row;
}

function paintRouteSelection() {
  refs.mirrorList.querySelectorAll('.mrow').forEach((row) => {
    const on = row.dataset.route === chosenRoute;
    row.classList.toggle('is-selected', on);
    row.setAttribute('aria-checked', on ? 'true' : 'false');
  });
}

/**
 * Create one row; the direct row is the permanent baseline entry and carries no probe data.
 *
 * 新建一行；直连那行是常驻的基准项，不带测速数据。
 */
function makeDirectRow() {
  return buildMirrorRow(
    { id: 'direct', name: t('update.mirror.direct'), host: 'github.com' },
    { ok: true, latency: null }
  );
}

/**
 * Lay out the waiting rows: direct connection plus one "pending" row per route.
 * List the routes to be probed up front, so the user knows what is being waited on instead of staring at a blank area.
 *
 * 铺等待行：直连 + 每条线路一行"等待中"。
 * 一进来就先把要测的线路列出来，用户知道在等哪几条，而不是对着空白等。
 */
function paintWaitingList(routes) {
  refs.mirrorList.textContent = '';
  refs.mirrorList.append(makeDirectRow());
  for (const route of routes || []) {
    refs.mirrorList.append(buildMirrorRow(route, null));
  }
  paintRouteSelection();
}

/**
 * Update one row's stats area in place, without reflowing or rebuilding the DOM.
 * The key to streaming backfill: when a route finishes, only the numbers on the right of its own row change,
 * other rows do not move, so the list does not jump around during probing.
 *
 * 就地更新某一行的统计区，不重排、不重建 DOM。
 * 流式回填的关键：某条线路测完只动它自己那一行的右侧数字，别的行纹丝不动，
 * 列表就不会在测速过程中跳来跳去。
 */
function updateMirrorRow(result) {
  const row = refs.mirrorList.querySelector('.mrow[data-route="' + result.id + '"]');
  if (!row) {
    return;
  }
  const stats = row.querySelector('.mrow__stats');
  stats.textContent = '';
  if (result.ok) {
    row.disabled = false;
    const ping = document.createElement('span');
    ping.className = 'mrow__stat';
    ping.textContent = result.latency + ' ms';
    const speed = document.createElement('span');
    speed.className = 'mrow__stat';
    speed.textContent = formatSpeed(result.speed);
    stats.append(ping, speed);
  } else {
    row.disabled = true;
    const bad = document.createElement('span');
    bad.className = 'mrow__stat mrow__stat--bad';
    bad.textContent = failureText(result);
    stats.append(bad);
  }
}

/**
 * All probes done: reorder by "usable → speed → latency".
 *
 * This deliberately does **not** change the selection automatically. The default is always the direct
 * connection; whichever route the user clicks is the one used — otherwise you get "shows direct selected
 * but actually went through a third-party proxy", and it happens silently.
 * Only one case moves the selection: the route the user picked turns out unusable, then fall back to direct.
 *
 * 全部测完：按「可用 → 速度 → 延迟」重排。
 *
 * 这里**不**自动改选中项。默认永远是直连，用户点哪条才算哪条——
 * 否则会出现"看着选的是直连、实际走了第三方代理"这种事，而且是静默发生的。
 * 只有一种情况会动选择：用户选中的那条测出来不可用，退回直连。
 */
function settleProbe(results) {
  const usable = (results || []).filter((item) => item.ok);
  refs.mirrorList.textContent = '';
  refs.mirrorList.append(makeDirectRow());
  for (const item of results || []) {
    refs.mirrorList.append(buildMirrorRow(
      { id: item.id, name: item.name, host: item.host },
      item
    ));
  }

  if (chosenRoute !== 'direct' && !usable.some((item) => item.id === chosenRoute)) {
    chosenRoute = 'direct';
  }
  refs.mirrorHint.textContent = usable.length
    ? t('update.mirror.pick')
    : t('update.mirror.allFailed');
  paintRouteSelection();
}

function setProbing(on) {
  probeBusy = on;
  refs.mirrorPanel.classList.toggle('is-probing', on);
  if (on) {
    refs.mirrorHint.textContent = t('update.mirror.probing');
  }
}

/**
 * Expand the mirror panel and start probing.
 *
 * Timing: expand → lay out waiting rows → send requests; each finished route is backfilled in place, and once
 * everything is back the list is reordered + the fastest picked. Expanding before requesting matters — the user
 * sees the list move within 100ms of clicking, instead of staring at a button for 12 seconds.
 *
 * 展开镜像面板并测速。
 *
 * 时序：展开 → 铺等待行 → 发请求；每测完一条就地回填，全部回来再重排 + 自动选最快。
 * 先展开再请求很重要——用户点下去 100ms 内就能看到列表动起来，而不是盯着按钮等 12 秒。
 */
async function openMirrorPanel() {
  if (!pendingUpdate || probeBusy || updateBusy) {
    return;
  }
  const assetUrl = pendingUpdate.asset && pendingUpdate.asset.url;
  if (!assetUrl) {
    return;
  }

  refs.mirrorPanel.hidden = false;
  refs.updDialog.classList.add('is-mirroring');

  if (probeCache) {
    settleProbe(probeCache);
    return;
  }

  setProbing(true);

  // The route list has a single source, the main process; the renderer does not hardcode its own — adding/removing routes only touches updater.js
  // 线路清单是主进程的唯一来源，渲染层不自己写死一份——加/删线路只改 updater.js
  let routes = routeIndex || [];
  try {
    routeIndex = await window.bridge.update.routes();
    routes = routeIndex;
  } catch {
    // If the list cannot be fetched, show only the direct connection and fill in rows from the results afterwards
    // 拿不到清单就只显示直连，测完再按结果补行
  }
  paintWaitingList(routes);

  const token = ++probeToken;
  // Probe and fill as we go: the main process pushes one result per finished route, and this only updates that row's stats
  // 边测边填：主进程每测完一条推一条，这里只更新对应那一行的右侧统计
  const off = window.bridge.update.onMirror((result) => {
    if (token === probeToken) {
      updateMirrorRow(result);
    }
  });

  let results = [];
  try {
    const reply = await window.bridge.update.probeMirrors(assetUrl);
    if (!reply || !reply.ok) {
      refs.mirrorHint.textContent = t('update.mirror.probeFailed', (reply && reply.error) || '');
    } else {
      results = reply.results || [];
    }
  } catch (error) {
    refs.mirrorHint.textContent = t('update.mirror.probeFailed', error.message);
  } finally {
    off();
  }

  // The dialog was closed or a new round started: discard this batch of results
  // 弹窗已经关掉或重新开了一轮：这一批结果作废
  if (token !== probeToken) {
    return;
  }
  probeCache = results;
  setProbing(false);
  settleProbe(results);
}

/**
 * Reset the mirror-related state when the dialog closes, so the next open is clean.
 *
 * 关掉弹窗时把镜像相关状态复位，下次打开是干净的
 */
function resetMirrorPanel() {
  refs.mirrorPanel.hidden = true;
  refs.mirrorPanel.classList.remove('is-probing');
  refs.updDialog.classList.remove('is-mirroring');
  refs.mirrorList.textContent = '';
  refs.mirrorHint.textContent = '';
  chosenRoute = 'direct';
  probeCache = null;
  probeBusy = false;
  // Invalidate the round still in flight, so it is not written back into the cleared list
  // 让还在飞的那一轮结果失效，别写回已经清空的列表
  probeToken += 1;
}

function showUpdate(update) {
  pendingUpdate = update;
  updateBusy = false;
  resetMirrorPanel();
  refs.updTitle.textContent = t('update.title', update.version);
  refs.updNotes.textContent = update.notes || t('update.notesEmpty');
  refs.updProg.hidden = true;
  resetProgress(0);
  paintUpdateStatus('');
  refs.updCancel.textContent = t('update.cancel');
  refs.updGo.textContent = t('update.go');
  refs.updGo.disabled = false;
  refs.updCancel.disabled = false;
  if (!refs.updDialog.open) {
    refs.updDialog.showModal();
  }
  // There is no separate "open routes" entry any more: the panel is laid out and probing starts as soon as the dialog appears
  // 没有单独的"打开线路"入口了：弹窗一出来就把线路铺开并开始测速
  void openMirrorPanel();
}

function closeUpdate() {
  if (refs.updDialog.open) {
    refs.updDialog.close();
  }
}

async function startUpdate() {
  if (!pendingUpdate || updateBusy) {
    return;
  }
  updateBusy = true;
  refs.updGo.disabled = true;
  refs.updCancel.disabled = true;
  refs.updProg.hidden = false;
  resetProgress(pendingUpdate.asset && pendingUpdate.asset.size);
  paintUpdateStatus(t('update.downloading'));

  const off = window.bridge.update.onProgress(({ received, total }) => {
    paintProgress(received, total || (pendingUpdate.asset && pendingUpdate.asset.size) || 0);
  });

  try {
    const result = await window.bridge.update.download(pendingUpdate, chosenRoute);
    if (!result.ok) {
      const reason = result.code === 'checksumMismatch'
        ? t('update.checksumMismatch')
        : result.code === 'noChecksum'
          ? t('update.noChecksum')
          : result.code === 'timeout'
            ? t('update.downloadTimeout')
            : result.code === 'stalled'
              ? t('update.downloadStalled')
              : (result.error || t('update.failedUnknown'));
      throw new Error(reason);
    }
    paintUpdateStatus(t('update.applying'));
    refs.updFill.style.width = '100%';
    refs.updPct.textContent = '100%';
    refs.updSpeed.textContent = '';
    const applied = await window.bridge.update.apply(result.file);
    if (!applied.ok) {
      throw new Error(applied.error || t('update.failedUnknown'));
    }
    // The main process exits a few hundred milliseconds later; the script overwrites and relaunches the new version
    // 主进程会在几百毫秒后退出，脚本负责覆盖并拉起新版
  } catch (error) {
    paintUpdateStatus(t('update.failed', error.message));
    refs.updGo.disabled = false;
    refs.updCancel.disabled = false;
    updateBusy = false;
  } finally {
    off();
  }
}

/* ── Update check · 检查更新 ─────────────────────────────── */

/**
 * A check is running; stops the "retry" button from being spam-clicked into a pile of requests.
 *
 * 正在跑一次检查，防止"重试"按钮被连点叠出一堆请求
 */
let checkBusy = false;

/**
 * Check for updates and explain failures clearly.
 *
 * The three failure kinds are treated separately; otherwise the user just sees a bare "check failed":
 *   · direct timeout (almost always blocked) → dialog offering "retry" or "use a mirror"
 *   · already on a mirror and still timing out  → the mirror is unreachable too; tell the user the network is the problem
 *   · anything else (404 / bad JSON etc.)       → ordinary error toast; switching mirrors will not help
 *
 * 检查更新，并把失败讲清楚。
 *
 * 三种失败要分开对待，不然用户只会看到一句没头没脑的"检查失败"：
 *   · 直连超时（几乎都是被墙）→ 弹窗让用户选"重试"或"改用镜像源"
 *   · 已经走了镜像还超时     → 说明镜像也不通，直接告诉用户网络有问题
 *   · 其它（404/坏 JSON 等）  → 普通错误 toast，换镜像也没用
 *
 * @param {HTMLButtonElement} [button] the "check" button on the settings page, greyed out while running · 设置页那颗"检查"按钮，跑的时候置灰
 * @param {'direct'|'mirror'} [via] when omitted, read the switch from settings · 省略则读设置里的开关
 */
async function runUpdateCheck(button, via) {
  if (checkBusy) {
    return;
  }
  checkBusy = true;
  if (button) {
    button.disabled = true;
  }
  let useMirror = via ? via === 'mirror'
    : (state.settings && state.settings.injector.useMirrorCheck === true);

  try {
    // One "timeout → ask the user → switch route and retry" counts as one round; at most two rounds, to avoid an endless loop
    // 一次"超时 → 问用户 → 换线路重来"算一轮，最多跑两轮，防死循环
    for (let round = 0; round < 2; round += 1) {
      const result = await window.bridge.update.check({ via: useMirror ? 'mirror' : 'direct' });

      if (result.ok) {
        if (result.update) {
          showUpdate(result.update);
        } else {
          toast(refs.toasts, { level: 'info', text: t('update.upToDate') });
        }
        return;
      }

      if (result.code !== 'timeout' && !useMirror) {
        toast(refs.toasts, { level: 'error', text: t('update.checkFailed', result.error || '') });
        return;
      }

      if (useMirror) {
        // Failed even through a mirror: whether timeout or 403, to the user it is "this path does not work",
        // and further splitting by HTTP code only confuses them more
        // 走镜像还失败：不管是超时还是 403，对用户来说都是"这条路走不通"，
        // 再区分 HTTP 码只会让人更糊涂
        toast(refs.toasts, { level: 'error', text: t('update.checkMirrorTimeout') });
        return;
      }

      // Direct timeout: hand the choice to the user
      // 直连超时：把选择权交给用户
      const useMirrorNow = await openModal({
        title: t('update.checkTimeoutTitle'),
        body: t('update.checkTimeoutBody'),
        okText: t('update.checkUseMirror'),
        cancelText: t('update.checkRetry')
      });
      if (useMirrorNow) {
        useMirror = true;
        await saveAppSetting({ useMirrorCheck: true });
        paintSettings();
        continue;
      }
      // Retry direct only once; a further timeout reports the error and exits (rounds exhausted)
      // 只重试一次直连，再超时就报错退出（round 用完）
    }
    toast(refs.toasts, { level: 'error', text: t('update.checkTimeoutAgain') });
  } catch (error) {
    toast(refs.toasts, { level: 'error', text: t('update.checkFailed', error.message) });
  } finally {
    checkBusy = false;
    if (button) {
      button.disabled = false;
    }
  }
}

function setScene(scene) {
  state.scene = scene;
  motion.setScene(scene);
  const selected = state.targets.find((t) => t.pid === state.selectedPid);
  render.renderPrimary(refs.btnInject, refs.btnLabel, {
    scene,
    hasTarget: Boolean(selected && selected.injectable)
  });
  paintTip(scene);

  // Injection started / already done: the countdown is meaningless now
  // 已经开始注入 / 已经注入完，倒计时就没意义了
  if (scene === 'injecting' || scene === 'attached') {
    cancelAutoInject();
  }

  // Remember which PID this round injected into: only once it disappears (game restart) may another injection happen;
  // the failed one is blacklisted, so the same bad process is not retried on every scan.
  // 记下这一轮注进了哪个 PID：它消失（游戏重启）时才允许再注一次；
  // 失败的那一个则拉黑，避免每轮扫描都重试同一个坏进程。
  if (scene === 'attached') {
    state.injectedPid = state.attemptPid;
    state.failedPid = null;
  } else if (scene === 'failed') {
    state.failedPid = state.attemptPid;
    state.injectedPid = null;
  }

  // After a successful injection the agent has already logged its own config path, so pick it up here and
  // the settings page needs no "relocate" click
  // 注入成功后 agent 已经把自己的配置路径写进日志了，顺手学一下，设置页不用再点"重新定位"
  if (scene === 'attached') {
    void relocateModConfig({ quiet: true });
  }
}

function appendOutput({ stream, line }) {
  state.log.push(line);
  const cap = Math.max(200, Number(state.logCap) || 800);
  if (state.log.length > cap) {
    state.log.splice(0, state.log.length - cap);
  }
  if (state.view !== 'log') {
    return;
  }
  const node = render.appendLog(refs.logBody, { stream, line });
  motion.animateLine(node);
}

/* ── Painting · 绘制 ─────────────────────────────────────── */

function paintTargets() {
  const cards = render.renderTargets(
    { grid: refs.grid, empty: refs.empty, count: refs.count },
    state.targets,
    state.selectedPid
  );
  motion.stagger(cards);
  cards.forEach(bindCard);
  const selected = state.targets.find((t) => t.pid === state.selectedPid);
  render.renderPrimary(refs.btnInject, refs.btnLabel, {
    scene: state.scene,
    hasTarget: Boolean(selected && selected.injectable)
  });
}

function bindCard(card) {
  const pid = Number(card.dataset.pid);
  motion.trackPointer(card);

  card.addEventListener('click', (event) => {
    if (state.scene === 'injecting') {
      return;
    }
    const target = state.targets.find((t) => t.pid === pid);
    motion.ripple(event, card, 'dark');
    if (!target) {
      return;
    }
    if (!target.injectable) {
      toast(refs.toasts, {
        level: 'info',
        text: t('device.unverifiedNote', target.client)
      });
    }
    state.selectedPid = pid;
    paintTargets();
  });

  card.addEventListener('dblclick', () => {
    if (state.scene !== 'injecting' && state.scene !== 'attached') {
      void startInject();
    }
  });
}

/* ── Interaction · 交互 ──────────────────────────────────── */

/**
 * About page text: two hardcoded sets, one picked by UI language.
 * Deliberately kept out of the i18n json and off t() — the credits and links on this page do not go through translation.
 *
 * 关于页文案：硬编码两套，按界面语言二选一。
 * 刻意不放进 i18n json、不走 t()——这一页的署名和链接不参与翻译流程。
 */
const ABOUT_TEXT = {
  zh: {
    tab: '关于',
    headline: '关于',
    injectorAuthor: '启动器作者：RareKoKo',
    modAuthor: '原模组作者：sggn',
    originalProject: '原项目地址',
    thisProject: '本项目地址',
    fontCredit: '界面字体 MiSans · 小米科技'
  },
  en: {
    tab: 'About',
    headline: 'About',
    injectorAuthor: 'Launcher author: RareKoKo',
    modAuthor: 'Original mod author: sggn',
    originalProject: 'Original project',
    thisProject: 'This project',
    fontCredit: 'UI typeface MiSans, by Xiaomi'
  }
};

function aboutLang() {
  return String(locale() || '').toLowerCase().startsWith('zh') ? 'zh' : 'en';
}

function renderAbout() {
  const pack = ABOUT_TEXT[aboutLang()];
  // The tab text is refreshed on this page too; order does not matter, querySelectorAll picks up all of them
  // tab 的文字也在这一页刷；顺序无所谓，querySelectorAll 都能拿到
  document.querySelectorAll('[data-about]').forEach((node) => {
    node.textContent = pack[node.dataset.about] || '';
  });
  refs.viewAbout.querySelectorAll('[data-about-title]').forEach((node) => {
    const text = pack[node.dataset.aboutTitle] || '';
    node.title = text;
    node.setAttribute('aria-label', text);
  });
}

/**
 * Page header text per view; the tone of the devices page differs from the other two, so do not share one set.
 *
 * 每个视图的页头文案；设备和其它两个页面的语气不一样，别共用一套。
 */
const HEADER_COPY = {
  devices: { eyebrow: 'BRIDGETAP · EXTERNAL LAUNCHER', headline: 'view.devices.headline', subhead: null },
  log: { eyebrow: 'RUNTIME · AGENT LOG', headline: 'view.log.headline', subhead: null },
  settings: { eyebrow: 'SETTINGS · LAUNCHER & MOD', headline: 'view.settings.headline', subhead: null },
  about: { eyebrow: 'ABOUT · CREDITS', headlineText: { zh: '关于', en: 'About' }, subhead: null }
};

function paintHeader(view) {
  const copy = HEADER_COPY[view] || HEADER_COPY.devices;
  refs.eyebrow.textContent = copy.eyebrow;
  // The about headline is hardcoded text (not in i18n); the other pages still go through t()
  // about 的标题是硬编码文案（不进 i18n），其余页还是走 t()
  refs.headline.textContent = copy.headlineText
    ? copy.headlineText[aboutLang()]
    : t(copy.headline);
  // settings / log / devices have no subhead: blank it + hidden, do not leave a blank strip
  // settings / log / devices 都没有副标题：置空 + hidden，别留一条空白
  const subhead = copy.subhead ? t(copy.subhead) : '';
  refs.subhead.textContent = subhead;
  refs.subhead.hidden = !subhead;
}

function switchView(view) {
  state.view = view;
  document.body.dataset.view = view;
  paintHeader(view);
  refs.viewDevices.classList.toggle('is-active', view === 'devices');
  refs.viewLog.classList.toggle('is-active', view === 'log');
  refs.viewSettings.classList.toggle('is-active', view === 'settings');
  refs.viewAbout.classList.toggle('is-active', view === 'about');
  // Stay on the current page after a refresh: changing language reloads the whole page, and without the hash it always snaps back to devices
  // 刷新后停在当前页：换语言会整页 reload，不记 hash 就总是弹回设备页
  const hash = '#' + view;
  if (location.hash !== hash) {
    history.replaceState(null, '', hash);
  }
  for (const item of refs.segments) {
    const active = item.dataset.view === view;
    item.classList.toggle('is-active', active);
    item.setAttribute('aria-selected', active ? 'true' : 'false');
    if (active) {
      motion.slideIndicator(refs.segmentTrack, item);
    }
  }
  if (view === 'log') {
    render.renderLog(refs.logBody, state.log);
  }
  if (view === 'settings') {
    void loadSettings();
  }
  if (view === 'about') {
    renderAbout();
  }
}

async function refresh() {
  refs.btnRefresh.disabled = true;
  try {
    await bridge.listTargets();
  } finally {
    refs.btnRefresh.disabled = false;
  }
}

function paintThemeButton() {
  const name = t('injector.theme.' + (theme.mode || 'auto'));
  refs.btnTheme.title = t('win.themeCycle', name);
  refs.btnTheme.setAttribute('aria-label', t('win.themeAria'));
}

/**
 * Cycle auto → light → dark; the main process persists it and syncs system-level dark mode.
 *
 * 自动 → 浅色 → 暗色 循环；主进程负责落盘并同步系统级暗色。
 */
async function cycleTheme() {
  const next = nextMode(theme.mode);
  theme.set(next);
  paintThemeButton();
  try {
    const state = await bridge.setTheme(next);
    theme.sync(state && state.resolved);
  } catch (error) {
    toast(refs.toasts, { level: 'error', text: t('toast.themeSaveFailed', error.message) });
  }
}

/* ── Settings page · 设置页 ─────────────────────────────── */

/**
 * Serialise mod setting writes: when the slider is clicked rapidly only the last result is kept, so a stale reply cannot
 * overwrite newer state.
 *
 * 模组设置的写入串行化：连点滑块时只保留最后一次结果，避免旧回包把新状态盖掉。
 */
let modWriteSeq = 0;

/**
 * Which client's config is being edited.
 *
 * Lunar's and Badlion's config/bridgetap.properties are **two different files** (each has its own CWD),
 * so the PID must be passed along to ask the main process; a blanket "use the last path in the log" will not do —
 * that was the bug before: open Lunar then BLC, settings all went into Lunar's file and BLC did not react at all.
 *
 * 当前要编辑哪个客户端的配置。
 *
 * Lunar 和 Badlion 的 config/bridgetap.properties 是**两个不同文件**（各自的 CWD 不同），
 * 所以必须带上 PID 去问主进程，不能笼统地"用日志里最后那个路径"——
 * 之前就是这个 bug：先开 Lunar 再开 BLC，设置全写进 Lunar 的文件，BLC 一点反应没有。
 */
function currentModTarget() {
  return pickModTarget(state.targets);
}

function pickModTarget(list) {
  const selected = list.find((item) => item.pid === state.selectedPid && item.injectable)
    || list.find((item) => item.injectable)
    || null;
  return selected
    ? { pid: selected.pid, client: selected.client }
    : { pid: null, client: null };
}

async function loadSettings() {
  try {
    const target = currentModTarget();
    const snapshot = await bridge.readSettings(target);
    state.settings = snapshot;
    state.settingsTarget = target;
    state.logCap = snapshot.injector.logLines;
    paintSettings();
    return snapshot;
  } catch (error) {
    toast(refs.toasts, { level: 'error', text: t('toast.settingsReadFailed', error.message) });
    return null;
  }
}

function paintSettings() {
  const snapshot = state.settings;
  if (!snapshot) {
    return;
  }
  const mod = snapshot.mod;
  settingsView.renderModSettings(refs.modSettingsBody, mod.fields, mod.values, onModChange);
  settingsView.renderAppSettings(refs.appSettingsBody, {
    logLines: snapshot.injector.logLines,
    autoUpdateCheck: snapshot.injector.autoUpdateCheck !== false,
    useMirrorCheck: snapshot.injector.useMirrorCheck === true,
    theme: snapshot.injector.theme || { mode: theme.mode },
    modPath: mod.path,
    version: state.version || snapshot.injector.version || '',
    autoInject: snapshot.injector.autoInject === true,
    autoInjectDelaySec: snapshot.injector.autoInjectDelaySec,
    appLanguage: snapshot.injector.appLanguage || 'auto',
    locales: locales()
  }, {
    onLanguage: (code) => {
      // Changing language reloads the whole page: this screen's DOM was all drawn at startup from the old dictionary,
      // and patching parts of it easily misses some
      // 换语言要整页重载：这一屏的 DOM 全是启动时按旧字典画的，局部替换容易漏
      void saveAppSetting({ appLanguage: code }).then(() => location.reload());
    },
    onLogLines: (value) => {
      void saveAppSetting({ logLines: Number(value) });
    },
    onAutoUpdate: (on) => {
      void saveAppSetting({ autoUpdateCheck: on === true });
    },
    onMirrorCheck: (on) => {
      void saveAppSetting({ useMirrorCheck: on === true });
    },
    onCheckUpdate: (button) => runUpdateCheck(button),
    onAutoInject: (on) => {
      void saveAppSetting({ autoInject: on === true });
    },
    onAutoInjectDelay: (seconds) => {
      void saveAppSetting({ autoInjectDelaySec: seconds });
    },
    onTheme: (mode) => {
      void applyThemeMode(mode);
    },
    onRelocate: () => {
      void relocateModConfig();
    },
    onOpenFolder: (which) => {
      bridge.openFolder(which === 'mod' ? 'mod' : 'logs');
    },
    onUninstall: async () => {
      const confirmed = await openModal({
        title: t('uninstall.confirmTitle'),
        body: t('uninstall.confirmBody'),
        okText: t('uninstall.confirmAction'),
        cancelText: t('uninstall.cancel'),
        tone: 'danger'
      });
      if (!confirmed) {
        return;
      }
      try {
        const result = await bridge.uninstall();
        if (!result.ok) {
          toast(refs.toasts, { level: 'error', text: t('uninstall.failed', result.error || '') });
          return;
        }
        // The main process exits on its own after 3 seconds; show "done" to the user first
        // 主进程 3 秒后自行退出；这里先把「完成」给用户看到
        await openModal({
          title: t('uninstall.doneTitle'),
          body: t('uninstall.doneBody'),
          okText: t('uninstall.close'),
          tone: 'normal'
        });
        window.close();
      } catch (error) {
        toast(refs.toasts, { level: 'error', text: t('uninstall.failed', error.message) });
      }
    }
  });
}

async function saveAppSetting(patch) {
  try {
    const next = await bridge.writeAppSettings(patch);
    if (state.settings) {
      state.settings.injector = { ...state.settings.injector, ...next };
    }
    state.logCap = next.logLines;
    // Auto inject was just turned on/off, or the wait time changed: reschedule the countdown right away
    // 刚打开/关掉自动注入、或者改了等待时长，立刻按新设置重排倒计时
    maybeScheduleAutoInject();
  } catch (error) {
    toast(refs.toasts, { level: 'error', text: t('toast.appSaveFailed', error.message) });
  }
}

async function applyThemeMode(mode) {
  theme.set(mode);
  paintThemeButton();
  try {
    const result = await bridge.setTheme(mode);
    theme.sync(result && result.resolved);
  } catch (error) {
    toast(refs.toasts, { level: 'error', text: t('toast.themeSaveFailed', error.message) });
  }
}

/**
 * Mod setting changed: write straight to disk; the agent's 500ms polling picks it up and it takes effect next frame.
 *
 * 模组设置改动：直接落盘，agent 那边 500ms 轮询会捡起来，下一帧生效。
 */
async function onModChange(key, value) {
  const seq = ++modWriteSeq;
  const target = state.settingsTarget || currentModTarget();
  try {
    const result = await bridge.writeModSettings({ [key]: value }, target);
    if (seq !== modWriteSeq) {
      return; // a newer write is still queued, so this result is discarded · 后面还有一次更新的写入，这次结果作废
    }
    if (!result.ok) {
      const failed = result.error || t('toast.modWriteFailed');
      toast(refs.toasts, { level: 'error', text: failed });
      return;
    }
    if (state.settings && state.settings.mod) {
      state.settings.mod.values = result.values;
      if (Array.isArray(result.paths)) {
        state.settings.mod.paths = result.paths;
      }
    }
  } catch (error) {
    toast(refs.toasts, {
      level: 'error',
      text: t('toast.modWriteFailed') + ' · ' + error.message
    });
  }
}

async function relocateModConfig(options = {}) {
  const quiet = options.quiet === true;
  const target = currentModTarget();
  try {
    const result = await bridge.relocateModConfig(target);
    if (state.settings && state.settings.mod) {
      state.settings.mod.path = result.path;
      state.settings.mod.exists = result.exists;
      state.settingsTarget = target;
      if (result.values) {
        state.settings.mod.values = result.values;
      }
      if (state.view === 'settings') {
        paintSettings();
      }
    }
    if (quiet) {
      return;
    }
    if (result.path) {
      toast(refs.toasts, { level: 'success', text: t('toast.modWriteOk', result.path) });
    } else {
      toast(refs.toasts, { level: 'error', text: t('toast.relocateNoPath') });
    }
  } catch (error) {
    if (!quiet) {
      toast(refs.toasts, { level: 'error', text: t('toast.relocateFailed', error.message) });
    }
  }
}

/* ── Auto scan / auto inject · 自动扫描 / 自动注入 ───────── */

/**
 * Adaptive polling interval.
 *
 * The injector keeps scanning from launch and ignores whether it is in the foreground (auto inject must finish the job in the background):
 *   empty field probes every 3 seconds (waiting for the client to be started),
 *   once a process is found it slows to 15 seconds (already found, no point pulling PowerShell every 3 seconds),
 *   when all processes are gone it returns to 3 seconds.
 *
 * 自适应轮询间隔。
 *
 * 注入器一启动就一直扫，不看在不在前台（自动注入要能在后台把活干完）：
 *   空场子 3 秒一探（等人把客户端开起来），
 *   一旦扫到进程就放慢到 15 秒（已经找到了，没必要每 3 秒拉一次 PowerShell），
 *   进程全没了再回到 3 秒。
 */
const SCAN_FAST_MS = 3000;
const SCAN_SLOW_MS = 15000;

let scanTimer = null;
let scanInterval = SCAN_FAST_MS;
/**
 * Auto injection currently counting down: { pid, deadline, timer }
 *
 * 正在倒计时的自动注入：{ pid, deadline, timer }
 */
let pendingInject = null;

function scheduleNextScan(delay = scanInterval) {
  if (scanTimer) {
    clearTimeout(scanTimer);
  }
  scanTimer = setTimeout(runScan, Math.max(200, delay));
}

async function runScan() {
  scanTimer = null;
  // While injecting, the service returns an empty array (busy); do not use that result to decide "is there a process"
  // 注入过程中 service 会直接返回空数组（busy），别拿这个结果去判"有没有进程"
  if (state.scene === 'injecting') {
    scheduleNextScan();
    return;
  }
  const started = Date.now();
  let found = state.targets.length > 0;
  try {
    const targets = await bridge.listTargets({ silent: true });
    if (Array.isArray(targets)) {
      found = targets.length > 0;
    }
  } catch {
    // If the scan fails, keep the current cadence and try again next time
    // 扫描失败就维持当前节奏，下一次再试
  }
  const next = found ? SCAN_SLOW_MS : SCAN_FAST_MS;
  if (next !== scanInterval) {
    scanInterval = next;
    // The main process forwards this line to stdout, so the cadence switch is visible when self-checking
    // 主进程会把这一行转到 stdout，自检时能直接看到节奏切换
    console.info('[scan] targets=' + (found ? 'yes' : 'no') + ' interval=' + scanInterval + 'ms');
  }
  // Subtract the time this scan itself took (starting PowerShell on Windows takes about 1 second);
  // otherwise the 3-second cadence actually becomes a bit more than 4 seconds
  // 扣掉这次扫描本身花的时间（Windows 起一次 PowerShell 约 1 秒），
  // 否则 3 秒的节奏实际会变成 4 秒多
  scheduleNextScan(scanInterval - (Date.now() - started));
}

function startAutoScan() {
  if (scanTimer) {
    return;
  }
  scanInterval = state.targets.length > 0 ? SCAN_SLOW_MS : SCAN_FAST_MS;
  scheduleNextScan();
}

function stopAutoScan() {
  if (scanTimer) {
    clearTimeout(scanTimer);
    scanTimer = null;
  }
}

/**
 * The inject button's label during the countdown goes through here; on cancel it is handed back to render.renderPrimary.
 *
 * 注入按钮在倒计时期间的文案走这里；取消时再交回 render.renderPrimary。
 */
function paintAutoInject(target, secondsLeft) {
  refs.btnInject.disabled = false;
  refs.btnLabel.textContent = t('btn.countdown', secondsLeft);
  refs.btnInject.classList.add('is-auto');
  refs.tip.textContent = t('tip.detected', target.client, target.pid, secondsLeft);
}

function cancelAutoInject() {
  if (pendingInject) {
    clearInterval(pendingInject.timer);
    pendingInject = null;
  }
  if (!refs.btnInject.classList.contains('is-auto')) {
    return;
  }
  refs.btnInject.classList.remove('is-auto');
  const selected = state.targets.find((item) => item.pid === state.selectedPid);
  render.renderPrimary(refs.btnInject, refs.btnLabel, {
    scene: state.scene,
    hasTarget: Boolean(selected && selected.injectable)
  });
  paintTip(state.scene);
}

function scheduleAutoInject(target, seconds) {
  cancelAutoInject();
  const deadline = Date.now() + seconds * 1000;
  const tick = () => {
    if (!pendingInject) {
      return;
    }
    const left = Math.max(0, Math.ceil((pendingInject.deadline - Date.now()) / 1000));
    paintAutoInject(target, left);
    if (left <= 0) {
      const pid = pendingInject.pid;
      cancelAutoInject();
      void injectByPid(pid);
    }
  };
  pendingInject = { pid: target.pid, deadline, timer: setInterval(tick, 250) };
  paintAutoInject(target, seconds);
}

/**
 * After every new process list, decide: start / keep / cancel the countdown.
 *
 * 每次拿到新进程列表后判断：要不要开始 / 维持 / 取消倒计时。
 */
function maybeScheduleAutoInject() {
  const option = state.settings && state.settings.injector;
  if (!option || option.autoInject !== true) {
    cancelAutoInject();
    return;
  }
  if (state.scene === 'injecting' || state.scene === 'attached') {
    cancelAutoInject();
    return;
  }
  const candidates = state.targets.filter((item) => item.injectable
    && item.pid !== state.injectedPid
    && item.pid !== state.failedPid);
  if (!candidates.length) {
    cancelAutoInject();
    return;
  }
  const preferred = candidates.find((item) => item.pid === state.selectedPid) || candidates[0];
  if (pendingInject && pendingInject.pid === preferred.pid) {
    return; // same target keeps counting down; do not restart the timer every 3 seconds · 同一个目标继续倒计时，别每 3 秒重新计时
  }
  scheduleAutoInject(preferred, Math.max(1, Number(option.autoInjectDelaySec) || 5));
}

async function injectByPid(pid) {
  state.selectedPid = pid;
  state.attemptPid = pid;
  paintTargets();
  await bridge.inject(pid);
}

async function startInject() {
  // Clicking the button while counting down = cancel, not inject immediately
  // 正在倒计时的时候点按钮 = 取消，不是立刻注入
  if (pendingInject) {
    cancelAutoInject();
    toast(refs.toasts, { level: 'info', text: t('toast.autoCancelled') });
    return;
  }
  const target = state.targets.find((t) => t.pid === state.selectedPid);
  if (!target) {
    toast(refs.toasts, { level: 'info', text: t('toast.pickTargetFirst') });
    return;
  }
  if (!target.injectable) {
    toast(refs.toasts, { level: 'error', text: t('toast.targetUnverified', target.client) });
    return;
  }
  motion.pulse(refs.btnInject, 'is-pressed', 220);
  state.attemptPid = target.pid;
  await bridge.inject(target.pid);
}

function bindChrome() {
  refs.btnMinimize.addEventListener('click', () => bridge.window('minimize'));
  refs.btnClose.addEventListener('click', () => bridge.window('close'));
  refs.btnMaximize.addEventListener('click', () => bridge.window('maximize'));
  // A maximised window sits flush with the screen, so the rounded corner has to go flat or the
  // desktop shows through it. The state comes from the main process because CSS cannot see it.
  // 最大化后窗口贴着屏幕边，圆角必须归零，否则四角会露出桌面。状态由主进程给，CSS 看不到。
  window.bridge.onWindowState((maximized) => {
    document.documentElement.dataset.maximized = maximized ? '1' : '0';
    const key = maximized ? 'win.restore' : 'win.maximize';
    refs.btnMaximize.dataset.i18nTitle = key;
    refs.btnMaximize.dataset.i18nAria = key;
    refs.btnMaximize.title = t(key);
    refs.btnMaximize.setAttribute('aria-label', t(key));
  });
  refs.btnTheme.addEventListener('click', () => {
    void cycleTheme();
  });
  refs.btnTheme.addEventListener('pointerdown', (event) => motion.ripple(event, refs.btnTheme, 'dark'));
  // Primary button glow follows the pointer (the MCHOSE-style effect)
  // 主按钮的光斑跟随指针（MCHOSE 那套光效）
  motion.trackGlow(refs.btnInject);
  refs.btnRefresh.addEventListener('click', (event) => {
    motion.ripple(event, refs.btnRefresh, 'dark');
    void refresh();
  });
  refs.btnInject.addEventListener('click', (event) => {
    motion.ripple(event, refs.btnInject, 'light');
    void startInject();
  });
  refs.btnClearLog.addEventListener('click', () => {
    state.log = [];
    render.renderLog(refs.logBody, []);
  });
  refs.btnExportLog.addEventListener('click', async () => {
    refs.btnExportLog.disabled = true;
    const label = refs.btnExportLog.textContent;
    refs.btnExportLog.textContent = t('log.exporting');
    try {
      const result = await bridge.exportLogs();
      if (result.ok) {
        toast(refs.toasts, { level: 'success', text: t('log.exported', result.file) });
      } else if (!result.canceled) {
        toast(refs.toasts, { level: 'error', text: t('log.exportFailed', result.error || '') });
      }
    } catch (error) {
      toast(refs.toasts, { level: 'error', text: t('log.exportFailed', error.message) });
    } finally {
      refs.btnExportLog.textContent = label;
      refs.btnExportLog.disabled = false;
    }
  });

  for (const item of refs.segments) {
    item.addEventListener('click', (event) => {
      motion.ripple(event, item, 'dark');
      switchView(item.dataset.view);
    });
  }

  document.addEventListener('keydown', (event) => {
    // When focus is in an input, all shortcuts yield to typing — the settings page has numeric/text inputs,
    // and previously pressing 1/2/3 switched pages outright, making it impossible to type numbers.
    // 焦点在输入框里时，所有快捷键都让位给打字——设置页有数字/文本输入，
    // 之前按 1/2/3 会直接切页，根本没法输入数字。
    const target = event.target;
    const typing = target instanceof HTMLElement
      && (target.tagName === 'INPUT' || target.tagName === 'SELECT' || target.tagName === 'TEXTAREA'
        || target.isContentEditable);
    if (typing) {
      return;
    }
    if (event.key === 'Enter' && !refs.btnInject.disabled) {
      void startInject();
    } else if (event.key === 'F5' || event.key.toLowerCase() === 'r') {
      event.preventDefault();
      void refresh();
    }
  });

  window.addEventListener('resize', () => {
    const active = refs.segments.find((item) => item.classList.contains('is-active'));
    motion.slideIndicator(refs.segmentTrack, active);
  });
}

/* ── Startup · 启动 ──────────────────────────────────────── */

async function boot() {
  // Language first: every string below (including error toasts) goes through t()
  // 语言先就位：下面所有文案（包括错误 toast）都走 t()
  await initI18n();
  applyStatic();
  // The about page text is hardcoded; applyStatic will not touch it, so refresh it once here
  // 关于页的文案是硬编码的，applyStatic 不会碰它，得自己刷一次
  renderAbout();
  bindChrome();
  bridge.onEvent(handleEvent);
  bridge.update.onAvailable((update) => showUpdate(update));
  refs.updCancel.addEventListener('click', () => {
    if (!updateBusy) {
      closeUpdate();
    }
  });
  refs.updGo.addEventListener('click', () => {
    void startUpdate();
  });
  // Closing via ESC must also reset, otherwise the next open carries the previous probe results and selected route
  // ESC 关窗也走一遍复位，否则下次打开会带着上一次的测速结果和选中线路
  refs.updDialog.addEventListener('close', () => {
    if (!updateBusy) {
      resetMirrorPanel();
    }
  });
  refs.modalCancel.addEventListener('click', () => closeModal(false));
  refs.modalOk.addEventListener('click', () => closeModal(true));
  refs.modalScrim.addEventListener('click', () => closeModal(false));
  motion.mount();

  try {
    const info = await bridge.info();
    // Theme follows the main process (it reads the last persisted choice); if unavailable, stay on "auto"
    // 主题以主进程为准（它读的是上次落盘的选择）；拿不到就维持"自动"
    if (info.theme) {
      theme.set(info.theme.mode);
      theme.sync(info.theme.resolved);
    }
    paintThemeButton();
    if (!info.dev && !info.injectorReady) {
      toast(refs.toasts, {
        level: 'error',
        text: t('toast.noInjector')
      });
    }
  } catch (error) {
    toast(refs.toasts, { level: 'error', text: t('toast.mainSilent', error.message) });
  }

  await refresh();

  // Fetch settings once up front: injector settings like the log line cap must take effect at startup,
  // not wait until the user opens the settings page
  // 先拉一次设置：日志行数上限这类注入器设置启动就要生效，不能等用户点开设置页
  const snapshot = await loadSettings();
  if (snapshot) {
    state.version = snapshot.injector && snapshot.injector.version;
  }

  const initialView = location.hash.replace('#', '');
  if (initialView === 'log' || initialView === 'settings' || initialView === 'about') {
    switchView(initialView);
  }

  requestAnimationFrame(() => {
    const active = refs.segments.find((item) => item.classList.contains('is-active'));
    motion.slideIndicator(refs.segmentTrack, active);
  });

  // Scan from launch on: ignore whether it is in the foreground, auto inject must be able to finish in the background
  // 启动就一直扫：不看在不在前台，自动注入要能在后台跑完
  startAutoScan();
  maybeScheduleAutoInject();
}

void boot();

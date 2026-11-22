'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { app } = require('electron');
const logTail = require('./inject/log-tail');
const i18n = require('./i18n');

/**
 * Two layers of settings.
 *
 *  · Injector settings: this app's own preferences, stored in userData/settings.json (the theme is
 *    the exception — it has its own theme.json).
 *  · Mod settings: the whole config/bridgetap.properties driven by the in-game /bt commands.
 *
 * Why mod settings are edited "directly in the file" instead of messaging the game:
 *   the agent polls mtime every 500ms (Config.startWatcher), so a file change takes effect on the
 *   next frame — no separate IPC stack per Lunar / Badlion, edits made while the game is closed
 *   still apply once it starts, and what we write is exactly the file the player writes with
 *   /bt save in game, so both sides always share one copy of the data.
 *
 * The only thing that must be guessed is the absolute file path: the agent's config is
 * config/bridgetap.properties relative to its CWD, and the CWD is not on the command line. So on
 * startup the agent prints a line "config file: <absolute path>" into agent.log; we learn it once
 * from the log and cache it, and after one injection we can always locate it.
 *
 * 两层设置。
 *
 *  · 注入器设置：本程序自己的偏好，写 userData/settings.json（主题除外，主题有自己的 theme.json）。
 *  · 模组设置：游戏里 /bt 那一整套 config/bridgetap.properties。
 *
 * 为什么模组设置是"直接改文件"而不是发消息给游戏：
 *   agent 里有个 500ms 的 mtime 轮询（Config.startWatcher），文件一变下一帧就生效——
 *   不用为 Lunar / Badlion 各写一套 IPC，游戏没开的时候改，等它开起来照样生效，
 *   而且改的就是玩家自己在游戏里 /bt save 写的那个文件，两边永远同一份数据。
 *
 * 唯一需要猜的东西是文件绝对路径：agent 的配置是相对 CWD 的 config/bridgetap.properties，
 * 而 CWD 不在命令行里。所以 agent 启动时会往 agent.log 打一行 "config file: <绝对路径>"，
 * 这里从日志里学一次并缓存；注入过一次之后就一直能定位。
 */

/* ── The injector's own settings ────────────────────────── */
/* ── 注入器自己的设置 ────────────────────────────────────── */

const APP_DEFAULTS = {
  /**
   * Max number of lines kept in the log view, prevents DOM bloat on long runs.
   * 日志视图最多保留多少行，防止长跑之后 DOM 膨胀
   */
  logLines: 800,
  /**
   * Auto-inject on a countdown once a process is found.
   * 扫到进程后倒计时自动注入
   */
  autoInject: false,
  /**
   * Countdown length in seconds.
   * 倒计时长度（秒）
   */
  autoInjectDelaySec: 20,
  /**
   * Run the launcher in the background (tray only) when Windows starts.
   *
   * <p>Stored here, but the OS side is a scheduled task, not this file — see `autostart.js`. The two
   * are kept in step by the IPC handler that owns writes: a value that the task could not be made to
   * match is rolled back rather than left lying.
   *
   * 开机时在后台（仅托盘）运行启动器。
   *
   * <p>存在这里，但系统那一侧是计划任务而非这个文件——见 `autostart.js`。两者由写入方（IPC 处理函数）
   * 保持一致：注册不上就回滚，不留一个说一套做一套的值。
   */
  launchOnStartup: false,
  /**
   * UI language: auto = follow the system locale; anything else is a code listed in i18n/index.json.
   * 界面语言：auto = 跟系统区域走；其余是 i18n/index.json 里的 code
   */
  appLanguage: 'auto',
  /**
   * Check GitHub for the latest Release on every startup.
   * 每次启动自动去 GitHub 查最新 Release
   */
  autoUpdateCheck: true,
  /**
   * Use mirror routes for update checks (when api.github.com is unreachable directly).
   * 查更新时走镜像线路（api.github.com 直连不通时用）
   */
  useMirrorCheck: false
};

function appSettingsPath() {
  return path.join(app.getPath('userData'), 'settings.json');
}

/**
 * Schema version of the file on disk.
 *
 * <p>A default change alone cannot reach an existing install: `writeAppSettings` writes the whole
 * object, so every user who ever touched a launcher setting has the *old* default frozen in their
 * file. When the shipped default changes meaningfully, bump this and add a step to `migrate`.
 *
 * 磁盘上文件的结构版本。
 *
 * <p>只改默认值影响不到已安装的用户：`writeAppSettings` 每次都写整个对象，所以任何动过启动器设置的
 * 用户，文件里都冻结着**旧的**默认值。默认值有实质变化时就升这个号，并在 `migrate` 里加一步。
 */
const APP_SCHEMA = 2;

/** Guard so a read-only config directory doesn't retry the migration on every read. */
/** 防止配置目录只读时每次读取都重试迁移。 */
let migrationAttempted = false;

/**
 * Bring an older file up to `APP_SCHEMA`.
 *
 * <p>Deliberately narrow: a value is rewritten only when it still equals the *previous* default, so a
 * user who picked something else keeps their choice. That is the whole reason these are written as
 * "was 5, now 20" rather than "force to 20".
 *
 * 把旧文件升级到 `APP_SCHEMA`。
 *
 * <p>刻意保守：只有当某个值仍等于**上一个**默认值时才改写，用户选过别的值就保留他的选择。这也是这里
 * 写成"原来 5、现在 20"而不是"强制 20"的全部理由。
 */
function migrate(next, from) {
  if (from < 2) {
    // 1 → 2: the auto-inject countdown shipped as 5 s; 5 s turned out too short for a client that is
    // still loading its world, so the default became 20 s.
    // 1 → 2：自动启动倒计时原来的默认是 5 秒；5 秒对一个还在加载世界的客户端太短，默认改成 20 秒。
    if (Number(next.autoInjectDelaySec) === 5) {
      next.autoInjectDelaySec = APP_DEFAULTS.autoInjectDelaySec;
    }
  }
  next.schema = APP_SCHEMA;
}

/**
 * Write the whole object without going through `writeAppSettings`.
 *
 * <p>`writeAppSettings` starts by calling `readAppSettings`, which is what triggers the migration —
 * calling it here would recurse.
 *
 * 直接写整个对象，不走 `writeAppSettings`。
 *
 * <p>`writeAppSettings` 开头就调 `readAppSettings`，而那正是触发迁移的地方——在这里调它会递归。
 */
function persistQuietly(next) {
  try {
    fs.mkdirSync(path.dirname(appSettingsPath()), { recursive: true });
    fs.writeFileSync(appSettingsPath(), JSON.stringify(next, null, 2), 'utf8');
    return true;
  } catch {
    return false;
  }
}

function readAppSettings() {
  let raw;
  try {
    raw = JSON.parse(fs.readFileSync(appSettingsPath(), 'utf8'));
  } catch {
    // No file (or unreadable): fresh installs get today's defaults and no migration
    // 没有文件（或读不出来）：全新安装直接用今天的默认值，不需要迁移
    return { ...APP_DEFAULTS, schema: APP_SCHEMA };
  }
  const next = { ...APP_DEFAULTS, ...raw };
  const from = Number(raw && raw.schema);
  if ((!Number.isFinite(from) || from < APP_SCHEMA) && !migrationAttempted) {
    migrationAttempted = true;
    migrate(next, Number.isFinite(from) ? from : 1);
    persistQuietly(next);
  }
  return next;
}

function writeAppSettings(patch) {
  const next = { ...readAppSettings(), ...(patch || {}) };
  const lines = Math.round(Number(next.logLines));
  next.logLines = Number.isFinite(lines) ? Math.min(5000, Math.max(100, lines)) : APP_DEFAULTS.logLines;
  next.autoInject = next.autoInject === true || next.autoInject === 'true';
  next.autoUpdateCheck = next.autoUpdateCheck === true || next.autoUpdateCheck === 'true';
  next.useMirrorCheck = next.useMirrorCheck === true || next.useMirrorCheck === 'true';
  next.launchOnStartup = next.launchOnStartup === true || next.launchOnStartup === 'true';
  const delay = Math.round(Number(next.autoInjectDelaySec));
  next.autoInjectDelaySec = Number.isFinite(delay)
    ? Math.min(120, Math.max(1, delay))
    : APP_DEFAULTS.autoInjectDelaySec;
  // The language must be auto or a code listed in i18n/index.json; anything else falls back to auto
  // 语言必须是 auto 或 i18n/index.json 里列出来的 code，否则回 auto
  const known = ['auto', ...i18n.listLocales().locales.map((item) => item.code)];
  if (typeof next.appLanguage !== 'string' || !known.includes(next.appLanguage)) {
    next.appLanguage = 'auto';
  }
  try {
    fs.mkdirSync(path.dirname(appSettingsPath()), { recursive: true });
    fs.writeFileSync(appSettingsPath(), JSON.stringify(next, null, 2), 'utf8');
  } catch (error) {
    console.warn('[settings] write failed / 写入注入器设置失败：' + error.message);
  }
  return next;
}

/* ── Field table for the mod settings ───────────────────── */
/* ── 模组设置的字段表 ───────────────────────────────────── */
/**
 * This table is the single source of truth for both "what the UI looks like" and "what goes into
 * the file": the renderer builds controls straight from fields, and the main process validates and
 * type-converts against the same table. Order = display order on the settings page, sorted from
 * frequently used to fine tuning.
 *
 * 这份表是"界面长什么样"和"文件里写什么"的唯一来源：
 * 渲染层拿到 fields 直接生成控件，主进程按同一份表做校验和类型转换。
 * 顺序 = 设置页的显示顺序，按"常用 → 微调"排。
 */
/**
 * The 16 § color codes of 1.8.9; since upstream 1.5.0 every text segment can be styled separately.
 *
 * Only codes and keys live here — names are in the language packs (`color.0` … `color.f`),
 * otherwise the swatch tooltips would stay Chinese when the language changes.
 *
 * 1.8.9 的 16 个 § 颜色码；上游 1.5.0 起每段文字都能单独改。
 *
 * 这里只有码和键，名称在语言包里（`color.0` … `color.f`），否则换语言时色块提示还是中文。
 */
const MC_COLORS = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f']
  .map((code) => ({ value: code, labelKey: 'color.' + code }));

/**
 * The field table stores **keys**, never copy.
 *
 * All copy lives in `i18n/<locale>.json` (`mod.<field>.label` / `.hint` / `.opt.<value>`), so adding
 * a language never touches this file, and the split-brain case of "main process sends Chinese while
 * the UI is switched to English" cannot happen.
 *
 * 字段表里不存文案，只存**键**。
 *
 * 文案全在 `i18n/<locale>.json` 里（`mod.<字段>.label` / `.hint` / `.opt.<值>`），所以加一门语言
 * 不用动这个文件，也不会出现"主进程下发中文、界面切了英文还在显示中文"的割裂。
 */
/**
 * Matches the order of core's Config.COLOR_NAMES; change one and you must change the other.
 * 与 core 的 Config.COLOR_NAMES 顺序一致，改这里必须同步改那边。
 */
// Only 0-7 (the text part) are exposed. 8 = panel background, 9 = border: the injector panel draws
// its own solid block and hasn't wired these two values yet, so they stay hidden in the UI instead
// of leaving a switch that does nothing when clicked (the keys still exist in the config file).
// 只暴露 0-7（文字部分）。8 = 面板背景、9 = 边框：注入器的面板是自己画的实心块，
// 还没接这两个色值，所以界面上先不出现，免得留一个点了没用的开关（配置文件里仍有这两个键）。
const COLOR_SLOTS = ['slot.0', 'slot.1', 'slot.2', 'slot.3', 'slot.4', 'slot.5', 'slot.6', 'slot.7'];
/**
 * Same defaults as core's Config.colors; when the key is missing from the file the UI must show this
 * value rather than "black".
 * 与 core 的 Config.colors 默认值一致；文件里没有这个键时界面要显示它，而不是显示"黑"。
 */
const COLOR_DEFAULTS = ['b', 'e', 'a', 'c', '7', 'a', 'e', 'b'];
const COLOR_FIELDS = COLOR_SLOTS.map((slot, index) => ({
  key: 'color.' + index,
  labelKey: 'mod.colorN.label',
  labelArg: slot,
  type: 'select',
  hintKey: 'mod.colorN.hint',
  hintArg: String(index),
  options: MC_COLORS,
  defaultValue: COLOR_DEFAULTS[index]
}));

/**
 * Languages the mod can read (mirror whatever exists in core's lang/ directory; auto = follow the
 * client language).
 * 模组能读的语言（core 的 lang/ 目录里有哪些就写哪些；auto = 跟客户端语言）。
 */
const MOD_LANGUAGES = [
  { value: 'auto', labelKey: 'lang.auto' },
  { value: 'en', labelKey: 'lang.en' },
  { value: 'zh_CN', labelKey: 'lang.zh_CN' }
];

const MOD_FIELDS = [
  { key: 'enabled', labelKey: 'mod.enabled.label', type: 'switch' },
  { key: 'displayMode', labelKey: 'mod.displayMode.label', type: 'select', options: [
    { value: 'ALL', labelKey: 'mod.displayMode.opt.ALL' },
    { value: 'COORDS', labelKey: 'mod.displayMode.opt.COORDS' },
    { value: 'COORDS_ONLY', labelKey: 'mod.displayMode.opt.COORDS_ONLY' },
    { value: 'TICKS', labelKey: 'mod.displayMode.opt.TICKS' },
    { value: 'TIME', labelKey: 'mod.displayMode.opt.TIME' },
    { value: 'LAST_TURN', labelKey: 'mod.displayMode.opt.LAST_TURN' }
  ] },
  { key: 'preferredAxis', labelKey: 'mod.preferredAxis.label', type: 'segmented', options: [
    { value: 'AUTO', labelKey: 'mod.preferredAxis.opt.AUTO' },
    { value: 'Z', labelKey: 'mod.preferredAxis.opt.Z' },
    { value: 'X', labelKey: 'mod.preferredAxis.opt.X' }
  ] },
  // `defaultValue` matters: without it a freshly created config gets the slider minimum (0.5), while
  // the agent's own default is 1.0 — the HUD would come up half-size after the first write from the stick.
  // `defaultValue` 不能省：缺了它，新建的配置文件会拿到滑块下限 0.5，而 agent 自己的默认是 1.0——
  // 走一次摇杆写入，HUD 就会缩成一半。
  { key: 'hudScale', labelKey: 'mod.hudScale.label', type: 'range', hintKey: 'mod.hudScale.hint', min: 0.5, max: 2, step: 0.05, defaultValue: '1' },
  // Percent of the free travel (canvas minus the panel's own width/height), not pixels and not raw
  // canvas percent: 0 = flush left/top, 100 = flush right/bottom, 50 = centred, and every value in
  // between is reachable without the panel jumping when it passes the midpoint.
  //
  // No `hintKey` on either row: they sit directly under the stick row, and three stacked lines of
  // grey text were louder than the values they were meant to explain. The 0 / 100 / 50 meaning is
  // documented here and in ARCHITECTURE.md instead of on screen.
  //
  // 画布"可移动余量"（扣掉面板自身宽高）的百分比，不是像素、也不是裸的画布百分比：
  // 0 = 贴左/上，100 = 贴右/下，50 = 居中；中间每个值都到得了，跨过中点也不会跳。
  //
  // 这两行都不挂 `hintKey`：它们紧跟在摇杆行下面，连着三行灰字比它们要解释的数值还吵。
  // 0/100/50 的含义记在这里和 ARCHITECTURE.md，不放到界面上。
  { key: 'hudXPercent', labelKey: 'mod.hudX.label', type: 'number', min: 0, max: 100, step: 0.1, unit: '%', defaultValue: '1.6' },
  { key: 'hudYPercent', labelKey: 'mod.hudY.label', type: 'number', min: 0, max: 100, step: 0.1, unit: '%', defaultValue: '2.8' },
  { key: 'backgroundEnabled', labelKey: 'mod.backgroundEnabled.label', type: 'switch' },
  { key: 'textShadow', labelKey: 'mod.textShadow.label', type: 'switch' },
  { key: 'showReadyNotice', labelKey: 'mod.showReadyNotice.label', type: 'switch' },
  { key: 'yieldToTabList', labelKey: 'mod.yieldToTabList.label', type: 'switch' },
  { key: 'showGainType', labelKey: 'mod.showGainType.label', type: 'switch' },
  { key: 'gainText', labelKey: 'mod.gainText.label', type: 'text', placeholder: 'Gain', maxLength: 24 },
  { key: 'noGainText', labelKey: 'mod.noGainText.label', type: 'text', placeholder: 'No Gain', maxLength: 24 },
  // These three carry an explicit `defaultValue` because the table's fallbacks disagree with the
  // agent: a switch with no default reads as `true`, and a number with no default reads as `min`
  // (here -40). Both are wrong for these keys, and the launcher writes its defaults straight into
  // the client's properties file — so the launcher's fallback silently overrode the agent's own
  // `chatOutput = false` / `tickOffset = 0` / `plateTickOffset = 0` on every fresh config.
  // 这三行必须显式给 `defaultValue`，因为表里的兜底与 agent 不一致：开关没有默认时读作 `true`，
  // 数字没有默认时读作 `min`（这里是 -40）。两者对这些键都是错的，而且启动器会把自己的默认值
  // 直接写进客户端的 properties 文件——于是一个新配置上，启动器的兜底悄悄覆盖了 agent 自己的
  // `chatOutput = false` / `tickOffset = 0` / `plateTickOffset = 0`。
  { key: 'chatOutput', labelKey: 'mod.chatOutput.label', type: 'switch', defaultValue: 'false' },
  { key: 'tickOffset', labelKey: 'mod.tickOffset.label', type: 'number', hintKey: 'mod.tickOffset.hint', min: -40, max: 40, step: 1, unit: 't', defaultValue: '0' },
  { key: 'plateTickOffset', labelKey: 'mod.plateTickOffset.label', type: 'number', hintKey: 'mod.plateTickOffset.hint', min: -40, max: 40, step: 1, unit: 't', defaultValue: '0' },
  { key: 'turnTicks', labelKey: 'mod.turnTicks.label', type: 'segmented', options: [
    { value: '12', labelKey: 'mod.turnTicks.opt.12' },
    { value: '15', labelKey: 'mod.turnTicks.opt.15' }
  ] },
  // Language goes last in the list (before the color section): it affects the in-game half, which
  // is a different category from the display items above.
  // 语言放在列表末尾（颜色分区之前）：它影响的是游戏里那半边，跟上面的显示项不是一类
  { key: 'language', labelKey: 'mod.language.label', type: 'select', options: MOD_LANGUAGES, defaultValue: 'auto' },
  ...COLOR_FIELDS
];

const FIELD_BY_KEY = new Map(MOD_FIELDS.map((field) => [field.key, field]));

/**
 * Runtime hint written by the launcher for the agent, in the same file because that is the only
 * channel it reads.
 *
 * <p>It is not a setting: never listed in the UI, never defaulted, never echoed back. The value is
 * epoch milliseconds until which the agent should poll the file every 50 ms (`0` = idle). The HUD
 * stick refreshes it while the player drags and drops it when the gesture ends, which is how the
 * agent switches cadence mid-session — no socket, no second file, no timer on the agent side.
 *
 * 启动器写给 agent 的运行时提示，走同一个文件（agent 只读这一个通道）。
 *
 * <p>它不是设置：界面不列、不给默认值、不回显。值是 epoch 毫秒：到这一刻之前 agent 每 50ms 读一次
 * 文件（`0` = 空闲）。摇杆拖动期间不断刷新，手势结束时撤销——agent 就是这样在会话中切换节奏，
 * 不需要 socket、不需要第二个文件、agent 侧也不用定时器。
 */
const STICK_LEASE_KEY = 'hudStickUntil';

/**
 * Only a plain non-negative integer makes it into the file; anything else means idle.
 *
 * 只有非负整数会写进文件，其余一律当空闲。
 */
function coerceLease(raw) {
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) {
    return '0';
  }
  return String(Math.round(value));
}

/**
 * Rows that are buttons rather than settings: they open a panel and store nothing.
 *
 * <p>They deliberately stay out of MOD_FIELDS, because that array drives reading, writing and the
 * defaults — putting them there would make the writer emit a `hudJoystick=0` line the agent has no
 * idea what to do with, and would also hand the renderer a bogus value. The display list below
 * splices them back in at the position the UI wants.
 *
 * 只弹面板、不存值的按钮行。
 *
 * <p>它们刻意不进 MOD_FIELDS：那张表同时决定读取、写入和默认值，塞进去会让写入端多出一行
 * agent 看不懂的 `hudJoystick=0`，还会给渲染层一个假值。下面那张展示清单再把它们插回该在的位置。
 */
const MOD_ACTION_FIELDS = [
  // Scale first, then the stick, then the two exact percentages — coarse to fine, top to bottom.
  // 先缩放、再摇杆、最后是两个精确百分比——从上到下由粗到细。
  {
    key: 'hudJoystick',
    labelKey: 'mod.hudJoystick.label',
    actionLabelKey: 'mod.hudJoystick.action',
    type: 'action',
    action: 'hudJoystick'
  }
];

/**
 * The list the settings page draws: persisted fields plus the action rows, in display order.
 *
 * 设置页实际绘制的清单：持久化字段 + 动作行，按显示顺序排好。
 */
const FIELDS_FOR_UI = (() => {
  const list = [...MOD_FIELDS];
  const anchor = list.findIndex((field) => field.key === 'hudScale');
  list.splice(anchor < 0 ? list.length : anchor + 1, 0, ...MOD_ACTION_FIELDS);
  return list;
})();

/**
 * Convert a value coming from the UI into the string written to the file, per the field table.
 * Illegal values are rejected outright — never write a corrupted file.
 * 按字段表把界面上来的值转成要写进文件的字符串；非法值直接拒绝，不写坏文件。
 */
function coerce(field, raw) {
  if (field.type === 'text') {
    // The display layer adds the brackets; letting the user type them would render as [[Gain]].
    // A newline would stretch the panel taller.
    // 方括号由显示层加，用户填进来会把标签画成 [[Gain]]；换行会把面板撑高。
    const text = String(raw === undefined || raw === null ? '' : raw)
      .replace(/[\[\]\r\n]/g, ' ')
      .trim();
    const max = field.maxLength || 24;
    return text.slice(0, max);
  }
  if (field.type === 'switch') {
    return raw === true || raw === 'true' ? 'true' : 'false';
  }
  if (field.type === 'select' || field.type === 'segmented') {
    const allowed = field.options.map((option) => String(option.value));
    const value = String(raw);
    if (!allowed.includes(value)) {
      throw new Error(i18n.t('err.badOption', i18n.t(field.labelKey || field.key), allowed.join(' / ')));
    }
    return value;
  }
  if (field.type === 'range' || field.type === 'number') {
    const value = Number(raw);
    if (!Number.isFinite(value)) {
      throw new Error(i18n.t('err.needNumber', i18n.t(field.labelKey || field.key)));
    }
    const min = field.min ?? -Infinity;
    const max = field.max ?? Infinity;
    const clamped = Math.min(max, Math.max(min, value));
    const rounded = field.step && field.step < 1
      ? Math.round(clamped / field.step) * field.step
      : Math.round(clamped);
    return String(Number(rounded.toFixed(3)));
  }
  return String(raw);
}

/* ── properties read / write ────────────────────────────── */
/* ── properties 读写 ────────────────────────────────────── */

function parseProperties(text) {
  const map = new Map();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('!')) {
      continue;
    }
    const cut = line.search(/[=:]/);
    if (cut < 0) {
      continue;
    }
    map.set(line.slice(0, cut).trim(), line.slice(cut + 1).trim());
  }
  return map;
}

function serializeProperties(map) {
  const lines = ['#BridgeTap Configuration', '#edited by the desktop launcher'];
  for (const [key, value] of map) {
    lines.push(key + '=' + value);
  }
  return lines.join('\n') + '\n';
}

/* ── Locating the mod config file ───────────────────────── */
/* ── 模组配置文件定位 ───────────────────────────────────── */
/*
 * Key point: the config file is created relative to each client's own **working directory**, so
 * Lunar and Badlion use two different files:
 *   Lunar   → %LOCALAPPDATA%\Programs\Lunar Client\config\bridgetap.properties
 *   Badlion → %APPDATA%\.minecraft\config\bridgetap.properties
 *
 * Early versions only remembered "the last config file in the log", so launching Lunar and then
 * BLC made the UI write settings into Lunar's file — BLC of course showed no reaction at all.
 *
 * Now the mapping is keyed by **PID**: the agent prints "config file: <path> | pid <pid>" on
 * startup, we store it as { byPid: { "1234": "C:\\..." }, last: "..." }, and writes pick the entry
 * for the currently selected process. Old agents (no pid in the log) fall back to "derive the path
 * from the client type" — both clients use fixed directories.
 *
 * 关键：配置文件是相对各客户端自己的**工作目录**生成的，所以 Lunar 和 Badlion 用的是两个文件：
 *   Lunar   → %LOCALAPPDATA%\Programs\Lunar Client\config\bridgetap.properties
 *   Badlion → %APPDATA%\.minecraft\config\bridgetap.properties
 *
 * 早期版本只记"日志里最后一条 config file"，于是先开 Lunar 再开 BLC 时，
 * 界面会把设置写进 Lunar 的配置文件，BLC 里当然一点反应都没有。
 *
 * 现在按 **PID** 建映射：agent 启动时打 "config file: <路径> | pid <pid>"，
 * 这里存成 { byPid: { "1234": "C:\\..." }, last: "..." }，写入时按当前选中的进程挑。
 * 老版本 agent（日志里没有 pid）就退回到"按客户端类型推路径"，两个客户端都是固定目录。
 */

function cachePath() {
  return path.join(app.getPath('userData'), 'mod-config.json');
}

function readCache() {
  const empty = { byPid: {}, last: null };
  try {
    const raw = JSON.parse(fs.readFileSync(cachePath(), 'utf8'));
    const byPid = {};
    if (raw && typeof raw.byPid === 'object' && raw.byPid) {
      for (const [pid, value] of Object.entries(raw.byPid)) {
        if (typeof value === 'string') {
          byPid[String(pid)] = value;
        }
      }
    }
    // Compatible with the old v1.2.x { path } cache
    // 兼容 v1.2.x 的 { path } 旧缓存
    const last = typeof raw.last === 'string' ? raw.last : (typeof raw.path === 'string' ? raw.path : null);
    return { byPid, last };
  } catch {
    return empty;
  }
}

function writeCache(cache) {
  try {
    fs.mkdirSync(path.dirname(cachePath()), { recursive: true });
    fs.writeFileSync(cachePath(), JSON.stringify(cache, null, 2), 'utf8');
  } catch {
    // A failed cache write doesn't break anything: we simply relearn it from the log next time
    // 缓存写不进去不影响功能：下次还会从日志里再学一遍
  }
}

/**
 * Learn pid → config path from agent.log.
 * Line format (since v1.2.4): `[BridgeTap] config file: C:\...\bridgetap.properties | pid 1234`
 * The old format has no pid, in which case only `last` is updated.
 *
 * 从 agent.log 里学 pid → 配置路径。
 * 行格式（v1.2.4 起）：`[BridgeTap] config file: C:\...\bridgetap.properties | pid 1234`
 * 老格式没有 pid，只更新 last。
 */
function learnFromLog() {
  const cache = readCache();
  // Scan the whole log: recent(600) drops the config line when there is verbose output
  // 整个日志扫一遍：recent(600) 在有 verbose 输出时会把 config 行挤出去
  let lines = [];
  try {
    const text = fs.readFileSync(logTail.logPath(), 'utf8');
    lines = text.split(/\r?\n/);
  } catch {
    lines = logTail.recent(600);
  }
  for (const line of lines) {
    const match = /config file:\s*(.+?)\s*(?:\|\s*pid\s*(\d+))?\s*$/.exec(line);
    if (!match) {
      continue;
    }
    const configPath = match[1].trim().replace(/^"|"$/g, '');
    if (!configPath) {
      continue;
    }
    cache.last = configPath;
    if (match[2]) {
      cache.byPid[String(match[2])] = configPath;
    }
  }
  return cache;
}

/**
 * Fallback for old agents with no pid: derive the directory from the client type — both clients use
 * fixed paths.
 * 老 agent 没有 pid 时的兜底：按客户端类型推目录，两个客户端都是固定路径。
 */
function pathFromClient(client) {
  const appData = process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming');
  const localAppData = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
  const name = String(client || '');
  if (/lunar/i.test(name)) {
    const candidate = path.join(localAppData, 'Programs', 'Lunar Client', 'config', 'bridgetap.properties');
    return fs.existsSync(candidate) ? candidate : null;
  }
  if (/badlion|minecraft|vanilla/i.test(name)) {
    const candidate = path.join(appData, '.minecraft', 'config', 'bridgetap.properties');
    return fs.existsSync(candidate) ? candidate : null;
  }
  return null;
}

/**
 * Resolve the "primary" config file (the one shown on the settings page).
 *
 * 解析"主"配置文件（设置页显示用的那一份）。
 *
 * @param {{relocate?:boolean, pid?:number, client?:string}} [options]
 * @returns {{path:string|null, source:'pid'|'client'|'last'|'none'}}
 */
function resolveModConfig(options = {}) {
  const cache = options.relocate ? { byPid: {}, last: null } : readCache();
  const learned = options.relocate || Object.keys(cache.byPid).length === 0 || !cache.last
    ? learnFromLog()
    : cache;

  if (options.pid !== undefined && options.pid !== null) {
    const exact = learned.byPid[String(options.pid)];
    if (exact) {
      writeCache(learned);
      return { path: exact, source: 'pid' };
    }
  }

  const guessed = pathFromClient(options.client);
  if (guessed) {
    writeCache(learned);
    return { path: guessed, source: 'client' };
  }

  if (learned.last) {
    writeCache(learned);
    return { path: learned.last, source: 'last' };
  }

  writeCache(learned);
  return { path: null, source: 'none' };
}

/**
 * Legacy wrapper: path only.
 * 兼容旧调用：只要路径。
 */
function modConfigPath(options = {}) {
  return resolveModConfig(options).path;
}

/**
 * Every known config file (one for Lunar, one for BLC/.minecraft, plus third-party clients).
 *
 * The requirement is "BLC and Lunar share one set of settings", so writes must hit **all of these
 * files at once** rather than picking the currently selected client — otherwise switching clients
 * loses the settings. Only existing files are collected, so no config is conjured into the
 * directory of a client the user never plays.
 *
 * 所有已知的配置文件（Lunar 一份、BLC/.minecraft 一份，第三方客户端另算）。
 *
 * 需求是"BLC 和 Lunar 共用同一套设置"，所以写入时要**同时写这几份**，
 * 而不是按当前选中的客户端挑一份——否则换客户端就丢设置。
 * 只收已存在的文件，避免在不玩的客户端目录里凭空造出配置。
 */
function knownConfigPaths(primary) {
  const cache = learnFromLog();
  const candidates = new Set();
  for (const value of Object.values(cache.byPid)) {
    candidates.add(value);
  }
  if (cache.last) {
    candidates.add(cache.last);
  }
  const lunar = pathFromClient('Lunar Client');
  if (lunar) {
    candidates.add(lunar);
  }
  const vanilla = pathFromClient('Badlion Client');
  if (vanilla) {
    candidates.add(vanilla);
  }
  if (primary) {
    candidates.add(primary);
  }
  const existing = [...candidates].filter((item) => {
    try {
      return fs.statSync(item).isFile();
    } catch {
      return false;
    }
  });
  // The primary file counts even if it doesn't exist yet (the first write creates it)
  // 主文件哪怕还不存在也要算进去（第一次写就是把它建出来）
  if (primary && !existing.includes(primary)) {
    existing.push(primary);
  }
  return existing;
}

/* ── Public API: read / write ───────────────────────────── */
/* ── 对外：读 / 写 ──────────────────────────────────────── */

function readModSettings(options = {}) {
  const resolved = resolveModConfig(options);
  const configPath = resolved.path;
  const paths = knownConfigPaths(configPath);
  const base = { fields: FIELDS_FOR_UI, source: resolved.source, paths };
  if (!configPath) {
    return {
      ...base,
      path: null,
      exists: false,
      values: defaultsOfFields(),
      reason: 'no-path'
    };
  }
  let text = null;
  try {
    text = fs.readFileSync(configPath, 'utf8');
  } catch {
    return {
      ...base,
      path: configPath,
      exists: false,
      values: defaultsOfFields(),
      reason: 'missing'
    };
  }
  const map = parseProperties(text);
  const values = {};
  for (const field of MOD_FIELDS) {
    const raw = map.get(field.key);
    values[field.key] = raw === undefined ? defaultsOfField(field) : raw;
  }
  return { ...base, path: configPath, exists: true, values };
}

function defaultsOfField(field) {
  if (field.defaultValue !== undefined) {
    return field.defaultValue;
  }
  if (field.type === 'switch') {
    return 'true';
  }
  if (field.type === 'text') {
    return '';
  }
  if (field.options) {
    return String(field.options[0].value);
  }
  return String(field.min ?? 0);
}

function defaultsOfFields() {
  const values = {};
  for (const field of MOD_FIELDS) {
    values[field.key] = defaultsOfField(field);
  }
  return values;
}

/**
 * Change one or more keys and **sync them to every known client config file**.
 *
 * Lunar and Badlion each have a config/bridgetap.properties, and users don't want to maintain two
 * sets, so this writes all of them in one go; each file keeps its own existing content as the base
 * (preserving its extra keys) and only the changed keys are overwritten. Write a temp file and
 * rename — the agent detects changes by mtime+size, so whole-file replacement is the most reliable,
 * and it also prevents the agent from reading a half-written file.
 *
 * 改一个或多个键，并**同步到所有已知的客户端配置文件**。
 *
 * Lunar 和 Badlion 各有一份 config/bridgetap.properties，用户不想分两套维护，
 * 所以这里一次写全部；每份都以它自己的现有内容为底（保留各自的额外键），只覆盖改动的键。
 * 写临时文件再 rename —— agent 靠 mtime+size 判断变化，整文件替换最稳，
 * 也避免它读到写了一半的内容。
 *
 * @param {Record<string, unknown>} patch
 * @param {{pid?:number, client?:string, relocate?:boolean}} [target]
 * @returns {{ok:boolean, values?:object, error?:string, path?:string, paths?:string[]}}
 */
function writeModSettings(patch, target = {}) {
  const primary = modConfigPath(target);
  if (!primary) {
    return { ok: false, error: i18n.t('err.notLocated') };
  }

  // Validate the whole patch first: one illegal entry aborts the batch, never write half of it
  // 先把改动整体校验一遍：任何一项非法就整批放弃，不能写一半
  const updates = new Map();
  try {
    for (const [key, raw] of Object.entries(patch || {})) {
      // The stick lease is not a field: it passes through untouched by the field table
      // 摇杆租约不是字段：不经过字段表，单独放行
      if (key === STICK_LEASE_KEY) {
        updates.set(key, coerceLease(raw));
        continue;
      }
      const field = FIELD_BY_KEY.get(key);
      if (!field) {
        // Ignore unknown keys; don't turn a UI bug into dirty data
        continue; // 未知键忽略，别把界面 bug 写成脏数据
      }
      updates.set(key, coerce(field, raw));
    }
  } catch (error) {
    return { ok: false, error: error.message };
  }

  const targets = knownConfigPaths(primary);
  const written = [];
  for (const configPath of targets) {
    let text = '';
    try {
      text = fs.readFileSync(configPath, 'utf8');
    } catch {
      text = '';
    }
    const map = parseProperties(text);
    if (map.size === 0) {
      // File missing or empty: fill in the defaults first, configRevision above all — without it the
      // agent treats the whole file as a legacy version and migrates it, resetting tickOffset to 0,
      // throwing away the value the user just changed.
      // 文件不存在或空的：先把默认值补齐，尤其 configRevision——缺了它 agent 会把整个文件当旧版迁移，
      // 把 tickOffset 重置成 0，用户刚改的值就白改了。
      for (const field of MOD_FIELDS) {
        map.set(field.key, defaultsOfField(field));
      }
      map.set('configRevision', '2');
    }
    for (const [key, value] of updates) {
      map.set(key, value);
    }
    // The pixel pair is gone from the field list; drop it from the file too, so what is on disk
    // matches what the panel shows. The agent migrates it once, on the first read, and never looks
    // at it again — leaving it behind would just be a second, contradictory source of truth.
    // 像素那一对已经从字段表里去掉了，文件里也一并删掉，让磁盘上的内容和面板显示的一致。
    // agent 只在第一次读取时迁移一次，之后再也不看它——留着就是第二个自相矛盾的来源。
    if (updates.has('hudXPercent')) {
      map.delete('hudX');
    }
    if (updates.has('hudYPercent')) {
      map.delete('hudY');
    }
    // The lease must not outlive the gesture that set it. Any ordinary write drops the line, so a
    // launcher killed mid-drag cannot leave the agent behind polling at 20 Hz for the rest of the
    // session. (The agent caps the lease at a minute as well — two independent guards.)
    // 租约不能比设置它的手势活得久。任何普通写入都会删掉这一行，所以启动器在拖动中崩掉也不会让
    // agent 在剩下的会话里一直跑 20Hz。（agent 侧也把租约压到最多一分钟——两道独立的保险。）
    if (!updates.has(STICK_LEASE_KEY)) {
      map.delete(STICK_LEASE_KEY);
    }
    try {
      fs.mkdirSync(path.dirname(configPath), { recursive: true });
      const temp = configPath + '.injector.tmp';
      fs.writeFileSync(temp, serializeProperties(map), 'utf8');
      fs.renameSync(temp, configPath);
      written.push(configPath);
    } catch (error) {
      return { ok: false, error: i18n.t('err.writeFailed', configPath, error.message) };
    }
  }

  return {
    ok: true,
    path: primary,
    paths: written,
    values: readModSettings(target || {}).values
  };
}

module.exports = {
  MOD_FIELDS,
  // The list the renderer draws (persisted fields plus action rows). Exported so a test can assert
  // the order — the stick has to sit between HUD scale and HUD X — without opening a window.
  // 渲染层要画的清单（持久化字段 + 动作行）。导出是为了让测试能断言顺序——
  // 摇杆必须夹在 HUD 缩放和横向位置之间——而不必真的开一个窗口。
  FIELDS_FOR_UI,
  readAppSettings,
  writeAppSettings,
  readModSettings,
  writeModSettings,
  modConfigPath,
  appSettingsPath,
  // Whoever has to touch every config file at once — the uninstaller does, to delete them — needs
  // the same list the settings writer uses. Exporting it keeps the candidate rules in one place.
  // 需要一次性处理所有配置文件的地方（卸载要删它们）得用与设置写入同一份清单。
  // 导出来能让候选规则只留在一处。
  knownConfigPaths
};

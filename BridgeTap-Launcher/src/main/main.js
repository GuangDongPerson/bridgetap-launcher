'use strict';

const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const { app, shell, ipcMain, BrowserWindow, nativeTheme, crashReporter } = require('electron');
const { CALL, UPDATE_EVENT } = require('../shared/contract');
const { InjectService } = require('./inject');
const settings = require('./settings');
const i18n = require('./i18n');
const updater = require('./updater');
const logExport = require('./log-export');
const uninstaller = require('./uninstall');
const autostart = require('./autostart');
const tray = require('./tray');
const { createOpacityController } = require('./window-opacity');

/**
 * Main process: window + IPC wiring.
 *
 * Deliberately free of business logic — all state lives in InjectService, all UI lives in the renderer.
 * Features such as "auto reconnect" or "silent update" later on change the service, not this file.
 *
 * The one exception is the theme: the renderer only draws, but persisting it and applying the
 * OS-level dark mode (nativeTheme) must happen in the main process; otherwise the window flashes a
 * light frame at startup.
 *
 * 主进程：窗口 + IPC 接线。
 *
 * 这里刻意不写任何业务判断——所有状态都在 InjectService 里，所有界面都在 renderer 里。
 * 以后加"自动重连""静默更新"这类功能，动的是 service，不是这里。
 *
 * 唯一多出来的一块是主题：渲染层只管画，落盘和应用系统级暗色（nativeTheme）必须在主进程，
 * 否则启动瞬间窗口底色还是浅色，会闪一帧白。
 */

let mainWindow = null;
let service = null;
/**
 * Owns the "dim the window while the HUD stick is dragged" state. Created here rather than inside
 * `createWindow` so it survives window recreation with its baseline intact — the controller does not
 * hold a window reference at all, it is handed one per call.
 *
 * 负责"拖 HUD 摇杆时把窗口变淡"的状态。放在这里而不是 `createWindow` 里创建，是为了让它在窗口
 * 重建后仍能保住基准值——控制器本身不持有窗口引用，每次调用时由外部传进来。
 */
const windowOpacity = createOpacityController(() => mainWindow);

/**
 * Set when the user (or the tray menu) really intends to exit.
 *
 * <p>`close` is intercepted while the tray exists, so hiding to the tray and quitting have to be
 * distinguishable — otherwise closing the window would be an unstoppable quit and the tray menu
 * would be unable to do the same thing on purpose.
 *
 * 用户（或托盘菜单）真的要退出时置位。
 *
 * <p>托盘存在时会拦截 `close`，所以"收进托盘"和"退出"必须能区分开——否则关窗口就成了无法阻止的
 * 退出，而托盘菜单反倒做不到同样的事。
 */
let quitting = false;

/* ── Startup log ─────────────────────────────────────────── */
/* ── 启动日志 ────────────────────────────────────────────── */

/**
 * Write one line to `%APPDATA%\BridgeTap\launcher.log`.
 *
 * <p>Packaged builds have no console, so every `console.log` and `console.warn` below currently
 * goes nowhere. Field reports of "double-click does nothing" therefore arrive with zero evidence,
 * and the only way to tell an application bug from an environment problem is to guess. This file
 * is that missing evidence.
 *
 * <p>Never throws: a diagnostic that can break startup is worse than no diagnostic.
 *
 * <p>Not rotating by size: this is a startup trace, a few lines per launch. Trimming happens on
 * demand (keep the last 200 lines) so the file cannot grow without bound over a long-lived install.
 *
 * 往 `%APPDATA%\BridgeTap\launcher.log` 写一行。
 *
 * <p>打包版没有控制台，下面所有 `console.log` / `console.warn` 目前都进了虚空。于是
 * "双击没反应"这类现场反馈到手时一点证据都没有，只能靠猜来区分"应用 bug"和"环境问题"。
 * 这个文件就是那份缺失的证据。
 *
 * <p>绝不抛异常：会弄坏启动的诊断比没有诊断更糟。
 *
 * <p>不按大小滚动：这是启动轨迹，每次启动几行。按需裁剪（保留最后 200 行），
 * 长期使用的机器上文件也不会无限增长。
 */
const STARTUP_LOG_MAX_LINES = 200;

function startupLogPath() {
  return path.join(app.getPath('userData'), 'launcher.log');
}

function writeStartupLog(message) {
  try {
    const file = startupLogPath();
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.appendFileSync(file, new Date().toISOString() + ' ' + message + '\n', 'utf8');
    // Trim occasionally rather than on every line: reading the whole file each time would make a
    // hot path out of a diagnostic.
    // 偶尔裁剪而不是每行都裁：每行都读整个文件，会让诊断变成热路径。
    if (Math.random() < 0.1) {
      const lines = fs.readFileSync(file, 'utf8').split('\n');
      if (lines.length > STARTUP_LOG_MAX_LINES) {
        fs.writeFileSync(
          file,
          lines.slice(-STARTUP_LOG_MAX_LINES).join('\n'),
          'utf8'
        );
      }
    }
  } catch {
    // Startup must not depend on the log succeeding
    // 启动不能依赖日志写成功
  }
}

/* ── Permissions: always run as administrator ────────────── */
/* ── 权限：始终以管理员运行 ─────────────────────────────── */

/**
 * Injection needs `OpenProcess` / `CreateRemoteThread` to open the game process. When the target
 * client is started elevated, only an equally elevated process gets a handle; otherwise all you get
 * is "OpenProcess failed: 5".
 *
 * The packaged exe manifest already declares requireAdministrator (see electron-builder.yml), so a
 * double-click raises UAC directly. This block covers the other launch paths: running the exe in
 * win-unpacked, starting from a plain terminal, being spawned by another program.
 *
 * When not elevated, relaunch ourselves via RunAs and exit, so two instances never fight over the
 * single-instance lock. If UAC is denied, keep running with the current privileges — settings and
 * logs still work, only injection fails with a native error code.
 *
 * 注入要 `OpenProcess` / `CreateRemoteThread` 打开游戏进程。目标客户端是管理员启动的时候，
 * 同级权限才拿得到句柄，否则只会得到一句 "OpenProcess failed: 5"。
 *
 * 打包后的 exe 清单已经声明了 requireAdministrator（见 electron-builder.yml），双击会直接弹 UAC。
 * 这一段兜住其余启动方式：直接跑 win-unpacked 里的 exe、从普通终端拉起、被别的程序 spawn。
 *
 * 非管理员时用 RunAs 重新拉起自己再退出，避免两个实例抢单实例锁。
 * UAC 被拒绝时继续以当前权限运行——设置页、日志都还能用，只是注入会失败并报原生错误码。
 */
function isElevated() {
  try {
    const { spawnSync } = require('node:child_process');
    // Use an absolute path, don't rely on PATH: the packaged process's PATH may not include
    // System32, and `spawnSync('net', ...)` would fail with ENOENT, misread as "not elevated".
    // 走绝对路径，不依赖 PATH：打包进程的 PATH 不一定带 System32，
    // `spawnSync('net', ...)` 会变成 ENOENT，进而被误判成"非管理员"。
    const netExe = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'net.exe');
    const probe = spawnSync(netExe, ['session'], { stdio: 'ignore', windowsHide: true });
    // If the probe itself never starts (exe missing, blocked), treat it as "already elevated":
    // better to receive a native error code during injection than to misjudge here, relaunch and
    // quit ourselves — that is the direct cause of "nothing happens after double-clicking".
    // 探测本身没跑起来（找不到 exe、被拦）时按"已提权"处理：
    // 宁可注入时收到一个原生错误码，也不要在这里误判后 relaunch 把自己退掉 ——
    // 那是"双击后什么也没发生"的直接来源。
    if (probe.error || probe.status === null) {
      return true;
    }
    // net session requires administrator; returns non-zero (access denied) otherwise
    // net session 需要管理员，非管理员返回非零（拒绝访问）
    return probe.status === 0;
  } catch {
    return true;
  }
}

function relaunchElevated() {
  const { spawnSync } = require('node:child_process');
  const quote = (value) => "'" + String(value).replace(/'/g, "''") + "'";
  const exe = quote(process.execPath);
  const args = process.argv.slice(1).map(quote).join(' ');
  const command = 'Start-Process -FilePath ' + exe
    + (args ? ' -ArgumentList ' + args : '')
    + ' -Verb RunAs -WindowStyle Normal';
  try {
    const result = spawnSync('powershell', [
      '-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', command
    ], { stdio: 'ignore', windowsHide: true, timeout: 60000 });
    return result.status === 0;
  } catch {
    return false;
  }
}

/* ── Last-resort diagnostics ─────────────────────────────── */
/* ── 最后一道诊断 ────────────────────────────────────────── */

/**
 * An exception thrown before any window exists is completely invisible in a packaged build: no
 * console to print to, no dialog, the process just disappears. That is indistinguishable from
 * "double-click does nothing", which is precisely the report this log was added to answer.
 *
 * <p>Recording and then rethrowing keeps Node's normal fatal behaviour (non-zero exit) while
 * leaving a line on disk that names the file and line number.
 *
 * 任何在窗口存在之前抛出的异常，在打包版里是完全不可见的：没有控制台可打印、没有对话框，
 * 进程就那么消失了。这与"双击没反应"无法区分，而这正是这份日志要回答的现场反馈。
 *
 * <p>记录之后重新抛出，保留 Node 原本的致命行为（非零退出码），同时在磁盘上留下一行
 * 指明文件名与行号的记录。
 */
process.on('uncaughtException', (error) => {
  writeStartupLog('UNCAUGHT: ' + (error && error.stack ? error.stack : String(error)));
  throw error;
});
process.on('unhandledRejection', (reason) => {
  writeStartupLog('UNHANDLED REJECTION: ' + (reason && reason.stack ? reason.stack : String(reason)));
});

/**
 * Decide, and record the decision: an elevation misjudgement is the one startup failure that
 * silently relaunches forever, so both the verdict and the relaunch attempt must be on disk.
 *
 * 判断并记录：提权误判是唯一会静默无限重启的启动故障，所以结论和重启尝试都必须落盘。
 */
if (process.platform === 'win32') {
  const elevated = isElevated();
  writeStartupLog('elevation: isElevated=' + elevated + ' pid=' + process.pid);
  if (!elevated) {
    const relaunched = relaunchElevated();
    writeStartupLog('elevation: not elevated, relaunchElevated=' + relaunched);
    if (relaunched) {
      // The elevated instance takes over; exit cleanly here, or the single-instance lock turns it away
      // 提权后的新实例接手；这里必须退干净，否则单实例锁会把它挡回去
      app.exit(0);
    }
  }
}

const THEME_MODES = ['auto', 'light', 'dark'];
/**
 * Window background, read from the stylesheet rather than kept here as a second copy.
 *
 * <p>The frame colour in tokens.css has to match the window colour exactly, otherwise the area
 * outside the rounded corners shows a wedge of a different shade. With the pair defined in one
 * place the stylesheet is the only thing to edit.
 *
 * <p>Read lazily: this runs on first use, after Electron has registered the asar filesystem, and
 * falls back to the old hard-coded pair when the variables are missing or renamed - a renamed
 * variable must not take the window down.
 *
 * 窗口底色，从样式表读，不在代码里留第二份。
 *
 * <p>tokens.css 里的边框色必须与窗口色完全一致，否则圆角外面会透出一块异色三角。
 * 成对的值只留一处，以后只改样式表。
 *
 * <p>惰性读取：首次使用时才跑，此时 Electron 已注册 asar 文件系统；变量缺失或改名时
 * 回退到原来硬编码的一对值 —— 改个变量名不该把窗口弄挂。
 */
const FRAME_FALLBACK = { light: '#f4f4f4', dark: '#0a0a0a' };
let frameColours = null;

function readFrameColours() {
  try {
    const css = fs.readFileSync(path.join(__dirname, '..', 'renderer', 'styles', 'tokens.css'), 'utf8');
    const pick = (name, fallback) => {
      const hit = css.match(new RegExp('--' + name + '\\s*:\\s*([^;]+);'));
      return hit ? hit[1].trim() : fallback;
    };
    const colours = {
      light: pick('shell-frame-light', FRAME_FALLBACK.light),
      dark: pick('shell-frame-dark', FRAME_FALLBACK.dark),
    };
    console.log('[theme] frame colours: light=' + colours.light + ' dark=' + colours.dark);
    return colours;
  } catch (error) {
    console.warn('[theme] frame colours unreadable, using built-in values: ' + error.message);
    return FRAME_FALLBACK;
  }
}

/**
 * @param {string} scheme {@code light} or {@code dark}
 * @return {string} the colour to hand to BrowserWindow
 * · @param {string} scheme {@code light} 或 {@code dark}
 * · @return {string} 交给 BrowserWindow 的颜色
 */
function windowBackground(scheme) {
  if (!frameColours) {
    frameColours = readFrameColours();
  }
  return frameColours[scheme] || frameColours.dark;
}
let themeMode = 'auto';

function themeFile() {
  return path.join(app.getPath('userData'), 'theme.json');
}

function readThemeMode() {
  try {
    const parsed = JSON.parse(fs.readFileSync(themeFile(), 'utf8'));
    if (THEME_MODES.includes(parsed.mode)) {
      return parsed.mode;
    }
  } catch {
    // Never saved before: fall back to auto
    // 没存过就用自动
  }
  return 'auto';
}

function themeState() {
  return { mode: themeMode, resolved: nativeTheme.shouldUseDarkColors ? 'dark' : 'light' };
}

/**
 * Apply a theme: auto follows the system, light/dark force an override (also flips prefers-color-scheme).
 * 应用主题：auto 交给系统，light/dark 强制覆盖（同时改 prefers-color-scheme）。
 */
function applyTheme(mode) {
  themeMode = THEME_MODES.includes(mode) ? mode : 'auto';
  nativeTheme.themeSource = themeMode === 'auto' ? 'system' : themeMode;
  const state = themeState();
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.setBackgroundColor(windowBackground(state.resolved));
  }
  return state;
}

function persistTheme() {
  try {
    fs.mkdirSync(path.dirname(themeFile()), { recursive: true });
    fs.writeFileSync(themeFile(), JSON.stringify({ mode: themeMode }), 'utf8');
  } catch (error) {
    console.warn('[theme] write failed / 写入失败：' + error.message);
  }
}

function emit(kind, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('app:event', { kind, payload });
  }
}

/**
 * Whether this is Windows 11 or newer.
 *
 * <p>Windows 10 reports itself as 10.0 and so does Windows 11 — the difference only shows in
 * `build`: 22000 and up is Windows 11. Options such as `accentColor` and `roundedCorners` describe
 * Windows 11 composition features. Passing them on Windows 10 asks the compositor for something it
 * does not have.
 *
 * <p>为什么需要这个判断：Win10 与 Win11 都报 10.0，只能在 `build` 上区分（22000 起是 Win11）。
 * `accentColor` / `roundedCorners` 描述的是 Win11 的合成器行为，在 Win10 上等于向不存在的
 * 能力提要求。开发机是 Win11（26300），现场是 Win10 22H2（19045）——这条差异在日志里表现为
 * `whenReady` 之后直接消失，连异常都来不及记。
 */
function isWindowsElevenOrNewer() {
  if (process.platform !== 'win32') {
    return false;
  }
  const build = Number((os.release() || '').split('.')[2]);
  return Number.isFinite(build) && build >= 22000;
}

/**
 * Bring the window back from the tray, creating it if it was destroyed.
 *
 * 从托盘把窗口叫回来；窗口已被销毁就重建。
 */
function showMainWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) {
    createWindow();
    return;
  }
  if (mainWindow.isMinimized()) {
    mainWindow.restore();
  }
  if (!mainWindow.isVisible()) {
    mainWindow.show();
  }
  mainWindow.focus();
}

/**
 * Create the tray icon, or drop it when the feature is off.
 *
 * <p>Removing the icon must never leave the app with no way back, so a hidden window is shown first.
 *
 * 创建托盘图标；功能关闭时移除。
 *
 * <p>移除图标绝不能把应用留在"再也够不着"的状态，所以先把隐藏的窗口显示出来。
 */
function applyTray(enabled) {
  if (enabled) {
    const created = tray.ensure({
      title: i18n.t('app.title'),
      showLabel: i18n.t('tray.show'),
      quitLabel: i18n.t('tray.quit'),
      onShow: showMainWindow,
      onQuit: () => {
        quitting = true;
        app.quit();
      }
    });
    // Logged because "no icon in the corner" and "no process running" look identical to a user
    // whose only interface is a missing icon.
    // 记一行，因为当用户唯一的界面就是那个不存在的图标时，「角落没图标」和「进程没跑」是一回事。
    writeStartupLog('tray: ' + (created ? 'icon created' : 'icon could NOT be created'));
    return;
  }
  if (!tray.exists()) {
    return;
  }
  if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.isVisible()) {
    mainWindow.show();
  }
  tray.destroy();
  writeStartupLog('tray: icon removed (launch-on-startup off)');
}

/**
 * Make Windows agree with `launchOnStartup`, and undo the stored value if it refuses.
 *
 * <p>The setting and the scheduled task are two pieces of state that must not drift. The file is
 * written first (that is what the settings API does), so a task that could not be created leaves a
 * value claiming a feature that does not exist. Rolling the file back is the only honest outcome:
 * the switch springs back and the user sees why, instead of finding out at the next boot that
 * nothing started.
 *
 * 让 Windows 与 `launchOnStartup` 保持一致；系统不接受时把存下来的值改回去。
 *
 * <p>设置和计划任务是两处状态，不能各说各话。文件是先写的（设置 API 就是这么做的），所以一个建不
 * 起来的任务会留下一个声称"功能已开"的值。把它改回去是唯一诚实的处理：开关弹回原位、用户当场看到
 * 原因，而不是等下次开机才发现什么都没启动。
 *
 * @param {object} next Settings as written, including the requested value · 已写入的设置
 * @returns {object} Settings as they actually stand · 实际生效的设置
 */
function applyLaunchOnStartup(next) {
  const wanted = next.launchOnStartup === true;
  const result = autostart.sync(wanted);
  if (!result.ok) {
    writeStartupLog('autostart: ' + (wanted ? 'enable' : 'disable') + ' failed: ' + result.error);
    const reverted = settings.writeAppSettings({ launchOnStartup: !wanted });
    applyTray(reverted.launchOnStartup === true);
    return { ...reverted, startupError: result.error };
  }
  writeStartupLog(
    'autostart: ' + (wanted ? 'enabled' : 'disabled')
    + (result.changed ? '' : ' (already in that state)')
    + ' exe=' + autostart.launcherExe()
  );
  applyTray(wanted);
  return next;
}

function createWindow() {
  const win11 = isWindowsElevenOrNewer();
  writeStartupLog('createWindow: win11=' + win11 + ' osRelease=' + os.release());
  /** @type {Electron.BrowserWindowConstructorOptions} */
  const windowOptions = {
    width: 1180,
    height: 768,
    minWidth: 1040,
    minHeight: 680,
    show: false,
    frame: false,
    // Match the theme, otherwise a dark theme flashes a light frame at startup
    // 和主题对齐，否则暗色主题启动会先闪一帧浅色
    backgroundColor: windowBackground(themeState().resolved),
    title: i18n.t('app.title'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // preload needs require('../shared/contract'), and sandbox mode forbids relative require;
      // contextIsolation stays on, so the renderer has no node access and every capability goes
      // through the preload whitelist.
      // preload 需要 require('../shared/contract')，沙箱模式下不允许相对 require；
      // contextIsolation 仍然开着，渲染进程拿不到 node，能力全走 preload 白名单。
      sandbox: false,
      spellcheck: false,
      /**
       * Disable background throttling: when the window is minimized or occluded, Chromium drops
       * setTimeout to once a minute, turning the auto scan from "once every 3 seconds" into "once
       * a minute", which kills auto-injection along with it.
       *
       * 关掉后台节流：窗口被最小化/遮挡时 Chromium 会把 setTimeout 降到 1 分钟一次，
       * 自动扫描就从"3 秒一探"变成"一分钟一探"，自动注入也就跟着废了。
       */
      backgroundThrottling: false
    }
  };

  if (win11) {
    // Windows 11 only. `accentColor: false` opts out of the accent-coloured border, which shows up
    // as a pale blue outline on a dark window; `roundedCorners` asks for rounded corners. Both
    // describe Win11 composition, and the system setting plus every other window are untouched.
    // 仅 Windows 11。`accentColor: false` 退出强调色边框（深色窗口上是一圈淡蓝轮廓），
    // `roundedCorners` 要求圆角。两者都是 Win11 的合成行为，系统设置和其他窗口都不受影响。
    windowOptions.accentColor = false;
    windowOptions.roundedCorners = true;
  }

  writeStartupLog('createWindow: constructing BrowserWindow');
  // No marker juggling here any more. The level was persisted before `whenReady` and stays
  // "unconfirmed" for the whole launch; `ready-to-show` and `did-finish-load` flip it to confirmed.
  // That covers the window this crash actually happens in — after construction, before first paint.
  // 这里不再摆弄标记。级别在 `whenReady` 之前就已持久化，并整轮保持"未确认"；
  // 由 `ready-to-show` 与 `did-finish-load` 翻成"已确认"。这正好覆盖崩溃真正发生的区间：
  // 窗口构造之后、首次绘制之前。
  mainWindow = new BrowserWindow(windowOptions);
  writeStartupLog('createWindow: BrowserWindow constructed');

  mainWindow.setMenuBarVisibility(false);
  // The corner has to go flat while the window fills the screen, otherwise the desktop shows
  // through it. CSS cannot detect this, so the renderer is told.
  // 窗口铺满屏幕时圆角必须归零，否则四角会露出桌面。CSS 判断不了，只能由主进程通知。
  const broadcastWindowState = () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send(CALL.WINDOW_STATE, mainWindow.isMaximized());
    }
  };
  mainWindow.on('maximize', broadcastWindowState);
  mainWindow.on('unmaximize', broadcastWindowState);

  // The window is going away, being hidden, or losing activation: whatever gesture was dimming it
  // cannot finish, and the renderer may never hear about it. A minimized window gets no `blur` in
  // the page, so a dim left standing here would come back *with* the window — the very "opacity
  // residue" the feature must never leave behind. The main process owns the baseline, so the
  // safety net belongs here rather than in the renderer's `blur` handler.
  // 窗口要消失、被隐藏、或者失去激活：正在变淡的那个手势不可能正常结束了，而渲染层可能根本
  // 收不到通知。最小化的窗口在页面里拿不到 `blur`，所以留在这里的变淡会**跟着窗口一起回来**——
  // 正是这功能绝不能留下的"透明度残留"。基准归主进程所有，兜底也就该放在这里，
  // 而不是渲染层的 `blur` 处理函数里。
  const undimForInterruption = () => {
    if (windowOpacity.isDimmed()) {
      windowOpacity.apply(mainWindow, false);
      // Tell the renderer, because it may not know: the whole point of this path is that the page
      // might never have received `blur`. Its `dimmed` flag only sends an edge on change, so a
      // stale `true` would silently disable dimming for every later drag.
      // 告诉渲染层，因为它可能并不知道：这条路存在的全部理由就是页面可能没收到 `blur`。
      // 它那个 `dimmed` 标记只在变化时发边沿，残留的 `true` 会让之后每次拖动都悄悄失效。
      if (!mainWindow.isDestroyed()) {
        mainWindow.webContents.send(CALL.WINDOW_OPACITY_RESET);
      }
    }
  };
  mainWindow.on('minimize', undimForInterruption);
  mainWindow.on('hide', undimForInterruption);
  mainWindow.on('blur', undimForInterruption);

  mainWindow.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));

  /**
   * `ready-to-show` is the only path that shows the window, and it depends on the renderer
   * finishing its first paint. A renderer that dies before that — GPU driver, virtual display
   * adapter, a script that throws during load — never fires it, and the window stays hidden
   * forever. Nothing is logged and nothing is shown, which is exactly the shape of the field
   * report "I double-clicked and nothing happened".
   *
   * This timer converts that silent failure into a visible one: after ten seconds the window is
   * shown regardless, so the user at least gets a frame, and the log says why.
   *
   * `ready-to-show` 是唯一显示窗口的路径，而它依赖渲染进程完成首绘。在那之前就死掉的渲染进程
   * （显卡驱动、虚拟显示适配器、加载期抛异常的脚本）永远不会触发它，窗口就永远隐藏。
   * 既不打日志也不显示——正是现场反馈"我双击了，什么也没发生"的形状。
   *
   * 这个定时器把静默失败变成看得见的失败：十秒后无论如何都显示窗口，用户至少能看到画面，
   * 而日志会说清原因。
   */
  /**
   * Reaching first paint is the only proof the current graphics level works. `ready-to-show` fires
   * when the renderer has produced its first frame, so it is the earliest honest signal — and it is
   * the step that is missing in the field log.
   *
   * <p>`show: false` plus this handler is what the app relies on everywhere else, so it is also
   * where the confirmation belongs: a crash before this line leaves the state unconfirmed, and the
   * next launch starts one level lower.
   *
   * `ready-to-show` 是唯一能证明当前图形级别可用的信号。它表示渲染进程产出了第一帧，
   * 是最早的诚实信号——也正是现场日志里缺失的那一步。
   *
   * <p>全应用都靠 `show: false` + 这个处理函数出窗口，所以"确认"也该挂在这里：
   * 死在这一行之前就留下未确认状态，下次启动降一级。
   */
  const confirmGraphicsLevel = (why) => {
    try {
      writeGraphicsState(graphicsLevel, true);
      writeStartupLog('graphics: level=' + graphicsLevelName + ' confirmed (' + why + ')');
    } catch {
      // Losing the confirmation only costs one extra degraded launch
      // 确认丢失最多多一次降级启动
    }
  };

  const showFallbackTimer = setTimeout(() => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      // A background start stays hidden. Forcing it up after ten seconds would turn "runs quietly
      // in the tray" into "a window appears ten seconds after every boot", which is the opposite of
      // what the setting asks for.
      // 后台启动时保持隐藏。十秒后强行显示会把「安静地待在托盘」变成「每次开机十秒后弹个窗口」，
      // 与这个设置要求的正好相反。
      if (startHidden) {
        writeStartupLog('window: ready-to-show did not fire within 10s, staying hidden (autostart)');
        return;
      }
      const visible = mainWindow.isVisible();
      writeStartupLog(
        'window: ready-to-show did not fire within 10s, showing anyway (visible=' + visible + ')'
      );
      if (!visible) {
        mainWindow.show();
      }
      // The process survived ten seconds and the window object is alive, but the renderer never
      // reported a frame. Showing it is still the right move — a blank window the user can close
      // beats an invisible process — but the level stays unconfirmed so the next launch escalates.
      // 进程活了十秒、窗口对象还在，但渲染进程始终没报出第一帧。仍然要显示——能让用户关掉的
      // 空白窗口好过看不见的进程——但级别保持未确认，下次启动继续降级。
      writeStartupLog('graphics: level=' + graphicsLevelName + ' left unconfirmed (no first paint)');
    }
  }, 10000);

  mainWindow.once('ready-to-show', () => {
    clearTimeout(showFallbackTimer);
    confirmGraphicsLevel('ready-to-show');
    if (startHidden) {
      writeStartupLog('window: ready-to-show fired, staying hidden (autostart)');
      return;
    }
    writeStartupLog('window: ready-to-show fired, showing');
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.show();
    }
  });

  // Why these two listeners exist: without them a crashed renderer is completely invisible. The
  // window just never appears, no dialog is raised, and the process keeps running so the user
  // cannot even tell something failed.
  // 为什么需要这两个监听：没有它们，渲染进程崩溃是完全不可见的——窗口只是不出现，不弹任何对话框，
  // 而进程还在跑，用户连"出错了"都判断不了。
  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    const line = 'renderer gone: reason=' + details.reason + ' exitCode=' + details.exitCode;
    console.error('[renderer] ' + line);
    writeStartupLog(line);
    // A dead renderer leaves a blank window; report it rather than showing nothing.
    // 渲染进程死了只会留一个空白窗口；与其什么都不显示，不如把原因报出来。
    if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.isVisible()) {
      mainWindow.show();
    }
  });

  mainWindow.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL) => {
    writeStartupLog('load failed: ' + errorCode + ' ' + errorDescription + ' url=' + validatedURL);
  });

  mainWindow.webContents.on('did-finish-load', () => {
    writeStartupLog('window: did-finish-load');
    // The document parsed and its scripts ran. This is later and weaker than `ready-to-show` as
    // proof of painting, but if it happens first (a slow, paused compositor) it still means the
    // renderer is alive, which is what the escalation levels care about.
    // 文档解析完、脚本跑完了。作为"绘制成功"的证据它比 `ready-to-show` 更晚也更弱，
    // 但如果它先到（合成器慢或被挂起），仍然说明渲染进程活着——降级逻辑关心的就是这一点。
    confirmGraphicsLevel('did-finish-load');
  });

  // External links always go to the system browser, never a new in-app window
  // 外链一律走系统浏览器，不在应用内开新窗口
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  // With a tray icon present, closing the window means "put it away", not "stop". Without this the
  // first close would take the tray down with the process, and a background start would be
  // indistinguishable from a normal one.
  // 有托盘图标时，关窗口意味着「收起来」而不是「停掉」。少了这一段，第一次关闭就会连带托盘一起
  // 结束进程，后台启动也就和普通启动没区别了。
  mainWindow.on('close', (event) => {
    if (quitting || !tray.exists()) {
      return;
    }
    event.preventDefault();
    mainWindow.hide();
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
    // The window is gone, so any captured baseline is meaningless: the next window starts at
    // opacity 1. Clearing it keeps `isDimmed()` honest instead of reporting a dimmed window that
    // no longer exists — which would make the next `stop` look like a no-op.
    // 窗口没了，抓下来的基准也就没意义了：下一个窗口是从透明度 1 建出来的。
    // 清掉它能让 `isDimmed()` 说真话，而不是报一个已经不存在的变淡窗口——那会让下一次 `stop`
    // 看起来像什么都没做。
    windowOpacity.reset();
  });
}

function registerIpc() {
  ipcMain.handle(CALL.WINDOW_OPACITY, (event, dim) => {
    // The sender decides *which* window, not this module: the request always comes from the window
    // that owns the stick panel, and looking it up from the event avoids dimming some other window
    // if a second one ever exists.
    // 由发送方决定是*哪个*窗口，而不是本模块：请求总是来自拥有摇杆面板的那个窗口，
    // 从事件里取能避免将来存在第二个窗口时淡错了对象。
    const win = BrowserWindow.fromWebContents(event.sender);
    return windowOpacity.apply(win, dim === true);
  });
  ipcMain.handle(CALL.LIST_TARGETS, (_event, options) => {
    const opts = options || {};
    return service.listTargets(opts);
  });
  ipcMain.handle(CALL.START_INJECT, (_event, pid) => service.start(Number(pid)));
  ipcMain.handle(CALL.CANCEL_INJECT, () => {
    service.cancel();
    return { ok: true };
  });
  ipcMain.handle(CALL.READ_LOG, (_event, lines) => service.recentLog(Number(lines) || 300));
  ipcMain.handle(CALL.APP_INFO, () => ({ ...service.info(), theme: themeState() }));
  ipcMain.handle(CALL.SET_THEME, (_event, mode) => {
    const state = applyTheme(String(mode || 'auto'));
    persistTheme();
    return state;
  });
  ipcMain.handle(CALL.SETTINGS_READ, (_event, target) => ({
    injector: { ...settings.readAppSettings(), theme: themeState(), version: app.getVersion() },
    mod: settings.readModSettings(target || {})
  }));
  ipcMain.handle(CALL.SETTINGS_WRITE_MOD, (_event, patch, target) =>
    settings.writeModSettings(patch || {}, target || {}));
  ipcMain.handle(CALL.SETTINGS_WRITE_APP, (_event, patch) => {
    const next = settings.writeAppSettings(patch || {});
    // Switching language only affects the main process's own title and messages; the renderer
    // reloads the whole page, so there is nothing to broadcast here.
    // 切语言只影响主进程自己的标题与消息；渲染层会整页重载，所以这里不用广播
    if (patch && typeof patch.appLanguage === 'string') {
      i18n.setLocale(next.appLanguage);
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.setTitle(i18n.t('app.title'));
      }
    }
    if (patch && typeof patch.launchOnStartup === 'boolean') {
      return applyLaunchOnStartup(next);
    }
    return next;
  });
  ipcMain.handle(CALL.I18N_INDEX, () => i18n.listLocales());
  ipcMain.handle(CALL.I18N_LOAD, (_event, code) => i18n.load(
    typeof code === 'string' && code ? code : settings.readAppSettings().appLanguage || undefined));

  ipcMain.handle(CALL.UPDATE_CHECK, (_event, options) => updater.check({
    via: options && options.via === 'mirror' ? 'mirror' : 'direct'
  }));
  ipcMain.handle(CALL.UPDATE_PROBE_MIRRORS, (event, assetUrl) => updater.probeMirrors(
    assetUrl,
    (result) => {
      if (!event.sender.isDestroyed()) {
        event.sender.send(UPDATE_EVENT.MIRROR, result);
      }
    }
  ));
  ipcMain.handle(CALL.UPDATE_ROUTES, () => updater.MIRRORS.map(({ id, name, host }) => ({ id, name, host })));
  ipcMain.handle(CALL.UPDATE_DOWNLOAD, async (event, update, mirrorId) => {
    try {
      const result = await updater.download(update, (received, total) => {
        if (!event.sender.isDestroyed()) {
          event.sender.send(UPDATE_EVENT.PROGRESS, { received, total });
        }
      }, typeof mirrorId === 'string' ? mirrorId : '');
      return { ok: true, file: result.file, sha: result.sha };
    } catch (error) {
      return { ok: false, error: error.message, code: error.code || '' };
    }
  });
  ipcMain.handle(CALL.UPDATE_APPLY, (_event, file) => updater.apply(file));
  ipcMain.handle(CALL.LOG_EXPORT, () => logExport.exportLogs(mainWindow));
  ipcMain.handle(CALL.APP_UNINSTALL, (event, options) => uninstaller.uninstall(options));
  ipcMain.handle(CALL.SETTINGS_RELOCATE, (_event, target) => {
    const mod = settings.readModSettings({ ...(target || {}), relocate: true });
    return { path: mod.path, exists: mod.exists, values: mod.values, source: mod.source };
  });
  ipcMain.handle(CALL.OPEN_FOLDER, (_event, which) => {
    if (which === 'logs' || which === 'bridge-tap') {
      return shell.openPath(require('./inject/log-tail').logDir());
    }
    if (which === 'mod') {
      const configPath = settings.modConfigPath();
      return configPath ? shell.openPath(path.dirname(configPath)) : '未定位到模组配置文件';
    }
    const dir = which === 'injector'
      ? path.dirname(service.info().injectorPath)
      : require('./inject/log-tail').logDir();
    return shell.openPath(dir);
  });
  ipcMain.on(CALL.WINDOW, (event, op) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (!win) {
      return;
    }
    if (op === 'minimize') {
      win.minimize();
    } else if (op === 'maximize') {
      // Toggle, so one button covers both states · 切换，一个按钮管两种状态
      if (win.isMaximized()) {
        win.unmaximize();
      } else {
        win.maximize();
      }
    } else if (op === 'close') {
      win.close();
    }
  });
}

// Development only: point userData elsewhere so a second instance does not fight a running one over
// the single-instance lock. Inert in packaged builds — `app.isPackaged` is true there, so an
// environment variable cannot change where settings, cache or logs are read from once installed.
// 仅开发期有效：把 userData 指到别处，避免第二个实例和正在运行的实例抢单实例锁。
// 打包后 `app.isPackaged` 为真，这段不生效 —— 安装版无法用环境变量改变设置、缓存与日志的
// 读写位置。
if (!app.isPackaged && process.env.BT_USER_DATA) {
  app.setPath('userData', process.env.BT_USER_DATA);
}

/* ── GPU fallback ───────────────────────────────────────── */
/* ── GPU 兜底 ────────────────────────────────────────────── */

/**
 * Escalating graphics fallback.
 *
 * <p>A GPU process that fails to initialise can take the whole browser process down on some
 * drivers. The field signature is a log that stops right after `createWindow returned` — the window
 * object exists, `loadFile` was called, and then the process simply disappears: no
 * `ready-to-show`, no `did-finish-load`, no `renderer gone`, no exception, and not even the 10s
 * "show anyway" fallback. A native crash unwinds nothing, so `uncaughtException` never fires.
 *
 * <p>The first attempt at this used a marker written before `new BrowserWindow` and cleared right
 * after it. That was useless here: the crash happens *after* the window object is constructed and
 * *before* the first paint, so the marker was already gone. The marker must therefore live from
 * just before `new BrowserWindow` all the way to the first successful paint.
 *
 * <p>Three levels, each strictly less demanding on the driver than the one before:
 * <ol start="0">
 *   <li>`hardware` — the default.</li>
 *   <li>`software` — `disableHardwareAcceleration()`. Chromium still composites, but on the CPU, so
 *       a broken driver or a VM display adapter can no longer take the process down.</li>
 *   <li>`safe` — additionally `disable-gpu-compositing` and `disable-gpu-rasterization` plus SwiftShader
 *       as the WebGL backend. For machines where even software compositing through the GPU process
 *       fails.</li>
 * </ol>
 *
 * <p>The level is only known before `app.whenReady()`, because the GPU process is created during
 * Chromium startup and disabling it later has no effect. So it is persisted: a launch that dies
 * before its first paint leaves `graphics-level.json` at the level it was attempting, and the next
 * launch starts one step lower. The counter resets as soon as a window reaches first paint, so a
 * one-off crash costs exactly one degraded launch instead of crippling rendering forever.
 *
 * <p>`BT_DISABLE_GPU=1` or `--disable-gpu` still forces the `software` level directly — that is
 * what a support reply tells a user to try first.
 *
 * <p>逐级降低图形要求的兜底。
 *
 * <p>GPU 进程初始化失败在某些驱动上会把整个浏览器进程带崩。现场特征是日志停在
 * `createWindow returned` 之后——窗口对象有了、`loadFile` 也调了，然后进程直接消失：
 * 没有 `ready-to-show`、没有 `did-finish-load`、没有 `renderer gone`、没有异常，
 * 连 10 秒"无论如何先显示"的兜底都没等到。原生崩溃不展开调用栈，`uncaughtException` 不会触发。
 *
 * <p>第一版实现是在 `new BrowserWindow` 之前写标记、建完立刻清掉——在这个场景下完全无效：
 * 崩溃发生在窗口对象构造**之后**、首次绘制**之前**，标记早就被删了。
 * 标记必须从 `new BrowserWindow` 之前一直活到首次绘制成功。
 *
 * <p>三个级别，对驱动的依赖一级比一级轻：
 * <ol start="0">
 *   <li>`hardware`：默认。</li>
 *   <li>`software`：`disableHardwareAcceleration()`。Chromium 仍然合成，但走 CPU，
 *       坏驱动或虚拟机显示适配器再也带不崩进程。</li>
 *   <li>`safe`：再加 `disable-gpu-compositing` / `disable-gpu-rasterization`，
 *       WebGL 走 SwiftShader。给连软件合成都过不去的机器。</li>
 * </ol>
 *
 * <p>级别只能在 `app.whenReady()` 之前确定——GPU 进程在 Chromium 启动时就创建了，之后再禁用无效。
 * 所以它必须持久化：某次启动在首次绘制前死掉，就把 `graphics-level.json` 留在当时尝试的级别，
 * 下次启动从低一级开始。一旦窗口成功绘制，计数立刻归零，偶发崩溃只损失一次降级启动，
 * 不会永久残废渲染路径。
 *
 * <p>`BT_DISABLE_GPU=1` 或 `--disable-gpu` 仍然直接强制 `software` 级——客服话术第一条。
 */
const GRAPHICS_LEVELS = ['hardware', 'software', 'safe'];
const GRAPHICS_STATE_NAME = 'graphics-level.json';

function graphicsStatePath() {
  try {
    return path.join(app.getPath('userData'), GRAPHICS_STATE_NAME);
  } catch {
    return null;
  }
}

/**
 * Read the level the previous launch was attempting.
 *
 * <p>Three states, and the difference between them decides whether to escalate:
 * <ul>
 *   <li><b>No record</b> (`known: false`) — a fresh install. There is no evidence either way, so
 *       start at the top level and do *not* escalate. Crucially it must not be treated as
 *       "confirmed": doing so would write `confirmed: true` before the first launch proves
 *       anything, and a crash on that very launch would then loop forever at the same level.</li>
 *   <li><b>Recorded and confirmed</b> — this level has painted successfully at least once. Stay.</li>
 *   <li><b>Recorded and unconfirmed</b> — the last attempt at this level died before painting.
 *       Escalate one step down.</li>
 * </ul>
 *
 * 读取上一次启动尝试的级别。
 *
 * <p>三种状态，它们之间的差别决定要不要降级：
 * <ul>
 *   <li><b>没有记录</b>（`known: false`）——全新安装。没有任何证据，所以从最高级开始，
 *       而且**不降级**。关键是绝不能把它当成"已确认"：那会在首次启动还没验证之前就写下
 *       `confirmed: true`，万一就在这一次崩了，之后就会永远卡在同一级别循环。</li>
 *   <li><b>有记录且已确认</b>——这个级别至少成功绘制过一次。保持。</li>
 *   <li><b>有记录但未确认</b>——上一次在这个级别绘制前就死了。降一级。</li>
 * </ul>
 */
function readGraphicsState() {
  try {
    const file = graphicsStatePath();
    if (!file || !fs.existsSync(file)) {
      return { level: 0, confirmed: false, known: false };
    }
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    const level = GRAPHICS_LEVELS.indexOf(parsed && parsed.level);
    if (level < 0) {
      return { level: 0, confirmed: false, known: false };
    }
    return { level, confirmed: parsed.confirmed === true, known: true };
  } catch {
    return { level: 0, confirmed: false, known: false };
  }
}

function writeGraphicsState(level, confirmed) {
  try {
    const file = graphicsStatePath();
    if (file) {
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(
        file,
        JSON.stringify({ level: GRAPHICS_LEVELS[level], confirmed: !!confirmed }) + '\n',
        'utf8'
      );
    }
  } catch {
    // Diagnostics must never be the thing that breaks startup
    // 诊断绝不能成为弄坏启动的那个东西
  }
}

const forceSoftware = process.argv.includes('--disable-gpu')
  || process.env.BT_DISABLE_GPU === '1';
const previousGraphics = readGraphicsState();
let graphicsLevel = previousGraphics.level;
let graphicsReason = 'default';
if (forceSoftware) {
  graphicsLevel = 1;
  graphicsReason = 'flag';
} else if (previousGraphics.known && !previousGraphics.confirmed && graphicsLevel < GRAPHICS_LEVELS.length - 1) {
  // The previous launch died before painting, so it never proved this level works
  // 上一次启动在绘制前就死了，说明这个级别没被验证过
  graphicsLevel += 1;
  graphicsReason = 'previous launch died before first paint';
}

const graphicsLevelName = GRAPHICS_LEVELS[graphicsLevel];
if (graphicsLevelName !== 'hardware') {
  app.disableHardwareAcceleration();
  if (graphicsLevelName === 'safe') {
    // Even software compositing through the GPU process can fail on some VMs and remote desktops;
    // these two flags move both compositing and rasterisation onto the CPU entirely.
    // 某些虚拟机和远程桌面上，连经 GPU 进程的软件合成也会失败；这两个开关把合成与光栅化
    // 整体挪到 CPU。
    app.commandLine.appendSwitch('disable-gpu-compositing');
    app.commandLine.appendSwitch('disable-gpu-rasterization');
    app.commandLine.appendSwitch('use-gl', 'swiftshader');
  }
}
// Record the level as unconfirmed *before* anything can crash. It is flipped to confirmed when a
// window reaches first paint, so a crash in between is visible to the next launch. Staying at the
// level the previous run already proved keeps it confirmed — no need to re-learn on every launch.
// A fresh install records its first level as unconfirmed too: the first launch has to earn it.
// 在任何可能崩溃的代码之前把级别记成"未确认"。窗口绘制成功后才翻成"已确认"，
// 于是中途崩溃对下一次启动是可见的。如果这次仍在上一轮已证实可用的级别上，就保持"已确认"，
// 不必每次启动都重新验证一遍。全新安装的第一级同样记为未确认——首次启动也得自己挣到这个确认。
const graphicsConfirmed = previousGraphics.known
  && previousGraphics.confirmed
  && graphicsLevel === previousGraphics.level;
writeGraphicsState(graphicsLevel, graphicsConfirmed);
writeStartupLog(
  'graphics: level=' + graphicsLevelName + ' reason=' + graphicsReason
  + ' previous=' + (previousGraphics.known ? GRAPHICS_LEVELS[previousGraphics.level] : 'none')
  + (previousGraphics.confirmed ? ' (confirmed)' : ' (unconfirmed)')
);

/* ── Native crash capture ───────────────────────────────── */
/* ── 原生崩溃采集 ────────────────────────────────────────── */

/**
 * List dumps left by a previous launch and report the newest one.
 *
 * <p>`getLastCrashReport()` is not used because it only reports crashes that went through the
 * uploader; with `uploadToServer: false` the local dump is written and then never surfaced, so the
 * API returns null even though a `.dmp` is sitting on disk. Reading the directory is the only
 * honest check — and it lets the log state, definitively, whether a native crash happened.
 *
 * <p>Returns a short summary so the armed/empty state is visible in one log line.
 *
 * 列出上一次启动留下的转储并报告最新一个。
 *
 * <p>不用 `getLastCrashReport()`：它只报经过上传器的崩溃；`uploadToServer: false` 时本地转储写完
 * 就再也不上报，即使 `.dmp` 躺在磁盘上，API 也返回 null。直接读目录才是诚实的检查——
 * 也只有这样，日志才能肯定地说"发生过/没发生过原生崩溃"。
 *
 * <p>返回一句摘要，让"已就绪/没有转储"在一行日志里就看得见。
 */
function logNativeCrashDumps() {
  try {
    const dir = app.getPath('crashDumps');
    if (!dir || !fs.existsSync(dir)) {
      return 'none (crashDumps dir absent)';
    }
    const dumps = fs.readdirSync(dir)
      .filter((name) => name.toLowerCase().endsWith('.dmp'))
      .map((name) => {
        const full = path.join(dir, name);
        return { name, full, at: (fs.statSync(full).mtimeMs || 0) };
      })
      .sort((a, b) => b.at - a.at);
    if (dumps.length === 0) {
      return 'none';
    }
    const newest = dumps[0];
    writeStartupLog(
      'crash: found ' + dumps.length + ' native dump(s), newest ' + newest.name
      + ' at ' + new Date(newest.at).toISOString() + ' in ' + dir
    );
    return dumps.length + ' in ' + dir;
  } catch (error) {
    return 'unreadable (' + (error && error.message) + ')';
  }
}

/**
 * Capture native crash dumps locally.
 *
 * <p>Earlier diagnosis repeatedly cited "no crash dump" as evidence that the process had not
 * reached the rendering stage. That inference was invalid: Crashpad writes nothing unless
 * `crashReporter.start()` has been called, and this project never called it. Absence of dumps
 * proved nothing at all.
 *
 * <p>`uploadToServer` stays false — nothing leaves the machine. The dump directory is inspected on
 * every launch by `logNativeCrashDumps()`, which turns a silent native death into something the
 * user can send back.
 *
 * <p>Must be called before `app.whenReady()` to be attached early enough.
 *
 * 在本地采集原生崩溃转储。
 *
 * <p>前几轮诊断多次把"没有崩溃转储"当成"进程还没走到渲染阶段"的证据。那个推论是无效的：
 * 只有调用过 `crashReporter.start()` 才会有 Crashpad 写入，而这个项目从来没调过。
 * 没有转储这件事什么也证明不了。
 *
 * <p>`uploadToServer` 保持 false——什么都不离开本机。每次启动都由 `logNativeCrashDumps()`
 * 检查转储目录，把静默的原生死亡变成可回传的证据。
 *
 * <p>必须在 `app.whenReady()` 之前调用才能足够早地挂上。
 */
try {
  crashReporter.start({
    productName: 'BridgeTap',
    companyName: 'BridgeTap',
    uploadToServer: false,
    compress: true
  });
  writeStartupLog('crash: reporter armed (local dumps only), dumps=' + logNativeCrashDumps());
} catch (error) {
  writeStartupLog('crash: crash reporter failed to start: ' + (error && error.message));
}

/**
 * A dead GPU or utility process takes the renderer with it, and Electron reports it here rather
 * than through any renderer event. Without this listener the only symptom is a window that never
 * paints — indistinguishable from the bug being chased.
 *
 * GPU 或 utility 进程死掉会把渲染进程一起带走，Electron 从这条路报告，而不是任何渲染层事件。
 * 没有这个监听，唯一症状就是窗口始终不绘制——和正在排查的 bug 长得一模一样。
 */
app.on('child-process-gone', (_event, details) => {
  writeStartupLog(
    'child process gone: type=' + details.type + ' reason=' + details.reason
    + ' exitCode=' + details.exitCode + ' name=' + (details.name || '-')
  );
});

/**
 * Start in the tray instead of on screen.
 *
 * <p>Both conditions are required: the scheduled task passes `--autostart`, and the setting is still
 * on. The flag alone is not enough — a stale task (left behind because the user removed the app
 * manually) must not silently produce an invisible process with no icon to find it by.
 *
 * 启动时进托盘而不上屏。
 *
 * <p>两个条件都要满足：计划任务传 `--autostart`，且设置仍然开着。只看 flag 不够——一个残留的任务
 * （用户手工删了程序而留下的）不能悄悄造出一个隐形进程，连个能找回它的图标都没有。
 */
const startHidden = autostart.launchedByTask(process.argv)
  && settings.readAppSettings().launchOnStartup === true;
if (startHidden) {
  writeStartupLog('startup: --autostart with launchOnStartup enabled, window stays hidden');
}

const gotLock = app.requestSingleInstanceLock();
writeStartupLog('single-instance lock: gotLock=' + gotLock + ' packaged=' + app.isPackaged);
if (!gotLock) {
  // A previous run that died badly can leave a process holding the lock with no window of its own.
  // Every later double-click then quits here instantly, and the user sees nothing at all — not even
  // a process. Recording it is the only way to tell this apart from "the app never started".
  // 上一次异常退出可能留下一个没有窗口却持有锁的进程。此后每次双击都在这里立刻退出，
  // 用户什么也看不到——连进程都看不到。记下来是区分它和"应用根本没启动"的唯一办法。
  writeStartupLog('second instance detected, quitting (window is in another process)');
  app.quit();
} else {  app.on('second-instance', () => {
    // A second double-click while the app sits in the tray must bring the window back, not just
    // focus a hidden one — `focus()` on a hidden window does nothing visible.
    // 应用在托盘里时再次双击，必须把窗口叫回来，而不是只给一个隐藏窗口 `focus()`——
    // 对隐藏窗口调用它是看不见任何效果的。
    showMainWindow();
  });

  app.whenReady().then(() => {
    writeStartupLog('whenReady: app is ready, version=' + app.getVersion() + ' electron=' + process.versions.electron);
    // Each step is logged before it runs, not after. A native crash takes the process down without
    // unwinding, so `uncaughtException` never fires and the last line written is the only evidence
    // of how far startup got. Logging before each call makes that line name the guilty step.
    // 每一步都在执行前记录，而不是之后。原生崩溃不会走异常展开，`uncaughtException` 根本不会触发，
    // 最后一行日志就是启动走到哪一步的唯一证据。在执行前记录，那一行就点名了凶手。
    writeStartupLog('step: i18n.setLocale');
    i18n.setLocale(settings.readAppSettings().appLanguage);
    // Tray before the window: `createWindow` installs a `close` handler that asks whether a tray
    // exists, and a background start needs somewhere for the window to be hidden *to*.
    // 托盘先于窗口：`createWindow` 里的 `close` 处理函数要问"托盘在不在"，而后台启动也需要有个
    // 「藏到这里去」的地方。
    writeStartupLog('step: applyTray');
    const launchOnStartup = settings.readAppSettings().launchOnStartup === true;
    applyTray(launchOnStartup);
    if (launchOnStartup) {
      // The setting is a promise the OS has to keep. Antivirus and system "cleanup" tools delete
      // scheduled tasks silently, so verify it every launch and put it back if it is gone — the
      // alternative is a toggle that reads "on" while nothing starts at boot.
      // 这个设置是系统要替我们兑现的承诺。杀软和系统"清理"工具会悄悄删掉计划任务，所以每次启动
      // 都核对一遍、没了就补回去——否则就是一个显示"开"、开机却什么都不启动的开关。
      const healed = autostart.sync(true);
      writeStartupLog(
        'autostart: task ' + (healed.ok
          ? (healed.changed ? 'was missing, re-created' : 'verified')
          : 'missing and could not be re-created: ' + healed.error)
      );
    }
    writeStartupLog('step: applyTheme');
    applyTheme(readThemeMode());
    writeStartupLog('step: new InjectService');
    service = new InjectService({ emit, log: writeStartupLog });
    writeStartupLog('step: registerIpc');
    registerIpc();
    writeStartupLog('step: updater.clearUpdateCache');
    updater.clearUpdateCache();
    writeStartupLog('step: createWindow');
    createWindow();
    writeStartupLog('step: createWindow returned');

    // Deferred update check after startup: keeps the first paint fast; when the repo has no
    // Release yet, check() silently returns null.
    // 启动后延迟检查更新：不拖首屏，仓库还没有 Release 时 check() 会静默返回 null
    const appSettings = settings.readAppSettings();
    if (appSettings.autoUpdateCheck !== false) {
      setTimeout(() => {
        updater.check({ via: appSettings.useMirrorCheck === true ? 'mirror' : 'direct' }).then((result) => {
          if (result.ok && result.update && mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.webContents.send(UPDATE_EVENT.AVAILABLE, result.update);
          }
        }).catch(() => {
          // A failed check doesn't disturb the user; the settings page has a manual entry point
          // 检查失败不打扰用户，设置页里有手动入口
        });
      }, 3000);
    }

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
      }
    });
  });

  app.on('before-quit', () => {
    // Every quit path (tray menu, uninstall, OS shutdown) goes through here, so this is the one
    // place the `close` interceptor can learn that the window is meant to disappear for real.
    // 所有退出路径（托盘菜单、卸载、系统关机）都经过这里，所以 `close` 拦截器只需要在这一处
    // 得知「窗口这次是真的要消失了」。
    quitting = true;
  });

  app.on('window-all-closed', () => {
    // With a tray icon the app is expected to outlive its window; without one, closing the last
    // window still means quit, exactly as before.
    // 有托盘图标时，应用本来就该活得比窗口久；没有托盘时，关掉最后一个窗口仍然是退出，与以前一致。
    if (process.platform !== 'darwin' && !tray.exists()) {
      app.quit();
    }
  });
}

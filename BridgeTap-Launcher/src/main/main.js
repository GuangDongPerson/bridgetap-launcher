'use strict';

const path = require('node:path');
const fs = require('node:fs');
const { app, shell, ipcMain, BrowserWindow, nativeTheme } = require('electron');
const { CALL, UPDATE_EVENT } = require('../shared/contract');
const { InjectService } = require('./inject');
const settings = require('./settings');
const i18n = require('./i18n');
const updater = require('./updater');
const logExport = require('./log-export');
const uninstaller = require('./uninstall');

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

if (process.platform === 'win32' && !isElevated()) {
  if (relaunchElevated()) {
    // The elevated instance takes over; exit cleanly here, or the single-instance lock turns it away
    // 提权后的新实例接手；这里必须退干净，否则单实例锁会把它挡回去
    app.exit(0);
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

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 768,
    minWidth: 1040,
    minHeight: 680,
    show: false,
    frame: false,
    /**
     * Windows 11 tints the window border with the system accent colour, which shows up as a pale
     * blue outline on a dark window. `false` opts this window out of it - the system setting and
     * every other window are untouched, and it is the documented way to do it per window.
     *
     * Win11 会把窗口边框染成系统强调色，深色窗口上就是一圈淡蓝轮廓。`false` 让这个窗口退出该
     * 行为：系统设置不动，其他窗口也不受影响，这也是官方给的「按窗口」开关。
     */
    accentColor: false,
    roundedCorners: true,
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
  });

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

  mainWindow.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));

  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
  });

  // External links always go to the system browser, never a new in-app window
  // 外链一律走系统浏览器，不在应用内开新窗口
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function registerIpc() {
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
  ipcMain.handle(CALL.APP_UNINSTALL, () => uninstaller.uninstall(mainWindow));
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

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) {
        mainWindow.restore();
      }
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    // Window title and main-process error messages use the same language; the renderer re-reads
    // the dictionary on its own.
    // 窗口标题与主进程的错误消息用同一门语言；渲染层会自己再读一次字典
    i18n.setLocale(settings.readAppSettings().appLanguage);
    // Set the theme before creating the window: background color and prefers-color-scheme must be
    // correct on the very first frame.
    // 先定主题再建窗口：窗口底色、prefers-color-scheme 都要在第一帧之前是对的
    applyTheme(readThemeMode());
    service = new InjectService({ emit });
    registerIpc();
    createWindow();

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

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
      app.quit();
    }
  });
}

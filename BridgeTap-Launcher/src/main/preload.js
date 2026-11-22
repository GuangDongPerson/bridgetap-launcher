'use strict';

const { contextBridge, ipcRenderer } = require('electron');
const { CALL, EVENT, UPDATE_EVENT, PHASE } = require('../shared/contract');

/**
 * Preload script: every capability the renderer can touch lives here, whitelist style.
 *
 * The contract comes from shared/contract.js, so the renderer neither needs (nor is allowed) to
 * hardcode any channel string.
 *
 * 预加载脚本：渲染进程能碰到的全部能力都在这里，白名单式。
 *
 * 契约来自 shared/contract.js，所以渲染层不需要（也不允许）写死任何通道字符串。
 */

const api = {
  info: () => ipcRenderer.invoke(CALL.APP_INFO),
  setTheme: (mode) => ipcRenderer.invoke(CALL.SET_THEME, mode),
  readSettings: (target) => ipcRenderer.invoke(CALL.SETTINGS_READ, target),
  writeModSettings: (patch, target) => ipcRenderer.invoke(CALL.SETTINGS_WRITE_MOD, patch, target),
  writeAppSettings: (patch) => ipcRenderer.invoke(CALL.SETTINGS_WRITE_APP, patch),
  relocateModConfig: (target) => ipcRenderer.invoke(CALL.SETTINGS_RELOCATE, target),
  listTargets: (options) => ipcRenderer.invoke(CALL.LIST_TARGETS, options),
  inject: (pid) => ipcRenderer.invoke(CALL.START_INJECT, pid),
  cancel: () => ipcRenderer.invoke(CALL.CANCEL_INJECT),
  readLog: (lines) => ipcRenderer.invoke(CALL.READ_LOG, lines),
  openFolder: (which) => ipcRenderer.invoke(CALL.OPEN_FOLDER, which),
  window: (op) => ipcRenderer.send(CALL.WINDOW, op),
  /**
   * Drag-time dimming for the HUD stick. The renderer says only start/stop — the opacity to restore
   * is remembered in the main process, so a duplicate or missing call cannot strand the window.
   *
   * 拖摇杆时的窗口变淡。渲染层只说开始/结束——要还原的透明度记在主进程里，
   * 所以重复或缺失的调用都不会把窗口困住。
   * @param {boolean} dim `true` = dim to the drag level, `false` = restore · true 变淡，false 还原
   * @returns {Promise<{ok:boolean, dimmed:boolean}>}
   */
  windowOpacity: (dim) => ipcRenderer.invoke(CALL.WINDOW_OPACITY, dim === true),
  /**
   * Be told that the main process restored the opacity by itself.
   *
   * <p>Happens when the window was minimized, hidden or deactivated mid-drag: main puts the
   * opacity back whether or not the page noticed. The renderer must drop its own "I am dimmed"
   * flag on that signal, otherwise the next drag would not send a fresh start edge and dimming
   * would stay dead for the rest of the session.
   *
   * 被告知主进程自己把透明度还原了。
   *
   * <p>拖到一半窗口被最小化、隐藏或失去激活时会发生：不管页面有没有察觉，主进程都会还原。
   * 渲染层收到这个信号必须清掉自己那个"我正在变淡"的标记，否则下一次拖动不会发新的开始边沿，
   * 这次运行剩下的时间里变淡就彻底失效了。
   *
   * @param {() => void} handler
   * @returns {() => void} Unsubscribe function · 取消订阅
   */
  onWindowOpacityReset: (handler) => {
    const listener = () => handler();
    ipcRenderer.on(CALL.WINDOW_OPACITY_RESET, listener);
    return () => ipcRenderer.removeListener(CALL.WINDOW_OPACITY_RESET, listener);
  },
  /** @param {(maximized:boolean)=>void} handler Called on maximise and restore · 最大化/还原时回调 */
  onWindowState: (handler) => {
    const listener = (_event, maximized) => handler(maximized);
    ipcRenderer.on(CALL.WINDOW_STATE, listener);
    return () => ipcRenderer.removeListener(CALL.WINDOW_STATE, listener);
  },
  listLocales: () => ipcRenderer.invoke(CALL.I18N_INDEX),
  loadLocale: (code) => ipcRenderer.invoke(CALL.I18N_LOAD, code),
  exportLogs: () => ipcRenderer.invoke(CALL.LOG_EXPORT),
  uninstall: (options) => ipcRenderer.invoke(CALL.APP_UNINSTALL, options),

  update: {
    /** @param {{via?:'direct'|'mirror'}} [options] Omitted = official host directly · 省略 = 直连官方 */
    check: (options) => ipcRenderer.invoke(CALL.UPDATE_CHECK, options),
    /** @param {string} assetUrl Original GitHub asset URL; the main process probes every route against it ·
     *   原始 GitHub 资产地址，主进程据此给每条线路测速 */
    probeMirrors: (assetUrl) => ipcRenderer.invoke(CALL.UPDATE_PROBE_MIRRORS, assetUrl),
    routes: () => ipcRenderer.invoke(CALL.UPDATE_ROUTES),
    /** @param {string} [mirrorId] Omitted or 'direct' = official host directly · 省略或 'direct' = 直连官方 */
    download: (update, mirrorId) => ipcRenderer.invoke(CALL.UPDATE_DOWNLOAD, update, mirrorId),
    apply: (file) => ipcRenderer.invoke(CALL.UPDATE_APPLY, file),
    /** @param {(update:object)=>void} handler */
    onAvailable: (handler) => {
      const listener = (_event, update) => handler(update);
      ipcRenderer.on(UPDATE_EVENT.AVAILABLE, listener);
      return () => ipcRenderer.removeListener(UPDATE_EVENT.AVAILABLE, listener);
    },
    /** @param {(progress:{received:number,total:number})=>void} handler */
    onProgress: (handler) => {
      const listener = (_event, progress) => handler(progress);
      ipcRenderer.on(UPDATE_EVENT.PROGRESS, listener);
      return () => ipcRenderer.removeListener(UPDATE_EVENT.PROGRESS, listener);
    },
    /** @param {(result:object)=>void} handler Called once per finished route · 每测完一条线路回调一次 */
    onMirror: (handler) => {
      const listener = (_event, result) => handler(result);
      ipcRenderer.on(UPDATE_EVENT.MIRROR, listener);
      return () => ipcRenderer.removeListener(UPDATE_EVENT.MIRROR, listener);
    }
  },

  /**
   * Subscribe to main-process events.
   *
   * 订阅主进程事件。
   *
   * @param {(message:{kind:string,payload:object})=>void} handler
   * @returns {() => void} Unsubscribe function · 取消订阅
   */
  onEvent: (handler) => {
    const listener = (_event, message) => handler(message);
    ipcRenderer.on(EVENT, listener);
    return () => ipcRenderer.removeListener(EVENT, listener);
  },

  PHASE: Object.freeze({ ...PHASE })
};

contextBridge.exposeInMainWorld('bridge', Object.freeze(api));

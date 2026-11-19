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
  /** @param {(maximized:boolean)=>void} handler Called on maximise and restore · 最大化/还原时回调 */
  onWindowState: (handler) => {
    const listener = (_event, maximized) => handler(maximized);
    ipcRenderer.on(CALL.WINDOW_STATE, listener);
    return () => ipcRenderer.removeListener(CALL.WINDOW_STATE, listener);
  },
  listLocales: () => ipcRenderer.invoke(CALL.I18N_INDEX),
  loadLocale: (code) => ipcRenderer.invoke(CALL.I18N_LOAD, code),
  exportLogs: () => ipcRenderer.invoke(CALL.LOG_EXPORT),
  uninstall: () => ipcRenderer.invoke(CALL.APP_UNINSTALL),

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

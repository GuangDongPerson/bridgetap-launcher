'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { Tray, Menu, nativeImage, app } = require('electron');

/**
 * Tray icon, created only while "launch on startup" is on.
 *
 * <p>The feature only makes sense with somewhere for the window to go, so the tray is tied to the
 * setting rather than always present: off means the launcher behaves exactly as before (close quits,
 * no tray icon). On means close hides to the tray and the app keeps running, which is what "starts
 * in the background at boot" promises.
 *
 * 托盘图标，只在「开机自启」开启时存在。
 *
 * <p>这个功能有意义的前提是窗口有个地方可去，所以托盘跟着设置走而不是常驻：关着时启动器与以前完全
 * 一样（关闭即退出、没有托盘图标）；开着时关闭收进托盘、进程继续活着——这才是「开机后在后台运行」
 * 承诺的东西。
 */

let tray = null;
let onShow = null;
let onQuit = null;

/**
 * Build the icon.
 *
 * <p>Read through `fs` rather than `nativeImage.createFromPath`: the PNG ships inside `app.asar`, and
 * a buffer read is the form that definitely works against an asar path. Resized to 16 px because a
 * 1024 px source is scaled by the shell and ends up soft.
 *
 * 构建图标。
 *
 * <p>用 `fs` 读而不是 `nativeImage.createFromPath`：这张 PNG 打在 `app.asar` 里，而读成 buffer 是
 * 对 asar 路径确定可用的形式。缩到 16px，因为 1024px 的源图交给外壳缩放会发虚。
 *
 * @returns {Electron.NativeImage}
 */
function trayIcon() {
  try {
    const file = path.join(app.getAppPath(), 'build', 'icon.png');
    const image = nativeImage.createFromBuffer(fs.readFileSync(file));
    if (image.isEmpty()) {
      return image;
    }
    const resized = image.resize({ width: 16, height: 16, quality: 'best' });
    // Windows trays want a template-free 16 px image; macOS would need setTemplateImage(true), and
    // this app is Windows-only, so the plain resize is enough.
    // Windows 托盘就要 16px 普通图；macOS 才需要 setTemplateImage(true)，本应用只跑 Windows，
    // 直接缩放就够。
    return resized.isEmpty() ? image : resized;
  } catch {
    // An empty image gives a tray entry with no visible icon rather than an exception at startup
    // 空图标只会得到一个没有图形的托盘项，而不是启动时抛异常
    return nativeImage.createEmpty();
  }
}

/**
 * @param {{title:string, showLabel:string, quitLabel:string,
 *          onShow:()=>void, onQuit:()=>void}} options
 * @returns {Electron.Tray|null}
 *
 * 创建托盘。已存在时直接返回。
 */
function ensure(options) {
  if (tray) {
    return tray;
  }
  onShow = options.onShow;
  onQuit = options.onQuit;
  try {
    tray = new Tray(trayIcon());
  } catch {
    tray = null;
    return null;
  }
  tray.setToolTip(options.title);
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: options.showLabel, click: () => onShow && onShow() },
    { type: 'separator' },
    { label: options.quitLabel, click: () => onQuit && onQuit() }
  ]));
  // Left click is the shortcut every tray app has; without it the only way back is the menu
  // 左键单击是托盘应用的通行习惯；没有它就只能走菜单
  tray.on('click', () => onShow && onShow());
  return tray;
}

/**
 * Remove the icon. Callers must make sure a window is visible first, otherwise the app becomes
 * unreachable: no tray, no window.
 *
 * 移除图标。调用方必须先确保有窗口可见，否则应用就再也够不着了：没托盘、也没窗口。
 */
function destroy() {
  if (!tray) {
    return;
  }
  try {
    tray.destroy();
  } catch {
    // Already gone
    // 已经没了
  }
  tray = null;
  onShow = null;
  onQuit = null;
}

function exists() {
  return tray !== null;
}

module.exports = { ensure, destroy, exists };

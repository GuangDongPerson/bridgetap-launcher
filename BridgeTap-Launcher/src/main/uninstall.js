/**
 * Uninstall: wipe the data this app leaves in the user directory, keep game configs and the entry
 * exe.
 *
 * All interaction is handled by the self-drawn dialog in the renderer; the main process only deletes
 * files:
 *   things that can go right now (settings.json, caches) are deleted now;
 *   the running app\<version> can't delete itself, so PowerShell is asked to finish the job after
 *   the process exits.
 *
 * Explicitly left untouched:
 *   - bridgetap.properties / bridgetap.log in the game directory
 *   - the entry exe the user double-clicks
 *
 * 卸载：清掉本程序在用户目录下留下的数据，保留游戏配置与入口 exe。
 *
 * 交互全部交给渲染层的自绘弹窗，主进程只负责删文件：
 *   能立刻删的（settings.json、缓存）现在就删；
 *   正在运行的 app\<version> 删不掉自己，交给 PowerShell 等进程退出后再收尾。
 *
 * 明确不碰的东西：
 *   - 游戏目录里的 bridgetap.properties / bridgetap.log
 *   - 用户双击的那个入口 exe
 */
const fs = require('node:fs');
const path = require('node:path');
const { app } = require('electron');
const { spawn } = require('node:child_process');

function buildScript(pid) {
  return [
    "$ErrorActionPreference = 'SilentlyContinue'",
    `Wait-Process -Id ${pid} -Timeout 45`,
    'Start-Sleep -Milliseconds 600',
    'Remove-Item -LiteralPath (Join-Path $env:LOCALAPPDATA "BridgeTap") -Recurse -Force',
    'Remove-Item -LiteralPath (Join-Path $env:APPDATA "BridgeTap") -Recurse -Force',
    ''
  ].join('\r\n');
}

function removeQuietly(target) {
  try {
    fs.rmSync(target, { recursive: true, force: true });
  } catch {
    // Still held by this process; the script deletes it again after exit
    // 正在被本进程占用，脚本会在退出后补删
  }
}

/**
 * @returns {{ok:boolean, error?:string}}
 */
function uninstall() {
  // Settings and caches under userData go first; a file lock on Cache doesn't matter, the script
  // covers it
  // userData 里的设置与缓存先清；Cache 有文件锁也无所谓，脚本兜底
  removeQuietly(path.join(app.getPath('appData'), 'BridgeTap'));

  const encoded = Buffer.from(buildScript(process.pid), 'utf16le').toString('base64');
  try {
    spawn('powershell.exe', ['-NoProfile', '-WindowStyle', 'Hidden', '-EncodedCommand', encoded], {
      detached: true,
      stdio: 'ignore',
      windowsHide: true
    }).unref();
  } catch (error) {
    return { ok: false, error: error.message };
  }

  // Leave 3 seconds so the UI can finish showing "uninstall complete", then exit
  // 留 3 秒让界面把「卸载完成」显示完，再退出
  setTimeout(() => app.exit(0), 3000);
  return { ok: true };
}

module.exports = { uninstall };

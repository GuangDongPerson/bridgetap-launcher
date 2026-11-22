'use strict';

/**
 * Uninstall: wipe what this app left on the machine, and optionally keep the user's configuration.
 *
 * <p>Two modes, chosen by the checkbox in the confirm dialog:
 *
 * <table>
 *   <tr><th></th><th>default</th><th>keepConfig</th></tr>
 *   <tr><td>%LOCALAPPDATA%\BridgeTap</td><td>whole directory</td><td>only app\</td></tr>
 *   <tr><td>%APPDATA%\BridgeTap (settings, theme, mod-config, caches)</td><td>deleted</td><td>kept</td></tr>
 *   <tr><td>game config + bridgetap.log</td><td>deleted</td><td>kept</td></tr>
 *   <tr><td>%TEMP%\bridgetap-update</td><td>deleted</td><td>kept</td></tr>
 * </table>
 *
 * <p>Why the split exists: the extracted app under `%LOCALAPPDATA%\BridgeTap\app\<version>` is
 * replaced on every version change and is pure bulk (hundreds of MB). Someone reinstalling or
 * moving to a new build wants that gone but does not want to redo their settings — so "keep the
 * configuration" deletes the bulk and nothing else.
 *
 * <p>What cannot be done in-process: `app\<version>\BridgeTap.exe` is the image this process is
 * running from, and Windows refuses to delete a running image. Those paths are handed to a helper
 * that retries after exit. The helper is started through `cmd.exe /c` on purpose — a bare detached
 * PowerShell was measured on a field machine spawning successfully and then executing none of its
 * statements, which is exactly how an earlier updater deleted nothing and reported success.
 *
 * <p>Explicitly untouched in both modes: the entry exe the user double-clicks.
 *
 * 卸载：清掉本程序在机器上留下的东西，并可以选择保留用户的配置。
 *
 * <p>两种模式，由确认弹窗里的勾选框决定：
 *
 * <table>
 *   <tr><th></th><th>默认</th><th>保留配置</th></tr>
 *   <tr><td>%LOCALAPPDATA%\BridgeTap</td><td>整个目录</td><td>仅 app\</td></tr>
 *   <tr><td>%APPDATA%\BridgeTap（设置、主题、mod-config、缓存）</td><td>删除</td><td>保留</td></tr>
 *   <tr><td>游戏配置 + bridgetap.log</td><td>删除</td><td>保留</td></tr>
 *   <tr><td>%TEMP%\bridgetap-update</td><td>删除</td><td>保留</td></tr>
 * </table>
 *
 * <p>为什么要分这两种：`%LOCALAPPDATA%\BridgeTap\app\<版本>` 下解包出来的应用每次换版本都会被替换，
 * 是纯粹的体积（几百 MB）。重装或换新构建的人想清掉它，但不想重做设置——所以"保留配置"只删体积。
 *
 * <p>进程内做不到的部分：`app\<版本>\BridgeTap.exe` 正是本进程运行的镜像，Windows 不允许删除运行中的
 * 镜像。这些路径交给一个在退出后重试的助手。助手刻意通过 `cmd.exe /c` 启动——现场实测过裸的 detached
 * PowerShell 能创建成功、却一条语句都不执行，上一版更新器就是这样"什么都没删还报成功"。
 *
 * <p>两种模式下都不碰：用户双击的那个入口 exe。
 */

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { app } = require('electron');
const { spawn } = require('node:child_process');
const settings = require('./settings');

/** `%LOCALAPPDATA%\BridgeTap` — extracted app, agent logs, scan trail, agent payload. */
function localBridgeTap() {
  const base = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
  return path.join(base, 'BridgeTap');
}

/** `%APPDATA%\BridgeTap` — settings.json, theme.json, mod-config.json, Chromium state. */
function roamingBridgeTap() {
  return path.join(app.getPath('appData'), 'BridgeTap');
}

/** `%TEMP%\bridgetap-update` — downloaded installers, one full copy per version. */
function updateCache() {
  return path.join(app.getPath('temp'), 'bridgetap-update');
}

function helperLog() {
  return path.join(app.getPath('temp'), 'bridgetap-uninstall.log');
}

/**
 * The game-side files this app created.
 *
 * <p>`bridgetap.log` is always `<APPDATA>\.minecraft\logs\bridgetap.log` — the agent hard-codes that
 * location so the file lands next to the client's own logs (see `Log.openGameLog`). The properties
 * file follows each client's working directory, so `.minecraft\config\` and Lunar's own `config\`
 * are both candidates; `knownConfigPaths()` adds whatever the launcher learned from agent.log.
 *
 * 本程序在游戏侧留下的文件。
 *
 * <p>`bridgetap.log` 恒为 `<APPDATA>\.minecraft\logs\bridgetap.log`——agent 把这个位置写死了，
 * 为的是让它和客户端自己的日志放在一起（见 `Log.openGameLog`）。配置文件跟着各客户端的工作目录走，
 * 所以 `.minecraft\config\` 与 Lunar 自己的 `config\` 都是候选；`knownConfigPaths()` 再补上
 * 启动器从 agent.log 里学到的那些。
 */
function gameFiles() {
  const roaming = app.getPath('appData');
  const local = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
  const files = new Set([
    path.join(roaming, '.minecraft', 'logs', 'bridgetap.log'),
    path.join(roaming, '.minecraft', 'config', 'bridgetap.properties'),
    path.join(local, 'Programs', 'Lunar Client', 'config', 'bridgetap.properties')
  ]);
  try {
    for (const known of settings.knownConfigPaths()) {
      files.add(known);
    }
  } catch {
    // Learning from the log is a bonus; the fixed candidates above already cover both clients
    // 从日志里学是加分项；上面的固定候选已经覆盖两个客户端
  }
  return [...files];
}

function removeTreeQuietly(target) {
  try {
    fs.rmSync(target, { recursive: true, force: true });
    return true;
  } catch {
    // Held by this process (the running image, Chromium's cache); the helper finishes it
    // 被本进程占住（运行中的镜像、Chromium 缓存）；助手会收尾
    return false;
  }
}

function removeFileQuietly(target) {
  try {
    if (fs.statSync(target).isFile()) {
      fs.rmSync(target, { force: true });
    }
  } catch {
    // Missing is the normal case
    // 不存在才是常态
  }
}

function psQuote(value) {
  return "'" + String(value).replace(/'/g, "''") + "'";
}

/**
 * Retry loop instead of `Wait-Process`.
 *
 * <p>`Wait-Process -Id <pid>` throws when the pid has been recycled, and the old script died silently
 * on exactly that. Retrying the deletion itself is both simpler and strictly better: it succeeds the
 * moment the image is released, and a path that is already gone is reported and skipped.
 *
 * <p>`Set-Location` is the first statement for a reason. Windows keeps a handle on every process's
 * current directory, and the portable shell starts the app with its working directory set to
 * `%LOCALAPPDATA%\BridgeTap\app\<version>` (`SetOutPath $INSTDIR` in portable.nsi). A helper that
 * inherits that directory cannot delete the tree above it — measured: a recursive delete of
 * `%LOCALAPPDATA%\BridgeTap` from inside `app\<version>` removes **nothing at all**, while the same
 * command from `%SystemRoot%` removes everything. Moving out first covers the case where the spawn
 * option below is ever lost; `spawnHelper` also sets `cwd` so cmd.exe itself is outside.
 *
 * 用重试循环代替 `Wait-Process`。
 *
 * <p>pid 被回收时 `Wait-Process -Id <pid>` 会抛错，旧脚本正是因为这一点静默退出。直接重试删除既更简单，
 * 也严格更好：镜像一释放就成功，已经不在的路径直接记一笔跳过。
 *
 * <p>`Set-Location` 放在第一句是有原因的。Windows 会给每个进程的当前目录挂一个句柄，而便携壳启动应用时
 * 把工作目录设成了 `%LOCALAPPDATA%\BridgeTap\app\<版本>`（portable.nsi 里的 `SetOutPath $INSTDIR`）。
 * 继承了那个目录的助手删不掉它上层的树——实测：从 `app\<版本>` 里递归删 `%LOCALAPPDATA%\BridgeTap`
 * **一个文件都不删**，同一条命令从 `%SystemRoot%` 执行则全删。先挪出去是为了防住下面那个 spawn 选项
 * 哪天被去掉；`spawnHelper` 同时设了 `cwd`，让 cmd.exe 自己也在外面。
 */
function buildScript(targets, logFile) {
  const items = targets.map((target) => psQuote(target)).join(', ');
  return [
    "$ErrorActionPreference = 'SilentlyContinue'",
    `$log = ${psQuote(logFile)}`,
    "function Note($m) { Add-Content -LiteralPath $log -Value ((Get-Date).ToString('HH:mm:ss.fff') + ' ' + $m) -Encoding UTF8 }",
    '$inherited = (Get-Location).Path',
    'Set-Location -LiteralPath $env:SystemRoot',
    `Note ('helper start (pid ${process.pid}, inherited cwd ' + $inherited + ')')`,
    `foreach ($t in @(${items})) {`,
    '  for ($i = 0; $i -lt 60; $i++) {',
    '    if (-not (Test-Path -LiteralPath $t)) { Note ("gone " + $t); break }',
    '    Remove-Item -LiteralPath $t -Recurse -Force -ErrorAction SilentlyContinue',
    '    if (Test-Path -LiteralPath $t) { Start-Sleep -Milliseconds 400 } else { Note ("removed " + $t); break }',
    '  }',
    '  if (Test-Path -LiteralPath $t) { Note ("FAILED " + $t) }',
    '}',
    "Note 'done'",
    ''
  ].join('\r\n');
}

function spawnHelper(targets) {
  const logFile = helperLog();
  const encoded = Buffer.from(buildScript(targets, logFile), 'utf16le').toString('base64');
  // Never start inside the tree being deleted — see buildScript(). SystemRoot always exists and is
  // never one of the targets.
  // 绝不在待删目录里启动——见 buildScript()。SystemRoot 一定存在，也绝不会是待删目标。
  const cwd = process.env.SystemRoot || 'C:\\Windows';
  try {
    const child = spawn('cmd.exe',
      ['/c', 'powershell.exe', '-NoProfile', '-WindowStyle', 'Hidden', '-EncodedCommand', encoded],
      { cwd, detached: true, stdio: 'ignore', windowsHide: true });
    child.on('error', () => {
      // Nothing left to tell the user: the app is about to exit
      // 已经没有用户可通知的余地：本进程马上退出
    });
    child.unref();
    return true;
  } catch {
    return false;
  }
}

/**
 * @param {{keepConfig?: boolean}} [options]
 * @returns {{ok:boolean, error?:string}}
 */
function uninstall(options = {}) {
  const keepConfig = options.keepConfig === true;
  const deferred = [];

  if (keepConfig) {
    // Only the extracted copies go; settings, game config and logs stay
    // 只删解包出来的副本；设置、游戏配置与日志都留下
    deferred.push(path.join(localBridgeTap(), 'app'));
  } else {
    for (const file of gameFiles()) {
      removeFileQuietly(file);
    }
    removeTreeQuietly(roamingBridgeTap());
    removeTreeQuietly(updateCache());
    // Deferred on purpose: deleting our own application directory while it is running would take
    // the window down before it can show "uninstall complete", and Electron may still fault in
    // resources. The helper does it 3 seconds from now, once this process is gone.
    // 刻意延后：在本进程还在跑的时候删掉自己的应用目录，会让窗口来不及显示"卸载完成"，
    // 而且 Electron 可能还要读资源。助手在本进程退出 3 秒后动手。
    deferred.push(localBridgeTap());
    // Chromium rewrites Local State / Cache while shutting down, so this one is repeated after exit
    // Chromium 退出时会重写 Local State / Cache，所以这一份在退出后再删一次
    deferred.push(roamingBridgeTap());
  }

  if (!spawnHelper(deferred)) {
    return { ok: false, error: 'cleanup helper could not start' };
  }

  // Leave 3 seconds so the UI can finish showing "uninstall complete", then exit
  // 留 3 秒让界面把「卸载完成」显示完，再退出
  setTimeout(() => app.exit(0), 3000);
  return { ok: true };
}

module.exports = { uninstall };

'use strict';

const path = require('node:path');
const { spawnSync } = require('node:child_process');

/**
 * Windows autostart, implemented as a scheduled task rather than a Run-key entry.
 *
 * <p>Why not `HKCU\...\Run`: the launcher's manifest declares `requireAdministrator`, so anything
 * that starts it through the normal shell path raises a UAC prompt — every single boot. A task
 * registered with `/RL HIGHEST` starts it elevated *silently*, which is the only way "runs in the
 * background at boot" can actually mean background.
 *
 * <p>Why `ONLOGON` and not `ONSTART`: a tray icon lives in a user session. A task that fires at
 * machine start runs before anyone logs in and would never draw one.
 *
 * <p>Which exe gets registered: `PORTABLE_EXECUTABLE_FILE` when present. The portable shell exports
 * the path of the file the user actually double-clicked; `process.execPath` is the copy released
 * into `%LOCALAPPDATA%\BridgeTap\app\<version>\`, which the shell prunes when the version changes —
 * registering that path would produce a task pointing at a directory that no longer exists.
 *
 * Windows 开机自启，用计划任务实现，而不是 Run 注册表项。
 *
 * <p>为什么不用 `HKCU\...\Run`：启动器的清单声明了 `requireAdministrator`，任何经普通 shell 路径
 * 启动它的方式都会弹 UAC——每一次开机都弹。用 `/RL HIGHEST` 注册的计划任务则是**静默**以管理员身份
 * 启动，「开机后在后台运行」才真的成立。
 *
 * <p>为什么是 `ONLOGON` 而不是 `ONSTART`：托盘图标活在用户会话里。机器启动时就触发的任务跑在任何人
 * 登录之前，图标永远不会出现。
 *
 * <p>注册哪个 exe：有 `PORTABLE_EXECUTABLE_FILE` 就用它。portable 壳会导出用户实际双击的那个文件的
 * 路径；而 `process.execPath` 是释放到 `%LOCALAPPDATA%\BridgeTap\app\<版本>\` 的那一份，版本一变
 * 壳就会把它清掉——注册那个路径等于留下一个指向已消失目录的任务。
 */

const TASK_NAME = 'BridgeTap Launcher';
const AUTOSTART_FLAG = '--autostart';

function schtasksExe() {
  return path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'schtasks.exe');
}

function launcherExe() {
  return process.env.PORTABLE_EXECUTABLE_FILE || process.execPath;
}

function run(args) {
  const result = spawnSync(schtasksExe(), args, {
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    encoding: 'utf8'
  });
  if (result.error) {
    return { ok: false, error: result.error.message };
  }
  if (result.status !== 0) {
    const text = String(result.stderr || result.stdout || '').trim().split(/\r?\n/)[0];
    return { ok: false, error: text || ('schtasks exit ' + result.status) };
  }
  return { ok: true };
}

/**
 * Whether the task currently exists.
 *
 * <p>`/Query` exits non-zero when the task is absent, but the message is localised — the exit code is
 * the only portable signal, so nothing here parses stdout.
 *
 * 任务当前是否存在。
 *
 * <p>任务不存在时 `/Query` 返回非零，但输出是本地化的——退出码是唯一跨语言可靠的信号，
 * 所以这里不解析 stdout。
 */
function isEnabled() {
  const result = spawnSync(schtasksExe(), ['/Query', '/TN', TASK_NAME], {
    stdio: 'ignore',
    windowsHide: true
  });
  return result.status === 0;
}

function enable() {
  const exe = launcherExe();
  // /TR takes one string; the exe is quoted so a path with spaces survives, and the flag lets the
  // app tell a boot launch from a double-click.
  // /TR 收一个字符串；exe 加引号以容纳带空格的路径，flag 让应用能区分开机启动和手动双击。
  return run([
    '/Create', '/F',
    '/SC', 'ONLOGON',
    '/RL', 'HIGHEST',
    '/TN', TASK_NAME,
    '/TR', '"' + exe + '" ' + AUTOSTART_FLAG
  ]);
}

function disable() {
  return run(['/Delete', '/F', '/TN', TASK_NAME]);
}

/**
 * Make the OS match `wanted`.
 *
 * @param {boolean} wanted
 * @returns {{ok:boolean, changed:boolean, error?:string}}
 *
 * 让系统状态与 `wanted` 一致。
 */
function sync(wanted) {
  const current = isEnabled();
  if (wanted === current) {
    return { ok: true, changed: false };
  }
  const result = wanted ? enable() : disable();
  return { ok: result.ok, changed: true, error: result.error };
}

/**
 * `--autostart` present in the command line?
 *
 * 命令行里是否有 `--autostart`？
 */
function launchedByTask(argv) {
  return Array.isArray(argv) && argv.includes(AUTOSTART_FLAG);
}

module.exports = {
  TASK_NAME,
  AUTOSTART_FLAG,
  launcherExe,
  isEnabled,
  enable,
  disable,
  sync,
  launchedByTask
};

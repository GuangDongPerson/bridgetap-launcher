'use strict';

const { execFile } = require('node:child_process');

/**
 * Scan for running Minecraft JVMs.
 *
 * Why not reuse the injector's --list:
 *   that command only prints candidate PIDs, with no window title and no Lunar / Badlion
 *   distinction. The UI needs "which client is this" to show the right hint, so we scan here
 *   ourselves; the injection itself is still performed by the injector (see native-injector.js).
 *
 * PowerShell is used because Win32_Process exposes both CommandLine and the window title, whereas
 * tasklist can't get titles and wmic has been removed from new systems. PowerShell ships with
 * Windows, so it is not an extra dependency.
 *
 * 扫描正在跑的 Minecraft JVM。
 *
 * 为什么不复用注入器的 --list：
 *   那个命令只输出候选 PID，没有窗口标题，也不区分 Lunar / Badlion。UI 需要"客户端是哪一个"
 *   才能给出对应提示，所以这里自己扫一遍；注入动作仍然由注入器执行（见 native-injector.js）。
 *
 * 用 PowerShell 是因为 Win32_Process 能同时拿到 CommandLine 与窗口标题，
 * 而 tasklist 拿不到标题、wmic 已被新系统移除。PowerShell 是 Windows 自带组件，不算额外依赖。
 */

const SCRIPT = `
$ErrorActionPreference = 'SilentlyContinue';
$list = @();
Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" | ForEach-Object {
  $p = $_
  $title = '';
  try { $title = (Get-Process -Id $p.ProcessId).MainWindowTitle } catch {}
  $list += [pscustomobject]@{ pid = $p.ProcessId; cmd = $p.CommandLine; title = $title }
}
if ($list.Count -eq 0) { '[]' } else { ConvertTo-Json -InputObject @($list) -Compress -Depth 3 }
`;

/** @returns {{pid:number,title:string,cmd:string}[]} */
function runPowerShell(script, timeout = 15000) {
  return new Promise((resolve) => {
    const args = ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script];
    execFile('powershell.exe', args, { timeout, windowsHide: true, maxBuffer: 8 * 1024 * 1024 },
      (error, stdout, stderr) => {
        // Return an empty array on error: a failed scan must not crash the UI, and the next poll
        // will try again. But a log line is mandatory — otherwise "0 processes" and "the scan
        // command never ran" look exactly the same in the UI.
        // 出错时返回空数组：扫描失败不该让界面崩掉，下一次轮询会再试。
        // 但必须留一条日志——否则"0 个进程"和"扫描命令根本没跑起来"在界面上长得一模一样。
        if (error && !stdout) {
          console.error('[scan] powershell failed / powershell 执行失败：' + (error.message || error.code) +
            (stderr ? ' | stderr: ' + String(stderr).trim().slice(0, 300) : ''));
          resolve([]);
          return;
        }
        try {
          const parsed = JSON.parse(String(stdout).trim() || '[]');
          resolve(Array.isArray(parsed) ? parsed : [parsed]);
        } catch {
          console.error('[scan] output is not valid JSON / 输出不是合法 JSON：' + String(stdout).trim().slice(0, 300) +
            (stderr ? ' | stderr: ' + String(stderr).trim().slice(0, 300) : ''));
          resolve([]);
        }
      });
  });
}

/**
 * Classification signals.
 *
 * Lesson from a real machine (Badlion Client 4.4.4-f8775e4a-PRODUCTION4, 1.8.9):
 *   BLC **installs its whole ichor runtime inside the Lunar Client directory**, and the launch
 *   command line is
 *     -Djava.library.path="...\Programs\Lunar Client\native-modules;..."
 *     -agentpath:"...\Lunar Client\native-modules\badlion_js.dll"
 *     -cp "...\Lunar Client\libs\disruptor-3.4.2.jar;..."
 *     --badlionPath "...\Programs\Lunar Client"
 *   so "Lunar Client" appears all over the cmd line while the process is clearly Badlion.
 *
 * Conclusion: directory names / paths / jar locations **must not** be used as identity evidence (the
 * two clients share the same libraries); only client-specific launch arguments count. And Badlion must
 * be checked first — BLC's command line hits path keywords of both sides at once. The old code
 * concatenated title+cmd and looked for "lunar" first, so BLC was always labelled Lunar.
 *
 * 判定信号。
 *
 * 真机教训（Badlion Client 4.4.4-f8775e4a-PRODUCTION4，1.8.9）：
 *   BLC 把整套 ichor 运行时**装在 Lunar Client 的安装目录里**，启动命令行是
 *     -Djava.library.path="...\Programs\Lunar Client\native-modules;..."
 *     -agentpath:"...\Lunar Client\native-modules\badlion_js.dll"
 *     -cp "...\Lunar Client\libs\disruptor-3.4.2.jar;..."
 *     --badlionPath "...\Programs\Lunar Client"
 *   于是 cmd 里随地都是 "Lunar Client"，而进程明明是 Badlion。
 *
 * 结论：目录名 / 路径 / jars 位置**不能**当身份依据（两个客户端共用同一套库），
 * 只有启动参数里的客户端专属字段才算数；且必须先判 Badlion——BLC 的命令行会同时命中两边的路径关键字。
 * 旧的写法是 title+cmd 拼一起后先查 "lunar"，于是 BLC 一律被标成 Lunar。
 */
const BADLION_TITLE = /\bbadlion\b/i;
/** --badlionToken / --badlionPath / --badlionPid / net.badlion.odinwrapper / badlion_js.dll / BLClient */
const BADLION_ARG = /--badlion[a-z]*|\bnet\.badlion\b|badlion_js|\bodinwrapper\b|\bblclient\b/i;
const LUNAR_TITLE = /\blunar client\b/i;
/**
 * Real Lunar's gameDir is %USERPROFILE%\.lunarclient; arguments like --lunarClientVersion also only
 * appear on the Lunar side. ichor doesn't count: BLC runs on ichor too.
 * 真 Lunar 的 gameDir 在 %USERPROFILE%\.lunarclient；--lunarClientVersion 一类参数也只在 Lunar 侧出现。
 *  ichor 不能算：BLC 也走 ichor 运行时。 */
const LUNAR_ARG = /--lunar[a-z]*|\\\.lunarclient\\|\/\.lunarclient\/|\bmoonsworth\b/i;

function classify(entry) {
  const title = String(entry.title || '');
  const cmd = String(entry.cmd || '');

  const badlionTitle = BADLION_TITLE.test(title);
  const badlionArg = BADLION_ARG.test(cmd);
  const lunarTitle = LUNAR_TITLE.test(title);
  const lunarArg = LUNAR_ARG.test(cmd);

  // Order is priority: Badlion first. Title beats command line — the title is drawn by the client
  // itself and can't be faked.
  // 顺序即优先级：Badlion 先判。标题优先于命令行——标题是客户端自己画的，改不了。
  if (badlionTitle || badlionArg) {
    return {
      client: 'Badlion',
      injectable: true,
      // The note must fit on one line: the card footer has a fixed width, and a long sentence
      // squeezes the "injectable" pill into a vertical stack (learned the hard way in v1.0.0)
      // 备注要能一行放下：卡片底栏宽度固定，长句会把"可注入"胶囊挤成竖排（v1.0.0 实测踩过）
      noteKey: 'device.note.verified',
      noteArg: 'updateDisplay',
      evidence: badlionTitle ? 'title:badlion' : 'cmd:badlion-arg'
    };
  }
  if (lunarTitle || lunarArg) {
    return {
      client: 'Lunar',
      injectable: true,
      noteKey: 'device.note.verified',
      noteArg: 'renderGameOverlay',
      evidence: lunarTitle ? 'title:lunar' : 'cmd:lunar-arg'
    };
  }

  // Fallback: only when neither title nor arguments matched, fall back to "this name appeared
  // somewhere in the command line". Reaching here means the cmd path contains the client directory
  // but there is no client-specific launch argument — treat it as an older build of the same client.
  // 兜底：标题/参数都没命中时，才回退到"命令行里出现过这个名字"。
  // 走到这里说明 cmd 的路径里带了客户端目录，但没有任何专属启动参数——当成同款客户端的旧版本。
  const haystack = (title + ' ' + cmd).toLowerCase();
  if (haystack.includes('badlion')) {
    return { client: 'Badlion', injectable: true, noteKey: 'device.note.weak', noteArg: 'updateDisplay', evidence: 'cmd:path-only' };
  }
  if (haystack.includes('lunar')) {
    return { client: 'Lunar', injectable: true, noteKey: 'device.note.weak', noteArg: 'renderGameOverlay', evidence: 'cmd:path-only' };
  }
  if (haystack.includes('minecraft')) {
    return { client: 'Minecraft', injectable: false, noteKey: 'device.note.vanilla', evidence: 'cmd:minecraft' };
  }
  return { client: 'Java', injectable: false, noteKey: 'device.note.unknown', evidence: 'none' };
}

/**
 * @returns {Promise<import('../../shared/contract').Target[]>}
 */
async function scan() {
  const rows = await runPowerShell(SCRIPT);
  const targets = rows
    .filter((row) => row && Number.isFinite(Number(row.pid)) && Number(row.pid) > 0)
    // Keep only JVMs that "look like a game": the command line mentions minecraft or a client name,
    // and the title is non-empty
    // 只保留"看起来是游戏"的 JVM：命令行里有 minecraft 或客户端名，标题非空
    .filter((row) => {
      const hay = ((row.cmd || '') + ' ' + (row.title || '')).toLowerCase();
      return hay.includes('minecraft') || hay.includes('lunar') || hay.includes('badlion');
    })
    .map((row) => {
      const meta = classify(row);
      return {
        pid: Number(row.pid),
        title: row.title || meta.client,
        client: meta.client,
        injectable: meta.injectable,
        noteKey: meta.noteKey,
        noteArg: meta.noteArg,
        // Evidence (title:badlion / cmd:badlion-arg / cmd:path-only …), troubleshooting only, never
        // shown in the UI
        // 判定依据（title:badlion / cmd:badlion-arg / cmd:path-only …），只用于排障，界面不展示
        evidence: meta.evidence
      };
    })
    .sort((a, b) => Number(b.injectable) - Number(a.injectable) || a.pid - b.pid);

  return targets;
}

// classify is exported too: scripts/classify-test.js runs regressions with it, so a regex change
// doesn't mislabel BLC as Lunar next time
// classify 也导出：scripts/classify-test.js 用它跑回归，免得下次改正则又把 BLC 判成 Lunar
module.exports = { scan, classify };

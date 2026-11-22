'use strict';

const { execFile } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const logTail = require('./log-tail');
const nativeInjector = require('./native-injector');

/**
 * Scan for running Minecraft JVMs.
 *
 * Why this is a chain of three strategies instead of one shell command:
 *   A field report had BLC running with its game log being written, and the launcher showing no
 *   devices at all — with nothing anywhere saying why. The single `Get-CimInstance` call below can
 *   fail for reasons that never reach the user: WMI disabled or corrupt, an antivirus refusing a
 *   hidden `powershell.exe -Command <inline script>`, a stripped PATH, a locked-down execution
 *   policy. Every one of those produced the same picture: an empty list and a silent console.
 *
 *   So the scan now walks a ladder and records what each rung said. The ladder matters because the
 *   three sources fail independently:
 *     1. CIM      — richest (command line + title), needs WMI and a working PowerShell
 *     2. tasklist — no command line, but a plain Win32 call; the same source the injector trusts
 *     3. injector — the bundled exe's own `--list`, which already knows how to find these clients
 *
 *   Rung 1 alone is what shipped in 1.0.12, and it is the only rung that can see the command line.
 *   Losing it silently is what made the report impossible to act on.
 *
 * 扫描正在跑的 Minecraft JVM。
 *
 * 为什么改成三级策略而不是一条 shell 命令：
 *   现场报告里 BLC 在跑、游戏日志还在写，而启动器一个设备都不显示，且哪儿都没有原因。
 *   单条 `Get-CimInstance` 可能因多种原因失败，而这些原因从不抵达用户：WMI 被禁用或损坏、
 *   杀软拒绝一条隐藏窗口的 `powershell.exe -Command <内联脚本>`、PATH 被裁剪、执行策略收紧。
 *   每一种的表现都一模一样：空列表 + 静默控制台。
 *
 *   所以现在按梯子逐级尝试，并记录每一级说了什么。三级之所以必要，是因为它们独立失败：
 *     1. CIM      —— 信息最全（命令行 + 标题），但依赖 WMI 与可用的 PowerShell
 *     2. tasklist —— 没有命令行，但是纯 Win32 调用；注入器自己信任的也是这个来源
 *     3. 注入器   —— 自带 exe 的 `--list`，它本来就知道怎么找这些客户端
 *
 *   1.0.12 只有第一级，而它也是唯一能看到命令行的一级。它静默失败，才让那份报告无从下手。
 */

const LOG_NAME = 'scan.log';
/** Rotate at 512 KB: this file is a diagnostic trail, not a data store. */
const LOG_LIMIT = 512 * 1024;
/** Only the first rows are worth keeping once a scan starts returning real targets. */
const LOG_ROWS = 24;

/**
 * Where the scan trail lives. Same directory as agent.log, because that is the directory the log
 * exporter already knows how to collect — a diagnostic nobody can send is not a diagnostic.
 *
 * 诊断留痕的位置。与 agent.log 同目录，因为导出器已经知道怎么收这个目录——
 * 一个没人能发出来的诊断不算诊断。
 */
function scanLogPath() {
  return path.join(logTail.logDir(), LOG_NAME);
}

let lastSignature = '';

function note(line) {
  try {
    const file = scanLogPath();
    fs.mkdirSync(path.dirname(file), { recursive: true });
    try {
      if (fs.statSync(file).size > LOG_LIMIT) {
        fs.renameSync(file, file + '.1');
      }
    } catch {
      // No file yet
      // 还没有文件
    }
    fs.appendFileSync(file, new Date().toISOString() + ' ' + line + '\n', 'utf8');
  } catch {
    // A diagnostic that can break the scan is worse than no diagnostic
    // 会弄坏扫描的诊断比没有诊断更糟
  }
}

/**
 * Writes only when the outcome changes. The UI polls every three seconds; logging every poll would
 * bury the one line that matters under a megabyte of repetition.
 *
 * 只在结果变化时落笔。界面每三秒轮询一次，每次都写会把关键的那一行埋进一兆重复里。
 */
function noteOnce(line) {
  if (line === lastSignature) {
    return;
  }
  lastSignature = line;
  note(line);
}

const SCRIPT_CIM = `
$ErrorActionPreference = 'SilentlyContinue';
$Error.Clear();
$list = @();
Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" | ForEach-Object {
  $p = $_
  $title = '';
  try { $title = (Get-Process -Id $p.ProcessId).MainWindowTitle } catch {}
  $list += [pscustomobject]@{ pid = $p.ProcessId; name = $p.Name; cmd = $p.CommandLine; title = $title }
}
if ($Error.Count -gt 0) { 'ERR ' + $Error[0].Exception.Message }
elseif ($list.Count -eq 0) { '[]' }
else { ConvertTo-Json -InputObject @($list) -Compress -Depth 3 }
`;

/**
 * The image names that can be the game.
 *
 * <p>Only JVMs. Badlion and Lunar both ship an Electron launcher whose image name carries the client
 * name too, and listing those put eight "Lunar Client.exe" cards on screen that could not be
 * injected and could not be told apart — the game itself always runs as {@code javaw.exe}.
 *
 * 能装游戏的镜像名。
 *
 * <p>只要 JVM。Badlion 与 Lunar 都带一个 Electron 启动器，镜像名里同样有客户端名；把它们列出来
 * 会在界面上摆出八个既不能注入、又分不清彼此的 "Lunar Client.exe" 卡片——游戏本体始终是
 * {@code javaw.exe}。
 */
const JVM_IMAGE = /^(javaw?|java)\.exe$/i;

/**
 * Absolute path for a Windows tool, falling back to the bare name.
 *
 * <p>A portable build inherits whatever PATH its launcher had. Elevated starts, some installers and
 * a few "system optimiser" tools hand over a stripped PATH, and then {@code execFile('tasklist')}
 * dies with ENOENT while {@code C:\Windows\System32\tasklist.exe} sits right there. Resolving the
 * absolute path first removes that failure mode without changing behaviour when PATH is fine.
 *
 * Windows 工具的绝对路径，取不到时退回裸名。
 *
 * <p>便携版继承的是拉起它的那个进程的 PATH。提权启动、某些安装器、以及一些"系统优化"工具会交出一个
 * 被裁剪的 PATH，于是 {@code execFile('tasklist')} 直接 ENOENT，而
 * {@code C:\Windows\System32\tasklist.exe} 就躺在那里。先解析绝对路径可以消除这种失败模式，
 * 而 PATH 正常时行为不变。
 */
function systemBinary(relative) {
  const root = process.env.SystemRoot || process.env.windir || 'C:\\Windows';
  const full = path.join(root, relative);
  try {
    if (fs.statSync(full).isFile()) {
      return full;
    }
  } catch {
    // Fall through to the bare name and let execFile search PATH
    // 落到裸名，交给 execFile 去 PATH 里找
  }
  return path.basename(relative);
}

const POWERSHELL = systemBinary('System32\\WindowsPowerShell\\v1.0\\powershell.exe');
const TASKLIST = systemBinary('System32\\tasklist.exe');

/**
 * Runs one tool and never rejects.
 *
 * <p>Eight seconds, against measured times of 0.6 s (CIM), 0.7 s (tasklist) and 0.05 s (the
 * injector). Generous for a healthy machine, short enough that a blocked rung cannot push a scan
 * past the polling interval.
 *
 * 跑一个外部工具，永不 reject。
 *
 * <p>八秒，对应实测 0.6 秒（CIM）、0.7 秒（tasklist）、0.05 秒（注入器）。对健康的机器很宽松，
 * 又短到被拦住的一级不会把一次扫描拖过轮询周期。
 */
function run(file, args, timeout = 8000) {
  return new Promise((resolve) => {
    execFile(file, args, { timeout, windowsHide: true, maxBuffer: 8 * 1024 * 1024 },
      (error, stdout, stderr) => {
        resolve({
          error: error ? (error.message || String(error.code || 'failed')) : null,
          stdout: String(stdout || ''),
          stderr: String(stderr || '')
        });
      });
  });
}

/**
 * Rung 1: CIM. The only source with a command line, which is what tells Badlion from Lunar and what
 * the `--pid` verifier wants.
 *
 * 第一级：CIM。唯一带命令行的来源，它既区分 Badlion 与 Lunar，也是 `--pid` 校验需要的东西。
 */
async function viaCim() {
  const result = await run(POWERSHELL,
    ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', SCRIPT_CIM]);
  const out = result.stdout.trim();
  if (result.error && !out) {
    return { rows: [], error: 'powershell: ' + result.error + head(result.stderr) };
  }
  if (out.startsWith('ERR')) {
    return { rows: [], error: 'cim: ' + out.slice(4).trim() };
  }
  try {
    const parsed = JSON.parse(out || '[]');
    const rows = (Array.isArray(parsed) ? parsed : [parsed])
      .filter((row) => row && Number(row.pid) > 0)
      .map((row) => ({
        pid: Number(row.pid),
        image: String(row.name || ''),
        cmd: String(row.cmd || ''),
        title: String(row.title || '')
      }));
    return { rows, error: null };
  } catch {
    return { rows: [], error: 'cim: output is not JSON: ' + head(out) };
  }
}

/**
 * Rung 2: tasklist. No command line and no WMI, but a plain Win32 call that survives a broken
 * PowerShell. Column order is fixed; the header names are localised so they are never matched.
 *
 * 第二级：tasklist。没有命令行也不要 WMI，但它是纯 Win32 调用，PowerShell 坏了它还在。
 * 列顺序固定；表头是本地化的，所以从不按表头匹配。
 */
async function viaTasklist() {
  const result = await run(TASKLIST, ['/v', '/fo', 'csv', '/nh']);
  if (result.error && !result.stdout.trim()) {
    return { rows: [], error: 'tasklist: ' + result.error };
  }
  const rows = [];
  for (const line of result.stdout.split(/\r?\n/)) {
    if (!line.trim()) {
      continue;
    }
    // "javaw.exe","21664","Console","1","2,742,300 K","Running","PC\user","0:05:21","Lunar Client 1.8.9"
    const cells = line.split('","').map((cell) => cell.replace(/^"|"$/g, ''));
    if (cells.length < 2 || !JVM_IMAGE.test(String(cells[0]).trim())) {
      continue;
    }
    const pid = Number(cells[1]);
    if (!Number.isFinite(pid) || pid <= 0) {
      continue;
    }
    rows.push({
      pid,
      image: cells[0],
      cmd: '',
      // The title is last and may itself contain commas, so it is rebuilt from the tail
      // 标题在最后，本身可能带逗号，所以从尾部重新拼回来
      title: cells.length > 8 ? cells.slice(8).join('","') : ''
    });
  }
  return { rows, error: rows.length ? null : 'tasklist: no JVM' };
}

/**
 * Rung 3: the bundled injector's own `--list`. Last resort, and a meaningful one: if this rung
 * cannot see the client either, the problem is not the launcher's process listing at all — it is
 * something injection itself would hit next.
 *
 * <p>Two output shapes exist and both must parse, because `--list` is handled natively before the
 * Java side is ever started:
 *
 * <pre>
 *   native : "Java / Minecraft processes: 1"       then  "  29596  Badlion Minecraft Client ..."
 *   java   : "Java processes / Java 进程: 1"        then  "  29596\tBadlion Client\t<title>"
 * </pre>
 *
 * <p>The native lister walks {@code CreateToolhelp32Snapshot} — no WMI, no PowerShell — which is
 * exactly why it is kept as the last rung: it is the one path that still works when both shells are
 * unavailable.
 *
 * 第三级：自带注入器的 `--list`。最后手段，但很有意义：如果这一级也看不到客户端，
 * 那问题根本不在启动器的进程枚举上，而是注入本身接下来就会撞上的东西。
 *
 * <p>存在两种输出格式，两种都必须能解析，因为 `--list` 在 Java 侧被拉起之前就由原生层处理了：
 *
 * <pre>
 *   原生 : "Java / Minecraft processes: 1"        然后是  "  29596  Badlion Minecraft Client ..."
 *   Java : "Java processes / Java 进程: 1"        然后是  "  29596\tBadlion Client\t<title>"
 * </pre>
 *
 * <p>原生枚举走 {@code CreateToolhelp32Snapshot}——不碰 WMI、不碰 PowerShell——这正是它留作最后
 * 一级的原因：两条 shell 路都不可用时，它是唯一还能工作的那条。
 */
async function viaInjector() {
  if (!nativeInjector.available()) {
    return { rows: [], error: 'injector: not found' };
  }
  const result = await run(nativeInjector.injectorPath(), ['--list'], 12000);
  if (result.error && !result.stdout.trim()) {
    return { rows: [], error: 'injector: ' + result.error };
  }
  const rows = [];
  for (const line of result.stdout.split(/\r?\n/)) {
    if (!line.trim() || !/^\s*\d/.test(line)) {
      continue;
    }
    const tabbed = /^\s*(\d+)\t(.+?)\t(.*)$/.exec(line);
    const spaced = /^\s*(\d+)\s+(.*)$/.exec(line);
    const match = tabbed || spaced;
    if (!match) {
      continue;
    }
    const pid = Number(match[1]);
    if (!Number.isFinite(pid) || pid <= 0) {
      continue;
    }
    rows.push(tabbed
      ? { pid, image: '', cmd: '', title: match[3], kind: match[2].trim() }
      : { pid, image: '', cmd: '', title: match[2] });
  }
  return { rows, error: rows.length ? null : 'injector: no rows' };
}

function head(text, limit = 160) {
  const clean = String(text || '').replace(/\s+/g, ' ').trim();
  return clean ? ' | ' + clean.slice(0, limit) : '';
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
/** Build tools and IDEs are the JVMs we never want in the list. */
const NOISE = /\b(intellij|idea|gradle|eclipse|maven|jetbrains|kotlin-daemon|jps)\b/i;

function classify(entry) {
  const title = String(entry.title || '');
  const cmd = String(entry.cmd || '');
  // The injector's own verdict, when this row came from its `--list`
  // 这一行来自注入器 `--list` 时，它自己给出的判断
  const kind = String(entry.kind || '');

  if (/badlion/i.test(kind)) {
    return { client: 'Badlion', injectable: true, noteKey: 'device.note.verified', noteArg: 'updateDisplay', evidence: 'injector:kind' };
  }
  if (/lunar/i.test(kind)) {
    return { client: 'Lunar', injectable: true, noteKey: 'device.note.verified', noteArg: 'renderGameOverlay', evidence: 'injector:kind' };
  }
  if (/minecraft/i.test(kind)) {
    return { client: 'Minecraft', injectable: false, noteKey: 'device.note.vanilla', evidence: 'injector:kind' };
  }

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

  // A JVM with nothing to identify it by. This is what the game looks like when the launcher cannot
  // read another session's command line or window title — and hiding it is exactly how "BLC is
  // running and the list is empty" happens. The game always runs as javaw.exe, and the injector
  // itself ranks any javaw as a valid target, so it is offered with an explicit "unverified" note
  // rather than suppressed.
  // 一个没有任何可识别信息的 JVM。启动器读不到另一个会话的命令行或窗口标题时，游戏就长这样——
  // 而把它藏起来，正是"BLC 在跑而列表为空"的来路。游戏本体始终是 javaw.exe，注入器自己也把任何
  // javaw 当作有效目标，所以这里给它一张带"未验证"备注的卡片，而不是抹掉。
  if (JVM_IMAGE.test(String(entry.image || '').trim())) {
    return { client: 'Java', injectable: true, noteKey: 'device.note.unknown', evidence: 'java:unknown' };
  }
  return { client: 'Java', injectable: false, noteKey: 'device.note.unknown', evidence: 'none' };
}

/** One ladder rung, kept only if it says something new. */
let lastError = null;

/**
 * The scan in flight, if any.
 *
 * <p>The list is polled every three seconds. A rung that is being blocked — an antivirus holding a
 * hidden PowerShell, a WMI query timing out — can take seconds, and seven seconds of a ten-second
 * budget is not enough headroom to start a second scan on top of the first: they would queue, each
 * carrying its own timeouts. Callers share one run instead.
 *
 * 正在进行的那次扫描。
 *
 * <p>列表每三秒轮询一次。被拦住的一级——杀软卡住隐藏的 PowerShell、WMI 查询超时——可能耗掉几秒，
 * 在十秒的周期里再去叠一次扫描没有余量：它们会排队，各自带着超时。改成调用方共用同一次扫描。
 */
let inFlight = null;

/**
 * @returns {Promise<import('../../shared/contract').Target[]>}
 */
async function scan() {
  if (inFlight) {
    return inFlight;
  }
  inFlight = runLadder();
  try {
    return await inFlight;
  } finally {
    inFlight = null;
  }
}

async function runLadder() {
  const seen = new Map();
  const attempts = [];
  let error = null;

  const rungs = [
    ['cim', viaCim],
    ['tasklist', viaTasklist],
    ['injector', viaInjector]
  ];

  for (const [name, rung] of rungs) {
    let result;
    try {
      result = await rung();
    } catch (failure) {
      result = { rows: [], error: name + ': ' + (failure && failure.message ? failure.message : failure) };
    }
    attempts.push(name + '=' + result.rows.length + (result.error ? '(err)' : ''));
    if (result.error && !error) {
      error = result.error;
    }
    for (const row of result.rows) {
      // First rung wins: CIM knows more than tasklist, which knows more than --list
      // 先到先得：CIM 知道得比 tasklist 多，tasklist 又比 --list 多
      if (!seen.has(row.pid)) {
        seen.set(row.pid, row);
      }
    }
    if (seen.size > 0) {
      break;
    }
  }

  lastError = seen.size === 0 ? error : null;

  const all = Array.from(seen.values());
  const game = all.filter((row) => {
    const hay = ((row.cmd || '') + ' ' + (row.title || '') + ' ' + (row.image || '')).toLowerCase();
    if (hay.includes('minecraft') || hay.includes('lunar') || hay.includes('badlion')) {
      return true;
    }
    // A bare JVM is a candidate too — see the "java:unknown" branch in classify(). Build tools are
    // the one exception: they are the JVMs that are certainly not the game.
    // 裸 JVM 也算候选——见 classify() 里的 "java:unknown" 分支。构建工具是唯一的例外：
    // 那是最不可能在跑游戏的 JVM。
    return JVM_IMAGE.test(String(row.image || '').trim())
      && !NOISE.test((row.title || '') + ' ' + (row.cmd || ''));
  });

  const targets = game
    .filter((row) => !NOISE.test((row.title || '') + ' ' + (row.cmd || '')))
    .map((row) => {
      const meta = classify(row);
      return {
        pid: row.pid,
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

  // The trail: what each rung said, and — when a JVM was found but nothing was shown — the rows
  // that were dropped. That last part is the whole point: "BLC is running and the list is empty"
  // has to leave a trace.
  // 留痕：每一级说了什么；以及"找到了 JVM 却没显示"时被丢掉的那些行。
  // 最后这部分才是重点——"BLC 在跑而列表为空"必须留下痕迹。
  let trail = 'rungs[' + attempts.join(' ') + '] jvm=' + all.length + ' game=' + game.length
    + ' shown=' + targets.length + (error ? ' error=' + error : '');
  if (targets.length === 0 && all.length > 0) {
    trail += ' dropped=' + all.slice(0, LOG_ROWS).map((row) =>
      row.pid + '|' + (row.image || '?') + '|' + (row.title || '-') + '|' + head(row.cmd, 60)).join(' ; ');
  }
  noteOnce(trail);

  return targets;
}

/** Why the last scan came back empty, or null. Surfaced by the UI so silence stops being ambiguous. */
function failure() {
  return lastError;
}

// Every rung is exported alongside scan(): a machine where the list is empty needs the rungs probed
// one by one from a shell, and that has to be possible without editing the file.
// 每一级都和 scan() 一起导出：列表为空的机器需要逐级在命令行上探一遍，而这件事必须能在不改文件的前提下做到。
module.exports = {
  scan,
  classify,
  failure,
  scanLogPath,
  rungs: { cim: viaCim, tasklist: viaTasklist, injector: viaInjector }
};

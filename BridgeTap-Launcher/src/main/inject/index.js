'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { PHASE, EVENT_KIND } = require('../../shared/contract');
const { app } = require('electron');
const native = require('./native-injector');
const logTail = require('./log-tail');
const processScan = require('./process-scan');
const i18n = require('../i18n');

/**
 * payload.dll's own log; the path matches init_paths() in payload.c.
 * payload.dll 自己的日志，路径与 payload.c 里 init_paths() 一致。
 */
function nativeLogPath() {
  return path.join(logTail.logDir(), 'inject-native.log');
}

function fileSize(file) {
  try {
    return fs.statSync(file).size;
  } catch {
    return -1;
  }
}

/**
 * Snapshot both logs before injecting.
 *
 * The confirmation stage relies entirely on "did the file grow"; without a baseline there is no way
 * to tell what this run wrote from what a previous run left behind.
 *
 * 注入前给两份日志拍个快照。
 *
 * 确认阶段全靠"文件有没有变长"，没有基准就分不清是这一次写的还是上一次留下的。
 */
function snapshotLogs() {
  return {
    agent: fileSize(logTail.logPath()),
    native: { file: nativeLogPath(), size: fileSize(nativeLogPath()) }
  };
}

/**
 * Has the agent written anything yet? When it didn't exist at all before (-1), appearing with any
 * content now is enough.
 * agent 写过字了？之前完全不存在（-1）时，只要现在出现且非空就算。
 */
function agentWrote(snapshot) {
  const size = fileSize(logTail.logPath());
  if (size < 0) {
    return false;
  }
  return snapshot < 0 ? size > 0 : size > snapshot;
}

/**
 * Read only the newly appended part of a file; offset<0 means read from the start.
 * 只读某份文件的新增部分；offset<0 表示从头读。
 */
function readNew(file, offset) {
  const from = offset < 0 ? 0 : offset;
  try {
    const size = fs.statSync(file).size;
    if (size <= from) {
      return '';
    }
    const length = Math.min(size - from, 32 * 1024);
    const fd = fs.openSync(file, 'r');
    const buffer = Buffer.alloc(length);
    fs.readSync(fd, buffer, 0, length, from);
    fs.closeSync(fd);
    return buffer.toString('utf8');
  } catch {
    return '';
  }
}

/**
 * A notice bar fits one line, so overly long FATAL lines are truncated.
 *
 * Head and tail are kept rather than simply cutting the tail: the error code usually sits at the end
 * of the FATAL line (`AddToBootstrapClassLoaderSearch(...): -1`), and chopping it off throws away the
 * conclusion. The middle is the jar's absolute path, so omitting it costs nothing.
 *
 * 提示条一行放得下，太长的 FATAL 行截断。
 *
 * 保留头尾而不是简单截尾：FATAL 行末尾往往才是错误码（`AddToBootstrapClassLoaderSearch(...): -1`），
 * 砍掉就等于把结论丢了。中间那段是 jar 的绝对路径，省略没有损失。
 */
function compact(line) {
  const text = line.replace(/^\[BridgeTap\]\s*/, '').trim();
  if (text.length <= 110) {
    return text;
  }
  return text.slice(0, 74) + '\u2026' + text.slice(-32);
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Injection service: state machine + event outlet.
 *
 * This layer is the **only** place that knows "how injection is done". It emits just three kinds of
 * events to the outside:
 *   phase / targets / output  —— all defined in contract.js.
 * The renderer decides what to display and which animation to play based on them; neither side
 * references the other's code.
 *
 * 注入服务：状态机 + 事件出口。
 *
 * 这一层是**唯一**知道"注入是怎么做的"的地方。它对外只发三种事件：
 *   phase / targets / output  —— 全部在 contract.js 里定义。
 * 渲染进程据此决定显示什么、播什么动效；两边互不引用对方的代码。
 */
class InjectService {
  /**
   * @param {{emit:(kind:string,payload:object)=>void, log?:(message:string)=>void}} options
   */
  constructor(options) {
    this.emit = options.emit;
    /**
     * Optional sink for the startup trace (`launcher.log`). Passed in rather than required, so this
     * service stays usable without the launcher's logging — the default is "no log", not a crash.
     * 启动轨迹（`launcher.log`）的可选出口。用注入而不是直接 require，服务在没有启动器日志的
     * 场合也能用——默认是"不记"，不是崩掉。
     */
    this.log = typeof options.log === 'function' ? options.log : null;
    this.phase = PHASE.IDLE;
    this.busy = false;
    this.stopFollow = null;
    this.current = null;
    this.strategy = native;
    /**
     * Last scan failure already shown to the user.
     *
     * <p>The list is polled every three seconds, so an unthrottled notice would fire twenty times a
     * minute and bury itself. One notice per distinct failure: the user learns the scan is broken,
     * and a second, different failure still gets through.
     *
     * 已经告知用户的最后一条扫描失败信息。
     * 列表每三秒轮询一次，不节流的话一分钟弹二十次，等于没弹。同一条失败只提示一次：
     * 用户知道了扫描坏了，而换了一种失败仍然能进来。
     */
    this.scanError = null;
  }

  setPhase(phase, detail) {
    this.phase = phase;
    this.emit(EVENT_KIND.PHASE, detail ? { phase, detail } : { phase });
  }

  info() {
    return {
      // npm_package_version only has a value in a process launched via `npm run` and is always empty
      // after packaging, which would make the UI show the fallback string forever. app.getVersion()
      // reads package.json itself.
      // npm_package_version 只在 `npm run` 拉起的进程里有值，打包后恒为空，
      // 会让界面永远显示兜底的字符串。app.getVersion() 读的就是 package.json。
      version: app.getVersion(),
      injectorPath: native.injectorPath(),
      injectorReady: native.available()
    };
  }

  /**
   * @param {{silent?:boolean}} [options] With silent=true the state machine is untouched (used by
   *   background polling, so the UI doesn't keep flashing "scanning") ·
   *   silent=true 时不切状态机（后台轮询用，避免界面一直闪"扫描中"）
   */
  async listTargets(options = {}) {
    if (this.busy) {
      return [];
    }
    const silent = options.silent === true;
    if (!silent) {
      this.setPhase(PHASE.SCANNING);
    }
    let targets = [];
    try {
      targets = await processScan.scan();
    } catch (error) {
      this.emit(EVENT_KIND.NOTICE, { level: 'error', text: i18n.t('toast.scanFailed', error.message) });
    }
    // An empty list means two very different things: no client running, or the scan itself could not
    // run. `scan()` never throws, so without this the second case looked exactly like the first —
    // which is how a field report ended up with "BLC is running and the launcher shows nothing".
    // 空列表有两种完全不同的含义：没有客户端在跑，或者扫描本身跑不起来。
    // scan() 从不抛异常，所以少了这一段，第二种情况会和第一种长得一模一样——
    // 现场那份"BLC 在跑而启动器什么都不显示"的报告就是这么来的。
    if (targets.length === 0) {
      const failure = processScan.failure();
      if (failure && failure !== this.scanError) {
        this.scanError = failure;
        this.emit(EVENT_KIND.NOTICE, { level: 'error', text: i18n.t('toast.scanFailed', failure) });
      }
    } else {
      this.scanError = null;
    }
    this.emit(EVENT_KIND.TARGETS, { targets });
    if (!silent) {
      this.setPhase(PHASE.READY, targets.length ? undefined : 'no-targets');
    }
    return targets;
  }

  /**
   * @param {number} pid
   */
  async start(pid) {
    if (this.busy) {
      return { accepted: false, reason: 'busy' };
    }
    this.busy = true;
    this.current = pid;

    if (this.stopFollow) {
      this.stopFollow();
      this.stopFollow = null;
    }

    this.setPhase(PHASE.INJECTING, 'attach');
    // Snapshot before injecting: the confirmation stage judges by "did the file grow", and without a
    // baseline it can't tell this run's output from the previous one's
    // 注入前留底：确认阶段靠"文件有没有变长"判断，没有基准就分不清是本次写的还是上次的
    const before = snapshotLogs();
    let result = { ok: false, code: null, timedOut: false };

    try {
      result = await this.strategy.inject(pid, (line, stream) => {
        this.emit(EVENT_KIND.OUTPUT, { stream: stream || 'stdout', line });
      });
    } catch (error) {
      result = { ok: false, code: null, timedOut: false, error: error.message };
    }

    if (result.ok) {
      // The native injector returning 0 only means payload.dll was loaded into the target process by
      // LoadLibraryW. Whether JVMTI was acquired, the jar attached, the hook planted — it knows
      // nothing about any of it, so we must verify once here.
      // 原生注入器返回 0 只代表 payload.dll 被 LoadLibraryW 载进目标进程了。JVMTI 拿没拿到、
      // jar 挂没挂上、hook 打没打上，它一概不知道 —— 所以这里必须自己确认一次。
      const verdict = await this.confirmAgent(before);
      if (!verdict.ok) {
        this.setPhase(PHASE.FAILED, 'unconfirmed');
        this.emit(EVENT_KIND.NOTICE, { level: 'error', text: verdict.text });
        this.busy = false;
        this.current = null;
        return { accepted: true, ok: false };
      }
      this.setPhase(PHASE.ATTACHED, 'agent-loaded');
      this.emit(EVENT_KIND.NOTICE, {
        level: 'success',
        text: i18n.t('notice.injected')
      });
      this.stopFollow = logTail.follow(
        (line) => this.emit(EVENT_KIND.OUTPUT, { stream: 'agent', line }),
        { durationMs: 30_000 }
      );
    } else {
      this.setPhase(PHASE.FAILED, result.timedOut ? 'timeout' : 'inject-failed');
      this.logFailure(pid, result);
      this.emit(EVENT_KIND.NOTICE, {
        level: 'error',
        text: this.failureHint(result)
      });
    }

    this.busy = false;
    this.current = null;
    return { accepted: true, ok: result.ok };
  }

  /**
   * Record why a launch failed, with enough detail to tell the causes apart afterwards.
   *
   * <p>The user-facing sentence has to stay short, which means it necessarily drops the parts that
   * matter for diagnosis: which paths were checked, how big the file was, the raw spawn error, the
   * signal. Those go to `launcher.log`, next to the startup trace — so a support reply can ask for
   * one file instead of guessing. A missing injector is exactly the case where "which directory was
   * empty" is the whole answer, and it must survive in a log even though it cannot fit in a toast.
   *
   * 记录一次启动失败的原因，细节足够事后区分不同成因。
   *
   * <p>给用户的那句话必须短，也就必然丢掉对诊断至关重要的部分：查了哪些路径、文件多大、
   * spawn 的原始错误、信号。这些写进 `launcher.log`，和启动轨迹放在一起——于是客服只要索要
   * 一个文件，而不是靠猜。注入器缺失恰恰是「哪个目录是空的」就是全部答案的场景，
   * 哪怕塞不进提示条，也必须留在日志里。
   */
  logFailure(pid, result) {
    if (typeof this.log !== 'function') {
      return;
    }
    const parts = ['inject failed: pid=' + pid];
    if (result.error) {
      parts.push('error=' + result.error);
    }
    if (result.timedOut) {
      parts.push('timedOut=true');
    }
    if (typeof result.code === 'number' && Number.isFinite(result.code)) {
      parts.push('exitCode=' + result.code);
    }
    if (result.signal) {
      parts.push('signal=' + result.signal);
    }
    if (result.syscallCode) {
      parts.push('syscall=' + result.syscallCode);
    }
    if (result.message) {
      parts.push('message=' + result.message);
    }
    if (result.detail && Array.isArray(result.detail.checked)) {
      // Every candidate and its state: this is what makes a deleted file diagnosable at all.
      // Paths are resolved to absolute form because the first two candidates are built from
      // `process.resourcesPath`, which is empty outside a packaged app — logging a bare relative
      // path there would send the reader looking in the wrong directory.
      // 每个候选路径及其状态：文件被删时，全靠这一行才查得出是哪个位置空了。
      // 路径取绝对形式：前两个候选由 `process.resourcesPath` 拼出，打包环境之外它是空的——
      // 那时日志里只留一个相对路径，会把读日志的人引到错误的目录去。
      parts.push('checked=[' + result.detail.checked
        .map((item) => item.state + ':' + path.resolve(item.path))
        .join(' | ') + ']');
    }
    this.log(parts.join(' '));
  }

  /**
   * Did the agent actually come up.
   *
   * The first statement of both `Bootstrap.run` and `runNative` is `Log.info(...)`, and `Log`'s two
   * output streams are static field initializers (`Log.java:21-23`) — so as long as the Bootstrap
   * class is loaded and either entry point is called, `%LOCALAPPDATA%\BridgeTap\agent.log` is
   * necessarily created and gets a line written to it. Conversely, if that file didn't change, not a
   * single line of agent code ran, and the "injection complete" shown in the UI is a lie.
   *
   * After a timeout we don't fail outright; instead we read payload's own log (inject-native.log)
   * and surface the `FATAL:` line verbatim — in payload.c every failed step (jvm.dll / JavaVM /
   * JVMTI / attaching the jar / Class.forName) writes that prefix, and it points straight at the
   * step that died.
   *
   * agent 到底起没起来。
   *
   * `Bootstrap.run` / `runNative` 的第一句都是 `Log.info(...)`，而 `Log` 的两个输出流是静态
   * 字段初始化（`Log.java:21-23`）—— 只要 Bootstrap 类被加载并调用了任一入口，
   * `%LOCALAPPDATA%\BridgeTap\agent.log` 就必然被创建并写进一行。反过来，这个文件没动，
   * 就说明 agent 的代码一行都没执行过，界面上的"注入完成"是假的。
   *
   * 超时后不直接报失败，而是去读 payload 自己的日志（inject-native.log），
   * 把 `FATAL:` 那行原样带出来 —— payload.c 里 jvm.dll / JavaVM / JVMTI / 挂 jar /
   * Class.forName 每一步失败都写这个前缀，它直接指出死在哪一步。
   *
   * @param {{agent:number, native:{file:string,size:number}}} before File snapshot taken before
   *   injection · 注入前的文件快照
   * @returns {Promise<{ok:boolean, text?:string}>}
   */
  async confirmAgent(before, timeoutMs = 8000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      await delay(400);
      if (agentWrote(before.agent)) {
        return { ok: true };
      }
      // agent.log may never be written (if the client cleared LOCALAPPDATA, Log reroutes to temp),
      // but payload only writes this line after runNative() finishes and the probe works, so it
      // counts as hard evidence too.
      // agent.log 可能没写出来（客户端把 LOCALAPPDATA 清了，Log 会改道去 temp），
      // 但 payload 只在 runNative() 跑完、probe 调通之后才写这一行，所以它同样算硬证据。
      if (readNew(before.native.file, before.native.size).includes('payload init complete')) {
        return { ok: true };
      }
    }

    const lines = readNew(before.native.file, before.native.size)
      .split(/\r?\n/)
      .filter(Boolean);
    const fatal = lines.filter((line) => line.includes('FATAL:')).pop();
    if (fatal) {
      return { ok: false, text: i18n.t('notice.injectUnconfirmed', compact(fatal)) };
    }
    if (!lines.some((line) => line.includes('payload attached'))) {
      // payload didn't even write its own first line: the dll never really executed, or the target
      // process isn't a JVM
      // payload 连自己的第一行都没写下来：dll 没真正执行，或者目标进程不是 JVM
      return { ok: false, text: i18n.t('notice.injectUnconfirmed', i18n.t('reason.noAttachLine')) };
    }
    // Reaching here means JVMTI was acquired but initialization never finished: either it is still in
    // ClassScan, or the Java side threw (exceptions only go to stdout, and javaw has no console, so
    // this is the only way it can look).
    // 走到这里说明 JVMTI 已经拿到了，但初始化没跑完：要么还在 ClassScan，要么 Java 侧抛了异常
    // （异常只写 stdout，javaw 没有控制台，所以只剩这一种表现）。
    return { ok: false, text: i18n.t('notice.injectUnconfirmed', i18n.t('reason.noInitComplete')) };
  }

  /**
   * Turn a failed injection result into one sentence the user can act on.
   *
   * <p>The previous version ended with `i18n.t('err.injectFailed', result.code)`, which is correct
   * only when `code` is a real exit code. It is `null` for three unrelated situations — the injector
   * file is gone, the spawn was blocked, or the process was killed by a signal — and in all three
   * the user was shown the literal string "启动失败（退出码 null）". A null in a user-facing message is
   * never information; it means the branch that produced it did not know what happened.
   *
   * <p>Each case now has its own sentence, and the generic message is only used when a numeric exit
   * code actually exists.
   *
   * 把一次失败的注入结果翻译成一句用户能据此行动的话。
   *
   * <p>上一版以 `i18n.t('err.injectFailed', result.code)` 收尾，只有在 `code` 是真实退出码时才对。
   * 而它在三种互不相干的情况下都是 `null`——注入器文件没了、spawn 被拦截、进程被信号终止——
   * 三种情况都显示成字面的「启动失败（退出码 null）」。面向用户的文案里出现 null 从来不是信息，
   * 只说明产生它的那个分支并不知道发生了什么。
   *
   * <p>现在每种情况都有各自的一句话，通用文案只在真的拿到数字退出码时才用。
   */
  failureHint(result) {
    switch (result.error) {
      case 'injector-missing':
        return i18n.t('err.injectorMissing');
      case 'injector-empty':
        return i18n.t('err.injectorEmpty');
      case 'injector-not-file':
        return i18n.t('err.injectorNotFile');
      case 'injector-blocked':
        return i18n.t('err.injectorBlocked', result.syscallCode || 'UNKNOWN');
      default:
        break;
    }
    if (result.timedOut) {
      return i18n.t('err.injectTimeout');
    }
    if (typeof result.code === 'number' && Number.isFinite(result.code)) {
      return i18n.t('err.injectFailed', result.code);
    }
    // No numeric exit code and no recognised cause. Saying "null" would be worse than saying
    // nothing useful; name the situation instead.
    // 既没有数字退出码，也没有可识别的原因。说 "null" 还不如说清处境。
    if (result.signal) {
      return i18n.t('err.injectKilled', result.signal);
    }
    return i18n.t('err.injectUnknown');
  }

  cancel() {
    if (!this.busy) {
      this.setPhase(PHASE.READY);
    }
  }

  recentLog(lines = 300) {
    return logTail.recent(lines);
  }
}

module.exports = { InjectService };

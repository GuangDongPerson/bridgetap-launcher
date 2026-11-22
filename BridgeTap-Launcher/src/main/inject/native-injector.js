'use strict';

const path = require('node:path');
const fs = require('node:fs');
const { spawn } = require('node:child_process');
const { app } = require('electron');

/**
 * Native launcher adapter layer.
 *
 * It knows only three things: where the launcher exe is, how to invoke it with `--pid`, and how to
 * hand its output over line by line. It knows nothing about the HUD, the state machine, or the UI —
 * those belong to the layers above.
 *
 * 原生启动器适配层。
 *
 * 它只知道三件事：启动器 exe 在哪、怎么用 `--pid` 调它、它的输出按行交出去。
 * 不认识 HUD、不认识状态机、不认识界面——那是上层的事。
 */

const EXE_NAME = 'BridgeTap.exe';

/**
 * Three candidate locations, in priority order:
 *   1. resources/app.asar.unpacked/vendor/launcher/  —— where asarUnpack puts it (current scheme)
 *   2. resources/launcher/                           —— where extraResources puts it (old scheme, kept
 *                                                      for compatibility)
 *   3. <appPath>/vendor/launcher/                    —— running straight from source in development
 *
 * 三个候选位置，按优先级：
 *   1. resources/app.asar.unpacked/vendor/launcher/  —— asarUnpack 的落点（当前方案）
 *   2. resources/launcher/                           —— extraResources 的落点（旧方案，保留兼容）
 *   3. <appPath>/vendor/launcher/                    —— 开发时直接跑源码
 */
function injectorPath() {
  for (const candidate of candidates()) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return candidates()[candidates().length - 1];
}

/**
 * The candidate paths, in priority order. Split out of `injectorPath()` so the diagnostic can report
 * every location it looked at — when an antivirus deletes the file, "which directory is empty" is
 * the only thing that tells the user where to look.
 *
 * 候选路径，按优先级排列。从 `injectorPath()` 里拆出来，是为了让诊断能报告**每一个**查过的位置——
 * 被杀软删掉时，"哪个目录是空的"才是用户唯一能据以行动的信息。
 */
function candidates() {
  const resources = process.resourcesPath || '';
  return [
    path.join(resources, 'app.asar.unpacked', 'vendor', 'launcher', EXE_NAME),
    path.join(resources, 'launcher', EXE_NAME),
    path.join(app.getAppPath(), 'vendor', 'launcher', EXE_NAME),
    // When spawning from the source directory in development (npm start), appPath is the project
    // root; add the working directory as a fallback
    // 开发时从源码目录拉起（npm start）时 appPath 就是项目根，补一个工作目录兜底
    path.join(process.cwd(), 'vendor', 'launcher', EXE_NAME)
  ];
}

/**
 * Whether a *usable* injector exists.
 *
 * <p>Delegates to `diagnose()` rather than `statSync(...).isFile()`, because the latter is true for a
 * 0-byte file — the exact shape antivirus leaves after quarantining in place. Answering "ready" for
 * a file that cannot run is what let the failure surface only after the user clicked launch.
 *
 * 是否存在**可用的**注入器。
 *
 * <p>改为委托 `diagnose()`，而不是 `statSync(...).isFile()`：后者对 0 字节文件同样返回 true——
 * 那恰好是杀软「就地隔离」后留下的形态。把一个跑不起来的文件答成"就绪"，正是失败被推迟到
 * 用户点了启动之后才暴露的原因。
 */
function available() {
  return diagnose().ok;
}

/**
 * Check every candidate before trying to spawn, and classify *why* it is unusable.
 *
 * <p>Returning a bare boolean was the reason a deleted file surfaced as "exit code null": the only
 * signal available was "spawn failed", which carries no information about the cause. The three
 * failure shapes need different advice, so they have to be told apart here — at the last point where
 * the filesystem still knows the difference:
 *
 * <ul>
 *   <li>`missing` — no candidate exists. Deleted or never shipped with the package.</li>
 *   <li>`empty` — the file exists but is 0 bytes. This is what an antivirus leaves behind when it
 *       "quarantines in place": `statSync().isFile()` is still true, so an existence check alone
 *       passes and the spawn then fails with no useful detail.</li>
 *   <li>`not-file` — a directory or a reparse point sitting at the path.</li>
 * </ul>
 *
 * @returns {{ok:boolean, path:string, reason?:'missing'|'empty'|'not-file', size:number, checked:{path:string,state:string}[]}}
 *
 * 尝试 spawn 之前先把所有候选都查一遍，并区分**为什么**不可用。
 *
 * <p>只返回一个布尔值，正是「文件被删」表现为「退出码 null」的原因：当时唯一可用的信号就是
 * "spawn 失败"，它不携带任何原因。三种失败形态需要三条不同的建议，所以必须在这里区分开——
 * 这是文件系统还知道答案的最后一个位置：
 *
 * <ul>
 *   <li>`missing` —— 一个候选都不存在。被删除，或者打包时根本没带上。</li>
 *   <li>`empty` —— 文件在，但 0 字节。这是杀软「就地隔离」留下的形态：
 *       `statSync().isFile()` 仍为 true，所以光查存在性会通过，随后 spawn 失败且毫无细节。</li>
 *   <li>`not-file` —— 那个路径上坐着一个目录或重解析点。</li>
 * </ul>
 */
function diagnose() {
  const checked = [];
  let firstExisting = null;
  for (const candidate of candidates()) {
    let state = 'missing';
    let size = -1;
    try {
      const stat = fs.statSync(candidate);
      size = stat.size;
      if (!stat.isFile()) {
        state = 'not-file';
      } else if (stat.size === 0) {
        state = 'empty';
      } else {
        state = 'ok';
      }
    } catch {
      state = 'missing';
    }
    checked.push({ path: candidate, state });
    if (state === 'ok') {
      return { ok: true, path: candidate, size, checked };
    }
    if (!firstExisting && state !== 'missing') {
      firstExisting = { path: candidate, reason: state, size };
    }
  }
  const fallback = firstExisting || {
    path: checked[checked.length - 1].path,
    reason: 'missing',
    size: -1
  };
  return { ok: false, path: fallback.path, reason: fallback.reason, size: fallback.size, checked };
}

/**
 * Launch into one process.
 *
 * 启动一个进程。
 *
 * @param {number} pid
 * @param {(line:string, stream:'stdout'|'stderr')=>void} onLine
 * @param {{timeoutMs?:number}} [options]
 * @returns {Promise<{ok:boolean, code:number|null, timedOut:boolean, error?:string, syscallCode?:string, signal?:string|null}>}
 */
function inject(pid, onLine, options = {}) {
  const timeoutMs = options.timeoutMs ?? 90_000;

  return new Promise((resolve) => {
    // Pre-flight before spawning. A missing file must never reach `spawn`, because the error it
    // produces ("spawn ... ENOENT") is indistinguishable from a permission problem and carries no
    // exit code — which is exactly how it became "启动失败（退出码 null）".
    // spawn 之前先体检。文件缺失绝不能走到 `spawn`：它产生的错误（"spawn ... ENOENT"）与权限问题
    // 无法区分，且不携带退出码——"启动失败（退出码 null）"就是这么来的。
    const health = diagnose();
    if (!health.ok) {
      const mapped = health.reason === 'empty' ? 'injector-empty'
        : health.reason === 'not-file' ? 'injector-not-file'
          : 'injector-missing';
      resolve({ ok: false, code: null, timedOut: false, error: mapped, detail: health });
      return;
    }

    const exe = health.path;
    const child = spawn(exe, ['--pid', String(pid)], {
      cwd: path.dirname(exe),
      windowsHide: true
    });

    let settled = false;
    const timer = setTimeout(() => {
      if (settled) {
        return;
      }
      settled = true;
      try {
        child.kill();
      } catch {
        // Already exited
        // 已经退出了
      }
      resolve({ ok: false, code: null, timedOut: true });
    }, timeoutMs);

    const pipe = (stream) => {
      let buffer = '';
      stream.setEncoding('utf8');
      stream.on('data', (chunk) => {
        buffer += chunk;
        const lines = buffer.split(/\r?\n/);
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          if (line.trim()) {
            onLine(line.trimEnd(), stream === child.stdout ? 'stdout' : 'stderr');
          }
        }
      });
      stream.on('end', () => {
        if (buffer.trim()) {
          onLine(buffer.trimEnd(), stream === child.stdout ? 'stdout' : 'stderr');
        }
      });
    };

    if (child.stdout) {
      pipe(child.stdout);
    }
    if (child.stderr) {
      pipe(child.stderr);
    }

    child.on('error', (error) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      // Keep `syscallCode` (ENOENT / EACCES / EPERM / UNKNOWN). It is the only thing that separates
      // "the file vanished between the check and the spawn" from "security software blocked the
      // spawn" — both are `error` events with no exit code.
      // 保留 `syscallCode`（ENOENT / EACCES / EPERM / UNKNOWN）。它是唯一能区分「检查之后、spawn
      // 之前文件消失了」和「安全软件拦截了 spawn」的东西——两者都是没有退出码的 `error` 事件。
      resolve({
        ok: false,
        code: null,
        timedOut: false,
        error: 'injector-blocked',
        syscallCode: error.code || 'UNKNOWN',
        message: error.message
      });
    });

    child.on('close', (code, signal) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      // `code` is null when the process was terminated by a signal; reporting that as an exit code
      // would be a fabrication. Carry `signal` so the caller can say what actually happened.
      // 进程被信号终止时 `code` 为 null；把它当成退出码报出去就是编造。带上 `signal`，
      // 让调用方能说清真正发生了什么。
      resolve({ ok: code === 0, code, timedOut: false, signal: signal || null });
    });
  });
}

module.exports = { inject, available, injectorPath, diagnose, candidates, EXE_NAME };

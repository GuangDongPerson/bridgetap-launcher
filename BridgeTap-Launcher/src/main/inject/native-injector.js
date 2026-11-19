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
  const resources = process.resourcesPath || '';
  const candidates = [
    path.join(resources, 'app.asar.unpacked', 'vendor', 'launcher', EXE_NAME),
    path.join(resources, 'launcher', EXE_NAME),
    path.join(app.getAppPath(), 'vendor', 'launcher', EXE_NAME),
    // When spawning from the source directory in development (npm start), appPath is the project
    // root; add the working directory as a fallback
    // 开发时从源码目录拉起（npm start）时 appPath 就是项目根，补一个工作目录兜底
    path.join(process.cwd(), 'vendor', 'launcher', EXE_NAME)
  ];
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return candidates[candidates.length - 1];
}

function available() {
  try {
    return fs.statSync(injectorPath()).isFile();
  } catch {
    return false;
  }
}

/**
 * Launch into one process.
 *
 * 启动一个进程。
 *
 * @param {number} pid
 * @param {(line:string, stream:'stdout'|'stderr')=>void} onLine
 * @param {{timeoutMs?:number}} [options]
 * @returns {Promise<{ok:boolean, code:number|null, timedOut:boolean, error?:string}>}
 */
function inject(pid, onLine, options = {}) {
  const timeoutMs = options.timeoutMs ?? 90_000;
  const exe = injectorPath();

  return new Promise((resolve) => {
    if (!available()) {
      resolve({ ok: false, code: null, timedOut: false, error: 'injector-missing' });
      return;
    }

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
      resolve({ ok: false, code: null, timedOut: false, error: error.message });
    });

    child.on('close', (code) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      resolve({ ok: code === 0, code, timedOut: false });
    });
  });
}

module.exports = { inject, available, injectorPath, EXE_NAME };

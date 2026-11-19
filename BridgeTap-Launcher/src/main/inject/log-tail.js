'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

/**
 * agent.log tail reader.
 *
 * The injector itself only reports "success/failure"; the real work is recorded in agent.log (which
 * classes JVMTI retransformed, whether the hooks hit, whether the HUD loaded). When the UI wants to
 * show the user "which step we're on", reading this file is the cheapest way.
 *
 * agent.log 尾巴读取器。
 *
 * 注入器本身只说"成功/失败"，真正干活的过程写在 agent.log 里（JVMTI 重转换了哪些类、
 * 站点是否命中、HUD 是否加载）。UI 想给用户看"现在到哪一步了"，读这个文件是最省事的方式。
 */

function logDir() {
  const base = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
  return path.join(base, 'BridgeTap');
}

function logPath() {
  return path.join(logDir(), 'agent.log');
}

/**
 * The most recent n lines (empty array when the file doesn't exist).
 * 最近 n 行（文件不存在时返回空数组）。
 */
function recent(n = 200) {
  try {
    const text = fs.readFileSync(logPath(), 'utf8');
    const lines = text.split(/\r?\n/).filter(Boolean);
    return lines.slice(Math.max(0, lines.length - n));
  } catch {
    return [];
  }
}

/**
 * Follow the file growth from the current position and hand each new line out one by one.
 *
 * 从当前位置开始跟着文件增长，把新增行逐条交出去。
 *
 * @param {(line:string)=>void} onLine
 * @param {{durationMs?:number, intervalMs?:number}} [options]
 * @returns {() => void} Stop function · 停止函数
 */
function follow(onLine, options = {}) {
  const durationMs = options.durationMs ?? 30_000;
  const intervalMs = options.intervalMs ?? 700;
  let offset = 0;
  let stopped = false;
  let pending = '';

  try {
    offset = fs.statSync(logPath()).size;
  } catch {
    offset = 0;
  }

  const read = () => {
    if (stopped) {
      return;
    }
    let stat;
    try {
      stat = fs.statSync(logPath());
    } catch {
      return;
    }
    if (stat.size < offset) {
      // The file got truncated (rewritten after a client restart): start over from the beginning
      // 文件被截断（客户端重启后重写），从头开始
      offset = 0;
      pending = '';
    }
    if (stat.size === offset) {
      return;
    }
    try {
      const fd = fs.openSync(logPath(), 'r');
      const length = stat.size - offset;
      const buffer = Buffer.alloc(length);
      fs.readSync(fd, buffer, 0, length, offset);
      fs.closeSync(fd);
      offset = stat.size;
      pending += buffer.toString('utf8');
      const lines = pending.split(/\r?\n/);
      pending = lines.pop() ?? '';
      for (const line of lines) {
        if (line.trim()) {
          onLine(line.trimEnd());
        }
      }
    } catch {
      // The file is being written; read again next round
      // 文件正在被写，下一轮再读
    }
  };

  const timer = setInterval(read, intervalMs);
  const stopTimer = setTimeout(() => {
    stopped = true;
    clearInterval(timer);
  }, durationMs);

  return () => {
    stopped = true;
    clearInterval(timer);
    clearTimeout(stopTimer);
  };
}

module.exports = { recent, follow, logDir, logPath };

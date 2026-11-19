/**
 * Export troubleshooting logs: one copy each of injector / mod / client, bundled into a zip.
 *
 * Every path is "include only if it exists": the user may have only BLC or only LC installed, and a
 * missing piece shouldn't fail the whole export. Packaging uses Windows' built-in Compress-Archive,
 * so no extra dependency is pulled in.
 *
 * 导出排障日志：注入器 / 模组 / 客户端各一份，打成一个 zip。
 *
 * 路径都是"存在才带"：用户可能只装了 BLC 或只装了 LC，缺的那份不该让整个导出失败。
 * 打包用 Windows 自带的 Compress-Archive，不额外引依赖。
 */
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { app, dialog } = require('electron');
const { spawnSync } = require('node:child_process');
const logTail = require('./inject/log-tail');

function appData() {
  return process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming');
}

function localAppData() {
  return process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
}

/**
 * Lunar's game log lives under a per-version profile, not in a fixed `logs/game` folder:
 *   %USERPROFILE%\.lunarclient\profiles\<profile>\logs\latest.log
 * The profile name follows the game version (1.8 for 1.8.9, 1.21 for 1.21.x), and several
 * profiles can coexist, so the directory has to be discovered instead of hardcoded. Newest first:
 * the most recently written one is the session that is running right now.
 *
 * Lunar 的游戏日志按版本放在各自的 profile 下，不在固定的 `logs/game` 里：
 *   %USERPROFILE%\.lunarclient\profiles\<profile>\logs\latest.log
 * profile 名跟游戏版本走（1.8.9 → 1.8，1.21.x → 1.21），而且同时可能装了好几个版本，
 * 所以必须现扫，不能写死目录。按修改时间倒序：最新的那份就是当前这一局。
 */
function lunarProfileLogs(home) {
  const root = path.join(home, '.lunarclient', 'profiles');
  let entries;
  try {
    entries = fs.readdirSync(root, { withFileTypes: true });
  } catch {
    return [];
  }
  const found = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) {
      continue;
    }
    const file = path.join(root, entry.name, 'logs', 'latest.log');
    try {
      const stat = fs.statSync(file);
      if (stat.isFile()) {
        found.push({ file, mtime: stat.mtimeMs });
      }
    } catch {
      // This profile has never been launched
      // 这个 profile 没跑过
    }
  }
  return found.sort((a, b) => b.mtime - a.mtime).map((item) => item.file);
}

/**
 * Candidate paths for the three log kinds; the first existing one per kind is used.
 * 三类日志的候选路径；同一类里取第一个存在的。
 */
function candidates() {
  const roaming = appData();
  const home = os.homedir();
  return [
    {
      name: 'launcher-agent.log',
      label: 'Launcher log / 启动器日志 (agent.log)',
      paths: [
        path.join(logTail.logDir(), 'agent.log'),
        // When the client clears LOCALAPPDATA, Log falls back to java.io.tmpdir and lands here.
        // Without this candidate, "the agent did run but wrote to temp" looks like "the agent never
        // executed".
        // 客户端把 LOCALAPPDATA 清掉时，Log 会回退到 java.io.tmpdir，落点就是这里。
        // 少了这个候选，"agent 其实跑了只是写去 temp"会被误判成"agent 没执行"。
        path.join(os.tmpdir(), 'BridgeTap', 'agent.log')
      ]
    },
    {
      // payload.dll logs each step in order: jvm.dll → JavaVM → JVMTI → attach jar → Class.forName,
      // and every failure is a `FATAL: ...` line. The native launcher reporting "success" only means
      // the dll was loaded by LoadLibraryW; whether it actually attached to the JVM can only be
      // answered by this file.
      // payload.dll 在里面按步骤记：jvm.dll → JavaVM → JVMTI → 挂 jar → Class.forName，
      // 失败一律 `FATAL: ...`。原生启动器报"成功"只代表 dll 被 LoadLibraryW 载入，
      // 到底有没有真的接上 JVM，只有这份文件能回答。
      name: 'launcher-native.log',
      label: 'Native launcher log / 原生启动日志 (inject-native.log)',
      paths: [path.join(logTail.logDir(), 'inject-native.log')]
    },
    {
      name: 'mod-bridgetap.log',
      label: 'Mod log / 模组日志 (bridgetap.log)',
      paths: [path.join(roaming, '.minecraft', 'logs', 'bridgetap.log')]
    },
    {
      /**
       * Badlion does not write to `<gameDir>/logs/latest.log`: the launcher passes
       * `-DlogLocation=...\.minecraft\logs\blclient\minecraft`, so the real file sits one level
       * deeper. Keep the vanilla path as the fallback, because a plain Minecraft install does use
       * it and the two never coexist on the same gameDir.
       *
       * Badlion 不写 `<gameDir>/logs/latest.log`：启动器会传
       * `-DlogLocation=...\.minecraft\logs\blclient\minecraft`，真实文件在更深一层。
       * 原版客户端走的还是老路径，留作兜底——同一个 gameDir 下两者不会同时存在。
       */
      name: 'client-minecraft-latest.log',
      label: 'Client log / 客户端日志 (Badlion / vanilla latest.log)',
      paths: [
        path.join(roaming, '.minecraft', 'logs', 'blclient', 'minecraft', 'latest.log'),
        path.join(roaming, '.minecraft', 'logs', 'latest.log')
      ]
    },
    {
      name: 'client-lunar-latest.log',
      label: 'Client log / 客户端日志 (Lunar latest.log)',
      paths: [
        ...lunarProfileLogs(home),
        // Legacy layouts, kept for older Lunar versions
        // 旧版 Lunar 的布局，留作兼容
        path.join(home, '.lunarclient', 'logs', 'game', 'latest.log'),
        path.join(roaming, '.lunarclient', 'logs', 'game', 'latest.log'),
        path.join(roaming, '.lunarclient', 'logs', 'latest.log')
      ]
    },
    {
      name: 'bootstrap.ini',
      label: 'Launch bootstrap file / 启动引导文件 (bootstrap.ini)',
      paths: [path.join(logTail.logDir(), 'inject', 'bootstrap.ini')]
    }
  ];
}

function firstExisting(paths) {
  for (const file of paths) {
    try {
      if (fs.statSync(file).isFile()) {
        return file;
      }
    } catch {
      // Try the next candidate
      // 继续试下一个候选
    }
  }
  return null;
}

function stamp() {
  const now = new Date();
  const pad = (value) => String(value).padStart(2, '0');
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
    + `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

function systemInfo(found) {
  const lines = [
    'BridgeTap log bundle',
    '====================',
    '',
    'Exported at : ' + new Date().toISOString(),
    'BridgeTap   : ' + app.getVersion(),
    'Electron    : ' + process.versions.electron,
    'Chrome      : ' + process.versions.chrome,
    'Node        : ' + process.versions.node,
    'Windows     : ' + os.release() + ' (' + process.arch + ')',
    'User        : ' + os.userInfo().username,
    'JAVA_HOME   : ' + (process.env.JAVA_HOME || '(not set)'),
    'PORTABLE    : ' + (process.env.PORTABLE_EXECUTABLE_FILE || '(not portable)'),
    '',
    'Log sources:'
  ];
  for (const item of found) {
    lines.push(`  [OK]   ${item.label}`);
    lines.push(`         ${item.source} (${item.size} bytes)`);
    lines.push(`         modified: ${item.modified}`);
  }
  for (const item of found.missing || []) {
    lines.push(`  [MISS] ${item.label}`);
    lines.push('         tried: ' + item.paths.join(' | '));
  }
  lines.push('');
  return lines.join('\r\n');
}

function readme(found) {
  const lines = [
    'BridgeTap 日志包 / Log bundle',
    '=============================',
    '',
    '导出时间 / Exported: ' + new Date().toISOString(),
    'BridgeTap 版本 / Version: ' + app.getVersion(),
    '',
    '包含的文件 / Contents:'
  ];
  for (const item of found) {
    lines.push(`  ${item.name}`);
    lines.push(`    来源 / from: ${item.source}`);
    lines.push(`    大小 / size: ${item.size} bytes`);
    // The timestamp tells a live log from one left over by a previous session
    // 时间戳用来区分"这次跑的"和"上次留下的"
    lines.push(`    修改时间 / modified: ${item.modified}`);
  }
  if (!found.length) {
    lines.push('  (empty)');
  }
  lines.push('');
  lines.push('缺失的文件 / Missing:');
  if (!found.missing || !found.missing.length) {
    lines.push('  (none)');
  } else {
    for (const item of found.missing) {
      lines.push(`  ${item.label}: not found`);
    }
  }
  lines.push('');
  lines.push('说明：latest.log 是客户端"最近一次启动"的日志；bridgetap.log 每次启动时截断重写。');
  lines.push('Notes: latest.log covers the most recent client launch; bridgetap.log is truncated per launch.');
  lines.push('');
  return lines.join('\r\n');
}

function psQuote(value) {
  return "'" + String(value).replace(/'/g, "''") + "'";
}

/**
 * @param {import('electron').BrowserWindow} [browserWindow] Parent window, keeps the dialog centered ·
 *   父窗口，弹窗居中用
 * @returns {Promise<{ok:true, file:string, count:number}|{ok:false, canceled?:boolean, error?:string}>}
 */
async function exportLogs(browserWindow) {
  const picked = await dialog.showOpenDialog(browserWindow, {
    title: '选择日志导出目录',
    properties: ['openDirectory', 'createDirectory']
  });
  if (picked.canceled || !picked.filePaths.length) {
    return { ok: false, canceled: true };
  }
  const targetDir = picked.filePaths[0];

  const found = [];
  const missing = [];
  for (const item of candidates()) {
    const file = firstExisting(item.paths);
    if (file) {
      const stat = fs.statSync(file);
      found.push({
        ...item,
        source: file,
        size: stat.size,
        modified: stat.mtime.toISOString()
      });
    } else {
      missing.push(item);
    }
  }
  found.missing = missing;

  if (!found.length) {
    return { ok: false, error: 'no logs found' };
  }

  const work = path.join(os.tmpdir(), 'bridgetap-export-' + Date.now());
  const zipName = 'BridgeTap-Logs-' + stamp() + '.zip';
  const zipPath = path.join(targetDir, zipName);

  try {
    fs.mkdirSync(work, { recursive: true });
    for (const item of found) {
      fs.copyFileSync(item.source, path.join(work, item.name));
    }
    fs.writeFileSync(path.join(work, 'README.txt'), '\ufeff' + readme(found), 'utf8');
    fs.writeFileSync(path.join(work, 'system-info.txt'), '\ufeff' + systemInfo(found), 'utf8');

    const script = 'Compress-Archive -Path ' + psQuote(path.join(work, '*'))
      + ' -DestinationPath ' + psQuote(zipPath) + ' -Force';
    const result = spawnSync('powershell.exe',
      ['-NoProfile', '-NonInteractive', '-Command', script],
      { windowsHide: true, encoding: 'utf8', timeout: 120000 });

    if (result.status !== 0 || !fs.existsSync(zipPath)) {
      const detail = (result.stderr || '').trim().split(/\r?\n/).pop() || 'Compress-Archive failed';
      try {
        if (fs.existsSync(zipPath)) {
          fs.unlinkSync(zipPath);
        }
      } catch {
        // A half-written file that can't be deleted: still report the error first
        // 半截文件删不掉也先报错
      }
      return { ok: false, error: detail };
    }

    return { ok: true, file: zipPath, count: found.length };
  } catch (error) {
    return { ok: false, error: error.message };
  } finally {
    try {
      fs.rmSync(work, { recursive: true, force: true });
    } catch {
      // A leftover temp directory doesn't affect the export result; the system cleans it up later
      // 临时目录清不掉不影响导出结果，下次系统会收拾
    }
  }
}

module.exports = { exportLogs, candidates };

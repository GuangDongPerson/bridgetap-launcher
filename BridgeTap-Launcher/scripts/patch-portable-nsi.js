/**
 * Patch electron-builder's portable shell to cache by version and skip extraction when it already
 * happened.
 *
 * The stock template does `RMDir /r $INSTDIR` and unpacks everything on every launch (1~3 seconds),
 * and the portable options only let you change `unpackDirName` (still pinned to %TEMP%), so reuse is
 * impossible. So the Section in the template is rewritten outright:
 *
 *   %LOCALAPPDATA%\BridgeTap\app\<version>\   extract once on first launch
 *   same version launched again              exe detected → no extraction, start directly
 *   version changed                          extract into a new directory, prune the old one
 *
 * %LOCALAPPDATA% is used instead of %APPDATA%: local caches shouldn't be synced by domain roaming.
 *
 * Idempotent: skipped when the [BT_VERSIONED_CACHE] marker is already present. Reinstalling
 * node_modules restores the template, so both postinstall and dist run this script first.
 *
 * 给 electron-builder 的 portable 外壳打补丁：按版本缓存，已解压过就跳过。
 *
 * 原模板每次启动都 `RMDir /r $INSTDIR` 再整包解压（1~3 秒），而 portable 的配置项
 * 只能改 `unpackDirName`（仍钉死在 %TEMP%），做不到复用。所以直接重写模板里的 Section：
 *
 *   %LOCALAPPDATA%\BridgeTap\app\<version>\   首次启动解压一次
 *   同版本再次启动                            检测到 exe 存在 → 零解压，直接拉起
 *   版本变了                                  新目录解压，旧的顺手删掉
 *
 * 用 %LOCALAPPDATA% 而不是 %APPDATA%：本地缓存不该被域漫游同步。
 *
 * 幂等：已有 [BT_VERSIONED_CACHE] 标记就跳过。
 * node_modules 重装会还原模板，所以 postinstall 与 dist 都会先跑本脚本。
 */
const fs = require('node:fs');
const path = require('node:path');

const MARK = '[BT_VERSIONED_CACHE]';
/**
 * Marker for the environment-scrubbing patch, tracked separately so machines that already have the
 * versioned-cache patch still receive this one. `dist` is run many times per session, so the check
 * has to be about "is this specific edit present", not "was this file touched at all".
 * 环境变量清理补丁的独立标记。已经打过"按版本缓存"补丁的机器也要能收到这一条。
 * 一个会话里 `dist` 会跑很多次，所以判据必须是"这一处改动在不在"，而不是"这个文件动过没有"。
 */
const ENV_MARK = '[BT_CLEAR_ELECTRON_ENV]';
const target = path.join(
  __dirname, '..', 'node_modules', 'app-builder-lib', 'templates', 'nsis', 'portable.nsi'
);

if (!fs.existsSync(target)) {
  console.log('[patch-portable] template not found, skipped');
  process.exit(0);
}

let source = fs.readFileSync(target, 'utf8');

// The environment scrub, expressed once and used by both paths below.
//
// ELECTRON_RUN_AS_NODE turns the Electron binary into a bare Node interpreter: no window, no
// error, no log, exit code 0. It is indistinguishable from "double-click does nothing", and it
// leaks in from IDEs, build scripts and anything that runs Electron headlessly. The shell always
// runs before Electron, so clearing it here makes the launcher immune to whatever the parent set.
// An empty string deletes the variable.
//
// 环境变量清理，只定义一次，下面两条路径共用。
// ELECTRON_RUN_AS_NODE 会让 Electron 退化成裸 Node 解释器：无窗口、无报错、无日志、退出码 0。
// 与"双击什么也没发生"无法区分，而且它来自 IDE、构建脚本和任何无头运行 Electron 的工具。
// 壳一定先于 Electron 运行，所以在这里清掉，父进程设了什么都影响不到启动器。
// 传空字符串即删除该变量。
const envScrub = [
  '',
  '  ; [BT_CLEAR_ELECTRON_ENV] Neutralize inherited Electron control variables before starting the app.',
  '  ; ELECTRON_RUN_AS_NODE would make the Electron binary run as a bare Node interpreter instead of',
  '  ; an app: no window, no error, no log, exit code 0 - indistinguishable from "double-click does',
  '  ; nothing". It leaks in from IDEs, build scripts and any tool that runs Electron headlessly.',
  '  ; The shell always runs before Electron, so clearing it here makes the launcher immune no matter',
  '  ; what the parent process had set.',
  '  ; [BT_CLEAR_ELECTRON_ENV] 启动前清掉继承来的 Electron 控制变量。',
  '  ; ELECTRON_RUN_AS_NODE 会让 Electron 退化成裸 Node 解释器：无窗口、无报错、无日志、退出码 0，',
  '  ; 与"双击什么也没发生"无法区分。它来自 IDE、构建脚本、以及任何无头运行 Electron 的工具。',
  '  ; 在壳里清掉（壳一定先于 Electron 运行），无论父进程设了什么都不会影响启动器。',
  '  ;',
  '  ; Kernel32::SetEnvironmentVariable takes (LPCTSTR name, LPCTSTR value), and a NULL value deletes',
  '  ; the variable. Pass NULL explicitly as i0 rather than an empty string: NSIS\'s System plugin',
  '  ; does not parse "" inside a quoted call string, and a malformed call here corrupts the',
  '  ; registers the ExecWait below depends on - the shell then exits 0 without starting the app,',
  '  ; which is the very symptom this patch exists to remove. Measured: with an empty-string',
  '  ; argument the packaged launcher produced 0 processes and no log; with i0 it starts normally.',
  '  ; Kernel32::SetEnvironmentVariable 接收 (名称, 值)，值为 NULL 时删除该变量。',
  '  ; 必须显式传 i0 表示 NULL，不能用空字符串：System 插件解析不了引号调用里的 ""，',
  '  ; 而一条畸形的调用会破坏下面 ExecWait 依赖的寄存器——壳就会以 0 退出且不启动应用，',
  '  ; 正是这个补丁要消除的症状。实测：空字符串参数时打包版产出 0 进程、无日志；改 i0 后正常启动。',
  '  System::Call \'Kernel32::SetEnvironmentVariable(t, i)i ("ELECTRON_RUN_AS_NODE", 0).r0\'',
  '  System::Call \'Kernel32::SetEnvironmentVariable(t, i)i ("ELECTRON_NO_ATTACH_CONSOLE", 0).r0\'',
].join('\r\n');

/**
 * Insert the scrub immediately before the line that starts the app.
 *
 * <p>Appending at `ExecWait` rather than rewriting the whole Section keeps this edit independent of
 * the cache patch: templates already carrying the versioned-cache rewrite get the scrub added in
 * place, with no need to restore and re-patch the original.
 *
 * <p>Idempotent through the ENV_MARK check above; returns null when the anchor is missing so the
 * caller can fall back to the full rewrite.
 *
 * 在拉起应用那一行之前插入清理语句。
 *
 * <p>挂在 `ExecWait` 上而不是重写整个 Section，是为了让这处改动与缓存补丁互不依赖：
 * 已经打过按版本缓存的模板直接就地补上，不需要先还原再重打。
 *
 * <p>幂等由上面对 ENV_MARK 的检查保证；找不到锚点时返回 null，调用方退回整段重写。
 */
function insertEnvScrub(text) {
  // Matching `ExecWait "<installdir>\<exe>" <args>` — the path separator before the exe name is a
  // single backslash in the template, the same one the rest of the generated section uses.
  // 匹配 `ExecWait "<安装目录>\<exe>" <参数>`——模板里 exe 名前面是单个反斜杠，
  // 与生成段落其他地方的写法一致。
  const anchor = /^([ \t]*ExecWait\s+"\$INSTDIR\\\$\{APP_EXECUTABLE_FILENAME\}[^\r\n]*)/m;
  if (!anchor.test(text)) {
    return null;
  }
  return text.replace(anchor, (whole) => envScrub + '\r\n\r\n' + whole);
}

if (source.includes(MARK) && source.includes(ENV_MARK)) {
  // An earlier revision of this patch emitted `("ELECTRON_RUN_AS_NODE", "")`, which the System
  // plugin cannot parse: the malformed call corrupted the registers ExecWait depends on, so the
  // shell exited 0 without ever starting the app — the exact symptom the patch was meant to fix.
  // Repair that in place instead of trusting the marker, because the marker was present then too.
  // 早期版本的补丁生成的是 `("ELECTRON_RUN_AS_NODE", "")`，System 插件解析不了：这条畸形调用
  // 破坏了 ExecWait 依赖的寄存器，壳于是以 0 退出、根本不启动应用——正是补丁要修的那个症状。
  // 这里就地修正，而不是只信标记，因为当年那个坏版本也带着标记。
  if (source.includes('("ELECTRON_RUN_AS_NODE", "")')) {
    source = source
      .replace(/SetEnvironmentVariable\(t, t\)i \("ELECTRON_RUN_AS_NODE", ""\)/g,
        'SetEnvironmentVariable(t, i)i ("ELECTRON_RUN_AS_NODE", 0)')
      .replace(/SetEnvironmentVariable\(t, t\)i \("ELECTRON_NO_ATTACH_CONSOLE", ""\)/g,
        'SetEnvironmentVariable(t, i)i ("ELECTRON_NO_ATTACH_CONSOLE", 0)');
    fs.writeFileSync(target, source, 'utf8');
    console.log('[patch-portable] repaired the empty-string form to the i0 (NULL) form');
    process.exit(0);
  }
  console.log('[patch-portable] already patched (cache + env scrub)');
  process.exit(0);
}

// Already carries the versioned-cache patch: add the scrub in place, no restore needed.
// 已带"按版本缓存"补丁：就地补上环境变量清理，不需要还原模板。
if (source.includes(MARK) && !source.includes(ENV_MARK)) {
  const patched = insertEnvScrub(source);
  if (patched) {
    fs.writeFileSync(target, patched, 'utf8');
    console.log('[patch-portable] added env scrub to the existing patched template');
    process.exit(0);
  }
  console.error('[patch-portable] could not find the ExecWait anchor; restoring the template instead');
  console.error('  Run:  rm -rf node_modules/app-builder-lib && npm install');
  process.exit(1);
}

const section = [
  'Section',
  '  !ifdef SPLASH_IMAGE',
  '    HideWindow',
  '  !endif',
  '',
  '  ; [BT_VERSIONED_CACHE] Extract each version into its own directory; if it is already there, start directly without extracting again',
  '  ; [BT_VERSIONED_CACHE] 每个版本解压到自己的目录；已存在就直接启动，不再解压',
  '  StrCpy $INSTDIR "$LOCALAPPDATA\\BridgeTap\\app\\${VERSION}"',
  '',
  '  ; Prune old version directories (the current one is kept); if an old build is still running it cannot be deleted, ignore that',
  '  ; 清掉旧版本目录（当前版本保留）；旧版还在跑时删不掉，忽略即可',
  '  Push $R1',
  '  FindFirst $R1 $R2 "$LOCALAPPDATA\\BridgeTap\\app\\*.*"',
  '  bt_prune_loop:',
  '    StrCmp $R2 "" bt_prune_done',
  '    StrCmp $R2 "." bt_prune_next',
  '    StrCmp $R2 ".." bt_prune_next',
  '    StrCmp $R2 "${VERSION}" bt_prune_next',
  '    RMDir /r "$LOCALAPPDATA\\BridgeTap\\app\\$R2"',
  '  bt_prune_next:',
  '    FindNext $R1 $R2',
  '    Goto bt_prune_loop',
  '  bt_prune_done:',
  '  FindClose $R1',
  '  Pop $R1',
  '',
  '  ${IfNot} ${FileExists} "$INSTDIR\\${APP_EXECUTABLE_FILENAME}"',
  '  SetOutPath $INSTDIR',
  '',
  '  !ifdef APP_DIR_64',
  '    !ifdef APP_DIR_ARM64',
  '      !ifdef APP_DIR_32',
  '        ${if} ${IsNativeARM64}',
  '          File /r "${APP_DIR_ARM64}\\*.*"',
  '        ${elseif} ${RunningX64}',
  '          File /r "${APP_DIR_64}\\*.*"',
  '        ${else}',
  '          File /r "${APP_DIR_32}\\*.*"',
  '        ${endIf}',
  '      !else',
  '        !ifdef APP_DIR_ARM64',
  '          File /r "${APP_DIR_ARM64}\\*.*"',
  '        !else',
  '          File /r "${APP_DIR_64}\\*.*"',
  '        !endif',
  '      !endif',
  '    !else',
  '      !ifdef APP_DIR_32',
  '        ${if} ${RunningX64}',
  '          File /r "${APP_DIR_64}\\*.*"',
  '        ${else}',
  '          File /r "${APP_DIR_32}\\*.*"',
  '        ${endIf}',
  '      !else',
  '        File /r "${APP_DIR_64}\\*.*"',
  '      !endif',
  '    !endif',
  '  !else',
  '    !ifdef APP_DIR_32',
  '      File /r "${APP_DIR_32}\\*.*"',
  '    !else',
  '      !insertmacro extractEmbeddedAppPackage',
  '    !endif',
  '  !endif',
  '  ${EndIf}',
  '',
  '  System::Call \'Kernel32::SetEnvironmentVariable(t, t)i ("PORTABLE_EXECUTABLE_DIR", "$EXEDIR").r0\'',
  '  System::Call \'Kernel32::SetEnvironmentVariable(t, t)i ("PORTABLE_EXECUTABLE_FILE", "$EXEPATH").r0\'',
  '  System::Call \'Kernel32::SetEnvironmentVariable(t, t)i ("PORTABLE_EXECUTABLE_APP_FILENAME", "${APP_FILENAME}").r0\'',
  '  ${StdUtils.GetAllParameters} $R0 0',
  // Reuses the same block as insertEnvScrub, so both patch paths stay in step.
  // 与 insertEnvScrub 共用同一段，两条补丁路径不会走偏。
  envScrub,
  '',
  '  !ifdef SPLASH_IMAGE',
  '    BgImage::Destroy',
  '  !endif',
  '',
  '  ExecWait "$INSTDIR\\${APP_EXECUTABLE_FILENAME} $R0" $0',
  '  SetErrorLevel $0',
  '',
  '  SetOutPath $EXEDIR',
  '  ; Do not delete $INSTDIR: the same version reuses it on the next launch',
  '  ; 不删 $INSTDIR：同版本下次启动直接复用',
  'SectionEnd'
].join('\r\n');

const pattern = /Section\r?\n[\s\S]*?SectionEnd/;
const matches = source.match(pattern);
if (!matches || matches.length !== 1) {
  console.error('[patch-portable] Section block not found or ambiguous, aborted');
  process.exit(1);
}

source = source.replace(pattern, section);
fs.writeFileSync(target, source, 'utf8');
console.log('[patch-portable] ok -> %LOCALAPPDATA%\\BridgeTap\\app\\<version>');

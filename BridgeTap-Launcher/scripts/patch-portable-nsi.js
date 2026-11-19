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
const target = path.join(
  __dirname, '..', 'node_modules', 'app-builder-lib', 'templates', 'nsis', 'portable.nsi'
);

if (!fs.existsSync(target)) {
  console.log('[patch-portable] template not found, skipped');
  process.exit(0);
}

let source = fs.readFileSync(target, 'utf8');
if (source.includes(MARK)) {
  console.log('[patch-portable] already patched (cache: %LOCALAPPDATA%\\BridgeTap\\app\\<version>)');
  process.exit(0);
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

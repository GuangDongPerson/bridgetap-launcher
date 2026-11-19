#!/usr/bin/env bash
# Builds the single-file injector: dist/BridgeTap.exe
#
# Steps: gradle jars -> stage agent.jar / hud.jar -> compile payload.dll -> compile resources ->
# link one exe with all three embedded.
#
# The MSVC/SDK paths are probed by directory name rather than through vcvars64.bat, for the same
# reason native/build.sh does it: vcvars shells out to reg.exe, which is blocked on this machine.
#
# Usage: native/pack.sh [--skip-java]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
jdk="${BT_JDK:-}"
skip_java=0
[ "${1:-}" = "--skip-java" ] && skip_java=1

if [ -z "$jdk" ]; then
  for candidate in "$root"/../.tools/jdk-*; do
    [ -d "$candidate" ] && jdk="$candidate"
  done
fi
if [ -z "$jdk" ] || [ ! -f "$jdk/include/jni.h" ]; then
  echo "ERROR: no JDK with jni.h found; set BT_JDK=/path/to/jdk" >&2
  exit 64
fi

vct_root="/c/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC"
[ -d "$vct_root" ] || vct_root="/c/Program Files/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC"
[ -d "$vct_root" ] || { echo "ERROR: MSVC toolset not found" >&2; exit 65; }
msvc="$(ls -1 "$vct_root" | sort -V | tail -1)"

sdk_root="/c/Program Files (x86)/Windows Kits/10"
[ -d "$sdk_root" ] || sdk_root="/c/Program Files/Windows Kits/10"
[ -d "$sdk_root" ] || { echo "ERROR: Windows SDK not found" >&2; exit 65; }
sdkv="$(ls -1 "$sdk_root/Include" | sort -V | tail -1)"

export MSYS2_ARG_CONV_EXCL="*"
export INCLUDE="C:\\Program Files (x86)\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\MSVC\\$msvc\\include;C:\\Program Files (x86)\\Windows Kits\\10\\Include\\$sdkv\\ucrt;C:\\Program Files (x86)\\Windows Kits\\10\\Include\\$sdkv\\um;C:\\Program Files (x86)\\Windows Kits\\10\\Include\\$sdkv\\shared"
export LIB="C:\\Program Files (x86)\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\MSVC\\$msvc\\lib\\x64;C:\\Program Files (x86)\\Windows Kits\\10\\Lib\\$sdkv\\ucrt\\x64;C:\\Program Files (x86)\\Windows Kits\\10\\Lib\\$sdkv\\um\\x64"
export PATH="$vct_root/$msvc/bin/Hostx64/x64:$sdk_root/bin/$sdkv/x64:$PATH"

# rc.exe ships with the SDK, not with the MSVC toolset, and its folder is not on PATH by default.
for candidate in "$sdk_root/bin/$sdkv/x64/rc.exe" "$sdk_root/bin/$sdkv/x86/rc.exe"; do
  if [ -f "$candidate" ]; then
    rc_tool="$candidate"
    break
  fi
done
[ -n "${rc_tool:-}" ] || { echo "ERROR: rc.exe not found in the Windows SDK" >&2; exit 65; }

jdkw="$(cygpath -w "$jdk")"

# ---- 1. java artifacts -------------------------------------------------------
if [ "$skip_java" -eq 0 ]; then
  echo "[pack] building jars"
  ( cd "$root" && JAVA_HOME="$jdk" ./gradlew :agent:jar :hud:jar --console=plain -q )
fi

agent_src="$(ls -1t "$root"/agent/build/libs/bridgetap-agent-*.jar 2>/dev/null | head -1)"
hud_src="$(ls -1t "$root"/hud/build/libs/bridgetap-hud-*.jar 2>/dev/null | head -1)"
[ -n "$agent_src" ] || { echo "ERROR: agent jar missing - run ./gradlew :agent:jar" >&2; exit 66; }
[ -n "$hud_src" ] || { echo "ERROR: hud jar missing - run ./gradlew :hud:jar" >&2; exit 66; }

cp -f "$agent_src" "$here/agent.jar"
cp -f "$hud_src" "$here/hud.jar"
echo "[pack] agent.jar $(stat -c%s "$here/agent.jar") bytes"
echo "[pack] hud.jar   $(stat -c%s "$here/hud.jar") bytes"

# ---- 2. payload.dll ---------------------------------------------------------
if [ ! -f "$here/payload.dll" ] || [ "$here/payload.c" -nt "$here/payload.dll" ]; then
  echo "[pack] building payload.dll"
  mkdir -p "$here/build"
  cl.exe /nologo /LD /MT /O2 /W3 /D_CRT_SECURE_NO_WARNINGS \
    "/I$jdkw\\include" "/I$jdkw\\include\\win32" \
    "/Fo$(cygpath -w "$here/build")\\" "/Fd$(cygpath -w "$here/build")\\" \
    "$(cygpath -w "$here/payload.c")" \
    "/Fe$(cygpath -w "$here/payload.dll")" /link psapi.lib user32.lib >/dev/null
  rm -f "$here"/payload.lib "$here"/payload.exp
fi
echo "[pack] payload.dll $(stat -c%s "$here/payload.dll") bytes"

# ---- 3. resources -----------------------------------------------------------
echo "[pack] compiling resources"
"$rc_tool" /nologo /fo "$(cygpath -w "$here/build/BridgeTap.res")" "$(cygpath -w "$here/resource.rc")"
# ---- 4. link ----------------------------------------------------------------
echo "[pack] linking BridgeTap.exe"
cl.exe /nologo /MT /O2 /W3 /D_CRT_SECURE_NO_WARNINGS /DUNICODE /D_UNICODE \
  "/Fo$(cygpath -w "$here/build")\\" "/Fd$(cygpath -w "$here/build")\\" \
  "$(cygpath -w "$here/BridgeTap.c")" "$(cygpath -w "$here/build/BridgeTap.res")" \
  "/Fe$(cygpath -w "$here/build/BridgeTap.exe")" \
  /link user32.lib shell32.lib comctl32.lib advapi32.lib

mkdir -p "$root/dist"
cp -f "$here/build/BridgeTap.exe" "$root/dist/BridgeTap.exe"

echo "[pack] done:"
ls -la "$root/dist/BridgeTap.exe"

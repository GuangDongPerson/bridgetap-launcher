#!/usr/bin/env bash
# Compiles the native half of BridgeTap with MSVC.
#
# vcvars64.bat is deliberately NOT used: it shells out to reg.exe to read the toolchain paths, and
# reg.exe is on this machine's program blacklist. The MSVC/SDK include+lib layout is deterministic,
# so the environment is set by hand.
#
# Usage: native/build.sh [output-dir]
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-$here/../dist/native}"
obj="$here/build"
jdk="${BT_JDK:-}"

if [ -z "$jdk" ]; then
  for candidate in "$here"/../../.tools/jdk-*; do
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

w() { printf 'C:\\Program Files (x86)\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\MSVC\\%s' "$msvc"; }

export MSYS2_ARG_CONV_EXCL="*"
export INCLUDE="$(w)\\include;C:\\Program Files (x86)\\Windows Kits\\10\\Include\\$sdkv\\ucrt;C:\\Program Files (x86)\\Windows Kits\\10\\Include\\$sdkv\\um;C:\\Program Files (x86)\\Windows Kits\\10\\Include\\$sdkv\\shared"
export LIB="$(w)\\lib\\x64;C:\\Program Files (x86)\\Windows Kits\\10\\Lib\\$sdkv\\ucrt\\x64;C:\\Program Files (x86)\\Windows Kits\\10\\Lib\\$sdkv\\um\\x64"
export PATH="$vct_root/$msvc/bin/Hostx64/x64:$PATH"

jdkw="$(cygpath -w "$jdk")"
outw="$(cygpath -w "$out")"
objw="$(cygpath -w "$obj")"
# cl.exe sees a bare MSYS path as a slash-option; every path handed to it must be a Windows path.
payloadw="$(cygpath -w "$here/payload.c")"
injectw="$(cygpath -w "$here/btinject.c")"
mkdir -p "$out" "$obj"

echo "[native] msvc=$msvc sdk=$sdkv"
echo "[native] jdk=$jdkw"

# /MT: the payload must not depend on a VC++ redistributable being present inside the client's JRE.
cl.exe /nologo /LD /MT /O2 /W3 /D_CRT_SECURE_NO_WARNINGS \
  "/I$jdkw\\include" "/I$jdkw\\include\\win32" \
  "/Fo$objw\\" "/Fd$objw\\" "$payloadw" \
  "/Fe$outw\\bridgetap-payload.dll" /link psapi.lib user32.lib

cl.exe /nologo /MT /O2 /W3 /D_CRT_SECURE_NO_WARNINGS \
  "/Fo$objw\\" "/Fd$objw\\" "$injectw" \
  "/Fe$outw\\btinject.exe"

rm -f "$out"/*.lib "$out"/*.exp "$out"/*.pdb "$obj"/*.obj

echo "[native] built:"
ls -la "$out" | grep -E "payload|btinject" || true

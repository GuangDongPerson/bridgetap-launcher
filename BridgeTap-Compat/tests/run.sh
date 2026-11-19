#!/usr/bin/env bash
# Offline acceptance: exercise core's detection logic directly, without starting the game.
# Usage: bash tests/run.sh   (run ./gradlew :core:compileJava first)
# 离线验收：不开游戏，直接对 core 的检测逻辑打表。
# 用法：bash tests/run.sh   （先跑 ./gradlew :core:compileJava）
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
jdk="${BT_JDK:-}"
if [ -z "$jdk" ]; then
  for candidate in "$root"/../.tools/jdk-*; do
    [ -d "$candidate" ] && jdk="$candidate"
  done
fi
[ -n "$jdk" ] && [ -x "$jdk/bin/javac" ] || { echo "ERROR: JDK not found, set BT_JDK / 找不到 JDK，设置 BT_JDK" >&2; exit 64; }

classes="$root/core/build/classes/java/main"
[ -d "$classes" ] || { echo "ERROR: run ./gradlew :core:compileJava first / 先跑 ./gradlew :core:compileJava" >&2; exit 65; }
# The language packs live in resources (Lang reads them with Class.getResourceAsStream), so the
# compiled classes and the resources are two separate directories.
# 语言包在 resources 里（Lang 用 Class.getResourceAsStream 读），编译产物和资源是两个目录
res="$root/core/build/resources/main"
[ -d "$res" ] || { echo "ERROR: run ./gradlew :core:processResources first / 先跑 ./gradlew :core:processResources" >&2; exit 65; }

out="$root/build/sync-test"
mkdir -p "$out"

# javac/java are Windows programs: hand the paths to cygpath instead of letting MSYS convert them.
# javac/java 都是 Windows 程序：路径交给 cygpath，别让 MSYS 自己转
w_classes="$(cygpath -w "$classes");$(cygpath -w "$res")"
w_out="$(cygpath -w "$out")"
w_src="$(cygpath -w "$here/BtSyncTest.java")"

"$jdk/bin/javac" -encoding UTF-8 -cp "$w_classes" -d "$w_out" "$w_src"

# Run inside a temp directory: Config.save() writes the relative path
# config/bridgetap.properties, and running straight from the repo root overwrites the sample config
# (that happened once). The classpath is absolute, so the CWD can be anything.
# 在临时目录里跑：Config.save() 写的是相对路径 config/bridgetap.properties，
# 直接在仓库根跑会把样例配置覆盖掉（踩过一次）。classpath 用绝对路径，CWD 随便换。
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
MSYS2_ARG_CONV_EXCL="*" "$jdk/bin/java" -Dfile.encoding=UTF-8 -cp "$w_classes;$w_out" BtSyncTest

#!/usr/bin/env bash
# Full-pipeline native injection test against the isolated-classloader harness.
#
# Goes through the real user path (the injector's --pid entry point), so this covers everything:
# process enumeration, the attach attempt, the native fallback, agent/hud/payload deployment,
# JVMTI bootstrap, bytecode transform, and the frame sink actually firing.
#
# The harness loads its game classes and the LWJGL stub through a child-first URLClassLoader whose
# parent is the platform loader, i.e. it cannot see our classes by delegation at all - only through
# the bootstrap loader.
#
# Usage: scripts/e2e-harness-native.sh <jdk-home>
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jdk="${1:-}"
if [ -z "$jdk" ]; then
  for candidate in "$here"/../.tools/jdk-*; do [ -d "$candidate" ] && jdk="$candidate"; done
fi
[ -x "$jdk/bin/java" ] || { echo "ERROR: no jdk at $jdk" >&2; exit 64; }

work="$here/build/e2e-harness-native"
harness="$here/harness/build/libs/bridgetap-harness-1.1.0.jar"
injector="$here/injector/build/libs/bridgetap-injector-1.1.0.jar"
for f in "$harness" "$injector"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 65; }
done

rm -rf "$work"; mkdir -p "$work"

"$jdk/bin/java" -XX:+DisableAttachMechanism -cp "$harness" client.IsolatedMain "$harness" \
  > "$work/target.log" 2>&1 &
targetPid=""
for _ in $(seq 1 80); do
  targetPid="$(sed -n 's/.*\[isolated\] pid=//p' "$work/target.log" 2>/dev/null | head -1)"
  [ -n "$targetPid" ] && break
  sleep 0.2
done
[ -n "$targetPid" ] || { echo "[e2e] FAIL: no pid"; cat "$work/target.log"; exit 1; }
echo "[e2e] target pid = $targetPid (isolated loader, attach disabled)"

echo "[e2e] --- injection via injector CLI ---"
"$jdk/bin/java" -cp "$injector" com.bridger.compat.injector.Main --pid "$targetPid" 2>&1 | tail -12

sleep 5
echo "[e2e] --- payload log ---"
cat "$LOCALAPPDATA/BridgeTap/inject-native.log" 2>/dev/null | tail -18 || echo "(none)"
echo "[e2e] --- agent log ---"
cat "$LOCALAPPDATA/BridgeTap/agent.log" 2>/dev/null | tail -14 || echo "(none)"
echo "[e2e] --- target still alive? ---"
tail -2 "$work/target.log"

kill "$targetPid" 2>/dev/null
taskkill //F //PID "$targetPid" >/dev/null 2>&1
echo "[e2e] done"

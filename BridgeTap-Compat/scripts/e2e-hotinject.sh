#!/usr/bin/env bash
# Hot-inject end-to-end test.
#
# Starts a fake client whose game classes live in an isolated URLClassLoader (the same isolation
# Lunar-style clients use), injects the agent into that running JVM with the injector CLI, and
# asserts that the agent actually reached the game loop.
#
# usage: e2e-hotinject.sh <jdk-home> <harness.jar> <injector.jar> <work-dir>
set -u

JDK="$1"
HARNESS="$2"
INJECTOR="$3"
WORK="$4"

rm -rf "$WORK"
mkdir -p "$WORK"
cd "$WORK" || exit 1

"$JDK/bin/java" -XX:+EnableDynamicAgentLoading -cp "$HARNESS" client.IsolatedMain "$HARNESS" > target.log 2>&1 &
MSYS_PID=$!
sleep 3

if ! kill -0 "$MSYS_PID" 2>/dev/null; then
  echo "[e2e] FAIL: target died on startup"
  cat target.log
  exit 1
fi

# bash's $! is the MSYS pid; attach needs the real Windows pid, which the target prints itself.
WIN_PID=$(sed -n 's/.*\[isolated\] pid=\([0-9][0-9]*\).*/\1/p' target.log | head -1)
echo "[e2e] msys pid=$MSYS_PID windows pid=$WIN_PID"

if [ -z "$WIN_PID" ]; then
  echo "[e2e] FAIL: could not determine target pid"
  cat target.log
  kill "$MSYS_PID" 2>/dev/null
  exit 1
fi

if [ "${INJECTOR##*.}" = "exe" ]; then
  "$INJECTOR" --pid "$WIN_PID" > injector.log 2>&1
else
  "$JDK/bin/java" -cp "$INJECTOR" com.bridger.compat.injector.Main --pid "$WIN_PID" > injector.log 2>&1
fi
INJECT_RC=$?
echo "[e2e] injector exit=$INJECT_RC"
sed 's/^/[e2e]   /' injector.log

sleep 4
taskkill //F //PID "$WIN_PID" > /dev/null 2>&1
kill "$MSYS_PID" 2>/dev/null
wait "$MSYS_PID" 2>/dev/null

echo "[e2e] ----- target log (tail) -----"
tail -20 target.log | sed 's/^/[e2e]   /'

ATTACHED=$(grep -c "agent attach" target.log || true)
RESOLVED=$(grep -c "resolved game" target.log || true)
HOOKED=$(grep -c "hooked org/lwjgl/opengl/Display" target.log || true)

echo "[e2e] hooked=$HOOKED attach=$ATTACHED resolved=$RESOLVED"

if [ "$INJECT_RC" -eq 0 ] && [ "$ATTACHED" -ge 1 ] && [ "$RESOLVED" -ge 1 ] && [ "$HOOKED" -ge 1 ]; then
  echo "[e2e] PASS"
  exit 0
fi

echo "[e2e] FAIL"
exit 1

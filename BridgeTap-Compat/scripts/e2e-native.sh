#!/usr/bin/env bash
# End-to-end test of the native injection path against a JVM that has the attach mechanism
# disabled - the exact condition Lunar Client creates with -XX:+DisableAttachMechanism.
#
# Verifies, in order:
#   1. btinject.exe can obtain a handle and place a remote thread (the attach API cannot)
#   2. the payload finds jvm.dll and JNI_GetCreatedJavaVMs
#   3. JVMTI is reachable without an attach listener
#   4. the agent jar lands on the bootstrap loader and runNative() executes
#
# Usage: scripts/e2e-native.sh <jdk-home>
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jdk="${1:-}"
if [ -z "$jdk" ]; then
  for candidate in "$here"/../.tools/jdk-*; do [ -d "$candidate" ] && jdk="$candidate"; done
fi
[ -x "$jdk/bin/java" ] || { echo "ERROR: no jdk at $jdk" >&2; exit 64; }

work="$here/build/e2e-native"
deploy="$LOCALAPPDATA/BridgeTap/inject"
rm -rf "$work"; mkdir -p "$work" "$deploy"

agent="$here/agent/build/libs/bridgetap-agent-1.1.0.jar"
payload="$here/dist/native/bridgetap-payload.dll"
inject="$here/dist/native/btinject.exe"
for f in "$agent" "$payload" "$inject"; do
  [ -f "$f" ] || { echo "ERROR: missing $f (run ./gradlew :agent:jar and native/build.sh)" >&2; exit 65; }
done

cp "$agent" "$deploy/bridgetap-agent.jar"
cp "$payload" "$deploy/bridgetap-payload.dll"
cp "$inject" "$deploy/btinject.exe"
printf 'agent=%s\n' "$(cygpath -w "$deploy/bridgetap-agent.jar")" > "$deploy/bootstrap.ini"
echo "[e2e] deploy dir: $deploy"

# A target that does nothing but sleep, with the attach mechanism turned off on purpose.
cat > "$work/Sleeper.java" <<'EOF'
public final class Sleeper {
    public static void main(String[] args) throws Exception {
        System.out.println("PID=" + ProcessHandle.current().pid());
        System.out.flush();
        Thread.sleep(120_000L);
    }
}
EOF
"$jdk/bin/javac" -d "$work" "$work/Sleeper.java" || exit 1

rm -f "$LOCALAPPDATA/BridgeTap/inject-native.log" "$LOCALAPPDATA/BridgeTap/agent.log"

"$jdk/bin/java" -XX:+DisableAttachMechanism -cp "$work" Sleeper > "$work/target.log" 2>&1 &
targetPid=""
for _ in $(seq 1 60); do
  targetPid="$(sed -n 's/^PID=//p' "$work/target.log" 2>/dev/null | head -1)"
  [ -n "$targetPid" ] && break
  sleep 0.2
done
if [ -z "$targetPid" ]; then
  echo "[e2e] FAIL: target never printed its pid"; cat "$work/target.log"; exit 1
fi
echo "[e2e] target pid = $targetPid (attach disabled)"

echo "[e2e] --- proving attach really is unavailable ---"
"$jdk/bin/jcmd" "$targetPid" VM.version 2>&1 | head -3

echo "[e2e] --- native injection ---"
( cd "$deploy" && ./btinject.exe "$targetPid" "$(cygpath -w "$deploy/bridgetap-payload.dll")" ) 2>&1 | tee "$work/inject.log"

sleep 4
echo "[e2e] --- payload log ---"
if [ -f "$LOCALAPPDATA/BridgeTap/inject-native.log" ]; then
  cat "$LOCALAPPDATA/BridgeTap/inject-native.log"
else
  echo "(no payload log - the DLL never ran)"
fi
echo "[e2e] --- agent log ---"
if [ -f "$LOCALAPPDATA/BridgeTap/agent.log" ]; then
  cat "$LOCALAPPDATA/BridgeTap/agent.log"
else
  echo "(no agent log - class load or runNative failed)"
fi

kill "$targetPid" 2>/dev/null
taskkill //F //PID "$targetPid" >/dev/null 2>&1
echo "[e2e] target stopped"

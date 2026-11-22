@echo off
REM BridgeTap for Lunar Client.
REM Preferred: paste the -javaagent line into Lunar's Settings > JVM Arguments.
REM This script is the fallback for launchers without such a field.
setlocal
set "BT_DIR=%~dp0"
set "AGENT=%BT_DIR%bridgetap-agent-2.6.0.jar"

if not exist "%AGENT%" (
  echo [BridgeTap] agent jar missing: %AGENT%
  pause
  exit /b 1
)

set "JAVA_TOOL_OPTIONS=-javaagent:%AGENT% -Djava.awt.headless=true"
echo [BridgeTap] JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS%
echo [BridgeTap] now start Lunar Client from this window.
endlocal

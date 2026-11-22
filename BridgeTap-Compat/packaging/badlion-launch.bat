@echo off
REM BridgeTap for Badlion Client.
REM Badlion's launcher exposes no JVM-argument field, so the agent is injected through
REM JAVA_TOOL_OPTIONS, which every HotSpot JVM picks up before main().
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
echo [BridgeTap] start Badlion from THIS window so it inherits the variable.
echo.

set "BADLION=%LOCALAPPDATA%\Programs\Badlion Client\Badlion Client.exe"
if exist "%BADLION%" (
  start "" "%BADLION%"
) else (
  echo [BridgeTap] launcher not found at "%BADLION%"
  echo [BridgeTap] start it manually from this console, or edit BADLION= above.
)
endlocal

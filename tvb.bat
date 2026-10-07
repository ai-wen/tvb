@echo off
setlocal

REM ============================================================
REM  MyTVB TV App build script
REM  JDK   : Android Studio bundled JBR (C:\Program Files\Android\Android Studio\jbr)
REM  Task  : assembleDebugRenamed
REM  Output: app\build\outputs\renamed_apk\debug\MyTVB-v<version>-debug.apk
REM  Usage : build.bat [extra gradle args, e.g. --refresh-dependencies]
REM ============================================================

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Switch to project root (directory of this script)
cd /d "%~dp0"

REM Ensure local.properties points at the Android SDK
if not exist "local.properties" (
    > "local.properties" echo sdk.dir=%LOCALAPPDATA:\=/%/Android/Sdk
)

echo [build.bat] JAVA_HOME = %JAVA_HOME%
echo [build.bat] Task      = assembleDebugRenamed %*
echo.

call gradlew.bat assembleDebugRenamed --stacktrace %*
if errorlevel 1 (
    echo.
    echo [build.bat] BUILD FAILED
    goto :end
)

echo.
echo [build.bat] BUILD SUCCESSFUL
echo [build.bat] APK output:
dir /b "app\build\outputs\renamed_apk\debug\*.apk" 2>nul

:end
REM Pause only when double-clicked from Explorer, not when run from a terminal
echo %cmdcmdline% | find /i "%~nx0" >nul && pause
endlocal

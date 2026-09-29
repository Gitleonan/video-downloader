@echo off
rem One-shot debug build.
rem
rem   scripts\build.bat
rem
rem Produces a universal, debug-signed APK:
rem   app\build\outputs\apk\debug\Video Downloader.apk
setlocal
cd /d "%~dp0.."

if "%JAVA_HOME%"=="" (
  where java >nul 2>nul
  if errorlevel 1 (
    echo error: no JDK found. Install JDK 17 and set JAVA_HOME ^(or put java on PATH^).
    exit /b 1
  )
)

if not exist local.properties (
  if "%ANDROID_HOME%%ANDROID_SDK_ROOT%"=="" (
    echo error: no Android SDK configured.
    echo        Create local.properties with:  sdk.dir=/path/to/Android/Sdk
    echo        ^(or set ANDROID_HOME^).
    exit /b 1
  )
)

call gradlew.bat :app:assembleDebug
if errorlevel 1 exit /b 1

echo.
echo built: app\build\outputs\apk\debug\Video Downloader.apk

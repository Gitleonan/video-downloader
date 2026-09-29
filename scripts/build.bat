@echo off
rem One-shot build.
rem
rem   scripts\build.bat            -^> debug   ^(app\build\outputs\apk\debug\Video Downloader.apk^)
rem   scripts\build.bat release    -^> release ^(app\build\outputs\apk\release\Video Downloader.apk^)
rem
rem The debug variant is signed with the public Android debug key and carries
rem android:debuggable="true", which device security scanners flag as high risk.
rem Use `release` for anything you hand to other people.
setlocal
cd /d "%~dp0.."

set VARIANT=%1
if "%VARIANT%"=="" set VARIANT=debug

if /i not "%VARIANT%"=="debug" if /i not "%VARIANT%"=="release" (
  echo error: unknown variant "%VARIANT%" ^(expected debug or release^).
  exit /b 1
)

set TASK=Debug
if /i "%VARIANT%"=="release" set TASK=Release

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

if /i "%VARIANT%"=="release" (
  if not exist keystore.properties (
    echo error: a release build needs signing credentials.
    echo        Create keystore.properties at the repo root - see README.
    exit /b 1
  )
)

call gradlew.bat :app:assemble%TASK%
if errorlevel 1 exit /b 1

echo.
echo built: app\build\outputs\apk\%VARIANT%\Video Downloader.apk

@echo off
rem Serve the packaged page for browser development, without building an APK.
rem
rem   scripts\serve.bat [port]      -^>  http://localhost:8123
rem
rem Why a server at all: the page is split into index.html + css\ + js\ + img\
rem with relative paths. Opening the file directly (file://) breaks those, and
rem IndexedDB / localStorage behave differently there too. Inside the APK the
rem WebViewAssetLoader serves the same tree over https, so this mirrors it.
setlocal
cd /d "%~dp0.."

set PORT=%1
if "%PORT%"=="" set PORT=8123

where python >nul 2>nul
if errorlevel 1 (
  echo error: python not found ^(needed for a static file server^).
  exit /b 1
)

echo serving app\src\main\assets on http://localhost:%PORT%  ^(Ctrl-C to stop^)
python -m http.server %PORT% --directory app\src\main\assets

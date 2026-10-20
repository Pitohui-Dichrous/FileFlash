@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat :app:assembleDebug :core:test
if errorlevel 1 goto failed
if not exist dist mkdir dist
copy /y "app\build\outputs\apk\debug\app-debug.apk" "dist\FileFlash-debug.apk" >nul
echo APK: %CD%\dist\FileFlash-debug.apk
pause
exit /b 0
:failed
echo Build failed. See README.md for JDK and Android SDK setup.
pause
exit /b 1

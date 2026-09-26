@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\hbs\play.ps1" %*
set "HBS_EXIT=%ERRORLEVEL%"
if not "%HBS_EXIT%"=="0" pause
exit /b %HBS_EXIT%

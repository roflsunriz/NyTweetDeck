@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-nytweetdeck.ps1"
if errorlevel 1 pause

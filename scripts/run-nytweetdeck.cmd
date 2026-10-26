@echo off
setlocal
wscript.exe //nologo "%~dp0run-nytweetdeck.vbs" --background %*
if errorlevel 1 pause

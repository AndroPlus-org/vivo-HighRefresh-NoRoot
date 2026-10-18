@echo off
rem Runs vivo-rms.ps1 without changing the PowerShell execution policy.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0vivo-rms.ps1" %*

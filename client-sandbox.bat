@echo off
title Mindless Client Sandbox
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\client-sandbox\start.ps1"

if errorlevel 1 (
    echo.
    echo Failed to start the Mindless Client Sandbox.
    pause
)

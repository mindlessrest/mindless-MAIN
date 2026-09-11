@echo off
setlocal
title Mindless Developer Build

where python >nul 2>&1
if errorlevel 1 (
    echo [x] Python is required. Install Python 3 and try again.
    exit /b 1
)

set "MINDLESS_DEBUG_LOGS=1"
python "%~dp0tools\build.py" --dev
exit /b %errorlevel%

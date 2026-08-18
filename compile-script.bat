@echo off
title Mindless Script Compiler

if "%~1"=="" (
    echo Drag a .java script onto this file.
    pause
    exit /b 1
)

python "%~dp0msbt.py" %*
exit /b %errorlevel%

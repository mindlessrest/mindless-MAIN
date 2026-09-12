@echo off
setlocal EnableDelayedExpansion
title Mindless United - Build

echo.
echo  ==========================================
echo   Mindless United  -  Build Launcher
echo  ==========================================
echo.

:: -----------------------------------------------
:: 1. Check Python
:: -----------------------------------------------
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo  [!] Python not found. Installing via winget...
    echo.
    winget install --id Python.Python.3.12 -e --silent --accept-source-agreements --accept-package-agreements
    if %errorlevel% neq 0 (
        echo.
        echo  [x] winget install failed.
        echo      Download Python from https://python.org and re-run this file.
        echo      Make sure to check "Add Python to PATH" during install.
        goto :done
    )
    echo.
    echo  [+] Python installed. Refreshing PATH...
    call :reload_path
    python --version >nul 2>&1
    if %errorlevel% neq 0 (
        echo  [x] Python still not found after install.
        echo      Close this window, reopen it, and try again.
        goto :done
    )
)

for /f "tokens=*" %%v in ('python --version 2^>^&1') do set PYVER=%%v
echo  [+] %PYVER%

:: -----------------------------------------------
:: 2. tools\build.py uses stdlib only - no pip installs
::    needed. But verify pip works just in case.
:: -----------------------------------------------
python -m pip --version >nul 2>&1
if %errorlevel% equ 0 (
    echo  [+] pip OK
) else (
    echo  [!] pip not available - ensurepip...
    python -m ensurepip --upgrade >nul 2>&1
)

:: -----------------------------------------------
:: 3. Run tools\build.py  (pass any args: build.bat --loader etc.)
:: -----------------------------------------------
echo.
echo  ==========================================
echo   Running tools\build.py --prod %*
echo  ==========================================
echo.

python "%~dp0tools\build.py" --prod %*
set EXIT_CODE=%errorlevel%

echo.
echo  ==========================================
if %EXIT_CODE% equ 0 (
    echo   Done.  Exit code 0
) else (
    echo   Finished with errors.  Exit code %EXIT_CODE%
)
echo  ==========================================

:done
echo.
pause
exit /b %EXIT_CODE%


:: -----------------------------------------------
:: Reload PATH from registry so fresh installs
:: are visible without reopening the terminal.
:: -----------------------------------------------
:reload_path
set "SYS_PATH="
set "USR_PATH="
for /f "tokens=2*" %%a in (
    'reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment" /v Path 2^>nul'
) do set "SYS_PATH=%%b"
for /f "tokens=2*" %%a in (
    'reg query "HKCU\Environment" /v Path 2^>nul'
) do set "USR_PATH=%%b"
if defined SYS_PATH (
    if defined USR_PATH (
        set "PATH=!SYS_PATH!;!USR_PATH!"
    ) else (
        set "PATH=!SYS_PATH!"
    )
)
exit /b 0

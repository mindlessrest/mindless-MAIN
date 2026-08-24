@echo off
REM Builds MindlessAudioTap.dll and drops it into the client resources so the jar carries it.
REM Requires Visual Studio Build Tools with the Windows 10/11 SDK (process loopback needs the
REM 10.0.20348 headers or newer).

setlocal

set "SCRIPT_DIR=%~dp0"
set "OUT_DIR=%SCRIPT_DIR%build"
set "RES_DIR=%SCRIPT_DIR%..\src\main\resources\mindless\native"

if not defined VSINSTALL set "VSINSTALL=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools"
set "VCVARS=%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat"

if not exist "%VCVARS%" (
    echo [!] vcvars64.bat not found at "%VCVARS%".
    echo     Set VSINSTALL to your Visual Studio install root and retry.
    exit /b 1
)

call "%VCVARS%" >nul
if errorlevel 1 exit /b 1

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
if not exist "%RES_DIR%" mkdir "%RES_DIR%"

pushd "%OUT_DIR%"

cl /nologo /LD /O2 /MT /EHsc /W3 /std:c++17 /D_CRT_SECURE_NO_WARNINGS ^
   "%SCRIPT_DIR%spotify_tap.cpp" ^
   /Fe:MindlessAudioTap.dll ^
   /link /DLL ole32.lib mmdevapi.lib
if errorlevel 1 (
    popd
    echo [!] Build failed.
    exit /b 1
)

popd

copy /Y "%OUT_DIR%\MindlessAudioTap.dll" "%RES_DIR%\MindlessAudioTap.dll" >nul
if errorlevel 1 (
    echo [!] Could not copy the DLL into "%RES_DIR%".
    exit /b 1
)

echo [+] MindlessAudioTap.dll built and copied to resources.
endlocal

@echo off
call "%~dp0build.bat" --all --test-loader
exit /b %errorlevel%

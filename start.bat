@echo off
chcp 65001 >nul 2>nul
title 12306 Startup
cd /d "%~dp0"

REM ============================================================
REM  start.bat - launcher for start.py
REM  Tries: python (PATH) -> common install paths -> py launcher
REM ============================================================

REM Try python in PATH first
where python >nul 2>nul
if %errorlevel%==0 (
    python "%~dp0start.py"
    goto :check_result
)

REM Try common Python install locations
if exist "D:\python\python.exe" (
    "D:\python\python.exe" "%~dp0start.py"
    goto :check_result
)

if exist "C:\Python312\python.exe" (
    "C:\Python312\python.exe" "%~dp0start.py"
    goto :check_result
)

if exist "C:\Python311\python.exe" (
    "C:\Python311\python.exe" "%~dp0start.py"
    goto :check_result
)

if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" (
    "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%~dp0start.py"
    goto :check_result
)

REM Try py launcher as last resort
where py >nul 2>nul
if %errorlevel%==0 (
    py -3 "%~dp0start.py"
    goto :check_result
)

echo.
echo [X] Python not found
echo     Please install Python 3.8+ and add to PATH
echo     https://www.python.org/downloads/
echo.
pause
exit /b 1

:check_result
if errorlevel 1 (
    echo.
    echo [X] Startup failed, check error messages above
    echo.
    pause
)

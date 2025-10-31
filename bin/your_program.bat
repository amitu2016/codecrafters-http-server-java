@echo off
setlocal

REM Use this script to run your program LOCALLY on Windows.
REM Note: Changing this script WILL NOT affect how CodeCrafters runs your program.

REM Ensure compile steps are run within the repository directory
cd /d "%~dp0"

REM Build directory
set "BUILD_DIR=%TEMP%\codecrafters-build-http-server-java"

REM Compile (copied behavior from .codecrafters/compile.sh)
mvn -q -B package -Ddir="%BUILD_DIR%"
if errorlevel 1 exit /b 1

REM Run (copied behavior from .codecrafters/run.sh)
java --enable-preview -jar "%BUILD_DIR%\codecrafters-http-server.jar" %*

endlocal


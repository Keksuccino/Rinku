@echo off
setlocal
call "%~dp0gradlew.bat" verifyAll %*
exit /b %ERRORLEVEL%

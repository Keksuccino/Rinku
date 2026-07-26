@echo off
setlocal
call "%~dp0gradlew.bat" verifyAll buildSrc:test %*
exit /b %ERRORLEVEL%

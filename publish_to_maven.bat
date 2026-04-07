@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PROJECT_DIR=%~dp0"
set "GRADLE_PROPS=%PROJECT_DIR%gradle.properties"
set "GIT_ERROR="

if not exist "%GRADLE_PROPS%" (
    echo Gradle properties file not found: %GRADLE_PROPS%
    goto :finish
)

for /f "usebackq tokens=1,* delims==" %%A in ("%GRADLE_PROPS%") do (
    set "KEY=%%~A"
    set "VALUE=%%~B"
    for /f "tokens=* delims= " %%k in ("!KEY!") do set "KEY=%%k"
    for /f "tokens=* delims= " %%v in ("!VALUE!") do set "VALUE=%%v"
    if defined KEY (
        if /I not "!KEY:~0,1!"=="#" (
            if /I "!KEY!"=="version" set "PROJECT_VERSION=!VALUE!"
            if /I "!KEY!"=="group" set "GROUP=!VALUE!"
            if /I "!KEY!"=="minecraft_version" set "MINECRAFT_VERSION=!VALUE!"
            if /I "!KEY!"=="mod_version" set "MOD_VERSION=!VALUE!"
            if /I "!KEY!"=="mod_id" set "MOD_ID=!VALUE!"
            if /I "!KEY!"=="java_version" set "JAVA_VERSION=!VALUE!"
        )
    )
)

if not defined MINECRAFT_VERSION (
    echo minecraft_version not found in gradle.properties
    goto :finish
)
if not defined MOD_VERSION (
    echo mod_version not found in gradle.properties
    goto :finish
)
if not defined MOD_ID (
    echo mod_id not found in gradle.properties
    goto :finish
)
if not defined PROJECT_VERSION set "PROJECT_VERSION=1.0.0"
if not defined GROUP set "GROUP=de.keksuccino"
if not defined JAVA_VERSION set "JAVA_VERSION=25"

set "PUBLISH_VERSION=%MOD_VERSION%-%MINECRAFT_VERSION%"
set "GROUP_PATH=%GROUP:.=\%"
set "GROUP_PATH_GIT=%GROUP:.=/%"

set "FABRIC_ARTIFACT_ID=%MOD_ID%-fabric"
set "FABRIC_FILENAME_BASE=%FABRIC_ARTIFACT_ID%-%PUBLISH_VERSION%"
set "FABRIC_JAR_SRC=%PROJECT_DIR%fabric\build\libs\%MOD_ID%-%PROJECT_VERSION%.jar"
set "FABRIC_SOURCES_SRC=%PROJECT_DIR%fabric\build\libs\%MOD_ID%-%PROJECT_VERSION%-sources.jar"

set "NEOFORGE_ARTIFACT_ID=%MOD_ID%-neoforge"
set "NEOFORGE_FILENAME_BASE=%NEOFORGE_ARTIFACT_ID%-%PUBLISH_VERSION%"
set "NEOFORGE_JAR_SRC=%PROJECT_DIR%neoforge\build\libs\%MOD_ID%-%PROJECT_VERSION%-all.jar"
set "NEOFORGE_SOURCES_SRC=%PROJECT_DIR%neoforge\build\libs\%MOD_ID%-%PROJECT_VERSION%-sources.jar"

for %%F in (
    "%FABRIC_JAR_SRC%"
    "%FABRIC_SOURCES_SRC%"
    "%NEOFORGE_JAR_SRC%"
    "%NEOFORGE_SOURCES_SRC%"
) do (
    if not exist "%%~fF" (
        echo Missing build artifact: %%~fF
        echo Build the latest loader jars first, then rerun this script.
        goto :finish
    )
)

set "REPO_URL=https://github.com/Keksuccino/keksuccino.github.io.git"
set "WORK_DIR=%PROJECT_DIR%build\maven-publish"
set "REPO_DIR=%WORK_DIR%\keksuccino.github.io"

if not exist "%WORK_DIR%" mkdir "%WORK_DIR%"

if exist "%REPO_DIR%\.git" (
    pushd "%REPO_DIR%"
    git fetch origin main || goto :gitfail
    git checkout main || goto :gitfail
    git pull --ff-only || goto :gitfail
    popd
) else (
    pushd "%WORK_DIR%"
    git clone --branch main "%REPO_URL%" "%REPO_DIR%" || goto :gitfail
    popd
)

set "FABRIC_TARGET_DIR=%REPO_DIR%\maven\%GROUP_PATH%\%FABRIC_ARTIFACT_ID%\%PUBLISH_VERSION%"
if not exist "%FABRIC_TARGET_DIR%" mkdir "%FABRIC_TARGET_DIR%" || goto :copyfail
copy /y "%FABRIC_JAR_SRC%" "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%.jar" >nul || goto :copyfail
copy /y "%FABRIC_SOURCES_SRC%" "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%-sources.jar" >nul || goto :copyfail
call :writePom "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%.pom" "%FABRIC_ARTIFACT_ID%" "%PUBLISH_VERSION%" || goto :metadatafail
call :writeModuleMetadata "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%.module" "%FABRIC_ARTIFACT_ID%" "%PUBLISH_VERSION%" "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%.jar" "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%-sources.jar" || goto :metadatafail

set "NEOFORGE_TARGET_DIR=%REPO_DIR%\maven\%GROUP_PATH%\%NEOFORGE_ARTIFACT_ID%\%PUBLISH_VERSION%"
if not exist "%NEOFORGE_TARGET_DIR%" mkdir "%NEOFORGE_TARGET_DIR%" || goto :copyfail
copy /y "%NEOFORGE_JAR_SRC%" "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%.jar" >nul || goto :copyfail
copy /y "%NEOFORGE_SOURCES_SRC%" "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%-sources.jar" >nul || goto :copyfail
call :writePom "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%.pom" "%NEOFORGE_ARTIFACT_ID%" "%PUBLISH_VERSION%" || goto :metadatafail
call :writeModuleMetadata "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%.module" "%NEOFORGE_ARTIFACT_ID%" "%PUBLISH_VERSION%" "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%.jar" "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%-sources.jar" || goto :metadatafail

pushd "%REPO_DIR%"
git add "maven/%GROUP_PATH_GIT%/%FABRIC_ARTIFACT_ID%/%PUBLISH_VERSION%" "maven/%GROUP_PATH_GIT%/%NEOFORGE_ARTIFACT_ID%/%PUBLISH_VERSION%" || goto :gitfail
git diff --cached --quiet --
if errorlevel 1 (
    git commit -m "Publish %MOD_ID% %PUBLISH_VERSION%" || goto :gitfail
    git push origin main || goto :gitfail
    if not defined GIT_ERROR (
        echo Artifacts published to %REPO_URL%
    )
) else (
    echo No changes to publish.
)
popd
goto :finish

:gitfail
set "GIT_ERROR=1"
echo Git operation failed.
popd
goto :finish

:copyfail
echo Failed to copy artifacts to Maven repository.
goto :finish

:metadatafail
echo Failed to generate Maven metadata files.
goto :finish

:finish
echo.
pause
endlocal
goto :eof

:writePom
set "POM_PATH=%~1"
set "POM_ARTIFACT_ID=%~2"
set "POM_VERSION=%~3"
setlocal DisableDelayedExpansion
(
echo ^<?xml version="1.0" encoding="UTF-8"?^>
echo ^<project xmlns="http://maven.apache.org/POM/4.0.0" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"^>
echo   ^<!-- This module was also published with a richer model, Gradle metadata,  --^>
echo   ^<!-- which should be used instead. Do not delete the following line which  --^>
echo   ^<!-- is to indicate to Gradle or any Gradle module metadata file consumer  --^>
echo   ^<!-- that they should prefer consuming it instead. --^>
echo   ^<!-- do_not_remove: published-with-gradle-metadata --^>
echo   ^<modelVersion^>4.0.0^</modelVersion^>
echo   ^<groupId^>%GROUP%^</groupId^>
echo   ^<artifactId^>%POM_ARTIFACT_ID%^</artifactId^>
echo   ^<version^>%POM_VERSION%^</version^>
echo ^</project^>
) > "%POM_PATH%"
set "WRITE_POM_ERROR=%errorlevel%"
endlocal & exit /b %WRITE_POM_ERROR%

:writeModuleMetadata
set "MODULE_PATH=%~1"
set "MODULE_ARTIFACT_ID=%~2"
set "MODULE_VERSION=%~3"
set "MODULE_MAIN_FILE=%~4"
set "MODULE_SOURCES_FILE=%~5"
call :populateArtifactMetadata MAIN "%MODULE_MAIN_FILE%" || exit /b 1
call :populateArtifactMetadata SOURCES "%MODULE_SOURCES_FILE%" || exit /b 1
(
echo {
echo   "formatVersion": "1.1",
echo   "component": {
echo     "group": "%GROUP%",
echo     "module": "%MODULE_ARTIFACT_ID%",
echo     "version": "%MODULE_VERSION%",
echo     "attributes": {
echo       "org.gradle.status": "release"
echo     }
echo   },
echo   "variants": [
echo     {
echo       "name": "apiElements",
echo       "attributes": {
echo         "org.gradle.category": "library",
echo         "org.gradle.dependency.bundling": "external",
echo         "org.gradle.jvm.version": %JAVA_VERSION%,
echo         "org.gradle.libraryelements": "jar",
echo         "org.gradle.usage": "java-api"
echo       },
echo       "files": [
echo         {
echo           "name": "!MAIN_NAME!",
echo           "url": "!MAIN_NAME!",
echo           "size": !MAIN_SIZE!,
echo           "sha512": "!MAIN_SHA512!",
echo           "sha256": "!MAIN_SHA256!",
echo           "sha1": "!MAIN_SHA1!",
echo           "md5": "!MAIN_MD5!"
echo         }
echo       ]
echo     },
echo     {
echo       "name": "runtimeElements",
echo       "attributes": {
echo         "org.gradle.category": "library",
echo         "org.gradle.dependency.bundling": "external",
echo         "org.gradle.jvm.version": %JAVA_VERSION%,
echo         "org.gradle.libraryelements": "jar",
echo         "org.gradle.usage": "java-runtime"
echo       },
echo       "files": [
echo         {
echo           "name": "!MAIN_NAME!",
echo           "url": "!MAIN_NAME!",
echo           "size": !MAIN_SIZE!,
echo           "sha512": "!MAIN_SHA512!",
echo           "sha256": "!MAIN_SHA256!",
echo           "sha1": "!MAIN_SHA1!",
echo           "md5": "!MAIN_MD5!"
echo         }
echo       ]
echo     },
echo     {
echo       "name": "sourcesElements",
echo       "attributes": {
echo         "org.gradle.category": "documentation",
echo         "org.gradle.dependency.bundling": "external",
echo         "org.gradle.docstype": "sources",
echo         "org.gradle.usage": "java-runtime"
echo       },
echo       "files": [
echo         {
echo           "name": "!SOURCES_NAME!",
echo           "url": "!SOURCES_NAME!",
echo           "size": !SOURCES_SIZE!,
echo           "sha512": "!SOURCES_SHA512!",
echo           "sha256": "!SOURCES_SHA256!",
echo           "sha1": "!SOURCES_SHA1!",
echo           "md5": "!SOURCES_MD5!"
echo         }
echo       ]
echo     }
echo   ]
echo }
) > "%MODULE_PATH%" || exit /b 1
exit /b 0

:populateArtifactMetadata
set "ARTIFACT_PREFIX=%~1"
set "ARTIFACT_FILE=%~2"
if not exist "%ARTIFACT_FILE%" exit /b 1
for %%I in ("%ARTIFACT_FILE%") do (
    set "%ARTIFACT_PREFIX%_NAME=%%~nxI"
    set "%ARTIFACT_PREFIX%_SIZE=%%~zI"
)
call :computeFileHash "%ARTIFACT_FILE%" SHA512 %ARTIFACT_PREFIX%_SHA512 || exit /b 1
call :computeFileHash "%ARTIFACT_FILE%" SHA256 %ARTIFACT_PREFIX%_SHA256 || exit /b 1
call :computeFileHash "%ARTIFACT_FILE%" SHA1 %ARTIFACT_PREFIX%_SHA1 || exit /b 1
call :computeFileHash "%ARTIFACT_FILE%" MD5 %ARTIFACT_PREFIX%_MD5 || exit /b 1
exit /b 0

:computeFileHash
set "HASH_FILE=%~1"
set "HASH_ALGORITHM=%~2"
set "HASH_OUTPUT_VAR=%~3"
set "HASH_VALUE="
for /f "usebackq delims=" %%H in (`powershell -NoProfile -Command "(Get-FileHash -LiteralPath '%HASH_FILE%' -Algorithm %HASH_ALGORITHM%).Hash.ToLowerInvariant()"`) do (
    if not defined HASH_VALUE set "HASH_VALUE=%%H"
)
if not defined HASH_VALUE exit /b 1
set "%HASH_OUTPUT_VAR%=%HASH_VALUE%"
exit /b 0

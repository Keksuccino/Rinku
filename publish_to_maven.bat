@echo off
setlocal enabledelayedexpansion

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
            if /I "!KEY!"=="minecraft_version" set "MINECRAFT_VERSION=!VALUE!"
            if /I "!KEY!"=="mod_version" set "MOD_VERSION=!VALUE!"
            if /I "!KEY!"=="mod_id" set "MOD_ID=!VALUE!"
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

set "VERSION=%MOD_VERSION%-%MINECRAFT_VERSION%"
set "FABRIC_LIB_DIR=%PROJECT_DIR%fabric\build\libs"
set "NEOFORGE_LIB_DIR=%PROJECT_DIR%neoforge\build\libs"
set "FABRIC_BASE=%MOD_ID%-1.0.0"
set "NEOFORGE_BASE=%MOD_ID%-1.0.0"
set "GRADLEW=%PROJECT_DIR%gradlew.bat"

if not exist "%GRADLEW%" (
    echo Gradle wrapper not found: %GRADLEW%
    goto :finish
)

pushd "%PROJECT_DIR%"
call "%GRADLEW%" :fabric:generatePomFileForMavenJavaPublication :fabric:generateMetadataFileForMavenJavaPublication :neoforge:generatePomFileForMavenJavaPublication :neoforge:generateMetadataFileForMavenJavaPublication
if errorlevel 1 (
    popd
    goto :gradlefail
)
popd

for %%F in ("%FABRIC_LIB_DIR%\%FABRIC_BASE%-named.jar" "%FABRIC_LIB_DIR%\%FABRIC_BASE%-sources.jar" "%FABRIC_LIB_DIR%\%FABRIC_BASE%.pom") do (
    if not exist "%%~fF" (
        echo Missing Fabric artifact: %%~fF
        goto :finish
    )
)

for %%F in ("%NEOFORGE_LIB_DIR%\%NEOFORGE_BASE%.jar" "%NEOFORGE_LIB_DIR%\%NEOFORGE_BASE%-sources.jar" "%NEOFORGE_LIB_DIR%\%NEOFORGE_BASE%.pom") do (
    if not exist "%%~fF" (
        echo Missing NeoForge artifact: %%~fF
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

set "FABRIC_ARTIFACT_ID=%MOD_ID%-fabric"
set "FABRIC_FILENAME_BASE=%FABRIC_ARTIFACT_ID%-%VERSION%"
set "FABRIC_TARGET_DIR=%REPO_DIR%\maven\de\keksuccino\%FABRIC_ARTIFACT_ID%\%VERSION%"
set "FABRIC_MODULE_SRC=%PROJECT_DIR%fabric\build\publications\mavenJava\module.json"
if not exist "%FABRIC_MODULE_SRC%" (
    echo Missing Fabric metadata file: %FABRIC_MODULE_SRC%
    goto :finish
)
call :stripModuleDependencies "%FABRIC_MODULE_SRC%" || goto :modulecleanfail
if not exist "%FABRIC_TARGET_DIR%" mkdir "%FABRIC_TARGET_DIR%"
copy /y "%FABRIC_LIB_DIR%\%FABRIC_BASE%-named.jar" "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%.jar" >nul || goto :copyfail
copy /y "%FABRIC_LIB_DIR%\%FABRIC_BASE%-sources.jar" "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%-sources.jar" >nul || goto :copyfail
copy /y "%FABRIC_LIB_DIR%\%FABRIC_BASE%.pom" "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%.pom" >nul || goto :copyfail
copy /y "%FABRIC_MODULE_SRC%" "%FABRIC_TARGET_DIR%\%FABRIC_FILENAME_BASE%.module" >nul || goto :copyfail

set "NEOFORGE_ARTIFACT_ID=%MOD_ID%-neoforge"
set "NEOFORGE_FILENAME_BASE=%NEOFORGE_ARTIFACT_ID%-%VERSION%"
set "NEOFORGE_TARGET_DIR=%REPO_DIR%\maven\de\keksuccino\%NEOFORGE_ARTIFACT_ID%\%VERSION%"
set "NEOFORGE_MODULE_SRC=%PROJECT_DIR%neoforge\build\publications\mavenJava\module.json"
if not exist "%NEOFORGE_MODULE_SRC%" (
    echo Missing NeoForge metadata file: %NEOFORGE_MODULE_SRC%
    goto :finish
)
call :stripModuleDependencies "%NEOFORGE_MODULE_SRC%" || goto :modulecleanfail
if not exist "%NEOFORGE_TARGET_DIR%" mkdir "%NEOFORGE_TARGET_DIR%"
copy /y "%NEOFORGE_LIB_DIR%\%NEOFORGE_BASE%.jar" "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%.jar" >nul || goto :copyfail
copy /y "%NEOFORGE_LIB_DIR%\%NEOFORGE_BASE%-sources.jar" "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%-sources.jar" >nul || goto :copyfail
copy /y "%NEOFORGE_LIB_DIR%\%NEOFORGE_BASE%.pom" "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%.pom" >nul || goto :copyfail
copy /y "%NEOFORGE_MODULE_SRC%" "%NEOFORGE_TARGET_DIR%\%NEOFORGE_FILENAME_BASE%.module" >nul || goto :copyfail

pushd "%REPO_DIR%"
git add "maven/de/keksuccino/%FABRIC_ARTIFACT_ID%/%VERSION%" "maven/de/keksuccino/%NEOFORGE_ARTIFACT_ID%/%VERSION%" || goto :gitfail
set "CHANGES="
for /f %%S in ('git status --porcelain') do (
    set "CHANGES=1"
    goto :statusChecked
)
:statusChecked
if defined CHANGES (
    git commit -m "Publish %MOD_ID% %VERSION%" || goto :gitfail
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

:modulecleanfail
echo Failed to strip dependencies from metadata files.
goto :finish

:gradlefail
echo Gradle metadata generation failed.
goto :finish

:copyfail
echo Failed to copy artifacts to Maven repository.
goto :finish

:finish
echo.
pause
endlocal
goto :eof

:stripModuleDependencies
set "MODULE_PATH=%~1"
powershell -NoProfile -Command "try { $modulePath = '%MODULE_PATH%'; $json = Get-Content -Raw -LiteralPath $modulePath; $obj = $json | ConvertFrom-Json; if ($obj -and $obj.variants) { foreach ($variant in $obj.variants) { $variant.PSObject.Properties.Remove('dependencies') } }; [System.IO.File]::WriteAllText($modulePath, ($obj | ConvertTo-Json -Depth 100), [System.Text.Encoding]::UTF8); exit 0 } catch { exit 1 }"
exit /b !errorlevel!

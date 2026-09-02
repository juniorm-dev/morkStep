@echo off
setlocal
cd /d "%~dp0"

echo [1/4] Fetching tags from origin...
git fetch --tags --prune origin
if errorlevel 1 goto :err

echo [2/4] Building release package (version guard active)...
call gradlew.bat assembleRelease --console=plain
if errorlevel 1 goto :err

rem Read the versionName that was actually built (the guard already checked it
rem is not yet tagged, so tag and package version cannot drift).
for /f "tokens=3" %%a in ('findstr /c:"versionName = " app\build.gradle.kts') do set "VER=%%~a"
if "%VER%"=="" goto :err
set "TAG=v%VER%"

echo [3/4] Tagging %TAG%...
git tag -a "%TAG%" -m "morkStep %VER%"
if errorlevel 1 goto :err

echo [4/4] Pushing branch and tag...
git push origin HEAD
if errorlevel 1 goto :err
git push origin "%TAG%"
if errorlevel 1 goto :err

echo.
echo Done.
echo   Package: app\build\outputs\apk\release\morkStep-release-%VER%.apk
echo   Tag:     %TAG%
exit /b 0

:err
echo.
echo FAILED. No tag was pushed.
exit /b 1
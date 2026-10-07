@echo off
setlocal

if /i "%~1"=="--help" goto help
if /i "%~1"=="-h" goto help
if /i "%~1"=="/?" goto help
set "FORGE_APK_VARIANT=debug"
if /i "%~1"=="release" set "FORGE_APK_VARIANT=release"
if not "%~1"=="" if /i not "%~1"=="debug" if /i not "%~1"=="release" goto usage_error
if not "%~2"=="" goto usage_error

pushd "%~dp0"
if errorlevel 1 exit /b 1

if not defined JAVA_HOME (
    for /d %%D in ("%~dp0.cache\jdk17\*") do (
        if exist "%%~fD\bin\java.exe" set "JAVA_HOME=%%~fD"
    )
)
if not defined JAVA_HOME (
    echo ERROR: Set JAVA_HOME to a JDK 17 installation.
    goto build_failed
)
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JAVA_HOME does not contain bin\java.exe.
    goto build_failed
)

set "FORGE_NATIVE_TOOLS=%~dp0.cache\native-tools\forge-source-candidate"
for %%F in (aapt2 zipalign NOTICE.txt notice-inventory.json provenance.json) do (
    if not exist "%FORGE_NATIVE_TOOLS%\%%F" (
        echo ERROR: Missing native tool input: %%F
        echo Prepare the native tools first; see docs\BUILDING.md.
        goto build_failed
    )
)

set "FORGE_GRADLE_TASK=:app:assembleOnlineDebug"
if /i "%FORGE_APK_VARIANT%"=="release" call :load_release_signing
if /i "%FORGE_APK_VARIANT%"=="release" (
    if not defined MH_UPLOAD_STORE_FILE goto missing_signing
    if not defined MH_UPLOAD_STORE_PASSWORD goto missing_signing
    if not defined MH_UPLOAD_KEY_ALIAS goto missing_signing
    if not defined MH_UPLOAD_KEY_PASSWORD goto missing_signing
    set "FORGE_GRADLE_TASK=:app:assembleOnlineRelease"
)

call "%~dp0mobile\gradlew.bat" -p "%~dp0mobile" %FORGE_GRADLE_TASK% -PmhNdkVersion=27.2.12479018 --console=plain
set "FORGE_BUILD_EXIT_CODE=%ERRORLEVEL%"
if not "%FORGE_BUILD_EXIT_CODE%"=="0" (
    popd
    exit /b %FORGE_BUILD_EXIT_CODE%
)

set "FORGE_APK=%~dp0mobile\app\build\outputs\apk\online\%FORGE_APK_VARIANT%\app-online-%FORGE_APK_VARIANT%.apk"
if not exist "%FORGE_APK%" (
    echo ERROR: Expected APK output was not found.
    goto build_failed
)
echo APK: %FORGE_APK%
popd
exit /b 0

:missing_signing
echo ERROR: Release signing requires these environment variables:
echo MH_UPLOAD_STORE_FILE, MH_UPLOAD_STORE_PASSWORD, MH_UPLOAD_KEY_ALIAS, MH_UPLOAD_KEY_PASSWORD.
echo Configure them or restore .cache\alpha-signing\release-signing.bat.
goto build_failed

:build_failed
popd
exit /b 1

:usage_error
echo ERROR: Supported arguments are debug, release and --help.
exit /b 2

:help
echo Usage: build_apk.bat [debug or release]
echo Default: signed online debug APK; no PowerShell is invoked.
echo Release: build_apk.bat release, using MH_UPLOAD_* or the local alpha signing configuration.
echo Requires JDK 17, Android SDK, NDK 27.2.12479018, CMake 3.22.1 and prepared native tools.
echo Configure the SDK in mobile\local.properties or ANDROID_HOME.
exit /b 0

:load_release_signing
if defined MH_UPLOAD_STORE_FILE if defined MH_UPLOAD_STORE_PASSWORD if defined MH_UPLOAD_KEY_ALIAS if defined MH_UPLOAD_KEY_PASSWORD exit /b 0
if exist "%~dp0.cache\alpha-signing\release-signing.bat" call "%~dp0.cache\alpha-signing\release-signing.bat"
exit /b 0

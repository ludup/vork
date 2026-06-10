@echo off
setlocal EnableDelayedExpansion
:: ============================================================================
::  Vork Windows Installer Build Script
::  Produces: out\vork-<version>-setup.exe  (Burn bootstrapper)
::            out\vork-<version>.msi        (standalone MSI)
::
::  Prerequisites (must be on PATH):
::    dotnet  — .NET SDK  (https://dotnet.microsoft.com/download)
::    mvn     — Apache Maven (must already be able to build the project)
::    curl    — ships with Windows 10 1803+ and Windows Server 2019+
::
::  WiX toolset version:
::    This script targets WiX v7 (https://wixtoolset.org), released April 2026.
::    It installs the wix dotnet global tool automatically on first run.
::    Update WIX_TOOL_VERSION if a newer patch release is available.
::
::  Usage:
::    build.bat [version]          e.g.  build.bat 1.0.0
::    build.bat 1.0.0 --no-maven   skip mvn package (use existing target\)
::
::  Output:
::    src\wix\out\vork-<version>-setup.exe    — installer for end users
::    src\wix\out\vork-<version>.msi          — standalone MSI (no JDK bundled)
:: ============================================================================

:: ── Version ──────────────────────────────────────────────────────────────────
set VERSION=%~1
if "%VERSION%"=="" set VERSION=1.0.0
:: MSI version must be X.Y.Z.W with all-numeric parts
set MSI_VERSION=%VERSION%.0

:: ── Paths ────────────────────────────────────────────────────────────────────
set SCRIPT_DIR=%~dp0
:: Strip trailing backslash
set SCRIPT_DIR=%SCRIPT_DIR:~0,-1%
set PROJECT_ROOT=%SCRIPT_DIR%\..\..\..
set ARTIFACT_DIR=%PROJECT_ROOT%\target
set DOWNLOADS=%SCRIPT_DIR%\.downloads
set OUTPUT_DIR=%SCRIPT_DIR%\out

:: ── WiX tool version ─────────────────────────────────────────────────────────
set WIX_TOOL_VERSION=7.0.0

:: ── WinSW (Windows Service Wrapper) ─────────────────────────────────────────
::  https://github.com/winsw/winsw/releases
set WINSW_VERSION=2.12.0
set WINSW_EXE=WinSW-x64.exe
set WINSW_URL=https://github.com/winsw/winsw/releases/download/v%WINSW_VERSION%/%WINSW_EXE%

:: ── Eclipse Temurin JDK 25 LTS (x64 MSI) ──────────────────────────────────────────
::  https://adoptium.net/releases.html?variant=openjdk25
::  Vork needs a full JDK (not JRE) — javax.tools.JavaCompiler is used at runtime.
set JDK_TAG=jdk-25.0.3+9
set JDK_TAG_URL=jdk-25.0.3%%2B9
set JDK_MSI=temurin-25-jdk-x64.msi
set JDK_MSI_ORIG=OpenJDK25U-jdk_x64_windows_hotspot_25.0.3_9.msi
set JDK_URL=https://github.com/adoptium/temurin25-binaries/releases/download/%JDK_TAG_URL%/%JDK_MSI_ORIG%

:: ── Parse flags ──────────────────────────────────────────────────────────────
set SKIP_MAVEN=0
:parse_args
if "%~2"=="--no-maven" set SKIP_MAVEN=1
if "%~3"=="--no-maven" set SKIP_MAVEN=1

:: ═════════════════════════════════════════════════════════════════════════════
echo.
echo  Vork Installer Build  ^|  version %VERSION%
echo  ════════════════════════════════════════════
echo.

:: ── [1/6] Create output directories ─────────────────────────────────────────
echo [1/6] Creating output directories...
if not exist "%DOWNLOADS%" mkdir "%DOWNLOADS%"
if not exist "%OUTPUT_DIR%"  mkdir "%OUTPUT_DIR%"

:: ── [2/6] Build the JAR ──────────────────────────────────────────────────────
if "%SKIP_MAVEN%"=="1" (
    echo [2/6] Skipping Maven build ^(--no-maven^).
) else (
    echo [2/6] Building JAR  ^(mvn package -DskipTests^)...
    pushd "%PROJECT_ROOT%"
    call mvn package -DskipTests -q
    if errorlevel 1 (
        echo.
        echo  ERROR: Maven build failed.  Fix compilation errors and retry.
        exit /b 1
    )
    popd
    echo        Done.
)

:: Normalise the JAR name to vork.jar so Product.wxs has a stable source path.
echo        Copying JAR to stable name...
if not exist "%ARTIFACT_DIR%\vork-%VERSION%-SNAPSHOT.jar" (
    :: Try without SNAPSHOT qualifier (release build)
    if not exist "%ARTIFACT_DIR%\vork-%VERSION%.jar" (
        echo  ERROR: Cannot find vork JAR in %ARTIFACT_DIR%\
        echo         Expected: vork-%VERSION%-SNAPSHOT.jar or vork-%VERSION%.jar
        exit /b 1
    )
    copy /Y "%ARTIFACT_DIR%\vork-%VERSION%.jar" "%ARTIFACT_DIR%\vork.jar" >nul
) else (
    copy /Y "%ARTIFACT_DIR%\vork-%VERSION%-SNAPSHOT.jar" "%ARTIFACT_DIR%\vork.jar" >nul
)

:: ── [3/6] Download WinSW ─────────────────────────────────────────────────────
echo [3/6] WinSW %WINSW_VERSION%...
if exist "%DOWNLOADS%\%WINSW_EXE%" (
    echo        Already present, skipping download.
) else (
    echo        Downloading from GitHub...
    curl -fsSL -o "%DOWNLOADS%\%WINSW_EXE%" "%WINSW_URL%"
    if errorlevel 1 (
        echo  ERROR: WinSW download failed.  Check network and retry.
        exit /b 1
    )
    echo        Done.
)

:: ── [4/6] Download Eclipse Temurin JDK 25 MSI ───────────────────────────────
echo [4/6] Eclipse Temurin JDK 25 ^(x64^)...
if exist "%DOWNLOADS%\%JDK_MSI%" (
    echo        Already present, skipping download.
) else (
    echo        Downloading ~200 MB from GitHub...
    curl -fsSL -o "%DOWNLOADS%\%JDK_MSI%" "%JDK_URL%"
    if errorlevel 1 (
        echo  ERROR: JDK download failed.  Check network and retry.
        exit /b 1
    )
    echo        Done.
)

:: ── [5/6] Install / update WiX dotnet global tool and extensions ─────────────
echo [5/6] WiX toolset...
dotnet tool list --global 2>nul | findstr /i "wix" >nul
if errorlevel 1 (
    echo        Installing wix %WIX_TOOL_VERSION% global tool...
    dotnet tool install --global wix --version %WIX_TOOL_VERSION%
    if errorlevel 1 ( echo  ERROR: WiX install failed. & exit /b 1 )
) else (
    echo        wix tool already installed.
)
wix extension add WixToolset.Util.wixext@%WIX_TOOL_VERSION% --global 2>nul
wix extension add WixToolset.Bal.wixext@%WIX_TOOL_VERSION%  --global 2>nul
:: Accept the OSMF EULA for WiX v7 (EULA ID: wix7, covers the v1.1 terms).
:: This is the per-user acceptance; the -acceptEula flag on each build command
:: below also accepts it explicitly for CI/CD environments.
wix eula accept wix7 2>nul
echo        Done.

:: ── [6/6] Build MSI then Bundle ──────────────────────────────────────────────
echo [6/6] Building installer...

set MSI_OUT=%OUTPUT_DIR%\vork-%VERSION%.msi
set EXE_OUT=%OUTPUT_DIR%\vork-%VERSION%-setup.exe

:: Build the MSI (no JDK — just the Vork application + service)
echo        Building MSI...
wix build "%SCRIPT_DIR%\Product.wxs"      ^
    -acceptEula wix7                      ^
    -d Version=%MSI_VERSION%              ^
    -d ArtifactDir=%ARTIFACT_DIR%         ^
    -d WinSWDir=%DOWNLOADS%              ^
    -d SrcDir=%SCRIPT_DIR%               ^
    -ext WixToolset.Util.wixext@%WIX_TOOL_VERSION% ^
    -o "%MSI_OUT%"

if errorlevel 1 (
    echo  ERROR: MSI build failed.
    exit /b 1
)
echo        MSI: %MSI_OUT%

:: Build the Bundle (bootstrapper EXE: JDK prereq + Vork MSI)
echo        Building Bundle...
wix build "%SCRIPT_DIR%\Bundle.wxs"       ^
    -acceptEula wix7                      ^
    -d Version=%MSI_VERSION%              ^
    -d MsiPath=%MSI_OUT%                  ^
    -d JdkMsiPath=%DOWNLOADS%\%JDK_MSI%  ^
    -ext WixToolset.Bal.wixext@%WIX_TOOL_VERSION%  ^
    -ext WixToolset.Util.wixext@%WIX_TOOL_VERSION% ^
    -o "%EXE_OUT%"

if errorlevel 1 (
    echo  ERROR: Bundle build failed.
    exit /b 1
)
echo        EXE: %EXE_OUT%

:: ── Done ─────────────────────────────────────────────────────────────────────
echo.
echo  ═══════════════════════════════════════════════════
echo   Build complete
echo   Installer  :  %EXE_OUT%
echo   MSI only   :  %MSI_OUT%
echo  ═══════════════════════════════════════════════════
echo.
echo   The setup.exe will:
echo     1. Detect any existing JDK 25 (Temurin / MSFT / Oracle^)
echo     2. Install Eclipse Temurin JDK 25 if none found
echo     3. Install Vork to C:\Program Files\Vork\
echo     4. Register and start the VorkService Windows service
echo.
echo   After install, the web UI is available at:  https://localhost:8443/
echo   Configuration:  C:\Program Files\Vork\conf.d\database.properties
echo   Service logs:   C:\Program Files\Vork\logs\
echo.
endlocal

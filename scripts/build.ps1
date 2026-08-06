<#
.SYNOPSIS
    Automated build script for RigelMCMod (Windows/PowerShell equivalent of
    scripts/build.sh). Thin wrapper around the Gradle build documented in README.md's
    "Building" section.

.PARAMETER SkipTests
    Skip unit tests, Checkstyle, and SpotBugs - just compile and package.

.PARAMETER Install
    Path to a server's plugins/ folder to copy the built jar into after a successful
    build.

.EXAMPLE
    scripts\build.ps1
.EXAMPLE
    scripts\build.ps1 -SkipTests
.EXAMPLE
    scripts\build.ps1 -Install C:\mc-server\plugins
#>
param(
    [switch]$SkipTests,
    [string]$Install
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$GradleArgs = @("clean", "build")
if ($SkipTests) {
    $GradleArgs += @("-x", "test", "-x", "checkstyleMain", "-x", "checkstyleTest", "-x", "spotbugsMain", "-x", "spotbugsTest")
    Write-Host "==> Building RigelMCMod (tests/Checkstyle/SpotBugs skipped)..."
} else {
    Write-Host "==> Building RigelMCMod (compile, test, Checkstyle, SpotBugs, package)..."
}

& "$RepoRoot\gradlew.bat" @GradleArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "error: Gradle build failed (exit code $LASTEXITCODE)." -ForegroundColor Red
    Write-Host ""
    Write-Host "If the output above mentions a timeout downloading services.gradle.org," -ForegroundColor Yellow
    Write-Host "that's the wrapper's own one-time bootstrap download, not a problem with" -ForegroundColor Yellow
    Write-Host "this project. Things to try:" -ForegroundColor Yellow
    Write-Host "  1. Just retry - transient network blips happen." -ForegroundColor Yellow
    Write-Host "  2. Test connectivity to that host directly:" -ForegroundColor Yellow
    Write-Host "       curl.exe -I https://services.gradle.org/distributions/gradle-9.6.1-bin.zip" -ForegroundColor Yellow
    Write-Host "  3. If you have Gradle installed separately, bypass the wrapper entirely:" -ForegroundColor Yellow
    Write-Host "       gradle $($GradleArgs -join ' ')" -ForegroundColor Yellow
    Write-Host "  4. If you're behind a proxy, point the wrapper at it:" -ForegroundColor Yellow
    Write-Host '       $env:GRADLE_OPTS = "-Dhttps.proxyHost=<host> -Dhttps.proxyPort=<port>"' -ForegroundColor Yellow
    exit $LASTEXITCODE
}

$JarPath = Get-ChildItem -Path "$RepoRoot\plugin\build\libs" -Filter "RigelMCMod-*.jar" -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $JarPath) {
    Write-Error "Build succeeded but no RigelMCMod-*.jar was found in plugin\build\libs\"
    exit 1
}

Write-Host "==> Build succeeded: $JarPath"

if ($Install) {
    New-Item -ItemType Directory -Force -Path $Install | Out-Null
    Copy-Item -Path $JarPath -Destination $Install -Force
    $DestPath = Join-Path $Install (Split-Path -Leaf $JarPath)
    Write-Host "==> Installed to $DestPath"
    Write-Host "    Restart (or /reload, though a full restart is safer) your test server to pick it up."
}

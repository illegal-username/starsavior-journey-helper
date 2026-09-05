[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatabasePath,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$VersionCode,

    [string]$VersionName = ""
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$resolvedDatabase = (Resolve-Path -LiteralPath $DatabasePath).Path
$gradleWrapper = Join-Path $repositoryRoot ".gradle\gradlew-codex.cmd"

$gradleArguments = @(
    "--no-daemon",
    "-PbundledTestDatabase=$resolvedDatabase",
    "-PbundledTestVersionCode=$VersionCode"
)
if (-not [string]::IsNullOrWhiteSpace($VersionName)) {
    $gradleArguments += "-PbundledTestVersionName=$VersionName"
}
$gradleArguments += @("testInternalUnitTest", "lintInternal", "assembleInternal")

Push-Location $repositoryRoot
try {
    & $gradleWrapper @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Bundled DB test APK build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

$apk = Join-Path $repositoryRoot "app\build\outputs\apk\internal\app-internal.apk"
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "Bundled DB test APK was not produced at the expected path: $apk"
}

Write-Output $apk

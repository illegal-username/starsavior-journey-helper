param(
    [string]$CorpusRoot,
    [string]$ReportPath,
    [string]$ExpectationsPath
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$privateRoot = Join-Path (Split-Path -Parent $projectRoot) 'starsavior-journey-helper-private'
if ([string]::IsNullOrWhiteSpace($CorpusRoot)) {
    $CorpusRoot = Join-Path $privateRoot 'stamina-regression-samples'
}
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $ReportPath = Join-Path $CorpusRoot "reports\stamina-regression-$stamp.tsv"
}
if ([string]::IsNullOrWhiteSpace($ExpectationsPath)) {
    $ExpectationsPath = Join-Path $CorpusRoot 'expectations.tsv'
}

$javaHome = Join-Path $projectRoot '.gradle\codex-tools\jdk-17'
$ffmpeg = 'C:\ffmpeg\bin\ffmpeg.exe'
$ffprobe = 'C:\ffmpeg\bin\ffprobe.exe'
$classes = Join-Path $projectRoot '.gradle\codex-analysis\stamina-regression\classes'
$detector = Join-Path $projectRoot 'app\src\main\java\helper\journey\starsavior\StaminaGaugeDetector.java'
$runner = Join-Path $projectRoot 'tools\stamina-regression\StaminaRegressionCli.java'

foreach ($requiredFile in @(
    (Join-Path $javaHome 'bin\javac.exe'),
    (Join-Path $javaHome 'bin\java.exe'),
    $ffmpeg,
    $ffprobe,
    $detector,
    $runner,
    $ExpectationsPath
)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
    }
}
if (-not (Test-Path -LiteralPath $CorpusRoot -PathType Container)) {
    throw "Regression corpus is missing: $CorpusRoot"
}

New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Join-Path $javaHome 'bin\javac.exe') -encoding UTF-8 -d $classes $detector $runner
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $javaHome 'bin\java.exe') -cp $classes `
    helper.journey.starsavior.StaminaRegressionCli `
    $ffmpeg $ffprobe $CorpusRoot $ReportPath $ExpectationsPath
exit $LASTEXITCODE

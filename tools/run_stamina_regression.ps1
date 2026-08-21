param(
    [string]$CorpusRoot,
    [string]$ReportPath,
    [string]$ExpectationsPath,
    [string]$AnnotationsPath,
    [string]$FfmpegPath,
    [string]$FfprobePath,
    [switch]$AnnotationsOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($CorpusRoot)) {
    $CorpusRoot = $env:STAR_JOURNEY_STAMINA_CORPUS
}
if ([string]::IsNullOrWhiteSpace($CorpusRoot)) {
    throw 'Supply -CorpusRoot or set STAR_JOURNEY_STAMINA_CORPUS.'
}
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $ReportPath = Join-Path $CorpusRoot "reports\stamina-regression-$stamp.tsv"
}
if ([string]::IsNullOrWhiteSpace($ExpectationsPath)) {
    $ExpectationsPath = Join-Path $CorpusRoot 'expectations.tsv'
}
if ([string]::IsNullOrWhiteSpace($AnnotationsPath)) {
    $AnnotationsPath = Join-Path $CorpusRoot 'annotated'
}

$javaHome = Join-Path $projectRoot '.gradle\codex-tools\jdk-17'
$ffmpeg = if ([string]::IsNullOrWhiteSpace($FfmpegPath)) {
    (Get-Command ffmpeg -ErrorAction Stop).Source
} else {
    $FfmpegPath
}
$ffprobe = if ([string]::IsNullOrWhiteSpace($FfprobePath)) {
    (Get-Command ffprobe -ErrorAction Stop).Source
} else {
    $FfprobePath
}
$classes = Join-Path $projectRoot '.gradle\codex-analysis\stamina-regression\classes'
$detector = Join-Path $projectRoot 'app\src\main\java\helper\journey\starsavior\StaminaGaugeDetector.java'
$runner = Join-Path $projectRoot 'tools\stamina-regression\StaminaRegressionCli.java'

foreach ($requiredFile in @(
    (Join-Path $javaHome 'bin\javac.exe'),
    (Join-Path $javaHome 'bin\java.exe'),
    $ffmpeg,
    $ffprobe,
    $detector,
    $runner
)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
    }
}
if (-not (Test-Path -LiteralPath $CorpusRoot -PathType Container)) {
    throw "Regression corpus is missing: $CorpusRoot"
}
if (-not $AnnotationsOnly -and -not (Test-Path -LiteralPath $ExpectationsPath -PathType Leaf)) {
    throw "Required file is missing: $ExpectationsPath"
}
if (-not (Test-Path -LiteralPath $AnnotationsPath)) {
    throw "Annotated ground truth is missing: $AnnotationsPath"
}

New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Join-Path $javaHome 'bin\javac.exe') -encoding UTF-8 -d $classes $detector $runner
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$expectationArgument = if ($AnnotationsOnly) { '-' } else { $ExpectationsPath }
& (Join-Path $javaHome 'bin\java.exe') -cp $classes `
    helper.journey.starsavior.StaminaRegressionCli `
    $ffmpeg $ffprobe $CorpusRoot $ReportPath $expectationArgument $AnnotationsPath
exit $LASTEXITCODE

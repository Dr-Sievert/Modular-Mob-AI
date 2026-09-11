# Measures a trained network: fights on its most likely actions, no exploration, nothing recorded for training.
#
#   scripts\eval.ps1                          the newest weights of run 'default', on natural terrain
#   scripts\eval.ps1 -Run wide -Iteration 400
#   scripts\eval.ps1 -Arenas 1000 -Suite arena
#   scripts\eval.ps1 -ReplayEvery 10          replays of one fight in ten per worker, in runs\eval-<run>-<iteration>\replays

param(
    [string] $Run = 'default',
    [int] $Iteration = -1,
    [int] $Arenas = 200,
    [int] $Workers = 2,
    [ValidateSet('terrain', 'arena')] [string] $Suite = 'terrain',
    [int] $ReplayEvery = 0
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

$weights = Join-Path (Get-RunDirectory $Run) 'weights'

if ($Iteration -ge 0) {

    $file = Join-Path $weights ('{0:D6}.mbw' -f $Iteration)
}

else {

    $file = (Get-ChildItem $weights -Filter '*.mbw' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1).FullName
}

if (-not $file -or -not (Test-Path $file)) {

    throw "No weights found in $weights"
}

Write-Host "Evaluating $file over $Arenas arenas"

$replayRun = 'eval-{0}-{1}' -f $Run, [IO.Path]::GetFileNameWithoutExtension($file)

Invoke-Gradle @(':fabric:runGametestParallel', '-Pbrain=neural', "-PbrainWeights=$file", "-Parenas=$Arenas", "-Pworkers=$Workers", "-Psuite=$Suite",
        "-PreplayEvery=$ReplayEvery", "-PreplayRun=$replayRun")

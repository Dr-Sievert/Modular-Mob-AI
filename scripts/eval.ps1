# Measures a trained network: fights on its most likely actions, no exploration, nothing recorded for training.
#
#   scripts\eval.ps1                          the newest weights of run 'default', 2,000 fights on natural terrain
#   scripts\eval.ps1 -Run wide -Iteration 400
#   scripts\eval.ps1 -Arenas 400 -Suite arena
#
# Starting the workers costs the same however many fights follow, and 2,000 fights put the win rate within about a
# point either way, where 400 leave it within two and a half.
#   scripts\eval.ps1 -ReplayEvery 10          replays of one fight in ten per worker, in runs\eval-<run>-<iteration>\replays

param(
    [string] $Run = 'default',
    [int] $Iteration = -1,
    [int] $Arenas = 2000,
    [int] $Workers = 8,
    [int] $Slots = 25,
    [string] $Heap = '1G',
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

Invoke-Gradle @(':fabric:runGametestParallel', '-Pbrain=neural', "-PbrainWeights=$file", "-Parenas=$Arenas", "-Pworkers=$Workers",
        "-PbatchSize=$Slots", "-PworkerHeap=$Heap", "-Psuite=$Suite", "-PreplayEvery=$ReplayEvery", "-PreplayRun=$replayRun")

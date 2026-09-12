# Measures a trained network: fights on its most likely actions, no exploration, nothing recorded for training.
#
#   scripts\eval.ps1                          the newest weights of run 'default', 2,000 fights on natural terrain
#   scripts\eval.ps1 -Run wide -Iteration 400
#   scripts\eval.ps1 -Arenas 400 -Suite arena
#   scripts\eval.ps1 -Weights models\vs-copy\best.mbw   a network from anywhere, such as one scripts\publish.ps1 put in git
#   scripts\eval.ps1 -Run league -Suite league   round every league opponent in turn, a frozen copy of itself included,
#                                               and a table of how it went against each at the end
#   scripts\eval.ps1 -Suite league -Loadouts bow,crossbow   those loadouts alone, to measure one weapon rather than wait
#                                                           for it to come round in the rotation
#
# Two evaluations of the same weights already fight the same sites in the same order, so two builds compared this way are
# compared on the same ground without being asked to be. -Ground asks for a different sample of the library instead, which
# is how to check that a difference is the change and not the ground it was measured on:
#
#   scripts\eval.ps1 -Weights models\league2\best.mbw -Suite league -Ground 7
#
# Starting the workers costs the same however many fights follow, and 2,000 fights put the win rate within about a
# point either way, where 400 leave it within two and a half. What is left over when the ground repeats is the mobs and the
# dice: four runs of one network over 300 fights measured 44.7, 44.7, 45.7 and 46.3 per cent.
#   scripts\eval.ps1 -ReplayEvery 10          replays of one fight in ten per worker, in runs\eval-<run>-<iteration>\replays

param(
    [string] $Run = 'default',
    [int] $Iteration = -1,
    [string] $Weights = '',
    [int] $Arenas = 2000,
    [int] $Workers = 8,
    [int] $Slots = 25,
    [string] $Heap = '1280M',
    [ValidateSet('terrain', 'arena', 'league')] [string] $Suite = 'terrain',
    [int] $ReplayEvery = 0,

    # Which loadouts to fight with, empty for all of them. The way to measure one weapon on its own: a run whose bow rows
    # look weak can be put on bow and crossbow alone rather than waiting for them to come round in the rotation.
    [string[]] $Loadouts = @(),

    # Which sites to fight on, as a seed. An evaluation already fights the same sites in the same order every time it is
    # run, so this is not here to steady anything: measured, the same weights over 300 fights gave 134 wins and then 134
    # again with no seed, and 137 and 139 with one. It is here to ask the same question of a *different* sample of the
    # library, which is how to tell a real difference from one sample's worth of ground.
    [int] $Ground = 0
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

# PowerShell names are not case sensitive, so the run's weight folder cannot be called $weights beside -Weights.
$folder = Join-Path (Get-RunDirectory $Run) 'weights'

if ($Weights) {

    $file = (Resolve-Path $Weights -ErrorAction SilentlyContinue).Path

    # Named after the folder it came from, so models\vs-copy\best.mbw gives replays in runs\eval-vs-copy-best.
    $Run = Split-Path (Split-Path $file -Parent) -Leaf
}

elseif ($Iteration -ge 0) {

    $file = Join-Path $folder ('{0:D6}.mbw' -f $Iteration)
}

else {

    $file = (Get-ChildItem $folder -Filter '*.mbw' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1).FullName
}

if (-not $file -or -not (Test-Path $file)) {

    throw "No weights found at $(if ($Weights) { $Weights } else { $folder })"
}

Write-Host ("Evaluating $file over $Arenas arenas" +
        $(if ($Loadouts.Count -gt 0) { ", with the $($Loadouts -join ', ') loadouts alone" }) +
        $(if ($Ground -ne 0) { ", on the ground seed $Ground" }))

$replayRun = 'eval-{0}-{1}' -f $Run, [IO.Path]::GetFileNameWithoutExtension($file)

Invoke-Gradle (@(':fabric:runGametestParallel', '-Pbrain=neural', "-PbrainWeights=$file", "-Parenas=$Arenas", "-Pworkers=$Workers",
        "-PbatchSize=$Slots", "-PworkerHeap=$Heap", "-Psuite=$Suite", "-PreplayEvery=$ReplayEvery", "-PreplayRun=$replayRun") +
        @(if ($Loadouts.Count -gt 0) { "-PleagueLoadouts=$($Loadouts -join ',')" }) +
        @(if ($Ground -ne 0) { "-PterrainSeed=$Ground" }))

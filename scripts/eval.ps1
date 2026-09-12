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
#   scripts\eval.ps1 -Suite league -Opponents ravager -ReplayEvery 1   one matchup, every fight of it written down: what
#                                                           the league page offers when a row has no recorded fight to show
#
# Two evaluations do NOT fight the same sites: without -Ground a worker starts somewhere random in the terrain library
# (TerrainSites.startLibrary, "anywhere otherwise"), so the ground under a comparison is a fresh sample every time. It is
# enough fights that makes the answer repeat, not repeated ground. Measured on the scripted fighter, 600 league fights, one
# worker, back to back: 79.00% and then 78.97%, on ground that was nothing like the same (236 flat sites against 91, 174
# lava against 70, none of the water in one run and 159 in the other). -Ground fixes where each worker starts, for asking
# the same question of a named sample rather than an arbitrary one:
#
#   scripts\eval.ps1 -Weights models\league2\best.mbw -Suite league -Ground 7
#
# **A comparison wants -Workers 1, and the same count either way.** On the league the fights are spread evenly over the
# opponents *per worker*, and workers get through uneven shares of the total, so several workers leave the opponent mix
# skewed by whichever of them happened to run fastest -- and an opponent is worth anything from 0% (a warden) to 100% (a
# wolf), which is a far bigger lever than terrain. Three workers measured the same fighter at 77.8% and then 83.0%, where
# one worker repeated to three hundredths of a point. scripts\bench.ps1 uses one worker for exactly this reason.
#
# Starting the workers costs the same however many fights follow, and 2,000 fights put the win rate within about a
# point either way, where 400 leave it within two and a half.
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

    # Which opponents to fight, empty for all of them, by the names the league writes in its results: ravager,
    # 2x_zombie, zombie(hard). The way to look at one matchup: with -ReplayEvery 1 a short evaluation records every fight
    # of it, which is how the league page answers a row whose fights were never written down.
    [string[]] $Opponents = @(),

    # Which sites to fight on, as a seed. An evaluation already fights the same sites in the same order every time it is
    # run, so this is not here to steady anything: measured, the same weights over 300 fights gave 134 wins and then 134
    # again with no seed, and 137 and 139 with one. It is here to ask the same question of a *different* sample of the
    # library, which is how to tell a real difference from one sample's worth of ground.
    [int] $Ground = 0,

    # The scripted fighter instead of a network, which is the reference every other number here wants. A copy that wins 27%
    # of the league says nothing on its own: the roster holds wardens and evokers, and the question is always how much of
    # what is missing is the copy and how much is the fight. Measured the same way, on the same sites, in the same order.
    [switch] $Teacher
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

# PowerShell names are not case sensitive, so the run's weight folder cannot be called $weights beside -Weights.
$folder = Join-Path (Get-RunDirectory $Run) 'weights'

if ($Teacher) {

    # Nothing to find: the scripted fighter is in the build.
    $file = ''
    $Run = 'teacher'
}

elseif ($Weights) {

    $file = (Resolve-Path $Weights -ErrorAction SilentlyContinue).Path

    # Said here rather than left to Split-Path, which answers a missing file with a page of PowerShell. A run keeps only its
    # last few weight files, so naming one by hand is a race worth losing politely.
    if (-not $file) {

        throw "No weights at $Weights. A run keeps only its most recent few, so copy one aside before evaluating it."
    }

    # Named after the folder it came from, so models\vs-copy\best.mbw gives replays in runs\eval-vs-copy-best.
    $Run = Split-Path (Split-Path $file -Parent) -Leaf
}

elseif ($Iteration -ge 0) {

    $file = Join-Path $folder ('{0:D6}.mbw' -f $Iteration)
}

else {

    $file = (Get-ChildItem $folder -Filter '*.mbw' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1).FullName
}

if (-not $Teacher -and (-not $file -or -not (Test-Path $file))) {

    throw "No weights found at $(if ($Weights) { $Weights } else { $folder })"
}

Write-Host ("Evaluating $(if ($Teacher) { 'the scripted fighter' } else { $file }) over $Arenas arenas" +
        $(if ($Loadouts.Count -gt 0) { ", with the $($Loadouts -join ', ') loadouts alone" }) +
        $(if ($Opponents.Count -gt 0) { ", against $($Opponents -join ', ') alone" }) +
        $(if ($Ground -ne 0) { ", on the ground seed $Ground" }))

$replayRun = 'eval-{0}-{1}' -f $Run, $(if ($Teacher) { 'scripted' } else { [IO.Path]::GetFileNameWithoutExtension($file) })

Invoke-Gradle (@(':fabric:runGametestParallel', "-Pbrain=$(if ($Teacher) { 'scripted' } else { 'neural' })", "-Parenas=$Arenas",
        "-Pworkers=$Workers", "-PbatchSize=$Slots", "-PworkerHeap=$Heap", "-Psuite=$Suite", "-PreplayEvery=$ReplayEvery",
        "-PreplayRun=$replayRun") +
        @(if (-not $Teacher) { "-PbrainWeights=$file" }) +
        @(if ($Loadouts.Count -gt 0) { "-PleagueLoadouts=$($Loadouts -join ',')" }) +
        @(if ($Opponents.Count -gt 0) { "-PleagueOpponents=$($Opponents -join ',')" }) +
        @(if ($Ground -ne 0) { "-PterrainSeed=$Ground" }))

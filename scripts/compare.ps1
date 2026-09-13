# Two runs side by side, to see where each lands: one seeded from a copy of the scripted fighter (scripts\imitate.ps1), the
# other from nothing. Both then learn by reinforcement on the same ground at the same time, the seeded one on twice the
# workers by default since it is the one closer to done.
#
#   scripts\imitate.ps1 -Run league-copy -Suite league    make the copy first, over every opponent and every loadout
#   scripts\compare.ps1 -Copy league-copy                 then both arms on the league, runs vs-copy / vs-scratch
#   scripts\compare.ps1 -Copy league-copy -Battles 500000
#   scripts\compare.ps1 -Copy league-copy -CopyWorkers 4 -ScratchWorkers 4      an even split
#   scripts\compare.ps1 -Copy vindicator -Suite terrain   the one on one fight instead
#
# -Copy has to be named. It used to default to runs\vindicator4, which is a copy nothing on this machine has any more: it
# was trained against the humanoid's old 634-float observation and its weights load nowhere, so the default was a run that
# could only fail. What makes a copy today is scripts\imitate.ps1 -Suite league, and a league copy is what a league
# comparison wants -- a record of one fight against one vindicator teaches a league run nothing about a creeper.
#
# **This compares two lineages, not two networks.** For "which of these two networks is better" the answer is
# scripts\bench.ps1, which puts them on one bench in one sitting; two numbers from two sittings cannot be subtracted at all,
# and a run's own eval.csv is measured against opponents matchmaking keeps changing. See docs\findings.md.
#
# Both run in the background, so this returns once they have started. Watch both with scripts\watch.ps1, the fights
# themselves with scripts\viewer.ps1, and stop both with scripts\stop.ps1.
#
# The seeded run stops on its own once evaluation says it has stopped getting better. A run from nothing wins nothing for a
# long time, and "ten checkpoints without a new best" would end it before it had started, so it only stops at
# -ScratchBattles.

param(
    [Parameter(Mandatory = $true)] [string] $Copy,
    [string] $Prefix = 'vs',
    [ValidateSet('terrain', 'arena', 'league')] [string] $Suite = 'league',
    [int] $Battles = 0,
    [int] $ScratchBattles = 3000000,
    [int] $CopyWorkers = 6,
    [int] $ScratchWorkers = 3
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

$source = Get-RunDirectory $Copy

if (-not (Test-Path (Join-Path $source 'state.pt'))) {

    throw "No copy in $source; make one with scripts\imitate.ps1 -Run $Copy -Suite $Suite"
}

# Compiled once, here, so the two runs never compile the same classes at the same moment.
Invoke-Gradle @(':fabric:gametestClasses')

foreach ($name in "$Prefix-copy", "$Prefix-scratch") {

    if (Test-Path (Join-Path (Get-RunDirectory $name) 'state.pt')) {

        Write-Host "$(Get-RunDirectory $name) already has a run in it; carrying it on"
    }
}

function Start-Run([string] $Name, [int] $Workers, [string] $Arguments) {

    $directory = Get-RunDirectory $Name
    New-Item -ItemType Directory -Force $directory | Out-Null

    # Everything goes into the console log, for when something needs looking into; watch.ps1 is the view to follow it by.
    $command = "& '$PSScriptRoot\train.ps1' -Run $Name -Suite $Suite -Workers $Workers -Full $Arguments *> '$directory\console.log'"
    Start-Process powershell.exe -ArgumentList @('-NoProfile', '-Command', $command) -WindowStyle Hidden | Out-Null

    Write-Host "Started '$Name'; its build output goes to $directory\console.log"
}

# -Seed takes the copy's state and leaves the copy alone; -Demos names the record to pull towards rather than linking it in,
# which is what this script used to do. A junction under runs\ is one more thing for a recursive delete to follow, and that
# has cost this repository a trainer environment and half an hour of generated ground once already; see docs\findings.md.
Start-Run "$Prefix-copy" $CopyWorkers "-Seed $Copy -Demos $Copy -TeacherWeight 0.5 -Battles $Battles"

# The second waits for the first to get past its own checks, so they do not write the same files at the same moment.
Start-Sleep -Seconds 30

Start-Run "$Prefix-scratch" $ScratchWorkers "-Battles $ScratchBattles -Extra '--eval-patience 1000000'"

Write-Host ''
Write-Host 'Both are running in the background. Watch them with:'
Write-Host '  scripts\watch.ps1'
Write-Host '  scripts\viewer.ps1'
Write-Host 'Stop both with scripts\stop.ps1'

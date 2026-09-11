# Two runs side by side, to see where each lands: one starts from a copy of the scripted fighter, corrected until it
# fights nearly as well (scripts\imitate.ps1), the other from nothing. Both then learn by reinforcement on the same
# terrain, at the same time, on half the machine each.
#
#   scripts\compare.ps1                                  from the copy in runs\vindicator4, until each is done
#   scripts\compare.ps1 -Copy vindicator5 -Battles 500000
#
# Both run in the background, so this returns once they have started. Watch them in two terminals:
#   scripts\watch.ps1 -Run <prefix>-copy
#   scripts\watch.ps1 -Run <prefix>-scratch
# the fights themselves with scripts\viewer.ps1, and stop both with scripts\stop.ps1.
#
# The copy's run stops on its own once evaluation says it has stopped getting better. A run from nothing wins nothing
# for a long time, and "ten checkpoints without a new best" would end it before it had started, so it only stops at
# -ScratchBattles.

param(
    [string] $Copy = 'vindicator4',
    [string] $Prefix = 'vs',
    [int] $Battles = 0,
    [int] $ScratchBattles = 3000000,
    [int] $WorkersEach = 4
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

$source = Get-RunDirectory $Copy

if (-not (Test-Path (Join-Path $source 'weights\000000.mbw'))) {

    throw "No copy in $source; make one with scripts\imitate.ps1 -Run $Copy"
}

# Compiled once, here, so the two runs never compile the same classes at the same moment.
Invoke-Gradle @(':fabric:gametestClasses')

$fromCopy = Get-RunDirectory "$Prefix-copy"
$fromScratch = Get-RunDirectory "$Prefix-scratch"

foreach ($directory in $fromCopy, $fromScratch) {

    if (Test-Path (Join-Path $directory 'state.pt')) {

        Write-Host "$directory already has a run in it; carrying it on"
    }
}

# The copy's run starts as the copy: its state, its weights, and the teacher's record it is pulled back towards. The
# record is linked rather than copied, since it is gigabytes and only ever read.
if (-not (Test-Path (Join-Path $fromCopy 'state.pt'))) {

    New-Item -ItemType Directory -Force (Join-Path $fromCopy 'weights') | Out-Null
    Copy-Item (Join-Path $source 'state.pt'), (Join-Path $source 'schema.json') $fromCopy
    Copy-Item (Join-Path $source 'weights\000000.mbw') (Join-Path $fromCopy 'weights')
    New-Item -ItemType Junction -Path (Join-Path $fromCopy 'demos') -Target (Join-Path $source 'demos') | Out-Null
}

function Start-Run([string] $Name, [string] $Arguments) {

    $directory = Get-RunDirectory $Name
    New-Item -ItemType Directory -Force $directory | Out-Null

    # Everything goes into the console log, for when something needs looking into; watch.ps1 is the view to follow it by.
    $command = "& '$PSScriptRoot\train.ps1' -Run $Name -Workers $WorkersEach -Full $Arguments *> '$directory\console.log'"
    Start-Process powershell.exe -ArgumentList @('-NoProfile', '-Command', $command) -WindowStyle Hidden | Out-Null

    Write-Host "Started '$Name'; its build output goes to $directory\console.log"
}

Start-Run "$Prefix-copy" "-FromCopy -Battles $Battles"

# The second waits for the first to get past its own checks, so they do not write the same files at the same moment.
Start-Sleep -Seconds 30

Start-Run "$Prefix-scratch" "-Battles $ScratchBattles -Extra '--eval-patience 1000000'"

Write-Host ''
Write-Host 'Both are running in the background. Watch them with:'
Write-Host "  scripts\watch.ps1 -Run $Prefix-copy"
Write-Host "  scripts\watch.ps1 -Run $Prefix-scratch"
Write-Host '  scripts\viewer.ps1'
Write-Host 'Stop both with scripts\stop.ps1'

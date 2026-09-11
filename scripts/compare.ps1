# Two runs side by side, to see where each lands: 'random' starts from nothing, 'imitate' starts by copying the
# scripted fighter. Both then train the same way, on the same terrain, for the same number of battles.
#
#   scripts\compare.ps1                        10,000 battles each, two workers each
#   scripts\compare.ps1 -Battles 2000 -WorkersEach 3
#
# Both run in the background, so this returns once they have started. Watch them in two terminals:
#   scripts\watch.ps1 -Run random
#   scripts\watch.ps1 -Run imitate
# and stop both with scripts\stop.ps1.

param(
    [int] $Battles = 10000,
    [int] $WorkersEach = 2,
    [int] $Demonstrations = 1500,
    [string] $Heap = '2G'
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

# Compiled once, here, so the two runs never compile the same classes at the same moment.
Invoke-Gradle @(':fabric:gametestClasses')

$imitate = Get-RunDirectory 'imitate'

if (-not (Test-Path (Join-Path $imitate 'state.pt'))) {

    Write-Host "Recording the scripted fighter over $Demonstrations fights"
    Invoke-Gradle @(':fabric:recordDemonstrations', '-Prun=imitate', "-Parenas=$Demonstrations", "-Pworkers=$($WorkersEach * 2)", "-PworkerHeap=$Heap")

    Write-Host 'Teaching the network to copy it'
    & $Python (Join-Path $Root 'trainer\train.py') imitate --run $imitate

    if ($LASTEXITCODE -ne 0) {

        throw 'Imitation failed; see the lines above'
    }
}

function Start-Run([string] $Name, [string] $Extra) {

    $directory = Get-RunDirectory $Name
    New-Item -ItemType Directory -Force $directory | Out-Null

    $command = "& '$PSScriptRoot\train.ps1' -Run $Name -Battles $Battles -Workers $WorkersEach -Heap $Heap $Extra *> '$directory\console.log'"
    Start-Process powershell.exe -ArgumentList @('-NoProfile', '-Command', $command) -WindowStyle Hidden | Out-Null

    Write-Host "Started '$Name'; its build output goes to $directory\console.log"
}

Start-Run 'random' ''

# The second waits for the first to get past its own checks, so they do not write the same files at the same moment.
Start-Sleep -Seconds 30

# The copy only gets to learn once the critic has caught up with it; see critic_warmup in trainer/mmai/ppo.py.
Start-Run 'imitate' "-Extra '--critic-warmup 5'"

Write-Host ''
Write-Host 'Both are running in the background. Watch them with:'
Write-Host '  scripts\watch.ps1 -Run random'
Write-Host '  scripts\watch.ps1 -Run imitate'
Write-Host 'Stop both with scripts\stop.ps1'

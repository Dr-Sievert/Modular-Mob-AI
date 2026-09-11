# Trains the agents: one on one against a vindicator on natural terrain, as many workers as the machine holds.
# Watch it from a second terminal with scripts\watch.ps1. Resumes where the run left off.
#
#   scripts\train.ps1                           10,000 battles on run 'default', then stops
#   scripts\train.ps1 -Battles 0                until Ctrl+C
#   scripts\train.ps1 -Workers 2 -Device cpu    lighter on the machine
#   scripts\train.ps1 -Run wide -Extra '--entropy-coef 0.003'
#   scripts\train.ps1 -ReplayEvery 50           replays of more fights in runs\<run>\replays, 0 for none
#   scripts\compare.ps1                         two runs side by side instead, see there
#
# Learning happens every -RolloutSteps steps of experience across all workers (an iteration): the workers pause, the
# trainer runs a few epochs of PPO over exactly that experience, the new weights swap in, and the fights carry on. The
# battles are fought in rounds of -RoundSize, each with fresh worker processes, so a worker that crashes costs at most
# the rest of its round.
#
# Each worker fights -Slots battles at once, on a quarter again as many terrain sites, in a -Heap sized heap. A worker
# is bound by its one server thread, so the machine's memory, not its cores, decides how many run. Twenty five slots in
# a 1 GB heap measured the same throughput per worker as fifty in 2 GB, in 1.35 GB of memory rather than 2.5, so about
# twice as many workers fit.

param(
    [string] $Run = 'default',
    [int] $Battles = 10000,
    [int] $RoundSize = 10000,
    [int] $Workers = 0,
    [int] $Slots = 25,
    [string] $Heap = '1G',
    [int] $RolloutSteps = 16384,
    [ValidateSet('cuda', 'cpu')] [string] $Device = 'cuda',
    [ValidateSet('terrain', 'arena')] [string] $Suite = 'terrain',
    [int] $ReplayEvery = 200,
    [string] $Extra = ''
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

if (-not (Test-Path $Python)) {

    throw 'No trainer environment yet. Run scripts\setup.ps1 first.'
}

# Zero workers means as many as the cores and the free memory allow; the build works that out.
$workerArguments = if ($Workers -gt 0) { @("-Pworkers=$Workers", "-PmaxWorkers=$Workers") } else { @('-Pworkers=64', '-PmaxWorkers=16') }

$directory = Get-RunDirectory $Run
Write-Host "Training run '$Run' in $directory on $Suite, $(if ($Battles -gt 0) { "$Battles battles" } else { 'until stopped' }). Watch with: scripts\watch.ps1 -Run $Run"

Invoke-Gradle (@(
    ':fabric:runTraining',
    "-Prun=$Run",
    "-Psuite=$Suite",
    "-Pbattles=$Battles",
    "-Parenas=$RoundSize",
    '-Prounds=0',
    "-PworkerHeap=$Heap",
    "-PbatchSize=$Slots",
    "-ProlloutSteps=$RolloutSteps",
    "-PreplayEvery=$ReplayEvery",
    "-PtrainArgs=--device $Device $Extra".Trim()
) + $workerArguments)

# Trains the agents: one on one against a vindicator on natural terrain, as many workers as the machine holds.
# Watch it from a second terminal with scripts\watch.ps1. Resumes where the run left off.
#
#   scripts\train.ps1                           10,000 battles on run 'default', then stops
#   scripts\train.ps1 -Battles 0                until Ctrl+C
#   scripts\train.ps1 -Workers 2 -Device cpu    lighter on the machine
#   scripts\train.ps1 -Run wide -Extra '--entropy-coef 0.003'
#   scripts\train.ps1 -ReplayEvery 50           replays of more fights in runs\<run>\replays, 0 for none
#   scripts\train.ps1 -Run vindicator -FromCopy a run that starts from scripts\imitate.ps1's copy, see -FromCopy
#   scripts\compare.ps1                         two runs side by side instead, see there
#
# Every checkpoint is played by the workers on its most likely action, in one fight in ten, and the best so far is kept
# as runs\<run>\best.mbw, with the history in runs\<run>\eval.csv. The run stops on its own once evaluation says it has
# stopped getting better, or reached the target, or after -Battles, whichever comes first.
#
# Learning happens every -RolloutSteps steps of experience across all workers (an iteration): the workers pause, the
# trainer runs a few epochs of PPO over exactly that experience, the new weights swap in, and the fights carry on. The
# battles are fought in rounds of -RoundSize, each with fresh worker processes, so a worker that crashes costs at most
# the rest of its round. Starting workers takes minutes, so a round is large enough to make that a small share of it.
#
# Each worker fights -Slots battles at once, on a quarter again as many terrain sites, in a -Heap sized heap. A worker
# is bound by its one server thread, so the machine's memory, not its cores, decides how many run. Twenty five slots in
# a 1 GB heap measured the same throughput per worker as fifty in 2 GB, in 1.35 GB of memory rather than 2.5, so about
# twice as many workers fit.

param(
    [string] $Run = 'default',
    [int] $Battles = 10000,
    [int] $RoundSize = 50000,
    [int] $Workers = 0,
    [int] $Slots = 25,
    [string] $Heap = '1G',
    [int] $RolloutSteps = 16384,
    [ValidateSet('cuda', 'cpu')] [string] $Device = 'cuda',
    [ValidateSet('terrain', 'arena')] [string] $Suite = 'terrain',
    [int] $ReplayEvery = 200,

    # For a run that starts from a copy of the scripted fighter. Twice reinforcement learning made such a copy worse: a
    # policy near its best has little to gain from a critic that has not yet learned the fight, and much to lose. So it
    # learns from four times the experience per update, in smaller and more carefully bounded steps, lets the critic
    # learn alone for longer first, explores less, and is pulled back towards the teacher's own answers throughout.
    [switch] $FromCopy,

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

if ($FromCopy) {

    if (-not $PSBoundParameters.ContainsKey('RolloutSteps')) {

        $RolloutSteps = 65536
    }

    $Extra = "--learning-rate 5e-5 --clip 0.1 --target-kl 0.01 --critic-warmup 30 --entropy-coef 0.001 --teacher-weight 0.5 $Extra"
}

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

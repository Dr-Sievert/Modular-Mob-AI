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
# the rest of its round. Starting workers takes about half a minute and a worker fights some seventy battles a second, so
# a round is large enough to make that a small share of it.
#
# Each worker fights -Slots battles at once, on a quarter again as many terrain sites, in a -Heap sized heap. A worker
# is bound by its one server thread, so the machine's memory, not its cores, decides how many run. Twenty five slots
# measured the same throughput per worker as fifty, in half the memory. Most of a heap is the ground around the sites in
# use, generated part way so that the sites could be; as sites move on it settles at about 0.95 GB, which a 1 GB heap
# only held by collecting garbage without end, so the heap is 1.5 GB.

param(
    [string] $Run = 'default',
    [int] $Battles = 10000,
    [int] $RoundSize = 250000,
    [int] $Workers = 0,
    [int] $Slots = 25,
    [string] $Heap = '1536M',
    [int] $RolloutSteps = 16384,
    [ValidateSet('cuda', 'cpu')] [string] $Device = 'cuda',
    [ValidateSet('terrain', 'arena')] [string] $Suite = 'terrain',
    [int] $ReplayEvery = 200,

    # For a run that starts from a copy of the scripted fighter. Twice reinforcement learning made such a copy worse: a
    # policy near its best has little to gain from a critic that has not yet learned the fight, and much to lose. So it
    # learns from four times the experience per update, in smaller and more carefully bounded steps, lets the critic
    # learn alone for longer first, explores less, and is pulled back towards the teacher's own answers throughout.
    [switch] $FromCopy,

    # Everything the build and both sides of it say, rather than the short feed: a line every few iterations with the
    # training win rate and pace, every evaluation, every round, and anything that went wrong.
    [switch] $Full,

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

if ($FromCopy) {

    if (-not $PSBoundParameters.ContainsKey('RolloutSteps')) {

        $RolloutSteps = 65536
    }

    # A copy's run stops on its own once evaluation says it has stopped getting better, so by default it runs until then.
    if (-not $PSBoundParameters.ContainsKey('Battles')) {

        $Battles = 0
    }

    $Extra = "--learning-rate 5e-5 --clip 0.1 --target-kl 0.01 --critic-warmup 30 --entropy-coef 0.001 --teacher-weight 0.5 $Extra"
}

Write-Host "Training run '$Run' in $directory on $Suite, $(if ($Battles -gt 0) { "$Battles battles" } else { 'until evaluation says it is done, or stopped' }). Watch with: scripts\watch.ps1 -Run $Run"

$started = Get-Date

# What the iterations since the last line of the feed added up to.
$feed = @{ Printed = $started; Steps = -1L; Fights = 0; Wins = 0.0; Ticks = 0.0 }

# The short feed: of all the build prints, only what says how the run is going. A line every half minute with the pace
# and the training win rate over it, evaluations and new bests, rounds, the end, and anything that went wrong.
function Format-Feed([string] $Line) {

    $now = Get-Date
    $clock = '{0:hh\:mm\:ss}' -f ($now - $started)

    if ($Line -match 'iteration +(\d+) +steps +([\d,]+) +episodes +(\d+) +win +([\d.]+)%.*length +([\d.]+)') {

        $steps = [long]($Matches[2] -replace ',', '')
        $fights = [int]$Matches[3]

        if ($feed.Steps -lt 0) {

            $feed.Steps = $steps
            $feed.Printed = $now
            return $null
        }

        $feed.Fights += $fights
        $feed.Wins += $fights * [double]$Matches[4] / 100.0
        $feed.Ticks += $fights * [double]$Matches[5]

        $seconds = ($now - $feed.Printed).TotalSeconds

        if ($seconds -lt 30) {

            return $null
        }

        $text = '{0}  iteration {1,6}  {2,7:N0} ticks/s  {3,6:N1} fights/s  training win {4,5:N1}%  fights {5,4:N0} ticks long' -f $clock,
                $Matches[1], (($steps - $feed.Steps) / $seconds), ($feed.Fights / $seconds), (100.0 * $feed.Wins / [Math]::Max(1, $feed.Fights)),
                ($feed.Ticks / [Math]::Max(1, $feed.Fights))

        $feed.Printed = $now
        $feed.Steps = $steps
        $feed.Fights = 0
        $feed.Wins = 0.0
        $feed.Ticks = 0.0

        return $text
    }

    if ($Line -match 'mmai\.eval\s+(.*)$' -or $Line -match 'mmai\.train\s+(done: .*)$') {

        return "$clock  $($Matches[1])"
    }

    if ($Line -match '^(=====.*=====|Round \d+:.*|Training finished.*|Evaluation says.*|Started \d+ game test workers[^(]*)') {

        return "$clock  $($Matches[1].Trim())"
    }

    if ($Line -match 'Traceback|Error|FAILED|What went wrong|Exception|already training') {

        return "$clock  $Line"
    }

    return $null
}

$arguments = (@(
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

if ($Full) {

    Invoke-Gradle $arguments
}

else {

    Invoke-Gradle $arguments | ForEach-Object {

        $said = Format-Feed "$_"

        if ($said) {

            Write-Host $said
        }
    }
}

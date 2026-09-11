# Trains the agents: one on one against a vindicator on natural terrain, as many workers as the machine holds.
# Watch it from a second terminal with scripts\watch.ps1. Resumes where the run left off.
#
#   scripts\train.ps1                           10,000 battles on run 'default', then stops
#   scripts\train.ps1 -Battles 0                until Ctrl+C
#   scripts\train.ps1 -Workers 2 -Device cpu    lighter on the machine
#   scripts\train.ps1 -Run wide -Extra '--entropy-coef 0.003'
#   scripts\train.ps1 -ReplayEvery 50           replays of more fights in runs\<run>\replays, 0 for none
#   scripts\train.ps1 -Run vindicator -FromCopy a run that starts from scripts\imitate.ps1's copy, see -FromCopy
#   scripts\train.ps1 -Run league -Suite league -Seed vs-copy
#                                               the league, starting from the best of runs\vs-copy, see -Suite and -Seed
#   scripts\compare.ps1                         two runs side by side instead, see there
#
# Every checkpoint is played by the workers on its most likely action, in one fight in ten, and the best so far is kept
# as runs\<run>\best.mbw, with the history in runs\<run>\eval.csv. The run stops on its own once evaluation says it has
# stopped getting better, or reached the target, or after -Battles, whichever comes first.
#
# -Suite league fights the league instead of the vindicator: nearly every hostile mob, the scripted fighter and frozen
# checkpoints of the run itself, a different one and a different loadout in every fight, most often the ones the agent
# beats about half the time. Every player gets a rating; scripts\league.ps1 -Run <run> prints the tier list. A checkpoint
# is judged on 1,000 evaluation fights spread evenly over the mobs and the scripted fighter, and the run is done once ten
# judged in a row have not beaten the best. trainer\mmai\league.py has how all of it works.
#
# Learning happens every -RolloutSteps steps of experience across all workers (an iteration): the workers pause, the
# trainer runs a few epochs of PPO over exactly that experience, the new weights swap in, and the fights carry on. The
# battles are fought in rounds of -RoundSize, each with fresh worker processes, so a worker that crashes costs at most
# the rest of its round. Starting workers takes about half a minute and a worker fights some seventy battles a second, so
# a round is large enough to make that a small share of it.
#
# Each worker fights -Slots battles at once, on a quarter again as many terrain sites, in a -Heap sized heap. A worker
# is bound by its one server thread, so the machine's memory, not its cores, decides how many run. Twenty five slots
# measured the same throughput per worker as fifty, in half the memory. Nearly all of a heap is the ground under and
# around the sites in use, so what those sites cost is what the heap has to hold: read from the terrain library it is
# about half a gigabyte and a worker runs in a gigabyte, where generating its own ground settles at about 0.95 GB and
# needs more. The build knows which of those a run is doing and picks the heap to match, unless -Heap says otherwise.

param(
    [string] $Run = 'default',
    [int] $Battles = 10000,
    [int] $RoundSize = 250000,
    [int] $Workers = 0,
    [int] $Slots = 25,
    # Heap per worker. Empty lets the build choose from what the run actually fights on: a gigabyte on the terrain
    # library, two when a worker generates its own ground.
    [string] $Heap = '',
    [int] $RolloutSteps = 16384,
    [ValidateSet('cuda', 'cpu')] [string] $Device = 'cuda',
    [ValidateSet('terrain', 'arena', 'league')] [string] $Suite = 'terrain',
    [int] $ReplayEvery = 200,

    # For a run that starts from a copy of the scripted fighter. Twice reinforcement learning made such a copy worse: a
    # policy near its best has little to gain from a critic that has not yet learned the fight, and much to lose. So it
    # learns from four times the experience per update, in smaller and more carefully bounded steps, lets the critic
    # learn alone for longer first, explores less, and is pulled back towards the teacher's own answers throughout.
    [switch] $FromCopy,

    # Starts a new run from another run's best checkpoint: its whole training state, network, critic and all, taken from
    # runs\<seed>\checkpoints, and runs\<seed> itself left as it is. Without such a run, a network published in
    # models\<seed> with its state, or a folder given by path, seeds it from the state.pt there. It carries on learning
    # the way a copy does, gently, but is not pulled back towards a teacher, whose record is of one fight against one mob.
    # The critic learns alone for the first thirty iterations, since it has only ever seen the fights the seed was trained
    # on. A run that has already started carries on from where it is and only takes the gentle settings from this.
    [string] $Seed = '',

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

if ($Seed) {

    if (-not $PSBoundParameters.ContainsKey('RolloutSteps')) {

        $RolloutSteps = 65536
    }

    if (-not $PSBoundParameters.ContainsKey('Battles')) {

        $Battles = 0
    }

    $warmup = ''

    if (-not (Test-Path (Join-Path $directory 'state.pt'))) {

        $source = if (Test-Path (Get-RunDirectory $Seed)) { Get-RunDirectory $Seed }
                elseif (Test-Path (Join-Path $Root "models\$Seed")) { Join-Path $Root "models\$Seed" }
                else { $Seed }

        $table = Join-Path $source 'eval.csv'
        $best = @(if (Test-Path $table) { Import-Csv $table | Where-Object { $_.best -eq '1' } }) | Select-Object -Last 1

        # The best checkpoint's own state, which holds the weights that won with the critic that went with them. A run
        # whose best is where it started has no such file, and a published model keeps only the state its run had got to
        # when it was published, so those give that instead.
        $state = if ($best) { Join-Path $source ('checkpoints\iteration-{0:D6}.pt' -f [int]$best.iteration) } else { $null }

        if (-not $state -or -not (Test-Path $state)) {

            $state = Join-Path $source 'state.pt'
            Write-Host "No checkpoint of the best iteration in $source; seeding from its state.pt"
        }

        if (-not (Test-Path $state)) {

            throw "No training state in $source to seed run '$Run' from"
        }

        New-Item -ItemType Directory -Force $directory | Out-Null
        Copy-Item $state (Join-Path $directory 'state.pt')

        # Iterations carry on from the seed's, so the critic's time alone is counted from there. Whatever torch says on
        # its way in goes to stderr, which Windows PowerShell would take for an error.
        $ErrorActionPreference = 'Continue'
        $iteration = & $Python -c "import sys, torch; print(torch.load(sys.argv[1], map_location='cpu', weights_only=False)['iteration'])" $state 2>$null
        $ErrorActionPreference = 'Stop'

        if ($LASTEXITCODE -ne 0 -or "$iteration" -notmatch '^\d+$') {

            throw "Could not read the iteration of $state"
        }

        $warmup = "--critic-warmup $([int]$iteration + 30)"

        Write-Host "Seeded '$Run' from $state, iteration $iteration"
    }

    $Extra = "--learning-rate 5e-5 --clip 0.1 --target-kl 0.01 --entropy-coef 0.001 $warmup $Extra"
}

# A checkpoint is judged on this many evaluation fights spread over the league's mobs and the scripted fighter, a few
# dozen against each of them.
if ($Suite -eq 'league') {

    $Extra = "--eval-fights 1000 $Extra"
}

Write-Host "Training run '$Run' in $directory on $Suite, $(if ($Battles -gt 0) { "$Battles battles" } else { 'until evaluation says it is done, or stopped' }). Watch with: scripts\watch.ps1 -Run $Run$(if ($Suite -eq 'league') { ", the tier list with scripts\league.ps1 -Run $Run" })"

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

    if ($Line -match 'mmai\.(?:eval|league)\s+(.*)$' -or $Line -match 'mmai\.train\s+(done: .*)$') {

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
    "-PbatchSize=$Slots",
    "-ProlloutSteps=$RolloutSteps",
    "-PreplayEvery=$ReplayEvery",
    "-PtrainArgs=--device $Device $Extra".Trim()
# An empty -Heap is left off the command line altogether rather than passed as nothing, so that the build sees no
# property at all and falls back to the heap that suits what this run fights on.
) + $workerArguments + @(if ($Heap) { "-PworkerHeap=$Heap" }))

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

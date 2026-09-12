# Starts a run from a copy of the scripted fighter instead of from nothing, then corrects the copy a few rounds over.
# Afterwards scripts\train.ps1 -Run <run> carries on from the copy with reinforcement learning.
#
#   scripts\imitate.ps1 -Run vindicator                 record the teacher, copy it, then three rounds of correction
#   scripts\imitate.ps1 -Run vindicator -Rounds 0       only record the teacher and copy it
#   scripts\imitate.ps1 -Run vindicator -Rounds 2       two more rounds on top of what the run already has
#   scripts\imitate.ps1 -Run league -Suite league       every opponent and every loadout, for a run that will fight the league
#   scripts\imitate.ps1 -Run wide -Demos runs\league -Extra '--h1 512 --hidden 256 --h3 256'
#                                                       another shape of copy from a record already collected, which is what
#                                                       comparing two architectures fairly takes: one record, two networks
#
# A copy made from the teacher's own fights drifts into situations the teacher never got into, and has no idea what to do
# there. Each round lets the copy fight while the scripted fighter says what it would have done on every tick, then copies
# again from everything recorded so far, so the copy learns exactly the situations it gets wrong.
#
# **A copy for the league wants -Suite league**, and this defaulting to the vindicator is what cost league768 a third of
# its fights. Its record was 16,000 fights on the terrain suite, hotbar `[iron_sword]` and nothing else, so the copy and
# the 2,000 iterations of teacher pull that followed had never once seen a bow: over 46,044 bow fights it fired 0.00
# arrows, holding the bow and punching with it. The same teacher with a league record fires 11 arrows a fight and wins
# 60.3% with a bow. What the record does not hold, nothing downstream can learn; see docs\findings.md.

param(
    [string] $Run = 'imitate',
    [int] $Rounds = 3,
    [int] $Fights = 4000,

    # What the record is of. The terrain suite is one vindicator with one sword, which is all a run that will fight one
    # vindicator needs; the league is every opponent, every squad and all ten loadouts, which is what a run that will fight
    # the league needs. A league round wants more fights for the same coverage per pairing: 4,000 is about fifteen fights a
    # pairing there against 4,000 against the one vindicator.
    [ValidateSet('terrain', 'arena', 'league')] [string] $Suite = 'terrain',

    # Which loadouts to record, empty for all of them. A record weighted towards what the copy will be worst at is worth
    # more than an even one; see scripts\dagger.ps1, which takes the same flag for a run already training.
    [string[]] $Loadouts = @(),

    # Another run's record to copy from instead of collecting one, by run name or by path. What it is for is a fair
    # comparison of two shapes of network: a record is a record of the teacher and has no shape of its own, so two copies
    # made from the same one differ by their architecture and nothing else. Recording is skipped and so are the correction
    # rounds, which would need this run's own fights.
    #
    # Read, never written, and given to the trainer as a path rather than linked in: a junction under runs\ is one more
    # thing for a recursive delete to follow, which has cost this repository an environment once already.
    [string] $Demos = '',

    [int] $Workers = 8,

    # Small workers: a worker is bound by its one server thread, and twenty five fights in a gigabyte keep it as busy as
    # fifty in two, so twice as many fit; see scripts\train.ps1.
    [int] $Slots = 25,
    [string] $Heap = '1280M',

    # How far the applied movement and aim are pushed off, as a fraction of full deflection. The teacher gets some, so its
    # record covers getting back on target: 0.1 is six degrees of aim a tick. At 0.2 it won only 11% of the fights it was
    # recorded in, and a record of a fighter losing is mostly of positions nobody should be in. The copy gets little, so
    # the situations it is corrected in are the ones it really gets itself into.
    [double] $TeacherNoise = 0.1,
    [double] $StudentNoise = 0.05,

    # Anything else for the trainer, which is how a copy is made at a size other than the usual one: the widths travel in
    # the weight file and the game reads them from there, so a wider copy needs no Java change and can be trained beside an
    # ordinary one and benched against it.
    #
    #   scripts\imitate.ps1 -Run wide -Extra '--h1 512 --hidden 256 --h3 256'
    [string] $Extra = ''
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

if (-not (Test-Path $Python)) {

    throw 'No trainer environment yet. Run scripts\setup.ps1 first.'
}

$directory = Get-RunDirectory $Run
$copy = Join-Path $directory 'weights\000000.mbw'

# A record borrowed from elsewhere, by run name or by path. Resolved here so a name that is neither fails now rather than
# after the fights have been collected.
$record = ''

if ($Demos) {

    # A run's name, that run's demos folder, or either as a path: whichever of them holds shards, nearest first.
    $fromName = Get-RunDirectory $Demos

    $candidates = @((Join-Path $fromName 'demos'), $fromName, (Join-Path $Demos 'demos'), $Demos)

    $record = @($candidates |
            Where-Object { Test-Path $_ -PathType Container } |
            Where-Object { @(Get-ChildItem $_ -Filter '*.mbr' -Recurse -ErrorAction SilentlyContinue).Count -gt 0 } |
            Select-Object -First 1)

    if (-not $record) {

        throw "No record of the teacher in '$Demos'; make one with scripts\imitate.ps1 -Run $Demos"
    }

    $record = (Resolve-Path $record).Path
    $shards = @(Get-ChildItem $record -Filter '*.mbr' -Recurse)

    Write-Host ("Copying from the record in ${record}: {0} shards, {1:N2} GB, no fighting of its own" -f
            $shards.Count, (($shards | Measure-Object Length -Sum).Sum / 1GB))

    # The layout, which the game writes while recording and so nothing here would. It is the record's own by definition:
    # every shard carries the schema it was recorded against and the trainer refuses one that does not match.
    if (-not (Test-Path (Join-Path $directory 'schema.json'))) {

        $layout = @((Split-Path -Parent $record), $record |
                ForEach-Object { Join-Path $_ 'schema.json' } |
                Where-Object { Test-Path $_ } |
                Select-Object -First 1)

        if (-not $layout) {

            throw "No schema.json beside the record in $record; the run it came from should have one"
        }

        New-Item -ItemType Directory -Force $directory | Out-Null
        Copy-Item $layout (Join-Path $directory 'schema.json')
    }
}

function Invoke-Record([double] $Noise, [string[]] $Extra) {

    Invoke-Gradle (@(':fabric:recordDemonstrations', "-Prun=$Run", "-Psuite=$Suite", "-Parenas=$Fights", "-Pworkers=$Workers",
            "-PbatchSize=$Slots", "-PmaxWorkers=$Workers", "-PworkerHeap=$Heap", "-PdemonstrationNoise=$Noise") +
            @(if ($Loadouts.Count -gt 0) { "-PleagueLoadouts=$($Loadouts -join ',')" }) + $Extra)
}

function Invoke-Imitate {

    # Split on spaces so that a single -Extra string arrives as the flags and values the trainer expects.
    $arguments = @((Join-Path $Root 'trainer\train.py'), 'imitate', '--run', $directory) +
            @(if ($record) { '--demos', $record }) +
            @($Extra.Split(' ', [StringSplitOptions]::RemoveEmptyEntries))

    & $Python $arguments

    if ($LASTEXITCODE -ne 0) {

        throw 'Imitation failed; see the lines above'
    }
}

$recorded = @(Get-ChildItem (Join-Path $directory 'demos') -Filter '*.mbr' -Recurse -ErrorAction SilentlyContinue)

if (-not $record -and $recorded.Count -eq 0) {

    Write-Host ("Recording the scripted fighter over $Fights fights on the $Suite suite" +
            $(if ($Loadouts.Count -gt 0) { ", with the $($Loadouts -join ', ') loadouts alone" }))
    Invoke-Record $TeacherNoise @()
}

if (-not (Test-Path $copy)) {

    Write-Host 'Copying it'
    Invoke-Imitate
}

# A correction round is of this copy's own fights, so a run copying somebody else's record has none to do: -Demos is for
# making another shape of the same copy, and a round would be recorded into a folder the copy it came from does not read.
if ($record -and $Rounds -gt 0) {

    Write-Host "Skipping the $Rounds correction rounds: this copy came from the record in $record, not from its own fights"
}

for ($round = 1; $round -le $(if ($record) { 0 } else { $Rounds }); $round++) {

    Write-Host "Round $round of ${Rounds}: the copy fights, the scripted fighter says what it would have done"
    Invoke-Record $StudentNoise @("-Pstudent=$copy")
    Invoke-Imitate
}

Write-Host "The copy is iteration 0 of '$Run'. Measure it with scripts\eval.ps1 -Run $Run -Iteration 0, train it with scripts\train.ps1 -Run $Run"

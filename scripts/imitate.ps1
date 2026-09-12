# Starts a run from a copy of the scripted fighter instead of from nothing, then corrects the copy a few rounds over.
# Afterwards scripts\train.ps1 -Run <run> carries on from the copy with reinforcement learning.
#
#   scripts\imitate.ps1 -Run vindicator                 record the teacher, copy it, then three rounds of correction
#   scripts\imitate.ps1 -Run vindicator -Rounds 0       only record the teacher and copy it
#   scripts\imitate.ps1 -Run vindicator -Rounds 2       two more rounds on top of what the run already has
#
# A copy made from the teacher's own fights drifts into situations the teacher never got into, and has no idea what to do
# there. Each round lets the copy fight while the scripted fighter says what it would have done on every tick, then copies
# again from everything recorded so far, so the copy learns exactly the situations it gets wrong.

param(
    [string] $Run = 'imitate',
    [int] $Rounds = 3,
    [int] $Fights = 4000,
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

function Invoke-Record([double] $Noise, [string[]] $Extra) {

    Invoke-Gradle (@(':fabric:recordDemonstrations', "-Prun=$Run", "-Parenas=$Fights", "-Pworkers=$Workers", "-PbatchSize=$Slots",
            "-PmaxWorkers=$Workers", "-PworkerHeap=$Heap", "-PdemonstrationNoise=$Noise") + $Extra)
}

function Invoke-Imitate {

    # Split on spaces so that a single -Extra string arrives as the flags and values the trainer expects.
    $arguments = @((Join-Path $Root 'trainer\train.py'), 'imitate', '--run', $directory) +
            @($Extra.Split(' ', [StringSplitOptions]::RemoveEmptyEntries))

    & $Python $arguments

    if ($LASTEXITCODE -ne 0) {

        throw 'Imitation failed; see the lines above'
    }
}

$recorded = @(Get-ChildItem (Join-Path $directory 'demos') -Filter '*.mbr' -Recurse -ErrorAction SilentlyContinue)

if ($recorded.Count -eq 0) {

    Write-Host "Recording the scripted fighter over $Fights fights"
    Invoke-Record $TeacherNoise @()
}

if (-not (Test-Path $copy)) {

    Write-Host 'Copying it'
    Invoke-Imitate
}

for ($round = 1; $round -le $Rounds; $round++) {

    Write-Host "Round $round of ${Rounds}: the copy fights, the scripted fighter says what it would have done"
    Invoke-Record $StudentNoise @("-Pstudent=$copy")
    Invoke-Imitate
}

Write-Host "The copy is iteration 0 of '$Run'. Measure it with scripts\eval.ps1 -Run $Run -Iteration 0, train it with scripts\train.ps1 -Run $Run"

# Records one round of correction for a run that is already training: the run's own best network drives, the scripted
# fighter says what it would have done on every tick, and the answers go into the run's demos. A training run with
# -TeacherWeight is then pulled back towards them on every update.
#
#   scripts\dagger.ps1 -Run league                       one round for runs\league on the league, from its best weights
#   scripts\dagger.ps1 -Run league -Fights 8000          more of it
#   scripts\dagger.ps1 -Run league -Weights models\vs-copy\best.mbw    another network's answers to correct
#   scripts\dagger.ps1 -Run vindicator -Suite terrain    the same against one vindicator, as scripts\imitate.ps1's rounds are
#
# This is scripts\imitate.ps1's correction round on its own, for a run past imitation. The point of it is the same: the
# record of a teacher driving only covers the positions a teacher gets into, and a network that has drifted somewhere else
# has never seen what to do there. What is new is the suite. On the league the student meets every mob and every loadout,
# so the record covers a bow, a crossbow, a shield and every opponent there is, which is what a league run needs to be
# pulled back towards; a record of one fight against one vindicator is not.
#
# Nothing here touches what the run is doing. The record is written into a folder of its own, so a run can go on training
# while this collects, and the next update after it restarts picks the demos up. Both want memory, though, so the run is
# best stopped first unless the machine has room for both.

param(
    [Parameter(Mandatory = $true)] [string] $Run,

    # Every fight on the league draws the next opponent and loadout in turn, so a round wants enough fights for each of
    # the twenty seven opponents and ten loadouts to come round a few times. Four thousand is about fifteen fights a
    # pairing, which is what scripts\imitate.ps1 records per round against the one vindicator.
    [int] $Fights = 4000,

    [ValidateSet('terrain', 'arena', 'league')] [string] $Suite = 'league',

    # Which network drives. The run's best weights unless another is named, since the best is what the run would be
    # judged on and so the one whose mistakes are worth correcting.
    [string] $Weights = '',

    [int] $Workers = 8,
    [int] $Slots = 25,
    [string] $Heap = '1280M',

    # How far the student's movement and aim are pushed off while it drives, as a fraction of full deflection. Little, so
    # the positions it is corrected in are the ones it really gets itself into; see scripts\imitate.ps1.
    [double] $StudentNoise = 0.05,

    # Which loadouts the round is fought with, empty for all of them. A record of the loadouts a run is worst with is
    # what teaches it those: the fighter's bow and crossbow win about a third of their fights where its sword wins two
    # thirds, and a round drawn from every loadout spends nine tenths of itself on what it can already do.
    [string[]] $Loadouts = @()
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

$directory = Get-RunDirectory $Run

if (-not (Test-Path $directory)) {

    throw "There is no run at $directory"
}

# PowerShell names are not case sensitive, so a local cannot be called $weights beside -Weights.
$driver = if ($Weights) { (Resolve-Path $Weights -ErrorAction SilentlyContinue).Path } else { Join-Path $directory 'best.mbw' }

if (-not $driver -or -not (Test-Path $driver)) {

    throw "No weights to drive with. Run '$Run' has no best.mbw yet, so give -Weights a file, or train it until evaluation names a best."
}

# A demos folder that is a junction points at another run's record, which scripts\compare.ps1 makes so a copy's run can be
# pulled towards the record it was copied from. Writing a round into that would put this run's corrections into the other
# run's folder, where every run reading it would learn from fights it never had.
$demos = Join-Path $directory 'demos'
$item = Get-Item $demos -ErrorAction SilentlyContinue

if ($item -and $item.Attributes -band [IO.FileAttributes]::ReparsePoint) {

    throw ("$demos points at another run's demos ($($item.Target)). Replace the junction with a real folder, and put a " +
            "junction to that record inside it if the run should still learn from it, before recording into this one.")
}

Write-Host ("Recording $Fights fights on the $Suite suite: $(Split-Path $driver -Leaf) drives, the scripted fighter labels " +
        "every tick$(if ($Loadouts.Count -gt 0) { ", with the $($Loadouts -join ', ') loadouts alone" })")

Invoke-Gradle (@(':fabric:recordDemonstrations', "-Prun=$Run", "-Psuite=$Suite", "-Parenas=$Fights", "-Pworkers=$Workers",
        "-PbatchSize=$Slots", "-PmaxWorkers=$Workers", "-PworkerHeap=$Heap", "-PdemonstrationNoise=$StudentNoise",
        "-Pstudent=$driver") + @(if ($Loadouts.Count -gt 0) { "-PleagueLoadouts=$($Loadouts -join ',')" }))

$recorded = @(Get-ChildItem $demos -Filter '*.mbr' -Recurse -ErrorAction SilentlyContinue)

Write-Host ("The run's record is now $($recorded.Count) shards in $demos. Train pulled back towards it with: " +
        "scripts\train.ps1 -Run $Run -Suite $Suite -TeacherWeight 0.5")

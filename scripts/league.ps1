# The league of a run on the league suite: every player by rating with its tier, how the agent does against each
# opponent, with each loadout, and where the training fights go now. Only reads the files the trainer keeps in
# runs\<run>\league, so it can be run at any time, during the run or after it.
#
#   scripts\league.ps1 -Run league              the tier list, the newest checkpoints in it, and the tables
#   scripts\league.ps1 -Run league -All         every checkpoint that has been rated, not only the newest
#   scripts\league.ps1 -Run league -Pairings 40 more of the pairings the fights are drawn as
#   scripts\league.ps1 -Test                    the trainer's whole unit suite, which is where the league's own tests live:
#                                               the Elo arithmetic, the pairings, the pool, and beside them the shard
#                                               reader, the critic, the auxiliary heads and the rest
#
# Ratings are Elo, from evaluation fights only: a checkpoint on its most likely action against an opponent drawn evenly
# from everyone, one point for a win, half for a timeout or a draw. The scripted fighter is held at 1500, so the scale
# means the same in every run. A tier is 150 points wide, which is what a 70% expected score over the tier below comes
# to; the scripted fighter sits at the bottom of B.
#
# An opponent is a mob, a squad of several at once (2x_zombie, zombie+skeleton) or a rung of the difficulty ladder
# (zombie(hard), zombie(easy)). Every one of those is a player in its own right: two zombies are not twice a zombie and a
# hard zombie is not a zombie, so nothing here adds any of them up.

param(
    [string] $Run = 'default',
    [int] $Checkpoints = 8,

    # How many pairings of a loadout and an opponent to show, largest share first; there are hundreds of them.
    [int] $Pairings = 15,
    [switch] $All,
    [switch] $Test
)

. "$PSScriptRoot\_common.ps1"

if ($Test) {

    # The whole trainer suite, not only the league's own tests: they live in the same folder, they all run in seconds, and
    # a switch that ran a subset would be a second list to keep in step with the first.
    #
    # unittest reports on stderr, which Windows PowerShell would take for an error.
    $ErrorActionPreference = 'Continue'

    Push-Location (Join-Path $Root 'trainer')

    try {

        & $Python -m unittest discover -s tests -v
    }

    finally {

        Pop-Location
    }

    if ($LASTEXITCODE -ne 0) {

        throw 'The trainer''s unit tests failed'
    }

    return
}

$directory = Get-RunDirectory $Run
$league = Join-Path $directory 'league'

if (-not (Test-Path (Join-Path $league 'ratings.csv'))) {

    throw "No league in $league yet. Start one with scripts\train.ps1 -Run $Run -Suite league"
}

# A table the trainer writes, or nothing while it has not written it yet.
function Read-Table([string] $File) {

    return @(if (Test-Path $File) { Import-Csv $File })
}

$ratings = Read-Table (Join-Path $league 'ratings.csv')
$opponents = Read-Table (Join-Path $league 'opponents.csv')
$loadouts = Read-Table (Join-Path $league 'loadouts.csv')
$pairs = Read-Table (Join-Path $league 'pairs.csv')
$ground = Read-Table (Join-Path $league 'ground.csv')

# The checkpoint evaluation judged best, which is the one in best.mbw; none until the first has been judged.
$best = @(Read-Table (Join-Path $directory 'eval.csv') | Where-Object { $_.best -eq '1' }) | Select-Object -Last 1
$bestName = if ($best) { 'iteration-{0:D6}' -f [int]$best.iteration } else { '' }

function Get-Tier([double] $Rating) {

    foreach ($tier in @(@(1800, 'S'), @(1650, 'A'), @(1500, 'B'), @(1350, 'C'), @(1200, 'D'), @(1050, 'E'))) {

        if ($Rating -ge $tier[0]) {

            return $tier[1]
        }
    }

    return 'F'
}

function Format-Percent([int] $Count, [int] $Of) {

    if ($Of -le 0) {

        return '-'
    }

    return '{0:N1}' -f (100.0 * $Count / $Of)
}

# Every mob and the scripted fighter, rated or not yet; of the checkpoints, the newest few that have been rated and the
# best, or all of them.
$rated = @($ratings | Where-Object { $_.kind -eq 'checkpoint' -and [int]$_.games -gt 0 } | Sort-Object { $_.player } -Descending)
$shownCheckpoints = if ($All) { $rated } else { @($rated | Select-Object -First $Checkpoints) + @($rated | Where-Object { $_.player -eq $bestName }) }
$shownNames = @($shownCheckpoints | ForEach-Object { $_.player } | Select-Object -Unique)

$shown = @($ratings | Where-Object { $_.kind -ne 'checkpoint' -or $shownNames -contains $_.player })
$totalRated = ($ratings | Where-Object { $_.kind -eq 'checkpoint' } | Measure-Object -Property games -Sum).Sum

Write-Host ("League of run '{0}': {1:N0} rated fights, {2} players, {3} checkpoints rated" -f $Run, $totalRated, $ratings.Count, $rated.Count)
Write-Host ''
Write-Host 'Tier list, by rating, with each player''s own rated fights. The scripted fighter is held at 1500; a tier is 150 points.'
Write-Host ('{0,4}  {1,-4} {2,-28} {3,7} {4,7} {5,6} {6,6} {7,6}' -f '#', 'tier', 'player', 'rating', 'rated', 'won', 'lost', 'drawn')

$rank = 0

foreach ($player in $shown) {

    $rank++
    $note = if ($player.player -eq $bestName) { '   <- best, in best.mbw' } elseif ([int]$player.games -eq 0) { '   not rated yet' } else { '' }

    Write-Host ('{0,4}  {1,-4} {2,-28} {3,7:N0} {4,7} {5,6} {6,6} {7,6}{8}' -f $rank, (Get-Tier ([double]$player.rating)), $player.player,
            [double]$player.rating, $player.games, $player.wins, $player.losses, $player.draws, $note)
}

if (-not $All -and $rated.Count -gt $shownNames.Count) {

    Write-Host ("      {0} older checkpoints left out; -All shows every one" -f ($rated.Count - $shownNames.Count))
}

if ($opponents.Count -gt 0) {

    Write-Host ''
    Write-Host 'Against each opponent: the last fights of each kind. Evaluation plays a checkpoint on its most likely action;'
    Write-Host 'training is the agent exploring. Share is how many of the training fights go to it now. A squad, 2x_zombie or'
    Write-Host 'zombie+skeleton, is a player of its own: two zombies are not twice a zombie, and nothing adds them up.'
    Write-Host ('{0,-28} {1,7} {2,7}   {3,6} {4,6} {5,6} {6,9} {7,6}   {8,6} {9,6}' -f 'opponent', 'rating', 'share %',
            'eval', 'won %', 'lost %', 'timeout %', 'draw %', 'train', 'won %')

    foreach ($row in $opponents | Sort-Object { [double]$_.rating } -Descending) {

        $fights = [int]$row.eval_fights
        $trained = [int]$row.train_fights

        Write-Host ('{0,-28} {1,7:N0} {2,7:N1}   {3,6} {4,6} {5,6} {6,9} {7,6}   {8,6} {9,6}' -f $row.opponent, [double]$row.rating,
                (100.0 * [double]$row.share), $fights, (Format-Percent $row.eval_wins $fights), (Format-Percent $row.eval_losses $fights),
                (Format-Percent $row.eval_timeouts $fights), (Format-Percent $row.eval_draws $fights), $trained,
                (Format-Percent $row.train_wins $trained))
    }
}

if ($loadouts.Count -gt 0) {

    Write-Host ''
    Write-Host 'With each loadout the agent carried: the last fights of each kind.'
    Write-Host ('{0,-20} {1,6} {2,6} {3,6} {4,9} {5,6}   {6,6} {7,6}' -f 'loadout', 'eval', 'won %', 'lost %', 'timeout %', 'draw %', 'train', 'won %')

    foreach ($row in $loadouts) {

        $fights = [int]$row.eval_fights
        $trained = [int]$row.train_fights

        Write-Host ('{0,-20} {1,6} {2,6} {3,6} {4,9} {5,6}   {6,6} {7,6}' -f $row.loadout, $fights, (Format-Percent $row.eval_wins $fights),
                (Format-Percent $row.eval_losses $fights), (Format-Percent $row.eval_timeouts $fights), (Format-Percent $row.eval_draws $fights),
                $trained, (Format-Percent $row.train_wins $trained))
    }
}

if ($pairs.Count -gt 0) {

    Write-Host ''
    Write-Host 'Where the training fights go now, as pairings of a loadout and an opponent, which is what a fight is drawn as:'
    Write-Host 'the largest shares first. A pairing near an even result gets the most, a floor keeps every one of them coming'

    $shownPairs = @($pairs | Select-Object -First $Pairings)
    $more = if ($shownPairs.Count -lt $pairs.Count) { ' with the most, -Pairings for more' } else { '' }

    Write-Host ("round, and a cap is the opponent's. {0:N0} pairings in all; showing {1}{2}:" -f $pairs.Count, $shownPairs.Count, $more)
    Write-Host ('{0,-20} {1,-28} {2,7} {3,7}   {4,7} {5,6}' -f 'loadout', 'opponent', 'share %', 'chance', 'fights', 'won %')

    foreach ($row in $shownPairs) {

        # The record is the faded one the chance leans on, so its fights are not whole numbers.
        $fought = [double]$row.fights

        Write-Host ('{0,-20} {1,-28} {2,7:N2} {3,7:N2}   {4,7:N0} {5,6}' -f $row.loadout, $row.opponent, (100.0 * [double]$row.share),
                [double]$row.chance, $fought, (Format-Percent ([int][double]$row.wins) ([int]$fought)))
    }
}

if ($ground.Count -gt 0) {

    Write-Host ''
    Write-Host 'On each kind of ground, and what finished the other side. A quarter of the fights are drawn onto ground with'
    Write-Host 'something worth knocking an opponent into. "Ground %" of the wins is the number to watch: a fight the terrain'
    Write-Host 'ends is the agent''s win either way, so it is the only sign that the agent has learned the terrain is a weapon.'
    Write-Host ('{0,-12} {1,8} {2,8} {3,7}   {4,8} {5,8} {6,8} {7,8}' -f 'ground', 'fights', 'wins', 'won %', 'by agent', 'by ground',
            'ground %', 'by side')

    foreach ($row in $ground) {

        $fights = [int]$row.fights
        $wins = [int]$row.wins

        Write-Host ('{0,-12} {1,8} {2,8} {3,7}   {4,8} {5,8} {6,8} {7,8}' -f $row.site, $fights, $wins, (Format-Percent $wins $fights),
                $row.by_agent, $row.by_terrain, (Format-Percent $row.by_terrain $wins), $row.by_side)
    }
}

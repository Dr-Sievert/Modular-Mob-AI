# The quick sanity check: fights with the scripted brain, which proves the observation and the body still work.
# Needs nothing but Java.
#
#   scripts\test.ps1                    20 fights in the closed arena, boots in seconds
#   scripts\test.ps1 -Terrain           the same on natural terrain, which has to generate the world first
#   scripts\test.ps1 -Arenas 200
#   scripts\test.ps1 -Terrain -Replays  every fight written down for watching, in runs\gametest\replays
#   scripts\test.ps1 -Mechanics         no fights: the agent's bows, shields, blocks and the rest against a player's rules
#   scripts\test.ps1 -League            54 fights on terrain, twice round every league opponent, then how each went
#   scripts\test.ps1 -League -Weights runs\x\best.mbw
#                                       a network drives the agents instead, and fights a frozen copy of itself as well

param(
    [int] $Arenas = 20,
    [switch] $Terrain,
    [switch] $Mechanics,
    [switch] $League,
    [string] $Weights = '',
    [switch] $Replays
)

. "$PSScriptRoot\_common.ps1"

$suite = if ($Mechanics) { 'mechanics' } elseif ($League) { 'league' } elseif ($Terrain) { 'terrain' } else { 'arena' }
$replayEvery = if ($Replays) { 1 } else { 0 }

# Twice round the league's twenty six mobs and the scripted fighter, unless asked for another number.
if ($League -and -not $PSBoundParameters.ContainsKey('Arenas')) {

    $Arenas = 54
}

# The game runs in a folder of its own under mod\, so a path relative to here would not be found from there.
$brain = if ($Weights) { @('-Pbrain=neural', "-PbrainWeights=$((Resolve-Path $Weights).Path)") } else { @() }

Invoke-Gradle (@(':fabric:runGametest', "-Parenas=$Arenas", "-Psuite=$suite", "-PreplayEvery=$replayEvery") + $brain)

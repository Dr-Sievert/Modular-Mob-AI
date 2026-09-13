# The quick sanity check: fights with the scripted brain, which proves the observation and the body still work.
# Needs nothing but Java.
#
#   scripts\test.ps1                    20 fights in the closed arena, boots in seconds
#   scripts\test.ps1 -Terrain           the same on natural terrain, which has to generate the world first
#   scripts\test.ps1 -Arenas 200
#   scripts\test.ps1 -Terrain -Replays  every fight written down for watching, in runs\gametest\replays
#   scripts\test.ps1 -Mechanics         no fights: the agent's bows, shields, blocks and the rest against a player's rules
#   scripts\test.ps1 -League            194 fights on terrain, twice round every league opponent, then how each went
#   scripts\test.ps1 -League -Weights runs\x\best.mbw
#                                       a network drives the agents instead, and fights a frozen copy of itself as well
#   scripts\test.ps1 -League -LeagueModels blast
#                                       published networks in the league as well, each a player of its own
#   scripts\test.ps1 -Play              the agent in a real game: networks in the jar, /mmai, sides, Infinity loadouts
#   scripts\test.ps1 -Play -Loader neoforge   any suite on NeoForge rather than Fabric

param(
    [int] $Arenas = 20,
    [switch] $Terrain,
    [switch] $Mechanics,
    [switch] $League,
    [switch] $Play,
    [string] $Weights = '',

    # League only: published networks in models\ to field as players of their own, by name. See docs\training.md.
    [string[]] $LeagueModels = @(),
    [switch] $Replays,
    [ValidateSet('fabric', 'neoforge')] [string] $Loader = 'fabric'
)

. "$PSScriptRoot\_common.ps1"

$suite = if ($Play) { 'play' } elseif ($Mechanics) { 'mechanics' } elseif ($League) { 'league' } elseif ($Terrain) { 'terrain' } else { 'arena' }
$replayEvery = if ($Replays) { 1 } else { 0 }

# Twice round the league's thirty seven mobs and eleven squads, each on normal and on hard, and the scripted fighter,
# unless asked for another number. Twice, so an opponent that only fails on some ground is not written off as working, and
# every one gets a second loadout. Add easy with -PleagueDifficulties=easy,normal,hard, and count it in: the rungs are the
# trainer's to open in a real run, and this is the only place they are all fought.
if ($League -and -not $PSBoundParameters.ContainsKey('Arenas')) {

    $Arenas = 194
}

# The game runs in a folder of its own under mod\, so a path relative to here would not be found from there.
$brain = if ($Weights) { @('-Pbrain=neural', "-PbrainWeights=$((Resolve-Path $Weights).Path)") } else { @() }

# A run that names no published networks fields none, so the property is left off the command line altogether.
# A comma is PowerShell's array operator, so -LeagueModels blast,other arrives as two words: joined back here,
# since a string parameter would have handed Gradle "blast other" and the build would have failed on the second.
$models = if ($LeagueModels.Count -gt 0) { @("-PleagueModels=$($LeagueModels -join ',')") } else { @() }

# The two loaders name their headless test run differently.
$task = if ($Loader -eq 'neoforge') { ':neoforge:runGameTestServer' } else { ':fabric:runGametest' }

Invoke-Gradle (@($task, "-Parenas=$Arenas", "-Psuite=$suite", "-PreplayEvery=$replayEvery") + $brain + $models)

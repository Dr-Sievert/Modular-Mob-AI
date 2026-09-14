# The quick sanity check: fights with the scripted brain, which proves the observation and the body still work.
# Needs nothing but Java.
#
#   scripts\test.ps1                    20 fights in the closed arena, boots in seconds
#   scripts\test.ps1 -Terrain           the same on natural terrain, which has to generate the world first
#   scripts\test.ps1 -Arenas 200
#   scripts\test.ps1 -Terrain -Replays  every fight written down for watching, in runs\gametest\replays
#   scripts\test.ps1 -Mechanics         no fights: the agent's bows, shields, blocks and the rest against a player's rules
#   scripts\test.ps1 -League            246 fights on terrain, twice round every league opponent, then how each went
#   scripts\test.ps1 -League -Weights runs\x\best.mbw
#                                       a network drives the agents instead, and fights a frozen copy of itself as well
#   scripts\test.ps1 -League -LeagueModels blast
#                                       published networks in the league as well, each a player of its own
#   scripts\test.ps1 -Crowd -Weights models\blast6\best.mbw
#                                       the same fight with nobody standing about it and with 1, 3 and 9, tick by tick:
#                                       which slot the opponent is in, where the aim is, who a press lands on
#   scripts\test.ps1 -Pack              packs of 2, 3 and 4 zombies and of 2 and 3 vindicators on natural ground, tick by
#                                       tick: how far the nearest of them stood, how often two were on top of the agent,
#                                       how far off its blows landed, which way its feet went. -Weights for a network
#   scripts\test.ps1 -Horde             20, 100, 500 and 2,000 mobs round one agent on flat ground: clips a tick, time to
#                                       perceive, slot churn, blows, ticks lived against an idle body
#   scripts\test.ps1 -Play              the agent in a real game: networks in the jar, /mmai, sides, Infinity loadouts
#   scripts\test.ps1 -Play -Loader neoforge   any suite on NeoForge rather than Fabric

param(
    [int] $Arenas = 20,
    [switch] $Terrain,
    [switch] $Mechanics,
    [switch] $League,

    # The crowd diagnosis: one fight, fought with 0, 1, 3 and 9 monsters standing about it, watched tick by tick.
    [switch] $Crowd,

    # The pack diagnosis: packs of 2, 3 and 4 zombies and of 2 and 3 vindicators, watched tick by tick. What a win rate
    # cannot say — whether the kite is holding them off or standing in the middle of them. See docs\testing.md.
    [switch] $Pack,

    # Twenty, a hundred, five hundred and two thousand mobs round one agent: what perceiving a horde costs, and whether
    # the network is worth anything in one. Prints a table; see docs\testing.md.
    [switch] $Horde,
    [switch] $Play,
    [string] $Weights = '',

    # League only: published networks in models\ to field as players of their own, by name. See docs\training.md.
    [string[]] $LeagueModels = @(),
    [switch] $Replays,
    [ValidateSet('fabric', 'neoforge')] [string] $Loader = 'fabric'
)

. "$PSScriptRoot\_common.ps1"

$suite = if ($Play) { 'play' } elseif ($Mechanics) { 'mechanics' } elseif ($League) { 'league' } elseif ($Crowd) { 'crowd' } elseif ($Pack) { 'pack' } elseif ($Horde) { 'horde' } elseif ($Terrain) { 'terrain' } else { 'arena' }
$replayEvery = if ($Replays) { 1 } else { 0 }

# Twice round the league's forty eight mobs, eleven squads and two jockeys, each on normal and on hard, and the scripted
# fighter, unless asked for another number. Twice, so an opponent that only fails on some ground is not written off as
# working, and every one gets a second loadout. Add easy with -PleagueDifficulties=easy,normal,hard, and count it in: the
# rungs are the trainer's to open in a real run, and this is the only place they are all fought.
if ($League -and -not $PSBoundParameters.ContainsKey('Arenas')) {

    $Arenas = 246
}

# Four crowds, three fights each: enough for the four numbers to mean something and few enough to read every tick of one
# fight per crowd. The crowds go round in turn, so a count that is not a multiple of four is uneven.
if ($Crowd -and -not $PSBoundParameters.ContainsKey('Arenas')) {

    $Arenas = 12
}

# Five packs and ten loadouts, drawn so that every pairing of the two comes round once in fifty. Three hundred is six of
# each pairing, sixty fights a pack and thirty a loadout, and about a minute and a half on three workers. A count that is
# not a multiple of fifty leaves the last few pairings one fight ahead of the rest.
if ($Pack -and -not $PSBoundParameters.ContainsKey('Arenas')) {

    $Arenas = 300
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

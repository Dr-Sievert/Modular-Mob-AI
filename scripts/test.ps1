# The quick sanity check: fights with the scripted brain, which proves the observation and the body still work.
# Needs nothing but Java.
#
#   scripts\test.ps1                    20 fights in the closed arena, boots in seconds
#   scripts\test.ps1 -Terrain           the same on natural terrain, which has to generate the world first
#   scripts\test.ps1 -Arenas 200
#   scripts\test.ps1 -Terrain -Replays  every fight written down for watching, in runs\gametest\replays
#   scripts\test.ps1 -Mechanics         no fights: the agent's bows, shields, blocks and the rest against a player's rules
#   scripts\test.ps1 -Play              the agent in a real game: networks in the jar, /mmai, sides, Infinity loadouts
#   scripts\test.ps1 -Play -Loader neoforge   the same on NeoForge

param(
    [int] $Arenas = 20,
    [switch] $Terrain,
    [switch] $Mechanics,
    [switch] $Play,
    [switch] $Replays,
    [ValidateSet('fabric', 'neoforge')] [string] $Loader = 'fabric'
)

. "$PSScriptRoot\_common.ps1"

$suite = if ($Play) { 'play' } elseif ($Mechanics) { 'mechanics' } elseif ($Terrain) { 'terrain' } else { 'arena' }
$replayEvery = if ($Replays) { 1 } else { 0 }

# The two loaders name their headless test run differently.
$task = if ($Loader -eq 'neoforge') { ':neoforge:runGameTestServer' } else { ':fabric:runGametest' }

Invoke-Gradle @($task, "-Parenas=$Arenas", "-Psuite=$suite", "-PreplayEvery=$replayEvery")

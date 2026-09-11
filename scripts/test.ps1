# The quick sanity check: fights with the scripted brain, which proves the observation and the body still work.
# Needs nothing but Java.
#
#   scripts\test.ps1                    20 fights in the closed arena, boots in seconds
#   scripts\test.ps1 -Terrain           the same on natural terrain, which has to generate the world first
#   scripts\test.ps1 -Arenas 200

param(
    [int] $Arenas = 20,
    [switch] $Terrain
)

. "$PSScriptRoot\_common.ps1"

$suite = if ($Terrain) { 'terrain' } else { 'arena' }

Invoke-Gradle @(':fabric:runGametest', "-Parenas=$Arenas", "-Psuite=$suite")

# Starts Minecraft with the mod, every agent driven by a trained network from the moment a world opens.
#
#   scripts\play.ps1                          the Fabric client, on the best network in models\ (the highest win rate)
#   scripts\play.ps1 -Model blast             a published network by name, by its folder under models\
#   scripts\play.ps1 -Weights runs\blast\weights\002000.mbw      any weight file, such as a checkpoint of a local run
#   scripts\play.ps1 -Scripted                the hand written fighter instead
#   scripts\play.ps1 -Loader neoforge         the NeoForge client rather than Fabric's
#   scripts\play.ps1 -World arena             straight into the saved world 'arena', skipping the menus
#
# It is the development client, so the mod is the one in this checkout, built on the way. Every network under models\
# can be named in the game as well, /mmai brain @e blast, including ones published after the last build. The log
# says which network is driving as soon as a world opens:
#   Loaded ...\models\blast4\best.mbw from iteration 8825: 792 -> 792 -> 256 -> GRU 128 -> 128 -> 19 (371,783 parameters)
#   Agents with no brain of their own run on best.mbw from ...\models\blast4, iteration 8825
#
# In the world: /mmai spawn, /mmai loadout, /mmai brain, /mmai ally, /mmai enemy, /mmai info. See docs\playing.md.
# The client wants about 3 GB of memory.

param(
    [string] $Model = '',
    [string] $Weights = '',
    [ValidateSet('fabric', 'neoforge')] [string] $Loader = 'fabric',
    [switch] $Scripted,
    [string] $World = ''
)

. "$PSScriptRoot\_common.ps1"

$models = Join-Path $Root 'models'

# The published network with the highest win rate its model.json records, and of equal ones the one judged on more
# fights; the build makes the same pick for the network it puts in the jar as 'best'.
function Get-BestModel {

    $published = Get-ChildItem $models -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName 'best.mbw') } | ForEach-Object {

        $info = Get-Content (Join-Path $_.FullName 'model.json') -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json -ErrorAction SilentlyContinue

        [pscustomobject]@{
            Name = $_.Name
            Won = $(if ($info -and $null -ne $info.won) { [double]$info.won } else { -1.0 })
            Fights = $(if ($info -and $null -ne $info.fights) { [int]$info.fights } else { 0 })
        }
    }

    return @($published | Sort-Object -Property @{ Expression = 'Won'; Descending = $true }, @{ Expression = 'Fights'; Descending = $true } |
            Select-Object -First 1)
}

if ($Scripted) {

    $label = 'the scripted fighter'
    $brain = @('-Pbrain=scripted')
}

else {

    if ($Weights) {

        $file = (Resolve-Path $Weights -ErrorAction SilentlyContinue).Path

        if (-not $file) {

            throw "No weight file at $Weights"
        }
    }

    else {

        if (-not $Model) {

            $best = Get-BestModel

            if (-not $best) {

                throw "No network is published under $models. Name one with -Weights, or play with -Scripted."
            }

            $Model = $best.Name
        }

        $file = Join-Path $models "$Model\best.mbw"

        if (-not (Test-Path $file)) {

            $known = (Get-ChildItem $models -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.Name }) -join ', '
            throw "No network at $file. Published: $known"
        }
    }

    $info = Get-Content (Join-Path (Split-Path $file -Parent) 'model.json') -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json -ErrorAction SilentlyContinue
    $label = $file

    if ($info -and $null -ne $info.won) {

        $label = "$file (iteration $($info.iteration), won $($info.won)% of $($info.fights) fights)"
    }

    $brain = @('-Pbrain=neural', "-PbrainWeights=$file")
}

$free = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB

if ($free -lt 4) {

    Write-Warning ("{0:N1} GB of memory is free and the client wants about 3 GB; with training running, it may crowd the workers out." -f $free)
}

Write-Host "Starting the $Loader client, agents on $label"

$arguments = @(":${Loader}:runClient", "-Pmodels=$models") + $brain

# Minecraft's own quick play: the client opens the world by its folder name under saves\ as soon as it has started. The
# build adds it to the client's program arguments; Gradle's --args would replace the ones the run needs to start at all.
if ($World) {

    $arguments += "-Pworld=$World"
}

Invoke-Gradle $arguments

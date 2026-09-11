# Measures a trained network: fights on its most likely actions, no exploration, nothing recorded.
#
#   scripts\eval.ps1                          the newest weights of run 'default', on natural terrain
#   scripts\eval.ps1 -Run wide -Iteration 400
#   scripts\eval.ps1 -Arenas 1000 -Suite arena

param(
    [string] $Run = 'default',
    [int] $Iteration = -1,
    [int] $Arenas = 200,
    [int] $Workers = 2,
    [ValidateSet('terrain', 'arena')] [string] $Suite = 'terrain'
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

$weights = Join-Path (Get-RunDirectory $Run) 'weights'

if ($Iteration -ge 0) {

    $file = Join-Path $weights ('{0:D6}.mbw' -f $Iteration)
}

else {

    $file = (Get-ChildItem $weights -Filter '*.mbw' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1).FullName
}

if (-not $file -or -not (Test-Path $file)) {

    throw "No weights found in $weights"
}

Write-Host "Evaluating $file over $Arenas arenas"

Invoke-Gradle @(':fabric:runGametestParallel', '-Pbrain=neural', "-PbrainWeights=$file", "-Parenas=$Arenas", "-Pworkers=$Workers", "-Psuite=$Suite")

# Puts a run's best network into git, so anyone with the repository can fight it, evaluate it or carry the training on,
# without the run folder. runs\ itself is never committed: rollouts, demonstrations and checkpoints run to gigabytes.
#
#   scripts\publish.ps1 -Run blast              models\blast: best.mbw, eval.csv, schema.json and model.json
#   scripts\publish.ps1 -Run blast -State       and state.pt, the trainer's own state, to train on from it elsewhere
#   scripts\publish.ps1 -Run blast -Push        and commit and push it
#
# models\<run>\best.mbw is what the game loads, e.g. scripts\eval.ps1 -Weights models\<run>\best.mbw, or any game with
#   -Dmodular_mob_ai.brain=neural -Dmodular_mob_ai.brain.weights=models\<run>\best.mbw
# To train on from a published state, copy models\<run>\state.pt and schema.json into runs\<name>\ and start
# scripts\train.ps1 -Run <name>.
#
# A run with no evaluation yet (a copy made by scripts\imitate.ps1, say) publishes its newest weights instead.

param(
    [Parameter(Mandatory = $true)] [string] $Run,
    [switch] $State,
    [switch] $Push
)

. "$PSScriptRoot\_common.ps1"

$source = Get-RunDirectory $Run

if (-not (Test-Path $source)) {

    throw "No run folder at $source"
}

$weights = Join-Path $source 'best.mbw'
$evaluated = @(if (Test-Path (Join-Path $source 'eval.csv')) { Import-Csv (Join-Path $source 'eval.csv') })
$best = $evaluated | Where-Object { $_.best -eq '1' } | Select-Object -Last 1

if (-not (Test-Path $weights) -or -not $best) {

    $weights = (Get-ChildItem (Join-Path $source 'weights') -Filter '*.mbw' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1).FullName
    $best = $null
}

if (-not $weights) {

    throw "$Run has no weights to publish yet"
}

$target = Join-Path $Root "models\$Run"
New-Item -ItemType Directory -Force $target | Out-Null

Copy-Item $weights (Join-Path $target 'best.mbw')
Copy-Item (Join-Path $source 'schema.json') $target -ErrorAction SilentlyContinue

if ($evaluated.Count -gt 0) {

    Copy-Item (Join-Path $source 'eval.csv') $target
}

if ($State) {

    Copy-Item (Join-Path $source 'state.pt') $target
}

# What the network is, in a form both people and scripts can read.
$summary = [ordered]@{
    run = $Run
    published = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ssK')
    code = "$(git -C $Root rev-parse --short HEAD)".Trim()
    weights = $(if ($best) { "best evaluated, iteration $($best.iteration)" } else { "newest, $([IO.Path]::GetFileNameWithoutExtension($weights)), not evaluated" })
}

if ($best) {

    $fights = [Math]::Max(1, [int]$best.fights)
    $summary.iteration = [int]$best.iteration
    $summary.fights = [int]$best.fights
    $summary.won = [Math]::Round(100.0 * [int]$best.wins / $fights, 1)
    $summary.lost = [Math]::Round(100.0 * ([int]$best.fights - [int]$best.wins - [int]$best.timeouts) / $fights, 1)
    $summary.timed_out = [Math]::Round(100.0 * [int]$best.timeouts / $fights, 1)
}

$summary.state = [bool]$State -or (Test-Path (Join-Path $target 'state.pt'))

[IO.File]::WriteAllText((Join-Path $target 'model.json'), ($summary | ConvertTo-Json) + "`n")

$line = if ($best) { "iteration $($summary.iteration), won $($summary.won)% of $($summary.fights) fights" } else { $summary.weights }
Write-Host "Published $Run to $target ($line)"

if ($Push) {

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    git -C $Root add -- "models/$Run"
    git -C $Root diff --cached --quiet -- "models/$Run"

    if ($LASTEXITCODE -eq 0) {

        Write-Host 'Nothing new to commit'
    }

    else {

        git -C $Root commit -q -m "Publish $Run's network: $line" -- "models/$Run"
        git -C $Root push -q
    }

    $ErrorActionPreference = $previous
}

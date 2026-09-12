# Puts networks on the same bench and prints what each won, which is the only way to compare two of them.
#
#   scripts\bench.ps1 -Run league-sharp -Last 4          the newest four checkpoints of a run, newest first
#   scripts\bench.ps1 -Run league-sharp -Iterations 6900,7100,8100
#   scripts\bench.ps1 -Weights models\league-sharp\best.mbw,models\vs-copy\best.mbw
#   scripts\bench.ps1 -Run league-sharp -Last 3 -Arenas 2000     tighter, four times as long
#
# Why this exists rather than reading a run's own eval.csv: a league run's win rate and rating are measured against
# opponents the matchmaking keeps changing, so the same network scores differently as the run goes on, and a rating wanders
# by thirty between checkpoints. This fights a fixed suite on ground that repeats, so two numbers can be subtracted.
#
# Two rules, both learned the hard way:
#   - **Keep -Workers the same** in every comparison. Each worker takes its own slice of the arenas, so 600 fights over one
#     worker are not the 600 over three. One worker is the default because it also fits beside a training run.
#   - 600 fights is about a point of repeatability, so five points mean something and one does not. Use -Arenas 2000 to
#     halve that, at four times the wall clock.
#
# Each network is copied aside before it is fought, because a run keeps only its last few weight files and prunes the rest
# while this is running.

param(
    [string] $Run = '',
    [int] $Last = 0,
    [int[]] $Iterations = @(),
    [string[]] $Weights = @(),
    [int] $Arenas = 600,
    [int] $Workers = 1,
    [ValidateSet('terrain', 'arena', 'league')] [string] $Suite = 'league',
    [string[]] $Loadouts = @(),
    [int] $Ground = 0
)

. "$PSScriptRoot\_common.ps1"

$scratch = Join-Path ([IO.Path]::GetTempPath()) ('mmai-bench-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $scratch | Out-Null

try {

    $entries = @()

    foreach ($path in $Weights) {

        $file = (Resolve-Path $path -ErrorAction SilentlyContinue).Path

        if (-not $file) {

            throw "No weights at $path"
        }

        $entries += [pscustomobject]@{ name = Split-Path (Split-Path $file -Parent) -Leaf; file = $file }
    }

    if ($Run) {

        $folder = Join-Path (Get-RunDirectory $Run) 'weights'
        $all = @(Get-ChildItem $folder -Filter '*.mbw' -ErrorAction SilentlyContinue | Sort-Object Name)

        if ($all.Count -eq 0) {

            throw "No weight files in $folder"
        }

        $wanted = if ($Iterations.Count -gt 0) {

            $Iterations | ForEach-Object { $want = '{0:D6}.mbw' -f $_; $all | Where-Object { $_.Name -eq $want } }
        }

        else {

            $all | Select-Object -Last ([Math]::Max($Last, 1))
        }

        foreach ($one in $wanted) {

            $entries += [pscustomobject]@{ name = "$Run $([IO.Path]::GetFileNameWithoutExtension($one.Name))"; file = $one.FullName }
        }
    }

    if ($entries.Count -eq 0) {

        throw 'Nothing to bench: give -Run with -Last or -Iterations, or -Weights'
    }

    Write-Host "Benching $($entries.Count) network$(if ($entries.Count -ne 1) { 's' }) over $Arenas fights of the $Suite suite, $Workers worker$(if ($Workers -ne 1) { 's' }) each"

    $results = @()

    foreach ($entry in $entries) {

        # Copied aside first: a run prunes all but its last few weight files, and the ones being benched are exactly the
        # ones it is about to prune.
        $copy = Join-Path $scratch ((Split-Path $entry.file -Leaf) + '.' + $results.Count)
        Copy-Item $entry.file $copy -Force

        # A hashtable, not a list: splatting a list hands the values over positionally, and eval.ps1's second position is
        # the iteration.
        $arguments = @{ Weights = $copy; Suite = $Suite; Arenas = $Arenas; Workers = $Workers }

        if ($Loadouts.Count -gt 0) {

            $arguments.Loadouts = $Loadouts
        }

        if ($Ground -ne 0) {

            $arguments.Ground = $Ground
        }

        $output = & "$PSScriptRoot\eval.ps1" @arguments 2>&1
        $line = $output | Select-String '^\s+won\s+\d+' | Select-Object -First 1

        if (-not $line) {

            Write-Host ($output | Select-Object -Last 10)
            throw "No result from $($entry.name); its output is above"
        }

        $won = [double]([regex]::Match($line.Line, '\(\s*([\d.]+)%\)').Groups[1].Value)
        $results += [pscustomobject]@{ Network = $entry.name; Won = $won }

        Write-Host ('  {0,-34} {1,6:N1}%' -f $entry.name, $won)
    }

    Write-Host ''
    Write-Host "Best first, over $Arenas fights each:"

    $results | Sort-Object Won -Descending | ForEach-Object { Write-Host ('  {0,-34} {1,6:N1}%' -f $_.Network, $_.Won) }

    $spread = ($results | Measure-Object Won -Maximum).Maximum - ($results | Measure-Object Won -Minimum).Minimum

    if ($results.Count -gt 1 -and $spread -lt 2.0) {

        Write-Host ''
        Write-Host ("The spread is {0:N1} points, which is inside what this many fights can tell apart. Use -Arenas 2000, " -f $spread) -NoNewline
        Write-Host 'or call them equal.'
    }
}

finally {

    Remove-Item $scratch -Recurse -Force -ErrorAction SilentlyContinue
}

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
# Three rules, all learned the hard way:
#   - **Bench everything being compared in one call.** Back to back, one network over 600 fights measures within half a
#     point of itself -- the scripted fighter repeated to three hundredths of a point -- while across an evening the same
#     file measured 66.7% and then 60.5%, with every network in the sitting moving together. The worker sizing is measured from the machine's own throughput at startup, so a machine with four
#     training workers on it sizes differently from one with two, and different sizing means different fights. A number from
#     one sitting cannot be subtracted from a number in another, and a whole night's conclusions nearly were.
#   - **Keep -Workers the same** in every comparison, and one is not only for fitting beside a training run. On the league
#     the fights are spread evenly over the opponents *per worker*, and workers get through uneven shares of the total, so
#     several workers leave the opponent mix skewed by whichever of them ran fastest -- and an opponent is worth anything
#     from 0% (a warden) to 100% (a wolf). Measured on the scripted fighter over 600 league fights: three workers gave
#     77.8% and then 83.0%, one worker gave 79.00% and then 78.97%.
#   - 600 fights is about half a point of repeatability within a sitting, so three points mean something and one does not.
#     Use -Arenas 2000 to halve it, at four times the wall clock.
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

        # Named after the folder it came from: models\vs-copy\best.mbw is "vs-copy". A run's own weights all sit in a folder
        # called weights under files all called by their iteration, so three copies benched together all read "weights"
        # until this named them by the run and the iteration instead: "copy770 000000".
        $parent = Split-Path (Split-Path $file -Parent) -Leaf
        $label = if ($parent -eq 'weights') { "$(Split-Path (Split-Path (Split-Path $file -Parent) -Parent) -Leaf) $([IO.Path]::GetFileNameWithoutExtension($file))" } else { $parent }

        $entries += [pscustomobject]@{ name = $label; file = $file }
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

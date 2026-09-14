# Puts networks on the same bench and prints what each won, which is the only way to compare two of them.
#
#   scripts\bench.ps1 -Run blast -Last 4                 the newest four checkpoints of a run, newest first
#   scripts\bench.ps1 -Run blast -Iterations 1900,2050,2150
#   scripts\bench.ps1 -Weights models\blast\best.mbw,runs\blast\weights\002000.mbw
#   scripts\bench.ps1 -Run blast -Last 3 -Arenas 2000    tighter, four times as long
#   scripts\bench.ps1 -Run blast -Last 2 -Teacher        and the scripted fighter as one more row, in the same sitting
#   scripts\bench.ps1 -Weights models\blast6\best.mbw -Teacher -Heap 1G    beside a live run, which needs the memory
#   scripts\bench.ps1 -Weights models\blast6\best.mbw -Teacher -Bystanders 0   the plain one-on-one league, no crowds
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
#   - **-Teacher belongs in the same call, not in a second one.** A win rate on its own says nothing -- the league's roster
#     holds wardens and evokers -- so the scripted fighter is the scale every other row is read against, and its own reads
#     span 78.2 to 81.5% across sittings. Asking for it separately is exactly the mistake the first rule is about, so it is
#     a switch here and a row like any other.
#
# Every network is copied aside before the first fight, because a run keeps only its last few weight files and prunes the
# rest while this is running.

[CmdletBinding()]
param(
    [string] $Run = '',
    [int] $Last = 0,
    [int[]] $Iterations = @(),
    [string[]] $Weights = @(),
    [int] $Arenas = 600,
    [int] $Workers = 1,

    # The worker's heap, forwarded to eval.ps1. A bench beside a live training run is the case this is for: the run stops
    # itself when the machine falls under 1.5 GB free, and a worker on the terrain library holds about 520 MB live and runs
    # in a gigabyte, which is the build's own default for that suite. Whatever it is, it is the same for every row.
    [string] $Heap = '1280M',
    [ValidateSet('terrain', 'arena', 'league')] [string] $Suite = 'league',
    [string[]] $Loadouts = @(),

    # Only these opponents, by the names the league writes in its results (zombie, 2x_zombie, zombie(hard)), forwarded to
    # eval.ps1. A bench that names none fights the whole roster -- and a bench that names some and is not heard fights the
    # whole roster too, which is why this script binds its parameters strictly: an argument it does not know is refused
    # rather than swallowed, after one sitting measured "packs of zombies" over evokers and wardens for exactly that reason.
    [string[]] $Opponents = @(),
    [int] $Ground = 0,

    # What share of the fights stands a crowd about them, forwarded to eval.ps1; empty for the build's own default of a
    # quarter. `-Bystanders 0` is the plain one-on-one bench, which is how to ask whether a gap is the crowd or the fight.
    [string] $Bystanders = '',

    # What share of the one-mob fights are packs of it, forwarded to eval.ps1; empty for the build's own tenth.
    # `-HostileCrowds 1` is the pack bench: every one-mob fight a pack, read by size in each fighter's `+N_pack` rows.
    [string] $HostileCrowds = '',

    # The scripted fighter as one more row of this bench, measured first so the live lines have their scale from the start.
    [switch] $Teacher
)

. "$PSScriptRoot\_common.ps1"

$scratch = Join-Path ([IO.Path]::GetTempPath()) ('mmai-bench-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $scratch | Out-Null

# Where each fighter's whole evaluation table is kept once the sitting is over: the scratch above goes with the copies, and
# a table of how it went against each opponent is exactly the record a sitting is worth keeping for. Under runs\, which
# is machine-local and not in git, named by the moment the sitting began.
$kept = Join-Path $Runs ('bench-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $kept | Out-Null

try {

    $entries = @()

    # First, so that every line after it is read against it. Nothing to copy aside: the scripted fighter is in the build.
    if ($Teacher) {

        $entries += [pscustomobject]@{ name = 'the scripted fighter'; file = '' }
    }

    foreach ($path in $Weights) {

        $file = (Resolve-Path $path -ErrorAction SilentlyContinue).Path

        if (-not $file) {

            throw "No weights at $path"
        }

        # Named after the folder it came from: models\blast\best.mbw is "blast". A run's own weights all sit in a folder
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

        throw 'Nothing to bench: give -Run with -Last or -Iterations, or -Weights, or -Teacher'
    }

    # Every network is copied aside now, before the first fight, and not when its turn comes round: a run keeps only its
    # last few weight files and prunes the rest, and the ones being benched are exactly the ones it is about to prune. Taking
    # the copy at the fight lost a live run's newest checkpoint an hour after it was named -- three fights of a four-row
    # sitting is minutes, and 027471.mbw was gone by the third.
    $copied = 0

    foreach ($entry in $entries) {

        if ($entry.file) {

            $copy = Join-Path $scratch ((Split-Path $entry.file -Leaf) + '.' + $copied)
            Copy-Item $entry.file $copy -Force

            $entry | Add-Member -NotePropertyName copy -NotePropertyValue $copy
            $copied++
        }
    }

    Write-Host "Benching $($entries.Count) fighter$(if ($entries.Count -ne 1) { 's' }) over $Arenas fights of the $Suite suite, $Workers worker$(if ($Workers -ne 1) { 's' }) each"

    $results = @()

    foreach ($entry in $entries) {

        # A hashtable, not a list: splatting a list hands the values over positionally, and eval.ps1's second position is
        # the iteration.
        $arguments = @{ Suite = $Suite; Arenas = $Arenas; Workers = $Workers; Heap = $Heap }

        if ($entry.file) {

            $arguments.Weights = $entry.copy
        }

        else {

            $arguments.Teacher = $true
        }

        if ($Loadouts.Count -gt 0) {

            $arguments.Loadouts = $Loadouts
        }

        if ($Opponents.Count -gt 0) {

            $arguments.Opponents = $Opponents
        }

        if ($Bystanders -ne '') {

            $arguments.Bystanders = $Bystanders
        }

        if ($HostileCrowds -ne '') {

            $arguments.HostileCrowds = $HostileCrowds
        }

        if ($Ground -ne 0) {

            $arguments.Ground = $Ground
        }

        $output = & "$PSScriptRoot\eval.ps1" @arguments 2>&1

        # The whole of what the evaluation printed, kept beside the copies: the table of how it went against each opponent
        # is the part a win rate cannot say, and a sitting whose only record was one number per fighter had to be run again.
        $table = Join-Path $kept (($entry.name -replace '[^\w.-]', '_') + '.txt')
        $output | ForEach-Object { "$_" } | Set-Content $table -Encoding utf8

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
    Write-Host "Best first, over $Arenas fights each (each fighter's whole table is kept under $kept):"

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

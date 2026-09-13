# A live view of the training runs for a second terminal: one wide line per run, redrawn in place. Only reads files, so
# it can be started and stopped at any time without touching a run.
#
#   scripts\watch.ps1                          every run that is training now, and any that stopped in the last hours
#   scripts\watch.ps1 -Run blast               just that run; -Run blast,other for several
#   scripts\watch.ps1 -Once                    print once and exit
#
# Per run: what the trainer is doing and with how many workers, the iteration, fights and game ticks a second over the
# last minute, the training win rate over the last 25 iterations with its trend and a sparkline of the last 60 (training
# explores, so it sits below what the weights can do), the latest evaluation as won / lost / timed out, and the best
# evaluation so far, which is the one kept as best.mbw. The last line adds the runs up.

param(
    [string[]] $Run = @(),
    [int] $Every = 5,
    [switch] $Once
)

. "$PSScriptRoot\_common.ps1"

$ErrorActionPreference = 'Continue'

# Written as code points: Windows PowerShell reads a script without a byte order mark as the local code page.
$Blocks = [char[]](0x2581, 0x2582, 0x2583, 0x2584, 0x2585, 0x2586, 0x2587, 0x2588)
$Line = [string][char]0x2500
$Divider = ' ' + [char]0x2502 + ' '

# A run stays on screen this long after its trainer stops, so a run that finished is seen to have finished.
$RecentHours = 3

$IterationPattern = '^(?<time>\d\d:\d\d:\d\d)\s+\S+\s+iteration\s+(?<iteration>\d+)\s+steps\s+(?<steps>[\d,]+)\s+episodes\s+(?<episodes>\d+)\s+win\s+(?<win>[\d.]+)%'

function Test-TrainerAlive([string] $Name) {

    $file = Join-Path $Root "mod\fabric\build\training\$Name-trainer.txt"

    # A crash can leave the file holding nothing readable, which just means nothing is running.
    $id = "$(Get-Content $file -Raw -ErrorAction SilentlyContinue)".Trim([char]0, ' ', "`r", "`n", "`t")

    return ($id -match '^\d+$') -and [bool](Get-Process -Id ([int]$id) -ErrorAction SilentlyContinue)
}

function Get-RunNames {

    if ($Run.Count -gt 0) {

        return @($Run | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
    }

    $cutoff = (Get-Date).AddHours(-$RecentHours)
    $known = @(Get-ChildItem $Runs -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName 'trainer.status') })
    $shown = @($known | Where-Object { (Test-TrainerAlive $_.Name) -or (Get-Item (Join-Path $_.FullName 'trainer.status')).LastWriteTime -gt $cutoff })

    if ($shown.Count -eq 0) {

        $shown = @($known | Sort-Object { (Get-Item (Join-Path $_.FullName 'trainer.status')).LastWriteTime } -Descending | Select-Object -First 3)
    }

    return @($shown | Sort-Object Name | ForEach-Object { $_.Name })
}

# Seconds from one log time of day to a later one, across midnight if need be.
function Get-Seconds([TimeSpan] $From, [TimeSpan] $To) {

    $seconds = ($To - $From).TotalSeconds
    return $(if ($seconds -lt 0) { $seconds + 86400 } else { $seconds })
}

function Get-RunView([string] $Name, $Workers) {

    $directory = Get-RunDirectory $Name
    $alive = Test-TrainerAlive $Name
    $status = "$(Get-Content (Join-Path $directory 'trainer.status') -ErrorAction SilentlyContinue | Select-Object -First 1)"
    $finished = "$(Get-Content (Join-Path $directory 'finished') -ErrorAction SilentlyContinue)".Trim()

    $view = [ordered]@{
        Name = $Name; State = 'stopped'; Workers = @($Workers | Where-Object { $_.CommandLine -match ([regex]::Escape($directory) + '(?![\w-])') }).Count
        Iteration = ''; FightsPerSecond = $null; TicksPerSecond = $null; Win = $null; Trend = $null; Spark = ''
        Evaluated = $null; Best = $null; Note = ''; Warning = ''
    }

    if ($finished) { $view.State = 'done'; $view.Note = "done: $finished" }
    elseif (-not $alive) { $view.State = 'stopped' }
    elseif ($status -match '^waiting') { $view.State = 'playing' }
    elseif ($status -match '^training') { $view.State = 'learning' }
    else { $view.State = 'starting' }

    $log = Get-ChildItem (Join-Path $directory 'logs') -Filter 'train-*.log' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
    $text = if ($log) { @(Get-Content $log.FullName -Tail 1500 -ErrorAction SilentlyContinue) } else { @() }
    $rows = @($text | ForEach-Object { if ($_ -match $IterationPattern) { [pscustomobject]@{
        Time = [TimeSpan]$Matches.time; Iteration = [int]$Matches.iteration; Steps = [long]($Matches.steps -replace ',', '')
        Episodes = [int]$Matches.episodes; Win = [double]$Matches.win } } })

    if ($rows.Count -gt 0) {

        $last = $rows[-1]
        $view.Iteration = $last.Iteration

        # Pace over the last minute or so of iterations, and never less than the last two.
        $window = @($rows | Where-Object { (Get-Seconds $_.Time $last.Time) -le 60 })
        if ($window.Count -lt 2) { $window = @($rows | Select-Object -Last 2) }

        if ($window.Count -ge 2 -and $alive) {

            $seconds = Get-Seconds $window[0].Time $last.Time

            if ($seconds -gt 0) {

                $view.TicksPerSecond = ($last.Steps - $window[0].Steps) / $seconds
                $view.FightsPerSecond = (($window | Select-Object -Skip 1 | Measure-Object Episodes -Sum).Sum) / $seconds
            }
        }

        $recent = @($rows | Select-Object -Last 25 | Where-Object { $_.Episodes -gt 0 })
        $before = @($rows | Select-Object -Last 50 | Select-Object -First 25 | Where-Object { $_.Episodes -gt 0 })

        if ($recent.Count -gt 0) {

            $view.Win = ($recent | Measure-Object Win -Average).Average

            if ($rows.Count -ge 50 -and $before.Count -gt 0) {

                $view.Trend = $view.Win - ($before | Measure-Object Win -Average).Average
            }
        }

        # Twelve marks, each the average of five iterations, scaled to their own spread but never to less than five
        # points, so noise in a steady run stays small.
        $marks = @()
        $tail = @($rows | Select-Object -Last 60)

        for ($i = 0; $i -lt $tail.Count; $i += 5) {

            $marks += (@($tail[$i..([Math]::Min($i + 4, $tail.Count - 1))]) | Measure-Object Win -Average).Average
        }

        if ($marks.Count -gt 0) {

            $low = ($marks | Measure-Object -Minimum).Minimum
            $high = [Math]::Max(($marks | Measure-Object -Maximum).Maximum, $low + 5)
            $view.Spark = -join ($marks | ForEach-Object { $Blocks[[Math]::Min(7, [int][Math]::Floor(8 * ($_ - $low) / ($high - $low + 1e-9)))] })
        }

        # Anything the trainer complained about in its last few hundred lines.
        $trouble = $text | Select-Object -Last 300 | Where-Object { $_ -match 'WARNING|ERROR|Traceback|drifted|failed' } | Select-Object -Last 1
        if ($trouble) { $view.Warning = "$trouble".Trim() }
    }

    $table = Join-Path $directory 'eval.csv'
    $evaluated = @(if (Test-Path $table) { Import-Csv $table })

    if ($evaluated.Count -gt 0) {

        $view.Evaluated = $evaluated[-1]
        $view.Best = $evaluated | Where-Object { $_.best -eq '1' } | Select-Object -Last 1
    }

    $target = "$(Get-Content (Join-Path $directory 'eval\target') -ErrorAction SilentlyContinue)".Trim()

    if ($target -and -not $view.Note) {

        $view.Note = "evaluating $target"
    }

    return [pscustomobject]$view
}

# Percentages of an evaluation row: won, lost and timed out.
function Get-Split($Row) {

    $fights = [Math]::Max(1, [int]$Row.fights)
    $lost = [int]$Row.fights - [int]$Row.wins - [int]$Row.timeouts

    return @((100.0 * [int]$Row.wins / $fights), (100.0 * $lost / $fights), (100.0 * [int]$Row.timeouts / $fights))
}

function Format-Number($Value, [string] $Format) {

    return $(if ($null -eq $Value) { '-' } else { $Format -f $Value })
}

# A screen is a list of lines, each a list of pieces: some text and the colour to write it in.
function New-Line { return ,(New-Object System.Collections.Generic.List[object]) }

function Add-Piece($Pieces, [string] $Text, [string] $Colour = 'Gray') { $Pieces.Add(@($Text, $Colour)) }

function Get-Screen {

    $screen = New-Object System.Collections.Generic.List[object]

    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
    $machine = 'CPU {0,3:N0}%   RAM {1:N1} of {2:N1} GB free' -f $cpu, ($os.FreePhysicalMemory / 1MB), ($os.TotalVisibleMemorySize / 1MB)

    if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {

        $gpu = (nvidia-smi --query-gpu=utilization.gpu,temperature.gpu,memory.used --format=csv,noheader,nounits 2>$null) -split ',\s*'
        if ($gpu.Count -ge 3) { $machine += '   GPU {0}% {1}C {2:N1} GB' -f $gpu[0], $gpu[1], ([double]$gpu[2] / 1024) }
    }

    $top = New-Line
    Add-Piece $top 'Modular Mob AI' 'White'
    Add-Piece $top ('   {0:HH:mm:ss}   ' -f (Get-Date)) 'DarkGray'
    Add-Piece $top $machine 'DarkGray'
    $screen.Add($top)

    $names = Get-RunNames

    if ($names.Count -eq 0) {

        $empty = New-Line
        Add-Piece $empty 'No runs yet. Start one with scripts\train.ps1' 'Yellow'
        $screen.Add($empty)
        return ,$screen
    }

    $workers = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -match 'gametest\.shardIndex' })
    $views = @($names | ForEach-Object { Get-RunView $_ $workers })

    $header = '{0,-14} {1,-8} {2,3} {3,6} {4,9} {5,9}' -f 'run', 'state', 'wk', 'iter', 'fights/s', 'ticks/s'
    $header += $Divider + ('{0,-12} {1,6} {2,6}' -f 'win, last 60', 'win%', 'trend')
    $header += $Divider + ('{0,-8} {1,6} {2,6} {3,6}' -f 'eval', 'won', 'lost', 't/o')
    $header += $Divider + 'best'
    $rule = $Line * $header.Length

    foreach ($text in $header, $rule) {

        $line = New-Line
        Add-Piece $line $text 'DarkGray'
        $screen.Add($line)
    }

    foreach ($view in $views) {

        $stateColour = switch ($view.State) { 'playing' { 'Green' } 'learning' { 'Cyan' } 'done' { 'Green' } 'starting' { 'Yellow' } default { 'DarkGray' } }
        $trendColour = if ($null -eq $view.Trend) { 'DarkGray' } elseif ($view.Trend -ge 0) { 'Green' } else { 'Red' }

        $line = New-Line
        Add-Piece $line ('{0,-14} ' -f $view.Name) 'White'
        Add-Piece $line ('{0,-8} ' -f $view.State) $stateColour
        Add-Piece $line ('{0,3} {1,6} {2,9} {3,9}' -f $view.Workers, $view.Iteration, (Format-Number $view.FightsPerSecond '{0:N1}'), (Format-Number $view.TicksPerSecond '{0:N0}'))
        Add-Piece $line $Divider 'DarkGray'
        Add-Piece $line ('{0,-12}' -f $view.Spark) 'DarkCyan'
        Add-Piece $line (' {0,6}' -f (Format-Number $view.Win '{0:N1}'))
        Add-Piece $line (' {0,6}' -f (Format-Number $view.Trend '{0:+0.0;-0.0}')) $trendColour
        Add-Piece $line $Divider 'DarkGray'

        if ($view.Evaluated) {

            $split = Get-Split $view.Evaluated
            Add-Piece $line ('{0,-8} ' -f ('it ' + $view.Evaluated.iteration))
            Add-Piece $line ('{0,6:N1} ' -f $split[0]) 'Green'
            Add-Piece $line ('{0,6:N1} ' -f $split[1]) 'Red'
            Add-Piece $line ('{0,6:N1}' -f $split[2]) 'Yellow'
        }

        else {

            Add-Piece $line ('{0,-8} {1,6} {2,6} {3,6}' -f 'none yet', '', '', '') 'DarkGray'
        }

        Add-Piece $line $Divider 'DarkGray'

        if ($view.Best) {

            Add-Piece $line ('{0:N1}% it {1}' -f (100.0 * [double]$view.Best.win_rate), $view.Best.iteration) 'Yellow'
        }

        if ($view.Note) {

            Add-Piece $line ('   ' + $view.Note) $(if ($view.State -eq 'done') { 'Green' } else { 'DarkGray' })
        }

        $screen.Add($line)
    }

    if ($views.Count -gt 1) {

        $running = @($views | Where-Object { $_.State -ne 'stopped' -and $null -ne $_.TicksPerSecond })
        $fights = if ($running.Count -gt 0) { ($running | Measure-Object FightsPerSecond -Sum).Sum } else { $null }
        $ticks = if ($running.Count -gt 0) { ($running | Measure-Object TicksPerSecond -Sum).Sum } else { $null }

        $line = New-Line
        Add-Piece $line $rule 'DarkGray'
        $screen.Add($line)

        $line = New-Line
        Add-Piece $line ('{0,-14} {1,-8} {2,3} {3,6} {4,9} {5,9}' -f 'all runs', '', (($views | Measure-Object Workers -Sum).Sum), '',
                (Format-Number $fights '{0:N1}'), (Format-Number $ticks '{0:N0}')) 'White'
        $screen.Add($line)
    }

    foreach ($view in $views | Where-Object { $_.Warning }) {

        $line = New-Line
        Add-Piece $line ('{0,-14} ' -f $view.Name) 'DarkGray'
        Add-Piece $line $view.Warning 'Yellow'
        $screen.Add($line)
    }

    return ,$screen
}

function Get-Width {

    try { return [Math]::Max(40, $Host.UI.RawUI.WindowSize.Width - 1) } catch { return 220 }
}

# Writes a screen from the top left, each line cut to the window and padded out, so a line that got shorter leaves
# nothing behind and nothing ever wraps; then blanks whatever the last screen had below this one.
function Write-Screen($Screen, [int] $Previous, [bool] $InPlace) {

    $width = Get-Width

    if ($InPlace) { [Console]::SetCursorPosition(0, 0) }

    foreach ($pieces in $Screen) {

        $left = $width

        foreach ($piece in $pieces) {

            if ($left -le 0) { break }

            $text = [string]$piece[0]
            if ($text.Length -gt $left) { $text = $text.Substring(0, $left) }

            Write-Host $text -ForegroundColor $piece[1] -NoNewline
            $left -= $text.Length
        }

        Write-Host $(if ($InPlace) { ' ' * [Math]::Max(0, $left) } else { '' })
    }

    for ($i = $Screen.Count; $i -lt $Previous; $i++) {

        Write-Host (' ' * $width)
    }
}

if ($Once) {

    Write-Screen (Get-Screen) 0 $false
    return
}

Clear-Host
$shown = 0

try {

    [Console]::CursorVisible = $false

    while ($true) {

        $screen = Get-Screen
        Write-Screen $screen $shown $true
        $shown = $screen.Count

        Start-Sleep -Seconds $Every
    }
}

finally {

    [Console]::CursorVisible = $true
}

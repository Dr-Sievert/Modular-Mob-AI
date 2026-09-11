# A live view of a training run, for a second terminal. Only reads files, so it can be started and stopped at any time
# without touching the run.
#
#   scripts\watch.ps1                  refresh every 3 seconds until Ctrl+C
#   scripts\watch.ps1 -Run wide
#   scripts\watch.ps1 -Once            print once and exit

param(
    [string] $Run = 'default',
    [int] $Every = 3,
    [int] $Rows = 15,
    [switch] $Once
)

. "$PSScriptRoot\_common.ps1"

$ErrorActionPreference = 'Continue'
$directory = Get-RunDirectory $Run

$pattern = 'iteration\s+(?<iteration>\d+)\s+steps\s+(?<steps>[\d,]+)\s+episodes\s+(?<episodes>\d+)\s+win\s+(?<win>[\d.]+)%\s+' +
        'return\s+(?<return>\S+)\s+length\s+(?<length>\S+)\s+\|\s+pi\s+(?<pi>\S+)\s+v\s+(?<v>\S+)\s+ent\s+(?<ent>\S+)\s+' +
        'clip\s+(?<clip>\S+)\s+kl\s+(?<kl>\S+)\s+ep\s+(?<ep>\d+)\s+\|\s+drift\s+(?<drift>\S+)\s+(?<device>\w+)\s+(?<seconds>[\d.]+)s'

function Show-Run {

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add(("Modular Mob AI - run '{0}' - {1}" -f $Run, (Get-Date -Format 'HH:mm:ss')))
    $lines.Add('')

    if (-not (Test-Path $directory)) {

        $lines.Add("No run folder at $directory yet. Start one with scripts\train.ps1")
        return $lines
    }

    # What the trainer says it is doing, and whether it is still there to say it.
    $pidFile = Join-Path $Root 'mod\fabric\build\training-process-pid.txt'
    $alive = $false

    # A crash can leave the file holding nothing readable, which just means nothing is running.
    $trainerPid = "$(Get-Content $pidFile -Raw -ErrorAction SilentlyContinue)".Trim([char]0, ' ', "`r", "`n", "`t")

    if ($trainerPid -match '^\d+$') {

        $alive = [bool](Get-Process -Id ([int]$trainerPid) -ErrorAction SilentlyContinue)
    }

    $status = Get-Content (Join-Path $directory 'trainer.status') -ErrorAction SilentlyContinue | Select-Object -First 1

    if ($status -match '^(\w+) (\d+) (\d+)') {

        $what = if ($Matches[1] -eq 'waiting') { 'collecting experience for' } else { 'learning from' }
        $lines.Add(("Trainer   {0}, {1} iteration {2}, {3} rounds finished" -f ($(if ($alive) { 'running' } else { 'not running' })), $what, $Matches[2], $Matches[3]))
    }

    else {

        $lines.Add(("Trainer   {0}" -f ($(if ($alive) { 'starting' } else { 'not running' }))))
    }

    # Each worker publishes how many of its arenas are done.
    $progress = @(Get-ChildItem $WorkerDirectory -Filter 'durations.txt.progress' -Recurse -ErrorAction SilentlyContinue)

    if ($progress.Count -gt 0) {

        $parts = foreach ($file in $progress | Sort-Object FullName) {

            $numbers = (Get-Content $file.FullName -ErrorAction SilentlyContinue) -split '\s+'
            if ($numbers.Count -ge 2) { '{0} {1}/{2}' -f $file.Directory.Name.Replace('worker-', 'w'), $numbers[0], $numbers[1] }
        }

        $lines.Add('Workers   ' + ($parts -join '   '))
    }

    $workerProcesses = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -match 'gametest\.shardIndex' })
    $lines.Add(('          {0} Minecraft worker processes alive' -f $workerProcesses.Count))

    # The machine, since this is what has to stay healthy for a run to last.
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
    $machine = 'Machine   CPU {0,3:N0}%   RAM free {1:N1} of {2:N1} GB' -f $cpu, ($os.FreePhysicalMemory / 1MB), ($os.TotalVisibleMemorySize / 1MB)

    if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {

        $gpu = (nvidia-smi --query-gpu=utilization.gpu,temperature.gpu,memory.used --format=csv,noheader,nounits 2>$null) -split ',\s*'

        if ($gpu.Count -ge 3) {

            $machine += '   GPU {0}% {1}C {2} MB' -f $gpu[0], $gpu[1], $gpu[2]
        }
    }

    $lines.Add($machine)
    $lines.Add('')

    # The trainer's own log: one line per iteration, which is where learning shows up.
    $log = Get-ChildItem (Join-Path $directory 'logs') -Filter 'train-*.log' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime | Select-Object -Last 1

    if (-not $log) {

        $lines.Add('No trainer log yet.')
        return $lines
    }

    $text = Get-Content $log.FullName -Tail 4000 -ErrorAction SilentlyContinue
    $iterations = @($text | ForEach-Object { if ($_ -match $pattern) { [pscustomobject]$Matches } })

    if ($iterations.Count -eq 0) {

        $lines.Add('No iterations finished yet; the first one needs every worker to boot and fight for a while.')
    }

    else {

        $lines.Add(('{0,9} {1,12} {2,6} {3,6} {4,8} {5,7} {6,7} {7,7} {8,8} {9,6}' -f 'iteration', 'steps', 'fights', 'win %', 'return', 'length', 'entropy', 'kl', 'drift', 'secs'))

        foreach ($row in $iterations | Select-Object -Last $Rows) {

            $lines.Add(('{0,9} {1,12} {2,6} {3,6} {4,8} {5,7} {6,7} {7,7} {8,8} {9,6}' -f $row.iteration, $row.steps, $row.episodes, $row.win, $row.return, $row.length, $row.ent, $row.kl, $row.drift, $row.seconds))
        }

        # Win rate is noisy iteration to iteration; the trend over the last few dozen is what says it is learning.
        $recent = @($iterations | Select-Object -Last 25 | Where-Object { [int]$_.episodes -gt 0 })
        $earlier = @($iterations | Select-Object -Last 50 | Select-Object -First 25 | Where-Object { [int]$_.episodes -gt 0 })

        if ($recent.Count -gt 0) {

            $now = ($recent | ForEach-Object { [double]$_.win } | Measure-Object -Average).Average
            $trend = ''

            if ($earlier.Count -gt 0 -and $iterations.Count -ge 50) {

                $before = ($earlier | ForEach-Object { [double]$_.win } | Measure-Object -Average).Average
                $trend = ', {0:+0.0;-0.0} points on the 25 before' -f ($now - $before)
            }

            $lines.Add('')
            $lines.Add(('Win rate over the last 25 iterations: {0:N1}%{1}' -f $now, $trend))
        }
    }

    # Anything the trainer complained about recently.
    $trouble = @($text | Where-Object { $_ -match 'WARNING|ERROR|Traceback|drifted|failed' } | Select-Object -Last 3)

    if ($trouble.Count -gt 0) {

        $lines.Add('')
        $lines.Add('Recent warnings:')
        $trouble | ForEach-Object { $lines.Add('  ' + $_) }
    }

    return $lines
}

while ($true) {

    $screen = Show-Run

    if (-not $Once) {

        Clear-Host
    }

    $screen | ForEach-Object { Write-Host $_ }

    if ($Once) {

        break
    }

    Start-Sleep -Seconds $Every
}

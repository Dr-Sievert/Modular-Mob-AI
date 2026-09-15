# Stops training and anything it left behind: the trainers and every Minecraft worker the build started. Safe to run
# at any time; the weights and checkpoints already on disk are kept, and the next scripts\train.ps1 carries on.
#
#   scripts\stop.ps1                every run
#   scripts\stop.ps1 -Run imitate   just that one

param([string] $Run = '')

. "$PSScriptRoot\_common.ps1"

$ErrorActionPreference = 'Continue'
$stopped = 0

foreach ($loader in 'fabric', 'neoforge') {

    $build = Join-Path $Root "mod\$loader\build"

    # Training runs keep a trainer and a worker list each; plain parallel test runs keep one list.
    $files = if ($Run) {

        @("training\$Run-trainer.txt", "training\$Run-workers.txt")
    }

    else {

        @('gametest-parallel-pids.txt') + @(Get-ChildItem (Join-Path $build 'training') -Filter '*.txt' -ErrorAction SilentlyContinue |
                ForEach-Object { "training\$($_.Name)" })
    }

    foreach ($name in $files) {

        $file = Join-Path $build $name

        if (-not (Test-Path $file)) {

            continue
        }

        foreach ($line in Get-Content $file) {

            # A crash can leave the file holding nothing readable.
            $id = $line.Trim([char]0, ' ', "`t")

            if ($id -notmatch '^\d+$') {

                continue
            }

            $process = Get-Process -Id ([int]$id) -ErrorAction SilentlyContinue

            # Process ids get reused, so only ever stop one that is still Java or Python.
            if ($process -and $process.ProcessName -match '^(java|python)') {

                Stop-Process -Id $process.Id -Force
                Write-Host "Stopped $($process.ProcessName) $($process.Id)"
                $stopped++
            }
        }
    }
}

# The Gradle client driving a run, if it is still waiting on them. The build itself runs in Gradle's daemon, which
# cancels it once its client is gone and then stays up for the next one.
$pattern = if ($Run) { "(runTraining|recordDemonstrations).*-Prun=$Run(\s|$)" } else { 'runTraining|recordDemonstrations' }

Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'GradleWrapperMain' -and $_.CommandLine -match $pattern } | ForEach-Object {

    Stop-Process -Id $_.ProcessId -Force
    Write-Host "Stopped Gradle $($_.ProcessId)"
    $stopped++
}

Write-Host $(if ($stopped -eq 0) { 'Nothing was running.' } else { "Stopped $stopped processes." })

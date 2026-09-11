# Stops a training run and anything it left behind: the trainer and every Minecraft worker the build started. Safe to
# run at any time; the weights and checkpoints already on disk are kept, and the next scripts\train.ps1 carries on.

. "$PSScriptRoot\_common.ps1"

$ErrorActionPreference = 'Continue'
$stopped = 0

foreach ($loader in 'fabric', 'neoforge') {

    $build = Join-Path $Root "mod\$loader\build"

    foreach ($name in 'training-process-pid.txt', 'gametest-parallel-pids.txt') {

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

# The Gradle process driving the run, if it is still waiting on them.
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'GradleWrapperMain|GradleMain' -and $_.CommandLine -match 'runTraining' } | ForEach-Object {

    Stop-Process -Id $_.ProcessId -Force
    Write-Host "Stopped Gradle $($_.ProcessId)"
    $stopped++
}

Write-Host $(if ($stopped -eq 0) { 'Nothing was running.' } else { "Stopped $stopped processes." })

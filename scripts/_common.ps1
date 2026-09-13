# Shared by every script in this folder: where things live, and how to reach Java and Python. Dot-sourced, never run.

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Mod = Join-Path $Root 'mod'
$Gradle = Join-Path $Mod 'gradlew.bat'
$Python = Join-Path $Root 'trainer\.venv\Scripts\python.exe'
$Runs = Join-Path $Root 'runs'

# A git worktree has no trainer environment of its own, and setting one up per worktree is gigabytes of PyTorch for a
# checkout that lives a few hours. So a worktree borrows the main checkout's, found through git rather than guessed: the
# common directory of a worktree is the main checkout's .git, whose parent is the checkout.
#
# This exists because the alternative was done by hand four times over and then cost the environment itself. Each
# development worktree had a junction from its trainer\.venv to the real one, and a `robocopy /MIR` from an empty folder
# to clear a worktree out followed one of those junctions and mirrored the emptiness into the environment every run on the
# machine uses: torch, numpy and pyvenv.cfg gone in seconds. Nothing that walks a worktree can be trusted not to follow a
# reparse point, so the right answer is for there to be no reparse point to follow.
if (-not (Test-Path $Python)) {

    $common = & git -C $Root rev-parse --path-format=absolute --git-common-dir 2>$null

    if ($LASTEXITCODE -eq 0 -and $common) {

        $main = Split-Path -Parent $common.Trim()
        $borrowed = Join-Path $main 'trainer\.venv\Scripts\python.exe'

        if ($main -and $borrowed -ne $Python -and (Test-Path $borrowed)) {

            $Python = $borrowed
            Write-Host "No trainer environment in this worktree; using the one in $main"
        }
    }
}

# What setup.ps1 downloads when the machine has no Java 21 or Python of its own. Never in git.
$Tools = Join-Path $Root '.tools'

# The major version of the Java installed at a folder, from the release file every JDK carries; 0 for anything else.
function Get-JavaMajor([string] $Directory) {

    if (-not $Directory -or -not (Test-Path (Join-Path $Directory 'bin\java.exe'))) {

        return 0
    }

    $line = Select-String -Path (Join-Path $Directory 'release') -Pattern '^JAVA_VERSION="(\d+)' -ErrorAction SilentlyContinue |
            Select-Object -First 1

    if ($line) {

        return [int]$line.Matches[0].Groups[1].Value
    }

    return 0
}

# Minecraft 1.21.1 is built with Java 21 exactly. Looks in the order a machine is most likely to have it right: the copy
# setup.ps1 unpacked, then JAVA_HOME as this terminal, the user and the machine see it (a terminal opened before Java was
# installed still has the old environment), then whatever java is on the PATH.
function Find-Java21 {

    $candidates = @(Get-ChildItem $Tools -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName })

    $candidates += $env:JAVA_HOME
    $candidates += [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    $candidates += [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')

    $onPath = Get-Command java -ErrorAction SilentlyContinue

    if ($onPath) {

        $candidates += Split-Path -Parent (Split-Path -Parent $onPath.Source)
    }

    foreach ($candidate in $candidates) {

        if ((Get-JavaMajor $candidate) -eq 21) {

            return $candidate
        }
    }

    return $null
}

function Use-Java21 {

    $found = Find-Java21

    if (-not $found) {

        throw 'No Java 21 found. Run scripts\setup.ps1 first.'
    }

    $env:JAVA_HOME = $found
}

function Invoke-Gradle([string[]] $Arguments) {

    Use-Java21

    # Windows PowerShell turns every line a program writes to stderr into an error when output is redirected, and Gradle
    # writes compiler warnings there. The exit code is what says whether the build worked.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    # The build lives in mod/, and Gradle finds a build from the folder it is started in.
    & $Gradle -p $Mod @Arguments --console=plain

    $ErrorActionPreference = $previous

    if ($LASTEXITCODE -ne 0) {

        throw "gradlew $($Arguments -join ' ') failed"
    }
}

# Intel's 13th and 14th generation desktop chips degrade and crash under load on microcode older than 0x12B, and the fix
# only arrives with a BIOS update. This machine blue screened on exactly that, so every heavy script checks first.
function Test-MachineStability {

    $cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name

    if ($cpu -notmatch 'i[3579]-1[34]\d{3}') {

        return
    }

    $bytes = (Get-ItemProperty 'HKLM:\HARDWARE\DESCRIPTION\System\CentralProcessor\0').'Update Revision'
    $revision = [BitConverter]::ToUInt32($bytes, 4)

    if ($revision -lt 0x12B) {

        Write-Warning ("{0} is running microcode 0x{1:X}. Intel's stability fix needs 0x12B or newer, which comes with a " +
                "BIOS update. Until then heavy load can blue screen this machine; see docs\findings.md." -f $cpu.Trim(), $revision)
    }
}

function Get-RunDirectory([string] $Name) {

    return Join-Path $Runs $Name
}

# The folder of recorded teacher answers that -Demos names, or nothing when none of the four places holds any.
#
# A record can be named four ways and every caller accepts all four: a run's name, that run's demos folder, or either of
# those given as a path. Whichever of them is a folder with shards under it wins, nearest first, so a run called the same
# as a folder in the working directory cannot be taken for it. Resolved here so that both scripts that take -Demos agree
# about what a name means; each says its own thing about a name that leads nowhere, since one can offer to record the
# record and the other cannot.
function Find-TeacherRecord([string] $Named) {

    $fromName = Get-RunDirectory $Named

    $found = @((Join-Path $fromName 'demos'), $fromName, (Join-Path $Named 'demos'), $Named |
            Where-Object { Test-Path $_ -PathType Container } |
            Where-Object { @(Get-ChildItem $_ -Filter '*.mbr' -Recurse -ErrorAction SilentlyContinue).Count -gt 0 } |
            Select-Object -First 1)

    return $(if ($found) { (Resolve-Path $found).Path } else { '' })
}

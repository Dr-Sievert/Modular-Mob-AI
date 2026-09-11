# Shared by every script in this folder: where things live, and how to reach Java and Python. Dot-sourced, never run.

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Mod = Join-Path $Root 'mod'
$Gradle = Join-Path $Mod 'gradlew.bat'
$Python = Join-Path $Root 'trainer\.venv\Scripts\python.exe'
$Runs = Join-Path $Root 'runs'

# A terminal opened before Java was installed still has the old environment, so this looks further than JAVA_HOME.
function Use-Java21 {

    foreach ($candidate in @($env:JAVA_HOME, [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine'))) {

        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) {

            $env:JAVA_HOME = $candidate
            return
        }
    }

    $installed = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1

    if ($installed) {

        $env:JAVA_HOME = $installed.FullName
        return
    }

    throw 'No Java 21 found. Run scripts\setup.ps1 first.'
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
                "BIOS update. Until then heavy load can blue screen this machine; see docs\README.md, 'Machine stability'." -f $cpu.Trim(), $revision)
    }
}

function Get-RunDirectory([string] $Name) {

    return Join-Path $Runs $Name
}

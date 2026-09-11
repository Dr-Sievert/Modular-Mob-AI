# Everything a Windows machine needs, from nothing, in one go. Safe to run again: whatever is already there is kept.
#
#   scripts\setup.ps1              Java 21, Python, the trainer's environment with PyTorch, the mod compiled, parity checked
#   scripts\setup.ps1 -Cpu         PyTorch without CUDA, for a machine with no NVIDIA card or an old driver
#
# Nothing is installed system wide. A machine without Java 21 gets a JDK unpacked into .tools\; a machine without a
# usable Python gets one there too. Both are ignored by git, and deleting .tools\ undoes them.

param([switch] $Cpu)

. "$PSScriptRoot\_common.ps1"

# Windows PowerShell 5.1 turns anything a program writes to stderr into an error once it is redirected, and probing for a
# Python that is not there writes exactly that. Programs are judged by their exit codes here instead, and the commands
# that must not fail say so themselves.
$ErrorActionPreference = 'Continue'

# Windows PowerShell 5.1 still offers old TLS by default, and draws a progress bar that makes downloads crawl.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

$architecture = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'aarch64' } else { 'x64' }

function Save-Download([string] $Url, [string] $File) {

    Write-Host "  downloading $Url"
    New-Item -ItemType Directory -Force (Split-Path -Parent $File) -ErrorAction Stop | Out-Null
    Invoke-WebRequest -Uri $Url -OutFile $File -UseBasicParsing -ErrorAction Stop
}

# -----------------------------------------------------------------------------------------------------------------------
# Java 21
# -----------------------------------------------------------------------------------------------------------------------

Write-Host 'Java 21'

if (-not (Find-Java21)) {

    # The latest Temurin 21 JDK, as a zip that runs from wherever it is unpacked.
    $zip = Join-Path $Tools 'jdk-21.zip'
    Save-Download "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$architecture/jdk/hotspot/normal/eclipse?project=jdk" $zip
    Expand-Archive -Path $zip -DestinationPath $Tools -Force -ErrorAction Stop
    Remove-Item -LiteralPath $zip
}

Use-Java21
Write-Host "  $env:JAVA_HOME"

# -----------------------------------------------------------------------------------------------------------------------
# Python and the trainer's environment
# -----------------------------------------------------------------------------------------------------------------------

# PyTorch publishes wheels for these; a newer Python can go months without one.
$pythonVersions = @('3.12', '3.13', '3.11', '3.10')

# A command that starts a usable Python, as the program and the arguments that pick the version, or $null.
function Find-BasePython {

    $local = Join-Path $Tools 'python\python.exe'

    if (Test-Path $local) {

        return @($local)
    }

    $launcher = Get-Command py -ErrorAction SilentlyContinue

    if ($launcher) {

        foreach ($version in $pythonVersions) {

            & py "-$version" -c 'import sys' 2>$null

            if ($LASTEXITCODE -eq 0) {

                return @('py', "-$version")
            }
        }
    }

    # The python.exe Windows puts on the PATH before any is installed only opens the Store, and fails this check.
    $onPath = Get-Command python -ErrorAction SilentlyContinue

    if ($onPath) {

        # Windows PowerShell 5.1 drops double quotes inside arguments to programs, so the Python here uses single ones.
        $version = & python -c "import sys; print('%d.%d' % sys.version_info[:2])" 2>$null

        if ($LASTEXITCODE -eq 0 -and $pythonVersions -contains "$version".Trim()) {

            return @('python')
        }
    }

    return $null
}

Write-Host 'Python'

if (-not (Test-Path $Python)) {

    $base = Find-BasePython

    if (-not $base) {

        # The official installer, for this user only, into .tools\python: no PATH changes, no launcher, no admin.
        $installer = Join-Path $Tools 'python-installer.exe'
        Save-Download "https://www.python.org/ftp/python/3.12.10/python-3.12.10-$(if ($architecture -eq 'x64') { 'amd64' } else { 'arm64' }).exe" $installer

        $arguments = @('/quiet', 'InstallAllUsers=0', 'PrependPath=0', 'Include_launcher=0', 'Include_test=0',
                'Include_doc=0', 'Shortcuts=0', "TargetDir=$(Join-Path $Tools 'python')")
        Start-Process -FilePath $installer -ArgumentList $arguments -Wait -ErrorAction Stop
        Remove-Item -LiteralPath $installer

        $base = Find-BasePython

        if (-not $base) {

            throw 'Python could not be installed; install Python 3.12 from python.org and run this again.'
        }
    }

    Write-Host '  creating trainer\.venv'
    $program = $base[0]
    $rest = @($base | Select-Object -Skip 1)
    & $program @rest -m venv (Join-Path $Root 'trainer\.venv')

    if ($LASTEXITCODE -ne 0) {

        throw 'Creating the Python environment failed'
    }
}

& $Python -c "import sys; print('  ' + sys.version.split()[0], sys.executable)"
& $Python -m pip install --upgrade pip --quiet --disable-pip-version-check

# CUDA when there is an NVIDIA card to use it, unless asked not to.
$nvidia = [bool](Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'NVIDIA' })
$index = if ($nvidia -and -not $Cpu) { 'https://download.pytorch.org/whl/cu128' } else { 'https://download.pytorch.org/whl/cpu' }

Write-Host "PyTorch from $index"
& $Python -m pip install torch --index-url $index --quiet --disable-pip-version-check

if ($LASTEXITCODE -ne 0) {

    throw 'Installing PyTorch failed'
}

& $Python -m pip install -r (Join-Path $Root 'trainer\requirements.txt') --quiet --disable-pip-version-check

if ($LASTEXITCODE -ne 0) {

    throw 'Installing the trainer''s packages failed'
}

$torch = & $Python -c "import torch; print(torch.__version__, torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'cpu only')"
Write-Host "  torch $torch"

if ($nvidia -and -not $Cpu -and "$torch" -match 'cpu only') {

    Write-Warning 'There is an NVIDIA card but PyTorch cannot use it; the driver is probably too old for CUDA 12.8. Update it, or run setup with -Cpu.'
}

# -----------------------------------------------------------------------------------------------------------------------
# The mod, and proof both halves agree
# -----------------------------------------------------------------------------------------------------------------------

Test-MachineStability

# The first build downloads Minecraft and both loaders, which takes a few minutes once.
Write-Host 'Compiling the mod'
Invoke-Gradle @(':fabric:gametestClasses')

Write-Host 'Checking the game and PyTorch agree about the network'
Invoke-Gradle @(':fabric:brainParity')

$memory = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB
$cores = (Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfLogicalProcessors -Sum).Sum

Write-Host ''
Write-Host ('Ready: {0} logical cores, {1:N0} GB of memory, PyTorch {2}' -f $cores, $memory, $torch)
Write-Host 'Try scripts\test.ps1 -Terrain for a first look at some fights, then scripts\compare.ps1 or scripts\train.ps1.'

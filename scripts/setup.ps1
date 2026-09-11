# One time setup: Java 21, the trainer's Python environment with PyTorch, then the parity check to prove both work.
#
#   scripts\setup.ps1              CUDA build of PyTorch
#   scripts\setup.ps1 -Cpu         CPU only build, smaller, no NVIDIA driver involved

param([switch] $Cpu)

. "$PSScriptRoot\_common.ps1"

try {

    Use-Java21
}

catch {

    Write-Host 'Installing Java 21 (Temurin) with winget'
    winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-package-agreements --accept-source-agreements
    Use-Java21
}

Write-Host "Java: $env:JAVA_HOME"

if (-not (Test-Path $Python)) {

    Write-Host 'Creating trainer\.venv'

    if (Get-Command py -ErrorAction SilentlyContinue) {

        & py -3.12 -m venv (Join-Path $Root 'trainer\.venv')
    }

    else {

        & python -m venv (Join-Path $Root 'trainer\.venv')
    }
}

& $Python -m pip install --upgrade pip --quiet

if ($Cpu) {

    & $Python -m pip install torch --index-url https://download.pytorch.org/whl/cpu
}

else {

    & $Python -m pip install torch --index-url https://download.pytorch.org/whl/cu128
}

& $Python -m pip install -r (Join-Path $Root 'trainer\requirements.txt')
& $Python -c "import torch; print('torch', torch.__version__, '| cuda' if torch.cuda.is_available() else '| cpu only')"

Test-MachineStability

Write-Host 'Checking the game and PyTorch agree about the network'
Invoke-Gradle @(':fabric:brainParity')

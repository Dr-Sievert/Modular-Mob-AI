# Checks the game's forward pass against PyTorch's on the same weights and inputs, and times it. Takes seconds and
# boots nothing. Training runs this first by itself; run it by hand after touching anything in brain\nn or the model.

. "$PSScriptRoot\_common.ps1"

Invoke-Gradle @(':fabric:brainParity')

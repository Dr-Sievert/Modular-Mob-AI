# Checks the game's forward pass against PyTorch's on the same weights and inputs, and times it. Takes seconds and
# boots nothing. Training runs this first by itself; run it by hand after touching anything in brain\nn or the model.
#
#   scripts\parity.ps1                          every body, the ordinary network, and then the pooled one
#   scripts\parity.ps1 -Extra '--h1 512 --hidden 256 --h3 256'
#                                               a size of its own, which is also how the pass is timed at that size
#   scripts\parity.ps1 -Plain                   only the ordinary network, for a quick check of something unrelated
#
# The pooled pass is checked as well as the plain one because it is a different pass, not a different size: the enemy
# slots go through a shared encoder and a masked maximum before the first layer, which is arithmetic the plain pass never
# does. A mode nothing checks is a mode that breaks quietly, and a forward pass that disagrees with PyTorch is
# indistinguishable from "reinforcement learning is hard" -- which is the whole reason this check exists.

param(
    [string] $Extra = '',
    [switch] $Plain
)

. "$PSScriptRoot\_common.ps1"

# What the pooled arm is checked at. Sixteen is what a run that pools uses; the point is that pooling is on at all.
$SlotEncoding = 16

$arms = @(@{ Name = 'the ordinary network'; Args = $Extra })

if (-not $Plain -and $Extra -notmatch '--slot-enc') {

    $arms += @{ Name = "the pooled network, slots through $SlotEncoding"; Args = "$Extra --slot-enc $SlotEncoding".Trim() }
}

foreach ($arm in $arms) {

    Write-Host ""
    Write-Host "parity: $($arm.Name)"

    $arguments = @(':fabric:brainParity')

    if ($arm.Args) {

        $arguments += "-PparityArgs=$($arm.Args)"
    }

    Invoke-Gradle $arguments
}

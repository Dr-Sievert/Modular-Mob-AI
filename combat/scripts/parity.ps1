# Checks the game's forward pass against PyTorch's on the same weights and inputs, and times it. Takes seconds and
# boots nothing. Training runs this first by itself; run it by hand after touching anything in brain\nn or the model.
#
#   scripts\parity.ps1                          every body, the ordinary network, and then the attended one
#   scripts\parity.ps1 -Extra '--h1 512 --hidden 256 --h3 256'
#                                               a size of its own, which is also how the pass is timed at that size
#   scripts\parity.ps1 -Plain                   only the ordinary network, for a quick check of something unrelated
#
# The attended pass is checked as well as the plain one because it is a different pass, not a different size: each head
# scores every occupied enemy slot, softmaxes over them and over an empty token, and the slot it picked is taken away from
# the heads after it -- arithmetic the plain pass never does, with a tie rule and an exp both sides have to agree on. A
# mode nothing checks is a mode that breaks quietly, and a forward pass that disagrees with PyTorch is indistinguishable
# from "reinforcement learning is hard" -- which is the whole reason this check exists.

param(
    [string] $Extra = '',
    [switch] $Plain
)

. "$PSScriptRoot\_common.ps1"

# What the attended arm is checked at. Three is what the converter builds; the point is that attention is on at all, and
# that more than one head means the exclusion between them is exercised.
$SlotHeads = 3

$arms = @(@{ Name = 'the ordinary network'; Args = $Extra })

if (-not $Plain -and $Extra -notmatch '--slot-heads') {

    $arms += @{ Name = "the attended network, $SlotHeads heads over the slots"; Args = "$Extra --slot-heads $SlotHeads".Trim() }
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

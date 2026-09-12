# Brings a stopped training run up to date with a new schema id, so it can carry on rather than start again. A one-off:
# delete this and trainer\restamp_schema.py once every run folder has been through it.
#
#   scripts\restamp.ps1 -Run runs\league2 -WhatIf      say what would change, change nothing
#   scripts\restamp.ps1 -Run runs\league2              do it
#
# A layout became a body's own, so a layout now says which body it is for and the checksum of it changed. Nothing about
# what an input means moved: the same inputs in the same blocks at the same offsets, the same controls, the same heads, and
# the parity check's own figures are unchanged to four places. So a run's networks are still exactly the networks they
# were, and the only thing out of date is the number in each file that says which layout it was trained against.
#
# What gets stamped: state.pt and every checkpoints\*.pt, best.mbw and every weights\*.mbw, and every .mbr shard under
# rollouts\ and demos\, patched in place because a record runs to gigabytes. schema.json is left alone, since the build
# writes a fresh one whenever a run starts.
#
# Stop the run first. Nothing is written until every file has been looked at, and a folder holding a file of some third
# layout is refused untouched, because a run carrying two layouts is worse than a run carrying an old one. Running it twice
# is safe: the second time finds everything already stamped and changes nothing.
#
# The new id is worked out from models\vs-copy\schema.json, the layout published with the networks in git, so it cannot
# drift from what the game writes. -Schema points it at another one.

param(
    [Parameter(Mandatory = $true)] [string] $Run,
    [string] $Schema = '',
    [switch] $WhatIf,
    # Goes ahead on a run whose trainer.status was written moments ago. Only for a run you know is stopped, such as one
    # stopped seconds before.
    [switch] $Force
)

. "$PSScriptRoot\_common.ps1"

if (-not (Test-Path $Python)) {

    throw "No Python environment at $Python; run scripts\setup.ps1 first"
}

$folder = (Resolve-Path $Run).Path

if (-not $Schema) {

    $Schema = Join-Path $Root 'models\vs-copy\schema.json'
}

$Schema = (Resolve-Path $Schema).Path

# A run whose trainer is still alive would write its own files back under the old id the moment it next checkpointed.
$status = Join-Path $folder 'trainer.status'

if ((Test-Path $status) -and -not $Force -and -not $WhatIf) {

    $age = ((Get-Date) - (Get-Item $status).LastWriteTime).TotalSeconds

    if ($age -lt 120) {

        throw ("$folder looks like it is still training: its trainer.status was written {0:N0} s ago. " -f $age) +
                "Stop it with scripts\stop.ps1 -Run $(Split-Path $folder -Leaf) first, or pass -Force if you know it is stopped."
    }
}

$arguments = @((Join-Path $Root 'trainer\restamp_schema.py'), $folder, '--schema', $Schema)

if ($WhatIf) {

    $arguments += '--dry-run'
}

& $Python @arguments

if ($LASTEXITCODE -ne 0) {

    throw "Re-stamping $folder failed"
}

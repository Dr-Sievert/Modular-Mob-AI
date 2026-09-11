# Builds the terrain library: fight sites generated once and kept in runs\terrain\<minecraft version>\library, which
# every terrain worker then reads its sites from instead of generating ground. Generating was most of what a worker did
# besides fighting, two to three cores' worth, and the part generated ground around its sites was most of its memory;
# from the library a worker loads a site in a fraction of a second into a fraction of the memory, and never generates
# anything. Run it once per machine, and again for fresh ground; training picks it up by itself. Without a library,
# workers generate their own ground as before.
#
#   scripts\terrain.ps1                         4,096 sites on as much of the machine as fits, and 3.2 GB of disk
#   scripts\terrain.ps1 -Sites 8192 -Builders 6 more ground to go round, sooner, on a machine with the memory for it
#
# Training on natural ground needs a library: scripts\train.ps1 stops and says to build one. More sites is always better
# ground, at about 0.8 MB and a second and a half of one builder each, so the only limits are disk and patience; a builder
# needs its own 2.5 GB of memory. Every fight in a run is drawn from the whole library, so 4,096 sites is thousands of
# fights before any site is seen twice.
#
# Vanilla generates most of a chunk one task at a time, so one builder gets through about half a site a second however
# many cores are free; each builder is a server of its own with a -Heap sized heap. Workers link the library's files
# rather than copy them, so however many run, it is on disk once. Building a new one while training runs is safe: the new
# library replaces the old one only when it is whole, and the workers already on the old one keep reading it until their
# round ends.

param(
    [int] $Sites = 4096,
    [int] $Builders = 0,
    [string] $Heap = '2G'
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

# No training runs while a library is being built, since a run without one stops and says to build it, so this takes the
# whole machine by default: the build cuts the count down to what the memory holds, at the heap plus half a gigabyte each.
$builderArguments = if ($Builders -gt 0) { @("-Pworkers=$Builders") } else { @('-Pworkers=64') }

Invoke-Gradle (@(':fabric:buildTerrainLibrary', "-PlibrarySites=$Sites", "-PworkerHeap=$Heap") + $builderArguments)

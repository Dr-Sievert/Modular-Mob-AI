# Builds the terrain library: fight sites generated once and kept in runs\terrain\<minecraft version>\library, which
# every terrain worker then reads its sites from instead of generating ground. Generating was most of what a worker did
# besides fighting, two to three cores' worth, and the part generated ground around its sites was most of its memory;
# from the library a worker loads a site in a fraction of a second into a fraction of the memory, and never generates
# anything. Run it once per machine, and again for fresh ground; training picks it up by itself. Without a library,
# workers generate their own ground as before.
#
#   scripts\terrain.ps1                         2,048 sites with 3 builders, about 25 minutes and 1.6 GB of disk
#   scripts\terrain.ps1 -Sites 4096 -Builders 6 more ground to go round, sooner, on a machine with the memory for it
#
# Vanilla generates most of a chunk one task at a time, so one builder gets through about half a site a second however
# many cores are free; each builder is a server of its own with a -Heap sized heap. Workers link the library's files
# rather than copy them, so however many run, it is on disk once. Building a new one while training runs is safe: the new
# library replaces the old one only when it is whole, and the workers already on the old one keep reading it until their
# round ends.

param(
    [int] $Sites = 2048,
    [int] $Builders = 3,
    [string] $Heap = '2G'
)

. "$PSScriptRoot\_common.ps1"

Test-MachineStability

Invoke-Gradle @(':fabric:buildTerrainLibrary', "-PlibrarySites=$Sites", "-Pworkers=$Builders", "-PworkerHeap=$Heap")

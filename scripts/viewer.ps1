# Opens the fight replay viewer in the browser: viewer\serve.py, a small local server with a live list of every run's
# replays. When it is already running this only opens the browser on it. Ctrl+C stops it.
#
#   scripts\viewer.ps1                  the newest replay of any run
#   scripts\viewer.ps1 -Run imitate     the newest replay of that run
#   scripts\viewer.ps1 -Port 8800       try that port first
#   scripts\viewer.ps1 -MinecraftJar C:\path\client.jar
#                                       take mob and block textures from that jar instead of looking in the Gradle cache

param(
    [string] $Run = '',
    [int] $Port = 0,
    [string] $MinecraftJar = ''
)

. "$PSScriptRoot\_common.ps1"

$arguments = @((Join-Path $Root 'viewer\serve.py'))
if ($Run) { $arguments += @('--run', $Run) }
if ($Port -gt 0) { $arguments += @('--port', $Port) }
if ($MinecraftJar) { $arguments += @('--minecraft-jar', $MinecraftJar) }

# The server needs only the standard library, so any Python 3 does; the trainer's own is simply the likeliest to exist.
if (Test-Path $Python) {

    & $Python @arguments
}

elseif (Get-Command py -ErrorAction SilentlyContinue) {

    & py -3 @arguments
}

elseif (Get-Command python -ErrorAction SilentlyContinue) {

    & python @arguments
}

else {

    throw 'No Python found. Run scripts\setup.ps1, or install Python 3.'
}

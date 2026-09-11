# The replay viewer

```
scripts\viewer.ps1                         open the newest replay of any run in the browser
scripts\viewer.ps1 -Run vs-copy            the newest replay of that run
scripts\viewer.ps1 -Port 8800              try that port first
scripts\viewer.ps1 -MinecraftJar <jar>     take textures from that jar instead of the Gradle cache
```

`viewer\serve.py` is a small local server with a live list of every run's replays; the page is `viewer\index.html`.
Starting it again replaces an older running viewer rather than starting a second one. Ctrl+C stops it.

## Watching

- Fights play at 20 ticks a second, with pause, step, speed and full screen.
- **V** switches between the 2D map and the 3D view.
- **B** switches between block textures and map colours.
- In 3D the site is drawn block by block, with textures and biome tints:
  - logs follow their axis; leaves are cut out; water is see-through;
  - plants are crossed sprites; slabs, stairs and snow are shaped;
  - fighters stay visible through blocks as silhouettes.
- Mobs are drawn with their own textures.
- Textures are read at runtime from the local Minecraft jar in the Gradle cache. Nothing from the game is written to
  disk or into exports; without the jar, blocks keep their shapes in map colours.

## Recording

Training records one fight in `-ReplayEvery` (200) per worker, into `runs\<run>\replays\`. `scripts\test.ps1 -Terrain
-Replays` records every fight. Each replay holds the site's blocks (median about 80 KB) plus every tick's positions,
actions and projectiles. The format is in [replay-format.md](replay-format.md) (version 2; version 1 replays are no
longer drawn).

## Deleting

From the list: delete one replay, a whole run's replays, a selection (Shift-click selects a range; the box on a run
ticks all of it), or everything. Each asks first. The server only deletes finished `*.json` replays directly inside
`runs\<name>\replays\`, and refuses anything else.

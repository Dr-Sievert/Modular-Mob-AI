# Fight replay viewer

Watch recorded fights to see where the agent goes, whether its aim tracks the vindicator, what it pressed on every
tick, and why it won or lost. It has a top-down map, which is the default, and a 3D view.

## Opening it

- `scripts\viewer.ps1` starts `viewer\serve.py`, a small local server that needs only the Python standard library,
  and opens the browser on it. The page lists every run's replays (`runs\<name>\replays\*.json`) and checks for new
  ones every 3 seconds, so fights show up while training records them. Starting it again while it runs only opens the
  browser.
  - `-Run imitate` opens that run's newest replay. `-Port 8800` tries that port first; the default is 8765, then the
    next free one.
  - With *Auto-open newest* ticked, the newest replay of the run opens each time the one on screen finishes playing.
- Or open `viewer\replay.html` straight from disk and drop replay files or folders on it.
- `python viewer\serve.py --export [--run NAME | --path FILE_OR_FOLDER] [--count 20]` bakes the newest replays into
  `runs\<name>\replays.html`, a single file that works anywhere, including its 3D view.

The viewer draws replays of format version 2, which record the site block by block. Older replays have only a height
and a colour per column; the list shows them greyed out as *old*, with a link to select them all for deleting, and a
page opened from disk or an export skips them.

## Deleting replays

Served by `scripts\viewer.ps1`, the list deletes replays: the bin on a replay or on a run, or tick replays (Shift-click
ticks every replay between two, the box on a run ticks all of it) and press *Delete…*, or *Delete all replays…* at the
bottom. Each asks first, saying how many files from which runs, and the list updates at once. The files are deleted,
not moved to the recycle bin. `serve.py` does the deleting and takes only run and file names: it deletes nothing but
finished replays, `*.json` files straight inside `runs\<name>\replays\`, and only for the page on this machine. A page
opened from disk or an export cannot delete anything, and says so under the list.

A link like `http://127.0.0.1:8765/?run=imitate&replay=w01-f000400.json&t=120&view=3d&zoom=3` opens that fight at tick
120, in 3D, three times closer than framing both fighters.

## Minecraft mobs in 3D

When the viewer is served, the 3D view draws the fighters as their Minecraft mobs:
- **Models:** the agent uses the player model with wide arms in its own skin (the mod's `agent.png`) and holds an iron
  sword. There are also the vindicator (arms folded until it fights, then its axe raised), pillager, evoker,
  skeleton (bow), stray, bogged, wither skeleton, zombie, husk, drowned and iron golem. Any other mob is a humanoid in
  its own texture, or a box when there is no texture.
- **Animation:** it comes from the replay. The head follows yaw and pitch, the body lags towards where the mob walks,
  and the legs swing with its speed. Arms swing on `swing` ticks, the mob flashes red on `hurt` ticks, and it falls
  over at 0 health.
- **Blocks:** the site's real blocks, each with its own texture from the jar: grass blocks with tinted tops and grassy
  sides, logs by the way they lie, leaves with their holes, water tinted and see-through and darker where it is deep.
  Grass, ferns, flowers, saplings, dead bushes, sugar cane, hanging roots and seagrass are crossed planes, vines and
  lichen lie flat against their sides, lily pads float, slabs, stairs, snow layers, carpets and cactus are boxes of about
  their shape, and fences and walls are posts. A block the viewer knows no shape for is a cube in the texture its name
  suggests. Grass, leaves and water take their biome's colour, one colour for the whole site. Corners hidden by other
  blocks are darkened, as in the game's smooth lighting. `B` switches to every block in its map colour.

The textures come from your own Minecraft 1.21.1 client jar, which building the mod put in the Gradle cache.
`serve.py` looks for it in the Fabric Loom and NeoForge folders there (under `GRADLE_USER_HOME`, or `~/.gradle`), or
takes `-MinecraftJar PATH` (`--minecraft-jar PATH`). Mojang's licence does not allow passing their assets on, so the
textures are read from the jar while serving and kept in memory only: never written to disk, never put into an export.
Opened from disk, or as an export, the 3D view draws the mobs as boxes and the blocks in their shapes and map colours,
and says why.

## Using it

| Key | |
|---|---|
| Space | Play or pause. 1× is real time, 20 ticks a second. |
| ← → | One tick; with Shift, ten. |
| , . | Previous or next hit. |
| - + | Speed, from 0.25× to 8×. |
| ↑ ↓ | Previous or next replay in the list. |
| V | Map or 3D view. |
| F | Fullscreen. |
| C, 0 | Follow camera; whole terrain. |
| T K E G H L | Trails, contours, elevation tint, chunk grid, charts, list. |
| B | Blocks in 3D in their textures, when served with a Minecraft jar, or in their map colours. |

On the map, the mouse wheel zooms, and dragging pans (which turns the follow camera off). In 3D, dragging orbits,
right-dragging or Shift-dragging pans, the wheel zooms, and *Iso* and *Top* are camera presets. Clicking a chart or an
event marker jumps there. Press `?` in the page for how to read the views and what the numbers mean.

## three.js

The 3D view uses [three.js](https://threejs.org) 0.169.0, loaded only when you switch to 3D. It is the official
CommonJS build, `build/three.cjs` from the npm package, kept unmodified as `vendor/three.cjs`, with its MIT licence in
`vendor/LICENSE`. That build is used because a page opened from disk may load classic scripts but not ES modules.

## The replay format

The game writes one JSON file per recorded fight to `runs/<name>/replays/w<worker>-f<fight>.json`. Each file has:

- **Metadata:** `version` (2), `run`, `iteration`, `brain`, `worker`, `fight`, `biome`, `outcome`
  (`win` | `loss` | `timeout`), `ticks` (T) and `tickRate`.
- **Blocks:** `blocks`, every block of the site from under its lowest ground to its highest block: an origin and a
  size, a `palette` of block states with a map `color` and `flags` for each, and the cells as `runs` of palette
  indices. See [docs/replay-format.md](../docs/replay-format.md).
- **Fighters:** `entities` (the agent first, then its opponents), and `frames` with T values per entity of `x`, `y`,
  `z`, `yaw`, `pitch`, `health`, `swing` and `hurt`.
- **Actions:** `actions` holds the 11 values the brain asked for on each tick.
- **Reward:** `reward` is optional.
- **Projectiles:** `projectiles` is optional, and written only when the fight had some. Each entry has a `type`, the
  `owner`'s entity index (-1 for none), and a `start` tick. It holds one `x`, `y`, `z` per tick of its flight, and
  may have an `end` (`tick`, where it ended, and the entity it `hit`, or -1 for a block).

Yaw follows Minecraft: 0 faces south (+z) and 90 faces west. The viewer ignores fields it does not know, and copes
without `reward`, `biome`, `iteration` or `projectiles`, but not without `blocks`.

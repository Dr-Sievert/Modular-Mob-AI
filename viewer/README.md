# Fight replay viewer

Watch recorded fights to see where the agent goes, whether its aim tracks the vindicator, what it pressed on every
tick, and why it won or lost. It has a top-down map, which is the default, and a 3D view. Beside it, at `/league`, are
the league standings of every run that has one: see [the league page](#the-league-page) below.

## Opening it

- `scripts\viewer.ps1` starts `viewer\serve.py`, a small local server that needs only the Python standard library,
  and opens the browser on it. The page lists every run's replays (`runs\<name>\replays\*.json`) and checks for new
  ones every 3 seconds, so fights show up while training records them. Starting it again while it runs only opens the
  browser.
  - The list is every replay on disk, not only the ones opened: each is listed from the first four kilobytes of its
    file, and only a replay that is clicked is fetched whole. Twenty four thousand of them list in about a second, and
    the answer is kept until a replay appears, goes or is written over. `/` searches it — an opponent, a loadout, a
    biome, a ground, an iteration or a file name — and what each fight was comes from the run's per-fight records; see
    [../docs/viewer.md](../docs/viewer.md).
  - A checkout with no `runs\` of its own — a git worktree, which is where development happens — reads the main
    checkout's, found out of the `.git` file the worktree carries and never through a junction, and writes nothing
    into it.
  - `-League` opens the league standings instead of a replay, and the *League* button in the header goes there too.
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
- **Shapes:** each mob in its own, out of the game's own model classes. `viewer/models/<mob>.json` holds the tree of
  cuboids a mob is built from, written by `gradlew :fabric:exportMobModels` and committed — generated data derived from
  the game, no texture and no image in it. Every league opponent is there, 38 in all, plus the player shape the agent
  is drawn in; anything else falls back to a humanoid box and says so in the console. See
  [../docs/viewer.md](../docs/viewer.md), "Mob shapes", for what is exported, what is left out and how it is regenerated.
- **Textures by name:** which texture a mob gets is looked up in the jar's own list, `/api/entities`, rather than
  guessed from its id. Vanilla files a mob's texture by family as often as by name — `spider/cave_spider`,
  `hoglin/zoglin`, `illager/ravager`, `piglin/zombified_piglin` — and two of them are named nothing like their id at
  all (`bear/polarbear`, `slime/magmacube`), which is why those mobs used to come out as boxes while the jar had them
  all along.
- **Animation:** it comes from the replay. The head follows yaw and pitch, the body lags towards where the mob walks,
  and the legs swing with its speed — a humanoid's two, a quadruped's four in the diagonal gait. Arms swing on `swing`
  ticks, the mob flashes red on `hurt` ticks, and it falls over at 0 health. A mob that is neither shape only turns its
  head: a spider's legs, a blaze's rods and a bee's wings are still.
- **Blocks:** the site's real blocks, each with its own texture from the jar: grass blocks with tinted tops and grassy
  sides, logs by the way they lie, leaves with their holes, water tinted and see-through and darker where it is deep.
  Grass, ferns, flowers, saplings, dead bushes, sugar cane, hanging roots and seagrass are crossed planes, vines and
  lichen lie flat against their sides, lily pads float, slabs, stairs, snow layers, carpets and cactus are boxes of about
  their shape, and fences and walls are posts. A block the viewer knows no shape for is a cube in the texture its name
  suggests. Grass, leaves and water take their biome's colour, one colour for the whole site. Corners hidden by other
  blocks are darkened, as in the game's smooth lighting. `B` switches to every block in its map colour.

**When a texture is missing.** The page asks the server what the jar has — `/api/blocks` for the block names,
`/api/entities` for the entity paths — and then asks only for those, so `No such texture in the Minecraft jar: <path>`
in the server's log means this page built a path the jar does not have, and names it. A block the jar gives nothing for
is drawn in its map colour and named once in the browser's console, `No texture in the jar for: …`, which is the check
that a whole class of blocks has not quietly stopped resolving: open a handful of replays in 3D with the console open,
and anything named there wants a rule in `blockTextures`. That is where the rules live, so that is where the check is;
a second copy of them in a script would drift from the page it is meant to be checking.

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
| / | Search the list. Several words all have to match. |
| V | Map or 3D view. |
| F | Fullscreen. |
| C, 0 | Follow camera; whole terrain. |
| T K E G H L | Trails, contours, elevation tint, chunk grid, charts, list. |
| B | Blocks in 3D in their textures, when served with a Minecraft jar, or in their map colours. |

On the map, the mouse wheel zooms, and dragging pans (which turns the follow camera off). In 3D, dragging orbits,
right-dragging or Shift-dragging pans, the wheel zooms, and *Iso* and *Top* are camera presets. Clicking a chart or an
event marker jumps there. Press `?` in the page for how to read the views and what the numbers mean.

## The league page

`viewer\league.html`, served at `/league`, needs the server: it reads a run's files rather than files you drop on it.
What it shows and where every number comes from is in [../docs/viewer.md](../docs/viewer.md). In short: a run picker and
a second run to read beside it, the tier list with the rating over the checkpoints as a curve, the per-opponent,
per-loadout and per-ground tables as `scripts\league.ps1` prints them, the per-model stats out of the workers' per-fight
records, and a matrix of every pairing of an opponent and a loadout: how it went, and how much of the curriculum
matchmaking is giving it. Every row and every cell opens the recorded fights of that matchup, or, where a run recorded
none of them, the `scripts\eval.ps1` line that records forty.

The server serves it through three endpoints, all of them read-only:

| Endpoint | |
|---|---|
| `GET /league` | the page |
| `GET /api/league` | every run with a `league\ratings.csv`, with its player and rated-fight counts, for the picker |
| `GET /api/league/<run>[?model=N]` | one run: the trainer's tables, its `eval.csv`, and the sums of its per-fight records, with one model's own tables when asked |

A fourth, `GET /api/matchups/<run>`, is for the replay list rather than this page: what each replay of that run on disk
was a fight of — opponent, loadout, ground, kind and what killed the agent — by file name, out of the same per-fight
records. A replay does not carry its opponent anywhere near its front (it sits in `entities`, behind the site's blocks),
so the list would have to read whole files to show it. A run with no league answers `{"league": false}` and its replays
say only what their own headers hold.

The per-fight records are read on from where they last got to rather than re-read, so the page can poll every few
seconds against a run of hundreds of thousands of fights. A results file that has grown *shorter* is a different run
under the same name, and everything is read again.

## three.js

The 3D view uses [three.js](https://threejs.org) 0.169.0, loaded only when you switch to 3D. It is the official
CommonJS build, `build/three.cjs` from the npm package, kept unmodified as `vendor/three.cjs`, with its MIT licence in
`vendor/LICENSE`. That build is used because a page opened from disk may load classic scripts but not ES modules.

## The replay format

The game writes one JSON file per recorded fight to `runs/<name>/replays/w<worker>-f<fight>.json`. Each file has:

- **Metadata:** `version` (2), `run`, `iteration`, `brain`, `worker`, `fight`, `biome`, `outcome`
  (`win` | `loss` | `timeout` | `draw`), `ticks` (T) and `tickRate`.
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

# The viewer

Two pages served by one small local server: the fight replays, and the league standings.

```
scripts\viewer.ps1                         open the newest replay of any run in the browser
scripts\viewer.ps1 -Run vs-copy            the newest replay of that run
scripts\viewer.ps1 -League                 the league standings instead: the tier list, the tables, the per-model stats
scripts\viewer.ps1 -League -Run league2    that run's league
scripts\viewer.ps1 -Port 8800              try that port first
scripts\viewer.ps1 -MinecraftJar <jar>     take textures from that jar instead of the Gradle cache
```

`viewer\serve.py` is a small local server with a live list of every run's replays; the pages are `viewer\replay.html`
and `viewer\league.html`, and *League* in the replay page's header opens the second. Starting it again replaces an older
running viewer rather than starting a second one. Ctrl+C stops it.

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

## The league page

`scripts\viewer.ps1 -League`, or the *League* button in the replay page's header. It lists every run that has a league
(`runs\<run>\league\ratings.csv`), and shows one of them at a time with a second beside it if you pick one. Six tabs,
all of them sortable by any column, all of them live while a run trains:

| Tab | |
|---|---|
| Tier list | Rating over the evaluated checkpoints as a curve, then every player by rating with its tier, its rated fights and its own record, and the share of the training fights it is given now. Under it, every fight of the run and what the agent died of. |
| Opponents | What `scripts\league.ps1` prints: rating, share, the last 200 evaluation and training fights against each, and beside them every fight of the run against it. |
| Loadouts and ground | The same per loadout, and the ground table with what finished the other side. The `ground` column is the one to watch: a fight the terrain ends is the agent's win either way, so it is the only sign that the agent has learned to knock things into lava. |
| Models | Every evaluated checkpoint from the per-fight records: its rating, its record, the average fight, the item it held longest, what it died of most, and its swaps, uses and shots a fight. Pick one for its record against each opponent and with each loadout, every weapon it held and everything it died of. |
| Opponent by loadout | Win rate for every pairing over every fight of the run, green above half and red below. |
| Two runs | Two lineages side by side: both rating curves on one chart, both tier lists, and the players they share. That last table is the point — the scripted fighter is held at 1500 in both, and a published network both runs field is rated by each on its own fights, so two ratings within a tier of each other mean the lists can be read together. See [training.md](training.md), `-LeagueModels`. |

A `▸ 4` in the last column of a row is the recorded fights of that matchup: it opens a list of them, each a link into the
replay viewer at that fight. A run records one fight in 200 per worker, so most rows have none.

Everything comes from files a league run already writes, and nothing is written: the trainer's tables in
`runs\<run>\league\`, the run's `eval.csv`, and the workers' per-fight records in `runs\<run>\league\results\`. Those
last run to hundreds of thousands of lines, so they are added up once when the page first asks and then only read on
from where they got to — a quarter of a million fights takes about a second the first time and nothing after. A row's
link is checked against the replays actually on disk, so it never points at a fight that was never written.

The behaviour columns — the weapon, the swaps, the uses and the shots — are recorded per fight by the game, not guessed
from replays, and are newer than the runs training today: an older line simply says nothing about them, and the page
shows a dash.

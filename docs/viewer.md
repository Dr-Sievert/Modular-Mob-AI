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

## The list

Every replay under `runs\*\replays`, whether or not one has been opened: twenty four thousand of them list in about a
second and cost nothing after. Listing one reads its first four kilobytes, which is where the recorder puts the run, the
iteration, the brain, the biome, the outcome and the length; the rest of the file, the site's blocks and every tick, is
fetched only when it is clicked. The answer is kept between polls and built again only when a replay appears, goes or is
written over, so the page can ask every three seconds while training writes.

- A row reads *iteration · opponent · loadout · ground · biome · ticks*, with the whole of it, the outcome and what the
  agent died of, on hover.
- The opponent, the loadout and the ground are not in a replay's first kilobytes — the opponent sits behind the site's
  blocks — so they come from the run's own per-fight records, a run at a time, when that run's replays are on show. A run
  with no `league\results` simply says less.
- **/** or the box at the top filters the list: `pillager`, `bow lava`, `it 1389`, `w00-f0416`. Several words all have to
  match, and a run with anything that matches opens itself.

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
- The page asks the server what the jar holds — `/api/blocks` and `/api/entities` — and then asks only for those, so it
  never sends the server after a texture that cannot exist. That matters for mobs, which vanilla files by family as
  often as by name: a cave spider under `spider/`, a zoglin under `hoglin/`, every illager under `illager/`. Guessing
  `entity/<id>/<id>` and `entity/<id>` cost a 404 per mob of the older convention and found nothing at all for eight
  more. A `No such texture in the Minecraft jar` line in the server's log is now a mapping this page has got wrong, and
  it names the path.
- A block the jar gives nothing for is drawn in its map colour, and the page says which in the browser's console: open a
  handful of replays with it open and anything named there wants a rule in `blockTextures`.

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

The last column of a row is its recorded fights against the fights it is about, `▸ 4 / 1 812` or `0 / 214`. Both open a
drawer: with recordings, a list of them, each a link into the replay viewer at that fight; with none, what the run
recorded and the command that would record some,

```
scripts\eval.ps1 -Weights runs\<run>\best.mbw -Suite league -Arenas 40 -ReplayEvery 1 -Opponents ravager
```

which leaves forty fights of that matchup in `runs\eval-<run>-best\replays`. **A 0 there is not a page that lost
something.** A run records one fight in 200 per worker (`scripts\train.ps1 -ReplayEvery`), and those few land across a
hundred opponents and fifteen loadouts, so most matchups were never written down at all: league768 has 1,400 replays of
335,947 fights, and 25 of its 142 opponents have none. The one in 200 is measured from the gaps between the replay names
a run left, not assumed, since nothing a run writes down says what it was started with.

Everything comes from files a league run already writes, and nothing is written: the trainer's tables in
`runs\<run>\league\`, the run's `eval.csv`, and the workers' per-fight records in `runs\<run>\league\results\`. Those
last run to hundreds of thousands of lines, so they are added up once when the page first asks and then only read on
from where they got to — a quarter of a million fights takes about a second the first time and nothing after, and two
million took under seven. A row's link is checked against the replays actually on disk, so it never points at a fight
that was never written. The recorded fights kept per run are capped, high enough to cover every replay a run can have on
disk: at 4,000 the longest run on this machine had 7,875 replays and the older half of them silently lost their labels.

The behaviour columns — the weapon, the swaps, the uses and the shots — are recorded per fight by the game, not guessed
from replays, and are newer than the runs training today: an older line simply says nothing about them, and the page
shows a dash.

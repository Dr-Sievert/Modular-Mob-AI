# The viewer

Two pages served by one small local server: the fight replays, and the league standings.

```
scripts\viewer.ps1                         open the newest replay of any run in the browser
scripts\viewer.ps1 -Run blast              the newest replay of that run
scripts\viewer.ps1 -League                 the league standings instead: the tier list, the tables, the per-model stats
scripts\viewer.ps1 -League -Run blast      that run's league
scripts\viewer.ps1 -Port 8800              try that port first
scripts\viewer.ps1 -MinecraftJar <jar>     take textures from that jar instead of the Gradle cache
```

`viewer\serve.py` is a small local server with a live list of every run's replays; the pages are `viewer\replay.html`
and `viewer\league.html`, and *League* in the replay page's header opens the second. Starting it again replaces an older
running viewer rather than starting a second one. Ctrl+C stops it. [../viewer/README.md](../viewer/README.md) is the note for
anyone changing the viewer itself: its files, its endpoints, the vendored three.js.

Two ways to watch a fight without the server, both of which give up the Minecraft textures and the deleting, and say so on
the page:

- open `viewer\replay.html` straight from disk and drop replay files or folders on it;
- `python viewer\serve.py --export [--run NAME | --path FILE_OR_FOLDER] [--count 20]` bakes the newest replays into
  `runs\<name>\replays.html`, one file that works anywhere, its 3D view included.

A checkout with no `runs\` of its own reads the main checkout's `combat\runs\`, found the way `scripts\_common.ps1`
finds the trainer's environment: out of the `.git` file a worktree carries — which sits a level above this half, so the
`combat\` segment is joined back on — never through a junction. Development happens in a worktree,
which has no runs at all, and a viewer there used to say the machine had never trained. Nothing is written into a
borrowed folder, not even the lock file that says where this viewer is listening, so the viewer serving the main
checkout keeps the port a plain start finds.

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
- The list is the server's answer, not what has been opened: it is there from the first paint, and until the answer
  arrives the page says it is looking. It used to say *nothing under `runs\*\replays`* in that second instead, which on a
  machine with twenty five thousand replays on it reads as a page that can only show what has been dropped on it.
- A replay of the older format, which has only a height and a colour per column rather than the site's blocks, is greyed
  out as *old* with a link that selects every one of them for deleting. A page opened from disk or an export skips them.

## Watching

| Key | |
|---|---|
| Space | play or pause; 1× is real time, 20 ticks a second |
| ← → | one tick; with Shift, ten |
| , . | previous or next hit |
| - + | speed, 0.25× to 8× |
| ↑ ↓ | previous or next replay in the list |
| / | search the list; several words all have to match |
| V | map or 3D |
| F | full screen |
| C, 0 | follow camera; the whole terrain |
| T K E G H L | trails, contours, elevation tint, chunk grid, charts, list |
| B | blocks in their textures, where the jar is there, or in their map colours |

On the map the wheel zooms and dragging pans, which turns the follow camera off. In 3D dragging orbits, right-dragging or
Shift-dragging pans, the wheel zooms, and *Iso* and *Top* are presets. Clicking a chart or an event marker jumps there.
`?` in the page says how to read the views and what the numbers mean.

A link carries all of it, so a fight can be pointed at:
`http://127.0.0.1:8765/?run=blast&replay=w01-f000400.json&t=120&view=3d&zoom=3` opens that fight at tick 120, in 3D, three
times closer than framing both fighters. With *Auto-open newest* ticked, the newest replay of the run opens whenever the one
on screen finishes.

- In 3D the site is drawn block by block, with textures and biome tints:
  - logs follow their axis; leaves are cut out; water is see-through;
  - plants are crossed sprites; slabs, stairs and snow are shaped;
  - fighters stay visible through blocks as silhouettes.
- Mobs are drawn in their own shape and their own texture; see [Mob shapes](#mob-shapes) below.
- A replay holds the agent, then the other side, then anything standing about the fight taking no interest in it. The agent
  is green and the opponents red, orange and pink; a monster standing about — a league fight's `+3_idle` crowd — is grey, so a
  crowd never reads as nine opponents. Squad fights and crowded fights record like any other, which they did not until
  recently; see [replay-format.md](replay-format.md).
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

## Mob shapes

Every mob is drawn in its own shape. A polar bear is a polar bear, a spider a spider, a ghast a ghast; before this the
page had five shapes ported by hand and wrapped every other mob's skin round a humanoid box, so a wolf and a creeper came
out person-shaped.

A Minecraft entity model is data — a tree of cuboids, each with a pivot, a resting rotation and a texture offset, and
nothing else — but it lives in the client's Java classes, which a browser cannot read. So the game is asked once:

```
gradlew :fabric:exportMobModels          from mod\, writes viewer\models\<mob>.json
```

`LayerDefinitions.createRoots()` builds every model the client registers, as plain data with no window and no rendering,
and `gametest/tools/MobModelTool` walks those trees and writes the numbers down: per part a pivot, a rotation and its
cubes, per cube the corner, the size, the texture offset, the mirror and the inflate, and per mob the layer's texture
size, the entity's own width and height, and the box the whole model covers. The tool takes a few seconds and needs no
game running. Its output is committed: it is generated data derived from the game — cuboid numbers, the same kind of
thing as a block's shape — and not a Mojang asset. **No texture and no image is in it**; mob skins are still read from
the user's own jar while the viewer serves, as they always were.

Regenerate it when the game version changes or a mob joins the league; the files come out byte for byte the same when
the game has not changed, so a diff is the change.

**Which mobs.** Every opponent the league fields — see `gametest/league/Roster`, which is also every mob a replay can
hold — plus the player shape the agent is drawn in: 38 files, about 40 kB in all. A replay with anything else in it (an
arena test told to fight something exotic) falls back to the humanoid box and says so once in the browser's console.

**Only the base layer.** The extra layers a mob has are named in each file under `otherLayers` and drawn by nothing: the
armour layers every humanoid carries, a wolf's collar and armour, a slime's outer shell, a creeper's charge. A fighter
in a replay wears no armour, so there is nothing for them to do. A breeze's wind and a player's cape and deadmau5 ears
are in the base layer but are drawn by a layer of the renderer's rather than by the model, so the page leaves those out
too (`MOB_NOT_DRAWN`).

**Size is not in the model.** Every model is one size in its file, and the client's renderer scales a few of them, which
is code rather than data. Those scales are in `MOB_KINDS` in the page, each taken from that mob's renderer: 1.0625 for a
husk, 1.2 for a wither skeleton and a polar bear, 0.7 for a cave spider, 0.9375 for the illagers and the witch, 4.5 for
a ghast; a slime and a magma cube are scaled by their size, which the replay's recorded height is what says. Everything
else is drawn at 1, which is what its renderer does. The check that one of them is wrong or missing is the recorded
height: a shape drawn more than twice or less than half as tall as the mob the replay recorded says so in the console.

**Animation** comes from the replay, and from the parts a model has rather than from a list of mobs, which is how the
game's own model classes divide up too:

| Model has | What moves |
|---|---|
| a pair of arms and a pair of legs | HumanoidModel's walk, the attack swing and the idle sway — every zombie, skeleton, piglin, illager, the warden, an iron golem, the agent |
| four named legs | QuadrupedModel's diagonal gait — a wolf, a polar bear, a hoglin, a ravager, a creeper |
| a head and neither of those | the head follows the recorded look, and nothing else |

The walk is driven by the speed between the recorded positions, the head by the recorded yaw and pitch, the arm swing by
the recorded `swing` ticks. Nothing else is invented: a spider's eight legs, a blaze's rods, a bee's wings, a ghast's
tentacles, a slime's squash and a phantom's wingbeat are all still, because a pose that is right beats a movement that
is made up. The outer skin layer — a hat, a jacket, sleeves, trousers — is a sibling part in the tree that the game
poses by copying the part under it, and the page does the same (`MOB_COPIES`); without it a walking mob's jacket stays
behind.

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
| Opponent by loadout | Every pairing of an opponent and a loadout over every fight of the run: the win rate, green above half and red below, and under each cell the share of the training fights that pairing is being given. A cell's number can be swapped for that share or for the chance the trainer estimates. |
| Two runs | Two lineages side by side: both rating curves on one chart, both tier lists, and the players they share. That last table is the point — the scripted fighter is held at 1500 in both, and a published network both runs field is rated by each on its own fights, so two ratings within a tier of each other mean the lists can be read together. See [training.md](training.md), `-LeagueModels`. |

**Every row offers a fight to watch**, and so does every cell of the pairing matrix. The last column of a row is its
recorded fights against the fights it is about, `▸ 4 / 1 812` or `0 / 214`, and both open the same drawer: with
recordings, a list of them, each a link into the replay viewer at that fight; with none, what the run recorded and the
command that would record some,

```
scripts\eval.ps1 -Weights runs\<run>\best.mbw -Suite league -Arenas 40 -ReplayEvery 1 -Opponents ravager
```

which leaves forty fights of that matchup in `runs\eval-<run>-best\replays`. A cell of the matrix asks for the pairing,
`-Opponents 'bee(hard)' -Loadouts bow`, and a model row for that checkpoint, `-Run <run> -Iteration 2525`. A name that is
not a plain word is quoted, since `zombie(hard)` unquoted sends PowerShell looking for a command called `hard`.

**A 0 there is not a page that lost something**, which is why the row with none is drawn dashed rather than left blank: a
run records one fight in 200 per worker (`scripts\train.ps1 -ReplayEvery`), and those few land across a hundred opponents
and fifteen loadouts, so most matchups were never written down at all: league768 has 2,506 replays of 604,463 fights,
and 26 of the 191 players it has met have none. The one in 200 is measured from the gaps between the replay names a run
left, not assumed, since nothing a run writes down says what it was started with.

**The pairing matrix** is the one table that says where the fights are going as well as how they went. What matchmaking
draws is the pairing, a loadout and an opponent together, so `league\pairs.csv` holds each pairing's share of the
training fights and the chance the trainer reckons the agent has in it; the page shows the share as a violet line under
the cell, against the largest share in the table, and the buttons above swap the cell's number between the win rate, that
share and that chance. A pairing with a share and no fights yet is a row of dots with a line under it — where the fights
are about to go. A run started before the trainer drew pairings has no `pairs.csv`, and says so: its cells are the win
rate and nothing else.

Everything comes from files a league run already writes, and nothing is written: the trainer's tables in
`runs\<run>\league\` — `ratings`, `opponents`, `loadouts`, `ground`, `matchmaking` and `pairs` — the run's `eval.csv`,
and the workers' per-fight records in `runs\<run>\league\results\`. Those last run to hundreds of thousands of lines, so
they are added up once when the page first asks and then only read on from where they got to — a quarter of a million
fights takes about a second the first time and nothing after, and two million took under seven. A row's link is checked
against the replays actually on disk, so it never points at a fight
that was never written. The recorded fights kept per run are capped, high enough to cover every replay a run can have on
disk: at 4,000 the longest run on this machine had 7,875 replays and the older half of them silently lost their labels.

The behaviour columns — the weapon, the swaps, the uses and the shots — are recorded per fight by the game, not guessed
from replays, and are newer than the runs training today: an older line simply says nothing about them, and the page
shows a dash.

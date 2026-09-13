# Fight replay viewer: the code

**What the viewer does and how to use it is [../docs/viewer.md](../docs/viewer.md)** — the commands, the list, the keys, the
3D view, mob shapes, recording, deleting and the league page. It is the reference because it is the fuller of the two and it
is in the doc index every other doc is reached from; this file used to repeat about two thirds of it, and the two had already
drifted. What is left here is what somebody changing the viewer needs and a reader of it does not.

## The files

| | |
|---|---|
| `serve.py` | the whole server: the replay list, the league endpoints, the deleting, the textures out of the Minecraft jar, and `--export`. Standard library only |
| `replay.html` | the replay page, one file: the map, the 3D view, the charts, the list |
| `league.html` | the league page |
| `models/<mob>.json` | one mob's tree of cuboids, written by `gradlew :fabric:exportMobModels`; generated data, no texture in it |
| `vendor/three.cjs` | three.js 0.169.0, unmodified, with its licence beside it |

## The endpoints

All read-only but the deleting, and all of them answer only for this machine's own pages.

| | |
|---|---|
| `GET /` | the replay page; `?run=&replay=&t=&view=&zoom=` opens one fight where it says |
| `GET /api/runs` | every replay on disk from its first four kilobytes, kept between polls and built again only when one appears, goes or is written over |
| `GET /api/replay/<run>/<file>` | one replay whole, which is what clicking a row fetches |
| `GET /api/matchups/<run>` | what each of that run's replays was a fight of — opponent, loadout, ground, kind, what killed the agent — by file name, out of the per-fight records. A replay does not carry its opponent near its front, so the list would otherwise have to read whole files. `{"league": false}` for a run with none |
| `GET /api/blocks`, `GET /api/entities` | what textures the jar actually holds, so the page never asks for one that cannot exist |
| `GET /mc/...`, `GET /skin/agent.png` | one texture, read from the jar and never written to disk |
| `GET /models/<mob>.json`, `GET /vendor/<file>` | the mob shapes and three.js |
| `GET /league` | the league page |
| `GET /api/league` | every run with a `league\ratings.csv`, with its player and rated-fight counts, for the picker |
| `GET /api/league/<run>[?model=N]` | one run: the trainer's tables, its `eval.csv`, and the sums of its per-fight records, with one model's own tables when asked |
| `GET /api/ping` | what a second start asks, to find out whether a viewer is already listening here |
| `POST /api/delete` | run and file names only: nothing but finished `*.json` replays directly inside `runs\<name>\replays\` |

The per-fight records are read on from where they last got to rather than re-read, which is what lets the league page poll a
run of hundreds of thousands of fights. A results file that has grown *shorter* is a different run under the same name, and
everything is read again.

## Textures

The page asks what the jar holds and then asks only for those, so `No such texture in the Minecraft jar: <path>` in the
server's log means the page built a path the jar does not have, and names it. A block the jar gives nothing for is drawn in
its map colour and named once in the browser's console, `No texture in the jar for: …` — which is the check that a whole
class of blocks has not quietly stopped resolving. Open a handful of replays in 3D with the console open, and anything named
there wants a rule in `blockTextures`. The rules live in the page, so the check does too: a second copy of them in a script
would drift from the thing it is meant to be checking.

Mojang's licence does not allow passing their assets on. Textures are read from the user's own 1.21.1 client jar in the
Gradle cache while serving and kept in memory only: never written to disk, never put into an export, never committed.

## three.js

0.169.0, loaded only when the 3D view is switched to. The official CommonJS build, `build/three.cjs` from the npm package,
kept unmodified as `vendor/three.cjs` with its MIT licence in `vendor/LICENSE`. CommonJS rather than the module build
because a page opened straight from disk may load classic scripts but not ES modules.

## The replay format

[../docs/replay-format.md](../docs/replay-format.md). The viewer ignores fields it does not know, and copes without
`reward`, `biome`, `iteration` or `projectiles`, but not without `blocks`.

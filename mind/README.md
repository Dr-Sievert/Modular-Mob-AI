# The mind

A pure-Python testbed for the behaviour half of [Modular Mob AI](../README.md), whose other half is
the combat mod in the rest of this repository: dwarf NPCs whose actions come out of **persistent
emotional and social state** plus **one arbitrator choosing among skills**, with a log that records
*why* every decision was made, and a single-file HTML viewer of a run.

Nothing here needs Java, Gradle or Minecraft. **Every command below is run from this directory**
(`cd mind` from the repository root).

No game engine, no neural net, no dependencies: Python 3 and the standard library. It is a rehearsal
for "tiny learned specialists + persistent emotional/social state + one action selector", with the
arbitrator hand-weighted for now and sitting behind the interface a network would use
(`score(observation, candidate_features) -> float`, both flat float vectors with fixed layouts).

Six dwarves in a settlement of six places. They mine, smith, farm, drink, rest, gossip, steal, hold
grudges, apologise, brawl and fight whatever turns up at the gate. Nothing in here is scripted: a
feud is what happens when the numbers line up.

They also **remember** (a bounded list of episodes that fade at a rate set by a forgiveness trait),
**want things for longer than a tick** (get rich, avenge somebody, repay somebody), **owe each other
favours** (an ask with a deadline, which can be accepted, refused, haggled over, kept or broken),
and **answer being provoked in six different ways** -- ignore it, snap back, demand an apology,
complain to a friend, walk out, or swing. Which one a dwarf reaches for is not scripted either: a
proud dwarf in a crowded room demands, a timid one leaves, a forgiving one lets it go.

```
python -m dwarfsim run --agents 6 --ticks 2000 --seed 1 --out runs/run1.jsonl
python -m dwarfsim run --scenario feud --ticks 2000 --seed 1 --out runs/feud.jsonl --html runs/feud.html
python -m dwarfsim run --scenario gossip --out runs/gossip.jsonl --html runs/gossip.html
python -m dwarfsim run --scenario feud-chief --out runs/chief.jsonl      # authority, opt-in
python -m dwarfsim player --out runs/player.jsonl --html runs/player.html
python -m dwarfsim view runs/feud.jsonl -o runs/feud.html
```

`--scenario` is one of `default`, `feud` (two dwarves start distrustful and hot-tempered),
`feud-chief` (the same, with a chief), `theft` (one very greedy dwarf), `raid` (things at the gate,
often), `gossip` (a sour pair and a settlement of talkers, to watch a feud spread through friends)
and `player` (ordinary dwarves, ready to be spoken to), plus the three the reward stage added --
`friends` (mild insults between dwarves who trust each other), `bully` (one hostile dwarf, one timid
one and somebody strong enough to step in) and `thief` (a thief who gets caught in a crowded tavern).
Scenarios set starting conditions only.

**There is no chief unless you ask for one.** `--chief` appoints the most respected dwarf,
`--no-chief` forbids it, and only `feud-chief` turns it on by itself. A chief hears complaints and
can fine or publicly rebuke; measurably, that means fewer blows and more complaints and apologies.

`python -m dwarfsim player` is a scripted **player session**, not a scenario: an outsider with the
speaker id `"player"` gives one dwarf an order, is haggled with and ignored, then fights beside
them, gives them gold and keeps a promise -- and gives the identical order again, which this time is
taken on. The player is otherwise an ordinary speaker with a neutral relationship row in every head.

Runs are deterministic from `--seed`; 6 dwarves for 2,000 ticks takes about a second and writes 6 to
16 MB of log.

Open the HTML from `file://`, no server. Six views: the auto-narrated story, a filterable timeline
with the dialogue, per-dwarf traces of emotions, needs and health, the **Mind panel** (what one
dwarf feels, wants, owes and remembers at the tick you are on, with each memory's salience and
whether it was suffered, seen or heard from somebody), the relationship matrix with an
actual/**believed by others** toggle, and the decision inspector -- pick a dwarf and a tick and see
every candidate the arbitrator weighed and every term that went into each score.

Tests: `python -m pytest -q tests`.

- [docs/design.md](docs/design.md) -- the mind model, the event delta table, episodic memory, goals,
  obligations, the arbitrator terms, the vector layouts, and how each piece maps onto the Java mod.
- [docs/port.md](docs/port.md) -- the brief for the Java port: which `Brain` each of the two trained
  models becomes, the `.mbw` segment order proposed for each, the one seam in `BrainState`, the chat
  hook and the parity procedure. The frozen models themselves are in [`../shared/models/`](../shared/models),
  each with a specification and a 200-record parity file; `python -m tools.freeze` rebuilds them after a
  retrain and `python -m tools.check_parity` proves they have not drifted. `shared/` is the one place
  anything here writes to or reads from outside `mind/`, because those files are what the combat half
  ports: see [../shared/README.md](../shared/README.md).
- [text/SCHEMA.md](text/SCHEMA.md) -- the dialogue label schema, which is also the input contract for
  `dwarfsim/speech.py`, where a text classifier plugs in later. The sim adds one optional field to
  it, `ask`, documented in [docs/design.md](docs/design.md#speech). The corpus and labelling pipeline
  that produces training data against that schema lives in `text/`.

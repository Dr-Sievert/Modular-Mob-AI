# Training

Everything here runs from the repository root in Windows PowerShell. `scripts\setup.ps1` must have run once.

## The pipeline that works

Training from nothing learns slowly: vs-scratch reached 78.6% after about 650 iterations. The route that reaches ~100% is
to copy a hand-written fighter first, then improve the copy with reinforcement learning.

1. **The scripted teacher.** `brain/ScriptedBrain.java` wins about 98.6% against a vindicator on terrain. It plans a
   path over the agent's own terrain grid, keeps the vindicator between 2.4 and 3.1 blocks, swings only at full strength
   and never into a block, and drops down to a target stuck in a pit. It sees only what the network sees.
2. **Imitation**, with `scripts\imitate.ps1 -Run <copy>`:
   - It records the teacher's fights with its movement and aim pushed off by noise (DART, 0.1 of full deflection), so
     the record includes getting back on target.
   - It copies that record into a network by behaviour cloning.
   - Then it runs rounds of DAgger: the copy drives with light noise (0.05) and the teacher labels every tick, then the
     network is copied again from everything so far.
   - `runs\vindicator4`, made this way, wins 97.9% on its most likely action.
3. **Reinforcement learning from the copy**, with `scripts\train.ps1 -Run <run> -FromCopy` (start by copying the copy's
   `state.pt`, `schema.json`, `weights\000000.mbw` and a link to its `demos` into the new run; `scripts\compare.ps1` does
   this). It's PPO with safeguards, because plain PPO made a good copy worse twice:
   - four times the experience per update (65,536 steps);
   - smaller, bounded steps: learning rate 5e-5, clip 0.1, target KL 0.01;
   - 30 iterations where only the critic learns;
   - little exploration: entropy 0.001, and a narrow spread on aim;
   - a pull back towards the teacher's answers throughout (`--teacher-weight 0.5`: imitation loss on a sample of the
     recorded demos).
4. **Evaluation inside the run.** Every 25th iteration's checkpoint is played by the workers on its most likely action,
   in one fight in ten, until it has had `--eval-fights` fights (500 by default).
   - Verdicts go to `eval.csv`, and the best checkpoint's weights to `best.mbw`.
   - The run is done when a checkpoint reaches `--eval-target` (0.995), or after `--eval-patience` judged checkpoints in
     a row without a new best (10).
   - The build then starts no more rounds, and the `finished` file says why.
5. **Publishing.** `scripts\publish.ps1 -Run <run> -State -Push` copies the best network into `models\<run>\` and pushes
   it; see [models.md](models.md).

Results so far (evaluated on the most likely action):

| Run | Started from | Best | Won / lost / timed out |
| --- | --- | --- | --- |
| vindicator4 | imitation + 3 DAgger rounds | 97.9% | |
| vs-copy | vindicator4, PPO with the teacher pull | iteration 650 | 99.8 / 0.2 / 0.0 (553 fights) |
| vs-scratch | nothing | iteration 650 | 78.6 / 18.4 / 3.0 (500 fights), still climbing |

## Scripts

### `scripts\train.ps1`: one run

```
scripts\train.ps1                                  10,000 battles on run 'default', then stops
scripts\train.ps1 -Run name -Battles 0             until evaluation says it's done, or Ctrl+C
scripts\train.ps1 -Run name -FromCopy              the safeguarded PPO above, for a run started from a copy
scripts\train.ps1 -Run name -Workers 4             4 workers; 0 (default) means as many as cores and memory allow
scripts\train.ps1 -Run name -Extra '--eval-target 0.999 --eval-fights 2000'    any trainer option, see below
scripts\train.ps1 -Run name -Full                  everything the build prints, instead of the half-minute feed
scripts\train.ps1 -Run league -Suite league -Seed vs-copy     the league, from vs-copy's best (see the league below)
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-Run` | default | folder under `runs\`; an existing run resumes from its `state.pt` |
| `-Battles` | 10000 (0 with `-FromCopy`) | stop after this many fights; 0 means run until evaluation says done |
| `-RoundSize` | 250000 | fights per round; each round starts fresh worker processes |
| `-Workers` | 0 = auto | worker processes (game servers) |
| `-Slots` | 25 | fights at once per worker |
| `-Heap` | 1536M | heap per worker; it needs about 0.95 GB live, so 1 GB thrashes |
| `-RolloutSteps` | 16384 (65536 with `-FromCopy`) | steps of experience per update (an iteration) |
| `-Device` | cuda | `cpu` keeps the GPU out of it |
| `-Suite` | terrain | `arena` is a closed 9-block box, for quick checks; `league` is every mob, the scripted fighter and the run's own checkpoints |
| `-ReplayEvery` | 200 | record one fight in this many per worker, for the viewer; 0 for none |
| `-FromCopy` | off | the safeguarded settings for a run that starts from a copy |
| `-Seed` | | start a new run from another's best checkpoint state: `runs\<seed>`, else `models\<seed>\state.pt`, else a folder by path; gentle settings as `-FromCopy` but no teacher pull, the critic alone for 30 iterations, 65536 steps and no battle limit by default |
| `-Full` | off | the whole build output |
| `-Extra` | | options passed to the trainer |

A run is refused if it's already training, rather than killing the live one. The console shows a line every half
minute: iteration, ticks/s, fights/s, training win rate, fight length; plus evaluations, rounds and errors.

### Trainer options (`-Extra`)

Every field of `Config` in `trainer/mmai/ppo.py` is an option, as `--field-name value`. The useful ones:

| Option | Default | Meaning |
| --- | --- | --- |
| `--learning-rate` | 3e-4 | Adam step size |
| `--clip` | 0.2 | PPO clip range |
| `--target-kl` | 0.02 | stop an update early past this KL |
| `--epochs` | 4 | passes over each iteration's data |
| `--entropy-coef` | 0.01 | exploration bonus |
| `--critic-warmup` | 0 | iterations where only the critic learns |
| `--teacher-weight` | 0 | pull towards the run's demos; needs `runs\<run>\demos` |
| `--seq-len` | 32 | ticks of GRU unrolled per training chunk |
| `--h1 --hidden --h3` | 256, 128, 128 | network widths; only for a new run, and the game needs no change |
| `--eval-fights` | 500 | fights per judged checkpoint (2,000 tells 99.6% from 99.9%) |
| `--eval-patience` | 10 | judged checkpoints without a new best before done |
| `--eval-target` | 0.995 | win rate at which the run is done at once |
| `--checkpoint-every` | 25 | iterations between kept checkpoints, and so between evaluations |
| `--league-pool`, `--league-recent` | 8, 4 | checkpoints the league agent meets, and how many of them are the newest |
| `--league-self-play` | 0.2 | share of league training fights against those checkpoints |
| `--league-floor` | 0.25 | share of each group's fights spread evenly, whatever the agent's chances |
| `--league-k` | 16 | Elo K (twice that for a player's first `--league-provisional` 30 rated fights) |

### The league: `-Suite league` and `scripts\league.ps1`

A league run fights 26 mobs, the scripted fighter and frozen checkpoints of itself, with a loadout drawn every fight; see
[architecture.md](architecture.md#the-league). Matchmaking sends training fights where the agent wins about half the
time; evaluation fights are drawn evenly and rated. A checkpoint is judged on 1,000 evaluation fights over the mobs and
the scripted fighter, and the run is done after ten judged checkpoints in a row without a new best.

```
scripts\train.ps1 -Run league -Suite league -Seed vs-copy     start one from vs-copy's best, run until done
scripts\league.ps1 -Run league                                the tier list, the record against each opponent and loadout
scripts\league.ps1 -Run league -All                           every rated checkpoint in the tier list
scripts\league.ps1 -Test                                      the unit tests of the Elo, matchmaking and pool arithmetic
scripts\eval.ps1 -Run league -Suite league                    a network round every opponent, with a table at the end
```

### `scripts\compare.ps1`: from the copy and from nothing, side by side

```
scripts\compare.ps1                                  copy of runs\vindicator4 vs from nothing, runs vs-copy / vs-scratch
scripts\compare.ps1 -CopyWorkers 4 -ScratchWorkers 2
```

This sets up `runs\<prefix>-copy` from the copy the first time (state, weights, a junction to its demos) and starts both
runs in the background, with output going to each run's `console.log`. The copy's run stops when evaluation says done;
the run from nothing only stops at `-ScratchBattles` (3,000,000).

### `scripts\imitate.ps1`: make a copy of the teacher

```
scripts\imitate.ps1 -Run vindicator                 record the teacher, copy it, then three rounds of correction
scripts\imitate.ps1 -Run vindicator -Rounds 0       only record and copy
scripts\imitate.ps1 -Run vindicator -Rounds 2       two more rounds on top
```

| Parameter | Default | Meaning |
| --- | --- | --- |
| `-Fights` | 4000 | fights recorded per round |
| `-TeacherNoise` | 0.1 | noise on the teacher's recorded fights (0.2 lost too much) |
| `-StudentNoise` | 0.05 | noise on the copy while it drives in correction rounds |
| `-Workers`, `-Slots`, `-Heap` | 8, 25, 1280M | as for training |

Demos go to `runs\<run>\demos\round-N\`. They're gigabytes, and not in git.

### Watching and stopping

```
scripts\watch.ps1                 every live run, one wide line each, redrawn in place; -Once to print once
scripts\stop.ps1 -Run name        stop that run: its trainer, its workers, its Gradle client
scripts\stop.ps1                  stop EVERY run on the machine
```

`watch.ps1` shows, per run:
- state and workers;
- iteration, fights/s and game ticks/s over the last minute;
- the training win rate with its trend and a sparkline (training explores, so it sits below what the weights can do);
- the latest evaluation as won / lost / timed out;
- the best evaluation.

A last line adds the runs up. Stopping loses nothing: the next `train.ps1` for the run carries on from `state.pt`.

## What's in a run folder (`runs\<run>\`)

```
schema.json          the layout the game ran; stamped into weights
state.pt             everything the trainer needs to carry on: networks, optimiser, normaliser, iteration
weights\NNNNNN.mbw   the policy of each iteration (older ones pruned, checkpoints kept)
rollouts\            shards in flight, deleted once learned from
checkpoints\         state.pt every 25 iterations
eval\ eval.csv best.mbw finished       evaluation, see architecture.md
logs\train-*.log     the trainer's log; one line per iteration
console.log          the whole build output of a background run
replays\*.json       recorded fights for the viewer
demos\               the teacher's recorded answers (imitation runs, or a junction to them)
```

## The machine

Each worker is a whole headless Minecraft server, about 1.85 GB of memory with its 1.5 GB heap, and uses 2.5 to 4
cores: its server thread, plus terrain generation and garbage collection. On a 32 GB machine, memory decides how many
fit. The build protects the machine:

| Gradle property | Default | What it does |
| --- | --- | --- |
| `maxWorkers` | 16 | ceiling on workers in a round |
| `memoryReserve` | 3 | GB left for everything else when deciding how many workers fit (heap + 0.5 GB each) |
| `memoryFloor` | 1.5 | GB of free memory below which the workers are stopped rather than left to swap |
| `workerCpus` | auto | cores each server sees |
| `workerStagger` | 1 | seconds between starting workers |
| `terrainPool`, `terrainUses` | 8, 8 | kept terrain worlds, and how often each is reused |

The trainer caps itself at half the GPU's memory and falls back to the CPU if an update runs out. An update takes 0.5 to
1.6 s on an RTX 4070 Ti SUPER.

Throughput today: about 3.5k game ticks per second per worker inside training; vs-copy on 4 workers does about 12–16k.
Every worker pauses while the trainer learns (about a fifth of the time), and each iteration waits for the slowest
worker. Work to remove both is under way.

## Troubleshooting

| Symptom | Cause, fix |
| --- | --- |
| "Run 'X' is already training" | a live trainer for that run; watch it, or `scripts\stop.ps1 -Run X` first |
| "Stopped the workers to protect the machine" | free memory fell under `memoryFloor`; use fewer workers, or close things |
| a worker "stopped with exit code 1" | read its `mod\fabric\build\training\<run>\worker-N\log.txt`; `OutOfMemoryError` means the heap is too small |
| iterations suddenly take many times longer | one worker is slow and every worker waits for it; check its garbage collection with `jstat -gcutil <pid>` |
| `PermissionError` on a rename | fixed in `mmai/files.py`; a reader held the file open |
| win rate jumps around | training explores; judge by the evaluation columns |

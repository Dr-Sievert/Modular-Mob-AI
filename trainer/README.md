# trainer

The training side. The game in `../mod` runs the fights *and* the network that fights them; this only learns better
weights from what the game recorded and hands them back. Nothing here runs inside a game tick.

## Setting up

Once, from the repository root:

```
scripts\setup.ps1
```

That makes `.venv` here, installs PyTorch (CUDA build; `-Cpu` for the CPU one) and `requirements.txt`, and finishes with
the parity check. By hand it is:

```
python -m venv .venv
.venv\Scripts\activate
pip install torch --index-url https://download.pytorch.org/whl/cu128
pip install -r requirements.txt
```

## Training

```
scripts\train.ps1            10,000 battles on run 'default', then stops; resumes where the run left off
scripts\watch.ps1            in a second terminal: iterations, win rate, workers, memory, GPU
```

`train.ps1` runs `gradlew :fabric:runTraining`, which checks parity, writes the game's schema into the run folder,
starts `train.py loop` here, and then runs rounds of battles until the battle count is reached. The run lives in
`../runs/<name>`; everything both sides write goes there, including this side's logs.

The words, smallest first:

- A **battle** is one fight, agent against vindicator, until one dies or a minute passes, which is a loss.
- An **iteration** is one policy. Every worker plays under iteration N's weights until it has taken its share of
  `--rollout-steps` (16,384 by default, a few dozen to a hundred or so battles), writes its shard and waits; once every
  worker has delivered, this learns from all of it and writes iteration N+1's weights, and the workers carry on without
  restarting.
- An **epoch** is one pass of PPO over one iteration's data, in minibatches of 2,048 steps. Each iteration runs up to
  four, stopping early once the policy has moved as far as `target_kl` allows.
- A **round** is how many battles one set of worker processes fights before being replaced, 250,000 by default
  (`scripts\train.ps1 -RoundSize`). It only exists so that a worker that crashes costs at most the rest of its round;
  starting workers takes about half a minute, so a round is large enough to make that a small share of it.

The loss is PPO's: the clipped policy objective, plus half the critic's clipped squared error, minus a hundredth of the
entropy of the three heads, so the policy keeps exploring.

Every field of `Config` in `mmai/ppo.py` is a flag, passed through with `-Extra`:

```
scripts\train.ps1 -Extra '--entropy-coef 0.003 --learning-rate 1e-4'
scripts\train.ps1 -Device cpu
```

A run resumes from `state.pt` in its folder; `checkpoints/` keeps a copy every `--checkpoint-every` iterations. Only the
last few weight files are kept, plus one per checkpoint.

## Reading the log

One line per iteration:

```
13:23:46 mmai.ppo    iteration    12  steps     196,608  episodes    41  win  31.7%  return  +0.412  length  240.5  |  pi -0.0154  v 0.1855  ent 9.834  clip 0.217  kl 0.0187  ep 2  |  drift 9.5e-07  cuda  1.8s  |    12.4m
```

`win`, `return` and `length` are over the fights that finished during the iteration, in the game's own units. `kl` is how
far the policy moved; it should sit near `target_kl` (0.02), and `ep` says how many of the epochs ran before the early
stop caught it. `clip` above about 0.3 or `kl` above 0.05 means the update is too aggressive and the learning rate or
rollout size wants changing. `v` should fall over the first few dozen iterations and then stay small.

`drift` is the largest difference between the log probability the game recorded for an action and the one this side works
out for it on replay. It should stay around a millionth. Anything above a thousandth is logged as a warning and means the
two sides disagree about the network, which makes every ratio in the update wrong: stop and run `scripts\parity.ps1`.

## What is here

| File | Holds |
| --- | --- |
| `train.py` | Entry point: `loop`, `init`, `parity`. |
| `mmai/schema.py` | The observation and action layout, parsed from what the game wrote. Nothing is hardcoded. |
| `mmai/model.py` | The exported actor, the critic that never leaves this side, the normaliser and the reward scaler. |
| `mmai/weights.py` | The `.mbw` weight file and the flat parameter layout both sides agree on. |
| `mmai/rollout.py` | Reading the game's `.mbr` shards into per agent segments. |
| `mmai/run.py` | The run folder: shards in, weights out, status, and who still has to deliver. |
| `mmai/ppo.py` | `Trainer`: replay, advantages, the PPO update, checkpoints. |
| `mmai/evaluate.py` | Evaluation of checkpoints, the best weights, and when a run is done. |
| `mmai/league.py` | A league run's matchmaking, Elo ratings and tables; turned on by the build for `-Psuite=league`. |
| `mmai/parity.py` | The fixture the game checks its own forward pass against. |
| `tests/` | Unit tests, `python -m unittest discover -s tests` from here, or `scripts\league.ps1 -Test`. |

The file formats themselves are written down once, in [`../docs/architecture.md`](../docs/architecture.md#files).

## How the training works

The game records raw observations, what it chose, the log probability of what it chose, and the hidden state each
segment started from. An update first replays the policy that acted over those observations, from those hidden states,
which recovers the memory at every step without the game storing it; then the critic values every step, generalised
advantage estimation runs over each segment, and PPO trains on fixed length chunks that each start from the replayed
memory.

The critic is its own network, with a memory of its own and fourteen privileged inputs the actor never sees: what only
this side knows about the episode, and what only the game knows about the other side, which it writes into every rollout
row. No gradient of the value loss reaches the actor, and the game never has to carry weights it will not use. See
[`../docs/training.md`](../docs/training.md#the-critic) for every column and why each one is there.

Three things keep it stable, all standard: rewards are divided by the running spread of the return so the value loss stays
comparable to the policy loss whatever the reward is measured in; the value estimate is clipped the same way the policy
is; and an update stops early once the policy has moved `target_kl` from the one that collected the data. The
observation statistics move once per iteration, after the update, and travel inside the next weight file.

If the GPU fails underneath an update, which torch reports as a CUDA error, the update is redone on the CPU and the run
carries on there. A GPU that does this has hung and needs looking at before it is trusted again.

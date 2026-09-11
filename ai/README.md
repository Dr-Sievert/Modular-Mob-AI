# ai

The training side. The game in `../minecraft` runs the fights; this decides what the agents do in them.

## Why it is a separate process

The network is trained in PyTorch, and keeping it there means there is one model rather than a trained one and an
exported one that can quietly disagree. The cost is a socket round trip inside every server tick, which measurement says
is affordable: a worker gets roughly ten milliseconds per tick, and a batched forward pass over the agents alive in that
tick uses a small fraction of it.

Shipping is the other way round. A player's machine has no Python, so the finished network is exported and run inside
the game. That is a different `Brain` implementation behind the same interface, not a different entity.

## Setting up

Once, from this folder:

```
python -m venv .venv
.venvScriptsctivate
pip install torch --index-url https://download.pytorch.org/whl/cu128
pip install -r requirements.txt
```

## Training

Run the **Fabric Training** (or NeoForge Training) configuration from the IDE, or:

```
gradlew :fabric:runTraining -Parenas=5000
```

That starts `serve.py` from the environment above, waits for it to listen, then runs rounds of arenas against it until
stopped. The game exits after every round and comes back for the next; the network lives in the Python process and
keeps learning across them. Its log is forwarded into the build output prefixed `[ai]`, so the `[ppo]` lines show up
alongside the arena summaries. Checkpoints land in `checkpoints/`.

A round is `arenas` fights, and each round costs a game start, so training wants far more per round than a quick
check does; five thousand is a reasonable floor. `-Prounds=N` runs a fixed number instead of going until stopped.
`-PtrainArgs="..."` passes flags through to `serve.py`, which is how a checkpoint is resumed or a hyperparameter
changed:

```
gradlew :fabric:runTraining -Parenas=5000 -PtrainArgs="--checkpoint checkpoints/update-000100.pt"
```

Stopping the run stops the training process with it. The two can also be run by hand, `python serve.py` in one
terminal and `gradlew :fabric:runGametestParallel -Pbrain=remote` in another, which is the same thing without the
round loop. Without `-Pbrain=remote` the game uses its own scripted fighter and never connects, which is what keeps the
game tests runnable on their own.

`serve.py` trains from scratch by default. Every field of the PPO config is a flag, so `--rollout-steps 32768
--entropy-coef 0.005` works without editing anything. `--policy random` and `--policy echo` are stubs for checking the
connection and the observation without a network involved.

## Reading the log

Everything goes to the console, which the game's build forwards into the IDE prefixed `[ai]`, and to a file under
`logs/` named for when the run started. The file is what survives a run being stopped, which is how every run ends.

One line per update:

```
11:23:01 mmai.ppo    update   24  steps      24,608  episodes    32  win 100.0%  return +31.750  length   40.0  |  pi -0.0154  v 0.1855  ent 11.349  clip 0.217  kl 0.0187  ep 2  |  cuda  0.2s  batch   165 rows/3.0 workers  |     0.1m
```

`win`, `return` and `length` are over the episodes that finished since the last update, in the game's own units.
`kl` is how far the policy moved; it should sit near `target_kl` (0.02), and `ep` shows how many of the epochs ran
before the early stop caught it. `clip` above about 0.3 or `kl` above 0.05 means the update is too aggressive and the
learning rate or rollout size wants changing. `v` should fall over the first few dozen updates and then stay small.
`batch` is how many agents and how many workers each call on the network covered; the workers figure should be close
to the number of workers running, or the batching is not catching them.

## What is here

| File | Holds |
| --- | --- |
| `mmai/protocol.py` | The whole conversation with the game: framing, message layout, reading and writing. |
| `mmai/schema.py` | The observation layout, parsed from what the game sends. Nothing is hardcoded. |
| `mmai/policy.py` | The `Policy` interface, plus stubs that flail or do nothing. |
| `mmai/model.py` | The network, the observation normaliser, and the reward scaler. |
| `mmai/buffer.py` | Per agent trajectories, advantage estimation, and chunking for the recurrent update. |
| `mmai/ppo.py` | `PPOPolicy`: acting, the update loop, checkpoints. |
| `mmai/server.py` | Accepts workers and answers their steps, one thread each. |
| `serve.py` | Entry point. |

## The schema is not written down here

The game sends its observation layout when it connects, and `schema.py` parses it. There is deliberately no copy of that
layout in this folder.

A schema written down in both places drifts the moment one side is edited, and the failure is silent: nothing crashes,
the network simply reads health out of whichever slot used to hold it and plays badly for reasons nobody can find. The
handshake also refuses a worker whose layout disagrees with the one already connected, since steps from two different
games cannot share a network.

## The conversation

Little endian throughout, so a message reads straight into a numpy array. No lengths are sent: every array's size
follows from the agent count and the dimensions agreed in the greeting.

```
HELLO    u32 magic, u16 version, u32 workerId, u32 schemaLength, utf8[schemaLength]
(reply)  u8 status                                    0 accepts, anything else refuses

STEP     u8 type=1, u32 count, i32[count] ids, u8[count] flags, f32[count] rewards, f32[count*obsDim] obs
ACTIONS  u8 type=2, u32 count, f32[count*actDim] actions
BYE      u8 type=0
```

`flags` carries two bits. `NEW` means an agent's episode has just started and its hidden state should begin from
scratch. `DONE` means this is the last step of an episode: the observation is real and worth bootstrapping a value from,
the action is ignored, and the agent will not appear again.

Agents are identified by `(workerId, agentId)`. An entity id only means something inside one game process, so a parallel
run has several agents called seven, and pairing the id with the worker is what keeps their hidden states apart.

## How the training works

There is no separate rollout phase. The game asks for actions once a tick, `PPOPolicy` answers and remembers what it
said, and once `rollout_steps` have piled up it stops answering for a moment, learns from them, and carries on. The game
blocks on the socket while that happens, which is what an on-policy method wants: nothing is collected from a policy
that is about to change.

The network is an encoder, a GRU, and one head per kind of control. The GRU carries a hidden state per agent across
ticks, reset when the game flags an episode as new. Trajectories are cut into `seq_len` chunks for training, each
remembering the hidden state it started from.

Acting runs on the CPU, where a batch of fifty agents through a small GRU beats the round trip to a GPU and stays inside
the game's tick budget. Learning runs on `--device`, CUDA when available, and the acting copy is refreshed after every
update.

The workers are not answered one at a time. Their steps are gathered into one call on the network per tick, because a
forward pass over three hundred agents costs barely more than one over a hundred and the alternative is three in a
row. The workers fall into step by themselves, since they are all released by the same batch; one that is genuinely
busy elsewhere is waited for only as long as `--window` allows. Measured on the CPU, a tick costs about a millisecond
for fifty agents and two for a hundred and fifty, nearly all of it the network itself.

If the GPU dies underneath an update, which torch reports as a CUDA error on the next kernel launch, the last good
weights are written to `checkpoints/before-gpu-fault-*.pt` and the rest of the run continues on the CPU. Nothing
learned is lost, since the acting copy was already on the CPU; only the optimiser's momentum starts over. The `[ppo]`
lines show which device each update ran on. A GPU that does this has hung, and needs looking at before it is trusted
again: a driver can recover from a hang once, and the time it cannot is a blue screen.

Three things keep it stable, all standard: rewards are divided by the running spread of the return so the value loss
stays comparable to the policy loss whatever the reward is measured in; the value head is clipped the same way the
policy is; and an update stops early once the policy has moved `target_kl` from the one that collected the data.

## What is missing

A way to watch it. The log says whether it is learning; nothing yet shows what it learned. The checkpoints are plain
`torch.save` dictionaries, so exporting one for the game is straightforward when the in-game brain exists.

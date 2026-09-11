# ai

The training side. The game in `../minecraft` runs the fights; this decides what the agents do in them.

## Why it is a separate process

The network is trained in PyTorch, and keeping it there means there is one model rather than a trained one and an
exported one that can quietly disagree. The cost is a socket round trip inside every server tick, which measurement says
is affordable: a worker gets roughly ten milliseconds per tick, and a batched forward pass over the agents alive in that
tick uses a small fraction of it.

Shipping is the other way round. A player's machine has no Python, so the finished network is exported and run inside
the game. That is a different `Brain` implementation behind the same interface, not a different entity.

## Running it

```
pip install -r requirements.txt
python serve.py
```

Then start the game pointed at it:

```
gradlew :fabric:runGametestParallel -Pbrain=remote
```

Without `-Pbrain=remote` the game uses its own scripted fighter and never connects, which is what keeps
the game tests runnable on their own.

## What is here

| File | Holds |
| --- | --- |
| `mmai/protocol.py` | The whole conversation with the game: framing, message layout, reading and writing. |
| `mmai/schema.py` | The observation layout, parsed from what the game sends. Nothing is hardcoded. |
| `mmai/policy.py` | The `Policy` interface, plus stubs that flail or do nothing. |
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

## What is missing

The network. `RandomPolicy` proves the connection end to end and nothing more. PPO with a recurrent policy, the rollout
buffer and the training loop all still have to be written against the `Policy` interface in `policy.py`.

# decisions: the learned arbitrator, frozen

What to do next. One observation plus one candidate action in, one score out; the highest score
wins, or a softmax at T = 0.25 samples among them the way the sim does. Schema id
`dwarfsim-imitator-v1` against world layout `dwarfsim-v1`, layout sha256
`b7dba1c41f7d3e908f47118533714b55ed2060af7da3e8c8811d0ca3bf35b69e` (the sha256 of `layout.json`).

Files: `imitator.npz` (six float32 arrays), `imitator.json` (dims, layout, training run, metrics),
`layout.json`, `parity.jsonl` (200 pairs with their exact scores), this file.

It was trained to imitate the hand-written weight table and makes 97.8% of
its choices on held-out seeds (43,128 decisions, chance
5.9%). The table is still the teacher and still the readable
explanation; this is the same function with the terms folded in.

## 1. The observation: 69 floats

| block | at | size | what |
| --- | --- | --- | --- |
| `mind` | 0 | 44 | the whole mind vector, below |
| &nbsp;&nbsp;`mind.emotions` | 0 | 4 | anger, fear, happiness, grief |
| &nbsp;&nbsp;`mind.needs` | 4 | 4 | hunger, thirst, fatigue, social |
| &nbsp;&nbsp;`mind.traits` | 8 | 6 | bravery, greed, temper, sociability, pride, forgiveness |
| &nbsp;&nbsp;`mind.health` | 14 | 1 | health / 20 |
| &nbsp;&nbsp;`mind.inventory` | 15 | 5 | ore, gold, food, ale, weapon quality; each count divided by ore 10, gold 20, food 10, ale 10, weapon quality already 0..1 |
| &nbsp;&nbsp;`mind.focus` | 20 | 24 | 4 relationship slots of 6: present, trust, respect, hatred, grudge, gratitude; filled by salience, padded with zeros |
| `place` | 44 | 6 | one-hot over FORGE, MINE, TAVERN, HALL, FARM, GATE |
| `crowd` | 50 | 1 | living dwarves here / 6 |
| `monster` | 51 | 1 | a monster is here |
| `monster_hp` | 52 | 1 | its health fraction |
| `under_attack` | 53 | 1 | hit within the last 12 ticks |
| `hit_age` | 54 | 1 | ticks since the last hit / 50 |
| `alive_fraction` | 55 | 1 | living dwarves / starting count |
| `clock` | 56 | 1 | (tick % 200) / 200 |
| `goals` | 57 | 6 | strength per goal: GET_RICH, AVENGE, PROTECT, REPAY, BEFRIEND, KEEP_PEACE |
| `obligations` | 63 | 2 | open owed by me, owed to me, each / 3 |
| `memory` | 65 | 2 | memories held / 64, mean salience |
| `is_chief` | 67 | 1 | am I the chief (0 when nobody is) |
| `chief_here` | 68 | 1 | the chief is standing here |

Everything is roughly 0..1 (a few relationship columns are signed). There is no normaliser and no
clip: the vector is built already scaled, which is why nothing about a normaliser travels with the
weights the way the combat network's does.

## 2. The candidate: 64 floats

One proposed action. `[0, 22)` is a **skill one-hot**, `[22,
64)` are the **raw, unweighted term values** the hand-written table would have
multiplied by its weights.

Skills, in the frozen order (appended to, never reordered):

0 `WORK`, 1 `EAT`, 2 `DRINK`, 3 `REST`, 4 `SOCIALIZE`, 5 `STEAL`, 6 `ATTACK`, 7 `FLEE`, 8 `APOLOGIZE`, 9 `FIGHT_MONSTER`, 10 `IGNORE`, 11 `RETORT`, 12 `DEMAND_APOLOGY`, 13 `REFUSE_APOLOGY`, 14 `COMPLAIN_TO`, 15 `AVOID`, 16 `GOSSIP`, 17 `ACCEPT`, 18 `REFUSE`, 19 `BARGAIN`, 20 `FULFIL`, 21 `PUNISH`

Terms, in the frozen order:

0 `base`, 1 `need_hunger`, 2 `need_thirst`, 3 `need_fatigue`, 4 `need_social`, 5 `anger`, 6 `fear`, 7 `happiness`, 8 `grief`, 9 `bravery`, 10 `greed`, 11 `temper`, 12 `sociability`, 13 `anger_at_target`, 14 `hatred_target`, 15 `trust_target`, 16 `respect_target`, 17 `being_attacked`, 18 `monster_threat`, 19 `hurt`, 20 `wealth_drive`, 21 `supply_pressure`, 22 `request_pull`, 23 `distance_cost`, 24 `noise`, 25 `pride`, 26 `forgiveness`, 27 `grudge_target`, 28 `gratitude_target`, 29 `reputation_target`, 30 `provoked_by_target`, 31 `publicity`, 32 `humiliation`, 33 `chief_present`, 34 `expected_punishment`, 35 `fear_target`, 36 `goal_bias`, 37 `obligation_pressure`, 38 `ask_cost`, 39 `payment_offered`, 40 `gossip_value`, 41 `punish_pressure`

`base` is always 1.0. `noise` is one draw per candidate per decision, from the sim's own RNG: in the
game it is what keeps identical agents from moving in lockstep, and a port that wants determinism
sets it to 0 for every candidate rather than dropping the column.

## 3. Forward pass

| # | array | shape | role |
| --- | --- | --- | --- |
| 0 | `fc1.weight` | 64x133 | first hidden layer, (hidden1, OBS_SIZE + CAND_SIZE) |
| 1 | `fc1.bias` | 64 | (hidden1,) |
| 2 | `fc2.weight` | 64x64 | second hidden layer, (hidden2, hidden1) |
| 3 | `fc2.bias` | 64 | (hidden2,) |
| 4 | `out.weight` | 1x64 | the score, (1, hidden2) |
| 5 | `out.bias` | 1 | (1,) |

Linears are stored the way torch stores them, `weight` is `(out, in)` row major, `y = x @ weight.T
+ bias`.

```
x     = observation ++ candidate                         # 133 floats
h     = max(x @ fc1.weight.T + fc1.bias, 0)              # 64
h     = max(h @ fc2.weight.T + fc2.bias, 0)              # 64
score = (h @ out.weight.T + out.bias)[0]                 # one float
```

No normalisation, no embedding, no residual, no recurrence: six arrays, 12801
parameters, two matrix products and a dot.

**One decision, many candidates.** The observation is the same for every candidate, so split the
first layer's weight in two at column 69: the observation half times the observation
is computed once and added to the candidate half times each candidate row. That is what the numpy
reference does and what the port should do, because a decision is 8 to 30 candidates.

## 4. parity.jsonl

200 pairs, one JSON object each, drawn from a fresh collection of the teacher playing
20 runs (400 ticks, seeds
[1, 2] plus targeted [9, 10]), round-robin over
the skills so the rare reactions are represented as heavily as `WORK`: 21 of the
22 skills occur, and the missing ones are the ones these scenarios never open
(`PUNISH`). A port passes when every score matches to **1e-5** absolute.

| field | what |
| --- | --- |
| `i` | the record index, 0..199 |
| `scenario`, `seed`, `decision`, `candidate` | where it came from; not input |
| `skill` | the candidate's skill, the one-hot's name; not input |
| `chosen` | whether the sim actually took this candidate; not input |
| `obs` | the 69 observation floats, exactly as they go in |
| `cand` | the 64 candidate floats, exactly as they go in |
| `teacher` | the hand-written table's score, for context; **not** what parity checks |
| `score` | **the answer**: this model's score for that pair |

The field that is checked is `score`: run `obs ++ cand` through the pass above and compare. `obs`
was stored as float16 in the collection and widened to float32, which is exactly what the reference
scored, so there is nothing to round.

# How dwarfsim works

A settlement of dwarves, no game engine. The point is the shape of the thing: **small skills that
propose, persistent emotional and social state that colours the proposals, one arbitrator that picks,
and a log that says exactly why**. The arbitrator is a hand-written weight table today, and the
interface it sits behind is the one a tiny network would use tomorrow.

```
                 skills propose                 arbitrator scores and samples
  world  -->  WORK EAT DRINK REST SOCIALIZE -->  sum(weight[skill][term] * term) --> one action
    ^  ^      STEAL ATTACK FLEE APOLOGIZE         softmax at T = 0.25                    |
    |  |      FIGHT_MONSTER  GOSSIP                                                      |
    |  |      IGNORE RETORT DEMAND_APOLOGY                                                |
    |  |      REFUSE_APOLOGY COMPLAIN_TO AVOID                                            |
    |  |      ACCEPT REFUSE BARGAIN FULFIL PUNISH                                         v
    |  |                                                                                 |
    |  +-- goals (what I still want) <-- memory (what I remember) <-----------------+     |
    |                                        ^                                     |     |
  mind  <----------- mind.apply_event(): the one table of deltas <----------------- events
```

Everything above the arbitrator is unchanged from v0. What v1 adds is *state between ticks that is
not a relationship number*: a bounded list of episodes, a handful of standing wants, and a ledger of
who owes what to whom. All three feed the arbitrator as ordinary named terms, so the log still says
exactly why, and a learned scorer still sees one flat vector.

v2 adds two more of the same shape, and both of them are about a number that meant too much. A blow
used to spend *health* and only health, so every quarrel was a step toward a funeral: dwarves now
carry a **condition** -- see [Injuries](#injuries) -- and a fist fight ends in a black eye. And a
kind word used to buy *trust* however many times it had been said before, so the cheapest way to be
adored was a clipboard: words now buy a decaying **warmth** while deeds buy trust, and a listener
learns to hear a repeated compliment for what it is. See
[No approval farming](#no-approval-farming).

## The mind

Per dwarf, all persistent, all in `dwarfsim/mind.py`:

| Block | Fields | Range | Behaviour |
| --- | --- | --- | --- |
| emotions | anger, fear, happiness, grief | 0..1 | decay toward a per-dwarf baseline every tick; fear burns off fastest (5.5% of the gap a tick), grief slowest (0.6%) |
| needs | hunger, thirst, fatigue, social | 0..1 | rise every tick (0.003 to 0.005), dropped by EAT, DRINK, REST, SOCIALIZE |
| traits | bravery, greed, temper, sociability, pride, forgiveness | 0..1 | static, rolled from the seed |
| relationships | trust, respect (-1..1), hatred (0..1), **warmth** (0..1) per other dwarf *and for the player* | | the first three decay toward 0 at 0.15% a tick, a half-life of about 460 ticks; warmth at 0.3%, about 230 ticks |
| condition | the injuries this dwarf is carrying, each with a kind, a severity and a healing rate | | `dwarfsim/condition.py`, below |
| memory | up to 64 episodes, each with its own salience | | `dwarfsim/memory.py`, below |
| goals | up to 4 standing wants, each with a strength | 0..1 | `dwarfsim/goals.py`, below |
| obligations | the open asks this dwarf is on either end of | | `dwarfsim/obligations.py`, below |

`pride` is how much a slight in front of other people costs, and it is the one trait that only
matters in company: it is multiplied by how many were watching. `forgiveness` sets how fast an
episode's salience fades, which is what lets two dwarves with identical relationship numbers hold a
grudge for wildly different lengths of time.

`trust` is "would I leave my ore with them", `respect` is "do they matter, are they dangerous, did
they stand at the gate", `hatred` is "would I swing at them". They are separate because they move
separately: being hit costs trust *and* buys respect. `warmth` is the odd one out and the newest:
it is how well disposed a dwarf *feels* right now, as against what it believes. Words buy warmth,
deeds buy trust -- see [No approval farming](#no-approval-farming) -- and everything that asks "do
I like this dwarf" reads **felt trust** (`regard.felt_trust`, trust plus 0.6 of warmth) while
everything that asks "do I rely on this dwarf" reads trust alone.

Three modulators apply to every delta, and they are what make two dwarves react differently to the
same event:

* **temper** scales anger gains, x0.6 to x1.4.
* **bravery** scales fear gains, x1.4 down to x0.6.
* **diminishing returns**: a change pushing a relationship away from neutral is multiplied by the
  headroom left (`1 - |current|`). The hundredth kind word is worth almost nothing, nothing pins at
  1.0, and a change back toward neutral is never damped. Without this the settlement converged on
  everybody adoring everybody within 300 ticks.

One more rule earns its keep now that dwarves talk about each other: **an episode is only learned
once**. Two memories are the same episode when their kind, actor, target and tick match, and a head
that already holds one does not take it again -- the second telling only makes it slightly louder,
and moves no opinion at all. Without that, one theft goes round the settlement forever and the sixth
retelling moves trust as much as the first.

## The event table

Every state change an event causes is in `EVENT_TABLE` in `dwarfsim/mind.py` and applied by
`mind.apply_event()`. Skills move inventory, health and needs; events move feelings; nothing else
does either. The table below is generated from the code.

| Event | Target feels | Target -> actor | Witness feels | Witness -> actor | Actor feels |
| --- | --- | --- | --- | --- | --- |
| `INSULT` | anger +0.26, happiness -0.10 | trust -0.10, respect -0.06, hatred +0.11 | anger +0.03 | trust -0.05, respect -0.02, hatred +0.04 | anger -0.04 |
| `SLUR` | anger +0.40, happiness -0.18, grief +0.05 | trust -0.18, respect -0.12, hatred +0.22 | anger +0.03 | trust -0.05, respect -0.02, hatred +0.04 | anger -0.02 |
| `PRAISE` | happiness +0.16, anger -0.07 | trust +0.07, respect +0.04, hatred -0.07 | happiness +0.02 | trust +0.03, respect +0.02 | happiness +0.04 |
| `SMALLTALK` | happiness +0.05 | trust +0.03 | happiness +0.01 | trust +0.01 | happiness +0.03 |
| `THREAT` | fear +0.22, anger +0.14 | trust -0.14, respect +0.04, hatred +0.09 | fear +0.08 | trust -0.07, respect +0.03, hatred +0.04 | anger -0.02 |
| `ACCUSE` | anger +0.17, happiness -0.05 | trust -0.09, hatred +0.06 | -- | trust -0.04, hatred +0.02 | anger +0.03 |
| `APOLOGY` | anger -0.22, happiness +0.07 | trust +0.11, respect -0.02, hatred -0.10 | -- | trust +0.03, respect -0.01 | anger -0.10, happiness +0.02 |
| `GIFT` | happiness +0.14, anger -0.06 | trust +0.16, respect +0.04, hatred -0.08 | happiness +0.02 | trust +0.05, respect +0.03 | happiness +0.03 |
| `STEAL` | anger +0.34, happiness -0.08 | trust -0.30, respect -0.05, hatred +0.18 | anger +0.05 | trust -0.16, respect -0.06, hatred +0.08 | fear +0.06, happiness +0.05 |
| `HIT` | anger +0.33, fear +0.26 | trust -0.24, respect +0.05, hatred +0.25 | fear +0.11, anger +0.03 | trust -0.11, respect +0.04, hatred +0.07 | anger +0.04, fear +0.03 |
| `KILL` | -- | -- | fear +0.38, grief +0.42, happiness -0.20 | see below | fear +0.10, grief +0.08, anger -0.15 |
| `HELP` | fear -0.10, happiness +0.08 | trust +0.08, respect +0.14, hatred -0.05 | fear -0.06, happiness +0.05 | trust +0.06, respect +0.14, hatred -0.04 | happiness +0.06 |
| `WARNING` | fear +0.13 | trust +0.04, respect +0.02 | fear +0.06 | trust +0.02 | -- |
| `IGNORE` | -- | -- | -- | -- | happiness -0.03 |
| `RETORT` | anger +0.21, happiness -0.06 | trust -0.06, respect -0.01, hatred +0.08 | anger +0.02 | trust -0.02, hatred +0.01 | anger -0.13, happiness +0.02 |
| `DEMAND` | anger +0.12, fear +0.05 | trust -0.03, respect +0.06 | -- | respect +0.03 | anger -0.05 |
| `REFUSE_APOLOGY` | anger +0.18, happiness -0.06 | trust -0.08, respect -0.04, hatred +0.07 | anger +0.02 | trust -0.06, respect -0.04, hatred +0.03 | anger +0.02 |
| `COMPLAIN` | anger +0.04 | trust +0.03, respect -0.01 | -- | -- | anger -0.06, happiness +0.03 |
| `GOSSIP` | happiness +0.02 | trust +0.01 | -- | -- | happiness +0.01 |
| `AVOID` | anger +0.04 | trust -0.02 | -- | respect -0.02 | fear -0.04, anger -0.05 |
| `ACCEPT` | happiness +0.06 | trust +0.05, respect +0.03 | -- | trust +0.01 | -- |
| `REFUSE` | anger +0.09, happiness -0.03 | trust -0.06, respect -0.04 | -- | respect -0.01 | -- |
| `BARGAIN` | anger +0.03 | trust -0.02, respect +0.03 | -- | -- | -- |
| `PROMISE_KEPT` | happiness +0.11, anger -0.05 | trust +0.19, respect +0.09, hatred -0.07 | happiness +0.02 | trust +0.06, respect +0.05 | happiness +0.06 |
| `BROKEN_PROMISE` | anger +0.22, happiness -0.09 | trust -0.30, respect -0.11, hatred +0.12 | anger +0.03 | trust -0.13, respect -0.07, hatred +0.03 | happiness -0.04 |
| `PUNISH` | anger +0.20, fear +0.18, happiness -0.12 | trust -0.05, respect +0.12, hatred +0.05 | fear +0.05 | respect +0.09, trust +0.03 | -- |

Two rows in the table only know about an actor and a target, but the acts they belong to are about
somebody who is not in the room. `GOSSIP` and `COMPLAIN` therefore also call
`mind.shift_opinion(listener, about, table, scale)`, which is the same machinery -- diminishing
returns included -- pointed at the third party. `PUNISH` uses it too, to take respect for the
culprit away from everyone standing there. Anything moved that way is in the event's `deltas` like
everything else, so the viewer still shows it.

Three rules on top of the table:

* **Witnesses react by a fraction.** Every witness delta is cut to 45% and then multiplied by
  `1 + likes(witness -> victim)`, where `likes` folds trust, respect and hatred into one number in
  -1..1. A friend of the victim reacts up to twice as hard; someone who hated the victim barely
  reacts at all.
* **A kill makes everyone take a side.** `KILL` has no witness relationship row in the table because
  it is computed: liked the dead, and the killer earns `trust -0.30, respect +0.10, hatred +0.35`;
  hated the dead, and the killer earns `trust -0.05, respect +0.30, hatred -0.10`, scaled by how
  strongly the witness felt.
* **Magnitude.** The caller passes a multiplier. Speech passes aggression, valence and urgency
  through `speech.magnitude_of()`, so a shouted threat lands about four times as hard as a muttered
  one.

## Episodic memory

`dwarfsim/memory.py`. Relationships are the running state the arbitrator reads every tick; memory is
the *episodes* behind those numbers -- what explains a grudge, what gossip carries, and what a
complaint is evidence of. One record:

| Field | What |
| --- | --- |
| `tick` | when it happened (not when it was heard about) |
| `kind` | the event kind: `INSULT`, `HIT`, `STEAL`, `GIFT`, `HELP`, `BROKEN_PROMISE`, ... |
| `actor` `target` | who did it, who it was done to |
| `place` | where |
| `intensity` | how loud it was when filed: a per-kind weight times the event's magnitude |
| `source` | `SEEN` (I watched it), `SUFFERED` (it was me), `HEARD` (I was told) |
| `from_id` | who told me, on a `HEARD` |
| `witnesses` | how many others were standing there |
| `answered` | whether I have already reacted to it |

Salience is `intensity * exp(-rate * age)` with `rate = 0.0016 * (0.35 + 1.3 * forgiveness)`, so a
bitter dwarf's half-life is about 1,200 ticks and a forgiving one's about 250. A head holds at most
64 episodes; over that the faintest is forgotten. Praise and apologies are deliberately *not*
remembered -- they are cheap and constant, and letting them accumulate turned gratitude into a
number that was always 1.0.

Two derived numbers are what the arbitrator actually reads, both softened to 0..1 with `x / (x+1)`:

* **`grudge(who)`** -- summed salience of harms `who` did to me, plus harms done to people I like,
  weighted by how much I like them. Hurting my friend is hurting me; hurting my enemy is not.
* **`gratitude(who)`** -- the same sum over helps, gifts and kept promises.

## Goals

`dwarfsim/goals.py`. Without these the arbitrator is memoryless between ticks and dwarves flip-flop:
insult, work, insult, drink. A goal is `(kind, target, strength, since)`, adopted from state, decayed
at 0.45% a tick, dropped when it falls below 0.08 or when it is satisfied. Four at a time, most of
them the strongest.

| Goal | Adopted when | Satisfied when |
| --- | --- | --- |
| `GET_RICH` | greed > 0.65 and fewer than 4 gold | 14 gold |
| `AVENGE(t)` | hatred of `t` > 0.50 **and** a remembered harm from them | hatred falls below 0.18 |
| `PROTECT(t)` | I saw `t` harmed in the last 120 ticks and I like them | I stop liking them |
| `REPAY(t)` | gratitude toward `t` > 0.35 | the gratitude fades |
| `BEFRIEND(t)` | trust > 0.50, hatred < 0.20, lonely | trust > 0.60 |
| `KEEP_PEACE` | I am the chief | never |

A held goal contributes one term, `goal_bias`, worth `strength x GOAL_MATCH[kind][skill]`, where the
match table also says whether the candidate has to be aimed *at* the goal's target (`@`), at anybody
else (`~`), or wherever (`*`). `AVENGE(Torvi)` pushes `ATTACK -> Torvi` up by 0.90 and
`APOLOGIZE -> Torvi` down by 0.90, and does nothing at all to an attack on somebody else. The bias
is deliberately smaller than the emotional terms: goals stop a dwarf changing its mind every tick,
they do not decide for it.

## Obligations

`dwarfsim/obligations.py`. A `REQUEST`, `COMMAND` or `OFFER` that carries a structured `ask` creates
a record instead of only the vague `request_pull` nudge:

```
ask = {"action": BRING | GIVE | GO_TO | FIGHT | STOP | HELP,
       "item": str | None, "quantity": int, "place": str | None,
       "target": agent id | None, "payment": gold the asker is offering}
```

```
PENDING --ACCEPT--> ACCEPTED --FULFIL--> KEPT
   |                    |
   |                    +---- deadline ----> BROKEN
   +--REFUSE--> REFUSED
   +--BARGAIN--> PENDING again, price raised, now the asker's turn to answer
   +-- 40 ticks --> EXPIRED
```

The obligated dwarf's arbitrator gets `ACCEPT`, `REFUSE` and `BARGAIN` candidates, scored on trust,
respect, `fear_target`, `ask_cost` (what doing it would actually cost me, computed per action by
`Obligation.cost`), greed and `payment_offered`. An accepted obligation adds a `FULFIL` candidate
every tick until it is done or the deadline passes, with `obligation_pressure` rising as the
deadline nears. **Gold moves when the obligation is accepted, not when it is kept** -- dwarves are
practical, and a prepayment that is then not earned is exactly the betrayal that should cost trust.
A kept promise raises trust both ways; a broken one drops it and writes a `BROKEN_PROMISE` memory,
which is a harm, which means gossip carries it.

Dwarves generate their own asks: `skills._wants_from` is an empty belly and somebody standing there
with two loaves, or a monster at the gate and somebody you trust. Whether it is a request, an order
or a paid offer comes from what the asker thinks of the asked.

## Speech

`speech.hear(world, speaker_id, listener_id, parsed)` is the only door between words and state, and
`parsed` has the seven fields in [../text/SCHEMA.md](../text/SCHEMA.md) plus one optional field this
sim adds. A text classifier plugs in here later by producing the same dict from a raw string;
nothing else changes.

**The optional `ask`.** `REQUEST`, `COMMAND` and `OFFER` may carry one more key:

```json
{"text": "Fetch it here, Brokk. Now.", "intent": "COMMAND", "topic": "MINE",
 "addressed": "LISTENER", "aggression": 0.35, "valence": -0.1, "urgency": 0.75, "names": ["Brokk"],
 "ask": {"action": "BRING", "item": "ore", "quantity": 2, "place": "MINE",
         "target": null, "payment": 3}}
```

Every field inside `ask` is optional except `action`, and an `ask` naming an action the sim does not
know is ignored rather than refused. With it, the utterance creates an obligation and the event
logged is `ASK` rather than `REQUEST`; without it `hear()` behaves exactly as it did, so a
classifier that never produces the field loses only the bargaining. Nothing else in the dict
changes meaning, which is the point: the seven-field contract with the classifier still holds.

| Intent | Becomes |
| --- | --- |
| `INSULT` `THREAT` `ACCUSE` | the same event, magnitude `0.45 + 0.85*aggression + 0.35*max(0,-valence)` |
| `PRAISE` `OFFER` `APOLOGY` | `PRAISE` / `GIFT` / `APOLOGY`, magnitude `0.5 + 0.8*max(0,valence)` |
| `WARNING` | `WARNING`, magnitude `0.5 + 0.8*urgency` |
| `REQUEST` `COMMAND` | not an event: a standing task proposal on the listener, weighed by trust |
| `GREET` `FAREWELL` `SMALLTALK` `QUESTION` | `SMALLTALK` |
| any of them, with a tier 3 term in it | `SLUR` -- see [Profanity](#profanity) |

`addressed` decides who it lands on: `THIRD` retargets the event at whoever is named (the pool is
exact-match, so "Don't trust Sindri" hits Sindri, not the listener), `NONE` is muttering and is cut
to 40%, `GROUP` to 80%. A `REQUEST` or `COMMAND` sets `agent.request`, which the arbitrator reads as
the `request_pull` term: `urgency * (0.5 + 0.5*trust in the speaker)`, applied only to candidates at
the place the topic points to, and expiring after 80 ticks. An order from someone you distrust is
worth nothing, which is the whole point of having the trust number.

`World.say(speaker, listener, parsed)` is the same door from outside, for a player's line.

**The player** is speaker id `"player"` -- an ordinary outsider, not a special case. It is an
`Agent` that is not in `world.agents`, so it never decides, never ticks and is never a witness; it
has one relationship row in every dwarf, starting neutral, and it appears in the roster and in the
reputation row like anybody else. Besides `World.say` it has three doors, all of which go through
the same event table:

| Door | What it is |
| --- | --- |
| `World.player_help(dwarf)` | a `HELP`: standing between them and whatever is at the gate |
| `World.player_give(dwarf, gold)` | a `GIFT`, and the gold actually moves |
| `World.player_promise(dwarf, ask)` | an obligation with the player on the hook, settled by `World.fulfil_obligation` |

The measurable consequence: the same `COMMAND` with the same `ask`, given by a stranger, is refused
or haggled over; given after help, a gift and a kept promise, it is taken on. Nothing about the
order changes -- only trust, `gratitude_target` and the `REPAY` goal it produced.

## Profanity

Two jobs that must not be confused, both in `dwarfsim/profanity.py`, one `Lexicon` doing both.

**Recognition** reads `text/profanity.json`, the user's file: three tiers of
`{term, kind, targets, strength, example}`, tier 1 mild undirected swearing, tier 2 crude
expletives and personal insults, tier 3 group-targeted slurs. `Lexicon.scan` matches whole words
and whole phrases, case-insensitively, with the spellings exactly as they are listed and with an
`*` in a term as a one-character wildcard. One refinement earns its keep: when the file already
lists a term spelled out in letters (`hell` beside `h*ll`, `shit` beside `s**t`), the wildcard
entry keeps the substitutions -- `f*ck`, `f4ck`, `f#ck` -- and gives up the letters, because
otherwise `h*ll` finds *hall*, `s**t` finds *salt* and *soot*, and `a**` finds *ask*, which in a
mine is most of the conversation.

`speech.screen()` is where a scan becomes state, and it runs on every line before anything else in
`speech.hear` -- so the sim's own dialogue and whatever you type in the talk app are read by the
same code:

| What was matched | What it does to the labels |
| --- | --- |
| an `expletive` aimed at `none` | `aggression` up to the term's strength; the intent is left alone |
| an `insult` or `slur` aimed at a `person` or a `group` | that, plus `intent` becomes `INSULT`, `valence` drops to at most -0.5, and an `addressed` of `NONE` becomes `LISTENER` |
| anything in tier 3 | `aggression` 1.0, `valence` at most -0.8, and the event is a `SLUR` |

The intent override is what stops "dumb <term>" from being read as small talk that *raises* the
listener's happiness, which is exactly what a classifier trained on sentence shape does with it.
"What the hell happened to the ore bins" keeps its intent and only moves aggression, because an
oath about the world is not an insult to anybody.

`SLUR` is one row in the event table, above: on the one it was aimed at it is worth about one and a
half insults in anger and twice one in hatred, and the room takes it exactly as it takes an insult
(the witness rows are equal on purpose -- what makes a slur worse is what it does to the person it
was aimed at, not what the bystanders do about it). It is in `HARM_KINDS` and `PROVOCATION_KINDS`,
so the graded reactions can answer one.

A `SLUR` is only ever what `speech.hear` makes of a line -- anything the player says, and a dwarf's
own insult, which is the path that goes through it. The reaction skills (`RETORT`, `DEMAND`,
`REFUSE_APOLOGY`, `REBUKE`) emit their own event directly and keep it: a retort with a slur in it is
still the retort it was, and what the slur does to it is the magnitude, which `screen` has already
taken to 1.0 aggression -- about 1.6x the blow of the same retort said politely. Turning those into
`SLUR` instead would lose the reaction the log's provocation tally is counting.

**Speech** is a different list per tier, capped by `Lexicon.max_tier` -- the `--profanity 0..3`
setting the world and the talk app carry, default 1:

| Setting | What a dwarf will say |
| --- | --- |
| 0 | nothing, ever |
| 1 | a mild oath from the file's tier 1, when it is annoyed |
| 2 | and the file's tier 2 at a dwarf it is angry with |
| 3 | and an in-world slur at one it hates |

Whether the slot is filled at all is `anger * (0.6 + 0.8*temper)`: below 0.28 nothing, above it a
mild oath, above 0.45 something crude *about the dwarf it is angry with*, and a slur only where
hatred of that dwarf is over 0.45. The draws come from `world.swear_rng`, a stream of its own
seeded off `--seed`, so a settlement says the same words every run and turning swearing on spends
none of the randomness the rest of the sim is drawing from.

A swear word is a slot in a line, never a line: `speech.utterance()` picks the template as it
always did and `profanity.fill()` puts the term into it -- an oath in front ("Damn, out of my way,
Brokk.") or a name for somebody at the end ("Mind your own beard, Gudrun, you petal-eater.").
`replies.py` fills the same slot, but only in an answer to a provocation: a greeting, a question
and an answer to an ask stay civil whatever the dwarf feels, because those cells are read back out
of the arbitrator and the obligation machinery.

**Tier 3 speech is the user's file.** What a dwarf says at tier 3 comes from
`--profanity-speech` (default `text/profanity_speech.json`), used verbatim: term, kind, targets
and strength as written. When the file is absent, empty or unreadable, `IN_WORLD_SLURS` in
`dwarfsim/profanity.py` stands instead. Terms that also sit in the recognition file are kept;
recognition and speech may share a list.

## Injuries

`dwarfsim/condition.py`. Before this, a fight was arithmetic on one number: twenty health, a blow
took two of it, and the tenth blow was a funeral -- so two dwarves who had had a drink and a
disagreement killed each other about as often as not, which is not what a brawl is.

Health stays, and it still means *how close to dying*. What changed is what a blow spends. An
unarmed blow mostly produces an **injury** and very little health; weapons, monsters, and hitting
somebody who is already broken are what kill. Each dwarf carries a `Condition`: a set of injuries,
each with a kind, a severity 0..1 and its own healing rate.

### The injury table

One row per kind, and nothing outside this table decides what an injury means. Each row is the
effect at severity 1.0 and scales linearly down to nothing.

| Kind | Heals (severity/tick) | What it does |
| --- | --- | --- |
| `bruised` | 0.0200 | sore, and nothing else: pain 0.10 |
| `cut` | 0.0120 | pain 0.18, work -5%, bleeds a trickle (0.004 hp/tick) |
| `black_eye` | 0.0100 | pain 0.15, weapon quality -10%, decisions 10% noisier |
| `sprained_hand` | 0.0070 | pain 0.30, **work halved, weapon quality halved** |
| `broken_arm` | 0.0022 | pain 0.60, work -80%, weapon -80%, and **blocks every skill that needs two hands** past severity 0.25: no mining, no forging, no farming, no fighting at the gate. Eating, talking, walking, gossiping and buying ale are untouched |
| `broken_leg` | 0.0020 | pain 0.60, work -35%, **blocks every skill that needs legs** past severity 0.30 (FIGHT_MONSTER), and gives up to a 75% chance per tick that a walk between places gets nowhere -- which is also what makes fleeing barely work |
| `concussion` | 0.0050 | pain 0.45, work -30%, **doubles the arbitrator's softmax temperature** and multiplies a new memory's intensity by 0.45 |
| `cracked_ribs` | 0.0040 | pain 0.50, work -30%, **attack force -45%**, and adds 0.30 to the `fear` *term* -- a flinch, not a mood, so the emotion itself is untouched |
| `bleeding` | 0.0060 | pain 0.35, attack -15%, **0.030 health a tick until it is treated** |

`pain` is summed over every injury and then softened (`x / (x + 1)`), so ten bruises are not a
broken leg. Everything heals on its own each tick, scaled by how fed and watered the dwarf is;
`REST` takes 0.020 off every injury at once and `EAT` 0.012, and both take **four times** that off
bleeding, which is the only thing that reliably stops a wound. When an injury finally goes the
world emits `RECOVERED` and the story says so: *Hrolf's arm has mended*.

### Resolving a blow

`condition.resolve_blow(rng, attacker, defender)` returns `(health lost, injuries, profile)` and
applies both. Three inputs:

* **strength**, 0..1, and it is *not* a new trait: `0.25 + 0.50 x bravery + 0.25 x rested`, times
  whatever the attacker's own injuries leave of it. Deriving it is what keeps the mind vector's
  trait block at six.
* **the weapon**, but only if it is *drawn*. Whether a dwarf is carrying an edge and whether they
  pull it are different questions, and the second is the line between a brawl and a killing:
  `draws_weapon` wants hatred past 0.55 before steel comes out at all, and then scales with hatred
  and with quality. Getting this wrong was the first version's whole problem -- every dwarf in the
  settlement carries something, so every blow was an armed one and nothing had changed.
* **how broken the defender already is**: the health cost is multiplied by `1 + 1.2 x pain`. This
  is the "repeated blows on somebody already badly hurt" clause, and it is what eventually kills.

One unarmed blow, measured over 8,000 of them: **65.1%** nothing at all, 17.3% bruised, 5.2% a
black eye, 3.4% a cut, 3.1% a sprained hand, 2.2% cracked ribs, 1.6% a concussion, 0.9% a broken
leg, 0.7% a broken arm, 0.6% bleeding. An armed blow reverses it -- 8% nothing, and the mass sits
on cuts, bleeding and broken bones -- and a monster's bite is its own profile again.

### What the rest of the sim makes of it

* **The arbitrator sees it.** The observation grew a compact eight-float condition block (see
  [The vectors](#the-vectors)), `hurt` now reads `max(how close to dying, pain)` rather than health
  alone, and a new term `impairment` prices how much *this candidate in particular* is hampered --
  a broken leg makes walking to the mine hopeless and saying sorry no harder at all.
* **Skills declare what a body has to be able to do.** `Skill.needs_two_hands` and
  `Skill.needs_legs`, with a per-candidate override, which is how buying ale at the tavern stays
  available to a dwarf who cannot swing a pick. A blocked candidate is not proposed at all, and the
  decision record names what was dropped, so the inspector does not show a dwarf mysteriously never
  choosing to mine.
* **The grudge is for what was broken, not for the arithmetic.** A `HIT`'s magnitude comes from
  `blow_magnitude(health lost, injuries)`, which weights an injury far above the health: a punch
  that takes two health and leaves nothing fades, and a broken arm is remembered whether or not it
  bled. Each injury records who caused it.
* **A hurt dwarf stops swinging.** `hurt` is -3.40 on ATTACK, -2.40 on FIGHT_MONSTER, and +2.40 on
  FLEE, +1.90 on AVOID, +1.40 on REST; `impairment` is -1.80 on ATTACK and -2.60 on FIGHT_MONSTER.
  Badly broken, the scorer's answer is to flee, avoid, complain or lie down.
* **They mention it.** A hurt dwarf adds a clause to whatever it was going to say ("Mind the arm."),
  the viewer's Mind panel and the talk app show the condition beside the health, and the story
  narrates both the break and the mending.

**200 unarmed brawls** -- full health, fists, three to twelve exchanges, stopping when one of them
is down to a quarter -- end in **0 deaths**, and 376 of the 400 participants carry something away.
Over ten seeds of 2,000 ticks the settlement as a whole is as deadly as it was before any of this:
19 kills against 18, 41 survivors of 60 against 42, off roughly twice the blows. That is the result
the model was for: the same mortality, arrived at through fights that are fights.

## No approval farming

`dwarfsim/regard.py`. "Wow, nice sword" pasted twenty times used to buy as much trust as twenty
different kindnesses, which made the cheapest way to be adored a clipboard. The answer is four
mechanics rather than a list of banned strings, all of them per *(listener, speaker)* and all of
them living on the listener, in `agent.regard`.

### Habituation

Every social event carries a **signature**: `(event kind, intent, topic, hash of the normalised
text)`. Normalising is lower-case, strip punctuation, collapse spaces; the hash is a SHA-1 prefix,
because Python's `hash()` is not stable across processes. A line with no text -- a skill's own
event -- has an empty hash and is carried by its labels alone, which is right: a dwarf saying the
same kind of thing about the same thing is repeating itself either way.

Each relationship keeps a ring of the last 12 signatures. A fresh event is worth:

| | worth |
| --- | --- |
| the same signature, 1st / 2nd / 3rd / 4th / after, within 400 ticks | 1.0 / 0.35 / 0.06 / 0.02 / 0.0 |
| a different line, same intent and topic, within 120 ticks | `0.80^n`, floored at 0.35 |

The factor multiplies the event's **magnitude**, so the event table lands quieter without a single
row of it changing. Two things keep the second row gentle, and both were learned the hard way: its
window is much shorter than the identical one, and its floor is high. Six dwarves in six rooms make
small talk about the mine all day; treating that as spam took the settlement's goodwill away and
the killings went up by half. Saying the same *words* again is repetition. Saying another true
thing about the same subject is conversation.

### Words against deeds

`PRAISE`, `SMALLTALK` and `GOSSIP` are words. Only 25% of a word's positive trust or respect is
real; the rest becomes **warmth**, and trust is additionally held under a rolling cap of 0.10 per
speaker per 400 ticks. `HELP`, `GIFT` and `PROMISE_KEPT` are deeds, and move trust in full.

An **apology** is deliberately not a word here, and neither is an accepted ask. Both cost the one
making them, and an apology is the only thing in the sim that ends a feud; rationing it, in an
early draft, left the settlement with no way back from a quarrel. Habituation still quietens a
repeated apology, which is the right answer to somebody who keeps saying sorry and keeps doing it.

Warmth is why rationing words did not turn the settlement into a knife fight. It is *real*: it sits
in the relationship row beside trust, respect and hatred, it decays about four times as fast, and
it is read by `regard.felt_trust` (trust + 0.6 x warmth), by `MindState.likes`, by the arbitrator's
`trust_target`, by whether small talk comes out friendly, by who counts as a confidant, and by how
a dwarf speaks to you (`replies.stance_of`). What it is not is *evidence*: nothing that decides
whether to hand somebody your ore ever looks at it.

One consequence worth writing down, because it cost a day: a chief's deterrent used to lean on
respect alone, and respect stopped being replenished by chatter, so his standing drained away and a
settlement with a chief fought exactly as much as one without. `expected_punishment` now takes the
larger of what a dwarf thinks of the chief and **what it has actually watched him do** -- every
`PUNISH` it remembers, faded by age. An opinion drains; a record does not.

### Flattery suspicion

Two things make praise suspect, and **either is enough**:

* **repeated** -- how many times these exact words have come out of this mouth already. Nothing for
  the first, half for the second, all of it from the third. Varied praise never counts here;
* **unearned** -- 0.55, when the dwarf being praised has done nothing inside the last 250 ticks
  worth praising. A shift that produced something counts, which is what keeps an honest compliment
  about the ore from being read as flattery.

Both are gated on **frequency** -- the first two praises from one mouth inside 300 ticks are free,
whatever they are -- and discounted by 0.6 x whatever trust the listener already has, because a
friend who watched you kill a creeper may say so all evening. The three used to multiply, which
meant a dwarf who had done a shift could be pasted at forever; making any one of them sufficient
without the frequency gate had sociable dwarves reading each other as manipulators inside a few
hundred ticks.

Past a suspicion of 0.50 the next compliment stops being one. The event emitted is `FLATTERY`
rather than `PRAISE` -- its own row in the event table, costing trust and respect instead of buying
them -- and the answer comes out of the `FLATTERY` cell: *"Say it a third time and I'll start
wondering what you want."* Suspicion decays at 0.0015 a tick, and a genuine deed clears it outright.

### Gifts

A gift is worth what it cost the giver relative to what they had, divided by how many times they
have done it: `(0.25 + 2.2 x share of the giver's wealth) x 0.5^(prior gifts inside 600 ticks)`.
Twenty coins handed over one at a time are worth less than one gift of ten, and a poor dwarf's
single coin is worth more than a rich one's.

### The numbers

| | result |
| --- | --- |
| the same praise x20 | ends in `FLATTERY`, suspicion at the ceiling, and trust **-0.41** |
| two genuinely different compliments | trust +0.029, more than the twenty pastes managed |
| fifty varied compliments | trust capped at 0.10 |
| one kept promise | trust +0.19, more than the fifty |
| 1 gold x20, out of 40 | trust 0.10 |
| 10 gold x1, out of 40 | trust 0.16 |

## The arbitrator

Every tick, for every living dwarf: every skill proposes, every candidate is scored, one is sampled.

```
score = sum over terms of  WEIGHTS[skill][term] * term_value
```

Terms not listed for a skill weigh nothing, so a candidate's logged breakdown is complete even
though only the listed terms are ever computed. The term names are fixed in `dwarfsim/schema.py`:

| Term | What it is |
| --- | --- |
| `base` | always 1.0, so the weight is the skill's resting appetite |
| `need_hunger` `need_thirst` `need_fatigue` `need_social` | the four needs |
| `anger` `fear` `happiness` `grief` | the four emotions |
| `bravery` `greed` `temper` `sociability` | the four traits |
| `anger_at_target` | anger x blame: `0.30 + 0.50*hatred`, plus 0.45 if this is who hit me in the last 25 ticks |
| `hatred_target` `trust_target` `respect_target` | the relationship triple toward this candidate's target |
| `being_attacked` | 1 fading to 0 over 12 ticks since the last blow; quartered if the candidate's target is not who threw it |
| `monster_threat` | a monster is at the candidate's place |
| `hurt` | `1 - health/20` |
| `wealth_drive` | `greed * (1 - gold/20)` |
| `supply_pressure` | how badly this particular job needs doing, the one term a skill computes for itself |
| `request_pull` | a standing order from someone, weighed by trust in them |
| `distance_cost` | 1 if the candidate is somewhere else |
| `noise` | one uniform draw per candidate, cached for the life of the context |
| `pride` `forgiveness` | the two new traits |
| `grudge_target` | summed salience of harms, softened to 0..1 -- see memory above |
| `gratitude_target` | the same over helps, gifts and kept promises |
| `reputation_target` | what the whole settlement currently believes, folded to one signed number |
| `provoked_by_target` | salience of the freshest unanswered thing they did to me |
| `publicity` | how many were watching, over 3 |
| `humiliation` | `publicity x (0.30 + 1.40 x pride)`, computed by the skill |
| `chief_present` | the chief is standing here (always 0 when there is no chief) |
| `expected_punishment` | what violence in front of the chief would probably cost |
| `fear_target` | fear of *that* dwarf: fear scaled by their standing, plus a bump if they just hit me |
| `goal_bias` | every held goal's strength times how well this candidate serves it |
| `obligation_pressure` | how urgently an ask, a counter-offer or a demanded apology wants answering |
| `ask_cost` | what doing the asked thing would cost me |
| `payment_offered` | gold on the table, over 8 |
| `gossip_value` | salience of the best story I could tell this listener |
| `punish_pressure` | how strong the case in front of the chief is |
| `impairment` | how much *this candidate* is hampered by what is broken -- see [Injuries](#injuries) |

**Aimed at, and about.** `trust_target`, `respect_target`, `hatred_target` and `fear_target` read
whoever the candidate is *aimed at*. The memory-derived four -- `grudge_target`,
`gratitude_target`, `reputation_target`, `provoked_by_target` -- read whoever it is *about*, which is
the same dwarf for an attack and a different one for a complaint or a piece of gossip. That is the
whole of `Candidate.about()`.

**Terms a skill computes for itself.** `supply_pressure` was the first; `humiliation`,
`obligation_pressure`, `ask_cost`, `payment_offered`, `gossip_value`, `punish_pressure` and (for a
reaction) `publicity` joined it, because only the proposing skill knows *which* memory, obligation
or complaint the candidate refers to. They arrive on `cand.hints` and are otherwise ordinary terms.
`humiliation` is also the one place two terms multiply, which a linear scorer cannot do for itself.

The weights are in `arbitrator.WEIGHTS`. Two of them earn their keep: `hurt` is why a losing fighter
runs instead of dying (`FLEE +2.00`, `ATTACK -1.30`), and `distance_cost -0.85` on `WORK` is why a
dwarf sticks to a job instead of walking between the mine and the forge every other tick.

### The graded reactions

An unanswered harm in episodic memory opens six candidates at once, and nothing orders them: which
one wins is whatever the terms add up to. That is the whole feature.

| Reaction | What it does | Pushed up by | Pushed down by |
| --- | --- | --- | --- |
| `IGNORE` | nothing, but the memory stays | forgiveness, fear | pride, temper, humiliation, anger, grudge |
| `RETORT` | an insult back, through the templates | temper, pride, provocation, anger at them | fear, forgiveness, expected punishment |
| `DEMAND_APOLOGY` | a public demand; they then get `APOLOGIZE` and `REFUSE_APOLOGY` | pride, humiliation, publicity, respect and trust for them, bravery, the chief being there | temper, fear, hatred |
| `COMPLAIN_TO` | tell a trusted friend, or the chief | trust in the confidant, grudge, publicity, fear, sociability | bravery, pride |
| `AVOID` | leave, and stop seeking them out for 150 ticks | fear, being hurt, the provocation | bravery, temper, sociability, pride |
| `ATTACK` | the existing skill | anger at them, hatred, grudge, being hit, temper, bravery | fear, being hurt, expected punishment |

A retort is deliberately **not** itself a provocation. Letting one be answered in turn made two
dwarves trade insults every tick until one of them was dead, which is what happened the first time
this ran. He gave as good as he got, and that is the end of it -- the retort is still filed as a
harm, so it still feeds grudges and still travels as gossip. For the same reason a demand cannot be
repeated at the same dwarf for 90 ticks, whether it was met or refused.

### Gossip and reputation

`GOSSIP` is `SOCIALIZE`'s other half: instead of talking *to* somebody, talk *about* somebody. The
teller picks the loudest episode about a third party that the listener does not already know and is
not in; the listener files it as a `HEARD` memory whose intensity is scaled by how much they trust
the teller, and shifts their view of the third party by a fraction of the same number. Reputation is
what everyone currently believes about one dwarf, averaged over every head that holds an opinion;
`World.reputation()` computes it, the log writes it every tenth tick, and the viewer has a toggle
for it. Feuds spread to friends because the harms are told, not because anything says they should.

### Authority

**There is no chief by default.** `World(..., chief=True)` appoints the most respected dwarf,
`chief=<id>` names one, and the `feud-chief` scenario turns it on; `default`, `feud`, `gossip`,
`theft`, `raid`, `player` and the three reward-stage scenarios (`friends`, `bully`, `thief`) all
run without one. With no chief there is no `PUNISH` candidate,
`chief_present` and `expected_punishment` are always zero, `KEEP_PEACE` is never adopted, and
`COMPLAIN_TO` has only friends to go to.

With one, the chief holds `KEEP_PEACE`, starts with +0.35 respect from everybody, and gets a
`PUNISH` candidate against anyone standing in front of it that it has either seen do harm or heard a
complaint about. Punishment is a fine of up to 4 gold, paid to the victim, or a public rebuke when
there is no gold; either way everyone present loses respect for the culprit, and the culprit gains
both respect and a little hatred for the chief. Measurably, in the feud scenario over two seeds: a
chief cuts blows by roughly a third and turns the difference into complaints and formal demands.

Selection is a softmax over the scores at temperature 0.25: mostly the best candidate, with the
occasional surprise, seeded like everything else. The chosen candidate and the top five, each with
every term's contribution, go into the log every tick. That breakdown is the product.

**Walking is a tick.** A candidate that names a place the dwarf is not standing in spends the tick
walking there instead of acting, handled once in `World.step`, so no skill thinks about travel and
`distance_cost` prices it honestly. Six places, fully connected, one tick between any two.

## The vectors

`dwarfsim/schema.py` is the layout contract, the same job `brain/schema/ObservationSchema.java` does
in the mod: named offsets, a size, and a schema id to refuse a mismatch.

**The mind vector, 44 floats** (`mind.MindState.to_vector`), which is the first block of the
observation. Two things moved from v0: traits grew from four to six, and each focus slot grew from
four floats to six.

| Offset | Size | Contents |
| --- | --- | --- |
| 0 | 4 | emotions: anger, fear, happiness, grief |
| 4 | 4 | needs: hunger, thirst, fatigue, social |
| 8 | 6 | traits: bravery, greed, temper, sociability, **pride, forgiveness** |
| 14 | 1 | health / 20 |
| 15 | 5 | inventory normalised: ore, gold, food, ale, weapon quality |
| 20 | 24 | four focus slots x (present, trust, respect, hatred, **grudge, gratitude**) |

The focus slots are the mod's enemy slots in miniature: a fixed number of stable slots filled by
salience (hatred x2, plus the size of trust and respect, plus 1.5 for being in the same room, plus
2.0 for having just hit me), padded with zeros, so slot 0 always means "the one that matters most".
The player can fill a slot like anyone else. The two new floats are the memory-derived pair, so a
learned scorer sees not just what a dwarf feels about the one that matters most but what it is
holding against them.

The relationship row carries a fourth number, `warmth`, and it is deliberately **not** in the
vector: it is a mood, the scorer gets it folded into `trust_target` instead, and keeping it out is
what left the mind block at 44 across the injury stage.

**Observation, 77 floats** (`arbitrator.observation_vector`), one row per dwarf per tick:

| Offset | Size | Contents |
| --- | --- | --- |
| 0 | 44 | the whole mind vector above |
| 44 | 6 | place, one-hot over FORGE MINE TAVERN HALL FARM GATE |
| 50 | 1 | dwarves here / 6 |
| 51 | 2 | a monster is here; its health fraction |
| 53 | 2 | hit in the last 12 ticks; ticks since the last hit / 50 |
| 55 | 1 | living dwarves / starting count |
| 56 | 1 | `(tick % 200) / 200`, a cheap day clock |
| 57 | 6 | goal strength, one per kind: GET_RICH AVENGE PROTECT REPAY BEFRIEND KEEP_PEACE |
| 63 | 2 | open obligations on me / 3; open asks I made / 3 |
| 65 | 2 | memories held / 64; their mean salience |
| 67 | 2 | I am the chief; the chief is standing here (both 0 when there is none) |
| 69 | 8 | **the condition block**, below |

The condition block, all 0..1, added by the injury stage. Health is already in the mind vector and
says how close to dying a dwarf is; this says what is *broken*, which is a different question and
the one a scorer has to answer to know whether the mine is worth walking to. The three named bones
are here and the mild kinds are not, because those three are what change what a dwarf *can do*; the
rest reach the scorer through pain and worst.

| Offset | Contents |
| --- | --- |
| 69 | pain: every injury's pain summed and softened |
| 70 | worst: the severity of the worst single injury |
| 71 | how many injuries are being carried, / 4 |
| 72 | broken arm severity -- past 0.25, no two-handed work and no weapon |
| 73 | broken leg severity -- past 0.30, no fleeing well and no monsters |
| 74 | concussion severity -- noisier decisions, less remembered |
| 75 | bleeding severity -- health draining until it is rested or eaten off |
| 76 | cracked ribs severity -- more fear, less force |

**Candidate features, 65 floats** (`arbitrator.candidate_features`): a 22-wide skill one-hot, then
all 43 term values raw. The 43rd is `impairment`, which the injury stage appended.
`arbitrator.score(observation, features)` reproduces the hand table exactly, because the table *is*
a linear model over this block. That is the seam, and it is unchanged: the blocks got wider, the
contract did not.

The schema id is `dwarfsim-v2`. Offsets are appended to, never reordered, so the skill one-hot and
the term order still mean what they meant -- but the observation grew from 69 to 77 and the
candidate features from 64 to 65, so a v1 weight file must be refused, which is what the id is for.

**The two frozen models in `../shared/models/` are deliberately not re-frozen for v2.** They are a
snapshot of v1 and they still reproduce their own 200-record answer sheets, which is exactly what
`tools/check_parity` proves: `LearnedScorer.load(..., live=False)` reads a frozen model on the
layout *it* was written against rather than on this sim's, and `live=True` -- the only way the sim
itself ever loads one -- still refuses a mismatch. Stage B of the port takes the new layout on; see
[port.md](port.md).

## The learned scorer

`dwarfsim/learn/`. The first half of "rules are the body, learning is the choice": the hand-written
table is the *teacher*, a tiny MLP is the *student*, and the student is trained to make the same
choice. No reward and no PPO in this half -- this is the imitation stage, and what it buys is the
proof that the seam in `arbitrator.py` really is narrow enough to swap through, plus a starting
policy that already behaves. [The reward stage](#the-reward-stage) is the other half. Nothing else
about the sim changes: the skills still propose, the softmax still samples at T = 0.25, and the log
still says why.

```
  python -m dwarfsim.learn.collect --seeds 1-8 --ticks 1000 --targeted-seeds 9-13 \
      --hard-gap 0.5 --out runs/learn/teacher_v2.npz
  python -m dwarfsim.learn.imitate --data runs/learn/teacher_v2.npz --holdout 7,8 \
      --balance-skills 0.35 --out runs/learn/imitator
  python -m dwarfsim run --scenario feud --seed 9 --scorer runs/learn/imitator \
      --out runs/feud_learned.jsonl --html runs/feud_learned.html
```

**Collecting.** One *decision* is one dwarf on one tick: the 69-float observation, every candidate
its skills proposed as 64 floats, the table's score for each, and which one the softmax took.
`World.collector` is a list; when it is set, `arbitrator.decide` builds the full feature vectors and
appends one tuple per decision. Recording does not change the run -- `candidate_features` draws
exactly the one `noise` value per candidate that the table draws, in the same order, so a collected
run and a plain run of the same seed are the same run tick for tick, which `tests/test_learn.py`
checks. Seven scenarios (`default`, `feud`, `gossip`, `player` -- the last driven by the same player
script as `run_player_session` -- plus the three
[targeted ones](#three-scenarios-for-the-rare-reactions)) x eight seeds x 1,000 ticks, every second
tick recorded.

**Two knobs aim at the rare reactions**, because an ordinary collection contains a few dozen of
them. `--targeted-seeds 9-13` runs `friends`, `bully` and `thief` over five more seeds each, and
`--hard-gap 0.5` records *every* tick of those runs rather than every second one wherever the
teacher's best two candidates were that close -- the close decisions are where the tails live, and
building the feature vectors is what costs, not keeping them. Together: **220,000 decisions, 3.8 M
candidates, 370 MB, 119 s**, of which the three targeted settlements are 144,042 against the four
ordinary ones' 75,958. (The first collection, which `runs/learn/imitator_v1` and both PPO runs came
from, was four scenarios x eight seeds x 2,000 ticks: 145,254 decisions, 237 MB, 70 s.)

The file is one ragged `.npz` -- decisions have 8 to 30 candidates and padding them all to the
widest would have doubled it:

| Array | Shape | Type | What |
| --- | --- | --- | --- |
| `obs` | (D, 69) | float16 | one observation per decision |
| `offsets` | (D+1,) | int64 | decision `d` owns candidates `offsets[d]:offsets[d+1]` |
| `cand_terms` | (C, 42) | float16 | the raw term values of one candidate |
| `cand_skill` | (C,) | int8 | its skill; the 22-wide one-hot is rebuilt on load |
| `teacher` | (C,) | float32 | the table's score, full precision |
| `chosen` | (D,) | int16 | which candidate the softmax took, within the decision |
| `scenario`, `seed` | (D,) | int8, int16 | so seeds can be held out whole |

float16 for the inputs and float32 for the target is the whole size trick, with storing the skill as
an index rather than 22 floats: numbers in roughly 0..1 whose third decimal never mattered, against
the thing being fitted.

**The student.** `observation ++ candidate_features` -> 133 -> 64 -> ReLU -> 64 -> ReLU -> 1.
Three Linears, 12,801 parameters, 53 KB. No normalisation layer, no embedding, no residual, because
the point is that the Java port is six float arrays and two matrix products -- the same shape the
combat brain already loads. The observation is the same for every candidate of one decision, so the
forward pass splits the first layer in two and broadcasts the observation half across the
candidates instead of materialising it K times.

**The loss is listwise.** A scorer is only ever asked which candidate wins, so a decision is one
training example, not K:

```
loss = CE(pred / 0.25, the teacher's best candidate) + 0.2 * mean((pred - teacher)^2)
```

The softmax uses the sim's own temperature, so the distribution being fitted is the distribution the
sim samples from. The MSE term is small but not optional: cross-entropy alone is invariant to adding
a constant per decision and to scaling every score, so the student could drift to any magnitudes it
liked -- and then the softmax at 0.25 would be sharper or flatter than the teacher's and the
*variety* of the sim would change even where the argmax did not.

**Class-balanced weighting.** `--balance-skills POWER` weights each decision by how rare the
teacher's own choice was: `(n / (k * count(skill))) ** POWER`, zero for a skill that never appears,
normalised to mean 1 -- the same softened inverse-frequency `text/classifier` uses for its intents,
and the same reasoning for softening it. Only the cross-entropy is weighted; the MSE term is there
to pin the magnitudes the sampling temperature reads, and weighting it would distort exactly the
thing it holds still. At 0.35 the heaviest classes are `REFUSE_APOLOGY` and `IGNORE` at 1.8 against
`WORK`'s 0.6.

**Held out.** Seeds 7 and 8 entirely, not two random slices: consecutive decisions inside one run
are nearly the same decision, so a random split measures memorisation. 30 epochs, Adam at 3e-3,
batch 512, single-threaded CPU: **138 s** on 220k decisions.

| Held out (43,128 decisions) | Top-1 vs the teacher | Hard decisions | Top-1 on those |
| --- | --- | --- | --- |
| overall | **97.8%** (chance 5.9%) | 27,128 | **96.6%** |
| `default` | 98.4% | 2,935 | 97.0% |
| `feud` | 98.0% | 2,191 | 96.1% |
| `gossip` | 98.2% | 2,892 | 96.9% |
| `player` | 98.6% | 2,941 | 97.4% |
| `friends` | 97.2% | 7,138 | 96.2% |
| `bully` | 97.3% | 3,542 | 96.1% |
| `thief` | 97.6% | 5,489 | 96.6% |

A *hard* decision is one where the teacher's best two candidates were within 0.5 of each other --
just under two thirds of them here, and the ones where a wrong pick is a different action rather
than a tie broken differently. Mean absolute error on the raw scores is 0.19, which is what keeps
the sampling temperature meaningful.

**Exporting.** `runs/learn/imitator.npz` (six float32 arrays in a fixed order) plus
`imitator.json` (the schema id, the dims, the layout, the CLI that made it, the metrics), the same
pair and the same spirit as `text/classifier/export.py`. `LearnedScorer.load(path)` reads them with
numpy and nothing else -- torch is needed to train, never to run -- and offers the arbitrator's own
`score(observation, candidate_features) -> float` plus a `score_all` for a whole decision at once.
It matches the torch model to 1.8e-6.

**Running on it.** `World(scorer=...)`, or `run --scorer`. Every candidate's logged breakdown is
then a single term named `learned`, so the viewer's "why" panel still renders -- it just has one bar
instead of fifteen, and the log is half the size.

What that looks like, `feud` at seed 9 for 2,000 ticks, teacher against student (this one was run
against `imitator_v1`, the first student). The first six columns are `provocations.answered` --
how each suffered harm was answered:

| | ignore | retort | demand | complain | avoid | hit | gossip | thefts | deaths |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | 1 | 7 | 5 | 4 | 5 | 19 | 97 | 25 | 2 |
| learned | 2 | 21 | 5 | 8 | 10 | 15 | 39 | 47 | 3 |

These are not meant to match and cannot: at 97.9% per decision and 12,000 decisions a run, the two
trajectories have parted company within the first few dozen ticks, and everything after that is a
different settlement. What is worth reading is that all six reactions still appear, in the same
rough order, and the feud still emerges. Over six seeds the same holds (teacher 6/57/19/31/19/102
against learned 4/64/13/23/27/72 for ignore/retort/demand/complain/avoid/hit): the student hits
somewhat less, avoids and steals somewhat more.

**Where it is weakest, and what fixed some of it.** Per-skill *recall* -- of the held-out decisions
where the teacher chose this skill, the share where the student chose it too -- is the number a
97.9% overall figure hides, because the tails are a few dozen decisions in forty thousand. Four
models on the same held-out set (seeds 7 and 8, all seven scenarios, 43,128 decisions): `v1` is the
first imitator, trained on the first collection; the other three are the second collection with the
balancing knob at 0, 0.35 and 0.5.

| Skill | N | v1 | plain | **0.35** | 0.5 |
| --- | --- | --- | --- | --- | --- |
| `IGNORE` | 8 | 0.12 | 0.12 | **0.12** | 0.25 |
| `REFUSE_APOLOGY` | 11 | 1.00 | 0.82 | **1.00** | 1.00 |
| `AVOID` | 20 | 0.75 | 0.95 | **0.85** | 0.80 |
| `APOLOGIZE` | 20 | 0.30 | 0.70 | **0.85** | 0.90 |
| `DEMAND_APOLOGY` | 24 | 0.75 | 0.71 | **0.79** | 0.71 |
| `RETORT` | 36 | 0.75 | 0.72 | **0.81** | 0.81 |
| `REFUSE` | 51 | 0.67 | 0.76 | **0.78** | 0.90 |
| `FLEE` | 55 | 0.56 | 0.85 | **0.80** | 0.84 |
| `BARGAIN` | 60 | 0.90 | 0.77 | **0.88** | 0.85 |
| `ACCEPT` | 68 | 0.66 | 0.91 | **0.94** | 0.94 |
| `COMPLAIN_TO` | 72 | 0.68 | 0.88 | **0.93** | 0.96 |
| `FULFIL` | 91 | 0.86 | 0.91 | **0.98** | 0.99 |
| `EAT` | 92 | 0.65 | 0.70 | **0.90** | 0.93 |
| `STEAL` | 99 | 0.65 | 0.71 | **0.94** | 0.87 |
| `ATTACK` | 119 | 0.91 | 0.92 | **0.89** | 0.89 |
| `DRINK` | 275 | 0.67 | 0.86 | **0.96** | 0.98 |
| `GOSSIP` | 414 | 0.80 | 0.83 | **0.90** | 0.94 |
| `FIGHT_MONSTER` | 542 | 0.93 | 0.92 | **0.98** | 0.99 |
| `SOCIALIZE` | 582 | 0.79 | 0.86 | **0.94** | 0.97 |
| `REST` | 2,250 | 0.98 | 0.96 | **0.98** | 0.98 |
| `WORK` | 38,239 | 0.99 | 1.00 | **0.99** | 0.97 |
| **mean over the 15 rarest** | | 0.681 | 0.762 | **0.832** | 0.842 |
| **overall top-1** | | 0.9747 | 0.9818 | **0.9783** | 0.9688 |
| **hard top-1** | | 0.9605 | 0.9713 | **0.9656** | 0.9508 |

Both knobs pay. The data alone (`plain`) is worth +0.081 mean rare recall *and* +0.7 points of
overall agreement, because the three targeted settlements and the close decisions are simply more
of the thing that was missing. The balancing on top of it is the trade the old text here predicted:
at 0.5 it buys another +0.080 of rare recall for **-1.30** points overall, which is more than the
budget; at **0.35** it buys +0.070 for **-0.35**, which is not. So `runs/learn/imitator` is the
0.35 model, `runs/learn/imitator_plain` and `runs/learn/imitator_balanced05` sit beside it, and
`runs/learn/imitator_v1` is the original, which is what both PPO runs were initialised from.

`IGNORE` is the one that did not move: 8 decisions in the held-out set, one of which the 0.35 model
gets. There is no weighting trick for a class that small -- it wants a scenario built for it, the
way `bully` was built for `COMPLAIN_TO`.

## The reward stage

The other half: the same 133-64-64-1 scorer, started from the imitator, then moved by PPO against a
reward table instead of against the hand-written arbitrator's choices. Four modules:
`learn/reward.py` (what counts as doing well), `learn/scenarios.py` (three settlements where the
rare reactions are the sensible ones), `learn/ppo.py` (the training loop) and `learn/evaluate.py`
(the paired comparison). Nothing else about the sim changes -- the skills still propose, the softmax
still samples at T = 0.25, the log still says why -- and the trained policy is exported in the
imitator's own format, so `--scorer runs/learn/ppo/best` works in the sim and the viewer.

```
  python -m dwarfsim.learn.ppo --init runs/learn/imitator --out runs/learn/ppo \
      --iterations 45 --ticks 400
  python -m dwarfsim.learn.evaluate --seeds 20-23 --policies teacher runs/learn/imitator \
      runs/learn/ppo_v1/best runs/learn/ppo_v2/best
  python -m dwarfsim run --scenario feud --seed 21 --scorer runs/learn/ppo/best \
      --out runs/feud_ppo.jsonl --html runs/feud_ppo.html
```

### The reward table

One step is one decision -- one dwarf on one tick -- and its reward is read off the world before
that tick and after it, plus the events the tick emitted. Every term is signed so that more is
better, so the step reward is `sum(weight * value)`. The whole table is `REWARD_WEIGHTS` at the top
of `reward.py`:

| Term | Weight | Value per step |
| --- | --- | --- |
| `needs` | 0.50 | `-(mean of hunger, thirst, fatigue, social)`: 0 at best, -1 at worst |
| `alive` | 15.00 | `-1` on the step this dwarf dies, else 0 |
| `wealth` | 0.10 | my change in `gold + 2*ore` **minus the settlement's mean change**, over 5 |
| `standing` | 12.00 | the mean, over the other dwarves, of the change in **respect** toward me |
| `promises` | 2.00 | `+1` per promise I kept, `-1` per promise I broke |
| `violence` | 3.00 | `-1` per *unprovoked* blow I threw |
| `time` | 0.10 | `-1` on a step I chose `SOCIALIZE` or `GOSSIP` while a need was already over 0.35 |

This is the second table. The first is still in the file as `REWARD_WEIGHTS_V1`, and the two term
definitions it also needs are arguments to `RewardModel` (`standing_keys`, `relative_wealth`), so
the first run stays reproducible. There is now a third,
[trait-conditioned](#a-third-table-one-reward-per-dwarf) one as well -- `REWARD_WEIGHTS_TRAIT`,
which adds six terms these seven have no room for; `RewardModel(mode=...)` and `--reward` pick
between all three, and the six extra terms are zero-weighted here and in v1, so a run against
either of those two tables is the run it always was. What changed and why is
[the second run](#a-second-reward-table-and-what-it-did-not-fix), below; the short version is that
the first PPO run farmed `standing` with flattery, so standing lost trust and two thirds of its
weight, wealth became relative, and `time` was added to make chatter cost something.

**Unprovoked** means: at the start of the tick the attacker held no remembered grudge against that
target at all (`memories.grudge` at or below 0.02) *and* that target had not hit the attacker within
25 ticks. Hitting back, or hitting a thief who robbed you, is free. The reward says nothing about
whether violence is right, only that starting it is expensive, which is the only claim this sim can
support. Under the hand-written arbitrator only about 2% of blows are unprovoked by that definition,
so the term is a guard rail against a learned policy that starts swinging for no reason rather than
a criticism of the table.

**Standing is a delta, not a level.** A level would pay a dwarf every tick for a reputation it
earned a thousand ticks ago; the delta credits the tick that moved it. Holders who died during the
tick are dropped from both sides of the mean, so the settlement shrinking does not read as a social
triumph.

**Standing counts respect, not trust.** Respect is the half of a relationship that deeds move and
trust is the half that words move, so what one event is worth to the actor, weighted, changed like
this: `SMALLTALK` +0.069 -> **0.000**, `PRAISE` +0.280 -> **+0.143**, `GIFT` +0.487 -> +0.143,
`PROMISE_KEPT` +0.704 -> +0.322, `HELP` +0.601 -> **+0.500**. Small talk is now worth nothing at
all, praise about half, and helping almost what it was: a deed was 2.1 praises and is now 3.5.

**Wealth is relative.** The settlement's mean change is subtracted, over the dwarves alive at both
ends of the tick, so the term sums to about zero across the settlement: mining pays while the
others are not mining, and a tick in which every pile grows equally pays nobody. The absolute
version paid six dwarves for the same ore, and `wealth` was the teacher's second-largest positive
term because of it. It is now worth about 0.00 per episode to every policy measured, which is the
intended outcome and also an admission that gold has almost no use in this sim.

**The time term is the one that did not work.** It charges 0.10 for a `SOCIALIZE` or `GOSSIP` step
taken while hunger, thirst or fatigue is over 0.35. It was specified at 0.6, but 0.6 never happens
here: over 7,200 steps the teacher chatted 604 times and not once with any need that high, social
included -- dwarves in this sim keep themselves fed, and the 90th percentile of the largest of the
three is 0.40. `social` is deliberately not one of the three, because `SOCIALIZE` is the cure for
it and charging a lonely dwarf for talking would make the term fight itself. At 0.35 it did what
it was asked and charged the first PPO policy -21 an episode against the teacher's -6.5 -- and the
second PPO policy then learned to keep its needs *down* and chat exactly as much as before for -1.7
an episode. A cost that can be dodged by fixing the condition it is conditioned on is not a cost.

**The weights are a balance.** Over a 600-tick settlement under the teacher the terms come out at
`needs -186`, `alive -10`, `standing +9`, `time -7`, `promises +4`, `violence -1`, `wealth -0.0`.
Needs are the drum beat and everything else is a correction on top of them.

Per-episode totals go to a CSV (`episodes.csv` while training, `evaluation.csv` when evaluating),
one row per settlement, with the weighted term totals -- which add up to the `reward` column -- and
the behaviour counters beside them.

### Three scenarios for the rare reactions

The imitator's honest weakness is its tails: `IGNORE`, `COMPLAIN_TO` and `APOLOGIZE` are a few dozen
decisions in thirty-six thousand. A reward can only fix that if the training runs contain situations
where those choices pay, so `learn/scenarios.py` adds three, wired into `collect.py`, the PPO
rollouts and the evaluation. Like every other scenario they are starting conditions only.

| Scenario | The setup | What would be sensible |
| --- | --- | --- |
| `friends` | six dwarves who trust and respect each other, all quick-tempered, two starting angry | let it go, snap back or ask for an apology -- not a fist |
| `bully` | one hostile dwarf, the timid dwarf it hates, and a strong dwarf who likes the victim, in one room | the victim avoids or complains; the protector is the one dwarf whose violence *is* provoked |
| `thief` | a broke, greedy dwarf and five others holding goods, all in the tavern | the theft is seen, so the victim and the witnesses have a case and the thief has a reason to apologise |

`friends` works because `SOCIALIZE` turns hostile at
`hatred*1.3 + anger*(0.4 + 0.7*temper) - trust*0.5 > 0.55`: with trust that high only anger can get
it there, and anger decays back to a baseline that cannot, so the friction comes in bursts and burns
out. Nothing scripts an insult.

### PPO

| Setting | Value |
| --- | --- |
| policy | the imitator's `133 -> 64 -> 64 -> 1`, initialised from `runs/learn/imitator.npz` |
| distribution | `softmax(scores / 0.25)` over *this decision's* candidates -- the sim's own sampling temperature, so the policy is literally what the sim does |
| value net | `observation ++ mean(this decision's candidate features) -> 64 -> 1`, 133 in, trained alongside |
| advantages | GAE, gamma 0.99, lambda 0.95; bootstrapped off the state each living dwarf is left in, zero on death |
| objective | clipped surrogate at 0.2, value coefficient 0.5, entropy bonus 0.01, KL back to the frozen imitator 0.05, early stop at a step KL of 0.05 |
| optimiser | Adam, 3e-4 for the policy and 1e-3 for the value net, 4 epochs of 512-decision minibatches per iteration, gradient norm clipped at 0.5 |
| rollouts | 6 scenarios x 1 seed x 400 ticks per iteration, rotating through seeds 1-8; about 14,000 decisions |
| checkpoints | `best` by a held-back validation settlement (seed 50) every 5 iterations, plus `last` |

**One step is one decision; one trajectory is one dwarf.** Six dwarves in one settlement are six
trajectories that interact, treated as six independent single-agent problems -- the standard
simplification, and the reason the value loss bounces between 0.17 and 0.57 for the whole run
instead of settling: the other five dwarves are learning too, so the world the value net is fitting
keeps moving.

**Two anchors, because a policy whose logits are scores divided by 0.25 can collapse onto one action
in a handful of updates**: the entropy bonus, and the KL penalty back to the imitator, which is what
the imitation stage bought. Both are logged per iteration in `progress.csv` along with the reward
and its terms. In the default run neither the early stop nor the clip did much work -- about 6% of
minibatch ratios were clipped and the step KL never reached 0.05 -- so what actually held the policy
together was the KL penalty and the small learning rate, not the guards.

The default run is **45 iterations in 357 s** on one CPU thread, single process. Mean episode reward
over the rollouts went from -179 to -34, entropy from 0.20 to 0.94 nats, KL to the imitator from
0.01 to 1.90. The best validation was iteration 25 (-33.7); the four after it were -40.9, -38.7,
-38.4 and -35.9, so it had stopped improving by a quarter of the way in and the rest is noise.
`best` is the checkpoint that gets evaluated and shipped, not `last`.

The [second run](#a-second-reward-table-and-what-it-did-not-fix), against the second reward table,
is the same command with `--out runs/learn/ppo_v2`: 45 iterations in 599 s (the wall clock is
contention on a shared machine, not more work). Rollout reward -180 -> -65, entropy 0.20 -> 0.98,
KL to the imitator 0.01 -> 1.71, and the best validation was the *last* iteration (-52.9), so
unlike the first run it had not finished improving when the budget ran out.

### What it learned

Held out entirely -- seeds 20-23 are in neither the rollout pool nor the validation seed -- over all
seven scenarios, 600 ticks, 28 episodes per policy. Mean reward per episode, with the terms:

| Policy | reward | needs | alive | wealth | standing | promises | violence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | -149.6 | -180.9 | -13.9 | +29.5 | +12.5 | +3.9 | -0.6 |
| imitator | -244.2 | -262.0 | -9.1 | +37.1 | -10.6 | +0.4 | 0.0 |
| **ppo/best** | **-85.3** | -199.1 | **-1.1** | +26.3 | **+73.0** | **+15.6** | -0.1 |

The behaviour underneath it, totalled over the same 28 episodes each:

| Policy | ignore | retort | demand | complain | avoid | attack | apologise | steal | gossip | accept | fulfil | deaths | thefts | kept | broken | unprovoked |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | 19 | 97 | 53 | 97 | 49 | 405 | 74 | 167 | 1254 | 57 | 76 | 26 | 167 | 56 | 1 | 6 |
| imitator | 6 | 93 | 33 | 81 | 36 | 288 | 25 | 161 | 765 | 5 | 9 | 17 | 161 | 5 | 0 | 0 |
| ppo/best | 2 | 7 | 12 | 9 | 0 | 27 | 24 | 7 | 729 | 218 | 252 | 2 | 7 | 218 | 0 | 1 |

**Read it honestly.** PPO wins the number it was trained on by a wide margin, and two of the wins
are real: deaths fall from 26 to 2 and blows from 288 to 27, unprovoked ones from 6 to 1, while the
imitator's collapsed social standing (-10.6) becomes the best of the three. It keeps 218 promises
where the teacher keeps 56. But the strategy it found is narrower than the teacher's: over one seed
pair on three scenarios it chooses `SOCIALIZE` 6,875 times against the teacher's 1,283 and `WORK`
11,667 against 14,892. It has discovered that praising everybody, taking on every ask and fulfilling
it is the cheapest way to move `standing` and `promises`, and it does little else.

**The graded reactions all but disappear in absolute terms** -- 720 reactions across the 28 episodes
become 57 -- but the *mix* is close to intact: as a share of everything a provoked dwarf did, attack
falls from 56% to 47%, demand rises from 7% to 21%, retort and complain hold at roughly 12% and 16%,
and only avoid (7% -> 0%) actually vanishes. What collapsed is the supply of provocations, not the
repertoire for answering them: with thefts down from 167 to 7 and insults from 34 to 21, there is
simply far less to answer. That is the honest reading of the headline number too -- a settlement of
unfailingly polite dwarves who never steal is a *duller* settlement, and the variety the arbitrator
was built to show is the thing the reward never asked for and therefore lost.

The same run in the viewer, `feud` at seed 21 for 2,000 ticks, is
[runs/feud_ppo.html](../runs/feud_ppo.html):

| | deaths | hits | thefts | feuds | ignore | retort | demand | complain | avoid | hit | asks | kept |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | 3 | 37 | 13 | 3 | 3 | 11 | 5 | 9 | 2 | 24 | 12 | 6 |
| imitator | 1 | 17 | 7 | 1 | 0 | 6 | 0 | 0 | 0 | 15 | 1 | 0 |
| ppo/best | 1 | 17 | 2 | 1 | 0 | 4 | 0 | 1 | 0 | 13 | 14 | 14 |

The obvious next knob is the reward, not the algorithm: a term that pays for *answering* a
provocation at all, or a per-episode variety bonus, would buy back the middle five. That is the same
open question the plan has always named -- social behaviour needs reward shaping -- now with a
measurement attached to it.

### A second reward table, and what it did not fix

The three changes above -- respect-only standing at weight 12, relative wealth, the `time` cost --
were an attempt at exactly that knob, and a second PPO run with identical settings (45 iterations,
400 ticks, same seeds, same seed) was trained against them. **It did not work.** The policy is
more degenerate than the first, not less.

The same paired evaluation, every policy on the same settlements: seeds 20-23, seven scenarios,
600 ticks, 28 episodes each, all four scored under the second table (the imitator row is
`imitator_v1`, which is what both PPO runs started from) (the first table's numbers are
not comparable -- two of its terms mean something else).

| Policy | reward | needs | alive | wealth | standing | promises | violence | time |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | -189.5 | -185.6 | -9.6 | -0.0 | +9.5 | +3.6 | -0.8 | -6.5 |
| imitator v1 | -278.3 | -262.4 | -11.8 | -0.0 | -1.8 | +0.3 | 0.0 | -2.6 |
| ppo v1 | -161.5 | -198.5 | -1.1 | -0.0 | +45.2 | +13.9 | 0.0 | -21.0 |
| **ppo v2** | **-96.0** | -148.9 | 0.0 | -0.0 | +45.2 | +9.4 | 0.0 | -1.7 |

And what the settlements actually did, totalled over the same 28 episodes each:

| Policy | ignore | retort | demand | complain | avoid | attack | apologise | steal | gossip | accept | fulfil | **socialize** | **work** | deaths | thefts | hits |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | 13 | 72 | 41 | 82 | 38 | 312 | 68 | 156 | 1117 | 53 | 66 | **6409** | **70956** | 18 | 156 | 214 |
| imitator v1 | 7 | 121 | 38 | 93 | 41 | 327 | 18 | 169 | 765 | 4 | 4 | **1204** | **78838** | 22 | 169 | 259 |
| ppo v1 | 4 | 6 | 12 | 10 | 0 | 28 | 31 | 15 | 628 | 195 | 220 | **35563** | **52706** | 2 | 15 | 27 |
| ppo v2 | 1 | 0 | 7 | 3 | 0 | 0 | 13 | 2 | 613 | 132 | 175 | **36345** | **51165** | 0 | 2 | 0 |

**Read it honestly, again.** v2 wins the number it was trained on by 65 points and loses on every
behavioural axis there is. It socialises *more* than v1 (36,345 against 35,563, and 5.7x the
teacher) and works *less* (51,165 against 52,706, 72% of the teacher). The graded reactions fall
from v1's 32 to 11 against the teacher's 246: `RETORT` and `AVOID` are now never chosen at all,
`IGNORE` once in 28 episodes. Thefts fall from 156 to 2, hits from 214 to **0**, deaths to 0. It is
a settlement of six dwarves who compliment each other, run errands and never once have a problem.

Each of the three changes can be traced:

- **Respect-only standing did not stop the pump.** v1 and v2 both score +45.2 on it. Respect is
  moved by `HELP` and by `PROMISE_KEPT`, and "take on every ask and fulfil it" moves those as
  reliably as praise moved trust. The pump moved from words to errands; it did not close.
- **The time cost was dodged, not paid.** v2 drove its `needs` from v1's -198 to -149 -- genuinely
  better dwarf-keeping -- and with the needs down the cost almost never triggers: -1.7 an episode
  against v1's -21.0, on *more* chat. Gating a cost on a state the policy controls hands it a way
  out.
- **Relative wealth did what it said and nothing more.** Every policy now scores -0.0. It removed a
  term that was being paid six times over; it did not redirect anything, because nothing was
  chasing it.

`runs/learn/ppo/best` is therefore still **v1**; v2 is kept beside it at `runs/learn/ppo_v2/best`
and v1's original run directory at `runs/learn/ppo_v1/`, so both are loadable. The honest
conclusion is the one the first run already suggested and this one confirms: a scalar sum of
settlement-state deltas has a cheapest path through it, and *tightening the cheap terms only moves
the cheapest path*. What the reward has never contained is any payment for a provocation being
answered, for variety, or for a settlement having anything happen in it -- and until it does, PPO
will keep finding whichever polite loop the table happens to leave open.

### A third table: one reward per dwarf

Both collapses have the same shape, and it is not really a shape a *weight* can fix: six dwarves
were handed one objective, and one objective has one cheapest path. So the third table stops
tuning the terms and changes who the reward is for. **Every dwarf is scored by a table shaped by
its own traits**, so six dwarves in one settlement pursue six objectives and no single equilibrium
fits them all. `RewardModel(mode="trait")`, and `--reward trait` on both `ppo.py` and
`evaluate.py`.

```
  python -m dwarfsim.learn.ppo --init runs/learn/imitator --out runs/learn/ppo_trait \
      --iterations 45 --ticks 400 --reward trait
  python -m dwarfsim.learn.evaluate --seeds 20-23 --reward trait --per-dwarf bully:21 \
      --policies teacher runs/learn/imitator runs/learn/ppo_v1/best runs/learn/ppo_trait/best
  python -m dwarfsim run --scenario feud --seed 21 --scorer runs/learn/ppo_trait/best \
      --out runs/feud_trait.jsonl --html runs/feud_trait.html
```

**Six new terms**, because most traits have nothing in the old seven to grip:

| Term | Weight | Value per step |
| --- | --- | --- |
| `hurt` | 3.00 | `-(health lost this tick) / 20`: a wound, separately from dying of it |
| `grudge` | 0.05 | `-(mean grudge I am carrying)`, 0..1 -- a level, charged every tick it lasts |
| `slight` | 0.50 | `-1` on a step I chose `IGNORE` having been insulted *in front of witnesses* |
| `duty` | 1.00 | `+1` per `HELP` for a dwarf I trust, and per blow struck at somebody who just hit one |
| `social_act` | 0.02 | `+1` on a step I chose `SOCIALIZE` |
| `payback` | 0.30 | `+1` on a step I chose `RETORT` or `ATTACK` while already provoked |

The other seven keep their v2 definitions, at their v2 weights, except that `wealth` is read
**absolutely** again (v1 style): the relative version is zero-sum across the settlement, and a
zero-sum pot cannot be scaled six different ways without the scalings fighting over it.

**And then the mapping, which is the actual experiment** -- `TRAIT_SHAPING`, one row per term,
read as "multiplier at trait 0, multiplier at trait 1", linear in between:

| Term | Trait | at 0 | at 1 | What the dwarf is being told |
| --- | --- | --- | --- | --- |
| `wealth` | greed | 0.20 | 1.80 | the greedy one's ore is worth 4.5x the indifferent one's |
| `standing` | pride | 0.40 | 1.60 | the proud one is the one that cares what the room thinks |
| *social part of* `needs` | sociability | 0.40 | 1.60 | loneliness bites the sociable dwarf hardest |
| `social_act` | sociability | 0.00 | 2.00 | and a small flat payment for choosing to talk |
| `violence` | bravery | 1.60 | 0.40 | starting a fight is cheap for the brave, dear for the timid |
| `alive` | bravery | 1.25 | 0.75 | the brave discount their own death a little |
| `hurt` | bravery | 2.00 | 0.00 | the coward is charged for bleeding; the brave one is not |
| `slight` | pride | 0.00 | 2.00 | swallowing a public insult |
| `grudge` | forgiveness | 0.00 | 2.00 | holding on costs the forgiving and is free to the bitter |
| `duty` | loyalty\* | 0.00 | 2.00 | helping and defending a friend |
| `payback` | temper | 0.00 | 2.00 | answering a provocation in kind |

\* `mind.TRAITS` has six entries and `loyalty` is not one of them, so it is **derived**:
`(forgiveness + (1 - greed)) / 2`, the dwarf that lets things go and does not count its gold. That
is the one row of the table that is not a number the sim already rolls, and it should be read as a
stand-in.

**Every multiplier is 1.0 at trait 0.5**, which is the whole trick for keeping the scale: a
settlement of perfectly average dwarves scores `REWARD_WEIGHTS_TRAIT` exactly, the total stays
within sight of v1 (teacher -149 against v1's -146 on the same settlements), and the PPO settings
transfer with nothing touched. Mechanically the per-dwarf part is folded into the term *values*
before they are weighted, so `Rollout` still computes the reward as `terms @ weights` against one
global weight vector and nothing in the training loop had to learn about traits.

**The run**: 45 iterations in 574 s, identical settings. Rollout reward -158 -> -17, entropy 0.16
-> 1.00, KL to the imitator 0.004 -> 1.55. Validation went -139, -114, -105, -69, -51, -32, -33,
-15.3, **-14.8** at iterations 5 through 45, so like v2 and unlike v1 it was **still improving when
the budget ran out** -- `best` is the last iteration.

#### What it learned

Seeds 20-23, seven scenarios, 600 ticks, 28 episodes per policy. Scored under the **trait** table:

| Policy | reward | needs | alive | wealth | standing | promises | violence | time | hurt | grudge | slight | duty | social_act | payback |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | -149.2 | -187.1 | -9.6 | +26.6 | +9.7 | +3.6 | -0.5 | -6.5 | -4.1 | -6.4 | -0.0 | +11.8 | +6.7 | +6.6 |
| imitator | -224.1 | -254.6 | -10.4 | +31.9 | -1.0 | +0.4 | -0.4 | -2.8 | -4.8 | -4.0 | -0.0 | +13.0 | +1.6 | +6.9 |
| ppo v1 | -100.1 | -199.3 | -1.0 | +23.5 | +44.4 | +13.9 | 0.0 | -21.0 | -1.6 | -4.0 | -0.0 | +9.1 | +35.2 | +0.7 |
| **ppo_trait** | **-34.6** | -133.1 | -6.9 | +15.1 | +44.7 | +0.9 | -0.5 | -1.2 | -3.6 | -3.3 | 0.0 | +12.6 | +35.7 | +4.9 |

and the **same four policies on the same settlements** scored under the first table, which none of
the trait terms exist in:

| Policy | reward | needs | alive | wealth | standing | promises | violence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | -146.4 | -185.6 | -9.6 | +30.8 | +15.1 | +3.6 | -0.8 |
| imitator | -233.7 | -250.1 | -10.7 | +36.2 | -8.7 | +0.4 | -0.8 |
| ppo v1 | -85.8 | -198.5 | -1.1 | +26.6 | +73.3 | +13.9 | 0.0 |
| **ppo_trait** | **-52.3** | -132.3 | -7.0 | +17.3 | +69.6 | +0.9 | -0.9 |

That second table is the one worth pausing on: **ppo_trait beats ppo v1 on v1's own table**, which
it was never trained against, and it does it by halving the term that dominates everything
(`needs` -132 against -198) rather than by farming `standing`, where the two are level.

The behaviour under it -- the same 28 episodes, and identical under either scoring, because the
table changes only what is measured:

| Policy | ignore | retort | demand | complain | avoid | attack | apologise | steal | gossip | accept | refuse | fulfil | **socialize** | **work** | deaths | thefts | kept | unprovoked | hits |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | 13 | 72 | 41 | 82 | 38 | 312 | 68 | 156 | 1117 | 53 | 76 | 66 | **6409** | **70956** | 18 | 156 | 51 | 7 | 214 |
| imitator | 15 | 96 | 26 | 73 | 44 | 335 | 20 | 153 | 601 | 6 | 48 | 6 | **1507** | **78342** | 20 | 153 | 6 | 7 | 248 |
| ppo v1 | 4 | 6 | 12 | 10 | 0 | 28 | 31 | 15 | 628 | 195 | 7 | 220 | **35563** | **52706** | 2 | 15 | 194 | 0 | 27 |
| ppo_trait | 12 | 23 | 16 | 22 | 30 | 270 | 20 | 33 | 407 | 13 | 10 | 21 | **37602** | **41916** | 13 | 33 | 13 | 8 | 164 |

**Read it honestly.** Half of the collapse is genuinely gone and half of it is worse.

What came back is everything that needs *something to happen*. The graded reactions go from v1's
32 to **103** against the teacher's 246, and `AVOID` -- which v1 never chose once in 28 episodes --
is back at 30 against the teacher's 38. Blows go 27 -> 164, thefts 15 -> 33, deaths 2 -> 13. The
errand pump is *shut*: `FULFIL` collapses from 220 to 21 and promises kept from 194 to 13, and
`standing` is held up anyway, so respect is now being earned by something other than running
messages. Every one of those is a trait term doing what it was asked: `payback` pays the
quick-tempered dwarf for hitting back, `duty` pays the loyal one for stepping in, `hurt` and
`alive` let the brave one take a risk the timid one will not.

What did **not** come back is the day's work. ppo_trait socialises **more** than either previous
policy (37,602 against v1's 35,563 and the teacher's 6,409) and works **less than any policy
measured** (41,916 against the teacher's 70,956). `social_act` is the obvious suspect and is
probably not guilty: ppo v1 scores +35.2 on it *without having been trained on it*, so the
chatter was already there and the new term merely prices it. The honest reading is that this table
bought back conflict and bought nothing back for the mine, and that a settlement of six dwarves who
talk for two thirds of the day is still not the settlement the arbitrator writes.

#### Six dwarves, six trait rolls: do they diverge?

The point of the table is per-*dwarf* variety, which a settlement total cannot show, so
`--per-dwarf bully:21` runs one settlement and counts each dwarf separately. Traits come off the
seed, so the rows line up and only the mix moves. `bully` seed 21, 600 ticks, teacher against
ppo_trait (blank means zero):

| Dwarf | Dominant traits | Policy | steps | work | social | gossip | steal | attack | flee | retort | complain | ignore | avoid |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Ingrid | temper .95, pride .90 | teacher | 159 † | 85 | 1 | 17 | 3 | 28 | | 7 | | | |
| | | **ppo_trait** | 600 | 219 | 250 | 12 | 3 | **26** | | | | | |
| Brokk | bravery .12, greed .81 | teacher | 34 † | 3 | | | 7 | 3 | 13 | | 2 | 1 | 5 |
| | | **ppo_trait** | 30 † | 5 | | | **2** | 1 | **7** | **5** | 1 | 1 | **3** |
| Dagna | bravery .95, greed .12 | teacher | 600 | 446 | 32 | 14 | | 6 | 2 | | | | |
| | | **ppo_trait** | 600 | 190 | 303 | 9 | | | | | | | |
| Halvar | bravery .77, forgiveness .23 | teacher | 600 | 473 | 27 | 11 | | | | | | | |
| | | **ppo_trait** | 600 | 349 | 167 | 3 | | | | | | | |
| Steinar | pride .30, sociability .70 | teacher | 600 | 500 | 24 | 5 | | | | | | | |
| | | **ppo_trait** | 600 | 323 | 191 | | | | | | | | |
| Tova | forgiveness .14, bravery .80 | teacher | 600 | 463 | 38 | 6 | 3 | | | | | | |
| | | **ppo_trait** | 600 | 231 | 293 | 2 | | | | | | | |

† died; the step count is how long it lasted.

**Yes, but narrowly.** The two dwarves the scenario puts in conflict behave like their traits under
ppo_trait and do not under ppo v1, which flattened both: quick-tempered proud Ingrid still throws 26
blows (v1: zero, and it survives by never fighting), and timid greedy Brokk still flees, steals,
avoids and now *retorts* five times before it dies at tick 30 (under v1 it lives all 600 ticks
working and chatting, which is not what bravery 0.12 in a room with a bully looks like). The other
four -- all high-bravery or low-conflict rolls, with nothing in this scenario aimed at them -- are
four copies of the same work-and-talk mix, exactly as they are under the teacher. So the divergence
that appears is *situational*: the trait reward stops the policy from ironing out a dwarf whose
situation calls for something, but it does not make an unprovoked settlement interesting.

The same run in the viewer, `feud` at seed 21 for 2,000 ticks, is
[runs/feud_trait.html](../runs/feud_trait.html):

| | deaths | hits | thefts | feuds | ignore | retort | demand | complain | avoid | hit | asks | kept |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| teacher | 3 | 37 | 13 | 3 | 3 | 11 | 5 | 9 | 2 | 24 | 12 | 6 |
| imitator | 1 | 17 | 7 | 1 | 0 | 6 | 0 | 0 | 0 | 15 | 1 | 0 |
| ppo/best (v1) | 1 | 17 | 2 | 1 | 0 | 4 | 0 | 1 | 0 | 13 | 14 | 14 |
| ppo_trait/best | 1 | 19 | 3 | 1 | 0 | 2 | 0 | 0 | 0 | 19 | 0 | 0 |

#### The verdict

Trait-conditioning **partly** fixes the collapse, and the honest summary is that it fixes the
symptom it was aimed at and not the one that matters most.

- It fixes the *yes-man* collapse proper. The errand pump is shut (`FULFIL` 220 -> 21), the
  reactions come back (32 -> 103), violence and theft come back, and a dwarf whose traits point
  somewhere is no longer ironed flat. The hypothesis -- six objectives, no single equilibrium --
  is supported by the one comparison that could falsify it cheaply: ppo_trait wins on v1's table
  too, so the gain is not the new terms marking their own homework.
- It does **not** fix the underlying thing. Chatter is up again and work is at its lowest of any
  policy measured; four of six dwarves still run the same loop as each other; and the graded
  reactions are still 103 against the teacher's 246. The divergence that showed up is the
  scenario's, kept rather than created.

So: a real improvement, not a solution, and a third data point for the same conclusion. Each table
moves the cheapest path somewhere new; conditioning the reward on traits moves it to *six* cheapest
paths, which is visibly better and still six cheapest paths. `runs/learn/ppo/best` is therefore
**left as v1**, with the new policy beside it at `runs/learn/ppo_trait/best` and its evaluation
under both tables at `runs/learn/ppo_trait/evaluation.csv` and `evaluation_v1.csv`.

## Where this goes in the Java mod

The two halves line up like this:

| Here | There |
| --- | --- |
| `arbitrator.observation_vector` | `AgentObservation` filling its row of the batch, against `ObservationSchema` |
| `dwarfsim/schema.py` | `brain/schema/ObservationSchema.java` + `ActionSchema.java`, joined by a `Species` |
| `arbitrator.score(obs, features)` | `Brain.act(BrainStep)` -- numbers in, numbers out, batched per tick |
| `WEIGHTS` (a linear model over candidate features) | a `.mbw` weight file driving `Forward`, once it is trained |
| `arbitrator._sample` (softmax at T) | `ActionDecoder`: sample while training, argmax when deployed |
| skills: WORK, ATTACK, FLEE, FIGHT_MONSTER | existing `Brain` implementations, each a trained specialist |
| the social skills: RETORT, COMPLAIN_TO, GOSSIP, ... | one speech-act handler plus the chat event; no network needed |
| `mind.MindState` | per-agent state that travels with the agent, like `BrainState`'s hidden vector |
| `mind.apply_event` | the game's own events (damage, theft, death) mapped into that state |
| `memory.MemoryBook` | a bounded NBT list on the entity; the same eviction rule |
| `goals`, `obligations` | more NBT, and the one thing that has to survive a chunk unload |

The shape a learned arbitrator would take: `observation (69) -> hidden -> a score per candidate`,
run once per dwarf per tick over the candidate list, sampled the same way. Candidate ordering is
stable (`Candidate.key()` sorts by skill, place, target and what it is about), which is what lets a
fixed-width head line up with a variable-length proposal list. The skills stay hand-written or
separately trained; only
the arbitrator's weights change, and everything either side of it -- the flat observation, the flat
candidate block, the sampling, the log -- stays exactly as it is.

What deliberately does not carry over: the settlement economy, the place graph and the dialogue
templates are scaffolding for having something to decide about.

## The log

`dwarfsim/log.py`. One JSON object per line: a header, then one object per tick, then a summary.
Events and decisions are complete on every tick; the per-agent snapshot is complete every tick too,
except the four slow blocks -- relationships, goals, memories and open obligations -- and
the settlement-wide reputation row, which are written every 10 ticks (and on the first and last).
That keeps six dwarves over 2,000 ticks to 6 to 16 MB depending on how much happens; writing
memories every tick roughly doubles it for nothing, because they move on the scale of hundreds of
ticks.

A feud is recorded when two dwarves' hatred is above 0.50 in both directions, and the summary's
`story` is the narration: kills, first blows, thefts, feuds, monsters, the sharpest insults, and now
every retort, demand, refusal, complaint, punishment, piece of gossip, promise made, kept or broken,
and every goal adopted or dropped ("Hrolf now wants to avenge Torvi"), each with the numbers that
moved. The summary also carries the tallies that make claims about a run checkable:

| Field | What |
| --- | --- |
| `reactions` | how many times each reaction kind was chosen at all |
| `provocations` | how many were suffered, how each one was answered, how many never were |
| `promises` | asks made, taken on, kept, broken, haggled over, and gold moved |
| `gossip` | pieces of gossip told |
| `punishments` | every fine and rebuke, with what it was for |
| `goals_adopted` | how many wants were taken up |
| `reputation` | the settlement's final opinion of everyone, the player included |

### What the summary counts

The run summary grew one block with the injury stage, `injuries`: every injury dealt by kind, how
many mended before the run ended, and what each survivor is still carrying. It is the honest answer
to "was that brawl bad", which the hit count on its own stopped being the moment a blow stopped
meaning two health.

## Known simplifications

* The arbitrator is linear and hand-weighted. That is the whole point of v0, but it means every
  behaviour is traceable to a weight rather than learned.
* The imitation stage only imitates. It reproduces the table's choice 97.8% of the time on held-out
  seeds and is a genuine MLP doing it, but nothing about it is better than the table -- there is no
  reward, so it cannot discover anything the table does not already do. Its tails are much less
  soft than they were (targeted data plus class-balanced weighting took the mean recall over the
  fifteen rarest skills from 0.68 to 0.83), but `IGNORE` is still 8 held-out decisions and still
  essentially unlearned.
* The reward table is seven hand-picked terms with hand-picked weights, which moves the
  hand-writing from the arbitrator to the objective rather than removing it. It says nothing about
  whether a dwarf *should* want any of those things; it is a statement of what this settlement
  counts as doing well, and PPO optimises exactly it, loopholes included. Two tables have now been
  tried and both were farmed -- the second harder than the first -- which is evidence that the
  problem is the shape of the objective (a scalar sum of state deltas, with nothing paying for a
  settlement having events in it) rather than the particular numbers in it.
* Every dwarf shares one policy and is trained as if the other five were part of a stationary
  world. They are not -- they are learning too, in the same rollouts -- so the "environment" moves
  under the value function. It is the standard simplification, and the reason the value loss never
  settles.
* Dwarves know where everyone is: there is no perception range beyond "same place". A thief's
  witnesses see it or do not, on a coin flip. Second-hand knowledge now exists, but only through
  gossip and complaints -- there is no rumour that nobody chose to tell.
* A dwarf answers at most one provocation per tick, and only ever the freshest one. Older
  unanswered slights still feed `grudge_target`, but they do not queue up their own scenes.
* Memory is flat: 64 episodes, no consolidation, no summarising "Hrolf is a thief" out of six
  thefts. The grudge sum is the only abstraction over it.
* An obligation has one deadline and no partial credit: two of three ore is not two thirds kept.
  Haggling is one round -- a counter-offer is accepted or refused, never re-countered.
* The chief is one dwarf with one power. There is no council, no succession, and no appeal.
* A retort settles the score: it is filed as a harm but cannot itself be answered, and a demand
  cannot be repeated at the same dwarf for 90 ticks. Both are dampers on a feedback loop that
  otherwise ran to the death every time, not claims about how people work.
* One monster at a time, only at the gate, and combat is still abstract: a blow is one roll for
  health and one for an injury, with no positioning, reach or timing. What is new is only what the
  roll *produces*.
* An injury is a kind and a severity and nothing else: there is no left arm and right arm, no scar
  that stays after it heals, no infection, and no dwarf who is worse at healing than another beyond
  being hungrier. A second broken arm deepens the first rather than being a second arm.
* Nobody treats anybody. Resting and eating are the whole of medicine; there is no skill for
  binding a wound, so a dwarf who cannot walk cannot be carried and nobody thinks to feed them.
* Habituation is per *(listener, speaker)* and knows nothing about content beyond a hash and two
  labels. "Nice sword" and "what a fine blade" are different signatures, so a determined farmer with
  a thesaurus does better than one with a clipboard -- the speech trust cap is what actually stops
  them, not the habituation.
* Suspicion is about one speaker at a time. Six dwarves all flattering the same one, in turn, never
  add up to anything, and nobody gossips about a flatterer.
* Skills do not compose. There is no plan longer than one tick, so "go to the forge, make an axe,
  then go to the gate" only happens because the terms happen to line up three ticks running.
* No economy beyond counting: prices are fixed, the forge turns ore into gold without limit, and
  gold has no use except being worth stealing, paying a fine with, or putting on the table in a
  bargain. Over two thousand ticks a diligent smith ends up absurdly rich.

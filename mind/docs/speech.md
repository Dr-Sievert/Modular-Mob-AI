# Speech: what a dwarf says back, and why

A dwarf answering you is two machines, not one. The first decides **what to say**, in rules. The
second decides **how to say it**, by picking a written line and filling its slots from real state.
There is no language model anywhere in this, and nothing in it invents a fact.

```
you say something
        |
   speech.hear ................. the mind moves: anger, trust, memory, an obligation
        |                        (unchanged; see design.md#speech)
   speechplan.plan ............. one ACT out of 43, plus the reasons
        |                        reading: what was heard, regard, mood, condition, needs,
        |                        goals, obligations, memory, and the dialogue state
   replybank.choose ............ one LINE, out of text/data/replies.jsonl
        |                        filtered by act, by fillable slots and by trust reach,
        |                        then scored
   realize.fill ................ every {slot} resolved from the sim
        |
   replies.swear ............... the profanity slot, gated by tier and anger
        |
        v
   "Then take my shift at the forge tomorrow. Rest."
```

Five modules and one data file:

| | |
| --- | --- |
| [`dwarfsim/dialogue.py`](../dwarfsim/dialogue.py) | the conversation's memory, per (dwarf, speaker) |
| [`dwarfsim/speechplan.py`](../dwarfsim/speechplan.py) | the rule table, and the two derived columns |
| [`dwarfsim/replybank.py`](../dwarfsim/replybank.py) | the bank, the filters and the chooser |
| [`dwarfsim/realize.py`](../dwarfsim/realize.py) | one resolver per slot |
| [`dwarfsim/learn/ranker.py`](../dwarfsim/learn/ranker.py) | the learned chooser, prepared, not default |
| [`text/data/replies.jsonl`](../text/data) | the bank, merged by `text/merge_replies.py` |

`dwarfsim/replies.py` is still the door — `replies.reply(world, dwarf, speaker_id, parsed)` — and
still holds the two rules that were there before the pipeline existed. Its old tables are now the
floor under an act the bank cannot say.

## The two rules that outrank everything

1. **A reply never contradicts the arbitrator.** For a request, an order or an offer the caller
   passes `decided`: the skill the dwarf actually chose. The words come out of that skill's cell in
   `replies.DECIDED` and no other, so a dwarf whose arbitrator chose `REFUSE` cannot be made to say
   yes by a rule or by a bank line. With no decision yet it says something non-committal — never
   yes. `speechplan.DECIDED_ACT` maps the skill onto an act so `/why` is still complete.
2. **A skill that already spoke wins.** If the sim's own reaction has put a line in this dwarf's
   mouth — a retort, a demand, an apology, a haggle — that line *is* the answer and nothing is
   added.

Both are checked in `replies.reply` before the planner is consulted at all.

## The conversation's memory

`dialogue.DialogueState`, one per `(dwarf, speaker)` pair, on the dwarf in `agent.dialogue` beside
`agent.regard`. It holds

* the last **6 turns**, each `(tick, who, intent, about, news, topic, act, entities)`;
* the **questions** this dwarf owes an answer to (`QUESTION_TTL` = 120 ticks), and the **asks** and
  **promises** open between the two of them;
* two **signature rings**, `heard` and `said`, which are `regard.Regard` objects and not a second
  mechanism: the curve that makes twenty pasted compliments worth nothing is the curve that makes a
  repeated sentence worth nothing;
* **what was last said about whom**, keyed by subject so "him again" means one person;
* when the pair last spoke.

It **expires** after `SILENCE_EXPIRY` = 600 ticks of quiet, and the next line starts a new
conversation. Without that, a greeting at tick 4 makes a greeting at tick 3,000 a repetition, which
is not memory, it is a bug.

This is what makes `CALLBACK` honest. "You asked me that already" is a fact about the ring, not a
flavour tag.

## The planner

`speechplan.plan(dwarf, speaker, parsed, world) -> Construction`.

### The tags

Ten buckets, and they are the bank's own words. Everything is defined once, in `speechplan`, and
`replybank` indexes on the same strings.

| Block | Tag | Values |
| --- | --- | --- |
| **hears** | `intent` | the thirteen in `text/SCHEMA.md` |
| | `about` | `SPEAKER` `LISTENER` `THIRD` `WORLD` `OBJECT` `NONE` |
| | `news` | `MISFORTUNE` `FORTUNE` `PLAN` `OPINION` `FACT` `SEEKING` `NONE` |
| | `topic` | the twelve in `text/SCHEMA.md` |
| | `sincerity` | `SINCERE` `SARCASTIC` `JOKING` |
| | `heat` | `calm` (aggression < 0.25) `edged` (< 0.60) `hot` |
| **state** | `mood` | `angry` `afraid` `grieving` `tired` `glad` `flat`, tested in that order |
| | `trust` | `hostile` `cold` `neutral` `warm` `close`, off `regard.felt_trust` and hatred |
| | `condition` | `healthy` `bruised` `hurt` `badly_hurt`, off the injuries and the blood |
| | `suspicion` | `low` `high`, off `regard.suspicion` against its threshold |

The mood order is the point: a dwarf that is furious *and* cheerful sounds furious. The trust
bucket reads **felt** trust — trust plus the warmth an afternoon of talk has bought — because how
somebody is spoken to is a mood about them, not a belief; hatred is read separately and outranks
everything.

### `about` and `news`, until the classifier labels them

`text/CURSOR_UNDERSTANDING_PROMPT.md` adds these two columns, and the classifier has not been
retrained on them yet. `speechplan.derive()` works them out by rule, in **one function**, so the
learned head drops in against the same contract: when `parsed` already carries legal values —
which is what the retrained classifier will give it — the rules are not consulted at all and the
reasons say `"labelled by the interpreter"`. `dwarfsim/skills.py`'s `GOSSIP` already labels its own,
because it is the one utterance the sim knows the subject of.

`about`, first hit wins:

1. a pool name that is neither of the two talking → `THIRD`
2. a second-person word (`you`, `your`, `youve`, `yer` …) → `LISTENER`
3. a first-person word (`i`, `im`, `ive`, `my`, `me`, `we` …) → `SPEAKER`
4. a hostile intent aimed at the listener → `LISTENER`
5. topic names a thing (`TREASURE` `FOOD` `DRINK` `WEAPON` `TRADE`) → `OBJECT`
6. topic names the settlement (`MINE` `FORGE` `HOME` `CLAN` `MONSTER` `WORK`) → `WORLD`
7. a question with nothing else to go on → `LISTENER`
8. otherwise `NONE`

`news`: a question is `SEEKING`; a greeting, farewell or apology is `NONE`; a request, order, offer
or threat is `PLAN`; a warning is `FACT`; praise, insult and blame are `OPINION`. A plain statement
is read off its words — a misfortune word, then a fortune word, then a plan word, then an opinion
word, then the valence, and `FACT` if none of that fires.

Four words are deliberately **not** in the pronoun lists, and each one cost something:

* `mine` — in a mining settlement it is a hole in the ground far more often than a possessive, and
  reading it as one sent every "how is the mine?" to `ADMIT_IGNORANCE`;
* `ill` (I'll), `id` (I'd) and `were` (we're) — indistinguishable from *ill*, an identifier and a
  past tense. All four are carried by the other markers in the same sentence when they matter.

Apostrophes are stripped before the lookup, so there is one spelling per word.

### The 43 acts

```
GREET_BACK FAREWELL_BACK SMALLTALK_BACK ANSWER_SELF ANSWER_PLACE ANSWER_THIRD ANSWER_ITEM
ANSWER_YESNO ANSWER_WHY DEFLECT ADMIT_IGNORANCE ASK_BACK ASK_CLARIFY CALLBACK SYMPATHIZE
GLOAT CONGRATULATE BELITTLE_FORTUNE REASSURE ADVISE OFFER_HELP WARN AGREE DISAGREE CORRECT
THANK APOLOGIZE ACCEPT REFUSE BARGAIN DEMAND_PAYMENT MOCK INSULT_BACK THREATEN_BACK
DEMAND_APOLOGY ACCUSE_BACK SUSPECT_FLATTERY BOAST COMPLAIN REMINISCE CHANGE_SUBJECT SILENCE
JOKE
```

`speechplan.ANSWERING` is the subset that answers a question with a fact. A question the dwarf
cannot know never gets one: after the table has chosen, `knows_answer(scene)` is checked and an
answering act becomes `ADMIT_IGNORANCE`. That guard is the planner's half of the bank's own rule —
*never invent a fact the game cannot fill from a slot*.

### The rule table

`speechplan.RULES` is one list, read top to bottom. A rule names any of the six *hears* tags, any of
the four *state* tags, and optionally a predicate for what a bucket cannot say (`_hates`,
`_repeated`, `_guilty`, `_brave`, `_proud`, `_cannot_know` …). **The highest priority that matches
wins; among equals, the most constraints; among those, the one written first** — so the table reads
as a table, general row first and the exception under it.

| Priority | What lives there |
| --- | --- |
| 30 | the conversation's own memory: the three `CALLBACK` rows |
| 25 | sarcasm and flattery, which must be *seen* to be caught |
| 20 | a question this dwarf cannot honestly answer |
| 13 | the cruel versions — hatred, not coldness, buys these |
| 12 | the body and the strong moods; yes/no and why questions |
| 11 | pride, courage, guilt, the long conversation |
| 10 | the `about`/`news` table — the bulk of it |
| 9 | questions, by what they are about; a greeting through a bad mood |
| 8 | the plain intent table |
| -10 | the floor: `DEFLECT` when cold, `SMALLTALK_BACK` otherwise |

The worked example, and the case the bank exists for — *"I've been sick lately"*, heard as
SMALLTALK / about `SPEAKER` / news `MISFORTUNE`:

| trust | act | rule |
| --- | --- | --- |
| `close` | `SYMPATHIZE` | `misfortune.close` |
| `warm` | `OFFER_HELP` | `misfortune.warm` |
| `neutral` | `SMALLTALK_BACK` | `misfortune.neutral` |
| `cold` | `DEFLECT` | `misfortune.cold` |
| `hostile` | `GLOAT` | `misfortune.hostile` |
| `hostile`, hatred ≥ 0.55 | `MOCK` | `misfortune.cruel` |

Every `Construction` carries its `reasons` out with it — the winning rule and the three it beat,
each with the short sentence from the table — the way `arbitrator.decide` carries its terms. That
is what the talk app prints under every reply and what `/why` prints in full.

## The bank

One JSON object per line in `text/data/replies.jsonl`:

```json
{"hears": {"intent": "SMALLTALK", "about": "SPEAKER", "news": "MISFORTUNE",
           "topic": "NONE", "sincerity": "SINCERE", "heat": "calm"},
 "state": {"mood": "flat", "trust": "warm", "condition": "healthy", "suspicion": "low"},
 "act": "SYMPATHIZE", "slots": ["speaker"],
 "text": "That's a rotten run of luck, {speaker}. Sit by the fire a while."}
```

`Bank.load()` **prefers `replies.jsonl` and falls back to `replies_seed.jsonl`**, and
`Bank.source` says which it read — the one thing a caller has to be able to see, because a run
against 300 hand-written lines and a run against 6,000 generated ones are the same code and very
different conversations. The seed bank is 314 lines covering all 43 acts at least twice and the
full trust spread for misfortune, fortune, questions, greetings, insults and requests. **When the
real bank lands nothing else changes.**

### Merging

```
python text/merge_replies.py text/batches/replies_*.jsonl
python text/merge_replies.py text/batches/replies_01.jsonl --dry-run
```

Same spirit and same report as `text/merge_labels.py`. It calls `replybank.parse_line` for the
enum, slot and length rules, so the merge tool and the loader cannot drift apart. Rejected:

* a bad enum anywhere in `hears`, `state` or `act`;
* an unknown `{slot}` — `realize.py` has one resolver per slot and no others;
* a slot used in `text` but not declared in `slots`, or declared and never used;
* a real name from the pool in `text` — names come from `{speaker}`, `{dwarf}` and `{third}`;
* a line outside 1 to 25 words;
* a `SILENCE` line whose text is not exactly `...`;
* a duplicate — same words **and** same tags. Not words alone: every `SILENCE` line is the same
  three dots and the whole point is that one is filed under grieving and another under hostile.

Nothing is repaired. A reply is a written thing and a half-right one is worse than one fewer.

### Choosing

`replybank.choose(construction, ...)`, three steps, and the order is the design:

1. **filter by act** — the planner has decided what the reply does, and that is not negotiable;
2. **filter by what can be said** — a line whose `{slot}` this dwarf cannot fill is dropped before
   anything is scored, and a line more than `TRUST_REACH` = 1 step away on the trust spread is
   dropped too, because a cruel line must never reach a friend. If that empties the list the
   filters relax in a fixed order — first the trust reach, then the act (`FALLBACK_ACTS`) — and
   every relaxation is named in `notes`, so a dwarf reaching outside its band is visible in `/why`
   rather than discovered in a transcript;
3. **score what is left**, three named terms:

   | term | what |
   | --- | --- |
   | `match` | the weighted tag agreement: `trust` 4.0, `intent` 3.0, `about`/`news` 2.5, `mood` 2.0, `sincerity`/`condition`/`suspicion` 1.5, `topic`/`heat` 1.0 |
   | `repeat` | `-6.0 × (1 - freshness)`, freshness straight off the dialogue state's `said` ring |
   | `slots` | `+0.25` per slot, capped at `0.75` — a small pull toward a line that names something real |

Among lines within `TIE` = 0.35 of the best, one is drawn from `world.rng`. That is the **only**
randomness in the pipeline: which line, never what goes into it. A seed and the same typing give
the same conversation.

## The slots

`realize.py`, one resolver per slot, each reading the sim. A resolver that returns `None` makes the
line unavailable — that is the whole of "nothing is invented".

| slot | from | `None` when |
| --- | --- | --- |
| `{speaker}` | the speaker's name | never |
| `{dwarf}` | its own name | never |
| `{third}` | a pool name in the line, else the loudest actor in its memory | it knows nobody |
| `{place}` | the topic's place, else where it is standing | never |
| `{item}` | the ask's item, else the topic's | the topic names no thing |
| `{qty}` | the ask's quantity, else what it is carrying | it has none |
| `{price}` | what was offered, else its own asking price off `greed` | never |
| `{injury}` | `condition.complaint()` — "my arm", "these ribs" | unhurt |
| `{culprit}` | who dealt the worst injury, else who last hit it, else the loudest suffered harm | nobody did anything to it |
| `{goal}` | its strongest goal, in words | it wants nothing in particular |
| `{need}` | its most pressing need past 0.45 — "a meal", "sleep" | nothing is pressing |
| `{memory}` | one recent episode, in words | its book is empty |
| `{time}` | the oldest of: this conversation, its worst injury, its loudest memory | it has no history |
| `{work}` | what it has been at, by where it stands | — |

`{place}`, `{item}` and `{price}` are **bare** — no article, no "the" — because the line carries the
grammar around them ("at the {place}", "{price} gold"). A resolver returning "the forge" gave "at
the the forge" in a third of the bank, and that was the first thing this got wrong.

Profanity is unchanged: `replies.swear` fills the optional slot on an answer to a provocation only
(`replies.SWEARING_ACTS`), gated by the world's tier ceiling and the dwarf's temper, anger and
hatred.

## Dwarf to dwarf

`speech.answer(world, listener, speaker, parsed)` runs the same pipeline for a dwarf spoken to by
another dwarf, and `SOCIALIZE` and `GOSSIP` call it. Two things about it:

* it emits with `apply=False` — **a reply is words**: it moves no trust, no needs and no memory.
  Only what was *said to* the listener does that, and `speech.hear` has already done it;
* it draws from `world.speech_rng`, a separate stream, so giving the settlement a conversation
  moves no other draw in a run and a seed still replays word for word. The same trick swearing uses.

An ask is deliberately left unanswered here: the arbitrator has not weighed it yet, and a line at
that point would be the one thing rule 1 forbids.

## The learned chooser

`replybank.score(line, construction, scene, state, tick)` has the same shape as the arbitrator's
`score(observation, candidate_features)` and is the seam. `dwarfsim/learn/ranker.py` trains the
model that replaces it:

```
python -m dwarfsim.learn.ranker --epochs 40 --out runs/learn/ranker
```

**The training data is the bank itself.** Every line was written for a set of tags, so that set is
the construction it is the right answer to and the line is its own positive; the negatives are the
lines with the *same act* and a different state key, which is the discrimination that actually
matters, because the act is already decided by the time the chooser is asked anything. A
166-48-48-1 MLP in `learn/model.py`'s style, trained listwise, exported as the same `.npz` + `.json`
pair `learn/scorer.py` writes — six float32 arrays, `ARRAY_ORDER`, `y = x @ weight.T + bias`.

**It is not the default and nothing switches to it.** Pass a loaded `RankerScorer` as
`replies.reply(..., ranker=...)`. On the seed bank it reaches about 88% held out against the
weighted match's 100% on the same rows, which is the honest answer rather than a disappointing one:
the self-supervised task *is* the weighted match's own definition, so the baseline cannot lose it.
A real bank with genuine near-ties, or preference data from somebody reading transcripts, is what
would make this worth switching on. The repetition penalty stays outside the model either way,
because it is about this conversation rather than about this line.

This writes its own pair and does **not** re-freeze `shared/models`.

## What is simplified

* **`about` and `news` are rules, not a model.** `derive()` is a stand-in with a documented order
  and it will get a sentence wrong — "Hrolf's been sick, you know" reads as about the third party
  because the name is checked before the pronoun. One function, one seam, one retrain.
* **The bank is 314 seed lines.** Every act has at least two, which is enough to test with and not
  enough to stop a dwarf reaching for the same line twice in a long conversation. The Cursor job in
  `text/CURSOR_REPLIES_PROMPT.md` is 6,000.
* **A reply still moves no state.** It is words. Nothing a dwarf says back changes anybody's mind,
  including its own; only what is *said to* it does.
* ~~**Open asks and promises are tracked but nothing reads them yet.**~~ **Done.**
  `World.note_obligation` is the one place an obligation is mirrored into both dwarves'
  `DialogueState`, called at every point the status moves — proposed, accepted, kept, broken,
  refused, expired — and `DialogueState.latest_ask` is the read-back. Two rules sit at priority
  **31**, above the heard-ring callbacks, because a record beats a count: `callback.ask_open`
  when they are asking again for something already on the table ("Still no, {speaker}."), and
  `callback.ask_taken_on` when this dwarf took it on and has not done it yet ("About that {item}
  you wanted. It's seen to."). Both want the ask to have been made *of* this dwarf; one it made
  of them is theirs to call back on. The `{item}`, `{qty}` and `{price}` slots fall back to the
  open obligation's ask when the line heard carries none of its own, so a line that names an item
  can still only be chosen when there really is one to name.
* **One construction per line heard.** A dwarf does not volunteer anything, interrupt, or start a
  subject of its own.

# Talking to them

```
python -m dwarfsim.talk --seed 1 --dwarves 3 --profanity 2
```

A console app for the one thing the JSONL log cannot give you: saying something to a dwarf and
watching, in the same second, what it did to the dwarf's head and which way that tipped its next
decision. It adds no rules. Words go in through the classifier, the labels go through
`speech.hear`, the sim ticks, `arbitrator.decide` picks, and the narration is the log's own
`Recorder`. Everything on the screen is read back out of those.

## What is on the screen

Left, the dwarf you are talking to: traits, the four emotions as bars with an arrow and the size
of the last change, the four needs, health and place, **what is broken** and how badly, its
`trust / respect / hatred` toward `player` and toward each other dwarf, the goals it currently
holds, the three loudest memories with their salience, and any open obligation it is on either
end of.

Under the relationship numbers there are two more, and they are the anti-farming model made
visible (see [design.md](design.md#no-approval-farming)):

* **warmth** -- what words buy. It is capped, it fades on its own, and it is what makes a dwarf
  *feel* friendly toward you without believing anything about you. Trust is what deeds buy.
* **suspicion** -- what saying the same thing too often buys. Past 0.50 the panel says *it thinks
  you want something*, and your next compliment lands as a `FLATTERY` rather than a `PRAISE`.

The conversation panel shows the **newest** exchanges: it walks the feed backwards, measures each
entry as it will really be drawn, and stops when the next would not fit, always drawing the latest
one whole. `/log N` prints the last N in full however long they are, and `/clear` empties the
panel. Under `--no-rich` there is no panel to fit anything into, so the prompt prints only what
has happened since the last one and lets the terminal scroll.

Right, the conversation. Per line: what you typed, what the interpreter read out of it
(`intent topic addressed aggression valence urgency sincerity names`, one line), the event it
became and every emotion and relationship that moved with `before -> after`, a note for each
witness whose state moved, what the dwarf then chose with the three biggest terms behind it, and
what it said back -- out of its own skill's templates when it reacted, and out of
`dwarfsim/replies.py` otherwise (below).

Bottom, the prompt. The screen is redrawn after every action.

With `rich` installed that is two panels side by side; without it the same content stacks with a
rule between, in plain ANSI. `pip install --user rich` if you want the panels;
`--no-rich` if you do not.

## Typing at it

Plain text is said to the focused dwarf, as speaker id `"player"`. A line that names a dwarf
("Brokk, get out of my forge") moves the focus to that dwarf first, so you can talk to the
settlement without reaching for `/to` every time. After the line lands the sim runs three ticks
(`--ticks-per-say`) so the dwarf has room to answer.

| Command | What |
| --- | --- |
| `/to NAME` | focus a dwarf (a prefix is enough) |
| `/tick N` | run N ticks and read back the narration |
| `/give ITEM N` | hand over `gold`, `ore`, `food` or `ale` -- a `GIFT` |
| `/hit` | swing at the focused dwarf, a few points of damage |
| `/why` | the full term breakdown of its last decision, what it was choosing between, and the construction behind what it last said |
| `/mind` | everything in its head |
| `/all` | one line per dwarf |
| `/labels k=v ... text=...` | say something with hand-typed labels (`about=` and `news=` included) |
| `/log N` | print the last N exchanges in full, however long they are |
| `/clear` | empty the conversation panel |
| `/help`, `/quit` | |

`--script FILE` (or `-` for stdin) plays a file of those lines and prints the transcript instead
of opening a prompt. That is how the app is tested end to end and how a demo is reproduced.

## The classifier, and doing without it

`text/models/clf` is loaded lazily, at the first line you type, and never at import: it is
rewritten in place by a retrain, and a half-written `weights.npz` should not stop the app from
starting. If it is missing, half-written or trained on a label set this sim does not know, you get
one clear message and the fallback:

```
/labels intent=INSULT aggression=0.9 valence=-0.8 text=You couldn't swing a pick straight.
```

Every field defaults (`SMALLTALK / NONE / LISTENER / 0.0`), `text=` takes the rest of the line,
and names are matched exactly against the pool as usual. Anything the classifier says is put
through the same check: an intent or topic this sim does not have becomes `SMALLTALK` / `NONE`
with a note rather than a traceback.

`/labels` is also how you say something the classifier reads differently from the way you meant
it -- which, with a model trained on partial labels, is often.

## Swearing

`--profanity 0..3` (default 1) is how far the dwarves themselves will go: 0 never swears, 1 mild
oaths when annoyed, 2 crude insults at a dwarf they are angry with, 3 the slurs in
`text/profanity_speech.json` (or the in-world list if that file is missing) at one they hate.
It is a ceiling on what is *said* and nothing else --
what you type is always read for all three tiers of `text/profanity.json`, at every setting,
including 0. A matched term shows up in the `read` line as `profanity: tier 2 x1 (term)` with the
`aggression 0.10 -> 0.70` it caused, and the terms themselves are printed back only for tiers 1
and 2; a tier 3 match says `tier 3 x1` and no more. Two things the lexicon overrules the
classifier on, and says so in the same line: a term that names a person or a people makes the
intent `INSULT` (`intent SMALLTALK -> INSULT (lexicon)`) and, at tier 3, a `SLUR` -- the event
that costs about one and a half insults in anger and twice one in hatred. An oath about the world
("what the hell happened to the ore bins") only moves aggression and stays the question it was.

`--profanity-speech PATH` points at a JSON file of tier 3 terms the dwarves may *say*, in the
shape of `text/profanity.json`'s tiers (a bare list of `{term, kind, targets, strength, example}`
entries, or a whole `{"tiers": {...}}` file whose tier `"3"` is read). It defaults to
`text/profanity_speech.json`, which the sim only ever reads: if the file is there its entries are
used verbatim -- term, kind, targets and strength as written -- and if it is not there, is empty,
or cannot be read, the in-world list in `dwarfsim/profanity.py` stands. Terms that also sit in
the recognition file's tier 3 are kept and spoken. As the repository stands today,
`text/profanity_speech.json` is that shared list.

## Asks

A `REQUEST` or `COMMAND` whose topic points somewhere (`MINE`, `FOOD`, `DRINK`, `MONSTER`,
`FORGE`) is given the obvious structured `ask` -- "bring me two ore from the mine" -- so the
utterance creates a real obligation and the dwarf gets `ACCEPT`, `REFUSE` and `BARGAIN`
candidates to weigh, instead of only the vague `request_pull`. `/labels ask=none` suppresses it,
and `/labels ask=BRING:ore:2@MINE:pay4` writes one by hand. Everything about how the ask is then
answered is `dwarfsim/obligations.py` and the weight table; nothing here.

This is what makes the second demo work: the same words, from a stranger, are refused or haggled
over; after a gift or two they are taken on. Nothing about the words changes -- only
`trust_target`, `gratitude_target`, and whatever goal the gratitude made it adopt.

## What it says back

`dwarfsim/replies.py` is the door; behind it is the **speech pipeline**, which is its own document:
[docs/speech.md](speech.md). In short, every line you say gets an answer built in two halves.

1. **What to say.** `dwarfsim/speechplan.py` picks one **act** out of 43 -- `SYMPATHIZE`,
   `GLOAT`, `ADMIT_IGNORANCE`, `CALLBACK`, `SILENCE` and the rest -- from one readable rule table
   over what was heard (intent, *about*, *news*, topic, sincerity, heat) and what the dwarf is
   (trust in you, mood, condition, suspicion), plus what the two of you have already said. Every
   choice carries its reasons, the way the arbitrator's carries its terms.
2. **How to say it.** `dwarfsim/replybank.py` picks a written line for that act out of
   `text/data/replies.jsonl`, and `dwarfsim/realize.py` fills its `{slots}` -- `{injury}` from the
   dwarf's broken arm, `{culprit}` from who broke it, `{need}`, `{goal}`, `{third}`, `{price}` --
   from real state. **A line whose slot cannot be filled is never chosen**, so nothing a dwarf says
   is a fact it does not have.

The same "I've been sick lately" therefore runs the whole spread: *"Sick? Get inside, and I'll not
have you dying on my floor"* from a friend, *"Then take my shift at the forge tomorrow"* from
somebody warm, *"That's the way of it lately"* from a stranger, *"I've enough on without hearing
yours"* from somebody cold, *"Ha. Hurry up about it, then"* from an enemy -- and *"Poor you.
However will the hall manage"* from one who hates you.

The line under each reply on the screen says which act was chosen, which rule chose it, the top two
reasons, the slots that were filled and which bank line was used. `/why` prints the whole of it
under the arbitrator's own terms.

Two rules keep it from lying about the sim, and they are why it is a module and not more
templates:

1. **It never contradicts the arbitrator.** For a request, an order or an offer the app passes
   the skill the dwarf actually chose about you, and the words come from that skill's cell. A
   dwarf whose arbitrator chose `REFUSE` cannot be made to say yes. With no decision yet it says
   something non-committal -- never yes.
2. **It never speaks twice.** If the sim's own skill already put a line in the dwarf's mouth
   aimed at you -- a retort, a demand, an apology, a haggle -- that line *is* the answer and
   nothing is added.

Three things ride on top. A dwarf who has decided you want something answers with
**`SUSPECT_FLATTERY`** rather than `THANK` -- which is where suspicion becomes something you can
hear rather than a number in a panel. A dwarf carrying an injury tacks a clause onto whatever it
was going to say: *"Aye. Mind the arm."* And a dwarf **remembers this conversation**: ask the same
question twice and you get a `CALLBACK` -- *"You asked me that already. The answer hasn't moved."*
That memory (`dwarfsim/dialogue.py`) expires after 600 quiet ticks, so a greeting an hour ago is
not a repetition.

`text/data/replies.jsonl` is written by `text/merge_replies.py` out of the Cursor batches. Until it
exists the bank falls back to the 314 hand-written lines in `text/data/replies_seed.jsonl` and says
which it used; when the real one lands nothing else changes.

Dwarves answer each other through the same pipeline now, so `SOCIALIZE` and `GOSSIP` read as two
dwarves talking rather than one talking at another.

It changes no state but the conversation's own memory: the rest already moved, in `speech.hear`.
Variants are drawn from `world.rng` (and `world.speech_rng` between dwarves), so a seed and the
same typing give the same conversation.

## The seam for the tests

`talk.Session` is the whole app without a terminal: `say(text)`, `command(line)` and
`snapshot()`. Both actions return one *entry* -- a plain dict of what you typed, what was read
out of it, the deltas, the decisions, the lines spoken and the narration -- which is what
`talk_ui` draws and what `tests/test_talk.py` asserts on. `talk_ui.render(session)` returns the
whole screen as a string, with rich or without it.

## Known simplifications

* The player follows the focused dwarf around: `world.player.place` is set to its place every
  tick. Without that you can say something and then never be in the room to be answered.
* The player's pack is not part of the economy. Gold is counted (you start with 20 and `/give`
  really moves it), but `ore`, `food` and `ale` come out of nowhere.
* A dwarf that haggles (`BARGAIN`) with you is making a counter-offer you have no command to
  answer. It lapses after 40 ticks. Ask again with a payment if you want the deal.
* `/hit` is a plain `HIT` event with fixed damage: it does not go through the injury model, so
  you cannot break a dwarf's arm from the keyboard, only wear its health down.
* There is no command for tending a wound. You can feed a hurt dwarf with `/give food` and hope it
  eats; you cannot bind anything.
* Suspicion of you is per dwarf and has no memory of *what* you said, only that you said it before.
  Two speakers pasting the same line at the same dwarf are two unrelated strangers to it.
* `about` and `news` are worked out by rule, not by the classifier, which has not been retrained on
  them: `speechplan.derive` is one function and will read some sentences wrong. `/labels
  about=THIRD news=MISFORTUNE ...` overrides it.
* The reply bank is 314 hand-written seed lines until the Cursor batches land. Every act has at
  least two, which is enough to test with and not enough to keep a dwarf from reaching for the same
  line twice in a long conversation.
* A dialogue state remembers open asks and promises, but nothing reads them back yet: a `CALLBACK`
  comes from the ring of what was heard, not from the obligation.
* Sincerity does nothing to the sim's state -- `speech.hear` does not read it. It only changes
  what the dwarf says back.
* A reply is words, not an event: it moves no trust, no needs and no memory. Only what you say
  does that.
* One `Session` is one settlement. There is no save, no undo and no rewind; `--seed` is the only
  way back to a run you liked.

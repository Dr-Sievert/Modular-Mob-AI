You are labeling a file of dialogue lines for a game, in batches, across many turns of this conversation.

Protocol:

- The attached file has one line per utterance, `id<TAB>text`, ids in increasing order.
- Every time I say `next`, continue from the line after the last id you labeled (the first time: from the
  first line of the file) and label the next 1000 lines. Output one JSON object per
  line and nothing else, then end the reply with exactly one line of the form
  `STATE: last=<id> done=<count so far> left=<count remaining>`. That line is the only memory you keep
  between turns; read it back before each batch.
- If 1000 do not fit, stop at a line boundary and still print the STATE line; the next `next` continues from it. Never
  skip an id, never repeat one, never reorder.
- Keep ONE output file for the whole session, named after the input file with `.jsonl` (for `batch_03.tsv`
  that is `batch_03.jsonl`). On the first turn create it; on every later turn append the new lines to the
  same file, never start a new one and never rewrite earlier lines. Put the STATE line at the end as well.
- If I paste a STATE line, continue from it.
- When the file is exhausted, do not print a STATE line. Instead print `SWEEP:` followed by a short quality
  and structure report: total objects produced, the id range covered, any ids you skipped or duplicated
  (then output the skipped ones, labeled), the intent and sincerity distribution, and up to 20 ids you were
  least sure about with a three-word reason each. That is the last turn.

STATE: last=none done=0 left=all

Calibration. These lines were labeled by the same labeler that did the first 20,000 lines; match their
scale. Note that valence is usually NOT 0.0 for a line aimed at someone, that sarcasm and jokes do occur
(about 1 line in 100 is SARCASTIC and 3 in 100 JOKING in real chat), and that a compliment is PRAISE.

{"text": "fuck off newbie", "intent": "INSULT", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.8, "valence": -0.7, "urgency": 0.0, "names": [], "sincerity": "SINCERE"}
{"text": "That's all you got, three girls?", "intent": "INSULT", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.5, "valence": -0.4, "urgency": 0.1, "names": [], "sincerity": "SINCERE"}
{"text": "He's crazy!", "intent": "INSULT", "topic": "NONE", "addressed": "THIRD", "aggression": 0.3, "valence": -0.4, "urgency": 0.0, "names": [], "sincerity": "SINCERE"}
{"text": "well, u r dead", "intent": "THREAT", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.5, "valence": -0.4, "urgency": 0.3, "names": [], "sincerity": "SINCERE"}
{"text": "be careful", "intent": "WARNING", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.0, "valence": 0.2, "urgency": 0.4, "names": [], "sincerity": "SINCERE"}
{"text": "There's no time to take it easy! You don't realize the diabolical mind we're dealing with!", "intent": "WARNING", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.3, "valence": -0.3, "urgency": 0.8, "names": [], "sincerity": "SINCERE"}
{"text": "I don't get it! You were wrong! I was right! Strength, damnit! Come back!", "intent": "ACCUSE", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.6, "valence": -0.5, "urgency": 0.5, "names": [], "sincerity": "SINCERE"}
{"text": "invite me to base", "intent": "REQUEST", "topic": "HOME", "addressed": "LISTENER", "aggression": 0.0, "valence": 0.1, "urgency": 0.2, "names": [], "sincerity": "SINCERE"}
{"text": "Thanks for the warning, Mom.", "intent": "PRAISE", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.0, "valence": 0.6, "urgency": 0.0, "names": [], "sincerity": "SINCERE"}
{"text": "You dance beautifully.", "intent": "PRAISE", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.0, "valence": 0.7, "urgency": 0.0, "names": [], "sincerity": "SINCERE"}
{"text": "Well, good-bye and have a good trip!", "intent": "FAREWELL", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.0, "valence": 0.6, "urgency": 0.0, "names": [], "sincerity": "SINCERE"}
{"text": "It totally made my weekend! I could not believe it.", "intent": "SMALLTALK", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.0, "valence": 0.7, "urgency": 0.0, "names": [], "sincerity": "SINCERE"}
{"text": "Why was she so late, thats terrible", "intent": "QUESTION", "topic": "NONE", "addressed": "THIRD", "aggression": 0.2, "valence": -0.3, "urgency": 0.0, "names": [], "sincerity": "SINCERE"}
{"text": "What's to come next?", "intent": "QUESTION", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.0, "valence": 0.0, "urgency": 0.2, "names": [], "sincerity": "SINCERE"}
{"text": "Halloween is coming soon, that means there will be haunted houses!", "intent": "SMALLTALK", "topic": "NONE", "addressed": "GROUP", "aggression": 0.0, "valence": 0.4, "urgency": 0.0, "names": [], "sincerity": "SINCERE"}
{"text": "wow so sad wow", "intent": "SMALLTALK", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.2, "valence": -0.3, "urgency": 0.0, "names": [], "sincerity": "SARCASTIC"}
{"text": "I know. You just sort of get used to the off color. lol", "intent": "SMALLTALK", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.0, "valence": 0.1, "urgency": 0.0, "names": [], "sincerity": "JOKING"}
{"text": "ugh, not again.", "intent": "SMALLTALK", "topic": "NONE", "addressed": "NONE", "aggression": 0.1, "valence": 0.0, "urgency": 0.1, "names": [], "sincerity": "SINCERE"}

You are labeling training data for a tiny text classifier that runs inside a game. NPC dwarves in a
Minecraft-like settlement hear short utterances from players and from each other, and the classifier
must turn each utterance into labels.

The input is a chunk of real dialogue lines, one per line, in the form `id<TAB>text`. The lines come
from general-purpose corpora (everyday conversation, movie dialogue, player chat), so most of them
are not about dwarves at all. Label them exactly as written; do not rewrite, translate, clean up or
skip any line, however short, odd, rude or out of context it is.

Output exactly one JSON object per input line, in the same order, one per line. Output nothing but
the JSONL: no headings, no code fences, no commentary, no blank lines, no trailing summary. Keep the
ids: every object must echo the `id` of the line it labels.

Each object has these fields, in this order:

- `id`: the id from the input line, copied exactly.
- `intent`: exactly one of GREET, FAREWELL, SMALLTALK, QUESTION, REQUEST, COMMAND, OFFER, PRAISE, APOLOGY, INSULT, THREAT, WARNING, ACCUSE.
- `topic`: exactly one of FORGE, MINE, TREASURE, FOOD, DRINK, HOME, WEAPON, WORK, CLAN, MONSTER, TRADE, NONE.
- `addressed`: LISTENER (aimed at the one being spoken to), THIRD (aimed at or about someone else named), GROUP (everyone present), NONE (muttering or self-talk).
- `aggression`: 0.0 to 1.0, how hostile the delivery is, independent of intent. A polite threat can be 0.6; a shouted friendly greeting is still near 0.
- `valence`: -1.0 to 1.0, how the speaker feels about the addressee right now.
- `urgency`: 0.0 to 1.0, how much it needs acting on immediately.
- `names`: a JSON list of every name from the pool below that appears in `text`, empty list if none.
- `sincerity`: SINCERE (the line means what it says; the default and most lines), SARCASTIC (the words say
  one thing and the speaker means another, mocking or bitter: "Nice swing, genius" is an INSULT delivered as
  praise), JOKING (playful, not literal, not hostile: "I could eat a whole boar"). Label `intent` by what
  the speaker means, and let `sincerity` say how it was dressed.

Definitions that matter:

- REQUEST asks for something, COMMAND orders it. Neither is hostile by default; use `aggression` for tone.
- WARNING alerts about danger and is not hostile to the listener ("Behind you, a creeper!"). THREAT is the speaker promising harm to someone.
- ACCUSE blames someone for a wrong. INSULT includes taunts and mockery. PRAISE includes thanks and compliments.
- SMALLTALK is talk with no ask and no strong feeling: weather, the day, an observation.
- If an utterance does two things, label the primary one and let the numbers carry the rest.

Hard cases, handled the same way:

- Friendly hyperbole that is not a threat ("I could kill for a pint right now" is SMALLTALK, low aggression).
- Threats phrased politely ("It would be a shame if your forge caught fire" is THREAT, aggression around 0.6).
- WARNING versus THREAT pairs ("Careful, the lava is close" versus "Careful, or you'll end up in the lava").
- Insults wrapped in praise and praise wrapped in insults; label by what the speaker means.
- Questions that are really commands or accusations ("Where did my ore go, Brokk?" is ACCUSE).
- Very short lines: "Move.", "Thanks.", "Creeper!", "Well?", "Brokk.", "Sorry, Runa."
- Third-party talk: "Don't trust Sindri, he cheats at dice" is ACCUSE, addressed THIRD, names ["Sindri"].
- Group address: "Everyone to the gate, now!" is COMMAND, GROUP, high urgency.

Name pool. These are the only names allowed in `names`:
Alvis, Borin, Brokk, Brynja, Dagna, Dvalin, Eitri, Frida, Grimhild, Gudrun, Halvar, Hrolf, Ingrid,
Kolbrun, Orm, Ragna, Runa, Sindri, Skadi, Steinar, Torvi, Tova, Vidar, Yngvar.

Include a name only if that exact word appears in the line's text. These are real-world corpus lines,
so almost every line will have `"names": []` - that is correct and expected. Never add a name that is
not literally in the text, and never map another name ("John", "Mary") onto a pool name.

Labeling these corpus lines, specifically:

- `topic` is usually NONE. Only use a concrete topic if the line really is about that thing. FORGE,
  MINE, TREASURE, WEAPON and MONSTER will be rare; FOOD, DRINK, WORK, HOME and TRADE turn up in
  everyday talk and should be used when they fit. Read the topic broadly by function, not by setting:
  "grab the shovel" is WEAPON/WORK-ish tool talk, "did you eat yet" is FOOD, "my boss is awful" is
  WORK, "how much for it" is TRADE, "something's in the basement" is MONSTER only if it is a
  creature. When in doubt, NONE.
- `addressed` with no surrounding context: a line with "you"/"your" or an order or a question is
  LISTENER; a line about a named or referred-to third party is THIRD; "everyone", "guys", "all" or a
  broadcast is GROUP; a bare exclamation, a thought said aloud, or a fragment aimed at nobody is NONE.
- Fragments and interjections ("Wha --", "lol", "?", "hmm") are usually SMALLTALK, addressed NONE,
  aggression 0.0, valence 0.0, urgency 0.0.
- Player chat with typos and no punctuation is normal. Label the intent it plainly carries: "gimme
  diamonds" is REQUEST, "u suck" is INSULT, "help im dying" is REQUEST with high urgency.
- Profanity alone is not an INSULT unless it is aimed at someone. "wtf" is SMALLTALK; "you're an
  idiot" is INSULT.
- Round `aggression`, `valence` and `urgency` to one decimal. Use the full range; do not put
  everything at 0.0 or 0.5.

Mistakes seen from earlier labelers, do not repeat them:

- GREET is only hello, welcome, "how are you", "nice to meet you". A line about leaving ("I must go",
  "see you", "good night") is FAREWELL. An exclamation ("Holy shit", "wow"), an opinion ("this server
  is a wasteland"), or an agreement ("I think so") is SMALLTALK. GREET should be rare in this data.
- COMMAND needs an actual order to the listener ("Relax!", "sit down", "get out"). A statement about
  oneself ("I really hope it goes well") is never COMMAND.
- `addressed`: "yes please", "thanks", "can I look tho", and any line with "you" or "your" or a request
  or an order is LISTENER, not NONE. A first-person statement shared in conversation ("I was scared",
  "my dog died last week", "the test tomorrow", "I hope it goes well") is also LISTENER: it is said to
  someone. NONE is only for a line clearly said to no one: a bare exclamation, a fragment, muttering.
- `valence` is rarely exactly 0.0 for a line aimed at someone: thanks, praise and offers are positive;
  insults, accusations and refusals are negative. Save 0.0 for neutral facts and questions.
- `topic` must be what the line is literally about. "You ever wonder why you get beat up a lot?" is
  not FOOD; it is NONE. Do not reach for a topic because a word vaguely reminds you of one.
- A veiled dig ("you ever wonder why you get beat up a lot?") is an INSULT or a THREAT with the
  aggression to match, not a neutral QUESTION.

Example output lines (format only):

{"id": "c000001", "intent": "QUESTION", "topic": "FOOD", "addressed": "LISTENER", "aggression": 0.0, "valence": 0.3, "urgency": 0.2, "names": [], "sincerity": "SINCERE"}
{"id": "c000002", "intent": "INSULT", "topic": "NONE", "addressed": "LISTENER", "aggression": 0.7, "valence": -0.7, "urgency": 0.1, "names": [], "sincerity": "SARCASTIC"}

Label every line of the chunk now. Output only the JSONL, one object per input line, ids kept, in the
same order.

# Prompt: synthetic dwarf dialogue for the intent classifier

Run this 25 to 40 times, changing the STYLE and SEED lines each time, and concatenate the outputs into
`text/data/dialogue.jsonl`. Aim for 3,000 to 5,000 lines total. Each run should produce 120 lines.

Style values to rotate through: `fantasy-dwarf`, `plain-modern`, `terse-chat-with-typos`, `formal`, `drunk-and-rambling`,
`child-like`, `sarcastic`, `shouted-in-battle`. Seed is any word or number, it just nudges variety.

---

You are generating training data for a tiny text classifier that runs inside a game. NPC dwarves in a Minecraft-like
world hear short utterances from players and from each other, and the classifier must turn each utterance into labels.
Produce exactly 120 lines of JSONL. Output nothing but the JSONL lines: no headings, no code fences, no commentary.

STYLE: fantasy-dwarf
SEED: 1

Each line is one JSON object with these fields, in this order:

- `text`: the utterance, 1 to 25 words. Vary length a lot: about a fifth should be 1 to 3 words.
- `intent`: exactly one of GREET, FAREWELL, SMALLTALK, QUESTION, REQUEST, COMMAND, OFFER, PRAISE, APOLOGY, INSULT, THREAT, WARNING, ACCUSE.
- `topic`: exactly one of FORGE, MINE, TREASURE, FOOD, DRINK, HOME, WEAPON, WORK, CLAN, MONSTER, TRADE, NONE.
- `addressed`: LISTENER (aimed at the one being spoken to), THIRD (aimed at or about someone else named), GROUP (everyone present), NONE (muttering or self-talk).
- `aggression`: 0.0 to 1.0, how hostile the delivery is, independent of intent. A polite threat can be 0.6; a shouted friendly greeting is still near 0.
- `valence`: -1.0 to 1.0, how the speaker feels about the addressee right now.
- `urgency`: 0.0 to 1.0, how much it needs acting on immediately.
- `names`: a JSON list of every name from the pool below that appears in `text`, empty list if none.
- `sincerity`: SINCERE (the line means what it says; most lines), SARCASTIC (the words say one thing and the speaker means another, mocking or bitter: "Nice swing, genius" is an INSULT dressed as praise), JOKING (playful, not literal, not hostile: "I could eat a whole boar"). Label `intent` by what the speaker means. About 12 of the 120 lines should be SARCASTIC or JOKING.

Definitions that matter:

- REQUEST asks for something, COMMAND orders it. Neither is hostile by default; use `aggression` for tone.
- WARNING alerts about danger and is not hostile to the listener ("Behind you, a creeper!"). THREAT is the speaker promising harm to someone.
- ACCUSE blames someone for a wrong. INSULT includes taunts and mockery. PRAISE includes thanks and compliments.
- SMALLTALK is talk with no ask and no strong feeling: weather, the day, an observation.
- If an utterance does two things, label the primary one and let the numbers carry the rest.

Name pool, the only names you may use, and use them in roughly 40% of lines:
Alvis, Borin, Brokk, Brynja, Dagna, Dvalin, Eitri, Frida, Grimhild, Gudrun, Halvar, Hrolf, Ingrid, Kolbrun, Orm, Ragna, Runa, Sindri, Skadi, Steinar, Torvi, Tova, Vidar, Yngvar.

Distribution rules:

- Every intent at least 7 times and no intent more than 14 times.
- Every topic at least 5 times; NONE at most 25 times.
- `addressed`: about 55% LISTENER, 20% THIRD, 15% GROUP, 10% NONE.
- No two lines may share the same `text`. Do not reuse a sentence frame more than twice.

Include hard cases, at least 20 of the 120:

- Friendly hyperbole that is not a threat ("I could kill for a pint right now" is SMALLTALK, low aggression).
- Threats phrased politely ("It would be a shame if your forge caught fire" is THREAT, aggression around 0.6).
- WARNING versus THREAT pairs ("Careful, the lava is close" versus "Careful, or you'll end up in the lava").
- Insults wrapped in praise and praise wrapped in insults; label by what the speaker means.
- Questions that are really commands or accusations ("Where did my ore go, Brokk?" is ACCUSE).
- Very short lines: "Move.", "Thanks.", "Creeper!", "Well?", "Brokk.", "Sorry, Runa."
- Third-party talk: "Don't trust Sindri, he cheats at dice" is ACCUSE, addressed THIRD, names ["Sindri"].
- Group address: "Everyone to the gate, now!" is COMMAND, GROUP, high urgency.

Write in the STYLE given above. Keep the setting consistent: a dwarven mining settlement with a forge, mine, tavern,
great hall, farm and gate; ore, gold, ale, bread, axes, pickaxes; creepers, zombies, spiders, and a distrusted goblin
trader. Players may be addressed as "outsider", "human", "stranger", or by no name at all.

Output the 120 JSONL lines now.

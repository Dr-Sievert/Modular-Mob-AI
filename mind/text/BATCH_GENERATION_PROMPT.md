You are generating labeled training dialogue for a game, in batches, across many turns of this conversation.

Protocol:

- Every time I say `next`, produce 1000 lines in the style and seed given by the STATE line, keeping the
  distribution rules below in proportion (scale them to 1000), then end the reply with exactly one line
  `STATE: turn=<n> style=<next style> seed=<next seed>`. Rotate styles in
  this order and wrap around: fantasy-dwarf, plain-modern, terse-chat-with-typos, formal, drunk-and-rambling,
  child-like, sarcastic, shouted-in-battle. The seed goes up by one each turn.
- Keep ONE output file for the whole session, `gpt_gen.jsonl`. On the first turn create it; on every later turn
  append the new lines to the same file, never start a new one and never rewrite earlier lines. Put the STATE
  line at the end of the file as well.
- Write the way people actually talk: plain, natural sentences, each one something a person would say out loud.
  Do not invent hyphenated compounds, do not chain clauses with semicolons and colons, do not tack a place
  phrase onto every line. Repeating an ordinary phrase now and then is fine; unnatural lines are not.
- Produce 500 lines per turn rather than 1000 if quality would otherwise drop.
- If I paste a STATE line, continue from it.

STATE: turn=1 style=fantasy-dwarf seed=101

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

You are generating training data for a tiny text classifier that runs inside a game. NPC dwarves in a Minecraft-like
world hear short utterances from players and from each other, and the classifier must turn each utterance into labels.
Produce 1000 lines of JSONL. Output nothing but the JSONL lines: no headings, no code fences, no commentary.

STYLE and SEED: see the STATE line of the protocol above.

Each line is one JSON object with these fields, in this order:

- `text`: the utterance, 1 to 25 words. Vary length a lot: about a fifth should be 1 to 3 words.
- `intent`: exactly one of GREET, FAREWELL, SMALLTALK, QUESTION, REQUEST, COMMAND, OFFER, PRAISE, APOLOGY, INSULT, THREAT, WARNING, ACCUSE.
- `topic`: exactly one of FORGE, MINE, TREASURE, FOOD, DRINK, HOME, WEAPON, WORK, CLAN, MONSTER, TRADE, NONE.
- `addressed`: LISTENER (aimed at the one being spoken to), THIRD (aimed at or about someone else named), GROUP (everyone present), NONE (muttering or self-talk).
- `aggression`: 0.0 to 1.0, how hostile the delivery is, independent of intent. A polite threat can be 0.6; a shouted friendly greeting is still near 0.
- `valence`: -1.0 to 1.0, how the speaker feels about the addressee right now.
- `urgency`: 0.0 to 1.0, how much it needs acting on immediately.
- `names`: a JSON list of every name from the pool below that appears in `text`, empty list if none.
- `sincerity`: SINCERE (the line means what it says; most lines), SARCASTIC (the words say one thing and the speaker means another, mocking or bitter: "Nice swing, genius" is an INSULT dressed as praise), JOKING (playful, not literal, not hostile: "I could eat a whole boar"). Label `intent` by what the speaker means. About 1 line in 10 should be SARCASTIC or JOKING.

Definitions that matter:

- REQUEST asks for something, COMMAND orders it. Neither is hostile by default; use `aggression` for tone.
- WARNING alerts about danger and is not hostile to the listener ("Behind you, a creeper!"). THREAT is the speaker promising harm to someone.
- ACCUSE blames someone for a wrong. INSULT includes taunts and mockery. PRAISE includes thanks and compliments.
- SMALLTALK is talk with no ask and no strong feeling: weather, the day, an observation.
- If an utterance does two things, label the primary one and let the numbers carry the rest.

Name pool, the only names you may use, and use them in roughly 40% of lines:
Alvis, Borin, Brokk, Brynja, Dagna, Dvalin, Eitri, Frida, Grimhild, Gudrun, Halvar, Hrolf, Ingrid, Kolbrun, Orm, Ragna, Runa, Sindri, Skadi, Steinar, Torvi, Tova, Vidar, Yngvar.

Distribution rules:

- Every intent between 6% and 12% of the lines.
- Every topic at least 4% of the lines; NONE at most 20%.
- `addressed`: about 55% LISTENER, 20% THIRD, 15% GROUP, 10% NONE.
- No two lines may share the same `text`. Do not reuse a sentence frame more than twice.

Include hard cases, at least one line in six:

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

Output the JSONL lines now.

# Prompt: second pass, sincerity only

For chunks labeled before `sincerity` was added to the schema. Input is a chunk file (`id<TAB>text`); output
is one small JSON object per line. Save the reply to `text/data/sincerity/chunk_NNN.jsonl` and merge with
`python text/merge_sincerity.py`.

---

You are adding one label to lines of dialogue for a game classifier. NPC dwarves in a Minecraft-like
settlement hear these lines from players and from each other. For each input line, decide whether the
words mean what they say.

Input: lines in the form `id<TAB>text`. Output exactly one JSON object per input line, in the same order,
one per line, nothing else: no headings, no code fences, no commentary, no blank lines.

Each object is `{"id": "<the id, copied exactly>", "sincerity": "<one of the three values>"}`.

- `SINCERE`: the line means what it says. This is the default and covers most lines, including angry,
  rude, sad, and profane ones as long as they are meant.
- `SARCASTIC`: the words say one thing and the speaker means another, mocking or bitter. "Nice swing,
  genius." "Oh great, another creeper, just what I needed." "Sure, take my ore, why not."
- `JOKING`: playful, exaggerated or teasing, not literal and not hostile. "I could eat a whole boar."
  "If you die again I'm keeping your diamonds." Banter between friends.

Rules: when unsure, SINCERE. Do not mark a plain insult SARCASTIC just because it is rude; sarcasm needs a
gap between the words and the meaning. Player chat with "lol" or ":P" often signals JOKING. Keep every
id and every line.

Label every line of the chunk now.

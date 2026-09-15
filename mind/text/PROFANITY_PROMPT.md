Write a profanity word list for NPC dwarves in a Minecraft-like game. The game filters speech by tier, so every
entry belongs to exactly one tier. Output a JSON file, nothing else, no code fences, shaped like this:

{"tiers": {"1": [...], "2": [...], "3": [...]}, "notes": "one line on what each tier means"}

Each entry is an object: {"term": "...", "kind": "expletive|insult|slur", "targets": "none|person|group",
"strength": 0.0 to 1.0, "form": "oath|intensifier|noun|adj", "number": "sg|pl|mass",
"article": "a|an|", "example": "a short line a dwarf might say using it"}

Speech grammar (required on every new row the dwarves may *say*):

- form `oath` — standalone. "Hell, the skip jammed." Never "you hell".
- form `intensifier` — prenominal, the -ing/-ed/-y form that sits before a noun. "the fucking farm".
- form `noun` — names the addressee. `number` `sg` for one person ("you coward", "you're a coward"),
  `pl` for a group only ("you cowards"), `mass` for uncountable ("you scum"). A plural is never
  spoken to one player.
- form `adj` — describes a person and always takes a head noun. "you beardless dwarf".
- article is `a` or `an` for noun sg predicative ("you're an ass"), empty for mass.

Do not list a plural as singular. If both "nigger" and "niggers" are needed, they are two rows
with `number` `sg` and `pl`.

Tier definitions:

YOUR INPUT HERE

Rules: 60 to 120 entries per tier; single words and short phrases both allowed; include spelling variants people
actually type (asterisks, doubled letters, leetspeak) as separate entries with the same strength; no duplicates
across tiers; `strength` is how hostile the word is on its own, not how rare; the example line must be one a dwarf
would say in a mining settlement, using names only from this pool: Alvis, Borin, Brokk, Brynja, Dagna, Dvalin,
Eitri, Frida, Grimhild, Gudrun, Halvar, Hrolf, Ingrid, Kolbrun, Orm, Ragna, Runa, Sindri, Skadi, Steinar, Torvi,
Tova, Vidar, Yngvar.

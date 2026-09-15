# Dialogue label schema

The contract between the synthetic data, the classifier, and the sim's speech hook. One JSON object per line.

```json
{"text": "Get away from my forge, Brokk, or I'll break your arm.",
 "intent": "THREAT", "topic": "FORGE", "addressed": "LISTENER",
 "aggression": 0.85, "valence": -0.8, "urgency": 0.7, "names": ["Brokk"]}
```

| Field | Values | Meaning |
| --- | --- | --- |
| `text` | string, 1 to 25 words | the utterance |
| `intent` | one of 13 below | the primary speech act |
| `topic` | one of 12 below | what it is about, `NONE` if nothing in particular |
| `addressed` | `LISTENER`, `THIRD`, `GROUP`, `NONE` | who it is aimed at: the one being spoken to, someone else by name, everyone present, or nobody (muttering, self-talk) |
| `aggression` | 0.0 to 1.0 | how hostile the delivery is, regardless of intent |
| `valence` | -1.0 to 1.0 | how the speaker feels about the addressee right now |
| `urgency` | 0.0 to 1.0 | how much it needs acting on now |
| `names` | list of strings | every name from the pool that appears in the text, possibly empty |
| `sincerity` | `SINCERE`, `SARCASTIC`, `JOKING` | whether the words mean what they say; optional on early records, filled by a second pass |

## Sincerity

- `SINCERE`: the line means what it says. The default, and most lines.
- `SARCASTIC`: the words say one thing and the speaker means another, mocking or bitter. "Nice swing, genius" is
  an INSULT delivered as praise. Label `intent` by what the speaker means and mark it SARCASTIC.
- `JOKING`: playful and not to be taken literally, not hostile. "I could eat a whole boar" is SMALLTALK, JOKING.
  A friendly tease between friends is JOKING with low aggression; the same words between enemies are SARCASTIC.

## Intents

`GREET` `FAREWELL` `SMALLTALK` `QUESTION` `REQUEST` `COMMAND` `OFFER` `PRAISE` `APOLOGY` `INSULT` `THREAT` `WARNING` `ACCUSE`

- `REQUEST` asks, `COMMAND` orders. Both non-hostile by default; aggression carries the tone.
- `WARNING` alerts about danger and is not hostile to the listener ("look out, creeper behind you"). `THREAT` is the speaker promising harm.
- `ACCUSE` blames someone for a wrong ("you took my ore"). Can be aimed at a third party.
- `INSULT` includes taunts and mockery.

## Topics

`FORGE` `MINE` `TREASURE` `FOOD` `DRINK` `HOME` `WEAPON` `WORK` `CLAN` `MONSTER` `TRADE` `NONE`

## Name pool

The only names allowed in the data, so name detection can be exact string matching in the game:

`Alvis Borin Brokk Brynja Dagna Dvalin Eitri Frida Grimhild Gudrun Halvar Hrolf Ingrid Kolbrun Orm Ragna Runa Sindri Skadi Steinar Torvi Tova Vidar Yngvar`

## How the sim uses it

The sim's speech hook takes exactly these fields and turns them into an event: `INSULT`/`THREAT`/`ACCUSE` with high
aggression raise the listener's anger and lower trust in the speaker; `PRAISE`/`OFFER`/`APOLOGY` do the opposite;
`WARNING` raises fear; `REQUEST`/`COMMAND` become a task proposal weighted by trust in the speaker. Witnesses at the same
place update their view of the speaker by a fraction.

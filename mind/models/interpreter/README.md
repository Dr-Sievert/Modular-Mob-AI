# interpreter: the dialogue classifier, frozen

One line of chat in, one label set out. Schema id `dialogue-clf-v2`, layout sha256
`09a397c9f8e809b303b9a1c94bf4ea32d42456273347374bd37a5d40017fbc1e` -- the sha256 of `layout.json`, which is
what a port checks itself against.

Files: `weights.npz` (65536 x 32 embedding and two small layers, float32),
`model.json` (the same facts as data, plus the training metrics), `layout.json` (the contract whose
hash is the schema id), `parity.jsonl` (200 lines with their exact outputs), this file.

Everything below is the whole model. A Java port needs nothing else, and needs no Python.

## 1. Tokenizer

In order, over the raw line:

1. **Quote folding.** `U+2018` and `U+2019` become ASCII `'`; `U+201C` and `U+201D` become ASCII
   `"`. Nothing else is normalised: no NFC, no accent stripping.
2. **Split on separators.** A separator is any of these 40 characters -- the ASCII
   punctuation block *without* the apostrophe, plus en dash, em dash and ellipsis:

   ```
    !"#$%&()*+,-./:;<=>?@[\]^_`{|}~–—…
   ```

   (the first of those is a space), and the whitespace controls U+0009, U+000A, U+000B, U+000C, U+000D. Every other character,
   digits and non-ASCII letters included, belongs to the token. Runs of separators produce no empty
   tokens.
3. **Lowercase, ASCII only.** `A`-`Z` map to `a`-`z` (`ch + 32`); every other character is left
   exactly as it is. Do **not** use `String.toLowerCase()`: it is locale-dependent (Turkish `I`)
   and folds non-ASCII, and either one changes the hash.
4. **Strip apostrophes from both ends of a token.** `'hello'` -> `hello`, `don't` stays `don't`. A
   token that becomes empty is dropped.

Iterate over Unicode code points, not UTF-16 chars, wherever a character is compared.

## 2. Feature strings

From the token list, in this order (the bag is a sum, so the order is documentation, but the list
itself is not):

| kind | string |
| --- | --- |
| word unigram | `"w:" + token` |
| word bigram | `"b:" + token[i] + " " + token[i+1]` |
| char trigram | `"c:" + t` for every 3-character window of `"<" + token + ">"` |
| first word | `"^first=" + tokens[0]` |
| last word | `"$last=" + tokens[-1]` |

A one-character token yields exactly one trigram (`<a>`). A one-token line emits both position
features over the same token. **A line with no tokens at all** (the empty string, `"?!..."`) emits
no feature strings and instead uses the single bucket `40389`, which is
`fnv1a_32("") & 0xffff`; the bag is never empty.

## 3. Hash

FNV-1a, 32 bit, over the **UTF-8 bytes** of the feature string:

```java
int h = 0x811c9dc5;
for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
    h ^= (b & 0xff);
    h *= 0x01000193;          // int overflow is the & 0xffffffff
}
int bucket = h & (65536 - 1);
```

Known vectors: `fnv1a_32("")` = `0x811c9dc5`, `fnv1a_32("a")` = `0xe40c292c`,
`fnv1a_32("foobar")` = `0xbf9cf968`. `65536` is a power of two, so the mask and a modulo
are the same thing. Buckets repeat, and a repeat is counted every time: the bag **sums** rows, it
does not average or deduplicate.

## 4. Side vector

20 floats, computed from the **raw** line before any lowercasing or quote folding, in
this frozen order:

| index | column |
| --- | --- |
| 0 | `log1p_word_count` |
| 1 | `log1p_char_count` |
| 2 | `caps_share` |
| 3 | `exclamation_count` |
| 4 | `question_count` |
| 5 | `has_ellipsis` |
| 6 | `prev_intent=GREET` |
| 7 | `prev_intent=FAREWELL` |
| 8 | `prev_intent=SMALLTALK` |
| 9 | `prev_intent=QUESTION` |
| 10 | `prev_intent=REQUEST` |
| 11 | `prev_intent=COMMAND` |
| 12 | `prev_intent=OFFER` |
| 13 | `prev_intent=PRAISE` |
| 14 | `prev_intent=APOLOGY` |
| 15 | `prev_intent=INSULT` |
| 16 | `prev_intent=THREAT` |
| 17 | `prev_intent=WARNING` |
| 18 | `prev_intent=ACCUSE` |
| 19 | `prev_intent=unknown` |

* `log1p_word_count` = `ln(1 + number of tokens)`, the same tokens as the bag;
* `log1p_char_count` = `ln(1 + number of characters in the raw line)`, code points;
* `caps_share` = ASCII `A`-`Z` count / ASCII letter count, `0.0` when the line has no ASCII letter;
* `exclamation_count`, `question_count` = raw counts, not capped;
* `has_ellipsis` = `1.0` when two `.` are adjacent anywhere (`..`, `...`), else `0.0`;
* `prev_intent=*` = a 14-way one-hot at offset 6: the 13 intents in
  label order, then `unknown`. Anything the caller does not supply, or does not spell exactly like
  an intent, is `unknown`.

`prev_intent` is plumbing, not signal: every training row carries `unknown`, so the other thirteen
columns have never had a gradient. Wire it, do not read anything into what it does today.

## 5. Forward pass

`weights.npz` holds these arrays and nothing else, in this order (this is the order a flat
`.mbw`-style segment layout should follow):

| # | array | shape | role |
| --- | --- | --- | --- |
| 0 | `emb.weight` | 65536x32 | embedding bag rows, (buckets, dim), summed over a text's buckets |
| 1 | `fc1.weight` | 64x52 | hidden layer, (hidden, dim + side): the first `dim` columns take the embedding sum, the rest take the side vector |
| 2 | `fc1.bias` | 64 | hidden layer bias, (hidden,) |
| 3 | `head_intent.weight` | 13x64 | intent logits, (13, hidden) |
| 4 | `head_intent.bias` | 13 | (13,) |
| 5 | `head_topic.weight` | 12x64 | topic logits, (12, hidden) |
| 6 | `head_topic.bias` | 12 | (12,) |
| 7 | `head_addressed.weight` | 4x64 | addressed logits, (4, hidden) |
| 8 | `head_addressed.bias` | 4 | (4,) |
| 9 | `head_sincerity.weight` | 3x64 | sincerity logits, (3, hidden) |
| 10 | `head_sincerity.bias` | 3 | (3,) |
| 11 | `head_floats.weight` | 3x64 | aggression/valence/urgency, (3, hidden) |
| 12 | `head_floats.bias` | 3 | (3,) |

A Linear is stored the way torch stores it, `weight` is `(out, in)` row major, so one output's
weights are contiguous and `y = x @ weight.T + bias`.

```
bag  = sum over the line's buckets of emb.weight[bucket]      # 32 floats
h    = max(bag, 0)                                            # ReLU
x    = concat(h, side)                                        # 52 floats
h    = max(x @ fc1.weight.T + fc1.bias, 0)                    # 64 floats
intent    = h @ head_intent.weight.T    + head_intent.bias    # 13 logits
topic     = h @ head_topic.weight.T     + head_topic.bias     # 12 logits
addressed = h @ head_addressed.weight.T + head_addressed.bias # 4 logits
sincerity = h @ head_sincerity.weight.T + head_sincerity.bias # 3 logits
raw       = h @ head_floats.weight.T    + head_floats.bias    # 3
floats    = [sigmoid(raw[0]), tanh(raw[1]), sigmoid(raw[2])]  # aggression, valence, urgency
```

Each enum label is `argmax` of its logits; a probability, where one is wanted, is `softmax` of them.
`names` are **not predicted**: they are exact matches of the name pool (`model.json`,
`labels.names`) in the raw text. `sincerity` is optional and was masked when absent while training.

Arithmetic is float32 throughout; accumulating the bag in float32 is what the reference does and
what the tolerance below assumes.

## 6. parity.jsonl

200 lines, one JSON object each, the frozen answer sheet: 91 corpus, 8 edge, 61 generated, 40 hardcase. A port passes when
every field matches to **1e-5** absolute (`buckets` matches exactly).

| field | what |
| --- | --- |
| `i` | the record index, 0..199; `prev_intent` is non-null where `i % 20 == 19` |
| `source` | where the line came from: `edge:*`, `hardcase`, `generated`, `corpus` |
| `text` | the raw input line, exactly as it goes in |
| `prev_intent` | the previous line's intent, or `null` for `unknown` |
| `buckets` | the hashed bucket list, in feature order, repeats kept |
| `side` | the 20 side floats |
| `logits.*` | the four heads' **pre-softmax** logits |
| `floats` | the three floats **after** their activations |

The scalar counts in `side` are exact integers as floats, so a mismatch there is a tokenizer bug and
not rounding. Check `buckets` first: everything else is downstream of it.

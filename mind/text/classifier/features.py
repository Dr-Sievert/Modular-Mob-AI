"""Tokenizer, feature strings, FNV-1a hashing and the dense side vector.

Everything in this file is written to be ported to Java bit-for-bit: no regexes,
no locale-dependent case mapping, no library hashing. The Java port is a direct
transcription of `tokenize`, `feature_strings`, `fnv1a_32` and `side_features`.

Tokenizer rules (in order)
--------------------------
1. Quote folding. `U+2018`/`U+2019` become ASCII `'`; `U+201C`/`U+201D` become
   ASCII `"`. Nothing else is normalised (no NFC, no accent stripping).
2. Separators split the text. A separator is any character in `SEPARATORS`:
   ASCII whitespace plus the ASCII punctuation block *except* the apostrophe,
   plus the en dash, em dash and ellipsis. Every other character, including
   digits and non-ASCII letters, is part of a token.
3. Lowercasing is ASCII-only: `A`-`Z` map to `a`-`z` (+32), every other
   character is left exactly as it is. This keeps the mapping a three-line loop
   in Java and avoids Unicode case-folding differences between the languages.
4. Leading and trailing apostrophes are stripped from each token (`'hello'` ->
   `hello`, `don't` stays `don't`). Tokens that become empty are dropped.

Feature strings (all hashed into the same 2^16 buckets, prefixed so the five
kinds cannot collide by accident)
---------------------------------
* word unigram    `"w:" + token`
* word bigram     `"b:" + token[i] + " " + token[i+1]`
* char trigram    `"c:" + t` for every 3-character window of `"<" + token + ">"`
* first word      `"^first=" + tokens[0]`
* last word       `"$last=" + tokens[-1]`

The two position features are emitted last, after the trigrams. A one-token line
emits both of them over the same token, which is correct: "stop" is both the
opening and the closing word. A short token still yields at least one trigram
(`a` -> `<a>`). A text with no tokens at all yields the single bucket
`fnv1a_32("") & 0xFFFF`, so a bag is never empty (torch's EmbeddingBag dislikes
empty bags and the Java side would have to special-case it too).

Hash
----
FNV-1a, 32-bit, over the UTF-8 bytes of the feature string, folded into
`BUCKETS = 2**16` with a mask (BUCKETS is a power of two, so `& (BUCKETS - 1)`
and `% BUCKETS` are the same thing).

Side vector
-----------
`side_features(raw_text, prev_intent)` is a dense float vector of length
`SIDE_DIM`, concatenated onto the (post-ReLU) 32-float embedding sum before the
first MLP layer. The order is `SIDE_FIELDS` and it is frozen: index 0 is the
first column of `fc1.weight`'s embedding-tail, so a Java port that shuffles it
silently gets a different model. Every entry is computed from the *raw* line,
before any lowercasing.

    0   log1p(word count)        `len(tokenize(text))`, the same tokens the bag uses
    1   log1p(character count)   `len(text)`, raw Python/Java code points
    2   caps share               ASCII capitals / ASCII letters, 0.0 when no letters
    3   count of '!'             raw count, not capped
    4   count of '?'             raw count, not capped
    5   has ellipsis             1.0 if two or more '.' are adjacent anywhere
    6.. 14-way one-hot for the previous line's intent: the 13 SCHEMA.md intents
        in `labels.INTENTS` order, then `unknown` at the last index. Anything the
        caller does not supply, or does not spell exactly like an intent, is
        `unknown`. Today's corpus has no `prev_intent` field at all, so every
        training row is `unknown`; the column exists so the game can fill it.
"""

from __future__ import annotations

import math

from .labels import INTENTS

# ---------------------------------------------------------------------------
# hash
# ---------------------------------------------------------------------------

FNV_OFFSET_BASIS = 0x811C9DC5
FNV_PRIME = 0x01000193
MASK32 = 0xFFFFFFFF
BUCKETS = 1 << 16          # 65536


def fnv1a_32(s: str) -> int:
    """FNV-1a 32-bit over the UTF-8 bytes of `s`. Java: same loop on a byte[]."""
    h = FNV_OFFSET_BASIS
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * FNV_PRIME) & MASK32
    return h


def bucket(s: str) -> int:
    """Feature string -> bucket index in [0, BUCKETS)."""
    return fnv1a_32(s) & (BUCKETS - 1)


# ---------------------------------------------------------------------------
# tokenizer
# ---------------------------------------------------------------------------

_ASCII_PUNCT_MINUS_APOSTROPHE = "!\"#$%&()*+,-./:;<=>?@[\\]^_`{|}~"
_ASCII_WHITESPACE = " \t\n\r\f\v"
_EXTRA_SEPARATORS = "–—…"        # en dash, em dash, ellipsis

SEPARATORS = frozenset(_ASCII_WHITESPACE + _ASCII_PUNCT_MINUS_APOSTROPHE + _EXTRA_SEPARATORS)

QUOTE_FOLD = {
    "‘": "'", "’": "'",                # curly single quotes
    "“": '"', "”": '"',                # curly double quotes
}

APOSTROPHE = "'"
PAD_LEFT = "<"
PAD_RIGHT = ">"

PREFIX_UNIGRAM = "w:"
PREFIX_BIGRAM = "b:"
PREFIX_TRIGRAM = "c:"
PREFIX_FIRST = "^first="
PREFIX_LAST = "$last="
EMPTY_BUCKET = fnv1a_32("") & (BUCKETS - 1)      # 0x811c9dc5 & 0xffff = 40389


def _lower_ascii(ch: str) -> str:
    return chr(ord(ch) + 32) if "A" <= ch <= "Z" else ch


def tokenize(text: str) -> list[str]:
    """Text -> list of tokens, following the four rules in the module docstring."""
    tokens: list[str] = []
    current: list[str] = []
    for raw in text:
        ch = QUOTE_FOLD.get(raw, raw)
        if ch in SEPARATORS:
            if current:
                tok = _finish("".join(current))
                if tok:
                    tokens.append(tok)
                current = []
        else:
            current.append(_lower_ascii(ch))
    if current:
        tok = _finish("".join(current))
        if tok:
            tokens.append(tok)
    return tokens


def _finish(tok: str) -> str:
    """Strip leading/trailing apostrophes; may return the empty string."""
    start, end = 0, len(tok)
    while start < end and tok[start] == APOSTROPHE:
        start += 1
    while end > start and tok[end - 1] == APOSTROPHE:
        end -= 1
    return tok[start:end]


def char_trigrams(token: str) -> list[str]:
    """Character trigrams of `<token>`; a 1-char token yields exactly `<t>`."""
    padded = PAD_LEFT + token + PAD_RIGHT
    return [padded[i:i + 3] for i in range(len(padded) - 2)]


def feature_strings(text: str) -> list[str]:
    """Feature strings, fixed order: unigrams, bigrams, trigrams, first word, last word."""
    tokens = tokenize(text)
    if not tokens:
        return []
    feats = [PREFIX_UNIGRAM + t for t in tokens]
    for i in range(len(tokens) - 1):
        feats.append(PREFIX_BIGRAM + tokens[i] + " " + tokens[i + 1])
    for t in tokens:
        for tri in char_trigrams(t):
            feats.append(PREFIX_TRIGRAM + tri)
    feats.append(PREFIX_FIRST + tokens[0])
    feats.append(PREFIX_LAST + tokens[-1])
    return feats


def buckets(text: str) -> list[int]:
    """Text -> the list of hashed bucket indices (with repeats; the bag sums them)."""
    feats = feature_strings(text)
    if not feats:
        return [EMPTY_BUCKET]
    return [fnv1a_32(f) & (BUCKETS - 1) for f in feats]


def tokenizer_rules() -> dict:
    """The tokenizer as data, for model.json and for the Java port to check against."""
    return {
        "lowercase": "ASCII only: A-Z -> a-z (+32); all other characters unchanged",
        "quote_fold": {k: v for k, v in QUOTE_FOLD.items()},
        "separators": "".join(sorted(SEPARATORS)),
        "apostrophe": "kept inside tokens, stripped from both ends of a token",
        "features": [
            "word unigram: 'w:' + token",
            "word bigram: 'b:' + token[i] + ' ' + token[i+1]",
            "char trigram: 'c:' + each 3-window of '<' + token + '>'",
            "first word: '^first=' + tokens[0]",
            "last word: '$last=' + tokens[-1]",
        ],
        "feature_order": "unigrams, bigrams, trigrams, first word, last word "
                         "(the bag is a sum, so the order is documentation only)",
        "empty_text_bucket": EMPTY_BUCKET,
    }


# ---------------------------------------------------------------------------
# side vector (dense, concatenated onto the embedding sum)
# ---------------------------------------------------------------------------

UNKNOWN_PREV_INTENT = "unknown"
#: The 14-way one-hot: the 13 schema intents in order, then `unknown`.
PREV_INTENTS = list(INTENTS) + [UNKNOWN_PREV_INTENT]

#: Frozen order of the dense side vector. Index i here is column i of the side
#: block, which sits immediately after the `dim` embedding columns in fc1's input.
SIDE_FIELDS = [
    "log1p_word_count",
    "log1p_char_count",
    "caps_share",
    "exclamation_count",
    "question_count",
    "has_ellipsis",
] + ["prev_intent=" + name for name in PREV_INTENTS]

SIDE_DIM = len(SIDE_FIELDS)          # 6 + 14 = 20
SIDE_SCALAR_DIM = SIDE_DIM - len(PREV_INTENTS)


def side_features(text: str, prev_intent: str | None = None) -> list[float]:
    """The dense side vector of one raw line; length `SIDE_DIM`, order `SIDE_FIELDS`.

    `text` is the raw line: capitals, punctuation and all. Java: one pass over
    the characters plus the tokenizer's word count, no regex.
    """
    n_words = len(tokenize(text))
    letters = capitals = bangs = questions = 0
    ellipsis = False
    prev_dot = False
    for ch in text:
        if "A" <= ch <= "Z":
            letters += 1
            capitals += 1
        elif "a" <= ch <= "z":
            letters += 1
        if ch == "!":
            bangs += 1
        elif ch == "?":
            questions += 1
        if ch == ".":
            if prev_dot:
                ellipsis = True
            prev_dot = True
        else:
            prev_dot = False

    vec = [0.0] * SIDE_DIM
    vec[0] = math.log1p(n_words)
    vec[1] = math.log1p(len(text))
    vec[2] = (capitals / letters) if letters else 0.0
    vec[3] = float(bangs)
    vec[4] = float(questions)
    vec[5] = 1.0 if ellipsis else 0.0
    name = prev_intent if prev_intent in INTENTS else UNKNOWN_PREV_INTENT
    vec[SIDE_SCALAR_DIM + PREV_INTENTS.index(name)] = 1.0
    return vec


def side_feature_rules() -> dict:
    """The side vector as data, for model.json and for the Java port."""
    return {
        "dim": SIDE_DIM,
        "order": list(SIDE_FIELDS),
        "placement": "concatenated onto the end of relu(embedding sum), so fc1's "
                     "input is [dim embedding columns, then these SIDE_DIM columns]",
        "computed_from": "the raw line, before any lowercasing or quote folding",
        "fields": {
            "log1p_word_count": "ln(1 + number of tokens from tokenize(text))",
            "log1p_char_count": "ln(1 + number of characters in the raw line)",
            "caps_share": "ASCII A-Z count / ASCII letter count; 0.0 when the line "
                          "has no ASCII letters",
            "exclamation_count": "raw count of '!' characters, not capped",
            "question_count": "raw count of '?' characters, not capped",
            "has_ellipsis": "1.0 when two or more '.' characters are adjacent "
                            "anywhere in the raw line ('..' or '...'), else 0.0",
            "prev_intent": "14-way one-hot at offset %d: the 13 intents in label "
                           "order, then 'unknown'. A caller that supplies nothing, "
                           "or a string that is not exactly one of the 13 intents, "
                           "gets 'unknown'." % SIDE_SCALAR_DIM,
        },
        "prev_intent_labels": list(PREV_INTENTS),
        "prev_intent_offset": SIDE_SCALAR_DIM,
        "prev_intent_in_training_data": "always 'unknown': no corpus row carries a "
                                        "prev_intent field yet, so the other 13 "
                                        "columns of fc1 have never had a gradient "
                                        "and supplying one shifts the output by an "
                                        "untrained amount",
    }

"""Swearing: one lexicon, two jobs that must not be confused.

**Recognition.** ``text/profanity.json`` is the user's file: three tiers of terms, each entry
``{term, kind, targets, strength, example}``. Tier 1 is mild undirected swearing, tier 2 crude
expletives and personal insults, tier 3 group-targeted slurs. :meth:`Lexicon.scan` finds those
terms in anything said to a dwarf -- whole words and whole phrases, case-insensitively, with the
spellings exactly as they are listed and with an ``*`` in a term treated as a one-character
wildcard, so ``f*ck`` finds ``f*ck`` and ``fuck`` and ``fvck`` alike. What comes back is what
:func:`dwarfsim.speech.screen` turns into aggression, and what a tier 3 hit turns into a ``SLUR``
event. **Every tier of that file is read.**

**Speech.** What a dwarf may say is a different list per tier:

* tiers 1 and 2 are the recognition file, which is where the settlement's own mild oaths and
  crude insults live;
* tier 3 is ``text/profanity_speech.json`` (``--profanity-speech``) if that file is there, used
  as written -- including terms the recognition file already knows. If the file is missing,
  empty or unreadable, :data:`IN_WORLD_SLURS` stands instead.

:attr:`Lexicon.max_tier` (0 to 3) is the setting the world and the talk app carry: 0 never swears,
1 mild expletives, 2 crude insults, 3 the speech-file slurs (or the in-world list). It caps
speech only; recognition always reads all three tiers, because a dwarf has to understand a
word to be insulted by it.

A spoken term carries ``form``, ``number`` and ``article``. :func:`fill` weaves it into the
line from a closed set of insertions (vocative, prenominal, fronted oath). A ``number`` of
``pl`` is never used when the line is aimed at one person.
"""

import json
import os
import re
from collections import namedtuple

from .schema import NAME_POOL

#: The subproject root (this package's parent), so the defaults below do not depend on the
#: working directory: the subproject sits inside a larger repository and is run from anywhere.
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: The user's recognition file, under the subproject root.
DEFAULT_PATH = os.path.join(_ROOT, "text", "profanity.json")

#: User file of tier 3 terms the dwarves may *say*. If it is missing, the in-world list is used.
DEFAULT_SPEECH_PATH = os.path.join(_ROOT, "text", "profanity_speech.json")

#: What ``--profanity`` defaults to: mild expletives, no insults, no slurs.
DEFAULT_MAX_TIER = 1

TIERS = (1, 2, 3)

#: One term. ``form``, ``number`` and ``article`` are the speech-grammar fields -- see
#: the Vocabulary contract above :func:`infer_grammar`. Recognition still only needs
#: ``term kind targets strength tier``; the last three default so old rows keep loading.
Term = namedtuple("Term", "term kind targets strength tier form number article")
Term.__new__.__defaults__ = ("oath", "sg", "")


# ---------------------------------------------------------------------------
# Vocabulary contract -- set these on every new speech entry
# ---------------------------------------------------------------------------
#
# Recognition still only needs ``term``, ``kind``, ``targets``, ``strength``.
# Speech additionally needs the three fields below. Put them on the JSON row.
# If they are missing, :func:`infer_grammar` fills them from the spelling; that
# is a fallback, not a licence to omit them on new words.
#
#   form      oath | intensifier | noun | adj
#   number    sg | pl | mass          (noun / adj only; oath and intensifier ignore it)
#   article   a | an | ""             (noun sg only)
#
# What each form is allowed to do in a sentence -- the weaver will not do
# anything else, so a word in the wrong form cannot produce a broken line:
#
#   oath          Standalone interjection. Never inflects. Never "you hell".
#                 Goes in front of the whole line: "Hell, the skip jammed."
#                 Required spelling: the word as shouted on its own.
#
#   intensifier   Prenominal. Goes after a determiner, before a noun:
#                 "the fucking farm". Required spelling: the form that sits
#                 in front of a noun (fucking, bloody, blasted) -- not fuck,
#                 not bloodied. If the line has no determiner it is used as
#                 a clause adverb: "Fucking get away from my forge."
#
#   noun          Names the addressee. Required spelling: the citation form.
#                 sg   one person.  Vocative "you coward". Predicative
#                      "you're a coward" / "you're an ass" (see article).
#                 pl   two or more. Vocative "you cowards". Never spoken
#                      when the line is aimed at one player or one dwarf.
#                 mass uncountable. Vocative "you scum". Predicative
#                      "you're scum" -- no a/an.
#
#   adj           Describes a person. Required spelling: the adjective
#                 (beardless, worthless). Always takes a person-noun head
#                 so it cannot stand as "you beardless.": "you beardless dwarf".
#
# number is why "niggers" is never said to one player: a pl noun is dropped
# from the pool unless ``addressed == "GROUP"``. Do not list a plural as sg.
# Do not list a singular as pl and hope the weaver will strip the s -- it will
# not. Add both rows if both are needed.
#
# article is only for predicative noun sg. "a" before a consonant sound, "an"
# before a vowel sound, "" for mass. The weaver will not invent one.

FORMS = ("oath", "intensifier", "noun", "adj")
NUMBERS = ("sg", "pl", "mass")
ARTICLES = ("a", "an", "")

#: Count nouns that end in s (or look like a regular plural) but name one person
#: or are an oath, not a group. "ass" is one insult; "niggers" is not on this list.
_NOT_PLURAL = frozenset((
    "ass", "dumbass", "jackass", "smartass", "hardass", "badass",
    "piss", "beardless", "worthless", "pebblehands", "longshanks",
    "hells", "blazes", "cripes", "crumbs",
))

#: Uncountable insults. Vocative "you scum"; never "you're a scum".
_MASS = frozenset(("scum", "filth", "slag", "muck", "dirt", "shit"))

#: Adjectives that do not end in a regular adj suffix.
_ADJ = frozenset(("worthless", "beardless", "bloody"))

#: Intensifier endings: the spoken prenominal form, not the root.
_INTENSIFIER_ENDINGS = ("ing", "in", "ed", "y")


def infer_grammar(term, kind, targets=None, form=None, number=None, article=None):
    """``(form, number, article)`` for one entry. JSON wins; the rest is inferred.

    New vocabulary should set the fields. This function is what keeps an old
    row, or a row somebody forgot to tag, from being woven as the wrong part
    of speech -- and what keeps a plural from being treated as one person.
    """
    word = (term or "").strip().lower()
    kind = (kind or "expletive").strip().lower()
    form = _clean_form(form)
    number = _clean_number(number)
    if article is not None:
        article = str(article).strip().lower()
        if article not in ARTICLES:
            article = None

    if form is None:
        if kind in ("insult", "slur"):
            form = "adj" if _looks_adj(word) else "noun"
        elif _looks_intensifier(word):
            form = "intensifier"
        else:
            form = "oath"

    if number is None:
        if form in ("noun", "adj") and _looks_plural(word):
            number = "pl"
        elif form == "noun" and _last_word(word) in _MASS:
            number = "mass"
        else:
            number = "sg"

    if article is None:
        article = _article_of(word, form, number)
    return form, number, article


def _clean_form(value):
    if value is None or value == "":
        return None
    value = str(value).strip().lower()
    return value if value in FORMS else None


def _clean_number(value):
    if value is None or value == "":
        return None
    value = str(value).strip().lower()
    if value in ("singular", "one"):
        return "sg"
    if value in ("plural", "many", "group"):
        return "pl"
    return value if value in NUMBERS else None


def _last_word(term):
    return term.rsplit(" ", 1)[-1]


def _looks_adj(word):
    tail = _last_word(word)
    if tail in _ADJ:
        return True
    return tail.endswith(("less", "ish", "ous", "ful")) and len(tail) > 4


def _looks_intensifier(word):
    """Prenominal form: *fucking*, *bloody*, *blasted*. Not *fuck*, not *blood*."""
    tail = _last_word(word)
    return len(tail) > 3 and tail.endswith(_INTENSIFIER_ENDINGS)


def _looks_plural(word):
    """Regular English plural, or a listed group word. Not *ass*, not *jackass*."""
    if not word or word in _NOT_PLURAL or _last_word(word) in _NOT_PLURAL:
        return False
    if " " in word:
        return word.endswith("s") and not word.endswith("ss")
    if word.endswith("ies") and len(word) > 4:
        return True
    if word.endswith("s") and not word.endswith(("ss", "'s")):
        return True
    return False


def _article_of(word, form, number):
    if form != "noun" or number != "sg":
        return ""
    if _last_word(word) in _MASS:
        return ""
    head = word.lstrip("'\"").lstrip()
    return "an" if head[:1] in "aeiou" else "a"


def directed_ok(term, addressed="LISTENER"):
    """Can this term be aimed at whoever ``addressed`` names?

    A plural noun or adjective is a group word. It is not spoken to one player
    or one dwarf. ``GROUP`` is the only address that may use it.
    """
    if getattr(term, "form", "oath") not in ("noun", "adj"):
        return True
    if getattr(term, "number", "sg") != "pl":
        return True
    return addressed == "GROUP"


# ---------------------------------------------------------------------------
# What a dwarf may say at tier 3
# ---------------------------------------------------------------------------
#
# Fallback only: used when there is no speech file. ``targets`` names the group.

IN_WORLD_SLURS = (
    # -- elves ----------------------------------------------------------------
    ("sap-drinker", "elves", 0.60),
    ("bark-chewer", "elves", 0.58),
    ("thistle-blood", "elves", 0.62),
    ("willow-spine", "elves", 0.55),
    ("arrow-thief", "elves", 0.58),
    ("moonwhelp", "elves", 0.60),
    ("bough-skulker", "elves", 0.56),
    ("lute-blood", "elves", 0.54),
    ("glade-lurker", "elves", 0.55),
    ("petal-eater", "elves", 0.52),

    # -- goblins and what comes up out of the dark ----------------------------
    ("snout-crawler", "goblins", 0.66),
    ("bile-spawn", "goblins", 0.68),
    ("nail-gnawer", "goblins", 0.62),
    ("gutter-whelp", "goblins", 0.60),
    ("scrap-eater", "goblins", 0.58),
    ("fang-runt", "goblins", 0.60),
    ("muck-litter", "goblins", 0.62),
    ("bone-picker", "goblins", 0.60),
    ("warren-filth", "goblins", 0.66),
    ("crook-fang", "goblins", 0.58),

    # -- surface-dwellers -----------------------------------------------------
    ("sky-gawper", "surface", 0.52),
    ("sun-squinter", "surface", 0.55),
    ("rain-drinker", "surface", 0.50),
    ("roof-hider", "surface", 0.48),
    ("daylight-loafer", "surface", 0.52),
    ("topsoil-lout", "surface", 0.55),
    ("straw-hutter", "surface", 0.50),
    ("grass-treader", "surface", 0.50),

    # -- outsiders, whoever they are ------------------------------------------
    ("roadborn", "outsiders", 0.50),
    ("gate-beggar", "outsiders", 0.55),
    ("hall-crasher", "outsiders", 0.52),
    ("dust-drifter", "outsiders", 0.48),
    ("hold-jumper", "outsiders", 0.54),

    # -- the clanless ---------------------------------------------------------
    ("clan-cast", "clanless", 0.66),
    ("kinless whelp", "clanless", 0.64),
    ("nameless-blood", "clanless", 0.62),
    ("hearth-beggar", "clanless", 0.58),
    ("unclanned pup", "clanless", 0.56),

    # -- the beardless --------------------------------------------------------
    ("stubble-chin", "beardless", 0.48),
    ("bald-jaw", "beardless", 0.50),
    ("wispbeard", "beardless", 0.46),
    ("milk-braid", "beardless", 0.48),
    ("shorn-chin", "beardless", 0.50),
)


def in_world_terms():
    """:data:`IN_WORLD_SLURS` as :class:`Term` rows. Every one is a singular noun."""
    out = []
    for t, who, s in IN_WORLD_SLURS:
        form, number, article = infer_grammar(t, "slur", who)
        out.append(Term(t, "slur", who, s, 3, form, number, article))
    return out


# ---------------------------------------------------------------------------
# The lexicon
# ---------------------------------------------------------------------------

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

#: Loaded files, by absolute path and modification time. A Lexicon is cheap to build once the
#: regex is compiled, and every world builds one.
_CACHE = {}


def resolve(path):
    """A relative path is relative to the repo root as well as to the working directory."""
    if not path:
        return path
    if os.path.isabs(path) or os.path.exists(path):
        return path
    here = os.path.join(_ROOT, path)
    return here if os.path.exists(here) else path


def _rows(data, tier=None):
    """Entries out of whatever shape the JSON is in. Anything malformed is skipped."""
    out = []
    if isinstance(data, dict):
        tiers = data.get("tiers")
        if isinstance(tiers, dict):
            for key, entries in tiers.items():
                try:
                    n = int(key)
                except (TypeError, ValueError):
                    continue
                if tier is None or n == tier:
                    out.extend(_entries(entries, n))
            return out
        entries = data.get("terms") or data.get("entries")
        if entries is not None:
            return _entries(entries, tier or 3)
        return out
    if isinstance(data, list):
        return _entries(data, tier or 3)
    return out


def _entries(entries, tier):
    out = []
    if not isinstance(entries, (list, tuple)):
        return out
    for row in entries:
        if not isinstance(row, dict):
            continue
        term = str(row.get("term") or "").strip().lower()
        if not term:
            continue
        try:
            strength = float(row.get("strength", 0.5))
        except (TypeError, ValueError):
            strength = 0.5
        strength = 0.0 if strength < 0.0 else (1.0 if strength > 1.0 else strength)
        kind = str(row.get("kind") or "expletive")
        targets = str(row.get("targets") or "none")
        form, number, article = infer_grammar(
            term, kind, targets,
            form=row.get("form"), number=row.get("number"), article=row.get("article"),
        )
        out.append(Term(term, kind, targets, strength, int(tier), form, number, article))
    return out


def _read(path):
    """``[Term]`` for one file, or ``[]`` if it is missing, unreadable or not what we expect."""
    path = resolve(path)
    try:
        stamp = os.path.getmtime(path)
    except OSError:
        return []
    key = (os.path.abspath(path), stamp)
    hit = _CACHE.get(key)
    if hit is None:
        try:
            with open(path, encoding="utf-8") as fh:
                hit = _rows(json.load(fh))
        except Exception:                      # missing, half-written, not JSON, not our shape
            hit = []
        _CACHE[key] = hit
    return hit


#: What an ``*`` in a term stands for: any one character that is not a space.
ANY = "[^\\s]"

#: ... except when the file already lists the term spelled out in letters. Then the ``*`` entry
#: is there for the censored spellings, and letting it match letters too costs far more than it
#: catches: ``h*ll`` would find *hall*, ``s**t`` would find *salt* and *soot*, ``a**`` would find
#: *ask*. Those are ordinary words in a mine, and a lexicon that cries wolf on them is worse than
#: no lexicon. So a term whose spelled-out form is listed keeps the wildcard for the
#: substitutions -- ``f*ck``, ``f4ck``, ``f#ck`` -- and gives up the letters.
CENSOR = "[^\\sA-Za-z]"


def _body(term, wild):
    return wild.join(re.escape(chunk) for chunk in term.split("*"))


def _pattern(terms):
    """One case-folded regex over every term, longest first, ``*`` as a one-character wildcard.

    The lookarounds are what make this match whole words and whole phrases only: ``ass`` is not
    found inside ``class`` and ``damn`` is not found inside ``damnation``. Longest first is what
    makes a two-word phrase win over the word inside it.
    """
    if not terms:
        return None, {}
    plain = [t.term for t in terms if "*" not in t.term]
    order = sorted(terms, key=lambda t: (-len(t.term), t.term.count("*"), -t.strength, t.term))
    parts, by_group = [], {}
    for i, t in enumerate(order):
        name = "t%d" % i
        wild = ANY
        if "*" in t.term:
            spelled = re.compile("(?:%s)\\Z" % _body(t.term, ANY))
            if any(spelled.match(p) for p in plain):
                wild = CENSOR
        parts.append("(?P<%s>%s)" % (name, _body(t.term, wild)))
        by_group[name] = t
    return re.compile(r"(?<![0-9A-Za-z])(?:%s)(?![0-9A-Za-z])" % "|".join(parts)), by_group


class Lexicon:
    """The terms, what may be said out of them, and how far the settlement will go.

    Build one with :meth:`load`. It is cheap to copy and the regex is shared, so every world
    gets its own with its own :attr:`max_tier`.
    """

    __slots__ = ("tiers", "speech3", "max_tier", "path", "speech_path", "speech_source",
                 "speech_dropped", "_re", "_by_group")

    def __init__(self, tiers=None, speech3=None, max_tier=DEFAULT_MAX_TIER, path=None,
                 speech_path=None, _re=None, _by_group=None):
        self.tiers = {n: list(tiers.get(n, ())) for n in TIERS} if tiers else {n: [] for n in TIERS}
        self.speech3 = list(speech3 if speech3 is not None else in_world_terms())
        self.max_tier = clamp_tier(max_tier)
        self.path = path
        self.speech_path = speech_path
        #: Where tier 3 speech came from, and how many of the speech file's entries were not
        #: usable for it. Reported by the run and shown in the talk app, so a speech file that
        #: is not being used does not look like one that is.
        self.speech_source = "in-world"
        self.speech_dropped = 0
        if _re is None:
            _re, _by_group = _pattern([t for n in TIERS for t in self.tiers[n]])
        self._re, self._by_group = _re, _by_group or {}

    # -- building -----------------------------------------------------------

    @classmethod
    def load(cls, path=DEFAULT_PATH, max_tier=DEFAULT_MAX_TIER, speech_path=DEFAULT_SPEECH_PATH):
        """Read the recognition file, and the speech file if the user keeps one.

        Tolerant of everything: a missing, half-written or unrecognisable file gives an empty
        lexicon rather than an exception, because a retrain rewrites files in place and the sim
        must still start.
        """
        tiers = {n: [] for n in TIERS}
        for t in _read(path):
            if t.tier in tiers:
                tiers[t.tier].append(t)
        lex = cls(tiers, speech3=[], max_tier=max_tier, path=path, speech_path=speech_path)
        lex.speech3 = lex._speech_tier3(speech_path)
        return lex

    def _speech_tier3(self, speech_path):
        """The user's tier 3 speech file if there is one, else the in-world list.

        The file is used as written. Terms that also sit in the recognition file are kept:
        recognition and speech are allowed to share a list.
        """
        rows = _read(speech_path) if speech_path else []
        rows = [t._replace(tier=3, kind=t.kind or "slur") for t in rows if t.tier in (3, None)]
        self.speech_dropped = 0
        if rows:
            self.speech_source = "file"
            return rows
        self.speech_source = "in-world"
        return in_world_terms()

    def copy(self, max_tier=None):
        """The same terms with a different ceiling. The regex is shared, not rebuilt."""
        out = Lexicon.__new__(Lexicon)
        out.tiers = self.tiers
        out.speech3 = self.speech3
        out.max_tier = clamp_tier(self.max_tier if max_tier is None else max_tier)
        out.path, out.speech_path = self.path, self.speech_path
        out.speech_source, out.speech_dropped = self.speech_source, self.speech_dropped
        out._re, out._by_group = self._re, self._by_group
        return out

    # -- recognition --------------------------------------------------------

    def scan_terms(self, text, tiers=TIERS):
        """Every :class:`Term` in ``text``, in the order they appear.

        Whole words and whole phrases, case-insensitively; an ``*`` in a listed term matches any
        one non-space character, so the file's deliberate mis-spellings and the plain spelling
        are both found by the same entry. ``tiers`` narrows which tiers are reported.

        The whole entry comes back, not only the term, because ``kind`` and ``targets`` are what
        say whether the line was aimed at somebody -- see :func:`aimed_at_somebody`.
        """
        if not text or self._re is None:
            return []
        out = []
        for m in self._re.finditer(str(text).lower()):
            t = self._by_group.get(m.lastgroup)
            if t is not None and t.tier in tiers:
                out.append(t)
        return out

    def scan(self, text, tiers=TIERS):
        """``[(term, tier, strength)]`` for every term in ``text``."""
        return [(t.term, t.tier, t.strength) for t in self.scan_terms(text, tiers)]

    # -- speech -------------------------------------------------------------

    def speech_terms(self, tier, kind=None):
        """What a dwarf may say at ``tier``: the recognition file for 1 and 2, speech3 for 3.

        ``kind`` filters on the entry's own kind (``expletive``, ``insult``, ``slur``).
        """
        tier = int(tier or 0)
        if tier <= 0 or tier > 3:
            return []
        rows = list(self.speech3) if tier == 3 else list(self.tiers.get(tier, ()))
        if kind:
            rows = [r for r in rows if r.kind == kind]
        return rows

    def speech_note(self):
        """One line about where tier 3 speech comes from, or ``None`` if there is nothing odd.

        There is something to say exactly when the user keeps a speech file and it is not the
        thing being spoken out of.
        """
        if not self.speech_dropped:
            return None
        return ("tier 3 speech: %d entr%s in %s %s also in the recognition file's tier 3 and "
                "%s not spoken; using %s" % (
                    self.speech_dropped, "y" if self.speech_dropped == 1 else "ies",
                    self.speech_path, "is" if self.speech_dropped == 1 else "are",
                    "it is" if self.speech_dropped == 1 else "they are",
                    "the rest of the file" if self.speech_source == "file"
                    else "the in-world list in dwarfsim/profanity.py"))

    def __repr__(self):
        return "<Lexicon %s tiers=%s speech3=%d (%s) max_tier=%d>" % (
            self.path, [len(self.tiers[n]) for n in TIERS], len(self.speech3),
            self.speech_source, self.max_tier)


def speakable(term):
    """A spelling a dwarf would say, not a leetspeak / censored variant people type."""
    word = term.term if hasattr(term, "term") else str(term)
    if any(ch in word for ch in "*$"):
        return False
    if any(ch.isdigit() for ch in word):
        return False
    return True


def clamp_tier(n):
    try:
        n = int(n)
    except (TypeError, ValueError):
        return DEFAULT_MAX_TIER
    return 0 if n < 0 else (3 if n > 3 else n)


#: Kinds and targets that mean the term was aimed at a person or at a people, rather than
#: sworn at the world. This is the difference between "what the hell happened to the ore bins"
#: and calling somebody something: the first is an oath, the second is an insult whatever the
#: rest of the sentence sounded like.
AIMED_KINDS = ("insult", "slur")
AIMED_TARGETS = ("person", "group")


def aimed_at_somebody(terms):
    """The terms in this line that are aimed at whoever is being spoken to (or at their kind)."""
    return [t for t in terms if t.kind in AIMED_KINDS and t.targets in AIMED_TARGETS]


def rows(terms):
    """``[Term]`` -> the JSON-friendly shape that rides along in ``parsed`` and in the log."""
    return [{"term": t.term, "tier": t.tier, "strength": round(t.strength, 3),
             "kind": t.kind, "targets": t.targets} for t in terms]


def unrows(records):
    """The other way, for a ``parsed`` that already carries what its speaker knows it said."""
    out = []
    for r in records or ():
        try:
            kind = str(r.get("kind") or "expletive")
            targets = str(r.get("targets") or "none")
            term = str(r["term"])
            form, number, article = infer_grammar(
                term, kind, targets,
                form=r.get("form"), number=r.get("number"), article=r.get("article"),
            )
            out.append(Term(term, kind, targets, float(r["strength"]), int(r["tier"]),
                            form, number, article))
        except (KeyError, TypeError, ValueError):
            continue
    return out


# ---------------------------------------------------------------------------
# Whether this dwarf, right now, swears -- and how far
# ---------------------------------------------------------------------------

#: Anger (scaled by temper) above which a dwarf mutters something mild.
ANNOYED = 0.28

#: ... and above which it gets crude about whoever it is angry with.
ANGRY = 0.45

#: Hatred of the target above which it reaches for the worst thing it has.
HATES = 0.45

#: Acts that are aimed at somebody, and so can carry a directed insult rather than an oath.
#: ``COMPLAIN`` and ``GOSSIP`` are deliberately absent: they are about a dwarf who is not in the
#: room, so a term hung off the end of one would land on the wrong person. They get an oath.
#: ``COMMAND`` is absent for a different reason: an order with a name for the one being ordered
#: hung off it reads, to :func:`dwarfsim.speech.screen`, as the insult it is -- and an order that
#: has become an insult is no longer an order, so the obligation it was making disappears. An
#: order gets an oath and keeps its ask.
DIRECTED_ACTS = frozenset((
    "INSULT", "THREAT", "ACCUSE", "RETORT", "DEMAND", "REFUSE_APOLOGY", "REBUKE", "REFUSE",
))

def heat_of(agent):
    """One number for how likely this dwarf is to swear at all: anger, scaled by temper."""
    mind = agent.mind
    return min(1.0, mind.emotions["anger"] * (0.6 + 0.8 * mind.traits["temper"]))


def tier_for(agent, target=None, hatred=None):
    """The worst tier this dwarf would reach for right now, before the world's ceiling.

    0 nothing, 1 a mild oath when annoyed, 2 something crude when it is angry at *this* dwarf,
    3 an in-world slur when it hates them or what they are.
    """
    heat = heat_of(agent)
    rel = agent.mind.rels.get(target.id) if target is not None else None
    if hatred is None:
        hatred = rel["hatred"] if rel else 0.0
    trust = rel["trust"] if rel else 0.0
    if target is not None and hatred >= HATES and heat >= ANNOYED:
        return 3
    if target is not None and heat >= ANGRY and (hatred >= 0.15 or trust < 0.0):
        return 2
    if heat >= ANNOYED:
        return 1
    return 0


class Swearing:
    """One speaker's licence to swear, and the words it may use.

    Built by :func:`for_speaker` and handed to :func:`dwarfsim.speech.utterance`, which fills
    the optional slot in whatever template it drew. It never picks the line -- a swear word is
    a slot in a line, not a line.
    """

    __slots__ = ("lex", "tier", "rng", "chance")

    def __init__(self, lex, tier, rng, chance=0.5):
        self.lex = lex
        self.tier = clamp_tier(tier)
        self.rng = rng
        self.chance = max(0.0, min(1.0, chance))

    def __bool__(self):
        return self.tier > 0 and self.chance > 0.0

    def pick(self, act=None, directed=None, addressed="LISTENER"):
        """The term this line would carry, or ``None``. Draws from the seeded stream.

        ``directed`` says whether the line is aimed at the dwarf it is about, which is what
        decides between a term for a person and an oath about the world; by default the act
        decides it. ``addressed`` is who that person is: a plural noun is refused unless
        it is ``GROUP``.
        """
        if self.tier <= 0 or self.rng.random() >= self.chance:
            return None
        if directed is None:
            directed = act is None or act in DIRECTED_ACTS
        for tier in range(self.tier, 0, -1):
            if tier >= 3:
                pool = self.lex.speech_terms(3) if directed else []
            elif tier == 2:
                pool = self.lex.speech_terms(2, "insult" if directed else "expletive")
            else:
                pool = self.lex.speech_terms(1, "expletive") or self.lex.speech_terms(1)
            clean = [t for t in pool if speakable(t)]
            if directed:
                clean = [t for t in clean if directed_ok(t, addressed)]
            if clean:
                return clean[self.rng.randrange(len(clean))]
        return None

    def apply(self, text, act=None, directed=None, name=None, addressed="LISTENER"):
        """``(line, [Term])``: the line with its slot filled, and what went in it."""
        if not text:
            return text, []
        term = self.pick(act, directed, addressed=addressed)
        if term is None:
            return text, []
        line = fill(text, term, self.rng, name=name, addressed=addressed)
        return (line, [term]) if line else (text, [])


def for_speaker(world, agent, target=None):
    """The :class:`Swearing` for one dwarf about to speak, or ``None`` if it would not.

    Uses the world's own swearing stream, so the words a settlement uses are fixed by ``--seed``
    and adding them does not move any other draw in the run.
    """
    lex = getattr(world, "profanity", None)
    if lex is None or lex.max_tier <= 0 or agent is None:
        return None
    tier = min(lex.max_tier, tier_for(agent, target))
    if tier <= 0:
        return None
    heat = heat_of(agent)
    chance = min(0.95, 0.30 + 0.65 * heat + (0.12 if tier >= 2 else 0.0))
    return Swearing(lex, tier, getattr(world, "swear_rng", world.rng), chance)


# ---------------------------------------------------------------------------
# Weaving the word into the line
# ---------------------------------------------------------------------------
#
# Closed set. Each ``form`` has one or more insertions that are always English.
# ``fill`` picks among the ones that fit this line and never invents a third
# shape -- so a new word cannot break a sentence if its grammar fields are
# right, and a plural cannot be said to one person because :func:`pick`
# will not hand it over.
#
#   oath          "{Oath}, {line}" or "{Oath}. {line}"
#   intensifier   after the first determiner; else clause-adverb in front
#   noun sg/mass  vocative in the name slot, or "You {term}, {line}"
#   noun pl       same, only when addressed is GROUP
#   adj           "you {term} dwarf" in those same slots
#
# The old tack-on (", Brokk, you {term}.") is gone. The insult takes the
# vocative the template already left for the listener's name, or it opens
# the sentence. It is never a second sentence glued on the end.

_END = ".!?"

#: The person-noun an adjective is allowed to modify. Always grammatical
#: in this hold; do not weave an adj without a head.
_ADJ_HEAD = "dwarf"

_DETERMINER = re.compile(r"\b(my|the|that|this|your|our|his|her|their)\s+", re.I)

_NAME_ALT = "|".join(re.escape(n) for n in sorted(NAME_POOL, key=len, reverse=True))
_TRAIL_NAME = re.compile(r",\s+(?P<name>%s)(?P<end>\s*[.!?]+)?\s*$" % _NAME_ALT, re.I)
_LEAD_NAME = re.compile(r"^(?P<name>%s),\s+" % _NAME_ALT, re.I)


def _split_end(line):
    body = line.rstrip()
    end = ""
    while body and body[-1] in _END:
        end = body[-1] + end
        body = body[:-1]
    return body.rstrip(), (end or ".")


def _cap(word):
    return word[:1].upper() + word[1:]


def _lower_first(line):
    """Lower the first word unless it is a name or an ``I``, so a vocative can go in front."""
    if not line:
        return line
    first = line.split(" ", 1)[0].strip(",.!?")
    if first in NAME_POOL or first == "I" or first.startswith("I'"):
        return line
    return line[:1].lower() + line[1:]


def is_intensifier(term):
    """Is this a word that goes in front of a noun rather than on its own?"""
    form = getattr(term, "form", None)
    if form:
        return form == "intensifier"
    return _looks_intensifier(term.term if hasattr(term, "term") else str(term))


def _vocative(term):
    """The phrase that can stand where a name stands: 'you coward', 'you beardless dwarf'."""
    word = term.term
    form = getattr(term, "form", "noun")
    if form == "adj":
        return "you %s %s" % (word, _ADJ_HEAD)
    return "you %s" % word


def _predicative(term):
    """A full clause naming the addressee. Always grammatical for noun sg / mass."""
    word = term.term
    number = getattr(term, "number", "sg")
    article = getattr(term, "article", "") or _article_of(word, "noun", number)
    if number == "mass" or not article:
        return "You're %s." % word
    return "You're %s %s." % (article, word)


def _replace_trail_name(line, name, vocative):
    """', Brokk!' at the end becomes ', you coward!' -- same clause, not a suffix."""
    if name:
        pat = re.compile(r",\s+%s(?P<end>\s*[.!?]+)?\s*$" % re.escape(name), re.I)
        m = pat.search(line)
        if m:
            end = (m.group("end") or ".").lstrip()
            if not end:
                end = "."
            return line[:m.start()] + ", " + vocative + end
    m = _TRAIL_NAME.search(line)
    if not m:
        return None
    end = (m.group("end") or ".").lstrip() or "."
    return line[:m.start()] + ", " + vocative + end


def _replace_lead_name(line, name, vocative):
    """'Brokk, out of my way.' becomes 'You coward, out of my way.'"""
    if name:
        pat = re.compile(r"^%s,\s+" % re.escape(name), re.I)
        m = pat.search(line)
        if m:
            return _cap(vocative) + ", " + line[m.end():]
    m = _LEAD_NAME.search(line)
    if not m:
        return None
    return _cap(vocative) + ", " + line[m.end():]


def _weave_oath(line, word, rng):
    """In front of the line. A trailing oath is not offered -- that was the old tack-on."""
    if rng.randrange(2) == 0:
        return "%s, %s" % (_cap(word), _lower_first(line))
    return "%s. %s" % (_cap(word), line)


def _weave_intensifier(line, word):
    """After the first determiner, else as a clause adverb in front. Both always parse."""
    m = _DETERMINER.search(line)
    if m is not None:
        return line[:m.end()] + word + " " + line[m.end():]
    return "%s %s" % (_cap(word), _lower_first(line))


def _weave_person(line, term, rng, name):
    """Put a noun or adj where the listener is already being addressed."""
    vocative = _vocative(term)
    choices = []
    trail = _replace_trail_name(line, name, vocative)
    if trail:
        choices.append(trail)
    lead = _replace_lead_name(line, name, vocative)
    if lead:
        choices.append(lead)
    choices.append("%s, %s" % (_cap(vocative), _lower_first(line)))
    form = getattr(term, "form", "noun")
    number = getattr(term, "number", "sg")
    if form == "noun" and number in ("sg", "mass"):
        choices.append("%s %s" % (_predicative(term), line))
    return choices[rng.randrange(len(choices))]


def fill(line, term, rng, name=None, addressed="LISTENER"):
    """One line with one term woven in. Always a finished sentence; never ``None``.

    The insertion is chosen from the closed set for ``term.form``. A plural
    aimed at one person is refused and the original line is returned -- the
    caller should not have picked it; this is the last guard.
    """
    if not line or term is None:
        return line
    form = getattr(term, "form", None)
    if form not in FORMS:
        form, _, _ = infer_grammar(term.term, term.kind, term.targets)
    if form in ("noun", "adj") and not directed_ok(term, addressed):
        return line
    word = term.term
    if form == "oath":
        return _weave_oath(line, word, rng)
    if form == "intensifier":
        return _weave_intensifier(line, word)
    return _weave_person(line, term, rng, name)

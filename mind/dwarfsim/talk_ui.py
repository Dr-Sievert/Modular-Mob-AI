"""Drawing :class:`dwarfsim.talk.Session`, with rich if it is installed and ANSI if it is not.

The content is built once, as ``(indent, style, text)`` triples, and painted twice: rich turns
them into two panels side by side, and the fallback turns them into ANSI escapes and a rule.
That is the only reason this file is separate from ``talk.py`` -- so the terminal, which is the
part that cannot be tested, is the only thing in it.

    from dwarfsim.talk import Session
    from dwarfsim.talk_ui import render
    print(render(Session(seed=1)))

:func:`render` always returns a string, with rich or without it, which is what the tests check.
"""

import io
import os
import sys
import textwrap

#: style name -> ANSI SGR parameters, for the no-rich path.
ANSI = {
    "": "0",
    "title": "1;36",
    "dim": "90",
    "you": "1;33",
    "npc": "96",
    "good": "32",
    "bad": "31",
    "warn": "35",
    "term": "34",
    "head": "1;37",
}

#: style name -> rich style.
RICH_STYLE = {
    "": "",
    "title": "bold cyan",
    "dim": "dim",
    "you": "bold yellow",
    "npc": "cyan",
    "good": "green",
    "bad": "red",
    "warn": "magenta",
    "term": "blue",
    "head": "bold white",
}

#: Width used when nobody says otherwise: enough that the left panel does not wrap.
DEFAULT_WIDTH = 110

#: Under this many columns the two panels stack instead of sitting side by side.
SIDE_BY_SIDE = 100

#: The most exchanges the live panel will ever draw, however tall the terminal is. The height
#: budget usually bites first; this is the guard against a very tall window redrawing the whole
#: feed every prompt.
FEED_LIMIT = 40

#: How many spoken lines and narration lines one entry shows before it starts counting.
SPOKEN_CAP = 8
STORY_CAP = 24

BAR_FULL_RICH, BAR_EMPTY_RICH = "█", "·"
BAR_FULL_PLAIN, BAR_EMPTY_PLAIN = "#", "."


def rich_modules():
    """The bits of rich this module uses, or ``None`` if rich is not installed.

    One function, so a test can monkeypatch it and take the fallback path.
    """
    try:
        from rich import box
        from rich.console import Console, Group
        from rich.layout import Layout
        from rich.panel import Panel
        from rich.table import Table
        from rich.text import Text
    except Exception:
        return None
    return {"Console": Console, "Group": Group, "Layout": Layout, "Panel": Panel,
            "Table": Table, "Text": Text, "box": box}


def supports_colour():
    if os.environ.get("NO_COLOR"):
        return False
    return bool(getattr(sys.stdout, "isatty", lambda: False)())


def unicode_ok(stream=None):
    """Whether the block characters survive the console's code page.

    A Windows console is still cp1252 unless somebody asked for UTF-8, and a bar drawn out of
    U+2588 would take the whole app down with a UnicodeEncodeError on the way out.
    """
    enc = getattr(stream or sys.stdout, "encoding", None) or "ascii"
    try:
        (BAR_FULL_RICH + BAR_EMPTY_RICH).encode(enc)
    except Exception:
        return False
    return True


def bar_chars(ascii_only):
    return (BAR_FULL_PLAIN, BAR_EMPTY_PLAIN) if ascii_only else (BAR_FULL_RICH, BAR_EMPTY_RICH)


# ---------------------------------------------------------------------------
# Numbers into something you can read at a glance
# ---------------------------------------------------------------------------


def bar(value, width=10, full=BAR_FULL_PLAIN, empty=BAR_EMPTY_PLAIN):
    value = 0.0 if value is None else max(0.0, min(1.0, float(value)))
    n = int(round(value * width))
    return full * n + empty * (width - n)


def arrow(delta):
    """``^ +0.26`` / ``v -0.10`` / ``''``. The one number that says what just moved."""
    if not delta:
        return "", ""
    before, after = delta
    diff = after - before
    if abs(diff) < 0.005:
        return "", ""
    return ("%s %+.2f" % ("^" if diff > 0 else "v", diff), "good" if diff > 0 else "bad")


def _fmt(x, signed=True):
    return ("%+.2f" if signed else "%.2f") % (x or 0.0)


# ---------------------------------------------------------------------------
# The left panel: one dwarf's whole head
# ---------------------------------------------------------------------------


def mind_lines(snap, full=BAR_FULL_PLAIN, empty=BAR_EMPTY_PLAIN):
    """``[(indent, style, text)]`` for the focused dwarf."""
    d = snap["dwarves"].get(snap["focus"])
    out = []
    if d is None:
        return [(0, "bad", "nobody left to talk to")]
    deltas = d.get("deltas") or {}

    head = "%s%s  at the %s   hp %.1f/20" % (
        d["name"], "  (chief)" if d.get("chief") else "", d["place"], d["health"])
    out.append((0, "head", head))
    t = d["traits"]
    out.append((0, "dim", "bravery %.2f  greed %.2f  temper %.2f" % (
        t["bravery"], t["greed"], t["temper"])))
    out.append((0, "dim", "sociab %.2f  pride %.2f  forgive %.2f" % (
        t["sociability"], t["pride"], t["forgiveness"])))
    hp = deltas.get("health")
    if hp:
        out.append((0, "bad" if hp[1] < hp[0] else "good",
                    "health %.1f -> %.1f" % hp))
    # Health says how close to dying; the condition says what it can still do.
    if d.get("injuries"):
        out.append((0, "bad", "hurt: %s" % d.get("condition", "hurt")))
        for inj in d["injuries"]:
            out.append((1, "dim", "%-14s %s %.2f" % (
                inj["kind"], bar(inj["severity"], 8, full, empty), inj["severity"])))

    out.append((0, "title", "emotions"))
    for k in ("anger", "fear", "happiness", "grief"):
        text, style = arrow(deltas.get(k))
        out.append((1, style or "",
                    "%-9s %s %.2f %s" % (k, bar(d["emotions"][k], 8, full, empty),
                                         d["emotions"][k], text)))

    out.append((0, "title", "needs"))
    for k in ("hunger", "thirst", "fatigue", "social"):
        text, style = arrow(deltas.get("need_" + k))
        out.append((1, style or "dim",
                    "%-9s %s %.2f %s" % (k, bar(d["needs"][k], 8, full, empty),
                                         d["needs"][k], text)))

    out.append((0, "title", "toward you (the player)"))
    p = d["player"]
    for k in ("trust", "respect", "hatred"):
        text, style = arrow(deltas.get("player_" + k))
        out.append((1, style or "",
                    "%-9s %s %s" % (k, _fmt(p[k]), text)))
    # What words bought, and what saying the same thing too often bought. Trust is deeds.
    out.append((1, "dim", "%-9s %s   (words; it fades)" % ("warmth", _fmt(p.get("warmth", 0.0),
                                                                         False))))
    if p.get("suspicion"):
        out.append((1, "warn" if p["suspicion"] >= 0.5 else "dim",
                    "%-9s %s   %s" % ("suspicion", _fmt(p["suspicion"], False),
                                      "it thinks you want something"
                                      if p["suspicion"] >= 0.5 else "")))
    out.append((1, "dim", "your gold: %d" % snap.get("player_gold", 0)))

    if d["others"]:
        out.append((0, "title", "others (trust / respect / hatred)"))
        for o in d["others"]:
            out.append((1, "dim", "%-9s %s  %s  %s" % (
                o["name"], _fmt(o["trust"]), _fmt(o["respect"]), _fmt(o["hatred"], False))))

    out.append((0, "title", "goals"))
    if d["goals"]:
        for g in d["goals"]:
            out.append((1, "", "%s%s  %.2f" % (
                g["kind"], (" " + g["target"]) if g["target"] else "", g["strength"])))
    else:
        out.append((1, "dim", "nothing it is holding on to"))

    out.append((0, "title", "memories"))
    if d["memories"]:
        for m in d["memories"]:
            who = m["actor"] or "someone"
            at = (" to %s" % m["target"]) if m["target"] and m["target"] != d["name"] else ""
            out.append((1, "", "%-12s by %s%s t%d sal %.2f" % (
                m["kind"], who, at, m["tick"], m["salience"])))
    else:
        out.append((1, "dim", "nothing worth remembering yet"))

    if d["obligations"]:
        out.append((0, "title", "obligations"))
        for ob in d["obligations"]:
            out.append((1, "", "#%s %s -- %s%s, by t%d" % (
                ob["id"], ob["what"], ob["status"],
                (", %d gold" % ob["payment"]) if ob["payment"] else "", ob["deadline"])))
    return out


# ---------------------------------------------------------------------------
# The right panel: the conversation
# ---------------------------------------------------------------------------


def _parsed_line(p):
    # The lexicon overrules the classifier on two fields, and says so: a line with a name for
    # somebody in it is an insult whatever the model made of the sentence around it.
    intent = ("intent %s -> %s (lexicon)" % tuple(p["intent_override"])
              if p.get("intent_override") else "intent %s" % p.get("intent"))
    addressed = ("to %s -> %s (lexicon)" % tuple(p["addressed_override"])
                 if p.get("addressed_override") else "to %s" % p.get("addressed"))
    bits = [intent, "topic %s" % p.get("topic"), addressed,
            "aggr %.2f" % (p.get("aggression") or 0.0),
            "val %+.2f" % (p.get("valence") or 0.0),
            "urg %.2f" % (p.get("urgency") or 0.0),
            (p.get("sincerity") or "SINCERE").lower()]
    if p.get("names"):
        bits.append("names %s" % ",".join(p["names"]))
    prof = p.get("profanity")
    if prof:
        top = max(int(r.get("tier", 0)) for r in prof)
        # The terms are shown back for tiers 1 and 2, so it is clear what was matched. A tier 3
        # match says only that it was one: the screen does not repeat a slur back at you.
        shown = ", ".join(r["term"] for r in prof if int(r.get("tier", 0)) < 3)
        bits.append("profanity: tier %d x%d%s" % (top, len(prof),
                                                  (" (%s)" % shown) if shown else ""))
        bump = p.get("profanity_bump")
        if bump and abs(bump[1] - bump[0]) > 1e-9:
            bits.append("aggression %.2f -> %.2f" % (bump[0], bump[1]))
    if p.get("ask"):
        a = p["ask"]
        bits.append("ask %s%s%s" % (
            a.get("action"), (" %sx %s" % (a.get("quantity", 1), a["item"])) if a.get("item")
            else "", (" @%s" % a["place"]) if a.get("place") else ""))
    return " | ".join(bits)


def _terms_text(terms):
    return ", ".join("%s %+.2f" % (k, v) for k, v in terms)


def decision_lines(decisions, highlight=None, keep=16):
    """What the focused dwarf did, with runs of the same action folded into one line.

    Fifty ticks of WORK @FORGE is one fact, not fifty, and the whole point of the panel is
    that the interesting decision should be findable in it.
    """
    rows = []
    for i, d in enumerate(decisions):
        if i == highlight:
            rows.append({"hi": True, "d": d})
            continue
        last = rows[-1] if rows else None
        if last is not None and not last.get("hi") and last["action"] == d["action"]:
            last["to"], last["n"] = d["tick"], last["n"] + 1
        else:
            rows.append({"hi": False, "action": d["action"], "from": d["tick"],
                         "to": d["tick"], "n": 1})
    out = []
    shown = rows
    elided = 0
    if len(rows) > keep:
        head, tail = keep // 2, keep - keep // 2 - 1
        shown = rows[:head] + [None] + rows[len(rows) - tail:]
        elided = len(rows) - head - tail
    for row in shown:
        if row is None:
            out.append((1, "dim", "... %d more ..." % elided))
        elif row["hi"]:
            d = row["d"]
            out.append((1, "npc", "t%d chose %s (%.2f)" % (d["tick"], d["action"], d["score"])))
            if d["terms"]:
                out.append((2, "term", "because: %s" % _terms_text(d["terms"])))
        elif row["n"] > 1:
            out.append((1, "dim", "t%d-%d %s (x%d)" % (row["from"], row["to"],
                                                       row["action"], row["n"])))
        else:
            out.append((1, "dim", "t%d %s" % (row["from"], row["action"])))
    return out


def entry_lines(entry):
    """``[(indent, style, text)]`` for one thing that happened in the conversation."""
    out = []
    kind = entry.get("kind")

    if kind == "log":
        # ``/log N``: the last N exchanges, drawn in full however long they are. The session
        # handed over the entries themselves, so this is the same renderer, once per entry.
        out.append((0, "you", entry.get("you", "/log")))
        for i, old in enumerate(entry.get("show") or ()):
            if i:
                out.append((0, "dim", ""))
            out.extend(entry_lines(old))
        for note in entry.get("notes") or ():
            out.append((1, "warn", note))
        return out

    if entry.get("you"):
        if kind in ("say",):
            out.append((0, "you", "you -> %s: \"%s\"" % (entry.get("to", "?"), entry["you"])))
        else:
            out.append((0, "you", "you: %s" % entry["you"]))

    p = entry.get("parsed")
    if p:
        out.append((1, "dim", "read (%s): %s" % (entry.get("source", "?"), _parsed_line(p))))

    ev = entry.get("event")
    if ev:
        head = "%s" % ev["type"]
        if ev.get("target"):
            head += " -> %s" % ev["target"]
        extra = []
        if ev.get("magnitude") is not None:
            extra.append("magnitude %.2f" % ev["magnitude"])
        if ev.get("item"):
            extra.append("%d %s" % (ev.get("count", 1), ev["item"]))
        if ev.get("damage"):
            extra.append("%.1f damage, %.1f hp left" % (ev["damage"], ev.get("health", 0.0)))
        if ev.get("place"):
            extra.append("at the %s" % ev["place"])
        if extra:
            head += "  (%s)" % ", ".join(extra)
        out.append((1, "warn", head))

    for d in entry.get("deltas") or ():
        style = "good" if d["to"] > d["from"] else "bad"
        out.append((2, style, "%-22s %+.2f -> %+.2f" % (d["field"], d["from"], d["to"])))

    for w in entry.get("witness") or ():
        bits = ", ".join("%s %+.2f -> %+.2f" % (c["field"], c["from"], c["to"])
                         for c in w["changes"])
        out.append((2, "dim", "%s saw it: %s" % (w["who"], bits)))

    out.extend(decision_lines(entry.get("decisions") or (), entry.get("highlight")))

    lines = entry.get("lines") or ()
    answer = entry.get("reply") or {}
    for line in lines[-SPOKEN_CAP:]:
        style = "npc" if line["kind"] not in ("INSULT", "THREAT", "RETORT",
                                              "REFUSE_APOLOGY") else "bad"
        out.append((1, style, "%s: \"%s\"" % (line["who"], line["text"])))
        if line["kind"] == "REPLY" and answer.get("kind"):
            out.append((2, "dim", "answering as %s, %s (%s)" % (
                answer["stance"].lower(), answer["mood"].lower(), answer["kind"])))
    if len(lines) > SPOKEN_CAP:
        out.append((1, "dim", "(%d more lines were said)" % (len(lines) - SPOKEN_CAP)))

    story = entry.get("story") or ()
    for s in story[:STORY_CAP]:
        out.append((1, "dim", "- " + s))
    if len(story) > STORY_CAP:
        out.append((1, "dim", "- ... and %d more" % (len(story) - STORY_CAP)))

    for row in entry.get("rows") or ():
        out.append((1, "npc" if row["focus"] else "",
                    "%-9s %-6s hp %4.1f  %-18s %s  goals: %s" % (
                        row["name"], row["place"], row["health"], row["mood"],
                        row["player"], row["goals"])))

    if kind == "why":
        out.append((0, "title", "%s chose %s (%.2f) because:" % (
            entry["who"], entry["action"], entry["score"])))
        for k, v in entry.get("terms") or ():
            out.append((1, "term", "%-22s %+.3f" % (k, v)))
        out.append((1, "dim", "it was choosing between:"))
        for c in entry.get("candidates") or ():
            out.append((2, "npc" if c["won"] else "dim",
                        "%-28s %+.2f%s" % (c["action"], c["score"],
                                           "  <-- won" if c["won"] else "")))

    if kind == "mind":
        out.append((0, "title", "everything in %s's head" % entry["who"]))
        out.extend((i + 1, s, t) for i, s, t in mind_lines(
            {"dwarves": {entry["state"]["id"]: entry["state"]},
             "focus": entry["state"]["id"], "player_gold": 0}))
        st = entry["state"]
        out.append((1, "dim", "inventory: %s" % ", ".join(
            "%s %s" % (k, v) for k, v in st["inv"].items())))

    for h in entry.get("help") or ():
        out.append((1, "", "%-28s %s" % h))
    for b in entry.get("banner") or ():
        out.append((1, "dim", b))
    for n in entry.get("notes") or ():
        out.append((1, "warn", n))
    return out


def rendered_height(lines, width):
    """How many terminal rows ``lines`` will actually occupy once wrapped at ``width``."""
    total = 0
    for indent, _style, text in lines:
        pad = 2 * indent
        total += len(textwrap.wrap(text, max(20, width - pad)) or [""])
    return total


def conversation_lines(snap, limit=6, height=None, width=DEFAULT_WIDTH, since=None):
    """The *tail* of the conversation: the newest exchanges, as many as will fit.

    The panel is a fixed size and the log is not, so this walks the feed backwards, measures
    each entry as it will really be drawn -- wrapping included -- and stops adding older ones
    when the next would overflow. The newest entry is always drawn whole, even when it is on
    its own taller than the panel: a reply you cannot see is worse than a panel that spills.
    Rendering from the front, which is what this used to do, put the newest reply off the
    bottom edge after a few dozen exchanges and there was no way to get it back. ``/log N``
    is the way back to anything older.

    ``since`` is an entry number (``entry["n"]``): with one, only entries after it are drawn,
    which is what the no-rich path wants -- there the terminal does the scrolling and reprinting
    the whole conversation every prompt is just noise.
    """
    feed = snap["feed"]
    if since is not None:
        feed = [e for e in feed if e.get("n", -1) > since]
    out = []
    if not feed:
        if since is None:
            out.append((0, "dim", "say something to %s, or /help" % snap["focus_name"]))
        return out
    if height is None:
        chosen = feed[-max(1, limit):]
    else:
        chosen = []
        used = 0
        for entry in reversed(feed):
            lines = entry_lines(entry)
            cost = rendered_height(lines, width) + (1 if chosen else 0)
            if chosen and (used + cost > height or len(chosen) >= max(1, limit)):
                break
            chosen.insert(0, entry)
            used += cost
    for i, entry in enumerate(chosen):
        if i:
            out.append((0, "dim", ""))
        out.extend(entry_lines(entry))
    return out


def status_line(snap):
    bits = ["tick %d" % snap["tick"], "seed %d" % snap["seed"],
            "%s" % snap["scenario"], "%d dwarves" % snap["living"],
            "focus %s" % snap["focus_name"]]
    if snap.get("profanity") is not None:
        bits.append("profanity %d" % snap["profanity"])
    if snap.get("profanity_note"):
        bits.append(snap["profanity_note"])
    if snap["classifier"] == "unavailable":
        bits.append("no classifier -- use /labels")
    return " | ".join(bits)


# ---------------------------------------------------------------------------
# Painting, twice
# ---------------------------------------------------------------------------


def _plain_block(lines, width, colour):
    out = []
    for indent, style, text in lines:
        pad = "  " * indent
        wrapped = textwrap.wrap(text, max(20, width - len(pad)),
                                subsequent_indent="  ") or [""]
        for line in wrapped:
            body = pad + line
            if colour and style:
                body = "\x1b[%sm%s\x1b[0m" % (ANSI.get(style, "0"), body)
            out.append(body)
    return out


def plain_render(snap, width=100, colour=False, limit=6, ascii_only=True, since=None):
    """The no-rich rendering: two stacked blocks and a rule.

    With ``since``, the conversation block is only what has happened since that entry number.
    There is no panel here and nothing is cleared: the terminal scrolls by itself, and
    reprinting the whole conversation at every prompt is exactly what pushed the newest reply
    off the top of the screen.
    """
    width = max(40, int(width))
    rule = "-" * width
    full, empty = bar_chars(ascii_only)
    out = [status_line(snap), rule, "THE DWARF"]
    out.extend(_plain_block(mind_lines(snap, full, empty), width, colour))
    out.append(rule)
    out.append("THE CONVERSATION" if since is None else "SINCE THE LAST PROMPT")
    body = _plain_block(conversation_lines(snap, limit, width=width, since=since), width, colour)
    out.extend(body or ["  (nothing new)"])
    out.append(rule)
    return "\n".join(out)


def _rich_group(mods, lines):
    Text = mods["Text"]
    body = []
    for indent, style, text in lines:
        t = Text("  " * indent + text, style=RICH_STYLE.get(style, ""))
        t.no_wrap = False
        body.append(t)
    return mods["Group"](*body) if body else mods["Text"]("")


def _rich_panels(mods, snap, limit=6, ascii_only=True, height=None, width=DEFAULT_WIDTH):
    full, empty = bar_chars(ascii_only)
    box = mods["box"].ASCII if ascii_only else mods["box"].ROUNDED
    left = mods["Panel"](
        _rich_group(mods, mind_lines(snap, full, empty)),
        title=snap["focus_name"], border_style="cyan", box=box)
    right = mods["Panel"](
        _rich_group(mods, conversation_lines(snap, limit, height=height, width=width)),
        title="conversation", border_style="magenta", box=box)
    return left, right


def rich_render(mods, snap, width=100, limit=6, ascii_only=True):
    """The same content, two panels wide, captured to a string."""
    width = max(60, int(width))
    left, right = _rich_panels(mods, snap, limit, ascii_only)
    grid = mods["Table"].grid(expand=True)
    if width >= SIDE_BY_SIDE:
        grid.add_column(ratio=40)
        grid.add_column(ratio=60)
        grid.add_row(left, right)
    else:
        grid.add_column()          # too narrow for two panels: stack them
        grid.add_row(left)
        grid.add_row(right)
    console = mods["Console"](file=io.StringIO(), width=width,
                              force_terminal=False, no_color=True, legacy_windows=False)
    console.print(status_line(snap), style="dim")
    console.print(grid)
    return console.file.getvalue()


def render(session, use_rich=None, width=None, limit=6, ascii_only=None, colour=None,
           since=None):
    """The whole screen as one string. Never raises, with rich or without it.

    ``colour`` defaults to whether stdout is a terminal, so a transcript piped to a file has no
    escape codes in it and a real console does.
    """
    snap = session.snapshot() if hasattr(session, "snapshot") else session
    if ascii_only is None:
        ascii_only = not unicode_ok()
    if colour is None:
        colour = supports_colour()
    mods = None if use_rich is False else rich_modules()
    if mods is None:
        return plain_render(snap, width or DEFAULT_WIDTH, colour=colour, limit=limit,
                            ascii_only=ascii_only, since=since)
    try:
        return rich_render(mods, snap, width or DEFAULT_WIDTH, limit=limit, ascii_only=ascii_only)
    except Exception:
        return plain_render(snap, width or DEFAULT_WIDTH, colour=colour, limit=limit,
                            ascii_only=ascii_only, since=since)


# ---------------------------------------------------------------------------
# The app
# ---------------------------------------------------------------------------


PROMPT = "\ntalk to %s > "


def _print_banner(session, out):
    from .talk import BANNER
    out.write("dwarfsim.talk -- seed %d, %d dwarves, scenario %s\n"
              % (session.seed, len(session.world.agents), session.scenario))
    for line in BANNER:
        out.write(line + "\n")
    out.write("\n")


def run(session, use_rich=True, width=None, out=None):
    """The interactive app: draw, read a line, act, draw again."""
    out = out or sys.stdout
    mods = rich_modules() if use_rich else None
    console = None
    seen = None
    ascii_only = not unicode_ok(out)
    if mods is not None:
        console = mods["Console"]()
        ascii_only = ascii_only or bool(getattr(console, "legacy_windows", False))
    _print_banner(session, out)
    while session.running:
        if console is not None:
            try:
                console.clear()
                layout = mods["Layout"]()
                layout.split_column(
                    mods["Layout"](name="body"),
                    mods["Layout"](name="foot", size=3))
                snap = session.snapshot()
                # The panel is this tall and no taller, so the tail that fits is what is
                # drawn: see conversation_lines(). The width is the right-hand column's,
                # because that is what the text will wrap to.
                talk_width = (int(console.width * 0.60) if console.width >= SIDE_BY_SIDE
                              else console.width) - 4
                talk_height = (console.height - 8 if console.width >= SIDE_BY_SIDE
                               else int((console.height - 8) * 0.55))
                left, right = _rich_panels(mods, snap, limit=FEED_LIMIT,
                                           ascii_only=ascii_only,
                                           height=max(4, talk_height),
                                           width=max(30, talk_width))
                body = layout["body"]
                if console.width >= SIDE_BY_SIDE:
                    body.split_row(mods["Layout"](left, name="mind", ratio=40),
                                   mods["Layout"](right, name="talk", ratio=60))
                else:
                    body.split_column(mods["Layout"](right, name="talk", ratio=55),
                                      mods["Layout"](left, name="mind", ratio=45))
                layout["foot"].update(mods["Panel"](status_line(snap), border_style="dim"))
                console.print(layout)
            except Exception:
                out.write(render(session, use_rich=False, width=width, since=seen) + "\n")
                seen = _last_n(session, seen)
        else:
            # No panel to fit anything into: print what is new and let the terminal scroll.
            out.write(render(session, use_rich=False, width=width, since=seen) + "\n")
            seen = _last_n(session, seen)
        try:
            line = input(PROMPT % session.focus.name)
        except (EOFError, KeyboardInterrupt):
            out.write("\n")
            break
        session.command(line)
    return 0


def _last_n(session, fallback):
    """The number of the newest entry, for "only what is new since the last prompt"."""
    feed = getattr(session, "feed", None)
    if not feed:
        return fallback
    return feed[-1].get("n", fallback)


def run_script(session, lines, use_rich=True, width=None, out=None):
    """Play a list of typed lines and print the transcript. No terminal needed.

    This is what the deliverable transcript comes out of, and it is also the cheapest way to
    check that a change to the renderer still renders.
    """
    out = out or sys.stdout
    ascii_only = not unicode_ok(out)
    _print_banner(session, out)
    for raw in lines:
        raw = raw.strip()
        if not raw or raw.startswith("#"):
            continue
        out.write("\n%s\n" % ("=" * 78))
        out.write("> %s\n" % raw)
        out.write("%s\n" % ("=" * 78))
        session.command(raw)
        entry = session.feed[-1] if session.feed else None
        if entry is not None:
            for line in _plain_block(entry_lines(entry), width or DEFAULT_WIDTH,
                                     colour=False):
                out.write(line + "\n")
        if not session.running:
            break
    out.write("\n%s\n" % ("=" * 78))
    out.write(render(session, use_rich=use_rich, width=width, ascii_only=ascii_only))
    out.write("\n")
    return 0

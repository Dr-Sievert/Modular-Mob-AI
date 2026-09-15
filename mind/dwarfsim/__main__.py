"""Command line.

    python -m dwarfsim run --agents 6 --ticks 2000 --seed 1 --out runs/run1.jsonl
    python -m dwarfsim run --scenario feud --out runs/feud.jsonl
    python -m dwarfsim run --scenario feud-chief --out runs/chief.jsonl
    python -m dwarfsim player --out runs/player.jsonl
    python -m dwarfsim run --scenario feud --seed 9 --scorer runs/learn/imitator \
        --out runs/feud_learned.jsonl
    python -m dwarfsim view runs/run1.jsonl -o runs/run1.html
"""

import argparse
import os
import sys
import time

from . import run_player_session, run_sim
from .world import SCENARIOS


def _report(path, summary, args, started, label):
    size = os.path.getsize(path)
    print("%s: %d ticks, %d agents, seed %d, %s" % (
        path, summary["ticks"], args.agents, args.seed, label))
    print("  %.1f MB, %.2f s" % (size / 1e6, time.time() - started))
    print("  %d deaths, %d hits, %d thefts, %d feuds, %d monsters (%d slain)" % (
        len(summary["deaths"]), summary["fights"]["hits"], len(summary["thefts"]),
        len(summary["feuds"]), summary["monsters"]["arrived"], summary["monsters"]["slain"]))
    answered = summary["provocations"]["answered"]
    print("  provocations answered: " + ", ".join(
        "%s %d" % (k.lower(), n) for k, n in answered.items() if n))
    p = summary["promises"]
    print("  %d asks, %d taken on, %d kept, %d broken, %d haggled; %d gossip; %d goals adopted"
          % (p["made"], p["accepted"], p["kept"], p["broken"], p["bargained"],
             summary["gossip"], summary["goals_adopted"]))
    if summary["punishments"]:
        print("  %d punishments by %s" % (len(summary["punishments"]), summary["chief"]))


def main(argv=None):
    parser = argparse.ArgumentParser(prog="dwarfsim", description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="simulate a settlement and write a JSONL log")
    run.add_argument("--agents", type=int, default=6, help="how many dwarves (default 6)")
    run.add_argument("--ticks", type=int, default=2000, help="how many ticks (default 2000)")
    run.add_argument("--seed", type=int, default=1, help="everything random comes from this")
    run.add_argument("--scenario", default="default", choices=SCENARIOS,
                     help="starting conditions only, never a scripted action")
    run.add_argument("--chief", dest="chief", action="store_true", default=None,
                     help="appoint a chief (off by default; feud-chief turns it on)")
    run.add_argument("--no-chief", dest="chief", action="store_false",
                     help="forbid a chief even in a scenario that wants one")
    run.add_argument("--temperature", type=float, default=None,
                     help="softmax temperature for action choice (default 0.25)")
    run.add_argument("--scorer", default=None,
                     help="an exported learned scorer (e.g. runs/learn/imitator) instead of "
                          "the hand-written weight table")
    run.add_argument("--profanity", type=int, default=1, choices=(0, 1, 2, 3),
                     help="how far the dwarves go: 0 never swears, 1 mild oaths (default), "
                          "2 crude insults, 3 the in-world slurs; what is said to them is "
                          "always read for all three tiers")
    run.add_argument("--profanity-speech", default=None,
                     help="a JSON file of tier 3 terms the dwarves may say (default "
                          "text/profanity_speech.json, and the in-world list in "
                          "dwarfsim/profanity.py if it is not there)")
    run.add_argument("--out", default="runs/run1.jsonl", help="where to write the log")
    run.add_argument("--html", default=None, help="also write the viewer here")

    play = sub.add_parser("player", help="a scripted player session: one order, twice")
    play.add_argument("--agents", type=int, default=6)
    play.add_argument("--ticks", type=int, default=900)
    play.add_argument("--seed", type=int, default=1)
    play.add_argument("--temperature", type=float, default=None)
    play.add_argument("--scorer", default=None)
    play.add_argument("--profanity", type=int, default=1, choices=(0, 1, 2, 3))
    play.add_argument("--profanity-speech", default=None)
    play.add_argument("--out", default="runs/player.jsonl")
    play.add_argument("--html", default=None)

    view = sub.add_parser("view", help="render a run as one self-contained HTML file")
    view.add_argument("log", help="the .jsonl written by 'run'")
    view.add_argument("-o", "--out", default=None, help="the .html to write (default: alongside)")

    args = parser.parse_args(argv)

    if args.command in ("run", "player"):
        started = time.time()
        scorer = None
        if args.scorer:
            from .learn import LearnedScorer
            scorer = LearnedScorer.load(args.scorer)
        if args.command == "run":
            summary = run_sim(args.out, n_agents=args.agents, ticks=args.ticks, seed=args.seed,
                              scenario=args.scenario, temperature=args.temperature,
                              chief=args.chief, scorer=scorer, profanity_tier=args.profanity,
                              profanity_speech=args.profanity_speech)
            label = "scenario %s%s" % (args.scenario,
                                       "" if summary["chief"] is None
                                       else ", chief %s" % summary["chief"])
        else:
            summary = run_player_session(args.out, n_agents=args.agents, ticks=args.ticks,
                                         seed=args.seed, temperature=args.temperature,
                                         scorer=scorer, profanity_tier=args.profanity,
                                         profanity_speech=args.profanity_speech)
            label = "player session"
        if scorer is not None:
            label += ", learned scorer %s" % args.scorer
        _report(args.out, summary, args, started, label)
        if summary.get("profanity_note"):
            print("  " + summary["profanity_note"])
        swore = summary.get("swears") or {}
        if swore.get("count"):
            tiers = swore.get("tiers") or {}
            print("  swore %d times (tier 1 x%d, 2 x%d, 3 x%d)" % (
                swore["count"], tiers.get(1, 0) or tiers.get("1", 0),
                tiers.get(2, 0) or tiers.get("2", 0),
                tiers.get(3, 0) or tiers.get("3", 0)))
        quoted = [line for line in summary["story"] if '"' in line["text"]]
        for line in (quoted or summary["story"])[:12]:
            print("  " + line["text"])
        if args.html:
            from .viewer import write_html
            write_html(args.out, args.html)
            print("  viewer: %s" % args.html)
        return 0

    if args.command == "view":
        from .viewer import write_html
        out = args.out or (os.path.splitext(args.log)[0] + ".html")
        write_html(args.log, out)
        print("%s (%.1f MB)" % (out, os.path.getsize(out) / 1e6))
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())

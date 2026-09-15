#!/usr/bin/env python3
"""Classify one line, or a file of lines, with the trained tiny classifier.

    python text/classify.py "Get away from my forge, Brokk!"
    python text/classify.py --file lines.txt
    python text/classify.py --file lines.txt --scores
    python text/classify.py --prev-intent INSULT "get out"

One JSON object per input line, in SCHEMA.md field order. numpy only: no torch
is imported, so this is the same code path the game will run.

`--prev-intent` sets the one-hot in the side vector for the intent of the line
these answer, the same value the game passes when a dwarf replies. It applies to
every line of the invocation. Omit it for `unknown`, which is what every row of
today's corpus carries, because the corpus has no conversations in it: the other
thirteen columns have never had a gradient, so passing one *does* move the
numbers and that movement means nothing yet. The flag exists so that the day the
corpus has turns in it, the only work left is labelling, not plumbing.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from text.classifier.features import PREV_INTENTS, UNKNOWN_PREV_INTENT  # noqa: E402
from text.classifier.infer import Classifier  # noqa: E402

DEFAULT_MODEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "models", "clf")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("text", nargs="*", help="one or more utterances")
    ap.add_argument("--file", help="file of utterances, one per line")
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--scores", action="store_true", help="include per-class probabilities")
    ap.add_argument("--prev-intent", default=UNKNOWN_PREV_INTENT, choices=PREV_INTENTS,
                    metavar="INTENT",
                    help="intent of the line these answer, one of: %s "
                         "(default: %s)" % (", ".join(PREV_INTENTS), UNKNOWN_PREV_INTENT))
    ap.add_argument("--indent", type=int, default=None, help="pretty-print with this indent")
    args = ap.parse_args(argv)

    texts = list(args.text)
    if args.file:
        with open(args.file, encoding="utf-8") as fh:
            texts.extend(line.strip() for line in fh if line.strip())
    if not texts:
        ap.error("give an utterance or --file")

    if not os.path.exists(os.path.join(args.model, "model.json")):
        ap.error(f"no model in {args.model}; run "
                 f"`python -m text.classifier.train --hardcases` first")

    clf = Classifier.load(args.model)
    for text in texts:
        print(json.dumps(clf.predict(text, with_scores=args.scores,
                                     prev_intent=args.prev_intent),
                         ensure_ascii=False, indent=args.indent))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

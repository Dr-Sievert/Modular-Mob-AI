"""The label spaces, copied from `text/SCHEMA.md` in exactly the order it lists them.

The order is load-bearing: it is the index order of the softmax heads, of the
exported weight rows, and of the Java port. `tests/test_classifier.py` parses
SCHEMA.md and asserts these four lists match it exactly, so the file below can
never silently drift from the schema.
"""

from __future__ import annotations

import os
import re

# --- from SCHEMA.md, "## Intents" -----------------------------------------
INTENTS = ["GREET", "FAREWELL", "SMALLTALK", "QUESTION", "REQUEST", "COMMAND",
           "OFFER", "PRAISE", "APOLOGY", "INSULT", "THREAT", "WARNING", "ACCUSE"]

# --- from SCHEMA.md, "## Topics" ------------------------------------------
TOPICS = ["FORGE", "MINE", "TREASURE", "FOOD", "DRINK", "HOME", "WEAPON",
          "WORK", "CLAN", "MONSTER", "TRADE", "NONE"]

# --- from SCHEMA.md, the `addressed` row of the field table ---------------
ADDRESSED = ["LISTENER", "THIRD", "GROUP", "NONE"]

# --- from SCHEMA.md, "## Sincerity" ---------------------------------------
# Optional on a record: early labelings predate the field. Rows without it are
# masked out of the sincerity loss and out of its metrics.
SINCERITY = ["SINCERE", "SARCASTIC", "JOKING"]

# --- from SCHEMA.md, "## Name pool"; names are matched, never predicted ---
NAME_POOL = ["Alvis", "Borin", "Brokk", "Brynja", "Dagna", "Dvalin", "Eitri", "Frida",
             "Grimhild", "Gudrun", "Halvar", "Hrolf", "Ingrid", "Kolbrun", "Orm", "Ragna",
             "Runa", "Sindri", "Skadi", "Steinar", "Torvi", "Tova", "Vidar", "Yngvar"]

# The regression heads, in the order of the exported `head_floats` rows.
# aggression and urgency are squashed with sigmoid, valence with tanh.
FLOAT_FIELDS = ["aggression", "valence", "urgency"]
FLOAT_ACTIVATIONS = ["sigmoid", "tanh", "sigmoid"]
FLOAT_RANGES = {"aggression": (0.0, 1.0), "valence": (-1.0, 1.0), "urgency": (0.0, 1.0)}

# The softmax heads, in export order. `sincerity` is optional per record.
ENUM_FIELDS = {"intent": INTENTS, "topic": TOPICS, "addressed": ADDRESSED,
               "sincerity": SINCERITY}
OPTIONAL_ENUM_FIELDS = ("sincerity",)
MASKED = -100          # target index for "this row has no label for this head"

SCHEMA_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "SCHEMA.md")


def names_in_text(text: str, pool: list[str] | None = None) -> list[str]:
    """Every pool name literally present in `text`, in pool order.

    Case-sensitive with letter boundaries, the same rule `merge_labels.py` uses
    to validate the labels, so training targets and inference agree.
    """
    pool = NAME_POOL if pool is None else pool
    return [n for n in pool
            if re.search(r"(?<![A-Za-z])" + re.escape(n) + r"(?![A-Za-z])", text)]


def parse_schema(path: str | None = None) -> dict:
    """Pull the four lists straight out of SCHEMA.md (used by the tests)."""
    with open(path or SCHEMA_PATH, encoding="utf-8") as fh:
        text = fh.read()

    def section(title: str) -> str:
        m = re.search(r"^##\s+" + re.escape(title) + r"\s*$(.*?)(?=^##\s|\Z)",
                      text, re.M | re.S)
        if not m:
            raise ValueError(f"section {title!r} not found in SCHEMA.md")
        return m.group(1)

    def first_backtick_line(title: str) -> str:
        for line in section(title).splitlines():
            line = line.strip()
            if line.startswith("`"):
                return line
        raise ValueError(f"no backticked list under {title!r} in SCHEMA.md")

    def table_row(field: str) -> str:
        m = re.search(r"^\|\s*`" + re.escape(field) + r"`\s*\|([^|]*)\|", text, re.M)
        if not m:
            raise ValueError(f"`{field}` row not found in SCHEMA.md")
        return m.group(1)

    return {
        "intents": re.findall(r"`([A-Z]+)`", first_backtick_line("Intents")),
        "topics": re.findall(r"`([A-Z]+)`", first_backtick_line("Topics")),
        "addressed": re.findall(r"`([A-Z]+)`", table_row("addressed")),
        "sincerity": re.findall(r"`([A-Z]+)`", table_row("sincerity")),
        "names": first_backtick_line("Name pool").strip("`").split(),
    }

"""One log for the whole training process: to the console for whoever is watching, and to a file for afterwards.

The console copy is what the game's build forwards into the IDE. The file is what survives a run that was stopped from
outside, which is how every training run ends.
"""

from __future__ import annotations

import logging
import sys
import time
from pathlib import Path

FORMAT = "%(asctime)s %(name)-11s %(message)s"
DATE = "%H:%M:%S"


def setup(directory: str = "logs") -> Path:
    """Configures the ``mmai`` logger tree and returns the file it is writing to."""

    path = Path(directory) / time.strftime("train-%Y%m%d-%H%M%S.log")
    path.parent.mkdir(parents=True, exist_ok=True)

    formatter = logging.Formatter(FORMAT, DATE)

    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(formatter)

    file = logging.FileHandler(path, encoding="utf-8")
    file.setFormatter(formatter)

    root = logging.getLogger("mmai")
    root.setLevel(logging.INFO)
    root.handlers.clear()
    root.addHandler(console)
    root.addHandler(file)
    root.propagate = False

    return path


def get(name: str) -> logging.Logger:
    return logging.getLogger(f"mmai.{name}")

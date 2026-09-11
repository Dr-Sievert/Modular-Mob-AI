"""Putting a finished file in place of the old one, the way everything this side writes for someone else to read.

Every file the game, the build or a watching script reads (the weights, the status, the evaluation table and target, the
trainer's state) is written under a temporary name and renamed over the old one, so a reader never sees half of it. On
Windows the rename is refused while anyone has the old file open, and the readers open these files every few seconds:
scripts\\watch.ps1 reads the status and the evaluation table, the build reads the status, every worker reads the
evaluation target at the start of each fight. A refusal lasts as long as one read, so it is waited out rather than
allowed to end a run that had been training for hours, which is what it did.
"""

from __future__ import annotations

import os
import time
from pathlib import Path

# A read takes milliseconds; ten seconds of refusals means something is holding the file open for good.
ATTEMPTS = 500
PAUSE = 0.02


def replace(temporary: str | Path, target: str | Path) -> None:
    """``os.replace``, tried again for as long as a reader holding the target open is the only thing in the way."""

    for attempt in range(ATTEMPTS):
        try:
            os.replace(temporary, target)
            return

        except PermissionError:
            if attempt == ATTEMPTS - 1:
                raise

            time.sleep(PAUSE)

"""Brings a stopped training run up to date with a new schema id, changing nothing else. A one-off; delete it after.

The observation and the action vector did not move when a layout became a body's own: the same inputs in the same blocks
at the same offsets, the same controls, the same heads. What changed is that a layout now says which body it is for, so
the checksum of the layout changed with it, and every file a run carries that says "this is the layout I was trained
against" has to be brought up to the new number. Nothing else in any of them is touched.

What carries the id, all of which this covers:

  state.pt, checkpoints/*.pt   the "schema_id" the trainer refuses to resume across
  best.mbw, weights/*.mbw      the schema id at offset 8 of a 52-byte header
  rollouts/**/*.mbr            the same offset in a 16-word header; patched in place, since these run to gigabytes
  demos/**/*.mbr               the teacher's record, which the teacher pull refuses from another layout

schema.json is deliberately left alone: the build writes a fresh one every time a run starts.

Nothing is written until everything has been looked at. A file whose id is neither the one being replaced nor the one
being written to stops the whole folder before a byte changes, because a run holding two layouts is worse than a run
holding an old one.

    python trainer/restamp_schema.py <run folder> --schema models/vs-copy/schema.json [--from 3e475bda] [--dry-run]

The new id is the CRC32 of the schema file's bytes, worked out here rather than typed in, so it cannot drift from what
the game actually writes. --from defaults to the id every run carried before bodies had names of their own.
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import zlib
from pathlib import Path

# Both formats put their magic first, their version second and their schema id third, so the offset is the same in each.
SCHEMA_OFFSET = 8

MAGIC = {".mbw": b"MBW1", ".mbr": b"MBR1"}


def stamped_id(path: Path) -> tuple[bytes, int]:
    """The magic and the schema id in a weight file or a rollout shard, without reading the rest of it."""

    with path.open("rb") as handle:
        header = handle.read(SCHEMA_OFFSET + 4)

    if len(header) < SCHEMA_OFFSET + 4:
        raise SystemExit(f"{path} is shorter than its own header")

    return header[:4], struct.unpack_from("<I", header, SCHEMA_OFFSET)[0]


def restamp_binary(path: Path, new: int) -> None:
    """Four bytes, in place. A rollout shard can be gigabytes and none of the rest of it is being changed."""

    with path.open("r+b") as handle:
        handle.seek(SCHEMA_OFFSET)
        handle.write(struct.pack("<I", new))


def main() -> None:
    parser = argparse.ArgumentParser(description="Re-stamps a stopped run with a new schema id.")
    parser.add_argument("run", type=Path, help="the run folder, such as runs/league2")
    parser.add_argument("--schema", type=Path, required=True, help="the schema.json whose checksum is the new id")
    parser.add_argument("--from", dest="old", default="3e475bda", help="the id being replaced, hexadecimal")
    parser.add_argument("--dry-run", action="store_true", help="say what would change and change nothing")
    arguments = parser.parse_args()

    run = arguments.run
    old = int(arguments.old, 16)
    new = zlib.crc32(arguments.schema.read_bytes()) & 0xFFFFFFFF

    if not run.is_dir():
        raise SystemExit(f"{run} is not a folder")

    print(f"{run}: {old:08x} -> {new:08x} (the checksum of {arguments.schema})")

    if old == new:
        raise SystemExit("the two ids are the same; nothing to do, and probably the wrong schema file")

    # Everything is looked at before anything is written, so a folder that holds a file of some third layout is left
    # exactly as it was rather than half converted.
    binaries: list[Path] = []
    already = 0
    links: list[Path] = []

    for suffix in MAGIC:
        for path in sorted(run.rglob("*" + suffix)):

            magic, found = stamped_id(path)

            if magic != MAGIC[suffix]:
                raise SystemExit(f"{path} starts with {magic!r}, not {MAGIC[suffix]!r}: nothing has been changed")

            if found == new:
                already += 1
                continue

            if found != old:
                raise SystemExit(f"{path} carries schema {found:08x}, which is neither {old:08x} nor {new:08x}: "
                                 f"nothing has been changed")

            binaries.append(path)

    states = sorted(run.rglob("*.pt"))

    # A run started by compare.ps1 has its demos as a junction to another run's, so patching through it reaches the other
    # run's record too. That is what has to happen, but it should not be a surprise. Junctions are what Windows actually
    # makes, and a junction is not a symlink as far as is_symlink is concerned, so both are asked about.
    for folder in run.rglob("*"):
        if folder.is_dir() and (folder.is_symlink() or os.path.isjunction(folder)):
            links.append(folder)

    for link in links:
        print(f"  note: {link.relative_to(run)} is a link, so files it points at are reached through it as well")

    print(f"  {len(binaries)} weight files and rollout shards to stamp, {already} already stamped, "
          f"{len(states)} checkpoints to look at")

    if arguments.dry_run:
        print("  dry run: nothing changed")
        return

    for path in binaries:
        restamp_binary(path, new)

    # Imported here rather than at the top, so that a dry run, and the refusal above, need no torch at all.
    import torch

    changed = 0
    untouched = 0

    for path in states:

        state = torch.load(path, map_location="cpu", weights_only=False)

        if not isinstance(state, dict) or "schema_id" not in state:
            untouched += 1
            continue

        if state["schema_id"] == new:
            untouched += 1
            continue

        if state["schema_id"] != old:
            raise SystemExit(f"{path} carries schema {state['schema_id']:08x}, which is neither {old:08x} nor "
                             f"{new:08x}. The weight files and shards have already been stamped; sort this file out and "
                             f"run it again, which will skip everything already done")

        state["schema_id"] = new
        torch.save(state, path)
        changed += 1

    print(f"  stamped {len(binaries)} weight files and shards, and {changed} checkpoints "
          f"({untouched} carried no schema id or already had the new one)")
    print(f"  {run} is ready to carry on under {new:08x}")


if __name__ == "__main__":
    sys.exit(main())

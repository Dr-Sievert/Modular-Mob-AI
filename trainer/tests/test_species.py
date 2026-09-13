"""Every body the game declares, through the training side's generic paths.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test

Nothing here names a body. The layouts come from whatever ``gradlew :fabric:brainParity`` wrote, which is one folder per
body in ``Species.ALL``, so a body added to that register arrives here without a line being written: that is the claim
being checked, and a test that listed the bodies it knew about would be the list the register exists to make impossible.

What it checks is that this side really does read a body out of its file rather than knowing the humanoid by heart. The
one body that makes that worth checking is the smallest -- no terrain grid, no echo, and a choice masked by a block of its
own rather than by a hotbar -- since the humanoid and the beast both happen to have a terrain grid and an echo, and code
that assumed either would pass on both of them.

The other suites read the humanoid's file on purpose: they test what the trainer does, not how many bodies it can do it
for, and the humanoid is the body every trained network drives.
"""

from __future__ import annotations

import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from mmai.model import SHARD_PRIVILEGED, Actor
from mmai.rollout import FLAG_DONE, FLAG_NEW, VERSION, read_shard
from mmai.schema import Schema

REPOSITORY = Path(__file__).resolve().parents[2]
BODIES = REPOSITORY / "mod" / "fabric" / "build" / "brain-parity" / "check"

H1, MEMORY, H3 = 256, 128, 128


def layouts() -> list[tuple[str, Schema]]:
    """Every body's layout the parity check has written, by folder name. The folder is the body's own name."""

    found = []

    for schema in sorted(BODIES.glob("*/schema.json")):
        parsed = Schema.load(schema)

        # The folder the build writes is named after the body, so a file in the wrong one would mean the build and the game
        # disagree about which body it asked for -- which is the one way this whole arrangement could go quietly wrong.
        assert parsed.species == schema.parent.name, f"{schema} holds a {parsed.species}"
        found.append((parsed.species, parsed))

    return found


def shard(path: Path, schema: Schema, rows: int = 4) -> Path:
    """One shard of this body's width, laid out as RolloutWriter lays one out, so the reader is really given the body.

    Counting observations, so that a reader taking the wrong width lands on the wrong numbers rather than on zeroes.
    """

    privileged = len(SHARD_PRIVILEGED)

    header = [int.from_bytes(b"MBR1", "little"), VERSION, schema.schema_id, 0, 1, 1, 0, 1,
              schema.obs_dim, schema.act_dim, MEMORY, 0, rows, 1, 0, privileged]

    out = bytearray(struct.pack("<16I", *header))

    for row in range(rows):
        flags = (FLAG_NEW if row == 0 else 0) | (FLAG_DONE if row == rows - 1 else 0)
        out += struct.pack("<2I2f", 7, flags, 0.25 * row, -0.5)
        out += np.asarray(
            [0.1] * schema.act_dim
            + [row + column / 1000.0 for column in range(schema.obs_dim)]
            + [0.0] * privileged,
            dtype="<f4",
        ).tobytes()

    out += struct.pack("<I", 0)
    out += np.zeros(MEMORY, dtype="<f4").tobytes()

    path.write_bytes(bytes(out))
    return path


class EveryBodyTest(unittest.TestCase):

    def setUp(self) -> None:
        self.bodies = layouts()

        if not self.bodies:
            self.skipTest(f"no layouts in {BODIES}; run scripts\\parity.ps1 first")

    def test_there_is_more_than_one_body(self) -> None:
        """One body proves nothing about going round them, and the whole suite below would pass on a build with one."""

        self.assertGreater(len(self.bodies), 1, [name for name, _ in self.bodies])

    def test_no_two_bodies_share_a_layout(self) -> None:
        """The schema id carries the species name, so two bodies can only collide by being declared twice -- and if they
        did, either one's weights would load into the other and play badly for reasons nobody could find."""

        ids = {name: schema.schema_id for name, schema in self.bodies}

        self.assertEqual(len(set(ids.values())), len(ids), ids)

    def test_every_layout_adds_up(self) -> None:
        """Blocks covering the observation exactly, heads filling the action vector exactly. Schema.parse checks this as it
        reads, so the assertions here are about what it read rather than about whether it complained."""

        for name, schema in self.bodies:
            with self.subTest(name):
                self.assertEqual(sum(block.size for block in schema.blocks), schema.obs_dim)
                self.assertEqual(len(schema.action_names), schema.act_dim)
                self.assertEqual(sum(head.size for head in schema.heads), schema.logit_dim)

                # Whatever a choice is masked by has to be somewhere in this body's own observation, wherever the body
                # chose to keep it: a mask offset past the end reads whatever is next in memory.
                for head in schema.categorical_heads:
                    if head.mask >= 0:
                        self.assertLessEqual(head.mask + head.size, schema.obs_dim, head.name)

    def test_an_actor_is_built_at_each_body_s_own_width(self) -> None:
        """Straight out of the file, plain and pooled. Pooling reads where the enemy slots are from the body's schema,
        which is the only thing that knows, so it is the arithmetic most likely to have a width written into it."""

        for name, schema in self.bodies:
            with self.subTest(name):
                for slots in (0, 16):
                    actor = Actor.for_schema(schema, H1, MEMORY, H3, slot_enc=slots)

                    self.assertEqual(actor.topology.obs_dim, schema.obs_dim)
                    self.assertEqual(actor.topology.out_dim, schema.logit_dim)
                    self.assertEqual(actor.norm_mean.shape[0], schema.obs_dim)

                    logits, memory = actor(torch.zeros(2, 1, schema.obs_dim), torch.zeros(2, MEMORY))

                    # Both come back with the time axis the batch went in with, as the parity fixture reads them.
                    self.assertEqual(tuple(logits.shape), (2, 1, schema.logit_dim))
                    self.assertEqual(tuple(memory.shape), (2, 1, MEMORY))

    def test_a_shard_of_each_body_s_width_round_trips(self) -> None:
        """The rollout reader takes its widths from the shard's own header, and a body is one such width. Read back
        column by column, so a reader off by a body's worth of floats fails rather than merely reading less."""

        with tempfile.TemporaryDirectory() as directory:
            for name, schema in self.bodies:
                with self.subTest(name):
                    header, segments = read_shard(shard(Path(directory) / f"{name}.mbr", schema))

                    self.assertEqual(header.schema_id, schema.schema_id)
                    self.assertEqual(header.obs_dim, schema.obs_dim)
                    self.assertEqual(header.act_dim, schema.act_dim)
                    self.assertEqual(len(segments), 1)

                    segment = segments[0]

                    self.assertTrue(segment.done)
                    self.assertEqual(segment.obs.shape[1], schema.obs_dim)
                    self.assertEqual(segment.actions.shape[1], schema.act_dim)
                    self.assertTrue(
                        np.allclose(segment.obs[0], [column / 1000.0 for column in range(schema.obs_dim)], atol=1e-5)
                    )

    def test_a_body_with_a_block_another_lacks_is_asked_rather_than_assumed(self) -> None:
        """Which blocks exist is the thing that varies, and this is the arrangement that lets it: block() for one that may
        be missing, require() for one something genuinely needs. A body missing a block has to come back as None here and
        as a message naming the body there, never as a slice of whatever is at that offset instead."""

        every = {name: {block.name for block in schema.blocks} for name, schema in self.bodies}
        shared = set.intersection(*every.values())
        varies = set.union(*every.values()) - shared

        self.assertTrue(varies, f"every body has the same blocks, so nothing here is exercised: {every}")

        for name, schema in self.bodies:
            with self.subTest(name):
                for block in varies:
                    if block in every[name]:
                        self.assertIsNotNone(schema.block(block))
                        self.assertEqual(schema.require(block).name, block)

                    else:
                        self.assertIsNone(schema.block(block))

                        with self.assertRaises(ValueError) as refused:
                            schema.require(block)

                        self.assertIn(name, str(refused.exception))
                        self.assertIn(block, str(refused.exception))


if __name__ == "__main__":
    unittest.main()

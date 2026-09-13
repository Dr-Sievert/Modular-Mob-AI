"""The rollout shard format, the privileged columns in it, and that none of it reached the actor.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test

The shards here are written by hand rather than by the game, so that what is tested is the format both sides agreed on and
not whatever the reader happens to do. The game's half of it is arena/FightFacts.java and brain/nn/RolloutWriter.java, and
the last test reads the first of those to make sure the two lists have not drifted apart.
"""

from __future__ import annotations

import inspect
import re
import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from mmai.model import SHARD_PRIVILEGED, Actor
from mmai.rollout import FLAG_DONE, FLAG_NEW, FLAG_TRUNCATED, VERSION, read_header, read_shard
from mmai.schema import Schema
from mmai.weights import export as export_weights, read as read_weights, segments as weight_segments

REPOSITORY = Path(__file__).resolve().parents[2]
SCHEMA = REPOSITORY / "mod" / "fabric" / "build" / "brain-parity" / "check" / "humanoid" / "schema.json"
FACTS = REPOSITORY / "mod" / "common" / "src" / "main" / "java" / "net" / "sievert" / "modularmobai" / "arena" / "FightFacts.java"

OBS_DIM = 3
ACT_DIM = 2
HIDDEN = 2
PRIVILEGED = len(SHARD_PRIVILEGED)

# The widths the trainer runs by default, and the parameter count they come to on the humanoid's 792 floats. The number is
# in docs/architecture.md, and the point of having it here is that anything the actor grows an input for moves it.
H1, MEMORY, H3 = 256, 128, 128
PARAMETERS = 371_783


def written(path: Path, rows: list[tuple], starts: list[tuple[int, list[float]]], version: int = VERSION,
            privileged: int = PRIVILEGED) -> Path:
    """One shard, laid out by hand exactly as RolloutWriter lays one out.

    :param rows: (agent, flags, reward, log probability, action, obs, privileged) per row
    :param starts: (row, hidden state) for each segment
    """

    header = [int.from_bytes(b"MBR1", "little"), version, 0x7D7BF60E, 0, 12, 3, 1, 4,
              OBS_DIM, ACT_DIM, HIDDEN, 0, len(rows), len(starts), sum(1 for row in rows if not row[1] & 6), privileged]

    out = bytearray(struct.pack("<16I", *header))

    for agent, flags, reward, log_prob, action, obs, told in rows:
        out += struct.pack("<2I2f", agent, flags, reward, log_prob)
        out += np.asarray(list(action) + list(obs) + list(told), dtype="<f4").tobytes()

    for row, state in starts:
        out += struct.pack("<I", row)
        out += np.asarray(state, dtype="<f4").tobytes()

    path.write_bytes(bytes(out))
    return path


def told(first: float) -> list[float]:
    """Ten privileged floats counting up from a given number, so every column is a different one."""

    return [round(first + column / 100.0, 4) for column in range(PRIVILEGED)]


def two_fights(path: Path, **options) -> Path:
    """Two agents' rows interleaved in one shard, as a worker running two fights at once writes them: one fight that ended
    and one that was cut off."""

    return written(
        path,
        [
            (7, FLAG_NEW, 0.0, -0.5, [0.0, 0.0], [1.0, 2.0, 3.0], told(0.10)),
            (8, FLAG_NEW, 0.0, -0.4, [0.0, 0.0], [4.0, 5.0, 6.0], told(0.20)),
            (7, 0, 1.0, -0.3, [0.0, 0.0], [7.0, 8.0, 9.0], told(0.30)),
            (7, FLAG_DONE, 2.0, 0.0, [0.0, 0.0], [10.0, 11.0, 12.0], told(0.40)),
            (8, FLAG_TRUNCATED, 3.0, 0.0, [0.0, 0.0], [13.0, 14.0, 15.0], told(0.50)),
        ],
        [(0, [0.1, 0.2]), (1, [0.3, 0.4])],
        **options,
    )


class ShardTest(unittest.TestCase):

    def test_the_header_says_how_many_privileged_floats_a_row_carries(self):
        with tempfile.TemporaryDirectory() as folder:
            header = read_header(two_fights(Path(folder) / "r0003-w01.mbr"))

        self.assertEqual(header.version, 2)
        self.assertEqual(header.privileged, PRIVILEGED)
        self.assertEqual(header.obs_dim, OBS_DIM)
        self.assertEqual(header.rows, 5)
        self.assertEqual(header.steps, 3)

    def test_a_shard_of_the_older_format_is_refused_by_version(self):
        """No legacy shard is worth reading: a shard is one iteration's experience, learned from and thrown away within the
        minute. Filling the new columns with zeroes would tell the critic there was no opponent and no clock, so the reader
        says which version it found instead."""

        with tempfile.TemporaryDirectory() as folder:
            path = two_fights(Path(folder) / "old.mbr", version=1, privileged=0)

            with self.assertRaises(ValueError) as refused:
                read_header(path)

        self.assertIn("version 1", str(refused.exception))
        self.assertIn("2", str(refused.exception))

    def test_every_row_comes_back_with_its_own_privileged_columns(self):
        with tempfile.TemporaryDirectory() as folder:
            header, segments = read_shard(two_fights(Path(folder) / "r0003-w01.mbr"))

        self.assertEqual(len(segments), 2)

        ended, cut = segments[0], segments[1]

        self.assertEqual(ended.key, (3, 1, 7))
        self.assertEqual(ended.steps, 2)
        self.assertTrue(ended.done)

        # One row of columns per observation, the row the fight ended on included: that row is what a cut fight is
        # bootstrapped from, so the critic has to be able to price it.
        self.assertEqual(ended.privileged.shape, (3, PRIVILEGED))
        self.assertEqual(ended.obs.shape, (3, OBS_DIM))

        for row, first in enumerate((0.10, 0.30, 0.40)):
            self.assertEqual([round(float(value), 4) for value in ended.privileged[row]], told(first))

        # And the other agent's rows went to the other agent, which is the whole reason a row says who it belongs to.
        self.assertEqual(cut.key, (3, 1, 8))
        self.assertTrue(cut.new)
        self.assertFalse(cut.done)
        self.assertEqual([round(float(value), 4) for value in cut.privileged[0]], told(0.20))
        self.assertEqual([round(float(value), 4) for value in cut.privileged[1]], told(0.50))

    def test_a_row_that_is_a_word_short_is_refused_rather_than_reshaped(self):
        """The privileged width is in the header, so a file written with a different one has a length that does not divide.
        It is caught by arithmetic before a single column is read."""

        with tempfile.TemporaryDirectory() as folder:
            path = two_fights(Path(folder) / "r0003-w01.mbr")
            data = bytearray(path.read_bytes())
            struct.pack_into("<I", data, 4 * 15, PRIVILEGED + 1)
            path.write_bytes(bytes(data))

            with self.assertRaises(ValueError) as refused:
                read_shard(path)

        self.assertIn("its header describes", str(refused.exception))

    # ---------------------------------------------------------------------------------------------------------------
    # None of it reaches the agent
    # ---------------------------------------------------------------------------------------------------------------

    @unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
    def test_the_actor_is_exactly_the_network_it_was_before_any_of_this(self):
        """The privileged columns are the critic's alone. The actor is what gets exported and what the game runs, so if it
        had grown a single input the parity fixture, the weight format and every trained network would all be void."""

        schema = Schema.load(SCHEMA)
        actor = Actor.for_schema(schema, H1, MEMORY, H3)

        self.assertEqual(actor.topology.obs_dim, schema.obs_dim)
        self.assertEqual(actor.topology.size(), PARAMETERS)

        # Everything the file carries: the learned parameters and the observation normaliser, which travels with them
        # because the network is meaningless behind any other one.
        learned = sum(parameter.numel() for parameter in actor.parameters())

        self.assertEqual(learned + actor.norm_mean.numel() + actor.norm_std.numel(), PARAMETERS)

        # The parameters the file holds, in the order the game reads them: nothing has been added to either end.
        self.assertEqual(sorted(name for name, _ in actor.named_parameters()),
                         ["fc1.bias", "fc1.weight", "fc2.bias", "fc2.weight", "gru.bias_hh_l0", "gru.bias_ih_l0",
                          "gru.weight_hh_l0", "gru.weight_ih_l0", "log_std", "out.bias", "out.weight"])
        self.assertEqual(sum(part.size for part in weight_segments(actor)), PARAMETERS)

    @unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
    def test_an_exported_network_holds_the_actor_and_nothing_else(self):
        schema = Schema.load(SCHEMA)
        actor = Actor.for_schema(schema, H1, MEMORY, H3)

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "000042.mbw"

            export_weights(path, actor, schema.schema_id, 42)
            header, flat = read_weights(path)

            # A 68-byte header and the parameters, to the byte: there is no room in it for anything of the critic's.
            self.assertEqual(path.stat().st_size, 68 + 4 * PARAMETERS)

        self.assertEqual(header.schema_id, schema.schema_id)
        self.assertEqual(header.topology.obs_dim, schema.obs_dim)
        self.assertEqual(flat.size, PARAMETERS)

    @unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
    def test_the_actors_answer_does_not_depend_on_anything_privileged(self):
        """It cannot, since it is never handed any of it. Said out loud because it is the one property the whole exercise
        rests on: the exported network has to behave in the game exactly as it did here."""

        schema = Schema.load(SCHEMA)
        actor = Actor.for_schema(schema, 16, 8, 8)
        obs = torch.zeros(1, 4, schema.obs_dim)
        hidden = torch.zeros(1, 8)

        with torch.no_grad():
            logits, _ = actor(obs, hidden)

        self.assertEqual(tuple(logits.shape), (1, 4, schema.logit_dim))
        self.assertEqual(list(inspect.signature(actor.forward).parameters), ["raw_obs", "hidden"])

    # ---------------------------------------------------------------------------------------------------------------
    # The two sides of the block
    # ---------------------------------------------------------------------------------------------------------------

    @unittest.skipUnless(FACTS.is_file(), "needs the game's source")
    def test_the_game_and_the_trainer_name_the_same_columns_in_the_same_order(self):
        """The columns are floats in a row, so a name that moved on one side and not the other is a critic quietly reading
        armour as a fuse. The game's FightFacts is the only place any of them is argued for; this is the check that this
        side's list still is that one."""

        source = FACTS.read_text(encoding="utf-8")
        size = re.search(r"int SIZE = (\d+);", source)

        self.assertIsNotNone(size)
        self.assertEqual(int(size.group(1)), PRIVILEGED)

        for index, name in enumerate(SHARD_PRIVILEGED):
            with self.subTest(column=name):
                self.assertRegex(source, rf"int {name.upper()} = {index};")


if __name__ == "__main__":
    unittest.main()

"""The shared encoder over the enemy slots: its shape, its file, and that it sees a squad the same way round either way.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import torch

from mmai.model import Actor
from mmai.schema import Schema
from mmai.weights import Topology, export, read

SCHEMA = Path(__file__).resolve().parents[2] / "mod" / "fabric" / "build" / "brain-parity" / "check" / "humanoid" / "schema.json"


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class SlotEncoderTest(unittest.TestCase):
    def setUp(self) -> None:
        self.schema = Schema.load(SCHEMA)
        self.enemies = self.schema.require("enemies")

    def actor(self, slot_enc: int) -> Actor:
        return Actor.for_schema(self.schema, h1=32, hidden=16, h3=16, slot_enc=slot_enc)

    def test_a_plain_network_is_exactly_what_it_always_was(self):
        """Adding a shape to the format must not change the identity of the shape that was already there, or every network
        trained before it would be refused for no reason."""

        plain = self.actor(0).topology
        before = Topology(self.schema.obs_dim, 32, 16, 16, self.schema.logit_dim, self.schema.std_dim)

        self.assertFalse(plain.pooled())
        self.assertEqual(plain.hash(), before.hash())
        self.assertEqual(plain.size(), before.size())
        self.assertEqual(plain.fc1_in(), self.schema.obs_dim)

    def test_pooling_takes_the_slots_out_and_puts_features_in(self):
        topology = self.actor(64).topology
        slots, stride = self.enemies.facts["slots"], self.enemies.facts["stride"]

        self.assertTrue(topology.pooled())
        self.assertEqual(topology.slot_at, self.enemies.offset)
        self.assertEqual(topology.fc1_in(), self.schema.obs_dim - slots * stride + 64)

    def test_it_is_cheaper_at_a_real_width_and_dearer_at_a_toy_one(self):
        """Worth knowing before choosing a width. The encoder costs a fixed slots x stride x width however small the rest
        of the network is, while what it saves on the first layer is (slots x stride - width) per unit of h1. So it pays
        once h1 is past about 82 at a width of 64, which every real network is and the toy ones in these tests are not."""

        def macs(h1, slot_enc):
            return Actor.for_schema(self.schema, h1=h1, hidden=16, h3=16, slot_enc=slot_enc).topology.macs_per_step()

        self.assertLess(macs(256, 64), macs(256, 0), "pooling should be cheaper at the width networks actually use")
        self.assertGreater(macs(32, 64), macs(32, 0), "and dearer at a width where the first layer is tiny")

    def occupied(self, stride: int) -> torch.Tensor:
        """One slot with something in it: the present flag set, and the rest whatever."""

        slot = torch.randn(stride)
        slot[0] = 1.0
        return slot

    def test_a_squad_reads_the_same_whichever_slots_it_sits_in(self):
        """The whole point: two opponents in slots 0 and 1 must reach the first layer as the same thing as the same two in
        slots 4 and 7. A plain first layer cannot do this, which is what 84% against one skeleton and 9% against two was."""

        actor = self.actor(64).eval()
        slots, stride = self.enemies.facts["slots"], self.enemies.facts["stride"]
        at = self.enemies.offset

        one = torch.zeros(1, self.schema.obs_dim)
        one[0, :at] = torch.randn(at)
        first, second = self.occupied(stride), self.occupied(stride)

        one[0, at:at + stride] = first
        one[0, at + stride:at + 2 * stride] = second

        other = one.clone()
        other[0, at:at + slots * stride] = 0.0
        other[0, at + 4 * stride:at + 5 * stride] = second
        other[0, at + 7 * stride:at + 8 * stride] = first

        with torch.no_grad():
            self.assertTrue(torch.allclose(actor.encode(one, one), actor.encode(other, other), atol=1e-6))

    def test_an_empty_slot_cannot_win_the_pooling_through_its_bias(self):
        """An empty slot is all zeros, so the encoder answers it with ReLU(bias). Unmasked, every feature whose bias came
        out positive would be won by slots with nothing in them, and the network's view of the worst thing in view would be
        partly noise from empty air. With the bias driven positive on purpose, the masked pooling must still ignore them."""

        actor = self.actor(64).eval()
        stride = self.enemies.facts["stride"]
        at = self.enemies.offset

        with torch.no_grad():
            actor.slot_encoder.bias.fill_(5.0)

        empty = torch.zeros(1, self.schema.obs_dim)

        with torch.no_grad():
            nothing = actor.encode(empty, empty)[0, at:at + 64]

            one = empty.clone()
            one[0, at:at + stride] = self.occupied(stride)
            something = actor.encode(one, one)[0, at:at + 64]

        self.assertTrue(torch.all(nothing == 0.0), "empty air reached the first layer as a positive feature")
        self.assertGreater(float(something.abs().sum()), 0.0, "an occupied slot pooled to nothing")

    def test_the_mask_is_taken_from_the_raw_row_not_the_normalised_one(self):
        """After normalising, an absent slot's present flag is (0 - mean) / std, which is not zero and can be well above the
        half a naive mask would test against. Reading the mask from the raw row is what keeps that from marking empty slots
        occupied."""

        actor = self.actor(64).eval()
        at, stride = self.enemies.offset, self.enemies.facts["stride"]

        with torch.no_grad():
            actor.norm_mean.fill_(0.0)
            actor.norm_mean[at] = -3.0      # so an absent flag normalises to +3, far above any sensible threshold
            actor.slot_encoder.bias.fill_(5.0)

        empty = torch.zeros(1, self.schema.obs_dim)

        with torch.no_grad():
            pooled = actor.encode(actor.normalise(empty), empty)[0, at:at + 64]

        self.assertTrue(torch.all(pooled == 0.0), "a normalised absent flag was read as an occupied slot")

    def test_it_survives_a_trip_through_a_weight_file(self):
        actor = self.actor(64).eval()

        with tempfile.TemporaryDirectory() as folder:
            path = export(Path(folder) / "one.mbw", actor, schema_id=0x1234, iteration=7)
            header, flat = read(path)

            self.assertEqual(header.topology, actor.topology)
            self.assertEqual(flat.size, actor.topology.size())
            self.assertEqual(header.iteration, 7)

    def test_an_older_file_still_reads_as_a_network_without_one(self):
        plain = self.actor(0).eval()

        with tempfile.TemporaryDirectory() as folder:
            path = export(Path(folder) / "plain.mbw", plain, schema_id=0x1234, iteration=1)
            header, _ = read(path)

            self.assertFalse(header.topology.pooled())
            self.assertEqual(header.topology, plain.topology)


if __name__ == "__main__":
    unittest.main()

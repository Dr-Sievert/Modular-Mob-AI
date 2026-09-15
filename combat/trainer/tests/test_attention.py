"""Attention over the enemy slots: the arithmetic, the file, the tied statistics, and the conversion that carries a plain
run's weights into it without losing them.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test

Everything here is synthetic. The conversion was validated against blast7's real weights before it was written -- a K=1
conversion reproduced it to 6e-5 on real one-on-one rows -- but a test that reads runs\\ is a test that stops working the
day that run is deleted, so what is pinned here is the arithmetic on weights built for the purpose.
"""

from __future__ import annotations

import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from mmai.attend import EMPTY_REST, convert
from mmai.model import Actor, RunningNormalizer
from mmai.ppo import Config, Trainer
from mmai.schema import Schema
from mmai.weights import Topology, export, read

SCHEMA = Path(__file__).resolve().parents[2] / "mod" / "fabric" / "build" / "brain-parity" / "check" / "humanoid" / "schema.json"

# Small enough that a conversion and a few hundred forward passes run in a moment.
SMALL = dict(h1=32, hidden=16, h3=16, critic_width=32, threads=1, aux_coef=0.05)

# Where the fields the initial scores are written in terms of sit inside one humanoid enemy slot; the same numbers as
# mmai.attend's ordering, said again here so that a test cannot be made to pass by moving the ordering.
PRESENT, DISTANCE, KIND, TARGETS_ME = 0, 4, 12, 30
MONSTER = 0.75


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class TopologyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.schema = Schema.load(SCHEMA)
        self.enemies = self.schema.require("enemies")

    def actor(self, slot_heads: int) -> Actor:
        return Actor.for_schema(self.schema, h1=32, hidden=16, h3=16, slot_heads=slot_heads)

    def test_a_plain_network_is_exactly_what_it_always_was(self):
        """Adding a shape to the format must not change the identity of the shape that was already there, or every network
        trained before it would be refused for no reason."""

        plain = self.actor(0).topology
        before = Topology(self.schema.obs_dim, 32, 16, 16, self.schema.logit_dim, self.schema.std_dim)

        self.assertFalse(plain.attended())
        self.assertEqual(plain.hash(), before.hash())
        self.assertEqual(plain.size(), before.size())
        self.assertEqual(plain.fc1_in(), self.schema.obs_dim)

    def test_attention_takes_the_slots_out_and_puts_one_slot_per_head_in(self):
        topology = self.actor(3).topology
        slots, stride = self.enemies.facts["slots"], self.enemies.facts["stride"]

        self.assertTrue(topology.attended())
        self.assertEqual(topology.slot_at, self.enemies.offset)
        self.assertEqual(topology.fc1_in(), self.schema.obs_dim - slots * stride + 3 * stride)

    def test_it_survives_a_trip_through_a_weight_file(self):
        actor = self.actor(3).eval()

        with tempfile.TemporaryDirectory() as folder:
            path = export(Path(folder) / "one.mbw", actor, schema_id=0x1234, iteration=7)
            header, flat = read(path)

            self.assertEqual(header.topology, actor.topology)
            self.assertEqual(flat.size, actor.topology.size())
            self.assertEqual(header.iteration, 7)

    def older(self, folder: str, version: int) -> Path:
        """A plain network written out and then aged: version 3 and version 2 have the same header, and version 1 is that
        header without the four slot numbers."""

        path = export(Path(folder) / "old.mbw", self.actor(0).eval(), schema_id=0x1234, iteration=1)
        data = bytearray(path.read_bytes())
        data[4:8] = struct.pack("<I", version)

        if version == 1:
            del data[40:56]

        path.write_bytes(bytes(data))
        return path

    def test_an_older_plain_file_still_reads(self):
        """Every network ever published is one of these, and they go on loading: the four slot numbers are zero in both
        older versions, which is exactly a plain network."""

        with tempfile.TemporaryDirectory() as folder:
            for version in (1, 2):
                header, _ = read(self.older(folder, version))

                self.assertFalse(header.topology.attended())
                self.assertEqual(header.topology, self.actor(0).topology)

    def test_a_max_pooled_file_is_refused_by_name(self):
        """In version 2 the fourth number was the width of a max-pooled encoder and in version 3 it is a count of heads:
        the same four bytes meaning two different things. No such network was ever published, so the answer is to say so
        rather than to read a pooling matrix as a row of scores."""

        with tempfile.TemporaryDirectory() as folder:
            path = self.older(folder, 2)
            data = bytearray(path.read_bytes())
            data[40:56] = struct.pack("<4I", self.enemies.offset, 10, 31, 16)
            path.write_bytes(bytes(data))

            with self.assertRaises(ValueError) as refused:
                read(path)

            self.assertIn("max-pooled", str(refused.exception))


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class TiedStatisticsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.schema = Schema.load(SCHEMA)
        enemies = self.schema.require("enemies")
        self.at, self.slots, self.stride = enemies.offset, enemies.facts["slots"], enemies.facts["stride"]

    def test_the_tied_statistics_count_occupied_slots_only_and_reach_every_slot(self):
        """Per slot the statistics are useless to an attended network: in a one-on-one fight slots 2..9 hold nothing on
        every row of the run, so their means go to zero and their spreads to the floor. Tied, they are the statistics of a
        body -- and an empty slot must not be counted as one, or they would be the statistics of empty air instead."""

        normalizer = RunningNormalizer(self.schema.obs_dim, slots=(self.at, self.slots, self.stride))
        rows = torch.zeros(4, self.schema.obs_dim)

        # One body in slot 0 on every row, and on two of them a second in slot 6. Every occupied slot counts once,
        # wherever it sits; the eight empty ones on each row count not at all.
        occupied = []

        for row in range(4):
            body = torch.arange(self.stride, dtype=torch.float32) / 10.0 + row
            body[PRESENT] = 1.0
            rows[row, self.at : self.at + self.stride] = body
            occupied.append(body)

            if row % 2 == 0:
                other = body + 5.0
                other[PRESENT] = 1.0
                rows[row, self.at + 6 * self.stride : self.at + 7 * self.stride] = other
                occupied.append(other)

        normalizer.update(rows)

        wanted = torch.stack(occupied).double()

        # The count starts at the same epsilon the untied one does, so that nothing is ever divided by nothing.
        self.assertAlmostEqual(normalizer.tied_count, len(occupied), places=6)
        self.assertTrue(torch.allclose(normalizer.tied_mean, wanted.mean(0), atol=1e-6))
        self.assertTrue(torch.allclose(normalizer.tied_var, wanted.var(0, unbiased=False), atol=1e-6))

        # And they are what every slot of the exported network normalises by, so the file's layout and the game's
        # normalise step are unchanged: the game applies ten copies of one set without having to know they are tied.
        actor = Actor.for_schema(self.schema, h1=32, hidden=16, h3=16, slot_heads=3)
        normalizer.into(actor)

        first = actor.norm_mean[self.at : self.at + self.stride]

        for slot in range(1, self.slots):
            block = actor.norm_mean[self.at + slot * self.stride : self.at + (slot + 1) * self.stride]
            self.assertTrue(torch.equal(first, block), f"slot {slot} normalises by something of its own")

        self.assertTrue(torch.allclose(first, normalizer.tied_mean.float(), atol=1e-6))

        # Outside the slots nothing is tied to anything.
        self.assertTrue(torch.allclose(actor.norm_mean[: self.at], normalizer.mean[: self.at].float(), atol=1e-6))

    def test_a_plain_network_keeps_the_statistics_it_always_had(self):
        normalizer = RunningNormalizer(self.schema.obs_dim)
        normalizer.update(torch.randn(8, self.schema.obs_dim))

        actor = Actor.for_schema(self.schema, h1=32, hidden=16, h3=16)
        normalizer.into(actor)

        self.assertTrue(torch.allclose(actor.norm_mean, normalizer.mean.float(), atol=1e-6))
        self.assertNotIn("tied", str(actor.topology))

    def test_an_older_state_loads(self):
        """A state written before the slots could be tied carries three keys, not six, and a run resuming from one must
        not stop for it."""

        normalizer = RunningNormalizer(self.schema.obs_dim, slots=(self.at, self.slots, self.stride))
        normalizer.load_state_dict({"mean": torch.zeros(self.schema.obs_dim, dtype=torch.float64),
                                    "var": torch.ones(self.schema.obs_dim, dtype=torch.float64), "count": 12.0})

        self.assertEqual(normalizer.count, 12.0)
        self.assertEqual(normalizer.tied_mean.shape[0], self.stride)


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class ConversionTest(unittest.TestCase):
    """A plain network carried over into an attended one: exactly where the old network's slot k held what head k now
    reads, and -- the whole point -- unmoved by idle bodies standing about, which the plain network is not."""

    HEADS = 3

    def setUp(self) -> None:
        self.schema = Schema.load(SCHEMA)
        enemies = self.schema.require("enemies")
        self.at, self.slots, self.stride = enemies.offset, enemies.facts["slots"], enemies.facts["stride"]
        self.rng = np.random.default_rng(11)

        self.folder = tempfile.TemporaryDirectory()
        source = Path(self.folder.name) / "plain"
        self.plain = self.write_plain_run(source)
        self.attended = self.convert(source, Path(self.folder.name) / "attended")

    def tearDown(self) -> None:
        self.folder.cleanup()

    # -----------------------------------------------------------------------------------------------------------
    # The fixture
    # -----------------------------------------------------------------------------------------------------------

    def write_plain_run(self, into: Path) -> Actor:
        """A plain run of the shape blast7 is, with the statistics a run of one-on-one fights actually produces: slot 0
        wide, because the opponent is always in it, and slots 1..9 narrow and near zero, because they are nearly always
        empty. That difference is what the conversion has to undo, and a fixture with one set of statistics everywhere
        would prove nothing about it."""

        one = Trainer(Config(device="cpu", **SMALL, seed=5), self.schema)
        normalizer = one.normalizer

        # One step of nothing in particular, so that Adam has moments for the conversion to carry over: a run that has
        # trained for thirty thousand iterations has them for every parameter, and a freshly built one has none at all.
        total = sum(parameter.sum() for parameter in list(one.actor.parameters()) + list(one.critic.parameters()))
        one.optimizer.zero_grad(set_to_none=True)
        total.backward()
        one.optimizer.step()

        mean = self.rng.normal(0.0, 0.2, self.schema.obs_dim)
        spread = self.rng.uniform(0.3, 1.0, self.schema.obs_dim)

        for slot in range(self.slots):
            block = slice(self.at + slot * self.stride, self.at + (slot + 1) * self.stride)

            if slot == 0:
                mean[block] = self.rng.normal(0.0, 0.3, self.stride)
                spread[block] = self.rng.uniform(0.3, 0.8, self.stride)

            else:
                # Just above the floor rather than on it, so that a body arriving in slot 1 does not clip: the clip is
                # real and is part of why the plain network reads a crowd so badly, but it is not what this is measuring.
                mean[block] = self.rng.normal(0.0, 0.05, self.stride)
                spread[block] = self.rng.uniform(0.2, 0.3, self.stride)

        normalizer.mean = torch.from_numpy(mean)
        normalizer.var = torch.from_numpy(spread**2)
        normalizer.count = 1e6
        normalizer.into(one.actor)

        with torch.no_grad():
            # A first layer whose bias is not zero, since the bias is what the conversion folds into.
            one.actor.fc1.bias.copy_(torch.randn(one.actor.fc1.bias.shape) * 0.5)
            one.actor.out.weight.mul_(50.0)

        into.mkdir(parents=True)
        (into / "schema.json").write_bytes(SCHEMA.read_bytes())
        one.save(into / "state.pt")

        return one.actor.eval()

    def convert(self, source: Path, into: Path) -> Actor:
        convert(source, into, self.HEADS)

        state = torch.load(into / "state.pt", map_location="cpu", weights_only=False)
        actor = Actor.for_schema(self.schema, SMALL["h1"], SMALL["hidden"], SMALL["h3"], slot_heads=self.HEADS)
        actor.load_state_dict(state["actor"])

        self.converted = into

        return actor.eval()

    def body(self, engaged: bool, distance: float) -> np.ndarray:
        """One occupant of a slot: a monster, at a distance the score can tell from another's, with the rest of its fields
        whatever. Magnitudes a real slot holds, so that nothing clips on either side."""

        slot = self.rng.uniform(-1.0, 1.0, self.stride).astype(np.float32)
        slot[PRESENT] = 1.0
        slot[DISTANCE] = distance
        slot[KIND] = MONSTER
        slot[TARGETS_ME] = 1.0 if engaged else 0.0

        return slot

    def rows(self, count: int = 24, bodies: tuple = ()) -> np.ndarray:
        """Rows of observation with the named slots occupied and every other slot empty, which is what the game writes."""

        obs = (self.rng.standard_normal((count, self.schema.obs_dim)) * 0.3).astype(np.float32)
        obs[:, self.at : self.at + self.slots * self.stride] = 0.0

        for slot, engaged, distance in bodies:
            obs[:, self.at + slot * self.stride : self.at + (slot + 1) * self.stride] = self.body(engaged, distance)

        return obs

    def logits(self, actor: Actor, obs: np.ndarray) -> torch.Tensor:
        with torch.no_grad():
            out, _ = actor(torch.from_numpy(obs).unsqueeze(1), torch.zeros(obs.shape[0], SMALL["hidden"]))

        return out.squeeze(1)

    def apart(self, first: np.ndarray, second: np.ndarray | None = None) -> float:
        """How far the two networks' logits are on the same rows, at their widest."""

        return float((self.logits(self.attended, first if second is None else second)
                      - self.logits(self.plain, first)).abs().max())

    # -----------------------------------------------------------------------------------------------------------
    # What it has to be
    # -----------------------------------------------------------------------------------------------------------

    def test_one_engaged_body_is_the_network_it_came_from(self):
        """The fight every network was trained on: one opponent, in slot 0, and nine empty slots. Head 0 reads the
        opponent through the same statistics and the same columns, and the heads after it read the empty token, whose
        contribution is exactly what the conversion folded into the first layer's bias. Nothing here is an approximation
        of the old network; it is the old network."""

        self.assertLess(self.apart(self.rows(bodies=((0, True, 0.3),))), 1e-5)

    def test_idle_bodies_do_not_move_it(self):
        """The whole point. The same rows with nine idle bodies written into slots 1..9 have to reach the first layer as
        the rows without them: heads 1 and 2 rank what head 0 left, an idle body scores under the empty token, and so the
        network's function of the slots does not know the crowd is there.

        The plain network on the same rows is what this is worth: it is measured beside it, because an absolute tolerance
        says nothing without the thing it is an improvement on."""

        alone = self.rows(bodies=((0, True, 0.3),))
        crowd = alone.copy()

        for slot in range(1, self.slots):
            crowd[:, self.at + slot * self.stride : self.at + (slot + 1) * self.stride] = self.body(
                engaged=False, distance=float(self.rng.uniform(8.0, 30.0) / 32.0))

        moved = self.apart(alone, crowd)
        plainly_moved = float((self.logits(self.plain, crowd) - self.logits(self.plain, alone)).abs().max())

        self.assertLess(moved, 1e-5, "idle bodies reached the first layer")
        self.assertLess(moved * 100, plainly_moved, "the crowd moved the attended network as much as the plain one")

    def test_a_second_engaged_body_reaches_the_layer_it_used_to(self):
        """Two opponents, in slots 0 and 1, where the old network read them with two sets of statistics and two sets of
        columns. Head 0 takes the nearer, head 1 what is left, and the rescaling of slot 1's columns puts it where it was.
        Not exact, and cannot be: each head's softmax leaves a little of the other body in, which is what a head reading
        one slot out of several costs and what the run is about to learn to use."""

        self.assertLess(self.apart(self.rows(bodies=((0, True, 0.1), (1, True, 0.9)))), 1e-4)

    def test_a_body_in_slot_four_is_the_same_body(self):
        """Position independence, which the plain network has none of: the same opponent alone in slot 4 must read as the
        same opponent alone in slot 0, and the old network's answer to the second is the one to match."""

        alone = self.rows(bodies=((0, True, 0.3),))
        moved = alone.copy()
        moved[:, self.at : self.at + self.stride] = 0.0
        moved[:, self.at + 4 * self.stride : self.at + 5 * self.stride] = alone[:, self.at : self.at + self.stride]

        self.assertLess(self.apart(alone, moved), 1e-5)

    def test_each_head_reads_a_different_body(self):
        """The exclusion, which is what stops three heads from all reading the most dangerous thing in view.

        Near enough rather than equal: a head's softmax leaves a few thousandths of the other body in, which is what makes
        the choice differentiable at all."""

        near, far = (0, True, 0.1), (6, True, 0.8)
        obs = torch.from_numpy(self.rows(count=4, bodies=(near, far)))

        with torch.no_grad():
            encoded = self.attended.encode(self.attended.normalise(obs), obs)
            normalised = self.attended.normalise(obs)

        slot = lambda index: normalised[:, self.at + index * self.stride : self.at + (index + 1) * self.stride]
        head = lambda index: encoded[:, self.at + index * self.stride : self.at + (index + 1) * self.stride]

        self.assertLess(float((head(0) - slot(0)).abs().max()), 1e-3, "the first head did not read the nearer body")
        self.assertLess(float((head(1) - slot(6)).abs().max()), 1e-3, "the second head read the same body again")
        self.assertTrue(torch.allclose(head(2), self.attended.slot_empty(), atol=1e-5), "the third head found a body")

    def test_with_nothing_occupied_every_head_reads_the_empty_token(self):
        """A row with nothing in view at all is a thing the network can recognise, and it is not a row of zeros: an
        all-zero slot is a body standing exactly at the agent's eye."""

        obs = torch.from_numpy(self.rows(count=4))

        with torch.no_grad():
            encoded = self.attended.encode(self.attended.normalise(obs), obs)

        for index in range(self.HEADS):
            block = encoded[:, self.at + index * self.stride : self.at + (index + 1) * self.stride]
            self.assertTrue(torch.equal(block, self.attended.slot_empty().expand_as(block)), f"head {index}")

    def test_an_idle_body_loses_to_the_empty_token_and_an_engaged_one_beats_it(self):
        """The ordering the starting scores are chosen for, which is what the number is in aid of: after head 0 has taken
        the opponent, a head reads another body only if that body is in the fight too."""

        state = torch.load(self.converted / "state.pt", map_location="cpu", weights_only=False)
        score_w = state["actor"]["attention.score_w"]
        score_b = state["actor"]["attention.score_b"]

        def score(head: int, engaged: bool, distance: float) -> float:
            raw = torch.from_numpy(self.body(engaged, distance))
            mean = self.attended.norm_mean[self.at : self.at + self.stride]
            spread = self.attended.norm_std[self.at : self.at + self.stride]
            z = ((raw - mean) / spread).clamp(-10.0, 10.0)

            return float(score_w[head] @ z + score_b[head])

        for distance in (0.0, 0.5, 1.0):
            self.assertGreater(score(1, True, distance), EMPTY_REST, f"an engaged body at {distance} lost the slot")
            self.assertLess(score(1, False, distance), EMPTY_REST, f"an idle body at {distance} took the slot")

        # And head 0 reads whatever is there, engaged or not, because something in view is always worth more than nothing.
        self.assertGreater(score(0, False, 1.0), float(state["actor"]["attention.score_empty"][0]))

    def test_the_run_it_writes_is_one_a_trainer_can_carry_on(self):
        """What -Seed reads and what the workers are handed: a state of the new shape that loads, at the iteration the old
        run had reached, with a weight file beside it."""

        state = torch.load(self.converted / "state.pt", map_location="cpu", weights_only=False)

        self.assertEqual(state["config"]["slot_heads"], self.HEADS)
        self.assertEqual(state["schema_id"], self.schema.schema_id)

        one = Trainer(Config(device="cpu", **SMALL, slot_heads=self.HEADS), self.schema)
        one.load(self.converted / "state.pt")

        self.assertEqual(one.iteration, state["iteration"])
        self.assertTrue(one.actor.topology.attended())

        header, flat = read(self.converted / "weights" / f"{state['iteration']:06d}.mbw")

        self.assertEqual(header.topology, one.actor.topology)
        self.assertEqual(flat.size, header.topology.size())

    def test_what_is_behind_the_first_layer_keeps_its_momentum(self):
        """A conversion that threw Adam's moments away would restart the run with the weights it had: the layers whose
        columns moved start again, and everything behind them -- both GRUs, fc2, the output, the value head -- does not."""

        was = torch.load(Path(self.folder.name) / "plain" / "state.pt", map_location="cpu", weights_only=False)
        now = torch.load(self.converted / "state.pt", map_location="cpu", weights_only=False)

        names = [name for name, _ in Trainer(Config(device="cpu", **SMALL, slot_heads=self.HEADS),
                                             self.schema).actor.named_parameters()]

        gru = names.index("gru.weight_ih_l0")
        fc1 = names.index("fc1.weight")

        # The old state's own index for the GRU, which is two parameters earlier: attention put score_w and score_b and
        # score_empty in front of the first layer.
        self.assertEqual(now["optimizer"]["state"][gru]["exp_avg"].shape,
                         was["optimizer"]["state"][gru - 3]["exp_avg"].shape)
        self.assertTrue(torch.equal(now["optimizer"]["state"][gru]["exp_avg"],
                                    was["optimizer"]["state"][gru - 3]["exp_avg"]))
        self.assertNotIn(fc1, now["optimizer"]["state"])


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class UpdateTest(unittest.TestCase):
    def test_an_attended_network_learns_from_an_iteration(self):
        """One whole update, actor and critic, through the heads and the exclusion and the empty token.

        Worth a test of its own because of how the scores of the slots a head may not read are masked: minus infinity,
        which exponentiates to exactly zero, and which is one careless subtraction away from the NaN that takes a run's
        every parameter with it. The statistics move afterwards as well, which for an attended network means the tied ones.
        """

        from mmai.model import SHARD_PRIVILEGED
        from mmai.rollout import Segment

        one = Trainer(Config(device="cpu", **SMALL, slot_heads=3, seq_len=8, minibatch_chunks=4, epochs=1,
                             min_steps=1, seed=2), Schema.load(SCHEMA))
        rng = np.random.default_rng(4)
        steps = 24

        def noise() -> np.ndarray:
            """Rows of nothing in particular, which is all an update needs -- except that whatever a choice is masked by
            has to allow the choice the rows say was made, or the log probability of it is minus infinity."""

            obs = rng.standard_normal((steps + 1, one.schema.obs_dim)).astype(np.float32)

            for head in one.schema.categorical_heads:
                if head.mask >= 0:
                    obs[:, head.mask : head.mask + head.size] = 1.0

            return obs

        segments = [
            Segment(
                key=(1, 0, index),
                obs=noise(),
                privileged=np.tile(np.arange(1, len(SHARD_PRIVILEGED) + 1, dtype=np.float32) / 10.0, (steps + 1, 1)),
                actions=np.zeros((steps, one.schema.act_dim), dtype=np.float32),
                log_probs=np.zeros(steps, dtype=np.float32),
                rewards=np.ones(steps, dtype=np.float32),
                h0=np.zeros(one.config.hidden, dtype=np.float32),
                done=True,
                new=True,
            )
            for index in range(4)
        ]

        before = one.actor.attention.score_w.detach().clone()
        stats = one.update(segments)

        self.assertNotIn("skipped", stats)
        self.assertEqual(one.not_finite(), "")
        self.assertFalse(torch.equal(one.actor.attention.score_w, before), "the scores took no gradient")
        self.assertGreater(one.normalizer.tied_count, 0.5, "the tied statistics saw none of the iteration")


if __name__ == "__main__":
    unittest.main()

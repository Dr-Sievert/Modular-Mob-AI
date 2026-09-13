"""The auxiliary predictions off the actor's memory: what each one is asked, what it is not asked, and that the network the
game runs is untouched by any of it.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import logging
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path

import numpy as np
import torch

from mmai import weights
from mmai.model import SHARD_PRIVILEGED, AuxiliaryHeads, masked_mean
from mmai.ppo import Config, Trainer
from mmai.rollout import Segment
from mmai.schema import Schema

SCHEMA = Path(__file__).resolve().parents[2] / "mod" / "fabric" / "build" / "brain-parity" / "check" / "humanoid" / "schema.json"

# Small enough that an update is a moment, wide enough that the memory is a real GRU.
SMALL = dict(device="cpu", h1=32, hidden=16, h3=16, critic_width=32, seq_len=4, scale_rewards=False)


def trainer(**config) -> Trainer:
    return Trainer(Config(**{**SMALL, **config}), Schema.load(SCHEMA))


@contextmanager
def quiet():
    """An update over made up log probabilities warns loudly that the two sides disagree about the network, which is right
    in a run and only noise here."""

    root = logging.getLogger("mmai")
    was = root.level
    root.setLevel(logging.ERROR)

    try:
        yield
    finally:
        root.setLevel(was)


def fight(one: Trainer, steps: int, *, done: bool, agent: int = 7, mark: float = 1.0, seed: int = 0) -> Segment:
    """One made up stretch of a fight.

    Every observation carries its own row number in the first two numbers of the body's own block, one positive and one
    negative, so a target can be told from its neighbour and from the next fight's. The rest is noise, so that a network
    run over it does something.
    """

    schema = one.schema
    body = schema.block("self") or schema.blocks[0]
    random = np.random.default_rng(seed)

    obs = (0.01 * random.standard_normal((steps + 1, schema.obs_dim))).astype(np.float32)
    rows = np.arange(steps + 1, dtype=np.float32) + 1.0

    obs[:, body.offset] = mark * rows
    obs[:, body.offset + 1] = -mark * rows

    actions = (0.1 * random.standard_normal((steps, schema.act_dim))).astype(np.float32)

    # A categorical control is a choice and not a number to be jittered: off its own range, the replay's log probability
    # asks a distribution for an index it has not got.
    for head in schema.categorical_heads:
        actions[:, head.action] = random.integers(0, head.size, steps)

    return Segment(
        key=(1, 0, agent),
        obs=obs,
        # The game's own privileged columns. Nothing here asks anything of them -- they reach the critic and never the
        # memory these heads shape -- but a segment carries them, so one row per observation of nothing in particular.
        privileged=np.zeros((steps + 1, len(SHARD_PRIVILEGED)), dtype=np.float32),
        actions=actions,
        log_probs=np.zeros(steps, dtype=np.float32),
        rewards=(np.arange(steps, dtype=np.float32) + 1.0) / 10.0,
        h0=np.zeros(one.config.hidden, dtype=np.float32),
        done=done,
        new=True,
    )


def batch(one: Trainer, segments: list[Segment]) -> dict:
    """The batch an update learns from, caught on its way into the learning step.

    Taken from a real update rather than assembled here, so that what these tests know about is what the heads are asked
    and not how a batch is put together: the scaling, the replay and the chunking are free to change shape without a
    target test having to be rewritten. The targets are built before any gradient and from the normaliser as it stood, so
    catching them here and letting the update carry on is the same thing as reading them.
    """

    caught = {}
    learn = one._learn

    def catch(built: dict) -> dict:
        caught["batch"] = built
        return learn(built)

    one._learn = catch

    with quiet():
        one.update(segments)

    return caught["batch"]


def actor_parameters(one: Trainer) -> torch.Tensor:
    return torch.cat([parameter.detach().reshape(-1) for parameter in one.actor.parameters()])


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class AuxiliaryTargetsTest(unittest.TestCase):
    """What the heads are asked to predict, and where the answers come from."""

    def test_the_body_target_is_the_row_after_each_step_across_every_boundary(self):
        """The one that is easy to get wrong. A chunk is four steps of a segment that has six, so the target of a chunk's
        last step lives outside it, the target of a segment's last step is the row the fight ended on, and neither may be
        taken from the next chunk or the next fight."""

        one = trainer(aux_coef=0.05, seq_len=4)
        segments = [fight(one, 6, done=True), fight(one, 3, done=False, agent=9, seed=1)]
        made = batch(one, segments)

        state = made["aux_state"]
        self.assertEqual((3, 4, one.schema.block("self").size), tuple(state.shape))

        # Row numbers, so 2 is the row after step 0. The second fight starts again at 2 rather than carrying on from 7.
        self.assertEqual([2.0, 3.0, 4.0, 5.0], state[0, :, 0].tolist())
        self.assertEqual([6.0, 7.0, 0.0, 0.0], state[1, :, 0].tolist())
        self.assertEqual([2.0, 3.0, 4.0, 0.0], state[2, :, 0].tolist())

        # And the same the other way up, so it is the row being read and not the column.
        self.assertEqual([-2.0, -3.0, -4.0, -5.0], state[0, :, 1].tolist())

    def test_the_target_goes_through_the_networks_own_normaliser(self):
        """A target on one scale and an input on another are two different tasks, and the clip is part of the scale."""

        one = trainer(aux_coef=0.05, seq_len=4)
        segment = fight(one, 3, done=True, mark=4.0)
        at = one.schema.block("self").offset

        with torch.no_grad():
            one.actor.norm_mean[at] = 2.0
            one.actor.norm_std[at] = 4.0

        state = batch(one, [segment])["aux_state"]

        # Rows 2, 3 and 4 are marked 8, 12 and 16: (8 - 2) / 4 and so on. The padding is still zero, as it is everywhere
        # else in the batch, which it would not be if the statistics were applied after the gather.
        self.assertEqual([1.5, 2.5, 3.5, 0.0], state[0, :, 0].tolist())

        # The column with no statistics set is divided by one and clipped at ten, as the input is.
        self.assertEqual([-8.0, -10.0, -10.0, 0.0], state[0, :, 1].tolist())

    def test_the_reward_target_is_what_the_step_earned(self):
        one = trainer(aux_coef=0.05, seq_len=4)
        segment = fight(one, 6, done=True)
        made = batch(one, [segment])

        self.assertEqual([0.1, 0.2, 0.3, 0.4], [round(value, 4) for value in made["aux_reward"][0].tolist()])
        self.assertEqual([0.5, 0.6, 0.0, 0.0], [round(value, 4) for value in made["aux_reward"][1].tolist()])

    def test_a_fight_that_ended_is_known_throughout(self):
        one = trainer(aux_coef=0.05, aux_horizon=3, seq_len=4)
        made = batch(one, [fight(one, 6, done=True)])

        # Six steps, so the last three are within three of the end.
        self.assertEqual([0.0, 0.0, 0.0, 1.0], made["aux_ending"][0].tolist())
        self.assertEqual([1.0, 1.0, 0.0, 0.0], made["aux_ending"][1].tolist())

        self.assertEqual([True, True, True, True], made["aux_known"][0].tolist())
        self.assertEqual([True, True, False, False], made["aux_known"][1].tolist())

    def test_a_fight_that_was_only_cut_off_says_nothing_about_its_last_steps(self):
        """The other half of the same off-by-one. The recording stops with the fight still going, so every step further from
        the cut than the horizon is honestly "no end soon" and every step nearer it is not known at all -- and those are
        exactly the steps a fight cut in half was often about to finish on."""

        one = trainer(aux_coef=0.05, aux_horizon=3, seq_len=4)
        made = batch(one, [fight(one, 6, done=False)])

        self.assertEqual([0.0, 0.0, 0.0, 0.0], made["aux_ending"][0].tolist())
        self.assertEqual([0.0, 0.0, 0.0, 0.0], made["aux_ending"][1].tolist())

        self.assertEqual([True, True, True, False], made["aux_known"][0].tolist())
        self.assertEqual([False, False, False, False], made["aux_known"][1].tolist())

    def test_padding_is_never_asked_anything(self):
        one = trainer(aux_coef=0.05, aux_horizon=3, seq_len=4)
        made = batch(one, [fight(one, 6, done=True)])

        self.assertEqual([True, True, False, False], made["mask"][1].tolist())
        self.assertEqual([0.0, 0.0], made["aux_state"][1, 2:, 0].tolist())
        self.assertEqual([0.0, 0.0], made["aux_reward"][1, 2:].tolist())
        self.assertFalse(any(made["aux_known"][1, 2:].tolist()))

    def test_nothing_is_built_when_the_coefficient_is_zero(self):
        one = trainer(aux_coef=0.0)
        made = batch(one, [fight(one, 4, done=True)])

        self.assertIsNone(one.aux)
        self.assertIsNone(one.aux_optimizer)
        self.assertEqual([], [key for key in made if key.startswith("aux")])

    def test_a_body_with_no_such_block_gets_the_other_two_heads(self):
        """A layout belongs to a body. The block the first head predicts is asked for by name and simply may not be there,
        and a body without one still gets a reward and an ending to predict."""

        renamed = Schema.parse(SCHEMA.read_bytes().replace(b'"name":"self"', b'"name":"torso"'))
        one = Trainer(Config(**{**SMALL, "aux_coef": 0.05}), renamed)
        made = batch(one, [fight(one, 4, done=True)])

        self.assertIsNone(one.aux.state)
        self.assertNotIn("aux_state", made)
        self.assertEqual(["reward", "ending"], list(self.losses(one, made)))

    def losses(self, one: Trainer, made: dict) -> dict:
        _, memory = one.actor(made["obs"], made["hidden"])

        return one.aux.losses(
            memory,
            made["aux_state"] if "aux_state" in made else None,
            made["aux_reward"],
            made["aux_ending"],
            made["mask"],
            made["mask"] & made["aux_known"],
        )


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class AuxiliaryLossTest(unittest.TestCase):
    """The losses themselves, and what a mask that allows nothing does to them."""

    def test_a_mean_over_nothing_is_zero_and_not_a_nan(self):
        values = torch.tensor([1.0, 2.0, 3.0, 4.0])

        self.assertEqual(0.0, float(masked_mean(values, torch.zeros(4, dtype=torch.bool))))
        self.assertEqual(3.0, float(masked_mean(values, torch.tensor([False, True, False, True]))))

    def test_a_minibatch_with_no_known_ending_still_has_a_finite_loss(self):
        """Every chunk of a minibatch can be a fight cut short of the horizon, and the mean of nothing would take the whole
        update with it."""

        heads = AuxiliaryHeads(hidden=8, state_size=4, width=8)
        memory = torch.randn(2, 3, 8)
        mask = torch.ones(2, 3, dtype=torch.bool)

        losses = heads.losses(memory, torch.randn(2, 3, 4), torch.randn(2, 3), torch.zeros(2, 3), mask,
                              torch.zeros(2, 3, dtype=torch.bool))

        self.assertEqual(["state", "reward", "ending"], list(losses))
        self.assertEqual(0.0, float(losses["ending"].detach()))
        self.assertTrue(all(torch.isfinite(piece) for piece in losses.values()))

    def test_the_losses_reach_the_memory_they_are_meant_to_shape(self):
        heads = AuxiliaryHeads(hidden=8, state_size=4, width=8)
        memory = torch.randn(2, 3, 8, requires_grad=True)
        mask = torch.ones(2, 3, dtype=torch.bool)

        losses = heads.losses(memory, torch.randn(2, 3, 4), torch.randn(2, 3), torch.ones(2, 3), mask, mask)
        sum(losses.values()).backward()

        self.assertGreater(float(memory.grad.abs().sum()), 0.0)


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class ExportedNetworkTest(unittest.TestCase):
    """The heads are trainer-only, and this is what proves it: the game must never be handed a parameter it cannot use, and
    a weight file one number longer than the topology says is refused outright."""

    def test_the_heads_are_not_part_of_the_actor(self):
        one = trainer(aux_coef=0.05)

        self.assertNotIn(one.aux, list(one.actor.modules()))
        self.assertEqual([], [key for key in one.actor.state_dict() if "aux" in key or "ending" in key])

    def test_the_exported_network_is_exactly_what_it_was_without_them(self):
        without, with_aux = trainer(aux_coef=0.0), trainer(aux_coef=0.05)

        expected = weights.Topology(
            without.schema.obs_dim, SMALL["h1"], SMALL["hidden"], SMALL["h3"],
            without.schema.logit_dim, without.schema.std_dim,
        )

        self.assertEqual(list(without.actor.state_dict()), list(with_aux.actor.state_dict()))
        self.assertEqual(len(weights.segments(without.actor)), len(weights.segments(with_aux.actor)))
        self.assertEqual(
            sum(parameter.numel() for parameter in without.actor.parameters()),
            sum(parameter.numel() for parameter in with_aux.actor.parameters()),
        )

        with tempfile.TemporaryDirectory() as folder:
            plain = with_aux.export(Path(folder) / "plain.mbw", 3)
            beside = without.export(Path(folder) / "beside.mbw", 3)

            for path in (plain, beside):
                header, flat = weights.read(path)

                self.assertEqual(expected, header.topology)
                self.assertEqual(expected.size(), flat.size)

            self.assertEqual(plain.stat().st_size, beside.stat().st_size)

        # And the heads really do exist, or this test would pass by predicting nothing.
        self.assertGreater(sum(parameter.numel() for parameter in with_aux.aux.parameters()), 0)


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class AuxiliaryUpdateTest(unittest.TestCase):
    """What an update does with them, and what it does without them."""

    def fights(self, one: Trainer) -> list[Segment]:
        return [fight(one, 6, done=True), fight(one, 5, done=False, agent=9, seed=1),
                fight(one, 3, done=True, agent=11, seed=2)]

    def updated(self, **config) -> tuple[torch.Tensor, dict]:
        one = trainer(**config)

        # The heads draw from the random generator as they are built, so the stream is put back where a run without them
        # would have it. What this compares is the loss, not the order the chunks happen to come in.
        torch.manual_seed(one.config.seed)
        np.random.seed(one.config.seed)

        with quiet():
            stats = one.update(self.fights(one))

        return actor_parameters(one), stats

    def test_the_coefficient_at_zero_leaves_the_update_as_it_was(self):
        first, stats = self.updated(aux_coef=0.0)
        again, _ = self.updated(aux_coef=0.0)
        pulled, with_stats = self.updated(aux_coef=0.05)

        self.assertTrue(torch.equal(first, again), "the comparison itself is not deterministic")
        self.assertFalse(stats["aux"], "an update with no heads reported a prediction")
        self.assertFalse(torch.equal(first, pulled), "the auxiliary loss moved nothing")

        self.assertEqual(["state", "reward", "ending"], list(with_stats["aux"]))
        self.assertTrue(all(np.isfinite(value) for value in with_stats["aux"].values()))

    def test_the_predictions_shape_the_memory_and_not_the_layers_that_choose(self):
        """Off the GRU's output, so the gradient lands on the recurrence and the encoder under it -- the representation --
        and never on the layers between the memory and an action, which the fights alone are entitled to shape."""

        one = trainer(aux_coef=0.05)
        made = batch(one, self.fights(one))

        # Clear of the update that built the batch, so that what is left on each parameter came from the predictions alone.
        one.actor.zero_grad(set_to_none=True)

        _, memory = one.actor(made["obs"], made["hidden"])

        losses = one.aux.losses(memory, made["aux_state"], made["aux_reward"], made["aux_ending"], made["mask"],
                                made["mask"] & made["aux_known"])
        sum(losses.values()).backward()

        self.assertGreater(float(one.actor.gru.weight_ih_l0.grad.abs().sum()), 0.0)
        self.assertGreater(float(one.actor.gru.weight_hh_l0.grad.abs().sum()), 0.0)
        self.assertGreater(float(one.actor.fc1.weight.grad.abs().sum()), 0.0)

        self.assertIsNone(one.actor.fc2.weight.grad, "a prediction reached the layers that choose the action")
        self.assertIsNone(one.actor.out.weight.grad)
        self.assertIsNone(one.actor.log_std.grad)

    def test_nothing_is_predicted_while_only_the_critic_learns(self):
        """The warmup exists to hold a copied policy still until the critic is worth listening to, and a loss that moves the
        memory moves the policy with it."""

        _, stats = self.updated(aux_coef=0.05, critic_warmup=5)

        self.assertFalse(stats["aux"])

    def test_the_report_keeps_the_predictions_off_the_line_watch_reads(self):
        one = trainer(aux_coef=0.05)

        with self.assertLogs("mmai.ppo", level="INFO") as logged:
            one.report({"policy": 0.0, "value": 0.0, "entropy": 0.0, "clip": 0.0, "kl": 0.0, "epochs": 1,
                        "drift": 0.0, "seconds": 1.0, "vram": 0.0, "steps": 100,
                        "aux": {"state": 0.5, "reward": 0.25, "ending": 0.125}})

        lines = [record.getMessage() for record in logged.records]
        iterations = [line for line in lines if " steps " in line]

        self.assertEqual(1, len(iterations), "the auxiliary figures were added to the iteration line")
        self.assertTrue(any("aux  state 0.5000  reward 0.2500  ending 0.1250" in line for line in lines))


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class AuxiliaryStateTest(unittest.TestCase):
    """Saving and resuming. Two runs are training as this is written, and both must resume into it."""

    def test_a_state_written_before_the_heads_existed_still_resumes(self):
        """Adam refuses a saved state whose parameter group is a size other than the one it is loaded into, so heads in the
        main optimizer would have ended every run on the machine the moment it restarted."""

        before = trainer(aux_coef=0.0)
        before.iteration = 12

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            before.save(path)

            after = trainer(aux_coef=0.05)

            with quiet():
                after.load(path)

        self.assertEqual(12, after.iteration)
        self.assertIsNotNone(after.aux)

    def test_the_heads_and_their_moments_come_back(self):
        one = trainer(aux_coef=0.05)

        with quiet():
            one.update([fight(one, 6, done=True)])

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            one.save(path)

            two = trainer(aux_coef=0.05)

            with quiet():
                two.load(path)

        self.assertTrue(torch.equal(one.aux.shared.weight, two.aux.shared.weight))
        self.assertTrue(torch.equal(one.aux.ending.bias, two.aux.ending.bias))
        self.assertTrue(two.aux_optimizer.state_dict()["state"], "Adam's moments for the heads did not come back")

    def test_turning_them_off_on_a_resume_is_not_an_error(self):
        one = trainer(aux_coef=0.05)

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            one.save(path)

            two = trainer(aux_coef=0.0)

            with quiet():
                two.load(path)

        self.assertIsNone(two.aux)


if __name__ == "__main__":
    unittest.main()

"""What an update does with a value that is not a number, on either side of it.

blast7 died of one. A breeze in a league fight arrived at a tick with a NaN vertical velocity, vanilla's physics made that
permanent, and the enemy slot copied it into the observation for 54 ticks. The network turned one NaN input into NaN logits,
so the actions and the log probabilities of those rows were NaN too, and PPO averaged them into a minibatch loss: one Adam
step wrote a NaN into all 91 tensors of the run, the observation normaliser included. Nothing caught it -- the drift check
is ``NaN > 1e-3``, which is False -- and by the time the export refused to write non-finite weights, state.pt had already
been overwritten with the poison, so the run had nothing on disk worth resuming from.

Two guards, tested here:

- rows that are not finite are dropped before the update, and what is said about them names the field of the body's own
  schema they came from;
- an update whose loss or gradient is not finite is abandoned whole -- every parameter and every one of Adam's moments back
  where it was -- the iteration is reported as not learned from, and the run carries on until Config.skip_limit of them in
  a row.

And the line that must never be crossed again: a state that is not finite is not written, so the last good one survives.

    python -m unittest discover -s tests       from trainer/
"""

from __future__ import annotations

import copy
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from mmai.model import SHARD_PRIVILEGED
from mmai.ppo import Config, NotFinite, Trainer
from mmai.rollout import Segment
from mmai.schema import Schema

SCHEMA = Path(__file__).resolve().parents[2] / "mod" / "fabric" / "build" / "brain-parity" / "check" / "humanoid" / "schema.json"

SMALL = dict(h1=32, hidden=16, h3=16, critic_width=32, seq_len=8, minibatch_chunks=4, epochs=1, threads=1)

# The field the breeze's NaN landed in, as an offset into a humanoid's row: enemy slot 1's vertical velocity. Written out
# rather than read off the schema, because the point of the test that names it is that the name is worked out and not given.
BREEZE_VELOCITY_UP = 90


def trainer(**config) -> Trainer:
    return Trainer(Config(device="cpu", **(SMALL | config)), Schema.load(SCHEMA))


def fight(one: Trainer, steps: int, key: tuple[int, int, int] = (1, 0, 7), done: bool = True, seed: int = 0) -> Segment:
    """One stretch of a made up fight, finite throughout: noise for the observations, zeroes for the actions because a
    categorical control is an index, and the game's privileged columns counting up from a tenth."""

    told = np.arange(1, len(SHARD_PRIVILEGED) + 1, dtype=np.float32) / 10.0
    obs = np.random.default_rng(seed).standard_normal((steps + 1, one.schema.obs_dim)).astype(np.float32)

    # Every masked choice left open, because the action below is index nought of each of them. Noise would close some, the
    # replay would score a closed choice at minus infinity, and the KL of the update would be an infinity that has nothing
    # to do with what is being tested: the game never records a choice it could not have made.
    for head in one.schema.categorical_heads:
        if head.mask >= 0:
            obs[:, head.mask : head.mask + head.size] = 1.0

    return Segment(
        key=key,
        obs=obs,
        privileged=np.tile(told, (steps + 1, 1)),
        actions=np.zeros((steps, one.schema.act_dim), dtype=np.float32),
        log_probs=np.zeros(steps, dtype=np.float32),
        rewards=np.arange(1, steps + 1, dtype=np.float32),
        h0=np.zeros(one.config.hidden, dtype=np.float32),
        done=done,
        new=True,
    )


def frozen(one: Trainer) -> dict:
    """Every number an update could move, copied: the three networks and both optimizers' moments."""

    return {
        "actor": copy.deepcopy(one.actor.state_dict()),
        "critic": copy.deepcopy(one.critic.state_dict()),
        "optimizer": copy.deepcopy(one.optimizer.state_dict()),
        "aux": copy.deepcopy(one.aux.state_dict()) if one.aux is not None else {},
    }


def unmoved(case: unittest.TestCase, before: dict, after: dict) -> None:
    """Every tensor of two such snapshots equal to the bit, Adam's moments included."""

    for part in ("actor", "critic", "aux"):
        case.assertEqual(sorted(before[part]), sorted(after[part]), f"{part} changed shape")

        for name, tensor in before[part].items():
            case.assertTrue(torch.equal(tensor, after[part][name]), f"{part}.{name} moved")

    for key, moments in before["optimizer"]["state"].items():
        for name, value in moments.items():
            other = after["optimizer"]["state"][key][name]

            if torch.is_tensor(value):
                case.assertTrue(torch.equal(value, other), f"Adam's {name} for parameter {key} moved")
            else:
                case.assertEqual(value, other, f"Adam's {name} for parameter {key} moved")


def nan_critic(one: Trainer) -> None:
    """Makes the critic answer with NaN while every one of its parameters stays a number, so what the update is asked to
    survive is arithmetic that went wrong rather than a network that was already broken."""

    real = one.critic.forward

    def nan_values(*arguments):
        values, memory = real(*arguments)
        return values * float("nan"), memory

    one.critic.forward = nan_values


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class DroppingRowsTest(unittest.TestCase):
    """The first guard: a shard's bad rows never reach the update."""

    def test_a_clean_iteration_is_handed_on_untouched(self):
        one = trainer()
        segments = [fight(one, 20), fight(one, 12, key=(1, 0, 8))]
        kept, dropped = one._finite(segments)

        self.assertEqual(dropped, 0)
        self.assertEqual([segment.steps for segment in kept], [20, 12])
        self.assertIs(kept[0], segments[0], "a clean segment should not even be copied")

    def test_a_segment_is_cut_at_its_first_bad_row_and_the_good_prefix_kept(self):
        """The shape of what happened: a fight that was fine for fourteen ticks and NaN for the other fifty four."""

        one = trainer()
        segment = fight(one, 68)
        segment.obs[14:, BREEZE_VELOCITY_UP] = np.nan

        kept, dropped = one._finite([segment])

        # Thirteen steps survive, not fourteen: a segment of n steps needs n + 1 good observations, the last of which is
        # what the cut fight is priced against, and row 14 is the bad one.
        self.assertEqual([piece.steps for piece in kept], [13])
        self.assertEqual(dropped, 68 - 13)
        self.assertEqual(kept[0].obs.shape[0], 14)
        self.assertTrue(np.isfinite(kept[0].obs).all())
        self.assertTrue(np.isfinite(kept[0].actions).all())
        self.assertTrue(np.isfinite(kept[0].log_probs).all())

    def test_a_cut_segment_is_no_longer_one_that_ended(self):
        """It was cut, not finished: the rows that ended the fight are the ones being thrown away, so the last good row is
        a position to be priced and not an outcome."""

        one = trainer()
        segment = fight(one, 30, done=True)
        segment.obs[20, BREEZE_VELOCITY_UP] = np.nan

        kept, _ = one._finite([segment])
        self.assertFalse(kept[0].done)
        self.assertTrue(kept[0].new, "where the fight started is still where it started")

    def test_a_segment_with_nothing_good_before_the_bad_row_goes_whole(self):
        one = trainer()

        for row in (0, 1):
            with self.subTest(row=row):
                segment = fight(one, 40)
                segment.obs[row, BREEZE_VELOCITY_UP] = np.nan

                kept, dropped = one._finite([segment])
                self.assertEqual(kept, [])
                self.assertEqual(dropped, 40)

    def test_a_segment_whose_starting_memory_is_not_finite_goes_whole(self):
        one = trainer()
        segment = fight(one, 40)
        segment.h0[3] = np.inf

        kept, dropped = one._finite([segment])
        self.assertEqual(kept, [])
        self.assertEqual(dropped, 40)

    def test_it_names_the_field_of_the_body_own_schema(self):
        """A row number says nothing. What the log has to say is which field, and the schema is the only thing that knows:
        offset 90 of a humanoid's row is the vertical velocity of enemy slot 1, which is where the breeze's NaN landed."""

        one = trainer()
        segment = fight(one, 30)
        segment.obs[9, BREEZE_VELOCITY_UP] = np.nan

        row, why = one._first_bad_row(segment)
        self.assertEqual(row, 9)
        self.assertIn("enemies slot 1", why)
        self.assertIn("offset 6 of 31", why)
        self.assertIn("row offset 90", why)

    def test_every_column_of_a_row_is_checked_not_only_the_observation(self):
        one = trainer()

        def spoil(what: str):
            segment = fight(one, 30)
            getattr(segment, what)[5] = np.nan if what != "actions" else np.full(one.schema.act_dim, np.nan)
            return one._first_bad_row(segment)

        for field, expected in (("privileged", "privileged column"), ("actions", "action"),
                                ("log_probs", "log probability"), ("rewards", "reward")):
            with self.subTest(field=field):
                row, why = spoil(field)
                self.assertEqual(row, 5 if field != "rewards" else 5)
                self.assertIn(expected, why)

    def test_a_privileged_column_is_named_by_the_games_own_name_for_it(self):
        one = trainer()
        segment = fight(one, 30)
        segment.privileged[7, SHARD_PRIVILEGED.index("foe_fuse")] = np.nan

        _, why = one._first_bad_row(segment)
        self.assertIn("foe_fuse", why)

    def test_the_update_survives_the_shape_of_the_failure(self):
        """The whole chain, as blast7 had it: one segment of many holding NaN observations, NaN actions and NaN log
        probabilities. The update has to finish, and nothing of the network may come out a NaN."""

        one = trainer()
        segments = [fight(one, 40, key=(1, 0, index), seed=index) for index in range(6)]

        poisoned = segments[3]
        poisoned.obs[14:, BREEZE_VELOCITY_UP] = np.nan
        poisoned.actions[14:, :4] = np.nan
        poisoned.log_probs[14:] = np.nan

        stats = one.update(segments)

        self.assertIsNone(stats.get("skipped"), "there was plenty of good experience in the iteration")
        self.assertEqual(stats["scrubbed"], 40 - 13)
        self.assertTrue(np.isfinite(stats["policy"]))
        self.assertTrue(np.isfinite(stats["value"]))
        self.assertTrue(np.isfinite(stats["kl"]))
        self.assertEqual(one.not_finite(), "")

    def test_an_iteration_that_is_all_bad_is_a_skipped_one_rather_than_a_crash(self):
        one = trainer()
        segments = [fight(one, 30, key=(1, 0, index)) for index in range(3)]

        for segment in segments:
            segment.obs[0, BREEZE_VELOCITY_UP] = np.nan

        before = frozen(one)
        stats = one.update(segments)

        self.assertIsNotNone(stats.get("skipped"))
        self.assertEqual(one.skipped_in_a_row, 1)
        unmoved(self, before, frozen(one))


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class SkippingAnUpdateTest(unittest.TestCase):
    """The second guard: an update that goes non-finite anyway is abandoned whole."""

    def learned(self, one: Trainer, key: int = 0) -> dict:
        """One ordinary update, so that Adam has moments for the restore to have to put back."""

        stats = one.update([fight(one, 24, key=(1, 0, key), seed=key), fight(one, 18, key=(1, 0, key + 100), seed=key + 1)])
        self.assertIsNone(stats.get("skipped"))
        return stats

    def test_a_non_finite_loss_leaves_every_parameter_and_every_moment_where_it_was(self):
        one = trainer()
        self.learned(one)

        before = frozen(one)
        rate = one.rate

        nan_critic(one)
        stats = one.update([fight(one, 24, key=(1, 0, 2), seed=2), fight(one, 18, key=(1, 0, 3), seed=3)])

        self.assertIsNotNone(stats.get("skipped"))
        self.assertEqual(one.rate, rate, "a skipped update says nothing about the step size")
        self.assertEqual(one.not_finite(), "")
        unmoved(self, before, frozen(one))

    def test_the_epochs_are_undone_even_when_some_of_them_had_already_stepped(self):
        """The check is between the backward pass and the step, so no bad minibatch is ever applied -- but the good ones
        before it in the same update were, and those are what the snapshot is for."""

        one = trainer(epochs=4, minibatch_chunks=1)
        self.learned(one)
        before = frozen(one)

        segments = [fight(one, 24, key=(1, 0, 4), seed=4)]
        scaled, privileged = one._scale(segments)
        batch = one._chunks(segments, one._replay(segments, scaled, privileged))

        # Good everywhere but one step, which is enough: the row is in some minibatch of some epoch, and whichever one it
        # lands in there are minibatches before it that have already stepped.
        batch["returns"][0, 0] = float("nan")

        with self.assertRaises(NotFinite):
            one._learn(batch)

        unmoved(self, before, frozen(one))

    def test_the_count_rises_on_each_one_and_resets_on_a_good_update(self):
        one = trainer()
        self.assertEqual(one.skipped_in_a_row, 0)

        real = one.critic.forward
        nan_critic(one)

        for expected in (1, 2, 3):
            one.update([fight(one, 20, key=(1, 0, expected), seed=expected)])
            self.assertEqual(one.skipped_in_a_row, expected)

        one.critic.forward = real
        self.learned(one, key=9)
        self.assertEqual(one.skipped_in_a_row, 0, "one update that learned something clears the count")

    def test_the_skip_limit_is_a_flag(self):
        self.assertEqual(Config().skip_limit, 3)
        self.assertEqual(Config(skip_limit=1).skip_limit, 1)

    def test_a_skipped_update_reports_itself_rather_than_a_line_of_nans(self):
        """How blast7's failure read in the log was a normal line with every field a NaN. A skipped iteration says so."""

        one = trainer()
        nan_critic(one)
        stats = one.update([fight(one, 20)])

        self.assertIn("skipped", stats)
        one.report(stats)  # the line is the warning; what is tested here is that reporting one does not throw


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class SavingTest(unittest.TestCase):
    """The line that must never be crossed again: the last good state stays on disk."""

    def test_a_healthy_state_is_written(self):
        one = trainer()

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.pt"
            one.save(path)
            self.assertTrue(path.is_file())
            self.assertEqual(one.not_finite(), "")

    def test_a_poisoned_state_is_refused_and_the_file_already_there_is_left_alone(self):
        one = trainer()

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.pt"
            one.save(path)
            good = path.read_bytes()

            with torch.no_grad():
                one.actor.fc1.weight[0, 0] = float("nan")

            self.assertIn("actor.fc1.weight", one.not_finite())

            with self.assertRaises(ValueError) as refusal:
                one.save(path)

            self.assertIn("refusing to write a state that is not finite", str(refusal.exception))
            self.assertEqual(path.read_bytes(), good, "the state that was there is the one to carry on from")

    def test_the_normaliser_counts_as_part_of_the_state(self):
        """It is the one piece no amount of further training would mend: a NaN mean divides every observation for ever."""

        one = trainer()
        one.normalizer.mean[7] = float("nan")
        one.normalizer.into(one.actor)

        self.assertIn("normalizer.mean", one.not_finite())

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                one.save(Path(directory) / "state.pt")


class WhereTest(unittest.TestCase):
    """Naming a row offset, which is what makes a message about one number useful."""

    @unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
    def test_it_names_a_slot_of_a_repeated_block_and_the_offset_within_it(self):
        schema = Schema.load(SCHEMA)

        self.assertEqual(schema.where(BREEZE_VELOCITY_UP),
                         "enemies slot 1, offset 6 of 31 (row offset 90)")

    @unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
    def test_it_names_a_plain_block_by_how_far_into_it_the_offset_is(self):
        schema = Schema.load(SCHEMA)
        body = schema.require("self")

        self.assertEqual(schema.where(body.offset + 3), f"self offset 3 of {body.size} (row offset {body.offset + 3})")

    @unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
    def test_an_offset_past_the_row_says_so_rather_than_throwing(self):
        schema = Schema.load(SCHEMA)
        self.assertIn("past every block", schema.where(schema.obs_dim + 1))


if __name__ == "__main__":
    unittest.main()

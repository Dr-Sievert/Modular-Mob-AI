"""The critic's own memory, what it is told that the actor is not, and which critic a resume and a seed each end up with.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from mmai.model import EPISODE_TICKS, PRIVILEGED, PRIVILEGED_COLUMNS, SHARD_PRIVILEGED
from mmai.ppo import Config, Trainer
from mmai.rollout import Segment
from mmai.schema import Schema

SCHEMA = Path(__file__).resolve().parents[2] / "mod" / "fabric" / "build" / "brain-parity" / "check" / "humanoid" / "schema.json"

# Small enough that a whole update runs in a moment, and a seq_len the fights below are several chunks of.
SMALL = dict(h1=32, hidden=16, h3=16, critic_width=32, seq_len=8, minibatch_chunks=4, epochs=1, threads=1)


def trainer(**config) -> Trainer:
    return Trainer(Config(device="cpu", **(SMALL | config)), Schema.load(SCHEMA))


def fight(one: Trainer, steps: int, key: tuple[int, int, int] = (1, 0, 7), done: bool = True, new: bool = True,
          seed: int = 0) -> Segment:
    """One stretch of a made up fight. The observations are noise, which is all the critic needs to give every row a
    different value; the rewards count 1, 2, 3 up so that a column of what was paid is readable by eye.

    The actions are nought rather than noise because a categorical control is an index: a negative one is not a choice the
    game could have made, and the replay refuses it.

    The privileged columns are the ones the game writes into a row, counting up from a tenth so that each is a different
    number and it is obvious which column ended up where.
    """

    schema = one.schema
    told = np.arange(1, len(SHARD_PRIVILEGED) + 1, dtype=np.float32) / 10.0

    return Segment(
        key=key,
        obs=np.random.default_rng(seed).standard_normal((steps + 1, schema.obs_dim)).astype(np.float32),
        privileged=np.tile(told, (steps + 1, 1)),
        actions=np.zeros((steps, schema.act_dim), dtype=np.float32),
        log_probs=np.zeros(steps, dtype=np.float32),
        rewards=np.arange(1, steps + 1, dtype=np.float32),
        h0=np.zeros(one.config.hidden, dtype=np.float32),
        done=done,
        new=new,
    )


def replay(one: Trainer, segments: list[Segment]):
    """Everything an update works out before it takes a gradient: the scaled rewards, the privileged rows, the replay and
    the chunks."""

    scaled, privileged = one._scale(segments)
    replayed = one._replay(segments, scaled, privileged)

    return privileged, replayed, one._chunks(segments, replayed)


def values_of(one: Trainer, batch: dict, hidden: torch.Tensor | None = None) -> np.ndarray:
    """What the critic makes of the chunks, laid back out as one row per step in segment order."""

    with torch.no_grad():
        values, _ = one.critic(one.actor.normalise(batch["obs"]), batch["memory"], batch["privileged"],
                               batch["critic_hidden"] if hidden is None else hidden)

    return values.reshape(-1).numpy()[batch["mask"].reshape(-1).numpy()]


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class CriticTest(unittest.TestCase):

    # ---------------------------------------------------------------------------------------------------------------
    # Its own memory
    # ---------------------------------------------------------------------------------------------------------------

    def test_the_memory_is_as_wide_as_the_actors_unless_a_width_is_asked_for(self):
        self.assertEqual(trainer().critic.gru_width, 16)
        self.assertEqual(trainer(critic_gru_width=48).critic.gru_width, 48)
        self.assertEqual(trainer(critic_gru=False).critic.gru_width, 0)

    def test_a_segment_comes_back_one_value_and_one_state_a_step(self):
        one = trainer()
        segments = [fight(one, 21)]
        privileged, replayed, batch = replay(one, segments)

        self.assertEqual(privileged[0].shape, (22, PRIVILEGED_COLUMNS))
        self.assertEqual(replayed[0].values.shape, (21,))
        self.assertEqual(replayed[0].privileged.shape, (21, PRIVILEGED_COLUMNS))
        self.assertEqual(replayed[0].critic_hidden_in.shape, (21, 16))

        # 21 steps of a seq_len of 8 is three chunks, the last one padded.
        self.assertEqual(tuple(batch["obs"].shape[:2]), (3, 8))
        self.assertEqual(tuple(batch["critic_hidden"].shape), (3, 16))
        self.assertEqual(tuple(batch["privileged"].shape), (3, 8, PRIVILEGED_COLUMNS))
        self.assertEqual(int(batch["mask"].sum()), 21)

    def test_it_starts_a_segment_from_nothing_and_not_from_the_actors_memory(self):
        """The game never runs the critic, so there is nothing of it in a shard to replay from. Anything else would be a
        state produced by weights that have since moved."""

        one = trainer()
        segment = fight(one, 12)
        segment.h0 = np.full(one.config.hidden, 0.3, dtype=np.float32)

        _, replayed, batch = replay(one, [segment])

        self.assertTrue(np.all(replayed[0].critic_hidden_in[0] == 0.0))
        self.assertTrue(np.all(batch["critic_hidden"][0].numpy() == 0.0))

        # And the actor's, which the game does record, is carried in as it always was.
        self.assertTrue(np.all(replayed[0].hidden_in[0] == 0.3))

    def test_its_memory_carries_across_the_chunks_the_way_the_actors_does(self):
        """The whole point of a recurrent critic. A segment replayed in one go and the same segment replayed as chunks of
        seq_len, each starting from the state the replay recovered, must price every step identically; if they do not, the
        chunks in the middle of a fight are being priced from a blank memory."""

        one = trainer()
        segments = [fight(one, 21)]
        _, replayed, batch = replay(one, segments)

        self.assertTrue(np.allclose(values_of(one, batch), replayed[0].values, atol=1e-5))

        # And the memory is load bearing rather than decorative: start every chunk from nothing and the answers move.
        blank = values_of(one, batch, torch.zeros_like(batch["critic_hidden"]))

        self.assertFalse(np.allclose(blank, replayed[0].values, atol=1e-5))

        # Only from the second chunk on, since the first one does start from nothing.
        self.assertTrue(np.allclose(blank[:8], replayed[0].values[:8], atol=1e-5))

    def test_the_plain_critic_has_no_memory_to_carry(self):
        one = trainer(critic_gru=False)
        segments = [fight(one, 21)]
        _, replayed, batch = replay(one, segments)

        self.assertEqual(replayed[0].critic_hidden_in.shape, (21, 0))
        self.assertEqual(tuple(batch["critic_hidden"].shape), (3, 0))
        self.assertTrue(np.allclose(values_of(one, batch), replayed[0].values, atol=1e-5))

    # ---------------------------------------------------------------------------------------------------------------
    # What it is told
    # ---------------------------------------------------------------------------------------------------------------

    def test_every_privileged_column_is_strictly_behind_the_row_it_sits_on(self):
        """The return is the target, so an input carrying any part of it would train the critic to read the answer off its
        own inputs. With rewards counting 1, 2, 3, 4 the account on a row is the rewards of every earlier step and the last
        one paid is the step before: neither ever reaches this row's own reward, which is the first term of its return."""

        one = trainer(scale_rewards=False)
        privileged, _, _ = replay(one, [fight(one, 4)])
        rows = privileged[0]

        self.assertEqual([round(float(value), 4) for value in rows[:, 0]], [0.0, 1.0, 3.0, 6.0, 10.0])
        self.assertEqual([round(float(value), 4) for value in rows[:, 1]], [0.0, 1.0, 2.0, 3.0, 4.0])

        # The terminal reward, 4, lands on the row after the last action and so is on no acted row's inputs at all.
        self.assertNotIn(4.0, [round(float(value), 4) for value in rows[:4, :2].reshape(-1)])

    def test_the_clock_counts_ticks_and_the_first_row_of_a_segment_says_so(self):
        one = trainer(scale_rewards=False)
        privileged, _, _ = replay(one, [fight(one, 4)])
        rows = privileged[0]

        self.assertEqual([round(float(value) * EPISODE_TICKS) for value in rows[:, 2]], [0, 1, 2, 3, 4])
        self.assertEqual([float(value) for value in rows[:, 3]], [1.0, 0.0, 0.0, 0.0, 0.0])

    def test_a_fight_cut_in_two_carries_its_clock_and_its_account_into_the_next_stretch(self):
        """A segment is however much of a fight fitted in one shard, so most rows of a long fight are in a stretch that
        started somewhere else. The trainer keeps the totals per agent across iterations, which is the whole reason those
        rows can be told where in the fight they are at all."""

        one = trainer(scale_rewards=False)
        key = (3, 1, 11)

        first = fight(one, 4, key=key, done=False, new=True)
        second = fight(one, 3, key=key, done=True, new=False, seed=1)

        privileged, _, _ = replay(one, [first, second])

        # 1 + 2 + 3 + 4 of the first stretch already paid, and four ticks already run.
        self.assertEqual(round(float(privileged[1][0, 0]), 4), 10.0)
        self.assertEqual(round(float(privileged[1][0, 2]) * EPISODE_TICKS), 4)

        # And it is still flagged as a segment's first row, because the reward for the action before it is in a shard this
        # side no longer has: `last` reads nought there, and `start` is what says that nought is a gap.
        self.assertEqual(float(privileged[1][0, 1]), 0.0)
        self.assertEqual(float(privileged[1][0, 3]), 1.0)

    def test_a_new_fight_starts_the_account_and_the_clock_again(self):
        one = trainer(scale_rewards=False)
        key = (3, 1, 11)

        privileged, _, _ = replay(one, [fight(one, 4, key=key), fight(one, 3, key=key, seed=1)])

        self.assertEqual(float(privileged[1][0, 0]), 0.0)
        self.assertEqual(float(privileged[1][0, 2]), 0.0)

    def test_the_games_own_columns_come_through_the_shard_and_land_after_the_trainers(self):
        """The order is the contract. A critic reads its columns from the first, so anything appended has to go on the end:
        putting the game's block first would move `paid` under a critic already trained to read it there."""

        one = trainer(scale_rewards=False)
        privileged, replayed, batch = replay(one, [fight(one, 9)])

        told = [round(float(value), 4) for value in privileged[0][3, len(PRIVILEGED):]]

        self.assertEqual(told, [round(0.1 * (column + 1), 4) for column in range(len(SHARD_PRIVILEGED))])

        # And they arrive on every row the same way, the row the segment ended on included, since that is the row a cut
        # fight is bootstrapped from.
        self.assertTrue(np.allclose(privileged[0][:, len(PRIVILEGED):], privileged[0][0, len(PRIVILEGED):]))
        self.assertTrue(np.allclose(replayed[0].privileged[:, len(PRIVILEGED):], told, atol=1e-6))
        self.assertTrue(np.allclose(batch["privileged"][0, 0, len(PRIVILEGED):].numpy(), told, atol=1e-6))

    def test_the_critic_reads_the_observation_the_actors_memory_and_every_privileged_column(self):
        one = trainer()

        self.assertEqual(one.critic.privileged, PRIVILEGED_COLUMNS)
        self.assertEqual(one.critic.encoder.in_features, one.schema.obs_dim + one.config.hidden + PRIVILEGED_COLUMNS)

    def test_the_privileged_columns_are_load_bearing(self):
        """Ten of them are a tenth of nothing beside 792 of observation, so it is worth proving the value path actually
        reads them rather than that they merely fit."""

        one = trainer()
        _, replayed, batch = replay(one, [fight(one, 9)])

        moved = batch["privileged"].clone()
        moved[..., len(PRIVILEGED):] = 0.0

        with torch.no_grad():
            values, _ = one.critic(one.actor.normalise(batch["obs"]), batch["memory"], moved, batch["critic_hidden"])

        self.assertFalse(np.allclose(values.reshape(-1).numpy()[batch["mask"].reshape(-1).numpy()],
                                     replayed[0].values, atol=1e-5))

    def test_the_plain_critic_is_handed_the_columns_and_reads_none_of_them(self):
        """The trainer works the columns out once whatever critic it has, so both shapes take the same batch and there is
        one value path rather than two."""

        one = trainer(critic_gru=False)
        _, _, batch = replay(one, [fight(one, 9)])

        self.assertEqual(tuple(batch["privileged"].shape), (2, 8, PRIVILEGED_COLUMNS))
        self.assertEqual(one.critic.privileged, 0)
        self.assertEqual(one.critic.net[0].in_features, one.schema.obs_dim + one.config.hidden)

    # ---------------------------------------------------------------------------------------------------------------
    # Learning, saving and resuming
    # ---------------------------------------------------------------------------------------------------------------

    def test_the_value_loss_reaches_the_critics_own_memory(self):
        """A recurrent critic whose GRU gets no gradient is a slower feed-forward critic. The replay holds the *actor's*
        memory still on purpose; the critic's own is the one thing in the value path that must still learn."""

        one = trainer()
        before = one.critic.gru.weight_hh_l0.detach().clone()

        _, _, batch = replay(one, [fight(one, 21)])
        stats = one._learn(batch)

        self.assertGreater(stats["value"], 0.0)
        self.assertFalse(torch.allclose(before, one.critic.gru.weight_hh_l0))

    def test_an_update_runs_either_way(self):
        for recurrent in (True, False):
            with self.subTest(critic_gru=recurrent):
                one = trainer(critic_gru=recurrent)
                _, _, batch = replay(one, [fight(one, 21), fight(one, 5, key=(1, 0, 9), seed=2)])

                self.assertGreater(one._learn(batch)["value"], 0.0)

    def test_a_critic_survives_a_save_and_a_load(self):
        one = trainer()

        # One update first, so the critic saved is one that has learned something: an untrained critic is rebuilt on the way
        # in rather than loaded, and two freshly built ones would agree whether it loaded or not.
        _, _, batch = replay(one, [fight(one, 21, key=(1, 0, 5))])
        one._learn(batch)

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"

            # Saved before either of them replays anything further, so both carry the same reward scaler: the scaling of a
            # reward is part of the run's state, and a critic that was handed different numbers would price differently
            # however faithfully its weights came back.
            one.save(path)

            two = trainer()
            two.load(path)

        self.assertEqual(two.critic.gru_width, 16)
        self.assertEqual(two.critic.privileged, PRIVILEGED_COLUMNS)

        _, mine, _ = replay(one, [fight(one, 21)])
        _, theirs, _ = replay(two, [fight(two, 21)])

        self.assertTrue(np.allclose(mine[0].values, theirs[0].values, atol=1e-6))

    def test_a_state_from_before_this_carries_on_with_the_critic_it_holds(self):
        """A critic is learned rather than configured. A state written before the critic had a memory of its own names no
        width, and rebuilding it as a recurrent one would throw away everything it knew about the fight and hand the policy
        nonsense advantages until it learned again — which is what --critic-warmup exists to prevent. So the state wins over
        the flag, and the run is told."""

        old = trainer(critic_gru=False)

        # A run that has been training, which is the case the rule is about; one that has not is the seed gap below.
        _, _, batch = replay(old, [fight(old, 21, key=(1, 0, 5))])
        old._learn(batch)
        old.iteration = 40

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            old.save(path)

            # What a state from before that change looks like: the keys that say what shape the critic is, and whether it has
            # learned anything, are simply absent.
            state = torch.load(path, map_location="cpu", weights_only=False)
            del state["critic_gru_width"]
            del state["critic_trained"]
            torch.save(state, path)

            two = trainer(critic_gru=True)

            with self.assertLogs("mmai.ppo", level="WARNING") as said:
                two.load(path)

        self.assertIn("carries on with the critic it has", "\n".join(said.output))
        self.assertEqual(two.critic.gru_width, 0)
        self.assertEqual(two.critic.privileged, 0)
        self.assertTrue(torch.allclose(old.critic.net[0].weight, two.critic.net[0].weight))

        # And it still trains, from the critic it carried on with.
        _, _, batch = replay(two, [fight(two, 21)])
        self.assertGreater(two._learn(batch)["value"], 0.0)

    def test_a_critic_trained_when_there_were_fewer_columns_goes_on_reading_those(self):
        """What a run that was already going when the game started writing privileged floats of its own resumes into. Its
        critic's first layer is ten columns narrower, and it would not load into this build's at all; the columns being in a
        fixed order with the trainer's own first is what makes carrying on correct rather than merely possible — it reads
        exactly what it learned to read."""

        old = trainer()
        old.critic = old._new_critic(16, len(PRIVILEGED))
        old.optimizer = torch.optim.Adam(list(old.actor.parameters()) + list(old.critic.parameters()), lr=1e-3, eps=1e-5)

        _, _, batch = replay(old, [fight(old, 21, key=(1, 0, 5))])
        old._learn(batch)
        old.iteration = 40

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            old.save(path)

            # A state from before the width was written down holds a critic built when the trainer's four columns were all
            # there were, which is what its absence has to read as.
            state = torch.load(path, map_location="cpu", weights_only=False)
            del state["critic_privileged"]
            torch.save(state, path)

            two = trainer()

            with self.assertLogs("mmai.ppo", level="WARNING"):
                two.load(path)

        self.assertEqual(two.critic.privileged, len(PRIVILEGED))
        self.assertEqual(two.critic.encoder.in_features, two.schema.obs_dim + two.config.hidden + len(PRIVILEGED))
        self.assertTrue(torch.allclose(old.critic.encoder.weight, two.critic.encoder.weight))

        # And it still prices a fight, from the batch every shape of critic is handed.
        _, _, batch = replay(two, [fight(two, 21)])
        self.assertGreater(two._learn(batch)["value"], 0.0)

    def test_a_flag_cannot_take_a_recurrent_critic_away_from_a_run_either(self):
        one = trainer()

        _, _, batch = replay(one, [fight(one, 21, key=(1, 0, 5))])
        one._learn(batch)

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            one.save(path)

            two = trainer(critic_gru=False)

            with self.assertLogs("mmai.ppo", level="WARNING"):
                two.load(path)

        self.assertEqual(two.critic.gru_width, 16)
        self.assertTrue(torch.allclose(one.critic.gru.weight_hh_l0, two.critic.gru.weight_hh_l0))

    # ---------------------------------------------------------------------------------------------------------------
    # Seeding a run from somebody else's copy
    # ---------------------------------------------------------------------------------------------------------------

    def test_a_critic_that_has_never_learned_is_built_to_the_flags_and_not_to_the_state(self):
        """The seed gap. A copy's state holds an actor worth having and a critic that has never seen a fight: imitation
        trains the actor only. Keeping that critic because the state names it meant a run seeded from a copy silently got the
        critic of whatever build made the copy, and could never be given the one it asked for."""

        copy = trainer(critic_gru=False)

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"

            # What imitation leaves behind: iteration zero, an actor that learned, a critic that did not.
            copy.save(path)

            two = trainer(critic_gru=True)

            with self.assertLogs("mmai.ppo", level="INFO") as said:
                two.load(path)

        self.assertIn("never learned anything", "\n".join(said.output))
        self.assertEqual(two.critic.gru_width, 16)
        self.assertEqual(two.critic.privileged, PRIVILEGED_COLUMNS)

        # The actor is what a seed is for, and it came across whole.
        self.assertTrue(torch.allclose(copy.actor.fc1.weight, two.actor.fc1.weight))

        # And the run trains from there, which is what the optimizer being rebuilt around the new critic has to allow.
        _, _, batch = replay(two, [fight(two, 21)])
        self.assertGreater(two._learn(batch)["value"], 0.0)

    def test_a_copy_seeded_at_its_parents_iteration_is_still_a_critic_that_has_never_learned(self):
        """`-Seed` carries the iteration over, so the iteration cannot be what says whether a critic has learned. A copy
        published as a model keeps whatever iteration its run had reached."""

        copy = trainer(critic_gru=False)
        copy.iteration = 770

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            copy.save(path)

            two = trainer(critic_gru=True)

            with self.assertLogs("mmai.ppo", level="INFO"):
                two.load(path)

        self.assertEqual(two.iteration, 770)
        self.assertEqual(two.critic.gru_width, 16)

    def test_a_critic_that_has_learned_is_kept_however_it_was_reached(self):
        one = trainer(critic_gru=False)

        _, _, batch = replay(one, [fight(one, 21)])
        one._learn(batch)
        one.iteration = 40

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            one.save(path)

            two = trainer(critic_gru=True)

            with self.assertLogs("mmai.ppo", level="WARNING") as said:
                two.load(path)

        self.assertIn("carries on with the critic it has", "\n".join(said.output))
        self.assertEqual(two.critic.gru_width, 0)
        self.assertTrue(torch.allclose(one.critic.net[0].weight, two.critic.net[0].weight))

    def test_a_state_from_before_this_was_recorded_goes_by_its_iteration(self):
        """The only thing that writes a state at iteration zero is an imitation, so a state with no word on the matter and
        an iteration past zero is a run that has been training, and its critic is its own."""

        one = trainer(critic_gru=False)
        one.iteration = 40

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            one.save(path)

            state = torch.load(path, map_location="cpu", weights_only=False)
            del state["critic_trained"]
            torch.save(state, path)

            two = trainer(critic_gru=True)

            with self.assertLogs("mmai.ppo", level="WARNING"):
                two.load(path)

        self.assertEqual(two.critic.gru_width, 0)

    def test_the_switch_off_is_exactly_the_critic_that_was_there_before(self):
        """Parameter names included, since that is what lets an older state load into it at all: the old critic was one
        Sequential called net, and a run started before this one keeps it."""

        plain = trainer(critic_gru=False).critic

        self.assertIsNone(plain.gru)
        self.assertEqual(sorted(plain.state_dict()),
                         ["net.0.bias", "net.0.weight", "net.2.bias", "net.2.weight", "net.4.bias", "net.4.weight"])


if __name__ == "__main__":
    unittest.main()

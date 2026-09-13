"""The pull back towards the teacher, and its falling away.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import unittest
from pathlib import Path

import numpy as np

from mmai.model import SHARD_PRIVILEGED
from mmai.ppo import Config, Trainer
from mmai.rollout import Segment
from mmai.schema import Schema

SCHEMA = Path(__file__).resolve().parents[2] / "mod" / "fabric" / "build" / "brain-parity" / "check" / "humanoid" / "schema.json"


def trainer(**config) -> Trainer:
    return Trainer(Config(device="cpu", **config), Schema.load(SCHEMA))


def demo(one: Trainer, steps: int = 4) -> list[Segment]:
    """One made up stretch of a teacher's fight, which is all set_teacher needs to have something to hold."""

    schema = one.schema
    actions = np.zeros((steps, schema.act_dim), dtype=np.float32)
    actions[::2, :] = 1.0

    return [Segment(
        key=(1, 0, 7),
        obs=np.zeros((steps + 1, schema.obs_dim), dtype=np.float32),
        privileged=np.zeros((steps + 1, len(SHARD_PRIVILEGED)), dtype=np.float32),
        actions=actions,
        log_probs=np.zeros(steps, dtype=np.float32),
        rewards=np.zeros(steps, dtype=np.float32),
        h0=np.zeros(one.config.hidden, dtype=np.float32),
        done=True,
        new=True,
    )]


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class TeacherPullTest(unittest.TestCase):
    def test_it_falls_to_nothing_over_the_decay(self):
        one = trainer(teacher_weight=0.2, teacher_decay=1000)
        one.teacher_from = 0

        for iteration, expected in ((0, 0.2), (250, 0.15), (500, 0.1), (1000, 0.0), (4000, 0.0)):
            one.iteration = iteration
            self.assertAlmostEqual(one.teacher_pull(), expected, places=6)

    def test_it_counts_from_where_the_pull_started_and_not_from_zero(self):
        """A seeded run starts at its parent's iteration, so counting from zero would hand it no teacher at all."""

        one = trainer(teacher_weight=0.2, teacher_decay=1000)
        one.iteration = 6075
        one.set_teacher(demo(one))

        self.assertEqual(one.teacher_from, 6075)
        self.assertAlmostEqual(one.teacher_pull(), 0.2, places=6)

        one.iteration = 6575
        self.assertAlmostEqual(one.teacher_pull(), 0.1, places=6)

    def test_resuming_does_not_start_the_fall_again(self):
        one = trainer(teacher_weight=0.2, teacher_decay=1000)
        one.iteration = 100
        one.set_teacher(demo(one))
        one.iteration = 600

        # What a resumed run does: it has a base already, so setting the teacher again leaves it where it was.
        one.set_teacher(demo(one))

        self.assertEqual(one.teacher_from, 100)
        self.assertAlmostEqual(one.teacher_pull(), 0.1, places=6)

    def test_a_decay_of_zero_holds_it_for_ever(self):
        one = trainer(teacher_weight=0.2, teacher_decay=0)
        one.teacher_from = 0
        one.iteration = 100000

        self.assertAlmostEqual(one.teacher_pull(), 0.2, places=6)

    def test_no_teacher_is_no_pull(self):
        one = trainer(teacher_weight=0.0, teacher_decay=1000)
        one.iteration = 10

        self.assertEqual(one.teacher_pull(), 0.0)

    def test_evaluation_lets_the_teacher_go_early(self):
        """The horizon is a guess; evaluation knows. league768 sat at its iteration 925 for 1,200 more while still
        pulled at 0.32, which is what this releases."""

        one = trainer(teacher_weight=0.4, teacher_decay=4000, teacher_release=6, teacher_release_over=200)
        one.teacher_from = 0
        one.iteration = 1000

        # Five checkpoints without a best is not yet evidence of a ceiling.
        one.note_evaluation(5)
        self.assertIsNone(one.teacher_released)
        self.assertAlmostEqual(one.teacher_pull(), 0.3, places=6)

        one.note_evaluation(6)
        self.assertEqual(one.teacher_released, 1000)

        one.iteration = 1100
        self.assertAlmostEqual(one.teacher_pull(), 0.4 * 0.725 * 0.5, places=6)

        one.iteration = 1200
        self.assertEqual(one.teacher_pull(), 0.0)

        one.iteration = 5000
        self.assertEqual(one.teacher_pull(), 0.0)

    def test_a_release_is_said_once_and_improving_again_does_not_take_it_back(self):
        one = trainer(teacher_weight=0.4, teacher_decay=4000, teacher_release=6, teacher_release_over=200)
        one.teacher_from = 0
        one.iteration = 1000
        one.note_evaluation(6)

        # A new best resets the count, and the teacher stays let go: the run improved without it.
        one.iteration = 1050
        one.note_evaluation(0)

        self.assertEqual(one.teacher_released, 1000)
        self.assertAlmostEqual(one.teacher_pull(), 0.4 * 0.7375 * 0.75, places=6)

    def test_a_release_of_zero_waits_for_the_horizon(self):
        one = trainer(teacher_weight=0.4, teacher_decay=4000, teacher_release=0)
        one.teacher_from = 0
        one.iteration = 1000
        one.note_evaluation(50)

        self.assertIsNone(one.teacher_released)
        self.assertAlmostEqual(one.teacher_pull(), 0.3, places=6)

    def test_a_run_with_no_teacher_is_never_released(self):
        one = trainer(teacher_weight=0.0, teacher_release=1)
        one.iteration = 10
        one.note_evaluation(50)

        self.assertIsNone(one.teacher_released)


if __name__ == "__main__":
    unittest.main()

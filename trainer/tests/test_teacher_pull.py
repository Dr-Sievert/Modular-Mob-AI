"""The pull back towards the teacher, and its falling away.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import unittest
from pathlib import Path

import numpy as np

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


if __name__ == "__main__":
    unittest.main()

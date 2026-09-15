"""The pull back towards the teacher, and its falling away.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import shutil
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

from mmai.model import SHARD_PRIVILEGED
from mmai.ppo import Config, Trainer
from mmai.rollout import Segment
from mmai.schema import Schema

SCHEMA = Path(__file__).resolve().parents[2] / "mod" / "fabric" / "build" / "brain-parity" / "check" / "humanoid" / "schema.json"

# Small enough that writing a state and reading it back takes a moment; nothing here depends on the widths.
SMALL = dict(h1=32, hidden=16, h3=16, critic_width=32, seq_len=8, minibatch_chunks=4, epochs=1, threads=1)


def trainer(**config) -> Trainer:
    return Trainer(Config(device="cpu", **(SMALL | config)), Schema.load(SCHEMA))


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


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class TeacherClockTest(unittest.TestCase):
    """Whose clock a run's pull falls on: its own, or one it inherited and can never finish.

    blast8 is what this is about. Its state carried teacher_from 0, inherited through blast7 and blast6 from the imitation
    copy the lineage began with, and it was started with --teacher-weight 0.3 at iteration 34840: every update computed
    0.3 * max(0, 1 - 34841/1500) = 0, and the pull was dead on arrival for 1,654 iterations with nothing saying so.
    """

    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)

    def written(self, run: str, iteration: int, teacher_from: int | None, released: int | None = None, **config) -> Path:
        """A run of that name, saved at some iteration with some clock already running."""

        one = trainer(**config)
        one.iteration = iteration
        one.teacher_from = teacher_from
        one.teacher_released = released

        path = self.root / run / "state.pt"
        one.save(path)

        return path

    def seeded(self, source: Path, run: str) -> Path:
        """What scripts\\train.ps1 -Seed does: the seed's best state copied into a run folder of another name."""

        into = self.root / run / "state.pt"
        into.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, into)

        return into

    def test_a_seeded_run_starts_with_no_clock(self):
        into = self.seeded(self.written("blast7", 20000, teacher_from=0, released=19000, teacher_weight=0.3), "blast8")

        one = trainer(teacher_weight=0.3, teacher_decay=1500)
        one.load(into)

        self.assertIsNone(one.teacher_from)
        self.assertIsNone(one.teacher_released)

        # And the first iteration it pulls is where its own fall starts, so it is pulled at what was asked for.
        one.set_teacher(demo(one))

        self.assertEqual(one.teacher_from, 20000)
        self.assertAlmostEqual(one.teacher_pull(), 0.3, places=6)

    def test_a_converted_run_starts_with_no_clock(self):
        """train.py attend carries a run's state whole into a network of another shape. The clock is the one thing it does
        not carry: a converted run is a new run, and this is the lineage blast8's zero came down."""

        from mmai.attend import convert

        source = self.root / "plain"
        source.mkdir()
        (source / "schema.json").write_bytes(SCHEMA.read_bytes())
        self.written("plain", 20000, teacher_from=0, released=19000, teacher_weight=0.3)

        convert(source, self.root / "attended", 3)
        state = torch.load(self.root / "attended" / "state.pt", map_location="cpu", weights_only=False)

        self.assertIsNone(state["teacher_from"])
        self.assertIsNone(state["teacher_released"])

    def test_a_state_saved_with_the_pull_off_starts_the_clock_afresh(self):
        """A pull is only charged for the iterations it was on. This run trained for 1,653 iterations with no pull at all,
        which is longer than the decay, and is now being given one."""

        path = self.written("blast8", 36493, teacher_from=34840, released=35000, teacher_weight=0.0)

        one = trainer(teacher_weight=0.3, teacher_decay=1500)
        one.load(path)

        self.assertIsNone(one.teacher_from)
        self.assertIsNone(one.teacher_released)

    def test_a_plain_resume_keeps_its_clock(self):
        """The other half of the same rule, and the one a run must not be able to get out of: the pull was on when this was
        saved, so it is charged for every iteration since, however little of it is left."""

        path = self.written("blast8", 36493, teacher_from=35993, teacher_weight=0.3)

        one = trainer(teacher_weight=0.3, teacher_decay=1500)
        one.load(path)

        self.assertEqual(one.teacher_from, 35993)

        # Setting the teacher again is what a resume does, and it leaves the clock where it was.
        one.set_teacher(demo(one))

        self.assertEqual(one.teacher_from, 35993)
        self.assertAlmostEqual(one.teacher_pull(), 0.3 * (1.0 - 500.0 / 1500.0), places=6)

    def test_a_state_that_says_neither_keeps_its_clock(self):
        """A state written before either key existed says nothing about where it came from or whether it was pulling, and
        nothing to go on is not a reason to throw a clock away."""

        source = self.written("blast7", 20000, teacher_from=0, teacher_weight=0.3)
        state = torch.load(source, map_location="cpu", weights_only=False)
        del state["run"], state["teacher_pulling"]
        torch.save(state, source)

        one = trainer(teacher_weight=0.3, teacher_decay=1500)
        one.load(self.seeded(source, "blast8"))

        self.assertEqual(one.teacher_from, 0)

    def test_a_fall_that_has_run_out_says_so_at_startup(self):
        """blast8's evening, in one test. The only line said about the pull was "pulling towards the teacher with weight
        0.30", which is the configured weight and was never the effective one."""

        one = trainer(teacher_weight=0.3, teacher_decay=1500)
        one.iteration = 36493
        one.teacher_from = 0

        with self.assertLogs("mmai.ppo", "WARNING") as caught:
            one.set_teacher(demo(one))

        self.assertEqual(one.teacher_pull(), 0.0)
        self.assertEqual(1, len(caught.records))

        said = caught.records[0].getMessage()

        for named in ("0.00", "iteration 0", "at 36493", "--teacher-decay 1500"):
            self.assertIn(named, said)

    def test_a_pull_with_something_left_in_it_says_nothing_at_startup(self):
        """A warning said every start whether or not anything is wrong is a warning nobody reads."""

        one = trainer(teacher_weight=0.3, teacher_decay=1500)
        one.iteration = 36493
        one.teacher_from = 36000

        with self.assertNoLogs("mmai.ppo", "WARNING"):
            one.set_teacher(demo(one))

    def test_the_effective_weight_is_on_the_iteration_line_and_watch_still_reads_it(self):
        """Once per iteration, beside drift, and only where the pull has left the weight that was configured. The prefix is
        what scripts\\watch.ps1 reads field by field, and the field goes after all of it."""

        # scripts\watch.ps1's $IterationPattern, without the timestamp and logger name the formatter puts in front.
        prefix = r"iteration\s+(\d+)\s+steps\s+([\d,]+)\s+episodes\s+(\d+)\s+win\s+([\d.]+)%"

        one = trainer(teacher_weight=0.3, teacher_decay=1500)
        one.iteration = 750
        one.teacher_from = 0

        stats = {"policy": 0.0, "value": 0.0, "entropy": 0.0, "clip": 0.0, "kl": 0.0, "epochs": 1, "drift": 0.0,
                 "seconds": 1.0, "vram": 0.0, "steps": 100}

        with self.assertLogs("mmai.ppo", "INFO") as caught:
            one.report(dict(stats))

        line = next(said for said in (record.getMessage() for record in caught.records) if " steps " in said)

        self.assertIn("teacher 0.15", line)
        self.assertRegex(line, prefix)

        # And at full pull the field is not there at all: a line that says the same thing every iteration says nothing.
        one.teacher_from = 750

        with self.assertLogs("mmai.ppo", "INFO") as caught:
            one.report(dict(stats))

        self.assertNotIn("teacher ", next(said for said in (record.getMessage() for record in caught.records)
                                          if " steps " in said))


if __name__ == "__main__":
    unittest.main()

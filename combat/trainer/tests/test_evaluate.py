"""How the best checkpoint is chosen: paired on the opponents two checkpoints both met.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from mmai.evaluate import Evaluator, Result
from mmai.run import RunDirectory


def result(against: dict[str, tuple[int, int]], rating: float | None = None) -> Result:
    fights = sum(pair[0] for pair in against.values())
    wins = sum(pair[1] for pair in against.values())

    return Result(fights, wins, 0, rating, {name: [pair[0], pair[1]] for name, pair in against.items()})


class PairedTest(unittest.TestCase):
    def test_an_opponent_counts_once_however_often_it_was_drawn(self):
        # Ten fights against the zombie and one against the warden, so a plain win rate is the zombie's alone.
        one = result({"zombie": (10, 10), "warden": (1, 0)})
        two = result({"zombie": (10, 5), "warden": (1, 1)})

        mine, theirs, shared = one.paired(two)

        self.assertEqual(shared, 2)
        self.assertAlmostEqual(mine, 0.5)
        self.assertAlmostEqual(theirs, 0.75)

    def test_a_rung_opening_does_not_decide_it(self):
        """The newer checkpoint met a hard rung the older never saw, and loses every fight of it. Judged on everything it
        met it looks worse; judged on what they share, it is better, which it is."""

        old = result({"zombie": (20, 12), "skeleton": (20, 12)})
        new = result({"zombie": (20, 16), "skeleton": (20, 16), "warden(hard)": (20, 0), "evoker(hard)": (20, 0)})

        self.assertLess(new.rate, old.rate)

        mine, theirs, shared = new.paired(old)

        self.assertEqual(shared, 2)
        self.assertGreater(mine, theirs)

    def test_nothing_shared_says_so(self):
        self.assertEqual(result({"zombie": (4, 2)}).paired(result({"husk": (4, 2)})), (0.0, 0.0, 0))


class BetterTest(unittest.TestCase):
    def evaluator(self, folder: str) -> Evaluator:
        run = RunDirectory(Path(folder) / "run")
        return Evaluator(run, every=25, fights=10, patience=5, target=0.995, rating=lambda iteration: 1500.0)

    def test_a_point_is_asked_for_over_the_shared_opponents(self):
        with tempfile.TemporaryDirectory() as folder:
            evaluator = self.evaluator(folder)
            best = result({"zombie": (100, 50)})

            self.assertFalse(evaluator._better(result({"zombie": (100, 50)}), best))
            self.assertFalse(evaluator._better(result({"zombie": (100, 50)}), best))
            self.assertFalse(evaluator._better(result({"zombie": (100, 50)}), best), "half a point is not a new best")
            self.assertTrue(evaluator._better(result({"zombie": (100, 52)}), best))

    def test_records_with_no_opponents_fall_back_to_the_win_rate(self):
        with tempfile.TemporaryDirectory() as folder:
            evaluator = self.evaluator(folder)

            # What an older run's lines give: counts and nothing to pair on.
            best = Result(100, 50, 0, 1600.0)

            self.assertTrue(evaluator._better(Result(100, 60, 0, 1500.0), best))
            self.assertFalse(evaluator._better(Result(100, 40, 0, 1900.0), best),
                             "the rating no longer decides anything")

    def test_a_best_that_cannot_be_compared_stands_aside_once(self):
        """A run carried across this change has a best with no opponents written down. Nothing could ever be shown to beat
        it, and its win rate was measured against the roster of the time, so the first checkpoint that can be compared takes
        it — and then has to win on the shared opponents like anything else."""

        with tempfile.TemporaryDirectory() as folder:
            evaluator = self.evaluator(folder)
            old = Result(1000, 655, 0, 1748.0)
            fresh = result({"zombie": (100, 57), "warden": (100, 0)})

            self.assertLess(fresh.rate, old.rate)
            self.assertTrue(evaluator._better(fresh, old))

            # And once there is something to pair on, the margin applies again.
            self.assertFalse(evaluator._better(result({"zombie": (100, 57), "warden": (100, 0)}), fresh))
            self.assertTrue(evaluator._better(result({"zombie": (100, 62), "warden": (100, 0)}), fresh))

    def test_a_higher_rating_does_not_win_on_its_own(self):
        with tempfile.TemporaryDirectory() as folder:
            evaluator = self.evaluator(folder)
            best = result({"zombie": (100, 60)}, rating=1500.0)
            lucky = result({"zombie": (100, 55)}, rating=1708.0)

            self.assertFalse(evaluator._better(lucky, best))


class ResumeTest(unittest.TestCase):
    def resumed(self, folder: str, table: str, lines: str = "") -> Evaluator:
        run = RunDirectory(Path(folder) / "run")
        run.path.mkdir(parents=True, exist_ok=True)
        (run.path / "eval.csv").write_text(table, encoding="utf-8")

        if lines:
            (run.path / "eval").mkdir(parents=True, exist_ok=True)
            (run.path / "eval" / "w00.csv").write_text(lines, encoding="utf-8")

        return Evaluator(run, every=25, fights=10, patience=40, target=0.995, rating=lambda iteration: 1500.0)

    def test_a_resumed_run_gets_one_more_checkpoint_before_it_may_stop(self):
        """What ended a run the moment it started: it came back holding a best nothing could beat, counted 47 against a
        patience of 40, and said it was done, while the next checkpoint went on to beat it."""

        header = "iteration,fights,wins,timeouts,win_rate,best,rating\n"
        rows = "100,1000,655,0,0.6550,1,1748.0\n"
        rows += "".join(f"{200 + 25 * i},1000,600,0,0.6000,0,1600.0\n" for i in range(47))

        with tempfile.TemporaryDirectory() as folder:
            evaluator = self.resumed(folder, header + rows)

            self.assertEqual(evaluator.best, 100)
            self.assertLess(evaluator.since_best, evaluator.patience)
            self.assertIsNone(evaluator.update(1200), "a resumed run called itself done before judging anything")

    def test_a_best_with_no_opponents_clears_the_count(self):
        header = "iteration,fights,wins,timeouts,win_rate,best,rating\n"

        with tempfile.TemporaryDirectory() as folder:
            evaluator = self.resumed(folder, header + "100,1000,655,0,0.6550,1,1748.0\n"
                                                     "200,1000,600,0,0.6000,0,1600.0\n")

            self.assertEqual(evaluator.since_best, 0)


class VerdictTest(unittest.TestCase):
    """The evaluator's side of taking a verdict back: once a checkpoint beats the best, update stops saying the run is
    done, which is what lets train.py withdraw it."""

    def test_a_new_best_takes_the_verdict_back(self):
        with tempfile.TemporaryDirectory() as folder:
            run = RunDirectory(Path(folder) / "run")
            run.weights.mkdir(parents=True, exist_ok=True)
            evaluator = Evaluator(run, every=25, fights=2, patience=2, target=0.995, rating=lambda i: 1500.0)

            def fights(iteration: int, outcomes: tuple[str, str]) -> None:
                run.weights_file(iteration).write_bytes(b"weights")
                lines = "".join(f"{iteration},{outcome},100,zombie\n" for outcome in outcomes)
                with open(evaluator.folder / "w00.csv", "a", encoding="utf-8") as stream:
                    stream.write(lines)

            # One checkpoint at a time, as a run does it. Each call judges the one the call before named, so the last one
            # takes an extra call to be judged at all.
            # The first one wins half, so that the last one has something to beat.
            for iteration, outcomes in ((25, ("win", "loss")), (50, ("loss", "loss")), (75, ("loss", "loss"))):
                fights(iteration, outcomes)
                evaluator.update(iteration)

            reason = evaluator.update(75)

            self.assertEqual(evaluator.best, 25)
            self.assertGreaterEqual(evaluator.since_best, evaluator.patience)
            self.assertIsNotNone(reason, "the run should be done by patience here")

            # A checkpoint that beats it, and the verdict is gone.
            fights(100, ("win", "win"))
            evaluator.update(100)

            self.assertIsNone(evaluator.update(100), "a new best left the run still saying it was done")
            self.assertEqual(evaluator.best, 100)


class ReadingTest(unittest.TestCase):
    def test_the_opponent_column_is_read_and_an_older_line_still_counts(self):
        with tempfile.TemporaryDirectory() as folder:
            run = RunDirectory(Path(folder) / "run")
            evaluator = Evaluator(run, every=25, fights=10, patience=5, target=0.995)

            (evaluator.folder / "w00.csv").write_text(
                "100,win,240,zombie\n"
                "100,loss,300,warden\n"
                "100,win,180\n",  # a line from before the column existed
                encoding="utf-8")

            evaluator._read()
            live = evaluator.results[100]

            self.assertEqual((live.fights, live.wins), (3, 2))
            self.assertEqual(live.against, {"zombie": [1, 1], "warden": [1, 0]})


if __name__ == "__main__":
    unittest.main()

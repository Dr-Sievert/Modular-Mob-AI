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

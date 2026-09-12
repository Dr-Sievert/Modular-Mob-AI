"""Evaluation of a training run's checkpoints, played inside its own workers, and the decision of when it is done.

What a run learns from is a policy that explores, and how often that wins says little about the weights on their most
likely action: twice a run's training win rate rose while the fighter it would ship got worse. So the workers play one
fight in ten with a checkpoint named here instead, on its most likely action, and write down how each ended. This adds
those up per checkpoint, keeps the best weights, and says when the run has stopped getting better.

    runs/RUN/eval/target      the iteration being evaluated, written here, read by the workers
    runs/RUN/eval/wNN.csv     iteration,outcome,ticks per evaluation fight, appended by the workers
    runs/RUN/eval.csv         one line per evaluated checkpoint, rewritten here
    runs/RUN/best.mbw         the weights of the best checkpoint so far
"""

from __future__ import annotations

import shutil
from dataclasses import dataclass
from pathlib import Path

from . import files, log
from .run import RunDirectory

logger = log.get("eval")


@dataclass
class Result:
    fights: int = 0
    wins: int = 0
    timeouts: int = 0

    # What the checkpoint was worth when it was judged, when something better than its win rate can say: a league run's
    # Elo rating. None for a run whose opponent never changes, where the win rate is the whole story.
    rating: float | None = None

    @property
    def rate(self) -> float:
        return self.wins / self.fights if self.fights else 0.0

    @property
    def score(self) -> float:
        """What checkpoints are compared on. See {@link Evaluator} for why a league needs the rating."""

        return self.rate if self.rating is None else self.rating


class Evaluator:
    """Names the checkpoint the workers evaluate, adds up how its fights went, and keeps the best one.

    :param every: checkpoints come every this many iterations, and only those are evaluated
    :param fights: evaluation fights a checkpoint gets before it is judged
    :param patience: judged checkpoints in a row without a new best before the run counts as done
    :param target: a win rate at which the run is done at once, ignored when a rating decides instead
    :param rating: what a checkpoint's iteration is worth, when a win rate cannot be compared across time

    A win rate only says which checkpoint is better while every checkpoint met the same opponents. A league run's
    opponents do not stay the same: a rung of hard opponents opens as the agent gets good enough for it, squads and new
    mobs join, and the pool of its own past selves grows. Then a later, better fighter can win a smaller share of a
    harder set, and judging on the win rate would keep an early checkpoint as the best for ever, which is what it did:
    iteration 1175 stayed best while every rating said the newest checkpoints had passed it. So a league run judges on
    the checkpoint's Elo rating, which is what accounts for who it beat, and nothing else changes.
    """

    def __init__(self, run: RunDirectory, every: int, fights: int, patience: int, target: float,
                 rating=None) -> None:
        self.run = run
        self.every = every
        self.fights = fights
        self.patience = patience
        self.target = target
        self.rating = rating

        self.folder = run.path / "eval"
        self.folder.mkdir(parents=True, exist_ok=True)
        self.table = run.path / "eval.csv"
        self.best_file = run.path / "best.mbw"

        self.results: dict[int, Result] = {}
        self.offsets: dict[Path, int] = {}

        # What each checkpoint was judged on, frozen at the moment it was: fights still in flight when the target moves on
        # keep coming in, and a verdict has to stay the verdict its numbers say.
        self.judged: dict[int, Result] = {}
        self.best: int | None = None
        self.since_best = 0
        self.current: int | None = None

        self._resume()

    # -------------------------------------------------------------------------------------------------------------

    def update(self, iteration: int) -> str | None:
        """Once per training iteration: takes in what the workers wrote, judges the checkpoint under evaluation once it
        has had its fights, and names the next. Returns why the run is done, or None while it is not."""

        self._read()

        if self.current is not None and self.results.get(self.current, Result()).fights >= self.fights:
            self._judge(self.current)
            self.current = None

        if self.current is None:
            self._name(self._next(iteration))

        # A win rate target says nothing about a league, whose opponents keep getting harder on purpose: there such a run
        # ends on patience alone, when no checkpoint has beaten the best for long enough.
        if self.best is not None and self.rating is None:
            best = self.judged[self.best]

            if best.rate >= self.target:
                return f"iteration {self.best} won {100 * best.rate:.1f}% of {best.fights} evaluation fights"

        if self.since_best >= self.patience:
            return f"{self.since_best} checkpoints in a row without beating iteration {self.best}"

        return None

    # -------------------------------------------------------------------------------------------------------------

    def _next(self, iteration: int) -> int | None:
        """The newest checkpoint not yet judged, so evaluation never falls behind by more than one."""

        newest = iteration - iteration % self.every

        for candidate in range(newest, -1, -self.every):
            if candidate in self.judged:
                return None

            if self.run.weights_file(candidate).is_file():
                return candidate

        return None

    def _name(self, iteration: int | None) -> None:
        target = self.folder / "target"

        # Nothing new to evaluate: the workers are told so, rather than going on playing a checkpoint already judged.
        if iteration is None:
            target.unlink(missing_ok=True)
            return

        self.current = iteration
        temporary = self.folder / "target.tmp"
        temporary.write_text(str(iteration), encoding="utf-8")
        files.replace(temporary, target)
        logger.info("evaluating iteration %d", iteration)

    def _judge(self, iteration: int) -> None:
        live = self.results[iteration]
        rating = self.rating(iteration) if self.rating is not None else None
        result = Result(live.fights, live.wins, live.timeouts, rating)
        self.judged[iteration] = result

        if self.best is None or result.score > self.judged[self.best].score:
            self.best = iteration
            self.since_best = 0
            # Swapped in whole like everything else here: scripts\publish.ps1 copies it out while the run goes on.
            temporary = self.best_file.with_suffix(".tmp")
            shutil.copyfile(self.run.weights_file(iteration), temporary)
            files.replace(temporary, self.best_file)
            verdict = "new best"
        else:
            self.since_best += 1
            best = self.judged[self.best]
            stood = f"{best.rating:.0f}" if best.rating is not None else f"{100 * best.rate:.1f}%"
            verdict = f"best is still iteration {self.best} at {stood}"

        fights = max(1, result.fights)
        lost = result.fights - result.wins - result.timeouts
        rated = "" if result.rating is None else f", rated {result.rating:.0f}"

        logger.info(
            "evaluation of iteration %d over %d fights: won %.1f%%, lost %.1f%%, timed out %.1f%%%s; %s",
            iteration, result.fights, 100 * result.rate, 100 * lost / fights, 100 * result.timeouts / fights,
            rated, verdict,
        )
        self._write_table()

    def _read(self) -> None:
        """Takes in whatever the workers have appended since last time."""

        for file in sorted(self.folder.glob("w*.csv")):
            start = self.offsets.get(file, 0)

            with open(file, "rb") as stream:
                stream.seek(start)
                data = stream.read()

            # Only whole lines; a worker may be half way through writing the last one.
            end = data.rfind(b"\n") + 1
            self.offsets[file] = start + end

            for line in data[:end].decode("utf-8").splitlines():
                parts = line.split(",")

                if len(parts) != 3:
                    continue

                result = self.results.setdefault(int(parts[0]), Result())
                result.fights += 1
                result.wins += parts[1] == "win"
                result.timeouts += parts[1] == "timeout"

    def _write_table(self) -> None:
        lines = ["iteration,fights,wins,timeouts,win_rate,best,rating"]

        for iteration, result in self.judged.items():
            rating = "" if result.rating is None else f"{result.rating:.1f}"
            lines.append(f"{iteration},{result.fights},{result.wins},{result.timeouts},{result.rate:.4f},"
                         f"{int(iteration == self.best)},{rating}")

        temporary = self.table.with_suffix(".tmp")
        temporary.write_text("\n".join(lines) + "\n", encoding="utf-8")
        files.replace(temporary, self.table)

    def _resume(self) -> None:
        """Picks a resumed run's evaluation up where it was: what was judged, and which was best."""

        self._read()

        if not self.table.is_file():
            return

        for line in self.table.read_text(encoding="utf-8").splitlines()[1:]:
            parts = line.split(",")

            # Six fields is a table written before ratings were kept; the seventh is empty for a run without them.
            if len(parts) < 6:
                continue

            iteration = int(parts[0])
            rating = float(parts[6]) if len(parts) > 6 and parts[6] else None
            self.judged[iteration] = Result(int(parts[1]), int(parts[2]), int(parts[3]), rating)

            if parts[5] == "1":
                self.best = iteration

        if self.best is not None:
            self.since_best = sum(1 for i in self.judged if i > self.best)

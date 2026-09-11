"""The run folder, which is the whole of the conversation between the game and this side.

    runs/<name>/schema.json              the layout the game is running, written by the game
    runs/<name>/weights/000042.mbw       iteration 42's policy, written here and read by the game
    runs/<name>/rollouts/000042/*.mbr    what the workers did under it, written by the game and read here
    runs/<name>/trainer.status           what this side is doing, read by the task that starts the workers
    runs/<name>/state.pt                 everything needed to carry the run on where it left off
    runs/<name>/logs/                    what this side said, one file per start

There is no socket and no protocol beyond files appearing. The game waits for the next weights to exist; this waits for
every worker to hand its shard over. Both write under a temporary name and rename, so neither ever reads half of
something.

An iteration is learned from only once every worker still running has delivered. Waiting for a fixed number of shards
instead would deadlock the moment a worker finished its arenas early, so workers say when they are leaving, and the wait
is for whoever is left.
"""

from __future__ import annotations

import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import files
from .rollout import EXTENSION, ShardHeader, read_header
from .weights import EXTENSION as WEIGHT_EXTENSION

STATUS_FILE = "trainer.status"

# Written once the run has got as good as it is going to; see RunDirectory.finish.
FINISHED_FILE = "finished"

WAITING = "waiting"
TRAINING = "training"


@dataclass
class Workers:
    """Who is expected to deliver, learned from the shards themselves and from the build's markers.

    The game's rounds restart the workers, and a worker that has run out of arenas leaves mid round, so neither the count
    nor the membership is known in advance; both are read off the headers. A worker that crashes never says goodbye, so
    the build says it instead, with a marker, and says when a whole round is over.
    """

    counts: dict[int, int] = field(default_factory=dict)
    finished: dict[int, set[int]] = field(default_factory=dict)
    rounds: set[int] = field(default_factory=set)
    closed: set[int] = field(default_factory=set)

    def note(self, header: ShardHeader) -> None:
        self.rounds.add(header.round)
        self.counts[header.round] = header.worker_count

    def note_markers(self, gone: set[tuple[int, int]], closed: set[int]) -> None:
        """Workers the build saw die, and rounds whose every worker has exited."""

        for round_number, worker in gone:
            self.rounds.add(round_number)
            self.finished.setdefault(round_number, set()).add(worker)

        for round_number in closed:
            self.rounds.add(round_number)
            self.closed.add(round_number)

    def consume(self, headers: list[ShardHeader]) -> None:
        """Remembers which workers said goodbye, so later iterations stop waiting for them."""

        for header in headers:
            self.note(header)

            if header.final:
                self.finished.setdefault(header.round, set()).add(header.worker)

    def ready(self, headers: list[ShardHeader]) -> bool:
        if not headers:
            return False

        for header in headers:
            self.note(header)

        # The round the game is in now. Earlier rounds only appear here through the final shards they left behind.
        current = max(self.rounds)

        # Every worker of it has exited, so whatever is here is all that is coming.
        if current in self.closed:
            return True

        count = self.counts.get(current)

        if count is None:
            return False

        live = set(range(count)) - self.finished.get(current, set())
        delivered = {header.worker for header in headers if header.round == current}

        return live <= delivered

    def rounds_done(self, pending: list[ShardHeader] | None = None) -> int:
        """The highest round that is over and whose shards have all been learned from. The build waits for this before
        starting the next round, so new workers never write into an iteration that is being learned from."""

        waiting_on = {header.round for header in pending or []}
        done = 0

        for round_number in sorted(self.rounds):
            count = self.counts.get(round_number, 0)
            over = round_number in self.closed or (count and len(self.finished.get(round_number, set())) >= count)

            if not over or round_number in waiting_on:
                break

            done = round_number

        return done


class RunDirectory:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path).resolve()
        self.weights = self.path / "weights"
        self.rollouts = self.path / "rollouts"
        self.checkpoints = self.path / "checkpoints"

        for directory in (self.weights, self.rollouts, self.checkpoints):
            directory.mkdir(parents=True, exist_ok=True)

    # -----------------------------------------------------------------------------------------------------------
    # Paths
    # -----------------------------------------------------------------------------------------------------------

    def schema_file(self) -> Path:
        return self.path / "schema.json"

    def weights_file(self, iteration: int) -> Path:
        return self.weights / f"{iteration:06d}{WEIGHT_EXTENSION}"

    def rollouts_for(self, iteration: int) -> Path:
        return self.rollouts / f"{iteration:06d}"

    def state_file(self) -> Path:
        return self.path / "state.pt"

    def latest_weights(self) -> int | None:
        iterations = [int(file.stem) for file in self.weights.glob(f"*{WEIGHT_EXTENSION}") if file.stem.isdigit()]
        return max(iterations) if iterations else None

    # -----------------------------------------------------------------------------------------------------------
    # Shards
    # -----------------------------------------------------------------------------------------------------------

    def shards(self, iteration: int) -> list[ShardHeader]:
        """Every complete shard handed over for an iteration. Half written ones are named differently and ignored."""

        directory = self.rollouts_for(iteration)

        if not directory.is_dir():
            return []

        headers = []

        for file in sorted(directory.glob(f"*{EXTENSION}")):
            try:
                headers.append(read_header(file))

            except (OSError, ValueError):
                # Appeared between the listing and the read, or is not ours. It will be there next time round.
                continue

        return headers

    def clear_rollouts(self) -> None:
        """Throws away anything left by a run that is no longer going, which cannot be trusted as on-policy."""

        for directory in sorted(self.rollouts.iterdir()) if self.rollouts.is_dir() else []:
            shutil.rmtree(directory, ignore_errors=True)

    def drop_rollouts(self, iteration: int) -> None:
        shutil.rmtree(self.rollouts_for(iteration), ignore_errors=True)

    def prune_weights(self, current: int, keep: int, every: int) -> None:
        """Keeps the last few weight files and every checkpoint's, so a long run does not fill the disk a megabyte and a
        half at a time. The game only ever needs the current one."""

        for file in self.weights.glob(f"*{WEIGHT_EXTENSION}"):
            if not file.stem.isdigit():
                continue

            iteration = int(file.stem)

            if iteration <= current - keep and iteration % max(1, every) != 0:
                file.unlink(missing_ok=True)

    # -----------------------------------------------------------------------------------------------------------
    # Status
    # -----------------------------------------------------------------------------------------------------------

    def finish(self, reason: str) -> None:
        """Says the run has got as good as it is going to, so the build starts no more rounds. The build clears it when a
        run starts, so a finished run can still be carried on by hand."""

        (self.path / FINISHED_FILE).write_text(reason + "\n", encoding="utf-8")

    def status(self, state: str, iteration: int, rounds_done: int) -> None:
        """Says what this side is doing. The build waits on this before starting another round of arenas."""

        file = self.path / STATUS_FILE
        temporary = file.with_suffix(".tmp")
        temporary.write_text(f"{state} {iteration} {rounds_done}\n", encoding="utf-8")
        files.replace(temporary, file)

    def markers(self) -> tuple[set[tuple[int, int]], set[int]]:
        """What the build has said: which workers died, as (round, worker), and which rounds are over."""

        gone: set[tuple[int, int]] = set()
        closed: set[int] = set()

        for file in (self.rollouts / "gone").glob("r*-w*"):
            round_part, worker_part = file.name.split("-")
            gone.add((int(round_part[1:]), int(worker_part[1:])))

        for file in (self.rollouts / "rounds").glob("r*.done"):
            closed.add(int(file.name[1:].split(".")[0]))

        return gone, closed

    def wait_for_iteration(self, iteration: int, workers: Workers, iteration_status, poll: float = 0.1) -> list[ShardHeader]:
        """Blocks until every worker still running has handed over its shard for this iteration.

        While it waits, a round can end with nothing left to learn from, every worker having crashed or having already
        handed over everything. The build is waiting to hear that before it starts the next round, so it is told as soon
        as it happens rather than whenever the next iteration comes in, which then never would.
        """

        reported = -1

        while True:
            headers = self.shards(iteration)
            workers.note_markers(*self.markers())

            if workers.ready(headers):
                return headers

            done = workers.rounds_done(headers)

            if done != reported:
                iteration_status(done)
                reported = done

            time.sleep(poll)

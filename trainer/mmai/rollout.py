"""Reading what the game did: the rollout shards it writes, one per worker per iteration.

A shard is the step the game already takes once a tick, written down. The rows of one agent within one shard make a
segment, and a segment is what a recurrent update can be replayed over: it carries the hidden state the agent had going
into its first row, so the policy can be run forward from exactly where the game was.

A segment's last row is never a step. It holds the observation the fight reached and the reward the previous action
earned, and no action: either the fight ended there, or the shard was cut while it carried on and that observation is
what the rest of the fight has to be bootstrapped from. The reward on a row always belongs to the action on the row
before it, which is why the first row's reward is not ours to use: it was paid for an action in an earlier shard.

The format is in the game's RolloutWriter; this is the other half of it.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np

MAGIC = int.from_bytes(b"MBR1", "little")
VERSION = 1
EXTENSION = ".mbr"

HEADER_WORDS = 16

FLAG_NEW = 1
FLAG_DONE = 2
FLAG_TRUNCATED = 4


@dataclass(frozen=True)
class ShardHeader:
    path: Path
    version: int
    schema_id: int
    topology_hash: int
    iteration: int
    round: int
    worker: int
    worker_count: int
    obs_dim: int
    act_dim: int
    hidden: int
    final: bool
    rows: int
    segments: int
    steps: int

    def describe(self) -> str:
        return (
            f"{self.path.name}: iteration {self.iteration}, round {self.round}, worker {self.worker} of "
            f"{self.worker_count}, {self.steps:,} steps{', final' if self.final else ''}"
        )


@dataclass
class Segment:
    """One stretch of one agent's fight, as recorded under one policy."""

    # (round, worker, agent)
    key: tuple[int, int, int]

    # (steps + 1, obs_dim): one row per step, plus the row the segment ended on.
    obs: np.ndarray

    # (steps, act_dim) and (steps,): what was chosen, and how likely the game thought it was.
    actions: np.ndarray
    log_probs: np.ndarray

    # (steps,): what each action earned, which arrived on the following row.
    rewards: np.ndarray

    # (hidden,): the memory the agent had going into the first row.
    h0: np.ndarray

    # The fight ended on the last row. Otherwise the last row is only where the recording stopped.
    done: bool

    # The agent's episode started on the first row, so its memory started from nothing.
    new: bool

    @property
    def steps(self) -> int:
        return int(self.actions.shape[0])


def read_header(path: str | Path) -> ShardHeader:
    path = Path(path)

    with open(path, "rb") as file:
        words = np.frombuffer(file.read(4 * HEADER_WORDS), dtype="<u4")

    if words.size != HEADER_WORDS or int(words[0]) != MAGIC:
        raise ValueError(f"{path} is not a rollout shard")

    if int(words[1]) != VERSION:
        raise ValueError(f"{path} is format version {int(words[1])}, not {VERSION}")

    return ShardHeader(
        path=path,
        version=int(words[1]),
        schema_id=int(words[2]),
        topology_hash=int(words[3]),
        iteration=int(words[4]),
        round=int(words[5]),
        worker=int(words[6]),
        worker_count=int(words[7]),
        obs_dim=int(words[8]),
        act_dim=int(words[9]),
        hidden=int(words[10]),
        final=bool(words[11]),
        rows=int(words[12]),
        segments=int(words[13]),
        steps=int(words[14]),
    )


def read_shard(path: str | Path) -> tuple[ShardHeader, list[Segment]]:
    """The whole shard, cut into segments. Rows of different agents interleave, so they are grouped here."""

    header = read_header(path)
    raw = np.fromfile(path, dtype="<u4")

    per_row = 4 + header.act_dim + header.obs_dim
    expected = HEADER_WORDS + header.rows * per_row + header.segments * (1 + header.hidden)

    if raw.size != expected:
        raise ValueError(f"{path} is {raw.size} words but its header describes {expected}")

    rows = raw[HEADER_WORDS : HEADER_WORDS + header.rows * per_row].reshape(header.rows, per_row)
    floats = rows.view("<f4")

    keys = rows[:, 0]
    flags = rows[:, 1]
    rewards = floats[:, 2]
    log_probs = floats[:, 3]
    actions = floats[:, 4 : 4 + header.act_dim]
    observations = floats[:, 4 + header.act_dim :]

    tail = raw[HEADER_WORDS + header.rows * per_row :].reshape(header.segments, 1 + header.hidden)
    starts = {int(row): index for index, row in enumerate(tail[:, 0])}
    states = tail.view("<f4")[:, 1:]

    segments: list[Segment] = []

    # Stable, so each agent's rows stay in the order they were written.
    order = np.argsort(keys, kind="stable")
    boundaries = np.flatnonzero(np.diff(keys[order])) + 1 if order.size else np.array([], dtype=np.int64)

    for group in np.split(order, boundaries):
        if group.size == 0:
            continue

        cuts = [position for position, row in enumerate(group) if int(row) in starts]

        for index, begin in enumerate(cuts):
            end = cuts[index + 1] if index + 1 < len(cuts) else group.size
            piece = group[begin:end]

            # One row alone is a segment that never completed a step: its reward is still to come.
            if piece.size < 2:
                continue

            acted = piece[:-1]
            last = int(piece[-1])

            segments.append(
                Segment(
                    # Entity ids start again in every game process, so an agent is only unique within its round and
                    # worker. Leaving the round out would glue a new fight onto whatever an old one left behind.
                    key=(header.round, header.worker, int(keys[piece[0]])),
                    obs=observations[piece].copy(),
                    actions=actions[acted].copy(),
                    log_probs=log_probs[acted].copy(),
                    rewards=rewards[piece[1:]].copy(),
                    h0=states[starts[int(piece[0])]].copy(),
                    done=bool(flags[last] & FLAG_DONE),
                    new=bool(flags[piece[0]] & FLAG_NEW),
                )
            )

    return header, segments


def pack_by_rows(segments: list[Segment], budget: int) -> list[list[int]]:
    """Groups segments of similar length together, so padding them into one batch wastes little.

    Replaying a four thousand row segment beside a hundred row one would pad the short one out forty times over. Sorting
    by length first and filling a row budget keeps every batch nearly square. Returns indices into ``segments``.
    """

    groups: list[list[int]] = []
    current: list[int] = []
    longest = 0

    for index in sorted(range(len(segments)), key=lambda position: segments[position].obs.shape[0]):
        length = segments[index].obs.shape[0]

        if current and max(longest, length) * (len(current) + 1) > budget:
            groups.append(current)
            current = []
            longest = 0

        current.append(index)
        longest = max(longest, length)

    if current:
        groups.append(current)

    return groups

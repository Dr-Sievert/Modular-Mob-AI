"""What decides the actions.

The interface is deliberately small and framework free, so that the socket layer never learns what a network is. The
real policy is a recurrent one and will keep hidden state per agent; that is why ``act`` is handed the keys and the
flags rather than just a block of observations.
"""

from __future__ import annotations

from typing import Protocol

import numpy as np

from . import log
from .protocol import FLAG_DONE, FLAG_NEW, Step
from .schema import Schema


class Policy(Protocol):
    def act(self, step: Step, schema: Schema) -> np.ndarray:
        """One action per agent, shaped ``(count, act_dim)``, in the order the step arrived in.

        Rows flagged done are ignored by the game, but still have to be present so the shapes line up.
        """
        ...

    def forget(self, keys: list[tuple[int, int]]) -> None:
        """Drops any state held for these agents, called once their episodes have ended."""
        ...


class RandomPolicy:
    """Flails.

    Useless as a fighter and exactly right for proving the pipe: it exercises every field, every shape and both ends of
    the socket without a network being involved, so anything that goes wrong is the plumbing rather than the learning.
    """

    def __init__(self, seed: int = 0) -> None:
        self._random = np.random.default_rng(seed)

    def act(self, step: Step, schema: Schema) -> np.ndarray:
        count = step.count
        actions = np.zeros((count, schema.act_dim), dtype=np.float32)

        # The first four are continuous and live in minus one to one; the next six are held or not; the last is a slot.
        actions[:, 0:4] = self._random.uniform(-1.0, 1.0, size=(count, 4))
        actions[:, 4:10] = (self._random.random((count, 6)) < 0.1).astype(np.float32)
        actions[:, 10] = self._random.integers(0, schema.hotbar.size, size=count)

        return actions

    def forget(self, keys: list[tuple[int, int]]) -> None:
        return None


class EchoPolicy:
    """Does nothing at all, and reports what it saw.

    Useful when the question is whether the observation is being filled in correctly rather than whether an agent can
    fight: run one arena, watch the numbers, compare them against what is happening on screen.
    """

    def __init__(self, every: int = 20) -> None:
        self._every = every
        self._ticks = 0

    def act(self, step: Step, schema: Schema) -> np.ndarray:
        self._ticks += 1

        if self._ticks % self._every == 0 and step.count > 0:
            enemies = schema.enemies.per_slot(step.observations)[0]
            present = enemies[:, 0] > 0.5
            new = int((step.flags & FLAG_NEW).sum())
            done = int((step.flags & FLAG_DONE).sum())

            log.get("echo").info(
                "tick %d: %d agents (%d new, %d done), reward %+.3f, agent 0 sees %d opponents, nearest at %.3f",
                self._ticks,
                step.count,
                new,
                done,
                float(step.rewards.sum()),
                int(present.sum()),
                float(enemies[present, 4].min()) if present.any() else float("nan"),
            )

        return np.zeros((step.count, schema.act_dim), dtype=np.float32)

    def forget(self, keys: list[tuple[int, int]]) -> None:
        return None

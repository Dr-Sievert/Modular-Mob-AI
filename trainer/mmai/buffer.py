"""Where experience waits between being lived and being learned from.

Each agent's steps are kept as their own trajectory, because agents start and finish at different times and a step
only makes sense next to the ones before it. A step is stored the moment the policy acts on it and completed one tick
later, when the game reports what that action earned. The last step of a fight arrives flagged done and carries the
terminal reward, so every trajectory ends with a step whose reward is the outcome.

Nothing is copied per agent per tick. The policy acts on a whole batch at once and hands the batch over as one
:class:`TickRecord`; a trajectory only remembers which row of which record was its step. The per agent work on the hot
path is a tuple append, and the rows are gathered into arrays once per rollout instead of once per tick.

At update time, trajectories are cut into fixed length chunks so the recurrent network can be trained on batches. Each
chunk remembers the hidden state the agent had going into its first step, which is what lets a chunk be replayed on its
own instead of from the start of the fight.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import torch
from torch import Tensor


@dataclass
class TickRecord:
    """Everything the policy produced for one batch, kept whole. Rows belong to different agents."""

    obs: np.ndarray  # (rows, obs_dim), already normalised
    hidden: np.ndarray  # (rows, hidden), the state going into this step
    slot_mask: np.ndarray  # (rows, slots) bool
    actions: np.ndarray  # (rows, act_dim)
    log_probs: np.ndarray  # (rows,)
    values: np.ndarray  # (rows,)


@dataclass
class Trajectory:
    refs: list[tuple[TickRecord, int]] = field(default_factory=list)
    rewards: list[float] = field(default_factory=list)
    dones: list[bool] = field(default_factory=list)

    # The step acted on but not yet paid for. Its reward arrives with the agent's next step.
    pending: tuple[TickRecord, int] | None = None

    # What to bootstrap the last step from when the fight was cut off rather than finished: the value the policy had put
    # on the step that never got its reward. None means work it out from the pending step or the ending.
    bootstrap: float | None = None

    # Running totals for the episode this trajectory is part of, kept across updates that cut it short.
    episode_return: float = 0.0
    episode_length: int = 0

    def __len__(self) -> int:
        return len(self.rewards)

    def complete(self, reward: float, done: bool, raw_reward: float) -> None:
        if self.pending is None:
            return

        self.refs.append(self.pending)
        self.rewards.append(reward)
        self.dones.append(done)

        self.episode_return += raw_reward
        self.episode_length += 1
        self.pending = None

    def pending_value(self) -> float | None:
        if self.pending is None:
            return None

        record, row = self.pending
        return float(record.values[row])

    def clear_completed(self) -> None:
        self.refs.clear()
        self.rewards.clear()
        self.dones.clear()


@dataclass
class Batch:
    """Everything an update needs, as padded ``(chunks, seq_len, ...)`` tensors."""

    obs: Tensor
    hidden: Tensor  # (chunks, hidden), the state going into each chunk
    slot_mask: Tensor  # (chunks, seq_len, slots) bool
    actions: Tensor
    log_probs: Tensor
    advantages: Tensor
    returns: Tensor
    mask: Tensor  # (chunks, seq_len) bool, false where a chunk was padded

    def __len__(self) -> int:
        return int(self.obs.shape[0])

    def index(self, rows: Tensor) -> "Batch":
        return Batch(
            obs=self.obs[rows],
            hidden=self.hidden[rows],
            slot_mask=self.slot_mask[rows],
            actions=self.actions[rows],
            log_probs=self.log_probs[rows],
            advantages=self.advantages[rows],
            returns=self.returns[rows],
            mask=self.mask[rows],
        )

    def to(self, device: torch.device) -> "Batch":
        return Batch(
            obs=self.obs.to(device),
            hidden=self.hidden.to(device),
            slot_mask=self.slot_mask.to(device),
            actions=self.actions.to(device),
            log_probs=self.log_probs.to(device),
            advantages=self.advantages.to(device),
            returns=self.returns.to(device),
            mask=self.mask.to(device),
        )


class RolloutBuffer:
    def __init__(self, gamma: float, gae_lambda: float) -> None:
        self.gamma = gamma
        self.gae_lambda = gae_lambda

        # One live trajectory per agent still fighting, and a pile of finished ones waiting to be learned from. They are
        # kept apart so that an agent id coming back, which happens every time a game worker restarts, starts a fresh
        # trajectory instead of being glued onto the end of whoever had that id before.
        self._active: dict[tuple[int, int], Trajectory] = {}
        self._finished: list[Trajectory] = []
        self._steps = 0

        self.finished_returns: list[float] = []
        self.finished_lengths: list[int] = []
        self.finished_wins = 0

    @property
    def steps(self) -> int:
        """Completed transitions waiting to be learned from."""
        return self._steps

    def complete(self, key: tuple[int, int], reward: float, done: bool, raw_reward: float) -> None:
        """
        :param reward: what the policy learns from, possibly rescaled
        :param raw_reward: what the game actually paid, for the episode figures
        """
        trajectory = self._active.get(key)

        if trajectory is None or trajectory.pending is None:
            return

        trajectory.complete(reward, done, raw_reward)
        self._steps += 1

        if done:
            self.finished_returns.append(trajectory.episode_return)
            self.finished_lengths.append(trajectory.episode_length)

            # A win is the only way to end a fight with a positive terminal reward.
            if reward > 0.0:
                self.finished_wins += 1

            self._finished.append(self._active.pop(key))

    def begin(self, key: tuple[int, int], record: TickRecord, row: int, new: bool) -> None:
        """
        :param new: the game says this is the first step of an episode. Anything already held under this key belongs to
            an earlier agent that never reported its ending, so it is closed off as cut short rather than continued.
        """
        if new:
            stale = self._active.pop(key, None)

            if stale is not None and len(stale) > 0:
                pending_value = stale.pending_value()
                stale.bootstrap = pending_value if pending_value is not None else float(stale.refs[-1][0].values[stale.refs[-1][1]])
                stale.pending = None
                self._finished.append(stale)

        trajectory = self._active.get(key)

        if trajectory is None:
            trajectory = Trajectory()
            self._active[key] = trajectory

        trajectory.pending = (record, row)

    def collect(self, seq_len: int) -> Batch | None:
        """Cuts everything completed into chunks with advantages attached, and empties the completed steps.

        Trajectories that are still running keep their pending step and their episode totals, so a fight that straddles
        an update is not lost or double counted.
        """

        if self._steps == 0:
            return None

        chunks_obs = []
        chunks_hidden = []
        chunks_slot_masks = []
        chunks_actions = []
        chunks_log_probs = []
        chunks_advantages = []
        chunks_returns = []
        chunks_mask = []

        for trajectory in [*self._finished, *self._active.values()]:
            length = len(trajectory)

            if length == 0:
                continue

            refs = trajectory.refs
            rows = np.fromiter((row for _, row in refs), dtype=np.int64, count=length)

            # Gathered once per rollout rather than copied once per tick. Consecutive steps of one agent usually come
            # from consecutive records, so this is a run of small gathers rather than one per step.
            obs = np.stack([record.obs[row] for record, row in refs])
            hidden = np.stack([record.hidden[row] for record, row in refs])
            slot_masks = np.stack([record.slot_mask[row] for record, row in refs])
            actions = np.stack([record.actions[row] for record, row in refs])
            log_probs = np.fromiter((record.log_probs[row] for record, row in refs), dtype=np.float32, count=length)
            values = np.fromiter((record.values[row] for record, row in refs), dtype=np.float32, count=length)
            del rows

            rewards = np.asarray(trajectory.rewards, dtype=np.float32)
            dones = np.asarray(trajectory.dones, dtype=np.float32)

            # What comes after the last stored step: nothing if the fight ended there, otherwise the value the policy
            # put on the step it is still waiting to hear about, or was waiting on when the fight was cut off.
            if trajectory.dones[-1]:
                bootstrap = 0.0
            elif trajectory.bootstrap is not None:
                bootstrap = trajectory.bootstrap
            else:
                pending_value = trajectory.pending_value()
                bootstrap = pending_value if pending_value is not None else float(values[-1])

            advantages = np.zeros(length, dtype=np.float32)
            last = 0.0

            for t in reversed(range(length)):
                next_value = bootstrap if t == length - 1 else values[t + 1]
                not_done = 1.0 - dones[t]
                delta = rewards[t] + self.gamma * next_value * not_done - values[t]
                last = delta + self.gamma * self.gae_lambda * not_done * last
                advantages[t] = last

            returns = advantages + values

            for start in range(0, length, seq_len):
                end = min(start + seq_len, length)
                size = end - start
                pad = seq_len - size

                def padded(array: np.ndarray) -> np.ndarray:
                    piece = array[start:end]
                    if pad == 0:
                        return piece
                    shape = (pad,) + piece.shape[1:]
                    return np.concatenate([piece, np.zeros(shape, dtype=piece.dtype)])

                chunks_obs.append(padded(obs))
                chunks_hidden.append(hidden[start])
                chunks_slot_masks.append(padded(slot_masks))
                chunks_actions.append(padded(actions))
                chunks_log_probs.append(padded(log_probs))
                chunks_advantages.append(padded(advantages))
                chunks_returns.append(padded(returns))
                chunks_mask.append(np.concatenate([np.ones(size, dtype=bool), np.zeros(pad, dtype=bool)]))

            trajectory.clear_completed()

        # Finished fights have now been learned from in full; live ones keep their pending step and carry on.
        self._finished.clear()
        self._steps = 0

        if not chunks_obs:
            return None

        return Batch(
            obs=torch.from_numpy(np.stack(chunks_obs)),
            hidden=torch.from_numpy(np.stack(chunks_hidden)),
            slot_mask=torch.from_numpy(np.stack(chunks_slot_masks)),
            actions=torch.from_numpy(np.stack(chunks_actions)),
            log_probs=torch.from_numpy(np.stack(chunks_log_probs)),
            advantages=torch.from_numpy(np.stack(chunks_advantages)),
            returns=torch.from_numpy(np.stack(chunks_returns)),
            mask=torch.from_numpy(np.stack(chunks_mask)),
        )

    def take_episode_stats(self) -> tuple[list[float], list[int], int]:
        """Returns and empties the finished episode figures, so each update reports only what happened since the last."""
        returns, lengths, wins = self.finished_returns, self.finished_lengths, self.finished_wins
        self.finished_returns = []
        self.finished_lengths = []
        self.finished_wins = 0
        return returns, lengths, wins

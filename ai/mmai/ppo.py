"""PPO with a recurrent policy, driven live by the game.

There is no separate rollout phase. The game asks for actions once a tick, this answers and remembers what it said,
and once enough steps have piled up it stops answering for a moment, learns from them, and carries on. The game
blocks on the socket while that happens, which is exactly right for an on-policy method: nothing is collected from a
policy that is about to change.

Two copies of the network are kept. Acting happens on the CPU, because a batch of fifty agents through a small GRU is
faster there than the round trip to a GPU, and it sits inside the game's tick budget. Learning happens wherever it is
fastest, and the acting copy is refreshed after every update.
"""

from __future__ import annotations

import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable

import numpy as np
import torch
from torch import Tensor

from . import log
from .buffer import RolloutBuffer, TickRecord
from .model import ActorCritic, RewardScaler, RunningNormalizer
from .protocol import FLAG_DONE, FLAG_NEW, Step
from .schema import Schema

logger = log.get("ppo")


@dataclass
class Config:
    # How much experience to gather before each update, in completed steps across every agent.
    rollout_steps: int = 16384

    # How long a chunk the recurrent network is trained through. Longer remembers more, costs more, and pads more.
    seq_len: int = 32

    # Chunks per minibatch. With seq_len 32 this is 2048 steps per gradient.
    minibatch_chunks: int = 64
    epochs: int = 4

    learning_rate: float = 3e-4
    gamma: float = 0.99
    gae_lambda: float = 0.95
    clip: float = 0.2
    value_coef: float = 0.5
    entropy_coef: float = 0.01
    max_grad_norm: float = 0.5

    # Stop an update early once the policy has moved this far from the one that collected the data. Past this the
    # clipped objective stops meaning anything and an update can wreck a policy in one go.
    target_kl: float = 0.02

    # Rewards are divided by the running spread of the return, so the value loss stays the same size whatever the
    # reward is measured in. Off for a task whose rewards are already near unit scale.
    scale_rewards: bool = True

    hidden: int = 256
    encoder: int = 512

    checkpoint_dir: str = "checkpoints"
    checkpoint_every: int = 10

    device: str = "cuda" if torch.cuda.is_available() else "cpu"

    # CPU threads for torch. Acting is a small batch through a small network, and past eight threads the cost of waking
    # them exceeds the work they do: measured, twenty four threads was slower than eight. It also leaves cores for the
    # game workers, which are what actually need them.
    threads: int = 8

    seed: int = 0


class PPOPolicy:
    def __init__(self, config: Config | None = None, checkpoint: str | None = None) -> None:
        self.config = config or Config()

        torch.manual_seed(self.config.seed)
        np.random.seed(self.config.seed)
        torch.set_num_threads(max(1, self.config.threads))

        self.device = torch.device(self.config.device)

        self.schema: Schema | None = None
        self.act_model: ActorCritic | None = None
        self.train_model: ActorCritic | None = None
        self.optimizer: torch.optim.Optimizer | None = None
        self.normalizer: RunningNormalizer | None = None
        self.reward_scaler = RewardScaler(self.config.gamma)

        self.buffer = RolloutBuffer(self.config.gamma, self.config.gae_lambda)
        self.hidden: dict[tuple[int, int], Tensor] = {}

        self.updates = 0
        self.total_steps = 0
        self._started = time.time()
        self._pending_checkpoint = checkpoint

        # Set by whoever is batching steps into this policy, so the update line can say how well that is working.
        self.batch_stats: Callable[[], tuple[float, float]] | None = None

    # -----------------------------------------------------------------------------------------------------------
    # Acting
    # -----------------------------------------------------------------------------------------------------------

    def act(self, step: Step, schema: Schema) -> np.ndarray:
        if self.act_model is None:
            self._build(schema)

        keys = step.keys()
        count = step.count
        actions = np.zeros((count, schema.act_dim), dtype=np.float32)

        # First, what the previous actions earned. A row's reward belongs to the action taken on that agent's previous
        # step, and a done flag closes that agent's episode.
        for index, key in enumerate(keys):
            done = bool(step.flags[index] & FLAG_DONE)
            raw = float(step.rewards[index])
            reward = self.reward_scaler.scale(key, raw, done) if self.config.scale_rewards else raw

            self.buffer.complete(key, reward, done, raw_reward=raw)

            if done:
                self.hidden.pop(key, None)

        # Then, what to do now, for everyone still fighting.
        live = [index for index in range(count) if not (step.flags[index] & FLAG_DONE)]

        if live:
            self._decide(step, schema, keys, live, actions)

        self.total_steps += len(live)

        if self.buffer.steps >= self.config.rollout_steps:
            self.update()

        return actions

    @torch.inference_mode()
    def _decide(self, step: Step, schema: Schema, keys: list, live: list[int], out: np.ndarray) -> None:
        assert self.act_model is not None and self.normalizer is not None

        raw = torch.from_numpy(np.ascontiguousarray(step.observations[live]))
        slot_mask = torch.from_numpy(schema.hotbar.slice(step.observations[live]) > 0.0)

        self.normalizer.update(raw)
        obs = self.normalizer(raw)

        hidden = torch.stack(
            [
                torch.zeros(self.act_model.hidden)
                if (step.flags[index] & FLAG_NEW) or keys[index] not in self.hidden
                else self.hidden[keys[index]]
                for index in live
            ]
        ).unsqueeze(0)

        features, next_hidden = self.act_model.features(obs.unsqueeze(1), hidden)
        features = features[:, 0]

        distribution = self.act_model.distribution(features, slot_mask)
        action = distribution.sample()
        log_prob = distribution.log_prob(action)
        value = self.act_model.values(features)

        # The movement heads are unbounded Gaussians; the game clamps, but clamping here too keeps what was sent and
        # what was stored the same thing.
        sent = action.clone()
        sent[:, :4] = sent[:, :4].clamp(-1.0, 1.0)

        # The whole batch is handed to the buffer once. Every tensor here was made fresh in this call, so the record
        # owns them outright and no agent needs its own copy of anything.
        record = TickRecord(
            obs=obs.numpy(),
            hidden=hidden[0].numpy(),
            slot_mask=slot_mask.numpy(),
            actions=action.numpy(),
            log_probs=log_prob.numpy(),
            values=value.numpy(),
        )

        flags = step.flags
        buffer_begin = self.buffer.begin
        hidden_states = self.hidden
        next_rows = next_hidden[0]

        for row, index in enumerate(live):
            key = keys[index]
            hidden_states[key] = next_rows[row]
            buffer_begin(key, record, row, bool(flags[index] & FLAG_NEW))

        out[live] = sent.numpy()

    def forget(self, keys: list[tuple[int, int]]) -> None:
        # Episodes are closed inside act, where the done flag is seen; this is only belt and braces.
        for key in keys:
            self.hidden.pop(key, None)

    # -----------------------------------------------------------------------------------------------------------
    # Learning
    # -----------------------------------------------------------------------------------------------------------

    def update(self) -> None:
        assert self.train_model is not None and self.act_model is not None and self.optimizer is not None

        started = time.time()
        batch = self.buffer.collect(self.config.seq_len)

        if batch is None:
            return

        stats = self._learn_safely(batch)
        config = self.config

        self.updates += 1

        returns, lengths, wins = self.buffer.take_episode_stats()
        episodes = len(returns)

        batching = ""

        if self.batch_stats is not None:
            rows, workers = self.batch_stats()
            batching = f"  batch {rows:5.0f} rows/{workers:.1f} workers"

        logger.info(
            "update %4d  steps %11s  episodes %5d  win %5.1f%%  return %+7.3f  length %6.1f  |  "
            "pi %+.4f  v %.4f  ent %.3f  clip %.3f  kl %.4f  ep %d  |  %s %4.1fs%s  |  %6.1fm",
            self.updates,
            f"{self.total_steps:,}",
            episodes,
            100.0 * wins / max(1, episodes),
            float(np.mean(returns)) if returns else float("nan"),
            float(np.mean(lengths)) if lengths else float("nan"),
            stats["policy"],
            stats["value"],
            stats["entropy"],
            stats["clip"],
            stats["kl"],
            stats["epochs"],
            self.device.type,
            time.time() - started,
            batching,
            (time.time() - self._started) / 60,
        )

        if self.updates % config.checkpoint_every == 0:
            self.save(Path(config.checkpoint_dir) / f"update-{self.updates:06d}.pt")

    def _learn_safely(self, cpu_batch) -> dict:
        """Runs the update on the chosen device, and on the CPU instead if that device fails underneath it.

        A GPU that hangs takes its whole CUDA context with it, and torch reports that as a RuntimeError on the next
        kernel launch. Left alone, that exception would kill the server, the game workers would die on a closed
        socket, and the next launch would run straight back into the same GPU. So the failure is caught, the last good
        weights are written out, and training carries on where it can. The acting copy already lives on the CPU and is
        refreshed after every update, so nothing learned is lost; only the optimiser's momentum has to start again.
        """

        try:
            return self._learn(cpu_batch.to(self.device))

        except RuntimeError as failure:
            if self.device.type == "cpu" or "cuda" not in str(failure).lower():
                raise

            logger.error("the %s device failed during an update: %s", self.device, str(failure).splitlines()[0])
            logger.error("falling back to the CPU for the rest of this run; check the GPU before trusting it again")

            self._fall_back_to_cpu()
            self.save(Path(self.config.checkpoint_dir) / f"before-gpu-fault-update-{self.updates:06d}.pt")

            return self._learn(cpu_batch)

    def _fall_back_to_cpu(self) -> None:
        assert self.act_model is not None and self.schema is not None

        self.device = torch.device("cpu")
        self.config.device = "cpu"

        self.train_model = ActorCritic(self.schema.obs_dim, self.config.hidden, self.config.encoder)
        self.train_model.load_state_dict(self.act_model.state_dict())
        self.optimizer = torch.optim.Adam(self.train_model.parameters(), lr=self.config.learning_rate, eps=1e-5)

    def _learn(self, batch) -> dict:
        assert self.train_model is not None and self.act_model is not None and self.optimizer is not None

        config = self.config
        valid = batch.mask

        # The values the old policy put on these steps, recovered before the advantages are normalised away.
        old_values = batch.returns - batch.advantages

        advantages = batch.advantages
        mean = advantages[valid].mean()
        std = advantages[valid].std() + 1e-8
        advantages = (advantages - mean) / std

        chunks = len(batch)
        policy_losses, value_losses, entropies, clip_fractions, approx_kls = [], [], [], [], []
        epochs_run = 0

        self.train_model.train()

        for _ in range(config.epochs):
            order = torch.randperm(chunks, device=self.device)
            epoch_kls = []

            for start in range(0, chunks, config.minibatch_chunks):
                rows = order[start : start + config.minibatch_chunks]
                minibatch = batch.index(rows)
                minibatch_advantages = advantages[rows]
                minibatch_old_values = old_values[rows]
                valid_rows = minibatch.mask

                features, _ = self.train_model.features(minibatch.obs, minibatch.hidden.unsqueeze(0))
                distribution = self.train_model.distribution(features, minibatch.slot_mask)

                log_probs = distribution.log_prob(minibatch.actions)
                entropy = distribution.entropy()
                values = self.train_model.values(features)

                ratio = (log_probs - minibatch.log_probs).exp()
                unclipped = ratio * minibatch_advantages
                clipped = ratio.clamp(1.0 - config.clip, 1.0 + config.clip) * minibatch_advantages

                policy_loss = -torch.min(unclipped, clipped)[valid_rows].mean()

                # The value head is clipped the same way the policy is, so one update cannot move an estimate further
                # than the data it was fitted on can justify.
                value_unclipped = (values - minibatch.returns) ** 2
                value_bounded = minibatch_old_values + (values - minibatch_old_values).clamp(-config.clip, config.clip)
                value_clipped = (value_bounded - minibatch.returns) ** 2
                value_loss = 0.5 * torch.max(value_unclipped, value_clipped)[valid_rows].mean()
                entropy_bonus = entropy[valid_rows].mean()

                loss = policy_loss + config.value_coef * value_loss - config.entropy_coef * entropy_bonus

                self.optimizer.zero_grad(set_to_none=True)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(self.train_model.parameters(), config.max_grad_norm)
                self.optimizer.step()

                with torch.no_grad():
                    policy_losses.append(policy_loss.item())
                    value_losses.append(value_loss.item())
                    entropies.append(entropy_bonus.item())
                    clip_fractions.append(((ratio - 1.0).abs() > config.clip)[valid_rows].float().mean().item())
                    kl = ((ratio - 1.0) - (log_probs - minibatch.log_probs))[valid_rows].mean().item()
                    approx_kls.append(kl)
                    epoch_kls.append(kl)

            epochs_run += 1

            if config.target_kl > 0.0 and np.mean(epoch_kls) > config.target_kl:
                break

        self.train_model.eval()

        # The acting copy lives on the CPU whatever the training device is, so this is also the only copy that can be
        # trusted to still be readable after the training device has failed.
        self.act_model.load_state_dict({key: value.cpu() for key, value in self.train_model.state_dict().items()})

        return {
            "policy": float(np.mean(policy_losses)),
            "value": float(np.mean(value_losses)),
            "entropy": float(np.mean(entropies)),
            "clip": float(np.mean(clip_fractions)),
            "kl": float(np.mean(approx_kls)),
            "epochs": epochs_run,
        }

    # -----------------------------------------------------------------------------------------------------------
    # Setup and checkpoints
    # -----------------------------------------------------------------------------------------------------------

    def _build(self, schema: Schema) -> None:
        self.schema = schema
        config = self.config

        self.act_model = ActorCritic(schema.obs_dim, config.hidden, config.encoder).eval()
        self.train_model = ActorCritic(schema.obs_dim, config.hidden, config.encoder).to(self.device).eval()
        self.train_model.load_state_dict(self.act_model.state_dict())

        self.optimizer = torch.optim.Adam(self.train_model.parameters(), lr=config.learning_rate, eps=1e-5)
        self.normalizer = RunningNormalizer(schema.obs_dim)

        if self._pending_checkpoint is not None:
            self.load(Path(self._pending_checkpoint))
            self._pending_checkpoint = None

        logger.info("%s parameters, learning on %s, %d torch threads", f"{sum(p.numel() for p in self.act_model.parameters()):,}", self.device, torch.get_num_threads())

    def save(self, path: Path) -> None:
        assert self.act_model is not None and self.optimizer is not None and self.normalizer is not None

        # Weights come from the acting copy, which is always on the CPU and always in step with the training copy. It
        # is also the only copy that can still be read after the training device has died underneath an update.
        path.parent.mkdir(parents=True, exist_ok=True)
        torch.save(
            {
                "config": asdict(self.config),
                "model": self.act_model.state_dict(),
                "optimizer": self.optimizer.state_dict(),
                "normalizer": self.normalizer.state_dict(),
                "reward_scaler": self.reward_scaler.state_dict(),
                "updates": self.updates,
                "total_steps": self.total_steps,
            },
            path,
        )
        logger.info("saved %s", path)

    def load(self, path: Path) -> None:
        assert self.train_model is not None and self.act_model is not None
        assert self.optimizer is not None and self.normalizer is not None

        state = torch.load(path, map_location=self.device, weights_only=False)
        self.train_model.load_state_dict(state["model"])
        self.act_model.load_state_dict(state["model"])
        self.optimizer.load_state_dict(state["optimizer"])
        self.normalizer.load_state_dict(state["normalizer"])

        if "reward_scaler" in state:
            self.reward_scaler.load_state_dict(state["reward_scaler"])

        self.updates = state.get("updates", 0)
        self.total_steps = state.get("total_steps", 0)
        logger.info("loaded %s at update %d", path, self.updates)


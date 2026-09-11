"""PPO over recorded rollouts.

One iteration is one policy: the game plays under iteration N's weights, writes down what it did, and this learns from
exactly that and exports iteration N+1. Nothing is ever learned from a policy that has already moved on, which is what
being on-policy means and what the clipped objective assumes.

Learning from a recording rather than from a live socket means the hidden states are not lying around to be reused, so
the first thing an update does is replay the policy the game acted with over the observations it recorded, from the memory
each segment started with. That replay is also a continuous check on the parity between the two sides: the log
probabilities it works out should match the ones the game wrote down to within float noise, and if they ever stop
matching, something about the network has drifted apart and every ratio in the update is wrong.
"""

from __future__ import annotations

import os
import time
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import torch
from torch import Tensor

from . import log
from .model import INITIAL_LOG_STD, Actor, Critic, PolicyHeads, RewardScaler, RunningNormalizer
from .rollout import Segment, pack_by_rows
from .schema import Schema
from .weights import export as export_weights

logger = log.get("ppo")


@dataclass
class Config:
    # How much experience one policy collects before it is updated, in steps across every worker. The game is told this
    # number too: each worker takes its share and then waits for the next weights.
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

    # Stop an update early once the policy has moved this far from the one that collected the data. Past this the clipped
    # objective stops meaning anything and an update can wreck a policy in one go.
    target_kl: float = 0.02

    # Rewards are divided by the running spread of the return, so the value loss stays the same size whatever the reward
    # is measured in. Off for a task whose rewards are already near unit scale.
    scale_rewards: bool = True

    # The network the game runs: 634 -> h1 -> GRU hidden -> h3 -> 19 logits. The hidden width is the agent's memory and
    # the middle of the tick budget; the encoder is wide because the observation is.
    h1: int = 256
    hidden: int = 128
    h3: int = 128

    # The critic, which never leaves this side and so costs the game nothing.
    critic_width: int = 256

    obs_clip: float = 10.0

    # An iteration this small is not worth a gradient; the weights are carried over unchanged and the data with them.
    min_steps: int = 512

    # Padded rows per replay batch, which is the only thing here that grows with segment length.
    replay_rows: int = 131072

    checkpoint_every: int = 25

    device: str = "cuda" if torch.cuda.is_available() else "cpu"

    # The most of the GPU's memory this process may take. Past physical memory Windows quietly spills into system memory
    # and everything slows to a crawl; with a ceiling, running out is an error that says so, and the update falls back to
    # the CPU instead. An update here needs about a gigabyte, so half a 16 GB card is room to spare.
    gpu_memory_fraction: float = 0.5

    # CPU threads for torch. The workers sit idle while an update runs, so the update may have a fair share of the cores.
    threads: int = 8

    seed: int = 0

    # Iterations at the start of a run in which only the critic learns. A policy copied from the scripted fighter is
    # already good, and a critic that has never seen a fight would hand it nonsense advantages that undo it before the
    # critic caught up. Zero for a run from scratch, where there is nothing to protect.
    critic_warmup: int = 0

    # How hard every update pulls the policy back towards the teacher it was copied from, zero for a run with no teacher.
    # Started from a 90% copy, reinforcement learning twice made the fighter it would ship worse: advantages from a critic
    # that has not yet learned the fight are mostly noise, and a policy near its best has little to gain and everything to
    # lose from following noise. Pulled back towards the teacher's own answers, recorded in the run's demos, the policy
    # only moves where the fights clearly say so. Scored the way the copy was, on up to teacher_rows of its steps.
    teacher_weight: float = 0.0
    teacher_rows: int = 262144

    # Evaluation: every checkpoint is played by the workers on its most likely action, eval_fights times, see
    # evaluate.py. The run is done once one wins eval_target of its fights, or once eval_patience checkpoints in a row
    # have not beaten the best.
    eval_fights: int = 500
    eval_patience: int = 10
    eval_target: float = 0.995

    # The league, for a run on the league suite, which the build turns on; see league.py. Off for every other suite.
    league: bool = False

    # Frozen checkpoints the agent meets in self play: this many, the newest league_recent of them and the rest spread
    # over the run so far. They get league_self_play of the training fights, the mobs and the scripted fighter the rest.
    league_pool: int = 8
    league_recent: int = 4
    league_self_play: float = 0.2

    # Of each of those two shares, how much is spread evenly over its opponents whatever the agent's chances, so none
    # is forgotten. The rest goes by how close to an even fight each one is.
    league_floor: float = 0.25

    # What the agent's training fights against an opponent still count for an iteration later, and how many fights'
    # worth the ratings' guess at its chances is worth beside them.
    league_decay: float = 0.98
    league_prior: float = 10.0

    # Elo: how far one rated fight moves a rating, twice that for a player's first league_provisional of them, where
    # everyone starts, and whose rating never moves, so the scale means the same in every run.
    league_k: float = 16.0
    league_provisional: int = 30
    league_initial: float = 1500.0
    league_anchor: str = "scripted"

    # How many of the most recent fights against each opponent, and with each loadout, the tables go by.
    league_window: int = 200


@dataclass
class Replayed:
    """What the replay recovered for one segment, all of it on the CPU and aligned to its steps."""

    hidden_in: np.ndarray
    memory: np.ndarray
    values: np.ndarray
    bootstrap: float
    log_probs: np.ndarray
    rewards: np.ndarray
    advantages: np.ndarray
    returns: np.ndarray


class Trainer:
    def __init__(self, config: Config, schema: Schema) -> None:
        self.config = config
        self.schema = schema

        torch.manual_seed(config.seed)
        np.random.seed(config.seed)
        torch.set_num_threads(max(1, config.threads))

        # Full float precision on the GPU. By default cuDNN runs the GRU in TF32 on this generation of card, which keeps
        # ten bits of mantissa, and the replay then drifts a thousandth away from what the game computed in plain
        # floats: every PPO ratio starts off wrong by that much. The network is small enough that the speed does not
        # matter.
        torch.backends.cudnn.allow_tf32 = False
        torch.backends.cuda.matmul.allow_tf32 = False

        self.device = torch.device(config.device)

        if self.device.type == "cuda":
            # The memory calls below want to know which card, so plain "cuda" is pinned to the current one.
            if self.device.index is None:
                self.device = torch.device("cuda", torch.cuda.current_device())

            free, total = torch.cuda.mem_get_info(self.device)
            torch.cuda.set_per_process_memory_fraction(config.gpu_memory_fraction, self.device)

            logger.info(
                "%s: %.1f of %.1f GB free, this process capped at %.1f GB",
                torch.cuda.get_device_name(self.device),
                free / 2**30,
                total / 2**30,
                config.gpu_memory_fraction * total / 2**30,
            )

            if free < 2 * 2**30:
                logger.warning("under 2 GB of GPU memory is free; something else is using the card and updates may be slow")

        self.heads = PolicyHeads(schema.heads)
        self.actor = Actor.for_schema(schema, config.h1, config.hidden, config.h3, config.obs_clip).to(self.device)
        self.critic = Critic(schema.obs_dim, config.hidden, config.critic_width).to(self.device)

        self.optimizer = torch.optim.Adam(
            list(self.actor.parameters()) + list(self.critic.parameters()), lr=config.learning_rate, eps=1e-5
        )

        self.normalizer = RunningNormalizer(schema.obs_dim)
        self.normalizer.into(self.actor)
        self.reward_scaler = RewardScaler(config.gamma)

        self.iteration = 0
        self.total_steps = 0
        self.started = time.time()

        # Episodes straddle iterations, so their totals are kept here until they end.
        self.episodes: dict[tuple[int, int], list[float]] = {}

        self.finished_returns: list[float] = []
        self.finished_lengths: list[float] = []
        self.finished_wins = 0

        # The teacher's record, for the pull back towards it; empty unless the run has one, see set_teacher.
        self.teacher: list[Segment] = []
        self.teacher_groups: list[list[int]] = []
        self.teacher_press_weight: Tensor | None = None

        logger.info(
            "%s, critic %d wide, learning on %s",
            self.actor.topology.describe(),
            config.critic_width,
            self.device,
        )

    # -----------------------------------------------------------------------------------------------------------
    # One iteration
    # -----------------------------------------------------------------------------------------------------------

    def update(self, segments: list[Segment]) -> dict:
        """Learns from one iteration's shards. Returns the figures for the log line."""

        started = time.time()
        steps = sum(segment.steps for segment in segments)
        self.total_steps += steps

        scaled = self._scale(segments)

        try:
            replayed = self._replay(segments, scaled)
            batch = self._chunks(segments, replayed)
            stats = self._learn(batch)

        except RuntimeError as failure:
            if self.device.type == "cpu" or "cuda" not in str(failure).lower():
                raise

            logger.error("the %s device failed during an update: %s", self.device, str(failure).splitlines()[0])
            logger.error("carrying on from the CPU; check the GPU before trusting it again")

            self._fall_back_to_cpu()

            replayed = self._replay(segments, scaled)
            batch = self._chunks(segments, replayed)
            stats = self._learn(batch)

        # The statistics move after the update, never before it: the log probabilities the game recorded were worked out
        # behind the ones it was given, and shifting them first would make every ratio in this update a lie.
        self._refresh_normalizer(segments)

        drift = max((float(np.abs(replayed[index].log_probs - segments[index].log_probs).max())
                     for index in range(len(segments)) if segments[index].steps > 0), default=0.0)

        if drift > 1e-3:
            logger.warning(
                "log probabilities drifted by %.2e from what the game recorded; the two sides disagree about the network",
                drift,
            )

        vram = 0.0

        if self.device.type == "cuda":
            vram = torch.cuda.max_memory_allocated(self.device) / 2**30
            torch.cuda.reset_peak_memory_stats(self.device)

        stats.update({"steps": steps, "drift": drift, "seconds": time.time() - started, "vram": vram})
        return stats

    def _scale(self, segments: list[Segment]) -> list[np.ndarray]:
        """Rewards as the critic sees them, and the episode totals as the game paid them."""

        scaled = []

        for segment in segments:
            totals = self.episodes.setdefault(segment.key, [0.0, 0.0])

            if segment.new:
                totals[0] = 0.0
                totals[1] = 0.0

            totals[0] += float(segment.rewards.sum())
            totals[1] += segment.steps

            values = np.empty(segment.steps, dtype=np.float32)

            for step in range(segment.steps):
                last = step == segment.steps - 1
                reward = float(segment.rewards[step])

                values[step] = (
                    self.reward_scaler.scale(segment.key, reward, segment.done and last)
                    if self.config.scale_rewards
                    else reward
                )

            if segment.done:
                self.finished_returns.append(totals[0])
                self.finished_lengths.append(totals[1])

                # A win is the only way to end a fight with a positive terminal reward.
                if float(segment.rewards[-1]) > 0.0:
                    self.finished_wins += 1

                del self.episodes[segment.key]
                self.reward_scaler.forget(segment.key)

            scaled.append(values)

        return scaled

    @torch.no_grad()
    def _replay(self, segments: list[Segment], scaled: list[np.ndarray]) -> list[Replayed]:
        """Runs the policy the game acted with back over what it saw, to recover the memory and the values."""

        config = self.config
        replayed: list[Replayed | None] = [None] * len(segments)

        self.actor.eval()
        self.critic.eval()

        for group in pack_by_rows(segments, config.replay_rows):
            width = max(segments[index].obs.shape[0] for index in group)

            obs = torch.zeros(len(group), width, self.schema.obs_dim)
            actions = torch.zeros(len(group), width, self.schema.act_dim)
            hidden = torch.zeros(len(group), config.hidden)

            for row, index in enumerate(group):
                segment = segments[index]
                length = segment.obs.shape[0]

                obs[row, :length] = torch.from_numpy(segment.obs)
                actions[row, : segment.steps] = torch.from_numpy(segment.actions)
                hidden[row] = torch.from_numpy(segment.h0)

            obs = obs.to(self.device)
            actions = actions.to(self.device)
            hidden = hidden.to(self.device)

            logits, memory = self.actor(obs, hidden)
            values = self.critic(self.actor.normalise(obs), memory)
            log_probs = self.heads.log_prob(self.heads.distributions(logits, self.actor.log_std, obs), actions)

            memory = memory.cpu().numpy()
            values = values.cpu().numpy()
            log_probs = log_probs.cpu().numpy()
            starts = hidden.cpu().numpy()

            for row, index in enumerate(group):
                segment = segments[index]
                steps = segment.steps

                # The memory going into a step is the memory coming out of the one before it.
                hidden_in = np.concatenate([starts[row : row + 1], memory[row, : steps - 1]], axis=0)

                # Nothing follows a fight that ended; one that was only cut off is worth whatever the critic says.
                bootstrap = 0.0 if segment.done else float(values[row, steps])

                advantages, returns = self._advantages(scaled[index], values[row, :steps], bootstrap, segment.done)

                replayed[index] = Replayed(
                    hidden_in=hidden_in,
                    memory=memory[row, :steps].copy(),
                    values=values[row, :steps].copy(),
                    bootstrap=bootstrap,
                    log_probs=log_probs[row, :steps].copy(),
                    rewards=scaled[index],
                    advantages=advantages,
                    returns=returns,
                )

        return [item for item in replayed if item is not None]

    def _advantages(self, rewards: np.ndarray, values: np.ndarray, bootstrap: float, done: bool):
        """Generalised advantage estimation over one segment."""

        config = self.config
        steps = rewards.shape[0]
        advantages = np.zeros(steps, dtype=np.float32)
        running = 0.0

        for step in reversed(range(steps)):
            last = step == steps - 1
            following = bootstrap if last else float(values[step + 1])
            carries = 0.0 if (last and done) else 1.0

            delta = float(rewards[step]) + config.gamma * following * carries - float(values[step])
            running = delta + config.gamma * config.gae_lambda * carries * running
            advantages[step] = running

        return advantages, advantages + values

    def _chunks(self, segments: list[Segment], replayed: list[Replayed]) -> dict[str, Tensor]:
        """Cuts the segments into fixed length chunks, each remembering the memory it starts from."""

        length = self.config.seq_len

        obs, hidden_in, memory, actions, log_probs, advantages, returns, values, mask = [], [], [], [], [], [], [], [], []

        for segment, data in zip(segments, replayed):
            for start in range(0, segment.steps, length):
                end = min(start + length, segment.steps)
                size = end - start
                pad = length - size

                def padded(array: np.ndarray) -> np.ndarray:
                    piece = array[start:end]

                    if pad == 0:
                        return piece

                    return np.concatenate([piece, np.zeros((pad,) + piece.shape[1:], dtype=piece.dtype)])

                obs.append(padded(segment.obs[: segment.steps]))
                actions.append(padded(segment.actions))
                log_probs.append(padded(segment.log_probs))
                hidden_in.append(data.hidden_in[start])
                memory.append(padded(data.memory))
                advantages.append(padded(data.advantages))
                returns.append(padded(data.returns))
                values.append(padded(data.values))
                mask.append(np.concatenate([np.ones(size, dtype=bool), np.zeros(pad, dtype=bool)]))

        def stacked(arrays: list[np.ndarray]) -> Tensor:
            return torch.from_numpy(np.stack(arrays)).to(self.device)

        return {
            "obs": stacked(obs),
            "hidden": stacked(hidden_in),
            "memory": stacked(memory),
            "actions": stacked(actions),
            "log_probs": stacked(log_probs),
            "advantages": stacked(advantages),
            "returns": stacked(returns),
            "values": stacked(values),
            "mask": stacked(mask),
        }

    def _learn(self, batch: dict[str, Tensor]) -> dict:
        config = self.config

        valid = batch["mask"]
        advantages = batch["advantages"]
        mean = advantages[valid].mean()
        spread = advantages[valid].std() + 1e-8
        advantages = (advantages - mean) / spread

        chunks = int(batch["obs"].shape[0])
        policy_losses, value_losses, entropies, clip_fractions, approximate_kls, teacher_losses = [], [], [], [], [], []
        epochs_run = 0

        self.actor.train()
        self.critic.train()

        for _ in range(config.epochs):
            order = torch.randperm(chunks, device=self.device)
            epoch_kls = []

            for start in range(0, chunks, config.minibatch_chunks):
                rows = order[start : start + config.minibatch_chunks]

                obs = batch["obs"][rows]
                mask = valid[rows]
                old_log_probs = batch["log_probs"][rows]
                old_values = batch["values"][rows]

                logits, _ = self.actor(obs, batch["hidden"][rows])
                distributions = self.heads.distributions(logits, self.actor.log_std, obs)

                log_probs = self.heads.log_prob(distributions, batch["actions"][rows])
                entropy = self.heads.entropy(distributions)

                # The critic reads the memory the policy had at the time, recovered once by the replay and held still for
                # the whole update, so no gradient of the value loss ever reaches the policy's features.
                values = self.critic(self.actor.normalise(obs), batch["memory"][rows])

                ratio = (log_probs - old_log_probs).exp()
                chunk_advantages = advantages[rows]

                unclipped = ratio * chunk_advantages
                clipped = ratio.clamp(1.0 - config.clip, 1.0 + config.clip) * chunk_advantages
                policy_loss = -torch.min(unclipped, clipped)[mask].mean()

                # The value head is clipped the same way the policy is, so one update cannot move an estimate further
                # than the data it was fitted on can justify.
                target = batch["returns"][rows]
                bounded = old_values + (values - old_values).clamp(-config.clip, config.clip)
                value_loss = 0.5 * torch.max((values - target) ** 2, (bounded - target) ** 2)[mask].mean()

                entropy_bonus = entropy[mask].mean()
                if self.iteration < config.critic_warmup:
                    loss = config.value_coef * value_loss
                else:
                    loss = policy_loss + config.value_coef * value_loss - config.entropy_coef * entropy_bonus

                    # The pull back towards the teacher: however noisy this update's advantages are, the policy cannot
                    # wander far from what the teacher does without paying for it, and where the fights clearly say
                    # otherwise the advantages still win.
                    if config.teacher_weight > 0.0 and self.teacher_groups:
                        group = self.teacher_groups[int(np.random.randint(len(self.teacher_groups)))]
                        teacher_obs, teacher_targets, teacher_mask = self._teacher_batch(self.teacher, group)
                        teacher_logits, _ = self.actor(teacher_obs, torch.zeros(teacher_obs.shape[0], config.hidden, device=self.device))
                        teacher_loss = -self._teacher_log_prob(
                            teacher_logits, teacher_obs, teacher_targets, self.teacher_press_weight)[teacher_mask].mean()
                        loss = loss + config.teacher_weight * teacher_loss
                        teacher_losses.append(teacher_loss.item())

                self.optimizer.zero_grad(set_to_none=True)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(
                    list(self.actor.parameters()) + list(self.critic.parameters()), config.max_grad_norm
                )
                self.optimizer.step()
                self.actor.bound_spread()

                with torch.no_grad():
                    policy_losses.append(policy_loss.item())
                    value_losses.append(value_loss.item())
                    entropies.append(entropy_bonus.item())
                    clip_fractions.append(((ratio - 1.0).abs() > config.clip)[mask].float().mean().item())
                    kl = ((ratio - 1.0) - (log_probs - old_log_probs))[mask].mean().item()
                    approximate_kls.append(kl)
                    epoch_kls.append(kl)

            epochs_run += 1

            if config.target_kl > 0.0 and float(np.mean(epoch_kls)) > config.target_kl:
                break

        self.actor.eval()
        self.critic.eval()

        return {
            "policy": float(np.mean(policy_losses)),
            "value": float(np.mean(value_losses)),
            "entropy": float(np.mean(entropies)),
            "clip": float(np.mean(clip_fractions)),
            "kl": float(np.mean(approximate_kls)),
            "teacher": float(np.mean(teacher_losses)) if teacher_losses else 0.0,
            "epochs": epochs_run,
            "chunks": chunks,
        }

    # -----------------------------------------------------------------------------------------------------------
    # Copying a teacher
    # -----------------------------------------------------------------------------------------------------------

    def imitate(self, segments: list[Segment], epochs: int, learning_rate: float = 1e-3, batch_rows: int = 8192) -> None:
        """Teaches the actor to do what the recorded teacher did, before any reinforcement learning.

        The loss is the policy's own log probability of the teacher's action, under the same heads PPO uses, so what is
        learned here is exactly what PPO starts from. The spread stays where it was set: a teacher that never hesitates
        would otherwise shrink it to nothing, and a policy that cannot explore cannot improve on what it copied.
        Continuous targets are pulled in from the very edge, where a tanh can only reach by growing without bound.
        """

        config = self.config

        rows = np.concatenate([segment.obs for segment in segments])
        self.normalizer.update(torch.from_numpy(rows))
        self.normalizer.into(self.actor)

        parameters = [parameter for name, parameter in self.actor.named_parameters() if name != "log_std"]
        optimizer = torch.optim.Adam(parameters, lr=learning_rate)
        groups = pack_by_rows(segments, batch_rows)
        press_weight = self._press_weight(segments)
        buttons = next(head for head in self.schema.heads if head.kind == "binary")

        self.actor.train()

        for epoch in range(epochs):
            started = time.time()
            order = np.random.permutation(len(groups))
            losses, swings_caught, swings, false_swings, quiet = [], 0.0, 0.0, 0.0, 0.0

            for group_index in order:
                obs, targets, mask = self._teacher_batch(segments, groups[group_index])
                logits, _ = self.actor(obs, torch.zeros(obs.shape[0], config.hidden, device=self.device))
                loss = -self._teacher_log_prob(logits, obs, targets, press_weight)[mask].mean()

                optimizer.zero_grad(set_to_none=True)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(parameters, 1.0)
                optimizer.step()

                losses.append(loss.item())

                # The teacher swings on only a few ticks, so plain agreement says little: never swinging agrees almost
                # always. What matters is how many of its swings the copy makes, and how rarely it swings when it should not.
                with torch.no_grad():
                    attack = self.schema.action_names.index("attack")
                    predicted = (logits[..., buttons.logit + attack - buttons.action] > 0.0)[mask]
                    wanted = (targets[..., attack] > 0.5)[mask]
                    swings_caught += (predicted & wanted).float().sum().item()
                    swings += wanted.float().sum().item()
                    false_swings += (predicted & ~wanted).float().sum().item()
                    quiet += (~wanted).float().sum().item()

            logger.info(
                "imitation epoch %3d  loss %+.4f  swings made %5.1f%%  swings out of turn %5.1f%%  %4.1fs",
                epoch + 1,
                float(np.mean(losses)),
                100.0 * swings_caught / max(1.0, swings),
                100.0 * false_swings / max(1.0, quiet),
                time.time() - started,
            )

        self.actor.eval()
        self.actor.narrow_spread()
        logger.info("the copy explores with a spread of %s", self.actor.log_std.detach().exp().cpu().numpy().round(3))

    def set_teacher(self, segments: list[Segment]) -> None:
        """Keeps a record of the teacher, taken at random up to so many rows, for the pull back towards it in every
        update; see Config.teacher_weight."""

        order = np.random.default_rng(self.config.seed).permutation(len(segments))
        kept, rows = [], 0

        for index in order:
            if rows >= self.config.teacher_rows:
                break

            kept.append(segments[index])
            rows += segments[index].steps

        self.teacher = kept
        self.teacher_groups = pack_by_rows(kept, 4096)
        self.teacher_press_weight = self._press_weight(kept)
        logger.info("pulling towards the teacher with weight %.2f, from %d of its fights, %s steps",
                    self.config.teacher_weight, len(kept), f"{rows:,}")

    def _press_weight(self, segments: list[Segment]) -> Tensor:
        """How much more a press of each button counts than not pressing it, in copying the teacher.

        A button the teacher presses on one tick in twenty-five is learned as "never press it" by a plain likelihood:
        never swinging is right ninety six times in a hundred. Presses are weighted up to make them count, but only a
        little: weighted too far, the copy swings whenever in doubt, every swing restarts the cooldown, and a fighter that
        never waits for a full strength hit loses every fight.
        """
        buttons = next(head for head in self.schema.heads if head.kind == "binary")
        pressed = np.concatenate([segment.actions[:, buttons.action : buttons.action + buttons.size] for segment in segments])
        rate = (pressed > 0.5).mean(axis=0)
        weight = np.where(rate > 0.0, np.clip((1.0 - rate) / np.maximum(rate, 1e-9), 1.0, 3.0), 1.0)
        return torch.from_numpy(weight).to(torch.float32).to(self.device)

    def _teacher_batch(self, segments: list[Segment], group: list[int]) -> tuple[Tensor, Tensor, Tensor]:
        """A group of recorded fights, whole and padded to the longest, as observations, actions and a mask. Continuous
        targets are pulled in from the very edge, where a tanh can only reach by growing without bound."""

        width = max(segments[index].steps for index in group)
        obs = torch.zeros(len(group), width, self.schema.obs_dim)
        targets = torch.zeros(len(group), width, self.schema.act_dim)
        mask = torch.zeros(len(group), width, dtype=torch.bool)

        for row, index in enumerate(group):
            segment = segments[index]
            obs[row, : segment.steps] = torch.from_numpy(segment.obs[: segment.steps])
            targets[row, : segment.steps] = torch.from_numpy(segment.actions)
            mask[row, : segment.steps] = True

        for head in self.schema.heads:
            if head.kind == "continuous":
                targets[..., head.action : head.action + head.size].clamp_(-0.95, 0.95)

        return obs.to(self.device), targets.to(self.device), mask.to(self.device)

    def _teacher_log_prob(self, logits: Tensor, obs: Tensor, targets: Tensor, press_weight: Tensor) -> Tensor:
        """How likely the policy makes what the teacher did, per step.

        Scored with one spread for every continuous control, whatever each explores with. A narrower spread divides that
        control's error by its square, and with aim exploring at a tenth of full deflection its error would count
        fourteen times over and crowd out learning when to swing.
        """
        scoring_log_std = torch.full_like(self.actor.log_std, INITIAL_LOG_STD)
        log_prob = None

        for head, distribution in self.heads.distributions(logits, scoring_log_std, obs):
            if head.kind == "binary":
                taken = targets[..., head.action : head.action + head.size]
                weight = torch.where(taken > 0.5, press_weight, torch.ones_like(press_weight))
                piece = (distribution.log_prob(taken) * weight).sum(-1)
            else:
                piece = self.heads.log_prob([(head, distribution)], targets)

            log_prob = piece if log_prob is None else log_prob + piece

        return log_prob

    def _refresh_normalizer(self, segments: list[Segment]) -> None:
        if not segments:
            return

        rows = np.concatenate([segment.obs for segment in segments])
        self.normalizer.update(torch.from_numpy(rows))
        self.normalizer.into(self.actor)

    def _fall_back_to_cpu(self) -> None:
        self.device = torch.device("cpu")
        self.config.device = "cpu"

        self.actor.to(self.device)
        self.critic.to(self.device)
        self.optimizer = torch.optim.Adam(
            list(self.actor.parameters()) + list(self.critic.parameters()),
            lr=self.config.learning_rate,
            eps=1e-5,
        )

    # -----------------------------------------------------------------------------------------------------------
    # Episode figures
    # -----------------------------------------------------------------------------------------------------------

    def forget_rounds(self, through: int) -> None:
        """Drops every running total kept for agents of rounds that have finished."""

        for key in [key for key in self.episodes if key[0] <= through]:
            del self.episodes[key]

        for key in [key for key in self.reward_scaler.returns if key[0] <= through]:
            self.reward_scaler.forget(key)

    def take_episode_stats(self) -> tuple[list[float], list[float], int]:
        """Returns and empties the finished episode figures, so each line reports only what happened since the last."""

        returns, lengths, wins = self.finished_returns, self.finished_lengths, self.finished_wins
        self.finished_returns = []
        self.finished_lengths = []
        self.finished_wins = 0
        return returns, lengths, wins

    def report(self, stats: dict) -> None:
        returns, lengths, wins = self.take_episode_stats()
        episodes = len(returns)

        logger.info(
            "iteration %5d  steps %11s  episodes %5d  win %5.1f%%  return %+7.3f  length %6.1f  |  "
            "pi %+.4f  v %.4f  ent %.3f  clip %.3f  kl %.4f  ep %d  |  drift %.1e  %s %4.1fs  vram %.2fG  |  %6.1fm",
            self.iteration,
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
            stats["drift"],
            self.device.type,
            stats["seconds"],
            stats["vram"],
            (time.time() - self.started) / 60.0,
        )

        # Said on its own line rather than added to the one above, which scripts\watch.ps1 reads field by field.
        if self.config.teacher_weight > 0.0 and stats.get("teacher"):
            logger.info("iteration %5d  teacher loss %+.4f", self.iteration, stats["teacher"])

    # -----------------------------------------------------------------------------------------------------------
    # Weights and checkpoints
    # -----------------------------------------------------------------------------------------------------------

    def export(self, path: Path, iteration: int) -> Path:
        return export_weights(path, self.actor, self.schema.schema_id, iteration)

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")

        torch.save(
            {
                "config": asdict(self.config),
                "schema_id": self.schema.schema_id,
                "actor": self.actor.state_dict(),
                "critic": self.critic.state_dict(),
                "optimizer": self.optimizer.state_dict(),
                "normalizer": self.normalizer.state_dict(),
                "reward_scaler": self.reward_scaler.state_dict(),
                "iteration": self.iteration,
                "total_steps": self.total_steps,
            },
            temporary,
        )

        os.replace(temporary, path)

    def load(self, path: Path) -> None:
        state = torch.load(path, map_location=self.device, weights_only=False)

        if state["schema_id"] != self.schema.schema_id:
            raise ValueError(
                f"{path} was trained against schema {state['schema_id']:08x} and the game is running "
                f"{self.schema.schema_id:08x}"
            )

        self.actor.load_state_dict(state["actor"])
        self.critic.load_state_dict(state["critic"])
        self.optimizer.load_state_dict(state["optimizer"])
        self.normalizer.load_state_dict(state["normalizer"])
        self.reward_scaler.load_state_dict(state["reward_scaler"])
        self.iteration = state["iteration"]
        self.total_steps = state["total_steps"]

        logger.info("carrying on from %s at iteration %d", path, self.iteration)

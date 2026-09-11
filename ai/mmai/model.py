"""The network: an encoder, a GRU, and one head per kind of control.

The GRU is what gives the agent memory. A single observation says where the opponent is; it takes a sequence to know
which way it is moving, whether it just swung, or that it was behind a pillar a moment ago. The hidden state is carried
per agent across ticks by whoever is driving the model, and reset when an episode starts.

The heads match the action layout the game expects, one number per control:

    four continuous   movement and aim, Gaussian, in minus one to one
    six binary        jump, sprint, sneak, attack, use, use offhand, Bernoulli
    one categorical   which hotbar slot to hold, over nine, with empty slots masked off

The value head shares the same features, which is the usual arrangement for PPO and keeps the model small.
"""

from __future__ import annotations

from dataclasses import dataclass

import torch
from torch import Tensor, nn
from torch.distributions import Bernoulli, Categorical, Distribution, Normal

# The distributions check their arguments on every construction, which on a hot path is a set of extra kernels per
# head per tick for a mistake that would show up on the first step anyway.
Distribution.set_default_validate_args(False)

CONTINUOUS = 4
BINARY = 6
SLOTS = 9
ACT_DIM = CONTINUOUS + BINARY + 1


@dataclass
class ActionDistribution:
    """The three distributions the policy samples from, held together so log probability and entropy add up."""

    movement: Normal
    buttons: Bernoulli
    slot: Categorical

    def sample(self) -> Tensor:
        movement = self.movement.sample()
        buttons = self.buttons.sample()
        slot = self.slot.sample().unsqueeze(-1).to(movement.dtype)
        return torch.cat([movement, buttons, slot], dim=-1)

    def mode(self) -> Tensor:
        """The most likely action, for evaluation rather than exploration."""
        movement = self.movement.mean
        buttons = (self.buttons.probs > 0.5).to(movement.dtype)
        slot = self.slot.probs.argmax(dim=-1).unsqueeze(-1).to(movement.dtype)
        return torch.cat([movement, buttons, slot], dim=-1)

    def log_prob(self, action: Tensor) -> Tensor:
        movement = action[..., :CONTINUOUS]
        buttons = action[..., CONTINUOUS : CONTINUOUS + BINARY]
        slot = action[..., -1].long()

        return (
            self.movement.log_prob(movement).sum(-1)
            + self.buttons.log_prob(buttons).sum(-1)
            + self.slot.log_prob(slot)
        )

    def entropy(self) -> Tensor:
        return self.movement.entropy().sum(-1) + self.buttons.entropy().sum(-1) + self.slot.entropy()


class ActorCritic(nn.Module):
    def __init__(self, obs_dim: int, hidden: int = 256, encoder: int = 512) -> None:
        super().__init__()

        self.obs_dim = obs_dim
        self.hidden = hidden

        self.encoder = nn.Sequential(
            nn.Linear(obs_dim, encoder),
            nn.ReLU(),
            nn.Linear(encoder, hidden),
            nn.ReLU(),
        )

        self.gru = nn.GRU(hidden, hidden, batch_first=True)

        self.movement_mean = nn.Linear(hidden, CONTINUOUS)
        # State independent, which is the usual choice for PPO: the spread of exploration is learned once for the whole
        # task rather than per situation, and shrinks as the policy commits.
        self.movement_log_std = nn.Parameter(torch.zeros(CONTINUOUS))
        self.buttons = nn.Linear(hidden, BINARY)
        self.slot = nn.Linear(hidden, SLOTS)
        self.value = nn.Linear(hidden, 1)

        self._init()

    def _init(self) -> None:
        for module in self.modules():
            if isinstance(module, nn.Linear):
                nn.init.orthogonal_(module.weight, gain=2**0.5)
                nn.init.zeros_(module.bias)

        # A small policy head starts the agent near uniform, and a unit value head keeps early value estimates tame.
        for head in (self.movement_mean, self.buttons, self.slot):
            nn.init.orthogonal_(head.weight, gain=0.01)

        nn.init.orthogonal_(self.value.weight, gain=1.0)

        for name, parameter in self.gru.named_parameters():
            if "weight" in name:
                nn.init.orthogonal_(parameter)
            else:
                nn.init.zeros_(parameter)

    def initial_hidden(self, batch: int, device: torch.device | None = None) -> Tensor:
        return torch.zeros(1, batch, self.hidden, device=device)

    def features(self, obs: Tensor, hidden: Tensor) -> tuple[Tensor, Tensor]:
        """
        :param obs: ``(batch, time, obs_dim)``
        :param hidden: ``(1, batch, hidden)``, the state going into the first step
        :returns: features ``(batch, time, hidden)`` and the state coming out of the last step
        """
        return self.gru(self.encoder(obs), hidden)

    def distribution(self, features: Tensor, slot_mask: Tensor) -> ActionDistribution:
        """
        :param slot_mask: ``(..., SLOTS)`` bool, true where a slot may be chosen. A row with nothing allowed is left
            unmasked rather than made impossible, since something has to be picked.
        """
        logits = self.slot(features)
        usable = slot_mask.any(dim=-1, keepdim=True)
        logits = logits.masked_fill(~slot_mask & usable, float("-inf"))

        return ActionDistribution(
            movement=Normal(self.movement_mean(features), self.movement_log_std.exp()),
            buttons=Bernoulli(logits=self.buttons(features)),
            slot=Categorical(logits=logits),
        )

    def values(self, features: Tensor) -> Tensor:
        return self.value(features).squeeze(-1)


class RunningNormalizer:
    """Keeps the observation near unit scale, per feature, from what has been seen so far.

    The observation mixes zero or one terrain cells, positions scaled by view distance, and velocities scaled by a
    guess. Left as they are, the larger features would dominate the first layer and the smaller ones would take far
    longer to matter. Normalising by running statistics fixes that without anyone having to pick the scales by hand.

    Frozen during an update so every minibatch sees the same transform.
    """

    def __init__(self, size: int, clip: float = 10.0, epsilon: float = 1e-8) -> None:
        self.mean = torch.zeros(size, dtype=torch.float64)
        self.var = torch.ones(size, dtype=torch.float64)
        self.count = epsilon
        self.clip = clip
        self.epsilon = epsilon

    def update(self, batch: Tensor) -> None:
        batch = batch.reshape(-1, batch.shape[-1]).to(torch.float64)
        batch_mean = batch.mean(0)
        batch_var = batch.var(0, unbiased=False)
        batch_count = batch.shape[0]

        delta = batch_mean - self.mean
        total = self.count + batch_count

        self.mean = self.mean + delta * batch_count / total
        self.var = (self.var * self.count + batch_var * batch_count + delta**2 * self.count * batch_count / total) / total
        self.count = total

    def __call__(self, obs: Tensor) -> Tensor:
        scaled = (obs - self.mean.to(obs.dtype).to(obs.device)) / (self.var.sqrt() + self.epsilon).to(obs.dtype).to(obs.device)
        return scaled.clamp(-self.clip, self.clip)

    def state_dict(self) -> dict:
        return {"mean": self.mean, "var": self.var, "count": self.count}

    def load_state_dict(self, state: dict) -> None:
        self.mean = state["mean"]
        self.var = state["var"]
        self.count = state["count"]


class RewardScaler:
    """Divides rewards by the running spread of the discounted return, per agent.

    PPO's value head and policy head share a trunk, and the value loss grows with the square of the return. Left
    unscaled, a task whose returns reach the tens hands nearly the whole gradient to the value head, and the policy is
    dragged wherever that takes it. Scaling by what the returns actually look like keeps the two losses comparable
    whatever the reward happens to be measured in, which is what makes one set of hyperparameters work across the
    ladder from a zombie to self play.

    The reward itself is scaled, never shifted, so zero still means zero.
    """

    def __init__(self, gamma: float, epsilon: float = 1e-8) -> None:
        self.gamma = gamma
        self.epsilon = epsilon
        self.returns: dict[tuple[int, int], float] = {}
        self.mean = 0.0
        self.var = 1.0
        self.count = epsilon

    def scale(self, key: tuple[int, int], reward: float, done: bool) -> float:
        running = self.returns.get(key, 0.0) * self.gamma + reward

        delta = running - self.mean
        self.count += 1
        self.mean += delta / self.count
        self.var += (delta * (running - self.mean) - self.var) / self.count

        if done:
            self.returns.pop(key, None)
        else:
            self.returns[key] = running

        return reward / (self.var**0.5 + self.epsilon)

    def state_dict(self) -> dict:
        return {"mean": self.mean, "var": self.var, "count": self.count}

    def load_state_dict(self, state: dict) -> None:
        self.mean = state["mean"]
        self.var = state["var"]
        self.count = state["count"]

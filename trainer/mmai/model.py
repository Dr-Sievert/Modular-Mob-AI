"""The network the game runs, and the critic that never leaves this side.

The actor is an encoder, a GRU and one head per kind of control, and it is exactly what gets exported: the game runs the
same arithmetic on the same parameters, which is why there is a parity test rather than a hope.

The GRU is what gives the agent memory. A single observation says where the opponent is; it takes a sequence to know
which way it is moving, whether it just swung, or that it was behind a pillar a moment ago. The hidden state is carried
per agent by the game, recorded at the start of each stretch of a fight, and replayed from there when learning.

The critic stays here. It is a separate network rather than a head on the shared trunk, so the value loss never shapes
the features the policy is built from, and nothing in the game has to carry weights it will never use. It reads the
observation and the actor's memory of the fight so far, and it is free to grow privileged inputs the actor never sees.

The observation normaliser lives on the actor, as buffers, because the game has to apply exactly the same transform. It
is frozen during an update and refreshed afterwards from what the iteration actually saw.
"""

from __future__ import annotations

import torch
from torch import Tensor, nn
from torch.distributions import Bernoulli, Categorical, Distribution, Normal

from .schema import BINARY, CATEGORICAL, CONTINUOUS, Head, Schema
from .weights import Topology

# The distributions check their arguments on every construction, which on a hot path is a set of extra kernels per head
# for a mistake that would show up on the first step anyway.
Distribution.set_default_validate_args(False)


class PolicyHeads:
    """Walks the head table the game sent: squashing, masking, log probabilities and entropy, all from data.

    Blocks are independent, so their log probabilities add and their entropies add. Exclusivity only exists inside a
    categorical block, and a choice the game could not make is masked to minus infinity before the softmax, exactly as
    the game masks it, so the two sides agree about what was possible as well as about what was chosen.
    """

    def __init__(self, heads: tuple[Head, ...]) -> None:
        self.heads = heads

    def distributions(self, logits: Tensor, log_std: Tensor, raw_obs: Tensor) -> list[tuple[Head, Distribution]]:
        out: list[tuple[Head, Distribution]] = []

        for head in self.heads:
            piece = logits[..., head.logit : head.logit + head.size]

            if head.kind == CONTINUOUS:
                spread = log_std[head.std : head.std + head.size].exp()
                out.append((head, Normal(torch.tanh(piece), spread)))

            elif head.kind == BINARY:
                out.append((head, Bernoulli(logits=piece)))

            else:
                if head.mask >= 0:
                    allowed = raw_obs[..., head.mask : head.mask + head.size] > 0.0
                    # A row with nothing allowed is left unmasked rather than made impossible, since something has to be
                    # picked. The game does the same.
                    usable = allowed.any(dim=-1, keepdim=True)
                    piece = piece.masked_fill(~allowed & usable, float("-inf"))

                out.append((head, Categorical(logits=piece)))

        return out

    def log_prob(self, distributions: list[tuple[Head, Distribution]], actions: Tensor) -> Tensor:
        total = None

        for head, distribution in distributions:
            if head.kind == CATEGORICAL:
                chosen = actions[..., head.action].round().long()
                piece = distribution.log_prob(chosen)

            else:
                taken = actions[..., head.action : head.action + head.size]
                piece = distribution.log_prob(taken).sum(-1)

            total = piece if total is None else total + piece

        return total

    def entropy(self, distributions: list[tuple[Head, Distribution]]) -> Tensor:
        total = None

        for head, distribution in distributions:
            piece = distribution.entropy()

            if head.kind != CATEGORICAL:
                piece = piece.sum(-1)

            total = piece if total is None else total + piece

        return total


INITIAL_LOG_STD = -1.0
MIN_LOG_STD = -2.5
MAX_LOG_STD = 0.0

# Aim gets far less, as (start, least, most). Movement can wander at a third of full deflection and still get
# somewhere, but on aim that is a random jerk of about 22 degrees every tick, and no swing lands through it: the copy of
# the scripted fighter won 63% of its fights taking its most likely action and about 1% sampling. These are 0.10, 0.03
# and 0.20 of full deflection, about 6, 2 and 12 degrees a tick.
AIM_LOG_STD = (-2.3, -3.5, -1.6)

# Where a copy of a teacher starts instead: 0.14 of full deflection on movement, 0.05 on aim. A copy already knows what
# to do, and reinforcement learning improves whatever policy it samples from. At the spreads above, the copy of the
# scripted fighter won 90% of its fights on its most likely action and under half of them sampling, and training made the
# noisy version better at the expense of the one that gets deployed, which fell to 85%.
COPY_LOG_STD = -2.0
COPY_AIM_LOG_STD = -3.0


class Actor(nn.Module):
    """The exported network: normalise, encode, remember, decide."""

    def __init__(self, topology: Topology, obs_clip: float = 10.0, aim: tuple[int, ...] = ()) -> None:
        """:param aim: which spreads belong to aim controls, which explore far less than the rest"""
        super().__init__()

        self.topology = topology
        self.obs_clip = obs_clip

        self.register_buffer("norm_mean", torch.zeros(topology.obs_dim))
        self.register_buffer("norm_std", torch.ones(topology.obs_dim))

        self.fc1 = nn.Linear(topology.obs_dim, topology.h1)
        self.gru = nn.GRU(topology.h1, topology.hidden, batch_first=True)
        self.fc2 = nn.Linear(topology.hidden, topology.h3)
        self.out = nn.Linear(topology.h3, topology.out_dim)

        # State independent, which is the usual choice for PPO: the spread of exploration is learned once for the whole
        # task rather than per situation, and shrinks as the policy commits. It starts at a third of full deflection,
        # not all of it: at full deflection the aim swings up to sixty degrees a tick at random, the crosshair never
        # settles on anything, and no swing ever lands for the policy to learn from.
        start = torch.full((topology.std_dim,), INITIAL_LOG_STD)
        least = torch.full((topology.std_dim,), MIN_LOG_STD)
        most = torch.full((topology.std_dim,), MAX_LOG_STD)

        for index in aim:
            start[index], least[index], most[index] = AIM_LOG_STD

        self.aim = aim
        self.log_std = nn.Parameter(start)

        # Not saved with the weights: they are settings of the training, not something it learned.
        self.register_buffer("least_log_std", least, persistent=False)
        self.register_buffer("most_log_std", most, persistent=False)

        self._init()

    def narrow_spread(self) -> None:
        """Sets the spread a copy of a teacher starts reinforcement learning from; see COPY_LOG_STD."""
        with torch.no_grad():
            start = torch.full_like(self.log_std, COPY_LOG_STD)

            for index in self.aim:
                start[index] = COPY_AIM_LOG_STD

            self.log_std.copy_(start)

    def bound_spread(self) -> None:
        """Keeps exploration between a sliver and a ceiling. The entropy bonus alone would keep widening it, and does
        most when nothing is working yet, which is exactly when a wider spread makes things worse."""
        with torch.no_grad():
            self.log_std.copy_(torch.maximum(torch.minimum(self.log_std, self.most_log_std), self.least_log_std))

    def _init(self) -> None:
        for module in (self.fc1, self.fc2):
            nn.init.orthogonal_(module.weight, gain=2**0.5)
            nn.init.zeros_(module.bias)

        # A small output layer starts the agent near the middle of everything it could do.
        nn.init.orthogonal_(self.out.weight, gain=0.01)
        nn.init.zeros_(self.out.bias)

        for name, parameter in self.gru.named_parameters():
            if "weight" in name:
                nn.init.orthogonal_(parameter)
            else:
                nn.init.zeros_(parameter)

    @staticmethod
    def for_schema(schema: Schema, h1: int, hidden: int, h3: int, obs_clip: float = 10.0) -> "Actor":
        aim = tuple(
            head.std + i
            for head in schema.heads
            if head.kind == CONTINUOUS
            for i in range(head.size)
            if schema.action_names[head.action + i].startswith("aim")
        )

        return Actor(Topology(schema.obs_dim, h1, hidden, h3, schema.logit_dim, schema.std_dim), obs_clip, aim)

    def normalise(self, raw_obs: Tensor) -> Tensor:
        """The same three operations in the same order as the game: subtract, divide, clamp."""
        return ((raw_obs - self.norm_mean) / self.norm_std).clamp(-self.obs_clip, self.obs_clip)

    def forward(self, raw_obs: Tensor, hidden: Tensor) -> tuple[Tensor, Tensor]:
        """
        :param raw_obs: ``(batch, time, obs_dim)``, exactly as the game recorded it
        :param hidden: ``(batch, hidden)``, the state going into the first step
        :returns: the raw logits ``(batch, time, out_dim)`` and the state after every step ``(batch, time, hidden)``
        """
        encoded = torch.relu(self.fc1(self.normalise(raw_obs)))
        states, _ = self.gru(encoded, hidden.unsqueeze(0).contiguous())

        return self.out(torch.relu(self.fc2(states))), states


class Critic(nn.Module):
    """What a position was worth, for the advantages. Never exported, so it may know more than the actor does."""

    def __init__(self, obs_dim: int, hidden: int, width: int = 256) -> None:
        super().__init__()

        self.net = nn.Sequential(
            nn.Linear(obs_dim + hidden, width),
            nn.ReLU(),
            nn.Linear(width, width),
            nn.ReLU(),
            nn.Linear(width, 1),
        )

        for module in self.net:
            if isinstance(module, nn.Linear):
                nn.init.orthogonal_(module.weight, gain=2**0.5)
                nn.init.zeros_(module.bias)

        nn.init.orthogonal_(self.net[-1].weight, gain=1.0)

    def forward(self, normalised_obs: Tensor, memory: Tensor) -> Tensor:
        return self.net(torch.cat([normalised_obs, memory], dim=-1)).squeeze(-1)


class RunningNormalizer:
    """Keeps the observation near unit scale, per feature, from what has been seen so far.

    The observation mixes zero or one terrain cells, positions scaled by view distance, and velocities scaled by a
    guess. Left as they are, the larger features would dominate the first layer and the smaller ones would take far
    longer to matter. Normalising by running statistics fixes that without anyone having to pick the scales by hand.

    Updated once per iteration, after the update rather than before it, because the log probabilities the game recorded
    were worked out behind the statistics the game was given. Moving them first would make the ratio at the start of an
    update something other than one, which is the one thing PPO assumes.

    The spread is floored. A feature that barely moved so far, a terrain cell that has always been solid or a flag that
    has never been set, would otherwise be divided by next to nothing, and the first time it did move it would hit the
    clip at full strength and swamp everything else going into the network.
    """

    def __init__(self, size: int, epsilon: float = 1e-8, floor: float = 0.1) -> None:
        self.mean = torch.zeros(size, dtype=torch.float64)
        self.var = torch.ones(size, dtype=torch.float64)
        self.count = epsilon
        self.epsilon = epsilon
        self.floor = floor

    def update(self, batch: Tensor) -> None:
        """Takes in a batch of rows, which may be on any device; the statistics themselves stay on the CPU."""

        batch = batch.reshape(-1, batch.shape[-1]).to(torch.float64)
        batch_mean = batch.mean(0).cpu()
        batch_var = batch.var(0, unbiased=False).cpu()
        batch_count = batch.shape[0]

        delta = batch_mean - self.mean
        total = self.count + batch_count

        self.mean = self.mean + delta * batch_count / total
        self.var = (self.var * self.count + batch_var * batch_count + delta**2 * self.count * batch_count / total) / total
        self.count = total

    def into(self, actor: Actor) -> None:
        """Hands the statistics to the network that will be exported with them."""
        with torch.no_grad():
            actor.norm_mean.copy_(self.mean.to(torch.float32))
            actor.norm_std.copy_(self.var.sqrt().clamp(min=self.floor).to(torch.float32))

    def state_dict(self) -> dict:
        return {"mean": self.mean, "var": self.var, "count": self.count}

    def load_state_dict(self, state: dict) -> None:
        # The statistics live on the CPU, where the rows they are updated from arrive. A checkpoint loaded straight onto
        # the GPU would otherwise put them there, and the first update after resuming would mix the two.
        self.mean = state["mean"].cpu()
        self.var = state["var"].cpu()
        self.count = state["count"]


class RewardScaler:
    """Divides rewards by the running spread of the discounted return, per agent.

    The value loss grows with the square of the return, so left unscaled a task whose returns reach the tens hands nearly
    the whole gradient to the critic. Scaling by what the returns actually look like keeps the two losses comparable
    whatever the reward happens to be measured in, which is what makes one set of hyperparameters work across the ladder
    from a vindicator to self play.

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

    def forget(self, key: tuple[int, int]) -> None:
        self.returns.pop(key, None)

    def state_dict(self) -> dict:
        return {"mean": self.mean, "var": self.var, "count": self.count}

    def load_state_dict(self, state: dict) -> None:
        self.mean = state["mean"]
        self.var = state["var"]
        self.count = state["count"]

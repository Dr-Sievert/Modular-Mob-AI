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

The auxiliary heads stay here too, and for the same reason as the critic: they are how the memory is given something
dense to learn from, and the game has no use for a parameter that only ever predicted something. They are a module of
their own rather than part of the actor, so what gets exported is unchanged by their existence.
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

        # One small matrix run over every enemy slot in turn, where this network has one; see Topology and Actor.encode.
        if topology.pooled():
            self.slot_encoder = nn.Linear(topology.slot_stride, topology.slot_enc)

        self.fc1 = nn.Linear(topology.fc1_in(), topology.h1)
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
        encoder = (self.slot_encoder,) if self.topology.pooled() else ()

        for module in (self.fc1, self.fc2) + encoder:
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
    def for_schema(schema: Schema, h1: int, hidden: int, h3: int, obs_clip: float = 10.0, slot_enc: int = 0) -> "Actor":
        """
        :param slot_enc: width of the shared encoder over the enemy slots, or zero for a plain first layer. Where the slots
            are comes from the body's own schema, which is the only thing that knows.
        """

        aim = tuple(
            head.std + i
            for head in schema.heads
            if head.kind == CONTINUOUS
            for i in range(head.size)
            if schema.action_names[head.action + i].startswith("aim")
        )

        enemies = schema.require("enemies") if slot_enc > 0 else None

        topology = Topology(
            schema.obs_dim, h1, hidden, h3, schema.logit_dim, schema.std_dim,
            enemies.offset if enemies else 0,
            enemies.facts["slots"] if enemies else 0,
            enemies.facts["stride"] if enemies else 0,
            slot_enc if enemies else 0,
        )

        return Actor(topology, obs_clip, aim)

    def normalise(self, raw_obs: Tensor) -> Tensor:
        """The same three operations in the same order as the game: subtract, divide, clamp."""
        return ((raw_obs - self.norm_mean) / self.norm_std).clamp(-self.obs_clip, self.obs_clip)

    def encode(self, normalised: Tensor, raw_obs: Tensor) -> Tensor:
        """
        What the first layer is given: the observation itself, or the observation with its ten enemy slots replaced by the
        features a shared encoder found in them.

        Each occupied slot goes through the same small matrix and the largest answer per feature is kept, so what reaches
        the rest of the network is *what is out there* rather than what is in slot three. A plain first layer has to learn
        every opponent once per slot, which is why one skeleton was beaten 84% of the time and two 9%. Max rather than
        mean because a fight is decided by the most dangerous thing in view; how many things there are is already in the
        self block, as the count of bodies in range.

        **Only occupied slots are pooled**, and the mask comes from the raw observation rather than the normalised one. An
        empty slot is all zeros, so the encoder would answer it with ReLU(bias), and any feature whose bias came out
        positive would then be won by slots with nothing in them: the network's view of the worst thing out there would be
        partly noise from empty air. The mask cannot be taken from the normalised row because an absent slot's present flag
        normalises to (0 - mean) / std, which is not zero. With nothing in view at all the features are zero, which is a
        thing the network can recognise and no real opponent produces.

        The slots are cut out and the rest kept in order, so what the first layer sees is the blocks before the slots, then
        the pooled features, then the blocks after: the game does exactly the same, and the parity check proves it.
        """

        topology = self.topology

        if not topology.pooled():
            return normalised

        at, count, stride = topology.slot_at, topology.slots, topology.slot_stride

        slots = normalised[..., at:at + count * stride].unflatten(-1, (count, stride))
        present = raw_obs[..., at:at + count * stride].unflatten(-1, (count, stride))[..., :1] > 0.5

        encoded = torch.relu(self.slot_encoder(slots))
        pooled = encoded.masked_fill(~present, 0.0).amax(dim=-2).clamp(min=0.0)

        return torch.cat([normalised[..., :at], pooled, normalised[..., at + count * stride:]], dim=-1)

    def forward(self, raw_obs: Tensor, hidden: Tensor) -> tuple[Tensor, Tensor]:
        """
        :param raw_obs: ``(batch, time, obs_dim)``, exactly as the game recorded it
        :param hidden: ``(batch, hidden)``, the state going into the first step
        :returns: the raw logits ``(batch, time, out_dim)`` and the state after every step ``(batch, time, hidden)``
        """
        encoded = torch.relu(self.fc1(self.encode(self.normalise(raw_obs), raw_obs)))
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


def masked_mean(values: Tensor, mask: Tensor) -> Tensor:
    """The mean of the values a mask allows, and zero where it allows none.

    Written as a sum over a count rather than ``values[mask].mean()`` because the count can be zero -- a minibatch in
    which nothing is known -- and the mean of nothing is a NaN that would take the whole update with it.
    """

    return (values * mask).sum() / mask.sum().clamp(min=1)


class AuxiliaryHeads(nn.Module):
    """What the actor's memory is asked to predict beside choosing an action. Never exported.

    The GRU is trained by the policy gradient and by nothing else: one advantage per step, mostly noise, is the whole of
    what its 128 numbers ever learn about what the fight is doing. The critic reads the memory as well, but the value loss
    is deliberately kept from reaching the policy's features (see ``Trainer._learn``), so nothing it works out about the
    fight arrives there either.

    These heads let a dense signal in, as prediction rather than as value. Every target is already in the rows the game
    wrote down, so none of them costs the game a thing:

    - **what the body will be next tick**: the observation's own self block one step on, through the same normaliser the
      input goes through. Velocity, the attack cooldown, hurt time, on the ground or not -- a memory that can say where its
      own body is about to be has learned what the controls do, which is most of what a fighter needs. Copying the fields
      that barely move is free and stops mattering as soon as the head learns it; what is left of the loss is carried by
      the fields that actually move, which is why the value rather than the change is enough to ask for. The rest of the
      observation is left out on purpose: the terrain grid is 405 of the humanoid's 770 numbers and hardly changes from
      tick to tick, so predicting it is easy for the wrong reason.
    - **what this step earns**: the same scaled reward the critic is fitted against. This is exactly the signal the wall
      around the value loss keeps out, and a memory that knows a blow is about to land, or about to be taken, is the one
      the policy wants to be built on.
    - **whether the fight ends soon**, within a horizon the Trainer picks. A win pays a bonus for being quick and the clock
      running out is scored as a loss, so telling a fight that is seconds from over from one that has just begun is worth
      real reward, and the policy gradient teaches it only through the outcome.

    One shared layer over the memory and a linear head each. Deliberately small: the work of being right should land on
    the memory, which is the thing being shaped, and not on a predictor deep enough to do it alone.
    """

    def __init__(self, hidden: int, state_size: int, width: int) -> None:
        """:param state_size: how wide the body's own block is, or zero for a body that has no such block at all"""
        super().__init__()

        self.shared = nn.Linear(hidden, width)
        self.state = nn.Linear(width, state_size) if state_size > 0 else None
        self.reward = nn.Linear(width, 1)
        self.ending = nn.Linear(width, 1)

        nn.init.orthogonal_(self.shared.weight, gain=2**0.5)
        nn.init.zeros_(self.shared.bias)

        for head in (self.state, self.reward, self.ending):
            if head is not None:
                nn.init.orthogonal_(head.weight, gain=1.0)
                nn.init.zeros_(head.bias)

    def forward(self, memory: Tensor) -> tuple[Tensor | None, Tensor, Tensor]:
        """
        :param memory: the actor's GRU output, ``(batch, time, hidden)``, after the recurrence and before the policy's
            own layers, so that what this shapes is the memory itself
        :returns: the next body block (or None for a body without one), what the step earns, and the logit of the fight
            ending soon
        """

        features = torch.relu(self.shared(memory))

        return (
            self.state(features) if self.state is not None else None,
            self.reward(features).squeeze(-1),
            self.ending(features).squeeze(-1),
        )

    def losses(self, memory: Tensor, state: Tensor | None, reward: Tensor, ending: Tensor, mask: Tensor,
               ending_mask: Tensor) -> dict[str, Tensor]:
        """One loss per head, named, each averaged over the steps its own mask allows.

        The ending has a mask of its own because the answer is not always known: a segment cut off mid fight says nothing
        about what happened after its last row. See ``Trainer._aux_targets``.
        """

        predicted_state, predicted_reward, predicted_ending = self(memory)
        losses: dict[str, Tensor] = {}

        if predicted_state is not None and state is not None:
            losses["state"] = masked_mean(((predicted_state - state) ** 2).mean(-1), mask)

        losses["reward"] = masked_mean((predicted_reward - reward) ** 2, mask)
        losses["ending"] = masked_mean(
            nn.functional.binary_cross_entropy_with_logits(predicted_ending, ending, reduction="none"), ending_mask
        )

        return losses

    def describe(self) -> str:
        state = [f"the body's own {self.state.out_features} numbers a tick on"] if self.state is not None else []

        return ", ".join(state + ["what the step earns", "whether the fight ends soon"])


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

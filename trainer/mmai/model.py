"""The network the game runs, and the critic that never leaves this side.

The actor is an encoder, a GRU and one head per kind of control, and it is exactly what gets exported: the game runs the
same arithmetic on the same parameters, which is why there is a parity test rather than a hope.

The GRU is what gives the agent memory. A single observation says where the opponent is; it takes a sequence to know
which way it is moving, whether it just swung, or that it was behind a pillar a moment ago. The hidden state is carried
per agent by the game, recorded at the start of each stretch of a fight, and replayed from there when learning.

The critic stays here. It is a separate network rather than a head on the shared trunk, so the value loss never shapes
the features the policy is built from, and nothing in the game has to carry weights it will never use. Because it is
never exported it has a memory of its own rather than a borrowed one, and inputs the actor never sees; see Critic.

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


class SlotAttention(nn.Module):
    """K heads that each pick one enemy slot out and hand it to the layer above, with hard exclusion between them.

    The enemy block is ten slots of the same shape, and a plain first layer gives every slot its own 31 columns. In every
    one-on-one fight the opponent sits in slot 0 and the other nine are all zeros, so the columns for slots 2..9 never
    received a gradient worth the name and their statistics sit on the normaliser's floor -- and a body that then turns up
    in slot 5 arrives as inputs of magnitude 5 to 10 against weights that are still near their initialisation. Measured on
    blast7 over 60 real one-on-one segments: one idle bystander written into slot 1 moved the deterministic aim by 22
    degrees of yaw and 13 of pitch a tick, the attack logit by 2.4, and flipped the chosen hotbar slot on 32% of ticks; the
    first layer's pre-activation shift from that one idle zombie was 1.0 in slot 0, the real signal, and 8.3 in slot 8.

    So the slots are not read where they sit. Each head scores every occupied slot, softmaxes over them and over one
    virtual **empty token**, and hands the layer above the ``stride`` floats it got; the slot a head scored highest is then
    excluded from the heads after it, so head 1 ranks what is left rather than the same body again. What reaches the first
    layer is therefore K bodies chosen by what they are doing, and nothing about the arrangement of the rest -- which is
    the invariance, by construction rather than by curriculum.

    Three details that both sides have to agree on to the letter, because the game runs this same arithmetic:

    - **occupancy comes from the raw row**, never the normalised one: an absent slot's present flag normalises to
      ``(0 - mean) / std``, which is not zero and is often well above a half.
    - **the exclusion is a set operation**, not a soft one. No gradient runs through the choice; it runs through each
      head's softmax weights, as in any top-k selection. The highest score wins, and the **lowest slot index** wins a tie.
    - **the empty token is a candidate for every head**, and with nothing occupied at all a head reads exactly it. That is
      what makes "nothing left to attend to" a thing the network can recognise rather than a row of zeros, which is a real
      observation of a body standing at the origin.
    """

    def __init__(self, topology: Topology) -> None:
        super().__init__()

        self.topology = topology

        # One row of weights over a slot per head, one bias, and one score for the empty token. Small random weights
        # rather than zeros: with every score identical every slot ties, and the tie rule would then hand head 0 slot 0 on
        # every row of the first iteration, which is the positional reading this exists to remove. The converter overwrites
        # all three with an ordering that already knows what a fight looks like; see train.py attend.
        self.score_w = nn.Parameter(torch.randn(topology.slot_heads, topology.slot_stride) * 0.1)
        self.score_b = nn.Parameter(torch.zeros(topology.slot_heads))
        self.score_empty = nn.Parameter(torch.zeros(topology.slot_heads))

    def forward(self, normalised: Tensor, raw_obs: Tensor, empty: Tensor) -> Tensor:
        """
        :param normalised: the whole observation after the normaliser, ``(..., obs_dim)``
        :param raw_obs: the same rows as the game recorded them, which is where occupancy is read from
        :param empty: what an all-zero slot normalises to, ``(stride,)``; see ``Actor.slot_empty``
        :returns: the first layer's input: the blocks before the slots, one slot per head, then the blocks after
        """

        topology = self.topology
        at, count, stride = topology.slot_at, topology.slots, topology.slot_stride

        slots = normalised[..., at : at + count * stride].unflatten(-1, (count, stride))
        present = raw_obs[..., at : at + count * stride].unflatten(-1, (count, stride))[..., 0] > 0.5

        # (..., heads, slots): every head's opinion of every slot, before anything is taken out.
        scores = (slots @ self.score_w.T).transpose(-1, -2) + self.score_b.unsqueeze(-1)

        index = torch.arange(count, device=normalised.device)
        taken = torch.zeros_like(present)
        picked = []

        for head in range(topology.slot_heads):
            # Minus infinity rather than a large negative: it exponentiates to exactly zero, so a slot that is empty or
            # already taken contributes nothing to the sum and nothing to the gradient.
            score = scores[..., head, :].masked_fill(~(present & ~taken), float("-inf"))
            empty_score = self.score_empty[head]

            best = score.amax(dim=-1)
            top = torch.maximum(best, empty_score)

            weights = torch.exp(score - top.unsqueeze(-1))
            weight_empty = torch.exp(empty_score - top)
            total = weight_empty + weights.sum(-1)

            pooled = weight_empty.unsqueeze(-1) * empty + (weights.unsqueeze(-1) * slots).sum(-2)
            picked.append(pooled / total.unsqueeze(-1))

            if head + 1 == topology.slot_heads:
                break

            # The lowest slot index among those that scored highest, and only where the highest beat the empty token. With
            # no candidates at all the best is minus infinity, which loses to the token, so nothing is taken. The clamp is
            # against a row that is not a number at all, where nothing equals the maximum and the sentinel would be
            # chosen: such a row is dropped before any update, and a crash here would say nothing about it.
            highest = torch.where(score == best.unsqueeze(-1), index, index.new_full((), count)).amin(-1).clamp(max=count - 1)
            chose = (best >= empty_score).unsqueeze(-1)

            taken = taken | (nn.functional.one_hot(highest, count).bool() & chose)

        return torch.cat([normalised[..., :at], *picked, normalised[..., at + count * stride :]], dim=-1)


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

        # The heads that pick the enemy slots out, where this network has them; see SlotAttention and Actor.encode.
        if topology.attended():
            self.attention = SlotAttention(topology)

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
    def for_schema(schema: Schema, h1: int, hidden: int, h3: int, obs_clip: float = 10.0,
                   slot_heads: int = 0) -> "Actor":
        """
        :param slot_heads: how many heads read the enemy slots, or zero for a first layer that takes every slot where it
            sits. Where the slots are comes from the body's own schema, which is the only thing that knows.
        """

        aim = tuple(
            head.std + i
            for head in schema.heads
            if head.kind == CONTINUOUS
            for i in range(head.size)
            if schema.action_names[head.action + i].startswith("aim")
        )

        enemies = schema.require("enemies") if slot_heads > 0 else None

        topology = Topology(
            schema.obs_dim, h1, hidden, h3, schema.logit_dim, schema.std_dim,
            enemies.offset if enemies else 0,
            enemies.facts["slots"] if enemies else 0,
            enemies.facts["stride"] if enemies else 0,
            slot_heads if enemies else 0,
        )

        return Actor(topology, obs_clip, aim)

    def normalise(self, raw_obs: Tensor) -> Tensor:
        """The same three operations in the same order as the game: subtract, divide, clamp."""
        return ((raw_obs - self.norm_mean) / self.norm_std).clamp(-self.obs_clip, self.obs_clip)

    def slot_empty(self) -> Tensor:
        """What an all-zero enemy slot normalises to: the empty token every attention head may read instead of a body.

        The same three operations in the same order as everything else -- subtract, divide, clamp -- on the statistics of
        the first slot. For an attended network those statistics are tied, so every slot carries the same ones and the
        first is simply where they are written; see RunningNormalizer.
        """

        at, stride = self.topology.slot_at, self.topology.slot_stride
        mean, spread = self.norm_mean[at : at + stride], self.norm_std[at : at + stride]

        return ((torch.zeros_like(mean) - mean) / spread).clamp(-self.obs_clip, self.obs_clip)

    def encode(self, normalised: Tensor, raw_obs: Tensor) -> Tensor:
        """
        What the first layer is given: the observation itself, or the observation with its ten enemy slots replaced by the
        one slot each attention head picked out.

        The slots are cut out and the rest kept in order, so what the first layer sees is the blocks before the slots, then
        the heads' slots, then the blocks after: the game does exactly the same, and the parity check proves it. See
        SlotAttention for why the slots cannot be read where they sit.
        """

        if not self.topology.attended():
            return normalised

        return self.attention(normalised, raw_obs, self.slot_empty())

    def forward(self, raw_obs: Tensor, hidden: Tensor) -> tuple[Tensor, Tensor]:
        """
        :param raw_obs: ``(batch, time, obs_dim)``, exactly as the game recorded it
        :param hidden: ``(batch, hidden)``, the state going into the first step
        :returns: the raw logits ``(batch, time, out_dim)`` and the state after every step ``(batch, time, hidden)``
        """
        encoded = torch.relu(self.fc1(self.encode(self.normalise(raw_obs), raw_obs)))
        states, _ = self.gru(encoded, hidden.unsqueeze(0).contiguous())

        return self.out(torch.relu(self.fc2(states))), states


# What only the trainer knows, in order; Trainer._scale fills these in. All of it is the past. A value estimate may
# condition on anything that happened before the step it prices and must see nothing after it: the return is the target, so
# an input carrying any part of the return would teach the critic to read the answer off its own inputs instead of learning
# what a position is worth.
#
#   paid   the fight's reward account before this row, in the same scaled units as the target
#   last   what the action on the row before this one earned, and nought on a segment's first row
#   age    how many ticks the fight has run before this row, over EPISODE_TICKS
#   start  one on a segment's first row: the one row whose previous reward is in a shard this side no longer has, and
#          whose own memory starts from nothing rather than from where the fight had got to
PRIVILEGED = ("paid", "last", "age", "start")

# What only the *game* knows, which it now writes into every rollout row: the other side as it really is rather than as the
# agent perceives it, what the agent itself carries, and how long the fight is given. The game's arena/FightFacts.java is
# the one place any of it is argued for, and this tuple is its order; the two have to be changed together, and the shard
# header states its own width so a mismatch is caught by name rather than by a silently shifted column.
SHARD_PRIVILEGED = (
    "foes",
    "foe_health",
    "foe_hearts",
    "foe_damage",
    "foe_armour",
    "foe_fuse",
    "went_for",
    "own_damage",
    "own_armour",
    "limit",
)

# The critic's whole privileged block, the trainer's columns first. That order is what lets a critic trained before a column
# was added carry on reading only the columns it was built for; see Critic.forward.
PRIVILEGED_COLUMNS = len(PRIVILEGED) + len(SHARD_PRIVILEGED)

# Only the scale the age is divided by, so a training fight's clock lands near one; a league matchup sets its own limit
# and a longer fight simply reads above one. The observation already carries the elapsed *fraction* of this fight's limit
# (SELF_CLOCK), and the shard's `limit` says how long the limit itself is.
#
# It is the game's AgentReward.DEFAULT_MAX_TICKS, which is the one place the number is written; nothing here can read a Java
# constant, so test_shard.test_the_trainer_divides_the_age_by_the_games_own_cap reads that source and holds this to it.
EPISODE_TICKS = 1200


class Critic(nn.Module):
    """What a position was worth, for the advantages. Never exported, so it may remember more and know more than the actor.

    **A memory of its own.** It used to read the actor's hidden state, recovered by the replay and held still: memory
    trained for another job, summarising what a policy needs in order to choose a button rather than what a value needs in
    order to price a position. It now runs its own GRU over the segment the way the actor's is run, with its own state
    carried from chunk to chunk by the replay. On the league the scripted teacher still scores about 78% where the best
    network scores about 55%, and advantage noise is the lever on that gap: every improvement in credit assignment
    anywhere goes through the value estimate.

    **It starts each segment from nothing.** The actor's state on a segment's first row comes from the game, which carried
    it tick by tick and wrote it down. Nothing carries the critic's: the game never runs the critic, so there is no
    recording to replay from, and a state held here from the previous iteration would have been produced by weights that
    have since moved. Zero is the honest reading, and the privileged inputs are what make it cheap — the age and the
    reward already paid say where in the fight this row is without a memory having to.

    **What it knows that the actor does not**, from two places and in this order: PRIVILEGED, which only the trainer knows —
    the reward, which appears in no observation, and the shape of the episode the segments were cut out of — and then
    SHARD_PRIVILEGED, which only the game knows and now writes into every row: what the other side really is, what the agent
    carries, and how long the fight is given. None of it reaches the actor, and none of it is in the exported weights.

    With no GRU it is exactly the network that was here before this, parameter names included, so a state saved by an
    older trainer loads into it unchanged; see Config.critic_gru and Trainer.load.
    """

    def __init__(self, obs_dim: int, hidden: int, width: int = 256, gru: int = 0, privileged: int = 0,
                 topology: Topology | None = None) -> None:
        """
        :param gru: width of its own recurrent memory, or zero for the plain feed-forward critic
        :param privileged: how many privileged columns it reads, counted from the first; the rest are ignored
        :param topology: the actor's shape, where that actor attends to its enemy slots. The critic reads the same
            observation and has the same ten-slots-of-the-same-shape problem with it, so it gets the same attention over
            the front of its input -- with scores of its own, since what a value wants to look at is not what a policy
            does. None, or a plain topology, is the critic that was here before, parameter names included.
        """
        super().__init__()

        self.gru_width = gru
        self.privileged = privileged
        self.attention = SlotAttention(topology) if topology is not None and topology.attended() else None
        inputs = (topology.fc1_in() if self.attention is not None else obs_dim) + hidden + privileged

        if gru > 0:
            # Named in the order the forward pass runs them, which is the actor's shape with the slots left out: encode,
            # remember, decide what the position is worth.
            self.encoder = nn.Linear(inputs, width)
            self.gru = nn.GRU(width, gru, batch_first=True)
            self.middle = nn.Linear(gru, width)
            self.value = nn.Linear(width, 1)
            linear = (self.encoder, self.middle, self.value)

        else:
            self.gru = None
            self.net = nn.Sequential(
                nn.Linear(inputs, width),
                nn.ReLU(),
                nn.Linear(width, width),
                nn.ReLU(),
                nn.Linear(width, 1),
            )
            linear = tuple(module for module in self.net if isinstance(module, nn.Linear))

        for module in linear:
            nn.init.orthogonal_(module.weight, gain=2**0.5)
            nn.init.zeros_(module.bias)

        nn.init.orthogonal_(linear[-1].weight, gain=1.0)

        if self.gru is not None:
            for name, parameter in self.gru.named_parameters():
                if "weight" in name:
                    nn.init.orthogonal_(parameter)
                else:
                    nn.init.zeros_(parameter)

    def forward(self, normalised_obs: Tensor, memory: Tensor, privileged: Tensor, hidden: Tensor,
                raw_obs: Tensor | None = None, empty: Tensor | None = None) -> tuple[Tensor, Tensor]:
        """
        :param memory: the actor's hidden state at each step, ``(batch, time, hidden)``
        :param privileged: ``(batch, time, PRIVILEGED_COLUMNS)``; the columns past what this critic reads are ignored, so the
            trainer works them out once, every shape of critic takes the same batch, and a critic already trained against
            fewer columns goes on reading exactly the ones it learned
        :param hidden: ``(batch, gru)``, its own state going into the first step, zero width where it has no memory
        :param raw_obs: the same rows as the game recorded them, and :param empty: the actor's ``slot_empty()``. Both are
            needed only by an attended critic, which reads occupancy off the raw row exactly as the actor does
        :returns: the value of every step ``(batch, time)`` and its own state after every step ``(batch, time, gru)``
        """

        if self.attention is not None:
            if raw_obs is None or empty is None:
                raise ValueError("an attended critic needs the raw observation and the empty slot as well")

            normalised_obs = self.attention(normalised_obs, raw_obs, empty)

        inputs = torch.cat([normalised_obs, memory, privileged[..., : self.privileged]], dim=-1)

        if self.gru is None:
            return self.net(inputs).squeeze(-1), hidden.unsqueeze(1).expand(-1, inputs.shape[-2], -1)

        states, _ = self.gru(torch.relu(self.encoder(inputs)), hidden.unsqueeze(0).contiguous())

        return self.value(torch.relu(self.middle(states))).squeeze(-1), states


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
      observation is left out on purpose: the terrain grid is 405 of the humanoid's 792 numbers and hardly changes from
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

    **An attended network's enemy slots share one set of statistics**, taken from the occupied slots alone. Per slot they
    are useless to it: in a one-on-one fight slots 2..9 hold nothing on every row of the run, so their means go to zero and
    their spreads to the floor, and the one time a body does arrive in slot 5 it arrives as inputs of magnitude 5 to 10.
    Since attention reads a slot for what is in it rather than for where it sits, the statistics have to be the statistics
    of a body and not of a position. They are written into every slot's entries of the weight file, so the file's layout
    and the game's normalise step are unchanged -- the game need not know they are tied, it just applies them.
    """

    def __init__(self, size: int, epsilon: float = 1e-8, floor: float = 0.1,
                 slots: tuple[int, int, int] | None = None) -> None:
        """:param slots: ``(at, count, stride)`` of the enemy block for an attended network, whose slots share one set of
        statistics; None for the untied per-offset statistics every plain network has."""

        self.mean = torch.zeros(size, dtype=torch.float64)
        self.var = torch.ones(size, dtype=torch.float64)
        self.count = epsilon
        self.epsilon = epsilon
        self.floor = floor

        self.slots = slots
        stride = slots[2] if slots else 0

        self.tied_mean = torch.zeros(stride, dtype=torch.float64)
        self.tied_var = torch.ones(stride, dtype=torch.float64)
        self.tied_count = epsilon

    def update(self, batch: Tensor) -> None:
        """Takes in a batch of raw rows, which may be on any device; the statistics themselves stay on the CPU."""

        batch = batch.reshape(-1, batch.shape[-1]).to(torch.float64)

        self.mean, self.var, self.count = self._merge(
            self.mean, self.var, self.count, batch.mean(0).cpu(), batch.var(0, unbiased=False).cpu(), batch.shape[0])

        if self.slots is None:
            return

        at, count, stride = self.slots

        # Occupancy off the raw row, as everything else reads it: an absent slot's present flag normalises to something
        # that is not zero, and counting empty slots in would put the statistics of a body where it never stands.
        rows = batch[:, at : at + count * stride].reshape(-1, stride)
        rows = rows[rows[:, 0] > 0.5]

        if rows.shape[0] == 0:
            return

        self.tied_mean, self.tied_var, self.tied_count = self._merge(
            self.tied_mean, self.tied_var, self.tied_count,
            rows.mean(0).cpu(), rows.var(0, unbiased=False).cpu(), rows.shape[0])

    def _merge(self, mean: Tensor, var: Tensor, count: float, batch_mean: Tensor, batch_var: Tensor,
               batch_count: int) -> tuple[Tensor, Tensor, float]:
        """One running (mean, variance, count) brought up to date with a batch's, in doubles."""

        delta = batch_mean - mean
        total = count + batch_count

        return (
            mean + delta * batch_count / total,
            (var * count + batch_var * batch_count + delta**2 * count * batch_count / total) / total,
            total,
        )

    def into(self, actor: Actor) -> None:
        """Hands the statistics to the network that will be exported with them."""
        with torch.no_grad():
            actor.norm_mean.copy_(self.mean.to(torch.float32))
            actor.norm_std.copy_(self.var.sqrt().clamp(min=self.floor).to(torch.float32))

            if self.slots is None or not actor.topology.attended():
                return

            at, count, stride = self.slots

            actor.norm_mean[at : at + count * stride].copy_(self.tied_mean.to(torch.float32).repeat(count))
            actor.norm_std[at : at + count * stride].copy_(
                self.tied_var.sqrt().clamp(min=self.floor).to(torch.float32).repeat(count))

    def state_dict(self) -> dict:
        return {"mean": self.mean, "var": self.var, "count": self.count,
                "tied_mean": self.tied_mean, "tied_var": self.tied_var, "tied_count": self.tied_count}

    def load_state_dict(self, state: dict) -> None:
        # The statistics live on the CPU, where the rows they are updated from arrive. A checkpoint loaded straight onto
        # the GPU would otherwise put them there, and the first update after resuming would mix the two.
        self.mean = state["mean"].cpu()
        self.var = state["var"].cpu()
        self.count = state["count"]

        # Absent from every state written before the slots could be tied, which is every plain run's: it keeps its untied
        # statistics, and a run converted to attention is given these by the converter.
        if "tied_mean" in state:
            self.tied_mean = state["tied_mean"].cpu()
            self.tied_var = state["tied_var"].cpu()
            self.tied_count = state["tied_count"]


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

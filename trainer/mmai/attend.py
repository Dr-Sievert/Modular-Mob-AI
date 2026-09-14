"""Turning a run's plain network into an attended one without losing what it learned.

    python train.py attend --from runs\\blast7 --into runs\\blast8 --heads 3

A network that reads its ten enemy slots where they sit cannot be trained out of doing so: the columns for slots 2..9
never see a real body in a one-on-one fight, so they stay near their initialisation while their statistics sit on the
normaliser's floor, and the first idle bystander to stand in slot 5 shakes the first layer harder than the opponent does
(see model.SlotAttention for the measurements). Attention fixes that by construction -- but thirty two thousand
iterations of fighting are in those weights, and starting again to get it would be throwing away weeks.

So the weights are carried over instead. Where the old network's slot k held what head k now attends to, the conversion is
**exact**: the same columns, rescaled to the statistics the attended network normalises every slot with, and a first layer
bias that folds in what the old one always received from its empty slots and takes out what the new heads receive when
they read the empty token. Validated the other way round first, in PyTorch against blast7's own weights: a K=1 conversion
reproduced it to 6e-5 in the logits on real one-on-one rows, and stayed within 6e-5 of "blast7 alone" with nine idle
bodies written into slots 1..9 -- which is the whole point of the change. trainer/tests/test_attention.py holds that
equivalence on synthetic weights, and is what this file is proved by.

Nothing in the source run is touched; the destination is written whole.
"""

from __future__ import annotations

import shutil
from dataclasses import fields
from pathlib import Path

import torch
from torch import Tensor

from . import log
from .model import PRIVILEGED, Actor, Critic, RunningNormalizer
from .ppo import Config, Trainer
from .schema import Schema
from .weights import export as export_weights

logger = log.get("attend")

# Where the fields the initial scores are written in terms of sit inside one enemy slot. They are the humanoid's, and they
# are its own ObservationSchema's; another body's slots say something else in the same offsets, so its ordering has to be
# written down here before its run can be converted. See docs/species.md.
ORDERINGS = {
    "humanoid": {"present": 0, "distance": 4, "kind": 12, "targets_me": 30, "stride": 31},
}

# What every head starts out ranking a slot by, in raw field units: engaged first, then nearest, then bodies before shots.
# A body that has come for the agent scores 40 and a distance of 32 blocks costs 16, so no bystander outranks an opponent
# however close it stands; the kind is the tie-breaker (projectile -0.25, agent 0.25, player 0.5, monster 0.75, other 1.0),
# and its 8 cannot reach across the engagement term either.
#
# **Twice the ordering that was measured, because the ordering is only half of a softmax.** At 20 / -8 / 4, with the token
# between them, nine idle bodies written into blast7's converted slots still leaked about a thousandth of themselves into
# every head and moved its logits by 0.58 -- sixty times better than the plain network's 37 on the same rows, and still not
# the invariance this change exists for. Doubled, the same rows move by 1e-4. Not sharper than that: the scores are learned
# from here, and a softmax saturated everywhere has no gradient to learn them with.
ENGAGED_SCORE = 40.0
DISTANCE_SCORE = -16.0
KIND_SCORE = 8.0

# What the empty token scores against those. Head 0 attends to anything that is there at all and reads the token only when
# nothing is: -60 is below the lowest score a real slot can reach (a shot at full view distance, -18). Every head after it
# reads the token unless something ENGAGED is left, which is the ordering that matters and not the number: an engaged body
# scores at least 40 - 16 + 2 = 26 and an idle one at most 8, so anything between those two does it. A shot falls under it
# as well, which is accepted: a shot's slot was rarely the one being read.
EMPTY_FIRST = -60.0
EMPTY_REST = 16.0


def ordering(schema: Schema) -> dict[str, int]:
    """The slot ordering for a body, or a refusal naming it: a score written against the wrong offsets would rank slots by
    whatever happens to live at 30, and the conversion would look like it worked."""

    found = ORDERINGS.get(schema.species)

    if found is None:
        raise SystemExit(
            f"no slot ordering is written down for a {schema.species}, and the humanoid's offsets mean something else in "
            f"its slots. Add one to mmai/attend.py from that body's own schema; see docs/species.md"
        )

    enemies = schema.require("enemies")

    if enemies.facts["stride"] != found["stride"]:
        raise SystemExit(
            f"the {schema.species}'s enemy slots are {enemies.facts['stride']} wide and the ordering written down here is "
            f"for {found['stride']}: the layout has moved, and the ordering has to move with it"
        )

    return found


def convert(source: Path, into: Path, heads: int) -> Path:
    """Reads a plain run's state and writes an attended one beside it. Returns the run folder written."""

    source, into = Path(source), Path(into)
    state_file = source if source.is_file() else source / "state.pt"
    schema_file = (source.parent if source.is_file() else source) / "schema.json"

    if not state_file.is_file():
        raise SystemExit(f"no training state at {state_file}")

    if not schema_file.is_file():
        raise SystemExit(f"no layout at {schema_file}; a converted run needs the one its source was trained against")

    schema = Schema.load(schema_file)
    fields_of = ordering(schema)
    state = torch.load(state_file, map_location="cpu", weights_only=False)

    if state["schema_id"] != schema.schema_id:
        raise SystemExit(f"{state_file} was trained against schema {state['schema_id']:08x} and {schema_file} is "
                         f"{schema.schema_id:08x}")

    saved = dict(state.get("config") or {})

    if saved.get("slot_heads") or saved.get("slot_enc"):
        raise SystemExit(f"{state_file} does not hold a plain network, and only a plain one is converted from")

    known = {field.name for field in fields(Config)}
    config = Config(**{name: value for name, value in saved.items() if name in known})

    # One pass on the CPU over a network of a few hundred thousand parameters. Which device the run itself trains on comes
    # from its own flags, as it always has.
    config.device = "cpu"
    config.slot_heads = heads

    if heads > schema.require("enemies").facts["slots"]:
        raise SystemExit(f"{heads} heads over {schema.require('enemies').facts['slots']} slots: a head past the last slot "
                         f"could only ever read the empty token")

    # The network as it is, built to the source's own widths and loaded, so that a state that does not fit says so here
    # rather than halfway through the surgery.
    plain = Actor.for_schema(schema, config.h1, config.hidden, config.h3, config.obs_clip)
    plain.load_state_dict(state["actor"])

    critic_gru = int(state.get("critic_gru_width", 0))
    critic_privileged = int(state.get("critic_privileged", len(PRIVILEGED) if critic_gru else 0))
    plain_critic = Critic(schema.obs_dim, config.hidden, config.critic_width, critic_gru, critic_privileged)
    plain_critic.load_state_dict(state["critic"])

    trainer = Trainer(config, schema)

    # The critic keeps its own shape, exactly as a resume does: it is learned rather than configured, and a run whose
    # critic was built when there were fewer privileged columns goes on reading the ones it learned. See Trainer.load.
    if (critic_gru, critic_privileged) != (trainer.critic.gru_width, trainer.critic.privileged):
        trainer.critic = trainer._new_critic(critic_gru, critic_privileged)
        trainer.optimizer = torch.optim.Adam(
            list(trainer.actor.parameters()) + list(trainer.critic.parameters()), lr=config.learning_rate, eps=1e-5)

    topology = trainer.actor.topology
    at, slots, stride = topology.slot_at, topology.slots, topology.slot_stride

    # ------------------------------------------------------------------------------------------------------------
    # The statistics. The tied ones are slot 0's, because slot 0 is the slot every plain network actually learned from:
    # in a one-on-one fight the opponent is always in it, so its mean and spread are the statistics of a body, which is
    # exactly what an attended network wants. Copying the count with them is what makes the run's own rows go on counting.
    # ------------------------------------------------------------------------------------------------------------

    normalizer = RunningNormalizer(schema.obs_dim, slots=(at, slots, stride))
    normalizer.load_state_dict(state["normalizer"])

    normalizer.tied_mean = normalizer.mean[at : at + stride].clone()
    normalizer.tied_var = normalizer.var[at : at + stride].clone()
    normalizer.tied_count = normalizer.count

    # As the network itself sees them -- the floored, float32 numbers that are in the weight file -- rather than as the
    # running statistics they came from, because those are what the old first layer was reading behind.
    mean_old = [plain.norm_mean[at + j * stride : at + (j + 1) * stride].double() for j in range(slots)]
    std_old = [plain.norm_std[at + j * stride : at + (j + 1) * stride].double() for j in range(slots)]
    mean_new, std_new = mean_old[0], std_old[0]

    clip = float(config.obs_clip)
    empty_old = [((0.0 - mean_old[j]) / std_old[j]).clamp(-clip, clip) for j in range(slots)]
    empty_new = ((0.0 - mean_new) / std_new).clamp(-clip, clip)

    def fold(weight: Tensor, bias: Tensor) -> tuple[Tensor, Tensor]:
        """One layer's observation columns, with the slots taken out and one block per head put in.

        Head k keeps the old slot k's columns, rescaled per column by ``stdNew / stdOld_k`` so that the same body reaching
        the layer through the new tied statistics reaches it as the same numbers. Head 0 is exact and untouched: the new
        statistics *are* slot 0's.

        The bias absorbs the difference. The old layer received something from every empty slot on every row -- an absent
        slot is all zeros, which normalises to ``(0 - mean) / std`` and is not zero -- and that is folded in; what the new
        heads receive when they read the empty token is taken back out. Those two together also cancel the mean shift of
        the rescaling, so a body that used to sit in slot k reaches the layer as what it always was: see the two-engaged
        case in test_attention.py, which is what proves this rather than the algebra.
        """

        weight = weight.double()
        columns = [weight[:, at + k * stride : at + (k + 1) * stride] * (std_new / std_old[k]) for k in range(heads)]
        folded = bias.double().clone()

        for j in range(1, slots):
            folded += weight[:, at + j * stride : at + (j + 1) * stride] @ empty_old[j]

        for k in range(1, heads):
            folded -= columns[k] @ empty_new

        rest = [weight[:, :at]] + columns + [weight[:, at + slots * stride :]]

        return torch.cat(rest, dim=1).float(), folded.float()

    # ------------------------------------------------------------------------------------------------------------
    # The actor
    # ------------------------------------------------------------------------------------------------------------

    actor_state = dict(plain.state_dict())
    actor_state["fc1.weight"], actor_state["fc1.bias"] = fold(plain.fc1.weight.data, plain.fc1.bias.data)
    actor_state.update(scores(fields_of, heads, mean_new, std_new, "attention."))

    trainer.actor.load_state_dict(actor_state)

    # After the parameters, because it is the statistics the export carries and it must be the tied ones that go out.
    normalizer.into(trainer.actor)

    # ------------------------------------------------------------------------------------------------------------
    # The critic. Its encoder reads the observation, the actor's memory and the privileged columns, in that order, so only
    # the first obsDim columns are the ones that move; its GRU, middle and value layers are untouched, as are the aux heads.
    # ------------------------------------------------------------------------------------------------------------

    critic_state = dict(plain_critic.state_dict())
    first = "encoder" if plain_critic.gru is not None else "net.0"
    weight, bias = critic_state[f"{first}.weight"], critic_state[f"{first}.bias"]

    encoded, folded = fold(weight[:, : schema.obs_dim], bias)
    critic_state[f"{first}.weight"] = torch.cat([encoded, weight[:, schema.obs_dim :]], dim=1)
    critic_state[f"{first}.bias"] = folded
    critic_state.update(scores(fields_of, heads, mean_new, std_new, "attention."))

    trainer.critic.load_state_dict(critic_state)

    # ------------------------------------------------------------------------------------------------------------
    # Everything else the run is carrying
    # ------------------------------------------------------------------------------------------------------------

    trainer.normalizer = normalizer
    trainer.reward_scaler.load_state_dict(state["reward_scaler"])
    trainer.iteration = int(state["iteration"])
    trainer.total_steps = int(state["total_steps"])
    # Not the teacher's clock, though. A converted run is a new run: its pull, if it is given one, falls from the first
    # iteration it itself pulls, not from whenever the run it was carried over from first did. Carrying the clock whole is
    # how blast8 came to be started with --teacher-weight 0.3 and a teacher_from of 0, inherited through two runs from the
    # imitation copy the lineage began with, and to pull at exactly nothing for 1,654 iterations; see ppo.teacher_pull.
    trainer.teacher_from = None
    trainer.teacher_released = None
    trainer.critic_trained = bool(state.get("critic_trained", state["iteration"] > 0))
    trainer.rate = config.learning_rate * float(state.get("rate_ratio", 1.0))

    if trainer.aux is not None and state.get("aux") and state.get("aux_optimizer"):
        trainer.aux.load_state_dict(state["aux"])
        trainer.aux_optimizer.load_state_dict(state["aux_optimizer"])

    carried = moments(state.get("optimizer"), plain, plain_critic, trainer)

    if carried:
        trainer.optimizer.load_state_dict(carried)

    # ------------------------------------------------------------------------------------------------------------
    # Written out
    # ------------------------------------------------------------------------------------------------------------

    into.mkdir(parents=True, exist_ok=True)
    (into / "weights").mkdir(parents=True, exist_ok=True)
    (into / "checkpoints").mkdir(parents=True, exist_ok=True)

    shutil.copyfile(schema_file, into / "schema.json")
    trainer.save(into / "state.pt")

    weights = into / "weights" / f"{trainer.iteration:06d}.mbw"
    export_weights(weights, trainer.actor, schema.schema_id, trainer.iteration)

    logger.info("%s -> %s", plain.topology.describe(), topology.describe())
    logger.info("converted iteration %d of %s into %s, with %d of Adam's %d moments carried over",
                trainer.iteration, state_file, into, len(carried.get("state", {})) if carried else 0,
                len(trainer.optimizer.param_groups[0]["params"]))
    logger.info("wrote %s, %s and %s", (into / "state.pt").name, (into / "schema.json").name, weights.name)

    return into


def scores(fields_of: dict[str, int], heads: int, mean: Tensor, spread: Tensor, prefix: str) -> dict[str, Tensor]:
    """The starting scores, identical for every head, in the z-space the network reads slots in.

    A score is written in raw field units -- 20 for being engaged, -8 for a full view distance away -- and the slots
    arrive normalised, so each weight is multiplied by that field's spread and the means are gathered into the bias:
    ``w·(z*std + mean) = (w*std)·z + w·mean``. That way the ordering means what it says whatever the statistics are, and it
    goes on meaning it as they move, which a hand-set weight on a normalised input would not.
    """

    raw = torch.zeros(mean.shape[0], dtype=torch.float64)
    raw[fields_of["targets_me"]] = ENGAGED_SCORE
    raw[fields_of["distance"]] = DISTANCE_SCORE
    raw[fields_of["kind"]] = KIND_SCORE

    empty = torch.full((heads,), EMPTY_REST, dtype=torch.float64)
    empty[0] = EMPTY_FIRST

    return {
        f"{prefix}score_w": (raw * spread).float().repeat(heads, 1),
        f"{prefix}score_b": torch.full((heads,), float(raw @ mean)).float(),
        f"{prefix}score_empty": empty.float(),
    }


def moments(saved: dict | None, plain: Actor, plain_critic: Critic, trainer: Trainer) -> dict | None:
    """Adam's moments, carried over for every parameter that is still the same parameter.

    The optimizer's state is indexed by position over the actor's parameters and then the critic's, and attention has put
    new parameters in the middle of both lists, so the two are matched up by name instead. A layer whose columns moved gets
    nothing and starts again: a moment is an average of gradients of a weight that no longer means what it did, and Adam
    fills one in from the first step anyway. Everything behind the first layer -- both GRUs, fc2, the output, the value
    head -- is exactly the parameter it was, and keeping its momentum is the difference between carrying a run on and
    restarting it with the weights it had.
    """

    if not saved or not saved.get("param_groups"):
        return None

    was = [f"actor.{name}" for name, _ in plain.named_parameters()]
    was += [f"critic.{name}" for name, _ in plain_critic.named_parameters()]

    now = [f"actor.{name}" for name, _ in trainer.actor.named_parameters()]
    now += [f"critic.{name}" for name, _ in trainer.critic.named_parameters()]

    # The layers the conversion rewrote, by the names they have in those lists.
    moved = {"actor.fc1.weight", "actor.fc1.bias", "critic.encoder.weight", "critic.encoder.bias",
             "critic.net.0.weight", "critic.net.0.bias"}

    where = {name: index for index, name in enumerate(was)}
    kept = {}

    for index, name in enumerate(now):
        found = where.get(name)

        if found is None or name in moved:
            continue

        entry = saved["state"].get(found)

        if entry is not None:
            kept[index] = entry

    return {"state": kept, "param_groups": [dict(saved["param_groups"][0], params=list(range(len(now))))]}

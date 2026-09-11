"""Builds the fixture the game checks itself against.

Float arithmetic does not associate, so the two sides never agree exactly; they have to agree to about a millionth. What
this catches is the class of bug that cannot be caught any other way: a transposed matrix, the GRU's gates in the wrong
order, the reset gate applied to the wrong half of the new gate, a normaliser applied on one side only. All of those land
around a tenth, which is unmissable, and all of them otherwise look exactly like "reinforcement learning is hard".

The weights are deliberately awkward: a normaliser that shifts and scales, spreads that are not one, an output layer big
enough that the logits mean something, and a hotbar with slots missing so the mask is exercised. A fixture of zeroes
would pass against almost any bug.
"""

from __future__ import annotations

import struct
from pathlib import Path

import numpy as np
import torch

from .model import Actor, PolicyHeads
from .schema import CATEGORICAL, Schema
from .weights import export

MAGIC = b"MBP1"


def generate(directory: str | Path, schema: Schema, h1: int, hidden: int, h3: int, obs_clip: float = 10.0,
             count: int = 67, seed: int = 12345) -> Path:
    """Writes ``weights.mbw`` and ``fixture.bin`` into the directory, and returns it."""

    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)

    torch.manual_seed(seed)
    generator = np.random.default_rng(seed)

    actor = Actor.for_schema(schema, h1, hidden, h3, obs_clip)
    heads = PolicyHeads(schema.heads)

    with torch.no_grad():
        # Not the training initialisation: an output layer that starts near zero would hide a wrong output matrix.
        torch.nn.init.orthogonal_(actor.out.weight, gain=1.0)
        actor.out.bias.copy_(torch.randn(actor.out.bias.shape) * 0.1)

        actor.norm_mean.copy_(torch.randn(schema.obs_dim) * 0.2)
        actor.norm_std.copy_(torch.rand(schema.obs_dim) * 0.8 + 0.6)
        actor.log_std.copy_(torch.randn(schema.std_dim) * 0.3 - 0.7)

    actor.eval()

    observations = generator.standard_normal((count, schema.obs_dim)).astype(np.float32) * 0.5

    # A few rows far outside the usual range, so the clamp is exercised on both sides.
    observations[0] *= 40.0
    observations[1] = -observations[1] * 40.0

    # The hotbar decides which slots may be chosen. Some rows keep a few, one row keeps none at all.
    hotbar = np.zeros((count, schema.hotbar.size), dtype=np.float32)
    keep = generator.random((count, schema.hotbar.size)) < 0.5
    hotbar[keep] = generator.integers(1, 8, size=int(keep.sum())).astype(np.float32) / 8.0
    hotbar[0] = 0.0
    observations[:, schema.hotbar.offset : schema.hotbar.offset + schema.hotbar.size] = hotbar

    states = (generator.standard_normal((count, hidden)).astype(np.float32) * 0.5)

    obs = torch.from_numpy(observations)
    h0 = torch.from_numpy(states)

    with torch.no_grad():
        logits, memory = actor(obs.unsqueeze(1), h0)
        logits = logits.squeeze(1)
        memory = memory.squeeze(1)

        distributions = heads.distributions(logits, actor.log_std, obs)
        actions = torch.zeros(count, schema.act_dim)

        for head, distribution in distributions:
            sample = distribution.sample()

            if head.kind == CATEGORICAL:
                actions[:, head.action] = sample.to(actions.dtype)

            else:
                actions[:, head.action : head.action + head.size] = sample.to(actions.dtype)

        log_probs = heads.log_prob(distributions, actions)

    export(directory / "weights.mbw", actor, schema.schema_id, 0)

    with open(directory / "fixture.bin", "wb") as file:
        file.write(MAGIC)
        file.write(struct.pack("<5I", count, schema.obs_dim, hidden, schema.logit_dim, schema.act_dim))

        for array in (observations, states, actions.numpy(), logits.numpy(), memory.numpy(), log_probs.numpy()):
            file.write(np.ascontiguousarray(array, dtype="<f4").tobytes())

    return directory

"""One body's observation and action layout, as the game describes it.

Nothing here is written down by hand. The game writes its own layout out before a run starts and this parses it, which is
the only arrangement where the two halves cannot drift apart. A schema copied into both would fail silently when one side
changed: no crash, no error, just a network reading health out of the slot that used to hold it and playing badly for
reasons nobody can find.

The id is a checksum of those very bytes, species name included. It is stamped into every weight file and every rollout
shard, and the game refuses weights that carry any other, so a rearranged block, or a network handed to the wrong body,
stops a run instead of quietly spoiling it.

Which blocks a body has is the thing that varies: one with no hands has no hotbar to see and no slot to choose. So the
blocks arrive as a list, in offset order, and anything that wants one asks for it by name and copes with it being absent.
"""

from __future__ import annotations

import json
import zlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np

CONTINUOUS = "continuous"
BINARY = "binary"
CATEGORICAL = "categorical"


@dataclass(frozen=True)
class Block:
    """One named run of the observation, plus whatever else the game said about it.

    ``facts`` carries the extras a block of that kind has: an enemy block's ``slots`` and ``stride``, a terrain block's
    ``x``, ``y`` and ``z``. They are read rather than assumed, because which blocks exist is a property of the body.
    """

    name: str
    offset: int
    size: int
    facts: dict[str, int]

    def slice(self, observations: np.ndarray) -> np.ndarray:
        """The columns of this block, for a ``(..., obs_dim)`` batch."""
        return observations[..., self.offset : self.offset + self.size]

    def shaped(self, observations: np.ndarray, *dimensions: str) -> np.ndarray:
        """Reshaped by the named facts, outermost first: ``block.shaped(obs, "z", "y", "x")`` for a terrain grid.

        The terrain grid is written x fastest, then y, then z, and an enemy block slot by slot, so the names go in the
        order numpy wants them.
        """
        shape = tuple(self.facts[name] for name in dimensions)
        return self.slice(observations).reshape(*observations.shape[:-1], *shape)


@dataclass(frozen=True)
class Head:
    """One block of network outputs and where its values land in the action vector.

    ``mask`` is an observation offset, one value per choice, a choice being allowed only while its value is above zero;
    -1 for no mask. That is what stops a slot the agent is not carrying anything in from being chosen.
    """

    name: str
    kind: str
    size: int
    action: int
    logit: int
    std: int
    mask: int


@dataclass(frozen=True)
class Schema:
    species: str
    obs_dim: int
    act_dim: int
    logit_dim: int
    action_names: tuple[str, ...]
    heads: tuple[Head, ...]

    blocks: tuple[Block, ...]

    schema_id: int

    def block(self, name: str) -> Block | None:
        """The block of that name, or None for a body that has no such thing."""
        for block in self.blocks:
            if block.name == name:
                return block

        return None

    def require(self, name: str) -> Block:
        """The block of that name, or a clear failure: for something that only makes sense for a body that has one."""
        found = self.block(name)

        if found is None:
            have = ", ".join(block.name for block in self.blocks)
            raise ValueError(f"a {self.species} has no {name} block; it has {have}")

        return found

    def where(self, offset: int) -> str:
        """Which field of the observation a row offset is, in words, for a message about one number having gone wrong.

        Named by block and by how far into it, and for a block of repeated things -- ten enemy slots, a terrain grid -- by
        which one of them as well, because "enemies 37" is not a field anybody can look up and "enemies slot 1, offset 6 of
        31" is: it names an entry of the body's own schema, which is where the number is written.
        """

        for block in self.blocks:
            if not block.offset <= offset < block.offset + block.size:
                continue

            within = offset - block.offset
            stride = block.facts.get("stride")

            if stride:
                return f"{block.name} slot {within // stride}, offset {within % stride} of {stride} (row offset {offset})"

            return f"{block.name} offset {within} of {block.size} (row offset {offset})"

        return f"row offset {offset}, which is past every block"

    @property
    def categorical_heads(self) -> tuple[Head, ...]:
        """The heads that choose one of several, which are the ones a mask applies to."""
        return tuple(head for head in self.heads if head.kind == CATEGORICAL)

    @staticmethod
    def load(path: str | Path) -> "Schema":
        """Reads the file the game wrote. The id comes from the raw bytes, never from anything re-serialised here."""
        data = Path(path).read_bytes()
        return Schema.parse(data)

    @staticmethod
    def parse(data: bytes | str) -> "Schema":
        raw_bytes = data.encode("utf-8") if isinstance(data, str) else data
        raw = json.loads(raw_bytes.decode("utf-8"))

        named = {"name", "offset", "size"}

        schema = Schema(
            species=raw["species"],
            obs_dim=raw["obsDim"],
            act_dim=raw["actDim"],
            logit_dim=raw["logits"],
            action_names=tuple(raw["actions"]),
            heads=tuple(
                Head(h["name"], h["kind"], h["size"], h["action"], h["logit"], h["std"], h["mask"])
                for h in raw["heads"]
            ),
            blocks=tuple(
                Block(
                    b["name"],
                    b["offset"],
                    b["size"],
                    {key: value for key, value in b.items() if key not in named},
                )
                for b in raw["blocks"]
            ),
            schema_id=zlib.crc32(raw_bytes) & 0xFFFFFFFF,
        )

        schema.validate()
        return schema

    @property
    def std_dim(self) -> int:
        """How many continuous outputs need a spread of their own."""
        return sum(head.size for head in self.heads if head.kind == CONTINUOUS)

    def validate(self) -> None:
        """Catches a layout that does not add up, which is a bug in the game rather than in the file."""

        covered = 0

        for block in self.blocks:
            if block.offset != covered:
                raise ValueError(f"block {block.name} starts at {block.offset} but the blocks before it end at {covered}")

            covered += block.size

            # Whatever facts a block carries have to multiply out to its size, whichever facts those are. That is what
            # catches a body whose grid and whose block disagree, without this side knowing what kinds of block exist.
            if block.facts:
                product = 1

                for value in block.facts.values():
                    product *= value

                if product != block.size:
                    raise ValueError(f"block {block.name} is {block.size} wide but its {block.facts} multiply to {product}")

        if covered != self.obs_dim:
            raise ValueError(f"blocks cover {covered} values but the observation is {self.obs_dim} wide")

        if len(self.action_names) != self.act_dim:
            raise ValueError(f"{len(self.action_names)} action names for {self.act_dim} actions")

        logits = sum(head.size for head in self.heads)

        if logits != self.logit_dim:
            raise ValueError(f"the heads read {logits} outputs but the network is said to have {self.logit_dim}")

        actions = sum(1 if head.kind == CATEGORICAL else head.size for head in self.heads)

        if actions != self.act_dim:
            raise ValueError(f"the heads fill {actions} action values but the action vector is {self.act_dim} wide")

        for head in self.heads:
            if head.kind not in (CONTINUOUS, BINARY, CATEGORICAL):
                raise ValueError(f"head {head.name} is of unknown kind {head.kind}")

    def describe(self) -> str:
        heads = ", ".join(f"{head.name} {head.size} {head.kind}" for head in self.heads)
        blocks = ", ".join(
            block.name + " " + ("x".join(str(value) for value in block.facts.values()) if block.facts else str(block.size))
            for block in self.blocks
        )

        return (
            f"schema {self.schema_id:08x}, a {self.species}: observation {self.obs_dim} wide ({blocks}); "
            f"{self.logit_dim} outputs as {heads}; {self.act_dim} actions"
        )

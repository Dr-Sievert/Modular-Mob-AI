"""The observation and action layout, as the game describes it.

Nothing here is written down by hand. The game writes its own layout out before a run starts and this parses it, which is
the only arrangement where the two halves cannot drift apart. A schema copied into both would fail silently when one side
changed: no crash, no error, just a network reading health out of the slot that used to hold it and playing badly for
reasons nobody can find.

The id is a checksum of those very bytes. It is stamped into every weight file and every rollout shard, and the game
refuses weights that carry any other, so a rearranged block stops a run instead of quietly spoiling it.
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
    offset: int
    size: int

    def slice(self, observations: np.ndarray) -> np.ndarray:
        """The columns of this block, for a ``(..., obs_dim)`` batch."""
        return observations[..., self.offset : self.offset + self.size]


@dataclass(frozen=True)
class EnemyBlock(Block):
    slots: int
    stride: int

    def per_slot(self, observations: np.ndarray) -> np.ndarray:
        """Reshaped to ``(..., slots, stride)``, which is the shape anything per opponent wants."""
        return self.slice(observations).reshape(*observations.shape[:-1], self.slots, self.stride)


@dataclass(frozen=True)
class TerrainBlock(Block):
    x: int
    y: int
    z: int

    def grid(self, observations: np.ndarray) -> np.ndarray:
        """Reshaped to ``(..., z, y, x)``, matching how the game writes it: x fastest, then y, then z."""
        return self.slice(observations).reshape(*observations.shape[:-1], self.z, self.y, self.x)


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
    obs_dim: int
    act_dim: int
    logit_dim: int
    action_names: tuple[str, ...]
    heads: tuple[Head, ...]

    self_block: Block
    hotbar: Block
    echo: Block
    enemies: EnemyBlock
    terrain: TerrainBlock

    schema_id: int

    @staticmethod
    def load(path: str | Path) -> "Schema":
        """Reads the file the game wrote. The id comes from the raw bytes, never from anything re-serialised here."""
        data = Path(path).read_bytes()
        return Schema.parse(data)

    @staticmethod
    def parse(data: bytes | str) -> "Schema":
        raw_bytes = data.encode("utf-8") if isinstance(data, str) else data
        raw = json.loads(raw_bytes.decode("utf-8"))
        blocks = raw["blocks"]

        enemies = blocks["enemies"]
        terrain = blocks["terrain"]

        schema = Schema(
            obs_dim=raw["obsDim"],
            act_dim=raw["actDim"],
            logit_dim=raw["logits"],
            action_names=tuple(raw["actions"]),
            heads=tuple(
                Head(h["name"], h["kind"], h["size"], h["action"], h["logit"], h["std"], h["mask"])
                for h in raw["heads"]
            ),
            self_block=Block(blocks["self"]["offset"], blocks["self"]["size"]),
            hotbar=Block(blocks["hotbar"]["offset"], blocks["hotbar"]["size"]),
            echo=Block(blocks["echo"]["offset"], blocks["echo"]["size"]),
            enemies=EnemyBlock(enemies["offset"], enemies["size"], enemies["slots"], enemies["stride"]),
            terrain=TerrainBlock(terrain["offset"], terrain["size"], terrain["x"], terrain["y"], terrain["z"]),
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

        covered = sum(block.size for block in (self.self_block, self.hotbar, self.echo, self.enemies, self.terrain))

        if covered != self.obs_dim:
            raise ValueError(f"blocks cover {covered} values but the observation is {self.obs_dim} wide")

        if len(self.action_names) != self.act_dim:
            raise ValueError(f"{len(self.action_names)} action names for {self.act_dim} actions")

        if self.enemies.slots * self.enemies.stride != self.enemies.size:
            raise ValueError("the enemy block is not its slot count times its stride")

        if self.terrain.x * self.terrain.y * self.terrain.z != self.terrain.size:
            raise ValueError("the terrain block is not its own dimensions")

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

        return (
            f"schema {self.schema_id:08x}: observation {self.obs_dim} wide "
            f"(self {self.self_block.size}, hotbar {self.hotbar.size}, echo {self.echo.size}, "
            f"enemies {self.enemies.slots}x{self.enemies.stride}, "
            f"terrain {self.terrain.x}x{self.terrain.y}x{self.terrain.z}); "
            f"{self.logit_dim} outputs as {heads}; {self.act_dim} actions"
        )

"""The observation layout, as the game describes it.

Nothing here is written down by hand. The game sends its own layout when it connects and this parses it, which is the
only arrangement where the two halves cannot drift apart. A schema copied into both would fail silently when one side
changed: no crash, no error, just a network reading health out of the slot that used to hold it and playing badly for
reasons nobody can find.

If a block moves, this picks it up on the next connection.
"""

from __future__ import annotations

import json
from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class Block:
    offset: int
    size: int

    def slice(self, observations: np.ndarray) -> np.ndarray:
        """The columns of this block, for a ``(count, obs_dim)`` batch."""
        return observations[..., self.offset : self.offset + self.size]


@dataclass(frozen=True)
class EnemyBlock(Block):
    slots: int
    stride: int

    def per_slot(self, observations: np.ndarray) -> np.ndarray:
        """Reshaped to ``(count, slots, stride)``, which is the shape anything per opponent wants."""
        return self.slice(observations).reshape(*observations.shape[:-1], self.slots, self.stride)


@dataclass(frozen=True)
class TerrainBlock(Block):
    x: int
    y: int
    z: int

    def grid(self, observations: np.ndarray) -> np.ndarray:
        """Reshaped to ``(count, z, y, x)``, matching how the game writes it: x fastest, then y, then z."""
        return self.slice(observations).reshape(*observations.shape[:-1], self.z, self.y, self.x)


@dataclass(frozen=True)
class Schema:
    obs_dim: int
    act_dim: int
    action_names: tuple[str, ...]

    self_block: Block
    hotbar: Block
    echo: Block
    enemies: EnemyBlock
    terrain: TerrainBlock

    @staticmethod
    def parse(text: str) -> "Schema":
        raw = json.loads(text)
        blocks = raw["blocks"]

        enemies = blocks["enemies"]
        terrain = blocks["terrain"]

        schema = Schema(
            obs_dim=raw["obsDim"],
            act_dim=raw["actDim"],
            action_names=tuple(raw["actions"]),
            self_block=Block(blocks["self"]["offset"], blocks["self"]["size"]),
            hotbar=Block(blocks["hotbar"]["offset"], blocks["hotbar"]["size"]),
            echo=Block(blocks["echo"]["offset"], blocks["echo"]["size"]),
            enemies=EnemyBlock(enemies["offset"], enemies["size"], enemies["slots"], enemies["stride"]),
            terrain=TerrainBlock(terrain["offset"], terrain["size"], terrain["x"], terrain["y"], terrain["z"]),
        )

        schema.validate()
        return schema

    def validate(self) -> None:
        """Catches a layout that does not add up, which is a bug in the game rather than in the message."""

        covered = sum(block.size for block in (self.self_block, self.hotbar, self.echo, self.enemies, self.terrain))

        if covered != self.obs_dim:
            raise ValueError(f"blocks cover {covered} values but the observation is {self.obs_dim} wide")

        if len(self.action_names) != self.act_dim:
            raise ValueError(f"{len(self.action_names)} action names for {self.act_dim} actions")

        if self.enemies.slots * self.enemies.stride != self.enemies.size:
            raise ValueError("the enemy block is not its slot count times its stride")

        if self.terrain.x * self.terrain.y * self.terrain.z != self.terrain.size:
            raise ValueError("the terrain block is not its own dimensions")

    def matches(self, other: "Schema") -> bool:
        """Whether two workers are describing the same game. They must, or their steps cannot share a network."""
        return (
            self.obs_dim == other.obs_dim
            and self.act_dim == other.act_dim
            and self.action_names == other.action_names
        )

    def describe(self) -> str:
        return (
            f"observation {self.obs_dim} wide: "
            f"self {self.self_block.size}, hotbar {self.hotbar.size}, echo {self.echo.size}, "
            f"enemies {self.enemies.slots}x{self.enemies.stride}, "
            f"terrain {self.terrain.x}x{self.terrain.y}x{self.terrain.z}; "
            f"{self.act_dim} actions"
        )

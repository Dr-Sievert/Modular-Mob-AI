"""The weight files the game reads, and the one flat layout both sides agree on.

One float array in a fixed segment order, written straight out and read straight in: no object graph, no transposes, no
name matching. ``nn.Linear.weight`` is already ``[out, in]`` row major and ``nn.GRU``'s input weights are already
``[3H, in]`` in the reset, update, new order the game applies them in, which is why choosing this layout costs nothing
here and saves the game a rearrangement per tick.

The observation normaliser travels inside the file. A network trained on normalised inputs and run on raw ones does not
crash, it just plays badly, so the statistics are part of the weights rather than something the game has to be told
separately.
"""

from __future__ import annotations

import os
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch

from . import files

MAGIC = b"MBW1"
VERSION = 1
EXTENSION = ".mbw"

HEADER = struct.Struct("<4s3I6IfII")


@dataclass(frozen=True)
class Topology:
    """Mirrors the game's Topology record, including how its hash is worked out."""

    obs_dim: int
    h1: int
    hidden: int
    h3: int
    out_dim: int
    std_dim: int

    def hash(self) -> int:
        packed = struct.pack("<6I", self.obs_dim, self.h1, self.hidden, self.h3, self.out_dim, self.std_dim)
        return zlib.crc32(packed) & 0xFFFFFFFF

    def size(self) -> int:
        return (
            2 * self.obs_dim
            + self.h1 * self.obs_dim
            + self.h1
            + 3 * self.hidden * self.h1
            + 3 * self.hidden
            + 3 * self.hidden * self.hidden
            + 3 * self.hidden
            + self.h3 * self.hidden
            + self.h3
            + self.out_dim * self.h3
            + self.out_dim
            + self.std_dim
        )

    def macs_per_step(self) -> int:
        return (
            self.obs_dim * self.h1
            + 3 * self.hidden * self.h1
            + 3 * self.hidden * self.hidden
            + self.hidden * self.h3
            + self.h3 * self.out_dim
        )

    def describe(self) -> str:
        return (
            f"{self.obs_dim} -> {self.h1} -> GRU {self.hidden} -> {self.h3} -> {self.out_dim} "
            f"({self.size():,} parameters, {self.macs_per_step():,} multiply adds a step)"
        )


@dataclass(frozen=True)
class WeightHeader:
    schema_id: int
    topology: Topology
    obs_clip: float
    iteration: int


def segments(actor) -> list[np.ndarray]:
    """The parameters in the order the game reads them. One list, one concatenation, one file."""

    parts = [
        actor.norm_mean,
        actor.norm_std,
        actor.fc1.weight,
        actor.fc1.bias,
        actor.gru.weight_ih_l0,
        actor.gru.bias_ih_l0,
        actor.gru.weight_hh_l0,
        actor.gru.bias_hh_l0,
        actor.fc2.weight,
        actor.fc2.bias,
        actor.out.weight,
        actor.out.bias,
        actor.log_std,
    ]

    return [part.detach().to(torch.float32).cpu().reshape(-1).numpy() for part in parts]


def export(path: str | Path, actor, schema_id: int, iteration: int) -> Path:
    """Writes one iteration's weights, under a temporary name and then renamed, so a reader never sees half a file."""

    path = Path(path)
    topology = actor.topology
    flat = np.concatenate(segments(actor)).astype("<f4")

    if flat.size != topology.size():
        raise ValueError(f"exported {flat.size} parameters but {topology.describe()} needs {topology.size()}")

    if not np.isfinite(flat).all():
        raise ValueError("refusing to export weights that are not finite")

    header = HEADER.pack(
        MAGIC,
        VERSION,
        schema_id & 0xFFFFFFFF,
        topology.hash(),
        topology.obs_dim,
        topology.h1,
        topology.hidden,
        topology.h3,
        topology.out_dim,
        topology.std_dim,
        float(actor.obs_clip),
        iteration,
        flat.size,
    )

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")

    with open(temporary, "wb") as file:
        file.write(header)
        file.write(flat.tobytes())
        file.flush()
        os.fsync(file.fileno())

    files.replace(temporary, path)
    return path


def read(path: str | Path) -> tuple[WeightHeader, np.ndarray]:
    """Reads a weight file back, for resuming a run from one."""

    data = Path(path).read_bytes()

    if len(data) < HEADER.size:
        raise ValueError(f"{path} is shorter than a header")

    magic, version, schema_id, topology_hash, obs_dim, h1, hidden, h3, out_dim, std_dim, obs_clip, iteration, count = (
        HEADER.unpack_from(data)
    )

    if magic != MAGIC:
        raise ValueError(f"{path} is not a weight file")

    if version != VERSION:
        raise ValueError(f"{path} is format version {version}, not {VERSION}")

    topology = Topology(obs_dim, h1, hidden, h3, out_dim, std_dim)

    if topology.hash() != topology_hash:
        raise ValueError(f"{path} has a topology hash that does not match its own dimensions")

    if count != topology.size() or len(data) != HEADER.size + 4 * count:
        raise ValueError(f"{path} does not hold the {topology.size()} parameters its header claims")

    flat = np.frombuffer(data, dtype="<f4", count=count, offset=HEADER.size)

    return WeightHeader(schema_id, topology, float(obs_clip), int(iteration)), flat


def load_into(actor, flat: np.ndarray) -> None:
    """Puts a flat parameter array back into a model, the exact inverse of :func:`segments`."""

    topology = actor.topology

    if flat.size != topology.size():
        raise ValueError(f"{flat.size} parameters for a network that needs {topology.size()}")

    offset = 0

    with torch.no_grad():
        for part in (
            actor.norm_mean,
            actor.norm_std,
            actor.fc1.weight,
            actor.fc1.bias,
            actor.gru.weight_ih_l0,
            actor.gru.bias_ih_l0,
            actor.gru.weight_hh_l0,
            actor.gru.bias_hh_l0,
            actor.fc2.weight,
            actor.fc2.bias,
            actor.out.weight,
            actor.out.bias,
            actor.log_std,
        ):
            size = part.numel()
            piece = torch.from_numpy(np.array(flat[offset : offset + size], dtype=np.float32))
            part.copy_(piece.reshape(part.shape))
            offset += size

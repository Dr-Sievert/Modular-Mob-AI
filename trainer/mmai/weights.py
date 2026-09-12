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

# 2 added the four numbers that describe a shared encoder over the enemy slots; see Topology. A version 1 file is still
# read, and is a network without one, because the shapes it can describe are a subset of what 2 can.
VERSION = 2
OLDEST = 1
EXTENSION = ".mbw"

HEADER_V1 = struct.Struct("<4s3I6IfII")
HEADER = struct.Struct("<4s3I10IfII")


@dataclass(frozen=True)
class Topology:
    """Mirrors the game's Topology record, including how its hash is worked out.

    The last four describe an optional **shared encoder over the enemy slots**, and are all zero for a network without
    one, which is every network trained before it existed.

    Why it is worth a change to the format: the enemy block is ten slots of the same shape, and a plain first layer gives
    every slot its own weights, so an opponent in slot three and the same opponent in slot seven are unrelated inputs and
    each has to be learned from scratch. The league showed what that costs — 84% against one skeleton and 9% against two.
    A shared encoder runs the same small matrix over each slot and keeps the largest answer per feature, so a squad is the
    same thing wherever its members happen to sit, and the first layer gets those features instead of 290 raw numbers. It
    is also smaller: at a width of 64 the first layer drops from 768x256 to 542x256, which pays twice over, since the pass
    is bound by arithmetic per agent rather than by weight bandwidth (findings.md).

    Where the slots are is stored here rather than worked out from the schema, so that a weight file says in itself what
    its numbers mean and can never be read against a layout that has moved.
    """

    obs_dim: int
    h1: int
    hidden: int
    h3: int
    out_dim: int
    std_dim: int

    # The enemy block: where it starts, how many slots, how wide each is, and the width of the shared encoder. Zero
    # everywhere means no encoder and the plain first layer over the whole observation.
    slot_at: int = 0
    slots: int = 0
    slot_stride: int = 0
    slot_enc: int = 0

    def pooled(self) -> bool:
        """Whether this network encodes the enemy slots before the first layer."""

        return self.slot_enc > 0 and self.slots > 0 and self.slot_stride > 0

    def fc1_in(self) -> int:
        """What the first layer actually takes: the whole observation, or everything outside the slots plus the pooled
        features that replace them."""

        return self.obs_dim - self.slots * self.slot_stride + self.slot_enc if self.pooled() else self.obs_dim

    def hash(self) -> int:
        packed = struct.pack("<6I", self.obs_dim, self.h1, self.hidden, self.h3, self.out_dim, self.std_dim)

        # A network with no encoder hashes exactly as it did before these four fields existed, so that adding them retires
        # nothing: the identity of a plain network has not changed just because another shape is now describable.
        if self.pooled():
            packed += struct.pack("<4I", self.slot_at, self.slots, self.slot_stride, self.slot_enc)

        return zlib.crc32(packed) & 0xFFFFFFFF

    def size(self) -> int:
        return (
            2 * self.obs_dim
            + (self.slot_enc * self.slot_stride + self.slot_enc if self.pooled() else 0)
            + self.h1 * self.fc1_in()
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
            (self.slots * self.slot_stride * self.slot_enc if self.pooled() else 0)
            + self.fc1_in() * self.h1
            + 3 * self.hidden * self.h1
            + 3 * self.hidden * self.hidden
            + self.hidden * self.h3
            + self.h3 * self.out_dim
        )

    def describe(self) -> str:
        slots = f"{self.slots}x{self.slot_stride} -> {self.slot_enc} pooled, " if self.pooled() else ""

        return (
            f"{self.obs_dim} -> {slots}{self.fc1_in()} -> {self.h1} -> GRU {self.hidden} -> {self.h3} -> {self.out_dim} "
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
    ]

    # The shared encoder comes before the first layer here because that is the order the game applies it in, and the file
    # is read straight through without seeking.
    if actor.topology.pooled():
        parts += [actor.slot_encoder.weight, actor.slot_encoder.bias]

    parts += [
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
        topology.slot_at,
        topology.slots,
        topology.slot_stride,
        topology.slot_enc,
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

    if len(data) < HEADER_V1.size:
        raise ValueError(f"{path} is shorter than a header")

    magic, version = struct.unpack_from("<4sI", data)

    if magic != MAGIC:
        raise ValueError(f"{path} is not a weight file")

    if not OLDEST <= version <= VERSION:
        raise ValueError(f"{path} is format version {version}, and this reads {OLDEST} to {VERSION}")

    # Version 1 has no encoder over the slots, which is exactly a version 2 file with those four numbers at zero, so the
    # two are read by the same code with a shorter header.
    layout = HEADER if version >= 2 else HEADER_V1

    if len(data) < layout.size:
        raise ValueError(f"{path} is shorter than its own header says")

    fields = layout.unpack_from(data)
    schema_id, topology_hash = fields[2], fields[3]
    obs_dim, h1, hidden, h3, out_dim, std_dim = fields[4:10]
    slots = fields[10:14] if version >= 2 else (0, 0, 0, 0)
    obs_clip, iteration, count = fields[-3:]

    topology = Topology(obs_dim, h1, hidden, h3, out_dim, std_dim, *slots)

    if topology.hash() != topology_hash:
        raise ValueError(f"{path} has a topology hash that does not match its own dimensions")

    if count != topology.size() or len(data) != layout.size + 4 * count:
        raise ValueError(f"{path} does not hold the {topology.size()} parameters its header claims")

    flat = np.frombuffer(data, dtype="<f4", count=count, offset=layout.size)

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

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

# 3 renamed the fourth slot number from the width of a max-pooled encoder to the number of attention heads over the enemy
# slots; see Topology. A version 1 file is still read, and so is a version 2 file with all four numbers at zero, because
# both are exactly a plain network -- which is every network ever published. A version 2 file that really was pooled is
# refused by name: no such network was ever kept, and reading one as attended would be reading a matrix as a score.
VERSION = 3
OLDEST = 1
EXTENSION = ".mbw"

HEADER_V1 = struct.Struct("<4s3I6IfII")
HEADER = struct.Struct("<4s3I10IfII")


@dataclass(frozen=True)
class Topology:
    """Mirrors the game's Topology record, including how its hash is worked out.

    The last four describe optional **attention over the enemy slots**, and are all zero for a network without it, which
    is every network trained before it existed.

    Why it is worth a change to the format: the enemy block is ten slots of the same shape, and a plain first layer gives
    every slot its own weights. In every one-on-one fight the opponent sits in slot 0 and slots 1..9 are all zeros, so
    those columns never learn anything and their statistics sit at the normaliser's floor -- and then a bystander arriving
    in slot 5 lands on the first layer harder than the opponent does. Measured on blast7 over 60 real one-on-one segments:
    one idle body written into slot 1 moved the deterministic aim by 22 degrees of yaw a tick and flipped the chosen hotbar
    slot on 32% of ticks; the fc1 pre-activation shift from one idle zombie was 1.0 in slot 0 and 8.3 in slot 8. That is
    the whole of "one bystander costs 16 points and nine cost 45", and it is the representation rather than the curriculum.

    ``slot_heads`` heads each pick one slot out by a learned score and hand the first layer that slot's numbers, with the
    slot a head chose excluded from the ones after it. The first layer then reads K slots' worth of somebody rather than
    ten slots' worth of wherever they happened to sit, so what it sees is invariant to the idle bodies standing about, by
    construction rather than by training. See ``model.SlotAttention`` for the arithmetic, which the game runs too.

    Where the slots are is stored here rather than worked out from the schema, so that a weight file says in itself what
    its numbers mean and can never be read against a layout that has moved.
    """

    obs_dim: int
    h1: int
    hidden: int
    h3: int
    out_dim: int
    std_dim: int

    # The enemy block: where it starts, how many slots, how wide each is, and how many heads read it. Zero everywhere
    # means no attention and the plain first layer over the whole observation.
    slot_at: int = 0
    slots: int = 0
    slot_stride: int = 0
    slot_heads: int = 0

    def attended(self) -> bool:
        """Whether this network picks its enemy slots out by attention before the first layer."""

        return self.slot_heads > 0 and self.slots > 0 and self.slot_stride > 0

    def fc1_in(self) -> int:
        """What the first layer actually takes: the whole observation, or everything outside the slots plus the one slot
        each head picked out."""

        return (self.obs_dim - self.slots * self.slot_stride + self.slot_heads * self.slot_stride
                if self.attended() else self.obs_dim)

    def hash(self) -> int:
        packed = struct.pack("<6I", self.obs_dim, self.h1, self.hidden, self.h3, self.out_dim, self.std_dim)

        # A plain network hashes exactly as it did before these four fields existed, so that adding them retires nothing:
        # the identity of a plain network has not changed just because another shape is now describable.
        if self.attended():
            packed += struct.pack("<4I", self.slot_at, self.slots, self.slot_stride, self.slot_heads)

        return zlib.crc32(packed) & 0xFFFFFFFF

    def size(self) -> int:
        return (
            2 * self.obs_dim
            # scoreW, scoreB, scoreEmpty: one row of weights over a slot, one bias and one empty token per head.
            + (self.slot_heads * (self.slot_stride + 2) if self.attended() else 0)
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
            # One score per slot per head, and then one weighted sum over the slots and the empty token per head.
            (self.slot_heads * self.slot_stride * (2 * self.slots + 1) if self.attended() else 0)
            + self.fc1_in() * self.h1
            + 3 * self.hidden * self.h1
            + 3 * self.hidden * self.hidden
            + self.hidden * self.h3
            + self.h3 * self.out_dim
        )

    def describe(self) -> str:
        slots = f"{self.slots}x{self.slot_stride} -> {self.slot_heads} attended, " if self.attended() else ""

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

    # The scores come before the first layer here because that is the order the game applies them in, and the file is read
    # straight through without seeking.
    if actor.topology.attended():
        parts += [actor.attention.score_w, actor.attention.score_b, actor.attention.score_empty]

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
        topology.slot_heads,
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

    # Version 1 has nothing over the slots, which is exactly a later file with those four numbers at zero, so the
    # versions are read by the same code with a shorter header.
    layout = HEADER if version >= 2 else HEADER_V1

    if len(data) < layout.size:
        raise ValueError(f"{path} is shorter than its own header says")

    fields = layout.unpack_from(data)
    schema_id, topology_hash = fields[2], fields[3]
    obs_dim, h1, hidden, h3, out_dim, std_dim = fields[4:10]
    slots = fields[10:14] if version >= 2 else (0, 0, 0, 0)
    obs_clip, iteration, count = fields[-3:]

    # In version 2 the fourth number was the width of a max-pooled encoder over the slots, and in version 3 it is the
    # number of attention heads: the same four bytes meaning two different things. A plain file says zero in both, which is
    # every network ever published; anything else is refused by name rather than read as the shape it is not.
    if version == 2 and slots[3] != 0:
        raise ValueError(
            f"{path} is a max-pooled network, which this build no longer reads: the enemy slots go through attention "
            f"now, see docs/architecture.md"
        )

    topology = Topology(obs_dim, h1, hidden, h3, out_dim, std_dim, *slots)

    if topology.hash() != topology_hash:
        raise ValueError(f"{path} has a topology hash that does not match its own dimensions")

    if count != topology.size() or len(data) != layout.size + 4 * count:
        raise ValueError(f"{path} does not hold the {topology.size()} parameters its header claims")

    flat = np.frombuffer(data, dtype="<f4", count=count, offset=layout.size)

    return WeightHeader(schema_id, topology, float(obs_clip), int(iteration)), flat

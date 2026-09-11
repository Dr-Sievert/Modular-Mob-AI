"""The wire between the game and the training process.

Everything is little endian, which lets a message be read straight into a numpy array with no rearranging. Sizes are
never sent: every array's length follows from the agent count and the dimensions agreed during the greeting, so there is
nothing to keep in step and nothing to get wrong.

The conversation, from the game's side::

    HELLO    u32 magic, u16 version, u32 workerId, u32 schemaLength, utf8[schemaLength]
    (reply)  u8 status                                          0 accepts, anything else refuses

    STEP     u8 type=1, u32 count, i32[count] ids, u8[count] flags, f32[count] rewards, f32[count*obsDim] obs
    ACTIONS  u8 type=2, u32 count, f32[count*actDim] actions
    BYE      u8 type=0

The game blocks between sending a step and receiving the actions, so a slow reply here shows up directly as a slower
server tick. There is roughly ten milliseconds of room per tick; a batched forward pass uses a small fraction of it.
"""

from __future__ import annotations

import socket
import struct
from dataclasses import dataclass

import numpy as np

MAGIC = 0x4D4D4149
VERSION = 1

TYPE_BYE = 0
TYPE_STEP = 1
TYPE_ACTIONS = 2

STATUS_OK = 0
STATUS_REFUSED = 1

FLAG_NEW = 1
"""First step of this agent's episode. Start its hidden state from scratch."""

FLAG_DONE = 2
"""Last step of this agent's episode. The observation is real and worth bootstrapping from; the action is ignored."""


class ProtocolError(RuntimeError):
    pass


@dataclass(frozen=True)
class Step:
    """One tick's worth of agents.

    The arrays are views onto the receive buffer and are overwritten by the next step, so anything kept beyond the call
    that produced it has to be copied.
    """

    worker_id: int
    agent_ids: np.ndarray  # int32,   (count,)
    flags: np.ndarray  # uint8,   (count,)
    rewards: np.ndarray  # float32, (count,)
    observations: np.ndarray  # float32, (count, obs_dim)

    @property
    def count(self) -> int:
        return int(self.agent_ids.shape[0])

    def keys(self) -> list[tuple[int, int]]:
        """Identity for each row, unique across workers.

        Entity ids only mean anything inside one game process, so a parallel run has several agents called seven. Pairing
        the id with the worker is what keeps their hidden states apart.
        """
        return [(self.worker_id, int(agent)) for agent in self.agent_ids]


def read_exactly(sock: socket.socket, size: int) -> memoryview:
    """Reads exactly ``size`` bytes, or raises. A short read is normal on a socket and never an error by itself."""

    buffer = bytearray(size)
    view = memoryview(buffer)
    filled = 0

    while filled < size:
        received = sock.recv_into(view[filled:], size - filled)

        if received == 0:
            raise ProtocolError(f"connection closed after {filled} of {size} bytes")

        filled += received

    return memoryview(buffer)


def read_hello(sock: socket.socket) -> tuple[int, str]:
    """Reads the greeting and returns the worker id and the schema JSON it carried."""

    header = read_exactly(sock, 14)
    magic, version, worker_id, schema_length = struct.unpack_from("<IHII", header, 0)

    if magic != MAGIC:
        raise ProtocolError(f"not a game connection: magic was {magic:#x}")

    if version != VERSION:
        raise ProtocolError(f"protocol version {version}, expected {VERSION}")

    schema = bytes(read_exactly(sock, schema_length)).decode("utf-8")
    return worker_id, schema


def send_status(sock: socket.socket, status: int) -> None:
    sock.sendall(bytes([status]))


def read_step(sock: socket.socket, worker_id: int, obs_dim: int) -> Step | None:
    """Reads one step, or None once the game says goodbye."""

    kind = read_exactly(sock, 1)[0]

    if kind == TYPE_BYE:
        return None

    if kind != TYPE_STEP:
        raise ProtocolError(f"expected a step, got message type {kind}")

    count = struct.unpack("<I", read_exactly(sock, 4))[0]

    agent_ids = np.frombuffer(read_exactly(sock, 4 * count), dtype="<i4")
    flags = np.frombuffer(read_exactly(sock, count), dtype=np.uint8)
    rewards = np.frombuffer(read_exactly(sock, 4 * count), dtype="<f4")
    observations = np.frombuffer(read_exactly(sock, 4 * count * obs_dim), dtype="<f4").reshape(count, obs_dim)

    return Step(worker_id=worker_id, agent_ids=agent_ids, flags=flags, rewards=rewards, observations=observations)


def send_actions(sock: socket.socket, actions: np.ndarray) -> None:
    """Sends one action per agent, in the order the step arrived in."""

    count = actions.shape[0]
    payload = np.ascontiguousarray(actions, dtype="<f4")

    sock.sendall(struct.pack("<BI", TYPE_ACTIONS, count))
    sock.sendall(payload.tobytes())

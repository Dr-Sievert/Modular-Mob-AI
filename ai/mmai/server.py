"""Listens for game workers and answers their steps.

A parallel run starts one game process per worker and every one of them connects here, so this has to hold several
conversations at once. Each gets a thread: the work in a thread is a socket read, a forward pass and a socket write, and
the read is where nearly all of the wall clock goes, so threads spend most of their lives blocked and out of each
other's way.

Every worker must describe the same game. The first one to connect sets the schema and the rest are measured against it,
because steps from workers that disagree cannot share a network.
"""

from __future__ import annotations

import socket
import threading
from typing import Callable

from .policy import Policy
from .protocol import (
    FLAG_DONE,
    STATUS_OK,
    STATUS_REFUSED,
    ProtocolError,
    read_hello,
    read_step,
    send_actions,
    send_status,
)
from .schema import Schema

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765


class Server:
    def __init__(
        self,
        policy_factory: Callable[[], Policy],
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        shared_policy: bool = True,
    ) -> None:
        """
        :param shared_policy: one policy serving every worker, which is what training wants, since all the experience
            belongs to the same network. Turn it off to give each worker its own, which is only useful for testing.
        """

        self._policy_factory = policy_factory
        self._host = host
        self._port = port
        self._shared = policy_factory() if shared_policy else None
        self._lock = threading.Lock()

        self._schema: Schema | None = None
        self._schema_lock = threading.Lock()

    def serve_forever(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind((self._host, self._port))
            listener.listen(64)

            print(f"[mmai] listening on {self._host}:{self._port}")

            while True:
                connection, address = listener.accept()
                thread = threading.Thread(target=self._serve, args=(connection, address), daemon=True)
                thread.start()

    def _serve(self, connection: socket.socket, address) -> None:
        with connection:
            connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

            try:
                worker_id, schema_json = read_hello(connection)
                schema = Schema.parse(schema_json)

                if not self._accept_schema(schema):
                    send_status(connection, STATUS_REFUSED)
                    print(f"[mmai] refused worker {worker_id}: it describes a different game")
                    return

                send_status(connection, STATUS_OK)
                print(f"[mmai] worker {worker_id} from {address[0]}: {schema.describe()}")

                self._pump(connection, worker_id, schema)

            except (ProtocolError, ConnectionError, OSError) as failure:
                print(f"[mmai] worker at {address[0]} went away: {failure}")

    def _pump(self, connection: socket.socket, worker_id: int, schema: Schema) -> None:
        policy = self._shared if self._shared is not None else self._policy_factory()
        steps = 0

        while True:
            step = read_step(connection, worker_id, schema.obs_dim)

            if step is None:
                print(f"[mmai] worker {worker_id} finished after {steps:,} steps")
                return

            # A shared policy is one object being driven from several threads, so its turn on the network is serialised.
            # This is also the seam where steps from different workers would be gathered into one forward pass, if the
            # lock ever turns out to be what is costing the time.
            if self._shared is not None:
                with self._lock:
                    actions = policy.act(step, schema)
                    self._retire(policy, step)
            else:
                actions = policy.act(step, schema)
                self._retire(policy, step)

            send_actions(connection, actions)
            steps += 1

    @staticmethod
    def _retire(policy: Policy, step) -> None:
        finished = [key for key, flag in zip(step.keys(), step.flags) if flag & FLAG_DONE]

        if finished:
            policy.forget(finished)

    def _accept_schema(self, schema: Schema) -> bool:
        with self._schema_lock:
            if self._schema is None:
                self._schema = schema
                return True

            return self._schema.matches(schema)

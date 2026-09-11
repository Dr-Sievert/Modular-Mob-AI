"""Listens for game workers and answers their steps.

A parallel run starts one game process per worker and every one of them connects here. Each gets a thread that reads
its steps and writes its actions; the thread spends nearly all of its life blocked on the socket.

The steps themselves are not answered one worker at a time. They are gathered by the :class:`Batcher` into one call on
the policy per tick, covering every worker that has a step waiting, because a forward pass over three hundred agents
costs barely more than one over a hundred and the alternative is three passes in a row. The workers fall into step with
each other on their own: they are all released by the same batch, tick for about the same time, and arrive back
together. A worker that is genuinely slow to arrive, mid startup or between batches of tests, is waited for only as
long as ``window`` allows, so the rest are never held up for long.

Every worker must describe the same game. The first one to connect sets the schema and the rest are measured against it,
because steps from workers that disagree cannot share a network.
"""

from __future__ import annotations

import socket
import threading
import time
from typing import Callable

import numpy as np

from . import log
from .policy import Policy
from .protocol import (
    FLAG_DONE,
    STATUS_OK,
    STATUS_REFUSED,
    ProtocolError,
    Step,
    read_hello,
    read_step,
    send_actions,
    send_status,
)
from .schema import Schema

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765

logger = log.get("server")


class Batcher:
    """Turns one step per worker into one call on the policy."""

    def __init__(self, policy: Policy, window: float) -> None:
        """
        :param window: how long, in seconds, a step waits for the other workers before going without them.
        """
        self._policy = policy
        self._window = window

        self._lock = threading.Condition()
        self._connected = 0
        self._waiting: list[tuple[Step, list]] = []
        self._round = 0
        self._schema: Schema | None = None

        # For the log: how many workers each call to the policy covered.
        self.calls = 0
        self.rows = 0
        self.workers_served = 0

    def attach(self) -> None:
        with self._lock:
            self._connected += 1

    def detach(self) -> None:
        with self._lock:
            self._connected -= 1
            # Whoever is waiting for this worker should stop waiting for it.
            self._lock.notify_all()

    def submit(self, step: Step, schema: Schema) -> np.ndarray:
        """Hands in one worker's step and blocks until its actions are ready."""

        holder: list = []

        with self._lock:
            self._schema = schema
            self._waiting.append((step, holder))
            joined = self._round

            deadline = time.monotonic() + self._window

            # The last worker to arrive runs the batch for everyone. A worker that has waited its whole window runs it
            # for whoever has arrived, and anyone still to come joins the next one.
            while not holder and self._round == joined:
                if len(self._waiting) >= self._connected:
                    self._run()
                    break

                remaining = deadline - time.monotonic()

                if remaining <= 0.0:
                    self._run()
                    break

                self._lock.wait(remaining)

        result = holder[0]

        if isinstance(result, BaseException):
            raise result

        return result

    def _run(self) -> None:
        """Called with the lock held. Answers every step waiting, then wakes their threads."""

        waiting = self._waiting
        self._waiting = []
        self._round += 1

        steps = [step for step, _ in waiting]
        merged = steps[0] if len(steps) == 1 else Step.merge(steps)

        try:
            actions = self._policy.act(merged, self._schema)

            finished = [key for key, flag in zip(merged.keys(), merged.flags) if flag & FLAG_DONE]

            if finished:
                self._policy.forget(finished)

            offset = 0

            for step, holder in waiting:
                holder.append(actions[offset : offset + step.count])
                offset += step.count

            self.calls += 1
            self.rows += merged.count
            self.workers_served += len(waiting)

        except BaseException as failure:
            # Everyone in this batch gets the failure; leaving them waiting would hang every worker for good.
            for _, holder in waiting:
                holder.append(failure)

            raise

        finally:
            self._lock.notify_all()

    def take_stats(self) -> tuple[float, float]:
        """Mean rows and mean workers per policy call since last asked."""
        with self._lock:
            calls = max(1, self.calls)
            stats = (self.rows / calls, self.workers_served / calls)
            self.calls = self.rows = self.workers_served = 0
            return stats


class Server:
    def __init__(
        self,
        policy_factory: Callable[[], Policy],
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        window: float = 0.003,
    ) -> None:
        """
        :param window: how long a worker's step waits for the others, in seconds. Once the workers are in step this is
            rarely reached; it bounds the wait when one of them is busy elsewhere.
        """

        self._host = host
        self._port = port
        self._policy = policy_factory()
        self._batcher = Batcher(self._policy, window)

        # A policy that wants to report how the batching is going gets a way to ask.
        if hasattr(self._policy, "batch_stats"):
            self._policy.batch_stats = self._batcher.take_stats

        self._schema: Schema | None = None
        self._schema_lock = threading.Lock()

    @property
    def batcher(self) -> Batcher:
        return self._batcher

    def serve_forever(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind((self._host, self._port))
            listener.listen(64)

            logger.info("listening on %s:%d", self._host, self._port)

            while True:
                connection, address = listener.accept()
                thread = threading.Thread(target=self._serve, args=(connection, address), daemon=True)
                thread.start()

    def _serve(self, connection: socket.socket, address) -> None:
        with connection:
            connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            worker_id = -1

            try:
                worker_id, schema_json = read_hello(connection)
                schema = Schema.parse(schema_json)

                if not self._accept_schema(schema):
                    send_status(connection, STATUS_REFUSED)
                    logger.warning("refused worker %d: it describes a different game", worker_id)
                    return

                send_status(connection, STATUS_OK)
                logger.info("worker %d connected: %s", worker_id, schema.describe())

                self._batcher.attach()

                try:
                    self._pump(connection, worker_id, schema)
                finally:
                    self._batcher.detach()

            except (ProtocolError, ConnectionError, OSError) as failure:
                logger.info("worker %d went away: %s", worker_id, failure)

            except Exception:
                logger.exception("worker %d: the policy failed", worker_id)

    def _pump(self, connection: socket.socket, worker_id: int, schema: Schema) -> None:
        steps = 0

        while True:
            step = read_step(connection, worker_id, schema.obs_dim)

            if step is None:
                logger.info("worker %d finished after %s steps", worker_id, f"{steps:,}")
                return

            actions = self._batcher.submit(step, schema)
            send_actions(connection, actions)
            steps += 1

    def _accept_schema(self, schema: Schema) -> bool:
        with self._schema_lock:
            if self._schema is None:
                self._schema = schema
                return True

            return self._schema.matches(schema)

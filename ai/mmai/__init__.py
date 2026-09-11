"""The training side of Modular Mob AI.

The game runs the fights and this decides what the agents do in them. The two talk over a socket, and the whole of that
conversation is in :mod:`mmai.protocol`.
"""

from . import log
from .policy import EchoPolicy, Policy, RandomPolicy
from .schema import Schema
from .server import Server

__all__ = ["EchoPolicy", "Policy", "RandomPolicy", "Schema", "Server", "log"]

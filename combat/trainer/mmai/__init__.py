"""The training side of Modular Mob AI.

The game runs the fights and the network that fights them; this learns better weights from what it recorded and hands
them back. The whole of the conversation between the two is files in a run folder, described in :mod:`mmai.run`.
"""

from . import log
from .schema import Schema

__all__ = ["Schema", "log"]

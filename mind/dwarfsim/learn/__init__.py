"""The learned arbitrator: collect the hand-written scorer's decisions, then imitate them.

Three steps, each its own module and each its own command line:

    python -m dwarfsim.learn.collect --seeds 1-8 --ticks 2000 --out runs/learn/teacher.npz
    python -m dwarfsim.learn.imitate --data runs/learn/teacher.npz --out runs/learn/imitator
    python -m dwarfsim run --scenario feud --seed 9 --scorer runs/learn/imitator \
        --out runs/feud_learned.jsonl

The teacher is :mod:`dwarfsim.arbitrator`'s weight table. The student is a 133-64-64-1 MLP over
``observation ++ candidate_features``, trained listwise. Nothing else about the sim changes: the
skills still propose, the softmax still samples, the log still says why.

:class:`LearnedScorer` is the only thing the sim itself imports, and it is pure numpy -- torch is
needed to train, never to run.
"""

from .scorer import LearnedScorer

__all__ = ["LearnedScorer"]

"""The torch model: an embedding bag, a side vector, one hidden layer, six heads.

    buckets -> EmbeddingBag(65536, 32, sum) -> ReLU -> concat(side, 20 floats)
            -> Linear(52, 64) -> ReLU
            -> intent    Linear(64, 13)  softmax
            -> topic     Linear(64, 12)  softmax
            -> addressed Linear(64, 4)   softmax
            -> sincerity Linear(64, 3)   softmax   (optional label, masked)
            -> floats    Linear(64, 3)   [sigmoid, tanh, sigmoid]
                                         = aggression, valence, urgency

The three regressions share one Linear so the export has one array per layer;
the activation is per column and fixed by `labels.FLOAT_ACTIVATIONS`.

The side vector (`features.side_features`, length `features.SIDE_DIM`) is
concatenated *after* the ReLU on the embedding sum and before `fc1`, so the
embedding half of the pipeline is byte-for-byte what it always was and fc1 just
grew from (64, 32) to (64, 32 + SIDE_DIM). Everything in it is already
non-negative, so putting it inside or outside the ReLU would give the same
numbers; outside is the version that is one line in Java.

Loss = CE(intent, class weighted) + CE(topic) + CE(addressed) + CE(sincerity)
     + FLOAT_LOSS_WEIGHT * MSE(floats), with FLOAT_LOSS_WEIGHT = 2.0 so the
three small regressions are not drowned out by the cross-entropies.

`sincerity` is optional on a record (SCHEMA.md: "optional on early records").
Rows without it carry the target `labels.MASKED` and cross_entropy's
`ignore_index` drops them from the term; a batch with no labelled row at all
contributes exactly 0.0 rather than NaN.
"""

from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F

from .features import BUCKETS, SIDE_DIM
from .labels import ADDRESSED, FLOAT_FIELDS, INTENTS, MASKED, SINCERITY, TOPICS

EMB_DIM = 32
HIDDEN = 64
FLOAT_LOSS_WEIGHT = 2.0


class DialogueClassifier(nn.Module):
    def __init__(self, buckets: int = BUCKETS, dim: int = EMB_DIM, hidden: int = HIDDEN,
                 side: int = SIDE_DIM):
        super().__init__()
        self.buckets, self.dim, self.hidden, self.side = buckets, dim, hidden, side
        self.emb = nn.EmbeddingBag(buckets, dim, mode="sum")
        self.fc1 = nn.Linear(dim + side, hidden)
        self.head_intent = nn.Linear(hidden, len(INTENTS))
        self.head_topic = nn.Linear(hidden, len(TOPICS))
        self.head_addressed = nn.Linear(hidden, len(ADDRESSED))
        self.head_sincerity = nn.Linear(hidden, len(SINCERITY))
        self.head_floats = nn.Linear(hidden, len(FLOAT_FIELDS))
        nn.init.uniform_(self.emb.weight, -0.05, 0.05)

    def forward(self, indices: torch.Tensor, offsets: torch.Tensor,
                side: torch.Tensor) -> dict:
        """indices: flat int64 buckets of the batch; offsets: start of each row;
        side: float32 (rows, SIDE_DIM) from `features.side_features`."""
        h = F.relu(self.emb(indices, offsets))
        h = F.relu(self.fc1(torch.cat([h, side], dim=1)))
        raw = self.head_floats(h)
        return {
            "intent": self.head_intent(h),
            "topic": self.head_topic(h),
            "addressed": self.head_addressed(h),
            "sincerity": self.head_sincerity(h),
            # column order is labels.FLOAT_FIELDS = aggression, valence, urgency
            "floats": torch.stack([torch.sigmoid(raw[:, 0]),
                                   torch.tanh(raw[:, 1]),
                                   torch.sigmoid(raw[:, 2])], dim=1),
        }


def _masked_ce(logits: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    if not bool((target != MASKED).any()):
        return logits.sum() * 0.0 + 0.0  # keeps the graph, contributes nothing (and not -0.0)
    return F.cross_entropy(logits, target, ignore_index=MASKED)


def loss_fn(out: dict, targets: dict, intent_weight: torch.Tensor | None = None) -> dict:
    """Total loss plus each term, so training can print where the loss sits."""
    parts = {
        "intent": F.cross_entropy(out["intent"], targets["intent"], weight=intent_weight),
        "topic": F.cross_entropy(out["topic"], targets["topic"]),
        "addressed": F.cross_entropy(out["addressed"], targets["addressed"]),
        "sincerity": _masked_ce(out["sincerity"], targets["sincerity"]),
        "floats": FLOAT_LOSS_WEIGHT * F.mse_loss(out["floats"], targets["floats"]),
    }
    parts["total"] = (parts["intent"] + parts["topic"] + parts["addressed"]
                      + parts["sincerity"] + parts["floats"])
    return parts

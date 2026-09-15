"""The student: a tiny MLP over ``observation ++ candidate_features``.

    133 -> 64 -> ReLU -> 64 -> ReLU -> 1

Three Linears and nothing else. No normalisation layer, no embedding table, no residual: the
whole point is that the Java port is six float arrays and two matrix products, the same shape the
combat brain already loads. About 13k parameters, 50 KB on disk.

The observation is the same for every candidate of one decision, so the forward pass takes the
two halves separately: ``forward(obs, cand)`` with ``obs`` shaped ``(B, OBS_SIZE)`` and ``cand``
shaped ``(B, K, CAND_SIZE)`` broadcasts the observation across the K candidates instead of
materialising it K times. That is the only thing here that is not the obvious code.
"""

import torch
from torch import nn

from ..schema import CAND_SIZE, OBS_SIZE

#: Torch is a guest on a shared machine. One thread, always, imported or not.
torch.set_num_threads(1)

HIDDEN = (64, 64)


class Scorer(nn.Module):
    """``score(observation, candidate features) -> one float``."""

    def __init__(self, obs_size=OBS_SIZE, cand_size=CAND_SIZE, hidden=HIDDEN):
        super().__init__()
        self.obs_size = obs_size
        self.cand_size = cand_size
        self.hidden = tuple(hidden)
        self.fc1 = nn.Linear(obs_size + cand_size, self.hidden[0])
        self.fc2 = nn.Linear(self.hidden[0], self.hidden[1])
        self.out = nn.Linear(self.hidden[1], 1)

    def forward(self, obs, cand):
        """``obs`` ``(B, OBS)``, ``cand`` ``(B, K, CAND)`` -> ``(B, K)`` scores.

        ``obs`` may also be ``(B, K, OBS)`` if a caller really has one per candidate.
        """
        w = self.fc1.weight
        h = torch.nn.functional.linear(cand, w[:, self.obs_size:])
        part = torch.nn.functional.linear(obs, w[:, :self.obs_size], self.fc1.bias)
        if part.dim() == h.dim() - 1:
            part = part.unsqueeze(-2)
        h = torch.relu(h + part)
        h = torch.relu(self.fc2(h))
        return self.out(h).squeeze(-1)

    def score_flat(self, x):
        """The plain ``(N, OBS + CAND)`` form, for the parity check against numpy."""
        h = torch.relu(self.fc1(x))
        h = torch.relu(self.fc2(h))
        return self.out(h).squeeze(-1)

    def arrays(self):
        """The six float32 arrays :mod:`dwarfsim.learn.scorer` exports, in its names."""
        state = self.state_dict()
        return {name: state[name].detach().cpu().numpy().astype("float32")
                for name in ("fc1.weight", "fc1.bias", "fc2.weight", "fc2.bias",
                             "out.weight", "out.bias")}

    def n_parameters(self):
        return sum(p.numel() for p in self.parameters())

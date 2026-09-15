"""Steering the learning rate by the distance the policy actually moved.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import unittest
from pathlib import Path

from mmai.ppo import Config, Trainer
from mmai.schema import Schema

SCHEMA = Path(__file__).resolve().parents[2] / "mod" / "fabric" / "build" / "brain-parity" / "check" / "humanoid" / "schema.json"


def trainer(**config) -> Trainer:
    return Trainer(Config(device="cpu", **config), Schema.load(SCHEMA))


@unittest.skipUnless(SCHEMA.is_file(), "needs a schema, which scripts\\parity.ps1 writes")
class SteerRateTest(unittest.TestCase):
    def test_an_overshoot_lowers_the_rate_and_the_optimizer_follows(self):
        """league768's own numbers: a target of 0.01 and updates landing at 0.013 to 0.03."""

        one = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5, kl_adapt_band=2.0)

        # Inside the band, which is most iterations: nothing moves, because a rate that chases noise is a worse rate.
        self.assertAlmostEqual(one.steer_rate(0.013), 5e-5, places=12)

        # Past twice the target, which is a real overshoot.
        self.assertAlmostEqual(one.steer_rate(0.03), 5e-5 / 1.5, places=12)
        self.assertAlmostEqual(one.optimizer.param_groups[0]["lr"], 5e-5 / 1.5, places=12)

    def test_room_to_spare_raises_it(self):
        one = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5, kl_adapt_band=2.0)

        self.assertAlmostEqual(one.steer_rate(0.001), 5e-5 * 1.5, places=12)

    def test_it_stays_within_a_decade_either_way(self):
        one = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5, kl_adapt_range=10.0)

        for _ in range(40):
            one.steer_rate(1.0)

        self.assertAlmostEqual(one.rate, 5e-6, places=12)

        for _ in range(80):
            one.steer_rate(0.0)

        self.assertAlmostEqual(one.rate, 5e-4, places=12)

    def test_the_critics_warmup_does_not_steer(self):
        """The policy is not updated while the critic warms up, so the KL reads exactly zero, and zero is not "room to
        spare": steering on it walked the rate to the ceiling before the first real update."""

        one = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5, critic_warmup=30)

        for iteration in range(0, 30):
            one.iteration = iteration
            self.assertAlmostEqual(one.steer_rate(0.0), 5e-5, places=12)

        # The first update that moves the policy is the first that may steer.
        one.iteration = 30
        self.assertAlmostEqual(one.steer_rate(0.0), 5e-5 * 1.5, places=12)

    def test_a_fall_to_the_cpu_keeps_the_steered_rate(self):
        """The fallback rebuilds the optimizer, and rebuilt it at the configured rate: a change of device is not a reason
        to forget what the KL has said about the step size."""

        one = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5)
        one.steer_rate(1.0)
        one._fall_back_to_cpu()

        self.assertAlmostEqual(one.optimizer.param_groups[0]["lr"], 5e-5 / 1.5, places=12)

    def test_off_leaves_the_rate_alone(self):
        one = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=0.0)

        self.assertAlmostEqual(one.steer_rate(10.0), 5e-5, places=12)
        self.assertAlmostEqual(one.optimizer.param_groups[0]["lr"], 5e-5, places=12)

    def test_no_target_is_nothing_to_steer_by(self):
        one = trainer(learning_rate=5e-5, target_kl=0.0, kl_adapt=1.5)

        self.assertAlmostEqual(one.steer_rate(10.0), 5e-5, places=12)

    def test_a_steered_rate_survives_a_resume(self):
        import tempfile

        one = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5)
        one.steer_rate(1.0)
        one.steer_rate(1.0)
        steered = one.rate

        self.assertLess(steered, 5e-5)

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            one.save(path)

            two = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5)
            two.load(path)

        self.assertAlmostEqual(two.rate, steered, places=12)
        self.assertAlmostEqual(two.optimizer.param_groups[0]["lr"], steered, places=12)

    def test_a_state_from_before_this_starts_from_the_configured_rate(self):
        import tempfile

        one = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5)

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            one.save(path)

            import torch

            state = torch.load(path, map_location="cpu", weights_only=False)
            del state["rate_ratio"]
            torch.save(state, path)

            two = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5)
            two.load(path)

        self.assertAlmostEqual(two.rate, 5e-5, places=12)

    def test_a_seed_starts_from_its_own_configured_rate_not_the_copys(self):
        """The copy's state is written by imitation under the default 3e-4; a run seeded from it and configured for 5e-5
        took the 3e-4 and put its first update at a KL of 0.18. The ratio is what carries, and an unsteered ratio is 1."""

        import tempfile

        copy = trainer(learning_rate=3e-4, target_kl=0.01, kl_adapt=1.5)

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            copy.save(path)

            seeded = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5)
            seeded.load(path)

        self.assertAlmostEqual(seeded.rate, 5e-5, places=12)
        self.assertAlmostEqual(seeded.optimizer.param_groups[0]["lr"], 5e-5, places=12)

    def test_a_state_with_only_the_old_absolute_rate_is_read_as_a_ratio(self):
        import tempfile

        one = trainer(learning_rate=3e-4, target_kl=0.01, kl_adapt=1.5)
        one.steer_rate(1.0)  # 3e-4 / 1.5

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "state.pt"
            one.save(path)

            import torch

            state = torch.load(path, map_location="cpu", weights_only=False)
            del state["rate_ratio"]
            state["rate"] = 3e-4 / 1.5
            torch.save(state, path)

            two = trainer(learning_rate=5e-5, target_kl=0.01, kl_adapt=1.5)
            two.load(path)

        self.assertAlmostEqual(two.rate, 5e-5 / 1.5, places=12)


if __name__ == "__main__":
    unittest.main()

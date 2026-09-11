"""Runs the training server.

    python serve.py                       train from scratch with PPO
    python serve.py --checkpoint X.pt     carry on from a checkpoint
    python serve.py --policy random       flail, for proving the connection works
    python serve.py --policy echo         do nothing and log what arrives, for checking the observation

The game connects to this; see README.md for how to start it pointed here. Everything logged goes to the console and
to a file under logs/, so a run that was stopped from outside still leaves its record behind.
"""

from __future__ import annotations

import argparse
from dataclasses import fields

from mmai import EchoPolicy, RandomPolicy, Server, log
from mmai.server import DEFAULT_HOST, DEFAULT_PORT


def main() -> None:
    parser = argparse.ArgumentParser(description="Serve actions to Modular Mob AI game workers.")
    parser.add_argument("--policy", choices=["ppo", "random", "echo"], default="ppo")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--checkpoint", help="a .pt file to resume from")
    parser.add_argument("--log-dir", default="logs")
    parser.add_argument(
        "--window",
        type=float,
        default=3.0,
        help="milliseconds a worker's step waits for the other workers before being answered without them",
    )

    # Every field of the PPO config is a flag, so nothing needs editing to try a different setting.
    from mmai.ppo import Config

    for field in fields(Config):
        kind = type(field.default)
        parser.add_argument(f"--{field.name.replace('_', '-')}", type=str if kind is bool else kind, default=None)

    arguments = parser.parse_args()

    path = log.setup(arguments.log_dir)
    logger = log.get("serve")
    logger.info("logging to %s", path)

    if arguments.policy == "ppo":
        from mmai.ppo import PPOPolicy

        overrides = {}

        for field in fields(Config):
            value = getattr(arguments, field.name)

            if value is None:
                continue

            overrides[field.name] = value.lower() in ("1", "true", "yes") if type(field.default) is bool else value

        config = Config(**overrides)
        logger.info("ppo config: %s", config)

        def factory():
            return PPOPolicy(config, checkpoint=arguments.checkpoint)

    elif arguments.policy == "random":
        factory = RandomPolicy

    else:
        factory = EchoPolicy

    server = Server(factory, host=arguments.host, port=arguments.port, window=arguments.window / 1000.0)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("stopped")


if __name__ == "__main__":
    main()

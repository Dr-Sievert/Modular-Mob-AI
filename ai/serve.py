"""Runs the training server.

    python serve.py                 a policy that flails, for proving the connection works
    python serve.py --policy echo   does nothing and prints what it sees, for checking the observation
"""

from __future__ import annotations

import argparse

from mmai import EchoPolicy, RandomPolicy, Server
from mmai.server import DEFAULT_HOST, DEFAULT_PORT

POLICIES = {
    "random": RandomPolicy,
    "echo": EchoPolicy,
}


def main() -> None:
    parser = argparse.ArgumentParser(description="Serve actions to Modular Mob AI game workers.")
    parser.add_argument("--policy", choices=sorted(POLICIES), default="random")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    arguments = parser.parse_args()

    server = Server(POLICIES[arguments.policy], host=arguments.host, port=arguments.port)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mmai] stopped")


if __name__ == "__main__":
    main()

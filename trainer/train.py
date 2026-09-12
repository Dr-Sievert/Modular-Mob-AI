"""The training side of Modular Mob AI.

    python train.py loop --run ../runs/default      wait for rollouts, learn, export the next weights, repeat
    python train.py init --run ../runs/default      just write iteration zero's weights and stop
    python train.py imitate --run ../runs/imitate   start a run by copying the recorded scripted fighter
    python train.py parity --schema S --out DIR     build the fixture the game checks its own forward pass against

The game is what runs the network; this only improves it. The two never talk directly: the game writes rollout shards
into the run folder and waits for the next weights to appear, and this waits for the shards and writes those weights.
Everything logged goes to the console and to the run's logs/ folder, so a run that was stopped from outside still leaves
its record behind.

Normally the build starts this: see scripts/train.ps1 and trainer/README.md.
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
from dataclasses import fields
from pathlib import Path

import torch

import numpy as np

from mmai import log
from mmai.evaluate import Evaluator
from mmai.league import League, checkpoint_name
from mmai.ppo import Config, Trainer
from mmai.rollout import ShardHeader, read_header, read_shard
from mmai.run import TRAINING, WAITING, RunDirectory, Workers
from mmai.schema import Schema

logger = log.get("train")


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Modular Mob AI agents from recorded rollouts.")
    parser.add_argument("command", choices=["loop", "init", "imitate", "parity"], nargs="?", default="loop")
    parser.add_argument("--run", help="the run folder the game is writing into")
    parser.add_argument("--schema", help="the layout the game wrote, defaults to <run>/schema.json")
    parser.add_argument("--out", help="where to put the parity fixture")
    parser.add_argument("--demos", help="the recorded teacher for imitate, defaults to <run>/demos")
    parser.add_argument("--imitation-epochs", type=int, default=80, help="passes over the teacher's record")
    parser.add_argument("--log-dir", help="defaults to <run>/logs, or the parity folder")
    parser.add_argument("--keep-weights", type=int, default=5, help="how many recent weight files to keep")

    # Every field of the config is a flag, so nothing needs editing to try a different setting.
    for field in fields(Config):
        kind = type(field.default)
        parser.add_argument(f"--{field.name.replace('_', '-')}", type=str if kind is bool else kind, default=None)

    arguments = parser.parse_args()

    overrides = {}

    for field in fields(Config):
        value = getattr(arguments, field.name)

        if value is None:
            continue

        overrides[field.name] = value.lower() in ("1", "true", "yes") if type(field.default) is bool else value

    config = Config(**overrides)

    lower_priority()

    default_logs = Path(arguments.run) / "logs" if arguments.run else Path(arguments.out or "parity")
    path = log.setup(arguments.log_dir or default_logs)
    logger.info("logging to %s", path)

    if arguments.command == "parity":
        from mmai.parity import generate

        if not arguments.schema:
            parser.error("parity needs --schema, which the game writes with its BrainTool")

        schema = Schema.load(arguments.schema)
        logger.info("%s", schema.describe())

        directory = generate(arguments.out or "parity", schema, config.h1, config.hidden, config.h3, config.obs_clip,
                             slot_enc=config.slot_enc)
        logger.info("wrote the parity fixture to %s", directory.resolve())
        return

    if not arguments.run:
        parser.error("loop, init and imitate need --run")

    run = RunDirectory(arguments.run)
    schema = Schema.load(arguments.schema or run.schema_file())

    logger.info("run %s", run.path)
    logger.info("%s", schema.describe())
    logger.info("config %s", config)

    trainer = Trainer(config, schema)

    if arguments.command == "imitate":
        imitate(run, trainer, schema, Path(arguments.demos) if arguments.demos else run.path / "demos", arguments.imitation_epochs)
        return

    if run.state_file().is_file():
        trainer.load(run.state_file())

    else:
        trainer.save(run.state_file())

    # The game cannot start without weights to act under, and a resumed run may have lost the file its state refers to.
    weights = run.weights_file(trainer.iteration)

    if not weights.is_file():
        trainer.export(weights, trainer.iteration)
        logger.info("wrote iteration %d to %s", trainer.iteration, weights.name)

    if arguments.command == "init":
        run.status(WAITING, trainer.iteration, 0)
        return

    # Anything left in the folder was collected by a run that is no longer going, which makes it off-policy by now.
    run.clear_rollouts()

    if config.teacher_weight > 0.0:
        trainer.set_teacher(sample_teacher(run.path / "demos", schema, config.teacher_rows))

    loop(run, trainer, config, schema, arguments.keep_weights)


def sample_teacher(demos: Path, schema: Schema, rows: int) -> list:
    """Fights from the run's record of its teacher, taken evenly from every round of it up to about so many steps. Read a
    shard at a time: the whole record of a copy corrected three times is several gigabytes."""

    paths = sorted(demos.rglob("*.mbr"))

    if not paths:
        raise SystemExit(f"a pull towards the teacher needs its record, and there is none in {demos}")

    total = sum(read_header(path).steps for path in paths)
    share = min(1.0, rows / max(1, total))
    random = np.random.default_rng(0)
    kept = []

    for path in paths:
        header, segments = read_shard(path)

        if header.schema_id != schema.schema_id:
            raise SystemExit(f"{path.name} was recorded against a different layout")

        kept.extend(segment for segment in segments if random.random() < share)

    return kept


def imitate(run: RunDirectory, trainer: Trainer, schema: Schema, demos: Path, epochs: int) -> None:
    """Starts a run from a copy of the recorded teacher rather than from nothing: iteration zero is the copy."""

    # A run still at iteration zero holds nothing but a copy, which a better record may replace. Past that, reinforcement
    # learning has started and a new copy would throw it away.
    if run.state_file().is_file() and torch.load(run.state_file(), map_location="cpu", weights_only=False)["iteration"] > 0:
        raise SystemExit(f"{run.path} has already started training; imitation only makes sense before that")

    segments = []
    steps = 0

    # The teacher's own record and every round of it correcting a copy, which all count the same.
    for folder in sorted({path.parent for path in demos.rglob("*.mbr")}):
        found_here = []

        for path in sorted(folder.glob("*.mbr")):
            header, found = read_shard(path)

            if header.schema_id != schema.schema_id:
                raise SystemExit(f"{path.name} was recorded against a different layout")

            found_here.extend(found)
            steps += header.steps

        wins = sum(1 for segment in found_here if segment.done and float(segment.rewards[-1]) > 0.0)
        logger.info("  %-12s %5d fights, whoever drove won %5.1f%%", folder.name, len(found_here), 100.0 * wins / max(1, len(found_here)))
        segments.extend(found_here)

    if not segments:
        raise SystemExit(f"no demonstrations in {demos}; record some with gradlew :fabric:recordDemonstrations")

    logger.info("copying %d fights, %s steps, from %s", len(segments), f"{steps:,}", demos)

    trainer.imitate(segments, epochs)
    trainer.iteration = 0
    trainer.save(run.state_file())
    trainer.export(run.weights_file(0), 0)

    logger.info("iteration 0 of %s is the copy; training carries on from it", run.path.name)


def lower_priority() -> None:
    """Runs this process below normal priority, so the desktop stays responsive and training yields to everything else."""

    try:
        if sys.platform == "win32":
            import ctypes

            below_normal = 0x00004000
            ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), below_normal)

        else:
            os.nice(5)

    except (OSError, AttributeError):
        # Best effort: a process that could not be reprioritised still trains, just at normal priority.
        pass


def loop(run: RunDirectory, trainer: Trainer, config: Config, schema: Schema, keep_weights: int) -> None:
    workers = Workers()
    carried = []
    forgotten = 0

    # A league run's matchmaking and ratings, brought up to date before the first round so its workers start from them.
    league = League(run, config) if config.league else None

    if league is not None:
        league.update(trainer.iteration)

    # On the league, checkpoints are compared on their Elo rating rather than their win rate: the opponents get harder as
    # the agent does, so a win rate cannot be compared across time. See Evaluator.
    rating = None if league is None else (lambda iteration: league.ratings.rating(checkpoint_name(iteration)))

    evaluator = Evaluator(run, config.checkpoint_every, config.eval_fights, config.eval_patience, config.eval_target,
                          rating)
    done = False

    while True:
        iteration = trainer.iteration

        run.status(WAITING, iteration, workers.rounds_done())
        headers = run.wait_for_iteration(iteration, workers, lambda done: run.status(WAITING, iteration, done))

        segments = list(carried)
        carried = []

        for header in headers:
            check(header, trainer, schema)
            segments.extend(read_shard(header.path)[1])

        workers.consume(headers)
        run.status(TRAINING, iteration, workers.rounds_done())

        steps = sum(segment.steps for segment in segments)

        if steps < config.min_steps:
            # Not worth a gradient. The next iteration's weights are these weights unchanged, so what was collected is
            # still on-policy and waits here for the rest of it.
            logger.info("iteration %d gathered only %d steps; carrying them into the next one", iteration, steps)
            carried = segments
            trainer.iteration += 1

        else:
            stats = trainer.update(segments)
            trainer.iteration += 1
            trainer.report(stats)
            trainer.save(run.state_file())

            if trainer.iteration % config.checkpoint_every == 0:
                keep = run.checkpoints / f"iteration-{trainer.iteration:06d}.pt"
                shutil.copyfile(run.state_file(), keep)
                logger.info("kept %s", keep.name)

        trainer.export(run.weights_file(trainer.iteration), trainer.iteration)
        run.drop_rollouts(iteration)
        run.prune_weights(trainer.iteration, keep_weights, config.checkpoint_every)

        # Done is said once, and the round under way is still learned from to its end: the build only stops starting
        # new ones, and the workers already fighting wait on this side for their next weights.
        reason = evaluator.update(trainer.iteration)

        # What evaluation has found is also what decides whether the teacher has anything left to give; see
        # Trainer.note_evaluation.
        trainer.note_evaluation(evaluator.since_best)

        if league is not None:
            league.update(trainer.iteration)

        if reason and not done:
            done = True
            logger.info("done: %s; the best weights are in %s", reason, evaluator.best_file)
            run.finish(reason)

        elif done and not reason:
            # Taken back rather than left standing. A verdict was once said on a count reached under an older rule and the
            # very next checkpoint beat the best it was about, and because done was said once and never withdrawn the run
            # ended anyway, fifty minutes of a six worker machine running on two. The build reads the file at the top of
            # every round, so deleting it starts rounds again.
            done = False
            run.unfinish()
            logger.info("not done after all: iteration %s is the best now, so the run carries on", evaluator.best)

        # A round whose workers have all left will never send another step, so whatever is still held for its agents is
        # only taking up memory. Over a run of thousands of rounds that adds up.
        if workers.rounds_done() > forgotten and not carried:
            forgotten = workers.rounds_done()
            trainer.forget_rounds(forgotten)


def check(header: ShardHeader, trainer: Trainer, schema: Schema) -> None:
    """A shard from a game that does not match these weights is refused rather than learned from."""

    if header.schema_id != schema.schema_id:
        raise ValueError(
            f"{header.path.name} was recorded against schema {header.schema_id:08x} and this run is "
            f"{schema.schema_id:08x}"
        )

    if header.topology_hash != trainer.actor.topology.hash():
        raise ValueError(f"{header.path.name} was recorded by a differently shaped network")

    if header.obs_dim != schema.obs_dim or header.act_dim != schema.act_dim:
        raise ValueError(f"{header.path.name} disagrees about the size of an observation or an action")


if __name__ == "__main__":
    main()

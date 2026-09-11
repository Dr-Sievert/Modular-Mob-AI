"""The league: who a league run's agent fights, how every player in it rates, and the numbers behind the tier list.

A league run's agent fights nearly every hostile mob there is, the scripted fighter, and frozen checkpoints of itself,
with a different loadout from one fight to the next; the game's gametest/league has who and how. The workers write down
every fight. This side reads them, keeps an Elo rating for every player, decides who the agent meets next, and writes
all of it down:

    runs/RUN/league/roster.csv          written by the workers: the mobs and the scripted fighter they field, and the cap on each
    runs/RUN/league/results/wNN.csv     appended by each worker: iteration,kind,opponent,loadout,opponent_loadout,outcome,ticks,cause,site,finish
    runs/RUN/league/matchmaking.csv     written here: each opponent's share of the training fights, and why
    runs/RUN/league/ratings.csv         written here: every player's rating, best first
    runs/RUN/league/opponents.csv       written here: the agent's recent record against each opponent
    runs/RUN/league/loadouts.csv        written here: the agent's recent record with each loadout
    runs/RUN/league/ground.csv          written here: the fights on each kind of ground, and what finished the other side
    runs/RUN/league/evaluations.csv     written here: every evaluated checkpoint's record against each opponent
    runs/RUN/league/state.json          written here: everything a resumed run needs to carry on where it was

The ground. A quarter of the fights are drawn onto sites with something on them worth knocking an opponent into, lava or a
cliff edge, and every fight says which kind of ground it was on and what finished the other side: the agent, the ground,
its own side, or nothing. ground.csv adds that up per kind of ground. It is the one number that says whether the agent has
learned that the terrain is a weapon, since a fight the ground finishes is its win either way.

The difficulty ladder. Every opponent has three rungs, and its name says which: zombie on normal, zombie(hard) and
zombie(easy) either side. Only normal is there from the start; a rung opens when the agent's evaluated record says it is
ready for one. Past league_hard_at there is little left to learn from the opponent as it stands, so hard opens beside it;
under league_easy_below there is nothing to learn from it yet, so easy does. Either needs league_rung_fights evaluation
fights behind it. Once open a rung stays open, so its rating is never of a moving target and the agent has to keep what it
won. Each rung is a player of its own, as each composition is, and a cap belongs to the opponent rather than the rung.

Who is rated. Every player is a fixed policy: a kind of mob, a squad of them, a rung of the ladder, the scripted fighter,
a checkpoint on its most likely action.
The agent in training is none of those, since it samples and changes every iteration, so only evaluation fights are
rated: a checkpoint on its most likely action, against an opponent drawn evenly from everyone. A fight scores one for a
win, nothing for a loss, and a half when neither killed the other, on time or because a creeper blew itself up. Both
sides move by K times how far the score was from what their ratings expected. The scripted fighter is held where
everyone starts, so the scale means the same from run to run: 1500 fights like the scripted fighter, and 400 points
above a player wins ten fights to its one.

Matchmaking. The training fights go where there is the most to learn: to opponents the agent beats about half the time.
Its chance against each is estimated from its recent training fights against it, which fade an iteration at a time, and
filled in from the ratings while there are few; an opponent's weight is that chance times its complement, which peaks
at an even fight. A floor of the fights is spread evenly, so an opponent the agent always beats, or never does, still
comes round. Self play gets a share of its own, weighed the same way over a pool of checkpoints: the newest few, and the
rest spaced out over the run so far, so the agent has to keep beating what it used to be as well as what it is.

An opponent may also come with a cap on its share, which the workers write into roster.csv beside it, since the game is
what knows: the warden cannot be beaten at all, the reward has no way to pay for getting away alive from one, and the
floor alone would still hand it its even share of the fights. A cap holds it down to a fraction of that and gives what it
gave up to the opponents there is something to learn from. Nothing caps the evaluation draw, so a capped opponent is
rated on as many fights as any other.

When a league run is done is not decided here. A checkpoint's fights against the mobs and the scripted fighter also go
into the run's evaluation, drawn evenly across them, and evaluate.py keeps the best and stops the run once ten judged
checkpoints in a row have failed to beat it. Ratings would make a worse yardstick for that: they are relative, and a
rating that holds still can mean everything around it got better too.
"""

from __future__ import annotations

import json
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

from . import files, log
from .run import RunDirectory

if TYPE_CHECKING:
    from .ppo import Config

logger = log.get("league")

CHECKPOINT = "iteration-"
SCRIPTED = "scripted"

# The rungs of the difficulty ladder either side of normal, as the game writes them on the end of an opponent's name; see
# the gametest's league/Opposition. Normal has no suffix, so every name the league had before the ladder means what it did.
RUNGS = ("(hard)", "(easy)")

OUTCOMES = ("win", "loss", "timeout", "draw")
SCORES = {"win": 1.0, "loss": 0.0, "timeout": 0.5, "draw": 0.5}

# One letter per outcome in the saved windows, which keeps a state of a few thousand fights small.
CODES = {"win": "w", "loss": "l", "timeout": "t", "draw": "d"}
NAMES = {code: name for name, code in CODES.items()}


def checkpoint_name(iteration: int) -> str:
    return f"{CHECKPOINT}{iteration:06d}"


def checkpoint_iteration(name: str) -> int | None:
    return int(name[len(CHECKPOINT) :]) if name.startswith(CHECKPOINT) and name[len(CHECKPOINT) :].isdigit() else None


def base(name: str) -> str:
    """The opponent a name is a rung of: zombie for zombie(hard), and the name itself for anything else."""

    for rung in RUNGS:
        if name.endswith(rung):
            return name[: -len(rung)]

    return name


# ---------------------------------------------------------------------------------------------------------------------
# Elo
# ---------------------------------------------------------------------------------------------------------------------


def expected(rating: float, other: float) -> float:
    """What a player rated so is expected to score against one rated other: a half when level, ten to one at 400 up."""

    return 1.0 / (1.0 + 10.0 ** ((other - rating) / 400.0))


@dataclass
class Player:
    name: str
    kind: str
    rating: float
    games: int = 0
    wins: int = 0
    losses: int = 0
    draws: int = 0


class Ratings:
    """Every player's rating, moved one rated fight at a time.

    :param k: how far a fight moves a rating, times how far its score was from what was expected
    :param provisional: a player's first this many fights move it twice as far, so a newcomer finds its level quickly
    :param initial: where everyone starts
    :param anchor: whoever is held at the start, so the scale means the same in every run; empty for nobody
    """

    def __init__(self, k: float, provisional: int, initial: float, anchor: str) -> None:
        self.k = k
        self.provisional = provisional
        self.initial = initial
        self.anchor = anchor
        self.players: dict[str, Player] = {}

    def ensure(self, name: str, kind: str) -> Player:
        """The player of that name, starting a new one where it most likely belongs.

        A checkpoint starts where the newest one before it has got to, since a network one checkpoint on is much the
        same network; anyone else, and the very first checkpoint, where everyone starts.
        """

        player = self.players.get(name)

        if player is not None:
            return player

        rating = self.initial
        iteration = checkpoint_iteration(name)

        if iteration is not None:
            earlier = [(checkpoint_iteration(other.name), other) for other in self.players.values() if other.kind == "checkpoint"]
            earlier = [(number, other) for number, other in earlier if number is not None and number < iteration]

            if earlier:
                rating = max(earlier, key=lambda pair: pair[0])[1].rating

        player = Player(name, kind, rating)
        self.players[name] = player
        return player

    def k_of(self, player: Player) -> float:
        if player.name == self.anchor:
            return 0.0

        return 2.0 * self.k if player.games < self.provisional else self.k

    def game(self, name: str, kind: str, other_name: str, other_kind: str, score: float) -> None:
        """One rated fight, scored from the first player's side: 1 won, 0 lost, 0.5 neither."""

        player = self.ensure(name, kind)
        other = self.ensure(other_name, other_kind)

        # Both worked out before either moves, so the order the two are given in changes nothing.
        surprise = score - expected(player.rating, other.rating)
        player_k, other_k = self.k_of(player), self.k_of(other)

        player.rating += player_k * surprise
        other.rating -= other_k * surprise

        for one, won in ((player, score), (other, 1.0 - score)):
            one.games += 1
            one.wins += won == 1.0
            one.losses += won == 0.0
            one.draws += won == 0.5

    def rating(self, name: str) -> float:
        player = self.players.get(name)
        return player.rating if player is not None else self.initial

    def newest_checkpoint(self) -> Player | None:
        """The nearest thing to where the agent in training stands: the newest checkpoint that has played its provisional
        fights, which a checkpoint only does as the one being evaluated; failing that, whichever has played the most. A
        checkpoint met a few times from the pool has hardly moved from where it started, and says little."""

        rated = [player for player in self.players.values() if player.kind == "checkpoint" and player.games > 0]

        if not rated:
            return None

        settled = [player for player in rated if player.games >= self.provisional]

        if settled:
            return max(settled, key=lambda player: checkpoint_iteration(player.name) or 0)

        return max(rated, key=lambda player: (player.games, checkpoint_iteration(player.name) or 0))


# ---------------------------------------------------------------------------------------------------------------------
# Matchmaking
# ---------------------------------------------------------------------------------------------------------------------


def win_chance(wins: float, fights: float, guess: float, guess_weight: float) -> float:
    """The agent's chance against an opponent: its record, with the guess counted as so many fights of it."""

    return (wins + guess_weight * guess) / (fights + guess_weight)


def shares(chances: dict[str, float], floor: float, caps: dict[str, float] | None = None) -> dict[str, float]:
    """Each opponent's share of a group's fights, adding up to one.

    Weighed by chance times its complement, which is largest for an even fight and nothing for a certain one, and a
    floor spread evenly so every opponent keeps coming round however the fights against it go. Caps, where the workers
    named any, hold an opponent down to at most its own share of the fights.
    """

    if not chances:
        return {}

    weights = {name: chance * (1.0 - chance) for name, chance in chances.items()}
    total = sum(weights.values())
    even = 1.0 / len(chances)

    if total <= 0.0:
        return capped({name: even for name in chances}, caps or {})

    return capped({name: (1.0 - floor) * weight / total + floor * even for name, weight in weights.items()}, caps or {})


def capped(group: dict[str, float], caps: dict[str, float]) -> dict[str, float]:
    """The same shares with every cap honoured, what the capped ones gave up going to the rest in proportion.

    Spreading what one gave up can push another over its own cap, so this goes round again until nothing is over, which
    takes at most one pass per opponent. If every one of them ends up capped the shares add up to less than one, which
    costs nothing: the workers draw from the shares in proportion, whatever they come to.
    """

    result = dict(group)
    held: set[str] = set()

    for _ in range(len(result)):
        over = [name for name, share in result.items() if name not in held and share > caps.get(name, 1.0)]

        if not over:
            return result

        spare = 0.0

        for name in over:
            spare += result[name] - caps[name]
            result[name] = caps[name]
            held.add(name)

        free = {name: share for name, share in result.items() if name not in held}
        loose = sum(free.values())

        if loose <= 0.0:
            return result

        for name, share in free.items():
            result[name] = share + spare * share / loose

    return result


def pool(checkpoints: list[int], size: int, recent: int) -> list[int]:
    """Which checkpoints the agent meets: the newest few, and the rest spaced out evenly over those before them."""

    ordered = sorted(set(checkpoints))

    if len(ordered) <= size:
        return ordered

    newest = ordered[-recent:] if recent > 0 else []
    older = ordered[: len(ordered) - len(newest)]
    wanted = size - len(newest)

    if wanted <= 0:
        return ordered[-size:]

    # Evenly spaced through the older ones, the first of them always included, so the run's start stays in the pool.
    step = (len(older) - 1) / max(1, wanted - 1)
    spread = sorted({older[round(index * step)] for index in range(wanted)}) if wanted > 1 else [older[0]]

    return spread + newest


# ---------------------------------------------------------------------------------------------------------------------
# Tallies
# ---------------------------------------------------------------------------------------------------------------------


@dataclass
class Tally:
    fights: int = 0
    wins: int = 0
    losses: int = 0
    timeouts: int = 0
    draws: int = 0

    def add(self, outcome: str) -> None:
        self.fights += 1
        self.wins += outcome == "win"
        self.losses += outcome == "loss"
        self.timeouts += outcome == "timeout"
        self.draws += outcome == "draw"

    def values(self) -> list[int]:
        return [self.fights, self.wins, self.losses, self.timeouts, self.draws]

    @staticmethod
    def of(outcomes) -> Tally:
        tally = Tally()

        for outcome in outcomes:
            tally.add(outcome)

        return tally


@dataclass
class Ground:
    """How the fights on one kind of ground went, and how the other side went down.

    The number this exists for is ``by_terrain``: a fight the ground finishes counts as the agent's win either way, so the
    only way to see whether the agent has learned that lava and cliff edges are weapons is to count how often the ground is
    what ended the fight, on ground that had any. Counted over the whole run rather than a window, since it is a trend and
    not a current form, and saved with the rest so a resumed run keeps it.
    """

    fights: int = 0
    wins: int = 0
    by_agent: int = 0
    by_terrain: int = 0
    by_side: int = 0

    def add(self, outcome: str, finish: str) -> None:
        self.fights += 1
        self.wins += outcome == "win"
        self.by_agent += finish == "agent"
        self.by_side += finish == "side"
        self.by_terrain += finish not in ("agent", "side", "-")

    def values(self) -> list[int]:
        return [self.fights, self.wins, self.by_agent, self.by_terrain, self.by_side]


@dataclass
class Windows:
    """The most recent outcomes against each opponent, or with each loadout, so many of each."""

    size: int
    recent: dict[str, deque] = field(default_factory=dict)

    def add(self, name: str, outcome: str) -> None:
        self.recent.setdefault(name, deque(maxlen=self.size)).append(outcome)

    def tally(self, name: str) -> Tally:
        return Tally.of(self.recent.get(name, ()))

    def save(self) -> dict[str, str]:
        return {name: "".join(CODES[outcome] for outcome in outcomes) for name, outcomes in self.recent.items()}

    def load(self, saved: dict[str, str]) -> None:
        for name, codes in saved.items():
            self.recent[name] = deque((NAMES[code] for code in codes), maxlen=self.size)


# ---------------------------------------------------------------------------------------------------------------------
# The league
# ---------------------------------------------------------------------------------------------------------------------


class League:
    """Reads the workers' results once an iteration, and writes the league's files from them."""

    def __init__(self, run: RunDirectory, config: Config) -> None:
        self.run = run
        self.config = config

        self.folder = run.path / "league"
        self.results = self.folder / "results"
        self.results.mkdir(parents=True, exist_ok=True)
        self.state_file = self.folder / "state.json"

        self.ratings = Ratings(config.league_k, config.league_provisional, config.league_initial, config.league_anchor)

        # The agent's training record against each opponent, [wins, fights], both fading an iteration at a time.
        self.training: dict[str, list[float]] = {}

        self.eval_windows = Windows(config.league_window)
        self.train_windows = Windows(config.league_window)
        self.eval_loadouts = Windows(config.league_window)
        self.train_loadouts = Windows(config.league_window)

        # Every evaluated checkpoint's record against each opponent, keyed (iteration, opponent).
        self.evaluations: dict[tuple[int, str], Tally] = {}

        self.offsets: dict[str, int] = {}
        self.rated = 0
        self.shares: dict[str, float] = {}
        self.chances: dict[str, float] = {}

        # What the workers say about each opponent they field: what kind of thing it is, and the largest share of the
        # training fights it may take. Read afresh from roster.csv every iteration, so nothing of it is saved.
        self.kinds: dict[str, str] = {}
        self.caps: dict[str, float] = {}

        # The rungs of the difficulty ladder opened so far, by full name: zombie(hard), 2x_zombie(easy). A ratchet, so it
        # is saved with the rest and a resumed run does not have to earn them again.
        self.rungs: set[str] = set()

        # How the fights on each kind of ground went, and how often the ground itself finished the other side.
        self.ground: dict[str, Ground] = {}

        self._resume()

    # -------------------------------------------------------------------------------------------------------------

    def update(self, iteration: int) -> None:
        """Once per training iteration: takes in what the workers wrote, and writes everything that follows from it."""

        for record in self.training.values():
            record[0] *= self.config.league_decay
            record[1] *= self.config.league_decay

        # Read before the results, so a fight against an opponent nobody has rated yet is filed under the kind the workers
        # say it is rather than the fallback: a player keeps the kind it was first entered under.
        roster = self._roster()

        for row in self._read():
            self._take(*row)

        # Nobody to weigh until a worker has said who it fields; until then the workers go round all of them evenly.
        if roster:
            roster = roster + self._ladder(roster)
            self._matchmake(roster, self._pool(iteration))
            self._write(roster)

        self._save()

        if iteration % max(1, self.config.checkpoint_every) == 0:
            self._report(iteration)

    # -------------------------------------------------------------------------------------------------------------

    def _read(self) -> list[tuple]:
        """Every whole line the workers have appended since the last read, parsed."""

        rows = []

        for file in sorted(self.results.glob("w*.csv")):
            start = self.offsets.get(file.name, 0)

            with open(file, "rb") as stream:
                stream.seek(start)
                data = stream.read()

            # Only whole lines; a worker may be half way through writing the last one.
            end = data.rfind(b"\n") + 1
            self.offsets[file.name] = start + end

            # The eighth field, what the agent died of, is for reading fights back later; nothing here needs it. The ninth
            # and tenth, what ground the fight was on and what finished the other side, are newer than some runs, so a line
            # without them still reads.
            for line in data[:end].decode("utf-8").splitlines():
                parts = line.strip().split(",")

                if not 7 <= len(parts) <= 10 or parts[5] not in SCORES or parts[1] not in ("train", "eval"):
                    continue

                try:
                    rows.append((int(parts[0]), parts[1], parts[2], parts[3], parts[4], parts[5], int(parts[6]),
                                 parts[8] if len(parts) > 8 else "-", parts[9] if len(parts) > 9 else "-"))

                except ValueError:
                    continue

        return rows

    def _take(self, iteration: int, kind: str, opponent: str, loadout: str, opponent_loadout: str, outcome: str,
              ticks: int, site: str, finish: str) -> None:

        self.ground.setdefault(site, Ground()).add(outcome, finish)

        if kind == "train":
            record = self.training.setdefault(opponent, [0.0, 0.0])
            record[0] += outcome == "win"
            record[1] += 1.0
            self.train_windows.add(opponent, outcome)
            self.train_loadouts.add(loadout, outcome)
            return

        self.ratings.game(checkpoint_name(iteration), "checkpoint", opponent, self._kind(opponent), SCORES[outcome])
        self.rated += 1

        self.eval_windows.add(opponent, outcome)
        self.eval_loadouts.add(loadout, outcome)
        self.evaluations.setdefault((iteration, opponent), Tally()).add(outcome)

    def _kind(self, name: str) -> str:
        if checkpoint_iteration(name) is not None:
            return "checkpoint"

        if name == SCRIPTED:
            return "scripted"

        # A rung of the ladder is the same kind of thing as the opponent it is a rung of: 2x_zombie(hard) is still a squad.
        return self.kinds.get(base(name), "mob")

    def _roster(self) -> list[str]:
        """The mobs and the scripted fighter the workers field, as they last said, with what kind each is and the largest
        share of the training fights it may take. A build that says neither leaves both at what they always were: a mob,
        with no cap on it."""

        file = self.folder / "roster.csv"

        try:
            lines = file.read_text(encoding="utf-8").splitlines()[1:]

        except OSError:
            return []

        names = []

        for line in lines:
            if not line.strip():
                continue

            parts = [part.strip() for part in line.split(",")]
            names.append(parts[0])
            self.kinds[parts[0]] = parts[1] if len(parts) > 1 and parts[1] else "mob"

            try:
                self.caps[parts[0]] = float(parts[2]) if len(parts) > 2 and parts[2] else 1.0

            except ValueError:
                self.caps[parts[0]] = 1.0

        return names

    def _ladder(self, roster: list[str]) -> list[str]:
        """The rungs of the difficulty ladder the agent has earned, opening any that its evaluated record now calls for.

        An opponent starts on normal. Once its evaluated win rate passes league_hard_at there is little left to learn from
        it as it stands, so the hard rung opens beside it; while the rate is still under league_easy_below there is nothing
        to learn from it yet, so the easy one does. Either needs league_rung_fights evaluation fights behind it, or one
        lucky handful would open a rung.

        A rung stays open once it is open. Closing one again would make its rating a moving target, and the agent would
        stop having to hold what it won; and the fights cost little, since matchmaking sends them where the fight is even
        and a rung the agent walks over is weighed down to the floor like any other opponent.
        """

        for name in roster:
            tally = self.eval_windows.tally(name)

            if tally.fights < self.config.league_rung_fights:
                continue

            rate = tally.wins / tally.fights
            rung = "(hard)" if rate >= self.config.league_hard_at else "(easy)" if rate <= self.config.league_easy_below else None

            if rung is None or name + rung in self.rungs:
                continue

            self.rungs.add(name + rung)

            logger.info(
                "%s is met on %s from now on: %.0f%% of the last %d evaluation fights on normal",
                name, rung.strip("()"), 100.0 * rate, tally.fights,
            )

        # Only of the opponents the workers still field, so a run told to field fewer does not meet the rest of a rung.
        return sorted(name for name in self.rungs if base(name) in roster)

    def _pool(self, iteration: int) -> list[str]:
        """The checkpoints the agent meets, of those whose weights are still on disk; the trainer keeps every one."""

        every = max(1, self.config.checkpoint_every)
        checkpoints = [number for number in range(0, iteration + 1, every) if self.run.weights_file(number).is_file()]

        return [checkpoint_name(number) for number in pool(checkpoints, self.config.league_pool, self.config.league_recent)]

    def _matchmake(self, roster: list[str], checkpoints: list[str]) -> None:
        """Each opponent's chance, from its record and its rating, and from those its share of the training fights."""

        learner = self.ratings.newest_checkpoint()
        learner_rating = learner.rating if learner is not None else self.config.league_initial

        chances = {}

        for name in roster + checkpoints:
            opponent = self.ratings.ensure(name, self._kind(name))
            wins, fights = self.training.get(name, [0.0, 0.0])
            guess = expected(learner_rating, opponent.rating)
            chances[name] = win_chance(wins, fights, guess, self.config.league_prior)

        self_play = self.config.league_self_play if checkpoints else 0.0

        # A cap belongs to the opponent, so every rung of it is held to the same share: a hard warden is no more worth
        # training against than a normal one.
        caps = {name: self.caps.get(base(name), 1.0) for name in roster}

        fixed = shares({name: chances[name] for name in roster}, self.config.league_floor, caps)
        frozen = shares({name: chances[name] for name in checkpoints}, self.config.league_floor)

        self.chances = chances
        self.shares = {name: (1.0 - self_play) * share for name, share in fixed.items()}
        self.shares.update({name: self_play * share for name, share in frozen.items()})

    # -------------------------------------------------------------------------------------------------------------

    def _write(self, roster: list[str]) -> None:
        ordered = sorted(self.shares, key=lambda name: -self.shares[name])

        self._table("matchmaking.csv", "opponent,share,chance,rating,fights", [
            f"{name},{self.shares[name]:.5f},{self.chances[name]:.4f},{self.ratings.rating(name):.1f},"
            f"{self.training.get(name, [0.0, 0.0])[1]:.1f}"
            for name in ordered
        ])

        players = sorted(self.ratings.players.values(), key=lambda player: -player.rating)

        self._table("ratings.csv", "player,kind,rating,games,wins,losses,draws", [
            f"{player.name},{player.kind},{player.rating:.1f},{player.games},{player.wins},{player.losses},{player.draws}"
            for player in players
        ])

        # Everyone the agent meets now, and anyone it has met in an evaluation lately.
        opponents = list(ordered) + [name for name in self.eval_windows.recent if name not in self.shares]

        self._table("opponents.csv", "opponent,kind,rating,share," + ",".join(
            f"{side}_{column}" for side in ("eval", "train") for column in ("fights", "wins", "losses", "timeouts", "draws")), [
            f"{name},{self._kind(name)},{self.ratings.rating(name):.1f},{self.shares.get(name, 0.0):.5f},"
            + ",".join(str(value) for value in self.eval_windows.tally(name).values() + self.train_windows.tally(name).values())
            for name in opponents
        ])

        loadouts = sorted(set(self.eval_loadouts.recent) | set(self.train_loadouts.recent))

        self._table("loadouts.csv", "loadout," + ",".join(
            f"{side}_{column}" for side in ("eval", "train") for column in ("fights", "wins", "losses", "timeouts", "draws")), [
            f"{name}," + ",".join(str(value) for value in self.eval_loadouts.tally(name).values() + self.train_loadouts.tally(name).values())
            for name in loadouts
        ])

        self._table("ground.csv", "site,fights,wins,by_agent,by_terrain,by_side", [
            f"{site}," + ",".join(str(value) for value in tally.values())
            for site, tally in sorted(self.ground.items())
        ])

        self._table("evaluations.csv", "iteration,opponent,fights,wins,losses,timeouts,draws", [
            f"{iteration},{opponent}," + ",".join(str(value) for value in tally.values())
            for (iteration, opponent), tally in sorted(self.evaluations.items())
        ])

    def _table(self, name: str, header: str, lines: list[str]) -> None:
        target = self.folder / name
        temporary = target.with_suffix(".tmp")
        temporary.write_text("\n".join([header] + lines) + "\n", encoding="utf-8")

        # Every worker reads the matchmaking at the start of each fight, and league.ps1 the rest whenever it is run.
        files.replace(temporary, target)

    def _report(self, iteration: int) -> None:
        learner = self.ratings.newest_checkpoint()

        if learner is None or not self.shares:
            return

        most = sorted(self.shares, key=lambda name: -self.shares[name])[:3]

        logger.info(
            "%s rated %.0f over %d fights; %d rated fights in all; most training against %s",
            learner.name, learner.rating, learner.games, self.rated,
            ", ".join(f"{name} {100 * self.shares[name]:.0f}%" for name in most),
        )

        # What the hazard sites are for: the ground finishing the fight, and only on the ground that has any.
        using = [
            f"{site} {100.0 * tally.by_terrain / max(1, tally.wins):.0f}% of {tally.wins} wins"
            for site, tally in sorted(self.ground.items()) if tally.by_terrain > 0
        ]

        if using:
            logger.info("the ground finished the opponent in %s", "; ".join(using))

    # -------------------------------------------------------------------------------------------------------------

    def _save(self) -> None:
        state = {
            "offsets": self.offsets,
            "rated": self.rated,
            "rungs": sorted(self.rungs),
            "ground": {site: tally.values() for site, tally in self.ground.items()},
            "players": {
                player.name: [player.kind, player.rating, player.games, player.wins, player.losses, player.draws]
                for player in self.ratings.players.values()
            },
            "training": self.training,
            "windows": {
                "eval": self.eval_windows.save(),
                "train": self.train_windows.save(),
                "eval_loadouts": self.eval_loadouts.save(),
                "train_loadouts": self.train_loadouts.save(),
            },
            "evaluations": {f"{iteration}|{opponent}": tally.values() for (iteration, opponent), tally in self.evaluations.items()},
        }

        temporary = self.state_file.with_suffix(".tmp")
        temporary.write_text(json.dumps(state), encoding="utf-8")
        files.replace(temporary, self.state_file)

    def _resume(self) -> None:
        """Picks a resumed run's league up where it was. Without a state, whatever the workers wrote is read from the
        start, which rates it all again in the same order."""

        if not self.state_file.is_file():
            return

        state = json.loads(self.state_file.read_text(encoding="utf-8"))

        self.offsets = {name: int(offset) for name, offset in state.get("offsets", {}).items()}
        self.rated = int(state.get("rated", 0))
        self.rungs = set(state.get("rungs", []))
        self.ground = {site: Ground(*values) for site, values in state.get("ground", {}).items()}

        for name, (kind, rating, games, wins, losses, draws) in state.get("players", {}).items():
            self.ratings.players[name] = Player(name, kind, float(rating), int(games), int(wins), int(losses), int(draws))

        self.training = {name: [float(wins), float(fights)] for name, (wins, fights) in state.get("training", {}).items()}

        windows = state.get("windows", {})
        self.eval_windows.load(windows.get("eval", {}))
        self.train_windows.load(windows.get("train", {}))
        self.eval_loadouts.load(windows.get("eval_loadouts", {}))
        self.train_loadouts.load(windows.get("train_loadouts", {}))

        for key, values in state.get("evaluations", {}).items():
            iteration, opponent = key.split("|", 1)
            self.evaluations[(int(iteration), opponent)] = Tally(*values)

        logger.info("carrying on from %d rated fights over %d players, %d rungs of the ladder open", self.rated,
                    len(self.ratings.players), len(self.rungs))

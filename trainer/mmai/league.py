"""The league: who a league run's agent fights, how every player in it rates, and the numbers behind the tier list.

A league run's agent fights nearly every hostile mob there is, the scripted fighter, published networks the run names, and
frozen checkpoints of itself,
with a different loadout from one fight to the next; the game's gametest/league has who and how. The workers write down
every fight. This side reads them, keeps an Elo rating for every player, decides who the agent meets next, and writes
all of it down:

    runs/RUN/league/roster.csv          written by the workers: the mobs, the scripted fighter and the models they field, and the cap on each
    runs/RUN/league/results/wNN.csv     appended by each worker, a line a fight; the columns are in the game's league/League#write
    runs/RUN/league/matchmaking.csv     written here: each opponent's share of the training fights, and why
    runs/RUN/league/pairs.csv           written here: each loadout against each opponent, its share and the record behind it
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

Who is rated. Every player is a fixed policy: a kind of mob, a squad of them, a rung of the ladder, a fight with a crowd of
monsters standing about it, a pack of the mob itself, the scripted fighter, a published network the run fields, a checkpoint on
its most likely action. A crowd is what a real world's night puts in the agent's view and what no league fight used to, and a
share of the fights against a mob or a squad now has one: zombie+3_idle, rated separately for the same reason a rung is, so the
plain zombie rating keeps meaning what it meant in every run before this one. A pack is the other shape a real world has —
several of the same mob that all come at once, zombie+3_pack being a zombie and three more of it — and a smaller share of the
fights against one mob field one. Nothing here matchmakes over either kind of name, since they are not in the roster the
workers hand over; they arrive in the results and take a row in the tier list. See the game's league/Bystanders and
league/HostilePacks.
The agent in training is none of those, since it samples and changes every iteration, so only evaluation fights are
rated: a checkpoint on its most likely action, against an opponent drawn evenly from everyone. A fight scores one for a
win, nothing for a loss, and a half when neither killed the other, on time or because a creeper blew itself up. Both
sides move by K times how far the score was from what their ratings expected. The scripted fighter is held where
everyone starts, so the scale means the same from run to run: 1500 fights like the scripted fighter, and 400 points
above a player wins ten fights to its one.

Published networks. A run can name networks published under models/ and field them as players (the game's
league/Published; scripts\\train.ps1 -LeagueModels). Nothing here treats one specially: the workers write it into
roster.csv as a player of kind "model", it is weighed with the mobs and the scripted fighter rather than in the self-play
share, since it never learns, and its rating starts where everyone's does and moves on its own fights. It is deliberately
not a second anchor: one fixed point is what makes the scale mean the same everywhere, and a second would assert the
distance between the two of them instead of measuring it. That is the whole trick to putting two lineages on one tier
list — both runs rate the same fixed network against the same anchor, and comparing what each says it is worth is also the
check that the two scales have not drifted apart.

Matchmaking. The training fights go where there is the most to learn: to fights the agent wins about half the time. What
is drawn is a **pairing** — one loadout against one opponent — and not an opponent to be handed a loadout afterwards; see
`pairs` below for why that matters and what it costs. Its chance in each pairing is estimated from its recent training
fights, which fade an iteration at a time, and filled in from the opponent's chance and the ratings while there are few; a
pairing's weight is that chance times its complement, which peaks at an even fight. A floor of the fights is spread evenly,
so a pairing the agent always wins, or never does, still comes round. Self play gets a share of its own, weighed the same
way over a pool of checkpoints: the newest few, and the rest spaced out over the run so far, so the agent has to keep
beating what it used to be as well as what it is.

Pairs. The loadout and the opponent used to be drawn independently, the trainer weighing the opponent and the worker
handing the agent whichever loadout came up. That spends a run's fights in the wrong places. A bow was drawn against a
creeper it should kite exactly as often as against a ghast it cannot reach, so the gradient that reaches the drawing of a
bow was an average over the matchups where a bow is the answer and the matchups where it is hopeless: measured on a league
run, the ranged loadouts won about 40% of their fights and the melee ones far more. Drawing the pairing puts the fights
where a loadout can still learn something, and hands a loadout that is losing more of the matchups it is losing.

The pair table is loadouts times opponents, and both are small: ten loadouts against 61 mobs, squads and jockeys plus the
scripted fighter is 620 pairings at the start of a run, 1,840 once every rung of the difficulty ladder is open, and 80 more for the
self-play pool. That is thin ground for a per-pair win rate. The faded record holds about 1/(1 - league_decay) = 50
iterations of fights, which at rollout_steps and a few hundred ticks a fight is a couple of thousand fights all told, so a
pairing has single figures of them and plenty have none at all. **So a pairing's chance is never asked to stand on its
own**: it is the pairing's own record over a prior worth league_prior fights, and that prior is the opponent's chance moved
by how the loadout does over all of its fights, which is a tenth of the run's and dense enough to mean something (see
`pair_guess`). With nothing recorded anywhere the prior is exactly the opponent's chance, so the draw starts out as what it
always was — the loadout even within the opponent — and only separates as the fights say it should.

None of this costs coverage. The floor is spread over the pairings rather than over the opponents, which comes to the same
share per pairing as before: an opponent's even floor was already being split ten ways by the even loadout draw.

One pairing is not in the table at all: **a loadout that carries nothing to shoot with against a flyer that never comes
within reach**, the ghast and the phantom. There is nothing to win there and nothing to lose — a deflected fireball only
kills a ghast for a real player, and a phantom swoops past and climbs away again — so every one of those fights is 2,400
ticks of timeout, dragging a rating with a number that means nothing and spending a worker's minute on a question with one
answer. It gets no share, which also means the frontier probe never reaches it, since the probe only holds down a share that
exists. The workers say which rows those are in the fourth column of roster.csv, `reach`: `melee` on a loadout that carries
no shot, `unreachable` on an opponent nothing but a shot can touch. A build too old to write the column bars nothing, which
is what the league did before. The game side refuses the same pairing in its own draw, evaluation included; see the game's
league/Loadouts#fights. **It changes what a checkpoint's evaluated win rate is averaged over**, so it belongs at a run
boundary; see docs/training.md.

Only the training fights are paired. An evaluation fight still draws its opponent evenly and its loadout evenly, because
every rating in the league is measured on those: pairing them would move the scale under a run that is already going.

An opponent may also come with a cap on its share, which the workers write into roster.csv beside it, since the game is
what knows: the warden cannot be beaten at all, the reward has no way to pay for getting away alive from one, and the
floor alone would still hand it its even share of the fights. A cap holds it down to a fraction of that and gives what it
gave up to the opponents there is something to learn from. A cap belongs to the opponent and not to one pairing with it, so
it holds down everything the agent might carry against it at once: what is capped at two thousandths is two thousandths of
the fights over all ten loadouts, not two thousandths each. Nothing caps the evaluation draw, so a capped opponent is
rated on as many fights as any other.

When a league run is done is not decided here. A checkpoint's fights against the mobs and the scripted fighter also go
into the run's evaluation, drawn evenly across them, and evaluate.py keeps the best and stops the run once ten judged
checkpoints in a row have failed to beat it. Ratings would make a worse yardstick for that: they are relative, and a
rating that holds still can mean everything around it got better too.
"""

from __future__ import annotations

import json
import re
from collections import deque
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from . import files, log
from .run import RunDirectory

if TYPE_CHECKING:
    from .ppo import Config

logger = log.get("league")

CHECKPOINT = "iteration-"
SCRIPTED = "scripted"

# The kind a row of roster.csv carries when it is one of the loadouts the agent is armed with rather than an opponent; the
# game writes both in the one file, see the game's league/League#writeRoster.
LOADOUT = "loadout"

# What the fourth column of roster.csv, `reach`, says about a row: that this loadout carries nothing to shoot with, or that
# nothing but a shot can ever touch this opponent. The two of them together are the one pairing that is never drawn; every
# other row says "-", and a build too old to write the column says nothing and bars nothing.
MELEE = "melee"
UNREACHABLE = "unreachable"

# The faded fights below which a training record is forgotten: a thousandth of a fight, which league_decay reaches about 340
# iterations after the last one, and which the prior outweighs ten thousand to one.
FADED = 1.0e-3

# The rungs of the difficulty ladder either side of normal, as the game writes them on the end of an opponent's name; see
# the gametest's league/Opposition. Normal has no suffix, so every name the league had before the ladder means what it did.
RUNGS = ("(hard)", "(easy)")

# The crowd of monsters a share of league fights stands about it, as the game writes it on the end of an opponent's name:
# zombie+3_idle, 2x_zombie+3_idle, zombie(hard)+3_idle. A fight with a crowd in it is a player of its own, so the plain
# zombie rating keeps meaning what it meant in every run before this one, and a digit followed by _idle cannot be a squad
# member's name, so this cannot swallow one. See the gametest's league/Bystanders.
IDLE = re.compile(r"\+([1-9])_idle$")

# The pack of the same mob a smaller share of the fights against one of it fields instead, all of them fighting, as the game
# writes it on the end of the name: zombie+3_pack is a zombie and three more of it, zombie(hard)+5_pack is six hard ones. A
# player of its own for the same reason a crowd is, and read off the name the same way — a digit followed by _pack cannot be a
# squad member's name. See the gametest's league/HostilePacks.
PACK = re.compile(r"\+([1-9])_pack$")

# What the game writes on the end of an opponent's name for a fight that is not the plain one against it: a crowd standing
# about it, or a pack of it. Both come off before the rung, since the game writes them outside it.
VARIATIONS = (IDLE, PACK)

# The kinds of player that have rungs at all: a rung is how hard the mobs on one side spawn, so only they and the squads of
# them have one. The scripted fighter and a published network are fixed policies with nothing to turn up.
RUNGED = ("mob", "squad")

# What a fight is worth to the side it is scored from, and the only list of the outcomes: the game writes these four words
# into its results and nothing here is keyed by anything else.
SCORES = {"win": 1.0, "loss": 0.0, "timeout": 0.5, "draw": 0.5}

# One letter per outcome in the saved windows, which keeps a state of a few thousand fights small.
CODES = {"win": "w", "loss": "l", "timeout": "t", "draw": "d"}
NAMES = {code: name for name, code in CODES.items()}


def checkpoint_name(iteration: int) -> str:
    return f"{CHECKPOINT}{iteration:06d}"


def checkpoint_iteration(name: str) -> int | None:
    return int(name[len(CHECKPOINT) :]) if name.startswith(CHECKPOINT) and name[len(CHECKPOINT) :].isdigit() else None


def base(name: str) -> str:
    """The opponent a name is a variation on: zombie for zombie(hard), for zombie+3_idle, for zombie+3_pack and for
    zombie(hard)+3_idle, and the name itself for anything else.

    What this is for is everything a variation inherits from the opponent it is one of: what kind of thing it is, and the cap
    on its share of the training fights. A crowd of bystanders and a pack of the mob itself come off first, since the game
    writes either outside the rung; a fight is never both, so at most one of them is ever there to come off.
    """

    for variation in VARIATIONS:
        name = variation.sub("", name)

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


# How far from certain a chance is held before it is turned into odds; see pair_guess.
MARGIN = 0.01


def odds(chance: float) -> float:
    """A chance as odds, held a hundredth away from either end: odds of nothing and of a certainty divide by zero."""

    held = min(max(chance, MARGIN), 1.0 - MARGIN)

    return held / (1.0 - held)


def pair_guess(opponent: float, loadout: float, average: float) -> float:
    """What one loadout is expected to do against one opponent before the pairing's own fights can say.

    The opponent's chance, moved by how that loadout does over all its fights against how the loadouts do on average, in
    odds: a loadout whose odds are half the average's has half the average's odds here too. It is the simplest guess that
    carries the one thing about a loadout that is measured densely enough to trust — a loadout is in one fight in ten, a
    pairing in one in five hundred — and with nothing recorded it gives back the opponent's chance unchanged, which is the
    draw as it was before pairings.

    Holding the odds away from certainty also keeps the guess from claiming more than it knows: a loadout that has won every
    fight it has had so far does not thereby beat the warden.
    """

    combined = odds(opponent) * odds(loadout) / odds(average)

    return combined / (1.0 + combined)


def shares(chances: dict, floor: float, caps: dict[str, float] | None = None,
           frontier: float = 0.0, probe: float = 1.0, groups: dict | None = None) -> dict:
    """Each opponent's, or each pairing's, share of a group's fights, adding up to one.

    Weighed by chance times its complement, which is largest for an even fight and nothing for a certain one, and a
    floor so every one of them keeps coming round however the fights go. Caps, where the workers named any, hold an
    opponent down to at most its own share of the fights.

    The keys are opponents, or pairings of a loadout and an opponent; nothing here cares which, since the rule is the same
    either way. `groups` says what a key's cap belongs to when that is not the key itself: a cap is the opponent's, so every
    pairing with it is held under one cap between them rather than each under a cap of its own.

    The floor is where a run's fights quietly go. Spread evenly it is the same share for an even fight and for one the
    agent has never once won, and with a hundred opponents on the roster and sixteen of them hopeless that was **nine per
    cent of a run's fights spent losing every time**: a fight lost every time carries almost nothing to learn from, since
    there is no version of it the agent got further in. So anything below `frontier` chance keeps only `probe` of the
    floor — enough to be tried again as the agent gets better, since one that was hopeless in the first thousand
    iterations may not be in the ten thousandth — and what it gives up goes to the fights that are close.
    """

    if not chances:
        return {}

    weights = {name: chance * (1.0 - chance) for name, chance in chances.items()}
    total = sum(weights.values())
    even = 1.0 / len(chances)

    if total <= 0.0:
        return capped({name: even for name in chances}, caps or {}, groups)

    # The floor, as much of it as each opponent has earned, put back to adding up to one so that holding the hopeless down
    # hands their share to the rest rather than losing it.
    held = {name: even * (1.0 if chance >= frontier else probe) for name, chance in chances.items()}
    standing = sum(held.values())
    held = {name: share / standing for name, share in held.items()} if standing > 0.0 else held

    return capped({name: (1.0 - floor) * weight / total + floor * held[name]
                   for name, weight in weights.items()}, caps or {}, groups)


def capped(group: dict, caps: dict[str, float], groups: dict | None = None) -> dict:
    """The same shares with every cap honoured, what the capped ones gave up going to the rest in proportion.

    A cap is on everything under one key of `groups` together — every pairing with the warden, not each of them — so a
    group over its cap is scaled down to it and keeps the balance between its own members. Spreading what one gave up can
    push another over its own cap, so this goes round again until nothing is over, which takes at most one pass per group.
    If every one of them ends up capped the shares add up to less than one, which costs nothing: the workers draw from the
    shares in proportion, whatever they come to.
    """

    result = dict(group)
    held: set = set()
    key = (lambda name: name) if groups is None else (lambda name: groups.get(name, name))

    for _ in range(len(result)):
        totals: dict[str, float] = {}

        for name, share in result.items():
            if name not in held:
                totals[key(name)] = totals.get(key(name), 0.0) + share

        over = [name for name, share in totals.items() if share > caps.get(name, 1.0)]

        if not over:
            return result

        spare = 0.0

        for name in over:
            spare += totals[name] - caps[name]
            shrink = caps[name] / totals[name] if totals[name] > 0.0 else 0.0

            for member in [one for one in result if key(one) == name and one not in held]:
                result[member] *= shrink
                held.add(member)

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

        # The same record with each loadout, and the same in each pairing of a loadout and an opponent, which is the unit
        # the training fights are drawn in; all three fade together. A pairing's own record is thin by construction, so the
        # other two are what fills it in: see pair_guess and the `pairs` paragraph above.
        self.training_loadouts: dict[str, list[float]] = {}
        self.training_pairs: dict[tuple[str, str], list[float]] = {}

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

        # What the workers actually draw from: each pairing's share of the training fights, and the chance behind it. Empty
        # until the workers have said which loadouts they field, and then the shares above are these added up per opponent,
        # which is what the tier list and the tables read.
        self.pair_shares: dict[tuple[str, str], float] = {}
        self.pair_chances: dict[tuple[str, str], float] = {}

        # What the workers say about each opponent they field: what kind of thing it is, and the largest share of the
        # training fights it may take, and which loadouts the agent is armed with. Read afresh from roster.csv every
        # iteration, so nothing of it is saved.
        self.kinds: dict[str, str] = {}
        self.caps: dict[str, float] = {}
        self.loadouts: list[str] = []

        # The one pairing the workers will not field, as the `reach` column of roster.csv names its two halves: the loadouts
        # that carry nothing to shoot with, and the opponents nothing but a shot can ever reach. Read afresh with the rest.
        self.melee: set[str] = set()
        self.unreachable: set[str] = set()

        # The rungs of the difficulty ladder opened so far, by full name: zombie(hard), 2x_zombie(easy). A ratchet, so it
        # is saved with the rest and a resumed run does not have to earn them again.
        self.rungs: set[str] = set()

        # How the fights on each kind of ground went, and how often the ground itself finished the other side.
        self.ground: dict[str, Ground] = {}

        self._resume()

    # -------------------------------------------------------------------------------------------------------------

    def update(self, iteration: int) -> None:
        """Once per training iteration: takes in what the workers wrote, and writes everything that follows from it."""

        for records in (self.training, self.training_loadouts, self.training_pairs):
            for record in records.values():
                record[0] *= self.config.league_decay
                record[1] *= self.config.league_decay

            # What has faded to nothing is dropped rather than carried for the rest of the run. A record this small says
            # exactly what no record says, since the prior swamps it, and without this the saved state would keep a row for
            # every checkpoint the run ever met times every loadout it ever carried.
            for name in [name for name, record in records.items() if record[1] < FADED]:
                del records[name]

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

            # Only the fields this side uses are read, by position, and the record grows to the right: the eighth, what the
            # agent died of, and everything past the tenth — what the agent held, its swaps, uses and shots, and which replay
            # is of the fight — are for reading fights back later, in the viewer. The ninth and tenth, what ground the fight
            # was on and what finished the other side, are newer than some runs, so a line without them still reads, and so
            # does one longer than anything this build knows about.
            for line in data[:end].decode("utf-8").splitlines():
                parts = line.strip().split(",")

                if len(parts) < 7 or parts[5] not in SCORES or parts[1] not in ("train", "eval"):
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
            # The pairing is the unit the fight was drawn in, so it is the unit the record is kept in; the opponent's and the
            # loadout's own records are the same fights added up the two other ways, and are what a thin pairing leans on.
            for record in (self.training.setdefault(opponent, [0.0, 0.0]),
                           self.training_loadouts.setdefault(loadout, [0.0, 0.0]),
                           self.training_pairs.setdefault((loadout, opponent), [0.0, 0.0])):
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
        with no cap on it.

        The loadouts the agent is armed with come in the same file, under the kind `loadout`, because the game is what knows
        which of them a run fields (`-PleagueLoadouts`) and a pairing cannot be drawn without both halves. A build too old to
        say leaves the list empty, and then the shares stay what they always were, one per opponent.

        The fourth column, `reach`, is the other thing only the game knows: which loadouts carry nothing that shoots and
        which opponents nothing but a shot can ever touch, so that the two are never paired. A build too old to write it
        leaves both sets empty and nothing is barred.
        """

        file = self.folder / "roster.csv"

        try:
            lines = file.read_text(encoding="utf-8").splitlines()[1:]

        except OSError:
            return []

        names = []
        loadouts = []

        for line in lines:
            if not line.strip():
                continue

            parts = [part.strip() for part in line.split(",")]
            kind = parts[1] if len(parts) > 1 and parts[1] else "mob"
            reach = parts[3] if len(parts) > 3 else ""

            if kind == LOADOUT:
                loadouts.append(parts[0])
                self.melee.discard(parts[0])

                if reach == MELEE:
                    self.melee.add(parts[0])

                continue

            names.append(parts[0])
            self.kinds[parts[0]] = kind
            self.unreachable.discard(parts[0])

            if reach == UNREACHABLE:
                self.unreachable.add(parts[0])

            try:
                self.caps[parts[0]] = float(parts[2]) if len(parts) > 2 and parts[2] else 1.0

            except ValueError:
                self.caps[parts[0]] = 1.0

        self.loadouts = loadouts

        return names

    def _ladder(self, roster: list[str]) -> list[str]:
        """The rungs of the difficulty ladder the agent has earned, opening any that its evaluated record now calls for.

        An opponent starts on normal. Once its evaluated win rate passes league_hard_at there is little left to learn from
        it as it stands, so the hard rung opens beside it; while the rate is still under league_easy_below there is nothing
        to learn from it yet, so the easy one does. Either needs league_rung_fights evaluation fights behind it, or one
        lucky handful would open a rung.

        Only a mob or a squad has rungs. A rung is the DifficultyInstance a mob's own finalizeSpawn is handed, and neither
        the scripted fighter nor a published network has one: there is no such thing as a hard scripted fighter, and a run
        that opened one would spend a share of its fights on a name its workers cannot field at all. One opened before that
        was noticed is dropped, along with the player it entered, if that player never fought.

        A rung stays open once it is open. Closing one again would make its rating a moving target, and the agent would
        stop having to hold what it won; and the fights cost little, since matchmaking sends them where the fight is even
        and a rung the agent walks over is weighed down to the floor like any other opponent.
        """

        for name in roster:
            if self._kind(name) not in RUNGED:
                continue

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

        wrong = {name for name in self.rungs if self._kind(base(name)) not in RUNGED}

        for name in wrong:
            player = self.ratings.players.get(name)

            # Never rated, so nothing is lost by forgetting it; one that somehow did fight keeps its row, since its fights
            # really happened.
            if player is not None and player.games == 0:
                del self.ratings.players[name]

        self.rungs -= wrong

        # Only of the opponents the workers still field, so a run told to field fewer does not meet the rest of a rung.
        return sorted(name for name in self.rungs if base(name) in roster)

    def _pool(self, iteration: int) -> list[str]:
        """The checkpoints the agent meets, of those whose weights are still on disk; the trainer keeps every one."""

        every = max(1, self.config.checkpoint_every)
        checkpoints = [number for number in range(0, iteration + 1, every) if self.run.weights_file(number).is_file()]

        return [checkpoint_name(number) for number in pool(checkpoints, self.config.league_pool, self.config.league_recent)]

    def _matchmake(self, roster: list[str], checkpoints: list[str]) -> None:
        """Each pairing's chance, from its own record, the opponent's and the loadout's, and from those its share of the
        training fights; and the same added up per opponent, which is what the tables show.

        A build that has not said which loadouts it fields gets what the league always did, a share per opponent: the
        workers draw the loadout themselves then, so there is nothing here to say about it.
        """

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

        pairs = self._pairs(chances)
        self.chances = chances
        self.pair_chances = pairs

        if not pairs:
            fixed = shares({name: chances[name] for name in roster}, self.config.league_floor, caps,
                           self.config.league_frontier, self.config.league_probe)

            # The pool of its own past selves is left alone: every one of those is a fight worth having by construction,
            # since it was the agent not long ago.
            frozen = shares({name: chances[name] for name in checkpoints}, self.config.league_floor)

            self.pair_shares = {}
            self.shares = {name: (1.0 - self_play) * share for name, share in fixed.items()}
            self.shares.update({name: self_play * share for name, share in frozen.items()})
            return

        against = set(roster)

        fixed = shares({pair: chance for pair, chance in pairs.items() if pair[1] in against},
                       self.config.league_floor, caps, self.config.league_frontier, self.config.league_probe,
                       groups={pair: pair[1] for pair in pairs})

        frozen = shares({pair: chance for pair, chance in pairs.items() if pair[1] not in against},
                        self.config.league_floor)

        self.pair_shares = {pair: (1.0 - self_play) * share for pair, share in fixed.items()}
        self.pair_shares.update({pair: self_play * share for pair, share in frozen.items()})

        # What every table and the tier list read, and what a worker too old to draw pairings falls back on: the pairings
        # with one opponent, added up.
        self.shares = {}

        for (_, opponent), share in self.pair_shares.items():
            self.shares[opponent] = self.shares.get(opponent, 0.0) + share

    def _pairs(self, chances: dict[str, float]) -> dict[tuple[str, str], float]:
        """The agent's chance in every pairing of a loadout it carries and an opponent it meets, or nothing at all until the
        workers have said which loadouts they field.

        A pairing's own record is thin — a few thousand fights spread over hundreds of pairings — so it is counted against a
        prior of `league_prior` fights of the guess, and the guess is the opponent's chance moved by how this loadout does
        over all of its fights (`pair_guess`). The loadout's own chance is shrunk towards the average the same way, so a
        loadout with a handful of fights does not drag a whole column of the table about.

        One pairing never enters the table at all, so it gets no share and the frontier probe never reaches it: a loadout
        that carries nothing to shoot with against a flyer that never comes within reach. See `pairable`.
        """

        if not self.loadouts:
            return {}

        wins = sum(record[0] for record in self.training_loadouts.values())
        fights = sum(record[1] for record in self.training_loadouts.values())
        average = win_chance(wins, fights, 0.5, self.config.league_prior)

        loadouts = {}

        for loadout in self.loadouts:
            carried = self.training_loadouts.get(loadout, [0.0, 0.0])
            loadouts[loadout] = win_chance(carried[0], carried[1], average, self.config.league_prior)

        pairs = {}

        for loadout in self.loadouts:
            for opponent, chance in chances.items():
                if not self.pairable(loadout, opponent):
                    continue

                record = self.training_pairs.get((loadout, opponent), [0.0, 0.0])
                guess = pair_guess(chance, loadouts[loadout], average)
                pairs[(loadout, opponent)] = win_chance(record[0], record[1], guess, self.config.league_prior)

        return pairs

    def pairable(self, loadout: str, opponent: str) -> bool:
        """Whether a fight of that loadout against that opponent is one the workers would ever field.

        One rule: a loadout that carries nothing to shoot with is never drawn against something nothing but a shot can
        reach, a ghast or a phantom, on their own or on a squad with one, on any rung. Both halves come from the `reach`
        column of roster.csv, since the game is what knows; the rung comes off the name first, because how hard a mob spawns
        has nothing to do with whether a sword can get at it.

        A build too old to write that column leaves both sets empty and nothing is barred, which is what the league did
        before the rule.
        """

        return not (loadout in self.melee and base(opponent) in self.unreachable)

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

        self._pair_table()

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

    def _pair_table(self) -> None:
        """pairs.csv: the pairing the workers draw a training fight in, largest share first, with the chance behind it and
        the faded record that chance leans on. The record is of the training fights only, since an evaluation draws evenly
        and a pairing gets about one of its fights per checkpoint, which would say nothing.

        A run whose workers do not report their loadouts has no pair table, and the file is taken away rather than left to
        go stale: the workers read it if it is there, and a table from another build's loadouts is worse than none.
        """

        target = self.folder / "pairs.csv"

        if not self.pair_shares:
            target.unlink(missing_ok=True)
            return

        ordered = sorted(self.pair_shares, key=lambda pair: (-self.pair_shares[pair], pair))

        self._table("pairs.csv", "loadout,opponent,share,chance,fights,wins", [
            f"{loadout},{opponent},{self.pair_shares[(loadout, opponent)]:.6f},"
            f"{self.pair_chances[(loadout, opponent)]:.4f},"
            f"{self.training_pairs.get((loadout, opponent), [0.0, 0.0])[1]:.1f},"
            f"{self.training_pairs.get((loadout, opponent), [0.0, 0.0])[0]:.1f}"
            for loadout, opponent in ordered
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

        if self.pair_shares:
            pairs = sorted(self.pair_shares, key=lambda pair: -self.pair_shares[pair])[:3]

            logger.info("the pairings with the most: %s", "; ".join(
                f"{loadout} against {opponent} {100 * self.pair_shares[(loadout, opponent)]:.1f}%"
                for loadout, opponent in pairs))

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
            "training_loadouts": self.training_loadouts,

            # A pairing keyed as loadout|opponent, since json has string keys only and no name on either side has a bar in it.
            "training_pairs": {f"{loadout}|{opponent}": record for (loadout, opponent), record in self.training_pairs.items()},
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

        # A run saved before the fights were drawn in pairings has neither of these, and starts its pairings from the
        # opponents' records it does have, which is where a pairing with nothing behind it starts anyway.
        self.training_loadouts = {name: [float(wins), float(fights)]
                                  for name, (wins, fights) in state.get("training_loadouts", {}).items()}

        for key, (wins, fights) in state.get("training_pairs", {}).items():
            loadout, opponent = key.split("|", 1)
            self.training_pairs[(loadout, opponent)] = [float(wins), float(fights)]

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

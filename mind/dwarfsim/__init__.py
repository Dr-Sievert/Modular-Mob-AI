"""dwarfsim -- a settlement of dwarves with persistent minds and one action selector.

No game engine, no neural net, no dependencies outside the standard library. What it is for is the
shape of the thing: persistent emotional and social state, small skills that propose, one arbitrator
that picks, and a log that says why.

    from dwarfsim import run_sim
    summary = run_sim("runs/run1.jsonl", n_agents=6, ticks=2000, seed=1)
"""

import os

from .log import Recorder
from .obligations import make_ask
from .schema import PLAYER_ID
from .world import World

__all__ = ["World", "Recorder", "run_sim", "run_player_session"]


def _prepare(path):
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)


def run_sim(path, n_agents=6, ticks=2000, seed=1, scenario="default", temperature=None,
            chief=None, scorer=None, profanity_tier=None, profanity_speech=None):
    """Run a settlement and write the JSONL log. Returns the summary object.

    ``chief`` is off unless asked for: ``None`` lets the scenario decide (only ``feud-chief``
    wants one), ``True`` appoints the most respected dwarf, ``False`` forbids it, and an id
    names one.

    ``scorer`` replaces the hand-written weight table with a learned one; see
    :class:`dwarfsim.learn.LearnedScorer`.
    """
    _prepare(path)
    world = World(n_agents=n_agents, seed=seed, scenario=scenario, temperature=temperature,
                  chief=chief, scorer=scorer, profanity_tier=profanity_tier,
                  profanity_speech=profanity_speech)
    rec = Recorder(path, world, ticks)
    for _ in range(ticks):
        rec.record(world, world.step())
        if not world.living():
            break
    return rec.close(world)


#: The player's side of the demonstration below: when it does what, in ticks. The order is given
#: twice cold and three times warm, because one softmax draw is not a demonstration of anything --
#: the arbitrator is stochastic on purpose, and the claim is about which way it leans.
PLAYER_SCRIPT = (
    (60, "order"),      # a stranger gives an order
    (140, "order"),     # and gives it again
    (220, "help"),      # then stands with them at the gate
    (300, "give"),      # then hands over gold
    (380, "promise"),   # then promises something
    (440, "keep"),      # and keeps it
    (560, "order"),     # the same order, word for word, from somebody who has earned it
    (660, "order"),
    (760, "order"),
)

#: The order the player gives, every time. Identical down to the word, cold or warm.
PLAYER_ORDER = {
    "text": "Get to the mine and bring me ore.", "intent": "COMMAND", "topic": "MINE",
    "addressed": "LISTENER", "aggression": 0.3, "valence": 0.0, "urgency": 0.7, "names": [],
}


def run_player_session(path, n_agents=6, ticks=900, seed=1, temperature=None, scorer=None,
                       profanity_tier=None, profanity_speech=None):
    """One settlement, one player, and the same order given twice.

    This is a demonstration, not a scenario: it drives the player's side from the outside, the
    way a keyboard would, through ``World.say`` and the three player doors. The dwarves are not
    told anything about it -- the only difference between the first order and the second is what
    the player did in between, and what that did to trust, gratitude and reputation.
    """
    _prepare(path)
    world = World(n_agents=n_agents, seed=seed, scenario="player", temperature=temperature,
                  scorer=scorer, profanity_tier=profanity_tier,
                  profanity_speech=profanity_speech)
    rec = Recorder(path, world, ticks)
    who = world.agents[0]
    promised = []
    script = dict((t, what) for t, what in PLAYER_SCRIPT)

    for _ in range(ticks):
        step = world.step()
        if not world.living():
            rec.record(world, step)
            break
        if not who.alive:
            who = world.living()[0]
        what = script.get(step["tick"])
        # These append to the same event list the tick is about to be written from.
        if what == "order":
            world.say(PLAYER_ID, who, dict(
                PLAYER_ORDER, ask=make_ask("BRING", item="ore", quantity=1, place="MINE")))
        elif what == "help":
            world.player_help(who, magnitude=1.4)
        elif what == "give":
            world.player_give(who, 5)
        elif what == "promise":
            promised.append(world.player_promise(who, make_ask("GIVE", item="gold", quantity=3)))
        elif what == "keep" and promised:
            world.player_give(who, 3)
            world.fulfil_obligation(promised[-1])
        rec.record(world, step)
    return rec.close(world)

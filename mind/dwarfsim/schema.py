"""The layout contract.

Everything that a future learned scorer would have to agree on lives here and nowhere else: the
places, the name pool, the observation layout, the candidate feature layout, and the stable list of
scoring term names. The Java mod does the same thing in ``brain/schema/ObservationSchema.java``:
constants with offsets, and a size that a weight file can be checked against.

Two flat float vectors:

* **observation** -- what the agent knows this tick, ``OBS_SIZE`` floats, one per agent per tick.
  Mostly the mind vector, plus where it is, what is happening around it, and the shape of what it
  currently wants and owes.
* **candidate features** -- what one proposed action looks like, ``CAND_SIZE`` floats. A candidate
  is a skill one-hot plus the raw (unweighted) value of every scoring term.

The v0 arbitrator scores a candidate as ``sum(weight[skill][term] * term_value)``, which is a linear
model over the candidate feature block. A tiny MLP replaces it with the same interface:
``score(observation, candidate_features) -> float``.
"""

# ---------------------------------------------------------------------------
# World constants
# ---------------------------------------------------------------------------

#: The only names that may be used, matching text/SCHEMA.md so name detection stays exact matching.
NAME_POOL = (
    "Alvis", "Borin", "Brokk", "Brynja", "Dagna", "Dvalin", "Eitri", "Frida",
    "Grimhild", "Gudrun", "Halvar", "Hrolf", "Ingrid", "Kolbrun", "Orm", "Ragna",
    "Runa", "Sindri", "Skadi", "Steinar", "Torvi", "Tova", "Vidar", "Yngvar",
)

#: Graph nodes. Fully connected: one move is one tick, from anywhere to anywhere.
PLACES = ("FORGE", "MINE", "TAVERN", "HALL", "FARM", "GATE")
PLACE_INDEX = {p: i for i, p in enumerate(PLACES)}
N_PLACES = len(PLACES)

#: Inventory slots, in vector order. ``weapon`` is a quality 0..1, the rest are counts.
INVENTORY = ("ore", "gold", "food", "ale", "weapon")
#: What counts as "a lot" of each, for normalising into the vector.
INVENTORY_SCALE = {"ore": 10.0, "gold": 20.0, "food": 10.0, "ale": 10.0, "weapon": 1.0}

MAX_HEALTH = 20.0

#: The speaker id used for a human at the keyboard. Not a dwarf: it never decides, never ticks,
#: and every dwarf carries a relationship row for it that starts neutral like anyone else's.
PLAYER_ID = "player"

# ---------------------------------------------------------------------------
# Episodic memory, goals, obligations
# ---------------------------------------------------------------------------

#: How a memory got into a head. ``HEARD`` also carries who it was heard from.
MEMORY_SOURCES = ("SEEN", "SUFFERED", "HEARD")

#: Memories per dwarf. Over this, the least salient is forgotten.
MEMORY_CAP = 64

#: Stable goal order: the goal block of the observation is one float of strength per kind.
GOAL_KINDS = ("GET_RICH", "AVENGE", "PROTECT", "REPAY", "BEFRIEND", "KEEP_PEACE")
GOAL_INDEX = {g: i for i, g in enumerate(GOAL_KINDS)}
N_GOALS = len(GOAL_KINDS)

#: The structured ask inside an obligation, and inside a parsed utterance's optional ``ask`` field.
ASK_ACTIONS = ("BRING", "GIVE", "GO_TO", "FIGHT", "STOP", "HELP")

#: Obligation lifecycle.
OBLIGATION_STATUS = ("PENDING", "ACCEPTED", "REFUSED", "KEPT", "BROKEN", "EXPIRED")

# ---------------------------------------------------------------------------
# Mind vector: MIND_SIZE floats
# ---------------------------------------------------------------------------

MIND_EMOTIONS = 0        # anger, fear, happiness, grief                          (4)
MIND_NEEDS = 4           # hunger, thirst, fatigue, social                        (4)
MIND_TRAITS = 8          # bravery, greed, temper, sociability, pride, forgiveness (6)
MIND_HEALTH = 14         # health / MAX_HEALTH                                    (1)
MIND_INVENTORY = 15      # ore, gold, food, ale, weapon quality                   (5)
MIND_FOCUS = 20          # FOCUS_SLOTS x FOCUS_STRIDE                             (24)

#: How many other dwarves the mind carries a relationship for in the vector. The same idea as the
#: mod's ten enemy slots: a fixed number of stable slots, filled by salience, padded with zeros.
FOCUS_SLOTS = 4
#: present, trust, respect, hatred, grudge, gratitude -- the running relationship plus the two
#: numbers episodic memory derives about that dwarf.
FOCUS_STRIDE = 6

MIND_SIZE = MIND_FOCUS + FOCUS_SLOTS * FOCUS_STRIDE  # 44

# ---------------------------------------------------------------------------
# Observation vector: OBS_SIZE floats
# ---------------------------------------------------------------------------

OBS_MIND = 0                       # the whole mind vector          (MIND_SIZE)
OBS_PLACE = MIND_SIZE              # one-hot over PLACES            (N_PLACES)
OBS_CROWD = OBS_PLACE + N_PLACES   # living dwarves here / 6        (1)
OBS_MONSTER = OBS_CROWD + 1        # a monster is here              (1)
OBS_MONSTER_HP = OBS_MONSTER + 1   # its health fraction            (1)
OBS_UNDER_ATTACK = OBS_MONSTER_HP + 1   # hit within the last 12 ticks   (1)
OBS_HIT_AGE = OBS_UNDER_ATTACK + 1      # ticks since the last hit / 50  (1)
OBS_ALIVE_FRACTION = OBS_HIT_AGE + 1    # living dwarves / starting count (1)
OBS_CLOCK = OBS_ALIVE_FRACTION + 1      # (tick % 200) / 200, a cheap day cycle (1)
OBS_GOALS = OBS_CLOCK + 1               # strength per GOAL_KINDS        (N_GOALS)
OBS_OBLIGATIONS = OBS_GOALS + N_GOALS   # open owed by me, owed to me, /3 (2)
OBS_MEMORY = OBS_OBLIGATIONS + 2        # memories held / CAP, mean salience (2)
OBS_IS_CHIEF = OBS_MEMORY + 2           # am I the chief (0 when nobody is) (1)
OBS_CHIEF_HERE = OBS_IS_CHIEF + 1       # the chief is standing here     (1)
OBS_CONDITION = OBS_CHIEF_HERE + 1      # the injury block               (CONDITION_SIZE)

#: The compact injury block, written by :meth:`dwarfsim.condition.Condition.block`. Health is
#: already in the mind vector and says how close to dying a dwarf is; this says what is *broken*,
#: which is a different question and the one the arbitrator has to answer to know whether the
#: mine is worth walking to. Eight floats, all 0..1, in this order:
#:
#:   0 pain          every injury's pain summed and softened
#:   1 worst         the severity of the worst single injury
#:   2 count         how many injuries are being carried, / 4
#:   3 broken arm    severity; past ARM_BLOCK no two-handed work and no weapon
#:   4 broken leg    severity; past LEG_BLOCK no fleeing well and no monsters
#:   5 concussion    severity; noisier decisions, less remembered
#:   6 bleeding      severity; health draining until it is rested or eaten off
#:   7 cracked ribs  severity; more fear, less force
#:
#: The three named bones are here and the mild kinds are not, because those three are the ones
#: that change what a dwarf *can do*; the rest reach the scorer through pain and worst.
CONDITION_SIZE = 8

OBS_SIZE = OBS_CONDITION + CONDITION_SIZE  # 77

# ---------------------------------------------------------------------------
# Skills and scoring terms
# ---------------------------------------------------------------------------

#: Stable skill order. Appended to, never reordered: a candidate one-hot depends on it.
SKILL_NAMES = (
    "WORK", "EAT", "DRINK", "REST", "SOCIALIZE",
    "STEAL", "ATTACK", "FLEE", "APOLOGIZE", "FIGHT_MONSTER",
    # graded reactions to provocation
    "IGNORE", "RETORT", "DEMAND_APOLOGY", "REFUSE_APOLOGY", "COMPLAIN_TO", "AVOID",
    # gossip, bargaining, authority
    "GOSSIP", "ACCEPT", "REFUSE", "BARGAIN", "FULFIL", "PUNISH",
)
SKILL_INDEX = {s: i for i, s in enumerate(SKILL_NAMES)}
N_SKILLS = len(SKILL_NAMES)

#: The reaction skills a provocation opens up, in the order the design talks about them. ATTACK is
#: a reaction too, but it is also its own thing, so it is not in this list.
REACTION_SKILLS = ("IGNORE", "RETORT", "DEMAND_APOLOGY", "COMPLAIN_TO", "AVOID")

#: Stable term order. ``base`` is always 1.0, so a skill's weight for it is its resting appetite.
#: Every other term is a number in roughly 0..1 (a few are signed) read off the agent, the target
#: and the world. Appended to, never reordered.
TERM_NAMES = (
    "base",
    "need_hunger", "need_thirst", "need_fatigue", "need_social",
    "anger", "fear", "happiness", "grief",
    "bravery", "greed", "temper", "sociability",
    "anger_at_target", "hatred_target", "trust_target", "respect_target",
    "being_attacked", "monster_threat", "hurt",
    "wealth_drive", "supply_pressure", "request_pull",
    "distance_cost", "noise",
    # -- v1: memory, reactions, gossip, obligations, goals, authority ------------
    "pride", "forgiveness",
    "grudge_target", "gratitude_target", "reputation_target",
    "provoked_by_target", "publicity", "humiliation",
    "chief_present", "expected_punishment", "fear_target",
    "goal_bias", "obligation_pressure", "ask_cost", "payment_offered",
    "gossip_value", "punish_pressure",
    # -- v2: injuries -------------------------------------------------------------
    #: How much *this* candidate in particular is hampered by what is broken. Not the same as
    #: ``hurt``: a broken leg makes walking to the mine hopeless and saying sorry no harder.
    "impairment",
)
TERM_INDEX = {t: i for i, t in enumerate(TERM_NAMES)}
N_TERMS = len(TERM_NAMES)

CAND_SKILL = 0                  # skill one-hot        (N_SKILLS)
CAND_TERMS = N_SKILLS           # raw term values      (N_TERMS)
CAND_SIZE = N_SKILLS + N_TERMS  # 65

#: Bumped whenever any offset above moves, so a stale weight file can be refused the way the mod
#: refuses a schema mismatch.
#:
#: ``v2`` is the injury stage: the observation grew the condition block (69 -> 77) and the
#: candidate features grew ``impairment`` (64 -> 65). ``shared/models/decisions`` has been
#: re-frozen against it -- 142 inputs, 13,377 parameters, a new ``layout.json`` and so a new schema
#: id stamped into ``decisions.mbw`` -- and the mod's ``MindObservation`` grew the matching
#: ``condition`` column, which it fills with zeros until the game has injuries of its own. The
#: interpreter reads none of this layout and did not move.
SCHEMA_ID = "dwarfsim-v2"

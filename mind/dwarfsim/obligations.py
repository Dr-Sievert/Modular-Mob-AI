"""Obligations: what one dwarf has asked another to do, and whether it got done.

A ``REQUEST``, ``COMMAND`` or ``OFFER`` that carries a structured ``ask`` becomes a record here
instead of (only) the vague ``request_pull`` nudge. The record is the thing that makes promises
mean something: it has a deadline, so it can be broken, and breaking it is an event that gossip can
carry across the settlement.

    ask = {"action": one of ASK_ACTIONS, "item": str|None, "quantity": int, "place": str|None,
           "target": agent id|None, "payment": gold offered by the asker}

Lifecycle::

    PENDING --ACCEPT--> ACCEPTED --FULFIL--> KEPT
       |                   |
       |                   +----deadline----> BROKEN
       +--REFUSE--> REFUSED
       +--BARGAIN--> PENDING again, with the payment raised, for the asker to accept
       +--deadline--> EXPIRED

Gold moves when an obligation is accepted, not when it is kept: dwarves are practical, and a
prepayment that is then not earned is exactly the betrayal that should cost trust.
"""

from .schema import ASK_ACTIONS

#: How long an ask sits unanswered before it lapses.
RESPONSE_TTL = 40

#: Default deadline for an accepted ask, in ticks from when it was made.
DEFAULT_DEADLINE = 120

#: How much gold a bargain asks for on top of whatever was offered.
BARGAIN_STEP = 3


def make_ask(action, item=None, quantity=1, place=None, target=None, payment=0):
    """Build a structured ask, defaulting the fields the caller left out."""
    if action not in ASK_ACTIONS:
        raise ValueError("unknown ask action %r (one of %s)" % (action, ", ".join(ASK_ACTIONS)))
    return {"action": action, "item": item, "quantity": int(quantity), "place": place,
            "target": target, "payment": int(payment)}


def normalise_ask(raw):
    """Take the optional ``ask`` off a parsed utterance and make it a full ask, or ``None``."""
    if not raw:
        return None
    action = raw.get("action")
    if action not in ASK_ACTIONS:
        return None
    return make_ask(action, raw.get("item"), raw.get("quantity", 1), raw.get("place"),
                    raw.get("target"), raw.get("payment", 0))


class Obligation:
    """One ask, from somebody to somebody, with a deadline and a status."""

    __slots__ = ("oid", "frm", "to", "ask", "made", "deadline", "status", "accepted_tick",
                 "paid", "counter", "place")

    def __init__(self, oid, frm, to, ask, made, deadline):
        self.oid = oid
        self.frm = frm
        self.to = to
        self.ask = ask
        self.made = made
        self.deadline = deadline
        self.status = "PENDING"
        self.accepted_tick = None
        self.paid = 0
        self.counter = 0          # gold the obligated has counter-demanded, 0 if no bargain yet
        self.place = ask.get("place")

    @property
    def open(self):
        return self.status in ("PENDING", "ACCEPTED")

    def payment(self):
        """What the asker is currently offering."""
        return max(self.ask.get("payment", 0), self.counter)

    def label(self, namer=None):
        a = self.ask
        who = namer(self.frm) if namer else str(self.frm)
        bits = [a["action"]]
        if a.get("item"):
            bits.append("%dx %s" % (a.get("quantity", 1), a["item"]))
        if a.get("place"):
            bits.append("@" + a["place"])
        if a.get("target") is not None:
            bits.append("->%s" % (namer(a["target"]) if namer else a["target"]))
        return "%s for %s" % (" ".join(bits), who)

    def cost(self, agent, world):
        """How much doing this would cost me, 0..1. The personal-cost term of ACCEPT/REFUSE."""
        a = self.ask
        action = a["action"]
        if action in ("BRING", "GIVE"):
            item = a.get("item") or "gold"
            qty = max(1, a.get("quantity", 1))
            have = agent.inv.get(item, 0)
            if item == "weapon":
                return 0.9
            scarcity = 1.0 if have < qty else max(0.15, min(1.0, qty / float(have + qty)))
            return min(1.0, 0.25 + 0.75 * scarcity)
        if action == "GO_TO":
            return 0.15 if a.get("place") == agent.place else 0.40
        if action == "FIGHT":
            foe = world.agent(a.get("target"))
            if foe is None:
                return 0.8
            edge = agent.inv["weapon"] - foe.inv["weapon"]
            return max(0.2, min(1.0, 0.75 - 0.5 * edge + 0.3 * (1.0 - agent.health / 20.0)))
        if action == "HELP":
            return 0.55
        if action == "STOP":
            return 0.30
        return 0.5

    def snapshot(self):
        return {"id": self.oid, "from": self.frm, "to": self.to, "ask": dict(self.ask),
                "made": self.made, "deadline": self.deadline, "status": self.status,
                "paid": self.paid, "payment": self.payment()}

    def __repr__(self):
        return "<obl %s %s->%s %s %s>" % (self.oid, self.frm, self.to,
                                          self.ask["action"], self.status)

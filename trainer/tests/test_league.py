"""The league's arithmetic: Elo, matchmaking and the pool, and the league reading and resuming a run's results.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import json
import math
import tempfile
import unittest
from dataclasses import replace

from mmai.league import (League, Ratings, base, capped, checkpoint_name, expected, pair_guess, pool, shares,
                         win_chance)
from mmai.ppo import Config
from mmai.run import RunDirectory


class EloTest(unittest.TestCase):
    def test_level_players_expect_a_half_and_400_points_is_ten_to_one(self):
        self.assertAlmostEqual(expected(1500.0, 1500.0), 0.5)
        self.assertAlmostEqual(expected(1900.0, 1500.0), 10.0 / 11.0)
        self.assertAlmostEqual(expected(1500.0, 1900.0) + expected(1900.0, 1500.0), 1.0)

    def test_a_win_between_level_players_moves_each_by_half_of_k(self):
        ratings = Ratings(k=16.0, provisional=0, initial=1500.0, anchor="")
        ratings.game("iteration-000100", "checkpoint", "zombie", "mob", 1.0)

        self.assertAlmostEqual(ratings.rating("iteration-000100"), 1508.0)
        self.assertAlmostEqual(ratings.rating("zombie"), 1492.0)

    def test_equal_k_keeps_the_total(self):
        ratings = Ratings(k=16.0, provisional=0, initial=1500.0, anchor="")

        for score in (1.0, 0.0, 0.5, 1.0, 1.0):
            ratings.game("iteration-000000", "checkpoint", "creeper", "mob", score)

        self.assertAlmostEqual(ratings.rating("iteration-000000") + ratings.rating("creeper"), 3000.0)

    def test_the_anchor_never_moves_and_its_opponent_still_does(self):
        ratings = Ratings(k=16.0, provisional=0, initial=1500.0, anchor="scripted")

        for _ in range(20):
            ratings.game("iteration-000025", "checkpoint", "scripted", "scripted", 1.0)

        self.assertEqual(ratings.rating("scripted"), 1500.0)
        self.assertGreater(ratings.rating("iteration-000025"), 1600.0)
        self.assertEqual(ratings.players["scripted"].games, 20)
        self.assertEqual(ratings.players["scripted"].losses, 20)

    def test_a_newcomer_moves_twice_as_far_until_it_has_played_its_provisional_fights(self):
        ratings = Ratings(k=16.0, provisional=2, initial=1500.0, anchor="")
        ratings.players["zombie"] = replace(ratings.ensure("zombie", "mob"), games=100)

        ratings.game("iteration-000000", "checkpoint", "zombie", "mob", 1.0)
        self.assertAlmostEqual(ratings.rating("iteration-000000"), 1516.0)
        self.assertAlmostEqual(ratings.rating("zombie"), 1492.0)

    def test_a_draw_between_level_players_changes_nothing(self):
        ratings = Ratings(k=16.0, provisional=30, initial=1500.0, anchor="")
        ratings.game("iteration-000000", "checkpoint", "skeleton", "mob", 0.5)

        self.assertAlmostEqual(ratings.rating("iteration-000000"), 1500.0)
        self.assertEqual(ratings.players["skeleton"].draws, 1)

    def test_the_order_of_the_two_players_does_not_matter(self):
        one = Ratings(k=16.0, provisional=0, initial=1500.0, anchor="")
        two = Ratings(k=16.0, provisional=0, initial=1500.0, anchor="")

        one.players["witch"] = replace(one.ensure("witch", "mob"), rating=1620.0)
        two.players["witch"] = replace(two.ensure("witch", "mob"), rating=1620.0)

        one.game("iteration-000050", "checkpoint", "witch", "mob", 1.0)
        two.game("witch", "mob", "iteration-000050", "checkpoint", 0.0)

        self.assertAlmostEqual(one.rating("witch"), two.rating("witch"))
        self.assertAlmostEqual(one.rating("iteration-000050"), two.rating("iteration-000050"))

    def test_the_agent_stands_where_the_newest_settled_checkpoint_does(self):
        ratings = Ratings(k=16.0, provisional=30, initial=1500.0, anchor="")
        self.assertIsNone(ratings.newest_checkpoint())

        ratings.players["iteration-000025"] = replace(ratings.ensure("iteration-000025", "checkpoint"), games=12)
        ratings.players["iteration-000050"] = replace(ratings.ensure("iteration-000050", "checkpoint"), games=2)
        self.assertEqual(ratings.newest_checkpoint().name, "iteration-000025")

        ratings.players["iteration-000000"] = replace(ratings.ensure("iteration-000000", "checkpoint"), games=300)
        ratings.players["iteration-000075"] = replace(ratings.ensure("iteration-000075", "checkpoint"), games=40)
        self.assertEqual(ratings.newest_checkpoint().name, "iteration-000075")

    def test_a_new_checkpoint_starts_where_the_one_before_it_got_to(self):
        ratings = Ratings(k=16.0, provisional=30, initial=1500.0, anchor="")
        ratings.players["iteration-000025"] = replace(ratings.ensure("iteration-000025", "checkpoint"), rating=1710.0)
        ratings.players["iteration-000100"] = replace(ratings.ensure("iteration-000100", "checkpoint"), rating=1300.0)

        self.assertEqual(ratings.ensure("iteration-000050", "checkpoint").rating, 1710.0)
        self.assertEqual(ratings.ensure("iteration-000000", "checkpoint").rating, 1500.0)
        self.assertEqual(ratings.ensure("zoglin", "mob").rating, 1500.0)


class MatchmakingTest(unittest.TestCase):
    def test_shares_add_up_to_one_and_the_even_fight_gets_the_most(self):
        result = shares({"silverfish": 0.99, "creeper": 0.5, "ravager": 0.02, "zombie": 0.8}, floor=0.25)

        self.assertAlmostEqual(sum(result.values()), 1.0)
        self.assertEqual(max(result, key=result.get), "creeper")

    def test_the_floor_keeps_every_opponent_coming_round(self):
        result = shares({"silverfish": 1.0, "creeper": 0.5, "ravager": 0.0}, floor=0.3)

        self.assertAlmostEqual(result["silverfish"], 0.1)
        self.assertAlmostEqual(result["ravager"], 0.1)
        self.assertAlmostEqual(result["creeper"], 0.8)

    def test_the_frontier_takes_the_floor_off_a_fight_it_never_wins(self):
        """What nine per cent of a run's fights were going on: an even floor gives a hopeless opponent the same standing
        share as a close one, and a fight lost every time has no version of itself the agent got further in."""

        chances = {"zombie": 0.5, "skeleton": 0.4, "warden": 0.0, "evoker": 0.01, "ghast": 0.05}

        even = shares(chances, floor=0.25)
        frontier = shares(chances, floor=0.25, frontier=0.15, probe=0.2)

        self.assertAlmostEqual(sum(frontier.values()), 1.0)

        for hopeless in ("warden", "evoker", "ghast"):
            self.assertLess(frontier[hopeless], even[hopeless])

        for close in ("zombie", "skeleton"):
            self.assertGreater(frontier[close], even[close])

    def test_a_hopeless_opponent_is_still_tried_now_and_then(self):
        """It has to be: one that cannot be beaten at a thousand iterations may be beatable at ten thousand, and nothing
        would ever find that out."""

        result = shares({"zombie": 0.5, "warden": 0.0}, floor=0.25, frontier=0.15, probe=0.2)

        self.assertGreater(result["warden"], 0.0)

    def test_no_frontier_is_what_it_always_was(self):
        chances = {"silverfish": 1.0, "creeper": 0.5, "ravager": 0.0}

        self.assertEqual(shares(chances, floor=0.3), shares(chances, floor=0.3, frontier=0.0, probe=1.0))

    def test_nothing_to_tell_them_apart_is_an_even_spread(self):
        for chances, floor in (({"a": 1.0, "b": 1.0}, 0.25), ({"a": 0.3, "b": 0.3, "c": 0.3, "d": 0.3}, 0.0)):
            for share in shares(chances, floor).values():
                self.assertAlmostEqual(share, 1.0 / len(chances))

        self.assertEqual(shares({}, floor=0.25), {})

    def test_a_cap_holds_an_opponent_down_and_the_rest_take_what_it_gave_up(self):
        result = shares({"warden": 0.5, "zombie": 0.5, "creeper": 0.5}, floor=0.25, caps={"warden": 0.002})

        self.assertAlmostEqual(result["warden"], 0.002)
        self.assertAlmostEqual(result["zombie"], result["creeper"])
        self.assertAlmostEqual(sum(result.values()), 1.0)

    def test_an_uncapped_opponent_under_its_cap_is_left_alone(self):
        plain = shares({"warden": 0.02, "zombie": 0.5}, floor=0.25)
        held = shares({"warden": 0.02, "zombie": 0.5}, floor=0.25, caps={"warden": 0.5, "zombie": 1.0})

        self.assertEqual(plain, held)

    def test_capping_everyone_spends_less_than_all_the_fights(self):
        result = capped({"a": 0.5, "b": 0.5}, {"a": 0.1, "b": 0.1})

        self.assertAlmostEqual(result["a"], 0.1)
        self.assertAlmostEqual(sum(result.values()), 0.2)

    def test_what_one_cap_gives_up_does_not_push_another_over_its_own(self):
        result = capped({"a": 0.6, "b": 0.3, "c": 0.1}, {"a": 0.1, "b": 0.35})

        self.assertAlmostEqual(result["a"], 0.1)
        self.assertAlmostEqual(result["b"], 0.35)
        self.assertAlmostEqual(sum(result.values()), 1.0)

    def test_the_chance_is_the_record_filled_in_by_the_guess(self):
        self.assertAlmostEqual(win_chance(0.0, 0.0, 0.7, 10.0), 0.7)
        self.assertAlmostEqual(win_chance(90.0, 100.0, 0.2, 10.0), (90.0 + 2.0) / 110.0)

    def test_a_pairing_is_weighed_the_way_an_opponent_is(self):
        """The keys being pairs changes nothing about the rule: the even one gets the most and they add up to one."""

        chances = {("bow", "creeper"): 0.5, ("bow", "ghast"): 0.02, ("sword", "creeper"): 0.95, ("sword", "ghast"): 0.5}
        result = shares(chances, floor=0.25)

        self.assertAlmostEqual(sum(result.values()), 1.0)
        self.assertEqual(max(result, key=result.get), ("bow", "creeper"))
        self.assertGreater(result[("sword", "ghast")], result[("sword", "creeper")])
        self.assertGreater(result[("bow", "ghast")], 0.0)

    def test_a_cap_is_the_opponents_and_holds_every_loadout_against_it_between_them(self):
        """Two thousandths of the fights against the warden, not two thousandths with each of three loadouts."""

        pairs = {(loadout, opponent): 0.5 for loadout in ("sword", "bow", "axe") for opponent in ("warden", "zombie")}
        groups = {pair: pair[1] for pair in pairs}
        result = shares(pairs, floor=0.25, caps={"warden": 0.002}, groups=groups)

        warden = sum(share for pair, share in result.items() if pair[1] == "warden")

        self.assertAlmostEqual(warden, 0.002)
        self.assertAlmostEqual(sum(result.values()), 1.0)

        # What it gave up went to the pairings with the zombie, and the three of those are still even with each other.
        for loadout in ("bow", "axe"):
            self.assertAlmostEqual(result[(loadout, "zombie")], result[("sword", "zombie")])
            self.assertAlmostEqual(result[(loadout, "warden")], result[("sword", "warden")])

    def test_a_group_cap_keeps_the_balance_inside_the_group(self):
        result = capped({("bow", "warden"): 0.3, ("sword", "warden"): 0.1, ("sword", "zombie"): 0.6},
                        {"warden": 0.04}, {("bow", "warden"): "warden", ("sword", "warden"): "warden",
                                           ("sword", "zombie"): "zombie"})

        self.assertAlmostEqual(result[("bow", "warden")], 0.03)
        self.assertAlmostEqual(result[("sword", "warden")], 0.01)
        self.assertAlmostEqual(sum(result.values()), 1.0)

    def test_the_guess_for_a_pairing_is_the_opponent_moved_by_how_the_loadout_does(self):
        # A loadout that does what the average loadout does says nothing about the opponent, so the guess is the opponent's.
        self.assertAlmostEqual(pair_guess(0.4, 0.55, 0.55), 0.4)

        # One that wins less often than the average moves it down, and one that wins more moves it up, and never past either
        # end: a loadout that has won everything so far still does not beat the warden.
        self.assertLess(pair_guess(0.4, 0.3, 0.55), 0.4)
        self.assertGreater(pair_guess(0.4, 0.8, 0.55), 0.4)
        self.assertLess(pair_guess(0.02, 1.0, 0.5), 1.0)
        self.assertGreater(pair_guess(0.98, 0.0, 0.5), 0.0)

    def test_the_pool_is_the_newest_and_the_rest_spread_out(self):
        checkpoints = list(range(0, 500, 25))

        self.assertEqual(pool(checkpoints, 8, 4), [0, 125, 250, 375, 400, 425, 450, 475])
        self.assertEqual(pool([0, 25, 50], 8, 4), [0, 25, 50])
        self.assertEqual(pool(checkpoints, 3, 4), [425, 450, 475])
        self.assertEqual(pool(checkpoints, 5, 4), [0, 400, 425, 450, 475])
        self.assertEqual(len(pool(checkpoints, 8, 0)), 8)


class LadderTest(unittest.TestCase):
    """The rungs of the difficulty ladder, on a run folder as a training run leaves one."""

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.run = RunDirectory(self.folder.name)
        self.config = Config(league=True, checkpoint_every=25, device="cpu", league_rung_fights=10)

        league = self.run.path / "league"
        (league / "results").mkdir(parents=True)
        (league / "roster.csv").write_text(
            "opponent,kind,cap\nzombie,mob,1.00000\nravager,mob,1.00000\nwarden,mob,0.00200\n2x_zombie,squad,1.00000\n"
            "scripted,scripted,1.00000\n", encoding="utf-8")

        self.run.weights_file(0).write_bytes(b"")

    def tearDown(self):
        self.folder.cleanup()

    def evaluations(self, opponent: str, wins: int, losses: int) -> None:
        lines = [f"0,eval,{opponent},sword,-,win,200" for _ in range(wins)]
        lines += [f"0,eval,{opponent},sword,-,loss,200" for _ in range(losses)]

        with open(self.run.path / "league" / "results" / "w00.csv", "a", encoding="utf-8") as stream:
            stream.write("".join(line + "\n" for line in lines))

    def matchmaking(self) -> dict[str, float]:
        text = (self.run.path / "league" / "matchmaking.csv").read_text(encoding="utf-8")
        return {line.split(",")[0]: float(line.split(",")[1]) for line in text.splitlines()[1:]}

    def test_the_name_of_a_rung_says_which_opponent_it_is_a_rung_of(self):
        self.assertEqual(base("zombie(hard)"), "zombie")
        self.assertEqual(base("2x_zombie(easy)"), "2x_zombie")
        self.assertEqual(base("zombie"), "zombie")
        self.assertEqual(base("iteration-000050"), "iteration-000050")

    def test_the_name_of_a_crowded_fight_says_which_opponent_it_was_against(self):
        # A crowd of bystanders is written outside the rung, so both come off and in that order. What this buys is that a
        # crowded fight inherits the opponent's kind and the opponent's cap instead of falling back to "mob" and to no cap.
        self.assertEqual(base("zombie+3_idle"), "zombie")
        self.assertEqual(base("zombie(hard)+9_idle"), "zombie")
        self.assertEqual(base("2x_zombie+1_idle"), "2x_zombie")

        # A squad's own name is plus signs all the way through, and none of it is a digit followed by _idle, so a squad
        # cannot be mistaken for a crowd however it is spelled.
        self.assertEqual(base("zombie+skeleton"), "zombie+skeleton")
        self.assertEqual(base("zombie+skeleton+2_idle"), "zombie+skeleton")
        self.assertEqual(base("witch+zombie(easy)"), "witch+zombie")

    def test_the_name_of_a_pack_says_which_mob_the_pack_is_of(self):
        # A pack of the same mob, all of them fighting, is written outside the rung exactly as a crowd is, so it comes off the
        # same way and a pack inherits the mob's kind and the mob's cap. zombie+3_pack is a zombie and three more of it.
        self.assertEqual(base("zombie+3_pack"), "zombie")
        self.assertEqual(base("zombie(hard)+5_pack"), "zombie")
        self.assertEqual(base("ravager+1_pack"), "ravager")

        # And nothing about a squad's name or a crowd's can be read as a pack, or the other way round: a fight is never both,
        # and no squad member is a digit followed by _pack.
        self.assertEqual(base("zombie+skeleton"), "zombie+skeleton")
        self.assertEqual(base("zombie+skeleton+2_pack"), "zombie+skeleton")
        self.assertEqual(base("zombie+3_idle"), "zombie")

    def test_a_pack_is_a_player_of_its_own_and_is_never_matchmade_over(self):
        # The same claim a crowd gets, and the one thing that could go wrong if the suffix were not known here: a pack would
        # arrive as a player of kind "mob" with no cap and could have a rung opened on it, and the workers field no such
        # opponent by name, so every fight the trainer then asked for would fall back to a random one.
        self.evaluations("zombie", wins=6, losses=4)
        self.evaluations("zombie+3_pack", wins=1, losses=9)
        self.evaluations("warden+2_pack", wins=0, losses=10)

        league = League(self.run, self.config)
        league.update(0)

        self.assertIn("zombie+3_pack", league.ratings.players)
        self.assertEqual(league.ratings.players["zombie+3_pack"].kind, "mob")
        self.assertNotIn("zombie+3_pack", self.matchmaking())

        # The cap comes off the mob the pack is of, which is the whole reason base() has to know the suffix: the warden is
        # capped at a fifth of a per cent, and a pack of wardens may not be the run's curriculum either.
        self.assertLessEqual(self.matchmaking().get("warden", 0.0), 0.01)

        # And the plain rating is untouched: the agent won six of ten against one zombie and lost nine of ten against four, so
        # the pack has to be the higher rated player of the two.
        self.assertGreater(league.ratings.rating("zombie+3_pack"), league.ratings.rating("zombie"))

    def test_a_crowded_fight_is_a_player_of_its_own_and_is_never_matchmade_over(self):
        # Every fight against the plain zombie and against the same zombie with three monsters standing about it. Both are
        # rated; only the one the workers said they field is matchmade over, since the crowd is the game's own coin flip.
        self.evaluations("zombie", wins=6, losses=4)
        self.evaluations("zombie+3_idle", wins=1, losses=9)

        league = League(self.run, self.config)
        league.update(0)

        self.assertIn("zombie+3_idle", league.ratings.players)
        self.assertEqual(league.ratings.players["zombie+3_idle"].kind, "mob")
        self.assertIn("zombie", self.matchmaking())
        self.assertNotIn("zombie+3_idle", self.matchmaking())

        # And the plain rating is untouched by it: the agent lost nine of ten in the crowd and won six of ten without one, so
        # the crowd has to be the higher rated of the two players.
        self.assertGreater(league.ratings.rating("zombie+3_idle"), league.ratings.rating("zombie"))

    def test_hard_opens_once_the_agent_wins_most_and_easy_while_it_wins_almost_none(self):
        self.evaluations("zombie", wins=9, losses=1)
        self.evaluations("ravager", wins=1, losses=9)
        self.evaluations("2x_zombie", wins=5, losses=5)

        league = League(self.run, self.config)
        league.update(0)

        self.assertEqual(league.rungs, {"zombie(hard)", "ravager(easy)"})
        self.assertIn("zombie(hard)", self.matchmaking())
        self.assertNotIn("2x_zombie(hard)", self.matchmaking())
        self.assertNotIn("2x_zombie(easy)", self.matchmaking())

    def test_a_rung_waits_for_enough_fights_to_have_judged_it(self):
        self.evaluations("zombie", wins=3, losses=0)

        league = League(self.run, self.config)
        league.update(0)

        self.assertEqual(league.rungs, set())

    def test_a_rung_stays_open_once_it_is_open_and_survives_a_resume(self):
        self.evaluations("zombie", wins=10, losses=0)

        first = League(self.run, self.config)
        first.update(0)
        self.assertEqual(first.rungs, {"zombie(hard)"})

        # The agent now loses every fight on normal; the rung it earned is not taken away again.
        self.evaluations("zombie", wins=0, losses=20)
        first.update(1)
        self.assertEqual(first.rungs, {"zombie(hard)"})

        resumed = League(self.run, self.config)
        self.assertEqual(resumed.rungs, {"zombie(hard)"})

    def test_nothing_but_a_mob_or_a_squad_earns_a_rung(self):
        """The scripted fighter and a published network are fixed policies: there is no harder version of either to open."""

        (self.run.path / "league" / "roster.csv").write_text(
            "opponent,kind,cap\nzombie,mob,1.00000\nscripted,scripted,1.00000\nvs-copy,model,1.00000\n", encoding="utf-8")

        self.evaluations("zombie", wins=10, losses=0)
        self.evaluations("scripted", wins=10, losses=0)
        self.evaluations("vs-copy", wins=0, losses=10)

        league = League(self.run, self.config)
        league.update(0)

        self.assertEqual(league.rungs, {"zombie(hard)"})
        self.assertNotIn("scripted(hard)", self.matchmaking())
        self.assertNotIn("vs-copy(easy)", self.matchmaking())

    def test_a_rung_opened_for_something_that_has_none_is_dropped_with_its_player(self):
        """Runs already going opened scripted(easy), which their workers could never field. It goes, and so does the row it
        left in the ratings, since it never fought."""

        state = self.run.path / "league" / "state.json"
        state.write_text(json.dumps({"rungs": ["scripted(easy)", "zombie(hard)"],
                                     "players": {"scripted(easy)": ["scripted", 1500.0, 0, 0, 0, 0]}}), encoding="utf-8")

        league = League(self.run, self.config)
        self.assertEqual(league.rungs, {"scripted(easy)", "zombie(hard)"})

        league.update(0)

        self.assertEqual(league.rungs, {"zombie(hard)"})
        self.assertNotIn("scripted(easy)", league.ratings.players)
        self.assertNotIn("scripted(easy)", self.matchmaking())
        self.assertIn("zombie(hard)", self.matchmaking())

    def test_a_rung_is_the_same_kind_of_thing_and_under_the_same_cap_as_its_opponent(self):
        self.evaluations("2x_zombie", wins=10, losses=0)
        self.evaluations("warden", wins=0, losses=10)

        league = League(self.run, self.config)
        league.update(0)

        ratings = {row[0]: row for row in [line.split(",") for line in
                   (self.run.path / "league" / "ratings.csv").read_text(encoding="utf-8").splitlines()[1:]]}

        self.assertEqual(ratings["2x_zombie(hard)"][1], "squad")
        self.assertEqual(ratings["warden(easy)"][1], "mob")
        self.assertLessEqual(self.matchmaking()["warden(easy)"], 0.002)


class LeagueTest(unittest.TestCase):
    """The league on a run folder the way a training run leaves one."""

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.run = RunDirectory(self.folder.name)
        self.config = Config(league=True, checkpoint_every=25, device="cpu")

        league = self.run.path / "league"
        (league / "results").mkdir(parents=True)
        (league / "roster.csv").write_text("opponent,kind\nzombie,mob\ncreeper,mob\nscripted,scripted\n", encoding="utf-8")

        for iteration in (0, 25, 50):
            self.run.weights_file(iteration).write_bytes(b"")

    def tearDown(self):
        self.folder.cleanup()

    def results(self, worker: int, *lines: str) -> None:
        with open(self.run.path / "league" / "results" / f"w{worker:02d}.csv", "a", encoding="utf-8") as stream:
            stream.write("".join(line + "\n" for line in lines))

    def read(self, name: str) -> list[list[str]]:
        text = (self.run.path / "league" / name).read_text(encoding="utf-8")
        return [line.split(",") for line in text.splitlines()[1:]]

    def test_evaluations_are_rated_and_training_fights_are_not(self):
        self.results(0, "50,eval,zombie,sword,-,win,200,-", "50,eval,creeper,bow,-,draw,90,-", "51,train,zombie,axe,-,loss,300,opponent",
                     "50,eval,skeleton,axe,-,loss,410,lava")

        league = League(self.run, self.config)
        league.update(51)

        ratings = {row[0]: row for row in self.read("ratings.csv")}

        self.assertEqual(int(ratings["iteration-000050"][3]), 3)
        self.assertEqual(int(ratings["zombie"][3]), 1)
        self.assertLess(float(ratings["zombie"][2]), 1500.0)
        self.assertEqual(ratings["skeleton"][4], "1")
        self.assertEqual(league.rated, 3)

    def test_matchmaking_covers_the_roster_and_the_pool_and_adds_up(self):
        league = League(self.run, self.config)
        league.update(50)

        rows = {row[0]: float(row[1]) for row in self.read("matchmaking.csv")}

        self.assertEqual(set(rows), {"zombie", "creeper", "scripted", checkpoint_name(0), checkpoint_name(25), checkpoint_name(50)})
        self.assertAlmostEqual(sum(rows.values()), 1.0, places=4)
        self.assertAlmostEqual(sum(rows[checkpoint_name(number)] for number in (0, 25, 50)), self.config.league_self_play, places=4)

    def test_a_squad_is_rated_as_a_player_of_its_own_and_never_as_a_sum(self):
        (self.run.path / "league" / "roster.csv").write_text(
            "opponent,kind,cap\nzombie,mob,1.00000\n2x_zombie,squad,1.00000\nzombie+skeleton,squad,1.00000\nscripted,scripted,1.00000\n",
            encoding="utf-8")

        self.results(0, "50,eval,zombie,sword,-,win,200", "50,eval,2x_zombie,sword,-,loss,300",
                     "50,eval,zombie+skeleton,bow,-,timeout,1200")

        league = League(self.run, self.config)
        league.update(50)

        ratings = {row[0]: row for row in self.read("ratings.csv")}
        opponents = {row[0]: row for row in self.read("opponents.csv")}

        self.assertEqual(ratings["2x_zombie"][1], "squad")
        self.assertEqual(ratings["zombie+skeleton"][1], "squad")
        self.assertEqual(ratings["zombie"][1], "mob")

        # Each of the three moved on its own fight and nothing added the squads up out of their members.
        self.assertLess(float(ratings["zombie"][2]), 1500.0)
        self.assertGreater(float(ratings["2x_zombie"][2]), 1500.0)
        self.assertEqual(float(ratings["zombie+skeleton"][2]), 1500.0)
        self.assertEqual(opponents["2x_zombie"][4:6], ["1", "0"])

    def test_a_capped_opponent_takes_no_more_than_its_cap_and_is_still_drawn_for_evaluation(self):
        (self.run.path / "league" / "roster.csv").write_text(
            "opponent,kind,cap\nzombie,mob,1.00000\nwarden,mob,0.00200\nscripted,scripted,1.00000\n", encoding="utf-8")

        league = League(self.run, self.config)
        league.update(50)

        rows = {row[0]: float(row[1]) for row in self.read("matchmaking.csv")}

        self.assertLessEqual(rows["warden"], 0.002)
        self.assertGreater(rows["warden"], 0.0)
        self.assertGreater(rows["zombie"], rows["warden"])

    def test_an_opponent_always_beaten_is_met_less_than_an_even_one(self):
        self.results(0, *[f"50,train,zombie,sword,-,win,100" for _ in range(60)])
        self.results(1, *[f"50,train,creeper,sword,-,{'win' if index % 2 else 'loss'},100" for index in range(60)])

        league = League(self.run, self.config)
        league.update(50)

        rows = {row[0]: float(row[1]) for row in self.read("matchmaking.csv")}

        self.assertGreater(rows["creeper"], rows["zombie"])
        self.assertGreater(rows["zombie"], 0.0)

    def test_half_written_lines_wait_for_the_rest(self):
        path = self.run.path / "league" / "results" / "w00.csv"
        path.write_text("50,eval,zombie,sword,-,win,200\n50,eval,cree", encoding="utf-8")

        league = League(self.run, self.config)
        league.update(50)
        self.assertEqual(league.rated, 1)

        with open(path, "a", encoding="utf-8") as stream:
            stream.write("per,sword,-,loss,120\n")

        league.update(51)
        self.assertEqual(league.rated, 2)
        self.assertEqual(league.ratings.players["creeper"].wins, 1)

    def test_a_resumed_league_carries_on_exactly_where_it_was(self):
        self.results(0, "50,eval,zombie,sword,-,win,200", "50,eval,scripted,axe,sword,loss,400")

        first = League(self.run, self.config)
        first.update(50)

        self.results(1, "50,eval,creeper,sword,-,timeout,1200")
        first.update(51)

        resumed = League(self.run, self.config)
        resumed.update(52)

        for name, player in first.ratings.players.items():
            self.assertTrue(math.isclose(player.rating, resumed.ratings.players[name].rating))

        self.assertEqual(resumed.rated, 3)
        self.assertEqual(json.loads((self.run.path / "league" / "state.json").read_text(encoding="utf-8"))["rated"], 3)

    def test_the_ground_a_fight_was_on_and_what_finished_it_are_counted_per_kind(self):
        self.results(0,
                     "50,eval,zombie,sword,-,win,200,-,lava,lava",
                     "50,train,zombie,sword,-,win,150,-,lava,agent",
                     "50,train,creeper,sword,-,win,150,-,drop,fall",
                     "50,train,zombie,sword,-,loss,300,opponent,flat,-",
                     "50,train,2x_creeper,sword,-,win,150,-,flat,side")

        league = League(self.run, self.config)
        league.update(50)

        ground = {row[0]: [int(value) for value in row[1:]] for row in self.read("ground.csv")}

        # fights, wins, by_agent, by_terrain, by_side
        self.assertEqual(ground["lava"], [2, 2, 1, 1, 0])
        self.assertEqual(ground["drop"], [1, 1, 0, 1, 0])
        self.assertEqual(ground["flat"], [2, 1, 0, 0, 1])

    def test_a_result_written_before_the_ground_was_recorded_still_reads(self):
        self.results(0, "50,eval,zombie,sword,-,win,200", "50,eval,creeper,sword,-,loss,300,lava")

        league = League(self.run, self.config)
        league.update(50)

        ground = {row[0]: [int(value) for value in row[1:]] for row in self.read("ground.csv")}

        self.assertEqual(league.rated, 2)
        self.assertEqual(ground["-"], [2, 1, 0, 0, 0])

    def test_the_ground_counts_survive_a_resume(self):
        self.results(0, "50,train,zombie,sword,-,win,150,-,lava,lava")

        first = League(self.run, self.config)
        first.update(50)

        resumed = League(self.run, self.config)
        self.assertEqual(resumed.ground["lava"].by_terrain, 1)

    def test_a_published_model_is_a_player_in_the_mobs_group_and_not_a_second_anchor(self):
        """Two lineages meet by both fielding the same published network. It is weighed with the mobs, since it never
        learns, it starts where everyone starts, and only the scripted fighter is held still."""

        (self.run.path / "league" / "roster.csv").write_text(
            "opponent,kind,cap\nzombie,mob,1.00000\nscripted,scripted,1.00000\nvs-copy,model,1.00000\n", encoding="utf-8")

        self.results(0, "50,eval,vs-copy,sword,bow,loss,300,opponent,flat,agent", "50,eval,scripted,axe,sword,win,200,-,flat,agent")

        league = League(self.run, self.config)
        league.update(50)

        ratings = {row[0]: row for row in self.read("ratings.csv")}
        shares = {row[0]: float(row[1]) for row in self.read("matchmaking.csv")}

        self.assertEqual(ratings["vs-copy"][1], "model")

        # Beating the agent pushed it up from where everyone starts, and the anchor did not move when it lost.
        self.assertGreater(float(ratings["vs-copy"][2]), 1500.0)
        self.assertEqual(float(ratings["scripted"][2]), 1500.0)

        # In the fixed group with the mobs: the self-play share still belongs to the run's own checkpoints alone.
        checkpoints = sum(share for name, share in shares.items() if name.startswith("iteration-"))
        self.assertAlmostEqual(checkpoints, self.config.league_self_play, places=4)
        self.assertGreater(shares["vs-copy"], 0.0)

        # A fight against it judges the checkpoint as one against the scripted fighter does, both being fixed policies.
        self.assertEqual(league.evaluations[(50, "vs-copy")].fights, 1)

    def test_a_record_with_columns_this_side_does_not_use_still_reads(self):
        """The per-fight record grows to the right: what the agent held, its swaps, uses and shots, and its replay."""

        self.results(0, "50,eval,zombie,bow,-,win,200,-,lava,lava,bow,3,11,9,w00-f000400.json",
                     "50,train,creeper,sword,-,loss,300,opponent,flat,-,iron_sword,0,0,0,-")

        league = League(self.run, self.config)
        league.update(50)

        ground = {row[0]: [int(value) for value in row[1:]] for row in self.read("ground.csv")}

        self.assertEqual(league.rated, 1)
        self.assertEqual(ground["lava"], [1, 1, 0, 1, 0])
        self.assertEqual(league.training["creeper"], [0.0, 1.0])

    def armed(self, *loadouts: str) -> None:
        """A roster as a worker that says which loadouts it fields writes one, which is what makes the fights pairings."""

        rows = "".join(f"{name},loadout,1.00000\n" for name in loadouts)
        (self.run.path / "league" / "roster.csv").write_text(
            "opponent,kind,cap\nzombie,mob,1.00000\ncreeper,mob,1.00000\nghast,mob,1.00000\nscripted,scripted,1.00000\n" + rows,
            encoding="utf-8")

    def test_the_pairings_are_written_and_add_up_to_the_opponents_shares(self):
        self.armed("sword", "bow")

        league = League(self.run, self.config)
        league.update(50)

        pairs = {(row[0], row[1]): float(row[2]) for row in self.read("pairs.csv")}
        opponents = {row[0]: float(row[1]) for row in self.read("matchmaking.csv")}

        # Every loadout against every opponent and every checkpoint in the pool: two by seven.
        self.assertEqual(len(pairs), 14)
        self.assertAlmostEqual(sum(pairs.values()), 1.0, places=4)

        for opponent, share in opponents.items():
            self.assertAlmostEqual(share, sum(value for pair, value in pairs.items() if pair[1] == opponent), places=5)

        # The self-play share still belongs to the checkpoints, however it is split between the loadouts.
        checkpoints = sum(share for pair, share in pairs.items() if pair[1].startswith("iteration-"))
        self.assertAlmostEqual(checkpoints, self.config.league_self_play, places=4)

    def test_a_loadout_that_cannot_win_a_matchup_stops_being_given_it(self):
        """The whole point of pairing: the bow is hopeless against the ghast and even against the creeper, and the sword is
        even against both. The bow's ghast fights go to the fights that are close, and the sword's ghast fights stay."""

        self.armed("sword", "bow")

        self.results(0, *[f"50,train,ghast,bow,-,loss,600" for _ in range(40)])
        self.results(1, *[f"50,train,ghast,sword,-,{'win' if index % 2 else 'loss'},600" for index in range(40)])
        self.results(2, *[f"50,train,creeper,bow,-,{'win' if index % 2 else 'loss'},300" for index in range(40)])
        self.results(3, *[f"50,train,creeper,sword,-,{'win' if index % 2 else 'loss'},300" for index in range(40)])

        league = League(self.run, self.config)
        league.update(50)

        pairs = {(row[0], row[1]): float(row[2]) for row in self.read("pairs.csv")}

        self.assertLess(pairs[("bow", "ghast")], pairs[("sword", "ghast")] / 3.0)
        self.assertLess(pairs[("bow", "ghast")], pairs[("bow", "creeper")] / 3.0)

        # Still drawn, because a loadout that cannot win a matchup today may be able to in ten thousand iterations.
        self.assertGreater(pairs[("bow", "ghast")], 0.0)

        # And the opponent has not been written off with it: the sword's fights against the ghast are as close as ever.
        self.assertGreater(pairs[("sword", "ghast")], pairs[("sword", "zombie")])

    def test_the_pair_records_fade_and_survive_a_resume(self):
        self.armed("sword", "bow")
        self.results(0, "50,train,ghast,bow,-,loss,600", "50,train,creeper,sword,-,win,300")

        first = League(self.run, self.config)
        first.update(50)

        self.assertEqual(first.training_pairs[("bow", "ghast")], [0.0, 1.0])
        self.assertEqual(first.training_loadouts["sword"], [1.0, 1.0])

        resumed = League(self.run, self.config)

        self.assertEqual(resumed.training_pairs[("sword", "creeper")], [1.0, 1.0])
        self.assertEqual(resumed.training_loadouts["bow"], [0.0, 1.0])

        resumed.update(51)

        self.assertAlmostEqual(resumed.training_pairs[("bow", "ghast")][1], self.config.league_decay)

    def test_a_run_whose_workers_name_no_loadouts_is_matchmade_as_it_always_was(self):
        """A build too old to say which loadouts it fields draws the loadout itself, so there is nothing to pair with and no
        table to leave lying about."""

        league = League(self.run, self.config)
        league.update(50)

        rows = {row[0]: float(row[1]) for row in self.read("matchmaking.csv")}

        self.assertEqual(league.pair_shares, {})
        self.assertFalse((self.run.path / "league" / "pairs.csv").is_file())
        self.assertAlmostEqual(sum(rows.values()), 1.0, places=4)

    def test_a_pair_table_from_loadouts_a_run_no_longer_fields_is_taken_away(self):
        self.armed("sword", "bow")

        league = League(self.run, self.config)
        league.update(50)
        self.assertTrue((self.run.path / "league" / "pairs.csv").is_file())

        (self.run.path / "league" / "roster.csv").write_text(
            "opponent,kind,cap\nzombie,mob,1.00000\nscripted,scripted,1.00000\n", encoding="utf-8")

        league.update(51)

        self.assertFalse((self.run.path / "league" / "pairs.csv").is_file())

    def test_a_melee_loadout_is_never_paired_with_a_flyer_it_cannot_reach(self):
        """The one pairing that gets no share at all: something with nothing to shoot with against something nothing but a
        shot can reach. Every such fight is 2,400 ticks of timeout, so it drags a rating with a number that means nothing and
        spends a worker's minute on a question with one answer.

        Getting no share is also what keeps the frontier probe off it: the probe holds a hopeless pairing down to a trickle
        rather than to nothing, and a pairing that is never in the table is never probed."""

        (self.run.path / "league" / "roster.csv").write_text(
            "opponent,kind,cap,reach\n"
            "zombie,mob,1.00000,-\n"
            "ghast,mob,1.00000,unreachable\n"
            "phantom+zombie,squad,1.00000,unreachable\n"
            "scripted,scripted,1.00000,-\n"
            "sword,loadout,1.00000,melee\n"
            "bow,loadout,1.00000,-\n", encoding="utf-8")

        league = League(self.run, self.config)
        league.update(50)

        pairs = {(row[0], row[1]): float(row[2]) for row in self.read("pairs.csv")}
        shares = {row[0]: float(row[1]) for row in self.read("matchmaking.csv")}

        self.assertNotIn(("sword", "ghast"), pairs)
        self.assertNotIn(("sword", "phantom+zombie"), pairs)

        # And nothing else has gone with them: the bow still meets both, the sword still meets everything it can reach, and
        # the two unreachable opponents are still met and still rated.
        self.assertIn(("bow", "ghast"), pairs)
        self.assertIn(("bow", "phantom+zombie"), pairs)
        self.assertIn(("sword", "zombie"), pairs)
        self.assertIn(("sword", "scripted"), pairs)
        self.assertGreater(shares["ghast"], 0.0)

        # A ghast's whole share is the bow's, since the sword has none of it.
        self.assertAlmostEqual(shares["ghast"], pairs[("bow", "ghast")], places=4)
        self.assertAlmostEqual(sum(pairs.values()), 1.0, places=4)

        # A rung is not a different question: how hard a mob spawns has nothing to do with whether a sword can get at it.
        self.assertFalse(league.pairable("sword", "ghast(hard)"))
        self.assertFalse(league.pairable("sword", "ghast(easy)"))
        self.assertTrue(league.pairable("bow", "ghast(hard)"))
        self.assertTrue(league.pairable("sword", "zombie(hard)"))

    def test_a_build_too_old_to_say_what_is_out_of_reach_pairs_everything_as_before(self):
        """The `reach` column is newer than some workers. Without it nothing is barred, which is exactly what the league did
        before the rule, so a run resumed across the change keeps drawing what it was drawing."""

        self.armed("sword", "bow")

        league = League(self.run, self.config)
        league.update(50)

        pairs = {(row[0], row[1]): float(row[2]) for row in self.read("pairs.csv")}

        self.assertEqual(league.melee, set())
        self.assertEqual(league.unreachable, set())
        self.assertIn(("sword", "ghast"), pairs)

    def test_the_tables_hold_every_opponent_and_loadout(self):
        self.results(0, "50,eval,zombie,sword,-,win,200", "50,train,creeper,bow,-,loss,300", "50,eval,iteration-000025,axe,bow,win,500")

        league = League(self.run, self.config)
        league.update(50)

        opponents = {row[0]: row for row in self.read("opponents.csv")}
        loadouts = {row[0]: row for row in self.read("loadouts.csv")}
        evaluations = self.read("evaluations.csv")

        self.assertEqual(opponents["zombie"][4:6], ["1", "1"])
        self.assertEqual(opponents["creeper"][9:12], ["1", "0", "1"])
        self.assertEqual(set(loadouts), {"sword", "bow", "axe"})
        self.assertIn(["50", "iteration-000025", "1", "1", "0", "0", "0"], evaluations)


if __name__ == "__main__":
    unittest.main()

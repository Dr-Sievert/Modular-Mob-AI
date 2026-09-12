"""The league's arithmetic: Elo, matchmaking and the pool, and the league reading and resuming a run's results.

    python -m unittest discover -s tests       from trainer/, or scripts\\league.ps1 -Test
"""

from __future__ import annotations

import json
import math
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from mmai.league import League, Ratings, base, capped, checkpoint_name, expected, pool, shares, win_chance
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

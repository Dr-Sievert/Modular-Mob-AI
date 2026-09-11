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

from mmai.league import League, Ratings, checkpoint_name, expected, pool, shares, win_chance
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
        self.results(0, "50,eval,zombie,sword,-,win,200", "50,eval,creeper,bow,-,draw,90", "51,train,zombie,axe,-,loss,300")

        league = League(self.run, self.config)
        league.update(51)

        ratings = {row[0]: row for row in self.read("ratings.csv")}

        self.assertEqual(int(ratings["iteration-000050"][3]), 2)
        self.assertEqual(int(ratings["zombie"][3]), 1)
        self.assertLess(float(ratings["zombie"][2]), 1500.0)
        self.assertEqual(league.rated, 2)

    def test_matchmaking_covers_the_roster_and_the_pool_and_adds_up(self):
        league = League(self.run, self.config)
        league.update(50)

        rows = {row[0]: float(row[1]) for row in self.read("matchmaking.csv")}

        self.assertEqual(set(rows), {"zombie", "creeper", "scripted", checkpoint_name(0), checkpoint_name(25), checkpoint_name(50)})
        self.assertAlmostEqual(sum(rows.values()), 1.0, places=4)
        self.assertAlmostEqual(sum(rows[checkpoint_name(number)] for number in (0, 25, 50)), self.config.league_self_play, places=4)

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

package net.sievert.modularmobai.gametest.tests;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.league.Pairings;
import net.sievert.modularmobai.gametest.terrain.PouredHazards;

/**
 * Not the body: how the ground a fight happens on and the opponent it happens against are arranged. The lava poured beside a
 * hazard fight and drained again, which has to leave the site exactly as it found it because a site hosts a hundred fights;
 * and a league training fight drawn out of the trainer's shares as a loadout and an opponent together.
 *
 * <p>They are in the mechanics suite because this is the suite that checks a rule against the number it is supposed to give,
 * and because it boots in seconds. The shares themselves are the trainer's, tested by {@code scripts\league.ps1 -Test}.
 *
 * <p>See {@link Mechanics} for the arena, the brain and the rest the suite shares.
 */
@GameTestGroup
public class FightSetupGameTest {

    // ---------------------------------------------------------------------------------------------------------------
    // Lava poured for a fight, and taken away again
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A poured pool is nine blocks of lava, and every block of ground it touched is exactly as it was once it is drained.
     * That second part is the one that matters: a site hosts a hundred fights and the ground under it is a hard-linked
     * library shared between workers, so a pool left behind by one fight would still be there for the other ninety-nine, and
     * the library would rot a pool at a time.
     *
     * <p>Where a pool goes is not checked here. That needs open ground with a heightmap that means something, and this
     * arena is a closed bedrock box; the live run is what exercises the search. See {@link PouredHazards}.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void pouredLavaIsLavaAndLeavesNothingBehind(GameTestHelper helper) {

        ServerLevel level = helper.getLevel();

        // The floor of the arena, which is what a body in here stands on.
        BlockPos ground = helper.absolutePos(new BlockPos(4, 1, 4));

        // Everything the pool can reach: two blocks of margin either way, one below for the ground it makes solid, two
        // above for the plants it clears.
        AABB box = new AABB(ground.offset(-4, -3, -4)).minmax(new AABB(ground.offset(4, 4, 4)));
        Map<BlockPos, BlockState> before = new HashMap<>();

        BlockPos.betweenClosedStream(box).forEach(at -> before.put(at.immutable(), level.getBlockState(at)));

        PouredHazards.Pool pool = PouredHazards.pourAt(level, ground);

        int lava = 0;

        for (BlockPos at : before.keySet()) {

            lava += level.getBlockState(at).is(Blocks.LAVA) ? 1 : 0;
        }

        helper.assertValueEqual(lava, 9, "blocks of poured lava");

        PouredHazards.drain(level, pool);

        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {

            BlockState now = level.getBlockState(entry.getKey());

            helper.assertTrue(now == entry.getValue(), "Draining left " + now + " at " + entry.getKey()
                    + " where there had been " + entry.getValue());
        }

        helper.succeed();
    }

    /**
     * Lava that a pool fed but never held is swept up too, and draining says that it happened.
     *
     * <p>This is the case blast3 paid for. A pool is only flush if the ground beside it is level with it, and that was
     * checked at the middle column alone: one block of step at the rim puts a lava block over open air, from where it runs
     * three blocks and falls, out of the box the fight swept afterwards. Over 929,311 fights, 97.6% of the surface lava
     * beside a fight on ground labelled {@code flat} and all of it on {@code water} was flowing lava with no source anywhere
     * near — a spill from an earlier fight on the same site, which hosts a hundred of them — while ground labelled
     * {@code drop}, the one kind never poured on, had none at all. It cost 4.2% of every flat-ground fight against 0.014%
     * on drop ground.
     *
     * <p>What this arena can check is the sweep and what it reports: a block of lava three from the pool, which is as far as
     * lava spreads on land and was outside the old box. What it cannot check is either of the other two halves of the fix —
     * that a pool is only laid where the five by five under it is level, and that putting the ground back notifies the
     * neighbours so a flow works out its source has gone. The first needs open ground with a heightmap, and the second needs
     * room for a flow to run past the sweep; this is a bedrock box seven blocks across. The live run is what exercises both.
     * See {@link PouredHazards}.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void pouredLavaSweepsUpWhatItFedOutsideItself(GameTestHelper helper) {

        ServerLevel level = helper.getLevel();

        BlockPos ground = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos spilled = ground.offset(PouredHazards.spread(), 1, 0);
        BlockState was = level.getBlockState(spilled);

        PouredHazards.Pool pool = PouredHazards.pourAt(level, ground);

        level.setBlock(spilled, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);

        helper.assertTrue(PouredHazards.drain(level, pool),
                "Draining did not report that it had swept up lava the pool's own list never held");

        helper.assertTrue(level.getBlockState(spilled) == was,
                "Draining left " + level.getBlockState(spilled) + " at " + spilled + ", " + PouredHazards.spread()
                        + " blocks from the pool, where there had been " + was);

        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // How a league training fight is drawn
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The pairing draw against the shares the trainer wrote: a loadout and an opponent come out together, in proportion, the
     * same way twice from one seed, and nothing the table names is ever starved.
     *
     * <p>Here rather than in the league suite because it is arithmetic and not a fight, and this is the suite that checks a
     * rule against the number it is supposed to give. What it is guarding is the thing a wrong draw would hide: the shares
     * are what send a bow to the opponents a bow can learn from, so a draw that quietly ignored them, or that starved the
     * pairings on the floor, would cost a run its curriculum and nothing would fail. The shares themselves are the trainer's,
     * and tested over there; see trainer/tests/test_league.py.
     */
    @GameTest(template = Mechanics.ARENA, timeoutTicks = 100)
    public static void theLeagueDrawsAPairingByItsShare(GameTestHelper helper) {

        // A table shaped like the trainer's: the even fights carry most of it, the hopeless ones are held down to the floor,
        // and two rows name things this build does not field.
        Pairings pairings = Pairings.parse(List.of(
                "loadout,opponent,share,chance,fights,wins",
                "sword,creeper,0.300000,0.5100,120.0,61.0",
                "bow,creeper,0.250000,0.4700,90.0,42.0",
                "sword,zombie,0.200000,0.5500,110.0,60.0",
                "bow,zombie,0.240000,0.4500,80.0,36.0",
                "sword,ghast,0.005000,0.0200,40.0,1.0",
                "bow,ghast,0.005000,0.0300,30.0,1.0",
                "bow,warden,0.900000,0.0000,10.0,0.0",
                "netherite_sword,creeper,0.900000,0.5000,10.0,5.0",
                ""), List.of("creeper", "zombie", "ghast")::contains, List.of("sword", "bow")::contains);

        helper.assertValueEqual(pairings.pairings().size(), 6, "pairings this build can field");
        helper.assertTrue(Math.abs(pairings.total() - 1.0D) < 1.0E-9D, "The shares add up to " + pairings.total() + ", not one");

        Map<Pairings.Pairing, Integer> drawn = draws(pairings, 20_000L, 20_000);
        Map<Pairings.Pairing, Integer> again = draws(pairings, 20_000L, 20_000);

        helper.assertTrue(drawn.equals(again), "The same seed drew a different set of fights the second time");

        for (int index = 0; index < pairings.pairings().size(); index++) {

            Pairings.Pairing pairing = pairings.pairings().get(index);
            int count = drawn.getOrDefault(pairing, 0);
            double expected = 20_000 * pairings.share(index);

            // Four standard deviations of a binomial draw either way, and never none: the floor is only worth having if a
            // pairing on it keeps coming round.
            double spread = 4.0D * Math.sqrt(expected * (1.0D - pairings.share(index)));

            helper.assertTrue(count > 0, "Nothing was ever drawn for " + pairing.loadout() + " against " + pairing.opponent());
            helper.assertTrue(Math.abs(count - expected) <= spread, pairing.loadout() + " against " + pairing.opponent()
                    + " came up " + count + " times, not the " + Math.round(expected) + " its share asks for");
        }

        // The point of the whole thing: the fights go to the pairings where the result is close, not evenly over the table.
        int even = drawn.get(new Pairings.Pairing("sword", "creeper")) + drawn.get(new Pairings.Pairing("bow", "creeper"));
        int hopeless = drawn.get(new Pairings.Pairing("sword", "ghast")) + drawn.get(new Pairings.Pairing("bow", "ghast"));

        helper.assertTrue(even > 20 * hopeless, "The even pairings took " + even + " fights against the hopeless ones' " + hopeless);

        helper.assertTrue(Pairings.NONE.draw(RandomSource.create(1L)) == null, "An empty table drew something");
        helper.succeed();
    }

    /** How often each pairing came up in so many draws from one seed. */
    private static Map<Pairings.Pairing, Integer> draws(Pairings pairings, long seed, int draws) {

        RandomSource random = RandomSource.create(seed);
        Map<Pairings.Pairing, Integer> counts = new HashMap<>();

        for (int draw = 0; draw < draws; draw++) {

            counts.merge(pairings.draw(random), 1, Integer::sum);
        }

        return counts;
    }
}

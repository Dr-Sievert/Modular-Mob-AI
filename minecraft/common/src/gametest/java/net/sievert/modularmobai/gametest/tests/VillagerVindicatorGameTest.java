package net.sievert.modularmobai.gametest.tests;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.npc.Villager;
import net.sievert.modularmobai.gametest.ArenaRecorder;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.GameTestTuning;
import net.sievert.modularmobai.gametest.RepeatGameTest;
import net.sievert.modularmobai.gametest.util.TestDurationStats;

// Tests are written once here and run on every loader. Nothing in this class knows which loader it is running on.
@GameTestGroup
public class VillagerVindicatorGameTest {

    /**
     * The outer edge length of one arena, matching the arena structure. The shell is bedrock, so the fight happens in the
     * {@code SIZE - 2} cube inside. The box itself lives in the structure file rather than being placed here: the framework
     * clears every plot and places the structure anyway, so building it from the test would only repeat that work a block
     * at a time.
     */
    private static final int SIZE = 9;

    /**
     * The floor the mobs stand on. A game test structure block sits one block below the structure it places, so a block at
     * structure height y ends up at helper height y + 1. The bedrock floor is structure height 0, which puts the first air
     * layer above it at helper height 2. Spawning at helper height 1 buries both mobs in the floor.
     */
    private static final int FLOOR_Y = 2;

    /**
     * Both mobs stand on the floor, in the middle of opposite walls, one block clear of the inner face so that neither
     * starts pressed against bedrock.
     */
    private static final BlockPos VILLAGER_POS = new BlockPos(SIZE / 2, FLOOR_Y, 2);
    private static final BlockPos VINDICATOR_POS = new BlockPos(SIZE / 2, FLOOR_Y, SIZE - 3);

    /**
     * A villager outruns a vindicator, so the kill depends on the villager being cornered rather than caught, and how long
     * that takes varies. Two minutes is well past any run that is going to end at all.
     */
    private static final int TIMEOUT_TICKS = 2400;

    /**
     * Gathers the time every arena took. Individual arenas report nothing; only the spread across the field is printed,
     * once the last arena lands.
     */
    private static final TestDurationStats TIME_TO_DEATH =
            new TestDurationStats("Villager time to death", GameTestTuning.arenasInShard(GameTestTuning.arenaCount()));

    @GameTest(template = "arena", timeoutTicks = TIMEOUT_TICKS)
    @RepeatGameTest
    public static void vindicatorKillsVillager(GameTestHelper helper, int arena) {

        final Villager villager = helper.spawn(EntityType.VILLAGER, VILLAGER_POS);
        final Vindicator vindicator = helper.spawn(EntityType.VINDICATOR, VINDICATOR_POS);

        // spawn() places the entity without running finalizeSpawn, so a vindicator would arrive empty handed and hit for
        // far less than one that spawned on its own. Running it here hands it the iron axe it is supposed to carry.
        vindicator.finalizeSpawn(
                helper.getLevel(),
                helper.getLevel().getCurrentDifficultyAt(helper.absolutePos(VINDICATOR_POS)),
                MobSpawnType.EVENT,
                null
        );

        // Without this the vindicator spends its first ticks acquiring a target it can already see.
        vindicator.setTarget(villager);

        // The wait is on the villager no longer being alive rather than on it being gone: an entity stays in the level for
        // its death animation, which would add those ticks to the time being measured. Holding the villager this arena
        // spawned, rather than asking the level what is present, is also what keeps the arenas from reading each other.
        // The wait runs once per tick, which makes it the natural place to sample the arena. Positions are taken relative
        // to the arena's own corner so every arena reads the same way wherever it was placed in the world.
        final BlockPos origin = helper.absolutePos(BlockPos.ZERO);

        helper.startSequence()
                .thenWaitUntil(() -> {

                    ArenaRecorder.record(
                            arena, helper.getTick(),
                            villager.getX() - origin.getX(), villager.getY() - origin.getY(), villager.getZ() - origin.getZ(),
                            villager.getHealth(),
                            vindicator.getX() - origin.getX(), vindicator.getY() - origin.getY(), vindicator.getZ() - origin.getZ(),
                            vindicator.getHealth()
                    );

                    if (villager.isAlive()) {

                        throw new GameTestAssertException("Villager is still alive");
                    }
                })
                .thenExecute(() -> TIME_TO_DEATH.record(helper.getTick()))
                .thenSucceed();
    }

}

package net.sievert.modularmobai.gametest.tests;

import java.util.List;
import java.util.function.IntPredicate;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.sievert.modularmobai.allegiance.Allegiance;
import net.sievert.modularmobai.arena.Loadout;
import net.sievert.modularmobai.arena.Loadouts;
import net.sievert.modularmobai.brain.AgentDriver;
import net.sievert.modularmobai.brain.Brain;
import net.sievert.modularmobai.brain.BrainStep;
import net.sievert.modularmobai.brain.Brains;
import net.sievert.modularmobai.brain.Models;
import net.sievert.modularmobai.brain.NeuralBrain;
import net.sievert.modularmobai.brain.ScriptedBrain;
import net.sievert.modularmobai.brain.schema.ActionSchema;
import net.sievert.modularmobai.brain.schema.BeastSchema;
import net.sievert.modularmobai.brain.schema.EnemySlots;
import net.sievert.modularmobai.brain.schema.Species;
import net.sievert.modularmobai.entity.ModEntities;
import net.sievert.modularmobai.entity.agent.AgentMob;
import net.sievert.modularmobai.entity.agent.BeastMob;
import net.sievert.modularmobai.gametest.GameTestGroup;
import net.sievert.modularmobai.gametest.util.TestTicks;

/**
 * The agent in a real game: the networks the jar carries, {@code /mmai}, sides, and the loadouts that keep a bow firing.
 *
 * <p>Commands go through the server's own dispatcher, parsed as a player's chat would be, so what is tested is the
 * command a person types. Sides are vanilla teams made through {@link Allegiance}, as a game test or the league would
 * make them, and every test takes the teams it made down again, since the scoreboard outlives the test.
 *
 * <p>The fights here are short and in the arena's closed box, a bedrock floor at height one, room from two to eight, a
 * roof, and walls round an inside seven blocks across, from one to seven. The roof is not what keeps a zombie out of the
 * sun — on a test's first tick the light of the box it was just given has not been worked out, so the sky shows through
 * bedrock — and that is why every game test runs at midnight; see {@code GameTestServerMixin}.
 *
 * <p>Run with {@code -Psuite=play}, which is what {@code scripts\test.ps1 -Play} does.
 */
@GameTestGroup
public class PlayGameTest {

    private static final String ARENA = "arena";

    /** A brain name nothing answers to, for the refusals. Asserted absent where it is used, never assumed. */
    private static final String ABSENT = "no_such_network";

    // ---------------------------------------------------------------------------------------------------------------
    // Networks by name
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The build put the repository's networks into the jar, and every one of them loads by its own name: best is the one
     * that won the most, scripted is the hand written fighter, and a name that leads nowhere is refused, saying what there
     * is instead.
     *
     * <p>Nothing here names a network, because which ones are published changes with every run: it asks the jar what it
     * carries and holds all of it to the rule. That matters for the one mistake this catches — a network left in
     * {@code models\} after the layout it was trained against changed. The game refuses such a file by its schema id, which
     * is the id doing its job, and a suite that named its network instead would fail here, in the two tests below, and
     * anywhere else {@code best} is asked for, with three messages and no statement of what is actually wrong. Iterating
     * fails once, and names the network to retire.
     */
    @GameTest(template = ARENA)
    public static void networksInTheJarLoadByName(GameTestHelper helper) {

        List<String> bundled = Models.bundled();

        helper.assertFalse(bundled.isEmpty(), "The jar carries no networks");

        for (String name : bundled) {

            Brain network = loads(helper, name);
            helper.assertTrue(network instanceof NeuralBrain, name + " in the jar is not a network");
            helper.assertTrue(((NeuralBrain) network).weights().iteration() > 0, name + " in the jar is untrained");
        }

        // best is a name of its own for one of them, picked by the win rate each model.json records, the same pick
        // scripts\play.ps1 makes; naming it either way has to reach the one brain, so both share a forward pass.
        String bestName = Models.best();

        helper.assertTrue(bestName != null && bundled.contains(bestName), "The jar names no best network");

        Brain best = loads(helper, "best");

        helper.assertTrue(best instanceof NeuralBrain, "best is not a network");
        helper.assertTrue(best == Brains.named(bestName), "best and " + bestName + " load two copies of one network");

        helper.assertTrue(Brains.named("scripted") instanceof ScriptedBrain, "scripted is not the scripted fighter");
        helper.assertTrue(Brains.known().containsAll(List.of("scripted", "best")) && Brains.known().containsAll(bundled),
                "known() leaves names out: " + String.join(", ", Brains.known()));

        // A name nothing answers to, asserted absent rather than assumed: a published network could be called anything,
        // and a refusal test that named a real one, or one that might be published later, would pass for the wrong reason.
        // The two below it hold whatever is published: a network's name can hold no slash, so neither can ever be one.
        helper.assertFalse(Brains.known().contains(ABSENT), "'" + ABSENT + "' is a network here, so it refuses nothing");

        refused(helper, () -> Brains.named(ABSENT), "a network nobody has");
        refused(helper, () -> Brains.named("../models/" + bestName), "a name that climbs out of its folder");
        refused(helper, () -> Brains.named("nowhere/at/all.mbw"), "a weight file that is not there");

        helper.succeed();
    }

    /**
     * An agent out in the world acts on the network the jar carries: armed with a sword and given best, which is the
     * network scripts\play.ps1 would have started the game on, it goes for a zombie across the box and hurts it.
     *
     * <p>Two things here are the way they are because of what the network is, and both were measured rather than guessed.
     * <b>The zombie has its free will</b>, where every other fight in this suite uses a dummy: the enemy slots now carry
     * whether an opponent has the agent as its target, and a network trained on the league has never met something that
     * stands there ignoring it. Against a dummy this one closes to a block, swings twenty times in two hundred ticks and
     * lands none of them — it presses attack, so the body is doing its part, and the aim is simply not on a thing that is
     * not fighting back. <b>It is given a fight's length to land one</b>, the 1,200 ticks every training fight was given,
     * because the first blow takes two to four hundred of them in a bedrock box seven across, which is nothing like the
     * open ground it was judged on. What this proves is that the jar's network drives a playable agent and does damage; how
     * well it fights is what {@code scripts\bench.ps1} is for.
     */
    @GameTest(template = ARENA, timeoutTicks = 1200)
    public static void worldAgentFightsOnTheBundledNetwork(GameTestHelper helper) {

        AgentMob agent = worldAgent(helper, new BlockPos(4, 2, 1), 0.0F);
        agent.equip(Loadout.SWORD);
        agent.setBrainName("best");

        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 5));

        // Counted for the failure message only. "The zombie is unhurt" cannot tell a network that never moved from one
        // that closed and swung and missed, and those two want opposite answers; the numbers above came out of this.
        int[] swings = {0};
        int[] ticks = {0};

        helper.succeedWhen(() -> {

            ticks[0]++;

            if (agent.executed().attacked) {

                swings[0]++;
            }

            helper.assertTrue(agent.brain().brain() == Brains.named("best"),
                    "The agent is not on best, which is " + Brains.describe(Brains.named("best")));
            helper.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "The zombie is unhurt after " + ticks[0]
                    + " ticks: " + swings[0] + " swings, " + String.format(java.util.Locale.ROOT, "%.2f",
                    agent.distanceTo(zombie)) + " blocks away, slots " + agent.brain().enemySlots().inRangeCount());
        });
    }

    /**
     * Out in the world with nobody in view, an agent stands where it was put whatever its brain says, and the moment
     * something to fight is in view it acts. Driven here by a brain that only ever walks forward.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void aloneAnAgentStandsStill(GameTestHelper helper) {

        AgentMob agent = worldAgent(helper, new BlockPos(4, 2, 1), 0.0F);
        agent.brain().use(WALKER);

        Vec3[] start = new Vec3[1];

        run(helper, tick -> {

            if (tick == 10) {

                start[0] = agent.position();
            }

            if (tick == 30) {

                helper.assertTrue(agent.position().distanceTo(start[0]) < 0.01D, "An agent with nobody in view walked off");
                helper.assertTrue(agent.brain().enemySlots().isEmpty(), "Something is in an empty box's view");
                helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 7));
            }

            if (tick == 45) {

                helper.assertFalse(agent.brain().enemySlots().isEmpty(), "The zombie never took a slot");
                helper.assertTrue(agent.position().distanceTo(start[0]) > 0.3D, "The agent stood still with a zombie in view");
                return true;
            }

            return false;
        });
    }

    /**
     * A second body, driven through the same plumbing as the first, and refused a brain that was not made for it.
     *
     * <p>The beast has no hands: no hotbar to see, no slot to choose, no use buttons, and so a narrower observation and a
     * shorter action vector than the humanoid's. This is the whole of what that costs at the game's end — it is spawned,
     * driven and it moves — and it is also where the refusal is proved, because a brain for the wrong body is the mistake
     * that will be made and the one failure that would not look like one.
     */
    @GameTest(template = ARENA)
    public static void aSecondBodyIsDrivenAndARefusedBrainIsNamed(GameTestHelper helper) {

        BeastMob beast = helper.spawn(ModEntities.beastAgent(), new BlockPos(4, 2, 1));
        face(beast, 0.0F);

        helper.assertTrue(beast.species() == Species.BEAST, "The beast is not of its own species");
        helper.assertTrue(beast.species().obsDim() < Species.HUMANOID.obsDim(),
                "The beast should see less than the humanoid, having no hands");
        helper.assertTrue(beast.species().schemaId() != Species.HUMANOID.schemaId(),
                "Two bodies must not share a schema id, or either one's weights would drive the other");

        // A humanoid brain on a beast: refused, by name, rather than handed an observation that means something else.
        beast.brain().use(WALKER);

        try {

            AgentDriver.tick(helper.getLevel());
            helper.fail("A humanoid's brain was allowed to drive a beast");
        }

        catch (IllegalStateException expected) {

            helper.assertTrue(expected.getMessage().contains("humanoid") && expected.getMessage().contains("beast"),
                    "The refusal has to name both bodies, and said: " + expected.getMessage());
        }

        // And its own brain drives it. Forward is forward whatever the body, so it should walk — once there is something in
        // view, since an agent out in the world stands still with nobody to fight whatever its brain says, beast or not.
        beast.brain().use(BEAST_WALKER);
        helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 7));

        Vec3[] start = new Vec3[1];

        run(helper, tick -> {

            if (tick == 10) {

                start[0] = beast.position();
                helper.assertFalse(beast.brain().enemySlots().isEmpty(),
                        "The beast sees nothing, so the enemy slots every body shares are not being written for it");
            }

            if (tick == 35) {

                helper.assertTrue(beast.position().distanceTo(start[0]) > 0.3D,
                        "The beast never moved, so nothing is driving it");
                helper.assertTrue(beast.executed().moveForward > 0.5F,
                        "The beast's body never saw the forward the brain asked for");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * /mmai spawn makes a playable agent where it is told, armed with the loadout and on the brain it names, and one
     * that will never despawn. /mmai brain hands it back to the default with default.
     */
    @GameTest(template = ARENA)
    public static void spawnCommandMakesAnArmedAgentOnItsBrain(GameTestHelper helper) {

        Vec3 at = helper.absoluteVec(new Vec3(4.5D, 2.0D, 4.5D));
        command(helper, "mmai spawn bow_infinity scripted " + coordinates(at));

        List<AgentMob> agents = helper.getEntities(ModEntities.agentMob());
        helper.assertValueEqual(agents.size(), 1, "agents spawned");

        AgentMob agent = agents.get(0);
        ItemStack bow = agent.getHotbarItem(0);

        helper.assertTrue(bow.is(Items.BOW), "The agent is not holding a bow");
        helper.assertValueEqual(infinity(helper, bow), 1, "Infinity on the bow");
        helper.assertValueEqual(agent.getHotbarItem(1).getCount(), 1, "arrows beside the Infinity bow");
        helper.assertValueEqual(agent.brainName(), "scripted", "brain name");
        helper.assertValueEqual(agent.loadoutName(), Loadouts.BOW_INFINITY, "loadout name");
        helper.assertTrue(agent.brain().brain() instanceof ScriptedBrain, "The agent is not on the scripted fighter");
        helper.assertTrue(agent.requiresCustomPersistence(), "A playable agent can despawn");
        helper.assertTrue(agent.position().distanceTo(at) < 0.01D, "The agent is not where it was sent");

        command(helper, "mmai brain " + agent.getStringUUID() + " default");
        helper.assertTrue(agent.brainName() == null, "default left the agent a brain of its own");

        helper.succeed();
    }

    /**
     * From a command block or a data pack's function, which run at the permission a function gets and with relative
     * coordinates, /mmai spawn works the same.
     */
    @GameTest(template = ARENA)
    public static void spawnCommandWorksFromAFunction(GameTestHelper helper) {

        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack function = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(helper.absoluteVec(new Vec3(2.5D, 2.0D, 4.5D)))
                .withPermission(2)
                .withSuppressedOutput();

        try {

            server.getCommands().getDispatcher().execute("mmai spawn sword default ~2 ~ ~", function);
        }

        catch (CommandSyntaxException exception) {

            throw new GameTestAssertException("/mmai spawn was refused from a function: " + exception.getMessage());
        }

        helper.assertValueEqual(helper.getEntities(ModEntities.agentMob()).size(), 1, "agents spawned from a function");
        helper.succeed();
    }

    /** A bare /mmai spawn arms the agent with the config's loadout, the sword unless someone changed it. */
    @GameTest(template = ARENA)
    public static void bareSpawnArmsWithTheConfigsLoadout(GameTestHelper helper) {

        Vec3 at = helper.absoluteVec(new Vec3(4.5D, 2.0D, 4.5D));
        command(helper, "mmai spawn sword default " + coordinates(at));

        AgentMob agent = helper.getEntities(ModEntities.agentMob()).get(0);

        helper.assertTrue(agent.getHotbarItem(0).is(Items.IRON_SWORD), "The agent is not holding a sword");
        helper.assertTrue(agent.brainName() == null, "default gave the agent a brain of its own");
        helper.assertFalse(agent.isLeftHanded(), "The agent spawned left handed");
        helper.succeed();
    }

    /** /mmai loadout arms an agent's whole hotbar and a vanilla mob's two hands, and refuses a loadout that is none. */
    @GameTest(template = ARENA)
    public static void loadoutCommandArmsAgentsAndMobs(GameTestHelper helper) {

        AgentMob agent = worldAgent(helper, new BlockPos(2, 2, 2), 0.0F);
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(6, 2, 6));

        command(helper, "mmai loadout " + agent.getStringUUID() + " sword_and_shield");
        command(helper, "mmai loadout " + zombie.getStringUUID() + " axe_and_shield");

        helper.assertTrue(agent.getHotbarItem(0).is(Items.IRON_SWORD), "The agent's first slot is not the sword");
        helper.assertTrue(agent.getOffhandItem().is(Items.SHIELD), "The agent's off hand is not the shield");
        helper.assertValueEqual(agent.loadoutName(), Loadout.SWORD_AND_SHIELD.name(), "the agent's loadout");
        helper.assertTrue(zombie.getMainHandItem().is(Items.IRON_AXE), "The zombie is not holding the axe");
        helper.assertTrue(zombie.getOffhandItem().is(Items.SHIELD), "The zombie's off hand is not the shield");

        commandFails(helper, "mmai loadout " + agent.getStringUUID() + " trident", "a loadout that is none");
        helper.succeed();
    }

    /**
     * /mmai brain gives an agent a network by name, and refuses a name that leads nowhere without changing anything. The
     * network it hands over is whichever one the jar calls best, and the one it refuses is a name nothing is published
     * under, so neither half of this depends on what {@code models\} happens to hold.
     */
    @GameTest(template = ARENA)
    public static void brainCommandRefusesWhatLeadsNowhere(GameTestHelper helper) {

        AgentMob agent = worldAgent(helper, new BlockPos(4, 2, 4), 0.0F);

        command(helper, "mmai brain " + agent.getStringUUID() + " best");
        helper.assertValueEqual(agent.brainName(), "best", "brain name");
        helper.assertTrue(agent.brain().brain() instanceof NeuralBrain, "best did not put the agent on a network");

        commandFails(helper, "mmai brain " + agent.getStringUUID() + " " + ABSENT, "a network nobody has");
        helper.assertValueEqual(agent.brainName(), "best", "brain name after a refused one");

        commandFails(helper, "mmai brain " + agent.getStringUUID() + " \"nowhere/at/all.mbw\"", "a weight file that is not there");
        helper.succeed();
    }

    /**
     * An agent keeps its brain and what it carries through being saved and loaded: the hotbar as it stands, arrows spent
     * and all, never armed afresh. A /summon that names a loadout and a brain gets both.
     */
    @GameTest(template = ARENA)
    public static void agentKeepsBrainAndLoadoutThroughSaving(GameTestHelper helper) {

        AgentMob agent = worldAgent(helper, new BlockPos(2, 2, 2), 0.0F);
        agent.equip(Loadout.BOW);
        agent.setBrainName("scripted");
        agent.getHotbarItem(1).shrink(4);

        CompoundTag saved = agent.saveWithoutId(new CompoundTag());
        AgentMob loaded = ModEntities.agentMob().create(helper.getLevel());
        loaded.load(saved);

        helper.assertValueEqual(loaded.brainName(), "scripted", "brain name after loading");
        helper.assertValueEqual(loaded.loadoutName(), Loadout.BOW.name(), "loadout name after loading");
        helper.assertTrue(loaded.getHotbarItem(0).is(Items.BOW), "The bow was lost in saving");
        helper.assertValueEqual(loaded.getHotbarItem(1).getCount(), 60, "arrows after loading");

        Vec3 at = helper.absoluteVec(new Vec3(6.5D, 2.0D, 6.5D));
        command(helper, "summon modular_mob_ai:agent_mob " + coordinates(at)
                + " {Loadout:\"crossbow\",BrainName:\"scripted\"}");

        AgentMob summoned = helper.getEntities(ModEntities.agentMob()).stream().filter(found -> found != agent).findFirst()
                .orElseThrow(() -> new GameTestAssertException("/summon made no agent"));

        helper.assertTrue(summoned.getHotbarItem(0).is(Items.CROSSBOW), "The summoned agent is not holding a crossbow");
        helper.assertValueEqual(summoned.getHotbarItem(1).getCount(), 64, "the summoned agent's arrows");
        helper.assertValueEqual(summoned.brainName(), "scripted", "the summoned agent's brain name");
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sides
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * What the agent sees follows the sides. An ally standing right beside it never takes a slot, an enemy further off
     * does, and the moment the ally is set against it, it takes a slot too.
     */
    @GameTest(template = ARENA, timeoutTicks = 40)
    public static void onlyEnemiesAreInTheView(GameTestHelper helper) {

        AgentMob agent = trainingAgent(helper, new BlockPos(4, 2, 2), STILL);
        AgentMob ally = trainingAgent(helper, new BlockPos(4, 2, 3), STILL);
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 6));

        PlayerTeam side = Allegiance.side(agent, ally);
        EnemySlots view = agent.brain().enemySlots();

        run(helper, tick -> {

            if (tick == 3) {

                helper.assertValueEqual(view.inRangeCount(), 1, "enemies in view with an ally beside it");
                helper.assertTrue(occupies(view, zombie), "The zombie has no slot");
                helper.assertFalse(occupies(view, ally), "The ally has a slot");

                Allegiance.enemy(List.of(agent), List.of(ally));
            }

            if (tick == 6) {

                helper.assertValueEqual(view.inRangeCount(), 2, "enemies in view once the ally is one");
                helper.assertTrue(occupies(view, ally), "The ally turned enemy has no slot");

                disband(agent, ally, zombie);
                Allegiance.disband(side);
                return true;
            }

            return false;
        });
    }

    /**
     * Two agents on the scripted fighter, put on one side with /mmai ally, stand side by side and never fight. Set against
     * each other with /mmai enemy, they do.
     */
    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void alliesNeverFightAndEnemiesDo(GameTestHelper helper) {

        AgentMob first = worldAgent(helper, new BlockPos(3, 2, 4), 0.0F);
        AgentMob second = worldAgent(helper, new BlockPos(5, 2, 4), 0.0F);

        for (AgentMob agent : List.of(first, second)) {

            agent.equip(Loadout.SWORD);
            agent.setBrainName("scripted");
        }

        command(helper, "mmai ally " + first.getStringUUID() + " " + second.getStringUUID());
        helper.assertTrue(first.getTeam() != null && first.getTeam() == second.getTeam(), "ally left them on different sides");

        run(helper, tick -> {

            if (tick < 100) {

                helper.assertTrue(unhurt(first) && unhurt(second), "Allies hurt each other");
                return false;
            }

            if (tick == 100) {

                command(helper, "mmai enemy " + first.getStringUUID() + " " + second.getStringUUID());
                helper.assertTrue(Allegiance.opposed(first, second), "enemy left them on one side");
                return false;
            }

            if (!unhurt(first) || !unhurt(second)) {

                disband(first, second);
                return true;
            }

            return false;
        });
    }

    /**
     * Vanilla mobs on opposing teams go after each other, whatever they are: a zombie and a vindicator, which vanilla
     * has leave each other alone, fight once they are on different sides.
     */
    @GameTest(template = ARENA, timeoutTicks = 300)
    public static void mobsOnOpposingTeamsFight(GameTestHelper helper) {

        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(2, 2, 2));
        Vindicator vindicator = helper.spawn(EntityType.VINDICATOR, new BlockPos(6, 2, 6));

        Allegiance.enemy(List.of(zombie), List.of(vindicator));

        helper.succeedWhen(() -> {

            helper.assertTrue(zombie.getTarget() == vindicator || vindicator.getTarget() == zombie, "Neither went after the other");
            helper.assertTrue(!unhurt(zombie) || !unhurt(vindicator), "Neither was hurt");
            disband(zombie, vindicator);
        });
    }

    /**
     * On one side, what vanilla would have fight leaves each other alone: an iron golem, which goes for any zombie it
     * sees, never touches one on its own team.
     */
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void mobsOnOneTeamLeaveEachOtherAlone(GameTestHelper helper) {

        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, new BlockPos(2, 2, 2));
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(6, 2, 6));

        PlayerTeam side = Allegiance.side(golem, zombie);

        run(helper, tick -> {

            helper.assertTrue(golem.getTarget() != zombie && zombie.getTarget() != golem, "Allies went after each other");
            helper.assertTrue(unhurt(golem) && unhurt(zombie), "Allies hurt each other by tick " + tick + ": the golem at "
                    + golem.getHealth() + " from " + golem.getLastDamageSource() + ", the zombie at " + zombie.getHealth()
                    + " from " + zombie.getLastDamageSource());

            if (tick == 150) {

                Allegiance.disband(side);
                return true;
            }

            return false;
        });
    }

    /**
     * A member of the other side is an enemy whatever it is. The agent leaves a cow alone, as it always has, until the
     * cow is on the other side; then it goes for it.
     */
    @GameTest(template = ARENA, timeoutTicks = 400)
    public static void agentFightsTheOtherSideWhateverItIs(GameTestHelper helper) {

        AgentMob agent = worldAgent(helper, new BlockPos(4, 2, 1), 0.0F);
        agent.equip(Loadout.SWORD);
        agent.setBrainName("scripted");

        Cow cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(4, 2, 5));

        run(helper, tick -> {

            if (tick < 60) {

                helper.assertTrue(unhurt(cow) && agent.brain().enemySlots().isEmpty(), "The agent went for a cow on no side");
                return false;
            }

            if (tick == 60) {

                Allegiance.enemy(List.of(agent), List.of(cow));
                return false;
            }

            if (!unhurt(cow)) {

                disband(agent, cow);
                return true;
            }

            return false;
        });
    }

    /**
     * Friendly fire off keeps the agent from hurting its own side, as it keeps a player from hurting a teammate: a blow
     * on an ally does nothing. With friendly fire back on, the same blow lands.
     */
    @GameTest(template = ARENA, timeoutTicks = 100)
    public static void friendlyFireOffSparesTheSide(GameTestHelper helper) {

        AgentMob agent = trainingAgent(helper, new BlockPos(4, 2, 2), null);
        AgentMechanicsGameTest.HeldControls.drive(agent);
        Loadout.SWORD.equip(agent);

        Mob zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 4));
        PlayerTeam side = Allegiance.side(agent, zombie);

        helper.assertFalse(side.isAllowFriendlyFire(), "A team of the mod's own has friendly fire on");

        // The one thing besides the agent that could take this zombie's health is the sun, and checking it is not the same
        // as checking the roof: the box has one, but the sky light of a plot placed this tick is not worked out until the
        // next, so canSeeSky answers yes under bedrock on the tick the zombie takes its first. What has to hold is that
        // there is no sun to catch it, which is why every game test runs at midnight; see GameTestServerMixin.
        helper.assertFalse(helper.getLevel().isDay(), "The suite is in daylight, so this zombie can catch fire under a roof");

        run(helper, tick -> {

            agent.controls().attack = tick == 20 || tick == 45;

            if (tick == 21) {

                helper.assertTrue(agent.executed().attacked, "The agent never swung");

                // What took the health stays in the message. This test failed about one run in seven and the message said
                // only that the zombie was hurt, which reads the same whether a swing was let through or the box burned it;
                // it was the sun, through a roof, on the first tick. The sun is gone now and the assertion above says so,
                // but the next thing to take a zombie's health here should name itself the first time it happens.
                helper.assertTrue(unhurt(zombie), "The agent hurt its own side with friendly fire off: the zombie at "
                        + zombie.getHealth() + " of " + zombie.getMaxHealth() + " from " + zombie.getLastDamageSource());
                side.setAllowFriendlyFire(true);
            }

            if (tick == 46) {

                helper.assertFalse(unhurt(zombie), "With friendly fire on, the blow did not land");
                Allegiance.disband(side);
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Loadouts
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * The Infinity bow never runs out: three full draws, three arrows in the air, and the one arrow in the hotbar still
     * there. What flies is an arrow only a creative player can pick up. The loadouts fights train with keep their 64.
     */
    @GameTest(template = ARENA, timeoutTicks = 150)
    public static void infinityBowNeverRunsOut(GameTestHelper helper) {

        AgentMob agent = trainingAgent(helper, new BlockPos(4, 2, 1), null);
        AgentMechanicsGameTest.HeldControls.drive(agent);
        Loadouts.byName(Loadouts.BOW_INFINITY, helper.getLevel().registryAccess()).orElseThrow().equip(agent);

        helper.assertValueEqual(Loadout.BOW.hotbar().get(1).getCount(), 64, "arrows in the bow loadout fights train with");
        helper.assertValueEqual(Loadout.CROSSBOW.hotbar().get(1).getCount(), 64, "arrows in the crossbow loadout fights train with");

        run(helper, tick -> {

            // Twenty ticks drawn and let go, three times over.
            agent.controls().use = tick < 90 && tick % 30 < 21;

            if (tick == 100) {

                List<AbstractArrow> arrows = arrows(helper);

                helper.assertValueEqual(arrows.size(), 3, "arrows loosed");
                helper.assertValueEqual(agent.getHotbarItem(1).getCount(), 1, "arrows left beside the Infinity bow");

                for (AbstractArrow arrow : arrows) {

                    helper.assertValueEqual(arrow.pickup, AbstractArrow.Pickup.CREATIVE_ONLY, "who can pick an Infinity arrow up");
                }

                return true;
            }

            return false;
        });
    }

    /** The same for the crossbow: wound and fired twice on the one arrow, which is still there. */
    @GameTest(template = ARENA, timeoutTicks = 150)
    public static void infinityCrossbowNeverRunsOut(GameTestHelper helper) {

        AgentMob agent = trainingAgent(helper, new BlockPos(4, 2, 1), null);
        AgentMechanicsGameTest.HeldControls.drive(agent);
        Loadouts.byName(Loadouts.CROSSBOW_INFINITY, helper.getLevel().registryAccess()).orElseThrow().equip(agent);

        run(helper, tick -> {

            // Wound for thirty ticks and let go, which loads it, then a single press, which fires it; twice.
            agent.controls().use = tick < 30 || tick == 35 || tick >= 45 && tick < 75 || tick == 80;

            if (tick == 90) {

                helper.assertValueEqual(arrows(helper).size(), 2, "bolts fired");
                helper.assertValueEqual(agent.getHotbarItem(1).getCount(), 1, "arrows left beside the Infinity crossbow");
                return true;
            }

            return false;
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** A brain that asks for nothing at all. Humanoid, like everything in this suite: it writes a humanoid's controls. */
    private static final Brain STILL = new Brain() {

        @Override
        public Species species() {

            return Species.HUMANOID;
        }

        @Override
        public void act(BrainStep step) {

            java.util.Arrays.fill(step.actions, 0, step.count * ActionSchema.ACT_DIM, 0.0F);
        }
    };

    /** The same for the beast, in its own seven wide action vector: the whole of what a body's brain has to say. */
    private static final Brain BEAST_WALKER = new Brain() {

        @Override
        public Species species() {

            return Species.BEAST;
        }

        @Override
        public void act(BrainStep step) {

            java.util.Arrays.fill(step.actions, 0, step.count * BeastSchema.ACT_DIM, 0.0F);

            for (int index = 0; index < step.count; index++) {

                step.actions[index * BeastSchema.ACT_DIM + BeastSchema.MOVE_FORWARD] = 1.0F;
            }
        }
    };

    /** A brain that only ever walks straight ahead. */
    private static final Brain WALKER = new Brain() {

        @Override
        public Species species() {

            return Species.HUMANOID;
        }

        @Override
        public void act(BrainStep step) {

            java.util.Arrays.fill(step.actions, 0, step.count * ActionSchema.ACT_DIM, 0.0F);

            for (int index = 0; index < step.count; index++) {

                step.actions[index * ActionSchema.ACT_DIM + ActionSchema.MOVE_FORWARD] = 1.0F;
            }
        }
    };

    /** The playable agent, as a player meets it, standing on a block and looking the given way. */
    private static AgentMob worldAgent(GameTestHelper helper, BlockPos feet, float yaw) {

        AgentMob agent = helper.spawn(ModEntities.agentMob(), feet);
        face(agent, yaw);
        return agent;
    }

    /** The one the arenas fight with, looking south, on the given brain if there is one. */
    private static AgentMob trainingAgent(GameTestHelper helper, BlockPos feet, Brain brain) {

        AgentMob agent = helper.spawn(ModEntities.trainingAgent(), feet);
        face(agent, 0.0F);

        if (brain != null) {

            agent.brain().use(brain);
        }

        return agent;
    }

    private static void face(AgentMob agent, float yaw) {

        agent.setYRot(yaw);
        agent.setYHeadRot(yaw);
        agent.setYBodyRot(yaw);
        agent.setXRot(0.0F);
    }

    private static boolean unhurt(LivingEntity entity) {

        return entity.getHealth() >= entity.getMaxHealth();
    }

    private static boolean occupies(EnemySlots view, LivingEntity entity) {

        for (int slot = 0; slot < net.sievert.modularmobai.brain.schema.ObservationSchema.ENEMY_SLOTS; slot++) {

            if (view.occupant(slot) == entity) {

                return true;
            }
        }

        return false;
    }

    private static List<AbstractArrow> arrows(GameTestHelper helper) {

        return helper.getLevel().getEntitiesOfClass(AbstractArrow.class, helper.getBounds());
    }

    private static int infinity(GameTestHelper helper, ItemStack stack) {

        return EnchantmentHelper.getItemEnchantmentLevel(
                helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.INFINITY), stack);
    }

    /** Takes down whatever teams these are on, which outlive the test otherwise. */
    private static void disband(Entity... entities) {

        for (Entity entity : entities) {

            if (entity.getTeam() instanceof PlayerTeam team) {

                Allegiance.disband(team);
            }
        }
    }

    /** A position as a command takes it. The test plots sit millions of blocks out, where a double prints as 1.4E7. */
    private static String coordinates(Vec3 at) {

        return String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f", at.x, at.y, at.z);
    }

    /** Runs a command as an operator standing in the test's corner would, and fails the test if it is refused. */
    private static int command(GameTestHelper helper, String command) {

        try {

            return dispatch(helper, command);
        }

        catch (CommandSyntaxException exception) {

            throw new GameTestAssertException("/" + command + " was refused: " + exception.getMessage());
        }
    }

    private static void commandFails(GameTestHelper helper, String command, String what) {

        try {

            dispatch(helper, command);
        }

        catch (CommandSyntaxException | RuntimeException exception) {

            return;
        }

        throw new GameTestAssertException("/" + command + " took " + what);
    }

    private static int dispatch(GameTestHelper helper, String command) throws CommandSyntaxException {

        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(helper.getLevel())
                .withPosition(helper.absoluteVec(new Vec3(1.5D, 2.0D, 1.5D)))
                .withPermission(4)
                .withSuppressedOutput();

        return server.getCommands().getDispatcher().execute(command, source);
    }

    /**
     * A brain by name, failing the test with the reason it could not be had. Which network was asked for is in the
     * message, because the one failure worth reading here is a published network the layout has left behind, and the
     * reason the game gives for that names the schema and not the file.
     */
    private static Brain loads(GameTestHelper helper, String name) {

        try {

            return Brains.named(name);
        }

        catch (RuntimeException exception) {

            throw new GameTestAssertException("The brain '" + name + "' cannot be loaded: " + exception.getMessage());
        }
    }

    private static void refused(GameTestHelper helper, Runnable attempt, String what) {

        try {

            attempt.run();
        }

        catch (RuntimeException exception) {

            return;
        }

        throw new GameTestAssertException("Brains.named took " + what);
    }

    /** See {@link TestTicks#run}, which the mechanics suite runs its own tests through too. */
    private static void run(GameTestHelper helper, IntPredicate step) {

        TestTicks.run(helper, step);
    }
}

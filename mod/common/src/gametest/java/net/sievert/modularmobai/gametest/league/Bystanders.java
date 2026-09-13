package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;

/**
 * The monsters standing about a fight taking no interest in it, which a share of league fights now has.
 *
 * <p>Every league fight was the agent against one opponent, or a squad of two or three, and every one of them on the other
 * team and coming for it. A real world is not like that, and the bill came in from a real world: an agent spawned in a
 * creative night, with a zombie beside it, walked about and swung at the grass while the zombie killed it. Nothing was wrong
 * with the body or the observation — what was wrong was that ten enemy slots full of bodies that mostly ignore the agent is a
 * shape no training fight had ever shown it. The view has since been narrowed to what the agent can actually see, which took
 * the monsters behind the wall out of it; what is left, and what this is, is the crowd that really is in sight.
 *
 * <pre>
 *   -Dmodular_mob_ai.league.bystanders=0.25   the share of fights with a crowd in them; 0 for none at all
 * </pre>
 *
 * <p>What a bystander is and is not:
 *
 * <ul>
 *   <li><b>On no team, and unprovoked every tick.</b> A league opponent is handed the agent by {@link Roster.Member#provoke}
 *       on every tick of the fight; a bystander is handed it back, {@link #leaveAlone}. Leaving the provocation out is not
 *       enough on its own, which was measured rather than assumed: three plain zombies on no team, with nothing having
 *       touched them, all took a nearby agent as their target on the same tick six blocks off, so a crowd left to vanilla's
 *       own judgement is not reliably a crowd. Struck, one fights back as any mob does — that is what passive <i>until
 *       struck</i> means, and it is the point: a body in a slot is not by itself a fight, and the agent has to learn which
 *       ones are.</li>
 *   <li><b>Not part of the win condition, and not paid for.</b> The fight's {@link net.sievert.modularmobai.arena.Episode}
 *       is given the opponents and nothing else, so {@code pays} is false for a bystander and the reward is exactly what it
 *       was. An agent paid for hurting them would learn to farm a crowd instead of winning a fight.</li>
 *   <li><b>A player of its own in the ratings</b>, {@code zombie+3_idle}, the way a squad is. A quarter of the plain
 *       {@code zombie} fights quietly having a crowd in them would move what that rating means, and every run before this one
 *       is compared against it. The trainer never matchmakes over these names — they are not in the roster it is handed — so
 *       they cost a row in the tier list and nothing in the machinery. What they do move is a checkpoint's evaluated win
 *       rate, and deliberately: a network that cannot fight in a crowd should not be the run's best weights.</li>
 *   <li><b>Only against mobs and squads.</b> Never in a fight against the scripted fighter, a published network or a
 *       checkpoint. The scripted fighter is the anchor every rating in the league is measured against, held at 1500, and its
 *       fights may not change under a run that is already going.</li>
 * </ul>
 *
 * <p>Which mobs they are drawn from is narrower than the roster, for two reasons that are both about the crowd being a crowd:
 * <b>monsters only</b>, since a bystander exists to fill an enemy slot and a wolf or an iron golem standing about is not an
 * enemy on sight and takes none, so the number in the name would stop being the number in the view; and <b>only what
 * walks</b>, since a flyer starts in the air and the open sky a site guarantees is over the fighters, not over a spot twenty
 * blocks away, so a bystander ghast would start inside a hill or drift out of the fight's own ground. The warden is left out
 * by name: it picks what to fight by how angry it is rather than by seeing anything, so it is the one mob here that would not
 * stay a bystander.
 */
public final class Bystanders {

    private Bystanders() {}

    private static final String PROPERTY = "modular_mob_ai.league.bystanders";

    /**
     * What share of league fights against a mob or a squad stands a crowd around it. A quarter, which is what the hazard
     * ground was given and for the same reason: the plain fight on plain ground against one opponent is still the fight the
     * agent has to be able to win, and a run that only ever saw crowds would learn the crowd rather than the fight.
     */
    private static final double SHARE = 0.25D;

    /** How many stand about when any do. One is already a different fight from none; ten slots is what nine fills. */
    private static final int FEWEST = 1;
    private static final int MOST = 9;

    /**
     * How far from the middle of the fight they stand. Near enough to be well inside the thirty two blocks the agent sees,
     * far enough that none of them is in the fight: the nearest is a second off at a walk, and a mob that does not come is
     * still that far off when the fight ends.
     */
    private static final int NEAREST = 8;
    private static final int FURTHEST = 30;

    /**
     * How much of the site's own edge to leave clear. A bystander outside the site is outside the chunks the fight keeps
     * loaded and outside the bounds the agent may see into, which would be a mob nobody can see and nothing can tick.
     */
    private static final int EDGE = 6;

    /** How far around a chosen spot to look for somewhere to stand, and how many directions to try. */
    private static final int SEARCH = 3;
    private static final int TURNS = 8;

    /** What the count is written after, so a name says what the fight was: zombie+3_idle. */
    private static final String SUFFIX = "_idle";

    private static double share = Double.NaN;

    @Nullable
    private static List<Roster.Member> crowd;

    /**
     * How many bystanders the next fight against a mob or a squad stands about in, and nought for none. Drawn per fight
     * rather than settled per opponent, so an opponent is met both ways and the plain rating and the crowd's are both fed
     * from the same draw.
     */
    public static synchronized int wanted(RandomSource random) {

        double asked = share();

        if (!(asked > 0.0D) || crowding().isEmpty() || random.nextDouble() >= asked) {

            return 0;
        }

        return Mth.nextInt(random, FEWEST, MOST);
    }

    /**
     * The share this process was told, read once: what the build passed, or a quarter. Public because a test that asserted
     * against a quarter would fail a run that had been told something else and was doing exactly as it was told.
     */
    public static synchronized double share() {

        if (Double.isNaN(share)) {

            String asked = System.getProperty(PROPERTY, "").trim();

            try {

                share = asked.isEmpty() ? SHARE : Math.max(0.0D, Math.min(1.0D, Double.parseDouble(asked)));
            }

            catch (NumberFormatException exception) {

                share = SHARE;
            }
        }

        return share;
    }

    /** What a fight with that many standing about is called: {@code zombie+3_idle}, and the plain name for none. */
    public static String name(String opponent, int standing) {

        return standing <= 0 ? opponent : opponent + "+" + standing + SUFFIX;
    }

    /**
     * Stands that many monsters about the fight and hands them back, so the arena can tell them from its fighters. Fewer
     * than asked for where the ground offers nowhere to stand, which is the same answer a fight that asks for hazard ground
     * and is handed flat ground gets: what the share is is a ceiling, and the results say what it really was.
     *
     * <p>Each is spawned the way it would spawn on its own and readied the way a league opponent is — grown up, a slime at
     * full size — at the rung of the ladder this fight is on, since a crowd on a hard fight is a hard crowd. What it is
     * never given is {@link Roster.Member#provoke}: that call is the whole difference between an opponent and a bystander.
     */
    public static List<Mob> stand(ServerLevel level, TerrainSites.Site site, Opposition opposition, int wanted,
                                  RandomSource random) {

        List<Roster.Member> from = crowding();

        if (wanted <= 0 || from.isEmpty()) {

            return List.of();
        }

        BlockPos middle = BlockPos.containing(site.agent().getCenter().add(site.opponent().getCenter()).scale(0.5D));
        List<Mob> standing = new ArrayList<>(wanted);

        for (int index = 0; index < wanted; index++) {

            BlockPos at = spot(level, site, middle, random);

            if (at == null) {

                continue;
            }

            Roster.Member member = from.get(random.nextInt(from.size()));
            Mob mob = member.type().create(level);

            if (mob == null) {

                continue;
            }

            float yaw = random.nextFloat() * 360.0F;

            mob.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, yaw, 0.0F);
            mob.setYHeadRot(yaw);
            mob.setYBodyRot(yaw);
            mob.finalizeSpawn(level, opposition.spawnDifficulty(level, at), MobSpawnType.EVENT, member.groupData());
            member.prepare(mob, random);

            // Persistent so nothing despawns mid fight, and tagged as the fight's own so the sweep that keeps a site clear
            // of wildlife spares it while the fight is on and takes it away when the fight is over; see TerrainSites.
            mob.setPersistenceRequired();
            mob.addTag(TerrainSites.TAG);

            level.addFreshEntity(mob);
            standing.add(mob);
        }

        return List.copyOf(standing);
    }

    /**
     * Hands the agent back to itself: a bystander that has picked the agent as its target, and that nothing has hurt, is a
     * bystander no longer, so its target is taken away again. The exact opposite of {@link Roster.Member#provoke}, asked on
     * every tick of the fight for the same reason that is — a mob's own mind is not still.
     *
     * <p>Needed rather than assumed. A vanilla mob looks for a target among players and an agent is no player, so leaving the
     * provocation out ought to have been the whole of it; measured, three plain zombies on no team with nothing having
     * touched them all took a nearby agent as their target on one tick, six blocks off. A quarter of the fights quietly
     * gaining extra opponents that the reward does not pay for and the ratings do not know about is exactly the kind of fault
     * that would never show up in a result, so the crowd is held to being a crowd instead.
     *
     * <p>Once something has hurt it, it keeps whatever target it likes, which is what passive <b>until struck</b> means. What
     * counts as having been hurt is vanilla's own memory of it, which runs out after five seconds, so a bystander the agent
     * hit and walked away from goes back to standing about. That is the honest reading of a mob losing interest.
     */
    public static void leaveAlone(Mob standing, LivingEntity agent) {

        if (standing.getTarget() == agent && standing.getLastHurtByMob() == null) {

            standing.setTarget(null);
        }
    }

    /**
     * Somewhere in the crowd's band to stand: a few directions from a random start, a fresh distance for each, and never so
     * far out that the spot leaves the site. Null where the ground offered nowhere, which leaves the fight one bystander
     * short rather than costing it the site.
     */
    @Nullable
    private static BlockPos spot(ServerLevel level, TerrainSites.Site site, BlockPos middle, RandomSource random) {

        AABB box = site.bounds();
        float heading = random.nextFloat() * Mth.TWO_PI;

        for (int turn = 0; turn < TURNS; turn++) {

            float angle = heading + turn * (Mth.TWO_PI / TURNS);
            int distance = Mth.nextInt(random, NEAREST, FURTHEST);

            int x = middle.getX() + Math.round(Mth.cos(angle) * distance);
            int z = middle.getZ() + Math.round(Mth.sin(angle) * distance);

            if (x < box.minX + EDGE || x > box.maxX - EDGE || z < box.minZ + EDGE || z > box.maxZ - EDGE) {

                continue;
            }

            BlockPos found = TerrainSites.nearestStanding(level, x, z, SEARCH);

            // The search for standing ground can walk a few blocks in, so the band is checked on where it actually landed.
            if (found != null && found.distSqr(middle) >= NEAREST * NEAREST) {

                return found;
            }
        }

        return null;
    }

    /** The roster mobs a bystander is drawn from, read once; see the class comment for why it is narrower than the roster. */
    private static synchronized List<Roster.Member> crowding() {

        if (crowd == null) {

            crowd = Roster.fielded().stream().filter(Bystanders::crowds).toList();

            Constants.LOG.info("League fights stand bystanders drawn from {} of the {} mobs, {} to {} of them {} blocks off",
                    crowd.size(), Roster.fielded().size(), FEWEST, MOST, NEAREST + " to " + FURTHEST);
        }

        return crowd;
    }

    /** Whether that mob makes a crowd: a monster, so it takes a slot on sight; on its feet; and not the warden. */
    private static boolean crowds(Roster.Member member) {

        return member.type().getCategory() == MobCategory.MONSTER && member.height() == 0
                && member.type() != EntityType.WARDEN;
    }
}

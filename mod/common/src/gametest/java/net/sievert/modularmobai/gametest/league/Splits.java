package net.sievert.modularmobai.gametest.league;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.sievert.modularmobai.gametest.terrain.TerrainSites;

/**
 * What a slime leaves behind when it dies, taken into the fight it was part of.
 *
 * <p>A slime and a magma cube are the only mobs in the game that make new bodies by dying: above its smallest size, one
 * killed leaves two to four copies of itself at half its size, and those split again. Vanilla does it in
 * {@code Slime#remove}, so the children appear the moment the parent's health reaches nought and nothing about the fight is
 * asked first.
 *
 * <p><b>What that cost, measured.</b> Until this existed a league fight against a big slime ended <i>at the split</i>. The
 * arena waits for every body on the other side to be dead, the other side was the one slime the fight spawned, and so the
 * tick the big one died the fight was written down as a <b>win</b> — with two to four middling slimes standing on the ground
 * the agent had not touched, each of which would have split again. They were not on the other side, so the reward paid
 * nothing for them and the win condition never looked at them; they carried no fight tag either, so the sweep for wildlife
 * took them away within the second, and nothing in a run's results said any of it had happened. Every {@code slime} and
 * {@code magma_cube} rating in the league was of a fight that stopped at the first body. See
 * {@code AgentCrowdGameTest.aSlimeThatSplitsLeavesTheFightUnwon}, which fails on that build with the count it found.
 *
 * <p><b>What it does now.</b> Every child is taken into the fight as it appears, which is the same answer an evoker's vexes
 * already get ({@code Roster#summon}) and for the same reason — a body a fight makes is the fight's to own. It is tagged, so
 * the wildlife sweep spares it and the site's own cleanup takes it at the end; it joins the other side, so the reward pays
 * for hurting it exactly once and the fight is won only when the last of them is down; it joins the side's team where there
 * is one, so a pack of slimes does not end up fighting its own children; and it is provoked every tick like anything else.
 *
 * <p>It changes what {@code slime} and {@code magma_cube} mean, along with their rungs, their packs and their crowded names,
 * so it belongs at a run boundary; see docs/findings.md. What it does not change is any other opponent: nothing else in the
 * game splits, {@link #splitting} answers null for every one of them, and not a tick of work is done in their fights.
 */
public final class Splits {

    private Splits() {}

    /**
     * The first mob on that side that can leave bodies behind, or null where none can. Asked once as a fight is set up, so
     * the look for children below is only ever done in the fights that can have any — and it is also the member a child is
     * filed under, since a child is a body the fight never spawned and has no member of its own. Its parent's provocation,
     * touching the agent the way a slime touches a player, is the one it wants.
     */
    @Nullable
    public static Roster.Member splitting(@Nullable Opposition opposition) {

        return opposition == null ? null : opposition.mobs().stream().filter(Splits::splits).findFirst().orElse(null);
    }

    /**
     * The children that have appeared since the last look, each tagged as the fight's on the way out so that it is only ever
     * handed over once and so that the sweeps treat it as a fighter rather than as wildlife.
     *
     * <p>Two things narrow the look, and the second is the one that is not obvious. The <b>box</b> is the fight's own ground,
     * since a worker's sites sit near enough to each other that a wider one would reach into the next fight. And a child has
     * to have appeared <b>where a body of this fight fell</b>: a crowd of bystanders can hold slimes of its own, and a
     * bystander the agent has struck and killed leaves children exactly as an opponent does — untagged, on this site, and
     * nothing to do with the fight. Taking one of those in would hand the fight an opponent nobody drew and a win condition
     * that waits for it. A child appears within a block of its parent, so a few blocks of slack is all this wants.
     *
     * @param fell  where each body of the fight that can split was last standing as it died
     * @param reach how far from one of those a body must be to be its child
     */
    public static List<Slime> taken(ServerLevel level, AABB where, List<Vec3> fell, double reach) {

        List<Slime> left = new ArrayList<>();

        if (fell.isEmpty()) {

            return left;
        }

        for (Slime child : level.getEntitiesOfClass(Slime.class, where, slime -> !slime.getTags().contains(TerrainSites.TAG))) {

            if (fell.stream().noneMatch(at -> at.closerThan(child.position(), reach))) {

                continue;
            }

            child.addTag(TerrainSites.TAG);
            left.add(child);
        }

        return left;
    }

    /**
     * Whether that mob is one that can split: anything of a slime's kind, which is the slime and the magma cube. Asked of
     * the entity type and not of the size it was made, so a smallest one is looked for too — it never leaves anything, the
     * look finds nothing, and the rule stays one that cannot fall out of step with how the sizes are set up.
     */
    private static boolean splits(Roster.Member member) {

        return member.type() == EntityType.SLIME || member.type() == EntityType.MAGMA_CUBE;
    }
}

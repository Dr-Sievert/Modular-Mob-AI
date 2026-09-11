package net.sievert.modularmobai.arena;

/**
 * What the agent is being paid for: damage dealt and taken while the fight is running, and how it ended.
 *
 * <p>Damage is scaled by the health of whoever took it, so a full kill is worth one no matter how much health the
 * opponent had. That keeps the numbers comparable when the ladder moves from a zombie to a vindicator to another agent.
 *
 * <p>Time only enters through a win: winning quickly pays more than winning slowly. A loss is a loss however it came,
 * killed in the first second or still standing when the minute ran out. Paying anything back for lasting longer made
 * running away the best thing an agent that could not yet win could do, and a policy that has learned to run never
 * finds out how to fight.
 *
 * <pre>
 *   instant kill, untouched         +1.50 dealt   0.00 taken   +3 outcome   =  +4.50
 *   slow kill, untouched            +1.50 dealt   0.00 taken   +2 outcome   =  +3.50
 *   slow kill, nearly dead          +1.50 dealt  -0.99 taken   +2 outcome   =  +2.51
 *   died at the buzzer, nearly won  +1.49 dealt  -1.00 taken   -2 outcome   =  -1.51
 *   stalled to the timeout           0.00 dealt   0.00 taken   -2 outcome   =  -2.00
 *   killed immediately               0.00 dealt  -1.00 taken   -2 outcome   =  -3.00
 * </pre>
 *
 * <p>Every win outscores everything else by a wide margin, and among the ways of not winning, only the damage done and
 * taken tells them apart.
 */
public final class AgentReward {

    /** Paid for winning at all, regardless of how long it took. */
    public static final float WIN = 2.0F;

    /** Charged for losing, regardless of how long it took. */
    public static final float LOSS = -2.0F;

    /** Added to a win, in full for an instant kill and nothing for one that came in at the buzzer. */
    public static final float SPEED_BONUS = 1.0F;

    /**
     * Given back on a loss for the time survived. Zero: anything above it pays the agent to run rather than fight, see
     * the class comment.
     */
    public static final float SURVIVAL_BONUS = 0.0F;

    /**
     * Hurting the opponent counts for more than being hurt. Without this an agent that stalls in a corner and an agent
     * that takes its opponent to one health before dying score the same, because both used the whole clock and dying
     * always costs exactly one full health bar. Weighting the two sides apart is what makes trying worth more than
     * hiding, and it is the only place the reward expresses a preference for aggression.
     */
    public static final float DEALT_WEIGHT = 1.5F;
    public static final float TAKEN_WEIGHT = 1.0F;

    private static final int DEFAULT_MAX_TICKS = 1200;

    private float pending;
    private float episodeTotal;
    private boolean terminal;

    private int elapsedTicks;
    private int maxTicks = DEFAULT_MAX_TICKS;

    /** @param maxTicks the arena's own time limit, which is what fast and slow are measured against. */
    public void beginEpisode(int maxTicks) {

        this.reset();
        this.maxTicks = Math.max(1, maxTicks);
    }

    public void tick() {

        this.elapsedTicks++;
    }

    public void damageDealt(float amount, float targetMaxHealth) {

        this.add(DEALT_WEIGHT * amount / Math.max(1.0F, targetMaxHealth));
    }

    public void damageTaken(float amount, float ownMaxHealth) {

        this.add(-TAKEN_WEIGHT * amount / Math.max(1.0F, ownMaxHealth));
    }

    public void won() {

        this.finish(WIN + SPEED_BONUS * (1.0F - this.elapsedFraction()));
    }

    /** Also how a timeout ends. Running the clock out is a loss: the agent did not die, but it did not win either. */
    public void lost() {

        this.finish(LOSS + SURVIVAL_BONUS * this.elapsedFraction());
    }


    /** Zero at the start of the fight, one once the arena's time is up. */
    private float elapsedFraction() {

        return Math.min(1.0F, this.elapsedTicks / (float) this.maxTicks);
    }

    private void finish(float amount) {

        if (this.terminal) {

            return;
        }

        this.add(amount);
        this.terminal = true;
    }

    private void add(float amount) {

        this.pending += amount;
        this.episodeTotal += amount;
    }

    /** Reads what has built up since the last tick and clears it, so nothing is ever counted twice. */
    public float takeTick() {

        float value = this.pending;
        this.pending = 0.0F;
        return value;
    }

    public float episodeTotal() {

        return this.episodeTotal;
    }

    public int elapsedTicks() {

        return this.elapsedTicks;
    }

    public boolean isTerminal() {

        return this.terminal;
    }

    public void reset() {

        this.pending = 0.0F;
        this.episodeTotal = 0.0F;
        this.terminal = false;
        this.elapsedTicks = 0;
    }
}

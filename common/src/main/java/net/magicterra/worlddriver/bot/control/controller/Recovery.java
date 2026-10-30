package net.magicterra.worlddriver.bot.control.controller;

import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.*;
import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.EnumSet;
import java.util.Set;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.ResetScope;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.control.sense.EscalationClock;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Getting a stuck bot unstuck by moving it: a back-off straight away from a riser it keeps failing to mount, so the
 * next attempt has a run-up, and a burst along a fixed heading out of a wedge or a churn pocket. The phases that notice
 * the trouble only ask for one ({@link #backOff}, {@link #burst}); this drives it. A back-off asked for while a burst
 * runs goes first, and the burst resumes after it.
 *
 * <p>Both push in the camera's frame instead of turning the head: a burst that turned the head wound the camera eight
 * full turns in a water-cave wedge cluster (bursts every ~6 s, each on a different heading, raw yaw at -3109°). The
 * look stays wherever it was, and the push is converted to the escape heading.
 */
public final class Recovery implements Controller {

    /**
     * How long a back-off drives, and how long after it ends before another may be asked for: a riser that cannot be
     * mounted at all would otherwise get a retreat every 12 ticks.
     */
    private static final int BACKOFF_TICKS = 12, BACKOFF_COOLDOWN = 60;
    /** A burst out of a wedge (repeated safety repaths at one spot) and out of a churn pocket. */
    public static final int WEDGE_BURST_TICKS = 14, CHURN_BURST_TICKS = 16;
    /** A back-off, a burst, and one burst asked for again while the first ran: no run is longer. */
    private static final int MAX_TICKS = BACKOFF_TICKS + WEDGE_BURST_TICKS + CHURN_BURST_TICKS;

    /** Look too, though it never turns the head: nothing else may turn it under a push it has converted. */
    private static final Set<Channel> CHANNELS = EnumSet.of(Channel.MOVE, Channel.LOOK, Channel.JUMP);

    private int backOffLeft;
    private float backOffYaw;
    /**
     * The navigator tick ({@link EscalationClock#tick}) from which another back-off may be asked for. Outlives a run on
     * purpose — it is the memory that stops a retreat loop — and goes with the goal.
     */
    private long backOffReadyAt;

    private int burstLeft;
    private float burstYaw;

    // ---- the wedge count Repath keeps to decide when a burst is due ----
    // Kept across a replan on purpose: a resume loop at the same wedge must still escalate to the burst.
    public BlockPos lastWedgeFoot; // where the last wedge/stuck repath fired
    public int wedgeRepathsHere; // consecutive wedge repaths from (about) the same foot
    public int countCooldown; // min ticks between counted repath events (debounce)

    /** Forget the wedge: the bot got away from it, or a new route makes it moot. */
    public void dropWedgeAnchor() {
        wedgeRepathsHere = 0;
        lastWedgeFoot = null;
    }

    // ---- requests, from the phases that notice the trouble ----

    /** Whether a back-off may be asked for at navigator tick {@code now}. */
    public boolean backOffReady(long now) {
        return now >= backOffReadyAt;
    }

    /** Drive along {@code yaw} for {@link #BACKOFF_TICKS}, starting next tick. */
    public void backOff(float yaw, long now) {
        backOffLeft = BACKOFF_TICKS;
        backOffYaw = yaw;
        backOffReadyAt = now + BACKOFF_TICKS + BACKOFF_COOLDOWN;
    }

    /**
     * Push for {@code ticks} along the heading away from the waypoint, the one heading certain to be open since the bot
     * came from there. Not a fixed turn from the current heading: in a concave pocket (three walls and water) that just
     * grinds the next wall — the yaw once wound eleven full turns at a mud-cliff notch, every burst re-entering the
     * same seam. Within a tenth of a block of the waypoint there is no "away", and the burst turns 150° from where the
     * bot faces.
     */
    public void burst(int ticks, LivingEntity p, BlockPos wp) {
        double bdx = wp != null ? p.getX() - (wp.getX() + 0.5) : 0;
        double bdz = wp != null ? p.getZ() - (wp.getZ() + 0.5) : 0;
        burstYaw = (bdx * bdx + bdz * bdz) > 0.01 ? (float) Math.toDegrees(Math.atan2(-bdx, bdz)) : p.getYRot() + 150f;
        burstLeft = ticks;
    }

    /** Forget the back-off and its cooldown: the route it was backing off from is gone. */
    private void dropBackOff() {
        backOffLeft = 0;
        backOffReadyAt = 0;
    }

    /** Ticks of recovery still asked for, for the inspector. */
    public int remaining() {
        return backOffLeft + burstLeft;
    }

    // ---- Controller ----

    @Override
    public String name() {
        return "recovery";
    }

    @Override
    public Set<Channel> channels() {
        return CHANNELS;
    }

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public int maxTicks() {
        return MAX_TICKS;
    }

    @Override
    public String whyNot(Navigator nav, TickCtx cx, WorldView world) {
        return remaining() > 0 ? null : "nothing asked for";
    }

    @Override
    public boolean canContinue(Navigator nav, TickCtx cx, WorldView world) {
        return remaining() > 0;
    }

    @Override
    public boolean tick(Navigator nav, TickCtx cx, Body a, WorldView world) {
        LivingEntity p = cx.frame.p;
        if (backOffLeft > 0) {
            backOffLeft--;
            push(a, p, backOffYaw);
            nav.keys.jump(a, false);
        } else {
            burstLeft--;
            push(a, p, burstYaw);
            // Floor-gated hop (navigatorRecoveryHopFloorGate): the burst's jump is an UNAIMED ballistic arc —
            // on narrow footing beside a lethal drop it clears the deck where the stride floor-guard
            // (grounded velocity only) can no longer help. The grounded push is kept: drift toward a lip
            // stays covered by the guard's sneak-pin; only the airborne launch is unguarded, so only it is cut.
            boolean hop = hopAllowed(world, p, cx.frame.foot);
            if (hop) {
                nav.jumpTag = "unstuckBurst";
            }
            nav.keys.jump(a, hop);
        }
        p.setSprinting(false);
        return true;
    }

    @Override
    public void stop(String why) {
        backOffLeft = 0;
        burstLeft = 0;
    }

    @Override
    public void reset(ResetScope scope) {
        dropBackOff();
        if (scope == ResetScope.GOAL) {
            dropWedgeAnchor();
            countCooldown = 0;
        }
    }

    private static void push(Body a, LivingEntity p, float yaw) {
        double d = Math.toRadians(angleDiff(p.getYRot(), yaw));
        a.commandMove((float) -Math.sin(d), (float) Math.cos(d));
    }

    /**
     * The burst's own copy of {@link Navigator#wiggleHop}'s decision — and the only line that will ever say what it
     * decided.
     *
     * <p><b>The second consumer of the same guard, so it inherited the same defect.</b>
     * {@code lethalDropWithinHopRange} was a Chebyshev-2 ring around the LAUNCH cell measured against an arc of 3.47
     * blocks; repairing only {@code Navigator#wiggleHop} would have left the identical hole open on the door beside it.
     * This path is the easier of the two to reason about, because a burst already carries an explicit escape heading —
     * the very heading the push follows — so it never had to be handed the drive channel at all.
     *
     * <p><b>It logs because it never had</b> — and the first run that gave it a pen settled a question the wiggle path
     * could not. One rehearsal produced 216 rows from the wiggle path and <b>zero</b> from this one; the next produced
     * <b>97</b> here, on which camera and drive sit a median <b>177°</b> apart (min 66°, max 180°, <b>not one sample
     * under 45°</b>) against a median of 2° on the wiggle path. A gate reading {@code p.getYRot()} would have judged
     * every one of those 97 backwards — the divergence between the two channels is per code path, and the path that
     * diverges most is the one that had no instrument at all. Throttled by the ticks left rather than latched: a burst
     * is 14 or 16 ticks, so {@code % 8} prints exactly twice per burst, and BOTH outcomes print — a row that only
     * appears when the hop is cut cannot tell a reader the gate was consulted and said yes.
     */
    private boolean hopAllowed(WorldView world, LivingEntity p, BlockPos foot) {
        boolean allowed = !(ControlConfig.navigatorRecoveryHopFloorGate && hopSuppressed(world, p, foot, burstYaw));
        if (burstLeft % 8 == 0) {
            // The RETIRED gate's verdict, printed beside the live one: ring ≥ 0 means the old Chebyshev-≤2
            // test would have held this hop, ">2" means it would have let it go. A row that prints only
            // the rule that won cannot tell a reader the two ever disagreed.
            int oldRing = nearestLethalHopRing(world, p, foot, HOP_RANGE);
            LOG.info(
                    "[navigator] unstuck burst hop: jump={} foot={} remaining={} ringGateNearestLethal={} | {}",
                    allowed,
                    foot.toShortString(),
                    burstLeft,
                    oldRing < 0 ? ">" + HOP_RANGE : String.valueOf(oldRing),
                    hopLandingRow(world, p, foot, burstYaw));
        }
        return allowed;
    }
}

package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.smoothAngle;

import java.util.List;
import java.util.Locale;
import net.magicterra.worlddriver.bot.planning.ElytraPathfinder;
import net.magicterra.worlddriver.bot.rules.ElytraPhysics;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Flies an elytra toward a target: arms flight, steers with fireworks for boost, and lands at the goal. */
public final class ElytraProcess implements BotProcess {
    /** Give up arming flight after this many ticks (~3 s) — no elytra, stuck on the ground, or in water. */
    private static final int TAKEOFF_TIMEOUT = 60;

    // --- reactive waypoint following (milestone C) ---
    private static final int REPLAN_TICKS = 40; // re-plan cadence (newly loaded terrain refines route)

    private static final double WAYPOINT_REACH = 6.0;

    // --- landing + failsafes (milestone D) ---
    /** Horizontal distance to the final goal at which the flare begins. */
    private static final double LANDING_APPROACH = 24.0;

    /**
     * Durability headroom below the elytra's break point at which we abort: a wing that snaps mid-air drops the bot, so
     * glide down while it can.
     */
    private static final int DURABILITY_MARGIN = 15;

    /**
     * Ticks of no progress toward the goal before aborting (covers out-of-fireworks-can't-climb and any other stall).
     */
    private static final int STUCK_TICKS = 120;

    // --- long-distance plan-as-you-fly (milestone E; NO chunk cache) ---
    /**
     * Known (loaded) blocks required ahead per block/tick of speed before a firework boost is allowed — so the bot
     * never outruns what it can see and slams into a chunk that pops in. boost ⟺ knownAhead ≥ SAFETY_TICKS·horizSpeed.
     */
    private static final int SAFETY_TICKS = 50;

    /** How far ahead to probe for the loaded frontier (blocks). */
    private static final int FRONTIER_SCAN = 256;

    /** Keep the flight sub-goal this far back inside loaded terrain (blocks). */
    private static final int FRONTIER_BACKOFF = 24;

    // --- via points ---
    /**
     * A via point counts as passed when the body's middle crosses the plane through it, square to its line, within
     * this of the point both across and up. The flight does not know how wide a ring is, so this is generous: a body
     * this far off the middle of a seven-block opening would have struck its frame, and one a little nearer went
     * through. Judged tighter, a ring passed near its edge counted as missed and the flight turned back for it.
     */
    private static final double GATE_REACH = 4.5;

    /**
     * How far above or below the last via point the flight crosses it, toward pos's height. A seven-block opening
     * leaves three blocks either side of its middle for a gliding body, so this keeps half of that clear.
     */
    private static final double GATE_LEAN = 1.5;

    /** Height of a gliding body's middle above its feet, which is what has to go through a ring. */
    private static final double BODY_MID = 0.3;

    /** Closer than this in front of a via point, the steering aims past it instead of before it. */
    private static final double GATE_ALIGN = 4.0;

    /** How far past a via point the steering aims for the last few blocks before it. */
    private static final double GATE_OVERSHOOT = 12.0;

    /**
     * Horizontal distance to pos at which the touchdown search takes over. Its candidates reach about 60 blocks, and a
     * landing from boosted speed needs the room to dive and flare.
     */
    private static final double TOUCHDOWN_APPROACH = 48.0;

    /** Within this of the last via point, a flight that keeps a rocket burning lets the last one run out. */
    private static final double LAST_GATE_COAST = 30.0;

    /** The longest the landing spends after touchdown sliding to a stop and walking back to pos. */
    private static final int SETTLE_TICKS = 60;

    /** After the slide, the body walks back until it stands this close to pos. */
    private static final double SETTLE_REACH = 1.5;

    private final BlockPos target; // optional horizontal goal; null → heading hold

    private final List<Vec3> gates; // via points, cell centres, flown through in order before target

    private int gateIndex;

    private Vec3 origin; // where the flight started, the leg in to the first via point

    private Vec3 prevPos; // last tick's position, for the crossing of a via point's plane

    private double lastAhead = Double.NaN; // last tick's distance in front of the current via point's plane

    private boolean gateTurnedBack; // the current via point was missed and is approached from its far side

    private boolean seeded; // the first flying tick has taken over a rocket an earlier order lit

    private int settleTicks = -1; // ticks since touchdown while the body slides to a stop; -1 before it

    private final Float fixedYaw; // hold this yaw; null → aim at target / keep current

    private final float pitch; // cruise/test pitch, MC sign (+down dives)

    private final boolean useFireworks;

    private final int fireworkEveryTicks;

    private final int maxTicks;

    private final double stopXzDist;

    private final boolean reactive; // milestone-B sim-lookahead control toward a 3D target

    private final boolean validate; // sample sim error (only meaningful glide-only)

    private final ElytraController controller = new ElytraController();

    private List<Vec3> waypoints; // corridor to the goal; last == goal

    private int wpIndex;

    private int sinceReplan;

    private boolean landing; // in the final flare / controlled descent

    private boolean aborting; // failsafe descent (durability / stall) — heads down, no boost

    private double landMinDist = Double.POSITIVE_INFINITY; // closest 3D approach to goal while landing

    private double bestGoalDist = Double.POSITIVE_INFINITY; // best 3D dist (for stall detection)

    private int sinceImprove;

    /**
     * Cruise altitude held while heading to a far (still-unloaded) goal: seeded from the launch altitude on the first
     * flying tick. The frontier sub-goal sits at this Y so the controller boosts to hold height instead of slowly
     * sinking over a long glide; reactive avoidance still climbs above it to clear a known ridge, then settles back.
     */
    private double cruiseY = Double.NaN;

    private boolean frontierMode; // last tick aimed at the loaded frontier, not the real goal

    private Phase phase = Phase.TAKEOFF;

    private int ticks;

    private int takeoffTicks;

    private int sinceFirework = Integer.MAX_VALUE;

    // --- simulator validation state (look + velocity in effect last tick) ---
    private boolean haveSample;

    private Vec3 prevVel = Vec3.ZERO;

    private float prevYaw;

    private float prevPitch;

    private double prevGravity = ElytraPhysics.GRAVITY;

    private double sumErr;

    private double maxErr;

    private int samples;

    /** Every elytra stamp is a failure (arrivals stamp nothing), so the slot answers directly. */
    private BotState.ProcessSlot slot;

    private enum Phase {
        TAKEOFF,
        FLYING
    }

    /**
     * Creates a flight toward {@code target}, bounded by {@code maxTicks}; a non-null {@code fixedYaw} holds that
     * heading.
     */
    public ElytraProcess(
            BlockPos target,
            List<BlockPos> via,
            Float fixedYaw,
            float pitch,
            boolean useFireworks,
            int fireworkEveryTicks,
            int maxTicks,
            double stopXzDist,
            boolean reactive) {
        this.target = target;
        this.gates = via.stream().map(Vec3::atCenterOf).toList();
        this.fixedYaw = fixedYaw;
        this.pitch = pitch;
        this.useFireworks = useFireworks;
        this.fireworkEveryTicks = fireworkEveryTicks;
        this.maxTicks = maxTicks;
        this.stopXzDist = stopXzDist;
        this.reactive = reactive && target != null;
        // Validate only in the fixed-heading glide rig: reactive flight steers
        // every tick and boosts, so glide-only prediction wouldn't match.
        this.validate = !useFireworks && !this.reactive;
    }

    @Override
    public String kind() {
        return "elytra";
    }

    @Override
    public void attach(BotState st) {
        st.elytra.beginRun(
                target != null
                        ? "fly→" + target.getX() + "," + target.getY() + "," + target.getZ()
                        : String.format(Locale.ROOT, "glide pitch %.1f", pitch),
                target);
        slot = st.elytra;
    }

    @Override
    public String failure() {
        return slot == null ? null : slot.lastError;
    }

    @Override
    public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        if (p == null) {
            st.elytra.lastError = "player vanished";
            st.elytra.reset();
            return true;
        }
        // This tick's hands, bound here, the one place they can be absent.
        final Equipment equipment = a.equipment().orElse(null);
        if (equipment == null) {
            st.elytra.lastError = Readiness.Reason.NO_HANDS;
            st.elytra.reset();
            return true;
        }

        if (phase == Phase.TAKEOFF) {
            Takeoff step = takeoff(a, p, st, equipment);
            if (step != Takeoff.AIRBORNE) {
                return step == Takeoff.ENDED;
            }
        }

        // --- FLYING ---
        a.commandJump(false);
        if (settleTicks >= 0) {
            return settle(a, p, st);
        }
        if (!p.isFallFlying()) {
            if (landing && BehaviorConfig.elytraLandingStops && p.onGround()) {
                settleTicks = 0;
                return settle(a, p, st);
            }
            return wingClosed(a, p, st); // wing closed / landed / no room
        }
        Vec3 curVel = p.getDeltaMovement();
        if (!seeded) {
            seeded = true;
            if (BehaviorConfig.elytraKeepsBurningRocket && st.elytraRocketLitAt != Long.MIN_VALUE) {
                controller.seedBoost(p.level().getGameTime() - st.elytraRocketLitAt);
            }
        }

        // Prove last tick's prediction against what the client actually did.
        if (validate && haveSample) {
            checkPrediction(curVel);
        }

        boolean ended = reactive ? flyReactive(a, w, st, p, equipment, curVel) : flyGlide(a, st, p, equipment, curVel);
        return ended || (++ticks > maxTicks && outOfBudget(a, st));
    }

    /** How a TAKEOFF tick ended: the command is over, the wing is still arming, or the wing is open. */
    private enum Takeoff {
        ENDED,
        ARMING,
        AIRBORNE
    }

    private Takeoff takeoff(Body a, LivingEntity p, BotState st, Equipment equipment) {
        if (p.isFallFlying()) {
            phase = Phase.FLYING;
            return Takeoff.AIRBORNE;
        }
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        boolean flyable = chest.is(Items.ELYTRA) && ElytraItem.isFlyEnabled(chest);
        if (!flyable) {
            st.elytra.lastError = "no usable elytra in chest slot";
            a.releaseInputs();
            st.elytra.reset();
            return Takeoff.ENDED;
        }
        if (p.onGround()) {
            a.commandJump(true); // jump to leave the ground
        } else {
            a.commandJump(false);
            if (equipment.startFallFlying()) {
                phase = Phase.FLYING;
            }
        }
        if (phase != Phase.TAKEOFF) {
            return Takeoff.AIRBORNE;
        }
        if (++takeoffTicks > TAKEOFF_TIMEOUT) {
            st.elytra.lastError = "takeoff failed (never started fall-flying)";
            a.releaseInputs();
            st.elytra.reset();
            return Takeoff.ENDED;
        }
        return Takeoff.ARMING;
    }

    /** Compare last tick's glide prediction with the velocity the client actually has now. */
    private void checkPrediction(Vec3 curVel) {
        Vec3 pred = ElytraPhysics.glideStep(prevVel, ElytraPhysics.lookVec(prevYaw, prevPitch), prevPitch, prevGravity);
        double err = pred.subtract(curVel).length();
        sumErr += err;
        samples++;
        if (err > maxErr) {
            maxErr = err;
        }
        if (BehaviorConfig.elytraDebug) {
            LOG.info(
                    "[elytra] t={} pred=({},{},{}) obs=({},{},{}) err={} |v|={}",
                    ticks,
                    fmt(pred.x),
                    fmt(pred.y),
                    fmt(pred.z),
                    fmt(curVel.x),
                    fmt(curVel.y),
                    fmt(curVel.z),
                    fmt(err),
                    fmt(curVel.length()));
        }
    }

    private static void applyLook(LivingEntity p, float yaw, float pitchDegrees) {
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(pitchDegrees);
    }

    /**
     * Milestones B+C+D: plan a corridor (C), fly it with the sim-lookahead controller (B), flare to land + fail safe
     * (D). True when the flight ended this tick.
     */
    private boolean flyReactive(Body a, WorldView w, BotState st, LivingEntity p, Equipment equipment, Vec3 curVel) {
        Vec3 goal = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        Vec3 pos = p.position();
        if (Double.isNaN(cruiseY)) {
            cruiseY = pos.y; // hold launch altitude over far/unknown segments
            origin = pos;
        }
        passGates(pos);
        // Between via points progress is toward the next one: the route to them can lead away from pos for longer
        // than the stall failsafe waits.
        double goalDist = gateIndex < gates.size() ? gateFeet(gateIndex).distanceTo(pos) : goal.distanceTo(pos);
        checkFailsafes(st, p, goalDist);
        if (landing) {
            return BehaviorConfig.elytraLandsOnSurface
                    ? flyTouchdown(a, w, st, p, equipment, goal, pos, goalDist, curVel)
                    : flyLanding(a, w, st, p, goal, pos, goalDist, curVel);
        }
        if (gateIndex < gates.size()) {
            flyGate(w, st, p, equipment, goal, pos, curVel);
            return false;
        }
        return flyCruise(a, w, st, p, equipment, goal, pos, goalDist, curVel);
    }

    /** Where the feet go for the body's middle to pass through via point {@code i}. */
    private Vec3 gateFeet(int i) {
        Vec3 gate = gates.get(i);
        double lean = 0;
        if (BehaviorConfig.elytraViaLinesUp && i == gates.size() - 1) {
            // The last via point leans toward the landing's height: after it the landing has to lose the rest of the
            // height at a sink slow enough not to hurt, and crossing the middle of the ring left it a fraction of a
            // block per tick short of that. Leaning at every via point instead cost the ring after a steep dive.
            lean = Mth.clamp(target.getY() + 0.5 - gate.y, -GATE_LEAN, GATE_LEAN);
        }
        return gate.add(0, lean - BODY_MID, 0);
    }

    /**
     * The horizontal line through via point {@code i} that the flight crosses it along: the one halving the turn from
     * the leg in to the leg out, or the leg in alone when the line-up is off or the legs fold back on each other.
     */
    private Vec3 gateAxis(int i) {
        Vec3 gate = gates.get(i);
        Vec3 from = i == 0 ? origin : gates.get(i - 1);
        Vec3 next = i + 1 < gates.size() ? gates.get(i + 1) : Vec3.atCenterOf(target);
        Vec3 in = flatUnit(gate.subtract(from));
        Vec3 out = flatUnit(next.subtract(gate));
        if (in == null) {
            return out != null ? out : new Vec3(1, 0, 0);
        }
        if (!BehaviorConfig.elytraViaLinesUp || out == null) {
            return in;
        }
        Vec3 half = flatUnit(in.add(out));
        return half != null ? half : in;
    }

    private static Vec3 flatUnit(Vec3 v) {
        double h = Math.hypot(v.x, v.z);
        return h < 1e-3 ? null : new Vec3(v.x / h, 0, v.z / h);
    }

    /**
     * Moves past every via point whose plane the body crossed since last tick inside {@link #GATE_REACH} of it, either
     * way. A crossing outside it is a miss: the point stays next, and the steering turns back for it.
     */
    private void passGates(Vec3 pos) {
        while (gateIndex < gates.size()) {
            Vec3 gate = gates.get(gateIndex);
            Vec3 axis = gateAxis(gateIndex);
            double ahead = (gate.x - pos.x) * axis.x + (gate.z - pos.z) * axis.z;
            if (prevPos == null || Double.isNaN(lastAhead) || (lastAhead > 0) == (ahead > 0)) {
                lastAhead = ahead;
                break;
            }
            double f = lastAhead / (lastAhead - ahead);
            Vec3 cross = prevPos.add(pos.subtract(prevPos).scale(f));
            double across = Math.abs((cross.x - gate.x) * -axis.z + (cross.z - gate.z) * axis.x);
            double up = Math.abs(cross.y + BODY_MID - gate.y);
            if (across > GATE_REACH || up > GATE_REACH) {
                if (BehaviorConfig.elytraDebug) {
                    LOG.info("[elytra] missed via {} across={} up={}", gateIndex, fmt(across), fmt(up));
                }
                lastAhead = ahead;
                gateTurnedBack = !gateTurnedBack;
                break;
            }
            if (BehaviorConfig.elytraDebug) {
                LOG.info("[elytra] through via {} across={} up={}", gateIndex, fmt(across), fmt(up));
            }
            gateIndex++;
            gateTurnedBack = false;
            lastAhead = Double.NaN;
            bestGoalDist = Double.POSITIVE_INFINITY;
            sinceImprove = 0;
            waypoints = null;
        }
        prevPos = pos;
    }

    /**
     * Flies toward the next via point without slowing for it. The yaw goes to a point on its line half as far before it
     * as the body is (past it once close), so the turn onto the line happens early and the crossing is square; the
     * pitch is the lookahead's, scored on the closest pass to the point itself.
     */
    private void flyGate(WorldView w, BotState st, LivingEntity p, Equipment equipment, Vec3 goal, Vec3 pos, Vec3 vel) {
        Vec3 feet = gateFeet(gateIndex);
        Vec3 axis = crossingAxis();
        final double ahead = (feet.x - pos.x) * axis.x + (feet.z - pos.z) * axis.z;
        Vec3 aim = gateAim(feet, axis, pos);
        ElytraController.Steer steer = q -> yawTo(gateAim(feet, axis, q), q);
        // Something solid between here and the point: the corridor planner's first turn point leads round it.
        if (!ElytraPathfinder.losClear(w, pos, feet)) {
            if (waypoints == null || sinceReplan >= REPLAN_TICKS) {
                waypoints = ElytraPathfinder.plan(w, pos, feet);
                sinceReplan = 0;
            }
            Vec3 turn = waypoints.stream()
                    .filter(v -> v.distanceTo(pos) > WAYPOINT_REACH)
                    .findFirst()
                    .orElse(feet);
            aim = turn;
            steer = q -> yawTo(turn, q);
        }
        sinceReplan++;
        float rawYaw = yawTo(aim, pos);
        double horizSpd = Math.hypot(vel.x, vel.z);
        Vec3 fwd = horizSpd > 0.05 ? new Vec3(vel.x / horizSpd, 0, vel.z / horizSpd) : axis;
        boolean boostOk = knownAhead(w, pos, fwd) >= SAFETY_TICKS * Math.max(horizSpd, 0.2);
        // Short of the last via point the flight stops relighting: a rocket burns 20 to 31 ticks, the lookahead counts
        // 20, and one lit just before the last ring still pushed the landing that followed lower than it planned.
        boolean coast = gateIndex == gates.size() - 1 && ahead < LAST_GATE_COAST;
        final ElytraController.Decision d = controller.decide(
                w,
                p,
                vel,
                steer,
                feet,
                useFireworks && boostOk,
                false,
                BehaviorConfig.elytraViaKeepsBoost && !coast,
                true);
        applyLook(p, smoothAngle(p.getYRot(), rawYaw), d.pitch());
        fire(d, p, st, equipment);
        st.elytra.pathLen = (int) Math.round(goal.distanceTo(pos));
        st.elytra.pathStep = gateIndex;
        if (BehaviorConfig.elytraDebug) {
            LOG.info(
                    "[elytra] via {} t={} pos=({},{},{}) ahead={} aim=({},{}) pitch={} fire={} |v|={}",
                    gateIndex,
                    ticks,
                    fmt(pos.x),
                    fmt(pos.y),
                    fmt(pos.z),
                    fmt(ahead),
                    fmt(aim.x),
                    fmt(aim.z),
                    fmt(d.pitch()),
                    d.fire(),
                    fmt(vel.length()));
        }
    }

    /**
     * Where a body at {@code q} steers for the via point whose feet target is {@code feet}, crossing along
     * {@code axis}: with the line-up on, a point on the line half as far before the point as the body is, and once
     * close, a point that stays {@link #GATE_OVERSHOOT} ahead of the body along the line, also after the crossing;
     * otherwise the point itself. The lookahead steers by this from simulated positions too, so past the plane it
     * has to keep going: aiming back at the point there turned every simulated crossing round into the frame.
     */
    private static Vec3 gateAim(Vec3 feet, Vec3 axis, Vec3 q) {
        if (!BehaviorConfig.elytraViaLinesUp) {
            return feet;
        }
        double ahead = (feet.x - q.x) * axis.x + (feet.z - q.z) * axis.z;
        return ahead > GATE_ALIGN
                ? feet.subtract(axis.scale(ahead / 2))
                : feet.add(axis.scale(Math.max(0, -ahead) + GATE_OVERSHOOT));
    }

    /**
     * The line the flight crosses the current via point along, turned round after each miss so that the flight comes
     * back through from the side it ended up on.
     */
    private Vec3 crossingAxis() {
        Vec3 axis = gateAxis(gateIndex);
        return gateTurnedBack ? axis.scale(-1) : axis;
    }

    private static float yawTo(Vec3 aim, Vec3 from) {
        return (float) Math.toDegrees(Math.atan2(-(aim.x - from.x), aim.z - from.z));
    }

    /**
     * After touchdown: holds shift until the body stands still, then walks it back, still sneaking, to within
     * {@link #SETTLE_REACH} of pos, and ends the flight. A touchdown carries a slide of several blocks, and vanilla
     * keeps a sneaking player from moving off an edge, so on a raised pad the slide stops at its edge instead of
     * carrying the body over it; but a body stopped there hangs half over the drop and tips off once shift is let go.
     * Gives up after three seconds.
     */
    private boolean settle(Body a, LivingEntity p, BotState st) {
        a.commandSneak(true);
        Vec3 v = p.getDeltaMovement();
        boolean still = p.onGround() && Math.hypot(v.x, v.z) < 0.02;
        double gx = target.getX() + 0.5;
        double gz = target.getZ() + 0.5;
        boolean home = Math.hypot(gx - p.getX(), gz - p.getZ()) <= SETTLE_REACH;
        if ((still && home) || ++settleTicks > SETTLE_TICKS) {
            a.releaseInputs();
            st.elytra.reset();
            return true;
        }
        if (!home && p.onGround() && Math.hypot(v.x, v.z) < 0.1) {
            a.commandToward(gx, gz, 1.0f);
        } else {
            a.commandMove(0, 0);
        }
        return false;
    }

    /** Lights a rocket when the decision asks, and remembers when for this order and the next. */
    private void fire(ElytraController.Decision d, LivingEntity p, BotState st, Equipment equipment) {
        if (d.fire() && equipment.holdItem(Items.FIREWORK_ROCKET)) {
            InteractionResult r = equipment.useItemInHand();
            if (r.consumesAction()) {
                p.swing(InteractionHand.MAIN_HAND);
                controller.onFired();
                st.elytraRocketLitAt = p.level().getGameTime();
            }
        }
    }

    /**
     * The landing: steers at the surface on pos's column and takes the lookahead's touchdown pitch, which dives and
     * flares to land there without a hurting fall. On the ground it hands over to {@link #settle}; with that off, it
     * ends when the body is down, which closes the wing, and the tick above reports that as the arrival.
     */
    private boolean flyTouchdown(
            Body a,
            WorldView w,
            BotState st,
            LivingEntity p,
            Equipment equipment,
            Vec3 goal,
            Vec3 pos,
            double goalDist,
            Vec3 vel) {
        int ground = groundBelow(w, (int) Math.floor(goal.x), (int) Math.floor(goal.y), (int) Math.floor(goal.z));
        Vec3 aim = new Vec3(goal.x, ground == Integer.MIN_VALUE ? goal.y - 0.5 : ground + 1.0, goal.z);
        double goalH = Math.hypot(aim.x - pos.x, aim.z - pos.z);
        double horizSpeed = Math.hypot(vel.x, vel.z);
        // Slow and under a one-in-four slope to the aim, gliding on would end short of it; under the aim's level, as
        // after glancing off the side of a raised pad, it can only climb back with a rocket.
        boolean boost = BehaviorConfig.elytraFlareBoosts
                && useFireworks
                && !aborting
                && ((goalH > 6.0 && horizSpeed < 0.45 && pos.y < aim.y + 0.25 * goalH) || pos.y < aim.y - 0.5);
        // Straight over the aim the bearing spins; within a block and a half the heading is held.
        float held = p.getYRot();
        ElytraController.Steer steer = q -> Math.hypot(aim.x - q.x, aim.z - q.z) < 1.5 ? held : yawTo(aim, q);
        float yaw = steer.yaw(pos);
        final ElytraController.Decision d = controller.decideTouchdown(w, p, vel, steer, aim, boost);
        applyLook(p, smoothAngle(p.getYRot(), yaw), d.pitch());
        fire(d, p, st, equipment);
        st.elytra.pathLen = (int) Math.round(goalDist);
        int groundHere = groundBelow(w, (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
        double aboveGround = groundHere == Integer.MIN_VALUE ? 999.0 : pos.y - (groundHere + 1);
        if (BehaviorConfig.elytraDebug) {
            LOG.info(
                    "[elytra] touchdown t={} pos=({},{},{}) goalH={} aboveGround={} pitch={} fire={} hSpeed={} vy={}",
                    ticks,
                    fmt(pos.x),
                    fmt(pos.y),
                    fmt(pos.z),
                    fmt(goalH),
                    fmt(aboveGround),
                    fmt(d.pitch()),
                    d.fire(),
                    fmt(horizSpeed),
                    fmt(vel.y));
        }
        boolean settled = aboveGround <= 1.0 && horizSpeed < 0.3;
        boolean hoverNoGround = ground == Integer.MIN_VALUE && goalH <= LANDING_APPROACH && horizSpeed < 0.3;
        if (BehaviorConfig.elytraLandingStops) {
            // Shift held from just before contact, so the first tick on the ground already stops at an edge.
            a.commandSneak(aboveGround <= 1.5);
            if (p.onGround()) {
                settleTicks = 0;
                return settle(a, p, st);
            }
        }
        if (p.onGround() || settled || hoverNoGround) {
            a.releaseInputs();
            st.elytra.reset();
            return true;
        }
        return false;
    }

    /** Failsafe 1, durability, and failsafe 2, stall: either one starts the glide-down. */
    private void checkFailsafes(BotState st, LivingEntity p, double goalDist) {
        // Failsafe 1 — durability: glide down before the wing breaks. Not during the flare, which
        // already is that descent; a stamp there would fail the touch-down at the target.
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!aborting
                && !landing
                && chest.is(Items.ELYTRA)
                && chest.getMaxDamage() > 0
                && chest.getDamageValue() >= chest.getMaxDamage() - DURABILITY_MARGIN) {
            aborting = true;
            landing = true;
            landMinDist = Double.POSITIVE_INFINITY;
            st.elytra.lastError = "elytra durability low — gliding down";
            if (BehaviorConfig.elytraDebug) {
                LOG.info(
                        "[elytra] durability {}/{} ≤ margin — aborting to glide-down",
                        chest.getDamageValue(),
                        chest.getMaxDamage());
            }
        }
        // Failsafe 2 — stall: no progress toward the goal for a while
        // (e.g. need to climb but out of fireworks) → glide down.
        if (goalDist < bestGoalDist - 1.0) {
            bestGoalDist = goalDist;
            sinceImprove = 0;
        } else {
            sinceImprove++;
        }
        if (!aborting && !landing && sinceImprove > STUCK_TICKS) {
            aborting = true;
            landing = true;
            landMinDist = Double.POSITIVE_INFINITY;
            st.elytra.lastError = "no progress (stalled / out of boost) — gliding down";
            if (BehaviorConfig.elytraDebug) {
                LOG.info("[elytra] stalled — aborting to glide-down");
            }
        }
    }

    /** The flare / glide-down branch of {@link #flyReactive}; true when the bot touched down and the flight is over. */
    private boolean flyLanding(
            Body a, WorldView w, BotState st, LivingEntity p, Vec3 goal, Vec3 pos, double goalDist, Vec3 curVel) {
        // Descend to the GROUND near the goal and only finish once
        // we're low + slow (or grounded). Finishing mid-air would
        // release the bot still fall-flying at cruise speed, and it
        // would coast uncontrolled for hundreds of blocks into
        // whatever's ahead (a mountain). All steering still goes
        // through the collision-aware controller (no boost, speed-bleed
        // biased) so the descent itself never rams terrain.
        int goalGround = groundBelow(
                w, (int) Math.floor(goal.x), (int) Math.floor(Math.max(pos.y, goal.y)), (int) Math.floor(goal.z));
        Vec3 land = new Vec3(goal.x, goalGround == Integer.MIN_VALUE ? goal.y : goalGround + 3.0, goal.z);
        final double landDist = land.distanceTo(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(-(land.x - pos.x), land.z - pos.z));
        final ElytraController.Decision ld = controller.decide(w, p, curVel, yaw, land, false, true);
        // smoothLook pans yaw for the cinematic look; pitch stays SNAPPED —
        // it's the physics input the controller simulated, so lagging it
        // would desync the collision-avoidance lookahead.
        applyLook(p, smoothAngle(p.getYRot(), yaw), ld.pitch());
        if (landDist < landMinDist) {
            landMinDist = landDist;
        }
        int groundHere = groundBelow(w, (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
        double aboveGround = groundHere == Integer.MIN_VALUE ? 999.0 : pos.y - (groundHere + 1);
        double horizSpeed = Math.hypot(curVel.x, curVel.z);
        st.elytra.pathLen = (int) Math.round(goalDist);
        if (BehaviorConfig.elytraDebug) {
            LOG.info(
                    "[elytra] {} t={} pos=({},{},{}) landDist={} aboveGround={} pitch={} hSpeed={} hcol={}",
                    aborting ? "abort-glide" : "landing",
                    ticks,
                    fmt(pos.x),
                    fmt(pos.y),
                    fmt(pos.z),
                    fmt(landDist),
                    fmt(aboveGround),
                    fmt(ld.pitch()),
                    fmt(horizSpeed),
                    p.horizontalCollision);
        }
        // Touch-down: grounded, or low + slow (release here lets the
        // bot settle the last block or two with negligible momentum),
        // or — over water/void with no ground — bled to a near-hover.
        boolean lowSlow = aboveGround <= 5.0 && horizSpeed < 0.7;
        boolean hoverNoGround = goalGround == Integer.MIN_VALUE && landDist <= LANDING_APPROACH && horizSpeed < 0.3;
        if (p.onGround() || lowSlow || hoverNoGround || landDist <= Math.max(stopXzDist, 2.5)) {
            a.releaseInputs();
            st.elytra.reset();
            return true;
        }
        return false;
    }

    /** The plan-and-fly branch of {@link #flyReactive}; true when the bot reached the final waypoint's goal. */
    private boolean flyCruise(
            Body a,
            WorldView w,
            BotState st,
            LivingEntity p,
            Equipment equipment,
            Vec3 goal,
            Vec3 pos,
            double goalDist,
            Vec3 curVel) {
        // Plan-as-you-fly (E): aim at the real goal once it's loaded,
        // else at the loaded frontier along the bearing — never commit
        // a route through chunks we can't see. Re-planning walks it out.
        replanIfDue(w, pos, flightTarget(w, pos, goal));
        sinceReplan++;
        // Advance past waypoints we've reached or can already see past.
        while (wpIndex < waypoints.size() - 1
                && (waypoints.get(wpIndex).distanceTo(pos) <= WAYPOINT_REACH
                        || ElytraPathfinder.losClear(w, pos, waypoints.get(wpIndex + 1)))) {
            wpIndex++;
        }
        boolean onFinal = wpIndex >= waypoints.size() - 1;
        // Enter the flare once lined up on the final goal and close.
        final double goalH = Math.hypot(goal.x - pos.x, goal.z - pos.z);
        // The touchdown search sees its own obstacles, so it starts as soon as the air over pos is in sight: just through
        // a ring the corridor still routes round its frame, and waiting for the last waypoint left six blocks to land
        // in. The sight line ends two blocks up, because the line test keeps a block clear all round and pos itself
        // stands on the ground.
        boolean approach = BehaviorConfig.elytraLandsOnSurface
                ? goalH < TOUCHDOWN_APPROACH && (onFinal || ElytraPathfinder.losClear(w, pos, goal.add(0, 2, 0)))
                : onFinal && goalH < LANDING_APPROACH;
        if (approach) {
            landing = true;
            landMinDist = goalDist;
            if (BehaviorConfig.elytraDebug) {
                LOG.info("[elytra] flare: final approach, goalH={}", fmt(goalH));
            }
        }
        Vec3 wp = waypoints.get(wpIndex);
        float rawYaw = (float) Math.toDegrees(Math.atan2(-(wp.x - pos.x), wp.z - pos.z));
        // Boost governance (E): don't outrun the loaded terrain. A
        // rocket is allowed only when the world is known far enough
        // ahead along our heading to react (SAFETY_TICKS·speed); as we
        // near the frontier this throttles to a glide until chunks load.
        double horizSpd = Math.hypot(curVel.x, curVel.z);
        Vec3 fwd = horizSpd > 0.05
                ? new Vec3(curVel.x / horizSpd, 0, curVel.z / horizSpd)
                : new Vec3(-Math.sin(Math.toRadians(rawYaw)), 0, Math.cos(Math.toRadians(rawYaw)));
        double knownFwd = knownAhead(w, pos, fwd);
        boolean boostOk = knownFwd >= SAFETY_TICKS * Math.max(horizSpd, 0.2);
        final ElytraController.Decision d = controller.decide(w, p, curVel, rawYaw, wp, useFireworks && boostOk);
        // smoothLook pans yaw only; pitch stays snapped (see landing branch).
        float prevYaw = p.getYRot();
        float yaw = smoothAngle(prevYaw, rawYaw);
        applyLook(p, yaw, d.pitch());
        fire(d, p, st, equipment);
        st.elytra.pathLen = (int) Math.round(goalDist);
        st.elytra.pathStep = wpIndex;
        if (BehaviorConfig.elytraDebug) {
            LOG.info(
                    "[elytra] reactive t={} pos=({},{},{}) wp{}/{}=({},{},{}) goalDist={} yawTgt={} yaw={} dYaw={} pitch={} fire={} boostOk={} knownFwd={} front={} |v|={} hp={} hcol={}",
                    ticks,
                    fmt(pos.x),
                    fmt(pos.y),
                    fmt(pos.z),
                    wpIndex,
                    waypoints.size() - 1,
                    fmt(wp.x),
                    fmt(wp.y),
                    fmt(wp.z),
                    fmt(goalDist),
                    fmt(rawYaw),
                    fmt(yaw),
                    fmt(Mth.degreesDifference(prevYaw, yaw)),
                    fmt(d.pitch()),
                    d.fire(),
                    boostOk,
                    fmt(knownFwd),
                    frontierMode,
                    fmt(curVel.length()),
                    fmt(p.getHealth()),
                    p.horizontalCollision);
        }
        // A landing on the surface ends on the ground only; passing near pos in the air is not an arrival.
        if (onFinal && !(landing && BehaviorConfig.elytraLandsOnSurface) && goalDist <= Math.max(stopXzDist, 2.5)) {
            a.releaseInputs();
            st.elytra.reset();
            return true;
        }
        return false;
    }

    /** Re-plan the corridor to {@code planGoal} when there is none yet or the last plan is {@code REPLAN_TICKS} old. */
    private void replanIfDue(WorldView w, Vec3 pos, Vec3 planGoal) {
        if (waypoints != null && sinceReplan < REPLAN_TICKS) {
            return;
        }
        waypoints = ElytraPathfinder.plan(w, pos, planGoal);
        wpIndex = 0;
        sinceReplan = 0;
        if (BehaviorConfig.elytraDebug) {
            LOG.info(
                    "[elytra] planned {} waypoint(s) to ({},{},{}){}",
                    waypoints.size(),
                    fmt(planGoal.x),
                    fmt(planGoal.y),
                    fmt(planGoal.z),
                    frontierMode ? " [frontier]" : "");
        }
    }

    /** The fixed-heading / glide rig (no corridor planning); true when the bot is within {@code stopXzDist}. */
    private boolean flyGlide(Body a, BotState st, LivingEntity p, Equipment equipment, Vec3 curVel) {
        // Heading for NEXT tick's travel (rotation set now drives the next tick).
        float yaw;
        if (fixedYaw != null) {
            yaw = fixedYaw;
        } else if (target != null) {
            double dx = target.getX() + 0.5 - p.getX();
            double dz = target.getZ() + 0.5 - p.getZ();
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        } else {
            yaw = p.getYRot();
        }
        applyLook(p, yaw, pitch);

        // Firework boost policy.
        if (useFireworks && sinceFirework >= fireworkEveryTicks && equipment.holdItem(Items.FIREWORK_ROCKET)) {
            InteractionResult r = equipment.useItemInHand();
            if (r.consumesAction()) {
                p.swing(InteractionHand.MAIN_HAND);
                sinceFirework = 0;
            }
        } else if (sinceFirework < Integer.MAX_VALUE) {
            sinceFirework++;
        }

        // Save this tick's inputs for next tick's prediction.
        final boolean slow = p.hasEffect(MobEffects.SLOW_FALLING) && curVel.y <= 0;
        prevVel = curVel;
        prevYaw = yaw;
        prevPitch = pitch;
        prevGravity = slow ? ElytraPhysics.GRAVITY_SLOW_FALLING : ElytraPhysics.GRAVITY;
        haveSample = true;

        // Arrival / safety stop.
        if (target != null) {
            double dx = target.getX() + 0.5 - p.getX();
            double dz = target.getZ() + 0.5 - p.getZ();
            if (Math.sqrt(dx * dx + dz * dz) <= stopXzDist) {
                if (BehaviorConfig.elytraDebug) {
                    logSummary();
                }
                a.releaseInputs();
                st.elytra.reset();
                return true;
            }
        }
        return false;
    }

    /**
     * Flew the whole tick budget and never met an arrival condition.
     *
     * <p>Every arrival returns from {@link #tick} above this, so reaching here IS the non-arrival — and it used to
     * leave the slot unstamped, which {@code ProcessSlot.snapshot()} renders as a clean finish (it emits
     * {@code lastError} only when non-null, and {@code attach} cleared it). The file's OTHER timeout,
     * {@code takeoffTicks > TAKEOFF_TIMEOUT}, has always stamped; this one is its drifted twin. {@code logSummary()}
     * was not the substitute: it sits behind {@code elytraDebug}, which is off in a normal run, so the failure left no
     * trace at all.
     */
    private boolean outOfBudget(Body a, BotState st) {
        // A glide with no target has nothing to arrive at; its budget is the flight it was asked for.
        if (target != null) {
            st.elytra.lastError = "flight budget exhausted (" + maxTicks + " ticks, never arrived)";
        }
        if (BehaviorConfig.elytraDebug) {
            logSummary();
        }
        a.releaseInputs();
        st.elytra.reset();
        return true;
    }

    /**
     * The wing closed. Touching down at the goal closes it too, so this is also an arrival path, and only a close away
     * from the goal is stamped.
     *
     * <p>If this happened mid-air (wing broke, ran out of room) the always-on water-bucket clutch — which deliberately
     * stands down while isFallFlying() — now sees a fast unplanned fall and arms reactively, so a bucketed bot still
     * self-rescues. Nothing to do here but release; the bot's clutch picks it up at the top of its next tick.
     */
    private boolean wingClosed(Body a, LivingEntity p, BotState st) {
        if (BehaviorConfig.elytraDebug) {
            logSummary();
            if (!p.onGround()) {
                LOG.info("[elytra] flight ended airborne at y={} — handing fall to the clutch", fmt(p.getY()));
            }
        }
        // A failsafe descent already stamped why it gave up; keep that reason.
        if (st.elytra.lastError == null && target != null) {
            double horizDist = Math.hypot(target.getX() + 0.5 - p.getX(), target.getZ() + 0.5 - p.getZ());
            st.elytra.lastError = closedShort(landing, horizDist, stopXzDist);
        }
        a.releaseInputs();
        st.elytra.reset();
        return true;
    }

    /**
     * Why a flight toward a target ended when its wing closed, or null if that close was the arrival: a touch-down
     * after the flare, which the landing branch also counts as done anywhere inside the approach, or a stop within
     * {@code stopXzDist}.
     */
    static String closedShort(boolean landing, double horizDist, double stopXzDist) {
        if (landing || horizDist <= stopXzDist) {
            return null;
        }
        return String.format(Locale.ROOT, "wing closed %.1f blocks short of target", horizDist);
    }

    /**
     * Highest landing surface at/below {@code (x,startY,z)} within 256 — the first solid OR water block scanning down,
     * i.e. what the bot would come to rest on. Water counts so a descent over ocean settles ON the surface (and floats)
     * instead of diving to the seabed and drowning. {@link Integer#MIN_VALUE} if only air/void in range.
     */
    private static int groundBelow(WorldView w, int x, int startY, int z) {
        for (int y = startY; y >= startY - 256; y--) {
            BlockPos b = new BlockPos(x, y, z);
            if (w.isSolid(b) || w.isWater(b)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    // --- milestone E: plan-as-you-fly helpers (no chunk cache) -----------

    /**
     * Distance (blocks) along unit {@code dir} from {@code from} before the world becomes unknown (an unloaded chunk),
     * capped at FRONTIER_SCAN; returns FRONTIER_SCAN when known the whole way. Sampled every 4 blocks (chunks are 16
     * wide, so this can't skip an unloaded chunk).
     */
    private static double knownAhead(WorldView w, Vec3 from, Vec3 dir) {
        final double step = 4.0;
        for (double d = step; d <= FRONTIER_SCAN; d += step) {
            int x = (int) Math.floor(from.x + dir.x * d);
            int y = (int) Math.floor(from.y + dir.y * d);
            int z = (int) Math.floor(from.z + dir.z * d);
            if (!w.isKnown(new BlockPos(x, y, z))) {
                return d - step;
            }
        }
        return FRONTIER_SCAN;
    }

    /**
     * Where to actually aim/plan this tick. The real goal once its cell is loaded; otherwise a point at the loaded
     * frontier along the goal bearing (backed off, held at {@link #cruiseY}) so the flight only ever commits to terrain
     * it can see — the 40-tick re-plan walks this forward as new chunks stream in. Sets {@link #frontierMode} as a side
     * effect.
     */
    private Vec3 flightTarget(WorldView w, Vec3 pos, Vec3 goal) {
        if (w.isKnown(new BlockPos((int) Math.floor(goal.x), (int) Math.floor(goal.y), (int) Math.floor(goal.z)))) {
            frontierMode = false;
            return goal;
        }
        double dx = goal.x - pos.x;
        double dz = goal.z - pos.z;
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-3) {
            frontierMode = false;
            return goal;
        }
        Vec3 dir = new Vec3(dx / horiz, 0, dz / horiz);
        double reach = Math.min(horiz, Math.max(8.0, knownAhead(w, pos, dir) - FRONTIER_BACKOFF));
        frontierMode = true;
        double y = Double.isNaN(cruiseY) ? pos.y : cruiseY;
        return new Vec3(pos.x + dir.x * reach, y, pos.z + dir.z * reach);
    }

    private void logSummary() {
        LOG.info(
                "[elytra] glide-sim validation: samples={} meanErr={} maxErr={} (blocks/tick)",
                samples,
                samples > 0 ? fmt(sumErr / samples) : "n/a",
                fmt(maxErr));
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.4f", d);
    }
}

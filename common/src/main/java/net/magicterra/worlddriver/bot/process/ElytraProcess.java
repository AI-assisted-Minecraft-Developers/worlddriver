package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.movement.Avatar;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.elytra.ElytraPhysics;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import net.minecraft.world.effect.MobEffects;
import net.magicterra.worlddriver.bot.elytra.ElytraPathfinder;

public final class ElytraProcess implements BotProcess {
    /** Give up arming flight after this many ticks (~3 s) — no elytra, stuck
     *  on the ground, or in water. */
    private static final int TAKEOFF_TIMEOUT = 60;

    private final BlockPos target;        // optional horizontal goal; null → heading hold
    private final Float fixedYaw;         // hold this yaw; null → aim at target / keep current
    private final float pitch;            // cruise/test pitch, MC sign (+down dives)
    private final boolean useFireworks;
    private final int fireworkEveryTicks;
    private final int maxTicks;
    private final double stopXZDist;
    private final boolean reactive;       // milestone-B sim-lookahead control toward a 3D target
    private final boolean validate;       // sample sim error (only meaningful glide-only)
    private final ElytraController controller = new ElytraController();

    // --- reactive waypoint following (milestone C) ---
    private static final int REPLAN_TICKS = 40;      // re-plan cadence (newly loaded terrain refines route)
    private static final double WAYPOINT_REACH = 6.0;
    private List<Vec3> waypoints;          // corridor to the goal; last == goal
    private int wpIndex;
    private int sinceReplan;

    // --- landing + failsafes (milestone D) ---
    /** Horizontal distance to the final goal at which the flare begins. */
    private static final double LANDING_APPROACH = 24.0;
    /** Durability headroom below the elytra's break point at which we abort:
     *  a wing that snaps mid-air drops the bot, so glide down while it can. */
    private static final int DURABILITY_MARGIN = 15;
    /** Ticks of no progress toward the goal before aborting (covers
     *  out-of-fireworks-can't-climb and any other stall). */
    private static final int STUCK_TICKS = 120;
    private boolean landing;        // in the final flare / controlled descent
    private boolean aborting;       // failsafe descent (durability / stall) — heads down, no boost
    private double landMinDist = Double.POSITIVE_INFINITY;  // closest 3D approach to goal while landing
    private double bestGoalDist = Double.POSITIVE_INFINITY; // best 3D dist (for stall detection)
    private int sinceImprove;

    // --- long-distance plan-as-you-fly (milestone E; NO chunk cache) ---
    /** Known (loaded) blocks required ahead per block/tick of speed before a
     *  firework boost is allowed — so the bot never outruns what it can see
     *  and slams into a chunk that pops in. boost ⟺ knownAhead ≥ SAFETY_TICKS·hSpeed. */
    private static final int SAFETY_TICKS = 50;
    /** How far ahead to probe for the loaded frontier (blocks). */
    private static final int FRONTIER_SCAN = 256;
    /** Keep the flight sub-goal this far back inside loaded terrain (blocks). */
    private static final int FRONTIER_BACKOFF = 24;
    /** Cruise altitude held while heading to a far (still-unloaded) goal:
     *  seeded from the launch altitude on the first flying tick. The frontier
     *  sub-goal sits at this Y so the controller boosts to hold height instead
     *  of slowly sinking over a long glide; reactive avoidance still climbs
     *  above it to clear a known ridge, then settles back. */
    private double cruiseY = Double.NaN;
    private boolean frontierMode;   // last tick aimed at the loaded frontier, not the real goal

    private enum Phase { TAKEOFF, FLYING }
    private Phase phase = Phase.TAKEOFF;
    private int ticks;
    private int takeoffTicks;
    private int sinceFirework = Integer.MAX_VALUE;

    // --- simulator validation state (look + velocity in effect last tick) ---
    private boolean haveSample;
    private Vec3 prevVel = Vec3.ZERO;
    private float prevYaw, prevPitch;
    private double prevGravity = ElytraPhysics.GRAVITY;
    private double sumErr, maxErr;
    private int samples;

    public ElytraProcess(BlockPos target, Float fixedYaw, float pitch, boolean useFireworks,
                  int fireworkEveryTicks, int maxTicks, double stopXZDist, boolean reactive) {
        this.target = target;
        this.fixedYaw = fixedYaw;
        this.pitch = pitch;
        this.useFireworks = useFireworks;
        this.fireworkEveryTicks = fireworkEveryTicks;
        this.maxTicks = maxTicks;
        this.stopXZDist = stopXZDist;
        this.reactive = reactive && target != null;
        // Validate only in the fixed-heading glide rig: reactive flight steers
        // every tick and boosts, so glide-only prediction wouldn't match.
        this.validate = !useFireworks && !this.reactive;
    }

    public String kind() { return "elytra"; }

    public void attach(BotState st) {
        st.elytra.active = true;
        st.elytra.goal = target != null
                ? "fly→" + target.getX() + "," + target.getY() + "," + target.getZ()
                : String.format(Locale.ROOT, "glide pitch %.1f", pitch);
        st.elytra.target = target;
        st.elytra.startedAtMs = System.currentTimeMillis();
        st.elytra.lastError = null;
    }

    public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.elytra.lastError = "player vanished"; st.elytra.reset(); return true; }

        if (phase == Phase.TAKEOFF) {
            if (p.isFallFlying()) {
                phase = Phase.FLYING;
            } else {
                ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
                boolean flyable = chest.is(Items.ELYTRA)
                        && chest.getMaxDamage() > 0 && chest.getDamageValue() < chest.getMaxDamage() - 1;
                if (!flyable) {
                    st.elytra.lastError = "no usable elytra in chest slot";
                    a.releaseInputs(); st.elytra.reset(); return true;
                }
                if (p.onGround()) {
                    a.commandJump(true);          // jump to leave the ground
                } else {
                    a.commandJump(false);
                    if (a.startFallFlying()) {
                        phase = Phase.FLYING;
                    }
                }
                if (phase == Phase.TAKEOFF) {
                    if (++takeoffTicks > TAKEOFF_TIMEOUT) {
                        st.elytra.lastError = "takeoff failed (never started fall-flying)";
                        a.releaseInputs(); st.elytra.reset(); return true;
                    }
                    return false;                              // still arming
                }
            }
        }

        // --- FLYING ---
        a.commandJump(false);
        if (!p.isFallFlying()) {                               // wing closed / landed / no room
            // If this happened mid-air (wing broke, ran out of room) the
            // always-on water-bucket clutch — which deliberately stands down
            // while isFallFlying() — now sees a fast unplanned fall and arms
            // reactively, so a bucketed bot still self-rescues. Nothing to do
            // here but release; clientTick's CLUTCH.armReactive picks it up.
            if (BotConfig.elytraDebug) {
                logSummary();
                if (!p.onGround())
                    LOG.info(
                            "[elytra] flight ended airborne at y={} — handing fall to the clutch",
                            f(p.getY()));
            }
            a.releaseInputs(); st.elytra.reset(); return true;
        }

        Vec3 curVel = p.getDeltaMovement();

        // Prove last tick's prediction against what the client actually did.
        if (validate && haveSample) {
            Vec3 pred = ElytraPhysics.glideStep(prevVel, ElytraPhysics.lookVec(prevYaw, prevPitch), prevPitch, prevGravity);
            double err = pred.subtract(curVel).length();
            sumErr += err; samples++; if (err > maxErr) maxErr = err;
            if (BotConfig.elytraDebug)
                LOG.info(
                        "[elytra] t={} pred=({},{},{}) obs=({},{},{}) err={} |v|={}",
                        ticks, f(pred.x), f(pred.y), f(pred.z), f(curVel.x), f(curVel.y), f(curVel.z),
                        f(err), f(curVel.length()));
        }

        if (reactive) {
            // --- milestones B+C+D: plan a corridor (C), fly it with the
            //     sim-lookahead controller (B), flare to land + fail safe (D) ---
            Vec3 goal = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            Vec3 pos = p.position();
            if (Double.isNaN(cruiseY)) cruiseY = pos.y;   // hold launch altitude over far/unknown legs
            double goalDist = goal.distanceTo(pos);
            double goalH = Math.hypot(goal.x - pos.x, goal.z - pos.z);

            // Failsafe 1 — durability: abort to a gentle descent before the
            // wing breaks (a mid-air snap would just drop the bot).
            ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
            if (!aborting && chest.is(Items.ELYTRA) && chest.getMaxDamage() > 0
                    && chest.getDamageValue() >= chest.getMaxDamage() - DURABILITY_MARGIN) {
                aborting = true; landing = true; landMinDist = Double.POSITIVE_INFINITY;
                st.elytra.lastError = "elytra durability low — gliding down";
                if (BotConfig.elytraDebug)
                    LOG.info(
                            "[elytra] durability {}/{} ≤ margin — aborting to glide-down",
                            chest.getDamageValue(), chest.getMaxDamage());
            }
            // Failsafe 2 — stall: no progress toward the goal for a while
            // (e.g. need to climb but out of fireworks) → glide down.
            if (goalDist < bestGoalDist - 1.0) { bestGoalDist = goalDist; sinceImprove = 0; }
            else sinceImprove++;
            if (!aborting && !landing && sinceImprove > STUCK_TICKS) {
                aborting = true; landing = true; landMinDist = Double.POSITIVE_INFINITY;
                st.elytra.lastError = "no progress (stalled / out of boost) — gliding down";
                if (BotConfig.elytraDebug)
                    LOG.info("[elytra] stalled — aborting to glide-down");
            }
            if (landing) {
                // Descend to the GROUND near the goal and only finish once
                // we're low + slow (or grounded). Finishing mid-air would
                // release the bot still fall-flying at cruise speed, and it
                // would coast uncontrolled for hundreds of blocks into
                // whatever's ahead (a mountain). All steering still goes
                // through the collision-aware controller (no boost, speed-bleed
                // biased) so the descent itself never rams terrain.
                int gGoal = groundBelow(w, (int) Math.floor(goal.x),
                        (int) Math.floor(Math.max(pos.y, goal.y)), (int) Math.floor(goal.z));
                Vec3 land = new Vec3(goal.x, gGoal == Integer.MIN_VALUE ? goal.y : gGoal + 3.0, goal.z);
                double landDist = land.distanceTo(pos);
                float yaw = (float) Math.toDegrees(Math.atan2(-(land.x - pos.x), land.z - pos.z));
                ElytraController.Decision ld = controller.decide(w, p, curVel, yaw, land, false, true);
                // smoothLook pans yaw for the cinematic look; pitch stays SNAPPED —
                // it's the physics input the controller simulated, so lagging it
                // would desync the collision-avoidance lookahead.
                yaw = smoothAngle(p.getYRot(), yaw);
                p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw;
                p.setXRot(ld.pitch());
                if (landDist < landMinDist) landMinDist = landDist;
                int gPl = groundBelow(w, (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
                double aboveGround = gPl == Integer.MIN_VALUE ? 999.0 : pos.y - (gPl + 1);
                double hSpeed = Math.hypot(curVel.x, curVel.z);
                st.elytra.pathLen = (int) Math.round(goalDist);
                if (BotConfig.elytraDebug)
                    LOG.info(
                            "[elytra] {} t={} pos=({},{},{}) landDist={} aboveGround={} pitch={} hSpeed={} hcol={}",
                            aborting ? "abort-glide" : "landing", ticks,
                            f(pos.x), f(pos.y), f(pos.z), f(landDist), f(aboveGround),
                            f(ld.pitch()), f(hSpeed), p.horizontalCollision);
                // Touch-down: grounded, or low + slow (release here lets the
                // bot settle the last block or two with negligible momentum),
                // or — over water/void with no ground — bled to a near-hover.
                boolean lowSlow = aboveGround <= 5.0 && hSpeed < 0.7;
                boolean hoverNoGround = gGoal == Integer.MIN_VALUE && landDist <= LANDING_APPROACH && hSpeed < 0.3;
                if (p.onGround() || lowSlow || hoverNoGround || landDist <= Math.max(stopXZDist, 2.5)) {
                    a.releaseInputs(); st.elytra.reset(); return true;
                }
            } else {
                // Plan-as-you-fly (E): aim at the real goal once it's loaded,
                // else at the loaded frontier along the bearing — never commit
                // a route through chunks we can't see. Re-planning walks it out.
                Vec3 planGoal = flightTarget(w, pos, goal);
                if (waypoints == null || sinceReplan >= REPLAN_TICKS) {
                    waypoints = ElytraPathfinder.plan(w, pos, planGoal);
                    wpIndex = 0;
                    sinceReplan = 0;
                    if (BotConfig.elytraDebug)
                        LOG.info(
                                "[elytra] planned {} waypoint(s) to ({},{},{}){}",
                                waypoints.size(), f(planGoal.x), f(planGoal.y), f(planGoal.z),
                                frontierMode ? " [frontier]" : "");
                }
                sinceReplan++;
                // Advance past waypoints we've reached or can already see past.
                while (wpIndex < waypoints.size() - 1
                        && (waypoints.get(wpIndex).distanceTo(pos) <= WAYPOINT_REACH
                            || ElytraPathfinder.losClear(w, pos, waypoints.get(wpIndex + 1)))) {
                    wpIndex++;
                }
                boolean onFinal = wpIndex >= waypoints.size() - 1;
                // Enter the flare once lined up on the final goal and close.
                if (onFinal && goalH < LANDING_APPROACH) {
                    landing = true; landMinDist = goalDist;
                    if (BotConfig.elytraDebug)
                        LOG.info(
                                "[elytra] flare: final approach, goalH={}", f(goalH));
                }
                Vec3 wp = waypoints.get(wpIndex);
                float rawYaw = (float) Math.toDegrees(Math.atan2(-(wp.x - pos.x), wp.z - pos.z));
                // Boost governance (E): don't outrun the loaded terrain. A
                // rocket is allowed only when the world is known far enough
                // ahead along our heading to react (SAFETY_TICKS·speed); as we
                // near the frontier this throttles to a glide until chunks load.
                double hSpd = Math.hypot(curVel.x, curVel.z);
                Vec3 fwd = hSpd > 0.05
                        ? new Vec3(curVel.x / hSpd, 0, curVel.z / hSpd)
                        : new Vec3(-Math.sin(Math.toRadians(rawYaw)), 0, Math.cos(Math.toRadians(rawYaw)));
                double knownFwd = knownAhead(w, pos, fwd);
                boolean boostOk = knownFwd >= SAFETY_TICKS * Math.max(hSpd, 0.2);
                ElytraController.Decision d = controller.decide(w, p, curVel, rawYaw, wp, useFireworks && boostOk);
                // smoothLook pans yaw only; pitch stays snapped (see landing branch).
                float prevYaw = p.getYRot();
                float yaw = smoothAngle(prevYaw, rawYaw);
                p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw;
                p.setXRot(d.pitch());
                if (d.fire() && a.holdItem(Items.FIREWORK_ROCKET)) {
                    InteractionResult r = a.useItemInHand();
                    if (r.consumesAction()) { p.swing(InteractionHand.MAIN_HAND); controller.onFired(); }
                }
                st.elytra.pathLen = (int) Math.round(goalDist);
                st.elytra.pathStep = wpIndex;
                if (BotConfig.elytraDebug)
                    LOG.info(
                            "[elytra] reactive t={} pos=({},{},{}) wp{}/{}=({},{},{}) goalDist={} yawTgt={} yaw={} dYaw={} pitch={} fire={} boostOk={} knownFwd={} front={} |v|={} hp={} hcol={}",
                            ticks, f(pos.x), f(pos.y), f(pos.z), wpIndex, waypoints.size() - 1,
                            f(wp.x), f(wp.y), f(wp.z), f(goalDist), f(rawYaw), f(yaw),
                            f(((yaw - prevYaw) % 360f + 540f) % 360f - 180f), f(d.pitch()), d.fire(),
                            boostOk, f(knownFwd), frontierMode, f(curVel.length()),
                            f(p.getHealth()), p.horizontalCollision);
                if (onFinal && goalDist <= Math.max(stopXZDist, 2.5)) {
                    a.releaseInputs(); st.elytra.reset(); return true;
                }
            }
        } else {
            // Heading for NEXT tick's travel (rotation set now drives the next tick).
            float yaw;
            if (fixedYaw != null) yaw = fixedYaw;
            else if (target != null) {
                double dx = (target.getX() + 0.5) - p.getX(), dz = (target.getZ() + 0.5) - p.getZ();
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            } else yaw = p.getYRot();
            p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw;
            p.setXRot(pitch);

            // Firework boost policy.
            if (useFireworks && sinceFirework >= fireworkEveryTicks
                    && a.holdItem(Items.FIREWORK_ROCKET)) {
                InteractionResult r = a.useItemInHand();
                if (r.consumesAction()) { p.swing(InteractionHand.MAIN_HAND); sinceFirework = 0; }
            } else if (sinceFirework < Integer.MAX_VALUE) {
                sinceFirework++;
            }

            // Save this tick's inputs for next tick's prediction.
            boolean slow = p.hasEffect(MobEffects.SLOW_FALLING) && curVel.y <= 0;
            prevVel = curVel;
            prevYaw = yaw; prevPitch = pitch;
            prevGravity = slow ? ElytraPhysics.GRAVITY_SLOW_FALLING : ElytraPhysics.GRAVITY;
            haveSample = true;

            // Arrival / safety stop.
            if (target != null) {
                double dx = (target.getX() + 0.5) - p.getX(), dz = (target.getZ() + 0.5) - p.getZ();
                if (Math.sqrt(dx * dx + dz * dz) <= stopXZDist) {
                    if (BotConfig.elytraDebug) logSummary();
                    a.releaseInputs(); st.elytra.reset(); return true;
                }
            }
        }
        if (++ticks > maxTicks) {
            if (BotConfig.elytraDebug) logSummary();
            a.releaseInputs(); st.elytra.reset(); return true;
        }
        return false;
    }

    /** Highest landing surface at/below {@code (x,yStart,z)} within 256 — the
     *  first solid OR water block scanning down, i.e. what the bot would come
     *  to rest on. Water counts so a descent over ocean settles ON the
     *  surface (and floats) instead of diving to the seabed and drowning.
     *  {@link Integer#MIN_VALUE} if only air/void in range. */
    private static int groundBelow(WorldView w, int x, int yStart, int z) {
        for (int y = yStart; y >= yStart - 256; y--) {
            BlockPos b = new BlockPos(x, y, z);
            if (w.isSolid(b) || w.isWater(b)) return y;
        }
        return Integer.MIN_VALUE;
    }

    // --- milestone E: plan-as-you-fly helpers (no chunk cache) -----------

    /** Distance (blocks) along unit {@code dir} from {@code from} before the
     *  world becomes unknown (an unloaded chunk), capped at FRONTIER_SCAN;
     *  returns FRONTIER_SCAN when known the whole way. Sampled every 4 blocks
     *  (chunks are 16 wide, so this can't skip an unloaded chunk). */
    private static double knownAhead(WorldView w, Vec3 from, Vec3 dir) {
        final double step = 4.0;
        for (double d = step; d <= FRONTIER_SCAN; d += step) {
            int x = (int) Math.floor(from.x + dir.x * d);
            int y = (int) Math.floor(from.y + dir.y * d);
            int z = (int) Math.floor(from.z + dir.z * d);
            if (!w.isKnown(new BlockPos(x, y, z))) return d - step;
        }
        return FRONTIER_SCAN;
    }

    /** Where to actually aim/plan this tick. The real goal once its cell is
     *  loaded; otherwise a point at the loaded frontier along the goal bearing
     *  (backed off, held at {@link #cruiseY}) so the flight only ever commits
     *  to terrain it can see — the 40-tick re-plan walks this forward as new
     *  chunks stream in. Sets {@link #frontierMode} as a side effect. */
    private Vec3 flightTarget(WorldView w, Vec3 pos, Vec3 goal) {
        if (w.isKnown(new BlockPos((int) Math.floor(goal.x),
                (int) Math.floor(goal.y), (int) Math.floor(goal.z)))) {
            frontierMode = false;
            return goal;
        }
        double dx = goal.x - pos.x, dz = goal.z - pos.z;
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-3) { frontierMode = false; return goal; }
        Vec3 dir = new Vec3(dx / horiz, 0, dz / horiz);
        double reach = Math.min(horiz, Math.max(8.0, knownAhead(w, pos, dir) - FRONTIER_BACKOFF));
        frontierMode = true;
        double y = Double.isNaN(cruiseY) ? pos.y : cruiseY;
        return new Vec3(pos.x + dir.x * reach, y, pos.z + dir.z * reach);
    }

    private void logSummary() {
        LOG.info(
                "[elytra] glide-sim validation: samples={} meanErr={} maxErr={} (blocks/tick)",
                samples, samples > 0 ? f(sumErr / samples) : "n/a", f(maxErr));
    }

    private static String f(double d) { return String.format(Locale.ROOT, "%.4f", d); }
}

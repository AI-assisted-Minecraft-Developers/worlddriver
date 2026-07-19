package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;

import static net.magicterra.agent.AgentDriverCommon.LOG;

/** Expectation alarms (walkerExpectAlarm): compare the world's actual response against
 *  what the pressed actions should produce, and WARN with cause the tick they diverge.
 *  Purely observational — reads avatar/player state at the top of tick, never drives.
 *
 *  <p>Carved out of {@link Walker} as a self-contained observer: it owns all the {@code ex*}
 *  sentinel state and never influences movement. {@link Walker} holds one instance and forwards
 *  {@link #tick} (once per tick when the flag is on), {@link #notePlace} (right after a place
 *  click) and {@link #noteRepathFlip} (on a U-turn route adoption). */
public final class WalkerExpectAlarms {
    /** Total alarms EMITTED (post-throttle) since JVM start — test-visible telemetry so
     *  coverage scenes can assert an alarm actually fired instead of trusting logs
     *  (task#95b). Monotonic; scenes diff before/after. */
    public static final java.util.concurrent.atomic.AtomicInteger FIRED = new java.util.concurrent.atomic.AtomicInteger();

    private boolean exPrevBreakHeld;   // was the break action held last tick (DIG-dropped edge)
    private BlockPos exPrevAimBlock;   // block the crosshair pointed at last tick
    private int exDigHoldTicks;        // consecutive held ticks on the SAME aim block (DIG-slow)
    private int exJumpTicksLeft;       // ticks left watching a jump's arc (JUMP-noRise)
    private double exJumpBaseY;        // Y at the launch tick
    private double exJumpPeakY;        // highest Y seen during the watched arc
    private boolean exPrevOnGround;    // grounded last tick (jump launch edge)
    private int exFwdNoMoveTicks;      // consecutive grounded forward-pressed ticks with ~zero displacement (MOVE-noMove)
    private double exPrevX, exPrevZ;   // last tick's position for the displacement check
    private int exThrottle;            // global alarm throttle (one line per 40t)
    private long exRepathFlipTick;     // pfTickCounter of the last U-turn route adoption (REPATH-flip window)
    private int exRepathFlipCount;     // consecutive U-turn adoptions inside the window — the oscillation depth
    private int exDriveTearTicks;      // consecutive pinned ticks with the heading >90° off the committed node (DRIVE-tear)
    private int exGearCheckTicks;      // sampling countdown for the hotbar gear sentinel (GEAR-degraded)
    private BlockPos exPlacePos;       // cell a placement expects to fill (PLACE-noBlock)
    private int exPlaceTicksLeft;      // ticks left for the placed block to appear

    private static float angleDiff(float a, float b) { return ((b - a) % 360f + 540f) % 360f - 180f; }

    /** Note a placement's expected cell for the PLACE-noBlock observer (call right
     *  after {@code a.placeOn(clicked, UP)} with {@code clicked.above()}). */
    void notePlace(BlockPos cell) {
        if (!BotConfig.walkerExpectAlarm) return;
        exPlacePos = cell;
        exPlaceTicksLeft = 8;
    }

    /** REPATH-flip: the planner adopted a route whose near-term direction reverses the current
     *  one. Called (under the walkerExpectAlarm + uTurnLeg guard) when such an adoption happens. */
    void noteRepathFlip(long pfTickCounter, BlockPos foot) {
        if (pfTickCounter - exRepathFlipTick < 200) {
            exRepathFlipCount++;
            FIRED.incrementAndGet();
            LOG.warn("[expect] REPATH-flip #{}: adopted a route whose near-term direction reverses the current one "
                    + "({}t since last flip) — planner oscillating between near-equal routes at {},{},{}",
                    exRepathFlipCount, pfTickCounter - exRepathFlipTick,
                    foot.getX(), foot.getY(), foot.getZ());
        } else {
            exRepathFlipCount = 0;
        }
        exRepathFlipTick = pfTickCounter;
    }

    void tick(Avatar a, WorldView world, Player p, List<BlockPos> path, int step, int noStepProgressTicks) {
        if (exThrottle > 0) exThrottle--;
        // DIG-dropped / DIG-slow: vanilla resets break progress on ANY released tick, so a
        // committed dig must hold continuously until the block breaks. Dropping the hold
        // while the target is still solid = wasted progress (the GroundBlip bug class).
        boolean held = a.breakHeld();
        BlockPos aim = a.lookingAtBlock();
        if (exPrevBreakHeld && !held && exPrevAimBlock != null && world.isSolid(exPrevAimBlock)) {
            if (exThrottle == 0 && exDigHoldTicks >= 5) {
                FIRED.incrementAndGet();
                LOG.warn("[expect] DIG-dropped: breakHold released after {}t while {},{},{} still solid — progress reset",
                        exDigHoldTicks, exPrevAimBlock.getX(), exPrevAimBlock.getY(), exPrevAimBlock.getZ());
                exThrottle = 40;
            }
            exDigHoldTicks = 0;
        } else if (held && aim != null && aim.equals(exPrevAimBlock)) {
            exDigHoldTicks++;
            if (exDigHoldTicks == 200 && exThrottle == 0) {
                FIRED.incrementAndGet();
                LOG.warn("[expect] DIG-slow: {},{},{} held 200t and still solid (tool? water 25x? aim drift?)",
                        aim.getX(), aim.getY(), aim.getZ());
                exThrottle = 40;
            }
        } else {
            exDigHoldTicks = held ? 1 : 0;
        }
        exPrevBreakHeld = held;
        exPrevAimBlock = aim;
        // JUMP-noRise: a grounded launch (vy jumped to ~+0.42) should lift the body ~+1 block
        // within 8 ticks; a peak under +0.9 is an in-place/blocked jump (§48 mount grind).
        double vy = p.getDeltaMovement().y;
        if (exJumpTicksLeft > 0) {
            exJumpPeakY = Math.max(exJumpPeakY, p.getY());
            // 0.8→0.75: C26 mountain journeys showed 12-13 alarms/journey ALL peaking at
            // +0.78 with the launch y climbing 2 blocks between alarms — i.e. genuinely
            // ascending step-ups flagged 0.02 under the gate. A truly blocked jump peaks
            // ≤+0.5 (hCol cap); 0.75 keeps that signal and drops the ascent false floor.
            if (--exJumpTicksLeft == 0 && exJumpPeakY < exJumpBaseY + 0.75 && !p.isInWater() && exThrottle == 0) {
                FIRED.incrementAndGet();
                LOG.warn("[expect] JUMP-noRise: launched at y={} peaked {} (<+0.75) hCol={} — blocked/in-place jump",
                        String.format(Locale.ROOT, "%.2f", exJumpBaseY),
                        String.format(Locale.ROOT, "%.2f", exJumpPeakY), p.horizontalCollision);
                exThrottle = 40;
            }
        } else if (exPrevOnGround && !p.onGround() && vy > 0.3 && !p.isInWater()) {
            exJumpTicksLeft = 8;
            exJumpBaseY = p.getY();
            exJumpPeakY = p.getY();
        }
        exPrevOnGround = p.onGround();
        // MOVE-noMove: forward impulse pressed (zza) on the ground for 10 straight ticks with
        // under 0.3 blocks of total displacement = pressing into a wall/trunk.
        double moved = Math.hypot(p.getX() - exPrevX, p.getZ() - exPrevZ);
        boolean fwdPressed = Math.abs(p.zza) > 0.4;
        if (fwdPressed && p.onGround() && moved < 0.03) {
            if (++exFwdNoMoveTicks == 10 && exThrottle == 0) {
                FIRED.incrementAndGet();
                LOG.warn("[expect] MOVE-noMove: forward held 10t, displacement<0.3 at {},{},{} hCol={}",
                        String.format(Locale.ROOT, "%.1f", p.getX()),
                        String.format(Locale.ROOT, "%.1f", p.getY()),
                        String.format(Locale.ROOT, "%.1f", p.getZ()), p.horizontalCollision);
                exThrottle = 40;
            }
        } else if (moved >= 0.03 || !fwdPressed) {
            exFwdNoMoveTicks = 0;
        }
        exPrevX = p.getX(); exPrevZ = p.getZ();
        // DRIVE-tear: the driven heading (body yaw under commandMove decoupling ≈ the
        // carrot bearing) points >90° away from the committed node for 40 straight ticks
        // while the body is pinned — the carrot-vs-node tear (§56, user-witnessed: carrot
        // east into a wall, node 5 blocks south, attack=false, pinned indefinitely).
        if (path != null && step < path.size()) {
            BlockPos node = path.get(step);
            double ndx = (node.getX() + 0.5) - p.getX(), ndz = (node.getZ() + 0.5) - p.getZ();
            float bearNode = (float) Math.toDegrees(Math.atan2(-ndx, ndz));
            float tear = Math.abs(angleDiff(p.getYRot(), bearNode));
            if (tear > 90 && moved < 0.03) {
                if (++exDriveTearTicks == 40 && exThrottle == 0) {
                    FIRED.incrementAndGet();
                    LOG.warn("[expect] DRIVE-tear: heading {}° off the committed node for 40t while pinned "
                            + "(yaw={} bearNode={} node={},{},{} hCol={}) — carrot/recovery steering away from the path",
                            String.format(Locale.ROOT, "%.0f", tear),
                            String.format(Locale.ROOT, "%.0f", p.getYRot()),
                            String.format(Locale.ROOT, "%.0f", bearNode),
                            node.getX(), node.getY(), node.getZ(), p.horizontalCollision);
                    exThrottle = 40;
                }
            } else {
                exDriveTearTicks = 0;
            }
            // ADVANCE-deadzone: parked in the within/overshoot dead band (0.45 < cur2 < 4)
            // at the node's Y with no step progress for 60t — the step pointer is starved
            // (the crest-orbit family, now alarmed instead of silently grinding).
            double cur2 = ndx * ndx + ndz * ndz;
            if (cur2 > 0.45 && cur2 < 4.0 && Math.abs(node.getY() - p.getY()) < 1.0
                    && noStepProgressTicks > 60) {
                if (exThrottle == 0) {
                    FIRED.incrementAndGet();
                    LOG.warn("[expect] ADVANCE-deadzone: cur2={} at node {},{},{} noStepProg={} — within/passed both starved",
                            String.format(Locale.ROOT, "%.2f", cur2),
                            node.getX(), node.getY(), node.getZ(), noStepProgressTicks);
                    exThrottle = 40;
                }
            }
        } else {
            exDriveTearTicks = 0;
        }
        // PLACE-noBlock: a placement (pillar/bridge support) expects its target cell to
        // turn solid within a few ticks; still passable = the click never landed (allowPlace
        // off? no build block held? out of reach? falling block dropped through water?).
        if (exPlaceTicksLeft > 0) {
            if (world.isSolid(exPlacePos)) {
                exPlaceTicksLeft = 0;
            } else if (--exPlaceTicksLeft == 0 && exThrottle == 0) {
                FIRED.incrementAndGet();
                LOG.warn("[expect] PLACE-noBlock: cell {},{},{} still passable 8t after the place click "
                        + "(allowPlace? build block held? reach? falling block in water?)",
                        exPlacePos.getX(), exPlacePos.getY(), exPlacePos.getZ());
                exThrottle = 40;
            }
        }
        // GEAR-degraded: mined-drop pickups crowd the pickaxe / water bucket out of the
        // hotbar mid-journey (observed live: 9/9 slots junk → hand-mining + dead MLG).
        // Sampled every 100t; only meaningful for a LocalPlayer with an Items-based check.
        if (++exGearCheckTicks >= 100) {
            exGearCheckTicks = 0;
            // Side guard is load-bearing: LocalPlayer does not exist in a DEDICATED-server
            // environment and even the instanceof resolves the class — walkerExpectAlarm=true
            // on a dedicated server crashed the walker tick with "Cannot load class
            // net.minecraft.client.player.LocalPlayer" (ad.expectAlarmWallRam, 2026-07-19).
            // Every LocalPlayer reference lives in ClientGearCheck so the server JVM never
            // loads it; the sentinel is a client-body concern anyway (hotbar gear).
            String missing = p.level().isClientSide() ? ClientGearCheck.missing(p) : null;
            if (missing != null) {
                FIRED.incrementAndGet();
                LOG.warn("[expect] GEAR-degraded: hotbar missing {}— pickups crowded the gear out (MLG dead / hand-mining)",
                        missing);
            }
        }
    }

    /** Client-only holder for the GEAR sentinel — see the side guard above. */
    private static final class ClientGearCheck {
        /** @return the missing-gear description ("water_bucket ", "pickaxe", …) or null if OK / not a LocalPlayer. */
        static String missing(Player p) {
            if (!(p instanceof net.minecraft.client.player.LocalPlayer lp)) return null;
            boolean bucket = net.magicterra.agent.bot.util.BotInteract.hotbarSlotOf(lp, net.minecraft.world.item.Items.WATER_BUCKET) >= 0;
            boolean pick = net.magicterra.agent.bot.util.BotInteract.hotbarSlotOf(lp, net.minecraft.world.item.Items.DIAMOND_PICKAXE) >= 0
                    || net.magicterra.agent.bot.util.BotInteract.hotbarSlotOf(lp, net.minecraft.world.item.Items.IRON_PICKAXE) >= 0;
            if (bucket && pick) return null;
            return (bucket ? "" : "water_bucket ") + (pick ? "" : "pickaxe ");
        }
    }
}

package net.magicterra.worlddriver.bot.control;

import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.blockPosOf;

import java.util.Locale;
import net.magicterra.worlddriver.bot.world.MoveSignals;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.magicterra.worlddriver.game.body.Hotbar;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The always-on water-bucket clutch, one per bot. Ticked at the top of the bot's tick, before its scheduler, so it
 * self-rescues whether or not a process is driving the bot.
 */
public final class ClutchController {

    /**
     * Blocks above the landing floor at which water placement starts. Must stay within block-interaction reach (~4.5
     * from the eye, i.e. ~2.9 above the floor); a touch over is fine — the first in-reach tick succeeds and later ticks
     * see the water and stop.
     */
    private static final double MLG_PLACE_HEIGHT = 3.0;

    /**
     * Give up scooping a placed source after this many ticks (leave it) so a stubborn scoop can't pin the bot forever.
     */
    private static final int MLG_SCOOP_MAX = 30;

    /**
     * Remaining drop (feet to landing surface) above which the reactive clutch arms an UNPLANNED fall. A fall this tall
     * deals real damage (vanilla starts hurting above 3) and still leaves room to place water in the final
     * {@link #MLG_PLACE_HEIGHT}-block window. Lower would clutch harmless step-downs and waste the bucket; higher would
     * let survivable-but-costly falls hurt.
     */
    private static final double EMERGENCY_CLUTCH_MIN_DROP = 5.0;

    /**
     * A planned arm waits on the launch lip for the walk-off; if the bot hasn't gone airborne within this many grounded
     * ticks the step-off was abandoned (path changed, goal cancelled) → disarm so the arm can't hijack an unrelated
     * later jump. Reactive arms are already airborne, so they never sit in this window.
     */
    private static final int LIP_TIMEOUT_TICKS = 20;

    private boolean armed; // committed to a water-bucket fall → own the descent

    private boolean airborne; // have actually left the launch ground this fall

    private int scoopTicks; // ticks spent scooping the placed source

    private int lipTicks; // grounded ticks since a planned arm, before going airborne

    private int noArmThrottle; // CLUTCH-noArm alarm throttle (one line per fall, not per tick)

    private int landingX = Integer.MIN_VALUE; // target landing column X (MIN_VALUE = current column)

    private int landingZ = Integer.MIN_VALUE; // target landing column Z

    /**
     * Yield the movement channel for this tick: the clutch OWNS the descent, so nothing the navigator or a process
     * commanded may move the bot sideways or add a jump.
     *
     * <p>{@link Body#commandMove} is the channel that outranks the navigator's own command. Clearing the direction
     * keys, which is what this used to do, did nothing while a process was driving: the client's input layer runs
     * vanilla's key pass and then OVERWRITES the impulses with the navigator's command. During an MLG that is not
     * cosmetic: the drift-damping spring below fights the residual momentum one way while a still-live navigator
     * command pushes the other, and coasting one block off a 1-wide landing column is exactly the failure the spring
     * exists to prevent.
     */
    private static void yieldMovement(Body body) {
        body.commandMove(0f, 0f);
        body.commandJump(false);
        body.commandSprint(false);
    }

    /**
     * One-word state for {@code mc.bot.status.clutch}: idle / lip (planned, awaiting walk-off) / falling (placing
     * water, then scooping). The status contract lists these three words.
     */
    public String phase() {
        if (!armed) {
            return "idle";
        }
        if (airborne) {
            return "falling";
        }
        return "lip";
    }

    void reset() {
        armed = false;
        airborne = false;
        scoopTicks = 0;
        lipTicks = 0;
        landingX = Integer.MIN_VALUE;
        landingZ = Integer.MIN_VALUE;
    }

    /**
     * Commit to a planned MLG fall while still on the launch lip, recording the planned landing column so the airborne
     * latch can damp drift back toward it. No-op if already armed (an in-progress fall wins).
     */
    private void armPlanned(int x, int z) {
        if (armed) {
            return;
        }
        armed = true;
        landingX = x;
        landingZ = z;
    }

    /**
     * Reactively arm on an unplanned damaging fall: airborne, dropping fast, holding a water bucket, an MLG-able
     * non-hazard floor more than {@link #EMERGENCY_CLUTCH_MIN_DROP} below, and no water already in the column (existing
     * water breaks the fall and owns its own descent — also avoids hijacking a FallIntoWater landing). Holds the
     * current column as target. Gates on {@code allowWaterBucketFall} here rather than at the caller, so a dangerous
     * fall with the flag off still raises the CLUTCH-noArm alarm instead of vanishing.
     */
    private void armReactive(Body body, LivingEntity p, WorldView world) {
        if (noArmThrottle > 0) {
            noArmThrottle--;
        }
        if (armed) {
            return;
        }
        // While the elytra is open the bot is gliding, not falling to its
        // death — never hijack that descent with a water clutch (the elytra
        // process / milestone-D failsafe owns recovery if the wing closes).
        if (p.isFallFlying()) {
            return;
        }
        if (p.onGround() || p.getDeltaMovement().y >= -0.4) {
            return;
        }
        // Establish "this fall is dangerous" FIRST, then check the arm gates —
        // so a gate failure can be alarmed with its reason instead of silently
        // swallowed (the C21 class: bucket crowded out of the hotbar / the
        // permission flag left off after an env rebuild → MLG dead with zero
        // trace until the death screen).
        int cx = (int) Math.floor(p.getX());
        int cz = (int) Math.floor(p.getZ());
        BlockPos floor = floorBelow(world, cx, (int) Math.floor(p.getY()) - 1, cz, 64);
        if (floor == null) {
            return;
        }
        double remaining = p.getY() - (floor.getY() + 1);
        if (remaining <= EMERGENCY_CLUTCH_MIN_DROP) {
            return;
        }
        for (int y = (int) Math.floor(p.getY()); y >= floor.getY() + 1; y--) {
            if (world.isWater(new BlockPos(cx, y, cz))) {
                return; // water already breaks it
            } // water already breaks it
        }
        String why = null;
        if (!WorldConfig.allowWaterBucketFall) {
            why = "allowWaterBucketFall=false";
        } else if (body.equipment().isEmpty()) {
            why = "this body has no hands";
        } else if (!onHotbar(p, Items.WATER_BUCKET)) {
            why = "water_bucket not in hotbar";
        } else if (!world.isMlgFloor(floor) || world.isHazard(floor)) {
            why = "landing floor not MLG-able (hazard/non-solid)";
        }
        if (why != null) {
            if (ControlConfig.navigatorExpectAlarm && noArmThrottle == 0) {
                LOG.warn(
                        "[expect] CLUTCH-noArm: dangerous fall (remaining={} floor={},{},{}) but clutch cannot arm — {}",
                        String.format(Locale.ROOT, "%.1f", remaining),
                        floor.getX(),
                        floor.getY(),
                        floor.getZ(),
                        why);
                noArmThrottle = 60;
            }
            return;
        }
        armed = true;
        landingX = cx;
        landingZ = cz;
        if (GameConfig.navigatorDebug) {
            LOG.info(
                    "[clutch] emergency water-clutch armed: remaining={} y={} floor={},{},{}",
                    String.format(Locale.ROOT, "%.1f", remaining),
                    String.format(Locale.ROOT, "%.1f", p.getY()),
                    floor.getX(),
                    floor.getY(),
                    floor.getZ());
        }
    }

    /**
     * Arm on a fall the navigator planned last tick ({@link MoveSignals#planClutch}) or on an unplanned damaging one,
     * then drive the committed descent. Returns true while the clutch OWNS this tick (caller must suspend everything
     * else and yield the keys); false when not armed, or armed-but-still-on-the-launch-lip (let the navigator step
     * off), or just disarmed this tick.
     */
    public boolean tick(Body body, WorldView world) {
        LivingEntity p = body.entity();
        if (p == null) {
            reset();
            return false;
        }
        MoveSignals s = world.signals();
        if (s.clutchPlanned()) {
            armPlanned(s.clutchX(), s.clutchZ());
        }
        armReactive(body, p, world);
        if (!armed) {
            return false;
        }
        Equipment hands = body.equipment().orElse(null);
        if (!p.onGround()) {
            airborne = true;
            lipTicks = 0;
            yieldMovement(body);
            // Drift control: a walk-off leaves the lip with residual horizontal
            // momentum, and air has no friction, so over a tall fall the bot
            // coasts a full block sideways — clean off a 1-wide landing column
            // (it then descends a neighbouring column with no floor, and the
            // water lands at the world bottom). Bleed the horizontal velocity
            // and add a small spring nudge toward the planned landing centre:
            // the two settle the bot directly over the column it will place
            // water in, so "straight down" hits the intended block. Converges
            // to zero drift right at the centre (nudge → 0 as offset → 0), so
            // it can't push the bot past.
            if (landingX != Integer.MIN_VALUE) {
                Vec3 v = p.getDeltaMovement();
                double ox = (landingX + 0.5) - p.getX();
                double oz = (landingZ + 0.5) - p.getZ();
                double nudgeX = Math.max(-0.04, Math.min(0.04, ox * 0.08));
                double nudgeZ = Math.max(-0.04, Math.min(0.04, oz * 0.08));
                p.setDeltaMovement(v.x * 0.5 + nudgeX, v.y, v.z * 0.5 + nudgeZ);
            }
            int cx = (int) Math.floor(p.getX());
            int cz = (int) Math.floor(p.getZ());
            BlockPos floor = floorBelow(world, cx, (int) Math.floor(p.getY()) - 1, cz, 24);
            if (floor != null) {
                BlockPos cell = floor.offset(0, 1, 0); // water spawns here
                double aboveFloor = p.getY() - cell.getY();
                if (p.getDeltaMovement().y < -0.1
                        && aboveFloor > 0.4
                        && aboveFloor <= MLG_PLACE_HEIGHT
                        && !world.isWater(cell)
                        && hands != null
                        && hands.holdItem(Items.WATER_BUCKET)) {
                    p.setXRot(89.9f); // straight down → POV ray hits the floor
                    InteractionResult pr = hands.useItemInHand();
                    if (pr.consumesAction()) {
                        p.swing(InteractionHand.MAIN_HAND);
                    }
                    if (GameConfig.navigatorDebug) {
                        LOG.info(
                                "[clutch] mlg-place cell={},{},{} aboveFloor={} y={} r={}",
                                cell.getX(),
                                cell.getY(),
                                cell.getZ(),
                                String.format(Locale.ROOT, "%.2f", aboveFloor),
                                String.format(Locale.ROOT, "%.2f", p.getY()),
                                pr);
                    }
                }
            }
            return true;
        } else if (airborne) {
            // Landed after the fall → scoop the source back, then disarm.
            if (scoopPending(world, p) && hands != null && hands.holdItem(Items.BUCKET)) {
                yieldMovement(body);
                scoopTicks++;
                BlockPos feet = blockPosOf(p.getX(), p.getY(), p.getZ());
                body.aimAtBlock(feet);
                InteractionResult sr = hands.useItemInHand();
                if (sr.consumesAction()) {
                    p.swing(InteractionHand.MAIN_HAND);
                }
                if (GameConfig.navigatorDebug) {
                    LOG.info(
                            "[clutch] mlg-scoop feet={},{},{} t={}", feet.getX(), feet.getY(), feet.getZ(), scoopTicks);
                }
                return true;
            }
            reset();
            return false;
        }
        // armed but still on the launch lip (not yet airborne) → let the navigator
        // step us off the edge. If it never does (path changed / goal cancelled
        // before the walk-off), disarm so a stale planned arm can't hijack an
        // unrelated later jump.
        if (++lipTicks > LIP_TIMEOUT_TICKS) {
            reset();
        }
        return false;
    }

    /**
     * True while a placed source still needs scooping: scoop enabled, grounded standing IN water, an empty bucket on
     * the hotbar, and the scoop budget not yet exhausted. Checks the feet cell (where the bot actually landed), so
     * horizontal drift can't strand the placed source.
     */
    private boolean scoopPending(WorldView w, LivingEntity p) {
        if (!ControlConfig.waterBucketScoop || !p.onGround() || scoopTicks >= MLG_SCOOP_MAX) {
            return false;
        }
        BlockPos feet = blockPosOf(p.getX(), p.getY(), p.getZ());
        return w.isWater(feet) && onHotbar(p, Items.BUCKET);
    }

    private static boolean onHotbar(LivingEntity p, Item item) {
        return p instanceof Player pl && Hotbar.slotOf(pl, item) >= 0;
    }

    /**
     * First solid block at or below {@code (x, yStart, z)}, scanning down up to {@code maxDepth} blocks — the impact
     * floor under a falling column. Null if only air/void within range.
     */
    private static BlockPos floorBelow(WorldView w, int x, int yStart, int z, int maxDepth) {
        for (int y = yStart; y >= yStart - maxDepth; y--) {
            BlockPos b = new BlockPos(x, y, z);
            if (w.isSolid(b)) {
                return b;
            }
        }
        return null;
    }
}

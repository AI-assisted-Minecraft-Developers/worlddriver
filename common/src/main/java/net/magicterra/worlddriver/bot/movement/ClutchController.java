package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;

public final class ClutchController {

    /** The one always-on water-bucket clutch — one client = one falling
     *  player. Ticked at the top of {@code clientTick} so it self-rescues
     *  whether or not a process is driving the bot. */
    public static final ClutchController CLUTCH = new ClutchController();
    /** Blocks above the landing floor at which water placement starts. Must
     *  stay within block-interaction reach (~4.5 from the eye, i.e. ~2.9 above
     *  the floor); a touch over is fine — the first in-reach tick succeeds and
     *  later ticks see the water and stop. */
    private static final double MLG_PLACE_HEIGHT = 3.0;
    /** Give up scooping a placed source after this many ticks (leave it) so a
     *  stubborn scoop can't pin the bot forever. */
    private static final int MLG_SCOOP_MAX = 30;
    /** Remaining drop (feet to landing surface) above which the reactive
     *  clutch arms an UNPLANNED fall. A fall this tall deals real damage
     *  (vanilla starts hurting above 3) and still leaves room to place water
     *  in the final {@link #MLG_PLACE_HEIGHT}-block window. Lower would clutch
     *  harmless step-downs and waste the bucket; higher would let
     *  survivable-but-costly falls hurt. */
    private static final double EMERGENCY_CLUTCH_MIN_DROP = 5.0;

    /** A planned arm waits on the launch lip for the walk-off; if the bot
     *  hasn't gone airborne within this many grounded ticks the step-off was
     *  abandoned (path changed, goal cancelled) → disarm so the arm can't
     *  hijack an unrelated later jump. Reactive arms are already airborne, so
     *  they never sit in this window. */
    private static final int LIP_TIMEOUT_TICKS = 20;

    private boolean armed;        // committed to a water-bucket fall → own the descent
    private boolean airborne;     // have actually left the launch ground this fall
    private int scoopTicks;       // ticks spent scooping the placed source
    private int lipTicks;         // grounded ticks since a planned arm, before going airborne
    private int noArmThrottle;    // CLUTCH-noArm alarm throttle (one line per fall, not per tick)
    private int landingX = Integer.MIN_VALUE;  // target landing column X (MIN_VALUE = current column)
    private int landingZ = Integer.MIN_VALUE;  // target landing column Z

    boolean armed() { return armed; }

    /**
     * Yield the movement channel for this tick: the clutch OWNS the descent, so nothing the
     * walker or a process commanded may move the body sideways or add a jump.
     *
     * <p>Was two byte-identical six-line blocks clearing keyUp/Down/Left/Right/Jump/Sprint —
     * and clearing those keys did nothing at all while a process was driving:
     * {@link AvatarInput#tick} runs vanilla's key pass and then OVERWRITES the impulses with
     * the walker's command, so the four direction keys were the one input nobody read. During
     * an MLG that is not cosmetic: the drift-damping spring below fights the residual momentum
     * one way while the still-live walker command pushes the other, and coasting one block off
     * a 1-wide landing column is exactly the failure the spring exists to prevent.
     * {@link Avatar#commandMove} is the channel that outranks the walker's own command.
     *
     * <p>{@code keySprint.setDown(false)} is gone rather than translated: both sites already
     * paired it with {@code setSprinting(false)}, which is the flag {@code aiStep} actually
     * reads to emit STOP_SPRINTING. The key was redundant at both.
     */
    private static void yieldMovement(Minecraft mc) {
        ClientPlayerAvatar a = new ClientPlayerAvatar(mc);
        a.commandMove(0f, 0f);
        a.commandJump(false);
        a.commandSprint(false);
    }

    /** One-word state for {@code mc.bot.status.clutch}: idle / lip (planned,
     *  awaiting walk-off) / falling (placing water) / landed (scooping). */
    public String phase() {
        if (!armed) return "idle";
        if (airborne) return "falling";
        return "lip";
    }

    void reset() {
        armed = false; airborne = false; scoopTicks = 0; lipTicks = 0;
        landingX = Integer.MIN_VALUE; landingZ = Integer.MIN_VALUE;
    }

    /** Commit to a planned MLG fall while still on the launch lip, recording
     *  the planned landing column so the airborne latch can damp drift back
     *  toward it. No-op if already armed (an in-progress fall wins). */
    public void armPlanned(int x, int z) {
        if (armed) return;
        armed = true;
        landingX = x;
        landingZ = z;
    }

    /** Reactively arm on an unplanned damaging fall: airborne, dropping fast,
     *  holding a water bucket, an MLG-able non-hazard floor more than {@link
     *  #EMERGENCY_CLUTCH_MIN_DROP} below, and no water already in the column
     *  (existing water breaks the fall and owns its own descent — also avoids
     *  hijacking a FallIntoWater landing). Holds the current column as target. */
    public void armReactive(Minecraft mc, WorldView world) {
        LocalPlayer p = mc.player;
        if (noArmThrottle > 0) noArmThrottle--;
        if (p == null || armed) return;
        // While the elytra is open the bot is gliding, not falling to its
        // death — never hijack that descent with a water clutch (the elytra
        // process / milestone-D failsafe owns recovery if the wing closes).
        if (p.isFallFlying()) return;
        if (p.onGround() || p.getDeltaMovement().y >= -0.4) return;
        // Establish "this fall is dangerous" FIRST, then check the arm gates —
        // so a gate failure can be alarmed with its reason instead of silently
        // swallowed (the C21 class: bucket crowded out of the hotbar / the
        // permission flag left off after an env rebuild → MLG dead with zero
        // trace until the death screen).
        int cx = (int) Math.floor(p.getX()), cz = (int) Math.floor(p.getZ());
        BlockPos floor = floorBelow(world, cx, (int) Math.floor(p.getY()) - 1, cz, 64);
        if (floor == null) return;
        double remaining = p.getY() - (floor.getY() + 1);
        if (remaining <= EMERGENCY_CLUTCH_MIN_DROP) return;
        for (int y = (int) Math.floor(p.getY()); y >= floor.getY() + 1; y--)
            if (world.isWater(new BlockPos(cx, y, cz))) return;   // water already breaks it
        String why = null;
        if (!BotConfig.allowWaterBucketFall) why = "allowWaterBucketFall=false";
        else if (hotbarSlotOf(p, Items.WATER_BUCKET) < 0) why = "water_bucket not in hotbar";
        else if (!world.isMlgFloor(floor) || world.isHazard(floor)) why = "landing floor not MLG-able (hazard/non-solid)";
        if (why != null) {
            if (BotConfig.walkerExpectAlarm && noArmThrottle == 0) {
                LOG.warn("[expect] CLUTCH-noArm: dangerous fall (remaining={} floor={},{},{}) but clutch cannot arm — {}",
                        String.format(Locale.ROOT, "%.1f", remaining),
                        floor.getX(), floor.getY(), floor.getZ(), why);
                noArmThrottle = 60;
            }
            return;
        }
        armed = true;
        landingX = cx;
        landingZ = cz;
        if (BotConfig.walkerDebug)
            LOG.info(
                    "[clutch] emergency water-clutch armed: remaining={} y={} floor={},{},{}",
                    String.format(Locale.ROOT, "%.1f", remaining),
                    String.format(Locale.ROOT, "%.1f", p.getY()),
                    floor.getX(), floor.getY(), floor.getZ());
    }

    /** Drive the committed descent. Returns true while the clutch OWNS this
     *  tick (caller must suspend everything else and yield the keys); false
     *  when not armed, or armed-but-still-on-the-launch-lip (let the walker
     *  step off), or just disarmed this tick. */
    public boolean tick(Minecraft mc, WorldView world) {
        if (!armed) return false;
        LocalPlayer p = mc.player;
        if (p == null) { reset(); return false; }
        if (!p.onGround()) {
            airborne = true;
            lipTicks = 0;
            yieldMovement(mc);
            // Drift control: a walk-off leaves the lip with residual horizontal
            // momentum, and air has no friction, so over a tall fall the body
            // coasts a full block sideways — clean off a 1-wide landing column
            // (it then descends a neighbouring column with no floor, and the
            // water lands at the world bottom). Bleed the horizontal velocity
            // and add a small spring nudge toward the planned landing centre:
            // the two settle the body directly over the column it will place
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
            int cx = (int) Math.floor(p.getX()), cz = (int) Math.floor(p.getZ());
            BlockPos floor = floorBelow(world, cx, (int) Math.floor(p.getY()) - 1, cz, 24);
            if (floor != null) {
                BlockPos cell = floor.offset(0, 1, 0);     // water spawns here
                double aboveFloor = p.getY() - cell.getY();
                if (p.getDeltaMovement().y < -0.1 && aboveFloor > 0.4 && aboveFloor <= MLG_PLACE_HEIGHT
                        && !world.isWater(cell)
                        && ensureHolding(mc, Items.WATER_BUCKET)) {
                    p.setXRot(89.9f);                      // straight down → POV ray hits the floor
                    InteractionResult pr = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    if (pr.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
                    if (BotConfig.walkerDebug)
                        LOG.info(
                                "[clutch] mlg-place cell={},{},{} aboveFloor={} y={} r={}",
                                cell.getX(), cell.getY(), cell.getZ(),
                                String.format(Locale.ROOT, "%.2f", aboveFloor),
                                String.format(Locale.ROOT, "%.2f", p.getY()), pr);
                }
            }
            return true;
        } else if (airborne) {
            // Landed after the fall → scoop the source back, then disarm.
            if (scoopPending(world, p) && ensureHolding(mc, Items.BUCKET)) {
                yieldMovement(mc);
                scoopTicks++;
                // Three doubles, not `p`: widening a LocalPlayer into blockPosOf(Entity) makes
                // the verifier load that class.
                BlockPos feet = blockPosOf(p.getX(), p.getY(), p.getZ());
                aimAtBlockSnap(p, feet);
                InteractionResult sr = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                if (sr.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
                if (BotConfig.walkerDebug)
                    LOG.info(
                            "[clutch] mlg-scoop feet={},{},{} t={}", feet.getX(), feet.getY(), feet.getZ(), scoopTicks);
                return true;
            }
            reset();
            return false;
        }
        // armed but still on the launch lip (not yet airborne) → let the walker
        // step us off the edge. If it never does (path changed / goal cancelled
        // before the walk-off), disarm so a stale planned arm can't hijack an
        // unrelated later jump.
        if (++lipTicks > LIP_TIMEOUT_TICKS) reset();
        return false;
    }

    /** True while a placed source still needs scooping: scoop enabled,
     *  grounded standing IN water, an empty bucket on the hotbar, and the
     *  scoop budget not yet exhausted. Checks the feet cell (where the bot
     *  actually landed), so horizontal drift can't strand the placed source. */
    private boolean scoopPending(WorldView w, LocalPlayer p) {
        if (!BotConfig.waterBucketScoop || !p.onGround() || scoopTicks >= MLG_SCOOP_MAX) return false;
        // Three doubles, not `p`: widening a LocalPlayer into blockPosOf(Entity) makes the
        // verifier load that class.
        BlockPos feet = blockPosOf(p.getX(), p.getY(), p.getZ());
        return w.isWater(feet) && hotbarSlotOf(p, Items.BUCKET) >= 0;
    }

    /** First solid block at or below {@code (x, yStart, z)}, scanning down up
     *  to {@code maxDepth} blocks — the impact floor under a falling column.
     *  Null if only air/void within range. */
    private static BlockPos floorBelow(WorldView w, int x, int yStart, int z, int maxDepth) {
        for (int y = yStart; y >= yStart - maxDepth; y--) {
            BlockPos b = new BlockPos(x, y, z);
            if (w.isSolid(b)) return b;
        }
        return null;
    }
}

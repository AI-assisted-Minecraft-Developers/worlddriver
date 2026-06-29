package net.magicterra.agent.bot.auto;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import static net.magicterra.agent.AgentDriverCommon.LOG;

/**
 * Baritone-style autoSwim drowning backstop + active shore-seek.
 *
 * <p>Two jobs:
 * <ol>
 *   <li><b>Lift:</b> hold jump while the head is submerged so the player rises
 *       toward the surface (vanilla in-water jump ≈ 0.04 b/t of lift).</li>
 *   <li><b>Beach (idle only):</b> a bot that respawns or falls into a lake with
 *       NO movement process used to just bob at the surface until something
 *       (a mob, a current, a 1-deep head-dunk) drowned it — the spawn-water
 *       death-loop. Now, when idle and in water, steer toward the nearest dry
 *       standable shore and press forward, so the bot actually swims OUT.</li>
 * </ol>
 * The beach step runs ONLY when idle: an active process (goto/runAway) drives
 * its own water-escape moves ({@code SwimAshoreBreak}/{@code SwimUpBreak}) and we
 * must not fight its steering. The lift step is unconditional (compatible with a
 * walking Walker — both want the surface).
 */
public final class AutoSwim {
    private AutoSwim() {}

    /** Max horizontal (Chebyshev) rings scanned for a shore when beaching. */
    private static final int SHORE_SCAN_R = 10;
    /** How far above the bot's foot a climbable bank may sit. Capped at 1: the IDLE
     *  beach step has no foothold-placer (only an active-process Walker does, via the
     *  waterClimb actuator), so a swimming bot can only mount a single +1 step out of
     *  water. Accepting +2..+4 banks made it steer into a sheer 2–4 block face it
     *  can't climb and grind/bob there (drowning risk). Matches the [by-1, by+1] range
     *  documented on {@link #nearestShore}. */
    private static final int SHORE_UP = 1;
    /** How far below (a shallow/beach exit). */
    private static final int SHORE_DOWN = 1;
    /** Throttle counter for walkerDebug shore logging. */
    private static int DBG = 0;

    /** Legacy 2-arg entry (lift only) kept for any caller that lacks a WorldView. */
    public static void tick(Minecraft mc, LocalPlayer p) {
        if (p.isInWater() && p.isUnderWater()) {
            mc.options.keyJump.setDown(true);
        } else {
            mc.options.keyJump.setDown(false);
        }
    }

    /**
     * Full backstop. {@code idle} = no movement process owns the channel this
     * tick (only then do we steer, to avoid fighting an active pathing process).
     */
    public static void tick(Minecraft mc, LocalPlayer p, WorldView world, boolean idle) {
        // DRIVER POSITIONING: the driver only EXECUTES agent commands. With NO command (idle = no
        // movement process owns the bot this tick), it must not move the bot at all. A bot left in
        // water with no goal may be deliberately waiting, or holding a submerged position to reach
        // an underwater target — beaching it, or force-surfacing it with a held jump, is the driver
        // acting on its own (a survival reflex), which is exactly what must NOT happen with no
        // command. So when idle: drive nothing and let the idle key-release settle the bot per
        // physics. The lift + shore-steer below run ONLY under an active process (the command's own
        // executor), as an in-process drowning backstop — never as an unprompted idle behavior.
        if (idle) return;
        boolean inWater = p.isInWater();
        if (inWater && p.isUnderWater()) {
            mc.options.keyJump.setDown(true);
        } else if (!inWater) {
            mc.options.keyJump.setDown(false);
        }
        // (idle already returned above.) In-process drowning backstop: a goto/runAway that gets the
        // bot stuck submerged (the lake death-loop: drowned at y61 mid-path) gives no steering of its
        // own; surfacing/beaching beats drowning, and the process resumes once back at the surface.
        // Only the SUBMERGED head triggers this — a process crossing AT the surface keeps its own steer.
        boolean steer = p.isUnderWater() && inWater && world != null;
        if (!steer) return;
        // Fully out on dry land (on ground AND head clear of water) — done; let the
        // idle releaseKeys() take over. While still IN water, keep steering even if
        // momentarily on a shallow bottom, so the bot walks up the entry slope.
        if (p.onGround() && !inWater) { mc.options.keyUp.setDown(false); return; }

        int bx = (int) Math.floor(p.getX());
        int by = (int) Math.floor(p.getY());
        int bz = (int) Math.floor(p.getZ());
        // DEEP ASCENT: head still has water well above it → rise STRAIGHT up with
        // NO horizontal input. Steering toward a bank while deep pushes the body
        // into the column wall, wedging it so it bobs in place and burns air — the
        // 9-block spawn-column drown (fell to y53, drowned at y61 mid-climb). Pure
        // vertical beelines to air; the horizontal shore-steer below only kicks in
        // once the head nears the surface (cell 2 above the foot is no longer water).
        if (p.isUnderWater() && world.isWater(new BlockPos(bx, by + 2, bz))) {
            mc.options.keyJump.setDown(true);
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keySprint.setDown(false);
            if (BotConfig.walkerDebug && (DBG++ % 8 == 0))
                LOG.info("[autoSwim] deep-ascent straight-up pos={},{},{} air={}",
                        bx, by, bz, p.getAirSupply());
            return;
        }
        int[] dir = nearestShore(world, bx, by, bz);
        if (BotConfig.walkerDebug && (DBG++ % 8 == 0)) {
            LOG.info("[autoSwim] pos={},{},{} under={} onGround={} idle={} shoreDir={}",
                    bx, by, bz, p.isUnderWater(), p.onGround(), idle,
                    dir == null ? "null" : (dir[0] + "," + dir[1]));
        }
        if (dir == null) {                          // no reachable bank in range: keep rising
            if (p.isUnderWater()) mc.options.keyJump.setDown(true);
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-(double) dir[0], (double) dir[1]));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(0f);                              // swim flat toward the bank
        mc.options.keyUp.setDown(true);
        // Stay buoyant while escaping: hold jump until truly on dry land, so the bot
        // rises to the surface AND hops up 1–2 block banks instead of bobbing.
        if (!p.onGround()) mc.options.keyJump.setDown(true);
    }

    /**
     * Nearest column (by Chebyshev ring, then squared distance within the ring)
     * holding a dry standable spot in {@code [by-1, by+1]} — a bank the bot can
     * climb onto. Returns {dx,dz} from the bot toward it, or null if none within
     * {@link #SHORE_SCAN_R}.
     */
    private static int[] nearestShore(WorldView w, int bx, int by, int bz) {
        // Scan ring by ring (nearest first). Within the first ring that has any dry
        // standable bank, pick the LOWEST one (gentlest to climb out onto), tie-broken
        // by horizontal distance. Scanning low→high per column and keeping the lowest
        // hit means a shallow beach is always preferred over a tall cliff edge.
        for (int r = 1; r <= SHORE_SCAN_R; r++) {
            int bestY = Integer.MAX_VALUE, bestD = Integer.MAX_VALUE, bdx = 0, bdz = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // this ring only
                    int x = bx + dx, z = bz + dz;
                    for (int y = by - SHORE_DOWN; y <= by + SHORE_UP; y++) {
                        BlockPos foot = new BlockPos(x, y, z);
                        BlockPos below = new BlockPos(x, y - 1, z);
                        BlockPos head = new BlockPos(x, y + 1, z);
                        if (w.isSolid(below) && !w.isWater(foot)
                                && w.isPassable(foot) && w.isPassable(head)
                                && !w.isHazard(below)) {
                            int d = dx * dx + dz * dz;
                            if (y < bestY || (y == bestY && d < bestD)) {
                                bestY = y; bestD = d; bdx = dx; bdz = dz;
                            }
                            break;                         // lowest standable y in this column
                        }
                    }
                }
            }
            if (bestY != Integer.MAX_VALUE) return new int[]{bdx, bdz};
        }
        return null;
    }
}

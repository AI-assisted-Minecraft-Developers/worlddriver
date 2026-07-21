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
 *
 * <p><b>gap#70 boundary (live death #18), see {@link #drowningSentinel}:</b> the
 * paragraph above is precisely why an IDLE bot got none of this — {@link #tick}
 * returns immediately when idle (by design: the driver must not move the bot on
 * its own with no command). {@link #drowningSentinel} is the deliberate carve-out
 * for that gap: a PURE VERTICAL float (hold jump only, never forward/turn/beach)
 * is a survival reflex, not the "autonomous movement" the idle-passive contract
 * forbids — same boundary P1 already drew for combat (hurt-entry retreat reacts
 * to being hit while idle).
 */
public final class AutoSwim {
    private AutoSwim() {}

    /** True while {@link #drowningSentinel} is the one holding jump, so it
     *  releases its own hold exactly once when air recovers/surfaces, without
     *  clobbering a jump some other actuator set. */
    private static boolean floatHeld;

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
    /** Air ticks kept in reserve above the drownEscape threshold while yielding to an
     *  active dig (~1 s): the backstop resumes lifting BEFORE the escape chain preempts,
     *  so a dig that runs the lungs down hands over smoothly instead of at the wire. */
    private static final int DIG_AIR_RESERVE = 20;
    /** Throttle counter for walkerDebug shore logging. */
    private static int DBG = 0;

    /** Idle drowning REFLEX — must be called UNCONDITIONALLY every client tick, same as
     *  its predecessor (NOT behind the autoSwim flag: with autoSwim off, an idle bot left
     *  submerged after a cancelled goto has no walker and no DrowningEscape, and it
     *  drowned silently twice live 2026-07-02).
     *
     *  <p><b>gap#70 (live death #18):</b> this used to be ALARM-only — it computed the
     *  exact trigger condition every tick ({@code isUnderWater() && air<=100}) and just
     *  WARNed, on the theory that any self-rescue here would violate "driver idle must be
     *  passive". That theory was wrong: a bot tp'd into a ~29-block-deep river with no
     *  task sank to the bottom and drowned air 16→0 in ~40 s while this method logged the
     *  WARN the entire time and did nothing. <b>Controller ruling</b> (see also {@link
     *  BotConfig#autoFloatWhenDrowning}, {@link DrowningFloatGate}, and the class doc
     *  above): the idle-passive contract was always about forbidding UNCOMMANDED
     *  HORIZONTAL movement/beaching, never about letting the bot drown — P1 already drew
     *  this exact line for combat (hurt-entry retreat fires while idle, gap#68). A PURE
     *  VERTICAL float — hold jump ONLY, never {@code keyUp}/{@code keyLeft}/{@code
     *  keyRight}/yaw, never the shore-steer above — is the same class of survival reflex,
     *  in-bounds for idle. So this now DRIVES {@code keyJump} once air is critical,
     *  gated by {@link DrowningFloatGate#shouldFloat} (pure, matrix-tested) and {@link
     *  BotConfig#autoFloatWhenDrowning} — independent of {@link BotConfig#autoSwim} on
     *  purpose, since it's a bare reflex (the {@code AntiSuffocate} pattern), not the
     *  autoSwim movement/beach feature.
     *
     *  @return true iff this call drove {@code keyJump} (so the caller can mark the
     *          release gate dirty and avoid a trailing held jump). */
    public static boolean drowningSentinel(Minecraft mc, LocalPlayer p, boolean idle) {
        if (!idle || p == null) {
            if (floatHeld) { mc.options.keyJump.setDown(false); floatHeld = false; }
            return false;
        }
        boolean floating = DrowningFloatGate.shouldFloat(p.isUnderWater(), p.getAirSupply(),
                BotConfig.drownFloatAirThreshold, BotConfig.autoFloatWhenDrowning);
        if (floating) {
            mc.options.keyJump.setDown(true);
            floatHeld = true;
            if (BotConfig.walkerDebug && (DBG++ % 20 == 0))
                LOG.info("[drowningFloat] idle + underwater + air={} <= threshold {} → holding jump to surface at {},{},{}",
                        p.getAirSupply(), BotConfig.drownFloatAirThreshold, (int) p.getX(), (int) p.getY(), (int) p.getZ());
        } else if (floatHeld) {
            mc.options.keyJump.setDown(false);
            floatHeld = false;
        }
        return floating;
    }

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
        // DIG-PRIORITY YIELD (2026-07-21 live, flooded Mountains channel): while the
        // Walker holds an active block-break this tick and air is still healthy, the
        // whole in-process backstop stands down. Before this gate, deep-ascent had NO
        // air condition — the moment the head was submerged it force-held jump and
        // zeroed every horizontal key EVERY tick, from FULL lungs, bobbing the body
        // off the dig cell so vanilla reset destroyProgress; the dig looped to the
        // sticky-dig 4000t cap without ever finishing a single block. The Walker only
        // starts underwater digs that fit one breath (breath-feasibility gate in
        // WalkerTickClimb), so yielding down to the escape floor is safe: below
        // threshold+reserve the backstop resumes, and DrownEscapeChain (bid 500,
        // air<=drownEscapeAirThreshold) remains the untouched hard survival floor.
        if (BotConfig.walkerDigActive
                && p.getAirSupply() > BotConfig.drownEscapeAirThreshold + DIG_AIR_RESERVE) return;
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
            // gap#80: a held sneak SINKS the bot (DrownEscapeChain.tick's own comment) — a
            // concurrently-latched sneak from a prior process would defeat this straight-up climb.
            mc.options.keyShift.setDown(false);
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

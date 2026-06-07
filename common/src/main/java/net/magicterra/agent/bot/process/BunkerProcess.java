package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.movement.BotInput;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;

import java.util.Locale;

import static net.magicterra.agent.AgentDriverCommon.LOG;
import static net.magicterra.agent.bot.util.BotInteract.aimAtBlockSnap;
import static net.magicterra.agent.bot.util.BotInteract.ensureHoldingPlaceableAny;
import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;
import static net.magicterra.agent.bot.util.BotInteract.selectBestToolFor;
import static net.magicterra.agent.bot.util.BotInteract.walkerPlace;

/**
 * Agent-invoked "挖三填一" bunker — a sand-SAFE emergency shelter. The Agent calls
 * {@code mc.bot.bunker{depth}} when it plans to (e.g. at sunset when exposed); it
 * is NOT an auto-reflex.
 *
 * <p>Why not the naive "dig down + place a block overhead"? In a desert that cap
 * would be SAND, and a sand block placed into the shaft has only air beneath it,
 * so it falls straight back down onto the bot's head → suffocation, roof still
 * open (the user's catch). Instead this digs down, carves a 2-tall HORIZONTAL
 * niche off the bottom, steps into it, and plugs the shaft behind with blocks
 * that have SUPPORT BELOW — so even sand stays put. The niche roof is undug
 * terrain (already supported), so nothing has to be placed overhead at all.
 *
 * <p>Result: a sealed 1×1 pocket offset from the shaft, no falling-block hazard.
 * Needs hand-droppable walls (sand/dirt/gravel — bare stone by hand drops nothing
 * to plug with, so it bails). Aborts on water/lava/bedrock.
 */
public final class BunkerProcess implements BotProcess {

    private enum Phase { DIG_DOWN, CARVE, STEP_IN, PLUG, SEALED, DONE }

    private final int depth;
    // Niche depth may grow past the requested `depth` when the niche head would
    // otherwise sit at/near the surface (open-sky roof) or be roofed by a falling
    // block — we dig deeper until the roof is a solid, non-falling block.
    private int effectiveDepth;
    private static final int MAX_EXTRA_DEPTH = 4;
    private Phase phase = Phase.DIG_DOWN;
    private int startY = Integer.MIN_VALUE;
    private int lastDepth = 0, digTicks = 0, actTicks = 0;
    /** Separate place-attempt timer for PLUG. Kept distinct from {@link #actTicks}
     *  (which the body-overlap shuffle branch burns up to breakTimeoutTicks*2) so a
     *  long shuffle can't make the FIRST real placement instantly trip the place
     *  timeout and abort the bunker DONE-but-UNSEALED. */
    private int plugTicks = 0;
    private Direction nicheDir = null;       // horizontal direction of the niche
    private BlockPos bottom = null;          // shaft-bottom foot cell

    public BunkerProcess(int depth) {
        this.depth = Math.max(1, Math.min(5, depth));
        this.effectiveDepth = this.depth;
    }

    /** A block that falls if unsupported (sand/red_sand/gravel/…) — unsafe as a
     *  niche roof, since carving the head leaves air beneath it and it drops in. */
    private static boolean isFalling(Minecraft mc, BlockPos pos) {
        return mc.level != null && mc.level.getBlockState(pos).getBlock() instanceof FallingBlock;
    }

    private static void dbg(String msg, Object... a) {
        if (BotConfig.walkerDebug) LOG.info("[bunker] " + msg, a);
    }

    @Override public String kind() { return "bunker"; }
    @Override public void attach(BotState st) {}

    /** Surfaced as {@code activeProcessDetail} in mc.bot.status so the agent can
     *  tell a working bunker (DIG_DOWN/CARVE/STEP_IN/PLUG) from a SEALED one
     *  (success — hold until dawn, then cancel to break out) or a DONE one
     *  (finished; if it bailed the pocket may be UNSEALED — verify the shaft is
     *  plugged before trusting it). SEALED is the only "safe to walk away" state. */
    @Override public String statusDetail() { return phase.name(); }

    @Override public boolean tick(Minecraft mc, WorldView w, BotState st) {
        LocalPlayer p = mc.player;
        if (p == null) return true;
        BlockPos foot = p.blockPosition();
        if (startY == Integer.MIN_VALUE) {
            startY = foot.getY();
            // Center on the column so the shaft/niche cells are unambiguous.
            p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);
            // Water near the dig footprint floods a 1-wide shaft and traps the bot
            // (the spawn-water trap that stranded it at HP 3.6). But the low flats
            // around spawn (y61-63) have the water table just 1-2 below the surface,
            // so a blanket "abort if water within depth+2" left the bot with NO night
            // shelter anywhere in the flats (the night death-loop). Instead, AUTO-FIT
            // the niche depth to sit ABOVE the water table: take the deepest d ≤ the
            // requested depth where the shaft [foot..foot-d], the stand block
            // (foot-d-1) and the shaft's horizontal neighbours are all water-free.
            // Only when even a 1-deep niche is wet (truly in/at the water) do we abort
            // and ask the Agent to relocate.
            Direction[] horiz = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            int fit = 0;
            for (int d = 1; d <= effectiveDepth; d++) {
                boolean dry = !w.isWater(foot.offset(0, -d, 0))        // dug floor cell
                           && !w.isWater(foot.offset(0, -d - 1, 0));   // block the bot stands on
                for (int dy = 0; dy <= d && dry; dy++) {
                    BlockPos cell = foot.offset(0, -dy, 0);
                    for (Direction dir : horiz) {
                        if (w.isWater(cell.relative(dir))) { dry = false; break; }
                    }
                }
                if (dry) fit = d; else break;   // first wet depth caps the fit (deeper is wetter)
            }
            if (fit == 0) {
                dbg("ABORT bunker: water at/around even the shallowest niche at {}", foot);
                releaseKeys(); return true;
            }
            if (fit < effectiveDepth) dbg("bunker: water table → shrink depth {}→{}", effectiveDepth, fit);
            effectiveDepth = fit;
        }
        // Hold horizontal position while working (except the deliberate STEP_IN walk).
        if (phase != Phase.STEP_IN) p.setDeltaMovement(0, p.getDeltaMovement().y, 0);

        switch (phase) {
            case DIG_DOWN: return digDown(mc, w, p, foot);
            case CARVE:    return carve(mc, w, p);
            case STEP_IN:  return stepIn(mc, w, p, foot);
            case PLUG:     return plug(mc, w, p);
            // Stay SEALED after a successful plug: keep holding the channel (this is
            // a BunkerProcess, so UserTaskChain reports BUNKER priority 300 > combat
            // 60 — see GAP #20) so autoFight/idle can't walk the bot out of its
            // pocket and get it killed at night (GAP #22). Released only when the
            // Agent calls mc.bot.cancel (typically at dawn, then it breaks out).
            case SEALED:   releaseKeys(); return false;
            default:       releaseKeys(); return true;
        }
    }

    private boolean digDown(Minecraft mc, WorldView w, LocalPlayer p, BlockPos foot) {
        int d = startY - foot.getY();
        if (d != lastDepth) { lastDepth = d; digTicks = 0; }
        if (d >= effectiveDepth) {
            bottom = foot.immutable();
            phase = Phase.CARVE;
            actTicks = 0;
            mc.options.keyAttack.setDown(false);
            dbg("DIG_DOWN done bottom={} (dug {} down from y={})", bottom, effectiveDepth, startY);
            return false;
        }
        BlockPos below = foot.below();
        if (w.isWater(foot) || w.isWater(foot.offset(0, 1, 0))
                || w.isWater(below) || w.isHazard(below) || w.isHazard(foot.offset(0, 1, 0))) {
            mc.options.keyAttack.setDown(false); releaseKeys(); return true;   // unsafe
        }
        if (!w.isSolid(below)) return false;                                   // mid-fall, settle
        selectBestToolFor(mc, below);
        aimAtBlockSnap(p, below);
        mc.options.keyAttack.setDown(true);
        if (++digTicks > BotConfig.breakTimeoutTicks) { mc.options.keyAttack.setDown(false); releaseKeys(); return true; }
        return false;
    }

    private boolean carve(Minecraft mc, WorldView w, LocalPlayer p) {
        if (nicheDir == null) {
            // Pick a cardinal whose 2-tall niche is a solid (diggable) wall with a
            // solid floor (so we can stand), a SOLID NON-FALLING ROOF (n1.above() —
            // else the pocket is open to the sky or roofed by sand that falls in),
            // and won't immediately flood.
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos n0 = bottom.relative(d);
                BlockPos n1 = n0.above();
                BlockPos roof = n1.above();
                if (w.isSolid(n0) && w.isSolid(n1) && w.isSolid(n0.below())
                        && w.isSolid(roof) && !isFalling(mc, roof)
                        && !w.isWater(n0) && !w.isWater(n1) && !w.isHazard(n0) && !w.isHazard(n1)
                        && !w.isWater(n0.relative(d))) {     // not opening straight into water
                    nicheDir = d; break;
                }
            }
            if (nicheDir == null) {
                // No direction has a solid non-falling roof at this depth — the
                // niche head sits at/near the surface (open sky) or under falling
                // sand. Dig ONE block deeper and retry so the niche tucks under
                // real terrain. Bail only once we've exhausted the depth budget
                // (e.g. pure sand over un-mineable stone — genuinely no safe roof).
                if (effectiveDepth - depth < MAX_EXTRA_DEPTH) {
                    effectiveDepth++;
                    phase = Phase.DIG_DOWN; bottom = null; actTicks = 0;
                    dbg("CARVE no solid non-falling roof → deepen to effDepth={}", effectiveDepth);
                    return false;
                }
                dbg("CARVE no solid non-falling roof within depth budget → BAIL (would be open-air)");
                releaseKeys(); return true;
            }
            dbg("CARVE niche dir={} roof={} (n0={})", nicheDir,
                    bottom.relative(nicheDir).above().above(), bottom.relative(nicheDir));
        }
        BlockPos n0 = bottom.relative(nicheDir);
        BlockPos n1 = n0.above();
        BlockPos target = w.isSolid(n1) ? n1 : (w.isSolid(n0) ? n0 : null);   // clear head first, then foot
        if (target == null) { dbg("CARVE done dir={} → STEP_IN", nicheDir); phase = Phase.STEP_IN; actTicks = 0; mc.options.keyAttack.setDown(false); return false; }
        selectBestToolFor(mc, target);
        aimAtBlockSnap(p, target);
        mc.options.keyAttack.setDown(true);
        if (++actTicks > BotConfig.breakTimeoutTicks * 2) { mc.options.keyAttack.setDown(false); releaseKeys(); return true; }
        return false;
    }

    private boolean stepIn(Minecraft mc, WorldView w, LocalPlayer p, BlockPos foot) {
        mc.options.keyAttack.setDown(false);
        BlockPos n0 = bottom.relative(nicheDir);
        // Must enter the niche FULLY — pressed against its back wall — before
        // plugging. A blockPos-only match (foot.z == n0.z) fires while the bot
        // is still straddling the shaft/niche boundary (e.g. center z=297.76),
        // leaving its 0.6-wide hitbox overlapping the shaft cell so PLUG can't
        // place there → bunker ends unsealed. Gate on distance from the shaft
        // centre instead: ≥0.85 means the bot has cleared the shaft column.
        double shaftDx = p.getX() - (bottom.getX() + 0.5);
        double shaftDz = p.getZ() - (bottom.getZ() + 0.5);
        double distFromShaft = Math.sqrt(shaftDx * shaftDx + shaftDz * shaftDz);
        boolean inNiche = foot.getX() == n0.getX() && foot.getZ() == n0.getZ()
                && distFromShaft >= 0.85;
        if (inNiche) {
            releaseKeys();
            dbg("STEP_IN done foot={} distFromShaft={} → PLUG", foot, fmt(distFromShaft));
            phase = Phase.PLUG; actTicks = 0; plugTicks = 0;
            return false;
        }
        // Face the niche and walk in.
        p.setYRot(yawFor(nicheDir));
        p.setXRot(0f);
        BotInput.forward(mc, true);
        if (actTicks % 5 == 0)
            dbg("STEP_IN walking pos=({},{}) foot={} n0={} distFromShaft={} t={}",
                    fmt(p.getX()), fmt(p.getZ()), foot, n0, fmt(distFromShaft), actTicks);
        if (++actTicks > 80) {            // ~4s to shuffle one block; give up if stuck
            releaseKeys();
            dbg("STEP_IN TIMEOUT distFromShaft={} → PLUG (may be blocked)", fmt(distFromShaft));
            phase = Phase.PLUG; actTicks = 0; plugTicks = 0;
        }
        return false;
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    private boolean plug(Minecraft mc, WorldView w, LocalPlayer p) {
        BotInput.forward(mc, false);
        // Plug the shaft column the bot vacated: bottom foot then the cell above.
        // Both gain support from below (floor / the foot-plug) so even sand holds.
        BlockPos p0 = bottom;             // old foot, has solid floor under it
        BlockPos p1 = bottom.above();     // old head, supported by p0 once placed
        BlockPos target = !w.isSolid(p0) ? p0 : (!w.isSolid(p1) ? p1 : null);
        if (target == null) { dbg("PLUG sealed (p0={},p1={} both solid) → SEALED-hold", p0, p1); phase = Phase.SEALED; releaseKeys(); return false; }   // sealed → hold the pocket (GAP #22)
        if (!ensureHoldingPlaceableAny(mc)) { dbg("PLUG no placeable block in hand → DONE UNSEALED target={}", target); phase = Phase.DONE; releaseKeys(); return true; } // nothing to plug with
        // Guard: if the bot's own hitbox still overlaps the cell we're filling,
        // placement silently fails forever. Detect it and keep shuffling into
        // the niche instead of burning the timeout unsealed.
        if (p.getBoundingBox().intersects(new AABB(target))) {
            p.setYRot(yawFor(nicheDir));
            BotInput.forward(mc, true);
            dbg("PLUG body overlaps target={} pos=({},{}) → shuffle deeper", target, fmt(p.getX()), fmt(p.getZ()));
            if (++actTicks > BotConfig.breakTimeoutTicks * 2) { dbg("PLUG give up (still overlapping) → DONE UNSEALED"); phase = Phase.DONE; releaseKeys(); return true; }
            return false;
        }
        BotInput.forward(mc, false);
        aimAtBlockSnap(p, target);
        walkerPlace(mc, p, w, target);
        dbg("PLUG place target={} solidNow={} t={}", target, w.isSolid(target), actTicks);
        if (++plugTicks > BotConfig.breakTimeoutTicks) { dbg("PLUG TIMEOUT target={} solid={} → DONE", target, w.isSolid(target)); phase = Phase.DONE; releaseKeys(); return true; }
        return false;
    }

    /** Yaw that faces the given cardinal (MC: 0=+Z south, 90=-X west, 180=-Z north, -90=+X east). */
    private static float yawFor(Direction d) {
        return switch (d) {
            case SOUTH -> 0f;
            case WEST  -> 90f;
            case NORTH -> 180f;
            case EAST  -> -90f;
            default    -> 0f;
        };
    }
}

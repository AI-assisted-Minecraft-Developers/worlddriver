package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FallingBlock;

import static net.magicterra.agent.AgentDriverCommon.LOG;
import static net.magicterra.agent.bot.util.BotInteract.aimAtBlockSnap;
import static net.magicterra.agent.bot.util.BotInteract.clientUseItemOn;
import static net.magicterra.agent.bot.util.BotInteract.ensureHoldingPlaceableAny;
import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;
import static net.magicterra.agent.bot.util.BotInteract.selectBestToolFor;

/**
 * Block-less vertical pit / well escape — carve a staircase UP through the solid
 * DRY walls of a pit and climb out. The inverse of {@link BunkerProcess}, and the
 * escape a trapped bot needs when the pathfinder can't get it out.
 *
 * <p>Why this exists: a bot stuck foot-in-water in a 1-wide sandstone well at
 * spawn cannot be pathed out — A* routes it through a flush 1-wide WATER channel
 * the Walker physically can't thread (it grinds the wall and never advances),
 * while it is block-less so it can't {@code pillarUp} the open chimney either.
 * This process sidesteps the pathfinder entirely: each step it picks the driest
 * cardinal whose wall is carvable, breaks the up-forward foot+head cells, and
 * steps up onto the carved tread (a plain 1-block step-up that vanilla movement
 * handles cleanly on dry rock), repeating until the surface. No placed blocks
 * needed — it eats the wall, it doesn't build.
 *
 * <p>Crucially it climbs into the DRY rock (preferring directions with no water in
 * the niche), so the climb never re-enters the water that defeats the Walker. The
 * first step out of the water cell rides the jump + buoyancy onto the dry tread.
 *
 * <p>Needs {@link BotConfig#allowBreak} (every step mines), and a wall with a
 * solid non-falling tread to stand on; bails if no carvable direction exists or
 * a step stalls past the timeout.
 */
public final class EscapeProcess implements BotProcess {

    private enum Phase { PICK, CARVE, STEP_UP, VERT_BREAK, VERT_RISE }

    /** Climb until foot Y reaches this (or open sky is overhead). */
    private final int targetY;
    private static final int MAX_STEPS = 48;

    private Phase phase = Phase.PICK;
    private Direction dir = null;
    private BlockPos base = null;        // the foot cell we're climbing FROM this step
    private int actTicks = 0, steps = 0;

    public EscapeProcess(int targetY) { this.targetY = targetY; }

    private static void dbg(String m, Object... a) { if (BotConfig.walkerDebug) LOG.info("[escape] " + m, a); }

    @Override public String kind() { return "escape"; }
    @Override public void attach(BotState st) {}

    @Override public boolean tick(Minecraft mc, WorldView w, BotState st) {
        LocalPlayer p = mc.player;
        if (p == null) return true;
        if (!BotConfig.allowBreak) { dbg("allowBreak off → BAIL"); releaseKeys(); return true; }
        BlockPos foot = p.blockPosition();

        // Success: reached target height, or the bot can walk away laterally onto
        // supported ground. NOTE: we deliberately do NOT use skyOpen-straight-up —
        // the bottom of a deep open-top shaft can see sky directly overhead yet the
        // bot is still trapped and can't walk out. canWalkOut only becomes true once
        // a foot-level cardinal is passable AND supported, i.e. the bot has reached
        // a rim it can step off onto, which is the real "escaped" condition.
        if (foot.getY() >= targetY || (p.onGround() && canWalkOut(w, foot))) {
            // Settle on the current cell centre with no residual momentum so the
            // bot can't coast into the un-carved wall beside it (suffocation).
            p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
            dbg("DONE foot={} (targetY={} canWalkOut={} skyOpen={})",
                    foot, targetY, canWalkOut(w, foot), skyOpen(mc, foot));
            releaseKeys(); return true;
        }
        if (steps >= MAX_STEPS) { dbg("BAIL max steps at foot={}", foot); releaseKeys(); return true; }

        return switch (phase) {
            case PICK       -> pick(mc, w, p, foot);
            case CARVE      -> carve(mc, w, p);
            case STEP_UP    -> stepUp(mc, w, p, foot);
            case VERT_BREAK -> vertBreak(mc, w, p, foot);
            case VERT_RISE  -> vertRise(mc, w, p, foot);
        };
    }

    /** Choose the cardinal whose up-step is carvable: the tread {@code foot+dir}
     *  is a SOLID non-falling stand block, and the two niche cells above it are
     *  clear or finitely breakable (not fluid/hazard). Prefer a DRY direction so
     *  the climb stays out of water. */
    private boolean pick(Minecraft mc, WorldView w, LocalPlayer p, BlockPos foot) {
        // Centre on the column so the step geometry is unambiguous.
        p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
        Direction best = null;
        boolean bestDry = false;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos tread = foot.relative(d);
            BlockPos nf = tread.above();        // new foot cell
            BlockPos nh = nf.above();           // new head cell
            if (!w.isSolid(tread) || isFalling(mc, tread) || w.isHazard(tread)) continue;
            if (!carvable(w, nf) || !carvable(w, nh)) continue;
            boolean dry = !w.isWater(tread) && !w.isWater(nf) && !w.isWater(nh);
            if (best == null || (dry && !bestDry)) { best = d; bestDry = dry; }
            if (dry) break;                     // a dry direction is ideal — take it
        }
        if (best == null) {
            // No sideways staircase fits (a strict 1-wide shaft whose every cardinal
            // niche above the wall tread is itself a blocked/hazard cell, or whose
            // walls aren't carvable). The only remaining DRY escape is straight UP:
            // mine the ceiling overhead and PILLAR up into it — the dry twin of
            // SwimUpBreak, which a flooded shaft uses. Needs a placeable block to
            // stand on the rise (a block-less dry shaft is physically inescapable —
            // you can't levitate), so bail cleanly if the hotbar is empty.
            BlockPos head = foot.above();
            BlockPos ceil = head.above();
            boolean ceilBreakable = !w.isSolid(ceil) || carvable(w, ceil);
            if (ceilBreakable && ensureHoldingPlaceableAny(mc)) {
                base = foot.immutable();
                phase = Phase.VERT_BREAK;
                actTicks = 0;
                dbg("PICK no sideways carve → VERT_BREAK (pillar-up-break) from foot={}", foot);
                return false;
            }
            dbg("PICK no carvable up-direction at foot={} (ceilBreakable={} placeable={}) → BAIL",
                    foot, ceilBreakable, ensureHoldingPlaceableAny(mc));
            releaseKeys(); return true;
        }
        dir = best;
        base = foot.immutable();
        phase = Phase.CARVE;
        actTicks = 0;
        dbg("PICK dir={} dry={} from foot={}", dir, bestDry, foot);
        return false;
    }

    /** A niche cell we may climb into: already passable (air) and not a hazard,
     *  or solid but finitely breakable (allowBreak-gated breakCost). */
    private static boolean carvable(WorldView w, BlockPos c) {
        if (!w.isSolid(c)) return w.isPassable(c) && !w.isHazard(c);
        return !Double.isInfinite(w.breakCost(c));
    }

    private boolean carve(Minecraft mc, WorldView w, LocalPlayer p) {
        BlockPos nf = base.relative(dir).above();   // new foot
        BlockPos nh = nf.above();                   // new head (resting)
        BlockPos nhUp = nh.above();                 // jump-arc clearance: the head clips
                                                    // this cell at the jump peak (~+3 over
                                                    // the old tread) — leaving it solid
                                                    // suffocates the bot mid-climb (lethal
                                                    // at 1 HP). Clear it too when breakable.
        // Clear top-down so a falling block above can't drop into a just-cleared cell.
        BlockPos target = (w.isSolid(nhUp) && carvable(w, nhUp)) ? nhUp
                        : w.isSolid(nh) ? nh
                        : w.isSolid(nf) ? nf : null;
        if (target == null) { dbg("CARVE clear dir={} → STEP_UP", dir); phase = Phase.STEP_UP; actTicks = 0; mc.options.keyAttack.setDown(false); return false; }
        // Hold position while mining so buoyancy/drift doesn't slide us off the column.
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
        selectBestToolFor(mc, target);
        aimAtBlockSnap(p, target);
        mc.options.keyAttack.setDown(true);
        if (++actTicks > BotConfig.breakTimeoutTicks * 3) {   // walls (bare-hand sandstone) are slow
            mc.options.keyAttack.setDown(false);
            dbg("CARVE timeout target={} solid={} → BAIL", target, w.isSolid(target));
            releaseKeys(); return true;
        }
        return false;
    }

    private boolean stepUp(Minecraft mc, WorldView w, LocalPlayer p, BlockPos foot) {
        mc.options.keyAttack.setDown(false);
        BlockPos nf = base.relative(dir).above();    // destination foot cell
        boolean atDest = foot.getX() == nf.getX() && foot.getZ() == nf.getZ()
                && foot.getY() >= nf.getY();
        if (atDest && p.onGround()) {
            // Kill horizontal drift so the next carve/step starts from a settled,
            // centred stance (and we don't slide into the adjacent un-carved wall).
            p.setPos(nf.getX() + 0.5, p.getY(), nf.getZ() + 0.5);
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
            releaseKeys();
            steps++;
            dbg("STEP_UP arrived foot={} (step {}) → PICK", foot, steps);
            phase = Phase.PICK;
            return false;
        }
        // Face the carved niche and drive in: forward to move into `dir`, jump to
        // mount the 1-block tread (and to float up out of the start water cell).
        p.setYRot(yawFor(dir));
        p.setXRot(0f);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(p.getY() < nf.getY() + 0.4);   // jump only while still rising
        if (++actTicks > 100) {                                   // ~5s; geometry may have shifted → re-pick
            releaseKeys();
            dbg("STEP_UP stall foot={} dest={} → re-PICK", foot, nf);
            phase = Phase.PICK;
        }
        return false;
    }

    /** Clear the column straight above so a pillar-up has room to rise: the cell
     *  the new feet enter ({@code base+2}) and the head-clearance cell above it
     *  ({@code base+3}). Mined top-down so a falling block can't drop back in.
     *  Once both are open we move to {@link Phase#VERT_RISE} to place the support. */
    private boolean vertBreak(Minecraft mc, WorldView w, LocalPlayer p, BlockPos foot) {
        // Hold the column centre so buoyancy/drift can't slide us off while mining.
        p.setPos(base.getX() + 0.5, p.getY(), base.getZ() + 0.5);
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
        BlockPos newHead = base.above(2);   // head cell after the +1 pillar (feet → base+1)
        BlockPos arcClear = newHead.above(); // jump-arc clearance — head clips base+3 at the
                                             // jump peak (~+2.25); leaving it solid suffocates.
        BlockPos target = (w.isSolid(arcClear) && !Double.isInfinite(w.breakCost(arcClear))) ? arcClear
                        : w.isSolid(newHead) ? newHead : null;
        if (target == null) {
            dbg("VERT_BREAK ceiling clear → VERT_RISE base={}", base);
            mc.options.keyAttack.setDown(false);
            phase = Phase.VERT_RISE;
            actTicks = 0;
            return false;
        }
        if (Double.isInfinite(w.breakCost(target))) {
            mc.options.keyAttack.setDown(false);
            dbg("VERT_BREAK unbreakable ceiling target={} → BAIL", target);
            releaseKeys(); return true;
        }
        selectBestToolFor(mc, target);
        aimAtBlockSnap(p, target);
        mc.options.keyAttack.setDown(true);
        if (++actTicks > BotConfig.breakTimeoutTicks * 3) {
            mc.options.keyAttack.setDown(false);
            dbg("VERT_BREAK timeout target={} → BAIL", target);
            releaseKeys(); return true;
        }
        return false;
    }

    /** Pillar straight up one block: jump and place a support in the old feet cell
     *  ({@code base}) at the apex, lifting the bot to {@code base+1}. Mirrors the
     *  Walker's pillarUp actuator — vanilla rejects the place until the feet clear
     *  the cell, so we gate on real height. On arrival, advance and re-PICK (a
     *  sideways rim may now be reachable; else we VERT_BREAK the next ceiling). */
    private boolean vertRise(Minecraft mc, WorldView w, LocalPlayer p, BlockPos foot) {
        mc.options.keyAttack.setDown(false);
        BlockPos dest = base.above();
        if (foot.getY() >= dest.getY() && p.onGround()) {
            p.setPos(dest.getX() + 0.5, p.getY(), dest.getZ() + 0.5);
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
            releaseKeys();
            steps++;
            dbg("VERT_RISE arrived foot={} (step {}) → PICK", foot, steps);
            phase = Phase.PICK;
            return false;
        }
        if (!ensureHoldingPlaceableAny(mc)) {
            dbg("VERT_RISE no placeable block → BAIL");
            releaseKeys(); return true;
        }
        p.setXRot(89.5f);   // look straight down to aim the support
        if (p.onGround()) {
            mc.options.keyJump.setDown(true);
        } else {
            mc.options.keyJump.setDown(false);
            // Place into the old feet cell once the body has risen clear of it —
            // vanilla's collision check silently rejects a block the AABB overlaps.
            if (p.getY() >= base.getY() + 1.0) {
                clientUseItemOn(mc, p, base.below(), Direction.UP);
            }
        }
        if (++actTicks > 100) {
            releaseKeys();
            dbg("VERT_RISE stall foot={} dest={} → re-PICK", foot, dest);
            phase = Phase.PICK;
        }
        return false;
    }

    private static boolean isFalling(Minecraft mc, BlockPos pos) {
        return mc.level != null && mc.level.getBlockState(pos).getBlock() instanceof FallingBlock;
    }

    private static final Direction[] HORIZ =
            {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    /** Escaped: from the current foot cell the bot can step sideways onto a
     *  supported, head-clear, non-hazard cell — i.e. it reached a rim it can walk
     *  off, rather than being boxed in by walls at foot level. At a shaft bottom
     *  all four cardinals are walls so this is false; at the surface it's true. */
    private static boolean canWalkOut(WorldView w, BlockPos foot) {
        for (Direction d : HORIZ) {
            BlockPos side = foot.relative(d);
            if (w.isPassable(side) && !w.isHazard(side)
                    && w.isPassable(side.above()) && w.isSolid(side.below())) return true;
        }
        return false;
    }

    /** Out of the pit once nothing solid blocks the sky above the head cell.
     *  Kept for debug logging only — NOT a success gate (see tick()). */
    private static boolean skyOpen(Minecraft mc, BlockPos foot) {
        return mc.level != null && mc.level.canSeeSky(foot.above());
    }

    /** Yaw facing the given cardinal (MC: 0=+Z south, 90=-X west, 180=-Z north, -90=+X east). */
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

package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.util.BotUtil.yawFor;

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
 * <p>Every launch column must be jump-clear: CARVE opens the cell above the bot's
 * OWN head ({@code base+2}) before the target niche, because the jump arc clips it
 * (a sealed 1×2 shelter otherwise traps the bot in a STEP_UP↔re-PICK ping-pong
 * that breaks zero blocks). A futility watchdog bails after {@code FUTILE_LIMIT}
 * consecutive re-picks with no step progress, and every exit — success or bail —
 * is reported through {@link BotState#escape} so the agent can read the outcome
 * (the old slot-less version ended silently, indistinguishable from success).
 *
 * <p>Needs {@link BotConfig#allowBreak} (every step mines), and a wall with a
 * solid non-falling tread to stand on; bails if no carvable direction exists or
 * a step stalls past the timeout. Drives through the {@link Avatar} seam (break /
 * place / tool / forward / jump on the player's own input), so it runs over a
 * client LocalPlayer or a server FakePlayer alike.
 */
public final class EscapeProcess implements BotProcess {

    private enum Phase { PICK, CARVE, STEP_UP, VERT_BREAK, VERT_RISE }

    /** Climb until foot Y reaches this (or open sky is overhead). */
    private final int targetY;
    private static final int MAX_STEPS = 48;
    /** Consecutive PICKs with no step progress before we call the climb futile.
     *  Each futile cycle already burned a full STEP_UP/VERT_RISE stall (~5s), so
     *  4 ≈ 20s of confirmed zero-progress ping-pong — long enough to survive a
     *  transient geometry shift, short enough not to look like a hang. */
    private static final int FUTILE_LIMIT = 4;

    private Phase phase = Phase.PICK;
    private Direction dir = null;
    private BlockPos base = null;        // the foot cell we're climbing FROM this step
    private BlockPos actTarget = null;   // block currently being broken; actTicks is PER-target
    private int actTicks = 0, steps = 0;
    private int futileCycles = 0, stepsAtLastPick = -1;

    public EscapeProcess(int targetY) { this.targetY = targetY; }

    private static void dbg(String m, Object... a) { if (BotConfig.walkerDebug) LOG.info("[escape] " + m, a); }

    @Override public String kind() { return "escape"; }

    @Override public void attach(BotState st) {
        BotState.ProcessSlot s = st.escape;
        s.active = true;
        s.goal = "escape to y=" + targetY;
        s.pathLen = MAX_STEPS;
        s.startedAtMs = System.currentTimeMillis();
        s.lastError = null;
    }

    /** Single exit point: release inputs, stamp the outcome on the escape slot
     *  ({@code error == null} means success) and finish the process. */
    private boolean done(Avatar a, BotState st, String error) {
        a.breakHold(false);
        a.releaseInputs();
        BotState.ProcessSlot s = st.escape;
        s.lastError = error;
        s.reset();
        if (error != null) dbg("BAIL: {}", error);
        return true;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) return done(a, st, "no player");
        if (!BotConfig.allowBreak) return done(a, st, "allowBreak is off — escape mines every step");
        BlockPos foot = p.blockPosition();
        st.escape.pathStep = steps;
        st.escape.target = foot;

        // Success: reached target height, or the bot stands at a walkable rim
        // UNDER OPEN SKY. canWalkOut alone used to end the climb ("reached a rim
        // it can step off onto") — but in a natural cave passage canWalkOut is
        // true on the FIRST tick, so `mc.bot.ascend` no-opped exactly where the
        // agent needed it most (death #15, live 2026-07-20: two ascends from y=8
        // in an open cave ended instantly DONE without climbing; the 0.13-HP bot
        // never got its staircase to the surface). The verb's contract is "carve
        // UP to targetY": a lateral walk-out only counts as done when it is a
        // SURFACE rim (skyOpen). The original deep-shaft caveat (sky visible
        // straight up from a trapped shaft bottom) stays covered because that
        // bottom still fails canWalkOut; an UNDERGROUND rim now keeps the climb
        // going instead of stranding the bot in the cave it started in.
        if (foot.getY() >= targetY
                || (p.onGround() && canWalkOut(w, foot) && skyOpen(p.level(), foot))) {
            // Settle on the current cell centre with no residual momentum so the
            // bot can't coast into the un-carved wall beside it (suffocation).
            p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
            dbg("DONE foot={} (targetY={} canWalkOut={} skyOpen={})",
                    foot, targetY, canWalkOut(w, foot), skyOpen(p.level(), foot));
            return done(a, st, null);
        }
        if (steps >= MAX_STEPS) return done(a, st, "max steps (" + MAX_STEPS + ") reached at " + foot.toShortString());

        return switch (phase) {
            case PICK       -> pick(a, w, p, foot, st);
            case CARVE      -> carve(a, w, p, st);
            case STEP_UP    -> stepUp(a, w, p, foot);
            case VERT_BREAK -> vertBreak(a, w, p, foot, st);
            case VERT_RISE  -> vertRise(a, w, p, foot, st);
        };
    }

    /** Choose the cardinal whose up-step is carvable: the tread {@code foot+dir}
     *  is a SOLID non-falling stand block, and the two niche cells above it are
     *  clear or finitely breakable (not fluid/hazard). Prefer a DRY direction so
     *  the climb stays out of water. */
    private boolean pick(Avatar a, WorldView w, Player p, BlockPos foot, BotState st) {
        // Futility watchdog: STEP_UP / VERT_RISE stalls funnel back here without
        // advancing `steps`. Without the ceiling ever changing (e.g. an unbreakable
        // launch arc the phases below missed) that loop used to ping-pong forever,
        // breaking zero blocks with zero report.
        if (steps == stepsAtLastPick) {
            if (++futileCycles >= FUTILE_LIMIT) {
                return done(a, st, "futile: " + FUTILE_LIMIT
                        + " re-picks with no step progress at " + foot.toShortString());
            }
        } else {
            futileCycles = 0;
            stepsAtLastPick = steps;
        }
        // Centre on the column so the step geometry is unambiguous.
        p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
        // The jump arc out of THIS cell clips the cell above the bot's own head
        // (base+2) no matter which cardinal we climb. If it is sealed and
        // unbreakable, neither the sideways staircase nor the vertical pillar can
        // launch — report that instead of grinding the phases below.
        if (!carvable(w, foot.above(2))) {
            return done(a, st, "launch arc blocked: cell above head "
                    + foot.above(2).toShortString() + " is not clear/breakable");
        }
        Direction best = null;
        boolean bestDry = false;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos tread = foot.relative(d);
            BlockPos nf = tread.above();        // new foot cell
            BlockPos nh = nf.above();           // new head cell
            if (!w.isSolid(tread) || isFalling(p.level(), tread) || w.isHazard(tread)) continue;
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
            if (ceilBreakable && a.holdPlaceable()) {
                base = foot.immutable();
                phase = Phase.VERT_BREAK;
                actTicks = 0;
                actTarget = null;
                dbg("PICK no sideways carve → VERT_BREAK (pillar-up-break) from foot={}", foot);
                return false;
            }
            return done(a, st, "no carvable up-direction at " + foot.toShortString()
                    + " (ceilBreakable=" + ceilBreakable + " placeable=" + a.holdPlaceable() + ")");
        }
        dir = best;
        base = foot.immutable();
        phase = Phase.CARVE;
        actTicks = 0;
        actTarget = null;
        dbg("PICK dir={} dry={} from foot={}", dir, bestDry, foot);
        return false;
    }

    /** A niche cell we may climb into: already passable (air) and not a hazard,
     *  or solid but finitely breakable (allowBreak-gated breakCost). */
    private static boolean carvable(WorldView w, BlockPos c) {
        if (!w.isSolid(c)) return w.isPassable(c) && !w.isHazard(c);
        return !Double.isInfinite(w.breakCost(c));
    }

    private boolean carve(Avatar a, WorldView w, Player p, BotState st) {
        BlockPos startArc = base.above(2);          // launch-arc clearance over the bot's
                                                    // OWN head: the jump peak lifts the head
                                                    // into this cell before the body enters
                                                    // the niche. A sealed shelter roof left
                                                    // here made every STEP_UP a no-op ping-pong.
        BlockPos nf = base.relative(dir).above();   // new foot
        BlockPos nh = nf.above();                   // new head (resting)
        BlockPos nhUp = nh.above();                 // jump-arc clearance: the head clips
                                                    // this cell at the jump peak (~+3 over
                                                    // the old tread) — leaving it solid
                                                    // suffocates the bot mid-climb (lethal
                                                    // at 1 HP). Clear it too when breakable.
        // Own column first (nothing launches without it), then the target column
        // top-down so a falling block above can't drop into a just-cleared cell.
        BlockPos target = (w.isSolid(startArc) && carvable(w, startArc)) ? startArc
                        : (w.isSolid(nhUp) && carvable(w, nhUp)) ? nhUp
                        : w.isSolid(nh) ? nh
                        : w.isSolid(nf) ? nf : null;
        if (target == null) { dbg("CARVE clear dir={} → STEP_UP", dir); phase = Phase.STEP_UP; actTicks = 0; actTarget = null; a.breakHold(false); return false; }
        // A CARVE clears up to 4 cells (startArc→nhUp→nh→nf) top-down without leaving
        // the phase; give each its OWN break budget so a legitimately slow multi-block
        // bare-hand carve doesn't trip a CUMULATIVE cap on the last block (day6 bug: a
        // 4-block bare-hand climb hit breakTimeoutTicks*3 with three blocks already gone).
        if (!target.equals(actTarget)) { actTarget = target; actTicks = 0; }
        // Hold position while mining so buoyancy/drift doesn't slide us off the column.
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
        a.selectTool(target);
        a.aimAtBlock(target);
        a.breakHold(true);
        a.continueDestroy(target);
        if (++actTicks > BotConfig.breakTimeoutTicks * 3) {   // walls (bare-hand sandstone) are slow
            return done(a, st, "carve timeout at " + target.toShortString()
                    + " (solid=" + w.isSolid(target) + ")");
        }
        return false;
    }

    private boolean stepUp(Avatar a, WorldView w, Player p, BlockPos foot) {
        a.breakHold(false);
        BlockPos nf = base.relative(dir).above();    // destination foot cell
        boolean atDest = foot.getX() == nf.getX() && foot.getZ() == nf.getZ()
                && foot.getY() >= nf.getY();
        if (atDest && p.onGround()) {
            // Kill horizontal drift so the next carve/step starts from a settled,
            // centred stance (and we don't slide into the adjacent un-carved wall).
            p.setPos(nf.getX() + 0.5, p.getY(), nf.getZ() + 0.5);
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
            a.releaseInputs();
            steps++;
            dbg("STEP_UP arrived foot={} (step {}) → PICK", foot, steps);
            phase = Phase.PICK;
            return false;
        }
        // Face the carved niche and drive in: forward to move into `dir`, jump to
        // mount the 1-block tread (and to float up out of the start water cell).
        p.setYRot(yawFor(dir));
        p.setXRot(0f);
        a.commandForward(1f);
        a.commandJump(p.getY() < nf.getY() + 0.4);   // jump only while still rising
        if (++actTicks > 100) {                                   // ~5s; geometry may have shifted → re-pick
            a.releaseInputs();
            dbg("STEP_UP stall foot={} dest={} → re-PICK", foot, nf);
            phase = Phase.PICK;
        }
        return false;
    }

    /** Clear the column straight above so a pillar-up has room to rise: the cell
     *  the new feet enter ({@code base+2}) and the head-clearance cell above it
     *  ({@code base+3}). Mined top-down so a falling block can't drop back in.
     *  Once both are open we move to {@link Phase#VERT_RISE} to place the support. */
    private boolean vertBreak(Avatar a, WorldView w, Player p, BlockPos foot, BotState st) {
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
            a.breakHold(false);
            phase = Phase.VERT_RISE;
            actTicks = 0;
            actTarget = null;
            return false;
        }
        if (Double.isInfinite(w.breakCost(target))) {
            return done(a, st, "unbreakable ceiling at " + target.toShortString());
        }
        if (!target.equals(actTarget)) { actTarget = target; actTicks = 0; }   // per-target budget (see carve)
        a.selectTool(target);
        a.aimAtBlock(target);
        a.breakHold(true);
        a.continueDestroy(target);
        if (++actTicks > BotConfig.breakTimeoutTicks * 3) {
            return done(a, st, "ceiling-break timeout at " + target.toShortString());
        }
        return false;
    }

    /** Pillar straight up one block: jump and place a support in the old feet cell
     *  ({@code base}) at the apex, lifting the bot to {@code base+1}. Mirrors the
     *  Walker's pillarUp actuator — vanilla rejects the place until the feet clear
     *  the cell, so we gate on real height. On arrival, advance and re-PICK (a
     *  sideways rim may now be reachable; else we VERT_BREAK the next ceiling). */
    private boolean vertRise(Avatar a, WorldView w, Player p, BlockPos foot, BotState st) {
        a.breakHold(false);
        BlockPos dest = base.above();
        // Same column, not just the right height. The centring snap below is a discrete write that
        // does not sweep, so it is only safe while the body is already IN the cell it centres on —
        // which is what its two sibling arrival gates require and this one did not: STEP_DOWN
        // (DescendProcess:216-218) and STEP_UP (EscapeProcess:256-258) both test X and Z equality,
        // and only this one tested height alone.
        //
        // The gap is reachable because VERT_RISE is a JUMP: this phase commands a jump and, unlike
        // the other phases, neither zeroes the horizontal velocity nor re-pins the body per tick. A
        // dozen airborne ticks with any lateral component — a leftover forward key, flowing water,
        // a mob's Entity.push — can land the body in a neighbouring column, and a neighbour with a
        // solid face at dest.getY() satisfies the old gate. The snap then moves the body a whole
        // cell or more sideways; across two columns the cell in between may be solid rock, and
        // nothing checked it. VERT_RISE is entered precisely when no side is steppable — terrain
        // that HAS such neighbours.
        //
        // Not observed: EscapeProcess appears in none of the logs on disk (see
        // docs/parity-setpos-centre-snap.md). This closes a hole in the shape its own siblings
        // already closed, rather than answering a failure. A body that lands off-column now falls
        // to the phase's own 100-tick stall and re-PICKs, which is the escape this process already
        // has for every other way a rise can fail.
        if (foot.getX() == dest.getX() && foot.getZ() == dest.getZ()
                && foot.getY() >= dest.getY() && p.onGround()) {
            p.setPos(dest.getX() + 0.5, p.getY(), dest.getZ() + 0.5);
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
            a.releaseInputs();
            steps++;
            dbg("VERT_RISE arrived foot={} (step {}) → PICK", foot, steps);
            phase = Phase.PICK;
            return false;
        }
        if (!a.holdPlaceable()) {
            return done(a, st, "no placeable block for pillar-up at " + foot.toShortString());
        }
        p.setXRot(89.5f);   // look straight down to aim the support
        if (p.onGround()) {
            a.commandJump(true);
        } else {
            a.commandJump(false);
            // Place into the old feet cell once the body has risen clear of it —
            // vanilla's collision check silently rejects a block the AABB overlaps.
            if (p.getY() >= base.getY() + 1.0) {
                a.placeOn(base.below(), Direction.UP);
            }
        }
        if (++actTicks > 100) {
            a.releaseInputs();
            dbg("VERT_RISE stall foot={} dest={} → re-PICK", foot, dest);
            phase = Phase.PICK;
        }
        return false;
    }

    private static boolean isFalling(Level lvl, BlockPos pos) {
        return lvl != null && lvl.getBlockState(pos).getBlock() instanceof FallingBlock;
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
    private static boolean skyOpen(Level lvl, BlockPos foot) {
        return lvl != null && lvl.canSeeSky(foot.above());
    }

    /** Yaw facing the given cardinal (MC: 0=+Z south, 90=-X west, 180=-Z north, -90=+X east). */
}

package net.magicterra.worlddriver.bot.control;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.BodyGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.control.PathSmoothing.hasPendingEdge;
import static net.magicterra.worlddriver.bot.control.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.control.WalkerGeometry.*;

/**
 * Raising the bot one block at a time by jumping and filling the cell its feet just left: a planned
 * {@code pillarUp} edge, the settle onto the fresh block afterwards, and the recovery pillar that climbs
 * back up to a route the bot fell below. All three place the same way — {@link #placeRung} — so there is
 * one answer to "may the block go under the feet now".
 *
 * <p>The water-bank climb-out places under the bot too, but by its own rule ({@link WaterClimb#crestClearOf}):
 * it carries blocks shorter than a full cube into the cell the bot floats in, which the full-cube rule here
 * would never allow.
 *
 * <p>The recovery is also armed by StallDetect when the bot fell below its route, and RouteDig stands aside
 * while it is.
 */
final class Pillar implements Controller {

    private static final Set<Channel> CHANNELS = EnumSet.allOf(Channel.class);

    private enum Mode { NONE, PLANNED, SETTLE, RECOVER }

    /** What this tick asks for, from {@link #sense}. */
    private Mode mode = Mode.NONE;
    private Move.Edge edge;

    /** The planned pillar: the path index of its edge, and ticks since its jump (-1 = grounded). */
    private int edgeStep = -1, sinceJump = -1;

    // ---- the recovery pillar ----
    int recoverLatch;             // ticks left driving an in-place pillar-up recovery (bot fell below the climb path beyond jump reach) — latched across the jump's airborne phase so a place can land
    BlockPos recoverCell;         // the (grounded) feet cell the recovery is filling this rung
    int recoverPeakY;             // highest foot Y this pillar-recovery has reached (no-rise give-up tracking)
    int recoverStallTicks;        // consecutive recovery ticks with no height gain → PILLAR_NORISE_GIVEUP re-routes

    @Override public String name() { return "pillar"; }
    @Override public Set<Channel> channels() { return CHANNELS; }
    @Override public int priority() { return 4; }
    /** Bounded by the action timeout (planned) and the latch (recovery). */
    @Override public int maxTicks() { return Integer.MAX_VALUE; }

    /**
     * Place a block into {@code cell} — the cell the feet are rising out of — on the top face of the block
     * below it, once the feet are clear of it. Vanilla's entity-collision check (Level#isUnobstructed)
     * silently rejects the block while the bot's box still overlaps the cell, i.e. until the feet reach the
     * cell's top face ({@link WalkerTickClimb#feetClearOf}). A vanilla jump (peak ~+1.25) only crosses +1.0
     * around tick 4, so a fixed 3-tick delay fired at ~+0.99 and the place no-op'd against the bot's own
     * box. Gate on real height. True when the click was made.
     */
    static boolean placeRung(Walker wk, WorldView world, LivingEntity p, BlockPos cell) {
        if (!WalkerTickClimb.feetClearOf(p, cell) || world.isSolid(cell)) return false;
        wk.blocks.placeOn(cell.below(), Direction.UP);
        wk.exAlarms.notePlace(cell);
        return true;
    }

    @Override public void sense(Walker wk, WalkerTickCtx cx, WorldView world) {
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        edge = wk.edgeAt(wk.route.step());
        armRecovery(wk, p, foot, world);
        // Standing on the filled rung ends the recovery; a further rung re-armed it on the new foot
        // above. The latch used to run out its ticks here, jumping in place and re-placing into the
        // full cell (live R1 cliff 1390,82,-520: ~12 ticks of refused places, every run).
        if (wk.pillar.recoverLatch > 0 && wk.pillar.recoverCell != null && p.onGround()
                && p.getY() >= wk.pillar.recoverCell.getY() + 1.0 - 1e-3) wk.pillar.recoverLatch = 0;
        boolean pillarEdge = edge != null && "pillarUp".equals(edge.move);
        if (pillarEdge && hasPendingEdge(world, edge)) mode = Mode.PLANNED;
        // Pillar placed but the bot is still rising onto it: hold (no horizontal walk) until grounded at
        // the new level, so it doesn't walk off the fresh block mid-jump.
        else if (pillarEdge && !(p.onGround() && p.getY() >= wk.route.path().get(wk.route.step()).getY() - 0.1)) mode = Mode.SETTLE;
        else if (wk.pillar.recoverLatch > 0 && wk.pillar.recoverCell != null) mode = Mode.RECOVER;
        else mode = Mode.NONE;
    }

    /**
     * Vertical recovery: the bot is BELOW the next node by more than it can jump — it slid or fell below
     * the committed climb path, and a plain stepUp there just RAMS the wall (jump is suppressed for an
     * unreachable height): a tall block in front holds the bot in place. Carrying blocks, it pillars up in
     * place instead. Re-armed each grounded tick while still too low; ends when back within jump reach or
     * the blocks run out. A step taller than the bot could clear even with a jump also speeds the stuck
     * timer, so a bot without blocks gets its node blacklisted and a reroute instead of jumping forever.
     *
     * <p>Slow diagonal step-up: a planned +1 diagUp that bob-rams the riser past DIAGUP_PILLAR_TICKS (the √2
     * jump-traverse ~47 ticks/step sawtooth) escalates to the same pillar — place a support under the foot,
     * jump +1, walk the now-level node. Scoped hard: a true +1 diagUp by planner intent AND geometry,
     * grounded, dry, blocks in hand; dy==1 keeps it disjoint from the ≥2 ascent-ram slide.
     *
     * <p>holdPillarBlock (not world.canPlace): the recovery places on the solid rung below the grounded foot,
     * so supported sand and gravel work here.
     */
    private static void armRecovery(Walker wk, LivingEntity p, BlockPos foot, WorldView world) {
        Move.Edge edge = wk.edgeAt(wk.route.step());
        BlockPos wp = wk.route.path().get(wk.route.step());
        boolean parkourEdge = edge != null && edge.move != null && edge.move.startsWith("parkour");
        int upDy = wp.getY() - foot.getY();
        boolean needJumpForStep = upDy >= 1 && upDy > world.maxStepUpBlocks();
        boolean overJump = needJumpForStep && upDy > world.maxJumpUpBlocks() && p.onGround() && !p.isInWater()
                && edge != null && !"pillarUp".equals(edge.move) && !parkourEdge && !isDescentEdge(edge);
        if (overJump) wk.stuckTicks += 3;
        boolean diagUp = upDy >= 1 && wp.getX() != foot.getX() && wp.getZ() != foot.getZ()
                && !parkourEdge && !p.isInWater();
        boolean slowDiagUpPillar = "diagUp".equals(edge != null ? edge.move : null)
                && diagUp && upDy == 1 && p.onGround() && !p.isInWater()
                && wk.stepProg.noStepProgressTicks > DIAGUP_PILLAR_TICKS;
        if ((overJump || slowDiagUpPillar) && WorldConfig.allowPlace && wk.equipment.holdPillarBlock()) {
            wk.pillar.recoverLatch = PILLAR_RECOVER_TICKS;
            wk.pillar.recoverCell = foot;                 // grounded feet cell = the rung we fill
        }
    }

    @Override public String whyNot(Walker wk, WalkerTickCtx cx, WorldView world) {
        return mode == Mode.NONE ? "no pillar to climb" : null;
    }

    @Override public boolean canContinue(Walker wk, WalkerTickCtx cx, WorldView world) {
        return mode != Mode.NONE;
    }

    @Override public boolean tick(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        switch (mode) {
            case PLANNED -> planned(wk, a, world, p, foot);
            case SETTLE -> {
                wk.blocks.breakHold(false);
                wk.actionTicks = 0;
                Walker.avatarForward(a, false);
                wk.avatarJump(a, false);
                p.setSprinting(false);
            }
            case RECOVER -> recover(wk, a, world, p);
            case NONE -> { return false; }
        }
        return true;
    }

    @Override public void stop(String why) {}

    /** The recovery's cell, peak and stall count are re-based whenever it is armed, so only the latch goes. */
    @Override public void reset(Walker.Scope scope) {
        edgeStep = -1;
        sinceJump = -1;
        recoverLatch = 0;
    }

    /**
     * A planned pillarUp: clear the ceiling if one blocks the rise, then jump and place the support beneath
     * at the apex. Distinct from the route's general place because it owns the airborne timing (a block
     * cannot go into the cell the bot is standing in).
     */
    private void planned(Walker wk, Body a, WorldView world, LivingEntity p, BlockPos foot) {
        // Off-column guard (pillarUp-off-column wedge, see PILLAR_ALIGN_SQ): the pillar is
        // placed below the bot and it jumps in place, so it only lands the bot a level up
        // when standing over the column. If climb-drift left the bot to the side, WALK to
        // the column XZ first (no place/jump) — once within PILLAR_ALIGN_SQ the normal
        // pillar below engages. Self-recovering: a blocked approach rams → noStepProgress
        // climbs → the fellOffPath/repath path re-routes, so this can't deadlock.
        BlockPos pcol = wk.route.path().get(wk.route.step());
        double pcdx = (pcol.getX() + 0.5) - p.getX();
        double pcdz = (pcol.getZ() + 0.5) - p.getZ();
        // DRY pillars only: a buoyant/water pillar bobs and drifts off-column BY DESIGN and
        // has its own float-up + place-on-crest handling below; aligning would fight the bob
        // (regressed buoyantWallArena to 192-tick WATERLINE thrash). The thrash is AT the
        // waterline, where the bob peak lifts the bot out of the water cell so node-based
        // isWater reads "dry" — so probe the bot's own FOOT column (foot, -1, -2) for water,
        // not just the node. p.isInWater() flickers false at the bob peak, hence world-based.
        boolean nearWater = p.isInWater()
                || world.isWater(foot) || world.isWater(foot.offset(0, -1, 0)) || world.isWater(foot.offset(0, -2, 0))
                || world.isWater(pcol) || world.isWater(pcol.offset(0, -1, 0));
        if (p.onGround() && !nearWater && pcdx * pcdx + pcdz * pcdz > PILLAR_ALIGN_SQ) {
            float ayaw = (float) Math.toDegrees(Math.atan2(-pcdx, pcdz));
            float dyaw = angleDiff(p.getYRot(), ayaw);
            if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
            float ny = p.getYRot() + dyaw;
            p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny;
            p.setXRot(0f);
            wk.blocks.breakHold(false);
            p.setSprinting(false);
            Walker.avatarForward(a, true);
            if (p.horizontalCollision) wk.jumpTag = "riserHop";
            wk.avatarJump(a, p.horizontalCollision);   // hop only to clear a riser; flat-smooth otherwise
            return;
        }
        Walker.avatarForward(a, false);
        p.setSprinting(false);
        wk.totalTicks = 0;
        wk.stuckTicks = 0;   // pillaring stays on one cell while placing — not "stuck"
        if (wk.route.step() != wk.pillar.edgeStep) { wk.pillar.edgeStep = wk.route.step(); wk.pillar.sinceJump = -1; }
        if (++wk.actionTicks > ControlConfig.breakTimeoutTicks) {
            wk.blocks.breakHold(false);
            wk.avatarJump(a, false);
            wk.error = "pillar stalled at " + wk.route.path().get(wk.route.step());
            wk.route.drop(wk.error);
            return;
        }
        for (BlockPos b : edge.toBreak) {
            if (world.isSolid(b)) {
                wk.avatarJump(a, false);
                wk.equipment.selectTool(b);
                if (WalkerTickClimb.bailOnBreathInfeasibleDig(wk, a, p, b)) return;
                world.signals().digging = true;
                // Claim, aim and drive all live inside avatarDig: digging `b` first and claiming it
                // afterwards drove a cell another phase already held.
                WalkerDig.avatarDig(wk, a, b, false);
                return;
            }
        }
        wk.blocks.breakHold(false);
        // Buoyant pillar — the bot is rising out of water. Two sub-cases:
        //   (a) FLOODED shaft (the destination cell is itself water): just hold
        //       jump and FLOAT up through it; water follows up so the next
        //       ceiling break repeats until the bot surfaces. No place — a block
        //       would only dam the float.
        //   (b) DRY air above (a partly-mined bunker / 1-deep pocket with an open
        //       chimney): the swim-bob alone never gains permanent height, so on
        //       the crest — when the feet clear the place cell — PLACE a support
        //       there to stand on, lifting the bot one block; after that first
        //       lift it is grounded and the normal dry pillar climbs the rest.
        // Detect water from the world (cell below is water), not p.isInWater(),
        // which flickers false at the bob peak and would drop the jump.
        // Water-SURFACE shaft (walkerPillarSurfacePlace): the destination cell is
        // water but the cell ABOVE it is air — this is the water-bank climb-out,
        // not a flooded chimney. Floating higher is physically impossible (rig
        // 371.5,62,348.5: jump+buoyancy bob ceiling 63.08 vs the 63.9 the flooded-
        // shaft float path would need), so case (a) float-through starves forever
        // while the only ticks that could place the FULL CUBE this branch carries
        // (bob crest ≥ fill.y+1.0, 63.0-63.08 ≈ 1-2 ticks/bob) are spent in this
        // branch NOT placing = the deterministic water-bank pillarUp deadlock.
        // Treat the surface cell as case (b): jump and crest-place the support.
        //
        // The climb-out takeover is gated lower (WaterClimb.crestClearOf, 0.9)
        // and clicks in the 62.9-63.0 band. That is NOT a dead band, which this
        // comment used to claim: isUnobstructed tests the collision shape of the
        // state being PLACED, and that takeover carries mud — 14/16 = 0.875 tall —
        // so its clicks land there. The two paths are gated for different BLOCKS,
        // not for different luck; this one holds throwaway full cubes and 1.0 is
        // its real boundary. See crestClearOf for the whole argument.
        //
        // That carve-out lives in floodedShaft() because WalkerTickProgress has
        // to reach the same answer; when it was written here only, Progress kept
        // calling this cell a float and advanced the pointer off an unplaced support.
        boolean shaftFlooded = WalkerTickClimb.floodedShaft(world, wk.route.path().get(wk.route.step()));
        if (shaftFlooded || p.isInWater() || world.isWater(wk.route.path().get(wk.route.step()).offset(0, -1, 0))) {
            wk.avatarJump(a, true);
            // gap#81: routine pillar/scaffold filler must not spend gathered wood.
            if (!shaftFlooded && wk.equipment.holdThrowawayPlaceable()) {
                p.setXRot(89.5f);                       // look down to aim the support
                placeRung(wk, world, p, edge.toPlace.get(0));   // once bobbed clear of the place cell
            }
            return;
        }
        if (!wk.equipment.holdPlaceable()) {
            wk.error = "pillar: no placeable block in hotbar";
            wk.route.drop(wk.error);
            return;
        }
        BlockPos place = edge.toPlace.get(0);                    // cell we fill (old feet cell)
        p.setXRot(89.5f);                                        // look straight down (snap)
        if (p.onGround()) {
            wk.avatarJump(a, true);
            wk.pillar.sinceJump = 0;
        } else {
            wk.avatarJump(a, false);
            if (wk.pillar.sinceJump >= 0) wk.pillar.sinceJump++;
            if (wk.pillar.sinceJump >= PILLAR_PLACE_DELAY) placeRung(wk, world, p, place);
        }
    }

    /** One tick of the recovery pillar: clear the ceiling, jump off the rung, fill it on the way up. */
    private static void recover(Walker wk, Body a, WorldView world, LivingEntity p) {
        wk.pillar.recoverLatch--;
        wk.actionTicks = 0;   // no planned edge action is running, so its timeout starts over
        wk.blocks.breakHold(false);
        Walker.avatarForward(a, false);
        p.setSprinting(false);
        Walker.avatarSneak(a, false);
        p.setShiftKeyDown(false);
        // Clear the ceiling FIRST: a block where the head rises (tree-canopy leaves at
        // rung+2, a dirt overhang) makes the recovery jump RAM it — the bot can't gain
        // height, can't place, and wedges (a pillar-up under a tree stuck against the leaves). The planned
        // pillarUp clears its toBreak the same way; this recovery path had none.
        // Only with allowBreak, and only the single head cell, so it digs no more than the
        // one block needed to rise this rung (re-checked each rung as recoverCell rises).
        // DELIBERATELY on the global allowBreak switch, NOT mayBreak(): this is the
        // anti-suffocation safety dig, and it is EXEMPT from per-goto forbidDig — suffocation is
        // death, forbidDig is only a navigation preference, so safety wins over the constraint.
        BlockPos recCeiling = WorldConfig.allowBreak ? recoverCeiling(wk, p) : null;
        if (recCeiling != null) {
            wk.avatarJump(a, false);
            // Preempting: suffocation is death, a navigation claim is a preference. The other
            // six walker digs queue behind whoever holds the slot; this one takes it.
            WalkerDig.avatarDigPreempt(wk, a, recCeiling, true);
            return;
        }
        wk.blocks.breakHold(false);
        p.setXRot(89.5f);                         // look straight down to aim the support
        if (p.onGround()) {
            wk.jumpTag = "pillarRecoverRung";
            wk.avatarJump(a, true);     // jump off the current rung
        } else {
            wk.avatarJump(a, false);
            placeRung(wk, world, p, wk.pillar.recoverCell);
        }
    }

    /**
     * The block capping a recovery rung, or null. The ceiling is every cell the 0.6-wide box meets rising
     * one rung from the rung it stands on, not cell+2 alone: a bot straddling a column boundary bonks on the
     * NEIGHBOUR's block (live 2026-09-28, x=1389.9: stone at 1390,71 capped every jump at +0.2 for ~15 s
     * while 1389,71 was air). Anchored at the rung so an airborne tick asks the same question.
     */
    private static BlockPos recoverCeiling(Walker wk, LivingEntity p) {
        List<BlockPos> ceiling = BodyGeometry.riseBlockers(p,
                wk.pillar.recoverCell.getY() + BodyGeometry.PILLAR_RISE - p.getY());
        return ceiling.isEmpty() ? null : ceiling.get(0);
    }
}

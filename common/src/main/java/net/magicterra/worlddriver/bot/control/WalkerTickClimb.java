package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.bot.world.BreakFeasibility;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Direction;

import static net.magicterra.worlddriver.bot.control.PathSmoothing.*;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.bot.control.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.control.WalkerGeometry.*;
import net.magicterra.worlddriver.game.body.LookController;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 2348–3279): per-tick trace, water climb-out foothold (pillar takeover / bank dig), pillar-up actuator.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickClimb {
    /**
     * Is this pillarUp destination a FLOODED shaft — one the bot floats straight up through —
     * rather than a water SURFACE it must place a support to leave?
     *
     * <p><b>The single answer both phase classes ask.</b> It used to be computed twice. This class
     * carved the surface case out of "flooded" (the {@code walkerPillarSurfacePlace} branch below,
     * which is the flag's only reader in the whole product); {@link WalkerTickProgress} computed
     * plain {@code isWater(dest)} and never carved. On the one geometry where the two disagree —
     * destination water, air directly above — Climb decided "place a support" while Progress still
     * called it a buoyant float, took the branch whose comment says an unfilled place cell is not
     * genuinely pending, and let bare height promote the step. {@code Walker.tickInner} runs
     * Progress before Climb, so the pointer left the edge before the support existed.
     *
     * <p><b>Why the carve-out belongs to the shared answer and the pillarUp test does not.</b> The
     * carve-out is about GEOMETRY — what this cell is — so both callers need it. Whether the edge
     * under the pointer is a pillarUp at all is about WHICH EDGE, which only Progress has to ask
     * because Climb is already inside that branch. Folding the edge test in here would have made
     * Climb ask a question it has no {@code se} to answer.
     *
     * <p><b>Direction of the change, per call site.</b> Climb: byte-identical — this is its old
     * expression, moved. Progress: STRICTER and never looser — the result can only go true→false,
     * and each of the two places it feeds tightens when it does (the pending-edge check reverts to
     * {@code hasPendingEdge}, and the arrival gate reverts to requiring {@code onGround()}). A
     * pointer that used to advance mid-float now waits for the support, which is the fix.
     */
    static boolean floodedShaft(WorldView world, BlockPos dest) {
        if (!world.isWater(dest)) return false;
        // Surface, not shaft: water underfoot with AIR directly above is the water-bank climb-out.
        // Floating higher is physically impossible there, so the bot must crest and place.
        return !(ControlConfig.walkerPillarSurfacePlace && !world.isWater(dest.above()));
    }

    /**
     * Have the feet risen clear of {@code cell}, so that vanilla can accept a block placed into it?
     *
     * <p><b>1.0, and it is not a tuning knob.</b> A player's AABB starts at {@code p.getY()} and the
     * cell occupies {@code [y, y+1]}; {@code Level#isUnobstructed} refuses any placement whose cell
     * still intersects that box. So {@code p.getY() >= cell.y + 1.0} is the exact boundary between
     * "will be placed" and "will be silently refused" — there is nothing to tune, and a looser
     * number does not buy earlier placements, it buys refusals.
     *
     * <p><b>This is the FULL-CUBE boundary, and it is only that.</b> {@code isUnobstructed} tests the
     * collision shape of the state being PLACED, not the cell it goes in, so the number above is the
     * boundary for a block whose collision box is a whole cube. The climb-out places a different kind
     * of block into a different kind of cell and needs {@link WaterClimb#crestClearOf}; unifying the two is what
     * regressed {@code wd.waterLowBank}, because for that call site this predicate is not merely
     * stricter — it is unsatisfiable (see there).
     */
    static boolean feetClearOf(LivingEntity p, BlockPos cell) {
        return p.getY() >= cell.getY() + 1.0;
    }

    /** TTL for a breath-infeasible break cell in {@link ClientWorldView}'s poison set
     *  (~60 s): long enough that repeated repaths within the episode route around it,
     *  short enough that a later revisit with tools / from dry ground reprices it. */
    static final long BREATH_POISON_TTL_MS = 60_000;
    /** Effort ceiling for a single executor dig (~30 s of continuous mining): past
     *  this a visible detour always wins, and in water the drift-release + progress
     *  zeroing make the true cost effectively unbounded (live: bare-hand floating
     *  stone ≈3750t estimated, 0.05 progress per 65-155t drift-released lap). Chosen
     *  so a floating bare-hand DIRT climb-out (≈375t) stays allowed while any
     *  bare-hand stone-family dig in/over water (≥750t) is refused. */
    private static final int HOPELESS_DIG_TICKS = 600;
    private WalkerTickClimb() {}

    /**
     * The breath gate on a break cell, and what to do when it fails: drop the hold, poison the cell
     * so the next search routes around it instead of re-proposing it, and drop the path so the tick
     * ends in a repath. Two dig sites had these eight lines written out verbatim.
     *
     * @return true when the caller must return {@link Walker.Step#WALKING} at once.
     */
    private static boolean bailOnBreathInfeasibleDig(Walker wk, Body a, LivingEntity p, BlockPos b) {
        if (!breathInfeasibleDig(p, b)) return false;
        wk.blocks.breakHold(false);
        BreakFeasibility.poison(b, BREATH_POISON_TTL_MS);
        if (GameConfig.walkerDebug)
            LOG.info("[walker] breath-infeasible dig {} — poisoned {}s, repathing", b, BREATH_POISON_TTL_MS / 1000);
        wk.error = "breath-infeasible dig at " + b;
        wk.route.drop(wk.error);
        return true;
    }

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        // ---- original body (byte-identical modulo member prefixes) ----

        Move.Edge edge = wk.edgeAt(wk.route.step());

        // === Comprehensive per-tick Walker trace (walkerDebug) ===
        // The single source of truth for "why is the bot stuck": for the current
        // step it prints the exact advance-decision inputs (horizontal dist² vs the
        // REACH_DIST_SQ gate, the |Δy| vs the 1.2 gate that together decide `within`),
        // how many ticks we've been stuck on THIS step, the move type + its
        // break/place needs, and the live bot state. Read this trace top-to-bottom
        // to see precisely which condition fails tick after tick — no guessing.
        if (GameConfig.walkerDebug) {
            if (wk.route.step() != wk.dbgPrevStep) { wk.dbgPrevStep = wk.route.step(); wk.dbgTicksOnStep = 0; }
            wk.dbgTicksOnStep++;
            BlockPos nd = wk.route.path().get(wk.route.step());
            double ddx = (nd.getX() + 0.5) - p.getX();
            double ddz = (nd.getZ() + 0.5) - p.getZ();
            double cur2 = ddx * ddx + ddz * ddz;
            double dY = nd.getY() - p.getY();
            boolean within = cur2 < REACH_DIST_SQ && Math.abs(dY) < 1.2;
            BlockPos br0 = (edge != null && !edge.toBreak.isEmpty()) ? edge.toBreak.get(0) : null;
            // bearing TO the node (MC yaw: 0=+z south, atan2(-dx,dz)); yawErr = how far the bot's body
            // faces OFF that bearing. With hCol this separates "rammed a wall, facing right" (stuck against a wall) from
            // "facing the wrong way, not driving toward the node" (aim/drive bug) — the missing axis that
            // forced guessing on every "won't close" stall.
            double bearing = Math.toDegrees(Math.atan2(-ddx, ddz));
            double yawErr = angleDiff(p.getYRot(), (float) bearing);
            LOG.info("[walker] t={} step={}/{} move={} node={},{},{} p=({},{},{}) pitch={} yaw={} bear={} yawErr={} hCol={} lastAim={} cur2={} (gate {}) |dY|={} (gate 1.2) within={} onG={} inW={} undW={} stuck={} totStuck={} pend={} break0={}{}",
                    wk.dbgTicksOnStep, wk.route.step(), wk.route.path().size(), edge != null ? edge.move : "-",
                    nd.getX(), nd.getY(), nd.getZ(),
                    String.format(Locale.ROOT, "%.2f", p.getX()), String.format(Locale.ROOT, "%.2f", p.getY()), String.format(Locale.ROOT, "%.2f", p.getZ()),
                    String.format(Locale.ROOT, "%.0f", p.getXRot()),
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    String.format(Locale.ROOT, "%.0f", bearing),
                    String.format(Locale.ROOT, "%.0f", yawErr),
                    p.horizontalCollision,
                    String.format(Locale.ROOT, "%.0f", wk.aimSmooth.lastAimYaw),
                    String.format(Locale.ROOT, "%.3f", cur2), REACH_DIST_SQ,
                    String.format(Locale.ROOT, "%.2f", Math.abs(dY)), within,
                    p.onGround(), p.isInWater(), p.isUnderWater(),
                    wk.stuckTicks, wk.totalTicks, edge != null && hasPendingEdge(world, edge),
                    br0, br0 != null ? (world.isSolid(br0) ? " (solid)" : " (clear)") : "");
        }

        // Pillar-up actuator: clear the ceiling if one blocks the rise, then
        // jump and place the support block beneath at the apex. Distinct from
        // the generic place actuator because it owns the airborne timing
        // (you can't place a block in the cell you're standing in).
        if (edge != null && "pillarUp".equals(edge.move) && hasPendingEdge(world, edge)) {
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
                return Walker.Step.WALKING;
            }
            Walker.avatarForward(a, false);
            p.setSprinting(false);
            wk.totalTicks = 0;
            wk.stuckTicks = 0;   // pillaring stays on one cell while placing — not "stuck"
            if (wk.route.step() != wk.pillar.step) { wk.pillar.step = wk.route.step(); wk.pillar.sinceJump = -1; }
            if (++wk.actionTicks > ControlConfig.breakTimeoutTicks) {
                wk.blocks.breakHold(false);
                wk.avatarJump(a, false);
                wk.error = "pillar stalled at " + wk.route.path().get(wk.route.step());
                wk.route.drop(wk.error);
                return Walker.Step.WALKING;
            }
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    wk.avatarJump(a, false);
                    wk.equipment.selectTool(b);
                    if (bailOnBreathInfeasibleDig(wk, a, p, b)) return Walker.Step.WALKING;
                    world.signals().digging = true;
                    // Claim, aim and drive all live inside avatarDig now: this site used to dig `b`
                    // first and claim it afterwards, so it drove a cell another phase already held.
                    WalkerDig.avatarDig(wk, a, b, false);
                    return Walker.Step.WALKING;
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
            // The climbout-place takeover next door is gated lower (crestClearOf, 0.9)
            // and clicks in the 62.9-63.0 band. That is NOT a dead band, which this
            // comment used to claim: isUnobstructed tests the collision shape of the
            // state being PLACED, and that takeover carries mud — 14/16 = 0.875 tall —
            // so its clicks land there. The two paths are gated for different BLOCKS,
            // not for different luck; this one holds throwaway full cubes and 1.0 is
            // its real boundary. See crestClearOf for the whole argument.
            //
            // That carve-out now lives in floodedShaft() because WalkerTickProgress has
            // to reach the same answer; when it was written here only, Progress kept
            // calling this cell a float and advanced the pointer off an unplaced support.
            boolean shaftFlooded = floodedShaft(world, wk.route.path().get(wk.route.step()));
            if (shaftFlooded || p.isInWater() || world.isWater(wk.route.path().get(wk.route.step()).offset(0, -1, 0))) {
                wk.avatarJump(a, true);
                // gap#81: routine pillar/scaffold filler must not spend gathered wood.
                if (!shaftFlooded && wk.equipment.holdThrowawayPlaceable()) {
                    BlockPos wp = edge.toPlace.get(0);
                    p.setXRot(89.5f);                       // look down to aim the support
                    if (feetClearOf(p, wp)) {              // bobbed clear of the place cell
                        wk.blocks.placeOn(wp.offset(0, -1, 0), Direction.UP);
                        wk.exAlarms.notePlace(wp);
                    }
                }
                return Walker.Step.WALKING;
            }
            if (!wk.equipment.holdPlaceable()) {
                wk.error = "pillar: no placeable block in hotbar";
                wk.route.drop(wk.error);
                return Walker.Step.WALKING;
            }
            BlockPos place = edge.toPlace.get(0);                    // cell we fill (old feet cell)
            BlockPos support = place.offset(0, -1, 0);               // click its top face (block we stood on)
            p.setXRot(89.5f);                                        // look straight down (snap)
            if (p.onGround()) {
                wk.avatarJump(a, true);
                wk.pillar.sinceJump = 0;
            } else {
                wk.avatarJump(a, false);
                if (wk.pillar.sinceJump >= 0) wk.pillar.sinceJump++;
                // Place only once the feet have actually risen clear of the cell
                // being filled. The target IS the old feet cell, so vanilla's
                // entity-collision check (Level#isUnobstructed) silently rejects
                // the block while the player AABB still overlaps it — i.e. until
                // the feet (getY) reach that cell's top face, place.y + 1. A
                // vanilla jump (peak ~+1.25) only crosses +1.0 around tick 4, so
                // the old fixed 3-tick delay fired at ~+0.99 and the place
                // no-op'd against the player's own hitbox. Gate on real height.
                if (wk.pillar.sinceJump >= PILLAR_PLACE_DELAY && p.getY() >= place.getY() + 1.0) {
                    wk.blocks.placeOn(support, Direction.UP);
                    wk.exAlarms.notePlace(place);
                }
            }
            return Walker.Step.WALKING;
        }

        // Parkour-place actuator (Baritone allowParkourPlace): a two-phase leap
        // owned here so the generic place actuator below (which freezes all
        // motion to place) can't kill the arc's momentum.
        //   LEAP  (floor still air): forward + sprint + jump off the lip with
        //         run-up PRESERVED, then place the landing block mid-air the
        //         instant a support is in reach. The hit is synthetic
        //         (clientUseItemOn), so aiming down isn't needed — the
        //         crosshair stays on the destination for the whole arc.
        //   SETTLE(floor placed, still airborne): keep driving forward to clear
        //         the gap, but DROP sprint and hold sneak — sneak's ledge guard
        //         stops the bot on the fresh 1-wide block on touchdown instead
        //         of letting sprint momentum carry it off the far edge into a
        //         gap beyond (matters when the place-support is below/beside,
        //         not a walkable same-level wall).
        // Once placed AND grounded the guard falls through to the normal walk /
        // arrival check.
        if (edge != null && edge.move != null && edge.move.startsWith("parkourPlace")
                && (hasPendingEdge(world, edge) || !p.onGround())) {
            BlockPos dest = wk.route.path().get(wk.route.step());
            BlockPos floor = edge.toPlace.get(0);
            boolean placed = !hasPendingEdge(world, edge);   // landing block is down
            boolean grounded = p.onGround();
            wk.totalTicks = 0;
            wk.stuckTicks = 0;       // owns this cell while leaping — not "stuck"
            if (++wk.actionTicks > ControlConfig.breakTimeoutTicks) {
                wk.error = "parkour-place stalled at " + dest;
                wk.route.drop(wk.error);
                return Walker.Step.WALKING;
            }
            // Snap heading at the destination — can't course-correct mid-air.
            double adx = (dest.getX() + 0.5) - p.getX();
            double adz = (dest.getZ() + 0.5) - p.getZ();
            if (Math.abs(adx) > 1e-4 || Math.abs(adz) > 1e-4) {
                float yaw = (float) Math.toDegrees(Math.atan2(-adx, adz));
                p.setYRot(yaw);
                p.yHeadRot = yaw;
                p.yBodyRot = yaw;
                LookController.requestSnap();   // a parkour leap's heading is functional — exempt from the global slew
            }
            p.setXRot(0f);
            Walker.avatarForward(a, true);
            wk.avatarJump(a, !placed && grounded);   // jump off the lip once
            boolean sprint = !placed;                          // brake after the block is down
            p.setSprinting(sprint);
            Walker.avatarSneak(a, placed);               // sneak-brake / ledge-guard on landing
            p.setShiftKeyDown(placed);
            if (!placed && !grounded && wk.equipment.holdPlaceable()) {
                // 4.0, not the full blockReachToCentre(p): this fires MID-LEAP, so the eye read
                // here is already a tick stale by the time the place lands. The margin is bought
                // on purpose — see BotUtil#standingEye for the table of what each reach site pays.
                if (eyeWithin(p, floor, PARKOUR_PLACE_REACH)) {
                    wk.blocks.place(world::isSolid, floor);
                    if (GameConfig.walkerDebug)
                        LOG.info(
                                "[walker] parkour-place floor={},{},{} y={} dy={} solid={}",
                                floor.getX(), floor.getY(), floor.getZ(),
                                String.format(Locale.ROOT, "%.2f", p.getY()),
                                String.format(Locale.ROOT, "%.2f", p.getDeltaMovement().y),
                                world.isSolid(floor));
                }
            }
            return Walker.Step.WALKING;
        }

        // Break/place actuator: mine or place the blocks this edge needs
        // before walking into the cell. Functional aim SNAPS (same-tick),
        // independent of smoothLook. Returns each tick until the edge is
        // clear, then falls through to the normal walk below.
        // A break cell beyond mining reach is walked to, never dug at from here. The client's
        // MultiPlayerGameMode takes any cell: it accumulates progress and breaks the block LOCALLY
        // at full progress, the server refuses the break by distance and sends the block back, and
        // the bot has stood still the whole time. Live: the pointer sat on a downBreak column 8
        // cells away, the block was "broken" and restored twice, the next plan stepped down through
        // the phantom air, and the bot orbited the column. Out of reach, fall through to the walk.
        boolean breakInReach = true;
        if (edge != null) {
            for (BlockPos b : edge.toBreak) {
                if (!world.isSolid(b)) continue;
                breakInReach = eyeWithin(p, b, blockReachToCentre(p));
                if (!breakInReach && GameConfig.walkerDebug && p.tickCount % 20 == 0)
                    LOG.info("[walker] break cell {},{},{} out of reach ({} blocks from the eye) → walk to it first",
                            b.getX(), b.getY(), b.getZ(),
                            String.format(Locale.ROOT, "%.1f", Math.sqrt(p.getEyePosition().distanceToSqr(b.getCenter()))));
                break;
            }
        }
        if (edge != null && wk.pillarRecover.latch <= 0 && breakInReach && hasPendingEdge(world, edge)) {
            // pillarRecover.latch gate: while a fall-below-route recovery is active,
            // this actuator would otherwise grab the edge's HIGH toBreak cell
            // (unreachable from down here) and hold a futile dig until its own
            // timeout — starving the recovery block below that actually climbs.
            Walker.avatarForward(a, false);
            wk.avatarJump(a, false);
            p.setSprinting(false);
            wk.totalTicks = 0;                       // breaking/placing IS progress
            wk.stuckTicks = 0;                       // foot stays put while placing — don't trip the wiggle-jump (it'd leap off a 1-wide bridge)
            if (++wk.actionTicks > ControlConfig.breakTimeoutTicks) {
                // Lag or an unexpected obstruction — drop the path and let
                // the next tick repath from the current position.
                wk.blocks.breakHold(false);
                wk.error = "break/place stalled at " + wk.route.path().get(wk.route.step());
                wk.route.drop(wk.error);
                return Walker.Step.WALKING;
            }
            // Water-escape break (swimAshore / swimTraverseBreak): while breaking the
            // bank, a FLOATING bot drifts off its foot cell and the edge invalidates
            // before the block breaks — it bobs and never climbs out. We anchor by
            // pressing INTO the bank, but ONLY when the head is at the surface
            // (!isUnderWater). The earlier unconditional keyUp drowned the bot:
            // forward input while SUBMERGED drops it into the prone swim pose and it
            // sinks. Gating on surface means forward can't trigger swim-pose, so it
            // presses the bot against the bank + jumps to mount, holding position
            // long enough to finish the dig. (#9 autoSwim still surfaces it each tick.)
            boolean swimEscapeBreak = edge.move != null
                    && (edge.move.startsWith("swimAshore") || edge.move.startsWith("swimTraverseBreak"));
            // Generic floating-pocket climb-break: the planner can route a *dry*
            // move (stairUpBreak / stepUp) through a flooded canyon pocket. Executed
            // while the bot floats (isInWater && !onGround) with the break cell at or
            // above the feet, the buoyant bot drifts off its foot cell and the edge
            // invalidates before the block breaks → it bobs forever hand-mining the
            // bank without escaping (live canyon water-pocket dig-loop at
            // (2358,62,1863), break0=(2358,63,1862); the break-exempt stuck counter
            // means neither the freeze-breaker nor the anti-stuck burst engages).
            // Anchor it like swimAshore: press INTO the aimed bank at the surface
            // (gated !isUnderWater so forward never triggers the prone-swim drown)
            // and jump to mount the freed cell. swimEscape moves keep their own
            // handling (excluded), descending/diving breaks (cell below the feet)
            // are excluded so this never fights a swimDown.
            boolean floatingPocket = !swimEscapeBreak && p.isInWater()
                    && !p.onGround() && !p.isUnderWater();
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    wk.equipment.selectTool(b);
                    if (bailOnBreathInfeasibleDig(wk, a, p, b)) return Walker.Step.WALKING;
                    world.signals().digging = true;
                    // Claim, aim and drive all live inside avatarDig now: this site used to dig `b`
                    // first and claim it afterwards, so it drove a cell another phase already held.
                    WalkerDig.avatarDig(wk, a, b, false);
                    boolean climbBreak = floatingPocket && b.getY() >= foot.getY();
                    if ((swimEscapeBreak && p.isInWater() && !p.isUnderWater()) || climbBreak) {
                        Walker.avatarForward(a, true);     // press into the aimed bank (surface only)
                        if (climbBreak || edge.move.startsWith("swimAshore"))
                            wk.avatarJump(a, true);   // rise to mount the +1 / out of the pocket
                    }
                    return Walker.Step.WALKING;
                }
            }
            wk.blocks.breakHold(false);
            for (BlockPos b : edge.toPlace) {
                if (!world.isSolid(b)) {
                    // Descending-place LIP ANCHOR (task#4, replay-0013): the place
                    // cell is BELOW the foot — the bot stands at a lip bridging
                    // DOWN. This actuator only zeroes the drive inputs; residual
                    // walk momentum still slides the bot off the lip during the
                    // aim ticks (live: x 89.81→89.28 over 9 ticks, fall, 74×
                    // climb-back/repath wedge). Hold sneak while the support is
                    // pending: vanilla's ledge-guard arrests the slide AT the
                    // edge, the place lands, and the moment the cell is solid
                    // this branch stops firing — the normal (sneak-free) walk
                    // performs the planned step-down. Same-level places are
                    // byte-identical (cell not below the foot).
                    if (ControlConfig.walkerBridgeDescentPlaceAnchor
                            && b.getY() < foot.getY() && p.onGround()) {
                        Walker.avatarSneak(a, true);
                        p.setShiftKeyDown(true);
                    }
                    if (GameConfig.walkerDebug)
                        LOG.info(
                                "[walker] place-act foot={},{},{} y={} step={} placing={},{},{} onGround={} edge={}",
                                foot.getX(), foot.getY(), foot.getZ(), String.format(Locale.ROOT, "%.2f", p.getY()),
                                wk.route.step(), b.getX(), b.getY(), b.getZ(), p.onGround(), edge.move);
                    a.aimAtBlock(b);
                    wk.blocks.place(world::isSolid, b);
                    return Walker.Step.WALKING;
                }
            }
            return Walker.Step.WALKING;                  // settle a tick before walking on
        }
        wk.blocks.breakHold(false);
        wk.actionTicks = 0;

        // Pillar placed but the player is still rising onto it — hold (no
        // horizontal walk) until grounded at the new level, so we don't walk
        // off the fresh block mid-jump.
        if (edge != null && "pillarUp".equals(edge.move)
                && !(p.onGround() && p.getY() >= wk.route.path().get(wk.route.step()).getY() - 0.1)) {
            Walker.avatarForward(a, false);
            wk.avatarJump(a, false);
            p.setSprinting(false);
            return Walker.Step.WALKING;
        }
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        cx.edges.edge = edge;
        return null;
    }

    /** HOPELESS-DIG GATE (2026-07-21 live, flooded Mountains channel): estimate the
     *  dig with vanilla's own {@code getDestroyProgress} (already includes
     *  eyes-in-water ÷5, off-ground ÷5, and the currently held tool) and refuse to
     *  start digs that provably cannot pay off. Two prongs:
     *  <ol>
     *  <li><b>Effort ceiling</b> (any stance): past {@link #HOPELESS_DIG_TICKS} a
     *      single block costs more than any visible detour (bare-hand floating stone
     *      ≈3750t; live: prog 0.05 per 65-155t lap, drift-released and zeroed every
     *      lap — an unbounded sink). Mirrors the planner's wrong-tool aversion on the
     *      executor side, where stub/escape adoptions used to sneak past pricing.</li>
     *  <li><b>Breath box</b> (DEEP water only — water well above the head, same test
     *      as AutoSwim's deep-ascent): vanilla zeroes destroyProgress on any
     *      interruption and a fully-submerged dig gets one breath of uninterrupted
     *      work (maxAir − drownEscape floor − reserve ≈ 180t); longer digs can NEVER
     *      complete. Surface-bobbing digs refill air at the bob peaks, so they are
     *      judged by the effort ceiling alone.</li>
     *  </ol>
     *  Callers poison the cell (TTL) so the very next search routes around.
     *  <p>CLIENT-SIDE ONLY: the estimate models the vanilla client mining loop
     *  (per-tick destroyProgress, zeroed on interruption). Server avatars break
     *  through their own simplified fast path, so gating them on the vanilla
     *  estimate refused digs their executor completes easily (t0 wd.buoyantWall
     *  regression: bare-hand +5 stone wall the avatar mounts fine). */
    static boolean breathInfeasibleDig(LivingEntity p, BlockPos b) {
        // getDestroyProgress prices the dig for a Player's tool and stance; the client-only
        // guard above already restricts this to the local player, so a non-player falls
        // through to the same "not estimated" answer a server-side player gets.
        if (!p.level().isClientSide() || !(p instanceof Player pl)) return false;
        float dmg = p.level().getBlockState(b).getDestroyProgress(pl, p.level(), b);
        if (dmg >= 1f) return false;                       // instant-mine — always fits
        if (dmg <= 0f) return true;                        // unbreakable from here
        // STANCE NORMALIZATION (2026-07-21 live, shoreline poison storm): the live
        // estimate bakes in THIS tick's stance — a bobbing or jumping player eats vanilla's
        // off-ground ÷5 and eyes-in-water ÷5 — but the poison it justifies lasts 60 s
        // and outlives the stance. Live: the same stone bank cell is 150t dug grounded
        // ashore yet 750-3750t sampled mid-bob, so the gate carpet-poisoned the whole
        // climb-out shoreline while floating and the freshly-landed bot then had no
        // priced route into the hill — it stopped digging and rammed the bank instead.
        // Hopelessness must be judged from the best REACHABLE digging stance:
        //  - off-ground ÷5 always undone (the bot can always ground — ashore or on
        //    the basin floor);
        //  - eyes-in-water ÷5 undone when the target sits ABOVE the waterline (a dry
        //    stance beside it is reachable; if the player actually has aqua affinity
        //    this overcorrects toward permissive, which is harmless).
        float norm = 1f;
        if (!p.onGround()) norm *= 5f;
        boolean targetDry = !p.level().getFluidState(b.above()).is(net.minecraft.tags.FluidTags.WATER);
        if (targetDry && p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) norm *= 5f;
        if (Math.ceil(1f / (dmg * norm)) > HOPELESS_DIG_TICKS) return true;   // never worth it from ANY stance
        if (!p.isUnderWater()) return false;
        // Breath box uses the LIVE estimate: it asks "can THIS submerged dig finish
        // on this breath", and down here the wet stance is the real one.
        BlockPos foot = p.blockPosition();
        boolean deep = p.level().getFluidState(foot.above(2)).is(net.minecraft.tags.FluidTags.WATER);
        return deep && Math.ceil(1f / dmg) > p.getMaxAirSupply() - ControlConfig.drownEscapeAirThreshold - 20;
    }
}

package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.BreakFeasibility;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Direction;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.movement.PathSmoothing.*;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.movement.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 2348–3279): per-tick trace, water climb-out foothold (pillar takeover / bank dig), pillar-up actuator.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickClimb {
    /**
     * Is this pillarUp destination a FLOODED shaft — one the body floats straight up through —
     * rather than a water SURFACE it must place a support to leave?
     *
     * <p><b>The single answer both phase classes ask.</b> It used to be computed twice. This class
     * carved the surface case out of "flooded" (the {@code walkerPillarSurfacePlace} branch below,
     * which is the flag's only reader in the whole product); {@link WalkerTickProgress} computed
     * plain {@code isWater(dest)} and never carved. On the one geometry where the two disagree —
     * destination water, air directly above — Climb decided「place a support」while Progress still
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
        // Floating higher is physically impossible there, so the body must crest and place.
        return !(BotConfig.walkerPillarSurfacePlace && !world.isWater(dest.above()));
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
     * of block into a different kind of cell and needs {@link #crestClearOf}; unifying the two is what
     * regressed {@code wd.waterLowBank}, because for that call site this predicate is not merely
     * stricter — it is unsatisfiable (see there).
     */
    static boolean feetClearOf(Player p, BlockPos cell) {
        return p.getY() >= cell.getY() + 1.0;
    }

    /**
     * The climb-out's own crest gate: may the body click NOW, into the cell it is standing in?
     *
     * <p><b>Why {@link #feetClearOf} cannot be used here.</b> On the locked column the fill cell IS
     * the body's foot cell ({@code colFoot == foot}), so "feet entirely above the cell" expands to
     * {@code p.getY() >= floor(p.getY()) + 1.0} — a contradiction. Asking it here does not place
     * fewer blocks, it places NONE, for every body and every held item; the whole self-column family
     * goes silent. {@code [cell.y+0.9, cell.y+1.0)} is the ONLY window in which a self-column click
     * can exist at all.
     *
     * <p><b>Why a click in that window is not automatically refused.</b> Vanilla intersects the body
     * against {@code state.getCollisionShape(...)}, and the blocks this takeover actually carries are
     * not full cubes: {@code MudBlock.SHAPE = Block.box(0,0,0,16,14,16)} — 14/16 = 0.875 tall, as are
     * soul sand, farmland and dirt path. (Its {@code getBlockSupportShape} IS the full cube; they are
     * different shapes and only the collision one is asked here.) A body at {@code cell.y + 0.95}
     * clears mud's top by 0.075 and vanilla accepts. That is not a loophole — it is the mechanism the
     * water-bank climb-out is built on: fill the cell under your own feet, then jump off it, one cell
     * per cycle.
     *
     * <p><b>What it costs when the hand holds a full cube.</b> The click fires and vanilla refuses it.
     * That is correct and, since {@code faa3189}, free: the futility ledger counts LANDINGS, not
     * clicks, so a refused click reads as no progress and the dig fallback still gets the bank on
     * schedule. Before that ledger existed this gate had to be conservative to keep refusals from
     * laundering themselves into progress; it no longer does.
     */
    static boolean crestClearOf(Player p, BlockPos cell) {
        return p.getY() >= cell.getY() + CREST_CLEAR;
    }

    /** The crest gate's bound — {@link #crestClearOf}. Above mud's 0.875 collision top with 0.025 to
     *  spare, and below the 1.0 a full cube needs, which is exactly the discrimination wanted. */
    private static final double CREST_CLEAR = 0.9;

    /**
     * One tick of the climb-out's place attempt, and of the ledger that decides when to stop trying.
     *
     * <p><b>The ledger counts RESULTS, and it has to lag a tick to do it.</b> Only the world can say
     * whether a placement landed, and it cannot say so until the tick after the click. This used to
     * be written as "clicked → zero the counter", which is a different event: {@link Avatar#place}
     * returns {@code void}, so the click never had a verdict to report, and a body sitting in the
     * band where vanilla refuses every placement pressed the button forever while gaining nothing.
     * The counter is what hands this bank over to the dig ({@code placeFutile} at the top of
     * {@code run}), so resetting it on an event that is compatible with total failure disabled that
     * fallback outright — it could not fire, ever.
     *
     * <p>Someone else filling the cell also counts, and should: the body is no worse off for not
     * having done it itself, and the next rung is what matters.
     */
    private static void climboutPlaceTick(Walker wk, Avatar a, WorldView world, Player p,
                                          BlockPos fillCell, BlockPos foot, boolean dryGrounded) {
        boolean fcSolid = world.isSolid(fillCell);
        boolean fcSupport = Move.hasPlaceSupport(world, fillCell);
        boolean fcCleared = crestClearOf(p, fillCell);
        if (BotConfig.walkerDebug)
            LOG.info("[walker] climbout-place col={},{} fill={} fcSolid={} support={} cleared={}(p.y={} need={}) dryG={} foot.y={} ceil={}",
                    wk.waterClimb.colX, wk.waterClimb.colZ, fillCell.getY(), fcSolid, fcSupport, fcCleared,
                    String.format("%.2f", p.getY()), fillCell.getY() + CREST_CLEAR,
                    dryGrounded, foot.getY(), wk.waterClimb.targetY);
        // TWO ways to be making progress, and the second one is not a courtesy. The takeover's
        // product is「fill the cell under my own feet, then jump off it」, one cell per cycle — the
        // fill and the rise are two readings of ONE event, taken a tick apart, and either may be the
        // one this tick can see. wd.waterLowBank is the whole cycle in eight ticks: click at X.98,
        // the cell turns solid, soleOnSolid reads a full footprint, the ground-jump gate fires
        // +0.42, and the body arrives at (X+1).98 to do it again — three times, out of the water.
        // Counting only the click is what broke: Avatar#place returns void, so a refused click and a
        // landed one were the same event, and a body bobbing in a band it could never place from
        // pressed the button forever while the dig fallback behind it could not fire.
        // The rise half is bounded by the pillar ceiling and monotone (high-water, never a per-tick
        // delta), so it cannot launder buoyancy into progress the way「higher than last tick」would.
        BlockPos tried = wk.waterClimb.placeAttemptCell;
        boolean landed = tried != null && world.isSolid(tried);
        // HIGH-WATER, never a per-tick delta. The bob crosses a block boundary every cycle, so
        // "higher than last tick" is true forever and would launder buoyancy into progress; "higher
        // than ever" stops refreshing as soon as the bob settles between two cells, which is
        // exactly the deadlock this ledger exists to notice.
        boolean rose = foot.getY() > wk.waterClimb.pillarHighWaterY;
        if (rose) wk.waterClimb.pillarHighWaterY = foot.getY();
        if (landed || rose) {
            wk.waterClimb.pillarNoPlaceTicks = 0;
            if (landed) wk.waterClimb.placeAttemptCell = null;
        } else {
            wk.waterClimb.pillarNoPlaceTicks++;
        }
        if (!fcSolid && fcSupport && fcCleared) {     // feet cleared the cell
            Walker.waterPillarPlaceCalls++;
            a.place(world, fillCell);
            // Remember THIS cell, overwriting any older attempt: an earlier cell that filled late
            // must not be allowed to pay for the one being clicked now.
            wk.waterClimb.placeAttemptCell = fillCell;
            wk.waterClimb.placedRungs.add(fillCell);
        }
    }

    /**
     * Latch the pillar takeover's column, heading and safety ceiling — <b>once, at engage</b>.
     *
     * <p>The takeover pillars the bot's own column STRAIGHT UP, pinned to one bank, until it tops
     * out of the water onto dry ground. Following the live (repathing) node instead made the bot
     * wander between columns — chasing dive-to-floor and other-column pillar nodes A* kept
     * replanning — and lose the wall-supported foothold (live round76c: engaged toward y145 pool
     * floor + x2438→2441 drift, never climbed out).
     *
     * <p><b>⚠️ Everything here is a LATCH, and it is a separate method so that it has exactly one
     * call site and that call site is behind the engage transition.</b> The caller's condition is
     * satisfied on every tick of a pillar that is already running — {@code waterClimb.stall} is
     * cleared only when the climb context is LEFT, and a body busy pillaring is still in the
     * context, so {@code stall} only grows and the branch re-enters every tick. Running these four
     * assignments on each of those ticks is what the missing gate used to do, and it silently
     * undid BOTH of the things they exist for:
     *
     * <ul>
     *   <li>the column/heading re-locked onto wherever the body had drifted to — which IS the
     *       round76c drift the lock was written to stop; the latch followed the body instead of
     *       pinning it;</li>
     *   <li>the safety ceiling re-anchored to the current foot, so the caller's
     *       {@code tooHigh = foot.getY() > targetY} read {@code foot.getY() > foot.getY() + 5}
     *       (one {@code foot} per tick, from {@code cx.frame.foot}) and was UNCONDITIONALLY FALSE.
     *       The bail could not fire, and it failed hardest exactly when it was needed most: the
     *       longer the body stayed wedged, the larger {@code stall} grew and the more reliably the
     *       ceiling was pushed back out of reach.</li>
     * </ul>
     *
     * <p>Both are one defect: <b>a guard whose threshold is computed from the very quantity it is
     * meant to bound can never bind it.</b> Keeping the assignment of {@link WalkerState#targetY}
     * to a single call site inside a method named for the transition is the structural half of the
     * fix — a future "just refresh it each tick" has to go through this name first.
     */
    private static void engagePillar(Walker wk, Player p, BlockPos foot, BlockPos cwp) {
        Walker.waterPillarEngages++;
        wk.waterClimb.colX = foot.getX();
        wk.waterClimb.colZ = foot.getZ();
        // Re-base the height ledger on THIS segment. A re-locked column that inherited the previous
        // one's high-water would start already "as high as it ever got" and could never register a
        // rise again — the second segment would be declared futile on height no matter how well it
        // climbed, which is the same mistake as counting the click instead of the block, one level up.
        wk.waterClimb.pillarHighWaterY = foot.getY();
        wk.waterClimb.placeAttemptCell = null;
        wk.waterClimb.sideRung = false;
        double ex = (cwp.getX() + 0.5) - p.getX();
        double ez = (cwp.getZ() + 0.5) - p.getZ();
        wk.waterClimb.yaw = (ex * ex + ez * ez > 1e-4)
                ? (float) Math.toDegrees(Math.atan2(-ex, ez)) : p.getYRot();
        // Safety ceiling: a sane bank is +1..+3; never pillar more than +5 above the engage
        // foot, then bail to the fallback actuators.
        wk.waterClimb.targetY = foot.getY() + PILLAR_CEILING_RISE;
    }

    /**
     * One tick of an ENGAGED pillar takeover: decide whether it is over (topped out dry, over the
     * ceiling, no block, drifted, stale, futile) and otherwise drive the rise — heading pinned to
     * the latched column, fill cell chosen, keys pressed, the click made. Returns the step to
     * report when the tick is consumed, {@code null} when the takeover has let go and the caller's
     * ordinary actuators / bank-dig take the tick.
     */
    private static Walker.Step pillarTakeoverTick(Walker wk, Avatar a, WorldView world, Player p,
                                                  BlockPos foot, BlockPos cwp, boolean wantClimbNow, boolean wantClimb) {
                boolean haveBlock = BotConfig.allowSwimEscapePlace && a.holdPlaceable();
                // Done when we've topped out onto DRY solid ground (grounded, clear of
                // water). The old `foot.y >= targetY` test fired the instant targetY was
                // a path node BELOW us (A* dives to the pool floor), declaring success at
                // the water surface before any real climb — round76c. A terrain test is
                // robust to whatever A* planned and to the exact bank height.
                boolean dryGrounded = p.onGround() && !p.isInWater()
                        && !world.isWater(foot) && !world.isWater(foot.below())
                        // ...AND actually topped out — not a MID-WALL rung. On a tall sheer
                        // climb the takeover places a rung, grounds on it dry, and this fired
                        // "topped out" at every +1 → flush-walk → repath → re-engage; without
                        // this the climb-out sometimes never completes (buoyantWallArena run
                        // reached only y-mid-wall, onPlateau=false). While the climb node is
                        // still ≥2 above the foot the wall continues up; only a node at ~foot
                        // level is the real bank top.
                        && !(wantClimbNow && cwp.getY() - foot.getY() >= 2)
                        // ...AND there is somewhere to WALK to. The climb node the takeover latched
                        // can sit BELOW the bank top (a swimAshore break target in the pool wall),
                        // so the rise test above read「topped out」on a 1×1 rung one course under
                        // the rim, A* then asked for a diagonal step-up off that rung, and the body
                        // walked off it back into the pool (wd.clientOneHighBankPlaceOut: a second
                        // climb-out, 500 ticks). A rung is the top only when a dry cell beside it
                        // can be stepped onto flush or down; otherwise the column keeps going up.
                        && (!BotConfig.walkerPillarTopsOutAtFlushExit || flushExitBeside(world, foot, wk.waterClimb.placedRungs));
                boolean tooHigh = foot.getY() > wk.waterClimb.targetY;
                // Reported whether or not it fires: this bail was unconditionally false until the
                // engage latch was fixed, and the water-climb family records no evidence at all, so
                // a guard that could not fire changed no colour and nothing in the suite could see
                // it. Rise is kept so「never got near」differs from「never asked」. See Walker.
                if (tooHigh) Walker.waterPillarCeilingBails++;
                Walker.waterPillarTopRise = Math.max(Walker.waterPillarTopRise,
                        foot.getY() - (wk.waterClimb.targetY - PILLAR_CEILING_RISE));
                // Self-correction: the latch pins the bot to ONE locked column +
                // heading, which goes stale two ways in a live crossing — (a) the bot
                // DRIFTS off the column (swimming along a continuous bank), so the place
                // targets an unreachable far cell; (b) A* repaths the climb away (now
                // routes down/along → wantClimb gone), pinning the bot to a wall it
                // should swim past. And (c) a buoyant bob simply can't lift its feet
                // above a surface fill cell, so the place never fires. Any of these →
                // release the latch; for (a)/(c)/over-ceiling, remember it
                // (climbPillarGaveUp) so the bank-DIG takes this bank instead of the
                // pillar re-engaging into the same hopeless bob. (live 2026-06-15:
                // latched col z1954, bot drifted to z1940 while the path went diagDown —
                // 90 s deadlock bobbing at an unreachable column.)
                boolean drifted = Math.abs(foot.getX() - wk.waterClimb.colX) > 2
                        || Math.abs(foot.getZ() - wk.waterClimb.colZ) > 2;
                // In side-rung mode the intent is allowed to flicker: the climb node A* named sits
                // at the body's level as soon as the first rung is mounted, and「no longer
                // climbing」then dropped the takeover one course under the bank, handing a body on
                // a 1×1 rung in a current to the ordinary walker. The mode ends on its own terms —
                // a flush exit, the ceiling, or futility (wd.clientFlowingTrenchPlaceOut, run 22).
                boolean staleClimb = !wantClimb && !(BotConfig.walkerShallowWaterSideFoothold && wk.waterClimb.sideRung);
                boolean placeFutile = wk.waterClimb.pillarNoPlaceTicks > PILLAR_FUTILE_TICKS;
                if (dryGrounded || tooHigh || !haveBlock || drifted || staleClimb || placeFutile) {
                    wk.waterClimb.pillaring = false;
                    wk.waterClimb.pillarNoPlaceTicks = 0;
                    if (dryGrounded) {
                        // Out of the water on solid ground → re-plan; a flush walk now.
                        wk.path = null;
                        wk.stuckTicks = 0;
                        wk.totalTicks = 0;
                        wk.waterClimb.stall = 0;
                        // The takeover owned the heading for the whole climb; the aim's low-pass
                        // state went on smoothing toward nodes the body was never facing. Left as
                        // it was, the first dry walk starts up to 180° off and the dry-land EMA
                        // (0.08) closes on a node bearing that rotates as fast as it turns — the
                        // body orbits the node a block out, for hundreds of ticks
                        // (wd.clientFlowingChannelPlaceOut, run 22: yaw wound from 69 to -817).
                        if (BotConfig.walkerClimbOutResyncsAim) wk.aimSmooth.reset();
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] water climb-out: topped out dry at y={} → flush walk, repath", foot.getY());
                        return Walker.Step.WALKING;
                    }
                    // Only "give up" the pillar (block re-engage, hand off to the bank-DIG)
                    // when that DIG can actually fire — i.e. breaking is allowed. With break
                    // OFF (no pickaxe: the live +2 mud-bank case) there is NO fallback, so
                    // latching climbPillarGaveUp would strand the bot bobbing forever. Leaving
                    // it false lets the pillar RE-ENGAGE next tick, re-locking the column to the
                    // bot's current foot — which the locked-heading forward press has nudged
                    // toward the bank — so the column RATCHETS to the supported bank-adjacent
                    // cell and the foothold-place finally lands (the pre-faa3189 behavior the
                    // self-correcting latch regressed: waterLowBankArena went red for ~5 days).
                    boolean digFallbackHere = wk.mayBreak() && BotConfig.allowSwimEscapeBreak;   // mayBreak(): honor per-goto forbidDig, not just the global switch
                    if ((drifted || placeFutile || tooHigh) && digFallbackHere) {
                        wk.waterClimb.pillarGaveUp = true;
                        // walkerClimbGaveUpSticky: anchor the latch to THIS bank so repath-driven
                        // context resets can't clear it while the bot is still here (15s TTL).
                        if (BotConfig.walkerClimbGaveUpSticky) {
                            wk.waterClimb.gaveUpPos = foot;
                            wk.waterClimb.gaveUpTtl = 300;
                        }
                    }
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] water climb-out: bail ({}) → fallback",
                                !haveBlock ? "no block" : tooHigh ? "over ceiling"
                                : drifted ? "drifted off column" : placeFutile ? "place futile"
                                : "no longer climbing");
                    return null;   // the normal actuators / bank-dig take the tick
                }
                return pillarDriveTick(wk, a, world, p, foot, dryGrounded);
    }

    /**
     * The rising half of an engaged pillar takeover, one tick: heading pinned to the latched
     * column, the fill cell chosen (surface cell afloat, the cell on the column's solid top when
     * dry), the side-foothold branch for one-deep water, the keys, and the click.
     */
    private static Walker.Step pillarDriveTick(Walker wk, Avatar a, WorldView world, Player p,
                                               BlockPos foot, boolean dryGrounded) {
                // Pin to the LOCKED bank heading + column; look down to aim the place.
                p.setYRot(wk.waterClimb.yaw); p.yHeadRot = wk.waterClimb.yaw; p.yBodyRot = wk.waterClimb.yaw;
                p.setXRot(40f);
                p.setSprinting(false);
                // Standing dry after a side rung, the column is wherever the body actually
                // stands: it mounts the rung by its edge, centre over the next column, and a
                // fill aimed at the rung's column meets the body's box while one under its own
                // centre does not. The pillar then grows under the centre — into the water cell
                // beside the rung first, if that is what is there — and the body ends on a
                // full column of its own.
                if (BotConfig.walkerShallowWaterSideFoothold && wk.waterClimb.sideRung
                        && p.onGround() && !p.isInWater()) {
                    wk.waterClimb.colX = foot.getX();
                    wk.waterClimb.colZ = foot.getZ();
                }
                // Fill the top water cell of the LOCKED column (floating) or the feet
                // cell (grounded on the fresh rung) — not the live foot column, which
                // drifts off the wall-supported pillar.
                BlockPos colFoot = new BlockPos(wk.waterClimb.colX, foot.getY(), wk.waterClimb.colZ);
                // THE TOP WATER CELL OF THE COLUMN, wherever the bob has carried the foot. This
                // used to fill `foot` whenever the column's foot-level cell was not water — which
                // is every tick the boost lifts the body INTO THE AIR above the surface, i.e.
                // exactly the ticks the place could land. The target then followed the bob: at
                // y 221.2 it asked for cell 221 and「cleared」at 221.9, back at 220.6 it asked for
                // 220, and the one-tick window where 220 was both the target and cleared was
                // skipped by the rise itself. wd.clientOneHighBankPlaceOut: 130 ticks of that
                // cycle, no block. Reaching DOWN to the water under the foot pins the target to
                // the surface cell; a body grounded on its fresh rung (solid below, not water)
                // still fills its own foot cell as before.
                BlockPos fillCell = colFoot;
                while (!world.isWater(fillCell) && world.isWater(fillCell.below())
                        && fillCell.getY() > foot.getY() - 2) fillCell = fillCell.below();
                if (!world.isWater(fillCell)) fillCell = foot;
                while (world.isWater(fillCell.above())) fillCell = fillCell.above();
                // DRY COLUMN: the cell to fill is the one standing on the column's solid top, not
                // the airborne foot. Taking `foot` here let the target ride the jump — at y 221.0
                // it asked for 221 and「cleared」at 221.9 — so a body jumping off its own rung
                // never had a fillable cell under it (wd.clientFlowingTrenchPlaceOut, third run).
                boolean dryColumn = BotConfig.walkerShallowWaterSideFoothold && !world.isWater(fillCell)
                        && !p.isInWater();
                if (dryColumn) {
                    while (!world.isSolid(fillCell.below()) && !world.isWater(fillCell.below())
                            && fillCell.getY() > foot.getY() - 2) fillCell = fillCell.below();
                }
                // ONE-DEEP WATER WITH A FLOOR, body afloat: the fill cell is the foot cell and
                // the feet cannot clear it. In a layer higher than 0.4 the jump key swims
                // (LivingEntity.aiStep: in water and not grounded → jumpInLiquid), the body
                // hovers between y+0.24 and y+0.96 and vanilla refuses a block that meets its
                // box — wd.clientFlowingTrenchPlaceOut: 50 ticks of「cleared」at .95, no rung.
                // The rung that CAN land is the one beside the body: a neighbour cell at foot
                // level is clear of the box, its floor is the support, and once it is solid
                // the forward press rides the +0.3 collision boost onto it. Standing there,
                // dry, the ordinary ground-jump pillar takes over. The column re-latches onto
                // the foothold so the next fill cell is the foot on it, not the water beside.
                boolean shallowAfloat = BotConfig.walkerShallowWaterSideFoothold && !p.onGround()
                        && p.isInWater() && fillCell.equals(foot) && world.isSolid(foot.below());
                // A rung already placed beside the body (the column re-latched onto it) is the
                // one to mount: keep the heading on it while the current carries the body, and
                // place nothing new. Without this the drift changed `foot` every tick and each
                // tick chose a fresh neighbour — eight dirt in a ring, and the ring then read
                // as a flush exit one course up (wd.clientFlowingTrenchPlaceOut, first run).
                BlockPos rung = new BlockPos(wk.waterClimb.colX, foot.getY(), wk.waterClimb.colZ);
                // The click's block shows up a tick or two later, so the rung just asked for
                // counts while it is still pending; the futility ledger bounds the wait.
                boolean rungPending = rung.equals(wk.waterClimb.placeAttemptCell) && !world.isSolid(rung);
                // A click the server has not honoured within the wait was refused (the body's
                // box met the cell at the server's copy of it); forget it so a fresh cell is
                // chosen instead of treading water on a rung that will never come.
                if (rungPending && wk.waterClimb.pillarNoPlaceTicks > PENDING_RUNG_WAIT_TICKS) {
                    wk.waterClimb.placeAttemptCell = null;
                    rungPending = false;
                }
                boolean rungBeside = shallowAfloat && !rung.equals(foot)
                        && (world.isSolid(rung) || rungPending)
                        && Math.abs(rung.getX() - foot.getX()) <= 1 && Math.abs(rung.getZ() - foot.getZ()) <= 1;
                // The body has swum INTO the cell it asked for before the block arrived (the
                // current, or the press toward it): vanilla refuses that placement. Tread water
                // a few ticks for the server's answer — the click was judged at the server's
                // copy of the body, which lags, and often lands anyway — before asking elsewhere.
                boolean insidePending = shallowAfloat && rung.equals(foot) && rungPending
                        && wk.waterClimb.pillarNoPlaceTicks <= PENDING_RUNG_WAIT_TICKS;
                // Keys. The classic takeover holds forward and jump every tick: the floating body
                // rams the bank, the +0.3 boost lifts it, the jump-place fills the surface cell.
                // Once a SIDE RUNG is in play the keys turn anticipatory, because a key read this
                // tick is the state set LAST tick: a body landing on its 1×1 rung with jump still
                // held from the swim jumps again on the landing tick, with the swim's momentum
                // still in it, and clears the rung entirely (wd.clientFlowingTrenchPlaceOut, run
                // 21: mounted at x .42, airborne at .53, over the next column at 1.10). So in
                // side-rung mode: forward only while wet (the press is what mounts the rung; dry
                // it walks off), and never while the rung is pending (pressing swims the body into
                // the cell and voids the click); jump while wet, or dry once grounded with the
                // momentum gone — released in the air so the landing tick reads it released.
                boolean sideMode = BotConfig.walkerShallowWaterSideFoothold && wk.waterClimb.sideRung;
                boolean wet = p.isInWater();
                boolean settled = p.getDeltaMovement().horizontalDistanceSqr() < SETTLED_SPEED_SQ;
                Walker.avatarForward(a, sideMode ? (wet && !rungPending && !insidePending) : true);
                wk.avatarJump(a, sideMode ? (wet || (p.onGround() && settled)) : true);
                if (rungBeside) {
                    wk.waterClimb.yaw = (float) Math.toDegrees(Math.atan2(
                            -((rung.getX() + 0.5) - p.getX()), (rung.getZ() + 0.5) - p.getZ()));
                } else if (shallowAfloat && !insidePending) {
                    BlockPos side = sideFoothold(world, foot, wk.waterClimb.yaw, p.getBoundingBox());
                    if (side != null) {
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] climbout-place side foothold {},{},{} (one-deep water, body afloat at y={})",
                                    side.getX(), side.getY(), side.getZ(), String.format("%.2f", p.getY()));
                        Walker.waterPillarPlaceCalls++;
                        a.place(world, side);
                        wk.waterClimb.placeAttemptCell = side;
                        wk.waterClimb.placedRungs.add(side);
                        wk.waterClimb.sideRung = true;
                        wk.waterClimb.colX = side.getX();
                        wk.waterClimb.colZ = side.getZ();
                        wk.waterClimb.yaw = (float) Math.toDegrees(Math.atan2(
                                -((side.getX() + 0.5) - p.getX()), (side.getZ() + 0.5) - p.getZ()));
                        return Walker.Step.WALKING;
                    }
                }
                climboutPlaceTick(wk, a, world, p, fillCell, foot, dryGrounded);
                return Walker.Step.WALKING;
    }

    /**
     * The neighbour cell a side foothold goes into: at foot level, not yet solid, floored by a solid
     * block (so the rung is a real 1-deep case and the floor is the click support), with head room
     * above it to stand in. The heading's own cardinal is tried first so the locked forward press
     * walks the body onto the rung; the other three follow. {@code null} when every side is a
     * wall or already filled — the caller then falls back to the ordinary own-column attempt.
     */
    private static BlockPos sideFoothold(WorldView world, BlockPos foot, float yaw, net.minecraft.world.phys.AABB body) {
        double rad = Math.toRadians(yaw);
        double hx = -Math.sin(rad), hz = Math.cos(rad);
        BlockPos first = Math.abs(hx) >= Math.abs(hz)
                ? foot.offset(hx >= 0 ? 1 : -1, 0, 0) : foot.offset(0, 0, hz >= 0 ? 1 : -1);
        BlockPos[] order = { first, foot.east(), foot.west(), foot.north(), foot.south() };
        // Vanilla refuses a block whose box meets the body's, and the body is 0.6 wide: standing
        // 0.09 into a neighbour cell already voids the click (the first trench click at z .09 never
        // landed). The margin buys the tick or two of drift before the server answers.
        net.minecraft.world.phys.AABB clearance = body.inflate(SIDE_RUNG_CLEARANCE, 0, SIDE_RUNG_CLEARANCE);
        for (BlockPos n : order) {
            if (world.isSolid(n) || world.isHazard(n)) continue;
            if (!world.isSolid(n.below())) continue;
            if (!world.isPassable(n.above()) || !world.isPassable(n.above(2))) continue;
            if (clearance.intersects(new net.minecraft.world.phys.AABB(n))) continue;
            return n;
        }
        return null;
    }

    /** A dry cell beside {@code foot} that the body can step onto without climbing: standable at
     *  the same level or one below, and neither it nor its floor is water. */
    private static boolean flushExitBeside(WorldView world, BlockPos foot, java.util.Set<BlockPos> placedRungs) {
        for (BlockPos n : new BlockPos[] { foot.east(), foot.west(), foot.north(), foot.south() }) {
            if (world.isWater(n) || world.isWater(n.below())) continue;
            // A rung this climb-out placed itself is not the shore: two side footholds a tick apart
            // read as「flush exit」one course up and the takeover let go three courses under the bank.
            boolean ownRung = BotConfig.walkerShallowWaterSideFoothold && placedRungs.contains(n.below());
            if (!ownRung && world.canStandAt(n) && leadsOn(world, foot, n)) return true;
            BlockPos d = n.below();
            boolean ownLowerRung = BotConfig.walkerShallowWaterSideFoothold && placedRungs.contains(d.below());
            if (!ownLowerRung && !world.isWater(d.below()) && world.canStandAt(d) && leadsOn(world, foot, d)) return true;
        }
        return false;
    }

    /** An exit cell is shore only if it connects onward: one of its own neighbours other than
     *  {@code foot} is dry and standable at its level or one off. A 1×1 island — a stray rung, a
     *  post — is not an exit, however dry (wd.clientFlowingTrenchPlaceOut topped out onto one). */
    private static boolean leadsOn(WorldView world, BlockPos foot, BlockPos exit) {
        if (!BotConfig.walkerShallowWaterSideFoothold) return true;
        for (BlockPos m : new BlockPos[] { exit.east(), exit.west(), exit.north(), exit.south() }) {
            if (m.getX() == foot.getX() && m.getZ() == foot.getZ()) continue;
            if (world.isWater(m) || world.isWater(m.below())) continue;
            if (world.canStandAt(m) || world.canStandAt(m.above()) || (world.canStandAt(m.below()) && !world.isWater(m.below(2)))) return true;
        }
        return false;
    }

    /** How far above the engage foot the pillar may climb before bailing to the fallback
     *  actuators. Named so the ceiling and the rise reported by {@link Walker#waterPillarTopRise}
     *  cannot drift apart, and so the reader can see it is a CONSTANT above a FIXED anchor —
     *  the whole defect this replaced was an anchor that moved with the body. */
    static final int PILLAR_CEILING_RISE = 5;

    /** TTL for a breath-infeasible break cell in {@link ClientWorldView}'s poison set
     *  (~60 s): long enough that repeated repaths within the episode route around it,
     *  short enough that a later revisit with tools / from dry ground reprices it. */
    private static final long BREATH_POISON_TTL_MS = 60_000;
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
    private static boolean bailOnBreathInfeasibleDig(Walker wk, Avatar a, Player p, BlockPos b) {
        if (!breathInfeasibleDig(p, b)) return false;
        a.breakHold(false);
        BreakFeasibility.poison(b, BREATH_POISON_TTL_MS);
        if (BotConfig.walkerDebug)
            LOG.info("[walker] breath-infeasible dig {} — poisoned {}s, repathing", b, BREATH_POISON_TTL_MS / 1000);
        wk.lastError = "breath-infeasible dig at " + b;
        wk.path = null;
        return true;
    }

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Avatar a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        Player p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        // ---- original body (byte-identical modulo member prefixes) ----

        Move.Edge edge = wk.edgeAt(wk.step);

        // === Comprehensive per-tick Walker trace (walkerDebug) ===
        // The single source of truth for "why is the bot stuck": for the current
        // step it prints the exact advance-decision inputs (horizontal dist² vs the
        // REACH_DIST_SQ gate, the |Δy| vs the 1.2 gate that together decide `within`),
        // how many ticks we've been stuck on THIS step, the move type + its
        // break/place needs, and the live body state. Read this trace top-to-bottom
        // to see precisely which condition fails tick after tick — no guessing.
        if (BotConfig.walkerDebug) {
            if (wk.step != wk.dbgPrevStep) { wk.dbgPrevStep = wk.step; wk.dbgTicksOnStep = 0; }
            wk.dbgTicksOnStep++;
            BlockPos nd = wk.path.get(wk.step);
            double ddx = (nd.getX() + 0.5) - p.getX();
            double ddz = (nd.getZ() + 0.5) - p.getZ();
            double cur2 = ddx * ddx + ddz * ddz;
            double dY = nd.getY() - p.getY();
            boolean within = cur2 < REACH_DIST_SQ && Math.abs(dY) < 1.2;
            BlockPos br0 = (edge != null && !edge.toBreak.isEmpty()) ? edge.toBreak.get(0) : null;
            // bearing TO the node (MC yaw: 0=+z south, atan2(-dx,dz)); yawErr = how far the bot's body
            // faces OFF that bearing. With hCol this separates "rammed a wall, facing right" (贴墙卡住) from
            // "facing the wrong way, not driving toward the node" (aim/drive bug) — the missing axis that
            // forced guessing on every "won't close" stall.
            double bearing = Math.toDegrees(Math.atan2(-ddx, ddz));
            double yawErr = angleDiff(p.getYRot(), (float) bearing);
            LOG.info("[walker] t={} step={}/{} move={} node={},{},{} p=({},{},{}) pitch={} yaw={} bear={} yawErr={} hCol={} lastAim={} cur2={} (gate {}) |dY|={} (gate 1.2) within={} onG={} inW={} undW={} stuck={} totStuck={} pend={} break0={}{}",
                    wk.dbgTicksOnStep, wk.step, wk.path.size(), edge != null ? edge.move : "-",
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

        // === Water climb-out foothold (place to get grounded) ===
        // Runs BEFORE the break/place actuators so it catches a bob-stall no matter
        // how A* labelled the climb (plain StepUp, StairUpBreak into the bank, …).
        // A floating bot can't gain height onto a bank whose top sits ABOVE the
        // water surface: swim-up tops out AT the surface (~0.6 short of the step-up
        // grab) and a break/pillar can't actuate from deep water either — so it
        // bob-cycles forever (live trace: y6.2 peak → sinks to y4.7, no NET rise).
        // Fix: once stalled with no vertical progress, at a bob peak (feet clear of
        // the water, surface right beneath) place ONE throwaway block to fill that
        // top water cell → a flush foothold the bot rests on, GROUNDED; from solid
        // ground the ordinary climb (step-up / break-carve / pillar) finishes the
        // +1/+2 (verified live). The counter only advances while height is NOT
        // rising, so a working pillar / swim-up that DOES gain height never trips
        // it. Default-ON escape permission + a placeable in hand required; reads
        // only here, so the pathfinder is byte-for-byte unchanged.
        {
            BlockPos cwp = wk.path.get(wk.step);
            // DROWNING-ESCAPE reflex (walkerDrowningEscape, default OFF): a submerged climb-out
            // deadlock (e.g. the pillar↔repath loop below) can pin the bot under a bank lip until
            // its air runs out — Peaceful does not prevent drowning (live 2026-06-29: died at
            // (-21,60,-42) while climbout-place spun 26 engage/bail cycles). Air below ~3s while
            // underwater → LATCH a surface-for-air override that preempts every climb/dig/pillar
            // actuator this tick: hold the swim-up jump, and if a solid lip caps the head (or a
            // wall blocks the rise) drive BACKWARD off the bank so buoyancy finds open surface.
            // Released once air recovers (or out of water); the interrupted climb resumes fresh.
            if (BotConfig.walkerDrowningEscape) {
                // WorldView truth gate: the client's isUnderWater/air flags survive a teleport
                // (and a corpse) un-ticked — a replay tp'd the bot onto DRY land with stale
                // undW=true/air=0 and the reflex latched + froze the whole drive. Engage (and
                // hold) only while the WORLD actually has water at the foot/eye and the bot is
                // alive; a stale-flag body falls through to the normal drive.
                boolean reallyInWater = p.getHealth() > 0
                        && (world.isWater(foot) || world.isWater(foot.above()));
                if (reallyInWater && p.isUnderWater() && p.getAirSupply() <= 60) {
                    if (!wk.drownGuard.latch && BotConfig.walkerDebug)
                        LOG.info("[walker] DROWNING-ESCAPE engaged: air={} foot={},{},{} → surface for air",
                                p.getAirSupply(), foot.getX(), foot.getY(), foot.getZ());
                    wk.drownGuard.latch = true;
                } else if (!reallyInWater || !p.isInWater() || p.getAirSupply() >= 240) {
                    if (wk.drownGuard.latch && BotConfig.walkerDebug)
                        LOG.info("[walker] DROWNING-ESCAPE released: air={} → resume", p.getAirSupply());
                    wk.drownGuard.latch = false;
                }
                if (wk.drownGuard.latch && reallyInWater && p.isInWater()) {
                    wk.avatarJump(a, true);
                    a.breakHold(false);
                    p.setSprinting(false);
                    boolean riseBlocked = world.isSolid(foot.offset(0, 2, 0)) || p.horizontalCollision;
                    if (riseBlocked) {
                        // Swim toward open surface. A single fixed "reverse" heading dies in a
                        // POCKET (live 2026-06-29 (-254,61,-219): capped head + the reverse
                        // heading also walled → the bot spun in place a full minute and drowned
                        // at hp 5→0). Probe the 8 horizontal directions for one whose column two
                        // cells out is water at head height with NO solid lid two above (a
                        // buoyant rise is possible there); rotate the probe start each pick so a
                        // falsely-open direction can't be re-picked forever. Re-pick every 25
                        // ticks (or first tick); between picks hold the heading so the body
                        // actually crosses cells instead of jittering.
                        if (wk.drownGuard.turnTicks <= 0) {
                            wk.drownGuard.turnTicks = 25;
                            float pick = p.getYRot() + 180f;   // fallback: straight back
                            for (int i = 0; i < 8; i++) {
                                float cand = ((wk.drownGuard.probe + i) % 8) * 45f;
                                int dx = (int) Math.round(-Math.sin(Math.toRadians(cand)));
                                int dz = (int) Math.round(Math.cos(Math.toRadians(cand)));
                                BlockPos out = foot.offset(dx * 2, 0, dz * 2);
                                if (world.isWater(out.above()) && !world.isSolid(out.offset(0, 2, 0))) {
                                    pick = cand;
                                    wk.drownGuard.probe = (wk.drownGuard.probe + i + 1) % 8;
                                    break;
                                }
                            }
                            wk.drownGuard.heading = pick;
                        }
                        wk.drownGuard.turnTicks--;
                        p.setYRot(wk.drownGuard.heading);
                        p.yHeadRot = wk.drownGuard.heading; p.yBodyRot = wk.drownGuard.heading;
                        p.setXRot(0f);
                        Walker.avatarForward(a, true);
                    } else {
                        Walker.avatarForward(a, false);
                    }
                    return Walker.Step.WALKING;
                }
            }
            // walkerClimbGaveUpSticky: run down the sticky gave-up TTL; expire the anchor once
            // the foot leaves the futile bank (>3 blocks) or the TTL runs out.
            if (wk.waterClimb.gaveUpTtl > 0) {
                wk.waterClimb.gaveUpTtl--;
                if (wk.waterClimb.gaveUpTtl == 0 || wk.waterClimb.gaveUpPos == null
                        || Math.abs(foot.getX() - wk.waterClimb.gaveUpPos.getX()) > 3
                        || Math.abs(foot.getZ() - wk.waterClimb.gaveUpPos.getZ()) > 3) {
                    wk.waterClimb.gaveUpTtl = 0;
                    wk.waterClimb.gaveUpPos = null;
                }
            }
            // Detecting a stalled climb-out must survive confounders that reset the old
            // accounting before it armed: (1) the bob peak breaches the surface so
            // `touchingWater` flickers false; (2) at that same peak the foot BLOCK rises
            // to the stepUp target Y, so `cwp.y > foot.y` (the climb intent) flickers
            // false; (3) A* repaths every ~13 ticks while it can't execute the climb,
            // nulling `edge`; (4) the peak-vs-high-water-mark "real rise" reset — and any
            // height-based substitute — is itself tripped by the ~1.5-block buoyant bob.
            // Fix: keep water-contact AND climb-intent STICKY across (1)-(3), then count
            // pure ticks-in-context with NO height reset (4). A genuine climb-out leaves
            // the context within ~10 ticks (grounds on the bank → no higher node ahead →
            // wantClimb false), so it never reaches WATER_CLIMB_STALL; only a real
            // bob-stall sits in-context long enough to arm. (live round76b: net-window
            // armed 0 takeovers; pure tick-count arms reliably.)
            // wantClimbNow normally needs the waypoint ABOVE the foot. But a FLOATING bot ramming a
            // water-bank LIP (walkerFloatingBankBobFreeze, live #47 repro -638,418→-652): the next wp can
            // be at the SAME Y across a 1-block lip, so cwp.y>foot.y is false and waterClimbing never arms
            // — the bot just jumps+rams the lip (hCol, hSpd~0) for 20+ s with no bank-dig/pillar recovery.
            // Treat "afloat + horizontally colliding while touching water" as wanting to climb so the
            // existing climb-out recovery (bank-dig / foothold-pillar) engages over the lip.
            boolean floatingBankRam = BotConfig.walkerFloatingBankBobFreeze
                    && !p.onGround() && p.horizontalCollision
                    && (world.isWater(foot) || world.isWater(foot.below()));
            // Water climb-out LATERAL gate (walkerWaterClimbLateralGate, task#91 structural, default ON,
            // baseline-EXEMPT): a floating bot climbs a bank ONLY when the climb waypoint sits horizontally
            // BESIDE it. A higher waypoint that is laterally distant is the routed exit further down an open
            // corridor (riverSheerBank: the low bank +5 EAST across open water, only +1 up) — honoring its
            // +height as a climb-here intent made the block-less dig trench the SHEER wall the bot was merely
            // passing. Reached instead by the swim-drive carrying the body along the corridor; the climb
            // re-arms once swum adjacent. A genuine bank climb-out has cwp directly beside/below the float
            // (Chebyshev 0-1) so it is unchanged. floatingBankRam (a real in-place wall-ram) is exempt.
            int cwpLatDist = Math.max(Math.abs(cwp.getX() - foot.getX()), Math.abs(cwp.getZ() - foot.getZ()));
            boolean climbTargetBeside = !BotConfig.walkerWaterClimbLateralGate
                    || cwpLatDist <= WATER_CLIMB_LATERAL_MAX;
            boolean touchingWater = p.isInWater() || world.isWater(foot) || world.isWater(foot.below());
            // THE INTENT IS MEASURED AGAINST THE SURFACE, NOT THE BOBBING FOOT. `cwp.y > foot.y` is
            // true at the bottom of a bob and false at its top, and a body pressed into a bank rides
            // the collision boost through a two-and-a-half-block bob whose top lasts longer than
            // WANT_CLIMB_STICKY — so the context was left and `stall` zeroed once per cycle and the
            // takeover never armed. wd.clientTwoHighBankPlaceOut: 180 ticks of that, no event at all.
            // A dry waypoint beside the body at or above its column's surface cell is a climb-out
            // whatever the foot reads this tick.
            boolean surfaceClimbIntent = false;
            if (BotConfig.walkerClimbIntentFromSurface && edge != null && touchingWater && climbTargetBeside
                    && !world.isWater(cwp)) {
                int surfY = foot.getY();
                while (world.isWater(new BlockPos(foot.getX(), surfY + 1, foot.getZ()))) surfY++;
                while (surfY > foot.getY() - 3 && !world.isWater(new BlockPos(foot.getX(), surfY, foot.getZ()))) surfY--;
                surfaceClimbIntent = cwp.getY() >= surfY;
            }
            boolean wantClimbNow = edge != null
                    && ((cwp.getY() > foot.getY() && climbTargetBeside) || floatingBankRam || surfaceClimbIntent);
            if (touchingWater) wk.waterClimb.touchRecent = WATER_TOUCH_STICKY;
            else if (wk.waterClimb.touchRecent > 0) wk.waterClimb.touchRecent--;
            boolean nearWater = touchingWater || wk.waterClimb.touchRecent > 0;
            if (wantClimbNow) wk.waterClimb.wantClimbRecent = WANT_CLIMB_STICKY;
            else if (wk.waterClimb.wantClimbRecent > 0) wk.waterClimb.wantClimbRecent--;
            boolean wantClimb = wantClimbNow || wk.waterClimb.wantClimbRecent > 0;
            // Durable dig-commit: while we're still mid-breaking a LATCHED riser that is
            // solid and right beside the bot, STAY committed even if A* transiently repaths
            // the climb away (wantClimb flicker). A buoyant bot mines a STONE bank by hand at
            // ~750 ticks/block (×5 not-on-ground); the old code dropped the half-broken riser
            // the instant wantClimb fell for WANT_CLIMB_STICKY ticks, so the bot abandoned
            // each block partway, wandered 10+ columns, and drifted into a deep hole and SANK
            // (live 2026-06-20: one stone block dug 517× then dropped; y62→y27). The commit is
            // capped per-riser (WATER_CLIMB_DIG_COMMIT_CAP, reset on each fresh riser) so a
            // genuinely stuck dig still releases to repath. Held only while afloat and still
            // beside the riser — grounding out (climbed) or drifting >2 off it ends it.
            if (p.onGround()) wk.waterClimb.digGroundedStreak++; else wk.waterClimb.digGroundedStreak = 0;
            boolean digGroundBlipOk = !p.onGround()
                    || (BotConfig.walkerBankDigGroundBlip && wk.waterClimb.digGroundedStreak <= 5);
            boolean digCommitted = wk.waterClimb.digRiser != null
                    && world.isSolid(wk.waterClimb.digRiser) && digGroundBlipOk
                    && Math.abs(foot.getX() - wk.waterClimb.digRiser.getX()) <= 2
                    && Math.abs(foot.getZ() - wk.waterClimb.digRiser.getZ()) <= 2
                    && wk.waterClimb.digCommitTicks < WATER_CLIMB_DIG_COMMIT_CAP;
            boolean waterClimbing = (wantClimb && nearWater && !p.onGround()) || digCommitted;
            // Floating over DEEP water (water directly below the foot) with the dig
            // available: a buoyant bot can't swim-jump a +1 bank AND can't clear a surface
            // fill cell to pillar, so the pillar is ALWAYS futile here — pure wasted bob.
            // Skip it and engage the fast dig directly (the live journey's banks are all
            // this case, and it was eating ~50 ticks of futile pillaring per bank before
            // climbPillarGaveUp fell through to the dig). When break is OFF (place-only
            // arena) this is false → the pillar is kept as the only exit.
            // ...and more broadly for ANY buoyant float: the pillar can't clear the +0.9 fill
            // regardless of whether the cell DIRECTLY below is water — the bot may bob over a
            // solid-floored shallow shelf beside the bank yet still be too buoyant to stand a
            // rung, so the old isWater(foot.below()) test missed it and let the futile pillar
            // re-engage (live 2026-06-24 -67x pit: 264 climbout-place ticks, foot.below() solid,
            // pillar↔dig↔repath thrash ~105 s). Ride the isInWater bob-blink with the latch.
            boolean buoyantFloat = !p.onGround() && (p.isInWater() || wk.surfaceWaterLatch > 0);
            boolean deepDig = (world.isWater(foot.below()) || buoyantFloat)
                    && wk.mayBreak() && BotConfig.allowSwimEscapeBreak;   // mayBreak(): honor per-goto forbidDig, not just the global switch
            // The climb context's inputs, one row per wet tick: the takeover and the bank dig are
            // gated on these, and a bob-stall with no event in the log is unreadable without them.
            if (BotConfig.walkerDebug && nearWater)
                LOG.info("[walker] climb-ctx cwp={},{},{} wantNow={} (foot={} ram={} surface={}) want={} near={} climbing={} stall={} deepDig={} pillaring={} gaveUp={} digging={} riser={}",
                        cwp.getX(), cwp.getY(), cwp.getZ(), wantClimbNow, cwp.getY() > foot.getY() && climbTargetBeside,
                        floatingBankRam, surfaceClimbIntent, wantClimb, nearWater, waterClimbing, wk.waterClimb.stall,
                        deepDig, wk.waterClimb.pillaring, wk.waterClimb.pillarGaveUp, wk.waterClimb.digging, wk.waterClimb.digRiser);
            if ((!wantClimb || !nearWater) && !digCommitted) {
                // Left the climb context (grounded on the bank, or A* now routes
                // down/along) → clear the per-attempt accounting AND the "pillar
                // gave up" latch, so the NEXT genuine climb-out starts fresh.
                // walkerClimbGaveUpSticky: EXCEPT while the sticky anchor is live — a repath
                // that swaps the climb node resets this context every ~2.5s, and clearing the
                // latch here is what let the proven-futile pillar re-engage 26× until the bot
                // drowned (live 2026-06-29). While the foot is still at the futile bank, keep it.
                wk.waterClimb.stall = 0;
                if (!(BotConfig.walkerClimbGaveUpSticky && wk.waterClimb.gaveUpTtl > 0)) wk.waterClimb.pillarGaveUp = false;
                wk.waterClimb.pillarNoPlaceTicks = 0;
                wk.waterClimb.lastDigRiser = null;
                wk.waterClimb.digRiser = null;
                wk.waterClimb.digCommitTicks = 0;
                wk.waterClimb.digFloatTicks = 0;
            } else {
                wk.waterClimb.stall++;
                if (digCommitted) wk.waterClimb.digCommitTicks++;
                // Track how long this dig has run while the bot stayed AFLOAT. Any ground
                // contact resets it: a LEGIT climb-out bob-jumps onto the freed +1 notch and
                // grounds, so it never accumulates; only a perpetual float on an unreachable
                // riser does (live -784: onGround=false for all ~1000 stall ticks).
                if (digCommitted) {
                    if (p.onGround()) wk.waterClimb.digFloatTicks = 0;
                    else wk.waterClimb.digFloatTicks++;
                }
                // FUTILE-OVERHANG early-release (BotConfig.walkerFutileBankDigRelease, default
                // OFF → byte-identical no-op). The dig has committed to one still-fully-solid
                // riser that sits >= FUTILE_BANK_DIG_MIN_RISE above the foot (an overhang the
                // buoyant bob can never reach) and has done so AFLOAT for FUTILE_BANK_DIG_TICKS
                // without ever grounding and without the riser breaking → it is provably
                // hopeless. Release it NOW (well before WATER_CLIMB_DIG_COMMIT_CAP=1000, ~40 s
                // sooner): latch climbPillarGaveUp so the futile pillar/dig don't re-engage at
                // this exact spot, drop the riser + the waterClimbDigging flag so breakingEdge
                // falls THIS tick, and price the pocket cell out — handing the wedge to the
                // already-working reactive churn-charge + anti-stuck back-off burst (both gated
                // !breakingEdge, which is why the unbroken dig kept them suppressed). The legit
                // +1 grounded staircase dig can't reach here: its riser is +1 (below the rise
                // gate), it grounds (resets floatTicks), and the riser breaks (resets the
                // counter via the fresh-riser path).
                if (BotConfig.walkerFutileBankDigRelease && digCommitted
                        && wk.waterClimb.digRiser != null && world.isSolid(wk.waterClimb.digRiser)
                        && wk.waterClimb.digFloatTicks > FUTILE_BANK_DIG_TICKS
                        && wk.waterClimb.digRiser.getY() - foot.getY() >= FUTILE_BANK_DIG_MIN_RISE) {
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] futile bank-dig release: riser {},{},{} (+{} above foot) unbroken for {} afloat ticks → drop dig, hand to recovery",
                                wk.waterClimb.digRiser.getX(), wk.waterClimb.digRiser.getY(), wk.waterClimb.digRiser.getZ(),
                                wk.waterClimb.digRiser.getY() - foot.getY(), wk.waterClimb.digFloatTicks);
                    world.penalizeStuckNode(foot);
                    world.penalizeStuckNode(foot.above());
                    wk.waterClimb.pillarGaveUp = true;
                    wk.waterClimb.digRiser = null;
                    wk.waterClimb.lastDigRiser = null;
                    wk.waterClimb.digCommitTicks = 0;
                    wk.waterClimb.digFloatTicks = 0;
                    wk.waterClimb.digging = false;
                    wk.waterClimb.futileBankDigCooldown = FUTILE_BANK_DIG_COOLDOWN;
                }
            }
            // Trigger once bob-stalled below a bank we can't mount, with a placeable in
            // hand — then LATCH a pillar-up that runs to completion. Suppressed once the
            // pillar has proven futile here (climbPillarGaveUp): a buoyant bob can't lift
            // its feet above a surface fill cell, so re-engaging just bobs again — the
            // bank-DIG below takes over instead.
            // swimAshore +2 with no toBreak block never commits a dig (waterClimbDigging stays
            // false), so deepDig suppresses the pillar yet the dig never runs → bob-churn. After the
            // ~4 s dig window with NO dig swinging, fall back to the pillar despite deepDig so the
            // placeable lifts the bot onto the bank. Flag-gated; default OFF keeps this byte-identical.
            boolean swimAshorePillarFallback = BotConfig.walkerSwimAshorePillarDespiteDeepDig
                    && deepDig && !wk.waterClimb.digging && wk.waterClimb.stall > WATER_CLIMB_DIG_STALL;
            // FOOTHOLD FIRST. `deepDig` used to send a body holding a block to the dig before the
            // pillar, on the belief that a buoyant bob can never lift its feet clear of the surface
            // fill cell. The real client refutes it: a body pressed INTO the bank rides vanilla's
            // +0.3 collision boost (LivingEntity.travel) to feet ≈ +1.8 above the surface cell, and
            // wd.clientOneHighBankPlaceOut measured the takeover placing its foothold six ticks after
            // engaging — after sixteen seconds of hopeless bare-hand stone digging that deepDig had
            // put in front of it. So the pillar goes first whenever a block is in hand; a bank where
            // the place really cannot land hands over to the dig through `placeFutile` (50 ticks),
            // which is still seven times cheaper than the shortest floating dig.
            if (waterClimbing && wk.waterClimb.stall > WATER_CLIMB_STALL && !wk.waterClimb.pillarGaveUp
                    && (!deepDig || swimAshorePillarFallback || BotConfig.walkerFootholdBeforeBankDig)
                    && BotConfig.allowSwimEscapePlace && a.holdPlaceable()) {
                // THIS CONDITION IS SATISFIED ON EVERY TICK OF A PILLAR THAT IS ALREADY RUNNING,
                // so everything latched below has to be gated on the transition rather than on
                // the condition. `wk.waterClimb.stall` is only cleared when the climb context is
                // LEFT (see the reset above: `(!wantClimb || !nearWater) && !digCommitted`); a body
                // that is busy pillaring is still in the context, so `stall` only grows and this
                // `if` re-enters every tick. Re-entry is the normal case here, not an edge case.
                boolean engaging = !wk.waterClimb.pillaring;
                if (engaging && BotConfig.walkerDebug)
                    LOG.info("[walker] water climb-out: pillar takeover engaged (bob-stalled) toward bank node {},{},{}",
                            cwp.getX(), cwp.getY(), cwp.getZ());
                wk.waterClimb.pillaring = true;
                if (engaging) engagePillar(wk, p, foot, cwp);
            }
            // PILLAR-UP climb-out: place support blocks in the bot's OWN column up to the
            // bank stand level, so the final move onto the bank is a flush WALK — not a
            // fragile in-place +1 jump. A single surface foothold only lifts +1; a +2
            // bank then left an un-runnable +1 step (no running room, water behind) the
            // bot pogo-bobbed forever (live round69: jumped to bank height but z frozen,
            // never translated across). Reading-only — the pathfinder is unchanged.
            if (wk.waterClimb.pillaring) {
                Walker.Step took = pillarTakeoverTick(wk, a, world, p, foot, cwp, wantClimbNow, wantClimb);
                if (took != null) return took;
            }
            // Block-less climb-out fallback: a buoyant bot bob-stalled below a +1
            // bank with NO usable (non-falling) support block can't pillar — but it
            // can DIG. Break the single bank riser toward the climb node at foot
            // level so the +1 mount becomes a flat swim into the notch: the bot
            // enters the freed cell, grounds on whatever's below, and the next step
            // is a normal grounded climb (or a clean repath from the lower cell).
            // Gated identically to the pillar takeover (waterClimbing + bob-stalled) so
            // a grounded land step never triggers it. Fires when there's no place block
            // OR the pillar gave up here (climbPillarGaveUp) — a buoyant bob can't lift
            // its feet above a surface fill cell, so for a bank whose top is above the
            // water the DIG is the reliable primitive whether or not blocks are in hand
            // (the live z1940 deadlock had sand/gravel/cobble yet the place never cleared
            // → 90 s bob; the dig breaks the riser and the bot swims into the notch).
            // (live 2026-06-15: deep-water +1 dirt bank, sand/gravel-only inventory →
            // holdPlaceable false → 12.6s bob-stall, move=diagUp with empty toBreak so
            // neither swimAshore nor the floating-pocket break engaged; bob peak y63.56
            // sat 0.44 below the y64 ledge, hCol ramming the riser every tick.)
            int digStall = deepDig ? WATER_CLIMB_DIG_DEEP_STALL : WATER_CLIMB_DIG_STALL;
            if (wk.waterClimb.futileBankDigCooldown > 0) wk.waterClimb.futileBankDigCooldown--;   // post-release dig lockout (walkerFutileBankDigRelease)
            // walkerBankDigSkipWhenCwpSwims: the committed path's NEXT node (cwp) being a WATER cell
            // means the bot should SWIM into it, not dig — the real climb-out (a stepUp onto land)
            // sits FURTHER along the path, not here. Digging at a water-routed waypoint is premature:
            // the omnidirectional exit scan below picks the NEAREST dry exit, which on a tall sheer
            // bank (goal-side wall ~9 blocks) is the perpendicular wall face (dot≈0, passes the
            // forward-hemisphere guard) — so the bot trenches the goal-side wall, aimAtBlock locks the
            // yaw at it, the forward drive rams it, and it bob-stalls forever while A*'s actual path
            // swims west around to a lower climb-out (live 2026-06-27 deadlock @ -646,62,351, stuck
            // 1181 ticks: cwp=-648,62,352 WATER, dug east wall instead of swimming the path). Skipping
            // the dig when cwp is water lets the normal swim-drive follow the path to the real exit.
            // A genuine climb-out HERE routes cwp to a LAND/stepUp node (not water) so the dig still
            // fires for it. Default OFF; validate via replay A/B on the archived deadlock.
            boolean cwpSwims = BotConfig.walkerBankDigSkipWhenCwpSwims && world.isWater(cwp);
            if (!wk.waterClimb.pillaring && waterClimbing && wk.waterClimb.stall > digStall
                    && wk.waterClimb.futileBankDigCooldown <= 0 && !cwpSwims
                    && wk.mayBreak() && BotConfig.allowSwimEscapeBreak   // mayBreak(): honor per-goto forbidDig, not just the global switch
                    // deepDig no longer jumps the queue past a block in hand — see the foothold-first
                    // note at the pillar takeover; the pillar's own `placeFutile` bail sets pillarGaveUp.
                    && (!a.holdPlaceable() || wk.waterClimb.pillarGaveUp
                        || (deepDig && !BotConfig.walkerFootholdBeforeBankDig))) {
                // Keep digging the LATCHED riser while it's still solid — a buoyant bob
                // (foot.y flickering ±1) or lateral drift (foot.z wandering) must NOT
                // re-target a lower block of the same column or a neighbouring column
                // mid-dig. Choose a fresh riser only once the latched one breaks.
                // The fix the drift arena exposed: the old code dug `foot.y` directly, so
                // a low bob dug the foot-level block AND a high bob dug the step block of
                // the SAME column → the bank surface tunnelled DOWN to the water line and
                // the next column stayed a fresh +2 wall (infinite pogo, ashoreTick 162).
                // 兜底 anti-wander column LOCK: lock ONE chimney column at engage and dig
                // it straight up. Without it the dig re-derives the target from the live
                // (repathing) cwp every time a riser breaks, so the buoyant bot drifts along
                // the bank digging a fresh column each time and never tops out (live
                // 2026-06-20: 4545 digs across 10+ columns x2320-2342, never grounded). The
                // pillar takeover locks its column the same way; the toolless dig now does too.
                BlockPos riser = wk.waterClimb.digRiser;
                // walkerBankDigForwardExit, latch re-validation: a LATCHED riser is only re-chosen
                // when it goes null/non-solid (below), but the 25× underwater mining penalty means a
                // backward riser can NEVER break — so it stays latched and the forward-hemisphere
                // guard in the re-scan branch never re-applies (live isolation 2026-06-27: 1024 digs
                // all at the backward riser even with the guard ON). Drop a latched riser that now
                // points BACKWARD of cwp so the scan re-runs and re-picks a forward exit (or null →
                // no dig → swim the path). cwp follows the planned path, so this respects a path that
                // legitimately routes backward (its cwp points backward too).
                if (riser != null && BotConfig.walkerBankDigForwardExit
                        && (cwp.getX() != foot.getX() || cwp.getZ() != foot.getZ())) {
                    int rdx = riser.getX() - foot.getX();
                    int rdz = riser.getZ() - foot.getZ();
                    int gdx0 = Integer.signum(cwp.getX() - foot.getX());
                    int gdz0 = Integer.signum(cwp.getZ() - foot.getZ());
                    if (rdx * gdx0 + rdz * gdz0 < 0) riser = null;   // latched backward → force re-scan
                }
                if (riser == null || !world.isSolid(riser)) {
                    riser = null;
                    // Climb toward the NEAREST dry-standable EXIT (a cell the bot can stand on:
                    // solid floor, 2 air above, no water), NOT the far lateral goal (cwp). The
                    // old cwp direction made the bot trench the waterline layer SIDEWAYS toward a
                    // far goal — tunnelling INTO the bank at one Y and never stepping up onto the
                    // land 1-2 blocks above (live 2026-06-20: buried in the hill at y63 for 5 min
                    // digging +x toward the 2600 goal instead of the +2 to the y65 land). Aim at
                    // the closest way OUT; the onward path resumes once grounded on dry land.
                    int dx = Integer.signum(cwp.getX() - foot.getX());   // fallback: goal direction
                    int dz = Integer.signum(cwp.getZ() - foot.getZ());
                    int bestExitD2 = Integer.MAX_VALUE;
                    // walkerBankDigForwardExit: the omnidirectional exit scan below picks the NEAREST
                    // dry exit in ANY direction — including BEHIND the bot. When the nearest exit is
                    // backward (opposite the path's cwp) the climb-out digs AWAY from the goal into a
                    // churn — the live -733/-710 "dig the west wall behind me while the goal is east"
                    // deadlock (1000+ digs at the backward riser, ~48 s frozen, bot reverses 46 blocks).
                    // Bias the scan to the FORWARD hemisphere (dot(offset, cwp-dir) >= 0) so it only
                    // digs toward where the planned path actually leads. If NO forward exit exists the
                    // dx/dz fallback above (= cwp direction = forward) still drives a forward dig, so
                    // the bot never trenches backward. A genuinely backward path routes cwp backward
                    // too, so "forward" follows the PATH (the immediate waypoint), not the absolute goal.
                    // Independent of walkerBuoyantSearchFromSurface (which fixes the search START): even
                    // a correctly forward path can have its exit scan pick a closer backward exit.
                    boolean fwdExitGuard = BotConfig.walkerBankDigForwardExit
                            && (cwp.getX() != foot.getX() || cwp.getZ() != foot.getZ());
                    int gdx = Integer.signum(cwp.getX() - foot.getX());
                    int gdz = Integer.signum(cwp.getZ() - foot.getZ());
                    for (int sx = -4; sx <= 4; sx++)
                        for (int sz = -4; sz <= 4; sz++) {
                            if (sx == 0 && sz == 0) continue;
                            if (fwdExitGuard && (sx * gdx + sz * gdz) < 0) continue;   // skip backward-hemisphere exits
                            for (int sy = 1; sy <= 4; sy++) {
                                BlockPos land = new BlockPos(foot.getX() + sx, foot.getY() + sy, foot.getZ() + sz);
                                if (world.isSolid(land.below()) && !world.isSolid(land)
                                        && !world.isSolid(land.above()) && !world.isWater(land)
                                        && !world.isWater(land.below())) {
                                    int d2 = sx * sx + sz * sz;
                                    if (d2 < bestExitD2) {
                                        bestExitD2 = d2;
                                        dx = Integer.signum(sx);
                                        dz = Integer.signum(sz);
                                    }
                                    break;   // nearest (lowest) exit in this column
                                }
                            }
                        }
                    BlockPos[] cands = {
                            (dx != 0 || dz != 0) ? new BlockPos(foot.getX() + dx, foot.getY(), foot.getZ() + dz) : null,
                            dx != 0 ? new BlockPos(foot.getX() + dx, foot.getY(), foot.getZ()) : null,
                            dz != 0 ? new BlockPos(foot.getX(), foot.getY(), foot.getZ() + dz) : null};
                    // Dig the forward column's LOWEST solid cell ABOVE the waterline — a DRY notch
                    // whose floor (the cell below it) stays solid, so the bob-jump can ground on
                    // it (the +1 climb-out). Anchored on the bot's water-column surface Y (steady),
                    // not the bobbing foot/eye (an eye anchor broke buoyantWallArena). The cands
                    // follow the path's cwp, so as the bot climbs they advance +z+y → a DIAGONAL
                    // staircase up into the solid hill (a vertical column-lock chimney can't be
                    // ascended — the bot digs out its own floor; a real bank is a solid massif the
                    // staircase climbs THROUGH).
                    int surfY = foot.getY();
                    while (world.isWater(new BlockPos(foot.getX(), surfY + 1, foot.getZ()))) surfY++;
                    for (BlockPos cand : cands) {
                        if (cand == null) continue;
                        int ry = surfY + 1;
                        while (ry <= surfY + 5 && !world.isSolid(new BlockPos(cand.getX(), ry, cand.getZ()))) ry++;
                        BlockPos riserCand = new BlockPos(cand.getX(), ry, cand.getZ());
                        // Overhang rejection (walkerBankDigSkipOverhang): the riser must be a genuine
                        // bank-face step whose FLOOR (cell below) is solid — the invariant this comment
                        // already states ("a DRY notch whose floor stays solid") but the lowest-solid
                        // scan above omits. An air-floored riser is a CEILING/overhang the buoyant bob
                        // can never ground beside: digging it does nothing, the bot yaw-locks into it
                        // and bob-stalls while A*'s real climb-out (a pillarUp ~8 blocks along the
                        // water) goes unfollowed. Rejecting it leaves riser null → no dig → the bot
                        // swims the committed path to the real exit. The legit staircase-dig tunnels a
                        // SOLID massif (floor always solid) so it is unaffected.
                        boolean overhang = BotConfig.walkerBankDigSkipOverhang && !world.isSolid(riserCand.below());
                        // Hopeless-dig rejection: skip candidates the current stance/tool
                        // can never finish (bare-hand floating stone &c.) so the scan
                        // falls through to a diggable column or to no dig at all —
                        // the same gate the latched-riser check below applies.
                        if (ry <= surfY + 5 && world.isSolid(riserCand) && !overhang
                                && !breathInfeasibleDig(p, riserCand)) { riser = riserCand; break; }
                    }
                    wk.waterClimb.digRiser = riser;
                    wk.waterClimb.digCommitTicks = 0;   // fresh riser → fresh per-block commit budget
                }
                // Hopeless-dig gate on the LATCHED riser too: the latch predates the
                // gate (or the stance changed — e.g. the bot sank into deep water), and
                // a hopeless dig only burns drift-released zero-progress laps (live:
                // prog 0.05 per 65-155t lap at a bare-hand stone riser). Poison it so
                // searches route around, drop the latch, and let the scan above pick a
                // diggable column next tick — or none, in which case the bot swims the
                // committed path instead of mining a wall it can never break.
                if (riser != null && breathInfeasibleDig(p, riser)) {
                    BreakFeasibility.poison(riser, BREATH_POISON_TTL_MS);
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] hopeless bank dig {} — poisoned {}s, latch dropped", riser, BREATH_POISON_TTL_MS / 1000);
                    wk.waterClimb.digRiser = null;
                    riser = null;
                }
                if (riser != null) {
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] water climb-out: block-less bank dig (bob-stalled, no place block) riser={},{},{}",
                                riser.getX(), riser.getY(), riser.getZ());
                    a.selectTool(riser);
                    // Re-snap the look onto the riser ONLY when it changes, not every
                    // tick: aimAtBlock SNAPS yaw+pitch from the LIVE (bobbing) eye, so a
                    // per-tick call judders the camera ~25°/cycle — the "镜头剧烈抖动" the
                    // video flags during digs. The first snap aims dead-on; the ±0.5 bob
                    // then keeps the crosshair on the 1-tall riser face while the camera
                    // holds steady, and we only re-aim when the dig moves to a new riser.
                    // A TALL riser (>=2 above the floating foot) sits far enough above the
                    // bobbing eye that the once-only snap lets the mining ray drift OFF the
                    // block face as the bot bobs +-0.5 — the break never completes and the
                    // dig re-fires forever (live z2744 confined deep shaft: riser y64 vs foot
                    // y61, 1000+ swimUp/stairUpBreak ticks, full-shaft bob y62<->y47, no exit).
                    // Re-aim EVERY tick for a tall riser to hold the look ray on the block face
                    // (a little camera judder is the lesser evil vs a hard deadlock; tall
                    // confined digs are rare). A +1 riser keeps the steady once-only snap so
                    // the common shallow bank-dig camera stays smooth.
                    // Aim-stabilisation, dynamic-correction half: re-aim at the riser
                    // EVERY tick. The buoyant bot bobs at a bank (and dips underwater), so
                    // any tick we SKIP the re-aim the mining ray slips off the riser face
                    // and vanilla continueDestroyBlock resets the break — and on the 25×
                    // underwater+afloat mining penalty (dirt ≈375 ticks/block) it then NEVER
                    // completes (live 2026-06-20: same riser dug 1118 ticks, 0 breaks; the
                    // old CONDITIONAL re-aim left exactly those gaps). A per-tick re-aim pins
                    // the crosshair to the block face through the bob so the break
                    // accumulates to completion. Paired with the bob-tame jump below (holds
                    // the eye near the riser → near-horizontal ray), the residual camera
                    // motion stays small. Reliable digging is the priority at a climb-out.
                    // CLAIM FIRST, THEN DIG WHAT WAS CLAIMED. This block used to dig `riser`
                    // unconditionally and claim it afterwards, so a claim the travel drive already
                    // held was ignored here — the two phases alternated targets every 20–40 ticks
                    // and each switch threw away the other's destroyProgress. See
                    // Walker.StickyDig.engage for the measurement that named it.
                    BlockPos digCell = Walker.avatarDig(wk, a, riser);
                    wk.waterClimb.lastDigRiser = digCell;
                    wk.waterClimb.lastDigAimEyeY = p.getEyeY();
                    // Sticky-dig coverage gap (2026-07-21 live lake basin): the per-tick
                    // re-aim above holds the CAMERA on the riser, but a bob that dips the
                    // eye below the surface still makes the mining raycast MISS for those
                    // ticks and vanilla zeroes destroyProgress — the exact disease the
                    // direct destroy above cures by advancing the exact cell whatever the
                    // crosshair does; StickyDig then holds that dig across ticks. This
                    // dig site predates that machinery and never engaged it, so bank digs
                    // kept resetting through the bob while actuator digs held fine. Engage
                    // the same holder; it self-releases on break/stall/drift as everywhere.
                    // (The engage moved ABOVE the dig — see the claim note there.)
                    BotConfig.walkerDigActive = true;   // AutoSwim's backstop yields while air is healthy
                    // Mark the dig active: next tick's breakingEdge holds the leash and
                    // exempts the burst so the dig can finish (see the breakingEdge note).
                    // Clear any burst count accrued during the pre-dig bob-stall so the
                    // very first dig tick can't fire a stale burst before the exemption.
                    wk.waterClimb.digging = true;
                    wk.unstuck.wedgeRepathsHere = 0;
                    // Aim-stabilisation, BALANCE half — the jump (space) is corrected, not
                    // held flat-out and not bang-banged to the riser. Both extremes bob:
                    // holding jump CONTINUOUSLY over-swims the bot up; bang-banging to the
                    // riser centre overshoots on the jump impulse into a 2-block limit cycle
                    // (y61↔63 live) that slings the ray off the face. The minimal-bob hold a
                    // human uses to tread water: swim up ONLY when the head goes underwater —
                    // just enough to STAY AT THE SURFACE. Head out → no jump → it settles
                    // with a tiny natural bob; the instant it dips under → one correction
                    // pops it back up. The waterline riser then sits just below the steady
                    // surface eye → a near-horizontal, bob-tolerant mining ray. (Covers
                    // "松手空格就会沉下去": it still swims up the moment it submerges.)
                    // ...PLUS a controlled climb term: rise when the eye is clearly BELOW
                    // the current riser (more than 0.3 under its base). For a riser at eye
                    // level this is false → pure tread-water (the stable cycle-4 hold); once
                    // a cell breaks and the next riser sits a block higher, the eye drops
                    // below it → the bot swims UP to it and re-anchors there → it ascends the
                    // staircase instead of treading in place. The 0.3 deadband + no-sprint
                    // keep the rise from overshooting back into a bob.
                    boolean needRise = p.isUnderWater() || p.getEyeY() < riser.getY() - 0.3;
                    wk.avatarJump(a, needRise);
                    // No sprinting: a sprinting bot swim-DIVES into the prone pose and dunks
                    // its head underwater (the live "潜入水底/仰头空挖" thrash + the 25× mining
                    // penalty). Upright tread keeps the head out and the dig fast.
                    p.setSprinting(false);
                    // Press INTO the bank to enter the broken notch — but ONLY once the foot has
                    // risen to the notch floor (foot.y ≳ riser.y − 0.6). In DEEP water the bot
                    // floats with its foot ~2 below the surface, so pressing forward while still
                    // low RAMS the riser's solid floor-cell (riser.below()) and pins the bot below
                    // the +1 step — it breaks the block but never steps onto it and slides back
                    // into the water (live 2026-06-20: stuck at y61 ramming the y62 bank, "挖穿后
                    // 掉回水里"). Below the notch, suppress forward and just SWIM UP (the jump
                    // above); once the foot reaches the ledge, press in and ground on it. (Forward
                    // while submerged also drops the bot into the prone-swim pose and it sinks.)
                    if (!p.isUnderWater() && p.getY() >= riser.getY() - 0.6) Walker.avatarForward(a, true);
                    return Walker.Step.WALKING;
                }
            }
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
            BlockPos pcol = wk.path.get(wk.step);
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
                a.breakHold(false);
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
            if (wk.step != wk.pillar.step) { wk.pillar.step = wk.step; wk.pillar.sinceJump = -1; }
            if (++wk.actionTicks > BotConfig.breakTimeoutTicks) {
                a.breakHold(false);
                wk.avatarJump(a, false);
                wk.lastError = "pillar stalled at " + wk.path.get(wk.step);
                wk.path = null;
                return Walker.Step.WALKING;
            }
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    wk.avatarJump(a, false);
                    a.selectTool(b);
                    if (bailOnBreathInfeasibleDig(wk, a, p, b)) return Walker.Step.WALKING;
                    BotConfig.walkerDigActive = true;
                    // Claim, aim and drive all live inside avatarDig now: this site used to dig `b`
                    // first and claim it afterwards, so it drove a cell another phase already held.
                    Walker.avatarDig(wk, a, b);
                    return Walker.Step.WALKING;
                }
            }
            a.breakHold(false);
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
            boolean shaftFlooded = floodedShaft(world, wk.path.get(wk.step));
            if (shaftFlooded || p.isInWater() || world.isWater(wk.path.get(wk.step).offset(0, -1, 0))) {
                wk.avatarJump(a, true);
                // gap#81: routine pillar/scaffold filler must not spend gathered wood.
                if (!shaftFlooded && a.holdThrowawayPlaceable()) {
                    BlockPos wp = edge.toPlace.get(0);
                    p.setXRot(89.5f);                       // look down to aim the support
                    if (feetClearOf(p, wp)) {              // bobbed clear of the place cell
                        a.placeOn(wp.offset(0, -1, 0), Direction.UP);
                        wk.exAlarms.notePlace(wp);
                    }
                }
                return Walker.Step.WALKING;
            }
            if (!a.holdPlaceable()) {
                wk.lastError = "pillar: no placeable block in hotbar";
                wk.path = null;
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
                // no-op'd against the player's own body. Gate on real height.
                if (wk.pillar.sinceJump >= PILLAR_PLACE_DELAY && p.getY() >= place.getY() + 1.0) {
                    a.placeOn(support, Direction.UP);
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
            BlockPos dest = wk.path.get(wk.step);
            BlockPos floor = edge.toPlace.get(0);
            boolean placed = !hasPendingEdge(world, edge);   // landing block is down
            boolean grounded = p.onGround();
            wk.totalTicks = 0;
            wk.stuckTicks = 0;       // owns this cell while leaping — not "stuck"
            if (++wk.actionTicks > BotConfig.breakTimeoutTicks) {
                wk.lastError = "parkour-place stalled at " + dest;
                wk.path = null;
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
            if (!placed && !grounded && a.holdPlaceable()) {
                // 4.0, not the full blockReachToCentre(p): this fires MID-LEAP, so the eye read
                // here is already a tick stale by the time the place lands. The margin is bought
                // on purpose — see BotUtil#standingEye for the table of what each reach site pays.
                if (eyeWithin(p, floor, PARKOUR_PLACE_REACH)) {
                    a.place(world,floor);
                    if (BotConfig.walkerDebug)
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
        if (edge != null && wk.pillarRecover.latch <= 0 && hasPendingEdge(world, edge)) {
            // pillarRecover.latch gate: while a fall-below-route recovery is active,
            // this actuator would otherwise grab the edge's HIGH toBreak cell
            // (unreachable from down here) and hold a futile dig until its own
            // timeout — starving the recovery block below that actually climbs.
            Walker.avatarForward(a, false);
            wk.avatarJump(a, false);
            p.setSprinting(false);
            wk.totalTicks = 0;                       // breaking/placing IS progress
            wk.stuckTicks = 0;                       // foot stays put while placing — don't trip the wiggle-jump (it'd leap off a 1-wide bridge)
            if (++wk.actionTicks > BotConfig.breakTimeoutTicks) {
                // Lag or an unexpected obstruction — drop the path and let
                // the next tick repath from the current position.
                a.breakHold(false);
                wk.lastError = "break/place stalled at " + wk.path.get(wk.step);
                wk.path = null;
                return Walker.Step.WALKING;
            }
            // Water-escape break (swimAshore / swimTraverseBreak): while breaking the
            // bank, a FLOATING bot drifts off its foot cell and the edge invalidates
            // before the block breaks — it bobs and never climbs out. We anchor by
            // pressing INTO the bank, but ONLY when the head is at the surface
            // (!isUnderWater). The earlier unconditional keyUp drowned the bot:
            // forward input while SUBMERGED drops it into the prone swim pose and it
            // sinks. Gating on surface means forward can't trigger swim-pose, so it
            // presses the body against the bank + jumps to mount, holding position
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
                    a.selectTool(b);
                    if (bailOnBreathInfeasibleDig(wk, a, p, b)) return Walker.Step.WALKING;
                    BotConfig.walkerDigActive = true;
                    // Claim, aim and drive all live inside avatarDig now: this site used to dig `b`
                    // first and claim it afterwards, so it drove a cell another phase already held.
                    Walker.avatarDig(wk, a, b);
                    boolean climbBreak = floatingPocket && b.getY() >= foot.getY();
                    if ((swimEscapeBreak && p.isInWater() && !p.isUnderWater()) || climbBreak) {
                        Walker.avatarForward(a, true);     // press into the aimed bank (surface only)
                        if (climbBreak || edge.move.startsWith("swimAshore"))
                            wk.avatarJump(a, true);   // rise to mount the +1 / out of the pocket
                    }
                    return Walker.Step.WALKING;
                }
            }
            a.breakHold(false);
            for (BlockPos b : edge.toPlace) {
                if (!world.isSolid(b)) {
                    // Descending-place LIP ANCHOR (task#4, replay-0013): the place
                    // cell is BELOW the foot — the body stands at a lip bridging
                    // DOWN. This actuator only zeroes the drive inputs; residual
                    // walk momentum still slides the body off the lip during the
                    // aim ticks (live: x 89.81→89.28 over 9 ticks, fall, 74×
                    // climb-back/repath wedge). Hold sneak while the support is
                    // pending: vanilla's ledge-guard arrests the slide AT the
                    // edge, the place lands, and the moment the cell is solid
                    // this branch stops firing — the normal (sneak-free) walk
                    // performs the planned step-down. Same-level places are
                    // byte-identical (cell not below the foot).
                    if (BotConfig.walkerBridgeDescentPlaceAnchor
                            && b.getY() < foot.getY() && p.onGround()) {
                        Walker.avatarSneak(a, true);
                        p.setShiftKeyDown(true);
                    }
                    if (BotConfig.walkerDebug)
                        LOG.info(
                                "[walker] place-act foot={},{},{} y={} step={} placing={},{},{} onGround={} edge={}",
                                foot.getX(), foot.getY(), foot.getZ(), String.format(Locale.ROOT, "%.2f", p.getY()),
                                wk.step, b.getX(), b.getY(), b.getZ(), p.onGround(), edge.move);
                    a.aimAtBlock(b);
                    a.place(world,b);
                    return Walker.Step.WALKING;
                }
            }
            return Walker.Step.WALKING;                  // settle a tick before walking on
        }
        a.breakHold(false);
        wk.actionTicks = 0;

        // Pillar placed but the player is still rising onto it — hold (no
        // horizontal walk) until grounded at the new level, so we don't walk
        // off the fresh block mid-jump.
        if (edge != null && "pillarUp".equals(edge.move)
                && !(p.onGround() && p.getY() >= wk.path.get(wk.step).getY() - 0.1)) {
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
    static boolean breathInfeasibleDig(net.minecraft.world.entity.player.Player p, BlockPos b) {
        if (!p.level().isClientSide()) return false;
        float dmg = p.level().getBlockState(b).getDestroyProgress(p, p.level(), b);
        if (dmg >= 1f) return false;                       // instant-mine — always fits
        if (dmg <= 0f) return true;                        // unbreakable from here
        // STANCE NORMALIZATION (2026-07-21 live, shoreline poison storm): the live
        // estimate bakes in THIS tick's stance — a bobbing/jumping body eats vanilla's
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
        return deep && Math.ceil(1f / dmg) > p.getMaxAirSupply() - BotConfig.drownEscapeAirThreshold - 20;
    }
}

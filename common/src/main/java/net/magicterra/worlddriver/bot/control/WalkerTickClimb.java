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
 * The per-tick walker trace and the current edge, for the regular walk once no controller has taken the
 * tick. The manoeuvres that used to live here are controllers now ({@link WaterClimb}, {@link Pillar},
 * {@link ParkourPlace}, {@link RouteDig}); what stays is shared by them: the breath gate on a dig, the
 * full-cube place boundary, the flooded-shaft answer Progress also asks.
 */
final class WalkerTickClimb {
    /**
     * Is this pillarUp destination a FLOODED shaft — one the bot floats straight up through —
     * rather than a water SURFACE it must place a support to leave?
     *
     * <p><b>The single answer both callers ask.</b> It used to be computed twice. The planned pillar
     * ({@link Pillar}) carved the surface case out of "flooded" (the {@code walkerPillarSurfacePlace}
     * branch, which is the flag's only reader in the whole product); {@link WalkerTickProgress} computed
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
    static boolean bailOnBreathInfeasibleDig(Walker wk, Body a, LivingEntity p, BlockPos b) {
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

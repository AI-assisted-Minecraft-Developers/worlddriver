package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.debug.BotLevelHolder;
import net.magicterra.agent.bot.movement.PathSmoothing.SmoothResult;
import net.magicterra.agent.bot.pathfinder.Capability;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.pathfinder.SearchProfile;
import net.magicterra.agent.bot.pathfinder.Constraint;
import net.magicterra.agent.bot.pathfinder.constraints.NoBreak;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.world.SurvivalMath;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.movement.PathSmoothing.*;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.agent.AgentDriverCommon.LOG;
import static net.magicterra.agent.bot.movement.WalkerConstants.*;
import static net.magicterra.agent.bot.movement.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 2348–3279): per-tick trace, water climb-out foothold (pillar takeover / bank dig), pillar-up actuator.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickClimb {
    private WalkerTickClimb() {}

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
                    Walker.agentJump(a, true);
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
                        Walker.agentForward(a, true);
                    } else {
                        Walker.agentForward(a, false);
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
            boolean wantClimbNow = edge != null
                    && ((cwp.getY() > foot.getY() && climbTargetBeside) || floatingBankRam);
            boolean touchingWater = p.isInWater() || world.isWater(foot) || world.isWater(foot.below());
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
            if (waterClimbing && wk.waterClimb.stall > WATER_CLIMB_STALL && !wk.waterClimb.pillarGaveUp
                    && (!deepDig || swimAshorePillarFallback)
                    && BotConfig.allowSwimEscapePlace && a.holdPlaceable()) {
                if (!wk.waterClimb.pillaring && BotConfig.walkerDebug)
                    LOG.info("[walker] water climb-out: pillar takeover engaged (bob-stalled) toward bank node {},{},{}",
                            cwp.getX(), cwp.getY(), cwp.getZ());
                wk.waterClimb.pillaring = true;
                // LOCK the column + heading at engage. The takeover pillars the bot's
                // own column STRAIGHT UP, pinned to this one bank, until it tops out of
                // the water onto dry ground. Following the live (repathing) node instead
                // made the bot wander between columns — chasing dive-to-floor and
                // other-column pillar nodes A* kept replanning — and lose the
                // wall-supported foothold (live round76c: engaged toward y145 pool
                // floor + x2438→2441 drift, never climbed out).
                wk.waterClimb.colX = foot.getX();
                wk.waterClimb.colZ = foot.getZ();
                double ex = (cwp.getX() + 0.5) - p.getX();
                double ez = (cwp.getZ() + 0.5) - p.getZ();
                wk.waterClimb.yaw = (ex * ex + ez * ez > 1e-4)
                        ? (float) Math.toDegrees(Math.atan2(-ex, ez)) : p.getYRot();
                // Safety ceiling: a sane bank is +1..+3; never pillar more than +5 above
                // the engage foot, then bail to the fallback actuators.
                wk.waterClimb.targetY = foot.getY() + 5;
            }
            // PILLAR-UP climb-out: place support blocks in the bot's OWN column up to the
            // bank stand level, so the final move onto the bank is a flush WALK — not a
            // fragile in-place +1 jump. A single surface foothold only lifts +1; a +2
            // bank then left an un-runnable +1 step (no running room, water behind) the
            // bot pogo-bobbed forever (live round69: jumped to bank height but z frozen,
            // never translated across). Reading-only — the pathfinder is unchanged.
            if (wk.waterClimb.pillaring) {
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
                        && !(wantClimbNow && cwp.getY() - foot.getY() >= 2);
                boolean tooHigh = foot.getY() > wk.waterClimb.targetY;
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
                boolean staleClimb = !wantClimb;
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
                    // cell and the foothold-place finally lands (the pre-3160836 behavior the
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
                    // fall through to the normal actuators / bank-dig
                } else {
                    // Pin to the LOCKED bank heading + column; look down to aim the place.
                    p.setYRot(wk.waterClimb.yaw); p.yHeadRot = wk.waterClimb.yaw; p.yBodyRot = wk.waterClimb.yaw;
                    p.setXRot(40f);
                    Walker.agentForward(a, true);
                    p.setSprinting(false);
                    Walker.agentJump(a, true);
                    // Fill the top water cell of the LOCKED column (floating) or the feet
                    // cell (grounded on the fresh rung) — not the live foot column, which
                    // drifts off the wall-supported pillar.
                    BlockPos colFoot = new BlockPos(wk.waterClimb.colX, foot.getY(), wk.waterClimb.colZ);
                    BlockPos fillCell = world.isWater(colFoot) ? colFoot : foot;
                    while (world.isWater(fillCell.above())) fillCell = fillCell.above();
                    boolean fcSolid = world.isSolid(fillCell);
                    boolean fcSupport = Move.hasPlaceSupport(world, fillCell);
                    boolean fcCleared = p.getY() >= fillCell.getY() + 0.9;
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] climbout-place col={},{} fill={} fcSolid={} support={} cleared={}(p.y={} need={}) dryG={} foot.y={} ceil={}",
                                wk.waterClimb.colX, wk.waterClimb.colZ, fillCell.getY(), fcSolid, fcSupport, fcCleared,
                                String.format("%.2f", p.getY()), fillCell.getY() + 0.9,
                                dryGrounded, foot.getY(), wk.waterClimb.targetY);
                    if (!fcSolid && fcSupport && fcCleared) {     // feet cleared the cell
                        a.place(world, fillCell);
                        wk.waterClimb.pillarNoPlaceTicks = 0;                    // made a place → progressing
                    } else {
                        wk.waterClimb.pillarNoPlaceTicks++;                      // bobbing, can't clear the cell
                    }
                    return Walker.Step.WALKING;
                }
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
                    && (!a.holdPlaceable() || wk.waterClimb.pillarGaveUp || deepDig)) {
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
                        if (ry <= surfY + 5 && world.isSolid(riserCand) && !overhang) { riser = riserCand; break; }
                    }
                    wk.waterClimb.digRiser = riser;
                    wk.waterClimb.digCommitTicks = 0;   // fresh riser → fresh per-block commit budget
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
                    a.aimAtBlock(riser);
                    wk.waterClimb.lastDigRiser = riser;
                    wk.waterClimb.lastDigAimEyeY = p.getEyeY();
                    a.breakHold(true);
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
                    Walker.agentJump(a, needRise);
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
                    if (!p.isUnderWater() && p.getY() >= riser.getY() - 0.6) Walker.agentForward(a, true);
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
                Walker.agentForward(a, true);
                if (p.horizontalCollision) wk.jumpTag = "riserHop";
                Walker.agentJump(a, p.horizontalCollision);   // hop only to clear a riser; flat-smooth otherwise
                return Walker.Step.WALKING;
            }
            Walker.agentForward(a, false);
            p.setSprinting(false);
            wk.totalTicks = 0;
            wk.stuckTicks = 0;   // pillaring stays on one cell while placing — not "stuck"
            if (wk.step != wk.pillar.step) { wk.pillar.step = wk.step; wk.pillar.sinceJump = -1; }
            if (++wk.actionTicks > BotConfig.breakTimeoutTicks) {
                a.breakHold(false);
                Walker.agentJump(a, false);
                wk.lastError = "pillar stalled at " + wk.path.get(wk.step);
                wk.path = null;
                return Walker.Step.WALKING;
            }
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    Walker.agentJump(a, false);
                    a.selectTool(b);
                    a.aimAtBlock(b);
                    a.breakHold(true);
                    if (BotConfig.walkerStickyDig || BotConfig.walkerDigAimPriority) { wk.stickyDig.pos = b; wk.stickyDig.ticks = 0; }
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
            boolean shaftFlooded = world.isWater(wk.path.get(wk.step));
            // Water-SURFACE shaft (walkerPillarSurfacePlace): the destination cell is
            // water but the cell ABOVE it is air — this is the water-bank climb-out,
            // not a flooded chimney. Floating higher is physically impossible (rig
            // 371.5,62,348.5: jump+buoyancy bob ceiling 63.08 vs the 63.9 the flooded-
            // shaft float path would need), so case (a) float-through starves forever
            // while the ONLY ticks that could truly place (bob crest ≥ fill.y+1.0,
            // 63.0-63.08 ≈ 1-2 ticks/bob) are spent in this branch NOT placing — and
            // the lower-gated climbout-place takeover (0.9 threshold) clicks only in
            // the 62.9-63.0 band where vanilla silently rejects the still-overlapping
            // AABB. The two place paths miss each other's windows = the deterministic
            // water-bank pillarUp deadlock. Treat the surface cell as case (b): jump
            // and crest-place the support.
            if (shaftFlooded && BotConfig.walkerPillarSurfacePlace
                    && !world.isWater(wk.path.get(wk.step).above())) {
                shaftFlooded = false;
            }
            if (shaftFlooded || p.isInWater() || world.isWater(wk.path.get(wk.step).offset(0, -1, 0))) {
                Walker.agentJump(a, true);
                // gap#81: routine pillar/scaffold filler must not spend gathered wood.
                if (!shaftFlooded && a.holdThrowawayPlaceable()) {
                    BlockPos wp = edge.toPlace.get(0);
                    p.setXRot(89.5f);                       // look down to aim the support
                    if (p.getY() >= wp.getY() + 0.9) {      // bobbed clear of the place cell
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
                Walker.agentJump(a, true);
                wk.pillar.sinceJump = 0;
            } else {
                Walker.agentJump(a, false);
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
            Walker.agentForward(a, true);
            Walker.agentJump(a, !placed && grounded);   // jump off the lip once
            boolean sprint = !placed;                          // brake after the block is down
            p.setSprinting(sprint);
            Walker.agentSneak(a, placed);               // sneak-brake / ledge-guard on landing
            p.setShiftKeyDown(placed);
            if (!placed && !grounded && a.holdPlaceable()) {
                Vec3 eye = p.getEyePosition();
                double fdx = (floor.getX() + 0.5) - eye.x, fdy = (floor.getY() + 0.5) - eye.y, fdz = (floor.getZ() + 0.5) - eye.z;
                boolean inReach = fdx * fdx + fdy * fdy + fdz * fdz < 16;   // ~4 blocks of the eye
                if (inReach) {
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
            Walker.agentForward(a, false);
            Walker.agentJump(a, false);
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
                    a.aimAtBlock(b);
                    a.breakHold(true);
                    if (BotConfig.walkerStickyDig || BotConfig.walkerDigAimPriority) { wk.stickyDig.pos = b; wk.stickyDig.ticks = 0; }
                    boolean climbBreak = floatingPocket && b.getY() >= foot.getY();
                    if ((swimEscapeBreak && p.isInWater() && !p.isUnderWater()) || climbBreak) {
                        Walker.agentForward(a, true);     // press into the aimed bank (surface only)
                        if (climbBreak || edge.move.startsWith("swimAshore"))
                            Walker.agentJump(a, true);   // rise to mount the +1 / out of the pocket
                    }
                    return Walker.Step.WALKING;
                }
            }
            a.breakHold(false);
            for (BlockPos b : edge.toPlace) {
                if (!world.isSolid(b)) {
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
            Walker.agentForward(a, false);
            Walker.agentJump(a, false);
            p.setSprinting(false);
            return Walker.Step.WALKING;
        }
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        cx.edges.edge = edge;
        return null;
    }
}

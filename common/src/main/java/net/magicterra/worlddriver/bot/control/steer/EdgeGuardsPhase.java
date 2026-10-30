package net.magicterra.worlddriver.bot.control.steer;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.*;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.*;
import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.*;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;

import java.util.Locale;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Mechanical extraction of {@code Navigator#tickInner} (pre-split lines 3280–3504): the current edge, its context and
 * the MLG step-off, for the regular walk once no controller has taken the tick; and the per-tick navigator trace.
 * Climbing a vine moved to {@link VineClimb}. One phase of the per-tick pipeline — see {@link Navigator#tickInner} for
 * the driver and {@link TickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the original tick body
 * except for the {@code w.}/{@code Navigator.} member prefixes; do not restructure here without live/testkit evidence
 * (this file is state-machine surgery).
 */
public final class EdgeGuardsPhase {
    public int dbgPrevStep = -1; // navigatorDebug: detect step changes for per-step timing
    public int dbgTicksOnStep = 0; // navigatorDebug: ticks spent on the current step

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    public TickStep run(Navigator nav, TickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (TickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        // ---- original body (byte-identical modulo member prefixes) ----

        Move.Edge edge = nav.route.edgeAt(nav.route.step());
        if (GameConfig.navigatorDebug) trace(nav, p, world, edge);
        BlockPos wp = nav.route.path().get(nav.route.step());
        Move.Edge nextEdge = nav.route.edgeAt(nav.route.step() + 1);
        boolean parkourEdge = edge != null && edge.move != null && edge.move.startsWith("parkour");
        if (parkourEdge) nav.safety.guardParkourTick = true;
        // Bridging-over-a-gap detection (place-bridge on the current OR next edge),
        // hoisted up here so the travel-aim pitch (below) can keep the camera trained on
        // the bridge frontier instead of being yanked back to the horizon between place
        // ticks. The sneak/sprint gating that also reads this lives further down.
        boolean bridging = (edge != null && "bridgePlace".equals(edge.move))
                || (nextEdge != null && "bridgePlace".equals(nextEdge.move));
        // ANY block-placing edge (pillarUp / parkourPlace / stair scaffolds), current or
        // next: the place-tick aimAtBlockSnap pitches the camera down exactly like a
        // bridge place does, so the pitch-hold below must cover these too — a steep
        // dirt-ladder ascent showed the same down-snap ↔ horizon-pull sawtooth the
        // bridge fix (#30) solved, just via pillarUp edges instead of bridgePlace.
        boolean placingEdge =
                (edge != null && !edge.toPlace.isEmpty()) || (nextEdge != null && !nextEdge.toPlace.isEmpty());
        // Stepping off into an MLG fall: walk off at WALK speed (no sprint) so
        // the bot drops near-vertically and lands in the water we place under
        // it — a sprint launch carries horizontal momentum that drifts the bot
        // off the placed source and it clips the edge (partial fall damage).
        // Do NOT sneak though: sneak is ledge-protection and would stop the bot
        // walking off the edge at all. Triggered when the edge we're walking
        // onto OR the next one is an MLG fall.
        boolean steppingOffFall = (edge != null && edge.move != null && edge.move.startsWith("fallBucket"))
                || (nextEdge != null && nextEdge.move != null && nextEdge.move.startsWith("fallBucket"));
        // A fall into EXISTING water (Move.FallIntoWater, "fallWater*") wants
        // the same near-vertical step-off — no sprint — so the longer airtime
        // of a tall drop doesn't carry the bot horizontally past the 1-wide
        // water column and onto dry land beyond. But there's no source to
        // place or scoop here, so it must NOT arm the mlg latch (that latch
        // drives water placement and would strand an empty hand mid-fall).
        boolean steppingOffWaterFall = (edge != null && edge.move != null && edge.move.startsWith("fallWater"))
                || (nextEdge != null && nextEdge.move != null && nextEdge.move.startsWith("fallWater"));
        // Commit to the MLG fall while still on the launch lip: the latch at
        // the top of tick() then owns the entire descent (placing/scooping the
        // water) independent of how A* relabels the edge as we near the ground.
        if (steppingOffFall) {
            // Commit to the MLG fall while still on the launch lip, recording
            // the planned landing column so the airborne clutch can damp drift
            // back toward it: a walk-off's residual horizontal momentum (zero
            // air friction) otherwise carries the bot past a 1-wide landing
            // before it descends, and the water gets placed on whatever happens
            // to be below the drifted-into column instead.
            BlockPos land = (edge != null && edge.move != null && edge.move.startsWith("fallBucket"))
                    ? wp
                    : nav.route.path().get(nav.route.step() + 1);
            if (land != null) world.signals().planClutch(land.getX(), land.getZ());
        }
        // ---- publish: write this phase's products for the downstream phases (TickCtx) ----
        cx.edges.edge = edge;
        cx.edges.wp = wp;
        cx.edges.parkourEdge = parkourEdge;
        cx.edges.bridging = bridging;
        cx.edges.placingEdge = placingEdge;
        cx.edges.steppingOffFall = steppingOffFall;
        cx.edges.steppingOffWaterFall = steppingOffWaterFall;
        return null;
    }

    /**
     * The per-tick navigator trace, the single source of truth for "why is the bot stuck": for the current step the
     * exact advance-decision inputs (horizontal dist² against the REACH_DIST_SQ gate, |Δy| against the 1.2 gate that
     * together decide `within`), how many ticks on this step, the move and its break/place needs, and the live bot
     * state. Read it top to bottom to see which condition fails tick after tick.
     */
    private void trace(Navigator nav, LivingEntity p, WorldView world, Move.Edge edge) {
        if (nav.route.step() != dbgPrevStep) {
            dbgPrevStep = nav.route.step();
            dbgTicksOnStep = 0;
        }
        dbgTicksOnStep++;
        BlockPos nd = nav.route.path().get(nav.route.step());
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
        LOG.info(
                "[navigator] t={} step={}/{} move={} node={},{},{} p=({},{},{}) pitch={} yaw={} bear={} yawErr={} hCol={} lastAim={} cur2={} (gate {}) |dY|={} (gate 1.2) within={} onG={} inW={} undW={} stuck={} totStuck={} pend={} break0={}{}",
                dbgTicksOnStep,
                nav.route.step(),
                nav.route.path().size(),
                edge != null ? edge.move : "-",
                nd.getX(),
                nd.getY(),
                nd.getZ(),
                String.format(Locale.ROOT, "%.2f", p.getX()),
                String.format(Locale.ROOT, "%.2f", p.getY()),
                String.format(Locale.ROOT, "%.2f", p.getZ()),
                String.format(Locale.ROOT, "%.0f", p.getXRot()),
                String.format(Locale.ROOT, "%.0f", p.getYRot()),
                String.format(Locale.ROOT, "%.0f", bearing),
                String.format(Locale.ROOT, "%.0f", yawErr),
                p.horizontalCollision,
                String.format(Locale.ROOT, "%.0f", nav.aim.aimSmooth.lastAimYaw),
                String.format(Locale.ROOT, "%.3f", cur2),
                REACH_DIST_SQ,
                String.format(Locale.ROOT, "%.2f", Math.abs(dY)),
                within,
                p.onGround(),
                p.isInWater(),
                p.isUnderWater(),
                nav.stuckTicks,
                nav.totalTicks,
                edge != null && hasPendingEdge(world, edge),
                br0,
                br0 != null ? (world.isSolid(br0) ? " (solid)" : " (clear)") : "");
    }
}

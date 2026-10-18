package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.control.PathSmoothing.*;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.bot.control.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.control.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 3280–3504): current edge context and the MLG step-off.
 * Climbing a vine moved to {@link VineClimb}.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickEdgeGuards {
    private WalkerTickEdgeGuards() {}

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        Move.Edge edge = cx.edges.edge;
        // ---- original body (byte-identical modulo member prefixes) ----

        BlockPos wp = wk.route.path().get(wk.route.step());
        Move.Edge nextEdge = wk.edgeAt(wk.route.step() + 1);
        boolean parkourEdge = edge != null && edge.move != null && edge.move.startsWith("parkour");
        if (parkourEdge) wk.safety.guardParkourTick = true;
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
        boolean placingEdge = (edge != null && !edge.toPlace.isEmpty())
                || (nextEdge != null && !nextEdge.toPlace.isEmpty());
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
                    ? wp : wk.route.path().get(wk.route.step() + 1);
            if (land != null) world.signals().planClutch(land.getX(), land.getZ());
        }
        // ---- publish: write this phase's products for the downstream phases (WalkerTickCtx) ----
        cx.edges.wp = wp;
        cx.edges.parkourEdge = parkourEdge;
        cx.edges.bridging = bridging;
        cx.edges.placingEdge = placingEdge;
        cx.edges.steppingOffFall = steppingOffFall;
        cx.edges.steppingOffWaterFall = steppingOffWaterFall;
        return null;
    }
}

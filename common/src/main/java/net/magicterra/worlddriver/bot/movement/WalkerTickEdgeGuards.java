package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.movement.PathSmoothing.*;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.movement.WalkerGeometry.*;


/**
 * Mechanical extraction of {@code Walker#tickInner} (pre-split lines 3280–3504): current edge context: parkour-place, break/place actuator, vine traversal, MLG step-off.
 * One phase of the per-tick pipeline — see {@link Walker#tickInner} for the driver and
 * {@link WalkerTickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Walker.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
final class WalkerTickEdgeGuards {
    private WalkerTickEdgeGuards() {}

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    static Walker.Step run(Walker wk, WalkerTickCtx cx, Avatar a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (WalkerTickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        Move.Edge edge = cx.edges.edge;
        // ---- original body (byte-identical modulo member prefixes) ----

        BlockPos wp = wk.path.get(wk.step);
        Move.Edge nextEdge = wk.edgeAt(wk.step + 1);
        boolean parkourEdge = edge != null && edge.move != null && edge.move.startsWith("parkour");
        if (parkourEdge) wk.guardParkourTick = true;
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
        // VINE traversal (user insight 2026-06-06): vines are CLIMBABLE, so when the
        // walker fights a stale waypoint while the body is clinging to a vine it wedges
        // for seconds (the jungle cling-freeze: suspended off-ground, hCol, hSpd≈0, while
        // the waypoint sat 3 blocks BELOW). Don't fight it — a vine is a fixed climb
        // sub-path. Once the body is suspended ON a vine (!onGround), take over: face the
        // path-ahead bearing and, if the path ahead is up/level ("direction correct"),
        // CLIMB the vine (forward+jump); the offPath(>3) / stuck repath then re-routes
        // from the new elevation ("修正路径"). If the path ahead is BELOW (we over-climbed),
        // release the climb keys so the body slides back down the vine to be re-planned.
        // Gated on !onGround so it never hijacks normal ground-walking through a vine-
        // draped cell; ladders are excluded by isClimbable's caller using vines in jungle.
        // fix Q (re-applied 2026-06-25, now A/B-validatable via the DETERMINISTIC vineOverWaterClimbArena
        // — the journey replay's replan-variance, which forced the earlier revert, no longer blocks it):
        // parkour→vine HANDOFF. A parkourAscend that LANDS on a vine still has parkourEdge=true at the apex,
        // so the plain !parkourEdge gate leaves nothing driving the cling at the one instant it matters →
        // the bot fails to grab → drops into the y62 pocket (foot becomes water, isClimbable=false → handler
        // can't re-engage → bank-DIG churn, -711,67). Permit the handler on a parkour edge when the bot has
        // LANDED on the climbable column: foot=vine AND arrested horizontally over the landing node wp (XZ
        // within OVERSHOOT_RESYNC_SQ — the live landing arrests at cur2~1.24, too far for REACH_DIST_SQ) AND
        // airborne. CANNOT fire mid-leap (over a parkour GAP foot=air, isClimbable=false). !p.isInWater()
        // stays (the apex is BEFORE the drop); if the grab still misses, control falls through to the
        // unchanged water actuator. Strict ADD: inert when landedOnVine=false → identical to the old gate.
        double vineDx = (wp.getX() + 0.5) - p.getX();
        double vineDz = (wp.getZ() + 0.5) - p.getZ();
        boolean landedOnVine = BotConfig.walkerVineLandGrab
                && world.isClimbable(foot) && !p.onGround()
                && (vineDx * vineDx + vineDz * vineDz) < OVERSHOOT_RESYNC_SQ;
        boolean onVine = (!parkourEdge || landedOnVine)
                && !p.isInWater() && !p.onGround() && world.isClimbable(foot);
        // VINEDIAG (behind walkerDebug): capture the parkour→vine landing decision at the apex. Fires
        // while airborne OR in-water (the bug window), whether or not onVine engages — so an A/B can see
        // if the foot EVER becomes the climbable vine while parkourEdge is still true (the landedOnVine
        // case) or if the arc skips the vine straight to the water pocket (foot air→water, never climbable).
        if (BotConfig.walkerDebug && (!p.onGround() || p.isInWater())) {
            BlockPos footBelow = foot.below();
            LOG.info("[VINEDIAG] py={} foot={},{},{} climb(foot)={} climb(below)={} parkourEdge={} inWater={} onGround={} landedOnVine={} onVine={} cur2={} wp={},{},{}",
                    String.format(Locale.ROOT, "%.2f", p.getY()),
                    foot.getX(), foot.getY(), foot.getZ(),
                    world.isClimbable(foot), world.isClimbable(footBelow), parkourEdge,
                    p.isInWater(), p.onGround(), landedOnVine, onVine,
                    String.format(Locale.ROOT, "%.2f", vineDx * vineDx + vineDz * vineDz),
                    wp.getX(), wp.getY(), wp.getZ());
        }
        if (onVine) {
            BlockPos ahead = wk.path.get(Math.min(wk.step + 2, wk.path.size() - 1));
            // Vanilla vine ASCENT (LivingEntity.travel) only fires while
            // (horizontalCollision || jumping) && onClimbable, and SUSTAINS only while the
            // body keeps colliding with a wall — proven by vineClingFidelityProbe (the avatar
            // climbs the full 5-block column when it presses FORWARD into the backing wall).
            // So aim the heading at the vine's SOLID BACKING WALL (forward = into the wall =
            // sustained horizontalCollision), NOT at the overhead path node. The old aim used
            // path.get(step+2): once that node's XZ drifted off the column the atan2 bearing
            // swung the yaw away from the wall (live trace 0°→-90°→-206°), the forward press
            // stopped ramming the wall, the climb stalled, and the bot bobbed back down into
            // the water pocket. Bias the wall pick toward the path-ahead direction so a corner
            // (two walls) still yields a stable heading; if no neighbour is solid (free climb),
            // fall back to the path-ahead bearing.
            double vdx = (ahead.getX() + 0.5) - p.getX();
            double vdz = (ahead.getZ() + 0.5) - p.getZ();
            Float wallYaw = vineWallYaw(world, foot, vdx, vdz);
            // NOTE (fix Q): a broad slide-guard-over-water (force climbUp when water-below) was tried
            // here and REMOVED — it misfired on LEGIT vine descents over water (live -702,489: path
            // intentionally goes DOWN to y61, ahead.y < foot.y, but the guard forced a climb UP →
            // over-climb → detour stall at z485, never reaching -711). The slide-loop fix-P saw was a
            // CONSEQUENCE of the fall into the pocket; Part 2's parkour→vine handoff prevents the fall,
            // so the bot climbs the clean stepUp chain (path up → climbUp naturally true) and never
            // oscillates. Plain over-climb semantics restored; a narrower pocket-only guard can be
            // added later if a real slide-into-pocket is observed WITH the handoff in place.
            boolean climbUp = ahead.getY() >= foot.getY();   // path ahead up/level → climb; below → over-climbed, descend
            // DESCEND-OFF-THE-CURTAIN gate (walkerVineDescentDrop, default ON). When the path skims a
            // bank/inlet at one Y under a HANGING vine curtain, `ahead` (step+2) sits at the bob floor and
            // `climbUp` OSCILLATES with the y-bob → the bot is pinned ON the vine, jumping up / sliding down
            // with ZERO XZ progress toward the actual node (live -672,64,311 inlet bob ~10 s). If the
            // IMMEDIATE committed node `wp` is at/below the foot it is NOT a climb (a genuine ascent always
            // has wp ABOVE the foot — the free-hang -711 curtain + vineOverWaterClimbArena both ascend, so
            // wp.y > foot.y there, untouched). Force the slide-down so the body drops off the vine onto the
            // bank / into the inlet and the normal walk/stepDown resumes (it grounds → the !onGround gate
            // ends the cling). Strict ADD: inert when OFF or when wp.y > foot.y (a real climb).
            if (BotConfig.walkerVineDescentDrop && wp.getY() <= foot.getY()) climbUp = false;
            // FREE-HANGING vine sustain (walkerVineFreeHangClimb). A wall-less vine (wallYaw==null:
            // no solid horizontal neighbour of the foot column) cannot be climbed by ramming a wall —
            // vanilla's (horizontalCollision||jumping) ascent has no wall to keep horizontalCollision
            // live, and the path-ahead forward press WALKS the buoy-free body horizontally OUT of the
            // vine and it runs out of climbable / DROPS into the pocket (live -711: 65.02→65.20→detach→
            // 61.9 inW, totStuck 2400+). Sustain it by (a) holding JUMP every tick (jumping → vy=+0.2,
            // the only wall-less ascent drive) and (b) driving the body via the CAMERA-DECOUPLED impulse
            // straight at the immediate climb target wp — the live -711 vine is a WIDE curtain whose top
            // reaches the dismount ONLY on the exit side, so the body must keep advancing UP-AND-TOWARD
            // the exit (a deadzone that goes pure-vertical when "aligned" stalls the exit advance and the
            // bot wedges — live-verified). commandMove tracks wp exactly while the camera slews, so the
            // off-axis drift is corrected without the camera lag mis-aiming a raw forward press. Wall-
            // backed cells (wallYaw!=null) hand back to the wall-press path to top out + step off.
            boolean freeHang = BotConfig.walkerVineFreeHangClimb && wallYaw == null && climbUp;
            float vYaw;
            boolean pressForward = false;           // for the wall-backed / legacy path below
            boolean freeHangDrive = false;          // true → horizontal driven by commandMove (decoupled)
            float fhBearing = 0f;                   // radians; world bearing to the climb target
            if (freeHang) {
                // Steer toward the immediate next node `wp` (this is the LIVE-validated target: the body
                // climbs the curtain up-and-across toward each successive node). `wp` can flip ±180° when
                // the pure-pursuit `step` advances under the foot, so the heading is SLEW-LIMITED below to
                // keep that from circling the body off a narrow column.
                double tx = (wp.getX() + 0.5) - p.getX();
                double tz = (wp.getZ() + 0.5) - p.getZ();
                if (tx * tx + tz * tz < 0.04) {
                    // wp is DIRECTLY above (within 0.2 block): atan2 of a ~zero vector is pure noise.
                    // Climb PURE-VERTICAL — hold the camera, no horizontal drive, just the jump.
                    vYaw = p.getYRot();
                    freeHangDrive = false;
                    wk.freeHangDriveYaw = Float.NaN;            // resync the slew when a real bearing returns
                } else {
                    // SLEW-LIMIT the drive heading: turn a persistent heading at most ~30°/tick toward the
                    // bearing to wp. When `step` advances and wp flips, the heading averages the flip into
                    // a smooth arc rather than walking the body in CIRCLES off the column (the arena's
                    // narrow-column flake). On the live -711 curtain the bearing is steady (the climb tracks
                    // the exit) so the slewed value just hugs it — the proven up-and-across path is intact.
                    float target = (float) Math.toDegrees(Math.atan2(-tx, tz));
                    if (Float.isNaN(wk.freeHangDriveYaw)) {
                        wk.freeHangDriveYaw = target;            // snap on the first tick of the climb
                    } else {
                        float dyaw = angleDiff(wk.freeHangDriveYaw, target);   // shortest turn toward target
                        if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                        wk.freeHangDriveYaw += dyaw;
                    }
                    freeHangDrive = true;
                    fhBearing = (float) Math.toRadians(wk.freeHangDriveYaw);   // drive along the slewed heading
                    vYaw = wk.freeHangDriveYaw;                  // camera follows the same slewed heading
                }
            } else if (wallYaw != null) {
                vYaw = wallYaw;
                pressForward = climbUp;
            } else if (vdx * vdx + vdz * vdz < YAW_DEADZONE_SQ) {
                vYaw = p.getYRot();
                pressForward = climbUp;
            } else {
                vYaw = (float) Math.toDegrees(Math.atan2(-vdx, vdz));
                pressForward = climbUp;
            }
            if (Math.abs(angleDiff(p.getYRot(), vYaw)) > BotConfig.walkerYawHysteresisDeg) {
                float want = smoothAngle(p.getYRot(), vYaw);
                float dyaw = angleDiff(p.getYRot(), want);
                if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                float ny = p.getYRot() + dyaw;
                p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny;
            }
            p.setXRot(smoothAngle(p.getXRot(), 0f));
            // Horizontal drive: free-hang uses the camera-DECOUPLED impulse toward the climb target
            // (exact bearing regardless of the slewing camera); otherwise the raw forward press (into
            // the wall / along the camera). A modest forward magnitude keeps the body advancing toward
            // the exit column without overshooting off the curtain (the climbable clamp caps it ≤0.15).
            if (freeHangDrive) {
                // Drive the body toward wp via the impulse channel (vanilla travel() rotates it by the
                // camera yaw). Empirically LIVE-tuned: the world-bearing impulse + the camera slewing
                // toward fhBearing converges the body onto a steady up-and-toward-the-exit climb across
                // the wide -711 curtain (in-pocket→0, tops out, advances). A "fully decoupled" Δ-form was
                // tried and REGRESSED live (it wedged the bot in the pocket — the wide-curtain climb needs
                // THIS trajectory, not a geometrically-pure one), so do NOT change it without a live A/B.
                a.commandMove((float) (-Math.sin(fhBearing) * FREE_HANG_DRIVE),
                              (float) (Math.cos(fhBearing) * FREE_HANG_DRIVE));
            } else {
                Walker.avatarForward(a, pressForward);      // forward INTO the wall (wall-backed climb)
            }
            // On a free-hanging climb hold JUMP every tick (jumping sustains the wall-less vy=+0.2);
            // otherwise jump tracks climbUp (off → slide back down a wall-backed vine to be re-planned).
            if (freeHang || climbUp) wk.jumpTag = "vineClimb";
            wk.avatarJump(a, freeHang || climbUp);      // continuous jump on a wall-less climb
            Walker.avatarSneak(a, false);              // sneak would HALT the vine climb
            p.setShiftKeyDown(false);
            p.setSprinting(false);
            if (BotConfig.walkerDebug)
                LOG.info("[walker] vine-climb foot={},{},{} ahead={},{},{} climbUp={} freeHang={} fhDrive={} yaw={} py={}",
                        foot.getX(), foot.getY(), foot.getZ(),
                        ahead.getX(), ahead.getY(), ahead.getZ(), climbUp, freeHang, freeHangDrive,
                        String.format(Locale.ROOT, "%.0f", p.getYRot()),
                        String.format(Locale.ROOT, "%.2f", p.getY()));
            return Walker.Step.WALKING;
        }
        // Stepping off into an MLG fall: walk off at WALK speed (no sprint) so
        // the body drops near-vertically and lands in the water we place under
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
            // air friction) otherwise carries the body past a 1-wide landing
            // before it descends, and the water gets placed on whatever happens
            // to be below the drifted-into column instead.
            BlockPos land = (edge != null && edge.move != null && edge.move.startsWith("fallBucket"))
                    ? wp : wk.path.get(wk.step + 1);
            if (land != null) CLUTCH.armPlanned(land.getX(), land.getZ());
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

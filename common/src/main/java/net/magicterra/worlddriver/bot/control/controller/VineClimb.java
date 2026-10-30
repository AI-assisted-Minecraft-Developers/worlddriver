package net.magicterra.worlddriver.bot.control.controller;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.FREE_HANG_DRIVE;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.MAX_YAW_SLEW_DEG;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.OVERSHOOT_RESYNC_SQ;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.YAW_DEADZONE_SQ;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.angleDiff;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.vineWallYaw;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.body.BotUtil.smoothAngle;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.control.act.Keys;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Climbing a vine the bot is hanging on (user insight 2026-06-06). Vines are CLIMBABLE, so when the navigator fights a
 * stale waypoint while the bot clings to one it wedges for seconds (the jungle cling-freeze: suspended off-ground,
 * hCol, hSpd≈0, while the waypoint sat 3 blocks BELOW). A vine is a fixed climb sub-path: once the bot is suspended ON
 * a vine (!onGround), face the path-ahead bearing and, if the path ahead is up or level, CLIMB (forward + jump); the
 * offPath / stuck repath then re-routes from the new elevation. If the path ahead is BELOW (over-climbed), release the
 * climb keys so the bot slides back down to be re-planned. Never on the ground, so it doesn't hijack ordinary walking
 * through a vine-draped cell.
 *
 * <p>Hands are not claimed: climbing holds nothing.
 */
public final class VineClimb implements Controller {
    // ±180° flips that would circle the bot off a narrow column — self-resyncing (no journey reset)

    private static final Set<Channel> CHANNELS = EnumSet.of(Channel.MOVE, Channel.LOOK, Channel.JUMP);

    private float freeHangDriveYaw =
            Float.NaN; // slew-limited world heading of the free-hang vine DRIVE (NaN = resync); smooths the step-jitter

    private boolean onVine;

    private boolean parkourEdge;

    @Override
    public String name() {
        return "vineClimb";
    }

    @Override
    public Set<Channel> channels() {
        return CHANNELS;
    }

    @Override
    public int priority() {
        return 6;
    }

    /** As long as the bot hangs on the vine. */
    @Override
    public int maxTicks() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void sense(Navigator nav, TickCtx cx, WorldView world) {
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        Move.Edge edge = nav.route.edgeAt(nav.route.step());
        BlockPos wp = nav.route.path().get(nav.route.step());
        parkourEdge = edge != null && edge.move != null && edge.move.startsWith("parkour");
        // parkour→vine HANDOFF (fix Q, A/B-validatable via the DETERMINISTIC vineOverWaterClimbArena). A
        // parkourAscend that LANDS on a vine still has parkourEdge=true at the apex, so a plain !parkourEdge
        // gate leaves nothing driving the cling at the one instant it matters → the bot fails to grab →
        // drops into the y62 pocket (foot becomes water, isClimbable=false → can't re-engage → bank-DIG
        // churn, -711,67). Permit the climb on a parkour edge when the bot has LANDED on the climbable
        // column: foot=vine AND arrested horizontally over the landing node wp (XZ within
        // OVERSHOOT_RESYNC_SQ — the live landing arrests at cur2~1.24, too far for REACH_DIST_SQ) AND
        // airborne. CANNOT fire mid-leap (over a parkour GAP foot=air, isClimbable=false). !p.isInWater()
        // stays (the apex is BEFORE the drop); if the grab still misses, the water climb-out takes over.
        double vineDx = wp.getX() + 0.5 - p.getX();
        double vineDz = wp.getZ() + 0.5 - p.getZ();
        boolean landedOnVine = ControlConfig.navigatorVineLandGrab
                && world.isClimbable(foot)
                && !p.onGround()
                && (vineDx * vineDx + vineDz * vineDz) < OVERSHOOT_RESYNC_SQ;
        onVine = (!parkourEdge || landedOnVine) && !p.isInWater() && !p.onGround() && world.isClimbable(foot);
        // VINEDIAG (behind navigatorDebug): capture the parkour→vine landing decision at the apex. Fires
        // while airborne OR in-water (the bug window), whether or not the climb engages — so an A/B can
        // see if the foot EVER becomes the climbable vine while parkourEdge is still true (the
        // landedOnVine case) or if the arc skips the vine straight to the water pocket.
        if (GameConfig.navigatorDebug && (!p.onGround() || p.isInWater())) {
            BlockPos footBelow = foot.below();
            LOG.info(
                    "[VINEDIAG] py={} foot={},{},{} climb(foot)={} climb(below)={} parkourEdge={} inWater={} onGround={} landedOnVine={} onVine={} cur2={} wp={},{},{}",
                    String.format(Locale.ROOT, "%.2f", p.getY()),
                    foot.getX(),
                    foot.getY(),
                    foot.getZ(),
                    world.isClimbable(foot),
                    world.isClimbable(footBelow),
                    parkourEdge,
                    p.isInWater(),
                    p.onGround(),
                    landedOnVine,
                    onVine,
                    String.format(Locale.ROOT, "%.2f", vineDx * vineDx + vineDz * vineDz),
                    wp.getX(),
                    wp.getY(),
                    wp.getZ());
        }
    }

    @Override
    public String whyNot(Navigator nav, TickCtx cx, WorldView world) {
        return onVine ? null : "not hanging on a vine";
    }

    @Override
    public boolean canContinue(Navigator nav, TickCtx cx, WorldView world) {
        return onVine;
    }

    @Override
    // The vine tick is one ordered decision chain (free hang, wall-backed, legacy) that shares its locals.
    @SuppressWarnings("checkstyle:javancss")
    public boolean tick(Navigator nav, TickCtx cx, Body a, WorldView world) {
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        BlockPos wp = nav.route.path().get(nav.route.step());
        if (parkourEdge) {
            nav.safety.guardParkourTick = true;
        }
        BlockPos ahead = nav.route
                .path()
                .get(Math.min(nav.route.step() + 2, nav.route.path().size() - 1));
        // Vanilla vine ASCENT (LivingEntity.travel) only fires while
        // (horizontalCollision || jumping) && onClimbable, and SUSTAINS only while the
        // player keeps colliding with a wall — proven by vineClingFidelityProbe (the avatar
        // climbs the full 5-block column when it presses FORWARD into the backing wall).
        // So aim the heading at the vine's SOLID BACKING WALL (forward = into the wall =
        // sustained horizontalCollision), NOT at the overhead path node. The old aim used
        // path.get(step+2): once that node's XZ drifted off the column the atan2 bearing
        // swung the yaw away from the wall (live trace 0°→-90°→-206°), the forward press
        // stopped ramming the wall, the climb stalled, and the bot bobbed back down into
        // the water pocket. Bias the wall pick toward the path-ahead direction so a corner
        // (two walls) still yields a stable heading; if no neighbour is solid (free climb),
        // fall back to the path-ahead bearing.
        double vdx = ahead.getX() + 0.5 - p.getX();
        double vdz = ahead.getZ() + 0.5 - p.getZ();
        Float wallYaw = vineWallYaw(world, foot, vdx, vdz);
        // NOTE (fix Q): a broad slide-guard-over-water (force climbUp when water-below) was tried
        // here and REMOVED — it misfired on LEGIT vine descents over water (live -702,489: path
        // intentionally goes DOWN to y61, ahead.y < foot.y, but the guard forced a climb UP →
        // over-climb → detour stall at z485, never reaching -711). The slide-loop fix-P saw was a
        // CONSEQUENCE of the fall into the pocket; the parkour→vine handoff prevents the fall,
        // so the bot climbs the clean stepUp chain (path up → climbUp naturally true) and never
        // oscillates. Plain over-climb semantics restored; a narrower pocket-only guard can be
        // added later if a real slide-into-pocket is observed WITH the handoff in place.
        boolean climbUp = ahead.getY() >= foot.getY(); // path ahead up/level → climb; below → over-climbed, descend
        // DESCEND-OFF-THE-CURTAIN gate (navigatorVineDescentDrop, default ON). When the path skims a
        // bank/inlet at one Y under a HANGING vine curtain, `ahead` (step+2) sits at the bob floor and
        // `climbUp` OSCILLATES with the y-bob → the bot is pinned ON the vine, jumping up / sliding down
        // with ZERO XZ progress toward the actual node (live -672,64,311 inlet bob ~10 s). If the
        // IMMEDIATE committed node `wp` is at/below the foot it is NOT a climb (a genuine ascent always
        // has wp ABOVE the foot — the free-hang -711 curtain + vineOverWaterClimbArena both ascend, so
        // wp.y > foot.y there, untouched). Force the slide-down so the bot drops off the vine onto the
        // bank / into the inlet and the normal walk/stepDown resumes (it grounds → the !onGround gate
        // ends the cling). Strict ADD: inert when OFF or when wp.y > foot.y (a real climb).
        if (ControlConfig.navigatorVineDescentDrop && wp.getY() <= foot.getY()) {
            climbUp = false;
        }
        // FREE-HANGING vine sustain (navigatorVineFreeHangClimb). A wall-less vine (wallYaw==null:
        // no solid horizontal neighbour of the foot column) cannot be climbed by ramming a wall —
        // vanilla's (horizontalCollision||jumping) ascent has no wall to keep horizontalCollision
        // live, and the path-ahead forward press WALKS the buoy-free bot horizontally OUT of the
        // vine and it runs out of climbable / DROPS into the pocket (live -711: 65.02→65.20→detach→
        // 61.9 inW, totStuck 2400+). Sustain it by (a) holding JUMP every tick (jumping → vy=+0.2,
        // the only wall-less ascent drive) and (b) driving the bot via the CAMERA-DECOUPLED impulse
        // straight at the immediate climb target wp — the live -711 vine is a WIDE curtain whose top
        // reaches the dismount ONLY on the exit side, so the bot must keep advancing UP-AND-TOWARD
        // the exit (a deadzone that goes pure-vertical when "aligned" stalls the exit advance and the
        // bot wedges — live-verified). commandMove tracks wp exactly while the camera slews, so the
        // off-axis drift is corrected without the camera lag mis-aiming a raw forward press. Wall-
        // backed cells (wallYaw!=null) hand back to the wall-press path to top out + step off.
        boolean freeHang = ControlConfig.navigatorVineFreeHangClimb && wallYaw == null && climbUp;
        float climbYaw;
        boolean pressForward = false; // for the wall-backed / legacy path below
        boolean freeHangDrive = false; // true → horizontal driven by commandMove (decoupled)
        float fhBearing = 0f; // radians; world bearing to the climb target
        if (freeHang) {
            // Steer toward the immediate next node `wp` (this is the LIVE-validated target: the bot
            // climbs the curtain up-and-across toward each successive node). `wp` can flip ±180° when
            // the pure-pursuit `step` advances under the foot, so the heading is SLEW-LIMITED below to
            // keep that from circling the bot off a narrow column.
            double tx = wp.getX() + 0.5 - p.getX();
            double tz = wp.getZ() + 0.5 - p.getZ();
            if (tx * tx + tz * tz < 0.04) {
                // wp is DIRECTLY above (within 0.2 block): atan2 of a ~zero vector is pure noise.
                // Climb PURE-VERTICAL — hold the camera, no horizontal drive, just the jump.
                climbYaw = p.getYRot();
                freeHangDrive = false;
                freeHangDriveYaw = Float.NaN; // resync the slew when a real bearing returns
            } else {
                // SLEW-LIMIT the drive heading: turn a persistent heading at most ~30°/tick toward the
                // bearing to wp. When `step` advances and wp flips, the heading averages the flip into
                // a smooth arc rather than walking the bot in CIRCLES off the column (the arena's
                // narrow-column flake). On the live -711 curtain the bearing is steady (the climb tracks
                // the exit) so the slewed value just hugs it — the proven up-and-across path is intact.
                float target = (float) Math.toDegrees(Math.atan2(-tx, tz));
                if (Float.isNaN(freeHangDriveYaw)) {
                    freeHangDriveYaw = target; // snap on the first tick of the climb
                } else {
                    float dyaw = angleDiff(freeHangDriveYaw, target); // shortest turn toward target
                    if (Math.abs(dyaw) > MAX_YAW_SLEW_DEG) {
                        dyaw = Math.copySign(MAX_YAW_SLEW_DEG, dyaw);
                    }
                    freeHangDriveYaw += dyaw;
                }
                freeHangDrive = true;
                fhBearing = (float) Math.toRadians(freeHangDriveYaw); // drive along the slewed heading
                climbYaw = freeHangDriveYaw; // camera follows the same slewed heading
            }
        } else if (wallYaw != null) {
            climbYaw = wallYaw;
            pressForward = climbUp;
        } else if (vdx * vdx + vdz * vdz < YAW_DEADZONE_SQ) {
            climbYaw = p.getYRot();
            pressForward = climbUp;
        } else {
            climbYaw = (float) Math.toDegrees(Math.atan2(-vdx, vdz));
            pressForward = climbUp;
        }
        if (Math.abs(angleDiff(p.getYRot(), climbYaw)) > ControlConfig.navigatorYawHysteresisDeg) {
            float want = smoothAngle(p.getYRot(), climbYaw);
            float dyaw = angleDiff(p.getYRot(), want);
            if (Math.abs(dyaw) > MAX_YAW_SLEW_DEG) {
                dyaw = Math.copySign(MAX_YAW_SLEW_DEG, dyaw);
            }
            float ny = p.getYRot() + dyaw;
            p.setYRot(ny);
            p.yHeadRot = ny;
            p.yBodyRot = ny;
        }
        p.setXRot(smoothAngle(p.getXRot(), 0f));
        // Horizontal drive: free-hang uses the camera-DECOUPLED impulse toward the climb target
        // (exact bearing regardless of the slewing camera); otherwise the raw forward press (into
        // the wall / along the camera). A modest forward magnitude keeps the bot advancing toward
        // the exit column without overshooting off the curtain (the climbable clamp caps it ≤0.15).
        if (freeHangDrive) {
            // Drive the bot toward wp via the impulse channel (vanilla travel() rotates it by the
            // camera yaw). Empirically LIVE-tuned: the world-bearing impulse + the camera slewing
            // toward fhBearing converges the bot onto a steady up-and-toward-the-exit climb across
            // the wide -711 curtain (in-pocket→0, tops out, advances). A "fully decoupled" Δ-form was
            // tried and REGRESSED live (it wedged the bot in the pocket — the wide-curtain climb needs
            // THIS trajectory, not a geometrically-pure one), so do NOT change it without a live A/B.
            a.commandMove(
                    (float) (-Math.sin(fhBearing) * FREE_HANG_DRIVE), (float) (Math.cos(fhBearing) * FREE_HANG_DRIVE));
        } else {
            Keys.forward(a, pressForward); // forward INTO the wall (wall-backed climb)
        }
        // On a free-hanging climb hold JUMP every tick (jumping sustains the wall-less vy=+0.2);
        // otherwise jump tracks climbUp (off → slide back down a wall-backed vine to be re-planned).
        if (freeHang || climbUp) {
            nav.jumpTag = "vineClimb";
        }
        nav.keys.jump(a, freeHang || climbUp); // continuous jump on a wall-less climb
        Keys.sneak(a, false); // sneak would HALT the vine climb
        p.setShiftKeyDown(false);
        p.setSprinting(false);
        if (GameConfig.navigatorDebug) {
            LOG.info(
                    "[navigator] vine-climb foot={},{},{} ahead={},{},{} climbUp={} freeHang={} fhDrive={} yaw={} py={}",
                    foot.getX(),
                    foot.getY(),
                    foot.getZ(),
                    ahead.getX(),
                    ahead.getY(),
                    ahead.getZ(),
                    climbUp,
                    freeHang,
                    freeHangDrive,
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    String.format(Locale.ROOT, "%.2f", p.getY()));
        }
        return true;
    }

    @Override
    public void stop(String why) {
        // No state of its own to clear on stop.
    }
}

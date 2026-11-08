package net.magicterra.worlddriver.game.body;

import net.magicterra.worlddriver.config.GameConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single chokepoint that rate-limits ALL bot camera motion so the view can never snap — the AIRI live-stream
 * requirement ("no meaningless camera jumps"). Every actuator (Navigator, AutoSwim, the bunker/build/farm processes,
 * the reflexes, …) writes the player's rotation as usual; {@link #apply} runs ONCE per client tick <em>after</em> all
 * of them and clamps this tick's net yaw/pitch change to {@link GameConfig#cameraSlewDegPerTick} /
 * {@link GameConfig#cameraPitchSlewDegPerTick}. Because it's a single post-write clamp, no code path — present or
 * future — can teleport the camera; the worst any snap-style write can do is start a smooth multi-tick pan.
 *
 * <p>Exemption: a genuinely <em>functional</em> exact aim — a camera-raycast mine/attack that must land on the target
 * block this tick, or a parkour/MLG leap whose heading must be set before takeoff — calls {@link #requestSnap()} to
 * bypass the clamp for that one tick. These are rare and "meaningful" (the bot looks at what it acts on); cosmetic aims
 * (placement orient via the synthetic {@code clientUseItemOn}, the pillar look-down, swim heading, follow/look) all go
 * through the clamp and stay silky.
 *
 * <p>The clamp covers the turn made inside the client tick, between {@link #observe} and {@link #apply}. A rotation
 * given between ticks, a server teleport's, the mouse's or a verb's exact aim, is taken as it is: clamping it would
 * leave the camera one step towards it, there being no target left to slew on to.
 *
 * <p>Disabled by {@link GameConfig#cameraSlew} = false (restores the old snap behaviour). Inert in headless GameTest,
 * which has no client tick / LocalPlayer.
 */
public final class LookController {

    private static final Logger LOG = LoggerFactory.getLogger("WorldDriver");

    /** Whole-degree format for the RENDER log rows. */
    private static final String DEGREES = "%.0f";

    /** How quickly the drawn view catches up with the bot's rotation. */
    private static final double VIEW_TAU_SECONDS = 0.12;

    /** The furthest the drawn view trails the bot's rotation, in degrees. */
    private static final float VIEW_MAX_LAG = 8f;

    /** Calls closer together than this belong to one frame (the camera, the crosshair and the model all ask). */
    private static final long SAME_FRAME_NANOS = 2_000_000L;

    private static final ViewFollower YAW_VIEW = new ViewFollower();

    private static final ViewFollower PITCH_VIEW = new ViewFollower();

    private static float prevYaw;

    private static float prevPitch;

    private static boolean havePrev;

    private static boolean snapThisTick;

    // The bot's turn this tick, drawn across the tick's frames by viewYaw/viewPitch (GameConfig.cameraFrameLerp).
    private static boolean frameLerp;

    private static float lerpFromYaw;

    private static float lerpToYaw;

    private static float lerpFromPitch;

    private static float lerpToPitch;

    // WINDING ground truth: is the RENDERED camera spinning in circles (smoothly, no snap)?
    private static double windCumAbs;

    private static double windUnwrapped;

    private static double windMin;

    private static double windMax;

    private static float windPrev;

    private static boolean windHavePrev;

    private static int windTick;

    private LookController() {}

    /**
     * Reset the winding accumulators.
     *
     * <p><b>Nothing calls this.</b> The javadoc read "call at replay/journey start" and described a call site that does
     * not exist — {@code grep -rn resetWind} over every source set returns this declaration and nothing else. The
     * contract was the wish, not the wiring.
     *
     * <p>That makes the WIND row below say something other than what it looks like: with no reset, {@code turns} /
     * {@code netDrift} / {@code ticks} accumulate from the first tick {@code navigatorDebug} was ever on, for the life
     * of the JVM. A row read against one walk, one rung or one replay is answering a question about the whole session —
     * the instrument is fine, the window is not the one a reader assumes. Correlate it by DIFFERENCING two rows, never
     * by taking one row's value.
     *
     * <p><b>And a caller is the wrong fix, so do not add one.</b> These accumulators are {@code static}: one copy per
     * JVM, however many readers. A reset serves whoever called it by zeroing the window of every other reader at the
     * same time — two walks, two rungs or a scene and a replay reading at once would silently truncate each other, and
     * the corruption looks exactly like a short quiet stretch. {@code futileGateBuckets} met this and chose
     * differencing for that reason; {@code NavigatorCensus} is the same shape again. A reader that takes its own
     * baseline costs one extra row and gets in nobody's way, so the rule for every process-wide counter here is:
     * <em>the reader brings a baseline, the counter never rewinds.</em>
     *
     * <p>Kept rather than deleted only because deleting it is a code change wanting a gate, and an unwired method that
     * says why it stays unwired is a cheaper signpost than a silent gap.
     */
    public static void resetWind() {
        windCumAbs = windUnwrapped = windMin = windMax = 0;
        windHavePrev = false;
        windTick = 0;
    }

    /**
     * Bypass the slew for the CURRENT tick (functional exact aim — see class doc). Auto-reset by the next
     * {@link #apply}.
     */
    public static void requestSnap() {
        snapThisTick = true;
    }

    /**
     * Whether this tick's turn is an exact aim, which {@link #apply} leaves pointing where it points now and draws
     * without lag. Only then is a crosshair pick taken now the one the game sees at the next tick: any other turn is
     * clamped, and its drawn view (which the crosshair pick reads through {@code getViewYRot}) trails the rotation.
     */
    public static boolean exactAimThisTick() {
        return snapThisTick;
    }

    /**
     * Drop the slew baseline so the next tick accepts the camera as-is instead of panning across a discontinuity that
     * isn't a real camera move (teleport, world reload, respawn).
     */
    public static void resync() {
        havePrev = false;
    }

    /**
     * Take the rotation {@code p} has now as the baseline when something other than the bot turned it since the last
     * {@link #apply}: a server teleport's rotation, the mouse. Call at the start of the client tick, before any bot
     * actuator writes the rotation, so the clamp limits only the bot's own turn. Without this a teleport's rotation
     * read as a turn and was clamped back to one step from the old heading, which then stayed: the step left on the
     * player was the next tick's target, so the client kept it and sent it to the server as its own.
     */
    public static void observe(LivingEntity p) {
        if (p == null || !havePrev) {
            return;
        }
        // apply leaves the player at exactly prevYaw/prevPitch, so any difference came from outside it.
        if (p.getYRot() != prevYaw || p.getXRot() != prevPitch) {
            prevYaw = p.getYRot();
            prevPitch = p.getXRot();
            frameLerp = false;
        }
    }

    /**
     * The yaw to draw this frame, or {@code current} when the rotation is not the bot's (the mouse or a teleport turned
     * it since) or the turn was an exact aim. The bot's last turn is spread over the tick's frames, then low-passed in
     * time and held within {@link #VIEW_MAX_LAG} of it: the aim the bot steers by flickers a few degrees from tick to
     * tick, and drawn as it is the view shook. Only the drawing is smoothed; the rotation the body moves and the
     * navigator reads is the one the bot set.
     */
    public static float viewYaw(float partialTick, float current) {
        if (!frameLerp || current != lerpToYaw) {
            YAW_VIEW.clear();
            return current;
        }
        return YAW_VIEW.follow(lerpFromYaw + (lerpToYaw - lerpFromYaw) * partialTick);
    }

    /** The pitch to draw this frame, as {@link #viewYaw} for the yaw. */
    public static float viewPitch(float partialTick, float current) {
        if (!frameLerp || current != lerpToPitch) {
            PITCH_VIEW.clear();
            return current;
        }
        return PITCH_VIEW.follow(lerpFromPitch + (lerpToPitch - lerpFromPitch) * partialTick);
    }

    /** One drawn angle following its target in time, once per frame however often a frame asks. */
    private static final class ViewFollower {
        private float shown = Float.NaN;

        private long shownNanos;

        void clear() {
            shown = Float.NaN;
        }

        float follow(float target) {
            long now = System.nanoTime();
            if (Float.isNaN(shown)) {
                shown = target;
            } else if (now - shownNanos > SAME_FRAME_NANOS) {
                float k = (float) (1 - Math.exp(-(now - shownNanos) / 1e9 / VIEW_TAU_SECONDS));
                float behind = Mth.degreesDifference(target, shown) * (1 - k);
                shown = target + Mth.clamp(behind, -VIEW_MAX_LAG, VIEW_MAX_LAG);
            } else {
                return shown;
            }
            shownNanos = now;
            return shown;
        }
    }

    /**
     * Clamp this tick's net camera change. Call once, at the very end of the client tick, after every bot actuator has
     * written the bot's rotation. Takes any {@link LivingEntity}: yaw, pitch, head and body rotation all live there, so
     * the clamp reads the same on a driven mob as on the local player.
     */
    public static void apply(LivingEntity p) {
        if (p == null) {
            havePrev = false;
            snapThisTick = false;
            frameLerp = false;
            return;
        }
        // An exact aim is drawn where it lands: the crosshair has to be on the target on every frame of its tick.
        frameLerp = GameConfig.cameraFrameLerp && havePrev && !snapThisTick;
        lerpFromYaw = prevYaw;
        lerpFromPitch = prevPitch;
        float yaw = p.getYRot();
        float pitch = p.getXRot();
        boolean snap = snapThisTick || !GameConfig.cameraSlew || !havePrev;
        snapThisTick = false;
        logRenderJump(snap, yaw, pitch);
        float rendered;
        if (snap) {
            // Same heading, but the VALUE nearest last tick's: the renderer lerps yRotO→yRot by value, so a dig
            // snap from -547 to 56 (117° of real turn) drew a 603° spin, twice a run on the cross-country cliff.
            if (havePrev) {
                yaw = prevYaw + Mth.degreesDifference(prevYaw, yaw);
                p.setYRot(yaw);
                p.yHeadRot = yaw;
                p.yBodyRot = yaw;
            }
            prevYaw = yaw;
            prevPitch = pitch;
            havePrev = true;
            rendered = yaw;
        } else {
            // Shortest arc, at most the slew per tick: MC yaw is unwrapped.
            float ny = Mth.approachDegrees(prevYaw, yaw, GameConfig.cameraSlewDegPerTick);
            float dp = pitch - prevPitch;
            float maxP = GameConfig.cameraPitchSlewDegPerTick;
            if (dp > maxP) {
                dp = maxP;
            } else if (dp < -maxP) {
                dp = -maxP;
            }
            final float np = clamp(prevPitch + dp, -90f, 90f);
            p.setYRot(ny);
            p.yHeadRot = ny;
            p.yBodyRot = ny;
            p.setXRot(np);
            prevYaw = ny;
            prevPitch = np;
            rendered = ny;
        }
        lerpToYaw = p.getYRot();
        lerpToPitch = p.getXRot();
        accumulateWinding(rendered);
    }

    /**
     * GROUND TRUTH of the RENDERED camera: a snap that jumps the view >=30° this tick is exactly what the video sees
     * (and what walk-keys, captured pre-apply, cannot show). Reads the baseline before {@link #apply} moves it.
     */
    private static void logRenderJump(boolean snap, float yaw, float pitch) {
        if (GameConfig.navigatorDebug && havePrev) {
            float rd = Math.abs(Mth.degreesDifference(prevYaw, yaw));
            if (rd >= 30f) {
                LOG.info(
                        "[lookctrl] RENDER {} dyaw={} {}->{}",
                        snap ? "SNAP" : "slew",
                        String.format(DEGREES, rd),
                        String.format(DEGREES, prevYaw),
                        String.format(DEGREES, yaw));
            }
            float pd = Math.abs(pitch - prevPitch);
            if (pd >= 30f) {
                LOG.info(
                        "[lookctrl] RENDER {} dpitch={} {}->{}",
                        snap ? "SNAP" : "slew",
                        String.format(DEGREES, pd),
                        String.format(DEGREES, prevPitch),
                        String.format(DEGREES, pitch));
            }
        }
    }

    /**
     * WINDING: accumulate the rendered camera's net rotation. A smooth multi-turn spin (no single snap) still reads as
     * spinning — this is what per-tick snap-counting missed.
     */
    private static void accumulateWinding(float rendered) {
        if (GameConfig.navigatorDebug) {
            if (windHavePrev) {
                float d = Mth.degreesDifference(windPrev, rendered);
                windCumAbs += Math.abs(d);
                windUnwrapped += d;
                if (windUnwrapped < windMin) {
                    windMin = windUnwrapped;
                }
                if (windUnwrapped > windMax) {
                    windMax = windUnwrapped;
                }
                if (++windTick % 60 == 0) {
                    LOG.info(
                            "[lookctrl] WIND turns={} netDrift=[{}..{}]deg ticks={}",
                            String.format("%.2f", windCumAbs / 360.0),
                            (int) windMin,
                            (int) windMax,
                            windTick);
                }
            }
            windPrev = rendered;
            windHavePrev = true;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single chokepoint that rate-limits ALL bot camera motion so the view can never
 * snap — the AIRI live-stream requirement ("no meaningless camera jumps"). Every
 * actuator (Walker, AutoSwim, the bunker/build/farm processes, the reflexes, …)
 * writes the player's rotation as usual; {@link #apply} runs ONCE per client tick
 * <em>after</em> all of them and clamps this tick's net yaw/pitch change to
 * {@link BotConfig#cameraSlewDegPerTick} / {@link BotConfig#cameraPitchSlewDegPerTick}.
 * Because it's a single post-write clamp, no code path — present or future — can
 * teleport the camera; the worst any snap-style write can do is start a smooth
 * multi-tick pan.
 *
 * <p>Exemption: a genuinely <em>functional</em> exact aim — a camera-raycast
 * mine/attack that must land on the target block this tick, or a parkour/MLG leap
 * whose heading must be set before takeoff — calls {@link #requestSnap()} to bypass
 * the clamp for that one tick. These are rare and "meaningful" (the bot looks at
 * what it acts on); cosmetic aims (placement orient via the synthetic
 * {@code clientUseItemOn}, the pillar look-down, swim heading, follow/look) all go
 * through the clamp and stay silky.
 *
 * <p>Disabled by {@link BotConfig#cameraSlew} = false (restores the old snap
 * behaviour). Inert in headless GameTest, which has no client tick / LocalPlayer.
 */
public final class LookController {
    private LookController() {}

    private static final Logger LOG = LoggerFactory.getLogger("WorldDriver");
    private static float prevYaw, prevPitch;
    private static boolean havePrev;
    private static boolean snapThisTick;
    // WINDING ground truth: is the RENDERED camera spinning in circles (smoothly, no snap)?
    private static double windCumAbs, windUnwrapped, windMin, windMax;
    private static float windPrev;
    private static boolean windHavePrev;
    private static int windTick;
    /** Reset the winding accumulators.
     *
     *  <p><b>Nothing calls this.</b> The javadoc read "call at replay/journey start" and
     *  described a call site that does not exist — {@code grep -rn resetWind} over every
     *  source set returns this declaration and nothing else. The contract was the wish,
     *  not the wiring.
     *
     *  <p>That makes the WIND row below say something other than what it looks like: with
     *  no reset, {@code turns} / {@code netDrift} / {@code ticks} accumulate from the first
     *  tick {@code walkerDebug} was ever on, for the life of the JVM. A row read against
     *  one leg, one rung or one replay is answering a question about the whole session —
     *  the instrument is fine, the window is not the one a reader assumes. Correlate it by
     *  DIFFERENCING two rows, never by taking one row's value.
     *
     *  <p><b>And a caller is the wrong fix, so do not add one.</b> These accumulators are
     *  {@code static}: one copy per JVM, however many readers. A reset serves whoever called it
     *  by zeroing the window of every other reader at the same time — two legs, two rungs or a
     *  scene and a replay reading at once would silently truncate each other, and the corruption
     *  looks exactly like a short quiet stretch. {@code futileGateBuckets} met this and chose
     *  differencing for that reason; {@code WalkerCensus} is the same shape again. A reader that
     *  takes its own baseline costs one extra row and gets in nobody's way, so the rule for every
     *  process-wide counter here is: <em>the reader brings a baseline, the counter never rewinds.</em>
     *
     *  <p>Kept rather than deleted only because deleting it is a code change wanting a gate, and
     *  an unwired method that says why it stays unwired is a cheaper signpost than a silent gap. */
    public static void resetWind() { windCumAbs = windUnwrapped = windMin = windMax = 0; windHavePrev = false; windTick = 0; }

    /** Bypass the slew for the CURRENT tick (functional exact aim — see class doc).
     *  Auto-reset by the next {@link #apply}. */
    public static void requestSnap() { snapThisTick = true; }

    /** Drop the slew baseline so the next tick accepts the camera as-is instead of
     *  panning across a discontinuity that isn't a real camera move (teleport, world
     *  reload, respawn). */
    public static void resync() { havePrev = false; }

    /** Clamp this tick's net camera change. Call once, at the very end of the client
     *  tick, after every bot actuator has written the body's rotation. Takes any
     *  {@link LivingEntity}: yaw, pitch, head and body rotation all live there, so the
     *  clamp reads the same on a driven mob as on the local player. */
    public static void apply(LivingEntity p) {
        if (p == null) { havePrev = false; snapThisTick = false; return; }
        float yaw = p.getYRot(), pitch = p.getXRot();
        boolean snap = snapThisTick || !BotConfig.cameraSlew || !havePrev;
        snapThisTick = false;
        // GROUND TRUTH of the RENDERED camera: a snap that jumps the view >=30° this tick is
        // exactly what the video sees (and what walk-keys, captured pre-apply, cannot show).
        if (BotConfig.walkerDebug && havePrev) {
            float rd = Math.abs(((yaw - prevYaw) % 360f + 540f) % 360f - 180f);
            if (rd >= 30f)
                LOG.info("[lookctrl] RENDER {} dyaw={} {}->{}",
                        snap ? "SNAP" : "slew", String.format("%.0f", rd),
                        String.format("%.0f", prevYaw), String.format("%.0f", yaw));
            float pd = Math.abs(pitch - prevPitch);
            if (pd >= 30f)
                LOG.info("[lookctrl] RENDER {} dpitch={} {}->{}",
                        snap ? "SNAP" : "slew", String.format("%.0f", pd),
                        String.format("%.0f", prevPitch), String.format("%.0f", pitch));
        }
        float rendered;
        if (snap) {
            prevYaw = yaw; prevPitch = pitch; havePrev = true;
            rendered = yaw;
        } else {
            float ny = stepAngle(prevYaw, yaw, BotConfig.cameraSlewDegPerTick);
            float dp = pitch - prevPitch;
            float maxP = BotConfig.cameraPitchSlewDegPerTick;
            if (dp > maxP) dp = maxP; else if (dp < -maxP) dp = -maxP;
            float np = clamp(prevPitch + dp, -90f, 90f);
            p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny; p.setXRot(np);
            prevYaw = ny; prevPitch = np;
            rendered = ny;
        }
        // WINDING: accumulate the rendered camera's net rotation. A smooth multi-turn spin (no
        // single snap) still reads as spinning — this is what per-tick snap-counting missed.
        if (BotConfig.walkerDebug) {
            if (windHavePrev) {
                float d = ((rendered - windPrev) % 360f + 540f) % 360f - 180f;
                windCumAbs += Math.abs(d); windUnwrapped += d;
                if (windUnwrapped < windMin) windMin = windUnwrapped;
                if (windUnwrapped > windMax) windMax = windUnwrapped;
                if (++windTick % 60 == 0)
                    LOG.info("[lookctrl] WIND turns={} netDrift=[{}..{}]deg ticks={}",
                            String.format("%.2f", windCumAbs / 360.0), (int) windMin, (int) windMax, windTick);
            }
            windPrev = rendered; windHavePrev = true;
        }
    }

    /** Move {@code from} toward {@code to} by at most {@code maxDeg}, on the shortest
     *  signed arc (MC yaw is unwrapped, so use the wrapped difference). */
    private static float stepAngle(float from, float to, float maxDeg) {
        float d = ((to - from) % 360f + 540f) % 360f - 180f;
        if (d > maxDeg) d = maxDeg; else if (d < -maxDeg) d = -maxDeg;
        return from + d;
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}

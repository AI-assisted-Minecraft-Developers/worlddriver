package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.minecraft.client.player.LocalPlayer;

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

    private static float prevYaw, prevPitch;
    private static boolean havePrev;
    private static boolean snapThisTick;

    /** Bypass the slew for the CURRENT tick (functional exact aim — see class doc).
     *  Auto-reset by the next {@link #apply}. */
    public static void requestSnap() { snapThisTick = true; }

    /** Drop the slew baseline so the next tick accepts the camera as-is instead of
     *  panning across a discontinuity that isn't a real camera move (teleport, world
     *  reload, respawn). */
    public static void resync() { havePrev = false; }

    /** Clamp this tick's net camera change. Call once, at the very end of the client
     *  tick, after every bot actuator has written the player's rotation. */
    public static void apply(LocalPlayer p) {
        if (p == null) { havePrev = false; snapThisTick = false; return; }
        float yaw = p.getYRot(), pitch = p.getXRot();
        boolean snap = snapThisTick || !BotConfig.cameraSlew || !havePrev;
        snapThisTick = false;
        if (snap) { prevYaw = yaw; prevPitch = pitch; havePrev = true; return; }
        float ny = stepAngle(prevYaw, yaw, BotConfig.cameraSlewDegPerTick);
        float dp = pitch - prevPitch;
        float maxP = BotConfig.cameraPitchSlewDegPerTick;
        if (dp > maxP) dp = maxP; else if (dp < -maxP) dp = -maxP;
        float np = clamp(prevPitch + dp, -90f, 90f);
        p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny; p.setXRot(np);
        prevYaw = ny; prevPitch = np;
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

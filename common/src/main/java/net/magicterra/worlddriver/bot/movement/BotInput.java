package net.magicterra.worlddriver.bot.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Locomotion actuator facade: every bot driver (Walker special branches, flee chains,
 * mining/farm/follow approach loops) sets movement through HERE instead of poking the
 * SHARED global keybinds {@code mc.options.keyUp/Jump/Shift/Sprint} — those are the same
 * objects a human's keyboard maps to, and writing them fights manual play.
 *
 * <p>Instead this drives the player's OWN {@link AvatarInput} (impulse / Input.jumping /
 * Input.shiftKeyDown) and {@code setSprinting()} directly — which is the "send the packet,
 * don't press the key" path: vanilla serialises the input/flag into ServerboundMovePlayer /
 * ServerboundPlayerCommand packets. Per-tick (see {@link AvatarInput}): a driver re-asserts
 * each active tick (they all run per-tick loops); the instant it stops, the uncommanded tick
 * falls back to the real keybind, so manual play is untouched with no explicit release.
 *
 * <p><b>Sprint</b> needs no AvatarInput field — {@code LocalPlayer.setSprinting(v)} flips the
 * shared flag and aiStep emits START/STOP_SPRINTING; the old {@code keySprint.setDown} was
 * redundant beside the {@code setSprinting} every site already paired with it.
 *
 * <p><b>Attack / use</b> are deliberately NOT routed here — they have their own seam,
 * {@link ClientIntents}, because vanilla reads those two keybinds itself in
 * {@code Minecraft.handleKeybinds} rather than through the {@code Input} object this class
 * commands; and they don't collide with movement anyway.
 *
 * <p><b>Why the keybinds were not merely impolite but INERT.</b> Measured 2026-08-22 on the
 * integrated (real-client) ladder: {@link AvatarInput#tick} runs vanilla's key pass FIRST and
 * then <b>overwrites</b> {@code forwardImpulse}/{@code leftImpulse} with whatever the Walker
 * commanded that tick. So every horizontal key a reflex pressed was discarded outright whenever
 * a movement process was active, while {@code keyJump} — which the Walker usually does not
 * command — survived. A body submerged at world spawn bobbed between y=61 and y=63 for 7 800
 * ticks with <b>zero horizontal displacement</b>: jump worked, swimming did not. That asymmetry
 * is the whole bug, and it is invisible from a log because the keys were "pressed" successfully.
 *
 * <p><b>Two channels, and one of them still loses.</b> {@code commandForward} (what {@link
 * #forward} drives) and {@code commandMove} (what {@link #halt} drives) are NOT peers —
 * {@link AvatarInput#tick} checks {@code moveCommanded} first and only falls through to
 * {@code rawMoveCommanded}, so <b>a Walker {@code commandMove} in the same tick silently
 * discards a {@code forward()}</b>, exactly as the keybind pass discarded the keys. Call order
 * does not matter; the precedence is fixed in {@code tick()}. A reflex that must OVERRIDE a
 * running process therefore has to use {@link #halt} (or a real {@code commandMove}); a reflex
 * that merely wants to steer an otherwise-idle body may use {@link #forward}.
 */
public final class BotInput {
    private BotInput() {}

    /** The player's AvatarInput, installing it if a respawn/dimension swap left a vanilla
     *  KeyboardInput (drivers other than the Walker may take over before it installs one). */
    private static AvatarInput ai(LocalPlayer p) {
        if (!(p.input instanceof AvatarInput)) p.input = new AvatarInput(Minecraft.getInstance().options);
        return (AvatarInput) p.input;
    }

    /** Forward intent — {@code forward(mc, true)} ≡ keyUp held, {@code forward(mc, false)} ≡ released.
     *  Loses to a same-tick Walker {@code commandMove} (see the class doc); a reflex that must
     *  override a running process wants {@link #driveForward} or {@link #halt} instead. */
    public static void forward(Minecraft mc, boolean v) {
        LocalPlayer p = mc.player;
        if (p != null) ai(p).commandForward(v ? 1f : 0f);
    }

    /**
     * Full-speed forward along the body's own yaw, OVERRIDING a running process.
     *
     * <p>Identical in effect to {@link #forward}{@code (mc, true)} — {@code commandMove(0, 1)}
     * sets exactly the {@code forwardImpulse = 1, leftImpulse = 0} that a held W key produces —
     * and different in exactly one way: it wins the precedence check in {@link AvatarInput#tick},
     * so the Walker's own command for that tick cannot silently discard it.
     *
     * <p>For the survival reflexes (contact damage, lava front) that difference is the whole
     * point. Their class docs claim to "override an active walker's keys during the episode", and
     * on the weaker channel that claim was false on precisely the ticks it mattered: a body
     * wedged against a hazard by a running {@code goto} is the case those reflexes exist for, and
     * it is also the case where a movement process is commanding every tick.
     *
     * <p>Callers must aim first — this drives along the camera, so the yaw is the steering.
     */
    public static void driveForward(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p != null) ai(p).commandMove(0f, 1f);
    }

    /**
     * Zero the horizontal movement vector this tick, OVERRIDING a running process
     * (≡ clearing keyUp/Down/Left/Right, and then some).
     *
     * <p>Uses {@code commandMove(0,0)}, not {@code commandForward(0)}, on purpose: only the
     * former wins the precedence check in {@link AvatarInput#tick} (see the class doc). A
     * reflex that has preempted the movement channel — drown-escape's pure-vertical float,
     * the bunker seal, the contact-damage back-off — is asserting "nothing else moves this
     * body this tick", and {@code commandForward(0)} cannot assert that.
     */
    public static void halt(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p != null) ai(p).commandMove(0f, 0f);
    }

    /**
     * Drive toward a world point WITHOUT turning the camera.
     *
     * <p>The alternative — slam {@code setYRot} at the target and hold forward — is what the
     * drown-escape lateral arm and {@code AutoSwim}'s shore-steer do, and it is the right shape for
     * a metres-long swim. It is the wrong shape for a correction measured in tenths of a block: the
     * camera whips for a nudge, which is one of the things a watching person reports as「视角乱甩」.
     *
     * <p>The conversion is the {@code WalkerTickRepath} back-off idiom, unchanged: vanilla's
     * {@code travel()} rotates the impulse by the CURRENT yaw, so feeding it the bearing's offset
     * from that yaw makes the body move along the bearing while the camera stays where it is.
     *
     * @param scale impulse magnitude, 1.0 being a full press. Callers correcting a small offset
     *              should scale it down — a full press across 0.2 blocks in water overshoots to the
     *              opposite cell boundary, which for the caller that motivated this method would
     *              swap one pinning neighbour for another.
     */
    public static void driveToward(Minecraft mc, double wx, double wz, float scale) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        double dx = wx - p.getX(), dz = wz - p.getZ();
        if (dx * dx + dz * dz < 1.0E-6) { ai(p).commandMove(0f, 0f); return; }
        float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double d = Math.toRadians(WalkerGeometry.angleDiff(p.getYRot(), bearing));
        ai(p).commandMove((float) (-Math.sin(d) * scale), (float) (Math.cos(d) * scale));
    }

    /** Jump intent — replaces {@code keyJump.setDown(v)}. */
    public static void jump(Minecraft mc, boolean v) {
        LocalPlayer p = mc.player;
        if (p != null) ai(p).commandJump(v);
    }

    /** Sneak intent — replaces {@code keyShift.setDown(v)}. */
    public static void sneak(Minecraft mc, boolean v) {
        LocalPlayer p = mc.player;
        if (p != null) ai(p).commandSneak(v);
    }

    /**
     * Sprint intent — replaces {@code keySprint.setDown(v)}. There is no AvatarInput field to
     * drive: {@code keySprint} is read by {@code LocalPlayer.aiStep} only to DECIDE a sprint
     * start, so setting the flag on the body is both the shorter path and the one that also
     * works for stopping (aiStep emits STOP_SPRINTING from the flag, never from the key).
     * Unlike the per-tick command channel this is <b>sticky</b> — it stays until something
     * flips it back, which is what every "…and definitely do not sprint into the lava" caller
     * wanted from {@code keySprint.setDown(false)} anyway.
     */
    public static void sprint(Minecraft mc, boolean v) {
        LocalPlayer p = mc.player;
        if (p != null) p.setSprinting(v);
    }
}

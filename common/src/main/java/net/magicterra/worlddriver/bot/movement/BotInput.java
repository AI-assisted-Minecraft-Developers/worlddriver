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
 * <p><b>Attack / use</b> are deliberately NOT routed here — mining stays on
 * {@code mc.options.keyAttack} so it rides vanilla's continueAttack → continueDestroyBlock
 * pipeline (calling gameMode directly desyncs client prediction and breaks completion
 * detection — see MineProcess), and they don't collide with movement anyway.
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

    /** Forward intent — {@code forward(mc, true)} ≡ keyUp held, {@code forward(mc, false)} ≡ released. */
    public static void forward(Minecraft mc, boolean v) {
        LocalPlayer p = mc.player;
        if (p != null) ai(p).commandForward(v ? 1f : 0f);
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

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

    /** Stop horizontal movement this tick (≡ clearing keyUp/Down/Left/Right). */
    public static void stop(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p != null) ai(p).commandForward(0f);
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
}

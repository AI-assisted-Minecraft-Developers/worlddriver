package net.magicterra.worlddriver.bot;

import com.mojang.blaze3d.platform.InputConstants;
import net.magicterra.worlddriver.bot.movement.MouseYieldGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Client adapter around {@link MouseYieldGate}: samples the real ESC key / cursor state
 * and performs the grab/release the gate asks for. Driven once per client tick from
 * {@code BotApiImpl.clientTick}, before any of its early returns, so the cursor is
 * handed back even on the ticks the bot bails early.
 *
 * <p><b>Why poll ESC instead of a keybind.</b> The mod ships no mixins, and a
 * {@code KeyMapping} would consume a key the player has bound to the pause menu. Raw
 * {@link InputConstants#isKeyDown} sees the physical key regardless of which screen has
 * focus, so the double-tap is detected even though the FIRST tap already opened the
 * pause menu. The reclaim then closes that menu ({@link PauseScreen} only — never
 * someone's inventory) and grabs the cursor, which is exactly the vanilla ESC-ESC
 * muscle memory, just with the yield latch cleared as well. Sampling is per client tick
 * (50 ms), well inside the {@value #ESC_DOUBLE_TAP_MS} ms window.
 *
 * <p>Client-only by construction — {@code BotApiImpl} is registered from the per-loader
 * client bootstrap, so a dedicated server never loads this class.
 */
public final class MouseYield {
    private MouseYield() {}

    /** Max gap between the two ESC presses that count as a double-tap. */
    public static final long ESC_DOUBLE_TAP_MS = 400;

    private static final MouseYieldGate GATE = new MouseYieldGate();

    private static boolean escDown;
    private static long lastEscMs;
    /** Name of whatever drove last (chain or process slot) — HUD copy. Volatile: the
     *  HUD reads it from the render thread, the bot tick writes it. */
    private static volatile String driver = "";

    /** Record a bot drive this tick. {@code who} labels it for the HUD (chain or slot name). */
    public static void markDriving(String who) {
        GATE.markDriving();
        if (who != null && !who.isEmpty()) driver = who;
    }

    /** True while the bot is driving (drive mark + linger) — the HUD's visibility gate. */
    public static boolean driving() { return GATE.driving(); }

    /** True when the cursor is currently released because of the bot. */
    public static boolean yielded() { return GATE.yielded(); }

    /** True when the human double-tapped ESC and holds the cursor for this burst. */
    public static boolean reclaimed() { return GATE.reclaimed(); }

    /** What drove last, for display. Empty once the burst ends. */
    public static String driver() { return driver; }

    /** Drive the gate and apply its verdict. Safe to call on any tick, world or not. */
    public static void tick(Minecraft mc) {
        if (mc == null || mc.getWindow() == null || mc.mouseHandler == null) return;
        MouseYieldGate.Action action = GATE.tick(
                BotConfig.mouseYield,
                pollEscDoubleTap(mc),
                mc.screen != null,
                mc.mouseHandler.isMouseGrabbed());
        switch (action) {
            case RELEASE -> mc.mouseHandler.releaseMouse();
            case GRAB -> {
                // Only the pause menu is ours to close (it is what the first ESC of the
                // double-tap opened). Any other screen belongs to the player.
                if (mc.screen instanceof PauseScreen) mc.setScreen(null);
                // Never yank the cursor from another application: if the window isn't
                // focused, leave it free — the player is elsewhere and clicking back in
                // re-grabs it the vanilla way.
                if (mc.screen == null && mc.isWindowActive() && !mc.mouseHandler.isMouseGrabbed()) {
                    mc.mouseHandler.grabMouse();
                }
            }
            case NONE -> { }
        }
        if (!GATE.driving()) driver = "";
    }

    /** Rising-edge ESC sampler; true on the second press inside the double-tap window. */
    private static boolean pollEscDoubleTap(Minecraft mc) {
        boolean down = InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE);
        boolean rising = down && !escDown;
        escDown = down;
        if (!rising) return false;
        long now = System.currentTimeMillis();
        if (lastEscMs != 0 && now - lastEscMs <= ESC_DOUBLE_TAP_MS) {
            lastEscMs = 0;   // consume, so a triple-tap doesn't fire twice
            return true;
        }
        lastEscMs = now;
        return false;
    }
}

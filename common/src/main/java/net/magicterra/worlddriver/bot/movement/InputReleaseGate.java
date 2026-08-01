package net.magicterra.worlddriver.bot.movement;

/**
 * Decides WHEN the bot must clear the global movement keybinds
 * ({@code mc.options.keyUp/Down/Left/Right/Jump/Sprint/Attack/Shift}) it pressed,
 * WITHOUT clobbering a human player's keys when the bot never touched them.
 *
 * <p><b>The bug this fixes.</b> {@code BotApiImpl.clientTick} used to call
 * {@code releaseKeys()} on EVERY idle tick (no active process). Those keybinds are
 * the SAME objects the player's keyboard drives, and MC only re-asserts a held key
 * on the GLFW press edge — so clearing them 20×/s left a manually-held W/A/S/D/space
 * dead within ~50 ms of each press ("装了模组按键非常卡手"), even with no agent.
 *
 * <p><b>The rule.</b> The bot pressing any movement keybind marks them "dirty"
 * ({@link #markDirtied}). A release ({@link #consumeRelease} → {@code true}) clears
 * them AND the flag, so it fires exactly once per drive burst — enough to tidy the
 * bot's trailing presses, never the steady per-tick clobber. While the bot never
 * presses (manual play, no agent), nothing is ever dirtied, so the human's keys are
 * never cleared.
 *
 * <p>Pure logic, no client classes — unit-tested headless (testkit scene {@code wd.inputReleaseGate}).
 */
public final class InputReleaseGate {
    private boolean dirty;

    /** Record that the bot actuated a global movement keybind this tick. */
    public void markDirtied() {
        dirty = true;
    }

    /**
     * Ask, at an idle moment, whether the bot's trailing keybind presses should be
     * cleared now. Returns {@code true} (and disarms) only if the bot dirtied the
     * keybinds since the last release; {@code false} otherwise — so a never-driving
     * bot never asks the caller to touch the player's keys.
     */
    public boolean consumeRelease() {
        if (dirty) {
            dirty = false;
            return true;
        }
        return false;
    }

    public boolean isDirty() {
        return dirty;
    }
}

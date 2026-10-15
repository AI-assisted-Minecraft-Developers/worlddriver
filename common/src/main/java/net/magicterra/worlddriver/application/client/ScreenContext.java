package net.magicterra.worlddriver.application.client;

import java.security.SecureRandom;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The {@code contextId} screen reads hand out and screen writes may be held to: one generation per
 * screen opening. A class name or a slot number cannot say the same screen is still open, because the
 * chest closed and reopened is the same class with the same slots holding other things.
 *
 * <p>Every {@code setScreen} starts a generation ({@code MinecraftMixin}), even one that reopens the very
 * same screen object, as a pause menu does returning from its options. The identity check in
 * {@link #current} also starts one for a screen that arrived some other way. The ids carry a random
 * per-JVM prefix, so one from before a restart never matches. Client thread only.
 */
public final class ScreenContext {
    private ScreenContext() {}

    private static final String EPOCH = Long.toString(new SecureRandom().nextLong() & Long.MAX_VALUE, 36);

    private static long generation;
    private static Screen seen;

    /** A screen was set: a new context, whatever the screen is. */
    public static void opened(Screen now) {
        generation++;
        seen = now;
    }

    /** The context of what is open now. */
    public static String current() {
        Screen now = Minecraft.getInstance().screen;
        if (now != seen) opened(now);
        return EPOCH + "-" + generation;
    }

    /** Refuses as {@code context_changed} unless {@code expected} is absent or still current; acts on nothing. */
    public static void require(Optional<String> expected) {
        if (expected.isEmpty()) return;
        String now = current();
        if (!now.equals(expected.get())) {
            throw ApiException.of(ApiError.Code.CONTEXT_CHANGED, "the screen changed since contextId "
                    + expected.get() + " was read (now " + now + "); read it again before acting");
        }
    }
}

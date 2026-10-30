package net.magicterra.worlddriver.bot.control;

import net.minecraft.world.level.Level;

/**
 * Dist-neutral pointer to the level the bot is currently ticking in, set by the Navigator each tick. Lets server-thread
 * PathTrace sinks (PathArchiveRecorder) read blocks without referencing the client-only Minecraft class.
 */
public final class BotLevelHolder {

    public static volatile Level current;

    private BotLevelHolder() {}
}

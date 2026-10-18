package net.magicterra.worlddriver.bot;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Which bots exist: spawning a player bot, taking a mob over, and letting either go. What
 * {@code mc.bot.roster} and the {@code /worlddriver} commands both call, on the server thread.
 */
public final class Roster {
    private Roster() {}

    /** What a roster change answers: the bot's ref, or why nothing changed. */
    public sealed interface Outcome permits Done, Refusal {}
    public record Done(String bot) implements Outcome {}
    /** {@code unknown} when the ref names no bot; otherwise the request names something that cannot be a bot. */
    public record Refusal(String message, boolean unknown) implements Outcome {}

    /** A new player bot at {@code pos} in {@code level}, addressed as {@code player:<name>}. */
    public static Outcome spawn(ServerLevel level, Vec3 pos, String name) {
        String id = PlayerBot.KIND + ":" + name;
        // Checked before the player joins, so a taken name spawns nothing. Every registry writer runs on
        // the server thread, so nothing can take the name in between.
        if (BotRegistry.get(id) != null) return new Refusal("a bot named " + id + " already exists", false);
        PlayerBot bot = PlayerBot.createIsolated(level, pos.x, pos.y, pos.z).named(name);
        BotTicker.register(bot);
        BotRegistry.register(bot);
        return new Done(id);
    }

    /** Take {@code mob} over as {@code mob:<name>}, or as {@code entity:<uuid>} when {@code name} is null. */
    public static Outcome adopt(Mob mob, String name) {
        String id = name != null ? MobBot.KIND + ":" + name : BotRegistry.ENTITY_PREFIX + mob.getUUID();
        String refusal = MobBot.refusal(mob);
        if (refusal != null) return new Refusal("cannot drive this mob: " + refusal, false);
        for (Bot b : BotRegistry.all()) {
            if (b.entity() == mob) return new Refusal("that mob is already " + b.id(), false);
        }
        if (BotRegistry.get(id) != null) return new Refusal("a bot named " + id + " already exists", false);
        MobBot bot = MobBot.adopt(mob, id);
        BotRegistry.register(bot);
        BotTicker.register(bot);
        return new Done(id);
    }

    /**
     * Let {@code id} go: its running action ends as {@code bot_removed}, a mob gets its own AI back, and
     * a player bot leaves the world.
     */
    public static Outcome remove(String id) {
        Bot bot = BotRegistry.get(id);
        if (bot == null) return new Refusal("no bot named " + id + "; mc.bot.status lists the bots", true);
        BotRegistry.unregister(id);
        BotTicker.unregister(bot);
        if (bot instanceof PlayerBot player) player.fakePlayer().discard();
        return new Done(id);
    }
}

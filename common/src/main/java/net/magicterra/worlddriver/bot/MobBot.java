package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.config.BotConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.magicterra.worlddriver.game.body.MobBody;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * A mob under the bot stack: a {@link BotBrain} over a {@link MobBody}. It answers to
 * {@code mob:<name>} when {@code /worlddriver mob} adopted it under a name, or to
 * {@code entity:<uuid>} when an order named it by its UUID.
 *
 * <p>It stays adopted — goals off, reflexes running, ticking while idle — until it is released or its
 * entity is gone. Its tick throwing releases it too: nothing would drive it after, and a mob with its
 * goals taken off and no driver just stands there.
 *
 * <p>A mob has no hands, so the verbs that need them refuse it; the walk and the movement processes
 * run as they do for a player, at the mob's own speed. A mob whose own movement ignores the input
 * ends the task it was on with {@link #UNRESPONSIVE}.
 */
public final class MobBot extends ServerBot {

    public static final String KIND = "mob";

    /** The error a task ends with when held input moved the mob nowhere. */
    public static final String UNRESPONSIVE = "unresponsive: this mob does not move on the bot's input; "
            + "its own movement code ignores it";

    private final MobBody body;
    private final String id;

    private MobBot(MobBody body, String id) {
        super(body.entity());
        this.body = body;
        this.id = id;
    }

    /** Why {@code mob} cannot be taken over, or null when it can. */
    public static String refusal(Mob mob) {
        if (!MobBody.drivable(mob)) return "it has no AI (NoAI), so it never runs the step the bot's input goes through";
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        if (BotConfig.undrivableMobs.contains(type)) return type + " is listed in the undrivableMobs setting";
        return null;
    }

    /** Take {@code mob} over and address it as {@code id}. Register it with {@link BotRegistry} and {@link BotTicker}. */
    public static MobBot adopt(Mob mob, String id) {
        return new MobBot(MobBody.adopt(mob), id);
    }

    @Override public String id() { return id; }
    @Override public String kind() { return KIND; }
    @Override public MobBody body() { return body; }
    @Override public Mob entity() { return body.entity(); }

    /** The mob moves in its own AI step next tick; this hands it the input. */
    @Override protected void step() {
        body.apply();
        if (body.takeUnresponsive()) failTask(UNRESPONSIVE);
    }

    /** A mob walks at its own pace, too slow for any parkour gap, so it plans none. */
    @Override protected LevelWorldView view(Level level, LivingEntity entity) {
        return LevelWorldView.forMob(level, entity);
    }

    /** Off the tick list, goals back on: the mob runs itself again. */
    @Override public void release() {
        BotTicker.unregister(this);
        body.release();
    }

    /** A removed mob never comes back as the same entity, so the name it held is given up. */
    @Override public void entityRemoved() {
        if (BotRegistry.get(id) == this) BotRegistry.unregister(id);
    }

    @Override public void crashed(Throwable t) {
        super.crashed(t);
        body.release();
    }
}

package net.magicterra.worlddriver.bot;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.magicterra.worlddriver.api.game.EntityDriver;
import net.magicterra.worlddriver.api.game.EntityDrivers;
import net.magicterra.worlddriver.api.game.Mover;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Locomotion;
import net.magicterra.worlddriver.game.body.MobBody;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * A mob under the bot stack: a {@link BotBrain} over a {@link MobBody}, or over a {@link DriverBody} when another mod
 * registered an {@link EntityDriver} for the mob's type. It answers to {@code mob:<name>} when it was adopted under a
 * name, or to {@code entity:<uuid>} when an order named it by its UUID.
 *
 * <p>It stays adopted — goals off, reflexes running, ticking while idle — until it is released or its entity is gone.
 * Its tick throwing releases it too: nothing would drive it after, and a mob with its goals taken off and no driver
 * just stands there.
 *
 * <p>A mob has no hands, so the verbs that need them refuse it; the walk and the movement processes run as they do for
 * a player, at the mob's own speed. A mob whose own movement ignores the input ends the task it was on with
 * {@link #UNRESPONSIVE}.
 */
public final class MobBot extends ServerBot {

    public static final String KIND = "mob";

    /** The error a task ends with when held input moved the mob nowhere. */
    public static final String UNRESPONSIVE =
            "unresponsive: this mob does not move on the bot's input; " + "its own movement code ignores it";

    private final Body body;
    private final MobBody own;
    private final DriverBody registered;
    private final String id;

    private MobBot(Mob mob, MobBody own, DriverBody registered, String id) {
        super(mob);
        this.body = own != null ? own : registered;
        this.own = own;
        this.registered = registered;
        this.id = id;
    }

    /** Why {@code mob} cannot be taken over, or null when it can. A registered driver answers for its own type. */
    public static String refusal(Mob mob) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        if (BotConfig.undrivableMobs.contains(type)) return type + " is listed in the undrivableMobs setting";
        return EntityDrivers.has(mob.getType()) ? null : MobBody.undrivable(mob);
    }

    /**
     * Take {@code mob} over and address it as {@code id}. Register it with {@link BotRegistry} and {@link BotTicker}.
     */
    public static MobBot adopt(Mob mob, String id) {
        Optional<Function<Mob, EntityDriver>> factory = EntityDrivers.forType(mob.getType());
        if (factory.isPresent())
            return new MobBot(mob, null, new DriverBody(mob, factory.get().apply(mob)), id);
        return new MobBot(mob, MobBody.adopt(mob), null, id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Body body() {
        return body;
    }

    @Override
    public Mob entity() {
        return (Mob) body.entity();
    }

    /** The registered driver's own way of reaching a cell, when it has one. */
    Optional<Mover> mover() {
        return registered == null ? Optional.empty() : registered.driver().mover();
    }

    /** The mover of the mob bot driving {@code entity}, when there is one. Server thread. */
    static Optional<Mover> moverOf(LivingEntity entity) {
        for (Bot b : BotRegistry.all()) {
            if (b instanceof MobBot m && m.entity() == entity) return m.mover();
        }
        return Optional.empty();
    }

    /** The mob moves in its own AI step next tick; this hands it the input. */
    @Override
    protected void step() {
        boolean unresponsive;
        if (own != null) {
            own.apply();
            unresponsive = own.takeUnresponsive();
        } else {
            registered.apply();
            unresponsive = registered.driver().takeUnresponsive();
        }
        if (unresponsive) failTask(UNRESPONSIVE);
    }

    /** A mob walks at its own pace, too slow for any parkour gap, so it plans none. */
    @Override
    protected LevelWorldView view(Level level, LivingEntity entity) {
        Set<Locomotion.Mode> modes = registered == null ? null : DriverBody.modes(registered.driver());
        return LevelWorldView.forMob(level, entity, modes);
    }

    /** Off the tick list, goals back on: the mob runs itself again. */
    @Override
    public void release() {
        BotTicker.unregister(this);
        letGo();
    }

    /** A removed mob never comes back as the same entity, so the name it held is given up. */
    @Override
    public void entityRemoved() {
        if (BotRegistry.get(id) == this) BotRegistry.unregister(id);
    }

    @Override
    public void crashed(Throwable t) {
        super.crashed(t);
        letGo();
    }

    private void letGo() {
        if (own != null) own.release();
        else registered.driver().release();
    }
}

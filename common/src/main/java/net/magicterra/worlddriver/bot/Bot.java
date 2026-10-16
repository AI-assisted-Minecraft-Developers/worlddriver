package net.magicterra.worlddriver.bot;

import java.util.Map;

import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.control.Walker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.behavior.Readiness;

/**
 * A body and the stack that drives it, addressed by name: what the {@code mc.bot.*} verbs that take
 * {@code body} need from a controlled entity that is not this client's own player, and what
 * {@link BotTicker} advances once per server tick until it is unregistered or its entity is gone.
 *
 * <p>A bot runs one process at a time and the server tick advances it, so every method here is
 * called on the server thread. The client player is not one yet: {@code self} keeps going through
 * {@code BotApi}; both run their decisions through a {@link BotBrain}.
 *
 * <p>This was two interfaces, {@code BodyHost} (addressable) and {@code BodyDriver} (ticked), until
 * the layering rework; every implementation was both.
 */
public interface Bot {

    /** One server tick of driving. */
    Walker.Step tick();

    /** Whether the task it was last given has ended. A finished bot stays on {@link BotTicker}'s
     *  list and keeps ticking. */
    boolean finished();

    /**
     * {@link #tick} threw and {@link BotTicker} is dropping the bot: end whatever it held with the
     * error, or a caller waiting on it waits until its own deadline for an ending that never comes.
     */
    default void crashed(Throwable t) {}

    /** {@code player:<name>} or {@code npc:<name>}. */
    String id();

    /** {@code player} or {@code npc}. */
    String kind();

    /** The {@link Body} its processes drive; its capabilities ({@link Body#equipment()}, {@link Body#attack()}, ...) are what the hand verbs act through. */
    Body body();

    /** The entity, or null once there is none. */
    LivingEntity entity();

    /** The process slots, the same shape the client reports. */
    BotState botState();

    /** Whether a process is running. */
    boolean busy();

    /** {@code {kind, error}} of the last process ending, or null: the bot's {@code lastProcessEnd},
     *  with {@code error} null only when the process did what it was asked. */
    Map<String, Object> lastProcessEnd();

    /** Run {@code process}, superseding whatever was running. */
    default void start(BotProcess process) { start(process, null); }

    /**
     * Run {@code process} for the accepted {@code action} (null: none), superseding whatever was
     * running; the host's executor ends the action with the process, however it ends.
     */
    void start(BotProcess process, ActionHandle action);

    /** Stop the running process if it still carries {@code action}; false when it has left. */
    boolean cancelAction(ActionHandle action, String reason);

    /**
     * Cancel the running process when its kind is {@code process}, or when {@code process} is
     * {@code all}. Returns the kind cancelled, or null when nothing matched.
     */
    String cancel(String process);

    /**
     * Why the bot cannot take an order now, as {@code {ok:false, error, reason}}, or null. The same
     * words the client's {@code Readiness} uses, judged on the entity instead of on a screen.
     */
    default Map<String, Object> refusal() {
        LivingEntity e = entity();
        // A removed entity that was not killed has most often gone to disk with its chunk; the id
        // still names it, so this is not unknown_bot.
        if (e == null || (e.isRemoved() && e.getRemovalReason() != Entity.RemovalReason.KILLED)) {
            return refuse(Readiness.Reason.CHUNK_UNLOADED, "bot " + id() + " is not in a loaded chunk");
        }
        if (!e.isAlive()) return refuse(Readiness.Reason.DEAD, "bot " + id() + " is dead");
        if (!e.level().hasChunkAt(e.blockPosition())) {
            return refuse(Readiness.Reason.CHUNK_UNLOADED, "the chunk under bot " + id() + " is not loaded");
        }
        return null;
    }

    /** A refusal in the shape {@code Readiness.Refusal} gives. */
    static Map<String, Object> refuse(String reason, String error) {
        return new Readiness.Refusal(reason, error).result();
    }
}

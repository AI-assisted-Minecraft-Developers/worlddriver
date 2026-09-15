package net.magicterra.worlddriver.bot.body;

import java.util.LinkedHashMap;
import java.util.Map;

import net.magicterra.worlddriver.bot.BodyReady;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * A body the API addresses by name: what the {@code mc.bot.*} verbs that take {@code body} need from
 * a body that is not this client's own.
 *
 * <p>A host runs one process at a time and the server tick advances it, so every method here is
 * called on the server thread. The client's body is not a host. {@code self} keeps going through
 * {@code BotApi}, with the scheduler, chains and reflexes a host does not have.
 */
public interface BodyHost {

    /** {@code player:<name>} or {@code npc:<name>}. */
    String id();

    /** {@code player} or {@code npc}. */
    String kind();

    /** The body its processes drive; its {@link Body#hands()} is what the hand verbs act through. */
    Body body();

    /** The entity, or null once there is none. */
    LivingEntity entity();

    /** The process slots, the same shape the client reports. */
    BotState botState();

    /** Whether a process is running. */
    boolean busy();

    /** Run {@code process}, superseding whatever was running. */
    void start(BotProcess process);

    /**
     * Cancel the running process when its kind is {@code process}, or when {@code process} is
     * {@code all}. Returns the kind cancelled, or null when nothing matched.
     */
    String cancel(String process);

    /**
     * Why the body cannot take an order now, as {@code {ok:false, error, reason}}, or null. The same
     * words the client's {@code BodyReady} uses, judged on the entity instead of on a screen.
     */
    default Map<String, Object> refusal() {
        LivingEntity e = entity();
        // A removed entity that was not killed has most often gone to disk with its chunk; the id
        // still names it, so this is not unknown_body.
        if (e == null || (e.isRemoved() && e.getRemovalReason() != Entity.RemovalReason.KILLED)) {
            return refuse(BodyReady.Reason.CHUNK_UNLOADED, "body " + id() + " is not in a loaded chunk");
        }
        if (!e.isAlive()) return refuse(BodyReady.Reason.DEAD, "body " + id() + " is dead");
        if (!e.level().hasChunkAt(e.blockPosition())) {
            return refuse(BodyReady.Reason.CHUNK_UNLOADED, "the chunk under body " + id() + " is not loaded");
        }
        return null;
    }

    /** A refusal in the shape {@code BodyReady.Refusal} gives, built without loading that client class. */
    static Map<String, Object> refuse(String reason, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        m.put("reason", reason);
        return m;
    }
}

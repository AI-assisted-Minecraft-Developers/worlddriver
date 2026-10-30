package net.magicterra.worlddriver.api.game;

import net.minecraft.world.entity.LivingEntity;

/**
 * One running body task another mod registered with {@link BodyTasks}: the bot calls {@link #tick} once a game tick, on
 * the body's own game thread, until it answers that the task ended. Being the body's task, it is superseded by the next
 * one and cancelled by {@code mc.bot.cancel}, and its result answers {@code mc.wait.result}.
 */
public interface TaskStep<R> {
    enum Progress {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    /** Advance by one tick. {@code body} is the bot's entity now, which a respawn replaces. */
    Progress tick(LivingEntity body);

    /** What a task that {@link Progress#SUCCEEDED} reports, read once after it ended. */
    R result();

    /** Why a task that {@link Progress#FAILED} failed; the action's failure message. */
    default String failure() {
        return "failed";
    }

    /** The task was cancelled or superseded: let go of whatever it holds. */
    default void stop() {}
}

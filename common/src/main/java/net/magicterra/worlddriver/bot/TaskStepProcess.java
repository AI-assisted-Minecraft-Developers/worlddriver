package net.magicterra.worlddriver.bot;

import net.magicterra.worlddriver.api.game.BodyTasks;
import net.magicterra.worlddriver.api.game.TaskStep;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.world.entity.LivingEntity;

/**
 * A task another mod registered with {@link BodyTasks}, run as the body's process. Its kind is the method's name, so
 * {@code mc.bot.status} reports it by that; it has no slot of its own, and {@code mc.bot.cancel} stops it by its action
 * or with everything else.
 */
public final class TaskStepProcess implements BotProcess {
    private final String kind;

    private final TaskStep<?> step;

    private String failure;

    TaskStepProcess(String kind, TaskStep<?> step) {
        this.kind = kind;
        this.step = step;
    }

    /** The order {@code entry} gives for {@code params} on {@code self}: its task, or the refusal its factory threw. */
    public static <P extends BodyTasks.Params, R> VerbOrders.Order<R> order(
            BodyTasks.Entry<P, R> entry, P params, LivingEntity self) {
        TaskStep<R> step;
        try {
            step = entry.factory().start(params, self);
        } catch (BodyTasks.Refusal r) {
            return new VerbOrders.Refuse<>(r.reason(), r.getMessage());
        }
        return new VerbOrders.Start<>(new TaskStepProcess(entry.spec().name(), step), step::result);
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public void attach(BotState st) {}

    @Override
    public boolean tick(Body body, WorldView world, BotState st) {
        return switch (step.tick(body.entity())) {
            case RUNNING -> false;
            case SUCCEEDED -> true;
            case FAILED -> {
                failure = step.failure();
                yield true;
            }
        };
    }

    @Override
    public String failure() {
        return failure;
    }

    @Override
    public void onCancelled(String reason) {
        step.stop();
    }
}

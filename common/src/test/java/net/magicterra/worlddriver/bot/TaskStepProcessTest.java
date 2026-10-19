package net.magicterra.worlddriver.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import net.magicterra.worlddriver.api.game.BodyTasks;
import net.magicterra.worlddriver.api.game.TaskStep;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.methods.shared.Responses.AwaitMs;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

/** A registered task run as the body's process: its answers end the process, a cancel stops it, a refusal starts nothing. */
class TaskStepProcessTest {

    record Params(@Default("self") @BotRef String bot, @Default("0") @AwaitMs int awaitMs) implements BodyTasks.Params {}

    private static final ApiType<Params> PARAMS = ApiTypes.record(Params.class);

    /** A body whose every method answers null: the step under test never reads it. */
    private static final Body BODY = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[]{Body.class},
            (p, m, a) -> null);

    /** Answers RUNNING {@code ticks} times, then {@code end}. */
    private static final class Scripted implements TaskStep<String> {
        final TaskStep.Progress end;
        int left;
        boolean stopped;

        Scripted(int ticks, TaskStep.Progress end) { this.left = ticks; this.end = end; }

        @Override public Progress tick(LivingEntity body) { return left-- > 0 ? Progress.RUNNING : end; }
        @Override public String result() { return "done"; }
        @Override public String failure() { return "stuck"; }
        @Override public void stop() { stopped = true; }
    }

    /** The order a task named {@code name} gives; only the spec's name is read, so its types are placeholders. */
    private static VerbOrders.Order<String> order(String name, BodyTasks.Factory<Params, String> factory) {
        MethodSpec<Params, ActionResponse<String>> spec = MethodSpec.of(name, PARAMS,
                Responses.response("UnitStep", ApiTypes.of(String.class)), "");
        return TaskStepProcess.order(new BodyTasks.Entry<>(spec, factory), new Params("self", 0), null);
    }

    private static VerbOrders.Order<String> order(TaskStep<String> step) {
        return order("unittest.step", (p, body) -> step);
    }

    private static int run(TaskStepProcess p, BotState st) {
        p.attach(st);
        for (int t = 1; t <= 100; t++) if (p.tick(BODY, null, st)) return t;
        return -1;
    }

    @Test
    void aSuccessEndsTheProcessAndReportsTheResult() {
        VerbOrders.Start<String> s = assertInstanceOf(VerbOrders.Start.class, order(new Scripted(2, TaskStep.Progress.SUCCEEDED)));
        TaskStepProcess p = (TaskStepProcess) s.process();
        assertEquals("unittest.step", p.kind());
        assertEquals(3, run(p, new BotState()));
        assertNull(p.failure());
        assertEquals("done", s.result().get());
    }

    @Test
    void aFailureEndsItWithTheStepsReason() {
        VerbOrders.Start<String> s = assertInstanceOf(VerbOrders.Start.class, order(new Scripted(0, TaskStep.Progress.FAILED)));
        TaskStepProcess p = (TaskStepProcess) s.process();
        assertEquals(1, run(p, new BotState()));
        assertEquals("stuck", p.failure());
    }

    @Test
    void aCancelStopsTheStep() {
        Scripted step = new Scripted(50, TaskStep.Progress.SUCCEEDED);
        VerbOrders.Start<String> s = assertInstanceOf(VerbOrders.Start.class, order(step));
        s.process().onCancelled("user-cancel");
        assertTrue(step.stopped);
    }

    @Test
    void aRefusingFactoryStartsNothing() {
        VerbOrders.Order<String> o = order("unittest.refuses", (p, body) -> {
            throw new BodyTasks.Refusal(RefusalReason.INVALID_WORLD_TARGET, "no such thing");
        });
        VerbOrders.Refuse<String> r = assertInstanceOf(VerbOrders.Refuse.class, o);
        assertEquals(List.of(RefusalReason.INVALID_WORLD_TARGET, "no such thing"), List.of(r.reason(), r.message()));
    }
}

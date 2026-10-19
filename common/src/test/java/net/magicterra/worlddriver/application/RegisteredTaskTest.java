package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntFunction;

import net.magicterra.worlddriver.api.game.BodyTasks;
import net.magicterra.worlddriver.api.game.TaskStep;
import net.magicterra.worlddriver.api.methods.shared.ActionResults;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.wait.WaitContracts;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Variant;
import net.magicterra.worlddriver.api.methods.shared.Responses.AwaitMs;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

/**
 * A body task another mod registers: published as a method by every driver, before or after it exists, and
 * its result answerable by {@code mc.wait.result}. The registry is process-wide, so each test uses its own names.
 */
class RegisteredTaskTest {

    record Params(@Default("self") @BotRef String bot, @Default("0") @AwaitMs int awaitMs) implements BodyTasks.Params {}

    // A result's kind is its record's, so every registered kind has a record of its own.
    @Variant("unitBefore") record Before(int n) {}
    @Variant("unitAfter") record After(int n) {}
    @Variant("unitAnswers") record Answers(int n) {}
    @Variant("unitOnce") record Once(int n) {}
    @Variant("unitOnce") record Twin(int n) {}
    @Variant("explore") record Explorer(int n) {}
    @Variant("unitOther") record Other(int n) {}

    private static final ApiType<Params> PARAMS = ApiTypes.record(Params.class);

    private static <R extends Record> MethodSpec<Params, ActionResponse<R>> register(String name, Class<R> type,
                                                                                  IntFunction<R> make) {
        UnionType<R> result = ApiTypes.union(null, "kind", ApiTypes.record(type));
        return BodyTasks.register(name, PARAMS, result, "a test task", (p, body) -> new TaskStep<>() {
            @Override public Progress tick(LivingEntity self) { return Progress.SUCCEEDED; }
            @Override public R result() { return make.apply(1); }
        });
    }

    @Test
    void aDriverPublishesTasksRegisteredBeforeAndAfterIt() {
        MethodSpec<?, ?> before = register("unittest.before", Before.class, Before::new);
        DriverApplication app = new DriverApplication();
        assertNotNull(app.registry().method("unittest.before"));
        register("unittest.after", After.class, After::new);
        assertNotNull(app.registry().method("unittest.after"));
        assertEquals("unittest", app.registry().snapshot().ownerOf().get("unittest.after").split("/")[0]);
        assertTrue(before.effects().contains(MethodSpec.Effect.BODY_TASK) && before.blocking() == MethodSpec.Blocking.MAY_WAIT);
    }

    @Test
    void itsResultAnswersAWaitForAnyAction() {
        register("unittest.answers", Answers.class, Answers::new);
        String json = String.valueOf(WaitContracts.ANY_ACTION.encode(new Responses.Succeeded<>("a1", "self", new Answers(3))));
        assertEquals("{\"state\":\"succeeded\",\"actionId\":\"a1\",\"bot\":\"self\",\"result\":{\"kind\":\"unitAnswers\",\"n\":3}}", json);
    }

    @Test
    void aTakenNameKindOrClassOrAnMcNameRegistersNothing() {
        register("unittest.once", Once.class, Once::new);
        int kinds = ActionResults.UNION.variants().size();
        assertThrows(IllegalArgumentException.class, () -> register("unittest.once", Other.class, Other::new));
        assertThrows(IllegalArgumentException.class, () -> register("unittest.twice", Twin.class, Twin::new));
        assertThrows(IllegalArgumentException.class, () -> register("unittest.explore", Explorer.class, Explorer::new));
        assertThrows(IllegalArgumentException.class, () -> register("unittest.sameClass", Once.class, Once::new));
        assertThrows(IllegalArgumentException.class, () -> register("mc.bot.dance", Other.class, Other::new));
        assertThrows(IllegalArgumentException.class, () -> register("nodot", Other.class, Other::new));
        assertEquals(kinds, ActionResults.UNION.variants().size());
        assertFalse(BodyTasks.all().stream().anyMatch(e -> e.spec().name().equals("unittest.twice")));
    }
}

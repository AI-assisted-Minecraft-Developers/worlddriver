package net.magicterra.worlddriver.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import net.magicterra.worlddriver.api.game.BodyTasks;
import net.magicterra.worlddriver.api.game.TaskStep;
import net.magicterra.worlddriver.api.methods.shared.ActionResults;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.wait.WaitContracts;
import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

/**
 * A body task another mod registers: published as a method by every driver, before or after it exists, and
 * its result answerable by {@code mc.wait.result}. The registry is process-wide, so each test uses its own names.
 */
class RegisteredTaskTest {

    record Params(String bot, int awaitMs) implements BodyTasks.Params {}

    // A union picks a result's variant by its class, so every registered kind has a record of its own.
    record Before(int n) {}
    record After(int n) {}
    record Answers(int n) {}
    record Once(int n) {}
    record Other(int n) {}

    private static final ApiType<Params> PARAMS = ApiTypes.record(Params.class, null,
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(Params::bot),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(Params::awaitMs),
            Params::new);

    private static <R extends Record> MethodSpec<Params, ActionResponse<R>> register(
            String name, String kind, Class<R> type, IntFunction<R> make, ToIntFunction<R> n) {
        UnionType<R> result = ApiTypes.taggedUnion(null, "kind", ApiTypes.variant(kind, type, ApiTypes.record(type, null,
                ApiField.field("n", ApiTypes.integer()).required().forGetter(n::applyAsInt), make::apply)));
        return BodyTasks.register(name, PARAMS, result, "a test task", (p, body) -> new TaskStep<>() {
            @Override public Progress tick(LivingEntity self) { return Progress.SUCCEEDED; }
            @Override public R result() { return make.apply(1); }
        });
    }

    @Test
    void aDriverPublishesTasksRegisteredBeforeAndAfterIt() {
        MethodSpec<?, ?> before = register("unittest.before", "unitBefore", Before.class, Before::new, Before::n);
        DriverApplication app = new DriverApplication();
        assertNotNull(app.registry().method("unittest.before"));
        register("unittest.after", "unitAfter", After.class, After::new, After::n);
        assertNotNull(app.registry().method("unittest.after"));
        assertEquals("unittest", app.registry().snapshot().ownerOf().get("unittest.after").split("/")[0]);
        assertTrue(before.effects().contains(MethodSpec.Effect.BODY_TASK) && before.blocking() == MethodSpec.Blocking.MAY_WAIT);
    }

    @Test
    void itsResultAnswersAWaitForAnyAction() {
        register("unittest.answers", "unitAnswers", Answers.class, Answers::new, Answers::n);
        String json = String.valueOf(WaitContracts.ANY_ACTION.encode(new Responses.Succeeded<>("a1", "self", new Answers(3))));
        assertEquals("{\"state\":\"succeeded\",\"actionId\":\"a1\",\"bot\":\"self\",\"result\":{\"kind\":\"unitAnswers\",\"n\":3}}", json);
    }

    @Test
    void aTakenNameKindOrClassOrAnMcNameRegistersNothing() {
        register("unittest.once", "unitOnce", Once.class, Once::new, Once::n);
        int kinds = ActionResults.UNION.variants().size();
        assertThrows(IllegalArgumentException.class, () -> register("unittest.once", "unitOnceAgain", Other.class, Other::new, Other::n));
        assertThrows(IllegalArgumentException.class, () -> register("unittest.twice", "unitOnce", Other.class, Other::new, Other::n));
        assertThrows(IllegalArgumentException.class, () -> register("unittest.explore", "explore", Other.class, Other::new, Other::n));
        assertThrows(IllegalArgumentException.class, () -> register("unittest.sameClass", "unitSame", Once.class, Once::new, Once::n));
        assertThrows(IllegalArgumentException.class, () -> register("mc.bot.dance", "unitDance", Other.class, Other::new, Other::n));
        assertThrows(IllegalArgumentException.class, () -> register("nodot", "unitNoDot", Other.class, Other::new, Other::n));
        assertEquals(kinds, ActionResults.UNION.variants().size());
        assertFalse(BodyTasks.all().stream().anyMatch(e -> e.spec().name().equals("unittest.twice")));
    }
}

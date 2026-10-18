package net.magicterra.worlddriver.testcontent;

import net.magicterra.worlddriver.api.game.BodyTasks;
import net.magicterra.worlddriver.api.game.TaskStep;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.minecraft.world.entity.LivingEntity;

/** A body task registered as another mod would register one: {@code testmod.tally} counts ticks on its bot. */
public final class StandInTasks {
    private StandInTasks() {}

    public record TallyParams(String bot, int ticks, int awaitMs) implements BodyTasks.Params {}

    public record Tallied(int ticks, boolean onGround) {}

    private static final ApiType<TallyParams> PARAMS = ApiTypes.record(TallyParams.class, "TallyParams",
            ApiField.field("bot", Responses.BOT_REF).defaulted("self").forGetter(TallyParams::bot),
            ApiField.field("ticks", ApiTypes.integer(1, 200)).required().forGetter(TallyParams::ticks),
            ApiField.field("awaitMs", Responses.AWAIT_MS).defaulted(0).forGetter(TallyParams::awaitMs),
            TallyParams::new);

    private static final UnionType<Tallied> RESULT = ApiTypes.taggedUnion("TallyResult", "kind",
            ApiTypes.variant("tally", Tallied.class, ApiTypes.record(Tallied.class, null,
                    ApiField.field("ticks", ApiTypes.integer(1, 200)).required().forGetter(Tallied::ticks),
                    ApiField.field("onGround", ApiTypes.bool()).required().forGetter(Tallied::onGround),
                    Tallied::new)));

    public static MethodSpec<TallyParams, ActionResponse<Tallied>> TALLY;

    public static void register() {
        TALLY = BodyTasks.register("testmod.tally", PARAMS, RESULT, "Count ticks on the bot, then succeed.",
                (p, body) -> new TaskStep<>() {
                    int counted;
                    boolean onGround;

                    @Override public Progress tick(LivingEntity self) {
                        onGround = self.onGround();
                        return ++counted >= p.ticks() ? Progress.SUCCEEDED : Progress.RUNNING;
                    }

                    @Override public Tallied result() { return new Tallied(counted, onGround); }
                });
    }
}

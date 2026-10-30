package net.magicterra.worlddriver.testcontent;

import net.magicterra.worlddriver.api.game.BodyTasks;
import net.magicterra.worlddriver.api.game.TaskStep;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.AwaitMs;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Variant;
import net.minecraft.world.entity.LivingEntity;

/** A body task registered as another mod would register one: {@code testmod.tally} counts ticks on its bot. */
public final class StandInTasks {
    private StandInTasks() {}

    @Named("TallyParams")
    public record TallyParams(
            @Default("self") @BotRef String bot,
            @Range(min = 1, max = 200) int ticks,
            @Default("0") @AwaitMs int awaitMs)
            implements BodyTasks.Params {}

    @Variant("tally")
    public record Tallied(@Range(min = 1, max = 200) int ticks, boolean onGround) {}

    private static final ApiType<TallyParams> PARAMS = ApiTypes.record(TallyParams.class);

    private static final UnionType<Tallied> RESULT =
            ApiTypes.union("TallyResult", "kind", ApiTypes.record(Tallied.class));

    public static MethodSpec<TallyParams, ActionResponse<Tallied>> TALLY;

    public static void register() {
        TALLY = BodyTasks.register(
                "testmod.tally",
                PARAMS,
                RESULT,
                "Count ticks on the bot, then succeed.",
                (p, body) -> new TaskStep<>() {
                    int counted;
                    boolean onGround;

                    @Override
                    public Progress tick(LivingEntity self) {
                        onGround = self.onGround();
                        return ++counted >= p.ticks() ? Progress.SUCCEEDED : Progress.RUNNING;
                    }

                    @Override
                    public Tallied result() {
                        return new Tallied(counted, onGround);
                    }
                });
    }
}

package net.magicterra.worlddriver.api.methods.bot;

import java.util.Locale;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.EntityId;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.AwaitMs;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * {@code mc.bot.combat}: fight. On {@code self} the fight belongs to the combat reflex chain, which a retreat or dodge
 * may interrupt and resume without ending the action; on any other bot it is the body's held process like every other
 * task.
 */
public final class CombatContracts {
    private CombatContracts() {}

    @Named("CombatMode")
    public enum Mode implements WireName {
        /** Clear every hostile in range. */
        ENGAGE,
        /** Retaliate only against mobs targeting the body. */
        DEFEND,
        /** One target, named by {@code target}. */
        KILL;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Tag("kind")
    @Named("CombatTarget")
    public sealed interface Foe permits FoeEntity, FoeType {}

    @Variant("entity")
    public record FoeEntity(@EntityId int entityId) implements Foe {}
    /** The nearest entity of this type. */
    @Variant("entityType")
    public record FoeType(@ResourceId String type) implements Foe {}

    @Named("CombatParams")
    public record Params(
            @Default("self") @BotRef String bot,
            @Default("engage") Mode mode,
            @Doc("kill only, and required there.") Optional<Foe> target,

            @Doc("Fight even at or below the combatFrailThreshold health, which otherwise fails the action before a "
                    + "swing. self only: no other bot has that gate.")
            @Default("false")
            boolean force,

            @Default("0") @AwaitMs int awaitMs)
            implements SelfChecked {
        @Override
        public void check(DecodeContext at) {
            if (mode == Mode.KILL && target.isEmpty()) {
                at.field("target").fail(ApiIssue.Code.REQUIRED, "kill needs a target");
            }
            if (mode != Mode.KILL && target.isPresent()) {
                at.field("target").fail(ApiIssue.Code.INVALID_VALUE, mode.wireName() + " picks its own targets");
            }
        }
    }

    @Variant("combat")
    public record Fought(Mode mode, Optional<Foe> target) {}

    public static final ApiType<Mode> MODE = ApiTypes.of(Mode.class);

    public static final UnionType<Foe> FOE = ApiTypes.union(Foe.class);

    public static final ApiType<Params> PARAMS = ApiTypes.record(Params.class);

    public static final UnionType<Fought> RESULT =
            ApiTypes.union("CombatResult", "kind", ApiTypes.record(Fought.class));

    public static final MethodSpec<Params, ActionResponse<Fought>> SPEC = MethodSpec.of(
                    "mc.bot.combat",
                    PARAMS,
                    Responses.response("Combat", RESULT),
                    "Fight hostiles until the target dies or the area is clear: close to weapon range and swing only "
                            + "at full attack strength, jumping for crits; with a bow or crossbow in hand keep the "
                            + "kiteDistance setting and fire at full draw. On self it outranks goto and mine, which "
                            + "resume afterwards; the autoFight setting does this unasked.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();
}

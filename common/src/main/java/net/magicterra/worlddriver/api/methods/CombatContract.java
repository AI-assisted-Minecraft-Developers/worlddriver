package net.magicterra.worlddriver.api.methods;

import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.contract.WireName;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionResponse;

/**
 * {@code mc.bot.combat}: fight. On {@code self} the fight belongs to the combat reflex chain, which a
 * retreat or dodge may interrupt and resume without ending the action; on any other bot it is the
 * body's held process like every other task.
 */
public final class CombatContract {
    private CombatContract() {}

    public enum Mode implements WireName {
        /** Clear every hostile in range. */
        ENGAGE,
        /** Retaliate only against mobs targeting the body. */
        DEFEND,
        /** One target, named by {@code target}. */
        KILL;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public sealed interface Foe permits FoeEntity, FoeType {}
    public record FoeEntity(int entityId) implements Foe {}
    /** The nearest entity of this type. */
    public record FoeType(String type) implements Foe {}

    public record Params(String body, Mode mode, Optional<Foe> target, boolean force, int awaitMs) {}

    public record Fought(Mode mode, Optional<Foe> target) {}

    public static final ApiType<Mode> MODE = ApiTypes.enumOf("CombatMode", Mode.class);

    public static final UnionType<Foe> FOE = ApiTypes.taggedUnion("CombatTarget", "kind",
            ApiTypes.variant("entity", FoeEntity.class, ApiTypes.record(null,
                    ApiField.field("entityId", CommonTypes.ENTITY_ID).required().forGetter(FoeEntity::entityId),
                    FoeEntity::new)),
            ApiTypes.variant("entityType", FoeType.class, ApiTypes.record(null,
                    ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(FoeType::type),
                    FoeType::new)));

    public static final ApiType<Params> PARAMS = ApiTypes.record("CombatParams",
            ApiField.field("body", ActionContract.BODY_REF).defaulted("self").forGetter(Params::body),
            ApiField.field("mode", MODE).defaulted(Mode.ENGAGE).forGetter(Params::mode),
            ApiField.field("target", FOE).describe("kill only, and required there.").optional().forGetter(Params::target),
            ApiField.field("force", ApiTypes.bool())
                    .describe("Fight even at or below the combatFrailThreshold health, which otherwise fails the action "
                            + "before a swing. self only: no other bot has that gate.")
                    .defaulted(false).forGetter(Params::force),
            ApiField.field("awaitMs", ActionContract.AWAIT_MS).defaulted(0).forGetter(Params::awaitMs),
            Params::new)
            .checked((p, at) -> {
                if (p.mode() == Mode.KILL && p.target().isEmpty()) {
                    at.field("target").fail(ApiIssue.Code.REQUIRED, "kill needs a target");
                }
                if (p.mode() != Mode.KILL && p.target().isPresent()) {
                    at.field("target").fail(ApiIssue.Code.INVALID_VALUE, p.mode().wireName() + " picks its own targets");
                }
            });

    public static final UnionType<Fought> RESULT = ApiTypes.taggedUnion("CombatResult", "kind",
            ApiTypes.variant("combat", Fought.class, ApiTypes.record(null,
                    ApiField.field("mode", MODE).required().forGetter(Fought::mode),
                    ApiField.field("target", FOE).optional().forGetter(Fought::target),
                    Fought::new)));

    public static final MethodSpec<Params, ActionResponse<Fought>> SPEC = MethodSpec.of("mc.bot.combat", PARAMS,
                    ActionContract.response("Combat", RESULT),
                    "Fight hostiles until the target dies or the area is clear: close to weapon range and swing only "
                            + "at full attack strength, jumping for crits; with a bow or crossbow in hand keep the "
                            + "kiteDistance setting and fire at full draw. On self it outranks goto and mine, which "
                            + "resume afterwards; the autoFight setting does this unasked.")
            .effects(MethodSpec.Effect.BODY_TASK, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();
}

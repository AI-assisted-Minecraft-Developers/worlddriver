package net.magicterra.worlddriver.api.methods.shared;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.OpenUnion;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;

/**
 * Every action result there is, as one union on {@code kind}: what {@code mc.wait.result} answers
 * for an action of any method. Each method's own result union contributes its variants, so a new
 * body-task method is one line here; a task another mod registers adds its own through {@link #add}.
 */
public final class ActionResults {
    private ActionResults() {}

    private static final List<UnionType<?>> PER_METHOD = List.of(
            ClearAreaContracts.SUMMARY,
            GotoContracts.RESULT,
            GatherContracts.MINE_RESULT,
            GatherContracts.CRAFT_RESULT,
            GatherContracts.SMELT_RESULT,
            GatherContracts.FARM_RESULT,
            BuildContracts.BUILD_RESULT,
            BuildContracts.CONSTRUCT_RESULT,
            BuildContracts.BUNKER_RESULT,
            BuildContracts.ESCAPE_RESULT,
            MoveContracts.FOLLOW_RESULT,
            MoveContracts.EXPLORE_RESULT,
            MoveContracts.RUN_AWAY_RESULT,
            MoveContracts.SLEEP_RESULT,
            MoveContracts.ELYTRA_RESULT,
            CombatContracts.RESULT,
            InteractContracts.LOOK_RESULT);

    public static final OpenUnion<Object> UNION = ApiTypes.openUnion(union());

    /** Make {@code result}'s variants answerable too. Throws, adding nothing, on a kind already taken. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void add(UnionType<?> result) { UNION.add((UnionType) result); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static UnionType<Object> union() {
        List<UnionType.Variant> all = new ArrayList<>();
        for (UnionType<?> u : PER_METHOD) {
            for (UnionType.Variant<?, ?> v : u.variants()) all.add(ApiTypes.variant(v.tag(), (Class) v.javaType(), v.type()));
        }
        return ApiTypes.taggedUnion("ActionResult", "kind", all.toArray(new UnionType.Variant[0]));
    }
}

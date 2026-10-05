package net.magicterra.worlddriver.api.methods;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.UnionType;

/**
 * Every action result there is, as one union on {@code kind}: what {@code mc.wait.result} answers
 * for an action of any method. Each method's own result union contributes its variants, so a new
 * body-task method is one line here.
 */
public final class ActionResults {
    private ActionResults() {}

    private static final List<UnionType<?>> PER_METHOD = List.of(
            ClearAreaContract.SUMMARY);

    public static final UnionType<Object> UNION = union();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static UnionType<Object> union() {
        List<UnionType.Variant> all = new ArrayList<>();
        for (UnionType<?> u : PER_METHOD) {
            for (UnionType.Variant<?, ?> v : u.variants()) all.add(ApiTypes.variant(v.tag(), (Class) v.javaType(), v.type()));
        }
        return ApiTypes.taggedUnion("ActionResult", "kind", all.toArray(new UnionType.Variant[0]));
    }
}

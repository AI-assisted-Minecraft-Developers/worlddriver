package net.magicterra.worlddriver.api.methods.query;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/** {@code mc.query}: the blocks or entities around a point that match a filter. */
public interface QueryApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static QueryStubApi of(ApiRegistry registry) {
        return QueryApiBinding.calls(registry);
    }

    /** The non-air blocks or the entities in a cube around a center; blocks read only loaded chunks. */
    @Spec(QueryContracts.class)
    CompletionStage<QueryContracts.Rows> query(QueryContracts.Query q, CallContext ctx);
}

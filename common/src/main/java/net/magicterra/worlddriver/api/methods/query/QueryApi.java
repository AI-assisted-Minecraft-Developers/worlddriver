package net.magicterra.worlddriver.api.methods.query;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/** {@code mc.query}: the blocks or entities around a point that match a filter. */
public interface QueryApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static QueryApi of(ApiRegistry registry) { return QueryApiBinding.calls(registry); }

    @Spec(QueryContracts.class)
    CompletionStage<QueryContracts.Rows> query(QueryContracts.Query q, CallContext ctx);
}

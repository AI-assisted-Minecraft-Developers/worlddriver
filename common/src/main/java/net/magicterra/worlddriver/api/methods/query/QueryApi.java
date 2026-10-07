package net.magicterra.worlddriver.api.methods.query;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;

/** {@code mc.query}: the blocks or entities around a point that match a filter. */
public interface QueryApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static QueryApi of(DriverApi api) { return QueryApiBinding.calls(api); }

    @Spec(QueryContracts.class)
    CompletionStage<QueryContracts.Rows> query(QueryContracts.Query q, CallContext ctx);
}

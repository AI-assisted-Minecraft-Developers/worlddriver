package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;

/** {@code mc.query}: the blocks or entities around a point that match a filter. */
public interface QueryCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static QueryCalls of(DriverApi api) { return QueryCallsBinding.calls(api); }

    @Api(QueryContract.class)
    CompletionStage<QueryContract.Rows> query(QueryContract.Query q, CallContext ctx);
}

package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;

/** The recipe book and the plans built from it: {@code mc.recipe.*} and {@code mc.plan.acquire}. */
public interface RecipeCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static RecipeCalls of(DriverApi api) { return RecipeCallsBinding.calls(api); }

    @Api(value = RecipeContracts.class, spec = "LOOKUP")
    CompletionStage<RecipeContracts.Recipes> lookup(RecipeContracts.LookupParams p, CallContext ctx);

    @Api(value = RecipeContracts.class, spec = "RESOLVE")
    CompletionStage<RecipeContracts.CraftPlan> resolve(RecipeContracts.PlanParams p, CallContext ctx);

    @Api(value = RecipeContracts.class, spec = "ACQUIRE")
    CompletionStage<RecipeContracts.AcquirePlan> planAcquire(RecipeContracts.PlanParams p, CallContext ctx);
}

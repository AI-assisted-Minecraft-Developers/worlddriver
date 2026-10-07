package net.magicterra.worlddriver.api.methods.recipe;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.Spec;

/** The recipe book and the plans built from it: {@code mc.recipe.*} and {@code mc.plan.acquire}. */
public interface RecipeApi {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static RecipeApi of(DriverApi api) { return RecipeApiBinding.calls(api); }

    @Spec(value = RecipeContracts.class, spec = "LOOKUP")
    CompletionStage<RecipeContracts.Recipes> lookup(RecipeContracts.LookupParams p, CallContext ctx);

    @Spec(value = RecipeContracts.class, spec = "RESOLVE")
    CompletionStage<RecipeContracts.CraftPlan> resolve(RecipeContracts.PlanParams p, CallContext ctx);

    @Spec(value = RecipeContracts.class, spec = "ACQUIRE")
    CompletionStage<RecipeContracts.AcquirePlan> planAcquire(RecipeContracts.PlanParams p, CallContext ctx);
}

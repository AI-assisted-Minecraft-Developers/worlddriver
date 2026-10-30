package net.magicterra.worlddriver.api.methods.recipe;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/** The recipe book and the plans built from it: {@code mc.recipe.*} and {@code mc.plan.acquire}. */
public interface RecipeApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static RecipeStubApi of(ApiRegistry registry) {
        return RecipeApiBinding.calls(registry);
    }

    /** {@code mc.recipe.lookup}: finds recipes by what they make or by what they consume. */
    @Spec(value = RecipeContracts.class, spec = "LOOKUP")
    CompletionStage<RecipeContracts.Recipes> lookup(RecipeContracts.LookupParams p, CallContext ctx);

    /** {@code mc.recipe.resolve}: expands a wanted item into an ordered craft plan and the raw materials missing. */
    @Spec(value = RecipeContracts.class, spec = "RESOLVE")
    CompletionStage<RecipeContracts.CraftPlan> resolve(RecipeContracts.PlanParams p, CallContext ctx);

    /** {@code mc.plan.acquire}: a resolve plus how to mine, farm or smelt every missing leaf. */
    @Spec(value = RecipeContracts.class, spec = "ACQUIRE")
    CompletionStage<RecipeContracts.AcquirePlan> planAcquire(RecipeContracts.PlanParams p, CallContext ctx);
}

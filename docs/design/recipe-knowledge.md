# Recipes come from the game, not from a table

## The problem

An agent that wants to make something needs two different things: to know what a recipe is,
and to work out how to get from what it has to what it wants. Neither was available.

The obvious ways to supply them are both bad. Hard-coding a map from "resource" to "how you
get it" — which is what a well-known comparable project does, in a few hundred lines of static
initialisers — has to be maintained by hand and covers whatever mods the maintainer happened to
think about. Scraping a recipe-viewer mod's interface makes the driver depend on a mod the user
may not have installed and on a rendering layer that changes.

## What was decided

**The recipe book is already in the game.** Every mod registers its recipes into the server's
recipe manager during start-up, and the recipe-viewer mods are front ends onto that same object.
So looking up a recipe means querying the recipe manager, which covers vanilla and essentially
every mod's shaped and shapeless crafting, smelting, blasting, smoking, stonecutting and
smithing, with no per-mod work and no maintenance.

That is the whole decision, and everything else follows from it. It is data-driven rather than
curated, so support for an arbitrary mod pack is a property of the approach rather than an
ongoing task.

Recipe-viewer integration stays possible as optional compatibility, for the things genuinely
outside the recipe manager — some modded machine processes, worldgen products, mob drops — but is
not on the critical path.

**The reads and the planner are separate surfaces.** Looking up a recipe by result or by
ingredient is a pure read. Resolving what a recipe needs, and planning a route from the current
inventory to a target item, is a second thing built on top of it. Keeping them apart means a caller
that only wants to know what something is made of does not pay for a planner, and the planner has
exactly one source of truth.

Every ingredient slot is reported as the **set** of items that would be accepted, not as one item,
because that is what a recipe ingredient actually is and collapsing it to a representative would
quietly lose every tag-based alternative.

## What this rules out

There is no hand-maintained acquisition table, and there should not be one — a mod pack the author
never saw has to work.

There is no dependency on a recipe-viewer mod for the core capability.

## Where to look

- `api/RecipeApi.java` — the lookup, the resolve and the acquisition planner, behind
  `mc.recipe.lookup`, `mc.recipe.resolve` and `mc.plan.acquire`.
- `api/DriverApi.java` — where those three routes are registered.

Reading the recipe manager is a read, so it obeys the same thread rule as every other read in the
driver; see `docs/dev/architecture.md`.

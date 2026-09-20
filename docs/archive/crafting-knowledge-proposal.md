# Recipe knowledge and crafting, as originally proposed

> **Archived proposal, written 2026-06-04, in Chinese. Not a description of current behaviour.**
>
> This is the original argument for reading recipes out of the game's own recipe manager rather
> than hard-coding an acquisition table or scraping a recipe-viewer mod's interface. That
> argument was accepted and the surface exists as `mc.recipe.lookup`, `mc.recipe.resolve` and
> `mc.plan.acquire` in `api/RecipeApi.java`.
>
> The document also contains a proposed API shape, a phasing, and comparisons to another
> project's task catalogue. None of that matched what was built closely enough to be worth
> consulting. The surviving decision is `docs/design/recipe-knowledge.md`.

> This covers two roadmap milestones: a read-only knowledge layer, and the execution layer that
> acts on it. The comparisons throughout are to AltoClef, a third-party Minecraft bot whose source
> is not part of this repository; the classes named from it are its recipe tracker, its
> craft-in-inventory, craft-at-table and smelt-in-furnace tasks, its recipe value types, and its
> hard-coded task catalogue.

## 1. The central judgement: the recipe book is the `RecipeManager`

**The game already holds the recipes** — `level.recipeManager`, authoritative on the server with a
synchronised copy on the client. Every mod pushes its recipes into it during registration. JEI and
EMI are just visualisers on top of it. Therefore:

- **Looking up a recipe means querying `RecipeManager`**, which covers vanilla and the overwhelming
  majority of mods for `crafting_shaped`, `crafting_shapeless`, `smelting`, `blasting`, `smoking`,
  `stonecutting` and `smithing`.
- **Do not scrape JEI.** JEI and EMI integration stays an optional compatibility layer for the few
  things outside `RecipeManager`: some modded machine processes, worldgen products, and mob drops.

AltoClef is the cautionary example here. It **hard-codes** a map from every resource to how that
resource is obtained, in several hundred lines of static initialisers. That is expensive to maintain
and covers mods badly. Reading `RecipeManager` directly is **data-driven**: no maintenance, and it
automatically covers whatever mods happen to be installed.

## 2. The knowledge layer, a read-only API

A new `api/RecipeApi.java`, pure reads, touching no write path. It therefore does not engage the rule
that write paths go through `server.execute()`, but the reads still have to happen on the server
thread or against the synchronised copy — see section 2.4.

### 2.1 `mc.recipe.lookup`

```
in:  { result?: "minecraft:diamond_pickaxe", ingredient?: "minecraft:diamond", limit?: 20 }
out: [{
      id: "minecraft:diamond_pickaxe",
      type: "crafting_shaped",
      result: { id, count },
      ingredients: [                       // one "accepted set" per slot
        { slot: 0, accepts: ["minecraft:diamond"], tag: null },
        { slot: 3, accepts: ["minecraft:stick"],  tag: null }, ...
      ],
      pattern: ["DDD"," S "," S "],         // shaped recipes only
      station: "crafting_table" | "inventory2x2" | "furnace" | "smithing_table" | "stonecutter"
   }]
```

- Iterate `recipeManager.getRecipes()` and filter by `result` or `ingredient`.
- Expand each `Ingredient` into the set of items it accepts. A tag-based recipe such as
  `#minecraft:planks` lists every member and records the `tag`, so that the layer above can pick
  whatever it already has.
- Infer `station` from the `RecipeType`. A `CRAFTING` recipe that fits in a 2×2 grid is also marked
  `inventory2x2`.

### 2.2 `mc.recipe.resolve`, the most valuable route here

This expands "I want X" recursively into "make these first, and here is what you are short of". It is
exactly the kind of thing a program should compute and a language model gets wrong.

```
in:  { target: "minecraft:diamond_pickaxe", count: 1, have?: <inventory snapshot> }
out: {
     steps: [                              // topologically sorted; do them top to bottom
       { craft: "minecraft:oak_planks", count: 4, from: "minecraft:oak_log×1", station: "inventory2x2" },
       { craft: "minecraft:stick",      count: 2, from: "minecraft:oak_planks×2", station: "inventory2x2" },
       { craft: "minecraft:diamond_pickaxe", count: 1, from: "diamond×3 + stick×2", station: "crafting_table" }
     ],
     missing: [ { item: "minecraft:diamond", count: 3 } ],   // leaf ingredients not on hand
     stations_needed: ["crafting_table"]
   }
```

The algorithm:

1. Pick a recipe for the target. When several apply, prefer the one whose ingredients are already on
   hand, then the one with the best output-to-input ratio, then the simplest shape.
2. For each ingredient: if `have` covers it, deduct it; if not, recurse when the ingredient is itself
   craftable, and otherwise add it to `missing`.
3. A tag ingredient is satisfied by **any** member found in `have` or in the craftable set — any plank
   already in the inventory satisfies `#planks`.
4. **Detect cycles** by keeping a stack of the targets currently being expanded and stopping on a hit.
   This is what prevents an A → B → A loop, which some compress-and-decompress recipe pairs would
   otherwise cause.
5. Emit the steps in dependency order.

### 2.3 Tag queries

`mc.observe.tags{item}` returns the tags an item belongs to, and `mc.query` gains a `tag` filter. This
lets the caller reason about substitution through tags such as `#c:ingots` and `#minecraft:planks`.

### 2.4 Threading

Reads of `RecipeManager` go through the server thread, using the existing synchronisation helper
pattern in `DriverApi`, or through the client's synchronised copy in client-only situations — the bot
subsystem already lives on the client. This matches the existing `mc.observe.*` reads and introduces
no new threading model.

### 2.5 Validation (`43_recipe.js`)

- `resolve` a diamond pickaxe: assert that `steps` contains the three planks, stick and pickaxe steps,
  and that `missing` contains 3 diamonds.
- Tag recipes: put birch planks in the inventory, resolve a crafting table, and assert that the birch
  planks satisfy `#planks` rather than being reported as a missing ingredient.
- Cycles: construct or find a pair of mutually inverse recipes and assert that resolution terminates.
- Parity across all three transports.

## 3. The execution layer

This turns a resolved plan into real crafting actions. All of it runs as a `BotProcess` under the
scheduler's `UserTaskChain`, so it can be preempted by combat and resumed afterwards.

### 3.1 `CraftProcess`

```
mc.bot.craft{ item: "minecraft:diamond_pickaxe", count: 1 }
```

Internally:

1. Resolve the plan. If `missing` is non-empty, return `{ok:false, missing:[...]}` immediately and
   hand the decision back to the caller, which can go mine or craft what is missing. **The mod does
   not go and gather on its own** — that belongs to the external planner.
2. If `stations_needed` includes a crafting table: place one from the inventory onto clear ground in
   front of the bot with `useItemOn`, reusing the existing placement simulation; or, if there is none,
   recursively craft one first, since a table fits in the 2×2 grid.
3. Execute the steps in order:
   - A 2×2 step places ingredients directly into the inventory crafting grid with `slotClick`.
   - A table step opens the table with `useItemOn`, waits for the `ContainerScreen` to open through
     `mc.wait.condition`, places ingredients into the slots the `pattern` names with `slotClick`, and
     shift-clicks the result into the inventory.
4. Loop until `count` is satisfied, verifying the inventory's count of the product each round so that
   slot contention and missed pickups are caught.

The key point is that this reuses what already exists —
`mc.client.input.slotClick(slot, button, type)`, `mc.client.input.setHotbarSlot`, and
`mc.observe.container` for reading the crafting and result slots. **No new keyboard or mouse
primitive is needed.**

AltoClef does the equivalent in its craft-in-inventory and craft-at-table tasks. Note that it clears
the cursor slot first, returning or dropping whatever is held on the cursor; we have to do the same,
or leftover cursor contents contaminate the placement.

### 3.2 `SmeltProcess`

```
mc.bot.smelt{ input: "raw_iron", count: 8, fuel?: "coal" }
```

1. Walk to an existing furnace or place one, using the same logic as the crafting table.
2. Open it and `slotClick` the input into the upper slot and fuel into the lower one. If no fuel is
   specified, pick a usable one from the inventory — coal, charcoal, wood, or a lava bucket — by
   efficiency.
3. Use `mc.wait.condition` to wait for the output slot to accumulate `count`, polling the result slot
   through `mc.observe.container`.
4. Shift-click the output out.
5. On timeout — the fuel burned out and the count is still short — report the fuel shortage and hand
   back to the caller.

AltoClef's smelt-in-furnace task is the counterpart.

### 3.3 `SmithProcess`, optional

Smithing-table upgrades: netherite, and templates. Same structure, with three slots for template,
base and addition. AltoClef has an upgrade-in-smithing-table task. The boss equipment chain may need
this, so build it when that need is real.

### 3.4 Validation (`44_craft.js`)

- With all ingredients supplied, assert that crafting a table and then a wooden pickaxe both run
  entirely through the slot simulation, and that the products appear in the inventory.
- Smelting: supply iron ore and coal, assert that the furnace produces iron ingots.
- Missing ingredients: deliberately withhold the diamonds and assert that crafting a diamond pickaxe
  returns `{ok:false, missing:[diamond×3]}` rather than hanging.
- Parity across all three transports, for both the `{started}` response and the final status.

## 4. The external surface, kept small

Keeping the verb count down is a standing convention, so:

| Tool | Layer | What it does |
|---|---|---|
| `mc.recipe.lookup` | knowledge | Look up a recipe by result or by ingredient, in one verb |
| `mc.recipe.resolve` | knowledge | Recursive crafting plan plus missing ingredients |
| `mc.bot.craft` | execution | Craft, swallowing the resolve internally; 2×2 and table share one verb, routed by station |
| `mc.bot.smelt` | execution | Smelt, possibly with an optional smithing mode, or possibly separate — to be decided |

`mc.observe.tags` may be folded into `mc.query`'s `select` and `filter` instead of becoming a verb of
its own.

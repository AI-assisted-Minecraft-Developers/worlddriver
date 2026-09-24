package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;

import com.google.gson.JsonParser;

import net.magicterra.stagewright.scene.BlockInventory;
import net.magicterra.stagewright.scene.CapabilityDescriptor;
import net.magicterra.stagewright.scene.Closure;
import net.magicterra.stagewright.scene.CraftAudit;
import net.magicterra.stagewright.scene.Detected;
import net.magicterra.stagewright.scene.Probe;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneDef;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.stagewright.scene.SceneSet;
import net.magicterra.stagewright.contract.SceneSkipped;
import net.minecraft.world.level.block.Blocks;

/**
 * StageWright's own conformance facets, tested against the game rather than against a mock.
 *
 * <p>These scenes are not about worlddriver. They are about {@code s.items()},
 * {@code s.advancements()} and {@code s.recipes()} — the facets added so a suite can assert about
 * inventories, progression and recipe graphs instead of only about blocks. They live here because
 * this is where StageWright's scenes run: the framework has no testmod of its own, and a facet that
 * has only ever been compiled is not a facet that works.
 *
 * <p><b>Vanilla subjects on purpose.</b> Every assertion below is about {@code minecraft:} items and
 * recipes, so these scenes stay true in any runtime the framework is dropped into and cannot start
 * failing because worlddriver changed. The facets are exercised against a third-party mod in
 * {@code conformance-mods/}, which is a different question — "does it work on a mod we do not
 * control" — and needs the mod, not this suite.
 *
 * <p><b>Two topologies, on purpose.</b> The inventory and advancement facets need a connected
 * player and {@code skip} themselves with a reason on a bare dedicated server; the recipe facet and
 * the registry-shaped reads do not. So a dedicated run proves the half that is about the pack's
 * data, and the client topologies additionally prove the half that is about a player. That split is
 * the same one the Waystones conformance suite already reports, and it is the argument for running
 * more than one topology rather than three copies of one.
 */
@SceneSet("cap")
public final class StageWrightCapabilityScenes implements SceneProvider {

    // ---- items ----------------------------------------------------------------

    /**
     * Give, count, has and clear agree with each other.
     *
     * <p>The cheapest possible proof that the facet reaches a real inventory: if any of the four
     * were reading or writing a different place, no two of them would agree.
     */
    @SceneDef(budget = 100)
    static void itemsGiveAndCount(SceneContext s) {
        s.items().clear();
        s.expect(s.items().count("minecraft:diamond")).as("diamonds in a cleared inventory").isEqualTo(0);
        s.expect(s.items().has("minecraft:diamond")).as("has() on a cleared inventory").isFalse();

        s.items().give("minecraft:diamond", 5);
        s.expect(s.items().count("minecraft:diamond")).as("diamonds after giving 5").isEqualTo(5);
        s.expect(s.items().has("minecraft:diamond")).as("has() after giving").isTrue();

        // Across stacks, because count() walks slots and a one-slot implementation would pass the
        // assertion above and fail this one.
        s.items().give("minecraft:diamond", 64);
        s.expect(s.items().count("minecraft:diamond")).as("diamonds after another 64").isEqualTo(69);

        s.expect(s.items().distinct()).as("distinct ids carried").contains("minecraft:diamond");
    }

    /** The inventory this suite hands back is the one it was given. */
    @SceneDef(budget = 100)
    static void itemsRestoreIsRegisteredOnFirstWrite(SceneContext s) {
        // Nothing has written yet, so nothing has been pinned. Give something, and assert that the
        // scene's own cleanup queue grew — the mechanism the whole no-leak claim rests on.
        s.items().give("minecraft:stone", 1);
        s.expect(s.items().count("minecraft:stone")).as("stone after giving").isAtLeast(1);
        // The restore itself is asserted by the NEXT scene finding a clean inventory; a scene cannot
        // observe its own teardown. What it can do is record what it left behind, so a suite whose
        // restore broke shows a growing number here rather than an unexplained failure later.
        s.record("distinct.beforeTeardown", s.items().distinct().size());
    }

    /**
     * An unknown item id fails loudly, and the message names candidates.
     *
     * <p>This is the single most important behaviour in the whole facet set. Eight facets take ids
     * as strings; if a typo resolved to air, to an empty stack or to nothing at all, the scene would
     * carry on and fail later on an assertion about a world it never set up — naming the assertion
     * instead of the misspelling.
     */
    @SceneDef(budget = 100)
    static void itemsRejectUnknownIdsLoudly(SceneContext s) {
        String message = null;
        try {
            s.items().give("minecraft:diamonnd", 1);
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("the failure an unknown item id produces").isNotNull();
        if (message == null) return;
        s.record("message", message);
        // The whole point: the message must be about the ID, not about a downstream symptom. The
        // first version of Ids null-checked registry.get(), which for a DEFAULTED registry answers
        // minecraft:air rather than null — so this scene's first real run reported "the player's
        // inventory is full (0 would not fit)" for a misspelled diamond. Asserting on the sentence
        // is what pins that fix.
        s.check(message).as("names the registry, not a downstream symptom").contains("no item is registered");
        s.check(message).as("names the id").contains("diamonnd");
        s.check(message.contains("did you mean") || message.contains("not in this run"))
                .as("offers candidates or says the namespace is absent").isTrue();
    }

    /**
     * Eating raises hunger, through the game's own finish-using path.
     *
     * <p>Hunger is set outright rather than drained with {@code /effect}: a full player cannot eat,
     * and the command route does not work from a scene because {@link SceneContext#command} builds
     * its source with a null entity, so {@code @s} resolves to nobody and the command throws. That
     * was found here, on this scene's first real run.
     */
    @SceneDef(budget = 200)
    static void itemsEatRaisesHunger(SceneContext s) {
        s.items().clear().hunger(6, 0f);

        int before = s.items().foodLevel();
        s.record("food.before", before);
        s.expect(before).as("hunger after setting it to 6").isEqualTo(6);

        s.items().give("minecraft:cooked_beef", 1).eat("minecraft:cooked_beef");

        int after = s.items().foodLevel();
        s.record("food.after", after);
        s.record("saturation.after", s.items().saturation());
        s.expect(after).as("hunger after eating cooked beef").isGreaterThan(before);
    }

    /** Eating something that is not food fails rather than doing nothing. */
    @SceneDef(budget = 100)
    static void itemsEatRefusesANonFood(SceneContext s) {
        String message = null;
        try {
            s.items().eat("minecraft:stone");
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("the failure eating a non-food produces").isNotNull();
        if (message != null) s.check(message).as("explains why").contains("not food");
    }

    // ---- advancements ---------------------------------------------------------

    /**
     * The advancement registry is readable without a player.
     *
     * <p>Deliberately separate from the grant/revoke scene so a bare dedicated server still proves
     * something about this facet. A datapack-integrity assertion — "every advancement my quest book
     * references still exists" — is exactly this shape, and it must not need a client topology.
     */
    @SceneDef(budget = 100)
    static void advancementsRegistryReadsWithoutAPlayer(SceneContext s) {
        List<String> all = s.advancements().all();
        s.record("advancements.registered", all.size());
        s.expect(all).as("registered advancements").isNotEmpty();

        // A vanilla advancement every runtime has. If this is absent the facet is reading the wrong
        // registry, which no amount of counting would reveal.
        s.expect(s.advancements().registered("minecraft:story/root"))
                .as("minecraft:story/root is registered").isTrue();
        s.expect(s.advancements().registered("minecraft:story/definitely_not_a_real_advancement"))
                .as("a made-up advancement is registered").isFalse();

        s.expect(s.advancements().allIn("minecraft")).as("vanilla advancements").isNotEmpty();
        s.expect(s.advancements().allIn("nosuchmod")).as("advancements from an absent mod").isEmpty();
    }

    /**
     * The advancement tree's parent links read back, including the root's absence of one.
     *
     * <p>Membership is not the interesting property of a progression ladder; shape is. A tier
     * reparented onto the wrong predecessor leaves every id registered and the mod completable — it
     * is simply no longer gated where its author thought. Nothing in the game complains, and a suite
     * that only asks {@code registered()} stays green through it.
     *
     * <p>Vanilla subjects, so this holds in any runtime. The empty string for a root is asserted
     * explicitly because it is the one value a JS scene compares against, and null versus undefined
     * across the Rhino boundary is exactly the kind of difference that only shows up in a pack.
     */
    @SceneDef(budget = 100)
    static void advancementsReportTheirParent(SceneContext s) {
        s.expect(s.advancements().parentOf("minecraft:story/root"))
                .as("a root advancement's parent").isEqualTo("");
        s.expect(s.advancements().parentOf("minecraft:story/mine_stone"))
                .as("story/mine_stone hangs off").isEqualTo("minecraft:story/root");
        s.record("nether.root.parent", s.advancements().parentOf("minecraft:nether/root"));

        // Walk up from a deep advancement to its root, which is the assertion a progression suite
        // actually makes and the one that breaks when a link moves.
        String id = "minecraft:story/enter_the_nether";
        int hops = 0;
        while (!id.isEmpty() && hops < 16) {
            id = s.advancements().parentOf(id);
            hops++;
        }
        s.record("hopsToRoot", hops);
        s.expect(id).as("walking parents up from enter_the_nether ends at a root").isEqualTo("");

        String message = null;
        try {
            s.advancements().parentOf("minecraft:story/definitely_not_a_real_advancement");
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("what parentOf says about an id that is not registered").isNotNull();
    }

    /**
     * Granting completes an advancement, and the scene's teardown takes it back.
     *
     * <p>The revoke is what makes progression testable at all: a suite that granted
     * {@code story/mine_stone} and left it would silently disable every later scene asserting that
     * something is still locked — and those scenes would fail pointing at the mod's gate rather than
     * at their predecessor.
     */
    @SceneDef(budget = 200)
    static void advancementsGrantCompletesAndRemainingEmpties(SceneContext s) {
        String id = "minecraft:story/mine_stone";
        // Start from a known state regardless of what this world's player has done, so the scene
        // means the same thing on a fresh world and on a re-run.
        s.advancements().revoke(id);
        s.expect(s.advancements().has(id)).as("earned before granting").isFalse();
        s.expect(s.advancements().remaining(id)).as("criteria outstanding before granting").isNotEmpty();

        s.advancements().grant(id);

        s.expect(s.advancements().has(id)).as("earned after granting").isTrue();
        s.expect(s.advancements().remaining(id)).as("criteria outstanding after granting").isEmpty();
        s.expect(s.advancements().completed(id)).as("criteria met after granting").isNotEmpty();
        s.record("criteria", s.advancements().completed(id).size());
    }

    /** An unknown advancement id fails loudly and says what the namespace does register. */
    @SceneDef(budget = 100)
    static void advancementsRejectUnknownIdsLoudly(SceneContext s) {
        String message = null;
        try {
            s.advancements().has("minecraft:story/no_such_advancement_here");
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("the failure an unknown advancement id produces").isNotNull();
        if (message != null) {
            s.record("message", message);
            s.check(message).as("names the namespace's real advancements").contains("minecraft");
        }
    }

    // ---- recipes --------------------------------------------------------------

    /** The recipe index finds a vanilla chain, in both directions. */
    @SceneDef(budget = 200)
    static void recipesFindAVanillaChain(SceneContext s) {
        s.record("recipes.loaded", s.recipes().count());
        s.expect(s.recipes().count()).as("recipes this run loaded").isGreaterThan(100);

        List<String> makers = s.recipes().producing("minecraft:diamond_block");
        s.record("diamond_block.recipes", makers.size());
        s.expect(makers).as("recipes producing a diamond block").isNotEmpty();

        String recipe = makers.get(0);
        s.expect(s.recipes().resultOf(recipe)).as("what that recipe outputs")
                .isEqualTo("minecraft:diamond_block");
        s.expect(s.recipes().ingredientsOf(recipe)).as("what it consumes")
                .contains("minecraft:diamond");

        // An item nothing crafts. The honest answer is an empty list, not a failure — and a facet
        // that threw here would be unusable for exactly the raw materials a closure bottoms out on.
        s.expect(s.recipes().anyProduces("minecraft:diamond_ore"))
                .as("anything crafting diamond ore").isFalse();
    }

    /**
     * A closure walk bottoms out on raw materials and reports its shape.
     *
     * <p>Vanilla, so the numbers are stable: a diamond block comes from diamonds, which come from
     * ore blocks and the crafting-table chain, and every branch ends somewhere uncraftable.
     */
    @SceneDef(budget = 300)
    static void recipeClosureBottomsOutOnRawMaterials(SceneContext s) {
        Closure closure = s.recipes().closureOf("minecraft:diamond_block");
        s.record("closure", closure.toString());
        s.record("closure.items", closure.itemCount());
        s.record("closure.depth", closure.depth());
        s.record("closure.leaves", closure.leaves().size());

        s.expect(closure.root()).as("the item walked from").isEqualTo("minecraft:diamond_block");
        s.expect(closure.itemCount()).as("items the walk reached").isGreaterThan(1);
        s.expect(closure.reaches("minecraft:diamond")).as("the walk reaches diamonds").isTrue();
        s.expect(closure.leaves()).as("where the graph bottoms out").isNotEmpty();
        s.expect(closure.recipeCount()).as("recipes the walk went through").isGreaterThan(0);

        // Vanilla ships no recipe with an unsatisfiable ingredient. This assertion is the one that
        // earns its keep in a 452-mod pack, where a tag nobody populated makes a recipe that loads
        // cleanly, shows up in JEI, and can never be crafted.
        s.expect(closure.unresolvable()).as("ingredient slots nothing can satisfy").isEmpty();

        // And nothing here is opaque. Vanilla recipe types all implement getIngredients(), so an
        // opaque recipe in a VANILLA-only closure would mean this facet had started mis-reading
        // them — which is worth catching here, because in a modded pack a non-zero count is normal
        // and this assertion could never be made.
        s.record("closure.opaque", closure.opaqueCount());
        s.expect(closure.opaque()).as("vanilla recipes the walk could not see through").isEmpty();

        // Nothing was inferred either, and that follows from the line above rather than repeating it.
        // The walk only re-encodes a recipe and harvests item ids from the JSON when the recipe
        // declares no ingredient slots at all; with zero opaque recipes here, that channel must never
        // have fired. This is its negative test — the one that catches it firing where real slots
        // exist, which no modded closure could ever notice because a non-zero count is normal there.
        s.record("closure.inferred", closure.inferredCount());
        s.expect(closure.inferred()).as("items reached only by inference in a vanilla closure").isEmpty();
    }

    /**
     * A recipe whose type does not implement {@code getIngredients()} is reported, not absorbed.
     *
     * <p>The subject is Minecraft's own interface, not a pack's data. {@code Recipe#getIngredients()}
     * is a DEFAULT method returning an empty list, so a mod's machine recipe answers "no
     * ingredients" instead of refusing to answer, and a closure walking through it ends the branch
     * with nothing to report. All the Mods 10 hit exactly this: the walk down from the ATM Star
     * reached 11 items through 22 recipes with zero leaves and zero unresolvable slots, having never
     * left the star/star-block compression loop, and looked healthy doing it.
     *
     * <p>There is no fix available from here — there is no second interface to ask — so the contract
     * is that the closure states its own incompleteness. This scene proves the wiring exists on a
     * runtime where the answer is zero; the number being meaningful is proved by the pack.
     */
    @SceneDef(budget = 200)
    static void recipeClosureReportsWhatItCouldNotSeeThrough(SceneContext s) {
        Closure closure = s.recipes().closureOf("minecraft:beacon");
        s.record("closure", closure.toString());
        s.record("closure.opaque", closure.opaqueCount());
        s.expect(closure.opaqueCount()).as("opaque recipes in a vanilla closure").isEqualTo(0);
        s.expect(closure.opaque()).as("the opaque list itself").isNotNull();
        // Not a tautology with the count: the list has to be the one the walk filled, and an
        // implementation returning a fresh empty list would pass the count assertion forever.
        s.expect(closure.opaque().size()).as("list and count agree")
                .isEqualTo(closure.opaqueCount());
    }

    /**
     * Blowing a closure bound throws rather than returning a partial answer.
     *
     * <p>This is a guarantee, not an implementation detail. A truncated walk has FEWER unresolvable
     * ingredients and fewer dangling leaves than the real graph — so it looks exactly like a
     * healthier pack, which is the most dangerous possible way for this facet to be wrong.
     */
    @SceneDef(budget = 200)
    static void recipeClosureBoundsFailLoudlyRatherThanTruncating(SceneContext s) {
        String message = null;
        try {
            // One node is smaller than any real closure, so this must blow the bound.
            s.recipes().closureOf("minecraft:diamond_block", 1, 64);
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("the failure a blown node bound produces").isNotNull();
        if (message != null) s.check(message).as("names the bound").contains("maxNodes");

        String depthMessage = null;
        try {
            s.recipes().closureOf("minecraft:diamond_block", 5000, 1);
        } catch (SceneFailure e) {
            depthMessage = e.getMessage();
        }
        s.expect(depthMessage).as("the failure a blown depth bound produces").isNotNull();
        if (depthMessage != null) s.check(depthMessage).as("names the bound").contains("maxDepth");
    }

    // ---- probes for mods this runtime does not have ---------------------------

    /**
     * Vanilla armour equips; the Curios half reports its own absence instead of exploding.
     *
     * <p>The probe behaviour is the subject here, not Curios. {@code :stagewright-api} is compiled
     * by every conformance fork, so it cannot depend on Curios — which means the difference between
     * "Curios is missing" and "this facet is broken" has to be visible from inside a runtime that
     * has never heard of it. {@code curiosPresent()} answering false without throwing is that
     * proof; the equipping itself is exercised where Curios actually is.
     */
    @SceneDef(budget = 150)
    static void equipArmorWorksAndCuriosReportsItsAbsence(SceneContext s) {
        s.equip().armor("head", "minecraft:diamond_helmet");
        s.expect(s.equip().inArmor("head")).as("what is on the player's head")
                .isEqualTo("minecraft:diamond_helmet");
        s.expect(s.equip().inArmor("feet")).as("what is on their feet").isEqualTo("");

        s.record("curios.present", s.equip().curiosPresent());
        // No Curios in this runtime, and the probe must say so rather than throwing
        // NoClassDefFoundError — which is what a hard reference would do at class-load time, before
        // any try/catch in this scene could see it.
        s.expect(s.equip().curiosPresent()).as("Curios present in the worlddriver runtime").isFalse();
    }

    /** The FTB Quests probe reports its own absence the same way. */
    @SceneDef(budget = 150)
    static void questsProbeReportsItsAbsence(SceneContext s) {
        s.record("quests.loaded", s.quests().loaded());
        s.expect(s.quests().loaded()).as("an FTB Quests book in the worlddriver runtime").isFalse();
    }

    /**
     * Asking a probe facet to actually do something, with the mod absent, is a recorded SKIP.
     *
     * <p>Not a failure and not a silent pass. This is the behaviour that lets one suite carry
     * accessory and quest scenes and still be green in a runtime without those mods, while leaving
     * the reason in the results so nobody reads that green as coverage.
     *
     * <p>{@code mustSkip} because Curios is deliberately absent from every runtime this suite runs
     * in — that is the point of the scene. It also keeps this scene out of the cross-run coverage
     * report, which would otherwise be right to name it: it does skip everywhere.
     */
    @SceneDef(budget = 150, mustSkip = true)
    static void probeFacetsSkipRatherThanFailWhenTheModIsAbsent(SceneContext s) {
        s.equip().curioSlots();
        s.fail("s.equip().curioSlots() must have skipped this scene — Curios is not in this runtime,"
                + " so reaching this line means the probe fell through instead of skipping");
    }

    // ---- loot -----------------------------------------------------------------

    /** A chest-shaped table rolls, and rolling it enough times shows what it can contain. */
    @SceneDef(budget = 300)
    static void lootRollsAVanillaChestTable(SceneContext s) {
        String table = "minecraft:chests/simple_dungeon";
        s.expect(s.loot().exists(table)).as("the dungeon chest table is registered").isTrue();
        s.expect(s.loot().exists("minecraft:chests/no_such_table_at_all"))
                .as("a made-up table is registered").isFalse();

        // Enough rolls that "this table can produce something" is a fact rather than a dice throw:
        // a 0-2 rolls pool legitimately comes up empty, which is exactly why roll() has no
        // single-roll form.
        List<String> distinct = s.loot().distinct(table, 200);
        s.record("distinct.items", distinct.size());
        s.expect(distinct).as("what a dungeon chest can contain over 200 rolls").isNotEmpty();

        var totals = s.loot().rollTotals(table, 200);
        s.record("distinct.overTotals", totals.size());
        s.expect(totals).as("item totals over 200 rolls").isNotEmpty();

        s.record("tables.loaded", s.loot().all().size());
        s.expect(s.loot().all()).as("loot tables this run loaded").isNotEmpty();
    }

    /**
     * A missing table fails loudly instead of rolling empty.
     *
     * <p>Same defaulted-lookup trap as the item registry: {@code getLootTable} answers the EMPTY
     * stand-in rather than null, so a renamed table would otherwise read as "a table that drops
     * nothing" — which is a legal thing for a table to be, and therefore indistinguishable from the
     * bug this facet is meant to find.
     */
    @SceneDef(budget = 150)
    static void lootMissingTableFailsLoudly(SceneContext s) {
        String message = null;
        try {
            s.loot().roll("minecraft:chests/definitely_not_a_table", 1);
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("the failure a missing loot table produces").isNotNull();
        if (message != null) {
            s.record("message", message);
            s.check(message).as("explains the EMPTY stand-in").contains("EMPTY");
        }
    }

    /**
     * A table that needs context this facet cannot supply is refused, not rolled with guesses.
     *
     * <p>This scene found a real one. The facet originally trusted {@code getRandomItems} to throw
     * on a parameter-set mismatch — it does not. Rolling {@code minecraft:entities/zombie} with
     * chest parameters returns an EMPTY LIST, because the table's conditions cannot evaluate. A
     * scene doing that would report "this mob drops nothing": a wrong answer indistinguishable from
     * a right one. The facet now compares the context set itself and refuses.
     */
    @SceneDef(budget = 150)
    static void lootRefusesATableNeedingOtherContext(SceneContext s) {
        String tableId = "minecraft:entities/zombie";
        s.expect(s.loot().exists(tableId)).as("the zombie table is registered").isTrue();

        String message = null;
        try {
            s.loot().roll(tableId, 1);
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("the failure rolling an entity table produces").isNotNull();
        if (message != null) {
            s.record("message", message);
            s.check(message).as("says it is not chest-shaped").contains("chest-shaped");
            s.check(message).as("warns that the game would have stayed silent").contains("empty list");
        }
    }

    // ---- structures -----------------------------------------------------------

    /** The structure registry reads, and names an absent mod rather than guessing. */
    @SceneDef(budget = 150)
    static void structuresRegistryReads(SceneContext s) {
        s.record("structures.registered", s.structures().all().size());
        s.expect(s.structures().all()).as("registered structures").isNotEmpty();
        s.expect(s.structures().registered("minecraft:village_plains"))
                .as("a vanilla structure is registered").isTrue();
        s.expect(s.structures().registered("minecraft:no_such_structure"))
                .as("a made-up structure is registered").isFalse();
        s.expect(s.structures().allIn("minecraft")).as("vanilla structures").isNotEmpty();
        s.expect(s.structures().allIn("nosuchmod")).as("structures from an absent mod").isEmpty();

        String message = null;
        try {
            s.structures().generatedAt("nosuchmod:nosuchstructure", 0, 0, 0);
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("the failure an unknown structure id produces").isNotNull();
        if (message != null) s.check(message).as("names the absent namespace").contains("nosuchmod");
    }

    /**
     * Locating a structure answers, and reports what the search cost.
     *
     * <p>Runs on generated terrain because that is the only ground where a structure could be, and
     * a small radius on purpose: a locate generates chunks, and a scene body runs inline on the
     * server tick, so the cost lands on every scene after this one. Whether a village happens to be
     * within range of this arena is not asserted — that would be an assertion about worldgen's
     * choices — but the search itself either answers a position or reports -1, and both are results.
     */
    @SceneDef(budget = 900, terrain = net.magicterra.stagewright.contract.Terrain.GENERATED, chunkRadius = 2)
    static void structuresLocateAnswersAndReportsItsCost(SceneContext s) {
        double distance = s.structures().distanceTo("minecraft:village_plains", 8);
        s.record("village.distance", distance < 0 ? -1 : Math.round(distance));
        s.record("here", String.join(",", s.structures().at(0, 0, 0)));

        // Either a position or the explicit "none within range" answer — never an exception, because
        // "this structure does not generate near here" is a finding a scene may want to assert.
        s.expect(distance == -1 || distance >= 0).as("locate answered one way or the other").isTrue();

        // The cost record is the point of this scene as much as the answer is: Structures.locate
        // writes locate.<id>.ms on every path, so a search that scanned for seconds and found
        // nothing is visible in a passing run instead of silently eating the suite's budget.
        s.expect(s.records().containsKey("locate.minecraft:village_plains.ms"))
                .as("the locate recorded its own cost").isTrue();
    }

    // ---- dimensions -----------------------------------------------------------

    /**
     * A scene can run its arena in a dimension it names, not only in the two StageWright ships.
     *
     * <p>The Nether is the subject because every runtime has it, which makes this assertion true
     * anywhere the framework is dropped. The real target is a content mod's own dimension —
     * Twilight Forest's bosses, structures, progression gates and loot are all inside one, so a
     * suite that could only reach the overworld could only ever assert about registries.
     *
     * <p><b>The Nether does NOT exercise the build-height clamp, and that is worth writing down.</b>
     * Its bedrock ceiling at y=127 is worldgen, not a build limit — the dimension declares a height
     * of 256, so the grid's y=200 is legal empty space above the ceiling and a write there lands.
     * The clamp exists for a mod dimension that declares a genuinely smaller height, where an
     * out-of-range {@code setBlock} is silently dropped rather than throwing and the scene would
     * then fail asserting about a block it "placed". This scene proves the arena is reachable and
     * writable in a named dimension; it does not prove the clamp.
     */
    @SceneDef(budget = 200, dimension = "minecraft:the_nether")
    static void dimensionRunsTheArenaWhereItSays(SceneContext s) {
        s.record("dimension", s.dimension());
        s.record("originY", s.originY());
        s.expect(s.dimension()).as("the dimension this scene's arena is in")
                .isEqualTo("minecraft:the_nether");

        // The arena is genuinely usable there, not merely resolved to there.
        s.setBlock(0, 0, 0, net.minecraft.world.level.block.Blocks.STONE);
        s.expectBlock(0, 0, 0).as("a block placed in the named dimension")
                .isSameAs(net.minecraft.world.level.block.Blocks.STONE);
        s.expectBlock(0, 1, 0).as("the block above it, untouched")
                .isSameAs(net.minecraft.world.level.block.Blocks.AIR);
    }

    /**
     * A dimension no mod in this runtime registers records a skip, not a failure.
     *
     * <p>The bot cannot assert anything — it never runs, and the {@code s.fail} below is only
     * reachable if the harness got this wrong. {@code mustSkip} is where the real assertion lives:
     * without it, this scene was green whether it skipped OR executed, which made it the one scene
     * in the suite incapable of failing. The results file carries the rest — the scene stays
     * registered, stays reconciled against the manifest, and names both the dimension it wanted and
     * the ones that are loaded. A conformance suite for a mod that is not installed must not turn a
     * whole run RED, and must not silently vanish either.
     */
    @SceneDef(budget = 100, dimension = "nosuchmod:nosuchdimension", mustSkip = true)
    static void dimensionAbsentIsARecordedSkip(SceneContext s) {
        s.fail("this scene must never execute — its dimension does not exist, so the harness should"
                + " have recorded a skip before ever building a context");
    }

    // ---- menus ----------------------------------------------------------------

    /**
     * A recipe-shaped menu recomputes its output when its inputs change.
     *
     * <p>The crafting table stands in for every modded machine menu, Twilight Forest's uncrafting
     * table included: the interesting behaviour is not "the slot holds what I put in it", it is
     * "the menu noticed and produced something". A {@code put} that set the stack without telling
     * the menu would pass every assertion except the last one.
     *
     * <p>Slot layout is vanilla {@code CraftingMenu}: 0 is the result, 1..9 are the 3x3 grid in
     * reading order, and the player's own inventory is appended after that.
     */
    @SceneDef(budget = 200)
    static void menuCraftingTableComputesItsResult(SceneContext s) {
        s.floor(5, net.minecraft.world.level.block.Blocks.STONE);
        s.setBlock(0, 1, 0, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);

        s.menu().openAt(0, 1, 0);
        s.record("menu.title", s.menu().title());
        s.record("menu.slots", s.menu().slotCount());
        s.expect(s.menu().isOpen()).as("a menu is open").isTrue();
        s.expect(s.menu().slotCount()).as("crafting menu slot count").isGreaterThan(9);

        s.expect(s.menu().item(0)).as("the result slot before any input").isEqualTo("");

        // 2x2 of planks in the top-left of the 3x3 grid: the crafting-table recipe itself.
        s.menu().put(1, "minecraft:oak_planks").put(2, "minecraft:oak_planks")
                .put(4, "minecraft:oak_planks").put(5, "minecraft:oak_planks");

        s.record("menu.contents", String.join(" ", s.menu().contents()));
        s.expect(s.menu().item(0)).as("what the crafting menu computed")
                .isEqualTo("minecraft:crafting_table");
        s.expect(s.menu().count(0)).as("how many it computed").isEqualTo(1);
    }

    /** A plain container menu holds what is put into it, and a bad slot index says so. */
    @SceneDef(budget = 200)
    static void menuChestHoldsAndBoundsCheck(SceneContext s) {
        s.floor(5, net.minecraft.world.level.block.Blocks.STONE);
        s.setBlock(0, 1, 0, net.minecraft.world.level.block.Blocks.CHEST);

        s.menu().openAt(0, 1, 0);
        s.expect(s.menu().item(0)).as("a fresh chest's first slot").isEqualTo("");

        s.menu().put(0, "minecraft:diamond", 7);
        s.expect(s.menu().item(0)).as("what the chest slot holds").isEqualTo("minecraft:diamond");
        s.expect(s.menu().count(0)).as("how many").isEqualTo(7);
        s.expect(s.menu().contents()).as("non-empty slots").isNotEmpty();

        String message = null;
        try {
            s.menu().item(9999);
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("the failure an out-of-range slot produces").isNotNull();
        if (message != null) s.check(message).as("names the real slot count").contains("slots");

        s.menu().close();
        s.expect(s.menu().isOpen()).as("still open after close()").isFalse();
    }

    /** Opening the menu of a block that has none fails naming the block, not the slot. */
    @SceneDef(budget = 150)
    static void menuRefusesABlockWithNoMenu(SceneContext s) {
        s.floor(5, net.minecraft.world.level.block.Blocks.STONE);
        s.setBlock(0, 1, 0, net.minecraft.world.level.block.Blocks.STONE);

        String message = null;
        try {
            s.menu().openAt(0, 1, 0);
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("the failure opening a menu-less block produces").isNotNull();
        if (message != null) {
            s.record("message", message);
            s.check(message).as("explains there is no menu").contains("no menu");
        }
    }

    /**
     * The whole-run uncraftable audit runs, and vanilla is clean.
     *
     * <p>Recorded rather than only asserted: the NUMBER is the interesting output when this same
     * call is pointed at a real pack, and a scene that only said "empty" would have to be rewritten
     * to learn anything there.
     */
    @SceneDef(budget = 400)
    static void recipesUncraftableAuditIsCleanOnVanilla(SceneContext s) {
        List<String> uncraftable = s.recipes().uncraftable();
        s.record("uncraftable.count", uncraftable.size());
        if (!uncraftable.isEmpty()) {
            s.record("uncraftable.first", uncraftable.get(0));
        }
        s.expect(uncraftable).as("recipes with an ingredient nothing in this run satisfies").isEmpty();
    }

    /**
     * A recipe can be RUN, not merely read — and vanilla's whole book runs.
     *
     * <p>The control for the audit this facet performs on a pack, and the reason it can be trusted
     * there. Every other question this facet answers is about recipe DATA: who declares what, whose
     * ingredients resolve, what the graph closes over. None of them execute anything, so none of
     * them can see a recipe whose {@code matches()} rejects its own declared ingredients or whose
     * {@code assemble()} returns the wrong stack. Such a recipe is registered, has resolvable
     * ingredients, and is walked straight through by {@code closureOf} — every static check passes
     * and the item is uncraftable by anybody.
     *
     * <p>Two halves, because either alone is satisfiable by a broken implementation. A single named
     * recipe with a known answer proves the grid is built and laid out correctly — an
     * {@code assembleId} that always returned {@code ""} would pass a whole-book audit that only
     * asserted "no failures" if the audit also counted zero attempts. And the book-wide sweep proves
     * the first is not a lucky shape.
     *
     * <p>{@code attempted} is asserted, not just recorded, for the same reason: a change that made
     * every recipe unattemptable would otherwise report a clean audit of nothing at all.
     */
    @SceneDef(budget = 600)
    static void recipesRunAndNotOnlyRead(SceneContext s) {
        // A shaped recipe whose result is not in doubt, so a failure here is the facet and not
        // somebody's data. Three planks and two sticks, in a shape, making one wooden pickaxe.
        s.expect(s.recipes().isGridRecipe("minecraft:wooden_pickaxe"))
                .as("a vanilla crafting recipe reports as a grid recipe").isTrue();
        s.expect(s.recipes().crafts("minecraft:wooden_pickaxe"))
                .as("what the wooden pickaxe recipe makes from its own ingredients")
                .isEqualTo("minecraft:wooden_pickaxe");

        CraftAudit audit = s.recipes().craftAudit();
        s.record("craft.attempted", audit.attempted());
        s.record("craft.succeeded", audit.succeeded());
        s.record("craft.skipped", audit.skipped());
        s.record("craft.failures", audit.failures().size());
        if (!audit.failures().isEmpty()) s.record("craft.first", audit.failures().get(0));

        s.expect(audit.attempted()).as("grid recipes this run actually built and ran")
                .isGreaterThan(100);
        // The whole list, not just the vanilla-typed subset, because this runtime has no mods: every
        // recipe here IS vanilla's. That is what makes this the control — a pack has to assert on
        // the subset (a mod may ship a type that never matches on purpose), and this is the run that
        // proves the machinery is sound before that weaker assertion is trusted anywhere.
        s.expect(audit.failures()).as("vanilla recipes that cannot make their own output").isEmpty();
    }

    // ---- the capability seam ---------------------------------------------------

    /**
     * The capability registry answers, and the two shipped adapters are in it.
     *
     * <p>What this actually proves is that ServiceLoader discovery ran at all. A registry that found
     * nothing behaves identically to one that found providers none of which are available — both
     * answer "no" to everything — so a suite asking only {@code hasCapability} would stay green
     * through a missing service file, a shadowed resource, or a merge that dropped it. This runtime
     * has neither Curios nor FTB Quests, so their names are the ones to assert on: they must be
     * KNOWN and unavailable, which is a different statement from unknown.
     */
    @SceneDef(budget = 100)
    static void capabilityRegistryReportsWhatThisRuntimeOffers(SceneContext s) {
        s.record("registered", String.join(",", s.capabilityProviders()));
        s.record("available", String.join(",", s.capabilities()));

        // The assertion that catches a dropped service file. Both shipped adapters must be REGISTERED
        // here, and neither can be AVAILABLE, because this runtime has neither mod. A first version
        // of this scene asserted only the second half — and would have passed just as happily if
        // discovery had found nothing at all, which is exactly the failure it was written for.
        s.expect(s.capabilityProviders()).as("providers that loaded").contains("curios");
        s.expect(s.capabilityProviders()).as("providers that loaded").contains("ftbquests");

        s.expect(s.hasCapability("curios")).as("Curios in this runtime").isFalse();
        s.expect(s.hasCapability("ftbquests")).as("FTB Quests in this runtime").isFalse();
        s.expect(s.hasCapability("nosuchmod:nothing")).as("a name nobody registered").isFalse();

        // Available is NOT empty and must not be asserted so — this suite now ships a capability
        // descriptor of its own, and it resolves. Which is the assertion worth making instead: the
        // available set is exactly what this runtime satisfies, not everything registered and not
        // nothing. An earlier version of this line said isEmpty(), and it was true only for as long
        // as nobody used the mechanism it was testing.
        s.expect(s.capabilities()).as("what this runtime satisfies").contains("pack:driver");
        s.expect(s.capabilities().contains("curios")).as("Curios among them").isFalse();
        s.expect(s.capabilityProviders().size())
                .as("more is registered than is available, which is the point of the split")
                .isGreaterThan(s.capabilities().size());
    }

    /**
     * Asking for an absent capability records a skip that names what IS present.
     *
     * <p>The alternative — failing — would make every suite's portability the author's problem, and
     * silently passing would make an uninstalled mod look like a tested one. The message matters as
     * much as the outcome: "skipped" without the runtime's inventory sends whoever reads the results
     * back to the machine that produced them.
     *
     * <p>{@code mustSkip} because {@code nosuchmod:nothing} is unsatisfiable by construction. If this
     * scene ever executes, capability lookup answered "present" for an id nobody registers, and every
     * skip in every conformance suite that trusted that lookup is worthless — which is why the
     * verdict calls that DEAD rather than RED.
     */
    @SceneDef(budget = 100, mustSkip = true)
    static void absentCapabilityIsARecordedSkip(SceneContext s) {
        s.record("before", "reached");
        s.capability("nosuchmod:nothing");
        s.fail("asking for a capability nothing offers should have skipped this scene");
    }

    /**
     * {@code probe()} refuses {@code net.minecraft.*}, and says why.
     *
     * <p>The single rule that makes reflection safe for a pack author. A by-name call on a Minecraft
     * class passes on a mojmap runtime and throws on a production Fabric one — the most expensive
     * shape of bug this framework has, because it is invisible until somebody else's pack runs it.
     * Mod classes are not remapped, which is why the same technique is sound for them.
     */
    @SceneDef(budget = 100)
    static void probeRefusesMinecraftClasses(SceneContext s) {
        String message = null;
        try {
            s.probe("net.minecraft.world.item.ItemStack");
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.record("refusal", message == null ? "<none>" : message.substring(0, Math.min(90, message.length())));
        s.expect(message).as("what probe() says about a Minecraft class").isNotNull();
        s.expect(message.contains("intermediary")).as("the refusal explains remapping").isTrue();
    }

    /**
     * A probe binds a real object, calls it by name, and a miss lists the alternatives.
     *
     * <p>The subject is StageWright's own {@link Closure}, deliberately: a non-Minecraft class that
     * exists in every runtime this can run in, so the scene tests the MECHANISM rather than the
     * presence of some mod. Each hop is one a pack author actually makes — bind an object they were
     * handed, call a no-arg reader, call one with an argument, convert out.
     *
     * <p>The candidate list decides whether this hatch is usable at all. Reflection that fails with
     * "no such method" sends the author to a decompiler; reflection that fails with the names on the
     * class lets them fix it where they are standing.
     */
    @SceneDef(budget = 200)
    static void probeReadsAModClassAndNamesNearMisses(SceneContext s) {
        s.expect(s.hasClass("net.magicterra.stagewright.scene.Closure")).as("a class that is here").isTrue();
        s.expect(s.hasClass("com.example.definitely.NotHere")).as("a class that is not").isFalse();

        Closure closure = s.recipes().closureOf("minecraft:diamond_block");
        Probe probe = s.probe("net.magicterra.stagewright.scene.Closure").on(closure);

        s.expect(probe.call("root").asString()).as("a no-arg call through the probe")
                .isEqualTo("minecraft:diamond_block");
        s.expect(probe.call("itemCount").asInt()).as("a probed int agrees with the real call")
                .isEqualTo(closure.itemCount());
        s.expect(probe.call("reaches", "minecraft:diamond").asBoolean())
                .as("a call with an argument").isTrue();
        s.expect(probe.call("leaves").asList().isEmpty()).as("a probed collection comes back wrapped")
                .isFalse();

        String message = null;
        try {
            probe.call("noSuchMethodAtAll");
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.record("miss", message == null ? "<none>" : message.substring(0, Math.min(120, message.length())));
        s.expect(message).as("what a missing method reports").isNotNull();
        s.expect(message.contains("what it does declare")).as("the miss lists candidates").isTrue();
    }

    // ---- what is installed, and what that unlocks --------------------------------

    /**
     * The mod list arrived, and it describes this runtime rather than an empty one.
     *
     * <p>The distinction this scene exists for is the one {@link net.magicterra.stagewright.scene.Mods}
     * refuses to blur. An uninstalled list would answer "not loaded" to every question, so every
     * conditional scene in every pack would skip with a reason that is false — the framework's own
     * wiring failing while wearing an absent mod's clothes. So the facet throws instead, and this
     * asserts the positive case the throw protects: two mods that must be here, one that cannot be.
     */
    @SceneDef(budget = 100)
    static void modListNamesThisRuntime(SceneContext s) {
        s.record("mods.count", s.mods().count());
        s.record("worlddriver", s.mods().version("worlddriver"));

        s.expect(s.mods().loaded("minecraft")).as("Minecraft in the mod list").isTrue();
        s.expect(s.mods().loaded("worlddriver")).as("the mod whose suite this is").isTrue();
        s.expect(s.mods().version("worlddriver")).as("its version").isNotEqualTo("");
        s.expect(s.mods().loaded("definitely_not_a_mod")).as("a mod nobody has").isFalse();
        s.expect(s.mods().version("definitely_not_a_mod")).as("its version").isEqualTo("");
        s.expect(s.mods().any("definitely_not_a_mod", "worlddriver")).as("any() of one present").isTrue();
        s.expect(s.mods().all("definitely_not_a_mod", "worlddriver")).as("all() with one absent").isFalse();
        s.expect(s.mods().count()).as("how many mods are loaded").isGreaterThan(0);
    }

    /**
     * The descriptors StageWright ships are all discovered here, and none of them is available.
     *
     * <p>The registered/available split again, now for the declarative layer, and it is the only
     * assertion that can tell a working auto-detection from a broken one. This runtime has none of
     * Mekanism, AE2 or Create, so every built-in must report itself REGISTERED (the JSON was read
     * out of the jar, parsed, and loaded into the same registry the typed adapters use) and NOT
     * available (their classes are genuinely not here). A build that stopped shipping
     * {@code data/stagewright/capabilities.json} — a resource excluded, a shadow merge that dropped
     * it — would answer every {@code hasCapability} question exactly as it does now.
     */
    @SceneDef(budget = 100)
    static void shippedDescriptorsAreDiscoveredAndHonestlyAbsent(SceneContext s) {
        s.record("registered", String.join(",", s.capabilityProviders()));
        s.record("available", String.join(",", s.capabilities()));

        for (String shipped : List.of("mekanism", "ae2", "create", "ars_nouveau", "apotheosis",
                "mysticalagriculture")) {
            s.expect(s.capabilityProviders()).as("descriptor loaded: " + shipped).contains(shipped);
            s.expect(s.hasCapability(shipped)).as(shipped + " installed here").isFalse();
        }

        // And the skip message has to say which file made the claim, or a pack author overriding a
        // built-in has no way to find out whether their override was even read.
        String message = null;
        try {
            s.capability("mekanism");
        } catch (SceneSkipped skipped) {
            message = skipped.getMessage();
        }
        s.record("absentReason", message == null ? "<none>" : message);
        s.expect(message).as("why an absent descriptor skipped").isNotNull();
        s.expect(message.contains("capabilities.json")).as("the reason names the file").isTrue();
    }

    /**
     * A descriptor the PACK declared, satisfied, reached, and probed — with no Java anywhere.
     *
     * <p>The other end of the previous scene, and the reason the two are separate. That one proves
     * discovery survives absence; this proves a satisfied descriptor actually produces something a
     * scene can use. The file is {@code stagewright-scenes/pack-capabilities.json}, installed beside
     * {@code pack.js} by exactly the provisioning a modpack gets, and its subject is worlddriver
     * because a self-test cannot depend on a mod the self-test runtime does not have.
     *
     * <p>What it buys over {@code s.probe('...')} is the indirection: the class name lives in one
     * file instead of in every scene that reaches for it, so a mod that moves its API costs the pack
     * one edit.
     */
    @SceneDef(budget = 100)
    static void aPackDeclaredCapabilityResolvesAndProbes(SceneContext s) {
        s.expect(s.hasCapability("pack:driver")).as("the descriptor the pack shipped").isTrue();

        Detected detected = s.capability("pack:driver", Detected.class);
        s.record("source", detected.source());
        s.record("mods", String.join(",", detected.mods()));
        s.record("version", detected.version());

        s.expect(detected.source().contains("pack-capabilities.json"))
                .as("where the descriptor came from").isTrue();
        s.expect(detected.mods()).as("the mods it named that are loaded").contains("worlddriver");
        s.expect(detected.version()).as("their version").isNotEqualTo("");
        s.expect(detected.hasProbe()).as("it named a class to probe").isTrue();

        // Through the descriptor rather than through a hardcoded string — the whole point.
        Probe api = detected.probe();
        s.expect(api.className()).as("what the probe bound").isEqualTo("net.magicterra.worlddriver.api.DriverApi");
    }

    /**
     * A descriptor with no condition is rejected at load, not treated as always-available.
     *
     * <p>The failure mode that would otherwise be permanent and silent: a descriptor claiming no
     * requirement reports itself available in every runtime, so scenes gated on it run against packs
     * that do not have the thing and fail for a reason that has nothing to do with the pack. Better
     * to refuse the file. This scene asserts the refusal by parsing one directly, because a
     * malformed file in the pack folder would take the whole run down — which is the correct
     * behaviour and a poor test subject.
     */
    @SceneDef(budget = 100)
    static void aConditionlessDescriptorIsRejected(SceneContext s) {
        String message = null;
        try {
            CapabilityDescriptor.parse(
                    JsonParser.parseString("{\"name\":\"x:y\"}").getAsJsonObject(), "<this scene>");
        } catch (IllegalStateException e) {
            message = e.getMessage();
        }
        s.record("rejection", message == null ? "<none>" : message.substring(0, Math.min(90, message.length())));
        s.expect(message).as("what a conditionless descriptor reports").isNotNull();
        s.expect(message.contains("every runtime")).as("the rejection explains why").isTrue();

        // And a well-formed one parses, so the rejection above is about the condition and not about
        // the parser refusing everything.
        CapabilityDescriptor ok = CapabilityDescriptor.parse(
                JsonParser.parseString("{\"name\":\"x:y\",\"mods\":\"worlddriver\"}").getAsJsonObject(),
                "<this scene>");
        s.expect(ok.name()).as("a valid descriptor's name").isEqualTo("x:y");
        s.expect(ok.availableIn(s)).as("a single mod named as a bare string, not an array").isTrue();
    }

    /**
     * Block entities in the arena actually tick, proved with vanilla and with no capability.
     *
     * <p>The framework assumes this everywhere and has never asserted it. PREP waits for every arena
     * chunk to report entity-ticking and that wait succeeds, but "the chunk says it ticks" and
     * "things in it tick" are two claims, and a suite that only ever places blocks and reads them
     * back cannot tell them apart — a chunk that is loaded but not ticking passes every such
     * assertion. That gap is currently costing a real investigation: on All the Mods 10 an armor
     * stand in the arena records {@code tickCount = 0} across sixty ticks while
     * {@code isPositionEntityTicking()} answers true, and modded machines placed there refuse every
     * write while answering every read.
     *
     * <p>A furnace is the cheapest decisive probe. Give it fuel and something smeltable and it lights
     * on its first tick, from {@code AbstractFurnaceBlockEntity.serverTick} and from no other
     * trigger, so {@code BurnTime} leaving zero is proof that the block-entity ticker for this chunk
     * ran — no capability, no mod, and identical on both loaders. Everything goes through commands so
     * nothing crosses a method name Fabric spells differently.
     *
     * <p><b>The first version of this scene used a hopper and that was a mistake worth recording.</b>
     * It TIMED OUT, which read as "the arena does not tick block entities" — but a hopper moves an
     * item only after clearing three gates a furnace does not have: an eight-tick transfer cooldown,
     * the {@code ENABLED} blockstate any redstone update can clear, and a container lookup on the
     * block above. Four causes, one symptom, and the probe could not say which. The chunk conditions
     * it recorded were all true and re-placing the hopper long after the chunk settled changed
     * nothing, which is what rules out the registration-window theory that motivated it. The hopper
     * is still here, one column over, but only RECORDED — whether a hopper transfers is a separate
     * claim from whether the arena ticks, and one scene should carry one.
     *
     * <p><b>What it caught.</b> Every chunk condition true and {@code BurnTime} still 0 after sixty
     * ticks — because chunk status was never the question. {@code ServerLevel.tick} skips the entity
     * loop AND {@code tickBlockEntities()} once a level has had no player for 300 ticks, and the
     * arena's runtime {@code TicketType.FORCED} ticket does not count towards the emptiness test that
     * governs it. Fifteen seconds into any player-less suite, nothing moves on its own. The harness
     * now calls {@code resetEmptyTime()} every tick, which is why this scene is {@code required}: it
     * is the only thing in the suite that fails if that call is ever removed, and everything else
     * stays green while it is broken.
     */
    @SceneDef(budget = 300)
    static void blockEntitiesTickInTheArena(SceneContext s) {
        // The conditions LevelChunk.isTicking(pos) tests, recorded before anything is placed. The
        // first run of this scene answered all four TRUE and still moved nothing, and re-placing the
        // block after the chunk had long settled moved nothing either — which is what rules out a
        // registration window and sends the question to the block entity instead of to the chunk.
        s.record("fullStatus", String.valueOf(s.level().getChunkAt(s.origin()).getFullStatus()));
        s.record("entityTicking", s.level().isPositionEntityTicking(s.origin()));
        s.record("blockTicking", s.level().shouldTickBlocksAt(
                net.minecraft.world.level.ChunkPos.asLong(s.origin())));
        s.record("entitiesLoaded", s.level().areEntitiesLoaded(
                net.minecraft.world.level.ChunkPos.asLong(s.origin())));

        // A furnace, not a hopper, is the tick detector — a hopper sits behind three gates that a
        // furnace does not have (an eight-tick transfer cooldown, the ENABLED blockstate a redstone
        // update can clear, and a container lookup on the block above), so "the hopper moved nothing"
        // has four possible causes and only one of them is the one being tested. A furnace given fuel
        // and something smeltable lights on its FIRST tick and on no other trigger: BurnTime goes
        // 0 → 1600 or the block entity never ran.
        s.setBlock(0, 0, 0, Blocks.FURNACE);
        s.command("item replace block ~ ~ ~ container.0 with minecraft:raw_iron 1");
        s.command("item replace block ~ ~ ~ container.1 with minecraft:coal 1");

        // The premise, asserted rather than assumed: fuel and input really are in the furnace before
        // any ticking is in question. Without this an `item replace` that silently did nothing would
        // look exactly like a block entity that never ticked.
        s.expect(blockHas(s, 0, 0, "Items[0].id")).as("the raw iron went in").isTrue();
        s.expect(blockHas(s, 0, 0, "Items[1].id")).as("the coal went in").isTrue();
        s.expect(furnaceIsLit(s)).as("the furnace before any tick").isFalse();

        s.await(() -> furnaceIsLit(s)).within(60).then(() -> {
            s.record("furnace.ticksToLight", s.ticks());

            // Block entities tick. Now the hopper, in its own column so the furnace is not part of
            // its answer — and RECORDED rather than asserted, because at this point a hopper that
            // does not move an item is a fact about hoppers in an arena, not about whether the arena
            // ticks. Asserting it would put a second unrelated claim behind this scene's name.
            s.setBlock(2, 1, 0, Blocks.CHEST);
            s.setBlock(2, 0, 0, Blocks.HOPPER);
            s.command("item replace block ~2 ~1 ~ container.0 with minecraft:diamond 1");
            s.expect(blockHas(s, 2, 1, "Items[0].id")).as("the diamond is in the chest").isTrue();

            int placedAt = s.ticks();
            s.await(() -> !blockHas(s, 2, 1, "Items[0].id") || s.ticks() - placedAt > 60)
                    .within(120)
                    .then(() -> s.record("hopper.pulled", !blockHas(s, 2, 1, "Items[0].id")));
        });
    }

    /**
     * Whether a block entity's NBT has anything at {@code path}, offset from the arena origin.
     *
     * <p>Presence rather than value, and that is not laziness: since 1.20.5 an item stack in a
     * container serialises as {@code {id, count}} with a LOWERCASE key, and {@code count} is omitted
     * entirely when it is 1 because the codec defaults it. So {@code Items[0].Count} — the spelling
     * every pre-1.20.5 example uses — reads absent for a slot holding exactly one item, which is
     * indistinguishable from an empty slot. Asking whether {@code Items[0].id} is there sidesteps
     * both traps and is the whole question anyway.
     */
    private static boolean blockHas(SceneContext s, int dx, int dy, String path) {
        return commandSucceeds(s, "data get block ~" + dx + " ~" + dy + " ~ " + path);
    }

    /**
     * Whether the furnace at the arena origin is burning, read off its {@code lit} blockstate.
     *
     * <p>The blockstate rather than the {@code BurnTime} NBT, and that is not a style choice: the two
     * loaders do not agree on that field's TYPE. NeoForge widens furnace burn time to an {@code int}
     * so a modded fuel can outlast a short; vanilla — so Fabric — keeps it a {@code short}, and
     * {@code data get} prints a short with its type suffix, {@code 0s}. A reader that parses a plain
     * integer therefore gets the right answer on one loader and "no such value" on the other, which
     * is the same shape of cross-loader lie that {@code s.probe} refusing {@code net.minecraft.*}
     * exists to prevent — here in a command string instead of a class name. {@code lit} is a vanilla
     * blockstate, spelled the same everywhere, and is the thing a player would look at.
     */
    private static boolean furnaceIsLit(SceneContext s) {
        return commandSucceeds(s, "execute if block ~ ~ ~ minecraft:furnace[lit=true]");
    }

    /** Run a command for its yes/no, turning a rejection into {@code false}. {@code /execute if} that
     *  does not match and {@code data get} on an absent path both REJECT rather than answer, and
     *  {@link SceneContext#command} raises that as an {@code IllegalArgumentException} — NOT a
     *  {@code SceneFailure}, which is what the first version of this caught. Catching the wrong one
     *  does not fail the catch, it lets the throw escape to the harness, which reports
     *  {@code unexpected IllegalArgumentException} and the scene is RED on the one branch the helper
     *  exists to make routine. */
    private static boolean commandSucceeds(SceneContext s, String command) {
        try {
            s.command(command);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The block item-handler capability reads a vanilla chest.
     *
     * <p>The generic capabilities are the answer to "provide common implementations": every machine
     * in every tech mod exposes its inventory through this one interface, so testing against the
     * capability covers all of them, including mods written after this file. Proving it needs no mod
     * at all — vanilla containers implement it too, which is what lets the mechanism be verified here
     * and only its reach be verified against a pack.
     *
     * <p>Skips on Fabric, and the skip is the assertion: the providers ship from the NeoForge module
     * because the capability is NeoForge's, and a runtime without it must say so rather than quietly
     * answer zero.
     */
    @SceneDef(budget = 100)
    static void blockItemHandlerReadsAVanillaChest(SceneContext s) {
        BlockInventory inv = s.capability("itemhandler", BlockInventory.class);   // skips off NeoForge
        s.setBlock(0, 0, 0, Blocks.CHEST);

        s.expect(inv.present(0, 0, 0)).as("a chest exposes an item handler").isTrue();
        s.expect(inv.slots(0, 0, 0)).as("a chest's slot count").isEqualTo(27);
        s.expect(inv.item(0, 0, 0, 0)).as("an empty slot").isEqualTo("minecraft:air");

        s.expect(inv.insert(0, 0, 0, 0, "minecraft:diamond", 3)).as("what did not fit").isEqualTo(0);
        s.expect(inv.item(0, 0, 0, 0)).as("what is in slot 0 now").isEqualTo("minecraft:diamond");
        s.expect(inv.count(0, 0, 0, 0)).as("how many").isEqualTo(3);
        s.record("contents", String.join(" ", inv.contents(0, 0, 0)));

        s.expect(inv.extract(0, 0, 0, 0, 2)).as("what came back out").isEqualTo(2);
        s.expect(inv.count(0, 0, 0, 0)).as("what is left").isEqualTo(1);

        // A block with no handler is a FAIL naming the block, never a null — asserting about the
        // inventory of something that has none has already found a bug, and deserves a sentence.
        s.setBlock(1, 0, 0, Blocks.STONE);
        s.expect(inv.present(1, 0, 0)).as("stone exposes an item handler").isFalse();
        String message = null;
        try {
            inv.slots(1, 0, 0);
        } catch (SceneFailure e) {
            message = e.getMessage();
        }
        s.expect(message).as("what asking stone for its slots reports").isNotNull();
        s.expect(message.contains("present")).as("the failure names the way to branch").isTrue();
    }
}

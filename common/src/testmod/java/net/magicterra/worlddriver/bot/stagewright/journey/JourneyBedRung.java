package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.CombatProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;

/**
 * Rung 07 — the bed. Three wool of ONE colour, and the colour is the whole difficulty.
 *
 * <p>Its own file for the same reason {@link JourneyPortalRung} and {@link JourneyNetherRungs} are:
 * a rung is the unit a reader comes here holding. It moved out of {@code WorldDriverJourneyScenes}
 * when that file crossed the 3000-line budget, and the split is along the rung boundary rather than
 * along a line count, so nothing here is half of something that lives elsewhere.
 */
final class JourneyBedRung {

    private JourneyBedRung() {}

    /**
     * How many rounds this rung will spend before it stops and says so.
     *
     * <p>Five was the first guess and it was sized against the wrong bill. A bed wants three wool of
     * one colour, and at vanilla's 82% white the COLOUR is not what costs rounds — measured, the
     * colour logic picked white five times out of five. What costs rounds is that a round does not
     * reliably end in a dead sheep: the first live run banked <b>2 wool from 5 rounds</b> while the
     * flock stayed at four to six head, and 5 was then exactly the number that turns a working rung
     * into a failing one. Ten, because the whole hunt cost 791 ticks against a 26 000-tick budget —
     * the constraint here was never time.
     */
    private static final int WOOL_HUNT_ROUNDS = 10;

    private static final int WOOL_PER_BED = 3;

    /**
     * Craft a bed from wool taken off the flock.
     *
     * <p><b>Off the critical path on purpose.</b> {@link JourneyStage#requires()} skips this rung and
     * {@link JourneyStage#criticalPath()} excludes it, because nothing on the road to the dragon
     * needs a bed and seed 5471's spawn swamp has no sheep in it. Writing the steps does not change
     * that — a FAIL here still blocks nothing. What it changes is which of the two possible
     * sentences the ladder prints: {@code NOT_SCRIPTED} was a statement about us, and "there are no
     * sheep within 176 blocks, only [chicken, cow, frog, pig]" is a statement about the world.
     *
     * <p><b>The colour IS the rung.</b> A bed is three wool of ONE colour plus three planks, and a
     * sheep drops a single wool of whatever colour it happens to be. So killing the three nearest
     * sheep is not a plan: white + brown + black crafts nothing, and the run would come home holding
     * three wool and reporting a recipe that looks broken. The target is therefore chosen on colour
     * first and distance second — greedily toward whichever colour the bag is already closest to
     * three of — and {@link CombatProcess} is handed that individual's entity id rather than
     * {@code "minecraft:sheep"}, so the sheep that dies is the sheep that was chosen. (Same family
     * as the plank-variant trap the craft resolver taught this ladder once already.)
     *
     * <p><b>The half this does not do yet.</b> {@link JourneyStage#BED} describes a bed "slept in,
     * spawn point moved". Sleeping needs the bed PLACED and needs it to be NIGHT. Neither is in
     * reach today: the only placement verb is package-private ({@code PlaceNearby}), and forcing
     * night would be staging — which this ladder measures and keeps at zero, so buying the
     * assertion that way would cost the number the whole suite exists to report. The rung asserts
     * the half it can prove and records {@code bed.dayTime}, so the next pass decides about the
     * other half from a reading instead of from a guess.
     */
    static void bed(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.BED);
        rig.generousPathfinding();
        rig.attempting("hunt sheep for three wool of one colour and craft a bed");
        rig.evidence("bed.dayTime", ctx.level().getDayTime() % 24_000L);
        // Widen the pin, WAIT, then look — the food rung's lesson, and it applies identically here:
        // an entity scan only sees loaded chunks, so scanning on the same line as the pin reports
        // an empty world rather than "I cannot see that far".
        ctx.cleanup(JourneyRig::seeNormally);
        JourneyRig.seeAtLeast(WorldDriverJourneyScenes.PREY_SEARCH_CHUNKS);
        rig.settle(new HoldStill(40), 100,
                () -> woolRound(ctx, rig, WOOL_HUNT_ROUNDS, WorldDriverJourneyScenes.PREY_SEARCH_BLOCKS, true));
    }

    /** The colour the bag holds most of, or null when there is no wool at all. */
    private static String bestWoolColour(JourneyRig rig) {
        String best = null;
        int bestCount = 0;
        for (net.minecraft.world.item.DyeColor dye : net.minecraft.world.item.DyeColor.values()) {
            int have = rig.carrying("minecraft:" + dye.getName() + "_wool");
            if (have > bestCount) { bestCount = have; best = dye.getName(); }
        }
        return best;
    }

    /** One kill's worth of the hunt: bank what we have, pick the next sheep by colour, repeat. */
    private static void woolRound(SceneContext ctx, JourneyRig rig, int roundsLeft, int radius,
                                  boolean mayWiden) {
        String colour = bestWoolColour(rig);
        int have = colour == null ? 0 : rig.carrying("minecraft:" + colour + "_wool");
        rig.evidence("bed.wool", colour == null ? "0" : have + " × " + colour);
        rig.evidence("bed.woolAtRound" + (WOOL_HUNT_ROUNDS - roundsLeft + 1),
                colour == null ? "0" : have + " × " + colour);
        if (have >= WOOL_PER_BED) { craftTheBed(ctx, rig, colour); return; }

        List<JourneyRig.Woolly> flock = rig.woolNearby(radius);
        if (flock.isEmpty()) {
            if (mayWiden) {
                // Look FURTHER from here rather than walking somewhere else to look — the food
                // rung's second premise, refuted there by its own survey: this seed has no herd at
                // spawn either, so a walk home would ask the same question from a worse place.
                rig.evidence("bed.noSheepAt", rig.player().blockPosition().toShortString()
                        + ": no woolly sheep within " + radius + " blocks, searching again within "
                        + WorldDriverJourneyScenes.PREY_SEARCH_WIDE + " blocks");
                JourneyRig.seeAtLeast(WorldDriverJourneyScenes.PREY_SEARCH_WIDE_CHUNKS);
                rig.settle(new HoldStill(40), 100,
                        () -> woolRound(ctx, rig, roundsLeft, WorldDriverJourneyScenes.PREY_SEARCH_WIDE, false));
                return;
            }
            ctx.fail("no woolly sheep within " + radius + " blocks (chunks pinned to "
                    + WorldDriverJourneyScenes.PREY_SEARCH_WIDE_CHUNKS
                    + " and loaded before the scan); only " + rig.animalsNearby(radius)
                    + " nearby. The spawn swamp of this seed has no flock, so this rung needs a"
                    + " walk to a grassland biome first");
            return;
        }
        if (roundsLeft <= 0) {
            ctx.fail("hunted for " + WOOL_HUNT_ROUNDS + " rounds and still short of " + WOOL_PER_BED
                    + " wool of one colour: the most held is " + have + " × " + colour + ", "
                    + flock.size() + " woolly sheep left within " + radius + " blocks");
            return;
        }
        // Every row from here down is keyed by round. The first version of this rung shared
        // `bed.wool` and `bed.flock` across all five rounds, and StageWright's clash guard keeps a
        // second value only when it DIFFERS — so a round whose wool count had not moved wrote the
        // same string and vanished. Five rounds left four flock rows and three wool rows, and the
        // question the run existed to answer ("which rounds killed nothing?") was the one erased.
        String r = "bed.r" + (WOOL_HUNT_ROUNDS - roundsLeft + 1);

        // Colour first, distance second. `flock` arrives distance-sorted and the comparison is
        // strict, so among colours the bag holds equally much of, the nearest sheep wins — but two
        // white wool in the bag will send the bot past a brown sheep standing right next to it,
        // which is exactly the point: the brown one is worth nothing at all.
        JourneyRig.Woolly pick = flock.get(0);
        int pickScore = -1;
        for (JourneyRig.Woolly w : flock) {
            int score = rig.carrying(w.woolId());
            if (score >= WOOL_PER_BED) continue;
            if (score > pickScore) { pickScore = score; pick = w; }
        }
        final JourneyRig.Woolly target = pick;
        final int woolBefore = rig.carrying(target.woolId());
        // The id is in the row because five rounds picking the same CELL is ambiguous and five rounds
        // picking the same ID is not. Rounds 2–6 of 2026-08-22 all chose white @ -16,64,73 and all
        // reported a kill while the flock size never moved off 6, which one id would have explained
        // in a single glance. The corpse count beside it is the other half: it says whether the
        // scan was ever offering dead sheep at all.
        rig.evidence(r + ".flock", flock.size() + " woolly (plus "
                + rig.deadSheepNearby(radius) + " dead), chose " + target.colour() + " id=" + target.entityId()
                + " @ " + target.where().toShortString() + " (" + Math.round(target.distance()) + " blocks, "
                + woolBefore + " of that colour already held)");

        // Walk first, engage second — CombatProcess scans 32 blocks and gives up at once, so handing
        // it a sheep 90 blocks away fails in two ticks and reads like a broken verb.
        rig.attempting("walk to the " + target.colour() + " sheep " + Math.round(target.distance())
                + " blocks away");
        rig.drive(new IntentProcess(new Intent(new Goal.Near(target.where(), 6))), 6_000, () -> {
            rig.evidence(r + ".arrived", rig.player().blockPosition().toShortString() + ", "
                    + Math.round(Math.sqrt(rig.player().blockPosition().distSqr(target.where())))
                    + " blocks from the target");
            // Weapon in hand before the swing — same reason as the food rung: CombatProcess swings
            // whatever is selected and has no picker of its own. Keyed by round, because which round
            // was fought bare-handed is exactly the question a thin wool haul raises.
            rig.evidence(r + ".weapon", rig.holdBestWeapon());
            rig.attempting("kill the " + target.colour() + " sheep (id=" + target.entityId()
                    + "): CombatProcess did not obtain any wool");
            rig.drive(new CombatProcess(CombatProcess.Mode.KILL, target.entityId(), "minecraft:sheep"),
                    4_000, () -> {
                // Did the chosen sheep actually die? CombatProcess.pick returns null when the id
                // cannot be resolved, and tick() reads a null target as "target dead/gone → mission
                // complete" — so a round that never found its sheep and a round that killed it both
                // come back as success, in the same handful of ticks. Asking the level directly is
                // the only way to tell those two apart, and telling them apart is the difference
                // between "the flock is thin" and "the verb reports a kill it did not make".
                var still = ctx.level().getEntity(target.entityId());
                rig.evidence(r + ".target", still == null ? "gone from the world"
                        : (still.isAlive() ? "still alive: this round did not kill it" : "dead")
                          + " (health " + (still instanceof net.minecraft.world.entity.LivingEntity le
                                  ? String.format(java.util.Locale.ROOT, "%.1f", le.getHealth()) : "?")
                          + ", removed=" + still.isRemoved()
                          + (still.getRemovalReason() == null ? "" : "/" + still.getRemovalReason())
                          + ", at " + still.blockPosition().toShortString() + ")");
                // What is on the ground, whatever it is. `pickup.left` and the three id-taking drop
                // readers all ask "is wool here?", and eight rounds of `pickup.left=0` at a 32-block
                // radius answered that question perfectly while leaving the useful one untouched:
                // mutton without wool means the sheep died already sheared, and an empty ground means
                // it did not die at all. Those are opposite repairs and no wool-shaped question can
                // tell them apart.
                rig.evidence(r + ".ground", rig.dropCensus(16));
                rig.collectByHand(target.woolId(), 2, r, () -> {
                    rig.evidence(r + ".gained", (rig.carrying(target.woolId()) - woolBefore)
                            + " " + target.colour() + " wool (previously " + woolBefore + ")");
                    woolRound(ctx, rig, roundsLeft - 1, radius, mayWiden);
                });
            });
        });
    }

    private static void craftTheBed(SceneContext ctx, JourneyRig rig, String colour) {
        String bedId = "minecraft:" + colour + "_bed";
        rig.evidence("bed.colour", colour);
        rig.attempting("craft " + bedId + ": 3 " + colour + "_wool + 3 planks");
        // Through the table guard like every other craft on this ladder, which is also what buys the
        // three planks: a bare CraftProcess here would fail on wood the rung never went to get.
        WorldDriverJourneyScenes.craftKeepingTheTable(rig, bedId, 6_000, () -> {
            int made = rig.carrying(bedId);
            rig.evidence("bed.crafted", made);
            ctx.expect(made).as("bed crafted from same-colour wool").isAtLeast(1);
            WorldDriverJourneyScenes.walkHome(rig, "bed", () -> rig.reach("crafted " + bedId + " ×" + made));
        });
    }
}

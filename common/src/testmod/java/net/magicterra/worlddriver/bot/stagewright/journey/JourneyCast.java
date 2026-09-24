package net.magicterra.worlddriver.bot.stagewright.journey;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Rung 11's second half: carry the filled bucket back to daylight, pick a cell of standing water,
 * and pour — once, because there is exactly one bucket of lava and no second try.
 *
 * <p>Mechanical extraction out of {@link WorldDriverJourneyScenes}, which reached its 3000-line
 * budget; the same seam {@code JourneyStation} and {@code JourneyPortalEntry} were cut on. Every
 * evidence key is unchanged — {@code cast.*} rows from before and after this move are directly
 * comparable, which is the whole point of moving code rather than rewriting it.
 *
 * <p>The FIRST half — tunnelling to the lava and filling — stays in {@link JourneyFill} and in
 * {@code WorldDriverJourneyScenes.reachLava}. The two halves ask opposite questions about the same
 * 4.5-block ray, and both of them get it from {@link JourneyFill#BUCKET_REACH}: the fill needs a
 * source it can REACH (seeing it at the digging range is not enough), and the pour needs a bed it
 * can SEE (standing near it is not enough).
 */
final class JourneyCast {
    private JourneyCast() {}

    /** Climb back to daylight carrying the lava, get out of the water it surfaced in, then cast. */
    static void leaveWithTheLava(SceneContext ctx, JourneyRig rig, int surfaceY) {
        rig.attempting("climb back to the surface carrying the lava");
        JourneyShaft.climbOut(rig, surfaceY, "lava.exit", () -> {
            rig.evidence("lava_bucket.atSurface", rig.carrying("minecraft:lava_bucket"));
            standOnDryGround(rig, () -> castBesideWater(ctx, rig));
        });
    }

    /** How far to look for somewhere to stand. Sixteen, because the climb that made this necessary
     *  surfaced four columns off its own pillar — the drift is a few blocks, not a few dozen, and a
     *  wider search only buys a longer swim to reach it. */
    private static final int DRY_LAND_SEARCH = 16;

    /**
     * Get out of the water before doing anything else, because a bot that surfaced into it is
     * drowning against a clock.
     *
     * <p><b>The run this is written from.</b> j34 reached this rung holding the lava —
     * {@code lava_bucket.atSurface = 1}, one pour from the obsidian — and then drowned:
     * {@code death.blow = drown −2.0→14.0@6 … drown −2.0→0.0@146}, {@code death.driving = goto},
     * and {@code death.standingIn} reported the foot, floor and head cells all as
     * {@code water[level=8]}. The exit had already said so one row earlier:
     * {@code lava.exit#6.endedOn} reported water in the foot cell and under the feet (floating,
     * with no floor, so every step above would start from a sinking bot), over an
     * {@code endedIn} of {@code -3,56} while the pillar column was {@code -7,53} (not the same
     * column), and {@code gained = 32/37}. The cast then walked a suffocating bot for 146 ticks and
     * the rung's result was a death.
     *
     * <p><b>This is a missing hand-off, not a new mechanism.</b> {@link JourneyShaft#recordExit}'s
     * javadoc deliberately leaves the repair here: "Getting out of water is a horizontal problem and
     * it belongs to the caller, which is why the iron rung now ends with a walk home." The iron rung
     * performs that hand-off, and this method is the same hand-off for the cast. It is a
     * caller-side step, and
     * specifically NOT "refuse to finish the climb while wet"; that same
     * javadoc already priced that one: it falls through to a {@code Goal.YLevel(surfaceY)} fallback
     * which is satisfied at that very moment, i.e. the same question asked twice.
     *
     * <p>Both branches record, and the afloat test is literally the one {@code recordExit} prints:
     * it delegates to the shared predicate in {@link JourneyShaft} instead of restating it, so the
     * hand-off and the row that motivates it cannot drift apart. A caller answering "wet" where the
     * exit answered "afloat" would walk ashore for a bot that was standing on rock, or leave one
     * floating.
     */
    // Package-visible so `wd.journeyGetsAshoreBeforePouring` can run this step on its own. Driving
    // `leaveWithTheLava` instead would be wrong twice over: it starts with a climb this arena has no
    // shaft for, and it ENDS by casting, so a red would as often be about the pour as about getting
    // out of the water.
    static void standOnDryGround(JourneyRig rig, Runnable then) {
        ServerLevel lvl = rig.ctx().level();
        BlockPos at = rig.player().blockPosition();
        // THE FLOOR, not the head. `JourneyShaft.afloat` also requires the FOOT cell to be fluid
        // and therefore answers "no" for a bot treading water at the surface, which has nothing to
        // stand on, cannot place, and cannot pour. See JourneyShaft#noDryFooting for the run that
        // measured the difference.
        boolean afloat = JourneyShaft.noDryFooting(lvl, at);
        rig.evidence("lava.exit.afloat", afloat
                ? "yes - fluid under the feet (the head cell is not checked: floating at the surface"
                  + " also has no floor), " + at.toShortString() + ", going ashore before pouring"
                : "no - " + at.toShortString() + ", below the feet="
                  + lvl.getBlockState(at.below()).getBlock());
        if (!afloat) { then.run(); return; }
        // dryUnderfoot is the descent's own predicate for REFUSING a wet column; it is asked here
        // for the opposite reason: the nearest column that PASSES it is where the bot gets ashore.
        // Ranked by horizontal distance only: the y is whatever that column's daylight is.
        BlockPos dry = JourneyTerrain.nearestDryColumn(lvl, at, DRY_LAND_SEARCH);
        rig.evidence("lava.exit.dryLand", dry == null
                ? "no dry column within " + DRY_LAND_SEARCH + " blocks" : dry.toShortString());
        // Nothing to walk to is not a reason to stop: the pour may still find shallow water from
        // here, and failing the rung on the recovery would replace a pour diagnosis with a walking
        // one. The row above is what says which happened.
        if (dry == null) { then.run(); return; }
        rig.attempting("go ashore with the lava first: could not walk to " + dry.getX() + "," + dry.getZ()
                + " (the bot is drowning)");
        // Tolerance 1 and a short budget on purpose. Drowning costs 2 HP every 20 ticks, so a full
        // health bar is 200 ticks of swimming; a goto allowed to spend thousands of ticks here would
        // watch the bot die exactly as the cast's own goto did.
        WorldDriverJourneyScenes.walkToColumn(rig, "lava.ashore", dry.getX(), dry.getZ(), 1, 600,
                () -> stepOntoTheBank(rig, dry, then),
                () -> stepOntoTheBank(rig, dry, then));
    }

    /** How long the last step onto the bank may take. Two hundred, not eighty: an arena run spent
     *  all of eighty ticks one cell short of the bank. The drowning budget that would justify eighty
     *  applies to the goto BEFORE this one; by here the bot is at the surface with its air supply
     *  full (measured: health 20.0, air 300), so what this spends is time, not health. Two hundred
     *  is still under one drowning bar if the bot does go back under. */
    private static final int LAST_STEP = 200;

    /**
     * The step the tolerance swallowed.
     *
     * <p>{@code walkToColumn} above is given a tolerance of one, which is right for that goto: asking
     * a drowning bot for an exact cell is how a recovery spends its budget in the water. But "within
     * one of the bank" and "on the bank" are different places, and the scene
     * {@code wd.journeyGetsAshoreBeforePouring} caught this recovery reporting success from the
     * wrong one:
     *
     * <pre>
     * lava.exit.afloat  = yes …
     * lava.exit.dryLand = 242843, 221, 100000
     * (ended)             242844, 220, 100000, below the feet=water
     * </pre>
     *
     * <p>One block out and one block down, still in the water it was sent to leave, with every row
     * above it reading like a success. So the arrival is re-checked as the question that matters (is
     * the bot still afloat) rather than as a distance, and only a bot that is still floating pays
     * for one more short goto to the exact cell. The bed rung's walk home had the same defect: it
     * judged "arrived" three blocks short inside a tolerance of five and handed the next rung a bot
     * standing in a swamp.
     *
     * <p>Records on both paths: a run where the extra step was not needed has to look different from
     * one where nobody asked.
     */
    private static void stepOntoTheBank(JourneyRig rig, BlockPos dry, Runnable then) {
        ServerLevel lvl = rig.ctx().level();
        if (!JourneyShaft.noDryFooting(lvl, rig.player().blockPosition())) {
            rig.evidence("lava.exit.onTheBank", "the first goto was enough, ended at "
                    + rig.player().blockPosition().toShortString());
            ashore(rig);
            then.run();
            return;
        }
        rig.attempting("last cell onto the bank: the tolerance left the bot in the water, one more"
                + " step to " + dry.toShortString());
        // TOLERANCE ZERO, and a COLUMN rather than a cell. Two readings from the arena decided both
        // halves. The goto above reported `end=arrived`, 1 block from 242843,100000 with a tolerance
        // of 5: the walker's own arrival radius is five and the `1` this caller passed is a different
        // quantity, so "arrived" was true of a bot one cell out in the water. And a `Goal.Block`
        // retry returned almost immediately without moving: the exact cell carries a y the swimming
        // bot does not have. Asking for the COLUMN with no slack is the question that has one answer.
        WorldDriverJourneyScenes.walkToColumn(rig, "lava.lastStep", dry.getX(), dry.getZ(), 0,
                LAST_STEP, () -> bankRow(rig, dry, then), () -> bankRow(rig, dry, then));
    }

    private static void bankRow(JourneyRig rig, BlockPos dry, Runnable then) {
        ServerLevel lvl = rig.ctx().level();
        BlockPos at = rig.player().blockPosition();
        rig.evidence("lava.exit.onTheBank", "the last cell the tolerance left: aimed for "
                + dry.toShortString() + ", stopped at " + at.toShortString()
                + ", still fluid below the feet=" + JourneyShaft.noDryFooting(lvl, at)
                + " (below the feet=" + lvl.getBlockState(at.below()).getBlock()
                + "); this goto's end=" + rig.slotEnd("goto"));
        ashore(rig);
        then.run();
    }

    /**
     * The row both exits land on. It is named {@code ashore} on a path that has just printed
     * "still fluid below the feet=true", so it must carry the predicate that decides the word rather
     * than a block name that only usually agrees with it.
     *
     * <p>{@link JourneyShaft#noDryFooting} asks {@code getFluidState(at.below())}, whereas
     * {@code getBlock()} of the same cell disagrees on a WATERLOGGED block, where the name answers
     * "oak_stairs" for a cell that is still fluid. That is the one case where a reader checking the
     * name alone sees dry land under a bot that is standing on none.
     */
    private static void ashore(JourneyRig rig) {
        BlockPos at = rig.player().blockPosition();
        ServerLevel lvl = rig.ctx().level();
        boolean wet = JourneyShaft.noDryFooting(lvl, at);
        rig.evidence("lava.exit.ashore", (wet ? "not ashore (still no dry footing): " : "ashore: ")
                + at.toShortString()
                + ", below the feet=" + lvl.getBlockState(at.below()).getBlock()
                + ", no dry footing=" + wet
                + ", health " + rig.player().getHealth() + ", air " + rig.player().getAirSupply());
    }

    /**
     * Pour the lava into standing water, at a cell chosen before the pour.
     *
     * <p>"Chosen before" is the whole assertion. Obsidian appearing SOMEWHERE after a bucket is
     * emptied proves the fluids met; obsidian appearing in the cell the rung named proves the bot
     * put it there, and only the second is a capability a portal can be built on.
     */
    private static void castBesideWater(SceneContext ctx, JourneyRig rig) {
        rig.attempting("find shallow water with a solid bed and pour the lava into it");
        BlockPos shallow = JourneyTerrain.shallowWaterNear(rig, 24);
        if (shallow != null) { approachAndPour(ctx, rig, shallow); return; }
        // Nothing underfoot: fall back on the surveyed water. A swamp normally makes this branch
        // dead code, which is exactly why it is worth recording when it is not.
        BlockPos w = JourneyRoute.firstWater;
        rig.evidence("cast.walkedToSurveyedWater", w.toShortString());
        WorldDriverJourneyScenes.walkToColumn(rig, "water", w.getX(), w.getZ(), 0, 16_000,
                () -> approachAndPour(ctx, rig, JourneyTerrain.shallowWaterNear(rig, 12)),
                () -> ctx.fail("cannot reach firstWater " + w.toShortString()
                        + ": stopped at " + rig.player().blockPosition()));
    }

    private static void approachAndPour(SceneContext ctx, JourneyRig rig, BlockPos water) {
        approachAndPour(ctx, rig, water, CAST_APPROACHES);
    }

    /** How many times the cast may walk at its target before saying it cannot get there. A pour is
     *  a four-and-a-half-block ray and {@code Goal.Near} can end short, so arriving is a thing to
     *  CHECK rather than a thing to assume — measured, a run reached the pour at
     *  {@code cast.range=12.12} with {@code cast.picks=MISS}, twelve blocks from water it had
     *  walked at once. */
    private static final int CAST_APPROACHES = 3;

    private static void approachAndPour(SceneContext ctx, JourneyRig rig, BlockPos water, int tries) {
        approachAndPour(ctx, rig, water, tries, CAST_APPROACH_RADIUS);
    }

    /**
     * How near the walk is asked to end, and why the retry is allowed to tighten it to 1.
     *
     * <p>Two is what "adjacent, not merely near" requires, but {@code Goal.Near} measures in 3D, so
     * radius 2 also admits standing two rows BELOW the target, which is where ladder-1 poured from
     * ({@code 3,60,63} for a target at {@code 3,62,63}). From there the eye sits at y≈61.6, under
     * the bed's own top face at y=62, and the ray can only reach that bed through a SIDE — so the
     * lava lands beside the target no matter how many times the same goal is re-run.
     *
     * <p>Radius 1 is not a guess. It admits feet at y ≥ {@code target.y - 1} = the bed's own row,
     * hence an eye at ≥ bed.y + 1.62, hence above the bed's top face — which is what makes an `up`
     * hit geometrically available again. A retry at the SAME radius would settle instantly in the
     * same cell and change nothing ([[a-retry-that-changes-nothing]] is this exact shape).
     */
    private static final int CAST_APPROACH_RADIUS = 2;

    private static void approachAndPour(SceneContext ctx, JourneyRig rig, BlockPos water, int tries,
                                        int radius) {
        if (water == null) {
            ctx.fail("no water surface with a solid bed nearby: the bot is at "
                    + rig.player().blockPosition()
                    + " (deep water has no landing cell; poured lava would sink and the obsidian it"
                    + " casts could not be recovered)");
            return;
        }
        // NUMBERED, and counting FORWARD. Two reasons, and the run that needed both is ladder-14.
        //
        // `JourneyRig.slotFor` is deliberately idempotent: a row restated with the SAME value lands
        // back on the row it already holds rather than growing a `#n`. Three approaches that all end
        // in the same cell therefore collapse into ONE `cast.walk`, and the evidence then reads as a
        // single walk when it was three. That is how the entire approach budget got spent on a retry
        // that changed nothing while the row that would have said so was being overwritten by itself.
        //
        // Forward, because `tries` counts DOWN: a suffix taken straight from it numbers the last
        // attempt 1 and reads the whole timeline backwards for anyone who did not write this line.
        final int approach = CAST_APPROACHES - tries + 1;
        rig.evidence("cast.target." + approach, water.toShortString());
        // Adjacent, not merely near. A pour is a ray, and a ray aimed at a cell below the bank's lip
        // hits the lip: measured in the arena, where a mould two cells away swallowed the bucket and
        // left the target empty while the use still reported CONSUME.
        rig.settle(new IntentProcess(new Intent(new Goal.Near(water, radius))), 2_000, () -> {
            BlockPos at = rig.player().blockPosition();
            // HOW THIS WALK ENDED, on both branches. `rig.settle` runs its callback when the budget
            // is spent just as it does when the process finishes, so the two must be told apart
            // here: ladder-14 spent all 2100 ticks pinned at -7,58,45 with the water eight blocks
            // away, then re-picked a nearer pool and failed the pour, and the rung's result named
            // the AIMING LINE. The walk that never happened left no row at all, so the only account
            // of it was three heartbeat lines in the log. Same key shape the lava walk uses.
            rig.evidence("cast.walk." + approach, JourneyLeg.walkerEnd(rig) + "; stopped at "
                    + at.toShortString() + ", "
                    + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(at.distSqr(water)))
                    + " blocks from the planned water surface " + water.toShortString());
            BlockPos again = JourneyTerrain.shallowWaterNear(rig, 8);
            BlockPos aim = again == null ? water : again;
            // A NEARER WATER IS NOT A VISIBLE WATER. The re-pick exists because a walk can end a
            // cell short of the surveyed pool, and it is measured by DISTANCE — which says nothing
            // about the line. ladder-14: the walk got nowhere, the re-pick found a pool 3.4 away
            // and FOUR BLOCKS UP, and the ray to its bed went through the column holding it up
            // (`-7,59,46 stone`, `-7,60,46 dirt`, `-7,60,47 dirt`). The clearing loop then spent
            // itself on terrain that was never in the way of anything but this bad choice.
            //
            // Ask the engine, from here, with the FULL bucket's own ray. Blocked → go back to the
            // surveyed pool and walk again rather than dig upward into the pool's own floor: that
            // pool is above the body, so clearing the line means removing what holds the water,
            // and the reward for succeeding is water on the body's head.
            //
            // ⚠️ ASK THE POUR'S QUESTION, NOT THE FILL'S. This guard used to call
            // `bucketLineLandsOn`, which compares the hit BLOCK — the right question for an empty
            // bucket, which takes the fluid out of whatever it lands on, and the wrong one here. It
            // is the door that admitted ladder-1's `3,62,62`: the ray did land on that cell's bed,
            // so the fill predicate approved it, and the pour then went one cell south of it. The
            // re-pick has to be judged by the same invariant the pour is judged by or it will keep
            // choosing exactly the cells the final guard must refuse.
            BlockPos rePickLands = again == null ? null : JourneyFill.bucketPourLandsIn(rig, again.below());
            if (again != null && !again.equals(water) && tries > 0 && !again.equals(rePickLands)) {
                rig.evidence("cast.rePickBlocked." + approach, again.toShortString()
                        + " is nearer, but pouring from here along the full bucket's own ray would"
                        + " land in "
                        + (rePickLands == null ? "MISS (out of the ray's reach)"
                                               : rePickLands.toShortString())
                        + ", not in that cell; falling back to the surveyed " + water.toShortString()
                        + " and walking again");
                // Radius carried, never reset: it only ever tightens, and handing a later approach
                // the loose default would undo a tightening an earlier approach paid a walk for.
                approachAndPour(ctx, rig, water, tries - 1, radius);
                return;
            }
            // Did the walk actually ARRIVE? `Goal.Near` reporting done is not the same as being in
            // reach, and the pour has no way to tell the difference afterwards: out of range the ray
            // returns MISS, which the blocked-line guard then reports as an obstruction it cannot
            // clear. One rung's evidence read `cast.range=12.12, cast.picks=MISS` — nothing was in
            // the way at all, the target was simply eight blocks past the end of the ray.
            //
            // Derived from the BUCKET's reach, not the digging one. Same number as before by
            // arithmetic; the point is that the threshold now moves with the constant it is about.
            double limit = JourneyFill.BUCKET_REACH - 0.5;
            double range = rig.player().getEyePosition()
                    .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(aim));
            if (range > limit && tries > 0) {
                rig.evidence("cast.tooFar." + approach, String.format(java.util.Locale.ROOT,
                        "%.1fm > %.1fm, walking again", range, limit));
                approachAndPour(ctx, rig, aim, tries - 1, radius);
                return;
            }
            // WHICH CELL THE POUR WOULD LAND IN — asked here, where a walk is still affordable.
            // Everything above answers "can the ray REACH it": `cast.range` measures a distance and
            // the re-pick measures a line. Neither answers "where does the fluid GO", and vanilla
            // decides that with the hit FACE, not the hit block. ladder-1 stood at `3,60,63` — two
            // rows under its target — read `cast.picks = 3,61,62 dirt face=south`, poured, and cast
            // its one bucket of obsidian at `3,61,63`.
            //
            // The remedy is to MOVE, and it has to be here, because down in `pourInto` the only
            // thing left to try is digging — and the block in the way there is the bed itself, i.e.
            // the floor holding up the very water being poured into.
            BlockPos lands = JourneyFill.bucketPourLandsIn(rig, aim.below());
            // NOT GATED ON `tries`. Sharing the approach budget with the re-pick above lets the
            // re-pick spend it on a walk that changes nothing: on ladder-14's rung eleven three
            // passes ended in the identical cell, three identical rows collapsed into one, and by
            // the time the bot's own stance was the question the counter read zero. The remedy
            // documented at CAST_APPROACH_RADIUS, in the very terms of the geometry that failed the
            // rung (feet two rows below the bed, eye under its top face, side hits only), never got
            // a turn.
            //
            // `radius > 1` is the bound, and it is a better one: the radius only ever tightens, 2→1,
            // so this can fire exactly once and then never again. A counter shared between two
            // remedies is really one remedy, and it is whichever of them runs first.
            if (!aim.equals(lands) && radius > 1) {
                rig.evidence("cast.landsElsewhere." + approach, "pouring from " + at.toShortString()
                        + " would land in "
                        + (lands == null ? "MISS (the ray cannot reach the bed cell)"
                                         : lands.toShortString())
                        + ", not in the target " + aim.toShortString()
                        + ". The hit block is right but the face is wrong, so this is a standing"
                        + " position, not an obstruction; tightening to radius " + (radius - 1)
                        + " and walking again (only radius 1 forces the feet to be no lower than the"
                        + " bed's row, which puts the eye above the bed's top face)");
                approachAndPour(ctx, rig, aim, tries - 1, radius - 1);
                return;
            }
            pourInto(ctx, rig, aim);
        });
    }

    private static void pourInto(SceneContext ctx, JourneyRig rig, BlockPos target) {
        pourInto(ctx, rig, target, CAST_CLEARINGS);
    }

    /**
     * How many things the pour may clear out of its own line before giving up.
     *
     * <p>Two. The pour aims at the bed UNDER the water and the fluid lands in the cell in front of
     * whatever face the ray hits — so anything standing in that line silently moves the target one
     * cell. Measured: the rung chose `-15,62,42`, aimed at `-15,61,42`, and the pick came back
     * <b>`-15,62,42`</b> — the water cell itself, because it held <b>seagrass</b>. Seagrass has no
     * collision but it does have a {@code Block.OUTLINE} shape, which is what a bucket's clip uses.
     * The pour then reported `CONSUME`, the bucket emptied, and obsidian appeared at `-15,62,41`:
     * one cell short, cast against the near face of the plant. Swamp water is full of seagrass, so
     * this is terrain the ladder will meet every run, not an oddity.
     */
    private static final int CAST_CLEARINGS = 2;

    private static void pourInto(SceneContext ctx, JourneyRig rig, BlockPos target, int clearings) {
        ServerLevel level = ctx.level();
        var fp = rig.player();
        rig.evidence("cast.cell", target.toShortString());
        rig.evidence("cast.floor", String.valueOf(level.getBlockState(target.below()).getBlock()));
        rig.evidence("cast.range", String.format(java.util.Locale.ROOT, "%.2f",
                fp.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(target))));
        // Aimed at the FLOOR under the water, not at the water: a filled bucket ray-traces with
        // fluids ignored, so the block it lands on is the bed, and the fluid goes into the cell in
        // front of the face it hit — which is the water cell above.
        JourneyHands.holdForUse(rig, Items.LAVA_BUCKET, "cast");
        JourneyHands.aimThenAct(rig, target.below(), () -> {
            // hitFluids=false, matching what a FILLED bucket's own clip does. A pick that ignores
            // fluids is the only pick that predicts this pour.
            //
            // AND AT THE BUCKET'S OWN RANGE. This traced TUNNEL_REACH (5.0, the digging figure)
            // while the use it is predicting traces `blockInteractionRange()` = 4.5 — so between
            // 4.5 and 5.0 the guard approved a line the pour could not make, spending the run's one
            // bucket on a use that returns PASS and changes nothing. Third bucket site to carry the
            // wrong constant; the right one has been a javadoc in JourneyFill the whole time.
            var hit = JourneyHands.aimedAt(fp, JourneyFill.BUCKET_REACH, false);
            rig.evidence("cast.picks", hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    ? hit.getBlockPos().toShortString() + " " + level.getBlockState(hit.getBlockPos()).getBlock()
                      + " face=" + hit.getDirection()
                    : String.valueOf(hit.getType()));
            // Check the line BEFORE spending the bucket. The tunnel already works this way — mine
            // whatever the ray hits first, because that IS the obstruction — and the cast, which has
            // exactly one bucket of lava and no second try, did not. A pour down a blocked line is
            // not a failed pour: it is a SUCCESSFUL pour into the wrong cell, and by the time the
            // assertion reads the chosen cell the lava is gone.
            BlockPos bed = target.below();
            // AND THE FACE, NOT ONLY THE BLOCK. Vanilla `BucketItem.use` empties a non-water fluid
            // at `blockpos.relative(direction)` — the block the ray hit PLUS the face it hit. This
            // guard compared `getBlockPos()` alone for its whole life, while the comment fifteen
            // lines above stated the assumption it never checked («the fluid goes into the cell in
            // front of the face it hit — which is the water cell above»). ladder-1 lost rung 11 in
            // that gap: hit `3,61,62` from the south, guard said "right block", bucket spent,
            // obsidian at `3,61,63`, and the rung reported "casts obsidian in the chosen cell
            // (false)", the failure mode this method's own comment names as the worst one.
            //
            // NOT `face == UP`. A ray that hits the target's NEIGHBOUR on a side face and lands in
            // the target is a perfectly good pour; the invariant is the landing CELL, so the landing
            // cell is what is compared. (Asking for UP would refuse a whole class of legal pours —
            // the mirror mistake of the one being fixed.)
            BlockPos lands = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    ? hit.getBlockPos().relative(hit.getDirection()) : null;
            rig.evidence("cast.lands", (lands == null ? "MISS" : lands.toShortString())
                    + " (hit block + hit face, which is how vanilla BucketItem places); the target"
                    + " is " + target.toShortString()
                    + (target.equals(lands) ? " ✓ match" : " ✗ mismatch, not pouring"));
            boolean onLine = target.equals(lands);
            if (!onLine) {
                BlockPos inTheWay = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos() : null;
                // NEVER THE CELL UNDER THE BED. It is what holds the pool up, and a bot clearing
                // its way up to a pool above itself is digging out the floor of the water it wants
                // to pour into; the reward for succeeding is the pool on its own head. The re-pick
                // guard above should mean this line is never reached; it stays because a guard that
                // is only correct while its neighbour is correct is not a guard.
                boolean holdsThePool = inTheWay != null && inTheWay.equals(bed.below());
                if (holdsThePool)
                    rig.evidence("cast.wontClear", inTheWay.toShortString()
                            + " holds up the water cell being poured into; clearing it would drop"
                            + " the water onto the bot, so it is not cleared");
                // ⛔ NOR THE BED. The bed is what the aim ASKS the ray to hit, so "the ray hit the
                // bed" can never be an obstruction; reaching this line with `inTheWay == bed` means
                // the block was right and the FACE was wrong, which is a standing position and not
                // something in the way. Mining it would remove the floor under the target water and
                // drain the pool this pour needs, i.e. the fix for one defect opening a worse one.
                // Read `cast.lands` for where the fluid would have gone; `cast.landsElsewhere`
                // upstream is where a body still has the budget to move instead.
                boolean isTheBed = inTheWay != null && inTheWay.equals(bed);
                if (isTheBed)
                    rig.evidence("cast.wrongFace", inTheWay.toShortString()
                            + " is the aimed bed cell itself; the hit face is " + hit.getDirection()
                            + " ⇒ the fluid would land in "
                            + (lands == null ? "MISS" : lands.toShortString())
                            + ". This is a standing position, not an obstruction; mining it would"
                            + " remove the floor under that water cell, so it is not cleared");
                if (clearings > 0 && inTheWay != null && !holdsThePool && !isTheBed) {
                    // A plant stops the RAY but not the BODY. short_grass and seagrass have no
                    // collider — the body walks through them — yet `getPlayerPOVHitResult` clips on
                    // Block.OUTLINE, which a plant has, so they land square on the aiming line. And
                    // MineProcess will not remove them: measured, two clearings in a row left the
                    // same seagrass standing, and short_grass cost run 9 this rung.
                    //
                    // So swing at it directly, which is what a player does — aim, hold, destroy.
                    // This is the body's own verb, not staging: `staging.calls` stays 0 and the rung
                    // keeps its claim. Solid blockers still go through mine, where the drop matters.
                    boolean noCollider = level.getBlockState(inTheWay)
                            .getCollisionShape(level, inTheWay).isEmpty();
                    rig.evidence("cast.blockedBy", inTheWay.toShortString() + " "
                            + level.getBlockState(inTheWay).getBlock()
                            + (noCollider ? " (a plant with no collision box: swing at it directly,"
                                            + " mine cannot clear it)"
                                          : " (blocking the aiming line, clearing it first)"));
                    if (noCollider) {
                        // Aim BOTH bodies, then swing — see JourneyHands.swingOffPlant, which owns
                        // the whole shape and the two measurements that shaped it. Routing the break
                        // to the server avatar without moving the aim there (the previous fix here)
                        // left `aimTarget` null and destroyed nothing: this exact cell, `-4, 63, 55`,
                        // read back as `short_grass` on ladder-8 AND on the rehearsal that was run to
                        // verify that fix.
                        JourneyHands.swingOffPlant(rig, inTheWay);
                        rig.settle(new HoldStill(3), 12, () -> {
                            rig.evidence("cast.cleared." + inTheWay.toShortString(),
                                    String.valueOf(level.getBlockState(inTheWay).getBlock()));
                            pourInto(ctx, rig, target, clearings - 1);
                        });
                        return;
                    }
                    rig.mineBlock(inTheWay, 600, () -> rig.settle(new HoldStill(5), 20,
                            () -> pourInto(ctx, rig, target, clearings - 1)));
                    return;
                }
                // Out of clearings and the line is still not the bed: STOP. Pouring anyway spends
                // the run's only bucket of lava into whatever the ray does hit, and the rung then
                // reports "casts obsidian in the chosen cell (false)" — which reads as the cast
                // being broken, while `obsidian.anywhere` sits one metre away proving it is not.
                // A failure that names the obstruction is worth more than one that names the goal.
                // (Measured: two clearings in a row failed to remove the same seagrass, so the
                // third attempt poured blind. Why mineBlock cannot break it is a separate finding;
                // not choosing that cell in the first place is the fix, and this is the backstop.)
                ctx.fail((isTheBed ? "wrong standing position for the pour (not an obstruction): "
                                   : "the pour's aiming line is blocked and cannot be cleared: ")
                        + "target " + target.toShortString()
                        + " (aiming at " + bed.toShortString() + "), the ray stops at "
                        + (inTheWay == null ? String.valueOf(hit.getType())
                            : inTheWay.toShortString() + " " + level.getBlockState(inTheWay).getBlock()
                              + " face=" + hit.getDirection())
                        + ", the fluid would land in " + (lands == null ? "MISS" : lands.toShortString())
                        + ", the bot is at " + fp.blockPosition().toShortString()
                        + ". Not poured: there is only one bucket of lava, and pouring now would"
                        + " miss the cell and report the failure as \"no obsidian cast\"");
                return;
            }
            // THE HAND, RE-ASSERTED — `aimThenAct` settles ten ticks between the hold above and this
            // use, and ten ticks is enough for a hold that went down `ensureHolding`'s bag branch to
            // come undone on both player entities at once. See JourneyHands.regripBeforeUse; rung 12
            // lost a cast to it and reported the failure six movement tasks later.
            // ⚠️ Nothing between the hold and the use holds anything else, yet ladder-12 still saw
            // `cast.handSlipped` fire with COBBLESTONE in slot 0 on both player entities: the shaft
            // climb-out pillars up to 36 blocks of cobblestone, and the pillar's own hold is what
            // displaces the bucket. The interference comes from the previous movement task, not
            // from the next line.
            // SILENCE THE OTHER AUTHOR FOR THE LENGTH OF THE POUR.
            //
            // Re-gripping is as close to the use as a caller can get and ladder j46 proved it is
            // still not close enough: `cast.handSlipped` fired, `cast.again.hand` re-took the
            // bucket, `cast.atUse` recorded lava_bucket on BOTH ends — and then
            // `handTrace.t0.server` (gameTime=28476, slot 4 = lava_bucket) was followed by
            // `t1.server` (gameTime=28477, slot 4 = cobblestone ×29). The slot INDEX never moved (the
            // row prints `inv.selected`), so what changed is the slot's CONTENTS, which is
            // `BotInteract.ensureHoldingPillarBlock`'s tail: a real SWAP click that pulls a pillar
            // block out of the main inventory into the selected slot and pushes the bucket back
            // into the bag — hence `lava_bucket.after = 1` beside a cell that is still water.
            //
            // Its three call sites (one in `WalkerTickDrive.run`, two in
            // `WalkerTickStallDetect.run` — grep `holdPillarBlock`) and the two
            // `ensureHoldingPlaceableAny` ones (`Walker.widenFooting`, `Walker.strideFloorGuard` —
            // grep `holdPlaceable`) ALL short-circuit on
            // `BotConfig.allowPlace` before they touch the hand, so turning it off for these twelve
            // ticks makes the swap unreachable rather than merely unlikely. Nothing here needs to
            // place: the body is standing still, aimed, about to empty a bucket.
            //
            // Restored on every exit below — the fail branch and the settle's completion — because
            // the walk that follows the pour DOES need to pillar.
            boolean placeWas = BotConfig.allowPlace;
            BotConfig.allowPlace = false;
            // AND A NET UNDER IT. The two restores below cover every path this method can take, but
            // not the one it cannot: `cast.handTrace.samples` documents that a body which leaves the
            // world skips the settle outright, and then the flag would stay false for whatever runs
            // after — a global flipped by a scene that never came back. Cleanups drain on every exit,
            // including a timeout, so the flag cannot outlive the scene that turned it off.
            ctx.cleanup(() -> BotConfig.allowPlace = placeWas);
            rig.evidence("cast.placeHeldOff", "placement disabled for the duration of the pour"
                    + " (previous value " + placeWas + "); the pillar hold is the code that SWAPs"
                    + " the slot contents, and all three of its call sites short-circuit on"
                    + " allowPlace first");
            boolean gripped = JourneyHands.regripBeforeUse(rig, Items.LAVA_BUCKET, "cast");
            // BOTH BODIES AT THE INSTANT OF THE USE, unconditionally — the row ladder-12 needed and
            // did not have. `useItemInHand` is `MultiPlayerGameMode.useItem`, i.e. a CLIENT-side
            // prediction over the CLIENT's stack, so its SUCCESS says only "the client held a
            // bucket". That run returned SUCCESS and read `lava_bucket.after = 1` off the server
            // (a full bucket after a successful pour), and nothing on disk could say whether the
            // server had a bucket in that slot at all. regripBeforeUse checks `actingHolds`, which
            // is also the client; so "re-gripped" and "the server agrees" are two claims and both
            // must be recorded.
            JourneyHands.handsAtUse(rig, "cast");
            if (!gripped) {
                BotConfig.allowPlace = placeWas;
                ctx.fail("the hand about to pour does not hold minecraft:lava_bucket, and one"
                        + " re-grip did not fix it: " + JourneyHands.heldOnBoth(rig)
                        + ". Not poured: a use without the lava bucket in hand only returns PASS,"
                        + " and this rung would then report the failure as \"no obsidian cast\"");
                return;
            }
            // IS THE BODY STILL MOVING? `cast.atUse` shows both ends agreeing on the eye — but it
            // samples HERE, and the server runs the ray when it processes the packet, one or more
            // movement packets later. The angle cannot drift (ServerboundUseItemPacket carries
            // yRot/xRot and handleUseItem absRotateTo's before useItem); the POSITION can, and
            // `getPlayerPOVHitResult` starts the line at the eye. Over 2.98 blocks a few tenths
            // changes which face is hit, and a MISS returns PASS — which spends no bucket, changes
            // no block, and still lets the client predict SUCCESS. Exactly ladder-16's rung 11.
            //
            // The reason to suspect motion here rather than assume stillness: that rung's own
            // `cast.walk` row read `end=unavailable`, i.e. the walk was STILL DRIVING when its
            // budget ended. Releasing the controls is not braking.
            var vel = rig.player().getDeltaMovement();
            rig.evidence("cast.motionAtUse", String.format(java.util.Locale.ROOT,
                    "velocity=(%.4f,%.4f,%.4f) |horizontal|=%.4f onGround=%s; goto slot end=%s",
                    vel.x, vel.y, vel.z, Math.hypot(vel.x, vel.z), rig.player().onGround(),
                    String.valueOf(rig.slotEnd("goto"))));
            // THE CALIBRATION ROW, in the watcher's own format and taken BEFORE the use. It reads
            // the same instant `cast.atUse` does — nothing between the two lines ticks the server —
            // so if the two disagree the INSTRUMENT is broken and nothing below it may be read as a
            // fact about the world. Comparing the watcher's t0 against `cast.atUse` would not do
            // this job: t0 is taken after `useItemInHand` has already run.
            JourneyHands.handTrace(rig, "cast", -1);
            rig.evidence("cast.result", String.valueOf(rig.hands().useItemInHand()));
            // THE HAND ON CONSECUTIVE SERVER TICKS. `cast.result` is the CLIENT's prediction and
            // `cast.stillFull` is the SERVER ten ticks later; between them sits the moment that
            // decides this rung — the tick on which the server processes the use packet and reads
            // its OWN `inventory.selected`. Nothing here has ever sampled that, so a hand that was
            // right at the send and wrong at the handling is indistinguishable from a refusal.
            //
            // The watcher is the sampling site rather than the scene body because the watcher runs
            // on the SERVER thread (JourneyRig.settle → await → SceneContext.advance →
            // StageWrightHarness, which is driven from the server tick event). Sampling from a
            // per-tick step of this scene's own chain would read the server's inventory across the
            // same thread boundary the defect is about, i.e. copy the bug into the instrument.
            //
            // A counter rather than the watcher's own tick number: TickWatcher takes no argument,
            // and the window has to stop — 6 ticks × 2 bodies is 12 rows, and the rest of the
            // settle would add 8 more that answer nothing.
            int[] traced = {0};
            rig.settle(new HoldStill(10), 20, () -> {
                if (traced[0] < JourneyHands.TRACE_TICKS) {
                    JourneyHands.handTrace(rig, "cast", traced[0]++);
                }
            }, () -> {
                // FIRST LINE OF THE CALLBACK, before anything can fail or return: the pour is over,
                // and everything after it — the walk home, the next fetch — is allowed to pillar.
                BotConfig.allowPlace = placeWas;
                // HOW MANY TICKS THE INSTRUMENT ACTUALLY SAW, so that silence can be read. Three
                // outcomes have to look different on disk and this row is what separates them:
                // this row missing entirely ⇒ the run never reached the pour (not triggered,
                // evidence for neither side); this row present with 0 ⇒ the settle was skipped (the
                // bot left the world) and the instrument never fired, so its silence is also not
                // evidence; this row present with 6 ⇒ the trace rows above are the answer.
                rig.evidence("cast.handTrace.samples", "sampled " + traced[0] + "/"
                        + JourneyHands.TRACE_TICKS + " server ticks. t0 and useItemInHand fall on"
                        + " the same server tick (the settle's wait is evaluated once in the very"
                        + " advance() call that registers it), so go by each row's gameTime, not by"
                        + " the tick index. 0 sampled ⇒ the settle was skipped (the bot fell out of"
                        + " the world) and the instrument never fired, so this run's silence is not"
                        + " evidence; no cast.handTrace.* rows at all ⇒ this run never reached this"
                        + " pour, which is not evidence either.");
                var got = level.getBlockState(target).getBlock();
                rig.evidence("cast.cellAfter", String.valueOf(got));
                rig.evidence("lava_bucket.after", rig.carrying("minecraft:lava_bucket"));
                rig.evidence("bucket.after", rig.carrying("minecraft:bucket"));
                // Where it went when it did not go here. An empty bucket with no obsidian in the
                // chosen cell is a misplacement; a full bucket is a refusal; and the two want
                // opposite fixes.
                BlockPos anywhere = rig.nearestBlock("minecraft:obsidian", 8, 4);
                rig.evidence("obsidian.anywhere", anywhere == null ? "none" : anywhere.toShortString());
                // A THIRD outcome the pair above cannot name: the client predicted the pour and the
                // server never made it. `cast.result` comes from the client and this count comes
                // from the server, so "SUCCESS + the bucket is still full" is not a contradiction to
                // explain away; it is the two ends disagreeing, and it reads exactly like a refusal
                // until the two sources are named. Written whenever it happens, since the whole rung turns on
                // this one bucket.
                if (rig.carrying("minecraft:lava_bucket") > 0)
                    rig.evidence("cast.stillFull",
                            "the server still counts " + rig.carrying("minecraft:lava_bucket")
                            + " lava bucket(s), while cast.result is the client-side prediction of"
                            + " MultiPlayerGameMode.useItem; the two numbers come from the two ends."
                            + " To tell \"the server's hand was wrong\" from \"both ends held the"
                            + " bucket but this pour was refused\", "
                            // cast.atUse only answers for the moment the packet was sent; the server
                            // reads the hand at the moment it handles the packet, several ticks later,
                            // and cast.handTrace.* are the only rows that sample those ticks.
                            + "read the cast.handTrace.t*.server rows (consecutive server ticks"
                            + " after the use; check cast.handTrace.samples first to confirm the"
                            + " instrument fired). cast.atUse only covers the moment the packet was"
                            + " sent, not the moment it was handled."
                            // ⚠️ What follows is live state read again after the settle, not the
                            // cast.atUse row. Presenting it as a quote of that row would make one row
                            // claim to cite another while measuring again 10 ticks later, which misleads
                            // exactly on the runs where the two readings differ.
                            + " (the following was read again after the settle, 10 ticks later,"
                            + " not at the moment of the use) "
                            + JourneyHands.heldOnBoth(rig));
                ctx.expect(got == Blocks.OBSIDIAN)
                        .as("lava poured into standing water casts obsidian in the chosen cell").isTrue();
                // The claim, and what the claim is NOT. A rung that fetched its lava, climbed one
                // block of thirty-six, and then found water in the cave it was already standing in
                // has cast obsidian — the stage's whole assertion — while the exit it was supposed
                // to prove never happened. Saying so on the green row is the difference between a
                // ladder and a scoreboard.
                String how = rig.player().blockPosition().getY() < 50
                        ? " (poured underground at y=" + rig.player().blockPosition().getY()
                          + "; the bot did not climb back to the surface)"
                        : "";
                rig.reach("cast obsidian at " + target.toShortString()
                        + ", empty bucket back in the inventory ×"
                        + rig.carrying("minecraft:bucket") + how);
            });
        });
    }
}

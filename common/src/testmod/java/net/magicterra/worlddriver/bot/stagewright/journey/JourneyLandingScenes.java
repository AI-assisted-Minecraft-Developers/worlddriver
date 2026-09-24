package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Where a movement task leaves the bot, and the two recoveries that put it back on ground it can
 * work from.
 *
 * <p>Both fixes here were landed and then could not be judged. Their occasions are rare — a walk
 * home that ends on top of the tower it built, a climb out of a lava dive that ends still
 * swimming — and five consecutive ladder runs produced neither. Each run costs a quarter of an
 * hour and answers "did it happen this time"; what has to be answered is "when it happens, does
 * the recovery work". That question needs the occasion staged, which is what these two scenes are.
 *
 * <p><b>Both assert that the recovery was OBSERVED to run</b> — the evidence row it writes must be
 * present — rather than that the world ended up tidy. A body that was never on a tower also ends on
 * the ground; counting that as a pass is how a fix gets signed off without ever executing
 * (see the ladder's own history of exactly this).
 *
 * <p><b>Neither is staged to its threshold.</b> {@code SUNK_BELOW} is 8, so the tower is twelve
 * courses and the pit is twelve deep. A scene that only just crosses its own precondition stops
 * testing the fix the first time an unrelated constant moves.
 */
public final class JourneyLandingScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.journeyStepsDownOffItsOwnTower", 6_000,
                        JourneyLandingScenes::stepsDownOffItsOwnTower),
                Scene.of("wd.journeyClimbsOutOfItsOwnPit", 6_000,
                        JourneyLandingScenes::climbsOutOfItsOwnPit),
                // NOT REQUIRED, and standing RED on purpose — the same shelf `wd.vineOverWaterClimb`
                // and `wd.serverEscapeSealedShelter` sit on. It is not waiting on its own staging:
                // it has already driven two real fixes into the recovery (the tolerance that let
                // "arrived" mean one cell out in the water, and `afloat` answering "no" for a bot
                // treading water with no floor). What it is waiting on is TODO J47 — the pathfinder
                // returns `end=path-consumed` one cell short of a bank that is flush with the water,
                // so no amount of asking moves the body the last step. When J47 lands this flips to
                // PASS by itself, which is the whole reason it stays in the suite red rather than
                // being softened into something a broken walker can satisfy.
                Scene.of("wd.journeyGetsAshoreBeforePouring", 6_000,
                        JourneyLandingScenes::getsAshoreBeforePouring).withRequired(false),
                Scene.of("wd.journeyScoopsPastItsOwnObsidian", 6_000,
                        JourneyLandingScenes::scoopsPastItsOwnObsidian),
                Scene.of("wd.journeyScoopPrintsTheHandItFiredWith", 6_000,
                        JourneyLandingScenes::scoopPrintsTheHandItFiredWith),
                Scene.of("wd.journeyReseatsWhenItCanSeeNoWater", 6_000,
                        JourneyLandingScenes::reseatsWhenItCanSeeNoWater),
                Scene.of("wd.journeyKeepsTheSeatItMovedTo", 6_000,
                        JourneyLandingScenes::keepsTheSeatItMovedTo),
                Scene.of("wd.journeyFlightEndsOnADryStep", 6_000,
                        JourneyLandingScenes::flightEndsOnADryStep),
                // REQUIRED. It spent one afternoon on the optional shelf and came off it: first it
                // REFUTED the fix it was written to validate (a second leg aimed one step further
                // down walked the body two cells BACK and one row UP), then it named the real one.
                //
                // What it pins now is the turn before the last step. Its three readings, in order,
                // are the whole argument and none of them is an inference:
                //   two-cell staircase   → `end=failed:no path (expanded=2)`   (this arena's own bug)
                //   three-cell, no turn  → `end=path-consumed`, body 0.10 b short, `descentHolds=3`
                //   three-cell, turned   → `end=arrived`, `yawErr` 0, body in the terminal
                // Ten seconds a run under `-Pstagewright.scenes=`, and every one of those readings
                // came out of this arena rather than off a 50-minute ladder.
                Scene.of("wd.journeyWalksOffTheLipOntoTheDryStep", 6_000,
                        JourneyLandingScenes::walksOffTheLipOntoTheDryStep),
                // The airborne half of the same argument. The arm above asserts `onGround`, so the
                // family the rung-12 ladder actually died in is outside it by construction.
                Scene.of("wd.journeyJudgesTheLastStepAfterTheDropLands", 4_000,
                        JourneyLandingScenes::judgesTheLastStepAfterTheDropLands));
    }

    /** Natural ground level inside the arena box. */
    private static final int GROUND = 20;

    /** Courses of tower under the body. Twelve where the guard trips at eight. */
    private static final int TOWER = 12;

    /** How deep the pit is. Twelve, for the same reason. */
    private static final int PIT = 12;

    private static final int HALF = 12;

    // ------------------------------------------------------------ the tower ----

    private static void stepsDownOffItsOwnTower(SceneContext ctx) {
        ServerLevel level = ctx.level();
        ctx.cleanup(() -> clearBox(ctx));
        flatGround(ctx);
        // The tower, at the body's own column and NOT at the home column — the fix reads the home
        // column's heightmap precisely because the body's own reports the tower top.
        for (int dy = 1; dy <= TOWER; dy++) ctx.setBlock(0, GROUND + dy, 0, Blocks.COBBLESTONE);

        BlockPos home = ctx.rel(3, GROUND + 1, 0);
        ServerWorldDriver driver = SceneBody.managed(ctx, ctx.rel(0, GROUND + TOWER + 1, 0));
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        BlockPos foot = fp.blockPosition();
        int ground = JourneyTerrain.daylightAt(level, home);
        int off = foot.getY() - ground;
        ctx.record("staged.foot", foot.toShortString() + ", block below="
                + level.getBlockState(foot.below()).getBlock());
        ctx.record("staged.off", "bot y=" + foot.getY() + ", spawn column ground y=" + ground + ", difference " + off);
        ctx.check(off >= TOWER - 1).as("control: the bot must really be up on the tower top, well past the guard's"
                + " 8-block threshold, otherwise \"the recovery ran\" measures something else: difference "
                + off + " blocks").isTrue();

        int cobbleBefore = countOf(fp, Items.COBBLESTONE);
        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.BED, driver);
        WorldDriverJourneyScenes.settleOntoHomeGround(rig, "bed", home, () -> {
            BlockPos ended = fp.blockPosition();
            Object elev = rig.evidenceOf("bed.homeElevation");
            Object recovered = rig.evidenceOf("bed.towerRecovered");
            ctx.record("subject.homeElevation", String.valueOf(elev));
            ctx.record("subject.towerRecovered", String.valueOf(recovered));
            ctx.record("subject.endedAt", ended.toShortString() + ", block below="
                    + level.getBlockState(ended.below()).getBlock());
            ctx.record("subject.cobble", cobbleBefore + " → " + countOf(fp, Items.COBBLESTONE));

            ctx.check(elev).as("A the elevation row must be written **on every path**, otherwise \"it is flat\""
                    + " and \"nobody measured\" cannot be told apart: " + elev).isNotNull();
            ctx.check(recovered).as("B the tower-removal branch must be **observed to complete** -"
                    + " `bed.towerRecovered` must be written. A without B means the guard saw the elevation"
                    + " difference and did nothing: " + recovered).isNotNull();
            ctx.check(ended.getY() - ground <= 2).as("C the bot really ended near the ground (judged by the"
                    + " end position, not by the number of steps): end y=" + ended.getY() + ", ground y="
                    + ground).isTrue();
            ctx.check(countOf(fp, Items.COBBLESTONE) > cobbleBefore).as(
                    "D the tower was mined back into the inventory - every course is a block the walker spent,"
                    + " and recovering them is the **purpose** of removing the tower, not a side effect: "
                    + cobbleBefore + " → " + countOf(fp, Items.COBBLESTONE)).isTrue();
        });
    }

    // -------------------------------------------------------------- the pit ----

    private static void climbsOutOfItsOwnPit(SceneContext ctx) {
        ServerLevel level = ctx.level();
        ctx.cleanup(() -> clearBox(ctx));
        flatGround(ctx);
        // A dry shaft under the body: the OTHER direction of the same guard, which had never been
        // exercised either. Dug one wide, as the ladder's own shafts are.
        for (int dy = 0; dy > -PIT; dy--) ctx.setBlock(0, GROUND + dy, 0, Blocks.AIR);

        BlockPos home = ctx.rel(3, GROUND + 1, 0);
        ServerWorldDriver driver = SceneBody.managed(ctx, ctx.rel(0, GROUND - PIT + 1, 0));
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().items.set(1, new ItemStack(Items.STONE_PICKAXE));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        BlockPos foot = fp.blockPosition();
        int ground = JourneyTerrain.daylightAt(level, home);
        int off = foot.getY() - ground;
        ctx.record("staged.foot", foot.toShortString());
        ctx.record("staged.off", "bot y=" + foot.getY() + ", spawn column ground y=" + ground + ", difference " + off);
        ctx.check(off <= -(PIT - 2)).as("control: the bot must really be deep at the pit bottom, well past the"
                + " threshold: difference " + off + " blocks").isTrue();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.BED, driver);
        WorldDriverJourneyScenes.settleOntoHomeGround(rig, "bed", home, () -> {
            BlockPos ended = fp.blockPosition();
            Object climbed = rig.evidenceOf("bed.climbedBackTo");
            ctx.record("subject.homeElevation", String.valueOf(rig.evidenceOf("bed.homeElevation")));
            ctx.record("subject.climbedBackTo", String.valueOf(climbed));
            ctx.record("subject.endedAt", ended.toShortString() + ", block below="
                    + level.getBlockState(ended.below()).getBlock());

            ctx.check(rig.evidenceOf("bed.homeElevation")).as("A the elevation row must be written on every path")
                    .isNotNull();
            ctx.check(climbed).as("B the climb-out branch must be **observed to complete** -"
                    + " `bed.climbedBackTo` must be written: " + climbed).isNotNull();
            ctx.check(ended.getY() >= ground - 2).as("C the bot really returned to ground level: end y="
                    + ended.getY() + ", ground y=" + ground).isTrue();
        });
    }

    // ------------------------------------------------------------ the water ----

    /** Cut INTO the flat stone rather than piled on top of it: water whose top row sits above the
     *  surrounding surface flows away, and a scene whose pool drains between staging and measuring
     *  is staging nothing. The rim is the untouched stone at {@code |dx|,|dz| > POOL_HALF}. */
    private static final int POOL_DEPTH = 6;
    private static final int POOL_HALF = 4;

    /** How far under the surface the body starts. Two, so a body that bobs up a cell while the three
     *  settling steps run is still in water when the recovery reads it. */
    private static final int SUBMERGED = 2;

    private static void getsAshoreBeforePouring(SceneContext ctx) {
        ServerLevel level = ctx.level();
        ctx.cleanup(() -> clearBox(ctx));
        flatGround(ctx);
        for (int dx = -POOL_HALF; dx <= POOL_HALF; dx++)
            for (int dz = -POOL_HALF; dz <= POOL_HALF; dz++)
                for (int dy = 0; dy > -POOL_DEPTH; dy--)
                    ctx.setBlock(dx, GROUND + dy, dz, Blocks.WATER);

        ServerWorldDriver driver = SceneBody.managed(ctx, ctx.rel(0, GROUND - SUBMERGED, 0));
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.LAVA_BUCKET));
        // AND BLOCKS, because the real body has them. The first staging carried only the bucket and
        // the body could not get out of the pool at all: `end=path-consumed` one cell from the bank,
        // every run. `WalkerTickClimb:557` gates the swim escape on `holdPlaceable()`, so a body
        // with nothing to place has no way up out of water — and the ladder's body always has
        // something (the run this scene is about recorded `pillarStock = minecraft:dirt ×30`).
        // Staging it empty-handed was testing a body the ladder never has.
        fp.getInventory().items.set(1, new ItemStack(Items.COBBLESTONE, 32));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        BlockPos foot = fp.blockPosition();
        ctx.record("staged.foot", foot.toShortString() + ", foot cell="
                + level.getBlockState(foot).getBlock() + ", block below="
                + level.getBlockState(foot.below()).getBlock());
        ctx.check(JourneyShaft.afloat(level, foot)).as(
                "control A: the bot must really be afloat (foot cell and the cell below are both fluid),"
                + " otherwise the recovery is never triggered and \"got ashore\" is 0==0: foot cell="
                + level.getBlockState(foot).getBlock()
                + ", block below=" + level.getBlockState(foot.below()).getBlock()).isTrue();
        BlockPos bank = JourneyTerrain.nearestDryColumn(level, foot, 16);
        ctx.check(bank).as("control B: the bank must be in reach, otherwise the recovery has no route").isNotNull();
        // WHAT THE CHOSEN COLUMN ACTUALLY IS, spelled out. The first two arena runs both ended one
        // cell short of it and no row on disk could say whether that was the walker refusing a step
        // or the target being a cell nothing can stand in. Reasoning about the staging arithmetic
        // got the wrong answer twice; this asks the world.
        ctx.record("staged.bankColumn", column(level, bank));
        ctx.record("staged.bodyColumn", column(level, foot));

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.OBSIDIAN, driver);
        JourneyCast.standOnDryGround(rig, () -> {
            BlockPos ended = fp.blockPosition();
            Object afloat = rig.evidenceOf("lava.exit.afloat");
            Object dry = rig.evidenceOf("lava.exit.dryLand");
            ctx.record("subject.afloat", String.valueOf(afloat));
            ctx.record("subject.dryLand", String.valueOf(dry));
            ctx.record("subject.endedAt", ended.toShortString() + ", block below="
                    + level.getBlockState(ended.below()).getBlock());

            ctx.check(String.valueOf(afloat).startsWith("yes")).as(
                    "A the recovery must be **observed to start** - `lava.exit.afloat` must read as yes: "
                    + afloat).isTrue();
            ctx.check(dry).as("B and it really chose a bank column: " + dry).isNotNull();
            ctx.check(level.getFluidState(ended.below()).isEmpty()).as(
                    "C the bot ended with a solid block below its feet, not water (judged by the end position,"
                    + " not by the number of blocks walked): " + ended.toShortString()
                    + ", block below=" + level.getBlockState(ended.below()).getBlock()).isTrue();
        });
    }

    // --------------------------------------------------- the lidded source ----

    /**
     * A bucket must fill from a source it can SEE, not the one that is nearest.
     *
     * <p><b>The occasion, and why it needs staging.</b> Rung 12 opens by scooping water, standing
     * where rung 11 just cast obsidian — and rung 11 casts it into the very pond rung 12 drinks
     * from. On ladder j50 the body happened to stand at y=62 and the fresh obsidian sat on the
     * diagonal to the nearest source: {@code waterFill.result = FAIL}, rung 12 dead at tick 7.
     * On j48 and j51 the body happened to stand at y=63, its ray cleared the obsidian's top face,
     * and the same code filled. Same coordinate, same 1.4 blocks, opposite outcomes — the seat
     * decided it, so no number of ladder runs decides anything. This stages the bad seat.
     *
     * <p><b>Why a lid rather than a wall.</b> A wall has to be placed on the exact line the ray
     * takes, which is arithmetic this scene would then be testing instead of the fix. A lid
     * directly ABOVE the near source blocks every ray from every body standing higher than it, so
     * the staging cannot quietly stop reproducing the trap when an unrelated constant moves.
     *
     * <p><b>What it asserts, in order.</b> First that the trap is real (the distance-ranked finder
     * still picks the lidded cell — otherwise every later check is 0==0), then that the engine's
     * own clip agrees the lid blocks it (the ruler, before the measurement), then that the
     * line-of-sight finder picks a different cell, and finally that a bucket used at that cell
     * actually fills. The last one is the only one that is end-to-end, and the first one is the
     * only one that keeps the last one honest.
     */
    private static void scoopsPastItsOwnObsidian(SceneContext ctx) {
        ServerLevel level = ctx.level();
        ctx.cleanup(() -> clearBox(ctx));
        flatGround(ctx);

        // Cut both sources INTO the stone, for the reason the pool above is cut in: water whose top
        // row sits proud of the surface flows away, and a scene whose sources drain before it
        // measures them has staged nothing.
        BlockPos lidded = ctx.rel(-1, GROUND, -1);
        BlockPos open = ctx.rel(2, GROUND, 0);
        ctx.setBlock(-1, GROUND, -1, Blocks.WATER);
        ctx.setBlock(2, GROUND, 0, Blocks.WATER);
        // THE OBSIDIAN, and obsidian specifically rather than any solid: this is the block the real
        // failure was made of, and a reader who greps for it should land here.
        ctx.setBlock(-1, GROUND + 1, -1, Blocks.OBSIDIAN);

        ServerWorldDriver driver = SceneBody.managed(ctx, ctx.rel(0, GROUND + 1, 0));
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.BUCKET));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.OBSIDIAN, driver);
        BlockPos foot = fp.blockPosition();
        BlockPos nearest = JourneyTerrain.shallowWaterNear(rig, 8);
        BlockPos visible = JourneyFill.visibleSourceNear(rig, false, JourneyFill.FILL_RESEARCH);
        ctx.record("staged.foot", foot.toShortString() + ", eye y=" + fp.getEyePosition().y);
        ctx.record("staged.lidded", lidded.toShortString() + ", block above="
                + level.getBlockState(lidded.above()).getBlock());
        ctx.record("staged.open", open.toShortString() + ", block above="
                + level.getBlockState(open.above()).getBlock());
        ctx.record("subject.nearest", String.valueOf(nearest));
        ctx.record("subject.visible", String.valueOf(visible));

        ctx.check(lidded.equals(nearest)).as("control A: the nearest source by distance **must** be the lidded"
                + " cell, otherwise this test setup never built the trap and every later check is 0==0:"
                + " the distance-ranked choice is " + nearest).isTrue();
        ctx.check(JourneyFill.bucketLineLandsOn(rig, lidded, true)).as(
                "control B: calibrate the ruler first - the engine's own clip must **agree** that the lid"
                + " blocks that cell, so this check requires false. True means the lid does not block it,"
                + " and A did not build a trap").isFalse();

        ctx.check(visible).as("C the line-of-sight finder must return a cell - if it returns none, it rejected"
                + " both").isNotNull();
        ctx.check(!lidded.equals(visible)).as("D and it must **not** be the lidded cell: it chose " + visible)
                .isTrue();
        ctx.check(open.equals(visible)).as("E the chosen cell is exactly the open water source: expected " + open
                + ", actual " + visible).isTrue();

        // END TO END. Everything above is about choosing; this is the bucket. `waterFill.result`
        // is the same key the rung writes, on purpose — a reader comparing this scene against a
        // ladder run should be reading the same row name.
        int before = fp.getInventory().countItem(Items.WATER_BUCKET);
        JourneyHands.aimThenAct(rig, visible, () -> {
            JourneyHands.holdForUse(rig, Items.BUCKET, "waterFill");
            JourneyHands.handsAtUse(rig, "waterFill");
            rig.evidence("waterFill.result", String.valueOf(rig.hands().useItemInHand()));
            rig.settle(new HoldStill(3), 12, () -> {
                int after = fp.getInventory().countItem(Items.WATER_BUCKET);
                ctx.record("subject.waterBucket", before + " → " + after);
                ctx.record("subject.result", String.valueOf(rig.evidenceOf("waterFill.result")));
                ctx.record("subject.atUse", String.valueOf(rig.evidenceOf("waterFill.atUse")));
                ctx.check(after > before).as("F the bucket really filled with water (judged by stock, not by"
                        + " the use's return value - an empty-bucket use returns FAIL when the ray lands on a"
                        + " block that is not a BucketPickup and PASS when it hits nothing, and neither changes"
                        + " the stock): " + before + " → " + after
                        + "; the hands and both rays at that moment: " + rig.evidenceOf("waterFill.atUse")).isTrue();
            });
        });
    }

    /**
     * The scoop's own use has to print the reading the pour has had all along.
     *
     * <p><b>Why the scene right above this one does not catch it.</b>
     * {@link #scoopsPastItsOwnObsidian} calls {@link JourneyHands#handsAtUse} ITSELF, two lines
     * before its own {@code useItemInHand} — so it stays green whether or not the production path
     * takes that reading, and the production path did not. Ladder-18 paid for the gap:
     * {@code recover6.miss.3 = minecraft:water_bucket 0→0 …}, with the ray stopping on
     * {@code Block{minecraft:water}} at {@code 4, 59, 19}, has three authors and no row could
     * separate them — the acting hand was
     * not the bucket ({@code recover6.hand = minecraft:cobblestone}, {@code hand#2} the bucket, one
     * settle apart), the cell was water and not a source, or {@code BucketItem}'s own
     * {@code SOURCE_ONLY} clip is simply not the ray this file's instrument fires.
     *
     * <p>So this one drives {@link JourneyFill#fillFrom} — the entry the rung calls — and then asks
     * the EVIDENCE what it recorded, not the world what it looks like. The world looks the same
     * either way; that is the whole point of an instrument.
     *
     * <p>Every assertion carries its own value in the message. A row that merely "exists" would go
     * green on an empty string, and the field that answers the third author is the PAIR of rays:
     * an empty bucket clips {@code SOURCE_ONLY} and a full one clips {@code NONE}, and over water
     * the two answers differ.
     */
    private static void scoopPrintsTheHandItFiredWith(SceneContext ctx) {
        ctx.cleanup(() -> clearBox(ctx));
        flatGround(ctx);

        // Cut into the stone for the reason every pool in this file is cut in: water proud of the
        // surface flows away, and a scene whose source drains before it measures anything has
        // staged nothing.
        BlockPos open = ctx.rel(2, GROUND, 0);
        ctx.setBlock(2, GROUND, 0, Blocks.WATER);

        ServerWorldDriver driver = SceneBody.managed(ctx, ctx.rel(0, GROUND + 1, 0));
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.BUCKET));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.OBSIDIAN, driver);
        int before = fp.getInventory().countItem(Items.WATER_BUCKET);
        ctx.record("staged", "water source " + open.toShortString() + ", source="
                + ctx.level().getFluidState(open).isSource() + "; bot at "
                + fp.blockPosition().toShortString() + ", carrying minecraft:bucket ×"
                + fp.getInventory().countItem(Items.BUCKET));

        JourneyFill.fillFrom(ctx, rig, open, "probe", Items.WATER_BUCKET, () -> {
            String atUse = String.valueOf(rig.evidenceOf("probe.atUse"));
            String result = String.valueOf(rig.evidenceOf("probe.result"));
            int after = fp.getInventory().countItem(Items.WATER_BUCKET);
            ctx.record("probe.result", result);
            ctx.record("probe.atUse", atUse);
            ctx.record("probe.waterBucket", before + " → " + after);

            // A FIRST, because everything below reads rows this path writes. If the production path
            // never reached its own use, B–D would be asking an empty transcript and would fail for
            // a reason that has nothing to do with the instrument.
            ctx.check(!"null".equals(result)).as("A control: the production path really reached its own use -"
                    + " probe.result actual " + result).isTrue();
            ctx.check(after > before).as("B and that use really filled the bucket with water (judged by stock,"
                    + " not by the use's return value: an empty-bucket use returns FAIL on a block that is not a"
                    + " BucketPickup and PASS when it hits nothing, and neither changes the stock): "
                    + before + " → " + after).isTrue();

            ctx.check(!"null".equals(atUse)).as("C the water-collecting use printed the reading taken at the"
                    + " moment of the use - before this instrument was added the row did not exist, while the"
                    + " pour side always had it (cast6.atUse). Actual " + atUse).isTrue();
            ctx.check(atUse.contains("minecraft:bucket")).as("D and it reads **the acting hand**, **before the use**:"
                    + " at this moment the hand must hold the empty minecraft:bucket, which only becomes"
                    + " minecraft:water_bucket after filling - reading water_bucket means this row was taken too"
                    + " late. Actual " + atUse).isTrue();
            ctx.check(atUse.contains("full-bucket ray") && atUse.contains("empty-bucket ray")).as(
                    "E the rays for both fluid modes are present (this pair is exactly the field that separates"
                    + " \"the bucket's own SOURCE_ONLY ray\" from \"the scene instrument's ray\", and with one"
                    + " missing they cannot be told apart). Actual " + atUse).isTrue();

            // F/G — the OTHER reading ladder-18 needed and did not have. Same scene rather than a
            // second one: both ask whether the readings taken at this use are enough to determine the
            // cause, and this arena already produces the aim row for free.
            String aimsAt = String.valueOf(rig.evidenceOf("probe.aimsAt"));
            ctx.record("probe.aimsAt", aimsAt);
            ctx.check(aimsAt.contains("feet y=") && aimsAt.contains("onGround=")).as(
                    "F the aim row states whether the bot has landed - before this field was added it printed"
                    + " only the eye, and a ray fired by a bot that is still falling is not the ray that was"
                    + " verified (ladder-18 recover6: eye 60.48 ⇒ feet 58.86, while blockPosition() reported 58)."
                    + " Actual " + aimsAt).isTrue();
            ctx.check(aimsAt.contains("resting on an integer row")).as(
                    "G and this time it answers \"landed\" - the bot in this scene stands on solid ground, so"
                    + " printing the ★ branch means the criterion is always true or always false instead of"
                    + " reading the bot's position. Actual " + aimsAt).isTrue();
        });
    }

    // ------------------------------------------------------- the bank in the way ----

    /**
     * When no source is visible from where it stands, the scoop must MOVE — not spend its one use.
     *
     * <p><b>Why this is a different scene from {@link #scoopsPastItsOwnObsidian} and not a case of
     * it.</b> That one stages a blocked source next to an open one, so the chooser has a right
     * answer to find and never enters the re-seat at all. This one stages a pond with <i>no</i>
     * seat-visible source, which is the other branch: {@code visibleSourceNear} returns null and the
     * only thing left to change is the body's own cell. Ladder j52 measured exactly that
     * ({@code waterFill.aim} reported that no water source was visible from this eye) and then aimed
     * anyway.
     *
     * <p><b>Why a bank and not a lid.</b> A lid over the only source blocks the ray from every cell
     * — including the one the re-seat would move to — so {@code standToScoop} returns null too and
     * the branch has nowhere to go. The trap has to be <i>directional</i>, and the ladder's real one
     * was: {@code empty-bucket ray -5,62,55 minecraft:grass_block (1.05 blocks)} is a bank one
     * block above the
     * body's feet, and the same pond filled a bucket on the first try from one block higher (j48,
     * j51). So the staging is a seat cut one below the bank, with the pond behind it.
     *
     * <p><b>The body is staged exactly {@code Goal.Near}'s radius from the pond</b> (distSqr 4 for
     * radius 2, and {@code reached} is {@code <=}), so the approach the scoop opens with is already
     * satisfied and cannot quietly walk the body out of the trap before the branch is reached. That
     * is asserted, not assumed — control A asks the engine, after the settle.
     */
    private static void reseatsWhenItCanSeeNoWater(SceneContext ctx) {
        bankedPondScoop(ctx, false);
    }

    /**
     * The same trap, with the only workable seat beside a DIFFERENT pond — far outside the approach.
     *
     * <p><b>Why this is not the same scene twice.</b> The first run of the scene above chose the
     * bank top as its seat, {@code distSqr} 2 from the pond, i.e. already inside
     * {@code Goal.Near(water, 2)} — so the re-entry's second approach had nothing to do and the
     * guard against it undoing the move <b>never executed</b>. Recorded as not triggered, not passed.
     *
     * <p><b>And raising the bank would not have fixed that.</b> {@code standToFill} only ever
     * returns cells in a source's own 3×3 neighbourhood ({@code dy ∈ [-2,1]}), so the seat is at
     * most {@code distSqr} 6 from <i>the source it was chosen for</i> — and with one pond that
     * source IS {@code water}, which puts every possible seat within a whisker of the approach.
     * Arithmetic, not luck: no amount of stacking stone moves it.
     *
     * <p><b>What actually separates them is that the seat may belong to another source.</b>
     * {@code standToScoop} searches {@code FILL_RESEARCH} = 8 around the POND, so a second pond
     * seven blocks away contributes its own rim cells — and a seat there is seven blocks from the
     * {@code water} the re-entry would walk back to. So: ring the near pond at head height, which
     * kills every seat around it while leaving it open above (still the distance-nearest); and put
     * the far pond outside {@code visibleSourceNear}'s radius <i>of the body</i> but inside
     * {@code standToScoop}'s radius <i>of the near pond</i>. Without the guard, the re-entry walks
     * the body all the way back to the ringed pond it cannot drink from, and the scene says so.
     */
    private static void keepsTheSeatItMovedTo(SceneContext ctx) {
        bankedPondScoop(ctx, true);
    }

    /** How far west the second pond sits. Nine: outside {@code visibleSourceNear}'s radius 8 of a
     *  body two blocks east of the first pond, and seven from that pond, so it is inside
     *  {@code standToScoop}'s radius 8 of it. Both halves of that sentence are asserted. */
    private static final int FAR_POND_DX = -9;

    /**
     * @param farSeat false stages the ladder's own geometry — the bank top is standable and is what
     *        the re-seat picks, {@code distSqr} 2 from the pond. True rings the near pond so it
     *        yields no seat at all and adds a second pond whose rim is the only seat there is.
     */
    private static void bankedPondScoop(SceneContext ctx, boolean farSeat) {
        ServerLevel level = ctx.level();
        ctx.cleanup(() -> clearBox(ctx));
        flatGround(ctx);

        // The seat, one below the surface. The bank is the untouched stone at dx=-1.
        ctx.setBlock(0, GROUND, 0, Blocks.AIR);
        // The pond, cut in so its top is flush with the surface and it cannot flow away: a lone
        // source walled by stone on all four sides and floored by it.
        BlockPos pond = ctx.rel(-2, GROUND, 0);
        ctx.setBlock(-2, GROUND, 0, Blocks.WATER);
        if (farSeat) {
            // RING the near pond at head height — all eight neighbours, leaving the cell directly
            // above it open. That is what makes it yield no seat: every candidate `standToFill`
            // would consider around it is either this ring (foot cell occupied) or the original
            // stone below it. Open above, so it is still what `shallowWaterNear` ranks first, which
            // is what keeps it the `water` the re-entry would walk back to.
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dz != 0)
                        ctx.setBlock(-2 + dx, GROUND + 1, dz, Blocks.STONE);
            ctx.setBlock(FAR_POND_DX, GROUND, 0, Blocks.WATER);
        }

        ServerWorldDriver driver = SceneBody.managed(ctx, ctx.rel(0, GROUND, 0));
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.BUCKET));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.OBSIDIAN, driver);
        BlockPos seated = fp.blockPosition();
        BlockPos visible = JourneyFill.visibleSourceNear(rig, false, JourneyFill.FILL_RESEARCH);
        BlockPos nearest = JourneyTerrain.shallowWaterNear(rig, 8);
        BlockPos seat = nearest == null ? null : JourneyFill.standToScoop(rig, nearest);
        // ABSOLUTE, not the arena-relative constant. `GROUND` is an offset handed to `ctx.rel`; the
        // first run of this scene printed "bank top y=21" beside a bot at y=220, which is the kind
        // of row that costs an hour to a reader who trusts it.
        BlockPos bank = ctx.rel(-1, GROUND, 0);
        ctx.record("staged.foot", seated.toShortString() + ", eye y=" + fp.getEyePosition().y
                + ", bank " + bank.toShortString() + " top y=" + (bank.getY() + 1 + (farSeat ? 1 : 0))
                + (farSeat ? " (near pond ringed on all sides, with a separate far pond)"
                           : " (natural bank, not raised)"));
        ctx.record("staged.pond", pond.toShortString() + ", block above="
                + level.getBlockState(pond.above()).getBlock() + ", distSqr to the bot="
                + seated.distSqr(pond));
        ctx.record("staged.visible", String.valueOf(visible));
        ctx.record("staged.nearest", String.valueOf(nearest));
        ctx.record("staged.seat", String.valueOf(seat));

        ctx.check(visible).as("control A: from this seat **no** water source may be visible - if one is,"
                + " the bank does not block the ray, the re-seat branch never runs, and every later check is"
                + " 0==0: the line-of-sight finder returned " + visible).isNull();
        ctx.check(nearest).as("control B: the distance-ranked finder must still find this pond, otherwise"
                + " `pool` is null and a different branch runs").isNotNull();
        ctx.check(seat).as("control C: the re-seat must **have somewhere to go** - when `standToScoop` returns"
                + " null this branch only prints \"cannot re-seat\", which is not what this scene judges")
                .isNotNull();
        ctx.check(!seated.equals(seat)).as("control D: and that stand is not the current cell: the chosen"
                + " stand is " + seat + ", the bot is at " + seated).isTrue();
        if (farSeat) {
            // THE CONTROL THAT MAKES THIS VARIANT A DIFFERENT TEST. Without it a run whose seat
            // landed inside the approach would pass exactly as the near-pond scene does, and G
            // below would once again be judging nothing.
            ctx.check(seat != null && seat.distSqr(pond) > 4).as(
                    "control D': this variant **must** force the seat outside the `Goal.Near(water,2)`"
                    + " radius - inside it, the approach on re-entry has nothing to do and G is 0==0 again:"
                    + " seat " + seat + " distSqr to the pond="
                    + (seat == null ? "—" : String.valueOf(seat.distSqr(pond)))).isTrue();
            // And that the two radii really do separate: the far pond must be OUT of the body's
            // reach (else A above would have found it and there is no trap) and IN the near pond's
            // (else `standToScoop` never sees it and there is no seat). Both are staged by one
            // number, so one row proves or kills the whole geometry.
            BlockPos far = ctx.rel(FAR_POND_DX, GROUND, 0);
            ctx.record("staged.far", far.toShortString() + ", distSqr to the bot=" + seated.distSqr(far)
                    + ", distSqr to the near pond=" + pond.distSqr(far));
            ctx.check(seat != null && seat.distSqr(far) < seat.distSqr(pond)).as(
                    "control D'': and the chosen seat belongs to the **far pond**, not the near pond: seat " + seat
                    + " distSqr to the far pond=" + (seat == null ? "—" : String.valueOf(seat.distSqr(far)))
                    + ", distSqr to the near pond=" + (seat == null ? "—" : String.valueOf(seat.distSqr(pond)))
                    + " - if it still belongs to the near pond, the ring did not seal off every stand around"
                    + " the near pond").isTrue();
        }

        int before = fp.getInventory().countItem(Items.WATER_BUCKET);
        JourneyFill.scoopWaterOnly(ctx, rig, pond, () -> {
            BlockPos ended = fp.blockPosition();
            Object reseat = rig.evidenceOf("waterFill.reseat");
            int after = fp.getInventory().countItem(Items.WATER_BUCKET);
            ctx.record("subject.reseat", String.valueOf(reseat));
            ctx.record("subject.endedAt", ended.toShortString());
            ctx.record("subject.waterBucket", before + " → " + after);
            ctx.record("subject.aim", String.valueOf(rig.evidenceOf("waterFill.aim")));
            ctx.record("subject.result", String.valueOf(rig.evidenceOf("waterFill.result")));

            // E FIRST, because `then` only runs when the fill already succeeded — so without this
            // the scene would sign off a fill that happened to work from the bad seat and never
            // moved. The row must be present AND must read as a move: the same key carries the
            // "cannot re-seat" refusals, and a refusal is not a re-seat.
            ctx.check(reseat).as("E the re-seat branch must be **observed to run** - `waterFill.reseat` must be"
                    + " written. If it is not, the bucket was filled from the bad seat itself and this branch"
                    + " still has never executed").isNotNull();
            ctx.check(!String.valueOf(reseat).startsWith("cannot re-seat")).as(
                    "F and it really moved, rather than printing a refusal: " + reseat).isTrue();
            // G — the concern that the re-entry re-runs `Goal.Near(water,2)` and can walk the body
            // off the seat it just paid for. One pond cannot make that fail, so this is a guard for
            // the day a second source is within `FILL_RESEARCH` of the first, not a measurement of
            // it today.
            // G ASKS A DIFFERENT QUESTION IN THE TWO VARIANTS, and deliberately so.
            //
            // The near-pond variant asks for the exact cell, which it reaches: its re-seat is one
            // step onto the bank. The far variant cannot ask that — the walk is a dozen blocks and
            // `Goal.Block` reports "arrived" one cell out (the first staging of it measured expected
            // `…382,221,99999`, actual `…381,221,99999`, which is arrival tolerance and not a bot
            // that got dragged anywhere). Asking for cell identity there would red the scene for the
            // walker's tolerance while the thing under test was fine.
            //
            // What the guard actually promises is "the re-entry did not walk the bot back to
            // `water`", so that is what the far variant asks: still OUTSIDE the approach radius.
            // It is not a weaker question — a regression walks the bot to within 2 of the pond by
            // construction, which is exactly what this refuses.
            if (farSeat) {
                ctx.check(ended.distSqr(pond) > 4).as("G at the moment of the bucket use the bot is **still"
                        + " outside the `Goal.Near(near pond,2)` radius** - if the re-entry ran the approach again,"
                        + " it would drag the bot back to the pond it cannot drink from: end " + ended
                        + " distSqr to the near pond=" + ended.distSqr(pond)
                        + ", the seat it moved to is " + seat).isTrue();
            } else {
                ctx.check(ended.equals(seat)).as("G at the moment of the bucket use the bot stands on the seat"
                        + " it moved to and was not carried off again by a second `Goal.Near`: expected " + seat
                        + ", actual " + ended
                        + " - in the near-pond variant the seat is already inside the radius, so this is only a"
                        + " regression guard").isTrue();
            }
            ctx.check(after > before).as("H the bucket really filled with water (judged by stock, not by the"
                    + " use's return value): " + before + " → " + after).isTrue();
        });
    }

    // ------------------------------------------- where the flight itself ends ----

    /** Steps in the staged flight. Five, so that the stride (4) and the terminal are different
     *  indices — a three-step flight would end at the stride's own last waypoint and the scene would
     *  pass without the terminal ever being chosen. */
    private static final int STEPS = 5;

    /**
     * The flight stops on the lowest step a body can stand on, and that is the bottom only while the
     * bottom is dry.
     *
     * <p>Staged rather than waited for, because the occasion arrives exactly once per ladder run and
     * costs forty minutes to reach: rung 12's ninth cast, when the mould's own pour has climbed high
     * enough that the runoff reaches the bottom step's HEAD ROOM as well as the step. See
     * {@link JourneyStairs#lowestDryStep} for the nine casts that measured it.
     *
     * <p><b>Two arms, and they must assert different values.</b> {@link JourneyStairs#cells} is static
     * — one flight per process — so a scene that ran both arms against one assertion would pass on a
     * leaked list without ever re-reading the world. Here the dry arm demands the terminal BE the
     * bottom and the wet arm demands it NOT be, so one returned value cannot satisfy both.
     *
     * <p><b>The wet arm asserts the specification, not a named cell.</b> Water placed in the bottom
     * step's head room is free to flow sideways into the step above it, so which step ends up lowest
     * and dry is not fixed — what is fixed is that the terminal is dry in both its cells and that
     * every step below it is not. Pinning a coordinate here would make the scene fail on the fluid
     * tick rather than on the behaviour.
     */
    private static void flightEndsOnADryStep(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // FORGET THE FLIGHT, always. `JourneyStairs.cells` is process-wide, and a ladder run sharing
        // this process would otherwise start rung 12 with this scene's five-step staircase already
        // cut — the class javadoc asks for exactly this.
        ctx.cleanup(() -> { JourneyStairs.forget(); clearBox(ctx); });
        flatGround(ctx);

        // Cut the flight into the stone, east and down, one course a step — the shape
        // `digStairsDown` makes. Each step is its own cell plus its head room; the block under it
        // stays, because that is what holds the step up.
        // THREE CELLS PER STEP, the same three `digStairsDown` cuts — the step, its head room, and
        // the one above that. Cutting two gave these arenas a staircase the rung never digs, and the
        // lip scene paid for it on its first reading: `end=failed:no path (expanded=2)` on BOTH legs,
        // an artifact of this staging rather than anything the ladder does. `StepDown` requires the
        // PASSTHROUGH column's head to be clear (`moves/StepDown.java:23`), and on the way down that
        // passthrough is exactly the cell whose head is the third cut — so omitting it breaks the
        // descent search too, not only the climb back up that `digStairsDown`'s javadoc names.
        List<BlockPos> cut = new java.util.ArrayList<>();
        for (int i = 0; i < STEPS; i++) {
            ctx.setBlock(-4 + i, GROUND - i, 0, Blocks.AIR);
            ctx.setBlock(-4 + i, GROUND - i + 1, 0, Blocks.AIR);
            ctx.setBlock(-4 + i, GROUND - i + 2, 0, Blocks.AIR);
            cut.add(ctx.rel(-4 + i, GROUND - i, 0));
        }
        JourneyStairs.reset(level, cut.get(0));
        for (int i = 1; i < STEPS; i++) JourneyStairs.cut(cut.get(i));
        BlockPos bottom = cut.get(STEPS - 1);
        ctx.record("staged.flight", cut.get(0).toShortString() + " → " + bottom.toShortString()
                + " (" + JourneyStairs.steps() + " steps)");

        // ---- arm A: dry. The terminal must be the bottom, i.e. nothing changed for a healthy run.
        List<BlockPos> dry = JourneyStairwell.stairRoute(level, true);
        ctx.record("dry.route", dry.toString());
        ctx.check(dry.get(dry.size() - 1).equals(bottom))
                .as("A when the bottom of the stairs is dry, the final waypoint is still the bottom step "
                        + bottom.toShortString() + " - actual " + dry.get(dry.size() - 1)
                        + "; this arm is the control, and if it fails the fix has changed the healthy route")
                .isTrue();
        ctx.check(dry.size() >= 2)
                .as("A2 the route must really have intermediate waypoints (otherwise this tests \"there is only"
                        + " one endpoint\" rather than \"the right endpoint was chosen\"): "
                        + dry.size() + " waypoint(s)").isTrue();

        // ---- arm B: the bottom step and its head room under water, as cast8 found them.
        ctx.setBlock(-4 + STEPS - 1, GROUND - STEPS + 1, 0, Blocks.WATER);
        ctx.setBlock(-4 + STEPS - 1, GROUND - STEPS + 2, 0, Blocks.WATER);
        List<BlockPos> wet = JourneyStairwell.stairRoute(level, true);
        BlockPos ends = wet.get(wet.size() - 1);
        ctx.record("wet.route", wet.toString());
        ctx.record("wet.cells", story(level, cut));
        ctx.check(!ends.equals(bottom))
                .as("B when the bottom step is under water, the final waypoint must not still be the bottom step: "
                        + ends.toShortString() + " (bottom step " + bottom.toShortString() + ")").isTrue();
        ctx.check(level.getFluidState(ends).isEmpty() && level.getFluidState(ends.above()).isEmpty())
                .as("C the chosen step's own cell and head cell must both be free of fluid - a landing that"
                        + " cannot be stood on is no better than not changing it: "
                        + story(level, List.of(ends))).isTrue();
        int end = cut.indexOf(ends);
        boolean allBelowWet = true;
        for (int s = end + 1; s < STEPS; s++)
            if (level.getFluidState(cut.get(s)).isEmpty()
                    && level.getFluidState(cut.get(s).above()).isEmpty()) allBelowWet = false;
        ctx.check(allBelowWet)
                .as("D the chosen step is the lowest dry step, not an arbitrary higher one: every step below it"
                        + " must contain fluid - " + story(level, cut)).isTrue();
        for (BlockPos w : wet)
            ctx.check(cut.indexOf(w) <= end)
                    .as("E no waypoint lies below the endpoint (the stride would step past the endpoint and walk"
                            + " back into the water): " + w + " is at step " + cut.indexOf(w)
                            + ", the endpoint is at step " + end).isTrue();
    }

    /**
     * The flooded flight both lip arms are read against, and the raised terminal it produces.
     *
     * <p>THREE CELLS PER STEP, the same three {@code digStairsDown} cuts — the step, its head room,
     * and the one above that. Cutting two gave these arenas a staircase the rung never digs, and the
     * lip scene paid for it on its first reading: {@code end=failed:no path (expanded=2)} on BOTH
     * legs, an artifact of this staging rather than anything the ladder does. {@code StepDown}
     * requires the PASSTHROUGH column's head to be clear ({@code moves/StepDown.java:23}), and on the
     * way down that passthrough is exactly the cell whose head is the third cut — so omitting it
     * breaks the descent search too, not only the climb back up that {@code digStairsDown}'s javadoc
     * names.
     *
     * <p>THE TWO ENDS OF THE FLIGHT ARE STATE TOO, and they are process-wide exactly as the cell list
     * is. {@code landingStory} — which every row these scenes read goes through — formats
     * {@code stairBottom} unconditionally, so a scene that cuts a flight without naming its ends hands
     * the production path a null. Cleared with the cells for the same reason they are.
     *
     * <p>WALKER ROWS, unconditionally. {@code walkerDebug} is off in every gate run, so its step-trace
     * and guard lines can never explain a run that did not set it — and these scenes' whole subject is
     * which tick-phase refuses the last tenth of a block. Five ticks of rows is not a volume worth
     * throttling for, so it is not put behind a second flag of its own.
     */
    private static BlockPos stageLipArena(SceneContext ctx) {
        ServerLevel level = ctx.level();
        boolean debugWas = net.magicterra.worlddriver.bot.BotConfig.walkerDebug;
        net.magicterra.worlddriver.bot.BotConfig.walkerDebug = true;
        ctx.cleanup(() -> {
            net.magicterra.worlddriver.bot.BotConfig.walkerDebug = debugWas;
            JourneyStairs.forget();
            JourneyStairwell.stairTop = null;
            JourneyStairwell.stairBottom = null;
            clearBox(ctx);
        });
        flatGround(ctx);

        List<BlockPos> cut = new java.util.ArrayList<>();
        for (int i = 0; i < STEPS; i++) {
            ctx.setBlock(-4 + i, GROUND - i, 0, Blocks.AIR);
            ctx.setBlock(-4 + i, GROUND - i + 1, 0, Blocks.AIR);
            ctx.setBlock(-4 + i, GROUND - i + 2, 0, Blocks.AIR);
            cut.add(ctx.rel(-4 + i, GROUND - i, 0));
        }
        JourneyStairs.reset(level, cut.get(0));
        for (int i = 1; i < STEPS; i++) JourneyStairs.cut(cut.get(i));
        JourneyStairwell.stairTop = cut.get(0);
        JourneyStairwell.stairBottom = cut.get(STEPS - 1);
        // The bottom step under water, so `lowestDryStep` lifts the terminal one step — the only
        // world in which the lip pose is reachable at all. See finishTheFlight's own note.
        ctx.setBlock(-4 + STEPS - 1, GROUND - STEPS + 1, 0, Blocks.WATER);
        ctx.setBlock(-4 + STEPS - 1, GROUND - STEPS + 2, 0, Blocks.WATER);

        List<BlockPos> route = JourneyStairwell.stairRoute(level, true);
        BlockPos ends = route.get(route.size() - 1);
        BlockPos beyond = JourneyStairs.nextDown(ends);
        ctx.record("staged.terminal", ends.toShortString() + ", next step down=" + String.valueOf(beyond));
        // `check(beyond)`, NOT `check(beyond != null)`: the latter hands the assertion a boolean, and
        // a boolean is never null, so `isNotNull()` on it passes no matter what the staging did. This
        // precondition was written that way and could only ever say PASS.
        ctx.check(beyond).as("precondition: the final waypoint was raised, so there is still a step below it"
                + " to retarget to - no next step means this arm was never set up and the later checks are"
                + " meaningless").isNotNull();
        return ends;
    }

    /**
     * The other half of {@link #walksOffTheLipOntoTheDryStep}: the bot is FALLING into the terminal
     * when the last step is judged.
     *
     * <p>That arm's control B asserts {@code onGround} — "the bot must be **standing**, not falling" —
     * so the whole airborne family is deliberately outside it, and no green run of it can say anything
     * about the branch that family reaches. The rung-12 rehearsal of 2026-08-25 is what that family
     * looks like: the walker's last row reported an exact position of (1.454,58.000,20.500),
     * {@code cur2=0.002} and a solid-footing fraction of 0.0000, horizontally on the terminal's
     * centre with nothing under the feet, and the
     * judgment fired a tick or two before the drop landed. Same shape as the blaze fight's fall guard
     * — a branch a healthy arm never executes has to be staged, or its green is worth nothing.
     *
     * <p>Staged by POSE, not by clock: the body is put in the terminal's own head room with air below
     * it, so it is falling from the first tick, and the one-node movement task is consumed long
     * before the drop lands. Nothing about the healthy arm changes.
     */
    private static void judgesTheLastStepAfterTheDropLands(SceneContext ctx) {
        BlockPos ends = stageLipArena(ctx);

        // IN THE AIR OVER THE TERMINAL, dead centre — not a fifth of a cell into it like the lip arm.
        // The lip pose exists to keep the body supported by the tread above; this one exists to take
        // that support away, so the only thing between the body and the terminal is the fall.
        //
        // SLOW FALLING, AND IT IS A CLOCK CONTROL, NOT THE SUBJECT. `blockPosition()` flips the
        // moment the feet cross the cell boundary, and a free fall crosses a one-block cell in about
        // four ticks — less than the one-node leg takes to be consumed. Both measured, on this arena,
        // before the effect went in:
        //
        //   posed at ends+1.0 → `subject.endedAt … exact …/217.92/…`, settled=null
        //   posed at ends+1.6 → `subject.endedAt … exact …/217.83/…`, settled=null
        //
        // Both times `down()` was already true when the leg's callback ran and the branch under test
        // was never entered — there is no height inside a one-block cell that survives four ticks of
        // gravity. The effect slows the descent by an order of magnitude so the body is STILL in the
        // terminal's head room, still unsupported, and still going to land, when the callback fires.
        // That is the branch's entry condition exactly; what the effect changes is how long it lasts,
        // and the ladder held it open by a different route — a bot at exact position
        // (1.454,58.000,20.500) whose support had just gone, fall distance zero, one tick from the flip.
        ServerWorldDriver driver = SceneBody.managed(ctx, ends.above());
        ServerPlayer fp = driver.fakePlayer();
        ServerPlayerBody av = driver.avatar();
        fp.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.SLOW_FALLING, 400, 0));
        // +1.15, so the head stays under the stairwell's three-high cut (top at +2.95). This was +1.4,
        // which put the head 0.2 into the solid block over the terminal. The client's
        // moveTowardsClosestSpace, which the pumped body runs, answers that with 0.1/tick toward the
        // nearest free column — the tread above — and the body was shoved onto the lip it is meant to
        // be falling past (`staged.pose … 247967.40`, then resting at `.15/218.00`). The hand-
        // integrated body had no push-out and fell straight. The ladder's own pose was +1.0 with the
        // head clear; +1.15 still reads about +0.8 when the leg calls back, well past SETTLED_SLACK.
        fp.moveTo(ends.getX() + 0.5, ends.getY() + 1.15, ends.getZ() + 0.5);
        av.step();
        ctx.check(fp.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING))
                .as("control C: slow falling must really be applied - without it the bot crosses the cell"
                        + " boundary in four ticks, is already in the final waypoint when the check runs, and"
                        + " this arm again measures nothing").isTrue();

        BlockPos posed = fp.blockPosition();
        ctx.record("staged.pose", String.format(java.util.Locale.ROOT,
                "%s exact %.2f/%.2f/%.2f, onGround=%s", posed.toShortString(),
                fp.getX(), fp.getY(), fp.getZ(), fp.onGround()));
        ctx.check(posed.equals(ends.above())).as("control A: the bot must be in the head cell of the final"
                + " waypoint - that is the new branch's only entry condition, and outside this cell it is never"
                + " tested: " + posed + ", head cell of the final waypoint " + ends.above()).isTrue();
        ctx.check(fp.onGround()).as("control B: the bot must be **falling**, not standing - a standing bot"
                + " never drops in however long it waits, which is what the other arm tests: onGround="
                + fp.onGround()).isFalse();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.PORTAL_LIT, driver);
        JourneyStairwell.finishTheFlight(rig, "drop", ends, () -> {
            BlockPos got = fp.blockPosition();
            Object settled = rig.evidenceOf("drop.flightLastStepSettled");
            Object missed = rig.evidenceOf("drop.flightLastStepMissed");
            ctx.record("subject.endedAt", String.format(java.util.Locale.ROOT,
                    "%s exact %.2f/%.2f/%.2f, onGround=%s", got.toShortString(),
                    fp.getX(), fp.getY(), fp.getZ(), fp.onGround()));
            ctx.record("subject.settled", String.valueOf(settled));
            ctx.record("subject.missed", String.valueOf(missed));
            ctx.record("subject.walkerEnd", String.valueOf(rig.evidenceOf("drop.flightLastStepEnd")));

            // A IS THE WHOLE POINT, and it is asked first because the other two are 0==0 without it.
            // No settled row means the callback never saw the bot in the head room — either the drop
            // landed before the movement task ended (then this arm staged nothing) or the branch is
            // not being reached at all. Both want to be told apart from a pass.
            // THE OBJECT, not `settled != null`. The first version asked `ctx.check(settled != null)
            // … isNotNull()`, and a boolean is never null — so criterion A could only ever pass, and
            // it did, on a run whose `subject.settled` was null. A criterion success cannot fail is
            // worth exactly as much as one success cannot satisfy.
            ctx.check(settled).as("A the new branch must really have run and left a result message - no"
                    + " flightLastStepSettled means the bot was not in the head cell of the final waypoint when"
                    + " the check ran, so this arm measured nothing and B/C are 0==0. endedAt=" + got).isNotNull();
            ctx.check(got.getY() <= ends.getY()).as("B after waiting, the bot must really be on the final"
                    + " waypoint's row or lower - this is the quantity walkHome judges: end " + got
                    + ", final waypoint " + ends).isTrue();
            ctx.check(missed).as("C the result message must not still say \"not reached\" - a"
                    + " flightLastStepMissed left behind after the bot has already dropped into the final waypoint"
                    + " is exactly the row this chain uses to trigger returnStuck: " + missed).isNull();
        });
    }

    /**
     * The bot balanced on the lip above the terminal, and whether the last-step movement task gets
     * it off.
     *
     * <p><b>The pose is the whole scene.</b> The ladder of 2026-08-25 died twice in it and both
     * readings agree to the centimetre: {@code cast0.landing} reported the exact position
     * 1.20/58.00/19.49 with {@code onGround=true}, with the terminal at {@code 1,57,19}. A 0.6-wide box centred a fifth of a cell past the
     * boundary overlaps the previous step's tread by a tenth of a block — enough to stand on, three
     * tenths short of falling in. {@code blockPosition()} rounds into the terminal's column, so every
     * cell-granular row in the run says the body is where it needs to be.
     *
     * <p>That pose turns up about one return in three on the ladder and costs forty minutes to reach.
     * Staged here it is deterministic, which is the only reason the second leg can be judged at all —
     * see {@code landOnFloor}, the rehearsal lever written when this coin was first noticed.
     *
     * <p><b>Staged to the losing side, and checked that it IS the losing side before anything else.</b>
     * A body that simply falls into the terminal on its own would satisfy the outcome check while
     * testing nothing, so the control asserts the pose held: above the terminal's row, on the ground,
     * in the terminal's column.
     *
     * <p><b>The outcome is asserted; which leg bought it is recorded.</b> An isolated arena is not the
     * ladder and the walker may well land it in one leg here — demanding two would be a red that says
     * nothing about the production path. What IS asserted is the implication: if the first leg missed,
     * the second must have fired. That is the branch the ladder never had.
     */
    private static void walksOffTheLipOntoTheDryStep(SceneContext ctx) {
        BlockPos ends = stageLipArena(ctx);

        // ON THE LIP: a fifth of a cell into the terminal's column, one row up. The number is the
        // ladder's, not a guess — see the javadoc.
        ServerWorldDriver driver = SceneBody.managed(ctx, ends.above());
        ServerPlayer fp = driver.fakePlayer();
        ServerPlayerBody av = driver.avatar();
        fp.moveTo(ends.getX() + 0.20, ends.getY() + 1, ends.getZ() + 0.5);
        for (int i = 0; i < 3; i++) av.step();

        BlockPos posed = fp.blockPosition();
        ctx.record("staged.pose", String.format(java.util.Locale.ROOT, "%s exact %.2f/%.2f/%.2f, onGround=%s",
                posed.toShortString(), fp.getX(), fp.getY(), fp.getZ(), fp.onGround()));
        ctx.check(posed.getY() > ends.getY()).as("control A: the bot must really still be one row above the"
                + " final waypoint - a bot that drops in on its own turns the checks below into 0==0: bot at "
                + posed + ", final waypoint " + ends).isTrue();
        ctx.check(fp.onGround()).as("control B: the bot must be **standing**, not falling - a falling bot"
                + " drops in on its own after a moment, and that does not test the fix: " + fp.onGround()).isTrue();

        // DID THE GUARD THAT ALREADY EXISTS FIRE? `WalkerTickProgress.unwalkedDescentConsume` was
        // written for this exact shape — journey rung 13, a last node one row DOWN that the walker
        // spends on the tick it adopts it — and it bumps `Walker.descentHolds` every time it holds.
        // A counter is the honest instrument here: `walkerDebug` is behind a flag no gate turns on,
        // so its step-trace rows prove nothing about a run that did not set it, while a counter delta is a
        // state change that happened or did not.
        long holdsBefore = net.magicterra.worlddriver.bot.movement.Walker.descentHolds;
        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.PORTAL_LIT, driver);
        JourneyStairwell.finishTheFlight(rig, "lip", ends, () -> {
            BlockPos got = fp.blockPosition();
            Object missed = rig.evidenceOf("lip.flightLastStepMissed");
            Object end = rig.evidenceOf("lip.flightLastStepEnd");
            ctx.record("subject.descentHolds", (net.magicterra.worlddriver.bot.movement.Walker.descentHolds
                    - holdsBefore) + " (times the last-node hold branch fired; 0 = the guard is inert for this"
                    + " failure family, non-zero = it fired and the bot still did not descend; these are two"
                    + " entirely different paths)");
            ctx.record("subject.endedAt", String.format(java.util.Locale.ROOT,
                    "%s exact %.2f/%.2f/%.2f", got.toShortString(), fp.getX(), fp.getY(), fp.getZ()));
            ctx.record("subject.missed", String.valueOf(missed));
            ctx.record("subject.walkerEnd", String.valueOf(end));
            ctx.record("subject.rowsAbove", String.valueOf(got.getY() - ends.getY()));
            ctx.record("subject.movedBy", String.format(java.util.Locale.ROOT,
                    "%.2f blocks (from %.2f/%.2f to %.2f/%.2f)",
                    Math.hypot(fp.getX() - (ends.getX() + 0.20), fp.getZ() - (ends.getZ() + 0.5)),
                    ends.getX() + 0.20, ends.getZ() + 0.5, fp.getX(), fp.getZ()));

            ctx.check(got.getY() <= ends.getY()).as("A the bot must finally get down to the final waypoint's row"
                    + " or lower - this is the quantity walkHome judges (only `here.getY() > floorY + 1` counts"
                    + " as unable to walk back): end " + got + ", final waypoint " + ends).isTrue();
            // B IS NOT "did the movement task fire" ANY MORE. It fires; what it does not do is arrive.
            // The task must at least have been issued and answered — a run where `flightLastStepEnd`
            // is absent means `finishTheFlight` returned down the "already low enough" branch and
            // criterion A above is 0==0, which is the one way this scene can go green while testing
            // nothing.
            ctx.check(end != null).as("B the last-step movement task must really have run and left a result"
                    + " message - no flightLastStepEnd means finishTheFlight took the \"already low enough\""
                    + " branch, and criterion A becomes 0==0. walkerEnd=" + end).isTrue();
        });
    }

    /** Each step's own cell and head room, fluid named — the reading every check above quotes. */
    private static String story(ServerLevel level, List<BlockPos> steps) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos s : steps) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(s.toShortString()).append("=").append(fluid(level, s))
              .append(", head=").append(fluid(level, s.above()));
        }
        return sb.toString();
    }

    private static String fluid(ServerLevel level, BlockPos c) {
        var fs = level.getFluidState(c);
        return fs.isEmpty() ? "dry" : (fs.isSource() ? "water(source)" : "water(flowing level=" + fs.getAmount() + ")");
    }

    // ------------------------------------------------------------- plumbing ----

    private static int countOf(ServerPlayer fp, net.minecraft.world.item.Item item) {
        return fp.getInventory().countItem(item);
    }

    /** Five rows of one column, bottom-marked, plus what the heightmap says the surface is. */
    private static String column(ServerLevel level, BlockPos at) {
        StringBuilder sb = new StringBuilder(at.getX() + "," + at.getZ() + ":");
        for (int dy = -2; dy <= 2; dy++) {
            BlockPos c = at.above(dy);
            sb.append(' ').append(c.getY()).append('=')
              .append(level.getBlockState(c).getBlock().toString()
                      .replace("Block{minecraft:", "").replace("}", ""));
        }
        return sb + "; daylightAt=" + JourneyTerrain.daylightAt(level, at);
    }

    private static void flatGround(SceneContext ctx) {
        clearBox(ctx);
        for (int dx = -HALF; dx <= HALF; dx++)
            for (int dz = -HALF; dz <= HALF; dz++)
                for (int dy = -PIT - 2; dy <= 0; dy++)
                    ctx.setBlock(dx, GROUND + dy, dz, Blocks.STONE);
    }

    private static void clearBox(SceneContext ctx) {
        for (int dx = -HALF; dx <= HALF; dx++)
            for (int dz = -HALF; dz <= HALF; dz++)
                for (int dy = -PIT - 3; dy <= TOWER + 4; dy++)
                    ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
    }
}

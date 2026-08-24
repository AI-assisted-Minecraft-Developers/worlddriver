package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Where a leg leaves the body, and the two recoveries that put it back on ground it can work from.
 *
 * <p>Both fixes here were landed and then could not be judged. Their occasions are rare — a walk
 * home that ends on top of the tower it built, a climb out of a lava dive that ends still
 * swimming — and five consecutive ladder runs produced neither. Each run costs a quarter of an
 * hour and answers「did it happen this time」; what has to be answered is「when it happens, does the
 * recovery work」. That question needs the occasion staged, which is what these two scenes are.
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
                // 「arrived」mean one cell out in the water, and `afloat` answering「no」for a body
                // treading water with no floor). What it is waiting on is TODO J47 — the pathfinder
                // returns `end=path-consumed` one cell short of a bank that is flush with the water,
                // so no amount of asking moves the body the last step. When J47 lands this flips to
                // PASS by itself, which is the whole reason it stays in the suite red rather than
                // being softened into something a broken walker can satisfy.
                Scene.of("wd.journeyGetsAshoreBeforePouring", 6_000,
                        JourneyLandingScenes::getsAshoreBeforePouring).withRequired(false),
                Scene.of("wd.journeyScoopsPastItsOwnObsidian", 6_000,
                        JourneyLandingScenes::scoopsPastItsOwnObsidian));
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
        ServerPlayerAvatar av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        BlockPos foot = fp.blockPosition();
        int ground = JourneyTerrain.daylightAt(level, home);
        int off = foot.getY() - ground;
        ctx.record("staged.foot", foot.toShortString() + "，脚下="
                + level.getBlockState(foot.below()).getBlock());
        ctx.record("staged.off", "身体 y=" + foot.getY() + "，出生柱地面 y=" + ground + "，差 " + off);
        ctx.check(off >= TOWER - 1).as("控制组：身体必须真的高在塔顶上，且远超守卫的 8 格阈值，"
                + "否则「补救跑了」测的是别的东西：差 " + off + " 格").isTrue();

        int cobbleBefore = countOf(fp, Items.COBBLESTONE);
        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.BED, driver);
        WorldDriverJourneyScenes.settleOntoHomeGround(rig, "bed", home, () -> {
            BlockPos ended = fp.blockPosition();
            Object elev = rig.evidenceOf("bed.homeElevation");
            Object recovered = rig.evidenceOf("bed.towerRecovered");
            ctx.record("subject.homeElevation", String.valueOf(elev));
            ctx.record("subject.towerRecovered", String.valueOf(recovered));
            ctx.record("subject.endedAt", ended.toShortString() + "，脚下="
                    + level.getBlockState(ended.below()).getBlock());
            ctx.record("subject.cobble", cobbleBefore + " → " + countOf(fp, Items.COBBLESTONE));

            ctx.check(elev).as("A 高差这一行**每条路径都要写**，否则「它是平的」和「没人量过」"
                    + "分不开：" + elev).isNotNull();
            ctx.check(recovered).as("B 拆塔这一支**被观察到跑完** —— `bed.towerRecovered` 必须写了。"
                    + "只有 A 没有 B，说明守卫看见了高差却没动：" + recovered).isNotNull();
            ctx.check(ended.getY() - ground <= 2).as("C 身体最后真的落回地面附近（判终点，不是判"
                    + "走了几步）：终点 y=" + ended.getY() + "，地面 y=" + ground).isTrue();
            ctx.check(countOf(fp, Items.COBBLESTONE) > cobbleBefore).as(
                    "D 塔被挖回了包里 —— 每一级都是走行器花掉的一块，这是拆塔的**目的**而不是副作用："
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
        ServerPlayerAvatar av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        BlockPos foot = fp.blockPosition();
        int ground = JourneyTerrain.daylightAt(level, home);
        int off = foot.getY() - ground;
        ctx.record("staged.foot", foot.toShortString());
        ctx.record("staged.off", "身体 y=" + foot.getY() + "，出生柱地面 y=" + ground + "，差 " + off);
        ctx.check(off <= -(PIT - 2)).as("控制组：身体必须真的深在坑底，且远超阈值：差 " + off
                + " 格").isTrue();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.BED, driver);
        WorldDriverJourneyScenes.settleOntoHomeGround(rig, "bed", home, () -> {
            BlockPos ended = fp.blockPosition();
            Object climbed = rig.evidenceOf("bed.climbedBackTo");
            ctx.record("subject.homeElevation", String.valueOf(rig.evidenceOf("bed.homeElevation")));
            ctx.record("subject.climbedBackTo", String.valueOf(climbed));
            ctx.record("subject.endedAt", ended.toShortString() + "，脚下="
                    + level.getBlockState(ended.below()).getBlock());

            ctx.check(rig.evidenceOf("bed.homeElevation")).as("A 高差这一行每条路径都要写").isNotNull();
            ctx.check(climbed).as("B 爬出这一支**被观察到跑完** —— `bed.climbedBackTo` 必须写了："
                    + climbed).isNotNull();
            ctx.check(ended.getY() >= ground - 2).as("C 身体真的回到了地面高度：终点 y="
                    + ended.getY() + "，地面 y=" + ground).isTrue();
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
        ServerPlayerAvatar av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        BlockPos foot = fp.blockPosition();
        ctx.record("staged.foot", foot.toShortString() + "，脚格="
                + level.getBlockState(foot).getBlock() + "，脚下="
                + level.getBlockState(foot.below()).getBlock());
        ctx.check(JourneyShaft.afloat(level, foot)).as(
                "控制组 A 身体必须真的浮着（脚格与脚下都是流体），否则补救不会被触发，"
                + "「上岸了」是 0==0：脚格=" + level.getBlockState(foot).getBlock()
                + "，脚下=" + level.getBlockState(foot.below()).getBlock()).isTrue();
        BlockPos bank = JourneyTerrain.nearestDryColumn(level, foot, 16);
        ctx.check(bank).as("控制组 B 岸必须够得着，否则补救无路可走").isNotNull();
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
            ctx.record("subject.endedAt", ended.toShortString() + "，脚下="
                    + level.getBlockState(ended.below()).getBlock());

            ctx.check(String.valueOf(afloat).startsWith("是")).as(
                    "A 补救**被观察到进入** —— `lava.exit.afloat` 必须读作「是」：" + afloat).isTrue();
            ctx.check(dry).as("B 而且真的挑中了一柱岸：" + dry).isNotNull();
            ctx.check(level.getFluidState(ended.below()).isEmpty()).as(
                    "C 身体最后脚下是固体，不是水（判终点，不是判走了几格）：" + ended.toShortString()
                    + "，脚下=" + level.getBlockState(ended.below()).getBlock()).isTrue();
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
        ServerPlayerAvatar av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.OBSIDIAN, driver);
        BlockPos foot = fp.blockPosition();
        BlockPos nearest = JourneyTerrain.shallowWaterNear(rig, 8);
        BlockPos visible = JourneyFill.visibleSourceNear(rig, false, JourneyFill.FILL_RESEARCH);
        ctx.record("staged.foot", foot.toShortString() + "，眼睛 y=" + fp.getEyePosition().y);
        ctx.record("staged.lidded", lidded.toShortString() + "，其上="
                + level.getBlockState(lidded.above()).getBlock());
        ctx.record("staged.open", open.toShortString() + "，其上="
                + level.getBlockState(open.above()).getBlock());
        ctx.record("subject.nearest", String.valueOf(nearest));
        ctx.record("subject.visible", String.valueOf(visible));

        ctx.check(lidded.equals(nearest)).as("控制组 A 按距离的最近**必须**是被盖住的那一格，"
                + "否则这个布景根本没造出那个陷阱，后面每一条都是 0==0：按距离取到的是 "
                + nearest).isTrue();
        ctx.check(JourneyFill.bucketLineLandsOn(rig, lidded, true)).as(
                "控制组 B 先校准尺子：引擎自己的 clip 必须**同意**盖子挡住了那一格 —— "
                + "这一条要求它返回 false。返回 true 说明盖子没挡住，那么 A 造出的不是陷阱").isFalse();

        ctx.check(visible).as("C 通视 finder 必须给得出一格 —— 给不出就说明它把两格都否了").isNotNull();
        ctx.check(!lidded.equals(visible)).as("D 而且**不是**被盖住的那一格：它选了 " + visible).isTrue();
        ctx.check(open.equals(visible)).as("E 选中的正是那格露天的水源：期望 " + open
                + "，实到 " + visible).isTrue();

        // END TO END. Everything above is about choosing; this is the bucket. `waterFill.result`
        // is the same key the rung writes, on purpose — a reader comparing this scene against a
        // ladder run should be reading the same row name.
        int before = fp.getInventory().countItem(Items.WATER_BUCKET);
        JourneyHands.aimThenAct(rig, visible, () -> {
            JourneyHands.holdForUse(rig, Items.BUCKET, "waterFill");
            JourneyHands.handsAtUse(rig, "waterFill");
            rig.evidence("waterFill.result", String.valueOf(rig.avatar().useItemInHand()));
            rig.settle(new HoldStill(3), 12, () -> {
                int after = fp.getInventory().countItem(Items.WATER_BUCKET);
                ctx.record("subject.waterBucket", before + " → " + after);
                ctx.record("subject.result", String.valueOf(rig.evidenceOf("waterFill.result")));
                ctx.record("subject.atUse", String.valueOf(rig.evidenceOf("waterFill.atUse")));
                ctx.check(after > before).as("F 桶真的装上了水（判存量，不是判 use 的返回值 —— "
                        + "空桶 use 在射线落到非 BucketPickup 方块上时返回 FAIL，落空时返回 PASS，"
                        + "两者都不动存量）：" + before + " → " + after
                        + "；那一刻的手与两条射线：" + rig.evidenceOf("waterFill.atUse")).isTrue();
            });
        });
    }

    // ------------------------------------------------------------- plumbing ----

    private static int countOf(ServerPlayer fp, net.minecraft.world.item.Item item) {
        return fp.getInventory().countItem(item);
    }

    /** Five rows of one column, bottom-marked, plus what the heightmap says the surface is. */
    private static String column(ServerLevel level, BlockPos at) {
        StringBuilder sb = new StringBuilder(at.getX() + "," + at.getZ() + "：");
        for (int dy = -2; dy <= 2; dy++) {
            BlockPos c = at.above(dy);
            sb.append(' ').append(c.getY()).append('=')
              .append(level.getBlockState(c).getBlock().toString()
                      .replace("Block{minecraft:", "").replace("}", ""));
        }
        return sb + "；daylightAt=" + JourneyTerrain.daylightAt(level, at);
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

package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * A support that passes every test the placer has and still cannot be built on.
 *
 * <p><b>Why this is a scene and not another ladder run.</b> Three fixes in a row were recoveries for
 * rare failures, and three consecutive ladder runs — stopping at rungs 11, 10 and 6, each on a
 * different cause — did not exercise a single one. A ladder answers「did it happen this time」; what
 * has to be answered is「when it happens, does the recovery work」.
 *
 * <p><b>What the first version of this scene found, which is why it is now about lily pads.</b> It
 * staged the body over deep water and asserted a recovery that had just been added to the craft.
 * The scene went red on that assertion and green on everything else, and the evidence said why:
 * {@code station.steppingOff.0 = …219 → …216} then {@code furnace.crafted = 1}. The job already
 * belonged to {@code JourneyStation.makeRoomForAStation}, which runs earlier and is strictly more
 * capable, so the new retry never got a turn. The retry was removed; a scene paid for itself inside
 * five minutes by deleting a fix instead of confirming it.
 *
 * <p><b>The occasion that is real.</b> Ladder j47's furnace rung failed in three ticks holding
 * sixteen cobblestone and a table, on {@code 需要工作台（背包里有，但脚边没有可放置的空位）}, and
 * {@code makeRoomForAStation} wrote no row at all — it had answered「there is room」. The one line
 * that says why lives in the game log rather than the results:
 *
 * <pre>
 * [craft] placeNearby: click failed cell=67,64,59 (air) below=67,63,59 (lily_pad)
 * </pre>
 *
 * A lily pad is not air and cannot be replaced, so it passes the support test both the placer and
 * the recovery use — and its top face holds nothing. The rung then reported「no spot」about a body
 * that had a spot it could not use.
 *
 * <p><b>The staging is taken from the body, not predicted.</b> A body dropped into water settles at
 * whatever row the surface puts it in, and the whole point of this arena is that the pad sits in the
 * body's OWN row. So the pool is built first, the body is dropped and stepped, and only then is the
 * pad placed beside where it actually came to rest. Predicting that row is how a scene ends up
 * asserting on geometry it does not have.
 */
public final class JourneyWorkableSpotScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.journeyCraftStepsAsideForRoom", 4_000,
                        JourneyWorkableSpotScenes::craftStepsAsideForRoom));
    }

    /** The water surface, as a dy offset inside the arena box. */
    private static final int SURFACE = 20;

    /** How deep the pool is. Four: the placer probes {@code dy} of 0, −1 and +1, so two would already
     *  leave every support wet — this is double that, so a body that settles a row lower than
     *  expected is still over water on every side. */
    private static final int DEPTH = 4;

    /** Half-width of the pool. Four: the probe only ever looks one cell out. */
    private static final int POOL = 4;

    /** Where the dry platform starts and ends, in +x. */
    private static final int SHORE_NEAR = 6;
    private static final int SHORE_FAR = 10;

    private static void craftStepsAsideForRoom(SceneContext ctx) {
        ServerLevel level = ctx.level();
        ctx.cleanup(() -> clearBox(ctx));
        stage(ctx);

        ServerWorldDriver driver = SceneBody.managed(ctx, ctx.rel(0, SURFACE, 0));
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, 16));
        fp.getInventory().items.set(1, new ItemStack(Items.CRAFTING_TABLE, 1));
        fp.getInventory().selected = 0;
        ServerPlayerAvatar av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        BlockPos foot = fp.blockPosition();
        BlockPos pad = foot.east();
        level.setBlockAndUpdate(pad, Blocks.LILY_PAD.defaultBlockState());
        ctx.record("staged.foot", foot.toShortString() + "，脚格="
                + level.getBlockState(foot).getBlock() + "，脚下="
                + level.getBlockState(foot.below()).getBlock());
        ctx.record("staged.pad", pad.toShortString() + " = " + level.getBlockState(pad).getBlock());

        // THE CONTROL, and it is the whole finding: the staged spot has EXACTLY ONE support the old
        // test accepts, that support is the pad, and NOTHING here is sturdy enough to build on. A
        // green run without these three numbers could not tell「the recovery worked」from「the
        // staging never posed the question」.
        int loose = supports(level, foot, false);
        int sturdy = supports(level, foot, true);
        ctx.record("staged.supports", "旧判据（非空气且不可替换）认可 " + loose
                + " 个，真能承重（isFaceSturdy UP）的有 " + sturdy + " 个");
        ctx.check(loose > 0).as("控制组 A 旧判据必须被骗过去，否则这一格根本不是 j47 那个场合："
                + loose + " 个").isTrue();
        ctx.check(sturdy).as("控制组 B 而真正承得住的必须一个都没有，否则合成本来就会成功，"
                + "下面「补救跑了」是 0==0：" + sturdy + " 个").isEqualTo(0);
        ctx.check(level.getBlockState(pad).is(Blocks.LILY_PAD))
                .as("控制组 C 睡莲要真的还在（水没冲走它）：" + level.getBlockState(pad).getBlock())
                .isTrue();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.FURNACE, driver);
        WorldDriverJourneyScenes.craftKeepingTheTable(rig, "minecraft:furnace", 3_000, () -> {
            BlockPos ended = fp.blockPosition();
            Object stepped = rig.evidenceOf("station.steppingOff.0");
            ctx.record("subject.endedAt", ended.toShortString() + "，脚下="
                    + level.getBlockState(ended.below()).getBlock());
            ctx.record("subject.steppingOff", String.valueOf(stepped));
            ctx.record("subject.noGround", String.valueOf(rig.evidenceOf("station.noGround")));

            ctx.check(stepped).as(
                    "A 补救**被观察到进入** —— `station.steppingOff.0` 必须写了。它没写就说明 "
                    + "placerWouldFindRoom 又被睡莲骗过去了，也就是这条修法没生效："
                    + stepped).isNotNull();
            ctx.check(supports(level, ended, true) > 0).as(
                    "B 身体最后站的那一格真的承得住东西（判的是终点，不是走了几格）：" + ended
                    + " 有 " + supports(level, ended, true) + " 个").isTrue();
            ctx.check(rig.carrying("minecraft:furnace")).as(
                    "C 而且熔炉真的合出来了 —— A/B 全中而这条不中，说明挪窝治的不是这个病")
                    .isEqualTo(1);
        });
    }

    /**
     * How many of the cells {@code PlaceNearby.place} probes have a usable support.
     *
     * <p>{@code sturdy=false} is the test the placer and {@code JourneyStation} both used before
     * 2026-08-24: not air, not replaceable. {@code sturdy=true} adds the question the click will
     * actually ask. Both are spelled out here rather than called because the placer's copy is
     * private to a process and takes an {@code Avatar} — and because the SCENE's job is to state
     * what it staged, in numbers a reader can check, rather than to agree with the code under test
     * by construction.
     */
    private static int supports(ServerLevel level, BlockPos foot, boolean sturdy) {
        int[][] off = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, -1}, {1, 1}, {-1, -1}, {-1, 1}};
        int n = 0;
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] o : off) {
                BlockPos cell = foot.offset(o[0], dy, o[1]);
                BlockPos below = cell.below();
                var cs = level.getBlockState(cell);
                var bs = level.getBlockState(below);
                if (!cs.canBeReplaced()) continue;
                if (bs.isAir() || bs.canBeReplaced()) continue;
                if (sturdy && !bs.isFaceSturdy(level, below, Direction.UP)) continue;
                n++;
            }
        }
        return n;
    }

    private static void stage(SceneContext ctx) {
        clearBox(ctx);
        for (int dx = -POOL - 8; dx <= SHORE_FAR + 4; dx++)
            for (int dz = -POOL - 4; dz <= POOL + 4; dz++)
                ctx.setBlock(dx, SURFACE - DEPTH - 1, dz, Blocks.STONE);
        for (int dx = -POOL; dx <= POOL; dx++)
            for (int dz = -POOL; dz <= POOL; dz++)
                for (int dy = SURFACE - DEPTH; dy <= SURFACE; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.WATER);
        // The shore, and the rim that lets the body wade out rather than climb: this scene is about
        // the craft, and a body that cannot leave the pool would fail it for the wrong reason.
        for (int dx = POOL + 1; dx <= SHORE_FAR; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = SURFACE - DEPTH; dy <= SURFACE; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
    }

    private static void clearBox(SceneContext ctx) {
        for (int dx = -POOL - 8; dx <= SHORE_FAR + 4; dx++)
            for (int dz = -POOL - 4; dz <= POOL + 4; dz++)
                for (int dy = SURFACE - DEPTH - 2; dy <= SURFACE + 6; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }
}

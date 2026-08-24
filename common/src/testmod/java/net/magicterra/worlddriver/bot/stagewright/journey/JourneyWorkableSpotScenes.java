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
 * A craft with nowhere to put its table, and the step aside that gives it one.
 *
 * <p><b>Why this is a scene and not another ladder run.</b> Three fixes in a row were recoveries for
 * rare failures, and three consecutive ladder runs — stopping at rungs 11, 10 and 6, each on a
 * different cause — did not exercise a single one of them. A ladder can only answer「did it happen
 * this time」. What has to be answered is「when it happens, does the recovery work」, and the only
 * way to ask that is to stage the occasion. This is the first of those.
 *
 * <p><b>The occasion, measured.</b> Ladder j47's furnace rung failed in THREE ticks with
 * {@code cobblestone.before = 16}, {@code furnace.topUp = 不需要} and
 * {@code craftingTable.keptInBag = 1} — nothing missing and the table in the bag — on
 * {@code 需要工作台（背包里有，但脚边没有可放置的空位）}. The bed rung's walk home had stopped three
 * blocks short of its goal, inside the arrival tolerance, leaving the body at {@code 67,63,60} on
 * {@code tall_seagrass}.
 *
 * <p><b>The staging matches the real predicate, not the symptom.</b> {@code PlaceNearby.place} walks
 * eight horizontal offsets across three {@code dy} rows and takes the first cell that
 * {@code canBeReplaced()} and whose cell BELOW is neither air nor replaceable. Water passes the
 * first test and fails the second, so what kills the craft is not「the body is wet」— it is that
 * every candidate's SUPPORT is water. That distinction is load-bearing: ladders j34 and j46 both
 * ended the bed rung on {@code 脚下=water} and both crafted the furnace fine, so a scene staged on
 * wetness would assert something the passing runs also satisfy.
 *
 * <p><b>Not staged to the threshold.</b> The pool is four cells of water deep where two would do and
 * reaches nine cells across where three would do; dry land sits six to ten blocks out where the
 * search allows sixteen. A scene that only just meets its own precondition stops testing the fix the
 * first time an unrelated constant moves.
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

    /** How deep the pool is under the body. Four: {@code PlaceNearby} probes {@code dy} of 0, −1 and
     *  +1, so two would already leave every support wet — this is double that, on purpose. */
    private static final int DEPTH = 4;

    /** Half-width of the pool. Four: the probe only ever looks one cell out, so this is four times
     *  what the precondition needs and survives a body that drifts a cell while settling. */
    private static final int POOL = 4;

    /** Where the dry platform starts and ends, in +x. Six out, so the walk is a real leg rather than
     *  a step, and well inside {@code WORKABLE_SEARCH = 16}. */
    private static final int SHORE_NEAR = 6;
    private static final int SHORE_FAR = 10;

    private static void craftStepsAsideForRoom(SceneContext ctx) {
        ServerLevel level = ctx.level();
        ctx.cleanup(() -> clearBox(ctx));
        stage(ctx);

        BlockPos foot = ctx.rel(0, SURFACE, 0);
        ServerWorldDriver driver = SceneBody.managed(ctx, foot);
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, 16));
        fp.getInventory().items.set(1, new ItemStack(Items.CRAFTING_TABLE, 1));
        fp.getInventory().selected = 0;
        ServerPlayerAvatar av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();

        // THE CONTROL, before the subject: prove the staged spot really refuses a table. Without it
        // a green run cannot tell「the recovery worked」from「there was never anything to recover
        // from」, which is the failure mode that made three ladder runs worthless.
        BlockPos stood = fp.blockPosition();
        ctx.record("staged.foot", stood.toShortString() + "，脚格="
                + level.getBlockState(stood).getBlock() + "，脚下="
                + level.getBlockState(stood.below()).getBlock());
        ctx.record("staged.supportsDry", supportsAround(level, stood));
        ctx.check(supportsAround(level, stood))
                .as("控制组：布景必须真的放不下桌子 —— 八邻三层里支撑面不是水的格数必须是 0，"
                    + "否则下面那条「补救生效」是 0==0（身体 " + stood.toShortString() + "）")
                .isEqualTo(0);

        BlockPos dry = JourneyTerrain.nearestDryColumn(level, stood, 16);
        ctx.record("staged.dryLand", dry == null ? "16 格内没有干柱" : dry.toShortString());
        ctx.check(dry).as("控制组：岸必须够得着，否则补救无路可走").isNotNull();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.FURNACE, driver);
        WorldDriverJourneyScenes.craftKeepingTheTable(rig, "minecraft:furnace", 3_000, () -> {
            BlockPos ended = fp.blockPosition();
            String noRoom = String.valueOf(rig.evidenceOf("furnace.noRoomFor"));
            String stepped = String.valueOf(rig.evidenceOf("furnace.steppedAside"));
            ctx.record("subject.endedAt", ended.toShortString() + "，脚下="
                    + level.getBlockState(ended.below()).getBlock());

            ctx.check(rig.evidenceOf("furnace.noRoomFor")).as(
                    "A 补救**被观察到进入** —— `furnace.noRoomFor` 必须写了，否则这一趟根本没发作，"
                    + "断言的是别的东西: " + noRoom).isNotNull();
            ctx.check(rig.evidenceOf("furnace.steppedAside")).as(
                    "B 补救**被观察到跑完** —— 只有 A 没有 B 就是「看见了但没动」: " + stepped).isNotNull();
            ctx.check(supportsAround(level, ended) > 0).as(
                    "C 挪完之后脚边真的有支撑面（判的是身体最后站在哪，不是走了几格）: 身体 "
                    + ended.toShortString() + "，非水支撑面 " + supportsAround(level, ended)
                    + " 个").isTrue();
            ctx.check(rig.carrying("minecraft:furnace")).as(
                    "D 而且熔炉真的合出来了 —— A/B/C 全中而这一条不中，说明挪窝治的不是这个病")
                    .isEqualTo(1);
        });
    }

    /** How many of the cells {@code PlaceNearby.place} would probe have a support that is neither air
     *  nor replaceable — i.e. how many places a table could actually go. Written out here rather
     *  than called because that method is private to the process and takes an {@code Avatar}; the
     *  offsets and the two tests are copied deliberately and are what the control asserts on. */
    private static int supportsAround(ServerLevel level, BlockPos foot) {
        int[][] off = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, -1}, {1, 1}, {-1, -1}, {-1, 1}};
        int n = 0;
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] o : off) {
                BlockPos cell = foot.offset(o[0], dy, o[1]);
                BlockPos below = cell.below();
                if (!level.getBlockState(cell).canBeReplaced()) continue;
                if (level.getBlockState(below).isAir()
                        || level.getBlockState(below).canBeReplaced()) continue;
                n++;
            }
        }
        return n;
    }

    private static void stage(SceneContext ctx) {
        clearBox(ctx);
        // The bed of the world, well under the pool so nothing in the probe's reach touches it.
        for (int dx = -POOL - 8; dx <= SHORE_FAR + 4; dx++)
            for (int dz = -POOL - 4; dz <= POOL + 4; dz++)
                ctx.setBlock(dx, SURFACE - DEPTH - 1, dz, Blocks.STONE);
        // The pool, four deep and nine across.
        for (int dx = -POOL; dx <= POOL; dx++)
            for (int dz = -POOL; dz <= POOL; dz++)
                for (int dy = SURFACE - DEPTH; dy <= SURFACE; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.WATER);
        // The shore: a solid platform whose top is the water's own row, so the walk out is flat and
        // the leg being tested is「go somewhere workable」rather than「climb」.
        for (int dx = SHORE_NEAR; dx <= SHORE_FAR; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = SURFACE - DEPTH; dy <= SURFACE - 1; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        // And the rim between pool and shore, so the body can wade out rather than needing to swim
        // up a wall — the scene is about the craft, not about climbing.
        for (int dx = POOL + 1; dx < SHORE_NEAR; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = SURFACE - DEPTH; dy <= SURFACE - 1; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
    }

    private static void clearBox(SceneContext ctx) {
        for (int dx = -POOL - 8; dx <= SHORE_FAR + 4; dx++)
            for (int dz = -POOL - 4; dz <= POOL + 4; dz++)
                for (int dy = SURFACE - DEPTH - 2; dy <= SURFACE + 6; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }
}

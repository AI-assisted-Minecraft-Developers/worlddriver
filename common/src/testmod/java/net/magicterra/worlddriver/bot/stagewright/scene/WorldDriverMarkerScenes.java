package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.worlddriver.testcontent.FixtureBuilder;
import net.magicterra.worlddriver.testcontent.FixtureIO;
import net.magicterra.worlddriver.testcontent.MarkerBlock;
import net.magicterra.worlddriver.testcontent.MarkerBlockEntity;
import net.magicterra.worlddriver.testcontent.MarkerContent;
import net.magicterra.worlddriver.testcontent.MarkerRole;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/**
 * The marker block's one promise: a tester who puts it in the wrong place has not changed what the
 * body can do. The pathfinder reads {@code blocksMotion} and the collision shape, the body's
 * physics reads the collision shape, and a scene's terrain is written over markers with
 * {@code setBlock} — all three are held here, on the real {@link Walker}. The same promise on
 * water: a marker in a source cell keeps the cell a source and survives the neighbours' flow.
 */
public final class WorldDriverMarkerScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(Scene.of("wd.markerBlockNeverBlocksMotion", 300, WorldDriverMarkerScenes::neverBlocksMotion));
    }

    /**
     * A three-deep wall of markers, every role, two high, right across the body's path on a flat
     * floor; the body walks six cells through it. Then the entity's label survives a save/load, and
     * a marker gives way to a stone written over it.
     */
    private static void neverBlocksMotion(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.originX(), cz = ctx.originZ();
        final int floorY = ctx.originY() + 20, standY = floorY + 1;
        SceneArena.buildFloor(level, cx, cz, floorY);

        MarkerBlock marker = MarkerContent.MARKER.get();
        MarkerRole[] roles = MarkerRole.values();
        int i = 0;
        for (int dx = 1; dx <= 3; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = 0; dy <= 1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, standY + dy, cz + dz),
                            marker.defaultBlockState().setValue(MarkerBlock.ROLE, roles[i++ % roles.length]));

        BlockPos probe = new BlockPos(cx + 2, standY, cz);
        BlockState s = level.getBlockState(probe);
        ctx.check(s.is(marker)).as("A 探针格是标记方块").isTrue();
        ctx.check(s.blocksMotion()).as("B blocksMotion").isFalse();
        ctx.check(s.getCollisionShape(level, probe).isEmpty()).as("C 碰撞形状为空").isTrue();
        ctx.check(s.canBeReplaced()).as("D 可以像草一样被替换").isTrue();

        // The entity round trip: label and args come back from the tag the world would save.
        if (level.getBlockEntity(probe) instanceof MarkerBlockEntity be) {
            be.setLabel("near:2");
            CompoundTag args = new CompoundTag();
            args.putFloat("yaw", 90f);
            be.setArgs(args);
            CompoundTag saved = be.saveWithoutMetadata(level.registryAccess());
            MarkerBlockEntity fresh = new MarkerBlockEntity(probe, s);
            fresh.loadWithComponents(saved, level.registryAccess());
            ctx.check(fresh.label()).as("E 标签经保存再读回").isEqualTo("near:2");
            ctx.check(fresh.args().getFloat("yaw")).as("F 参数经保存再读回").isCloseTo(90.0, 1e-6);
        } else {
            ctx.fail("marker: the block has no MarkerBlockEntity at " + probe.toShortString());
        }

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;

        BlockPos goal = new BlockPos(cx + 5, standY, cz);
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx - 1 + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step step = Walker.Step.WALKING;
        int ticks = 0;
        for (; ticks < 200 && step == Walker.Step.WALKING; ticks++) { step = walker.tick(av, w); av.step(); }
        double dx = Math.abs(fp.getX() - (cx + 5.5)), dz = Math.abs(fp.getZ() - (cz + 0.5));
        ctx.record("行走", "step=" + step + " ticks=" + ticks + " pos=" + String.format("%.2f %.2f %.2f", fp.getX(), fp.getY(), fp.getZ()));
        ctx.check(dx < 1.5 && dz < 1.5).as("G 真 Walker 穿过标记墙到达目标").isTrue();
        ctx.check(level.getBlockState(probe).is(marker)).as("H 走过之后标记还在，没有被破坏").isTrue();

        level.setBlockAndUpdate(probe, Blocks.STONE.defaultBlockState());
        ctx.check(level.getBlockState(probe).is(Blocks.STONE)).as("I setBlock 直接盖掉标记").isTrue();
        ctx.check(level.getBlockEntity(probe) == null).as("J 盖掉之后方块实体一起没了").isTrue();

        // A marker put into a water source keeps the water: the cell still reads as a source, a
        // neighbouring source's flow tick does not wash the marker away (a bare no-collision block
        // would be gone in one tick), and taking the marker out gives the water back.
        BlockPos pool = new BlockPos(cx + 8, standY, cz);
        for (int px = -1; px <= 1; px++)
            for (int pz = -1; pz <= 1; pz++)
                level.setBlockAndUpdate(pool.offset(px, 0, pz), Blocks.WATER.defaultBlockState());
        FixtureIO.put(level, pool, MarkerRole.FORBID, "", null);
        ctx.check(level.getBlockState(pool).is(marker)).as("K 标记放进了水源格").isTrue();
        ctx.check(level.getFluidState(pool).is(Fluids.WATER) && level.getFluidState(pool).isSource())
                .as("L 那一格仍读作水源").isTrue();
        BlockPos east = pool.east();
        Fluids.WATER.tick(level, east, level.getFluidState(east));
        ctx.check(level.getBlockState(pool).is(marker)).as("M 邻格的水流一次 tick 之后标记还在").isTrue();
        FixtureIO.remove(level, List.of(new FixtureBuilder.Placed(pool, MarkerRole.FORBID, "", java.util.Map.of())));
        ctx.check(level.getBlockState(pool).is(Blocks.WATER)).as("N 拿走标记后那一格又是水").isTrue();
    }
}

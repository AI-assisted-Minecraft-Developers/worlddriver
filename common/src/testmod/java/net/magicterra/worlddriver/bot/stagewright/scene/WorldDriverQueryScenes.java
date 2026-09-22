package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.UnloadedAreaException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code mc.query q=blocks} against a real level: the documented maximum radius answers, one past
 * it is refused, and a cube reaching into an unloaded chunk is refused by name and leaves that
 * chunk unloaded. The last is the half a unit test cannot hold — only a live chunk source can say
 * whether the read loaded anything.
 */
public final class WorldDriverQueryScenes implements SceneProvider {

    private static final int MAX_RADIUS = 15;

    @Override
    public List<Scene> scenes() {
        return List.of(Scene.of("wd.queryBlocksStaysInLoadedChunks", 100,
                WorldDriverQueryScenes::blocksStayInLoadedChunks));
    }

    private static void blocksStayInLoadedChunks(SceneContext ctx) {
        DriverApi api = WorldDriverCommon.api();
        if (api == null) ctx.fail("DriverApi not initialized — was the mod loaded?");
        for (int d : new int[] {-MAX_RADIUS, MAX_RADIUS})
            if (ctx.outsideForcedChunks(d, d) || ctx.outsideForcedChunks(d, -d))
                ctx.skip("the arena's forced window is narrower than a radius-" + MAX_RADIUS + " cube");
        ServerLevel level = ctx.level();

        BlockPos center = ctx.rel(0, 10, 0);
        BlockPos probe = ctx.rel(3, 12, -2);
        level.setBlockAndUpdate(probe, Blocks.GOLD_BLOCK.defaultBlockState());
        ctx.cleanup(() -> level.setBlockAndUpdate(probe, Blocks.AIR.defaultBlockState()));

        Object rows = api.route("mc.query", Map.of("q", "blocks", "center", pos(center),
                "filter", Map.of("in_radius", MAX_RADIUS, "type", "minecraft:gold_block")));
        ctx.check(rows instanceof List<?> l && l.stream().anyMatch(r -> r instanceof Map<?, ?> m
                        && probe.equals(m.get("pos"))))
                .as("A the documented maximum radius answers and finds the probe block").isTrue();

        String refused = errorOf(() -> api.route("mc.query", Map.of("q", "blocks", "center", pos(center),
                "filter", Map.of("in_radius", MAX_RADIUS + 1))));
        ctx.check(refused != null && refused.contains("max " + MAX_RADIUS))
                .as("B one past the maximum is refused and names the limit (" + refused + ")").isTrue();

        // Far enough that nothing else in the suite has a reason to have it loaded.
        BlockPos far = new BlockPos(ctx.originX() + 1_000_000, 80, ctx.originZ() + 1_000_000);
        int fcx = far.getX() >> 4, fcz = far.getZ() >> 4;
        if (level.getChunkSource().getChunkNow(fcx, fcz) != null)
            ctx.skip("the far probe chunk [" + fcx + ", " + fcz + "] is already loaded");
        RuntimeException unloaded = null;
        try {
            api.route("mc.query", Map.of("q", "blocks", "center", pos(far), "filter", Map.of("in_radius", 2)));
        } catch (RuntimeException e) {
            unloaded = e;
        }
        ctx.record("unloadedError", String.valueOf(unloaded));
        ctx.check(unloaded instanceof UnloadedAreaException)
                .as("C a cube in an unloaded chunk is refused with UnloadedAreaException").isTrue();
        ctx.check(unloaded != null && String.valueOf(unloaded.getMessage()).contains("[" + fcx + ", " + fcz + "]"))
                .as("D the refusal names the unloaded chunk").isTrue();
        ctx.check(level.getChunkSource().getChunkNow(fcx, fcz) == null)
                .as("E the query left the chunk unloaded").isTrue();
    }

    private static Map<String, Object> pos(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    private static String errorOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }
}

package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.api.game.WorldDriver;
import static net.magicterra.worlddriver.api.game.Positions.position;

import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.game.UnloadedAreaException;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.methods.client.ClientApi;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.BlocksParams;
import net.magicterra.worlddriver.api.methods.query.QueryApi;
import net.magicterra.worlddriver.api.methods.query.QueryContracts;
import net.magicterra.worlddriver.api.methods.query.QueryContracts.BlockRows;
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

    /** A query on the server thread hops to itself and is done when the call returns. */
    private static final long QUERY_MS = 5_000;

    @Override
    public List<Scene> scenes() {
        return List.of(Scene.of("wd.queryBlocksStaysInLoadedChunks", 100,
                        WorldDriverQueryScenes::blocksStayInLoadedChunks),
                Scene.of("wd.clientBlocksNamesUnloadedChunks", 100,
                        WorldDriverQueryScenes::clientBlocksNameUnloadedChunks));
    }

    /**
     * The client half of the refusal: ClientLevel reads a chunk it never received as air, so
     * {@code mc.client.blocks} must name it in {@code unloaded} for the {@code mc.query} client
     * fallback to refuse the scan rather than answer "nothing here".
     */
    private static void clientBlocksNameUnloadedChunks(SceneContext ctx) {
        if (ctx.server().isDedicatedServer())
            ctx.skip("mc.client.blocks is client-only — only an integrated server has its handler here");
        if (WorldDriver.registry() == null) ctx.fail("the API registry is not initialized — was the mod loaded?");
        var client = ClientApi.of(SceneCalls.api());

        // Asked, not awaited: the scan runs on the client thread, which this server thread may not wait for.
        SceneCalls.ask(ctx, "nearScan", client.blocks(BlocksParams.aroundPlayer(1)), near ->
                ctx.check(near.unloaded().isEmpty())
                        .as("A a scan around the player reports no unloaded chunk (" + near + ")").isTrue());

        BlockPos far = new BlockPos(ctx.originX() + 1_000_000, 80, ctx.originZ() + 1_000_000);
        int fcx = far.getX() >> 4, fcz = far.getZ() >> 4;
        SceneCalls.ask(ctx, "farScan", client.blocks(BlocksParams.around(position(far),1)), distant ->
                ctx.check(distant.unloaded().orElse(List.of()).stream().anyMatch(c -> c.x() == fcx && c.z() == fcz))
                        .as("B a scan far from the player names chunk [" + fcx + ", " + fcz + "] as unloaded").isTrue());
    }

    private static void blocksStayInLoadedChunks(SceneContext ctx) {
        ApiRegistry api = WorldDriver.registry();
        if (api == null) ctx.fail("the API registry is not initialized — was the mod loaded?");
        var query = QueryApi.of(api);
        for (int d : new int[] {-MAX_RADIUS, MAX_RADIUS})
            if (ctx.outsideForcedChunks(d, d) || ctx.outsideForcedChunks(d, -d))
                ctx.skip("the arena's forced window is narrower than a radius-" + MAX_RADIUS + " cube");
        ServerLevel level = ctx.level();

        BlockPos center = ctx.rel(0, 10, 0);
        BlockPos probe = ctx.rel(3, 12, -2);
        level.setBlockAndUpdate(probe, Blocks.GOLD_BLOCK.defaultBlockState());
        ctx.cleanup(() -> level.setBlockAndUpdate(probe, Blocks.AIR.defaultBlockState()));

        QueryContracts.Rows rows = ApiRegistry.await(query.query(QueryContracts.Blocks.around(position(center),MAX_RADIUS)
                .ofType("minecraft:gold_block")), QUERY_MS);
        ctx.check(rows instanceof BlockRows b && b.blocks().stream().anyMatch(r -> r.pos().equals(java.util.Optional.of(position(probe)))))
                .as("A the documented maximum radius answers and finds the probe block").isTrue();

        String refused = errorOf(() -> ApiRegistry.await(query.query(QueryContracts.Blocks.around(position(center),MAX_RADIUS + 1)), QUERY_MS));
        ctx.check(refused != null && refused.contains("0–" + MAX_RADIUS))
                .as("B one past the maximum is refused and names the limit (" + refused + ")").isTrue();

        // Far enough that nothing else in the suite has a reason to have it loaded.
        BlockPos far = new BlockPos(ctx.originX() + 1_000_000, 80, ctx.originZ() + 1_000_000);
        int fcx = far.getX() >> 4, fcz = far.getZ() >> 4;
        if (level.getChunkSource().getChunkNow(fcx, fcz) != null)
            ctx.skip("the far probe chunk [" + fcx + ", " + fcz + "] is already loaded");
        RuntimeException unloaded = null;
        try {
            ApiRegistry.await(query.query(QueryContracts.Blocks.around(position(far),2)), QUERY_MS);
        } catch (RuntimeException e) {
            unloaded = e;
        }
        ctx.record("unloadedError", String.valueOf(unloaded));
        ctx.check(unloaded instanceof ApiException a && a.getCause() instanceof UnloadedAreaException)
                .as("C a cube in an unloaded chunk is refused with UnloadedAreaException").isTrue();
        ctx.check(unloaded != null && String.valueOf(unloaded.getMessage()).contains("[" + fcx + ", " + fcz + "]"))
                .as("D the refusal names the unloaded chunk").isTrue();
        ctx.check(level.getChunkSource().getChunkNow(fcx, fcz) == null)
                .as("E the query left the chunk unloaded").isTrue();
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

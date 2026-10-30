package net.magicterra.worlddriver.bot.stagewright.scenes;

import static net.magicterra.worlddriver.api.game.Positions.position;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Optional;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.game.UnloadedAreaException;
import net.magicterra.worlddriver.api.methods.client.ClientBlockingApi;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.BlocksParams;
import net.magicterra.worlddriver.api.methods.query.QueryBlockingApi;
import net.magicterra.worlddriver.api.methods.query.QueryContracts;
import net.magicterra.worlddriver.api.methods.query.QueryContracts.BlockRows;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/**
 * {@code mc.query q=blocks} against a real level: the documented maximum radius answers, one past it is refused, and a
 * cube reaching into an unloaded chunk is refused by name and leaves that chunk unloaded. The last is the half a unit
 * test cannot hold — only a live chunk source can say whether the read loaded anything. The client half checks that the
 * client's own level names what it never received.
 *
 * <p>Both scenes: the server half needs no client and runs in each, the client half only where a client joined.
 */
@Scene({"wd.scenes", "wd.clientScenes"})
class QueryTest {

    private static final int MAX_RADIUS = 15;

    /** Far enough that nothing else in the suite has a reason to have it loaded. */
    private static final int FAR = 1_000_000;

    @Test
    void queryBlocksStaysInLoadedChunks(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        QueryBlockingApi query = game.api(QueryBlockingApi::of);
        BlockPos center = plot.at(0, 0, 0);
        BlockPos probe = plot.at(3, 2, -2);
        plot.place(3, 2, -2, Blocks.GOLD_BLOCK);

        QueryContracts.Rows rows = query.query(
                QueryContracts.Blocks.around(position(center), MAX_RADIUS).ofType("minecraft:gold_block"));
        game.check(
                rows instanceof BlockRows b
                        && b.blocks().stream().anyMatch(r -> r.pos().equals(Optional.of(position(probe)))),
                "A the documented maximum radius answers and finds the probe block: " + rows);

        String refused = message(() -> query.query(QueryContracts.Blocks.around(position(center), MAX_RADIUS + 1)));
        game.check(
                refused != null && refused.contains("0–" + MAX_RADIUS),
                "B one past the maximum is refused and names the limit (" + refused + ")");

        BlockPos far = new BlockPos(center.getX() + FAR, 80, center.getZ() + FAR);
        int fcx = far.getX() >> 4, fcz = far.getZ() >> 4;
        boolean loaded = game.onServer(s -> s.overworld().getChunkSource().getChunkNow(fcx, fcz) != null);
        assumeTrue(!loaded, "the far probe chunk [" + fcx + ", " + fcz + "] is already loaded");
        RuntimeException unloaded = null;
        try {
            query.query(QueryContracts.Blocks.around(position(far), 2));
        } catch (RuntimeException e) {
            unloaded = e;
        }
        RuntimeException error = unloaded;
        game.check(
                error instanceof ApiException a && a.getCause() instanceof UnloadedAreaException,
                "C a cube in an unloaded chunk is refused with UnloadedAreaException: " + error);
        game.check(
                error != null && String.valueOf(error.getMessage()).contains("[" + fcx + ", " + fcz + "]"),
                "D the refusal names the unloaded chunk: " + error);
        game.check(
                game.<Boolean>onServer(s -> s.overworld().getChunkSource().getChunkNow(fcx, fcz) == null),
                "E the query left the chunk unloaded");
    }

    /**
     * The client half of the refusal: ClientLevel reads a chunk it never received as air, so {@code mc.client.blocks}
     * must name it in {@code unloaded} for the {@code mc.query} client fallback to refuse the scan rather than answer
     * "nothing here".
     */
    @Test
    @Requires(Requires.Capability.CLIENT)
    void clientBlocksNamesUnloadedChunks(Game game, Client client, Player player) {
        ClientBlockingApi blocks = client.api(ClientBlockingApi::of);
        ClientContracts.BlockScan near = blocks.blocks(BlocksParams.aroundPlayer(1));
        game.check(near.unloaded().isEmpty(), "A a scan around the player reports no unloaded chunk (" + near + ")");

        BlockPos at = player.blockPos();
        BlockPos far = new BlockPos(at.getX() + FAR, 80, at.getZ() + FAR);
        int fcx = far.getX() >> 4, fcz = far.getZ() >> 4;
        ClientContracts.BlockScan distant = blocks.blocks(BlocksParams.around(position(far), 1));
        game.check(
                distant.unloaded().orElse(List.of()).stream().anyMatch(c -> c.x() == fcx && c.z() == fcz),
                "B a scan far from the player names chunk [" + fcx + ", " + fcz + "] as unloaded: " + distant);
    }

    private static String message(Runnable call) {
        try {
            call.run();
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }
}

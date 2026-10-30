package net.magicterra.worlddriver.testcontent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Step;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.CancelParams;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.methods.system.SystemApi;
import net.magicterra.worlddriver.api.registry.ApiErrors;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * The Java half of the validation scripts' kit ({@code stagewright/tests/validation/kit.js}): the arena they are
 * written against, the topology their check counts depend on, and the same call made over the WebSocket RPC and MCP
 * servers ({@link TransportCalls}), for the checks that compare the three transports. Listed in
 * {@code META-INF/stagewright/steps}.
 */
public final class ValidationSteps {
    private ValidationSteps() {}

    private static final Logger LOG = TestMod.LOG;

    /**
     * {@code wd.validation.arena}: seeds {@link TestArena} and, where the game has a player, stands them on its pad.
     *
     * <p>The scripts' client-side checks ({@code mc.bot.craft} and friends) act at the player, so on a topology with
     * one they ran wherever the client had wandered: one run placed its crafting table, the next failed with
     * {@code placeNearby: click failed ... below=minecraft:lily_pad} because the player stood on a pond. One block
     * diagonal from the origin, since the seed puts an oak log at origin+(0,1,0) for the checks that mine one, and a
     * player standing in it has no free cell at their feet. The move is checked here and not at the end: later checks
     * move the player on purpose.
     */
    @Step("wd.validation.arena")
    public static void arena(Game game) {
        ApiRegistry api = game.registry();
        TestArena.seed(api, game.onServer(s -> s));
        BlockPos pad = Positions.blockPos(ApiRegistry.await(SystemApi.of(api).testOrigin(new NoParams()), 5_000));
        double tx = pad.getX() + 1.5, ty = pad.getY() + 1, tz = pad.getZ() + 1.5;
        String drift = game.onServer(s -> {
            List<ServerPlayer> players = s.getPlayerList().getPlayers();
            if (players.isEmpty()) return null;
            ServerPlayer player = players.get(0);
            player.teleportTo(s.overworld(), tx, ty, tz, Set.of(), 0f, 0f);
            double d = player.position().distanceTo(new Vec3(tx, ty, tz));
            return d > 1.0
                    ? "asked for (" + tx + ", " + ty + ", " + tz + "), ended at " + player.position() + " ("
                            + String.format(Locale.ROOT, "%.2f", d) + " away)"
                    : null;
        });
        if (drift != null) {
            throw new AssertionError("could not stand the player on the seeded test pad: " + drift + "; the scripts'"
                    + " client-side checks would run on whatever is under that spot instead");
        }
    }

    /**
     * {@code wd.validation.dedicated}: whether this game is a dedicated server, which decides a script's check count.
     */
    @Step("wd.validation.dedicated")
    public static boolean dedicated(Game game) {
        return game.onServer(MinecraftServer::isDedicatedServer);
    }

    /**
     * {@code wd.validation.rpc}: {@code {method, params}} over a fresh WebSocket connection, {@code params} the JSON
     * text a script wrote (so a whole number stays one) or null. Answers the envelope {@code Driver.invoke} reads.
     */
    @Step("wd.validation.rpc")
    public static String rpc(Map<String, Object> args) {
        int port = WorldDriver.rpcPort();
        if (port <= 0) return notListening("RPC");
        return TransportCalls.rpc(port, (String) args.get("method"), (String) args.get("params"));
    }

    /** {@code wd.validation.mcp}: as {@link #rpc}, as an MCP {@code tools/call} over HTTP. */
    @Step("wd.validation.mcp")
    public static String mcp(Map<String, Object> args) {
        int port = WorldDriver.mcpPort();
        if (port <= 0) return notListening("MCP");
        return TransportCalls.mcp(port, (String) args.get("method"), (String) args.get("params"));
    }

    private static String notListening(String server) {
        JsonObject envelope = new JsonObject();
        envelope.add(
                "error",
                ApiErrors.toTree(
                        ApiError.of(ApiError.Code.INTERNAL_ERROR, "the " + server + " server is not listening")));
        return envelope.toString();
    }

    /**
     * {@code wd.validation.end}: logs a script's checks as a table, and stands down whatever it started. The scripts
     * start real processes on the client and most never cancel them, which handed the next script a busy scheduler. A
     * dedicated server has no bot to stand down.
     */
    @Step("wd.validation.end")
    public static void end(Game game, Map<String, Object> args) {
        LOG.info("==================== {} ====================", args.get("script"));
        int failed = 0;
        for (Object o : (List<?>) args.get("checks")) {
            Map<?, ?> check = (Map<?, ?>) o;
            boolean passed = Boolean.TRUE.equals(check.get("passed"));
            if (!passed) failed++;
            LOG.info(String.format(
                    Locale.ROOT,
                    "  [%s] %-60s %5.0f ms",
                    passed ? "PASS" : "FAIL",
                    check.get("name"),
                    ((Number) check.get("ms")).doubleValue()));
            if (!passed) LOG.error("        {}", check.get("error"));
        }
        LOG.info("TOTAL: {}   FAIL: {}", ((List<?>) args.get("checks")).size(), failed);
        if (!dedicated(game)) ApiRegistry.await(ControlApi.of(game.registry()).cancel(CancelParams.all()), 5_000);
    }
}

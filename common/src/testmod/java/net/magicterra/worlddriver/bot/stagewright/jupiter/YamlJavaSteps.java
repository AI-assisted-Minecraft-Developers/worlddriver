package net.magicterra.worlddriver.bot.stagewright.jupiter;

import java.util.Map;

import net.magicterra.stagewright.test.Game;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** Methods jupiter-smoke.yaml calls with {@code java:} steps. */
public final class YamlJavaSteps {

    private YamlJavaSteps() {}

    /** What a {@code java:} step answers when its method returns an object rather than plain values. */
    public record Found(String type, int y) {}

    /** The block at {@code params.pos}, read on the server thread. */
    public static Found blockAt(Game game, Map<String, Object> params) {
        Map<?, ?> pos = (Map<?, ?>) params.get("pos");
        BlockPos at = new BlockPos(((Number) pos.get("x")).intValue(), ((Number) pos.get("y")).intValue(),
                ((Number) pos.get("z")).intValue());
        return game.onServer(server -> new Found(
                BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(at).getBlock()).toString(), at.getY()));
    }
}

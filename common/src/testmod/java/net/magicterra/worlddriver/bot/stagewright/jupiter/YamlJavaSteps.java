package net.magicterra.worlddriver.bot.stagewright.jupiter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** Methods jupiter-smoke.yaml calls with {@code java:} steps, and named steps listed in META-INF/stagewright/steps. */
public final class YamlJavaSteps {

    private YamlJavaSteps() {}

    /** What a {@code java:} step answers when its method returns an object rather than plain values. */
    public record Found(String type, int y) {}

    private static final List<String> MARKS = new CopyOnWriteArrayList<>();

    /** The step {@code wd.mark}: remembers {@code name}, for a Java test to see that a script test before it ran. */
    @Step("wd.mark")
    public static String mark(Object name) {
        MARKS.add(String.valueOf(name));
        return String.valueOf(name);
    }

    /** The step {@code wd.marks}: every mark taken so far, in the order they were taken. */
    @Step("wd.marks")
    public static List<String> marks(Object ignored) {
        return List.copyOf(MARKS);
    }

    /** The step {@code wd.serverTick}: the server's tick count, for a script to tell which tick its calls ran in. */
    @Step("wd.serverTick")
    public static int serverTick(Game game, Object ignored) {
        return game.onServer(server -> server.getTickCount());
    }

    /** The step {@code wd.gameTime}: the overworld's game time, which a frozen world does not advance. */
    @Step("wd.gameTime")
    public static long gameTime(Game game, Object ignored) {
        return game.onServer(server -> server.overworld().getGameTime());
    }

    /** Whether a script test took {@code wd.mark} with {@code name}. */
    static boolean marked(String name) {
        return MARKS.contains(name);
    }

    /** The block at {@code params.pos}, read on the server thread; also the named step {@code wd.blockAt}. */
    @Step("wd.blockAt")
    public static Found blockAt(Game game, Map<String, Object> params) {
        Map<?, ?> pos = (Map<?, ?>) params.get("pos");
        BlockPos at = new BlockPos(((Number) pos.get("x")).intValue(), ((Number) pos.get("y")).intValue(),
                ((Number) pos.get("z")).intValue());
        return game.onServer(server -> new Found(
                BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(at).getBlock()).toString(), at.getY()));
    }
}

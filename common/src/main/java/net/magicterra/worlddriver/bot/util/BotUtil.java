package net.magicterra.worlddriver.bot.util;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;

/**
 * Stateless conversion / parsing / threading helpers shared across the bot
 * package. Extracted from the former {@code BotApiImpl} god-class; callers
 * pull these in via {@code import static …BotUtil.*} so call sites stay
 * unqualified.
 */
public final class BotUtil {

    private BotUtil() {}

    public static BlockPos blockPosOf(Entity e) {
        return new BlockPos((int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ()));
    }

    public static Map<String, Object> posMap(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    public static BlockPos readPos(Object o) { return Params.toPos(o); }

    public static int intOr(Object o, int dflt) { return Params.toInt(o, dflt); }
    public static double doubleOr(Object o, double dflt) { return Params.toDouble(o, dflt); }
    public static int clamp(int v, int lo, int hi) { return Params.clamp(v, lo, hi); }

    public static List<String> parseStringList(Object o) { return Params.toStringList(o); }

    public static Map<String, Object> unimplemented(String msg) {
        return Map.of("ok", false, "error", "unimplemented: " + msg);
    }

    // === Threading bridge ====================================================

    /** Default budget for waiting on a client-tick hop. Mirrors
     *  {@code AgentApi.SERVER_THREAD_TIMEOUT_MS} — the server-side twin of this
     *  bridge — so a stalled client surfaces as a clear error instead of parking
     *  the calling RPC/MCP thread forever. Override with
     *  {@code -Dworlddriver.clientThreadTimeoutMs=N}. */
    private static final long CLIENT_THREAD_TIMEOUT_MS =
            Long.getLong("worlddriver.clientThreadTimeoutMs", 8_000L);

    /**
     * Run {@code body} on the client thread and return its value.
     *
     * <p>Modelled on {@code AgentApi.onServerThread}, and deliberately identical to it
     * in the two respects that are observable to a caller:
     * <ul>
     *   <li><b>Bounded.</b> {@code mc.execute} only runs when the client drains its task
     *       queue; during shutdown, a hung level load, or a blocking modal it may never
     *       do so. The old unbounded {@code fut.get()} then parked the calling transport
     *       thread permanently — the request never returned and never errored.</li>
     *   <li><b>Transparent to exceptions.</b> The old code wrapped everything in
     *       {@code RuntimeException(e)}, so the same failure read as
     *       {@code IllegalArgumentException: bad param} when invoked from the client
     *       thread (the {@code isSameThread} fast path, which rethrows raw) but as
     *       {@code RuntimeException: ExecutionException: IllegalArgumentException: bad
     *       param} from any transport thread. Unwrapping the {@code ExecutionException}
     *       makes both paths report the same text — which is what the three-transport
     *       byte-identical parity assertion actually compares.</li>
     * </ul>
     */
    public static <T> T onClient(Supplier<T> body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) return body.get();
        CompletableFuture<T> fut = new CompletableFuture<>();
        mc.execute(() -> {
            try { fut.complete(body.get()); }
            catch (Throwable t) { fut.completeExceptionally(t); }
        });
        try {
            return fut.get(CLIENT_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("client thread did not run task within "
                    + CLIENT_THREAD_TIMEOUT_MS + "ms (client busy, loading or paused)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting on client thread");
        }
    }

    // === Camera smoothing (mc.bot.setting{smoothLook}) =======================

    /**
     * Step {@code cur} toward {@code target} by at most
     * {@link BotConfig#smoothLookDegPerTick} when {@link BotConfig#smoothLook}
     * is on; otherwise snap straight to the target (current behavior). Valid for
     * both yaw (wraps mod 360) and pitch (no wrap within ±90). Used by the
     * pathfinding Walker and the LookProcess; functional aiming snaps by calling
     * the rotation setters directly instead of going through here.
     */
    public static float smoothAngle(float cur, float target) {
        if (!BotConfig.smoothLook) return target;
        float max = Math.max(0.1f, BotConfig.smoothLookDegPerTick);
        float d = ((target - cur) % 360f + 540f) % 360f - 180f; // shortest signed delta
        if (Math.abs(d) <= max) return target;
        return cur + Math.copySign(max, d);
    }

    /**
     * Damage-on-contact blocks the pathfinder must route around. Lava is checked
     * by fluid tag; fire variants by {@link net.minecraft.tags.BlockTags#FIRE} so
     * datapack-added fire blocks are covered automatically. Remaining hazards are
     * listed by {@link net.minecraft.world.level.block.Block} reference for O(1)
     * lookup and so a modpack-added hazard can be added in a single line.
     */
    public static final Set<Block> HAZARD_BLOCKS = Set.of(
            Blocks.MAGMA_BLOCK,
            Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.POWDER_SNOW,
            Blocks.WITHER_ROSE,
            // §88: "impaled on a stalagmite" (C102-J2 death). Fall damage onto an
            // upward spike is DOUBLED and the executor drifts ±1 block during a
            // committed fall, so the contact ring must tax the spike's neighbourhood
            // — the block was simply missing from this list (same class as the
            // FLOWING_LAVA FluidTags blind spot).
            Blocks.POINTED_DRIPSTONE
    );

    /** Cheap stand finder: 4 cardinals at same Y, then Y-1, then Y+1, then on
     *  top of the block. Water counts as passable. Used by the goto block
     *  selector — the more thorough variants in MineProcess / BboxFillProcess
     *  do additional reach checks tailored to mining/clearing. */
    public static BlockPos findStandAdjacent(Level lvl, BlockPos block) {
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : dxz) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (canStandHereStatic(lvl, cand)) return cand;
            }
        }
        BlockPos above = block.offset(0, 1, 0);
        if (canStandHereStatic(lvl, above)) return above;
        return null;
    }

    public static boolean canStandHereStatic(Level lvl, BlockPos foot) {
        BlockState below = lvl.getBlockState(foot.offset(0, -1, 0));
        BlockState here = lvl.getBlockState(foot);
        BlockState head = lvl.getBlockState(foot.offset(0, 1, 0));
        if (!below.blocksMotion()) return false;
        if (here.blocksMotion() && !here.getFluidState().is(Fluids.WATER)) return false;
        if (head.blocksMotion() && !head.getFluidState().is(Fluids.WATER)) return false;
        return true;
    }
}

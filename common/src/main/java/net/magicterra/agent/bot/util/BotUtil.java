package net.magicterra.agent.bot.util;

import net.magicterra.agent.bot.BotConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Stateless conversion / parsing / threading helpers shared across the bot
 * package. Extracted from the former {@code BotApiImpl} god-class; callers
 * pull these in via {@code import static …BotUtil.*} so call sites stay
 * unqualified.
 */
public final class BotUtil {

    private BotUtil() {}

    public static BlockPos blockPosOf(net.minecraft.world.entity.Entity e) {
        return new BlockPos((int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ()));
    }

    public static Map<String, Object> posMap(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    public static BlockPos readPos(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        Object x = m.get("x"), y = m.get("y"), z = m.get("z");
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) return null;
        return new BlockPos(((Number) x).intValue(), ((Number) y).intValue(), ((Number) z).intValue());
    }

    public static int intOr(Object o, int dflt) { return o instanceof Number n ? n.intValue() : dflt; }
    public static double doubleOr(Object o, double dflt) { return o instanceof Number n ? n.doubleValue() : dflt; }
    public static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    public static List<String> parseStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object e : l) if (e instanceof String s) out.add(s);
        } else if (o instanceof String s) {
            out.add(s);
        }
        return out;
    }

    public static Map<String, Object> unimplemented(String msg) {
        return Map.of("ok", false, "error", "unimplemented: " + msg);
    }

    // === Threading bridge ====================================================

    public static <T> T onClient(Supplier<T> body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) return body.get();
        CompletableFuture<T> fut = new CompletableFuture<>();
        mc.execute(() -> {
            try { fut.complete(body.get()); }
            catch (Throwable t) { fut.completeExceptionally(t); }
        });
        try { return fut.get(); }
        catch (Exception e) { throw new RuntimeException(e); }
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
    public static final Set<net.minecraft.world.level.block.Block> HAZARD_BLOCKS = Set.of(
            Blocks.MAGMA_BLOCK,
            Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.POWDER_SNOW,
            Blocks.WITHER_ROSE
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

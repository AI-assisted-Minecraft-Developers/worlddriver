package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Calibration for {@link JourneyFireCensus} — the instrument rung 14's death needs and which has
 * never been run.
 *
 * <h2>Why an instrument gets its own gate</h2>
 *
 * The census is going to be read as evidence for「the fire was already there」versus「the fire
 * appeared after the plan」, and those two answers call for opposite repairs. A census that silently
 * returns nothing answers the second one every time, and it answers it in exactly the shape a real
 * fire-free corridor would — so the reading that matters most is the one this file cannot take on
 * trust. Four arms, each killing a different way of being silently empty:
 *
 * <ul>
 *   <li><b>A</b> — a clean lane really is empty, AND the number of cells looked at is non-zero, so a
 *       zero can be told from a scan that never ran.</li>
 *   <li><b>B</b> — the occasion: two staged fire cells come back, as the CELLS, in scan order.</li>
 *   <li><b>C</b> — a box drawn around the start alone finds neither of them, so the span demonstrably
 *       depends on the far endpoint. A census that quietly ignored {@code to} would pass A and B.</li>
 *   <li><b>D</b> — a fire cell outside the padded box is NOT reported, so the bound is a bound.</li>
 * </ul>
 *
 * <p>Arms B and D together are the pair that matters: one proves the scan can say yes, the other
 * proves it can still say no. An instrument verified only on its positive arm is not verified — a
 * widened one that reported everything nearby would look like a working census on any corridor with
 * fire anywhere in the neighbourhood.
 *
 * <h2>What is deliberately NOT asserted</h2>
 *
 * Nothing here checks the row's prose. The disclaimer that the box is not the route is written for a
 * human and pinning its wording would make every rephrasing a red. What IS pinned is the reading a
 * machine or a reader compares against a burn coordinate: the cell list and the scanned count.
 *
 * <h2>Arena footprint</h2>
 *
 * <p>Stated by hand — {@code scripts/check_scene_arena.py} scans only
 * {@code .../bot/stagewright/scene/}. Every cell touched lies in {@code dx ∈ [-2, 8]},
 * {@code dz ∈ [-2, 8]}, {@code dy ∈ [BASE-1, BASE+5] = [19, 25]}: a netherrack floor with a handful
 * of fire on it, well inside the default one-chunk window.
 */
public final class JourneyFireCensusScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.netherCensusNamesTheFireInTheCorridorBox", 200,
                        JourneyFireCensusScenes::namesTheFireInTheCorridorBox));
    }

    // ---------------------------------------------------------------------- arena ----

    /** The floor row, as a dy offset — the same twenty above the grid the sibling journey arenas use. */
    private static final int BASE = 20;

    /** The pad the corridor legs will ask for: one cell on every side, enough that a body walking the
     *  span is inside the box even when it is a cell off its node. */
    private static final int PAD = 1;

    private static BlockPos from(SceneContext ctx) { return ctx.rel(0, BASE, 0); }

    private static BlockPos to(SceneContext ctx) { return ctx.rel(6, BASE, 4); }

    /** Inside the box, near the start. */
    private static BlockPos near(SceneContext ctx) { return ctx.rel(2, BASE, 1); }

    /** Inside the box, at the far end — the cell arm C would lose if the scan only looked around
     *  {@link #from}. */
    private static BlockPos far(SceneContext ctx) { return ctx.rel(5, BASE, 3); }

    /** Four rows above the span, so a pad of one cannot reach it however the horizontal bounds are
     *  computed. Fire is placed here too: a bound that is only tested against air is not tested. */
    private static BlockPos outside(SceneContext ctx) { return ctx.rel(0, BASE + 4, 0); }

    /** A lane the staging leaves clean, for the arm that has to see a real zero. */
    private static BlockPos cleanFrom(SceneContext ctx) { return ctx.rel(0, BASE, 7); }

    private static BlockPos cleanTo(SceneContext ctx) { return ctx.rel(6, BASE, 7); }

    private static void stage(SceneContext ctx) {
        for (int dx = -2; dx <= 8; dx++)
            for (int dz = -2; dz <= 8; dz++)
                for (int dy = BASE - 1; dy <= BASE + 5; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
        // NETHERRACK, not stone: fire on netherrack never burns out, so the census and the
        // assertions below cannot disagree because a tick passed between them.
        for (int dx = -2; dx <= 8; dx++)
            for (int dz = -2; dz <= 8; dz++)
                ctx.setBlock(dx, BASE - 1, dz, Blocks.NETHERRACK);
        ctx.setBlock(0, BASE + 3, 0, Blocks.NETHERRACK);
        ServerLevel level = ctx.level();
        for (BlockPos p : List.of(near(ctx), far(ctx), outside(ctx)))
            // Flag 2 — clients only, no neighbour update. A fire block asked to update itself on
            // placement can decide it has no business existing yet and vanish, which would stage the
            // arms of this scene into the very silence they exist to catch.
            level.setBlock(p, Blocks.FIRE.defaultBlockState(), 2);
    }

    // ---------------------------------------------------------------------- arms ----

    private static void namesTheFireInTheCorridorBox(SceneContext ctx) {
        ServerLevel level = ctx.level();
        stage(ctx);

        BlockPos from = from(ctx), to = to(ctx);
        for (BlockPos p : List.of(near(ctx), far(ctx), outside(ctx)))
            if (!level.getBlockState(p).is(net.minecraft.tags.BlockTags.FIRE))
                ctx.fail("THE RIG, not the subject: " + p.toShortString() + " 摆的火没留住，现在是 "
                        + level.getBlockState(p).getBlock() + " —— 底下四条臂量的就不是火了");

        // ---- A the control: a clean lane is empty, and says how much it looked at ----
        BlockPos cf = cleanFrom(ctx), ct = cleanTo(ctx);
        List<BlockPos> clean = JourneyFireCensus.fireCells(level, cf, ct, PAD);
        ctx.record("clean", cf.toShortString() + " → " + ct.toShortString() + " 这条干净车道找到 "
                + clean.size() + " 个火格：" + join(clean));
        ctx.check(clean.size()).as("A 没有火的走廊报 0 —— 而这个 0 只有在下面那个格数不为零时"
                + "才分得清「真没有」和「压根没扫」").isEqualTo(0);
        // THE PAD GROWS THE SPAN TOO, on both ends. The pre-registered value for this arm was
        // 7×3×3, counting the seven cells from x=0 to x=6 and then adding the pad only to the two
        // thin axes — and the census answered 81. It was right and the arithmetic here was wrong,
        // which is the whole reason an instrument gets a positive control with a NUMBER in it.
        ctx.check(JourneyFireCensus.scanned(cf, ct, PAD))
                .as("A' 而它确实扫过：(6−0+1)+2×" + PAD + " = 9 格长，另两轴各 1+2×" + PAD + " = 3")
                .isEqualTo(9 * 3 * 3);

        // ---- B the occasion: the two staged cells come back, as cells, in scan order ----
        List<BlockPos> found = JourneyFireCensus.fireCells(level, from, to, PAD);
        ctx.record("found", from.toShortString() + " → " + to.toShortString() + " 的盒里找到 "
                + found.size() + " 个火格：" + join(found));
        ctx.check(join(found)).as("B 盒里那两格火要被点名，按扫描序（x 升序），而且印的是格子不是"
                + "「有火/没火」—— 只有格子能跟烧伤那一行的坐标对上")
                .isEqualTo(join(List.of(near(ctx), far(ctx))));

        // ---- C the span really depends on the far endpoint ----
        List<BlockPos> atStart = JourneyFireCensus.fireCells(level, from, from, PAD);
        ctx.record("atStart", "只围着起点 " + from.toShortString() + " 画盒，找到 " + atStart.size()
                + " 个火格：" + join(atStart));
        ctx.check(atStart.size()).as("C 起点周围一格之内本来就没有火 —— 一个偷偷忽略终点的普查"
                + "会同时通过 A 和 B，只在这里露出来").isEqualTo(0);

        // ---- D the bound is a bound ----
        ctx.record("outside", "盒外那格火在 " + outside(ctx).toShortString() + "，比这条走廊高 4 排");
        ctx.check(found.contains(outside(ctx)))
                .as("D 盒外的火不许算进来 —— 一个把周围全报进来的普查在任何附近有火的走廊上"
                        + "都长得像它在工作").isEqualTo(false);
    }

    private static String join(List<BlockPos> cells) {
        StringBuilder out = new StringBuilder();
        for (BlockPos c : cells)
            out.append(out.isEmpty() ? "" : "、").append(c.toShortString());
        return out.isEmpty() ? "（空）" : out.toString();
    }
}

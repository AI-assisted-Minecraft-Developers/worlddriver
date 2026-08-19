package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.movement.WalkerGeometry;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>A body barely on a ledge over the void, holding blocks, with no legal move.</b>
 *
 * <h2>The cell this is a copy of</h2>
 *
 * Journey rung 20 has died at one coordinate across two runs with two different symptoms:
 *
 * <pre>{@code
 * run l: footing guard: sole 0.1594 < 0.18 at -32,85,27 beside a lethal drop -> sneak-pin
 *        ascend dead-zone UNREACHABLE move=diagUp node=(-33,86,26) foot=(-32,85,27)
 *        -> stood there 2400 ticks, the whole leg budget
 * run t: body left the world; last stood on -32,85,27 (108 ticks earlier)
 * }</pre>
 *
 * The body stands on 16% of its sole at the end of a bridge it built itself, void on every side,
 * the plan's next node one cell diagonally up, and 500+ cobblestone in the bag. The footing guard
 * sneak-pins it (right), the stride floor-guard refuses the next step (right), the recovery hop
 * refuses to jump with a lethal drop one cell away (right), and the ascent executor calls the node
 * UNREACHABLE (right). <b>Four correct refusals and no legal move.</b>
 *
 * <h2>Why this exists rather than another ladder run</h2>
 *
 * Three remedies were landed straight onto the ladder and the ladder could judge none of them: one
 * helped but not enough, one was never observed to fire, and one fired zero times in a whole run
 * (0 widen-footing lines while the footing guard fired five). A rung that dies somewhere new every
 * run cannot tell a fix from a coincidence. This arena reproduces the cell on its own.
 *
 * <h2>The two clauses, and why the second is the whole point</h2>
 *
 * <ol>
 *   <li><b>It must not fall.</b> The lowest y must stay at the ledge.</li>
 *   <li><b>It must actually reach the node.</b> Without this, standing perfectly still is a
 *       full-marks answer — and standing still is exactly what the body does today. The guards
 *       already satisfy clause 1; the entire defect lives in clause 2.</li>
 * </ol>
 *
 * <p><b>RED BY DESIGN until the body can widen its own footing.</b> The remedy this arm waits for is
 * one block placed into the empty column under the overhanging half of the sole: the stride guard
 * plugs the cell AHEAD, and nothing has ever plugged the cell the body is already half off.
 * {@code Walker.widenFooting} exists as of 2026-08-18 and did not fire once on the ladder; this arm
 * is where that gets answered instead of guessed.
 */
public final class WorldDriverThinFootingScenes implements SceneProvider {

    /** Clear cells under the ledge. Past survivable fall at full health, so a body that leaves the
     *  ledge is unambiguously lost rather than merely hurt. */
    private static final int VOID_DEPTH = 30;

    /** Idle ticks the stand must survive untouched before the subject is allowed to act. */
    private static final int SETTLE_TICKS = 20;

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.serverWidensAThinFooting", 600,
                        ctx -> widensAThinFooting(ctx, 0)).withRequired(false),
                // Same cell, same stack, one difference: the blocks are in the backpack rather than
                // the hotbar. holdPlaceable() has only ever looked at slots 0..8 (a tower once
                // reported "out of blocks?" while the body held 110 cobblestone), and rung 20 walks
                // its End legs with the haul wherever picking it up put it. Two arms differing by
                // exactly one variable is the only way to tell "the remedy is wrong" from "the
                // remedy could not see the blocks".
                Scene.of("wd.serverWidensFromTheBackpack", 600,
                        ctx -> widensAThinFooting(ctx, 20)).withRequired(false),
                Scene.of("wd.serverStopsAtTheBridgeHead", 600,
                        ctx -> stopsAtTheBridgeHead(ctx, 0)).withRequired(false),
                // Same bridge, one variable different: the goal is off the bridge's axis, so the
                // path must TURN at the head instead of running straight out of it. The ladder's
                // eighth departure had node -13,111,-3 — three out and one across — and the
                // straight arm passes, so the turn is the only difference left to test.
                Scene.of("wd.serverTurnsAtTheBridgeHead", 600,
                        ctx -> stopsAtTheBridgeHead(ctx, -3)).withRequired(false));
    }

    private static void widensAThinFooting(SceneContext ctx, int slot) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int deckY = ctx.origin().getY() + 40;
        final int standY = deckY + 1;

        // One 1-wide strip of ledge and a single landing cell one up and one across — the diagUp the
        // ladder's plan keeps producing. Everything else is cleared, so any block found under the
        // body afterwards was put there by the body.
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 8; dz++)
                for (int y = deckY - VOID_DEPTH; y <= deckY + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            Blocks.AIR.defaultBlockState());
        for (int dz = -3; dz <= 0; dz++)
            level.setBlockAndUpdate(new BlockPos(cx, deckY, cz + dz),
                    Blocks.OBSIDIAN.defaultBlockState());
        BlockPos landing = new BlockPos(cx - 1, deckY + 1, cz + 1);
        level.setBlockAndUpdate(landing, Blocks.OBSIDIAN.defaultBlockState());
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 8; dz++)
                if (dx < -2 || dx > 2 || dz < -2 || dz > 3)      // outside the shaft, see below
                    level.setBlockAndUpdate(new BlockPos(cx + dx, deckY - VOID_DEPTH, cz + dz),
                            Blocks.STONE.defaultBlockState());
        // A GENUINE shaft under the cells the body's own footprint spans. The remedy this arm judges
        // only spends a block over a column that is bottomless — scanned all the way to
        // Walker.BOTTOMLESS_SCAN_FLOOR (-70) — and it is right to: over an ordinary drop a thin sole
        // is a graze, and paying a block per ridge walk eats a bridging contract. A catch floor 30
        // cells down therefore reads as ordinary ground and the remedy declines, which is the arm
        // measuring its own arena rather than the subject. The End void the ladder dies over has no
        // floor, so neither does this.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 3; dz++)
                for (int y = level.getMinBuildHeight(); y < deckY; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            Blocks.AIR.defaultBlockState());

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = true;                 // the remedy IS a placement
        BotConfig.allowBreak = false;                // digging out is not the subject
        BotConfig.walkerDebug = true;

        // Nudge outward until the sole reads under the guard's own threshold rather than computing
        // an offset: the guard's predicate is the authority on what "barely on" means, and a rig
        // that assumes a number can stage a body the guard never looks at.
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        double sole = WalkerGeometry.soleOnSolid(w, fp);
        double nudge = 0.5;
        for (int i = 0; i < 40 && sole >= Walker.footingMin(); i++) {
            nudge += 0.02;
            fp.setPos(cx + nudge, standY, cz + 0.5);
            sole = WalkerGeometry.soleOnSolid(w, fp);
        }
        ctx.record("rig", "1-wide obsidian ledge x=" + cx + " z=" + (cz - 3) + ".." + cz
                + " y=" + deckY + " | landing (one up, one across)=" + landing.getX() + ","
                + landing.getY() + "," + landing.getZ() + " | " + VOID_DEPTH
                + " clear cells under it, catch floor y=" + (deckY - VOID_DEPTH));
        ctx.record("staged", String.format(Locale.ROOT, "body x=%.2f sole=%.4f (threshold %s)",
                nudge, sole, Walker.footingMin()));
        if (sole >= Walker.footingMin()) {
            ctx.fail("THE RIG, not the subject: could not stage a stand under the footing threshold"
                    + " (sole=" + sole + ")");
            return;
        }

        // Let vanilla physics have the stand BEFORE any driver touches it. Without this the two
        // explanations for a fall — "the rig handed over a stand vanilla was never going to hold"
        // and "the subject walked off a stand it was given" — produce the same reading, and the
        // first one is a rig fault masquerading as a finding.
        for (int i = 0; i < SETTLE_TICKS; i++) av.step();
        ctx.record("settle", String.format(Locale.ROOT,
                "%d ticks with no driver: y=%.3f sole=%.4f", SETTLE_TICKS, fp.getY(),
                WalkerGeometry.soleOnSolid(w, fp)));
        if (fp.getY() < standY - 0.5) {
            ctx.fail("THE RIG, not the subject: vanilla itself dropped the staged stand in "
                    + SETTLE_TICKS + " idle ticks (y=" + fp.getY() + "), so this arena is not the"
                    + " ladder's cell — there the body stood on it for 2400 ticks");
            return;
        }

        // Every clause of the guard's own fire predicate, asked here in the same order it asks them.
        // The guard's log line is unconditional, so zero lines proves it never got past one of these
        // — and "never entered" is a different defect from "entered and declined".
        BlockPos footCell = BlockPos.containing(fp.getX(), fp.getY() + 0.05, fp.getZ());
        ctx.record("guardClauses", "lethalEdgeBrake=" + BotConfig.lethalEdgeBrake
                + " inWater=" + fp.isInWater()
                + " sole=" + String.format(Locale.ROOT, "%.4f", sole)
                + " foot=" + footCell.getX() + "," + footCell.getY() + "," + footCell.getZ()
                + " (" + level.getBlockState(footCell) + ")"
                + " below=" + level.getBlockState(footCell.below())
                + " lethalDropAdjacent=" + WalkerGeometry.lethalDropAdjacent(w, fp, footCell));

        fp.getInventory().clearContent();
        fp.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;
        ctx.record("stock", "64 cobblestone in slot " + slot + " ("
                + (slot <= 8 ? "hotbar" : "backpack") + "), holdPlaceable=" + av.holdPlaceable());

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(landing.above()));
        double minY = fp.getY();
        int t = 0;
        Walker.Step s = Walker.Step.WALKING;
        int fireable = 0;                 // ticks on which the guard's whole predicate held
        StringBuilder soles = new StringBuilder();
        for (; t < 400 && s == Walker.Step.WALKING; t++) {
            double sn = WalkerGeometry.soleOnSolid(w, fp);
            BlockPos fc = BlockPos.containing(fp.getX(), fp.getY() + 0.05, fp.getZ());
            if (BotConfig.lethalEdgeBrake && !fp.isInWater() && sn > 0.0
                    && sn < Walker.footingMin() && WalkerGeometry.lethalDropAdjacent(w, fp, fc))
                fireable++;
            if (t < 8) soles.append(String.format(Locale.ROOT, " t%d=%.3f", t, sn));
            s = walker.tick(av, w);
            av.step();
            minY = Math.min(minY, fp.getY());
            if (fp.getY() < deckY - 3) break;        // shed: nothing after this is about footing
        }
        int spent = 64 - fp.getInventory().countItem(Items.COBBLESTONE.asItem());
        boolean underfootSolid = !level.getBlockState(new BlockPos(
                (int) Math.floor(fp.getX()), deckY, (int) Math.floor(fp.getZ()))).isAir();
        boolean arrived = fp.blockPosition().getY() >= landing.getY() + 1
                && Math.abs(fp.getX() - (landing.getX() + 0.5)) < 1.2
                && Math.abs(fp.getZ() - (landing.getZ() + 0.5)) < 1.2;

        ctx.record("fireable", fireable + " of " + t + " drive ticks had the guard's WHOLE"
                + " predicate true; its log line is unconditional, so any gap between this count"
                + " and the [walker] footing guard lines is the guard not being reached at all."
                + " sole per tick:" + soles);
        ctx.record("drive", String.format(Locale.ROOT, "%d ticks, body=(%.2f,%.2f,%.2f) step=%s",
                t, fp.getX(), fp.getY(), fp.getZ(), s));
        ctx.record("minY", String.format(Locale.ROOT, "%.3f (deck %d, criterion > %d)",
                minY, standY, standY - 1));
        ctx.record("widen", spent + " cobblestone left the bag; the deck row under the body is "
                + (underfootSolid ? "SOLID" : "AIR"));
        ctx.record("arrived", arrived + " (target " + landing.getX() + "," + (landing.getY() + 1)
                + "," + landing.getZ() + ", one up and one across — not reached today, see the"
                + " class note)");

        ctx.expect(minY > standY - 1).as("the body must not drop below the ledge").isTrue();
        ctx.expect(arrived).as("it must actually reach the cell one up and one across — with only"
                + " the first clause, standing perfectly still scores full marks, and that is"
                + " exactly today's behaviour").isTrue();
    }

    /**
     * <b>A body with a FULL sole walks off the end of its own bridge.</b>
     *
     * <h2>The shape this is a copy of</h2>
     *
     * Seven families of rung-20 departure have been closed one at a time — three in the planner
     * (runway, void leaps, void diagonals) and four in the executor (no brake, tower drift, jumping
     * off a graze, leaping from a standstill). The eighth is none of them:
     *
     * <pre>{@code
     * step=WALKING 跳标=未标 身体=-10.38,111.00,-2.44 速度h=0.118 脚底=0.360 节点=-13,111,-3
     * }</pre>
     *
     * No jump. Sole 0.360 — the FULL 0.6×0.6 footprint, not a graze. Walking speed, level target.
     * The body simply walked off the end of a bridge it had built, at y=111 over the End void.
     *
     * <h2>Why an arena and not another gate</h2>
     *
     * Each of the seven gates was landed straight onto the ladder and each bought exactly one thing:
     * a new departure coordinate on the next run. The two remedies that actually turned green in
     * this suite ({@code wd.serverWidens*}) were both built as arenas first. {@code strideFloorGuard}
     * is the guard whose job this is, it has been changed twice this session, and nothing in 271
     * scenes can judge it.
     *
     * <h2>What it actually found: the hypothesis was wrong</h2>
     *
     * It passes, first run, decisively — 8 deck cells walked, then <b>7 cobblestone spent bridging
     * past the end</b> to a goal six cells out over open void, {@code minY} exactly the deck. So the
     * stride guard is not broken for a straight walk-off, and the ladder's eighth departure is not
     * this. Kept anyway, and not as consolation: it is the only scene in 272 that exercises the
     * guard at all, and it pins the behaviour the next change to that guard could break silently —
     * the same guard has been edited twice in one session with nothing able to judge either edit.
     *
     * <p>The ladder's node was {@code -13,111,-3} — three cells out and one across, so the path was
     * TURNING at the head, not running straight down it. That difference is the next arm to build,
     * and it is a fact this arena earned by not reproducing the bug.
     *
     * <h2>The two clauses</h2>
     *
     * <ol>
     *   <li><b>It must not fall.</b> The lowest y stays at the deck.</li>
     *   <li><b>It must actually have walked.</b> Without this, refusing to move at all is a
     *       full-marks answer — and a guard that pins the body at the first cell would "pass" while
     *       making the ladder unable to cross anything. The bar is four of the eight deck cells.</li>
     * </ol>
     */
    private static void stopsAtTheBridgeHead(SceneContext ctx, int lateral) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int deckY = ctx.origin().getY() + 40;
        final int standY = deckY + 1;
        final int deckCells = 8;

        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 16; dz++)
                for (int y = deckY - 4; y <= deckY + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            Blocks.AIR.defaultBlockState());
        // A genuine shaft under and past the bridge: the guard only arms instantly over a column
        // that is bottomless to Walker's own scan floor, so a catch floor would measure the arena.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 16; dz++)
                for (int y = level.getMinBuildHeight(); y < deckY; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            Blocks.AIR.defaultBlockState());
        for (int i = 0; i < deckCells; i++)
            level.setBlockAndUpdate(new BlockPos(cx, deckY, cz + i),
                    Blocks.OBSIDIAN.defaultBlockState());

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = true;      // bridging onward is a legitimate answer, and the best one
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = true;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, standY, cz + 1.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;

        for (int i = 0; i < SETTLE_TICKS; i++) av.step();
        if (fp.getY() < standY - 0.5) {
            ctx.fail("THE RIG, not the subject: vanilla dropped the staged stand in " + SETTLE_TICKS
                    + " idle ticks (y=" + fp.getY() + ")");
            return;
        }

        // A goal well past the deck's end, so the walk has every reason to keep going and the only
        // thing that can stop it at the head is the guard.
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(
                new BlockPos(cx + lateral, standY, cz + deckCells + 6)));
        double minY = fp.getY();
        double farZ = fp.getZ();
        int t = 0;
        Walker.Step s = Walker.Step.WALKING;
        for (; t < 400 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            minY = Math.min(minY, fp.getY());
            farZ = Math.max(farZ, fp.getZ());
            if (fp.getY() < deckY - 3) break;
        }
        int spent = 64 - fp.getInventory().countItem(Items.COBBLESTONE.asItem());
        double walked = farZ - (cz + 1.5);

        ctx.record("goal", lateral == 0 ? "目标在桥的延长线上（直走）"
                : "目标横向偏 " + lateral + " 格 —— 路径必须在桥头转向，而真梯第八族的节点正是"
                  + "「三格外、偏一格」");
        ctx.record("rig", "1 格宽黑曜石桥 x=" + cx + " z=" + cz + ".." + (cz + deckCells - 1)
                + " y=" + deckY + "，桥外与桥下全部挖空到 y=" + level.getMinBuildHeight()
                + "（守卫只在直通虚空的柱子上立即武装，铺接住的地板等于在量场地）");
        ctx.record("drive", String.format(Locale.ROOT, "%d tick，身体=(%.2f,%.2f,%.2f) step=%s",
                t, fp.getX(), fp.getY(), fp.getZ(), s));
        ctx.record("minY", String.format(Locale.ROOT, "%.3f（桥面 %d，判据 > %d）",
                minY, standY, standY - 1));
        ctx.record("walked", String.format(Locale.ROOT,
                "沿桥走了 %.2f 格（共 %d 格），最远 z=%.2f，判据 ≥ 4", walked, deckCells, farZ));
        ctx.record("bridged", spent + " 块圆石离开背包（守卫可以选择架桥继续，那是最好的答案）");

        ctx.expect(minY > standY - 1).as("身体不许掉到桥面以下").isTrue();
        ctx.expect(walked >= 4.0).as("而且必须真的沿桥走过 4 格 —— 只有前一条判据的话，"
                + "「一步都不迈」就是满分答案，而那样的守卫会让整条真梯寸步难行").isTrue();
    }
}

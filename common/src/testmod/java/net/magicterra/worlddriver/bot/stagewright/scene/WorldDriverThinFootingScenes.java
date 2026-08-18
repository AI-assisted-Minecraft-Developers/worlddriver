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
        return List.of(Scene.of("wd.serverWidensAThinFooting", 600,
                WorldDriverThinFootingScenes::widensAThinFooting).withRequired(false));
    }

    private static void widensAThinFooting(SceneContext ctx) {
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
        fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;

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
}

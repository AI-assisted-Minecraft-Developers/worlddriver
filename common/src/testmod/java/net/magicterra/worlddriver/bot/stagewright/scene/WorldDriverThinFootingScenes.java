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
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
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
                        ctx -> widensAThinFooting(ctx, 0)),
                // Same cell, same stack, one difference: the blocks are in the backpack rather than
                // the hotbar. holdPlaceable() has only ever looked at slots 0..8 (a tower once
                // reported "out of blocks?" while the body held 110 cobblestone), and rung 20 walks
                // its End legs with the haul wherever picking it up put it. Two arms differing by
                // exactly one variable is the only way to tell "the remedy is wrong" from "the
                // remedy could not see the blocks".
                Scene.of("wd.serverWidensFromTheBackpack", 600,
                        ctx -> widensAThinFooting(ctx, 20)),
                // The PLANNER's half of the backpack question above. wd.serverWidensFromTheBackpack
                // proved the executor can reach slots 9..35; these two ask whether A* knows that.
                // Same gap, same stack, one variable: which slot holds it.
                Scene.of("wd.serverPlansABridgeFromTheHotbar", 600,
                        ctx -> plansABridge(ctx, 0)),
                Scene.of("wd.serverPlansABridgeFromTheBackpack", 600,
                        ctx -> plansABridge(ctx, 20)),
                Scene.of("wd.serverStopsAtTheBridgeHead", 600,
                        ctx -> stopsAtTheBridgeHead(ctx, 0)),
                // Same bridge, one variable different: the goal is off the bridge's axis, so the
                // path must TURN at the head instead of running straight out of it. The ladder's
                // eighth departure had node -13,111,-3 — three out and one across — and the
                // straight arm passes, so the turn is the only difference left to test.
                Scene.of("wd.serverTurnsAtTheBridgeHead", 600,
                        ctx -> stopsAtTheBridgeHead(ctx, -3)),
                Scene.of("wd.serverDrawsABow", 300,
                        WorldDriverThinFootingScenes::drawsABow),
                // The same shelf twice; the only variable is what is at the bottom of the bay. See
                // stopsAtALavaShore for the measurement — the guard's floor scan counts a lava lake
                // as a floor, so the arm whose bay is lava is the one that walks in.
                Scene.of("wd.serverStopsAtALavaShore", 600,
                        WorldDriverThinFootingScenes::stopsAtALavaShore),
                Scene.of("wd.serverWalksOffASurvivableLedge", 600,
                        WorldDriverThinFootingScenes::walksOffASurvivableLedge),
                // The shore arms above ask whether the guard STOPS a body walking into a lake. These
                // two ask the opposite question about the same guard: what the pin costs a body whose
                // route runs ALONG the rim and was never going in. Same trench twice, and its fill is
                // again the only variable.
                Scene.of("wd.serverKeepsWalkingAtALavaRim", 600,
                        WorldDriverThinFootingScenes::keepsWalkingAtALavaRim),
                Scene.of("wd.serverKeepsWalkingAtADryRim", 600,
                        WorldDriverThinFootingScenes::keepsWalkingAtADryRim));
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
        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 0.5);
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
     * <b>Does A* know about the blocks the executor can reach?</b>
     *
     * <h2>The cell this is a copy of</h2>
     *
     * Journey rung 14's fortress corridor, waypoint 8. Three archived ladder runs seat the body at
     * the identical cell — {@code fortress.wp7.at = 88,41,107} in every one — and give the identical
     * next hop, {@code 88,41,107 → 95,41,115}, eleven blocks. Two crossed it. The third:
     *
     * <pre>{@code
     * fortress.wp8.at  停在 88, 41, 107，差 11 格   end=failed:no path (expanded=100000)
     * 身上还剩 205 个可放置方块（netherrack×2, cobblestone×104, dirt×79, diorite×7, granite×13）
     * }</pre>
     *
     * <p>Same seed, same terrain, same seat, same goal, blocks in hand — and 100000 nodes spent on a
     * hop one bridge edge crosses. The variable is which SLOTS those 205 blocks were sitting in.
     *
     * <h2>The asymmetry</h2>
     *
     * {@link ServerPlayerAvatar#holdPlaceable()} swaps a stack up from slots 9..35 when the hotbar
     * has none — the executor is not limited to the hotbar. The planner was: {@code
     * LevelWorldView.placeableBlockCount()} counted 0..8 only. Two consumers turn that gap into a
     * dead leg — {@code BridgePlace.eval} emits no bridge edge, and {@code WalkerTickSearch}'s block
     * budget throws away a path A* has ALREADY FOUND and re-searches with placing OFF whenever the
     * edges outnumber the count. Over a nether gap, place-off leaves only walking, and walking is
     * what spends the node budget.
     *
     * <h2>Why two arms</h2>
     *
     * A single arm cannot separate "the remedy is wrong" from "the remedy could not see the blocks"
     * — the same reason {@code wd.serverWidens*} is a pair. These two differ by the slot index and
     * nothing else.
     *
     * <h2>The three clauses, and why the first is not decoration</h2>
     *
     * <ol>
     *   <li><b>The staging must have created the condition.</b> The backpack arm asserts the hotbar
     *       really is empty of placeables. Without it, a staging that quietly left a stack in slot 0
     *       would make the other two clauses true for the wrong reason, and the scene would be
     *       {@code 0 == 0} — a shape this suite has been burned by before.</li>
     *   <li><b>The planner must count them.</b> The number A* reads, asserted directly, because it
     *       is the quantity the defect was in.</li>
     *   <li><b>And it must actually cross.</b> Counting right while still failing to bridge would
     *       mean the fix went to the wrong consumer.</li>
     * </ol>
     */
    private static void plansABridge(SceneContext ctx, int slot) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int deckY = ctx.origin().getY() + 40;
        final int standY = deckY + 1;
        final int nearCells = 4;      // deck the body starts on
        final int gapCells = 4;       // open void it must bridge
        final int farCells = 4;       // deck on the other side

        // Void everywhere in the working box, and genuinely bottomless: a catch floor below the gap
        // would let the walker fall and recover, which measures the arena instead of the planner.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= nearCells + gapCells + farCells + 4; dz++)
                for (int y = level.getMinBuildHeight(); y <= deckY + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            Blocks.AIR.defaultBlockState());
        for (int i = 0; i < nearCells; i++)
            level.setBlockAndUpdate(new BlockPos(cx, deckY, cz + i),
                    Blocks.OBSIDIAN.defaultBlockState());
        final int farStart = nearCells + gapCells;
        for (int i = farStart; i < farStart + farCells; i++)
            level.setBlockAndUpdate(new BlockPos(cx, deckY, cz + i),
                    Blocks.OBSIDIAN.defaultBlockState());

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = true;
        BotConfig.allowBreak = false;   // there is nothing to dig through; bridging is the only answer
        BotConfig.walkerDebug = true;

        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        fp.getInventory().clearContent();
        fp.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;

        for (int i = 0; i < SETTLE_TICKS; i++) av.step();
        if (fp.getY() < standY - 0.5) {
            ctx.fail("THE RIG, not the subject: vanilla dropped the staged stand in " + SETTLE_TICKS
                    + " idle ticks (y=" + fp.getY() + ")");
            return;
        }

        int hotbar = 0;
        for (int s = 0; s < 9; s++) hotbar += fp.getInventory().items.get(s).getCount();
        final int planner = w.placeableBlockCount();

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(new BlockPos(cx, standY, cz + farStart + farCells - 1)));
        double minY = fp.getY();
        double farZ = fp.getZ();
        int t = 0;
        Walker.Step s = Walker.Step.WALKING;
        for (; t < 500 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            minY = Math.min(minY, fp.getY());
            farZ = Math.max(farZ, fp.getZ());
            if (fp.getY() < deckY - 3) break;
        }
        int spent = 64 - fp.getInventory().countItem(Items.COBBLESTONE.asItem());
        boolean crossed = farZ >= cz + farStart;

        ctx.record("rig", "近岸 " + nearCells + " 格 + 虚空 " + gapCells + " 格 + 对岸 "
                + farCells + " 格，1 格宽黑曜石，x=" + cx + " y=" + deckY
                + "，箱下挖空到 y=" + level.getMinBuildHeight() + "（接住的地板等于在量场地）");
        ctx.record("slot", "64 圆石放在槽位 " + slot
                + (slot < 9 ? "（快捷栏 —— 对照臂）" : "（背包 —— 执行器够得着，问的是规划器）"));
        ctx.record("hotbar", "快捷栏里 " + hotbar + " 件东西"
                + (slot < 9 ? "" : "，判据 = 0：不为零说明布景没造出条件，后两条判据就成了恒真"));
        ctx.record("planner", "A* 读到的可放置数 = " + planner + "，判据 = 64"
                + "（修法前这一臂读到的是 0，因为它只数 0..8）");
        ctx.record("drive", String.format(Locale.ROOT, "%d tick，身体=(%.2f,%.2f,%.2f) step=%s",
                t, fp.getX(), fp.getY(), fp.getZ(), s));
        ctx.record("crossed", String.format(Locale.ROOT,
                "最远 z=%.2f（对岸起点 z=%d，判据 ≥ 该值）；%d 块圆石离开背包", farZ, cz + farStart, spent));
        ctx.record("minY", String.format(Locale.ROOT, "%.3f（桥面 %d，判据 > %d）",
                minY, standY, standY - 1));

        if (slot >= 9)
            ctx.expect(hotbar).as("布景必须真的把石头放到快捷栏之外，否则这个场景什么也没测")
                    .isEqualTo(0);
        ctx.expect(planner).as("规划器数到的方块必须是执行器够得着的那些 —— 这就是缺陷所在的那个量")
                .isEqualTo(64);
        ctx.expect(minY > standY - 1).as("身体不许掉进虚空").isTrue();
        ctx.expect(crossed).as("而且必须真的架桥过去 —— 只数对不过去，说明修法接错了消费者")
                .isTrue();
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

        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx + 0.5, standY, cz + 1.5);
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

    /**
     * <b>Can this body draw a bow and loose an arrow at all?</b>
     *
     * <h2>Why this exists</h2>
     *
     * Rung 20's ranged half has been fixed four times — the bow was in the bag not the hand, the
     * dragon search box was narrower than the arena it flies in, a raised block budget overflowed
     * the inventory and pushed the bow out of it, and the avatar's edge-triggered use flag could not
     * re-arm after vanilla stopped the use. Every one of those was a real defect. None of them moved
     * the number: three consecutive rehearsals reported {@code 拉弓计数 7, 箭存量 256} — the same 7,
     * before and after a change that should have altered it. A value that does not move when its
     * cause is removed is measuring something else, and thirty minutes per reading is the wrong
     * price for finding out what.
     *
     * <p>So: flat stone, a body, a bow, arrows, and nothing else — no dragon, no walker, no
     * knockback, no fountain. Hold the use for well past a full draw and ask the only question that
     * matters: <b>did the quiver go down?</b> Everything the ladder adds on top of this is a
     * separate question, and none of it is worth asking until this one has an answer.
     */
    private static void drawsABow(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int deckY = ctx.origin().getY() + 4;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, deckY, cz + dz),
                        Blocks.STONE.defaultBlockState());
                for (int y = deckY + 1; y <= deckY + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            Blocks.AIR.defaultBlockState());
            }

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);

        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, cx + 0.5, deckY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.BOW, 1));
        fp.getInventory().setItem(9, new ItemStack(Items.ARROW, 64));
        fp.getInventory().selected = 0;
        fp.setXRot(-20.0F);                       // aim up a little so the arrow clears the deck

        int before = fp.getInventory().countItem(Items.ARROW);
        StringBuilder draw = new StringBuilder();
        int maxDraw = 0;
        // Hold the use for three full draws' worth of ticks. If the counter resets on a cycle, the
        // per-tick line shows the cycle; if it climbs and holds, the release is the suspect instead.
        av.commandUseItem(true);
        for (int t = 0; t < 60; t++) {
            av.step();
            int d = fp.isUsingItem() ? fp.getTicksUsingItem() : -1;
            maxDraw = Math.max(maxDraw, d);
            if (t < 24) draw.append(' ').append(d);
        }
        av.commandUseItem(false);                 // up-edge = release
        final int drewTo = maxDraw;
        final String drawLine = draw.toString();
        // Real SERVER ticks, not av.step(). step() ticks the avatar; a freshly spawned arrow sits in
        // the level's pending-entity queue until the LEVEL ticks, so counting inside a synchronous
        // loop asks the entity index about something it has not been told about yet — and reports a
        // working bow as a silent one. Same shape as an arena whose entities were inert because PREP
        // never waited for the promotion.
        int[] waited = {0};
        ctx.await(() -> ++waited[0] >= 10).within(60)
                .then(() -> finishBowArm(ctx, level, fp, before, drewTo, drawLine));
    }

    /**
     * How far from the body the arrow count looks, and the number is arithmetic rather than margin.
     *
     * <p>A full draw leaves the string at {@code power * 3.0} = <b>3 blocks per tick</b>
     * ({@code BowItem.releaseUsing} → {@code shootFromRotation(..., 3.0F, 1.0F)}), and the count runs
     * ten real server ticks later — the wait that exists so the entity is promoted out of the pending
     * queue. Thirty blocks of flight against a 24-block box is a scene that fails whenever the arrow
     * happens to fly straight, which is most of the time it is working: one run reported
     * {@code flew=0} beside {@code probe.flew=1} and read as「the release produced nothing」about a
     * release that had produced an arrow and lost it. Three times the arrow's own reach, so no draw
     * this arm can produce outruns the question.
     */
    private static final double ARROW_SEARCH = 96.0;

    private static void finishBowArm(SceneContext ctx, ServerLevel level, ServerPlayer fp,
            int before, int maxDraw, String draw) {
        int after = fp.getInventory().countItem(Items.ARROW);
        // Count the ARROWS IN THE WORLD, not the ones missing from the bag. A player with
        // instabuild gets a fresh projectile from getProjectile() and the quiver is never touched,
        // so an ammo delta can be structurally zero while the bow is working perfectly — and that
        // is precisely the counter rung 20 has been reporting for four rounds of「fixes」.
        int flew = level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class,
                fp.getBoundingBox().inflate(ARROW_SEARCH)).size();

        ctx.record("draw", "按住 60 tick，逐 tick 的 getTicksUsingItem（-1 = 那一 tick 不在使用中）:"
                + draw + " …… 最大 " + maxDraw + "（满蓄力需要 20）");
        ctx.record("promote", "松手后又等了 10 个真实服务器 tick 才数箭 —— 实体是在 level tick 时"
                + "才从待加入队列里提升的，同步循环里数等于问一个还没被告知的索引");
        ctx.record("ammo", "松手前 " + before + " 支 → 松手后 " + after + " 支"
                + "（instabuild=" + fp.getAbilities().instabuild
                + "；为真时 vanilla 从 getProjectile 另发一支、箭袋不动，为假时必须扣一支）");
        ctx.record("flew", flew + " 支箭出现在世界里（这才是「射出去了」的证据）");
        ctx.record("hand", "主手=" + fp.getMainHandItem().getItem()
                + "，isUsingItem=" + fp.isUsingItem());
        // DISCRIMINATOR. Two explanations survive an empty sky: stopUsingItem() never reached
        // BowItem.releaseUsing, or releaseUsing ran and this body cannot spawn a projectile at all.
        // They call for opposite fixes, so ask directly rather than picking one. Probe on the
        // failure path only; it never runs when the normal release already worked.
        ItemStack bow = fp.getMainHandItem();
        String probe;
        try {
            bow.getItem().releaseUsing(bow, level, fp, bow.getItem().getUseDuration(bow, fp) - 30);
            probe = "直接调用 releaseUsing 没有抛异常";
        } catch (RuntimeException e) {
            probe = "直接调用 releaseUsing 抛了 " + e;
        }
        ctx.record("probe", probe + "；弹药查询 getProjectile="
                + fp.getProjectile(bow).getItem()
                + "，instabuild=" + fp.getAbilities().instabuild);
        int afterProbe = level.getEntitiesOfClass(
                net.minecraft.world.entity.projectile.AbstractArrow.class,
                fp.getBoundingBox().inflate(ARROW_SEARCH)).size();
        ctx.record("probe.flew", afterProbe + " —— 直接调 releaseUsing 之后世界里的箭数。"
                + "跟上面的 flew 一起读：两个都是 0 说明这具身体根本生不出箭；"
                + "只有这一个非 0 说明 stopUsingItem 没走到 releaseUsing");

        ctx.expect(maxDraw >= 20).as("按住 60 tick 之后，拉弓计数必须至少到过一次满蓄力 20").isTrue();
        if (!fp.getAbilities().instabuild) {
            // Not a duplicate of flew: an entity in the sky only proves something spawned.
            // The quiver going down by exactly one proves it came out of BowItem.releaseUsing.
            ctx.expect(before - after == 1).as("松手之后箭袋里必须恰好少一支，实测 "
                    + before + " → " + after);
        }
        ctx.expect(flew >= 1).as("松手之后世界里必须出现一支箭 —— 只断言拉弓计数的话，"
                + "一次射不出箭的满蓄力也是满分答案；而只断言箭袋减少的话，"
                + "instabuild 下即使正常开火也永远不合格").isTrue();
    }

    // =====================================================================================
    // A lava shore — the drop none of the three brakes could see.
    // =====================================================================================

    /**
     * <b>A body walks straight off a shelf into a lava bay, and the guard whose job that was
     * declines because it counted the lake as a floor.</b>
     *
     * <h2>The tick this is a copy of</h2>
     *
     * Journey rung 14 crosses the Nether to a fortress and has now ended the same way four times.
     * The third hop of the run of 2026-08-19 printed the whole tick, read off the level rather than
     * off the bot's own view:
     *
     * <pre>{@code
     * fortress.ground.3.0 = 上一 tick：位置 (80.407, 42.0000, 81.368) 速度 (0.109, -0.078, 0.045)
     *   onGround=true 潜行=false；walker 那一 tick：drive=y-59 F1.00 L0.00 s0.00 f1.00
     *   vanilla 自己那一问（脚下 0.0784 格内有碰撞吗）=没有；实心接触面积 0.0000/0.36
     *   致命边刹车照 level 重算：1/0=岩10 -1/0=落1 0/1=岩10 0/-1=底 … → 该响
     * fortress.fell.3.0   = 从 80, 42, 81 … → 落进岩浆 80, 29, 81，坠 13 格
     *   计划下一格 77, 41, 83[diagDown]（计划第 1/6 步）… 距身体 3.70 格水平
     * }</pre>
     *
     * <p>Three brakes could have held that body and all three were off, for three different reasons:
     *
     * <ul>
     *   <li>{@code WalkerTickDrive}'s {@code edgeBrake} — released, because {@code plannedDescent}
     *       was true: the node it was steering at ({@code 77,41,83}) sits one below the foot.</li>
     *   <li>{@link Walker#footingGuard} — released by the SAME planned-descent exemption, and on the
     *       last two ticks also by {@code sole <= 0.0}. Both releases are deliberate and both are
     *       load-bearing: vanilla sneak refuses to walk off ANY edge, so a pin held across a step the
     *       route means to take deadlocks the descent, and wd.descent / wd.bridgeDescend /
     *       wd.descentYaw named that cost in one run.</li>
     *   <li>{@link Walker#strideFloorGuard} — the one guard with NO release here. Its own
     *       planned-descent exemption demands the descending node be in the stride column EXACTLY,
     *       and that plan was heading the other way. It asked its question and got a wrong answer.</li>
     * </ul>
     *
     * <h2>The wrong answer, and why it is arithmetic rather than judgement</h2>
     *
     * The guard's fall scan walks down from the stride cell and stops at the first cell that is not
     * {@code isPassable}. <b>Lava is passable</b> — not solid, not water — so the scan descended
     * straight through the lake and stopped on its netherrack bed. Measured on that cell: the lava
     * begins 13 rows down and the bed sits <b>exactly 23</b> rows down, which is the loop's own reach
     * at full health ({@code ceil(20)+3}). It found a floor on the last index it looks at and called
     * the stride safe. {@code WalkerGeometry.dropAdjacentExceeds} learned this same lesson in round52
     * and carries the {@code isHazard} line; this guard never got it.
     *
     * <h2>Two arms, one variable</h2>
     *
     * The same headland, the same eight cells of walk, the same eleven-block bay — a drop that is
     * comfortably SURVIVABLE dry, so nothing in this arena is lethal except what the bay is filled
     * with. {@code wd.serverStopsAtALavaShore} fills it with lava and requires the guard to stop the
     * body; {@code wd.serverWalksOffASurvivableLedge} fills it with stone and requires the guard to
     * stay out of the way. A fix that made every ledge a pin would pass the first and fail the
     * second, which is the whole reason the second exists.
     *
     * <h2>Each arm carries its own control</h2>
     *
     * Every arm drives the shelf TWICE over an identical staging, with {@code walkerStrideFloorGuard}
     * as the only difference between the two drives. On lava they must DISAGREE — the control walks
     * in, and an arm whose control did not walk in has not earned the right to report that the
     * subject stayed out. On stone they must AGREE, both reaching the bay floor, because there the
     * guard's correct answer is silence.
     *
     * <h2>{@code lethalEdgeBrake} is OFF in both arms, and that is the isolation</h2>
     *
     * Not a convenience: with it on, {@link Walker#footingGuard} pins this body as its sole thins and
     * neither arm ever reaches the bay, so the scene would be measuring the guard that was already
     * working. Live it was released by the plan's own descent — the row above quotes the node. Off
     * here, the guard that had no release is the only thing left, which is the situation rung 14
     * actually died in.
     *
     * <h2>The drive is the rig's, not the pathfinder's</h2>
     *
     * The body is walked forward at walking speed on a heading the rig re-imposes every tick, and the
     * walker is ticked only so that its guards run — they live in {@code Walker#tick}'s single-exit
     * wrapper, after every branch of {@code tickInner}. A goal is set because a null one NPEs in the
     * stall detector, and it is overridden immediately; nothing here is a claim about A*. Sneak
     * travels on a different channel from the impulse, so a guard's pin survives the rig's drive and
     * is what the body is actually stopped by.
     *
     * <h2>Arena footprint</h2>
     *
     * {@code dx ∈ [-6, 6]}, {@code dz ∈ [-3, 17]}, {@code dy ∈ [4, 36]} around the origin — inside
     * the default one-chunk window ({@code dx, dz ∈ [-16, 31]}), so no {@code withChunkRadius}.
     */
    private static void stopsAtALavaShore(SceneContext ctx) { shoreArm(ctx, true); }

    /** The lava arm's negative control — see {@link #stopsAtALavaShore}. The same bay filled with
     *  stone, where the guard must stay silent: an eleven-block drop onto rock is a graze this body
     *  walks off, and pinning at every such lip is what「killing momentum on every ledge would make
     *  ridge walking crawl」means in the guard's own note. */
    private static void walksOffASurvivableLedge(SceneContext ctx) { shoreArm(ctx, false); }

    /** dy of the shelf's top block — the cell the body's sole rests on. Its foot cell is one above. */
    private static final int SHELF = 30;

    /** Cells of shelf along +z. Eight, the same run {@code wd.serverStopsAtTheBridgeHead} walks. */
    private static final int SHELF_CELLS = 8;

    /** dy of the bay's surface: ten open rows under the shelf's top block, so the fall from the foot
     *  cell is eleven — under {@code SurvivalMath.survivableFall(20) = 22}, deliberately. A bay deep
     *  enough to be lethal dry would let a fix pass here for the wrong reason. */
    private static final int BAY_TOP = SHELF - 10;

    /** Rows of fill under that surface. Four, so a body that goes in is IN it rather than standing on
     *  the bed through a film of it. */
    private static final int BAY_ROWS = 4;

    /** dy of the bed's top block — the first solid cell the guard's downward scan can find, and the
     *  cell the whole defect turns on. */
    private static final int BAY_BED = BAY_TOP - BAY_ROWS;

    /** Synchronous physics ticks one drive of the shelf gets: the walk out is ~50 and the fall ~25. */
    private static final int SHORE_TICKS = 240;

    /** What one drive of the shelf produced. */
    private record Shore(int ticks, double walked, double minY, boolean inLava, int pinnedTicks,
                         String endedAt) {}

    private static void shoreArm(SceneContext ctx, boolean lava) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = false;      // the subject is the PIN; paving the bay is another answer
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = true;
        BotConfig.lethalEdgeBrake = false; // see the class note: this is the isolation, not a shortcut
        ctx.cleanup(() -> clearShore(ctx));

        stageShore(ctx, lava);
        ctx.record("rig", "3 格宽石台 dz=-2..7，顶面 dy=" + SHELF + "；越过台缘是一个 "
                + (SHELF + 1 - BAY_TOP) + " 格深的湾（湾面 dy=" + BAY_TOP + "，" + BAY_ROWS + " 层"
                + (lava ? "岩浆" : "石头") + "，湾底实心 dy=" + BAY_BED
                + "）。干着落是活得下来的（满血 22 格），所以这座场地里唯一致命的东西是湾里装了什么");
        ctx.record("scan", scanRow(ctx, lava));

        Shore control = drive(ctx, "control", false);
        ctx.record("control.after", (control.inLava() ? 1 : 0) + " fault(s): " + control.endedAt());
        if (lava && !control.inLava())
            ctx.fail("THE RIG, not the subject: 关掉 walkerStrideFloorGuard 之后身体也没走进岩浆，"
                    + "那么「主体没进岩浆」这条判据就分不清「守卫拦住了」和「这座场地根本走不进去」 —— "
                    + control.endedAt());
        if (!lava && control.inLava())
            ctx.fail("THE RIG, not the subject: 石头湾里出现了岩浆 —— 两条臂只差这一个变量，"
                    + "而这一臂的布景没放对：" + control.endedAt());

        stageShore(ctx, lava);
        Shore subject = drive(ctx, "subject", true);
        ctx.record("subject.after", (subject.inLava() ? 1 : 0) + " fault(s): " + subject.endedAt());

        if (lava) {
            ctx.check(subject.inLava()).as("A 开着守卫，身体一次都不许碰到岩浆（对照臂："
                    + control.endedAt() + "）").isFalse();
            ctx.check(subject.walked() >= 4.0).as("B 而且必须真的沿台面走过 4 格 —— 只有 A 的话，"
                    + "「一步都不迈」就是满分答案，而那样的守卫会让整条真梯寸步难行：走了 "
                    + String.format(Locale.ROOT, "%.2f", subject.walked()) + " 格").isTrue();
            ctx.check(subject.pinnedTicks() >= 1).as("C 而且要是守卫按住的，不是别的东西碰巧停住的："
                    + subject.pinnedTicks() + " 个 tick 处于潜行钉住状态").isTrue();
        } else {
            ctx.check(subject.minY() < ctx.rel(0, SHELF, 0).getY())
                    .as("A 干湾上守卫必须让开：关着守卫落到 "
                            + String.format(Locale.ROOT, "%.2f", control.minY()) + "，开着必须也落下去，"
                            + "实测 " + String.format(Locale.ROOT, "%.2f", subject.minY())
                            + "（台面 y=" + ctx.rel(0, SHELF + 1, 0).getY() + "）").isTrue();
            ctx.check(subject.pinnedTicks()).as("B 一次都不许钉：在活得下来的落差上钉住身体，"
                    + "等于把每一道台缘都变成一堵墙 —— " + subject.endedAt()).isEqualTo(0);
        }
    }

    /**
     * Re-derive the guard's own downward scan off the LEVEL, cell by cell.
     *
     * <p>The guard's decision and the row that judges it must not be able to disagree about what the
     * column holds, and「the guard declined」has two causes that want opposite fixes: it never
     * reached the scan, or it ran the scan and the scan said safe. This prints the two indices, so a
     * reader can see the bed sitting inside the loop's reach without opening the source.
     */
    private static String scanRow(SceneContext ctx, boolean lava) {
        ServerLevel level = ctx.level();
        BlockPos stride = ctx.rel(0, SHELF + 1, SHELF_CELLS);
        int depth = Math.max(BotConfig.pathfinderMaxDryFall + 1, 23);   // ceil(20 HP) + 3
        int hazardAt = -1, floorAt = -1;
        for (int i = 1; i <= depth && floorAt < 0; i++) {
            BlockPos c = stride.below(i);
            if (hazardAt < 0 && level.getBlockState(c).getFluidState().is(FluidTags.LAVA)) hazardAt = i;
            if (level.getBlockState(c).blocksMotion()) floorAt = i;
        }
        return "从 stride 格 " + stride.toShortString() + " 往下扫，最多 " + depth
                + " 格（max(maxDryFall+1, ceil(满血 20)+3)）："
                + (hazardAt < 0 ? "整列没有危险物" : "第 " + hazardAt + " 格是岩浆")
                + "，" + (floorAt < 0 ? "扫到底也没有实心格" : "第 " + floorAt + " 格是实心的")
                + "。修好之前这个循环只认第二个数字，于是"
                + (lava ? "它在岩浆下面找到了「地板」并放行" : "它照样在石头上找到地板并放行 —— 这一臂里那是对的");
    }

    /**
     * Walk the shelf once and report what stopped the body, if anything.
     *
     * <p>{@code strideGuard} is the arm's only variable. The walker is ticked for its guards alone —
     * they run in {@code Walker#tick}'s single-exit wrapper after every branch of {@code tickInner} —
     * and the heading, the impulse and the jump are re-imposed after that call so the body walks one
     * straight line whatever the walker would rather do. Sneak is NOT re-imposed: it is the channel a
     * guard pins on, and it is the thing being measured.
     */
    private static Shore drive(SceneContext ctx, String arm, boolean strideGuard) {
        ServerLevel level = ctx.level();
        BotConfig.walkerStrideFloorGuard = strideGuard;

        double startZ = ctx.origin().getZ() + 1.5;
        int standY = ctx.rel(0, SHELF + 1, 0).getY();
        ServerPlayerAvatar av = SceneBody.avatar(ctx, level,
                ctx.origin().getX() + 0.5, standY, startZ);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        fp.getInventory().clearContent();
        aim(fp);
        for (int i = 0; i < SETTLE_TICKS; i++) av.step();
        if (fp.getY() < standY - 0.5)
            ctx.fail("THE RIG, not the subject: vanilla 自己就没端住这个站位（" + SETTLE_TICKS
                    + " 个空 tick 之后 y=" + fp.getY() + "）");

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(ctx.rel(0, SHELF + 1, SHELF_CELLS - 1)));
        double minY = fp.getY(), farZ = fp.getZ();
        boolean inLava = false;
        int pinned = 0, t = 0;
        for (; t < SHORE_TICKS; t++) {
            walker.tick(av, w);
            aim(fp);
            av.commandMove(0f, 1f);
            av.commandJump(false);
            if (av.dbgSneak()) pinned++;
            av.step();
            minY = Math.min(minY, fp.getY());
            farZ = Math.max(farZ, fp.getZ());
            if (fp.isInLava()) { inLava = true; break; }
            if (fp.onGround() && fp.getY() < ctx.rel(0, SHELF, 0).getY()) break;   // landed in the bay
        }
        String ended = String.format(Locale.ROOT,
                "%d tick，身体=(%.2f,%.2f,%.2f)，最低 y=%.2f，沿台面走了 %.2f 格，钉住 %d tick，脚下=%s%s",
                t, fp.getX(), fp.getY(), fp.getZ(), minY, farZ - startZ, pinned,
                blockUnder(level, fp), inLava ? "，泡在岩浆里" : "");
        ctx.record(arm + ".drive", "walkerStrideFloorGuard=" + strideGuard + " → " + ended);
        return new Shore(t, farZ - startZ, minY, inLava, pinned, ended);
    }

    private static String blockUnder(ServerLevel level, ServerPlayer fp) {
        BlockPos below = fp.blockPosition().below();
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(below).getBlock()).getPath();
    }

    /** Face +z, head and body with it. Vanilla rotates the movement impulse by the yaw, so a heading
     *  the walker is free to slew would turn this rig's straight walk into whatever the walker
     *  wanted, and the arm would be measuring A* instead of a guard. */
    private static void aim(ServerPlayer fp) {
        fp.setYRot(0f);
        fp.yHeadRot = 0f;
        fp.yBodyRot = 0f;
    }

    /** Air out the working box. Called before each staging and once more on cleanup, so an arm that
     *  fails mid-drive still hands the shared dogfood world back empty — including its lava. */
    private static void clearShore(SceneContext ctx) {
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -3; dz <= 17; dz++)
                for (int dy = 4; dy <= 36; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    /**
     * The headland, the basin, and what the basin is filled with — the last of those being the two
     * arms' only difference.
     *
     * <p>The basin's rim is laid solid all the way round BEFORE the fill goes in, so lava cannot flow
     * out of it and the「floor」the guard's scan finds is real rock rather than the edge of the
     * staging. Same ordering, and the same reason, as the blaze room's walls-before-the-lid.
     */
    private static void stageShore(SceneContext ctx, boolean lava) {
        clearShore(ctx);
        for (int dx = -3; dx <= 3; dx++)                     // the headland the body walks out on
            for (int dz = -2; dz <= 7; dz++)
                for (int dy = 4; dy <= SHELF; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        for (int dx = -5; dx <= 5; dx++)                     // the basin: rim and bed, one piece
            for (int dz = 8; dz <= 16; dz++)
                for (int dy = 4; dy <= BAY_TOP; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        for (int dx = -3; dx <= 3; dx++)                     // …and the fill. THE variable.
            for (int dz = 8; dz <= 14; dz++)
                for (int dy = BAY_BED + 1; dy <= BAY_TOP; dy++)
                    ctx.setBlock(dx, dy, dz, lava ? Blocks.LAVA : Blocks.STONE);
    }

    // ── the lava RIM pair ────────────────────────────────────────────────────────────────────
    //
    // Everything below measures the OTHER half of the same guard's job. stopsAtALavaShore asks
    // whether it refuses a stride INTO a lake; these ask what its refusal costs a body that was
    // only ever walking PAST one.

    /** dy of the rim shelf's top block. The body's foot cell is one above it. */
    private static final int RIM_DECK = 20;

    /** dy of the trench fill's surface. Four rows under the deck, so a dry body that goes over the
     *  edge falls four and loses one heart (damage is blocks − 3). The only lethal thing in this
     *  arena is what the trench is filled with, exactly as in the shore pair. */
    private static final int RIM_FILL_TOP = RIM_DECK - 4;

    /** Rows of fill. Four, so a body that goes in is IN it rather than standing on the bed through a
     *  film of it — and so the bed sits at index 9 of the guard's downward scan, comfortably inside
     *  its 23-cell reach. That last part is the whole pre-fix behaviour: the scan walked through the
     *  lava and found this bed. */
    private static final int RIM_FILL_ROWS = 4;

    /** dy of the trench bed's top block — the「floor」the scan used to stop on. */
    private static final int RIM_BED = RIM_FILL_TOP - RIM_FILL_ROWS;

    /** Westmost shelf cell. dx below this is open trench, so the rim runs down the whole arena at a
     *  constant x and the body can walk beside it for as long as its budget lasts. */
    private static final int RIM_EDGE = -1;

    /** Heading in degrees; 0 is +z. 25° leans the walk toward -x, i.e. slightly into the trench.
     *
     *  <p>Not decoration and not a way to force a fall: it is the reading the ladder actually
     *  logged. Every one of rung 12's 83 pins on 2026-08-19 was a body travelling along the shore
     *  with {@code vel (-0.12, -0.00)} — a lateral drift toward the lake while the route it was
     *  following ran along the rim to a goal ON the rim. A pure +z walk keeps the stride cell on the
     *  deck forever and would measure nothing at all. */
    private static final float RIM_YAW = 25f;

    /** Synchronous physics ticks one drive of the rim gets. Long enough that the post-pin travel
     *  clause has room to be answered either way: ~40 ticks pass before the first fire and the
     *  sneak-limited walk that follows covers about a cell every 25. */
    private static final int RIM_TICKS = 200;

    /** What one drive of the rim produced. {@code zAfterPin} is the +z distance covered after the
     *  FIRST pinned tick — the number the whole pair exists to produce, and the one a total stop
     *  drives to zero while the plain「walked」figure stays healthy on the pre-pin run-up alone. */
    private record Rim(int ticks, double walked, double zAfterPin, double minY, boolean inLava,
                       int pinnedTicks, int longestPin, String endedAt) {}

    /**
     * <b>A body walking ALONG a lava rim, not into it.</b>
     *
     * <h2>The reading this is a copy of</h2>
     *
     * The stride guard learned to break its fall scan on {@code isHazard} on 2026-08-19, and the very
     * next zero-staging ladder run logged it firing 83 times inside one rung — every fire on two
     * cells of one lake's rim ({@code -10,64,16} and {@code -9,64,17}), none anywhere else in the
     * run:
     *
     * <pre>{@code
     * [walker] stride floor-guard: bottomless stride -10,64,16 (vel -0.12, -0.00) -> sneak-pin
     * [walker] stride floor-guard: bottomless stride -10,64,16 (vel -0.08, -0.00) -> sneak-pin
     * [walker] stride floor-guard: bottomless stride -10,64,16 (vel -0.06, -0.00) -> sneak-pin
     * [walker] stride floor-guard: bottomless stride -10,64,16 (vel -0.05, -0.00) -> sneak-pin
     * [walker] stride floor-guard: bottomless stride -10,64,16 (vel -0.04, -0.00) -> sneak-pin
     * }</pre>
     *
     * <p>The word {@code bottomless} in that line is a hardcoded literal, not a classification — the
     * decaying velocity is what says these were NOT bottomless columns, because the bottomless branch
     * zeroes the horizontal momentum on its first fire and there would be no second line. They were
     * lava columns one row under the stride cell, seen for the first time by the new break.
     *
     * <h2>The question, which the shore pair does not ask</h2>
     *
     * {@code wd.serverStopsAtALavaShore} drives a body AT a lake and requires the guard to stop it.
     * There, stopping is the whole answer. Here the body is walking PAST a lake to somewhere else,
     * and「stopped」is the failure: a guard that pins wherever an open column ends in lava turns
     * every rim into a wall, and a Nether crossing is nothing but rim. So this arm asks for three
     * things at once and the third is the one that had to be earned separately:
     *
     * <ol>
     *   <li><b>It must not go in.</b> The protection is not negotiable — a body that walks into lava
     *       dies and ends the run.</li>
     *   <li><b>The pin must be what held it.</b> Without this, an arena whose edge the body never
     *       reaches scores full marks for the guard.</li>
     *   <li><b>It must keep going along the rim afterwards.</b> Measured from the first pinned tick,
     *       not from the start: the run-up to the rim is four or five cells of ordinary walking and
     *       would carry a plain「walked N cells」clause on its own while the body stood frozen for
     *       the rest of the drive.</li>
     * </ol>
     *
     * <h2>Each arm carries its own control</h2>
     *
     * Both arms drive the rim TWICE over identical staging with {@code walkerStrideFloorGuard} as the
     * only difference. On lava the two must DISAGREE — the control walks in at 36 ticks with
     * {@code 脚下=lava}, and an arm whose control did not walk in has not earned the right to report
     * that the subject stayed out. On stone they must AGREE, both dropping into the trench, because
     * there the guard's correct answer is silence.
     *
     * <h2>What clause 3 costs to break, measured rather than assumed</h2>
     *
     * Clause 3's failure is a property of the guard, not of the arena, so the control arm cannot
     * produce it and it was attacked directly instead: the bottomless branch's
     * {@code setDeltaMovement(0, dy, 0)} was made unconditional — one line, the strongest stop the
     * guard is able to express — and the gate re-run. <b>It did not go red.</b> Post-pin travel fell
     * from 11.08 cells to 7.53, still nearly four times the bar, and clauses 1 and 2 were untouched.
     *
     * <p>That is a result and not a shrug: zeroing the horizontal momentum stops the body for one
     * tick and the drive re-accelerates it on the next, and vanilla's {@code maybeBackOffFromEdge}
     * refuses only the component of a move that would leave the floor. <b>A pin at a rim is a
     * refusal of the sideways step, not of the journey</b> — which is exactly the question the
     * ladder's caveat asked, answered with a number. Clause 3 is kept as the floor under that
     * finding: it is the row that would have gone red if a rim pin could immobilise this body, and
     * it is the row a future brake-on-fire has to get past.
     *
     * <h2>{@code lethalEdgeBrake} is OFF, for the shore pair's reason</h2>
     *
     * With it on, {@link Walker#footingGuard} pins this body the moment its sole thins at the rim and
     * the arm would be measuring the guard that was already working. Off, the sneak channel carries
     * exactly one writer and {@code pinnedTicks} means what it says.
     *
     * <h2>Arena footprint</h2>
     *
     * {@code dx ∈ [-7, 6]}, {@code dz ∈ [-3, 21]}, {@code dy ∈ [4, 28]} — inside the default
     * one-chunk window ({@code dx, dz ∈ [-16, 31]}), so no {@code withChunkRadius}.
     */
    private static void keepsWalkingAtALavaRim(SceneContext ctx) { rimArm(ctx, true); }

    /** The lava arm's negative control — see {@link #keepsWalkingAtALavaRim}. The same trench filled
     *  with stone, where the guard must stay silent and let the body drift over the edge and drop the
     *  four blocks: a fix that pinned at every lip would pass the lava arm and fail this one, which
     *  is the only reason a body is ever allowed to walk off anything. */
    private static void keepsWalkingAtADryRim(SceneContext ctx) { rimArm(ctx, false); }

    private static void rimArm(SceneContext ctx, boolean lava) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = false;      // the subject is the PIN; paving the trench is another answer
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = true;
        BotConfig.lethalEdgeBrake = false; // see the class note: this is the isolation, not a shortcut
        ctx.cleanup(() -> clearRim(ctx));

        stageRim(ctx, lava);
        ctx.record("rig", "石台面 dy=" + RIM_DECK + "（落脚排 dy=" + (RIM_DECK + 1)
                + "），西边 dx≤" + (RIM_EDGE - 1) + " 是一条沿 z 通到底的沟，沟里装 " + RIM_FILL_ROWS
                + " 层" + (lava ? "岩浆" : "石头") + "（面 dy=" + RIM_FILL_TOP + "，底 dy=" + RIM_BED
                + "）。掉进去是 " + (RIM_DECK - RIM_FILL_TOP)
                + " 格落差（落脚排 → 沟面上那一排），干着落满血只掉一颗心 —— "
                + "所以这座场地里唯一致命的东西还是沟里装了什么");
        ctx.record("scan", rimScanRow(ctx, lava));
        ctx.record("heading", "偏航 " + RIM_YAW + "°（0 是 +z）：沿 +z 走，同时带一点朝沟的横移 —— "
                + "真梯 2026-08-19 那 83 次点火，每一次的速度都是「沿岸走、横着往湖里飘」");

        Rim control = rimDrive(ctx, "control", false);
        ctx.record("control.after", (control.inLava() ? 1 : 0) + " fault(s): " + control.endedAt());
        if (lava && !control.inLava())
            ctx.fail("THE RIG, not the subject: 关掉 walkerStrideFloorGuard 之后身体也没走进岩浆，"
                    + "那么「主体没进岩浆」这条判据就分不清「守卫拦住了」和「这座场地根本走不进去」 —— "
                    + control.endedAt());
        if (!lava && control.inLava())
            ctx.fail("THE RIG, not the subject: 石头沟里出现了岩浆 —— 两条臂只差这一个变量，"
                    + "而这一臂的布景没放对：" + control.endedAt());

        stageRim(ctx, lava);
        Rim subject = rimDrive(ctx, "subject", true);
        ctx.record("subject.after", (subject.inLava() ? 1 : 0) + " fault(s): " + subject.endedAt());
        // Unconditional, both arms. The escape hatch meant to convert a sustained pin into a fresh
        // route is `guardPinStreak >= 30`, and whether it is ever reached is a property of the
        // approach rather than of the guard: a body pressing steadily at a rim pins every tick and
        // sails past 30, while the ladder's rung-12 rim produced BURSTS OF FIVE — the fires stop as
        // soon as the pin decelerates the body under the guard's own h ≥ 0.03, and the 8-tick hold
        // tail then expires and resets the streak. So「the crossing will route around」holds in one
        // of those shapes and not the other, and this row is what tells a reader which shape the
        // measurement came from instead of leaving them to re-derive the hysteresis.
        // ⚠️ 「够得着」不再蕴含「被丢掉重找过」。`Walker.forcedRepathIfPinnedTooLong` 自 d8e4e650
        // 起在钉满 30 tick 时先问身体这一段有没有前进：换过计划的重定基、还在推进的保留，只有真冻住
        // 的才丢。所以这一行只报它测得到的量（钉了多久、够不够门槛），把「那一次到底丢没丢」交给
        // JourneyFlight 的 guardForcedRepaths／guardKeptPlans 那一对——它们是并排报的，因为单独一个
        // 0 分不清「从没钉满」和「钉满了但计划被保住」。
        ctx.record("streak", "最长一次连续钉住 " + subject.longestPin()
                + " tick，强制重找路的门槛是 guardPinStreak ≥ 30 —— 这一趟"
                + (subject.longestPin() >= 30
                        ? "够得着（是否真的丢弃还要看身体那一段有没有前进，本场景不测这一步）"
                        : "够不着，所以从头到尾没改过道，只是一轮一轮地钉、松、再钉"));

        if (lava) {
            ctx.check(subject.inLava()).as("A 开着守卫，身体一次都不许碰到岩浆（对照臂："
                    + control.endedAt() + "）").isFalse();
            ctx.check(subject.pinnedTicks() >= 1).as("B 而且要是守卫按住的，不是身体压根没走到沟边："
                    + subject.pinnedTicks() + " 个 tick 处于潜行钉住状态").isTrue();
            ctx.check(subject.zAfterPin() >= 2.0).as("C 而且钉住之后必须还能沿着坑沿继续走 —— "
                    + "这一条量的是「第一次被钉住之后」，因为走到沟边那四五格普通行走本身就够满足一条"
                    + "「走过几格」的判据，而那样的守卫会把每一道岩浆沿都变成一堵墙：钉住之后又走了 "
                    + String.format(Locale.ROOT, "%.2f", subject.zAfterPin()) + " 格").isTrue();
        } else {
            ctx.check(subject.minY() < ctx.rel(0, RIM_DECK, 0).getY())
                    .as("A 干沟上守卫必须让开：关着守卫落到 "
                            + String.format(Locale.ROOT, "%.2f", control.minY()) + "，开着必须也落下去，"
                            + "实测 " + String.format(Locale.ROOT, "%.2f", subject.minY())
                            + "（台面 y=" + ctx.rel(0, RIM_DECK + 1, 0).getY() + "）").isTrue();
            ctx.check(subject.pinnedTicks()).as("B 一次都不许钉：在活得下来的落差上钉住身体，"
                    + "等于把每一道台缘都变成一堵墙 —— " + subject.endedAt()).isEqualTo(0);
        }
    }

    /**
     * Re-derive the guard's own downward scan off the LEVEL for the first cell out over the trench.
     *
     * <p>Same purpose as {@link #scanRow}: the guard's decision and the row that judges it must not
     * be able to disagree about what the column holds. Here it also prints the two indices side by
     * side, which is the whole of the 2026-08-19 fix in two numbers — the hazard comes first and the
     * bed is still inside the loop's reach, so a scan that only looks for a floor finds one.
     */
    private static String rimScanRow(SceneContext ctx, boolean lava) {
        ServerLevel level = ctx.level();
        BlockPos stride = ctx.rel(RIM_EDGE - 1, RIM_DECK + 1, 6);
        int depth = Math.max(BotConfig.pathfinderMaxDryFall + 1, 23);   // ceil(20 HP) + 3
        int hazardAt = -1, floorAt = -1;
        for (int i = 1; i <= depth && floorAt < 0; i++) {
            BlockPos c = stride.below(i);
            if (hazardAt < 0 && level.getBlockState(c).getFluidState().is(FluidTags.LAVA)) hazardAt = i;
            if (level.getBlockState(c).blocksMotion()) floorAt = i;
        }
        return "从沟上第一格 " + stride.toShortString() + " 往下扫，最多 " + depth
                + " 格（max(maxDryFall+1, ceil(满血 20)+3)）："
                + (hazardAt < 0 ? "整列没有危险物" : "第 " + hazardAt + " 格是岩浆")
                + "，" + (floorAt < 0 ? "扫到底也没有实心格" : "第 " + floorAt + " 格是实心的")
                + "。只认第二个数字的那版" + (lava
                        ? "会在岩浆下面找到「地板」并放行 —— 这一臂就是那条缺陷的现场；认第一个数字的"
                          + "这版会点火，而点火之后身体还走不走得动，才是这一臂真正在问的"
                        : "照样在石头上找到地板并放行 —— 这一臂里那是对的，守卫必须一声不吭");
    }

    /**
     * Walk the rim once and report what the guard cost.
     *
     * <p>Same rig as the shore pair's {@link #drive}: the heading and the impulse are re-imposed
     * after {@code walker.tick} so the body walks one straight diagonal whatever the walker would
     * rather do, and sneak is NOT re-imposed because sneak is the channel a guard pins on and the
     * thing being measured. The goal is level and well up the deck — nothing here is a claim about
     * A*, and a goal BELOW the foot would hand the guard its planned-descent exemption and measure
     * that instead.
     */
    private static Rim rimDrive(SceneContext ctx, String arm, boolean strideGuard) {
        ServerLevel level = ctx.level();
        BotConfig.walkerStrideFloorGuard = strideGuard;

        double startX = ctx.origin().getX() + 1.5, startZ = ctx.origin().getZ() + 0.5;
        int standY = ctx.rel(0, RIM_DECK + 1, 0).getY();
        ServerPlayerAvatar av = SceneBody.avatar(ctx, level, startX, standY, startZ);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        fp.getInventory().clearContent();
        aimRim(fp);
        for (int i = 0; i < SETTLE_TICKS; i++) av.step();
        if (fp.getY() < standY - 0.5)
            ctx.fail("THE RIG, not the subject: vanilla 自己就没端住这个站位（" + SETTLE_TICKS
                    + " 个空 tick 之后 y=" + fp.getY() + "）");

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(ctx.rel(0, RIM_DECK + 1, 18)));
        double minY = fp.getY(), farZ = fp.getZ(), pinZ = Double.NaN, farAfterPin = 0.0;
        boolean inLava = false;
        int pinned = 0, run = 0, longest = 0, t = 0;
        for (; t < RIM_TICKS; t++) {
            walker.tick(av, w);
            aimRim(fp);
            av.commandMove(0f, 1f);
            av.commandJump(false);
            if (av.dbgSneak()) {
                pinned++;
                longest = Math.max(longest, ++run);
                if (Double.isNaN(pinZ)) pinZ = fp.getZ();
            } else run = 0;
            av.step();
            minY = Math.min(minY, fp.getY());
            farZ = Math.max(farZ, fp.getZ());
            if (!Double.isNaN(pinZ)) farAfterPin = Math.max(farAfterPin, fp.getZ() - pinZ);
            if (fp.isInLava()) { inLava = true; break; }
            if (fp.getZ() > ctx.origin().getZ() + 17) break;      // ran out of staged deck
        }
        String ended = String.format(Locale.ROOT,
                "%d tick，身体=(%.2f,%.2f,%.2f)，最低 y=%.2f，沿岸走了 %.2f 格，"
                + "第一次被钉住之后又走了 %.2f 格，钉住 %d tick（最长连续 %d），脚下=%s%s",
                t, fp.getX(), fp.getY(), fp.getZ(), minY, farZ - startZ, farAfterPin, pinned,
                longest, blockUnder(level, fp), inLava ? "，泡在岩浆里" : "");
        ctx.record(arm + ".drive", "walkerStrideFloorGuard=" + strideGuard + " → " + ended);
        return new Rim(t, farZ - startZ, farAfterPin, minY, inLava, pinned, longest, ended);
    }

    /** Face {@link #RIM_YAW}, head and body with it — see {@link #aim} for why the heading has to be
     *  re-imposed every tick rather than left to the walker. */
    private static void aimRim(ServerPlayer fp) {
        fp.setYRot(RIM_YAW);
        fp.yHeadRot = RIM_YAW;
        fp.yBodyRot = RIM_YAW;
    }

    /** Air out the working box, on cleanup as well as before each staging, so an arm that fails
     *  mid-drive still hands the shared dogfood world back without its lava. */
    private static void clearRim(SceneContext ctx) {
        for (int dx = -7; dx <= 6; dx++)
            for (int dz = -3; dz <= 21; dz++)
                for (int dy = 4; dy <= 28; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    /**
     * One solid block, a trench cut out of it, and the fill put back — in that order.
     *
     * <p>The trench's walls and bed therefore exist BEFORE any lava does, so the lava cannot flow out
     * of the arena and the「floor」the guard's scan finds under it is real rock rather than the edge
     * of the staging. Same ordering, and the same reason, as {@link #stageShore}.
     */
    private static void stageRim(SceneContext ctx, boolean lava) {
        clearRim(ctx);
        for (int dx = -7; dx <= 6; dx++)                     // one solid mass, walls and bed included
            for (int dz = -3; dz <= 21; dz++)
                for (int dy = 4; dy <= RIM_DECK; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        for (int dx = -6; dx <= -2; dx++)                    // the trench, cut back out of it
            for (int dz = -2; dz <= 20; dz++)
                for (int dy = RIM_BED + 1; dy <= RIM_DECK; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
        for (int dx = -6; dx <= -2; dx++)                    // …and the fill. THE variable.
            for (int dz = -2; dz <= 20; dz++)
                for (int dy = RIM_BED + 1; dy <= RIM_FILL_TOP; dy++)
                    ctx.setBlock(dx, dy, dz, lava ? Blocks.LAVA : Blocks.STONE);
    }
}

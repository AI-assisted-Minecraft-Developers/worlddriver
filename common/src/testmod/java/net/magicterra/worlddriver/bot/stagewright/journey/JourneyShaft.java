package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.TowerProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/**
 * Getting a body down a shaft it digs and back up the one it dug.
 *
 * <p>Split out of the rung file because it is used by five rungs and belongs to none of them: the
 * stone, iron, portal-kit, obsidian and portal rungs all sink a shaft to something they can only
 * reach by digging, and all of them then have to leave. Both directions are spelled out block by
 * block rather than handed to the walker, and each one is spelled out because the searched version
 * was measured failing — see the two method notes for the numbers.
 *
 * <p>State is static and there is one field of it ({@code exitFromY}/{@code exitRise}), which is
 * safe for the same reason the rest of this package's state is: a journey is one body, one run, one
 * scene at a time.
 */
public final class JourneyShaft {

    private JourneyShaft() {}

    /**
     * Climb back out of the shaft this rung dug.
     *
     * <p>The counterpart nobody needed until mining became honest. Before the reach gate the bot
     * never dug a shaft, so it never had to leave one; now a mining rung ends standing at y=60 in a
     * one-wide hole, and the NEXT rung inherits that. Measured: the food rung asked for a cow 67
     * blocks away and spent its whole 8 000-tick budget "走向猎物" from the bottom of a pit.
     *
     * <p>Placing is on, because pillaring is how a player leaves a shaft and the run is carrying
     * the cobblestone it just mined. Best-effort by design — a rung that reached its goal should
     * not be failed for an awkward exit, and the next rung's own guard will say so if it matters.
     *
     * <p><b>Scripted, not searched.</b> The first version handed the exit to the walker as
     * {@code Goal.YLevel(surfaceY)} and sized its budget off {@code wd.serverPillarsOutOfAPit},
     * which leaves a four-deep arena pit in 46 ticks. In the field that bought <b>one block in
     * 6 000 ticks</b> — {@code exit.fromY=54 → exit.toY=55} — and the food rung then spent its
     * entire budget re-searching a route out of the hole from {@code 71,55,74}. A nine-deep shaft
     * cut sideways into a stone face is not the arena's clean column, and asking a search to
     * rediscover the way up is exactly the shape of plan this suite promises not to need. So the
     * ascent is spelled out the same way {@link #descendByMining} spells out the descent.
     */
    /** Where the last climb started and how far it meant to go, so {@link #recordExit} can report
     *  the fraction it actually covered rather than only the height it stopped at. */
    static int exitFromY, exitRise;

    /** The column the current climb started on. A tower that wanders is not a tower: see
     *  {@link #ascendByTowering}'s drift branch. */
    static int climbColX, climbColZ;

    static void climbOut(JourneyRig rig, int surfaceY, Runnable then) {
        BotConfig.allowPlace = true;
        int rise = Math.max(0, surfaceY - rig.player().blockPosition().getY());
        int cap = climbCoursesFor(rise);
        exitFromY = rig.player().blockPosition().getY();
        exitRise = rise;
        climbColX = rig.player().blockPosition().getX();
        climbColZ = rig.player().blockPosition().getZ();
        rig.evidence("exit.fromY", rig.player().blockPosition().getY());
        rig.evidence("exit.rise", rise + " block(s), cap " + cap + " course(s)");
        ascendByTowering(rig, surfaceY, cap, cap, () -> {
            if (rig.player().blockPosition().getY() >= surfaceY) { recordExit(rig, then); return; }
            // The tower gave up. Hand the rest to the walker — the route
            // wd.serverPillarsOutOfAPit measured at 46 ticks — and RECORD that it was needed, so
            // a run whose exit depended on the fallback cannot be read as one where the scripted
            // ascent worked. Two ways up is belt-and-braces; hiding which one carried the body is
            // how a capability quietly stops being tested.
            rig.evidence("exit.walkerFallback", true);
            rig.settle(new IntentProcess(new Intent(new Goal.YLevel(surfaceY))), 3_000,
                    () -> recordExit(rig, then));
        });
    }

    static void recordExit(JourneyRig rig, Runnable then) {
        rig.evidence("exit.toY", rig.player().blockPosition().getY());
        // How much of the climb actually happened, as a fraction rather than as a landing height.
        // `exit.toY=28` beside `exit.fromY=27` is only a shortfall if you remember the rise was 36,
        // and a rung that later finds what it needs underground will otherwise go green carrying a
        // capability failure nobody reads. This is the number to grep across runs.
        rig.evidence("exit.gained", (rig.player().blockPosition().getY() - exitFromY)
                + "/" + exitRise + " block(s)");
        rig.evidence("exit.cobblestone", rig.carrying("minecraft:cobblestone"));
        // What the climb would spend NEXT, which is the reading that says whether an exit stopped
        // for want of blocks. Cobblestone alone answered that while every shaft ended above y=0.
        rig.evidence("exit.pillarStock", pillarBlock(rig) + " ×" + rig.carrying(pillarBlock(rig)));
        then.run();
    }

    /** How many courses a scripted exit gets, at least. One course is at most two legs (mine,
     *  tower), and the deepest shaft the ladder dug when this was written was the iron rung's —
     *  sized with room to spare, because the cost of being wrong here is a rung that reads as a
     *  mining failure. It stopped being enough the moment a rung dug to the seed's lava. */
    static final int MAX_CLIMB_STEPS = 40;

    /** The course cap for a climb of a known height. Two per block: a course that has to break
     *  its own ceiling first spends one leg mining and one towering, and a body still falling
     *  after the mine spends another settling before it may jump. */
    static int climbCoursesFor(int rise) {
        return Math.max(MAX_CLIMB_STEPS, rise * 2 + 20);
    }

    /**
     * Rise one course: clear whatever is overhead, then pillar into the space.
     *
     * <p>The mirror of {@link #descendByMining}, and recursive for the same reason — a course is
     * two await legs and the body has to actually move between them.
     *
     * <p>{@link net.magicterra.worlddriver.bot.process.TowerProcess} cannot break, so a body that
     * mined sideways and is standing under its own ceiling would jump into rock forever and report
     * "stuck (no Y gain)". Clearing {@code feet+2} first is what makes the tower legal: that is the
     * cell the head moves into once the feet rise one.
     *
     * <p>Best-effort, but not silently: a course that gains nothing with a clear ceiling stops the
     * climb and records the builder's own reason, because forty identical no-op legs report a
     * missing capability where "no placeable block in the hotbar" is the actual answer.
     */
    /**
     * How many courses a climb may lose to moving water before it gives up.
     *
     * <p>Flowing water PUSHES entities, and a body on top of a one-block pillar is the easiest thing
     * in the game to push off one. Measured on the portal rung, whose alcove is flooded by the very
     * bucket the cast needs: {@code climb.1.stalled=done (placed=1, feetY=53)} — the tower placed its
     * block and the body did reach y=53 — beside {@code climb.1.state=onGround=false inWater=true
     * y=51.63}. It rose two blocks and was washed back down, and the climb then stopped for good on
     * that single lost course while forty-two of its forty-four remained.
     *
     * <p>This is not "a retry that changes nothing": the water is flowing, so each attempt starts
     * from a different current, and two courses is all it takes to get above the flood. Losing a
     * course on DRY land still ends the climb immediately — there the state does not change, and
     * forty identical no-op legs is the failure this cap was written to prevent.
     */
    static final int WASHED_OFF_RETRIES = 8;

    static void ascendByTowering(JourneyRig rig, int surfaceY, int budget, int cap, Runnable then) {
        ascendByTowering(rig, surfaceY, budget, cap, WASHED_OFF_RETRIES, then);
    }

    static void ascendByTowering(JourneyRig rig, int surfaceY, int budget, int cap, int washedOff,
                                 Runnable then) {
        BlockPos at = rig.player().blockPosition();
        if (at.getY() >= surfaceY || budget <= 0) { then.run(); return; }
        int step = cap - budget;
        ServerLevel lvl = lvlOf(rig);
        // Back onto the column before building another course.
        //
        // A tower that wanders is not a tower, and the wandering is not cosmetic. Measured on the
        // portal rung: the climb started at -9,51,21 and by its third course was at -9,54,23 —
        // it had drifted two cells into the FRAME'S OWN PLANE and then rose straight up through it,
        // mining the mould's cells out and filling the hole with cobblestone. The rung's ten casts
        // were being poured into a frame the exit had just eaten. `TowerProcess` places under the
        // body and jumps; where the body lands after that is not pinned to anything, so a course
        // that ends a cell over is normal and only the next course makes it permanent.
        if (at.getX() != climbColX || at.getZ() != climbColZ) {
            rig.evidence("climb." + step + ".drift", at.toShortString() + " 偏离起塔柱 "
                    + climbColX + "," + climbColZ + "，先走回去再垒");
            rig.settle(new IntentProcess(new Intent(new Goal.Block(
                    new BlockPos(climbColX, at.getY(), climbColZ)))), 120, () -> {
                BlockPos back = rig.player().blockPosition();
                // One attempt, then adopt. A correction that cannot be made must not become the
                // whole climb — forty courses of walking back to a cell the body cannot reach is
                // the same wedge in a different costume, and the climb still has to happen.
                if (back.getX() != climbColX || back.getZ() != climbColZ) {
                    rig.evidence("climb." + step + ".driftKept", back.toShortString()
                            + " 走不回 " + climbColX + "," + climbColZ + "，改以这一柱为准");
                    climbColX = back.getX();
                    climbColZ = back.getZ();
                }
                ascendByTowering(rig, surfaceY, budget - 1, cap, washedOff, then);
            });
            return;
        }
        BlockPos ceiling = at.above(2);
        rig.evidence("climb." + step, String.format("%d,%d,%d above=%s onGround=%s water=%s",
                at.getX(), at.getY(), at.getZ(), lvl.getBlockState(ceiling).getBlock(),
                rig.player().onGround(), rig.player().isInWater()));
        // blocksMotion, not !isAir: swamp groundwater is not air and mining it is a no-op, so an
        // air test would spend the whole budget breaking water that was never in the way.
        if (lvl.getBlockState(ceiling).blocksMotion()) {
            rig.mineBlock(ceiling, 2_000, () -> ascendByTowering(rig, surfaceY, budget - 1, cap, washedOff, then));
            return;
        }
        // Land before jumping. TowerProcess's READY phase waits for onGround and its stuck counter
        // runs from tick zero, so a body still settling after the mine that preceded it burns its
        // whole 60-tick patience falling and reports "stuck (no Y gain — out of blocks?)" while
        // holding thirty cobblestone. HoldStill is the same non-steering settle the descent uses.
        if (!rig.player().onGround()) {
            rig.settle(new HoldStill(40), 60, () -> ascendByTowering(rig, surfaceY, budget - 1, cap, washedOff, then));
            return;
        }
        String pillar = pillarBlock(rig);
        rig.evidence("climb." + step + ".with", pillar + " ×" + rig.carrying(pillar));
        // Put the block in the HAND before the tower asks for it, because the tower can only look in
        // the hotbar. `Avatar.holdPlaceable` scans slots 0..8 and gives up; `Avatar.holdItem` scans
        // all 36 and swaps one up. So a body four rungs deep — whose hotbar is pickaxes, a bucket,
        // flint, food — reports "no placeable block in hotbar" while carrying 110 cobblestone, which
        // is what the obsidian rung's exit did: 36 blocks of rise, one block gained. Doing it from
        // the script rather than widening holdPlaceable is deliberate; a caller who knows what it
        // wants to pillar with can say so, and the asymmetry is logged as an engine finding instead.
        var pillarItem = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(pillar));
        // Recorded only when it fails: a course that got what it asked for is already described by
        // `.with`, and thirty-six successful hand-swaps would bury the one that did not.
        if (!rig.body().avatar().holdItem(pillarItem)) {
            rig.evidence("climb." + step + ".hand", "拿不到 " + pillar + "，手上是 "
                    + BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem()));
        }
        // Land before judging, and that is a bug fix rather than politeness: a jump is not a gain.
        // The body is a block higher for the few ticks it is in the air, so a check taken at the end
        // of the tower's own leg reads a REFUSED PLACE as a successful course. Measured on the stone
        // rung: forty courses of 55 → 56 → 55, the cobblestone count never moving off 30, and the
        // stall branch below — the one whose whole job is to say why — never firing once, because
        // every course "gained" a block it did not keep.
        rig.settle(new TowerProcess(at.getY() + 1, pillar), 200, () -> rig.settle(new HoldStill(20), 40, () -> {
            if (rig.player().blockPosition().getY() > at.getY()) {
                ascendByTowering(rig, surfaceY, budget - 1, cap, washedOff, then);
                return;
            }
            // "stuck (no Y gain)" has two very different causes and the message cannot tell them
            // apart: the tower never JUMPED (its READY phase requires onGround, and a body floating
            // in the groundwater that seeped into its own shaft never is), or it jumped and the
            // place was rejected. The state at the moment it gave up is what separates them —
            // measured once already as `climb.0.stalled` with a clear ceiling and zero blocks spent.
            rig.evidence("climb." + step + ".stalled",
                    String.valueOf(rig.body().botState().builder.lastError));
            rig.evidence("climb." + step + ".state", String.format("onGround=%s inWater=%s y=%.2f",
                    rig.player().onGround(), rig.player().isInWater(), rig.player().getY()));
            // What it was holding when it gave up. "Out of blocks?" is the builder's guess and it is
            // usually wrong here — the stone rung stalled forty times holding thirty cobblestone.
            rig.evidence("climb." + step + ".stock", pillar + " ×" + rig.carrying(pillar));
            // Washed off, not stuck. In moving water the state at the end of a course is not the
            // state the next one starts from, so this is the one case where asking again is a real
            // retry — see WASHED_OFF_RETRIES for the measurement. Recorded every time, so a climb
            // that only got up because the water let go cannot read as one the tower simply made.
            if (rig.player().isInWater() && washedOff > 0) {
                rig.evidence("climb." + step + ".washedOff",
                        "水把身体冲下柱子了，还剩 " + (washedOff - 1) + " 次重试");
                rig.settle(new HoldStill(20), 40, () -> ascendByTowering(rig, surfaceY, budget - 1,
                        cap, washedOff - 1, then));
                return;
            }
            then.run();
        }));
    }

    /**
     * What to pillar with: whichever of the shaft's own spoil the body is actually carrying.
     *
     * <p>It was {@code minecraft:cobblestone}, hard-coded, and that was right for exactly as long as
     * every shaft in the ladder stopped above y=0. Below that the spoil is cobbled deepslate, and a
     * tower asked for a block the body does not hold reports <b>"stuck (no Y gain — out of blocks?)"</b>
     * while the inventory is full — a message that names the wrong problem so convincingly that the
     * first reading is always "the builder is broken".
     *
     * <p>Re-read every course rather than once, because a deep climb crosses the boundary: the
     * deepslate runs out around y=0 and the stone the shaft cut above it takes over.
     */
    static String pillarBlock(JourneyRig rig) {
        String best = "minecraft:cobblestone";
        int most = 0;
        for (String id : PILLAR_BLOCKS) {
            int n = rig.carrying(id);
            if (n > most) { most = n; best = id; }
        }
        return best;
    }

    /** Everything a shaft yields that a tower can stand on, commonest first. */
    static final List<String> PILLAR_BLOCKS = List.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt",
            "minecraft:tuff", "minecraft:andesite", "minecraft:diorite", "minecraft:granite");

    /** How many ATTEMPTS a scripted shaft gets, at least. Not blocks: a block costs two or three
     *  passes, because the body needs settle ticks to actually fall in after the floor is gone.
     *  Twelve was sized as blocks and bought exactly one block of descent before giving up; thirty
     *  covered a nine-deep shaft with nothing to spare, and the second iron vein is eleven deep. */
    static final int MAX_SHAFT_BLOCKS = 60;

    /**
     * Attempts per block of depth, which is what makes a fixed cap into a scaled one.
     *
     * <p>A constant was fine while every shaft in the ladder was nine or eleven deep. The obsidian
     * rung digs to whatever depth the seed's lava sits at, and a cap that does not know how far it
     * is going reports "the block broke but the body did not sink" for a shaft that was simply
     * longer than the number somebody typed. That failure names a driver bug and means a budget, and
     * telling those apart afterwards costs a whole run.
     *
     * <p>Three, because the settle-and-retry path costs an attempt of its own whenever the body has
     * not dropped in yet, and a shaft that hits gravel or water spends several.
     */
    static final int SHAFT_ATTEMPTS_PER_BLOCK = 3;

    /** The attempt cap for a descent of a known depth — see {@link #SHAFT_ATTEMPTS_PER_BLOCK}. */
    static int shaftAttemptsFor(int depth) {
        return Math.max(MAX_SHAFT_BLOCKS, depth * SHAFT_ATTEMPTS_PER_BLOCK + 20);
    }

    /**
     * Dig the block under the body, let it fall in, repeat until its feet reach {@code targetY}.
     *
     * <p>Recursive rather than looped because each block is its own {@code await} leg — the body
     * has to actually fall between them, and a loop inside one scene tick would break twelve blocks
     * in a world that never advanced and leave the body standing on air.
     *
     * <p>The step cap is not belt-and-braces. A mine that finishes without the body descending —
     * the block broke but something is holding it up — would otherwise recurse forever registering
     * new await steps, which reads as a hung suite rather than as the failure it is.
     */
    static void descendByMining(JourneyRig rig, int targetY, Runnable then) {
        int depth = Math.max(0, rig.player().blockPosition().getY() - targetY);
        int cap = shaftAttemptsFor(depth);
        rig.evidence("shaft.depth", depth + " block(s), cap " + cap + " attempt(s)");
        descendByMining(rig, targetY, cap, cap, then);
    }


    /**
     * The still-solid cell under the body's footprint — the one actually holding it up.
     *
     * <p>Prefers the centre cell so an ordinary shaft stays a straight one-wide hole, and falls
     * back to whichever corner of the bounding box is still standing. Returns the centre cell when
     * nothing under the footprint holds weight, so the caller's "already open" branch handles it.
     *
     * <p>{@code blocksMotion}, not {@code !isAir}. Water is not air and it is not a floor either,
     * and the difference cost a whole run: a shaft broke its centre cell, groundwater filled the
     * hole, and from then on this method answered "the support is the water" for twenty-eight
     * consecutive passes — mining a fluid is a no-op, so the digger reported "the block broke but
     * the body did not sink" while the corner cell actually carrying the body was never touched.
     */
    static BlockPos supportUnder(JourneyRig rig, BlockPos at) {
        ServerLevel lvl = rig.ctx().level();
        BlockPos centre = at.below();
        if (lvl.getBlockState(centre).blocksMotion()) return centre;
        var box = rig.player().getBoundingBox();
        int y = centre.getY();
        for (int x : new int[]{net.minecraft.util.Mth.floor(box.minX), net.minecraft.util.Mth.floor(box.maxX)})
            for (int z : new int[]{net.minecraft.util.Mth.floor(box.minZ), net.minecraft.util.Mth.floor(box.maxZ)}) {
                BlockPos corner = new BlockPos(x, y, z);
                if (lvl.getBlockState(corner).blocksMotion()) return corner;
            }
        return centre;
    }

    static ServerLevel lvlOf(JourneyRig rig) { return rig.ctx().level(); }

    static void descendByMining(JourneyRig rig, int targetY, int budget, int cap, Runnable then) {
        BlockPos at = rig.player().blockPosition();
        if (at.getY() <= targetY) { then.run(); return; }
        if (budget <= 0) {
            rig.ctx().fail("竖井挖不下去：目标 y=" + targetY + "，试了 " + cap
                    + " 次仍停在 " + at + "（方块破了但身体没下沉）");
            return;
        }
        // The block under the body's CENTRE is not necessarily the block holding it up. A player
        // box is 0.6 wide, so a body standing near a cell edge is supported by TWO cells, and
        // breaking only the centre one leaves it resting on the neighbour: measured, the shaft
        // broke cleanly and then read `below=air` at an unchanged y for three passes in a row.
        // That is the whole of this rung's run-to-run flakiness — same code, same coordinates, and
        // it descends or does not depending on where in the cell the walk happened to stop.
        BlockPos below = supportUnder(rig, at);
        // Per-step evidence, because the first version of this failed and could not say why: the
        // body sat at the same y for twelve legs and "the block broke but nothing fell" and "the
        // block was never solid to begin with" read identically from the outside.
        int step = cap - budget;
        // WHICH cell is holding the body up, not just what it is made of. `supportUnder` falls back
        // to a corner of the bounding box, so "below=stone" can name a different cell every pass —
        // and without its coordinates fifty identical lines read as one block that will not break
        // rather than as a body shuffling between two of them. Measured: 55 passes of
        // `-9,52,21 below=stone` → `broke=air` with the body never sinking, and nothing in the run
        // said where "below" was.
        rig.evidence("shaft." + step,
                String.format("%d,%d,%d below=%s %s onGround=%s", at.getX(), at.getY(), at.getZ(),
                        below.toShortString(), rig.ctx().level().getBlockState(below).getBlock(),
                        rig.player().onGround()));
        // Already open — the previous pass broke it and the body has not dropped in yet. Mining
        // air is a no-op that still costs an attempt, and three of those in a row is how a shaft
        // with budget for four blocks ran out after one. Fluid counts as open for the same reason
        // it does not count as support: there is nothing here left to break.
        if (!lvlOf(rig).getBlockState(below).blocksMotion()) {
            // …unless it is fluid and the body is IN it, which is not "about to fall" — it is
            // floating, and no number of settles fixes floating. Measured: the obsidian rung picked
            // a column under a swamp pond and spent all 122 of its attempts here, then reported
            // "the block broke but the body did not sink" about a body that was swimming. A shaft
            // that cannot start says so in one line instead of after seven thousand ticks.
            if (!lvlOf(rig).getFluidState(below).isEmpty() && rig.player().isInWater()) {
                rig.ctx().fail("竖井挖不动：身体浮在" + lvlOf(rig).getBlockState(below).getBlock()
                        + "里（" + at + "，脚下是流体不是地板）——这根柱子不干燥，换一根");
                return;
            }
            rig.settle(new HoldStill(40), 60,
                    () -> descendByMining(rig, targetY, budget - 1, cap, then));
            return;
        }
        rig.mineBlock(below, 2_000, () -> {
                // The reading that splits the two failures apart. "The body did not sink" is either
                // "the block is still there" (the mine did not break it) or "the block is gone and
                // the body stayed up" (the walker will not step into its own hole), and from the
                // outside those are the same sentence.
                rig.evidence("shaft." + step + ".broke",
                        String.format("%s body=%s", rig.ctx().level().getBlockState(below).getBlock(),
                                rig.player().blockPosition().toShortString()));
                // Breaking the floor is not falling through it. This body has no free-running
                // physics: it is stepped only while a driver is ticking it, and the single-block
                // mine ends on the tick the block turns to air — one `avatar.step()` per leg, which
                // is a tenth of a block of gravity. So the descent needs a leg that keeps ticking
                // until the body has settled.
                //
                // The goal is the CELL just emptied, not a height. Goal.YLevel(targetY) was tried
                // and it descends — to the wrong place: "be at y=60" is satisfied anywhere, and the
                // walker took the shortest way down it could find, landing at 77,83 with the ore
                // still under 83,75. A shaft is a column, and only a goal that names the column
                // keeps the body over its own hole.
                rig.settle(new HoldStill(40), 60,
                        () -> descendByMining(rig, targetY, budget - 1, cap, then));
        });
    }
}

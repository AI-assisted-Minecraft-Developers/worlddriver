package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.NavWalk;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * The WaterBank family: water physics on the server body and the bank climb-outs that place a way out — a buoyant sheer
 * wall, a low-bank foothold, an open-river sheer bank. Swimming across open water and digging up a bank are timed by
 * the benchmark's swimming and dig-ashore courses.
 *
 * <p>Each test builds its arena in a centred plot with the old arena's numbers ({@code cx, cz} the plot's middle,
 * {@code floorY} its middle height) and walks an {@code mc.bot.goto} one whole bot tick at a time (perceive, reflexes,
 * task, body) inside one server task, so no world tick falls between the bot's own.
 *
 * <p>Dropped: the two vine probes (optional diagnostics, one a standing known failure).
 */
@Scene("wd.scenes")
class WaterBankTest {

    private static final String POS = " pos=(";

    private static final String STEP = " step=";

    private static final String MAX_X = ") maxX=";

    private static final String MAX_Y = ") maxY=";

    private static final String COMMA = ",";

    private static final String ALLOW_BREAK = "allowBreak";

    private static final String ALLOW_PLACE = "allowPlace";

    private static final String NAVIGATOR_DEBUG = "navigatorDebug";

    private static final String STEP_TEXT = "step=";

    private static final String WALK = "walk";

    private static final String WATER_PILLAR_ENGAGES = "waterPillarEngages";

    /** 5x5 stone-walled tank, 3x3 water core {@code depth} tall, air above. */
    private static void buildWaterColumn(ServerLevel level, int cx, int cz, int floorY, int depth) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int dy = 1; dy <= depth; dy++) {
                    level.setBlockAndUpdate(
                            new BlockPos(cx + dx, floorY + dy, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
                }
                for (int dy = depth + 1; dy <= depth + 4; dy++) {
                    level.setBlockAndUpdate(
                            new BlockPos(cx + dx, floorY + dy, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Gives the bot its water effects and exactly these stacks, with the first hotbar slot selected. */
    private static void stockWaterBot(ServerPlayer fp, ItemStack... stacks) {
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        for (ItemStack stack : stacks) {
            fp.getInventory().add(stack);
        }
        fp.getInventory().selected = 0;
    }

    /** Water-physics probe 1: how far a submerged avatar with no input moves in 20 ticks. */
    private static double measureSinkDy(SceneBot av) {
        ServerPlayer fp = av.player();
        SimProbes.grantWaterEffects(fp);
        for (int i = 0; i < 2; i++) {
            av.press(av.keys().move(0, 0));
            av.step();
        } // warm up water state
        if (!fp.isInWater()) {
            fail("waterPhysicsParity: avatar not in water after baseTick (water state not wired)");
        }
        double y0 = fp.getY();
        for (int i = 0; i < 20; i++) {
            av.press(av.keys().move(0, 0));
            av.step();
        }
        return fp.getY() - y0;
    }

    /** Water-physics probe 2: how far a submerged avatar holding jump rises in 25 ticks. */
    private static double measureRiseDy(SceneBot av2) {
        ServerPlayer fp2 = av2.player();
        SimProbes.grantWaterEffects(fp2);
        for (int i = 0; i < 2; i++) {
            av2.press(av2.keys().move(0, 0));
            av2.step();
        }
        double jy0 = fp2.getY();
        for (int i = 0; i < 25; i++) {
            av2.press(av2.keys().jump(true).move(0, 0));
            av2.step();
        }
        return fp2.getY() - jy0;
    }

    /** Water-physics probe 3: how far a submerged avatar pressing forward swims in 20 ticks. */
    private static double measureSwimDz(SceneBot av3) {
        ServerPlayer fp3 = av3.player();
        SimProbes.grantWaterEffects(fp3);
        for (int i = 0; i < 2; i++) {
            av3.press(av3.keys().move(0, 0));
            av3.step();
        }
        double z0 = fp3.getZ();
        for (int i = 0; i < 20; i++) {
            fp3.setYRot(0f);
            av3.press(av3.keys().move(0, 1));
            av3.step();
        }
        return fp3.getZ() - z0;
    }

    /** Builds the basin, containing walls, water, sheer climb wall and dry plateau of {@link #buoyantWall}. */
    private static void buildBuoyantWallArena(
            ServerLevel level, int cx, int cz, int floorY, int surface, int plateauTop) {
        // Basin floor.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
        // Containing walls (−z, ±x) up to surface+1 to hold the water.
        for (int dz = -3; dz <= 2; dz++) {
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
            }
        }
        // Water fill (interior).
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 1; dz++) {
                for (int y = floorY + 1; y <= surface; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
                }
            }
        }
        // The +5 sheer climb wall at cz+2, floor → plateau top.
        for (int dx = -3; dx <= 3; dx++) {
            for (int y = floorY + 1; y <= plateauTop; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.STONE.defaultBlockState());
            }
        }
        // Dry plateau behind the wall.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = 3; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, plateauTop, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
    }

    /** Writes the three pillar-takeover rows of {@link #buoyantWall}, reading the counters since the start values. */
    private static void putPillarRows(
            Map<String, String> rows, BotDebugContracts.NavigatorReading nav, long engages0, long bails0) {
        // Recorded on every run, PASS included: a guard that cannot fire changes no colour, and this is
        // the only place a reader can see whether the pillar branch was even entered.
        long engages = nav.counter(WATER_PILLAR_ENGAGES) - engages0;
        long bails = nav.counter("waterPillarCeilingBails") - bails0;
        long topRise = nav.counter("waterPillarTopRise");
        rows.put(
                "pillar.engages",
                engages == 0
                        ? "0 — this run never took the pillar takeover path, so it measured nothing about that path"
                        : String.valueOf(engages));
        rows.put(
                "pillar.ceilingBails",
                engages == 0
                        ? "unavailable/the takeover never happened; 0 does not mean the guard did not fire, it"
                                + " means the guard was never consulted"
                        : bails + " times past the ceiling at +" + 5 + " above the feet at engage");
        rows.put(
                "pillar.topRise",
                engages == 0
                        ? "unavailable/same as above"
                        : topRise + " blocks (" + (5 - topRise) + " blocks short of the ceiling)");
    }

    /** Builds the basin, containment, river and split south bank of {@link #riverSheerBank}. */
    private static void buildRiverArena(
            ServerLevel level, int cx, int cz, int floorY, int surface, int wallTop, int riverLen, int lowBankX) {
        // Basin floor under everything (river + south land).
        for (int dx = -3; dx <= riverLen + 3; dx++) {
            for (int dz = -4; dz <= 20; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
        // Containment: north wall (cz-4), west (cx-3) and east (cx+riverLen+1) caps.
        for (int dx = -3; dx <= riverLen + 3; dx++) {
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 4), Blocks.STONE.defaultBlockState());
            }
        }
        for (int dz = -4; dz <= 1; dz++) {
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + riverLen + 1, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
        // River water: z cz-3..cz+1, x cx-2..cx+riverLen, depth blocks deep.
        for (int dx = -2; dx <= riverLen; dx++) {
            for (int dz = -3; dz <= 1; dz++) {
                for (int y = floorY + 1; y <= surface; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
                }
            }
        }
        // South bank line at cz+2: sheer +5 wall for the west run, LOW (+0) bank east.
        for (int dx = -2; dx <= riverLen; dx++) {
            int top = dx < lowBankX ? wallTop : surface;
            for (int y = floorY + 1; y <= top; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.STONE.defaultBlockState());
            }
        }
        // South land behind the bank line (cz+3..cz+20): tall plateau behind the sheer section, low ground
        // behind the low bank — joined by a cliff (so the ONLY water exit is the low bank).
        for (int dx = -2; dx <= riverLen; dx++) {
            for (int dz = 3; dz <= 20; dz++) {
                int top = dx < lowBankX ? wallTop : surface;
                for (int y = floorY + 1; y <= top; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
        }
    }

    /**
     * Water-physics parity for the server-side player body: a player driven by manual body steps in a deep water column
     * must reproduce vanilla fluid movement: (1) submerged no-input SINKS SLOWLY (not free-fall), (2) holding jump BOBS
     * UP, (3) forward swims (slow).
     */
    @Test
    void waterPhysicsParity(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 40, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int depth = 14;
        game.runOnServer(s -> buildWaterColumn(s.getLevel(plot.dimension()), cx, cz, floorY, depth));

        Settings.pin(game);

        // (1) Submerged, no input → slow sink (NOT free-fall to the floor).
        // Each tick presses its keys again, as a player's input does: a held jump lapses on any step
        // taken out of water, so pressing it once is not the same input.
        SceneBot av = SceneBot.spawn(game, plot.dimension(), cx + 0.5, floorY + depth - 4, cz + 0.5);
        double sinkDy = game.onServer(s -> measureSinkDy(av));

        // (2) Submerged, hold jump → buoyant rise.
        SceneBot av2 = SceneBot.spawn(game, plot.dimension(), cx + 0.5, floorY + depth - 6, cz + 0.5);
        double riseDy = game.onServer(s -> measureRiseDy(av2));

        // (3) Submerged, forward → swims forward (slow), stays in water.
        SceneBot av3 = SceneBot.spawn(game, plot.dimension(), cx + 0.5, floorY + depth - 5, cz + 0.5);
        double swimDz = game.onServer(s -> measureSwimDz(av3));

        reporter.publishEntry(
                "water",
                "sinkDy=" + sinkDy + " riseDy=" + riseDy + " swimDz=" + swimDz + " surface=" + (floorY + depth));
        if (sinkDy < -1.5 || sinkDy > 0.2) {
            fail("waterPhysicsParity: submerged sink not vanilla-slow: dy=" + sinkDy
                    + " (expected slow water drift, free-fall would be much more negative)");
        }
        if (riseDy < 0.8) {
            fail("waterPhysicsParity: buoyant jump did not lift the avatar: dy=" + riseDy);
        }
        if (swimDz < 0.3 || swimDz > 5.0) {
            fail("waterPhysicsParity: water swim displacement off: dz=" + swimDz);
        }
    }

    /**
     * The REAL navigator must mount a +5 SHEER wall rising from DEEP water (all buoyant — no dry ledge). Break+place
     * ON. Asserts the bot reaches the dry plateau behind the wall and the waterline bob stays bounded (≤120 ticks).
     */
    @Test
    void buoyantWall(Game game, @Plot.Centered @Plot.Size(x = 16, y = 40, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int depth = 6;
        int surface = floorY + depth; // water surface
        int plateauTop = surface + 5; // wall top +5 above water (sheer, buoyant)

        game.runOnServer(s -> buildBuoyantWallArena(s.getLevel(plot.dimension()), cx, cz, floorY, surface, plateauTop));
        BlockPos goal = new BlockPos(cx, plateauTop + 1, cz + 3);

        // Determinism: A* unbounded by wall-clock (one-shot repath, bounded only by node count).
        // Foothold first, as by default: the baseline's dig-first order relied on the server bot breaking the bank in
        // one tick, and a floating player's dig of it takes longer than the whole budget.
        Settings.pinUntimed(game)
                .set(ALLOW_BREAK, true)
                .set(ALLOW_PLACE, true)
                .set(NAVIGATOR_DEBUG, true)
                .set("navigatorFootholdBeforeBankDig", true);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            stockWaterBot(fp, new ItemStack(Items.DIRT, 64));

            // This bot's own counters, so another test climbing out of water cannot move them.
            BotDebugContracts.NavigatorReading nav0 = bot.navigator();
            final long engages0 = nav0.counter(WATER_PILLAR_ENGAGES);
            final long bails0 = nav0.counter("waterPillarCeilingBails");
            bot.goTo(Targets.block(goal));

            double maxY = fp.getY();
            boolean everDry = false;
            int bobTicks = 0; // ticks stuck in-water below the wall top
            boolean walking = true;
            for (int t = 0; t < 800 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                maxY = Math.max(maxY, fp.getY());
                if (fp.onGround() && !fp.isInWater()) {
                    everDry = true;
                }
                if (fp.isInWater() && fp.getY() < plateauTop) {
                    bobTicks++;
                }
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            BotDebugContracts.NavigatorReading nav = bot.navigator();
            boolean onPlateau = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= plateauTop + 1 - 0.4;
            rows.put(
                    WALK,
                    STEP_TEXT + st + POS + fp.getX() + COMMA + fp.getY() + COMMA + fp.getZ() + MAX_Y + maxY
                            + " everDry=" + everDry + " onPlateau=" + onPlateau + " bobTicks=" + bobTicks);
            putPillarRows(rows, nav, engages0, bails0);
            if (!onPlateau) {
                fails.add("buoyantWall: BUOYANT +5 wall: Navigator failed to mount from water: pos=("
                        + fp.getX() + COMMA + fp.getY() + COMMA + fp.getZ() + MAX_Y + maxY
                        + " everDry=" + everDry + STEP + st);
            }
            // Smoothness gate: the over-water diagUp-penalty climbs out with a stable vertical pillar,
            // cutting the waterline bob from ~348 ticks to ~44. Lock that in (>120 = staircase thrash).
            if (bobTicks > 120) {
                fails.add("buoyantWall: buoyant climb bobbed " + bobTicks
                        + " ticks at the waterline (expected ~44; >120 = diagonal-staircase thrash regressed)");
            }
        });
        report(reporter, rows, fails);
    }

    /**
     * Live round69 repro — a floating bot wedged at a LOW (+2) bank with NO pickaxe. Break OFF, place ON: the only way
     * up is to place ONE throwaway foothold block into the top water cell. Also guards {@code isUsableBuildBlock} in a
     * body (falling blocks rejected, mud accepted). Asserts the bot gets OUT onto the bank.
     */
    @Test
    void waterLowBank(Game game, @Plot.Centered @Plot.Size(x = 16, y = 40, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int depth = 6;
        final int surface = floorY + depth; // water surface (floating foot ~surface)
        final int bankTop = surface + 1; // bank rises ONE block above the water top → a +2 climb-out

        // The +2 low bank basin; the goal is on the dry land behind it.
        BlockPos goal = game.onServer(s -> buildLowBankBasin(s.getLevel(plot.dimension()), cx, cz, floorY, surface));

        // Deterministic: each repath completes in one go. The predicate-level guard for the root-cause fix
        // (falling blocks rejected, mud accepted) is BuildBlocksTest, a unit test over the same isUsableBuildBlock.
        Settings.pinUntimed(game)
                .set(ALLOW_BREAK, false) // live: bot had NO pickaxe → cannot carve the bank
                .set(ALLOW_PLACE, true) // foothold-place is the only climb-out
                .set(NAVIGATOR_DEBUG, true);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            // Live round69 inventory: sand+gravel (falling, useless), mud (the only usable support), holding sand.
            // Slot 0 is selected: holding sand (non-usable) like live.
            stockWaterBot(
                    fp, new ItemStack(Items.SAND, 9), new ItemStack(Items.GRAVEL, 37), new ItemStack(Items.MUD, 17));

            bot.goTo(Targets.block(goal));
            double maxY = fp.getY();
            int bobTicks = 0; // ticks floating below the bank top
            boolean walking = true;
            for (int tt = 0; tt < 800 && walking; tt++) {
                bot.tick();
                walking = bot.busy();
                maxY = Math.max(maxY, fp.getY());
                if (fp.isInWater() && fp.getY() < bankTop) {
                    bobTicks++;
                }
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            boolean onBank = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= bankTop + 1 - 0.4;
            rows.put(
                    WALK,
                    STEP_TEXT + st + POS + fp.getX() + COMMA + fp.getY() + COMMA + fp.getZ() + MAX_Y + maxY + " onBank="
                            + onBank + " bobTicks=" + bobTicks);
            if (!onBank) {
                fails.add("waterLowBank: LOW +2 bank: floating Navigator failed to climb out (foothold-place "
                        + "never lifted it): pos=(" + fp.getX() + COMMA + fp.getY() + COMMA + fp.getZ()
                        + MAX_Y + maxY + " bobTicks=" + bobTicks + STEP + st);
            }
        });
        report(reporter, rows, fails);
    }

    /**
     * Live-faithful OPEN-WATER +5 sheer-bank wedge (2026-06-09). The bot floats in an open river whose SOUTH bank is a
     * uniform +5 SHEER wall; the only climb-out is a LOW (+1) bank far EAST, goal diagonally SE. No flanking walls pin
     * the bot onto a pillar — the buoyant drift is free, as on the real river. Break+place ON. Asserts the bot gets
     * ashore.
     *
     * <p><b>The executor gap it guards.</b> A* ALWAYS returns the correct path ({@code goalReached=true}, endpoint the
     * low bank +5 EAST across open water). But at the wall the committed waypoint sat +5 east / +2 south / only +1 UP,
     * and the old {@code cwp.y>foot.y} climb intent fired — so the block-less bank dig trenched the SHEER wall directly
     * beside the bot instead of swimming the last few cells to the flat low walk-out. Root: a laterally-DISTANT higher
     * waypoint misread as a climb-HERE intent.
     *
     * <p><b>Fix = {@code navigatorWaterClimbLateralGate}</b> (default ON, deliberately not in the test baseline — a
     * correctness invariant, not a tunable): the floating climb-out (pillar + block-less dig) engages only when the
     * climb waypoint is horizontally BESIDE the bot ({@code WATER_CLIMB_LATERAL_MAX}=2, Chebyshev); a farther higher
     * waypoint is the routed corridor exit, reached by the swim-drive (climb re-arms once adjacent). K≥6 A/B, both
     * loaders: gate OFF 6/6 wedge (wallPressTicks 51, ashore=false), gate ON 6/6 ashore.
     */
    @Test
    void riverSheerBank(Game game, @Plot.Centered @Plot.Size(x = 64, y = 40, z = 48) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int depth = 6;
        final int surface = floorY + depth; // water surface
        final int wallTop = surface + 5; // sheer south wall top
        final int riverLen = 28; // east run of the channel
        final int lowBankX = riverLen - 4; // dx where the south bank turns low

        game.runOnServer(s ->
                buildRiverArena(s.getLevel(plot.dimension()), cx, cz, floorY, surface, wallTop, riverLen, lowBankX));
        // Goal diagonally SE on the low land (live bearing ≈ 40°SE).
        BlockPos goal = new BlockPos(cx + riverLen - 2, surface + 1, cz + 16);

        Settings.pinUntimed(game).set(ALLOW_BREAK, true).set(ALLOW_PLACE, true).set(NAVIGATOR_DEBUG, true);

        // Adjacent to the bank (cz+2), the way waterLowBank spawns. Three blocks out the takeover's entry gate
        // never fires and the arena measures nothing.
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            stockWaterBot(fp, new ItemStack(Items.DIRT, 64));

            bot.goTo(Targets.block(goal));
            double maxX = fp.getX();
            int wallPressTicks = 0; // in-water ticks spent pressed against the SHEER face
            boolean walking = true;
            for (int t = 0; t < 1500 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                maxX = Math.max(maxX, fp.getX());
                if (fp.isInWater() && fp.getZ() > cz + 1.2 && fp.getX() < cx + lowBankX - 1) {
                    wallPressTicks++;
                }
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            boolean ashore = !fp.isInWater() && fp.onGround() && fp.getZ() > cz + 1.5 && fp.getY() >= surface + 1 - 0.4;
            rows.put(
                    WALK,
                    STEP_TEXT + st + POS + fp.getX() + COMMA + fp.getY() + COMMA + fp.getZ() + MAX_X + maxX
                            + " wallPressTicks=" + wallPressTicks + " ashore=" + ashore);
            if (!ashore) {
                fails.add("riverSheerBank: open-river sheer bank: Navigator never climbed out: pos=("
                        + fp.getX() + COMMA + fp.getY() + COMMA + fp.getZ() + MAX_X + maxX
                        + " wallPressTicks=" + wallPressTicks + STEP + st);
            }
        });
        report(reporter, rows, fails);
    }

    /**
     * The +2 low bank of {@link #waterLowBank}: a 5x4 pool walled in stone on three sides, a dirt bank one above the
     * water on the fourth, grass behind it. Returns the goal on the dry land behind the bank.
     */
    private static BlockPos buildLowBankBasin(ServerLevel level, int cx, int cz, int floorY, int surface) {
        final int bankTop = surface + 1;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
        for (int dz = -3; dz <= 1; dz++) {
            for (int y = floorY + 1; y <= bankTop; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int y = floorY + 1; y <= bankTop; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 1; dz++) {
                for (int y = floorY + 1; y <= surface; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
                }
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int y = floorY + 1; y <= surface; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.DIRT.defaultBlockState());
            }
            level.setBlockAndUpdate(new BlockPos(cx + dx, bankTop, cz + 2), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = 3; dz <= 6; dz++) {
                level.setBlockAndUpdate(
                        new BlockPos(cx + dx, bankTop, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        return new BlockPos(cx, bankTop + 1, cz + 4);
    }

    /** Publish the rows, then fail with every failure the walk collected. */
    private static void report(TestReporter reporter, Map<String, String> rows, List<String> fails) {
        rows.forEach(reporter::publishEntry);
        if (!fails.isEmpty()) {
            fail(String.join("\n", fails));
        }
    }
}

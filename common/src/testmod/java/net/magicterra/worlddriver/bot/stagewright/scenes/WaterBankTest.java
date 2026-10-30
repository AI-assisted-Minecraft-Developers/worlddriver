package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
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
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * The WaterBank family: water physics on the server body and the bank climb-outs — a buoyant sheer wall, a toolless
 * tall-bank dig, a low-bank foothold, an open-river sheer bank, a deep-water cross, a drift entry, a water divider
 * rounded through a side gap, and the place-futility ledger pair.
 *
 * <p>Each test builds its arena in a centred plot with the old arena's numbers ({@code cx, cz} the plot's middle,
 * {@code floorY} its middle height) and walks an {@code mc.bot.goto} one whole bot tick at a time (perceive, reflexes,
 * task, body) inside one server task, so no world tick falls between the bot's own.
 *
 * <p>Dropped: the two vine probes (optional diagnostics, one a standing known failure) and
 * {@code deepWaterClimboutNoBlock}, the plain case of the block-less bank dig that {@code deepWaterClimboutDrift} and
 * {@code tallBankDigClimb} both cover.
 */
@Scene("wd.scenes")
class WaterBankTest {

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
        double sinkDy = game.onServer(s -> {
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
        });

        // (2) Submerged, hold jump → buoyant rise.
        SceneBot av2 = SceneBot.spawn(game, plot.dimension(), cx + 0.5, floorY + depth - 6, cz + 0.5);
        double riseDy = game.onServer(s -> {
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
        });

        // (3) Submerged, forward → swims forward (slow), stays in water.
        SceneBot av3 = SceneBot.spawn(game, plot.dimension(), cx + 0.5, floorY + depth - 5, cz + 0.5);
        double swimDz = game.onServer(s -> {
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
        });

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

        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
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
                    level.setBlockAndUpdate(
                            new BlockPos(cx + dx, plateauTop, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
        });
        BlockPos goal = new BlockPos(cx, plateauTop + 1, cz + 3);

        // Determinism: A* unbounded by wall-clock (one-shot repath, bounded only by node count).
        Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", true)
                .set("navigatorDebug", true);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

            // This bot's own counters, so another test climbing out of water cannot move them.
            BotDebugContracts.NavigatorReading nav0 = bot.navigator();
            final long engages0 = nav0.counter("waterPillarEngages");
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
                    "walk",
                    "step=" + st + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY
                            + " everDry=" + everDry + " onPlateau=" + onPlateau + " bobTicks=" + bobTicks);
            // Recorded on every run, PASS included: a guard that cannot fire changes no colour, and this is
            // the only place a reader can see whether the pillar branch was even entered.
            long engages = nav.counter("waterPillarEngages") - engages0;
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
            if (!onPlateau) {
                fails.add("buoyantWall: BUOYANT +5 wall: Navigator failed to mount from water: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY
                        + " everDry=" + everDry + " step=" + st);
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
     * TOOLLESS tall-bank climb-out (live 2026-06-20 deep-water stone-bank). +3 SOLID hill the bot digs a diagonal
     * staircase up THROUGH; the bot holds only SAND (FallingBlock → no pillar). {@code faithfulBreak} ON models the
     * ~750-tick/block slow stone-mine. Fallback gate: reach the dry plateau within the tick budget.
     */
    @Test
    void tallBankDigClimb(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 40, z = 20) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int depth = 6;
        int surface = floorY + depth; // water surface
        int plateauTop = surface + 3; // solid hill top

        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 8; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            // Back wall behind the hill top (cz+8) so a bot that overshoots can't walk off the far edge.
            for (int dx = -3; dx <= 3; dx++) {
                for (int y = floorY + 1; y <= plateauTop + 2; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 8), Blocks.STONE.defaultBlockState());
                }
            }
            // FULL bathtub containment: x±3 side walls and −z end wall rise to plateauTop+2 over the whole span.
            for (int dz = -3; dz <= 8; dz++) {
                for (int y = floorY + 1; y <= plateauTop + 2; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            for (int dx = -3; dx <= 3; dx++) {
                for (int y = floorY + 1; y <= plateauTop + 2; y++) {
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
            // SOLID stone hill (dz 2..7, full width) the bot digs a staircase up THROUGH, DIRT-capped at the top.
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = 2; dz <= 7; dz++) {
                    for (int y = floorY + 1; y <= plateauTop; y++) {
                        level.setBlockAndUpdate(
                                new BlockPos(cx + dx, y, cz + dz),
                                (y >= plateauTop ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
                    }
                }
            }
        });
        BlockPos goal = new BlockPos(cx, plateauTop + 1, cz + 5);

        Settings.pin(game)
                .set("allowBreak", true)
                .set("allowPlace", true)
                .set("navigatorDebug", true)
                // Bound the A* budget (live-realistic): with faithfulBreak ON the plateau goal stays UNREACHABLE
                // for hundreds of ticks, so a small cap gives up fast on the not-yet-reachable goal, as the live
                // client does.
                .set("pathfinder.sliceMs", 20)
                .set("pathfinder.maxMs", 250)
                .set("faithfulBreak", true); // model the live ~750-tick/block slow stone-mine

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.SAND, 64)); // FallingBlock → holdPlaceable() false → must dig
            fp.getInventory().selected = 0;

            bot.goTo(Targets.block(goal));
            double maxY = fp.getY();
            int t = 0;
            boolean walking = true;
            for (; t < 9000 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                maxY = Math.max(maxY, fp.getY());
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            boolean onPlateau = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= plateauTop + 1 - 0.4;
            rows.put(
                    "walk",
                    "step=" + st + " ticks=" + t + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY="
                            + maxY + " onPlateau=" + onPlateau);
            if (!onPlateau) {
                fails.add(
                        "tallBankDigClimb: TOOLLESS +5 STONE wall (slow-mine): Navigator failed to dig+mount from water: pos=("
                                + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY + " ticks=" + t
                                + " step=" + st);
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

        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            // Basin floor.
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 6; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            // Containing walls (−z, ±x) up to the bank top to hold the water in.
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
            // Water fill (interior columns, floorY+1 .. surface).
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 1; dz++) {
                    for (int y = floorY + 1; y <= surface; y++) {
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
                    }
                }
            }
            // The +2 low bank at cz+2: dirt up to surface, grass cap at bankTop (live geometry).
            for (int dx = -3; dx <= 3; dx++) {
                for (int y = floorY + 1; y <= surface; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.DIRT.defaultBlockState());
                }
                level.setBlockAndUpdate(new BlockPos(cx + dx, bankTop, cz + 2), Blocks.GRASS_BLOCK.defaultBlockState());
            }
            // Dry land behind the bank (the bank top is the walkable surface; stand bankTop+1).
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = 3; dz <= 6; dz++) {
                    level.setBlockAndUpdate(
                            new BlockPos(cx + dx, bankTop, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
        });
        BlockPos goal = new BlockPos(cx, bankTop + 1, cz + 4); // on the dry land behind the +2 bank

        // Deterministic: each repath completes in one go. The predicate-level guard for the root-cause fix
        // (falling blocks rejected, mud accepted) is BuildBlocksTest, a unit test over the same isUsableBuildBlock.
        Settings.pinUntimed(game)
                .set("allowBreak", false) // live: bot had NO pickaxe → cannot carve the bank
                .set("allowPlace", true) // foothold-place is the only climb-out
                .set("navigatorDebug", true);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            // Live round69 inventory: sand+gravel (falling, useless), mud (the only usable support), holding sand.
            fp.getInventory().add(new ItemStack(Items.SAND, 9));
            fp.getInventory().add(new ItemStack(Items.GRAVEL, 37));
            fp.getInventory().add(new ItemStack(Items.MUD, 17));
            fp.getInventory().selected = 0; // holding sand (non-usable) like live

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
                    "walk",
                    "step=" + st + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY
                            + " onBank=" + onBank + " bobTicks=" + bobTicks);
            if (!onBank) {
                fails.add("waterLowBank: LOW +2 bank: floating Navigator failed to climb out (foothold-place "
                        + "never lifted it): pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                        + ") maxY=" + maxY + " bobTicks=" + bobTicks + " step=" + st);
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

        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
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
                    level.setBlockAndUpdate(
                            new BlockPos(cx + riverLen + 1, y, cz + dz), Blocks.STONE.defaultBlockState());
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
        });
        // Goal diagonally SE on the low land (live bearing ≈ 40°SE).
        BlockPos goal = new BlockPos(cx + riverLen - 2, surface + 1, cz + 16);

        Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", true)
                .set("navigatorDebug", true);

        // Adjacent to the bank (cz+2), the way waterLowBank spawns. Three blocks out the takeover's entry gate
        // never fires and the arena measures nothing.
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

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
                    "walk",
                    "step=" + st + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxX=" + maxX
                            + " wallPressTicks=" + wallPressTicks + " ashore=" + ashore);
            if (!ashore) {
                fails.add("riverSheerBank: open-river sheer bank: Navigator never climbed out: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxX=" + maxX
                        + " wallPressTicks=" + wallPressTicks + " step=" + st);
            }
        });
        report(reporter, rows, fails);
    }

    /**
     * Deep-water (8-block) open crossing — a floating bot must swim a long straight channel at the SURFACE and climb
     * out a low far bank, never diving to the riverbed. Break+place ON. The predicate guard for the fall-to-surface fix
     * (a Fall / FallIntoWater onto a submerged bed cell is invalid) is SubmergedLandingTest, a unit test over the same
     * channel as a grid; this is the integration half.
     */
    @Test
    void deepWaterCross(Game game, @Plot.Centered @Plot.Size(x = 48, y = 40, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int depth = 8;
        final int surface = floorY + depth; // water surface (floating foot ~surface)
        final int span = 16; // E-W deep-water crossing length

        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            // Basin floor under the channel + the far land.
            for (int dx = -2; dx <= span + 4; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            // Containing walls (N/S sides + west cap) up past the surface to hold the water.
            for (int dx = -2; dx <= span; dx++) {
                for (int y = floorY + 1; y <= surface + 1; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
                }
            }
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = floorY + 1; y <= surface + 1; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            // Deep water: x cx-1 .. cx+span-1, z cz-2..cz+2, `depth` blocks deep.
            for (int dx = -1; dx < span; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int y = floorY + 1; y <= surface; y++) {
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
                    }
                }
            }
            // Far EAST low bank: solid to the surface (+1 climb-out), grass cap, dry land carrying the goal.
            for (int dx = span; dx <= span + 4; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int y = floorY + 1; y < surface; y++) {
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                    }
                    level.setBlockAndUpdate(
                            new BlockPos(cx + dx, surface, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
        });
        BlockPos goal = new BlockPos(cx + span + 2, surface + 1, cz); // dry land beyond the far bank

        Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", true)
                .set("navigatorDebug", true);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;

            bot.goTo(Targets.block(goal));
            double maxX = fp.getX();
            boolean walking = true;
            for (int t = 0; t < 2000 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                maxX = Math.max(maxX, fp.getX());
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            boolean ashore =
                    !fp.isInWater() && fp.onGround() && fp.getX() >= cx + span - 0.5 && fp.getY() >= surface + 1 - 0.4;
            rows.put(
                    "walk",
                    "step=" + st + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxX=" + maxX
                            + " ashore=" + ashore);
            if (!ashore) {
                fails.add("deepWaterCross: floating Navigator failed to cross the deep channel"
                        + " and climb out: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                        + ") maxX=" + maxX + " step=" + st);
            }
        });
        report(reporter, rows, fails);
    }

    /**
     * DRIFT-ENTRY climb-out (live z1973). The bot arrives at a WIDE +2 bank moving DIAGONALLY (lateral momentum) with
     * the goal offset along the face, plus an initial lateral shove. The drift-stable latched bank-dig makes it a clean
     * two-step climb (~74t). Bound at 120 catches a pogo regression.
     */
    @Test
    void deepWaterClimboutDrift(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 40, z = 20) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int depth = 8;
        final int surface = floorY + depth; // water surface
        final int span = 3; // deep-water run up to the bank
        final int bankTop = surface + 1; // +2 bank (one above the surface)
        final int zLo = -2;
        final int zHi = 8; // WIDE in Z so the bot can drift along the face

        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            // Basin floor.
            for (int dx = -2; dx <= span + 4; dx++) {
                for (int dz = zLo - 1; dz <= zHi + 1; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            // N/S walls + west cap hold the water in.
            for (int dx = -2; dx <= span; dx++) {
                for (int y = floorY + 1; y <= surface + 1; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + zLo - 1), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + zHi + 1), Blocks.STONE.defaultBlockState());
                }
            }
            for (int dz = zLo - 1; dz <= zHi + 1; dz++) {
                for (int y = floorY + 1; y <= surface + 1; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
                }
            }
            // Deep water column.
            for (int dx = -1; dx < span; dx++) {
                for (int dz = zLo; dz <= zHi; dz++) {
                    for (int y = floorY + 1; y <= surface; y++) {
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
                    }
                }
            }
            // Far bank: DIRT up to bankTop (+2 climb-out), dry land beyond, wide in Z.
            for (int dx = span; dx <= span + 4; dx++) {
                for (int dz = zLo; dz <= zHi; dz++) {
                    for (int y = floorY + 1; y <= bankTop; y++) {
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.DIRT.defaultBlockState());
                    }
                }
            }
        });
        BlockPos goal = new BlockPos(cx + span + 2, bankTop + 1, cz + 6); // OFFSET along the face → diagonal

        Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", true)
                .set("allowSwimEscapeBreak", true)
                .set("navigatorDebug", false);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.SAND, 64)); // FallingBlock → holdPlaceable() false
            fp.getInventory().selected = 0;
            fp.setDeltaMovement(0, 0, 0.45); // lateral shove → drift along the face

            bot.goTo(Targets.block(goal));
            int ashoreTick = -1;
            boolean walking = true;
            for (int t = 0; t < 400 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                if (ashoreTick < 0
                        && !fp.isInWater()
                        && fp.onGround()
                        && fp.getY() >= bankTop + 1 - 0.4
                        && fp.getX() >= cx + span - 0.5) {
                    ashoreTick = t;
                    break;
                }
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            rows.put(
                    "walk",
                    "step=" + st + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") ashoreTick="
                            + ashoreTick + " (clean baseline ~22)");
            if (ashoreTick < 0 || ashoreTick > 120) {
                fails.add("deepWaterClimboutDrift: drifting bot failed a smooth +2 bank climb"
                        + " (ashoreTick=" + ashoreTick + ", want 0..120): pos=(" + fp.getX() + "," + fp.getY()
                        + "," + fp.getZ() + ") step=" + st);
            }
        });
        report(reporter, rows, fails);
    }

    /**
     * Water-divider detour smoke — a buoyant bot shoved straight at a solid DIVIDER between it and the goal must round
     * it through a side GAP. Break/place OFF → the ONLY way through is to round it. Regression smoke test for the
     * in-water heading/aim path the anti-spin freeze + far-aim override sit on.
     */
    @Test
    void waterFarAimBankCorner(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 40, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int depth = 5;
        final int surface = floorY + depth; // water surface
        final int top = surface + 3; // wall crest (unclimbable for a buoyant bot)
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            // Floor.
            for (int x = cx - 2; x <= cx + 12; x++) {
                for (int z = cz - 5; z <= cz + 5; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, floorY, z), Blocks.STONE.defaultBlockState());
                }
            }
            // Pool: water floorY+1..surface, air above; perimeter ring solid.
            for (int x = cx - 2; x <= cx + 12; x++) {
                for (int z = cz - 5; z <= cz + 5; z++) {
                    boolean ring = x == cx - 2 || x == cx + 12 || z == cz - 5 || z == cz + 5;
                    for (int y = floorY + 1; y <= top; y++) {
                        BlockState bs = ring
                                ? Blocks.STONE.defaultBlockState()
                                : (y <= surface ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(x, y, z), bs);
                    }
                }
            }
            // DIVIDER wall at x=cx+6 spanning z=cz-4..cz+2 up to the crest; GAP at z=cz+3..cz+4.
            for (int z = cz - 4; z <= cz + 2; z++) {
                for (int y = floorY + 1; y <= top; y++) {
                    level.setBlockAndUpdate(new BlockPos(cx + 6, y, z), Blocks.STONE.defaultBlockState());
                }
            }
        });
        GotoContracts.Target goal = Targets.column(cx + 11, cz, 0); // east of the wall, SAME Z as the bot

        Settings.pinUntimed(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                .set("allowSwimEscapeBreak", false)
                .set("allowSwimEscapePlace", false)
                .set("navigatorDebug", false);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface, cz + 0.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.setDeltaMovement(0.45, 0, 0); // shove +X straight into the divider

            bot.goTo(goal);
            int reachedTick = -1;
            boolean walking = true;
            for (int t = 0; t < 500 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                if (Math.floor(fp.getX()) == cx + 11 && Math.floor(fp.getZ()) == cz) {
                    reachedTick = t;
                    break;
                }
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            double dGoal = Math.hypot(fp.getX() - (cx + 11 + 0.5), fp.getZ() - (cz + 0.5));
            rows.put(
                    "walk",
                    "step=" + st + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") reachedTick="
                            + reachedTick + " dGoal=" + String.format(Locale.ROOT, "%.1f", dGoal));
            // Round the wall via the gap → reach the east goal column.
            if (reachedTick < 0 && walking) {
                fails.add("waterFarAimBankCorner: bot failed to round the divider to the goal"
                        + " (far-aim rammed the wall through-LOS?): dGoal=" + dGoal + " pos=(" + fp.getX() + ","
                        + fp.getY() + "," + fp.getZ() + ") step=" + st);
            }
        });
        report(reporter, rows, fails);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // The place-futility LEDGER. Two tests, and neither is about whether the bot climbs — both are
    // about what the place-futility ledger counts. The positive one cannot pass while the counter is
    // zeroed by the CLICK; the negative one cannot pass if the counter is made to increment
    // unconditionally, which is the regression the positive one invites.
    //
    // The climb-out place branch resets `pillarNoPlaceTicks` on the tick it CLICKS, not on the tick
    // the block LANDS — and `Body.place` is `void`, so the click has no verdict to record. The window
    // that makes this fatal is real: the crest gate admits the player at `cell.y + 0.9`, while
    // vanilla's Level#isUnobstructed refuses any placement whose cell still intersects the player's
    // AABB, i.e. until `cell.y + 1.0`. Inside that 0.1-block band every click is refused and every
    // refusal zeroes the counter, so `placeFutile` can never become true and the dig fallback behind
    // it can never take the bank.
    //
    // ⚠️ Both tests PIN the player's Y rather than letting it bob. That is deliberate: the subject
    // is the LEDGER, and a bob that has to land inside a 0.1-block band on its own would make the
    // arena a coin toss. The pin is staging, not the thing under test — which is also why neither
    // test asserts anything about height gained.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The +2 low bank the two ledger tests both use — deliberately the same shape as {@link #waterLowBank}, because
     * that is the geometry the climb-out takeover is KNOWN to engage on. An earlier version built its own basin and
     * parked the bot three blocks off the bank; the takeover never engaged, both reported {@code engages=0}, and the
     * vacuity guard is what caught it. Returns the goal on the dry land behind the bank.
     */
    private static BlockPos buildLedgerBasin(ServerLevel level, int cx, int cz, int floorY, int surface) {
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

    /** Topmost water cell of the column the bot floats in, or null once the column is filled. */
    private static BlockPos topWater(SceneBot bot, int cx, int cz, int floorY, int surface) {
        for (int y = surface; y > floorY; y--) {
            BlockPos c = new BlockPos(cx, y, cz);
            if (NavWalk.cell(bot, c).water()) {
                return c;
            }
        }
        return null;
    }

    /** {@code c} is solid as the bot's planner view classifies it. */
    private static boolean solid(SceneBot bot, BlockPos c) {
        return NavWalk.cell(bot, c).solid();
    }

    /** The ledger tests' settings, both arms alike. */
    private static void pinLedger(Game game) {
        Settings.pinUntimed(game)
                .set("allowPlace", true)
                .set("allowBreak", true)
                .set("allowSwimEscapeBreak", true) // digFallbackHere, else placeFutile hands off to nothing
                .set("allowSwimEscapePlace", true) // on the takeover's ENTRY gate — pinned explicitly, not inherited
                // FALSE, explicitly, and not because that is the default. This flag is NOT the gate on the
                // ledger path; turning it ON would ADD a second placement route — the crest-place, reachable
                // only once floodedShaft() reports false — which fills cells WITHOUT touching
                // pillarNoPlaceTicks. That route would quietly falsify the positive test's premise that
                // nothing ever gets placed, and let the negative one see a fill it is not measuring.
                .set("navigatorPillarSurfacePlace", false)
                // TRUE, explicitly, because the pinned baseline turns it off and the takeover never engages
                // without it: off, `deepDig` sends the bank to the dig at stall 21, nine ticks before the
                // pillar may engage at 30, and the bot walks out before a single place is clicked. It is
                // also the production default, so this pins the order the navigator ships with.
                .set("navigatorFootholdBeforeBankDig", true)
                // The climb-ctx rows are what told a staging failure apart from a ledger one; keep them.
                .set("navigatorDebug", true);
    }

    /**
     * POSITIVE: every place is refused, so the ledger must reach futility and hand the bank to the dig. Pins the player
     * inside the {@code [cell.y+0.9, cell.y+1.0)} band — the crest gate says "go", vanilla says "no" — and asserts
     * {@code pillarNoPlaceTicks} climbs past {@code NavigatorConstants.PILLAR_FUTILE_TICKS} anyway.
     *
     * <p>Before the fix this could not pass: the click zeroed the counter every tick, so the maximum it ever reached
     * was 1.
     */
    @Test
    void pillarLedgerCountsRefusedPlaces(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 40, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int surface = floorY + 8;
        BlockPos goal = game.onServer(s -> buildLedgerBasin(s.getLevel(plot.dimension()), cx, cz, floorY, surface));

        pinLedger(game);

        // Adjacent to the bank (cz+2), the way waterLowBank spawns. Three blocks out the takeover's entry
        // gate never fires and the arena measures nothing.
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64)); // haveBlock, and NOT a FallingBlock
            fp.getInventory().selected = 0;

            BotDebugContracts.NavigatorReading nav0 = bot.navigator();
            final long engages0 = nav0.counter("waterPillarEngages");
            // Without this the test cannot tell its own two failure modes apart. "The cell never turned
            // solid" is what a refused click leaves behind AND what no click at all leaves behind, and
            // this arena is built to assert the first.
            final long clicks0 = nav0.counter("waterPillarPlaceCalls");
            bot.goTo(Targets.block(goal));
            long maxNoPlace = 0;
            // Sampled per tick, never at the end: the tick placeFutile goes true the takeover releases
            // and zeroes the counter, so a post-hoc read always sees 0.
            boolean sawFutile = false;
            boolean pinning = false;
            BlockPos pinnedAt = null;
            double bandLow = Double.MAX_VALUE;
            double bandHigh = -Double.MAX_VALUE;
            boolean walking = true;
            int t = 0;
            for (; t < 400 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                BotDebugContracts.NavigatorReading nav = bot.navigator();
                if (nav.flag("pillarPlaceFutile")) {
                    sawFutile = true;
                }
                // TWO PHASES, and the order is the whole staging. Let the bot swim and bob FREELY until
                // the takeover actually engages — its entry gate wants a bob-stall below a bank it cannot
                // mount, and a bot held still never accumulates one. Only then start holding the band.
                if (!pinning && nav.counter("waterPillarEngages") > engages0) {
                    pinning = true;
                }
                if (!pinning) {
                    continue;
                }
                // THE BAND. Hold the feet 0.95 above the top water cell's floor: past the 0.9 crest gate,
                // short of the 1.0 vanilla needs. Re-applied every tick because the climb-out drives
                // forward and jumps, and one un-pinned tick is one tick of real physics. Dead centre in
                // the column on BOTH horizontal axes, so the AABB overlaps the cell on all three and
                // vanilla's refusal is geometric rather than lucky.
                BlockPos top = topWater(bot, cx, cz + 1, floorY, surface);
                if (top != null) {
                    pinnedAt = top;
                    fp.setPos(cx + 0.5, top.getY() + 0.95, cz + 1.5);
                    fp.setDeltaMovement(0, 0, 0);
                    bandLow = Math.min(bandLow, fp.getY() - top.getY());
                    bandHigh = Math.max(bandHigh, fp.getY() - top.getY());
                }
                maxNoPlace = Math.max(maxNoPlace, nav.counter("pillarNoPlaceTicks"));
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            BotDebugContracts.NavigatorReading end = bot.navigator();
            long engages = end.counter("waterPillarEngages") - engages0;
            long clicks = end.counter("waterPillarPlaceCalls") - clicks0;
            String probe = NavWalk.trace(end, "progressProbe");
            boolean pinnedSolid = pinnedAt != null && solid(bot, pinnedAt);

            rows.put("gate.takeoverEngages", String.valueOf(engages));
            rows.put("gate.placeClicksFired", String.valueOf(clicks));
            rows.put("ledger.peakCount", String.valueOf(maxNoPlace));
            rows.put("ledger.everJudgedFutile", String.valueOf(sawFutile));
            rows.put(
                    "pillar.pinnedCell",
                    pinnedAt == null
                            ? "none (the water column was filled)"
                            : pinnedAt.toShortString() + " bot y=" + String.format(Locale.ROOT, "%.3f", fp.getY()));
            rows.put("pillar.pinnedCellSolid", String.valueOf(pinnedSolid));
            rows.put(
                    "band.feetAboveFillCell",
                    String.format(Locale.ROOT, "[%.3f, %.3f]", bandLow, bandHigh)
                            + " (old crest gate 0.9 / vanilla acceptance line 1.0)");
            rows.put("walk.end", st + " (used " + t + "/400 ticks)");
            rows.put("walk.probe", probe);

            // VACUITY, and this one is load-bearing: with the defect present the counter is 0 because
            // every click zeroes it, and with the takeover never engaged it is 0 because the branch
            // never ran. Those two look identical from the counter alone.
            if (engages == 0) {
                fails.add("pillarLedger: the water climb-out takeover never engaged — this run never reached"
                        + " the code under test, so a counter of 0 indicates a faulty test setup, not a ledger"
                        + " defect. probe=" + probe);
            }
            if (pinnedSolid) {
                fails.add("pillarLedger: the pinned cell " + pinnedAt.toShortString() + " became solid — a"
                        + " height of 0.95 should have been refused by Level#isUnobstructed, so the test setup"
                        + " did not produce the band in which every placement is refused.");
            }
            // THE BAND, self-proved. Without this the test can go green for the opposite reason: a bot
            // that never rose at all also never places, so the counter also climbs and placeFutile also
            // fires — a PASS that says nothing about the refusal band.
            if (bandHigh < 0.9 || bandHigh >= 1.0 || bandLow < 0.9) {
                fails.add("pillarLedger: the bot's height relative to the fill cell was ["
                        + String.format(Locale.ROOT, "%.3f", bandLow) + ", "
                        + String.format(Locale.ROOT, "%.3f", bandHigh)
                        + "], not within the [0.9, 1.0) band for the whole run — below 0.9 the old crest gate is never"
                        + " entered, and at 1.0 vanilla accepts the placement. In either case this run does not test"
                        + " whether a refused click counts as progress.");
            }
            // THE CLICK ITSELF. Everything above is satisfied by a run in which the crest gate never
            // opened: "a refused click is not progress" needs a click to have been refused.
            if (clicks == 0) {
                fails.add("pillarLedger: no click fired during the whole run (the crest gate never opened) — the"
                        + " counter reaching " + maxNoPlace + " means \"nothing was clicked\", not \"clicks had no"
                        + " effect\". This run did not test the behaviour under test.");
            }
            if (!sawFutile) {
                fails.add("The ledger records actions, not outcomes: for " + t + " consecutive ticks every placement"
                        + " was refused by vanilla (the bot was pinned at "
                        + (pinnedAt == null ? "?" : String.valueOf(pinnedAt.getY() + 0.95))
                        + ", high enough for the 0.9 gate but below the 1.0 physics line), yet placeFutile was"
                        + " never true and pillarNoPlaceTicks peaked at only " + maxNoPlace + "."
                        + " The climb-out unconditionally zeroes the counter after a.place(), and BlockInteraction.place"
                        + " returns void with no success result to report, so placeFutile is always false and"
                        + " the dig fallback behind it can never take over.");
            }
        });
        report(reporter, rows, fails);
    }

    /**
     * REVERSE: a place that really lands must NOT be counted as futile. Pins the player at {@code cell.y + 0.95}
     * holding MUD — inside the crest band and above mud's 0.875 collision top, the one combination vanilla accepts
     * while the player is still in the cell — and asserts the cell actually filled BEFORE asserting the ledger stayed
     * under the futility line.
     *
     * <p>It used to pin at {@code cell.y + 1.05} holding a full cube. Above 1.0 the foot cell moves up and the fill
     * cell moves with it, so the gate asked for {@code cell.y + 2.0} and the click could never fire: the test was
     * unsatisfiable by construction and its every red was about its own staging.
     *
     * <p>The order matters. "nothing was placed and nothing gave up" and "things were placed so nothing gave up" print
     * the same verdict, so the fill has to be proven first or this test is 0==0 — and it would then pass just as
     * happily against a ledger that increments unconditionally, which is the exact regression the positive test's fix
     * could introduce.
     */
    @Test
    void pillarLedgerClearsOnARealPlace(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 40, z = 16) Plot plot, TestReporter reporter) {
        BlockPos o = plot.origin();
        final int cx = o.getX();
        final int cz = o.getZ();
        final int floorY = o.getY();
        final int surface = floorY + 8;
        BlockPos goal = game.onServer(s -> buildLedgerBasin(s.getLevel(plot.dimension()), cx, cz, floorY, surface));

        pinLedger(game);

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 0.5, surface - 1, cz + 1.5);
        Map<String, String> rows = new LinkedHashMap<>();
        List<String> fails = new ArrayList<>();
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().clearContent();
            // MUD, and this test is unstageable without it. What it needs is a click vanilla ACCEPTS
            // while the player is inside the very cell being filled. isUnobstructed tests the collision
            // shape of the state being PLACED, and mud's is 14/16 = 0.875 tall, so a player pinned at
            // fill.y + 0.95 clears it by 0.075 and the placement lands. With a full cube in hand NO pin
            // height works: the crest gate wants ≥ 0.9 into the cell and vanilla wants ≥ 1.0 above it.
            fp.getInventory().add(new ItemStack(Items.MUD, 64));
            fp.getInventory().selected = 0;

            BotDebugContracts.NavigatorReading nav0 = bot.navigator();
            final long engages0 = nav0.counter("waterPillarEngages");
            final long clicks0 = nav0.counter("waterPillarPlaceCalls");
            bot.goTo(Targets.block(goal));
            int filled = 0;
            boolean pinning = false;
            // The reading that decides this test: what the ledger held on the tick AFTER a cell
            // actually filled. -1 = no fill was ever observed, which the guards below turn into a
            // staging failure rather than a pass.
            long ledgerAfterFill = -1;
            // The counter ON the fill tick. Without it, "0 afterwards" is satisfied by a counter that
            // was never anything else and the assertion below degenerates to 0 == 0.
            long ledgerAtFill = -1;
            boolean fillPending = false;
            boolean engagedAtRead = false;
            int footLow = Integer.MAX_VALUE;
            int footHigh = Integer.MIN_VALUE;
            Set<BlockPos> wasWater = new HashSet<>();
            boolean walking = true;
            int t = 0;
            // 60, not 400. Once the run is long enough for the ledger to climb past the futility line
            // on its own, "it never declared futility" stops being evidence about the FILL and becomes
            // evidence about the budget.
            for (; t < 60 && walking; t++) {
                BlockPos top = topWater(bot, cx, cz + 1, floorY, surface);
                boolean wasSolidBefore = top == null || solid(bot, top);
                if (top != null) {
                    wasWater.add(top);
                }
                bot.tick();
                walking = bot.busy();
                BotDebugContracts.NavigatorReading nav = bot.navigator();
                if (!pinning && nav.counter("waterPillarEngages") > engages0) {
                    pinning = true;
                }
                if (pinning) {
                    // A CONSTANT absolute height, and that is the point. Pinning relative to a moving top
                    // would let foot.getY() climb as cells fill, and the high-water half of the reset rule
                    // (b) would fire — after which a green run would prove nothing about the landing half
                    // (a), which is what this test is for. INSIDE the top water cell, inside the crest band:
                    // surface + 0.95 keeps colFoot a WATER cell, so fillCell stays the locked column's top
                    // cell instead of degenerating to the player's own foot cell one row higher.
                    fp.setPos(cx + 0.5, surface + 0.95, cz + 1.5);
                    fp.setDeltaMovement(0, 0, 0);
                    footLow = Math.min(footLow, fp.blockPosition().getY());
                    footHigh = Math.max(footHigh, fp.blockPosition().getY());
                    // ONE TICK LATER, and it is not an off-by-one worth tidying away. The climb-out reads the
                    // ledger BEFORE it clicks, so on the tick a cell turns solid the counter still holds the
                    // value it was incremented to at the top of that same tick. Also record whether the
                    // takeover was still engaged at that moment: every bail path zeroes the same counter, so a
                    // bare 0 does not name its own cause.
                    if (fillPending && ledgerAfterFill < 0) {
                        ledgerAfterFill = nav.counter("pillarNoPlaceTicks");
                        engagedAtRead = nav.flag("pillarEngaged");
                        fillPending = false;
                    }
                    if (top != null && !wasSolidBefore && solid(bot, top) && ledgerAtFill < 0) {
                        ledgerAtFill = nav.counter("pillarNoPlaceTicks");
                        fillPending = true;
                    }
                }
            }
            String st = NavWalk.step(bot);
            bot.cancel();
            for (BlockPos c : wasWater) {
                if (solid(bot, c)) {
                    filled++;
                }
            }
            BotDebugContracts.NavigatorReading end = bot.navigator();
            long engages = end.counter("waterPillarEngages") - engages0;
            String probe = NavWalk.trace(end, "progressProbe");

            rows.put("gate.takeoverEngages", String.valueOf(engages));
            rows.put("gate.placeClicksFired", String.valueOf(end.counter("waterPillarPlaceCalls") - clicks0));
            rows.put("pillar.cellsActuallyFilled", filled + "/" + wasWater.size());
            rows.put("ledger.onFillTick", ledgerAtFill < 0 ? "no fill observed" : String.valueOf(ledgerAtFill));
            rows.put("ledger.afterFill", ledgerAfterFill < 0 ? "no fill observed" : String.valueOf(ledgerAfterFill));
            rows.put("ledger.takeoverEngagedAtRead", String.valueOf(engagedAtRead));
            rows.put("feet.cellYRange", footLow > footHigh ? "never pinned" : "[" + footLow + ", " + footHigh + "]");
            rows.put("walk.end", st + " (used " + t + "/60 ticks)");
            rows.put("walk.probe", probe);

            if (engages == 0) {
                fails.add("pillarLedgerReal: the takeover never engaged — the test setup is wrong, and none of the"
                        + " rows describe the subject under test. probe=" + probe);
            }
            // THE SELF-PROOF, and it must come first: without an observed fill, "the ledger stayed at 0"
            // is satisfied by a run in which nothing whatsoever happened.
            if (filled == 0 || ledgerAfterFill < 0) {
                fails.add("pillarLedgerReal: no cell was observed changing from water to solid during the whole run"
                        + " (" + filled + " cells filled, reading after the fill=" + ledgerAfterFill + ") — this"
                        + " test asserts that \"a placement that actually lands resets the counter\", and the"
                        + " precondition \"a placement actually landed\" did not hold. Check two things in the test"
                        + " setup: the bot is pinned at " + (surface + 0.95) + " (inside the [+0.9, +1.0) band of the"
                        + " fill cell with its feet still in water), and the held item is a block with a collision box"
                        + " <= 0.875 tall, such as mud.");
            }
            // The counter has to have BEEN something before the landing zeroed it.
            if (ledgerAtFill <= 0) {
                fails.add("pillarLedgerReal: the ledger read " + ledgerAtFill + " on the fill tick — it never"
                        + " accumulated before the fill, so \"0 after the fill\" is not evidence (0 -> 0 is not"
                        + " a reset).");
            }
            // Every bail path zeroes the same counter, so a zero read after a bail answers this test's
            // question without the landing doing it.
            if (!engagedAtRead) {
                fails.add("pillarLedgerReal: the takeover was no longer engaged when the reset was read — every"
                        + " bail path zeroes the same counter, so this 0 does not identify its own cause.");
            }
            // (b) MUST NOT BE THE EXPLANATION. Held at one absolute height, foot.getY() is constant and the
            // high-water half of the reset rule cannot refresh after the first tick.
            if (footHigh > footLow) {
                fails.add("pillarLedgerReal: the feet cell changed within [" + footLow + ", " + footHigh + "] —"
                        + " the pin did not hold, so the high-water half (b) may be the cause of the reset, and"
                        + " this run cannot prove (a).");
            }
            if (ledgerAfterFill != 0) {
                fails.add("A cell was actually filled, but the ledger was not reset: after the fill tick"
                        + " pillarNoPlaceTicks is " + ledgerAfterFill + ", while the bot stayed pinned in the same"
                        + " cell for the whole run (feet cell " + footLow + "), so the high-water half cannot have"
                        + " reset it. At this rate a climb that is succeeding would be judged futile after "
                        + "51 ticks and taken over by the dig fallback.");
            }
        });
        report(reporter, rows, fails);
    }

    /** Publish the rows, then fail with every failure the walk collected. */
    private static void report(TestReporter reporter, Map<String, String> rows, List<String> fails) {
        rows.forEach(reporter::publishEntry);
        if (!fails.isEmpty()) {
            fail(String.join("\n", fails));
        }
    }
}

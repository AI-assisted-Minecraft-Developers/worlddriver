package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.world.WorldBlockingApi;
import net.magicterra.worlddriver.api.methods.world.WorldContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.TestReporter;

/**
 * The N4–N6/N9 portal tests: a portal built, cast and lit on flat ground; a lit portal walked through into the Nether;
 * twelve eyes set into an End frame and the crossing into the End.
 *
 * <p>Each runs inside ONE server task, as the old scenes ran inside one scene tick: the bot is parked and ticked by
 * hand, so no world tick falls between its steps — lava and water placed into the mould never flow, fire never spreads,
 * and the dimension the bot crosses into never ticks with it inside.
 *
 * <p><b>What the crossings leave behind.</b> A Nether transit makes vanilla's portal forcer build an arrival portal
 * (frame, platform, carved air) near the 8:1-scaled position; an End transit builds the obsidian spawn platform. Both
 * lie outside any plot, so each test snapshots the box vanilla may build in before the crossing and puts it back when
 * it ends. The bot is moved back to the overworld in the same server task it arrived in: the End's dragon fight starts
 * scanning (exit portal, dragon) only once its own tick has seen a player near the island, and it never sees this one.
 */
@Scene("wd.scenes")
@Isolated   // see ProcessTest: these tests hold the server thread for long spans
@Execution(ExecutionMode.SAME_THREAD)
class PortalTest {

    private static final AtomicInteger SNAPSHOTS = new AtomicInteger();

    /**
     * Walk a server-side player through a lit portal and out the other side, into the Nether.
     *
     * <p>The first question of ROADMAP N6, asked here rather than on the ladder because the rung that asks it in the
     * field has just spent an hour of wall-clock casting ten obsidian at the bottom of a shaft. A portal that lights and
     * does not transit would invalidate that whole rung, and it would do so silently: the bot would stand in purple fog
     * forever and the budget would run out, which reads as a slow walk.
     *
     * <p><b>Why it was genuinely in doubt.</b> {@code JoinedPlayers.JoinedBody} keeps {@code tick()} empty for the
     * level's entity loop, so the player is ticked only when its driver steps it. Vanilla's portal handling lives in
     * {@code Entity.baseTick()}, which each step reaches through {@code JoinedBody.pump}'s {@code doTick()}, and
     * {@code checkInsideBlocks()} rides {@code move()}. So the machinery is present — but "present" and "works for a
     * player with a connection that discards every packet it is given" are different claims, and only one of them can
     * be tested. Written as a frontier sensor, it went red on BOTH loaders for the same reason, and is kept required so
     * that reason cannot come back quietly.
     *
     * <p>Staged: the frame is set as obsidian rather than cast, because this test is about the seam AFTER a portal
     * exists. The lighting is NOT staged — it goes through the flint-and-steel path, so that a portal built by the
     * driver is what the driver then tries to walk into.
     */
    @Test
    void serverEntersTheNether(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        boolean hasNether = game.onServer(s -> s.getLevel(Level.NETHER) != null);
        Assumptions.assumeTrue(hasNether,
                "this runtime has no Nether dimension (a data pack removed minecraft:the_nether), so there is nowhere to go");
        Settings.pin(game);
        plot.fill(-4, 0, -3, 5, 0, 3, Blocks.STONE);

        // The obsidian ring, x0 = 1, y0 = 1, in the plane z = 0; the interior is the plot's air.
        final int x0 = 1, y0 = 1;
        plot.fill(x0, y0, 0, x0 + 1, y0, 0, Blocks.OBSIDIAN);
        plot.fill(x0, y0 + 4, 0, x0 + 1, y0 + 4, 0, Blocks.OBSIDIAN);
        plot.fill(x0 - 1, y0 + 1, 0, x0 - 1, y0 + 3, 0, Blocks.OBSIDIAN);
        plot.fill(x0 + 2, y0 + 1, 0, x0 + 2, y0 + 3, 0, Blocks.OBSIDIAN);
        BlockPos hearth = plot.at(x0, y0, 0);
        BlockPos doorway = hearth.above();

        // Where vanilla's portal forcer may build the arrival portal: its search and its build both stay within 16
        // blocks of the scaled position, anywhere under the roof; a few more for the frame and platform around it.
        BlockPos scaled = game.translate(doorway, Level.OVERWORLD, Level.NETHER);
        keepAndPutBack(game, Level.NETHER, scaled.offset(-20, 0, -20).atY(0), scaled.offset(20, 0, 20).atY(127));

        SceneBot bot = SceneBot.spawn(game, plot, x0, 1, 2);
        int logicalHeight = game.onServer(s -> s.getLevel(Level.NETHER).dimensionType().logicalHeight());
        BlockPos[] arrival = new BlockPos[1];
        game.runOnServer(s -> {
            ServerLevel level = bot.level();
            ServerPlayer fp = bot.player();
            fp.getInventory().items.set(0, new ItemStack(Items.FLINT_AND_STEEL, 1));
            fp.getInventory().selected = 0;

            bot.hold(Items.FLINT_AND_STEEL);
            bot.lookAt(hearth);
            bot.tick();
            bot.useOn(hearth, Direction.UP);
            bot.tick();
            assertTrue(level.getBlockState(doorway).is(Blocks.NETHER_PORTAL),
                    "the frame lights before anything is asked about walking through it: "
                            + level.getBlockState(doorway).getBlock());

            // Standing in it is what starts vanilla's portal timer; a player's is ~80 ticks, so the budget below is
            // generous by design — a run that spends it all has found a player the timer never starts for, which is a
            // different finding from a player it never fires for. Every key is let go: stepped, never steered.
            bot.release();
            fp.setPos(doorway.getX() + 0.5, doorway.getY(), doorway.getZ() + 0.5);
            final int budget = 600;
            int ticked = 0;
            while (ticked < budget && fp.level() == level) { bot.tick(); ticked++; }

            ResourceKey<Level> arrived = fp.level().dimension();
            BlockPos at = fp.blockPosition();
            reporter.publishEntry("transit", ticked + " ticks" + (ticked >= budget ? " (budget exhausted)" : "")
                    + ", " + arrived.location() + " " + at.toShortString() + ", standing in "
                    + fp.level().getBlockState(at).getBlock() + " on " + fp.level().getBlockState(at.below()).getBlock()
                    + "; the portal is " + (level.getBlockState(doorway).is(Blocks.NETHER_PORTAL) ? "still" : "no longer")
                    + " there");
            // Back before the Nether ticks with the bot in it.
            bot.moveTo(Level.OVERWORLD, plot.at(-3, 1, -2));

            assertEquals(Level.NETHER, arrived, "the driven bot arrives in the Nether through its own portal after "
                    + ticked + " ticks (a bot that never entered the portal block is a POSITIONING fault, one that stood "
                    + "in it for the whole budget a TICK fault)");
            // WHERE it landed, and this is not a detail. Vanilla scales the destination by the ratio of the two
            // dimensions' coordinate_scale — 8:1 — so a bot that arrives at the UNSCALED coordinate is in the Nether
            // and also tens of thousands of blocks from the fortress the blaze rod rung will look for, and every rung
            // above this one would search the wrong world while this test reported green.
            arrival[0] = at;
        });
        BlockPos at = arrival[0];
        int drift = Math.max(Math.abs(at.getX() - scaled.getX()), Math.abs(at.getZ() - scaled.getZ()));
        game.check(drift <= 128, "the arrival " + at.toShortString() + " is at the 8:1-scaled coordinate "
                + scaled.getX() + "," + scaled.getZ() + ", not the raw one: drift " + drift);
        // The dimension's own ceiling: a nether arrival above logical height is standing where the roof is, which no
        // portal search should ever return.
        game.check(at.getY() <= logicalHeight, "the arrival is under the Nether's own roof: y=" + at.getY()
                + " logicalHeight=" + logicalHeight);
    }

    /**
     * The whole of N5 end to end: a flat floor, a bucket, a flint-and-steel and a pile of cobblestone go in; a <b>lit
     * nether portal</b> comes out. The step the rung actually spends its blocks on is the mould: in the field there is
     * no two-thick wall waiting beside the lava. The bot has to build it.
     *
     * <p><b>Placement is exact, which is why this is affordable.</b> {@code mc.bot.useItem} with a {@code pos} and
     * {@code face} builds its own {@code BlockHitResult} from them rather than ray-tracing for one, so the mould is
     * bookkeeping rather than a navigation problem. It does check reach, as the server's packet handler would, so each
     * block is placed from a spot just in front of it; never inside it, because a block cannot be placed into a cell the
     * placer is standing in ({@code isUnobstructed}).
     *
     * <p><b>Order is what makes every block placeable.</b> A free-standing wall has nothing to place against, so the
     * backing slab at {@code z=+1} goes up first, bottom-up, each block supported by the one below and the lowest by the
     * floor. Every solid cell of the front layer is then placed against the backing behind it —
     * {@code useBlock(backing, NORTH)} — which needs no support of its own. Building the front layer first would strand
     * every cell whose lower neighbour is one of the sixteen that must stay air.
     *
     * <p>Staged: the floor, the lava lake, the reservoir, and the block the bot stands on to pour from. That is terrain
     * and a pillar — the terrain the seed provides, and a climb {@code ascendByTowering} owns on the ladder. Everything
     * the rung must MAKE is made here.
     */
    @Test
    void serverBuildsAndLightsAPortal(Game game, @Plot.Centered @Plot.Size(x = 48, y = 32, z = 32) Plot plot,
                                      TestReporter reporter) {
        Settings.pin(game);
        final int floorY = 0;
        plot.fill(-10, floorY, -12, 20, floorY, 6, Blocks.STONE);
        List<BlockPos> lake = new ArrayList<>();
        for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) lake.add(plot.at(14 + dx, floorY, -9 + dz));
        BlockPos well = plot.at(-8, floorY, -8);
        int floor = plot.at(0, floorY, 0).getY();

        final int x0 = 0, y0 = floorY + 1;
        SceneBot bot = SceneBot.spawn(game, plot, 7, floorY + 1, -6);

        game.runOnServer(s -> {
            ServerLevel level = bot.level();
            ServerPlayer fp = bot.player();
            fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE, 1));
            fp.getInventory().items.set(1, new ItemStack(Items.BUCKET, 1));
            fp.getInventory().items.set(2, new ItemStack(Items.FLINT_AND_STEEL, 1));
            for (int slot = 3; slot <= 5; slot++) fp.getInventory().items.set(slot, new ItemStack(Items.COBBLESTONE, 64));
            fp.getInventory().selected = 0;
            // The fluids go down in this same task: the floor has air under it, and a world tick before the bot works
            // would let them run down out of the lake and the well.
            for (BlockPos at : lake) level.setBlockAndUpdate(at, Blocks.LAVA.defaultBlockState());
            level.setBlockAndUpdate(well, Blocks.WATER.defaultBlockState());
            Portal p = new Portal(bot, level, fp, floor);

            // ---- 1. the backing slab, bottom-up, each block resting on the one below ----
            int placed = 0, wanted = 0;
            List<String> missed = new ArrayList<>();
            for (int dy = 0; dy <= 6; dy++)
                for (int dx = -2; dx <= 3; dx++) {
                    BlockPos at = plot.at(x0 + dx, y0 + dy, 1);
                    wanted++;
                    if (p.placeAt(at, at.below(), Direction.UP)) placed++;
                    else missed.add("backing " + dx + "_" + dy + "=" + level.getBlockState(at).getBlock());
                }

            // ---- 2. the front layer's solid cells, each against the backing behind it ----
            // Everything in the 6x8 face EXCEPT the ten ring cells, the six interior cells and the two cap notches the
            // top row is cast against. Each plan entry is {ring cell, the wet cell its lava is cast against}.
            Set<BlockPos> hollow = new HashSet<>();
            List<BlockPos[]> plan = new ArrayList<>();
            plan.add(new BlockPos[]{plot.at(x0, y0, 0), plot.at(x0, y0 + 1, 0)});
            plan.add(new BlockPos[]{plot.at(x0 + 1, y0, 0), plot.at(x0 + 1, y0 + 1, 0)});
            for (int dy = 1; dy <= 3; dy++) {
                plan.add(new BlockPos[]{plot.at(x0 - 1, y0 + dy, 0), plot.at(x0, y0 + dy, 0)});
                plan.add(new BlockPos[]{plot.at(x0 + 2, y0 + dy, 0), plot.at(x0 + 1, y0 + dy, 0)});
            }
            List<BlockPos> caps = List.of(plot.at(x0, y0 + 5, 0), plot.at(x0 + 1, y0 + 5, 0));
            plan.add(new BlockPos[]{plot.at(x0, y0 + 4, 0), caps.get(0)});
            plan.add(new BlockPos[]{plot.at(x0 + 1, y0 + 4, 0), caps.get(1)});
            List<BlockPos> interior = new ArrayList<>();
            for (int dx = 0; dx <= 1; dx++) for (int dy = 1; dy <= 3; dy++) interior.add(plot.at(x0 + dx, y0 + dy, 0));
            for (BlockPos[] step : plan) hollow.add(step[0]);
            hollow.addAll(interior);
            hollow.addAll(caps);

            int wall = 0, wallWanted = 0;
            for (int dy = 0; dy <= 6; dy++)
                for (int dx = -2; dx <= 3; dx++) {
                    BlockPos at = plot.at(x0 + dx, y0 + dy, 0);
                    if (hollow.contains(at)) continue;
                    wallWanted++;
                    if (p.placeAt(at, at.relative(Direction.SOUTH), Direction.NORTH)) wall++;
                    else missed.add("wall " + dx + "_" + dy + "=" + level.getBlockState(at).getBlock());
                }
            assertEquals(wanted + wallWanted, placed + wall, "every block of the mould goes where it was named: backing "
                    + placed + "/" + wanted + ", wall " + wall + "/" + wallWanted + ", missed " + missed
                    + ", cobblestone left " + Staging.countItem(fp, Items.COBBLESTONE));

            // ---- 3. the ten casts, one bucket ----
            assertTrue(p.scoopSource(well, Items.WATER_BUCKET), "the reservoir fills the bucket with water");
            int cast = 0;
            List<String> castNotes = new ArrayList<>();
            for (int i = 0; i < plan.size(); i++) {
                BlockPos cell = plan.get(i)[0], wet = plan.get(i)[1];
                if (!p.pourInto(wet, Items.WATER_BUCKET, Blocks.WATER)) { castNotes.add("water stuck at " + label(plot, cell)); break; }
                if (!p.scoopSource(lake.get(i), Items.LAVA_BUCKET)) { castNotes.add("lava stuck at " + label(plot, cell) + ": " + p.lastScoop()); break; }
                p.pourInto(cell, Items.LAVA_BUCKET, Blocks.OBSIDIAN);
                if (level.getBlockState(cell).is(Blocks.OBSIDIAN)) cast++;
                else castNotes.add("cast missed " + label(plot, cell) + "=" + level.getBlockState(cell).getBlock());
                p.scoopSource(wet, Items.WATER_BUCKET);
            }
            assertEquals(plan.size(), cast, "ten obsidian cast into a mould the bot built itself: " + castNotes);

            // ---- 4. light it ----
            BlockPos hearth = plot.at(x0, y0, 0);
            p.standTo(hearth);
            bot.tick();
            assertTrue(bot.hold(Items.FLINT_AND_STEEL), "flint-and-steel in hand");
            bot.lookAt(hearth);
            bot.tick();
            bot.useOn(hearth, Direction.UP);
            bot.tick();
            int lit = 0;
            for (BlockPos c : interior) if (level.getBlockState(c).is(Blocks.NETHER_PORTAL)) lit++;
            assertEquals(interior.size(), lit, "the portal the bot built and cast is lit end to end; the interior holds "
                    + level.getBlockState(interior.get(0)).getBlock());
            reporter.publishEntry("portal", "laid " + (wanted + wallWanted) + " mould blocks, cast " + cast
                    + " obsidian with one bucket, lit " + lit + " portal cells");
        });
    }

    /** {@code dx_dy} of a frame cell relative to the plot, readable when the plot moves. */
    private static String label(Plot plot, BlockPos at) {
        BlockPos o = plot.at(0, 0, 0);
        return (at.getX() - o.getX()) + "_" + (at.getY() - o.getY());
    }

    /** The bucket and placement moves of the portal build, on one parked bot. Server thread. */
    private record Portal(SceneBot bot, ServerLevel level, ServerPlayer fp, int floorY) {

        /**
         * Put the bot where the wall cell it is about to work on is at EYE LEVEL, on a block of its own.
         *
         * <p>Aiming at the backing behind a cell only reaches that cell if the ray is close to horizontal. From one
         * fixed ledge the ray to a cell five blocks up is steep enough to enter the wall a block low, and the run that
         * found this had just cast obsidian into exactly that block: the scoop hit the fresh obsidian, left the water
         * behind, and the NEXT cell reported an empty bucket. So the standpoint is a function of the target.
         */
        void standTo(BlockPos target) {
            int feet = Math.max(floorY + 2, target.getY() - 1);
            level.setBlockAndUpdate(new BlockPos(target.getX(), feet - 1, target.getZ() - 2), Blocks.STONE.defaultBlockState());
            fp.setPos(target.getX() + 0.5, feet, target.getZ() - 1.5);
        }

        /** Stand beside a one-cell pool and pick it up. The empty bucket clips {@code SOURCE_ONLY}, so the ray stops on
         *  the fluid itself rather than passing through to the floor. */
        boolean scoopSource(BlockPos at, Item expected) {
            boolean inWall = at.getY() > floorY;
            if (inWall) standTo(at);
            else fp.setPos(at.getX() + 0.5, floorY + 1, at.getZ() + 1.5);
            bot.tick();
            if (!bot.hold(Items.BUCKET)) {
                last[0] = "no empty bucket to hold; hand=" + fp.getMainHandItem();
                return false;
            }
            bot.lookAt(inWall ? at.relative(Direction.SOUTH) : at);
            String aimed = fp.getYRot() + "/" + fp.getXRot() + " " + pos(fp);
            bot.tick();
            String ticked = fp.getYRot() + "/" + fp.getXRot() + " " + pos(fp);
            String used = bot.use();
            bot.tick();
            last[0] = "use=" + used + " aimed " + aimed + ", after a tick " + ticked + ", now " + pos(fp) + " at "
                    + at.toShortString() + "=" + level.getBlockState(at).getBlock() + " hand=" + fp.getMainHandItem();
            return Staging.countItem(fp, expected) >= 1;
        }

        /** What the last scoop saw, for the failure message. */
        private static final String[] last = {""};

        String lastScoop() {
            return last[0];
        }

        private static String pos(ServerPlayer fp) {
            return String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)", fp.getX(), fp.getY(), fp.getZ());
        }

        /** Put the held fluid into a cell carved in the wall, by standing on the ledge in front of it and aiming at the
         *  SOLID BACKING behind it: a bucket fills the neighbour of the face its ray lands on, so aiming into the air
         *  cell itself hits nothing and the fluid goes wherever the ray eventually stops. */
        boolean pourInto(BlockPos target, Item held, Block want) {
            standTo(target);
            bot.tick();
            if (!bot.hold(held)) return false;
            bot.lookAt(target.relative(Direction.SOUTH));
            bot.tick();
            bot.use();
            bot.tick();
            return level.getBlockState(target).is(want);
        }

        /** Place a held cobblestone in {@code target} by clicking {@code face} of {@code support}. No aim and no walk:
         *  the click names its own cell and face. {@code mc.bot.useItem} refuses a block out of the player's reach, so
         *  the bot is first put a cell and a half in front of the target at its height — never in the target cell. */
        boolean placeAt(BlockPos target, BlockPos support, Direction face) {
            fp.setPos(target.getX() + 0.5, Math.max(floorY + 1, target.getY() - 1), Math.min(target.getZ(), support.getZ()) - 1.5);
            if (!bot.hold(Items.COBBLESTONE)) return false;
            bot.useOn(support, face);
            bot.tick();
            return !level.getBlockState(target).isAir();
        }
    }

    /**
     * Set twelve eyes into a stronghold's frame and step through into the End.
     *
     * <p>Two verbs, both on the critical path and neither exercised anywhere else. Inserting an eye is
     * {@code EnderEyeItem.useOn} — the same {@code useOn}-only shape as the flint-and-steel, so a bot that reaches for
     * {@code useItemInHand} gets {@code PASS} and a frame that never fills. Stepping through is {@code EndPortalBlock},
     * which reaches {@code changeDimension} by a different road than the Nether's and lands on a fixed point rather
     * than a searched one: {@code ServerLevel.END_SPAWN_POINT}, so a bot that arrived "somewhere in the End" would look
     * correct on a dimension check while standing in the void beside the island. The assertion is on the platform.
     *
     * <p>Staged: the frame and the twelve eyes. Where eyes come from and finding the stronghold are other rungs'
     * questions; what this owns is that a driven bot can spend them and survive the crossing.
     */
    @Test
    void serverOpensTheEndPortal(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        boolean hasEnd = game.onServer(s -> s.getLevel(Level.END) != null);
        Assumptions.assumeTrue(hasEnd,
                "this runtime has no End dimension (a data pack removed minecraft:the_end), so there is nowhere to go");
        Settings.pin(game);
        plot.fill(-4, 0, -4, 4, 0, 4, Blocks.STONE);

        // Twelve frames round a 3x3, each FACING the middle — the shape EndPortalFrameBlock.getOrCreatePortalShape()
        // looks for. A frame laid without facings is a frame that fills with eyes and never becomes a portal, which
        // reads as a broken insert.
        List<BlockPos> frames = new ArrayList<>();
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            for (int d = -1; d <= 1; d++) {
                frames.add(frame(level, plot.at(d, 1, -2), Direction.SOUTH));
                frames.add(frame(level, plot.at(d, 1, 2), Direction.NORTH));
                frames.add(frame(level, plot.at(-2, 1, d), Direction.EAST));
                frames.add(frame(level, plot.at(2, 1, d), Direction.WEST));
            }
        });
        BlockPos doorway = plot.at(0, 1, 0);

        // The arrival platform vanilla builds: 5x5 obsidian under END_SPAWN_POINT and three layers of air above it.
        BlockPos spawn = ServerLevel.END_SPAWN_POINT;
        keepAndPutBack(game, Level.END, spawn.offset(-2, -1, -2), spawn.offset(2, 2, 2));

        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 3);
        BlockPos[] arrival = new BlockPos[1];
        game.runOnServer(s -> {
            ServerLevel level = bot.level();
            ServerPlayer fp = bot.player();
            fp.getInventory().items.set(0, new ItemStack(Items.ENDER_EYE, 12));
            fp.getInventory().selected = 0;

            int set = 0;
            List<String> missed = new ArrayList<>();
            for (BlockPos at : frames) {
                if (!bot.hold(Items.ENDER_EYE)) { missed.add("eyes ran out at " + at.toShortString()); break; }
                bot.useOn(at, Direction.UP);
                bot.tick();
                if (level.getBlockState(at).getValue(EndPortalFrameBlock.HAS_EYE)) set++;
                else missed.add(at.toShortString() + "=" + level.getBlockState(at));
            }
            assertEquals(frames.size(), set, "all twelve eyes go into the frame from a driven bot's hand: " + missed
                    + ", eyes left " + Staging.countItem(fp, Items.ENDER_EYE));
            assertTrue(level.getBlockState(doorway).is(Blocks.END_PORTAL), "the twelfth eye opens the portal: "
                    + level.getBlockState(doorway).getBlock());

            // Idle with every key let go, as for the Nether: stepped, never steered.
            bot.release();
            fp.setPos(doorway.getX() + 0.5, doorway.getY(), doorway.getZ() + 0.5);
            final int budget = 400;
            int ticked = 0;
            while (ticked < budget && fp.level() == level) { bot.tick(); ticked++; }
            ResourceKey<Level> arrived = fp.level().dimension();
            BlockPos at = fp.blockPosition();
            String underfoot = String.valueOf(fp.level().getBlockState(at.below()).getBlock());
            // Back before the End ticks with the bot in it, so its dragon fight never starts.
            bot.moveTo(Level.OVERWORLD, plot.at(3, 1, 3));
            reporter.publishEntry("transit", ticked + " ticks" + (ticked >= budget ? " (budget exhausted)" : "") + ", "
                    + arrived.location() + " " + at.toShortString() + " on " + underfoot);

            assertEquals(Level.END, arrived, "the driven bot crosses into the End within " + budget + " ticks");
            // The platform, not merely the dimension. END_SPAWN_POINT is fixed, so "somewhere in the End" and "on the
            // obsidian island vanilla builds for arrivals" are different claims and only the second one can fight a
            // dragon.
            arrival[0] = at;
        });
        BlockPos at = arrival[0];
        int drift = Math.max(Math.abs(at.getX() - spawn.getX()), Math.abs(at.getZ() - spawn.getZ()));
        game.check(drift <= 16, "the arrival " + at.toShortString() + " is on the End's own spawn platform "
                + spawn.toShortString() + ": drift " + drift);
    }

    /** An end-portal frame at {@code at} facing {@code towards}, returned for the caller's list. Server thread. */
    private static BlockPos frame(ServerLevel level, BlockPos at, Direction towards) {
        level.setBlockAndUpdate(at, Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.FACING, towards));
        return at;
    }

    /** Keep the box {@code from..to} of {@code dimension} as it is now and put it back when the test ends. */
    private static void keepAndPutBack(Game game, ResourceKey<Level> dimension, BlockPos from, BlockPos to) {
        String id = "wd-portal-" + SNAPSHOTS.incrementAndGet();
        game.runOnServer(s -> game.api(WorldBlockingApi::of).snapshot(new WorldContracts.SnapshotParams(Targets.pos(from),
                Targets.pos(to), Optional.of(dimension.location().toString()), Optional.of(id), true, false)));
        game.afterTest(s -> game.api(WorldBlockingApi::of).restore(new WorldContracts.RestoreParams(id, true, false)));
    }
}

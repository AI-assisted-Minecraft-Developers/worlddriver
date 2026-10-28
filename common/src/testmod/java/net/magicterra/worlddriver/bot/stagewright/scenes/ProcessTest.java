package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.bot.BotTaskBlockingApi;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.Routes;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.TestReporter;

/**
 * The headless server-side task arenas: goto, mine, flee, build, look, follow, combat, bridge, tower and the log-cost
 * waiver, each on a parked server bot driven by the {@code mc.bot.*} verb that owns the task. A test either runs
 * everything inside one server task (start the verb, then tick the bot until it is idle) or starts the verb and lets
 * the server tick the bot, awaiting {@code !bot.busy()}. What a task reports is read from {@code mc.bot.status} slots;
 * what the body holds or looks at, from {@code mc.debug.body}.
 *
 * <p><b>Fresh entities.</b> The follow, combat and forbid-dig tests summon an entity and wait (bounded) until it is
 * queryable before driving.
 *
 * <p><b>The combat rig.</b> The zombie is {@code setNoAi(true)} + {@code setPersistenceRequired()} + max
 * {@code KNOCKBACK_RESISTANCE}, and it is re-pinned to its cell each drive iteration. The drive loop runs up to 1500 bot
 * ticks inside one server task, so the test ticks the zombie itself ({@code zombie.tick()}) each iteration to clear its
 * hurt-cooldown; a target ticked once stays invulnerable after the first hit. It cannot sun-burn to a false fire-kill
 * because the scene's world is frozen at midnight.
 */
@Scene("wd.scenes")
// Isolated, one test at a time: a test here may spin a bot thousands of ticks inside ONE server task, as the old scene
// did inside one scene tick, and while it does no other test's plot lease can start (StageWright gives up after 8 s).
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class ProcessTest {

    private static String pos(ServerPlayer fp) {
        return String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)", fp.getX(), fp.getY(), fp.getZ());
    }

    /** {@code at} relative to the plot, for messages that stay readable when the plot moves. */
    private static String rel(Plot plot, BlockPos at) {
        BlockPos o = plot.at(0, 0, 0);
        return (at.getX() - o.getX()) + "," + (at.getY() - o.getY()) + "," + (at.getZ() - o.getZ());
    }

    /** Put {@code stack} in inventory slot {@code slot} and select slot 0. Server thread. */
    private static void slot(ServerPlayer fp, int slot, ItemStack stack) {
        fp.getInventory().items.set(slot, stack);
        fp.getInventory().selected = 0;
    }

    // ---------------------------------------------------------------- goto / mine / flee

    /** Headless driver: a server bot walks + steps up a +1 ledge to a cell, through {@code mc.bot.goto}. */
    @Test
    void serverDriver(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pinUntimed(game).set("navigatorDebug", false);
        plot.fill(-1, 0, -1, 1, 0, 10, Blocks.STONE);
        // +1 ledge for the back half → exercises walk + stepUp via the server driver.
        plot.fill(-1, 1, 6, 1, 1, 10, Blocks.STONE);
        BlockPos goal = plot.at(0, 2, 9);   // foot on the ledge
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            bot.goTo(Targets.block(goal));
            assertTrue(bot.busy(), "the goto did not put the bot to work");
            bot.tickUntilIdle(200);
            ServerPlayer fp = bot.player();
            boolean reached = Math.abs(fp.getX() - (goal.getX() + 0.5)) < 1.5
                    && Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5 && fp.getY() >= goal.getY() - 0.4;
            assertFalse(bot.busy(), "server driver did not finish and go idle");
            assertTrue(reached, "server-driven agent did not reach the goal: pos=" + pos(fp) + " end="
                    + bot.status().lastProcessEnd());
        });
    }

    /** Navigate + MINE a target block (no client). */
    @Test
    void serverMine(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pinUntimed(game).set("allowBreak", true).set("allowPlace", false).set("navigatorDebug", false);
        SceneArena.buildFloor(plot, 0, 0, 0);
        plot.place(3, 1, 0, Blocks.STONE);   // a block on the floor, away from the bot
        BlockPos target = plot.at(3, 1, 0);
        SceneBot bot = SceneBot.spawn(game, plot, -3, 1, 0);
        game.runOnServer(s -> {
            bot.mineCell(target);
            bot.tickUntilIdle(200);
            assertTrue(bot.level().getBlockState(target).isAir(), "server agent did not mine the target (still "
                    + bot.level().getBlockState(target) + ")");
            assertFalse(bot.busy(), "mine task did not finish and go idle");
        });
    }

    /** The server bot runs {@code mc.bot.runAway} (stateful) to a minimum distance. */
    @Test
    void serverFlee(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pinUntimed(game).set("navigatorDebug", false);
        final int r = 10, minDist = 6;
        plot.fill(-r, 0, -r, r, 0, r, Blocks.STONE);
        BlockPos from = plot.at(0, 1, 0);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            bot.runAway(from, minDist);
            bot.tickUntilIdle(200);
            ServerPlayer fp = bot.player();
            double dx = fp.getX() - (from.getX() + 0.5), dz = fp.getZ() - (from.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dz * dz);
            assertTrue(dist >= minDist - 0.5, "server runAway did not reach min flee distance: dist=" + dist
                    + " (need " + minDist + ")");
            assertFalse(bot.busy(), "flee did not finish and go idle");
        });
    }

    /**
     * Mining must put the HARVEST IN THE BAG, not merely turn the block to air. <b>PASS means an item that did not
     * exist before is in the inventory afterwards.</b>
     *
     * <p><b>Real ticks, not a spin.</b> A mined block drops an {@link ItemEntity} with a 10-tick pickup delay, and an
     * entity only counts that delay down when the LEVEL ticks it. So the verb is started and the bot left to the
     * server's own tick, and the test awaits the bot going idle.
     *
     * <p><b>The rig reproduces the hazard, not a friendly case.</b> The pickaxe starts in the BAG (slot 20, not the
     * hotbar) and the hotbar starts holding DIRT, because that is the state the journey's iron rung actually reaches:
     * {@code holdPlaceable()} grabs the first placeable in the hotbar while bridging, so by the time the ore breaks the
     * hand holds the dirt it just tunnelled through.
     *
     * <p>The message separates the two ways this can fail: {@code drops=} counts raw-iron ItemEntities still lying in
     * the arena. Drops present + bag empty is a COLLECT/pickup defect; neither present is a drop defect.
     */
    @Test
    void serverMineHarvest(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        mineHarvest(game, plot, false);
    }

    /**
     * The SAME circuit with two of the three ores buried — the field shape, since the journey's iron rung mines ore
     * four blocks under the surface. The drops were never in a hole at all: they were sealed inside rock the bot had
     * no business mining through, and the miner now peels its way down instead.
     */
    @Test
    void serverMineHarvestBuried(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        mineHarvest(game, plot, true);
    }

    private static void mineHarvest(Game game, Plot plot, boolean buried) {
        // Real ticks — a pathfinder slice that blocks the server thread would stall the suite, so these stay the
        // generous-but-bounded values the journey rig uses.
        Settings.pin(game).set("allowBreak", true).set("allowPlace", true)   // placing is what makes the hand hold dirt
                .set("navigatorDebug", false).set("pathfinder.sliceMs", 20).set("pathfinder.maxMs", 2000);
        // DIRT floor (not a target, and not a pickaxe block) over three courses of STONE, so the two buried ores can
        // be dug down to and the drops land in the hole the bot just made.
        plot.fill(-2, -3, -2, 10, -1, 2, Blocks.STONE);
        plot.fill(-2, 0, -2, 10, 0, 2, Blocks.DIRT);
        // Three ores, spread out and at three depths. Collecting them is a CIRCUIT, not a reach: the bot walks, digs,
        // and has to come back for what fell into each hole.
        List<BlockPos> ores = List.of(plot.at(3, 1, 0), plot.at(6, buried ? -1 : 1, 0), plot.at(9, buried ? -2 : 1, 0));
        for (BlockPos ore : ores) plot.place(rel3(plot, ore)[0], rel3(plot, ore)[1], rel3(plot, ore)[2], Blocks.IRON_ORE);

        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.getInventory().items.set(0, new ItemStack(Items.DIRT, 16));   // what holdPlaceable will grab
            fp.getInventory().items.set(20, new ItemStack(Items.STONE_PICKAXE));  // bag, not hotbar
            fp.getInventory().selected = 0;
            // Ask for FOUR and put THREE there: the quota comes up short (so the give-up branch must still sweep what
            // was broken), the first drop is at the bot's feet with its pickup delay still running, and the other two
            // are at the bottom of holes several blocks apart. The journey's iron rung asks for four ores out of a vein
            // that does not hold four — the short quota IS the normal case.
            bot.mine(List.of("minecraft:iron_ore"), 4, 12);
        });
        game.await().within(1_200).as("the mine ends").until(() -> !bot.busy());

        game.runOnServer(s -> {
            ServerLevel level = bot.level();
            ServerPlayer fp = bot.player();
            int banked = fp.getInventory().countItem(Items.RAW_IRON);
            int drops = 0;
            StringBuilder where = new StringBuilder();
            for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, Staging.entityBox(plot.at(0, 0, 0))))
                if (it.getItem().is(Items.RAW_IRON)) {
                    drops += it.getItem().getCount();
                    BlockPos cell = it.blockPosition();
                    where.append(" drop@").append(rel(plot, cell))
                         .append("(d=").append(String.format(Locale.ROOT, "%.1f", Math.sqrt(it.distanceToSqr(fp))))
                         .append(",above=").append(level.getBlockState(cell.above()).getBlock())
                         .append(",openSides=").append(openSides(level, cell)).append(")");
                }
            ControlContracts.BotStatus status = bot.status();
            ControlContracts.Slot mine = status.slots().mine();
            StringBuilder left = new StringBuilder();
            int standing = 0;
            for (BlockPos at : ores) {
                boolean ore = level.getBlockState(at).is(Blocks.IRON_ORE);
                if (ore) standing++;
                left.append(left.length() == 0 ? "" : ",").append(ore ? "ore" : "air");
            }
            String diag = " [diag oresLeft=" + left + " banked=" + banked + " drops=" + drops
                    + " held=" + fp.getMainHandItem().getItem() + " idle=" + !status.busy()
                    + " lastError=" + mine.lastError().orElse(null) + " endReason=" + mine.endReason().orElse(null)
                    + " bot@" + rel(plot, fp.blockPosition()) + " end=" + status.lastProcessEnd()
                    + " collectPath=" + mine.pathStep() + "/" + mine.pathLen() + where + "]";
            assertEquals(0, standing, "mineHarvest rig: " + standing + " of 3 ores were never broken" + diag);
            assertFalse(status.busy(), "mineHarvest: the mine did not finish and go idle" + diag);
            assertTrue(banked >= 3, (buried ? "mineHarvestBuried" : "mineHarvest") + ": 3 ores broke but only " + banked
                    + " raw iron reached the inventory — "
                    + (drops > 0 ? "the drop exists and was not collected" : "no drop was ever created") + diag);
            // A finished command must say how it ended: `active:false` with no error reads as success, and this verb
            // can end clean having banked nothing.
            assertTrue(mine.endReason().isPresent(), "mineHarvest: the mine finished without stating a terminal verdict"
                    + diag);
        });
    }

    /** {@code at}'s plot-relative coordinates. */
    private static int[] rel3(Plot plot, BlockPos at) {
        BlockPos o = plot.at(0, 0, 0);
        return new int[]{at.getX() - o.getX(), at.getY() - o.getY(), at.getZ() - o.getZ()};
    }

    /** How many of a cell's six neighbours are non-solid — 0 means the item is walled in. */
    private static int openSides(ServerLevel level, BlockPos cell) {
        int open = 0;
        for (Direction d : Direction.values())
            if (!level.getBlockState(cell.relative(d)).isSolidRender(level, cell.relative(d))) open++;
        return open;
    }

    /**
     * Dig the block under your own feet and end up one block lower — the most ordinary thing a player does
     * underground. <b>The block must break</b>, and <b>the bot must then descend into the hole</b>: the single-block
     * mine ends on the tick the block turns to air, so the descent is driven explicitly, with the emptied cell as the
     * goal. A goal naming the column, not a height: "be at y=60" is satisfied anywhere, and only a goal naming the
     * column keeps the bot over its own hole.
     *
     * <p>TWO courses, not one: on a bot that had ever run another task, a later mine was silently ignored and the stale
     * task ran instead, finishing against its already-met goal. So the second course is mined AFTER a goto has owned
     * this bot.
     */
    @Test
    void serverSelfShaftDescends(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game).set("allowBreak", true)
                .set("allowPlace", false)    // placing would let it pillar back up and muddy the reading
                .set("navigatorDebug", false).set("pathfinder.sliceMs", 20).set("pathfinder.maxMs", 2000);
        // A solid slab with nowhere to walk down to: the field shape the journey hit — a swamp with the ore straight
        // down — does not have a staircase either.
        plot.fill(-3, -4, -3, 3, 0, 3, Blocks.STONE);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        BlockPos under = plot.at(0, 0, 0);
        BlockPos deeper = under.below();
        int startY = game.onServer(s -> {
            slot(bot.player(), 0, new ItemStack(Items.STONE_PICKAXE));
            return bot.player().blockPosition().getY();
        });

        bot.mineCell(under);
        game.await().within(400).as("the first mine ends").until(() -> !bot.busy());
        boolean brokeFirst = game.onServer(s -> bot.level().getBlockState(under).isAir());
        bot.goTo(Targets.block(under));
        game.await().within(600).as("the descent ends").until(() -> !bot.busy());
        int midY = game.onServer(s -> bot.player().blockPosition().getY());
        bot.mineCell(deeper);
        game.await().within(400).as("the second mine ends").until(() -> !bot.busy());

        game.runOnServer(s -> {
            ServerLevel level = bot.level();
            BlockPos at = bot.player().blockPosition();
            String diag = " [diag under=" + level.getBlockState(under).getBlock() + " deeper="
                    + level.getBlockState(deeper).getBlock() + " startY=" + startY + " midY=" + midY + " endY=" + at.getY()
                    + " bot@" + rel(plot, at) + " onGround=" + bot.player().onGround()
                    + " end=" + bot.status().lastProcessEnd() + "]";
            assertTrue(brokeFirst, "the block under the bot broke — if this is false the descent was never even "
                    + "attempted and the rest of the message is noise" + diag);
            assertTrue(midY < startY, "the bot followed its own shaft down; a player who digs the block beneath them "
                    + "falls in, and every drop from that dig is down there with it" + diag);
            assertTrue(level.getBlockState(deeper).isAir(), "a mine started AFTER another task actually mines — a stale "
                    + "task left running turns every later mine into a no-op that still reports success" + diag);
        });
    }

    /**
     * The bot must not mine what a player could not have touched. Three targets in one slab, at once, so a fix that
     * trades one for another cannot pass: a block <b>sealed</b> in stone (all six faces solid), a block <b>far</b> away
     * but exposed, and a block <b>adjacent</b> to the bot. Only the third may break.
     *
     * <p>Without the gate {@code Level#destroyBlock} breaks anything the bot aims at, at any distance, through any
     * amount of rock — and the drop from a sealed block lands in a 1×1×1 pocket that no pathfinder can ever reach.
     */
    @Test
    void serverBreakNeedsReach(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pin(game).set("allowBreak", true).set("allowPlace", false).set("navigatorDebug", false);
        plot.fill(-3, -4, -3, 12, 0, 3, Blocks.STONE);
        BlockPos sealed = plot.at(0, -2, 0);      // buried, six solid faces
        BlockPos far = plot.at(10, 0, 0);         // exposed, ten blocks away
        BlockPos adjacent = plot.at(1, 0, 0);     // the one a player can reach
        plot.place(0, -2, 0, Blocks.IRON_ORE).place(10, 0, 0, Blocks.IRON_ORE).place(1, 0, 0, Blocks.IRON_ORE);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            ServerLevel level = bot.level();
            ServerPlayer fp = bot.player();
            slot(fp, 0, new ItemStack(Items.DIAMOND_PICKAXE));   // never the excuse
            // Aim and hold at each, generously — a slow-mine needs ticks, and giving the two that must NOT break more
            // ticks than the one that must is the point.
            for (BlockPos at : List.of(sealed, far, adjacent)) {
                bot.press(bot.keys().toolFor(Targets.pos(at)).aimAt(Targets.pos(at)).attack(true));
                bot.step(60);
            }
            bot.release();
            String diag = " [diag sealed=" + level.getBlockState(sealed).getBlock() + " far="
                    + level.getBlockState(far).getBlock() + " adjacent=" + level.getBlockState(adjacent).getBlock()
                    + " eye=" + String.format(Locale.ROOT, "%.1f", fp.getEyePosition().y)
                    + " range=" + String.format(Locale.ROOT, "%.1f", fp.blockInteractionRange()) + "]";
            assertTrue(level.getBlockState(sealed).is(Blocks.IRON_ORE), "a block walled in on all six faces must "
                    + "survive — no ray from any eye can hit it, and its drop would be sealed where nothing can collect it"
                    + diag);
            assertTrue(level.getBlockState(far).is(Blocks.IRON_ORE), "a block ten blocks away must survive — vanilla's "
                    + "own server rejects the dig on distance alone" + diag);
            assertTrue(level.getBlockState(adjacent).isAir(), "and the block right next to the bot must still break, or "
                    + "the gate has simply turned mining off" + diag);
        });
    }

    /**
     * Stand at the bottom of a four-deep shaft with blocks in the bag and get out. The exit half of
     * {@code serverWalkIntoAPit}: the journey measured {@code exit.fromY=54 -> exit.toY=55}, one block in 1200 ticks,
     * with 17 cobblestone in the inventory and placing allowed. "The navigator never planned a pillar" and "it planned
     * one and the bot could not execute the jump-and-place" are different bugs, so the message carries the path length.
     */
    @Test
    void serverPillarsOutOfAPit(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pin(game).set("allowBreak", true)
                .set("allowPlace", true)      // pillaring is the point; forbidding it would be circular
                .set("navigatorDebug", false).set("pathfinder.sliceMs", 20).set("pathfinder.maxMs", 2000);
        final int floorY = 0;
        // Solid ground with a one-wide, four-deep shaft in the middle of it — the exact hole a mining rung leaves.
        plot.fill(-3, floorY - 5, -3, 3, floorY, 3, Blocks.STONE);
        plot.fill(0, floorY - 4, 0, 0, floorY, 0, Blocks.AIR);
        SceneBot bot = SceneBot.spawn(game, plot, 0, floorY - 4, 0);
        int surface = plot.at(0, floorY, 0).getY();
        int startY = game.onServer(s -> {
            slot(bot.player(), 0, new ItemStack(Items.COBBLESTONE, 32));
            return bot.player().blockPosition().getY();
        });
        bot.goTo(Targets.level(surface + 1));
        game.await().within(1_200).as("the walk ends").until(() -> !bot.busy());

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            BlockPos at = fp.blockPosition();
            ControlContracts.Slot walk = bot.gotoSlot();
            BlockPos column = plot.at(0, 0, 0);
            String diag = " [diag startY=" + startY + " endY=" + at.getY() + " surfaceY=" + (surface + 1)
                    + " climbed=" + (at.getY() - startY) + " bot@" + rel(plot, at)
                    + " cobble=" + fp.getInventory().countItem(Items.COBBLESTONE) + " end=" + bot.status().lastProcessEnd()
                    + " path=" + walk.pathStep() + "/" + walk.pathLen() + " endReason=" + walk.endReason().orElse(null) + "]";
            assertTrue(at.getY() >= surface, "the bot pillared out of the shaft it would have dug — a miner that cannot "
                    + "leave its own hole strands every rung after it" + diag);
            // NOT "it spent blocks pillaring": with breaking allowed it may cut a staircase through the shaft wall,
            // which is what a player with a pickaxe does, and is a better answer than pillaring.
            assertTrue(at.getX() != column.getX() || at.getZ() != column.getZ() || at.getY() >= surface,
                    "and it is genuinely out — not still standing in the shaft column at the depth it started" + diag);
        });
    }

    /**
     * Nine deep, one wide, and a ceiling — the shaft an honest mining rung actually leaves. The journey's stone rung
     * sinks nine courses and then mines sideways, so the bot ends under its own roof. This reproduces both and drives
     * the exit the way the journey scripts it: clear {@code feet+2}, then {@code mc.bot.construct} a one-course tower,
     * repeat. A tower does not break; under a roof it is a jump into rock, reported as "stuck (no Y gain)" — a message
     * that reads like a missing capability and is really a missing step in the plan.
     */
    @Test
    void serverTowersOutOfADeepShaft(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pin(game).set("allowBreak", true).set("allowPlace", true).set("navigatorDebug", false)
                .set("pathfinder.sliceMs", 20).set("pathfinder.maxMs", 2000);
        final int floorY = 0;
        plot.fill(-3, floorY - 10, -3, 3, floorY, 3, Blocks.STONE);
        // The shaft, and then one cell of sideways working at the bottom — which is what puts a roof over the bot's
        // head. Standing in the alcove, the column home is a step away and the way up is through stone.
        plot.fill(0, floorY - 9, 0, 0, floorY, 0, Blocks.AIR);
        plot.fill(1, floorY - 9, 0, 1, floorY - 8, 0, Blocks.AIR);
        SceneBot bot = SceneBot.spawn(game, plot, 1, floorY - 9, 0);
        int surface = plot.at(0, floorY, 0).getY();
        int startY = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            // Slot 0, because a tower not told to reach into the bag only scans the hotbar.
            slot(fp, 0, new ItemStack(Items.COBBLESTONE, 32));
            // A stone pickaxe, because the ceiling is stone and the exit is only scriptable if the bot can clear it.
            fp.getInventory().items.set(1, new ItemStack(Items.STONE_PICKAXE));
            return fp.blockPosition().getY();
        });

        // One course at a time: clear feet+2 if it is solid, else land and tower one block.
        for (int budget = 40; budget > 0; budget--) {
            BlockPos at = game.onServer(s -> bot.player().blockPosition());
            if (at.getY() >= surface + 1) break;
            BlockPos ceiling = at.above(2);
            if (game.onServer(s -> bot.level().getBlockState(ceiling).blocksMotion())) {
                bot.mineCell(ceiling);
                game.await().within(400).as("the ceiling mine ends").until(() -> !bot.busy());
                continue;
            }
            // Land before jumping, exactly as the journey's version does. A tower waits for onGround before it starts
            // and counts stuck ticks from zero, so a bot still settling out of the mine that preceded it burns its whole
            // patience falling. Stand still for 40 ticks, as the journey does.
            if (!game.onServer(s -> bot.player().onGround())) {
                bot.cancel();
                game.ticks(40);
                continue;
            }
            bot.tower(at.getY() + 1, "minecraft:cobblestone", false);
            game.await().within(200).as("the tower course ends").until(() -> !bot.busy());
            if (game.onServer(s -> bot.player().blockPosition().getY()) <= at.getY()) break;
        }

        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            BlockPos at = fp.blockPosition();
            String diag = " [diag startY=" + startY + " endY=" + at.getY() + " surfaceY=" + (surface + 1)
                    + " climbed=" + (at.getY() - startY) + " bot@" + rel(plot, at)
                    + " cobble=" + fp.getInventory().countItem(Items.COBBLESTONE)
                    + " builder=" + bot.status().slots().builder().lastError().orElse(null) + "]";
            assertTrue(at.getY() > surface, "the bot climbed nine courses out of a roofed shaft — the exit a mining rung "
                    + "has to make before the next rung can go anywhere" + diag);
        });
    }

    /**
     * Walk to the bottom of a two-deep, one-wide pit six blocks away: the navigation half of
     * {@code serverMineHarvestBuried}, on its own. "Cannot path into a pit" and "can path but the collect logic asks
     * for the wrong cell" produce the same collect verdict and live in different files, so the pit is dug by the test,
     * there is no item and no mining, and the only verb under test is {@code mc.bot.goto} to the pit floor's cell.
     */
    @Test
    void serverWalkIntoAPit(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        walkIntoAPit(game, plot, false);
    }

    /**
     * The same pit, with the miner's own permissions. MineProcess sweeps with breaking AND placing on, and a navigator
     * allowed to place can treat a hole as terrain to bridge rather than a place to stand — so the armed navigator must
     * keep choosing to descend.
     */
    @Test
    void serverWalkIntoAPitArmed(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        walkIntoAPit(game, plot, true);
    }

    private static void walkIntoAPit(Game game, Plot plot, boolean armed) {
        // Unarmed: nothing to dig or pave with, so the only answer the navigator can give is about the pit. Armed:
        // exactly what MineProcess.COLLECT runs under, dirt in hand included.
        Settings.pin(game).set("allowBreak", armed).set("allowPlace", armed).set("navigatorDebug", false)
                .set("pathfinder.sliceMs", 20).set("pathfinder.maxMs", 2000);
        plot.fill(-2, -3, -2, 9, 0, 2, Blocks.STONE);
        // Exactly the hole a bot leaves when it digs down to an ore one course under the floor: one wide, two deep.
        plot.fill(6, -1, 0, 6, 0, 0, Blocks.AIR);
        BlockPos pitBottom = plot.at(6, -1, 0);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        if (armed) {
            game.runOnServer(s -> {
                slot(bot.player(), 0, new ItemStack(Items.DIRT, 16));   // holdPlaceable's pick
                bot.player().getInventory().items.set(1, new ItemStack(Items.STONE_PICKAXE));
            });
        }
        bot.goTo(Targets.block(pitBottom));
        game.await().within(700).as("the walk ends").until(() -> !bot.busy());

        game.runOnServer(s -> {
            BlockPos at = bot.player().blockPosition();
            double away = Math.sqrt(at.distSqr(pitBottom));
            ControlContracts.Slot walk = bot.gotoSlot();
            String diag = " [diag pit=" + rel(plot, pitBottom) + " bot@" + rel(plot, at)
                    + " dist=" + String.format(Locale.ROOT, "%.1f", away) + " end=" + bot.status().lastProcessEnd()
                    + " pathStep=" + walk.pathStep() + "/" + walk.pathLen() + " endReason=" + walk.endReason().orElse(null)
                    + " lastError=" + walk.lastError().orElse(null) + "]";
            assertTrue(at.getY() <= pitBottom.getY(), "the bot got down into the pit — a drop at the bottom of a "
                    + "two-deep hole is only unreachable if this is false" + diag);
            assertTrue(away <= 1.5, "and it got to the pit's own cell, which is where vanilla's pickup magnet would "
                    + "reach an item lying there" + diag);
        });
    }

    /** Gap#2 tool-capability gate: a toolless bot must NOT grind harvest-requiring blocks; it must abort cleanly with
     *  a signal naming the missing tool. */
    @Test
    void serverMineNoTool(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pinUntimed(game).set("allowBreak", true).set("allowPlace", false).set("navigatorDebug", false);
        // DIRT floor (NOT a target, mines fine bare-handed) so the scan only weighs the stone.
        plot.fill(-1, 0, -1, 9, 0, 1, Blocks.DIRT);
        List<BlockPos> targets = List.of(plot.at(2, 1, 0), plot.at(4, 1, 0), plot.at(6, 1, 0));
        plot.place(2, 1, 0, Blocks.STONE).place(4, 1, 0, Blocks.STONE).place(6, 1, 0, Blocks.STONE);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            // NO pickaxe — empty-handed, matching the campaign soft-lock (broken pickaxe, no craft path).
            bot.mine(List.of("minecraft:stone"), 3, 8);
            bot.tickUntilIdle(400);
            int remaining = 0;
            for (BlockPos t : targets) if (!bot.level().getBlockState(t).isAir()) remaining++;
            String err = bot.status().slots().mine().lastError().orElse(null);
            // Must have mined NONE — a harvest-requiring block with no tool yields nothing.
            assertEquals(3, remaining, "toolless mine broke " + (3 - remaining) + "/3 stone for zero drops (should mine "
                    + "none)");
            // Must have aborted cleanly (finished and idle), not spun or ground the quota.
            assertFalse(bot.busy(), "toolless mine did not abort and go idle");
            // Signal must name the missing tool so the planner can act (craft/relocate).
            assertTrue(err != null && err.contains("pickaxe"), "expected a tool-block signal naming a pickaxe, got: " + err);
        });
    }

    /** Gap#2 regression guard: the navigator EXECUTOR clears a bare-hand deepslate plug it lacks the tool for (does NOT
     *  soft-lock). faithfulBreak ON. */
    @Test
    void serverNavigatorDeepslateNoTool(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pinUntimed(game).set("allowBreak", true).set("allowPlace", false).set("navigatorDebug", false)
                // REAL destroy-progress: bare-hand deepslate ~650t (else instant destroyBlock masks the timing)
                .set("faithfulBreak", true);
        // BEDROCK shell (unbreakable → infinite breakCost) so the ONLY finite route is straight through the deepslate
        // plug. Interior corridor: x=0, z=0..4, 2-tall.
        plot.fill(-1, 0, -1, 1, 0, 5, Blocks.BEDROCK);    // floor
        plot.fill(-1, 3, -1, 1, 3, 5, Blocks.BEDROCK);    // ceiling
        plot.fill(-1, 1, -1, -1, 2, 5, Blocks.BEDROCK);   // west wall
        plot.fill(1, 1, -1, 1, 2, 5, Blocks.BEDROCK);     // east wall
        plot.fill(0, 1, -1, 0, 2, -1, Blocks.BEDROCK);    // back cap
        plot.fill(0, 1, 5, 0, 2, 5, Blocks.BEDROCK);      // front cap
        plot.fill(0, 1, 2, 0, 2, 2, Blocks.DEEPSLATE);    // the plug, foot + head
        BlockPos plugFoot = plot.at(0, 1, 2), plugHead = plot.at(0, 2, 2), goal = plot.at(0, 1, 4);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            // NO pickaxe — empty-handed, matching the campaign soft-lock at y-14 deepslate.
            bot.goTo(Targets.block(goal));            // the navigator's own digging, no mine task
            final int budget = 3000;                  // >> navigatorTotalTickBudget(1200); an infinite grind stays active past this
            int endTick = budget;
            for (int t = 0; t < budget; t++) {
                if (!bot.busy()) { endTick = t; break; }
                bot.tick();
            }
            ServerLevel level = bot.level();
            ServerPlayer fp = bot.player();
            int plugRemaining = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean reached = Math.abs(fp.getZ() - (goal.getZ() + 0.5)) < 1.5 && fp.getY() >= goal.getY() - 0.4;
            // A reset-stall regression (dig never completes) would leave the plug solid and the bot short of the goal —
            // reached/plugRemaining catch it, where a bare idle check would not.
            assertTrue(reached && plugRemaining == 0, "Navigator did not clear bare-hand deepslate to the goal: reached="
                    + reached + " plugRemaining=" + plugRemaining + "/2 end=" + bot.status().lastProcessEnd()
                    + " endTick=" + endTick + " pos=" + pos(fp));
        });
    }

    /**
     * Per-goto forbidDig (NoBreak) against a solid wall = clean planner give-up (phase A), and the precision guard:
     * without forbidDig the bot digs through and reaches (phase B). The plug sits 6 CLEAR cells from the start, so the
     * best-effort partial is multi-node and the navigator WALKS it to the wall face, where wallDig can engage; the
     * leashed armor stand sits 3 cells beyond it, inside the leash radius, so the leash pulls forward.
     */
    @Test
    void serverForbidDigWall(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        final int plugDz = 6, standDz = 9;
        final double leashRadius = 16.0;
        final int budget = 1600;   // > navigatorTotalTickBudget(1200); wallDig fires within ~40t of engaging
        Settings.pinUntimed(game).set("allowBreak", true).set("allowPlace", false).set("navigatorDebug", false)
                .set("navigatorWallDigFallback", true);   // match the live default that produced day6
        // The BEDROCK shell corridor (only route = straight through the plug).
        plot.fill(-1, 0, -1, 1, 0, standDz + 2, Blocks.BEDROCK);
        plot.fill(-1, 3, -1, 1, 3, standDz + 2, Blocks.BEDROCK);
        plot.fill(-1, 1, -1, -1, 2, standDz + 2, Blocks.BEDROCK);
        plot.fill(1, 1, -1, 1, 2, standDz + 2, Blocks.BEDROCK);
        plot.fill(0, 1, -1, 0, 2, -1, Blocks.BEDROCK);
        plot.fill(0, 1, standDz + 2, 0, 2, standDz + 2, Blocks.BEDROCK);
        BlockPos plugFoot = plot.at(0, 1, plugDz), plugHead = plot.at(0, 2, plugDz);
        BlockPos standCell = plot.at(0, 1, standDz);
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            ArmorStand stand = new ArmorStand(level, standCell.getX() + 0.5, standCell.getY(), standCell.getZ() + 0.5);
            stand.setNoGravity(true);
            level.addFreshEntity(stand);
        });
        // A hard leash on the stand: inside its radius from the start, so it pulls forward rather than holding back.
        Routes leashed = Routes.plain().leashEntity("minecraft:armor_stand", leashRadius, true, 0);
        game.await().within(100).as("the armor stand is indexed").until(() -> !game.onServer(s -> s.getLevel(plot.dimension())
                .getEntitiesOfClass(ArmorStand.class, Staging.entityBox(plot.at(0, 0, 0))).isEmpty()));

        // ---- Phase A: forbidDig (NoBreak) — the plug MUST survive, the bot must NOT get past it. ----
        plot.fill(0, 1, 0, 0, 2, standDz + 1, Blocks.AIR);
        plot.fill(0, 1, plugDz, 0, 2, plugDz, Blocks.DIRT);
        SceneBot botA = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            ServerPlayer fpA = botA.player();
            slot(fpA, 0, new ItemStack(Items.STONE_PICKAXE));   // NOT tool-gated: only forbidDig can stop the dig
            botA.goTo(Targets.near(standCell, 1), leashed.noBreak().build());
            int endA = -1;
            for (int t = 0; t < budget; t++) {
                if (!botA.busy()) { endA = t; break; }
                botA.tick();
            }
            ServerLevel level = botA.level();
            int plugA = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean gotPastA = fpA.getZ() > plugFoot.getZ() + 1.0;   // past the plug = tunnelled through
            assertTrue(plugA == 2 && !gotPastA, "forbidDig LEAK: executor dig fallback punched the wall despite NoBreak — "
                    + "plugRemaining=" + plugA + "/2 (want 2) gotPast=" + gotPastA + " (want false) end="
                    + botA.status().lastProcessEnd() + " endTick=" + endA + " pos=" + pos(fpA));
        });
        botA.remove();   // so the first bot can't linger into phase B

        // ---- Phase B: precision — SAME rig, NO forbidDig — must dig through and REACH the stand. ----
        plot.fill(0, 1, 0, 0, 2, standDz + 1, Blocks.AIR);
        plot.fill(0, 1, plugDz, 0, 2, plugDz, Blocks.DIRT);
        SceneBot botB = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            ServerPlayer fpB = botB.player();
            slot(fpB, 0, new ItemStack(Items.STONE_PICKAXE));
            botB.goTo(Targets.near(standCell, 1), leashed.build());   // no NoBreak = digging allowed
            int endB = -1;
            for (int t = 0; t < budget; t++) {
                if (!botB.busy()) { endB = t; break; }
                botB.tick();
            }
            ServerLevel level = botB.level();
            int plugB = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean reachedB = Math.abs(fpB.getX() - (standCell.getX() + 0.5)) < 1.5
                    && Math.abs(fpB.getZ() - (standCell.getZ() + 0.5)) < 2.0;
            assertTrue(reachedB && plugB == 0, "precision guard: without forbidDig the bot must dig through and reach the "
                    + "stand — reached=" + reachedB + " (want true) plugRemaining=" + plugB + "/2 (want 0) end="
                    + botB.status().lastProcessEnd() + " endTick=" + endB + " pos=" + pos(fpB));
        });
    }

    // ---------------------------------------------------------------- build / look / follow / combat

    /** The server bot places a two-block schematic through {@code mc.bot.build}. */
    @Test
    void serverBuild(Game game, @Plot.Centered Plot plot) {
        Settings.pinUntimed(game).set("allowBreak", false).set("allowPlace", true).set("navigatorDebug", false);
        plot.fill(-1, 0, -1, 6, 0, 1, Blocks.DIRT);
        BlockPos origin = plot.at(0, 0, 0), t1 = plot.at(2, 1, 0), t2 = plot.at(3, 1, 0);
        // 4×2×1 with cobblestone at (2,1,0) and (3,1,0): [dx, dy, dz, palette index].
        BuildContracts.Blocks schem = new BuildContracts.Blocks(4, 2, 1, List.of("minecraft:cobblestone"),
                List.of(List.of(2, 1, 0, 0), List.of(3, 1, 0, 0)));
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            slot(bot.player(), 0, new ItemStack(Blocks.COBBLESTONE, 64));
            Calls.started("build", game.api(BotTaskBlockingApi::of).build(
                    new BuildContracts.BuildParams(bot.ref(), Targets.pos(origin), schem, 0)));
            bot.tickUntilIdle(400);
            ServerLevel level = bot.level();
            assertTrue(level.getBlockState(t1).is(Blocks.COBBLESTONE) && level.getBlockState(t2).is(Blocks.COBBLESTONE),
                    "server build failed to place both cobble: t1=" + level.getBlockState(t1) + " t2=" + level.getBlockState(t2));
            assertFalse(bot.busy(), "build did not finish and go idle");
        });
    }

    /** The body's eye→view clip raycast ({@code mc.debug.body} hands.lookingAt). */
    @Test
    void serverLookRaycast(Game game, @Plot.Centered Plot plot) {
        Settings settings = Settings.pin(game);
        plot.fill(-1, 0, 0, 4, 0, 0, Blocks.DIRT);
        plot.place(2, 1, 0, Blocks.STONE);   // a stone 2 cells east at foot height
        BlockPos target = plot.at(2, 1, 0);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        settings.set("smoothLook", false);   // turn at once, so the read below sees the new aim
        bot.lookAt(target);                  // sets yaw/pitch toward the cell's centre
        BlockPos look = bot.body().hands().lookingAt().map(Targets::cell).orElse(null);   // eye→view clip raycast
        assertEquals(target, look, "server lookingAtBlock did not resolve the aimed cell");
    }

    /** The server bot runs {@code mc.bot.follow} (entity-sensing) onto an armor stand. */
    @Test
    void serverFollow(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pinUntimed(game).set("navigatorDebug", false);
        plot.fill(-2, 0, -2, 12, 0, 2, Blocks.STONE);
        BlockPos standAt = plot.at(8, 1, 0);
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            ArmorStand stand = new ArmorStand(level, standAt.getX() + 0.5, standAt.getY(), standAt.getZ() + 0.5);
            stand.setNoGravity(true);
            level.addFreshEntity(stand);
        });
        game.await().within(100).as("the armor stand is indexed").until(() -> !game.onServer(s -> s.getLevel(plot.dimension())
                .getEntitiesOfClass(ArmorStand.class, Staging.entityBox(plot.at(0, 0, 0))).isEmpty()));
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            // Follow the nearest armor stand to within 2, never idling out, giving up after 600 ticks; plain route.
            Calls.started("follow", game.api(BotTaskBlockingApi::of).follow(new MoveContracts.FollowParams(bot.ref(),
                    new MoveContracts.QuarryType("minecraft:armor_stand"), 2, 0, 600, Optional.empty(), 0)));
            bot.tickUntilIdle(200);
            ServerPlayer fp = bot.player();
            double dx = fp.getX() - (standAt.getX() + 0.5), dz = fp.getZ() - (standAt.getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dz * dz);
            assertTrue(dist <= 3.0, "server follow did not close on the armor stand: dist=" + dist);   // radius 2 + slack
        });
    }

    /**
     * After the kill, the combat loop must SWEEP the drops near the kill spot before terminating (live 2026-07-21: six
     * hunt kills banked one porkchop — melee kites away from the corpse and the old loop ended wherever it stood).
     *
     * <p>The "drop" is deterministic: an ItemEntity pre-placed 3 blocks BEYOND the zombie (x=9; within the sweep's
     * 8-block scan of the kill spot x=6, but a spot the pre-fix loop had no reason to ever visit — it fought at reach
     * range and terminated in place), rather than the zombie's own RNG loot. PASS = the process finishes AND the drop
     * was swept: picked up, or the bot finished standing at it.
     */
    @Test
    void serverCombatCollectDrops(Game game, @Plot.Centered @Plot.Size(32) Plot plot) {
        Settings.pinUntimed(game).set("navigatorDebug", false);
        plot.fill(-2, 0, -2, 10, 0, 2, Blocks.STONE);
        BlockPos zombieAt = plot.at(6, 1, 0), dropAt = plot.at(9, 1, 0);
        int[] ids = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            Zombie zombie = new Zombie(level);
            zombie.setPos(zombieAt.getX() + 0.5, zombieAt.getY(), zombieAt.getZ() + 0.5);
            zombie.setNoAi(true);
            zombie.setPersistenceRequired();
            var kbr = zombie.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            if (kbr != null) kbr.setBaseValue(1.0);
            level.addFreshEntity(zombie);
            ItemEntity drop = new ItemEntity(level, dropAt.getX() + 0.5, dropAt.getY(), dropAt.getZ() + 0.5,
                    new ItemStack(Items.PORKCHOP, 3));
            drop.setDeltaMovement(Vec3.ZERO);
            drop.setNoGravity(true);   // stays put without needing item ticks
            level.addFreshEntity(drop);
            return new int[]{zombie.getId(), drop.getId()};
        });
        game.await().within(100).as("the zombie and the drop are indexed").until(() -> game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            return !level.getEntitiesOfClass(Zombie.class, Staging.entityBox(plot.at(0, 0, 0))).isEmpty()
                    && !level.getEntitiesOfClass(ItemEntity.class, Staging.entityBox(plot.at(0, 0, 0))).isEmpty();
        }));
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        bot.give(Items.IRON_SWORD, 1);
        game.runOnServer(s -> {
            ServerLevel level = bot.level();
            Zombie zombie = (Zombie) level.getEntity(ids[0]);
            ItemEntity drop = (ItemEntity) level.getEntity(ids[1]);
            // By TYPE (scans Level.getEntities) rather than by id — the same melee loop either way.
            Calls.started("combat", game.api(BotTaskBlockingApi::of).combat(new CombatContracts.Params(bot.ref(),
                    CombatContracts.Mode.KILL, Optional.of(new CombatContracts.FoeType("minecraft:zombie")), false, 0)));
            int killedAt = -1, endedAt = -1;
            double killX = Double.NaN;
            for (int t = 0; t < 1500 && bot.busy(); t++) {
                bot.tick();
                if (killedAt < 0 && !zombie.isAlive()) {
                    killedAt = t;
                    killX = bot.player().getX();
                }
                endedAt = t;
                if (zombie.isAlive()) {
                    zombie.tick();
                    zombie.setPos(zombieAt.getX() + 0.5, zombieAt.getY(), zombieAt.getZ() + 0.5);
                    zombie.setDeltaMovement(Vec3.ZERO);
                }
            }
            ServerPlayer fp = bot.player();
            boolean pickedUp = drop == null || !drop.isAlive() || fp.getInventory().countItem(Items.PORKCHOP) > 0;
            double distToDrop = Math.sqrt(fp.distanceToSqr(dropAt.getX() + 0.5, dropAt.getY(), dropAt.getZ() + 0.5));
            ControlContracts.BotStatus status = bot.status();
            String diag = " [diag killedAt=" + killedAt + " endedAt=" + endedAt
                    + " sweepTicks=" + (killedAt >= 0 ? endedAt - killedAt : -1) + " killX=" + killX + " endPos=" + pos(fp)
                    + " dropAlive=" + (drop != null && drop.isAlive()) + " end=" + status.lastProcessEnd()
                    + " goal=" + status.slots().combat().goal().orElse(null) + "]";
            assertFalse(zombie.isAlive(), "collectDrops rig: zombie not killed: hp=" + zombie.getHealth() + diag);
            assertFalse(status.busy(), "collectDrops: combat did not finish and go idle" + diag);
            assertTrue(pickedUp || distToDrop <= 2.0, "collectDrops: drop NOT swept — bot ended " + distToDrop
                    + " blocks from the drop (pre-fix behaviour: terminate at the kill spot)" + diag);
        });
    }

    /** The server bot runs a smooth {@code mc.bot.lookAt} (pure yaw/pitch) as a task. */
    @Test
    void serverLook(Game game, @Plot.Centered Plot plot) {
        // A look is only a task when it turns smoothly; an instant one answers before it returns.
        Settings.pin(game).set("navigatorDebug", false).set("smoothLook", true);
        plot.fill(-1, 0, -1, 1, 0, 1, Blocks.STONE);
        BlockPos track = plot.at(5, 1, 0);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        game.runOnServer(s -> {
            bot.lookAt(track);
            bot.tickUntilIdle(300);
            ServerPlayer fp = bot.player();
            Vec3 eye = fp.getEyePosition();
            double dx = track.getX() + 0.5 - eye.x, dy = track.getY() + 0.5 - eye.y, dz = track.getZ() + 0.5 - eye.z;
            float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            float yawErr = Math.abs(Mth.degreesDifference(fp.getYRot(), ty));
            float pitchErr = Math.abs(tp - fp.getXRot());
            assertFalse(bot.busy(), "server look did not align+finish: lastError="
                    + bot.status().slots().look().lastError().orElse(null));
            assertTrue(yawErr <= 2f && pitchErr <= 2f, "server look off target: yawErr=" + yawErr + " pitchErr=" + pitchErr);
        });
    }

    // ---------------------------------------------------------------- the trunk-tax waiver's state machine
    //
    // `pathfinderLogBreakTax` is waived while a MineProcess is working a log, so the walk that goes to fetch the fifth
    // log of a trunk may plan through the four under it. The waiver is DERIVED from the current target rather than
    // latched beside it, and the two things most likely to be wrong are the derivation (does it lift when the goal stops
    // being a log?) and the release (does a bot that dies up a tree leave it set forever?). Both are testable here. The
    // MULTIPLICATION is not, and no test below claims it is: the tax lives in ClientWorldView, and the server-side player
    // plans through LevelWorldView, which applies no break taxes at all. That half belongs to the live ladder.
    //
    // Each test OPENS by asserting the waiver is off. A leaked owner from anything earlier then fails here, by name,
    // instead of quietly answering the next test's question.

    /** One reachable log, plus the floor to walk it, shared by all three tests so the only difference between them is
     *  what the process is asked to do. */
    private static BlockPos stageOneLog(Plot plot) {
        plot.fill(-1, 0, 0, 7, 0, 0, Blocks.DIRT);
        plot.place(5, 1, 0, Blocks.OAK_LOG);
        return plot.at(5, 1, 0);
    }

    /** Whether the mine's log-cost waiver is held, as {@code mc.debug.body} reads it. */
    private static boolean waiverOn(SceneBot bot) { return bot.body().logWaiver(); }

    /** The guard every waiver test opens with, read before the bot is given anything to do. */
    private static void waiverNotLeakedIn(SceneBot bot) {
        if (waiverOn(bot)) {
            fail("the waiver was already on when the test started; another test leaked its owner. Any 'waiver on' "
                    + "reading in this run would not be caused by this test and cannot count as evidence.");
        }
    }

    /** The waiver is ON while a log goal is in flight, and OFF once the order is over. Both halves matter: an always-on
     *  waiver would pass the first half alone. */
    @Test
    void mineLogWaiverEngages(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        waiverNotLeakedIn(bot);
        BlockPos logPos = stageOneLog(plot);
        settings.set("allowBreak", true).set("allowPlace", false);
        game.runOnServer(s -> {
            slot(bot.player(), 0, new ItemStack(Items.WOODEN_AXE));
            bot.mine(List.of("#minecraft:logs"), 1, 16);
            int onTicks = 0;
            for (int t = 0; t < 600 && bot.busy(); t++) {
                bot.tick();
                if (waiverOn(bot)) onTicks++;
            }
            boolean mined = !bot.level().getBlockState(logPos).is(Blocks.OAK_LOG);
            boolean stillOn = waiverOn(bot);
            reporter.publishEntry("gate", "onTicks=" + onTicks + " mined=" + mined + " stillOn=" + stillOn);
            // Order matters. "The waiver never came on" and "the bot never reached a log" are the same reading, and
            // only one of them is a defect — so ask about the log first.
            assertTrue(mined, "this run never mined the log, so 'the waiver was off' proves nothing (not triggered, not a "
                    + "counterexample). lastError=" + bot.status().slots().mine().lastError().orElse(null));
            assertTrue(onTicks > 0, "the log was mined, but no tick in the run read the waiver as on; the derivation is "
                    + "not wired, so the approach to the log did not get the pricing it should have.");
            assertFalse(stillOn, "the waiver is still on after the order ended; it would carry over into the bot's next "
                    + "route, which is exactly what the tax exists to prevent.");
        });
    }

    /** The control arm. Same rig, same log standing in the world, only the order differs: mine STONE. The waiver must
     *  never come on, and the stone must actually get mined, or "never on" is just "never ran". */
    @Test
    void mineStoneLeavesTheWaiverOff(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        waiverNotLeakedIn(bot);
        // The log is STAGED and left standing: a log in the world is not what arms the waiver — a log GOAL is.
        BlockPos logPos = stageOneLog(plot);
        plot.place(3, 1, 0, Blocks.STONE);
        BlockPos stonePos = plot.at(3, 1, 0);
        settings.set("allowBreak", true).set("allowPlace", false);
        game.runOnServer(s -> {
            slot(bot.player(), 0, new ItemStack(Items.STONE_PICKAXE));
            bot.mine(List.of("minecraft:stone"), 1, 16);
            int onTicks = 0;
            for (int t = 0; t < 600 && bot.busy(); t++) {
                bot.tick();
                if (waiverOn(bot)) onTicks++;
            }
            boolean stoneGone = !bot.level().getBlockState(stonePos).is(Blocks.STONE);
            reporter.publishEntry("gate", "onTicks=" + onTicks + " stoneMined=" + stoneGone + " logStanding="
                    + bot.level().getBlockState(logPos).is(Blocks.OAK_LOG));
            assertTrue(stoneGone, "the stone was not mined, so 'the waiver stayed off throughout' only means 'this run "
                    + "did nothing'; it is not a control. lastError=" + bot.status().slots().mine().lastError().orElse(null));
            assertEquals(0, onTicks, "the stone-mining order turned the log-tax waiver on; a log standing in the world "
                    + "must not arm it, only a log goal may.");
        });
    }

    /**
     * The leak test. It supersedes the order through the SAME door production takes (a new {@code mc.bot.*} order),
     * not by calling onCancelled directly: a test that pokes the method under test would go green against a funnel
     * nothing actually uses. It also proves the waiver was ON first, because 0 -> 0 is not a release.
     */
    @Test
    void mineLogWaiverReleasedOnSupersede(Game game, @Plot.Centered Plot plot, TestReporter reporter) {
        Settings settings = Settings.pin(game);
        SceneBot bot = SceneBot.spawn(game, plot, 0, 1, 0);
        waiverNotLeakedIn(bot);
        stageOneLog(plot);
        settings.set("allowBreak", true).set("allowPlace", false);
        BlockPos home = plot.at(0, 1, 0);
        game.runOnServer(s -> {
            slot(bot.player(), 0, new ItemStack(Items.WOODEN_AXE));
            bot.mine(List.of("#minecraft:logs"), 1, 16);
            // Tick only until the waiver is observed on — superseding after the log is already down would test nothing
            // (the waiver would have lapsed on its own).
            int armedAt = -1;
            for (int t = 0; t < 400 && bot.busy() && armedAt < 0; t++) {
                bot.tick();
                if (waiverOn(bot)) armedAt = t;
            }
            reporter.publishEntry("gate.armedAt", String.valueOf(armedAt));
            assertTrue(armedAt >= 0, "the waiver never came on within 400 ticks, so 'it is off after cancellation' would "
                    + "be 0 -> 0 and cannot prove a release (not triggered).");
            bot.goTo(Targets.block(home));   // the production door: hand it a new order
            assertFalse(waiverOn(bot), "the order was replaced but the waiver is still on; a mining order superseded "
                    + "midway would leave the 1.0 pricing on every later route this bot plans.");
        });
    }

    // ---------------------------------------------------------------- bridge from a pillar top

    /**
     * Gap#75-a (live death #24): a bridge from a 1×1 pillar top. Arm A is the live death shape — a sneak-overhang start
     * (centre 0.55 east of the pillar cell's middle, so floor(centre) is already the void column) with the yaw 90° off;
     * arm B the centred baseline, 16 south.
     */
    @Test
    void serverBridgePillarStart(Game game, @Plot.Centered @Plot.Size(x = 32, y = 32, z = 48) Plot plot) {
        Settings.pin(game).set("navigatorDebug", false);
        bridgePillarLeg(game, plot, 0, 0.55, 90f, "A(overhang)");
        bridgePillarLeg(game, plot, 16, 0.0, 270f, "B(centered)");
    }

    /** One bridge-from-pillar-top run: 1×1 cobblestone pillar (6 high) over a catch floor, the bot on top,
     *  {@code mc.bot.construct} a bridge east ×4. Asserts: feet never drop below pillar-top−1, 4 bridge blocks laid,
     *  process ends {@code done}. */
    private static void bridgePillarLeg(Game game, Plot plot, int dz, double xOff, float startYaw, String leg) {
        final int floorY = 0, pillarH = 6, topY = floorY + pillarH, distance = 4;
        plot.fill(-3, floorY, dz - 3, 9, floorY, dz + 3, Blocks.STONE);
        plot.fill(0, floorY + 1, dz, 0, topY, dz, Blocks.COBBLESTONE);
        BlockPos pillar = plot.at(0, topY + 1, dz);
        final double feetY = pillar.getY();
        SceneBot bot = SceneBot.spawn(game, plot.dimension(), pillar.getX() + 0.5 + xOff, feetY, pillar.getZ() + 0.5);
        game.runOnServer(s -> {
            ServerPlayer fp = bot.player();
            fp.setYRot(startYaw);
            fp.yHeadRot = startYaw;
            fp.yBodyRot = startYaw;
            fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            fp.getInventory().selected = 0;
            Calls.started("bridge", game.api(BotTaskBlockingApi::of).construct(new BuildContracts.ConstructParams(bot.ref(),
                    new BuildContracts.Bridge(BuildContracts.BridgeDirection.EAST, distance, Optional.of("minecraft:cobblestone")),
                    0)));
            double minY = fp.getY();
            for (int t = 0; t < 400 && bot.busy(); t++) {
                bot.tick();
                minY = Math.min(minY, fp.getY());
            }
            boolean finished = !bot.busy();
            String lastErr = bot.status().slots().builder().lastError().orElse(null);
            StringBuilder laid = new StringBuilder();
            int laidCount = 0;
            for (int i = 1; i <= distance; i++) {
                boolean solid = bot.level().getBlockState(plot.at(i, topY, dz)).blocksMotion();
                if (solid) laidCount++;
                laid.append(solid ? '#' : '.');
            }
            assertTrue(minY >= feetY - 1.0, "arm " + leg + ": bot dropped below pillar-top-1 (minY=" + minY
                    + ", start feetY=" + feetY + ") — bridge start fell off the pillar (death #24 shape)");
            assertEquals(distance, laidCount, "arm " + leg + ": bridge only laid " + laidCount + "/" + distance + " ("
                    + laid + ") — lastErr=" + lastErr);
            assertTrue(finished && lastErr != null && lastErr.startsWith("done"), "arm " + leg + ": process did not "
                    + "reach the done terminal: finished=" + finished + " lastErr=" + lastErr);
        });
        bot.remove();
    }
}

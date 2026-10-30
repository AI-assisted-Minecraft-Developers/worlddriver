package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * The one test that lets a furnace actually cook. Every other smelt test drives the bot inside ONE server tick, which
 * is fast and correct for testing slot plumbing and structurally unable to test smelting — so the suite was green over
 * a smelt that produced ingots and delivered none. Needs real ticks (200 per item).
 */
@Scene("wd.scenes")
@Isolated // see ProcessTest: these tests hold the server thread for long spans
@Execution(ExecutionMode.SAME_THREAD)
class SmeltDeliveryTest {

    /**
     * Smelt an ore end to end and put the ingot in the BAG — then take the bag away and require the process to say so.
     *
     * <p><b>Why this is the first test that can.</b> {@code serverSmeltFurnaceHoleRim} and {@code smeltFuelPolicy} both
     * drive up to 400 bot ticks inside a SINGLE server tick, so no furnace tick ever fires; one asserts the load and
     * the other injects the result by hand and says so in its own javadoc. This one waits on real server ticks — 200
     * per item — so the block entity cooks, and it asks the question the journey's iron rung asks: how many ingots are
     * in the bot's inventory.
     *
     * <p><b>Phase 2 is the bug this test was written for.</b> {@code SmeltProcess.collect()} takes the result back with
     * a shift-click, and {@code AbstractFurnaceMenu.quickMoveStack} → {@code moveItemStackTo(stack, 3, 39, true)} moves
     * NOTHING and returns false when every player slot is taken. The process then reported DONE with {@code lastError}
     * null: ingots made, ingots stranded in the furnace, caller told nothing. Intermittent live for a reason that is
     * not about smelting at all — the bot stands beside the furnace for a thousand ticks with
     * {@code ServerPlayerBody}'s pickup loop running, so the slot its own ore vacated at LOAD refills with whatever the
     * mining rung left on the ground. So phase 2 plugs every free slot at the moment the ore enters the furnace —
     * deterministically, and before the first ingot exists, which is what keeps it out of a race with COLLECT — and
     * requires the run to come back with a reason instead of a silent success.
     *
     * <p>The furnace is PLACED by the bot from its own bag rather than staged by the test, so the place → open → load →
     * cook → collect path is exercised whole; phase 2 then reuses the furnace left standing. (A furnace left loaded is
     * fine: the plot's restore discards whatever its removal drops.)
     */
    @Test
    void serverSmeltDeliversTheIngot(Game game, @Plot.Centered Plot plot) {
        Settings.pin(game).set("navigatorDebug", false);
        plot.fill(-3, 0, -3, 3, 0, 3, Blocks.STONE);

        SceneBot bot = SceneBot.spawn(game, plot.at(0, 1, 0));
        bot.give(Items.FURNACE, 1).give(Items.RAW_IRON, 2).give(Items.COAL, 2);

        bot.smelt("minecraft:raw_iron", 2);
        // 200 ticks an item plus the place/open/load round trips; a run that spends the whole of this has found a
        // furnace that never cooked, which the process now names for itself.
        game.await().within(900).as("the first smelt ends").until(() -> !bot.busy());
        BlockPos fpos = game.onServer(s -> findBlockNear(bot.level(), plot, Blocks.FURNACE));
        assertNotNull(fpos, "the bot placed its own furnace and it is still standing");
        int ingots = game.onServer(s -> Staging.countItem(bot.player(), Items.IRON_INGOT));
        String phase1 = game.onServer(s -> "rawIronLeft=" + Staging.countItem(bot.player(), Items.RAW_IRON)
                + " lastError=" + bot.status().slots().smelt().lastError().orElse("null") + " furnaceHolds="
                + containerSummary(bot.level(), fpos));
        game.check(
                ingots >= 2,
                "iron ingots in the BOT'S BAG after a smelt that really cooked: " + ingots + "; " + phase1
                        + " (a furnace still holding them is the bug)");

        // ---- phase 2: the same smelt with nowhere to put the result ----
        // Phase 1's ingots have to GO. A full bag is not full for an item it already holds a partial stack of —
        // moveItemStackTo merges before it looks for an empty slot — so leaving them here made the plug below leak:
        // measured, 35 slots plugged and the third ingot still landed, on top of the two. The bot that hits this live
        // is smelting its first iron, which is exactly this arrangement.
        game.runOnServer(s -> {
            takeAll(bot.player(), Items.IRON_INGOT);
            bot.player().getInventory().add(new ItemStack(Items.RAW_IRON, 1));
            bot.smelt("minecraft:raw_iron", 1);
        });
        Container furnace = game.onServer(s -> bot.level().getBlockEntity(fpos) instanceof Container c ? c : null);
        assertNotNull(furnace, "the placed furnace is a container");
        // Plug the bag the moment the ore ENTERS the furnace, not when the ingot appears: the ore leaving the bag is
        // what frees the slot, and doing it now keeps this out of a race with COLLECT (which fires on the same tick the
        // last ingot is made). The plug runs inside the await's own server-thread check, so no tick falls between.
        int[] plugged = {-1};
        game.await()
                .within(300)
                .as("the ore enters the furnace, or the smelt ends")
                .until(() -> {
                    if (furnace.getItem(0).isEmpty() && bot.busy()) return false;
                    plugged[0] = fillEveryFreeSlot(bot.player());
                    return true;
                });
        game.check(
                game.onServer(s -> freeSlots(bot.player())) == 0,
                "the bag really is full (plugged " + plugged[0]
                        + ") — a phase that cannot block the delivery proves nothing about reporting one");
        game.await().within(600).as("the second smelt ends").until(() -> !bot.busy());
        String err = game.onServer(s -> bot.status().slots().smelt().lastError().orElse(null));
        int delivered = game.onServer(s -> Staging.countItem(bot.player(), Items.IRON_INGOT));
        int inFurnace = game.onServer(s -> furnace.getItem(2).getCount());
        String holds = game.onServer(s -> containerSummary(bot.level(), fpos));
        // Nothing may EVAPORATE: the ingot is in the bag or still in the furnace.
        game.check(
                delivered >= 1 || inFurnace >= 1,
                "the smelt's ingot is either in the bag or still in the furnace: " + "bag=" + delivered
                        + " furnaceHolds=" + holds);
        // And the contract this test exists for: hand it back, or say why not. Reporting done with lastError=null while
        // the ingots sit in the furnace is how a smelt that worked reads as a mining failure four rungs downstream.
        game.check(
                delivered >= 1 || err != null,
                "a smelt that cooked an ingot it could not hand back must SAY so: " + "lastError=" + err
                        + " furnaceHolds=" + holds);
    }

    /**
     * A bot teleported sixty blocks from its furnace mid-smelt must fail the smelt for being away, and must not take
     * the furnace's contents from there: vanilla refuses a container to a player beyond its interaction range plus 4. A
     * collect from sixty blocks away once succeeded.
     *
     * <p>Also published, not asserted: on which tick after the move the bot's open menu first reads as its inventory
     * menu, and whether the smelt was still running then — whether the player's own tick closes a menu out of reach
     * before the smelt notices.
     */
    @Test
    void serverSmeltFailsWhenTheBotLeaves(
            Game game, @Plot.Centered @Plot.Size(x = 80, y = 16, z = 16) Plot plot, TestReporter reporter) {
        Settings.pin(game).set("navigatorDebug", false);
        plot.fill(-33, 0, -3, -27, 0, 3, Blocks.STONE);
        plot.fill(27, 0, -1, 33, 0, 1, Blocks.STONE);
        SceneBot bot = SceneBot.spawn(game, plot.at(-30, 1, 0));
        bot.give(Items.FURNACE, 1).give(Items.RAW_IRON, 3).give(Items.COAL, 2);

        bot.smelt("minecraft:raw_iron", 3);
        BlockPos[] fpos = {null};
        game.await()
                .within(300)
                .as("the ore enters the bot's furnace, or the smelt ends")
                .until(() -> {
                    fpos[0] = findBlockNear(bot.level(), plot, Blocks.FURNACE, -30);
                    return !bot.busy()
                            || (fpos[0] != null
                                    && bot.level().getBlockEntity(fpos[0]) instanceof Container c
                                    && !c.getItem(0).isEmpty());
                });
        assertNotNull(fpos[0], "the bot placed its furnace");
        game.check(
                game.onServer(s -> bot.busy()),
                "the smelt is still running when the ore is in: lastError="
                        + game.onServer(
                                s -> bot.status().slots().smelt().lastError().orElse("null")));

        bot.moveTo(plot.at(30, 1, 0));
        int[] tick = {0};
        int[] menuClosedAt = {-1};
        boolean[] busyWhenClosed = {false};
        game.await().within(900).as("the smelt ends after the bot left").until(() -> {
            tick[0]++;
            if (menuClosedAt[0] < 0 && bot.player().containerMenu == bot.player().inventoryMenu) {
                menuClosedAt[0] = tick[0];
                busyWhenClosed[0] = bot.busy();
            }
            return !bot.busy();
        });
        reporter.publishEntry("menu.closedAtTick", menuClosedAt[0] + " busy=" + busyWhenClosed[0]);

        String err = game.onServer(s -> bot.status().slots().smelt().lastError().orElse(null));
        int bagIngots = game.onServer(s -> Staging.countItem(bot.player(), Items.IRON_INGOT));
        int bagOre = game.onServer(s -> Staging.countItem(bot.player(), Items.RAW_IRON));
        String holds = game.onServer(s -> containerSummary(bot.level(), fpos[0]));
        int inFurnace = game.onServer(s -> bot.level().getBlockEntity(fpos[0]) instanceof Container c
                ? c.getItem(0).getCount() + c.getItem(2).getCount()
                : -1);
        game.check(
                err != null && err.contains("left the furnace"), "the smelt names the bot's absence: lastError=" + err);
        game.check(
                bagIngots == 0 && bagOre == 0,
                "nothing came back from sixty blocks away: bag ingots=" + bagIngots + " ore=" + bagOre
                        + " furnaceHolds=" + holds);
        game.check(inFurnace == 3, "the three ore, raw or cooked, are still in the furnace: furnaceHolds=" + holds);
    }

    /** First cell of the arena (plot y 0..2, ±3) holding {@code want}, or null. Server thread. */
    private static BlockPos findBlockNear(ServerLevel level, Plot plot, Block want) {
        return findBlockNear(level, plot, want, 0);
    }

    /** {@link #findBlockNear(ServerLevel, Plot, Block)} around plot x {@code cx}. */
    private static BlockPos findBlockNear(ServerLevel level, Plot plot, Block want, int cx) {
        for (int dy = 0; dy <= 2; dy++)
            for (int dx = cx - 3; dx <= cx + 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos at = plot.at(dx, dy, dz);
                    if (level.getBlockState(at).is(want)) return at;
                }
        return null;
    }

    /**
     * What a container at {@code at} is holding — the reading that tells "never smelted" apart from "smelted and never
     * handed over".
     */
    private static String containerSummary(ServerLevel level, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof Container c)) return "not a container";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            sb.append(i)
                    .append('=')
                    .append(st.isEmpty() ? "-" : st.getCount() + "×" + st.getItem())
                    .append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Plug every empty inventory slot, so nothing can be shift-clicked back in. Cobblestone because it is what a mining
     * rung is actually carrying when this happens live.
     */
    private static int fillEveryFreeSlot(ServerPlayer fp) {
        int n = 0;
        var items = fp.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isEmpty()) continue;
            items.set(i, new ItemStack(Items.COBBLESTONE, 64));
            n++;
        }
        return n;
    }

    private static int freeSlots(ServerPlayer fp) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) if (st.isEmpty()) n++;
        return n;
    }

    /** Empty the bag of one item, so no partial stack of it survives to absorb a later one. */
    private static void takeAll(ServerPlayer fp, Item item) {
        var items = fp.getInventory().items;
        for (int i = 0; i < items.size(); i++) if (items.get(i).is(item)) items.set(i, ItemStack.EMPTY);
    }
}

package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * A server-side bot's dig follows the player's rules: the tool requirement for drops, Silk Touch, and tool wear. Each
 * digs one cell with {@code mineCell}, which has no tool gate of its own, in survival.
 */
@Scene("wd.scenes")
class DigFidelityTest {

    /** What one dig left behind: the cell, the tool afterwards, and every item of interest in the bag or nearby. */
    private record Dug(boolean cleared, ItemStack toolAfter, Function<Item, Integer> count, String where) {}

    /** Dig {@code block} at a cell beside the bot holding {@code tool} (empty for a bare hand). Server thread. */
    private static Dug dig(Game game, Plot plot, Block block, Function<ServerLevel, ItemStack> tool) {
        plot.fill(-3, 0, -3, 3, 0, 3, Blocks.STONE);
        plot.place(1, 1, 0, block);
        BlockPos cell = plot.at(1, 1, 0);
        Settings.pinUntimed(game)
                .set("allowBreak", true)
                .set("allowPlace", false)
                .set("navigatorDebug", false);
        SceneBot bot = SceneBot.spawn(game, plot.at(-1, 1, 0));
        return game.onServer(s -> {
            ServerPlayer fp = bot.player();
            fp.setGameMode(GameType.SURVIVAL);
            fp.getInventory().clearContent();
            fp.getInventory().setItem(0, tool.apply(fp.serverLevel()));
            fp.getInventory().selected = 0;
            bot.mineCell(cell);
            bot.tickUntilIdle(200);
            bot.tick(20); // the drop settles and is picked up or lies there
            AABB near = new AABB(cell).inflate(6);
            ServerLevel level = fp.serverLevel();
            Function<Item, Integer> count = item -> {
                int n = bot.count(item);
                for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, near)) {
                    if (e.getItem().is(item)) {
                        n += e.getItem().getCount();
                    }
                }
                return n;
            };
            Map<Item, Integer> counts = new HashMap<>();
            for (Item item : List.of(Items.OBSIDIAN, Items.STONE, Items.COBBLESTONE)) {
                counts.put(item, count.apply(item));
            }
            ItemStack toolAfter = fp.getInventory().getItem(0).copy();
            String where = "cell=" + level.getBlockState(cell) + " held=" + fp.getMainHandItem() + " found=" + counts
                    + " end=" + bot.status().lastProcessEnd();
            boolean cleared = level.getBlockState(cell).isAir();
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, near)) {
                e.discard();
            }
            return new Dug(cleared, toolAfter, counts::get, where);
        });
    }

    /** Obsidian needs a diamond pickaxe to drop: a fist breaks it (removal is not tool-gated) and gets nothing. */
    @Test
    void serverBareHandedObsidianDropsNothing(Game game, @Plot.Centered Plot plot) {
        Dug d = dig(game, plot, Blocks.OBSIDIAN, level -> ItemStack.EMPTY);
        if (!d.cleared()) {
            fail("rig broken: the obsidian was not broken: " + d.where());
        }
        int got = d.count().apply(Items.OBSIDIAN);
        if (got != 0) {
            fail("a bare hand harvested " + got + " obsidian, which a player cannot: " + d.where());
        }
    }

    /** Silk Touch on stone yields stone, not cobblestone. */
    @Test
    void serverSilkTouchKeepsTheBlock(Game game, @Plot.Centered Plot plot) {
        Dug d = dig(game, plot, Blocks.STONE, level -> {
            ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
            pick.enchant(
                    level.registryAccess()
                            .registryOrThrow(Registries.ENCHANTMENT)
                            .getHolderOrThrow(Enchantments.SILK_TOUCH),
                    1);
            return pick;
        });
        if (!d.cleared()) {
            fail("rig broken: the stone was not broken: " + d.where());
        }
        int stone = d.count().apply(Items.STONE), cobble = d.count().apply(Items.COBBLESTONE);
        if (stone != 1 || cobble != 0) {
            fail("Silk Touch on stone gave stone=" + stone + " cobblestone=" + cobble + " (a player gets 1 and 0): "
                    + d.where());
        }
    }

    /** A pickaxe that mines a block takes one point of wear. */
    @Test
    void serverDiggingWearsTheTool(Game game, @Plot.Centered Plot plot) {
        Dug d = dig(game, plot, Blocks.STONE, level -> new ItemStack(Items.IRON_PICKAXE));
        if (!d.cleared()) {
            fail("rig broken: the stone was not broken: " + d.where());
        }
        if (!d.toolAfter().is(Items.IRON_PICKAXE) || d.toolAfter().getDamageValue() != 1) {
            fail("the iron pickaxe after one stone is " + d.toolAfter() + " damage="
                    + d.toolAfter().getDamageValue() + " (a player's takes 1): " + d.where());
        }
    }

    /** The control arm: the right tool still gets the plain harvest, so a green above is not "nothing drops". */
    @Test
    void serverRightToolStillHarvests(Game game, @Plot.Centered Plot plot) {
        Dug d = dig(game, plot, Blocks.STONE, level -> new ItemStack(Items.STONE_PICKAXE));
        if (!d.cleared()) {
            fail("rig broken: the stone was not broken: " + d.where());
        }
        int cobble = d.count().apply(Items.COBBLESTONE);
        if (cobble != 1) {
            fail("a stone pickaxe on stone gave " + cobble + " cobblestone (a player gets 1): " + d.where());
        }
    }
}

package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.process.SmeltProcess;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The one scene that lets a furnace actually cook, split out of {@link WorldDriverProcessScenes}
 * verbatim so that class fits the 3000-line source budget — see {@link WorldDriverPortalScenes} for
 * the shape of the split. Its inventory helpers ({@code findBlockNear} / {@code containerSummary} /
 * {@code fillEveryFreeSlot} / {@code freeSlots} / {@code takeAll}) have no caller outside it and
 * came along; {@code countItem} stayed behind, shared.
 */
public final class WorldDriverSmeltDeliveryScenes {

    private WorldDriverSmeltDeliveryScenes() {}

    /** Registers this family in the order {@link WorldDriverProcessScenes#scenes()} used before the split. */
    static void register(List<Scene> out) {
        out.addAll(List.of(
                // The only scene in the repo that lets a furnace actually cook. Every other smelt
                // scene drives the avatar inside ONE server tick, which is fast and correct for
                // testing slot plumbing and structurally unable to test smelting — so the suite was
                // green over a smelt that produced ingots and delivered none. Needs real ticks
                // (200 per item), hence the budget.
                Scene.of("wd.serverSmeltDeliversTheIngot", 2_000,
                        WorldDriverSmeltDeliveryScenes::serverSmeltDeliversTheIngot)));
    }

    /**
     * Smelt an ore end to end and put the ingot in the BAG — then take the bag away and require
     * the process to say so.
     *
     * <p><b>Why this is the first scene that can.</b> {@code wd.serverSmeltStationOpens} and
     * {@code wd.smeltFuelPolicy} both drive up to 400 avatar ticks inside a SINGLE server tick, so
     * no furnace tick ever fires; one asserts the load and the other injects the result by hand and
     * says so in its own javadoc. That is the whole of the suite's smelting coverage, and it means
     * every assertion stops at the furnace door. This one waits on real server ticks — 200 per item
     * — so the block entity cooks, and it asks the question the journey's iron rung asks: how many
     * ingots are in the body's inventory.
     *
     * <p><b>Phase 2 is the bug this scene was written for.</b> {@code SmeltProcess.collect()} takes
     * the result back with a shift-click, and {@code AbstractFurnaceMenu.quickMoveStack} →
     * {@code moveItemStackTo(stack, 3, 39, true)} moves NOTHING and returns false when every player
     * slot is taken. The process then reported DONE with {@code lastError} null: ingots made, ingots
     * stranded in the furnace, caller told nothing. Intermittent live for a reason that is not about
     * smelting at all — the body stands beside the furnace for a thousand ticks with
     * {@code ServerPlayerAvatar}'s pickup loop running, so the slot its own ore vacated at LOAD
     * refills with whatever the mining rung left on the ground. So phase 2 plugs every free slot at
     * the moment the ore enters the furnace — deterministically, and before the first ingot exists,
     * which is what keeps it out of a race with COLLECT — and requires the run to come back with a
     * reason instead of a silent success.
     *
     * <p>The furnace is PLACED by the body from its own bag rather than staged by the scene, so the
     * place → open → load → cook → collect path is exercised whole; phase 2 then reuses the furnace
     * left standing.
     */
    private static void serverSmeltDeliversTheIngot(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = false;
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        // Empty the furnace BEFORE the block goes, or AbstractFurnaceBlock.onRemove drops its
        // contents as item entities and leaves the arena littered for the audit to blame on the
        // next scene. Phase 2 deliberately ends with a loaded furnace, so this is not hypothetical.
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = 0; dy <= 3; dy++)
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos at = new BlockPos(cx + dx, floorY + dy, cz + dz);
                        if (level.getBlockEntity(at) instanceof net.minecraft.world.Container c) c.clearContent();
                        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                    }
        });

        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        ServerWorldDriver driver = ServerWorldDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.FURNACE, 1));
        fp.getInventory().add(new ItemStack(Items.RAW_IRON, 2));
        fp.getInventory().add(new ItemStack(Items.COAL, 2));

        ServerAvatarManager.register(driver.runProcess(new SmeltProcess("minecraft:raw_iron", 2, null)));
        // 200 ticks an item plus the place/open/load round trips; a run that spends the whole of
        // this has found a furnace that never cooked, which the process now names for itself.
        ctx.await(driver::finished).within(900).then(() -> {
            BlockPos fpos = findBlockNear(level, cx, floorY, cz, Blocks.FURNACE);
            ctx.record("phase1.furnaceAt", fpos == null ? "never placed" : fpos.toShortString());
            ctx.record("phase1.ingots", WorldDriverProcessScenes.countItem(fp, Items.IRON_INGOT));
            ctx.record("phase1.rawIronLeft", WorldDriverProcessScenes.countItem(fp, Items.RAW_IRON));
            ctx.record("phase1.lastError", String.valueOf(driver.botState().smelt.lastError));
            ctx.record("phase1.furnaceHolds", fpos == null ? "-" : containerSummary(level, fpos));
            ctx.expect(WorldDriverProcessScenes.countItem(fp, Items.IRON_INGOT))
                    .as("iron ingots in the BODY'S BAG after a smelt that really cooked "
                            + "(see phase1.furnaceHolds: a furnace still holding them is the bug)")
                    .isAtLeast(2);
            ctx.expect(fpos).as("the body placed its own furnace and it is still standing").isNotNull();

            // ---- phase 2: the same smelt with nowhere to put the result ----
            // Phase 1's ingots have to GO. A full bag is not full for an item it already holds a
            // partial stack of — moveItemStackTo merges before it looks for an empty slot — so
            // leaving them here made the plug below leak: measured, 35 slots plugged and the third
            // ingot still landed, on top of the two. The body that hits this live is smelting its
            // first iron, which is exactly this arrangement.
            takeAll(fp, Items.IRON_INGOT);
            fp.getInventory().add(new ItemStack(Items.RAW_IRON, 1));
            ServerAvatarManager.register(driver.runProcess(new SmeltProcess("minecraft:raw_iron", 1, null)));
            net.minecraft.world.Container furnace =
                    level.getBlockEntity(fpos) instanceof net.minecraft.world.Container c ? c : null;
            ctx.expect(furnace).as("the placed furnace is a container").isNotNull();
            // Plug the bag the moment the ore ENTERS the furnace, not when the ingot appears: the
            // ore leaving the bag is what frees the slot, and doing it now keeps this out of a race
            // with COLLECT (which fires on the same tick the last ingot is made).
            ctx.await(() -> !furnace.getItem(0).isEmpty() || driver.finished()).within(300).then(() -> {
                int plugged = fillEveryFreeSlot(fp);
                ctx.record("phase2.pluggedSlots", plugged);
                ctx.record("phase2.freeSlots", freeSlots(fp));
                ctx.expect(freeSlots(fp))
                        .as("the bag really is full — a phase that cannot block the delivery "
                                + "proves nothing about reporting one").isEqualTo(0);
                ctx.await(driver::finished).within(600).then(() -> {
                    String err = driver.botState().smelt.lastError;
                    int delivered = WorldDriverProcessScenes.countItem(fp, Items.IRON_INGOT);
                    ctx.record("phase2.ingotsInBag", delivered);
                    ctx.record("phase2.furnaceHolds", containerSummary(level, fpos));
                    ctx.record("phase2.lastError", String.valueOf(err));
                    // Nothing may EVAPORATE: the ingot is in the bag or still in the furnace.
                    ctx.expect(delivered >= 1 || furnace.getItem(2).getCount() >= 1)
                            .as("the smelt's ingot is either in the bag or still in the furnace")
                            .isTrue();
                    // And the contract this scene exists for: hand it back, or say why not.
                    // Reporting done with lastError=null while the ingots sit in the furnace is how
                    // a smelt that worked reads as a mining failure four rungs downstream.
                    ctx.expect(delivered >= 1 || err != null)
                            .as("a smelt that cooked an ingot it could not hand back must SAY so "
                                    + "(see phase2.furnaceHolds for where it went)")
                            .isTrue();
                });
            });
        });
    }

    /** First cell of the arena holding {@code want}, or null. */
    private static BlockPos findBlockNear(ServerLevel level, int cx, int floorY, int cz, Block want) {
        for (int dy = 0; dy <= 2; dy++)
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos at = new BlockPos(cx + dx, floorY + dy, cz + dz);
                    if (level.getBlockState(at).is(want)) return at;
                }
        return null;
    }

    /** What a container at {@code at} is holding — the reading that tells "never smelted" apart
     *  from "smelted and never handed over". */
    private static String containerSummary(ServerLevel level, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof net.minecraft.world.Container c)) return "not a container";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            sb.append(i).append('=').append(st.isEmpty() ? "-" : st.getCount() + "×" + st.getItem()).append(' ');
        }
        return sb.toString().trim();
    }

    /** Plug every empty inventory slot, so nothing can be shift-clicked back in. Cobblestone
     *  because it is what a mining rung is actually carrying when this happens live. */
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
    private static int takeAll(ServerPlayer fp, net.minecraft.world.item.Item item) {
        int n = 0;
        var items = fp.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).is(item)) continue;
            n += items.get(i).getCount();
            items.set(i, ItemStack.EMPTY);
        }
        return n;
    }
}

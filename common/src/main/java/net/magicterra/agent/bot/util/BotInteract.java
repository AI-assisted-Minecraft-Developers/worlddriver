package net.magicterra.agent.bot.util;

import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import java.util.Locale;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.core.component.DataComponents;


/**
 * Player-facing interaction helpers: aiming, hotbar/tool selection, block
 * placement, key release, and food/hand/face parsing. Extracted from the
 * former {@code BotApiImpl} god-class; pulled in via
 * {@code import static …BotInteract.*}.
 */
public final class BotInteract {

    private BotInteract() {}

    public static InteractionHand parseHand(Object o) {
        if (o instanceof String s && (s.equalsIgnoreCase("off") || s.equalsIgnoreCase("offhand") || s.equalsIgnoreCase("off_hand"))) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    /** Parse "up"/"down"/"north"/... ; null on missing or unrecognized. */
    public static Direction parseFace(Object o) {
        if (!(o instanceof String s) || s.isBlank()) return null;
        try { return Direction.byName(s.toLowerCase(Locale.ROOT)); }
        catch (Exception e) { return null; }
    }

    /** Pick the face of {@code block} closest to the player's eye — the face the
     *  player would naturally hit if they ray-cast at the block. Used when the
     *  caller didn't specify a face. */
    public static Direction pickFaceTowardsPlayer(BlockPos block, net.minecraft.world.entity.player.Player p) {
        Vec3 eye = p.getEyePosition();
        Vec3 center = new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
        Vec3 delta = eye.subtract(center);
        double ax = Math.abs(delta.x), ay = Math.abs(delta.y), az = Math.abs(delta.z);
        if (ay >= ax && ay >= az) return delta.y >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return delta.x >= 0 ? Direction.EAST : Direction.WEST;
        return delta.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Internal — used by BuildProcess to drive the real placement pipeline
     *  instead of the legacy server.setBlock bypass. Must be called from the
     *  client thread. */
    public static InteractionResult clientUseItemOn(Minecraft mc, LocalPlayer p, BlockPos clickBlock, Direction face) {
        double cx = clickBlock.getX() + 0.5 + face.getStepX() * 0.5;
        double cy = clickBlock.getY() + 0.5 + face.getStepY() * 0.5;
        double cz = clickBlock.getZ() + 0.5 + face.getStepZ() * 0.5;
        BlockHitResult hit = new BlockHitResult(new Vec3(cx, cy, cz), face, clickBlock, false);
        // Don't touch the sneak state here — placement callers (BuildProcess /
        // TowerProcess / BridgeProcess) deliberately sneak to shrink the player
        // AABB before clicking (Baritone MovementHelper.attemptToPlaceABlock
        // pattern). An unconditional setShiftKeyDown(false) would break the
        // very check vanilla uses to allow the placement. Callers that need to
        // NOT be sneaking (e.g. SleepProcess clicking a bed) should release the
        // shift key themselves before calling.
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        if (r.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
        return r;
    }

    /**
     * Close any open server-side container (furnace/chest/table) and return to the
     * plain inventory menu. Headless, opening a block container sets
     * {@code player.containerMenu} on both sides but never spawns a client
     * {@code Screen}, so {@code mc.setScreen(null)} is a no-op and the container
     * LEAKS — every later {@code handleInventoryMouseClick} carries the inventory
     * menu's id (0), which the game silently ignores while a different container is
     * "open" (id mismatch). That stranded a furnace from one process and broke the
     * next process's inventory clicks. This sends the real close packet AND resets
     * the local menu, so subsequent inventory-menu clicks land. Safe to call when
     * nothing is open (no-op).
     */
    public static void closeContainer(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p != null && p.containerMenu != p.inventoryMenu) {
            if (p.connection != null) {
                p.connection.send(new ServerboundContainerClosePacket(p.containerMenu.containerId));
            }
            p.closeContainer();   // Player.closeContainer(): containerMenu = inventoryMenu
        }
        if (mc.screen != null) mc.setScreen(null);
    }

    public static void releaseKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
                                             mc.options.keyRight, mc.options.keyJump, mc.options.keySprint,
                                             mc.options.keyAttack, mc.options.keyShift}) {
            k.setDown(false);
        }
        // Also reset the player's logical sneak flag — BridgeProcess holds it
        // for the whole sneak-walk; cancel must clear it or the player stays
        // crouched after the process ends.
        if (mc.player != null) mc.player.setShiftKeyDown(false);
    }

    public static boolean isFoodStack(ItemStack stk) {
        if (stk == null || stk.isEmpty()) return false;
        return stk.get(DataComponents.FOOD) != null;
    }

    public static int findFoodHotbarSlot(LocalPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (isFoodStack(inv.items.get(i))) return i;
        }
        return -1;
    }

    public static void aimAtBlockSnap(LocalPlayer p, BlockPos block) {
        Vec3 eye = p.getEyePosition();
        double dx = (block.getX() + 0.5) - eye.x, dy = (block.getY() + 0.5) - eye.y, dz = (block.getZ() + 0.5) - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(pitch);
        // Functional exact aim: vanilla keyAttack mining / interaction raycasts off
        // the crosshair, so this snap must NOT be rate-limited by the global camera
        // slew (a lagged crosshair would mine/click the wrong block). Exempt this one
        // tick — see LookController. Cosmetic aims (synthetic placement, look-down)
        // do NOT call this, so they stay smoothed.
        net.magicterra.agent.bot.movement.LookController.requestSnap();
    }

    /** A stack's destroy speed against {@code bs} including its Efficiency enchant
     *  (+level²+1 once the tool already beats bare hand), matching the vanilla
     *  Player.getDestroySpeed path the A* breakCost estimates with. {@code eff}
     *  may be null (no enchant registry / data pack) → raw tool speed. */
    public static float effSpeed(ItemStack stk,
                                 BlockState bs,
                                 Holder<Enchantment> eff) {
        float sp = stk.getDestroySpeed(bs);
        if (sp > 1f && eff != null) {
            int el = EnchantmentHelper.getItemEnchantmentLevel(eff, stk);
            if (el > 0) sp += el * el + 1;
        }
        return sp;
    }

    /** Swap to the best hotbar tool for a block (mirrors MineProcess.selectBestTool). */
    public static void selectBestToolFor(Minecraft mc, BlockPos pos) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) return;
        BlockState bs = lvl.getBlockState(pos);
        Inventory inv = p.getInventory();
        // Resolve the Efficiency holder so this ranks tools the same way the A*
        // breakCost did (a +Efficiency tool can out-mine a higher-base one); a
        // data pack missing the vanilla enchant just falls back to raw speed.
        Holder<Enchantment> eff = null;
        try {
            eff = lvl.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.EFFICIENCY);
        } catch (Exception ignored) { eff = null; }
        int bestSlot = -1;
        float bestSpeed = effSpeed(inv.getSelected(), bs, eff);
        boolean bestCorrect = inv.getSelected().isCorrectToolForDrops(bs);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stk = inv.items.get(slot);
            if (stk.isEmpty()) continue;
            float sp = effSpeed(stk, bs, eff);
            boolean cor = stk.isCorrectToolForDrops(bs);
            if ((cor && !bestCorrect) || (cor == bestCorrect && sp > bestSpeed)) {
                bestSlot = slot;
                bestSpeed = sp;
                bestCorrect = cor;
            }
        }
        if (bestSlot >= 0 && bestSlot != inv.selected) {
            inv.selected = bestSlot;
            if (p.connection != null) {
                p.connection.send(new ServerboundSetCarriedItemPacket(bestSlot));
            }
        }
    }

    /** A BlockItem that forms a SOLID footing when placed over air — what the
     *  pillar / bridge / parkour-place actuators must stand on. Excludes FallingBlock
     *  (sand/gravel drop away over the gap) AND non-collidable blocks (sapling, flower,
     *  torch, …): placing one leaves nothing to stand on, so the actuator bobs forever
     *  with the place cell still air (pend=true) — observed live, the bot held an
     *  oak_sapling and pillared saplings it then couldn't climb. Mirrors
     *  {@code ClientWorldView.hasPlaceableBlock} so the planner's canPlace and the
     *  actuator's block-selection agree on what counts as buildable. */
    public static boolean isSupportBlock(ItemStack stk) {
        if (stk.isEmpty() || !(stk.getItem() instanceof BlockItem bi)) return false;
        return net.magicterra.agent.bot.BotConfig.isUsableBuildBlock(bi.getBlock());
    }

    /** Hold (or swap to) a SOLID-support BlockItem in the hotbar (see {@link #isSupportBlock}).
     *  Creative can pull from main inventory. Returns false when none is available. */
    public static boolean ensureHoldingPlaceableAny(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return false;
        Inventory inv = p.getInventory();
        if (isSupportBlock(inv.getSelected())) return true;
        for (int s = 0; s < 9; s++) {
            if (isSupportBlock(inv.items.get(s))) {
                inv.selected = s;
                if (p.connection != null) p.connection.send(
                        new ServerboundSetCarriedItemPacket(s));
                return true;
            }
        }
        if (p.isCreative()) {
            for (int s = 9; s < inv.items.size(); s++) {
                if (isSupportBlock(inv.items.get(s))) {
                    inv.pickSlot(s);
                    return isSupportBlock(inv.getSelected());
                }
            }
        }
        return false;
    }

    /** First hotbar slot (0-8) holding {@code item}, or -1. */
    public static int hotbarSlotOf(LocalPlayer p, Item item) {
        Inventory inv = p.getInventory();
        for (int s = 0; s < 9; s++) if (inv.items.get(s).getItem() == item) return s;
        return -1;
    }

    /** Swap the selected hotbar slot to one holding {@code item}; false if none. */
    public static boolean ensureHolding(Minecraft mc, Item item) {
        LocalPlayer p = mc.player;
        if (p == null) return false;
        Inventory inv = p.getInventory();
        if (inv.getSelected().getItem() == item) return true;
        int s = hotbarSlotOf(p, item);
        if (s < 0) return false;
        inv.selected = s;
        if (p.connection != null) p.connection.send(
                new ServerboundSetCarriedItemPacket(s));
        return true;
    }

    /** Place a block into {@code cell} by clicking a solid neighbour's face.
     *  Used by the Walker's bridge actuator. */
    public static void walkerPlace(Minecraft mc, LocalPlayer p, WorldView w, BlockPos cell) {
        if (!ensureHoldingPlaceableAny(mc)) return;
        // Prefer placing on top of a block below the cell, then the sides — the
        // support under our feet is adjacent to the gap floor we bridge.
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (Direction d : order) {
            BlockPos support = cell.offset(d.getStepX(), d.getStepY(), d.getStepZ());
            if (w.isSolid(support)) {
                clientUseItemOn(mc, p, support, d.getOpposite());   // face points from support back at cell
                return;
            }
        }
    }
}

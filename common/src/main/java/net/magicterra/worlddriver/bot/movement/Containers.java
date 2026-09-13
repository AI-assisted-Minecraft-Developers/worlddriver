package net.magicterra.worlddriver.bot.movement;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * What a body does with menus: the recipe book, container clicks, closing.
 *
 * <p>Split out of {@link Avatar} with {@link Hands}, and for the same reason: vanilla hangs
 * menus off {@code Player} ({@code containerMenu}, {@code inventoryMenu}), so only a player body
 * can answer these. A process that crafts or smelts asks {@link Avatar#containers()} and refuses
 * the order when it is empty. Every method here was moved from {@code Avatar} unchanged.
 */
public interface Containers {

    /** The body whose menus these are. */
    LivingEntity entity();

    /** The recipe registry — client: the connection's (works in multiplayer); server:
     *  the running server's. Null if unavailable (no server/connection). */
    RecipeManager recipeManager();
    /** Recipe-book placement into the open menu's grid — client:
     *  {@code gameMode.handlePlaceRecipe} (→ packet); server:
     *  {@code RecipeBookMenu.handlePlacement} directly (works for the always-present 2×2
     *  inventory grid even on a FakePlayer). */
    void placeRecipe(int containerId, RecipeHolder<?> recipe, boolean placeAll);
    /** A container-slot click — client: {@code gameMode.handleInventoryMouseClick}
     *  (→ packet); server: {@code menu.clicked} directly. */
    void containerClick(int containerId, int slot, int button, ClickType type);
    /** Close the open container back to the inventory menu. */
    void closeContainer();
    /** Return any material stranded in the 2×2 crafting grid (InventoryMenu slots
     *  1-4) to the main inventory via QUICK_MOVE, leaving the grid empty. {@code
     *  placeRecipe} fills the grid straight from the inventory, and a craft step
     *  that never reaches the shift-click out (e.g. an AWAIT_RESULT timeout) leaves
     *  it sitting there; {@link #closeContainer()}'s vanilla-close path only returns
     *  those items when the menu that was open differs from the inventory menu (a
     *  3×3 table screen, whose {@code removed()} runs the return) — a headless 2×2
     *  job has {@code containerMenu == inventoryMenu} from the start, so that guard
     *  never fires and the grid strands materials forever (live gap #67-③: 5
     *  acacia_log vanished after a failed 6-craft). Closes any OTHER open menu first
     *  so the QUICK_MOVE click lands on the inventory menu's id (see {@link
     *  #closeContainer()}'s id-mismatch note). Safe to call when nothing is
     *  stranded (no-op); callers should run it at every CraftProcess exit (DONE/FAIL)
     *  and before starting a fresh 2×2 job. */
    default void clearInventoryCraftGrid() {
        if (!(entity() instanceof Player p)) return;
        if (p.containerMenu != p.inventoryMenu) closeContainer();
        AbstractContainerMenu inv = p.inventoryMenu;
        for (int slot = 1; slot <= 4; slot++) {
            if (!inv.getSlot(slot).getItem().isEmpty()) {
                containerClick(inv.containerId, slot, 0, ClickType.QUICK_MOVE);
            }
        }
    }
}

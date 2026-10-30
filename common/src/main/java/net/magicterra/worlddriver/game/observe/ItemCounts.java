package net.magicterra.worlddriver.game.observe;

import static net.magicterra.worlddriver.game.observe.RegistryIds.itemId;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** How many of each item a player carries. */
public final class ItemCounts {
    private ItemCounts() {}

    /**
     * Item id -> count over the bag the craft executor actually consumes from: main inventory + offhand, armor
     * excluded, ids namespaced. This is the definition of {@code have} — {@code mc.observe.player} emits its
     * {@code items} map through THIS method precisely so the bag the agent asks the planner about is the bag the
     * executor will reach into. Two rulers is how a plan comes back "complete" and then fails at craft time (gap #38,
     * same lesson, stations half).
     */
    public static Map<String, Integer> bag(Player p) {
        Map<String, Integer> have = new LinkedHashMap<>();
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty()) continue;
            have.merge(itemId(s), s.getCount(), Integer::sum);
        }
        ItemStack off = p.getInventory().offhand.isEmpty()
                ? ItemStack.EMPTY
                : p.getInventory().offhand.get(0);
        if (!off.isEmpty()) have.merge(itemId(off), off.getCount(), Integer::sum);
        return have;
    }
}

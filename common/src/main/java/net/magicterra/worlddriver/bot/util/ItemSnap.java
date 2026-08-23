package net.magicterra.worlddriver.bot.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place item WEAR is projected into an API row.
 *
 * <p>Before this existed the agent could not see tool durability at all: every item the
 * driver reported was {@code {id, count}}, so a pickaxe with 5 uses left looked exactly
 * like a fresh one. The engine itself has always known — AutoEquip swaps on
 * {@code equipDurabilityThreshold}, ElytraProcess aborts on a durability margin — but
 * none of it reached the agent, which therefore could only learn a tool was gone AFTER
 * {@code tool.broke} fired. Everything a driver-driven agent might do BEFORE the break
 * (switch to the spare, head home while it can still dig, decide a 90-block tunnel is
 * affordable) needs this number.
 *
 * <p>It is a helper rather than four copy-pasted blocks because the driver builds item
 * rows in five different shapes (with {@code empty}, with {@code slot}, with
 * {@code index}, server-side and client-side). Duplicating the wear fields into each is
 * exactly how a field ends up meaning two things — see gap #41, where server and client
 * {@code inventory} drifted apart.
 */
public final class ItemSnap {
    private ItemSnap() {}

    /**
     * Adds {@code maxDamage}, {@code damage} and {@code durability} (points REMAINING,
     * {@code maxDamage - damage} — the number decisions actually turn on) to an already-built
     * item row, for damageable items only. Note points are not uses: Unbreaking makes a single
     * point survive several uses, so treat {@code durability} as a floor on remaining work.
     * Stackables (cobblestone, coal) carry no wear and get no fields, so the payload stays
     * small and the presence of {@code durability} itself means "this is a thing that wears out".
     */
    public static void putWear(Map<String, Object> row, ItemStack s) {
        if (s == null || s.isEmpty() || !s.isDamageableItem()) return;
        int max = s.getMaxDamage();
        int dmg = s.getDamageValue();
        row.put("maxDamage", max);
        row.put("damage", dmg);
        row.put("durability", max - dmg);
    }

    /**
     * The whole bag as API rows — non-empty slots only, in vanilla
     * {@link Player#getInventory()} indexing (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand),
     * each row {@code {slot, id, count}} plus whatever {@link #putWear} adds.
     *
     * <p><b>Why this is here and not written out twice.</b> This class's own header cites gap #41
     * — server and client {@code inventory} drifting apart — as the reason the WEAR fields were
     * centralised. The enumeration that produces the rows those fields go into was left copied:
     * {@code ObserveApi.playerSnapshot} and {@code ClientObserve.observePlayer} each carried this
     * loop, byte-identical but for the local's declared type. The server one's comment states the
     * contract outright — "Shape is deliberately byte-identical to the client's — same verb, same
     * field, same rows — so an agent never has to know which side answered" — and a contract of
     * the form「这两处必须逐字相同」that is maintained by hand is a drift waiting for its first
     * one-sided edit. It is the same field, and gap #41 is what that costs.
     *
     * <p>Slot INDEXING is vanilla's and is the load-bearing part: {@code getItem(i)} maps the flat
     * index onto items/armor/offhand itself, so the total is the three list sizes summed rather
     * than a hardcoded 41 — a modded inventory size follows automatically.
     */
    public static List<Map<String, Object>> inventoryRows(Player p) {
        List<Map<String, Object>> inv = new ArrayList<>();
        Inventory pInv = p.getInventory();
        int totalSlots = pInv.items.size() + pInv.armor.size() + pInv.offhand.size();
        for (int i = 0; i < totalSlots; i++) {
            ItemStack st = pInv.getItem(i);
            if (st == null || st.isEmpty()) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slot", i);
            entry.put("id", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
            entry.put("count", st.getCount());
            putWear(entry, st);
            inv.add(entry);
        }
        return inv;
    }
}

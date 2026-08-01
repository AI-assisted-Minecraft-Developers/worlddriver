package net.magicterra.worlddriver.bot.util;

import net.minecraft.world.item.ItemStack;

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
}

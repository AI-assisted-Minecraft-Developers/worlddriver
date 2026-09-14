package net.magicterra.worlddriver.bot.util;

import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one place the melee attack cooldown is projected into an API row.
 *
 * <p>Vanilla scales melee damage by how far the swing timer has recharged: a swing at
 * 40% of the cooldown lands ~40% of the weapon's damage, and a critical hit is only
 * possible on a full-strength swing. {@link net.magicterra.worlddriver.bot.process.CombatProcess}
 * has always known this — it reads {@link Player#getAttackStrengthScale} every tick to hold
 * its swing until the bar is full and to pre-jump for the crit — but none of it reached the
 * agent, and {@code mc.bot.attackEntity} deliberately does no cooldown check. An agent doing
 * its own combat therefore swung on a timer it could not see, landed 40-80% hits, and had no
 * way to tell that from "this mob is tanky" — the misdiagnosis being "I need a better weapon"
 * or "I should retreat" when the real answer was "wait {@code cooldownTicks} more ticks".
 * Same shape as gaps #41 (inventory) and #42 (durability): the engine used a fact to decide
 * correctly and handed the agent zero bytes of it.
 *
 * <p>Single helper rather than two copies because the snapshot is built on both sides
 * (server {@code ObserveApi.playerSnapshot}, client {@code ClientObserve.observePlayer});
 * duplicating the arithmetic is exactly how one field grows two meanings — see gap #41.
 * Derived purely from public API, so it needs none of the reflection
 * {@code ServerPlayerBody} needs for the protected {@code attackStrengthTicker}.
 */
public final class AttackSnap {
    private AttackSnap() {}

    /**
     * Melee readiness of the held weapon, right now:
     * <ul>
     *   <li>{@code strengthScale} — 0.0-1.0, vanilla's damage multiplier for a swing landed
     *       this tick. Sampled with no look-ahead (partial tick 0), so it is the honest
     *       current value, not CombatProcess's {@code 0.5f} half-tick lead.</li>
     *   <li>{@code ready} — {@code strengthScale >= 1.0}: the exact gate CombatProcess swings on.</li>
     *   <li>{@code cooldownTicks} — ticks until {@code ready} (0 when ready). This is the number
     *       to feed a wait: swinging earlier only wastes the hit.</li>
     *   <li>{@code fullCooldownTicks} — the held weapon's full recharge (attack speed attribute;
     *       a sword recharges faster than an axe), so the agent can compare weapons rather than
     *       infer damage-per-second from bruises.</li>
     * </ul>
     * A crit additionally needs the player to be falling ({@code onGround} is already in the
     * snapshot), so it is not restated here.
     */
    public static Map<String, Object> snapshot(Player p) {
        Map<String, Object> out = new LinkedHashMap<>();
        float scale = p.getAttackStrengthScale(0.0f);
        float period = p.getCurrentItemAttackStrengthDelay();
        out.put("strengthScale", scale);
        out.put("ready", scale >= 1.0f);
        out.put("cooldownTicks", (int) Math.ceil(period * (1.0f - scale)));
        out.put("fullCooldownTicks", (int) Math.ceil(period));
        return out;
    }
}

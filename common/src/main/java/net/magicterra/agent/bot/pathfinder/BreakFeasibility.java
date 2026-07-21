package net.magicterra.agent.bot.pathfinder;

import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/**
 * Break cells proven HOPELESS by the executor (2026-07-21 live, flooded Mountains
 * channel), mapped to expiry wall-clock ms. A dig is hopeless when vanilla's own
 * per-tick damage estimate says it either exceeds the effort ceiling (bare-hand
 * floating stone ≈3750t — a visible detour always wins) or, fully submerged,
 * exceeds one breath of uninterrupted work (vanilla zeroes destroyProgress on any
 * interruption, so such a dig can NEVER complete). Priced +INF by the client
 * pricing ({@code ClientWorldView.breakCost} AND {@code escapeBreakCost} — the
 * swim-escape moves price through the latter, and exempting them let the next
 * quick search re-adopt the just-poisoned carve within the same second) so the
 * very next search routes around instead of re-adopting the same doomed carve.
 *
 * <p>TTL-bounded so a later revisit (with tools / from dry ground) reprices
 * honestly. Lives in COMMON (not ClientWorldView) because the writer —
 * {@code WalkerTickClimb}'s hopeless-dig gate — also runs for server avatars,
 * and referencing a client class from there crashes dedicated-server
 * classloading (the t0 LocalPlayer-in-SERVER family). The drowning-survival
 * reflex (DrownEscapeChain float / AntiSuffocate-style direct dig) never prices
 * through breakCost at all, so MC-always-escapable is unaffected.
 */
public final class BreakFeasibility {
    private BreakFeasibility() {}

    private static final ConcurrentHashMap<BlockPos, Long> POISON = new ConcurrentHashMap<>();

    public static void poison(BlockPos p, long ttlMs) {
        POISON.put(p.immutable(), System.currentTimeMillis() + ttlMs);
    }

    public static boolean isPoisoned(BlockPos p) {
        if (POISON.isEmpty()) return false;
        Long e = POISON.get(p);
        if (e == null) return false;
        if (System.currentTimeMillis() > e) { POISON.remove(p); return false; }
        return true;
    }
}

package net.magicterra.agent.bot.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The CLIENT-side refresh loop + {@code Minecraft} convenience overloads for
 * {@link ThreatScanner}. Split out so the dist-neutral sensing core ({@code
 * ThreatScanner}) stays free of any {@code Minecraft}/{@code LocalPlayer} reference
 * and therefore LOADS on a dedicated server (where a server-driven CombatProcess
 * calls {@code ThreatScanner.compute(Level, Player, …)} directly). This class is only
 * ever touched by client-side code (the reflex chains, the observe verb, the bot
 * host tick), so it never loads on a server.
 *
 * <p>It owns the per-tick shared cache: {@link #refresh} computes one scan and
 * {@link ThreatScanner#publish}es it so every reflex reader ({@link #current}) in
 * that tick sees the same picture without re-scanning.
 */
public final class ClientThreatScanner {
    private ClientThreatScanner() {}

    /** Last-tick projectile positions, keyed by entity id (client-only — projectiles
     *  report {@code getDeltaMovement()}≈0 on the client, so velocity is derived from
     *  the position delta between refreshes). */
    private static final Map<Integer, Vec3> lastProjPos = new ConcurrentHashMap<>();

    /** Recompute the shared scan — called once per client tick by the bot host so every
     *  reflex reader ({@link #current}) sees the same fresh picture. Driven explicitly
     *  by the tick loop rather than memoised on a tick counter (the latter went stale in
     *  headless runs and starved PanicChain). */
    public static void refresh(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            ThreatScanner.publish(ThreatScanner.EMPTY);
            lastProjPos.clear();
            ThreatScanner.publishProjectileVel(null);
            return;
        }
        // Derive per-projectile velocities (this tick's position vs last tick's) and
        // roll the history forward BEFORE computing the scan, so the scan and any
        // same-tick observe read a full-tick velocity rather than a ~0 delta.
        Map<Integer, Vec3> pos = new HashMap<>();
        Map<Integer, Vec3> vel = new HashMap<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Projectile)) continue;
            Vec3 cur = e.position();
            Vec3 prev = lastProjPos.get(e.getId());
            pos.put(e.getId(), cur);
            vel.put(e.getId(), prev != null ? cur.subtract(prev) : e.getDeltaMovement());
        }
        lastProjPos.clear(); lastProjPos.putAll(pos);
        ThreatScanner.publishProjectileVel(vel);
        ThreatScanner.publish(ThreatScanner.compute(mc.level, mc.player, ThreatScanner.DEFAULT_RADIUS));
    }

    /** The shared scan from the most recent {@link #refresh} this tick. */
    public static ThreatScanner.Scan current(Minecraft mc) {
        return ThreatScanner.current();
    }

    /** Fresh scan at an explicit radius (for the {@code mc.observe.threats} verb). */
    public static ThreatScanner.Scan compute(Minecraft mc, int radius) {
        if (mc.player == null || mc.level == null) return ThreatScanner.EMPTY;
        return ThreatScanner.compute(mc.level, mc.player, radius);
    }
}

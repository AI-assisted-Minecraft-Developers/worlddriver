package net.magicterra.worlddriver.bot.world;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * One bot's threat picture, refreshed once per tick by whatever ticks the bot so every reflex reader that tick sees the
 * same scan without re-scanning.
 *
 * <p>It keeps the projectiles' positions between refreshes because a projectile on the client reports
 * {@code getDeltaMovement()}≈0; the velocity a scan uses is this tick's position minus the last. One per bot, not one
 * per JVM: an integrated server runs the client's bot and the server's bots on two threads, and a shared table let
 * either one read the other's arrows.
 */
public final class ThreatWatch {

    /** Last refresh's projectile positions by entity id; touched only by the ticking thread. */
    private final Map<Integer, Vec3> lastProjPos = new HashMap<>();

    private volatile Map<Integer, Vec3> velocities = Map.of();
    private volatile ThreatScanner.Scan latest = ThreatScanner.EMPTY;

    /**
     * Recompute the scan around {@code self}. {@code loaded} is every entity the caller can see, projectiles among
     * them: the client passes its render list, so a projectile already has a previous position by the time it enters
     * the scan radius.
     */
    public void refresh(LivingEntity self, Iterable<? extends Entity> loaded) {
        if (self == null) {
            clear();
            return;
        }
        // Roll the position history forward BEFORE computing the scan, so the scan reads a
        // full-tick velocity rather than a ~0 delta.
        Map<Integer, Vec3> pos = new HashMap<>();
        Map<Integer, Vec3> vel = new HashMap<>();
        for (Entity e : loaded) {
            if (!(e instanceof Projectile)) continue;
            Vec3 cur = e.position();
            Vec3 prev = lastProjPos.get(e.getId());
            pos.put(e.getId(), cur);
            vel.put(e.getId(), prev != null ? cur.subtract(prev) : e.getDeltaMovement());
        }
        lastProjPos.clear();
        lastProjPos.putAll(pos);
        velocities = Map.copyOf(vel);
        latest = ThreatScanner.compute(self.level(), self, ThreatScanner.DEFAULT_RADIUS, velocities);
    }

    public void clear() {
        lastProjPos.clear();
        velocities = Map.of();
        latest = ThreatScanner.EMPTY;
    }

    /** The scan from the most recent {@link #refresh}. */
    public ThreatScanner.Scan current() {
        return latest;
    }

    /** The projectile velocities the most recent {@link #refresh} derived, by entity id. */
    public Map<Integer, Vec3> velocities() {
        return velocities;
    }
}

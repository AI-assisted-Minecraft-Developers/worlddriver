package net.magicterra.agent.bot.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase B/C shared sensing: one per-tick scan of nearby hostiles + incoming
 * projectiles, scored so the reflex chains (panic/dodge) and the combat loop
 * (Phase C) read the same picture instead of each re-scanning. Purely client-side
 * (reads {@code entitiesForRendering} + client creeper-swell), so it self-skips
 * with an empty scan when there's no client level.
 *
 * <p>Results are cached by the player's tick count: many readers in one tick
 * (panic + dodge + shield + combat) share a single scan.
 */
public final class ThreatScanner {
    private ThreatScanner() {}

    /** A scored hostile. {@code entity} is kept so chains can act on it directly. */
    public record Threat(Entity entity, int id, String type, double distance,
                         boolean canSeeMe, boolean facingMe, boolean charging,
                         double score, float creeperSwell) {}

    /** An in-flight projectile heading roughly at the player. */
    public record Incoming(Entity entity, int id, String type, Vec3 pos, Vec3 vel,
                           boolean willHit, int ticksToImpact) {}

    public record Scan(List<Threat> threats, List<Incoming> projectiles) {
        public boolean isEmpty() { return threats.isEmpty() && projectiles.isEmpty(); }
        public Threat top() { return threats.isEmpty() ? null : threats.get(0); }
    }

    private static final Scan EMPTY = new Scan(List.of(), List.of());
    private static final int DEFAULT_RADIUS = 24;
    private static final int PROJECTILE_LOOKAHEAD = 30;

    private static volatile Scan latest = EMPTY;

    /** Last-tick projectile positions, keyed by entity id. */
    private static final Map<Integer, Vec3> lastProjPos = new ConcurrentHashMap<>();
    /** This tick's estimated projectile velocities (blocks/tick) from the position
     *  delta — client projectiles report {@code getDeltaMovement()}≈0 (the client
     *  interpolates their position, doesn't simulate physics). Computed once per
     *  {@link #refresh} so every {@link #compute} caller (the reflex scan and the
     *  observe verb, which may run mid-tick) shares one full-tick value instead of
     *  re-deriving a near-zero delta against the just-rolled-forward position. */
    private static final Map<Integer, Vec3> projVel = new ConcurrentHashMap<>();

    /** Recompute the shared scan — called once per client tick by the bot host so
     *  every reflex reader ({@link #current}) sees the same fresh picture. Driven
     *  explicitly by the tick loop rather than memoised on a tick counter (the
     *  latter went stale in headless runs and starved PanicChain). */
    public static void refresh(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            latest = EMPTY; lastProjPos.clear(); projVel.clear(); return;
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
        projVel.clear();     projVel.putAll(vel);
        latest = compute(mc, DEFAULT_RADIUS);
    }

    /** The shared scan from the most recent {@link #refresh} this tick. */
    public static Scan current(Minecraft mc) {
        return latest;
    }

    /** Fresh scan at an explicit radius (for the {@code mc.observe.threats} verb). */
    public static Scan compute(Minecraft mc, int radius) {
        if (mc.player == null || mc.level == null) return EMPTY;
        var player = mc.player;
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        AABB box = new AABB(px - radius, py - radius, pz - radius, px + radius, py + radius, pz + radius);
        Vec3 myEye = player.getEyePosition();
        AABB myBody = player.getBoundingBox().inflate(0.35);

        List<Threat> threats = new ArrayList<>();
        List<Incoming> incoming = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == player) continue;
            if (!box.intersects(e.getBoundingBox())) continue;

            if (e instanceof Projectile pr) {
                Incoming in = assessProjectile(pr, myBody);
                if (in != null) incoming.add(in);
                continue;
            }
            // Skip corpses: a just-killed mob lingers in the client entity list for
            // its ~20-tick death animation (isAlive()=false, still instanceof Zombie),
            // and a dead mob is no threat — exclude it so reflexes and the combat
            // loop (and mc.observe.threats) see a cleared field the moment it dies.
            if (!(e instanceof Enemy) || !(e instanceof LivingEntity) || !e.isAlive()) continue;

            double dist = Math.sqrt(e.distanceToSqr(px, py, pz));
            boolean canSee = lineOfSight(mc, e, myEye);
            boolean facing = facingPlayer(e, player.position());
            boolean charging = (e instanceof RangedAttackMob) && facing && canSee;
            float swell = (e instanceof Creeper c) ? c.getSwelling(1f) : 0f;
            double score = score(e, dist, radius, canSee, charging, swell);
            threats.add(new Threat(e, e.getId(), typeId(e), dist, canSee, facing, charging, score, swell));
        }
        threats.sort(Comparator.comparingDouble((Threat t) -> t.score).reversed());
        return new Scan(List.copyOf(threats), List.copyOf(incoming));
    }

    private static Incoming assessProjectile(Projectile pr, AABB target) {
        Vec3 pos = pr.position();
        // Use this tick's tracked velocity (client deltaMovement is unreliable for
        // projectiles); fall back to deltaMovement on first sight.
        Vec3 tracked = projVel.get(pr.getId());
        Vec3 vel = (tracked != null) ? tracked : pr.getDeltaMovement();
        if (vel.lengthSqr() < 1.0e-4) return null;          // resting / stuck / just appeared
        int hitT = -1;
        Vec3 step = pos;
        for (int t = 1; t <= PROJECTILE_LOOKAHEAD; t++) {
            step = step.add(vel);
            if (target.contains(step)) { hitT = t; break; }
        }
        // Only report projectiles actually closing on us (will hit, or moving
        // toward the player's column) — ignore arrows flying away.
        boolean toward = pos.add(vel).distanceToSqr(target.getCenter()) < pos.distanceToSqr(target.getCenter());
        if (hitT < 0 && !toward) return null;
        return new Incoming(pr, pr.getId(), typeId(pr), pos, vel, hitT > 0, hitT > 0 ? hitT : -1);
    }

    private static boolean lineOfSight(Minecraft mc, Entity from, Vec3 toEye) {
        try {
            BlockHitResult hit = mc.level.clip(new ClipContext(
                    from.getEyePosition(), toEye,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from));
            return hit.getType() == HitResult.Type.MISS;
        } catch (RuntimeException e) {
            return true;   // err on the side of "visible" so reflexes still react
        }
    }

    private static boolean facingPlayer(Entity e, Vec3 playerPos) {
        Vec3 look = e.getLookAngle();
        Vec3 toMe = playerPos.subtract(e.position());
        if (toMe.lengthSqr() < 1.0e-6) return true;
        return look.dot(toMe.normalize()) > 0.6;     // within ~53°
    }

    private static double score(Entity e, double dist, int radius, boolean canSee,
                                boolean charging, float swell) {
        double prox = Math.max(0.0, 1.0 - dist / radius);
        double typeW = (e instanceof Creeper) ? 1.0 : (e instanceof RangedAttackMob ? 0.7 : 0.5);
        double s = typeW * prox;
        if (canSee)   s += 0.15;
        if (charging) s += 0.20;
        if (swell > 0) s += swell * 0.5;
        return Math.max(0.0, Math.min(1.0, s));
    }

    private static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    // === verb serialization (mc.observe.threats) =============================

    public static Map<String, Object> toMap(Scan s) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> threats = new ArrayList<>();
        for (Threat t : s.threats()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("type", t.type());
            m.put("pos", posMap(t.entity().position()));
            m.put("distance", t.distance());
            m.put("hostile", true);
            m.put("canSeeMe", t.canSeeMe());
            m.put("facingMe", t.facingMe());
            m.put("charging", t.charging() ? "bow" : "none");
            m.put("creeperSwell", (double) t.creeperSwell());
            m.put("threat", t.score());
            threats.add(m);
        }
        List<Object> proj = new ArrayList<>();
        for (Incoming in : s.projectiles()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", in.id());
            m.put("type", in.type());
            m.put("pos", posMap(in.pos()));
            m.put("vel", posMap(in.vel()));
            m.put("willHit", in.willHit());
            m.put("ticksToImpact", in.ticksToImpact());
            proj.add(m);
        }
        out.put("threats", threats);
        out.put("incomingProjectiles", proj);
        return out;
    }

    private static Map<String, Object> posMap(Vec3 v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", v.x); m.put("y", v.y); m.put("z", v.z);
        return m;
    }
}

package net.magicterra.worlddriver.bot.world;

import static net.magicterra.worlddriver.game.observe.RegistryIds.typeId;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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
import java.util.List;
import java.util.Map;

/**
 * Phase B/C shared sensing: one per-tick scan of nearby hostiles + incoming
 * projectiles, scored so the reflex chains (panic/dodge) and the combat loop
 * (Phase C) read the same picture instead of each re-scanning.
 *
 * <p><b>Dist-neutral.</b> The sensing core ({@link #compute(Level, Player, int)})
 * scans via {@code Level.getEntities} + {@code Level.clip} — an EntityGetter API that
 * works on a {@code ClientLevel} AND a {@code ServerLevel} — so it loads and runs on a
 * dedicated server (a server-driven {@code CombatProcess} over a FakePlayer reads the
 * same scored picture). The per-tick cache, and the projectile velocities it derives,
 * are each bot's own {@link ThreatWatch}.
 */
public final class ThreatScanner {
    private ThreatScanner() {}

    /** A scored hostile. {@code entity} is kept so chains can act on it directly.
     *  {@code attackedMe} = this entity is the player's most recent damager (vanilla
     *  {@code getLastDamageSource()} 40-tick window) — the one signal that makes a
     *  non-{@code Enemy} mob (angered wolf/bee/…) a threat. */
    public record Threat(Entity entity, int id, String type, double distance,
                         boolean canSeeMe, boolean facingMe, boolean charging,
                         double score, float creeperSwell, boolean attackedMe) {}

    /** An in-flight projectile heading roughly at the player. */
    public record Incoming(Entity entity, int id, String type, Vec3 pos, Vec3 vel,
                           boolean willHit, int ticksToImpact) {}

    public record Scan(List<Threat> threats, List<Incoming> projectiles) {
        public boolean isEmpty() { return threats.isEmpty() && projectiles.isEmpty(); }
        public Threat top() { return threats.isEmpty() ? null : threats.get(0); }

        /**
         * A ranged attacker whose shot actually CONNECTED — {@link Threat#attackedMe} is the
         * vanilla last-damager over a ~2s window.
         *
         * <p>It lives on the scan rather than in a reflex chain because it asks nothing of the
         * bot's policy: no radius, no HP, no line-of-sight. That is the line between this and its
         * neighbours in {@code RetreatChain} — {@code rangedThreatAiming} compares against
         * {@code RANGED_RADIUS}, {@code visibleRangedThreatWithin} takes a radius, and a
         * threshold is a policy the chain owns. Both chains that read this had a private
         * byte-identical copy, one of them under a javadoc saying it "mirrors" the other.
         *
         * <p>Two chains, two measurements, one predicate. {@code RetreatChain} flees on it
         * because it is the only trigger that survives when both the distance yardstick and the
         * LoS-based aim signal fail — sniped through a stair shaft where the eye-ray is blocked
         * but the arrow arcs in (death #6). {@code BunkerChain} seals a roof on it because being
         * shot by a skeleton/witch is the precise "pinned in the open" signal a plain flee cannot
         * answer (death #26).
         */
        public boolean underRangedFire() {
            for (Threat t : threats) {
                if (t.attackedMe() && t.entity() instanceof RangedAttackMob) return true;
            }
            return false;
        }
    }

    static final Scan EMPTY = new Scan(List.of(), List.of());
    static final int DEFAULT_RADIUS = 24;
    private static final int PROJECTILE_LOOKAHEAD = 30;

    /** Convenience overload at the default radius — the server combat loop path. */
    public static Scan compute(Level lvl, LivingEntity player) {
        return compute(lvl, player, DEFAULT_RADIUS);
    }

    /** {@link #compute(Level, LivingEntity, int, Map)} with every projectile's velocity read off the entity,
     *  which is right on a server and ~0 on a client. */
    public static Scan compute(Level lvl, LivingEntity player, int radius) {
        return compute(lvl, player, radius, Map.of());
    }

    /** Level+Player core: works on BOTH client and server. Scans hostiles +
     *  projectiles via {@code Level.getEntities} (EntityGetter, polymorphic over
     *  ClientLevel/ServerLevel) — no client {@code Minecraft} dependency, so the
     *  server combat loop (over a FakePlayer) reads the same scored picture.
     *  {@code projVel} is the velocity a {@link ThreatWatch} derived for each projectile it
     *  has seen before, by entity id; one missing from it falls back to its own
     *  {@code getDeltaMovement()}.
     *
     *  <p>Over a player every {@link Enemy} is a threat, since every one of them hunts players. Over a
     *  mob only a mob that has it as its target is: hostiles do not hunt each other, so a driven zombie
     *  that fled every zombie in sight would be fleeing nothing. Either way the last attacker counts. */
    public static Scan compute(Level lvl, LivingEntity player, int radius, Map<Integer, Vec3> projVel) {
        if (lvl == null || player == null) return EMPTY;
        boolean isPlayer = player instanceof Player;
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        AABB box = new AABB(px - radius, py - radius, pz - radius, px + radius, py + radius, pz + radius);
        Vec3 myEye = player.getEyePosition();
        AABB myBody = player.getBoundingBox().inflate(0.35);

        // The player's most recent damager (vanilla 40-tick window, maintained on
        // BOTH sides: server hurt(), client handleDamageEvent). Being hit is the
        // highest-confidence threat signal there is, so the attacker joins the scan
        // even when it isn't an Enemy — angered NEUTRAL mobs (wolf/bee/polar bear)
        // never implement Enemy and were invisible to every reflex chain (gap #55).
        int attackerId = -1;
        DamageSource lastSrc = player.getLastDamageSource();
        if (lastSrc != null && lastSrc.getEntity() instanceof LivingEntity le
                && le != player && le.isAlive()) {
            attackerId = le.getId();
        }

        List<Threat> threats = new ArrayList<>();
        List<Incoming> incoming = new ArrayList<>();
        for (Entity e : lvl.getEntities(player, box, x -> true)) {
            if (e == player) continue;

            if (e instanceof Projectile pr) {
                Incoming in = assessProjectile(pr, myBody, projVel);
                if (in != null) incoming.add(in);
                continue;
            }
            // Skip corpses: a just-killed mob lingers in the entity list for its
            // ~20-tick death animation (isAlive()=false, still instanceof Zombie),
            // and a dead mob is no threat — exclude it so reflexes and the combat
            // loop (and mc.observe.threats) see a cleared field the moment it dies.
            boolean attackedMe = e.getId() == attackerId;
            boolean hunts = isPlayer ? e instanceof Enemy : e instanceof Mob m && m.getTarget() == player;
            if ((!hunts && !attackedMe) || !(e instanceof LivingEntity) || !e.isAlive()) continue;

            double dist = Math.sqrt(e.distanceToSqr(px, py, pz));
            boolean canSee = lineOfSight(lvl, e, myEye);
            boolean facing = facingPlayer(e, player.position());
            boolean charging = (e instanceof RangedAttackMob) && facing && canSee;
            float swell = (e instanceof Creeper c) ? c.getSwelling(1f) : 0f;
            double score = score(e, dist, radius, canSee, charging, swell);
            // "It just hit me" outranks any passive proximity read: floor + boost so
            // the attacker wins target selection over an idle mob that merely stands
            // closer, while a swelling creeper can still take over.
            if (attackedMe) score = Math.min(1.0, Math.max(score, 0.5) + 0.25);
            threats.add(new Threat(e, e.getId(), typeId(e), dist, canSee, facing, charging, score, swell, attackedMe));
        }
        threats.sort(Comparator.comparingDouble((Threat t) -> t.score).reversed());
        return new Scan(List.copyOf(threats), List.copyOf(incoming));
    }

    private static Incoming assessProjectile(Projectile pr, AABB target, Map<Integer, Vec3> projVel) {
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

    private static boolean lineOfSight(Level lvl, Entity from, Vec3 toEye) {
        try {
            BlockHitResult hit = lvl.clip(new ClipContext(
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
}

package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Phase C — the active combat loop. A {@link BotProcess} run by the scheduler's
 * {@code CombatChain} (priority {@code COMBAT} 60, above the user task), it picks a
 * hostile target, closes to weapon range, and lands cooldown-gated hits until the
 * target (or the whole area, in ENGAGE mode) is dead, then self-terminates so the
 * suspended user task resumes.
 *
 * <p>Drives through the {@link Avatar} seam: locomotion via the player's own input
 * ({@code commandMove}/{@code commandForward}/{@code commandJump} — never the shared
 * human keybinds), the hit via {@link Avatar#attackEntity} (the vanilla left-click
 * path: weapon damage, sweep, knockback, crit), and the bow draw via
 * {@link Avatar#commandUseItem}. Timing is judged on the {@link Player} state
 * (attack cooldown {@link Player#getAttackStrengthScale}, ground/fall for crits) so
 * it stays correct under server lag. The same code runs over a client
 * {@code LocalPlayer} (zero regression) or a server {@code FakePlayer}; entity
 * sensing reads {@link ThreatScanner} (the shared client scan on the client, a fresh
 * {@code compute} on the server).
 *
 * <p>Three intents:
 * <ul>
 *   <li>{@code KILL} — a specific target ({@code id} or {@code type}); done when it dies.</li>
 *   <li>{@code ENGAGE} — clear every hostile in scan range; done when none remain.</li>
 *   <li>{@code DEFEND} — only retaliate against hostiles actively eyeing the bot
 *       (facing + line-of-sight, close); done when nothing is pressing.</li>
 * </ul>
 *
 * <p>Melee closes to {@link BotConfig#combatReach} (pathing via {@link Walker}) and
 * pre-jumps to crit; ranged (bow/crossbow in hand) keeps {@link BotConfig#kiteDistance},
 * drawing and releasing at full charge.
 */
public final class CombatProcess implements BotProcess {

    public enum Mode { KILL, ENGAGE, DEFEND }

    /** Re-acquire window: a locked target out of sight this many ticks is dropped. */
    private static final int LOCK_GRACE = 20;
    /** A hostile counts as "pressing" (DEFEND) within this distance. */
    private static final double DEFEND_RANGE = 9.0;
    /** Level scan radius for KILL-by-type. */
    private static final double SCAN_RADIUS = 32.0;
    /** Ticks between melee strafe direction flips when orbiting a swarm. */
    private static final int STRAFE_FLIP = 16;
    /** Full bow draw (vanilla): 20 ticks of use = max power. */
    private static final int BOW_FULL_DRAW = 20;
    /** Post-kill drop sweep: item scan radius around the last kill position. */
    private static final double COLLECT_RADIUS = 8.0;
    /** Post-kill drop sweep: hard tick budget so an unreachable drop can't wedge
     *  the process open (the suspended user task must resume). */
    private static final int COLLECT_BUDGET_TICKS = 100;

    private final Mode mode;
    private final Integer targetId;     // KILL by entity id (nullable)
    private final String targetType;    // KILL by entity type id (nullable)

    private final Walker walker = new Walker("combat");
    private Integer lockedId;
    private boolean lockWasAlive;       // last-seen liveness of the locked target
    private int lostTicks;              // consecutive ticks the locked target was unseen
    private BlockPos lastGoalBlock;
    private int ticks;
    private boolean approaching;        // currently driving the Walker
    private BlockPos lastTargetPos;     // last seen position of a locked target
    private int collectTicks;           // post-kill drop-sweep tick counter

    public CombatProcess(Mode mode, Integer targetId, String targetType) {
        this.mode = mode;
        this.targetId = targetId;
        this.targetType = targetType;
    }

    @Override public String kind() { return "combat"; }

    @Override public void attach(BotState s) {
        s.combat.active = true;
        s.combat.goal = "combat " + mode.name().toLowerCase()
                + (targetId != null ? " id=" + targetId : "")
                + (targetType != null ? " type=" + targetType : "");
        s.combat.startedAtMs = System.currentTimeMillis();
        s.combat.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null || p.level() == null) { cleanup(a); return true; }
        ticks++;

        Entity target = acquireTarget(p, st);
        if (target == null) {
            // KILL: target dead/gone → mission complete. ENGAGE/DEFEND: area clear.
            // Before terminating, sweep the drops: melee kites away from the kill
            // spot, so the loot (meat, rotten flesh) lands blocks behind us and a
            // "successful" hunt puts nothing in the inventory (live 01:32: six
            // kills, one porkchop banked). KILL/ENGAGE only end with the area
            // clear, so the walk-back is safe; DEFEND stays snappy — it exists to
            // get back to the suspended task, not to loot.
            if (mode != Mode.DEFEND && lastTargetPos != null
                    && BotConfig.combatCollectDrops
                    && !collectSweep(a, p, w, st)) {
                return false;
            }
            cleanup(a);
            return true;
        }
        lastTargetPos = target.blockPosition();

        double dist = Math.sqrt(p.distanceToSqr(target));
        if (isRanged(p)) {
            rangedTick(a, p, target, dist, st);
        } else {
            meleeTick(a, p, w, target, dist, st);
        }
        return false;
    }

    // === target selection ====================================================

    private Entity acquireTarget(Player p, BotState st) {
        Level lvl = p.level();
        // Honour an existing lock to avoid per-tick target thrash.
        if (lockedId != null) {
            Entity locked = lvl.getEntity(lockedId);
            if (locked != null && locked.isAlive() && stillValid(p, locked)) {
                lockWasAlive = true;
                lostTicks = 0;
                return locked;
            }
            // A target we'd been hitting that we OBSERVE die — still present in the
            // level but no longer alive (the ~20-tick death animation lingers before
            // removal) — counts as a kill. A target that merely VANISHES (locked ==
            // null: despawn, chunk unload, peaceful toggle, render-distance) is NOT a
            // kill we made, so it must not inflate combatKills. `dead` still drives the
            // lock-clearing/grace logic below; only `died` gates the counter.
            boolean dead = (locked == null) || !locked.isAlive();
            boolean died = locked != null && !locked.isAlive();
            if (died && lockWasAlive) { st.combatKills++; }
            if (!dead && ++lostTicks < LOCK_GRACE) {
                // Temporarily out of our validity window (e.g. looked away in DEFEND)
                // but still alive — keep the lock briefly before re-picking.
                return locked;
            }
            lockedId = null;
            lostTicks = 0;
            lockWasAlive = false;
        }
        Entity next = pick(p);
        if (next != null) {
            lockedId = next.getId();
            lockWasAlive = true;
            lostTicks = 0;
        }
        return next;
    }

    /** Whether {@code e} is still a legitimate target for this mode. */
    private boolean stillValid(Player p, Entity e) {
        return switch (mode) {
            case KILL -> targetId != null ? e.getId() == targetId : matchesType(e);
            case ENGAGE -> isHostile(e);
            case DEFEND -> isHostile(e) && pressing(e, p);
        };
    }

    private Entity pick(Player p) {
        if (mode == Mode.KILL) {
            if (targetId != null) {
                Entity e = p.level().getEntity(targetId);
                return (e != null && e.isAlive() && e != p) ? e : null;
            }
            return nearestOfType(p);
        }
        // ENGAGE / DEFEND read the shared threat scan (highest score first).
        ThreatScanner.Scan scan = scanFor(p);
        for (ThreatScanner.Threat t : scan.threats()) {
            Entity e = t.entity();
            if (e == null || !e.isAlive()) continue;
            if (mode == Mode.DEFEND && !(t.facingMe() && t.canSeeMe() && t.distance() <= DEFEND_RANGE)) continue;
            return e;
        }
        return null;
    }

    private Entity nearestOfType(Player p) {
        Entity best = null;
        double bestD = SCAN_RADIUS * SCAN_RADIUS;
        AABB box = p.getBoundingBox().inflate(SCAN_RADIUS);
        for (Entity e : p.level().getEntities(p, box, x -> true)) {
            if (e == p || !e.isAlive()) continue;
            if (!matchesType(e)) continue;
            double d = e.distanceToSqr(p);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    private boolean matchesType(Entity e) {
        return targetType != null && targetType.equals(typeId(e));
    }

    private static boolean isHostile(Entity e) {
        return e instanceof Enemy && e instanceof LivingEntity && e.isAlive();
    }

    /** A hostile is "pressing" (DEFEND) when it can see the bot and is facing it. */
    private boolean pressing(Entity e, Player p) {
        for (ThreatScanner.Threat t : scanFor(p).threats()) {
            if (t.entity() == e) return t.facingMe() && t.canSeeMe() && t.distance() <= DEFEND_RANGE;
        }
        return false;
    }

    /** The threat picture: the shared client scan (refreshed each tick by the bot
     *  host) on the client; a fresh {@link ThreatScanner#compute} on the server, which
     *  has no refresh loop. {@code Level.isClientSide} is set on both ClientLevel and
     *  ServerLevel. */
    private ThreatScanner.Scan scanFor(Player p) {
        return p.level().isClientSide
                ? ThreatScanner.current()
                : ThreatScanner.compute(p.level(), p, (int) SCAN_RADIUS);
    }

    // === melee ===============================================================

    private void meleeTick(Avatar a, Player p, WorldView w, Entity target, double dist, BotState st) {
        if (dist > BotConfig.combatReach + 0.4) {
            approach(a, w, target);
            return;
        }
        // In range: stop pathing, face the target, strafe a swarm, swing on cooldown.
        if (approaching) { a.releaseInputs(); approaching = false; lastGoalBlock = null; }
        p.setSprinting(false);
        aimAt(p, target, 0.0);
        a.commandMove(strafeImpulse(p), 0f);

        float scale = p.getAttackStrengthScale(0.5f);
        // Pre-jump so we're descending when the cooldown completes (vanilla crit rule).
        a.commandJump(BotConfig.combatCrit && p.onGround() && scale >= 0.85f && scale < 1.0f);
        if (scale >= 1.0f) {
            a.attackEntity(target);
            p.swing(InteractionHand.MAIN_HAND);
            st.combatSwings++;
            st.combatWellTimed++;
            if (!p.onGround() && p.getDeltaMovement().y < 0.0) st.combatCrits++;
        }
    }

    /** Path toward the target's block (re-goaling as it moves), like FollowProcess. */
    private void approach(Avatar a, WorldView w, Entity target) {
        approaching = true;
        BlockPos tb = target.blockPosition();
        int radius = Math.max(1, (int) Math.floor(BotConfig.combatReach));
        if (lastGoalBlock == null || !lastGoalBlock.equals(tb)) {
            walker.setGoal(new Goal.Near(tb, radius));
            lastGoalBlock = tb;
        }
        walker.tick(a, w);
    }

    /** Orbit a swarm (≥2 close hostiles) to avoid being surrounded; stand still vs a
     *  lone target so the hit rhythm isn't interrupted. Returns the camera-frame strafe
     *  impulse (+1 = left, -1 = right, 0 = hold), the {@code commandMove} equivalent of
     *  the old keyLeft/keyRight presses. */
    private float strafeImpulse(Player p) {
        if (closeHostiles(p) < 2) return 0f;
        boolean left = (ticks / STRAFE_FLIP) % 2 == 0;
        return left ? 1f : -1f;
    }

    private int closeHostiles(Player p) {
        int n = 0;
        for (ThreatScanner.Threat t : scanFor(p).threats()) {
            if (t.distance() <= BotConfig.combatReach + 2.0) n++;
        }
        return n;
    }

    // === ranged ==============================================================

    private void rangedTick(Avatar a, Player p, Entity target, double dist, BotState st) {
        if (approaching) { approaching = false; lastGoalBlock = null; }
        aimAt(p, target, dist * 0.12);   // lead a little high for arrow drop
        p.setSprinting(false);
        // Keep the kite band: back up if too close, advance if too far, else hold.
        float fwd = 0f;
        if (dist < BotConfig.kiteDistance - 1.0) {
            fwd = -1f;                                  // step back, keep facing the target
        } else if (dist > BotConfig.kiteDistance + 2.0) {
            fwd = 1f;
        }
        a.commandForward(fwd);
        // Draw the bow (hold use); release the moment it's fully charged → fires.
        if (p.isUsingItem() && p.getTicksUsingItem() >= BOW_FULL_DRAW) {
            a.commandUseItem(false);                    // up-edge = release = shoot
            st.combatSwings++;
            st.combatWellTimed++;
        } else {
            a.commandUseItem(true);
        }
    }

    private static boolean isRanged(Player p) {
        ItemStack m = p.getMainHandItem();
        return m.getItem() instanceof BowItem || m.getItem() instanceof CrossbowItem;
    }

    // === post-kill drop sweep ===============================================

    /** Walk over the item drops around the last kill position (vanilla pickup is
     *  automatic on touch). Returns {@code true} when done: no live drops left in
     *  {@link #COLLECT_RADIUS} of the kill spot, or the tick budget ran out
     *  (unreachable drop — never wedge the suspended user task). */
    private boolean collectSweep(Avatar a, Player p, WorldView w, BotState st) {
        if (++collectTicks > COLLECT_BUDGET_TICKS) return true;
        AABB box = new AABB(lastTargetPos).inflate(COLLECT_RADIUS);
        ItemEntity nearest = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity ie : p.level().getEntitiesOfClass(ItemEntity.class, box)) {
            // NOTE: items with pickup delay still count — a just-dropped stack's
            // ~10-tick delay expires while we walk to it; skipping delayed items
            // would end the sweep instantly on the very drops we came for.
            if (!ie.isAlive()) continue;
            double d = ie.distanceToSqr(p);
            if (d < bestD) { bestD = d; nearest = ie; }
        }
        if (nearest == null) return true;
        if (collectTicks == 1) st.combat.goal = "combat collect-drops";
        BlockPos ib = nearest.blockPosition();
        if (lastGoalBlock == null || !lastGoalBlock.equals(ib)) {
            walker.setGoal(new Goal.Near(ib, 1));
            lastGoalBlock = ib;
        }
        Walker.Step s = walker.tick(a, w);
        if (s != Walker.Step.WALKING) {
            // The walker parked in a terminal (ARRIVED path-consumed short of the
            // stack / FAILED) while the drop still exists: without a reset it
            // no-ops every subsequent tick (setGoal is the only thing that clears
            // a terminal — measured live in the t0 diag round: sweepTicks=101,
            // 0.8 blocks moved, lastStep=ARRIVED). Same idiom as FollowProcess:
            // drop the goal so next tick re-goals at the CURRENT nearest drop
            // with a fresh path. Near radius 1 (not 0): pickup touch reaches an
            // adjacent cell, and radius-0 exact-cell goals are the rarely-driven
            // shape goto itself abandoned (near=2 default).
            lastGoalBlock = null;
        }
        return false;
    }

    // === helpers =============================================================

    /** Snap head+body to the target's mid-height (+{@code yLead} blocks up). Snaps
     *  rather than smooth-pans because the attack/shot raycast needs the crosshair
     *  on target the same tick. */
    private static void aimAt(Player p, Entity e, double yLead) {
        Vec3 eye = p.getEyePosition();
        double dx = e.getX() - eye.x;
        double dy = (e.getY() + e.getBbHeight() * 0.5 + yLead) - eye.y;
        double dz = e.getZ() - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(pitch);
    }

    private static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    private void cleanup(Avatar a) {
        a.releaseInputs();
        a.commandUseItem(false);
        Player p = a.player();
        if (p != null && p.isUsingItem()) p.stopUsingItem();
    }
}

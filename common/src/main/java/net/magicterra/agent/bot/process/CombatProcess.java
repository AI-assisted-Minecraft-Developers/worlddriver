package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;

/**
 * Phase C — the active combat loop. A {@link BotProcess} run by the scheduler's
 * {@code CombatChain} (priority {@code COMBAT} 60, above the user task), it picks a
 * hostile target, closes to weapon range, and lands cooldown-gated hits until the
 * target (or the whole area, in ENGAGE mode) is dead, then self-terminates so the
 * suspended user task resumes.
 *
 * <p>All timing is judged on the <em>client</em> {@code mc.player} state — attack
 * cooldown ({@link LocalPlayer#getAttackStrengthScale}), ground/fall for crits — so
 * it stays correct under server lag (no reliance on round-trip packets). The actual
 * hit goes through {@code mc.gameMode.attack}, the same vanilla path a left-click
 * takes, so the server applies weapon damage, sweep, knockback and crit rules.
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

    private final Mode mode;
    private final Integer targetId;     // KILL by entity id (nullable)
    private final String targetType;    // KILL by entity type id (nullable)

    private final Walker walker = new Walker();
    private Integer lockedId;
    private boolean lockWasAlive;       // last-seen liveness of the locked target
    private int lostTicks;              // consecutive ticks the locked target was unseen
    private BlockPos lastGoalBlock;
    private int ticks;
    private boolean approaching;        // currently driving the Walker

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

    @Override public boolean tick(Minecraft mc, WorldView w, BotState st) {
        LocalPlayer p = mc.player;
        Level lvl = mc.level;
        if (p == null || lvl == null) { cleanup(mc); return true; }
        ticks++;

        Entity target = acquireTarget(mc, p, st);
        if (target == null) {
            // KILL: target dead/gone → mission complete. ENGAGE/DEFEND: area clear.
            cleanup(mc);
            return true;
        }

        double dist = Math.sqrt(p.distanceToSqr(target));
        if (isRanged(p)) {
            rangedTick(mc, p, target, dist, st);
        } else {
            meleeTick(mc, p, w, target, dist, st);
        }
        return false;
    }

    // === target selection ====================================================

    private Entity acquireTarget(Minecraft mc, LocalPlayer p, BotState st) {
        Level lvl = mc.level;
        // Honour an existing lock to avoid per-tick target thrash.
        if (lockedId != null) {
            Entity locked = lvl.getEntity(lockedId);
            if (locked != null && locked.isAlive() && stillValid(mc, p, locked)) {
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
        Entity next = pick(mc, p);
        if (next != null) {
            lockedId = next.getId();
            lockWasAlive = true;
            lostTicks = 0;
        }
        return next;
    }

    /** Whether {@code e} is still a legitimate target for this mode. */
    private boolean stillValid(Minecraft mc, LocalPlayer p, Entity e) {
        return switch (mode) {
            case KILL -> targetId != null ? e.getId() == targetId : matchesType(e);
            case ENGAGE -> isHostile(e);
            case DEFEND -> isHostile(e) && pressing(mc, e, p);
        };
    }

    private Entity pick(Minecraft mc, LocalPlayer p) {
        if (mode == Mode.KILL) {
            if (targetId != null) {
                Entity e = mc.level.getEntity(targetId);
                return (e != null && e.isAlive() && e != p) ? e : null;
            }
            return nearestOfType(mc, p);
        }
        // ENGAGE / DEFEND read the shared threat scan (highest score first).
        ThreatScanner.Scan scan = ThreatScanner.current(mc);
        for (ThreatScanner.Threat t : scan.threats()) {
            Entity e = t.entity();
            if (e == null || !e.isAlive()) continue;
            if (mode == Mode.DEFEND && !(t.facingMe() && t.canSeeMe() && t.distance() <= DEFEND_RANGE)) continue;
            return e;
        }
        return null;
    }

    private Entity nearestOfType(Minecraft mc, LocalPlayer p) {
        Entity best = null;
        double bestD = SCAN_RADIUS * SCAN_RADIUS;
        for (Entity e : mc.level.entitiesForRendering()) {
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
    private boolean pressing(Minecraft mc, Entity e, LocalPlayer p) {
        for (ThreatScanner.Threat t : ThreatScanner.current(mc).threats()) {
            if (t.entity() == e) return t.facingMe() && t.canSeeMe() && t.distance() <= DEFEND_RANGE;
        }
        return false;
    }

    // === melee ===============================================================

    private void meleeTick(Minecraft mc, LocalPlayer p, WorldView w, Entity target, double dist, BotState st) {
        if (dist > BotConfig.combatReach + 0.4) {
            approach(mc, w, target);
            return;
        }
        // In range: stop pathing, face the target, strafe a swarm, swing on cooldown.
        if (approaching) { releaseKeys(); approaching = false; lastGoalBlock = null; }
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        p.setSprinting(false);
        aimAt(p, target, 0.0);
        strafe(mc, target);

        float scale = p.getAttackStrengthScale(0.5f);
        if (BotConfig.combatCrit && p.onGround() && scale >= 0.85f && scale < 1.0f) {
            mc.options.keyJump.setDown(true);     // pre-jump: be descending when cooldown completes
        } else {
            mc.options.keyJump.setDown(false);
        }
        if (scale >= 1.0f) {
            mc.gameMode.attack(p, target);
            p.swing(InteractionHand.MAIN_HAND);
            st.combatSwings++;
            st.combatWellTimed++;
            if (!p.onGround() && p.getDeltaMovement().y < 0.0) st.combatCrits++;
        }
    }

    /** Path toward the target's block (re-goaling as it moves), like FollowProcess. */
    private void approach(Minecraft mc, WorldView w, Entity target) {
        approaching = true;
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        BlockPos tb = target.blockPosition();
        int radius = Math.max(1, (int) Math.floor(BotConfig.combatReach));
        if (lastGoalBlock == null || !lastGoalBlock.equals(tb)) {
            walker.setGoal(new Goal.Near(tb, radius));
            lastGoalBlock = tb;
        }
        walker.tick(mc, w);
    }

    /** Orbit a swarm (≥2 close hostiles) to avoid being surrounded; stand still vs a
     *  lone target so the hit rhythm isn't interrupted. */
    private void strafe(Minecraft mc, Entity target) {
        boolean swarm = closeHostiles(mc) >= 2;
        if (!swarm) {
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            return;
        }
        boolean left = (ticks / STRAFE_FLIP) % 2 == 0;
        mc.options.keyLeft.setDown(left);
        mc.options.keyRight.setDown(!left);
    }

    private int closeHostiles(Minecraft mc) {
        int n = 0;
        for (ThreatScanner.Threat t : ThreatScanner.current(mc).threats()) {
            if (t.distance() <= BotConfig.combatReach + 2.0) n++;
        }
        return n;
    }

    // === ranged ==============================================================

    private void rangedTick(Minecraft mc, LocalPlayer p, Entity target, double dist, BotState st) {
        if (approaching) { approaching = false; lastGoalBlock = null; }
        aimAt(p, target, dist * 0.12);   // lead a little high for arrow drop
        // Keep the kite band: back up if too close, advance if too far, else hold.
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keySprint.setDown(false);
        p.setSprinting(false);
        if (dist < BotConfig.kiteDistance - 1.0) {
            mc.options.keyDown.setDown(true);          // step back, keep facing the target
        } else if (dist > BotConfig.kiteDistance + 2.0) {
            mc.options.keyUp.setDown(true);
        }
        // Draw the bow (hold use); release the moment it's fully charged → fires.
        if (p.isUsingItem() && p.getTicksUsingItem() >= BOW_FULL_DRAW) {
            mc.options.keyUse.setDown(false);          // up-edge = release = shoot
            st.combatSwings++;
            st.combatWellTimed++;
        } else {
            mc.options.keyUse.setDown(true);
        }
    }

    private static boolean isRanged(LocalPlayer p) {
        ItemStack m = p.getMainHandItem();
        return m.getItem() instanceof BowItem || m.getItem() instanceof CrossbowItem;
    }

    // === helpers =============================================================

    /** Snap head+body to the target's mid-height (+{@code yLead} blocks up). Snaps
     *  rather than smooth-pans because the attack/shot raycast needs the crosshair
     *  on target the same tick. */
    private static void aimAt(LocalPlayer p, Entity e, double yLead) {
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

    private void cleanup(Minecraft mc) {
        releaseKeys();
        if (mc.options != null) mc.options.keyUse.setDown(false);
        if (mc.player != null && mc.player.isUsingItem()) mc.player.stopUsingItem();
    }
}

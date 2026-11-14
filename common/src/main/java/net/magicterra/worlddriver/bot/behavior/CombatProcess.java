package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.game.observe.RegistryIds.typeId;

import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.game.body.Attack;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Phase C — the active combat loop. A {@link BotProcess} run by the scheduler's {@code CombatChain} (priority
 * {@code COMBAT} 60, above the user task), it picks a hostile target, closes to weapon range, and lands cooldown-gated
 * hits until the target (or the whole area, in ENGAGE mode) is dead, then self-terminates so the suspended user task
 * resumes.
 *
 * <p>Drives through the {@link Body} seam: locomotion via the player's own input
 * ({@code commandMove}/{@code commandForward}/{@code commandJump} — never the shared human keybinds), the hit via
 * {@link Attack#attackEntity} (the vanilla left-click path: weapon damage, sweep, knockback, crit), and the bow draw
 * via {@link Equipment#commandUseItem}. Timing is judged on the {@link Player} state (attack cooldown
 * {@link Player#getAttackStrengthScale}, ground/fall for crits) so it stays correct under server lag. The same code
 * runs over a client {@code LocalPlayer} (zero regression) or a server {@code FakePlayer}; entity sensing reads
 * {@link ThreatScanner} (the shared client scan on the client, a fresh {@code compute} on the server).
 *
 * <p>Three intents:
 *
 * <ul>
 *   <li>{@code KILL} — a specific target ({@code id} or {@code type}); done when it dies.
 *   <li>{@code ENGAGE} — clear every hostile in scan range; done when none remain.
 *   <li>{@code DEFEND} — only retaliate against hostiles actively eyeing the bot (facing + line-of-sight, close); done
 *       when nothing is pressing.
 * </ul>
 *
 * <p>Melee closes to {@link BehaviorConfig#combatReach} (pathing via {@link Navigator}), jumps to land each swing as a
 * crit, steps back from a mob inside its strike distance, holds an off-hand shield between swings, and outruns or
 * braces against a fusing creeper (each behind its own {@code combat*} setting); ranged (bow/crossbow in hand) keeps
 * {@link BehaviorConfig#kiteDistance}, drawing and releasing at full charge.
 */
public final class CombatProcess implements BotProcess, Navigating {

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

    /**
     * Post-kill drop sweep: hard tick budget so an unreachable drop can't wedge the process open (the suspended user
     * task must resume).
     */
    private static final int COLLECT_BUDGET_TICKS = 100;

    /** A nearer hostile takes the lock from the locked one when it is this much nearer ({@code combatNearestFirst}). */
    private static final double NEARER_BY = 1.5;

    /** Vanilla: a shield blocks only once it has been raised this many ticks. */
    private static final int SHIELD_RAISE_TICKS = 5;

    /** The shield comes down this many ticks before the cooldown fills, so the swing tick sees it lowered. */
    private static final double SHIELD_LOWER_LEAD = 2.0;

    /** A mob within this distance is close enough to strike before the next swing: raise the shield against it. */
    private static final double SHIELD_MELEE_RANGE = 3.2;

    /**
     * Ticks of cooldown left at which to jump for a crit: a jump rises for about six ticks, so jumping seven before the
     * cooldown fills has the body falling when it does.
     */
    private static final double CRIT_JUMP_LEAD = 7.0;

    /**
     * Ticks before the crit's jump from which the footing takes the body's speed as a jump's drift: the ground's
     * friction stops a walk in about two ({@code combatFootingDrift}).
     */
    private static final double CRIT_JUMP_LOOK_AHEAD = 2.0;

    /** In melee, a target farther than this is walked toward while the cooldown fills ({@code combatPressIn}). */
    private static final double PRESS_IN_FROM = 2.6;

    /** How far ahead a strafe is judged for cover from bows ({@code combatTakeCover}). */
    private static final double COVER_STEP = 0.6;

    /** Hostiles within this distance are the pack the bot keeps in front of it ({@code combatKeepPackInFront}). */
    private static final double PACK_RANGE = 4.0;

    /** Ticks of bow draw after which a skeleton's shot is due within the shield's raise time. */
    private static final int DRAW_RAISE_TICKS = 8;

    /** A bow drawn past this looses before a lowered shield is back ({@code combatSwingBetweenShots}). */
    private static final int SAFE_LOWER_DRAW = 11;

    /** On the walk in, a bow drawn this far looses within the shield's raise time ({@code combatShieldLateDraw}). */
    private static final int APPROACH_LATE_DRAW = 13;

    /** ... when its skeleton is no farther than this. */
    private static final double APPROACH_LATE_RANGE = 8.0;

    /** The straight chase sprint-jumps while the target is farther than this ({@code combatSprintJumpChase}). */
    private static final double SPRINT_JUMP_FROM = 4.5;

    /** A projectile slower than this, in blocks per tick, is spent and blocks nothing. */
    private static final double SPENT_ARROW_SPEED = 0.5;

    /** A drawing skeleton other than the target holds the swing only within this ({@code combatSwingBetweenShots}). */
    private static final double SWING_HOLD_RANGE = 8.0;

    /** An arrow's speed in blocks per tick, for how long a loosed shot is still on its way. */
    private static final double ARROW_SPEED = 1.6;

    /** Ticks added to that flight for the arrow's spawn reaching the client after the draw is seen to end. */
    private static final int ARROW_SEEN_LAG = 3;

    /**
     * Ticks between the client raising the shield and the server counting it raised: the input goes out on the next
     * tick and the packet lands on the one after. Hits logged with the shield five to seven ticks up on the client and
     * the mob dead ahead still did damage ({@code combatShieldByLanding}).
     */
    private static final int SHIELD_SERVER_LAG = 2;

    /** A skeleton looses its arrow at this many ticks of draw. */
    private static final int LOOSE_DRAW = 20;

    /** The client learns a bow is being drawn a tick after the server starts it. */
    private static final int DRAW_SEEN_LAG = 1;

    /**
     * Shots from farther than this are not shielded on the walk in: the spread of a skeleton's aim on normal is most of
     * a block there, so most miss, and every shield raised walks a fifth of the speed.
     */
    private static final double LANDING_RANGE = 12.0;

    /** On the walk in, the shield goes up for a shot from no farther than this. */
    private static final double APPROACH_SHIELD_RANGE = 4.0;

    /** On the walk in, an arrow predicted to land within this many ticks raises the shield ... */
    private static final int ARROW_LANDING_TICKS = 4;

    /** ... when it is no farther than this. */
    private static final double ARROW_LANDING_RANGE = 8.0;

    /** A target farther than this is never chased in a straight line ({@code combatDirectChase}). */
    private static final double DIRECT_CHASE_RANGE = 16.0;

    /** How far past the body's side a straight chase wants the floor clear ({@code combatChaseBodyWidth}). */
    private static final double CHASE_SIDE_MARGIN = 0.1;

    /** While walking in, a shot from within this many degrees of the walk is one the raised shield faces. */
    private static final double APPROACH_BLOCK_CONE = 70.0;

    /** A mob nearer than this is inside its strike distance plus a margin ({@code combatKeepSpacing}). */
    private static final double SPACING = 2.2;

    /**
     * Ticks the client's picture of a mob trails the mob ({@code combatSwingRoom}): its position reaches the client a
     * tick late and is drawn by interpolation over the next few, and the bot's step takes a tick to reach the server.
     */
    private static final double VIEW_LAG_TICKS = 2.0;

    /**
     * Blocks a tick a mob walks per unit of its speed attribute squared: a player with speed 0.1 and the walk key held
     * walks 0.216 a tick, and a mob's walk input is its speed rather than 1.
     */
    private static final double MOB_WALK_PER_SPEED_SQUARED = 2.16;

    /** No walker is taken for faster than a sprinting player, whatever its attribute says. */
    private static final double MAX_CLOSING = 0.3;

    /** The share of its horizontal speed a body keeps from one tick in the air to the next. */
    private static final double AIR_KEEP = 0.91;

    /** A swelling creeper keeps swelling until its target is past 7 blocks; nearer than that, it is our problem. */
    private static final double CREEPER_DEFUSE_RANGE = 7.0;

    /** How far a sprint slides on after its keys are let go, in blocks. */
    private static final double SPRINT_SLIDE = 0.7;

    /** Below this speed, in blocks a tick, a slide toward the blast's edge is spent ({@code combatCreeperWalkOut}). */
    private static final double SLIDE_SPENT = 0.05;

    /** A creeper's blast hurts out to twice its power of 3; a little past that is clear of it. */
    private static final double BLAST_CLEAR = 6.5;

    /** A creeper explodes this many ticks after it starts to swell (vanilla maxSwell). */
    private static final int CREEPER_FUSE_TICKS = 30;

    /** Blocks a sprint from a standstill covers per tick on average over its first second. */
    private static final double SPRINT_PER_TICK = 0.22;

    /** This tick's hands, bound at the top of {@link #tick}, which is the one place they can be absent. */
    private Equipment equipment;

    private Attack attack;

    private final Mode mode;

    private final Integer targetId; // KILL by entity id (nullable)

    private final String targetType; // KILL by entity type id (nullable)

    private final Navigator navigator = new Navigator(NavigatorSettings.named("combat"));

    private Integer lockedId;

    private boolean lockWasAlive; // last-seen liveness of the locked target

    private int lostTicks; // consecutive ticks the locked target was unseen

    private BlockPos lastGoalBlock;

    private int lastQuarryId = -1; // entity the navigator is currently pursuing; -1 = none

    private int ticks;

    private boolean approaching; // currently driving the Navigator

    private BlockPos lastTargetPos; // last seen position of a locked target

    private int collectTicks; // post-kill drop-sweep tick counter

    private boolean shieldUp; // this process holds the use intent for the off-hand shield

    private int shieldLoweredAt = Integer.MIN_VALUE / 2; // the tick this process last lowered it

    private int shieldRaisedAt; // the tick this process last raised it, for the hurt log

    private float lastHealth = -1f; // last tick's health, for the hurt log

    private int lastShieldWear = -1; // last tick's shield damage value, for the blocked count

    private int drawPeak; // the furthest bow draw seen in the last second, for the hurt log

    private int drawPeakAt; // the tick it was seen

    private boolean drawPeakInSight; // whether that skeleton could see the bot then

    private int creeperLogged = -1; // the fusing creeper already logged

    private final java.util.Map<Integer, Integer> lastDraw = new java.util.HashMap<>(); // draw ticks by skeleton id

    private Vec3 loosedFrom; // eyes of the skeleton that last loosed at the bot

    private int loosedUntil = Integer.MIN_VALUE / 2; // the tick by which its arrow has landed

    private String phase = "start"; // what the fight did last tick, for the hurt log

    private String lastChase = "none"; // how the last walk in went, straight or routed, for the contact log

    private boolean lastCollided; // whether the body was against something sideways last tick, for the contact log

    private int slowTicks; // ticks in a row under a block a second with no hostile within four, for the slow log

    private String slowPhase = ""; // what the fight was doing when that spell began

    private int circleSide = 1; // which way round a fusing creeper the bot walks: +1 or -1

    private int weaponSlot = -1; // the hotbar slot the weapon was in when the fight reached melee, or -1

    private int swingsOnTarget; // swings at the locked target, logged when it dies

    /** Ticks spent in each phase, and ready swings held back and why: where a fight's time went. */
    private final java.util.Map<String, Integer> tickBook = new java.util.TreeMap<>();

    private boolean bracing; // committed to facing a fusing creeper behind the shield until it is gone

    /** A target that is gone is the fight won or the area clear; only losing the player fails it. */
    private String failure;

    /** The ticking bot's threat picture, taken from the state each tick hands in. */
    private net.magicterra.worlddriver.bot.world.ThreatWatch threats;

    /** How a fight is conducted: the modes a combat order can name. */
    public enum Mode {
        KILL,
        ENGAGE,
        DEFEND
    }

    @Override
    public Navigator navigator() {
        return navigator;
    }

    /** Creates a fight in {@code mode} against the entity {@code targetId} or the type {@code targetType}. */
    public CombatProcess(Mode mode, Integer targetId, String targetType) {
        this.mode = mode;
        this.targetId = targetId;
        this.targetType = targetType;
    }

    @Override
    public String kind() {
        return "combat";
    }

    @Override
    public String failure() {
        return failure;
    }

    @Override
    public void attach(BotState s) {
        s.combat.beginRun(
                "combat " + mode.name().toLowerCase(java.util.Locale.ROOT)
                        + (targetId != null ? " id=" + targetId : "")
                        + (targetType != null ? " type=" + targetType : ""),
                null);
    }

    @Override
    public boolean tick(Body a, WorldView w, BotState st) {
        threats = st.threats;
        if (a.entity() == null || a.entity().level() == null) {
            failure = "player vanished";
            cleanup(a);
            return true;
        }
        // The swing and its cooldown are a player's: a controlled entity that is not a player has
        // no hands for them.
        Player p = a.asPlayer();
        equipment = a.equipment().orElse(null);
        attack = a.attack().orElse(null);
        if (equipment == null || attack == null || p == null) {
            failure = st.combat.lastError = Readiness.Reason.NO_HANDS;
            cleanup(a);
            return true;
        }
        ticks++;
        noteHurt(p, st);
        noteContact(p);
        noteSlow(p);

        Entity target = acquireTarget(p, st);
        if (target == null) {
            shield(false);
            if (!tickBook.isEmpty()) {
                LOG.info("[combat] fight over after {} ticks: {}", ticks, tickBook);
                tickBook.clear();
            }
            // KILL: target dead/gone → mission complete. ENGAGE/DEFEND: area clear.
            // Before terminating, sweep the drops: melee kites away from the kill
            // spot, so the loot (meat, rotten flesh) lands blocks behind us and a
            // "successful" hunt puts nothing in the inventory (live 01:32: six
            // kills, one porkchop banked). KILL/ENGAGE only end with the area
            // clear, so the walk-back is safe; DEFEND stays snappy — it exists to
            // get back to the suspended task, not to loot.
            if (mode != Mode.DEFEND
                    && lastTargetPos != null
                    && BehaviorConfig.combatCollectDrops
                    && !collectSweep(a, p, w, st)) {
                return false;
            }
            cleanup(a);
            return true;
        }
        lastTargetPos = target.blockPosition();

        double dist = Math.sqrt(p.distanceToSqr(target));
        if (isRanged(p)) {
            shield(false);
            rangedTick(a, p, target, dist, st);
        } else if (!(BehaviorConfig.combatCreeperEscape && creeperEscape(a, p, w, target, dist))) {
            meleeTick(a, p, w, target, dist, st);
        }
        book(phase);
        return false;
    }

    /**
     * Counts a blocked hit from the shield's wear and logs every hit that got through with what the fight was doing
     * when it landed: which way the bot faced, whether the shield was up and blocking, and how many were close. A tally
     * of hits says how many; this line says which of the fight's moves let each one in.
     */
    private void noteHurt(Player p, BotState st) {
        ItemStack off = p.getOffhandItem();
        int wear = off.is(Items.SHIELD) ? off.getDamageValue() : -1;
        if (lastShieldWear >= 0 && wear > lastShieldWear) {
            st.combatBlocked++;
            LOG.info("[combat] blocked a hit; phase={} close={}", phase, closeHostiles(p));
        }
        lastShieldWear = wear;
        noteDraws(p);
        float hp = p.getHealth();
        if (lastHealth > 0f && hp < lastHealth - 0.01f) {
            DamageSource src = p.getLastDamageSource();
            Entity by = src == null ? null : src.getEntity();
            Vec3 from = src == null ? null : src.getSourcePosition();
            Long bearing = null;
            if (from != null) {
                Vec3 look = p.getViewVector(1f);
                Vec3 to = from.subtract(p.position());
                double ang = Math.toDegrees(Math.atan2(look.x * to.z - look.z * to.x, look.x * to.x + look.z * to.z));
                bearing = Math.round(ang);
            }
            Entity direct = src == null ? null : src.getDirectEntity();
            LOG.info(
                    "[combat] hurt {} by {} via {} at {} blocks, {} deg off facing; phase={} shieldUp={} blocking={}"
                            + " close={} cooldown={} raisedFor={} using={} usingTicks={} drawPeak={} drawSeenAgo={}"
                            + " drawInSight={}",
                    String.format(java.util.Locale.ROOT, "%.1f", lastHealth - hp),
                    by == null ? null : typeId(by),
                    direct == null ? null : typeId(direct),
                    by == null ? null : String.format(java.util.Locale.ROOT, "%.1f", p.distanceTo(by)),
                    bearing,
                    phase,
                    shieldUp,
                    p.isBlocking(),
                    closeHostiles(p),
                    String.format(java.util.Locale.ROOT, "%.2f", p.getAttackStrengthScale(0f)),
                    shieldUp ? ticks - shieldRaisedAt : -1,
                    p.isUsingItem() ? p.getUseItem().getItem() : null,
                    p.getTicksUsingItem(),
                    drawPeak,
                    ticks - drawPeakAt,
                    drawPeakInSight);
        }
        lastHealth = hp;
    }

    /**
     * Logs the start of each run of ticks the body is against something sideways, with what the fight was doing on the
     * tick before: the benchmark counts such a run as a bump unless it was a short brush of a step the body went on to
     * climb, and a tally says how many, not which of the fight's moves made them.
     */
    private void noteContact(Player p) {
        boolean collided = p.horizontalCollision;
        if (collided && !lastCollided) {
            Vec3 v = p.getDeltaMovement();
            LOG.info(
                    "[combat] contact phase={} chase={} ground={} speed={} up={} close={} pos={}",
                    phase,
                    lastChase,
                    p.onGround(),
                    String.format(java.util.Locale.ROOT, "%.2f", Math.hypot(v.x, v.z)),
                    String.format(java.util.Locale.ROOT, "%.2f", v.y),
                    closeHostiles(p),
                    String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", p.getX(), p.getY(), p.getZ()));
        }
        lastCollided = collided;
    }

    /**
     * Logs a spell of ten ticks under a block a second with no hostile within four blocks, what the benchmark counts as
     * a stall, with what the fight was doing as it began.
     */
    private void noteSlow(Player p) {
        Vec3 v = p.getDeltaMovement();
        double nearest = Double.MAX_VALUE;
        for (ThreatScanner.Threat t : scanFor(p).threats()) {
            nearest = Math.min(nearest, t.distance());
        }
        if (Math.hypot(v.x, v.z) >= 0.05 || nearest <= 4.0) {
            slowTicks = 0;
            return;
        }
        if (slowTicks++ == 0) {
            slowPhase = phase + (shieldUp ? "+shield" : "") + (approaching ? "+route" : "");
        }
        if (slowTicks == 10) {
            LOG.info(
                    "[combat] slow phase={} now={} shield={} nearest={} ground={}",
                    slowPhase,
                    phase,
                    shieldUp,
                    Math.round(nearest * 10.0) / 10.0,
                    p.onGround());
        }
    }

    /**
     * Keeps the furthest bow draw seen among the scanned skeletons over the last second, for the hurt log, and notes a
     * skeleton in sight whose draw just ended past {@link #DRAW_RAISE_TICKS}: it has loosed, and its arrow is on the
     * way for {@link #shotFrom} to keep blocking.
     */
    private void noteDraws(Player p) {
        if (ticks - drawPeakAt > 20) {
            drawPeak = 0;
        }
        for (ThreatScanner.Threat t : scanFor(p).threats()) {
            if (!(t.entity() instanceof RangedAttackMob) || !(t.entity() instanceof LivingEntity le)) {
                continue;
            }
            int draw = le.isUsingItem() ? le.getTicksUsingItem() : 0;
            Integer before = lastDraw.put(le.getId(), draw);
            if (draw == 0 && before != null && before >= DRAW_RAISE_TICKS && t.canSeeMe()) {
                loosedFrom = le.getEyePosition();
                loosedUntil = ticks + (int) Math.ceil(le.distanceTo(p) / ARROW_SPEED) + ARROW_SEEN_LAG;
            }
            if (draw > 0 && draw >= drawPeak) {
                drawPeak = draw;
                drawPeakAt = ticks;
                drawPeakInSight = t.canSeeMe();
            }
        }
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
                // A lock held on a mob across the room while another walks up to the bot turns its back on the
                // one about to hit it: the bot ran past the first zombie to reach a skeleton, took the zombie's
                // hit on the way, and fought the whole pack in the skeleton's corner. The margin keeps two mobs at
                // about the same distance from trading the lock every tick.
                Entity nearer = nearestFirst() ? nearestThreat(scanFor(p)) : null;
                if (nearer != null && nearer != locked && p.distanceTo(nearer) + NEARER_BY < p.distanceTo(locked)) {
                    lockedId = nearer.getId();
                    swingsOnTarget = 0;
                    return nearer;
                }
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
            if (died && lockWasAlive) {
                st.combatKills++;
                LOG.info(
                        "[combat] killed {} with {} swings at tick {}; ticks so far {}",
                        typeId(locked),
                        swingsOnTarget,
                        ticks,
                        tickBook);
            }
            swingsOnTarget = 0;
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
                return e != null && e.isAlive() && e != p ? e : null;
            }
            return nearestOfType(p);
        }
        Entity next = pickFrom(scanFor(p));
        if (next == null && mode == Mode.ENGAGE && BehaviorConfig.combatEngageFullRange && p.level().isClientSide) {
            // On a client the shared scan is the threat watch's, which reaches only as far as the reflexes
            // look, while a server engage scans out to SCAN_RADIUS: a client engage called the area clear with
            // a creeper or spider 25 to 32 blocks off, and the mob was still there when the order ended. Look
            // as far as a server does before giving up.
            next = pickFrom(ThreatScanner.compute(p.level(), p, (int) SCAN_RADIUS));
            if (next != null) {
                LOG.info(
                        "[combat] past the watch: {} at {} blocks",
                        typeId(next),
                        String.format("%.1f", p.distanceTo(next)));
            }
        }
        return next;
    }

    private Entity pickFrom(ThreatScanner.Scan scan) {
        if (nearestFirst()) {
            return nearestThreat(scan);
        }
        // ENGAGE / DEFEND read the threat scan (highest score first).
        for (ThreatScanner.Threat t : scan.threats()) {
            Entity e = t.entity();
            // The first live threat in score order that DEFEND finds pressing is the pick.
            if (e != null
                    && e.isAlive()
                    && (mode != Mode.DEFEND || (t.facingMe() && t.canSeeMe() && t.distance() <= DEFEND_RANGE))) {
                return e;
            }
        }
        return null;
    }

    private boolean nearestFirst() {
        return mode == Mode.ENGAGE && BehaviorConfig.combatNearestFirst;
    }

    /**
     * The nearest live threat in the scan, or null when there is none. With {@link BehaviorConfig#combatCreeperLast} a
     * creeper is taken only when nothing else is left: it comes to the bot anyway, and left alone it fuses beside the
     * mobs the bot is fighting, so its blast does the killing once the bot has stepped out — three of the human's six
     * kills came that way. Hitting it first only knocks it back and spends three swings on what the fuse does free.
     */
    private Entity nearestThreat(ThreatScanner.Scan scan) {
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        Entity creeper = null;
        double creeperD = Double.MAX_VALUE;
        for (ThreatScanner.Threat t : scan.threats()) {
            Entity e = t.entity();
            if (e == null || !e.isAlive()) {
                continue;
            }
            if (BehaviorConfig.combatCreeperLast && e instanceof Creeper) {
                if (t.distance() < creeperD) {
                    creeper = e;
                    creeperD = t.distance();
                }
            } else if (t.distance() < bestD) {
                best = e;
                bestD = t.distance();
            }
        }
        return best != null ? best : creeper;
    }

    private Entity nearestOfType(Player p) {
        Entity best = null;
        double bestD = SCAN_RADIUS * SCAN_RADIUS;
        AABB box = p.getBoundingBox().inflate(SCAN_RADIUS);
        for (Entity e : p.level().getEntities(p, box, x -> true)) {
            if (e == p || !e.isAlive()) {
                continue;
            }
            if (!matchesType(e)) {
                continue;
            }
            double d = e.distanceToSqr(p);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
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
            if (t.entity() == e) {
                return t.facingMe() && t.canSeeMe() && t.distance() <= DEFEND_RANGE;
            }
        }
        return false;
    }

    /**
     * The threat picture: the bot's own scan (refreshed each tick by the bot host) on the client; a fresh
     * {@link ThreatScanner#compute} on the server, which has no refresh loop yet. {@code Level.isClientSide} is set on
     * both ClientLevel and ServerLevel.
     */
    private ThreatScanner.Scan scanFor(Player p) {
        return p.level().isClientSide ? threats.current() : ThreatScanner.compute(p.level(), p, (int) SCAN_RADIUS);
    }

    // === melee ===============================================================

    private void meleeTick(Body a, Player p, WorldView w, Entity target, double dist, BotState st) {
        float scale = p.getAttackStrengthScale(0.5f);
        double ticksToReady = (1.0 - scale) * p.getCurrentItemAttackStrengthDelay();
        double lead = BehaviorConfig.combatSwingRoom ? Math.max(0.0, ticksToReady) + VIEW_LAG_TICKS : 0.0;
        // Where the target will be at the swing, not where it is: a zombie stepped back from is still walking in, and
        // walked after it went straight back into its reach.
        double atSwing = dist - closing(target) * lead;
        if (atSwing > BehaviorConfig.combatReach + 0.4) {
            walkIn(a, p, w, target);
            return;
        }
        phase = "melee";
        holdWeapon(p);
        // In range: stop pathing, face the target, strafe a swarm, swing on cooldown.
        if (approaching) {
            a.releaseInputs();
            approaching = false;
            lastGoalBlock = null;
        }
        p.setSprinting(false);

        ThreatScanner.Scan scan = scanFor(p);
        guard(p, target, scan, ticksToReady);
        float[] move = BehaviorConfig.combatKeepSpacing ? spacing(p, w, scan, lead) : null;
        if (move == null) {
            // A swing knocks its target back a block or two, past reach, and the bot then stood at the edge of reach
            // until the approach took over. Walking in while the cooldown fills puts it back in reach by the time
            // the sword is ready; the spacing step above still wins when anything is closer than that. A mob that
            // walks in by itself is not walked in on: met halfway, it was inside its own reach at the swing.
            boolean pressIn = BehaviorConfig.combatPressIn && atSwing > PRESS_IN_FROM;
            float side = strafeImpulse(p, w);
            if (BehaviorConfig.combatTakeCover) {
                side = coverSide(p, w, scan, side);
            }
            move = new float[] {side, pressIn ? 1f : 0f};
        }
        boolean critOnFall = BehaviorConfig.combatCrit && BehaviorConfig.combatCritOnTheFall;
        boolean canJump = p.onGround() && !p.isInWater() && !p.onClimbable();
        boolean riseAhead = false;
        if (BehaviorConfig.combatFootingAware) {
            // The crit's jump carries the body as far as it is moving when it leaves the ground, so the footing looks
            // that far for the last ticks before it.
            // Not on a ready sword: that swing is made now, from the ground, and probing a jump's drift then stood the
            // body off from walls it would never reach.
            boolean jumpSoon = critOnFall
                    && canJump
                    && ticksToReady > 0.0
                    && ticksToReady <= CRIT_JUMP_LEAD + CRIT_JUMP_LOOK_AHEAD;
            float[] footing = MeleeFooting.step(p, w, move[0], move[1], false, jumpSoon);
            move = footing;
            riseAhead = footing[2] > 0f;
        }
        a.commandMove(move[0], move[1]);

        if (critOnFall) {
            // Vanilla's crit wants the swing made while falling (fallDistance > 0, airborne, not sprinting) on a full
            // cooldown. A jump rises for about six ticks, so the old jump two ticks before a full cooldown put every
            // swing on the way up. Jumping seven ticks out puts the top of the jump just before the cooldown fills.
            boolean critTime = ticksToReady > 0.0 && ticksToReady <= CRIT_JUMP_LEAD;
            boolean critJump = critTime && (!BehaviorConfig.combatSwingRoom || critRoom(p, target, scan, ticksToReady));
            if (critTime && !critJump && canJump) {
                book("crit-no-room");
            }
            a.commandJump(canJump && (riseAhead || critJump));
        } else {
            // Pre-jump so we're descending when the cooldown completes (vanilla crit rule).
            a.commandJump(canJump && riseAhead
                    || BehaviorConfig.combatCrit && p.onGround() && scale >= 0.85f && scale < 1.0f);
        }
        swing(p, target, scale, critOnFall, st);
    }

    /**
     * Swings at {@code target} on a full cooldown, unless the swing waits for the crit's fall or the lowered shield.
     */
    private void swing(Player p, Entity target, float scale, boolean critOnFall, BotState st) {
        boolean falling = !p.onGround() && p.fallDistance > 0f && p.getDeltaMovement().y < 0.0;
        // A ready swing waits for the fall only while the body is rising off a jump: a swing on the ground, in
        // water or on a ladder can never crit, so it is not held.
        boolean rising = !p.onGround() && !falling && !p.isInWater() && !p.onClimbable();
        boolean holdForFall = critOnFall && rising;
        // The lowered shield lands a tick after the intent drops, so the swing waits for the body to agree. Only for
        // a shield this fight lowered: another holder of the use intent (the ambient autoShield) is not waited on.
        boolean shieldDown = !shieldUp && (!p.isUsingItem() || ticks - shieldLoweredAt > SHIELD_LOWER_LEAD);
        if (scale >= 1.0f && !holdForFall && shieldDown) {
            attack.attackEntity(target);
            if (attack.lastAttackRefusal() != null) {
                book("swing-refused");
            }
            p.swing(InteractionHand.MAIN_HAND);
            st.combatSwings++;
            st.combatWellTimed++;
            swingsOnTarget++;
            if (falling) {
                st.combatCrits++;
            }
        } else if (scale >= 1.0f) {
            book(holdForFall ? "ready-held-for-fall" : "ready-held-for-shield");
        }
    }

    /**
     * Takes the weapon back into the hand when something else in the fight put it down ({@code combatReholdWeapon}).
     * The routed walk in may build: after a creeper's blast the bot had picked up the blocks it threw, the route
     * pillared with them, and the hand was left on the block slot, then empty, then on a potato a zombie dropped. The
     * fight went on with fists, a spider took nineteen swings, and every raise of the shield ate the potato instead.
     * The slot the weapon was in when the fight first reached melee is the one taken back.
     */
    private void holdWeapon(Player p) {
        if (!BehaviorConfig.combatReholdWeapon) {
            return;
        }
        Inventory inv = p.getInventory();
        if (weaponSlot < 0) {
            weaponSlot = isWeapon(p.getMainHandItem()) ? inv.selected : -1;
            return;
        }
        if (inv.selected != weaponSlot && !isWeapon(p.getMainHandItem()) && isWeapon(inv.getItem(weaponSlot))) {
            equipment.setSelectedSlot(weaponSlot);
            book("weapon-reheld");
        }
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem;
    }

    /** Counts one tick under {@code what} in the fight's tick book, logged when the fight ends. */
    private void book(String what) {
        tickBook.merge(what, 1, Integer::sum);
    }

    /** Raises or lowers the melee shield for this tick, and turns the bot to what it should face. */
    private void guard(Player p, Entity target, ThreatScanner.Scan scan, double ticksToReady) {
        ThreatScanner.Incoming arrow = BehaviorConfig.combatShield ? incomingHit(scan) : null;
        Vec3 shot = arrow != null ? arrow.pos() : null;
        if (shot == null && BehaviorConfig.combatShield && BehaviorConfig.combatShieldAgainstDraw) {
            shot = shotFrom(scan);
        }
        // An arrow already loosed keeps a raised shield up, and the swing waiting, until it has landed: lowered for a
        // ready sword, the shield could not come back within the arrow's few ticks of flight.
        boolean inFlight = BehaviorConfig.combatShieldAgainstDraw && shieldUp && shot != null && ticks <= loosedUntil;
        // A swing is planned against the draw. Lowering for it, swinging, and raising again takes the lower lead, the
        // swing tick, a tick of input lag and the shield's six ticks to the server, so a bow drawn past
        // SAFE_LOWER_DRAW looses before the shield is back: the shield stays up and the sword waits for the shot to
        // be spent instead. Below it the swing goes ahead, since the skeleton's forty idle ticks after each shot are
        // where the sword does its work; holding the swing from the eighth tick of every draw was slower.
        // Only the skeleton being fought, or one within SWING_HOLD_RANGE, holds the swing: held for every bow in
        // sight, a second skeleton across the arena kept a ready sword behind the shield for up to eighty ticks a run.
        // Held for the target alone, more runs went over three hits (three of five passed, against seven of ten).
        boolean shotDue = BehaviorConfig.combatSwingBetweenShots
                && shot != null
                && (closeDraw(p, target, scan) > SAFE_LOWER_DRAW || ticks <= loosedUntil);
        boolean raise = BehaviorConfig.combatShield
                && hasShield(p)
                && (inFlight
                        || shotDue
                        || ticksToReady > SHIELD_LOWER_LEAD
                                && (shieldUp || ticksToReady >= SHIELD_LOWER_LEAD + SHIELD_RAISE_TICKS)
                                && (shot != null || meleeHostileWithin(scan, SHIELD_MELEE_RANGE)));
        // The shield goes up as the sword comes down and down again as the sword is ready: a shield blocks only
        // after five ticks raised and a player cannot swing from behind it, so the window is the cooldown's middle,
        // and one not long enough to reach those five ticks is not opened at all. While it is up the bot faces the
        // shot it is blocking, else the middle of the mobs close enough to strike, since nothing is swung then; the
        // swing tick turns back to the target.
        shield(raise);
        Vec3 pack = raise && shot == null && BehaviorConfig.combatKeepPackInFront ? packCentre(scan) : null;
        if (raise && shot != null) {
            faceTowards(p, shot);
        } else if (pack != null) {
            faceTowards(p, pack);
        } else {
            aimAt(p, target, 0.0);
        }
    }

    /**
     * The {left, forward} impulse of a step back along a safe heading from the nearest hostile that will be inside
     * {@link #SPACING} within {@code lead} ticks, walking in at its {@link #closing} speed, or null when none will be
     * or no step is safe. Called after the bot has turned for the tick, since the step is resolved against its yaw.
     *
     * <p>{@code lead} is the time to the next swing ({@code combatSwingRoom}); with 0 the spacing is the mob's distance
     * now. Kept at the distance now, the bot was 2.2 blocks off a zombie when it jumped for the crit, could not step
     * back in the air, and the zombie walked the 0.8 blocks of the jump into its own reach: two hits in three that got
     * through came within a few ticks of the bot's own swing.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // an impulse is a fixed pair; null is "no step"
    private static float[] spacing(Player p, WorldView w, ThreatScanner.Scan scan, double lead) {
        Entity close = null;
        double closeD = Double.MAX_VALUE;
        for (ThreatScanner.Threat t : scan.threats()) {
            Entity e = t.entity();
            if (e != null && t.distance() < closeD && t.distance() < SPACING + closing(e) * lead && !baitIgnores(e)) {
                close = e;
                closeD = t.distance();
            }
        }
        if (close == null) {
            return null;
        }
        // Backing away from the nearest one alone can turn the bot's side to the next; backing away from the middle
        // of everything close keeps the pack ahead, where the shield and the sweep reach, and draws it into a line.
        Vec3 from = BehaviorConfig.combatKeepPackInFront ? packCentre(scan) : null;
        // Up to sideways, never toward it: in a corner a strafe along the wall is the step that is left.
        double[] h = StepAway.heading(w, p, from != null ? from : close.position(), 1, 90.0);
        return h == null ? null : bodyFrame(p, h);
    }

    /**
     * Whether a crit jump now lands its swing with room ({@code combatSwingRoom}): in the air the body cannot step, so
     * the jump commits it to where it drifts, at 0.91 of its take-off speed a tick, while the mobs walk in. Off this
     * jump, the target must end within sword reach and no mob that walks in inside {@link #SPACING}; otherwise the bot
     * stays on the ground, keeps its spacing, and swings without the crit. Taken off a step back, the jump carried the
     * bot a block further and the swing met nothing; taken toward a walking mob, it met the mob inside its reach.
     */
    private static boolean critRoom(Player p, Entity target, ThreatScanner.Scan scan, double ticksToReady) {
        double air = (1.0 - Math.pow(AIR_KEEP, ticksToReady)) / (1.0 - AIR_KEEP);
        Vec3 v = p.getDeltaMovement();
        for (ThreatScanner.Threat t : scan.threats()) {
            Entity e = t.entity();
            boolean isTarget = e == target;
            if (e == null || !isTarget && closing(e) <= 0.0) {
                continue;
            }
            double dx = p.getX() - e.getX();
            double dz = p.getZ() - e.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            double away = d < 1.0e-3 ? 0.0 : (v.x * dx + v.z * dz) / d;
            double atSwing = d + away * air - closing(e) * (ticksToReady + VIEW_LAG_TICKS);
            if ((atSwing < SPACING && !baitIgnores(e)) || (isTarget && atSwing > BehaviorConfig.combatReach)) {
                return false;
            }
        }
        return true;
    }

    /**
     * How fast {@code e} walks in on the bot, in blocks a tick: a hostile that fights hand to hand, at its walking
     * speed; 0 for one that shoots or explodes, which does not come to strike. A mob's walk input is its own speed
     * attribute, so it covers about {@link #MOB_WALK_PER_SPEED_SQUARED} times its speed squared a tick: a zombie 0.11,
     * a spider 0.19, a baby zombie 0.26.
     */
    private static double closing(Entity e) {
        if (!(e instanceof Enemy)
                || !(e instanceof LivingEntity le)
                || e instanceof RangedAttackMob
                || e instanceof Creeper
                || !le.getAttributes().hasAttribute(Attributes.MOVEMENT_SPEED)) {
            return 0.0;
        }
        double speed = le.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return Math.min(MOB_WALK_PER_SPEED_SQUARED * speed * speed, MAX_CLOSING);
    }

    /**
     * Whether the melee footwork lets {@code e} come: a creeper that has not started to swell, under
     * {@link BehaviorConfig#combatCreeperBait}. Backing off from it as from a zombie kept it from ever getting close
     * enough to fuse, so it went off alone at the end of the fight. Let in, it fuses in the middle of the pack, and the
     * creeper escape takes the bot out and leaves the pack in the blast.
     */
    private static boolean baitIgnores(Entity e) {
        return BehaviorConfig.combatCreeperBait && e instanceof Creeper c && c.getSwelling(1f) <= 0f;
    }

    /**
     * The strafe side, of left, right and none, from which the most skeletons have a mob in their line of fire;
     * {@code preferred} wins a tie. Most arrows that landed in melee came from a skeleton across the arena, many from
     * behind, where no shield faces. An arrow stops at the first mob in its way, and a skeleton that hits a zombie
     * turns on it, so a person keeps a zombie or the creeper between them and each bow, and the bot sidesteps for the
     * same.
     */
    private static float coverSide(Player p, WorldView w, ThreatScanner.Scan scan, float preferred) {
        boolean anyBow = false;
        for (ThreatScanner.Threat t : scan.threats()) {
            anyBow |= t.entity() instanceof RangedAttackMob;
        }
        if (!anyBow) {
            return preferred;
        }
        double yaw = Math.toRadians(p.getYRot());
        float best = preferred;
        int bestCover = -1;
        for (float side : new float[] {preferred, -preferred, 0f, 1f, -1f}) {
            if (side != 0f && !sideClear(p, w, side)) {
                continue;
            }
            Vec3 eye =
                    p.getEyePosition().add(Math.cos(yaw) * side * COVER_STEP, 0.0, Math.sin(yaw) * side * COVER_STEP);
            int cover = covered(eye, scan);
            if (cover > bestCover) {
                best = side;
                bestCover = cover;
            }
        }
        return best;
    }

    /** How many ranged mobs in the scan have another hostile in their line of fire to {@code eye}. */
    private static int covered(Vec3 eye, ThreatScanner.Scan scan) {
        int n = 0;
        for (ThreatScanner.Threat s : scan.threats()) {
            if (!(s.entity() instanceof RangedAttackMob)) {
                continue;
            }
            Vec3 bow = s.entity().getEyePosition();
            for (ThreatScanner.Threat b : scan.threats()) {
                Entity e = b.entity();
                if (e != s.entity()
                        && !(e instanceof RangedAttackMob)
                        && e.getBoundingBox().clip(eye, bow).isPresent()) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** The mean position of the hostiles within {@link #PACK_RANGE}, or null when none is. */
    private static Vec3 packCentre(ThreatScanner.Scan scan) {
        double x = 0;
        double y = 0;
        double z = 0;
        int n = 0;
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.entity() != null && t.distance() <= PACK_RANGE && !baitIgnores(t.entity())) {
                Vec3 e = t.entity().position();
                x += e.x;
                y += e.y + t.entity().getBbHeight() * 0.5;
                z += e.z;
                n++;
            }
        }
        return n == 0 ? null : new Vec3(x / n, y / n, z / n);
    }

    /**
     * Where a shot will come from: a projectile predicted to hit, else the eyes of a ranged mob in sight that has drawn
     * its bow long enough to loose within the shield's raise time. A skeleton looses at twenty ticks of draw; a shield
     * raised only when the arrow is seen has a few ticks of flight to reach the five it needs. Of two skeletons
     * drawing, the one furthest into its draw looses first, so that is the one faced; taking the first in the scan
     * turned the shield to whichever scored higher while the other's arrow arrived from the side.
     *
     * <p>The draw ends on the tick the bow looses, a tick or two before the client sees the arrow it spawned, and from
     * four blocks that arrow lands two ticks later: following the draw alone, the shield came down on the very tick it
     * was there for, after five ticks up. So a skeleton seen to stop drawing past {@link #DRAW_RAISE_TICKS} is still
     * the shot until its arrow's flight time, plus that lag, has passed.
     */
    private Vec3 shotFrom(ThreatScanner.Scan scan) {
        ThreatScanner.Incoming arrow = incomingHit(scan);
        if (arrow != null) {
            return arrow.pos();
        }
        Vec3 best = null;
        int most = DRAW_RAISE_TICKS - 1;
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.entity() instanceof RangedAttackMob
                    && t.entity() instanceof LivingEntity le
                    && t.canSeeMe()
                    && le.isUsingItem()
                    && le.getTicksUsingItem() > most) {
                best = le.getEyePosition();
                most = le.getTicksUsingItem();
            }
        }
        return best != null || ticks > loosedUntil ? best : loosedFrom;
    }

    /** The furthest bow draw, in ticks, among the ranged mobs in sight that are drawing; 0 when none is. */
    private static int furthestDraw(ThreatScanner.Scan scan) {
        int most = 0;
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.entity() instanceof RangedAttackMob
                    && t.entity() instanceof LivingEntity le
                    && t.canSeeMe()
                    && le.isUsingItem()) {
                most = Math.max(most, le.getTicksUsingItem());
            }
        }
        return most;
    }

    /**
     * The furthest bow draw, in ticks, of the target if it is drawing, or of a ranged mob in sight within
     * {@link #SWING_HOLD_RANGE}; 0 when none is.
     */
    private static int closeDraw(Player p, Entity target, ThreatScanner.Scan scan) {
        int most = 0;
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.entity() instanceof RangedAttackMob
                    && t.entity() instanceof LivingEntity le
                    && t.canSeeMe()
                    && le.isUsingItem()
                    && (le == target || le.distanceTo(p) <= SWING_HOLD_RANGE)) {
                most = Math.max(most, le.getTicksUsingItem());
            }
        }
        return most;
    }

    /** Whether {@code at} lies within {@code cone} degrees either side of where the body faces. */
    private static boolean ahead(Player p, Vec3 at, double cone) {
        Vec3 look = p.getViewVector(1f);
        double dx = at.x - p.getX();
        double dz = at.z - p.getZ();
        double len = Math.sqrt(dx * dx + dz * dz) * Math.sqrt(look.x * look.x + look.z * look.z);
        return len > 1.0e-6 && (look.x * dx + look.z * dz) / len >= Math.cos(Math.toRadians(cone));
    }

    /** Whether a hostile that is not a creeper is within {@code range}: one that hits with its hands. */
    private static boolean meleeHostileWithin(ThreatScanner.Scan scan, double range) {
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.distance() <= range && !(t.entity() instanceof Creeper)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The soonest projectile the scan predicts will hit, or null. One slower than {@link #SPENT_ARROW_SPEED} is not a
     * shot: an arrow the shield turned bounces off at a tenth of its speed and drops at the bot's feet, inside the body
     * box the scan tests against, so it read as an arrow about to land and held the shield up, at a fifth of the
     * walking speed, for ten to seventeen ticks after every blocked shot.
     */
    private static ThreatScanner.Incoming incomingHit(ThreatScanner.Scan scan) {
        ThreatScanner.Incoming best = null;
        for (ThreatScanner.Incoming in : scan.projectiles()) {
            if (in.willHit()
                    && in.vel().lengthSqr() >= SPENT_ARROW_SPEED * SPENT_ARROW_SPEED
                    && (best == null || in.ticksToImpact() < best.ticksToImpact())) {
                best = in;
            }
        }
        return best;
    }

    private static boolean hasShield(Player p) {
        return p.getOffhandItem().is(Items.SHIELD);
    }

    /**
     * Holds or lowers the off-hand shield through the use intent. Lowering touches the intent only if this process
     * raised it, so a bow draw or another holder is never released by a fight that never shielded.
     */
    private void shield(boolean up) {
        if (equipment == null) {
            return;
        }
        if (up) {
            if (!shieldUp) {
                shieldRaisedAt = ticks;
            }
            equipment.commandUseItem(true);
        } else if (shieldUp) {
            equipment.commandUseItem(false);
            shieldLoweredAt = ticks;
        }
        shieldUp = up;
    }

    // === creeper =============================================================

    /** Logs a creeper once when it starts to fuse, with how many mobs stand within three blocks of it. */
    private void logFusing(Player p, Creeper fusing, double d) {
        if (fusing.getId() == creeperLogged) {
            return;
        }
        creeperLogged = fusing.getId();
        long beside = scanFor(p).threats().stream()
                .filter(t -> t.entity() != fusing && t.entity().distanceTo(fusing) < 3.0)
                .count();
        LOG.info("[combat] creeper fusing at {} blocks with {} mobs within 3 of it", Math.round(d * 10) / 10.0, beside);
    }

    /**
     * Deals with a creeper fusing within {@link #CREEPER_DEFUSE_RANGE}; returns false when there is none, or when the
     * bot is already clear of the blast and its target is in reach, so the ordinary melee tick runs.
     *
     * <p>Once a creeper swells it stands still and keeps swelling until its target is past seven blocks, then explodes
     * thirty ticks in, hurting everything within six. So the bot sprints to past {@link #BLAST_CLEAR} if the fuse
     * leaves time to get there, and stops short of seven so the fuse runs out on whatever followed it — the way a
     * person lets a creeper clear the zombies. When there is no time, or no safe way out, it turns to face the creeper
     * behind the off-hand shield, which blocks an explosion from the front once it has been up five ticks; having
     * started that it stays braced, since a sprint begun late is caught in the open.
     */
    private boolean creeperEscape(Body a, Player p, WorldView w, Entity target, double targetDist) {
        Creeper fusing = null;
        double d = Double.MAX_VALUE;
        for (ThreatScanner.Threat t : scanFor(p).threats()) {
            boolean inFuseRange = t.distance() < Math.min(d, CREEPER_DEFUSE_RANGE);
            if (t.entity() instanceof Creeper c && t.creeperSwell() > 0f && inFuseRange) {
                fusing = c;
                d = t.distance();
            }
        }
        if (fusing == null) {
            bracing = false;
            creeperLogged = -1;
            return false;
        }
        logFusing(p, fusing, d);
        if (approaching) {
            a.releaseInputs();
            approaching = false;
            lastGoalBlock = null;
        }
        if (!bracing && d >= BLAST_CLEAR) {
            if (target != fusing && targetDist <= BehaviorConfig.combatReach + 0.4) {
                return false;
            }
            // Clear of the blast with nothing in reach: stay in the ring between the blast and the defuse distance,
            // facing it, rather than walk back in or far enough away to defuse it. Standing still there for the
            // rest of the fuse was a stall with nothing within four blocks, so the bot circles the creeper instead,
            // strafing; the shield, which would slow that to a fifth, stays down unless a shot is coming.
            phase = "creeper-hold";
            p.setSprinting(false);
            aimAt(p, fusing, 0.0);
            float[] move = BehaviorConfig.combatCreeperCircle ? circle(p, w, fusing, d) : null;
            footedMove(a, p, w, move == null ? 0f : move[0], move == null ? 0f : move[1]);
            boolean shot = BehaviorConfig.combatShieldAgainstDraw && shotFrom(scanFor(p)) != null;
            shield(hasShield(p) && (move == null || shot));
            return true;
        }
        int fuseLeft = (int) Math.ceil((1.0 - fusing.getSwelling(1f)) * (CREEPER_FUSE_TICKS - 2)) + 2;
        double ticksToClear = Math.max(0.0, BLAST_CLEAR - d) / SPRINT_PER_TICK;
        double[] h = bracing ? null : StepAway.heading(w, p, fusing.position(), 2, 135.0);
        boolean outrun = h != null && (fuseLeft - SHIELD_RAISE_TICKS - 1 > ticksToClear || !hasShield(p));
        if (outrun) {
            runFromBlast(a, p, w, h, d);
            return true;
        }
        phase = "creeper-brace";
        bracing = hasShield(p);
        p.setSprinting(false);
        aimAt(p, fusing, 0.0);
        a.commandMove(0f, 0f);
        shield(bracing);
        return true;
    }

    /** Sprints along {@code h} out of a fusing creeper's blast, {@code d} blocks from it now. */
    @SuppressWarnings("PMD.UseVarargs") // h is a fixed {x, z} heading, not an argument list
    private void runFromBlast(Body a, Player p, WorldView w, double[] h, double d) {
        phase = "creeper-run";
        shield(false);
        p.setYRot(StepAway.yawOf(h));
        p.setXRot(0f);
        // A sprint carries on most of a block after the keys are let go; let go at the blast's edge, it carried the bot
        // past seven blocks, the creeper stopped swelling and walked after it, and fused again later away from the
        // pack: those runs took 15 to 22 seconds where a single fuse took 13 to 18. So the sprint ends a slide short
        // of the edge and the bot coasts the rest.
        boolean coast = d >= BLAST_CLEAR - SPRINT_SLIDE;
        // A slide that stops short of the edge left the bot standing a few tenths inside it for the rest of the fuse,
        // neither run nor ring, a stall with nothing within four blocks. Once the slide is spent the bot walks the
        // rest, unsprinted, which stops within a step.
        boolean walkOut = coast
                && BehaviorConfig.combatCreeperWalkOut
                && Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z) < SLIDE_SPENT;
        footedMove(a, p, w, 0f, coast && !walkOut ? 0f : 1f);
        a.commandSprint(!coast);
        if (coast) {
            p.setSprinting(false);
        }
    }

    /**
     * The {left, forward} impulse that walks round {@code creeper} at the distance {@code d} it stands at, turned in by
     * 20 degrees past the middle of the ring and out by 20 inside it; null when neither way round is safe to step.
     * Changes direction at a wall.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // an impulse is a fixed pair; null is "no step"
    private float[] circle(Player p, WorldView w, Entity creeper, double d) {
        double rx = p.getX() - creeper.getX();
        double rz = p.getZ() - creeper.getZ();
        double len = Math.sqrt(rx * rx + rz * rz);
        if (len < 1.0e-3) {
            return null;
        }
        rx /= len;
        rz /= len;
        // The ring sits just outside the blast rather than midway to the defuse distance, and the walk turns in hard
        // as it nears that distance: midway, half a block from it, the strafe drifted past seven and defused the
        // creeper, which then followed the bot and fused again away from the pack.
        double ring = BLAST_CLEAR + 0.1;
        double tilt = Math.toRadians(
                d > CREEPER_DEFUSE_RANGE - 0.3 ? 50.0 : d > ring + 0.15 ? 20.0 : d < ring - 0.15 ? -20.0 : 0.0);
        for (int attempt = 0; attempt < 2; attempt++) {
            // The tangent on this side, tilted toward the creeper (tilt > 0) or away from it.
            double tx = -rz * circleSide;
            double tz = rx * circleSide;
            double hx = tx * Math.cos(tilt) - rx * Math.sin(tilt);
            double hz = tz * Math.cos(tilt) - rz * Math.sin(tilt);
            // StepAway checks a heading away from a point, so it is handed the point just behind the bot on this one.
            Vec3 behind = p.position().subtract(hx, 0.0, hz);
            double[] h = StepAway.heading(w, p, behind, 1, 25.0);
            if (h != null) {
                return bodyFrame(p, h);
            }
            circleSide = -circleSide;
        }
        return null;
    }

    /**
     * Steps by {@code left}, {@code forward} through {@link MeleeFooting} with {@code combatFootingAware}, jumping a
     * rise in the way: the creeper's ring and the run from its blast stepped into the cave's rises and walls as the
     * melee footwork did.
     */
    private static void footedMove(Body a, Player p, WorldView w, float left, float forward) {
        if (!BehaviorConfig.combatFootingAware) {
            a.commandMove(left, forward);
            return;
        }
        float[] footing = MeleeFooting.step(p, w, left, forward);
        a.commandMove(footing[0], footing[1]);
        a.commandJump(footing[2] > 0f && !p.isInWater() && !p.onClimbable());
    }

    /** A world heading {x, z} as the {left, forward} impulse of a body facing its current yaw. */
    @SuppressWarnings("PMD.UseVarargs") // h is a fixed {x, z} heading, not an argument list
    private static float[] bodyFrame(Player p, double[] h) {
        double yaw = Math.toRadians(p.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // The inverse of vanilla's moveRelative: world (x, z) into the body's (left, forward).
        return new float[] {(float) (h[0] * cos + h[1] * sin), (float) (-h[0] * sin + h[1] * cos)};
    }

    /** The walk in on a target out of reach, guarding against the shots met on the way. */
    private void walkIn(Body a, Player p, WorldView w, Entity target) {
        phase = "approach";
        if (BehaviorConfig.combatDirectChase && straightRun(p, w, target)) {
            chaseDirect(a, p, target);
        } else {
            approach(a, w, target);
        }
        // Closing on a skeleton is walking into its shots, and the bot cannot swing yet anyway. A raised shield
        // walks at a fifth of the speed and cannot sprint, so it goes up only near the end of the walk: within a
        // few blocks of the shooter, where its arrow has no flight to dodge, or for an arrow about to land from
        // close by. Raised for every shot from across the room, the walk to a skeleton that kept shooting took ten
        // seconds; stopping behind the shield for every drawn bow was slower still and stalled. Only a shot from
        // ahead: the walk owns the yaw here, and a shield blocks only what it faces.
        approachShield(p);
    }

    /** The shield on the walk in, raised for the shots {@link #walkIn} names. */
    private void approachShield(Player p) {
        ThreatScanner.Scan scan = scanFor(p);
        Vec3 shot = BehaviorConfig.combatShieldAgainstDraw && hasShield(p) ? shotFrom(scan) : null;
        boolean near = shot != null && shot.distanceTo(p.getEyePosition()) <= APPROACH_SHIELD_RANGE;
        ThreatScanner.Incoming arrow = incomingHit(scan);
        Vec3 from = shot != null ? shot : arrow != null ? arrow.pos() : null;
        boolean isAhead = from != null && ahead(p, from, APPROACH_BLOCK_CONE);
        // Once up, the shield stays up through the flight of an arrow just loosed, even when no cue alone would raise
        // it now: lowered between the draw's end and the arrow appearing, vanilla's four-tick right-click delay held
        // the re-raise back past the arrow's landing. Held for every later draw too, the walk crawled.
        near |= shieldUp && shot != null && ticks <= loosedUntil;
        // Raised at four blocks whatever the draw, the shield was often up three or four ticks when the arrow landed
        // from five or six. A skeleton within eight blocks whose bow is past APPROACH_LATE_DRAW looses within the
        // shield's raise and lag, so the shield goes up then, for that shot only, and the walk keeps its speed
        // through the forty ticks the skeleton idles between shots.
        near |= BehaviorConfig.combatShieldLateDraw
                && shot != null
                && shot.distanceTo(p.getEyePosition()) <= APPROACH_LATE_RANGE
                && furthestDraw(scan) >= APPROACH_LATE_DRAW;
        boolean landing = arrow != null
                && arrow.ticksToImpact() <= ARROW_LANDING_TICKS
                && arrow.pos().distanceTo(p.getEyePosition()) <= ARROW_LANDING_RANGE;
        // The due shot is a cue of its own, judged ahead by its own shooter: taking its place, a due shot from behind
        // dropped the shield against another shooter's arrow landing from ahead.
        Vec3 due = BehaviorConfig.combatShieldByLanding && hasShield(p) ? dueShot(p, scan) : null;
        boolean dueAhead = due != null && ahead(p, due, APPROACH_BLOCK_CONE);
        shield((near || landing) && isAhead || dueAhead);
        if (shieldUp) {
            book(dueAhead ? "approach-shield-due" : "approach-shield");
        }
    }

    /**
     * The shooter, or the arrow, of the soonest shot that lands within the time a shield raised now takes to block on
     * the server, plus a tick; null when none does ({@code combatShieldByLanding}). A shot lands when the bow has drawn
     * its {@link #LOOSE_DRAW} ticks and the arrow has flown the distance; a loosed one when {@link #noteDraws} said it
     * would; a seen arrow when the scan says. Raised by a fixed draw within a fixed range, the shield on the walk in
     * was up for one to four ticks when the arrows landed: from nine to twelve blocks the old cue never fired and the
     * arrow's own cue came too late, and the shield blocks only once the server has seen it up five ticks.
     */
    private Vec3 dueShot(Player p, ThreatScanner.Scan scan) {
        double soonest = SHIELD_RAISE_TICKS + SHIELD_SERVER_LAG + 1;
        Vec3 best = null;
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.entity() instanceof RangedAttackMob
                    && t.entity() instanceof LivingEntity le
                    && t.canSeeMe()
                    && le.isUsingItem()
                    && t.distance() <= LANDING_RANGE) {
                double lands =
                        Math.max(0.0, LOOSE_DRAW - DRAW_SEEN_LAG - le.getTicksUsingItem()) + t.distance() / ARROW_SPEED;
                if (lands <= soonest) {
                    soonest = lands;
                    best = le.getEyePosition();
                }
            }
        }
        if (loosedFrom != null
                && ticks <= loosedUntil
                && loosedUntil - ticks <= soonest
                && loosedFrom.distanceTo(p.getEyePosition()) <= LANDING_RANGE) {
            soonest = loosedUntil - ticks;
            best = loosedFrom;
        }
        ThreatScanner.Incoming arrow = incomingHit(scan);
        if (arrow != null && arrow.ticksToImpact() <= soonest) {
            best = arrow.pos();
        }
        return best;
    }

    /**
     * Whether the target can be run at in a straight line: in sight, on the bot's own floor, within
     * {@link #DIRECT_CHASE_RANGE}, and every cell on the line between them standable at that floor. Books each tick of
     * the walk in as a straight chase or a routed one.
     */
    private boolean straightRun(Player p, WorldView w, Entity target) {
        boolean straight = straightLine(p, w, target);
        lastChase = straight ? "chase-direct" : "chase-route";
        book(lastChase);
        return straight;
    }

    /**
     * The block level an entity stands on, or would land on: its feet's block on the ground, else the first cell with a
     * collision shape below its feet, plus one, looking down three blocks.
     */
    private static int floorY(Entity e) {
        BlockPos feet = e.blockPosition();
        if (e.onGround()) {
            return feet.getY();
        }
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos below = feet.below(dy);
            if (!e.level()
                    .getBlockState(below)
                    .getCollisionShape(e.level(), below)
                    .isEmpty()) {
                return below.getY() + 1;
            }
        }
        return feet.getY();
    }

    /** The test behind {@link #straightRun}, without the booking. */
    private static boolean straightLine(Player p, WorldView w, Entity target) {
        // Levels are compared by the floor each one stands over, not by where its feet are this tick: compared by feet,
        // every sprint jump or knockback hop of the bot, and every hop of a skeleton knocked back or strafing into the
        // wall, sent that tick to the navigator, which started a fresh path each time; that was where most of the
        // walk's ticks went, not round a crater, which declined the chase once in three runs.
        int y = floorY(p);
        if (y != floorY(target) || p.distanceTo(target) > DIRECT_CHASE_RANGE || !p.hasLineOfSight(target)) {
            return false;
        }
        double dx = target.getX() - p.getX();
        double dz = target.getZ() - p.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        // The body is as wide as it is, not a point: a line clear down its middle let a sprint-jumping chase brush
        // every corner the line passed close to, a bump each time in the cave courses. Both of the body's sides are
        // run along the line as well.
        double side = BehaviorConfig.combatChaseBodyWidth ? p.getBbWidth() / 2 + CHASE_SIDE_MARGIN : 0.0;
        double sx = -dz / len * side;
        double sz = dx / len * side;
        for (double s = 0.5; s < len; s += 0.5) {
            double cx = p.getX() + dx * s / len;
            double cz = p.getZ() + dz * s / len;
            for (int k = -1; k <= 1; k++) {
                if (k != 0 && side == 0.0) {
                    continue;
                }
                BlockPos cell = BlockPos.containing(cx + k * sx, y, cz + k * sz);
                if (!w.canStandAt(cell) || w.isHazard(cell)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Sprints straight at the target, without the navigator. On open flat floor the navigator re-planned at every block
     * the target moved into and walked the plan's cells; a straight sprint at a target in sight is the path anyway. The
     * navigator is told nothing, so its next goal starts a fresh search.
     */
    private void chaseDirect(Body a, Player p, Entity target) {
        approaching = true;
        lastGoalBlock = null;
        lastQuarryId = -1;
        aimAt(p, target, 0.0);
        p.setXRot(0f);
        a.commandMove(0f, 1f);
        a.commandSprint(true);
        // A sprint jump carries the sprint's push plus a jump boost of 0.2 blocks, so a chase of more than a few
        // blocks covers it faster jumping than running; a skeleton backing off at the same speed as a walk was
        // otherwise caught only at a wall. Not with the shield up, which stops the sprint, nor in the last blocks,
        // where a landing overshoots the swing.
        boolean jump = BehaviorConfig.combatSprintJumpChase
                && !shieldUp
                && p.onGround()
                && p.distanceTo(target) > SPRINT_JUMP_FROM;
        a.commandJump(jump);
    }

    /** Path toward the target's block (re-goaling as it moves), like FollowProcess. */
    private void approach(Body a, WorldView w, Entity target) {
        approaching = true;
        BlockPos tb = target.blockPosition();
        int radius = Math.max(1, (int) Math.floor(BehaviorConfig.combatReach));
        // A DIFFERENT ENTITY IS A DIFFERENT PURSUIT, and this is the only place that knows it —
        // the navigator sees cells, not quarries. Getting this wrong is worse than the bug below it:
        // a futile latch earned chasing an unreachable blaze would ride retarget into the walk
        // toward a zombie the bot could plainly reach, and nothing would ever start a search
        // again (the tick budget can't save it either — every re-goal clears totalTicks, so it
        // never fills). Silent freeze, no terminal, no log.
        boolean newQuarry = lastQuarryId != target.getId();
        if (newQuarry || lastGoalBlock == null || !lastGoalBlock.equals(tb)) {
            // Same quarry, new cell: retarget, NOT start. A flying quarry changes block every
            // couple of ticks, and start clears the futile-search governor each time — so a bot
            // chasing something it can never reach runs one full A* per tick forever (measured:
            // 240 ticks, 240 searches, counter never past 1 against a cap of 5).
            Goal.Near g = new Goal.Near(tb, radius);
            if (newQuarry) {
                navigator.start(g);
            } else {
                navigator.retarget(g);
            }
            lastGoalBlock = tb;
            lastQuarryId = target.getId();
        }
        Locomotor.Status s = navigator.tick(a, w);
        if (s != Locomotor.Status.MOVING && BehaviorConfig.combatWalkInPastArrival) {
            // The goal is a block within the reach's whole blocks of the target's, so the navigator can call it
            // reached with the body still a block out of sword reach, measured between the two feet: a skeleton
            // backed against the wall left the bot standing three to four blocks off it, never re-goaled (the
            // target's block did not change), for six seconds. The last block is open floor; walk it straight.
            aimAt(a.asPlayer(), target, 0.0);
            a.commandMove(0f, 1f);
        }
    }

    /**
     * Orbit a swarm (≥2 close hostiles) to avoid being surrounded; stand still vs a lone target so the hit rhythm isn't
     * interrupted, unless {@link BehaviorConfig#combatOrbitLoneTarget} orbits that too, away from walls. Returns the
     * camera-frame strafe impulse (+1 = left, -1 = right, 0 = hold), the {@code commandMove} equivalent of the old
     * keyLeft/keyRight presses.
     */
    private float strafeImpulse(Player p, WorldView w) {
        boolean orbitAlone = BehaviorConfig.combatOrbitLoneTarget;
        if (!orbitAlone && closeHostiles(p) < 2) {
            return 0f;
        }
        float side = ticks / STRAFE_FLIP % 2 == 0 ? 1f : -1f;
        if (!orbitAlone) {
            return side;
        }
        // Standing still against one mob read as a stall whenever the swing knocked it past four blocks before the
        // bot set off after it, so the bot keeps sidestepping round it, the way a person does. A strafe into a wall
        // is a bump, so the side is checked first and the other side taken when it is blocked.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (sideClear(p, w, side)) {
                return side;
            }
            side = -side;
        }
        return 0f;
    }

    /** Whether the cell a strafe to {@code side} (+1 left, -1 right) steps into is safe to stand on. */
    private static boolean sideClear(Player p, WorldView w, float side) {
        double yaw = Math.toRadians(p.getYRot());
        double hx = Math.cos(yaw) * side;
        double hz = Math.sin(yaw) * side;
        // StepAway checks a heading away from a point, so it is handed the point on the other side of the bot.
        return StepAway.heading(w, p, p.position().subtract(hx, 0.0, hz), 1, 0.0) != null;
    }

    private int closeHostiles(Player p) {
        int n = 0;
        for (ThreatScanner.Threat t : scanFor(p).threats()) {
            if (t.distance() <= BehaviorConfig.combatReach + 2.0) {
                n++;
            }
        }
        return n;
    }

    // === ranged ==============================================================

    private void rangedTick(Body a, Player p, Entity target, double dist, BotState st) {
        if (approaching) {
            approaching = false;
            lastGoalBlock = null;
        }
        aimAt(p, target, dist * 0.12); // lead a little high for arrow drop
        p.setSprinting(false);
        // Keep the kite band: back up if too close, advance if too far, else hold.
        float fwd = 0f;
        if (dist < BehaviorConfig.kiteDistance - 1.0) {
            fwd = -1f; // step back, keep facing the target
        } else if (dist > BehaviorConfig.kiteDistance + 2.0) {
            fwd = 1f;
        }
        a.commandForward(fwd);
        // Draw the bow (hold use); release the moment it's fully charged → fires.
        if (p.isUsingItem() && p.getTicksUsingItem() >= BOW_FULL_DRAW) {
            equipment.commandUseItem(false); // up-edge = release = shoot
            st.combatSwings++;
            st.combatWellTimed++;
        } else {
            equipment.commandUseItem(true);
        }
    }

    private static boolean isRanged(Player p) {
        ItemStack m = p.getMainHandItem();
        return m.getItem() instanceof BowItem || m.getItem() instanceof CrossbowItem;
    }

    // === post-kill drop sweep ===============================================

    /**
     * Walk over the item drops around the last kill position (vanilla pickup is automatic on touch). Returns
     * {@code true} when done: no live drops left in {@link #COLLECT_RADIUS} of the kill spot, or the tick budget ran
     * out (unreachable drop — never wedge the suspended user task).
     */
    private boolean collectSweep(Body a, Player p, WorldView w, BotState st) {
        if (++collectTicks > COLLECT_BUDGET_TICKS) {
            return true;
        }
        AABB box = new AABB(lastTargetPos).inflate(COLLECT_RADIUS);
        ItemEntity nearest = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity ie : p.level().getEntitiesOfClass(ItemEntity.class, box)) {
            // NOTE: items with pickup delay still count — a just-dropped stack's
            // ~10-tick delay expires while we walk to it; skipping delayed items
            // would end the sweep instantly on the very drops we came for.
            if (!ie.isAlive()) {
                continue;
            }
            double d = ie.distanceToSqr(p);
            if (d < bestD) {
                bestD = d;
                nearest = ie;
            }
        }
        if (nearest == null) {
            return true;
        }
        if (collectTicks == 1) {
            st.combat.goal = "combat collect-drops";
        }
        BlockPos ib = nearest.blockPosition();
        boolean regoal = lastGoalBlock == null || !lastGoalBlock.equals(ib);
        if (regoal) {
            navigator.start(new Goal.Near(ib, 1));
            lastQuarryId = -1; // the quarry is dead; the next approach starts a new pursuit
        }
        Locomotor.Status s = navigator.tick(a, w);
        if (s == Locomotor.Status.MOVING) {
            if (regoal) {
                lastGoalBlock = ib;
            }
        } else {
            // The navigator parked in a terminal (ARRIVED path-consumed short of the
            // stack / FAILED) while the drop still exists: without a reset it
            // no-ops every subsequent tick (start is the only thing that clears
            // a terminal — measured live in the t0 diag round: sweepTicks=101,
            // 0.8 blocks moved, lastStep=ARRIVED). Same idiom as FollowProcess:
            // drop the goal so next tick re-goals at the CURRENT nearest drop
            // with a fresh path. Near radius 1 (not 0): pickup touch reaches an
            // adjacent cell, and radius-0 exact-cell goals are the rarely-driven
            // shape goto itself abandoned (near=2 default).
            //
            // ⚠️ "PICKUP TOUCH REACHES AN ADJACENT CELL" IS AN UNMEASURED CLAIM, AND THE
            // OTHER COLLECT LOOP MEASURED IT FALSE. MineProcess's collect goal (search for
            // `currentCollectGoal`) uses Goal.Block — the drop's own cell — after an
            // adjacency goal on the three-ore rig reported ARRIVED standing 1.6 blocks out
            // and then sat there for the whole budget, against a magnet radius of ~1.4.
            // Note what that predicts HERE: a Near(ib,1) satisfied at an orthogonal
            // neighbour, a terminal step, and no pickup — which is the same surface as the
            // `sweepTicks=101, 0.8 blocks moved, lastStep=ARRIVED` reading above, currently
            // attributed entirely to the missing goal reset. The reset is real and stays;
            // what is NOT established is that it was the only cause, because the diag round
            // never recorded the eye-to-item distance that would separate "parked in a
            // terminal" from "arrived and still out of reach". Take that reading before
            // changing the radius in either direction — widening to Block here without it
            // would trade one unmeasured claim for another.
            lastGoalBlock = null;
        }
        return false;
    }

    // === helpers =============================================================

    /**
     * Snap head+body to the target's mid-height (+{@code leadY} blocks up). Snaps rather than smooth-pans because the
     * attack/shot raycast needs the crosshair on target the same tick.
     */
    private static void aimAt(Player p, Entity e, double leadY) {
        Vec3 eye = p.getEyePosition();
        double dx = e.getX() - eye.x;
        double dy = e.getY() + e.getBbHeight() * 0.5 + leadY - eye.y;
        double dz = e.getZ() - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        final float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(pitch);
    }

    /** Snap head+body toward a point, as {@link #aimAt} does toward an entity: the shield blocks where it faces. */
    private static void faceTowards(Player p, Vec3 at) {
        Vec3 eye = p.getEyePosition();
        double dx = at.x - eye.x;
        double dz = at.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        double dy = at.y - eye.y;
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private void cleanup(Body a) {
        a.releaseInputs();
        a.equipment().ifPresent(h -> h.commandUseItem(false));
        Player p = a.asPlayer();
        if (p != null && p.isUsingItem()) {
            p.stopUsingItem();
        }
    }
}

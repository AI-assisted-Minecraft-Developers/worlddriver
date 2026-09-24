package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;

public final class FollowProcess implements BotProcess {
    /** Consecutive Walker.FAILED replans before we give up. Each represents one full pathfinder attempt. */
    private static final int MAX_CONSECUTIVE_FAILS = 5;
    private final String entityType;
    private final String name;
    private final int radius;
    private final int maxIdleTicks;
    /** Ticks without progress before the follow ends as unreachable; 0 = never. */
    public static final int DEFAULT_GIVE_UP_TICKS = 600;
    private final int giveUpTicks;
    private final Chase chase;
    private final Walker walker = new Walker("follow");
    private int idleTicks;
    private int consecutiveFails;
    private BlockPos lastTargetBlock;
    private int lastTargetId = -1;

    public FollowProcess(String entityType, String name, int radius, int maxIdleTicks) {
        this(entityType, name, radius, maxIdleTicks, DEFAULT_GIVE_UP_TICKS, SearchProfile.NONE);
    }

    /** A3a: pass a per-follow {@link SearchProfile} (e.g. a leashed capability
     *  envelope) through to the underlying {@link Walker}. */
    public FollowProcess(String entityType, String name, int radius, int maxIdleTicks, int giveUpTicks,
                         SearchProfile profile) {
        this.entityType = entityType;
        this.name = name;
        this.radius = radius;
        this.maxIdleTicks = maxIdleTicks;
        this.giveUpTicks = giveUpTicks;
        this.chase = new Chase(giveUpTicks);
        walker.setSearchProfile(profile == null ? SearchProfile.NONE : profile);
    }

    public String kind() { return "follow"; }
    public void attach(BotState st) {
        st.follow.active = true;
        st.follow.goal = "follow " + (name != null ? "name=" + name : "type=" + entityType) + " r=" + radius
            + (maxIdleTicks > 0 ? " idle≤" + maxIdleTicks + "t" : "")
            + (giveUpTicks > 0 ? " giveUp≤" + giveUpTicks + "t" : "");
        st.follow.startedAtMs = System.currentTimeMillis();
        st.follow.lastError = null;
    }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        // Stamped for the same reason BackfillProcess/BuildProcess stamp theirs: `ProcessSlot
        // .snapshot()` emits lastError only `if (lastError != null)` and `attach` cleared it, so an
        // unstamped exit is not silence — it is the POSITIVE report "finished, no error". Every
        // other exit in this file already stamps; this one was the hole.
        if (p == null) return giveUp(st, "player vanished");
        Level lvl = p.level();
        Entity target = findTarget(lvl, p);
        if (target == null) {
            // No target visible — clear keys and idle. If maxIdleTicks set and
            // exceeded, finish gracefully so the LLM can poll and react.
            a.commandForward(0f);
            a.commandJump(false);
            p.setSprinting(false);
            if (maxIdleTicks > 0 && ++idleTicks > maxIdleTicks) {
                return giveUp(st, "target not seen for " + maxIdleTicks + " ticks");
            }
            return false;
        }
        idleTicks = 0;
        BlockPos tBlock = new BlockPos(
            (int) Math.floor(target.getX()),
            (int) Math.floor(target.getY()),
            (int) Math.floor(target.getZ()));
        regoal(target.getId(), tBlock);
        st.follow.target = tBlock;
        Walker.Step s = walker.tick(a, w);
        st.follow.pathLen = walker.pathLen();
        st.follow.pathStep = walker.pathStep();
        if (chase.tick(s == Walker.Step.ARRIVED, Math.sqrt(target.distanceToSqr(p)))) {
            return giveUp(st, String.format(Locale.ROOT,
                    "unreachable: no closer than %.1f blocks in %d ticks", chase.closest(), giveUpTicks));
        }
        if (s == Walker.Step.FAILED) {
            consecutiveFails++;
            if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                return giveUp(st, "unreachable (" + consecutiveFails + " failed replans): " + walker.lastError);
            }
            // Force a fresh A* on the next tick rather than re-walking the dead path.
            lastTargetBlock = null;
        } else if (s == Walker.Step.ARRIVED) {
            // Within `radius`: stop walking and watch the target so the
            // camera tracks it (a tracking shot). The Walker owns yaw while
            // moving; here, idle, we point at the entity. smoothAngle pans
            // when smoothLook is on and snaps when off.
            a.commandForward(0f);
            a.commandJump(false);
            p.setSprinting(false);
            aimAtEntity(p, target);
            consecutiveFails = 0;
        } else {
            consecutiveFails = 0;
        }
        return false; // follow runs until cancelled or unreachable
    }

    /** A follow runs until cancelled, so every ending of its own is a give-up. */
    private boolean giveUp(BotState st, String why) {
        failure = why;
        st.follow.lastError = why;
        st.follow.reset();
        return true;
    }

    private String failure;

    @Override public String failure() { return failure; }

    /**
     * Aim the walker at the target's cell. The same entity in a new cell is the same pursuit, so it
     * gets {@link Walker#retargetGoal}: {@code setGoal} zeroes the futile-search counter, and a target
     * that keeps moving would then never let an unreachable follow trip the cap. Only a different
     * entity starts over.
     */
    void regoal(int targetId, BlockPos tBlock) {
        boolean newTarget = lastTargetId != targetId;
        if (!newTarget && tBlock.equals(lastTargetBlock)) return;
        Goal g = new Goal.Near(tBlock, radius);
        if (newTarget) {
            walker.setGoal(g);
            chase.restart();
        } else {
            walker.retargetGoal(g);
        }
        lastTargetBlock = tBlock;
        lastTargetId = targetId;
    }

    Walker walker() { return walker; }

    /**
     * Whether the chase is still getting anywhere. The walker's futile-search guard cannot say: a
     * quarry in a pen the bot cannot enter sends it circling the pen, and a bot that moves is never
     * futile. Progress is reaching the standoff or a block gained on the closest approach so far.
     */
    static final class Chase {
        private final int giveUpTicks;
        private double closest = Double.POSITIVE_INFINITY;
        private int sinceGain;

        Chase(int giveUpTicks) {
            this.giveUpTicks = giveUpTicks;
        }

        /** One tick of the chase; true once {@code giveUpTicks} have passed without progress (0 = never). */
        boolean tick(boolean arrived, double distance) {
            if (arrived || distance < closest - 1.0) {
                closest = distance;
                sinceGain = 0;
                return false;
            }
            return giveUpTicks > 0 && ++sinceGain >= giveUpTicks;
        }

        double closest() { return closest; }

        void restart() {
            closest = Double.POSITIVE_INFINITY;
            sinceGain = 0;
        }
    }

    /** Point head+body yaw and pitch at the entity's mid-height, via
     *  {@link #smoothAngle} so it honors the smoothLook toggle. */
    private static void aimAtEntity(LivingEntity p, Entity e) {
        Vec3 eye = p.getEyePosition();
        double dx = e.getX() - eye.x;
        double dy = (e.getY() + e.getBbHeight() * 0.5) - eye.y;
        double dz = e.getZ() - eye.z;
        float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float ny = smoothAngle(p.getYRot(), ty);
        float np = smoothAngle(p.getXRot(), tp);
        p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny; p.setXRot(np);
    }

    private Entity findTarget(Level lvl, LivingEntity self) {
        double bestDist = Double.POSITIVE_INFINITY;
        Entity best = null;
        // Level.getEntities (EntityGetter) works on BOTH ClientLevel and ServerLevel,
        // unlike the client-only entitiesForRendering() — a generous AABB stands in
        // for "all loaded entities near us".
        AABB box = self.getBoundingBox().inflate(96.0);
        for (Entity e : lvl.getEntities(self, box, x -> true)) {
            if (e == self) continue;
            if (name != null) {
                String n = e.getName().getString();
                if (!name.equals(n)) continue;
            } else {
                String t = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if (!entityType.equals(t)) continue;
            }
            double d = e.distanceToSqr(self);
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }
}

package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import net.minecraft.client.multiplayer.ClientLevel;

public final class FollowProcess implements BotProcess {
    private static final int REPLAN_TICKS = 30;
    /** Consecutive Walker.FAILED replans before we give up. Each represents one full pathfinder attempt. */
    private static final int MAX_CONSECUTIVE_FAILS = 5;
    private final String entityType;
    private final String name;
    private final int radius;
    private final int maxIdleTicks;
    private final Walker walker = new Walker("follow");
    private int ticksSinceReplan;
    private int idleTicks;
    private int consecutiveFails;
    private BlockPos lastTargetBlock;

    public FollowProcess(String entityType, String name, int radius, int maxIdleTicks) {
        this(entityType, name, radius, maxIdleTicks, SearchProfile.NONE);
    }

    /** A3a: pass a per-follow {@link SearchProfile} (e.g. a leashed capability
     *  envelope) through to the underlying {@link Walker}. */
    public FollowProcess(String entityType, String name, int radius, int maxIdleTicks, SearchProfile profile) {
        this.entityType = entityType;
        this.name = name;
        this.radius = radius;
        this.maxIdleTicks = maxIdleTicks;
        walker.setSearchProfile(profile == null ? SearchProfile.NONE : profile);
    }

    public String kind() { return "follow"; }
    public void attach(BotState st) {
        st.follow.active = true;
        st.follow.goal = "follow " + (name != null ? "name=" + name : "type=" + entityType) + " r=" + radius
            + (maxIdleTicks > 0 ? " idle≤" + maxIdleTicks + "t" : "");
        st.follow.startedAtMs = System.currentTimeMillis();
        st.follow.lastError = null;
    }

    @Override public boolean tick(Avatar a, WorldView w, BotState st) {
        Player p = a.player();
        if (p == null) { st.follow.reset(); return true; }
        Level lvl = p.level();
        Entity target = findTarget(lvl, p);
        if (target == null) {
            // No target visible — clear keys and idle. If maxIdleTicks set and
            // exceeded, finish gracefully so the LLM can poll and react.
            a.commandForward(0f);
            a.commandJump(false);
            p.setSprinting(false);
            if (maxIdleTicks > 0 && ++idleTicks > maxIdleTicks) {
                st.follow.lastError = "target not seen for " + maxIdleTicks + " ticks";
                st.follow.reset();
                return true;
            }
            return false;
        }
        idleTicks = 0;
        BlockPos tBlock = new BlockPos(
            (int) Math.floor(target.getX()),
            (int) Math.floor(target.getY()),
            (int) Math.floor(target.getZ()));
        if (lastTargetBlock == null || !lastTargetBlock.equals(tBlock) || ticksSinceReplan > REPLAN_TICKS) {
            walker.setGoal(new Goal.Near(tBlock, radius));
            lastTargetBlock = tBlock;
            ticksSinceReplan = 0;
        }
        ticksSinceReplan++;
        st.follow.target = tBlock;
        Walker.Step s = walker.tick(a, w);
        st.follow.pathLen = walker.pathLen();
        st.follow.pathStep = walker.pathStep();
        if (s == Walker.Step.FAILED) {
            consecutiveFails++;
            if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
                st.follow.lastError = "unreachable (" + consecutiveFails + " failed replans): " + walker.lastError;
                st.follow.reset();
                return true;
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

    /** Point head+body yaw and pitch at the entity's mid-height, via
     *  {@link #smoothAngle} so it honors the smoothLook toggle. */
    private static void aimAtEntity(Player p, Entity e) {
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

    private Entity findTarget(Level lvl, Player self) {
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

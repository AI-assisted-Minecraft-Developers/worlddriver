package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.elytra.ElytraPhysics;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;

public final class ElytraController {
    /** Pitch fan searched each tick, MC sign (+ dives, − climbs). Spans hard
     *  dive to hard climb so a firework tick can pick a steep ascent. */
    private static final float[] PITCHES =
            {-45, -35, -25, -18, -12, -8, -4, 0, 4, 8, 15, 25, 40};
    private static final int HORIZON = 30;          // ticks simulated ahead (~45 blocks at cruise)
    private static final int BOOST_LIFE = 20;       // assumed live-rocket ticks (duration-1 ≈ 20–32)
    private static final int FIRE_COOLDOWN = 12;    // hard floor between rockets (subsumed by the no-overlap gate)
    private static final double MIN_CRUISE = 0.45;  // h-speed (b/t) below which we boost to keep moving
    /** Climb-band hysteresis for fuel economy. A new boost-climb episode only
     *  STARTS once the bot has sagged more than {@link #CLIMB_DEADBAND} below
     *  the target altitude, and ENDS once back within {@link #CLIMB_MARGIN}.
     *  Between, the bot glides (free distance) — this is the glide-and-boost
     *  sawtooth that makes each rocket carry as far as possible instead of
     *  topping up altitude every couple of blocks. */
    private static final double CLIMB_MARGIN = 2.0;     // climb satisfied within this of target ⇒ stop boosting
    private static final double CLIMB_DEADBAND = 12.0;  // sag this far below target before a boost-climb starts
    private static final double STEP = 0.5;         // terrain-raytrace sample spacing (blocks)
    private static final int CLEAR_MARGIN = 2;      // blocks of buffer we prefer to keep off terrain
    private static final double CLEAR_WEIGHT = 1.2; // per-tick penalty for skimming within the margin
    /** Anti-jitter: penalise picking a pitch far from the one we're holding,
     *  so the discrete fan doesn't flip between neighbours every tick (which
     *  reads as the camera bobbing up and down). This is hysteresis, NOT a
     *  rate limit — when terrain ahead or an overhead goal makes another
     *  pitch clearly better the score swing dwarfs this bias and we switch
     *  fully in one tick, so obstacle avoidance is never slowed. */
    private static final double TURN_WEIGHT = 0.18;
    /** Landing-mode bias: among collision-safe trajectories prefer the one
     *  that ends slowest, so the flare bleeds speed — but still terrain-aware
     *  (it never bypasses the crash/clearance check the way a blind fixed
     *  flare pitch did, which is what flew the bot into hillsides on descent). */
    private static final double LAND_BLEED_WEIGHT = 3.0;

    private int boostRemaining;     // ticks of firework boost still assumed live
    private boolean climbing;       // in a boost-climb episode (hysteresis; see CLIMB_DEADBAND)
    private int sinceFire = Integer.MAX_VALUE;
    private float lastPitch;        // pitch held last tick (hysteresis reference)
    private boolean pitchInit;      // seed from the live look on first tick

    record Decision(float pitch, boolean fire) {}

    Decision decide(WorldView w, LocalPlayer p, Vec3 vel, float yaw, Vec3 goal, boolean fireworksAllowed) {
        return decide(w, p, vel, yaw, goal, fireworksAllowed, false);
    }

    /** @param landing flare mode — no boost, and among collision-safe pitches
     *  prefer the slowest-ending one (bleed speed) while STILL avoiding
     *  terrain. The previous flare used a blind fixed pitch with no lookahead,
     *  which crashed into rising ground on the final approach. */
    Decision decide(WorldView w, LocalPlayer p, Vec3 vel, float yaw, Vec3 goal,
                    boolean fireworksAllowed, boolean landing) {
        if (boostRemaining > 0) boostRemaining--;
        if (sinceFire < Integer.MAX_VALUE) sinceFire++;
        if (!pitchInit) { lastPitch = p.getXRot(); pitchInit = true; }

        Vec3 pos = p.position();
        double hSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        // Hysteretic climb band → glide-and-boost sawtooth (fuel economy):
        // only engage a climb once we've sagged past the deadband, ride it
        // until back near the target, then glide the gained altitude away.
        double deficit = goal.y - pos.y;
        if (deficit > CLIMB_DEADBAND) climbing = true;
        else if (deficit <= CLIMB_MARGIN) climbing = false;
        boolean needClimb = climbing;
        // No-overlap: never light a new rocket while the previous boost is
        // still burning — wait until it's fully spent so every rocket's thrust
        // is used in full (the old FIRE_COOLDOWN=12 < BOOST_LIFE=20 let two
        // boosts overlap, wasting the second against the speed cap).
        boolean fire = !landing && fireworksAllowed && boostRemaining <= 0 && sinceFire >= FIRE_COOLDOWN
                && (needClimb || hSpeed < MIN_CRUISE);

        // Boost ticks the lookahead should assume: a rocket already burning,
        // or the one we're about to light this tick.
        int boostTicks = fire ? BOOST_LIFE : boostRemaining;

        float bestPitch = lastPitch;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (float pitch : PITCHES) {
            // Bias toward the pitch we're already holding so near-equal
            // candidates don't flip tick-to-tick (camera bob); a real
            // avoidance need outscores this and switches fully in one tick.
            double score = score(simulate(w, pos, vel, yaw, pitch, boostTicks, goal), landing)
                    - TURN_WEIGHT * Math.abs(pitch - lastPitch);
            if (score > bestScore) { bestScore = score; bestPitch = pitch; }
        }
        lastPitch = bestPitch;
        return new Decision(bestPitch, fire);
    }

    /** Mark that a rocket was actually lit (the actuator confirmed the use). */
    void onFired() { boostRemaining = BOOST_LIFE; sinceFire = 0; }

    // --- forward simulation + scoring ------------------------------------

    private record Sim(boolean crashed, int crashTick, double minDist, int nearTicks, double endHSpeed) {}

    private Sim simulate(WorldView w, Vec3 pos0, Vec3 vel0, float yaw, float pitch,
                         int boostTicks, Vec3 goal) {
        ElytraPhysics.State s = new ElytraPhysics.State(pos0, vel0);
        double minDist = pos0.distanceTo(goal);
        Vec3 prev = pos0;
        int nearTicks = 0;
        for (int t = 0; t < HORIZON; t++) {
            s = ElytraPhysics.stepTick(s, yaw, pitch, t < boostTicks);
            Vec3 cur = s.pos();
            if (segmentHitsTerrain(w, prev, cur)) return new Sim(true, t, minDist, nearTicks, 0);
            // Soft clearance: cheap per-tick probe at the tick's endpoint
            // (margin is a preference, not a safety check — coarse is fine).
            if (marginBreached(w, cur.x, cur.y, cur.z)) nearTicks++;
            double d = cur.distanceTo(goal);
            if (d < minDist) minDist = d;
            prev = cur;
        }
        Vec3 ev = s.vel();
        return new Sim(false, HORIZON, minDist, nearTicks, Math.hypot(ev.x, ev.z));
    }

    /** Higher is better. A crash is rejected hard (later crash slightly less
     *  bad, so when every option crashes the bot still buys the most time to
     *  recover); otherwise closer approach to the goal wins, with a penalty
     *  for skimming terrain inside the clearance margin so the bot keeps a
     *  buffer over ridges instead of scraping them. In landing mode the
     *  slowest-ending safe trajectory is preferred so the flare bleeds speed
     *  without ever giving up terrain avoidance. */
    private static double score(Sim r, boolean landing) {
        if (r.crashed) return -1.0e6 + r.crashTick * 1000.0;
        double s = -r.minDist - CLEAR_WEIGHT * r.nearTicks;
        if (landing) s -= LAND_BLEED_WEIGHT * r.endHSpeed;
        return s;
    }

    /** True if the player's ~2-tall hitbox sweeping from {@code a} to {@code b}
     *  passes through any solid block (sampled along the segment so a fast
     *  tick can't tunnel a thin wall). Unloaded chunks read as non-solid. */
    private static boolean segmentHitsTerrain(WorldView w, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / STEP));
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            double x = a.x + dx * f, y = a.y + dy * f, z = a.z + dz * f;
            if (solidAt(w, x, y, z) || solidAt(w, x, y + 1.0, z)) return true;  // feet + head cell
        }
        return false;
    }

    /** True if any solid sits within the clearance box around the point (but
     *  outside the tight hitbox, which is a crash handled separately). */
    private static boolean marginBreached(WorldView w, double x, double y, double z) {
        for (int ox = -CLEAR_MARGIN; ox <= CLEAR_MARGIN; ox++)
            for (int oy = -CLEAR_MARGIN; oy <= CLEAR_MARGIN + 1; oy++)
                for (int oz = -CLEAR_MARGIN; oz <= CLEAR_MARGIN; oz++)
                    if (solidAt(w, x + ox, y + oy, z + oz)) return true;
        return false;
    }

    private static boolean solidAt(WorldView w, double x, double y, double z) {
        return w.isSolid(new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }
}

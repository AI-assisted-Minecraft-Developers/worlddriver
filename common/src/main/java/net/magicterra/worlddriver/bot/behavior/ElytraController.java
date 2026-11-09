package net.magicterra.worlddriver.bot.behavior;

import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.magicterra.worlddriver.bot.rules.ElytraPhysics;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Chooses each tick's elytra pitch and yaw by simulating a short look-ahead over the terrain. */
public final class ElytraController {
    /**
     * Pitch fan searched each tick, MC sign (+ dives, − climbs). Spans hard dive to hard climb so a firework tick can
     * pick a steep ascent.
     */
    private static final float[] PITCHES = {-45, -35, -25, -18, -12, -8, -4, 0, 4, 8, 15, 25, 40};

    /**
     * The fan for a via point. At boosted speed four degrees of pitch move the crossing of a ring forty blocks out by
     * three blocks, half its opening, so near level the steps are one degree.
     */
    private static final float[] FINE_PITCHES = {
        -45, -35, -25, -18, -14, -11, -9, -7, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 7, 9, 11, 14, 18, 25, 40
    };

    private static final int HORIZON = 30; // ticks simulated ahead (~45 blocks at cruise)

    static final int BOOST_LIFE = 20; // assumed live-rocket ticks (duration-1 ≈ 20–32)

    /**
     * The touchdown search: each pitch of {@link #FINE_PITCHES} held, and each held for one of these tick counts and
     * then switched to one of {@link #FLARE_PITCHES}. One held pitch only reaches the ground either diving too fast or
     * gliding past the target; a dive and then a flare, or a glide and then a dive onto the pad, is how a landing is
     * flown.
     */
    private static final int[] FLARE_AFTER = {4, 8, 14, 20};

    private static final float[] FLARE_PITCHES = {-35, -20, -8, 4, 12, 20};

    /** Ticks a touchdown candidate is simulated: about 60 blocks at boosted speed, past any landing approach. */
    private static final int TOUCHDOWN_HORIZON = 40;

    /**
     * The most fall a touchdown may carry. A glide keeps its fall at one block on every tick it starts sinking slower
     * than {@link #SLOW_SINK} ({@code LivingEntity.checkSlowFallDistance}), so only the fast-sinking ticks before
     * contact count; a fall over three blocks hurts even on hay, and half a block of the three is kept as margin
     * against the simulation.
     */
    private static final double SAFE_DROP = 2.5;

    private static final double SLOW_SINK = -0.5;

    /** How far above an edge's top the feet must pass when coming over it from outside. */
    private static final double EDGE_CLEARANCE = 0.5;

    /**
     * Score lost per block per tick of speed at touchdown. A fast touchdown slides five blocks or more, so among
     * touchdowns equally near the aim the slower one wins; weighed more heavily, it chose flares that floated the body
     * over the whole pad without touching it.
     */
    private static final double TOUCH_SPEED_WEIGHT = 1.0;

    /** Half a player's width, the reach of the body's front edge ahead of its middle. */
    private static final double HALF_WIDTH = 0.3;

    private static final int FIRE_COOLDOWN = 12; // hard floor between rockets (subsumed by the no-overlap gate)

    private static final double MIN_CRUISE = 0.45; // h-speed (b/t) below which we boost to keep moving

    /**
     * Climb-band hysteresis for fuel economy. A new boost-climb episode only STARTS once the bot has sagged more than
     * {@link #CLIMB_DEADBAND} below the target altitude, and ENDS once back within {@link #CLIMB_MARGIN}. Between, the
     * bot glides (free distance) — this is the glide-and-boost sawtooth that makes each rocket carry as far as possible
     * instead of topping up altitude every couple of blocks.
     */
    private static final double CLIMB_MARGIN = 2.0; // climb satisfied within this of target ⇒ stop boosting

    private static final double CLIMB_DEADBAND = 12.0; // sag this far below target before a boost-climb starts

    private static final double STEP = 0.5; // terrain-raytrace sample spacing (blocks)

    private static final int CLEAR_MARGIN = 2; // blocks of buffer we prefer to keep off terrain

    private static final double CLEAR_WEIGHT = 1.2; // per-tick penalty for skimming within the margin

    /**
     * Anti-jitter: penalise picking a pitch far from the one we're holding, so the discrete fan doesn't flip between
     * neighbours every tick (which reads as the camera bobbing up and down). This is hysteresis, NOT a rate limit —
     * when terrain ahead or an overhead goal makes another pitch clearly better the score swing dwarfs this bias and we
     * switch fully in one tick, so obstacle avoidance is never slowed.
     */
    private static final double TURN_WEIGHT = 0.18;

    /**
     * Landing-mode bias: among collision-safe trajectories prefer the one that ends slowest, so the flare bleeds speed
     * — but still terrain-aware (it never bypasses the crash/clearance check the way a blind fixed flare pitch did,
     * which is what flew the bot into hillsides on descent).
     */
    private static final double LAND_BLEED_WEIGHT = 3.0;

    private int boostRemaining; // ticks of firework boost still assumed live

    private boolean climbing; // in a boost-climb episode (hysteresis; see CLIMB_DEADBAND)

    private int sinceFire = Integer.MAX_VALUE;

    private float lastPitch; // pitch held last tick (hysteresis reference)

    private boolean pitchInit; // seed from the live look on first tick

    record Decision(float pitch, boolean fire) {}

    /**
     * The yaw the flight would take from a position. The lookahead asks it at every simulated tick, so a flight that
     * turns as it goes is simulated turning: held at this tick's yaw, a curve through a ring's opening reads as a
     * straight line into its frame.
     */
    @FunctionalInterface
    interface Steer {
        float yaw(Vec3 pos);
    }

    Decision decide(WorldView w, LivingEntity p, Vec3 vel, float yaw, Vec3 goal, boolean fireworksAllowed) {
        return decide(w, p, vel, yaw, goal, fireworksAllowed, false);
    }

    /**
     * Decides this tick's pitch and yaw toward {@code goal} from a forward simulation of each candidate.
     *
     * @param landing flare mode — no boost, and among collision-safe pitches prefer the slowest-ending one (bleed
     *     speed) while STILL avoiding terrain. The previous flare used a blind fixed pitch with no lookahead, which
     *     crashed into rising ground on the final approach.
     */
    Decision decide(
            WorldView w, LivingEntity p, Vec3 vel, float yaw, Vec3 goal, boolean fireworksAllowed, boolean landing) {
        return decide(w, p, vel, q -> yaw, goal, fireworksAllowed, landing, false, false);
    }

    /**
     * As above, steered by {@code steer} through the lookahead; with {@code sustain} a rocket is lit whenever the last
     * one is spent, not only when the body is slow or low, for a flight that wants speed more than range; with
     * {@code fine} the pitches are searched in {@link #FINE_PITCHES}, for a goal that has to be met within a few blocks.
     */
    Decision decide(
            WorldView w,
            LivingEntity p,
            Vec3 vel,
            Steer steer,
            Vec3 goal,
            boolean fireworksAllowed,
            boolean landing,
            boolean sustain,
            boolean fine) {
        advanceClock(p);

        Vec3 pos = p.position();
        double horizSpeed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        // Hysteretic climb band → glide-and-boost sawtooth (fuel economy):
        // only engage a climb once we've sagged past the deadband, ride it
        // until back near the target, then glide the gained altitude away.
        double deficit = goal.y - pos.y;
        if (deficit > CLIMB_DEADBAND) {
            climbing = true;
        } else if (deficit <= CLIMB_MARGIN) {
            climbing = false;
        }
        boolean needClimb = climbing;
        // No-overlap: never light a new rocket while the previous boost is
        // still burning — wait until it's fully spent so every rocket's thrust
        // is used in full (the old FIRE_COOLDOWN=12 < BOOST_LIFE=20 let two
        // boosts overlap, wasting the second against the speed cap).
        boolean fire = !landing
                && fireworksAllowed
                && boostRemaining <= 0
                && sinceFire >= FIRE_COOLDOWN
                && (sustain || needClimb || horizSpeed < MIN_CRUISE);

        // Boost ticks the lookahead should assume: a rocket already burning,
        // or the one we're about to light this tick.
        int boostTicks = fire ? BOOST_LIFE : boostRemaining;

        float bestPitch = lastPitch;
        double bestScore = Double.NEGATIVE_INFINITY;
        StringBuilder trace = BehaviorConfig.elytraDebug ? new StringBuilder() : null;
        for (float pitch : fine ? FINE_PITCHES : PITCHES) {
            // Bias toward the pitch we're already holding so near-equal
            // candidates don't flip tick-to-tick (camera bob); a real
            // avoidance need outscores this and switches fully in one tick.
            Sim sim = simulate(w, pos, vel, steer, pitch, boostTicks, goal);
            double score = score(sim, landing) - TURN_WEIGHT * Math.abs(pitch - lastPitch);
            if (trace != null) {
                trace.append(String.format(
                        Locale.ROOT, " %.0f:%s%.1f", pitch, sim.crashed() ? "x" + sim.crashTick() + "/" : "", score));
            }
            if (score > bestScore) {
                bestScore = score;
                bestPitch = pitch;
            }
        }
        if (trace != null) {
            LOG.info("[elytra] pitches{}", trace);
        }
        lastPitch = bestPitch;
        return new Decision(bestPitch, fire);
    }

    private void advanceClock(LivingEntity p) {
        if (boostRemaining > 0) {
            boostRemaining--;
        }
        if (sinceFire < Integer.MAX_VALUE) {
            sinceFire++;
        }
        if (!pitchInit) {
            lastPitch = p.getXRot();
            pitchInit = true;
        }
    }

    /** Mark that a rocket was actually lit (the actuator confirmed the use). */
    void onFired() {
        boostRemaining = BOOST_LIFE;
        sinceFire = 0;
    }

    /** Count a rocket lit {@code age} ticks ago, by an earlier order, as still burning. */
    void seedBoost(long age) {
        if (age >= 0 && age < BOOST_LIFE) {
            boostRemaining = BOOST_LIFE - (int) age;
            sinceFire = (int) age;
        }
    }

    /**
     * Decides a landing tick: the pitch whose simulated flight touches down nearest {@code aim}, on its surface, with
     * a fall of no more than {@link #SAFE_DROP}. Unlike {@link #decide}, contact with a top face is the goal here
     * rather than a crash; a side hit still is one. A rocket is lit only when {@code boost} asks for it.
     */
    Decision decideTouchdown(WorldView w, LivingEntity p, Vec3 vel, Steer steer, Vec3 aim, boolean boost) {
        advanceClock(p);
        boolean fire = boost && boostRemaining <= 0 && sinceFire >= FIRE_COOLDOWN;
        int boostTicks = fire ? BOOST_LIFE : boostRemaining;
        Vec3 pos = p.position();
        // Hundreds of candidates walk the same few cells; reading each once keeps the search to a few thousand reads.
        Map<BlockPos, Boolean> solid = new HashMap<>();
        float bestPitch = lastPitch;
        double bestScore = Double.NEGATIVE_INFINITY;
        Touch best = null;
        StringBuilder trace = BehaviorConfig.elytraDebug ? new StringBuilder() : null;
        // A pad is a few blocks long and a glide's slope moves the touchdown a block per degree, so the fine fan.
        for (float first : FINE_PITCHES) {
            double turn = TURN_WEIGHT * Math.abs(first - lastPitch);
            for (int after = -1; after < FLARE_AFTER.length; after++) {
                for (float flare : after < 0 ? new float[] {first} : FLARE_PITCHES) {
                    int switchAt = after < 0 ? 0 : FLARE_AFTER[after];
                    Touch t = simulateTouchdown(w, solid, pos, vel, steer, first, switchAt, flare, boostTicks, aim);
                    double s = touchdownScore(t, aim) - turn;
                    if (trace != null && after < 0) {
                        trace.append(String.format(
                                Locale.ROOT,
                                " %.0f:%s@%d(%.1f,%.1f,%.1f)f%.1f",
                                first,
                                t.contact().name().charAt(0),
                                t.tick(),
                                t.at().x,
                                t.at().y,
                                t.at().z,
                                t.drop()));
                    }
                    if (s > bestScore) {
                        bestScore = s;
                        bestPitch = first;
                        best = t;
                    }
                }
            }
        }
        if (BehaviorConfig.elytraDebug && best != null) {
            LOG.info(
                    "[elytra] touchdown best pitch={} score={} {} t={} at=({},{},{}) fall={} speed={}",
                    bestPitch,
                    fmt(bestScore),
                    best.contact(),
                    best.tick(),
                    fmt(best.at().x),
                    fmt(best.at().y),
                    fmt(best.at().z),
                    fmt(best.drop()),
                    fmt(best.speed()));
            LOG.info("[elytra] touchdown held{}", trace);
        }
        lastPitch = bestPitch;
        return new Decision(bestPitch, fire);
    }

    /** How a touchdown candidate ended: on a top face, against a side, or still in the air at the horizon. */
    private enum Contact {
        TOUCH,
        CRASH,
        AIR
    }

    /**
     * A touchdown candidate's outcome: where and when it touched (or crashed), the fall it carried there, and its
     * closest pass over the aim when it stayed airborne.
     */
    private record Touch(Contact contact, int tick, Vec3 at, double drop, double minDist, double speed) {
        Touch(Contact contact, int tick, Vec3 at, double drop, double minDist) {
            this(contact, tick, at, drop, minDist, 0);
        }
    }

    /**
     * Flies {@code first} pitch, switching to {@code flare} after {@code after} ticks (never when {@code after} is 0),
     * until the body meets a block. Contact counts as a touchdown when the feet come down onto a block's top face with
     * the head cell clear.
     */
    private static Touch simulateTouchdown(
            WorldView w,
            Map<BlockPos, Boolean> solid,
            Vec3 pos0,
            Vec3 vel0,
            Steer steer,
            float first,
            int after,
            float flare,
            int boostTicks,
            Vec3 aim) {
        ElytraPhysics.State s = new ElytraPhysics.State(pos0, vel0);
        Vec3 over = aim.add(0, 1, 0);
        double minDist = pos0.distanceTo(over);
        // The fall so far is not known here; sinking fast already, it is taken to be three blocks.
        double drop = vel0.y > SLOW_SINK ? 1.0 : 3.0;
        double nearestH = Math.hypot(pos0.x - aim.x, pos0.z - aim.z);
        boolean passed = false;
        Vec3 prev = pos0;
        for (int t = 0; t < TOUCHDOWN_HORIZON; t++) {
            float pitch = after > 0 && t >= after ? flare : first;
            if (s.vel().y > SLOW_SINK) {
                drop = Math.min(drop, 1.0);
            }
            s = ElytraPhysics.stepTick(s, steer.yaw(s.pos()), pitch, t < boostTicks);
            Vec3 cur = s.pos();
            Vec3 d = cur.subtract(prev);
            // Samples closer than the front edge reaches ahead, so the edge probes below cannot step over an edge.
            int steps = Math.max(1, (int) Math.ceil(d.length() / (HALF_WIDTH * 0.8)));
            Vec3 last = prev;
            // The body's front edge, half its width ahead: coming in low over the pad's edge it meets the pad's side
            // while its middle is still over the air outside, which a test of the middle alone calls a touchdown.
            double flat = Math.hypot(d.x, d.z);
            Vec3 edge = flat < 1e-6 ? Vec3.ZERO : new Vec3(d.x / flat * HALF_WIDTH, 0, d.z / flat * HALF_WIDTH);
            for (int i = 1; i <= steps; i++) {
                Vec3 at = prev.add(d.scale((double) i / steps));
                // Only a side entry: the last sample was already below that block's top. Sinking onto a top face
                // between two samples is the touchdown test's to judge.
                if (cachedSolid(w, solid, at.x + edge.x, at.y + 0.05, at.z + edge.z)
                        && last.y < Math.floor(at.y + 0.05) + 1.0) {
                    return new Touch(Contact.CRASH, t, at, drop, minDist);
                }
                // Coming over an edge from the air outside, the feet keep EDGE_CLEARANCE above its top: the
                // simulation's height is good to a few tenths, and a flat approach that grazes the edge in the
                // simulation meets its side in the world.
                if (!cachedSolid(w, solid, at.x, at.y - EDGE_CLEARANCE, at.z)
                        && cachedSolid(w, solid, at.x + edge.x, at.y - EDGE_CLEARANCE, at.z + edge.z)) {
                    return new Touch(Contact.CRASH, t, at, drop, minDist);
                }
                boolean feet = cachedSolid(w, solid, at.x, at.y, at.z);
                if (feet || cachedSolid(w, solid, at.x, at.y + 1.0, at.z)) {
                    double top = Math.floor(at.y) + 1.0;
                    if (feet && last.y >= top - 1e-6 && !cachedSolid(w, solid, at.x, top + 0.5, at.z)) {
                        double fall = drop + Math.max(0, prev.y - top);
                        return new Touch(Contact.TOUCH, t, new Vec3(at.x, top, at.z), fall, minDist, flat);
                    }
                    return new Touch(Contact.CRASH, t, at, drop, minDist);
                }
                last = at;
            }
            drop += Math.max(0, -d.y);
            // Only the first pass counts: steered at the aim, a candidate that misses circles it, and on some later
            // lap every gentle glide comes near it, which made them all score alike.
            double h = Math.hypot(cur.x - aim.x, cur.z - aim.z);
            if (h > nearestH + 1.0) {
                passed = true;
            }
            nearestH = Math.min(nearestH, h);
            if (!passed) {
                minDist = Math.min(minDist, cur.distanceTo(over));
            }
            prev = cur;
        }
        return new Touch(Contact.AIR, TOUCHDOWN_HORIZON, prev, drop, minDist);
    }

    /**
     * Higher is better. A safe touchdown on the aim's level scores by how near the aim it lands, a little sooner
     * being better; one on another level or with a hurting drop scores far below, and below a candidate still
     * airborne that passes over the aim. A side hit is rejected as in {@link #score}.
     */
    private static double touchdownScore(Touch t, Vec3 aim) {
        return switch (t.contact()) {
            case CRASH -> -1.0e6 + t.tick() * 1000.0;
            case AIR -> -30.0 - t.minDist();
            case TOUCH -> {
                double s = -2.0 * Math.hypot(t.at().x - aim.x, t.at().z - aim.z)
                        - 0.05 * t.tick()
                        - TOUCH_SPEED_WEIGHT * t.speed();
                if (Math.abs(t.at().y - aim.y) > 0.5) {
                    s -= 100.0;
                }
                if (t.drop() > SAFE_DROP) {
                    s -= 50.0 + 10.0 * (t.drop() - SAFE_DROP);
                }
                yield s;
            }
        };
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static boolean cachedSolid(WorldView w, Map<BlockPos, Boolean> solid, double x, double y, double z) {
        return solid.computeIfAbsent(
                new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)), w::isSolid);
    }

    // --- forward simulation + scoring ------------------------------------

    private record Sim(boolean crashed, int crashTick, double minDist, int nearTicks, double endHorizSpeed) {}

    private Sim simulate(WorldView w, Vec3 pos0, Vec3 vel0, Steer steer, float pitch, int boostTicks, Vec3 goal) {
        ElytraPhysics.State s = new ElytraPhysics.State(pos0, vel0);
        double minDist = pos0.distanceTo(goal);
        Vec3 prev = pos0;
        int nearTicks = 0;
        for (int t = 0; t < HORIZON; t++) {
            s = ElytraPhysics.stepTick(s, steer.yaw(s.pos()), pitch, t < boostTicks);
            Vec3 cur = s.pos();
            if (segmentHitsTerrain(w, prev, cur)) {
                return new Sim(true, t, minDist, nearTicks, 0);
            }
            // Soft clearance: cheap per-tick probe at the tick's endpoint
            // (margin is a preference, not a safety check — coarse is fine).
            if (marginBreached(w, cur.x, cur.y, cur.z)) {
                nearTicks++;
            }
            double d = cur.distanceTo(goal);
            if (d < minDist) {
                minDist = d;
            }
            prev = cur;
        }
        Vec3 ev = s.vel();
        return new Sim(false, HORIZON, minDist, nearTicks, Math.hypot(ev.x, ev.z));
    }

    /**
     * Higher is better. A crash is rejected hard (later crash slightly less bad, so when every option crashes the bot
     * still buys the most time to recover); otherwise closer approach to the goal wins, with a penalty for skimming
     * terrain inside the clearance margin so the bot keeps a buffer over ridges instead of scraping them. In landing
     * mode the slowest-ending safe trajectory is preferred so the flare bleeds speed without ever giving up terrain
     * avoidance.
     */
    private static double score(Sim r, boolean landing) {
        if (r.crashed) {
            return -1.0e6 + r.crashTick * 1000.0;
        }
        double s = -r.minDist - CLEAR_WEIGHT * r.nearTicks;
        if (landing) {
            s -= LAND_BLEED_WEIGHT * r.endHorizSpeed;
        }
        return s;
    }

    /**
     * True if the player's ~2-tall hitbox sweeping from {@code a} to {@code b} passes through any solid block (sampled
     * along the segment so a fast tick can't tunnel a thin wall). Unloaded chunks read as non-solid.
     */
    private static boolean segmentHitsTerrain(WorldView w, Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / STEP));
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            double x = a.x + dx * f;
            double y = a.y + dy * f;
            double z = a.z + dz * f;
            if (solidAt(w, x, y, z) || solidAt(w, x, y + 1.0, z)) {
                return true; // feet + head cell
            }
        }
        return false;
    }

    /**
     * True if any solid sits within the clearance box around the point (but outside the tight hitbox, which is a crash
     * handled separately).
     */
    private static boolean marginBreached(WorldView w, double x, double y, double z) {
        for (int ox = -CLEAR_MARGIN; ox <= CLEAR_MARGIN; ox++) {
            for (int oy = -CLEAR_MARGIN; oy <= CLEAR_MARGIN + 1; oy++) {
                for (int oz = -CLEAR_MARGIN; oz <= CLEAR_MARGIN; oz++) {
                    if (solidAt(w, x + ox, y + oy, z + oz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean solidAt(WorldView w, double x, double y, double z) {
        return w.isSolid(new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }
}

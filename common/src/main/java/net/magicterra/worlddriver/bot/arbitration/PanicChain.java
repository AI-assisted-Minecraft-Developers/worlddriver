package net.magicterra.worlddriver.bot.arbitration;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.world.HazardField;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ArbitrationConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Phase B top-priority reflex — a creeper is close enough to be lethal, so sprint straight away from it. A creeper's
 * fuse is ~1.5 s; pathfinding away is too slow to plan, so this is a blunt immediate response (turn back to the
 * creeper, hold forward + sprint) rather than a planned route. Outbids everything except the water-bucket clutch. Gated
 * on {@link ArbitrationConfig#autoDodge}.
 */
public final class PanicChain implements Chain {

    /**
     * Candidate flee headings, in degrees offset from "straight away", tried nearest-to-straight-away first. Lets the
     * bot peel along a shore/ledge instead of sprinting off it.
     */
    private static final double[] FLEE_OFFSETS = {
        0, 25, -25, 50, -50, 75, -75, 90, -90, 115, -115, 135, -135, 160, -160, 180
    };

    @Override
    public String name() {
        return "panic";
    }

    @Override
    public float priority(Body body, WorldView w, BotState st) {
        if (!ArbitrationConfig.autoDodge || body == null || body.entity() == null) {
            return 0f;
        }
        return nearestCreeper(st) != null ? Priorities.PANIC : 0f;
    }

    @Override
    public void tick(Body body, WorldView w, BotState st) {
        Entity creeper = nearestCreeper(st);
        LivingEntity p = body.entity();
        if (creeper == null || p == null) {
            return;
        }
        Vec3 away = p.position().subtract(creeper.position()); // creeper → me
        if (away.lengthSqr() <= 1.0e-6) {
            body.releaseControls();
            return;
        }
        double ax = away.x;
        double az = away.z;
        double len = Math.sqrt(ax * ax + az * az);
        ax /= len;
        az /= len;

        // Sprinting straight away from a creeper is lethal if "away" runs off a
        // cliff or into lava/deep water (death #7 sibling: the bot backed off a
        // ledge). Pick the heading closest to straight-away whose next 1-2 cells
        // are walkable and non-lethal, using the same HazardField the planner uses.
        BlockPos foot = p.blockPosition();
        HazardField hf =
                HazardField.compute(w, foot, 3, SurvivalMath.survivableFall(p.getHealth()), WorldConfig.deepWaterMax);
        double bestX = 0;
        double bestZ = 0;
        boolean found = false;
        for (double off : FLEE_OFFSETS) {
            double r = Math.toRadians(off);
            double dx = ax * Math.cos(r) - az * Math.sin(r);
            double dz = ax * Math.sin(r) + az * Math.cos(r);
            if (safeStep(hf, dx, dz, 1) && safeStep(hf, dx, dz, 2)) {
                bestX = dx;
                bestZ = dz;
                found = true;
                break;
            }
        }
        if (!found) {
            // Cornered: every heading steps into a lethal cell. Holding still and
            // eating the blast (a full-HP bot often survives a single creeper) beats
            // a guaranteed void/lava death. Face away, but do NOT sprint forward.
            body.releaseControls();
            p.setYRot((float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0));
            p.setXRot(0f);
            return;
        }
        p.setYRot((float) (Math.toDegrees(Math.atan2(bestZ, bestX)) - 90.0));
        p.setXRot(0f);
        body.commandForward(1f);
        body.commandSprint(true);
    }

    /**
     * The cell {@code dist} blocks along (dx,dz) from the bot is walkable and non-lethal (not a killing drop / lava /
     * deep water).
     */
    private static boolean safeStep(HazardField hf, double dx, double dz, int dist) {
        int ox = (int) Math.round(dx * dist);
        int oz = (int) Math.round(dz * dist);
        var cell = hf.at(ox, oz);
        return cell.standable() && !cell.lethal();
    }

    @Override
    public void onInterrupt(Body body, Chain by) {
        if (body != null) {
            body.releaseControls();
        }
    }

    @Override
    public void onResume() {
        // Stateless between bids: nothing to restore.
    }

    /** The closest creeper within the keep-distance (or one already swelling a bit further out), else null. */
    private Entity nearestCreeper(BotState st) {
        ThreatScanner.Scan scan = st.threats.current();
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ThreatScanner.Threat t : scan.threats()) {
            if (!t.type().endsWith("creeper")) {
                continue;
            }
            boolean close = t.distance() <= ArbitrationConfig.creeperKeepDistance;
            boolean swelling = t.creeperSwell() > 0f && t.distance() <= ArbitrationConfig.creeperKeepDistance + 3.0;
            if ((close || swelling) && t.distance() < bestDist) {
                best = t.entity();
                bestDist = t.distance();
            }
        }
        return best;
    }
}

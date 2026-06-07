package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.bot.movement.BotInput;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.world.HazardField;
import net.magicterra.agent.bot.world.SurvivalMath;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;

/**
 * Phase B top-priority reflex — a creeper is close enough to be lethal, so sprint
 * straight away from it. A creeper's fuse is ~1.5 s; pathfinding away is too slow
 * to plan, so this is a blunt immediate response (turn back to the creeper, hold
 * forward + sprint) rather than a planned route. Outbids everything except the
 * water-bucket clutch. Gated on {@link BotConfig#autoDodge}.
 */
public final class PanicChain implements Chain {

    @Override public String name() { return "panic"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoDodge || mc.player == null) return 0f;
        return nearestCreeper(mc) != null ? Priorities.PANIC : 0f;
    }

    /** Candidate flee headings, in degrees offset from "straight away", tried
     *  nearest-to-straight-away first. Lets the bot peel along a shore/ledge
     *  instead of sprinting off it. */
    private static final double[] FLEE_OFFSETS =
            {0, 25, -25, 50, -50, 75, -75, 90, -90, 115, -115, 135, -135, 160, -160, 180};

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        Entity creeper = nearestCreeper(mc);
        if (creeper == null || mc.player == null) return;
        var p = mc.player;
        Vec3 away = p.position().subtract(creeper.position());   // creeper → me
        if (away.lengthSqr() <= 1.0e-6) { releaseKeys(); return; }
        double ax = away.x, az = away.z;
        double len = Math.sqrt(ax * ax + az * az);
        ax /= len; az /= len;

        // Sprinting straight away from a creeper is lethal if "away" runs off a
        // cliff or into lava/deep water (death #7 sibling: the bot backed off a
        // ledge). Pick the heading closest to straight-away whose next 1-2 cells
        // are walkable and non-lethal, using the same HazardField the planner uses.
        BlockPos foot = p.blockPosition();
        HazardField hf = HazardField.compute(w, foot, 3,
                SurvivalMath.survivableFall(p.getHealth()), BotConfig.deepWaterMax);
        double bestX = 0, bestZ = 0;
        boolean found = false;
        for (double off : FLEE_OFFSETS) {
            double r = Math.toRadians(off);
            double dx = ax * Math.cos(r) - az * Math.sin(r);
            double dz = ax * Math.sin(r) + az * Math.cos(r);
            if (safeStep(hf, dx, dz, 1) && safeStep(hf, dx, dz, 2)) {
                bestX = dx; bestZ = dz; found = true; break;
            }
        }
        if (!found) {
            // Cornered: every heading steps into a lethal cell. Holding still and
            // eating the blast (a full-HP bot often survives a single creeper) beats
            // a guaranteed void/lava death. Face away, but do NOT sprint forward.
            releaseKeys();
            p.setYRot((float) (Math.toDegrees(Math.atan2(az, ax)) - 90.0));
            p.setXRot(0f);
            return;
        }
        p.setYRot((float) (Math.toDegrees(Math.atan2(bestZ, bestX)) - 90.0));
        p.setXRot(0f);
        BotInput.forward(mc, true);
        p.setSprinting(true);
    }

    /** The cell {@code dist} blocks along (dx,dz) from the bot is walkable and
     *  non-lethal (not a killing drop / lava / deep water). */
    private static boolean safeStep(HazardField hf, double dx, double dz, int dist) {
        int ox = (int) Math.round(dx * dist);
        int oz = (int) Math.round(dz * dist);
        var cell = hf.at(ox, oz);
        return cell.standable() && !cell.lethal();
    }

    @Override public void onInterrupt(Chain by) { releaseKeys(); }
    @Override public void onResume() {}

    /** The closest creeper within the keep-distance (or one already swelling a bit
     *  further out), else null. */
    private Entity nearestCreeper(Minecraft mc) {
        ThreatScanner.Scan scan = ThreatScanner.current(mc);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ThreatScanner.Threat t : scan.threats()) {
            if (!t.type().endsWith("creeper")) continue;
            boolean close = t.distance() <= BotConfig.creeperKeepDistance;
            boolean swelling = t.creeperSwell() > 0f && t.distance() <= BotConfig.creeperKeepDistance + 3.0;
            if ((close || swelling) && t.distance() < bestDist) { best = t.entity(); bestDist = t.distance(); }
        }
        return best;
    }
}

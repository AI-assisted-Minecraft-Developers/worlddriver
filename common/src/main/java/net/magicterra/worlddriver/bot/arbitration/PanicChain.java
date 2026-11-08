package net.magicterra.worlddriver.bot.arbitration;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.behavior.StepAway;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ArbitrationConfig;
import net.magicterra.worlddriver.game.body.Body;
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
        if (away.x * away.x + away.z * away.z <= 1.0e-6) {
            body.releaseControls();
            return;
        }
        // Sprinting straight away from a creeper is lethal if "away" runs off a
        // cliff or into lava/deep water (death #7 sibling: the bot backed off a
        // ledge). Take the heading closest to straight-away whose next 1-2 cells
        // are walkable and non-lethal.
        double[] heading = StepAway.heading(w, p, creeper.position(), 2, 180.0);
        if (heading == null) {
            // Cornered: every heading steps into a lethal cell. Holding still and
            // eating the blast (a full-HP bot often survives a single creeper) beats
            // a guaranteed void/lava death. Face away, but do NOT sprint forward.
            body.releaseControls();
            p.setYRot((float) (Math.toDegrees(Math.atan2(away.z, away.x)) - 90.0));
            p.setXRot(0f);
            return;
        }
        p.setYRot(StepAway.yawOf(heading));
        p.setXRot(0f);
        body.commandForward(1f);
        body.commandSprint(true);
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

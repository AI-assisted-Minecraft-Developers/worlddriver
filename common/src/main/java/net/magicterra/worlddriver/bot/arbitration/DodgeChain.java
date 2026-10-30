package net.magicterra.worlddriver.bot.arbitration;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ArbitrationConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Phase B reflex — an arrow/trident/fireball is predicted to hit, so strafe perpendicular to its flight to step out of
 * the line. Outranked only by PanicChain (a creeper blast is the bigger threat) and the clutch. Gated on
 * {@link ArbitrationConfig#autoDodge}. (Dragon-breath cloud avoidance reuses this lane in Phase G; ThreatScanner
 * doesn't track AreaEffectClouds yet.)
 */
public final class DodgeChain implements Chain {

    @Override
    public String name() {
        return "dodge";
    }

    @Override
    public float priority(Body body, WorldView w, BotState st) {
        LivingEntity p = body == null ? null : body.entity();
        if (!ArbitrationConfig.autoDodge || p == null) {
            return 0f;
        }
        return imminent(p, st) != null ? Priorities.DODGE : 0f;
    }

    @Override
    public void tick(Body body, WorldView w, BotState st) {
        LivingEntity p = body.entity();
        if (p == null) {
            return;
        }
        ThreatScanner.Incoming in = imminent(p, st);
        if (in == null) {
            return;
        }
        // Perpendicular to the projectile's horizontal velocity (rotate 90° in XZ).
        Vec3 v = in.vel();
        double px = -v.z;
        double pz = v.x;
        double len = Math.sqrt(px * px + pz * pz);
        if (len > 1.0e-6) {
            p.setYRot((float) (Math.toDegrees(Math.atan2(pz, px)) - 90.0));
            p.setXRot(0f);
        }
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

    /** The soonest projectile predicted to hit, within the dodge radius. */
    private ThreatScanner.Incoming imminent(LivingEntity p, BotState st) {
        ThreatScanner.Scan scan = st.threats.current();
        Vec3 me = p.position();
        ThreatScanner.Incoming best = null;
        int bestT = Integer.MAX_VALUE;
        for (ThreatScanner.Incoming in : scan.projectiles()) {
            if (!in.willHit()) {
                continue;
            }
            if (in.pos().distanceToSqr(me)
                    > ArbitrationConfig.projectileDodgeRadius * ArbitrationConfig.projectileDodgeRadius) {
                continue;
            }
            if (in.ticksToImpact() < bestT) {
                best = in;
                bestT = in.ticksToImpact();
            }
        }
        return best;
    }
}

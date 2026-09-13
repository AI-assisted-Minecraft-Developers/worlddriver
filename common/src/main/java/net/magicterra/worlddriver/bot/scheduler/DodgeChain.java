package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.movement.ClientPlayerAvatar;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.combat.ClientThreatScanner;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.worlddriver.bot.util.BotInteract.releaseKeys;

/**
 * Phase B reflex — an arrow/trident/fireball is predicted to hit, so strafe
 * perpendicular to its flight to step out of the line. Outranked only by
 * PanicChain (a creeper blast is the bigger threat) and the clutch. Gated on
 * {@link BotConfig#autoDodge}. (Dragon-breath cloud avoidance reuses this lane in
 * Phase G; ThreatScanner doesn't track AreaEffectClouds yet.)
 */
public final class DodgeChain implements Chain {

    @Override public String name() { return "dodge"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoDodge || mc.player == null) return 0f;
        return imminent(mc) != null ? Priorities.DODGE : 0f;
    }

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        ThreatScanner.Incoming in = imminent(mc);
        if (in == null || mc.player == null) return;
        var p = mc.player;
        // Perpendicular to the projectile's horizontal velocity (rotate 90° in XZ).
        Vec3 v = in.vel();
        double px = -v.z, pz = v.x;
        double len = Math.sqrt(px * px + pz * pz);
        if (len > 1.0e-6) {
            p.setYRot((float) (Math.toDegrees(Math.atan2(pz, px)) - 90.0));
            p.setXRot(0f);
        }
        ClientPlayerAvatar a = new ClientPlayerAvatar(mc);
        a.commandForward(1f);
        a.commandSprint(true);
    }

    @Override public void onInterrupt(Chain by) { releaseKeys(); }
    @Override public void onResume() {}

    /** The soonest projectile predicted to hit, within the dodge radius. */
    private ThreatScanner.Incoming imminent(Minecraft mc) {
        ThreatScanner.Scan scan = ClientThreatScanner.current(mc);
        if (mc.player == null) return null;
        Vec3 me = mc.player.position();
        ThreatScanner.Incoming best = null;
        int bestT = Integer.MAX_VALUE;
        for (ThreatScanner.Incoming in : scan.projectiles()) {
            if (!in.willHit()) continue;
            if (in.pos().distanceToSqr(me) > BotConfig.projectileDodgeRadius * BotConfig.projectileDodgeRadius) continue;
            if (in.ticksToImpact() < bestT) { best = in; bestT = in.ticksToImpact(); }
        }
        return best;
    }
}

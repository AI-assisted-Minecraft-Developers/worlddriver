package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
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

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        Entity creeper = nearestCreeper(mc);
        if (creeper == null || mc.player == null) return;
        var p = mc.player;
        Vec3 away = p.position().subtract(creeper.position());   // creeper → me
        if (away.lengthSqr() > 1.0e-6) {
            p.setYRot((float) (Math.toDegrees(Math.atan2(away.z, away.x)) - 90.0));
            p.setXRot(0f);
        }
        mc.options.keyUp.setDown(true);
        mc.options.keySprint.setDown(true);
        p.setSprinting(true);
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

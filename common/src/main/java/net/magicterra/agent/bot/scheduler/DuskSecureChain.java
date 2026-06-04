package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.process.BunkerProcess;
import net.magicterra.agent.bot.world.WorldModel;
import net.minecraft.client.Minecraft;

import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;

/**
 * Idle-only proactive shelter at dusk (controlled re-intro of the shelved BunkerChain).
 * Bids IDLE_SECURE (40) — below USER (50) — ONLY when dusk/night + sky-exposed + not
 * cornered + no nearby threat, and only after a short debounce so a brief between-intents
 * pause doesn't trigger a dig. Drives BunkerProcess. See docs/design/04 / spec §4.3.
 */
public final class DuskSecureChain implements Chain {
    private static final int IDLE_DEBOUNCE_TICKS = 50; // ~2.5s at 20 tps
    private static final double THREAT_RADIUS = 12.0;
    private final BotState state;
    private final WorldModel worldModel;
    private BunkerProcess process;
    private int idleTicks;

    public DuskSecureChain(BotState state, WorldModel worldModel) {
        this.state = state;
        this.worldModel = worldModel;
    }

    @Override public String name() { return "duskSecure"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoSecureAtDusk || mc.player == null) { idleTicks = 0; return 0f; }
        WorldModel.Snapshot s = worldModel.snapshot();
        if (!s.present() || !s.exposedAtNight() || s.cornered()) { idleTicks = 0; return 0f; }
        for (ThreatScanner.Threat t : ThreatScanner.current(mc).threats()) {
            if (t.distance() <= THREAT_RADIUS) { idleTicks = 0; return 0f; } // never dig in under attack
        }
        idleTicks++;
        if (idleTicks < IDLE_DEBOUNCE_TICKS) return 0f;  // require a stable window before acting
        return Priorities.IDLE_SECURE;
    }

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        if (mc.player == null) return;
        if (process == null) {
            process = new BunkerProcess(BotConfig.bunkerDepth);
            process.attach(st);
        }
        if (process.tick(mc, w, st)) process = null; // sheltered/done -> priority will drop next tick
    }

    @Override public void onInterrupt(Chain by) { process = null; releaseKeys(); }
}

package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.api.AgentApi;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.process.BunkerProcess;
import net.magicterra.agent.bot.world.WorldModel;
import net.magicterra.agent.rpc.JsonCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Map;

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
        // Once a shelter dig is committed, hold the channel until BunkerProcess finishes.
        // The 1-wide shaft we dig makes the bot 'cornered', which must NOT trip our own
        // start-gate and abandon a half-dug, unsealed hole. (Live-cert finding.)
        if (process != null) return Priorities.IDLE_SECURE;
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
            announceAutoTrigger(mc);
        }
        if (process.tick(mc, w, st)) process = null; // sheltered/done -> priority will drop next tick
    }

    /** Push a warning-level {@code duskSecure.triggered} event the instant this reflex
     *  starts an UNATTENDED dig, so an auto-trigger is never a silent surprise: the Agent
     *  can react (e.g. mc.bot.cancel) and the operator sees it. Pairs with the off-by-default
     *  {@link BotConfig#autoSecureAtDusk} to keep 挖三填一 predominantly an Agent-invoked
     *  action (mc.bot.bunker) rather than an uncontrolled reflex. */
    private static void announceAutoTrigger(Minecraft mc) {
        AgentApi api = AgentDriverCommon.api();
        if (api == null || mc.player == null) return;
        BlockPos p = mc.player.blockPosition();
        api.emitExternal("duskSecure.triggered", p,
                JsonCodec.encode(Map.of(
                        "pos", Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()),
                        "depth", BotConfig.bunkerDepth)));
    }

    @Override public void onInterrupt(Chain by) { process = null; releaseKeys(); }
}

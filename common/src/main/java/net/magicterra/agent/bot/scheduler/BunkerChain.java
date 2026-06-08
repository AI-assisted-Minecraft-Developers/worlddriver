package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.combat.ClientThreatScanner;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import static net.magicterra.agent.bot.util.BotInteract.aimAtBlockSnap;
import static net.magicterra.agent.bot.util.BotInteract.ensureHoldingPlaceableAny;
import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;
import static net.magicterra.agent.bot.util.BotInteract.selectBestToolFor;
import static net.magicterra.agent.bot.util.BotInteract.walkerPlace;

/**
 * Emergency "挖三填一" bunker reflex. When the bot is CORNERED — HP at/below
 * {@link BotConfig#bunkerHpThreshold} and at least {@link BotConfig#bunkerMinHostiles}
 * hostiles within {@link BotConfig#bunkerTriggerRadius}, the situation where
 * fleeing just runs into more mobs — it digs straight down
 * {@link BotConfig#bunkerDepth} blocks and seals the roof with one of the blocks
 * it just dug, leaving a 1×1 pocket no mob can reach. The canonical no-gear
 * survival answer to a swarm; it needs no pre-existing items because digging
 * supplies the seal block (works on dirt/sand/gravel — hand-mined blocks that
 * drop themselves; bare stone by hand drops nothing, so it still digs the hole
 * but can't cap it).
 *
 * <p>Priority {@link Priorities#BUNKER} (300) outranks a plain low-HP retreat
 * (fleeing into the swarm is worse) but sits below the creeper/projectile
 * reflexes. Once committed it finishes digging+sealing even if the threat count
 * momentarily dips, then holds the pocket until the surface clears, at which
 * point it releases the channel so the user/other chains can dig back out.
 * Gated on {@link BotConfig#autoBunker} (off by default — it modifies the world).
 */
public final class BunkerChain implements Chain {

    private static final int NONE = Integer.MIN_VALUE;
    private int startY = NONE;       // surface Y where this bunker episode began
    private boolean sealed = false;  // roof placed (or given up on) → safe, holding
    private int lastDepth = 0;
    private int digTicks = 0;        // per-block mining watchdog

    @Override public String name() { return "bunker"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoBunker || mc.player == null) return 0f;
        int near = surroundCount(mc);
        if (sealed) {
            if (near < BotConfig.bunkerMinHostiles) { reset(); return 0f; }
            return Priorities.BUNKER;                 // hold the pocket while still besieged
        }
        if (startY != NONE) return Priorities.BUNKER; // mid-dig: finish what we started
        // NB: do NOT gate on onGround() — under a swarm the bot is constantly
        // knocked back, flickering onGround false on the very ticks the trigger
        // needs to hold, so it never took the channel (observed live: bot beaten
        // to death with bunker priority stuck at 0). The tick() handles a
        // non-grounded start gracefully (waits to settle before digging).
        boolean cornered = mc.player.getHealth() <= BotConfig.bunkerHpThreshold
                && near >= BotConfig.bunkerMinHostiles;
        return cornered ? Priorities.BUNKER : 0f;
    }

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        BlockPos foot = p.blockPosition();
        if (startY == NONE) { startY = foot.getY(); sealed = false; lastDepth = 0; digTicks = 0; }

        if (sealed) { releaseKeys(); return; }        // safe pocket — sit tight

        int depth = startY - foot.getY();
        if (depth != lastDepth) { lastDepth = depth; digTicks = 0; }   // descended a level

        if (depth < BotConfig.bunkerDepth) {
            // --- dig straight down ---
            BlockPos below = foot.below();
            // Don't open a shaft into water/lava/another hazard, and don't bunker
            // while standing in water (that just drowns us). Bail to let retreat/
            // combat take over instead.
            if (w.isWater(foot) || w.isWater(foot.offset(0, 1, 0))
                    || w.isWater(below) || w.isHazard(below) || w.isHazard(foot.offset(0, 1, 0))) {
                mc.options.keyAttack.setDown(false);
                reset();
                return;
            }
            if (!w.isSolid(below)) { return; }        // already open (still falling) — settle a tick
            selectBestToolFor(mc, below);
            aimAtBlockSnap(p, below);
            mc.options.keyAttack.setDown(true);
            if (++digTicks > BotConfig.breakTimeoutTicks) {   // unbreakable (bedrock) — give up
                mc.options.keyAttack.setDown(false);
                reset();
            }
            return;
        }

        // --- deep enough: seal the roof (the cell just above the head) ---
        mc.options.keyAttack.setDown(false);
        BlockPos ceiling = foot.offset(0, 2, 0);
        if (w.isSolid(ceiling)) { sealed = true; releaseKeys(); return; }
        if (ensureHoldingPlaceableAny(mc)) {
            aimAtBlockSnap(p, ceiling);
            walkerPlace(mc, p, w, ceiling);
        } else {
            // Nothing placeable (e.g. hand-mined stone dropped nothing). The deep
            // hole still buys time; stop churning and hold.
            sealed = true;
            releaseKeys();
        }
    }

    @Override public void onInterrupt(Chain by) {
        if (mc() != null) mc().options.keyAttack.setDown(false);
        releaseKeys();
        reset();                                       // re-evaluate from the new position when resumed
    }

    @Override public void onResume() {}

    private void reset() { startY = NONE; sealed = false; lastDepth = 0; digTicks = 0; }

    /** Hostiles within the trigger radius — the "surrounded" gauge. */
    private int surroundCount(Minecraft mc) {
        ThreatScanner.Scan scan = ClientThreatScanner.current(mc);
        int n = 0;
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.distance() <= BotConfig.bunkerTriggerRadius) n++;
        }
        return n;
    }

    private static Minecraft mc() { return Minecraft.getInstance(); }
}

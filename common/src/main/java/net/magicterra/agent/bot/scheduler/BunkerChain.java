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

    /** Per-siege anchor + descent-bounding state (single source; see {@link BunkerAnchor}). */
    private final BunkerAnchor a = new BunkerAnchor();

    @Override public String name() { return "bunker"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoBunker || mc.player == null) return 0f;
        int near = surroundCount(mc);
        if (a.sealed) {
            if (near < BotConfig.bunkerMinHostiles) { a.reset(); return 0f; }
            return Priorities.BUNKER;                 // hold the pocket while still besieged
        }
        if (a.active()) return Priorities.BUNKER;     // mid-dig: finish what we started
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
        // Stale-episode guard (survival-run death#2 aftermath): the bot could die
        // mid-dig at HP≤10 and respawn at full HP tens of blocks away; the old code
        // measured `depth` against the pre-death startY and dug a runaway shaft at
        // spawn. Only a GENUINE displacement — teleport/respawn/knocked clean off the
        // column — invalidates the episode. Ordinary digging only lowers Y and small
        // knockback stays within DRIFT_TOL, so those keep the SAME anchor. Resetting
        // on any 1-block drift (as the old exact-XZ guard did), combined with the
        // reset-on-every-preempt in onInterrupt, was the gap#29 downward ratchet that
        // marched a 3.8-HP bot from y-5 to y-15. See {@link BunkerAnchor}.
        if (a.displacedFrom(foot.getX(), foot.getY(), foot.getZ())) {
            mc.options.keyAttack.setDown(false);
            releaseKeys();
            a.reset();
        }
        a.beginIfIdle(foot.getX(), foot.getY(), foot.getZ());

        if (a.sealed) { releaseKeys(); return; }      // safe pocket — sit tight

        int depth = a.depth(foot.getY());
        a.noteDepth(depth);                           // descended a level → reset per-block watchdog

        if (depth < BotConfig.bunkerDepth) {
            // --- dig straight down ---
            BlockPos below = foot.below();
            // Don't open a shaft into water/lava/another hazard, and don't bunker
            // while standing in water (that just drowns us). Bail to let retreat/
            // combat take over instead.
            if (w.isWater(foot) || w.isWater(foot.offset(0, 1, 0))
                    || w.isWater(below) || w.isHazard(below) || w.isHazard(foot.offset(0, 1, 0))) {
                mc.options.keyAttack.setDown(false);
                a.reset();
                return;
            }
            if (!w.isSolid(below)) { return; }        // already open (still falling) — settle a tick
            selectBestToolFor(mc, below);
            aimAtBlockSnap(p, below);
            mc.options.keyAttack.setDown(true);
            if (++a.digTicks > BotConfig.breakTimeoutTicks) {   // unbreakable (bedrock) — give up
                mc.options.keyAttack.setDown(false);
                a.reset();
            }
            return;
        }

        // --- deep enough: seal the roof (the cell just above the head) ---
        mc.options.keyAttack.setDown(false);
        BlockPos ceiling = foot.offset(0, 2, 0);
        if (w.isSolid(ceiling)) { a.sealed = true; releaseKeys(); return; }
        if (ensureHoldingPlaceableAny(mc)) {
            aimAtBlockSnap(p, ceiling);
            walkerPlace(mc, p, w, ceiling);
        } else {
            // Nothing placeable (e.g. hand-mined stone dropped nothing). The deep
            // hole still buys time; stop churning and hold.
            a.sealed = true;
            releaseKeys();
        }
    }

    @Override public void onInterrupt(Chain by) {
        // Release the movement channel but PRESERVE the episode (startY + sealed). The
        // old reset() here re-anchored startY to the current, lower foot Y on the next
        // tick — under a flickering swarm (dodge/combat trading the channel every few
        // ticks) that ratcheted the bot arbitrarily deep (gap#29). A genuine relocation
        // is caught by displacedFrom() on the resuming tick; a plain preemption must
        // resume the SAME pocket, not dig a fresh one.
        if (mc() != null) mc().options.keyAttack.setDown(false);
        releaseKeys();
        a.onPreempt();
    }

    @Override public void onResume() { a.resume(); }  // preemption gap must not accrue toward breakTimeoutTicks

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

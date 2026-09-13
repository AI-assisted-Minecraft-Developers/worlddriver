package net.magicterra.worlddriver.bot.scheduler;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.combat.ThreatScanner;
import net.magicterra.worlddriver.bot.combat.ClientThreatScanner;
import net.magicterra.worlddriver.bot.movement.ClientIntents;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BunkerProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import static net.magicterra.worlddriver.bot.util.BotInteract.aimAtBlockSnap;
import static net.magicterra.worlddriver.bot.util.BotInteract.continueDestroy;
import static net.magicterra.worlddriver.bot.util.BotInteract.ensureHoldingPlaceableAny;
import static net.magicterra.worlddriver.bot.util.BotInteract.releaseKeys;
import static net.magicterra.worlddriver.bot.util.BotInteract.selectBestToolFor;
import static net.magicterra.worlddriver.bot.util.BotInteract.walkerPlace;

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

    @Override public float priority(Avatar body, WorldView w, BotState st) {
        Minecraft mc = Chain.clientOf(body);
        if (!BotConfig.autoBunker || mc.player == null) return 0f;
        int near = surroundCount(mc);
        // Self-heal on displacement/respawn even when NOT winning the bid: the old check
        // lived in tick(), which only runs while this chain holds the channel — a stale
        // sealed episode from before a death could bid 300 forever (gap#68-⑦).
        if (a.active() && mc.player != null) {
            BlockPos f = mc.player.blockPosition();
            if (a.displacedFrom(f.getX(), f.getY(), f.getZ())) a.reset();
        }
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
        // death#26 (07-20 live): a ranged attacker (skeleton) pins a low-HP bot in
        // the OPEN — controlled-live proof: a 6-HP naked bot fled continuously for
        // 41s yet the skeleton held bow range (skelDist ~13, never the >18 needed to
        // break contact) and whittled it 6→4→2→dead. Open-ground flight can't break
        // line-of-sight; a bunker CAN (the roof block occludes the shot). So escalate
        // to the bunker when a low-HP bot is actually being SHOT, even though HP is
        // above the swarm threshold and the shooter sits well outside
        // bunkerTriggerRadius. The !sealed guard stops the gap#29 re-dig ratchet once
        // the pocket is capped (near=0 for a range-13 shooter would otherwise reset
        // the sealed latch and re-trigger a deeper dig every tick attackedMe holds).
        boolean sealed = BunkerProcess.enclosed(w, mc.player.blockPosition());
        boolean rangedPinned = shouldRangedBunker(mc.player.getHealth(),
                BotConfig.retreatHpThreshold, ClientThreatScanner.current(mc), sealed);
        return (cornered || rangedPinned) ? Priorities.BUNKER : 0f;
    }

    /** death#26 (07-20 live): the ranged-pin escalation as a static, scan-fed gate so
     *  it is matrix-testable without a client (live combat is too non-deterministic
     *  to reproduce "shot from range at low HP" — the skeleton either closes to melee
     *  and can't shoot, or holds range and whittles). Fire a bunker (break line-of-
     *  sight by sealing a roof) when a low-HP bot is actually being SHOT by a ranged
     *  mob, even though HP is above the swarm {@link BotConfig#bunkerHpThreshold} and
     *  the shooter sits outside {@link BotConfig#bunkerTriggerRadius}. {@code !sealed}
     *  stops the gap#29 re-dig ratchet once the pocket is capped.
     *  @param hp current health; @param retreatThr {@link BotConfig#retreatHpThreshold}
     *  @param sealed block-level enclosure ground truth ({@link BunkerProcess#enclosed}). */
    public static boolean shouldRangedBunker(float hp, float retreatThr, ThreatScanner.Scan scan,
                                             boolean sealed) {
        return !sealed && hp <= retreatThr && scan.underRangedFire();
    }

    @Override public void tick(Avatar body, WorldView w, BotState st) {
        Minecraft mc = Chain.clientOf(body);
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
                ClientIntents.holdDig(false);
                a.reset();
                return;
            }
            if (!w.isSolid(below)) { return; }        // already open (still falling) — settle a tick
            selectBestToolFor(mc, below);
            aimAtBlockSnap(p, below);
            ClientIntents.holdDig(true);
            // Through BotInteract, not inline: naming MultiPlayerGameMode here puts a client class
            // in this chain's own bytecode, and this chain is constructed on a dedicated server by
            // the gate's matrix scenes. See BotInteract#continueDestroy — the drive is what breaks
            // the block, and it also makes vanilla's attack pass stand aside (ClientIntents).
            continueDestroy(mc, p, below);
            if (++a.digTicks > BotConfig.breakTimeoutTicks) {   // unbreakable (bedrock) — give up
                ClientIntents.holdDig(false);
                a.reset();
            }
            return;
        }

        // --- deep enough: seal the roof (the cell just above the head) ---
        ClientIntents.holdDig(false);
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
        releaseKeys();
        a.onPreempt();
    }

    @Override public void onResume() { a.resume(); }  // preemption gap must not accrue toward breakTimeoutTicks

    @Override public String episodePhase() {
        if (a.sealed) return "SEALED";
        return a.active() ? "DIGGING" : null;
    }

    @Override public void cancelEpisode(String reason) {
        resetEpisodeState();
        // Client-only key release — split from the state reset so the state semantics
        // stay testable on the dedicated GameTest server (no client classes there).
        releaseKeys();
    }

    /** Pure episode-state reset (server-safe, matrix-testable). Public (not merely
     *  package-private) because the gap#68-R1a matrix test lives in the neoforge
     *  module's {@code AgentGameTestServer}, a different package/module than this
     *  common-module class. */
    public void resetEpisodeState() { a.reset(); }

    /** Test seam: the anchor, for episode lifecycle matrix tests. Public for the
     *  same cross-module reason as {@link #resetEpisodeState()}. */
    public BunkerAnchor anchorForTest() { return a; }

    /** Hostiles within the trigger radius — the "surrounded" gauge. */
    private int surroundCount(Minecraft mc) {
        ThreatScanner.Scan scan = ClientThreatScanner.current(mc);
        int n = 0;
        for (ThreatScanner.Threat t : scan.threats()) {
            if (t.distance() <= BotConfig.bunkerTriggerRadius) n++;
        }
        return n;
    }

}

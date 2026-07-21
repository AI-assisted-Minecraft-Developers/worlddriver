package net.magicterra.agent.bot.scheduler;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.auto.DrownEscapeGate;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import static net.magicterra.agent.AgentDriverCommon.LOG;
import static net.magicterra.agent.bot.util.BotInteract.aimAtBlockSnap;
import static net.magicterra.agent.bot.util.BotInteract.selectBestToolFor;

/**
 * ACTIVE-process drowning-escape reflex (gap#76, live death #25). A mine
 * process dug a shaft from y59 into water at y53, and once air ran out the bot
 * ate seven drown hits (HP 16→0) while mine KEPT BREAKING BLOCKS — the last
 * swing landed the same tick it died. Two existing defenses both missed by
 * design: {@code AutoSwim.drowningSentinel} is idle-only (gap#70's deliberate
 * scope), and {@code AutoSwim.tick}'s in-process jump backstop shares the input
 * channel with the process, whose per-tick dig/steer drive suppresses it. The
 * structural fix is scheduler-semantic: drowning under an ACTIVE process must
 * PREEMPT the movement channel, exactly like bunker/retreat/panic do.
 *
 * <p>Priority {@link Priorities#DROWN_ESCAPE} (500): above {@link BunkerChain}
 * (300 — bunker digs DOWN, which while drowning is precisely lethal) and the
 * user task, below {@link PanicChain} (1000)/{@link DodgeChain} (900) so a
 * creeper-blast sprint still wins.
 *
 * <p>Behaviour while holding the channel: PURE VERTICAL float — hold jump,
 * zero horizontal input, zero turning (the same idle-passivity boundary gap#70
 * drew: a survival float is a reflex, not autonomous movement). If the cell two
 * above the foot is a solid lid (a capped 1×1 well — the bunker-seal shape),
 * break it, {@code allowBreak} permitting, in the {@link BunkerChain}
 * aim+attack style ({@code AntiSuffocate} owns the eye-cell case; the lid here
 * is one ABOVE the eye cell, which that reflex never targets).
 *
 * <p>Entry/release live in the pure {@link DrownEscapeGate} (matrix-tested with
 * no client): enter at {@code air <= }{@link BotConfig#drownEscapeAirThreshold}
 * (100 — far below the idle float's 240, so a planned dive/crossing is not
 * preempted), release with WIDE hysteresis at {@code air >= }
 * {@link BotConfig#drownEscapeReleaseAir} (280) or head-out-and-recovering.
 * Gated on {@link BotConfig#autoDrownEscape} (default ON — it saves the bot's
 * life and touches nothing but its own vertical motion).
 *
 * <p>gap#72 lifecycle: the latch is the episode ({@link #episodePhase} =
 * {@code "FLOATING"}), reachable by {@code mc.bot.cancel} and the player-death
 * hook via {@link #cancelEpisode}; it holds no {@code BotProcess}, so
 * {@link #heldProcessKind} keeps the default null. Note a cancel while still
 * underwater+critical re-arms next tick by design (same contract as a cancelled
 * BunkerChain under an ongoing siege): {@link Chain#cancelEpisode} restores
 * "fresh instance" state, not immunity.
 */
public final class DrownEscapeChain implements Chain {

    /** Chain name — also the {@code mc.bot.cancel{process:...}} key and the
     *  {@code BotApiImpl} guard that keeps {@code AutoSwim.tick}'s shore-steer
     *  off the channel while this chain floats (pure-vertical contract). */
    public static final String NAME = "drownEscape";

    /** The hysteresis latch — non-false IS the episode (see class doc). */
    private boolean latched;
    /** Air supply seen on the previous evaluation, for the head-out-and-
     *  recovering release leg. Sentinel -1 = no previous reading. */
    private int prevAir = -1;
    /** True while OUR tick() is holding client keys, so interrupt/cancel release
     *  exactly our hold and the release path never touches client classes on a
     *  dedicated server (where tick(mc=null) never actuates). */
    private boolean keysHeld;

    /** Headless-test sensor override ({@code null} in production): lets the
     *  dedicated-server arena feed the REAL FakePlayer's underwater/air readings
     *  through the real priority()/scheduler path without any client classes
     *  (same seam philosophy as {@link BunkerChain#anchorForTest}). */
    private BooleanSupplier underwaterForTest;
    private IntSupplier airForTest;

    @Override public String name() { return NAME; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (underwaterForTest != null && airForTest != null) {
            return updateLatch(underwaterForTest.getAsBoolean(), airForTest.getAsInt())
                    ? Priorities.DROWN_ESCAPE : 0f;
        }
        if (mc == null || mc.player == null || !mc.player.isAlive()) {
            resetEpisodeState();
            return 0f;
        }
        LocalPlayer p = mc.player;
        return updateLatch(p.isUnderWater(), p.getAirSupply()) ? Priorities.DROWN_ESCAPE : 0f;
    }

    /** One pure latch transition (server-safe, matrix/arena-testable): feed this
     *  tick's readings, get the new latch. Public for the same cross-module
     *  GameTest reason as {@link BunkerChain#resetEpisodeState()}. */
    public boolean updateLatch(boolean underwater, int air) {
        boolean was = latched;
        latched = DrownEscapeGate.next(latched, underwater, air,
                prevAir < 0 ? air : prevAir,
                BotConfig.drownEscapeAirThreshold, BotConfig.drownEscapeReleaseAir,
                BotConfig.autoDrownEscape);
        prevAir = air;
        if (latched != was) {
            LOG.info("[drownEscape] {} (air={} underwater={} enter<={} release>={})",
                    latched ? "PREEMPT — floating straight up" : "released",
                    air, underwater, BotConfig.drownEscapeAirThreshold, BotConfig.drownEscapeReleaseAir);
        }
        return latched;
    }

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        if (mc == null) return;                     // headless arena: decision-layer only
        LocalPlayer p = mc.player;
        if (p == null) return;
        // PURE VERTICAL: hold jump, actively zero every horizontal/turn input the
        // preempted process may have left pressed (mirrors AutoSwim's deep-ascent
        // discipline). Yaw/pitch are left untouched — zero turning.
        mc.options.keyJump.setDown(true);
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);         // a held sneak SINKS the bot (aiStep sink)
        keysHeld = true;
        // Sealed lid: the cell two above the foot (the bunker roof-seal cell) is
        // solid while we're trying to rise — break it (allowBreak permitting).
        // The eye cell itself is AntiSuffocate's job; this is one above it.
        BlockPos lid = p.blockPosition().above(2);
        boolean breaking = false;
        // Collision-shape test, NOT isSolid: mangrove roots (death #9's ceiling)
        // are a non-full block — isSolid misses them, so the float pinned the bot
        // under an unbreakable-in-practice lid while air ran to −17.
        boolean lidBlocksRise = mc.level != null
                && !mc.level.getBlockState(lid).getCollisionShape(mc.level, lid).isEmpty();
        if (BotConfig.allowBreak && lidBlocksRise
                && mc.level.getBlockState(lid).getDestroySpeed(mc.level, lid) >= 0f) {
            selectBestToolFor(mc, lid);
            aimAtBlockSnap(p, lid);
            mc.options.keyAttack.setDown(true);
            breaking = true;
        }
        if (!breaking) mc.options.keyAttack.setDown(false);
    }

    @Override public void onInterrupt(Chain by) { releaseHeldKeys(); }

    @Override public String episodePhase() { return latched ? "FLOATING" : null; }

    @Override public void cancelEpisode(String reason) {
        resetEpisodeState();
        releaseHeldKeys();
    }

    /** Pure episode-state reset (server-safe, matrix-testable) — the client
     *  key-release half stays in {@link #releaseHeldKeys()}, same split as
     *  {@link BunkerChain#resetEpisodeState()}. */
    public void resetEpisodeState() {
        latched = false;
        prevAir = -1;
    }

    /** Test seam ({@link BunkerChain#anchorForTest} precedent): route priority()
     *  readings through the given suppliers instead of {@code mc.player}, so the
     *  dedicated-server arena can drive the real chain+scheduler. */
    public void sensorForTest(BooleanSupplier underwater, IntSupplier air) {
        this.underwaterForTest = underwater;
        this.airForTest = air;
    }

    /** Release exactly the keys OUR tick() pressed. Guarded on {@link #keysHeld}
     *  so a dedicated GameTest server (where tick(mc=null) never actuates) never
     *  resolves a client class here. */
    private void releaseHeldKeys() {
        if (!keysHeld) return;
        keysHeld = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        mc.options.keyJump.setDown(false);
        mc.options.keyAttack.setDown(false);
    }
}

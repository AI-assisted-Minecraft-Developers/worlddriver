package net.magicterra.worlddriver.bot.scheduler;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.auto.DrownEscapeGate;
import net.magicterra.worlddriver.bot.movement.BotInput;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.util.BotInteract.aimAtBlockSnap;
import static net.magicterra.worlddriver.bot.util.BotInteract.pickFaceTowardsPlayer;
import static net.magicterra.worlddriver.bot.util.BotInteract.selectBestToolFor;

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
    /** Throttle counter for the capped-lateral-escape debug line. */
    private int dbg;

    /** How far UP a column is scanned to decide "solid cap vs open surface" and to
     *  find an air surface in a neighbour. A few blocks is enough — the overhang
     *  shelf that drowned the bot (live death #27) sat one block above its head. */
    private static final int SURFACE_SCAN_UP = 4;
    /** Chebyshev-ring radius scanned for a neighbouring column the bot can surface
     *  in. Bounded by one breath of underwater swim (~5 s ≈ a handful of blocks);
     *  beyond that no lateral escape completes anyway. */
    private static final int LATERAL_SCAN_R = 5;

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
        // OVERHANG LATERAL ESCAPE (live death #27, 2026-07-21 flooded Mountains
        // channel). Pure-vertical float assumes "up = air". When the bot is under a
        // SOLID cap — an undercut cliff shelf, where the lake tunnels beneath several
        // blocks of stone — floating up is blocked and breaking straight through the
        // lid can't chew a stone block underwater within one breath (~180 t bare-hand
        // vs the ~100 t of air we enter at). The bot drowned in a 1×1 capped pocket
        // ONE block from open water, because this reflex's no-horizontal contract
        // forbade the lateral swim and its preempt had locked out AutoSwim's
        // shore-steer. So: when THIS column cannot surface but an adjacent one can,
        // swim toward it (buoyant, bounded) — the sanctioned survival exception, same
        // class as PanicChain's sprint and AutoSwim's beach. Deep open water is NOT
        // capped (cappedColumn scans for a solid, not merely far water), so ordinary
        // dives still get the pure-vertical float below.
        if (w != null && p.isUnderWater()) {
            int bx = (int) Math.floor(p.getX());
            int by = (int) Math.floor(p.getY());
            int bz = (int) Math.floor(p.getZ());
            int[] dir = lateralEscapeDir(w, bx, by, bz);
            if (dir != null) {
                float yaw = (float) Math.toDegrees(Math.atan2(-(double) dir[0], (double) dir[1]));
                p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(0f);
                BotInput.jump(mc, true);            // stay buoyant crossing under the lid
                // Raw camera-frame forward: the yaw was just set at the open column, so "along
                // the body" IS "toward open water". commandForward also forces leftImpulse to 0,
                // which is what the keyDown/keyLeft/keyRight clears were for.
                BotInput.forward(mc, true);         // swim toward open water
                BotInput.sprint(mc, false);
                BotInput.sneak(mc, false);
                mc.options.keyAttack.setDown(false);
                keysHeld = true;
                if (BotConfig.walkerDebug && (dbg++ % 10 == 0))
                    LOG.info("[drownEscape] CAPPED lid — lateral swim to open water dir={},{} pos={},{},{} air={}",
                            dir[0], dir[1], bx, by, bz, p.getAirSupply());
                return;
            }
            // dir == null: deep/open water (float vertically below) or capped-but-boxed-in
            // (no open neighbour in range → fall through to vertical + lid-break, best
            // effort — MC is always escapable by breaking upward even if slow).
        }
        // PURE VERTICAL: hold jump, actively zero every horizontal/turn input the
        // preempted process may have left pressed (mirrors AutoSwim's deep-ascent
        // discipline). Yaw/pitch are left untouched — zero turning.
        //
        // This chain PREEMPTS an active process, so the zeroing must use the channel that
        // outranks the Walker's own per-tick command — BotInput.halt (commandMove(0,0)), not
        // forward(false). Clearing the four direction KEYS, which is what this block used to
        // do, never zeroed anything while a process was running: AvatarInput.tick overwrites
        // the impulses after vanilla's key pass, so the keys were the one input nobody read.
        // That is the same failure this class's own doc describes AutoSwim losing to.
        BotInput.jump(mc, true);
        BotInput.halt(mc);
        BotInput.sprint(mc, false);
        BotInput.sneak(mc, false);                  // a held sneak SINKS the bot (aiStep sink)
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
            // keyAttack ALONE breaks nothing on a driven client. Vanilla's
            // continueAttack → continueDestroyBlock is gated on mouseHandler.isMouseGrabbed(),
            // true only after a human clicks into the window — and MouseYield deliberately
            // refuses to grab it, so vanilla takes the other branch and calls stopDestroyBlock()
            // every tick instead. Measured 2026-08-04 (see Avatar#breakHold): 140 ticks aimed
            // dead-on at the block, destroyProgress pinned at exactly 0.0, grabbed=false.
            // The key still goes down because under a GRABBED mouse vanilla drives the identical
            // break and the two simply agree; the pipeline is driven directly for the case this
            // reflex actually runs in. A drowning body under a lid has one breath, and a break
            // that never starts spends all of it.
            if (mc.gameMode.continueDestroyBlock(lid, pickFaceTowardsPlayer(lid, p)))
                p.swing(InteractionHand.MAIN_HAND);   // armless digging is an anticheat signature
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

    /** Decision core for the overhang lateral escape (live death #27) — pure and
     *  {@link WorldView}-only, so the headless matrix test drives the exact logic
     *  with no client (same seam philosophy as {@link DrownEscapeGate}). Returns
     *  {dx,dz} toward the nearest column the bot can surface in WHEN the current
     *  column is capped by a solid lid, else null: deep/open water (caller floats
     *  pure-vertical) or capped-but-boxed-in with no open neighbour in range
     *  (caller falls back to lid-break). */
    public static int[] lateralEscapeDir(WorldView w, int bx, int by, int bz) {
        if (!cappedColumn(w, bx, by, bz)) return null;
        return nearestBreathable(w, bx, by, bz);
    }

    /** True iff a SOLID cap blocks this column's ascent to air: scanning up from
     *  just above the foot, the first non-water cell is solid (or a hazard), not
     *  open air. Deep water (all water within the scan) is deliberately NOT capped —
     *  floating up still reaches the surface, which is the pure-vertical case. */
    private static boolean cappedColumn(WorldView w, int x, int by, int z) {
        for (int y = by + 1; y <= by + SURFACE_SCAN_UP; y++) {
            BlockPos c = new BlockPos(x, y, z);
            if (w.isWater(c)) continue;                 // still submerged: keep looking up
            return !(w.isPassable(c) && !w.isHazard(c)); // first non-water: air ⇒ open, solid ⇒ capped
        }
        return false;                                    // all water up the scan: deep, not capped
    }

    /** True iff column (x,z) has a reachable open-air surface within the scan — the
     *  first non-water cell scanning up from the bot's foot level is passable air.
     *  Used to pick a lateral escape target the bot can actually breathe in. */
    private static boolean breathableColumn(WorldView w, int x, int by, int z) {
        for (int y = by; y <= by + SURFACE_SCAN_UP; y++) {
            BlockPos c = new BlockPos(x, y, z);
            if (w.isWater(c)) continue;
            return w.isPassable(c) && !w.isHazard(c);
        }
        return false;
    }

    /** Nearest horizontal neighbour column (Chebyshev rings, nearest first) the bot
     *  can surface in. Returns {dx,dz} toward it, or null within {@link #LATERAL_SCAN_R}. */
    private static int[] nearestBreathable(WorldView w, int bx, int by, int bz) {
        for (int r = 1; r <= LATERAL_SCAN_R; r++) {
            int bestD = Integer.MAX_VALUE, bdx = 0, bdz = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // this ring only
                    if (breathableColumn(w, bx + dx, by, bz + dz)) {
                        int d = dx * dx + dz * dz;
                        if (d < bestD) { bestD = d; bdx = dx; bdz = dz; }
                    }
                }
            }
            if (bestD != Integer.MAX_VALUE) return new int[]{bdx, bdz};
        }
        return null;
    }

    /** Release exactly what OUR tick() drove. Guarded on {@link #keysHeld}
     *  so a dedicated GameTest server (where tick(mc=null) never actuates) never
     *  resolves a client class here.
     *
     *  <p>The movement half self-releases — {@code BotInput}'s commands are per-tick and an
     *  uncommanded tick falls back to the real keybind — so the explicit jump(false) below is
     *  only belt-and-braces for the one tick between interrupt and the next scheduler pass.
     *  {@code keyAttack} is a genuinely LATCHED keybind and its release is load-bearing. */
    private void releaseHeldKeys() {
        if (!keysHeld) return;
        keysHeld = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        BotInput.jump(mc, false);
        mc.options.keyAttack.setDown(false);
    }
}

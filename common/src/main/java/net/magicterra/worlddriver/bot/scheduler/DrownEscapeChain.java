package net.magicterra.worlddriver.bot.scheduler;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.auto.DrownEscapeGate;
import net.magicterra.worlddriver.bot.movement.BotInput;
import net.magicterra.worlddriver.bot.movement.WalkerGeometry;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.util.BotInteract.aimAtBlockSnap;
import static net.magicterra.worlddriver.bot.util.BotInteract.continueDestroy;
import static net.magicterra.worlddriver.bot.util.BotInteract.drownVerticalRow;
import static net.magicterra.worlddriver.bot.util.BotInteract.riseBlockedCell;
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
 * drew: a survival float is a reflex, not autonomous movement). If something is
 * in the way of the rise — asked by sweeping the body's own box up, see
 * {@code BotInteract#riseBlockedCell}, NOT by naming one cell above one column — break it,
 * {@code allowBreak} permitting, in the {@link BunkerChain} aim+attack style
 * ({@code AntiSuffocate} owns the eye-cell case; the lid here is above it).
 *
 * <p>The one horizontal exception to「zero horizontal」, and it is smaller than the
 * lateral arm's: when the rise is blocked but the body's own column is clear to
 * air, the body drifts to that column's CENTRE. A player box is 0.6 wide, so a
 * body pressed against a cell boundary carries 0.3 of itself into the next
 * column, and one solid cell there pins it — while {@code halt}, the very thing
 * this arm presses to stay passive, holds that pose. Measured 2026-08-23
 * (`wd.drownEscapeClientPinnedByNeighbourColumn`): 200 ticks, 0.000 blocks
 * gained, with the scan reporting a clear path the whole time.
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
    /** Throttle counter for {@code BotInteract#drownVerticalRow}. Deliberately NOT shared with {@link #dbg}: one
     *  counter across two mutually-exclusive arms lets a run of lateral ticks advance the vertical
     *  arm's phase, so the vertical rows would land on an arbitrary subset of ticks instead of
     *  every tenth of its own. Two arms, two clocks. */
    private int dbgV;

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
                // UNCONDITIONAL, throttled — deliberately the same gate its vertical sibling
                // (`dbgV++ % 10 == 0`, no flag) has always had. This row used to be behind
                // `walkerDebug`, and the asymmetry cost a run: the ladder of 2026-08-23 latched
                // this chain at 00:56:21 and the body drowned 15 s later at an unchanged
                // 76,42,60 — 300 ticks in which the ONLY thing the log said was the PREEMPT
                // line. Not one vertical row printed, which is itself how we know it was THIS
                // arm (the only path that returns before the vertical one), and this arm said
                // nothing at all. A reflex that outranks every process and holds the channel is
                // the last thing that should go quiet while it holds it.
                //
                // The horizontal speed is on the row because it is the whole question here: this
                // arm holds `forward` and claims to swim, and a body that held forward for 300
                // ticks without moving reads identically to one that was never asked. Bounded by
                // the drowning episode itself, so the volume is ten rows per near-death.
                if (dbg++ % 10 == 0)
                    LOG.info("[drownEscape] CAPPED lid — lateral swim to open water dir={},{} "
                                    + "pos={},{},{} air={} 水平速度={} y={}",
                            dir[0], dir[1], bx, by, bz, p.getAirSupply(),
                            String.format(java.util.Locale.ROOT, "%.4f",
                                    Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z)),
                            String.format(java.util.Locale.ROOT, "%.3f", p.getY()));
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
        BotInput.sprint(mc, false);
        BotInput.sneak(mc, false);                  // a held sneak SINKS the bot (aiStep sink)
        keysHeld = true;
        // Sealed lid: whatever stops the rise, break it (allowBreak permitting). The eye cell
        // itself is AntiSuffocate's job; this is above it.
        //
        // ASKED OF THE BODY'S OWN BOX, not of one column. This used to be
        // `p.blockPosition().above(2)` — the cell two above the foot — which is right only for a
        // body standing in the middle of its cell. A player box is 0.6 wide, so a body hard against
        // a boundary has up to 0.3 of itself in the NEXT column, and one solid cell there pins it
        // while this test, `cappedColumn` and `nearestBreathable` all report a clear path. Measured
        // 2026-08-23, wd.drownEscapeClientPinnedByNeighbourColumn:「跳读回=true 撞顶=true 盖挡=false」
        // — the command landed, physics said blocked, the scan said clear — 200 ticks, 0.000 blocks.
        // The live death it reproduces spent 261 ticks the same way.
        BlockPos lid = riseBlockedCell(mc, p, RISE_PROBE);
        boolean lidBlocksRise = lid != null;
        boolean breaking = false;
        // RECENTRE, before reaching for a pick. When the rise is blocked but the body's OWN column
        // is clear all the way to air, the obstruction is in a neighbour and the fix is ≤0.3 blocks
        // of drift, not a dig — a body pressed against a boundary can simply stop pressing.
        // `BotInput.halt` is what HELD that pose: the pin was being maintained by this very method.
        // Note the pure-vertical contract this bends is smaller than the lateral arm's, which swims
        // whole blocks: this never leaves the cell the body already stands in.
        if (lidBlocksRise && w != null
                && breathableColumn(w, Mth.floor(p.getX()), Mth.floor(p.getY()), Mth.floor(p.getZ()))) {
            double cx = Mth.floor(p.getX()) + 0.5, cz = Mth.floor(p.getZ()) + 0.5;
            double off = Math.sqrt((cx - p.getX()) * (cx - p.getX()) + (cz - p.getZ()) * (cz - p.getZ()));
            // Eased by the remaining offset: a full press across 0.2 blocks of water carries the
            // body to the OPPOSITE boundary, trading one pinning neighbour for the other one.
            BotInput.driveToward(mc, cx, cz, (float) Math.min(1.0, off * 5.0));
        } else {
            BotInput.halt(mc);
        }
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
            // Through BotInteract, not inline: naming MultiPlayerGameMode here puts a client class
            // in this chain's own bytecode, and this chain is constructed on a dedicated server by
            // the gate's matrix scenes. See BotInteract#continueDestroy.
            continueDestroy(mc, p, lid);
            breaking = true;
        }
        if (!breaking) mc.options.keyAttack.setDown(false);
        // UNCONDITIONAL — it used to be gated on walkerDebug, and that gate cost a whole gate slot.
        // 2026-08-23, stagewrightIntegratedServerNeoforge: both armed arms of the drown scenes came
        // back 净升 0.000 while the very same code passed on Fabric an hour earlier (2.291 / 2.320).
        // `[drownEscape] PREEMPT` printed twice, so the chain armed and took the body — but there
        // were ZERO 竖直支 rows to say what happened next, because gates do not run with
        // walkerDebug on. A reading available only under a flag is a reading the run that needs it
        // never takes, and the zero it leaves cannot be told apart from「the arm never ran」.
        // Same lesson, same day, as BotInteract's [place] row. The %10 throttle stays: this arm
        // only ticks while a body is actually drowning, so the volume is an episode, not a stream.
        // Through BotInteract for the same reason continueDestroy above goes through it: the row
        // reads p.input, which only LocalPlayer has, and a method DECLARED here with LocalPlayer in
        // its descriptor stops this class loading on a dedicated server. An invokestatic resolves
        // its owner, not its owner's dependencies. See blockedAbove's note for what that cost.
        if (dbgV++ % 10 == 0) drownVerticalRow(mc, p, lid, lidBlocksRise, breaking);
    }

    /** How far up the body's own box is swept to ask「这一升会不会撞上东西」. Half a block: far
     *  enough to see the face a rising body is about to meet (terminal rise in water is ~0.175 per
     *  tick), short enough that it never nominates something the body would have drifted clear of.
     *  A lid a whole block higher is not in the way YET, and this arm re-asks every tick. */
    private static final double RISE_PROBE = 0.5;

    /**
     * The cell that stops this body from rising, or null if nothing does.
     *
     * <p>The question every column scan in this class approximates, asked exactly: sweep the body's
     * OWN bounding box up by {@link #RISE_PROBE} and see what it hits. Right for a neighbouring
     * column, a slab, a stair, a lily pad and a mangrove root alike, because it asks the same
     * geometry vanilla's own collision does rather than re-deriving it from one {@code BlockPos}.
     *
     * <p><b>The repo already knew this.</b> {@link WalkerGeometry#pillarRiseBlockers} was written
     * from the same 0.6-width measurement in 2026-08-19 for {@code TowerProcess}, and this arm
     * re-derived it — so this now delegates instead. Do not re-inline it: that helper's shape test
     * is {@code Shapes.joinIsNotEmpty}, which is exact where a {@code bounds()} test over-reports
     * every non-cubic block (a fence's bounds are a full cell, its collision is not).
     *
     * <p>The list comes back lowest-first and the lowest is what this arm wants: that is the face
     * actually bearing on the body.
     *
     * <p><b>BODY IN {@code BotInteract#riseBlockedCell}, and it must stay there.</b> The scan takes
     * a {@code Player}; this arm has a {@code LocalPlayer}. Handing one to the other is a WIDENING,
     * and a widening is what forces the verifier to LOAD {@code LocalPlayer} to prove the subtype
     * relation — which a dedicated server cannot do, and the gate's matrix scenes construct this
     * chain on one. Keeping the widening inside a client-only class costs nothing: an
     * {@code invokestatic} resolves its owner, and that owner is never loaded on a server because
     * {@code tick} returns at {@code mc == null} first.
     *
     * <p><b>Two wrong explanations were committed here first; both are retracted.</b> It is not
     * "the descriptor names a client class" (this class named {@code LocalPlayer} in descriptors
     * long before, and passed) and not "it calls into a client type" ({@code KeyMapping.setDown},
     * {@code ClientLevel.getBlockState}, {@code Minecraft.getInstance} and a {@code yHeadRot} write
     * were all here in the last green revision). Comparing {@code javap} output and git history
     * cleared every suspect and found nothing, because the cause was in none of them. What settled
     * it in seconds was one STACK — the scene's three matrices fenced separately so the throwable
     * could be printed. The lesson is the method, not just the answer: when a message names a
     * missing CLASS it is telling you what could not load, never who asked.
     */
    // (body moved — see above)

    /*
     * The execution-layer row for the pure-vertical arm — BODY IN BotInteract#drownVerticalRow.
     * Deliberately a plain comment, not javadoc: there is no member here to attach it to, and a
     * javadoc block with nothing under it silently documents whatever comes next.
     *
     * <p>Before this existed the arm printed <b>nothing</b> per tick, and the lid-break sub-arm
     * printed nothing ever. The log it left behind therefore could not tell apart the three ways a
     * body can hold jump in water and not move: the intent never reached {@code Input.jumping},
     * buoyancy applied but a collision face pinned the body, or the break arm ran and never
     * finished. Measured 2026-08-22 on the integrated ladder (BED rung, {@code -28,61,79}): 261
     * ticks holding jump, zero rise, air monotonically down to death — and the only in-window rows
     * in the whole log were the two scheduler handovers.
     *
     * <p>Every field separates exactly one candidate, so none of them is decoration:
     * <ul>
     *   <li>{@code 跳读回} is read back off the player's own {@code Input}, i.e. what
     *       {@link net.magicterra.worlddriver.bot.movement.AvatarInput#tick} actually left there on
     *       the previous tick — <b>not</b> what this class asked for. The command channel is
     *       last-writer-wins and heavily contended, so "we commanded it" is not the same claim.
     *       This said "nine callers"; a repo-wide {@code grep -rn "commandJump("} (minus the
     *       five plumbing lines in Avatar / AvatarInput / ClientPlayerAvatar / BotInput) returns
     *       thirty-odd writes across thirteen behaviour classes. Nine is the count for
     *       {@code AutoSwim} ALONE — one file was measured and reported as the whole. The
     *       argument survives (more contention, not less); the number is the part a reader
     *       would use to bound a race audit, so take it from the grep.</li>
     *   <li>{@code 撞顶} ({@code verticalCollision}) is the one-row proof of "buoyancy IS applying
     *       and something is in the way" — the state every column scan in this class is blind to.
     *       A body that is neither rising nor sinking is pinned, and only this field says so
     *       without arithmetic on two samples taken 200 ticks apart.</li>
     *   <li>{@code 身体跨柱} prints the cells the bounding box actually straddles at the lid's
     *       height, not the one cell {@code blockPosition()} names. A body at x=-27.716 has its box
     *       edge at -28.016 — 0.016 inside the NEXT column, which {@link #cappedColumn},
     *       {@link #nearestBreathable} and the {@code lid} cell all ignore.</li>
     *   <li>{@code 破盖中} makes the break arm visible at all.</li>
     * </ul>
     *
     * <p><b>The body lives in {@code BotInteract#drownVerticalRow}, not here.</b> It reads
     * {@code p.input}, which only {@code LocalPlayer} has, and a method DECLARED in this class with
     * {@code LocalPlayer} in its descriptor stops the class loading on a dedicated server — see
     * {@link #blockedAbove} for the measurement that established it.
     */

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

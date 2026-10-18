package net.magicterra.worlddriver.bot.arbitration;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import net.magicterra.worlddriver.config.ArbitrationConfig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.behavior.reflex.DrownEscapeGate;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.BodyGeometry;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import static net.magicterra.worlddriver.config.Mod.LOG;

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
 * in the way of the rise — asked by sweeping the player's own box up, see
 * {@link #blockedAbove}, NOT by naming one cell above one column — break it,
 * {@code allowBreak} permitting, in the {@link BunkerChain} aim+attack style
 * ({@code AntiSuffocate} owns the eye-cell case; the lid here is above it).
 *
 * <p>The lateral arm steers only along a route it has walked in the scan — see
 * {@link #lateralEscapeScan}. Until 2026-08-26 it steered at any column that could
 * surface within five cells, whether or not the bot could get there, and a bot that
 * cannot get there is a bot denied the lid-break as well.
 *
 * <p>The one horizontal exception to "zero horizontal", and it is smaller than the
 * lateral arm's: when the rise is blocked but the bot's own column is clear to
 * air, the bot drifts to that column's CENTRE. A player box is 0.6 wide, so a
 * player pressed against a cell boundary carries 0.3 of itself into the next
 * column, and one solid cell there pins it — while {@code halt}, the very thing
 * this arm presses to stay passive, holds that pose. Measured 2026-08-23
 * (`wd.drownEscapeClientPinnedByNeighbourColumn`): 200 ticks, 0.000 blocks
 * gained, with the scan reporting a clear path the whole time.
 *
 * <p>Entry/release live in the pure {@link DrownEscapeGate} (matrix-tested with
 * no client): enter at {@code air <= }{@link ControlConfig#drownEscapeAirThreshold}
 * (100 — far below the idle float's 240, so a planned dive/crossing is not
 * preempted), release with WIDE hysteresis at {@code air >= }
 * {@link ArbitrationConfig#drownEscapeReleaseAir} (280) or head-out-and-recovering.
 * Gated on {@link ArbitrationConfig#autoDrownEscape} (default ON — it saves the bot's
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
     *  {@code BotBrain} guard that keeps {@code AutoSwim.tick}'s shore-steer
     *  off the channel while this chain floats (pure-vertical contract). */
    public static final String NAME = "drownEscape";

    /** The hysteresis latch — non-false IS the episode (see class doc). */
    private boolean latched;
    /** Air supply seen on the previous evaluation, for the head-out-and-
     *  recovering release condition. Sentinel -1 = no previous reading. */
    private int prevAir = -1;
    /** True while OUR tick() is holding the body's jump or dig, so interrupt/cancel release
     *  exactly our hold. */
    private boolean keysHeld;
    /** Throttle counter for the capped-lateral-escape debug line. */
    private int dbg;
    /** Throttle counter for {@link #verticalRow}. Deliberately NOT shared with {@link #dbg}: one
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

    @Override public float priority(Body body, WorldView w, BotState st) {
        if (underwaterForTest != null && airForTest != null) {
            return updateLatch(underwaterForTest.getAsBoolean(), airForTest.getAsInt())
                    ? Priorities.DROWN_ESCAPE : 0f;
        }
        LivingEntity p = body == null ? null : body.entity();
        if (p == null || !p.isAlive()) {
            resetEpisodeState();
            return 0f;
        }
        return updateLatch(p.isUnderWater(), p.getAirSupply()) ? Priorities.DROWN_ESCAPE : 0f;
    }

    /** One pure latch transition (server-safe, matrix/arena-testable): feed this
     *  tick's readings, get the new latch. Public for the same cross-module
     *  GameTest reason as {@link BunkerChain#resetEpisodeState()}. */
    public boolean updateLatch(boolean underwater, int air) {
        boolean was = latched;
        latched = DrownEscapeGate.next(latched, underwater, air,
                prevAir < 0 ? air : prevAir,
                ControlConfig.drownEscapeAirThreshold, ArbitrationConfig.drownEscapeReleaseAir,
                ArbitrationConfig.autoDrownEscape);
        prevAir = air;
        if (latched != was) {
            LOG.info("[drownEscape] {} (air={} underwater={} enter<={} release>={})",
                    latched ? "PREEMPT — floating straight up" : "released",
                    air, underwater, ControlConfig.drownEscapeAirThreshold, ArbitrationConfig.drownEscapeReleaseAir);
        }
        return latched;
    }

    @Override public void tick(Body body, WorldView w, BotState st) {
        LivingEntity p = body == null ? null : body.entity();
        if (p == null) return;                      // headless arena: decision-layer only
        BlockInteraction blocks = body.blockInteraction().orElse(null);
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
            LateralEscape lateral = lateralEscapeScan(w, bx, by, bz);
            int[] dir = lateral.step();
            if (dir == null && lateral.unreachable() != null && dbgV % 10 == 0)
                // Same %10 window as the vertical row below (read, never incremented here — the
                // two are one arm and must land on the same ticks, unlike the lateral row's own
                // clock). This is the row whose absence sent a reader to the region file.
                LOG.info("[drownEscape] CAPPED lid — breathable column {} exists but cannot be reached "
                                + "by swimming (no route within {}), falling back to breaking the lid pos={},{},{}",
                        lateral.unreachable().toShortString(), LATERAL_SCAN_R, bx, by, bz);
            if (dir != null) {
                float yaw = (float) Math.toDegrees(Math.atan2(-(double) dir[0], (double) dir[1]));
                p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(0f);
                body.commandJump(true);             // stay buoyant crossing under the lid
                // Raw camera-frame forward: the yaw was just set at the open column, so "along
                // the player" IS "toward open water". commandForward also forces leftImpulse to 0,
                // which is what the keyDown/keyLeft/keyRight clears were for.
                body.commandForward(1f);            // swim toward open water
                body.commandSprint(false);
                body.commandSneak(false);
                if (blocks != null) blocks.breakHold(false);
                keysHeld = true;
                // UNCONDITIONAL, throttled — deliberately the same gate its vertical sibling
                // (`dbgV++ % 10 == 0`, no flag) has always had. This row used to be behind
                // `navigatorDebug`, and the asymmetry cost a run: the ladder of 2026-08-23 latched
                // this chain at 00:56:21 and the bot drowned 15 s later at an unchanged
                // 76,42,60 — 300 ticks in which the ONLY thing the log said was the PREEMPT
                // line. Not one vertical row printed, which is itself how we know it was THIS
                // arm (the only path that returns before the vertical one), and this arm said
                // nothing at all. A reflex that outranks every process and holds the channel is
                // the last thing that should go quiet while it holds it.
                //
                // The horizontal speed is on the row because it is the whole question here: this
                // arm holds `forward` and claims to swim, and a bot that held forward for 300
                // ticks without moving reads identically to one that was never asked. Bounded by
                // the drowning episode itself, so the volume is ten rows per near-death.
                if (dbg++ % 10 == 0)
                    // `firstStep` is the whole point of the field's name: a DESTINATION on this
                    // row cannot be told apart from a reachable one — a row reading `dir=0,-2`
                    // once named a column two cells away with stone in between. A first step is a
                    // cell the scan has already asserted the bot fits in, so a stalled bot on this
                    // row is a physics question, not a routing one.
                    LOG.info("[drownEscape] CAPPED lid — lateral swim to open water firstStep={},{} "
                                    + "pos={},{},{} air={} horizontalSpeed={} y={}",
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
        // outranks the Navigator's own per-tick command — commandMove(0,0), not
        // commandForward(0). Clearing the four direction KEYS, which is what this block used to
        // do, never zeroed anything while a process was running: AvatarInput.tick overwrites
        // the impulses after vanilla's key pass, so the keys were the one input nobody read.
        // That is the same failure this class's own doc describes AutoSwim losing to.
        body.commandJump(true);
        body.commandSprint(false);
        body.commandSneak(false);                   // a held sneak SINKS the bot (aiStep sink)
        keysHeld = true;
        // Sealed lid: whatever stops the rise, break it (allowBreak permitting). The eye cell
        // itself is AntiSuffocate's job; this is above it.
        //
        // ASKED OF THE BOT'S OWN BOX, not of one column. `p.blockPosition().above(2)` — the cell
        // two above the foot — is right only for a bot standing in the middle of its cell. A player
        // box is 0.6 wide, so a bot hard against a boundary has up to 0.3 of itself in the NEXT
        // column, and one solid cell there pins it while a single-column test, `cappedColumn` and
        // `nearestBreathable` all report a clear path. Measured 2026-08-23,
        // wd.drownEscapeClientPinnedByNeighbourColumn: jumpReadBack=true hitCeiling=true lidBlocks=false
        // — the command landed, physics said blocked, the scan said clear — 200 ticks, 0.000 blocks.
        // The live death it reproduces spent 261 ticks the same way.
        BlockPos lid = blockedAbove(p);
        boolean lidBlocksRise = lid != null;
        boolean breaking = false;
        // RECENTRE, before reaching for a pick. When the rise is blocked but the bot's OWN column
        // is clear all the way to air, the obstruction is in a neighbour and the fix is ≤0.3 blocks
        // of drift, not a dig — a bot pressed against a boundary can simply stop pressing.
        // `commandMove(0,0)` is what HELD that pose: the pin was being maintained by this very method.
        // Note the pure-vertical contract this bends is smaller than the lateral arm's, which swims
        // whole blocks: this never leaves the cell the bot already stands in.
        if (lidBlocksRise && w != null
                && breathableColumn(w, Mth.floor(p.getX()), Mth.floor(p.getY()), Mth.floor(p.getZ()))) {
            double cx = Mth.floor(p.getX()) + 0.5, cz = Mth.floor(p.getZ()) + 0.5;
            double off = Math.sqrt((cx - p.getX()) * (cx - p.getX()) + (cz - p.getZ()) * (cz - p.getZ()));
            // Eased by the remaining offset: a full press across 0.2 blocks of water carries the
            // bot to the OPPOSITE boundary, trading one pinning neighbour for the other one.
            body.commandToward(cx, cz, (float) Math.min(1.0, off * 5.0));
        } else {
            body.commandMove(0f, 0f);
        }
        if (WorldConfig.allowBreak && lidBlocksRise && blocks != null
                && p.level().getBlockState(lid).getDestroySpeed(p.level(), lid) >= 0f) {
            body.equipment().ifPresent(h -> h.selectTool(lid));
            body.aimAtBlock(lid);
            blocks.breakHold(true);
            // The drive is what breaks the lid on the exact cell, and on the client it also makes
            // vanilla's attack pass stand aside. A drowning bot under a lid has one breath, and a
            // break that never starts spends all of it.
            blocks.continueDestroy(lid);
            breaking = true;
        }
        if (!breaking && blocks != null) blocks.breakHold(false);
        // STAND UP TO DIG. Vanilla's Player#getDestroySpeed divides the rate by 5 when the player is
        // off the ground and by 5 AGAIN when its eyes are in water, and this arm was paying both:
        // the jump above is held every tick, so the player hovers instead of resting on whatever it
        // is standing over. Measured 2026-08-27 on the real client player
        // (wd.drownEscapeClientBreaksTheLidWhenOpenWaterIsWalledOff, integrated topology):
        // one dirt lid cost 380 ticks, the bot was on the ground for 3 of those 380 ticks, and the
        // floor was RIGHT THERE — stone underfoot, with the bot parked at y=208.235 over a floor
        // whose top is y=208.0.
        // 0.235 blocks of hover, and bare-hand dirt is ~15 ticks: 15 × 25 = 375.
        //
        // Releasing the jump drops those 0.235 blocks, `onGround` becomes true, and the same dig
        // costs a fifth. The number that makes this worth doing is the air budget: latching at
        // `drownEscapeAirThreshold` (100) buys roughly 300 ticks of life (100 air + 20 HP at 2 per
        // 20 ticks), so a 380-tick escape LOSES and a ~76-tick one wins with room to spare. The
        // navigator's own hopelessness gate has priced digs this way all along — Digging notes
        // "off-ground ÷5 always undone (the bot can always ground)" — while this reflex never
        // grounded. That gap is what the fix closes.
        //
        // GUARDED BY THE FLOOR, and the guard is geometry rather than caution. `RISE_PROBE` is 0.5
        // and it lifts the player's OWN box: feet at y, box top y+1.8, probe reaching y+2.3 — so in a
        // two-tall pocket a GROUNDED player still finds the lid, and the release costs nothing. In a
        // deeper pocket the player would sink away from the lid, `blockedAbove` would return null,
        // the jump would go straight back on, and the pair would oscillate with the break progress
        // reset every cycle. Asking whether the cell under the feet can be stood on separates the
        // pocket this helps from the pocket it would wreck.
        if (breaking && w != null) {
            BlockPos below = new BlockPos(Mth.floor(p.getX()), Mth.floor(p.getY()) - 1,
                    Mth.floor(p.getZ()));
            if (!cellOpen(w, below)) body.commandJump(false);
        }
        // UNCONDITIONAL, not gated on navigatorDebug: gates do not run with navigatorDebug on. On
        // stagewrightIntegratedServerNeoforge both armed arms of the drown scenes reported a net rise
        // of 0.000 while the same code passed on Fabric (2.291 / 2.320); `[drownEscape] PREEMPT`
        // printed twice, so the chain armed and took control of the bot, but a debug-gated vertical
        // arm row would have printed nothing about what happened next. A reading available only under
        // a flag is a reading the run that needs it never takes, and the zero it leaves cannot be told
        // apart from "the arm never ran".
        // Same lesson, same day, as BotInteract's [place] row. The %10 throttle stays: this arm
        // only ticks while the bot is actually drowning, so the volume is an episode, not a stream.
        if (dbgV++ % 10 == 0) verticalRow(body, p, lid, lidBlocksRise, breaking);
    }

    /** How far up the bot's own box is swept to ask "will this rise hit something". Half a block: far
     *  enough to see the face a rising player is about to meet (terminal rise in water is ~0.175 per
     *  tick), short enough that it never nominates something the player would have drifted clear of.
     *  A lid a whole block higher is not in the way YET, and this arm re-asks every tick. */
    private static final double RISE_PROBE = 0.5;

    /**
     * The cell that stops this player from rising, or null if nothing does.
     *
     * <p>The question every column scan in this class approximates, asked exactly: sweep the player's
     * OWN bounding box up by {@link #RISE_PROBE} and see what it hits. Right for a neighbouring
     * column, a slab, a stair, a lily pad and a mangrove root alike, because it asks the same
     * geometry vanilla's own collision does rather than re-deriving it from one {@code BlockPos}.
     *
     * <p><b>The repo already knew this.</b> {@link BodyGeometry#pillarRiseBlockers} was written
     * from the same 0.6-width measurement in 2026-08-19 for {@code TowerProcess}, and this arm
     * re-derived it — so this now delegates instead. Do not re-inline it: that helper's shape test
     * is {@code Shapes.joinIsNotEmpty}, which is exact where a {@code bounds()} test over-reports
     * every non-cubic block (a fence's bounds are a full cell, its collision is not).
     *
     * <p>The list comes back lowest-first and the lowest is what this arm wants: that is the face
     * actually bearing on the player.
     *
     * <p>This used to live in {@code BotInteract}, because the arm held the client's
     * {@code LocalPlayer} and handing it to the scan's wider parameter made the verifier load that
     * class on a dedicated server. The chain now holds only the body's own entity type, so there is
     * no client type here to widen.
     */
    private static BlockPos blockedAbove(LivingEntity p) {
        List<BlockPos> hits = BodyGeometry.riseBlockers(p, RISE_PROBE);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * The execution-layer row for the pure-vertical arm.
     *
     * <p>Before this existed the arm printed <b>nothing</b> per tick, and the lid-break sub-arm
     * printed nothing ever. The log it left behind therefore could not tell apart the three ways a
     * bot can hold jump in water and not move: the intent never reached {@code Input.jumping},
     * buoyancy applied but a collision face pinned the player, or the break arm ran and never
     * finished. Measured 2026-08-22 on the integrated ladder (BED rung, {@code -28,61,79}): 261
     * ticks holding jump, zero rise, air monotonically down to death — and the only in-window rows
     * in the whole log were the two scheduler handovers.
     *
     * <p>Every field separates exactly one candidate, so none of them is decoration:
     * <ul>
     *   <li>{@code jumpReadBack} is read back off the body's own input ({@link Body#dbgJumping()}),
     *       i.e. what the client's {@code AvatarInput#tick} actually left there on the previous
     *       tick — <b>not</b> what this class asked for. The command channel is
     *       last-writer-wins and heavily contended, so "we commanded it" is not the same claim.
     *       This said "nine callers", which is {@code AutoSwim}'s count ALONE — one file measured
     *       and reported as the whole. A repo-wide {@code grep -rn "commandJump(" common/src/main}
     *       returned 41 lines on 2026-08-26, five of them plumbing (the declaration in
     *       {@code Body}, the impl in {@code AvatarInput}, the since-retired {@code BotInput}
     *       forwarder, and the {@code ClientPlayerBody} / {@code ServerPlayerBody} overrides), leaving
     *       ~36 writes across 14 behaviour classes. The argument survives either way (more
     *       contention, not less); the NUMBER is what a reader would use to bound a race audit,
     *       and it has already drifted once since being corrected here, so re-run the grep. ⚠️ The
     *       identical wrong nine also sat in the client-side copy of this row that has since been
     *       folded in here — two copies from one memory,
     *       which is why the fix is a derivation rather than a better number.</li>
     *   <li>{@code hitCeiling} ({@code verticalCollision}) is the one-row proof of "buoyancy IS
     *       applying and something is in the way" — the state every column scan in this class is
     *       blind to. A bot that is neither rising nor sinking is pinned, and only this field says
     *       so without arithmetic on two samples taken 200 ticks apart.</li>
     *   <li>{@code straddledColumns} prints the cells the bounding box actually straddles at the
     *       lid's height, not the one cell {@code blockPosition()} names. A bot at x=-27.716 has its
     *       box edge at -28.016 — 0.016 inside the NEXT column, which {@link #cappedColumn},
     *       {@link #nearestBreathable} and the {@code lid} cell all ignore.</li>
     *   <li>{@code breakingLid} makes the break arm visible at all.</li>
     * </ul>
     *
     * <p>Unconditional by design: gates never set {@code navigatorDebug}, so a flag here would mean no
     * rows in exactly the runs that need them. The {@code %10} throttle lives at the call site.
     */
    private static void verticalRow(Body body, LivingEntity p, BlockPos lid,
                                    boolean lidBlocksRise, boolean breaking) {
        Level level = p.level();
        AABB box = p.getBoundingBox();
        // With nothing in the way `lid` is null, and the row still has to say WHICH cells were
        // looked at — a reader diagnosing "did not rise" needs the neighbours named on the clear ticks too,
        // otherwise the interesting rows have no baseline to differ from.
        int scanY = lid != null ? lid.getY() : p.blockPosition().getY() + 2;
        StringBuilder straddled = new StringBuilder();
        // maxX/maxZ are EXCLUSIVE edges: a box ending exactly on a boundary does not occupy the
        // next cell, and floor(maxX) would name one it never touches. Vanilla's own convention.
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1.0E-7); z++) {
                BlockPos c = new BlockPos(x, scanY, z);
                BlockState bs = level.getBlockState(c);
                if (straddled.length() > 0) straddled.append(", ");
                straddled.append(c.toShortString()).append('=')
                        .append(BuiltInRegistries.BLOCK.getKey(bs.getBlock()))
                        .append(bs.getCollisionShape(level, c).isEmpty() ? "" : "(solid)");
            }
        }
        LOG.info("[drownEscape] vertical arm y={} fallSpeed={} jumpReadBack={} onGround={} hitCeiling={} inWater={} "
                        + "underWater={} waterHeight={} air={} lidCell={} lidBlocks={} breakingLid={} straddledColumns={}",
                String.format(Locale.ROOT, "%.3f", p.getY()),
                String.format(Locale.ROOT, "%.4f", p.getDeltaMovement().y),
                body.dbgJumping(), p.onGround(), p.verticalCollision,
                p.isInWater(), p.isUnderWater(),
                String.format(Locale.ROOT, "%.3f", p.getFluidHeight(FluidTags.WATER)),
                p.getAirSupply(), lid == null ? "none (the way up is clear)" : lid.toShortString(),
                lidBlocksRise, breaking, straddled);
    }

    @Override public void onInterrupt(Body body, Chain by) { releaseHeldKeys(body); }

    @Override public String episodePhase() { return latched ? "FLOATING" : null; }

    @Override public void cancelEpisode(Body body, String reason) {
        resetEpisodeState();
        releaseHeldKeys(body);
    }

    /** Pure episode-state reset (server-safe, matrix-testable) — the client
     *  key-release half stays in {@link #releaseHeldKeys(Body)}, same split as
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
        return lateralEscapeScan(w, bx, by, bz).step();
    }

    /**
     * What the lateral scan found — {@link #lateralEscapeDir} is this, minus the half that
     * only the log wants.
     *
     * @param step        {dx,dz} of the FIRST step of a route to open water, or null if there
     *                    is none. Always one cell and always axis-aligned: a 0.6-wide player does
     *                    not fit through the diagonal gap between two solid cells, so a diagonal
     *                    "step" is a direction no player can travel.
     * @param unreachable a column that CAN surface, within range, that no route reaches — or
     *                    null when the scan found nothing at all. Both fields null is "boxed in,
     *                    nothing anywhere"; this one set is "open water is right there, behind
     *                    rock". The caller falls back to the lid-break either way, but a human
     *                    reading a death needs to tell them apart, and until 2026-08-26 could
     *                    not: the answer had to be dug out of the saved region file.
     */
    public record LateralEscape(int[] step, BlockPos unreachable) { }

    private static final LateralEscape NO_LATERAL = new LateralEscape(null, null);

    /** Four-way steps. See {@link LateralEscape#step} for why no diagonals. */
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * The capped-column lateral decision, in full.
     *
     * <p><b>A route, not a radius.</b> This used to be "scan outward in Chebyshev rings, take the
     * first column that can surface" — a test on the DESTINATION alone, with nothing anywhere
     * asking whether the bot can get there. It killed the ladder of 2026-08-26 at rung 9: the bot in
     * a 1×1 pocket at 81,59,82 under a dirt lid; the scan picked 81,59,80, which genuinely can
     * surface; 81,59,81, the one cell between them, is stone. The bot held {@code forward} into
     * that stone for 532 ticks at a horizontal speed of exactly 0.0000 and drowned. The picked
     * column was not even standable — its head cell 81,60,80 is stone too, so "can surface there"
     * was answered for a player that could never have been there.
     *
     * <p>Worse than useless: {@code step == null} is what sends the caller to the lid-break, the
     * arm the class doc calls always-escapable. A false-positive lateral target does not merely
     * fail to help — it withholds the fallback. See {@code a-fix-that-cannot-reach-its-own-occasion}.
     *
     * <p>So: breadth-first over the bot's own level, four-way, bounded to
     * {@link #LATERAL_SCAN_R}, through cells the PLAYER fits in — foot AND head, water or passable,
     * non-hazard. Asking only the foot is how a one-block-high crack reads as a corridor.
     *
     * <p>Deliberately 2-D. A route that needs the bot to change level is a swim this reflex does
     * not steer (it holds jump the whole time), so admitting one would put a target behind a
     * manoeuvre the arm cannot perform — the same class of promise this method was just fixed for.
     */
    public static LateralEscape lateralEscapeScan(WorldView w, int bx, int by, int bz) {
        if (!cappedColumn(w, bx, by, bz)) return NO_LATERAL;

        final int span = 2 * LATERAL_SCAN_R + 1, o = LATERAL_SCAN_R;
        boolean[][] seen = new boolean[span][span];
        int[][][] firstStep = new int[span][span][];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        seen[o][o] = true;
        queue.add(new int[]{0, 0});
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            for (int[] s : STEPS) {
                int dx = cur[0] + s[0], dz = cur[1] + s[1];
                if (Math.max(Math.abs(dx), Math.abs(dz)) > LATERAL_SCAN_R) continue;
                if (seen[dx + o][dz + o]) continue;
                seen[dx + o][dz + o] = true;
                if (!bodyFits(w, bx + dx, by, bz + dz)) continue;   // the cell the old scan never asked about
                // The first step of the route that reached here: our own step when we came
                // straight off the bot's cell, otherwise whatever got us to the cell we came from.
                int[] step = (cur[0] == 0 && cur[1] == 0) ? s : firstStep[cur[0] + o][cur[1] + o];
                firstStep[dx + o][dz + o] = step;
                if (breathableColumn(w, bx + dx, by, bz + dz))
                    return new LateralEscape(step, null);
                queue.add(new int[]{dx, dz});
            }
        }
        return new LateralEscape(null, firstBreathableInRange(w, bx, by, bz));
    }

    /** A cell this PLAYER can occupy at this level: foot and head both open and neither a hazard.
     *  Two cells, because the player is two tall — see {@code the-collision-box-is-not-the-cell}. */
    private static boolean bodyFits(WorldView w, int x, int y, int z) {
        return cellOpen(w, new BlockPos(x, y, z)) && cellOpen(w, new BlockPos(x, y + 1, z));
    }

    private static boolean cellOpen(WorldView w, BlockPos c) {
        return !w.isHazard(c) && (w.isWater(c) || w.isPassable(c));
    }

    /** The old scan, kept for exactly one purpose: naming the column the bot could see and not
     *  reach, so a death record says which of the two dead ends it was. Never steers anything. */
    private static BlockPos firstBreathableInRange(WorldView w, int bx, int by, int bz) {
        for (int r = 1; r <= LATERAL_SCAN_R; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (breathableColumn(w, bx + dx, by, bz + dz))
                        return new BlockPos(bx + dx, by, bz + dz);
                }
        return null;
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

    /** Release exactly what OUR tick() drove, on the body it drove. Guarded on {@link #keysHeld}
     *  so an episode that never actuated releases nothing.
     *
     *  <p>The movement half self-releases — the body's commands are per-tick and an
     *  uncommanded tick falls back to the real keybind — so the explicit jump(false) below is
     *  only belt-and-braces for the one tick between interrupt and the next scheduler pass.
     *  The dig latch is genuinely LATCHED and its release is load-bearing. */
    private void releaseHeldKeys(Body body) {
        if (!keysHeld) return;
        keysHeld = false;
        if (body == null) return;
        body.commandJump(false);
        body.blockInteraction().ifPresent(b -> b.breakHold(false));
    }
}

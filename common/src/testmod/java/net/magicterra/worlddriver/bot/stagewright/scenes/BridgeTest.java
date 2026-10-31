package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.NavigatorReading;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.PlanCursor;
import net.magicterra.worlddriver.bot.stagewright.kit.NavWalk;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Single-width plank-bridge battery: a systematic sweep of 1-wide elevated walkways — flat runs, hurdles, bypass
 * routing, dead ends, stairs, broken descents, foothold placement and dig-vs-detour economics. The family-wide contract
 * is <b>"traverse (or stop) WITHOUT falling off"</b>: every test tracks the player's minimum Y through the whole run
 * and fails if it ever dropped below the lowest legitimate deck level, and every rig hangs a catch floor 40 blocks
 * under the deck so a player that falls off lands inside the plot instead of leaving it (the catch floor is itself a
 * failure detector, not a route).
 *
 * <p>Dedicated-topology caveats (same class as the documented DIG-hold / GEAR-degraded exemptions in
 * docs/dev/coverage-exemptions.md):
 *
 * <ul>
 *   <li><b>Fall damage:</b> the bots' server-side players are damage-immune by contract, so the "damaging drop" test
 *       asserts ROUTING+traversal (the planner accepts a maxDryFall-cap drop), not HP loss — live damage is ~1 HP at
 *       drop 4.
 *   <li><b>MLG water-bucket fall:</b> {@code canWaterBucketFall()} is only wired on {@code ClientWorldView}
 *       (bucketFallReady) and the {@code ClutchController} is ticked from the client tick — a dedicated-server
 *       LevelWorldView can never emit or actuate {@code fallBucket*}. The lethal-gap test therefore covers the
 *       bucket-less case (MUST refuse the drop and hold the lip); the "with bucket → MLG through" case is live
 *       territory (verified live: drop-10/20 onto 1×1).
 *   <li><b>Digs are instant</b> (1 tick/block) on this topology, so what the dig-vs-detour tests exercise is the
 *       PLANNER's break-cost economics and the dig fallback chain.
 * </ul>
 *
 * <p>Rig notes: bypass branches sit at |dz|=3 from the mainline (outside the carrot wall-snap adoption radius) and
 * junctions are open-air right angles on flat deck (no wall corners to catch the drive). All strips are genuinely 1
 * block wide: lateral-drift shedding is part of what this family guards.
 *
 * <p>Each walk is ticked by the test, one whole bot tick at a time, inside one server task: no world tick falls between
 * the bot's ticks, as in the scenes these were.
 *
 * <p>The tests of this class run one after another: a walk ticked inside one server task holds the server thread for
 * seconds, and a sibling leasing its plot meanwhile gives up after 8 s.
 */
@Scene("wd.scenes")
@Execution(ExecutionMode.SAME_THREAD)
class BridgeTest {

    /** Deck height in plot coordinates (deck top at y=20, stand 21). */
    private static final int DECK = 20;
    /**
     * Catch floor sits this far under the LOWEST deck of the test — deep enough that no legal descent chain can ever
     * reach it (it must never enter the search space; a 10-block drop once attracted post-fall best-effort routing and
     * muddied triage).
     */
    private static final int CATCH_DROP = 40;

    // ---------------------------------------------------------------- rig helpers ----

    /** 1-wide deck strip along +x at deck-top height {@code y}, lane {@code z}. */
    private static void strip(Plot plot, int x0, int x1, int y, int z) {
        plot.fill(x0, y, z, x1, y, z, Blocks.STONE);
    }

    /** 1-wide deck strip along +z at fixed {@code x}. */
    private static void stripZ(Plot plot, int x, int z0, int z1, int y) {
        plot.fill(x, y, z0, x, y, z1, Blocks.STONE);
    }

    /** 3×3 pad centred on (cx, y, cz) — stable spawn / goal platforms. */
    private static void pad(Plot plot, int cx, int y, int cz) {
        plot.fill(cx - 1, y, cz - 1, cx + 1, y, cz + 1, Blocks.STONE);
    }

    /**
     * Catch floor: a player that falls off lands here instead of leaving the plot. Reaching it at all is already a
     * failure via the minY guard. The 3-tall bedrock rim keeps a fallen player from wandering off.
     */
    private static void catchFloor(Plot plot, int x0, int x1, int y, int z0, int z1) {
        plot.fill(x0, y, z0, x1, y, z1, Blocks.BEDROCK);
        plot.fill(x0, y + 1, z0, x1, y + 3, z0, Blocks.BEDROCK);
        plot.fill(x0, y + 1, z1, x1, y + 3, z1, Blocks.BEDROCK);
        plot.fill(x0, y + 1, z0, x0, y + 3, z1, Blocks.BEDROCK);
        plot.fill(x1, y + 1, z0, x1, y + 3, z1, Blocks.BEDROCK);
    }

    private static SceneBot spawn(Game game, Plot plot, int x, int standY, int z) {
        return SceneBot.spawn(game, plot.at(x, standY, z));
    }

    /**
     * How one walk went, read on the server thread at its end. {@code end}: the navigator as the walk left it, for its
     * probes in a failure message.
     */
    private record Run(
            String step,
            int ticks,
            double minY,
            double maxX,
            NavigatorReading end,
            Vec3 pos,
            String breach,
            String journey,
            String pinWindow,
            String invEvents,
            boolean walkedBestEffort,
            int dirtLeft) {}

    private static String progress(Run r) {
        return NavWalk.trace(r.end(), "progressProbe");
    }

    private static int bagCount(ServerPlayer fp) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) {
            n += st.getCount();
        }
        return n;
    }

    /**
     * Drive one navigator journey to {@code goal}, capped at {@code n} bot ticks, in one server task; tracks the
     * no-fall guard (minY) and the farthest +x progress (plot-relative). {@code guardStandY}: the test's lowest
     * legitimate stand level (plot-relative); the first tick the player dips below it, the navigator's state is
     * snapshotted so a fall failure reports WHERE and in WHAT state the player left the deck (the final probe is
     * post-fall and has misled triage before).
     */
    private static Run drive(Game game, Plot plot, SceneBot bot, int n, BlockPos goal, int guardStandY) {
        BlockPos o = plot.origin();
        return game.onServer(server -> {
            // Started in the same server task as the ticks: a started goto puts the bot on the tick list.
            bot.goTo(Targets.block(goal));
            ServerPlayer fp = bot.player();
            boolean walking = true;
            NavigatorReading nav = bot.navigator();
            double minY = fp.getY();
            double maxX = fp.getX() - o.getX();
            double guardAbs = o.getY() + guardStandY - 0.5;
            String breach = null;
            // Rolling kinematics window, snapshotted INTO the breach string at the first guard breach: the fail
            // message is the one diagnostics channel that always survives.
            ArrayDeque<String> trail = new ArrayDeque<>();
            // Journey log: one entry per PLAN SWAP (position + tick + the new plan's identity; full node dump for the
            // first few swaps) — arrive/approach failures have no breach window, and the livelock that matters
            // happened hundreds of ticks before the end.
            List<String> journey = new ArrayList<>();
            String lastPid = "";
            // Pin window: the first time the player sits STATIC for 15 ticks mid-journey the rolling trail is
            // snapshotted — arrive-failures wedge long before the terminal, and the end-of-run trail only shows the
            // FAILED epilogue.
            String pinWindow = null;
            double spx = 0;
            double spz = 0;
            int staticTicks = 0;
            // Inventory-delta events: the material-contract tests fail on END-state bag counts with no way to tell
            // WHEN/WHERE the blocks went — a planned foothold spends 1-2 at the face, a stall-recovery rung spends one
            // per churn cycle anywhere. Snapshot each change with position + the navigator probe.
            List<String> invEvents = new ArrayList<>();
            int invCount = bagCount(fp);
            boolean walkedBestEffort = false;
            int t = 0;
            for (; t < n && walking; t++) {
                bot.tick();
                walking = bot.busy();
                nav = bot.navigator();
                Optional<PlanCursor> cur = nav.plan();
                String jumpTag = cur.flatMap(PlanCursor::jumpTag).orElse("-");
                if (!walkedBestEffort) {
                    walkedBestEffort = NavWalk.trace(nav, "progressProbe").contains(" bestEffort");
                }
                minY = Math.min(minY, fp.getY());
                maxX = Math.max(maxX, fp.getX() - o.getX());
                Vec3 vel = fp.getDeltaMovement();
                String probe = NavWalk.trace(nav, "tickProbe");
                String pid = probe.length() >= 5 ? probe.substring(0, 5) : probe;
                if (!pid.equals(lastPid) || t < 12) {
                    lastPid = pid;
                    if (journey.size() < 52) {
                        journey.add(
                                "t" + t + "@" + String.format("(%.1f,%.1f)", fp.getX() - o.getX(), fp.getZ() - o.getZ())
                                        + (journey.size() < 16 ? " " + NavWalk.trace(nav, "planProbe") : " " + probe));
                    }
                }
                trail.addLast(String.format(
                                "t=%d p=(%.2f,%.2f,%.2f) v=(%.3f,%.3f,%.3f) yaw=%.0f g=%b sn=%b sp=%b hc=%b jt=%s",
                                t,
                                fp.getX() - o.getX(),
                                fp.getY() - o.getY(),
                                fp.getZ() - o.getZ(),
                                vel.x,
                                vel.y,
                                vel.z,
                                fp.getYRot(),
                                fp.onGround(),
                                fp.isShiftKeyDown(),
                                fp.isSprinting(),
                                fp.horizontalCollision,
                                jumpTag)
                        + " as=" + cur.flatMap(PlanCursor::aimTag).orElse("-")
                        + " dr=[" + cur.flatMap(PlanCursor::driveTag).orElse("EARLY-RETURN") + "]"
                        + " " + probe);
                if (trail.size() > 16) {
                    trail.removeFirst();
                }
                int ic = bagCount(fp);
                if (ic != invCount) {
                    if (invEvents.size() < 14) {
                        invEvents.add("t" + t + "Δ" + (ic - invCount) + "@"
                                + String.format(
                                        "(%.1f,%.1f,%.1f)",
                                        fp.getX() - o.getX(), fp.getY() - o.getY(), fp.getZ() - o.getZ())
                                + " jt=" + jumpTag + " " + probe);
                    }
                    invCount = ic;
                }
                if (Math.abs(fp.getX() - spx) < 0.01 && Math.abs(fp.getZ() - spz) < 0.01) {
                    staticTicks++;
                } else {
                    staticTicks = 0;
                    spx = fp.getX();
                    spz = fp.getZ();
                }
                if (pinWindow == null && t > 60 && staticTicks == 15) {
                    pinWindow = String.join(" ;; ", trail);
                }
                if (breach == null && fp.getY() < guardAbs) {
                    breach = "breach@t=" + t + " pos=("
                            + String.format(
                                    "%.1f,%.1f,%.1f", fp.getX() - o.getX(), fp.getY() - o.getY(), fp.getZ() - o.getZ())
                            + ")rel "
                            + NavWalk.trace(nav, "progressProbe")
                            + " ;; " + NavWalk.trace(nav, "planProbe")
                            + " ;; shed-window: " + String.join(" ;; ", trail);
                }
            }
            return new Run(
                    NavWalk.step(bot),
                    t,
                    minY,
                    maxX,
                    nav,
                    fp.position(),
                    breach,
                    String.join(" ;; ", journey),
                    pinWindow == null ? "-" : pinWindow,
                    invEvents.isEmpty() ? "-" : String.join(" ;; ", invEvents),
                    walkedBestEffort,
                    fp.getInventory().countItem(Items.DIRT));
        });
    }

    private static boolean atGoal(Run r, BlockPos goal) {
        return Math.abs(r.pos().x - (goal.getX() + 0.5)) < 1.5
                && Math.abs(r.pos().z - (goal.getZ() + 0.5)) < 1.5
                && r.pos().y >= goal.getY() - 0.5;
    }

    /** Family-wide no-fall guard: the player must never have dropped below the lowest legitimate deck stand level. */
    private static void assertNeverFell(Plot plot, String test, Run r, int lowestStandY) {
        double floorAbs = plot.origin().getY() + lowestStandY - 0.5;
        if (r.minY() < floorAbs) {
            fail(test + ": the bot dropped below the deck (minY=" + r.minY() + " < " + floorAbs
                    + ") — fell off the bridge | " + r.breach() + " | final " + progress(r));
        }
    }

    private static void assertArrived(String test, Run r, BlockPos goal) {
        if (!atGoal(r, goal)) {
            fail(test + ": did not arrive: step=" + r.step() + " ticks=" + r.ticks()
                    + " pos=" + r.pos() + " goal=" + goal + " | " + progress(r)
                    + " | " + NavWalk.trace(r.end(), "planProbe") + " ;; pin-window: " + r.pinWindow()
                    + " ;; journey: " + r.journey());
        }
    }

    /**
     * Stopped-short contract (dead-end tests): advanced to the barrier's approach, never crossed it, never fell.
     * {@code barrierX}: plot-relative x of the block.
     */
    private static void assertHeldAtBarrier(
            Plot plot, String test, Run r, BlockPos goal, int barrierX, int lowestStandY) {
        assertNeverFell(plot, test, r, lowestStandY);
        if (atGoal(r, goal)) {
            fail(test + ": expected to be blocked but ARRIVED — the barrier is not sealing the route");
        }
        if (r.maxX() < barrierX - 4) {
            fail(test + ": never approached the barrier (maxX=" + r.maxX() + " < " + (barrierX - 4)
                    + ") — expected to advance to the farthest reachable point | end=" + r.step()
                    + "@t" + r.ticks() + " | " + progress(r) + " ;; journey: " + r.journey());
        }
        if (r.maxX() > barrierX + 1.0) {
            fail(test + ": crossed the sealed barrier (maxX=" + r.maxX() + " > " + (barrierX + 1) + ") | "
                    + progress(r));
        }
    }

    /** The block at plot cell {@code (x, y, z)} is {@code want}. */
    private static void assertBlock(Game game, Plot plot, int x, int y, int z, Block want) {
        Block at = game.onServer(s ->
                s.getLevel(plot.dimension()).getBlockState(plot.at(x, y, z)).getBlock());
        if (at != want) {
            fail("block at rel(" + x + "," + y + "," + z + ") is " + at + ", expected " + want);
        }
    }

    /**
     * Standard course frame: start pad (centre x=-2), 1-wide deck x=0..len, end pad (centre x=len+2), catch floor.
     * Returns the goal (end-pad centre stand cell).
     */
    private static BlockPos frame(Plot plot, int len) {
        pad(plot, -2, DECK, 0);
        strip(plot, 0, len, DECK, 0);
        pad(plot, len + 2, DECK, 0);
        catchFloor(plot, -6, len + 6, DECK - CATCH_DROP, -8, 8);
        return plot.at(len + 2, DECK + 1, 0);
    }

    /**
     * Bypass around mainline cells [xa..xb]: a SOLID side platform (z=0..4) spanning [xa-3..xb+3]. Solid on purpose — a
     * first cut used 1-wide connector branches two cells apart and the planner immediately found a marginal
     * flat-parkour shortcut across the connector gap, then churned at the standing-start takeoff. The scenario under
     * test is bypass ROUTING, not marginal parkour (that has its own arenas), so the bypass offers no holes to jump.
     */
    private static void bypass(Plot plot, int xa, int xb, int y) {
        plot.fill(xa - 3, y, 0, xb + 3, y, 4, Blocks.STONE);
    }

    /**
     * Switch to the LIVE compiled-default stack: this battery's contract is live behaviour, and the test baseline
     * disables the very recovery machinery (corner-churn shorteners, monotonic stuck clocks, ...) these courses lean
     * on. Break/place are then re-asserted OFF (they default ON live) — tests that want them pin them ON explicitly
     * after this call.
     */
    private static Settings liveStack(Game game) {
        return Settings.compiledDefaults(game)
                .set("allowBreak", false)
                .set("allowPlace", false)
                // Default-OFF, but the sealed-goal tests need it to end at the farthest reachable point instead of
                // ping-ponging the deck; see its BotConfig javadoc for why it is not shipped.
                .set("navigatorFromEndNoProgressDiscard", true);
    }

    // ---------------------------------------------------------------- tests ----

    /**
     * The same walk on a 1-wide causeway flush with two-deep water on both sides, 104 blocks pad to pad: arriving, on
     * the deck, at no less than half a plain walk's pace (2.2 blocks/s), over a course built past the planner's horizon
     * so at least one segment is a best-effort partial. Water rather than void, because a bot that steps off here swims
     * instead of falling, and the failure this guards against ended with it in the water.
     */
    @Test
    void bridgeCausewayOverWater(
            Game game, @Plot.Centered @Plot.Size(x = 128, y = 64, z = 20) Plot plot, TestReporter reporter) {
        liveStack(game);
        final int half = 52;
        final int end = half + 3;
        plot.fill(-end, DECK - 2, -5, end, DECK - 2, 5, Blocks.STONE);
        plot.fill(-end, DECK - 1, -5, end, DECK, 5, Blocks.STONE); // the rim …
        plot.fill(-end + 1, DECK - 1, -4, end - 1, DECK, 4, Blocks.WATER); // … and the water inside it
        strip(plot, -end + 1, end - 1, DECK, 0);
        strip(plot, -end + 1, end - 1, DECK - 1, 0);
        pad(plot, -half, DECK, 0);
        pad(plot, half, DECK, 0);
        catchFloor(plot, -end - 2, end + 2, DECK - CATCH_DROP, -8, 8);

        String test = "bridgeCausewayOverWater";
        BlockPos goal = plot.at(half, DECK + 1, 0);
        Run r = drive(game, plot, spawn(game, plot, -half, DECK + 1, 0), 1400, goal, DECK + 1);
        reporter.publishEntry("walk.ticks", String.valueOf(r.ticks()));
        assertNeverFell(plot, test, r, DECK + 1);
        assertArrived(test, r, goal);
        int blocks = 2 * half;
        if (r.ticks() > blocks * 9) {
            fail(test + ": " + blocks + " blocks took " + r.ticks() + " ticks (limit " + blocks * 9 + ") | journey: "
                    + r.journey());
        }
        // Only a best-effort partial has a tail to consume; a search that reaches the goal outright would pass
        // whatever the tail consume does.
        if (!r.walkedBestEffort()) {
            fail(test + ": rig broken: no segment was a best-effort partial | journey: " + r.journey());
        }
    }

    /** Single-width bridge with a 1-high obstacle: one hurdle mid-deck; step up, step down, continue. */
    @Test
    void bridgeHurdle1(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game);
        BlockPos goal = frame(plot, 24);
        plot.place(12, DECK + 1, 0, Blocks.STONE);
        Run r = drive(game, plot, spawn(game, plot, -2, DECK + 1, 0), 1800, goal, DECK + 1);
        assertNeverFell(plot, "bridgeHurdle1", r, DECK + 1);
        assertArrived("bridgeHurdle1", r, goal);
    }

    /**
     * 1.5-high obstacle with a bypass: block+bottom-slab (1.5 > jump reach 1.25) seals the mainline; the |dz|=3 branch
     * is the only route.
     *
     * <p>The routing claim is carried by {@code assertArrived}: {@code bypass()} widens the deck to {@code z=0..4}, the
     * obstacle seals only {@code z=0}, and with break OFF there is no way to reach the goal except around it. So
     * "arrived" IS "went around" here. The arm that grades "dig or detour" is {@link #bridgeDetourCheap}.
     */
    @Test
    void bridgeHurdleSlabBypass(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game);
        BlockPos goal = frame(plot, 24);
        plot.place(12, DECK + 1, 0, Blocks.STONE);
        plot.place(12, DECK + 2, 0, Blocks.STONE_SLAB);
        bypass(plot, 12, 12, DECK);
        Run r = drive(game, plot, spawn(game, plot, -2, DECK + 1, 0), 2000, goal, DECK + 1);
        assertNeverFell(plot, "bridgeHurdleSlabBypass", r, DECK + 1);
        assertArrived("bridgeHurdleSlabBypass", r, goal);
    }

    /**
     * A head-height bar with no route around it, on the shipped navigator, whose from-end discard is OFF: the journey
     * must still reach the barrier and hold there instead of ping-ponging at the start pad.
     */
    @Test
    void bridgeHeadBlockStopShipped(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game).set("navigatorFromEndNoProgressDiscard", false);
        BlockPos goal = frame(plot, 24);
        plot.place(12, DECK + 2, 0, Blocks.STONE);
        Run r = drive(game, plot, spawn(game, plot, -2, DECK + 1, 0), 1500, goal, DECK + 1);
        assertHeldAtBarrier(plot, "bridgeHeadBlockStopShipped", r, goal, 12, DECK + 1);
    }

    /**
     * Foot-height block with no route: 1-high hurdle UNDER a low ceiling (deck+3 roof spans the hurdle) — the mount
     * jump has no headroom, the under-gap is sealed by the hurdle. No route; same hold-the-lip contract.
     */
    @Test
    void bridgeFootBlockStop(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game);
        BlockPos goal = frame(plot, 24);
        plot.place(12, DECK + 1, 0, Blocks.STONE); // foot hurdle
        plot.fill(10, DECK + 3, 0, 14, DECK + 3, 0, Blocks.STONE); // roof kills the jump
        Run r = drive(game, plot, spawn(game, plot, -2, DECK + 1, 0), 1500, goal, DECK + 1);
        assertHeldAtBarrier(plot, "bridgeFootBlockStop", r, goal, 12, DECK + 1);
    }

    /**
     * Descending bridge placement into a shaft — ROUTE regression cover for replay-0013, NOT a wedge repro: a step-up
     * lip over a ceiling-sealed 2-wide shaft whose only continuation is a DESCENDING bridgePlace (support cell below
     * the lip's foot). Both A/B arms of navigatorBridgeDescentPlaceAnchor pass here — the live wedge (74×
     * climb-slide-repath during the place-aim ticks) needs the async-search/escalation regime a deterministic
     * synchronous-search test structurally cannot enter. This test pins the descending-place ROUTE: planner emits it,
     * actuator executes it, the bot arrives without entering the shaft, material is spent. The wedge itself is
     * validated live via mc.debug.replay{replay-0013}.
     */
    @Test
    void bridgeDescendPlaceLip(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game).set("allowPlace", true);
        // Live shape (replay-0013): the lip is MOUNTED BY A STEP-UP right before the descending place — the wedge
        // fired while the bot was still climbing onto the lip.
        pad(plot, -2, DECK + 2, 0);
        strip(plot, 0, 6, DECK + 2, 0); // approach deck (stand DECK+3)
        plot.place(7, DECK + 3, 0, Blocks.STONE); // the LIP: one step UP (stand DECK+4)
        plot.place(7, DECK + 2, 0, Blocks.STONE); // solid column under the lip
        // shaft columns x=8..9: open air straight down to the catch floor
        strip(plot, 10, 20, DECK + 2, 0); // continuation deck (stand DECK+3)
        // Ceiling over the lip and shaft: stand DECK+4 needs head DECK+5 clear — but any jump arc needs +1.25 (head
        // into DECK+6). Sealing DECK+6 over x=6..11 forbids parkour, leaving the descending bridgePlace as the only
        // continuation.
        plot.fill(6, DECK + 6, 0, 11, DECK + 6, 0, Blocks.STONE);
        pad(plot, 22, DECK + 2, 0);
        catchFloor(plot, -6, 26, DECK - CATCH_DROP, -8, 8);
        BlockPos goal = plot.at(22, DECK + 3, 0);
        SceneBot bot = spawn(game, plot, -2, DECK + 3, 0);
        bot.give(Items.DIRT, 16);
        Run r = drive(game, plot, bot, 2200, goal, DECK + 3);
        assertNeverFell(plot, "bridgeDescendPlaceLip", r, DECK + 3);
        assertArrived("bridgeDescendPlaceLip", r, goal);
        if (r.dirtLeft() >= 16) {
            fail("bridgeDescendPlaceLip: arrived but no block was consumed (dirt=" + r.dirtLeft()
                    + "/16) — the shaft crossing should need a placed bridge support ;; "
                    + "inv-events: " + r.invEvents() + " ;; journey: " + r.journey());
        }
    }

    /**
     * Descending stair with a gap (fall damage): drop-4 resume = the pathfinderMaxDryFall cap edge. Live this costs ~1
     * HP; the bot's player is damage-immune by contract, so the test asserts routing+traversal (see class doc).
     */
    @Test
    void bridgeStairDownGapDamage(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        stairGap(game, plot, "bridgeStairDownGapDamage", 4, true);
    }

    /**
     * Descending stair with a gap (lethal, no water bucket): drop-12 break — beyond the dry cap, and the dedicated
     * world view can never offer fallBucket (client-only capability, see class doc), so there is NO route: hold the
     * lip, do not step off.
     */
    @Test
    void bridgeLethalGapStop(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        stairGap(game, plot, "bridgeLethalGapStop", 12, false);
    }

    /**
     * Shared rig: high approach deck x=0..11 at DECK+8, gap at x=12, resume deck x=12..24 at DECK+8-drop, end pad.
     * Catch floor sits under the LOW deck.
     */
    private static void stairGap(Game game, Plot plot, String name, int drop, boolean expectThrough) {
        liveStack(game);
        int hi = DECK + 8;
        int lo = hi - drop;
        pad(plot, -2, hi, 0);
        strip(plot, 0, 11, hi, 0);
        strip(plot, 12, 24, lo, 0); // resume ADJACENT to the lip (x=12) — a Fall edge is dx=1;
        // resuming at x=13 leaves a floorless chasm column instead of a step-off
        pad(plot, 26, lo, 0);
        catchFloor(plot, -6, 30, lo - CATCH_DROP, -8, 8);
        BlockPos goal = plot.at(26, lo + 1, 0);
        Run r = drive(game, plot, spawn(game, plot, -2, hi + 1, 0), 2000, goal, expectThrough ? lo + 1 : hi + 1);
        if (expectThrough) {
            assertNeverFell(plot, name, r, lo + 1);
            assertArrived(name, r, goal);
        } else {
            // Hold-the-lip: minY must stay at the HIGH deck — descending at all is the failure.
            assertNeverFell(plot, name, r, hi + 1);
            if (atGoal(r, goal)) {
                fail(name + ": crossed a lethal gap with no fall-break available");
            }
            if (r.maxX() < 8) {
                fail(name + ": never approached the lip (maxX=" + r.maxX() + ") | " + progress(r));
            }
        }
    }

    /**
     * Ascending step that needs a foothold (with material): a +2 cliff splits the deck; allowPlace + dirt in the bag →
     * the planner may pillar/foothold through. Assert arrival AND that placement actually happened.
     */
    @Test
    void bridgeFootholdPlace(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game).set("allowPlace", true);
        BlockPos goal = footholdRig(plot);
        SceneBot bot = spawn(game, plot, -2, DECK + 1, 0);
        bot.give(Items.DIRT, 16);
        Run r = drive(game, plot, bot, 2200, goal, DECK + 1);
        assertNeverFell(plot, "bridgeFootholdPlace", r, DECK + 1);
        assertArrived("bridgeFootholdPlace", r, goal);
        if (r.dirtLeft() >= 16) {
            fail("bridgeFootholdPlace: arrived but no block was consumed (dirt=" + r.dirtLeft()
                    + "/16) — the +2 face should be unclimbable without a foothold ;; inv-events: "
                    + r.invEvents() + " ;; journey: " + r.journey());
        }
    }

    /** Ascending step that needs a foothold (no material): same rig, empty bag → no route; hold at the face. */
    @Test
    void bridgeFootholdStarve(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game).set("allowPlace", true);
        BlockPos goal = footholdRig(plot);
        Run r = drive(game, plot, spawn(game, plot, -2, DECK + 1, 0), 1500, goal, DECK + 1);
        assertHeldAtBarrier(plot, "bridgeFootholdStarve", r, goal, 12, DECK + 1);
    }

    /** Low deck x=0..11 at DECK, high deck x=12..22 at DECK+2 (a sheer +2 face). */
    private static BlockPos footholdRig(Plot plot) {
        pad(plot, -2, DECK, 0);
        strip(plot, 0, 11, DECK, 0);
        strip(plot, 12, 22, DECK + 2, 0);
        // Seal the face so the column under the high deck lip is solid (no crawl-in).
        plot.place(12, DECK + 1, 0, Blocks.STONE);
        plot.place(12, DECK, 0, Blocks.STONE);
        pad(plot, 24, DECK + 2, 0);
        catchFloor(plot, -6, 28, DECK - CATCH_DROP, -8, 8);
        return plot.at(24, DECK + 3, 0);
    }

    /**
     * Two-high step with a bypass (no material spent): the same +2 face, but a |dz|=5 branch climbs it as two +1 steps.
     * Dirt in the bag + allowPlace ON — the walk detour must win on cost, so the bag stays untouched.
     */
    @Test
    void bridgeStepTwoBypassNoPlace(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game).set("allowPlace", true);
        BlockPos goal = footholdRig(plot);
        // Branch: leave around x=8, climb +1 at x=11 and +1 at x=13 along z=5, rejoin the high deck around x=14. The
        // branch parallels the mainline at z=5 — OUTSIDE the carrot/adoption snap radius (~3-4): at z=3 the
        // fast-forward adopted the branch node while the bot was still on the mainline and the drive dragged it off
        // the deck chasing a laterally-snapped carrot. Junctions are SOLID slabs, not 1-wide L-connectors: every thin
        // L-junction exposes a LEGAL parkour2d shortcut across its inside corner, and A* prices the leap under the
        // corridor walk. Chained marginal parkour has its own arenas; this test is about bypass ROUTING, so the rig
        // offers no corner to cut.
        plot.fill(7, DECK, 0, 10, DECK, 5, Blocks.STONE);
        strip(plot, 11, 12, DECK + 1, 5);
        plot.fill(13, DECK + 2, 0, 16, DECK + 2, 5, Blocks.STONE);
        SceneBot bot = spawn(game, plot, -2, DECK + 1, 0);
        bot.give(Items.DIRT, 16);
        Run r = drive(game, plot, bot, 2200, goal, DECK + 1);
        assertNeverFell(plot, "bridgeStepTwoBypassNoPlace", r, DECK + 1);
        assertArrived("bridgeStepTwoBypassNoPlace", r, goal);
        if (r.dirtLeft() != 16) {
            fail("bridgeStepTwoBypassNoPlace: the stair bypass exists but blocks were spent (dirt="
                    + r.dirtLeft() + "/16) — placement should lose to the walk detour on cost ;; inv-events: "
                    + r.invEvents() + " ;; journey: " + r.journey());
        }
    }

    /**
     * Digging is faster than the detour: thin wall (2 blocks) vs a LONG |dz|=6 detour (~32 extra walks), iron pickaxe
     * in hand → the planner should dig through; assert the wall fell.
     */
    @Test
    void bridgeDigShortcut(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game).set("allowBreak", true);
        BlockPos goal = frame(plot, 24);
        plot.place(12, DECK + 1, 0, Blocks.STONE);
        plot.place(12, DECK + 2, 0, Blocks.STONE);
        stripZ(plot, 2, 1, 6, DECK);
        strip(plot, 2, 22, DECK, 6);
        stripZ(plot, 22, 1, 6, DECK);
        SceneBot bot = spawn(game, plot, -2, DECK + 1, 0);
        bot.give(Items.IRON_PICKAXE, 1);
        Run r = drive(game, plot, bot, 2200, goal, DECK + 1);
        assertNeverFell(plot, "bridgeDigShortcut", r, DECK + 1);
        assertArrived("bridgeDigShortcut", r, goal);
        boolean intact = game.onServer(s -> {
            var level = s.getLevel(plot.dimension());
            return level.getBlockState(plot.at(12, DECK + 1, 0)).is(Blocks.STONE)
                    && level.getBlockState(plot.at(12, DECK + 2, 0)).is(Blocks.STONE);
        });
        if (intact) {
            fail("bridgeDigShortcut: arrived with the wall INTACT — took the 32-block detour "
                    + "where a 2-block pickaxe dig is far cheaper");
        }
    }

    /**
     * The detour is faster than digging: same wall but BARE-HAND (stone ≈150t/block live → huge break cost) vs a SHORT
     * |dz|=3 detour (~10 extra walks) → detour must win; wall intact.
     */
    @Test
    void bridgeDetourCheap(Game game, @Plot.Centered @Plot.Size(x = 80, y = 64, z = 20) Plot plot) {
        liveStack(game).set("allowBreak", true);
        BlockPos goal = frame(plot, 24);
        plot.place(12, DECK + 1, 0, Blocks.STONE);
        plot.place(12, DECK + 2, 0, Blocks.STONE);
        bypass(plot, 12, 12, DECK);
        Run r = drive(game, plot, spawn(game, plot, -2, DECK + 1, 0), 2200, goal, DECK + 1);
        assertNeverFell(plot, "bridgeDetourCheap", r, DECK + 1);
        assertArrived("bridgeDetourCheap", r, goal);
        assertBlock(game, plot, 12, DECK + 1, 0, Blocks.STONE);
        assertBlock(game, plot, 12, DECK + 2, 0, Blocks.STONE);
    }
}

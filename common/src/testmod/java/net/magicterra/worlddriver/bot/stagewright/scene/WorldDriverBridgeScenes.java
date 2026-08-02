package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Single-width plank-bridge (独木桥) battery: a systematic sweep of 1-wide elevated
 * walkways — flat runs, hurdles, bypass routing, dead ends, stairs, broken descents,
 * foothold placement and dig-vs-detour economics. The family-wide contract is
 * <b>"traverse (or stop) WITHOUT falling off"</b>: every scene tracks the body's
 * minimum Y through the whole run and fails if it ever dropped below the lowest
 * legitimate deck level, and every rig hangs a catch floor 10 blocks under the deck
 * so a shed body lands inside the slot instead of leaving the arena (void-fall rig
 * rule; the catch floor is itself a failure detector, not a route).
 *
 * <p>Dedicated-topology caveats (same class as the documented DIG-hold / GEAR-degraded
 * exemptions in docs/coverage-exemptions.md):
 * <ul>
 *   <li><b>Fall damage:</b> FakePlayer avatars are damage-immune by contract, so the
 *       "damaging drop" scene asserts ROUTING+traversal (the planner accepts a
 *       maxDryFall-cap drop), not HP loss — live damage is ~1 HP at drop 4.</li>
 *   <li><b>MLG water-bucket fall:</b> {@code canWaterBucketFall()} is only wired on
 *       {@code ClientWorldView} (bucketFallReady) and the {@code ClutchController} is
 *       ticked from the client tick — a dedicated-server LevelWorldView can never emit
 *       or actuate {@code fallBucket*}. The lethal-gap scene therefore covers the
 *       bucket-less leg (MUST refuse the drop and hold the lip); the "with bucket →
 *       MLG through" leg is live/T1 territory (verified live: drop-10/20 onto 1×1).</li>
 *   <li><b>Digs are instant</b> (1 tick/block) on this topology, so the pickaxe vs
 *       bare-hand legs of the break-through scene share execution speed; what they
 *       exercise is the PLANNER's break-cost economics and the dig fallback chain.</li>
 * </ul>
 *
 * <p>Rig notes: bypass branches sit at |dz|=3 from the mainline (outside the carrot
 * wall-snap adoption radius — iron rule #1) and junctions are open-air right angles
 * on flat deck (no wall corners to catch the drive — iron rule #2). All strips are
 * genuinely 1 block wide: lateral-drift shedding is part of what this family guards.
 */
public final class WorldDriverBridgeScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.bridgeLongRun", 600, WorldDriverBridgeScenes::bridgeLongRun),
                Scene.of("wd.bridgeHurdle1", 600, WorldDriverBridgeScenes::bridgeHurdle1),
                Scene.of("wd.bridgeHurdleSlabBypass", 700, WorldDriverBridgeScenes::bridgeHurdleSlabBypass),
                Scene.of("wd.bridgeHurdle2Bypass", 700, WorldDriverBridgeScenes::bridgeHurdle2Bypass),
                Scene.of("wd.bridgeHeadBlockBypass", 700, WorldDriverBridgeScenes::bridgeHeadBlockBypass),
                Scene.of("wd.bridgeHeadBlockStop", 900, WorldDriverBridgeScenes::bridgeHeadBlockStop),
                Scene.of("wd.bridgeFootBlockStop", 900, WorldDriverBridgeScenes::bridgeFootBlockStop),
                Scene.of("wd.bridgeStairUp", 700, WorldDriverBridgeScenes::bridgeStairUp),
                Scene.of("wd.bridgeStairDown", 700, WorldDriverBridgeScenes::bridgeStairDown),
                Scene.of("wd.bridgeDescendPlaceLip", 900, WorldDriverBridgeScenes::bridgeDescendPlaceLip),
                Scene.of("wd.bridgeStairDownGapSafe", 700, WorldDriverBridgeScenes::bridgeStairDownGapSafe),
                Scene.of("wd.bridgeStairDownGapDamage", 700, WorldDriverBridgeScenes::bridgeStairDownGapDamage),
                Scene.of("wd.bridgeLethalGapStop", 900, WorldDriverBridgeScenes::bridgeLethalGapStop),
                Scene.of("wd.bridgeFootholdPlace", 900, WorldDriverBridgeScenes::bridgeFootholdPlace),
                Scene.of("wd.bridgeFootholdStarve", 900, WorldDriverBridgeScenes::bridgeFootholdStarve),
                Scene.of("wd.bridgeStepTwoBypassNoPlace", 900, WorldDriverBridgeScenes::bridgeStepTwoBypassNoPlace),
                Scene.of("wd.bridgeBreakThrough", 900, WorldDriverBridgeScenes::bridgeBreakThrough),
                Scene.of("wd.bridgeDigShortcut", 900, WorldDriverBridgeScenes::bridgeDigShortcut),
                Scene.of("wd.bridgeDetourCheap", 900, WorldDriverBridgeScenes::bridgeDetourCheap));
    }

    // ---------------------------------------------------------------- rig helpers ----

    /** Deck height above the scene origin (slot Y=200 → deck top at oY+20, stand oY+21). */
    private static final int DECK = 20;
    /** Catch floor sits this far under the LOWEST deck of the scene — deep enough that
     *  no legal descent chain can ever reach it (it must never enter the search space;
     *  a 10-block drop once attracted post-fall best-effort routing and muddied triage). */
    private static final int CATCH_DROP = 40;

    /** 1-wide deck strip along +x at deck-top height {@code y}, lane {@code z}. */
    private static void strip(SceneContext ctx, int x0, int x1, int y, int z) {
        for (int x = x0; x <= x1; x++) ctx.setBlock(x, y, z, Blocks.STONE);
    }

    /** 1-wide deck strip along +z at fixed {@code x}. */
    private static void stripZ(SceneContext ctx, int x, int z0, int z1, int y) {
        for (int z = z0; z <= z1; z++) ctx.setBlock(x, y, z, Blocks.STONE);
    }

    /** 3×3 pad centred on (cx, y, cz) — stable spawn / goal platforms. */
    private static void pad(SceneContext ctx, int cx, int y, int cz) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                ctx.setBlock(cx + dx, y, cz + dz, Blocks.STONE);
    }

    /** Catch floor: a shed body lands here (inside the slot) instead of leaving the
     *  arena. Reaching it at all is already a scene failure via the minY guard. The
     *  3-tall bedrock rim keeps a fallen body from wandering off the slot (rig rule:
     *  fence every floor bordering void). */
    private static void catchFloor(SceneContext ctx, int x0, int x1, int y, int z0, int z1) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                ctx.setBlock(x, y, z, Blocks.BEDROCK);
        for (int dy = 1; dy <= 3; dy++) {
            for (int x = x0; x <= x1; x++) {
                ctx.setBlock(x, y + dy, z0, Blocks.BEDROCK);
                ctx.setBlock(x, y + dy, z1, Blocks.BEDROCK);
            }
            for (int z = z0; z <= z1; z++) {
                ctx.setBlock(x0, y + dy, z, Blocks.BEDROCK);
                ctx.setBlock(x1, y + dy, z, Blocks.BEDROCK);
            }
        }
    }

    private static ServerPlayerAvatar spawn(SceneContext ctx, int x, int standY, int z) {
        ServerLevel level = ctx.level();
        BlockPos p = ctx.rel(x, standY, z);
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> { if (!fp.isRemoved()) fp.discard(); });   // breakThrough discards per-leg
        fp.getInventory().clearContent();
        return av;
    }

    private record Run(Walker.Step step, int ticks, double minY, double maxX,
                       Walker walker, ServerPlayer fp, String breach, String journey,
                       String pinWindow, String invEvents) {}

    private static int bagCount(ServerPlayer fp) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) n += st.getCount();
        return n;
    }

    /** Drive one walker journey to {@code goal}, capped at {@code n} sim ticks; tracks
     *  the no-fall guard (minY) and the farthest +x progress (origin-relative).
     *  {@code guardStandY}: the scene's lowest legitimate stand level (origin-relative);
     *  the first tick the body dips below it, the walker's state is snapshotted so a
     *  fall failure reports WHERE and in WHAT state the body left the deck (the final
     *  probe is post-fall and has misled triage before). */
    private static Run drive(SceneContext ctx, ServerPlayerAvatar av, int n, BlockPos goal, int guardStandY) {
        ServerPlayer fp = av.fakePlayer();
        LevelWorldView w = new LevelWorldView(ctx.level(), fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        double minY = fp.getY();
        double maxX = fp.getX() - ctx.origin().getX();
        double guardAbs = ctx.origin().getY() + guardStandY - 0.5;
        String breach = null;
        // Rolling kinematics window, snapshotted INTO the breach string at the first
        // guard breach: the fail message is the only reliable diagnostics channel here —
        // these scenes run at the tail of the suite where the async logger's burst-drop
        // plus the post-footer halt silently discards whole log segments (task#95 lesson;
        // this battery's triage chased a phantom "stale build" for a full day on it).
        java.util.ArrayDeque<String> trail = new java.util.ArrayDeque<>();
        // Journey log: one entry per PLAN SWAP (position + tick + the new plan's identity;
        // full node dump for the first few swaps) — arrive/approach failures have no breach
        // window, and the livelock that matters happened hundreds of ticks before the end.
        java.util.List<String> journey = new java.util.ArrayList<>();
        String lastPid = "";
        // Pin window: the first time the body sits STATIC for 15 ticks mid-journey the
        // rolling trail is snapshotted — arrive-failures wedge long before the terminal,
        // and the end-of-run trail only shows the FAILED epilogue.
        String pinWindow = null;
        double spx = 0, spz = 0;
        int staticTicks = 0;
        // Inventory-delta events: the material-contract scenes (StepTwo no-place /
        // footholdPlace) fail on END-state bag counts with no way to tell WHEN/WHERE the
        // blocks went — a planned foothold spends 1-2 at the face, a stall-recovery rung
        // (jt=pillarRecoverRung) spends one per churn cycle anywhere. Snapshot each change
        // with position + the walker probe so the fail message carries the attribution.
        java.util.List<String> invEvents = new java.util.ArrayList<>();
        int invCount = bagCount(fp);
        int t = 0;
        for (; t < n && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            minY = Math.min(minY, fp.getY());
            maxX = Math.max(maxX, fp.getX() - ctx.origin().getX());
            var vel = fp.getDeltaMovement();
            String probe = walker.tickProbe();
            String pid = probe.length() >= 5 ? probe.substring(0, 5) : probe;
            if (!pid.equals(lastPid) || t < 12) {
                lastPid = pid;
                if (journey.size() < 52)
                    journey.add("t" + t + "@" + String.format("(%.1f,%.1f)",
                            fp.getX() - ctx.origin().getX(), fp.getZ() - ctx.origin().getZ())
                            + (journey.size() < 16 ? " " + walker.planProbe() : " " + probe));
            }
            trail.addLast(String.format("t=%d p=(%.2f,%.2f,%.2f) v=(%.3f,%.3f,%.3f) yaw=%.0f g=%b sn=%b sp=%b hc=%b jt=%s",
                    t, fp.getX() - ctx.origin().getX(), fp.getY() - ctx.origin().getY(),
                    fp.getZ() - ctx.origin().getZ(), vel.x, vel.y, vel.z,
                    fp.getYRot(), fp.onGround(), fp.isShiftKeyDown(), fp.isSprinting(),
                    fp.horizontalCollision,
                    walker.jumpTag == null ? "-" : walker.jumpTag)
                    + " as=" + (walker.aimTag == null ? "-" : walker.aimTag)
                    + " dr=[" + (walker.driveTag == null ? "EARLY-RETURN" : walker.driveTag) + "]"
                    + " " + probe);
            if (trail.size() > 16) trail.removeFirst();
            int ic = bagCount(fp);
            if (ic != invCount) {
                if (invEvents.size() < 14)
                    invEvents.add("t" + t + "Δ" + (ic - invCount) + "@" + String.format("(%.1f,%.1f,%.1f)",
                            fp.getX() - ctx.origin().getX(), fp.getY() - ctx.origin().getY(),
                            fp.getZ() - ctx.origin().getZ())
                            + " jt=" + (walker.jumpTag == null ? "-" : walker.jumpTag) + " " + probe);
                invCount = ic;
            }
            if (Math.abs(fp.getX() - spx) < 0.01 && Math.abs(fp.getZ() - spz) < 0.01) staticTicks++;
            else { staticTicks = 0; spx = fp.getX(); spz = fp.getZ(); }
            if (pinWindow == null && t > 60 && staticTicks == 15)
                pinWindow = String.join(" ;; ", trail);
            if (breach == null && fp.getY() < guardAbs) {
                breach = "breach@t=" + t + " pos=(" + String.format("%.1f,%.1f,%.1f",
                        fp.getX() - ctx.origin().getX(), fp.getY() - ctx.origin().getY(),
                        fp.getZ() - ctx.origin().getZ()) + ")rel " + walker.progressProbe()
                        + " ;; " + walker.planProbe()
                        + " ;; shed-window: " + String.join(" ;; ", trail);
            }
        }
        return new Run(s, t, minY, maxX, walker, fp, breach, String.join(" ;; ", journey),
                pinWindow == null ? "-" : pinWindow,
                invEvents.isEmpty() ? "-" : String.join(" ;; ", invEvents));
    }

    private static boolean atGoal(SceneContext ctx, Run r, BlockPos goal) {
        return Math.abs(r.fp().getX() - (goal.getX() + 0.5)) < 1.5
                && Math.abs(r.fp().getZ() - (goal.getZ() + 0.5)) < 1.5
                && r.fp().getY() >= goal.getY() - 0.5;
    }

    /** Family-wide no-fall guard: the body must never have dropped below the lowest
     *  legitimate deck stand level of the scene. */
    private static void assertNeverFell(SceneContext ctx, String scene, Run r, int lowestStandY) {
        double floorAbs = ctx.origin().getY() + lowestStandY - 0.5;
        if (r.minY() < floorAbs)
            ctx.fail(scene + ": body dropped below the deck (minY=" + r.minY() + " < " + floorAbs
                    + ") — fell off the bridge | " + r.breach() + " | final " + r.walker().progressProbe());
    }

    private static void assertArrived(SceneContext ctx, String scene, Run r, BlockPos goal) {
        if (!atGoal(ctx, r, goal))
            ctx.fail(scene + ": did not arrive: step=" + r.step() + " ticks=" + r.ticks()
                    + " pos=" + r.fp().position() + " goal=" + goal + " | " + r.walker().progressProbe()
                    + " | " + r.walker().planProbe() + " ;; pin-window: " + r.pinWindow()
                    + " ;; journey: " + r.journey());
    }

    /** Stopped-short contract (dead-end scenes): advanced to the barrier's approach,
     *  never crossed it, never fell. {@code barrierX}: origin-relative x of the block. */
    private static void assertHeldAtBarrier(SceneContext ctx, String scene, Run r, BlockPos goal,
                                            int barrierX, int lowestStandY) {
        assertNeverFell(ctx, scene, r, lowestStandY);
        if (atGoal(ctx, r, goal))
            ctx.fail(scene + ": expected to be blocked but ARRIVED — the barrier is not sealing the route");
        if (r.maxX() < barrierX - 4)
            ctx.fail(scene + ": never approached the barrier (maxX=" + r.maxX() + " < " + (barrierX - 4)
                    + ") — expected to advance to the farthest reachable point | end=" + r.step()
                    + "@t" + r.ticks() + " | " + r.walker().progressProbe()
                    + " ;; journey: " + r.journey());
        if (r.maxX() > barrierX + 1.0)
            ctx.fail(scene + ": crossed the sealed barrier (maxX=" + r.maxX() + " > " + (barrierX + 1)
                    + ") | " + r.walker().progressProbe());
    }

    /** Standard course frame: start pad (centre x=-2), 1-wide deck x=0..len, end pad
     *  (centre x=len+2), catch floor. Returns the goal (end-pad centre stand cell). */
    private static BlockPos frame(SceneContext ctx, int len) {
        pad(ctx, -2, DECK, 0);
        strip(ctx, 0, len, DECK, 0);
        pad(ctx, len + 2, DECK, 0);
        catchFloor(ctx, -6, len + 6, DECK - CATCH_DROP, -8, 8);
        return ctx.rel(len + 2, DECK + 1, 0);
    }

    /** Bypass around mainline cells [xa..xb]: a SOLID side platform (z=0..4) spanning
     *  [xa-3..xb+3]. Solid on purpose — a first cut used 1-wide connector branches two
     *  cells apart and the planner immediately found a marginal flat-parkour shortcut
     *  across the connector gap, then churned at the standing-start takeoff (t0
     *  2026-07-20 first run). The scenario under test is bypass ROUTING, not marginal
     *  parkour (that has its own arenas), so the bypass offers no holes to jump. */
    private static void bypass(SceneContext ctx, int xa, int xb, int y) {
        for (int x = xa - 3; x <= xb + 3; x++)
            for (int z = 0; z <= 4; z++)
                ctx.setBlock(x, y, z, Blocks.STONE);
    }

    /** Pin (snapshot + restore-on-close) then switch to the LIVE compiled-default
     *  stack: this battery's contract is live behaviour, and the legacy §78 arena
     *  baseline disables the very recovery machinery (corner-churn shorteners,
     *  monotonic stuck clocks, ...) these courses lean on. Break/place are then
     *  re-asserted OFF (they default ON live) — scenes that want them pin them ON
     *  explicitly after this call. */
    private static void liveStack(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.applyCompiledDefaults();
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        // Per-scene opt-in of the two default-OFF planner-flow mechanisms this battery
        // depends on (both pending their own replay A/B — see the BotConfig javadocs):
        // directional tail consume keeps the quick-start stub's far-ahead tail alive
        // (else sealed-goal journeys self-consume at the start pad and livelock), and
        // the from-end no-progress discard ends a sealed-goal journey cleanly at the
        // farthest reachable point instead of ping-ponging the deck.
        BotConfig.walkerTailConsumeDirectional = true;
        BotConfig.walkerFromEndNoProgressDiscard = true;
    }

    // ---------------------------------------------------------------- scenes ----

    /** 长条单宽独木桥跑酷: 32-block 1-wide run, end to end, no shed. */
    private static void bridgeLongRun(SceneContext ctx) {
        liveStack(ctx);
        BlockPos goal = frame(ctx, 32);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 1800, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeLongRun", r, DECK + 1);
        assertArrived(ctx, "bridgeLongRun", r, goal);
    }

    /** 带1格障碍的独木桥: single 1-high hurdle mid-deck — step up, step down, carry on. */
    private static void bridgeHurdle1(SceneContext ctx) {
        liveStack(ctx);
        BlockPos goal = frame(ctx, 24);
        ctx.setBlock(12, DECK + 1, 0, Blocks.STONE);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 1800, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeHurdle1", r, DECK + 1);
        assertArrived(ctx, "bridgeHurdle1", r, goal);
    }

    /** 1.5格高障碍+旁路: block+bottom-slab (1.5 > jump reach 1.25) seals the mainline;
     *  the |dz|=3 branch is the only route. Obstacle must survive (break stays OFF). */
    private static void bridgeHurdleSlabBypass(SceneContext ctx) {
        liveStack(ctx);
        BlockPos goal = frame(ctx, 24);
        ctx.setBlock(12, DECK + 1, 0, Blocks.STONE);
        ctx.setBlock(12, DECK + 2, 0, Blocks.STONE_SLAB);
        bypass(ctx, 12, 12, DECK);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 2000, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeHurdleSlabBypass", r, DECK + 1);
        assertArrived(ctx, "bridgeHurdleSlabBypass", r, goal);
        ctx.assertBlock(12, DECK + 1, 0, Blocks.STONE);
    }

    /** 2格高障碍+旁路: full 2-tall pillar on the deck, bypass branch is the route. */
    private static void bridgeHurdle2Bypass(SceneContext ctx) {
        liveStack(ctx);
        BlockPos goal = frame(ctx, 24);
        ctx.setBlock(12, DECK + 1, 0, Blocks.STONE);
        ctx.setBlock(12, DECK + 2, 0, Blocks.STONE);
        bypass(ctx, 12, 12, DECK);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 2000, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeHurdle2Bypass", r, DECK + 1);
        assertArrived(ctx, "bridgeHurdle2Bypass", r, goal);
        ctx.assertBlock(12, DECK + 2, 0, Blocks.STONE);
    }

    /** 挡头+旁路: head-height bar over the deck (foot cell free, head cell sealed —
     *  a 1-tall gap fits no biped and the planner may not crawl), bypass is the route. */
    private static void bridgeHeadBlockBypass(SceneContext ctx) {
        liveStack(ctx);
        BlockPos goal = frame(ctx, 24);
        ctx.setBlock(12, DECK + 2, 0, Blocks.STONE);            // head bar; foot cell open
        bypass(ctx, 12, 12, DECK);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 2000, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeHeadBlockBypass", r, DECK + 1);
        assertArrived(ctx, "bridgeHeadBlockBypass", r, goal);
    }

    /** 挡头且无路径: head bar, no bypass. Contract = advance to the farthest reachable
     *  cell, hold there (or fail the goto), and NEVER leave the deck. */
    private static void bridgeHeadBlockStop(SceneContext ctx) {
        liveStack(ctx);
        BlockPos goal = frame(ctx, 24);
        ctx.setBlock(12, DECK + 2, 0, Blocks.STONE);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 1500, goal, DECK + 1);
        assertHeldAtBarrier(ctx, "bridgeHeadBlockStop", r, goal, 12, DECK + 1);
    }

    /** 挡脚且无路径: 1-high hurdle UNDER a low ceiling (deck+3 roof spans the hurdle) —
     *  the mount jump has no headroom, the under-gap is sealed by the hurdle. No route;
     *  same hold-the-lip contract. */
    private static void bridgeFootBlockStop(SceneContext ctx) {
        liveStack(ctx);
        BlockPos goal = frame(ctx, 24);
        ctx.setBlock(12, DECK + 1, 0, Blocks.STONE);            // foot hurdle
        for (int x = 10; x <= 14; x++) ctx.setBlock(x, DECK + 3, 0, Blocks.STONE); // roof kills the jump
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 1500, goal, DECK + 1);
        assertHeldAtBarrier(ctx, "bridgeFootBlockStop", r, goal, 12, DECK + 1);
    }

    /** 阶梯上升的独木桥: five +1 rises, four flat cells apart, all 1-wide. */
    private static void bridgeStairUp(SceneContext ctx) {
        liveStack(ctx);
        pad(ctx, -2, DECK, 0);
        for (int i = 0; i <= 5; i++) strip(ctx, i * 4, Math.min(i * 4 + 3, 23), DECK + i, 0);
        pad(ctx, 25, DECK + 5, 0);
        catchFloor(ctx, -6, 30, DECK - CATCH_DROP, -8, 8);
        BlockPos goal = ctx.rel(25, DECK + 6, 0);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 2200, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeStairUp", r, DECK + 1);
        assertArrived(ctx, "bridgeStairUp", r, goal);
    }

    /** 阶梯下降的独木桥: mirror of stairUp — five -1 drops on a 1-wide strip. */
    private static void bridgeStairDown(SceneContext ctx) {
        liveStack(ctx);
        pad(ctx, -2, DECK + 5, 0);
        for (int i = 0; i <= 5; i++) strip(ctx, i * 4, Math.min(i * 4 + 3, 23), DECK + 5 - i, 0);
        pad(ctx, 25, DECK, 0);
        catchFloor(ctx, -6, 30, DECK - CATCH_DROP, -8, 8);
        BlockPos goal = ctx.rel(25, DECK + 1, 0);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 6, 0), 2200, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeStairDown", r, DECK + 1);
        assertArrived(ctx, "bridgeStairDown", r, goal);
    }

    /** 下行搭桥入竖井 — ROUTE regression cover for task#4 (replay-0013), NOT a wedge
     *  repro: a step-up lip over a ceiling-sealed 2-wide shaft whose only continuation
     *  is a DESCENDING bridgePlace (support cell below the lip's foot). Both A/B legs
     *  of walkerBridgeDescentPlaceAnchor PASS here — the live wedge (74× climb-slide-
     *  repath during the place-aim ticks) needs the async-search/escalation regime a
     *  deterministic synchronous-search scene structurally cannot enter (same verdict
     *  as the futileBankDig family / #52). This scene pins the descending-place ROUTE:
     *  planner emits it, actuator executes it, body arrives without entering the
     *  shaft, material is spent. The wedge itself is validated live via
     *  mc.debug.replay{replay-0013} (bot must be idle — it teleports the body). */
    private static void bridgeDescendPlaceLip(SceneContext ctx) {
        liveStack(ctx);
        BotConfig.allowPlace = true;
        // Live shape (replay-0013): the lip is MOUNTED BY A STEP-UP right before the
        // descending place — the wedge fired while the body was still climbing onto
        // the lip (the actuator freeze mid-step-up dropped it back down the stair,
        // x 89.81→89.28, 74× climb-slide-repath).
        pad(ctx, -2, DECK + 2, 0);
        strip(ctx, 0, 6, DECK + 2, 0);           // approach deck (stand DECK+3)
        ctx.setBlock(7, DECK + 3, 0, Blocks.STONE);   // the LIP: one step UP (stand DECK+4)
        ctx.setBlock(7, DECK + 2, 0, Blocks.STONE);   // solid column under the lip
        // shaft columns x=8..9: open air straight down to the catch floor
        strip(ctx, 10, 20, DECK + 2, 0);         // continuation deck (stand DECK+3)
        // Ceiling over the lip and shaft: stand DECK+4 needs head DECK+5 clear — but
        // any jump arc needs +1.25 (head into DECK+6). Sealing DECK+6 over x=6..11
        // forbids parkour, leaving the descending bridgePlace as the only continuation.
        for (int x = 6; x <= 11; x++) ctx.setBlock(x, DECK + 6, 0, Blocks.STONE);
        pad(ctx, 22, DECK + 2, 0);
        catchFloor(ctx, -6, 26, DECK - CATCH_DROP, -8, 8);
        BlockPos goal = ctx.rel(22, DECK + 3, 0);
        ServerPlayerAvatar av = spawn(ctx, -2, DECK + 3, 0);
        av.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 16));
        Run r = drive(ctx, av, 2200, goal, DECK + 3);
        assertNeverFell(ctx, "bridgeDescendPlaceLip", r, DECK + 3);
        assertArrived(ctx, "bridgeDescendPlaceLip", r, goal);
        int left = av.fakePlayer().getInventory().countItem(Items.DIRT);
        if (left >= 16)
            ctx.fail("bridgeDescendPlaceLip: arrived but no block was consumed (dirt=" + left
                    + "/16) — the shaft crossing should need a placed bridge support ;; "
                    + "inv-events: " + r.invEvents() + " ;; journey: " + r.journey());
    }

    /** 阶梯下降有中断(安全): the descending strip breaks at x=12 with a drop-3 resume —
     *  inside the dry-fall cap, planner routes the fall, body continues after landing. */
    private static void bridgeStairDownGapSafe(SceneContext ctx) {
        stairGapScene(ctx, "bridgeStairDownGapSafe", 3, true);
    }

    /** 阶梯下降有中断(跌落伤害): drop-4 resume = the pathfinderMaxDryFall cap edge.
     *  Live this costs ~1 HP; the FakePlayer is damage-immune by contract, so the
     *  scene asserts routing+traversal (see class doc). */
    private static void bridgeStairDownGapDamage(SceneContext ctx) {
        stairGapScene(ctx, "bridgeStairDownGapDamage", 4, true);
    }

    /** 阶梯下降有中断(致命, 无水桶): drop-12 break — beyond the dry cap, and the
     *  dedicated world view can never offer fallBucket (client-only capability, see
     *  class doc), so there is NO route: hold the lip, do not step off. */
    private static void bridgeLethalGapStop(SceneContext ctx) {
        stairGapScene(ctx, "bridgeLethalGapStop", 12, false);
    }

    /** Shared rig: high approach deck x=0..11 at DECK+8, gap at x=12, resume deck
     *  x=13..24 at DECK+8-drop, end pad. Catch floor sits under the LOW deck. */
    private static void stairGapScene(SceneContext ctx, String name, int drop, boolean expectThrough) {
        liveStack(ctx);
        int hi = DECK + 8, lo = hi - drop;
        pad(ctx, -2, hi, 0);
        strip(ctx, 0, 11, hi, 0);
        strip(ctx, 12, 24, lo, 0);   // resume ADJACENT to the lip (x=12) — a Fall edge is dx=1;
                                     // resuming at x=13 leaves a floorless chasm column instead of a step-off
        pad(ctx, 26, lo, 0);
        catchFloor(ctx, -6, 30, lo - CATCH_DROP, -8, 8);
        BlockPos goal = ctx.rel(26, lo + 1, 0);
        Run r = drive(ctx, spawn(ctx, -2, hi + 1, 0), 2000, goal, expectThrough ? lo + 1 : hi + 1);
        if (expectThrough) {
            assertNeverFell(ctx, name, r, lo + 1);
            assertArrived(ctx, name, r, goal);
        } else {
            // Hold-the-lip: minY must stay at the HIGH deck — descending at all is the failure.
            assertNeverFell(ctx, name, r, hi + 1);
            if (atGoal(ctx, r, goal))
                ctx.fail(name + ": crossed a lethal gap with no fall-break available");
            if (r.maxX() < 8)
                ctx.fail(name + ": never approached the lip (maxX=" + r.maxX()
                        + ") | " + r.walker().progressProbe());
        }
    }

    /** 阶梯上升需垫脚(有材料): a +2 cliff splits the deck; allowPlace + dirt in the bag
     *  → the planner may pillar/foothold through. Assert arrival AND that placement
     *  actually happened (dirt consumed or a placed block present). */
    private static void bridgeFootholdPlace(SceneContext ctx) {
        liveStack(ctx);
        BotConfig.allowPlace = true;
        BlockPos goal = footholdRig(ctx);
        ServerPlayerAvatar av = spawn(ctx, -2, DECK + 1, 0);
        av.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 16));
        Run r = drive(ctx, av, 2200, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeFootholdPlace", r, DECK + 1);
        assertArrived(ctx, "bridgeFootholdPlace", r, goal);
        int left = av.fakePlayer().getInventory().countItem(Items.DIRT);
        if (left >= 16)
            ctx.fail("bridgeFootholdPlace: arrived but no block was consumed (dirt=" + left
                    + "/16) — the +2 face should be unclimbable without a foothold ;; inv-events: "
                    + r.invEvents() + " ;; journey: " + r.journey());
    }

    /** 阶梯上升需垫脚(无材料): same rig, empty bag → no route; hold at the face. */
    private static void bridgeFootholdStarve(SceneContext ctx) {
        liveStack(ctx);
        BotConfig.allowPlace = true;
        BlockPos goal = footholdRig(ctx);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 1500, goal, DECK + 1);
        assertHeldAtBarrier(ctx, "bridgeFootholdStarve", r, goal, 12, DECK + 1);
    }

    /** Low deck x=0..11 at DECK, high deck x=12..22 at DECK+2 (a sheer +2 face). */
    private static BlockPos footholdRig(SceneContext ctx) {
        pad(ctx, -2, DECK, 0);
        strip(ctx, 0, 11, DECK, 0);
        strip(ctx, 12, 22, DECK + 2, 0);
        // Seal the face so the column under the high deck lip is solid (no crawl-in).
        ctx.setBlock(12, DECK + 1, 0, Blocks.STONE);
        ctx.setBlock(12, DECK, 0, Blocks.STONE);
        pad(ctx, 24, DECK + 2, 0);
        catchFloor(ctx, -6, 28, DECK - CATCH_DROP, -8, 8);
        return ctx.rel(24, DECK + 3, 0);
    }

    /** 两格高阶梯但有旁路(不消耗材料): the same +2 face, but a |dz|=3 branch climbs it
     *  as two +1 steps. Dirt in the bag + allowPlace ON — the walk detour must win on
     *  cost, so the bag stays untouched. */
    private static void bridgeStepTwoBypassNoPlace(SceneContext ctx) {
        liveStack(ctx);
        BotConfig.allowPlace = true;
        BlockPos goal = footholdRig(ctx);
        // Branch: leave around x=8, climb +1 at x=11 and +1 at x=13 along z=5, rejoin the
        // high deck around x=14. Branch parallels the mainline at z=5 — OUTSIDE the
        // carrot/adoption snap radius (~3-4): at z=3 the fast-forward adopted the branch
        // node while the body was still on the mainline and the drive dragged it off the
        // deck chasing a laterally-snapped carrot (t0 2026-07-20 round 2, breach@t=66).
        // Junctions are SOLID slabs, not 1-wide L-connectors: every thin L-junction
        // exposes a LEGAL parkour2d shortcut across its inside corner (the diagonal
        // mid-cell is floorless, so the move validates), and A* prices the leap under
        // the corridor walk — the plan came out (7,0)→(9,2)[parkour2d] and the chained
        // leap flew off the deck (t0 2026-07-20). Chained marginal parkour has its own
        // arenas; this scene tests bypass ROUTING, so the rig offers no corner to cut.
        for (int x = 7; x <= 10; x++)
            for (int z = 0; z <= 5; z++) ctx.setBlock(x, DECK, z, Blocks.STONE);
        strip(ctx, 11, 12, DECK + 1, 5);
        for (int x = 13; x <= 16; x++)
            for (int z = 0; z <= 5; z++) ctx.setBlock(x, DECK + 2, z, Blocks.STONE);
        ServerPlayerAvatar av = spawn(ctx, -2, DECK + 1, 0);
        av.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 16));
        Run r = drive(ctx, av, 2200, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeStepTwoBypassNoPlace", r, DECK + 1);
        assertArrived(ctx, "bridgeStepTwoBypassNoPlace", r, goal);
        int left = av.fakePlayer().getInventory().countItem(Items.DIRT);
        if (left != 16)
            ctx.fail("bridgeStepTwoBypassNoPlace: the stair bypass exists but blocks were spent (dirt="
                    + left + "/16) — placement should lose to the walk detour on cost ;; inv-events: "
                    + r.invEvents() + " ;; journey: " + r.journey());
    }

    /** 不可跨障碍+开破坏(有镐无镐都过): a 2-tall wall seals the deck, allowBreak ON, no
     *  bypass — leg 1 bare-hand, leg 2 iron pickaxe over a rebuilt wall. Digs are
     *  instant on this topology (class doc); the coverage is the dig-through routing. */
    private static void bridgeBreakThrough(SceneContext ctx) {
        liveStack(ctx);
        BotConfig.allowBreak = true;
        BlockPos goal = frame(ctx, 24);
        for (String leg : new String[] {"bareHand", "pickaxe"}) {
            ctx.setBlock(12, DECK + 1, 0, Blocks.STONE);
            ctx.setBlock(12, DECK + 2, 0, Blocks.STONE);
            ServerPlayerAvatar av = spawn(ctx, -2, DECK + 1, 0);
            if (leg.equals("pickaxe")) av.fakePlayer().getInventory().add(new ItemStack(Items.IRON_PICKAXE));
            Run r = drive(ctx, av, 2200, goal, DECK + 1);
            assertNeverFell(ctx, "bridgeBreakThrough[" + leg + "]", r, DECK + 1);
            assertArrived(ctx, "bridgeBreakThrough[" + leg + "]", r, goal);
            av.fakePlayer().discard();
        }
    }

    /** 挖掘比绕路快: thin wall (2 blocks) vs a LONG |dz|=6 detour (~32 extra walks),
     *  iron pickaxe in hand → the planner should dig through; assert the wall fell. */
    private static void bridgeDigShortcut(SceneContext ctx) {
        liveStack(ctx);
        BotConfig.allowBreak = true;
        BlockPos goal = frame(ctx, 24);
        ctx.setBlock(12, DECK + 1, 0, Blocks.STONE);
        ctx.setBlock(12, DECK + 2, 0, Blocks.STONE);
        stripZ(ctx, 2, 1, 6, DECK);
        strip(ctx, 2, 22, DECK, 6);
        stripZ(ctx, 22, 1, 6, DECK);
        ServerPlayerAvatar av = spawn(ctx, -2, DECK + 1, 0);
        av.fakePlayer().getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        Run r = drive(ctx, av, 2200, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeDigShortcut", r, DECK + 1);
        assertArrived(ctx, "bridgeDigShortcut", r, goal);
        if (ctx.level().getBlockState(ctx.rel(12, DECK + 1, 0)).is(Blocks.STONE)
                && ctx.level().getBlockState(ctx.rel(12, DECK + 2, 0)).is(Blocks.STONE))
            ctx.fail("bridgeDigShortcut: arrived with the wall INTACT — took the 32-block detour "
                    + "where a 2-block pickaxe dig is far cheaper");
    }

    /** 挖掘不如绕路快: same wall but BARE-HAND (stone ≈150t/block live → huge break
     *  cost) vs a SHORT |dz|=3 detour (~10 extra walks) → detour must win; wall intact. */
    private static void bridgeDetourCheap(SceneContext ctx) {
        liveStack(ctx);
        BotConfig.allowBreak = true;
        BlockPos goal = frame(ctx, 24);
        ctx.setBlock(12, DECK + 1, 0, Blocks.STONE);
        ctx.setBlock(12, DECK + 2, 0, Blocks.STONE);
        bypass(ctx, 12, 12, DECK);
        Run r = drive(ctx, spawn(ctx, -2, DECK + 1, 0), 2200, goal, DECK + 1);
        assertNeverFell(ctx, "bridgeDetourCheap", r, DECK + 1);
        assertArrived(ctx, "bridgeDetourCheap", r, goal);
        ctx.assertBlock(12, DECK + 1, 0, Blocks.STONE);
        ctx.assertBlock(12, DECK + 2, 0, Blocks.STONE);
        WorldDriverCommon.LOG.info("[wd.bridgeDetourCheap] wall intact, detour taken, ticks={}", r.ticks());
    }
}

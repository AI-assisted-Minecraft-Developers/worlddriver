package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.magicterra.agent.neoforge.sim.ServerPlayerAvatar;
import net.magicterra.agent.neoforge.sim.ServerAgentDriver;
import net.magicterra.agent.neoforge.sim.ServerAgentManager;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.process.BboxFillProcess;
import net.magicterra.agent.bot.process.BuildProcess;
import net.magicterra.agent.bot.process.FollowProcess;
import net.magicterra.agent.bot.process.MineProcess;
import net.magicterra.agent.bot.process.RunAwayProcess;
import net.magicterra.agent.bot.process.Schematic;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.debug.BotLevelHolder;
import net.magicterra.agent.bot.debug.NodePhysics;
import net.magicterra.agent.bot.debug.PathArchive;
import net.magicterra.agent.bot.debug.PathArchiveRecorder;
import net.magicterra.agent.bot.debug.PathDebugBootstrap;
import net.magicterra.agent.bot.pathfinder.MultiTrace;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.agent.bot.pathfinder.moves.Fall;
import net.magicterra.agent.bot.pathfinder.moves.FallIntoWater;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static net.magicterra.agent.neoforge.AgentGameTestSupport.*;

/**
 * Open-water crossing arenas (basin / route / step-down float / submerged cross / vine-over-water / lily-pad / beeline).
 *
 * <p>Split out of {@link AgentGameTest} for file-size hygiene; behaviour is identical.
 * Registered separately in {@code AgentDriverNeoForge} via {@code RegisterGameTestsEvent}.
 * See {@link AgentGameTest} for the threading / timeout rationale shared by every arena here.
 */
@GameTestHolder(AgentDriverCommon.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AgentGameTestWaterCross {
    private AgentGameTestWaterCross() {}

    /**
     * Goal-snap robustness: a {@code goto} whose exact target block is UNSTANDABLE (buried
     * in terrain) must NOT make the bot oscillate forever — the Walker snaps the goal to the
     * nearest standable cell so the search terminates and the bot arrives there. Reproduces
     * the live failure (random goal (2350,64,1820) was solid stone → A* goalReached never
     * fired → 5 s searches + churn into water). Here the goal column is a solid stone pillar
     * (no standable cell in it); flat floor all around. PRE-fix the Walker would run to the
     * 300-tick budget still WALKING; POST-fix it snaps to an adjacent floor cell and ARRIVEs.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void goalSnapBuriedArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 300, cz = 300, floorY = 64;
        // Flat stone floor the bot walks on; air above.
        for (int dx = -2; dx <= 14; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = floorY + 1; y <= floorY + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // The goal column is a SOLID stone pillar → its foot cell (cx+10, floorY+1) is not
        // standable (solid), and there is no 1.8-tall pocket anywhere in it.
        for (int y = floorY; y <= floorY + 5; y++)
            level.setBlockAndUpdate(new BlockPos(cx + 10, y, cz), Blocks.STONE.defaultBlockState());

        BlockPos buried = new BlockPos(cx + 10, floorY + 1, cz);   // solid → unstandable

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, floorY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(buried));
            Walker.Step s = Walker.Step.WALKING;
            int arrivedTick = -1;
            for (int t = 0; t < 300 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
                if (s == Walker.Step.ARRIVED) { arrivedTick = t; break; }
            }
            double dGoal = Math.hypot(fp.getX() - (cx + 10 + 0.5), fp.getZ() - (cz + 0.5));
            AgentDriverCommon.LOG.info("[goalSnapBuriedArena] step={} pos=({},{},{}) arrivedTick={} dToBuried={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), arrivedTick, String.format("%.1f", dGoal));
            // Snap → ARRIVED at a standable cell adjacent to the buried pillar (within the
            // snap radius). Pre-fix: never ARRIVES (goal unstandable) → still WALKING at 300t.
            // Snapped arrival must sit within the snap radius (6) + a half-block of
            // foot-centre slack of the buried goal column.
            if (s != Walker.Step.ARRIVED)
                throw new GameTestAssertException("goalSnap: bot never arrived at a buried/unstandable goal"
                        + " (snap failed): step=" + s + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
            if (dGoal > 7.0)
                throw new GameTestAssertException("goalSnap: arrived too far from the buried goal (d=" + dGoal + ")");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * Failure-case validation for the anti-basin-dive {@code pathfinderDepthPenalty}
     * (memory long wanted a fully-loaded fixed-world arena). An XZ (Y-agnostic)
     * goal sits across a plateau; the DIRECT corridor is a wide valley with a
     * walkable −1/step down-ramp into a deep floor whose far + side walls are +12
     * SHEER (place OFF → a dead-end trap pocket). The only route that REACHES is
     * the flat go-around on either side. With the penalty OFF the Y-agnostic
     * heuristic reads the downhill ramp as free progress and the search dives the
     * whole trap pocket before backtracking, burning far more nodes; with the
     * penalty ON it charges the descent and takes the rim. Both reach (unbounded);
     * the penalty's value is the node-count cut, AND under a node budget BETWEEN
     * the two counts penalty=0 fails while penalty=6 reaches. Pure planner A/B.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void basinArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 300, cz = 300, plY = 240;
        // Flat plateau (the go-around) across the whole arena.
        for (int dx = -12; dx <= 12; dx++)
            for (int dz = 0; dz <= 44; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, plY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = plY + 1; y <= plY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Carve the valley corridor (cx-3..cx+3, cz8..cz30): clear it, then lay a
        // −1/step down-ramp (cz8→plY-1 … cz19→plY-12) and a deep floor (cz20..30 at
        // plY-12). The plateau resumes flat at cz31 → a +12 sheer far wall; the
        // intact plateau at cx±4 forms +12 sheer side walls. A dead-end pocket.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 8; dz <= 30; dz++)
                for (int y = plY - 13; y <= plY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = 8; dz <= 19; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, plY - (dz - 7), cz + dz), Blocks.STONE.defaultBlockState());
            for (int dz = 20; dz <= 30; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, plY - 12, cz + dz), Blocks.STONE.defaultBlockState());
        }
        BlockPos start = new BlockPos(cx, plY + 1, cz + 2);
        Goal goal = new Goal.XZ(cx, cz + 40);            // ignoresY → the dive-prone case

        boolean odbg = BotConfig.walkerDebug;
        double odp = BotConfig.pathfinderDepthPenalty;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        boolean obe = BotConfig.pathfinderBoxedEscalate;
        BotConfig.walkerDebug = false;
        // pfDepthPenalty() returns max(penalty, 25) when boxed-escalation is ON, which would
        // clamp BOTH our penalty=0 and penalty=6 searches to an identical 25 (→ same node count
        // → false failure). Other tick-stepped arena tests in this batch drive the real Walker,
        // which writes this global flag (Walker L947) and can leave it true when basinArena's
        // tick runs. Force it OFF so pfDepthPenalty() returns the raw penalty we set. (2026-06-27:
        // this is why basinArena was a masked required failure — the test never isolated it.)
        BotConfig.pathfinderBoxedEscalate = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // each search runs to completion (deterministic)
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        LevelWorldView w = new LevelWorldView(level,
                ServerPlayerAvatar.create(level, cx + 0.5, plY + 1, cz + 2).fakePlayer());
        try {
            BotConfig.pathfinderDepthPenalty = 0;
            var r0 = runSearch(w, start, goal);
            BotConfig.pathfinderDepthPenalty = 6;
            var r6 = runSearch(w, start, goal);
            AgentDriverCommon.LOG.info("[basinArena] penalty0: reached={} expanded={} | penalty6: reached={} expanded={}",
                    r0.goalReached(), r0.expanded(), r6.goalReached(), r6.expanded());
            if (!r6.goalReached())
                throw new GameTestAssertException("depthPenalty=6 failed to reach via the rim go-around");
            if (r6.expanded() >= r0.expanded())
                throw new GameTestAssertException("depthPenalty did not cut basin-dive exploration: "
                        + "penalty0 expanded=" + r0.expanded() + " penalty6 expanded=" + r6.expanded());

            // Failure-case: a node budget BETWEEN the two counts — penalty=0 burns it
            // in the trap and FAILS; penalty=6 reaches via the rim within it.
            int budget = (r0.expanded() + r6.expanded()) / 2;
            BotConfig.pathfinderMaxNodes = budget;
            BotConfig.pathfinderDepthPenalty = 0;
            var b0 = runSearch(w, start, goal);
            BotConfig.pathfinderDepthPenalty = 6;
            var b6 = runSearch(w, start, goal);
            AgentDriverCommon.LOG.info("[basinArena] budget={}: penalty0 reached={} | penalty6 reached={}",
                    budget, b0.goalReached(), b6.goalReached());
            if (b0.goalReached() || !b6.goalReached())
                throw new GameTestAssertException("budget A/B not decisive: budget=" + budget
                        + " penalty0.reached=" + b0.goalReached() + " penalty6.reached=" + b6.goalReached());
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderDepthPenalty = odp;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
            BotConfig.pathfinderBoxedEscalate = obe;
        }
        helper.succeed();
    }

    /**
     * Pure-planner check that a buoyant bot is never routed to JUMP out of deep water
     * onto a higher bank — the structural floating-water gate (the source-level root of
     * the live "卡在土墙 / 反复挖同一土块 / 横跳 / 一直试跳1格岸" climb-out windows). A floating bot
     * (water below its feet) physically cannot mount a +1 bank; only a FLUSH walk-out or
     * a dig-to-flush climb works. The north shore offers two exits at equal crossing
     * distance: straight ahead (dx 0) a higher bank that needs a jump, and one cell over
     * (dx ±1) a SURFACE-LEVEL (+0) bank the floating bot just walks onto. The ascending
     * moves (stepUp/stepUp2/diagUp) now gate themselves off a floating-water source, so
     * the +1 bank exit is STRUCTURALLY unavailable and A* must take the flush exit,
     * never rising above the water line (maxY ≤ wsurf) — INDEPENDENT of the climb-out
     * tax. The A/B (tax off vs default) confirms the GATE, not merely the cost, enforces
     * this: both must stay flush. The {@code pathfinderWaterClimbOutCost} now backstops
     * GROUNDED shallow-water climbs (solid floor below → not floating → step-up allowed).
     * Pure planner, unbounded.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void waterClimbOutRouteArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 440, cz = 440, wsurf = 220;       // water surface y; air at wsurf+1
        // Two-column pool, dx 0 and dx 1, dz 0..6: solid floor wsurf-2, water at wsurf-1 & wsurf.
        for (int dx = 0; dx <= 1; dx++)
            for (int dz = 0; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf - 2, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf - 1, cz + dz), Blocks.WATER.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf, cz + dz), Blocks.WATER.defaultBlockState());
                for (int y = wsurf + 1; y <= wsurf + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // dx 0 STRAIGHT exit: a +1 bank at dz=7 (solid top wsurf → stand foot wsurf+1). The
        // shortest exit (fewest water cells) but a buoyant bot can't step onto it cleanly.
        for (int y = wsurf - 2; y <= wsurf; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz + 7), Blocks.STONE.defaultBlockState());
        for (int y = wsurf + 1; y <= wsurf + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz + 7), Blocks.AIR.defaultBlockState());
        // dx 1 GENTLE exit: the pool fingers ONE cell farther north (dz=7 stays water), then a
        // SURFACE-level bank at dz=8 (solid top wsurf-1 → stand foot wsurf, rise 0). One extra
        // water cell buys a step-free exit.
        level.setBlockAndUpdate(new BlockPos(cx + 1, wsurf - 2, cz + 7), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, wsurf - 1, cz + 7), Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx + 1, wsurf, cz + 7), Blocks.WATER.defaultBlockState());
        for (int y = wsurf + 1; y <= wsurf + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx + 1, y, cz + 7), Blocks.AIR.defaultBlockState());
        // Flat land dz 8..18 at stand-foot wsurf (solid top wsurf-1), dx -1..2 — both exits
        // converge here and reach the goal. (dx 0 +1 bank steps DOWN onto it.)
        for (int dx = -1; dx <= 2; dx++)
            for (int dz = 8; dz <= 18; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, wsurf - 1, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = wsurf; y <= wsurf + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        BlockPos start = new BlockPos(cx, wsurf, cz);       // floating at the pool's south end
        Goal goal = new Goal.XZ(cx, cz + 15);               // ignoresY → buoyant climb-out case

        boolean odbg = BotConfig.walkerDebug;
        double oco = BotConfig.pathfinderWaterClimbOutCost;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        LevelWorldView w = new LevelWorldView(level,
                ServerPlayerAvatar.create(level, cx + 0.5, wsurf, cz).fakePlayer());
        try {
            // The south start FLOATS (water directly below), so the floating-water gate
            // STRUCTURALLY forbids the straight +1 bank exit — a buoyant bot can't jump
            // out onto a +1 bank. A* must take the one-cell-over FLUSH exit and never
            // rise above the water line, INDEPENDENT of the climb-out tax. Verify the gate
            // (not just the cost) keeps it flush: with the tax both OFF and at its default,
            // neither route may climb the +1 bank (maxY ≤ wsurf).
            BotConfig.pathfinderWaterClimbOutCost = 0;
            var rOff = runSearch(w, start, goal);
            int maxYOff = maxPathY(rOff);
            BotConfig.pathfinderWaterClimbOutCost = oco;        // the configured default
            var rOn = runSearch(w, start, goal);
            int maxYOn = maxPathY(rOn);
            AgentDriverCommon.LOG.info("[waterClimbOutRouteArena] taxOff: reached={} maxY={} | taxDefault({}): reached={} maxY={}",
                    rOff.goalReached(), maxYOff, oco, rOn.goalReached(), maxYOn);
            if (!rOff.goalReached() || !rOn.goalReached())
                throw new GameTestAssertException("a climb-out route failed to reach: off=" + rOff.goalReached()
                        + " on=" + rOn.goalReached());
            if (maxYOff > wsurf)
                throw new GameTestAssertException("floating-water +1 climb-out was NOT forbidden (tax off): maxY="
                        + maxYOff + " (expected ≤" + wsurf + " — buoyant bot must take the flush exit, not jump the +1 bank)");
            if (maxYOn > wsurf)
                throw new GameTestAssertException("floating-water +1 climb-out was NOT forbidden (tax default): maxY="
                        + maxYOn + " (expected ≤" + wsurf + ")");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderWaterClimbOutCost = oco;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
        }
        helper.succeed();
    }

    /**
     * Attempted deterministic reproduction of the descent-OVERSHOOT step-pointer dead-zone (the
     * {@code walkerVerticalResync} lever) — and the documented CLEAN NEGATIVE that resulted.
     *
     * <p>The live wedge (-1037, intermittent ~1-in-8): the bot crosses a steep crest/shoulder with
     * un-braked momentum, free-falls 2 blocks PAST a stepDown/fall node, and grounds on a terrace
     * ~1.6 b shy of it — node 2 BELOW the foot, cur2≈2.5 (in the step-advance dead-zone),
     * horizontalCollision=false. None of the advance gates fire ({@code within} fails |Δy|=2&gt;1.2;
     * {@code passed}/{@code crossedDescendNode} blocked by their reachability gates;
     * {@code fellOffPath} one block short, |Δy|=2 ≤ 3; ascentRamSlide needs ≥2 ABOVE; descentRamStuck
     * needs 1 below + hCol), so the pointer freezes and the bot oscillates ~80 t until a WEDGE_TICKS
     * burst yanks it loose. Reading the Walker confirms the hole is real.
     *
     * <p>This arena tried to force the STATE deterministically — hand-building the 3-node plan and
     * pointing {@code step} at the overshot fall2 node via {@link Walker#beginScriptedFollow} (which,
     * unlike replay, leaves the live fellOffPath/repath/blacklist recovery ON), with the avatar on
     * faithful vanilla physics (validated by physicsParity / wallCollisionProbe). FOUR geometries
     * (approach-lip, side-notch, behind-notch overshoot, deep-floor continuation) plus a directly
     * injected grounded-2-above position ALL resolve in ~10 ticks: once the pointer sits on a 2-below
     * descend node, the camera-decoupled descent drive walks the grounded bot to that node and
     * vanilla physics DROPS it in before the dead-zone can persist — the |Δy|≥2 + cur2∈(0.45,4) state
     * never holds past STEPUP_FREEZE_TICKS. The ~80-tick live oscillation needs the un-braked 3-D
     * shoulder momentum that a flat arena + a static injected pose cannot reproduce (the same reason
     * {@code ridgeOvershootArena} is a smoothness guard, not a wedge repro). So there is NO
     * deterministic A/B here proving the fix HELPS the live wedge — hence {@code walkerVerticalResync}
     * ships OFF (a reviewed opt-in lever; see its BotConfig doc).
     *
     * <p>Re-purposed as a NON-WEDGE / NON-REGRESSION guard: it asserts the injected dead-zone
     * RESOLVES promptly (a future change that turns this transient into a real ≥WEDGE_TICKS stall —
     * making it a true A/B repro — would trip it) and that the fix is a STRICT no-op in this
     * resolvable state (OFF≡ON), positive evidence it doesn't perturb the smooth baseline.
     */
    /**
     * Deterministic repro of the #47 SHALLOW WATER-SURFACE STEP-DOWN bob-stall (live ground-truth
     * walker telemetry at node -809,62,350: water y62, dirt floor y61, lily-pad/vine head at y63).
     * The bot steps down into a 1-deep splash cell and GROUNDS vertically AT the node ({@code onG=true},
     * foot y62.00, {@code |dY|=0.00}) but pins at {@code cur2≈0.546} — just 0.10 OVER the
     * {@code REACH_DIST_SQ=0.45} reach gate (~0.74 b short of the node CENTRE in X, {@code hCol}, x
     * frozen): buoyancy + the water-climb jump keep lifting/ramming the foot and the prone sprint-swim
     * can't nudge the last fraction in, so {@code within} never fires and the step-pointer freezes for
     * 12 s+ until a safety repath. {@code floatOverSubmerged} misses it (node AT the foot, not below).
     *
     * <p>The buoyant equilibrium is hard to synthesize from a free approach on a static arena (the same
     * reason {@code descentOvershootResyncArena}/{@code ridgeOvershootArena} inject the pose). This arena
     * makes the pin DETERMINISTIC by HEAD-WALLING the node: the node's head cell ({@code waterY+1}) is a
     * solid block (the reliable synthesizer of the live lily-pad/vine head-clutter — the same failure, a
     * buoyant body that cannot seat its head into the surface foothold cell), so the bot pins in the
     * EAST-adjacent 1-deep water cell at {@code cur2≈0.6-1.0} (head blocked at the cell boundary, body in
     * water). A scripted plan makes the CURRENT step the water-surface {@code stepDown} node with a
     * continuation further WEST along the shelf. Leg 0 (flag OFF) must WEDGE (step frozen — the live bug:
     * {@code within} needs {@code cur2<0.45}, unreachable). Leg 1 (flag ON) must ADVANCE (the water-surface
     * float-and-advance relaxation fires once the bot is stalled at the surface foothold). A clean A/B on
     * the step-advance GATE — the only thing that differs between legs is {@code walkerWaterStepDownFloat}.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void waterStepDownFloatArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), away from every other footprint.
        final int cx = 360, cz = 520, floorY = 200;
        final int waterY = floorY + 1;     // y201 = the 1-deep water surface (dirt floor at floorY)
        // Clear a generous air box for a clean slate (a neighbour's residue would change physics).
        for (int dx = -10; dx <= 8; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = floorY - 2; y <= floorY + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // 1-WIDE channel along -X (the path runs WEST). Solid floor (dirt) at floorY, 1-deep water at
        // waterY, from the east approach (dx=+2) to well west of the goal (dx=-7). Channel walls at
        // dz=±1 keep the buoyant body from wandering laterally so the pin is deterministic.
        for (int dx = -7; dx <= 2; dx++) {
            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, waterY, cz), Blocks.WATER.defaultBlockState());
            for (int dz = -1; dz <= 1; dz += 2)
                for (int y = floorY; y <= waterY + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        }
        // NODE = the water-surface stepDown foothold at (cx, waterY, cz). The path runs WEST: the bot
        // approaches the node from the EAST-adjacent water cell (cx+1) and continues to cont/goal further
        // WEST. HEAD-WALL the node so the buoyant body cannot seat its head into the node cell and pins in
        // the east cell ~0.6-1.0 b short of the node centre (the deterministic stand-in for the live
        // lily-pad/vine head-clutter). A lily pad sits in the EAST cell's head (cx+1, waterY+1) — passable,
        // matching the live surface clutter — so the bot is genuinely in a 1-deep splash, not boxed dry.
        BlockPos node  = new BlockPos(cx,     waterY, cz);
        BlockPos cont  = new BlockPos(cx - 3, waterY, cz);   // continuation further WEST along the shelf
        BlockPos goalN = new BlockPos(cx - 6, waterY, cz);
        level.setBlockAndUpdate(node.above(), Blocks.STONE.defaultBlockState());   // head-wall at the node cell
        level.setBlockAndUpdate(new BlockPos(cx + 1, waterY + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        Goal goal = new Goal.Block(goalN);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        boolean owf = BotConfig.walkerWaterStepDownFloat;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;          // no carving — the stall must be the buoyant reach, not a dig
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            int[] advanceTick = { -1, -1 };    // first tick the step-pointer left the water node, per leg
            int[] nodeDwell = new int[2];      // ticks the step-pointer sat ON the water node, per leg
            boolean[] advanced = new boolean[2];
            double[] minCur2 = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
            // Leg 0 = flag OFF (must WEDGE), leg 1 = flag ON (must ADVANCE).
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.walkerWaterStepDownFloat = (leg == 1);
                BotConfig.walkerDebug = true;
                // SEED the bot in the EAST-adjacent 1-deep water cell (cx+1), pressed WEST toward the
                // head-walled node, with a small west velocity (residual approach momentum). It grounds
                // on the floor (foot=waterY, |dyNode|≈0) but its head is blocked at the cx/cx+1 boundary →
                // pins ~0.6-1.0 b short of the node centre (the live -807.76 vs node-centre pin).
                ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 1.5, waterY, cz + 0.5);
                FakePlayer fp = av.fakePlayer();
                fp.setDeltaMovement(-0.10, 0, 0);
                grantWaterEffects(fp);
                LevelWorldView w = new LevelWorldView(level, fp);
                Walker walker = new Walker();
                // Plan: step0 = the east approach cell (cx+1, where the bot is) → step1 = NODE (the
                // water-surface stepDown the bot pins at) → cont → goal, all WEST along the shelf.
                BlockPos approach = new BlockPos(cx + 1, waterY, cz);
                List<BlockPos> plan = List.of(approach, node, cont, goalN);
                List<Move.Edge> planEdges = List.of(
                        new Move.Edge(approach, 10, List.of(), List.of(), "walk"),
                        new Move.Edge(node,  10, List.of(), List.of(), "stepDown"),   // the water-surface foothold
                        new Move.Edge(cont,  10, List.of(), List.of(), "walk"),
                        new Move.Edge(goalN, 10, List.of(), List.of(), "walk"));
                // beginReplay (NOT beginScriptedFollow): pin the scripted plan with replayMode → the safety
                // repath is DISABLED, so the OFF leg's wedge is a TRUE permanent stall (no A* escape muddies
                // the A/B). The step starts at the approach node the bot stands on and advances to the water
                // node within a tick; the ONLY thing that then differs between legs is the new gate.
                walker.beginReplay(w, plan, planEdges, goal, approach);

                Walker.Step s = Walker.Step.WALKING;
                for (int t = 0; t < 200 && s == Walker.Step.WALKING; t++) {
                    s = walker.tick(av, w);
                    av.step();
                    BlockPos pn = walker.pathNode();
                    if (pn != null && pn.equals(node)) {
                        double dx = (node.getX() + 0.5) - fp.getX();
                        double dz = (node.getZ() + 0.5) - fp.getZ();
                        minCur2[leg] = Math.min(minCur2[leg], dx * dx + dz * dz);
                        nodeDwell[leg]++;            // ticks the step-pointer sat ON the water node
                    }
                    // ADVANCE = the step-pointer moved PAST the water node (step>=2) — i.e. the bot committed
                    // the stepDown instead of pinning on it forever (replayMode → no repath escape).
                    if (advanceTick[leg] < 0 && walker.pathStep() >= 2) {
                        advanceTick[leg] = t;
                        advanced[leg] = true;
                    }
                }
                AgentDriverCommon.LOG.info("[waterStepDownFloatArena] leg={} flagOn={} advanced={} advanceTick={} nodeDwell={} endStep={} minCur2={} endPos=({},{},{}) step={}",
                        leg, leg == 1, advanced[leg], advanceTick[leg], nodeDwell[leg], walker.pathStep(),
                        String.format(Locale.ROOT, "%.3f", minCur2[leg]),
                        String.format(Locale.ROOT, "%.2f", fp.getX()), String.format(Locale.ROOT, "%.2f", fp.getY()),
                        String.format(Locale.ROOT, "%.2f", fp.getZ()), s);
            }
            // CONFIRM the pin reproduced: the buoyant body must NOT have trivially closed to within the tight
            // reach (else there'd be no stall to fix and the A/B would be vacuous). The live pin sat at
            // cur2≈0.546 > REACH_DIST_SQ=0.45; require the OFF leg to have stayed above the tight gate.
            if (minCur2[0] < 0.45)
                throw new GameTestAssertException("waterStepDownFloatArena: the head-wall did NOT reproduce the buoyant pin "
                        + "(OFF minCur2=" + String.format(Locale.ROOT, "%.3f", minCur2[0]) + " < REACH_DIST_SQ=0.45 → the bot reached "
                        + "the node centre on its own, so there is no water-surface stall to exercise). Re-tune the seed/geometry.");
            // OFF must WEDGE — the step-pointer NEVER advances past the water node (the live 12 s+ bob-stall;
            // with replayMode there is no repath, so it pins the entire 200-tick window).
            if (advanced[0])
                throw new GameTestAssertException("waterStepDownFloatArena: with the fix OFF the step-pointer ADVANCED past "
                        + "the water-surface stepDown node (advanceTick=" + advanceTick[0] + ") — the bug did not reproduce; the "
                        + "OFF leg must stay pinned (within needs cur2<0.45, unreachable at the buoyant pin).");
            // ON must ADVANCE promptly — the water-surface float-and-advance relaxation fires once stalled
            // (~WATER_STEPDOWN_STALL_TICKS), well within the window the OFF leg pins forever.
            if (!advanced[1])
                throw new GameTestAssertException("waterStepDownFloatArena: with walkerWaterStepDownFloat ON the step-pointer "
                        + "FAILED to advance past the water-surface stepDown node (pinned " + nodeDwell[1] + " ticks) — the fix did "
                        + "not fire. minCur2_ON=" + String.format(Locale.ROOT, "%.3f", minCur2[1]));
            if (advanceTick[1] > 40)
                throw new GameTestAssertException("waterStepDownFloatArena: with the fix ON the advance was SLOW (advanceTick="
                        + advanceTick[1] + " > 40) — the relaxation should fire shortly after the stall gate (~"
                        + "WATER_STEPDOWN_STALL_TICKS), not drift.");
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.walkerWaterStepDownFloat = owf;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * Deep-water SUBMERGED-crossing surface-bias ({@link BotConfig#pathfinderFloatingSurfaceCross}).
     *
     * <p>The live #47 R3 bob-jam (-832.76,355.78): a buoyant bot ENTERS a deep open-water body already
     * submerged (the seg0 start was [-800,61,390], one below the y62 surface) headed for a Y-AWARE goal
     * ({@code Near[-862,62,300]}). For a Y-aware goal {@code waterCellTax} (XZ-only) is exempt and
     * {@code submergedTax} only prices a DESCENT — but once the bot is already submerged the rest of the
     * crossing is HORIZONTAL (never a fresh descent), so nothing prices it and A* threads the whole
     * 35-block crossing at y61, one cell below the surface (a {@code diag} run). The floating body
     * bobs at the y62 surface ABOVE that y61 path and can't sink to follow it — it jams (hCol, hSpd≈0,
     * cur2≈1.07, |dY|≈1) for ~2.8-3.4 s until a repath happens to re-route on top (seg2 at y62).
     *
     * <p>Geometry: a deep (9-block) open-water channel between two flush wade banks (1-deep shallow ends
     * the bot walks out of). The search is seeded one cell INTO the deep part at surface-1 (submerged,
     * floating) — the deterministic stand-in for the live mid-water submerged entry. A lily pad sits at
     * surface+1 mid-crossing (the live y63 pad) as incidental scenery — the bug is NOT about the pad.
     *
     * <p>Leg A (predicate A/B): with the flag OFF the committed plan must thread MANY submerged
     * (y &lt; surface, water-above) crossing nodes — the bug. With it ON the plan must surface
     * immediately and cross at the top (≤1 submerged node, the start cell only). Leg B (integration):
     * with the flag ON the floating Walker actually crosses and reaches the far bank, spending almost
     * no ticks below the surface (vs the OFF jam). A clean A/B on the planner gate.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void deepWaterSubmergedCrossArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 820, cz = 820, floorY = 200;
        final int surface = floorY + 9;            // y209 deep-water surface (floor at floorY → 9 deep)
        final int spanX = 24;                      // E-W open-water crossing length
        final int halfZ = 5;                       // open water half-width in Z
        // Clear a generous air box.
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1).
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // SHALLOW WADE shelf at the EAST end (floor raised to surface-2 → 1-deep water = grounded; the
        // bot stands on the bottom and walks out flush onto the dry goal bank, no buoyant climb).
        for (int dx = spanX - 1; dx <= spanX; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Dry banks at each end (top at surface-1, flush with the wade shelf) — start ground / goal.
        for (int dx = -5; dx <= -2; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Lily pad at surface+1 mid-crossing (the live -834 y63 pad) — incidental scenery only.
        level.setBlockAndUpdate(new BlockPos(cx + spanX / 2, surface + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        // SUBMERGED search seed: one cell INTO the deep water at surface-1 (floating, submerged) —
        // the live mid-water submerged entry. From here A* has no DESCENT edge to tax.
        BlockPos seed = new BlockPos(cx + 1, surface - 1, cz);
        // Y-AWARE Near goal on the dry east bank (like the live Near[-862,62,300]).
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 4, surface - 1, cz), 1);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        boolean ofsc = BotConfig.pathfinderFloatingSurfaceCross;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = false;          // pure swim/walk — the route choice must be the gate, not a dig
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            // ---- Leg A: predicate A/B on the committed plan ----
            int[] subNodes = new int[2];
            boolean[] reached = new boolean[2];
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.pathfinderFloatingSurfaceCross = (leg == 1);
                ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 1.5, surface - 1, cz + 0.5);
                FakePlayer fp = av.fakePlayer();
                grantWaterEffects(fp);
                fp.getInventory().clearContent();
                LevelWorldView w = new LevelWorldView(level, fp);
                PathFinder.Search s = new PathFinder(w).newSearch(seed, goal);
                s.advance(Long.MAX_VALUE / 2);
                PathFinder.Result r = s.result();
                reached[leg] = r.goalReached();
                for (BlockPos p : r.path())
                    if (p.getY() < surface && w.isWater(p) && w.isWater(p.above())) subNodes[leg]++;
                AgentDriverCommon.LOG.info("[deepWaterSubmergedCrossArena] legA flagOn={} reached={} pathLen={} submergedNodes={}",
                        leg == 1, r.goalReached(), r.path().size(), subNodes[leg]);
            }
            // OFF must reproduce the bug: the committed plan threads the crossing SUBMERGED (many
            // y<surface water-above nodes). A handful is the floor; the live run was 35 — require a
            // clear majority of the ~24-cell span so the bug is unambiguous.
            if (subNodes[0] < 10)
                throw new GameTestAssertException("deepWaterSubmergedCross: with the flag OFF the plan did NOT thread the "
                        + "submerged crossing (submergedNodes=" + subNodes[0] + " < 10) — the bug did not reproduce; the buoyant "
                        + "deep-water entry should route the whole crossing one below the surface. Re-tune the geometry/seed.");
            // ON must surface: at most the start cell stays submerged (it swims up immediately).
            if (subNodes[1] > 1)
                throw new GameTestAssertException("deepWaterSubmergedCross: with pathfinderFloatingSurfaceCross ON the plan STILL "
                        + "threads submerged crossing nodes (submergedNodes=" + subNodes[1] + " > 1) — the surface bias did not fire; "
                        + "A* must surface and cross on top.");
            if (!reached[1])
                throw new GameTestAssertException("deepWaterSubmergedCross: with the flag ON A* failed to reach the goal "
                        + "(the surface route must still solve the crossing).");

            // ---- Leg B: integration — the floating Walker crosses cleanly with the flag ON ----
            BotConfig.pathfinderFloatingSurfaceCross = true;
            BotConfig.walkerDebug = true;
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 1.5, surface - 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(goal);
            Walker.Step st = Walker.Step.WALKING;
            int submergedTicks = 0;
            double maxX = fp.getX();
            for (int t = 0; t < 1200 && st == Walker.Step.WALKING; t++) {
                st = walker.tick(av, w);
                av.step();
                maxX = Math.max(maxX, fp.getX());
                // A surface swimmer's eye dips under the waterline only on a bob; "stuck under" =
                // the FOOT block sitting a full cell below the surface with water still above it.
                BlockPos foot = BlockPos.containing(fp.getX(), fp.getY() + 0.1, fp.getZ());
                if (fp.isInWater() && foot.getY() < surface - 1 && w.isWater(foot.above())) submergedTicks++;
            }
            boolean ashore = !fp.isInWater() && fp.getX() >= cx + spanX + 1 - 0.5;
            AgentDriverCommon.LOG.info("[deepWaterSubmergedCrossArena] legB ashore={} step={} pos=({},{},{}) maxX={} submergedTicks={}",
                    ashore, st, String.format(Locale.ROOT, "%.2f", fp.getX()), String.format(Locale.ROOT, "%.2f", fp.getY()),
                    String.format(Locale.ROOT, "%.2f", fp.getZ()), String.format(Locale.ROOT, "%.2f", maxX), submergedTicks);
            if (!ashore)
                throw new GameTestAssertException("deepWaterSubmergedCross: with the flag ON the floating Walker failed to cross "
                        + "and reach the far bank: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxX=" + maxX + " step=" + st);
            // The surface route keeps the bot on top: it should spend almost no ticks pinned a full
            // cell under the surface (the OFF bug would jam there). Generous bound for transient bobs.
            if (submergedTicks > 40)
                throw new GameTestAssertException("deepWaterSubmergedCross: with the flag ON the bot spent " + submergedTicks
                        + " ticks pinned below the surface (>40) — the surface crossing should keep it on top.");
        } finally {
            BotConfig.allowBreak = ob;
            BotConfig.allowPlace = op;
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderFloatingSurfaceCross = ofsc;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }

    /**
     * Planner A/B for the vine/leaf-canopy-OVER-DEEP-WATER tax ({@link BotConfig#pathfinderVineOverWaterTax},
     * the leaf-canopy/lily-pad planner-tax family extended to vine/leaf-over-water). A tree-canopy grows IN
     * a deep-water crossing: a 1-cell-wide CENTER lane (z=cz) carries, over several X columns, hanging VINES
     * at body height (surface+1) + an OAK-LEAF canopy above (surface+2/+3) + a LILY pad (surface+1) — the
     * live #47 ~-780,339 cluster (oak_leaves y64-65 + hanging vines y63-64 + lily pads over deep water). The
     * water on either side of the lane (z=cz±1..±2) is CLEAR (open sky). A* crossing E→W toward the far bank
     * has two route classes: the straight CENTER line (z=cz, shortest) threads the vine/leaf column, or a
     * one-cell side deflection (z=cz±1) swims clear water around the tree.
     *
     * <p>None of the existing taxes price the center lane: {@code waterCellTax}/{@code submergedTax} see only
     * the surface water cell (air-like overhead at z=cz where the vine is, since a vine has no fluid), {@code
     * leafCellTax} checks {@code isLeaves(foot.above())} but the body cell is a VINE (not #minecraft:leaves)
     * and the leaf canopy sits TWO up, and {@code padCellTax} needs a COLLIDING instabreak block (a vine has
     * no collision shape). So with the tax OFF the planner threads the cheapest straight line right through
     * the vine/leaf column — the bob-jam — and with it ON the center lane costs +{@code pathfinderLeafCellCost}
     * per obstructed cell, tipping A* onto the clear side lane AROUND the tree.
     *
     * <p>Pure planner, unbounded, deterministic. Asserts: OFF routes ≥1 path node INTO the vine/leaf center
     * lane (bug reproduces); ON routes ZERO (detours around) and STILL reaches the goal (a tax, not a forbid).
     * #1 silent-no-op guards confirm the geometry actually carries the vine/leaf body obstruction the live
     * bug needs (else the A/B would be vacuous): the center body cell is climbable (a vine) and is NOT seen
     * by the sibling taxes (not leaves, not a breakable obstruction).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void vineOverWaterCrossArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 880, cz = 880, floorY = 200;
        final int surface = floorY + 8;            // y208 deep-water surface (floor at floorY → 8 deep)
        final int spanX = 16;                      // E-W open-water crossing length
        final int halfZ = 3;                       // open water half-width in Z (room for a side detour)
        // Tree-canopy band over the CENTER lane: dx from treeX0..treeX1 (mid-crossing), z=cz only.
        final int treeX0 = 5, treeX1 = 11;

        // Clear a generous air box (shared level — wipe any neighbour residue).
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1), the full Z width.
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Dry banks at each end, top at surface-1 → the walkable bank FOOT is `surface`, FLUSH with the
        // floating water foot (also `surface`) so the bot walks on/off the surface-level crossing with NO
        // +1 buoyant bank-climb (a floating-water +1 climb-out is structurally forbidden and would make the
        // goal unreachable — that's the bank-height the deepWaterCross/Submerged arenas use). Start = west
        // bank, goal = east bank, both at z=cz so the STRAIGHT E-W line is the through-the-tree route.
        for (int dx = -5; dx <= -1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // GROUNDED wade shelf one cell into the water at each bank (floor raised to surface-1 → 1-deep water
        // at `surface`): a solid floor under the foot makes the transition cell a GROUNDED shallow step (not
        // floating water), so the bot steps flush between bank and water with no buoyant snag. Mirrors the
        // east wade shelf in deepWaterSubmergedCrossArena.
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface - 1, cz + dz), Blocks.STONE.defaultBlockState());

        // TREE-CANOPY band over the center lane (z=cz), treeX0..treeX1. At each column:
        //   surface+1 (body cell)  = hanging VINE  (climbable, NO collision, NOT #leaves) → the body
        //                            obstruction a floating bot rams; ALSO a LILY pad scenery cell handled
        //                            below via surface+1 on the WATER plane is distinct — the vine occupies
        //                            the AIR cell directly above the water surface.
        //   surface+2, surface+3   = OAK-LEAF canopy (full-collision leaves) — the leaf cap TWO+ above foot.
        // The vine hangs DOWN from the leaf above (UP=true on the lower vine cell so it survives the update),
        // matching a leaf-draped jungle/oak vine. The water cell at surface stays WATER (the foot cell).
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, Boolean.TRUE);
        BlockState vineHang = Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, Boolean.TRUE);
        for (int dx = treeX0; dx <= treeX1; dx++) {
            // Leaf canopy first (top-down) so the vine below has something to hang from.
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 3, cz), leaf);
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 2, cz), leaf);
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz), vineHang);   // body-level vine
            // Lily pad on the water surface beneath the canopy (the live pads), at the foot+? plane: place it
            // one cell off-center so it never SOLIDIFIES the foot cell the bot needs (a pad sits on water at
            // surface+1, but here surface+1 center is the vine — so the pads go at z=cz±2, incidental scenery
            // proving the cluster is a real tree-over-water, NOT changing the center-lane vine repro).
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz - 2), Blocks.LILY_PAD.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz + 2), Blocks.LILY_PAD.defaultBlockState());
        }

        // Start on the WEST dry bank, goal on the EAST dry bank — both at FOOT y=`surface` (bank top at
        // surface-1), flush with the water crossing foot, z=cz so the straight E-W line is the through-the-
        // tree route. Goal.Near (Y-aware land target, like the live Near[-862,62,300]).
        BlockPos start = new BlockPos(cx - 3, surface, cz);
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        boolean odbg = BotConfig.walkerDebug;
        boolean ovow = BotConfig.pathfinderVineOverWaterTax;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 2.5, surface, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);
        try {
            // #1 silent-no-op guards: the center body cell must carry the vine/leaf obstruction the live bug
            // needs, and it must be INVISIBLE to the sibling taxes (else the A/B is vacuous / over-attributed).
            BlockPos bodyMid = new BlockPos(cx + (treeX0 + treeX1) / 2, surface + 1, cz);   // a vine cell
            BlockPos capMid  = new BlockPos(cx + (treeX0 + treeX1) / 2, surface + 2, cz);    // a leaf cell
            if (!w.isClimbable(bodyMid))
                throw new GameTestAssertException("vineOverWaterCross: center body cell is NOT climbable (vine) at "
                        + bodyMid + " — the vine did not survive setBlockAndUpdate; the repro is vacuous.");
            if (w.isLeaves(bodyMid))
                throw new GameTestAssertException("vineOverWaterCross: center body cell reads as LEAVES at " + bodyMid
                        + " — leafCellTax would already catch it and the new tax would be redundant; expected a VINE.");
            if (w.isBreakableObstruction(bodyMid))
                throw new GameTestAssertException("vineOverWaterCross: center body vine reads as a breakable obstruction at "
                        + bodyMid + " — padCellTax would already catch it; a vine must have no collision shape.");
            if (!w.isLeaves(capMid))
                throw new GameTestAssertException("vineOverWaterCross: leaf canopy missing at " + capMid + ".");

            // A/B: OFF threads the center lane (bug), ON detours around it (fix). Count path nodes whose foot
            // is a WATER cell in the center lane (z=cz) UNDER the canopy band — those are the through-the-tree
            // crossing cells. (Dry bank cells at z=cz are not water, so they don't count.)
            int[] laneNodes = new int[2];
            boolean[] reached = new boolean[2];
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.pathfinderVineOverWaterTax = (leg == 1);
                PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
                s.advance(Long.MAX_VALUE / 2);
                PathFinder.Result r = s.result();
                reached[leg] = r.goalReached();
                for (BlockPos p : r.path()) {
                    int rx = p.getX() - cx, rz = p.getZ() - cz;
                    if (rz == 0 && rx >= treeX0 && rx <= treeX1 && w.isWater(p)) laneNodes[leg]++;
                }
                AgentDriverCommon.LOG.info("[vineOverWaterCrossArena] flagOn={} reached={} pathLen={} centerLaneNodes={}",
                        leg == 1, r.goalReached(), r.path().size(), laneNodes[leg]);
            }
            // OFF must reproduce the bug: the straight plan threads the center lane through the tree (the
            // shortest line is the through-the-canopy route, so it crosses several of the treeX0..treeX1 cells).
            if (laneNodes[0] < 3)
                throw new GameTestAssertException("vineOverWaterCross: with the tax OFF the plan did NOT thread the "
                        + "vine/leaf center lane (centerLaneNodes=" + laneNodes[0] + " < 3) — the bug did not reproduce; "
                        + "the straight crossing should pass through the canopy band. Re-tune the geometry.");
            // ON must detour around: ZERO center-lane water nodes (it swims the clear side lane).
            if (laneNodes[1] != 0)
                throw new GameTestAssertException("vineOverWaterCross: with pathfinderVineOverWaterTax ON the plan STILL "
                        + "threads the vine/leaf center lane (centerLaneNodes=" + laneNodes[1] + " > 0) — the tax did not "
                        + "deflect A* around the tree-over-water cluster.");
            if (!reached[1])
                throw new GameTestAssertException("vineOverWaterCross: with the tax ON A* failed to reach the goal — the "
                        + "tax must DETOUR around the tree, never forbid the crossing (a clear side lane exists).");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderVineOverWaterTax = ovow;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
        }
        helper.succeed();
    }

    /**
     * Planner A/B for the SPARSE-single-lily-pad-OVER-DEEP-WATER tax ({@link BotConfig#pathfinderPadOverWaterTax}),
     * the Y-aware-goal sibling of the XZ-only {@code padCellTax}. A deep OPEN-water crossing is dotted with a
     * row of ISOLATED single lily pads on the straight center line (z=cz): at three mid-crossing columns a lone
     * LILY pad sits on the water surface (surface+1, the floating bot's body cell), each pad 1-wide with CLEAR
     * water on both sides in Z (z=cz±1..±2 open sky) — the live #47 corridor x[-875,-706] z[280,390] where
     * sparse single pads dig-stall the float at -830,363 / -817,298 / -849,364. A* crossing E→W toward the far
     * bank has two route classes: the straight CENTER line (z=cz) threads each lone pad, or a one-cell side
     * deflection (z=cz±1) swims the clear water around it.
     *
     * <p>Root cause this guards: the existing {@code padCellTax} prices THIS EXACT geometry (a breakable pad
     * over a water foot) but is gated to XZ goals ({@code goal.ignoresY()} — it mirrors {@code waterCellTax}'s
     * triple-gate, and the dense 睡莲池 pool A/B that validated it crossed on a bare-column XZ swim goal). A real
     * {@code mc.bot.goto x,y,z} resolves to a Y-AWARE goal ({@code Goal.Block}/{@code Goal.Near}), for which
     * {@code padCellTax} returns 0 — so over an open corridor of sparse single pads it never fires and A*
     * threads the cheapest straight line right through each pad (a 1-pad instabreak dig is cheaper than a
     * 1-block detour). {@code vineOverWaterTax} doesn't catch it either: a lily pad is neither {@code isLeaves}
     * nor {@code isClimbable} (it has a thin floor collision shape → it IS an {@code isBreakableObstruction}).
     * With the new tax ON the center line costs +{@code pathfinderLilyPadCellCost} per pad cell, tipping A*
     * onto the clear side lane AROUND each lone pad.
     *
     * <p>Pure planner, unbounded, deterministic, Y-AWARE goal ({@code Goal.Near}, like the live
     * {@code Near[-862,62,300]} — this is the case the XZ-gated {@code padCellTax} provably misses). #1
     * silent-no-op guards confirm the geometry actually carries the pad obstruction the live bug needs AND
     * that the XZ-gated {@code padCellTax} truly does NOT fire on this Y-aware goal (else the A/B would be
     * over-attributed): the pad body cell is a breakable obstruction, and {@code padCellTax} returns 0 for the
     * goal. Asserts: OFF routes ≥1 path node INTO a pad center-lane cell (bug reproduces); ON routes ZERO
     * (detours around) and STILL reaches the goal (a tax, not a forbid).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void padOverWaterCrossArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 920, cz = 880, floorY = 200;
        final int surface = floorY + 8;            // y208 deep-water surface (floor at floorY → 8 deep)
        final int spanX = 16;                      // E-W open-water crossing length
        final int halfZ = 3;                       // open water half-width in Z (room for a side detour)
        // Sparse single pads on the CENTER line (z=cz), at these mid-crossing columns (each isolated, 1-wide).
        final int[] padDx = { 6, 9, 12 };

        // Clear a generous air box (shared level — wipe any neighbour residue).
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1), the full Z width.
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Dry banks at each end, top at surface-1 → the walkable bank FOOT is `surface`, FLUSH with the
        // floating water foot (also `surface`) so the bot walks on/off the surface-level crossing with NO
        // +1 buoyant bank-climb (mirrors vineOverWaterCrossArena). Start = west bank, goal = east bank, both
        // at z=cz so the STRAIGHT E-W line is the through-the-pads route.
        for (int dx = -5; dx <= -1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // GROUNDED wade shelf one cell into the water at each bank (floor raised to surface-1 → 1-deep water
        // at `surface`): a solid floor under the foot makes the transition cell a GROUNDED shallow step so the
        // bot steps flush between bank and water with no buoyant snag. Mirrors vineOverWaterCrossArena.
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface - 1, cz + dz), Blocks.STONE.defaultBlockState());

        // Sparse SINGLE lily pads on the center line: one pad per column in padDx[], at the WATER surface
        // (surface+1 = the floating bot's body cell), z=cz only. Each is isolated — clear water at z=cz±1.
        for (int dx : padDx)
            level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        // Start on the WEST dry bank, goal on the EAST dry bank — both at FOOT y=`surface` (bank top at
        // surface-1), flush with the water crossing foot, z=cz so the straight E-W line is the through-the-
        // pads route. Goal.Near (Y-aware land target, like the live Near[-862,62,300]) — the case padCellTax
        // (XZ-gated) provably MISSES, which is exactly why the goal-neutral pad-over-water tax is needed.
        BlockPos start = new BlockPos(cx - 3, surface, cz);
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        boolean odbg = BotConfig.walkerDebug;
        boolean opow = BotConfig.pathfinderPadOverWaterTax;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 2.5, surface, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);
        try {
            // #1 silent-no-op guards: the pad body cell must carry the breakable-obstruction the live bug
            // needs, AND the XZ-gated padCellTax must NOT fire on this Y-aware goal (else the new tax is
            // redundant / the A/B is over-attributed to it).
            BlockPos padBody = new BlockPos(cx + padDx[1], surface + 1, cz);   // a lily-pad cell
            BlockPos padFoot = new BlockPos(cx + padDx[1], surface, cz);       // the water cell under it
            if (!w.isWater(padFoot))
                throw new GameTestAssertException("padOverWaterCross: foot under the pad is NOT water at " + padFoot
                        + " — the pad did not land on a water surface cell; the repro is vacuous.");
            if (!w.isBreakableObstruction(padBody))
                throw new GameTestAssertException("padOverWaterCross: pad body cell is NOT a breakable obstruction at "
                        + padBody + " — the lily pad did not survive setBlockAndUpdate; the repro is vacuous.");
            if (goal.ignoresY())
                throw new GameTestAssertException("padOverWaterCross: the goal reads as ignoresY (XZ) — padCellTax "
                        + "would already fire and the new goal-neutral tax would be redundant; expected a Y-aware goal.");

            // A/B: OFF threads the center lane through the pads (bug), ON detours around them (fix). Count path
            // nodes whose foot is a WATER cell directly UNDER one of the sparse pads (z=cz, dx in padDx) — those
            // are the through-the-pad crossing cells. (Dry bank cells at z=cz are not water, so they don't
            // count; clear side-lane water at z=cz±1 is not under a pad, so it doesn't count either.)
            int[] padNodes = new int[2];
            boolean[] reached = new boolean[2];
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.pathfinderPadOverWaterTax = (leg == 1);
                PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
                s.advance(Long.MAX_VALUE / 2);
                PathFinder.Result r = s.result();
                reached[leg] = r.goalReached();
                for (BlockPos p : r.path()) {
                    if (p.getZ() != cz || !w.isWater(p)) continue;
                    int rx = p.getX() - cx;
                    for (int pd : padDx) if (rx == pd) { padNodes[leg]++; break; }
                }
                AgentDriverCommon.LOG.info("[padOverWaterCrossArena] flagOn={} reached={} pathLen={} padLaneNodes={}",
                        leg == 1, r.goalReached(), r.path().size(), padNodes[leg]);
            }
            // OFF must reproduce the bug: the straight plan threads ≥1 pad cell (the shortest line crosses the
            // center lane, and a pad-dig is cheaper than a per-pad 1-block detour, so it passes through pads).
            if (padNodes[0] < 1)
                throw new GameTestAssertException("padOverWaterCross: with the tax OFF the plan did NOT thread any "
                        + "pad center-lane cell (padLaneNodes=" + padNodes[0] + " < 1) — the bug did not reproduce; "
                        + "the straight crossing should pass through the sparse pads. Re-tune the geometry.");
            // ON must detour around: ZERO pad center-lane nodes (it swims the clear side lane around each pad).
            if (padNodes[1] != 0)
                throw new GameTestAssertException("padOverWaterCross: with pathfinderPadOverWaterTax ON the plan STILL "
                        + "threads a pad center-lane cell (padLaneNodes=" + padNodes[1] + " > 0) — the tax did not "
                        + "deflect A* around the sparse single pads.");
            if (!reached[1])
                throw new GameTestAssertException("padOverWaterCross: with the tax ON A* failed to reach the goal — the "
                        + "tax must DETOUR around the pads, never forbid the crossing (a clear side lane exists).");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderPadOverWaterTax = opow;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
        }
        helper.succeed();
    }

    /**
     * Planner A/B for the ADJACENT-pad-CLUSTER tax ({@link BotConfig#pathfinderPadClusterTax}), the cluster
     * refinement of the just-shipped single-pad {@link BotConfig#pathfinderPadOverWaterTax}. This guards the
     * SOLE remaining 1/12 jank of the #47 silky-pathfinding acceptance: the flat single-pad tax detours A*
     * around a LONE pad, but for an ADJACENT pad PAIR / cluster the cheapest CLEAR lane sits ≥2 cells off the
     * crossing line, so the full detour costs MORE than digging ONE pad — and the flat tax (a tax, NOT a
     * forbid) lets A* pick the lesser evil: it threads (and the floating bot rams + hand-digs) one pad of the
     * cluster (~4 s, attack=true; the live adjacent pads -850/-851,323 / -750/-751,334-335 in the 23-pad
     * scatter).
     *
     * <p><b>Cost model</b> (Walk=10, Diagonal=14 → a 1-cell side-deflection costs +4 in / +4 out = +8 of turn
     * penalty per cell of Z-excursion; the flat pad tax = {@code pathfinderLilyPadCellCost}=20). The cluster
     * here is a pad WALL one X-column thick spanning {@code z=cz-3..cz+3} (7 pads), with the only CLEAR water
     * lanes at {@code z=cz±4}. Crossing the wall on the straight line digs ONE pad (+20); the full go-around to
     * {@code z=cz±4} is a 4-deep Z-excursion = 8 diagonals = +32. With ONLY the flat tax the dig (+20) is
     * cheaper than the detour (+32), so A* threads ONE pad of the wall (the bug). With the cluster surcharge
     * the wall's centre pad is adjacent to 2 sibling pads (N+S) → tax scales to 20·(1+2)=60, so the dig (+60)
     * now costs more than the detour (+32) and A* swims fully around — while a LONE pad keeps the flat 20 (the
     * single-pad sub-check below proves no over-detour).
     *
     * <p>Pure planner, unbounded, deterministic, Y-AWARE goal ({@code Goal.Near}, like the live
     * {@code Near[-862,62,300]}). The single-pad tax ({@code pathfinderPadOverWaterTax}) is ON for BOTH legs so
     * the A/B isolates the CLUSTER flag (no re-attribution of the shipped single-pad win). #1 silent-no-op
     * guards confirm the wall carries the pad obstruction the bug needs. Asserts, in three regions of one
     * arena: (1) the CLUSTER WALL — cluster OFF threads ≥1 wall pad node (bug), cluster ON routes ZERO (detour)
     * and still reaches; (2) a LONE sparse pad — cluster ON STILL detours it (no single-pad regression); (3) a
     * FULL-WIDTH wall with no clear lane — cluster ON STILL reaches (a tax, never a forbid → no stranding).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void padClusterCrossArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 960, cz = 880, floorY = 200;
        final int surface = floorY + 8;            // y208 deep-water surface (floor at floorY → 8 deep)
        final int spanX = 16;                      // E-W open-water crossing length
        final int halfZ = 6;                       // open water half-width in Z: room for a 4-deep detour (cz±4)
        final int wallDx = 8;                      // the pad WALL sits at this mid-crossing X column
        final int wallHalfZ = 3;                   // wall spans z=cz-3..cz+3 (7 pads) → clear lanes only at cz±4

        // Clear a generous air box (shared level — wipe any neighbour residue).
        for (int dx = -5; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 2; dz <= halfZ + 2; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) under the whole arena.
        for (int dx = -3; dx <= spanX + 7; dx++)
            for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water filling the basin up to `surface` (dx=-1..spanX+1), the full Z width.
        for (int dx = -1; dx <= spanX + 1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Dry banks at each end, top at surface-1 → the walkable bank FOOT is `surface`, FLUSH with the
        // floating water foot (also `surface`) so the bot walks on/off with NO +1 buoyant bank-climb. Start =
        // west bank, goal = east bank, both at z=cz so the STRAIGHT E-W line is the through-the-wall route.
        for (int dx = -5; dx <= -1; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = spanX + 1; dx <= spanX + 6; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                for (int y = floorY + 1; y <= surface - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // GROUNDED wade shelf one cell into the water at each bank (floor raised to surface-1 → 1-deep water at
        // `surface`): a solid floor under the foot makes the transition a GROUNDED shallow step (no buoyant
        // snag). Mirrors padOverWaterCrossArena.
        for (int dx = -1; dx <= spanX + 1; dx += spanX + 2)
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface - 1, cz + dz), Blocks.STONE.defaultBlockState());

        // The ADJACENT pad CLUSTER: a WALL of lily pads one X-column thick at wallDx, spanning z=cz-3..cz+3 (7
        // pads side-by-side in Z), at the water surface (surface+1 = the floating bot's body cell). The only
        // CLEAR water lanes are z=cz±4, a 4-deep Z-excursion off the straight center line — so a single
        // wall-pad dig (+20) beats the detour (+32) under the flat tax, but the cluster surcharge (×3 at the
        // centre pad) flips it. (Sibling adjacency in the wall is what the cluster tax keys on.)
        for (int dz = -wallHalfZ; dz <= wallHalfZ; dz++)
            level.setBlockAndUpdate(new BlockPos(cx + wallDx, surface + 1, cz + dz), Blocks.LILY_PAD.defaultBlockState());

        // A LONE sparse pad farther east on the center line (z=cz), isolated with clear water all around — the
        // no-regression control: the cluster tax must leave it at the flat 20 and STILL detour it by 1 cell.
        final int loneDx = 13;
        level.setBlockAndUpdate(new BlockPos(cx + loneDx, surface + 1, cz), Blocks.LILY_PAD.defaultBlockState());

        // Start on the WEST dry bank, goal on the EAST dry bank — both at FOOT y=`surface`, z=cz so the straight
        // E-W line is the through-the-wall route. Goal.Near (Y-aware land target, like Near[-862,62,300]).
        BlockPos start = new BlockPos(cx - 3, surface, cz);
        Goal goal = new Goal.Near(new BlockPos(cx + spanX + 3, surface, cz), 1);

        boolean odbg = BotConfig.walkerDebug;
        boolean opow = BotConfig.pathfinderPadOverWaterTax;
        boolean opct = BotConfig.pathfinderPadClusterTax;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        int omn = BotConfig.pathfinderMaxNodes;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each search completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxNodes = 1_000_000;
        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 2.5, surface, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        fp.getInventory().clearContent();
        LevelWorldView w = new LevelWorldView(level, fp);
        try {
            // #1 silent-no-op guards: the wall must carry the pad obstruction (a breakable obstruction over a
            // water foot) the live bug needs, and the goal must be Y-aware (so the XZ-gated padCellTax does NOT
            // fire — else the A/B would be over-attributed to it).
            BlockPos wallBody = new BlockPos(cx + wallDx, surface + 1, cz);   // the wall's centre lily-pad cell
            BlockPos wallFoot = new BlockPos(cx + wallDx, surface, cz);       // the water cell under it
            BlockPos wallSibN = new BlockPos(cx + wallDx, surface + 1, cz + 1);
            if (!w.isWater(wallFoot))
                throw new GameTestAssertException("padClusterCross: foot under the wall is NOT water at " + wallFoot
                        + " — the repro is vacuous.");
            if (!w.isBreakableObstruction(wallBody) || !w.isBreakableObstruction(wallSibN))
                throw new GameTestAssertException("padClusterCross: wall pad cells are NOT breakable obstructions ("
                        + wallBody + " / " + wallSibN + ") — the lily-pad wall did not survive setBlockAndUpdate; "
                        + "the repro is vacuous.");
            if (goal.ignoresY())
                throw new GameTestAssertException("padClusterCross: the goal reads as ignoresY (XZ) — padCellTax would "
                        + "already fire; expected a Y-aware goal.");

            // ---- REGION 1: the CLUSTER WALL A/B (single-pad tax ON for BOTH legs; CLUSTER flag is the variable).
            // Count path nodes whose foot is a WATER cell under a wall pad (x=wallDx, z in cz-2..cz+2): those are
            // the through-the-wall dig cells. (Dry bank cells aren't water; clear side-lane cells at cz±3 aren't
            // under a wall pad.)
            BotConfig.pathfinderPadOverWaterTax = true;        // the shipped single-pad fix stays ON for the A/B
            int[] wallNodes = new int[2];
            boolean[] reached = new boolean[2];
            for (int leg = 0; leg < 2; leg++) {
                BotConfig.pathfinderPadClusterTax = (leg == 1);
                PathFinder.Search s = new PathFinder(w).newSearch(start, goal);
                s.advance(Long.MAX_VALUE / 2);
                PathFinder.Result r = s.result();
                reached[leg] = r.goalReached();
                for (BlockPos p : r.path()) {
                    if (p.getX() - cx != wallDx || !w.isWater(p)) continue;
                    int rz = p.getZ() - cz;
                    if (rz >= -wallHalfZ && rz <= wallHalfZ) wallNodes[leg]++;
                }
                AgentDriverCommon.LOG.info("[padClusterCrossArena] region=wall clusterOn={} reached={} pathLen={} wallPadNodes={}",
                        leg == 1, r.goalReached(), r.path().size(), wallNodes[leg]);
            }
            // CLUSTER OFF must reproduce the bug: the flat single-pad tax lets the +20 dig of ONE wall pad beat
            // the +24 three-deep detour, so the plan threads ≥1 wall pad cell.
            if (wallNodes[0] < 1)
                throw new GameTestAssertException("padClusterCross: with the cluster tax OFF (flat single-pad tax only) "
                        + "the plan did NOT thread any wall pad cell (wallPadNodes=" + wallNodes[0] + " < 1) — the "
                        + "adjacent-cluster bug did not reproduce; the single dig should be cheaper than the 3-deep "
                        + "detour. Re-tune the wall span / lane offset.");
            // CLUSTER ON must detour fully: ZERO wall pad nodes (the surcharge makes the detour cheaper).
            if (wallNodes[1] != 0)
                throw new GameTestAssertException("padClusterCross: with pathfinderPadClusterTax ON the plan STILL "
                        + "threads a wall pad cell (wallPadNodes=" + wallNodes[1] + " > 0) — the cluster surcharge did "
                        + "not deflect A* around the adjacent-pad wall.");
            if (!reached[1])
                throw new GameTestAssertException("padClusterCross: with the cluster tax ON A* failed to reach the goal "
                        + "— the surcharge must DETOUR around the wall, never forbid the crossing (clear lanes at cz±3).");

            // ---- REGION 2: the LONE sparse pad (no single-pad regression). With the cluster tax ON, a lone pad
            // has 0 adjacent pads → flat 20 → A* must STILL detour it by one cell (0 lone-pad nodes). Re-run the
            // SAME full search (cluster ON) and confirm the lone pad cell carries no path node either.
            BotConfig.pathfinderPadClusterTax = true;
            PathFinder.Result rLone = new PathFinder(w).findPath(start, goal);
            int loneNodes = 0;
            for (BlockPos p : rLone.path())
                if (p.getX() - cx == loneDx && p.getZ() == cz && w.isWater(p)) loneNodes++;
            AgentDriverCommon.LOG.info("[padClusterCrossArena] region=lone clusterOn=true reached={} lonePadNodes={}",
                    rLone.goalReached(), loneNodes);
            if (loneNodes != 0)
                throw new GameTestAssertException("padClusterCross: with the cluster tax ON the plan threads the LONE "
                        + "sparse pad (lonePadNodes=" + loneNodes + " > 0) — the cluster surcharge must NOT change "
                        + "single-pad routing; a lone pad should still be detoured at the flat tax.");

            // ---- REGION 3: NO clear lane (no stranding). Fill the ENTIRE Z width at a fresh X column with pads
            // (a full-width pad wall, no gap), then confirm a search to the far bank STILL reaches with the
            // cluster tax ON — a tax, never a forbid: when there is no clear lane A* threads the shortest line
            // through (every cell carries the same scaled tax), nothing becomes unreachable.
            final int fullDx = 4;
            for (int dz = -halfZ; dz <= halfZ; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + fullDx, surface + 1, cz + dz), Blocks.LILY_PAD.defaultBlockState());
            PathFinder.Result rFull = new PathFinder(w).findPath(start, goal);
            // Count nodes that cross the full-width wall column (some pad MUST be threaded — there's no gap).
            int fullCross = 0;
            for (BlockPos p : rFull.path())
                if (p.getX() - cx == fullDx && w.isWater(p)) fullCross++;
            AgentDriverCommon.LOG.info("[padClusterCrossArena] region=fullwall clusterOn=true reached={} crossNodes={}",
                    rFull.goalReached(), fullCross);
            if (!rFull.goalReached())
                throw new GameTestAssertException("padClusterCross: with a FULL-WIDTH pad wall (no clear lane) the "
                        + "cluster tax made the goal UNREACHABLE — it must be a TAX, never a forbid; A* must still "
                        + "thread the shortest line through (no stranding).");
            if (fullCross < 1)
                throw new GameTestAssertException("padClusterCross: the full-width-wall search reached the goal WITHOUT "
                        + "crossing the wall column (crossNodes=" + fullCross + ") — the no-lane region is not testing "
                        + "the through-the-wall thread; re-check the geometry.");
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderPadOverWaterTax = opow;
            BotConfig.pathfinderPadClusterTax = opct;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
            BotConfig.pathfinderMaxNodes = omn;
        }
        helper.succeed();
    }

    /**
     * DEEP-WATER-FLOAT BEE-LINE anchor-gate exemption (live #47, {@link BotConfig#walkerDeepWaterFloatBeeline}).
     *
     * <p><b>The live dead-stop (deterministic, ~6 s).</b> A bot floating in DEEP open water with a FAR
     * open-water goal: the planner commits a BEST-EFFORT segment that string-pulls to {@code …[swimUp]·far
     * [walk]} (an IN-PLACE {@code swimUp} at the foot, then one long {@code walk} node tens of blocks east).
     * The eager continuation from that segment's {@code commitEnd} lands while the bot is still back at the
     * start, so its first node is tens of blocks from the foot and the segment anchor-gate (the
     * nearest-prefix-node {@code d2} check) REJECTS it as {@code mis-anchored} ({@code d2≈2300}). The reject
     * drops the path; the foot-search returns the SAME best-effort; it re-rejects → a repath storm,
     * {@code hSpd=0}, the bot never commits a forward path and never drives east, and anti-spin ends
     * best-effort (telemetry: {@code reject mis-anchored: -812,62 (d2=2305) vs foot -860,58}).
     *
     * <p><b>Why this is a UNIT assertion, not a full Walker-drive A/B.</b> The headless server-sim does NOT
     * reproduce the dead-stop: the sim's buoyancy + clean tick cadence let the floating bot surface and
     * sprint-swim a flat open-water crossing before the continuation-reject storm ever bites (verified — a
     * real-Walker long-channel drive ARRIVES with the fix OFF), and the sim's A* even places the
     * {@code swimUp} at the FAR end of the crossing rather than in-place at the start. So a full-drive arena
     * can't give the A/B (per the openwater-churn memory: live/replay is truth for water, the arena is
     * derived). Instead this exercises the FIX'S DECISION POINT directly via {@link Walker#adoptForTest}:
     * the segment anchor-gate's accept/reject verdict on the exact far-open-water continuation geometry.
     * The parent's live reload runs the end-to-end A/B.
     *
     * <p><b>Asserts</b>: (1) OFF — the far open-water continuation is REJECTED (the dead-stop root); (2) ON
     * — the SAME continuation is ACCEPTED (the fix) and the step drives toward the far node; (3) ON is
     * INERT when it must be — a continuation whose straight line is WALLED by stone is STILL rejected (the
     * exemption can't be abused for a truly mis-anchored / walled segment), and a continuation from a DRY
     * foot is STILL rejected (the exemption requires a floating foot).
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void deepWaterFloatBeelineArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Disjoint absolute region (shared level — see sheerWallArena), beyond every other footprint.
        final int cx = 1000, cz = 1000, floorY = 200, depth = 8;
        final int surface = floorY + depth;        // y208 deep-water surface
        final int spanX = 56;                      // E-W open-water crossing length (the far node is ~48 b out)

        // Clear a generous air box (shared level — wipe any neighbour residue).
        for (int dx = -6; dx <= spanX + 16; dx++)
            for (int dz = -5; dz <= 5; dz++)
                for (int y = floorY - 1; y <= surface + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // DEEP basin floor (floorY) + N/S walls + a west cap to hold the water.
        for (int dx = -5; dx <= spanX + 16; dx++)
            for (int dz = -4; dz <= 4; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = -4; dx <= spanX + 1; dx++)
            for (int y = floorY + 1; y <= surface + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
            }
        for (int dz = -3; dz <= 3; dz++)
            for (int y = floorY + 1; y <= surface + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 4, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep open water filling the basin up to `surface` — a wide-open clear-LOS channel.
        for (int dx = -3; dx <= spanX; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());

        // The deep-water-float dead-stop geometry. The CONTINUATION the live walker tries to adopt is
        // computed from the previous best-effort segment's commitEnd (the live -812), so its path[0] is that
        // FAR node — tens of blocks from where the floating bot still sits (the live foot -860). That is why
        // the anchor-gate's nearest-prefix-node is the far node (d2≈2300) and it rejects. We reproduce that
        // EXACT input: foot at the start, a continuation [farNode, beyond, goal] whose first node is ~48 b
        // east over clear open water (mirrors live "reject mis-anchored: -812,62 (d2=2305) vs foot -860").
        final int waterFootY = surface;                       // a surface water cell (floating foot rides here)
        BlockPos foot    = new BlockPos(cx, waterFootY, cz);
        BlockPos farNode = new BlockPos(cx + 48, waterFootY, cz);          // continuation path[0] — ~48 b east
        BlockPos beyond  = new BlockPos(cx + 52, waterFootY, cz);
        BlockPos goalN   = new BlockPos(cx + spanX, waterFootY, cz);

        List<BlockPos> cont = List.of(farNode, beyond, goalN);
        List<Move.Edge> contEdges = List.of(
                new Move.Edge(farNode, 50, List.of(), List.of(), "walk"),
                new Move.Edge(beyond,  10, List.of(), List.of(), "walk"),
                new Move.Edge(goalN,   30, List.of(), List.of(), "walk"));

        boolean obee = BotConfig.walkerDeepWaterFloatBeeline, odbg = BotConfig.walkerDebug;
        BotConfig.walkerDebug = false;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, waterFootY, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            LevelWorldView w = new LevelWorldView(level, fp);

            // Vacuity guards: the geometry must carry the dead-stop shape.
            if (!w.isWater(foot) || !w.isWater(foot.below()))
                throw new GameTestAssertException("deepWaterFloatBeeline: foot " + foot + " is not buoyant deep water "
                        + "(water at it AND below) — the float pin is vacuous.");
            if (!w.isWater(farNode))
                throw new GameTestAssertException("deepWaterFloatBeeline: far node " + farNode + " is not a water cell "
                        + "— the open-water bee-line target is vacuous.");
            double d2 = farNode.distSqr(foot);
            if (d2 < 64)
                throw new GameTestAssertException("deepWaterFloatBeeline: far node only d2=" + d2 + " from the foot — not a "
                        + "long open-water crossing (need ≥ the 64 reject gate). Lengthen spanX.");

            // (1) OFF: the far open-water continuation must be REJECTED (the live dead-stop root).
            BotConfig.walkerDeepWaterFloatBeeline = false;
            Walker wOff = new Walker();
            boolean acceptOff = wOff.adoptForTest(w, cont, contEdges, foot);
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] OFF accept={} (expect false: anchor-gate rejects mis-anchored)", acceptOff);
            if (acceptOff)
                throw new GameTestAssertException("deepWaterFloatBeeline: with the fix OFF the anchor-gate ACCEPTED the far "
                        + "open-water continuation (d2=" + (int) d2 + ") — the mis-anchored reject (the live dead-stop root) did "
                        + "not reproduce. Baseline broken.");

            // (2) ON: the SAME continuation must be ACCEPTED and the step driven toward the far node.
            BotConfig.walkerDeepWaterFloatBeeline = true;
            Walker wOn = new Walker();
            boolean acceptOn = wOn.adoptForTest(w, cont, contEdges, foot);
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] ON accept={} step={} node={} (expect true: clear-LOS open-water bee-line)",
                    acceptOn, wOn.pathStep(), wOn.pathNode());
            if (!acceptOn)
                throw new GameTestAssertException("deepWaterFloatBeeline: with walkerDeepWaterFloatBeeline ON the anchor-gate "
                        + "STILL rejected the clear-LOS open-water continuation — the bee-line exemption did not fire.");
            BlockPos drive = wOn.pathNode();
            if (drive == null || drive.getX() <= foot.getX())
                throw new GameTestAssertException("deepWaterFloatBeeline: after accepting, the step does not drive EAST toward "
                        + "the far node (step node=" + drive + ", foot=" + foot + ") — the accepted segment must carry the bot "
                        + "toward the far open-water node.");

            // (2b) ON + SUNK foot: the live foot bobs y58↔61 UNDER the y62 surface node (|Δy| up to 4), which
            // EXCEEDS the gate's |Δy|>maxJumpUp+2=3 jump bar. The open-water bee-line must still accept it (a
            // buoyant swim-UP to the surface node, not a dry jump), or the fix misses the deep-bob ticks and
            // the reject storm survives. The far node is at the surface (waterFootY); the sunk foot is 4 below.
            BlockPos sunkFoot = new BlockPos(cx, waterFootY - 4, cz);
            if (!w.isWater(sunkFoot) || Math.abs(farNode.getY() - sunkFoot.getY()) <= w.maxJumpUpBlocks() + 2)
                throw new GameTestAssertException("deepWaterFloatBeeline: the sunk-foot probe " + sunkFoot + " is not 4-below "
                        + "submerged water past the jump gate — vacuous (|Δy|=" + Math.abs(farNode.getY() - sunkFoot.getY()) + ").");
            Walker wSunk = new Walker();
            boolean acceptSunk = wSunk.adoptForTest(w, cont, contEdges, sunkFoot);
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] ON+SUNK accept={} (expect true: swim-up bee-line past the |Δy| jump gate)", acceptSunk);
            if (!acceptSunk)
                throw new GameTestAssertException("deepWaterFloatBeeline: with the fix ON a SUNK foot (4 below the surface node, "
                        + "the live deep-bob) was REJECTED — the exemption must bypass the |Δy| jump gate for an open-water swim-up "
                        + "(else the reject storm survives the y58 bob ticks).");

            // (3a) ON-INERT: a WALLED continuation (stone across the LOS line) must STILL be rejected — the
            // exemption requires a CLEAR open-water line, so it can't be abused for a truly mis-anchored seg.
            BlockPos wallAt = new BlockPos(cx + 20, waterFootY, cz);          // a stone block mid-line
            BlockPos wallHi = new BlockPos(cx + 20, waterFootY + 1, cz);
            level.setBlockAndUpdate(wallAt, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(wallHi, Blocks.STONE.defaultBlockState());
            Walker wWall = new Walker();
            boolean acceptWall = wWall.adoptForTest(w, cont, contEdges, foot);
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] ON+WALL accept={} (expect false: LOS blocked by stone)", acceptWall);
            level.setBlockAndUpdate(wallAt, Blocks.WATER.defaultBlockState());   // restore the channel
            level.setBlockAndUpdate(wallHi, Blocks.AIR.defaultBlockState());
            if (acceptWall)
                throw new GameTestAssertException("deepWaterFloatBeeline: with the fix ON a WALLED continuation (stone across "
                        + "the LOS) was ACCEPTED — the exemption must reject a non-open-water line (else it bee-lines through a wall).");

            // (3b) ON-INERT: a DRY foot (not floating), far from every continuation node, must STILL reject
            // the same far continuation — the exemption is gated on a floating foot, so dry mis-anchored
            // continuations are unaffected. Build a small dry platform well EAST of all continuation nodes.
            BlockPos dryFoot = new BlockPos(cx + spanX + 12, surface + 1, cz);
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    level.setBlockAndUpdate(new BlockPos(dryFoot.getX() + dx, surface, cz + dz), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(dryFoot.getX() + dx, surface + 1, cz + dz), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(dryFoot.getX() + dx, surface + 2, cz + dz), Blocks.AIR.defaultBlockState());
                }
            Walker wDry = new Walker();
            boolean acceptDry = wDry.adoptForTest(w, cont, contEdges, dryFoot);   // far continuation, dry foot
            AgentDriverCommon.LOG.info("[deepWaterFloatBeelineArena] ON+DRYFOOT accept={} (expect false: exemption needs a floating foot)", acceptDry);
            if (acceptDry)
                throw new GameTestAssertException("deepWaterFloatBeeline: with the fix ON a continuation from a DRY foot was "
                        + "ACCEPTED — the open-water bee-line exemption must be gated on a FLOATING foot.");
        } finally {
            BotConfig.walkerDeepWaterFloatBeeline = obee;
            BotConfig.walkerDebug = odbg;
        }
        helper.succeed();
    }
}

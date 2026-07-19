package net.magicterra.agent.bot.testkit.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.moves.PillarUp;
import net.magicterra.agent.bot.sim.ServerPlayerAvatar;
import net.magicterra.agent.bot.world.LevelWorldView;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneContext;
import net.magicterra.testkit.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded agent-driver scenes — <b>P4b wave 2, the Terrain family</b>: the 13
 * {@code AgentGameTestTerrain} traversal arenas (summit / sheer-wall / bridge /
 * parkour / ascent / descent / ledge / wall / crest-orbit / dig-cadence), migrated
 * verbatim to testkit {@code ad.*} scenes and their legacy twins retired in the same
 * commit.
 *
 * <p><b>Porting is by the canonical pattern established in {@link AgentDriverScenes}</b>
 * (study its class javadoc for the full rationale — this class applies the same
 * substitutions and does not re-explain them):
 * <ul>
 *   <li>{@code helper.getLevel()} → {@link SceneContext#level()};</li>
 *   <li>absolute {@code cx/cz} → {@link SceneContext#origin()} X/Z (arena math
 *       byte-identical, just relocated to the harness grid cell);</li>
 *   <li>absolute Y constants → {@code origin.y + (legacy_Y − 200)}. Because every grid
 *       origin sits at {@code GRID_Y = 200}, the mapped ABSOLUTE Y equals the legacy
 *       absolute Y — the vertical geometry is literally unchanged, only X/Z relocate
 *       (physics is y-invariant in this range regardless);</li>
 *   <li>{@code try/finally} per-key config save/restore → {@link BotConfig#pinnedBaseline()}
 *       + {@code ctx.cleanup(pin::close)} registered FIRST (LIFO → closes LAST, after the
 *       avatar discard) then the SAME keys the legacy body flipped;</li>
 *   <li>{@code ServerPlayerAvatar.create(...)} → {@link ServerPlayerAvatar#createUnique}
 *       (per-scene body, #48) + {@code ctx.cleanup(() -> fp.discard())} (closes the leak
 *       the throwaway-world GameTest bodies never had to);</li>
 *   <li>{@code AgentGameTestSupport.grantWaterEffects} →
 *       {@link SimProbes#grantWaterEffects} (the common single source);</li>
 *   <li>{@code AgentGameTestSupport.buildFloor} → the inlined {@link #buildFloor} helper
 *       below (origin-relative; promoted-into-class rather than imported across the
 *       neoforge testmod source-set boundary — only the 3 Terrain scenes that need it
 *       use it, so no cross-wave promotion);</li>
 *   <li>{@code throw new GameTestAssertException(msg)} → {@link SceneContext#fail(String)}
 *       (prefixed with the scene's short name for multi-scene log attribution);</li>
 *   <li>{@code helper.succeed()} → normal return;</li>
 *   <li>the {@code gtOnlySkips(...)} probe first line → deleted (the testkit gate
 *       reconciles itself).</li>
 * </ul>
 * The in-body synchronous walker loops are carried over unchanged (they run on the
 * scene's first RUN tick, same as the legacy GameTest shell), so the old/new-shell A/B
 * compares like with like.
 *
 * <p><b>Origin slots.</b> All 13 take AUTO slots at the default radius, with ONE
 * exception each way:
 * <ul>
 *   <li>{@code ad.ledgeOvershoot} needs {@code .withChunkRadius(2)}: its runway+terrace
 *       reaches dx +44, past the default window's +31 edge (radius-2 window = dx/dz
 *       [−32,+47]).</li>
 *   <li>No scene is pinned to a fixed slot: unlike the byte-determinism goldens
 *       {@code ad.descentYaw}/{@code ad.selfShaftDigUp}, every Terrain gate here is an
 *       OUTCOME (reached / fellInPit) or a WIDE-tolerance metric (ascent b/s floor 1.5 vs
 *       baseline 3.0; ledge worstBack &lt; −0.6 vs −0.12; ridge maxNoProgress budget 80),
 *       so registry-growth relocation cannot flip them — the {@code ad.gearScope} /
 *       {@code ad.buriedOre} auto-slot precedent applies.</li>
 * </ul>
 *
 * <p><b>{@code descentDrift} is NOT migrated — retired-without-scene, pending controller
 * adjudication (P4b escape hatch).</b> The 13th Terrain test, {@code descentDriftArena}, is a
 * legacy {@code required = false} PROVEN FALSE GREEN (gap #49 audit: it "passed" the shared-body
 * suite only because a concurrent arena shoved the shared FakePlayer out of the wedge; SOLO it
 * is deterministically RED — the fix-ON leg still LAUNCHES off the stair into open void, minY≈−60).
 * Its own javadoc records that "the fix's gate is the LIVE A/B" — i.e. the arena is superseded and
 * provides no reliable regression signal. It is <b>unmigratable as a faithful synchronous-body
 * scene</b>: the RED path is an open-void A* churn needing ~110 s of compute, and the dogfood
 * harness runs a scene body in ONE server tick, so it blows the 60 s {@code ServerHangWatchdog}
 * and CRASHES the whole suite (evidence: crash-2026-07-18_12.58.53-server.txt, stack rooted at
 * {@code descentDrift → Walker.tick → PathFinder.advance}). The determinism-critical config
 * ({@code pathfinderMaxNodes} node budget, sliceMs/maxMs=MAX) cannot be trimmed to fit without
 * rebaselining the search of an already-broken rig, and a wall-clock cap would be non-deterministic.
 * Per the P4b escape hatch this was raised as a retired-without-scene candidate and the controller
 * <b>adjudicated FULL RETIREMENT</b>: the legacy {@code descentDriftArena} twin was DELETED (its
 * class {@code AgentGameTestTerrain} emptied and was removed), with descent-behaviour coverage
 * continuing through {@code ad.descentYaw}'s golden signature gate. So this wave migrates 12 of 13
 * and retires the 13th (count 122 to 109). See task-1-report.md and the migration-log wave-2 note.
 */
public final class AgentDriverTerrainScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("ad.summit", 200, AgentDriverTerrainScenes::summit),
                Scene.of("ad.sheerWall", 200, AgentDriverTerrainScenes::sheerWall),
                Scene.of("ad.bridgeGap", 200, AgentDriverTerrainScenes::bridgeGap),
                Scene.of("ad.parkourAscend", 200, AgentDriverTerrainScenes::parkourAscend),
                Scene.of("ad.ridgeOvershoot", 200, AgentDriverTerrainScenes::ridgeOvershoot),
                Scene.of("ad.stepUpCrestOrbit", 200, AgentDriverTerrainScenes::stepUpCrestOrbit),
                Scene.of("ad.descent", 200, AgentDriverTerrainScenes::descent),
                // ad.descentDrift intentionally NOT registered — retired-without-scene (see class javadoc).
                Scene.of("ad.ascentSpeed", 200, AgentDriverTerrainScenes::ascentSpeed),
                Scene.of("ad.ledgeOvershoot", 200, AgentDriverTerrainScenes::ledgeOvershoot).withChunkRadius(2),
                Scene.of("ad.wallCollisionProbe", 200, AgentDriverTerrainScenes::wallCollisionProbe),
                Scene.of("ad.bridgeDescend", 200, AgentDriverTerrainScenes::bridgeDescend),
                Scene.of("ad.bareHandDigCadence", 200, AgentDriverTerrainScenes::bareHandDigCadence));
    }

    /** Inlined from {@code AgentGameTestSupport#buildFloor}: 11×11 stone floor at
     *  {@code floorY}, cleared air +1..+18 above. The generous clear is legacy residue
     *  hygiene for the shared world; harmless (and rebuilt over) under grid isolation. */
    private static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 1; dy <= 18; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
    }

    /** Ported from {@code AgentGameTestTerrain#summitArena}: the REAL {@link Walker} pillars
     *  a {@link ServerPlayerAvatar} up through an oak-leaf canopy (cardinal-neighbour leaf at
     *  each rung ceiling). Break+place ON; the {@code walkerPillarReachGoalNoSnap} flag keeps
     *  the elevated air goal pillar-reachable. Asserts the canopy {@code toBreak} fix
     *  ({@link PillarUp#eval}) AND that the avatar reaches the elevated goal. */
    private static void summit(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;   // legacy floorY 220 = origin.y(200)+20
        buildFloor(level, cx, cz, floorY);
        for (int y = standY + 1; y <= standY + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx - 1, y, cz), Blocks.OAK_LEAVES.defaultBlockState());
        BlockPos goal = new BlockPos(cx, standY + 3, cz);   // 3 pillars up

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerPillarReachGoalNoSnap = true;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.06, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);

        // Deterministic canopy-fix proof: PillarUp.eval at the rung whose ceiling (from+2) is
        // the leaf row must list the -x cardinal-neighbour leaf in toBreak (AABB head-sweep fix).
        Move.Edge pe = new PillarUp().eval(w, new BlockPos(cx, standY + 1, cz));   // from=222 → ceiling=224
        BlockPos neighbourLeaf = new BlockPos(cx - 1, standY + 3, cz);             // (cx-1, 224)
        if (pe == null || !pe.toBreak.contains(neighbourLeaf))
            ctx.fail("summit: canopy fix: PillarUp.eval did not add the ceiling-neighbour leaf "
                    + neighbourLeaf + " to toBreak (got " + (pe == null ? "null" : pe.toBreak) + ")");

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (cz + 0.5)) < 1.5
                && fp.getY() >= standY + 3 - 0.4;
        boolean leafCleared = level.getBlockState(new BlockPos(cx - 1, standY + 3, cz)).isAir();
        AgentDriverCommon.LOG.info("[ad.summit] step={} y={} reached={} leafCleared={}",
                s, fp.getY(), reached, leafCleared);
        if (!reached)
            ctx.fail("summit: real Walker failed to pillar to goal: y=" + fp.getY() + " step=" + s);
    }

    /** Ported from {@code AgentGameTestTerrain#sheerWallArena}: the REAL {@link Walker} climbs a
     *  +5 SHEER (vertical, stepless) wall onto a plateau behind it. Break OFF (no tunnelling),
     *  place ON (pillar/scaffold is the only way up). Asserts the avatar reaches the plateau. */
    private static void sheerWall(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;   // legacy floorY 220 = origin.y+20
        final int wallH = 5;
        buildFloor(level, cx, cz, floorY);
        int wallZ = cz + 2;
        int topY = floorY + wallH;                 // wall-top block; plateau surface = topY (stand topY+1)
        for (int dx = -5; dx <= 5; dx++) {
            for (int y = floorY + 1; y <= topY; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, wallZ), Blocks.STONE.defaultBlockState());
            for (int dz = 3; dz <= 5; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, topY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int yy = topY + 1; yy <= topY + 4; yy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, yy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        }
        BlockPos goal = new BlockPos(cx, topY + 1, cz + 3);   // first plateau cell behind the wall

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double maxY = fp.getY();
        for (int t = 0; t < 600 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w); av.step();
            maxY = Math.max(maxY, fp.getY());
        }
        boolean onPlateau = fp.getZ() > wallZ + 0.5 && fp.getY() >= topY + 1 - 0.4;
        AgentDriverCommon.LOG.info("[ad.sheerWall] step={} pos=({},{},{}) maxY={} onPlateau={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxY, onPlateau);
        if (!onPlateau)
            ctx.fail("sheerWall: Walker failed to climb the +" + wallH + " sheer wall: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY + " step=" + s);
    }

    /** Ported from {@code AgentGameTestTerrain#bridgeGapArena}: the REAL {@link Walker} bridges a
     *  3-cell void gap (full x-width, so the only crossing is a +z bridge). Break OFF = no
     *  dig-around, place ON. Asserts the avatar reaches the far platform. */
    private static void bridgeGap(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;   // legacy floorY 220 = origin.y+20
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean platform = dz <= -1 || dz >= 3;
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz),
                        platform ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                for (int yy = 1; yy <= 18; yy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + yy, cz + dz), Blocks.AIR.defaultBlockState());
                if (!platform)
                    for (int yy = 1; yy <= 4; yy++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY - yy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        }
        BlockPos goal = new BlockPos(cx, standY, cz + 4);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;   // force the bridge — no dig-around
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, standY, cz - 4 + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 600 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
        boolean crossed = fp.getZ() > cz + 3 - 0.5 && fp.getY() >= standY - 0.4;
        AgentDriverCommon.LOG.info("[ad.bridgeGap] step={} pos=({},{},{}) crossed={}",
                s, fp.getX(), fp.getY(), fp.getZ(), crossed);
        if (!crossed)
            ctx.fail("bridgeGap: Walker failed to bridge the 3-cell gap: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
    }

    /** Ported from {@code AgentGameTestTerrain#parkourAscendArena}: regression guard for the
     *  parkour-ascend sprint fix — a 2-block cardinal gap landing +1 higher clears only with a
     *  SPRINT-jump. Break/place OFF. Asserts the Walker sprint-jumps onto the +1 landing without
     *  falling into the deep pit. */
    private static void parkourAscend(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int launchY = ctx.origin().getY() + 30, pitY = ctx.origin().getY();   // legacy launchY 230 / pitY 200
        for (int dx = -6; dx <= 7; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, pitY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = -5; dx <= 0; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, launchY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = 2; dx <= 6; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, launchY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 4, launchY + 2, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 5 + 0.5, launchY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double minY = fp.getY();
        for (int t = 0; t < 300 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w); av.step();
            minY = Math.min(minY, fp.getY());
        }
        boolean fellInPit = minY <= pitY + 3;
        boolean onLanding = fp.getX() > cx + 1.5 && fp.getY() >= launchY + 2 - 0.4;
        AgentDriverCommon.LOG.info("[ad.parkourAscend] step={} pos=({},{},{}) minY={} fellInPit={} onLanding={}",
                s, fp.getX(), fp.getY(), fp.getZ(), minY, fellInPit, onLanding);
        if (fellInPit)
            ctx.fail("parkourAscend: parkour ascend fell into the gap (sprint disabled?): minY=" + minY);
        if (!onLanding)
            ctx.fail("parkourAscend: parkour ascend did not reach the +1 landing: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
    }

    /** Ported from {@code AgentGameTestTerrain#ridgeOvershootArena}: the FALL-OVERSHOOT re-sync
     *  dead-zone SMOOTHNESS guard — a steep foothold cliff (top platform, floating foothold 3
     *  below the rim, bottom plain 6 below carrying the goal). Break/place OFF. Asserts the bot
     *  descends to the goal WITHOUT stalling (max consecutive no-horizontal-progress ticks under
     *  a tight budget). */
    private static void ridgeOvershoot(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int plainY = ctx.origin().getY() - 20, topY = ctx.origin().getY() - 14;   // legacy plainY 180 / topY 186
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -10; dz <= 20; dz++)
                for (int y = plainY - 1; y <= topY + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -7; dz <= 0; dz++)
                for (int y = plainY; y <= topY; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, topY - 3, cz + 1), Blocks.STONE.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = 2; dz <= 16; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, plainY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, plainY + 1, cz + 12);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, topY + 1, cz - 3.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double px = fp.getX(), pz = fp.getZ();
        int noProgress = 0, maxNoProgress = 0;
        for (int t = 0; t < 700 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w); av.step();
            double dx = fp.getX() - px, dz = fp.getZ() - pz;
            if (dx * dx + dz * dz < 0.0025) noProgress++;   // <0.05 b/tick horizontally
            else noProgress = 0;
            maxNoProgress = Math.max(maxNoProgress, noProgress);
            px = fp.getX(); pz = fp.getZ();
        }
        boolean atGoal = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (cz + 12 + 0.5)) < 2.0
                && Math.abs(fp.getY() - (plainY + 1)) < 1.5;
        AgentDriverCommon.LOG.info("[ad.ridgeOvershoot] step={} pos=({},{},{}) atGoal={} maxNoProgress={}",
                s, fp.getX(), fp.getY(), fp.getZ(), atGoal, maxNoProgress);
        if (!atGoal)
            ctx.fail("ridgeOvershoot: did not reach the plain goal: pos=(" + fp.getX() + ","
                    + fp.getY() + "," + fp.getZ() + ") step=" + s + " maxNoProgress=" + maxNoProgress);
        if (maxNoProgress > 80)
            ctx.fail("ridgeOvershoot: fall-overshoot wedge — stalled " + maxNoProgress
                    + " ticks at a node the bot dropped past (budget 80)");
    }

    /** Ported from {@code AgentGameTestTerrain#stepUpCrestOrbitArena}: a deterministic A/B on the
     *  +1 STEPUP-CREST NODE-ORBIT gate. The scene HOLDS the orbit pose (foot at the node Y, ~0.74 b
     *  short of centre, cur2≈0.55, zero velocity) every tick — a pure function of pose + stall
     *  counter, so it is deterministic by construction (auto slot safe). Leg 0 (flag OFF) must WEDGE
     *  (step frozen), leg 1 (flag ON) must ADVANCE. The ONLY variable is
     *  {@code walkerStepUpCrestReach}. A single {@code createUnique} body is reused across both legs
     *  (the truest port of the legacy shared body repositioned each leg). */
    private static void stepUpCrestOrbit(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), baseY = ctx.origin().getY();   // legacy baseY 200 = origin.y
        final int footY = baseY + 1;
        for (int dx = -6; dx <= 10; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = baseY - 2; y <= footY + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -4; dx <= 2; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz), Blocks.STONE.defaultBlockState());
        for (int dx = -4; dx <= -1; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, footY, cz), Blocks.STONE.defaultBlockState());
        BlockPos approach = new BlockPos(cx + 1, footY, cz);
        BlockPos node = new BlockPos(cx, footY, cz);
        BlockPos cont = new BlockPos(cx - 1, footY + 1, cz);   // diagUp continuation, FURTHER + one higher
        BlockPos goalN = new BlockPos(cx - 3, footY + 1, cz);
        Goal goal = new Goal.Block(goalN);
        final double poseX = node.getX() + 0.5 + 0.74;   // 0.74 b east of centre → cur2 = 0.74² ≈ 0.548
        final double poseY = footY;                       // foot at the node block-Y (|dyNode| ≈ 0)
        final double poseZ = node.getZ() + 0.5;           // centred in Z

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;          // no carving — the stall must be the crest orbit, not a dig
        BotConfig.allowPlace = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, poseX, poseY, poseZ);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);       // resistance only — keeps a stray bonk from harming mid-test
        LevelWorldView w = new LevelWorldView(level, fp);

        int[] advanceTick = { -1, -1 };
        int[] nodeDwell = new int[2];
        boolean[] advanced = new boolean[2];
        double[] obsCur2 = { Double.NaN, Double.NaN };
        for (int leg = 0; leg < 2; leg++) {
            BotConfig.walkerStepUpCrestReach = (leg == 1);
            BotConfig.walkerDebug = true;
            fp.setPos(poseX, poseY, poseZ);
            fp.setDeltaMovement(0, 0, 0);
            Walker walker = new Walker();
            List<BlockPos> plan = List.of(approach, node, cont, goalN);
            List<Move.Edge> planEdges = List.of(
                    new Move.Edge(approach, 10, List.of(), List.of(), "walk"),
                    new Move.Edge(node, 10, List.of(), List.of(), "stepUp"),
                    new Move.Edge(cont, 10, List.of(), List.of(), "diagUp"),
                    new Move.Edge(goalN, 10, List.of(), List.of(), "walk"));
            walker.beginReplay(w, plan, planEdges, goal, node);

            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 200 && s == Walker.Step.WALKING; t++) {
                fp.setPos(poseX, poseY, poseZ);
                fp.setDeltaMovement(0, 0, 0);
                s = walker.tick(av, w);
                av.step();
                fp.setPos(poseX, poseY, poseZ);     // re-assert after physics too
                fp.setDeltaMovement(0, 0, 0);
                BlockPos pn = walker.pathNode();
                if (pn != null && pn.equals(node)) {
                    double dx = (node.getX() + 0.5) - poseX;
                    double dz = (node.getZ() + 0.5) - poseZ;
                    obsCur2[leg] = dx * dx + dz * dz;
                    nodeDwell[leg]++;
                }
                if (advanceTick[leg] < 0 && walker.pathStep() >= 2) {
                    advanceTick[leg] = t;
                    advanced[leg] = true;
                }
            }
            AgentDriverCommon.LOG.info("[ad.stepUpCrestOrbit] leg={} flagOn={} advanced={} advanceTick={} nodeDwell={} endStep={} heldCur2={} step={}",
                    leg, leg == 1, advanced[leg], advanceTick[leg], nodeDwell[leg], walker.pathStep(),
                    String.format(Locale.ROOT, "%.3f", obsCur2[leg]), s);
        }
        if (!(obsCur2[0] > 0.45 && obsCur2[0] < 1.3))
            ctx.fail("stepUpCrestOrbit: the held pose cur2=" + String.format(Locale.ROOT, "%.3f", obsCur2[0])
                    + " is not in the orbit band (0.45, 1.3) — re-tune poseX so the gate is exercised faithfully.");
        if (advanced[0])
            ctx.fail("stepUpCrestOrbit: with the fix OFF the step-pointer ADVANCED past the crest stepUp node "
                    + "(advanceTick=" + advanceTick[0] + ") — the orbit did not reproduce; the OFF leg must stay "
                    + "pinned (within needs cur2<0.45 and passed can't fire at the held orbit pose).");
        if (!advanced[1])
            ctx.fail("stepUpCrestOrbit: with walkerStepUpCrestReach ON the step-pointer FAILED to advance past the "
                    + "crest stepUp node (pinned " + nodeDwell[1] + " ticks) — the fix did not fire. heldCur2_ON="
                    + String.format(Locale.ROOT, "%.3f", obsCur2[1]));
        if (advanceTick[1] > 40)
            ctx.fail("stepUpCrestOrbit: with the fix ON the advance was SLOW (advanceTick=" + advanceTick[1]
                    + " > 40) — the relaxation should fire shortly after the stall gate, not drift.");
    }

    /** Ported from {@code AgentGameTestTerrain#descentArena}: regression guard for the descent
     *  crouch-deadlock fix (plannedDescent releases the lethal-edge sneak brake). A 1-wide staircase
     *  descends 12 steps over a DEEP pit with void on both x sides. Break/place OFF. Asserts the bot
     *  reaches the bottom and never falls off the 1-wide stair into the pit. */
    private static void descent(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int pitFloorY = ctx.origin().getY() - 20, topY = ctx.origin().getY() + 20, steps = 12;   // legacy 180 / 220
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= steps + 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, pitFloorY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int i = 0; i <= steps; i++)
            level.setBlockAndUpdate(new BlockPos(cx, topY - i, cz + i), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, topY - steps + 1, cz + steps);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, topY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double minY = fp.getY();
        for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w); av.step();
            minY = Math.min(minY, fp.getY());
        }
        boolean fellInPit = minY <= pitFloorY + 2;
        boolean atBottom = Math.abs(fp.getZ() - (cz + steps + 0.5)) < 1.5
                && Math.abs(fp.getY() - (topY - steps + 1)) < 1.5;
        AgentDriverCommon.LOG.info("[ad.descent] step={} pos=({},{},{}) minY={} fellInPit={} atBottom={}",
                s, fp.getX(), fp.getY(), fp.getZ(), minY, fellInPit, atBottom);
        if (fellInPit)
            ctx.fail("descent: fell off the 1-wide stair into the pit: minY=" + minY);
        if (!atBottom)
            ctx.fail("descent: crouch-deadlock: did not reach the bottom step: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
    }

    // NOTE: descentDriftArena is deliberately NOT ported — controller-adjudicated FULL RETIREMENT
    // (retired-without-scene). See the class javadoc: its RED path is an open-void A* churn that
    // exceeds the 60 s ServerHangWatchdog when run as a single-server-tick synchronous body,
    // crashing the whole dogfood suite; and the legacy twin is a documented PROVEN FALSE GREEN
    // (gap #49) whose real gate is the live A/B. The legacy twin (and its empty class
    // AgentGameTestTerrain) were DELETED in this same wave.

    /** Ported from {@code AgentGameTestTerrain#ascentSpeedArena}: SMOOTHNESS gate measuring the real
     *  {@link Walker}'s average horizontal speed UP a gentle staircase vs across flat ground (6 steps,
     *  2 blocks deep each, +1 y per step, flat run-up + flat top). Break/place OFF. Logs ascent/flat
     *  b/s + sprint%/hCol% and asserts the bot tops out and keeps a minimum ascent speed floor. */
    private static void ascentSpeed(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int baseY = ctx.origin().getY() + 10, stepCount = 6;   // legacy baseY 210 = origin.y+10
        for (int dx = -10; dx <= -1; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int i = 0; i < stepCount; i++) {
            int sy = baseY + 1 + i;
            for (int dx = 2 * i; dx <= 2 * i + 1; dx++)
                for (int dz = -2; dz <= 2; dz++)
                    for (int y = baseY; y <= sy; y++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        }
        final int topSurf = baseY + stepCount;
        final int ascEndX = cx + 2 * stepCount - 1;
        for (int dx = 2 * stepCount; dx <= 2 * stepCount + 12; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, topSurf, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 2 * stepCount + 10, topSurf + 1, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 9 + 0.5, baseY + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        int flatTicks = 0, ascTicks = 0;
        double flatStartX = Double.NaN, flatEndX = 0, ascStartX = Double.NaN, ascEndXObs = 0;
        int flatSprint = 0, ascSprint = 0, flatHcol = 0, ascHcol = 0;
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 500 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w); av.step();
            double x = fp.getX();
            boolean spr = fp.isSprinting(), hc = fp.horizontalCollision;
            if (x < cx) {
                if (Double.isNaN(flatStartX)) flatStartX = x;
                flatEndX = x; flatTicks++;
                if (spr) flatSprint++; if (hc) flatHcol++;
            } else if (x <= ascEndX + 1) {
                if (Double.isNaN(ascStartX)) ascStartX = x;
                ascEndXObs = x; ascTicks++;
                if (spr) ascSprint++; if (hc) ascHcol++;
            }
        }
        double flatBps = flatTicks > 0 ? (flatEndX - flatStartX) / (flatTicks * 0.05) : 0;
        double ascBps = ascTicks > 0 ? (ascEndXObs - ascStartX) / (ascTicks * 0.05) : 0;
        int flatSprintPct = flatTicks > 0 ? 100 * flatSprint / flatTicks : 0;
        int ascSprintPct = ascTicks > 0 ? 100 * ascSprint / ascTicks : 0;
        int ascHcolPct = ascTicks > 0 ? 100 * ascHcol / ascTicks : 0;
        boolean reachedTop = fp.getX() > ascEndX && fp.getY() >= topSurf + 1 - 0.4;
        AgentDriverCommon.LOG.info(
                "[ad.ascentSpeed] step={} pos=({},{},{}) reachedTop={} flatBps={} ascBps={} flatSprint%={} ascSprint%={} ascHcol%={} flatTicks={} ascTicks={}",
                s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                String.format(Locale.ROOT, "%.1f", fp.getZ()), reachedTop,
                String.format(Locale.ROOT, "%.2f", flatBps), String.format(Locale.ROOT, "%.2f", ascBps),
                flatSprintPct, ascSprintPct, ascHcolPct, flatTicks, ascTicks);
        if (!reachedTop)
            ctx.fail("ascentSpeed: did not reach the flat top: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
        if (ascBps < 1.5)
            ctx.fail("ascentSpeed: ascent speed collapsed to " + ascBps + " b/s");
    }

    /** Ported from {@code AgentGameTestTerrain#ledgeOvershootArena}: BACKWARD-HOP reproduction — a
     *  SPRINT-OVERSHOOT off a ledge (long flat runway → sheer 3-block drop → terrace). Metric =
     *  motion AWAY from the due-east goal after the lip + the worst single backward step. Break/place
     *  OFF. Guards a GROSS regression (worstBack &lt; −0.6, baseline ~−0.12). Needs
     *  {@code .withChunkRadius(2)}: the runway+terrace reach dx +44. */
    private static void ledgeOvershoot(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int H = ctx.origin().getY() + 40, RUN = 26, DROP = 3;   // legacy H 240 = origin.y+40
        for (int x = cx; x <= cx + RUN; x++)
            for (int z = cz - 2; z <= cz + 2; z++)
                for (int y = H - 4; y <= H - 1; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        for (int x = cx + RUN + 1; x <= cx + RUN + 18; x++)
            for (int z = cz - 2; z <= cz + 2; z++)
                for (int y = H - 4 - DROP; y <= H - 1 - DROP; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + RUN + 16, H - DROP, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 1.5, H, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        double prevX = fp.getX(), prevZ = fp.getZ();
        int backSteps = 0, moved = 0;
        double worstBack = 0, landX = 0;
        boolean pastLip = false;
        Walker.Step s = Walker.Step.WALKING;
        for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w); av.step();
            double ddx = fp.getX() - prevX, ddz = fp.getZ() - prevZ;
            if (!pastLip && fp.getX() > cx + RUN && fp.getY() < H - 1.5) {
                pastLip = true;
                landX = fp.getX();
            }
            if (pastLip && ddx * ddx + ddz * ddz > 1e-4) {
                moved++;
                if (ddx < -0.02) { backSteps++; worstBack = Math.min(worstBack, ddx); }
            }
            prevX = fp.getX(); prevZ = fp.getZ();
        }
        double dGoal = Math.hypot(fp.getX() - (goal.getX() + 0.5), fp.getZ() - (goal.getZ() + 0.5));
        boolean reached = dGoal < 2.5;
        double overshoot = landX - (cx + RUN);
        AgentDriverCommon.LOG.info(
                "[ad.ledgeOvershoot] step={} pos=({},{},{}) reached={} dGoal={} landX-overshoot={} backSteps={}/{} worstBack={}",
                s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                String.format(Locale.ROOT, "%.1f", fp.getZ()), reached, String.format(Locale.ROOT, "%.1f", dGoal),
                String.format(Locale.ROOT, "%.1f", overshoot), backSteps, moved, String.format(Locale.ROOT, "%.2f", worstBack));
        if (!reached)
            ctx.fail("ledgeOvershoot: did not reach the terrace goal: pos=(" + fp.getX() + ","
                    + fp.getY() + "," + fp.getZ() + ") dGoal=" + dGoal + " step=" + s);
        if (worstBack < -0.6)
            ctx.fail("ledgeOvershoot: backward-hop regressed to " + worstBack + " blocks/tick (baseline ~-0.12)");
    }

    /** Ported from {@code AgentGameTestTerrain#wallCollisionProbe}: does the sim avatar collide with a
     *  2-tall vertical wall? Drives the avatar straight (+z) into a 2-tall wall via
     *  {@code commandForward} (NOT the Walker) and asserts it does NOT pass through. Break OFF. */
    private static void wallCollisionProbe(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int H = ctx.origin().getY() + 40;   // legacy H 240 = origin.y+40
        for (int x = cx - 1; x <= cx + 1; x++)
            for (int z = cz - 1; z <= cz + 4; z++)
                level.setBlockAndUpdate(new BlockPos(x, H - 1, z), Blocks.STONE.defaultBlockState());
        for (int x = cx - 1; x <= cx + 1; x++)
            for (int y = H; y <= H + 1; y++)
                level.setBlockAndUpdate(new BlockPos(x, y, cz + 2), Blocks.STONE.defaultBlockState());

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, H, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        boolean wallSolid = !level.getBlockState(new BlockPos(cx, H, cz + 2)).isAir();
        double maxZ = fp.getZ();
        for (int t = 0; t < 80; t++) {
            fp.setYRot(0f);                          // face +z, straight at the wall
            fp.setSprinting(true);
            av.commandForward(1f);
            av.step();
            if (fp.getZ() > maxZ) maxZ = fp.getZ();
        }
        AgentDriverCommon.LOG.info("[ad.wallCollisionProbe] wallSolid={} startZ={} finalZ={} maxZ={} (wall front at z={})",
                wallSolid, cz + 0.5, String.format(Locale.ROOT, "%.2f", fp.getZ()),
                String.format(Locale.ROOT, "%.2f", maxZ), cz + 2);
        if (maxZ > cz + 1.8)
            ctx.fail("wallCollisionProbe: avatar PASSED THROUGH a 2-tall wall: maxZ=" + maxZ
                    + " (wall front z=" + (cz + 2) + ", wallSolid=" + wallSolid + ")");
    }

    /** Ported from {@code AgentGameTestTerrain#bridgeDescendArena}: a DESCENDING bridge — the bot
     *  bridgePlaces across a wide gap onto a far side ONE BLOCK LOWER than its start, and must reach
     *  it. Break OFF, place ON. Descending-bridge smoke coverage (the sneak ledge-guard fix's real
     *  gate is the live A/B; this clean arena passes with or without it). */
    private static void bridgeDescend(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int baseY = ctx.origin().getY() + 10;   // legacy baseY 210 = origin.y+10
        for (int dx = -8; dx <= -1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, baseY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = 5; dx <= 14; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, baseY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx + 12, baseY + 1, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = true;                 // bridging needs placement
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx - 7 + 0.5, baseY + 2, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        int crossTick = -1;
        for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w); av.step();
            if (crossTick < 0 && fp.getX() > cx + 5 && fp.getY() <= baseY + 1.4) crossTick = t;
        }
        boolean crossed = fp.getX() > cx + 5 && fp.getY() <= baseY + 1.4;
        AgentDriverCommon.LOG.info("[ad.bridgeDescend] step={} pos=({},{},{}) crossed={} crossTick={}",
                s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                String.format(Locale.ROOT, "%.1f", fp.getZ()), crossed, crossTick);
        if (!crossed)
            ctx.fail("bridgeDescend: descending bridge wedged (sneak ledge-guard?): pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
    }

    /** Ported from {@code AgentGameTestTerrain#bareHandDigCadenceArena}: BARE-HAND DIG CADENCE gate
     *  (gap #66). A sealed corridor whose only route is a traverseBreak through a 2-high stone wall
     *  (2 blocks × 150t bare-hand). Break ON, BARE HANDS. Asserts the breakHold does NOT drop while
     *  the wall is still solid (each drop zeroes vanilla mining progress) and the bot reaches the
     *  goal chamber. */
    private static void bareHandDigCadence(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), base = ctx.origin().getY();   // legacy base 200 = origin.y
        for (int dx = -1; dx <= 3; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int y = base; y <= base + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dy = 1; dy <= 2; dy++) {
            level.setBlockAndUpdate(new BlockPos(cx, base + dy, cz), Blocks.AIR.defaultBlockState());       // start chamber
            level.setBlockAndUpdate(new BlockPos(cx + 2, base + dy, cz), Blocks.AIR.defaultBlockState());   // goal chamber
        }
        BlockPos wallFeet = new BlockPos(cx + 1, base + 1, cz);
        BlockPos wallHead = new BlockPos(cx + 1, base + 2, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, base + 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();   // BARE HANDS — 150t/stone is the whole point
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(new BlockPos(cx + 2, base + 1, cz)));

        Walker.Step s = Walker.Step.WALKING;
        boolean prevHeld = false;
        int heldTicks = 0, dropsWhileSolid = 0;
        for (int t = 0; t < 1500 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w); av.step();
            boolean held = av.breakHeld();
            boolean wallSolid = level.getBlockState(wallFeet).isSolid() || level.getBlockState(wallHead).isSolid();
            if (prevHeld && !held && wallSolid) {
                dropsWhileSolid++;
                AgentDriverCommon.LOG.info("[ad.bareHandDigCadence] DROP #{} after {}t held (wall still solid) t={}",
                        dropsWhileSolid, heldTicks, t);
            }
            heldTicks = held ? (prevHeld ? heldTicks + 1 : 1) : 0;
            prevHeld = held;
        }
        boolean wallOpen = !level.getBlockState(wallFeet).isSolid() && !level.getBlockState(wallHead).isSolid();
        boolean arrived = fp.getX() > cx + 1.5;
        AgentDriverCommon.LOG.info("[ad.bareHandDigCadence] step={} pos=({},{},{}) wallOpen={} arrived={} dropsWhileSolid={}",
                s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                String.format(Locale.ROOT, "%.1f", fp.getZ()), wallOpen, arrived, dropsWhileSolid);
        if (dropsWhileSolid > 2)
            ctx.fail("bareHandDigCadence: dig cadence broken: breakHold dropped " + dropsWhileSolid
                    + "x while the wall was still solid (each drop zeroes vanilla mining progress — gap #66)");
        if (!arrived)
            ctx.fail("bareHandDigCadence: never reached the goal chamber: pos=(" + fp.getX() + ","
                    + fp.getY() + "," + fp.getZ() + ") wallOpen=" + wallOpen + " drops=" + dropsWhileSolid);
    }
}

package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.ScenePlan;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded worlddriver scenes — <b>P4b wave 2, the Terrain family</b>: the 13
 * {@code AgentGameTestTerrain} traversal arenas (summit / sheer-wall / bridge /
 * parkour / ascent / descent / ledge / wall / crest-orbit / dig-cadence), migrated
 * verbatim to testkit {@code wd.*} scenes and their legacy twins retired in the same
 * commit.
 *
 * <p>Same pattern as {@link WorldDriverScenes}: a roster bot ({@link SceneBot}) walks
 * {@code mc.bot.goto} and the scene ticks it with full bot ticks on its first RUN tick,
 * reading the player every tick; settings go through {@link Harness} after
 * {@code Harness.pin}. Arena X/Z derive from {@link SceneContext#origin()}; absolute Y
 * constants map to {@code origin.y + (legacy_Y − 200)}, which is the legacy absolute Y
 * since every grid origin sits at {@code GRID_Y = 200}.
 *
 * <p>{@code wd.stepUpCrestOrbit} hands its bot a scripted plan through {@code mc.debug.walkPlan}
 * and re-poses the body around every tick.
 *
 * <p><b>Origin slots.</b> All 13 take AUTO slots at the default radius, with ONE
 * exception each way:
 * <ul>
 *   <li>{@code wd.ledgeOvershoot} needs {@code .withChunkRadius(2)}: its runway+terrace
 *       reaches dx +44, past the default window's +31 edge (radius-2 window = dx/dz
 *       [−32,+47]).</li>
 *   <li>No scene is pinned to a fixed slot: unlike the byte-determinism goldens
 *       {@code wd.descentYaw}/{@code wd.selfShaftDigUp}, every Terrain gate here is an
 *       OUTCOME (reached / fellInPit) or a WIDE-tolerance metric (ascent b/s floor 1.5 vs
 *       baseline 3.0; ledge worstBack &lt; −0.6 vs −0.12; ridge maxNoProgress budget 80),
 *       so registry-growth relocation cannot flip them — the {@code wd.gearScope} /
 *       {@code wd.buriedOre} auto-slot precedent applies.</li>
 * </ul>
 *
 * <p><b>{@code descentDrift} is NOT migrated — retired-without-scene, pending controller
 * adjudication (P4b escape hatch).</b> The 13th Terrain test, {@code descentDriftArena}, is a
 * legacy {@code required = false} PROVEN FALSE GREEN (it "passed" the shared-player
 * suite only because a concurrent arena shoved the shared FakePlayer out of the wedge; SOLO it
 * is deterministically RED — the fix-ON arm still LAUNCHES off the stair into open void, minY≈−60).
 * Its own javadoc records that "the fix's gate is the LIVE A/B" — i.e. the arena is superseded and
 * provides no reliable regression signal. It is <b>unmigratable as a faithful synchronous-body
 * scene</b>: the RED path is an open-void A* churn needing ~110 s of compute, and the dogfood
 * harness runs a scene body in ONE server tick, so it blows the 60 s {@code ServerHangWatchdog}
 * and CRASHES the whole suite (evidence: crash-2026-07-18_12.58.53-server.txt, stack rooted at
 * {@code descentDrift → Navigator.tick → PathFinder.advance}). The determinism-critical config
 * ({@code pathfinderMaxNodes} node budget, sliceMs/maxMs=MAX) cannot be trimmed to fit without
 * rebaselining the search of an already-broken rig, and a wall-clock cap would be non-deterministic.
 * Per the P4b escape hatch this was raised as a retired-without-scene candidate and the controller
 * <b>adjudicated FULL RETIREMENT</b>: the legacy {@code descentDriftArena} twin was DELETED (its
 * class {@code AgentGameTestTerrain} emptied and was removed), with descent-behaviour coverage
 * continuing through {@code wd.descentYaw}'s golden signature gate. So this wave migrates 12 of 13
 * and retires the 13th (count 122 to 109). See task-1-report.md and the migration-log wave-2 note.
 */
public final class WorldDriverTerrainScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.summit", 200, WorldDriverTerrainScenes::summit),
                Scene.of("wd.sheerWall", 200, WorldDriverTerrainScenes::sheerWall),
                Scene.of("wd.bridgeGap", 200, WorldDriverTerrainScenes::bridgeGap),
                Scene.of("wd.parkourAscend", 200, WorldDriverTerrainScenes::parkourAscend),
                Scene.of("wd.ridgeOvershoot", 200, WorldDriverTerrainScenes::ridgeOvershoot),
                Scene.of("wd.stepUpCrestOrbit", 200, WorldDriverTerrainScenes::stepUpCrestOrbit),
                Scene.of("wd.descent", 200, WorldDriverTerrainScenes::descent),
                // wd.descentDrift intentionally NOT registered — retired-without-scene (see class javadoc).
                Scene.of("wd.ascentSpeed", 200, WorldDriverTerrainScenes::ascentSpeed),
                Scene.of("wd.ledgeOvershoot", 200, WorldDriverTerrainScenes::ledgeOvershoot).withChunkRadius(2),
                Scene.of("wd.wallCollisionProbe", 200, WorldDriverTerrainScenes::wallCollisionProbe),
                Scene.of("wd.bridgeDescend", 200, WorldDriverTerrainScenes::bridgeDescend),
                Scene.of("wd.bareHandDigCadence", 200, WorldDriverTerrainScenes::bareHandDigCadence));
    }

    /** Ported from {@code AgentGameTestTerrain#summitArena}: a bot's goto pillars up through an
     *  oak-leaf canopy (cardinal-neighbour leaf at each rung ceiling). Break+place ON; the
     *  {@code navigatorPillarReachGoalNoSnap} flag keeps the elevated air goal pillar-reachable.
     *  Asserts the bot reaches the elevated goal. The canopy {@code toBreak} fix itself (the rung
     *  lists the ceiling-neighbour leaf) is checked by {@code PillarUpTest} over a grid. */
    private static void summit(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;   // legacy floorY 220 = origin.y(200)+20
        SceneArena.buildFloor(level, cx, cz, floorY);
        // A CANOPY: leaves only in the rows the rising head meets (the first rung's ceiling and the
        // one above). The column used to run down to foot level, which is a wall, not a canopy —
        // a player cannot be off-centre into a wall beside its own feet, so PillarUp rightly stops
        // listing that side once the wall reaches the foot or the head (wd.clientPillarOutOfShaft).
        for (int y = standY + 3; y <= standY + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx - 1, y, cz), Blocks.OAK_LEAVES.defaultBlockState());
        BlockPos goal = new BlockPos(cx, standY + 3, cz);   // 3 pillars up

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorPillarReachGoalNoSnap", true);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.06, standY, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        bot.goTo(Targets.block(goal));
        bot.tickUntilIdle(400);
        String s = NavWalk.step(bot);
        boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (cz + 0.5)) < 1.5
                && fp.getY() >= standY + 3 - 0.4;
        boolean leafCleared = level.getBlockState(new BlockPos(cx - 1, standY + 3, cz)).isAir();
        TestMod.LOG.info("[wd.summit] step={} y={} reached={} leafCleared={}",
                s, fp.getY(), reached, leafCleared);
        if (!reached)
            ctx.fail("summit: the goto failed to pillar to goal: y=" + fp.getY() + " step=" + s);
    }

    /** Ported from {@code AgentGameTestTerrain#sheerWallArena}: a bot's goto climbs a
     *  +5 SHEER (vertical, stepless) wall onto a plateau behind it. Break OFF (no tunnelling),
     *  place ON (pillar/scaffold is the only way up). Asserts the avatar reaches the plateau. */
    private static void sheerWall(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + 20, standY = floorY + 1;   // legacy floorY 220 = origin.y+20
        final int wallH = 5;
        SceneArena.buildFloor(level, cx, cz, floorY);
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", true);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;
        bot.goTo(Targets.block(goal));

        double maxY = fp.getY();
        boolean walking = true;
        for (int t = 0; t < 600 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            maxY = Math.max(maxY, fp.getY());
        }
        String s = NavWalk.step(bot);
        boolean onPlateau = fp.getZ() > wallZ + 0.5 && fp.getY() >= topY + 1 - 0.4;
        TestMod.LOG.info("[wd.sheerWall] step={} pos=({},{},{}) maxY={} onPlateau={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxY, onPlateau);
        if (!onPlateau)
            ctx.fail("sheerWall: the goto failed to climb the +" + wallH + " sheer wall: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY + " step=" + s);
    }

    /** Ported from {@code AgentGameTestTerrain#bridgeGapArena}: a bot's goto bridges a
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);   // force the bridge — no dig-around
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", true);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, standY, cz - 4 + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        bot.goTo(Targets.block(goal));
        bot.tickUntilIdle(600);
        String s = NavWalk.step(bot);
        boolean crossed = fp.getZ() > cz + 3 - 0.5 && fp.getY() >= standY - 0.4;
        TestMod.LOG.info("[wd.bridgeGap] step={} pos=({},{},{}) crossed={}",
                s, fp.getX(), fp.getY(), fp.getZ(), crossed);
        if (!crossed)
            ctx.fail("bridgeGap: the goto failed to bridge the 3-cell gap: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
    }

    /** Ported from {@code AgentGameTestTerrain#parkourAscendArena}: regression guard for the
     *  parkour-ascend sprint fix — a 2-block cardinal gap landing +1 higher clears only with a
     *  SPRINT-jump. Break/place OFF. Asserts the Navigator sprint-jumps onto the +1 landing without
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx - 5 + 0.5, launchY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        bot.goTo(Targets.block(goal));

        double minY = fp.getY();
        boolean walking = true;
        for (int t = 0; t < 300 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            minY = Math.min(minY, fp.getY());
        }
        String s = NavWalk.step(bot);
        boolean fellInPit = minY <= pitY + 3;
        boolean onLanding = fp.getX() > cx + 1.5 && fp.getY() >= launchY + 2 - 0.4;
        TestMod.LOG.info("[wd.parkourAscend] step={} pos=({},{},{}) minY={} fellInPit={} onLanding={}",
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, topY + 1, cz - 3.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        bot.goTo(Targets.block(goal));

        double px = fp.getX(), pz = fp.getZ();
        int noProgress = 0, maxNoProgress = 0;
        boolean walking = true;
        for (int t = 0; t < 700 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            double dx = fp.getX() - px, dz = fp.getZ() - pz;
            if (dx * dx + dz * dz < 0.0025) noProgress++;   // <0.05 b/tick horizontally
            else noProgress = 0;
            maxNoProgress = Math.max(maxNoProgress, noProgress);
            px = fp.getX(); pz = fp.getZ();
        }
        String s = NavWalk.step(bot);
        boolean atGoal = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (cz + 12 + 0.5)) < 2.0
                && Math.abs(fp.getY() - (plainY + 1)) < 1.5;
        TestMod.LOG.info("[wd.ridgeOvershoot] step={} pos=({},{},{}) atGoal={} maxNoProgress={}",
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
     *  counter, so it is deterministic by construction (auto slot safe). Arm 0 (flag OFF) must WEDGE
     *  (step frozen), arm 1 (flag ON) must ADVANCE. The ONLY variable is
     *  {@code navigatorStepUpCrestReach}. A single {@code createUnique} player is reused across both
     *  arms (the truest port of the legacy shared player repositioned for each arm). */
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
        final double poseX = node.getX() + 0.5 + 0.74;   // 0.74 b east of centre → cur2 = 0.74² ≈ 0.548
        final double poseY = footY;                       // foot at the node block-Y (|dyNode| ≈ 0)
        final double poseZ = node.getZ() + 0.5;           // centred in Z

        Harness.pin(ctx);
        Harness.set("allowBreak", false);          // no carving — the stall must be the crest orbit, not a dig
        Harness.set("allowPlace", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, poseX, poseY, poseZ);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);       // resistance only — keeps a stray bonk from harming mid-test

        int[] advanceTick = { -1, -1 };
        int[] nodeDwell = new int[2];
        boolean[] advanced = new boolean[2];
        double[] obsCur2 = { Double.NaN, Double.NaN };
        List<DebugContracts.PlanEdge> plan = List.of(
                ScenePlan.edge(approach, "walk"),
                ScenePlan.edge(node, "stepUp"),
                ScenePlan.edge(cont, "diagUp"),
                ScenePlan.edge(goalN, "walk"));
        for (int leg = 0; leg < 2; leg++) {
            Harness.set("navigatorStepUpCrestReach", (leg == 1));
            Harness.set("navigatorDebug", true);
            fp.setPos(poseX, poseY, poseZ);
            fp.setDeltaMovement(0, 0, 0);
            bot.walkPlan(DebugContracts.WalkPlanParams.replay(bot.ref(), plan)
                    .goal(Targets.block(goalN)).from(Targets.pos(node)));

            boolean walking = true;
            int endStep = 0;
            for (int t = 0; t < 200 && walking; t++) {
                fp.setPos(poseX, poseY, poseZ);
                fp.setDeltaMovement(0, 0, 0);
                bot.tick();
                walking = bot.busy();
                fp.setPos(poseX, poseY, poseZ);     // re-assert after physics too
                fp.setDeltaMovement(0, 0, 0);
                BotDebugContracts.PlanCursor at = bot.navigator().plan().orElse(null);
                if (at == null) continue;
                endStep = at.step();
                if (at.node().map(Targets.pos(node)::equals).orElse(false)) {
                    double dx = (node.getX() + 0.5) - poseX;
                    double dz = (node.getZ() + 0.5) - poseZ;
                    obsCur2[leg] = dx * dx + dz * dz;
                    nodeDwell[leg]++;
                }
                if (advanceTick[leg] < 0 && at.step() >= 2) {
                    advanceTick[leg] = t;
                    advanced[leg] = true;
                }
            }
            bot.cancel();
            TestMod.LOG.info("[wd.stepUpCrestOrbit] leg={} flagOn={} advanced={} advanceTick={} nodeDwell={} endStep={} heldCur2={} walking={}",
                    leg, leg == 1, advanced[leg], advanceTick[leg], nodeDwell[leg], endStep,
                    String.format(Locale.ROOT, "%.3f", obsCur2[leg]), walking);
        }
        if (!(obsCur2[0] > 0.45 && obsCur2[0] < 1.3))
            ctx.fail("stepUpCrestOrbit: the held pose cur2=" + String.format(Locale.ROOT, "%.3f", obsCur2[0])
                    + " is not in the orbit band (0.45, 1.3) — re-tune poseX so the gate is exercised faithfully.");
        if (advanced[0])
            ctx.fail("stepUpCrestOrbit: with the fix OFF the step-pointer ADVANCED past the crest stepUp node "
                    + "(advanceTick=" + advanceTick[0] + ") — the orbit did not reproduce; the OFF arm must stay "
                    + "pinned (within needs cur2<0.45 and passed can't fire at the held orbit pose).");
        if (!advanced[1])
            ctx.fail("stepUpCrestOrbit: with navigatorStepUpCrestReach ON the step-pointer FAILED to advance past the "
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, topY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        bot.goTo(Targets.block(goal));

        double minY = fp.getY();
        boolean walking = true;
        for (int t = 0; t < 400 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            minY = Math.min(minY, fp.getY());
        }
        String s = NavWalk.step(bot);
        boolean fellInPit = minY <= pitFloorY + 2;
        boolean atBottom = Math.abs(fp.getZ() - (cz + steps + 0.5)) < 1.5
                && Math.abs(fp.getY() - (topY - steps + 1)) < 1.5;
        TestMod.LOG.info("[wd.descent] step={} pos=({},{},{}) minY={} fellInPit={} atBottom={}",
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

    /** Ported from {@code AgentGameTestTerrain#ascentSpeedArena}: SMOOTHNESS gate measuring a bot's
     *  goto's average horizontal speed UP a gentle staircase vs across flat ground (6 steps,
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx - 9 + 0.5, baseY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        bot.goTo(Targets.block(goal));

        int flatTicks = 0, ascTicks = 0;
        double flatStartX = Double.NaN, flatEndX = 0, ascStartX = Double.NaN, ascEndXObs = 0;
        int flatSprint = 0, ascSprint = 0, flatHcol = 0, ascHcol = 0;
        boolean walking = true;
        for (int t = 0; t < 500 && walking; t++) {
            bot.tick();
            walking = bot.busy();
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
        String s = NavWalk.step(bot);
        boolean reachedTop = fp.getX() > ascEndX && fp.getY() >= topSurf + 1 - 0.4;
        TestMod.LOG.info(
                "[wd.ascentSpeed] step={} pos=({},{},{}) reachedTop={} flatBps={} ascBps={} flatSprint%={} ascSprint%={} ascHcol%={} flatTicks={} ascTicks={}",
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 1.5, H, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        bot.goTo(Targets.block(goal));

        double prevX = fp.getX(), prevZ = fp.getZ();
        int backSteps = 0, moved = 0;
        double worstBack = 0, landX = 0;
        boolean pastLip = false;
        boolean walking = true;
        for (int t = 0; t < 400 && walking; t++) {
            bot.tick();
            walking = bot.busy();
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
        String s = NavWalk.step(bot);
        double dGoal = Math.hypot(fp.getX() - (goal.getX() + 0.5), fp.getZ() - (goal.getZ() + 0.5));
        boolean reached = dGoal < 2.5;
        double overshoot = landX - (cx + RUN);
        TestMod.LOG.info(
                "[wd.ledgeOvershoot] step={} pos=({},{},{}) reached={} dGoal={} landX-overshoot={} backSteps={}/{} worstBack={}",
                s, String.format(Locale.ROOT, "%.1f", fp.getX()), String.format(Locale.ROOT, "%.1f", fp.getY()),
                String.format(Locale.ROOT, "%.1f", fp.getZ()), reached, String.format(Locale.ROOT, "%.1f", dGoal),
                String.format(Locale.ROOT, "%.1f", overshoot), backSteps, moved, String.format(Locale.ROOT, "%.2f", worstBack));
        if (!reached)
            ctx.fail("ledgeOvershoot: did not reach the terrace goal: pos=(" + fp.getX() + ","
                    + fp.getY() + "," + fp.getZ() + ") dGoal=" + dGoal + " step=" + s);
        if (worstBack < -0.6)
            ctx.fail("ledgeOvershoot: backward-hop regressed to " + worstBack + " blocks/tick (baseline ~-0.12)");
    }

    /** Ported from {@code AgentGameTestTerrain#wallCollisionProbe}: does a bot's body collide with a
     *  2-tall vertical wall? Drives the body straight (+z) into a 2-tall wall by holding the forward
     *  and sprint keys over body-only steps (NOT the navigator) and asserts it does NOT pass through.
     *  Break OFF. */
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, H, cz + 0.5);
        ServerPlayer fp = bot.player();
        boolean wallSolid = !level.getBlockState(new BlockPos(cx, H, cz + 2)).isAir();
        double maxZ = fp.getZ();
        // Forward and sprint held for the whole run; a key stays down until the next press changes it.
        bot.press(bot.keys().move(0, 1).sprint(true));
        for (int t = 0; t < 80; t++) {
            fp.setYRot(0f);                          // face +z, straight at the wall
            bot.step();
            if (fp.getZ() > maxZ) maxZ = fp.getZ();
        }
        bot.release();
        TestMod.LOG.info("[wd.wallCollisionProbe] wallSolid={} startZ={} finalZ={} maxZ={} (wall front at z={})",
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", true);                 // bridging needs placement
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx - 7 + 0.5, baseY + 2, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        bot.goTo(Targets.block(goal));

        int crossTick = -1;
        boolean walking = true;
        for (int t = 0; t < 400 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            if (crossTick < 0 && fp.getX() > cx + 5 && fp.getY() <= baseY + 1.4) crossTick = t;
        }
        String s = NavWalk.step(bot);
        boolean crossed = fp.getX() > cx + 5 && fp.getY() <= baseY + 1.4;
        TestMod.LOG.info("[wd.bridgeDescend] step={} pos=({},{},{}) crossed={} crossTick={}",
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

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, base + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();   // BARE HANDS — 150t/stone is the whole point
        bot.goTo(Targets.block(new BlockPos(cx + 2, base + 1, cz)));

        boolean prevHeld = false;
        int heldTicks = 0, dropsWhileSolid = 0;
        boolean walking = true;
        for (int t = 0; t < 1500 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            boolean held = bot.body().hands().breakHeld();
            boolean wallSolid = level.getBlockState(wallFeet).isSolid() || level.getBlockState(wallHead).isSolid();
            if (prevHeld && !held && wallSolid) {
                dropsWhileSolid++;
                TestMod.LOG.info("[wd.bareHandDigCadence] DROP #{} after {}t held (wall still solid) t={}",
                        dropsWhileSolid, heldTicks, t);
            }
            heldTicks = held ? (prevHeld ? heldTicks + 1 : 1) : 0;
            prevHeld = held;
        }
        String s = NavWalk.step(bot);
        boolean wallOpen = !level.getBlockState(wallFeet).isSolid() && !level.getBlockState(wallHead).isSolid();
        boolean arrived = fp.getX() > cx + 1.5;
        TestMod.LOG.info("[wd.bareHandDigCadence] step={} pos=({},{},{}) wallOpen={} arrived={} dropsWhileSolid={}",
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

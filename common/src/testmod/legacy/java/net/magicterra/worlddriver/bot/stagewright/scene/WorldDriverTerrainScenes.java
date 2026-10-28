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
                Scene.of("wd.sheerWall", 200, WorldDriverTerrainScenes::sheerWall),
                Scene.of("wd.parkourAscend", 200, WorldDriverTerrainScenes::parkourAscend),
                Scene.of("wd.ridgeOvershoot", 200, WorldDriverTerrainScenes::ridgeOvershoot),
                Scene.of("wd.descent", 200, WorldDriverTerrainScenes::descent),
                // wd.descentDrift intentionally NOT registered — retired-without-scene (see class javadoc).
                Scene.of("wd.ascentSpeed", 200, WorldDriverTerrainScenes::ascentSpeed),
                Scene.of("wd.ledgeOvershoot", 200, WorldDriverTerrainScenes::ledgeOvershoot).withChunkRadius(2),
                Scene.of("wd.bareHandDigCadence", 200, WorldDriverTerrainScenes::bareHandDigCadence));
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

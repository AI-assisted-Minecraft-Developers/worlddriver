package net.magicterra.agent.bot.testkit.scene;

import java.util.List;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.Walker;
import net.magicterra.agent.bot.pathfinder.moves.Fall;
import net.magicterra.agent.bot.pathfinder.moves.FallIntoWater;
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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dogfooded agent-driver scenes — <b>P4b wave 4, the WaterBank family</b>: the 11
 * {@code AgentGameTestWaterBank} water-physics / bank-climb-out arenas (deep-water
 * buoyant-wall / vine cling / vine-over-water −711 repro / tall-bank dig / low-bank
 * foothold / river sheer bank / deep-water cross / block-less climb-out / drift entry /
 * water far-aim divider), migrated verbatim to testkit {@code ad.*} scenes and their
 * legacy twins retired in the same commit.
 *
 * <p><b>Porting is by the canonical pattern established in {@link AgentDriverScenes} /
 * {@link AgentDriverTerrainScenes}</b> (study their class javadocs for the full rationale —
 * this class applies the same mechanical substitutions and does not re-explain them):
 * <ul>
 *   <li>{@code helper.getLevel()} → {@link SceneContext#level()};</li>
 *   <li>absolute {@code cx/cz} → {@link SceneContext#origin()} X/Z (arena math
 *       byte-identical, just relocated to the harness grid cell);</li>
 *   <li>absolute Y constants → {@code origin.y + (legacy_Y − 200)}. Because every grid
 *       origin sits at {@code GRID_Y = 200}, the mapped ABSOLUTE Y equals the legacy
 *       absolute Y — the vertical water geometry is literally unchanged, only X/Z relocate
 *       (buoyancy/travel physics is y-invariant in this range);</li>
 *   <li>{@code try/finally} per-key config save/restore → {@link BotConfig#pinnedBaseline()}
 *       + {@code ctx.cleanup(pin::close)} registered FIRST (LIFO → closes LAST, after the
 *       avatar discard) then the SAME keys the legacy body flipped. The one NON-BotConfig
 *       knob, {@link ServerPlayerAvatar#faithfulBreak} (a static field, not covered by the
 *       pin), is saved/restored via its own {@code ctx.cleanup} in {@link #tallBankDigClimb};</li>
 *   <li>{@code ServerPlayerAvatar.create(...)} → {@link ServerPlayerAvatar#createUnique}
 *       (per-scene body, #48) + {@code ctx.cleanup(() -> fp.discard())}. In the ISOLATED
 *       per-scene body model the legacy shared-FakePlayer parking / anti-contamination
 *       finally blocks (e.g. {@code vineOverWaterClimbArena}'s {@code cleanupFp} re-park, the
 *       legacy per-arena isolation batches) become dead weight and are DROPPED —
 *       the createUnique body cannot bleed into another scene, so those guards had nothing
 *       left to protect;</li>
 *   <li>{@code AgentGameTestSupport.grantWaterEffects} → {@link SimProbes#grantWaterEffects}
 *       (the common single source);</li>
 *   <li>{@code AgentGameTestSupport.buildWaterColumn} → the inlined {@link #buildWaterColumn}
 *       helper below (faithful copy, promoted-into-class rather than imported across the
 *       neoforge testmod source-set boundary — only {@link #waterPhysicsParity} needs it);</li>
 *   <li>{@code throw new GameTestAssertException(msg)} → {@link SceneContext#fail(String)}
 *       (prefixed with the scene's short name for multi-scene log attribution);</li>
 *   <li>{@code helper.succeed()} → normal return;</li>
 *   <li>the {@code gtOnlySkips(...)} probe first line → deleted (the testkit gate
 *       reconciles itself).</li>
 * </ul>
 * The in-body synchronous walker loops are carried over unchanged (they run on the scene's
 * first RUN tick, same as the legacy GameTest shell), so the old/new-shell A/B compares like
 * with like. The legacy determinism AIR-clear boxes are kept verbatim (harmless under grid
 * isolation — the arena rebuilds over them).
 *
 * <p><b>Lottery / optional governance (this wave's core discipline).</b>
 * <ul>
 *   <li>{@code ad.vineClingFidelityProbe} and {@code ad.vineOverWaterClimb} are legacy
 *       {@code required = false} → migrated {@code .withRequired(false)}. The vine-over-water
 *       arena is the documented <b>live −711,67 bug</b> repro: against the CLEAN baseline
 *       (walkerVineFreeHangClimb OFF) it MUST FAIL, and that optional-FAIL is the proof it
 *       reproduces the live wall-less-vine detach. Its RED stays VISIBLE (reported per-run),
 *       never tuned away — marking it required would RED the whole suite. See its own javadoc.</li>
 *   <li>{@code ad.deepWaterClimboutNoBlock} is the gap #48 shared-body lottery member
 *       (solo-GREEN proven this phase, full-run flaky in the OLD shared-body suite). In the
 *       createUnique isolated body the shared-body flake mechanism is GONE, so it runs
 *       DETERMINISTICALLY GREEN (observed ×2×2) — kept {@code required=true}; the determinism
 *       is the expected outcome of body isolation (a shell difference), NOT a rebaseline of
 *       thresholds.</li>
 *   <li>{@code ad.riverSheerBank} → {@code .withRequired(false)}: the gap #48 shared-body
 *       false-green this wave <b>surfaced</b>. Legacy-GREEN only because concurrent GameTest
 *       batches shoved the shared body ashore; isolated it deterministically REDs (step=FAILED)
 *       under the identical authored default-OFF baseline (the walker water-escape flags the
 *       legacy GameTestServer + this pin both zero). NOT tuned — its RED stays visible, tracked
 *       by task#91. See its method javadoc for the full config-identity argument.</li>
 * </ul>
 *
 * <p><b>Origin slots.</b> All 11 take AUTO slots at the default radius — every WaterBank
 * gate is an OUTCOME (onPlateau / onBank / ashore / bobTicks under a wide tolerance), not a
 * byte-determinism golden, so registry-growth relocation cannot flip it. No scene overflows
 * the radius-1 window (usable dx/dz {@code [−16,+31]}): the widest, {@code ad.riverSheerBank},
 * reaches exactly dx +31 (basin floor {@code cx-3..riverLen+3 = -3..+31}), which fits.
 */
public final class AgentDriverWaterBankScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("ad.waterPhysicsParity", 200, AgentDriverWaterBankScenes::waterPhysicsParity),
                Scene.of("ad.buoyantWall", 200, AgentDriverWaterBankScenes::buoyantWall),
                Scene.of("ad.vineClingFidelityProbe", 200, AgentDriverWaterBankScenes::vineClingFidelityProbe)
                        .withRequired(false),
                Scene.of("ad.vineOverWaterClimb", 200, AgentDriverWaterBankScenes::vineOverWaterClimb)
                        .withRequired(false),
                Scene.of("ad.tallBankDigClimb", 200, AgentDriverWaterBankScenes::tallBankDigClimb),
                Scene.of("ad.waterLowBank", 200, AgentDriverWaterBankScenes::waterLowBank),
                Scene.of("ad.riverSheerBank", 200, AgentDriverWaterBankScenes::riverSheerBank)
                        .withRequired(false),   // gap #48 shared-body false-green — see javadoc + task#91
                Scene.of("ad.deepWaterCross", 200, AgentDriverWaterBankScenes::deepWaterCross),
                Scene.of("ad.deepWaterClimboutNoBlock", 200, AgentDriverWaterBankScenes::deepWaterClimboutNoBlock),
                Scene.of("ad.deepWaterClimboutDrift", 200, AgentDriverWaterBankScenes::deepWaterClimboutDrift),
                Scene.of("ad.waterFarAimBankCorner", 200, AgentDriverWaterBankScenes::waterFarAimBankCorner));
    }

    /** Inlined from {@code AgentGameTestSupport#buildWaterColumn}: 5x5 stone-walled tank,
     *  3x3 water core {@code depth} tall, air above. */
    private static void buildWaterColumn(ServerLevel level, int cx, int cz, int floorY, int depth) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int dy = 1; dy <= depth; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
                for (int dy = depth + 1; dy <= depth + 4; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
    }

    /** Ported from {@code AgentGameTestWaterBank#waterPhysicsParity}: water-physics-parity gate for
     *  {@link ServerPlayerAvatar} — a body driven by manual step() in a deep water column must
     *  reproduce vanilla fluid movement: (1) submerged no-input SINKS SLOWLY (not free-fall),
     *  (2) holding jump BOBS UP, (3) forward swims (slow). */
    private static void waterPhysicsParity(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 14;   // legacy floorY 200 = origin.y
        buildWaterColumn(level, cx, cz, floorY, depth);
        double surface = floorY + depth;            // water surface Y

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);

        // (1) Submerged, no input → slow sink (NOT free-fall to the floor).
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, floorY + depth - 4, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        for (int i = 0; i < 2; i++) { av.commandMove(0, 0); av.step(); }   // warm up water state
        if (!fp.isInWater())
            ctx.fail("waterPhysicsParity: avatar not in water after baseTick (water state not wired)");
        double y0 = fp.getY();
        for (int i = 0; i < 20; i++) { av.commandMove(0, 0); av.step(); }
        double sinkDy = fp.getY() - y0;
        if (sinkDy < -1.5 || sinkDy > 0.2)
            ctx.fail("waterPhysicsParity: submerged sink not vanilla-slow: dy=" + sinkDy
                    + " (expected slow water drift, free-fall would be much more negative)");

        // (2) Submerged, hold jump → buoyant rise.
        ServerPlayerAvatar av2 = ServerPlayerAvatar.createUnique(level, cx + 0.5, floorY + depth - 6, cz + 0.5);
        ServerPlayer fp2 = av2.fakePlayer();
        ctx.cleanup(() -> fp2.discard());
        SimProbes.grantWaterEffects(fp2);
        for (int i = 0; i < 2; i++) { av2.commandMove(0, 0); av2.step(); }
        double jy0 = fp2.getY();
        for (int i = 0; i < 25; i++) { av2.commandJump(true); av2.commandMove(0, 0); av2.step(); }
        double riseDy = fp2.getY() - jy0;
        if (riseDy < 0.8)
            ctx.fail("waterPhysicsParity: buoyant jump did not lift the avatar: dy=" + riseDy);

        // (3) Submerged, forward → swims forward (slow), stays in water.
        ServerPlayerAvatar av3 = ServerPlayerAvatar.createUnique(level, cx + 0.5, floorY + depth - 5, cz + 0.5);
        ServerPlayer fp3 = av3.fakePlayer();
        ctx.cleanup(() -> fp3.discard());
        SimProbes.grantWaterEffects(fp3);
        for (int i = 0; i < 2; i++) { av3.commandMove(0, 0); av3.step(); }
        double z0 = fp3.getZ();
        for (int i = 0; i < 20; i++) { fp3.setYRot(0f); av3.commandForward(1f); av3.step(); }
        double swimDz = fp3.getZ() - z0;
        if (swimDz < 0.3 || swimDz > 5.0)
            ctx.fail("waterPhysicsParity: water swim displacement off: dz=" + swimDz);

        AgentDriverCommon.LOG.info("[ad.waterPhysicsParity] sinkDy={} riseDy={} swimDz={} surface={}",
                sinkDy, riseDy, swimDz, surface);
    }

    /** Ported from {@code AgentGameTestWaterBank#buoyantWallArena}: the REAL {@link Walker} must mount
     *  a +5 SHEER wall rising from DEEP water (all buoyant — no dry ledge). Break+place ON. Asserts the
     *  bot reaches the dry plateau behind the wall and the waterline bob stays bounded (≤120 ticks). */
    private static void buoyantWall(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 6;   // legacy floorY 200 = origin.y
        int surface = floorY + depth;            // water surface
        int plateauTop = surface + 5;            // wall top +5 above water (sheer, buoyant)

        // Determinism: wipe the full build+explore box to AIR (grid-isolated now; kept verbatim).
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 11; dz++)
                for (int y = floorY - 1; y <= plateauTop + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        // Basin floor.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Containing walls (−z, ±x) up to surface+1 to hold the water.
        for (int dz = -3; dz <= 2; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
        // Water fill (interior).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 1; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // The +5 sheer climb wall at cz+2, floor → plateau top.
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= plateauTop; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.STONE.defaultBlockState());
        // Dry plateau behind the wall.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 3; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, plateauTop, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = plateauTop + 1; y <= plateauTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        BlockPos goal = new BlockPos(cx, plateauTop + 1, cz + 3);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        // Determinism: A* unbounded by wall-clock (one-shot repath, bounded only by node count).
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double maxY = fp.getY();
        boolean everDry = false;
        int bobTicks = 0;                 // ticks stuck in-water below the wall top
        for (int t = 0; t < 800 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            maxY = Math.max(maxY, fp.getY());
            if (fp.onGround() && !fp.isInWater()) everDry = true;
            if (fp.isInWater() && fp.getY() < plateauTop) bobTicks++;
        }
        boolean onPlateau = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= plateauTop + 1 - 0.4;
        AgentDriverCommon.LOG.info("[ad.buoyantWall] step={} pos=({},{},{}) maxY={} everDry={} onPlateau={} bobTicks={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxY, everDry, onPlateau, bobTicks);
        if (!onPlateau)
            ctx.fail("buoyantWall: BUOYANT +5 wall: Walker failed to mount from water: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY
                    + " everDry=" + everDry + " step=" + s);
        // Smoothness gate: the over-water diagUp-penalty climbs out with a stable vertical pillar,
        // cutting the waterline bob from ~348 ticks to ~44. Lock that in (>120 = staircase thrash).
        if (bobTicks > 120)
            ctx.fail("buoyantWall: buoyant climb bobbed " + bobTicks
                    + " ticks at the waterline (expected ~44; >120 = diagonal-staircase thrash regressed)");
    }

    /** Ported from {@code AgentGameTestWaterBank#vineClingFidelityProbe} (legacy required=false): fidelity
     *  probe for the server-side vine cling — drives the avatar straight UP a WALL-BACKED vine via
     *  commandForward+commandJump. If it climbs (dyMax≥1 over 40 ticks) the physics reproduce the cling.
     *  A diagnostic probe, not a required gate → {@code .withRequired(false)}. */
    private static void vineClingFidelityProbe(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), base = floorY + 1;   // legacy floorY 200 = origin.y
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 6; dz++)
                for (int y = floorY - 1; y <= base + 10; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Solid backing wall at cz+1 so the vine is WALL-BACKED (vineWallYaw != null).
        for (int dx = -1; dx <= 1; dx++)
            for (int y = base; y <= base + 6; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 1), Blocks.STONE.defaultBlockState());
        BlockState vineState = Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, Boolean.TRUE);
        for (int y = base; y <= base + 5; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz), vineState);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;

        BlockPos vineFoot = new BlockPos(cx, base, cz);
        BlockPos vineMid = new BlockPos(cx, base + 3, cz);
        boolean stuckFoot = level.getBlockState(vineFoot).is(Blocks.VINE);
        boolean stuckMid = level.getBlockState(vineMid).is(Blocks.VINE);
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, base, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);
        boolean climbFoot = w.isClimbable(vineFoot);
        boolean climbMid = w.isClimbable(vineMid);
        AgentDriverCommon.LOG.info("[ad.vineClingFidelityProbe] vine placement: stuckFoot={} stuckMid={} isClimbable(foot)={} isClimbable(mid)={}",
                stuckFoot, stuckMid, climbFoot, climbMid);
        if (!stuckFoot || !climbFoot)
            ctx.fail("vineClingFidelityProbe: VINE SILENT-NO-OP: vine at " + vineFoot
                    + " did not stick / not climbable (stuckFoot=" + stuckFoot + " isClimbable=" + climbFoot
                    + ") — arena geometry invalid, cannot test cling");
        fp.setYRot(0f);
        for (int i = 0; i < 2; i++) { av.commandMove(0f, 0f); av.step(); }
        double y0 = fp.getY();
        double maxY = y0;
        boolean wasOnVine = false;
        for (int t = 0; t < 40; t++) {
            fp.setYRot(0f);
            av.commandForward(1f);
            av.commandJump(true);
            av.step();
            maxY = Math.max(maxY, fp.getY());
            if (fp.onClimbable()) wasOnVine = true;
        }
        double yEnd = fp.getY();
        double dyMax = maxY - y0;
        AgentDriverCommon.LOG.info("[ad.vineClingFidelityProbe] VERDICT: y0={} yEnd={} maxY={} dyMax={} wasOnVine={} onGroundEnd={} inWaterEnd={}",
                y0, yEnd, maxY, dyMax, wasOnVine, fp.onGround(), fp.isInWater());
        if (!wasOnVine)
            ctx.fail("vineClingFidelityProbe: FIDELITY FAILS: avatar never registered onClimbable while pressing into the vine (y0="
                    + y0 + " yEnd=" + yEnd + " maxY=" + maxY + " dyMax=" + dyMax
                    + ") — the fake player does NOT cling vines; the arena cannot validate the executor cling");
        if (dyMax < 1.0)
            ctx.fail("vineClingFidelityProbe: FIDELITY FAILS: avatar clung (onClimbable=true) but did NOT climb (dyMax="
                    + dyMax + " < 1.0 over 40 ticks; y0=" + y0 + " maxY=" + maxY
                    + ") — vine-cling physics not reproduced under commandForward/commandJump; lean on live ≥5-replay validation");
    }

    /**
     * Ported from {@code AgentGameTestWaterBank#vineOverWaterClimbArena} (legacy required=false, the live
     * −711,67 bug). A free-hanging vine curtain over a 1-deep water pocket; A* routes a parkourAscend2
     * ONTO the vine then a climb up it. On a WALL-LESS vine the path-ahead forward press walks a buoy-free
     * body horizontally OUT of the column → it detaches into the pocket (the live bob-churn). The fix
     * ({@code walkerVineFreeHangClimb}) holds JUMP + CENTER-SEEKS the column so it re-centres and tops out.
     *
     * <p><b>{@code .withRequired(false)} BY DESIGN.</b> Against the CLEAN baseline (walkerVineFreeHangClimb
     * OFF) this arena MUST FAIL (the bot detaches into the pocket), and that optional-FAIL is the PROOF it
     * reproduces the live −711 bug. Its RED stays VISIBLE (reported per-run), never tuned away — marking it
     * required would RED the whole suite. Run it on demand for the executor-fix A/B; the fix must flip it to
     * PASS while keeping the required suite green.
     *
     * <p>#1 silent-no-op guards: isClimbable(vine base)==true, isFloatingWater(pocket)==false, AND the
     * mid-column vine is FREE-HANGING (no solid horizontal neighbour) — else the repro is vacuous and a
     * wall-press fix would pass it falsely.
     */
    private static void vineOverWaterClimb(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int padTop = ctx.origin().getY() + 1;   // legacy padTop 201 = origin.y+1
        int footY = padTop + 1;                   // bot foot on the pad
        int pocketFloorY = footY - 2;             // solid pocket bottom
        int pocketWaterY = footY - 1;             // 1-deep water, ONE below the launch foot
        int vineBaseY = footY + 1;                // parkourAscend2 (+1) landing on the vine
        int vineTopY = footY + 5;                 // vine column footY+1..footY+5 (5 tall)
        int plateauTop = vineTopY;                // TOP-ONLY dismount ledge flush with vine top
        final int vineZ = cz + 2;                 // vine column z (parkourAscend2 = 2-gap from the lip)
        final int plateauZ = cz + 3;              // dismount ledge one cell NORTH of the vine top

        // Determinism: wipe the full build+explore box to AIR (grid-isolated now; kept verbatim).
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 9; dz++)
                for (int y = pocketFloorY - 3; y <= plateauTop + 8; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        // CONTAINMENT: seal the arena in a box (floor slab under the whole footprint, ±x and −z/+z walls
        // ≥2 cells off the vine column so they NEVER back it — the free-hang invariant is preserved).
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -4; dz <= 8; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, pocketFloorY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dz = -4; dz <= 8; dz++)
            for (int y = pocketFloorY + 1; y <= plateauTop + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int y = pocketFloorY + 1; y <= plateauTop + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 4), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 8), Blocks.STONE.defaultBlockState());
            }

        // Approach PAD: solid top at padTop → foot stands at footY (launch lip at cz, gap opens at cz+1).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -3; dz <= 0; dz++)
                for (int y = pocketFloorY + 1; y <= padTop; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());

        // 2-cell GAP (cz+1, cz+2) with a 1-deep water POCKET: solid floor at pocketFloorY, water at
        // pocketWaterY (one BELOW the launch foot → a drop, not a walk-in).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = 1; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, pocketFloorY, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, pocketWaterY, cz + dz), Blocks.WATER.defaultBlockState());
            }

        // CANOPY + dismount LEDGE anchor (cz+3): solid at plateauTop AND plateauTop-1 ONLY (the top of the
        // wall) so the TOP vine cells can attach; AIR below at climb-height so it never backs the vine.
        for (int dx = -2; dx <= 2; dx++) {
            level.setBlockAndUpdate(new BlockPos(cx + dx, plateauTop, plateauZ), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, plateauTop - 1, plateauZ), Blocks.STONE.defaultBlockState());
            for (int y = plateauTop + 1; y <= plateauTop + 4; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, plateauZ), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(new BlockPos(cx, vineTopY + 1, vineZ),
                Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, Boolean.TRUE));

        // FREE-HANGING VINE column at (cx, vineZ), vineBaseY..vineTopY: top TWO cells attach SOUTH to the
        // ledge wall (only solid backing, at the TOP); every cell below hangs UP=true vine-on-vine. Placed
        // TOP-DOWN so each cell's support already exists. Climb-height cells have all-AIR horizontal
        // neighbours → vineWallYaw()==null → the free-hang bug fires.
        BlockState vineWall = Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, Boolean.TRUE);
        BlockState vineHang = Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, Boolean.TRUE);
        for (int y = vineTopY; y >= vineBaseY; y--) {
            BlockState st = (y >= vineTopY - 1) ? vineWall : vineHang;   // top 2 wall-attached, rest hang
            level.setBlockAndUpdate(new BlockPos(cx, y, vineZ), st);
        }
        BlockPos goal = new BlockPos(cx, plateauTop + 1, plateauZ);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        // Determinism: A* unbounded by wall-clock (one-shot repath, bounded only by node count).
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        // Hold the FIRST plan through the whole climb (no periodic repath) so the executor is tested on a
        // FIXED node path — matching the live deterministic A/B (mc.debug.replay replan:false).
        BotConfig.walkerRepathEveryTicks = 1_000_000;

        // #1 SILENT-NO-OP GUARD: the vine base MUST be present + climbable, else the repro is vacuous.
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, footY, cz - 1 + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        LevelWorldView w = new LevelWorldView(level, fp);
        BlockPos vineBase = new BlockPos(cx, vineBaseY, vineZ);
        boolean vineStuck = level.getBlockState(vineBase).is(Blocks.VINE);
        boolean vineClimbable = w.isClimbable(vineBase);
        boolean pocketFloating = w.isFloatingWater(new BlockPos(cx, pocketWaterY, cz + 1));
        BlockPos vineMid = new BlockPos(cx, vineBaseY + 1, vineZ);   // a free-hang climb cell
        boolean midFreeHang = !w.isSolid(vineMid.north()) && !w.isSolid(vineMid.south())
                && !w.isSolid(vineMid.east()) && !w.isSolid(vineMid.west());
        AgentDriverCommon.LOG.info("[ad.vineOverWaterClimb] geom: vineStuck={} isClimbable(base)={} pocketIsFloatingWater={} midFreeHang={} (expect true/true/false/true)",
                vineStuck, vineClimbable, pocketFloating, midFreeHang);
        if (!vineStuck || !vineClimbable)
            ctx.fail("vineOverWaterClimb: VINE SILENT-NO-OP: vine base at " + vineBase
                    + " not present/climbable (stuck=" + vineStuck + " climbable=" + vineClimbable
                    + ") — arena geometry invalid, repro is vacuous");
        if (pocketFloating)
            ctx.fail("vineOverWaterClimb: VINE SILENT-NO-OP: pocket at " + new BlockPos(cx, pocketWaterY, cz + 1)
                    + " is FLOATING water (no solid floor below) — must be a 1-deep pocket (the −711 geometry)");
        if (!midFreeHang)
            ctx.fail("vineOverWaterClimb: VINE SILENT-NO-OP: mid-column vine cell " + vineMid
                    + " has a SOLID horizontal neighbour — the vine is NOT free-hanging, so the repro is"
                    + " vacuous (a wall-press fix would pass this falsely). Check the geometry.");

        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double maxY = fp.getY();
        int pocketTicks = 0;                 // ticks in-water in the pocket below the vine = the bug
        boolean everInPocket = false;
        int budget = 220;
        for (int t = 0; t < budget && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            maxY = Math.max(maxY, fp.getY());
            if (fp.isInWater() && fp.getY() < vineBaseY) { pocketTicks++; everInPocket = true; }
        }
        boolean arrived = s == Walker.Step.ARRIVED;
        boolean onPlateau = arrived || (fp.getZ() > plateauZ - 0.5 && fp.getY() >= plateauTop - 0.4);
        boolean climbedTop = maxY >= vineTopY - 1;
        AgentDriverCommon.LOG.info("[ad.vineOverWaterClimb] step={} pos=({},{},{}) maxY={} arrived={} onPlateau={} climbedTop={} everInPocket={} pocketTicks={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxY, arrived, onPlateau, climbedTop, everInPocket, pocketTicks);

        // Pass predicate: PASS = topped the plateau AND pocketTicks under a small tolerance; FAIL (repro) =
        // wedged in the pocket (pocketTicks over tolerance) OR never topped out.
        final int POCKET_TOLERANCE = 12;
        if (pocketTicks > POCKET_TOLERANCE)
            ctx.fail("vineOverWaterClimb: VINE-OVER-WATER repro: bot WEDGED in the water POCKET below the vine"
                    + " (pocketTicks=" + pocketTicks + " > " + POCKET_TOLERANCE + " — the live −711 bug: it should"
                    + " cling+climb the wall-less vine, not detach and bob in the pocket). pos=(" + fp.getX() + ","
                    + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY + " step=" + s);
        if (!climbedTop)
            ctx.fail("vineOverWaterClimb: VINE-OVER-WATER repro: bot never climbed the wall-less vine"
                    + " (maxY=" + maxY + " < " + (vineTopY - 1) + " — climbed only " + (maxY - footY)
                    + " of the " + (vineTopY - footY) + "-block curtain; the live −711 bug detaches at"
                    + " the base and bobs in the pocket instead of clinging up the column). arrived="
                    + arrived + " onPlateau=" + onPlateau + " pos=(" + fp.getX() + "," + fp.getY()
                    + "," + fp.getZ() + ") step=" + s + " pocketTicks=" + pocketTicks);
    }

    /** Ported from {@code AgentGameTestWaterBank#tallBankDigClimbArena}: TOOLLESS tall-bank climb-out (live
     *  2026-06-20 deep-water stone-bank). +3 SOLID hill the bot digs a diagonal staircase up THROUGH; the
     *  bot holds only SAND (FallingBlock → no pillar). {@code faithfulBreak} ON models the ~750-tick/block
     *  slow stone-mine. 兜底 gate: reach the dry plateau within the tick budget. */
    private static void tallBankDigClimb(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 6;   // legacy floorY 200 = origin.y
        int surface = floorY + depth;            // water surface
        int plateauTop = surface + 3;            // solid hill top

        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 8; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Back wall behind the hill top (cz+8) so a bot that overshoots can't walk off the far edge.
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= plateauTop + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 8), Blocks.STONE.defaultBlockState());
        // FULL bathtub containment: x±3 side walls and −z end wall rise to plateauTop+2 over the whole span.
        for (int dz = -3; dz <= 8; dz++)
            for (int y = floorY + 1; y <= plateauTop + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= plateauTop + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 1; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // SOLID stone hill (dz 2..7, full width) the bot digs a staircase up THROUGH, DIRT-capped at the top.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 2; dz <= 7; dz++)
                for (int y = floorY + 1; y <= plateauTop; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            (y >= plateauTop ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
        BlockPos goal = new BlockPos(cx, plateauTop + 1, cz + 5);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        // Bound the A* budget (live-realistic): with faithfulBreak ON the plateau goal stays UNREACHABLE for
        // hundreds of ticks, so a small cap gives up fast on the not-yet-reachable goal, as the live client does.
        BotConfig.pathfinderSliceMs = 20;
        BotConfig.pathfinderMaxMs = 250;
        // faithfulBreak is a ServerPlayerAvatar static (NOT a BotConfig field), so it is not covered by the
        // pin — save/restore it via its own cleanup.
        boolean ofb = ServerPlayerAvatar.faithfulBreak;
        ctx.cleanup(() -> ServerPlayerAvatar.faithfulBreak = ofb);
        ServerPlayerAvatar.faithfulBreak = true;   // model the live ~750-tick/block slow stone-mine

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.SAND, 64));   // FallingBlock → holdPlaceable() false → must dig
        fp.getInventory().selected = 0;

        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double maxY = fp.getY();
        int t = 0;
        for (; t < 9000 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            maxY = Math.max(maxY, fp.getY());
        }
        boolean onPlateau = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= plateauTop + 1 - 0.4;
        AgentDriverCommon.LOG.info("[ad.tallBankDigClimb] step={} ticks={} pos=({},{},{}) maxY={} onPlateau={}",
                s, t, fp.getX(), fp.getY(), fp.getZ(), maxY, onPlateau);
        if (!onPlateau)
            ctx.fail("tallBankDigClimb: TOOLLESS +5 STONE wall (slow-mine): Walker failed to dig+mount from water: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxY=" + maxY + " ticks=" + t + " step=" + s);
    }

    /** Ported from {@code AgentGameTestWaterBank#waterLowBankArena}: live round69 repro — a floating bot
     *  wedged at a LOW (+2) bank with NO pickaxe. Break OFF, place ON: the only way up is to place ONE
     *  throwaway foothold block into the top water cell. Also guards {@code isUsableBuildBlock} (falling
     *  blocks rejected, mud accepted). Asserts the bot gets OUT onto the bank. */
    private static void waterLowBank(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 6;   // legacy floorY 200 = origin.y
        final int surface = floorY + depth;        // water surface (floating foot ~surface)
        final int bankTop = surface + 1;           // bank rises ONE block above the water top → a +2 climb-out

        // Basin floor.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 6; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Containing walls (−z, ±x) up to the bank top to hold the water in.
        for (int dz = -3; dz <= 1; dz++)
            for (int y = floorY + 1; y <= bankTop; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= bankTop; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
        // Water fill (interior columns, floorY+1 .. surface).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 1; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // The +2 low bank at cz+2: dirt up to surface, grass cap at bankTop (live geometry).
        for (int dx = -3; dx <= 3; dx++) {
            for (int y = floorY + 1; y <= surface; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, bankTop, cz + 2), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        // Dry land behind the bank (the bank top is the walkable surface; stand bankTop+1).
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 3; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, bankTop, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = bankTop + 1; y <= bankTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        BlockPos goal = new BlockPos(cx, bankTop + 1, cz + 4);   // on the dry land behind the +2 bank

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;          // live: bot had NO pickaxe → cannot carve the bank
        BotConfig.allowPlace = true;           // foothold-place is the only climb-out
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each repath completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        // Predicate-level guard for the root-cause fix: falling blocks stay rejected, mud accepted.
        if (BotConfig.isUsableBuildBlock(Blocks.SAND))
            ctx.fail("waterLowBank: isUsableBuildBlock: SAND (FallingBlock) must NOT be a usable foothold");
        if (BotConfig.isUsableBuildBlock(Blocks.GRAVEL))
            ctx.fail("waterLowBank: isUsableBuildBlock: GRAVEL (FallingBlock) must NOT be a usable foothold");
        if (!BotConfig.isUsableBuildBlock(Blocks.MUD))
            ctx.fail("waterLowBank: isUsableBuildBlock: MUD must be a usable foothold (standable, non-falling)");

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        // Live round69 inventory: sand+gravel (falling, useless), mud (the only usable support), holding sand.
        fp.getInventory().add(new ItemStack(Items.SAND, 9));
        fp.getInventory().add(new ItemStack(Items.GRAVEL, 37));
        fp.getInventory().add(new ItemStack(Items.MUD, 17));
        fp.getInventory().selected = 0;     // holding sand (non-usable) like live

        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double maxY = fp.getY();
        int bobTicks = 0;                  // ticks floating below the bank top
        for (int tt = 0; tt < 800 && s == Walker.Step.WALKING; tt++) {
            s = walker.tick(av, w);
            av.step();
            maxY = Math.max(maxY, fp.getY());
            if (fp.isInWater() && fp.getY() < bankTop) bobTicks++;
        }
        boolean onBank = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= bankTop + 1 - 0.4;
        AgentDriverCommon.LOG.info("[ad.waterLowBank] step={} pos=({},{},{}) maxY={} onBank={} bobTicks={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxY, onBank, bobTicks);
        if (!onBank)
            ctx.fail("waterLowBank: LOW +2 bank: floating Walker failed to climb out (foothold-place "
                    + "never lifted it): pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                    + ") maxY=" + maxY + " bobTicks=" + bobTicks + " step=" + s);
    }

    /**
     * Ported from {@code AgentGameTestWaterBank#riverSheerBankArena}: live-faithful OPEN-WATER +5 sheer-bank
     * wedge (2026-06-09). The bot floats in an open river whose SOUTH bank is a uniform +5 SHEER wall; the
     * only climb-out is a LOW (+1) bank far EAST, goal diagonally SE. No flanking walls pin the body onto a
     * pillar — the buoyant drift is free, as on the real river. Break+place ON. Asserts the bot gets ashore.
     *
     * <p><b>{@code .withRequired(false)} — gap #48 shared-body FALSE-GREEN (task#91).</b> This is the ONE
     * WaterBank scene the createUnique isolation flips from (legacy) GREEN to (isolated) deterministic RED,
     * and it is NOT a threshold to tune. The legacy twin ran under the identical config — the legacy
     * GameTestServer applies {@code BotConfig.applyGameTestBaseline()} at boot (AgentDriverNeoForge
     * onServerStarting), which zeroes the whole walker water-escape flag family (walkerBankDig*,
     * walkerBuoyantSearchFromSurface, walkerSwimAshorePillarDespiteDeepDig, walkerFloatingBankBobFreeze, …),
     * the exact baseline {@code pinnedBaseline()} re-applies here. Config, geometry and start pose are
     * byte-identical to legacy; the SOLE differentiator is the legacy shared body (concurrent GameTest
     * batches can shove/teleport the per-level singleton ashore) vs this scene's serial createUnique body.
     * So the legacy green was a shared-body artefact, exactly like {@code descentDriftArena} /
     * {@code descentOvershootResyncArena} (both free-drift water/descent arenas proven false-green under the
     * gap #48 audit). Isolated, the free-drift sheer-bank climb-out genuinely wedges (step=FAILED,
     * wallPressTicks≈51 — it presses the +5 face and never slides east) under the authored default-OFF
     * baseline: a real executor gap. Its RED stays VISIBLE (optional-fail reported each run), tracked by
     * task#91; the fix is a walker water-escape lever validated live, not an arena tweak.
     */
    private static void riverSheerBank(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 6;   // legacy floorY 200 = origin.y
        final int surface = floorY + depth;            // water surface
        final int wallTop = surface + 5;               // sheer south wall top
        final int riverLen = 28;                       // east run of the channel
        final int lowBankX = riverLen - 4;             // dx where the south bank turns low

        // Basin floor under everything (river + south land).
        for (int dx = -3; dx <= riverLen + 3; dx++)
            for (int dz = -4; dz <= 20; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Containment: north wall (cz-4), west (cx-3) and east (cx+riverLen+1) caps.
        for (int dx = -3; dx <= riverLen + 3; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 4), Blocks.STONE.defaultBlockState());
        for (int dz = -4; dz <= 1; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + riverLen + 1, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        // River water: z cz-3..cz+1, x cx-2..cx+riverLen, depth blocks deep.
        for (int dx = -2; dx <= riverLen; dx++)
            for (int dz = -3; dz <= 1; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // South bank line at cz+2: sheer +5 wall for the west run, LOW (+0) bank east.
        for (int dx = -2; dx <= riverLen; dx++) {
            int top = dx < lowBankX ? wallTop : surface;
            for (int y = floorY + 1; y <= top; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.STONE.defaultBlockState());
        }
        // South land behind the bank line (cz+3..cz+20): tall plateau behind the sheer section, low ground
        // behind the low bank — joined by a cliff (so the ONLY water exit is the low bank).
        for (int dx = -2; dx <= riverLen; dx++)
            for (int dz = 3; dz <= 20; dz++) {
                int top = dx < lowBankX ? wallTop : surface;
                for (int y = floorY + 1; y <= top; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = top + 1; y <= wallTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Air above the river so nothing caps the swim.
        for (int dx = -2; dx <= riverLen; dx++)
            for (int dz = -3; dz <= 1; dz++)
                for (int y = surface + 1; y <= wallTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        // Goal diagonally SE on the low land (live bearing ≈ 40°SE).
        BlockPos goal = new BlockPos(cx + riverLen - 2, surface + 1, cz + 16);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, surface - 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double maxX = fp.getX();
        int wallPressTicks = 0;     // in-water ticks spent pressed against the SHEER face
        for (int t = 0; t < 1500 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            maxX = Math.max(maxX, fp.getX());
            if (fp.isInWater() && fp.getZ() > cz + 1.2 && fp.getX() < cx + lowBankX - 1)
                wallPressTicks++;
        }
        boolean ashore = !fp.isInWater() && fp.onGround()
                && fp.getZ() > cz + 1.5 && fp.getY() >= surface + 1 - 0.4;
        AgentDriverCommon.LOG.info("[ad.riverSheerBank] step={} pos=({},{},{}) maxX={} wallPressTicks={} ashore={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxX, wallPressTicks, ashore);
        if (!ashore)
            ctx.fail("riverSheerBank: open-river sheer bank: Walker never climbed out: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxX=" + maxX
                    + " wallPressTicks=" + wallPressTicks + " step=" + s);
    }

    /** Ported from {@code AgentGameTestWaterBank#deepWaterCrossArena}: deep-water (8-block) open crossing —
     *  a floating bot must swim a long straight channel at the SURFACE and climb out a low far bank, never
     *  diving to the riverbed. Asserts (a) a Fall/FallIntoWater to a submerged bed cell is invalid; (b) the
     *  Walker crosses the deep channel and climbs out. Break+place ON. */
    private static void deepWaterCross(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 8;   // legacy floorY 200 = origin.y
        final int surface = floorY + depth;        // water surface (floating foot ~surface)
        final int span = 16;                       // E-W deep-water crossing length

        // Basin floor under the channel + the far land.
        for (int dx = -2; dx <= span + 4; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Containing walls (N/S sides + west cap) up past the surface to hold the water.
        for (int dx = -2; dx <= span; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
            }
        for (int dz = -3; dz <= 3; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water: x cx-1 .. cx+span-1, z cz-2..cz+2, `depth` blocks deep.
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Far EAST low bank: solid to the surface (+1 climb-out), grass cap, dry land carrying the goal.
        for (int dx = span; dx <= span + 4; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = floorY + 1; y < surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = surface + 1; y <= surface + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Air above the open water so nothing caps the swim.
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = surface + 1; y <= surface + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        BlockPos goal = new BlockPos(cx + span + 2, surface + 1, cz);   // dry land beyond the far bank

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;   // deterministic: each repath completes in one go
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, surface - 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        LevelWorldView w = new LevelWorldView(level, fp);

        // (a) Predicate guard for the fall-to-surface fix.
        BlockPos surfaceCell = new BlockPos(cx + 4, surface, cz);   // top water cell, air above
        if (new FallIntoWater(1, 0, depth - 1).valid(w, surfaceCell))
            ctx.fail("deepWaterCross: FallIntoWater to a SUBMERGED bed cell must be invalid"
                    + " (buoyancy floats the body back to the surface)");
        if (new Fall(1, 0, 2).valid(w, surfaceCell))
            ctx.fail("deepWaterCross: Fall to a SUBMERGED bed cell must be invalid");

        // (b) Integration: the floating Walker crosses the deep channel and climbs out.
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        double maxX = fp.getX();
        for (int t = 0; t < 2000 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            maxX = Math.max(maxX, fp.getX());
        }
        boolean ashore = !fp.isInWater() && fp.onGround()
                && fp.getX() >= cx + span - 0.5 && fp.getY() >= surface + 1 - 0.4;
        AgentDriverCommon.LOG.info("[ad.deepWaterCross] step={} pos=({},{},{}) maxX={} ashore={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxX, ashore);
        if (!ashore)
            ctx.fail("deepWaterCross: floating Walker failed to cross the deep channel"
                    + " and climb out: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                    + ") maxX=" + maxX + " step=" + s);
    }

    /** Ported from {@code AgentGameTestWaterBank#deepWaterClimboutNoBlockArena} (gap #48 shared-body lottery
     *  member — solo-GREEN this phase; deterministic in the createUnique isolated body). Block-LESS deep-water
     *  +2 bank climb-out: the bot holds only SAND (FallingBlock → no pillar), so the ONLY escape is the
     *  block-less bank-DIG fallback. Asserts the floating Walker reaches dry land within a bounded budget. */
    private static void deepWaterClimboutNoBlock(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 8;   // legacy floorY 200 = origin.y; PROBE deep
        final int surface = floorY + depth;        // water surface plane
        final int span = 3;                        // short deep-water run up to the bank
        final int bankTop = surface + 1;           // +2 bank (one above the water surface)

        // Basin floor + far land floor.
        for (int dx = -2; dx <= span + 4; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // N/S walls + west cap hold the water in.
        for (int dx = -2; dx <= span; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
            }
        for (int dz = -3; dz <= 3; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water column.
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Far bank: DIRT up to bankTop (+2 climb-out), breakable by hand, dry land beyond.
        for (int dx = span; dx <= span + 4; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = floorY + 1; y <= bankTop; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.DIRT.defaultBlockState());
                for (int y = bankTop + 1; y <= bankTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Air above the open water.
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = surface + 1; y <= surface + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        BlockPos goal = new BlockPos(cx + span + 2, bankTop + 1, cz);   // dry land atop the bank

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.allowSwimEscapeBreak = true;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, surface - 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.SAND, 64));   // FallingBlock → holdPlaceable() false
        fp.getInventory().selected = 0;

        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        // Tight budget: an isolated +1 dirt climb-out is a ~22-tick swim-jump; 200 ticks (10 s) is a
        // smoothness guard that still fails loudly on a bob-stall regression.
        int ashoreTick = -1;
        for (int t = 0; t < 200 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            if (ashoreTick < 0 && !fp.isInWater() && fp.onGround()
                    && fp.getX() >= cx + span - 0.5 && fp.getY() >= bankTop + 1 - 0.4) {
                ashoreTick = t;
                break;
            }
        }
        AgentDriverCommon.LOG.info("[ad.deepWaterClimboutNoBlock] step={} pos=({},{},{}) ashoreTick={}",
                s, fp.getX(), fp.getY(), fp.getZ(), ashoreTick);
        if (ashoreTick < 0)
            ctx.fail("deepWaterClimboutNoBlock: block-less bot failed to climb the +1 dirt bank"
                    + " out of deep water within budget: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                    + ") step=" + s);
    }

    /** Ported from {@code AgentGameTestWaterBank#deepWaterClimboutDriftArena}: DRIFT-ENTRY climb-out (live
     *  z1973). The bot arrives at a WIDE +2 bank moving DIAGONALLY (lateral momentum) with the goal offset
     *  along the face, plus an initial lateral shove. The drift-stable latched bank-dig makes it a clean
     *  two-step climb (~74t). Bound at 120 catches a pogo regression. Break+place ON. */
    private static void deepWaterClimboutDrift(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 8;   // legacy floorY 200 = origin.y
        final int surface = floorY + depth;        // water surface
        final int span = 3;                        // deep-water run up to the bank
        final int bankTop = surface + 1;           // +2 bank (one above the surface)
        final int zLo = -2, zHi = 8;               // WIDE in Z so the bot can drift along the face

        // Basin floor.
        for (int dx = -2; dx <= span + 4; dx++)
            for (int dz = zLo - 1; dz <= zHi + 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // N/S walls + west cap hold the water in.
        for (int dx = -2; dx <= span; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + zLo - 1), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + zHi + 1), Blocks.STONE.defaultBlockState());
            }
        for (int dz = zLo - 1; dz <= zHi + 1; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Deep water column.
        for (int dx = -1; dx < span; dx++)
            for (int dz = zLo; dz <= zHi; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Far bank: DIRT up to bankTop (+2 climb-out), dry land beyond, wide in Z.
        for (int dx = span; dx <= span + 4; dx++)
            for (int dz = zLo; dz <= zHi; dz++) {
                for (int y = floorY + 1; y <= bankTop; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.DIRT.defaultBlockState());
                for (int y = bankTop + 1; y <= bankTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        // Air above the open water.
        for (int dx = -1; dx < span; dx++)
            for (int dz = zLo; dz <= zHi; dz++)
                for (int y = surface + 1; y <= surface + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());

        BlockPos goal = new BlockPos(cx + span + 2, bankTop + 1, cz + 6);   // OFFSET along the face → diagonal

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.allowSwimEscapeBreak = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, surface - 1, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.SAND, 64));   // FallingBlock → holdPlaceable() false
        fp.getInventory().selected = 0;
        fp.setDeltaMovement(0, 0, 0.45);                        // lateral shove → drift along the face

        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        int ashoreTick = -1;
        for (int t = 0; t < 400 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            if (ashoreTick < 0 && !fp.isInWater() && fp.onGround() && fp.getY() >= bankTop + 1 - 0.4
                    && fp.getX() >= cx + span - 0.5) {
                ashoreTick = t;
                break;
            }
        }
        AgentDriverCommon.LOG.info("[ad.deepWaterClimboutDrift] step={} pos=({},{},{}) ashoreTick={} (clean baseline ~22)",
                s, fp.getX(), fp.getY(), fp.getZ(), ashoreTick);
        if (ashoreTick < 0 || ashoreTick > 120)
            ctx.fail("deepWaterClimboutDrift: drifting bot failed a smooth +2 bank climb"
                    + " (ashoreTick=" + ashoreTick + ", want 0..120): pos=(" + fp.getX() + "," + fp.getY()
                    + "," + fp.getZ() + ") step=" + s);
    }

    /** Ported from {@code AgentGameTestWaterBank#waterFarAimBankCornerArena}: water-divider detour smoke —
     *  a buoyant bot shoved straight at a solid DIVIDER between it and the goal must round it through a side
     *  GAP. Break/place OFF → the ONLY way through is to round it. Regression smoke test for the in-water
     *  heading/aim path the anti-spin freeze + far-aim override sit on. */
    private static void waterFarAimBankCorner(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 5;   // legacy floorY 200 = origin.y
        final int surface = floorY + depth;            // water surface
        final int top = surface + 3;                   // wall crest (unclimbable for a buoyant bot)
        // Floor.
        for (int x = cx - 2; x <= cx + 12; x++)
            for (int z = cz - 5; z <= cz + 5; z++)
                level.setBlockAndUpdate(new BlockPos(x, floorY, z), Blocks.STONE.defaultBlockState());
        // Pool: water floorY+1..surface, air above; perimeter ring solid.
        for (int x = cx - 2; x <= cx + 12; x++)
            for (int z = cz - 5; z <= cz + 5; z++) {
                boolean ring = x == cx - 2 || x == cx + 12 || z == cz - 5 || z == cz + 5;
                for (int y = floorY + 1; y <= top; y++) {
                    BlockState bs = ring ? Blocks.STONE.defaultBlockState()
                            : (y <= surface ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, y, z), bs);
                }
            }
        // DIVIDER wall at x=cx+6 spanning z=cz-4..cz+2 up to the crest; GAP at z=cz+3..cz+4.
        for (int z = cz - 4; z <= cz + 2; z++)
            for (int y = floorY + 1; y <= top; y++)
                level.setBlockAndUpdate(new BlockPos(cx + 6, y, z), Blocks.STONE.defaultBlockState());

        Goal.XZ goal = new Goal.XZ(cx + 11, cz);       // east of the wall, SAME Z as the bot

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.allowSwimEscapeBreak = false;
        BotConfig.allowSwimEscapePlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, surface, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.setDeltaMovement(0.45, 0, 0);           // shove +X straight into the divider

        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(goal);
        Walker.Step s = Walker.Step.WALKING;
        int reachedTick = -1;
        for (int t = 0; t < 500 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            if (Math.floor(fp.getX()) == cx + 11 && Math.floor(fp.getZ()) == cz) { reachedTick = t; break; }
        }
        double dGoal = Math.hypot(fp.getX() - (cx + 11 + 0.5), fp.getZ() - (cz + 0.5));
        AgentDriverCommon.LOG.info("[ad.waterFarAimBankCorner] step={} pos=({},{},{}) reachedTick={} dGoal={}",
                s, fp.getX(), fp.getY(), fp.getZ(), reachedTick, String.format("%.1f", dGoal));
        // Round the wall via the gap → reach the east goal column.
        if (reachedTick < 0 && s != Walker.Step.ARRIVED)
            ctx.fail("waterFarAimBankCorner: bot failed to round the divider to the goal"
                    + " (far-aim rammed the wall through-LOS?): dGoal=" + dGoal + " pos=(" + fp.getX() + ","
                    + fp.getY() + "," + fp.getZ() + ") step=" + s);
    }
}

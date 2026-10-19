package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;

import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
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
 * Dogfooded worlddriver scenes — <b>P4b wave 4, the WaterBank family</b>: the 11
 * {@code AgentGameTestWaterBank} water-physics / bank-climb-out arenas (deep-water
 * buoyant-wall / vine cling / vine-over-water −711 repro / tall-bank dig / low-bank
 * foothold / river sheer bank / deep-water cross / block-less climb-out / drift entry /
 * water far-aim divider), migrated verbatim to testkit {@code wd.*} scenes and their
 * legacy twins retired in the same commit.
 *
 * <p><b>Porting is by the canonical pattern established in {@link WorldDriverScenes} /
 * {@link WorldDriverTerrainScenes}</b> (study their class javadocs for the full rationale —
 * this class applies the same mechanical substitutions and does not re-explain them):
 * <ul>
 *   <li>{@code helper.getLevel()} → {@link SceneContext#level()};</li>
 *   <li>absolute {@code cx/cz} → {@link SceneContext#origin()} X/Z (arena math
 *       byte-identical, just relocated to the harness grid cell);</li>
 *   <li>absolute Y constants → {@code origin.y + (legacy_Y − 200)}. Because every grid
 *       origin sits at {@code GRID_Y = 200}, the mapped ABSOLUTE Y equals the legacy
 *       absolute Y — the vertical water geometry is literally unchanged, only X/Z relocate
 *       (buoyancy/travel physics is y-invariant in this range);</li>
 *   <li>{@code try/finally} per-key config save/restore → {@code Harness.pin(ctx)} (the pinned
 *       baseline, restored when the scene ends) then the SAME keys the legacy body flipped,
 *       {@code faithfulBreak} among them now that it is a setting;</li>
 *   <li>{@code ServerPlayerBody.create(...)} → a per-scene roster bot ({@code SceneBot.spawn},
 *       removed when the scene ends). In the ISOLATED per-scene player model the legacy
 *       shared-FakePlayer parking / anti-contamination finally blocks (e.g.
 *       {@code vineOverWaterClimbArena}'s {@code cleanupFp} re-park, the legacy per-arena isolation
 *       batches) become dead weight and are DROPPED — a per-scene bot cannot bleed into another
 *       scene, so those guards had nothing left to protect;</li>
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
 * The in-body synchronous walks run on the scene's first RUN tick, same as the legacy GameTest
 * shell: an {@code mc.bot.goto} ticked one whole bot tick at a time (perceive, reflexes, task,
 * body) where the first port ticked a bare navigator and the body. The legacy determinism AIR-clear boxes are kept verbatim (harmless under grid
 * isolation — the arena rebuilds over them).
 *
 * <p><b>Lottery / optional governance (this wave's core discipline).</b>
 * <ul>
 *   <li>{@code wd.vineClingFidelityProbe} and {@code wd.vineOverWaterClimb} are legacy
 *       {@code required = false} → migrated {@code .withRequired(false)}. The vine-over-water
 *       arena is the documented <b>live −711,67 bug</b> repro: against the CLEAN baseline
 *       (navigatorVineFreeHangClimb OFF) it MUST FAIL, and that optional-FAIL is the proof it
 *       reproduces the live wall-less-vine detach. Its RED stays VISIBLE (reported per-run),
 *       never tuned away — marking it required would RED the whole suite. See its own javadoc.</li>
 *   <li>{@code wd.deepWaterClimboutNoBlock} is the shared-player lottery member
 *       (solo-GREEN proven this phase, full-run flaky in the OLD shared-player suite). With the
 *       createUnique isolated player the shared-player flake mechanism is GONE, so it runs
 *       DETERMINISTICALLY GREEN (observed ×2×2) — kept {@code required=true}; the determinism
 *       is the expected outcome of player isolation (a shell difference), NOT a rebaseline of
 *       thresholds.</li>
 *   <li>{@code wd.riverSheerBank} → <b>required</b>. The shared-player
 *       false-green this wave surfaced was a REAL executor gap, now fixed structurally: A* always
 *       routed the correct far-lateral exit (the low bank +5 EAST across open water), but the
 *       climb-out executor misread that laterally-distant, only-+1-higher waypoint as a climb-here
 *       intent and trenched the SHEER wall the bot was merely passing. The
 *       {@code navigatorWaterClimbLateralGate} invariant (default ON, baseline-EXEMPT) gates the
 *       climb-out on lateral adjacency, so the bot swims the corridor to the real walk-out. K≥6 A/B
 *       both loaders: gate OFF 6/6 wedge (wallPressTicks 51), gate ON 6/6 ashore (byte-identical
 *       ARRIVED); the other 10 water families are byte-unchanged. See its method javadoc.</li>
 * </ul>
 *
 * <p><b>Origin slots.</b> All 11 take AUTO slots at the default radius — every WaterBank
 * gate is an OUTCOME (onPlateau / onBank / ashore / bobTicks under a wide tolerance), not a
 * byte-determinism golden, so registry-growth relocation cannot flip it. No scene overflows
 * the radius-1 window (usable dx/dz {@code [−16,+31]}): the widest, {@code wd.riverSheerBank},
 * reaches exactly dx +31 (basin floor {@code cx-3..riverLen+3 = -3..+31}), which fits.
 */
public final class WorldDriverWaterBankScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.waterPhysicsParity", 200, WorldDriverWaterBankScenes::waterPhysicsParity),
                Scene.of("wd.buoyantWall", 200, WorldDriverWaterBankScenes::buoyantWall),
                Scene.of("wd.vineClingFidelityProbe", 200, WorldDriverWaterBankScenes::vineClingFidelityProbe)
                        .withRequired(false),
                Scene.of("wd.vineOverWaterClimb", 200, WorldDriverWaterBankScenes::vineOverWaterClimb)
                        .withRequired(false),
                Scene.of("wd.tallBankDigClimb", 200, WorldDriverWaterBankScenes::tallBankDigClimb),
                Scene.of("wd.waterLowBank", 200, WorldDriverWaterBankScenes::waterLowBank),
                Scene.of("wd.riverSheerBank", 200, WorldDriverWaterBankScenes::riverSheerBank),   // required (task#91 structural fix: navigatorWaterClimbLateralGate)
                Scene.of("wd.deepWaterCross", 200, WorldDriverWaterBankScenes::deepWaterCross),
                Scene.of("wd.deepWaterClimboutNoBlock", 200, WorldDriverWaterBankScenes::deepWaterClimboutNoBlock),
                Scene.of("wd.deepWaterClimboutDrift", 200, WorldDriverWaterBankScenes::deepWaterClimboutDrift),
                Scene.of("wd.waterFarAimBankCorner", 200, WorldDriverWaterBankScenes::waterFarAimBankCorner),
                // J31, a pair. Neither is about whether the bot climbs — both are about what the
                // place-futility ledger counts. The positive one cannot pass while the counter is
                // zeroed by the CLICK; the negative one cannot pass if the counter is made to
                // increment unconditionally, which is the regression the positive one invites.
                Scene.of("wd.pillarLedgerCountsRefusedPlaces", 500,
                        WorldDriverWaterBankScenes::pillarLedgerCountsRefusedPlaces),
                Scene.of("wd.pillarLedgerClearsOnARealPlace", 500,
                        WorldDriverWaterBankScenes::pillarLedgerClearsOnARealPlace));
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
     *  the server-side player body — a player driven by manual step() in a deep water column must
     *  reproduce vanilla fluid movement: (1) submerged no-input SINKS SLOWLY (not free-fall),
     *  (2) holding jump BOBS UP, (3) forward swims (slow). */
    private static void waterPhysicsParity(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), depth = 14;   // legacy floorY 200 = origin.y
        buildWaterColumn(level, cx, cz, floorY, depth);
        double surface = floorY + depth;            // water surface Y

        Harness.pin(ctx);

        // (1) Submerged, no input → slow sink (NOT free-fall to the floor).
        // Each tick presses its keys again, as the old body loop did: a held jump lapses on any step
        // taken out of water, so pressing it once is not the same input.
        SceneBot av = SceneBot.spawn(ctx, level, cx + 0.5, floorY + depth - 4, cz + 0.5);
        ServerPlayer fp = av.player();
        SimProbes.grantWaterEffects(fp);
        for (int i = 0; i < 2; i++) { av.press(av.keys().move(0, 0)); av.step(); }   // warm up water state
        if (!fp.isInWater())
            ctx.fail("waterPhysicsParity: avatar not in water after baseTick (water state not wired)");
        double y0 = fp.getY();
        for (int i = 0; i < 20; i++) { av.press(av.keys().move(0, 0)); av.step(); }
        double sinkDy = fp.getY() - y0;
        if (sinkDy < -1.5 || sinkDy > 0.2)
            ctx.fail("waterPhysicsParity: submerged sink not vanilla-slow: dy=" + sinkDy
                    + " (expected slow water drift, free-fall would be much more negative)");

        // (2) Submerged, hold jump → buoyant rise.
        SceneBot av2 = SceneBot.spawn(ctx, level, cx + 0.5, floorY + depth - 6, cz + 0.5);
        ServerPlayer fp2 = av2.player();
        SimProbes.grantWaterEffects(fp2);
        for (int i = 0; i < 2; i++) { av2.press(av2.keys().move(0, 0)); av2.step(); }
        double jy0 = fp2.getY();
        for (int i = 0; i < 25; i++) { av2.press(av2.keys().jump(true).move(0, 0)); av2.step(); }
        double riseDy = fp2.getY() - jy0;
        if (riseDy < 0.8)
            ctx.fail("waterPhysicsParity: buoyant jump did not lift the avatar: dy=" + riseDy);

        // (3) Submerged, forward → swims forward (slow), stays in water.
        SceneBot av3 = SceneBot.spawn(ctx, level, cx + 0.5, floorY + depth - 5, cz + 0.5);
        ServerPlayer fp3 = av3.player();
        SimProbes.grantWaterEffects(fp3);
        for (int i = 0; i < 2; i++) { av3.press(av3.keys().move(0, 0)); av3.step(); }
        double z0 = fp3.getZ();
        for (int i = 0; i < 20; i++) { fp3.setYRot(0f); av3.press(av3.keys().move(0, 1)); av3.step(); }
        double swimDz = fp3.getZ() - z0;
        if (swimDz < 0.3 || swimDz > 5.0)
            ctx.fail("waterPhysicsParity: water swim displacement off: dz=" + swimDz);

        TestMod.LOG.info("[wd.waterPhysicsParity] sinkDy={} riseDy={} swimDz={} surface={}",
                sinkDy, riseDy, swimDz, surface);
    }

    /** Ported from {@code AgentGameTestWaterBank#buoyantWallArena}: the REAL navigator must mount
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

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", true);
        // Determinism: A* unbounded by wall-clock (one-shot repath, bounded only by node count).
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        // This bot's own counters, so another scene climbing out of water cannot move them.
        BotDebugContracts.NavigatorReading nav0 = bot.navigator();
        final long engages0 = nav0.counter("waterPillarEngages");
        final long bails0 = nav0.counter("waterPillarCeilingBails");
        bot.goTo(Targets.block(goal));

        double maxY = fp.getY();
        boolean everDry = false;
        int bobTicks = 0;                 // ticks stuck in-water below the wall top
        boolean walking = true;
        for (int t = 0; t < 800 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            maxY = Math.max(maxY, fp.getY());
            if (fp.onGround() && !fp.isInWater()) everDry = true;
            if (fp.isInWater() && fp.getY() < plateauTop) bobTicks++;
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        BotDebugContracts.NavigatorReading nav = bot.navigator();
        boolean onPlateau = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= plateauTop + 1 - 0.4;
        TestMod.LOG.info("[wd.buoyantWall] step={} pos=({},{},{}) maxY={} everDry={} onPlateau={} bobTicks={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxY, everDry, onPlateau, bobTicks);
        // THE FIRST EVIDENCE THIS FAMILY HAS EVER RECORDED. All eleven water-climb scenes published
        // nothing but their own colour, which is why a safety bail that was UNCONDITIONALLY FALSE
        // (see WaterClimb.engagePillar) survived here unnoticed: a guard that cannot fire
        // changes no colour, and colour was the only channel. Recorded on every run, PASS included —
        // a PASS prints no failure text, so this is the only place a reader can see whether the
        // pillar branch was even entered.
        long engages = nav.counter("waterPillarEngages") - engages0;
        long bails = nav.counter("waterPillarCeilingBails") - bails0;
        long topRise = nav.counter("waterPillarTopRise");
        ctx.record("pillar.engages", engages == 0
                ? "0 — this run never took the pillar takeover path, so it measured nothing about that path"
                : String.valueOf(engages));
        ctx.record("pillar.ceilingBails", engages == 0
                ? "unavailable/the takeover never happened; 0 does not mean the guard did not fire, it"
                        + " means the guard was never consulted"
                : bails + " times past the ceiling at +" + 5 + " above the feet at engage");
        ctx.record("pillar.topRise", engages == 0
                ? "unavailable/same as above"
                : topRise + " blocks ("
                        + (5 - topRise) + " blocks short of the ceiling)");
        if (!onPlateau)
            ctx.fail("buoyantWall: BUOYANT +5 wall: Navigator failed to mount from water: pos=("
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

        Harness.pin(ctx);
        Harness.set("navigatorDebug", true);

        BlockPos vineFoot = new BlockPos(cx, base, cz);
        BlockPos vineMid = new BlockPos(cx, base + 3, cz);
        boolean stuckFoot = level.getBlockState(vineFoot).is(Blocks.VINE);
        boolean stuckMid = level.getBlockState(vineMid).is(Blocks.VINE);
        SceneBot av = SceneBot.spawn(ctx, level, cx + 0.5, base, cz + 0.5);
        ServerPlayer fp = av.player();
        SimProbes.grantWaterEffects(fp);
        boolean climbFoot = NavWalk.cell(av, vineFoot).climbable();
        boolean climbMid = NavWalk.cell(av, vineMid).climbable();
        TestMod.LOG.info("[wd.vineClingFidelityProbe] vine placement: stuckFoot={} stuckMid={} isClimbable(foot)={} isClimbable(mid)={}",
                stuckFoot, stuckMid, climbFoot, climbMid);
        if (!stuckFoot || !climbFoot)
            ctx.fail("vineClingFidelityProbe: VINE SILENT-NO-OP: vine at " + vineFoot
                    + " did not stick / not climbable (stuckFoot=" + stuckFoot + " isClimbable=" + climbFoot
                    + ") — arena geometry invalid, cannot test cling");
        fp.setYRot(0f);
        for (int i = 0; i < 2; i++) { av.press(av.keys().move(0, 0)); av.step(); }
        double y0 = fp.getY();
        double maxY = y0;
        boolean wasOnVine = false;
        for (int t = 0; t < 40; t++) {
            fp.setYRot(0f);
            // Pressed every tick: a held jump lapses on each step taken out of water.
            av.press(av.keys().move(0, 1).jump(true));
            av.step();
            maxY = Math.max(maxY, fp.getY());
            if (fp.onClimbable()) wasOnVine = true;
        }
        double yEnd = fp.getY();
        double dyMax = maxY - y0;
        TestMod.LOG.info("[wd.vineClingFidelityProbe] VERDICT: y0={} yEnd={} maxY={} dyMax={} wasOnVine={} onGroundEnd={} inWaterEnd={}",
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
     * bot horizontally OUT of the column → it detaches into the pocket (the live bob-churn). The fix
     * ({@code navigatorVineFreeHangClimb}) holds JUMP + CENTER-SEEKS the column so it re-centres and tops out.
     *
     * <p><b>{@code .withRequired(false)} BY DESIGN.</b> Against the CLEAN baseline (navigatorVineFreeHangClimb
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

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", true);
        // Determinism: A* unbounded by wall-clock (one-shot repath, bounded only by node count).
        Harness.untimed(ctx);
        // Hold the FIRST plan through the whole climb (no periodic repath) so the executor is tested on a
        // FIXED node path — matching the live deterministic A/B (mc.debug.replay replan:false).
        Harness.set("navigator.repathEveryTicks", 1_000_000);

        // #1 SILENT-NO-OP GUARD: the vine base MUST be present + climbable, else the repro is vacuous.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, footY, cz - 1 + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        BlockPos vineBase = new BlockPos(cx, vineBaseY, vineZ);
        boolean vineStuck = level.getBlockState(vineBase).is(Blocks.VINE);
        boolean vineClimbable = NavWalk.cell(bot, vineBase).climbable();
        // Floating water is water standing on water: the cell and the one under it.
        BlockPos pocket = new BlockPos(cx, pocketWaterY, cz + 1);
        boolean pocketFloating = NavWalk.cell(bot, pocket).water() && NavWalk.cell(bot, pocket.below()).water();
        BlockPos vineMid = new BlockPos(cx, vineBaseY + 1, vineZ);   // a free-hang climb cell
        boolean midFreeHang = !NavWalk.cell(bot, vineMid.north()).solid() && !NavWalk.cell(bot, vineMid.south()).solid()
                && !NavWalk.cell(bot, vineMid.east()).solid() && !NavWalk.cell(bot, vineMid.west()).solid();
        TestMod.LOG.info("[wd.vineOverWaterClimb] geom: vineStuck={} isClimbable(base)={} pocketIsFloatingWater={} midFreeHang={} (expect true/true/false/true)",
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

        bot.goTo(Targets.block(goal));

        double maxY = fp.getY();
        int pocketTicks = 0;                 // ticks in-water in the pocket below the vine = the bug
        boolean everInPocket = false;
        int budget = 220;
        boolean walking = true;
        for (int t = 0; t < budget && walking; t++) {
            bot.tick();
            walking = bot.busy();
            maxY = Math.max(maxY, fp.getY());
            if (fp.isInWater() && fp.getY() < vineBaseY) { pocketTicks++; everInPocket = true; }
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        boolean arrived = !walking;
        boolean onPlateau = arrived || (fp.getZ() > plateauZ - 0.5 && fp.getY() >= plateauTop - 0.4);
        boolean climbedTop = maxY >= vineTopY - 1;
        TestMod.LOG.info("[wd.vineOverWaterClimb] step={} pos=({},{},{}) maxY={} arrived={} onPlateau={} climbedTop={} everInPocket={} pocketTicks={}",
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
     *  slow stone-mine. Fallback gate: reach the dry plateau within the tick budget. */
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

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", true);
        // Bound the A* budget (live-realistic): with faithfulBreak ON the plateau goal stays UNREACHABLE for
        // hundreds of ticks, so a small cap gives up fast on the not-yet-reachable goal, as the live client does.
        Harness.set("pathfinder.sliceMs", 20);
        Harness.set("pathfinder.maxMs", 250);
        Harness.set("faithfulBreak", true);   // model the live ~750-tick/block slow stone-mine

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.SAND, 64));   // FallingBlock → holdPlaceable() false → must dig
        fp.getInventory().selected = 0;

        bot.goTo(Targets.block(goal));
        double maxY = fp.getY();
        int t = 0;
        boolean walking = true;
        for (; t < 9000 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            maxY = Math.max(maxY, fp.getY());
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        boolean onPlateau = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= plateauTop + 1 - 0.4;
        TestMod.LOG.info("[wd.tallBankDigClimb] step={} ticks={} pos=({},{},{}) maxY={} onPlateau={}",
                s, t, fp.getX(), fp.getY(), fp.getZ(), maxY, onPlateau);
        if (!onPlateau)
            ctx.fail("tallBankDigClimb: TOOLLESS +5 STONE wall (slow-mine): Navigator failed to dig+mount from water: pos=("
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

        Harness.pin(ctx);
        Harness.set("allowBreak", false);          // live: bot had NO pickaxe → cannot carve the bank
        Harness.set("allowPlace", true);           // foothold-place is the only climb-out
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);   // deterministic: each repath completes in one go
        // The predicate-level guard for the root-cause fix (falling blocks rejected, mud accepted) is
        // BuildBlocksTest, a unit test over the same isUsableBuildBlock.

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        // Live round69 inventory: sand+gravel (falling, useless), mud (the only usable support), holding sand.
        fp.getInventory().add(new ItemStack(Items.SAND, 9));
        fp.getInventory().add(new ItemStack(Items.GRAVEL, 37));
        fp.getInventory().add(new ItemStack(Items.MUD, 17));
        fp.getInventory().selected = 0;     // holding sand (non-usable) like live

        bot.goTo(Targets.block(goal));
        double maxY = fp.getY();
        int bobTicks = 0;                  // ticks floating below the bank top
        boolean walking = true;
        for (int tt = 0; tt < 800 && walking; tt++) {
            bot.tick();
            walking = bot.busy();
            maxY = Math.max(maxY, fp.getY());
            if (fp.isInWater() && fp.getY() < bankTop) bobTicks++;
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        boolean onBank = fp.getZ() > (cz + 2) + 0.5 && fp.getY() >= bankTop + 1 - 0.4;
        TestMod.LOG.info("[wd.waterLowBank] step={} pos=({},{},{}) maxY={} onBank={} bobTicks={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxY, onBank, bobTicks);
        if (!onBank)
            ctx.fail("waterLowBank: LOW +2 bank: floating Navigator failed to climb out (foothold-place "
                    + "never lifted it): pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                    + ") maxY=" + maxY + " bobTicks=" + bobTicks + " step=" + s);
    }

    /**
     * Ported from {@code AgentGameTestWaterBank#riverSheerBankArena}: live-faithful OPEN-WATER +5 sheer-bank
     * wedge (2026-06-09). The bot floats in an open river whose SOUTH bank is a uniform +5 SHEER wall; the
     * only climb-out is a LOW (+1) bank far EAST, goal diagonally SE. No flanking walls pin the bot onto a
     * pillar — the buoyant drift is free, as on the real river. Break+place ON. Asserts the bot gets ashore.
     *
     * <p><b>required — structural fix.</b> The shared-player false-green this
     * scene surfaced was a REAL executor gap. Config, geometry and start pose are byte-identical to the
     * legacy twin, which ran green only because concurrent GameTest batches shoved the shared singleton
     * ashore; this scene's serial createUnique player has no such helper and, under the authored default-OFF
     * baseline {@code pinnedBaseline()} zeroes (navigatorBankDig*, navigatorBuoyantSearchFromSurface,
     * navigatorSwimAshorePillarDespiteDeepDig, navigatorFloatingBankBobFreeze, …), it wedged (step=FAILED,
     * wallPressTicks≈51 — pressing the +5 face, never sliding east).
     *
     * <p><b>Investigation verdict = EXECUTOR-root.</b> Telemetry (committed-path endpoint + climb-engage
     * cwp, ×3 runs) showed A* ALWAYS returns the correct path: {@code goalReached=true}, endpoint the low
     * bank +5 EAST across open water. But at the wall the committed waypoint {@code cwp} sat +5 east / +2
     * south / only +1 UP, and the old {@code cwp.y>foot.y} climb intent fired — so the block-less bank dig
     * trenched the SHEER wall directly beside the bot (risers z=cz+2, y climbing surface→wallTop) instead
     * of swimming the last few cells to the flat low walk-out. Root: a laterally-DISTANT higher waypoint
     * misread as a climb-HERE intent.
     *
     * <p><b>Fix = {@code navigatorWaterClimbLateralGate}</b> (default ON, deliberately NOT in
     * {@code applyGameTestBaseline()} — a correctness invariant, not a tunable): the floating climb-out
     * (pillar + block-less dig) engages only when the climb waypoint is horizontally BESIDE the bot
     * ({@code WATER_CLIMB_LATERAL_MAX}=2, Chebyshev); a farther higher waypoint is the routed corridor
     * exit, reached by the swim-drive (climb re-arms once adjacent). K≥6 A/B, both loaders: gate OFF 6/6
     * wedge (wallPressTicks 51, ashore=false), gate ON 6/6 ashore (byte-identical ARRIVED, wallPressTicks
     * 54); the other 10 WaterBank+WaterCross families are byte-unchanged. Now asserts the bot gets ashore.
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

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);

        // Adjacent to the bank (cz+2), the way wd.waterLowBank spawns. Three blocks out — where an
        // earlier version of this scene put the bot — the takeover's entry gate
        // (WalkerTickClimb:455-457) never fires and the arena measures nothing.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        bot.goTo(Targets.block(goal));
        double maxX = fp.getX();
        int wallPressTicks = 0;     // in-water ticks spent pressed against the SHEER face
        boolean walking = true;
        for (int t = 0; t < 1500 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            maxX = Math.max(maxX, fp.getX());
            if (fp.isInWater() && fp.getZ() > cz + 1.2 && fp.getX() < cx + lowBankX - 1)
                wallPressTicks++;
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        boolean ashore = !fp.isInWater() && fp.onGround()
                && fp.getZ() > cz + 1.5 && fp.getY() >= surface + 1 - 0.4;
        TestMod.LOG.info("[wd.riverSheerBank] step={} pos=({},{},{}) maxX={} wallPressTicks={} ashore={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxX, wallPressTicks, ashore);
        if (!ashore)
            ctx.fail("riverSheerBank: open-river sheer bank: Navigator never climbed out: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") maxX=" + maxX
                    + " wallPressTicks=" + wallPressTicks + " step=" + s);
    }

    /** Ported from {@code AgentGameTestWaterBank#deepWaterCrossArena}: deep-water (8-block) open crossing —
     *  a floating bot must swim a long straight channel at the SURFACE and climb out a low far bank, never
     *  diving to the riverbed. Asserts (a) a Fall/FallIntoWater to a submerged bed cell is invalid; (b) the
     *  Navigator crosses the deep channel and climbs out. Break+place ON. */
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

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);   // deterministic: each repath completes in one go

        // Adjacent to the bank (cz+2), the way wd.waterLowBank spawns. Three blocks out — where an
        // earlier version of this scene put the bot — the takeover's entry gate
        // (WalkerTickClimb:455-457) never fires and the arena measures nothing.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        fp.getInventory().selected = 0;

        // (a) The predicate guard for the fall-to-surface fix (a Fall / FallIntoWater onto a submerged
        // bed cell is invalid) is SubmergedLandingTest, a unit test over the same channel as a grid.

        // (b) Integration: the floating Navigator crosses the deep channel and climbs out.
        bot.goTo(Targets.block(goal));
        double maxX = fp.getX();
        boolean walking = true;
        for (int t = 0; t < 2000 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            maxX = Math.max(maxX, fp.getX());
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        boolean ashore = !fp.isInWater() && fp.onGround()
                && fp.getX() >= cx + span - 0.5 && fp.getY() >= surface + 1 - 0.4;
        TestMod.LOG.info("[wd.deepWaterCross] step={} pos=({},{},{}) maxX={} ashore={}",
                s, fp.getX(), fp.getY(), fp.getZ(), maxX, ashore);
        if (!ashore)
            ctx.fail("deepWaterCross: floating Navigator failed to cross the deep channel"
                    + " and climb out: pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                    + ") maxX=" + maxX + " step=" + s);
    }

    /** Ported from {@code AgentGameTestWaterBank#deepWaterClimboutNoBlockArena} (shared-player lottery
     *  member — solo-GREEN this phase; deterministic with the createUnique isolated player). Block-LESS deep-water
     *  +2 bank climb-out: the bot holds only SAND (FallingBlock → no pillar), so the ONLY escape is the
     *  block-less bank-DIG fallback. Asserts the floating Navigator reaches dry land within a bounded budget. */
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

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("allowSwimEscapeBreak", true);
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);

        // Adjacent to the bank (cz+2), the way wd.waterLowBank spawns. Three blocks out — where an
        // earlier version of this scene put the bot — the takeover's entry gate
        // (WalkerTickClimb:455-457) never fires and the arena measures nothing.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.SAND, 64));   // FallingBlock → holdPlaceable() false
        fp.getInventory().selected = 0;

        bot.goTo(Targets.block(goal));
        // Block-less, the +2 bank is a dig for both the server-side and the client player now that
        // the planner no longer swims into the air cell above the surface; 200 ticks (10 s) still
        // fails loudly on a bob-stall regression. The bot arrives airborne off the last dug step, so the walk is followed for
        // a few settle ticks after ARRIVED before its landing is judged: body steps alone, the walk being over.
        int ashoreTick = -1;
        int settle = 0;
        boolean walking = true;
        for (int t = 0; t < 200 && (walking || settle++ < 20); t++) {
            if (walking) { bot.tick(); walking = bot.busy(); }
            else bot.step();
            if (ashoreTick < 0 && !fp.isInWater() && fp.onGround()
                    && fp.getX() >= cx + span - 0.5 && fp.getY() >= bankTop + 1 - 0.4) {
                ashoreTick = t;
                break;
            }
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        TestMod.LOG.info("[wd.deepWaterClimboutNoBlock] step={} pos=({},{},{}) ashoreTick={}",
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

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("allowSwimEscapeBreak", true);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        // Adjacent to the bank (cz+2), the way wd.waterLowBank spawns. Three blocks out — where an
        // earlier version of this scene put the bot — the takeover's entry gate
        // (WalkerTickClimb:455-457) never fires and the arena measures nothing.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.SAND, 64));   // FallingBlock → holdPlaceable() false
        fp.getInventory().selected = 0;
        fp.setDeltaMovement(0, 0, 0.45);                        // lateral shove → drift along the face

        bot.goTo(Targets.block(goal));
        int ashoreTick = -1;
        boolean walking = true;
        for (int t = 0; t < 400 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            if (ashoreTick < 0 && !fp.isInWater() && fp.onGround() && fp.getY() >= bankTop + 1 - 0.4
                    && fp.getX() >= cx + span - 0.5) {
                ashoreTick = t;
                break;
            }
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        TestMod.LOG.info("[wd.deepWaterClimboutDrift] step={} pos=({},{},{}) ashoreTick={} (clean baseline ~22)",
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

        GotoContracts.Target goal = Targets.column(cx + 11, cz, 0);   // east of the wall, SAME Z as the bot

        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        Harness.set("allowSwimEscapeBreak", false);
        Harness.set("allowSwimEscapePlace", false);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.setDeltaMovement(0.45, 0, 0);           // shove +X straight into the divider

        bot.goTo(goal);
        int reachedTick = -1;
        boolean walking = true;
        for (int t = 0; t < 500 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            if (Math.floor(fp.getX()) == cx + 11 && Math.floor(fp.getZ()) == cz) { reachedTick = t; break; }
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        double dGoal = Math.hypot(fp.getX() - (cx + 11 + 0.5), fp.getZ() - (cz + 0.5));
        TestMod.LOG.info("[wd.waterFarAimBankCorner] step={} pos=({},{},{}) reachedTick={} dGoal={}",
                s, fp.getX(), fp.getY(), fp.getZ(), reachedTick, String.format("%.1f", dGoal));
        // Round the wall via the gap → reach the east goal column.
        if (reachedTick < 0 && walking)
            ctx.fail("waterFarAimBankCorner: bot failed to round the divider to the goal"
                    + " (far-aim rammed the wall through-LOS?): dGoal=" + dGoal + " pos=(" + fp.getX() + ","
                    + fp.getY() + "," + fp.getZ() + ") step=" + s);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // The place-futility LEDGER (J31). Two scenes, and neither is about whether the bot climbs.
    //
    // WalkerTickClimb's climb-out place branch resets `pillarNoPlaceTicks` on the tick it CLICKS,
    // not on the tick the block LANDS — and `Body.place` is `void`, so the click has no verdict
    // to record. The window that makes this fatal is real: the crest gate admits the player at
    // `cell.y + 0.9`, while vanilla's Level#isUnobstructed refuses any placement whose cell still
    // intersects the player's AABB, i.e. until `cell.y + 1.0`. Inside that 0.1-block band every
    // click is refused and every refusal zeroes the counter, so `placeFutile` can never become
    // true and the dig fallback behind it can never take the bank.
    //
    // ⚠️ Both scenes PIN the player's Y rather than letting it bob. That is deliberate: the subject
    // is the LEDGER, and a bob that has to land inside a 0.1-block band on its own would make the
    // arena a coin toss. The pin is staging, not the thing under test — which is also why neither
    // scene asserts anything about height gained.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The +2 low bank the two ledger scenes both use — deliberately the same shape as
     * {@code wd.waterLowBank}, because that is the geometry the climb-out takeover is KNOWN to
     * engage on. An earlier version of these scenes built its own basin and parked the bot three
     * blocks off the bank; the takeover never engaged, both scenes reported {@code engages=0}, and
     * the vacuity guard below is what caught it. Returns the goal on the dry land behind the bank.
     */
    private static BlockPos buildLedgerBasin(ServerLevel level, int cx, int cz, int floorY, int surface) {
        final int bankTop = surface + 1;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 6; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dz = -3; dz <= 1; dz++)
            for (int y = floorY + 1; y <= bankTop; y++) {
                level.setBlockAndUpdate(new BlockPos(cx - 3, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + 3, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++)
            for (int y = floorY + 1; y <= bankTop; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 1; dz++) {
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
                for (int y = surface + 1; y <= surface + 5; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        for (int dx = -3; dx <= 3; dx++) {
            for (int y = floorY + 1; y <= surface; y++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + dx, bankTop, cz + 2), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = 3; dz <= 6; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, bankTop, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = bankTop + 1; y <= bankTop + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        return new BlockPos(cx, bankTop + 1, cz + 4);
    }

    /** Topmost water cell of the column the bot floats in, or null once the column is filled. */
    private static BlockPos topWater(SceneBot bot, int cx, int cz, int floorY, int surface) {
        for (int y = surface; y > floorY; y--) {
            BlockPos c = new BlockPos(cx, y, cz);
            if (NavWalk.cell(bot, c).water()) return c;
        }
        return null;
    }

    /** {@code c} is solid as the bot's planner view classifies it. */
    private static boolean solid(SceneBot bot, BlockPos c) {
        return NavWalk.cell(bot, c).solid();
    }

    /**
     * J31 POSITIVE: every place is refused, so the ledger must reach futility and hand the bank to
     * the dig. Pins the player inside the {@code [cell.y+0.9, cell.y+1.0)} band — the crest gate says
     * "go", vanilla says "no" — and asserts {@code pillarNoPlaceTicks} climbs past
     * {@code NavigatorConstants.PILLAR_FUTILE_TICKS} anyway.
     *
     * <p>Before the fix this could not pass: the click zeroed the counter every tick, so the maximum
     * it ever reached was 1.
     */
    private static void pillarLedgerCountsRefusedPlaces(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), surface = floorY + 8;
        BlockPos goal = buildLedgerBasin(level, cx, cz, floorY, surface);

        Harness.pin(ctx);
        Harness.set("allowPlace", true);
        Harness.set("allowBreak", true);
        Harness.set("allowSwimEscapeBreak", true);        // digFallbackHere, else placeFutile hands off to nothing
        Harness.set("allowSwimEscapePlace", true);        // on the ENTRY gate (WalkerTickClimb:457) — pinned explicitly, not inherited
        // FALSE, explicitly, and not because that is the default. This flag is NOT the gate on the
        // ledger path: the entry condition at WalkerTickClimb:455-457 does not mention it, so the
        // climbout-place branch is live either way. Turning it ON would ADD a second placement
        // route — the crest-place at :921, reachable only once floodedShaft() reports false — which
        // fills cells WITHOUT touching pillarNoPlaceTicks. That route would quietly falsify this
        // scene's premise that nothing ever gets placed, and it would also flip the phase-class
        // semantics J24b just settled. One moving part, not three.
        Harness.set("navigatorPillarSurfacePlace", false);
        // TRUE, explicitly, because the pinned baseline turns it off and the takeover never engages
        // without it. With it off, `deepDig` sends the bank to the dig at stall 21, nine ticks before
        // the pillar may engage at 30. The hand-integrated player bobbed low enough that the dig was
        // judged infeasible and the pillar got its turn anyway; the pumped server-side player rides
        // the bank to feet 208.99 over a 208 surface, as the client does, the dig becomes feasible,
        // takes the riser and walks out (`climb-ctx … stall=20 deepDig=true` → `block-less bank dig`,
        // ARRIVED in 55 ticks).
        // It is also the production default, so this pins the order the navigator ships with.
        Harness.set("navigatorFootholdBeforeBankDig", true);
        // The climb-ctx rows are what told that staging failure apart from a ledger one; keep them.
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);

        // Adjacent to the bank (cz+2), the way wd.waterLowBank spawns. Three blocks out — where an
        // earlier version of this scene put the bot — the takeover's entry gate
        // (WalkerTickClimb:455-457) never fires and the arena measures nothing.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));   // haveBlock, and NOT a FallingBlock
        fp.getInventory().selected = 0;

        BotDebugContracts.NavigatorReading nav0 = bot.navigator();
        final long engages0 = nav0.counter("waterPillarEngages");
        // Without this the scene cannot tell its own two failure modes apart. "The cell never turned
        // solid" is what a refused click leaves behind AND what no click at all leaves behind, and
        // this arena is built to assert the first. Under the 2026-08-24 unified threshold it was
        // silently the second for 400 ticks and still reported PASS.
        final long clicks0 = nav0.counter("waterPillarPlaceCalls");
        bot.goTo(Targets.block(goal));
        long maxNoPlace = 0;
        // Sampled per tick, never at the end: the tick placeFutile goes true the takeover releases
        // and zeroes the counter (WalkerTickClimb:500-502), so a post-hoc read always sees 0.
        boolean sawFutile = false;
        boolean pinning = false;
        BlockPos pinnedAt = null;
        double bandLow = Double.MAX_VALUE, bandHigh = -Double.MAX_VALUE;
        boolean walking = true;
        int t = 0;
        for (; t < 400 && walking; t++) {
            bot.tick();
            walking = bot.busy();
            BotDebugContracts.NavigatorReading nav = bot.navigator();
            if (nav.flag("pillarPlaceFutile")) sawFutile = true;
            // TWO PHASES, and the order is the whole staging. Let the bot swim and bob FREELY
            // until the takeover actually engages — its entry gate wants a bob-stall below a bank
            // it cannot mount, and a bot held still never accumulates one. Only then start
            // holding the band. Pinning from tick 0 is what made the first version of this scene
            // report engages=0.
            if (!pinning && nav.counter("waterPillarEngages") > engages0) pinning = true;
            if (!pinning) continue;
            // THE BAND. Hold the feet 0.95 above the top water cell's floor: past the 0.9 crest
            // gate, short of the 1.0 vanilla needs. Re-applied every tick because the climb-out
            // drives forward and jumps, and one un-pinned tick is one tick of real physics.
            // Dead centre in the column on BOTH horizontal axes, so the AABB overlaps the cell on
            // all three and vanilla's refusal is geometric rather than lucky.
            BlockPos top = topWater(bot, cx, cz + 1, floorY, surface);
            if (top != null) {
                pinnedAt = top;
                fp.setPos(cx + 0.5, top.getY() + 0.95, cz + 1.5);
                fp.setDeltaMovement(0, 0, 0);
                // Where the player sat RELATIVE to the cell it was meant to fill. The pin makes this
                // constant by construction, and recording it is how the arena proves the constant
                // is the one it meant: 0.95 is inside the old crest gate (0.9) and below vanilla's
                // acceptance line (1.0), which is the whole point of the staging.
                bandLow = Math.min(bandLow, fp.getY() - top.getY());
                bandHigh = Math.max(bandHigh, fp.getY() - top.getY());
            }
            maxNoPlace = Math.max(maxNoPlace, nav.counter("pillarNoPlaceTicks"));
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        BotDebugContracts.NavigatorReading end = bot.navigator();
        long engages = end.counter("waterPillarEngages") - engages0;
        long clicks = end.counter("waterPillarPlaceCalls") - clicks0;
        String probe = NavWalk.trace(end, "progressProbe");
        boolean pinnedSolid = pinnedAt != null && solid(bot, pinnedAt);

        ctx.record("gate.takeoverEngages", engages);
        ctx.record("gate.placeClicksFired", clicks);
        ctx.record("ledger.peakCount", maxNoPlace);
        ctx.record("ledger.everJudgedFutile", sawFutile);
        ctx.record("pillar.pinnedCell", pinnedAt == null ? "none (the water column was filled)" : pinnedAt.toShortString()
                + " bot y=" + String.format("%.3f", fp.getY()));
        ctx.record("pillar.pinnedCellSolid", pinnedSolid);
        ctx.record("band.feetAboveFillCell", String.format("[%.3f, %.3f]", bandLow, bandHigh)
                + " (old crest gate 0.9 / vanilla acceptance line 1.0)");
        ctx.record("walk.end", s + " (used " + t + "/400 ticks)");
        ctx.record("walk.probe", probe);

        // VACUITY, and this one is load-bearing: with the defect present the counter is 0 because
        // every click zeroes it, and with the takeover never engaged it is 0 because the branch
        // never ran. Those two look identical from the counter alone, so the engage count has to
        // be asked separately or a staging failure would read as a reproduction of the defect.
        if (engages == 0)
            ctx.fail("pillarLedger: the water climb-out takeover never engaged — this run never reached"
                    + " the code under test, so a counter of 0 indicates a faulty test setup, not a ledger"
                    + " defect. probe=" + probe);
        if (pinnedSolid)
            ctx.fail("pillarLedger: the pinned cell " + pinnedAt.toShortString() + " became solid — a"
                    + " height of 0.95 should have been refused by Level#isUnobstructed, so the test setup"
                    + " did not produce the band in which every placement is refused.");
        // THE BAND, self-proved. Without this the scene can go green for the opposite reason: a bot
        // that never rose at all also never places, so the counter also climbs and placeFutile also
        // fires — a PASS that says nothing about the refusal band. Asserting both edges pins the
        // staging to the one geometry the defect lived in.
        if (bandHigh < 0.9 || bandHigh >= 1.0 || bandLow < 0.9)
            ctx.fail("pillarLedger: the bot's height relative to the fill cell was [" + String.format("%.3f", bandLow)
                    + ", " + String.format("%.3f", bandHigh) + "], not within the [0.9, 1.0) band for the"
                    + " whole run — below 0.9 the old crest gate is never entered, and at 1.0 vanilla accepts"
                    + " the placement. In either case this run does not test whether a refused click counts"
                    + " as progress, and a pass would not show that the ledger records outcomes.");

        // THE CLICK ITSELF, and it has to be asked out loud. Everything above is satisfied by a run
        // in which the crest gate never opened: the cell stays water, the counter climbs, futility
        // fires. That is not this scene's subject — "a refused click is not progress" needs a click to have been
        // refused. It went unnoticed for one whole gate run, which is why it is a guard now.
        if (clicks == 0)
            ctx.fail("pillarLedger: no click fired during the whole run (the crest gate never opened) — the"
                    + " counter reaching " + maxNoPlace + " means \"nothing was clicked\", not \"clicks had no"
                    + " effect\". This run did not test the behaviour under test.");
        if (!sawFutile)
            ctx.fail("The ledger records actions, not outcomes: for " + t + " consecutive ticks every placement"
                    + " was refused by vanilla (the bot was pinned at "
                    + (pinnedAt == null ? "?" : String.valueOf(pinnedAt.getY() + 0.95))
                    + ", high enough for the 0.9 gate but below the 1.0 physics line), yet placeFutile was"
                    + " never true and pillarNoPlaceTicks peaked at only " + maxNoPlace + "."
                    + " WalkerTickClimb unconditionally zeroes the counter after a.place(), and BlockInteraction.place"
                    + " returns void with no success result to report, so placeFutile is always false and"
                    + " the dig fallback behind it can never take over.");
    }

    /**
     * J31 REVERSE: a place that really lands must NOT be counted as futile. Pins the player at
     * {@code cell.y + 0.95} holding MUD — inside the crest band and above mud's 0.875 collision top,
     * the one combination vanilla accepts while the player is still in the cell — and asserts the cell
     * actually filled BEFORE asserting the ledger stayed under the futility line.
     *
     * <p>It used to pin at {@code cell.y + 1.05} holding a full cube, on the belief that clearing the
     * cell by 1.0 is what vanilla wants. Above 1.0 the foot cell moves up and the fill cell moves
     * with it, so the gate asked for {@code cell.y + 2.0} and the click could never fire: the scene
     * was unsatisfiable by construction and its every red was about its own staging.
     *
     * <p>The order matters. "nothing was placed and nothing gave up" and "things were placed so
     * nothing gave up" print the same verdict, so the fill has to be proven first or this scene is
     * 0==0 — and it would then pass just as happily against a ledger that increments unconditionally,
     * which is the exact regression the positive scene's fix could introduce.
     */
    private static void pillarLedgerClearsOnARealPlace(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY(), surface = floorY + 8;
        BlockPos goal = buildLedgerBasin(level, cx, cz, floorY, surface);

        Harness.pin(ctx);
        Harness.set("allowPlace", true);
        Harness.set("allowBreak", true);
        Harness.set("allowSwimEscapeBreak", true);
        Harness.set("allowSwimEscapePlace", true);     // on the ENTRY gate (WalkerTickClimb:457)
        // FALSE for the same reason as the positive scene: this flag does not gate the ledger path,
        // and turning it on adds the :921 crest-place, which fills cells WITHOUT going through the
        // ledger — the one route that would let this scene see a fill it is not measuring.
        Harness.set("navigatorPillarSurfacePlace", false);
        // TRUE for the same reason as the positive scene: off, the bank dig beats the takeover to the
        // bank and the pumped server-side player walks out before a single place is clicked.
        Harness.set("navigatorFootholdBeforeBankDig", true);
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);

        // Adjacent to the bank (cz+2), the way wd.waterLowBank spawns. Three blocks out — where an
        // earlier version of this scene put the bot — the takeover's entry gate
        // (WalkerTickClimb:455-457) never fires and the arena measures nothing.
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surface - 1, cz + 1.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        fp.getInventory().clearContent();
        // MUD, and this scene is unstageable without it. What it needs is a click vanilla ACCEPTS
        // while the player is inside the very cell being filled — the positive scene next door is the
        // one that needs a refusal. isUnobstructed tests the collision shape of the state being
        // PLACED, and mud's is 14/16 = 0.875 tall (MudBlock.SHAPE; its getBlockSupportShape is the
        // full cube, a different shape), so a player pinned at fill.y + 0.95 clears it by 0.075 and
        // the placement lands. With a full cube in hand — this scene held cobblestone until
        // 2026-08-24 — NO pin height works: the crest gate wants ≥ 0.9 into the cell and vanilla
        // wants ≥ 1.0 above it, and at 1.0 the foot cell moves up and the fill cell moves with it,
        // so the requirement chases itself forever.
        fp.getInventory().add(new ItemStack(Items.MUD, 64));
        fp.getInventory().selected = 0;

        BotDebugContracts.NavigatorReading nav0 = bot.navigator();
        final long engages0 = nav0.counter("waterPillarEngages");
        final long clicks0 = nav0.counter("waterPillarPlaceCalls");
        bot.goTo(Targets.block(goal));
        int filled = 0;
        boolean pinning = false;
        // The reading that decides this scene: what the ledger held on the tick AFTER a cell
        // actually filled. -1 = no fill was ever observed, which the guards below turn into a
        // staging failure rather than a pass.
        long ledgerAfterFill = -1;
        // The counter ON the fill tick. Without it, "0 afterwards" is satisfied by a counter that
        // was never anything else and the assertion below degenerates to 0 == 0.
        long ledgerAtFill = -1;
        boolean fillPending = false;
        boolean engagedAtRead = false;
        int footLow = Integer.MAX_VALUE, footHigh = Integer.MIN_VALUE;
        java.util.Set<BlockPos> wasWater = new java.util.HashSet<>();
        boolean walking = true;
        int t = 0;
        // 60, not 400. Once the run is long enough for the ledger to climb past the futility line
        // on its own, "it never declared futility" stops being evidence about the FILL and becomes
        // evidence about the budget. This scene asks one question — does a landing zero the
        // counter — so it runs just past the landing and stops.
        for (; t < 60 && walking; t++) {
            BlockPos top = topWater(bot, cx, cz + 1, floorY, surface);
            boolean wasSolidBefore = top == null || solid(bot, top);
            if (top != null) wasWater.add(top);
            bot.tick();
            walking = bot.busy();
            BotDebugContracts.NavigatorReading nav = bot.navigator();
            if (!pinning && nav.counter("waterPillarEngages") > engages0) pinning = true;
            if (pinning) {
                // A CONSTANT absolute height, and that is the point. Pinning relative to a moving
                // top would let foot.getY() climb as cells fill, and the high-water half of the
                // reset rule (b) would fire — after which a green run would prove nothing about
                // the landing half (a), which is what this scene is for. Held still, foot.getY()
                // cannot refresh the high-water mark after the first tick, so (a) is the only
                // reset left and the assertion below is about it alone.
                // INSIDE the top water cell, inside the crest band. surface + 0.95 keeps colFoot a
                // WATER cell, so fillCell stays the locked column's top cell instead of degenerating
                // to the player's own foot cell one row higher. The old pin (surface + 1.05) lifted the
                // feet out of the water, which made the gate ask for surface + 2.0 — a height the pin
                // itself forbade — so the click could never fire and this scene could only ever fail.
                fp.setPos(cx + 0.5, surface + 0.95, cz + 1.5);
                fp.setDeltaMovement(0, 0, 0);
                footLow = Math.min(footLow, fp.blockPosition().getY());
                footHigh = Math.max(footHigh, fp.blockPosition().getY());
                // ONE TICK LATER, and it is not an off-by-one worth tidying away. climboutPlaceTick
                // reads the ledger BEFORE it clicks, so on the tick a cell turns solid the counter
                // still holds the value it was incremented to at the top of that same tick. `landed`
                // is a world reading and the world cannot answer until the next tick — that lag IS
                // the mechanism this scene exists to prove, so the read has to sit on the far side
                // of it. Also record whether the takeover was still engaged at that moment: every
                // bail path zeroes the same counter, so a bare 0 does not name its own cause.
                if (fillPending && ledgerAfterFill < 0) {
                    ledgerAfterFill = nav.counter("pillarNoPlaceTicks");
                    engagedAtRead = nav.flag("pillarEngaged");
                    fillPending = false;
                }
                if (top != null && !wasSolidBefore && solid(bot, top) && ledgerAtFill < 0) {
                    ledgerAtFill = nav.counter("pillarNoPlaceTicks");
                    fillPending = true;
                }
            }
        }
        String s = NavWalk.step(bot);
        bot.cancel();
        for (BlockPos c : wasWater) if (solid(bot, c)) filled++;
        BotDebugContracts.NavigatorReading end = bot.navigator();
        long engages = end.counter("waterPillarEngages") - engages0;
        String probe = NavWalk.trace(end, "progressProbe");

        ctx.record("gate.takeoverEngages", engages);
        ctx.record("gate.placeClicksFired", end.counter("waterPillarPlaceCalls") - clicks0);
        ctx.record("pillar.cellsActuallyFilled", filled + "/" + wasWater.size());
        ctx.record("ledger.onFillTick", ledgerAtFill < 0 ? "no fill observed" : String.valueOf(ledgerAtFill));
        ctx.record("ledger.afterFill", ledgerAfterFill < 0 ? "no fill observed" : String.valueOf(ledgerAfterFill));
        ctx.record("ledger.takeoverEngagedAtRead", engagedAtRead);
        ctx.record("feet.cellYRange", footLow > footHigh ? "never pinned" : "[" + footLow + ", " + footHigh + "]");
        ctx.record("walk.end", s + " (used " + t + "/60 ticks)");
        ctx.record("walk.probe", probe);

        if (engages == 0)
            ctx.fail("pillarLedgerReal: the takeover never engaged — the test setup is wrong, and none of the"
                    + " rows below describe the subject under test. probe=" + probe);
        // THE SELF-PROOF, and it must come first: without an observed fill, "the ledger stayed at 0"
        // is satisfied by a run in which nothing whatsoever happened.
        if (filled == 0 || ledgerAfterFill < 0)
            ctx.fail("pillarLedgerReal: no cell was observed changing from water to solid during the whole run"
                    + " (" + filled + " cells filled, reading after the fill=" + ledgerAfterFill + ") — this"
                    + " scene asserts that \"a placement that actually lands resets the counter\", and the"
                    + " precondition \"a placement actually landed\" did not hold. A counter of 0 then proves"
                    + " nothing (nothing placed, no engage, and a fully successful run all give 0). Check two"
                    + " things in the test setup: the bot is pinned at " + (surface + 0.95)
                    + " (it must be inside the [+0.9, +1.0) band of the fill cell with its feet still in"
                    + " water, otherwise fillCell degenerates to the cell above and the criterion is always"
                    + " false), and the held item must be a block with a collision box <= 0.875 tall, such as"
                    + " mud — a full-cube block is refused by vanilla in this band and never lands.");
        // The counter has to have BEEN something before the landing zeroed it. Otherwise the
        // assertion below is 0 == 0 and passes on a run where the ledger never counted anything.
        if (ledgerAtFill <= 0)
            ctx.fail("pillarLedgerReal: the ledger read " + ledgerAtFill + " on the fill tick — it never"
                    + " accumulated before the fill, so \"0 after the fill\" is not evidence (0 -> 0 is not"
                    + " a reset).");
        // Every bail path zeroes the same counter (WalkerTickClimb sets pillarNoPlaceTicks = 0 on
        // release), so a zero read after a bail answers this scene's question without the landing doing it.
        if (!engagedAtRead)
            ctx.fail("pillarLedgerReal: the takeover was no longer engaged when the reset was read — every"
                    + " bail path zeroes the same counter, so this 0 does not identify its own cause, and"
                    + " this run cannot prove that the fill reset the counter.");
        // (b) MUST NOT BE THE EXPLANATION. The player is held at one absolute height, so foot.getY()
        // is constant and the high-water half of the reset rule cannot refresh after the first
        // tick. If this range is wider than one cell the pin failed, and a green run below would be
        // ambiguous between "the landing cleared it" and "the rise cleared it" — the scene would no
        // longer be about the half it claims to test.
        if (footHigh > footLow)
            ctx.fail("pillarLedgerReal: the feet cell changed within [" + footLow + ", " + footHigh + "] —"
                    + " the pin did not hold, so the high-water half (b) may be the cause of the reset, and"
                    + " this run cannot prove (a).");

        if (ledgerAfterFill != 0)
            ctx.fail("A cell was actually filled, but the ledger was not reset: after the fill tick"
                    + " pillarNoPlaceTicks is " + ledgerAfterFill + ", while the bot stayed pinned in the same"
                    + " cell for the whole run (feet cell " + footLow + "), so the high-water half cannot have"
                    + " reset it — the reset can only come from \"that cell actually became solid\", and it"
                    + " did not. At this rate a climb that is succeeding would be judged futile after "
                    + "51 ticks and taken over by the dig fallback.");
    }
}

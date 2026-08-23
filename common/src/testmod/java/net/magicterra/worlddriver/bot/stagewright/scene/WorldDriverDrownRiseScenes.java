package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;

/**
 * Did the body actually RISE — asked of the real client, because nothing else can ask it.
 *
 * <h2>The hole these fill</h2>
 *
 * The drowning family had four members before this file and every one of them stopped short of the
 * thing that kills bodies:
 *
 * <ul>
 *   <li>{@code wd.drowningFloatShouldFloatMatrix} — PURE, four boolean rows over
 *       {@code DrowningFloatGate.shouldFloat}. The word {@code should} is literal.</li>
 *   <li>{@code wd.drownEscapeGateMatrix} — PURE, 18 gate rows + 8 lifecycle rows.</li>
 *   <li>{@code wd.drownEscapePreempt} — mints a fake player, feeds {@code sensorForTest}, and
 *       asserts the scheduler's <b>bookkeeping</b>.</li>
 *   <li>{@code wd.drownEscapeSurface} — does assert a rise, but of the <b>Walker's</b> escape
 *       ({@code walkerDrowningEscape}), on a {@code ServerPlayerAvatar}, with the air supply
 *       simulated by hand. It never constructs {@code DrownEscapeChain}.</li>
 * </ul>
 *
 * <p>{@code DrownEscapeChain.tick} opens with {@code if (mc == null) return;} — so the entire
 * execution layer of the reflex that outranks every user process is unreachable from a dedicated
 * server, which is where all four of those run. Twenty-six assertions on「该不该浮」, zero on
 * 「浮起来了没有」.
 *
 * <p><b>What that cost.</b> Integrated ladder, 2026-08-22, BED rung: the reflex preempted at
 * {@code air=100}, held the movement channel for 261 ticks, and the body drowned at
 * {@code -28,61,79} without moving one block. The whole in-window log is two scheduler handovers —
 * that arm printed nothing per tick and its lid-break sub-arm printed nothing ever.
 *
 * <h2>Why the pinned arm is the one that matters</h2>
 *
 * The kinematics of that death already exclude two of the three candidate causes. The body neither
 * rose nor sank for 261 ticks, while the corpse — the same body one tick later, with the reflex
 * released — sank at ~0.02/tick. A body with no lift sinks; a body with lift and a clear path
 * surfaces and its air recovers. Neither happened, so buoyancy was applying and <b>a collision face
 * was in the way</b> — and every scan in {@code DrownEscapeChain} ({@code cappedColumn}, the
 * {@code lid} cell, {@code nearestBreathable}) looks at exactly one column, the one
 * {@code blockPosition()} names.
 *
 * <p>A body at {@code x=-27.716} has its bounding box edge at {@code -28.016}: 0.016 of it is in the
 * NEXT column, which no scan here ever asks about. {@link #pinnedByNeighbourColumn} stages that
 * geometry deliberately and with a large margin, so「今天红」means the blindness is real and
 * 「今天绿」means the neighbour column is not the mechanism and the search moves on. An open-water
 * arm alone could not tell those apart: it would go green today and go on being green through the
 * exact death it was written for.
 */
public final class WorldDriverDrownRiseScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.drownEscapeClientRisesInOpenWater", 400,
                        WorldDriverDrownRiseScenes::risesInOpenWater),
                // REQUIRED as of the fix. It went in optional for exactly one run — the run that
                // had to be allowed to report a red nobody could act on yet — and that run returned
                // 「净升 0.000 格 / 200 tick，盖挡=false 撞顶=true」. The hypothesis is settled, the
                // blindness is fixed, and a sensor that stays optional after its subject is
                // understood is a sensor that will be green-by-accident the day it regresses.
                Scene.of("wd.drownEscapeClientPinnedByNeighbourColumn", 400,
                        WorldDriverDrownRiseScenes::pinnedByNeighbourColumn),
                Scene.of("wd.drownEscapeClientStaysDownDisarmed", 400,
                        WorldDriverDrownRiseScenes::staysDownDisarmed));
    }

    /** Ticks the body is given to reach air. Ten times the ~20 ticks a free rise over this column
     *  takes, so a red cannot be read as「预算太紧」— and far below the ~240 ticks that separate the
     *  staged {@code air=40} from a drowning death, so a red never costs the client its life. */
    private static final int BUDGET = 200;

    /** Ticks between staging the blocks and using them. The blocks are written on the SERVER and the
     *  body that must be submerged in them is the CLIENT's; the chunk packets take a round trip, and
     *  a body judged before they land is judged against water it cannot see. */
    private static final int SYNC_TICKS = 20;

    /** Air to stage. Below {@code drownEscapeAirThreshold} (100) so the latch engages on the first
     *  evaluation — waiting for 200 real ticks of drowning to reach it would spend the budget on the
     *  precondition instead of on the subject. */
    private static final int STAGED_AIR = 40;

    // ------------------------------------------------------------------ the three arms

    /** Open column, nothing overhead: the body must reach air. This is the arm that says the
     *  actuation path works at all — command channel, scheduler, client physics, end to end. */
    private static void risesInOpenWater(SceneContext ctx) {
        run(ctx, false, true);
    }

    /** The regression. Same open column by every scan this class performs, but a solid block in the
     *  neighbouring column the body's own bounding box straddles. Nothing in
     *  {@code DrownEscapeChain} looks there, so the body should be pinned 那一格 below air with the
     *  reflex reporting a clear path. */
    private static void pinnedByNeighbourColumn(SceneContext ctx) {
        run(ctx, true, true);
    }

    /**
     * The counter-arm: with the reflex disarmed the body must NOT reach air.
     *
     * <p>Both flags, not one. {@code autoDrownEscape} switches off the chain; {@code
     * autoFloatWhenDrowning} switches off {@code AutoSwim.drowningSentinel}, which holds jump on an
     * IDLE body — and a body parked in a test column with no process is exactly idle. Disarming only
     * the chain would leave the sentinel to surface the body, and this arm would then fail while
     * describing the wrong subject. Its whole job is to prove the other two arms' green is not free.
     */
    private static void staysDownDisarmed(SceneContext ctx) {
        run(ctx, false, false);
    }

    // ------------------------------------------------------------------ the rig

    private static void run(SceneContext ctx, boolean pinNeighbour, boolean armed) {
        MinecraftServer server = ctx.server();
        boolean integrated = server != null && !server.isDedicatedServer();
        ctx.record("topology", (integrated ? "integratedServer" : "dedicatedServer")
                + "，mc.bot.* 在本 JVM=" + BotHooks.isAvailable());
        if (!integrated || !BotHooks.isAvailable()) {
            // Not a soft spot in the coverage — a hard one, and named. DrownEscapeChain.tick's first
            // line is `if (mc == null) return;`, so on a dedicated server this subject does not
            // merely go untested, it cannot execute. The dedicated gates' green says nothing here.
            ctx.skip("DrownEscapeChain 的执行层要求同一 JVM 里有真的 LocalPlayer："
                    + "tick() 第一行就是 if (mc == null) return。专用服上这条覆盖率不是弱，是零。");
        }
        List<ServerPlayer> humans = SceneBody.humanPlayers(ctx);
        if (humans.isEmpty()) {
            ctx.skip("集成服上没有真玩家 —— 客户端还没进世界，或已经掉线");
        }
        ServerPlayer body = humans.get(0);
        ServerLevel level = ctx.level();

        // ---- the column ----------------------------------------------------------------
        BlockPos o = ctx.origin();
        final int ox = o.getX(), oz = o.getZ();
        final int y0 = o.getY() + 8;            // the water's floor
        // Shell first, then carve: an unwalled pool drains sideways and the body would be judged in
        // air it made itself. Same order as wd.drownEscapeSurface's basin.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int y = y0 - 1; y <= y0 + 7; y++)
                    level.setBlockAndUpdate(new BlockPos(ox + dx, y, oz + dz), Blocks.STONE.defaultBlockState());
        // Water y0..y0+3 (surface plane y0+4.0), air above it. A body standing on the floor has its
        // eye at y0+1.62 and needs to reach y0+2.38 to breathe — 2.38 blocks of rise, big enough
        // that「浮了一点点」and「浮上去了」cannot print the same.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = y0; y <= y0 + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(ox + dx, y, oz + dz), Blocks.WATER.defaultBlockState());
                for (int y = y0 + 4; y <= y0 + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(ox + dx, y, oz + dz), Blocks.AIR.defaultBlockState());
            }

        // Where the body stands. Centred for the open arms; for the pinned arm, deliberately hard
        // against a cell boundary so the bounding box (width 0.6) straddles TWO columns: at
        // x = ox-0.98 the box runs [ox-1.28, ox-0.68], i.e. into column ox-2, while
        // blockPosition() still answers ox-1. Every scan in DrownEscapeChain asks about ox-1.
        final double bodyX = pinNeighbour ? ox - 0.98 : ox + 0.5;
        final double bodyZ = oz + 0.5;
        final BlockPos scanned = new BlockPos(pinNeighbour ? ox - 1 : ox, y0 + 2, oz);
        final BlockPos straddled = new BlockPos(ox - 2, y0 + 2, oz);
        if (pinNeighbour) {
            // The lid the body actually hits, in the column nobody reads. Its height is footY+2:
            // a body on the floor has its top at y0+1.8, so it is pinned from the first tick rather
            // than after a climb — the accident's own pose, where the corpse sat at 61.159 with its
            // top face at 62.959 against a face at 63.0.
            level.setBlockAndUpdate(straddled, Blocks.STONE.defaultBlockState());
        }
        ctx.record("柱.水面", "y=" + (y0 + 4) + ".0（水填 " + y0 + ".." + (y0 + 3) + "）");
        ctx.record("柱.身体", String.format(Locale.ROOT, "x=%.2f z=%.2f，blockPosition 的柱=%s",
                bodyX, bodyZ, scanned.toShortString()));
        ctx.record("柱.挡块", pinNeighbour ? straddled.toShortString() + "=stone（扫描永远不看这一柱）" : "无");

        // ---- config, restored by the pin ----------------------------------------------
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.autoDrownEscape = armed;
        BotConfig.autoFloatWhenDrowning = armed;
        BotConfig.walkerDebug = true;            // the arm's per-tick row is gated on it
        BotConfig.allowBreak = true;             // so a lid the scan DOES see would be broken

        // ---- the body, restored on the way out ----------------------------------------
        final double homeX = body.getX(), homeY = body.getY(), homeZ = body.getZ();
        final int homeAir = body.getAirSupply();
        ctx.cleanup(() -> {
            body.teleportTo(homeX, homeY, homeZ);
            body.setAirSupply(homeAir);
        });
        body.teleportTo(bodyX, y0, bodyZ);
        // FULL air for the sync wait, and the staged value only once measuring starts.
        //
        // Staging the low value here instead cost a criterion on 2026-08-23: the latch engaged
        // immediately, the reflex spent the 20 sync ticks freeing the body, and 升.起点y — taken
        // after the wait — was 209.017 instead of the staged 208.2. 升.净升 then read 1.302 for a
        // body that had actually risen 2.12, i.e. the baseline had been moved by the very thing the
        // number was measuring. 300 is above every float threshold in the driver
        // (drownEscapeAirThreshold 100, drownFloatAirThreshold 240) with room for the ~20 ticks of
        // drain the wait costs, so nothing acts before the window opens.
        body.setAirSupply(body.getMaxAirSupply());

        // ---- measure -------------------------------------------------------------------
        final int[] waited = { 0 };
        ctx.await(() -> ++waited[0] >= SYNC_TICKS).within(SYNC_TICKS + 100).then(() -> {
            // The staging is asserted BEFORE the subject is. A body that never got submerged would
            // produce a perfectly readable「没浮起来」that describes the arena, not the reflex.
            boolean submerged = body.isEyeInFluid(FluidTags.WATER);
            ctx.record("布景.眼在水里", submerged);
            ctx.record("布景.脚格", level.getBlockState(body.blockPosition()).getBlock().toString());
            if (!submerged) {
                ctx.fail("布景没成立：等了 " + SYNC_TICKS + " tick，身体的眼睛还不在水里（"
                        + String.format(Locale.ROOT, "y=%.3f", body.getY()) + "，脚格="
                        + level.getBlockState(body.blockPosition()) + "）。"
                        + "这一份读数与 DrownEscapeChain 无关，先修布景。");
            }
            body.setAirSupply(STAGED_AIR);       // the window opens HERE — see the teleport's note
            final double startY = body.getY();
            ctx.record("布景.测量起点相对布景高度", String.format(Locale.ROOT,
                    "%.3f（布景 y0=%d，同步等待里漂了 %.3f）", startY, y0, startY - y0));
            final double[] maxY = { startY };
            final boolean[] surfaced = { false };
            final int[] t = { 0 };
            // Terminates on its own: `within` expiring is a FAIL in this harness, and the disarmed
            // arm's success condition is by design never reached. So the loop counts to BUDGET and
            // the verdict is taken in then(), not by the await.
            ctx.await(() -> {
                maxY[0] = Math.max(maxY[0], body.getY());
                if (!body.isEyeInFluid(FluidTags.WATER)) surfaced[0] = true;
                return surfaced[0] || ++t[0] >= BUDGET;
            }).within(BUDGET + 100).then(() -> {
                double gained = maxY[0] - startY;
                ctx.record("升.起点y", String.format(Locale.ROOT, "%.3f", startY));
                ctx.record("升.峰值y", String.format(Locale.ROOT, "%.3f", maxY[0]));
                ctx.record("升.净升", String.format(Locale.ROOT, "%.3f 格", gained));
                ctx.record("升.出水", surfaced[0] + "（用了 " + t[0] + "/" + BUDGET + " tick）");
                ctx.record("升.末态", String.format(Locale.ROOT,
                        "y=%.3f 眼在水里=%s 气=%d", body.getY(), body.isEyeInFluid(FluidTags.WATER),
                        body.getAirSupply()));
                if (armed && !surfaced[0]) {
                    ctx.fail("按住跳 " + BUDGET + " tick，头始终没出水：净升 "
                            + String.format(Locale.ROOT, "%.3f", gained) + " 格，水面在 y=" + (y0 + 4)
                            + ".0，脚只要到 " + String.format(Locale.ROOT, "%.2f", y0 + 2.38)
                            + " 就能换气。" + (pinNeighbour
                                    ? "本臂的挡块在 " + straddled.toShortString()
                                      + "，而 cappedColumn/lid/nearestBreathable 三处都只看 "
                                      + scanned.toShortString() + " 那一柱 —— 单柱扫描的盲区成立。"
                                    : "本臂头顶完全敞开，所以问题不在几何，在执行层："
                                      + "看日志里 [drownEscape] 竖直支 那几行的 跳读回/撞顶 两列。"));
                }
                if (!armed && surfaced[0]) {
                    ctx.fail("反确认臂失败：autoDrownEscape 和 autoFloatWhenDrowning 都关着，"
                            + "身体却还是出水了（净升 " + String.format(Locale.ROOT, "%.3f", gained)
                            + " 格）。说明另有一条没被关掉的通道在浮它 —— 在它被找出来之前，"
                            + "另外两臂的绿证明不了是这条反射把身体浮上去的。");
                }
            });
        });
    }
}

package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.authlib.GameProfile;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.sim.JoinedPlayerBodies;
import net.magicterra.worlddriver.bot.sim.ServerAvatarBodies;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>A ruler, not a test.</b> One scene, zero assertions — it only {@code ctx.record}s.
 *
 * <h2>Why a scene that can never go red is worth having</h2>
 *
 * {@code docs/fake-player-parity.md} classifies 39 ways the driven body differs from a real player
 * into four buckets, and every one of those rows was derived by <b>reading code</b>: this override
 * is empty, that field is only decremented on a tick path nothing calls, this handler has no verb
 * on our side. Reading is how the list was found; it is not how the list gets confirmed. A row that
 * says「这具身体一辈子只吸一颗经验球」is a prediction about a running game, and the repo's own
 * standing rule is 「先测量、先证明差异真实存在且真的影响测试结论，再谈改」.
 *
 * <p>So this scene measures. It writes down what the body actually reports, for each of the
 * quantities the boundary table makes a claim about, and it <b>asserts nothing at all</b>. That is
 * deliberate and it is the only shape that can work here:
 *
 * <ul>
 *   <li><b>An assertion would be the {@code 0==0} trap.</b> Every threshold worth asserting is a
 *       number this scene would have to get from the same reading that produced the prediction. A
 *       scene that stages the subject into the expected state and then asserts it is in that state
 *       is green forever and measures nothing. Recording the raw value instead makes the reading
 *       falsifiable by a human, which is the whole job.</li>
 *   <li><b>The values are expected to be "wrong".</b> Most rows here are known defects. A scene
 *       that went red on them would be red on every run until all 39 are fixed, i.e. useless as a
 *       gate and actively harmful as noise.</li>
 *   <li><b>Records survive where logs do not.</b> {@link SceneContext#record} values travel into the
 *       results JSONL for a PASSing scene, and the async logger drops bursts precisely when a long
 *       suite is finishing — see that method's own javadoc. A census that logged instead of
 *       recording would lose its readings on exactly the runs worth reading.</li>
 * </ul>
 *
 * <h2>The time window this scene exists inside</h2>
 *
 * The 2026-08-20 body-selection instruction retires NeoForge's {@code FakePlayer}, puts
 * {@code JoinedBody} on the dedicated test server, and drives the integrated/joining topologies
 * with a {@code LocalPlayer}. That reshuffles the boundary table around one question, asked of
 * every row: <b>is this difference specific to {@code FakePlayer} (it disappears when that body
 * does), or is it caused by this driver never going through the real packet handlers (changing the
 * body will not help)?</b> §6.5 of the doc answers that for all 39 rows — <i>by reading</i>.
 *
 * <p><b>Two columns is what makes that answer checkable, and one of the two columns is about to
 * stop existing.</b> So this scene measures the same quantities on BOTH bodies in the same run:
 * the factory body ({@code FakePlayer} on NeoForge, {@code AvatarFakePlayer} on Fabric) and a
 * {@code JoinedBody}, side by side, regardless of whether {@code -Dworlddriver.realPlayerBodies} is
 * set. After the retirement the first column can never be taken again. Every row where the two
 * columns AGREE is a difference the retirement will NOT fix; every row where they DIFFER is one it
 * will. That is the archive this scene exists to leave behind.
 *
 * <p><b>「regardless of whether the flag is set」 was untrue for exactly one run, and that is worth
 * keeping.</b> Column A used to mint via {@code ServerPlayerBody.createUnique}, which routes
 * through {@code ServerAvatarBodies.require()}, whose first line is
 * {@code if (real != null) return real;}. So on 2026-08-22 — the first dedicated-server run after
 * {@code -Dworlddriver.realPlayerBodies=true} was armed for the gates — <b>both columns minted a
 * {@code JoinedBody}</b> and every one of the 26 rows came back identical. Nothing failed; the
 * scene passed; the two matching columns read exactly like 「换身体没有区别」, which is the
 * opposite of the truth. It was caught only because {@code census.armProperty} records this run's
 * PREMISE unconditionally, so the identical columns could be attributed to the switch instead of to
 * the bodies. Column A now bypasses {@code require()} via
 * {@code ServerAvatarBodies.loaderFactoryOrNull()}, and {@code census.factoryColumnSource} names
 * the factory it actually got. The general rule, which outlives this scene: <b>a control that can
 * silently degenerate into its own treatment arm is worse than no control</b>, and the only cheap
 * defence is to record, unconditionally, what the run's premise actually was.
 *
 * <h2>Reading the output</h2>
 *
 * Keys are {@code body.<column>.<quantity>}, where {@code <column>} is {@code factory} or
 * {@code joined}. Four coordinates are recorded before any of them, and all four are premises
 * rather than results — read them first, because each one can invalidate every row below it:
 *
 * <ul>
 *   <li>{@code census.topology} — which of the three topologies this run is, <b>read off the
 *       running game</b> rather than echoed from a {@code -D} flag. A row that merely repeated the
 *       flag would be green on a run where the flag was set and the thing it promises never
 *       happened; that failure has a name in this repo.</li>
 *   <li>{@code census.factoryColumnSource} — the concrete factory class column A was minted from.
 *       This is the <b>negative control</b>: the reading that must stay DIFFERENT from column B
 *       while everything else works. If it ever names a joined-body factory, or reports
 *       {@code unavailable}, then the two columns are not a comparison and no row below them
 *       means what it appears to mean.</li>
 *   <li>{@code body.<column>.identity} — the concrete class, the profile name, whether it is in
 *       {@code level.players()}, and whether it is a NeoForge {@code FakePlayer}. The last is the
 *       one the whole retirement turns on.</li>
 *   <li>{@code body.<column>.tickChain} — which of the three tick paths actually drives it. Without
 *       this a reading cannot be attributed: the same number means different things depending on
 *       whether {@code ServerPlayer.tick()} ran.</li>
 * </ul>
 *
 * <p><b>A value that could not be taken is recorded as {@code unavailable/<reason>}, never as 0 or
 * null.</b> This is not politeness. In this repo {@code 0} and {@code null} have repeatedly meant
 * 「还没算过」rather than 「算出来是零」, and a census whose misses are indistinguishable from its
 * zeroes would manufacture exactly the wrong conclusions — it would report a body that never got
 * built as a body with no fall distance.
 *
 * <h2>Footprint (stated by hand, because the gate cannot see it)</h2>
 *
 * {@code check_scene_arena.py} interval-evaluates offsets written inline in the scene body; this
 * scene builds its pads inside helpers, so the gate scores its hull as {@code [+0,+0]} — an honest
 * 「看不出来」, not a clean bill of health. The real span, written out so a reader can check it:
 *
 * <ul>
 *   <li>column {@code factory} at {@code dx=0}, pad and clear-box half-width {@code PAD+1 = 4}
 *       → {@code dx ∈ [-4, +4]}; its mining probe reaches {@code dx=+2}</li>
 *   <li>column {@code joined} at {@code dx = COLUMN_GAP = +24}, same half-width
 *       → {@code dx ∈ [+20, +28]}</li>
 *   <li>{@code dz ∈ [-4, +4]} for both</li>
 * </ul>
 *
 * <p>So the hull is {@code dx ∈ [-4, +28]}, {@code dz ∈ [-4, +4]}, against the default
 * {@code chunkRadius=1} window of {@code [-16, +31]}. It fits with 3 blocks to spare on the high
 * side — <b>which is the number to check if {@code COLUMN_GAP} is ever widened</b>: at
 * {@code COLUMN_GAP > 27} this scene silently starts writing outside its forced window, and that
 * failure reads as flakiness rather than as an arena bug.
 *
 * <h2>What this scene deliberately does NOT do</h2>
 *
 * <ul>
 *   <li><b>It does not fix anything.</b> Building the ruler and moving the thing being measured are
 *       different rounds, by instruction. Nothing here writes to production code.</li>
 *   <li><b>It does not call {@code level.tick()}</b>, per the Avatar-family rule. Most quantities
 *       get their time by stepping the avatar directly, which is what every other server-avatar
 *       scene in this suite does. Three of them cannot: {@code tickCount},
 *       {@code invulnerableTime} and {@code fallDistance} live in {@code ServerPlayer.tick()},
 *       which {@code avatar.step()} never calls, so a synchronous probe reads zero whether the
 *       channel is severed or merely unhurried. Those three are taken again in <b>phase 2</b>
 *       ({@code ServerTickWatch}), which stops driving the body and waits out
 *       {@link #SERVER_TICK_SAMPLES} real server ticks through {@code ctx.await} — still never
 *       calling {@code level.tick()} itself. Read the {@code serverTickCount} /
 *       {@code serverInvulnerableTime} / {@code serverFallDistance} keys ALONGSIDE their
 *       synchronous namesakes; either one alone is half a reading.</li>
 *   <li><b>It does not need Python.</b> Every reading is taken in-process by the same Gradle-driven
 *       harness that runs the gates — needing an external script to measure the framework's own
 *       subject would itself be the design failure.</li>
 * </ul>
 */
public final class WorldDriverBodyCensusScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                // Optional, and permanently so: this scene has no verdict to give. Marking it
                // required would promise the gate something it structurally cannot deliver — there
                // is no failure mode it can detect, only readings it can lose.
                Scene.of("wd.bodyParityCensus", 400,
                        WorldDriverBodyCensusScenes::bodyParityCensus).withRequired(false));
    }

    /** How far above the origin the census floor sits — clear of whatever the world put there. */
    private static final int FLOOR_LIFT = 20;

    /** Half-width of the stone pad each column stands on. */
    private static final int PAD = 3;

    /** Horizontal separation between the two columns' pads, so neither can touch the other's
     *  dropped items or experience orbs — the pickup box is 1 block, this is far outside it. */
    private static final int COLUMN_GAP = 24;

    /**
     * How many REAL server ticks phase 2 lets pass.
     *
     * <p>Six, not one, and the reason is the whole point of phase 2. {@code tickCount},
     * {@code invulnerableTime} and {@code fallDistance} are the three readings the synchronous phase
     * takes and cannot interpret: it drives the body with {@code avatar.step()} inside a SINGLE
     * {@code tickServer()}, so a zero there has two readings — 「通道一是死的」 and 「还没到时间」 —
     * and 0 has meant 「还没算过」 rather than 「算出来是零」 too often in this repo for that to be
     * left ambiguous. Six ticks of real server time separates them: a body the server actually ticks
     * gains six, and a body whose {@code tick()} is overridden to nothing gains zero no matter how
     * long anyone waits.
     */
    private static final int SERVER_TICK_SAMPLES = 6;

    private static void bodyParityCensus(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int ox = ctx.origin().getX(), oz = ctx.origin().getZ();
        final int floorY = ctx.origin().getY() + FLOOR_LIFT;

        ctx.record("census.topology", topology(ctx));
        ctx.record("census.armProperty", JoinedPlayerBodies.ARM_PROPERTY + "="
                + (JoinedPlayerBodies.armed() ? "true" : "false")
                + "（两列都量，与这个开关无关）");

        // ---- column A: the LOADER's own body — the negative control, and it must stay different ----
        //
        // Not ServerPlayerBody.createUnique(): that routes through ServerAvatarBodies.require(),
        // whose first line is `if (real != null) return real;`, so with the flip armed it hands back
        // a JoinedBody and this column silently becomes a second copy of column B. That is not a
        // hypothetical — it is what the 2026-08-22 NeoForge run actually did, and the two identical
        // columns read exactly like 「换身体没有区别」. A control is the reading that is supposed to
        // stay DIFFERENT while everything else works; one that quietly degenerates into its own
        // treatment arm measures nothing and still prints a colour.
        ServerAvatarBodies.BodyFactory loaderFactory = ServerAvatarBodies.loaderFactoryOrNull();
        ctx.record("census.factoryColumnSource", loaderFactory == null
                ? "unavailable/loader 尚未 install（这一列没有对照可言）"
                : loaderFactory.getClass().getName() + "（绕过 require()，所以翻闸不会把这一列变成 JoinedBody）");
        ServerPlayerBody factoryAvatar = measureColumn(ctx, "factory", level, ox, floorY, oz, () -> {
            if (loaderFactory == null) {
                throw new IllegalStateException("loader body factory not installed");
            }
            GameProfile profile = new GameProfile(
                    java.util.UUID.nameUUIDFromBytes(
                            "OfflinePlayer:wd-census-loader".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "wd-census-loader");
            ServerPlayer body = loaderFactory.unique(level, profile);
            body.setPos(ox + 0.5, floorY + 1, oz + 0.5);
            body.setDeltaMovement(Vec3.ZERO);
            return new ServerPlayerBody(body);
        });

        // ---- column B: a JoinedBody, minted directly rather than through the seam ----
        // Directly, because ServerAvatarBodies.require() only returns a joined body when the
        // system property is armed, and this census must take BOTH columns on every run — the
        // whole point is comparing them, and a run that could only ever see one of the two would
        // be the single-column archive this scene exists to avoid.
        final int jx = ox + COLUMN_GAP;
        ServerPlayerBody joinedAvatar = measureColumn(ctx, "joined", level, jx, floorY, oz, () -> {
            JoinedPlayerBodies bodies = new JoinedPlayerBodies();
            GameProfile profile = new GameProfile(
                    java.util.UUID.nameUUIDFromBytes(
                            "OfflinePlayer:wd-census".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "wd-census");
            ServerPlayer body = bodies.unique(level, profile);
            body.setPos(jx + 0.5, floorY + 1, oz + 0.5);
            body.setDeltaMovement(Vec3.ZERO);
            return new ServerPlayerBody(body);
        });

        // ---- phase 2: the three quantities the synchronous phase structurally cannot read ----
        ServerTickWatch factoryWatch = ServerTickWatch.arm("factory", factoryAvatar, ox, floorY, oz);
        ServerTickWatch joinedWatch = ServerTickWatch.arm("joined", joinedAvatar, jx, floorY, oz);
        ctx.await(() -> {
            // Both, every tick, and NOT `&&` — short-circuiting would stop pumping the second body
            // the moment the first finished, and its series would silently be the shorter one.
            boolean a = factoryWatch.pump();
            boolean b = joinedWatch.pump();
            return a && b;
        }).within(SERVER_TICK_SAMPLES + 20).then(() -> {
            factoryWatch.finish(ctx);
            joinedWatch.finish(ctx);
        });
    }

    /**
     * Take the whole reading set for one body, or say why it could not be taken.
     *
     * <p>The factory is a supplier rather than a body because minting is itself one of the things
     * that can fail — {@code placeNewPlayer} runs a great deal of code that assumes a socket, and a
     * census that let that throw would lose the OTHER column too. A column that cannot be built
     * records {@code unavailable/<exception>} for its identity and stops, and the run still carries
     * the column that did build.
     *
     * @return the body that was measured, so phase 2 can go on asking it questions across real
     *         server ticks, or {@code null} if this column could not be minted at all.
     */
    private static ServerPlayerBody measureColumn(SceneContext ctx, String column, ServerLevel level,
                                      int cx, int floorY, int cz,
                                      java.util.function.Supplier<ServerPlayerBody> mint) {
        pad(level, cx, floorY, cz);
        ctx.cleanup(() -> SceneArena.clearBox(level, cx, floorY, cz, PAD + 1, 8));

        ServerPlayerBody avatar;
        try {
            avatar = mint.get();
        } catch (RuntimeException | LinkageError e) {
            // LinkageError as well as RuntimeException: on a loader where the JoinedBody path
            // touches a class that is not on this classpath, the failure arrives as an Error and a
            // catch of RuntimeException alone would take the whole scene down with it.
            ctx.record(key(column, "identity"), "unavailable/" + e.getClass().getSimpleName()
                    + ": " + String.valueOf(e.getMessage()));
            return null;
        }
        ServerPlayer fp = avatar.fakePlayer();
        ctx.cleanup(() -> {
            try {
                fp.discard();
            } catch (RuntimeException ignored) {
                // A body that cannot leave is a leak worth nothing here: the scene has already
                // recorded everything it came for, and throwing from a cleanup would convert a
                // finished census into a failure of the NEXT scene.
            }
        });

        ctx.record(key(column, "identity"), identity(fp));
        ctx.record(key(column, "tickChain"), tickChain(fp));

        record(ctx, column, "isInvulnerableTo", () ->
                String.valueOf(fp.isInvulnerableTo(fp.damageSources().generic())));
        record(ctx, column, "invulnerableTime", () -> invulnerableTimeSeries(fp, avatar));
        record(ctx, column, "advancementsWritable", () -> advancementsWritable(fp));
        record(ctx, column, "fallDistance", () -> fallDistanceSeries(level, fp, avatar, cx, floorY, cz));
        record(ctx, column, "experienceOrbs", () -> experienceOrbs(level, fp, avatar, cx, floorY, cz));
        record(ctx, column, "pose", () -> poseSeries(fp, avatar));
        record(ctx, column, "walkStat", () -> walkStat(fp, avatar, cx, floorY, cz));
        record(ctx, column, "jumpApex", () -> jumpApex(fp, avatar, floorY));
        record(ctx, column, "jumpExhaustion", () -> jumpExhaustion(fp, avatar, cx, floorY, cz));
        record(ctx, column, "swinging", () -> swinging(level, fp, avatar, cx, floorY, cz));
        record(ctx, column, "mineDrop", () -> mineDrop(level, fp, avatar, cx, floorY, cz));
        record(ctx, column, "tickCount", () -> String.valueOf(fp.tickCount)
                + "（同步相，全程在一个服务端 tick 内；真玩家每 tick +1，"
                + "见 ServerLevel.tickNonPassenger。这个数要和 serverTickCount 一起读）");
        return avatar;
    }

    /**
     * Record one quantity, or record why it could not be taken.
     *
     * <p>Every reading goes through here so that <b>no quantity can be silently absent</b>. A key
     * that is missing from the results row and a key whose value is 0 are the two failure modes this
     * census must not have: the first makes a reading invisible, the second makes it a lie. Wrapping
     * each probe means an exception inside one of them costs that one key, not the rest of the
     * column and not the other column.
     */
    private static void record(SceneContext ctx, String column, String quantity,
                               java.util.function.Supplier<String> probe) {
        String value;
        try {
            value = probe.get();
        } catch (RuntimeException | LinkageError e) {
            value = "unavailable/" + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        }
        ctx.record(key(column, quantity), value);
    }

    /**
     * One body, watched across {@link #SERVER_TICK_SAMPLES} REAL server ticks.
     *
     * <p><b>What makes this different from every other probe in this file, and why it had to be.</b>
     * The rest of the census drives the body itself — {@code avatar.step()} in a loop — which is the
     * right shape for anything the DRIVER is responsible for, and the wrong shape for anything the
     * SERVER is responsible for. {@code tickCount}, {@code invulnerableTime} and {@code fallDistance}
     * are all in the second category: they move in {@code ServerPlayer.tick()}, which the server
     * calls from {@code ServerLevel.tickNonPassenger}, and which {@code avatar.step()} never calls.
     * Measured synchronously they all read zero, and a zero measured inside one tick cannot tell
     * 「这条通道被覆盖成空了」 from 「一个 tick 里本来就攒不出数」.
     *
     * <p>So this phase does the one thing that separates them: it <b>stops driving</b> and lets real
     * ticks pass. Nothing here calls {@code avatar.step()} — deliberately. If the numbers move, the
     * server is ticking the body and the synchronous zero merely meant 「时间不够」; if they stay at
     * zero through six ticks the body is not on channel one at all, and the empty {@code tick()}
     * override is the reason. That is the reading the 「能不能拆掉那行覆盖」 decision needs, and it
     * is the one reading the old shape could never produce.
     *
     * <p>It still does not call {@code level.tick()} — the Avatar-family rule stands. It waits for
     * ticks the harness is already running, through {@code ctx.await}.
     */
    private static final class ServerTickWatch {
        private final String column;
        private final ServerPlayer fp;          // null when the column could not be minted
        private final String unavailable;

        private final int tickCount0;
        private final double startY;
        /** World time when the watch was armed, so the denominator is MEASURED, not assumed. */
        private final long gameTime0;
        private final StringBuilder invulnerable = new StringBuilder();
        private double fallPeak;
        private int pumps;

        private ServerTickWatch(String column, ServerPlayer fp, String unavailable,
                                int tickCount0, double startY, long gameTime0) {
            this.column = column;
            this.fp = fp;
            this.unavailable = unavailable;
            this.tickCount0 = tickCount0;
            this.startY = startY;
            this.gameTime0 = gameTime0;
        }

        /**
         * Park the body where the three quantities all have room to move, and latch their start.
         *
         * <p>Lifted into the air on purpose: {@code fallDistance} can only be read through an actual
         * fall, and a body already standing on the pad would give the honest-looking zero that means
         * nothing. The lift is 8, which puts the FEET on the top layer of the {@code PAD+1} clear box
         * and the head just above it — the horizontal hull in the header is unchanged, which is the
         * part {@code check_scene_arena.py} scores, and the couple of blocks of headroom sit 20+
         * above whatever the world generated.
         */
        static ServerTickWatch arm(String column, ServerPlayerBody avatar,
                                   int cx, int floorY, int cz) {
            if (avatar == null) {
                return new ServerTickWatch(column, null, "unavailable/该列没能铸出身体（见 identity）",
                        0, 0, 0L);
            }
            try {
                ServerPlayer fp = avatar.fakePlayer();
                fp.setPos(cx + 0.5, floorY + 8, cz + 0.5);
                fp.setDeltaMovement(Vec3.ZERO);
                fp.fallDistance = 0.0F;
                fp.invulnerableTime = 20;
                return new ServerTickWatch(column, fp, null, fp.tickCount, fp.getY(),
                        fp.level().getGameTime());
            } catch (RuntimeException | LinkageError e) {
                return new ServerTickWatch(column, null,
                        "unavailable/" + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        0, 0, 0L);
            }
        }

        /** Sample once per real server tick; true when this watch has seen enough. */
        boolean pump() {
            if (fp == null) return true;
            pumps++;
            invulnerable.append(pumps == 1 ? "" : ",").append(fp.invulnerableTime);
            fallPeak = Math.max(fallPeak, fp.fallDistance);
            return pumps >= SERVER_TICK_SAMPLES;
        }

        void finish(SceneContext ctx) {
            if (fp == null) {
                // Unconditionally three keys, even for a column that never existed. A key that is
                // simply absent is the failure mode this census is built to not have.
                ctx.record(key(column, "serverTickCount"), unavailable);
                ctx.record(key(column, "serverInvulnerableTime"), unavailable);
                ctx.record(key(column, "serverFallDistance"), unavailable);
                return;
            }
            int gained = fp.tickCount - tickCount0;
            // The denominator is the world clock, not the pump count. How many real ticks N pumps
            // span depends on when ctx.await first evaluates the condition, and asserting "should be
            // +pumps" without measuring would be an evidence row that names a number it never took.
            long elapsed = fp.level().getGameTime() - gameTime0;
            ctx.record(key(column, "serverTickCount"), String.format(java.util.Locale.ROOT,
                    "%d 次采样跨了 %d 个真实服务器 tick（世界时钟量的）：tickCount %d→%d（+%d；"
                            + "真玩家应 +%d）——这里的 0 是「通道一没跑」，不是「时间不够」",
                    pumps, elapsed, tickCount0, fp.tickCount, gained, elapsed));
            ctx.record(key(column, "serverInvulnerableTime"), String.format(java.util.Locale.ROOT,
                    "置 20 后按真实服务器 tick 逐个采样（跨 %d tick）：%s（真玩家应递减）",
                    elapsed, invulnerable));
            ctx.record(key(column, "serverFallDistance"), String.format(java.util.Locale.ROOT,
                    "从 y=%.1f 起不再驱动，%d 个真实 tick 后 y=%.1f（实际下落 %.2f 格），"
                            + "fallDistance 峰值=%.3f，末值=%.3f",
                    startY, elapsed, fp.getY(), startY - fp.getY(), fallPeak, fp.fallDistance));
        }
    }

    private static String key(String column, String quantity) {
        return "body." + column + "." + quantity;
    }

    // ---------------------------------------------------------------- coordinates

    /**
     * Which topology this run is, read off the running game rather than echoed from a flag.
     *
     * <p>Deliberately the same two questions {@code JourneyRig} asks, for the same reason: a launch
     * can set a property and then fail to do the thing the property promises, and a coordinate that
     * repeated the property would be confidently wrong on exactly those runs. {@code
     * isDedicatedServer()} is false only inside a client hosting its own world; {@code
     * BotHooks.isAvailable()} is the driver's CLIENT half, registered only by a loader's client
     * entrypoint — on a dedicated server that code is not merely unexercised, it is absent.
     */
    private static String topology(SceneContext ctx) {
        MinecraftServer server = ctx.server();
        boolean dedicated = server == null || server.isDedicatedServer();
        boolean clientHalf = BotHooks.isAvailable();
        List<ServerPlayer> humans = new ArrayList<>();
        for (ServerPlayer p : ctx.level().players()) {
            if (p instanceof JoinedPlayerBodies.JoinedBody) continue;
            humans.add(p);
        }
        String kind = !dedicated ? "integratedServer"
                : humans.isEmpty() ? "dedicatedServer" : "dedicatedServerWithClient";
        StringBuilder s = new StringBuilder(kind).append("（真玩家 ").append(humans.size());
        for (ServerPlayer p : humans) {
            s.append('：').append(p.getGameProfile().getName())
                    .append('@').append(p.level().dimension().location());
        }
        return s.append("；mc.bot.* 在本 JVM=").append(clientHalf).append("）").toString();
    }

    /**
     * The body's class, name, player-list membership, and whether NeoForge would call it a fake.
     *
     * <p>{@code isFakePlayer} is asked <b>reflectively against the class name</b> rather than with
     * {@code instanceof}: {@code net.neoforged.neoforge.common.util.FakePlayer} is not on the Fabric
     * classpath at all, so a direct reference would not compile in {@code :common}. Walking the
     * superclass chain answers the same question on both loaders and answers it honestly on the one
     * where the class does not exist. <b>This single field is what the whole retirement turns on</b>
     * — neoforge {@code PlayerAdvancements.award} and {@code PlayerList.getPlayerAdvancements} both
     * key on exactly this {@code instanceof}.
     */
    private static String identity(ServerPlayer fp) {
        boolean neoFake = false;
        for (Class<?> c = fp.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("net.neoforged.neoforge.common.util.FakePlayer")) {
                neoFake = true;
                break;
            }
        }
        return fp.getClass().getSimpleName()
                + " " + fp.getGameProfile().getName()
                + "（在玩家表=" + fp.level().players().contains(fp)
                + "，是 neoforge FakePlayer=" + neoFake
                + "，@" + fp.level().dimension().location() + "）";
    }

    /**
     * Which tick path is actually driving this body — the coordinate a reading cannot be attributed
     * without.
     *
     * <p>The three paths a real player rides are independent, and this body may be on any subset:
     * {@code ServerLevel} → {@code ServerPlayer.tick()} (channel 1), the connection's
     * {@code doTick()} (channel 2), and the packet handlers (channel 3). Membership in
     * {@code level.players()} tells us the level would tick it; whether {@code tick()} is an empty
     * override tells us whether that does anything. Both are asked, because they differ: a
     * {@code JoinedBody} IS in the entity tick list and still overrides {@code tick()} to nothing.
     */
    private static String tickChain(ServerPlayer fp) {
        boolean inLevel = fp.level().players().contains(fp);
        boolean emptyTick;
        try {
            emptyTick = !fp.getClass().getMethod("tick").getDeclaringClass().equals(ServerPlayer.class);
        } catch (NoSuchMethodException e) {
            emptyTick = false;
        }
        return "avatar.step()/ServerAvatarManager"
                + "；在 ServerLevel 实体 tick 表=" + inLevel
                + "；tick() 被子类覆盖=" + emptyTick
                + "（覆盖成空则通道一被掐断）"
                + "；connection 在 ServerConnectionListener=false（两具身体都不是真 socket，通道二不跑）";
    }

    // ---------------------------------------------------------------- probes

    /**
     * Whether this body's advancement store will accept a write — <b>the retirement's single most
     * load-bearing reading</b>.
     *
     * <p>Asked by actually awarding a criterion and reading the return value, not by inspecting the
     * body's type. NeoForge's {@code PlayerAdvancements.award} returns {@code false} outright for a
     * {@code FakePlayer}; the prediction is that a {@code JoinedBody} gets {@code true} from the
     * same call because it is a plain {@code ServerPlayer}. Asking the store is what makes that a
     * measurement rather than a restatement of the prediction.
     *
     * <p>Revoked immediately afterwards: an advancement earned by a census would otherwise leak into
     * whatever the next scene asserts about progression, and this body's whole point is that it
     * shares a world with them.
     */
    private static String advancementsWritable(ServerPlayer fp) {
        MinecraftServer server = fp.getServer();
        if (server == null) return "unavailable/no server on this body";
        AdvancementHolder holder =
                server.getAdvancements().get(ResourceLocation.parse("minecraft:story/root"));
        if (holder == null) return "unavailable/story/root not registered in this runtime";
        String criterion = holder.value().criteria().keySet().stream().findFirst().orElse(null);
        if (criterion == null) return "unavailable/story/root has no criteria";
        boolean granted = fp.getAdvancements().award(holder, criterion);
        boolean done = fp.getAdvancements().getOrStartProgress(holder).isDone();
        fp.getAdvancements().revoke(holder, criterion);
        return "award() 返回 " + granted + "，随后 isDone=" + done
                + "（已 revoke；false 意味着这具身体拿不到任何进度）";
    }

    /**
     * The i-frame counter over five ticks after taking a hit.
     *
     * <p>Five, not one. A single reading cannot tell 「没有被赋值」 from 「赋值了但不递减」, and it is
     * precisely the递减 that is predicted missing: {@code LivingEntity.baseTick} excludes
     * {@code ServerPlayer} from the decrement explicitly, leaving {@code ServerPlayer.tick()} as its
     * only home — and that is the override this body empties. A flat series is the defect; a
     * counting-down series is its absence.
     */
    private static String invulnerableTimeSeries(ServerPlayer fp, ServerPlayerBody avatar) {
        fp.invulnerableTime = 20;
        StringBuilder s = new StringBuilder("置 20 后逐 tick：");
        for (int i = 0; i < 5; i++) {
            avatar.step();
            s.append(i == 0 ? "" : ",").append(fp.invulnerableTime);
        }
        return s.append("（真玩家应递减到 15）").toString();
    }

    /**
     * {@code fallDistance} sampled through an actual fall, plus the drop actually achieved.
     *
     * <p>Both halves matter and neither alone would do. Recording only {@code fallDistance} could
     * not distinguish 「没有累加」 from 「根本没掉下去」 — a body standing on solid ground has an
     * honest 0. So the vertical distance travelled is recorded next to it: a large drop with a zero
     * counter is the defect, a zero drop with a zero counter is a broken rig, and the two must not
     * print the same.
     */
    private static String fallDistanceSeries(ServerLevel level, ServerPlayer fp,
                                             ServerPlayerBody avatar, int cx, int floorY, int cz) {
        double startY = floorY + 12;
        fp.setPos(cx + 0.5, startY, cz + 0.5);
        fp.setDeltaMovement(Vec3.ZERO);
        float peak = 0f;
        for (int i = 0; i < 40 && fp.getY() > floorY + 1.01; i++) {
            avatar.step();
            peak = Math.max(peak, fp.fallDistance);
        }
        double dropped = startY - fp.getY();
        String s = String.format(Locale.ROOT,
                "从 y=%.1f 落到 y=%.1f（实际下落 %.2f 格），期间 fallDistance 峰值=%.3f，落地后=%.3f",
                startY, fp.getY(), dropped, peak, fp.fallDistance);
        // Back onto the pad, so the next probe does not start mid-air.
        fp.setPos(cx + 0.5, floorY + 1, cz + 0.5);
        fp.setDeltaMovement(Vec3.ZERO);
        return s;
    }

    /**
     * How many of three experience orbs this body actually absorbs.
     *
     * <p>Three, not one — and that count is the entire design of this probe. {@code
     * ExperienceOrb.playerTouch} only pays out when {@code takeXpDelay == 0} and then sets it back
     * to 2, while the only decrement lives on a tick path this body does not ride. One orb would be
     * collected by a healthy body and by a broken one alike, because the first orb is exactly the
     * one the defect lets through; the difference only appears from the second orb onwards.
     */
    private static String experienceOrbs(ServerLevel level, ServerPlayer fp,
                                         ServerPlayerBody avatar, int cx, int floorY, int cz) {
        int before = fp.totalExperience;
        List<ExperienceOrb> spawned = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ExperienceOrb orb = new ExperienceOrb(level, cx + 0.5, floorY + 1.1, cz + 0.5, 3);
            level.addFreshEntity(orb);
            spawned.add(orb);
        }
        for (int i = 0; i < 20; i++) avatar.step();
        int alive = 0;
        for (ExperienceOrb orb : spawned) if (!orb.isRemoved()) { orb.discard(); alive++; }
        return "扔 3 颗（每颗 3 点）：totalExperience " + before + "→" + fp.totalExperience
                + "，takeXpDelay=" + fp.takeXpDelay + "，20 tick 后仍未被吸收=" + alive
                + " 颗（真玩家应吸完 3 颗）";
    }

    /**
     * The pose and hitbox height with sneak off, then held on.
     *
     * <p>The height is recorded rather than only the pose enum, because the pose is a label and the
     * height is what collides. A body whose pose flips to {@code CROUCHING} while its box stays 1.8
     * tall still cannot fit under a slab, and those two readings are what tell that story apart.
     */
    private static String poseSeries(ServerPlayer fp, ServerPlayerBody avatar) {
        avatar.commandSneak(false);
        avatar.step();
        String standing = fp.getPose() + "/" + String.format(Locale.ROOT, "%.2f", fp.getBbHeight());
        avatar.commandSneak(true);
        for (int i = 0; i < 5; i++) avatar.step();
        String sneaking = fp.getPose() + "/" + String.format(Locale.ROOT, "%.2f", fp.getBbHeight());
        avatar.commandSneak(false);
        avatar.step();
        return "站立 pose/高度=" + standing + "，持续潜行 5 tick 后=" + sneaking
                + "（真玩家应 CROUCHING/1.50）";
    }

    /** Distance walked, and the walk statistic that should have counted it. Both, for the same
     *  reason the fall probe records both: a zero statistic beside a zero displacement is a broken
     *  rig, not a missing statistic. */
    private static String walkStat(ServerPlayer fp, ServerPlayerBody avatar,
                                   int cx, int floorY, int cz) {
        int before = fp.getStats().getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM));
        Vec3 from = fp.position();
        avatar.commandMove(0f, 1f);
        // Bounded by DISTANCE, not by a tick count. A tick count needs a per-tick speed to prove it
        // is safe, and this probe cannot know that speed — that is one of the things it is here to
        // measure, and the two columns may not share it. The first version of this probe asserted
        // "~0.11 blocks/tick" from nothing; vanilla walking is roughly twice that, which would have
        // walked the body straight off the pad it measures from. A body that falls reports a
        // displacement that is mostly vertical, AND leaves the next probe (jumpApex) reading its
        // start height in mid-air — two readings corrupted by a rig that walked off its own floor.
        //
        // WALK_TARGET = 2.0 against a pad of solid cells cx-3..cx+3: from the centre at cx+0.5 the
        // last supported foot position is about +3.2, so this stops with a full block of margin.
        // WALK_TICK_CAP only stops a body that is not moving at all — reaching it is a READING
        // (recorded below), not a failure, and it is exactly what a body off the tick channel looks
        // like. Both columns walk to the same distance, so their walk_one_cm deltas stay comparable
        // in a way that equal tick counts at unequal speeds would not be.
        int ticks = 0;
        while (ticks < WALK_TICK_CAP && horiz(fp, cx, cz) < WALK_TARGET) {
            avatar.step();
            ticks++;
        }
        boolean hitCap = ticks >= WALK_TICK_CAP;
        avatar.commandMove(0f, 0f);
        avatar.step();
        int after = fp.getStats().getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM));
        double moved = from.distanceTo(fp.position());
        boolean stillOnPad = Math.abs(fp.getX() - (cx + 0.5)) <= PAD
                && Math.abs(fp.getZ() - (cz + 0.5)) <= PAD;
        // Back to the centre, so jumpApex starts from a known cell on solid ground.
        fp.setPos(cx + 0.5, floorY + 1, cz + 0.5);
        fp.setDeltaMovement(Vec3.ZERO);
        return String.format(Locale.ROOT,
                "走到水平 %.1f 格用了 %d tick（撞上限=%b，上限 %d）：实际位移 %.2f 格"
                        + "（≈%.3f 格/tick，仍在台面上=%b），walk_one_cm %d→%d",
                WALK_TARGET, ticks, hitCap, WALK_TICK_CAP, moved,
                ticks == 0 ? 0.0 : moved / ticks, stillOnPad, before, after);
    }

    /** Horizontal distance from the pad centre, ignoring height — the walk probe is bounded on
     *  this and not on 3D distance, so a body that is falling cannot spend its budget going down. */
    private static double horiz(ServerPlayer fp, int cx, int cz) {
        double dx = fp.getX() - (cx + 0.5), dz = fp.getZ() - (cz + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** How far the walk probe drives before stopping. Chosen against the pad, not against a
     *  speed — see {@link #walkStat}. */
    private static final double WALK_TARGET = 2.0;

    /** Ceiling on the walk probe, so a body that never moves still ends the probe. Reaching it is
     *  a recorded reading, not an error. */
    private static final int WALK_TICK_CAP = 60;

    /**
     * The OTHER half of {@code Player.jumpFromGround} — its own key, deliberately.
     *
     * <p>{@code jumpFromGround} has two side effects, {@code awardStat(Stats.JUMP)} and
     * {@code causeFoodExhaustion(sprinting ? 0.2F : 0.05F)} (`Player.java:1471-1479`). They are
     * recorded separately because merging them would mean one {@code ctx.record} key describing two
     * mechanisms, and a half-working fix would then read as a clean pass on whichever half happened
     * to be quoted. It also cannot share {@link #jumpApex}'s key: two writes to one key means the
     * second silently swallows the first.
     *
     * <p>Reads exhaustion straight off {@code FoodData.getExhaustionLevel()} (public, {@code :107})
     * rather than waiting for food or saturation to move — a single jump adds 0.05, and
     * {@code FoodData.tick} only converts exhaustion into saturation once it passes 4.0, so a
     * food-level reading would show nothing here even when the mechanism works perfectly.
     */
    private static String jumpExhaustion(ServerPlayer fp, ServerPlayerBody avatar,
                                         int cx, int floorY, int cz) {
        // Start from a known cell on solid ground: jumpApex ran just before this and left the body
        // wherever its fall ended.
        fp.setPos(cx + 0.5, floorY + 1, cz + 0.5);
        fp.setDeltaMovement(Vec3.ZERO);
        avatar.step();
        boolean sprinting = fp.isSprinting();
        float before = fp.getFoodData().getExhaustionLevel();
        double startY = fp.getY();
        avatar.commandJump(true);
        avatar.step();
        avatar.commandJump(false);
        double apex = startY;
        for (int i = 0; i < 30 && fp.getY() >= startY - 0.01; i++) {
            avatar.step();
            apex = Math.max(apex, fp.getY());
        }
        float after = fp.getFoodData().getExhaustionLevel();
        // The rise is reported alongside the exhaustion for the same reason the fall probe reports
        // its drop: a zero delta beside a zero rise is a jump that never happened, which is a broken
        // rig; a zero delta beside a real rise is the defect this key exists to see.
        return String.format(Locale.ROOT,
                "起跳升高 %.3f 格（冲刺=%b），foodExhaustion %.3f→%.3f（差 %.3f；"
                        + "真玩家 jumpFromGround 应加 %.2f）",
                apex - startY, sprinting, before, after, after - before, sprinting ? 0.2f : 0.05f);
    }

    /**
     * How high one commanded jump actually goes, from a body that is standing still.
     *
     * <p>Only the plain-block arm is taken here. The honey-block and Jump-Boost arms that would
     * expose the hardcoded {@code 0.42} belong to a scene that asserts, and this one does not: three
     * arms recorded without a comparison would be three numbers a reader still has to interpret,
     * whereas the single number here has an unambiguous vanilla counterpart (0.42 initial upward
     * velocity, apex ≈ 1.25 blocks). What this row is FOR is the {@code Stats.JUMP} beside it — a
     * jump that visibly happens while the statistic stays flat is the reading that matters.
     */
    private static String jumpApex(ServerPlayer fp, ServerPlayerBody avatar, int floorY) {
        int jumpBefore = fp.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP));
        double startY = fp.getY();
        avatar.commandJump(true);
        avatar.step();
        avatar.commandJump(false);
        double apex = fp.getY();
        for (int i = 0; i < 30 && fp.getY() >= startY - 0.01; i++) {
            avatar.step();
            apex = Math.max(apex, fp.getY());
        }
        return String.format(Locale.ROOT, "起跳 y=%.3f → 顶点 y=%.3f（升高 %.3f 格），Stats.JUMP %d→%d",
                startY, apex, apex - startY, jumpBefore,
                fp.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)));
    }

    /**
     * Whether attacking through the avatar seam swings the arm.
     *
     * <p>Driven through {@link ServerPlayerBody#attackEntityUnchecked} on purpose, bypassing
     * {@code CombatProcess}: that process swings at its own call site, so measuring through it would
     * report the call site's behaviour and say nothing about the seam. The prediction under test is
     * that {@code bot/sim/} contains no {@code swing(} at all, which means every OTHER caller of the
     * seam gets no swing.
     */
    private static String swinging(ServerLevel level, ServerPlayer fp,
                                   ServerPlayerBody avatar, int cx, int floorY, int cz) {
        var target = EntityType.ARMOR_STAND.create(level);
        if (target == null) return "unavailable/could not create an armour stand in this runtime";
        target.setPos(cx + 1.5, floorY + 1, cz + 0.5);
        level.addFreshEntity(target);
        try {
            fp.swinging = false;
            avatar.attackEntityUnchecked(target);
            return "经 avatar 缝攻击后 swinging=" + fp.swinging
                    + "，attackAnim=" + String.format(Locale.ROOT, "%.2f", fp.attackAnim)
                    + "（真玩家的 handleInteract 会 swing(hand,true)）";
        } finally {
            target.discard();
        }
    }

    /**
     * What one bare-handed stone break actually yields, and what it costs the tool.
     *
     * <p>Bare-handed on purpose: vanilla gives a bare hand NO stone drop at all, so this single
     * reading separates the two mining routes cleanly. {@code Level#destroyBlock} — the route the
     * driver takes — passes {@code ItemStack.EMPTY} to {@code dropResources}, meaning no tool
     * requirement is consulted and cobblestone falls out anyway; {@code
     * ServerPlayerGameMode.destroyBlock} — the route a real player takes — gates the drop on
     * {@code canHarvestBlock}. A drop here is the defect; no drop is its absence.
     *
     * <p>The pickaxe's durability is read in the same breath because it fails the same way: the
     * driver's route never calls {@code mineBlock}, so the tool cannot wear.
     */
    private static String mineDrop(ServerLevel level, ServerPlayer fp,
                                   ServerPlayerBody avatar, int cx, int floorY, int cz) {
        BlockPos bare = new BlockPos(cx + 1, floorY + 1, cz);
        BlockPos withTool = new BlockPos(cx + 2, floorY + 1, cz);
        level.setBlockAndUpdate(bare, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(withTool, Blocks.STONE.defaultBlockState());
        AABB box = new AABB(cx - 2, floorY, cz - 2, cx + 4, floorY + 4, cz + 2);

        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) e.discard();
        fp.getInventory().clearContent();
        fp.setPos(cx + 0.5, floorY + 1, cz + 0.5);
        avatar.aimAtBlock(bare);
        avatar.breakHold(true);
        avatar.breakHold(false);
        int bareDrops = level.getEntitiesOfClass(ItemEntity.class, box).size();
        boolean bareGone = level.getBlockState(bare).isAir();

        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) e.discard();
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        fp.getInventory().items.set(0, pick);
        fp.getInventory().selected = 0;
        avatar.aimAtBlock(withTool);
        avatar.breakHold(true);
        avatar.breakHold(false);
        int toolDrops = level.getEntitiesOfClass(ItemEntity.class, box).size();
        int damage = fp.getInventory().getItem(0).getDamageValue();
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) e.discard();
        level.setBlockAndUpdate(bare, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(withTool, Blocks.AIR.defaultBlockState());

        return "赤手挖石头：方块消失=" + bareGone + "，掉落物 " + bareDrops
                + " 个（真玩家应 0）；铁镐挖石头：掉落物 " + toolDrops
                + " 个，镐耐久损耗=" + damage + "（真玩家应 1）";
    }

    // ---------------------------------------------------------------- rig

    /** A stone pad with clear air over it. Built before the body arrives so nothing generated by
     *  the world can be mistaken for the rig. */
    private static void pad(ServerLevel level, int cx, int floorY, int cz) {
        SceneArena.clearBox(level, cx, floorY + 1, cz, PAD + 1, 8);
        for (int dx = -PAD; dx <= PAD; dx++)
            for (int dz = -PAD; dz <= PAD; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz),
                        Blocks.STONE.defaultBlockState());
    }
}

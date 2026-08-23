package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.sim.AvatarFakePlayer;
import net.magicterra.worlddriver.bot.sim.JoinedPlayerBodies;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * How far apart the two halves of an "adopted" body drift, measured rather than argued about.
 *
 * <h2>The question this exists to answer</h2>
 *
 * On the integrated topology the playthrough ladder ADOPTS the client's real player, and it drives
 * that player through <b>two different paths that do not agree about which object is authoritative</b>:
 *
 * <ul>
 *   <li><b>The legs</b> — {@code settle}/{@code drive}/{@code legStart}, 113 call sites — go
 *       {@code BotApi.runProcess} → the client's {@code UserTaskChain} → {@code BotProcess.tick(Minecraft,…)}
 *       → {@code ClientPlayerAvatar}, whose field is literally {@code mc.player}. That half really is
 *       {@code LocalPlayer}, driven by client input and client physics.</li>
 *   <li><b>The single-shot actuations</b> — {@code rig.body().avatar().holdItem/aimAtBlock/useItemInHand/…},
 *       36 call sites plus all of {@code breakItWhereItStands} — go through the
 *       {@link ServerPlayerAvatar} built in {@code JourneyRig.spawnBody()}, which writes the
 *       <b>ServerPlayer</b> directly: {@code inv.selected = i}, {@code fp.setYRot(...)}.</li>
 * </ul>
 *
 * <p>Vanilla's client is authoritative for both of those quantities. The selected hotbar slot is
 * owned by the client and travels up via {@code ServerboundSetCarriedItemPacket}; rotation travels up
 * every tick in {@code ServerboundMovePlayerPacket.Rot}. So a server-side write to either is a write
 * the client never agreed to, and the next packet can silently undo it.
 *
 * <h2>Why measure instead of just fixing the 36 sites</h2>
 *
 * Because rungs 1–13 currently PASS, and they are <b>full</b> of exactly these calls — filling a
 * bucket, lighting a portal, crafting, placing the frame. So one of two things is true, and they
 * demand opposite priorities:
 *
 * <ul>
 *   <li>the divergence does not actually happen (the client never contests these writes in this
 *       scenario) — then rerouting those 36 sites is a near-zero-behaviour refactor, safe to land
 *       whenever; or</li>
 *   <li>it does happen and the ladder has been getting away with it — then it is a live defect and
 *       outranks everything else.</li>
 * </ul>
 *
 * <p>Rerouting first and re-running the ladder cannot distinguish them: green before, green after,
 * and no reading either way. That is this repo's own discipline —「一条断言在缺陷存在时会不会红？」
 * — applied to a refactor instead of to a test.
 *
 * <h2>What it does NOT do</h2>
 *
 * <b>It changes no actuator and asserts no fix.</b> It performs the two writes the way the ladder
 * performs them today, then reads BOTH sides and records the pair. The check it makes is only that
 * the reading was obtainable at all; the numbers themselves are recorded unconditionally, on PASS as
 * well, because a divergence that first appears six months from now is invisible unless the healthy
 * value is already in the record.
 *
 * <p><b>Reads only, never marshalled writes.</b> The client side is read through
 * {@code mc.client.player}, which reports {@code selectedSlot} and {@code look.yaw/pitch} off the
 * real {@code LocalPlayer}. That route hops to the client thread and waits — acceptable here because
 * this scene is not inside a leg and holds no tick-critical deadline. It would NOT be acceptable
 * from inside {@code JourneyRig}'s await predicate, which is the deadlock this design is otherwise
 * careful to avoid, and the reason the fix in A0 must use a fire-and-forget form instead.
 *
 * <h2>Two samples, and why both are needed</h2>
 *
 * <b>Same tick</b> answers「服务端写下去了吗」. <b>After {@link #SETTLE_TICKS} real ticks</b> answers
 * 「客户端把它盖回去了吗」. Only the second can see the packet arrive, and only the first can tell a
 * write that never landed from one that landed and was reverted — printing one without the other
 * reproduces the ambiguity the body census had to grow a second phase to escape.
 */
public final class WorldDriverActuatorSplitScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                // Optional, permanently. This scene has no verdict: it reports how far apart two
                // authorities are, and every value it can print is a legitimate answer. Marking it
                // required would promise the gate a failure mode that does not exist.
                Scene.of("wd.actuatorSplitOnAnAdoptedBody", 200,
                        WorldDriverActuatorSplitScenes::actuatorSplit).withRequired(false),
                // The A0 twin. Same measurement, taken through the avatar the rungs now use, so the
                // pair reads as a before/after in ONE run. Deliberately a SECOND scene rather than an
                // edit to the first: the first is the ruler for the raw divergence, and if anyone
                // ever routes those call sites back to the server avatar, it goes on saying so.
                // Editing it to follow the fix would have meant changing the acceptance criterion
                // inside the change it judges.
                Scene.of("wd.actuatorSplitThroughTheClientAvatar", 200,
                        WorldDriverActuatorSplitScenes::actuatorSplitClient).withRequired(false));
    }

    /**
     * Real server ticks between the write and the second sample.
     *
     * <p>Ten, because the quantity being waited for is a network round trip on a connection whose
     * client end ticks independently. One tick could sample before the client has even run the tick
     * that would contest the write, which would report「没有分歧」for a divergence that arrives on
     * the next tick — the false-negative this whole scene exists to prevent.
     */
    private static final int SETTLE_TICKS = 10;

    /**
     * The slot the ruler scene's server-side write aims at, and the twin's fallback when the client
     * cannot be read at all.
     *
     * <p>Not 0, so that「写成功了」and「本来就是这个值」cannot print identically — 0==0 with extra
     * steps. But note that is only true for the RULER, which merely measures and asserts nothing
     * about the value. <b>A constant is not good enough for a criterion</b>: the twin derives its
     * target from the client's actual pre-write slot instead, because「故意不选 0」is an assumption
     * about the starting state, and a criterion resting on an assumption passes for free the day
     * the assumption stops holding.
     */
    private static final int TARGET_SLOT = 4;

    private static void actuatorSplit(SceneContext ctx) {
        // Read off the running game, never echoed from a -D: a launch can set a property and then
        // fail to do what it promised, and this scene's whole value is being unable to lie.
        MinecraftServer server = ctx.server();
        boolean integrated = server != null && !server.isDedicatedServer();
        ctx.record("topology", topology(ctx));
        if (!integrated || !BotHooks.isAvailable()) {
            ctx.skip("这条只在集成服上有意义：需要同一个 JVM 里既有服务端身体又有真的 LocalPlayer");
        }
        List<ServerPlayer> humans = humanPlayers(ctx);
        if (humans.isEmpty()) {
            ctx.skip("集成服上没有真玩家 —— 客户端还没进世界，或已经掉线");
        }

        ServerPlayer real = humans.get(0);

        // ---- WHO, before anything else, and the scene REFUSES rather than reporting beside it ----
        //
        // The gates now run with -Dworlddriver.realPlayerBodies=true, so this JVM holds a JoinedBody
        // as well as the client's player, and picking the wrong one would produce a full set of
        // plausible numbers describing the wrong subject — 「服务端写下去了，客户端没盖」would be
        // trivially true of a body no client has ever heard of. Every row below is void in that case,
        // so this must not be a row a reader has to notice: a criterion that depends on someone
        // checking a name before reading the numbers fails exactly when the numbers are interesting.
        //
        // Asked STRUCTURALLY, not by name. `agent-body-N` is a naming convention (JoinedPlayerBodies
        // .profileFor) and conventions get changed by people who do not know this scene reads them;
        // the class is what the two bodies actually differ by, and both driver-minted types are
        // checked so a future third one cannot slip through as「不是 JoinedBody 所以是真人」.
        boolean driverMinted = real instanceof JoinedPlayerBodies.JoinedBody
                || real instanceof AvatarFakePlayer;
        ctx.record("body", real.getGameProfile().getName() + "（" + real.getClass().getSimpleName()
                + "，在玩家表=" + real.level().players().contains(real)
                + "，驱动器自造=" + driverMinted + "）");
        if (driverMinted) {
            ctx.fail("挑错了身体：拿到的是驱动器自造的 " + real.getClass().getSimpleName()
                    + " " + real.getGameProfile().getName() + "，不是客户端的真玩家。"
                    + "这一整份读数作废 —— 一具没有客户端的身体当然「服务端写了没人盖」，"
                    + "那不是这条场景要问的问题。humanPlayers() 的排除判据要修。");
        }

        // The SAME wrapper JourneyRig.spawnBody() builds around an adopted player. Not a
        // reimplementation of its writes: if ServerPlayerAvatar's actuators change, this scene has to
        // change with them, and a copy would keep reporting the old mechanism's behaviour forever.
        Avatar avatar = new ServerPlayerAvatar(real);

        // ---- put it back, registered BEFORE the first write ---------------------------------
        //
        // This scene writes into a HUMAN's player: a stone into a hotbar slot, a selected slot, a
        // yaw and a pitch. Everything after this line runs on the client's real body, and the scenes
        // after this one inherit whatever it leaves — an arena audit calls a leftover entity a leak
        // and is right to; an item pushed into a player's inventory is the same class of residue.
        //
        // Registered here rather than at the end because cleanups drain on PASS, FAIL and TIMEOUT
        // alike, and the interesting exits are the other two. The wrong-body refusal above cannot
        // reach this point, by design — it fires before anything is written — but the final check
        // and the await budget both can end the scene after the writes have landed, and a restore
        // written after them would be skipped on exactly those runs.
        //
        // NOT restored through the Avatar. `holdItem` is the verb under measurement, and a cleanup
        // that runs the thing it is measuring fails silently precisely when that thing is broken —
        // and then lands on the NEXT scene, which is a shape this repo has already paid for. The
        // raw field is the primitive underneath it, so a restore can fail here only if the field
        // itself is unwritable.
        ItemStack slotWas = real.getInventory().items.get(TARGET_SLOT).copy();
        int selectedWas = real.getInventory().selected;
        float yawWas = real.getYRot();
        float pitchWas = real.getXRot();
        ctx.cleanup(() -> {
            real.getInventory().items.set(TARGET_SLOT, slotWas);
            real.getInventory().selected = selectedWas;
            real.setYRot(yawWas);
            real.setXRot(pitchWas);
            // Two rows used to sit here saying whether the restore took —「cleanup.槽位还原」and
            //「cleanup.物品还原」. Deleted, because each failed on its own:
            //
            //   • UNREADABLE. `SceneContext.record` is a bare map put and logs nothing, and the
            //     harness serialises `ctx.records()` inside `record(scene, …)`, which precedes
            //     `teardown(…)` — the caller of these cleanups — on every terminal path. A row
            //     written from here lands in a map whose only reader finished reading it.
            //   • UNFALSIFIABLE. Both read back what the lines above had just written, on this
            //     thread, with only the two rotation setters in between: `selected == selectedWas`
            //     one statement after `selected = selectedWas`, and `ItemStack.matches` against the
            //     very object just placed in the slot. They could print 是 and nothing else.
            //
            // The intent was right and the instrument was wrong, so relocating the rows would only
            // have moved a tautology somewhere visible. The CLIENT does own these two quantities,
            // but it takes them back by packet on a LATER tick — which is exactly why the scene asks
            // that question below as `serverSlotSameTick` against `clientSlotSameTick`, two sources
            // compared, rather than by reading one field twice. Teardown has no later tick, so the
            // question cannot be asked from here at all.
        });

        // ---- 1. the hotbar slot -------------------------------------------------------------
        // Give the body something to select, so holdItem has a real target rather than failing for
        // the uninteresting reason that the item is absent. Recorded, because「没换成」and「没东西可换」
        // are different findings and must never print alike.
        real.getInventory().items.set(TARGET_SLOT, new ItemStack(Items.STONE, 1));
        int slotBefore = real.getInventory().selected;
        boolean held = avatar.holdItem(Items.STONE);
        int serverSlotSameTick = real.getInventory().selected;
        Integer clientSlotSameTick = clientSelectedSlot();

        ctx.record("slot.前", slotBefore);
        ctx.record("slot.holdItem返回", held);
        // WHICH THREAD DID THE WRITING. Without this row a「一致」reading is ambiguous in the one
        // direction that matters: a cross-thread write to client state usually does NOT throw (few
        // of these vanilla fields carry a thread assertion), so an actuator that agrees today can be
        // sitting on a data race that disagrees under load. 「绿了」would not mean「对了」. Recorded
        // at BOTH sample points, because the scene body and its await continuation are different
        // moments and nothing guarantees the harness runs them on the same thread.
        ctx.record("thread.写入时", Thread.currentThread().getName());
        ctx.record("slot.服务端.同tick", serverSlotSameTick);
        ctx.record("slot.客户端.同tick", render(clientSlotSameTick));
        ctx.record("slot.同tick一致", agree(serverSlotSameTick, clientSlotSameTick));

        // ---- 2. the aim ---------------------------------------------------------------------
        // A cell far enough off-axis that the resulting angles cannot coincide with whatever the
        // body happened to be facing — an aim that agrees by luck measures nothing.
        BlockPos aimAt = chooseAimTarget(real);
        ctx.record("aim.选格依据", aimChoiceEvidence(real, aimAt));
        float yawBefore = real.getYRot();
        float pitchBefore = real.getXRot();
        avatar.aimAtBlock(aimAt);
        float serverYawSameTick = real.getYRot();
        float serverPitchSameTick = real.getXRot();
        float[] clientLookSameTick = clientLook();
        // WHERE the body stood when the angle was written, and what the angle therefore had to be.
        // An aim stores ANGLES, not a target — the body moving afterwards silently invalidates it,
        // and at this range (~8.6 blocks) three quarters of a block of drift is worth the entire
        // tolerance. Captured here so the continuation can say whether the requirement moved, and so
        // the criterion below can be judged against what the actuator was ASKED for rather than
        // against a requirement recomputed ten ticks later at a position nobody asked about.
        Vec3 posAtWrite = real.position();
        float[] wantAtWrite = aimFromEyeTo(real, aimAt);

        ctx.record("aim.目标格", aimAt.toShortString());
        ctx.record("aim.前", deg(yawBefore) + " / " + deg(pitchBefore));
        ctx.record("aim.服务端.同tick", deg(serverYawSameTick) + " / " + deg(serverPitchSameTick));
        ctx.record("aim.客户端.同tick", renderLook(clientLookSameTick));

        // ---- 3. let real ticks pass, then ask both sides again --------------------------------
        // The whole point: only after the client has ticked can its packet contest either write.
        int[] waited = {0};
        ctx.await(() -> ++waited[0] >= SETTLE_TICKS).within(SETTLE_TICKS + 100).then(() -> {
            int serverSlotAfter = real.getInventory().selected;
            Integer clientSlotAfter = clientSelectedSlot();
            float serverYawAfter = real.getYRot();
            float serverPitchAfter = real.getXRot();
            float[] clientLookAfter = clientLook();

            ctx.record("thread.复查时", Thread.currentThread().getName());
            ctx.record("slot.服务端.过" + SETTLE_TICKS + "tick", serverSlotAfter);
            ctx.record("slot.客户端.过" + SETTLE_TICKS + "tick", render(clientSlotAfter));
            ctx.record("slot.最终一致", agree(serverSlotAfter, clientSlotAfter));
            ctx.record("slot.服务端被回滚", serverSlotSameTick != serverSlotAfter
                    ? "是：" + serverSlotSameTick + " → " + serverSlotAfter
                    : "否（服务端仍是 " + serverSlotAfter + "）");

            ctx.record("aim.服务端.过" + SETTLE_TICKS + "tick",
                    deg(serverYawAfter) + " / " + deg(serverPitchAfter));
            ctx.record("aim.客户端.过" + SETTLE_TICKS + "tick", renderLook(clientLookAfter));
            ctx.record("aim.服务端被回滚", drift(serverYawSameTick, serverPitchSameTick,
                    serverYawAfter, serverPitchAfter));
            ctx.record("aim.两侧差", clientLookAfter == null ? "unavailable/客户端读不到"
                    : drift(serverYawAfter, serverPitchAfter, clientLookAfter[0], clientLookAfter[1]));

            // ---- the twin's aim criterion, applied HERE, where it must NOT hold -----------------
            //
            // The negative control for `wd.actuatorSplitThroughTheClientAvatar`'s aim check. That
            // check can only be trusted if it is capable of going red, and nothing else in the suite
            // demonstrates that: on a healthy tree it passes every run, which is indistinguishable
            // from a criterion that passes unconditionally.
            //
            // This scene aims at the SAME cell through the OLD server-side path, so evaluating the
            // twin's exact predicate on this reading must come out FALSE. Two properties make it
            // worth having as code rather than as a one-off experiment: it runs on every run, so it
            // cannot rot the way「记得手动改回旧路径试一次」does; and it leaves no broken state
            // behind if the run is interrupted, which a temporary revert of a live call site does.
            //
            // RECORDED, not checked. This scene has no verdict by design — see the class javadoc —
            // and asserting here would give it one, in the direction of「缺陷必须继续存在」, which is
            // a criterion nobody should be able to satisfy by fixing something. The ⚠️ is for the
            // reader: if the server path ever starts driving the client's aim too, the twin's check
            // has stopped distinguishing the two routes and is no longer evidence of anything.
            float[] wantHere = aimFromEyeTo(real, aimAt);
            ctx.record("aim.要求漂移",
                    requirementDrift(posAtWrite, real.position(), wantAtWrite, wantHere));
            boolean clientFollowed = aimSatisfies(clientLookAfter, wantAtWrite);
            // 「客户端本来就朝着那边」是第三种结局，必须单独说。同 tick 那次读数取自服务端写完的
            // 瞬间 —— 服务端 actuator 只碰 ServerPlayer 的字段，同一个 JVM 里的 LocalPlayer 那一刻
            // 还没有任何理由动过，所以它就是客户端的「写之前」。如果那时客户端已经满足几何要求，
            // 这一趟的对照什么也证明不了：满足是巧合，不满足反而说明客户端后来转开了。
            // 不把它折进上面两支，是因为那会让一次巧合印成「⚠️ 判据失效」，
            // 派人去查一条其实好好的判据 —— 一个假的「不行」和一个假的「可以」一样贵。
            //
            // 两支都拿 wantAtWrite 比，不拿 wantHere：同 tick 的读数配 +10 tick 算出来的要求，
            // 就是把两个时刻的量放进同一个不等式，身体只要动过一点，这两支就会因为取样时刻不同
            // 而互相矛盾，而不是因为客户端真的转了。
            boolean clientAlreadyThere = aimSatisfies(clientLookSameTick, wantAtWrite);
            // The margin, printed. Whether the control holds comfortably or by a fraction of a
            // degree is not visible from「有效」alone, and the difference decides whether anyone
            // should trust it next month.
            ctx.record("aim.对照缺口", aimGap(clientLookAfter, wantAtWrite));
            ctx.record("aim.阴性对照", clientLookAfter == null
                    ? "读不到客户端朝向，这一趟没法当对照"
                    : clientAlreadyThere
                            ? "本趟作废：写之前客户端就已经朝着 " + aimAt.toShortString()
                                    + "（同 tick 读数 " + renderLook(clientLookSameTick)
                                    + "，几何要求 " + deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1])
                                    + "）—— 巧合，这一趟分不出「服务端驱动了客户端」和"
                                    + "「客户端本来就在那儿」，别拿它当结论"
                            : clientFollowed
                                    ? "⚠️ 走服务端 actuator，客户端朝向竟然也跟到了几何要求上"
                                            + "（写之前是 " + renderLook(clientLookSameTick)
                                            + "，" + SETTLE_TICKS + " tick 后变成 "
                                            + renderLook(clientLookAfter) + "）—— 双胞胎那条 A0 "
                                            + "瞄准判据已经分不出两条路径，它的绿不再是证据，去查它"
                                    : "有效：同一目标格走服务端 actuator 时，客户端朝向不满足几何要求"
                                            + "（要求 " + deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1])
                                            + "，实际 " + renderLook(clientLookAfter) + "）——"
                                            + "所以双胞胎那条判据在缺陷存在时确实会红");

            // THE ONLY CHECK, and it is about the instrument rather than the subject: if the client
            // side could not be read, every 一致/差 row above is the string "unavailable" and the
            // scene has measured nothing. Without this the scene would go green while reporting
            // nothing at all — the shape where a suite reports a subject it never executed.
            ctx.check(clientSlotAfter != null && clientLookAfter != null)
                    .as("客户端侧读数拿得到（mc.client.player 报了 selectedSlot 和 look）——"
                            + "拿不到的话上面每一行「一致」都是空话，这条场景什么都没量到")
                    .isTrue();
        });
    }

    /**
     * The same measurement, taken through {@code BotApi.clientAvatar()} — the avatar the rungs use
     * after A0.
     *
     * <p><b>The acceptance criterion for A0, and it is designed to be able to fail.</b> Its sibling
     * measured the raw divergence and found it total: server slot 4 vs client 0, server aim
     * (−55.32, 29.55) vs client (283.23, 0.00), unchanged ten ticks later. If routing the same two
     * writes through the client's own avatar does not close that gap, this scene says so.
     *
     * <p><b>A green row here is necessary and not sufficient</b>, and the {@code thread.*} rows are
     * why. These calls run on the SERVER thread — the open defect stated on
     * {@code BotApi.clientAvatar()} — and a cross-thread write to client state usually does not
     * throw. So agreement measured here can be agreement that happens to hold, not agreement that is
     * guaranteed. Read the thread rows before reading the numbers.
     */
    private static void actuatorSplitClient(SceneContext ctx) {
        MinecraftServer server = ctx.server();
        boolean integrated = server != null && !server.isDedicatedServer();
        ctx.record("topology", topology(ctx));
        if (!integrated || !BotHooks.isAvailable()) {
            ctx.skip("这条只在集成服上有意义：需要同一个 JVM 里既有服务端身体又有真的 LocalPlayer");
        }
        List<ServerPlayer> humans = humanPlayers(ctx);
        if (humans.isEmpty()) {
            ctx.skip("集成服上没有真玩家 —— 客户端还没进世界，或已经掉线");
        }
        ServerPlayer real = humans.get(0);

        // Same structural refusal as the sibling, and for the same reason: a body no client owns
        // would make「两侧一致」trivially true and this scene would certify the fix on the strength
        // of never having tested it.
        boolean driverMinted = real instanceof JoinedPlayerBodies.JoinedBody
                || real instanceof AvatarFakePlayer;
        ctx.record("body", real.getGameProfile().getName() + "（" + real.getClass().getSimpleName()
                + "，驱动器自造=" + driverMinted + "）");
        if (driverMinted) {
            ctx.fail("挑错了身体：拿到的是驱动器自造的 " + real.getClass().getSimpleName()
                    + "，这一整份读数作废 —— 这条场景是 A0 的验收判据，"
                    + "拿一具没有客户端的身体验收等于没验。");
        }

        // THE ONE DIFFERENCE from the sibling: the avatar comes from the client, not from a
        // ServerPlayerAvatar wrapped around the ServerPlayer.
        Avatar client = BotHooks.impl() == null ? null : BotHooks.impl().clientAvatar();
        if (client == null) {
            ctx.fail("BotApi.clientAvatar() 返回 null —— 客户端没有 LocalPlayer，"
                    + "A0 的路径在这一趟根本没被走到，不能读成「修好了」");
        }

        // No inventory staging here, unlike the sibling: this scene's probe does not read contents,
        // so putting an item in would be a write with no reader — and one more thing to restore.
        int selectedWas = real.getInventory().selected;
        float yawWas = real.getYRot();
        float pitchWas = real.getXRot();
        ctx.cleanup(() -> {
            real.getInventory().selected = selectedWas;
            real.setYRot(yawWas);
            real.setXRot(pitchWas);
        });

        // ⚠️ setSelectedSlot, NOT holdItem — and this is the whole difference between measuring A0
        // and measuring this scene's own staging mistake.
        //
        // `holdItem` on the client resolves through BotInteract.ensureHolding, which searches the
        // CLIENT's inventory for the item. The staging above puts the stone into the SERVER's copy,
        // and the client learns about it only when a container/slot packet arrives — not in this
        // tick. So a client holdItem would return false here for a reason that has nothing to do
        // with the actuator being tested, and the criterion below would go red while A0 was working
        // perfectly. That is the shape this file exists to avoid: a failure whose cause is the test.
        //
        // setSelectedSlot asks the identical question with no dependency on inventory CONTENTS —
        // it writes the client's `selected` and sends the ServerboundSetCarriedItemPacket, which is
        // exactly the mechanism whose absence the sibling scene measured (server 4 / client 0).
        // ---- the probe must MOVE something, or a green here certifies nothing ----------------
        //
        // Both criteria in this scene have the form「客户端最后等于目标值」. If the client ALREADY
        // sat at that value before the write, they pass while the actuator contributes exactly
        // zero — a scene going green while the mechanism in its name did nothing at all. A fixed
        // TARGET_SLOT can only ever be an ASSUMPTION about the starting state (「故意不选 0」is an
        // assumption, not a guard), and assumptions about starting state are what this file exists
        // to stop trusting.
        //
        // So the target is DERIVED from the reading: +4 mod 9 is provably a different slot from
        // whatever the client is on, whatever that turns out to be. Now「客户端最后在目标槽」can
        // only be true if something actually moved it.
        Integer clientSlotBefore = clientSelectedSlot();
        int targetSlot = clientSlotBefore == null ? TARGET_SLOT : (clientSlotBefore + 4) % 9;
        ctx.record("slot.动作前客户端", render(clientSlotBefore));
        ctx.record("slot.目标槽", targetSlot + "（由动作前的客户端读数推出，保证与它不同。"
                + "写死常数的话，客户端恰好已经在那一格时这条判据会零贡献地绿）");

        ctx.record("thread.写入时", Thread.currentThread().getName());
        client.setSelectedSlot(targetSlot);
        int serverSlot = real.getInventory().selected;
        Integer clientSlot = clientSelectedSlot();
        ctx.record("slot.动作", "setSelectedSlot(" + targetSlot + ")（不用 holdItem：它按客户端"
                + "背包内容找物品，而这条场景的布景只放进了服务端那份，会红在布景上而不是红在缺陷上）");
        ctx.record("slot.服务端.同tick", serverSlot);
        ctx.record("slot.客户端.同tick", render(clientSlot));
        ctx.record("slot.同tick一致", agree(serverSlot, clientSlot));

        // Same disease on the aim half, same cure. Point the client at a cell in the OPPOSITE
        // direction first, so「最后朝着目标格」cannot be satisfied by wherever it already happened
        // to be looking. Without this, a client idly facing that quadrant hands the aim criterion a
        // free pass and A0 gets credit for an angle it never wrote.
        //
        // Yes, the parking uses the verb under test — but it cannot manufacture a false GREEN, only
        // a red. If aimAtBlock is broken the park does nothing, and then either the client is left
        // where it was (the precondition check below fires and says so) or it is left satisfying the
        // requirement by luck (same check fires). A broken actuator cannot reach a pass through
        // this door; that is the difference between this and a cleanup that runs its own verb.
        BlockPos aimAt = chooseAimTarget(real);
        ctx.record("aim.选格依据", aimChoiceEvidence(real, aimAt));
        BlockPos parkAt = chooseParkTarget(real, aimFromEyeTo(real, aimAt));
        client.aimAtBlock(parkAt);
        float[] clientLookParked = clientLook();

        // Same reason as the sibling: the actuator writes angles, so the requirement must be pinned
        // to the moment it was asked for. Judging at +10 ticks against a recomputed requirement would
        // let a body that merely MOVED fail this criterion, and A0 would be blamed for physics.
        Vec3 posAtWrite = real.position();
        float[] wantAtWrite = aimFromEyeTo(real, aimAt);
        // The precondition, measured BEFORE the real aim: parked and provably not already on target.
        boolean parkedAway = !aimSatisfies(clientLookParked, wantAtWrite);
        ctx.record("aim.停靠格", parkAt.toShortString());
        ctx.record("aim.动作前客户端", renderLook(clientLookParked));
        ctx.record("aim.前置成立", clientLookParked == null ? "读不到客户端朝向，判不了"
                : parkedAway ? "是：动作前客户端不满足目标几何要求，所以「最后满足」只能是这次动作造成的"
                        : "⚠️ 否：动作前客户端就已经满足目标几何要求了 —— "
                                + "这一趟的瞄准判据零贡献也能绿，别把它读成 A0 通过");

        client.aimAtBlock(aimAt);
        float[] clientLookNow = clientLook();
        ctx.record("aim.目标格", aimAt.toShortString());
        ctx.record("aim.服务端.同tick", deg(real.getYRot()) + " / " + deg(real.getXRot()));
        ctx.record("aim.客户端.同tick", renderLook(clientLookNow));

        int[] waited = {0};
        ctx.await(() -> ++waited[0] >= SETTLE_TICKS).within(SETTLE_TICKS + 100).then(() -> {
            ctx.record("thread.复查时", Thread.currentThread().getName());
            int serverSlotAfter = real.getInventory().selected;
            Integer clientSlotAfter = clientSelectedSlot();
            float[] clientLookAfter = clientLook();
            ctx.record("slot.服务端.过" + SETTLE_TICKS + "tick", serverSlotAfter);
            ctx.record("slot.客户端.过" + SETTLE_TICKS + "tick", render(clientSlotAfter));
            ctx.record("slot.最终一致", agree(serverSlotAfter, clientSlotAfter));
            ctx.record("aim.服务端.过" + SETTLE_TICKS + "tick",
                    deg(real.getYRot()) + " / " + deg(real.getXRot()));
            ctx.record("aim.客户端.过" + SETTLE_TICKS + "tick", renderLook(clientLookAfter));
            ctx.record("aim.两侧差", clientLookAfter == null ? "unavailable/客户端读不到"
                    : drift(real.getYRot(), real.getXRot(), clientLookAfter[0], clientLookAfter[1]));

            // ⚠️ EVERY evidence row is written BEFORE the first ctx.check, and the order matters.
            //
            // A failing check throws, so anything recorded after it never reaches the results file.
            // The two halves of A0 — the slot and the aim — are INDEPENDENT: the aim rows are not
            // context for the slot criterion, they are the only description of a separate subject.
            // Recording them after the slot check meant that a broken slot half deleted the entire
            // aim diagnosis from the record, and the reader would be told nothing about the half
            // that might still be fine. Evidence goes in the file first; verdicts come after.
            float[] want = aimFromEyeTo(real, aimAt);
            boolean aimed = aimSatisfies(clientLookAfter, wantAtWrite);

            // THE SAME CRITERION FOR THE OTHER HALF. A0 rerouted `aimAtBlock` at 8 call sites and
            // nothing above could go red if that regressed: every aim row was ctx.record. A scene
            // that only PRINTS the quantity it exists to protect is the shape this repo keeps
            // paying for — the reading is there, and no run fails when it goes wrong.
            //
            // The threshold comes from GEOMETRY, not from either body's reported angle. Deriving
            // an aim criterion from the aim being measured is the same defect as taking a Y-band
            // ceiling from the drifted body: it would be satisfied by any value the actuator
            // happened to write, including no write at all.
            ctx.record("aim.几何要求", deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1])
                    + "（由目标格心与写入那一刻的眼位算出，与两侧读数无关）");
            ctx.record("aim.要求漂移",
                    requirementDrift(posAtWrite, real.position(), wantAtWrite, want));
            // WHICH of the three ways this can go wrong, named rather than left to be inferred.
            //
            // The client runs LookController as a post-filter at the end of every client tick: it
            // rewrites yaw/pitch, verbatim when the tick is snap-exempt (aimAtBlockSnap asks for
            // that) and otherwise clamped to 30°/tick yaw and 20°/tick pitch. So a red here has a
            // third cause besides「写失败了」—— the write landing and then being filtered or
            // overwritten by a later actuator in the same tick. The two samples already tell those
            // apart; without this row the reader has to notice that themselves, and the whole point
            // of the same-tick sample is lost the moment nobody reads it next to the later one.
            //
            // Note SETTLE_TICKS is generous enough that slewing alone cannot explain a red: the
            // measured gaps are 12-24° yaw and 26-30° pitch, and ten ticks of even the CLAMPED rate
            // covers 300° / 200°. If this row says 落地后被改回, something rewrote it — not slew.
            ctx.record("aim.客户端.落地了吗", clientLookNow == null || clientLookAfter == null
                    ? "读不到，判不了"
                    : aimSatisfies(clientLookNow, wantAtWrite)
                            ? (aimed ? "落地并保持住了（同 tick 就到位，" + SETTLE_TICKS + " tick 后仍在）"
                                     : "⚠️ 落地后被改回：同 tick 已到位 " + renderLook(clientLookNow)
                                             + "，" + SETTLE_TICKS + " tick 后变成 "
                                             + renderLook(clientLookAfter)
                                             + " —— actuator 写成功了，是后面有人覆盖它，"
                                             + "去查 LookController 和同 tick 的其它转向写入")
                            : (aimed ? "晚到：同 tick 还是 " + renderLook(clientLookNow)
                                             + "，" + SETTLE_TICKS + " tick 后才到位 —— 判据算过，"
                                             + "但说明这条路径不是同步生效的"
                                     : "⚠️ 从未落地：同 tick 和 " + SETTLE_TICKS
                                             + " tick 后都不满足几何要求 —— 写本身没生效，"
                                             + "这才是 A0 那条路径真的坏了的样子"));
            // ---- verdicts, all of them after every row above is safely in the file ----------
            // Instrument first: an unreadable client side makes every「一致」row above a空话.
            ctx.check(clientSlotAfter != null && clientLookAfter != null)
                    .as("客户端侧读数拿得到 —— 拿不到的话上面每一行「一致」都是空话")
                    .isTrue();
            // THE criterion for the slot half. Deliberately asserted on the CLIENT's value, not on
            // agreement between the two: agreement would also be satisfied by both sides being
            // wrong together.
            // The two preconditions, asserted rather than merely printed. Each one is the reason its
            // criterion is evidence at all: if the client was already on the target slot, or already
            // aimed at the target cell, the criterion below passes with the actuator contributing
            // nothing. Recording that and passing anyway would be the shape we are trying to kill —
            // a green whose named mechanism moved zero.
            ctx.check(clientSlotBefore != null && clientSlotBefore != targetSlot)
                    .as("前置：动作前客户端不在目标槽（动作前 " + render(clientSlotBefore)
                            + "，目标 " + targetSlot + "）。不成立的话下面那条判据零贡献也会绿")
                    .isTrue();
            ctx.check(parkedAway)
                    .as("前置：动作前已把客户端朝向停靠到别处，且不满足目标几何要求（停靠后 "
                            + renderLook(clientLookParked) + "，目标要求 " + deg(wantAtWrite[0])
                            + " / " + deg(wantAtWrite[1]) + "）。不成立的话瞄准判据零贡献也会绿")
                    .isTrue();
            ctx.check(clientSlotAfter != null && clientSlotAfter == targetSlot)
                    .as("A0 判据：客户端自己的选中槽应为 " + targetSlot + "，实际 "
                            + render(clientSlotAfter) + "。这一条在缺陷存在时会红 —— "
                            + "修复之前同样的写法测出来客户端停在动作前那一格没动")
                    .isTrue();
            ctx.check(aimed)
                    .as("A0 判据（瞄准半边）：客户端自己的朝向应指向 " + aimAt.toShortString()
                            + "，几何要求 " + deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1])
                            + "，实际 " + renderLook(clientLookAfter)
                            + "，差 " + aimGap(clientLookAfter, wantAtWrite)
                            + "。缺陷存在时这一条会红 —— 证据去看同一趟 "
                            + "wd.actuatorSplitOnAnAdoptedBody 的 aim.阴性对照 行，"
                            + "那是同一条谓词喂服务端路径读数的结果（不在这里写死数字：站位一变就不成立，"
                            + "上一版写的 12~24° 下一趟实测就成了 5.20°）。"
                            + "红了先看 aim.客户端.落地了吗 和 aim.要求漂移 两行分死因")
                    .isTrue();
        });
    }

    // ------------------------------------------------------------------ client-side reads

    /**
     * The client's own selected slot, or null when it cannot be read.
     *
     * <p>Null rather than a sentinel number, and rendered as {@code unavailable/<原因>} — a slot of
     * {@code -1} or {@code 0} standing in for「没读到」is the exact confusion this repo keeps paying
     * for, where 0 means「还没算过」and is read as「算出来是零」.
     */
    private static Integer clientSelectedSlot() {
        Map<?, ?> cp = clientPlayer();
        if (cp == null) return null;
        return cp.get("selectedSlot") instanceof Number n ? n.intValue() : null;
    }

    /** The client's own {yaw, pitch}, or null when it cannot be read. */
    private static float[] clientLook() {
        Map<?, ?> cp = clientPlayer();
        if (cp == null) return null;
        if (!(cp.get("look") instanceof Map<?, ?> look)) return null;
        if (!(look.get("yaw") instanceof Number y) || !(look.get("pitch") instanceof Number p)) return null;
        return new float[]{y.floatValue(), p.floatValue()};
    }

    /**
     * {@code mc.client.player}'s payload, or null if the route is unavailable or reports no player.
     *
     * <p>Exceptions are swallowed deliberately and turned into null: this scene's subject is the
     * DIVERGENCE, and a client that cannot answer is a reading it must report rather than a crash
     * that replaces every other reading with a stack trace.
     */
    private static Map<?, ?> clientPlayer() {
        try {
            DriverApi api = WorldDriverCommon.api();
            if (api == null) return null;
            Object r = api.route("mc.client.player", Map.of());
            if (!(r instanceof Map<?, ?> m)) return null;
            return Boolean.TRUE.equals(m.get("present")) ? m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * How far the client's own aim may sit from the geometric requirement before this scene calls it
     * unaimed.
     *
     * <p>Five degrees, and the number is chosen against the MEASURED defect rather than picked for
     * feeling safe: the ruler scene recorded gaps of 12-24° in yaw and 26-30° in pitch, so five
     * separates「瞄准生效了」from「完全没生效」by a wide margin while leaving room for the eye-height
     * and sub-tick position differences between the two bodies. A tolerance tuned tighter would make
     * this criterion report the difference between two healthy implementations.
     */
    private static final float AIM_TOLERANCE_DEG = 5.0f;

    /**
     * How much the aim REQUIREMENT moved while the scene waited, and whether that invalidates the
     * verdict.
     *
     * <p>An {@code aimAtBlock} stores <b>angles</b>, not a target. Once written, the body moving
     * makes them stale, and nothing in the actuator notices. At this scene's range (~8.6 blocks
     * horizontally) roughly three quarters of a block of drift is worth the entire
     * {@link #AIM_TOLERANCE_DEG} tolerance — so a body that got pushed, fell, or was shoved by a mob
     * during the settle would make the aim criterion go red <b>on a perfectly healthy actuator</b>.
     *
     * <p>This row exists so that failure can never be silent. Without it,「客户端朝向不对」has two
     * causes that print identically, and the reader has no way to tell「actuator 没生效」from
     * 「身体动了，角度过期了」— which is precisely the ambiguity that costs rounds in this repo.
     * The criterion itself is judged against the write-time requirement, so movement does not
     * actually change the verdict; this row is what tells a reader that, instead of asking them to
     * take it on faith.
     */
    private static String requirementDrift(Vec3 from, Vec3 to, float[] wantAtWrite, float[] wantNow) {
        double moved = from.distanceTo(to);
        float dy = Math.abs(wrap(wantNow[0] - wantAtWrite[0]));
        float dp = Math.abs(wantNow[1] - wantAtWrite[1]);
        boolean stale = Math.max(dy, dp) >= AIM_TOLERANCE_DEG;
        return (stale ? "⚠️ " : "") + "身体位移 " + String.format(Locale.ROOT, "%.3f", moved)
                + " 格（" + pos(from) + " → " + pos(to) + "），几何要求随之从 "
                + deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1]) + " 变成 "
                + deg(wantNow[0]) + " / " + deg(wantNow[1]) + "，差 " + deg(dy) + "° / " + deg(dp) + "°"
                + (stale
                        ? "：已经吃掉整条 " + deg(AIM_TOLERANCE_DEG) + "° 容差。判据比的是写入时那份要求，"
                                + "所以结论仍然成立；但这一趟身体确实动了，读别的行时把这件事算进去"
                        : "，远小于 " + deg(AIM_TOLERANCE_DEG) + "° 容差 —— 身体基本没动，"
                                + "瞄准这半边的红绿只可能来自 actuator 本身");
    }

    private static String pos(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z);
    }

    /**
     * The aim predicate itself, in ONE place because two scenes must ask it identically.
     *
     * <p>The twin scene asserts this and {@code wd.actuatorSplitOnAnAdoptedBody} evaluates it as a
     * negative control — and a negative control is only evidence about a criterion if it is the SAME
     * criterion. Written out twice, an edit to the assertion would silently stop being mirrored by
     * the control, and the control would go on reporting「有效」about a predicate that no longer
     * exists. Sharing the method makes that divergence impossible rather than merely unlikely.
     */
    private static boolean aimSatisfies(float[] look, float[] want) {
        return look != null
                && Math.abs(wrap(look[0] - want[0])) <= AIM_TOLERANCE_DEG
                && Math.abs(look[1] - want[1]) <= AIM_TOLERANCE_DEG;
    }

    /**
     * Candidate cells to aim at, spread across quadrants and above/below the eye.
     *
     * <p>Never picked by index — see {@link #chooseAimTarget}. A fixed choice is what put a 5.21°
     * yaw gap into the first green run: {@code offset(7,-3,5)} happened to sit almost exactly along
     * the body's spawn facing, so the yaw half of the test asked the actuator to turn five degrees
     * and the whole reading rested on pitch. The measurement looked two-dimensional and was not.
     */
    private static final int[][] AIM_CANDIDATES = {
            {7, -3, 5}, {-7, -3, -5}, {7, -3, -5}, {-7, -3, 5},
            {7, 4, 5}, {-7, 4, -5}, {5, -5, -8}, {-5, -5, 8},
    };

    /**
     * The candidate cell that forces the LARGEST movement in BOTH yaw and pitch from where the body
     * currently looks — chosen by maximising the smaller of the two gaps.
     *
     * <p>Maximising the <i>minimum</i> is the whole point. Being far in one component is enough to
     * make the aim predicate false, so a control would still read「有效」— but it would prove
     * nothing about the other component, and the run would report a two-axis test it never
     * performed. Requiring both gaps to be large is what makes「客户端跟到了」evidence about yaw
     * <i>and</i> pitch.
     *
     * <p>Derived from the body's own SERVER-side rotation, deliberately not from the client's
     * reported look: the client's angles are (half of) what these scenes measure, and choosing the
     * target from the measurement is the shared-source mistake this file keeps warning about.
     * Both scenes call this, so both aim at the same cell and their rows stay comparable.
     */
    private static BlockPos chooseAimTarget(ServerPlayer body) {
        BlockPos best = null;
        float bestScore = -1f;
        for (int[] o : AIM_CANDIDATES) {
            BlockPos cell = body.blockPosition().offset(o[0], o[1], o[2]);
            float[] want = aimFromEyeTo(body, cell);
            float score = Math.min(Math.abs(wrap(want[0] - body.getYRot())),
                    Math.abs(want[1] - body.getXRot()));
            if (score > bestScore) { bestScore = score; best = cell; }
        }
        return best;
    }

    /** The candidate furthest from {@code target}'s requirement — where the twin parks the client
     *  before the real aim, so the parking cannot land near the target by accident. */
    private static BlockPos chooseParkTarget(ServerPlayer body, float[] targetWant) {
        BlockPos best = null;
        float bestScore = -1f;
        for (int[] o : AIM_CANDIDATES) {
            BlockPos cell = body.blockPosition().offset(o[0], o[1], o[2]);
            float[] want = aimFromEyeTo(body, cell);
            float score = Math.min(Math.abs(wrap(want[0] - targetWant[0])),
                    Math.abs(want[1] - targetWant[1]));
            if (score > bestScore) { bestScore = score; best = cell; }
        }
        return best;
    }

    /**
     * Why {@link #chooseAimTarget} picked what it picked — <b>including its input</b>.
     *
     * <p>The chooser reads the body's current rotation, and that rotation is not a constant: earlier
     * actions in a scene change it, and the body is a human player who may be facing anywhere at
     * scene start. So the chosen cell legitimately differs run to run. Recording only the OUTPUT
     * would make a prediction that misses indistinguishable between「选择函数错了」and「输入变了」—
     * and the second is not a defect at all. Must be called BEFORE the aim write, while the input is
     * still the value the chooser actually saw.
     */
    private static String aimChoiceEvidence(ServerPlayer body, BlockPos cell) {
        float[] w = aimFromEyeTo(body, cell);
        float dy = Math.abs(wrap(w[0] - body.getYRot()));
        float dp = Math.abs(w[1] - body.getXRot());
        return "选格时身体朝向 " + deg(body.getYRot()) + " / " + deg(body.getXRot())
                + "（这是选择函数的输入，跟它一起读）→ 选中 " + cell.toShortString()
                + "，要求 " + deg(w[0]) + " / " + deg(w[1])
                + "，需转 yaw " + deg(dy) + "° / pitch " + deg(dp) + "°，较弱分量 "
                + deg(Math.min(dy, dp)) + "°（容差 " + deg(AIM_TOLERANCE_DEG) + "°，"
                + "越大越说明两个分量都真的被验到了）";
    }

    /** How far a target sits from a look direction, per component — printed so a thin margin is
     *  visible instead of having to be recomputed by hand from two other rows. */
    private static String aimGap(float[] look, float[] want) {
        if (look == null) return "unavailable/客户端没答";
        return "yaw 差 " + deg(Math.abs(wrap(look[0] - want[0])))
                + "°，pitch 差 " + deg(Math.abs(look[1] - want[1]))
                + "°（容差 " + deg(AIM_TOLERANCE_DEG) + "°）";
    }

    /**
     * The yaw/pitch that pointing at {@code cell}'s centre REQUIRES, computed from the body's eye
     * position — the independent yardstick this scene's aim criterion is judged against.
     *
     * <p>Same arithmetic both actuators perform ({@code ServerPlayerAvatar.aimAtBlock} and
     * {@code BotInteract.aimAtBlockSnap}), deliberately recomputed here instead of read back from
     * either of them: a criterion whose expected value comes from the thing under test cannot fail.
     *
     * <p>(This block sat above {@code requirementDrift} until 2026-08-23, stacked on top of that
     * method's own javadoc. Java keeps only the LAST block, so it documented nothing and the method
     * it describes had no documentation at all — a comment that is silently discarded is worse than
     * a missing one, because both the writer and the reader believe it is there.)
     */
    private static float[] aimFromEyeTo(ServerPlayer body, BlockPos cell) {
        double dx = (cell.getX() + 0.5) - body.getX();
        double dy = (cell.getY() + 0.5) - body.getEyeY();
        double dz = (cell.getZ() + 0.5) - body.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return new float[]{
                (float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) -Math.toDegrees(Math.atan2(dy, horiz))};
    }

    // ------------------------------------------------------------------ rendering

    private static String render(Integer v) { return v == null ? "unavailable/客户端没答" : String.valueOf(v); }

    private static String renderLook(float[] v) {
        return v == null ? "unavailable/客户端没答" : deg(v[0]) + " / " + deg(v[1]);
    }

    private static String deg(float f) { return String.format(Locale.ROOT, "%.2f", f); }

    private static String agree(int server, Integer client) {
        if (client == null) return "unavailable/客户端没答";
        return server == client ? "一致（都是 " + server + "）"
                : "⚠️ 不一致：服务端 " + server + "，客户端 " + client;
    }

    /** Angular distance between two look directions, wrapped so 359° and 1° read as 2° apart. */
    private static String drift(float yaw0, float pitch0, float yaw1, float pitch1) {
        float dy = Math.abs(wrap(yaw1 - yaw0));
        float dp = Math.abs(pitch1 - pitch0);
        if (dy < 0.01f && dp < 0.01f) return "否（yaw/pitch 都没动）";
        return "是：yaw 差 " + deg(dy) + "°，pitch 差 " + deg(dp) + "°";
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    // ------------------------------------------------------------------ topology

    /** Everyone on this level who is not one of the driver's own minted bodies. */
    private static List<ServerPlayer> humanPlayers(SceneContext ctx) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : ctx.level().players()) {
            if (p instanceof JoinedPlayerBodies.JoinedBody) continue;
            out.add(p);
        }
        return out;
    }

    /** Which topology this run is, asked of the game rather than echoed from a flag. */
    private static String topology(SceneContext ctx) {
        MinecraftServer server = ctx.server();
        boolean dedicated = server == null || server.isDedicatedServer();
        List<ServerPlayer> humans = humanPlayers(ctx);
        String kind = !dedicated ? "integratedServer"
                : humans.isEmpty() ? "dedicatedServer" : "dedicatedServerWithClient";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("真玩家", humans.size());
        m.put("mc.bot.*在本JVM", BotHooks.isAvailable());
        return m.toString();
    }
}

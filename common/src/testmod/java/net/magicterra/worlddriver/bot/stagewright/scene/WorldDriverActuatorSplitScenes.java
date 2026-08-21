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

    /** The slot the server-side write aims at. Deliberately not 0: a body that already sits on slot
     *  0 would make「写成功了」and「本来就是这个值」print identically — 0==0 with extra steps. */
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
            // Say whether it took. A restore nobody verified is a claim, and on this topology the
            // CLIENT owns both of these quantities — so a server-side put-back is exactly as
            // contestable as the write it undoes, which is the scene's own subject.
            ctx.record("cleanup.槽位还原", real.getInventory().selected == selectedWas
                    ? "是（回到 " + selectedWas + "）"
                    : "⚠️ 否：想还原成 " + selectedWas + "，实际 " + real.getInventory().selected);
            ctx.record("cleanup.物品还原", ItemStack.matches(
                    real.getInventory().items.get(TARGET_SLOT), slotWas)
                    ? "是" : "⚠️ 否：槽 " + TARGET_SLOT + " 现在是 "
                            + real.getInventory().items.get(TARGET_SLOT));
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
        BlockPos aimAt = real.blockPosition().offset(7, -3, 5);
        float yawBefore = real.getYRot();
        float pitchBefore = real.getXRot();
        avatar.aimAtBlock(aimAt);
        float serverYawSameTick = real.getYRot();
        float serverPitchSameTick = real.getXRot();
        float[] clientLookSameTick = clientLook();

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
        ctx.record("thread.写入时", Thread.currentThread().getName());
        client.setSelectedSlot(TARGET_SLOT);
        int serverSlot = real.getInventory().selected;
        Integer clientSlot = clientSelectedSlot();
        ctx.record("slot.动作", "setSelectedSlot(" + TARGET_SLOT + ")（不用 holdItem：它按客户端"
                + "背包内容找物品，而这条场景的布景只放进了服务端那份，会红在布景上而不是红在缺陷上）");
        ctx.record("slot.服务端.同tick", serverSlot);
        ctx.record("slot.客户端.同tick", render(clientSlot));
        ctx.record("slot.同tick一致", agree(serverSlot, clientSlot));

        BlockPos aimAt = real.blockPosition().offset(7, -3, 5);
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

            // Same instrument check as the sibling: unreadable client side = measured nothing.
            ctx.check(clientSlotAfter != null && clientLookAfter != null)
                    .as("客户端侧读数拿得到 —— 拿不到的话上面每一行「一致」都是空话")
                    .isTrue();
            // And THE criterion: the slot the client actually holds must be the one that was asked
            // for. Deliberately asserted on the CLIENT's value, not on agreement between the two:
            // agreement would also be satisfied by both sides being wrong together.
            ctx.check(clientSlotAfter != null && clientSlotAfter == TARGET_SLOT)
                    .as("A0 判据：客户端自己的选中槽应为 " + TARGET_SLOT + "，实际 "
                            + render(clientSlotAfter) + "。这一条在缺陷存在时会红 —— "
                            + "修复之前同样的写法测出来客户端停在 0")
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

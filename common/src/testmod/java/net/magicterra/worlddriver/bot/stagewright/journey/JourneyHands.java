package net.magicterra.worlddriver.bot.stagewright.journey;

import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 同一件事让两具身体都做到 —— aiming, holding, and the rows that read both halves at once.
 *
 * <p>On the client topology {@code rig.avatar()} and {@code rig.player()} are two different objects
 * one packet apart, and every helper here exists because a verb applied to one of them only looks
 * identical to a verb that worked. Each method's own note carries the measurement that put it here.
 */
final class JourneyHands {

    private JourneyHands() {
    }

    /**
     * The block a use would hit, clipped exactly the way vanilla's item path clips it.
     *
     * <p>Not {@code Entity.pick}, and the difference cost this rung a run. {@code pick} calls
     * {@code getViewYRot}, which {@code LivingEntity} overrides to return <b>{@code yHeadRot}</b> —
     * and {@code Avatar.aimAtBlock} sets {@code yRot}/{@code xRot} only. So the pick rays down a
     * direction nobody aimed: measured, the body at {@code -4,27,56} aiming at a pool at
     * {@code -6,26,54} produced hits marching away at {@code -4,28,57 → -3,28,57 → -2,27,58}, and
     * the self-driving tunnel dutifully mined eight blocks in the wrong direction.
     *
     * <p>{@code Item.getPlayerPOVHitResult} — what {@code BucketItem} actually uses — reads
     * {@code getXRot()}/{@code getYRot()} directly, so the POUR was never wrong; only the prediction
     * was. This reproduces that clip, which makes it the only thing that can honestly claim to say
     * what the use will see.
     *
     * <p>(That {@code aimAtBlock} leaves the head rotation behind is an engine-side finding in its
     * own right — anything reading head rotation sees a stale direction — and is logged as one
     * rather than fixed from a test.)
     *
     * <p><b>Takes {@code Player}, not {@code ServerPlayer}, on purpose.</b> Every existing caller
     * passes {@code rig.player()} and still compiles, but the widening lets the SAME clip run over
     * {@code rig.avatar().player()} — and comparing the two bodies' rays is the only way to tell a
     * pour whose server ray missed from a pour the server never held the bucket for. {@code Player}
     * is also the widest type that is safe to name here: {@code Avatar.player()} is declared to
     * return it precisely so that headless code never resolves {@code LocalPlayer}.
     */
    static net.minecraft.world.phys.BlockHitResult aimedAt(net.minecraft.world.entity.player.Player fp,
                                                                  double range, boolean hitFluids) {
        net.minecraft.world.phys.Vec3 eye = fp.getEyePosition();
        net.minecraft.world.phys.Vec3 look =
                net.minecraft.world.phys.Vec3.directionFromRotation(fp.getXRot(), fp.getYRot());
        net.minecraft.world.phys.Vec3 end = eye.add(look.scale(range));
        return fp.level().clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                hitFluids ? net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY
                          : net.minecraft.world.level.ClipContext.Fluid.NONE, fp));
    }

    /**
     * The ray that will ACTUALLY FIRE on a client-driven use: <b>the server body's eye, the client
     * body's angles.</b> Neither body's own ray is that line.
     *
     * <p>{@code ServerboundUseItemPacket} carries yRot/xRot and the server adopts them before
     * {@code Item.use} rays (see {@link #aimBoth}) — so the eye is the SERVER's and the angles are the
     * CLIENT's. {@code aimBoth} aims each body from its own position, which is exact for each of them
     * and produces a THIRD line for the use: a gate reading {@code aimedAt(rig.player(), …)} is
     * judging a ray nobody fires.
     *
     * <p>Harmless while the two bodies sit a packet apart and the ray runs through open cell interiors
     * — which is why this went unnoticed until a ray grazed a boundary. <b>Measured, ladder5 rung 12
     * cell 4:</b> the bodies were 0.06 blocks apart and the two rays picked different FACES —
     * client {@code 4,56,21 face=west}, server {@code 4,56,22 face=up} — so the pour that the gate
     * cleared put its lava somewhere the cell check then reported as {@code air}. A small displacement
     * does not imply a small difference: the answer here is discrete.
     */
    static net.minecraft.world.phys.BlockHitResult aimedAtAsUseWill(
            net.minecraft.world.entity.player.Player eyeFrom,
            net.minecraft.world.entity.player.Player anglesFrom, double range, boolean hitFluids) {
        net.minecraft.world.phys.Vec3 eye = eyeFrom.getEyePosition();
        net.minecraft.world.phys.Vec3 look = net.minecraft.world.phys.Vec3
                .directionFromRotation(anglesFrom.getXRot(), anglesFrom.getYRot());
        net.minecraft.world.phys.Vec3 end = eye.add(look.scale(range));
        return eyeFrom.level().clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                hitFluids ? net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY
                          : net.minecraft.world.level.ClipContext.Fluid.NONE, eyeFrom));
    }
    /**
     * Hold still, THEN aim, then act — with nothing between the aim and the act.
     *
     * <p><b>On this topology an aim only lives until the next packet.</b> The ladder's body is an
     * adopted REAL player, and {@code ServerPlayerAvatar.aimAtBlock} writes {@code yRot}/{@code xRot}
     * on the {@code ServerPlayer}. A real player's rotation is client-authoritative: every tick
     * {@code ServerboundMovePlayerPacket} arrives and {@code handleMovePlayer} overwrites it with
     * whatever the client thinks it is looking at. So a server-side aim followed by "settle a couple
     * of ticks, then read" is a write that is guaranteed to be erased before the read.
     *
     * <p>And the client is not neutral about where it looks: {@code WalkerTickAim} pulls pitch back
     * toward the horizon every tick it is not bridging or diving. Measured on run 9's rung 11 — a
     * lava source 2.6–3.3 m away and about two blocks BELOW the eye, which wants roughly 30° of
     * down-pitch, was aimed at with a recorded pitch of {@code 0, 0, 20, 4, 2}: two ticks at exactly
     * the levelled value and three decaying back to it. Seventeen tunnel steps hit twelve
     * non-adjacent cells; a scatter like that is not thick rock, it is an aim that does not hold.
     *
     * <p>The remedy is ordering, not a new verb: two Java statements have no tick between them, so
     * an aim written immediately before the read cannot be overwritten before it is used, whatever
     * the packet order is. The {@code HoldStill} still runs — a still body was always wanted — it
     * just runs BEFORE the aim instead of after it.
     *
     * <p>The dedicated-server topology never needed this and still does not: nothing else writes a
     * fake player's rotation, which is exactly why rungs 11 and 12 pass there and rung 11 fails here
     * on the same code and the same seed.
     */
    static void aimThenAct(JourneyRig rig, BlockPos at, Runnable act) {
        rig.settle(new HoldStill(2), 10, () -> {
            aimBoth(rig, at);
            act.run();
        });
    }

    /**
     * Point BOTH bodies at one cell — the client's, which acts, and the server's, which predicts.
     *
     * <p>On the client topology {@code rig.avatar()} is the CLIENT avatar ({@link JourneyRig#avatar}
     * hands back {@code BotHooks.impl().clientAvatar()}) while {@code rig.player()} is the
     * {@code ServerPlayer}.
     * The first version of {@link #aimThenAct} aimed one body and rayed the other, with a
     * {@code ServerboundMovePlayerPacket} in between; putting the two calls on adjacent lines bought
     * nothing, because <b>adjacency is about ticks and that gap is about objects</b>.
     *
     * <p>Measured, client rehearsal of rung 11: the body sat in cell −4,27,56 for seven consecutive
     * steps and the target never moved, yet the recorded angle changed every step (yaw
     * 142→116→102→98→96→95, pitch 35→7→4→2→2→1). One body and one target can only produce one angle,
     * so the printed angle was never the one just written.
     *
     * <p><b>What the server-side aim is FOR, precisely.</b> It is not for the use — that would be
     * the tidier story and it is false. Disassembled from 1.21.1 (mojang mappings),
     * {@code ServerGamePacketListenerImpl.handleUseItem} runs, in this order:
     * {@code packet.getYRot()} → {@code Mth.wrapDegrees} (72), {@code packet.getXRot()} (81),
     * {@code player.absRotateTo(F,F)} (123), {@code gameMode.useItem(…)} (141) — because
     * {@code ServerboundUseItemPacket} <b>carries yRot/xRot</b> and the server adopts them before
     * using anything. {@code ServerboundUseItemOnPacket} is stronger still: it carries the client's
     * whole {@code BlockHitResult}. So a use aimed only on the client is aimed correctly on both.
     *
     * <p>The aim on the server is owed entirely to the code in THIS repo that rays the server body:
     * {@code aimedAt(rig.player(), …)} prediction gates such as {@code JourneyFill.scoop}'s
     * {@code onTarget}. Those gates decide whether to re-aim, to MINE a supposed blocker, or to walk
     * the body somewhere else — <b>branches that change the world</b> — so a gate reading an unaimed
     * body does not merely mis-report, it acts. Aiming both is exact rather than approximate:
     * {@code aimAtBlock} is a pure function of (body position, target cell) and the two bodies are
     * the same body one packet apart.
     *
     * <p>Consequence worth carrying: a fill/pour site with <b>no</b> server-side prediction gate
     * needs none of this, and reading a failure there as an aiming bug sends the next person to the
     * wrong file. See {@code JourneyFill#scoopWater}, whose only defect was judging early.
     */
    static void aimBoth(JourneyRig rig, BlockPos at) {
        rig.avatar().aimAtBlock(at);
        rig.body().avatar().aimAtBlock(at);
    }

    /**
     * Destroy a colliderless plant standing on an aiming line — <b>the break that needs both halves,
     * each doing the half only it can.</b>
     *
     * <p>Two copies of these lines existed, six lines identical, and <b>both destroyed nothing</b>.
     * They read:
     * <pre>{@code rig.avatar().aimAtBlock(p); var b = rig.body().avatar(); b.breakHold(true); … }</pre>
     * The comment above each said "break server-side, aim client-side", and that reasoning is right
     * for a <i>use</i> and wrong for a <i>break</i>. {@code ServerPlayerAvatar.breakHold(true)} does
     * not take the block as an argument: it destroys its own {@code aimTarget} <b>field</b>, and its
     * very first branch is {@code if (!v || aimTarget == null || …isAir()) return;}. Aiming the
     * CLIENT leaves that field exactly as the last walk left it, so the server took the early return
     * and {@code continueDestroy} — an inherited no-op on a server avatar — was the whole attempt.
     *
     * <p><b>Measured, rehearsal of rung 11 (2026-08-23, integrated Fabric, seed 5471).</b> The cast
     * at {@code -4,62,54} was blocked by {@code short_grass} at {@code -4,63,55}; after the swing and
     * a 12-tick settle the evidence read
     * {@code cast.cleared.-4, 63, 55 = Block{minecraft:short_grass}} — the same cell, the same block,
     * the same coordinates ladder-8 died on. Routing the break to the server avatar (which was the
     * previous fix) changed nothing, because the aim had never followed it there.
     *
     * <p>So: aim both, swing on the client, destroy on the server.
     * <ul>
     *   <li>The <b>client</b> half is the only one a human watching the window can see — a driven
     *       client's {@code keyAttack} is ignored (vanilla gates it on {@code isMouseGrabbed}), but
     *       {@code continueDestroy} drives {@code gameMode.continueDestroyBlock} directly and swings
     *       the arm. It runs first because an instant-break block may simply die here, which is the
     *       faithful path; on a headless body it is a no-op and costs nothing.</li>
     *   <li>The <b>server</b> half is authoritative and is what actually clears the line. It still
     *       answers to {@code canBreakFromHere}, so a plant out of reach is refused rather than
     *       teleport-broken — judge it by re-reading the cell, never by these calls returning.</li>
     * </ul>
     *
     * <p>Solid blockers do not come here: they go through {@code rig.mineBlock}, where the drop is
     * part of the point. This exists because {@code MineProcess} will not remove a colliderless
     * plant at all — measured twice, two clearings in a row left the same seagrass standing.
     */
    static void swingOffPlant(JourneyRig rig, BlockPos plant) {
        aimBoth(rig, plant);
        rig.avatar().continueDestroy(plant);
        var breaker = rig.body().avatar();
        breaker.breakHold(true);
        breaker.breakHold(false);
    }

    /**
     * Put a specific item in the main hand, and record what actually ended up there.
     *
     * <p>{@code useItemInHand} uses the SELECTED hotbar slot, not "the bucket in the bag". By the
     * time the ladder reaches the lava the body has mined a 36-block shaft, so the selected slot
     * holds a pickaxe — and a pickaxe's {@code use} returns {@code PASS} and changes nothing, which
     * is byte-identical to a bucket whose ray missed. Run 15 read {@code fill.result=PASS,
     * lava_bucket=0, fill.sourceAfter=lava} while the aim was dead on the source at 2.5 m, and the
     * only way to tell those two apart afterwards is this evidence line.
     *
     * <p><b>The row it writes is one packet early on the client topology, and that is display only.</b>
     * {@code holdItem} tells the CLIENT to select the slot; {@code rig.player().getMainHandItem()}
     * reads the SERVER. So a correct run can still print {@code .hand=minecraft:stone_pickaxe} — rung
     * 12 did, beside a {@code SUCCESS} only a bucket can return. Do not follow that row into a
     * packet-race theory: {@code MultiPlayerGameMode.useItem} calls {@code ensureHasSentCarriedItem()}
     * at bytecode offset 15, ahead of the {@code startPrediction} that sends
     * {@code ServerboundUseItemPacket}, so the slot change cannot arrive after the use. Write the same
     * key again after a settle if you want the reading rather than the row — StageWright's clash guard
     * keeps the second value only when it differs, which makes {@code .hand#2} a staleness instrument.
     *
     * <p><b>"Display only" holds for the hotbar branch and NOT for the bag branch.</b> The paragraph
     * above reasons about {@code ServerboundSetCarriedItemPacket}, which is what {@code ensureHolding}
     * sends when the item is already in the hotbar. When it is only in the bag it instead sends a
     * {@code ClickType.SWAP} container click, and that click is why the two sides disagree at this
     * instant — see {@link #holdBoth}, which stops writing the server half there because applying the
     * same swap twice is the identity. A disagreeing row on that branch is neither a race nor display:
     * it is one packet of latency on a swap that will land ahead of the use. Which branch ran is not
     * guessable from this row — read {@code holdBoth.<item>.inFlight}, and read
     * {@link #handTrace} for what the server actually held at handling time.
     */
    static boolean holdForUse(JourneyRig rig, net.minecraft.world.item.Item item, String what) {
        boolean ok = holdBoth(rig, item);
        rig.evidence(what + ".hand", (ok ? "" : "拿不到 " + BuiltInRegistries.ITEM.getKey(item) + "，手上是 ")
                + BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem())
                + actingHand(rig)
                + (ok ? "" : "；" + bucketStock(rig)));
        return ok;
    }

    /**
     * Put the item in BOTH bodies' hands — the twin of {@link #aimBoth}, one field over.
     *
     * <p><b>Why one call is not enough, measured.</b> Rung 12's client rehearsal spent one use
     * successfully and then every later use did nothing, silently. The server's ray was never at
     * fault: {@code water0.picks.3 = 5,57,19 stone face=west → 落进 4,57,19}, dead on the target
     * cell, and {@code lava0.aimsAt#3 = -9,63,18 minecraft:lava 源块=true} at 3.5 m. What the server
     * was holding was: {@code stone_pickaxe}, while the client held the bucket. A pickaxe's
     * {@code use} returns {@code PASS} — no exception, no chat, no sound, no log line — which is
     * byte-identical to every other way a use can do nothing.
     *
     * <p><b>The drift has two authors and neither can see the other.</b>
     * {@code ServerPlayerAvatar.selectTool} (the engine's {@code MineProcess} path, which this
     * ladder still steers for {@code d.mine}) writes {@code inv.selected} on the {@code ServerPlayer}
     * and deliberately sends no packet — its comment says "this body's connection swallows them
     * anyway", true of a headless {@code FakePlayer} and <b>false here</b>, where the body is an
     * adopted player with a live client. Then {@code BotInteract.ensureHolding} opens with
     * {@code if (inv.getSelected().getItem() == item) return true;} — correct for a real player,
     * whose selected slot only ever moves from the client, and wrong for a body a second helm
     * steers. So a mine between two uses moves the server's hand, the next hold sees the CLIENT's
     * hand already right, sends nothing, and the server uses the wrong item.
     *
     * <p>Asked by ITEM on each side rather than by slot index, deliberately: each body then resolves
     * the slot within its own inventory, which stays correct even after the two have diverged.
     * {@code ServerPlayerAvatar.holdItem}'s bag branch swaps stacks server-side, and
     * {@code broadcastChanges} pushes that to the client on the next tick — the corrective
     * direction.
     *
     * <p>Returns the CLIENT's answer, because the client is the body that runs {@code useItem} and
     * predicts. The server's answer is recorded rather than returned: a false there with a true here
     * is the diverged-inventory case, and it must not silently cancel a use the caller can still
     * make land.
     */
    static boolean holdBoth(JourneyRig rig, net.minecraft.world.item.Item item) {
        // WHICH BRANCH THE CLIENT IS ABOUT TO TAKE, asked before it takes it. Calling both halves is
        // right for two of `BotInteract.ensureHolding`'s three branches and wrong for the third, and
        // the boolean it returns cannot tell them apart:
        //
        //   already held  → the client sends NOTHING, so only the server half can correct a server
        //                   whose `selected` was moved by `ServerPlayerAvatar.selectTool` (which
        //                   also sends nothing). This is the case this method was written for; the
        //                   server half MUST still run.
        //   hotbar        → client writes `inv.selected = s` and sends SetCarriedItem; the server
        //                   half writes the same index. Selecting slot N twice is still slot N —
        //                   IDEMPOTENT, so running both is harmless.
        //   bag swap      → client swaps items[ms]↔items[hb] AND sends a ClickType.SWAP container
        //                   click (`BotInteract.swapFromMainInv`); the server half swaps the
        //                   same pair directly and deliberately sends nothing
        //                   (`ServerPlayerAvatar.holdItem`). The server therefore performs
        //                   that swap TWICE — once here, once when the click lands — and a swap is
        //                   an INVOLUTION. Twice is the identity, and the hand goes back.
        //
        // Measured on ladder j48's rung 12: six pours and six casts all read 槽 0 and all worked;
        // the one pour that came after a ramp (a ramp holds cobblestone, which pushes the bucket out
        // of the hotbar) read 槽 3 and did nothing. Every row agreed at send time —
        // `water6.again.hand` and `water6.atUse` showed the bucket on BOTH bodies — because the
        // click had not landed. It landed before the use packet, on the same ordered connection,
        // and `handleUseItem` then read a stone_pickaxe: PASS, nothing consumed, nothing logged.
        // `water6.spent = water_bucket 1→1`, then `lava6.hand = 槽 3 = stone_pickaxe；桶存量 空=0 水=1`.
        //
        // So the bag branch gets ONE author, and it is the client: its click already fixes the
        // server, and it arrives BEFORE the use — the very ordering that breaks this today is what
        // makes a single author correct. The test mirrors `ensureHolding`'s own condition
        // (`hotbarSlotOf < 0`) rather than guessing from the return value.
        var acting = rig.avatar().player();
        boolean oneBody = acting == rig.player();
        boolean wouldSwapFromBag = !oneBody && acting != null
                && acting.getMainHandItem().getItem() != item
                && hotbarSlotOf(acting, item) < 0;

        boolean client = rig.avatar().holdItem(item);
        boolean server;
        if (client && wouldSwapFromBag) {
            // Read, never write. False here is the click in flight, NOT a diverged bag — and saying
            // so matters, because the old row's wording ("两份背包已经分叉") would now fire on every
            // single bag-branch hold and read as a defect report.
            server = rig.player().getMainHandItem().getItem() == item;
            if (!server) {
                rig.evidence("holdBoth." + BuiltInRegistries.ITEM.getKey(item).getPath() + ".inFlight",
                        "客户端走的是背包交换分支（快捷栏里没有 "
                                + BuiltInRegistries.ITEM.getKey(item) + "），服务端这一刻手上还是 "
                                + BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem())
                                + " —— 这是那个 SWAP 点击包还没到，不是背包分叉。"
                                + "服务端这一半故意不动手：交换两次等于没换，"
                                + "而点击包排在 use 包前面，会把服务端改对。" + stockOnBoth(rig, item));
            }
            return client;
        }
        server = rig.body().avatar().holdItem(item);
        if (client != server) {
            rig.evidence("holdBoth." + BuiltInRegistries.ITEM.getKey(item).getPath(),
                    "两具身体对同一件物品给了不同答案：客户端 " + client + "，服务端 " + server
                            + " —— 两份背包已经分叉，" + stockOnBoth(rig, item));
        }
        return client;
    }

    /** {@code BotInteract.hotbarSlotOf} asked of a {@link net.minecraft.world.entity.player.Player}
     *  rather than a {@code LocalPlayer}, so {@link #holdBoth} can ask it of the acting body without
     *  a client-only type in a signature this common source set compiles for both sides. Same nine
     *  slots, same order, same {@code -1}. */
    private static int hotbarSlotOf(net.minecraft.world.entity.player.Player p,
                                    net.minecraft.world.item.Item item) {
        var items = p.getInventory().items;
        for (int s = 0; s < 9; s++) if (items.get(s).getItem() == item) return s;
        return -1;
    }

    /**
     * How many of one item each body thinks it has — the number that decides which way a
     * {@code holdBoth} disagreement should be read.
     *
     * <p>Deliberately not the bucket triple: this is called for cobblestone and ender eyes as often
     * as for buckets now, and three bucket counts beside a failed cobblestone hold are noise that
     * looks like data. {@code rig.carrying} reads the SERVER, so the client half has to be counted
     * here — and the pair is the whole point, because "the client has it and the server does not" and
     * "neither has it" want completely different next steps.
     */
    private static String stockOnBoth(JourneyRig rig, net.minecraft.world.item.Item item) {
        var acting = rig.avatar().player();
        int onClient = 0;
        if (acting != null) {
            for (var stack : acting.getInventory().items) {
                if (stack.getItem() == item) onClient += stack.getCount();
            }
        }
        return BuiltInRegistries.ITEM.getKey(item) + " 客户端 ×" + onClient
                + "，服务端 ×" + rig.carrying(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    /**
     * The hand that will actually be used, beside the hand this row has always printed.
     *
     * <p>Every reading in this file came from {@code rig.player()} — the ServerPlayer — while the
     * use runs on the client. That is one packet of lag on a good day, and this row said so. What
     * it could never say is the thing a reader actually needs when a use does nothing: <b>whether
     * the client is holding the right item at all</b>. {@code holdItem} returning true is not that
     * evidence; it is {@code BotInteract.ensureHolding}'s opinion, and its main-inventory branch
     * goes through a swap CLICK whose effect is not visible in the same statement.
     *
     * <p>Rung 12 spent three rounds without it. The pour reported
     * {@code water0.hand=minecraft:stone_pickaxe} (server, stale), {@code water0.result=SUCCESS}
     * (client, predicted) and {@code water0.spent=water_bucket 1→1} (server, after a round trip) —
     * three readings from two bodies and two moments, and no two of them describe the same thing.
     * Printing both hands in one row costs nothing and collapses that.
     *
     * <p>Identical on the dedicated-server helm, where both calls reach the same object — the row
     * degrades to a repetition rather than a lie.
     */
    private static String actingHand(JourneyRig rig) {
        var acting = rig.avatar().player();
        if (acting == null) return "";
        // THE SLOT NUMBER, not just the item — because two different failures print the same item
        // pair and want opposite fixes. `BotInteract.ensureHolding` has two branches: the hotbar one
        // sets `inv.selected` and sends ServerboundSetCarriedItemPacket, while the main-inventory
        // one performs a SWAP **click** through the container menu.
        //
        // ⚠️ BUT THE SERVER HALF OF THIS ROW CANNOT DECIDE BETWEEN THEM, because of WHEN it is read.
        // `holdItem` only queues the packet; this line runs in the same client tick, so the server
        // number is the PRE-selection one **whenever the hold did anything at all**. "Different
        // slots" here is the healthy reading, not evidence of a lost selection — and the first
        // version of this comment said the opposite, which would have sent the next round chasing
        // a selection that was merely in flight. `waterFill.hand#2 = minecraft:bucket` (the clash
        // guard's second write, taken later) is the proof: the server DID catch up.
        //
        // The matrix that comment wanted lives in `handsAtUse`, which reads both bodies at the
        // moment of the use, after the settle. Read that row, not this one.
        return "（真正要动手的那只手：槽 " + acting.getInventory().selected + " = "
                + BuiltInRegistries.ITEM.getKey(acting.getMainHandItem().getItem())
                + "；服务端槽 " + rig.player().getInventory().selected + "，换手包还没往返，这个数按定义是旧的）";
    }

    /**
     * Is the body that will actually run the use holding {@code item} RIGHT NOW?
     *
     * <p>The one question {@link #holdForUse} cannot answer, because it is asked at the wrong moment.
     * {@code holdForUse} runs where the caller decides to use something; the use runs after the aim,
     * after the plant clearing, after the last settle — and ladder-11's twelfth rung showed those are
     * far enough apart for the hold to come undone on BOTH bodies at once.
     *
     * <p><b>How a hold comes undone, measured.</b> Cell six read {@code cast6.hand =
     * minecraft:lava_bucket} with {@code 真正要动手的那只手：槽 4 = minecraft:lava_bucket} — client and
     * server agreeing — and then, ten ticks later, {@code cast6.atUse = 客户端 槽 4 = minecraft:dirt}
     * and {@code 服务端 槽 4 = minecraft:dirt}. Cells zero through five never did. What is different
     * about six is that six is the first cell whose pour needed a RAISE, and the tower holds dirt:
     * that hold pushed the bucket out of the hotbar, so the next hold went down
     * {@code BotInteract.ensureHolding}'s main-inventory branch instead of its hotbar branch — a swap
     * <b>click</b> on the client and a stack swap on the server, two authors of one slot, and swapping
     * the same pair twice is the identity. The second one landed during the settle.
     *
     * <p>So this is not a lag reading and re-reading it later does not fix it: the slot really does
     * hold dirt by then. Ask here, right before the use, and re-hold — see {@link #holdForUse}'s
     * caller in {@code JourneyPortalRung.placeFluid} for the gate that follows.
     *
     * <p>Reads the ACTING body ({@code rig.avatar()}), because that is the one whose
     * {@code useItemInHand} runs; on a headless helm the two are the same object and this degrades to
     * the server reading.
     */
    static boolean actingHolds(JourneyRig rig, net.minecraft.world.item.Item item) {
        var acting = rig.avatar().player();
        return acting != null && acting.getMainHandItem().getItem() == item;
    }

    /**
     * Re-assert the hold immediately before a use, and say whether it took.
     *
     * <p>The gap {@link #actingHolds} documents is not one site's mistake — it is what
     * {@link #aimThenAct} does for a living: settle two ticks, aim, act. Every caller that holds
     * something and then goes through an aim has ten ticks between the hold and the use, and rung
     * 11's {@code pourInto} has exactly that shape over a bucket its own comment calls「一桶岩浆
     * 只有一次机会」. It has never been bitten because nothing there holds dirt in between; that is a
     * property of the neighbouring code, not a guarantee.
     *
     * <p>Three states, on purpose, and the caller must keep them apart:
     * <ul>
     *   <li>no {@code .handSlipped} row ⇒ the hold never came undone;</li>
     *   <li>a row and then {@code true} ⇒ it came undone and the second hold fixed it;</li>
     *   <li>a row and then {@code false} ⇒ refuse the use. A use with the wrong thing in hand
     *       returns {@code PASS} and changes nothing, which is byte-identical to a ray that
     *       missed — and the failure then surfaces legs later, somewhere else.</li>
     * </ul>
     */
    static boolean regripBeforeUse(JourneyRig rig, net.minecraft.world.item.Item item, String tag) {
        if (actingHolds(rig, item)) return true;
        rig.evidence(tag + ".handSlipped", "动手前手上不是 "
                + BuiltInRegistries.ITEM.getKey(item) + " 了：" + heldOnBoth(rig)
                + " —— 上一次 hold 之后隔了一次落定，重新拿一次");
        holdForUse(rig, item, tag + ".again");
        return actingHolds(rig, item);
    }

    /** What both bodies hold, for a failure message that has to name the thing that went wrong. */
    static String heldOnBoth(JourneyRig rig) {
        var acting = rig.avatar().player();
        return "客户端 " + (acting == null ? "没有身体"
                        : "槽 " + acting.getInventory().selected + " = "
                          + BuiltInRegistries.ITEM.getKey(acting.getMainHandItem().getItem()))
                + "，服务端 槽 " + rig.player().getInventory().selected + " = "
                + BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem());
    }

    /**
     * Both bodies, at the instant of the use — the reading every earlier row was too early to take.
     *
     * <p>Rung 12's pour has three rows and no two of them describe the same thing:
     * {@code .hand} is the SERVER before the selection packet has flown, {@code .result} is the
     * CLIENT's own return value (and {@code sidedSuccess} makes {@code SUCCESS} mean nothing more
     * than "the client ran it"), and {@code .spent} is the SERVER after a round trip. Three
     * readings, two bodies, three moments. This row collapses them: one line, one moment, both
     * bodies, everything the use consumes.
     *
     * <p><b>Why the ray is here and not left to be inferred.</b> A pour that does nothing has two
     * causes that every other row prints identically. Either the server is holding the wrong item —
     * a pickaxe's {@code use} is {@code PASS} — or the server holds the bucket and its
     * {@code getPlayerPOVHitResult} came back {@code MISS}, which also returns {@code PASS}.
     * Vanilla logs neither: {@code handleUseItem} has no refusal branch that speaks, and the range
     * cap inside the clip ({@code blockInteractionRange()}, 4.5) fails silently. So the ray has to
     * be printed, and printed for BOTH bodies, because they can disagree — the use packet carries
     * yRot/xRot so the ANGLES always agree, but the eye POSITIONS are one movement packet apart.
     *
     * <p>Both fluid modes, deliberately, so no caller has to pass a flag it can get backwards: an
     * empty bucket clips {@code SOURCE_ONLY} and a full one clips {@code NONE}, and the two answers
     * differ exactly where this rung lives — over water and lava.
     */
    static void handsAtUse(JourneyRig rig, String tag) {
        var client = rig.avatar().player();
        var server = rig.player();
        rig.evidence(tag + ".atUse", client == server
                ? "两半是同一个对象（无客户端拓扑）：" + oneBodyAtUse(server)
                : "客户端 " + oneBodyAtUse(client) + "\n            服务端 " + oneBodyAtUse(server));
    }

    private static String oneBodyAtUse(net.minecraft.world.entity.player.Player p) {
        if (p == null) return "没有身体";
        return String.format(java.util.Locale.ROOT,
                "槽 %d = %s；眼睛 %.2f/%.2f/%.2f 朝 yaw=%.2f pitch=%.2f；满桶线 %s；空桶线 %s",
                p.getInventory().selected,
                BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()),
                p.getEyePosition().x, p.getEyePosition().y, p.getEyePosition().z,
                p.getYRot(), p.getXRot(),
                describeRay(p, false), describeRay(p, true));
    }

    /**
     * How many consecutive ticks {@link #handTrace} is worth taking after a use.
     *
     * <p>Six, not the five the question was first phrased with, and the extra one is not slack.
     * {@code t0} lands in the SAME server tick as the use (see {@link #handTrace}), so five rows
     * would cover only {@code use+0 … use+4} — and the reading that says「the other author is merely
     * slower」is a flip on {@code use+5}. A window whose last tick is the one an inconvenient answer
     * lives on cannot return that answer.
     */
    static final int TRACE_TICKS = 6;

    /**
     * ONE tick's hand on BOTH bodies, written as <b>two independent rows</b>.
     *
     * <p><b>The question it exists to answer.</b> {@code ServerboundUseItemPacket} carries
     * hand/sequence/yaw/pitch and <b>no item</b>: {@code handleUseItem} runs
     * {@code this.player.getItemInHand(hand)}, i.e. whatever the SERVER's {@code inventory.selected}
     * points at <i>when it processes the packet</i>. A second author that moves the server's
     * selection between the send and the handling makes the server use a different object — and
     * cobblestone is a {@code BlockItem}, whose {@code use} returns {@code PASS}: nothing consumed,
     * nothing placed, nothing logged. Every other row this rung writes is one snapshot of one
     * moment, so none of them can see a flip. This one asks the same question on consecutive ticks.
     *
     * <p><b>Two rows, never one.</b> {@link #handsAtUse} joins the halves into a single string,
     * which permanently destroys the ability to ask whether they were read at the same instant.
     * Each half here is its own key with its own thread name and its own {@code gameTime}, so
     *「same moment?」stays a question the output can answer.
     *
     * <p><b>The sampling thread is recorded because the thread IS the finding.</b>
     * {@link JourneyRig#avatar()}'s javadoc already required it — <i>"judge this path only with the
     * calling thread recorded beside the numbers"</i> — and not one row in this suite had it. When
     * driven from a {@link JourneyRig.TickWatcher} the thread is the SERVER thread, by this chain:
     * {@code ServerTickEvents.END_SERVER_TICK} (Fabric) / {@code ServerTickEvent.Post} (NeoForge)
     * → {@code StageWrightCommon.onServerTick} → {@code StageWrightHarness.tick} →
     * {@code SceneContext.advance} → the step's condition → {@link JourneyRig#await} 's predicate →
     * the watcher. So the SERVER row is a same-thread reading of the very object
     * {@code handleUseItem} consults, and the CLIENT row is a cross-thread snapshot — which is
     * exactly the asymmetry {@code cast.atUse} hid by concatenating them.
     *
     * <p><b>{@code gameTime} is printed rather than the tick index alone</b> so that「six
     * consecutive server ticks」is measured instead of argued: two rows sharing a {@code gameTime}
     * were sampled in one tick no matter what the index says, and that is how a caller finds out
     * that {@code t0} coincides with the use rather than following it.
     *
     * <p>Read-only and total: a null half prints「没有身体」rather than throwing, because an
     * instrument that can end the leg it is measuring is not an instrument.
     */
    static void handTrace(JourneyRig rig, String tag, int tick) {
        String thread = Thread.currentThread().getName();
        var client = rig.avatar().player();
        var server = rig.player();
        String key = tag + ".handTrace.t" + tick;
        rig.evidence(key + ".client", oneHandAt(client, tick, thread,
                client == server
                        ? "客户端半（无客户端拓扑，与服务端是同一个对象）"
                        : "客户端 LocalPlayer（拥有它的是客户端线程，本行由上面那个线程读 ⇒ 跨线程快照）"));
        rig.evidence(key + ".server", oneHandAt(server, tick, thread,
                "服务端 ServerPlayer（handleUseItem 读的就是这一份）"));
    }

    private static String oneHandAt(net.minecraft.world.entity.player.Player p, int tick,
                                    String thread, String whose) {
        if (p == null) {
            return "tick=" + tick + " 取数线程=" + thread + "；" + whose + "：没有身体";
        }
        return String.format(java.util.Locale.ROOT,
                "tick=%d gameTime=%d 取数线程=%s；%s：槽 %d = %s ×%d",
                tick, p.level().getGameTime(), thread, whose,
                p.getInventory().selected,
                BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()),
                p.getMainHandItem().getCount());
    }

    private static String describeRay(net.minecraft.world.entity.player.Player p, boolean hitFluids) {
        var hit = aimedAt(p, p.blockInteractionRange(), hitFluids);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return String.format(java.util.Locale.ROOT,
                    "MISS（%.2f 格内什么都没挡住 —— vanilla 到这里就 return PASS，一个字都不打印）",
                    p.blockInteractionRange());
        }
        return hit.getBlockPos().toShortString() + " "
                + BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(hit.getBlockPos()).getBlock())
                + " 面=" + hit.getDirection()
                + String.format(java.util.Locale.ROOT, "（%.2f 格）",
                        Math.sqrt(hit.getLocation().distanceToSqr(p.getEyePosition())));
    }

    /**
     * Where the three bucket states stand — printed only when a hold FAILED.
     *
     * <p>A bucket is not an item this ladder owns one of; it is <b>one object in three states</b>
     * ({@code bucket} / {@code water_bucket} / {@code lava_bucket}), and rung 12 carries exactly one
     * of it. So {@code holdItem(Items.BUCKET)} returning false has two completely different
     * meanings — the bucket was lost, or the bucket is FULL — and the row it used to write
     * ({@code 拿不到 minecraft:bucket，手上是 minecraft:stone_pickaxe}) could not tell them apart.
     * Rung 12's client rehearsal died on exactly that row with an aim that was beyond reproach:
     * {@code 射线停在 -10,63,12 Block{minecraft:lava}} at 3.5 m. Nothing about the fill was wrong;
     * the question was upstream and unasked.
     *
     * <p>Only on failure, deliberately. On the success path these three numbers are noise in every
     * evidence map the ladder writes, and this rung already spends its budget of rows.
     */
    static String bucketStock(JourneyRig rig) {
        return String.format(java.util.Locale.ROOT, "桶存量 空=%d 水=%d 岩浆=%d",
                rig.carrying("minecraft:bucket"),
                rig.carrying("minecraft:water_bucket"),
                rig.carrying("minecraft:lava_bucket"));
    }

    /**
     * Open one named cell with an aimed swing — the same three calls
     * {@code JourneyRig.breakItWhereItStands} makes, which is what a rung's {@code mineCellOrGiveUp}
     * tries before it routes anywhere.
     *
     * <p>Judged on the WORLD rather than on the call, because {@code breakHold} returns nothing and
     * refuses silently. That is what makes this a scene-side twin of the production verb rather than
     * a call to it: an arena that wants to know whether a cell opened must ask the level, and a
     * scene that asked {@code breakItWhereItStands} would be asking the subject to grade itself.
     *
     * <p>Two arena files each had a byte-identical copy of the twin. One independent oracle is the
     * point; two are just two, and the second is free to drift into agreeing with the subject.
     */
    static boolean swing(ServerWorldDriver driver, BlockPos cell) {
        ServerPlayerAvatar av = driver.avatar();
        if (!av.canBreak(cell)) return false;
        av.selectTool(cell);
        av.aimAtBlock(cell);
        av.breakHold(true);
        av.breakHold(false);
        return !driver.fakePlayer().level().getBlockState(cell).blocksMotion();
    }
}

package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.magicterra.worlddriver.api.methods.interact.InteractApi;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * The run's bot's hands: aiming, holding and using, through the {@code mc.bot.*} hand verbs any client calls,
 * and the rows that read the hand at the moment it matters.
 *
 * <p><b>Two helms, one hand.</b> A roster bot's verbs answer within the call, on the server, against the very
 * player every reading here takes. self's answer on the client thread a tick or two later, and the server's copy
 * of the real player — what {@code rig.player()} is, and what vanilla's packet handlers consult — follows the
 * client by a packet. So on self a verb's answer is recorded when it lands ({@link JourneyRig#recordWhenLanded}),
 * and a verb whose answer a rung branches on is predicted from the server's copy; each such method says which.
 * The client's own copy of the player is not readable from a scene, so the rows that used to print it beside the
 * server's now print the server's alone and say so.
 */
final class JourneyHands {

    private JourneyHands() {
    }

    private static boolean onSelf(JourneyRig rig) { return rig.serverBotOrNull() == null; }

    /** What the rows print in place of the client's own copy of self, which a scene cannot read. */
    private static final String CLIENT_UNREADABLE = "the client's own copy of the player is not readable from a scene";

    private static String id(net.minecraft.world.item.Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /**
     * The block a use would hit, clipped exactly the way vanilla's item path clips it.
     *
     * <p>Not {@code Entity.pick}, and the difference cost this rung a run. {@code pick} calls
     * {@code getViewYRot}, which {@code LivingEntity} overrides to return <b>{@code yHeadRot}</b> —
     * and an aim sets {@code yRot}/{@code xRot} only. So the pick rays down a direction nobody aimed:
     * measured, the bot at {@code -4,27,56} aiming at a pool at {@code -6,26,54} produced hits marching
     * away at {@code -4,28,57 → -3,28,57 → -2,27,58}, and the self-driving tunnel dutifully mined eight
     * blocks in the wrong direction.
     *
     * <p>{@code Item.getPlayerPOVHitResult} — what {@code BucketItem} actually uses — reads
     * {@code getXRot()}/{@code getYRot()} directly, so the POUR was never wrong; only the prediction
     * was. This reproduces that clip, which makes it the only thing that can honestly claim to say
     * what the use will see.
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
     * Hold still, THEN aim, then act — with nothing between the aim and the act.
     *
     * <p><b>On the integrated topology an aim only lives until the next packet.</b> The ladder's bot is
     * the REAL player, whose rotation is client-authoritative: every tick
     * {@code ServerboundMovePlayerPacket} arrives and {@code handleMovePlayer} overwrites the server's copy
     * with whatever the client thinks it is looking at. So a server-side aim followed by "settle a couple
     * of ticks, then read" is a write that is guaranteed to be erased before the read.
     *
     * <p>And the client is not neutral about where it looks: {@code AimPhase} pulls pitch back
     * toward the horizon every tick it is not bridging or diving. Measured on run 9's rung 11 — a
     * lava source 2.6–3.3 m away and about two blocks BELOW the eye, which wants roughly 30° of
     * down-pitch, was aimed at with a recorded pitch of {@code 0, 0, 20, 4, 2}: two ticks at exactly
     * the levelled value and three decaying back to it.
     *
     * <p>The remedy is ordering, not a new verb: two Java statements have no tick between them, so
     * an aim written immediately before the act cannot be overwritten before it is used. The hold
     * still runs — a still bot was always wanted — it just runs BEFORE the aim instead of after it.
     * The dedicated-server topology never needed this and still does not: nothing else writes a
     * roster bot's rotation.
     */
    static void aimThenAct(JourneyRig rig, BlockPos at, Runnable act) {
        rig.hold(2, 10, () -> {
            aimBoth(rig, at);
            act.run();
        });
    }

    /**
     * Point the bot at one cell — and, on self, the server's copy of it too, which the gates here predict with.
     *
     * <p>{@code mc.bot.lookAt} turns the bot that acts: a roster bot within the call, self on the client
     * thread a tick later. A use on self carries the client's own angles ({@code ServerboundUseItemPacket}
     * carries yRot/xRot, and {@code handleUseItem} adopts them before using anything), so a use aimed on the
     * client is aimed correctly on both.
     *
     * <p><b>What the server-side aim on self is FOR, precisely.</b> Not the use. It is owed entirely to the
     * code in THIS suite that rays the server's player: {@code aimedAt(rig.player(), …)} prediction gates such
     * as {@code JourneyFill.scoop}'s {@code onTarget}. Those gates decide whether to re-aim, to MINE a supposed
     * blocker, or to walk the bot somewhere else — <b>branches that change the world</b> — so a gate reading an
     * unaimed player does not merely mis-report, it acts. The angles written are the pure function of (eye,
     * target cell centre) the verb turns the client to, so the two copies agree but for the packet of position
     * between them.
     */
    static void aimBoth(JourneyRig rig, BlockPos at) {
        InteractApi.of(Calls.api()).lookAt(new InteractContracts.LookParams(rig.bot(), Optional.of(Targets.pos(at)),
                Optional.empty(), Optional.empty(), 0));
        if (onSelf(rig)) turnServerCopy(rig.player(), at);
    }

    /** Point a roster bot at one cell's centre with {@code mc.bot.lookAt}; it turns within the call. */
    static void aim(SceneBot bot, BlockPos at) {
        InteractApi.of(Calls.api()).lookAt(new InteractContracts.LookParams(bot.ref(), Optional.of(Targets.pos(at)),
                Optional.empty(), Optional.empty(), 0));
    }

    /** The angles {@code mc.bot.lookAt} turns a player to for {@code cell}'s centre, written on {@code p}. */
    private static void turnServerCopy(net.minecraft.server.level.ServerPlayer p, BlockPos cell) {
        double dx = (cell.getX() + 0.5) - p.getX();
        double dy = (cell.getY() + 0.5) - p.getEyeY();
        double dz = (cell.getZ() + 0.5) - p.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, horiz)));
    }

    /**
     * Destroy a colliderless plant standing on an aiming line, then continue.
     *
     * <p>A roster bot aims and swings within the call, the held dig landing at once with {@code faithfulBreak}
     * off. self has no such swing to give: its break is the client's own, a dig held over the ticks the block
     * takes, so on self this is the one-cell mine, started in reach. Judge either by re-reading the cell, never
     * by these calls returning: a plant out of reach is refused rather than teleport-broken.
     *
     * <p>Solid blockers do not come here: they go through {@code rig.mineBlock}, where the drop is part of the
     * point. This exists because the searching mine will not remove a colliderless plant at all — measured twice,
     * two clearings in a row left the same seagrass standing.
     */
    static void swingOffPlant(JourneyRig rig, BlockPos plant, Runnable then) {
        SceneBot bot = rig.serverBotOrNull();
        if (bot == null) {
            rig.settle(JourneyTasks.mineCell(plant), 40, then);
            return;
        }
        var cell = Targets.pos(plant);
        bot.press(bot.keys().aimAt(cell).attack(true));
        bot.press(bot.keys().attack(false));
        then.run();
    }

    /**
     * Put a specific item in the main hand, and record what actually ended up there.
     *
     * <p>A use uses the SELECTED hotbar slot, not "the bucket in the bag". By the time the ladder reaches the
     * lava the bot has mined a 36-block shaft, so the selected slot holds a pickaxe — and a pickaxe's {@code use}
     * returns {@code PASS} and changes nothing, which is byte-identical to a bucket whose ray missed. Run 15 read
     * {@code fill.result=PASS, lava_bucket=0, fill.sourceAfter=lava} while the aim was dead on the source at
     * 2.5 m, and the only way to tell those two apart afterwards is this evidence line.
     *
     * <p><b>On self the row it writes is one packet early, and that is display only.</b> {@code holdItem} tells
     * the CLIENT to select the slot; {@code rig.player().getMainHandItem()} reads the SERVER. So a correct run can
     * still print {@code .hand=minecraft:stone_pickaxe} — rung 12 did, beside a {@code SUCCESS} only a bucket can
     * return. {@code MultiPlayerGameMode.useItem} calls {@code ensureHasSentCarriedItem()} ahead of the
     * {@code startPrediction} that sends {@code ServerboundUseItemPacket}, so the slot change cannot arrive after
     * the use. Write the same key again after a settle if you want the reading rather than the row — the clash
     * guard keeps the second value only when it differs, which makes {@code .hand#2} a staleness instrument.
     */
    static boolean holdForUse(JourneyRig rig, net.minecraft.world.item.Item item, String what) {
        boolean ok = holdBoth(rig, item);
        rig.evidence(what + ".hand", (ok ? "" : "cannot hold " + id(item) + "; main hand holds ")
                + id(rig.player().getMainHandItem().getItem())
                + actingHand(rig)
                + (ok ? "" : "; " + bucketStock(rig)));
        return ok;
    }

    /**
     * Put the item in the bot's main hand, through {@code mc.bot.holdItem}: select its hotbar slot, or swap it up
     * from the bag.
     *
     * <p>A roster bot answers within the call, and that answer is returned. self answers on the client thread,
     * so its answer is recorded when it lands under {@code holdBoth.<item>.self} and what is returned is the
     * prediction the verb's own rule gives — the item is somewhere in the bag — read off the server's copy.
     * A hold on self is ONE author: the client's selection packet or swap click corrects the server, and it is
     * ordered before any use packet that follows, which is why nothing here writes the server's copy as well.
     * (Writing both is what once swapped a bag item twice — an involution — and handed the server a pickaxe.)
     */
    static boolean holdBoth(JourneyRig rig, net.minecraft.world.item.Item item) {
        CompletableFuture<Responses.ImmediateResponse> f = InteractApi.of(Calls.api())
                .holdItem(new InteractContracts.HoldParams(rig.bot(), id(item))).toCompletableFuture();
        if (!onSelf(rig)) {
            Responses.ImmediateResponse r = Calls.now(f);
            if (r instanceof Responses.Refused<?> no) {
                rig.evidence("holdBoth." + BuiltInRegistries.ITEM.getKey(item).getPath(),
                        "refused: " + no.message() + "; " + stockOnBoth(rig, item));
                return false;
            }
            return true;
        }
        rig.recordWhenLanded("holdBoth." + BuiltInRegistries.ITEM.getKey(item).getPath() + ".self", f);
        return rig.carrying(id(item)) > 0;
    }

    /** How many of one item the bot has, by the server's count; the decider for a failed hold. */
    private static String stockOnBoth(JourneyRig rig, net.minecraft.world.item.Item item) {
        return id(item) + " ×" + rig.carrying(id(item)) + (onSelf(rig) ? " (server's copy; " + CLIENT_UNREADABLE + ")" : "");
    }

    /**
     * The slot the hand reads from, beside the item this row has always printed.
     *
     * <p>THE SLOT NUMBER, not just the item — because two different failures print the same item and want
     * opposite fixes: the hotbar branch of a hold selects a slot, the bag branch swaps a stack up into it. On self
     * the server's number is the PRE-selection one whenever the hold did anything at all, so "a different slot"
     * there is the healthy reading, not a lost selection. {@link #handsAtUse}, read at the moment of the use, is
     * the row that decides.
     */
    private static String actingHand(JourneyRig rig) {
        var p = rig.player();
        return " (slot " + p.getInventory().selected + " = " + id(p.getMainHandItem().getItem())
                + (onSelf(rig) ? "; the server's copy, stale by the slot change's round trip; " + CLIENT_UNREADABLE : "")
                + ")";
    }

    /**
     * Is the bot holding {@code item} RIGHT NOW, by the copy the use will consult?
     *
     * <p>The one question {@link #holdForUse} cannot answer, because it is asked at the wrong moment.
     * {@code holdForUse} runs where the caller decides to use something; the use runs after the aim,
     * after the plant clearing, after the last settle — and ladder-11's twelfth rung showed those are
     * far enough apart for the hold to come undone.
     *
     * <p><b>How a hold comes undone, measured.</b> Cell six read {@code cast6.hand = minecraft:lava_bucket}
     * at slot 4, and ten ticks later {@code cast6.atUse} read slot 4 = {@code minecraft:dirt}. Six is the first
     * cell whose pour needed a RAISE, and the tower holds dirt: that hold pushed the bucket out of the hotbar.
     * So this is not a lag reading and re-reading it later does not fix it: the slot really does hold dirt by
     * then. Ask here, right before the use, and re-hold.
     *
     * <p>The server's player: on a roster bot the only copy, on self the one {@code handleUseItem} reads its
     * item from when the use packet lands.
     */
    static boolean actingHolds(JourneyRig rig, net.minecraft.world.item.Item item) {
        return rig.player().getMainHandItem().getItem() == item;
    }

    /**
     * Re-assert the hold immediately before a use, and say whether it took.
     *
     * <p>The gap {@link #actingHolds} documents is not one site's mistake — it is what
     * {@link #aimThenAct} does for a living: hold two ticks, aim, act. Every caller that holds
     * something and then goes through an aim has ten ticks between the hold and the use.
     *
     * <p>Three states, on purpose, and the caller must keep them apart:
     * <ul>
     *   <li>no {@code .handSlipped} row ⇒ the hold never came undone;</li>
     *   <li>a row and then {@code true} ⇒ it came undone and the second hold fixed it;</li>
     *   <li>a row and then {@code false} ⇒ refuse the use. A use with the wrong thing in hand
     *       returns {@code PASS} and changes nothing, which is byte-identical to a ray that
     *       missed — and the failure then surfaces several steps later, somewhere else.</li>
     * </ul>
     *
     * <p>On self the second hold's answer has not landed by the line after it, so the re-check there reads the
     * hold's prediction rather than the hand.
     */
    static boolean regripBeforeUse(JourneyRig rig, net.minecraft.world.item.Item item, String tag) {
        if (actingHolds(rig, item)) return true;
        rig.evidence(tag + ".handSlipped", "the main hand no longer holds "
                + id(item) + " before the use: " + heldOnBoth(rig)
                + " - a settle ran after the previous hold; holding the item again");
        boolean held = holdForUse(rig, item, tag + ".again");
        return onSelf(rig) ? held : actingHolds(rig, item);
    }

    /** What the bot holds, for a failure message that has to name the thing that went wrong. */
    static String heldOnBoth(JourneyRig rig) {
        var p = rig.player();
        return (onSelf(rig) ? CLIENT_UNREADABLE + "; server " : "") + "slot " + p.getInventory().selected + " = "
                + id(p.getMainHandItem().getItem());
    }

    /**
     * The hand, the eye and both rays at the instant of the use — the reading every earlier row was too early
     * to take.
     *
     * <p>A pour that does nothing has two causes that every other row prints identically. Either the server is
     * holding the wrong item — a pickaxe's {@code use} is {@code PASS} — or it holds the bucket and its
     * {@code getPlayerPOVHitResult} came back {@code MISS}, which also returns {@code PASS}. Vanilla logs
     * neither, so the ray has to be printed. Both fluid modes, deliberately, so no caller has to pass a flag it
     * can get backwards: an empty bucket clips {@code SOURCE_ONLY} and a full one clips {@code NONE}, and the
     * two answers differ exactly where this rung lives — over water and lava.
     */
    static void handsAtUse(JourneyRig rig, String tag) {
        var server = rig.player();
        rig.evidence(tag + ".atUse", onSelf(rig)
                ? "server " + oneBodyAtUse(server) + " (" + CLIENT_UNREADABLE + ")"
                : "both halves are the same object (no client topology): " + oneBodyAtUse(server));
    }

    private static String oneBodyAtUse(net.minecraft.world.entity.player.Player p) {
        if (p == null) return "no player entity";
        return String.format(java.util.Locale.ROOT,
                "slot %d = %s; eye %.2f/%.2f/%.2f facing yaw=%.2f pitch=%.2f; full-bucket ray %s; empty-bucket ray %s",
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
     * would cover only {@code use+0 … use+4} — and the reading that says "the other author is merely
     * slower" is a flip on {@code use+5}.
     */
    static final int TRACE_TICKS = 6;

    /**
     * ONE tick's hand on the server's player, with the thread and the game time it was read at.
     *
     * <p><b>The question it exists to answer.</b> {@code ServerboundUseItemPacket} carries
     * hand/sequence/yaw/pitch and <b>no item</b>: {@code handleUseItem} runs
     * {@code this.player.getItemInHand(hand)}, i.e. whatever the SERVER's {@code inventory.selected}
     * points at <i>when it processes the packet</i>. A second author that moves the server's selection between
     * the send and the handling makes the server use a different object — and cobblestone is a
     * {@code BlockItem}, whose {@code use} returns {@code PASS}: nothing consumed, nothing placed, nothing
     * logged. Every other row this rung writes is one snapshot of one moment, so none of them can see a flip.
     * This one asks the same question on consecutive ticks.
     *
     * <p>Two keys, {@code .client} and {@code .server}, as before: on self the client half says it cannot be
     * read, on a roster bot it is the same object as the server half. {@code gameTime} is printed so that "six
     * consecutive server ticks" is measured instead of argued.
     */
    static void handTrace(JourneyRig rig, String tag, int tick) {
        String thread = Thread.currentThread().getName();
        var server = rig.player();
        String key = tag + ".handTrace.t" + tick;
        rig.evidence(key + ".client", onSelf(rig)
                ? "tick=" + tick + " readThread=" + thread + "; client LocalPlayer: " + CLIENT_UNREADABLE
                : oneHandAt(server, tick, thread, "client half (no client topology; same object as the server half)"));
        rig.evidence(key + ".server", oneHandAt(server, tick, thread,
                "server ServerPlayer (the object handleUseItem reads)"));
    }

    private static String oneHandAt(net.minecraft.world.entity.player.Player p, int tick,
                                    String thread, String whose) {
        if (p == null) {
            return "tick=" + tick + " readThread=" + thread + "; " + whose + ": no player entity";
        }
        return String.format(java.util.Locale.ROOT,
                "tick=%d gameTime=%d readThread=%s; %s: slot %d = %s ×%d",
                tick, p.level().getGameTime(), thread, whose,
                p.getInventory().selected,
                BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()),
                p.getMainHandItem().getCount());
    }

    private static String describeRay(net.minecraft.world.entity.player.Player p, boolean hitFluids) {
        var hit = aimedAt(p, p.blockInteractionRange(), hitFluids);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return String.format(java.util.Locale.ROOT,
                    "MISS (nothing blocks the ray within %.2f blocks; vanilla returns PASS here and logs nothing)",
                    p.blockInteractionRange());
        }
        return hit.getBlockPos().toShortString() + " "
                + BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(hit.getBlockPos()).getBlock())
                + " face=" + hit.getDirection()
                + String.format(java.util.Locale.ROOT, " (%.2f blocks)",
                        Math.sqrt(hit.getLocation().distanceToSqr(p.getEyePosition())));
    }

    /**
     * Where the three bucket states stand — printed only when a hold FAILED.
     *
     * <p>A bucket is not an item this ladder owns one of; it is <b>one object in three states</b>
     * ({@code bucket} / {@code water_bucket} / {@code lava_bucket}), and rung 12 carries exactly one
     * of it. So a failed hold of an empty bucket has two completely different meanings — the bucket was lost,
     * or the bucket is FULL — and only these three numbers tell them apart.
     */
    static String bucketStock(JourneyRig rig) {
        return String.format(java.util.Locale.ROOT, "bucket stock empty=%d water=%d lava=%d",
                rig.carrying("minecraft:bucket"),
                rig.carrying("minecraft:water_bucket"),
                rig.carrying("minecraft:lava_bucket"));
    }

    // ---------------------------------------------------------------- using

    /**
     * Right-click with the held item in the air, along the bot's own look — a bucket fills or pours where its
     * ray lands — through {@code mc.bot.useItem}. The answer is written under {@code key}: within the call for a
     * roster bot, when it lands for self.
     */
    static void useInAir(JourneyRig rig, String key) {
        rig.recordWhenLanded(key, sendUseInAir(rig));
    }

    /**
     * {@link #useInAir} for a caller that branches on the answer in the same tick: a roster bot's, or
     * {@code pending} for self's, whose use the server has not seen yet either — exactly as when this was the
     * client's own {@code useItemInHand}, which the server handled a packet later.
     */
    static String useInAirNow(JourneyRig rig) {
        return JourneyRig.landedNow(sendUseInAir(rig));
    }

    private static java.util.concurrent.CompletableFuture<?> sendUseInAir(JourneyRig rig) {
        return InteractApi.of(Calls.api()).useItem(new InteractContracts.UseParams(rig.bot(),
                Optional.empty(), Optional.empty(), Optional.empty(), InteractContracts.Hand.MAIN, false, false))
                .toCompletableFuture();
    }

    /**
     * Right-click {@code cell}'s {@code face} with the held item — light a portal, set an eye, place against it —
     * through {@code mc.bot.useItem}, without turning: the click lands on the face's centre, as the body's
     * {@code useBlock}/{@code placeOn} it replaces did. The answer is written under {@code key}: within the call
     * for a roster bot, when it lands for self.
     *
     * <p>One difference from the body call, and it is the verb's: a cell beyond reach is refused rather than
     * clicked, so the row reads {@code refused: …out of reach} where the old click reached past the player.
     */
    static void useOn(JourneyRig rig, BlockPos cell, Direction face, String key) {
        rig.recordWhenLanded(key, sendUseOn(rig, cell, face));
    }

    /** {@link #useOn} for a caller that reads the world in the same tick; see {@link #useInAirNow}. */
    static String useOnNow(JourneyRig rig, BlockPos cell, Direction face) {
        return JourneyRig.landedNow(sendUseOn(rig, cell, face));
    }

    private static java.util.concurrent.CompletableFuture<?> sendUseOn(JourneyRig rig, BlockPos cell, Direction face) {
        return sendUseOn(rig.bot(), cell, face);
    }

    private static java.util.concurrent.CompletableFuture<?> sendUseOn(String bot, BlockPos cell, Direction face) {
        return InteractApi.of(Calls.api()).useItem(new InteractContracts.UseParams(bot,
                Optional.of(Targets.pos(cell)), Optional.empty(),
                Optional.of(InteractContracts.Face.valueOf(face.name())), InteractContracts.Hand.MAIN, false, false))
                .toCompletableFuture();
    }

    /**
     * What a placing loop needs off a bot — an item in hand, a click against a face — so the loop runs the same
     * on the rig's bot and on an arena's. Both go through the hand verbs; a placement is judged off the world.
     */
    interface Placer {
        boolean hold(net.minecraft.world.item.Item item);
        void clickOn(BlockPos cell, Direction face);
    }

    /** The rig's bot as a {@link Placer}: {@link #holdBoth}'s answer, one {@code mc.bot.useItem} per click. */
    static Placer placer(JourneyRig rig) {
        return new Placer() {
            @Override public boolean hold(net.minecraft.world.item.Item item) { return holdBoth(rig, item); }
            @Override public void clickOn(BlockPos cell, Direction face) { sendUseOn(rig, cell, face); }
        };
    }

    /** A roster bot as a {@link Placer}; its calls answer within the call. */
    static Placer placer(SceneBot bot) {
        return new Placer() {
            @Override public boolean hold(net.minecraft.world.item.Item item) {
                return Calls.now(InteractApi.of(Calls.api()).holdItem(new InteractContracts.HoldParams(bot.ref(), id(item))))
                        instanceof Responses.Held;
            }
            @Override public void clickOn(BlockPos cell, Direction face) { sendUseOn(bot.ref(), cell, face); }
        };
    }

    /**
     * Open one named cell with an aimed swing of a roster bot — what the rig's in-place swing does, which is
     * what a rung's {@code mineCellOrGiveUp} tries before it routes anywhere.
     *
     * <p>Judged on the WORLD rather than on the call, because the press answers what the bot holds, not whether
     * a block gave. That is what makes this a scene-side twin of the production verb rather than a call to it:
     * an arena that wants to know whether a cell opened must ask the level.
     */
    static boolean swing(SceneBot bot, BlockPos cell) {
        if (!bot.reach(cell).canBreak()) return false;
        var at = Targets.pos(cell);
        bot.press(bot.keys().toolFor(at).aimAt(at).attack(true));
        bot.press(bot.keys().attack(false));
        return !bot.player().level().getBlockState(cell).blocksMotion();
    }
}

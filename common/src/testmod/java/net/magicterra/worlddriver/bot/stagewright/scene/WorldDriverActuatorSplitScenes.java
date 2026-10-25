package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static net.magicterra.worlddriver.bot.stagewright.scene.SceneCalls.self;
import static net.magicterra.worlddriver.bot.stagewright.scene.SceneCalls.ask;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.api.methods.client.ClientApi;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.screen.ScreenApi;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.magicterra.worlddriver.bot.stagewright.ClientRegistry;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * How far apart the two halves of an "adopted" player drift, measured rather than argued about.
 *
 * <h2>The question this exists to answer</h2>
 *
 * On an integrated server the client's player exists twice: the {@code ServerPlayer} and the
 * {@code LocalPlayer}. Vanilla's client is authoritative for the selected hotbar slot and the
 * rotation — the slot travels up via {@code ServerboundSetCarriedItemPacket}, rotation every tick in
 * {@code ServerboundMovePlayerPacket.Rot} — so a write to the server copy of either is a write the
 * client never agreed to, and the next packet can silently undo it. A driver that actuates the
 * client's player by writing its server copy is therefore wrong, and the two scenes here measure by
 * how much:
 *
 * <ul>
 *   <li>{@code wd.actuatorSplitOnAnAdoptedBody}, the ruler, makes the server-side writes directly —
 *       {@code inv.selected = i}, {@code setYRot(...)} — and records both sides;</li>
 *   <li>{@code wd.actuatorSplitThroughTheClientAvatar}, the twin, makes the same two changes through
 *       the client's own input ({@code mc.client.input.setHotbarSlot}, {@code mc.test.input.press}
 *       on self) and asserts the client ends where it was sent.</li>
 * </ul>
 *
 * <h2>What the ruler does NOT do</h2>
 *
 * <b>It asserts no fix.</b> It performs the server-side writes, then reads BOTH sides and records the
 * pair. The check it makes is only that the reading was obtainable at all; the numbers themselves are
 * recorded unconditionally, on PASS as well, because a divergence that first appears six months from
 * now is invisible unless the healthy value is already in the record.
 *
 * <p>The client side is read through {@code mc.client.player}, which reports
 * {@code inventory.selectedSlot} and {@code look.yaw/pitch} off the real {@code LocalPlayer}. It
 * answers on the client thread, which the scene's server thread may not wait for, so each reading is
 * asked for and the scene continues when it lands.
 *
 * <h2>Two samples, and why both are needed</h2>
 *
 * <b>At the write</b> answers "did the write land". <b>After {@link #SETTLE_TICKS} real
 * ticks</b> answers "did the client overwrite it". Only the second can see the packet arrive, and only the first can tell a
 * write that never landed from one that landed and was reverted — printing one without the other
 * reproduces the ambiguity the player parity census ({@code wd.bodyParityCensus}) had to grow a
 * second phase to escape.
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
                // The twin. Same measurement, taken through the client's own input, so the
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
     * that would contest the write, which would report "no divergence" for a divergence that arrives on
     * the next tick — the false-negative this whole scene exists to prevent.
     */
    private static final int SETTLE_TICKS = 10;

    /**
     * The slot the ruler scene's server-side write aims at, and the twin's fallback when the client
     * cannot be read at all.
     *
     * <p>Not 0, so that "the write succeeded" and "it already had this value" cannot print
     * identically — 0==0 with extra
     * steps. But note that is only true for the RULER, which merely measures and asserts nothing
     * about the value. <b>A constant is not good enough for a criterion</b>: the twin derives its
     * target from the client's actual pre-write slot instead, because "deliberately not 0" is an assumption
     * about the starting state, and a criterion resting on an assumption passes for free the day
     * the assumption stops holding.
     */
    private static final int TARGET_SLOT = 4;

    private static void actuatorSplit(SceneContext ctx) {
        // Read off the running game, never echoed from a -D: a launch can set a property and then
        // fail to do what it promised, and this scene's whole value is being unable to lie.
        ctx.record("topology", topology(ctx));
        ServerPlayer real = clientsPlayer(ctx);

        // ---- put it back, registered BEFORE the first write ---------------------------------
        //
        // This scene writes into a HUMAN's player: a stone into a hotbar slot, a selected slot, a
        // yaw and a pitch. Everything after this line runs on the client's real player, and the scenes
        // after this one inherit whatever it leaves — an arena audit calls a leftover entity a leak
        // and is right to; an item pushed into a player's inventory is the same class of residue.
        //
        // Registered here rather than at the end because cleanups drain on PASS, FAIL and TIMEOUT
        // alike, and the interesting exits are the other two: the final check and the await budget
        // both can end the scene after the writes have landed, and a restore written after them
        // would be skipped on exactly those runs.
        //
        // Restored through the raw fields, the primitive underneath any actuator, so a restore can
        // fail here only if the field itself is unwritable.
        ItemStack slotWas = real.getInventory().items.get(TARGET_SLOT).copy();
        int selectedWas = real.getInventory().selected;
        float yawWas = real.getYRot();
        float pitchWas = real.getXRot();
        ctx.cleanup(() -> {
            real.getInventory().items.set(TARGET_SLOT, slotWas);
            real.getInventory().selected = selectedWas;
            real.setYRot(yawWas);
            real.setXRot(pitchWas);
            // No row here reports whether the restore (selected slot, slot item) took effect, for two
            // independent reasons:
            //
            //   • UNREADABLE. `SceneContext.record` is a bare map put and logs nothing, and the
            //     harness serialises `ctx.records()` inside `record(scene, …)`, which precedes
            //     `teardown(…)` — the caller of these cleanups — on every terminal path. A row
            //     written from here lands in a map whose only reader finished reading it.
            //   • UNFALSIFIABLE. It could only read back what the lines above had just written, on
            //     this thread, with only the two rotation setters in between: `selected == selectedWas`
            //     one statement after `selected = selectedWas`, and `ItemStack.matches` against the
            //     very object just placed in the slot. It could print "yes" and nothing else.
            //
            // Recording such a row elsewhere would only move a tautology somewhere visible. The
            // CLIENT does own these two quantities, but it takes them back by packet on a LATER
            // tick — which is exactly why the scene asks that question below as the server's slot
            // against the client's, two sources compared, rather than by reading one field twice.
            // Teardown has no later tick, so the question cannot be asked from here at all.
        });

        // ---- 0. the client, before anything is written ----------------------------------------
        // Nothing below touches the LocalPlayer, so this reading is also what the client holds the
        // instant the writes complete: the client's side of the at-the-write sample.
        readClient(ctx, "client.beforeWrite", atWrite -> writeAndMeasure(ctx, real, atWrite));
    }

    /** The ruler's two server-side writes, and both sides read again {@link #SETTLE_TICKS} later. */
    private static void writeAndMeasure(SceneContext ctx, ServerPlayer real, ClientReading atWrite) {
        float[] clientLookAtWrite = atWrite.look();

        // ---- 1. the hotbar slot -------------------------------------------------------------
        // Something to select, so the write selects a real stack. The write is a server-side
        // holdItem: the selected index, on the server's copy of the player.
        real.getInventory().items.set(TARGET_SLOT, new ItemStack(Items.STONE, 1));
        int slotBefore = real.getInventory().selected;
        real.getInventory().selected = TARGET_SLOT;
        int serverSlotSameTick = real.getInventory().selected;

        ctx.record("slot.before", slotBefore);
        // WHICH THREAD DID THE WRITING. Without this row an "agree" reading is ambiguous in the one
        // direction that matters: a cross-thread write to client state usually does NOT throw (few
        // of these vanilla fields carry a thread assertion), so an actuator that agrees today can be
        // sitting on a data race that disagrees under load. "Green" would not mean "correct".
        // Recorded at BOTH sample points, because the scene method and its await continuation are
        // different moments and nothing guarantees the harness runs them on the same thread.
        ctx.record("thread.atWrite", Thread.currentThread().getName());
        ctx.record("slot.server.sameTick", serverSlotSameTick);
        ctx.record("slot.client.atWrite", render(atWrite.slot()));

        // ---- 2. the aim ---------------------------------------------------------------------
        // A cell far enough off-axis that the resulting angles cannot coincide with whatever the
        // player happened to be facing — an aim that agrees by luck measures nothing. The write is a
        // server-side aimAtBlock: the server copy's yaw and pitch, set to face the cell's centre.
        BlockPos aimAt = chooseAimTarget(real);
        ctx.record("aim.targetChoiceBasis", aimChoiceEvidence(real, aimAt));
        float yawBefore = real.getYRot();
        float pitchBefore = real.getXRot();
        float[] aimed = aimFromEyeTo(real, aimAt);
        real.setYRot(aimed[0]);
        real.setXRot(aimed[1]);
        float serverYawSameTick = real.getYRot();
        float serverPitchSameTick = real.getXRot();
        // WHERE the player stood when the angle was written, and what the angle therefore had to be.
        // An aim stores ANGLES, not a target — the player moving afterwards silently invalidates it,
        // and at this range (~8.6 blocks) three quarters of a block of drift is worth the entire
        // tolerance. Captured here so the continuation can say whether the requirement moved, and so
        // the criterion below can be judged against what the actuator was ASKED for rather than
        // against a requirement recomputed ten ticks later at a position nobody asked about.
        Vec3 posAtWrite = real.position();
        float[] wantAtWrite = aimFromEyeTo(real, aimAt);

        ctx.record("aim.targetBlock", aimAt.toShortString());
        ctx.record("aim.before", deg(yawBefore) + " / " + deg(pitchBefore));
        ctx.record("aim.server.sameTick", deg(serverYawSameTick) + " / " + deg(serverPitchSameTick));
        ctx.record("aim.client.atWrite", renderLook(clientLookAtWrite));

        // ---- 3. let real ticks pass, then ask both sides again --------------------------------
        // The whole point: only after the client has ticked can its packet contest either write.
        // The server side is read when the client's reading lands, so the two are a tick apart at most.
        int[] waited = {0};
        ctx.await(() -> ++waited[0] >= SETTLE_TICKS).within(SETTLE_TICKS + 100).then(() ->
                readClient(ctx, "client.after" + SETTLE_TICKS + "Ticks", after -> {
            int serverSlotAfter = real.getInventory().selected;
            Integer clientSlotAfter = after.slot();
            float serverYawAfter = real.getYRot();
            float serverPitchAfter = real.getXRot();
            float[] clientLookAfter = after.look();

            ctx.record("thread.atRecheck", Thread.currentThread().getName());
            ctx.record("slot.server.after" + SETTLE_TICKS + "Ticks", serverSlotAfter);
            ctx.record("slot.client.after" + SETTLE_TICKS + "Ticks", render(clientSlotAfter));
            ctx.record("slot.finalAgreement", agree(serverSlotAfter, clientSlotAfter));
            ctx.record("slot.serverReverted", serverSlotSameTick != serverSlotAfter
                    ? "yes: " + serverSlotSameTick + " → " + serverSlotAfter
                    : "no (the server still has " + serverSlotAfter + ")");

            ctx.record("aim.server.after" + SETTLE_TICKS + "Ticks",
                    deg(serverYawAfter) + " / " + deg(serverPitchAfter));
            ctx.record("aim.client.after" + SETTLE_TICKS + "Ticks", renderLook(clientLookAfter));
            ctx.record("aim.serverReverted", drift(serverYawSameTick, serverPitchSameTick,
                    serverYawAfter, serverPitchAfter));
            ctx.record("aim.serverClientDifference", clientLookAfter == null ? "unavailable/client could not be read"
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
            // cannot rot the way "remember to switch back to the old path by hand and try once" does;
            // and it leaves no broken state
            // behind if the run is interrupted, which a temporary revert of a live call site does.
            //
            // RECORDED, not checked. This scene has no verdict by design — see the class javadoc —
            // and asserting here would give it one, in the direction of "the defect must persist",
            // which is a criterion nobody should be able to satisfy by fixing something. The WARNING
            // marker is for the reader: if the server path ever starts driving the client's aim too, the twin's check
            // has stopped distinguishing the two routes and is no longer evidence of anything.
            float[] wantHere = aimFromEyeTo(real, aimAt);
            ctx.record("aim.requirementDrift",
                    requirementDrift(posAtWrite, real.position(), wantAtWrite, wantHere));
            boolean clientFollowed = aimSatisfies(clientLookAfter, wantAtWrite);
            // "The client was already facing that way" is a third outcome and must be reported on
            // its own. The at-the-write reading is the client's pre-write state: the server writes
            // touch only ServerPlayer fields, so the LocalPlayer has had no reason to move. If
            // the client already met the geometric requirement then, this run's control proves
            // nothing: meeting it is a coincidence, and not meeting it would only mean the client
            // turned away later. It is not folded into the two branches above because that would
            // print a coincidence as "WARNING: criterion ineffective" and send someone to
            // investigate a criterion that is fine; a false "fails" costs as much as a false "works".
            //
            // Both branches compare against wantAtWrite, not wantHere. Pairing an at-the-write reading
            // with a requirement computed 10 ticks later puts quantities from two moments into one
            // inequality; if the bot moved at all, the branches would contradict each other because
            // of the sampling times, not because the client actually turned.
            boolean clientAlreadyThere = aimSatisfies(clientLookAtWrite, wantAtWrite);
            // The margin, printed. Whether the control holds comfortably or by a fraction of a
            // degree is not visible from "valid" alone, and the difference decides whether anyone
            // should trust it next month.
            ctx.record("aim.controlMargin", aimGap(clientLookAfter, wantAtWrite));
            ctx.record("aim.negativeControl", clientLookAfter == null
                    ? "the client's facing could not be read; this run cannot serve as a control"
                    : clientAlreadyThere
                            ? "void for this run: before the write the client was already facing "
                                    + aimAt.toShortString()
                                    + " (reading at the write " + renderLook(clientLookAtWrite)
                                    + ", geometric requirement " + deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1])
                                    + "). This is a coincidence; this run cannot distinguish \"the server drove "
                                    + "the client\" from \"the client was already there\", so draw no conclusion from it"
                            : clientFollowed
                                    ? "WARNING: through the server actuator, the client's facing also reached the "
                                            + "geometric requirement (before the write " + renderLook(clientLookAtWrite)
                                            + ", after " + SETTLE_TICKS + " ticks "
                                            + renderLook(clientLookAfter) + "). The twin scene's aim "
                                            + "criterion no longer distinguishes the two paths, so its pass is no "
                                            + "longer evidence; investigate it"
                                    : "valid: for the same target block through the server actuator, the client's "
                                            + "facing does not meet the geometric requirement (required "
                                            + deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1])
                                            + ", actual " + renderLook(clientLookAfter) + "), so the twin "
                                            + "scene's criterion does fail while the defect is present");

            // THE ONLY CHECK, and it is about the instrument rather than the subject: if the client
            // side could not be read, every agreement/difference row above is the string
            // "unavailable" and the scene has measured nothing. Without this the scene would go
            // green while reporting nothing at all — the shape where a suite reports a subject it
            // never executed.
            ctx.check(clientSlotAfter != null && clientLookAfter != null)
                    .as("the client-side readings are available (mc.client.player reported selectedSlot "
                            + "and look); without them every agreement row above is meaningless and this "
                            + "scene measured nothing")
                    .isTrue();
        }));
    }

    /**
     * The same measurement, taken through the client's own input: {@code mc.client.input.setHotbarSlot}
     * for the slot and {@code mc.test.input.press} on self for the aim, both of which act on the
     * {@code LocalPlayer} on the client thread.
     *
     * <p><b>The acceptance criterion, and it is designed to be able to fail.</b> Its sibling
     * measured the raw divergence and found it total: server slot 4 vs client 0, server aim
     * (−55.32, 29.55) vs client (283.23, 0.00), unchanged ten ticks later. If routing the same two
     * changes through the client's own input does not close that gap, this scene says so.
     */
    private static void actuatorSplitClient(SceneContext ctx) {
        ctx.record("topology", topology(ctx));
        ServerPlayer real = clientsPlayer(ctx);

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

        // WARNING: setHotbarSlot, NOT holdItem — and this is the whole difference between measuring the
        // route and measuring this scene's own staging mistake.
        //
        // `holdItem` on the client searches the CLIENT's inventory for the item, and what a staging
        // step puts into the SERVER's copy reaches the client only when a container/slot packet
        // arrives. So a client holdItem could fail for a reason that has nothing to do with the
        // route being tested, and the criterion below would go red while the route was working
        // perfectly. That is the shape this file exists to avoid: a failure whose cause is the test.
        //
        // setHotbarSlot asks the identical question with no dependency on inventory CONTENTS —
        // it writes the client's `selected` and sends the ServerboundSetCarriedItemPacket, which is
        // exactly the mechanism whose absence the sibling scene measured (server 4 / client 0).
        // ---- the probe must MOVE something, or a green here certifies nothing ----------------
        //
        // Both criteria in this scene have the form "the client finally equals the target value".
        // If the client ALREADY sat at that value before the write, they pass while the actuator
        // contributes exactly zero — a scene going green while the mechanism in its name did
        // nothing at all. A fixed TARGET_SLOT can only ever be an ASSUMPTION about the starting
        // state ("deliberately not 0" is an assumption, not a guard), and assumptions about
        // starting state are what this file exists to stop trusting.
        //
        // So the target is DERIVED from the reading: +4 mod 9 is provably a different slot from
        // whatever the client is on, whatever that turns out to be. Now "the client ends on the
        // target slot" can only be true if something actually moved it.
        readClient(ctx, "client.beforeAction", before -> {
            Integer clientSlotBefore = before.slot();
            int targetSlot = clientSlotBefore == null ? TARGET_SLOT : (clientSlotBefore + 4) % 9;
            ctx.record("slot.clientBeforeAction", render(clientSlotBefore));
            ctx.record("slot.targetSlot", targetSlot + " (derived from the client reading before the action, "
                    + "so it is guaranteed to differ from it; with a hard-coded constant, this criterion would "
                    + "pass with zero contribution whenever the client already happened to be on that slot)");
            ask(ctx, "slot.action", ScreenApi.of(self()).setHotbarSlot(
                            new ScreenContracts.HotbarParams(targetSlot, Optional.empty())),
                    selected -> parkThenAim(ctx, real, clientSlotBefore, targetSlot));
        });
    }

    /** The twin's aim half: park the client's facing away from the target, then aim it there. */
    private static void parkThenAim(SceneContext ctx, ServerPlayer real, Integer clientSlotBefore, int targetSlot) {
        // Same disease on the aim half, same cure. Point the client at a cell in the OPPOSITE
        // direction first, so "finally facing the target block" cannot be satisfied by wherever it
        // already happened to be looking. Without this, a client idly facing that quadrant hands the aim criterion a
        // free pass and the route gets credit for an angle it never wrote.
        //
        // Yes, the parking uses the verb under test — but it cannot manufacture a false GREEN, only
        // a red. If the aim is broken the park does nothing, and then either the client is left
        // where it was (the precondition check below fires and says so) or it is left satisfying the
        // requirement by luck (same check fires). A broken actuator cannot reach a pass through
        // this door; that is the difference between this and a cleanup that runs its own verb.
        BlockPos aimAt = chooseAimTarget(real);
        ctx.record("aim.targetChoiceBasis", aimChoiceEvidence(real, aimAt));
        BlockPos parkAt = chooseParkTarget(real, aimFromEyeTo(real, aimAt));
        ask(ctx, "aim.park", aimSelf(parkAt), parkedKeys -> readClient(ctx, "client.parked", parked -> {
            float[] clientLookParked = parked.look();
            // The slot change has had the park's round trip to reach the server; a side that has not
            // caught up shows here, and the verdict waits for the reading after the settle.
            int serverSlot = real.getInventory().selected;
            ctx.record("slot.server.afterAction", serverSlot);
            ctx.record("slot.client.afterAction", render(parked.slot()));
            ctx.record("slot.agreementAfterAction", agree(serverSlot, parked.slot()));

            // Same reason as the sibling: the actuator writes angles, so the requirement must be pinned
            // to the moment it was asked for. Judging at +10 ticks against a recomputed requirement would
            // let a player that merely MOVED fail this criterion, and the route would be blamed for physics.
            Vec3 posAtWrite = real.position();
            float[] wantAtWrite = aimFromEyeTo(real, aimAt);
            // The precondition, measured BEFORE the real aim: parked and provably not already on target.
            boolean parkedAway = !aimSatisfies(clientLookParked, wantAtWrite);
            ctx.record("aim.parkBlock", parkAt.toShortString());
            ctx.record("aim.clientBeforeAction", renderLook(clientLookParked));
            ctx.record("aim.preconditionHolds", clientLookParked == null
                    ? "the client's facing could not be read; cannot judge"
                    : parkedAway ? "yes: before the action the client did not meet the target's geometric "
                            + "requirement, so \"finally meets it\" can only be caused by this action"
                            : "WARNING: no: before the action the client already met the target's geometric "
                                    + "requirement; this run's aim criterion can pass with zero contribution, "
                                    + "so do not read it as the route passing");

            ask(ctx, "aim.action", aimSelf(aimAt), aimedKeys -> readClient(ctx, "client.afterAction", now -> {
                ctx.record("aim.targetBlock", aimAt.toShortString());
                ctx.record("aim.server.afterAction", deg(real.getYRot()) + " / " + deg(real.getXRot()));
                ctx.record("aim.client.afterAction", renderLook(now.look()));
                Twin twin = new Twin(aimAt, clientSlotBefore, targetSlot, clientLookParked, parkedAway,
                        posAtWrite, wantAtWrite, now.look());
                int[] waited = {0};
                ctx.await(() -> ++waited[0] >= SETTLE_TICKS).within(SETTLE_TICKS + 100).then(() ->
                        readClient(ctx, "client.after" + SETTLE_TICKS + "Ticks", judgeTwin(ctx, real, twin)));
            }));
        }));
    }

    /** What the twin set up and saw before the settle, for judging after it. */
    private record Twin(BlockPos aimAt, Integer clientSlotBefore, int targetSlot, float[] clientLookParked,
                        boolean parkedAway, Vec3 posAtWrite, float[] wantAtWrite, float[] clientLookNow) {}

    /** The twin's rows after the settle, then its verdicts. */
    private static Consumer<ClientReading> judgeTwin(SceneContext ctx, ServerPlayer real, Twin twin) {
        BlockPos aimAt = twin.aimAt();
        Integer clientSlotBefore = twin.clientSlotBefore();
        int targetSlot = twin.targetSlot();
        float[] clientLookParked = twin.clientLookParked();
        boolean parkedAway = twin.parkedAway();
        Vec3 posAtWrite = twin.posAtWrite();
        float[] wantAtWrite = twin.wantAtWrite();
        float[] clientLookNow = twin.clientLookNow();
        return after -> {
            int serverSlotAfter = real.getInventory().selected;
            Integer clientSlotAfter = after.slot();
            float[] clientLookAfter = after.look();
            ctx.record("slot.server.after" + SETTLE_TICKS + "Ticks", serverSlotAfter);
            ctx.record("slot.client.after" + SETTLE_TICKS + "Ticks", render(clientSlotAfter));
            ctx.record("slot.finalAgreement", agree(serverSlotAfter, clientSlotAfter));
            ctx.record("aim.server.after" + SETTLE_TICKS + "Ticks",
                    deg(real.getYRot()) + " / " + deg(real.getXRot()));
            ctx.record("aim.client.after" + SETTLE_TICKS + "Ticks", renderLook(clientLookAfter));
            ctx.record("aim.serverClientDifference", clientLookAfter == null ? "unavailable/client could not be read"
                    : drift(real.getYRot(), real.getXRot(), clientLookAfter[0], clientLookAfter[1]));

            // WARNING: EVERY evidence row is written BEFORE the first ctx.check, and the order matters.
            //
            // A failing check throws, so anything recorded after it never reaches the results file.
            // The two halves — the slot and the aim — are INDEPENDENT: the aim rows are not
            // context for the slot criterion, they are the only description of a separate subject.
            // Recording them after the slot check meant that a broken slot half deleted the entire
            // aim diagnosis from the record, and the reader would be told nothing about the half
            // that might still be fine. Evidence goes in the file first; verdicts come after.
            float[] want = aimFromEyeTo(real, aimAt);
            boolean aimed = aimSatisfies(clientLookAfter, wantAtWrite);

            // THE SAME CRITERION FOR THE OTHER HALF. Without it nothing here could go red if the aim
            // route regressed: every aim row would be ctx.record. A scene
            // that only PRINTS the quantity it exists to protect is the shape this repo keeps
            // paying for — the reading is there, and no run fails when it goes wrong.
            //
            // The threshold comes from GEOMETRY, not from either side's reported angle. Deriving
            // an aim criterion from the aim being measured is the same defect as taking a Y-band
            // ceiling from the drifted player: it would be satisfied by any value the actuator
            // happened to write, including no write at all.
            ctx.record("aim.geometricRequirement", deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1])
                    + " (computed from the target block centre and the eye position at the moment of the "
                    + "write, independent of either side's reading)");
            ctx.record("aim.requirementDrift",
                    requirementDrift(posAtWrite, real.position(), wantAtWrite, want));
            // WHICH of the three ways this can go wrong, named rather than left to be inferred.
            //
            // The client runs LookController as a post-filter at the end of every client tick: it
            // rewrites yaw/pitch, verbatim when the tick is snap-exempt (aimAtBlockSnap asks for
            // that) and otherwise clamped to 30°/tick yaw and 20°/tick pitch. So a red here has a
            // third cause besides "the write failed": the write landing and then being filtered or
            // overwritten by a later actuator. The two samples already tell those apart; without
            // this row the reader has to notice that themselves, and the whole point of the
            // first sample after the action is lost the moment nobody reads it next to the later one.
            //
            // Note SETTLE_TICKS is generous enough that slewing alone cannot explain a red: the
            // measured gaps are 12-24° yaw and 26-30° pitch, and ten ticks of even the CLAMPED rate
            // covers 300° / 200°. If this row says "reverted after landing", something rewrote it,
            // not slew.
            ctx.record("aim.client.didWriteLand", clientLookNow == null || clientLookAfter == null
                    ? "could not be read; cannot judge"
                    : aimSatisfies(clientLookNow, wantAtWrite)
                            ? (aimed ? "landed and held (on target right after the action, still on target after "
                                             + SETTLE_TICKS + " ticks)"
                                     : "WARNING: reverted after landing: on target right after the action at "
                                             + renderLook(clientLookNow)
                                             + ", then " + renderLook(clientLookAfter) + " after "
                                             + SETTLE_TICKS + " ticks. The actuator write succeeded and "
                                             + "something later overwrote it; check LookController and "
                                             + "other rotation writes")
                            : (aimed ? "late: still " + renderLook(clientLookNow)
                                             + " right after the action, on target only after " + SETTLE_TICKS
                                             + " ticks. The criterion passes, but this path does not "
                                             + "take effect when its reply lands"
                                     : "WARNING: never landed: the geometric requirement is not met "
                                             + "either right after the action or after " + SETTLE_TICKS
                                             + " ticks. The write itself had no effect, which is what "
                                             + "a genuinely broken route looks like"));
            // ---- verdicts, all of them after every row above is safely in the file ----------
            // Instrument first: an unreadable client side makes every agreement row above meaningless.
            ctx.check(clientSlotAfter != null && clientLookAfter != null)
                    .as("the client-side readings are available; without them every agreement row above "
                            + "is meaningless")
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
                    .as("precondition: before the action the client is not on the target slot (before "
                            + render(clientSlotBefore) + ", target " + targetSlot + "). If this does not "
                            + "hold, the criterion below can pass with zero contribution")
                    .isTrue();
            ctx.check(parkedAway)
                    .as("precondition: before the action the client's facing was parked elsewhere and "
                            + "does not meet the target's geometric requirement (after parking "
                            + renderLook(clientLookParked) + ", target requirement " + deg(wantAtWrite[0])
                            + " / " + deg(wantAtWrite[1]) + "). If this does not hold, the aim criterion "
                            + "can pass with zero contribution")
                    .isTrue();
            ctx.check(clientSlotAfter != null && clientSlotAfter == targetSlot)
                    .as("route criterion: the client's own selected slot should be " + targetSlot + ", actual "
                            + render(clientSlotAfter) + ". This fails while the defect is present: before "
                            + "the fix, the same write left the client on its pre-action slot")
                    .isTrue();
            ctx.check(aimed)
                    .as("route criterion (aim half): the client's own facing should point at "
                            + aimAt.toShortString()
                            + ", geometric requirement " + deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1])
                            + ", actual " + renderLook(clientLookAfter)
                            + ", difference " + aimGap(clientLookAfter, wantAtWrite)
                            + ". This fails while the defect is present; for evidence see the "
                            + "aim.negativeControl row of wd.actuatorSplitOnAnAdoptedBody in the same run, "
                            + "which applies the same predicate to the server-path reading (no number is "
                            + "hard-coded here: it stops holding as soon as the standing position changes; "
                            + "a gap once measured at 12-24° was 5.20° on a later run). "
                            + "On failure, read the aim.client.didWriteLand and aim.requirementDrift rows "
                            + "first to tell the causes apart")
                    .isTrue();
        };
    }

    // ------------------------------------------------------------------ the client side

    /**
     * The client's own selected slot and {yaw, pitch}, each null when {@code mc.client.player} did not
     * report it.
     *
     * <p>Null rather than a sentinel number, and rendered as {@code unavailable/<reason>} — a slot of
     * {@code -1} or {@code 0} standing in for "not read" is the exact confusion this repo keeps
     * paying for, where 0 means "not computed yet" and is read as "computed as zero".
     */
    private record ClientReading(Integer slot, float[] look) {
        static ClientReading of(ClientContracts.ClientPlayer cp) {
            return new ClientReading(cp.inventory().map(ClientContracts.Inventory::selectedSlot).orElse(null),
                    cp.look().map(l -> new float[]{(float) l.yaw(), (float) l.pitch()}).orElse(null));
        }

        @Override
        public String toString() { return "slot " + render(slot) + ", look " + renderLook(look); }
    }

    /**
     * Asks the client for its reading and continues the scene when it lands; a call that fails fails
     * the scene. The reply comes from the client thread, which the scene's server thread may not wait for.
     */
    private static void readClient(SceneContext ctx, String tag, Consumer<ClientReading> then) {
        ask(ctx, tag, ClientApi.of(self()).player(new NoParams()).thenApply(ClientReading::of), then);
    }

    /** Turns the client's own player to face {@code cell}'s centre, on the client thread. */
    private static CompletableFuture<TestHarnessContracts.Pressed> aimSelf(BlockPos cell) {
        return Harness.pressLater(Harness.keys(ClientHelm.SELF).aimAt(Targets.pos(cell)));
    }

    /**
     * The client's own player, on an integrated server — the one topology where its server copy and
     * its {@code LocalPlayer} share a JVM. Skips anywhere else.
     *
     * <p>A player entity no client owns would make "both sides agree" trivially true, and the twin
     * would certify the route on the strength of never having tested it. {@link SceneBot#humanPlayers}
     * answers an integrated server with its singleplayer owner only, which a bot can never be.
     */
    private static ServerPlayer clientsPlayer(SceneContext ctx) {
        // Both copies are needed: the server's is here, the LocalPlayer is read through mc.client.player — this
        // JVM's on an integrated server, the joined client's over its RPC port on a dedicated one.
        MinecraftServer server = ctx.server();
        if (server == null || (server.isDedicatedServer() && !ClientRegistry.remote())) {
            ctx.skip("This scene needs a server-side player entity and a real LocalPlayer it can read: an integrated"
                    + " server, or a dedicated one that names its joined client's driver (" + ClientRegistry.PORT_FILE + ")");
        }
        List<ServerPlayer> humans = SceneBot.humanPlayers(ctx);
        if (humans.isEmpty()) {
            ctx.skip("The server has no real player: the client has not joined the world yet, or has disconnected");
        }
        ServerPlayer real = humans.get(0);
        ctx.record("body", real.getGameProfile().getName() + " (" + real.getClass().getSimpleName()
                + (server.isDedicatedServer() ? ", the joined client's player)" : ", the singleplayer owner)"));
        return real;
    }

    /**
     * How far the client's own aim may sit from the geometric requirement before this scene calls it
     * unaimed.
     *
     * <p>Five degrees, and the number is chosen against the MEASURED defect rather than picked for
     * feeling safe: the ruler scene recorded gaps of 12-24° in yaw and 26-30° in pitch, so five
     * separates "the aim took effect" from "it had no effect at all" by a wide margin while leaving room for the eye-height
     * and sub-tick position differences between the server and client players. A tolerance tuned tighter would make
     * this criterion report the difference between two healthy implementations.
     */
    private static final float AIM_TOLERANCE_DEG = 5.0f;

    /**
     * How much the aim REQUIREMENT moved while the scene waited, and whether that invalidates the
     * verdict.
     *
     * <p>An {@code aimAtBlock} stores <b>angles</b>, not a target. Once written, the player moving
     * makes them stale, and nothing in the actuator notices. At this scene's range (~8.6 blocks
     * horizontally) roughly three quarters of a block of drift is worth the entire
     * {@link #AIM_TOLERANCE_DEG} tolerance — so a player that got pushed, fell, or was shoved by a mob
     * during the settle would make the aim criterion go red <b>on a perfectly healthy actuator</b>.
     *
     * <p>This row exists so that failure can never be silent. Without it, "the client's facing is
     * wrong" has two causes that print identically, and the reader has no way to tell "the actuator
     * had no effect" from "the bot moved and the angles went stale" — which is precisely the
     * ambiguity that costs rounds in this repo.
     * The criterion itself is judged against the write-time requirement, so movement does not
     * actually change the verdict; this row is what tells a reader that, instead of asking them to
     * take it on faith.
     */
    private static String requirementDrift(Vec3 from, Vec3 to, float[] wantAtWrite, float[] wantNow) {
        double moved = from.distanceTo(to);
        float dy = Math.abs(Mth.wrapDegrees(wantNow[0] - wantAtWrite[0]));
        float dp = Math.abs(wantNow[1] - wantAtWrite[1]);
        boolean stale = Math.max(dy, dp) >= AIM_TOLERANCE_DEG;
        return (stale ? "WARNING: " : "") + "the bot moved " + String.format(Locale.ROOT, "%.3f", moved)
                + " blocks (" + pos(from) + " → " + pos(to) + "), so the geometric requirement changed from "
                + deg(wantAtWrite[0]) + " / " + deg(wantAtWrite[1]) + " to "
                + deg(wantNow[0]) + " / " + deg(wantNow[1]) + ", a difference of " + deg(dy) + "° / "
                + deg(dp) + "°"
                + (stale
                        ? ": this uses up the entire " + deg(AIM_TOLERANCE_DEG) + "° tolerance. The criterion "
                                + "compares against the requirement at write time, so the verdict still "
                                + "stands; but the bot did move in this run, so take that into account when "
                                + "reading the other rows"
                        : ", far below the " + deg(AIM_TOLERANCE_DEG) + "° tolerance. The bot essentially "
                                + "did not move, so the aim half's pass or fail can only come from the "
                                + "actuator itself");
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
     * the control, and the control would go on reporting "valid" about a predicate that no longer
     * exists. Sharing the method makes that divergence impossible rather than merely unlikely.
     */
    private static boolean aimSatisfies(float[] look, float[] want) {
        return look != null
                && Math.abs(Mth.wrapDegrees(look[0] - want[0])) <= AIM_TOLERANCE_DEG
                && Math.abs(look[1] - want[1]) <= AIM_TOLERANCE_DEG;
    }

    /**
     * Candidate cells to aim at, spread across quadrants and above/below the eye.
     *
     * <p>Never picked by index — see {@link #chooseAimTarget}. A fixed choice is what put a 5.21°
     * yaw gap into the first green run: {@code offset(7,-3,5)} happened to sit almost exactly along
     * the player's spawn facing, so the yaw half of the test asked the actuator to turn five degrees
     * and the whole reading rested on pitch. The measurement looked two-dimensional and was not.
     */
    private static final int[][] AIM_CANDIDATES = {
            {7, -3, 5}, {-7, -3, -5}, {7, -3, -5}, {-7, -3, 5},
            {7, 4, 5}, {-7, 4, -5}, {5, -5, -8}, {-5, -5, 8},
    };

    /**
     * The candidate cell that forces the LARGEST movement in BOTH yaw and pitch from where the player
     * currently looks — chosen by maximising the smaller of the two gaps.
     *
     * <p>Maximising the <i>minimum</i> is the whole point. Being far in one component is enough to
     * make the aim predicate false, so a control would still read "valid" — but it would prove
     * nothing about the other component, and the run would report a two-axis test it never
     * performed. Requiring both gaps to be large is what makes "the client followed" evidence about yaw
     * <i>and</i> pitch.
     *
     * <p>Derived from the player's own SERVER-side rotation, deliberately not from the client's
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
            float score = Math.min(Math.abs(Mth.wrapDegrees(want[0] - body.getYRot())),
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
            float score = Math.min(Math.abs(Mth.wrapDegrees(want[0] - targetWant[0])),
                    Math.abs(want[1] - targetWant[1]));
            if (score > bestScore) { bestScore = score; best = cell; }
        }
        return best;
    }

    /**
     * Why {@link #chooseAimTarget} picked what it picked — <b>including its input</b>.
     *
     * <p>The chooser reads the player's current rotation, and that rotation is not a constant: earlier
     * actions in a scene change it, and the player is a human who may be facing anywhere at
     * scene start. So the chosen cell legitimately differs run to run. Recording only the OUTPUT
     * would make a prediction that misses indistinguishable between "the chooser is wrong" and
     * "the input changed" — and the second is not a defect at all. Must be called BEFORE the aim write, while the input is
     * still the value the chooser actually saw.
     */
    private static String aimChoiceEvidence(ServerPlayer body, BlockPos cell) {
        float[] w = aimFromEyeTo(body, cell);
        float dy = Math.abs(Mth.wrapDegrees(w[0] - body.getYRot()));
        float dp = Math.abs(w[1] - body.getXRot());
        return "the bot's facing when the block was chosen " + deg(body.getYRot()) + " / " + deg(body.getXRot())
                + " (this is the chooser's input; read it together with the result) → chose "
                + cell.toShortString()
                + ", requirement " + deg(w[0]) + " / " + deg(w[1])
                + ", turn needed yaw " + deg(dy) + "° / pitch " + deg(dp) + "°, weaker component "
                + deg(Math.min(dy, dp)) + "° (tolerance " + deg(AIM_TOLERANCE_DEG) + "°; "
                + "the larger this is, the more certainly both components were actually tested)";
    }

    /** How far a target sits from a look direction, per component — printed so a thin margin is
     *  visible instead of having to be recomputed by hand from two other rows. */
    private static String aimGap(float[] look, float[] want) {
        if (look == null) return "unavailable/client did not answer";
        return "yaw difference " + deg(Math.abs(Mth.wrapDegrees(look[0] - want[0])))
                + "°, pitch difference " + deg(Math.abs(look[1] - want[1]))
                + "° (tolerance " + deg(AIM_TOLERANCE_DEG) + "°)";
    }

    /**
     * The yaw/pitch that pointing at {@code cell}'s centre REQUIRES, computed from the player's eye
     * position — the independent yardstick this scene's aim criterion is judged against.
     *
     * <p>Same arithmetic both actuators perform ({@code ServerPlayerBody.aimAtBlock} and
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

    private static String render(Integer v) { return v == null ? "unavailable/client did not answer" : String.valueOf(v); }

    private static String renderLook(float[] v) {
        return v == null ? "unavailable/client did not answer" : deg(v[0]) + " / " + deg(v[1]);
    }

    private static String deg(float f) { return String.format(Locale.ROOT, "%.2f", f); }

    private static String agree(int server, Integer client) {
        if (client == null) return "unavailable/client did not answer";
        return server == client ? "agree (both " + server + ")"
                : "WARNING: disagree: server " + server + ", client " + client;
    }

    /** Angular distance between two look directions, wrapped so 359° and 1° read as 2° apart. */
    private static String drift(float yaw0, float pitch0, float yaw1, float pitch1) {
        float dy = Math.abs(Mth.wrapDegrees(yaw1 - yaw0));
        float dp = Math.abs(pitch1 - pitch0);
        if (dy < 0.01f && dp < 0.01f) return "no (neither yaw nor pitch changed)";
        return "yes: yaw difference " + deg(dy) + "°, pitch difference " + deg(dp) + "°";
    }

    // ------------------------------------------------------------------ topology

    /** Which topology this run is, asked of the game rather than echoed from a flag. */
    private static String topology(SceneContext ctx) {
        MinecraftServer server = ctx.server();
        boolean dedicated = server == null || server.isDedicatedServer();
        List<ServerPlayer> humans = SceneBot.humanPlayers(ctx);
        String kind = !dedicated ? "integratedServer"
                : humans.isEmpty() ? "dedicatedServer" : "dedicatedServerWithClient";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("realPlayers", humans.size());
        return m.toString();
    }
}

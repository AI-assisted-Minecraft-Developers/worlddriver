package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.Legs;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.minecraft.server.level.ServerPlayer;

/**
 * The hand on the run's bot: which bot it is, and how a task handed to it is started, watched and let go.
 *
 * <p><b>Two helms, one shape.</b> On a dedicated server the bot is a roster player ({@code player:sN}), and every
 * call naming it runs inline on the server thread, so a start, a status and a navigator reading all answer before
 * the call returns. On an integrated server the bot is the client's real player ({@code self}), whose calls run on
 * the client thread and answer a tick or two later; a scene thread never waits on them. So for self a start is sent
 * and its reply collected on a later tick, and the status and navigator readings are asked again each time the last
 * answer has landed — {@link #refresh} keeps one of each in flight, called from every tick of every rig wait. What
 * they report trails the client by that tick or two.
 *
 * <p><b>Ended means a status read after the start says idle.</b> A status asked before the start was accepted
 * describes the bot before the task, so for self no ask is counted until the start's reply has landed.
 */
final class JourneyHelm {

    /** What every call on the client's real player names. */
    static final String SELF = "self";

    private final String ref;
    private final ServerPlayer player;
    private final SceneBot serverBot;

    /** The task in hand: its kind, the start's reply (pending for self), and how a refused start said no. */
    private String kind;
    private CompletableFuture<? extends Responses.ActionResponse<?>> started;
    private boolean startRead;
    private String refusal;

    /** self's readings: the ask in flight and the last answer that landed. */
    private CompletableFuture<Responses.ImmediateResponse> statusAsk;
    private ControlContracts.BotStatus status;
    private boolean statusSinceStart;
    private CompletableFuture<BotDebugContracts.NavigatorReading> navigatorAsk;
    private BotDebugContracts.NavigatorReading navigator;

    private JourneyHelm(String ref, ServerPlayer player, SceneBot serverBot) {
        this.ref = ref;
        this.player = player;
        this.serverBot = serverBot;
    }

    /** A roster bot: every call answers within itself. */
    static JourneyHelm serverBot(SceneBot bot) { return new JourneyHelm(bot.ref(), bot.player(), bot); }

    /** The client's real player, {@code real} being the server's copy of it. */
    static JourneyHelm self(ServerPlayer real) { return new JourneyHelm(SELF, real, null); }

    /** What every call on this bot names. */
    String ref() { return ref; }

    /** The bot's player on this server: for self the server's copy, which trails the client by a packet. */
    ServerPlayer player() { return player; }

    /** Whether this is the client's real player rather than a roster bot. */
    boolean isSelf() { return serverBot == null; }

    /** The roster bot, or null for self. */
    SceneBot serverBotOrNull() { return serverBot; }

    // ---------------------------------------------------------------- a task

    /** Send {@code task}'s verb. A server bot's answer is read now; self's on a later {@link #ended}. */
    void start(JourneyTasks.Task task) {
        kind = task.kind();
        refusal = null;
        startRead = false;
        statusSinceStart = false;
        statusAsk = null;
        try {
            started = task.start().apply(ref).toCompletableFuture();
        } catch (ApiException e) {
            started = CompletableFuture.failedFuture(e);
        }
        if (!isSelf()) readStart();
    }

    /** The kind of the task last started, or null before any. */
    String kind() { return kind; }

    /** Why the last start was refused, or null when it was accepted (or has not answered yet). */
    String refusal() { return refusal; }

    /**
     * Whether the task {@link #start} sent has ended: refused, or idle on a status read after it was accepted.
     * True when nothing was ever started.
     */
    boolean ended() {
        if (started == null) return true;
        if (!started.isDone()) return false;
        if (!startRead) readStart();
        if (refusal != null) return true;
        if (!isSelf()) {
            status = statusNow();
            return !status.busy();
        }
        CompletableFuture<Responses.ImmediateResponse> f = statusAsk;
        if (f != null && f.isDone()) {
            ControlContracts.BotStatus landed = landedStatus(f);
            if (landed != null) {
                status = landed;
                if (statusSinceStart && !landed.busy()) return true;
            }
            f = null;
        }
        if (f == null) {
            statusAsk = askStatus();
            statusSinceStart = true;
        }
        return false;
    }

    private void readStart() {
        startRead = true;
        try {
            Responses.ActionResponse<?> r = started.join();
            if (r instanceof Responses.Refused<?> no) refusal = no.reason().wireName() + ": " + no.message();
            else if (!(r instanceof Responses.Accepted<?>)) refusal = "answered " + r + ", not an action";
        } catch (RuntimeException e) {
            ApiException a = ApiException.find(e);
            refusal = "the call failed: " + (a != null ? a.error() : e);
        }
    }

    /** Stop whatever the bot is doing and let go of its keys; self's answer is not waited for. */
    void cancel() {
        if (isSelf()) {
            Legs.cancel(ref);
            return;
        }
        Calls.now(ControlApi.of(Calls.api()).cancel(new ControlContracts.CancelParams(Optional.empty(), Optional.of(ref),
                Optional.empty())));
    }

    // ---------------------------------------------------------------- readings

    /**
     * The bot's status: read now for a server bot, the last that landed for self (null before any did).
     */
    ControlContracts.BotStatus status() {
        if (!isSelf()) status = statusNow();
        return status;
    }

    /**
     * The navigator of the bot's running task, or of the last one a caller started: read now for a server bot,
     * the last that landed for self (null before any did).
     */
    BotDebugContracts.NavigatorReading navigator() {
        if (!isSelf()) {
            navigator = Calls.now(DebugApi.of(Calls.api()).navigator(BotDebugContracts.NavigatorParams.of(ref)));
            return navigator;
        }
        CompletableFuture<BotDebugContracts.NavigatorReading> f = navigatorAsk;
        if (f != null && f.isDone() && !f.isCompletedExceptionally()) navigator = f.join();
        return navigator;
    }

    /**
     * Open a new walk in the bot's navigator telemetry ({@code mc.debug.navigator}'s {@code newLeg}), the boundary
     * every per-walk budget counts from. A server bot's now; self's is queued on the client thread ahead of any
     * task started after it, so the walk that follows still begins inside it.
     */
    void newLeg() {
        var f = DebugApi.of(Calls.api()).navigator(new BotDebugContracts.NavigatorParams(ref, true));
        if (!isSelf()) navigator = Calls.now(f);
    }

    /** Keep one status and one navigator ask in flight for self, never while a start's reply is pending. */
    void refresh() {
        if (!isSelf()) return;
        if (started != null && !started.isDone()) return;
        CompletableFuture<Responses.ImmediateResponse> s = statusAsk;
        if (s == null || s.isDone()) {
            if (s != null) {
                ControlContracts.BotStatus landed = landedStatus(s);
                if (landed != null) status = landed;
            }
            statusAsk = askStatus();
            if (started != null && startRead) statusSinceStart = true;
        }
        CompletableFuture<BotDebugContracts.NavigatorReading> n = navigatorAsk;
        if (n == null || n.isDone()) {
            if (n != null && !n.isCompletedExceptionally()) navigator = n.join();
            navigatorAsk = DebugApi.of(Calls.api()).navigator(BotDebugContracts.NavigatorParams.of(ref)).toCompletableFuture();
        }
    }

    private ControlContracts.BotStatus statusNow() {
        ControlContracts.BotStatus s = landedStatus(askStatus());
        if (s == null) throw new IllegalStateException("mc.bot.status " + ref + " did not answer within the call");
        return s;
    }

    private CompletableFuture<Responses.ImmediateResponse> askStatus() {
        return ControlApi.of(Calls.api()).status(new ControlContracts.StatusParams(Optional.of(ref))).toCompletableFuture();
    }

    /** A landed status answer's bot status, or null when it failed, is pending or names none. */
    private static ControlContracts.BotStatus landedStatus(CompletableFuture<Responses.ImmediateResponse> f) {
        if (!f.isDone() || f.isCompletedExceptionally()) return null;
        return f.join() instanceof Responses.Status s ? s.status().orElse(null) : null;
    }

    // ---------------------------------------------------------------- the end of the run

    /** Let the bot go: a roster bot leaves the world, self is handed back with its task cancelled. */
    void release() {
        if (serverBot != null) {
            serverBot.remove();
            return;
        }
        Legs.cancel(ref);
    }

    /** Keep a roster bot on the tick list from now on, idle or not, as a connected player is ticked. */
    void keepTicking() {
        if (serverBot != null) Harness.unpark(ref);
    }
}

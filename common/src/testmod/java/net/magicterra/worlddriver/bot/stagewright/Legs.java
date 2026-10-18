package net.magicterra.worlddriver.bot.stagewright;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;

/**
 * A task a scene starts and waits out, the same way for a server bot and for self. A server bot's calls answer
 * within themselves, so its status is read the tick it is asked; self's hop to the client thread, so its status
 * is asked again each time the last answer has landed, and trails the client by a tick or two. A scene thread
 * never waits on either.
 */
public final class Legs {
    private Legs() {}

    /** How long a start may take to answer, in server ticks. */
    private static final int REPLY_TICKS = 40;

    /** Something that reads the bot on every tick of a leg. */
    public interface Watcher { void tick(int tick); }

    /**
     * How a leg ended. {@code ended}: the bot let go of the task within the budget; {@code ticks}: how many it
     * waited; {@code status}: the last status that landed, null when none did.
     */
    public record End(boolean ended, int ticks, ControlContracts.BotStatus status) {
        /** The slot {@code kind} names in the last status, or empty. */
        public Optional<ControlContracts.Slot> slot(java.util.function.Function<ControlContracts.Slots, ControlContracts.Slot> kind) {
            return status == null ? Optional.empty() : Optional.of(kind.apply(status.slots()));
        }
    }

    /**
     * Waits for {@code order} to be accepted, then until {@code bot} is no longer busy or {@code ticks} run out,
     * calling {@code watcher} every tick, and continues with how it ended. A refused order fails the scene; a
     * task still running when the budget is spent is cancelled.
     */
    public static <R> void run(SceneContext ctx, String tag, String bot, CompletionStage<Responses.ActionResponse<R>> order,
                               int ticks, Watcher watcher, Consumer<End> then) {
        CompletableFuture<Responses.ActionResponse<R>> started = order.toCompletableFuture();
        ctx.await(started::isDone).within(REPLY_TICKS).then(() -> {
            ctx.record(tag + ".started", String.valueOf(Calls.started(tag, Calls.now(started))));
            await(ctx, bot, ticks, watcher, then);
        });
    }

    /** {@link #run} for a task already started: waits until {@code bot} is no longer busy or {@code ticks} run out. */
    public static void await(SceneContext ctx, String bot, int ticks, Watcher watcher, Consumer<End> then) {
        AtomicReference<CompletableFuture<Responses.ImmediateResponse>> inFlight = new AtomicReference<>();
        AtomicReference<ControlContracts.BotStatus> last = new AtomicReference<>();
        int[] waited = { 0 };
        boolean[] ended = { false };
        ctx.await(() -> {
            if (watcher != null) watcher.tick(waited[0]);
            CompletableFuture<Responses.ImmediateResponse> f = inFlight.get();
            if (f == null || f.isDone()) {
                if (idle(f, last)) { ended[0] = true; return true; }
                f = askStatus(bot);
                inFlight.set(f);
                if (idle(f, last)) { ended[0] = true; return true; }
            }
            return ++waited[0] >= ticks;
        }).within(ticks + 100).then(() -> {
            if (!ended[0]) cancel(bot);
            then.accept(new End(ended[0], waited[0], last.get()));
        });
    }

    /** Stops {@code bot}'s task without waiting for the answer. */
    public static void cancel(String bot) {
        ControlApi.of(Calls.api()).cancel(new ControlContracts.CancelParams(Optional.empty(), Optional.of(bot), Optional.empty()));
    }

    private static CompletableFuture<Responses.ImmediateResponse> askStatus(String bot) {
        return ControlApi.of(Calls.api()).status(new ControlContracts.StatusParams(Optional.of(bot))).toCompletableFuture();
    }

    /** Whether a landed status says the bot is idle; keeps it in {@code last}. A failed or pending one is not idle. */
    private static boolean idle(CompletableFuture<Responses.ImmediateResponse> f,
                                AtomicReference<ControlContracts.BotStatus> last) {
        if (f == null || !f.isDone() || f.isCompletedExceptionally()) return false;
        if (!(f.join() instanceof Responses.Status s) || s.status().isEmpty()) return false;
        last.set(s.status().get());
        return !s.status().get().busy();
    }
}

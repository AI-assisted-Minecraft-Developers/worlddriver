package net.magicterra.worlddriver.client.internal;

import net.magicterra.worlddriver.api.methods.ClientContracts.ChatHistory;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChatHistoryParams;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChatKind;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChatLine;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChatSendParams;
import net.magicterra.worlddriver.api.methods.ClientContracts.ChatSent;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientRefusal;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.ClientContracts.HistoryLine;
import net.magicterra.worlddriver.api.methods.ClientContracts.LaterChat;
import net.magicterra.worlddriver.api.methods.ClientContracts.OverlaysCleared;
import net.magicterra.worlddriver.api.methods.ClientContracts.OverlaysParams;
import net.magicterra.worlddriver.api.methods.ClientContracts.Refused;
import net.magicterra.worlddriver.api.methods.ClientContracts.SentAs;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static net.magicterra.worlddriver.client.internal.ClientThread.runOnClient;

/**
 * Chat send/history and HUD-overlay dismissal for {@code mc.client.chat.*} and
 * {@code mc.client.overlays}. Reads received lines from {@link ClientChatLog}
 * (packet-level, monotonic seq) — NOT from the GUI's ChatComponent, whose
 * newest-first 100-cap buffer made seq unstable and replies stale.
 */
public final class ClientChat {
    private ClientChat() {}

    /** Shared packet-tap funnel for the platform client entrypoints — ONE
     *  classifier so the loaders can't drift (disguised/profileless chat used
     *  to land as "player" on Fabric but "system" on NeoForge). ALL capture
     *  policy lives here, not at the call sites: {@code overlay} (action-bar)
     *  lines are dropped, and kind is "player" iff the line carries a real
     *  sender id — null and NIL_UUID both normalize to "system", so a loader
     *  handing a NIL sender down the player path (proxy-relayed / unsigned
     *  chat edge cases) can't reopen the drift. The echo of the local
     *  player's own chat is flagged {@code self} so reply correlation can
     *  skip it while history keeps the full transcript. Called on the client
     *  thread by the receive events. */
    public static void recordReceived(Component message, UUID sender, boolean overlay) {
        if (overlay) return;   // action bar — not part of the chat transcript
        if (Util.NIL_UUID.equals(sender)) sender = null;   // disguised chat → system
        LocalPlayer p = Minecraft.getInstance().player;
        boolean self = sender != null && p != null && sender.equals(p.getUUID());
        ClientChatLog.record(sender != null ? "player" : "system",
                message.getString(), gameTimeNow(), self);
    }

    /** Current game time for stamping received lines; 0 before a level exists. */
    private static long gameTimeNow() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }

    private static ChatKind kind(ClientChatLog.Entry e) {
        return "player".equals(e.kind()) ? ChatKind.PLAYER : ChatKind.SYSTEM;
    }

    /** The send, and where the log stood just before it. */
    private record Sent(ChatSent reply, long baseline) {}

    /**
     * Sends on the client thread; with {@code awaitReplyMs} then polls the log on the CALLING thread,
     * so ticks — including the server's response packets — keep flowing.
     */
    public static ClientReply chatSend(ChatSendParams p) {
        String t = p.text();
        Object sent = runOnClient(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.connection == null) {
                return new Refused(ClientRefusal.NO_PLAYER, "no player is connected to a server on this client");
            }
            boolean isCommand = t.startsWith("/");
            // Snapshot the log position BEFORE the send: everything recorded at
            // or after this seq arrived after our packet went out.
            long baseline = ClientChatLog.nextSeq();
            if (isCommand) {
                player.connection.sendCommand(t.substring(1));
            } else {
                player.connection.sendChat(t);
            }
            return new Sent(new ChatSent(isCommand ? SentAs.COMMAND : SentAs.CHAT, t.length(), Optional.empty()), baseline);
        });
        if (!(sent instanceof Sent s)) return (Refused) sent;
        if (p.awaitReplyMs() <= 0) return s.reply();
        return new ChatSent(s.reply().kind(), s.reply().length(), Optional.of(awaitLater(s.baseline(), p.awaitReplyMs())));
    }

    /**
     * Waits for a line other than this player's own echo to arrive after {@code baseline}, then gives
     * multi-line feedback a short settle window before collecting, so one broadcast doesn't race out the
     * command output arriving a tick later. Nothing ties the lines to the send but their timing.
     */
    private static LaterChat awaitLater(long baseline, int awaitReplyMs) {
        long started = System.currentTimeMillis();
        long deadline = started + awaitReplyMs;
        long scanned = baseline;   // advances past seen lines so polls stay O(1)
        while (System.currentTimeMillis() < deadline) {
            boolean hit = false;
            if (ClientChatLog.nextSeq() > scanned) {   // O(1) probe before copying
                for (ClientChatLog.Entry e : ClientChatLog.since(scanned)) {
                    scanned = e.seq() + 1;
                    // The server echoes our own plain-chat line back at us
                    // (since 1.19 signed chat it isn't rendered locally) —
                    // that echo is never the "reply". History keeps it.
                    if (!e.self()) hit = true;
                }
            }
            if (hit) {
                // Our own /say echo carries our sender profile → self=true → filtered like plain
                // chat; console/command-block /say is profileless → a reply.
                long settle = Math.min(deadline, System.currentTimeMillis() + 150);
                while (System.currentTimeMillis() < settle) {
                    if (!sleep()) break;
                }
                List<ChatLine> rows = new ArrayList<>();
                for (ClientChatLog.Entry e : ClientChatLog.since(baseline)) {
                    if (!e.self()) rows.add(new ChatLine(e.seq(), kind(e), e.text(), false));
                }
                if (rows.isEmpty()) continue;   // flood-evicted during settle; keep waiting
                return new LaterChat(rows, false, System.currentTimeMillis() - started);
            }
            if (!sleep()) break;
        }
        return new LaterChat(List.of(), true, System.currentTimeMillis() - started);
    }

    private static boolean sleep() {
        try {
            Thread.sleep(50L);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static ChatHistory chatHistory(ChatHistoryParams p) {
        // gameTime only needed to derive ageTicks; the log itself is thread-safe.
        long now = runOnClient(ClientChat::gameTimeNow);
        // Newest first, sliced straight off the tail — tail() never touches the
        // up-to-512 older entries the cap is about to throw away.
        List<ClientChatLog.Entry> entries = ClientChatLog.tail(p.limit(), p.sinceSeq());
        List<HistoryLine> rows = new ArrayList<>(entries.size());
        for (ClientChatLog.Entry e : entries) {
            rows.add(new HistoryLine(e.seq(), kind(e), e.text(), e.self(), Math.max(0L, now - e.gameTime())));
        }
        return new ChatHistory(ClientChatLog.nextSeq(), rows);
    }

    public static OverlaysCleared overlays(OverlaysParams p) {
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            boolean tutorial = false, toasts = false;
            Optional<String> tutorialError = Optional.empty(), toastsError = Optional.empty();
            if (p.tutorial()) {
                try {
                    // Options.tutorialStep is a plain public TutorialSteps field, not an
                    // OptionInstance — write it directly, then apply to the live Tutorial
                    // controller so the active step changes without an options-screen save.
                    mc.options.tutorialStep = TutorialSteps.NONE;
                    mc.getTutorial().setStep(TutorialSteps.NONE);
                    tutorial = true;
                } catch (Throwable t) {
                    tutorialError = Optional.of(t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            if (p.toasts()) {
                try {
                    mc.getToasts().clear();
                    toasts = true;
                } catch (Throwable t) {
                    toastsError = Optional.of(t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            return new OverlaysCleared(tutorial, toasts, tutorialError, toastsError);
        });
    }
}

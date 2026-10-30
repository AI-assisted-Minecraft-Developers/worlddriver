package net.magicterra.worlddriver.application.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatHistory;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatHistoryParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatKind;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatLine;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatSendParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatSent;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientRefusal;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.HistoryLine;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.LaterChat;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.OverlaysCleared;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.OverlaysParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Refused;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.SentAs;
import net.magicterra.worlddriver.application.client.ClientDriverApi.ChatReplies;
import net.magicterra.worlddriver.application.client.ClientDriverApi.ChatSendStart;
import net.magicterra.worlddriver.game.ClientHop;
import net.magicterra.worlddriver.game.ui.ClientChatLog;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.network.chat.Component;

/**
 * Chat send/history and HUD-overlay dismissal for {@code mc.client.chat.*} and {@code mc.client.overlays}. Reads
 * received lines from {@link ClientChatLog} (packet-level, monotonic seq) — NOT from the GUI's ChatComponent, whose
 * newest-first 100-cap buffer made seq unstable and replies stale.
 */
public final class ClientChat {
    private ClientChat() {}

    /**
     * Shared packet-tap funnel for the platform client entrypoints — ONE classifier so the loaders can't drift
     * (disguised/profileless chat used to land as "player" on Fabric but "system" on NeoForge). ALL capture policy
     * lives here, not at the call sites: {@code overlay} (action-bar) lines are dropped, and kind is "player" iff the
     * line carries a real sender id — null and NIL_UUID both normalize to "system", so a loader handing a NIL sender
     * down the player path (proxy-relayed / unsigned chat edge cases) can't reopen the drift. The echo of the local
     * player's own chat is flagged {@code self} so reply correlation can skip it while history keeps the full
     * transcript. Called on the client thread by the receive events.
     */
    public static void recordReceived(Component message, UUID sender, boolean overlay) {
        if (overlay) {
            return; // action bar — not part of the chat transcript
        }
        if (Util.NIL_UUID.equals(sender)) {
            sender = null; // disguised chat → system
        }
        LocalPlayer p = Minecraft.getInstance().player;
        boolean self = sender != null && p != null && sender.equals(p.getUUID());
        ClientChatLog.record(sender != null ? "player" : "system", message.getString(), gameTimeNow(), self);
    }

    /** Current game time for stamping received lines; 0 before a level exists. */
    private static long gameTimeNow() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }

    private static ChatKind kind(ClientChatLog.Entry e) {
        return "player".equals(e.kind()) ? ChatKind.PLAYER : ChatKind.SYSTEM;
    }

    /** Sends on the client thread. Replies are read afterwards, off it, so ticks keep flowing meanwhile. */
    public static ChatSendStart chatSend(ChatSendParams p) {
        String t = p.text();
        return ClientHop.call(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.connection == null) {
                return new ChatSendStart(
                        new Refused(ClientRefusal.NO_PLAYER, "no player is connected to a server on this client"),
                        null);
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
            return new ChatSendStart(
                    new ChatSent(isCommand ? SentAs.COMMAND : SentAs.CHAT, t.length(), Optional.empty()),
                    new Replies(baseline));
        });
    }

    /**
     * Waits for a line other than this player's own echo to arrive after {@code baseline}, then gives multi-line
     * feedback a short settle window before collecting, so one broadcast doesn't race out the command output arriving a
     * tick later. Nothing ties the lines to the send but their timing.
     */
    private static final class Replies implements ChatReplies {
        private static final long SETTLE_MS = 150;

        private final long baseline;

        private final long started = System.currentTimeMillis();

        private long scanned; // advances past seen lines so polls stay O(1)

        private long settleUntil = -1; // set by the first reply; collected once it passes

        Replies(long baseline) {
            this.baseline = baseline;
            this.scanned = baseline;
        }

        @Override
        public synchronized Optional<LaterChat> poll() {
            if (settleUntil < 0 && ClientChatLog.nextSeq() > scanned) { // O(1) probe before copying
                for (ClientChatLog.Entry e : ClientChatLog.since(scanned)) {
                    scanned = e.seq() + 1;
                    // The server echoes our own plain-chat line back at us (since 1.19 signed chat it
                    // isn't rendered locally); that echo is never the reply. History keeps it. Our own
                    // /say echo carries our sender profile, so it is filtered the same way; a console
                    // or command-block /say is profileless, so it is a reply.
                    if (!e.self() && settleUntil < 0) {
                        settleUntil = System.currentTimeMillis() + SETTLE_MS;
                    }
                }
            }
            if (settleUntil < 0 || System.currentTimeMillis() < settleUntil) {
                return Optional.empty();
            }
            List<ChatLine> rows = replies();
            if (rows.isEmpty()) { // flood-evicted during the settle; keep waiting
                settleUntil = -1;
                return Optional.empty();
            }
            return Optional.of(new LaterChat(rows, false, System.currentTimeMillis() - started));
        }

        /** A reply that came in time but whose settle the deadline cut short is still the answer. */
        @Override
        public synchronized LaterChat timedOut() {
            List<ChatLine> rows = settleUntil >= 0 ? replies() : List.of();
            return new LaterChat(rows, rows.isEmpty(), System.currentTimeMillis() - started);
        }

        private List<ChatLine> replies() {
            List<ChatLine> rows = new ArrayList<>();
            for (ClientChatLog.Entry e : ClientChatLog.since(baseline)) {
                if (!e.self()) {
                    rows.add(new ChatLine(e.seq(), kind(e), e.text(), false));
                }
            }
            return rows;
        }
    }

    public static ChatHistory chatHistory(ChatHistoryParams p) {
        // gameTime only needed to derive ageTicks; the log itself is thread-safe.
        long now = ClientHop.call(ClientChat::gameTimeNow);
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
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            boolean tutorial = false;
            boolean toasts = false;
            Optional<String> tutorialError = Optional.empty();
            Optional<String> toastsError = Optional.empty();
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

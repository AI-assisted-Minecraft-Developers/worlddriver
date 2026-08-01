package net.magicterra.worlddriver.client.internal;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.magicterra.worlddriver.client.internal.ClientThread.runOnClient;

/**
 * Chat send/history and HUD-overlay dismissal for {@code mc.client.chat.*} and
 * {@code mc.client.overlays}. Reads received lines from {@link ClientChatLog}
 * (packet-level, monotonic seq) — NOT from the GUI's ChatComponent, whose
 * newest-first 100-cap buffer made seq unstable and replies stale.
 * Extracted from {@code ClientAgentApiImpl}.
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

    public static Map<String, Object> chatSend(String text, int awaitReplyMs) {
        if (text == null || text.isEmpty()) {
            return Map.of("ok", false, "error", "text is required");
        }
        Map<String, Object> sent = runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || p.connection == null) {
                return Map.of("ok", false, "error", "no local player / not connected");
            }
            String t = text;
            boolean isCommand = t.startsWith("/");
            // Snapshot the log position BEFORE the send: everything recorded at
            // or after this seq arrived after our packet went out.
            long baseline = ClientChatLog.nextSeq();
            if (isCommand) {
                p.connection.sendCommand(t.substring(1));
            } else {
                p.connection.sendChat(t);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("kind", isCommand ? "command" : "chat");
            out.put("length", t.length());
            out.put("baselineSeq", baseline);
            return out;
        });
        // Reply wait runs OFF the client thread (ClientChatLog is thread-safe)
        // so ticks — including the server's response packets — keep flowing.
        if (awaitReplyMs > 0 && Boolean.TRUE.equals(sent.get("ok"))) {
            long baseline = (Long) sent.remove("baselineSeq");
            long deadline = System.currentTimeMillis() + Math.min(awaitReplyMs, 30000);
            long started = System.currentTimeMillis();
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
                    // A non-self line landed. Give multi-line feedback a short
                    // settle window before collecting, so one busy-server
                    // broadcast doesn't race out the actual command output
                    // arriving a tick later. (Our own /say echo carries our
                    // sender profile → self=true → filtered like plain chat;
                    // console//command-block /say is profileless → a reply.)
                    long settle = Math.min(deadline, System.currentTimeMillis() + 150);
                    while (System.currentTimeMillis() < settle) {
                        try { Thread.sleep(50L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (ClientChatLog.Entry e : ClientChatLog.since(baseline)) {
                        if (!e.self()) rows.add(e.row());
                    }
                    if (rows.isEmpty()) continue;   // flood-evicted during settle; keep waiting
                    Map<String, Object> mut = new LinkedHashMap<>(sent);
                    mut.put("reply", rows.get(0));
                    if (rows.size() > 1) mut.put("replyExtra", rows.subList(1, rows.size()));
                    mut.put("replyMs", System.currentTimeMillis() - started);
                    return mut;
                }
                try { Thread.sleep(50L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
            Map<String, Object> mut = new LinkedHashMap<>(sent);
            mut.put("replyTimeout", true);
            mut.put("replyMs", System.currentTimeMillis() - started);
            return mut;
        }
        // Strip internal field before returning.
        if (sent.containsKey("baselineSeq")) {
            Map<String, Object> mut = new LinkedHashMap<>(sent);
            mut.remove("baselineSeq");
            return mut;
        }
        return sent;
    }

    public static Map<String, Object> chatHistory(int limit, long sinceSeq) {
        int cap = Math.max(1, Math.min(256, limit));
        // gameTime only needed to derive ageTicks; the log itself is thread-safe.
        long now = runOnClient(ClientChat::gameTimeNow);
        // Newest first, sliced straight off the tail — tail() never touches the
        // up-to-512 older entries the cap is about to throw away.
        List<ClientChatLog.Entry> entries = ClientChatLog.tail(cap, sinceSeq);
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (ClientChatLog.Entry e : entries) {
            Map<String, Object> row = e.row();
            row.put("ageTicks", Math.max(0L, now - e.gameTime()));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", rows.size());
        out.put("nextSeq", ClientChatLog.nextSeq());
        out.put("messages", rows);
        return out;
    }

    public static Map<String, Object> overlays(boolean tutorial, boolean toasts) {
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            if (tutorial) {
                try {
                    // Options.tutorialStep is a plain public TutorialSteps field, not an
                    // OptionInstance — write it directly, then apply to the live Tutorial
                    // controller so the active step changes without an options-screen save.
                    mc.options.tutorialStep = TutorialSteps.NONE;
                    mc.getTutorial().setStep(TutorialSteps.NONE);
                    out.put("tutorial", "NONE");
                } catch (Throwable t) {
                    out.put("tutorialError", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            if (toasts) {
                try {
                    mc.getToasts().clear();
                    out.put("toasts", "cleared");
                } catch (Throwable t) {
                    out.put("toastsError", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            return out;
        });
    }

}

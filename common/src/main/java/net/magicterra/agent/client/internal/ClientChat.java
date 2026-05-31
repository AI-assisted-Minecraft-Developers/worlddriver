package net.magicterra.agent.client.internal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.magicterra.agent.client.internal.ClientThread.runOnClient;
import java.util.Locale;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import net.minecraft.network.chat.Component;
import java.util.Collections;
import net.minecraft.client.tutorial.TutorialSteps;

/**
 * Chat send/history and HUD-overlay dismissal for {@code mc.client.chat.*} and
 * {@code mc.client.overlays}. Holds the cached {@code ChatComponent.allMessages}
 * reflective handle. Extracted from {@code ClientAgentApiImpl}.
 */
public final class ClientChat {
    private ClientChat() {}

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
            // Snapshot the size of allMessages BEFORE the send so the reply
            // poll can detect the next inbound message even if it's identical
            // text to a prior one.
            int baseline = readChatSize(mc);
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
        // Reply wait runs OFF the client thread so other ticks (including the
        // server's response packet handling on the client side) can fire.
        if (awaitReplyMs > 0 && Boolean.TRUE.equals(sent.get("ok"))) {
            int baseline = (Integer) sent.remove("baselineSeq");
            long deadline = System.currentTimeMillis() + Math.min(awaitReplyMs, 30000);
            long started = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                List<Map<String, Object>> reply = runOnClient(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    int now = readChatSize(mc);
                    if (now > baseline) {
                        return chatLinesSince(mc, baseline);
                    }
                    return List.of();
                });
                if (!reply.isEmpty()) {
                    Map<String, Object> mut = new LinkedHashMap<>(sent);
                    mut.put("reply", reply.get(0));
                    if (reply.size() > 1) mut.put("replyExtra", reply.subList(1, reply.size()));
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

    public static Map<String, Object> chatHistory(int limit, int sinceSeq) {
        int cap = Math.max(1, Math.min(256, limit));
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            List<Map<String, Object>> rows = chatLinesSince(mc, sinceSeq);
            // Newest first, then trim.
            Collections.reverse(rows);
            if (rows.size() > cap) rows = rows.subList(0, cap);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("count", rows.size());
            out.put("nextSeq", readChatSize(mc));
            out.put("messages", rows);
            return out;
        });
    }

    public static Map<String, Object> overlays(boolean tutorial, boolean toasts) {
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            if (tutorial) {
                try {
                    Class<?> stepsCls = Class.forName("TutorialSteps");
                    Object none = Enum.valueOf((Class<Enum>) stepsCls, "NONE");
                    // Options.tutorialStep is a plain TutorialSteps field, not an
                    // OptionInstance — write it directly. (OptionInstance applies
                    // to most options but tutorialStep stayed simple.)
                    Field optField = mc.options.getClass().getDeclaredField("tutorialStep");
                    optField.setAccessible(true);
                    optField.set(mc.options, none);
                    // Apply immediately to the live Tutorial controller so the
                    // active step changes without waiting for an options-screen save.
                    Object tut = mc.getTutorial();
                    Method setStep = tut.getClass().getMethod("setStep", stepsCls);
                    setStep.invoke(tut, none);
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

    /** Cached reflective handle for {@code ChatComponent.allMessages}. Looked up
     *  once per JVM since obfuscated names are stable per dev mappings build. */
    private static volatile Field CHAT_ALL_FIELD;

    private static Field resolveAllMessagesField(Object chat) {
        Field f = CHAT_ALL_FIELD;
        if (f != null) return f;
        // Prefer the MojMap name "allMessages"; fall back to scanning fields
        // for a List type if obfuscated.
        try {
            f = chat.getClass().getDeclaredField("allMessages");
        } catch (NoSuchFieldException nsfe) {
            for (Field cand : chat.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(cand.getType())) {
                    String n = cand.getName().toLowerCase(Locale.ROOT);
                    // allMessages typically has "all" in the name even when obfuscated
                    // mappings are not in play; pick the first List<?> field as a last resort.
                    if (n.contains("all") || n.contains("message") || f == null) {
                        f = cand;
                    }
                }
            }
        }
        if (f != null) {
            f.setAccessible(true);
            CHAT_ALL_FIELD = f;
        }
        return f;
    }

    private static int readChatSize(Minecraft mc) {
        try {
            Object chat = mc.gui.getChat();
            Field f = resolveAllMessagesField(chat);
            if (f == null) return 0;
            List<?> list = (List<?>) f.get(chat);
            return list == null ? 0 : list.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static List<Map<String, Object>> chatLinesSince(Minecraft mc, int sinceSeq) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Object chat = mc.gui.getChat();
            Field f = resolveAllMessagesField(chat);
            if (f == null) return out;
            List<?> list = (List<?>) f.get(chat);
            if (list == null) return out;
            int size = list.size();
            long nowTick = mc.level != null ? mc.level.getGameTime() : 0L;
            // Iterate stable-order from oldest seq.
            for (int i = Math.max(0, sinceSeq); i < size; i++) {
                Object gm = list.get(i);
                if (gm == null) continue;
                String plain = "";
                long addedTick = 0L;
                try {
                    Method content = gm.getClass().getMethod("content");
                    Object comp = content.invoke(gm);
                    if (comp instanceof Component c) plain = c.getString();
                } catch (Throwable ignore) {}
                try {
                    Method addedTime = gm.getClass().getMethod("addedTime");
                    Object v = addedTime.invoke(gm);
                    if (v instanceof Number n) addedTick = n.longValue();
                } catch (Throwable ignore) {}
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", i);
                row.put("ageTicks", Math.max(0L, nowTick - addedTick));
                row.put("text", plain);
                out.add(row);
            }
        } catch (Throwable ignore) {}
        return out;
    }
}

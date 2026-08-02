package net.magicterra.worlddriver.client.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative client-side chat readback buffer for {@code mc.client.chat.*}.
 *
 * <p>Fed at the packet layer by the platform client entrypoints (Fabric
 * {@code ClientReceiveMessageEvents.CHAT/GAME}, NeoForge
 * {@code ClientChatReceivedEvent}) through {@link ClientChat#recordReceived},
 * so it sees every player/system line the server delivers and hands out a
 * <em>monotonic</em> {@code seq}. The previous implementation reflected on the
 * GUI's {@code ChatComponent.allMessages}, which is newest-first, hard-capped
 * at 100 (size saturates, so "new message arrived" checks go blind on busy
 * servers) and re-indexes on every arrival — the root cause of the 2026-06-08
 * "chat is not a usable readback channel" feedback. Overlay (action-bar) lines
 * are excluded by the feeders.
 *
 * <p>Boundary: lines another mod (or vanilla's chat-validation error path)
 * writes straight into the chat HUD via {@code ChatComponent.addMessage} never
 * cross the packet layer and are NOT captured here.
 *
 * <p>Seq spaces: this log's {@code seq} and the event buffer's cursor
 * ({@code DriverApi.events} / {@code mc.observe.eventsSince}) are SEPARATE
 * monotonic sequences — never feed one's cursor to the other. The
 * {@code client.message} event stream drains this log, and its rows carry the
 * chat {@code seq} (see {@link Entry#row()}), so consumers can reconcile an
 * event row against {@code mc.client.chat.history} by that field.
 *
 * <p>Pure JVM on purpose: no Minecraft client classes, so dedicated-server
 * gametests can exercise the seq/eviction semantics on a private
 * {@link Buffer} without touching the live session's log.
 */
public final class ClientChatLog {
    private ClientChatLog() {}

    /** One received line. {@code kind} is "player" (chat with a real sender
     *  profile) or "system" (command feedback, /say broadcasts, disguised chat,
     *  server messages). {@code self} marks the echo of the local player's own
     *  chat — reply correlation must skip it, history keeps it. */
    public record Entry(long seq, long gameTime, String kind, String text, boolean self) {
        /** The canonical wire row {@code {seq,kind,text,self}} — shared by
         *  chat.history, awaitReply and the client.message event stream so the
         *  three surfaces can't drift. Mutable so callers can add fields
         *  (chat.history adds {@code ageTicks}). */
        public Map<String, Object> row() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", seq);
            row.put("kind", kind);
            row.put("text", text);
            row.put("self", self);
            return row;
        }
    }

    /** Eviction cap. Generous vs the GUI's 100 — awaitReply polls only ever
     *  look at the tail, history rarely wants more than a screenful. */
    private static final int CAP = 512;

    /** Instance core so tests can exercise semantics in isolation; the live
     *  session goes through the static facade over {@link #GLOBAL}. */
    public static final class Buffer {
        private final ArrayDeque<Entry> log = new ArrayDeque<>();
        private long nextSeq = 0;

        /** Append a line. Called from the client thread by the platform hooks;
         *  synchronized because awaitReply polls read from an RPC worker thread.
         *  A null {@code kind} is normalized to "system" — the drain builds
         *  rows from these fields and must never NPE the tick. */
        public synchronized void record(String kind, String text, long gameTime, boolean self) {
            if (text == null) return;
            log.addLast(new Entry(nextSeq++, gameTime, kind == null ? "system" : kind, text, self));
            while (log.size() > CAP) log.removeFirst();
        }

        /** The seq the NEXT recorded line will get. Monotonic; never rewinds.
         *  O(1) — poll this before {@link #since} on hot paths. */
        public synchronized long nextSeq() {
            return nextSeq;
        }

        /** All retained entries with {@code seq >= sinceSeq}, oldest first.
         *  O(k) in the lines returned: walks back from the newest entry and
         *  stops at the first {@code seq < sinceSeq} (seq is contiguous, the
         *  deque is ordered), instead of scanning all {@value #CAP} retained
         *  lines — this runs under the global lock on the client tick drain
         *  and the awaitReply poll, so a full scan per new line would queue
         *  {@link #record} behind it during chat floods. */
        public synchronized List<Entry> since(long sinceSeq) {
            if (sinceSeq >= nextSeq || log.isEmpty()) return List.of();
            List<Entry> out = new ArrayList<>();
            for (Iterator<Entry> it = log.descendingIterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (e.seq() < sinceSeq) break;
                out.add(e);
            }
            Collections.reverse(out);
            return out;
        }

        /** Drop every retained entry, returning how many were discarded. The
         *  monotonic {@code seq} is deliberately NOT rewound (the class contract:
         *  seq never rewinds) — a cleared buffer keeps handing out strictly
         *  increasing seqs, so any cursor a caller still holds stays valid and
         *  simply finds nothing at/after it. Used by the client-pool entry reset
         *  ({@code mc.test.reset}) to wipe cross-run chat readback. */
        public synchronized int clear() {
            int n = log.size();
            log.clear();
            return n;
        }

        /** Up to {@code cap} newest entries with {@code seq >= sinceSeq},
         *  NEWEST first — the chat.history shape, sliced straight off the tail
         *  without materializing the older retained entries the cap would
         *  discard. */
        public synchronized List<Entry> tail(int cap, long sinceSeq) {
            if (cap <= 0 || sinceSeq >= nextSeq || log.isEmpty()) return List.of();
            List<Entry> out = new ArrayList<>(Math.min(cap, log.size()));
            for (Iterator<Entry> it = log.descendingIterator(); it.hasNext() && out.size() < cap; ) {
                Entry e = it.next();
                if (e.seq() < sinceSeq) break;
                out.add(e);
            }
            return out;
        }
    }

    private static final Buffer GLOBAL = new Buffer();

    public static void record(String kind, String text, long gameTime, boolean self) {
        GLOBAL.record(kind, text, gameTime, self);
    }

    public static long nextSeq() {
        return GLOBAL.nextSeq();
    }

    /** Wipe the live session's retained chat readback (seq preserved, never rewinds).
     *  Returns the number of entries discarded. See {@link Buffer#clear()}. */
    public static int clear() {
        return GLOBAL.clear();
    }

    public static List<Entry> since(long sinceSeq) {
        return GLOBAL.since(sinceSeq);
    }

    public static List<Entry> tail(int cap, long sinceSeq) {
        return GLOBAL.tail(cap, sinceSeq);
    }
}

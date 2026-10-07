package net.magicterra.worlddriver.client;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.BlockScan;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatHistory;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatHistoryParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ChatSendParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientPlayer;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.LaterChat;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.OverlaysCleared;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.OverlaysParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Screenshot;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ScreenshotParams;
import net.magicterra.worlddriver.api.methods.query.QueryContracts;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ClickParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.CloseParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Closed;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.HotbarParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeybindParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.MenuContainer;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.MoveParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Moved;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ReplaceParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ScreenInfo;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ScreenTree;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SliderParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SlotClickParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.TypeParams;

/**
 * Client-side surface for the agent driver. A platform-specific implementation
 * holds direct references to {@code net.minecraft.client.*} and is registered
 * through {@link ClientHooks} during client init. Dedicated servers never load
 * it, and every {@code mc.client.*} method answers capability_unavailable there.
 *
 * <p>Each method may be called from any thread: the ones that read or write the client
 * run their body on the client thread themselves. {@link #key}, {@link #keybind} and
 * {@link #screenshot} may hold their caller for client ticks or a fresh frame, so they are
 * for a thread that may wait. A write given a
 * {@code contextId} checks it on the client thread immediately before acting.
 *
 * <p>The few methods still answering a Map serve methods outside the client group
 * ({@code mc.observe.player}, {@code mc.query} and {@code mc.wait.worldReady} on a client
 * with no server attached).
 */
public interface ClientDriverApi {
    /** The hop onto this client's thread. */
    ServerThreadHop hop();

    ScreenInfo readScreenInfo();

    /** {@link #readScreenInfo} as the untyped wait routes read it. */
    @SuppressWarnings("unchecked")
    default Map<String, Object> screenInfo() {
        return (Map<String, Object>) ScreenContracts.SCREEN_INFO.encode(readScreenInfo());
    }

    ScreenTree readScreenTree();

    Closed closeScreen(CloseParams p);

    ClientReply click(ClickParams p);

    /** Sends a real container click, the same path vanilla's slot click takes. */
    ClientReply slotClick(SlotClickParams p);

    Moved mouseMove(MoveParams p);

    /** Selects the held hotbar slot and tells the server. */
    ClientReply setHotbarSlot(HotbarParams p);

    /** Appends at the cursor of the focused widget. */
    ClientReply typeText(TypeParams p);

    /** Overwrites a text box's whole contents. */
    ClientReply replaceText(ReplaceParams p);

    /** Reads every slider, or sets one. */
    ClientReply slider(SliderParams p);

    /** A key event; a click holds the caller until the next client tick carries the release. */
    ClientReply key(KeyParams p);

    /** A key mapping by name; a click holds the caller until the next client tick. */
    ClientReply keybind(KeybindParams p);

    /** The container menu open on this client, with the screen context it belongs to. */
    MenuContainer menu();

    /** Sends a chat line, or a command with a leading slash, on the client thread; never waits for replies. */
    ChatSendStart chatSend(ChatSendParams p);

    /** A send's reply, and for a line that went out, the reader of what arrives after it (null when refused). */
    record ChatSendStart(ClientReply reply, ChatReplies replies) {}

    /** The lines that arrive after one send. Each read is short and safe from any thread, and nothing is held between reads. */
    interface ChatReplies {
        /** The lines once a reply has arrived and settled; empty until then. */
        Optional<LaterChat> poll();

        /** What the wait ends with when no reply came in time. */
        LaterChat timedOut();
    }

    ChatHistory chatHistory(ChatHistoryParams p);

    OverlaysCleared overlays(OverlaysParams p);

    /** Holds the caller for a frame drawn after the request, unless called on the client thread. */
    Screenshot screenshot(ScreenshotParams p);

    /** This client's player, as the client predicts it. */
    ClientPlayer player();

    /**
     * Every non-air ClientLevel cell within Chebyshev {@code radius} (at most 16) of the centre, the
     * player's feet when any axis is null. {@code filterIds} keeps only those ids or {@code #tags}.
     * Throws capability_unavailable with no world open.
     */
    BlockScan scanBlocks(int radius, Double cx, Double cy, Double cz, Set<String> filterIds);

    /**
     * The {@code mc.observe.player} snapshot read from the LocalPlayer, for a client with no server
     * attached; {@code present:false} with no player.
     */
    Map<String, Object> observePlayer();

    /**
     * Client-side entity scan — the server-side {@code mc.query q='entities'} rows, every field
     * filled except {@code effects}, which a client is not told for other entities, read from
     * {@code ClientLevel}. Center defaults to the local player when not provided; radius is capped
     * at 32 (client interest range). Optional {@code hostileFilter} matches only Enemy instances
     * (true) or only non-Enemy instances (false); omit for all. Throws capability_unavailable with
     * no world open.
     */
    List<QueryContracts.EntityRow> queryEntities(int radius, Double cx, Double cy, Double cz, Boolean hostileFilter);

    /** Scored hostile + incoming-projectile assessment around the local player
     *  (Phase B/C ThreatScanner): {@code mc.observe.threats}' reply. */
    net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Threats observeThreats(int radius);

    /** Phase G boss sensing — nearest EnderDragon/WitherBoss within {@code radius}
     *  plus the End-crystal list: {@code mc.observe.boss}' reply. */
    net.magicterra.worlddriver.api.methods.observe.ObserveContracts.BossReading observeBoss(int radius);
}

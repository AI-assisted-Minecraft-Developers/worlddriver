package net.magicterra.agent.client;

import java.util.Map;
import java.util.Set;

/**
 * Client-side surface for the agent driver. A platform-specific implementation
 * holds direct references to {@code net.minecraft.client.*} and is registered
 * through {@link ClientHooks} during client init. Dedicated servers never load
 * it; {@link net.magicterra.agent.api.AgentApi} treats {@code mc.client.*}
 * routes as available-only-when-bound.
 *
 * Every method returns JSON-serializable values (Map / List / String / Number /
 * Boolean / null) so they round-trip cleanly through {@code JsonCodec}.
 */
public interface ClientAgentApi {
    /** Walks the current Screen widget tree and returns a JSON-friendly snapshot. */
    Map<String, Object> screenTree();

    /** Lightweight metadata about the current screen — useful as an availability probe. */
    Map<String, Object> screenInfo();

    /**
     * Client-side chat send. If {@code text} starts with {@code /}, dispatches
     * as a command via {@code ClientPacketListener.sendCommand}; otherwise sent
     * as a plain chat message via {@code sendChat}. Mirrors what pressing T,
     * typing, and pressing Enter does — without opening the {@code ChatScreen}.
     * When {@code awaitReplyMs > 0}, blocks up to that many milliseconds for
     * the next system chat line (server feedback such as "Gave 64 X to Y" or
     * "Set the time to N") and folds it into the response as {@code reply}.
     */
    Map<String, Object> chatSend(String text, int awaitReplyMs);

    /**
     * Read recent chat + system messages from the client's chat component.
     * Returns the most-recently-received messages first up to {@code limit}
     * (default 50, cap 256), filtered to those with {@code seq > sinceSeq}
     * if provided. Each entry: {@code {seq, ageMs, text}} where {@code text}
     * is the plain-string projection of the Component (formatting stripped).
     * Backs the new {@code mc.client.chat.history} tool — fills the
     * "server replied, agent can't see it" gap.
     */
    Map<String, Object> chatHistory(int limit, int sinceSeq);

    /**
     * Dismiss persistent on-screen overlays that don't belong to the world.
     * Two independently-controllable groups:
     * <ul>
     *   <li>{@code tutorial:true} (default) — sets the tutorial step to
     *       {@code NONE} so vanilla stops showing "Move with WASD" /
     *       "Look around" / "Use mouse to turn" toast-style hints.</li>
     *   <li>{@code toasts:true} (default) — clears the
     *       {@link net.minecraft.client.gui.components.toasts.ToastComponent}
     *       queue (advancements, recipes, system).</li>
     * </ul>
     * Idempotent — re-run safely from agents that don't track state.
     */
    Map<String, Object> overlays(boolean tutorial, boolean toasts);

    /**
     * Client-side player snapshot — pos, look, on-ground, hp/food, selected hotbar
     * slot + held item, plus the current crosshair {@code HitResult} (block or
     * entity the camera is aimed at, with reach distance). Works without a server
     * being attached to {@code AgentApi}, so it remains available when the client
     * is connected to a remote dedicated server. Result also includes
     * {@code inventory}: a list of every non-empty inventory slot
     * ({@code {slot,id,count}}, indexed the same way as {@code Player.getInventory()})
     * so callers don't need to open the {@code InventoryScreen} just to inspect
     * what the player is carrying.
     */
    Map<String, Object> observePlayer();

    /**
     * Client-side world scan — same shape as the server-side {@code mc.query}
     * with {@code q='blocks'} but reads {@code ClientLevel}. Center defaults to
     * the local player; radius is capped at 16 (client view distance, so larger
     * scans would just return air). Backs the client-MCP fallback path of
     * {@code mc.query q='blocks'} when no server is attached.
     */
    Map<String, Object> observeArea(int radius, Double cx, Double cy, Double cz, Set<String> filterIds);

    /**
     * Snapshot of whichever {@code AbstractContainerScreen} is currently open
     * client-side (player inventory, crafting table, the chest the server just
     * pushed). Returns {@code present:false} when no container menu is active.
     * Powers the no-{@code pos} mode of {@code mc.observe.container}.
     */
    Map<String, Object> observeContainerMenu();

    /**
     * Client-side entity scan — equivalent to the server-side {@code mc.query}
     * with {@code q='entities'} but reads {@code ClientLevel}. Each row carries
     * the numeric {@code id} (suitable for {@code mc.bot.attackEntity}) plus
     * {@code type, pos, health?, hostile}. Center defaults to the local
     * player when not provided; radius is capped at 32 (client interest range).
     * Optional {@code hostileFilter} matches only Enemy instances (true) or
     * only non-Enemy instances (false); omit for all.
     */
    Map<String, Object> queryEntities(int radius, Double cx, Double cy, Double cz, Boolean hostileFilter);

    /** Pops the current screen (equivalent to {@code mc.setScreen(null)}). */
    Map<String, Object> closeScreen();

    /**
     * Synthesizes a left/middle/right click at on-screen pixel coordinates.
     * Coordinates are in the Screen's logical (scaled) coordinate system.
     */
    Map<String, Object> click(double x, double y, int button);

    /**
     * Invokes {@code Menu.clicked(slot, button, type, player)} on the currently
     * open container screen, the same path vanilla takes for shift-click,
     * Q-drop, hotbar-swap, and creative middle-click. Unlike {@link #click},
     * this addresses slots semantically (Menu.slots index) and lets callers
     * pick the {@code ClickType} explicitly without spoofing GLFW modifier
     * state. Returns {@code ok:false} when no container screen is open.
     */
    Map<String, Object> slotClick(int slot, int button, String type);

    /**
     * Moves the mouse-hover state on the current screen (no click). Useful for
     * parking the cursor away from widgets so tooltips don't render over a
     * screenshot.
     */
    Map<String, Object> mouseMove(double x, double y);

    /**
     * Sets the local player's held hotbar slot (0–8). Sends
     * {@code ServerboundSetCarriedItemPacket} so the server tracks the change.
     * No-op (ok:false) when no local player.
     */
    Map<String, Object> setHotbarSlot(int slot);

    /**
     * Types a string into the current screen by dispatching {@code Screen.charTyped}
     * per codepoint. Targets the focused widget (EditBox/CommandSuggestions etc).
     * No-op (ok:false) when no screen open.
     */
    Map<String, Object> typeText(String text);

    /**
     * Synthesizes a keyboard key event on the current screen — Enter/Escape/Tab/F-keys,
     * letters and digits. action: "press" / "release" / "click" (default; press+release).
     * Returns ok:false when no screen is open or the key name is unrecognized.
     */
    Map<String, Object> key(String key, String action);

    /**
     * Captures the framebuffer and returns {@code {format, width, height, base64}}.
     * Supported options (all optional, all in {@code opts}):
     * <ul>
     *   <li>{@code maxWidth} / {@code maxHeight} — downscale (preserving aspect) so neither
     *       dimension exceeds the cap. Defaults to no downscale.</li>
     *   <li>{@code format} — {@code "png"} (default) or {@code "jpeg"}.</li>
     *   <li>{@code quality} — JPEG quality 1–100, default 85. Ignored for PNG.</li>
     * </ul>
     */
    Map<String, Object> screenshot(Map<String, Object> opts);
}

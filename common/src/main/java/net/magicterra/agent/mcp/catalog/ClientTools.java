package net.magicterra.agent.mcp.catalog;

import java.util.List;
import java.util.Map;

import static net.magicterra.agent.mcp.schema.Schemas.*;

/**
 * {@code mc.client.*} catalog entries. Only resolvable on a JVM with the MC
 * client loaded (i.e. runClient, never dedicated server). On a dedicated server
 * these calls return an MCP isError=true with "mc.client.* not available".
 * See {@code ToolCatalog} for ordering.
 */
public final class ClientTools {
    private ClientTools() {}

    public static List<Map<String, Object>> tools() {
        return List.of(
            roTool("mc.client.screen.info",
                "Lightweight probe of the current client screen. Cheap; call this first as an " +
                "availability check before any other mc.client.* tool. " +
                "Returns {hasScreen:boolean, worldOpen:boolean, hasPlayer:boolean, overlayActive:boolean, " +
                "type?:string, title?:string, width?:integer, height?:integer, " +
                "causeOfDeath?:string (on a DeathScreen — e.g. 'Player was slain by Phantom')}.",
                emptyObjectSchema()),

            roTool("mc.client.screen.tree",
                "Walk the current Screen widget tree and return a JSON snapshot. The canonical " +
                "input for picking a click target without taking a screenshot. " +
                "Returns {hasScreen:boolean, type:string, width:integer, height:integer, " +
                "children:[{type, x, y, width, height, visible, active, message?, children?}, ...]}.",
                emptyObjectSchema()),

            roTool("mc.client.player",
                "Client-AUTHORITATIVE player snapshot — reads the LocalPlayer / ClientLevel " +
                "directly, so unlike mc.observe.player (which prefers the SERVER when one is " +
                "attached) it shows what the CLIENT predicts. Same fields as mc.observe.player " +
                "PLUS the client-physics truth: pose (STANDING/CROUCHING/SWIMMING/…), eyePos{x,y,z}, " +
                "inWall, inWater, underWater, crouching, and eyeBlock/feetBlock (the ClientLevel " +
                "block id at the eye and feet cells). Diff against mc.observe.player to detect a " +
                "client/server desync (e.g. the client crawl-evading a command-placed block while " +
                "the server still suffocates). This is exactly what the client-tick reflexes " +
                "(autoSwim, antiSuffocate) gate on. {present:false} when no LocalPlayer.",
                emptyObjectSchema()),

            roTool("mc.client.scene",
                "Client-AUTHORITATIVE derived-facts snapshot from the per-tick WorldModel " +
                "blackboard. Returns {present:boolean, pos?, health?, food?, dayPhase?, " +
                "skyExposed?, exposedAtNight?, cornered?, lethalCount?, rows?}. " +
                "dayPhase is DAY/DUSK/NIGHT/DAWN. cornered=true when the hazard grid shows " +
                "no safe retreat direction. lethalCount is the number of lethal-fall hazard " +
                "cells in the local grid. rows is an ASCII map of the hazard field. " +
                "{present:false} when no LocalPlayer. Client-only: unavailable on the " +
                "dedicated server (throws 'no bot' like all mc.bot.* routes).",
                emptyObjectSchema()),

            roTool("mc.client.blocks",
                "Client-AUTHORITATIVE block scan — reads ClientLevel around center (default the " +
                "local player), Chebyshev radius filter.in_radius (default 4, cap 16). Optional " +
                "filter.type restricts to one block id (or a '#tag' selector). Unlike mc.query " +
                "(which prefers the SERVER when attached) this always returns what the CLIENT has " +
                "loaded, so you can diff client vs server block state. Returns " +
                "{blocks:[{pos,type}], center, radius}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "center", Map.of("type", "object", "properties", Map.of(
                            "x", Map.of("type", "integer"),
                            "y", Map.of("type", "integer"),
                            "z", Map.of("type", "integer"))),
                        "filter", Map.of("type", "object", "properties", Map.of(
                            "in_radius", Map.of("type", "integer", "minimum", 0, "maximum", 16),
                            "type", Map.of("type", "string")))
                    )
                )),

            wrTool("mc.client.chat.send",
                "Send a chat message or command from the local client — equivalent to " +
                "pressing T, typing, and pressing Enter. Text starting with '/' is sent " +
                "as a command (e.g. '/tp 0 80 0'); anything else is a plain chat message. " +
                "Goes through LocalPlayer.connection so it works even when the client is " +
                "on a remote dedicated server. With awaitReplyMs>0, blocks up to that many " +
                "ms for the next inbound chat line (server feedback like \"Gave 64 X to Y\" " +
                "or \"Set the time to N\") and folds it into the response as {reply:{seq,text,ageTicks}}. " +
                "On timeout, returns {replyTimeout:true, replyMs}. " +
                "Returns {ok, kind:'command'|'chat', length, reply?, replyExtra?, replyTimeout?, replyMs?}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "text", Map.of("type", "string",
                            "description", "Chat text. Leading '/' makes it a command."),
                        "awaitReplyMs", Map.of("type", "integer", "minimum", 1, "maximum", 30000,
                            "description", "Wait up to N ms for the next chat reply and include it.")
                    ),
                    "required", List.of("text")
                )),

            roTool("mc.client.chat.history",
                "Read recent chat + system messages from the client's chat component (the " +
                "scrollback you'd see by pressing T). Returns {ok, count, nextSeq, messages:" +
                "[{seq, ageTicks, text}, ...]} newest-first, capped at limit (default 50, " +
                "max 256). Pass sinceSeq to paginate; use the previous response's nextSeq. " +
                "Plain text only (formatting stripped via Component.getString()). Backs the " +
                "\"server replied to my command, what did it say\" use case — fills the gap " +
                "that chat.send didn't surface server feedback before awaitReplyMs landed.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 256,
                            "description", "Newest N messages. Default 50."),
                        "sinceSeq", Map.of("type", "integer", "minimum", 0,
                            "description", "Only return messages with seq > this. Default 0.")
                    )
                )),

            wrTool("mc.client.overlays",
                "Dismiss persistent HUD overlays that don't belong to the world. Two flags, " +
                "both default true so {} clears everything:\n" +
                "  tutorial — sets the tutorial step to NONE (kills \"Move with W,A,S,D\" / " +
                "\"Look around\" / \"Use mouse to turn\" toasts that get stuck under Xvfb)\n" +
                "  toasts — clears ToastComponent queue (advancements, recipes, system)\n" +
                "Idempotent. Returns {ok, tutorial?, toasts?, tutorialError?, toastsError?}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "tutorial", Map.of("type", "boolean",
                            "description", "Set Options.tutorialStep=NONE and apply live. Default true."),
                        "toasts", Map.of("type", "boolean",
                            "description", "Clear the toast queue. Default true.")
                    )
                )),

            wrTool("mc.client.screen.close",
                "Pop the current screen (equivalent to setScreen(null)). Always succeeds even if " +
                "nothing was open. Returns {ok:boolean}.",
                emptyObjectSchema()),

            wrTool("mc.client.input.click",
                "Click at logical Screen coordinates (post-GUI-scale). button: 0=left, 1=right, " +
                "2=middle. Reflection-based so headless Xvfb works. " +
                "Returns {ok, handled}; handled=false = clicked empty space; ok=false = no screen open.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "x", Map.of("type", "number"),
                        "y", Map.of("type", "number"),
                        "button", Map.of("type", "integer", "minimum", 0, "maximum", 2,
                            "description", "0=left, 1=right, 2=middle")
                    ),
                    "required", List.of("x", "y")
                )),

            wrTool("mc.client.input.slotClick",
                "Click a Slot in the open container menu with an explicit ClickType — the only way " +
                "to get shift-click / Q-drop without GLFW modifier spoofing. Routes through " +
                "MultiPlayerGameMode so the server sees a real ContainerClickPacket.\n" +
                "type:\n" +
                "  pickup (default) — button=0 take/place full; button=1 take half / place one\n" +
                "  quickMove        — shift-click; auto-moves between hotbar/inventory/container\n" +
                "  throw            — button=0 drop one (Q); button=1 drop stack (Ctrl+Q)\n" +
                "  swap             — button=0..8 hotbar slot to swap with target (number keys)\n" +
                "  clone            — creative middle-click; copies stack to cursor\n" +
                "  pickupAll        — double-click; collect all matching items into cursor\n" +
                "  quickCraft       — drag-distribute (internal; rarely needed)\n" +
                "Returns {ok, slot, button, type} or {ok:false, error}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "slot", Map.of("type", "integer", "minimum", 0,
                            "description", "Index into Menu.slots — discover via mc.observe.container or mc.client.screen.tree"),
                        "button", Map.of("type", "integer", "minimum", 0, "maximum", 8,
                            "description", "0=left/default; 1=right; for type='swap' this is the destination hotbar slot 0-8"),
                        "type", Map.of("type", "string",
                            "enum", List.of("pickup", "quickMove", "swap", "clone", "throw", "pickupAll", "quickCraft"),
                            "description", "ClickType; defaults to pickup")
                    ),
                    "required", List.of("slot")
                )),

            wrTool("mc.client.input.mouseMove",
                "Move the cursor to logical Screen coordinates and update MouseHandler xpos/ypos " +
                "(via reflection, so the headless Xvfb code path also fires hover effects). Useful " +
                "to park the cursor before a screenshot so tooltips don't occlude the UI. " +
                "Returns {ok:boolean, scale:integer, refl:'ok'|'failed', wx:number, wy:number} where " +
                "wx/wy are the GLFW window-pixel coordinates after multiplying by the GUI scale.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "x", Map.of("type", "number"),
                        "y", Map.of("type", "number")
                    ),
                    "required", List.of("x", "y")
                )),

            wrTool("mc.client.input.typeText",
                "Type a string into the current screen by dispatching Screen.charTyped per " +
                "codepoint. The text lands on whichever widget currently has focus (usually " +
                "an EditBox after a click). Non-BMP codepoints (emoji etc) are sent as a UTF-16 " +
                "surrogate pair, matching GLFW IME behaviour. Returns {ok, typed:int, length:int} " +
                "on success or {ok:false, error} if no screen is open.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "text", Map.of("type", "string",
                            "description", "Text to type into the focused widget.")
                    ),
                    "required", List.of("text")
                )),

            wrTool("mc.client.input.key",
                "Synthesize a keyboard event. Keys: ENTER, ESCAPE, TAB, BACKSPACE, DELETE, SPACE, " +
                "LEFT/RIGHT/UP/DOWN, HOME, END, PAGEUP/DOWN, F1..F25, A..Z, 0..9. action: 'press', " +
                "'release', or 'click' (default; press+release). " +
                "Routes via Screen.keyPressed when a screen is open (via:'screen'), else " +
                "KeyboardHandler.keyPress so in-game keybinds (F3/F5/Q/F/T/…) fire as if pressed " +
                "(via:'keybind'). For WASD movement use mc.bot.* — they're stickier. " +
                "Returns {ok, key, code, action, pressed, released, via}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "key", Map.of("type", "string",
                            "description", "Key name (see description for supported set)."),
                        "action", Map.of("type", "string", "enum", List.of("press", "release", "click"),
                            "description", "Default 'click' = press+release.")
                    ),
                    "required", List.of("key")
                )),

            wrTool("mc.client.input.setHotbarSlot",
                "Select the held hotbar slot (0–8). Sends ServerboundSetCarriedItemPacket so " +
                "subsequent attack / useItem resolve against the new item. Pair with " +
                "mc.observe.player.inventory to find which slot holds what. " +
                "Returns {ok, slot, previous} or {ok:false, error}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "slot", Map.of("type", "integer", "minimum", 0, "maximum", 8,
                            "description", "Hotbar slot index. 0 is leftmost.")
                    ),
                    "required", List.of("slot")
                )),

            roTool("mc.client.screenshot",
                "Capture the framebuffer. maxWidth/maxHeight = aspect-preserving downscale caps. " +
                "format: png (default, lossless) or jpeg (smaller). quality 1-100 for JPEG (default 85). " +
                "Over MCP returns two content blocks: text {format,width,height} + image (base64). " +
                "In-JVM/WebSocket callers get a single Map {format,width,height,base64}.",
                Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "maxWidth", Map.of("type", "integer", "minimum", 16, "maximum", 8192,
                            "description", "Cap on output width in pixels. Omit for native size."),
                        "maxHeight", Map.of("type", "integer", "minimum", 16, "maximum", 8192,
                            "description", "Cap on output height in pixels. Omit for native size."),
                        "format", Map.of("type", "string", "enum", List.of("png", "jpeg"),
                            "description", "Output image format. Default 'png'."),
                        "quality", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                            "description", "JPEG quality. Default 85. Ignored for PNG.")
                    )
                ),
                Map.of("anthropic/maxResultSizeChars", 500000))
        );
    }
}

package net.magicterra.worlddriver.mcp.catalog;

import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.mcp.schema.ToolSchema;

import static net.magicterra.worlddriver.mcp.schema.Schemas.*;

/**
 * {@code mc.client.*} catalog entries. Only resolvable on a JVM with the MC
 * client loaded (i.e. runClient, never dedicated server). On a dedicated server
 * these calls return an MCP isError=true with "mc.client.* not available".
 * See {@code ToolCatalog} for ordering.
 */
public final class ClientTools {
    private ClientTools() {}

    public static List<ToolSchema> tools() {
        return List.of(
            roTool("mc.client.screen.info",
                "Lightweight probe of the current client screen. Cheap; call this first as an " +
                "availability check before any other mc.client.* tool. " +
                "windowActive/mouseGrabbed are the environment a mod's key handler usually demands " +
                "before it acts at all — read them when a keystroke seems to vanish. " +
                "Returns {hasScreen:boolean, worldOpen:boolean, hasPlayer:boolean, overlayActive:boolean, " +
                "windowActive:boolean, mouseGrabbed:boolean, " +
                "type?:string, title?:string, width?:integer, height?:integer, " +
                "causeOfDeath?:string (on a DeathScreen — e.g. 'Player was slain by Phantom')}.",
                emptyObject()),

            screenTreeTool(),

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
                emptyObject()),

            roTool("mc.client.scene",
                "Client-AUTHORITATIVE derived-facts snapshot from the per-tick WorldModel " +
                "blackboard. Returns {present:boolean, pos?, health?, food?, dayPhase?, " +
                "skyExposed?, exposedAtNight?, cornered?, lethalCount?, rows?}. " +
                "dayPhase is DAY/DUSK/NIGHT/DAWN. cornered=true when the hazard grid shows " +
                "no safe retreat direction. lethalCount is the number of lethal-fall hazard " +
                "cells in the local grid. rows is an ASCII map of the hazard field. " +
                "{present:false} when no LocalPlayer. Client-only: unavailable on the " +
                "dedicated server (throws 'no bot' like all mc.bot.* routes).",
                emptyObject()),

            roTool("mc.client.blocks",
                "Client-AUTHORITATIVE block scan — reads ClientLevel around center (default the " +
                "local player), Chebyshev radius filter.in_radius (default 4, cap 16). Optional " +
                "filter.type restricts to one block id (or a '#tag' selector). Unlike mc.query " +
                "(which prefers the SERVER when attached) this always returns what the CLIENT has " +
                "loaded, so you can diff client vs server block state. Returns " +
                "{blocks:[{pos,type}], center, radius}.\n" +
                "NOT for terrain/geometry inspection: it dumps EVERY cell as verbose JSON, so a " +
                "radius>=5 overflows the tool-result token budget (a 13^3 box is ~2000 cells). " +
                "To READ TERRAIN SHAPE (banks, walls, pits, water depth, a wedge cross-section) " +
                "use mc.observe.map with plane='xy'/'zy' for a compact ASCII vertical slice, or " +
                "plane='xz' for a top-down heightmap — one glanceable grid instead of a cell dump, " +
                "and it works headless. Reach for mc.client.blocks ONLY to confirm a FEW specific " +
                "cells' exact ids or to diff client-vs-server state, always with a SPECIFIC " +
                "filter.type and the smallest radius that covers the cells you need.",
                object()
                    .prop("center", object()
                        .prop("x", integer())
                        .prop("y", integer())
                        .prop("z", integer()))
                    .prop("filter", object()
                        .prop("in_radius", integer(0, 16))
                        .prop("type", string()))),

            wrTool("mc.client.chat.send",
                "Send a chat message or command from the local client — equivalent to " +
                "pressing T, typing, and pressing Enter. Text starting with '/' is sent " +
                "as a command (e.g. '/tp 0 80 0'); anything else is a plain chat message. " +
                "Goes through LocalPlayer.connection so it works even when the client is " +
                "on a remote dedicated server. With awaitReplyMs>0, blocks up to that many " +
                "ms for chat lines that arrive AFTER the send (packet-level capture with a " +
                "monotonic seq — command feedback like \"Set the time to N\", /say echoes, " +
                "broadcasts), waits a ~150ms settle window so multi-line feedback batches, " +
                "then folds them in as {reply:{seq,kind:'system'|'player',text,self}, replyExtra?:[...]}. " +
                "The server's echo of your own plain-chat line is flagged self and NEVER " +
                "returned as the reply (history keeps it). On a busy server unrelated lines " +
                "can interleave — match on text/kind; command feedback is kind:'system'. " +
                "On timeout, returns {replyTimeout:true, replyMs}. " +
                "Returns {ok, kind:'command'|'chat', length, reply?, replyExtra?, replyTimeout?, replyMs?}. " +
                "Boundary: replies rendered CLIENT-locally (client-command feedback, chat " +
                "validation errors, mods writing straight to the chat HUD) never cross the " +
                "packet layer and won't be seen — those time out despite a visible answer. " +
                "(For server-side commands, prefer mc.action runCommand — it returns the " +
                "command's own feedback[] directly, no chat correlation needed.)",
                object()
                    .req("text", string()
                        .desc("Chat text. Leading '/' makes it a command."))
                    .prop("awaitReplyMs", integer(1, 30000)
                        .desc("Wait up to N ms for post-send chat lines and include them."))),

            roTool("mc.client.chat.history",
                "Read recent chat + system messages captured at the packet layer (player " +
                "chat, command feedback, /say, server broadcasts; action-bar excluded; " +
                "lines other client mods cancel are still captured). " +
                "Returns {ok, count, nextSeq, messages:[{seq, kind:'system'|'player', text, " +
                "ageTicks, self}, ...]} newest-first, capped at limit (default 50, max 256). " +
                "kind:'player' = chat with a real sender profile (self=true marks your own " +
                "echoed lines); disguised chat (console /say, command blocks) is 'system'. " +
                "seq is monotonic for the client session (buffer retains the last 512 lines). " +
                "Poll pattern: remember nextSeq, later pass it as sinceSeq to get only new " +
                "lines. Plain text only (formatting stripped via Component.getString()). " +
                "Boundary: lines added CLIENT-locally without a packet (client-command " +
                "feedback, chat validation errors, mods calling ChatComponent.addMessage) " +
                "render on screen but do NOT appear here. " +
                "Backs the \"server replied to my command, what did it say\" use case.",
                object()
                    .prop("limit", integer(1, 256)
                        .desc("Newest N messages. Default 50."))
                    .prop("sinceSeq", integer().min(0)
                        .desc("Only return messages with seq >= this. Default 0."))),

            wrTool("mc.client.overlays",
                "Dismiss persistent HUD overlays that don't belong to the world. Two flags, " +
                "both default true so {} clears everything:\n" +
                "  tutorial — sets the tutorial step to NONE (kills \"Move with W,A,S,D\" / " +
                "\"Look around\" / \"Use mouse to turn\" toasts that get stuck under Xvfb)\n" +
                "  toasts — clears ToastComponent queue (advancements, recipes, system)\n" +
                "Idempotent. Returns {ok, tutorial?, toasts?, tutorialError?, toastsError?}.",
                object()
                    .prop("tutorial", bool()
                        .desc("Set Options.tutorialStep=NONE and apply live. Default true."))
                    .prop("toasts", bool()
                        .desc("Clear the toast queue. Default true."))),

            wrTool("mc.client.screen.close",
                "Pop the current screen (equivalent to setScreen(null)). Always succeeds even if " +
                "nothing was open. Returns {ok:boolean}.",
                emptyObject()),

            wrTool("mc.client.input.click",
                "Click at logical Screen coordinates (post-GUI-scale). button: 0=left, 1=right, " +
                "2=middle. Reflection-based so headless Xvfb works. " +
                "Returns {ok, handled}; handled=false = clicked empty space; ok=false = no screen open.",
                object()
                    .req("x", number())
                    .req("y", number())
                    .prop("button", integer(0, 2)
                        .desc("0=left, 1=right, 2=middle"))),

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
                object()
                    .req("slot", integer().min(0)
                        .desc("Index into Menu.slots — discover via mc.observe.container or mc.client.screen.tree"))
                    .prop("button", integer(0, 8)
                        .desc("0=left/default; 1=right; for type='swap' this is the destination hotbar slot 0-8"))
                    .prop("type", stringEnum("pickup", "quickMove", "swap", "clone", "throw", "pickupAll", "quickCraft")
                        .desc("ClickType; defaults to pickup"))),

            wrTool("mc.client.input.mouseMove",
                "Move the cursor to logical Screen coordinates and update MouseHandler xpos/ypos " +
                "(via reflection, so the headless Xvfb code path also fires hover effects). Useful " +
                "to park the cursor before a screenshot so tooltips don't occlude the UI. " +
                "Returns {ok:boolean, scale:integer, refl:'ok'|'failed', wx:number, wy:number} where " +
                "wx/wy are the GLFW window-pixel coordinates after multiplying by the GUI scale.",
                object()
                    .req("x", number())
                    .req("y", number())),

            wrTool("mc.client.input.typeText",
                "Type a string into the current screen by dispatching Screen.charTyped per " +
                "codepoint. The text lands on whichever widget currently has focus (usually " +
                "an EditBox after a click). Non-BMP codepoints (emoji etc) are sent as a UTF-16 " +
                "surrogate pair, matching GLFW IME behaviour. Returns {ok, typed:int, length:int} " +
                "on success or {ok:false, error} if no screen is open.",
                object()
                    .req("text", string()
                        .desc("Text to type into the focused widget."))),

            wrTool("mc.client.input.replaceText",
                "Overwrite a text box's ENTIRE contents (atomic EditBox.setValue) — unlike " +
                "mc.client.input.typeText which only APPENDS at the cursor and can't clear a " +
                "pre-filled field. Targets the focused box, else the box whose value/label contains " +
                "'match', else the sole box on screen. " +
                "Returns {ok, value, previous} or {ok:false, error}.",
                object()
                    .req("text", string()
                        .desc("New full contents for the text box."))
                    .prop("match", string()
                        .desc("Optional case-insensitive substring of the target box's " +
                            "current value/label; needed only when several boxes exist and none is focused."))),

            wrTool("mc.client.input.slider",
                "Read or set a GUI slider (AbstractSliderButton — render/simulation distance, FOV, " +
                "volume, etc.). Omit 'fraction' to READ: returns every slider's {index, label, value} " +
                "so you can see the live value first (a slider value is only meaningful when shown). " +
                "Provide 'fraction' in [0,1] to SET the slider matched by 'match' (substring of its " +
                "label, e.g. 'render'), else 'index', else the sole slider — firing the vanilla " +
                "apply/update hooks so the option commits and the label refreshes. " +
                "Returns {ok, mode:'read', sliders:[{index,label,value}]} or " +
                "{ok, mode:'set', label, value, previousLabel, previousValue}.",
                object()
                    .prop("match", string()
                        .desc("Case-insensitive substring of the target slider's label."))
                    .prop("index", integer()
                        .desc("0-based index into the on-screen slider list (use when no match)."))
                    .prop("fraction", number()
                        .desc("Target value 0..1. OMIT to read all sliders instead of setting."))),

            keyTool(),
            keybindTool(),

            wrTool("mc.client.input.setHotbarSlot",
                "Select the held hotbar slot (0–8). Sends ServerboundSetCarriedItemPacket so " +
                "subsequent attack / useItem resolve against the new item. Pair with " +
                "mc.observe.player.inventory to find which slot holds what. " +
                "Returns {ok, slot, previous} or {ok:false, error}.",
                object()
                    .req("slot", integer(0, 8)
                        .desc("Hotbar slot index. 0 is leftmost."))),

            roTool("mc.client.screenshot",
                "Capture the framebuffer. maxWidth/maxHeight = aspect-preserving downscale caps. " +
                "format: png (default, lossless) or jpeg (smaller). quality 1-100 for JPEG (default 85). " +
                "Over MCP returns two content blocks: text {format,width,height} + image (base64). " +
                "In-JVM/WebSocket callers get a single Map " +
                "{format,width,height,frame,frameWaited,windowActive,fps,base64}. " +
                "The capture waits for a frame drawn after your request, so what you did just " +
                "before it is in the picture; frameWaited:false means none was drawn in time and " +
                "the image is whatever was left over. frame is that frame's number — two captures " +
                "with the same frame are the same image, however different the situation is.",
                object()
                    .prop("maxWidth", integer(16, 8192)
                        .desc("Cap on output width in pixels. Omit for native size."))
                    .prop("maxHeight", integer(16, 8192)
                        .desc("Cap on output height in pixels. Omit for native size."))
                    .prop("format", stringEnum("png", "jpeg")
                        .desc("Output image format. Default 'png'."))
                    .prop("quality", integer(1, 100)
                        .desc("JPEG quality. Default 85. Ignored for PNG.")),
                Map.of("anthropic/maxResultSizeChars", 500000))
        );
    }

    /** Out of {@link #tools()} for the same reason as {@link #keyTool()}. */
    private static ToolSchema screenTreeTool() {
        return roTool("mc.client.screen.tree",
            "Walk the current Screen widget tree and return a JSON snapshot. The canonical " +
            "input for picking a click target without taking a screenshot; the coordinates " +
            "are right for modded, self-drawn screens too. STRUCTURE, NOT PAINT: 'selected' " +
            "is only what a selection list reports, and a widget that draws its own selection " +
            "leaves this tree identical before and after the click that chose it — read that " +
            "with a screenshot. A widget that throws leaves 'error' on its own node instead " +
            "of blanking the answer. " +
            "Returns {hasScreen:boolean, type:string, width:integer, height:integer, " +
            "children:[{type, x, y, width, height, visible, active, focused, message?, " +
            "selected?, error?, children?}, ...]}.",
            emptyObject());
    }

    /** Out of {@link #tools()} because routing and the two halves of a click take a paragraph. */
    private static ToolSchema keyTool() {
        return wrTool("mc.client.input.key",
            "Synthesize a keyboard event. Keys: ENTER, ESCAPE, TAB, BACKSPACE, DELETE, SPACE, " +
            "LEFT/RIGHT/UP/DOWN, HOME, END, PAGEUP/DOWN, F1..F25, A..Z, 0..9. action: 'press', " +
            "'release', or 'click' (default; the release lands on the NEXT client tick, routed " +
            "again from whatever the press left open). route picks the recipient: 'auto' " +
            "(default) = the open screen, else the keybinds, which is what a real keystroke " +
            "gets; 'keybind' reaches keybinds even under an open screen; 'screen' refuses when " +
            "none is open. For WASD movement use mc.bot.* — they're stickier. " +
            "Returns {ok, key, code, action, route, modifiers, via, pressed, released, screenAfter} " +
            "plus releaseVia when the press changed the screen under it. pressed/released mean the " +
            "half went out; pressHandled/releaseHandled (screen route only) mean the screen " +
            "consumed it — screens rarely consume a release, so " +
            "releaseHandled:false is normal and is NOT a failed release. screenAfter names the " +
            "screen this keystroke left open ('none' if it left none) — the NEXT key is routed by " +
            "it, and one sent at a screen you did not know was there lands on that screen instead.",
            object()
                .req("key", string()
                    .desc("Key name (see description for supported set)."))
                .prop("action", stringEnum("press", "release", "click")
                    .desc("Default 'click' = press now, release next tick."))
                .prop("route", stringEnum("auto", "keybind", "screen")
                    .desc("Who receives it. Default 'auto' routes as vanilla would."))
                .prop("modifiers", array(stringEnum("shift", "ctrl", "alt", "super"))
                    .desc("Modifier bits for a SCREEN shortcut. A modified KEYBIND needs "
                        + "mc.client.input.keybind — see its description.")));
    }

    /** Out of {@link #tools()} for the same reason as {@link #keyTool()}. */
    private static ToolSchema keybindTool() {
        return wrTool("mc.client.input.keybind",
            "Drive a key mapping BY NAME. Use this, not input.key, whenever the binding carries a " +
            "modifier (ALT+Y, common in mod packs): that match asks the real keyboard through " +
            "glfwGetKey, where a synthesized ALT press does not appear, so pressing ALT then Y " +
            "fires nothing. It also reaches a binding whose key the human has rebound. " +
            "name = a mapping id (key.inventory, key.yes_steve_model.player_model.desc) or a " +
            "substring of an id or title; OMIT it to list every mapping, which is how you find " +
            "the one you want. action: 'press', 'release', or 'click' (default; released on the " +
            "next client tick). It drives the mapping AND sends the key as a raw event, because " +
            "mods split into two families: those that poll the mapping and those that subscribe " +
            "to the key event and re-test the mapping inside it. For the second family the " +
            "binding's modifier is cleared for the length of that event and restored after, since " +
            "their test reads the physical keyboard. rawEvent in the reply says what became of " +
            "it, and windowActive/mouseGrabbed report the two gates such a handler usually puts " +
            "in front of itself — a keystroke that arrives while either is false is refused by " +
            "the mod, not lost. Returns {ok, name, title, key, boundTo, category, action, clicks, " +
            "down, released, rawEvent, windowActive, mouseGrabbed}, or {ok, count, keybinds:[…]}.",
            object()
                .prop("name", string()
                    .desc("Mapping id or a substring of one. Omit to list every mapping."))
                .prop("action", stringEnum("press", "release", "click")
                    .desc("Default 'click' = down now, up next tick.")));
    }
}

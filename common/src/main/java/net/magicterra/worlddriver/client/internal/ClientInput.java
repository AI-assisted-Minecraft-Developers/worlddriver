package net.magicterra.worlddriver.client.internal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

import static net.magicterra.worlddriver.client.internal.ClientThread.runOnClient;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.events.ContainerEventHandler;

/**
 * Input synthesis for {@code mc.client.screen.close} and {@code mc.client.input.*}
 * (click / slotClick / mouseMove / setHotbarSlot / typeText / key). Stateless;
 * extracted from {@code ClientDriverApiImpl}.
 */
public final class ClientInput {
    private ClientInput() {}

    public static Map<String, Object> closeScreen() {
        return runOnClient(() -> {
            Minecraft.getInstance().setScreen(null);
            return Map.of("ok", true);
        });
    }

    public static Map<String, Object> click(double x, double y, int button) {
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            if (s == null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", false);
                r.put("error", "no screen open");
                return r;
            }
            // Fire mouseMoved first so HoverButton/Tooltip widgets register the
            // cursor over the target before the click lands — otherwise widgets
            // that toggle on hover (e.g. AbstractSelectionList row highlight)
            // miss the state transition and the click looks like it landed on a
            // non-active widget.
            s.mouseMoved(x, y);
            boolean handled = s.mouseClicked(x, y, button);
            s.mouseReleased(x, y, button);
            return Map.of("ok", true, "handled", handled);
        });
    }

    public static Map<String, Object> slotClick(int slotIndex, int button, String type) {
        final String tn = (type == null || type.isBlank()) ? "pickup" : type.trim().toLowerCase(Locale.ROOT);
        // Map our short names to the vanilla enum. Surface the mapping in the
        // tool description so callers don't have to crack open ClickType.java.
        ClickType ct;
        switch (tn) {
            case "pickup"     -> ct = ClickType.PICKUP;
            case "quickmove"  -> ct = ClickType.QUICK_MOVE;
            case "swap"       -> ct = ClickType.SWAP;
            case "clone"      -> ct = ClickType.CLONE;
            case "throw"      -> ct = ClickType.THROW;
            case "pickupall"  -> ct = ClickType.PICKUP_ALL;
            case "quickcraft" -> ct = ClickType.QUICK_CRAFT;
            default -> {
                return Map.of("ok", false,
                        "error", "type must be one of pickup|quickMove|swap|clone|throw|pickupAll|quickCraft (got " + type + ")");
            }
        }
        final ClickType clickType = ct;
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            if (!(s instanceof AbstractContainerScreen<?> acs)) {
                return Map.of("ok", false, "error", "no container screen open");
            }
            if (mc.player == null || mc.gameMode == null) {
                return Map.of("ok", false, "error", "no local player");
            }
            var menu = acs.getMenu();
            if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
                return Map.of("ok", false,
                        "error", "slot out of range 0.." + (menu.slots.size() - 1));
            }
            // Same path AbstractContainerScreen.slotClicked takes — routes
            // through MultiPlayerGameMode so the server sees a real
            // ServerboundContainerClickPacket and the menu state stays in sync.
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, button, clickType, mc.player);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("slot", slotIndex);
            out.put("button", button);
            out.put("type", tn);
            return out;
        });
    }

    public static Map<String, Object> mouseMove(double x, double y) {
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            double scale = mc.getWindow().getGuiScale();
            double wx = x * scale;
            double wy = y * scale;
            // 1) Move the real GLFW cursor — drives any "cursor visible"
            //    indicator and is what live users would experience.
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), wx, wy);
            // 2) On Xvfb, glfwSetCursorPos doesn't fire the cursor_pos callback, so
            //    MouseHandler.xpos/ypos stay stale and tooltips render at the *previous*
            //    hover spot. These were written via getDeclaredField("xpos"/"ypos"), which
            //    stopped working the moment the jar was remapped (the literals stay
            //    Mojang-named while the fields become field_1795/field_1794); the access
            //    widener in :common opens them instead, so this is a plain field write
            //    that tiny-remapper rewrites like any other reference. The response used
            //    to carry a "refl" status for that lookup — dropped with the lookup, since
            //    a field write has no failure mode to report.
            var mh = mc.mouseHandler;
            mh.xpos = wx;
            mh.ypos = wy;
            if (s != null) s.mouseMoved(x, y);
            return Map.of("ok", true, "wx", wx, "wy", wy, "scale", scale);
        });
    }

    public static Map<String, Object> setHotbarSlot(int slot) {
        if (slot < 0 || slot > 8) {
            return Map.of("ok", false, "error", "slot out of range 0..8 (got " + slot + ")");
        }
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", false);
                r.put("error", "no local player");
                return r;
            }
            int prev = p.getInventory().selected;
            p.getInventory().selected = slot;
            // Sync to server so subsequent useItem / useItemOn / attack packets
            // resolve against the new held item. Mirrors how vanilla key
            // 1..9 press dispatches (Inventory.swapPaint → setCarriedItemPacket).
            if (p.connection != null) {
                p.connection.send(new ServerboundSetCarriedItemPacket(slot));
            }
            return Map.of("ok", true, "slot", slot, "previous", prev);
        });
    }

    public static Map<String, Object> typeText(String text) {
        final String t = (text == null) ? "" : text;
        return runOnClient(() -> {
            Screen s = Minecraft.getInstance().screen;
            if (s == null) {
                return Map.of("ok", false, "error", "no screen open");
            }
            int typed = 0;
            for (int i = 0; i < t.length(); ) {
                int cp = t.codePointAt(i);
                i += Character.charCount(cp);
                // Screen.charTyped takes a 16-bit char; non-BMP codepoints are
                // delivered as a surrogate pair, which matches GLFW's behaviour
                // for IME-composed characters.
                if (Character.isBmpCodePoint(cp)) {
                    s.charTyped((char) cp, 0);
                } else {
                    s.charTyped(Character.highSurrogate(cp), 0);
                    s.charTyped(Character.lowSurrogate(cp), 0);
                }
                typed++;
            }
            return Map.of("ok", true, "typed", typed, "length", t.length());
        });
    }

    /**
     * Replace the entire contents of a text input box. Targets the focused
     * {@link EditBox} if any; otherwise the box matched by {@code match}
     * (case-insensitive substring of its message/value), else the sole EditBox
     * on the screen. Calls {@link EditBox#setValue} so the value-listener fires
     * exactly as if the user had retyped it. Returns the new value.
     *
     * <p>This is the missing twin of {@link #typeText}: typeText APPENDS at the
     * cursor (and can't clear a pre-filled field), so to overwrite a field you
     * had to spam BACKSPACE first. setValue is atomic and reliable.
     */
    public static Map<String, Object> replaceText(String text, String match) {
        final String t = (text == null) ? "" : text;
        final String m = (match == null) ? "" : match.trim().toLowerCase(Locale.ROOT);
        return runOnClient(() -> {
            Screen s = Minecraft.getInstance().screen;
            if (s == null) return Map.of("ok", false, "error", "no screen open");
            List<EditBox> edits = new ArrayList<>();
            collectWidgets(s, null, edits);
            if (edits.isEmpty()) return Map.of("ok", false, "error", "no text box on screen");
            EditBox target = null;
            // 1) explicit match wins
            if (!m.isEmpty()) {
                for (EditBox e : edits) {
                    String v = e.getValue() == null ? "" : e.getValue();
                    String msg = e.getMessage() == null ? "" : e.getMessage().getString();
                    if (v.toLowerCase(Locale.ROOT).contains(m) || msg.toLowerCase(Locale.ROOT).contains(m)) {
                        target = e; break;
                    }
                }
                if (target == null) return Map.of("ok", false,
                        "error", "no text box matching '" + match + "' (found " + edits.size() + ")");
            }
            // 2) focused box
            if (target == null) {
                for (EditBox e : edits) { if (e.isFocused()) { target = e; break; } }
            }
            // 3) sole box
            if (target == null) {
                if (edits.size() == 1) target = edits.get(0);
                else return Map.of("ok", false,
                        "error", edits.size() + " text boxes and none focused — pass match");
            }
            String prev = target.getValue();
            target.setValue(t);
            target.moveCursorToEnd(false);
            return Map.of("ok", true, "value", target.getValue(),
                    "previous", prev == null ? "" : prev);
        });
    }

    /**
     * Read or set a GUI slider (an {@link AbstractSliderButton} — render distance,
     * volume, FOV, etc.). With {@code fraction == null} it just READS every slider
     * (label + current 0..1 value) so the agent can see the live value before
     * touching it. With a fraction in [0,1] it sets the matched slider's value,
     * fires the vanilla {@code applyValue}/{@code updateMessage} hooks (so the
     * option actually applies and the label refreshes), and returns the new
     * label + value.
     *
     * <p>Target selection mirrors {@link #replaceText}: explicit {@code match}
     * (case-insensitive substring of the slider's message, e.g. "render") first,
     * else {@code index} into the on-screen slider list, else the sole slider.
     */
    public static Map<String, Object> setSlider(String match, Integer index, Double fraction) {
        final String m = (match == null) ? "" : match.trim().toLowerCase(Locale.ROOT);
        return runOnClient(() -> {
            Screen s = Minecraft.getInstance().screen;
            if (s == null) return Map.of("ok", false, "error", "no screen open");
            List<AbstractSliderButton> sliders = new ArrayList<>();
            collectWidgets(s, sliders, null);
            if (sliders.isEmpty()) return Map.of("ok", false, "error", "no slider on screen");

            // READ mode — list every slider with its label + current value so the
            // agent always knows the live value (the value is only meaningful when
            // shown). Render-distance label like "Render Distance: 5 chunks".
            if (fraction == null) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (int i = 0; i < sliders.size(); i++) {
                    AbstractSliderButton sb = sliders.get(i);
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("index", i);
                    e.put("label", sb.getMessage().getString());
                    e.put("value", readSliderValue(sb));
                    list.add(e);
                }
                return Map.of("ok", true, "mode", "read", "count", sliders.size(), "sliders", list);
            }

            double f = Math.max(0.0, Math.min(1.0, fraction));
            AbstractSliderButton target = null;
            if (!m.isEmpty()) {
                for (AbstractSliderButton sb : sliders) {
                    if (sb.getMessage().getString().toLowerCase(Locale.ROOT).contains(m)) { target = sb; break; }
                }
                if (target == null) return Map.of("ok", false,
                        "error", "no slider matching '" + match + "' (found " + sliders.size() + ")");
            } else if (index != null) {
                if (index < 0 || index >= sliders.size()) return Map.of("ok", false,
                        "error", "slider index out of range 0.." + (sliders.size() - 1));
                target = sliders.get(index);
            } else if (sliders.size() == 1) {
                target = sliders.get(0);
            } else {
                return Map.of("ok", false,
                        "error", sliders.size() + " sliders — pass match or index");
            }

            double prev = readSliderValue(target);
            String prevLabel = target.getMessage().getString();
            // value/applyValue/updateMessage are protected in vanilla and opened by
            // worlddriver.accesswidener. applyValue() commits the new value to the
            // backing Option; updateMessage() refreshes the displayed label. Both must
            // run, and in that order, or the widget shows a stale caption for a value
            // the Option already has.
            target.value = f;
            target.applyValue();
            target.updateMessage();
            return Map.of("ok", true, "mode", "set",
                    "label", target.getMessage().getString(), "value", readSliderValue(target),
                    "previousLabel", prevLabel, "previousValue", prev);
        });
    }

    private static double readSliderValue(AbstractSliderButton sb) {
        return sb.value;
    }

    /**
     * Depth-first walk of a screen's widget tree collecting sliders and/or text
     * boxes. Recurses through {@link ContainerEventHandler} children so it
     * reaches options-list rows (each {@code OptionsList.Entry} is itself a
     * container holding the actual widgets), not just top-level renderables.
     * Pass {@code null} for a bucket you don't care about.
     */
    private static void collectWidgets(GuiEventListener node,
                                       List<AbstractSliderButton> sliders,
                                       List<EditBox> edits) {
        if (node instanceof AbstractSliderButton sb && sliders != null) sliders.add(sb);
        if (node instanceof EditBox eb && edits != null) edits.add(eb);
        if (node instanceof ContainerEventHandler c) {
            for (GuiEventListener child : c.children()) {
                if (child != node) collectWidgets(child, sliders, edits);
            }
        }
    }

    /** How long a click waits for the tick that carries its release before reporting it undelivered. */
    private static final long CLICK_RELEASE_MS = 2_000;

    /** One half of a key event: where it went, and whether that recipient took it. */
    private record Half(String via, boolean delivered) {}

    public static Map<String, Object> key(String key, String action, String route, Object modifiers) {
        final String kn = (key == null) ? "" : key.trim().toUpperCase(Locale.ROOT);
        final String act = (action == null || action.isBlank()) ? "click" : action.trim().toLowerCase(Locale.ROOT);
        final String rt = (route == null || route.isBlank()) ? "auto" : route.trim().toLowerCase(Locale.ROOT);
        int code = glfwKeyCode(kn);
        if (code < 0) {
            return Map.of("ok", false, "error", "unknown key name: " + kn);
        }
        if (!act.equals("press") && !act.equals("release") && !act.equals("click")) {
            return Map.of("ok", false, "error", "action must be press|release|click (got " + act + ")");
        }
        if (!rt.equals("auto") && !rt.equals("keybind") && !rt.equals("screen")) {
            return Map.of("ok", false, "error", "route must be auto|keybind|screen (got " + rt + ")");
        }
        final int mods;
        try {
            mods = modifierBits(modifiers);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "error", "modifiers take shift|ctrl|alt|super (got " + e.getMessage() + ")");
        }
        final int scan = org.lwjgl.glfw.GLFW.glfwGetKeyScancode(code);
        final boolean down = !act.equals("release");
        Map<String, Object> out = runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) {
                if (rt.equals("screen")) {
                    return Map.of("ok", false, "reason", "no_screen",
                            "error", "route screen was asked for, but no screen is open");
                }
                // Between worlds there is neither a screen nor a player; the event would reach
                // nothing, and the old {ok:true, via:"keybind"} said it had been delivered.
                if (mc.player == null) {
                    return Map.of("ok", false, "reason", "no_player",
                            "error", "no screen open and no player to receive the key — open a screen or join a world first");
                }
            }
            Half h = deliver(code, scan, rt, down, mods);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("key", kn);
            m.put("code", code);
            m.put("action", act);
            m.put("route", rt);
            m.put("modifiers", mods);
            m.put("via", h.via());
            m.put("pressed", down && h.delivered());
            m.put("released", !down && h.delivered());
            return m;
        });
        if (!act.equals("click") || !Boolean.TRUE.equals(out.get("ok"))) return out;
        // A real keystroke spans ticks, and a click that collapses into one does not act like one:
        // the press can open or close a screen, so the release belongs to whatever is open AFTER
        // it, and vanilla only consumes a keybind's click on a tick boundary. So release on the
        // next client tick, routed again from what is open then.
        Half rel = ClientThread.runNextTick(() -> deliver(code, scan, rt, false, mods), CLICK_RELEASE_MS);
        Map<String, Object> full = new LinkedHashMap<>(out);
        full.put("released", rel != null && rel.delivered());
        if (rel == null) {
            full.put("releaseNote", "the client did not tick within " + CLICK_RELEASE_MS
                    + " ms, so the key is still down — send action release once it ticks again");
        } else if (!rel.via().equals(out.get("via"))) {
            // The press moved the screen out from under the release; say so, because the caller's
            // next read of screen.info is explained by it.
            full.put("releaseVia", rel.via());
        }
        return full;
    }

    /**
     * Delivers one half of a key event and says where it went.
     *
     * <p>{@code route} picks the recipient: {@code screen} hands it to the open screen,
     * {@code keybind} to the key mappings, {@code auto} to the screen when one is open and to the
     * key mappings otherwise — which is the routing a real keystroke gets from vanilla.
     *
     * <p>With no screen open the event goes through {@code KeyboardHandler.keyPress} as a raw GLFW
     * event, so vanilla's own handling (ESC opens the pause menu, F3 debug, F5 perspective, Q drop,
     * T chat) runs exactly as it does for a player. That method is package-private in vanilla and
     * opened by worlddriver.accesswidener. Forcing {@code keybind} while a screen IS open cannot
     * use it — vanilla would hand the raw event to that screen — so it sets the mapping directly,
     * which is the same thing minus the screen's veto.
     */
    private static Half deliver(int code, int scan, String route, boolean down, int mods) {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        boolean toScreen = switch (route) {
            case "screen" -> true;
            case "keybind" -> false;
            default -> s != null;
        };
        if (toScreen) {
            if (s == null) return new Half("screen", false);
            return new Half("screen", down ? s.keyPressed(code, scan, mods) : s.keyReleased(code, scan, mods));
        }
        if (s == null) {
            long window = mc.getWindow().getWindow();
            int glfwAction = down ? org.lwjgl.glfw.GLFW.GLFW_PRESS : org.lwjgl.glfw.GLFW.GLFW_RELEASE;
            mc.keyboardHandler.keyPress(window, code, scan, glfwAction, mods);
            return new Half("keybind", true);
        }
        com.mojang.blaze3d.platform.InputConstants.Key k =
                com.mojang.blaze3d.platform.InputConstants.getKey(code, scan);
        net.minecraft.client.KeyMapping.set(k, down);
        if (down) net.minecraft.client.KeyMapping.click(k);
        return new Half("keybind", true);
    }

    /**
     * GLFW modifier bits from {@code ["alt"]}, {@code "alt+shift"}, or the raw int.
     *
     * <p>These reach a SCREEN, which is handed them as the {@code modifiers} argument it reads for
     * its own shortcuts. They do not reach a modified key binding: that match asks
     * {@code Screen.hasAltDown()}, which reads the real keyboard, so a bit set here is invisible to
     * it — {@code mc.client.input.keybind} is the verb for those. Throws
     * {@link IllegalArgumentException} naming the token it did not recognize.
     */
    private static int modifierBits(Object mods) {
        if (mods == null) return 0;
        if (mods instanceof Number n) return n.intValue();
        List<String> names = new ArrayList<>();
        if (mods instanceof List<?> l) {
            for (Object o : l) if (o != null) names.add(o.toString());
        } else {
            for (String part : mods.toString().split("[+,\\s]+")) names.add(part);
        }
        int bits = 0;
        for (String raw : names) {
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "" -> { }
                case "shift" -> bits |= org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
                case "ctrl", "control" -> bits |= org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
                case "alt" -> bits |= org.lwjgl.glfw.GLFW.GLFW_MOD_ALT;
                case "super", "meta", "cmd" -> bits |= org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER;
                default -> throw new IllegalArgumentException(raw);
            }
        }
        return bits;
    }

    private static int glfwKeyCode(String name) {
        switch (name) {
            case "ENTER": case "RETURN":     return org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
            case "ESCAPE": case "ESC":       return org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
            case "TAB":                      return org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
            case "BACKSPACE":                return org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
            case "DELETE": case "DEL":       return org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
            case "SPACE":                    return org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
            case "LEFT":                     return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
            case "RIGHT":                    return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
            case "UP":                       return org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
            case "DOWN":                     return org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
            case "HOME":                     return org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
            case "END":                      return org.lwjgl.glfw.GLFW.GLFW_KEY_END;
            case "PAGEUP":                   return org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP;
            case "PAGEDOWN":                 return org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN;
            // The modifiers, under their GLFW names and the short ones. Pressing one of these is
            // NOT how a modified keybind (ALT+Y) is reached — that match reads the real keyboard,
            // see ClientKeybinds — but a screen shortcut can want the key itself, and refusing the
            // name outright told callers the key did not exist.
            case "LEFT_ALT": case "LALT": case "ALT":
                return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;
            case "RIGHT_ALT": case "RALT":
                return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT;
            case "LEFT_CONTROL": case "LCTRL": case "CTRL": case "CONTROL":
                return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
            case "RIGHT_CONTROL": case "RCTRL":
                return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "LEFT_SHIFT": case "LSHIFT": case "SHIFT":
                return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
            case "RIGHT_SHIFT": case "RSHIFT":
                return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "LEFT_SUPER": case "LSUPER": case "SUPER": case "META": case "CMD":
                return org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER;
            case "RIGHT_SUPER": case "RSUPER":
                return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SUPER;
            default:
                // F1..F25
                if (name.length() >= 2 && name.charAt(0) == 'F') {
                    try {
                        int n = Integer.parseInt(name.substring(1));
                        if (n >= 1 && n <= 25) return org.lwjgl.glfw.GLFW.GLFW_KEY_F1 + (n - 1);
                    } catch (NumberFormatException ignored) {}
                }
                // single A..Z
                if (name.length() == 1) {
                    char c = name.charAt(0);
                    if (c >= 'A' && c <= 'Z') return org.lwjgl.glfw.GLFW.GLFW_KEY_A + (c - 'A');
                    if (c >= '0' && c <= '9') return org.lwjgl.glfw.GLFW.GLFW_KEY_0 + (c - '0');
                }
                return -1;
        }
    }
}

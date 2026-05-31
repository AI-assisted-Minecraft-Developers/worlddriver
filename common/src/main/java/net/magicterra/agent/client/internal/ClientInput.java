package net.magicterra.agent.client.internal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

import static net.magicterra.agent.client.internal.ClientThread.runOnClient;

/**
 * Input synthesis for {@code mc.client.screen.close} and {@code mc.client.input.*}
 * (click / slotClick / mouseMove / setHotbarSlot / typeText / key). Stateless;
 * extracted from {@code ClientAgentApiImpl}.
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
        final String tn = (type == null || type.isBlank()) ? "pickup" : type.trim().toLowerCase(java.util.Locale.ROOT);
        // Map our short names to the vanilla enum. Surface the mapping in the
        // tool description so callers don't have to crack open ClickType.java.
        net.minecraft.world.inventory.ClickType ct;
        switch (tn) {
            case "pickup"     -> ct = net.minecraft.world.inventory.ClickType.PICKUP;
            case "quickmove"  -> ct = net.minecraft.world.inventory.ClickType.QUICK_MOVE;
            case "swap"       -> ct = net.minecraft.world.inventory.ClickType.SWAP;
            case "clone"      -> ct = net.minecraft.world.inventory.ClickType.CLONE;
            case "throw"      -> ct = net.minecraft.world.inventory.ClickType.THROW;
            case "pickupall"  -> ct = net.minecraft.world.inventory.ClickType.PICKUP_ALL;
            case "quickcraft" -> ct = net.minecraft.world.inventory.ClickType.QUICK_CRAFT;
            default -> {
                return Map.of("ok", false,
                        "error", "type must be one of pickup|quickMove|swap|clone|throw|pickupAll|quickCraft (got " + type + ")");
            }
        }
        final net.minecraft.world.inventory.ClickType clickType = ct;
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
            // 2) On Xvfb, glfwSetCursorPos doesn't fire the cursor_pos callback,
            //    so MouseHandler.xpos/ypos stay stale and tooltips render at
            //    the *previous* hover spot. Write them directly via reflection.
            String reflStatus = "ok";
            try {
                var mh = mc.mouseHandler;
                java.lang.reflect.Field fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
                java.lang.reflect.Field fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
                fx.setAccessible(true);
                fy.setAccessible(true);
                fx.setDouble(mh, wx);
                fy.setDouble(mh, wy);
            } catch (ReflectiveOperationException e) {
                reflStatus = "FAILED: " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            if (s != null) s.mouseMoved(x, y);
            return Map.of("ok", true, "wx", wx, "wy", wy, "scale", scale, "refl", reflStatus);
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
                p.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
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

    public static Map<String, Object> key(String key, String action) {
        final String kn = (key == null) ? "" : key.trim().toUpperCase(java.util.Locale.ROOT);
        final String act = (action == null || action.isBlank()) ? "click" : action.trim().toLowerCase(java.util.Locale.ROOT);
        int code = glfwKeyCode(kn);
        if (code < 0) {
            return Map.of("ok", false, "error", "unknown key name: " + kn);
        }
        if (!act.equals("press") && !act.equals("release") && !act.equals("click")) {
            return Map.of("ok", false, "error", "action must be press|release|click (got " + act + ")");
        }
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            int scan = org.lwjgl.glfw.GLFW.glfwGetKeyScancode(code);
            // No screen open → dispatch as a raw GLFW key event through
            // KeyboardHandler.keyPress so in-game keybinds (F3 debug, F5
            // perspective, Q drop, F swap hands, T chat, etc.) fire exactly
            // like a player pressing the key. Reflective because the method
            // is package-private in vanilla.
            if (s == null) {
                long window = mc.getWindow().getWindow();
                int glfwPress = org.lwjgl.glfw.GLFW.GLFW_PRESS;
                int glfwRelease = org.lwjgl.glfw.GLFW.GLFW_RELEASE;
                try {
                    java.lang.reflect.Method m = net.minecraft.client.KeyboardHandler.class
                            .getDeclaredMethod("keyPress", long.class, int.class, int.class, int.class, int.class);
                    m.setAccessible(true);
                    boolean pressed = false, released = false;
                    if (act.equals("press") || act.equals("click")) {
                        m.invoke(mc.keyboardHandler, window, code, scan, glfwPress, 0);
                        pressed = true;
                    }
                    if (act.equals("release") || act.equals("click")) {
                        m.invoke(mc.keyboardHandler, window, code, scan, glfwRelease, 0);
                        released = true;
                    }
                    return Map.of("ok", true, "key", kn, "code", code, "action", act,
                        "pressed", pressed, "released", released, "via", "keybind");
                } catch (ReflectiveOperationException e) {
                    return Map.of("ok", false, "error",
                        "keybind dispatch failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
            boolean pressed = false, released = false;
            if (act.equals("press") || act.equals("click")) {
                pressed = s.keyPressed(code, scan, 0);
            }
            if (act.equals("release") || act.equals("click")) {
                released = s.keyReleased(code, scan, 0);
            }
            return Map.of("ok", true, "key", kn, "code", code, "action", act,
                "pressed", pressed, "released", released, "via", "screen");
        });
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

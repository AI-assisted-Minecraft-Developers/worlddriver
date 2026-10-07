package net.magicterra.worlddriver.client.internal;

import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientRefusal;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Refused;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ClickParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Clicked;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.CloseParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Closed;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.HotbarParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.HotbarSelected;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyAction;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyRoute;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.KeySent;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyVia;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Modifier;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.MoveParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Moved;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ReplaceParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Replaced;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Slider;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SliderParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.SliderSet;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.SlidersRead;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SlotClickParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.SlotClicked;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.TypeParams;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Typed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;

import java.util.Optional;

import net.magicterra.worlddriver.bot.util.ClientHop;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.events.ContainerEventHandler;

/**
 * Input synthesis for {@code mc.client.screen.close} and {@code mc.client.input.*}
 * (click / slotClick / mouseMove / setHotbarSlot / typeText / replaceText / slider / key).
 * Stateless. Each write checks the caller's {@code contextId} on the client thread as its
 * first step, so a refused one has done nothing.
 */
public final class ClientInput {
    private ClientInput() {}

    public static Closed closeScreen(CloseParams p) {
        return ClientHop.call(() -> {
            ScreenContext.require(p.contextId());
            Minecraft mc = Minecraft.getInstance();
            Optional<String> was = Optional.ofNullable(mc.screen).map(s -> s.getClass().getSimpleName());
            mc.setScreen(null);
            return new Closed(was, ScreenContext.current());
        });
    }

    private static Refused noScreen() {
        return new Refused(ClientRefusal.NO_SCREEN, "no screen is open");
    }

    private static Refused noPlayer() {
        return new Refused(ClientRefusal.NO_PLAYER, "no player is in a world on this client");
    }

    public static ClientReply click(ClickParams p) {
        return ClientHop.call(() -> {
            ScreenContext.require(p.contextId());
            Screen s = Minecraft.getInstance().screen;
            if (s == null) return noScreen();
            // Fire mouseMoved first so HoverButton/Tooltip widgets register the
            // cursor over the target before the click lands — otherwise widgets
            // that toggle on hover (e.g. AbstractSelectionList row highlight)
            // miss the state transition and the click looks like it landed on a
            // non-active widget.
            s.mouseMoved(p.x(), p.y());
            boolean handled = s.mouseClicked(p.x(), p.y(), p.button());
            s.mouseReleased(p.x(), p.y(), p.button());
            return new Clicked(handled, ScreenContext.current());
        });
    }

    public static ClientReply slotClick(SlotClickParams p) {
        ClickType clickType = switch (p.type()) {
            case PICKUP -> ClickType.PICKUP;
            case QUICK_MOVE -> ClickType.QUICK_MOVE;
            case SWAP -> ClickType.SWAP;
            case CLONE -> ClickType.CLONE;
            case THROW -> ClickType.THROW;
            case PICKUP_ALL -> ClickType.PICKUP_ALL;
            case QUICK_CRAFT -> ClickType.QUICK_CRAFT;
        };
        return ClientHop.call(() -> {
            ScreenContext.require(p.contextId());
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof AbstractContainerScreen<?> acs)) {
                return new Refused(ClientRefusal.NO_CONTAINER, "the open screen holds no container menu");
            }
            if (mc.player == null || mc.gameMode == null) return noPlayer();
            var menu = acs.getMenu();
            if (p.slot() >= menu.slots.size()) {
                return new Refused(ClientRefusal.NO_TARGET,
                        "slot " + p.slot() + " is past the menu's last slot " + (menu.slots.size() - 1));
            }
            // Same path AbstractContainerScreen.slotClicked takes — routes
            // through MultiPlayerGameMode so the server sees a real
            // ServerboundContainerClickPacket and the menu state stays in sync.
            mc.gameMode.handleInventoryMouseClick(menu.containerId, p.slot(), p.button(), clickType, mc.player);
            return new SlotClicked(p.slot(), p.button(), p.type(), ScreenContext.current());
        });
    }

    public static Moved mouseMove(MoveParams p) {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            double scale = mc.getWindow().getGuiScale();
            double wx = p.x() * scale;
            double wy = p.y() * scale;
            // 1) Move the real GLFW cursor — drives any "cursor visible"
            //    indicator and is what live users would experience.
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), wx, wy);
            // 2) On Xvfb, glfwSetCursorPos doesn't fire the cursor_pos callback, so
            //    MouseHandler.xpos/ypos stay stale and tooltips render at the *previous*
            //    hover spot. The access widener in :common opens those fields, so this is a
            //    plain field write that tiny-remapper rewrites like any other reference.
            var mh = mc.mouseHandler;
            mh.xpos = wx;
            mh.ypos = wy;
            if (s != null) s.mouseMoved(p.x(), p.y());
            return new Moved(wx, wy, scale);
        });
    }

    public static ClientReply setHotbarSlot(HotbarParams p) {
        return ClientHop.call(() -> {
            ScreenContext.require(p.contextId());
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return noPlayer();
            int prev = player.getInventory().selected;
            player.getInventory().selected = p.slot();
            // Sync to server so subsequent useItem / useItemOn / attack packets
            // resolve against the new held item. Mirrors how vanilla key
            // 1..9 press dispatches (Inventory.swapPaint → setCarriedItemPacket).
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(p.slot()));
            }
            return new HotbarSelected(p.slot(), prev, ScreenContext.current());
        });
    }

    public static ClientReply typeText(TypeParams p) {
        String t = p.text();
        return ClientHop.call(() -> {
            ScreenContext.require(p.contextId());
            Screen s = Minecraft.getInstance().screen;
            if (s == null) return noScreen();
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
            return new Typed(typed, t.length(), ScreenContext.current());
        });
    }

    /**
     * Replace the entire contents of a text input box: the box matched by {@code match}
     * (case-insensitive substring of its message/value), else the focused {@link EditBox},
     * else the sole EditBox on the screen. Calls {@link EditBox#setValue} so the value-listener
     * fires exactly as if the user had retyped it.
     *
     * <p>This is the missing twin of {@link #typeText}: typeText APPENDS at the
     * cursor (and can't clear a pre-filled field), so to overwrite a field you
     * had to spam BACKSPACE first. setValue is atomic and reliable.
     */
    public static ClientReply replaceText(ReplaceParams p) {
        String m = p.match().map(x -> x.trim().toLowerCase(Locale.ROOT)).orElse("");
        return ClientHop.call(() -> {
            ScreenContext.require(p.contextId());
            Screen s = Minecraft.getInstance().screen;
            if (s == null) return noScreen();
            List<EditBox> edits = new ArrayList<>();
            collectWidgets(s, null, edits);
            if (edits.isEmpty()) return new Refused(ClientRefusal.NO_TARGET, "no text box on screen");
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
                if (target == null) return new Refused(ClientRefusal.NO_TARGET,
                        "no text box matching '" + p.match().get() + "' (found " + edits.size() + ")");
            }
            // 2) focused box
            if (target == null) {
                for (EditBox e : edits) { if (e.isFocused()) { target = e; break; } }
            }
            // 3) sole box
            if (target == null) {
                if (edits.size() == 1) target = edits.get(0);
                else return new Refused(ClientRefusal.AMBIGUOUS, edits.size() + " text boxes and none focused — pass match");
            }
            String prev = target.getValue();
            target.setValue(p.text());
            target.moveCursorToEnd(false);
            return new Replaced(target.getValue(), prev == null ? "" : prev, ScreenContext.current());
        });
    }

    /**
     * Read or set a GUI slider (an {@link AbstractSliderButton} — render distance,
     * volume, FOV, etc.). With no fraction it just READS every slider
     * (label + current 0..1 value) so the agent can see the live value before
     * touching it. With a fraction it sets the matched slider's value,
     * fires the vanilla {@code applyValue}/{@code updateMessage} hooks (so the
     * option actually applies and the label refreshes), and returns the new
     * label + value.
     *
     * <p>Target selection: explicit {@code match} (case-insensitive substring of the slider's
     * message, e.g. "render") first, else {@code index} into the on-screen slider list, else the
     * sole slider.
     */
    public static ClientReply slider(SliderParams p) {
        String m = p.match().map(x -> x.trim().toLowerCase(Locale.ROOT)).orElse("");
        return ClientHop.call(() -> {
            ScreenContext.require(p.contextId());
            Screen s = Minecraft.getInstance().screen;
            if (s == null) return noScreen();
            List<AbstractSliderButton> sliders = new ArrayList<>();
            collectWidgets(s, sliders, null);
            if (sliders.isEmpty()) return new Refused(ClientRefusal.NO_TARGET, "no slider on screen");

            // READ mode — list every slider with its label + current value so the
            // agent always knows the live value (the value is only meaningful when
            // shown). Render-distance label like "Render Distance: 5 chunks".
            if (p.fraction().isEmpty()) {
                List<Slider> list = new ArrayList<>();
                for (int i = 0; i < sliders.size(); i++) {
                    AbstractSliderButton sb = sliders.get(i);
                    list.add(new Slider(i, sb.getMessage().getString(), sb.value));
                }
                return new SlidersRead(list, ScreenContext.current());
            }

            AbstractSliderButton target = null;
            if (!m.isEmpty()) {
                for (AbstractSliderButton sb : sliders) {
                    if (sb.getMessage().getString().toLowerCase(Locale.ROOT).contains(m)) { target = sb; break; }
                }
                if (target == null) return new Refused(ClientRefusal.NO_TARGET,
                        "no slider matching '" + p.match().get() + "' (found " + sliders.size() + ")");
            } else if (p.index().isPresent()) {
                int index = p.index().get();
                if (index >= sliders.size()) return new Refused(ClientRefusal.NO_TARGET,
                        "slider index " + index + " is past the last one, " + (sliders.size() - 1));
                target = sliders.get(index);
            } else if (sliders.size() == 1) {
                target = sliders.get(0);
            } else {
                return new Refused(ClientRefusal.AMBIGUOUS, sliders.size() + " sliders — pass match or index");
            }

            double prev = target.value;
            String prevLabel = target.getMessage().getString();
            // value/applyValue/updateMessage are protected in vanilla and opened by
            // worlddriver.accesswidener. applyValue() commits the new value to the
            // backing Option; updateMessage() refreshes the displayed label. Both must
            // run, and in that order, or the widget shows a stale caption for a value
            // the Option already has.
            target.value = p.fraction().get();
            target.applyValue();
            target.updateMessage();
            return new SliderSet(target.getMessage().getString(), target.value, prevLabel, prev, ScreenContext.current());
        });
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

    /**
     * One half of a key event: where it went, whether it was handed over at all, and — only when
     * the recipient is a screen, the one recipient that answers — whether it took it.
     *
     * <p>The two are different questions and were once one field. A screen that ignores a key
     * returns false, and so does a key that was never delivered; reporting both as
     * {@code pressed:false} made "ESC did not close this screen" indistinguishable from "ESC was
     * never sent". Vanilla screens in particular almost never consume a key RELEASE, so a
     * correctly delivered click reads as {@code handled:false} on its second half every time.
     */
    private record Half(KeyVia via, boolean delivered, Boolean handled) {}

    /** That half, plus the screen standing open once it had landed and its context. */
    private record Tail(Half half, String screenAfter, String contextId) {}

    /**
     * The screen open right now, or {@code none}.
     *
     * <p>In every key reply because a keystroke is the thing that opens screens, and the caller's
     * NEXT keystroke is routed by whatever this one left behind: a key sent at a screen that the
     * caller does not know is there comes back {@code via:"screen"} with the screen named here,
     * which is the only field that separates "the verb is broken" from "something else was
     * listening".
     */
    private static String screenName() {
        Screen s = Minecraft.getInstance().screen;
        return s == null ? "none" : s.getClass().getSimpleName();
    }

    public static ClientReply key(KeyParams p) {
        String kn = p.key().trim().toUpperCase(Locale.ROOT);
        int code = glfwKeyCode(kn);
        if (code < 0) throw ApiException.invalidParam("/key", ApiIssue.Code.INVALID_VALUE, "unknown key name: " + p.key());
        int mods = modifierBits(p.modifiers());
        int scan = org.lwjgl.glfw.GLFW.glfwGetKeyScancode(code);
        boolean down = p.action() != KeyAction.RELEASE;
        ClientReply first = ClientHop.call(() -> {
            ScreenContext.require(p.contextId());
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) {
                if (p.route() == KeyRoute.SCREEN) {
                    return new Refused(ClientRefusal.NO_SCREEN, "route screen was asked for, but no screen is open");
                }
                // Between worlds there is neither a screen nor a player; the event would reach
                // nothing, and claiming it was delivered would be false.
                if (mc.player == null) {
                    return new Refused(ClientRefusal.NO_PLAYER,
                            "no screen open and no player to receive the key — open a screen or join a world first");
                }
            }
            Half h = deliver(code, scan, p.route(), down, mods);
            Optional<Boolean> handled = Optional.ofNullable(h.handled());
            return new KeySent(kn, code, p.action(), p.route(), p.modifiers(), h.via(), down && h.delivered(),
                    !down && h.delivered(), down ? handled : Optional.empty(), down ? Optional.empty() : handled,
                    Optional.empty(), screenName(), Optional.empty(), ScreenContext.current());
        });
        if (p.action() != KeyAction.CLICK || !(first instanceof KeySent pressed)) return first;
        // A real keystroke spans ticks, and a click that collapses into one does not act like one:
        // the press can open or close a screen, so the release belongs to whatever is open AFTER
        // it, and vanilla only consumes a keybind's click on a tick boundary. So release on the
        // next client tick, routed again from what is open then.
        Tail rel = ClientThread.runNextTick(
                () -> new Tail(deliver(code, scan, p.route(), false, mods), screenName(), ScreenContext.current()),
                CLICK_RELEASE_MS);
        if (rel == null) {
            return new KeySent(kn, code, p.action(), p.route(), p.modifiers(), pressed.via(), pressed.pressed(), false,
                    pressed.pressHandled(), Optional.empty(), Optional.empty(), pressed.screenAfter(),
                    Optional.of("the client did not tick within " + CLICK_RELEASE_MS
                            + " ms, so the key is still down — send action release once it ticks again"),
                    pressed.contextId());
        }
        // The press can move the screen out from under the release; say so, because the caller's
        // next read of screen.info is explained by it.
        Optional<KeyVia> releaseVia = rel.half().via() == pressed.via() ? Optional.empty() : Optional.of(rel.half().via());
        return new KeySent(kn, code, p.action(), p.route(), p.modifiers(), pressed.via(), pressed.pressed(),
                rel.half().delivered(), pressed.pressHandled(), Optional.ofNullable(rel.half().handled()), releaseVia,
                rel.screenAfter(), Optional.empty(), rel.contextId());
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
    private static Half deliver(int code, int scan, KeyRoute route, boolean down, int mods) {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        boolean toScreen = switch (route) {
            case SCREEN -> true;
            case KEYBIND -> false;
            case AUTO -> s != null;
        };
        if (toScreen) {
            if (s == null) return new Half(KeyVia.SCREEN, false, null);
            boolean took = down ? s.keyPressed(code, scan, mods) : s.keyReleased(code, scan, mods);
            return new Half(KeyVia.SCREEN, true, took);
        }
        // The keybind routes report no `handled`: neither KeyboardHandler.keyPress nor
        // KeyMapping.set answers, and inventing a `true` would claim a consumption nobody checked.
        if (s == null) {
            long window = mc.getWindow().getWindow();
            int glfwAction = down ? org.lwjgl.glfw.GLFW.GLFW_PRESS : org.lwjgl.glfw.GLFW.GLFW_RELEASE;
            mc.keyboardHandler.keyPress(window, code, scan, glfwAction, mods);
            return new Half(KeyVia.KEYBIND, true, null);
        }
        com.mojang.blaze3d.platform.InputConstants.Key k =
                com.mojang.blaze3d.platform.InputConstants.getKey(code, scan);
        net.minecraft.client.KeyMapping.set(k, down);
        if (down) net.minecraft.client.KeyMapping.click(k);
        return new Half(KeyVia.KEYBIND, true, null);
    }

    /**
     * GLFW modifier bits.
     *
     * <p>These reach a SCREEN, which is handed them as the {@code modifiers} argument it reads for
     * its own shortcuts. They do not reach a modified key binding: that match asks
     * {@code Screen.hasAltDown()}, which reads the real keyboard, so a bit set here is invisible to
     * it — {@code mc.client.input.keybind} is the verb for those.
     */
    private static int modifierBits(List<Modifier> mods) {
        int bits = 0;
        for (Modifier m : mods) {
            bits |= switch (m) {
                case SHIFT -> org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
                case CTRL -> org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
                case ALT -> org.lwjgl.glfw.GLFW.GLFW_MOD_ALT;
                case SUPER -> org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER;
                case CAPS_LOCK -> org.lwjgl.glfw.GLFW.GLFW_MOD_CAPS_LOCK;
                case NUM_LOCK -> org.lwjgl.glfw.GLFW.GLFW_MOD_NUM_LOCK;
            };
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

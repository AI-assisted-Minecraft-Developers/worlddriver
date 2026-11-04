package net.magicterra.worlddriver.testcontent;

import java.util.List;
import net.magicterra.worlddriver.api.game.ScreenTrees;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A widget that keeps its own element tree, as a UI library's root widget does: to vanilla it is one widget with no
 * children, and it hands each click and character to its elements itself. Two tabs, a button that opens an overlay, a
 * text field, and the overlay with its close button, hidden until opened. Its walker lists them for screen.tree.
 *
 * <p>Opened on the client by a system message starting {@link #OPEN}, so a test on the server can open it.
 */
public final class TestPanel extends AbstractWidget {

    /** The system message that opens the panel's screen on the client. */
    public static final String OPEN = "wd.panelTest:open";

    static {
        // Registered as the panel's class is first used, so on the client only; a mod registers from its client setup.
        ScreenTrees.register(TestPanel.class, TestPanel::list);
    }

    /** One element: drawn at its box, holding the elements of {@code under}. */
    private static final class Part {
        final String type;
        final String label;
        final int x;
        final int y;
        final int width;
        final int height;
        final List<Part> under;

        Part(String type, String label, int x, int y, int width, int height, List<Part> under) {
            this.type = type;
            this.label = label;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.under = under;
        }

        boolean at(double px, double py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }

    private final Part tabA = new Part("PanelTab", "Tab A", 30, 30, 50, 16, List.of());
    private final Part tabB = new Part("PanelTab", "Tab B", 84, 30, 50, 16, List.of());
    private final Part openOverlay = new Part("PanelButton", "Open overlay", 30, 52, 104, 16, List.of());
    private final Part field = new Part("PanelField", "Name", 30, 74, 104, 16, List.of());
    private final Part close = new Part("PanelButton", "Close", 150, 60, 50, 16, List.of());
    private final Part overlay = new Part("PanelOverlay", "Overlay", 144, 30, 62, 60, List.of(close));
    private final List<Part> parts = List.of(tabA, tabB, openOverlay, field, overlay);

    private Part tab = tabA;
    private boolean overlayOpen;
    private boolean typing;
    private final StringBuilder text = new StringBuilder();

    TestPanel() {
        super(20, 20, 200, 90, Component.literal("TestPanel"));
    }

    /** Opens the panel's screen when {@code message} asks to. Client thread. */
    public static void received(String message) {
        if (message.startsWith(OPEN)) {
            Minecraft.getInstance().setScreen(new PanelScreen());
        }
    }

    private void list(ScreenTrees.Sink out) {
        for (Part p : parts) {
            add(out, p, p != overlay || overlayOpen);
        }
    }

    private void add(ScreenTrees.Sink out, Part p, boolean shown) {
        ScreenTrees.Element e = ScreenTrees.Element.of(p.type)
                .box(p.x, p.y, p.width, p.height)
                .visible(shown)
                .label(p.label);
        if (p.type.equals("PanelTab")) {
            e = e.selected(p == tab);
        }
        if (p == field) {
            e = e.value(text.toString()).focused(typing);
        }
        ScreenTrees.Sink inside = out.add(p, e);
        for (Part child : p.under) {
            add(inside, child, shown);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        Part hit = null;
        if (overlayOpen && close.at(x, y)) {
            hit = close;
        } else {
            for (Part p : List.of(tabA, tabB, openOverlay, field)) {
                if (p.at(x, y)) {
                    hit = p;
                }
            }
        }
        typing = hit == field;
        if (hit == tabA || hit == tabB) {
            tab = hit;
        } else if (hit == openOverlay) {
            overlayOpen = true;
        } else if (hit == close) {
            overlayOpen = false;
        }
        return hit != null;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (!typing) {
            return false;
        }
        text.append(c);
        return true;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(getX(), getY(), getX() + width, getY() + height, 0xFF202020);
        for (Part p : parts) {
            if (p != overlay || overlayOpen) {
                draw(g, p);
            }
        }
    }

    private void draw(GuiGraphics g, Part p) {
        g.fill(p.x, p.y, p.x + p.width, p.y + p.height, p == tab ? 0xFF4060A0 : 0xFF505050);
        String shown = p == field ? text.toString() : p.label;
        g.drawString(Minecraft.getInstance().font, shown, p.x + 3, p.y + 4, 0xFFFFFFFF);
        for (Part child : p.under) {
            draw(g, child);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }

    /** The screen the panel is the only widget of. */
    static final class PanelScreen extends Screen {
        PanelScreen() {
            super(Component.literal("Panel test"));
        }

        @Override
        protected void init() {
            addRenderableWidget(new TestPanel());
        }
    }
}

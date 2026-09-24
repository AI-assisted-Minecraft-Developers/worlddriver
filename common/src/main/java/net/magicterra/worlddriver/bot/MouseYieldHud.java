package net.magicterra.worlddriver.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The on-screen "the bot is driving" badge. Rendered from each loader's HUD hook
 * (Fabric {@code HudRenderCallback}, NeoForge {@code RenderGuiEvent.Post}) — both hand
 * us a {@link GuiGraphics}, so the drawing itself lives here once.
 *
 * <p>Two lines, top-right: WHAT is driving, and WHO owns the mouse. Top-right because
 * the F3 debug overlay owns the top-left and boss bars the top-centre. It answers the
 * question a released cursor otherwise leaves open — "is the game broken, or is the bot
 * working?" — and advertises the way back in (double-tap ESC).
 */
public final class MouseYieldHud {
    private MouseYieldHud() {}

    private static final int COLOR_DRIVING = 0xFFFFB300;   // amber: bot has the body
    private static final int COLOR_YIELDED = 0xFFB0B0B0;   // grey: informational
    private static final int COLOR_RECLAIM = 0xFF7FD98A;   // green: you hold the mouse
    private static final int COLOR_BACKDROP = 0x90000000;

    private static final int MARGIN = 4;
    private static final int LINE_H = 10;

    /** Draw the badge if the bot is driving. No-op otherwise (and while the HUD is hidden). */
    public static void render(GuiGraphics gfx) {
        if (!BotConfig.mouseYieldHud || gfx == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.options == null || mc.options.hideGui) return;
        if (!MouseYield.driving()) return;

        String who = MouseYield.driver();
        String line1 = who.isEmpty() ? "● BOT in control" : "● BOT in control · " + who;
        String line2;
        int color2;
        if (MouseYield.reclaimed()) {
            line2 = "You have the mouse · the bot is still acting";
            color2 = COLOR_RECLAIM;
        } else if (MouseYield.yielded()) {
            line2 = "Mouse released · double-press ESC to take it back";
            color2 = COLOR_YIELDED;
        } else {
            // Driving but nothing released — mouseYield off, or a screen owns the cursor.
            line2 = "Double-press ESC to take back the mouse";
            color2 = COLOR_YIELDED;
        }

        Font font = mc.font;
        int width = Math.max(font.width(line1), font.width(line2));
        int right = mc.getWindow().getGuiScaledWidth() - MARGIN;
        int x = right - width;
        int y = MARGIN;

        gfx.fill(x - 3, y - 2, right + 2, y + LINE_H * 2 + 1, COLOR_BACKDROP);
        gfx.drawString(font, line1, x, y, COLOR_DRIVING);
        gfx.drawString(font, line2, x, y + LINE_H, color2);
    }
}

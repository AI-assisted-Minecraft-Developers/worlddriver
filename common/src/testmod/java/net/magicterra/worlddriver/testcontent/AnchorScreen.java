package net.magicterra.worlddriver.testcontent;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The anchor's screen, the structure block's idea applied to a scene: the name the scene is
 * found by, the box it spans (two corners as offsets from the anchor), the facing when the anchor
 * is the start, and the verbs. "Detect" reads the box off the corner markers around the anchor on
 * the client's copy of the world; everything else is one chat command to the server —
 * {@code /worlddriver anchor …} to store, then {@code /worlddriver scene save|place|run <name>} —
 * so the screen adds no network packet and no behaviour the commands do not already have.
 */
public final class AnchorScreen extends Screen {
    private static final int FIELD_H = 18;

    private final MarkerBlockEntity anchor;
    private final BlockPos pos;
    private final MarkerRole role;

    private EditBox name;
    private EditBox[] min;
    private EditBox[] max;
    private EditBox yaw;

    AnchorScreen(MarkerBlockEntity anchor) {
        super(Component.translatable("screen.worlddriver.anchor.title",
                anchor.getBlockState().getValue(MarkerBlock.ROLE).getSerializedName() + " " + anchor.getBlockPos().toShortString()));
        this.anchor = anchor;
        this.pos = anchor.getBlockPos();
        this.role = anchor.getBlockState().getValue(MarkerBlock.ROLE);
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        int y = 40;
        name = text(left + 100, y, 200, anchor.label(), 64);
        y += 30;
        int[] box = anchor.box();
        min = numbers(left + 100, y, box == null ? new int[3] : new int[] { box[0], box[1], box[2] });
        y += 24;
        max = numbers(left + 100, y, box == null ? new int[3] : new int[] { box[3], box[4], box[5] });
        y += 30;
        if (role == MarkerRole.START) {
            yaw = text(left + 100, y, 60, trim(anchor.args().getDouble("yaw")), 8);
            yaw.setFilter(s -> s.matches("-?\\d*(\\.\\d*)?"));
            y += 30;
        }
        y += 6;
        int bw = 72;
        addRenderableWidget(Button.builder(Component.translatable("screen.worlddriver.anchor.detect"), b -> detect())
                .bounds(left, y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worlddriver.anchor.save"), b -> verb("save"))
                .bounds(left + bw + 4, y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worlddriver.anchor.place"), b -> verb("place"))
                .bounds(left + 2 * (bw + 4), y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worlddriver.anchor.run"), b -> verb("run"))
                .bounds(left + 3 * (bw + 4), y, bw, 20).build());
        y += 28;
        addRenderableWidget(Button.builder(Component.translatable("screen.worlddriver.anchor.done"), b -> { if (apply()) onClose(); })
                .bounds(width / 2 - 104, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.worlddriver.anchor.cancel"), b -> onClose())
                .bounds(width / 2 + 4, y, 100, 20).build());
        setInitialFocus(name);
    }

    private EditBox text(int x, int y, int w, String value, int maxLength) {
        EditBox box = new EditBox(font, x, y, w, FIELD_H, Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(value);
        return addRenderableWidget(box);
    }

    private EditBox[] numbers(int x, int y, int[] values) {
        EditBox[] out = new EditBox[3];
        for (int i = 0; i < 3; i++) {
            out[i] = text(x + i * 56, y, 50, Integer.toString(values[i]), 7);
            out[i].setFilter(s -> s.matches("-?\\d*"));
        }
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int left = width / 2 - 150;
        g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        g.drawString(font, Component.translatable("screen.worlddriver.anchor.name"), left, 45, 0xA0A0A0);
        g.drawString(font, Component.translatable("screen.worlddriver.anchor.min"), left, 75, 0xA0A0A0);
        g.drawString(font, Component.translatable("screen.worlddriver.anchor.max"), left, 99, 0xA0A0A0);
        g.drawString(font, Component.translatable("screen.worlddriver.anchor.rel"), left + 100, 122, 0x707070);
        if (yaw != null) g.drawString(font, Component.translatable("screen.worlddriver.anchor.yaw"), left, 135, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- actions

    /** The box off the corner markers around the anchor, on the client's copy of the world. */
    private void detect() {
        if (minecraft == null || minecraft.level == null) return;
        try {
            FixtureBuilder.Box box = FixtureBuilder.cornerBoxAround(FixtureIO.scan(minecraft.level, pos), pos);
            BlockPos lo = box.min().subtract(pos), hi = box.max().subtract(pos);
            min[0].setValue(Integer.toString(lo.getX()));
            min[1].setValue(Integer.toString(lo.getY()));
            min[2].setValue(Integer.toString(lo.getZ()));
            max[0].setValue(Integer.toString(hi.getX()));
            max[1].setValue(Integer.toString(hi.getY()));
            max[2].setValue(Integer.toString(hi.getZ()));
        } catch (IllegalArgumentException e) {
            say(Component.translatable("screen.worlddriver.anchor.noCorners"));
        }
    }

    /** Stores name, box and facing on the anchor. False when a field is not a number. */
    private boolean apply() {
        if (minecraft == null || minecraft.player == null) return false;
        int[] box = new int[6];
        double facing = 0;
        try {
            for (int i = 0; i < 3; i++) {
                box[i] = Integer.parseInt(min[i].getValue().trim());
                box[3 + i] = Integer.parseInt(max[i].getValue().trim());
            }
            if (yaw != null) facing = Double.parseDouble(yaw.getValue().trim());
        } catch (NumberFormatException e) {
            say(Component.translatable("screen.worlddriver.anchor.badNumber"));
            return false;
        }
        String label = name.getValue().trim();
        StringBuilder cmd = new StringBuilder("worlddriver anchor ")
                .append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                .append(' ').append(role.getSerializedName())
                .append(' ').append(label.isEmpty() ? "-" : label);
        for (int v : box) cmd.append(' ').append(v);
        if (yaw != null) cmd.append(' ').append(trim(facing));
        minecraft.player.connection.sendCommand(cmd.toString());
        return true;
    }

    /** Stores, then runs one scene verb on the name; the report arrives in chat. */
    private void verb(String verb) {
        if (!apply()) return;
        String label = name.getValue().trim();
        if (label.isEmpty()) {
            say(Component.translatable("screen.worlddriver.anchor.needName"));
            return;
        }
        minecraft.player.connection.sendCommand("worlddriver scene " + verb + " " + label);
        onClose();
    }

    private void say(Component what) {
        if (minecraft != null && minecraft.player != null) minecraft.player.displayClientMessage(what, false);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Double.toString(v);
    }
}

package net.magicterra.worlddriver.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientRefusal;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Refused;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.SlotClicked;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ClickKind;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.PlayerMenu;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SlotClickParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SlotRow;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;

/**
 * The menu open on a server player: read it for {@code mc.observe.container} with {@code bot}, click it for
 * {@code mc.client.input.slotClick} with {@code bot}. Both run on the server thread, so neither needs a client. Also
 * the one place a {@link ClickKind} becomes vanilla's {@link ClickType}.
 */
public final class PlayerMenus {
    private static final String PREFIX = "player:";

    private PlayerMenus() {}

    /** Vanilla's click type for the API's click kind. */
    public static ClickType clickType(ClickKind kind) {
        return switch (kind) {
            case PICKUP -> ClickType.PICKUP;
            case QUICK_MOVE -> ClickType.QUICK_MOVE;
            case SWAP -> ClickType.SWAP;
            case CLONE -> ClickType.CLONE;
            case THROW -> ClickType.THROW;
            case PICKUP_ALL -> ClickType.PICKUP_ALL;
            case QUICK_CRAFT -> ClickType.QUICK_CRAFT;
        };
    }

    /** The player a {@code player:<name>} ref names, or null: a roster bot or a joined player. */
    private static ServerPlayer find(MinecraftServer server, String bot) {
        return BotRegistry.serverPlayer(server, bot);
    }

    /** Server thread. Throws {@code invalid_value} at {@code /bot} when no such player is here. */
    public static PlayerMenu read(MinecraftServer server, String bot) {
        ServerPlayer player = find(server, bot);
        if (player == null) {
            throw ApiException.invalidParam(
                    "/bot",
                    ApiIssue.Code.INVALID_VALUE,
                    "no player " + bot.substring(PREFIX.length()) + " on this server");
        }
        AbstractContainerMenu menu = player.containerMenu;
        // The inventory menu has no type: getType() throws there.
        Optional<String> type = menu == player.inventoryMenu
                ? Optional.empty()
                : Optional.of(BuiltInRegistries.MENU.getKey(menu.getType()).toString());
        List<SlotRow> rows = new ArrayList<>(menu.slots.size());
        for (int i = 0; i < menu.slots.size(); i++) {
            rows.add(ItemSnap.slotRow(i, menu.getSlot(i).getItem()));
        }
        Optional<SlotRow> carried =
                menu.getCarried().isEmpty() ? Optional.empty() : Optional.of(ItemSnap.slotRow(0, menu.getCarried()));
        return new PlayerMenu(bot, type, menu.containerId, menu.getStateId(), rows, carried);
    }

    /** Server thread. As the client's click packet would, then the change is sent to the player's own client. */
    public static ClientReply click(MinecraftServer server, SlotClickParams p) {
        ServerPlayer player = find(server, p.bot().orElseThrow());
        if (player == null) {
            return new Refused(ClientRefusal.NO_PLAYER, "no such player on this server");
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (p.slot() >= menu.slots.size()) {
            return new Refused(
                    ClientRefusal.NO_TARGET,
                    "slot " + p.slot() + " is past the menu's last slot " + (menu.slots.size() - 1));
        }
        menu.clicked(p.slot(), p.button(), clickType(p.type()), player);
        menu.broadcastChanges();
        return new SlotClicked(p.slot(), p.button(), p.type(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}

package net.magicterra.worlddriver.testcontent.neoforge;

import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.worlddriver.testcontent.TestPlayerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Fires {@link TestPlayerEvents} from NeoForge's login event, which it posts at the end of
 * {@code PlayerList.placeNewPlayer}. Game-bus listeners only; see {@link NeoForgeTestContent}.
 */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = TestMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class NeoForgeTestPlayerEvents {
    private NeoForgeTestPlayerEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TestPlayerEvents.fireJoin(player);
        }
    }
}

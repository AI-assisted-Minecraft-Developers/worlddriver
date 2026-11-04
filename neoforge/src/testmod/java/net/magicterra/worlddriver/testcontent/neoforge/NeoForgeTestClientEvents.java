package net.magicterra.worlddriver.testcontent.neoforge;

import net.magicterra.worlddriver.testcontent.TestClientLog;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

/** Hands {@link TestClientLog} the system messages the client receives. Game bus, client only. */
@SuppressWarnings("removal")
@EventBusSubscriber(modid = TestMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class NeoForgeTestClientEvents {
    private NeoForgeTestClientEvents() {}

    @SubscribeEvent
    public static void onSystemMessage(ClientChatReceivedEvent.System event) {
        TestClientLog.received(event.getMessage().getString());
    }
}

package net.magicterra.agent.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotApiImpl;
import net.magicterra.agent.bot.BotHooks;
import net.magicterra.agent.client.ClientAgentApiImpl;
import net.magicterra.agent.client.ClientHooks;

/**
 * Fabric client-side bootstrap. Only loaded under Dist.CLIENT — Fabric Loader
 * skips client entrypoints on a dedicated-server install — so referencing
 * {@link ClientAgentApiImpl} here doesn't drag client classes into dedi-server
 * classloading.
 */
public final class AgentDriverFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientHooks.register(new ClientAgentApiImpl());
        BotApiImpl bot = new BotApiImpl();
        BotHooks.register(bot);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> bot.clientTick());
        AgentDriverCommon.LOG.info("[{}] Fabric client api + bot registered", AgentDriverCommon.MOD_ID);
    }
}

package net.magicterra.agent.neoforge.sim;

/**
 * NeoForge shim (P1.6 Task 1): static-delegation onto the common
 * {@link net.magicterra.agent.bot.sim.ServerAgentManager}, which is now the SINGLE
 * registry. Keeping this FQN + static API means {@code AgentDriverNeoForge}'s
 * per-server-tick {@code ServerAgentManager.tickAll()}, {@code /agentserver}'s
 * register/clear, and the dogfood scenes all land in the same common list — with
 * zero source changes at any caller.
 */
public final class ServerAgentManager {
    private ServerAgentManager() {}

    public static void register(ServerAgentDriver d) { net.magicterra.agent.bot.sim.ServerAgentManager.register(d); }
    public static void unregister(ServerAgentDriver d) { net.magicterra.agent.bot.sim.ServerAgentManager.unregister(d); }
    public static int activeCount() { return net.magicterra.agent.bot.sim.ServerAgentManager.activeCount(); }
    public static void clear() { net.magicterra.agent.bot.sim.ServerAgentManager.clear(); }

    /** Advance every registered driver one tick; drop finished/crashed ones. */
    public static void tickAll() { net.magicterra.agent.bot.sim.ServerAgentManager.tickAll(); }
}

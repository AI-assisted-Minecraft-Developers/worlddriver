package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;

/** A bot behaviour driven once per client tick. Implementations live in this
 *  package; the host {@code BotApiImpl} owns the single active process. */

public interface BotProcess {
    String kind();
    void attach(BotState st);
    /** @return true when finished (success or unrecoverable failure). */
    boolean tick(Minecraft mc, WorldView w, BotState st);
}

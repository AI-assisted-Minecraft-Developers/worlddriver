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

    /** Called when this process regains the movement channel after being
     *  suspended by a higher-priority chain (panic/retreat/combat). Default
     *  no-op; pathing processes override to force a repath from the current
     *  position rather than reuse a path that went stale during suspension. */
    default void onResume() {}

    /** Optional sub-state string for {@code mc.bot.status} (key
     *  {@code activeProcessDetail}). Default {@code null} = no detail. Multi-phase
     *  processes override this so the agent can tell e.g. a bunker that is still
     *  DIG_DOWN/CARVE/STEP_IN/PLUG (working) apart from one that is SEALED
     *  (safe — wait for dawn then cancel) or DONE (finished, may be unsealed) —
     *  without it, every phase looks identical ("bunker") and the agent can
     *  cancel a working shelter or trust a failed one. */
    default String statusDetail() { return null; }
}

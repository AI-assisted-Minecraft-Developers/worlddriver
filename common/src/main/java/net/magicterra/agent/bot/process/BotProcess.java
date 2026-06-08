package net.magicterra.agent.bot.process;

import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.movement.Avatar;
import net.magicterra.agent.bot.movement.ClientPlayerAvatar;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;

/** A bot behaviour driven once per tick. Implementations live in this package;
 *  the host {@code BotApiImpl} owns the single active process (client), and
 *  {@code ServerAgentDriver} (neoforge) can run an Avatar-migrated process
 *  headless on the server tick.
 *
 *  <p><b>Avatar migration seam.</b> The canonical method is
 *  {@link #tick(Avatar, WorldView, BotState)}; it drives whatever entity the
 *  {@code Avatar} wraps (client {@code LocalPlayer} or server {@code FakePlayer}),
 *  exactly like {@code Walker.tick(Avatar, ...)}. The legacy
 *  {@link #tick(Minecraft, WorldView, BotState)} is a default bridge that wraps
 *  {@code mc} in a {@link ClientPlayerAvatar} (1:1 passthrough, zero regression),
 *  so the client scheduler/chains keep calling it unchanged.
 *
 *  <p>Processes migrate ONE AT A TIME:
 *  <ul>
 *    <li><b>Migrated</b> process overrides {@code tick(Avatar,...)} only — the
 *        client reaches it via the bridge, the server calls it directly.</li>
 *    <li><b>Not-yet-migrated</b> process overrides {@code tick(Minecraft,...)}
 *        only — the client works as before; the server hits the default
 *        {@code tick(Avatar,...)} which throws (correct: it is client-only).</li>
 *  </ul> */

public interface BotProcess {
    String kind();
    void attach(BotState st);

    /** Canonical per-tick driver over the controlled entity.
     *  @return true when finished (success or unrecoverable failure).
     *  Default throws — a process is server-runnable only once it overrides this. */
    default boolean tick(Avatar a, WorldView w, BotState st) {
        throw new UnsupportedOperationException(
                kind() + " has no Avatar (server-side) path yet — still client-only");
    }

    /** Legacy client entry point. Default bridges to {@link #tick(Avatar, WorldView, BotState)}
     *  via a {@link ClientPlayerAvatar}; not-yet-migrated processes override this
     *  directly instead. @return true when finished. */
    default boolean tick(Minecraft mc, WorldView w, BotState st) {
        return tick(new ClientPlayerAvatar(mc), w, st);
    }

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

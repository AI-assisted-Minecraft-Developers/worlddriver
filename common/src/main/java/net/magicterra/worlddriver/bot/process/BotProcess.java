package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.movement.ClientPlayerAvatar;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;

/** A bot behaviour driven once per tick. Implementations live in this package;
 *  the host {@code BotApiImpl} owns the single active process (client), and
 *  {@code ServerWorldDriver} (neoforge) can run an Avatar-migrated process
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
 *  <p><b>The migration is finished.</b> Every process in this package overrides
 *  {@code tick(Avatar,...)} and none overrides {@code tick(Minecraft,...)}; the only
 *  override of the legacy signature left anywhere is a stub inside a scene. The bridge
 *  and the throwing default below are therefore not a staging area any more — the bridge
 *  is the live client entry point (the scheduler and every chain still call it), and the
 *  default throw is now only reachable by a NEW process that forgets to implement the
 *  canonical one. Adding a process means overriding {@code tick(Avatar,...)}; there is no
 *  longer a "not yet migrated" state to be in. */

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
     *  suspended by a higher-priority chain (panic/retreat/combat), so it can force a repath
     *  from the current position rather than reuse a path that went stale during suspension.
     *
     *  <p><b>Exactly one process actually does that</b> ({@code IntentProcess}). The other
     *  nine that own a {@code Walker} — Mine, Follow, Explore, Build, Backfill, BboxFill,
     *  Farm, Bridge, Tower — inherit this no-op and resume on a path computed from a position
     *  the body may have been dragged out of. {@code UserTaskChain} calls the hook faithfully;
     *  there is simply nothing on the other end. Written down rather than fixed because
     *  「pathing processes override」 read as a description and was a wish. */
    default void onResume() {}

    /** Called when this process is cancelled/superseded before finishing
     *  naturally (e.g. {@code mc.bot.cancel}, or a new goto replacing this one), so it can
     *  finalize per-session observers — fire the pathfinder's terminal so a path archive is
     *  flushed for the partial run — before the channel is released.
     *
     *  <p>Same story as {@link #onResume}: {@code IntentProcess} is the only override, so
     *  every cancelled mine/build/farm/follow drops its path archive on the floor. */
    default void onCancelled(String reason) {}

    /** Optional sub-state string for {@code mc.bot.status} (key
     *  {@code activeProcessDetail}). Default {@code null} = no detail. Multi-phase
     *  processes override this so the agent can tell e.g. a bunker that is still
     *  DIG_DOWN/CARVE/STEP_IN/PLUG (working) apart from one that is SEALED
     *  (safe — wait for dawn then cancel) or DONE (finished, may be unsealed) —
     *  without it, every phase looks identical ("bunker") and the agent can
     *  cancel a working shelter or trust a failed one. */
    default String statusDetail() { return null; }
}

package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;

/** A bot behaviour driven once per tick. Implementations live in this package;
 *  the host {@code BotApiImpl} owns the single active process (client), and
 *  {@code ServerWorldDriver} can run the same process headless on the server tick.
 *
 *  <p>The one entry point is {@link #tick(Body, WorldView, BotState)}: it drives whatever
 *  body the {@code Body} wraps (the client's {@code LocalPlayer}, a joined server player),
 *  exactly like {@code Walker.tick(Body, ...)}. The scheduler's chains hand a process the
 *  body the scheduler was given, and the client tick chain builds a {@code ClientPlayerBody}
 *  once per tick for that. There used to be a {@code tick(Minecraft, …)} bridge here for the
 *  client callers; nothing overrode it and nothing calls it now, so it is gone, and a process
 *  that names {@code Minecraft} in its own signature is a process that will not run on a
 *  server body. Adding a process means overriding the canonical method; there is no
 *  "not yet migrated" state to be in. */

public interface BotProcess {
    String kind();
    void attach(BotState st);

    /** Per-tick driver over the controlled body.
     *  @return true when finished (success or unrecoverable failure). */
    boolean tick(Body a, WorldView w, BotState st);

    /** Why the process stopped short of what it was asked to do, or null when it did it. Read
     *  once, right after {@link #tick} returned true, and surfaced as the {@code error} of
     *  {@code lastProcessEnd}. Not defaulted: the slot's {@code lastError} cannot answer this,
     *  because several processes write their success summary there ({@code "done (placed=…)"}). */
    String failure();

    /** Called when this process regains the movement channel after being
     *  suspended by a higher-priority chain (panic/retreat/combat), so it can force a repath
     *  from the current position rather than reuse a path that went stale during suspension.
     *
     *  <p><b>Exactly one process actually does that</b> ({@code IntentProcess}). The other
     *  nine that own a {@code Walker} and run under {@code UserTaskChain} — Mine, Follow,
     *  Explore, Build, Backfill, BboxFill, Farm, RunAway, Sleep — inherit this no-op and resume
     *  on a path computed from a position the body may have been dragged out of.
     *  {@code UserTaskChain} calls the hook faithfully; there is simply nothing on the other end.
     *  Written down rather than fixed because 「pathing processes override」 read as a
     *  description and was a wish.
     *
     *  <p>The membership of that list is measured off {@code new Walker(} and off who hands the
     *  process to {@code UserTaskChain.setProcess}, not off which verbs feel path-shaped — it read
     *  「…Farm, Bridge, Tower」 for a while and both of those own no {@code Walker} at all (they use
     *  {@code WalkerGeometry} only), which quietly excused the two that do. Two more Walker owners
     *  are outside the list on purpose: {@code ReplayProcess} overrides this explicitly to a no-op
     *  (its plan is fixed, so repathing would discard the thing it exists to replay), and
     *  {@code CombatProcess} is minted by {@code CombatChain} rather than {@code UserTaskChain}, so
     *  this hook never reaches it — {@code CombatChain.onResume} is its own empty override. */
    default void onResume() {}

    /** Called when this process is cancelled/superseded before finishing
     *  naturally (e.g. {@code mc.bot.cancel}, or a new goto replacing this one), so it can
     *  finalize per-session observers — fire the pathfinder's terminal so a path archive is
     *  flushed for the partial run — before the channel is released.
     *
     *  <p><b>No process in this package flushes an archive here except {@code IntentProcess}.</b>
     *  It is not the only OVERRIDE — {@code MineProcess} overrides it too — but that one releases
     *  the log-cost waiver ({@code logWaiverOwner}) and never touches its {@code Walker}, so a
     *  cancelled mine drops its path archive exactly like build/farm/follow do. Count the overrides
     *  with {@code grep -rn "void onCancelled"}, and read each body before concluding it repaths:
     *  「overrides the hook」and「finalizes the walker」are two different questions, and this
     *  sentence answered the second with the first for a while. */
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

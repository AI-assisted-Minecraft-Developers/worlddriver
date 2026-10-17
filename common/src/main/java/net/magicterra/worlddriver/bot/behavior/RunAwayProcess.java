package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Walker;
import net.magicterra.worlddriver.bot.control.WalkerSettings;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;

public final class RunAwayProcess implements BotProcess {
    private final BlockPos from;
    private final int minDist;
    /** Status slot to report into. Lets the AUTO-retreat reflex (RetreatChain) use
     *  its own slot ({@code state.retreat}) instead of the user mc.bot.runAway verb's
     *  slot ({@code state.runAway}) — the two used to share one, so a reflex flee
     *  stomped a user flee's status and left it stuck active forever. Null = the
     *  user-verb default ({@code state.runAway}). */
    private final BotState.ProcessSlot reportSlot;
    private final Walker walker;

    public RunAwayProcess(BlockPos from, int minDist) { this(from, minDist, null); }

    public RunAwayProcess(BlockPos from, int minDist, BotState.ProcessSlot slot) {
        this.from = from;
        this.minDist = minDist;
        this.reportSlot = slot;
        // gap#72-④ owner tag: an explicit slot means the RetreatChain reflex owns this
        // flee (see reportSlot's javadoc) — attribute its searches to the chain, not
        // the user verb, so latest.log distinguishes "I told it to flee" from "it fled".
        this.walker = new Walker(WalkerSettings.named(slot != null ? "retreat" : "runAway"));
        walker.start(new Goal.RunAway(from, minDist));
    }

    /** The slot to report into — the explicit one, or {@code state.runAway} by default. */
    private BotState.ProcessSlot slot(BotState st) {
        return reportSlot != null ? reportSlot : st.runAway;
    }

    public String kind() { return "runAway"; }
    public void attach(BotState st) {
        BotState.ProcessSlot s = slot(st);
        s.active = true;
        s.goal = "runAway from=" + from + " minDist=" + minDist;
        s.target = from;
        s.startedAtMs = System.currentTimeMillis();
        s.lastError = null;
    }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        // Flee-context: mark this frame as an active flee BEFORE the Walker runs
        // its A* search, so ClientWorldView.beginSearch snapshots fleeSearch=true
        // and boosts water/ledge danger (no diving into water / off a cliff while
        // running). Covers both the RetreatChain reflex and user mc.bot.runAway —
        // both drive this process. Cleared at the start of each bot tick.
        w.signals().fleeing = true;
        BotState.ProcessSlot s = slot(st);
        Locomotor.Status step = walker.tick(a, w);
        s.pathLen = walker.pathLen();
        s.pathStep = walker.pathStep();
        if (step == Locomotor.Status.MOVING) return false;
        if (step == Locomotor.Status.FAILED) s.lastError = walker.result().reason();
        s.reset();
        failure = walker.result().reason();
        return true;
    }

    private String failure;

    @Override public String failure() { return failure; }
}

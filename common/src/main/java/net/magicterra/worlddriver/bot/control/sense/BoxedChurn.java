package net.magicterra.worlddriver.bot.control.sense;

import net.minecraft.core.BlockPos;

public final class BoxedChurn {
    public BlockPos base; // foot at the start of the current net-displacement window

    public int windowTicks; // ticks elapsed in the current window

    public int escapes; // consecutive windows that detected churn (escalates the charge radius)

    public int
            hColRamTicks; // wall-corner: consecutive ticks of sustained horizontalCollision (the signature of the bot

    // pinned against a wall)
    public int cycle; // this window: -1 got nearer the goal, 0 near base, 1 left it, 2 came back to it
    // (StallDetectPhase.trackWindow)

    public void resetForNewGoal() {
        base = null;
        windowTicks = 0;
        escapes = 0;
    }
}

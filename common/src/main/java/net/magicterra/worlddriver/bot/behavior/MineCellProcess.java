package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Break one named cell: walk within two blocks of it unless it is already in reach, then hold the dig on it with the
 * best tool until it gives. {@code mc.bot.mine} with a {@code pos}; it reports into the mine slot.
 *
 * <p>It gives up when the walk ends with the cell out of reach (no open face, or too far from the eye), or when the dig
 * has been held {@link #DIG_BUDGET_TICKS} ticks without the block changing: a block the hand cannot break in that time
 * is not going to break.
 */
public final class MineCellProcess implements BotProcess, Navigating {
    /** Ten seconds of holding the dig: obsidian with a diamond pickaxe takes about nine. */
    static final int DIG_BUDGET_TICKS = 200;

    private final BlockPos target;
    private final Navigator navigator = new Navigator(NavigatorSettings.named("mineCell"));

    @Override
    public Navigator navigator() {
        return navigator;
    }

    private Block initial;
    private boolean walking;
    private int digTicks;
    private boolean broken;
    private String failure;

    public MineCellProcess(BlockPos target) {
        this.target = target.immutable();
    }

    /** Whether the cell gave. */
    public boolean broken() {
        return broken;
    }

    @Override
    public String kind() {
        return "mine";
    }

    @Override
    public String failure() {
        return failure;
    }

    @Override
    public void onResume() {
        if (walking) navigator.replan();
    }

    @Override
    public void attach(BotState st) {
        st.mine.beginRun("cell " + target.toShortString(), target);
    }

    @Override
    public boolean tick(Body a, WorldView w, BotState st) {
        Equipment equipment = a.equipment().orElse(null);
        BlockInteraction hands = a.blockInteraction().orElse(null);
        if (a.entity() == null || equipment == null || hands == null) return fail(st, Readiness.Reason.NO_HANDS);
        BlockState now = a.entity().level().getBlockState(target);
        if (initial == null) {
            if (now.isAir()) return fail(st, "nothing to break at " + target.toShortString());
            initial = now.getBlock();
            walking = !hands.canBreak(target);
            if (walking) navigator.start(new Goal.Near(target, 2));
        }
        if (now.isAir() || now.getBlock() != initial) {
            broken = true;
            hands.breakHold(false);
            st.mine.lastError = null;
            return true;
        }
        if (walking) {
            Locomotor.Status s = navigator.tick(a, w);
            if (s == Locomotor.Status.MOVING) return false;
            walking = false;
            if (!hands.canBreak(target)) {
                return fail(st, target.toShortString() + " is out of reach from where the walk ended (" + s + ")");
            }
        }
        if (++digTicks > DIG_BUDGET_TICKS) {
            return fail(
                    st,
                    "held the dig on " + target.toShortString() + " for " + DIG_BUDGET_TICKS
                            + " ticks and it did not break");
        }
        equipment.selectTool(target);
        a.aimAtBlock(target);
        hands.breakHold(true);
        hands.continueDestroy(target);
        return false;
    }

    private boolean fail(BotState st, String why) {
        failure = why;
        st.mine.lastError = why;
        return true;
    }
}

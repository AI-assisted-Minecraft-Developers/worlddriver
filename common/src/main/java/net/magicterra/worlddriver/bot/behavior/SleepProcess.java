package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorSettings;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;

public final class SleepProcess implements BotProcess, Navigating {
    /** This tick's hands, bound at the top of {@link #tick}, which is the one place they can be absent. */
    private BlockInteraction blocks;
    private static final int USE_TIMEOUT_TICKS = 40;        // ~2 s — bed reply is one tick after the click
    private static final int CLICK_INTERVAL_TICKS = 10;     // re-click cadence within USE phase

    private final BlockPos explicit;
    private final int searchRadius;
    private final Navigator navigator = new Navigator(NavigatorSettings.named("sleep"));
    @Override public Navigator navigator() { return navigator; }

    private BlockPos bedPos;
    private int useTicks;
    private int sinceLastClick;
    private Phase phase = Phase.SEARCH;
    private enum Phase { SEARCH, GOING, USE, DONE }

    public SleepProcess(BlockPos explicit, int radius) {
        this.explicit = explicit;
        this.searchRadius = radius;
    }

    public String kind() { return "sleep"; }

    /** The bed it chose, or null before it found one. */
    public BlockPos bedPos() { return bedPos; }

    public void attach(BotState st) {
        st.mc_goto.active = true;
        st.mc_goto.goal = explicit != null
                ? "sleep[bed@" + explicit + "]"
                : "sleep[nearest bed within " + searchRadius + "]";
        st.mc_goto.startedAtMs = System.currentTimeMillis();
        st.mc_goto.lastError = null;
    }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        if (p == null) return giveUp(st, "player vanished");
        blocks = a.blockInteraction().orElse(null);
        if (blocks == null) return giveUp(st, Readiness.Reason.NO_HANDS);
        Level lvl = p.level();

        switch (phase) {
            case SEARCH -> {
                BlockPos found = explicit != null ? explicit : scanNearestBed(lvl, p);
                if (found == null || !lvl.getBlockState(found).is(BlockTags.BEDS)) {
                    return giveUp(st, "no bed within " + searchRadius);
                }
                bedPos = found;
                st.mc_goto.target = bedPos;
                navigator.start(new Goal.Near(bedPos, 2));
                phase = Phase.GOING;
            }
            case GOING -> {
                Locomotor.Status s = navigator.tick(a, w);
                st.mc_goto.pathLen = navigator.pathLen();
                st.mc_goto.pathStep = navigator.pathStep();
                if (s == Locomotor.Status.FAILED) {
                    return giveUp(st, "no path to bed @" + bedPos);
                }
                if (s.ended()) {
                    // A give-up also stops as ARRIVED. The bed may still be in reach from there, so
                    // click anyway, but a click that never lands is then the approach's fault.
                    approachShortfall = navigator.result().reason();
                    a.releaseInputs();
                    useTicks = 0;
                    sinceLastClick = CLICK_INTERVAL_TICKS;  // click immediately on first USE tick
                    phase = Phase.USE;
                }
            }
            case USE -> {
                // Bed may have been griefed during the walk.
                if (!lvl.getBlockState(bedPos).is(BlockTags.BEDS)) {
                    return giveUp(st, "bed disappeared during approach");
                }
                if (p.isSleeping()) {
                    st.mc_goto.lastError = "done (sleeping)";
                    st.mc_goto.reset();
                    phase = Phase.DONE;
                    return true;
                }
                aimAt(p, bedPos.getX() + 0.5, bedPos.getY() + 0.5, bedPos.getZ() + 0.5);
                // Bed interaction requires not-sneaking — vanilla treats
                // sneak+right-click on a bed as "place item against bed"
                // rather than "enter bed". clientUseItemOn no longer
                // unsneaks unconditionally, so do it explicitly here.
                a.commandSneak(false);
                p.setShiftKeyDown(false);
                if (++sinceLastClick >= CLICK_INTERVAL_TICKS) {
                    sinceLastClick = 0;
                    blocks.placeOn(bedPos, Direction.UP);
                }
                if (++useTicks > USE_TIMEOUT_TICKS) {
                    return giveUp(st, approachShortfall != null
                            ? "did not reach bed @" + bedPos + ": " + approachShortfall
                            : "bed click did not start sleep (wrong time / monsters / occupied)");
                }
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private boolean giveUp(BotState st, String why) {
        failure = why;
        st.mc_goto.lastError = why;
        st.mc_goto.reset();
        return true;
    }

    private String failure;
    private String approachShortfall;

    @Override public String failure() { return failure; }

    /** Measured from the feet cell, unlike a station, which is ranked from the eye. */
    private BlockPos scanNearestBed(Level lvl, LivingEntity p) {
        BlockPos foot = blockPosOf(p);
        return nearestMatching(lvl, foot, Vec3.atCenterOf(foot), s -> s.is(BlockTags.BEDS), Double.POSITIVE_INFINITY,
                searchRadius, Math.min(searchRadius, 8));
    }

}

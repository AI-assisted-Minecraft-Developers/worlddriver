package net.magicterra.worlddriver.bot.stagewright.journey;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;

/**
 * Stand still for N ticks and let physics happen.
 *
 * <p>Sounds like nothing; it is the only way to make this body fall. A server avatar has no
 * free-running physics — {@code ServerAvatarManager} steps only the avatars a registered driver is
 * ticking — so an unregistered body hangs in the air over the hole it just dug. Every process this
 * suite had available also STEERS, and steering is exactly what ruins a shaft.
 *
 * <p>Measured, twice. {@code Goal.YLevel(targetY)} descends to the wrong place: "be at y=60" is
 * satisfied anywhere, and the walker took the cheapest way down it could find, landing six blocks
 * off the ore column. {@code Goal.Block(cellJustEmptied)} names the right cell and still fails in
 * the field: the body stood exactly on the surveyed column ({@code arrived.horizontalDistance=0}),
 * broke the floor clean through, and the walker then carried it sideways one cell at a time —
 * {@code 72 → 73 → 74} — because open ground offers it alternatives that a sealed test arena does
 * not. {@code wd.serverSelfShaftDescends} passes precisely because there is nowhere else to go
 * there.
 *
 * <p>Public because the sealed arena that models this routine ({@code wd.serverTowersOutOfADeepShaft})
 * needs the same non-steering settle, and an arena that models the journey with a DIFFERENT
 * settle is not modelling the journey.
 *
 * <p>So the settle asks for no movement at all. Break the block under your feet and wait: that is
 * what a player does, and gravity does not need a pathfinder.
 */
public final class HoldStill implements BotProcess {

    private final int ticks;
    private int elapsed;

    public HoldStill(int ticks) { this.ticks = ticks; }

    @Override public String kind() { return "holdStill"; }

    @Override public void attach(BotState st) { }

    @Override
    public boolean tick(Avatar a, WorldView w, BotState st) {
        // Release everything a previous process may have latched. A leftover forward impulse would
        // walk the body off the hole just as surely as a goal would.
        a.commandMove(0, 0);
        a.commandJump(false);
        a.breakHold(false);
        return ++elapsed >= ticks;
    }
}

package net.magicterra.worlddriver.bot.stagewright.journey;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;

/**
 * Stand still for N ticks and let physics happen.
 *
 * <p>Sounds like nothing; it is the only way to make this bot fall. A server avatar has no
 * free-running physics — {@code ServerAvatarManager} steps only the avatars a registered driver is
 * ticking — so an unregistered bot hangs in the air over the hole it just dug. Every process this
 * suite had available also STEERS, and steering is exactly what ruins a shaft.
 *
 * <p>Measured, twice. {@code Goal.YLevel(targetY)} descends to the wrong place: "be at y=60" is
 * satisfied anywhere, and the walker took the cheapest way down it could find, landing six blocks
 * off the ore column. {@code Goal.Block(cellJustEmptied)} names the right cell and still fails in
 * the field: the bot stood exactly on the surveyed column ({@code arrived.horizontalDistance=0}),
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
 *
 * <h2>Waiting longer is not a safer settle, and neither is "wait until it lands"</h2>
 *
 * <p>Every ray query in {@link JourneyFill} wants the bot at rest, but a wait of "until the
 * position stops changing, capped at ten ticks" hit the cap on essentially every call in the
 * rehearsal (2026-08-16). The evidence showed why: {@code recover0.ask.settled} reported that the
 * bot was still moving after 10 ticks (eye y 57.62 to 57.62, 0.57 blocks moved in total), which is
 * a steady height with half a block of horizontal drift. The recovery happens in an alcove that
 * the rung has just flooded, and flowing water pushes an idle player entity every tick, so "at
 * rest" is a state this rung never reaches.
 *
 * <p>The wait also moves the bot. {@code recover8.ask.settled} reported the bot still moving
 * after 10 ticks (eye y 61.65 to 58.06, 3.60 blocks moved in total): eight extra ticks of falling
 * put the bot on the alcove floor, from which {@code standToFill} found no spot and the fill
 * walked. That walk, with the casting phase's {@code allowBreak} still enabled, mined a cast frame
 * cell to climb back ({@code frame.lost.1} attributed the loss to the step "recover8 collects
 * water from -9, 61, 38") and left the bot too low to open cell nine at all. A settle that lets
 * the bot travel is not a settle.
 *
 * <p>Two ticks is therefore the number, and the fills' fix for a stale aim is to re-aim AFTER those
 * two ticks rather than to spend more of them — see {@code JourneyFill.scoop}.
 */
public final class HoldStill implements BotProcess {

    private final int ticks;
    private int elapsed;

    public HoldStill(int ticks) { this.ticks = ticks; }

    @Override public String kind() { return "holdStill"; }
    @Override public String failure() { return null; }

    @Override public void attach(BotState st) { }

    @Override
    public boolean tick(Body a, WorldView w, BotState st) {
        // Release everything a previous process may have latched. A leftover forward impulse would
        // walk the bot off the hole just as surely as a goal would.
        a.commandMove(0, 0);
        a.commandJump(false);
        a.hands().ifPresent(h -> h.breakHold(false));
        return ++elapsed >= ticks;
    }
}

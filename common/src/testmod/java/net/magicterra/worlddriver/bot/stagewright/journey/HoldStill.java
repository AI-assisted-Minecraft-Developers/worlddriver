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
 *
 * <h2>Waiting LONGER is not a safer settle, and it is not「wait until it lands」either</h2>
 *
 * <p>Tried, measured, reverted (2026-08-16). Every ray question in {@link JourneyFill} wants the
 * body at rest, so this class briefly grew an「until the position stops changing, capped at ten
 * ticks」mode. The rehearsal that ran on it reported the cap on essentially every call, and the rows
 * say why: {@code recover0.ask.settled = 等了 10 tick 身体还在动（眼睛 y 57.62→57.62，共挪了 0.57
 * 格）} — height dead steady, half a block of HORIZONTAL drift. The recover happens in an alcove the
 * rung has just flooded and flowing water pushes an idle body every tick, so「at rest」is a state
 * this rung does not have.
 *
 * <p>Worse, the wait is itself a mover. {@code recover8.ask.settled = 等了 10 tick 身体还在动（眼睛
 * y 61.65→58.06，共挪了 3.60 格）}: eight extra ticks of falling put the body on the alcove floor,
 * from which {@code standToFill} found nothing and the fill WALKED — and that walk, with the
 * casting phase's {@code allowBreak} still on, mined a cast frame cell to climb back
 * ({@code frame.lost.1 … 丢在「recover8 从 -9, 61, 38 收水」这一步里}) and left the body too low to
 * open cell nine at all. A settle that lets the body travel is not a settle.
 *
 * <p>Two ticks is therefore the number, and the fills' fix for a stale aim is to re-aim AFTER those
 * two ticks rather than to spend more of them — see {@code JourneyFill.scoop}.
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

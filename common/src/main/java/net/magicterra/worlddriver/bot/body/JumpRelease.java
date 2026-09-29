package net.magicterra.worlddriver.bot.body;

/**
 * Lets go of a held jump key for one tick when vanilla would otherwise sit on it.
 *
 * <p>{@code LivingEntity.aiStep} arms {@code noJumpDelay = 10} on every take-off and clears it only on
 * a tick the key is up. A hop cut short — a ceiling over the launch cell, a riser met before the
 * rise — lands two ticks later with the key still down, and the body then stands grounded for the
 * other eight (live 1546,69,-156.7 under birch leaves: {@code jump=true onG=true hCol=true} for 8
 * ticks, every run through it). A player taps the key per step and never meets the delay; one released
 * tick costs nothing, since the held key could not have fired on it either.
 *
 * <p>This mirrors the delay from the jumps it sent, so it is only as exact as {@code grounded}: pass
 * {@code onGround} out of water and lava, where vanilla's take-off branch is the only one that arms it.
 */
public final class JumpRelease {
    private int delay;

    /** The jump to send this tick, given the one asked for; call once per body tick. With
     *  {@code mayRelease} false it only keeps count (a human on the keyboard is not overridden). */
    public boolean apply(boolean want, boolean grounded, boolean mayRelease) {
        boolean send = want && !(mayRelease && grounded && delay > 1);
        delay = Math.max(0, delay - 1);
        if (!send) delay = 0;
        else if (grounded && delay == 0) delay = 10;
        return send;
    }
}

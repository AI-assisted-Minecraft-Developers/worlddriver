package net.magicterra.worlddriver.bot.control.controller;

import net.minecraft.core.BlockPos;

public final class StickyDig {
    public BlockPos pos;         // planned-break cell being mined
    public int ticks;            // watchdog for pos
    // PROGRESS-AWARE release (2026-07-21, Mountains bank live + 23 RELEASE
    // loops): a fixed tick cap cannot work because vanilla stacks the x5 (eye in
    // water) and x5 (airborne) dig penalties MULTIPLICATIVELY — a swimAshoreClimb
    // bank dig needs 450t (dirt) to 3750t (stone, wrong tool). A 150t cap released
    // every such dig with the block still solid, reset progress to zero, and the
    // navigator re-acquired the same cell forever. Tracking the actual destroy
    // progress holds while it climbs and releases only on a true stall.
    public float lastProgress;   // last observed vanilla destroyProgress (0..1)
    public int stallTicks;       // consecutive ticks with no progress increase
    // The drive is on the exact cell and vanilla's crosshair-driven attack pass stands
    // aside for it (ClientIntents), so progress is decoupled from where the eye raycast
    // lands — the bob-reset latch that used to live here is gone with the attack key.
    /**
     * Claim the dig slot for {@code b}. <b>A claim, not a setter.</b>
     *
     * <p>Two navigator phases call this every tick — the travel drive with the cell in its way,
     * the water climb-out with its bank riser — and until 2026-08-22 whoever called LAST won.
     * That is not a preference, it is a data loss: vanilla's {@code MultiPlayerGameMode} can
     * track exactly ONE destroy target, so a switch runs {@code startDestroyBlock} and throws
     * the accumulated {@code destroyProgress} away.
     *
     * <p>Measured on the real-client ladder, rung 3: the target alternated between y=62 and
     * y=64 every 20–40 ticks while ONE bare-handed afloat block needs 300 (the ×5 in-water and
     * ×5 airborne penalties multiply). Progress peaked at <b>0.81</b> and the rung timed out
     * after 8000 ticks having broken nothing — with the dig hold never once expiring, because
     * the hold was never the thing that was wrong.
     *
     * <p>So an incumbent that has real progress keeps the slot. Releasing is the prelude's job
     * and only the prelude's: it frees the claim when the cell stops being solid, drifts out of
     * reach, or truly stalls. A claim that could also be revoked here would put the release
     * policy in two places, which is how the fixed-clock release outlived the lesson that
     * killed it.
     */
    public void engage(BlockPos b) {
        // A LIVE CLAIM HOLDS, whatever its progress currently reads. Two earlier shapes failed:
        // last-caller-wins (no guard at all), and hold-while-lastProgress>0 — the latter written
        // and refuted on the same night (2026-08-22, real-client ladder rung 3). It asked the
        // incumbent to prove itself with the one quantity the challenger had just zeroed, and
        // worse: re-engaging the cell ALREADY held fell through to the reset below and wiped the
        // ticks/stall counters the prelude's release reads. That is why `dig-aim RELEASE` did not
        // fire once in 8022 ticks while the target alternated between y=62 and y=64 forever.
        // The claim is structural now — only the prelude, which owns the release policy, hands
        // the slot over. Re-engaging the incumbent cell is a no-op, not a refresh.
        if (b != null && pos != null) return;
        pos = b;
        ticks = 0;
        lastProgress = 0f;
        stallTicks = 0;
    }

    /** Revoke unconditionally, for a preempting SAFETY dig (suffocation) — that is not a
     *  navigation preference and must not queue behind one. The expiry policy still lives only
     *  in the prelude; this is a named override with a caller, not a second clock. */
    public void revoke() { pos = null; ticks = 0; lastProgress = 0f; stallTicks = 0; }
}

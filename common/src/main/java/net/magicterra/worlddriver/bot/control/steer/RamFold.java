package net.magicterra.worlddriver.bot.control.steer;

public final class RamFold {
    public int stepRamStuckTicks;       // GROUNDED ticks ramming an above-node riser (bob-immune; dry OR shallow water) → STEPUP_FREEZE_TICKS engages stepUpFreeze
    public int ascentRamBobTicks;       // foot-below-node + lateral-close ticks IGNORING onGround (bob-resettable; steep-bank +1 mount) → ORs into stepUpFreeze (gated walkerAscentRamBobBreak)
    public int floatingBankBobTicks;    // FLOATING +1 water-bank: !onGround + foot-below-node + lateral-ram, IGNORING the in/out-water bob → ORs into stepUpFreeze (gated walkerFloatingBankBobFreeze)
    public int bankFollowRamTicks;      // FLOATING water-bank lateral ram (ANY node-Y) → drives a slide ALONG the bank toward a mountable exit (gated walkerFloatingBankFollow)
}

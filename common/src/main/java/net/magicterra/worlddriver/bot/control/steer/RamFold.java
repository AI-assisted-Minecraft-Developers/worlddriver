package net.magicterra.worlddriver.bot.control.steer;

/** Counts the grounded ticks the bot spends ramming a riser above its node. */
public final class RamFold {
    public int stepRamStuckTicks; // GROUNDED ticks ramming an above-node riser (bob-immune; dry OR shallow water) →

    // STEPUP_FREEZE_TICKS engages stepUpFreeze
    public int
            ascentRamBobTicks; // foot-below-node + lateral-close ticks IGNORING onGround (bob-resettable; steep-bank +1

    // mount) → ORs into stepUpFreeze (gated navigatorAscentRamBobBreak)
    public int floatingBankBobTicks; // FLOATING +1 water-bank: !onGround + foot-below-node + lateral-ram, IGNORING the
    // in/out-water bob → ORs into stepUpFreeze (gated navigatorFloatingBankBobFreeze)
}

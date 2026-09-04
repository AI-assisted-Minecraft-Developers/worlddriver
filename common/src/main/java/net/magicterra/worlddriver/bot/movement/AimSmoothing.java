package net.magicterra.worlddriver.bot.movement;

/**
 * Aim/heading smoothing state, owned by {@link WalkerTickAim} and held on {@link Walker#aimSmooth}
 * ({@code smoothWaterDriveYaw} is also read by {@link WalkerTickDrive}; {@code lastAimYaw} by
 * {@link WalkerTickClimb}'s dig telemetry). {@link #reset()} resyncs the EMA chain + the yaw-thrash
 * detector — exactly the four fields both journey resets cleared; the remaining fields self-manage
 * (NaN resync / decay / streak logic).
 */
final class AimSmoothing {
    float smoothTargetYaw = Float.NaN;          // EMA-low-passed target heading (NaN = uninitialised; resync on launch/new goal)
    float smoothWaterDriveYaw = Float.NaN;      // EMA-low-passed water DRIVE heading (separate from the camera trend)
    float lastCarrotBearing = Float.NaN;        // previous tick's raw carrot bearing — feeds the in-water yaw-thrash detector
    int lastCarrotBearingSign = 0;              // sign of the last meaningful carrot-bearing turn (for reversal detection)
    int yawThrashTicks = 0;                     // decaying score: +4 per carrot-bearing reversal in water (cap 12), −1/tick → steady turn winds to 0, oscillation holds high
    float lastAimYaw = Float.NaN;               // previous tick's smoothed aim heading — feeds the anti-spin target-stability gate (a flipping target winds; a stable one converges)
    int aimStableTicks = 0;                     // consecutive ticks the smoothed aim target barely moved; once past AIM_STABLE_TICKS the anti-spin freeze releases (a stable target can't wind the camera)
    float rawLastTargetYaw = Float.NaN;         // previous tick's RAW (pre-EMA) target heading — the antipode-proof stability signal (see WalkerTickAim reversal fix)
    int rawStableTicks = 0;                     // consecutive ticks the RAW target barely moved; releases the anti-spin freeze even when the EMA oscillates at the ±180° antipode
    double freezeAnchorX, freezeAnchorZ;       // body pos when the frozen-press stall window opened (spinFreeze deadlock valve)
    int frozenStallTicks = 0;                   // consecutive frozen ticks with <FREEZE_PRESS_MOVE displacement — valve trips past FREEZE_PRESS_STALL_TICKS
    int reversalStreak = 0;                     // consecutive ticks the EMA target sat >AIM_REVERSAL_DEG from the smooth value — snap after AIM_REVERSAL_SNAP_TICKS (a true course reversal cannot be EMA-chased)
    int waterDriveRejectStreak = 0;             // consecutive flip-rejections of the water drive heading (escape-hatch snaps after WATER_DRIVE_MAX_REJECT)

    void reset() {
        smoothTargetYaw = Float.NaN;
        lastCarrotBearing = Float.NaN;
        lastCarrotBearingSign = 0;
        yawThrashTicks = 0;
    }
}

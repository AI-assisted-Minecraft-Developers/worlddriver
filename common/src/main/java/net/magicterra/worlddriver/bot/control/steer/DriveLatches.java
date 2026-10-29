package net.magicterra.worlddriver.bot.control.steer;

public final class DriveLatches {
    public int deepWaterDriftLatch = 0;                // ticks the deep-water drift sprint-brake stays latched after firing while grounded, so sprint stays OFF through the airborne sub-arcs of a step-down descent toward a deep pocket (otherwise sprint re-arms each airborne tick and the accumulated forward momentum still overshoots into the water)
    public int steepDescentLatch = 0;                  // DRY sibling of deepWaterDriftLatch: ticks the steep-descent sprint-brake stays latched across the airborne sub-arcs of a step-down, so sprint can't re-arm mid-fall and accumulate forward momentum off a survivable-deep lip (live 2026-07-11 Mountains massif: onG=false→sprint=true walked the bot off a 19-block lip to death)
    public int climbPressConsec = 0;                   // consecutive ticks the buoyant-climb-press raw condition has held (debounces the surface-bob false trigger)
    public int descentDriveRejectStreak = 0;           // consecutive back-hop rejections on a dry diagDown slope (escape-hatch snaps to the real node after WATER_DRIVE_MAX_REJECT)
    public int underwaterTicks;                        // consecutive eyes-under ticks → debounces the swim-up jump (surface bob ≠ sinking)
    public boolean cruiseOn;                           // surface cruise engaged last tick (entry needs the eyes out; the dip then takes them under on purpose)
    public boolean cruiseBreath;                       // surface cruise: air ran low → bob and breathe until it refills (hysteresis, see DrivePhase.surfaceCruise)
    public int cruiseSwimTicks;                        // surface cruise: consecutive ticks in the swim pose (0 = not swimming; log edge + the server-confirm hold)
    public int cruiseDipTicks;                         // surface cruise: ticks spent sinking for the pose without getting it → past CRUISE_DIP_MAX_TICKS the cruise backs off
    public int cruiseCooldown;                         // surface cruise: ticks left in that back-off
    public boolean lavaBrakeLogged;                    // edge-trigger for the hazard-ahead brake's log line: that brake can hold for every tick of a legitimate lava-side passage, so it prints once per engagement rather than once per tick. Not cleared by reset() — it is a log latch, not a drive latch, and a repath mid-passage should not re-announce the same creep
    public void reset() {
        deepWaterDriftLatch = 0;
        steepDescentLatch = 0;
    }
}

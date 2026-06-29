"""water_yaw_thrash:水中 body-yaw 摆动指标(2026-06-29)。"""
from scripts.pmcs.telemetry import water_yaw_thrash, _yaw_delta, WalkerTick


def _tk(t, yaw, inw):
    return WalkerTick(t=t, step=1, path_len=9, move="walk", x=0, y=0, z=0,
                      within=False, on_ground=True, in_water=inw, stuck=0, tot_stuck=0, yaw=yaw)


def test_yaw_delta_wraps():
    assert abs(_yaw_delta(179, -179) - 2.0) < 1e-6   # not 358
    assert abs(_yaw_delta(-179, 179) + 2.0) < 1e-6


def test_steady_swim_low_thrash():
    ticks = [_tk(i, 90.0, True) for i in range(10)]
    mean, n = water_yaw_thrash(ticks)
    assert n == 9 and mean == 0.0


def test_thrashing_swim_high():
    ticks = [_tk(i, 90.0 if i % 2 == 0 else 150.0, True) for i in range(10)]
    mean, n = water_yaw_thrash(ticks)
    assert n == 9 and mean == 60.0   # ±60 every tick


def test_dry_ticks_excluded():
    ticks = [_tk(i, 90.0 if i % 2 == 0 else 150.0, False) for i in range(10)]
    _, n = water_yaw_thrash(ticks)
    assert n == 0


def test_break_on_gap():
    # a non-consecutive t (search gap) breaks the pair chain
    ticks = [_tk(0, 0, True), _tk(1, 30, True), _tk(5, 200, True)]
    mean, n = water_yaw_thrash(ticks)
    assert n == 1 and mean == 30.0

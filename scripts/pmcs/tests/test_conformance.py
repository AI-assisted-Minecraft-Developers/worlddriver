from scripts.pmcs.telemetry import WalkerTick
from scripts.pmcs.conformance import conformance_table


def tick(step, move, tot):
    return WalkerTick(t=0, step=step, path_len=20, move=move, x=0, y=0, z=0,
                      within=False, on_ground=True, in_water=False, stuck=0, tot_stuck=tot)


def test_clean_move_not_churned():
    ticks = [tick(0, "walk", 5), tick(0, "walk", 10), tick(1, "stepUp", 30)]
    tbl = conformance_table(ticks, silky=120)
    assert tbl["walk"].executions == 1 and tbl["walk"].churned == 0
    assert tbl["stepUp"].executions == 1 and tbl["stepUp"].churned == 0
    assert tbl["walk"].worst_tot_stuck == 10


def test_churned_move_flagged():
    ticks = [tick(2, "stepUp2", 50), tick(2, "stepUp2", 300), tick(3, "walk", 8)]
    tbl = conformance_table(ticks, silky=120)
    assert tbl["stepUp2"].executions == 1 and tbl["stepUp2"].churned == 1
    assert tbl["stepUp2"].worst_tot_stuck == 300


def test_same_move_type_multiple_executions():
    ticks = [tick(0, "swimAshoreBreak", 20), tick(1, "walk", 5),
             tick(2, "swimAshoreBreak", 400)]
    tbl = conformance_table(ticks, silky=120)
    assert tbl["swimAshoreBreak"].executions == 2
    assert tbl["swimAshoreBreak"].churned == 1
    assert tbl["swimAshoreBreak"].worst_tot_stuck == 400

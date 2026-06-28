from scripts.pmcs.telemetry import parse_walker_line, parse_log, peak_totstuck

# 真实 [walker] telemetry 行格式(Walker.java:2388)
LINE = ("[12:00:01] [Render thread/INFO] (AgentDriver) [walker] t=5 step=3/12 move=stepUp "
        "node=-815,64,196 p=(-815.30,63.00,196.10) pitch=0 cur2=0.120 (gate 0.45) "
        "|dY|=1.00 (gate 1.2) within=false onG=true inW=false undW=false "
        "stuck=5 totStuck=42 pend=false break0=null")


def test_parse_single_line():
    tk = parse_walker_line(LINE)
    assert tk is not None
    assert tk.t == 5
    assert tk.step == 3 and tk.path_len == 12
    assert tk.move == "stepUp"
    assert abs(tk.x - (-815.30)) < 1e-6 and abs(tk.z - 196.10) < 1e-6
    assert tk.within is False and tk.on_ground is True and tk.in_water is False
    assert tk.stuck == 5 and tk.tot_stuck == 42


def test_non_walker_line_is_none():
    assert parse_walker_line("[12:00:01] [Render thread/INFO] something else") is None


def test_parse_log_and_peak():
    text = "\n".join([
        LINE.replace("totStuck=42", "totStuck=42"),
        LINE.replace("totStuck=42", "totStuck=137"),
        "noise line",
        LINE.replace("totStuck=42", "totStuck=88"),
    ])
    ticks = parse_log(text)
    assert len(ticks) == 3
    assert peak_totstuck(ticks) == 137


def test_peak_empty():
    assert peak_totstuck([]) == 0

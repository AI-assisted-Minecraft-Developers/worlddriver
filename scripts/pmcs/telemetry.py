"""Parse the Walker's [walker] telemetry lines.

The emitter is `LOG.info("[walker] t={} step=...")` in WalkerTickClimb.java (the Walker is
split into WalkerTick* phase classes, see docs/dev/movement-tick-phases.md). No line number is
pinned here: line numbers drift, class names do not.

The regexes below and the emitter's format string form a contract with **no compile-time
check**. `scripts/check_log_contract.py` is the only thing that ties the two ends together: it
feeds real run logs to these regexes and fails if any line cannot be parsed. Without it, a
renamed field still compiles and still passes every scene, but nothing here matches any more —
and a failed regex match does not raise, it returns an empty iterator, so the tool quietly
reports "no ticks", which looks exactly like a bot that never moved.
"""
import re
from dataclasses import dataclass

_RE = re.compile(
    r"\[walker\] t=(?P<t>\d+) step=(?P<step>\d+)/(?P<plen>\d+) move=(?P<move>\S+) "
    r"node=-?\d+,-?\d+,-?\d+ p=\((?P<x>-?[0-9.]+),(?P<y>-?[0-9.]+),(?P<z>-?[0-9.]+)\) "
    r".*?within=(?P<within>\w+) onG=(?P<onG>\w+) inW=(?P<inW>\w+) undW=\w+ "
    r"stuck=(?P<stuck>\d+) totStuck=(?P<tot>\d+)"
)
# yaw is logged AFTER p=(...) since 2026-06-29 (telemetry enhancement). Optional so old logs still parse.
_RE_YAW = re.compile(r"\byaw=(?P<yaw>-?[0-9.]+)")


@dataclass
class WalkerTick:
    t: int
    step: int
    path_len: int
    move: str
    x: float
    y: float
    z: float
    within: bool
    on_ground: bool
    in_water: bool
    stuck: int
    tot_stuck: int
    yaw: float = None  # None on pre-2026-06-29 logs without the yaw field


def parse_walker_line(line: str):
    m = _RE.search(line)
    if not m:
        return None
    my = _RE_YAW.search(line)
    return WalkerTick(
        t=int(m["t"]), step=int(m["step"]), path_len=int(m["plen"]), move=m["move"],
        x=float(m["x"]), y=float(m["y"]), z=float(m["z"]),
        within=m["within"] == "true", on_ground=m["onG"] == "true", in_water=m["inW"] == "true",
        stuck=int(m["stuck"]), tot_stuck=int(m["tot"]),
        yaw=float(my["yaw"]) if my else None,
    )


def _yaw_delta(a, b):
    """Smallest signed angle a→b in degrees (wraps ±180 so 179→-179 is 2°, not 358°)."""
    d = (b - a + 180.0) % 360.0 - 180.0
    return d


def water_yaw_thrash(ticks):
    """Mean |Δyaw| per tick across consecutive IN-WATER ticks = the signature of repeated side-to-side swerving in water that
    totStuck is BLIND to (in-water progress is horizontal-only/bob-immune, so a thrashing-yaw swim
    that drifts forward keeps totStuck low while the camera/body swings wildly — the dominant water
    jank on the 2026-06-29 journey, REGRESSION.md §14). Consecutive = adjacent ticks both inW with
    yaw present and t increasing by 1 (same uninterrupted swim). Returns (mean_abs_dyaw, samples)."""
    total, n = 0.0, 0
    prev = None
    for tk in ticks:
        if tk.in_water and tk.yaw is not None:
            if prev is not None and tk.t == prev.t + 1:
                total += abs(_yaw_delta(prev.yaw, tk.yaw))
                n += 1
            prev = tk
        else:
            prev = None
    return (total / n if n else 0.0, n)


def parse_log(text: str):
    out = []
    for line in text.splitlines():
        tk = parse_walker_line(line)
        if tk is not None:
            out.append(tk)
    return out


def peak_totstuck(ticks):
    return max((tk.tot_stuck for tk in ticks), default=0)

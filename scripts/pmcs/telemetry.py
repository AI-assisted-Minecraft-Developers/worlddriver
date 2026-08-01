"""解析 Walker 的 [walker] telemetry 行。

发射点是 WalkerTickClimb.java 里的 `LOG.info("[walker] t={} step=...")`
——不再是 Walker.java(该类已按 docs/walker-tick-architecture.md 拆成 WalkerTick* 相
位类),所以这里不再钉行号:行号会漂,类名不会。

下面的正则与发射端的格式字符串是一份**没有编译期约束**的契约。
`scripts/check_log_contract.py` 是唯一把两端拴在一起的东西:它拿真跑出来的日志
喂这些正则,任何一条解析不了就红。没有它,改个字段名照样编译、照样过全部场景,
只是这里从此匹配不到任何行——而正则匹配失败不抛异常,只是返回空迭代器,工具会
安静地报告"没有 tick",看起来就像机器人根本没动过。
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
    """Mean |Δyaw| per tick across consecutive IN-WATER ticks = the "水中反复横跳" signature that
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

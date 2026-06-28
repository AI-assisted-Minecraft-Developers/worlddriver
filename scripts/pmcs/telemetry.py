"""解析 Walker 的 [walker] telemetry 行(Walker.java:2388 格式)。"""
import re
from dataclasses import dataclass

_RE = re.compile(
    r"\[walker\] t=(?P<t>\d+) step=(?P<step>\d+)/(?P<plen>\d+) move=(?P<move>\S+) "
    r"node=-?\d+,-?\d+,-?\d+ p=\((?P<x>-?[0-9.]+),(?P<y>-?[0-9.]+),(?P<z>-?[0-9.]+)\) "
    r".*?within=(?P<within>\w+) onG=(?P<onG>\w+) inW=(?P<inW>\w+) undW=\w+ "
    r"stuck=(?P<stuck>\d+) totStuck=(?P<tot>\d+)"
)


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


def parse_walker_line(line: str):
    m = _RE.search(line)
    if not m:
        return None
    return WalkerTick(
        t=int(m["t"]), step=int(m["step"]), path_len=int(m["plen"]), move=m["move"],
        x=float(m["x"]), y=float(m["y"]), z=float(m["z"]),
        within=m["within"] == "true", on_ground=m["onG"] == "true", in_water=m["inW"] == "true",
        stuck=int(m["stuck"]), tot_stuck=int(m["tot"]),
    )


def parse_log(text: str):
    out = []
    for line in text.splitlines():
        tk = parse_walker_line(line)
        if tk is not None:
            out.append(tk)
    return out


def peak_totstuck(ticks):
    return max((tk.tot_stuck for tk in ticks), default=0)

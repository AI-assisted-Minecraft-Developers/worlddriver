"""驱动一个 (archive, flags) replay 并收集 maxStuck + per-Move conformance。

live 链路(spec §7 工作流第1步):
  1) 经 WS-RPC 设 flags(新 flag 会被 MCP 工具 schema strip,必须走 RPC——见 worlddriver-rpc skill)
  2) mc.debug.replay {file, restoreBlocks:true}
  3) 轮询 fabric/run/logs/latest.log 的 [walker] 行,到达 arrive_x 或超时即停
  4) 解析这段日志切片 → maxStuck + conformance
RPC 调用是 websockets + JSON-RPC 直连 port 39801(同 worlddriver-rpc skill 的 rpc.py)。
"""
import asyncio
import os
import shutil
import time
from dataclasses import dataclass

from scripts.pmcs.telemetry import parse_log, peak_totstuck, parse_walker_line
from scripts.pmcs.conformance import conformance_table

LOG = "fabric/run/logs/latest.log"
RPC_URL = "ws://127.0.0.1:39801/rpc"
# ReplayTool 从 RUNTIME config 目录读归档(cwd=fabric/run);repo 的 corpus 必须先 copy 过去。
REPO_REPLAY_DIR = "config/worlddriver/replays"
RUNTIME_REPLAY_DIR = "fabric/run/config/worlddriver/replays"


def _ensure_archive_in_runtime(archive: str):
    src = os.path.join(REPO_REPLAY_DIR, archive)
    dst = os.path.join(RUNTIME_REPLAY_DIR, archive)
    if os.path.exists(src) and not os.path.exists(dst):
        os.makedirs(RUNTIME_REPLAY_DIR, exist_ok=True)
        shutil.copy(src, dst)


def log_slice_since(text: str, marker_line: int) -> str:
    lines = text.splitlines(keepends=True)
    return "".join(lines[marker_line:])


def _read_log() -> str:
    with open(LOG, errors="ignore") as f:
        return f.read()


async def _rpc(method: str, params: dict):
    import json
    import websockets
    async with websockets.connect(RPC_URL, max_size=16 * 1024 * 1024, ping_interval=None) as ws:
        await ws.send(json.dumps({"id": 1, "method": method, "params": params}))
        while True:
            msg = json.loads(await ws.recv())
            if msg.get("id") == 1:
                return msg.get("result")


def _arrived(v: float, arrive_x: int, cmp: str) -> bool:
    return (v >= arrive_x) if cmp == "ge" else (v <= arrive_x)


@dataclass
class CaseResult:
    archive: str
    max_stuck: int
    arrived: bool
    conformance: dict


def run_case(archive: str, flags: dict, arrive_x: int, cmp: str, timeout: int = 220, axis: str = "x") -> CaseResult:
    _ensure_archive_in_runtime(archive)
    base = len(_read_log().splitlines())
    # walkerDebug 是 telemetry 观测开关,必须 ON 才能测 maxStuck/conformance——独立于被测候选 flags。
    asyncio.run(_rpc("mc.bot.setting", {"walkerDebug": True, **flags}))
    asyncio.run(_rpc("mc.debug.replay", {"file": archive, "restoreBlocks": True}))
    arrived = False
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(6)
        sl = log_slice_since(_read_log(), base)
        last = None
        for ln in sl.splitlines():
            tk = parse_walker_line(ln)
            if tk:
                last = tk
        if last and _arrived(last.x if axis == "x" else last.z, arrive_x, cmp):
            arrived = True
            break
    sl = log_slice_since(_read_log(), base)
    ticks = parse_log(sl)
    return CaseResult(archive, peak_totstuck(ticks), arrived, conformance_table(ticks))

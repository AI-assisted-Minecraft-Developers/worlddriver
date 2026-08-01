# Pathfinding Conformance Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建一套机制驱动的"发现→修复"闭环工具:把 planner/executor 两个世界模型的发散**自动发现**(per-Move conformance + journey corpus),并用**全集净正零回归接受门**守着修复。

**Architecture:** 三层 Python 包 `scripts/pmcs/`:(1) 纯逻辑核心——telemetry 解析 + per-Move conformance 聚合 + 接受门评估器,全 TDD 单测;(2) live 驱动层——经既有 WS-RPC(`journey_runner.py` 的 call 模式)设 flag + 触发 `mc.debug.replay`,收集 maxStuck/conformance;(3) corpus + 活文档 + runbook。本计划交付**机制本身**(可独立测试的软件);用机制跑真实修复是后续(见末尾 Scope)。

**Tech Stack:** Python 3(仓库根 `.venv`)、pytest、bash glue、既有 `mc.debug.replay`/`mc.bot.setting` MCP 路由、`scripts/journey_runner.py`(WS-RPC)、`scripts/wjourney.py`(telemetry 正则)。新 flag 经 RPC/script_eval 设(MCP schema 冻结会 strip 新 key)。

## Global Constraints

- **纯逻辑全 TDD**:telemetry 解析、conformance 聚合、接受门评估器必须有单测,fixture 用**真实** telemetry 行格式与**真实** lever 矩阵。
- **接受门判据**(spec §3):候选 flag 组合跨全 corpus,聚合 maxStuck 下降 **AND** 任一单归档不超容差回归(`TOL=0.10`)**AND** 无归档从 silky 跨到非 silky(`SILKY=120` ticks ≈ 6s)。
- **maxStuck** = `[walker]` telemetry 行 `totStuck=` 的峰值(ticks;/20 ≈ 秒)。
- **场景真实性**(spec §9 风险3):任何 replay 必须 `restoreBlocks:true` 忠实恢复;严禁手搭 arena 当判据。
- **新 flag 五处接线**(BotConfig 字段 + SettingsCommand setter + snapshot + BotTools `.prop` + Walker gate),且设值经 RPC(非 MCP 工具,schema 已冻结)。
- **default-OFF**:任何寻路修复都 default-OFF,只有过接受门才 flip default-ON——本计划**不含**具体寻路修复,只建发现/判定机制。
- Python 包路径前缀一律 `scripts/pmcs/`;测试在 `scripts/pmcs/tests/`,以 `python3 -m pytest` 跑。

---

### Task 1: pmcs 包骨架 + telemetry 行解析器

**Files:**
- Create: `scripts/pmcs/__init__.py`
- Create: `scripts/pmcs/telemetry.py`
- Test: `scripts/pmcs/tests/__init__.py`, `scripts/pmcs/tests/test_telemetry.py`

**Interfaces:**
- Produces:
  - `@dataclass WalkerTick(t:int, step:int, path_len:int, move:str, x:float, y:float, z:float, within:bool, on_ground:bool, in_water:bool, stuck:int, tot_stuck:int)`
  - `parse_walker_line(line:str) -> WalkerTick | None` — 非 `[walker] t=` 行返回 None
  - `parse_log(text:str) -> list[WalkerTick]`
  - `peak_totstuck(ticks:list[WalkerTick]) -> int` — 空列表返回 0

- [ ] **Step 1: 建测试 venv 依赖**

Run:
```bash
cd /home/coder/AI-assisted-Minecraft-Developers/worlddriver
.venv/bin/python3 -m pip install -q pytest
mkdir -p scripts/pmcs/tests
touch scripts/pmcs/__init__.py scripts/pmcs/tests/__init__.py
```
Expected: pytest 安装成功(或已存在)。

- [ ] **Step 2: 写失败测试** — `scripts/pmcs/tests/test_telemetry.py`

```python
from scripts.pmcs.telemetry import parse_walker_line, parse_log, peak_totstuck

# 真实 [walker] telemetry 行格式(Walker.java:2388)
LINE = ("[12:00:01] [Render thread/INFO] (WorldDriver) [walker] t=5 step=3/12 move=stepUp "
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
```

- [ ] **Step 3: 跑测试确认失败**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_telemetry.py -q`
Expected: FAIL — `ModuleNotFoundError: scripts.pmcs.telemetry`

- [ ] **Step 4: 实现** — `scripts/pmcs/telemetry.py`

```python
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
```

- [ ] **Step 5: 跑测试确认通过**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_telemetry.py -q`
Expected: PASS(4 passed)

- [ ] **Step 6: Commit**

```bash
git add scripts/pmcs/__init__.py scripts/pmcs/telemetry.py scripts/pmcs/tests/
git commit -m "feat(pmcs): walker telemetry line parser + peak totStuck (TDD)"
```

---

### Task 2: per-Move conformance 聚合

**Files:**
- Create: `scripts/pmcs/conformance.py`
- Test: `scripts/pmcs/tests/test_conformance.py`

**Interfaces:**
- Consumes: `WalkerTick`(Task 1)
- Produces:
  - `@dataclass MoveStat(move:str, executions:int, churned:int, worst_tot_stuck:int)`
  - `conformance_table(ticks:list[WalkerTick], silky:int=120) -> dict[str, MoveStat]`
  - 定义:一次"execution" = telemetry 流里 `step` 值的一段连续占用;该 step 期间 `tot_stuck` 峰值 ≥ `silky` 即 `churned`。Move 类型取该 step 段内的 `move`。

- [ ] **Step 1: 写失败测试** — `scripts/pmcs/tests/test_conformance.py`

```python
from scripts.pmcs.telemetry import WalkerTick
from scripts.pmcs.conformance import conformance_table


def tick(step, move, tot):
    return WalkerTick(t=0, step=step, path_len=20, move=move, x=0, y=0, z=0,
                      within=False, on_ground=True, in_water=False, stuck=0, tot_stuck=tot)


def test_clean_move_not_churned():
    # step 0 walk 干净(峰值 10),step 1 stepUp 干净(峰值 30)
    ticks = [tick(0, "walk", 5), tick(0, "walk", 10), tick(1, "stepUp", 30)]
    tbl = conformance_table(ticks, silky=120)
    assert tbl["walk"].executions == 1 and tbl["walk"].churned == 0
    assert tbl["stepUp"].executions == 1 and tbl["stepUp"].churned == 0
    assert tbl["walk"].worst_tot_stuck == 10


def test_churned_move_flagged():
    # step 2 stepUp2 churn(峰值 300 ≥ silky)
    ticks = [tick(2, "stepUp2", 50), tick(2, "stepUp2", 300), tick(3, "walk", 8)]
    tbl = conformance_table(ticks, silky=120)
    assert tbl["stepUp2"].executions == 1 and tbl["stepUp2"].churned == 1
    assert tbl["stepUp2"].worst_tot_stuck == 300


def test_same_move_type_multiple_executions():
    # 同一 Move 类型在不同 step 段出现两次:一次干净一次 churn
    ticks = [tick(0, "swimAshoreBreak", 20), tick(1, "walk", 5),
             tick(2, "swimAshoreBreak", 400)]
    tbl = conformance_table(ticks, silky=120)
    assert tbl["swimAshoreBreak"].executions == 2
    assert tbl["swimAshoreBreak"].churned == 1
    assert tbl["swimAshoreBreak"].worst_tot_stuck == 400
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_conformance.py -q`
Expected: FAIL — `ModuleNotFoundError: scripts.pmcs.conformance`

- [ ] **Step 3: 实现** — `scripts/pmcs/conformance.py`

```python
"""把 telemetry tick 流聚合成 per-Move conformance 表。

一次 Move "execution" = telemetry 里 step 指针的一段连续占用(step 变化 = 进入下一个 Move)。
该段内 totStuck 峰值 >= silky 即判 churned(executor 在这个 Move 上卡了)。
跨 corpus 聚合后,churned>0 的 Move = planner 谓词比 executor 真实能力宽松的发散点。
"""
from dataclasses import dataclass


@dataclass
class MoveStat:
    move: str
    executions: int
    churned: int
    worst_tot_stuck: int


def conformance_table(ticks, silky: int = 120):
    table: dict[str, MoveStat] = {}
    # 按连续 step 段切分
    seg_move = None
    seg_peak = 0
    prev_step = None

    def flush():
        nonlocal seg_move, seg_peak
        if seg_move is None:
            return
        st = table.get(seg_move) or MoveStat(seg_move, 0, 0, 0)
        st.executions += 1
        if seg_peak >= silky:
            st.churned += 1
        st.worst_tot_stuck = max(st.worst_tot_stuck, seg_peak)
        table[seg_move] = st
        seg_move = None
        seg_peak = 0

    for tk in ticks:
        if prev_step is None or tk.step != prev_step:
            flush()
            seg_move = tk.move
            seg_peak = tk.tot_stuck
        else:
            seg_peak = max(seg_peak, tk.tot_stuck)
        prev_step = tk.step
    flush()
    return table
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_conformance.py -q`
Expected: PASS(3 passed)

- [ ] **Step 5: Commit**

```bash
git add scripts/pmcs/conformance.py scripts/pmcs/tests/test_conformance.py
git commit -m "feat(pmcs): per-Move conformance aggregation from telemetry (TDD)"
```

---

### Task 3: 接受门评估器(机制核心)

**Files:**
- Create: `scripts/pmcs/gate.py`
- Test: `scripts/pmcs/tests/test_gate.py`

**Interfaces:**
- Produces:
  - `@dataclass GateVerdict(accepted:bool, net_positive:bool, regressions:list[str], crossed_silky:list[str], baseline_sum:int, candidate_sum:int)`
  - `evaluate_gate(baseline:dict[str,int], candidate:dict[str,int], tol:float=0.10, silky:int=120) -> GateVerdict`
  - 规则(Global Constraints):`accepted = net_positive AND not regressions AND not crossed_silky`;`net_positive = sum(candidate) < sum(baseline)`;`regressions = {a: candidate[a] > baseline[a]*(1+tol)}`;`crossed_silky = {a: baseline[a] < silky <= candidate[a]}`。

- [ ] **Step 1: 写失败测试** — `scripts/pmcs/tests/test_gate.py`(用**真实** lever 矩阵)

```python
from scripts.pmcs.gate import evaluate_gate

# 真实 baseline(all-OFF)与候选(apw-stack),来自 REGRESSION.md lever 矩阵
OFF = {"replay-0004": 579, "replay-0005": 840, "replay-0006": 1814}
APW = {"replay-0004": 1935, "replay-0005": 649, "replay-0006": 829}


def test_apw_rejected_due_to_0004_regression():
    # apw 在 0005/0006 大胜但 0004 灾难回归(579→1935)→ 必须 REJECT
    v = evaluate_gate(OFF, APW)
    assert v.accepted is False
    assert "replay-0004" in v.regressions


def test_genuine_improvement_accepted():
    cand = {"replay-0004": 560, "replay-0005": 700, "replay-0006": 900}
    v = evaluate_gate(OFF, cand)
    assert v.accepted is True
    assert v.net_positive is True
    assert v.regressions == [] and v.crossed_silky == []


def test_net_negative_rejected_even_without_regression():
    # 每归档都在容差内但聚合变差 → REJECT(非净正)
    base = {"a": 100, "b": 100}
    cand = {"a": 109, "b": 109}  # 各 +9%(<10% 容差,不算回归)但聚合 200→218
    v = evaluate_gate(base, cand)
    assert v.net_positive is False and v.accepted is False


def test_crossing_silky_threshold_rejected():
    # 某归档本来 silky(<120)变成非 silky → REJECT,即便聚合下降
    base = {"a": 100, "b": 1000}
    cand = {"a": 130, "b": 400}   # 聚合 1100→530 净正,但 a 从 100(silky)跨到 130
    v = evaluate_gate(base, cand)
    assert "a" in v.crossed_silky and v.accepted is False
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_gate.py -q`
Expected: FAIL — `ModuleNotFoundError: scripts.pmcs.gate`

- [ ] **Step 3: 实现** — `scripts/pmcs/gate.py`

```python
"""接受门:候选 flag 组合是否够格 flip default-ON。

spec §3 的"全集净正 + 零回归"门——直接对治本项目的 over-fit 教训(apw 在 0005/0006 调优
却在 0004 上 579→1935 灾难回归)。门只和 corpus 一样好(spec §9 风险2)。
"""
from dataclasses import dataclass


@dataclass
class GateVerdict:
    accepted: bool
    net_positive: bool
    regressions: list
    crossed_silky: list
    baseline_sum: int
    candidate_sum: int


def evaluate_gate(baseline: dict, candidate: dict, tol: float = 0.10, silky: int = 120):
    archives = sorted(baseline)
    regressions = [a for a in archives if candidate[a] > baseline[a] * (1 + tol)]
    crossed_silky = [a for a in archives if baseline[a] < silky <= candidate[a]]
    base_sum = sum(baseline[a] for a in archives)
    cand_sum = sum(candidate[a] for a in archives)
    net_positive = cand_sum < base_sum
    accepted = net_positive and not regressions and not crossed_silky
    return GateVerdict(accepted, net_positive, regressions, crossed_silky, base_sum, cand_sum)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_gate.py -q`
Expected: PASS(4 passed)

- [ ] **Step 5: Commit**

```bash
git add scripts/pmcs/gate.py scripts/pmcs/tests/test_gate.py
git commit -m "feat(pmcs): acceptance gate evaluator (net-positive + zero-regression, TDD on real lever matrix)"
```

---

### Task 4: corpus 清单 + 加载器

**Files:**
- Create: `config/worlddriver/replays/corpus.json`
- Create: `scripts/pmcs/corpus.py`
- Test: `scripts/pmcs/tests/test_corpus.py`

**Interfaces:**
- Produces:
  - `@dataclass CorpusEntry(archive:str, arrive_x:int, cmp:str, failure_class:str, region:str)`
  - `load_corpus(path:str) -> list[CorpusEntry]`
  - `cmp` ∈ {"ge","le"}(`replay_regression_track.sh` 的到达判据约定)

- [ ] **Step 1: 写 corpus 清单** — `config/worlddriver/replays/corpus.json`

```json
{
  "comment": "journey replay-corpus manifest。每条 = 一个忠实可复现的真实失败旅途。门只和 corpus 多样性一样好(spec §9 风险2)——覆盖各故障类型。arrive_x/cmp 喂 replay_regression_track.sh。",
  "silky_ticks": 120,
  "tolerance": 0.10,
  "entries": [
    {"archive": "replay-0004.json", "arrive_x": -520, "cmp": "le", "failure_class": "water-corridor", "region": "-540→-880 逆向"},
    {"archive": "replay-0005.json", "arrive_x": -520, "cmp": "ge", "failure_class": "steep-diagUp", "region": "-878,61,299"},
    {"archive": "replay-0006.json", "arrive_x": -520, "cmp": "ge", "failure_class": "steep-diagUp", "region": "-822,63,196"}
  ]
}
```

- [ ] **Step 2: 写失败测试** — `scripts/pmcs/tests/test_corpus.py`

```python
import json, os, tempfile
from scripts.pmcs.corpus import load_corpus


def test_load_seed_corpus():
    entries = load_corpus("config/worlddriver/replays/corpus.json")
    archives = {e.archive for e in entries}
    assert "replay-0004.json" in archives
    e4 = next(e for e in entries if e.archive == "replay-0004.json")
    assert e4.cmp == "le" and e4.arrive_x == -520
    assert e4.failure_class == "water-corridor"


def test_cmp_validation():
    bad = {"silky_ticks": 120, "tolerance": 0.1,
           "entries": [{"archive": "x.json", "arrive_x": 0, "cmp": "WRONG",
                        "failure_class": "f", "region": "r"}]}
    p = os.path.join(tempfile.mkdtemp(), "bad.json")
    open(p, "w").write(json.dumps(bad))
    try:
        load_corpus(p)
        assert False, "should reject cmp not in ge/le"
    except ValueError:
        pass
```

- [ ] **Step 3: 跑测试确认失败**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_corpus.py -q`
Expected: FAIL — `ModuleNotFoundError: scripts.pmcs.corpus`

- [ ] **Step 4: 实现** — `scripts/pmcs/corpus.py`

```python
"""journey replay-corpus 清单加载器。"""
import json
from dataclasses import dataclass


@dataclass
class CorpusEntry:
    archive: str
    arrive_x: int
    cmp: str
    failure_class: str
    region: str


def load_corpus(path: str):
    with open(path) as f:
        data = json.load(f)
    out = []
    for e in data["entries"]:
        if e["cmp"] not in ("ge", "le"):
            raise ValueError(f"cmp must be ge|le, got {e['cmp']!r} for {e['archive']}")
        out.append(CorpusEntry(e["archive"], int(e["arrive_x"]), e["cmp"],
                               e["failure_class"], e["region"]))
    return out
```

- [ ] **Step 5: 跑测试确认通过**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_corpus.py -q`
Expected: PASS(2 passed)

- [ ] **Step 6: Commit**

```bash
git add config/worlddriver/replays/corpus.json scripts/pmcs/corpus.py scripts/pmcs/tests/test_corpus.py
git commit -m "feat(pmcs): corpus manifest + loader (seed 3 archives, diversity-tagged)"
```

---

### Task 5: live case runner(设 flag + 触发 replay + 收集指标)

**Files:**
- Create: `scripts/pmcs/run_case.py`
- Test: `scripts/pmcs/tests/test_run_case.py`(只测纯 helper;live 部分手动集成验证)

**Interfaces:**
- Consumes: `parse_log`/`peak_totstuck`(Task 1)、`conformance_table`(Task 2)
- Produces:
  - `log_slice_since(text:str, marker_line:int) -> str` — 取日志第 `marker_line` 行之后(纯函数,避免累计日志污染;镜像 wjourney.py 的 base 切片)
  - `@dataclass CaseResult(archive:str, max_stuck:int, arrived:bool, conformance:dict)`
  - `run_case(archive:str, flags:dict, arrive_x:int, cmp:str, timeout:int=220) -> CaseResult` — live 驱动

- [ ] **Step 1: 写失败测试(纯 helper)** — `scripts/pmcs/tests/test_run_case.py`

```python
from scripts.pmcs.run_case import log_slice_since


def test_log_slice_since_marker():
    text = "a\nb\nc\nd\n"
    assert log_slice_since(text, 2) == "c\nd\n"


def test_log_slice_zero_returns_all():
    text = "a\nb\n"
    assert log_slice_since(text, 0) == "a\nb\n"
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_run_case.py -q`
Expected: FAIL — `ModuleNotFoundError: scripts.pmcs.run_case`

- [ ] **Step 3: 实现** — `scripts/pmcs/run_case.py`

```python
"""驱动一个 (archive, flags) replay 并收集 maxStuck + per-Move conformance。

live 链路(spec §7 工作流第1步):
  1) 经 WS-RPC 设 flags(新 flag 会被 MCP 工具 schema strip,必须走 RPC——见 worlddriver-rpc skill)
  2) mc.debug.replay {file, restoreBlocks:true}
  3) 轮询 fabric/run/logs/latest.log 的 [walker] 行,到达 arrive_x 或超时即停
  4) 解析这段日志切片 → maxStuck + conformance
RPC 调用复用 scripts/journey_runner.py 的 call() 模式(websockets, JSON-RPC, port 39801)。
"""
import asyncio
import time
from dataclasses import dataclass

from scripts.pmcs.telemetry import parse_log, peak_totstuck, parse_walker_line
from scripts.pmcs.conformance import conformance_table

LOG = "fabric/run/logs/latest.log"
RPC_URL = "ws://127.0.0.1:39801"


def log_slice_since(text: str, marker_line: int) -> str:
    lines = text.splitlines(keepends=True)
    return "".join(lines[marker_line:])


def _read_log() -> str:
    with open(LOG, errors="ignore") as f:
        return f.read()


async def _rpc(method: str, params: dict):
    # 复用 journey_runner.py 的最小 JSON-RPC over websockets
    import json, websockets
    async with websockets.connect(RPC_URL, max_size=None) as ws:
        await ws.send(json.dumps({"id": 1, "method": method, "params": params}))
        while True:
            msg = json.loads(await ws.recv())
            if msg.get("id") == 1:
                return msg.get("result")


def _arrived(x: float, arrive_x: int, cmp: str) -> bool:
    return (x >= arrive_x) if cmp == "ge" else (x <= arrive_x)


@dataclass
class CaseResult:
    archive: str
    max_stuck: int
    arrived: bool
    conformance: dict


def run_case(archive: str, flags: dict, arrive_x: int, cmp: str, timeout: int = 220) -> CaseResult:
    base = len(_read_log().splitlines())
    asyncio.run(_rpc("mc.bot.setting", flags))
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
        if last and _arrived(last.x, arrive_x, cmp):
            arrived = True
            break
    sl = log_slice_since(_read_log(), base)
    ticks = parse_log(sl)
    return CaseResult(archive, peak_totstuck(ticks), arrived, conformance_table(ticks))
```

- [ ] **Step 4: 跑纯 helper 测试确认通过**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_run_case.py -q`
Expected: PASS(2 passed)

- [ ] **Step 5: 安装 websockets(live 依赖)**

Run: `.venv/bin/python3 -m pip install -q websockets`
Expected: 成功。

- [ ] **Step 6: Commit**

```bash
git add scripts/pmcs/run_case.py scripts/pmcs/tests/test_run_case.py
git commit -m "feat(pmcs): live case runner — RPC flag-set + replay + maxStuck/conformance collect"
```

> **集成验证(需 live client,Task 7 corpus 提交后做)**:对一个已知归档跑
> `run_case("replay-0006.json", {}, -520, "ge")`,确认 `max_stuck` 与 REGRESSION.md 记录的 OFF baseline(~1814)同量级。这是机制端到端能跑的烟测。

---

### Task 6: corpus runner + 接受门接线(CLI)

**Files:**
- Create: `scripts/pmcs/run_corpus.py`
- Test: `scripts/pmcs/tests/test_run_corpus.py`(测 matrix→gate 纯组装)

**Interfaces:**
- Consumes: `load_corpus`(Task 4)、`run_case`/`CaseResult`(Task 5)、`evaluate_gate`(Task 3)
- Produces:
  - `build_matrix(results:list[CaseResult]) -> dict[str,int]` — archive→maxStuck(纯函数)
  - `divergent_moves(results:list[CaseResult]) -> list[str]` — 跨 corpus 合并:任一归档 churned>0 的 Move 类型并集,排序(纯函数,喂 REGRESSION.md §5 发散表)
  - `run_corpus(corpus_path:str, flags:dict, baseline) -> tuple[dict, GateVerdict|None]` — live;baseline=None 时只采集不判门(用于建 baseline)
  - CLI:`python3 -m scripts.pmcs.run_corpus --flags '{"walkerX":true}' --baseline baseline.json`

- [ ] **Step 1: 写失败测试** — `scripts/pmcs/tests/test_run_corpus.py`

```python
from scripts.pmcs.run_case import CaseResult
from scripts.pmcs.conformance import MoveStat
from scripts.pmcs.run_corpus import build_matrix, divergent_moves


def test_build_matrix():
    results = [CaseResult("a.json", 579, True, {}), CaseResult("b.json", 840, True, {})]
    assert build_matrix(results) == {"a.json": 579, "b.json": 840}


def test_divergent_moves_union():
    r1 = CaseResult("a.json", 0, True, {
        "walk": MoveStat("walk", 3, 0, 10),
        "stepUp2": MoveStat("stepUp2", 1, 1, 300)})       # churned
    r2 = CaseResult("b.json", 0, True, {
        "swimAshoreBreak": MoveStat("swimAshoreBreak", 2, 1, 400)})  # churned
    assert divergent_moves([r1, r2]) == ["stepUp2", "swimAshoreBreak"]
```

- [ ] **Step 2: 跑测试确认失败**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_run_corpus.py -q`
Expected: FAIL — `ModuleNotFoundError: scripts.pmcs.run_corpus`

- [ ] **Step 3: 实现** — `scripts/pmcs/run_corpus.py`

```python
"""跑一个 flag 组合穿过全 corpus → maxStuck 矩阵 → 接受门裁决。"""
from __future__ import annotations
import argparse
import json

from scripts.pmcs.corpus import load_corpus
from scripts.pmcs.run_case import run_case
from scripts.pmcs.gate import evaluate_gate


def build_matrix(results):
    return {r.archive: r.max_stuck for r in results}


def divergent_moves(results):
    """跨 corpus 合并:任一归档里 churned>0 的 Move 类型并集(排序)。"""
    moves = set()
    for r in results:
        for move, stat in r.conformance.items():
            if stat.churned > 0:
                moves.add(move)
    return sorted(moves)


def run_corpus(corpus_path: str, flags: dict, baseline):
    entries = load_corpus(corpus_path)
    results = [run_case(e.archive, flags, e.arrive_x, e.cmp) for e in entries]
    matrix = build_matrix(results)
    verdict = evaluate_gate(baseline, matrix) if baseline else None
    return matrix, verdict


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", default="config/worlddriver/replays/corpus.json")
    ap.add_argument("--flags", default="{}")
    ap.add_argument("--baseline", default=None, help="baseline matrix JSON 文件(省略=只采集)")
    a = ap.parse_args()
    baseline = json.load(open(a.baseline)) if a.baseline else None
    matrix, verdict = run_corpus(a.corpus, json.loads(a.flags), baseline)
    print("matrix:", json.dumps(matrix, indent=2))
    if verdict:
        print(f"net_positive={verdict.net_positive} regressions={verdict.regressions} "
              f"crossed_silky={verdict.crossed_silky} → "
              f"{'ACCEPT' if verdict.accepted else 'REJECT'} "
              f"(sum {verdict.baseline_sum}→{verdict.candidate_sum})")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: 跑测试确认通过**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/test_run_corpus.py -q`
Expected: PASS(1 passed)

- [ ] **Step 5: 全 pmcs 单测回归**

Run: `.venv/bin/python3 -m pytest scripts/pmcs/tests/ -q`
Expected: PASS(全部,17 passed)

- [ ] **Step 6: Commit**

```bash
git add scripts/pmcs/run_corpus.py scripts/pmcs/tests/test_run_corpus.py
git commit -m "feat(pmcs): corpus runner CLI — maxStuck matrix → acceptance gate verdict"
```

---

### Task 7: corpus 种子归档提交 + REGRESSION.md 活文档重构

**Files:**
- Create: `config/worlddriver/replays/replay-0004.json`, `replay-0005.json`, `replay-0006.json`(从 runtime 复制精选)
- Modify: `config/worlddriver/replays/REGRESSION.md`

**Interfaces:**
- Consumes: corpus.json(Task 4)的 archive 名

- [ ] **Step 1: 定位并复制 runtime 归档**

Run:
```bash
cd /home/coder/AI-assisted-Minecraft-Developers/worlddriver
ls -t fabric/run/config/worlddriver/replays/replay-*.json 2>/dev/null | head -20
```
Expected: 列出 runtime 录制的归档。挑出对应 -815 diagUp(0005/0006)与 water-corridor(0004)区域的 plan 归档(看 header.start/goal),复制为稳定名:
```bash
cp fabric/run/config/worlddriver/replays/<选中的-0006>.json config/worlddriver/replays/replay-0006.json
cp fabric/run/config/worlddriver/replays/<选中的-0005>.json config/worlddriver/replays/replay-0005.json
cp fabric/run/config/worlddriver/replays/<选中的-0004>.json config/worlddriver/replays/replay-0004.json
```
> 若 runtime 已无这些归档:用 live client 重录(`mc.bot.setting{pathArchive:true}` → 从对应起点 `mc.bot.goto{xz:{x:-520,z:180}}` → 归档落到 fabric/run/.../replays/)。这是 corpus 多样性持续工作的一部分(spec §9 风险2)。

- [ ] **Step 2: 确认 replay 能读到提交的归档**

Run(需 live client):经 RPC `mc.debug.replay {file:"replay-0006.json", restoreBlocks:true}`,确认返回 `ok:true` 且 `restoredBlocks>0`。
Expected: replay 启动,bot 在 -815 区开始走。

- [ ] **Step 3: REGRESSION.md 重构为活文档**

把 `config/worlddriver/replays/REGRESSION.md` 重构成固定章节(覆盖旧的临时记录):
```markdown
# Replay-Corpus Regression Harness (living doc)

## 1. 用法
- 设 flags(RPC,非 MCP 工具)→ `mc.debug.replay{file,restoreBlocks:true}` → `python3 -m scripts.pmcs.run_corpus --flags '...' --baseline baseline.json`
- maxStuck = [walker] totStuck 峰值;silky < 120(≈6s);容差 10%。

## 2. Corpus 清单(权威源 = corpus.json)
| archive | 故障类 | 起点 | arrive | 备注 |
|---|---|---|---|---|
| replay-0004 | water-corridor | -540→-880逆向 | x≤-520 | OFF baseline 579 |
| replay-0005 | steep-diagUp | -878,61,299 | x≥-520 | OFF 840 |
| replay-0006 | steep-diagUp | -822,63,196 | x≥-520 | OFF 1814 |
| (待补:深水穿越/水岸climb-out/树冠/峡谷 → 目标 ~10-15) | | | | |

## 3. 当前已接受 flag stack(default-ON)
(空 —— 本计划只建机制;任何 flip 由接受门 + 用户决定)

## 4. Baseline 矩阵(all default)
| archive | maxStuck |
|---|---|
| replay-0004 | 579 |
| replay-0005 | 840 |
| replay-0006 | 1814 |

## 5. 发散清单(per-Move conformance,run_corpus 产出后填)
(待首次发现运行填入)

## 6. Lever 历史(候选 flag 组合 × archive)
| 组合 | 0004 | 0005 | 0006 | 门裁决 |
|---|---|---|---|---|
| OFF(baseline) | 579 | 840 | 1814 | — |
| apw-stack | 1935 | 649 | 829 | REJECT(0004 回归) |
```

- [ ] **Step 4: Commit**

```bash
git add config/worlddriver/replays/replay-000*.json config/worlddriver/replays/REGRESSION.md
git commit -m "chore(corpus): seed 3 diverse archives + restructure REGRESSION.md as living doc"
```

---

### Task 8: 环境 runbook

**Files:**
- Create: `docs/runbook-pathfinding-loop.md`

- [ ] **Step 1: 写 runbook**(固化本项目踩过的环境坑 + 三件套判别器)

`docs/runbook-pathfinding-loop.md`:
```markdown
# Pathfinding Conformance Loop — Runbook

## Client 启动 / relaunch
- `DISPLAY=:99 ./gradlew :fabric:runClient`(Xvfb :99,MCP 39800,RPC 39801)。
- 菜单导航:Singleplayer (213,106) → Mountains (213,68) → Play Selected (134,198) → `mc.wait.worldReady`。
- relaunch 前 kill stray gradle:`ps aux|grep -iE "java.*(GradleWrapper|fabric.*runClient)"|awk '{print $2}'|xargs -r kill -9`。
- **禁止在 bot 处于水中时 relaunch**(先 tp 到干地)。
- Client GL hang(shader render 线程):需系统重启(fresh Xvfb 无效)。

## 设 flag(新 flag 必须走 RPC)
- MCP 工具 schema 在 session 启动冻结 → 新加的 BotConfig key 被 strip。
- 用 RPC(port 39801,见 worlddriver-rpc skill 的 rpc.py)或 `mc.script_eval` 里 `Agent.invoke('mc.bot.setting', {...})`。

## 三件套分层判别器(定 class A vs B)
1. `mc.observe.map` / `mc.client.blocks` — 真几何(非假设)。
2. `mc.debug.plan {goal, chain:true}` — planner 判别(goalReached/maxRegression/backwardSegments)。
3. `[walker]` telemetry + `run_case` conformance — executor 判别(哪个 Move churn)。
- planner 干净 + executor churn = class B(执行器脆弱);planner 发不可实现 move = class A。

## maxStuck 读取
- `grep '[walker] t=' fabric/run/logs/latest.log | <parse totStuck 峰值>`,或 `scripts/pmcs/run_case.py`。

## 终验协议(#47)
- 3 随机起终点 × 各 3 replay = 9 clean + live-screen-watch video 零卡点(见 live-screen-watch skill)。
```

- [ ] **Step 2: Commit**

```bash
git add docs/runbook-pathfinding-loop.md
git commit -m "docs(runbook): pathfinding loop environment + three-probe discriminator runbook"
```

---

### Task 9: 首次发现运行(机制端到端验证)

**Files:** 无新文件;产出写入 `REGRESSION.md` §5。

> 这一步**需要 live client**,是机制的端到端烟测:跑一次发现,确认 pmcs 真能从真实 corpus 列出发散表 + 接受门真能裁决。

- [ ] **Step 1: 起 live client + 加载 Mountains 世界**(按 runbook)。

- [ ] **Step 2: 采集 baseline 矩阵**

Run(经 RPC 设全 default 后):
```bash
.venv/bin/python3 -m scripts.pmcs.run_corpus --flags '{}'
```
把输出 matrix 存为 `config/worlddriver/replays/baseline.json`。
Expected: 三归档 maxStuck 与 REGRESSION.md §4(579/840/1814)同量级(确定性复现,允许小幅波动)。

- [ ] **Step 3: 产出首张 per-Move 发散表**

跑 baseline 采集时保留每归档 `CaseResult`,调 `divergent_moves(results)`(Task 6)得发散 Move 并集,写入 REGRESSION.md §5。最小脚本:
```python
from scripts.pmcs.corpus import load_corpus
from scripts.pmcs.run_case import run_case
from scripts.pmcs.run_corpus import divergent_moves
entries = load_corpus("config/worlddriver/replays/corpus.json")
results = [run_case(e.archive, {}, e.arrive_x, e.cmp) for e in entries]
print("divergent moves:", divergent_moves(results))
```
Expected: 至少复现已知发散(steep diagUp 段的 stepUp2/diagUp churn;water 段的 swimAshoreBreak/swimBankClimb churn)——即用户举的 +2坡/+1岸类。

- [ ] **Step 4: 用真实回归验证门**

Run(经 RPC 设 apw-stack flags 后):
```bash
.venv/bin/python3 -m scripts.pmcs.run_corpus --flags '{"walkerArcProgressWedge":true, ...apw-stack...}' --baseline config/worlddriver/replays/baseline.json
```
Expected: 门输出 **REJECT**,`regressions` 含 `replay-0004`(复现 over-fit 被挡)。**这是机制有效的决定性证据**:它自动抓住了我此前靠人肉才发现的回归。

- [ ] **Step 5: Commit 发现结果**

```bash
git add config/worlddriver/replays/baseline.json config/worlddriver/replays/REGRESSION.md
git commit -m "feat(pmcs): first discovery run — baseline matrix + divergence table + gate REJECT on apw over-fit"
```

---

## Scope:本计划之后(用机制做真实修复)

本计划交付**机制**(发现 + 判定 + 工具),是可独立测试的软件。**用它修复寻路**是后续迭代循环(spec §7),每轮:发现表排序 → 三件套定根因(A/B)→ default-OFF flag 修复 → `run_corpus` 过接受门 → flip default-ON → 收敛后 #47 终验。每个具体修复是独立小变更,逐个做,不在本计划内(避免把开放式修复循环塞进一个 plan)。corpus 补到 ~10-15 多样归档是贯穿后续的持续工作。

## 诚实风险(spec §9,执行时牢记)
1. **class B 修复本质难**:-815 churn approach-dependent,可能无单一 flag 全赢;机制让判断有据,不消除难度。
2. **corpus 多样性 = 门的生命线**:种子只 3 归档,门此时还弱;补齐多样性前别宣布"全集通过"。
3. **per-Move case 真实性**:全靠 `restoreBlocks:true` 忠实复现;envelope-cutting + 多起始状态(需 ReplayTool 支持 fromStep)是 MVP 后增强,本计划用"全归档忠实 replay + 按 step 段聚合"近似 Layer 1。

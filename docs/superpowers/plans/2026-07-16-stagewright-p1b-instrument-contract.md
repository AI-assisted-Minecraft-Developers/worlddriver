# stagewright P1b：最小仪表契约子集 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立仪表契约套件的 T0 依赖面子集（裸 RPC 直打 route()，跑在 worlddriver 裸专服上），让信任链在 P1c dogfood 开始前闭合：仪表契约绿 → testkit setup/断言可信。

**Architecture:** 独立 Python 运行器 `scripts/stagewright/instrument.py`（与 testkit 断言栈零耦合，spec §4.2），通过新的 `contractServer` run 配置启动 worlddriver 裸专服（双 loader），经 websocket 裸 RPC 执行 ~17 条契约检查 + 2 只金丝雀，JSONL 落盘后用与 t0.py 共享的 verdict 模块裁决（0 GREEN/1 RED/2 DEAD/3 ENV）。判决语义与编排契约 v0 同构（注册=执行对账、金丝雀误判=DEAD）。

**Tech Stack:** Python 3 stdlib + websocket 手写 envelope（复用 rpc_call.py 的连接方式）；gradle/architectury-loom run 配置；无新 Java 面（本阶段零 worlddriver 行为变更）。

## Global Constraints

- 依赖方向：testkit → worlddriver，永不反向；仪表契约套件**独立于 testkit 断言栈**（spec §4.2，不 import stagewright 模块）。
- 契约检查**只用仪表面** verbs（`mc.system/observe/query/action/world/wait/events/script`）；行为面（`mc.bot.goto/mine/...`）绝不出现在检查体内——唯一例外是断言 client-only verb 在专服上大声失败这一条 dispatch 检查。
- 编排契约 v0（`docs/stagewright/orchestration-contract-v0.md`）**不改语义**；t0.py 重构后 `--self-test` 必须 11/11 PASS 且输出记录逐字节不变。
- 退出码语义沿用契约 v0：0 GREEN / 1 RED / 2 DEAD（金丝雀误判=门死，整轮作废）/ 3 ENV。
- 运行纪律：等待用 Bash 工具 timeout 参数；JVM 清扫按显式 PID（匹配 `[a]gent.contractRun`），禁 pkill；每 run 前删 `run-contract/world`。
- RPC wire 格式是手写 envelope 非 JSON-RPC 2.0：发 `{"id":N,"method":"mc.x.y","params":{…}}`，收 `{"id":N,"result":…}` 或 `{"id":N,"error":"<string>"}`（参照 `scripts/rpc_call.py`）。
- 端口：contract 专服 `server-port=25597`（避开 t0 的 25599 与默认 25565）；RPC 端口用 ephemeral（`worlddriver.rpcPort=0`）+ 读 `run-contract/worlddriver-rpc.port` 端口文件发现，**绝不硬编码 39801**（39801 是 live 客户端的）。

## 已声明偏差 / 顺延清单（评审勿标缺）

1. **子集 ≠ 全套**：spec §4.2 的 30-50 条是完整套件目标；P1b 只建 T0 依赖面子集（route dispatch / 观察读 / 直接世界操作 / wait / events，~17 条）。客户端仪表（input 注入、屏幕内省）归 P2。
2. **#41 全背包 / #45 攻击冷却 / #55 伤害源** 永久断言顺延 P2：`mc.observe.player` 需要 PlayerList 里有真玩家，裸专服 headless 拿不到（`/agentserver` 的 FakePlayer 不入 PlayerList，对 observe.player 不可见——此 gap 在 Task 5 的契约文档里记为「已知缺口+未来断言」）。P1b 以 `obs.playerAbsentPin`（present:false 语义钉死）+ `obs.containerDurability`（#42 耐久字段经 container 路径）覆盖可及部分。
3. **#280 setting 未知键静默吞**不在本阶段修：`mc.bot.setting` 属 `mc.bot.*`，专服上抛 client-only（勘察实证 DriverApi.java:395-409）——不属于 T0 仪表面。契约以 `route.clientOnlyVerb` 断言其大声失败；未知键行为修复+断言归 P2 客户端仪表面。
4. 双 loader 验收分级沿用 P1：neoforge 全检查 + fabric 同套跑通即可（fabric 无 sim 包不影响——本子集不用任何 body）。

## 文件结构

| 文件 | 职责 |
|---|---|
| `scripts/stagewright/verdict.py`（新） | parse()/judge() 纯裁决，从 t0.py 抽出，t0 与 instrument 共享 |
| `scripts/stagewright/t0.py`（改） | 保留 launch/provision/sweep/CLI，裁决改 import verdict |
| `scripts/stagewright/instrument.py`（新） | 仪表契约运行器：provision/launch/RPC 客户端/检查注册表/JSONL/裁决/self-test |
| `neoforge/build.gradle`、`fabric/build.gradle`（改） | 各加 `contractServer` run 配置（runDir `run-contract`，marker+ephemeral 端口） |
| `docs/stagewright/instrument-contract-v0.md`（新） | 检查清单+永久断言台账+已知缺口册 |
| `stagewright/README.md`、`TODO.md`、`AGENTS.md`（改） | 入口文档、收尾条目、日志位置行 |

---

### Task 1: 从 t0.py 抽出共享 verdict 模块

**Files:**
- Create: `scripts/stagewright/verdict.py`
- Modify: `scripts/stagewright/t0.py`（删除 parse/judge 定义，改 import）

**Interfaces:**
- Produces: `verdict.parse(lines: list[str]) -> list[dict]`（与现 t0.parse 同义：逐行 json.loads，坏行抛 ValueError 且消息含行内容前 120 字符）；`verdict.judge(records: list[dict], record_type: str = "scene") -> tuple[int, list[str]]`（与现 t0.judge 完全同语义，唯一泛化点：场景记录的 `type` 字段值由参数给定，报告行里的名词跟随该参数）。
- Consumes: 无。

- [ ] **Step 1: 创建 verdict.py**

把 t0.py 中 `parse()` 与 `judge()` 两个函数**原样搬移**到新文件 `scripts/stagewright/verdict.py`（带模块 docstring：`"""Shared pure-verdict logic for testkit runners (t0 scenes, instrument checks). Semantics frozen by docs/stagewright/orchestration-contract-v0.md — do not change judge() behavior without a contract review."""`）。唯一允许的编辑：`judge()` 增加第二参数 `record_type="scene"`，函数体内所有 `rec.get("type") == "scene"` 比较与报告行文案里的 `scene` 字样改用该参数（`f"{record_type}"`）。**不做其它任何重构**——这是搬移不是改写。

- [ ] **Step 2: t0.py 改 import**

t0.py 顶部加 `from verdict import parse, judge`（t0.py 与 verdict.py 同目录；直跑 `python3 scripts/stagewright/t0.py` 时需 `sys.path` 保障——在 import 前加：

```python
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verdict import parse, judge
```

删除 t0.py 内的 parse/judge 定义。self-test fixtures 与检查列表留在 t0.py（它们测的是场景语义，属 t0 的契约锁）。

- [ ] **Step 3: 验证零漂移**

Run: `python3 scripts/stagewright/t0.py --self-test; echo "exit=$?"`
Expected: 11/11 PASS，exit=0。

再跑一轮真裁决确认端到端不变：
Run: `python3 scripts/stagewright/t0.py --loader neoforge --wall 540; echo "exit=$?"`
Expected: `VERDICT: GREEN`，exit=0。

- [ ] **Step 4: Commit**

```bash
git add scripts/stagewright/verdict.py scripts/stagewright/t0.py
git commit -m "refactor(testkit): extract shared verdict module from t0.py (behavior frozen, self-test 11/11)"
```

---

### Task 2: contractServer run 配置 + instrument.py 骨架端到端

**Files:**
- Modify: `neoforge/build.gradle`（runs 块内加 contractServer）
- Modify: `fabric/build.gradle`（同）
- Create: `scripts/stagewright/instrument.py`

**Interfaces:**
- Consumes: Task 1 的 `verdict.parse/judge`。
- Produces: `instrument.py` 的检查注册表约定——`CHECKS: list[tuple[str, str, callable|None]]`，元素 `(name, canary, fn)`，`canary ∈ {"NONE","MUST_FAIL","MUST_SWALLOW"}`；`fn(ctx)` 返回 None=PASS、抛 `ContractFailure(reason)`=FAIL；`ctx.call(method, params=None) -> result`（error 帧自动转 `ContractFailure`）、`ctx.call_raw(method, params=None) -> (result, error)`（不抛，用于断言错误形状）。Task 3/4 向 CHECKS 追加条目。

- [ ] **Step 1: 两个 build.gradle 加 contractServer run 配置**

`neoforge/build.gradle` 的 `runs { ... }` 块内（gameTestServer 之后）追加：

```groovy
        contractServer {
            server()
            property 'worlddriver.contractRun', 'true'
            property 'worlddriver.rpcPort', '0'
            property 'worlddriver.mcpPort', '0'
            runDir 'run-contract'
            jvmArg '-Xmx1g'
        }
```

`fabric/build.gradle` 的 `runs { ... }` 块内追加同一段（逐字相同）。

- [ ] **Step 2: 验证 run 任务存在且端口生效**

Run: `./gradlew :neoforge:tasks --all 2>/dev/null | grep -i contract; ./gradlew :fabric:tasks --all 2>/dev/null | grep -i contract`
Expected: 两行 `runContractServer`。

⚠️ 命名风险：fabric 的 `runConfigs.configureEach`（fabric/build.gradle:94-112）会给**所有** run 配置钉 `worlddriver.rpcPort=39801`——若 configureEach 后执行覆盖了本配置的 `'0'`，contract 专服会跟 live 客户端抢 39801。验证方法在 Step 4 首启后：`cat fabric/run-contract/worlddriver-rpc.port` 必须**不是** 39801（ephemeral 高位端口）。若是 39801，修法：configureEach 体内加名字守卫 `if (it.name == 'contractServer') return` 后重验。neoforge 的 configureEach 不钉端口，无此风险。

- [ ] **Step 3: 写 instrument.py 骨架**

创建 `scripts/stagewright/instrument.py`（完整骨架，含 1 条真检查 + 2 只金丝雀 + self-test）：

```python
#!/usr/bin/env python3
"""Instrument contract suite (T0-dependency face) — bare-RPC checks against a
plain worlddriver dedicated server. Independent of the testkit assertion
stack by design (spec §4.2): green here => testkit setup/asserts may trust
the driver's instrument face. Verdict semantics mirror contract v0 via the
shared verdict module (registered==executed reconciliation, canary
mis-judgement => DEAD)."""
import argparse, base64, hashlib, json, os, socket, struct, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verdict import parse, judge

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODULE = {"neoforge": "neoforge", "fabric": "fabric"}
SERVER_PORT = 25597


class ContractFailure(Exception):
    pass


# ---------- minimal websocket client (stdlib only, mirrors rpc_call.py) ----------
class Ws:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((
            f"GET /rpc HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.sock.recv(4096)
        if b" 101 " not in buf.split(b"\r\n", 1)[0]:
            raise ContractFailure("websocket handshake failed")
        self.sock.settimeout(30)

    def send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        hdr = b"\x81"
        n = len(data)
        if n < 126:
            hdr += bytes([0x80 | n])
        elif n < 65536:
            hdr += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            hdr += bytes([0x80 | 127]) + struct.pack(">Q", n)
        self.sock.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _read_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ContractFailure("socket closed")
            buf += chunk
        return buf

    def recv(self):
        while True:
            b1, b2 = self._read_exact(2)
            op = b1 & 0x0F
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read_exact(8))[0]
            payload = self._read_exact(n) if n else b""
            if op == 1:
                return json.loads(payload.decode())
            if op == 8:
                raise ContractFailure("websocket closed by server")
            # ignore ping/pong/continuation for this suite


class Ctx:
    def __init__(self, ws):
        self.ws = ws
        self._id = 0

    def call_raw(self, method, params=None):
        self._id += 1
        rid = self._id
        self.ws.send({"id": rid, "method": method, "params": params or {}})
        while True:
            frame = self.ws.recv()
            if frame.get("id") == rid:
                return frame.get("result"), frame.get("error")
            # notifications (event push) are ignored; we never subscribe

    def call(self, method, params=None):
        result, error = self.call_raw(method, params)
        if error is not None:
            raise ContractFailure(f"{method} -> error: {error}")
        return result


# ---------- checks ----------
def check_version_shape(ctx):
    v = ctx.call("mc.system.version")
    if v.get("modid") != "worlddriver":
        raise ContractFailure(f"modid={v.get('modid')!r} != 'worlddriver'")
    if not isinstance(v.get("uptimeMs"), int) or v["uptimeMs"] < 0:
        raise ContractFailure(f"uptimeMs not a non-negative int: {v.get('uptimeMs')!r}")


def canary_must_fail(ctx):
    raise ContractFailure("canary: this check must be reported as FAIL")


CHECKS = [
    ("system.versionShape", "NONE", check_version_shape),
    ("canary.mustFail", "MUST_FAIL", canary_must_fail),
    ("canary.mustSwallow", "MUST_SWALLOW", None),  # registered, never executed
]


# ---------- provision / launch / sweep ----------
def run_dir(loader):
    return os.path.join(ROOT, MODULE[loader], "run-contract")


def provision(loader):
    rd = run_dir(loader)
    os.makedirs(rd, exist_ok=True)
    with open(os.path.join(rd, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(rd, "server.properties"), "w") as f:
        f.write("\n".join([
            f"server-port={SERVER_PORT}", "online-mode=false", "level-type=minecraft:flat",
            "sync-chunk-writes=false", "spawn-protection=0", "motd=instrument-contract",
        ]) + "\n")
    subprocess.run(["rm", "-rf", os.path.join(rd, "world")], check=True)
    for leftover in ("worlddriver-rpc.port", "worlddriver-mcp.port", "instrument-results.jsonl"):
        p = os.path.join(rd, leftover)
        if os.path.exists(p):
            os.remove(p)


def sweep():
    out = subprocess.run(["ps", "ax", "-o", "pid=,args="], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "worlddriver.contractRun" in line and "grep" not in line:
            pid = line.strip().split()[0]
            print(f"[instrument] killing leftover contract JVM pid={pid}")
            subprocess.run(["kill", "-9", pid], check=False)


def wait_port_file(loader, wall):
    pf = os.path.join(run_dir(loader), "worlddriver-rpc.port")
    deadline = time.time() + wall
    while time.time() < deadline:
        if os.path.exists(pf):
            try:
                return int(open(pf).read().strip())
            except ValueError:
                pass
        time.sleep(2)
    return None


def launch(loader, wall):
    sweep()
    provision(loader)
    task = f":{MODULE[loader]}:runContractServer"
    print(f"[instrument] launching: ./gradlew {task} (wall={wall}s, waiting on worlddriver-rpc.port)")
    proc = subprocess.Popen(["./gradlew", task], cwd=ROOT,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    port = wait_port_file(loader, wall)
    if port is None:
        proc.kill()
        sweep()
        return None
    # RPC comes up before attachServer(onServerStarted); wait until version answers
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            ws = Ws("127.0.0.1", port)
            ctx = Ctx(ws)
            ctx.call("mc.system.version")
            return ctx
        except Exception:
            time.sleep(2)
    sweep()
    return None


def stop(ctx):
    try:
        ctx.call_raw("mc.action.runCommand", {"cmd": "stop"})
    except Exception:
        pass
    time.sleep(5)
    sweep()


# ---------- run + judge ----------
def run_suite(loader, wall):
    ctx = launch(loader, wall)
    if ctx is None:
        print("[instrument] ENV: server/RPC never came up")
        return 3
    lines = [json.dumps({"type": "suite", "loader": loader, "face": "instrument",
                         "registered": [{"name": n, "required": True, "canary": c}
                                        for n, c, _ in CHECKS]})]
    try:
        for name, canary, fn in CHECKS:
            if fn is None:
                continue  # MUST_SWALLOW: registered, deliberately not executed
            t0 = time.time()
            try:
                fn(ctx)
                outcome, reason = "PASS", ""
            except ContractFailure as e:
                outcome, reason = "FAIL", str(e)
            except Exception as e:  # transport/unexpected => ENV-grade, but record honestly
                outcome, reason = "ENV_FAIL", f"{type(e).__name__}: {e}"
            lines.append(json.dumps({"type": "check", "name": name, "outcome": outcome,
                                     "ticks": 0, "wallMs": int((time.time() - t0) * 1000),
                                     "reason": reason}))
    finally:
        stop(ctx)
    lines.append(json.dumps({"type": "done", "scenes": sum(1 for _, c, f in CHECKS if f is not None)}))
    results = os.path.join(run_dir(loader), "instrument-results.jsonl")
    with open(results, "w") as f:
        f.write("\n".join(lines) + "\n")
    code, report = judge(parse(lines), record_type="check")
    for r in report:
        print(f"[instrument] {r}")
    print(f"[instrument] VERDICT: {['GREEN','RED','DEAD','ENV'][code]}")
    return code


# ---------- self-test ----------
def self_test():
    reg = {"type": "suite", "loader": "x", "registered": [
        {"name": "a", "required": True, "canary": "NONE"},
        {"name": "cf", "required": True, "canary": "MUST_FAIL"},
        {"name": "cs", "required": True, "canary": "MUST_SWALLOW"}]}
    def rec(name, outcome):
        return {"type": "check", "name": name, "outcome": outcome, "ticks": 0,
                "wallMs": 0, "reason": ""}
    done = {"type": "done", "scenes": 2}
    checks = [
        ("all good -> 0", judge([reg, rec("a", "PASS"), rec("cf", "FAIL"), done],
                                record_type="check")[0] == 0),
        ("real check FAIL -> 1", judge([reg, rec("a", "FAIL"), rec("cf", "FAIL"), done],
                                       record_type="check")[0] == 1),
        ("must-fail canary PASS -> 2", judge([reg, rec("a", "PASS"), rec("cf", "PASS"), done],
                                             record_type="check")[0] == 2),
        ("swallow canary executed -> 2", judge([reg, rec("a", "PASS"), rec("cf", "FAIL"),
                                                rec("cs", "PASS"), done],
                                               record_type="check")[0] == 2),
        ("real check swallowed -> 1", judge([reg, rec("cf", "FAIL"), done],
                                            record_type="check")[0] == 1),
    ]
    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(f"[self-test] {'PASS' if ok else 'FAIL'}: {n}")
    print(f"[self-test] {len(checks) - len(failed)}/{len(checks)} PASS")
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--loader", choices=["neoforge", "fabric"], default="neoforge")
    ap.add_argument("--wall", type=int, default=300)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    sys.exit(self_test() if args.self_test else run_suite(args.loader, args.wall))


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: self-test 与首次真跑**

Run: `python3 scripts/stagewright/instrument.py --self-test; echo "exit=$?"`
Expected: 5/5 PASS，exit=0。

Run: `python3 scripts/stagewright/instrument.py --loader neoforge --wall 300; echo "exit=$?"`
Expected: `VERDICT: GREEN`，exit=0（1 真检查 PASS + mustFail 捕获 + mustSwallow 正确缺席）。

随后确认端口纪律：`cat neoforge/run-contract/worlddriver-rpc.port` 是高位 ephemeral 端口（非 39801/25597）；`ps aux | grep -E '[a]gent.contractRun'` 为空（stop+sweep 干净）。

- [ ] **Step 5: Commit**

```bash
git add neoforge/build.gradle fabric/build.gradle scripts/stagewright/instrument.py
git commit -m "feat(testkit): instrument contract runner skeleton — contractServer run configs, bare-RPC ws client, canary pair, shared verdict"
```

---

### Task 3: 检查批 A——route dispatch / schema / 解释器面

**Files:**
- Modify: `scripts/stagewright/instrument.py`（CHECKS 表追加 6 条 + 对应函数）

**Interfaces:**
- Consumes: Task 2 的 `ctx.call/call_raw`、`ContractFailure`、CHECKS 约定。
- Produces: 检查名 `route.unknownMethod`、`route.invalidParams.missingKey`、`route.invalidParams.wrongType`、`route.invalidParams.unknownKey`、`route.clientOnlyVerb`、`script.evalParity`。

- [ ] **Step 1: 追加检查函数**

在 `canary_must_fail` 前插入（错误形状字符串以 DriverApi.java:441 / SchemaValidator.java:25-28 / DriverApi.java:395-409 为准）：

```python
def check_unknown_method(ctx):
    result, error = ctx.call_raw("mc.no.suchMethod")
    if error is None:
        raise ContractFailure(f"unknown method answered result={result!r} instead of error")
    if "unknown method" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


def check_invalid_params_missing(ctx):
    # mc.observe.eventsSince requires 'cursor'
    result, error = ctx.call_raw("mc.observe.eventsSince", {})
    if error is None:
        raise ContractFailure(f"missing required key accepted: {result!r}")
    if "invalid params" not in error and "cursor" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


def check_invalid_params_wrong_type(ctx):
    result, error = ctx.call_raw("mc.observe.eventsSince", {"cursor": "not-a-number"})
    if error is None:
        raise ContractFailure(f"wrong-typed param accepted: {result!r}")


def check_invalid_params_unknown_key(ctx):
    result, error = ctx.call_raw("mc.system.waitTicks", {"ticks": 1, "bogusKey": 1})
    if error is None:
        raise ContractFailure(f"unexpected key accepted: {result!r}")
    if "unexpected key" not in error and "invalid params" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


def check_client_only_verb(ctx):
    # instrument face must fail LOUDLY on a dedicated server, never silently no-op
    result, error = ctx.call_raw("mc.bot.status")
    if error is None:
        raise ContractFailure(f"mc.bot.* answered on dedicated server: {result!r}")
    if "client only" not in error:
        raise ContractFailure(f"error shape drifted: {error!r}")


def check_script_eval_parity(ctx):
    # in-JVM route (invokeJson) must agree with the external transport
    r = ctx.call("mc.script.eval",
                 {"source": "Driver.invoke('mc.system.version').modid", "timeoutMs": 5000})
    if r.get("error"):
        raise ContractFailure(f"script error: {r['error']}")
    if r.get("result") != "worlddriver":
        raise ContractFailure(f"in-JVM route parity broken: {r.get('result')!r}")
```

CHECKS 表在 `("system.versionShape", ...)` 之后、金丝雀之前追加：

```python
    ("route.unknownMethod", "NONE", check_unknown_method),
    ("route.invalidParams.missingKey", "NONE", check_invalid_params_missing),
    ("route.invalidParams.wrongType", "NONE", check_invalid_params_wrong_type),
    ("route.invalidParams.unknownKey", "NONE", check_invalid_params_unknown_key),
    ("route.clientOnlyVerb", "NONE", check_client_only_verb),
    ("script.evalParity", "NONE", check_script_eval_parity),
```

⚠️ 若某条错误子串断言与实际不符（例如 eventsSince 的 schema 对 cursor 并非 required、或 waitTicks schema 是开放的不拒未知键），**先用 `ctx.call_raw` 的实际返回修正断言选用的方法/子串**（从 ToolCatalog schema 找一个确有 required int 键、确为封闭 schema 的仪表面方法替换），并在 commit message 里注明替换原因——契约断言必须钉在真实形状上，不许放宽为「有 error 就行」。

- [ ] **Step 2: 真跑验证**

Run: `python3 scripts/stagewright/instrument.py --loader neoforge --wall 300; echo "exit=$?"`
Expected: `VERDICT: GREEN`，exit=0，7 条真检查全 PASS。

- [ ] **Step 3: Commit**

```bash
git add scripts/stagewright/instrument.py
git commit -m "test(testkit): instrument contract batch A — route dispatch, schema violations, client-only loudness, script parity"
```

---

### Task 4: 检查批 B——直接世界操作 / 观察读 / wait / events

**Files:**
- Modify: `scripts/stagewright/instrument.py`（CHECKS 追加 10 条 + 函数）

**Interfaces:**
- Consumes: 同 Task 3。
- Produces: 检查名 `world.setblockQueryReadback`、`world.fillCount`、`world.snapshotRestore`、`obs.containerDurability`、`obs.playerAbsentPin`、`obs.entityQuery`、`events.commandResult`、`events.cursorMonotonic`、`wait.ticks`、`wait.conditionValue`。

- [ ] **Step 1: 追加检查函数**

坐标纪律：每条检查用独立坐标与独立方块种类（平坦世界 y=200 高空，出生 chunk 常驻加载），检查间零共享状态。全部插在批 A 函数之后：

```python
def _cmd(ctx, cmd):
    r = ctx.call("mc.action.runCommand", {"cmd": cmd})
    if not r.get("ok"):
        raise ContractFailure(f"command dispatch failed: {cmd!r} -> {r!r}")
    return r


def check_setblock_query_readback(ctx):
    _cmd(ctx, "setblock 0 200 0 minecraft:gold_block")
    rows = ctx.call("mc.query", {"q": "blocks", "center": {"x": 0, "y": 200, "z": 0},
                                 "filter": {"in_radius": 1, "type": "minecraft:gold_block"}})
    if len(rows) != 1 or rows[0]["pos"] != {"x": 0, "y": 200, "z": 0}:
        raise ContractFailure(f"driver read disagrees with vanilla write: {rows!r}")


def check_fill_count(ctx):
    r = ctx.call("mc.action.fill", {"from": {"x": 4, "y": 200, "z": 4},
                                    "to": {"x": 6, "y": 202, "z": 6},
                                    "type": "minecraft:polished_andesite"})
    if not r.get("ok") or r.get("placed") != 27:
        raise ContractFailure(f"fill 3x3x3 placed={r.get('placed')!r} != 27")
    rows = ctx.call("mc.query", {"q": "blocks", "center": {"x": 5, "y": 201, "z": 5},
                                 "filter": {"in_radius": 2, "type": "minecraft:polished_andesite"}})
    if len(rows) != 27:
        raise ContractFailure(f"query readback {len(rows)} != 27")


def check_snapshot_restore(ctx):
    snap = ctx.call("mc.world.snapshot", {"from": {"x": 10, "y": 200, "z": 10},
                                          "to": {"x": 12, "y": 202, "z": 12}})
    if not snap.get("ok"):
        raise ContractFailure(f"snapshot failed: {snap!r}")
    _cmd(ctx, "setblock 11 201 11 minecraft:emerald_block")
    r = ctx.call("mc.world.restore", {"id": snap["id"], "discard": True})
    if not r.get("ok"):
        raise ContractFailure(f"restore failed: {r!r}")
    rows = ctx.call("mc.query", {"q": "blocks", "center": {"x": 11, "y": 201, "z": 11},
                                 "filter": {"in_radius": 2, "type": "minecraft:emerald_block"}})
    if rows:
        raise ContractFailure(f"restore left the marker block behind: {rows!r}")


def check_container_durability(ctx):
    # #42 permanent assertion, dual-source: vanilla write path vs driver read path
    _cmd(ctx, "setblock 20 200 20 minecraft:chest")
    _cmd(ctx, "item replace block 20 200 20 container.0 with minecraft:diamond_pickaxe[minecraft:damage=123] 1")
    r = ctx.call("mc.observe.container", {"pos": {"x": 20, "y": 200, "z": 20}})
    if not r.get("present"):
        raise ContractFailure(f"chest not present: {r!r}")
    s0 = (r.get("slots") or [None])[0]
    if not s0 or s0.get("id") != "minecraft:diamond_pickaxe":
        raise ContractFailure(f"slot0={s0!r}")
    if s0.get("damage") != 123 or s0.get("maxDamage") != 1561 or s0.get("durability") != 1438:
        raise ContractFailure(
            f"#42 wear fields drifted: damage={s0.get('damage')!r} "
            f"maxDamage={s0.get('maxDamage')!r} durability={s0.get('durability')!r}")


def check_player_absent_pin(ctx):
    # documented semantics: empty PlayerList => {present:false}, never a throw
    r = ctx.call("mc.observe.player")
    if r.get("present") is not False:
        raise ContractFailure(f"expected present:false on empty dedicated server, got {r!r}")


def check_entity_query(ctx):
    _cmd(ctx, "time set midnight")  # keep the zombie from burning at y=200 open sky
    _cmd(ctx, "summon minecraft:zombie 30.5 200.0 30.5 {NoAI:1b,PersistenceRequired:1b}")
    rows = ctx.call("mc.query", {"q": "entities", "center": {"x": 30, "y": 200, "z": 30},
                                 "filter": {"in_radius": 4, "type": "zombie"}})
    if len(rows) != 1:
        raise ContractFailure(f"expected exactly 1 zombie, got {len(rows)}: {rows!r}")
    if not isinstance(rows[0].get("health"), (int, float)) or rows[0]["health"] <= 0:
        raise ContractFailure(f"living row lacks health: {rows[0]!r}")
    _cmd(ctx, "kill @e[type=zombie]")


def check_command_result_event(ctx):
    cur = ctx.call("mc.observe.cursor")["cursor"]
    _cmd(ctx, "setblock 40 200 40 minecraft:iron_block")
    evs = ctx.call("mc.observe.eventsSince", {"cursor": cur, "types": ["command.result"]})
    hits = [e for e in evs.get("events", evs if isinstance(evs, list) else [])
            if "iron_block" in json.dumps(e)]
    if not hits:
        raise ContractFailure("no command.result event for a dispatched command")
    data = hits[0].get("data")
    payload = json.loads(data) if isinstance(data, str) else data
    if payload.get("success") is not True:
        raise ContractFailure(f"success flag wrong on a succeeding command: {payload!r}")


def check_cursor_monotonic(ctx):
    c1 = ctx.call("mc.observe.cursor")["cursor"]
    _cmd(ctx, "setblock 42 200 42 minecraft:copper_block")
    c2 = ctx.call("mc.observe.cursor")["cursor"]
    if not (isinstance(c1, int) and isinstance(c2, int) and c2 > c1):
        raise ContractFailure(f"cursor not monotonic: {c1!r} -> {c2!r}")


def check_wait_ticks(ctx):
    t0 = time.time()
    r = ctx.call("mc.system.waitTicks", {"ticks": 10})
    ms = (time.time() - t0) * 1000
    if r.get("waited") is not True and r.get("waited") != 10:
        raise ContractFailure(f"waitTicks answer drifted: {r!r}")
    if ms < 300:  # 10 ticks nominal 500ms; <300ms means it did not actually wait
        raise ContractFailure(f"waitTicks returned too fast ({ms:.0f}ms) — did not block on ticks")


def check_wait_condition_value(ctx):
    _cmd(ctx, "setblock 50 200 50 minecraft:chest")
    _cmd(ctx, "item replace block 50 200 50 container.2 with minecraft:stone 7")
    r = ctx.call("mc.wait.condition", {
        "invoke": "mc.observe.container", "params": {"pos": {"x": 50, "y": 200, "z": 50}},
        "field": "slots.2.count", "value": 7, "timeoutMs": 5000, "pollMs": 200})
    if not r.get("satisfied") or r.get("value") != 7:
        raise ContractFailure(f"wait.condition value semantics broken: {r!r}")
```

CHECKS 追加（金丝雀之前）：

```python
    ("world.setblockQueryReadback", "NONE", check_setblock_query_readback),
    ("world.fillCount", "NONE", check_fill_count),
    ("world.snapshotRestore", "NONE", check_snapshot_restore),
    ("obs.containerDurability", "NONE", check_container_durability),
    ("obs.playerAbsentPin", "NONE", check_player_absent_pin),
    ("obs.entityQuery", "NONE", check_entity_query),
    ("events.commandResult", "NONE", check_command_result_event),
    ("events.cursorMonotonic", "NONE", check_cursor_monotonic),
    ("wait.ticks", "NONE", check_wait_ticks),
    ("wait.conditionValue", "NONE", check_wait_condition_value),
```

⚠️ 与 Task 3 同规则：若某方法实际返回形状与断言不符（如 eventsSince 包壳字段名、cursor 返回形状、waitTicks 返回键、`/item` 组件语法在 1.21.1 的确切拼写），用 `ctx.call_raw` 实测修正断言，**收紧到真实形状**，commit message 注明。`item replace ... [minecraft:damage=123]` 若语法报错，先在 gradle 控制台或经 `mc.script.eval` 验证 1.21.1 组件语法再修。

- [ ] **Step 2: 真跑验证**

Run: `python3 scripts/stagewright/instrument.py --loader neoforge --wall 300; echo "exit=$?"`
Expected: `VERDICT: GREEN`，exit=0，17 条真检查全 PASS + 双金丝雀正确。

- [ ] **Step 3: Commit**

```bash
git add scripts/stagewright/instrument.py
git commit -m "test(testkit): instrument contract batch B — world ops, observation fidelity (#42 wear), events, wait"
```

---

### Task 5: 双 loader 验收 + 门自证 + 文档

**Files:**
- Create: `docs/stagewright/instrument-contract-v0.md`
- Modify: `stagewright/README.md`、`TODO.md`、`AGENTS.md`（Log locations 表加一行 `stagewright 无关：<loader>/run-contract/`——注意本套件的 runDir 在 worlddriver 模块下）

**Interfaces:**
- Consumes: Task 1-4 全部。
- Produces: P1b 收官实证记录；契约文档 v0。

- [ ] **Step 1: fabric 侧真跑**

Run: `python3 scripts/stagewright/instrument.py --loader fabric --wall 300; echo "exit=$?"`
Expected: `VERDICT: GREEN`，exit=0。首启会拉 loom 配置。**必查** `cat fabric/run-contract/worlddriver-rpc.port` ≠ 39801（Task 2 Step 2 的 configureEach 风险在此兑现；若撞车按该步修法处理并单列 commit）。

- [ ] **Step 2: 门自证（一次性,不留 commit）**

临时把 `check_version_shape` 的断言改错（`!= 'worlddriver'` 改 `!= 'nonsense'`）→ 跑 neoforge → Expected: `VERDICT: RED` exit=1；再临时把 `canary_must_fail` 改为 `return None` → Expected: `VERDICT: DEAD` exit=2。两次都 `git checkout -- scripts/stagewright/instrument.py` 还原后重跑 GREEN。把三次输出摘要记入报告（这是金丝雀条款的活体验收，等价 P1a 的门自证）。

- [ ] **Step 3: 双 loader 复跑（确定性证据）**

Run: `python3 scripts/stagewright/instrument.py --loader neoforge --wall 300 && python3 scripts/stagewright/instrument.py --loader fabric --wall 300; echo "exit=$?"`
Expected: 两轮 GREEN，exit=0。

- [ ] **Step 4: 写 instrument-contract-v0.md**

```markdown
# 仪表契约 v0（T0 依赖面子集）

运行器：`python3 scripts/stagewright/instrument.py --loader {neoforge|fabric}`。
裸 RPC 直打 route()，跑在 worlddriver 裸专服（`runContractServer`，runDir
`<loader>/run-contract/`，RPC 端口 ephemeral 经 `worlddriver-rpc.port` 发现）。
退出码同编排契约 v0：0 GREEN / 1 RED / 2 DEAD（金丝雀误判）/ 3 ENV。
信任链（spec §4）：本套件绿 → testkit setup/断言可信 → 行为面测试可信。

## 检查清单（17 + 2 金丝雀）
（Task 3/4 的检查名逐条列出，一行一条，注明各自断言什么与钉住哪条病历）

## 永久断言台账
- #42 工具耐久可见性 → `obs.containerDurability`（damage/maxDamage/durability 三字段）。
- #280 病族（静默吞）→ `route.clientOnlyVerb`（client-only verb 必须大声失败）
  + `route.invalidParams.unknownKey`（封闭 schema 拒未知键）。

## 已知缺口（未来断言，P2）
- #41 全背包 36 槽、#45 攻击冷却、#55 伤害源：需要 PlayerList 内的真玩家（T1/T2）。
- `/agentserver` FakePlayer 对 `mc.observe.player` 不可见（不入 PlayerList）——
  avatar 可观察性接线后补断言。
- `mc.bot.setting` 未知键静默吞（applied/rejected 均不出现）——P2 修复+断言。
```

（实写时把 Task 3/4 的 17 条名字全部列出——不许省略号。）

- [ ] **Step 5: README/TODO/AGENTS.md**

`stagewright/README.md` 在 T0 节后加一节：

```markdown
## Instrument contract (trust chain)

    python3 scripts/stagewright/instrument.py --loader neoforge   # or fabric

Bare-RPC contract checks against a plain worlddriver dedicated server —
the instrument face testkit itself depends on (spec §4). Green here is the
precondition for trusting any scene's setup/assertions. Contract:
`../docs/stagewright/instrument-contract-v0.md`.
```

`TODO.md` 头部按现有条目风格加 P1b 条目（含 commit hashes、双 loader GREEN、门自证三跑、顺延清单指向 instrument-contract-v0.md）。`AGENTS.md` Log locations 表加行：`| Instrument contract server run | `<loader>/run-contract/` |`。

- [ ] **Step 6: Commit**

```bash
git add docs/stagewright/instrument-contract-v0.md stagewright/README.md TODO.md AGENTS.md
git commit -m "docs(testkit): instrument contract v0 — check registry, permanent assertions, known gaps; P1b entry"
```

---

## Self-Review（计划自检记录）

1. **Spec 覆盖（P1b 切片）**：仪表契约子集（§4.2 裸 RPC/独立断言栈/双源对账/病历沉淀）→ Task 2-4；信任链闭合先于 dogfood（§7 P1）→ 整体交付；金丝雀条款（§5）→ Task 2 骨架内建 + Task 5 门自证；双 loader 验收（§7）→ Task 5。§6 的 schema SPI/`mc.test.*` verbs 是 P2（头部顺延声明），`common/test` 迁出是 P1c/P2 素材。
2. **占位符扫描**：全部检查给出完整函数体与精确断言值（diamond_pickaxe maxDamage=1561 系 javap 级常识值，实测若异按 Task 4 修正规则收紧）；无 TBD。唯一开放点是 1.21.1 命令组件语法与个别返回包壳字段名，已写成显式的「call_raw 实测→收紧断言」规则而非留白。
3. **类型一致性**：`CHECKS` 三元组约定 Task 2 定义、Task 3/4 沿用；`ctx.call/call_raw` 签名一致；`verdict.judge(records, record_type)` Task 1 定义、Task 2 骨架与 self-test 传 `"check"`；JSONL 记录 `type:"check"` 与 done footer `scenes` 字段沿用 v0 形状（scenes 名字泛化不值当，契约 v0 冻结优先）。
4. **风险入案**：fabric configureEach 端口覆盖（Task 2 Step 2 显式验证+修法）；错误子串断言漂移（两批的 call_raw 实测规则）；y=200 高空僵尸白天自燃（time set midnight 内嵌于检查）；`stop` 后 gradle 不返回（沿用 t0 经验，判决不看 gradle 退出码，sweep 兜底）；检查间状态隔离（独立坐标+独立方块种类）。

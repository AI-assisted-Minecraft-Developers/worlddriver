# Testkit P0 — GameTest 套件完整性止血 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让现有 GameTest 套件的回归门恢复诚实——注册却未执行的测试（task#85 静默吞）、TOTAL 假绿、僵尸 JVM/污染世界，全部在门上变成显式失败。

**Architecture:** 游戏内单点插桩写 JSONL 执行清单（注册侧 = GameTestRegistry 全量 dump，执行侧 = 每个测试体第一行都会调的 `gtOnlySkips()` 单一咽喉）；进程外 Python 对账脚本判定「注册数=执行数 + BUILD 状态 + vanilla required 行」三合一裁决；bash 包装脚本统一「杀残留 JVM→删世界→跑→对账」。不新增 RPC verb、不动 AgentApi（P0 是测试脚手架，不是驱动层面）。

**Tech Stack:** Java 21（NeoForge 21.1.230 / MC 1.21.1 mojmap）、Python 3（标准库）、bash。

**Spec:** `docs/superpowers/specs/2026-07-16-mc-testkit-design.md` §7 P0。
**Spec 偏差声明**：spec 写「ENTER/EXIT JSONL」；本计划只做 ENTER——吞测试检测只需 ENTER（注册但零 enter = 被吞），而 EXIT 没有单点咽喉（测试体经 succeed()/超时/异常多路终止），加 EXIT 要改 130 处，违反 DRY/YAGNI。spec 意图（对账门捕获静默吞）完整保留。

## Global Constraints

- MC 1.21.1 / NeoForge 21.1.230 / JDK 21 / mojmap；gametest 只在 neoforge loader。
- ⛔ 禁 `pkill`（user 硬规则）——杀进程一律 `ps` 列候选 → 显式 PID `kill`。
- 运行时输出不进 git（AGENTS 规则 5）：日志写 `../neoforge-gametest-run.log`（仓库外，沿用现约定）；`testkit-manifest.jsonl` 落在 `neoforge/run-gametest/`（已被忽略的 run 目录）。
- 无命名冲突不用 FQN（AGENTS 规则 7）。
- GameTest 通过判据 = `BUILD SUCCESSFUL` + 无 `required tests failed` + 对账绿；**永不信 `TOTAL:` 行**。
- gametest JVM 的真实命令行标记是 `-Dneoforge.gameTestServer=true`（`pkill -f GameTestServer` 抓不到的历史教训）；grep 模式用 `[n]eoforge.gameTestServer`。
- 任何 gametest run 被 kill 后，下一次 run 前必须删 `neoforge/run-gametest/world`（包装脚本固化此规矩）。

---

### Task 1: GameTestManifest — 游戏内 JSONL 执行清单

**Files:**
- Create: `neoforge/src/main/java/net/magicterra/agent/neoforge/GameTestManifest.java`
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestSupport.java:74-81`（gtOnlySkips 首行插桩）
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentDriverNeoForge.java:53-55`（GameTestServer 分支调 reset()）

**Interfaces:**
- Consumes: `GameTestRegistry.getAllTestFunctions()` → `Collection<TestFunction>`；`TestFunction.testName()/batchName()/required()`（mojmap 1.21.1，已 javap 核实）。
- Produces: `neoforge/run-gametest/testkit-manifest.jsonl`，两种记录（Task 2 的解析契约）：
  - `{"type":"registered","name":"<testName>","batch":"<batchName>","required":true|false}` — 每个注册测试一行，server starting 时 dump。
  - `{"type":"enter","name":"<guard名>"}` — 测试体被调用时追加（guard 名是手写混合大小写；对账侧统一 lowercase 匹配 vanilla 的小写 testName）。

- [ ] **Step 1: 写 GameTestManifest.java**

```java
package net.magicterra.agent.neoforge;

import net.magicterra.agent.AgentDriverCommon;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;

/**
 * Suite-integrity manifest (task#85 stopgap): one JSONL file per GameTest run,
 * written into the GameTestServer working directory (neoforge/run-gametest/).
 *
 * Records:
 *   {"type":"registered","name":..,"batch":..,"required":..}
 *     — every TestFunction in GameTestRegistry, dumped once at server starting.
 *   {"type":"enter","name":..}
 *     — appended by AgentGameTestSupport.gtOnlySkips(), the first line of every
 *       test body. A registered test with no enter record was silently swallowed
 *       by the vanilla scheduler (#85); scripts/gt_reconcile.py turns that into
 *       a hard failure.
 *
 * Name matching is case-insensitive downstream: vanilla testName() is the
 * lowercased method name, guard strings are hand-written mixed case.
 * Test names are Java identifiers — no JSON escaping needed.
 */
final class GameTestManifest {
    private static final Path FILE = Path.of("testkit-manifest.jsonl");
    private static final Object LOCK = new Object();
    private static volatile boolean armed = false;

    private GameTestManifest() {}

    /** Truncate the manifest and dump every registered test. GameTestServer runs only —
     *  live/integrated servers never call this, so enter() stays a no-op there. */
    static void reset() {
        synchronized (LOCK) {
            try {
                Collection<TestFunction> all = GameTestRegistry.getAllTestFunctions();
                StringBuilder sb = new StringBuilder();
                for (TestFunction fn : all) {
                    sb.append("{\"type\":\"registered\",\"name\":\"").append(fn.testName())
                      .append("\",\"batch\":\"").append(fn.batchName())
                      .append("\",\"required\":").append(fn.required()).append("}\n");
                }
                Files.writeString(FILE, sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                armed = true;
                AgentDriverCommon.LOG.info("[{}] gametest manifest armed: {} registered tests -> {}",
                        AgentDriverCommon.MOD_ID, all.size(), FILE.toAbsolutePath());
            } catch (IOException e) {
                // A broken manifest must never read as green — fail the run loudly.
                throw new UncheckedIOException("cannot write gametest manifest", e);
            }
        }
    }

    /** Append an enter record. No-op unless reset() armed this run. */
    static void enter(String name) {
        if (!armed) return;
        synchronized (LOCK) {
            try {
                Files.writeString(FILE, "{\"type\":\"enter\",\"name\":\"" + name + "\"}\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot append gametest manifest", e);
            }
        }
    }
}
```

- [ ] **Step 2: gtOnlySkips 首行插桩**

`AgentGameTestSupport.java:74`，方法体第一行加一句（javadoc 不动）：

```java
    static boolean gtOnlySkips(String name) {
        GameTestManifest.enter(name);
        String only = System.getenv("AGENT_GT_ONLY");
        if (only == null) return false;
        for (String want : only.split(",")) {
            if (want.trim().equalsIgnoreCase(name)) return false;
        }
        return true;
    }
```

（`gtSkip` 内部调 `gtOnlySkips`，自动覆盖；被 AGENT_GT_ONLY 过滤而早退的测试也记 enter——语义就是「测试体被调用过」，#85 被吞者连体都不进。）

- [ ] **Step 3: GameTestServer 启动分支接 reset()**

`AgentDriverNeoForge.java:53-55` 的 instanceof 分支内加一行：

```java
        if (event.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) {
            net.magicterra.agent.bot.BotConfig.applyGameTestBaseline();
            GameTestManifest.reset();
        }
```

- [ ] **Step 4: 编译**

Run: `./gradlew :neoforge:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 单测烟囱验证（过滤跑一个便宜测试）**

Run: `rm -rf neoforge/run-gametest/world && AGENT_GT_ONLY=valuablePlacementBlockMatrix timeout 900 ./gradlew :neoforge:runGameTestServer 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL；然后检查清单：

Run: `grep -c '"type":"registered"' neoforge/run-gametest/testkit-manifest.jsonl && grep -c '"type":"enter"' neoforge/run-gametest/testkit-manifest.jsonl && grep '"type":"enter"' neoforge/run-gametest/testkit-manifest.jsonl | grep -i valuable`
Expected: registered ≈130（=注册总数）；enter 若干（≤registered——**缺口就是被吞名单，属预期数据，不是本 Task 失败**）；valuablePlacementBlockMatrix 的 enter 行存在。

- [ ] **Step 6: Commit**

```bash
git add neoforge/src/main/java/net/magicterra/agent/neoforge/GameTestManifest.java \
        neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestSupport.java \
        neoforge/src/main/java/net/magicterra/agent/neoforge/AgentDriverNeoForge.java
git commit -m "test(#85): in-game JSONL manifest — registered dump + enter probe at the gtOnlySkips chokepoint"
```

---

### Task 2: gt_reconcile.py — 对账脚本（自带 self-test）

**Files:**
- Create: `scripts/gt_reconcile.py`

**Interfaces:**
- Consumes: Task 1 的 JSONL 两种记录；gradle 控制台日志文本（`BUILD SUCCESSFUL/FAILED`、`N required tests failed`、`All N required tests passed` 行）。
- Produces: CLI `python3 scripts/gt_reconcile.py --manifest <path> --log <path>`，exit 0=绿 / 1=红；`--self-test` 跑内嵌 fixture。Task 4 的包装脚本按此调用。

- [ ] **Step 1: 先写含 self-test 的完整脚本（fixture 即失败测试——先运行确认「未实现时 self-test 红」不适用于单文件脚本，改为：写完立刻跑 self-test，fixture 覆盖四个判定路径）**

```python
#!/usr/bin/env python3
"""GameTest suite-integrity reconciler (task#85 P0).

Verdict = ALL of:
  1. gradle log has BUILD SUCCESSFUL;
  2. gradle log has no "required tests failed";
  3. every registered test (any batch) has an enter record  -> else SWALLOWED;
  4. every enter record maps to a registered test           -> else DRIFTED (guard-string typo).
Matching is case-insensitive (vanilla testName() is lowercase, guard strings are mixed case).
NEVER consults the mod reporter's "TOTAL:" line (it can read all-green while required tests fail).
"""
import argparse
import json
import sys


def parse_manifest(text):
    registered, entered = {}, set()
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        name = rec["name"].lower()
        if rec["type"] == "registered":
            registered[name] = {"batch": rec["batch"], "required": rec["required"]}
        elif rec["type"] == "enter":
            entered.add(name)
    return registered, entered


def parse_log(text):
    return {
        "build_success": "BUILD SUCCESSFUL" in text,
        "required_failed": "required tests failed" in text,
    }


def reconcile(registered, entered, log_facts):
    swallowed = sorted(n for n in registered if n not in entered)
    drifted = sorted(n for n in entered if n not in registered)
    ok = (log_facts["build_success"] and not log_facts["required_failed"]
          and not swallowed and not drifted and bool(registered))
    return {"ok": ok, "swallowed": swallowed, "drifted": drifted,
            "registered": len(registered), "entered": len(entered), **log_facts}


def report(r):
    print(f"[gt_reconcile] registered={r['registered']} entered={r['entered']} "
          f"build_success={r['build_success']} required_failed={r['required_failed']}")
    if r["swallowed"]:
        print(f"[gt_reconcile] SWALLOWED ({len(r['swallowed'])}) — registered but body never ran (#85):")
        for n in r["swallowed"]:
            print(f"  - {n}")
    if r["drifted"]:
        print(f"[gt_reconcile] DRIFTED ({len(r['drifted'])}) — enter name matches no registered test "
              f"(guard-string typo):")
        for n in r["drifted"]:
            print(f"  - {n}")
    print(f"[gt_reconcile] VERDICT: {'GREEN' if r['ok'] else 'RED'}")
    return 0 if r["ok"] else 1


FIXTURE_MANIFEST_GREEN = (
    '{"type":"registered","name":"alphaarena","batch":"defaultBatch","required":true}\n'
    '{"type":"registered","name":"betaarena","batch":"defaultBatch","required":false}\n'
    '{"type":"enter","name":"alphaArena"}\n'
    '{"type":"enter","name":"betaArena"}\n'
)
FIXTURE_MANIFEST_SWALLOWED = (
    '{"type":"registered","name":"alphaarena","batch":"defaultBatch","required":true}\n'
    '{"type":"registered","name":"betaarena","batch":"defaultBatch","required":true}\n'
    '{"type":"enter","name":"alphaArena"}\n'
)
FIXTURE_MANIFEST_DRIFTED = (
    '{"type":"registered","name":"alphaarena","batch":"defaultBatch","required":true}\n'
    '{"type":"enter","name":"alphaArena"}\n'
    '{"type":"enter","name":"alphaArena2"}\n'
)
FIXTURE_LOG_GREEN = "irrelevant\nAll 2 required tests passed :)\nBUILD SUCCESSFUL in 1m\n"
FIXTURE_LOG_REQFAIL = "TOTAL: 2   PASS: 2   FAIL: 0\n1 required tests failed :(\nBUILD FAILED in 1m\n"


def self_test():
    checks = []
    r = reconcile(*parse_manifest(FIXTURE_MANIFEST_GREEN), parse_log(FIXTURE_LOG_GREEN))
    checks.append(("green run passes", r["ok"]))
    r = reconcile(*parse_manifest(FIXTURE_MANIFEST_SWALLOWED), parse_log(FIXTURE_LOG_GREEN))
    checks.append(("swallowed test fails the run", not r["ok"] and r["swallowed"] == ["betaarena"]))
    r = reconcile(*parse_manifest(FIXTURE_MANIFEST_DRIFTED), parse_log(FIXTURE_LOG_GREEN))
    checks.append(("drifted guard fails the run", not r["ok"] and r["drifted"] == ["alphaarena2"]))
    r = reconcile(*parse_manifest(FIXTURE_MANIFEST_GREEN), parse_log(FIXTURE_LOG_REQFAIL))
    checks.append(("required-failed beats green TOTAL line", not r["ok"]))
    r = reconcile(*parse_manifest(""), parse_log(FIXTURE_LOG_GREEN))
    checks.append(("empty manifest fails (manifest never armed)", not r["ok"]))
    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    return 0 if not failed else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest")
    ap.add_argument("--log")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        sys.exit(self_test())
    if not args.manifest or not args.log:
        ap.error("--manifest and --log are required (or use --self-test)")
    try:
        manifest_text = open(args.manifest, encoding="utf-8").read()
    except FileNotFoundError:
        print(f"[gt_reconcile] manifest missing: {args.manifest} — manifest never armed => RED")
        sys.exit(1)
    log_text = open(args.log, encoding="utf-8", errors="replace").read()
    r = reconcile(*parse_manifest(manifest_text), parse_log(log_text))
    sys.exit(report(r))


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 跑 self-test**

Run: `python3 scripts/gt_reconcile.py --self-test`
Expected: 5 行全 PASS，exit 0（`echo $?` 验证）。

- [ ] **Step 3: 用 Task 1 烟囱验证留下的真实产物跑一次**

Run: `python3 scripts/gt_reconcile.py --manifest neoforge/run-gametest/testkit-manifest.jsonl --log ../neoforge-gametest-run.log 2>/dev/null || true`（若上一步日志文件不存在，用 Task 1 Step 5 的输出重定向重跑一次生成）
Expected: 打印 registered/entered 计数与 SWALLOWED 名单（**非空是预期**——#85 已知被吞者会出现），VERDICT 按实际。此步只验证脚本吃真数据不崩。

- [ ] **Step 4: Commit**

```bash
git add scripts/gt_reconcile.py
git commit -m "test(#85): reconciler — registered-vs-entered gate + BUILD/required verdict, embedded self-test"
```

---

### Task 3: 源码审计模式 — 揪出没有 guard 或名字漂移的测试

**Files:**
- Modify: `scripts/gt_reconcile.py`（加 `--audit-source` 模式）
- Modify: 审计揪出的 `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTest*.java`（若有）

**Interfaces:**
- Consumes: neoforge 测试源文件文本。
- Produces: `python3 scripts/gt_reconcile.py --audit-source neoforge/src/main/java/net/magicterra/agent/neoforge` exit 0/1；保证「每个 @GameTest 方法体内都有与方法名一致的 guard 调用」——这是 Task 1 enter 探针全覆盖的前提。

- [ ] **Step 1: 在 gt_reconcile.py 尾部（main 之前）加审计函数，并在 main 里接 `--audit-source`**

```python
import os
import re

GAMETEST_RX = re.compile(r"@GameTest\b[^)]*\)?\s*(?:@\w+[^\n]*\n\s*)*public\s+static\s+void\s+(\w+)\s*\(")
GUARD_RX = re.compile(r"gtOnlySkips\(\s*\"([^\"]+)\"\s*\)|gtSkip\(\s*\w+\s*,\s*\"([^\"]+)\"\s*\)")


def audit_source(src_dir):
    problems = []
    for fname in sorted(os.listdir(src_dir)):
        if not (fname.startswith("AgentGameTest") and fname.endswith(".java")):
            continue
        if fname == "AgentGameTestSupport.java":
            continue
        text = open(os.path.join(src_dir, fname), encoding="utf-8").read()
        methods = GAMETEST_RX.findall(text)
        guards = {(a or b).lower() for a, b in GUARD_RX.findall(text)}
        for m in methods:
            if m.lower() not in guards:
                problems.append(f"{fname}: @GameTest {m} has no matching gtOnlySkips/gtSkip "
                                f"guard — enter probe blind for it")
    for p in problems:
        print(f"  [AUDIT] {p}")
    print(f"[gt_reconcile] source audit: {'CLEAN' if not problems else str(len(problems)) + ' problem(s)'}")
    return 0 if not problems else 1
```

main() 里 `--self-test` 分支后加：

```python
    ap.add_argument("--audit-source")
```

（与其它 add_argument 放一起），并在 self-test 分支后：

```python
    if args.audit_source:
        sys.exit(audit_source(args.audit_source))
```

- [ ] **Step 2: 跑审计**

Run: `python3 scripts/gt_reconcile.py --audit-source neoforge/src/main/java/net/magicterra/agent/neoforge`
Expected: 列出无 guard 的 @GameTest 方法（可能为 0——~130 处 guard 是普查过的模式，但 `AgentGameTest.agentRpcSmoke` 等 batch 特例待证）。

- [ ] **Step 3: 修复揪出的每一个**

对每个报告的方法，在其方法体第一行插入（`NAME` 换成确切方法名；该类若未 `import static ...AgentGameTestSupport.gtSkip;` 则按兄弟类的既有 import static 模式补）：

```java
        if (gtSkip(helper, "NAME")) return;
```

注意：方法若无 `GameTestHelper helper` 参数（不太可能，@GameTest 签名固定），改用 `if (gtOnlySkips("NAME")) return;` 且该测试自行 succeed 的语义要人工确认——这类特例逐个看方法体后处理，处理原则=guard 语义与兄弟测试一致。

- [ ] **Step 4: 复跑审计 + 编译**

Run: `python3 scripts/gt_reconcile.py --audit-source neoforge/src/main/java/net/magicterra/agent/neoforge && ./gradlew :neoforge:compileJava -q`
Expected: `source audit: CLEAN` + BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add scripts/gt_reconcile.py neoforge/src/main/java/net/magicterra/agent/neoforge/
git commit -m "test(#85): source audit mode — every @GameTest must carry the guard/probe; fix stragglers"
```

---

### Task 4: run_gametests.sh — 统一包装脚本

**Files:**
- Create: `scripts/run_gametests.sh`（chmod +x）

**Interfaces:**
- Consumes: Task 2 的 CLI 契约。
- Produces: 从此 `scripts/run_gametests.sh` 是跑套件的唯一正门（TODO/AGENTS 的验收流程指向它）；env 开关 `GT_TIMEOUT`（默认 3600s）、`GT_LOG`（默认 `../neoforge-gametest-run.log`）；`AGENT_GT_ONLY` 照常透传。

- [ ] **Step 1: 写脚本**

```bash
#!/usr/bin/env bash
# Canonical GameTest entrypoint (task#85 P0). Guarantees, in order:
#   1. no leftover gametest JVM holds world/session.lock (kill by explicit PID — pkill is banned);
#   2. the persistent run-gametest world is deleted (killed-run pollution => processUnloads
#      single-tick livelock that GameTest timeouts cannot catch);
#   3. hard wall-clock cap (same livelock class never self-terminates);
#   4. verdict = BUILD status + vanilla required line + manifest reconciliation.
#      The mod reporter's "TOTAL:" line is never consulted.
set -u
cd "$(dirname "$0")/.."

LOG="${GT_LOG:-../neoforge-gametest-run.log}"
MANIFEST=neoforge/run-gametest/testkit-manifest.jsonl

sweep_jvms() {
  for pid in $(ps -eo pid,args | grep "[n]eoforge.gameTestServer" | awk '{print $1}'); do
    echo "[run_gametests] killing leftover gametest JVM pid=$pid"
    kill -9 "$pid"
  done
}

sweep_jvms
rm -rf neoforge/run-gametest/world
rm -f "$MANIFEST"

timeout "${GT_TIMEOUT:-3600}" ./gradlew :neoforge:runGameTestServer 2>&1 | tee "$LOG"
GRADLE_RC=${PIPESTATUS[0]}
if [ "$GRADLE_RC" -eq 124 ]; then
  echo "[run_gametests] WALL-CLOCK TIMEOUT after ${GT_TIMEOUT:-3600}s"
  # timeout killed the gradle wrapper; the game JVM is a child of the gradle
  # DAEMON and survives — sweep again so it cannot poison the next run.
  sweep_jvms
fi

python3 scripts/gt_reconcile.py --manifest "$MANIFEST" --log "$LOG"
RECON_RC=$?

echo "[run_gametests] gradle_rc=$GRADLE_RC reconcile_rc=$RECON_RC"
[ "$GRADLE_RC" -eq 0 ] && [ "$RECON_RC" -eq 0 ]
```

- [ ] **Step 2: chmod + 快速过滤跑验证机制**

Run: `chmod +x scripts/run_gametests.sh && AGENT_GT_ONLY=valuablePlacementBlockMatrix GT_TIMEOUT=900 scripts/run_gametests.sh; echo "exit=$?"`
Expected: 全流程跑通（sweep→删世界→gradle→对账）；**exit 码如实反映对账**——若 SWALLOWED 名单非空则 exit=1 且名单打印，这正是门在工作，不算本 Task 失败；核对打印的 `gradle_rc`/`reconcile_rc` 与日志一致。

- [ ] **Step 3: Commit**

```bash
git add scripts/run_gametests.sh
git commit -m "test(#85): canonical suite entrypoint — PID sweep, world clean, wall cap, reconcile verdict"
```

---

### Task 5: 全量基线 — 门的实证 + 被吞名单重建可信基线

**Files:**
- Modify: `TODO.md`（task#85 条目补 P0 落地记录与基线数据）

**Interfaces:**
- Consumes: Task 4 入口。
- Produces: ①被吞名单（对账门第一次全量实证，预期含 ascendmovementnoop / ascenddeadzonewatchdog / diagonalascentspeed）；②被吞者经 AGENT_GT_ONLY 显式执行的真实 GREEN/RED 基线；③TODO.md 记录。此基线是 P1 dogfood 迁移的输入（被吞名单优先迁移）。

- [ ] **Step 1: 全量跑**

Run: `scripts/run_gametests.sh; echo "exit=$?"`
Expected: 跑完（若 underwaterBase 型挂死触发 wall cap，脚本自会清扫——重跑一次；连续两次挂死则按既有处置先排世界变量）。对账输出 SWALLOWED 名单；**验证名单包含已知三个被吞者**（门抓住 #85 = P0 的核心验收）。若名单为空——意味着 #85 在当前 HEAD 不复现，同样记录（不算失败，门依然武装）。

- [ ] **Step 2: 被吞名单显式基线**

把 Step 1 的 SWALLOWED 名单逗号拼接（示例，按实际名单替换）：

Run: `AGENT_GT_ONLY=ascendMovementNoop,ascendDeadZoneWatchdog,diagonalAscentSpeed GT_TIMEOUT=1800 scripts/run_gametests.sh; echo "exit=$?"`
Expected: 名单内测试真实执行（enter 记录齐全、对账对 SWALLOWED 的报告只含未点名者——过滤跑下其余测试也 enter（早退），故对账应绿）；记下每个被吞者的真实 PASS/FAIL。

- [ ] **Step 3: 记录进 TODO.md**

在 `TODO.md` 的 task#85 相关条目（文件头部 2026-07-16 区域）追加一段，格式沿用现有条目风格，内容必须包含：P0 四件套已合（manifest/reconciler/audit/entrypoint + commit hashes）、全量 SWALLOWED 名单原文、显式基线的真实结果、以及「从此验收只走 scripts/run_gametests.sh」。

- [ ] **Step 4: Commit**

```bash
git add TODO.md
git commit -m "docs(#85): P0 landed — swallowed-list baseline recorded; run_gametests.sh is the only gate"
```

---

## Self-Review（计划自检记录）

1. **Spec 覆盖**：spec §7 P0 三件（执行清单/对账门/统一包装）→ Task 1/2/4；「被吞名单显式跑一轮重建基线」→ Task 5；guard 全覆盖前提 → Task 3。EXIT 偏差已在头部声明。
2. **占位符扫描**：全部代码为完整可用文本；Task 3 Step 3 的"逐个看方法体"是审计类任务的固有人工判断点，处理原则已给（与兄弟测试语义一致）。
3. **类型/名字一致性**：`GameTestManifest.enter/reset`、JSONL 两 record、`gt_reconcile.py` CLI 三模式（--manifest/--log、--self-test、--audit-source）、`run_gametests.sh` env 名，跨 Task 引用已核对一致。API 签名（getAllTestFunctions/testName/batchName/required）经 javap 对 mojmap 1.21.1 实证。

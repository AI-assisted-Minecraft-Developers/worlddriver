# mc-testkit P1.5b：彩票家族 driver 三员迁移 + 期望清单化 + task#86 签名门 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把彩票家族 driver 形态三员（gearScope、buriedOre、entityLeash）迁成 testkit 场景（建立 ServerAgentDriver/Process 类 arena 的移植模式），期望门从手打名单升级为 checked-in 清单（--expect-file），task#86 传感器升级为 golden-failure 签名门。

**Architecture:** 三场景走既有 SceneProvider/dogfood 通路，身体一律 `ServerAgentDriver.createIsolated`（#48 修复模式的 driver 版），manager 生命周期进 `ctx.cleanup`（targeted unregister + fp discard）；entityLeash 的手动 `level.tick(()->true)` 直译移植（场景在 ServerTickEvent.Post 执行，重入位置比 legacy GameTest 更安全），失败再降级为 await 步；--expect-file 读 `scripts/testkit/expected-scenes-neoforge.txt`（与 --expect-scene 并集）；ad.selfShaftDigUp 从 optional 翻回 required，断言改钉已知失败签名（bug 精确复现=PASS，修好或漂移=RED 大声报）。

**Tech Stack:** 沿用 P1c/P1.5a 全部基建；Java 21 + Python 3 stdlib。

## Global Constraints

- 依赖方向 agent-driver → testkit 不逆转；mc-testkit/* 零 agent-driver 引用。
- 移植保真：断言数值/循环上限/两阶段结构原值；同步循环原样进 body。唯一 sanctioned 身体偏差：`ServerAgentDriver.create` → `createIsolated`（唯一身体，同 P1c create→createUnique 先例，javadoc 注明）。
- **solo 定性必须真单名**（P1.5a 实证：多名 AGENT_GT_ONLY 非完全隔离）——所有 A/B 基线一律 `AGENT_GT_ONLY=<单个名字>`。
- 若 legacy 单名 solo RED（Task 3 selfShaftDigUp 先例）：先证移植保真（指标同值），STOP 报 BLOCKED 交裁决，不许自行降 optional、不许调松断言。
- 每场景 `BotConfig.pinnedBaseline()` 先注册 + `ctx.cleanup` 里 targeted `ServerAgentManager.unregister(driver)` + fp discard（LIFO：unregister/discard 先跑、pin 最后关）；`clear()` 只许作兜底且注明理由。
- optional 治理规则（README 已立）：任何 withRequired(false) 必须 javadoc 引 task 编号。
- 运行纪律：前台 Bash timeout；禁 pkill；子代理绝不带后台任务结束回合（有界前台轮询收割）；legacy 全量 RED 按 P0 协议单名 solo 定性、不 fishing。

## 已声明偏差 / 顺延清单（评审勿标缺）

1. fabric 全量对齐仍单列；legacy 删除仍不执行（期望门已武装，倒数继续等三连绿）。
2. task#86 本体（walker backslide 修复）不在本计划——本计划只把它的传感器升级为签名门。
3. P1.5a 终审遗留 Minor 顺手收进 Task 1/2：parse docstring 绝对化表述加限定句、槽位下限守卫；prefix-convention porting-map 注记进 Task 3 的 porting map 更新。
4. 迁移后 `AgentGameTestServer` 里三员 legacy 双胞胎保留（双门 A/B soak），probeSwing/probeHurt 最小可见性提升（private→public static，grantWaterEffects 先例）。

## 文件结构

| 文件 | 职责 |
|---|---|
| `scripts/testkit/t0.py`（改） | `--expect-file`（换行/逗号分隔、`#` 注释行），与 --expect-scene 并集去重 |
| `scripts/testkit/expected-scenes-neoforge.txt`（新） | checked-in 期望清单（先 5 名，随 Task 3-5 各自追加） |
| `scripts/testkit/verdict.py`（改，docstring only） | parse「no escape to GREEN」表述加第三方 harness 限定句 |
| `mc-testkit/common/.../harness/TestkitHarness.java`（改） | 显式槽位 < 1024 ⇒ IllegalStateException（防低位钉槽挤动 auto 区） |
| `neoforge/.../testkit/AgentDriverScenes.java`（改） | ad.selfShaftDigUp 签名门化 + 三新场景 + porting map 更新 |
| `neoforge/.../AgentGameTestServer.java`（改） | probeSwing/probeHurt 提 public static + 三员 javadoc 迁移注释（代码零动） |
| 文档：README（正门命令换 --expect-file）、契约附录（expect-file 小节）、TODO | |

---

### Task 1: --expect-file 清单化 + docstring 限定句

**Files:**
- Modify: `scripts/testkit/t0.py`
- Create: `scripts/testkit/expected-scenes-neoforge.txt`
- Modify: `scripts/testkit/verdict.py`（docstring only）

**Interfaces:**
- Produces: `--expect-file <path>`——逐行读，`#` 开头与空行跳过，行内允许逗号分隔多名；与 `--expect-scene` 结果做并集去重后传 judge 的 `expected`；文件不存在 ⇒ `ap.error`（大声，不静默）；文件存在但解析后零名字 ⇒ `ap.error`（同 P1.5a 空参语义）。相对路径锚定 REPO_ROOT（沿用 --results 的处理）。
- Consumes: P1.5a 的 expected 门。

- [ ] **Step 1: t0.py 实现**

```python
    ap.add_argument("--expect-file", default=None,
                    help="file of expected scene names (one per line, '#' comments, commas ok); union with --expect-scene")
```

解析函数：

```python
def load_expect_file(path):
    names = []
    with open(path) as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            names.extend(s.strip() for s in line.split(",") if s.strip())
    return names
```

main 里组装：expected = 并集（file + scene 参数），排序去重（`sorted(set(...))`——报告行序稳定）；两来源都空且都未给 ⇒ None；给了任一来源但结果为空 ⇒ `ap.error("expectation source given but contains no scene names")`。文件路径不存在 ⇒ `ap.error(f"--expect-file not found: {path}")`。

- [ ] **Step 2: 清单文件**

`scripts/testkit/expected-scenes-neoforge.txt`：

```
# Canonical expected-scene manifest for the neoforge dogfood suite (t0 --expect-file).
# One name per line. Every migrated ad.* scene MUST be listed here in its migration commit —
# a name missing from BOTH this file and registered[] is exactly the silent-composition
# hole this gate exists to close (P1c final review).
ad.ascendMovementNoop
ad.ascendDeadZoneWatchdog
ad.diagonalAscentSpeed
ad.selfShaftDigUp
ad.descentYaw
```

- [ ] **Step 3: verdict.py docstring 限定句**

parse 的 docstring「no escape path to GREEN」后补一句：对第三方 harness（footer 无 `scenes` 字段的合法 v0 实现），「坏行丢弃+重复记录」的复合边缘可能同时丢失 DUPLICATE 与 TRUNCATED 信号——本仓库 harness 自 936f4f2 起恒写 `scenes`，不受此影响。

- [ ] **Step 4: self-test 加 2 条 + 真跑**

self-test：tempfile 写清单（含注释/空行/逗号行）断言 load_expect_file 解析正确；union 去重断言（file 与 scene 参数重叠名不重复计）。

Run: `python3 scripts/testkit/t0.py --self-test; echo t0=$?`
Expected: 19/19 PASS exit 0。

Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl --expect-file scripts/testkit/expected-scenes-neoforge.txt; echo "exit=$?"`
Expected: `VERDICT: GREEN` exit=0（5 名单场景全在册，ad.selfShaftDigUp 仍 fail(optional)——签名门是 Task 2）。

- [ ] **Step 5: Commit**

```bash
git add scripts/testkit/t0.py scripts/testkit/expected-scenes-neoforge.txt scripts/testkit/verdict.py
git commit -m "feat(testkit): --expect-file checked-in manifest (union with --expect-scene), parse docstring qualifier"
```

---

### Task 2: task#86 传感器签名门化 + 槽位下限守卫

**Files:**
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/testkit/AgentDriverScenes.java`
- Modify: `mc-testkit/common/src/main/java/net/magicterra/testkit/harness/TestkitHarness.java`

**Interfaces:**
- Produces: ad.selfShaftDigUp 变 required 签名门；harness 显式槽 < 1024 拒绝。
- Consumes: P1.5a 的钉槽/withRequired 基建。

- [ ] **Step 1: 签名门改造**

ad.selfShaftDigUp 场景：**删掉** `.withRequired(false)`；断言逻辑改为钉已知失败签名——保留完整 arena/循环/采样，结尾判定换成：

```java
            // task#86 golden-failure pin: while the bug is open, this scene PASSES
            // only when the walker fails in EXACTLY the known way (deterministic
            // backslide, byte-stable across slots). Any other outcome is loud RED:
            //   - reached target with small backslide => #86 FIXED: flip this scene
            //     to the true assertion (see javadoc) and close the task.
            //   - different failure mode / drifted magnitude => new regression on
            //     top of #86, investigate before touching the pin.
            boolean reached = fp.getY() >= targetY - 1.5;
            boolean knownSignature = !reached && worstBackslide > 15.0;
            if (!knownSignature) {
                ctx.fail("task#86 signature broke: reached=" + reached
                        + " worstBackslide=" + worstBackslide
                        + " (known-bad: !reached && backslide>15; if this is the fix landing,"
                        + " flip ad.selfShaftDigUp to the strict assertion and close #86)");
            }
```

（`reached`/`worstBackslide` 判定沿用场景已有变量与阈值语义——`targetY - 1.5` 与 legacy 到达判据一致，读现场景体核对变量名。）javadoc 重写：签名门语义、翻回 strict 的操作步骤、golden 值 20.252203415101263 双槽同值记录、task#86 引用。签名区间用 >15 而非精确值：确定性是字节级的，但签名门要容忍未来无关 walker 改动的微小位移，只锁「失败模式本身」。

- [ ] **Step 2: 槽位下限守卫**

TestkitHarness.assignSlots pass-1 里显式槽检查：

```java
                if (s.originSlot() < 1024) {
                    throw new IllegalStateException("explicit origin slot " + s.originSlot()
                            + " below floor 1024 (scene " + s.name()
                            + ") — low pins displace auto slots, defeating pinning");
                }
```

（既有钉 4000/4001 不受影响。）

- [ ] **Step 3: 验证**

Run: `./gradlew :testkit-common:compileJava :neoforge:compileJava -q && python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl --expect-file scripts/testkit/expected-scenes-neoforge.txt; echo "exit=$?"`
Expected: `VERDICT: GREEN` exit=0，**ad.selfShaftDigUp 现在是 required PASS**（签名匹配），JSONL 该场景 outcome=PASS。

- [ ] **Step 4: Commit**

```bash
git add neoforge/src/main/java/net/magicterra/agent/neoforge/testkit/AgentDriverScenes.java mc-testkit/common/src/main/java/net/magicterra/testkit/harness/TestkitHarness.java
git commit -m "feat(testkit): task#86 sensor -> required golden-failure signature gate; origin-slot floor guard 1024"
```

---

### Task 3: 迁移 ad.gearScope（probe 提升 + driver 模式确立）

**Files:**
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestServer.java`（probeSwing:2507/probeHurt:2532 private→public static + serverAvatarGearScopeProbeArena javadoc 注释；代码零动）
- Modify: `neoforge/.../testkit/AgentDriverScenes.java`（新场景 + porting map 更新：driver 模式条目 + failure-msg prefix 约定注记）
- Modify: `scripts/testkit/expected-scenes-neoforge.txt`（+ad.gearScope）

**Interfaces:**
- Consumes: legacy `serverAvatarGearScopeProbeArena`（`AgentGameTestServer.java:2259-2339`）：7×5 pad+clearBox、`ServerAgentDriver.create`、probeSwing×2（空手/铁剑）、probeHurt×2（裸/穿甲）、断言剑伤≥3×拳伤、ATTACK_SPEED=1.6、ATTACK_DAMAGE=6.0、`ServerAgentManager.clear()` 收尾、config 仅 walkerDebug。
- Produces: `ad.gearScope`（auto 槽、默认半径——足迹审计写 javadoc）；**driver 类场景移植模式**（后续两任务沿用）：`createIsolated` + `ctx.cleanup(() -> { ServerAgentManager.unregister(driver); fp.discard(); })`（若 legacy 未 register 则只 discard；clear() 不用——targeted 优先，porting map 记录理由）。

- [ ] **Step 1: probe 提升 + 直译移植 + 清单追加**（porting 规则同前例；probe 调用点从类内直呼变 `AgentGameTestServer.probeSwing(...)` 显式引用）

- [ ] **Step 2: A/B（真单名）**

Run: `AGENT_GT_ONLY=serverAvatarGearScopeProbeArena ./scripts/run_gametests.sh`（前台 timeout 600000）
Expected: GREEN。（注意：该测试是彩票常客——若单名 solo 也 RED，按 Global Constraints 的 BLOCKED 协议走，那将是第二个倒置彩票样本。）

Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl --expect-file scripts/testkit/expected-scenes-neoforge.txt; echo "exit=$?"`
Expected: GREEN，ad.gearScope PASS，probe 数值与 legacy solo 同值（写进报告）。

- [ ] **Step 3: Commit**

```bash
git add neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestServer.java neoforge/src/main/java/net/magicterra/agent/neoforge/testkit/AgentDriverScenes.java scripts/testkit/expected-scenes-neoforge.txt
git commit -m "feat(testkit): dogfood wave 2b — ad.gearScope migrated (driver pattern established), probe helpers promoted"
```

---

### Task 4: 迁移 ad.buriedOre（MineProcess + manager 循环）

**Files:**
- Modify: `AgentDriverScenes.java` + `AgentGameTestServer.java`（javadoc）+ `expected-scenes-neoforge.txt`（+ad.buriedOre）

**Interfaces:**
- Consumes: legacy `serverMineBuriedOreArena`（`AgentGameTestServer.java:3240-3305`）：石方块埋 iron_ore、`ServerAgentDriver` + `MineProcess` + `ServerAgentManager.register/tickAll` 循环、断言 oreMined+process finished+unregistered、finally 整 rig 清除。
- Produces: `ad.buriedOre`（auto 槽）；tickAll 循环原样进 body（manager 只 tick 本场景注册的 driver——dogfood 服上无其它 agent，porting map 注明该假设）。

- [ ] **Step 1: 直译移植**（Task 3 的 driver 模式：createIsolated、cleanup targeted unregister+discard；legacy 的 finally rig 清除改 `ctx.cleanup`——网格隔离下方块残留无害但保持对称）
- [ ] **Step 2: A/B 真单名**（`AGENT_GT_ONLY=serverMineBuriedOreArena`，同 Task 3 协议——buriedore 也是彩票常客，BLOCKED 协议同样待命）+ 新壳跑（expect-file 已含新名）
- [ ] **Step 3: Commit**（`feat(testkit): dogfood wave 2b — ad.buriedOre migrated (MineProcess/manager loop pattern)`）

---

### Task 5: 迁移 ad.entityLeash（两阶段 + 手动 tick 直译）

**Files:**
- Modify: `AgentDriverScenes.java` + `AgentGameTestServer.java`（javadoc）+ `expected-scenes-neoforge.txt`（+ad.entityLeash）

**Interfaces:**
- Consumes: legacy `entityLeashRepathArena`（`AgentGameTestServer.java:931-1041`）：5 宽栏杆 lane、ArmorStand 锚、`IntentProcess`/`EntityLeash`、两阶段（hold→teleport 锚→repath），`for(i<3) level.tick(()->true)` ×2 强制实体索引。
- Produces: `ad.entityLeash`。**手动 tick 决策**：直译保留 `level.tick(()->true)`——场景在 ServerTickEvent.Post 执行（所有 level 已 tick 完），重入比 legacy（GameTest 在 tick 内跑）更安全；javadoc 记录该论证。**降级预案**（仅当直译实测异常——崩溃/实体索引仍失败）：把两处手动 tick 换成 `ctx.await(实体可查询).within(5)` 真实 tick 等待，两阶段拆 await 步，porting map 记录语义偏差与理由；降级属 sanctioned，但必须先试直译并留证据。
- 足迹审计：lane 长度按 legacy 铺设代码算包络，超默认窗则 `.withChunkRadius(2)`。

- [ ] **Step 1: 直译移植**（legacy 锚定 helper.absolutePos(BlockPos.ZERO) 的 this-chunk 语义换 ctx.origin() 相对——网格 origin 恒 chunk 对齐，等价）
- [ ] **Step 2: A/B 真单名**（`AGENT_GT_ONLY=entityLeashRepathArena`）+ 新壳跑
- [ ] **Step 3: Commit**（`feat(testkit): dogfood wave 2b — ad.entityLeash migrated (two-phase, manual-tick direct port)`）

---

### Task 6: 验收 + 文档

**Files:**
- Modify: `mc-testkit/README.md`（正门命令换 `--expect-file scripts/testkit/expected-scenes-neoforge.txt`）
- Modify: `docs/testkit/orchestration-contract-v0.md`（附录：expect-file 小节，语义只收紧仍 v0）
- Modify: `TODO.md`（P1.5b 条目）

- [ ] **Step 1: 全量验收**

Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl --expect-file scripts/testkit/expected-scenes-neoforge.txt && python3 scripts/testkit/t0.py --loader neoforge --wall 540; echo "exit=$?"`
Expected: dogfood GREEN（13 场景记录：5 内建 + 8 ad.*，全 required 全 PASS——签名门含）+ 纯 T0 GREEN。

legacy 全量一轮（后台启动+有界前台轮询收割）：GREEN 则删除倒数 +1；彩票 RED 则真单名 solo 定性如实入档。

- [ ] **Step 2: 文档 + Commit**

README 正门命令与迁移规则更新（expect-file 为正门形态；--expect-scene 降为 ad-hoc）；契约附录 expect-file 小节（文件不存在/零名字=大声错误、与 --expect-scene 并集、报告行同 MISSING-EXPECTED）；TODO P1.5b 条目（commits、8/8 迁移进度、A/B 留痕、签名门语义、若有新倒置彩票样本如实记录、残余=P2/fabric/legacy 删除倒数现值）。

```bash
git add mc-testkit/README.md docs/testkit/orchestration-contract-v0.md TODO.md
git commit -m "docs(testkit): P1.5b — expect-file canonical, signature-gate semantics, wave-2b A/B record"
```

---

## Self-Review（计划自检记录）

1. **覆盖**：P1.5a 终审建议三件（expect-file/签名门/治理已入 README）→ Task 1/2/既有；driver 三员 → Task 3-5（模式在 Task 3 确立、4/5 沿用）；遗留 Minor（docstring 限定/槽下限/prefix 注记）→ Task 1/2/3；fabric 与 legacy 删除=偏差声明。
2. **占位符**：Task 1/2 全代码；Task 3-5 沿用五次验证过的直译模式并点名行号、断言值、两处设计决策（createIsolated、手动 tick 直译+降级预案）——移植体引用 legacy 源码非留白。
3. **一致性**：expected-scenes-neoforge.txt 的名字在 Task 1（5 名）与 Task 3/4/5（各 +1）与 Task 6（8 名验收）串一致；签名门变量名以现场景体为准的读取点已标注；driver cleanup 模式三任务同款。
4. **风险入案**：gearscope/buriedore 单名 solo 可能揭第二/三个倒置彩票（BLOCKED 协议前置声明）；手动 tick 重入（论证+降级预案）；manager tickAll 全局性（dogfood 无其它 agent 假设入 porting map）；probe 提升的可见性最小化。

# mc-testkit P1.5a：wave-2 前置 + 彩票家族 walker 两员迁移 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 P1c 终审排出的 wave-2 规模化前置（外部期望门 / 按名固定 origin / per-scene forceload 半径 / parse 截断容错），并把彩票家族中 walker 形态的两员（selfShaftDigUp、descentYaw）迁成 testkit 场景。

**Architecture:** t0.py 加 `--expect-scene`（legacy 删除前置的外部期望校验）+ verdict.parse 坏行降级；Scene 加 originSlot/chunkRadius 两个带默认值的扩展（copy-with 方法），harness 槽位预留分配 + 半径化 forceload；两场景按 P1c 三员的同步 body 直译模式迁移（descentYaw 钉固定槽位防坐标重排破坏字节级确定性），legacy 双胞胎保留双门 A/B。

**Tech Stack:** Java 21 / architectury-loom；Python 3 stdlib；沿用 P1c 的 SceneProvider/dogfood 基建。

## Global Constraints

- 依赖方向 agent-driver → testkit 不逆转；mc-testkit/* 零 agent-driver 引用。
- 契约 v0 只收紧/澄清：`--expect-scene` 缺席名 ⇒ RED（MISSING-EXPECTED）；parse 坏行=丢弃并计数、后果向 RED 降级（缺 footer/SWALLOWED 自然接管），绝不向 GREEN 降级。
- 移植保真：断言数值一个不动（selfShaftDigUp 的 `worstBackslide ≤ maxDryFall+1`、4000 循环上限、目标 Y；descentYaw 的 sumAbsDyaw 天花板、backward-hop 断言、步进结构）；同步循环原样进 body。
- descentYaw 是字节级确定性场景（P0 探针自伤事故的受害者）：**必须钉显式 originSlot**；场景 body 内禁任何 wall-clock 依赖。
- 每场景 `ServerPlayerAvatar.createUnique` + `ctx.cleanup` discard；`BotConfig.pinnedBaseline()` 先注册（LIFO 最后关）。
- legacy 两员代码零改动（只加 javadoc 迁移注释）；场景名 `ad.selfShaftDigUp` / `ad.descentYaw`。
- t0 既有 13 self-test + instrument 5 self-test 必须全过；两 CLI 参数缺省行为不变。
- 运行纪律：前台 Bash timeout 参数；禁 pkill；每 run 删世界；子代理绝不带着后台任务结束回合（后台通知不会叫醒你——有界前台轮询收割）。

## 已声明偏差 / 顺延清单（评审勿标缺）

1. **driver 家族三员（gearscope/buriedore/entityLeash）归 P1.5b**：它们走 ServerAgentDriver/Process/manager.tickAll 与手动 level.tick() 形态，移植模式与 walker 家族不同（probe helper 可见性、实体索引策略需单独设计），不塞进本竖切。
2. **fabric 全量对齐仍单列**（spec §7 原 P1.5 里程碑之一，与本计划无冲突）。
3. **legacy 删除仍不执行**：本计划落地删除前置（外部期望门），删除动作等 3 轮双门全绿+期望门武装后另行执行。
4. P1c 终审 Minor #5（TestkitCommon 双臂防护）与 #4（--results 相对路径）顺手在 Task 1/2 收掉；#2（done.scenes 与异步 writer 的语义耦合注记）已在 TODO 残留，不动代码。

## 文件结构

| 文件 | 职责 |
|---|---|
| `scripts/testkit/verdict.py`（改） | parse 坏行丢弃+计数（返回不变，警告经新可选回调/返回附带——见 Task 1 设计）；judge 加 expected 参数（缺席名 ⇒ RED） |
| `scripts/testkit/t0.py`（改） | `--expect-scene`；--results 相对路径锚定 REPO_ROOT；self-test 扩充 |
| `mc-testkit/common/.../scene/Scene.java`（改） | `originSlot`(默认-1=auto)/`chunkRadius`(默认1) + `withOriginSlot`/`withChunkRadius` |
| `mc-testkit/common/.../harness/TestkitHarness.java`（改） | 槽位预留分配（显式槽冲突=启动即炸）+ forceload/等待按半径 |
| `mc-testkit/common/.../harness/TestkitCommon.java`（改） | 双臂幂等防护一行 |
| `neoforge/.../testkit/AgentDriverScenes.java`（改） | + ad.selfShaftDigUp、ad.descentYaw（后者钉槽） |
| `AgentGameTestTerrain.java`（改，仅注释） | 两员 javadoc 迁移注释 |
| 文档：契约附录、README、TODO | expect-scene/originSlot/chunkRadius 说明 + A/B 留痕 |

---

### Task 1: 编排器前置——expect-scene 门 + parse 截断容错 + 路径锚定

**Files:**
- Modify: `scripts/testkit/verdict.py`
- Modify: `scripts/testkit/t0.py`

**Interfaces:**
- Produces: `judge(records, record_type="scene", expected=None)`——expected 为名字集合，逐名要求出现在 suite header `registered[]`，缺席 ⇒ `code=max(code,1)` + 报告行 `MISSING-EXPECTED: <name> not in registered`；`parse(path)` 返回不变（list[dict]），但对无法解码的行：**丢弃并向 stderr 打警告**（`[verdict] WARN: dropped undecodable line N: <前80字符>`），不再抛裸 traceback——丢弃的后果由既有门自然接管（丢 footer→缺尾 RED、丢 scene 记录→SWALLOWED RED、丢 header→ENV），全部朝 RED 降级。
- Consumes: 无新依赖。

- [ ] **Step 1: verdict.parse 坏行降级**

parse() 现逐行 `json.loads` 坏行抛 ValueError。改为：

```python
def parse(path):
    records = []
    with open(path) as f:
        for i, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError:
                print(f"[verdict] WARN: dropped undecodable line {i}: {line[:80]!r}",
                      file=sys.stderr)
    return records
```

（补 `import sys`。语义：坏行只可能让结果更红——header/记录/footer 任何一类缺失都被既有规则判 ENV/RED，无向 GREEN 逃逸路径；把这句写进函数 docstring。）

- [ ] **Step 2: judge 加 expected 门**

签名 `def judge(records, record_type="scene", expected=None):`，在解析出 registered 名单之后、逐名循环之前插入：

```python
    if expected:
        reg_names = {norm(r["name"]) for r in registered}
        for want in expected:
            if norm(want) not in reg_names:
                code = max(code, 1)
                report.append(f"MISSING-EXPECTED: {want} not in registered")
```

（`norm`/名字规整沿用文件内既有做法；若无 norm 就直接字符串比对——与 registered 存储形态一致即可，以文件现状为准。）

- [ ] **Step 3: t0.py --expect-scene + 路径锚定**

```python
    ap.add_argument("--expect-scene", default=None,
                    help="comma-separated scene names that MUST appear in registered[] (RED if absent)")
```

传入 judge：`expected = [s.strip() for s in args.expect_scene.split(",")] if args.expect_scene else None`。

--results 相对路径锚定：`if args.results and not os.path.isabs(args.results): args.results = os.path.join(REPO_ROOT, args.results)`（README 命令从任意 cwd 可用）。

- [ ] **Step 4: self-test 扩充（t0 加 3 条）**

```python
        ("expected scene present -> 0",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), F_DONE], expected=["a"])[0] == 0),
        ("expected scene missing -> 1",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), F_DONE], expected=["ghost"])[0] == 1),
        ("expected=None unchanged -> 0",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), F_DONE])[0] == 0),
```

（F_DONE 的 scenes 数与 fixture 记录数已在 P1c 对齐——新 fixture 沿用。）parse 的坏行行为加 1 条自测：写临时文件含一条完整记录+一条截断行，断言 parse 返回 1 条且不抛（用 tempfile，self-test 内联）。

- [ ] **Step 5: 验证**

Run: `python3 scripts/testkit/t0.py --self-test; echo t0=$?; python3 scripts/testkit/instrument.py --self-test; echo ins=$?`
Expected: t0 17/17 PASS exit 0（13+3+1）；instrument 5/5 PASS exit 0。

Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540 --expect-scene floorAssert,awaitTicks; echo "exit=$?"`
Expected: `VERDICT: GREEN` exit=0（期望门对既有内建场景生效且不误伤）。

- [ ] **Step 6: Commit**

```bash
git add scripts/testkit/verdict.py scripts/testkit/t0.py
git commit -m "feat(testkit): --expect-scene external expectation gate, parse bad-line degradation, --results path anchoring"
```

---

### Task 2: harness 前置——originSlot 钉扎 + chunkRadius + 双臂防护

**Files:**
- Modify: `mc-testkit/common/src/main/java/net/magicterra/testkit/scene/Scene.java`
- Modify: `mc-testkit/common/src/main/java/net/magicterra/testkit/harness/TestkitHarness.java`
- Modify: `mc-testkit/common/src/main/java/net/magicterra/testkit/harness/TestkitCommon.java`

**Interfaces:**
- Produces: `Scene.withOriginSlot(int slot)`（显式槽位，默认 -1=自动分配）与 `Scene.withChunkRadius(int r)`（默认 1=3×3）；harness 槽位分配算法（下述）；TestkitCommon 幂等防护。
- Consumes: 既有 Scene record + originFor(index)。

- [ ] **Step 1: Scene 扩字段**

record 加两个组件 `int originSlot, int chunkRadius`（放尾部），全部既有工厂在内部传 `-1, 1`；加 copy-with：

```java
    /** Pin this scene to a fixed origin slot — REQUIRED for byte-determinism-
     *  sensitive scenes: auto slots are assignment-order dependent, so suite
     *  growth relocates them and double-precision physics differs by position. */
    public Scene withOriginSlot(int slot) {
        return new Scene(name, budgetTicks, required, canary, body, slot, chunkRadius);
    }

    /** Widen the forced-chunk window to (2r+1)² — for arenas that exceed the
     *  default 3×3 footprint (usable dx/dz beyond [-16,31] needs r>=2). */
    public Scene withChunkRadius(int r) {
        return new Scene(name, budgetTicks, required, canary, body, originSlot, r);
    }
```

（组件顺序/工厂细节以 Scene.java 现文件为准；保持既有调用零改动编译通过。）

- [ ] **Step 2: harness 槽位分配**

构造器里（重名门之后）做一次预分配，产出 `Map<String,Integer> slotByName`：

```java
    private static Map<String, Integer> assignSlots(List<Scene> scenes) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<Integer> taken = new HashSet<>();
        for (Scene s : scenes) {                       // pass 1: explicit pins
            if (s.originSlot() >= 0) {
                if (!taken.add(s.originSlot())) {
                    throw new IllegalStateException("origin slot collision: " + s.originSlot()
                            + " (scene " + s.name() + ")");
                }
                out.put(s.name(), s.originSlot());
            }
        }
        int next = 0;
        for (Scene s : scenes) {                       // pass 2: auto scenes skip pinned slots
            if (s.originSlot() < 0) {
                while (taken.contains(next)) next++;
                taken.add(next);
                out.put(s.name(), next);
            }
        }
        return out;
    }
```

`originFor(index)` 改为 `originFor(slot)`（同一网格公式，输入换 slot），调用点从场景序号换 `slotByName.get(scene.name())`。forceChunks/allChunksLoaded 加半径参数（循环 `-r..r`），PREP 与 teardown 的调用点都带上 `scene.chunkRadius()`。

- [ ] **Step 3: TestkitCommon 双臂防护**

onServerStarted 开头：`if (harness != null) { <既有日志惯例> "harness already armed — ignoring duplicate onServerStarted"; return; }`。

- [ ] **Step 4: 编译 + 双拓扑回归**

Run: `./gradlew :testkit-common:compileJava -q && python3 scripts/testkit/t0.py --loader neoforge --wall 540 && python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl --expect-scene ad.ascendMovementNoop,ad.ascendDeadZoneWatchdog,ad.diagonalAscentSpeed; echo "exit=$?"`
Expected: 两轮 `VERDICT: GREEN` exit=0——全 auto 场景时槽位分配=原 index 语义（内建+ad.* 序号不变），dogfood 带期望门首次武装。

- [ ] **Step 5: Commit**

```bash
git add mc-testkit/common/src/main/java/net/magicterra/testkit/
git commit -m "feat(testkit): pinned origin slots + per-scene chunk radius + harness double-arm guard"
```

---

### Task 3: 迁移 ad.selfShaftDigUp + A/B

**Files:**
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/testkit/AgentDriverScenes.java`
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestTerrain.java`（仅 javadoc）

**Interfaces:**
- Consumes: legacy `selfShaftDigUpArena`（`AgentGameTestTerrain.java:800-859`）；P1c 的移植映射规则（AgentDriverScenes 类 javadoc 里有 porting map——沿用）。
- Produces: 场景 `ad.selfShaftDigUp`（auto 槽位，默认半径）。

- [ ] **Step 1: 直译移植**

按 P1c 模式：密封石柱 arena（base=200,top=220 的相对化——**注意 legacy 用绝对 y=200..220，场景网格 y=200 起，直接用 origin 的 y 作 base 即可**，保持柱高/腔室尺寸原样）；cobblestone 库存、无镐、allowBreak/allowPlace=true（pinnedBaseline 后显式 set）；`Goal.YLevel` 目标、4000 循环、`worstBackslide ≤ maxDryFall+1` 与到达 Y 断言逐字保留。createUnique + cleanup discard + pin 先注册。legacy 方法 javadoc 加迁移注释（P1c 措辞模板）。

- [ ] **Step 2: A/B**

Run: `AGENT_GT_ONLY=selfShaftDigUpArena ./scripts/run_gametests.sh`（前台 timeout 600000）
Expected: legacy solo GREEN。

Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl --expect-scene ad.selfShaftDigUp; echo "exit=$?"`
Expected: GREEN，ad.selfShaftDigUp PASS。红则按 P1c 分诊规则（先移植误差后 harness 缝，禁调松断言，闭不了 gap 报 BLOCKED）。

- [ ] **Step 3: Commit**

```bash
git add neoforge/src/main/java/net/magicterra/agent/neoforge/testkit/AgentDriverScenes.java neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestTerrain.java
git commit -m "feat(testkit): dogfood wave 2a — ad.selfShaftDigUp migrated (lottery walker family), legacy kept for A/B"
```

---

### Task 4: 迁移 ad.descentYaw（钉槽 + 半径）+ A/B

**Files:**
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/testkit/AgentDriverScenes.java`
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestTerrain.java`（仅 javadoc）

**Interfaces:**
- Consumes: legacy `descentYawArena`（`AgentGameTestTerrain.java:1202-1322`，legacy 已用 pinnedBaseline，solo batch=soloDescentYaw）。
- Produces: 场景 `ad.descentYaw`，**`.withOriginSlot(4000)`**（高位远离 auto 区，常量命名 `DESCENT_YAW_SLOT` 加注释：字节级确定性场景，槽位一经发布不得变更）+ `.withChunkRadius(2)`。

- [ ] **Step 1: 足迹审计先行**

读 legacy arena 铺设代码，算出完整 dx/dz 包络（45° 下降 9 步 + run-out 平台 + 起步区），写进场景 javadoc；对照 radius=2 的可用窗（约 -32..+47）确认全含。若不够改 radius=3 并注明。

- [ ] **Step 2: 直译移植**

同步循环、采样与断言逐字保留（sumAbsDyaw 天花板、backward-hop、reached-bottom 判据、步进容差全原值）；pinnedBaseline（legacy 已有，模式一致）；createUnique + cleanup。**场景 javadoc 必须写**：确定性敏感（P0 探针事故受害者）、钉槽理由、任何 harness 时序改动后本场景是金丝雀级哨兵。legacy javadoc 加迁移注释。

- [ ] **Step 3: A/B + 稳定性三连**

Run: `AGENT_GT_ONLY=descentYawArena ./scripts/run_gametests.sh`（前台 timeout 600000）
Expected: legacy solo GREEN。

Run（三连，确定性证据）: `for i in 1 2 3; do python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl --expect-scene ad.descentYaw || break; done; echo "exit=$?"`
Expected: 三轮全 GREEN 且 ad.descentYaw 三轮 PASS——该场景在 legacy 全量下是彩票成员，新壳三连绿即为「隔离身体+钉槽」根治假说的第一手证据；若新壳也间歇红，如实记录（那是病随身体走的反证，同样有价值），报 DONE_WITH_CONCERNS 附三轮记录。

- [ ] **Step 4: Commit**

```bash
git add neoforge/src/main/java/net/magicterra/agent/neoforge/testkit/AgentDriverScenes.java neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestTerrain.java
git commit -m "feat(testkit): dogfood wave 2a — ad.descentYaw migrated with pinned origin slot + radius 2 (determinism-sensitive)"
```

---

### Task 5: 验收 + 文档

**Files:**
- Modify: `docs/testkit/orchestration-contract-v0.md`（附录补三段：--expect-scene 门、originSlot 钉扎、chunkRadius）
- Modify: `mc-testkit/README.md`（dogfood 命令更新为带 --expect-scene 全 5 名；迁移规则加「同步 body 必须有界循环」一句）
- Modify: `TODO.md`（P1.5a 条目）

**Interfaces:**
- Consumes: Task 1-4 全部。

- [ ] **Step 1: 全量验收**

Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl --expect-scene ad.ascendMovementNoop,ad.ascendDeadZoneWatchdog,ad.diagonalAscentSpeed,ad.selfShaftDigUp,ad.descentYaw && python3 scripts/testkit/t0.py --loader neoforge --wall 540; echo "exit=$?"`
Expected: dogfood（10 场景记录：5 内建含双金丝雀 + 5 ad.*）与纯 T0 双 GREEN。

legacy 全量门跑一轮（后台启动+有界前台轮询收割，参照 P1c Task 5 纪律；underwaterBase 挂死按病历处理）：GREEN 则删除倒数 +1；抽中彩票 RED 则按 P0 协议 solo 定性后如实入档（不 fishing）。

- [ ] **Step 2: 文档**

契约附录三段（语义均为收紧/澄清，v0 不 bump）：①--expect-scene=编排器侧外部期望，防套件组装层自洽假绿（写明这是 legacy 删除前置）；②originSlot=确定性场景的坐标钉扎，发布后不得变更；③chunkRadius=足迹超默认窗的声明武器。README 更新 dogfood 命令与迁移规则。TODO.md P1.5a 条目：commits、A/B 与三连稳定性证据、期望门首次武装、双门状态与删除倒数现值、残留（P1.5b driver 家族三员、fabric 对齐、异步 writer 注记）。

- [ ] **Step 3: Commit**

```bash
git add docs/testkit/orchestration-contract-v0.md mc-testkit/README.md TODO.md
git commit -m "docs(testkit): P1.5a — expect-scene gate armed, origin pinning & chunk radius appendices, wave-2a A/B record"
```

---

## Self-Review（计划自检记录）

1. **Spec/终审覆盖**：终审 Important（外部期望门）→ Task 1+Task 5 武装；wave-2 四前置 → Task 1（parse 截断）/Task 2（钉槽+半径）/Task 5（有界循环规则入文档）；终审 Minor #4/#5 → Task 1/2 顺手收；彩票家族 walker 两员 → Task 3/4；driver 三员+fabric 对齐=头部偏差①②。
2. **占位符扫描**：judge/parse/t0/槽位分配给完整代码；两场景移植沿用 P1c 已验证的直译模式并点名 legacy 行号与保留数值（4000 循环、worstBackslide≤maxDryFall+1、sumAbsDyaw/backward-hop 原值）；「以现文件为准」仅用于既有代码的引用点（Scene 工厂形态、norm 规整）。
3. **类型一致性**：judge(expected=None) 三处调用一致；withOriginSlot/withChunkRadius 与 assignSlots/originFor(slot) 的消费链一致；--expect-scene 的名字串在 Task 2/3/4/5 的命令里与场景注册名逐字一致。
4. **风险入案**：descentYaw 新壳仍间歇红的可能（Task 4 Step 3 显式允许 DONE_WITH_CONCERNS 如实记录——这本身是 #48 病理的有价值数据）；槽位公式网格容量（slot 4000×512 块=x≈2,148,000，仍在世界边界 3000 万内，无风险）；全 auto 时分配退化为原 index 语义（Task 2 Step 4 双拓扑回归验证）；legacy 全量彩票 RED 的处理协议沿用 P1c（solo 定性、不 fishing）。

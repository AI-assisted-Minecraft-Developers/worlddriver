# mc-testkit P1c：dogfood 迁移第一批（被吞三员）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通 agent-driver 场景挂进 testkit T0 的完整路径（SceneProvider SPI + dogfood 专服），并把 #85 被吞三员（ascendMovementNoop / ascendDeadZoneWatchdog / diagonalAscentSpeed）迁成 testkit 场景，新旧壳 A/B 后旧门与新门并行运行。

**Architecture:** testkit-common 加 ServiceLoader 制 SceneProvider 缝 + SceneContext 补 level()/origin()/cleanup() + PREP 等满 3×3 forceload；agent-driver neoforge 依赖 :testkit-common（方向不逆转），自带 dogfoodServer run 配置并在 testkit.autorun 时调 TestkitCommon 钩子；三个被吞测试按「同步循环原样进 body 首 tick」语义换壳（保留 byte 级时序，A/B 可比），legacy @GameTest 保留至 3 轮双门全绿再删（本计划不删）。

**Tech Stack:** Java 21 / architectury-loom；ServiceLoader（META-INF/services）；既有 t0.py 编排器加 `--run-task` 泛化。

## Global Constraints

- 依赖方向：agent-driver → testkit（neoforge 依赖 :testkit-common）；mc-testkit 各模块**零** agent-driver import（评审红线）。
- 编排契约 v0 不改语义；judge() 的新增 done.scenes 对账是**纯收紧**（多抓一类截断，绝不放宽）；t0.py 既有 11 条 self-test 必须全过。
- 场景名全局唯一（TestkitHarness 重名即炸）；agent-driver 场景名前缀 `ad.`。
- 场景 body 首 tick 同步执行是既有语义——迁移的 walker 循环**原样放 body 内**（与 legacy GameTest 同为 server 线程内同步循环，壳更换不引入时序漂移）；禁 wall-clock sleep。
- BotConfig 纪律：每个迁移场景用 `try (var pin = BotConfig.pinnedBaseline())` 包全身（比 legacy 的逐 key save/restore 更严，杜绝泄漏）；dogfood 服务器启动时 `BotConfig.applyGameTestBaseline()`（与 GameTestServer 同基线）。
- 身体纪律：迁移场景用 `ServerPlayerAvatar.createUnique(...)`（每场景唯一身体，根治 #48 共享身体彩票），并经 `ctx.cleanup(...)` 注册 discard——cleanup 必须在 PASS/FAIL/TIMEOUT 三种出口都执行。
- A/B 纪律（spec §10）：每个迁移场景先跑 legacy `AGENT_GT_ONLY` 显式基线，再跑新壳，断言语义逐条对齐后才算迁完；legacy @GameTest 本阶段**保留**（双门并行），删除条件=3 轮双门全绿（记 TODO，不在本计划执行）。
- 运行纪律：前台等待用 Bash timeout 参数；PID 清扫按 marker（dogfood 服沿用 `testkit.autorun`，t0.py 的 sweep 直接适用）；禁 pkill；每 run 删世界。

## 已声明偏差 / 顺延清单（评审勿标缺）

1. **testmod source set 搬家不在本阶段**：spec §6.3 把「迁 @SceneTest」与「搬出生产 jar」并提，但 §7 P4 单列「testmod 搬家」——本阶段场景放 neoforge 主 source set（与既有 130 测试同待遇），整体搬家归 P4，避免 gradle source set 工程压垮竖切。
2. **dogfood 仅 neoforge**：avatar/sim 包是 neoforge-only（勘察实证），fabric 侧驱动层测试对齐本来就是 P1.5 单列里程碑。
3. **只迁被吞三员**：彩票家族（gearscope/descentyaw/buriedore/entityleash/selfshaftdigup）依赖 ServerAgentDriver/Process 栈或百行级 arena，归 P1.5/P2 按 family 继续；三员先证明通路。
4. **legacy 删除不在本计划**（见 A/B 纪律）。
5. **ResultsJsonl 异步 writer 复审**：现状=场景边界写、javadoc 已带「边界写仍偏移下一场景 wall-clock」告示。三员断言全部**不依赖 wall-clock**（tick 计数/alloc 计数/bps 由 tick 推导），故本阶段维持同步边界写，异步化推迟到出现 wall-clock 敏感场景时（记入 TODO 残留）。

## 文件结构

| 文件 | 职责 |
|---|---|
| `mc-testkit/common/.../scene/SceneProvider.java`（新） | SPI 接口：`List<Scene> scenes()` |
| `mc-testkit/common/.../scene/Scenes.java`（改） | `all()` = 内建 5 场景 + ServiceLoader 发现的 provider 场景 |
| `mc-testkit/common/.../scene/SceneContext.java`（改） | 暴露 `level()`/`origin()`；新增 `cleanup(Runnable)` |
| `mc-testkit/common/.../harness/TestkitHarness.java`（改） | PREP 等满 3×3 全 chunk；出口统一跑 cleanups |
| `scripts/testkit/verdict.py` + `t0.py`（改） | judge 补 done.scenes==记录数对账 + self-test；t0 加 `--run-task`/`--results-dir` 泛化 |
| `neoforge/build.gradle`（改） | 依赖 :testkit-common；`dogfoodServer` run 配置（runDir `run-dogfood`） |
| `neoforge/.../AgentDriverNeoForge.java`（改） | testkit.autorun 时接 TestkitCommon 钩子 + applyGameTestBaseline |
| `neoforge/.../testkit/AgentDriverScenes.java`（新） | SceneProvider 实现：三个迁移场景 |
| `neoforge/src/main/resources/META-INF/services/...SceneProvider`（新） | SPI 注册 |
| `AgentGameTestTerrain.java`（改，仅注释） | 三员加「已迁 testkit，双门并行至 3 轮全绿」注释 |
| 文档：`docs/testkit/orchestration-contract-v0.md`（附录小节）、`mc-testkit/README.md`、`TODO.md` | SPI 说明、dogfood 入口、A/B 留痕 |

---

### Task 1: testkit-common 侧三件——SceneProvider SPI + SceneContext 扩口 + PREP 全 forceload 等待

**Files:**
- Create: `mc-testkit/common/src/main/java/net/magicterra/testkit/scene/SceneProvider.java`
- Modify: `mc-testkit/common/src/main/java/net/magicterra/testkit/scene/Scenes.java`
- Modify: `mc-testkit/common/src/main/java/net/magicterra/testkit/scene/SceneContext.java`
- Modify: `mc-testkit/common/src/main/java/net/magicterra/testkit/harness/TestkitHarness.java`

**Interfaces:**
- Produces: `public interface SceneProvider { List<Scene> scenes(); }`（ServiceLoader 发现）；`SceneContext.level() -> ServerLevel`、`SceneContext.origin() -> BlockPos`、`SceneContext.cleanup(Runnable)`（后进先出，出口必执行）；PREP 等待 3×3 全部 `hasChunkAt`。
- Consumes: 既有 Scene/Canary/TestkitHarness。

- [ ] **Step 1: SceneProvider 接口**

```java
package net.magicterra.testkit.scene;

import java.util.List;

/**
 * SPI seam for downstream mods to contribute scenes to the T0 suite.
 * Implementations are discovered via {@link java.util.ServiceLoader}
 * (META-INF/services/net.magicterra.testkit.scene.SceneProvider).
 * Scene names must be globally unique across all providers — the harness
 * rejects duplicates loudly before writing the suite header.
 */
public interface SceneProvider {
    List<Scene> scenes();
}
```

- [ ] **Step 2: Scenes.all() 合并 provider**

`Scenes.all()` 现返回内建 `List.of(...)`。改为：

```java
    public static List<Scene> all() {
        List<Scene> out = new ArrayList<>(builtin());
        for (SceneProvider p : ServiceLoader.load(SceneProvider.class)) {
            out.addAll(p.scenes());
        }
        return List.copyOf(out);
    }
```

原字面量列表改名为 `private static List<Scene> builtin()`，内容不动。加 import `java.util.ArrayList`/`java.util.ServiceLoader`。执行顺序=内建在前、provider 按发现序在后（注释注明）。

- [ ] **Step 3: SceneContext 扩口**

加三个成员方法（level 字段已存在）：

```java
    /** The backing server level — for scenes that drive entities/avatars directly. */
    public ServerLevel level() { return level; }

    /** Absolute origin of this scene's grid cell (scene code should prefer rel()). */
    public BlockPos origin() { return origin; }

    private final java.util.Deque<Runnable> cleanups = new java.util.ArrayDeque<>();

    /**
     * Register teardown to run when the scene resolves — on PASS, FAIL and
     * TIMEOUT alike (LIFO). Use for avatar discard, config unpin, entity kill:
     * anything that must not leak into the next scene.
     */
    public void cleanup(Runnable r) { cleanups.addFirst(r); }

    /** Harness-internal: drain cleanups; exceptions logged, never thrown. */
    public void runCleanups(java.util.function.Consumer<String> warn) {
        for (Runnable r : cleanups) {
            try { r.run(); } catch (Throwable t) { warn.accept("cleanup failed: " + t); }
        }
        cleanups.clear();
    }
```

（import 按文件现有风格补；`runCleanups` 的 warn 回调让 harness 决定日志去向，SceneContext 不引日志依赖。）

- [ ] **Step 4: harness 两处——出口统一 cleanup + PREP 全 forceload 等待**

`TestkitHarness` 场景出口（写 scene 记录之前/之后均可，但必须三种 outcome 都覆盖——找到 RUN/ADVANCE 阶段判定 PASS/FAIL/TIMEOUT 后、进入下一场景前的单一汇合点）插入：

```java
        ctx.runCleanups(msg -> LOG.warn("[testkit] {}: {}", scene.name(), msg));
```

（若该类无 LOG，用现有的日志/println 惯例。）

PREP 阶段现只 `hasChunkAt(origin)`。改为等满 3×3（forceload 已是 3×3）：

```java
    private boolean allChunksLoaded(BlockPos origin) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (!level.hasChunkAt(origin.offset(dx * 16, 0, dz * 16))) return false;
        return true;
    }
```

PREP 判定处 `hasChunkAt(origin)` 换 `allChunksLoaded(origin)`（PREP_BUDGET 不变）。

- [ ] **Step 5: 编译 + T0 回归**

Run: `./gradlew :testkit-common:compileJava :testkit-neoforge:build -q && python3 scripts/testkit/t0.py --loader neoforge --wall 540; echo "exit=$?"`
Expected: BUILD 通过；`VERDICT: GREEN` exit=0（无 provider 时行为不变：5 内建场景照旧）。

- [ ] **Step 6: Commit**

```bash
git add mc-testkit/common/src/main/java/net/magicterra/testkit/
git commit -m "feat(testkit): SceneProvider SPI, SceneContext level/origin/cleanup, PREP awaits full 3x3 forceload"
```

---

### Task 2: judge 补 done.scenes 对账 + t0.py 泛化 --run-task

**Files:**
- Modify: `scripts/testkit/verdict.py`（judge 内加对账）
- Modify: `scripts/testkit/t0.py`（self-test 加 2 条；CLI 加 `--run-task` 与 `--results`）

**Interfaces:**
- Produces: judge 新规则——`done.scenes` 字段存在且 ≠ 实际 scene/check 记录数 ⇒ RED（报告行 `TRUNCATED: done.scenes=N but M records`）；t0.py `--run-task <gradle task>`（默认 `:testkit-{loader}:runTestkitServer`）与 `--results <path>`（默认现值），供 dogfood 复用同一编排器。
- Consumes: Task 1 无关（可与 Task 1 并序，但按序执行）。

- [ ] **Step 1: verdict.judge 对账**

在 judge() 处理完全部注册循环之后、返回之前插入（位置：现有 duplicate 检查同层）：

```python
    declared = done.get("scenes")
    if isinstance(declared, int) and declared != len(records_by_name_all):
        code = max(code, 1)
        report.append(f"TRUNCATED: done.scenes={declared} but {len(records_by_name_all)} {record_type} records")
```

其中 `records_by_name_all` = 实际收到的 scene/check 记录总数（含重复计数——用已有的按名计数器求和，勿新扫一遍）。**注意**：`done.scenes` 语义=执行的记录数（吞金丝雀不计），与记录数应严格相等；缺字段（老文件）不触发（isinstance 门）。

- [ ] **Step 2: t0.py self-test 加 2 条**

沿既有 fixture 风格追加：

```python
        ("done.scenes mismatch -> 1",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), {"type": "done", "scenes": 99}])[0] == 1),
        ("done.scenes absent tolerated -> 0",
         judge([F_SUITE, _scene("a", "PASS"), _scene("opt", "PASS"), _scene("cf", "FAIL"),
                _scene("ct", "TIMEOUT"), {"type": "done"}])[0] == 0),
```

（既有 F_DONE 的 scenes 数值若与其 fixture 记录数不符，一并修正到相等——终审早已指出该字段是 cosmetic，现在它有牙了。）instrument.py 的 self-test 若受 done.scenes 新规则影响（它的 done.scenes=执行数，本来就相等），跑一遍确认。

- [ ] **Step 3: t0.py CLI 泛化**

```python
    ap.add_argument("--run-task", default=None,
                    help="gradle run task (default :testkit-<loader>:runTestkitServer)")
    ap.add_argument("--results", default=None,
                    help="results JSONL path (default mc-testkit/<loader>/run-testkit/testkit-results.jsonl)")
```

launch()/provision()/judge 读处按参数覆盖默认（默认行为不变——两参数都缺省时与现完全一致）。run-dir 由 results 路径推导（`os.path.dirname`）。

- [ ] **Step 4: 验证**

Run: `python3 scripts/testkit/t0.py --self-test; echo t0=$?; python3 scripts/testkit/instrument.py --self-test; echo ins=$?`
Expected: t0 13/13 PASS exit 0；instrument 5/5 PASS exit 0。

Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540; echo "exit=$?"`
Expected: `VERDICT: GREEN` exit=0（默认路径回归）。

- [ ] **Step 5: Commit**

```bash
git add scripts/testkit/verdict.py scripts/testkit/t0.py
git commit -m "feat(testkit): judge cross-checks done.scenes (truncation gate); t0 --run-task/--results generalization"
```

---

### Task 3: agent-driver 挂载——依赖 + dogfoodServer + 钩子接线，骨架场景跑通

**Files:**
- Modify: `neoforge/build.gradle`（依赖 `:testkit-common` + `dogfoodServer` run 配置）
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentDriverNeoForge.java`（testkit.autorun 接线）

**Interfaces:**
- Consumes: TestkitCommon 的钩子方法（读 `mc-testkit/common/.../harness/TestkitCommon.java` 确认精确签名——onServerStarted(MinecraftServer)/每 tick 方法名以文件为准）。
- Produces: `./gradlew :neoforge:runDogfoodServer` 起一台同时载 agent_driver + testkit harness 的专服，`neoforge/run-dogfood/testkit-results.jsonl` 输出契约 v0 记录。

- [ ] **Step 1: 依赖**

`neoforge/build.gradle` dependencies 块加（对照本文件既有 `:common` 的声明形态选同款配置名——architectury 惯例是 `common(project(path: ':testkit-common', ...))` 与 `shadowBundle`/`implementation` 组合；以 `:common` 现有两行为模板逐字改造）：

```groovy
    implementation project(path: ':testkit-common', configuration: 'namedElements')
```

若 `:common` 用的是 `common(...)+shadowBundle(...)` 双行惯例，则对 `:testkit-common` 同样双行。**判据**：`./gradlew :neoforge:compileJava` 能解析 `net.magicterra.testkit.harness.TestkitCommon`，且 dogfood run 时 testkit 类在 classpath（Step 4 实证）。

- [ ] **Step 2: run 配置**

`runs { }` 块加（gameTestServer 之后）：

```groovy
        dogfoodServer {
            server()
            property 'testkit.autorun', 'true'
            runDir 'run-dogfood'
            vmArg '-Xmx2g'
        }
```

注意 neoforge 的 `runConfigs.configureEach` 有 contractServer 名字守卫——dogfoodServer **不需要**守卫（-Xmx2g 与 configureEach 一致，agent.runValidation 等属性无害），除非 Step 4 实证有冲突。

- [ ] **Step 3: AgentDriverNeoForge 接线**

构造器/事件注册处（对照现有 ServerStarting/ServerStarted/Tick 事件监听的写法）加 testkit.autorun 门：

```java
    private static final boolean TESTKIT_AUTORUN = Boolean.getBoolean("testkit.autorun");
```

- ServerStarted 事件体内：`if (TESTKIT_AUTORUN) { BotConfig.applyGameTestBaseline(); TestkitCommon.onServerStarted(event.getServer()); }`
- ServerTick(Post) 事件体内：`if (TESTKIT_AUTORUN) TestkitCommon.onServerTick(event.getServer());`
（方法名以 TestkitCommon 实文件为准；若它还有 gate 内部判断 testkit.autorun，外层门保留——双门幂等无害，且 agent-driver 侧的门让 baseline 应用与 harness 严格同条件。）

- [ ] **Step 4: 端到端——内建 5 场景在 dogfood 服上 GREEN**

Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl; echo "exit=$?"`
Expected: `VERDICT: GREEN` exit=0——5 内建场景（含金丝雀）在载有 agent_driver 的专服上原样通过。这证明：钩子接线对、双 mod 共存无干扰、编排器泛化可用。

同时验证隔离：`ps aux | grep -E '[t]estkit.autorun'` run 后为空；`neoforge/run-dogfood/agent-rpc.port` 存在（agent_driver 已加载的旁证）。

- [ ] **Step 5: Commit**

```bash
git add neoforge/build.gradle neoforge/src/main/java/net/magicterra/agent/neoforge/AgentDriverNeoForge.java
git commit -m "feat(testkit): agent-driver dogfood wiring — :testkit-common dep, runDogfoodServer, autorun hooks + baseline"
```

---

### Task 4: 迁移被吞三员为 agent-driver 场景 + provider 注册

**Files:**
- Create: `neoforge/src/main/java/net/magicterra/agent/neoforge/testkit/AgentDriverScenes.java`
- Create: `neoforge/src/main/resources/META-INF/services/net.magicterra.testkit.scene.SceneProvider`
- Modify: `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestTerrain.java`（三员方法 javadoc 加迁移注释，代码不动）

**Interfaces:**
- Consumes: Task 1 的 SceneProvider/level()/origin()/cleanup()；legacy 三员代码 `AgentGameTestTerrain.java:969-1020`（noop）、`:1032-1102`（watchdog+ascendCtx）、`:1112-1187`（diagonal）。
- Produces: 场景 `ad.ascendMovementNoop`、`ad.ascendDeadZoneWatchdog`、`ad.diagonalAscentSpeed`（required=true, canary=NONE）。

- [ ] **Step 1: services 文件**

`neoforge/src/main/resources/META-INF/services/net.magicterra.testkit.scene.SceneProvider` 单行：

```
net.magicterra.agent.neoforge.testkit.AgentDriverScenes
```

- [ ] **Step 2: AgentDriverScenes 骨架 + watchdog 场景（完整模板）**

移植映射规则（三场景通用）：`helper.getLevel()` → `ctx.level()`；绝对 cx/cz 坐标 → `ctx.origin()` 相对（arena 在 origin 附近铺设，直接用 `ctx.rel(dx,dy,dz)` 或 origin.offset）；`throw new GameTestAssertException(msg)` → `ctx.fail(msg)`；`helper.succeed()` → 正常返回；`gtOnlySkips` 首行 → 删除（testkit 门自己对账）；逐 key config save/restore → `BotConfig.pinnedBaseline()` + `ctx.cleanup(pin::close)`；avatar `create(...)` → `createUnique(...)` + `ctx.cleanup(av::discard)`（discard 方法名以 ServerPlayerAvatar 实文件为准，找 despawn/remove 等效物；若无现成方法，`fp.discard()` + manager 无关）。

```java
package net.magicterra.agent.neoforge.testkit;

import java.util.List;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneContext;
import net.magicterra.testkit.scene.SceneProvider;
// ... agent-driver imports 按需（ServerPlayerAvatar, LevelWorldView, Walker, Goal,
//     AscendMovement, MovementContext, MovementStatus, Move, BotConfig, AgentGameTestSupport 等）

/**
 * Dogfooded agent-driver scenes — first migration wave: the #85 swallowed trio.
 * Ported from AgentGameTestTerrain with identical in-body synchronous loop
 * semantics (scene bodies run synchronously on their first tick, same as the
 * legacy GameTest shell) so the old/new-shell A/B compares like with like.
 * Legacy @GameTest twins stay registered until 3 consecutive dual-gate greens.
 */
public final class AgentDriverScenes implements SceneProvider {
    @Override
    public List<Scene> scenes() {
        return List.of(
            Scene.of("ad.ascendDeadZoneWatchdog", 200, AgentDriverScenes::ascendDeadZoneWatchdog),
            Scene.of("ad.ascendMovementNoop", 200, AgentDriverScenes::ascendMovementNoop),
            Scene.of("ad.diagonalAscentSpeed", 200, AgentDriverScenes::diagonalAscentSpeed));
    }
    // Scene.of 的精确工厂签名以 Scene.java 为准（name, budgetTicks, body 或更多参）——
    // 用与内建场景相同的工厂；budget 200t 足够（body 同步执行，一 tick 内完成）。
```

watchdog 场景体 = `AgentGameTestTerrain.java:1032-1102` 的直译（完整写出）：5×5 pad 用 `ctx.floor(5, Blocks.STONE)` 或按 legacy 逐块 `ctx.setBlock`；`ServerPlayerAvatar.createUnique(ctx.level(), <origin 相对坐标>.getX()+0.5, ...)`；`ctx.cleanup(() -> fp.discard())`；`var pin = BotConfig.pinnedBaseline(); ctx.cleanup(pin::close);`；后续状态机驱动与断言逐行照抄，`GameTestAssertException` 全部换 `ctx.fail`。**注意 legacy 用 `AgentGameTestSupport.grantWaterEffects(fp)`——该类是 package-private static，跨包不可见：把 `grantWaterEffects` 提为 public static（一行改动，属允许的最小接线）或在 AgentDriverScenes 内复制该 4 行效果施加逻辑并注明来源。优先前者。**

- [ ] **Step 3: noop 与 diagonal 场景体**

同规则直译 `:969-1020` 与 `:1112-1187`：arena 铺设改 origin 相对；walker 循环（`for t<500/900 { walker.tick(av,w); av.step(); }`）原样进 body；`MovementContext.ALLOC_COUNT` 断言、`ascBps >= 2.5` 断言原样保留数值。config 逐 key save/restore 换 `pinnedBaseline()` + 显式 set 需要的 key（`BotConfig.allowBreak=false` 等直接字段赋值，与 legacy 相同的 key 集合与值）。

- [ ] **Step 4: legacy 三员打注释**

三个 @GameTest 方法 javadoc 首行加：`Migrated to testkit scene "ad.<name>" (P1c); kept for dual-gate A/B — delete after 3 consecutive dual-gate greens.` 代码零改动（gtOnlySkips 探针保留，legacy 门照常对账）。

- [ ] **Step 5: A/B——先 legacy 基线后新壳**

Legacy 显式基线（同 build）：
Run: `AGENT_GT_ONLY=ascendMovementNoopArena,ascendDeadZoneWatchdogArena,diagonalAscentSpeedArena ./scripts/run_gametests.sh` （前台，timeout 参数 600000）
Expected: 三员 PASS（对账门 GREEN）。

新壳：
Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl; echo "exit=$?"`
Expected: `VERDICT: GREEN` exit=0，JSONL 含 8 场景记录（5 内建含金丝雀 + 3 ad.*，吞金丝雀无记录），三个 ad.* 全 PASS。

若新壳红而 legacy 绿：按 A/B 分诊——先查移植误差（坐标/清理/pin 语义），再疑 harness 缝；**不许**调松断言数值让它过（那是丢失 A/B 意义），BLOCKED 上报。

- [ ] **Step 6: Commit**

```bash
git add neoforge/src/main/java/net/magicterra/agent/neoforge/testkit/ neoforge/src/main/resources/META-INF/services/ neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestTerrain.java
# grantWaterEffects 若提公有: git add neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTestSupport.java
git commit -m "feat(testkit): dogfood wave 1 — swallowed trio migrated to ad.* scenes (SceneProvider), legacy kept for dual-gate A/B"
```

---

### Task 5: 双门并行验收 + 文档

**Files:**
- Modify: `docs/testkit/orchestration-contract-v0.md`（附录：SceneProvider 与 done.scenes 对账两小节，语义澄清不 bump 版本）
- Modify: `mc-testkit/README.md`（dogfood 入口 + SPI 三行示例）
- Modify: `TODO.md`（P1c 条目：commit hashes、A/B 留痕、legacy 删除条件=3 轮双门全绿、残留=ResultsJsonl 异步化/彩票家族 P1.5）

**Interfaces:**
- Consumes: Task 1-4 全部。

- [ ] **Step 1: 双门并行全量验收**

Run: `./scripts/run_gametests.sh`（前台 timeout 600000）
Expected: legacy 全量门 GREEN（130 测试含三员，对账 SWALLOWED=空）。已知风险：underwaterBase 偶发挂死——若撞上，按病历 kill PID + `rm -rf neoforge/run-gametest/world` 重跑一次。

Run: `python3 scripts/testkit/t0.py --loader neoforge --wall 540 --run-task :neoforge:runDogfoodServer --results neoforge/run-dogfood/testkit-results.jsonl && python3 scripts/testkit/t0.py --loader neoforge --wall 540; echo "exit=$?"`
Expected: dogfood GREEN + 纯 testkit T0 GREEN，exit=0（双跑证明 provider 有无两种拓扑都健康）。

- [ ] **Step 2: 契约文档附录**

`orchestration-contract-v0.md` 尾部加两小节：①「SceneProvider（v0 附录）」——ServiceLoader 发现、名字全局唯一、内建在前 provider 在后、金丝雀仍由内建场景承担；②「done.scenes 对账」——footer 声明的执行数与记录数不符 ⇒ RED（TRUNCATED），缺字段容忍（前向兼容）。语义只收紧，版本仍 v0。

- [ ] **Step 3: README + TODO**

README 的 T0 节后补 dogfood 小节（run 命令 + SceneProvider 三行说明 + services 文件路径示例）。TODO.md 头部按现行风格加 P1c 条目（branch 名写 feature/executor-permove-ascend；含全部 task commits；A/B 双证据一行各；「legacy 三员删除条件=3 轮双门全绿」显式记录；残留清单）。

- [ ] **Step 4: Commit**

```bash
git add docs/testkit/orchestration-contract-v0.md mc-testkit/README.md TODO.md
git commit -m "docs(testkit): P1c dogfood wave 1 — SceneProvider appendix, done.scenes gate, dual-gate A/B record"
```

---

## Self-Review（计划自检记录）

1. **Spec 覆盖（P1c 切片）**：dogfood 迁被吞名单（§7 P1）→ Task 4；SPI 缝（§6.1 的 verb SPI 是 P2，但场景 SPI 是迁移的前置，归此）→ Task 1；arena 基建默认值之身体重置/坐标分配（§5）→ createUnique+cleanup+网格 origin（既有）；旧新壳 A/B（§10 风险册）→ Task 4 Step 5 + Task 5 Step 1；「迁一批删一批」推迟=头部偏差④，删除条件显式记录。
2. **占位符扫描**：watchdog 模板给出映射规则+完整结构；noop/diagonal 按同规则直译并点名行号与保留数值（500/900 循环、ALLOC_COUNT=0、ascBps≥2.5）；唯一开放点（Scene.of 工厂精确参数、TestkitCommon 钩子方法名、avatar discard 方法名）都指明了「以实文件为准」的读取对象——这是对既有代码的引用而非留白。
3. **类型一致性**：SceneProvider.scenes() 与 Scenes.all() 的合并类型一致；ctx.cleanup(Runnable) 与 pin::close/fp::discard 兼容；t0 的 --run-task/--results 在 Task 3/4/5 的调用串一致；场景名 ad.* 三处（AgentDriverScenes/legacy 注释/TODO）一致。
4. **风险入案**：双 mod 共存干扰（Task 3 Step 4 先用内建 5 场景独立验证）；ServiceLoader 在 architectury dev classpath 的发现（若 dogfood run 发现不了 provider——症状=JSONL 只有 5 场景——排查顺序：services 文件路径拼写→jar/classpath 归属→改用显式注册 API 兜底并 BLOCKED 上报）；grantWaterEffects 包可见性（Task 4 Step 2 显式处理）；walker 场景在共享世界的残留（cleanup 强制 + 网格 origin 天然隔离）；underwaterBase 挂死病历（Task 5 Step 1 应对写明）。

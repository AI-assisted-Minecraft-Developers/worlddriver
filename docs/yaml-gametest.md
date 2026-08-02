# YAML → GameTest 转译器 — 技术设计

> ⚠️ **交付机制已更新（P4-final）**：本文原按「@GameTest 内联 + GameTestServer 跑套件」
> 设计并落地（§7.1 / §10）。P4-final 退役了 GameTestServer 运行机器——`mc.test.yaml`
> 路由本身**保留**（它走 `DriverApi.route()`，与 GameTestServer 无关），现由 stagewright
> 正门（`scripts/stagewright/` 的 t0/t1/t2 + `instrument.py`）与 JS 校验套件驱动。下文对
> `@GameTest`/GameTestServer 交付形态的描述属于**历史设计记录**，保留以存档实现脉络。
>
> 状态：已落地（§10 进度表）；yaml 转译器面 = `mc.test.yaml` verb。
> 对应 proposal §4.1 C「GameTest Adapter」/ §5 Phase 2
> 前置依赖：`mc.world.snapshot` / `mc.world.restore`（已落地，commit 5255082）

## 0. 目标与非目标

**目标**：把声明式的 YAML 测试用例转译成 Mojang GameTest，使整合包/模组组合的回归测试可以
*声明而非编码*，并在 CI（P4-final 后 = stagewright 正门 `scripts/stagewright/`）和 `/test runall`
（开发期）里逐用例报告 PASS/FAIL。

**一句话契约**（沿用 addendum §3.1 的验证理念）：
> YAML 用例里只有「场景 + 动作 + 断言」三段业务语义，没有任何序列化/网络/线程的杂音——
> 因为每条动作和断言都落到现有的 `DriverApi.route()`，与 JS / WS / MCP 三路走同一个 Java 方法。

**非目标（本期不做）**：
- microtiming（逐 tick 方块变化捕获）—— 留待 Phase 3 的 Carpet 风格 tracker，断言层先留 `block_changed_within` 占位但不实现。
- 自定义 `.nbt` 测试结构的加载 —— 第一期所有用例都跑在 `seedTestArea()` 的程序化竞技场（y=200）里，
  `structure` 字段先解析、暂不消费（见 §3 的「结构」一节）。
- 多 client 并行跑批（proposal §4.1 C 末尾的 headlessmc 多实例）—— 属于 CI 编排，不是转译器职责。

## 1. 现状锚点（实现时据此对齐）

| 设施 | 位置 | 设计里怎么用 |
|---|---|---|
| 单一 dispatch 点 | `DriverApi.route(String method, Map params) -> Object` | 所有 setup 动作 / 断言取数都走它；不新开旁路 |
| setup/teardown 回滚 | `mc.world.snapshot` / `mc.world.restore`（`WorldApi`） | 每个用例跑前 snapshot、`finally` restore |
| 确定性竞技场 | `DriverApi.seedTestArea()`（y=200，`ORIGIN`） | 第一期所有 YAML 用例的坐标基准 |
| 既有 GameTest 范本 | `neoforge/.../AgentGameTest.java`（`agentRpcSmoke`） | 复用 worker-thread + `startSequence().thenWaitUntil` 防死锁模式 |
| 断言语义 | `common/.../test/TestContext.java`（throw `AssertionError`） | interpreter 的断言失败复用同一套异常归类 |
| 结构资源 | `neoforge/.../data/worlddriver/structure/empty.nbt` | `@GameTest(template="empty")` 仍需要它（哪怕 body 不碰） |

**关键约束（来自 `AgentGameTest` 的注释，必须遵守）**：`@GameTest` body 跑在 server 线程上；
而 YAML 动作里的 `mc.bot.*` 寻路、`mc.world.*` 都通过 `DriverApi.onServerThread()` 把活儿
塞回 server tick 队列、靠每 tick drain——**如果 body 阻塞 server 线程就会死锁**。因此 interpreter
必须在 worker 线程上跑用例主体，再从 tick 路径用 `thenWaitUntil` 轮询完成。

## 2. 模块布局

新增 `common/src/main/java/net/magicterra/worlddriver/test/yaml/`：

```
yaml/
├── YamlTestSpec.java        # 一个用例的 POJO（name/structure/timeoutTicks/setup/asserts）
├── YamlTestLoader.java      # data/worlddriver/gametests/*.yaml → List<YamlTestSpec>
├── YamlTestInterpreter.java # 跑一个 spec：snapshot → setup → asserts → restore，收诊断
└── AssertKind.java          # 断言动词枚举 + 每种的求值逻辑
```

GameTest 接入仍在 neoforge 模块（`@GameTestHolder`/`@GameTestGenerator` 是 NeoForge/Mojang 注解）：
在 `AgentGameTest.java` 里加一个 `@GameTestGenerator` 方法。

YAML 用例资源：`common/src/main/resources/data/worlddriver/gametests/*.yaml`（datapack 路径，
与现有 `scripts/agent_validation/*.js` 同一套 classpath 资源加载方式）。

## 3. YAML schema

对齐 proposal §4.1 C 的示例，最小可用 schema：

```yaml
# data/worlddriver/gametests/redstone_tick_stability.yaml
- name: "redstone-tick-stability"      # 必填，唯一；映射成 GameTest 名 worlddriver:redstone-tick-stability
  structure: null                       # 第一期忽略；为 null/缺省时跑 seedTestArea() 竞技场
  timeout_ticks: 200                    # 默认 200
  region:                               # 该用例 snapshot/restore 的盒子（相对 ORIGIN 或绝对坐标）
    from: [-5, 200, -5]
    to:   [ 5, 205,  5]
  setup:                                # 动作序列，按序执行；每条 = 一次 route 调用
    - place: { pos: [2, 201, 2], type: "minecraft:redstone_block" }
    - run_command: "setblock 3 200 3 minecraft:repeater"
    - wait_ticks: 4
  asserts:                              # 全部为 AND；任一失败 → 用例 FAIL
    - block_present: { pos: [3, 201, 3], type: "minecraft:*" }
    - no_exception_in_log: true
    - tps: { min: 18.0 }
```

**字段说明**

| 字段 | 类型 | 默认 | 语义 |
|---|---|---|---|
| `name` | string | — | 必填，唯一；GameTest 注册名 |
| `structure` | string\|null | null | 第一期解析但不消费（见下） |
| `timeout_ticks` | int | 200 | 传给 `@GameTest`/TestFunction 的 timeout |
| `region.from/to` | [x,y,z] | 必填（有 snapshot 时） | snapshot 盒子，受 32³ 体积上限约束 |
| `setup` | list | [] | 动作序列，见 §4 |
| `asserts` | list | 必填非空 | 断言序列，见 §5 |

**坐标约定**：第一期用**绝对坐标**（直接喂给 `mc.world.*` / `mc.action.*` 的 `pos`），
基准是 `seedTestArea()` 的 `ORIGIN`（y=200 竞技场）。`structure` 落地后再引入「相对结构原点」的
坐标变换——届时在 schema 里加 `coords: relative|absolute`，默认保持 absolute 以兼容已写用例。

**`structure` 为何先不消费**：现有 `@GameTest` 都用 `empty.nbt` + 自建 y=200 竞技场，已被
33 个 JS 套件验证稳定。引入真实 `.nbt` 结构会牵出结构原点平移、`GameTestHelper` 相对坐标系、
以及 `RelativeBlockPos` 换算，属于独立增量，不应和转译器骨架耦合。第一期把字段解析进 POJO、
留 TODO，确保 schema 向前兼容。

## 4. setup 动作 → route 映射

每条 setup 动作是一个单键 map，键名即动作，值即参数。interpreter 把它直接翻成一次
`api.route(method, params)`：

| YAML 键 | route method | 说明 |
|---|---|---|
| `place` | `mc.action.placeMany` | 单块也走 placeMany：`{blocks:[{pos,type}]}`。**没有 `mc.action.placeBlock` route**——单块放置已并入 `placeMany`（核对自 `DriverApi` routes 表，2026-06-01） |
| `fill` | `mc.action.fill` | `{from, to, type}` |
| `place_many` | `mc.action.placeMany` | `{blocks:[{pos,type}, ...]}`（元素形状核对自 `ActionApi.placeMany`） |
| `run_command` | `mc.action.runCommand` | 字符串值 → `{cmd: "..."}`（参数键是 `cmd`，核对自 `DriverApi` 与 prelude） |
| `wait_ticks` | `mc.system.waitTicks` | 整数值 → `{ticks:N}`。**已有 route**（JS 套件里 `Agent.system.waitTicks` 在用），interpreter 直接调它即可，无需自建 sleep |
| `bot` | `mc.bot.<sub>` | `{do: "goto", ...}` 形式，转 `mc.bot.goto` 等（覆盖需要 bot 动作的用例） |

设计原则：**不为 YAML 发明新动词**——能映射到已有 route 的就映射，映射不到的（如 `wait_ticks`）
才作为 interpreter 内建。这样 YAML 能用的能力 == MCP 工具能用的能力，零额外维护面。

## 5. 断言动词（最小集）

每条断言同样是单键 map。interpreter 对每种动词调一次只读 route 取数后判定：

> **注**：`mc.observe.area` 已并入 `mc.query`（`{q:"blocks", center, filter:{in_radius, type?}}`），
> 没有独立的 `observe.area` route——下表取数一律走 `mc.query`（核对自 routes 表 + `02_observe_area.js` 注释）。

| YAML 键 | 取数来源 | 判定 |
|---|---|---|
| `block_present` | `mc.query{q:"blocks", center:pos, filter:{in_radius:0, type:...}}` | 该 cell 方块 type 匹配（支持 `mod:*` 通配） |
| `block_absent` | `mc.query{q:"blocks", center:pos, filter:{in_radius:0}}` | 该 cell 为 air 或不匹配给定 type |
| `entity_present` | `mc.query{q:"entities", filter:{in_radius}}` | 至少一个实体 type 匹配 |
| `no_exception_in_log` | interpreter 包裹用例执行期间的 log 捕获 | 期间无 ERROR/异常栈（见下） |
| `tps` | server `MinecraftServer.getTickTimes()` 均值 | `1000/meanMs >= min` |
| `block_changed_within` | **占位，不实现** | 抛 `UnsupportedOperationException("microtiming: Phase 3")` |

**通配匹配**：`minecraft:*` / `mekanism:*` 用前缀匹配（`type.startsWith(ns + ":")`），
精确名走 `equals`。

**`no_exception_in_log`**：第一期实现为「在用例 setup+asserts 执行窗口内，挂一个临时
`org.apache.logging.log4j` appender 收集 `ERROR` 及以上级别记录；窗口结束移除」。比扫文件简单、
线程安全、无 IO。检测到任何 ERROR → 断言失败并把首条记录摘要带进诊断。

> ⚠️ 这个 appender 是 **JVM 全局**的——它会捕获该时间窗口内*整个进程*的 ERROR，不只当前用例。
> 因此它的语义正确性**依赖 §7.1 的串行执行**：串行跑时窗口内只有当前用例在动，捕到的 ERROR
> 必然归属本用例。若将来改成并行跑，这个断言会误抓邻居用例的日志，必须改用 per-test 的
> `ThreadContext`/MDC 过滤或  logger name 范围限定。

**`tps`**：`MinecraftServer.getAverageTickTimeNanos()`（1.21 用 `getTickTimesNanos()`/
`getAverageTickTime()`，实现时按实际 API 名取）。GameTest 环境 tick 不限速，正常应稳定 20，
低于 `min` 说明某 mod 组合拖慢了 tick——这正是回归测试要抓的。

## 6. interpreter 执行流（单个 spec）

```
runSpec(spec):
    box = spec.region
    snapId = api.route("mc.world.snapshot", {from: box.from, to: box.to}).id   # 仅当有 region
    logCapture = attachErrorAppender()                                          # for no_exception_in_log
    try:
        for action in spec.setup:
            applyAction(action)        # → api.route(...) 或内建 wait_ticks
        for assertion in spec.asserts:
            evalAssert(assertion)      # 失败 throw AssertionError（复用 TestContext 语义）
    finally:
        detach(logCapture)
        if snapId: api.route("mc.world.restore", {id: snapId, discard: true})   # 永远回滚
```

`restore` 放 `finally`——即使断言失败也回滚，保证用例之间**互不污染**（这正是 snapshot/restore
作为 Phase 2 前置的全部意义）。`discard:true` 让快照用完即弃，避免 64 份上限被多用例打满。

## 7. GameTest 接入：`@GameTestGenerator`（逐用例粒度）

不要再写第二个「巨型单 `@GameTest` 内部跑全套」的方法——那样所有 YAML 用例会挤进一个
sub-test，失败定位差。改用 Mojang 原生的 `@GameTestGenerator`：一个返回
`Collection<TestFunction>` 的方法，运行期为每个 YAML spec 生成一个独立 `TestFunction`，于是
`/test runall`（历史上也含 GameTestServer 跑批，该机器已于 P4-final 退役）里**每个 YAML 各占一个 sub-test**。

```java
// neoforge/.../AgentGameTest.java （新增方法，与 agentRpcSmoke 并存）
@GameTestGenerator
public static Collection<TestFunction> yamlTests() {
    List<YamlTestSpec> specs = YamlTestLoader.loadAll();   // data/worlddriver/gametests/*.yaml
    List<TestFunction> fns = new ArrayList<>();
    for (YamlTestSpec spec : specs) {
        fns.add(new TestFunction(
            "agent_yaml",                         // batch
            "worlddriver:" + spec.name(),        // test name → 报告里逐条
            "empty",                              // structure template（同 agentRpcSmoke）
            Rotation.NONE,
            spec.timeoutTicks(),
            0L,                                   // setupTicks
            true,                                 // required
            helper -> runYamlAsGameTest(helper, spec)
        ));
    }
    return fns;
}

// body 复用 agentRpcSmoke 的防死锁模式：worker 线程跑 interpreter，tick 路径轮询完成
private static void runYamlAsGameTest(GameTestHelper helper, YamlTestSpec spec) {
    WorldDriverCommon.api().seedTestArea();
    AtomicReference<Throwable> crash = new AtomicReference<>();
    AtomicBoolean done = new AtomicBoolean(false);
    Thread worker = new Thread(() -> {
        try { new YamlTestInterpreter(WorldDriverCommon.api()).runSpec(spec); }
        catch (Throwable e) { crash.set(e); }
        finally { done.set(true); }
    }, "WorldDriver-YamlTest-" + spec.name());
    worker.setDaemon(true);
    worker.start();
    helper.startSequence()
        .thenWaitUntil(() -> {
            Throwable c = crash.get();
            if (c != null) throw new GameTestAssertException(spec.name() + ": " + c.getMessage());
            if (!done.get()) throw new GameTestAssertException("still running");
        })
        .thenSucceed();
}
```

> `TestFunction` 的构造参数顺序按 1.21.1 实际签名为准（实现时核对
> `net.minecraft.gametest.framework.TestFunction` 的 record 组件）。`@GameTestGenerator`
> 来自 vanilla（`net.minecraft.gametest.framework.GameTestGenerator`），NeoForge 的
> `@GameTestHolder` 类里同样会被扫描。

**风险**：`@GameTestGenerator` 在 NeoForge 1.21.1 的扫描时机若与预期不符（proposal §6.2 风险 1
已列「NeoForge GameTest 文档不充分」），退路是回到「单 `@GameTest` 内部 for-loop 跑全部 spec」
的保守形态，牺牲逐条粒度但保证能跑。实现时**先验证 `@GameTestGenerator` 能被发现**再铺用例。

### 7.1 并发安全：已确认问题，第一期改走「单 gametest + 路由」

**已核实（2026-06-01）**：`GameTestRunner` 接收 `Collection<GameTestBatch>` + `StructureSpawner`，
`StructureUtils.clearSpaceForStructure` 把各结构在世界里**网格铺开并发 tick**——同一批 GameTest 是
**并行执行**的，不是串行。

这比原先设想的更棘手：本工程的 `seedTestArea()` 和所有 setup/assert 用的是**绝对坐标**
（`ORIGIN = (0,200,0)`），**没有**用 `GameTestHelper` 的相对原点。所以无论框架把每个 `@GameTest`
的结构摆在哪，测试体都在敲打同一组绝对 cell。后果：**任何**第二个 `@GameTest`——无论用
`@GameTestGenerator` 逐用例生成还是手写——都会和现有的 `agentRpcSmoke` 在相同绝对 cell 上并发打架。
框架自带的空间隔离（相对坐标）被「用绝对坐标」这一既有约定抵消了。

**第一期落地决定**：**不新增任何 `@GameTest`**。改为在**现有的单个 `agentRpcSmoke` gametest 内部**、
通过 `mc.test.yaml` 路由跑 YAML 用例——它本来就在 worker 线程上**串行**跑整个 JS 套件。具体地，
`34_yaml_gametest.js`（属于该套件）调用 `mc.test.yaml{inline|file|all}`，于是：

- YAML 全流程（parse → snapshot → setup → asserts → restore）在已串行的上下文里执行，零并发风险；
- 不引入与 `agentRpcSmoke` 抢占绝对 ORIGIN 的第二个并行 gametest；
- 逐用例 PASS/FAIL 仍可见——`mc.test.yaml` 返回 `{results:[{name,pass,failures}], passed, failed}`，
  失败时把 spec 名 + 失败断言带进 JS 断言消息。

这正是原 §7 列的「保守退路」，只是实现为「路由内联」而非「单 `@GameTest` for-loop」，从而避免新增
gametest 与现有套件并发。

**`@GameTestGenerator`（逐用例独立 gametest）推迟到**：YAML 用例改用 `GameTestHelper` 相对坐标或
按 index 做空间偏移、从而能安全并发之后。届时每个 spec 才能成为 `/test runall` 里独立的 sub-test。
在那之前，§7 顶部那段 `yamlTests()` 生成器代码是**目标形态参考，尚未启用**。

> 与 §5 `no_exception_in_log` 的关系不变：串行执行天然让「全局 appender 窗口内只有当前用例在动」成立
> ——只是这条断言本身仍未实现（见 §5）。

## 8. YAML parser 依赖

classpath 上目前**没有** YAML 解析器。引入 `org.yaml:snakeyaml`（~300KB，无传递依赖）：

- `common` 用 `compileOnly`；`neoforge` 用 `shadowBundle` + `forgeRuntimeLibrary`，`fabric` 用
  `shadowBundle` + `implementation`——与 netty 完全相同的运行时可见性套路。
- **relocate**：两个平台的 `shadowJar` 各加一条 `relocate 'org.yaml.snakeyaml',
  'net.magicterra.worlddriver.shaded.snakeyaml'`。注意：现有的 Rhino/netty 其实**并没有** relocate
  （Rhino 是独有包名的 fork、netty 与 MC 自带版本兼容），snakeyaml 是本工程**唯一**被 relocate 的
  依赖，因为它是高撞包风险库。relocate 对源码透明（照常 `import org.yaml.snakeyaml`）；dev 运行
  用未 relocate 的 `forgeRuntimeLibrary`/`implementation`，所以
  **relocation 不被 dev CI 覆盖**，只在 remap 后的 prod jar 里生效。
- 用 `SafeConstructor` 读成 `List<Map<String,Object>>`（只产出标准类型，绝不实例化任意 Java 对象），
  再手写映射到 `YamlTestSpec`——不接 snakeyaml 的反射式 bean 绑定。

> 备选：若不愿加依赖，可让用例写成 JSON 复用现有 `JsonCodec`。但 proposal §4.1 C 明确以 YAML 为
> 交付形态（人写、可注释），且 snakeyaml 体积可忽略，故选 snakeyaml。

## 9. 验证用例（铁律：每加能力配一个测试）

1. `common/.../scripts/agent_validation/34_yaml_gametest.js` —— JS 层断言 `YamlTestLoader` +
   `YamlTestInterpreter` 对一个内置样例 spec 的 round-trip（load → run → 断言结果 / 断言 restore
   后区域复原）。把 `runValidation()` 名单从 33 → 34，套件 sub-test 数随之 +1。
2. 一个真实样例 YAML：`data/worlddriver/gametests/smoke_place_observe.yaml`——
   `place` 一个 cobblestone → `block_present` 断言它在 → restore 还原。端到端跑通
   校验套件，确认它作为独立 sub-test 出现且 PASS。

## 10. 落地顺序与实际进度

1. ✅ 加 snakeyaml 2.4 依赖 + relocate（gradle.properties / common / fabric / neoforge），`:common:compileJava` 通过。
2. ✅ `YamlTestSpec` + `YamlTestLoader`（`index.txt` 清单 + `SafeConstructor`）。
3. ✅ `YamlTestInterpreter` + `AssertKind`，snapshot→setup→asserts→restore；已实现 setup
   `place`/`place_many`/`fill`/`run_command`/`wait_ticks` 与 assert `block_present`/`block_absent`/
   `entity_present`。`mc.test.yaml` 路由（`inline`/`file`/`all`）作为对外入口。
4. ✅ **未走 `@GameTestGenerator`**（§7.1：已确认同批 gametest 并发 + 绝对 ORIGIN 会撞）。改由
   `34_yaml_gametest.js`（5 个 sub-test：inline / region-restore / file / all-index / 失败上报）
   在现有 `agentRpcSmoke` 内串行跑 `mc.test.yaml`。校验套件 **65/65 PASS**（当时经已退役的
   GameTestServer 跑批；含 `smoke_place_observe.yaml` 经 file/all 路径端到端跑通）。
5. ⏳ 待办：`tps`（cheap，需 `MinecraftServer.getAverageTickTimeNanos()`）、`no_exception_in_log`
   （log4j appender）、`block_changed_within`（microtiming，Phase 3）——当前为「识别但抛
   `UnsupportedOperationException`」，用到即响亮失败。
6. ⏳ （Phase 2 后续，非本设计）按 `/home/coder/magic-server-modpack/TODO.md` 已确认 bug 写真实回归集。

> 实现期发现的接口订正（已回写本文）：无 `mc.action.placeBlock` / `mc.observe.area` route（分别并入
> `placeMany` / `mc.query`）；`runCommand` 参数键是 `cmd`；`mc.query` 返回 **List** 不是 Map（解释器
> 用 `api.route` 取原始结果，不能套用「全部当 Map」的 helper——这是初版唯一的真 bug）。

## 11. 与提案的对账

落地后回写 `minecraft-worlddriver-proposal.md`：§0 状态表 §4.1 C 行从「⚠️ 部分」推进，
§5 Phase 2 的「YAML 转译器 ❌」勾掉；CHANGELOG `[Unreleased] / Added` 记一条。
microtiming、magic-server 回归集、headlessmc CI 仍留 ❌，是 Phase 2 收尾的后续项。

## 12. 可靠 gameplay 断言写作指南（来自外部消费者反馈 2026-06-04，坑均为实测）

写 headless（`runServer` / 测试用专服 / 无人登录的专服）测试脚本时，下面每一条都
曾让真实消费者烧掉数小时——世界**看起来**活着（命令能跑、实体能召唤、query 有返回），
但静默地**不在模拟**，所有症状都像被测 mod 的 bug。

### 12.1 无玩家 ⇒ 没有 chunk 达到 entity-ticking ⇒ 召唤的怪全冻结（最高频坑）

专服没人登录时，任何 chunk 都到不了 entity-ticking ticket 等级。后果（全部静默）：

- MobEffect 时长**不倒数**→ 永不过期，`MobEffectEvent.Expired` 不发、`applyEffectTick` 不跑;
- 无 AI、**无重力**（y=200 的怪悬空不落）、不白昼燃烧、不 despawn、poison/wither 不掉血;
- **但** `/summon`、`/effect give`、`/damage` 及其触发的事件 handler（如 `LivingDamageEvent`）
  **全部正常**——它们由命令同步驱动，不走 tick 循环。于是"命中即爆"类测试绿、
  "到期自爆/计时"类测试神秘地红。

**脚本修法**：召唤前 `/forceload add <cx> <cz>` 实体所在 chunk（chunk `0 0` 覆盖方块 0–15），
实体放进该 chunk;收尾 `/forceload remove`。实测：有 forceload 时 poison 掉血、自定义效果
expiry 触发;没有时同一套 setup 完全惰性。

### 12.2 无玩家 ⇒ `OnDatapackSyncEvent` 不发 ⇒ 惰性配置系统全是默认值

Iron's Spellbooks 族（及一切"datapack-sync 时才 build 配置"的系统）在玩家登录或 `/reload`
时才解析配置。headless 启动后直接断言 → 读到的全是参数默认值（例：自定义 school 的法术
伤害类型解析成默认 `evocation_magic`）。**脚本修法**：boot 后先跑一次 `/reload` 再断言。

### 12.3 无敌帧（i-frames）：连续 `/damage` 第二发静默 no-op

实体被 `/damage` 后 ~10 tick 免伤。背靠背两发 `/damage` 只有第一发生效。
**修法**：每次断言用新目标，或两发之间 `mc.system.waitTicks`。
（对被测 mod 的设计含义：任何"造成伤害后立刻 AoE/链式补伤"的机制必须绕过受害者 i-frames。）

### 12.4 难度缩放让绝对伤害不确定

带 `scaling: when_caused_by_living_non_player` 的伤害类型受难度影响（`/damage 6` 实际打 5）。
**修法**：测试开头 `/difficulty normal` 固定难度，断言**比例/差值**而非绝对 HP。

### 12.5 实体汤（entity soup）

同一竞技场格反复 summon 会累积尸体/残留实体污染 count 断言。
**修法**：每个断言用独立 tag + 独立坐标，收尾 `kill @e[tag=…]`;或用
`mc.world.snapshot`/`restore` 的区域复原模式管理场地（实体不在 snapshot 内，仍需手动 kill）。

### 12.6 读回自己的改动用哪条路

- 命令输出/结果值：`mc.action.runCommand` 返回 `{ok, success, value, feedback[]}`——
  `data get`/`execute if`/`seed` 的输出都在里面（2026-07-06 起）。
- 单格 blockstate/光照/BE NBT：`mc.world.block {pos, nbt?}`。
- 区域扫描：`mc.query q:"blocks"`（行含 `state` 属性 map）;实体含 `effects`/`uuid`，
  `filter.is_living` 排除掉落物污染。

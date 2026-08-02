# stagewright：跨加载器 Minecraft mod 测试框架 — 设计 spec

日期：2026-07-16
状态：待 user 审批（批准前不写实现）
前置任务：task#52（自造测试框架）、task#85（GameTest 静默吞测试 P0）
关联 spec：2026-07-14-scheduler-semantics-phase1-design.md（#54 验证底座章节由本 spec 接管）

---

## 1. 定位与动机

**stagewright 是面向 mod 开发者的跨加载器（Fabric + NeoForge）测试框架产品**，提供测试编排、断言语法接口与三种测试拓扑；worlddriver 是它的第一个用户（self-hosting）——本项目 ~130 个既有 GameTest 迁移到其上。

### 1.1 生态空缺（产品正当性）

- vanilla GameTest：仅服务端；调度黑盒（本仓库实证其三宗病：#85 defaultBatch 静默吞测试计 PASS、并发 arena 绝对坐标撞车、timeoutTicks 对 tick 内死循环无效）。
- Fabric client gametest（fabric-client-gametest-api-v1）：MC 1.21.2+ 才有、Fabric 独占；本项目锁 1.21.1（已核实 fabric-api 0.116.4+1.21.1 无该模块）。
- NeoForge：无客户端测试 API。
- **无任何框架能测「真客户端连专用服务器」的生产拓扑。**

### 1.2 本仓库测试框架的病历（重构的直接动因）

四层结构性问题，全部有实证：

1. **门的诚实性破产**：#85 静默吞测试（注册却从不执行、计 PASS，被吞名单历史 GREEN 全不可信）；自造 reporter TOTAL 行与 vanilla required 计数器独立（TOTAL 全绿 + BUILD FAILED 并存）；无机器可读执行清单可对账。
2. **三源共享可变状态 → 非确定性彩票**：共享单例 FakePlayer 身体（#48，失败集每轮换人、solo红↔suite红双向）；共享持久世界（跨 run 持久化 + 绝对坐标硬编码，坐标撞车即假 RED）；服务器线程负载耦合（client 开着跑 suite，flaky 1→3）。
3. **生命周期盲区**：被杀 run 污染持久世界 → 下次 run 在 ChunkMap.processUnloads 单 tick 死循环挂死，GameTest 超时无效；僵尸 JVM 攥 session.lock；清场全靠人肉规矩。
4. **代码结构病**：AgentGameTestServer.java 5220 行巨类；~130 处手抄 `gtOnlySkips("name")` guard；cx/cz 硬编码无分配器；私有 arena builder 重复；测试代码住 `neoforge/src/main`（在生产 jar 里）；fabric 侧零测试基建。

根因综合：框架把编排全权交给 vanilla GameTestServer，而其模型（structure 模板、相对坐标、短时确定性测试、无跨测试状态）与本项目用法（程序化绝对坐标 arena、长时物理仿真、共享 driver 状态）每一条都不成立。

---

## 2. 分层架构

```
策略层        LLM / JUnit 场景 / JS 场景              （进程外或 Rhino）
stagewright    编排 + 断言 DSL + harness + gradle 插件  （新产品）
worlddriver  驱动层：观察/动作/客户端输入/屏幕内省/
              RPC 通道/事件/wait —— 仪表原语唯一提供者  （既有 mod）
Minecraft     fabric / neoforge，1.21.1
```

**依赖方向铁律：stagewright → worlddriver，永不反向。** worlddriver 的定位是通用 agent 驱动层（观察 + 动作 + 客户端输入注入 + RPC + Rhino），客户端仪表（ClientInput/ScreenIntrospection/ClientObserve）是驱动层本职，不抽取。第三方 mod 开发者依赖两个 jar（maven 传递），得到「通用驱动层 + 测试编排层」。

### 2.1 模块划分（已拍板：本仓库平级新项目 `stagewright/`）

| 模块 | 形态 | 职责 |
|---|---|---|
| `core` | 多加载器 common 库 | 测试模型、断言 DSL、JSONL 结果 schema。由既有 `common/src/main/java/net/magicterra/worlddriver/test/`（ScriptTest/TestContext/yaml）迁出演化，非绿地 |
| `runtime` | 薄 mod（common+fabric+neoforge） | 游戏内 harness：`@SceneTest` 注解发现与串行调度、arena builder/坐标分配器、身体进场重置、执行清单；经 `DriverApi.addRoute` + schema SPI 注册 `mc.test.*` verbs |
| `orchestrator` | Python CLI（已拍板：Python 先行，gradle-plugin 后包同一契约） | 进程级编排：拓扑启动矩阵、世界生命周期、watchdog、JSONL 聚合、对账门 |
| `gradle-plugin` | Gradle 插件（P3） | `stagewrightServer` / `stagewrightClient` / `stagewrightE2E` 任务；testmod source set 惯例；shell 同一编排契约 |

**编排契约先冻结**（P1 定稿，Python 与后来的 gradle-plugin 共用）：JSONL 结果格式、退出码语义、端口文件发现协议（`worlddriver-rpc.port`）、进程启动/终止协议、世界模板协议。

---

## 3. 三拓扑 × 三授权形态

### 3.1 拓扑

| 拓扑 | 壳 | 用途 |
|---|---|---|
| **T0 server-only** | 普通 headless 专用服务器（两 loader 完全对称） | 批量行为回归（arena 测试）。金字塔底座，跑得起最大量 |
| **T1 integrated-client** | xvfb 下真客户端进单机世界 | UI/屏幕交互、client-only 路径（GUI 容器、screen watchdog、chat、death screen、LookController/AvatarInput 缝、客户端反射） |
| **T2 dedicated+client** | 专服 + 真客户端连入 | 生产拓扑（SurvivalTest 同构）；server/client avatar 行为缝；专服特有行为（ESC 不冻结等） |

**T0 不用 GameTestServer**：harness 自研调度后，T0 只需「专服 + mod + 启动参数触发 harness + 退出码」。vanilla GameTest 的调度病从存在上消除；fabric 侧（现零测试基建）与 neoforge 同一入口。GameTestServer 仅迁移过渡期保留。

**金字塔纪律**：T1/T2 每场景付真客户端成本，只放高价值缝测试与 UI 测试（各 10-30 个量级）；批量回归留 T0。

### 3.2 授权形态（mod 开发者可选，共用断言语义）

**① 游戏内 `@SceneTest`**（tick 精确，服务端逻辑回归）：

```java
@SceneTest(budgetTicks = 400)
public static void furnaceKeepsFuel(SceneContext ctx) {
    ctx.arena().floor(11, Blocks.STONE)          // 坐标由分配器给，只写相对坐标
       .place(rel(5, 1, 5), Blocks.FURNACE);
    ctx.player().giveItem(Items.COAL, 3);        // 进场即重置好的隔离身体
    ctx.await(() -> furnaceLit(ctx))             // continuation 式，不阻塞 tick
       .within(200)
       .then(() -> ctx.assertBlock(rel(5,1,5)).propertyIs(LIT, true));
}
```

注册在 common 层由 runtime 注解扫描发现，两个 loader 同一套测试代码。

**执行模型铁律**：`@SceneTest` 体跑在 server 线程 tick 内；体内调仪表 verb 安全（`onServerThread` 同线程内联执行，DriverApi:627 已核实），但**禁止任何阻塞等待跨线程 future**（如带长 awaitMs 的 `wait.*`）——只许 `ctx.await()` continuation，违反即死锁（既有 GameTest 死锁病的同款机制）。harness 对体内阻塞加看门狗侦测。

**② 进程外 JUnit 5 场景**（已拍板：JUnit 5 基座）——测试体跑在游戏进程外普通 JVM，经控制通道拿类型化代理；断点、断言报告、CI 集成白拿。**拓扑生命周期采用 attach 模式契约**：JUnit 扩展读 `TESTKIT_ENDPOINT`（编排器起好拓扑后写出的端点描述文件）——已设则附着，未设则 fail-fast 并提示先跑对应 gradle/编排器任务。IDE 里"点一下全自动起拓扑"要到 P3 gradle-plugin 内嵌启动逻辑后才成立，此前 IDE 单跑需先手动起一次拓扑（attach 后可反复跑）：

```java
@StageWrightScenario(topology = DEDICATED_PLUS_CLIENT, world = "template:flatstone")
class FurnaceScreenTest {
    @Test void openAndSmelt(Client client, Server server) {
        server.exec("give @p iron_ore 4");
        client.player().rightClick(server.blockAt(FURNACE_POS));
        client.screen().assertOpen(FurnaceScreen.class)
              .slot(0).drop(Items.IRON_ORE, 4);
        server.await(() -> smeltStarted(server)).within(Duration.ofSeconds(10));
        client.screenshotOnExit();
    }
}
```

**③ 游戏内 JS/Rhino 场景**（轻量 smoke）：驱动层内嵌 Rhino，既有 ScriptTest 模式收编延续；现 132 个 JS 验证脚本跑法不变。

**断言 DSL 要点**：方块/实体/背包/玩家状态断言；屏幕断言（screenTree 基座）；一等**事件流断言**（`assertEvents().next("block.break").within(40)`——替代现在 python grep 日志的事件对账实践）；失败自动截图（T1/T2）；`await().within().then()` continuation（游戏内形态）与阻塞式（进程外形态）双风格同语义。

---

## 4. 信任链（self-hosting 循环的设防）

worlddriver 用 testkit 自测，而 testkit 依赖 worlddriver——需防「驱动层回归让测试栈连坐失明」。**驱动层 API 面一分为二**：

- **仪表面**（testkit 依赖）：route dispatch、观察读、客户端输入注入、屏幕内省、wait/事件、直接世界操作（tp/setblock/give）。简单、确定、低变更。
- **行为面**（被测对象）：寻路、战斗、进程链、反射。testkit 对其**零依赖**。

规则：

1. **testkit 基建路径禁用行为面**：setup 就位用 tp、建造用直接写世界，绝不经 walker/goto。verb 按命名空间分级（instrumentation-grade / behavior-grade）并写进 mod 开发者文档：场景 setup 只许用前者，否则「你的测试在测你的 setup」。场景本质需要行为时（战斗测试要 walker 追击），行为即被测对象，失败是有效信号非基建失明。
2. **仪表契约套件**兜仪表面：30-50 条、裸 RPC 直打 `route()`、独立于 testkit 断言栈、秒级确定性。含**观察保真度双源对账**（RPC 读数 vs 服务端 ground-truth 直读逐项比对）；历史仪表 bug 逐条沉淀为永久契约断言（#41 observe 9/36 槽、#42 耐久不可见、#45 攻击冷却零暴露、#55 伤害源不可见）。这是既有铁律「verb 级改动必过 raw-RPC」的制度化。
3. **信任链单向**：仪表契约绿 → testkit 断言/setup 可信 → 行为面全量测试可信。寻路/战斗只出现在链末端当被测对象。
4. **live/replay 地位不变**：始终是行为面的真相层，arena/场景只是回归卫士。T1/T2 是把 live 人肉验证中可重复的部分自动化，不是替代 live。

---

## 5. 编排器职责（坑史的制度化）

| 职责 | 对应病历 |
|---|---|
| 拓扑×loader 启动矩阵，PID 追踪启停（禁 pkill 内建） | 僵尸 JVM 攥 session.lock、pkill 大小写抓空 |
| 世界模板仓库 + 每 run 拷贝→跑→删 | 持久世界污染挂死、跨 run 残留假 RED |
| 端口文件发现（读各进程 `worlddriver-rpc.port`，ephemeral 回退已内建于驱动层） | 多进程拓扑、并行 run 端口冲突 |
| 进程外墙钟 watchdog（tick 心跳 + 硬超时强杀，判该测试 FAIL） | ChunkMap 单 tick 死循环烧 71 分钟、GameTest timeout 盲区 |
| GL hang 签名自愈（TitleScreen 等待型自动点入） | 「GL hang」误判重启 |
| 统一时间控制（`/tick freeze` 单源，屏蔽 integrated/dedicated 差异） | ESC 语义两拓扑不同 |
| 客户端进程池（quit-to-title→重进世界复用，免每场景冷启数十秒） | 客户端启动慢 |
| xvfb/DISPLAY 管理 | headless CI |
| **单源 JSONL + 「注册数=执行数」对账门** | #85 静默吞 |
| required 语义单源（退出码由编排器判定） | TOTAL 行假绿 |
| **金丝雀自测**：套件内置必红/必超时/必吞（注册但标记不执行）三只哨兵，每轮全量必须把它们判成对应的 FAIL/TIMEOUT/RECONCILE-MISS，否则**门本身判死**、整轮结果作废 | 对账门/watchdog 自身回归 = 元盲区（#85 的教训反着用：验证"框架能抓住失败"这件事本身） |

**arena 基建默认值**（runtime 侧，同为坑史制度化）：坐标分配器（撞车假 RED）、身体进场重置（共享身体彩票，串行复用单具身体 + 显式 reset，绕开每测新建 ServerPlayer 的成本坑）、rig 护栏三件套内建于 builder（跑飞缓冲台 / config pin / reached 判定带 y 下限——虚空坠落 rig 病家族）。

结果 JSONL 统一格式：`{tier, name, entered, result, ticks, wallMs, reason, screenshots[]}`。

---

## 6. worlddriver 侧改动清单

1. **verb 扩展点公共化**：`addRoute()` 已是公共缝；补 ToolCatalog schema SPI（`requireSchemasFor` 开机不变量对第三方 verb 放行的唯一合理路径），命名空间约定（`mc.test.*` 归 testkit-runtime；第三方 `modid.*`）。测试 verbs 经声明白拿统一参数校验（#280 unknown-key 病预防）。
2. **`common/test/` 包迁出**为 testkit-core 起点（ScriptTest/TestContext/yaml/testOrigin 先例）。
3. **130 个测试按 family 迁 `@SceneTest`**，同时搬出生产 jar 进 testmod source set；被吞名单 + flaky 家族优先；巨类拆 family、私有 builder 收编共享 arena 库；旧 `@GameTest` 迁一批删一批，不并行养。
4. **「live 待验」清单**中可重复项沉淀为 T1/T2 场景（screen watchdog、GUI 容器、chat 回读、server/client avatar 缝）。
5. **仪表契约套件**建立（§4）。
6. **GameTestServer 与 `solo*` batch 机制退役**（P4，全量迁完后）。

---

## 7. 阶段计划

- **P0 止血**（立即，独立于产品，~1-2 天）：现有套件 ENTER/EXIT JSONL 执行清单（单处 helper）+ 跑后「注册=执行」对账脚本 + 统一包装脚本（跑前按 PID 杀残留→删 world→跑→解析 BUILD/required/对账，缺一即 FAIL）；被吞名单以 `AGENT_GT_ONLY` 显式跑一轮重建可信基线。
- **P1 第一竖切**：stagewright 项目骨架 + core（断言 DSL/测试模型/JSONL schema）+ T0 harness + 编排器最小版（server-only）+ 金丝雀自测；**编排契约冻结**；**最小仪表契约子集**（T0 依赖面：route dispatch/观察读/直接世界操作——信任链不许在 dogfood 开始前是断的）；dogfood 迁被吞名单 + flaky 家族。**双 loader 验收分级：neoforge 全量 + fabric 冒烟子集**（fabric 侧驱动层从未被测过，全量对齐单列为 P1.5 里程碑，防 scope 失控）。
- **P2 客户端两形态**：schema SPI + `mc.test.*` verbs + JUnit 5 集成（attach 模式）+ T1 拓扑 + 首批 UI 场景 + 仪表契约套件扩展到客户端仪表（含客户端进场重置 verb：releaseKeys/closeScreen/清 chat）。
- **P3 生产拓扑 + 分发**：T2 拓扑 + gradle-plugin（shell 同一契约）+ 客户端进程池 + maven 发布准备。
- **P4 收尾**：剩余测试迁完、testmod 搬家、GameTestServer/`solo*` 退役。

每阶段验收都以「编排器 JSONL 对账门全绿 + 双 loader」为准；P1 起 #54 等主线的新测试直接写在 testkit 上（增量摊销）。

---

## 8. 明确不做（YAGNI）

- 不升级 MC 换官方 client gametest（1.21.1 版本锁是既有约束；自建方案测的恰是驱动层生产通道 = dogfooding）。
- 不动 live/replay 链（真相层地位不变）。
- 不改 132 个 JS 验证脚本的既有跑法（收编为授权形态③即可）。
- P1 不做并行执行（先串行确定性；快照时序基线后再评估受控并行——N 具身体 + region 隔离）。
- 不做 structure NBT 模板消费（程序化 arena 是既定路线，yaml-gametest spec 同判）。

---

## 9. 已拍板决策记录

| 决策 | 结论 | 理由 |
|---|---|---|
| 仓库归属 | 本仓库平级新项目 `stagewright/` | 共享坑史与迭代速度；将来可拆库发布 |
| 进程外运行器 | JUnit 5 基座 | 发现/IDE/CI 白拿，断言 DSL 叠上 |
| 编排器 | Python 先行，gradle-plugin 后包同一契约 | dogfood 速度；契约先冻结保迁移 |
| verb 扩展点 | 公共化（addRoute + schema SPI） | addRoute 已是公共缝，别无他路 |
| T0 壳 | 普通专服，弃 GameTestServer | 跨 loader 对称；#85 病灶从存在上消除 |
| 依赖方向 | testkit → worlddriver，不抽取仪表 | 驱动层定位（user 纠正 07-16）：客户端输入/内省是驱动层本职 |

---

## 10. 风险与开放问题

- **进场重置的完备性**：单具身体串行复用，reset 必须覆盖位置/背包/HP/effects/BotConfig（pinnedBaseline）/进程链清空;漏一项即重现共享状态彩票。P1 用「同一测试连跑 3 次字节级一致」做 reset 完备性验收。
- **`@SceneTest` 注解扫描的跨 loader 实现**：common 层无 classpath 扫描标准设施;候选=显式注册表（保底，编译期可校验）或各 loader 的注解数据（fabric annotation processing / neoforge scan data）。P1 先显式注册 + 对账门（注册数=清单数），扫描做增强。
- **JUnit 场景与游戏进程的生命周期同步**：JUnit 并行 fork 与拓扑租约的映射（一个 topology 实例服务多个 @Test？串行租约先行）。
- **T2 世界模板的两端一致性**：专服世界模板与客户端资源的版本匹配。
- **迁移期双轨**：旧 GameTest 与新 harness 并存期间，验收门以哪边为准——规则：已迁 family 以 testkit 为准，未迁以旧门（P0 加固后）为准，家族清单入 spec 附录维护。
- **壳更换的时序漂移**：GameTestServer → 普通专服，chunk/entity-ticking 语义改变（GT 区块永不 entity-ticking 的旧限制消失、mob AI 开始活 tick、玩家相关的 spawn/sim-distance 语义不同）——同一测试迁壳后基线可能移动。处置：迁移按 family 做新旧壳 A/B，允许显式重定基线，漂移记录进迁移日志；**禁止为凑绿调阈值不留痕**。
- **`level.tick()` 手动模式是迁移雷**：AgentGameTestServer 里 9 处手动 `level.tick()`（正是 processUnloads 挂死栈的入口）在活 tick 专服上语义完全不同，**必须重写为 await 式，不得机械搬运**；迁移 checklist 单列。
- **fabric 侧 scope 膨胀**：worlddriver 的服务端路径从未在 fabric 上被测过，P1 双 loader 可能掀出驱动层 fabric 专属 bug。已用验收分级（P1 fabric 冒烟 / P1.5 全量对齐）设界；掀出的驱动层 bug 立独立 task，不算 testkit scope。
- **客户端进程池的状态残留**：复用 client 进程 = 新 flaky 源（按键卡手/screen 未关/相机残留——input clobber 病史）。客户端进场重置 verb（P2）+ reset 完备性验收（连跑 3 次一致）扩展到 client 侧；残留嫌疑时进程池支持"弃用重启"降级。
- **#85 根因未除的保险**：弃 GameTestServer 是绕开其调度，但 #85 从未根因定位——若吞的机制在本仓库共享 support 代码里会跟着走。保险：对账门与根因无关地捕获任何吞（注册数=执行数在新旧两侧都生效）；金丝雀"必吞"哨兵每轮验证捕获能力本身。不专项深挖 vanilla 根因（时间盒为零），除非对账门再次抓到新形态。
- **产品维护面耦合**：第三方依赖 testkit → 连带 worlddriver 的版本节奏与 MC 支持矩阵（当前 1.21.1 单版本）。发布前文档必须写明支持矩阵与兼容承诺；MC 版本升级策略（连带 fabric client gametest 官方 API 的关系重估）留待首个外部用户前决策。
- **仪表面≠冻结面**：仪表 verb 也会演进（驱动层自己的产品迭代），契约套件要随 verb 版本走——verb 语义变更必须同 PR 更新契约断言，CI 上契约套件红 = 阻断合并，防"仪表悄悄变语义、行为测试集体误判"。

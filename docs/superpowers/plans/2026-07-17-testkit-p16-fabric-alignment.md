# stagewright P1.6 — Fabric 对齐 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 worlddriver 的 fabric loader 获得与 neoforge 同级的 testkit dogfood 门（8 个 wd.* 场景 + 13 记录验收），同时把从未被测过的 fabric 服务端驱动层第一次置于测试之下。

**Architecture:** sim 核心（ServerPlayerAvatar/ServerWorldDriver/ServerAgentManager）与场景（WorldDriverScenes + probe helpers）从 neoforge 模块搬入 common；FakePlayer 的 loader 差异用「loader 注入 body 工厂」seam 解决（repo 无 @ExpectPlatform 先例，不引新依赖）：neoforge 注入 FakePlayerFactory（行为字节级不变），fabric 注入 common 自造的 vanilla-only `AgentFakePlayer`。neoforge 原 FQN 全部留薄 shim（协变返回 FakePlayer），legacy AgentGameTestServer 约 3000 行**零源码改动**。重构护栏 = P1.5 系列建立的字节级指标门。

**Tech Stack:** architectury 多 loader（loom.platform per module）、ServiceLoader SPI、t0.py --expect-file 正门。

## Global Constraints

- 依赖方向：stagewright 永不依赖 worlddriver loader 模块；worlddriver（common）→ :stagewright-common 是 P1c 已批的测试消费关系，本阶段扩展到 common 模块合法。
- **legacy 零改动**：`AgentGameTestServer.java`/`AgentGameTestSupport.java` 除新增 delegate 与 javadoc 注记外源码不动；neoforge `sim/` 三类保持原 FQN + 原 API（含 `FakePlayer` 返回类型），`/agentserver`、`WorldDriverNeoForge.tickAll`、全部 legacy 测试**不改一行即编译通过**。
- **neoforge 字节级指标门**（每个重构 Task 的验收；任一漂移=STOP/BLOCKED，禁止调门）：
  - `wd.descentYaw`: 871°/53（golden 双记之迁移版）
  - `wd.selfShaftDigUp` 签名门 PASS 且 `worstBackslide=20.252203415101263`
  - `wd.gearScope`: bare=0.94000053 sword=5.9040003 ATTACK_SPEED=1.5999999046325684 ATTACK_DAMAGE=6.0
- fabric 首跑指标**如实记录为 fabric golden 基线**——不预设与 neoforge 字节同（P1a 字节同先例仅覆盖 builtin 场景）；fabric 场景 RED → 确定性定性（×3 复跑）→ BLOCKED 上报裁决，禁止自行放宽断言/标 optional。
- 新 fabric run 配置必须加 configureEach 名字守卫（fabric 侧钉 39801 的 P1b 教训，fabric/build.gradle:106 已有 contractServer 先例）；loom 用 `vmArg` 非 `jvmArg`。
- ServiceLoader service 文件搬 common 后**必须删除 neoforge 副本**——dev classpath 两份同内容 service 文件 = provider 双加载 = 重名门 RED（该门顺便自证）。
- 运维纪律：禁止 pkill（ps 找 PID 显式杀）；前台有界等待（Bash timeout 参数，禁 sleep 外壳）；gametest/dogfood 挂死或被杀 → 删 run 世界目录再跑；子代理**永不**带着后台任务结束回合。

## File Structure（搬迁地图）

| 现址（neoforge） | 新址（common） | neoforge 残留 |
|---|---|---|
| `neoforge/.../sim/ServerPlayerAvatar.java`(563 行,neoforge 专有仅 FakePlayer/FakePlayerFactory 两 import) | `common/.../bot/sim/ServerPlayerAvatar.java`（字段/返回改 `ServerPlayer`） | 同 FQN 薄 shim extends common 版,协变 `FakePlayer fakePlayer()`,静态 create/createUnique 走 FakePlayerFactory |
| `neoforge/.../sim/ServerWorldDriver.java`(125 行) | `common/.../bot/sim/ServerWorldDriver.java` | 同 FQN 薄 shim（协变访问器 + createIsolated 委托） |
| `neoforge/.../sim/ServerAgentManager.java`(38 行) | `common/.../bot/sim/ServerAgentManager.java`（唯一注册表） | 同 FQN 静态全委托 shim（单一注册表在 common） |
| — | `common/.../bot/sim/AgentFakePlayer.java`（新,vanilla-only 镜像 NeoForge FakePlayer 含 net-handler stub,用 javadc 反编译对照） | — |
| — | `common/.../bot/sim/ServerAgentBodies.java`（新,工厂 seam:`install(BodyFactory)` 一次性 + `shared(level)`/`unique(level,profile)`,未安装即用=大声抛） | — |
| `neoforge/.../testkit/WorldDriverScenes.java` | `common/.../bot/testkit/WorldDriverScenes.java`（import 换 common sim;probe 调用换 SimProbes） | 删除（provider 以 common 版注册） |
| `AgentGameTestServer.probeSwing/probeHurt`、`AgentGameTestSupport.grantWaterEffects` | `common/.../bot/testkit/SimProbes.java`（签名用 common 类型） | 原静态改一行 delegate（legacy 调用点零改动） |
| `neoforge/src/main/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider` | `common/src/main/resources/META-INF/services/...`（内容=common 版 FQCN） | **删除** |
| — | `scripts/stagewright/expected-scenes-fabric.txt`（8 名,同 neoforge 清单） | — |

---

### Task 1: common sim 核心 + body 工厂 seam（neoforge shim 化）

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/bot/sim/{AgentFakePlayer,ServerAgentBodies,ServerPlayerAvatar,ServerWorldDriver,ServerAgentManager}.java`
- Modify: `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/sim/{ServerPlayerAvatar,ServerWorldDriver,ServerAgentManager}.java`（改写为 shim）、`neoforge/.../WorldDriverNeoForge.java`（init 安装 neoforge 工厂）

**Interfaces:**
- Consumes: 现 neoforge sim 三类源码（逻辑逐行搬,唯 FakePlayer 类型改 `ServerPlayer`）；NeoForge `FakePlayer`/`FakePlayerFactory`（shim 与工厂用）。
- Produces: `ServerAgentBodies.install(BodyFactory)`（loader init 一次性,重复 install=抛）;`BodyFactory { ServerPlayer shared(ServerLevel); ServerPlayer unique(ServerLevel, GameProfile); }`;common `ServerPlayerAvatar.create/createUnique` 经 seam 取身体;common `ServerAgentManager` 是唯一注册表（shim 全委托,`WorldDriverNeoForge:91` 的 tickAll 经 shim 落同一张表）。

- [ ] **Step 1: 逐行搬移三类到 common**（`FakePlayer fp` → `ServerPlayer fp`;`FakePlayerFactory.getMinecraft/get` 调用点换 `ServerAgentBodies.shared/unique`;方法留 non-final 供 shim 协变;javadoc 标注搬迁来源与 seam 契约）
- [ ] **Step 2: AgentFakePlayer + ServerAgentBodies**（AgentFakePlayer 本 Task 只建骨架并 javadoc 声明 fabric Task 3 首用;镜像 NeoForge FakePlayer 的 override 集,用 mcp javadc 反编译 `net.neoforged.neoforge.common.util.FakePlayer` 对照,连接 stub 必含）
- [ ] **Step 3: neoforge shim 化**（同 FQN extends common 版;协变 `FakePlayer fakePlayer()` 强转返回;静态工厂返回 shim 类型;`ServerAgentManager` shim 静态全委托;`WorldDriverNeoForge` init `ServerAgentBodies.install(...)` 走 FakePlayerFactory）
- [ ] **Step 4: 编译门**：`./gradlew :common:build :neoforge:build :fabric:build -x test`（fabric 此时未用 sim,须仍绿）
- [ ] **Step 5: neoforge 字节级指标门**（重构护栏）：dogfood 正门命令跑 GREEN 且三组指标与 Global Constraints 逐字比对;另跑 legacy 真单名 `AGENT_GT_ONLY=serverAvatarGearScopeProbeArena`（shim 路径的 legacy 侧验证）
- [ ] **Step 6: Commit** `refactor(sim): server-agent sim core to common behind loader body-factory seam (neoforge shims keep FQN, byte-metric gated)`

### Task 2: 场景 + probe helpers 搬 common,service 文件换位

**Files:**
- Create: `common/.../bot/testkit/{WorldDriverScenes,SimProbes}.java`、`common/src/main/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`
- Delete: `neoforge/.../testkit/WorldDriverScenes.java`、`neoforge/src/main/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`
- Modify: `common/build.gradle`（+:stagewright-common,镜像 stagewright 内部 common→common 消费法）、`AgentGameTestServer.java`（probeSwing/probeHurt 改一行 delegate→SimProbes）、`AgentGameTestSupport.java`（grantWaterEffects 同）

**Interfaces:**
- Consumes: Task 1 的 common sim;现 WorldDriverScenes 全文（含 porting map/裁决注记 javadoc,逐字搬,唯 import 与 probe 调用点换）。
- Produces: `SimProbes.probeSwing(ServerLevel, ServerWorldDriver, ServerPlayer, ItemStack, int,int,int)`/`probeHurt(ServerPlayer, boolean)`/`grantWaterEffects(ServerPlayer)`（签名 common 化;neoforge 原静态签名不变,内部 delegate + 类型收窄由 shim 协变保证）。

- [ ] **Step 1: 搬场景与 helpers**（场景体逐字;SimProbes 从 legacy 原文搬 probe 体,`FakePlayer`→`ServerPlayer`;legacy 三静态改 delegate 一行 + javadoc 注记）
- [ ] **Step 2: service 文件换位**（common 建,neoforge **删**;grep 确认全 repo 该 service 名只剩一份 main resources）
- [ ] **Step 3: 编译门**（三模块）
- [ ] **Step 4: neoforge 字节级指标门**（同 Task 1 Step 5 全套;此门若 RED 先查 service 双注册/重名门再查搬移 delta）
- [ ] **Step 5: Commit** `refactor(testkit): wd.* scenes + probe helpers to common, SceneProvider service relocated (dup-gate verified)`

### Task 3: fabric 接线（dogfood 配置 + hooks + 清单）

**Files:**
- Modify: `fabric/build.gradle`（+:stagewright-common 两行依赖,transformProductionFabric;+runDogfoodServer 配置镜像 neoforge/build.gradle:128-140;configureEach 名字守卫加 `dogfoodServer`）、`fabric/.../WorldDriverFabric.java`
- Create: `scripts/stagewright/expected-scenes-fabric.txt`（8 个 wd.* 名 + 头注释同 neoforge 清单格式）

**Interfaces:**
- Consumes: neoforge 侧接线全样（`WorldDriverNeoForge.java:33-92`:TESTKIT_AUTORUN 双门、applyGameTestBaseline、StageWrightCommon.onServerStarted(server,"fabric")、onServerTick、tickAll）;fabric 事件 API（`ServerLifecycleEvents.SERVER_STARTED`/`ServerTickEvents.END_SERVER_TICK`,WorldDriverFabric:24-26 已有挂点）。
- Produces: `:fabric:runDogfoodServer`（run-dogfood 目录,ephemeral 端口,stagewright.autorun=true）;fabric init 安装 `ServerAgentBodies.install(AgentFakePlayer 工厂)`（unique=profile 键控,shared=level 缓存,语义对照 FakePlayerFactory javadoc）。

- [ ] **Step 1: build.gradle**（依赖+运行配置+名字守卫;fabric 守卫先例在 :106）
- [ ] **Step 2: WorldDriverFabric 接线**（镜像双门与调用序:tickAll 在 StageWrightCommon.onServerTick **之前**,与 neoforge:91-92 同序;工厂 install 放 onInitialize）
- [ ] **Step 3: 清单文件**
- [ ] **Step 4: 结构门**：`python3 scripts/stagewright/t0.py --loader fabric --wall 540 --run-task :fabric:runDogfoodServer --results fabric/run-dogfood/testkit-results.jsonl --expect-file scripts/stagewright/expected-scenes-fabric.txt` 跑通到 done footer:13 记录全在场（PASS/FAIL 皆可,本门只验组装:名字齐、金丝雀三员判定正确、无 SWALLOWED/TRUNCATED/MISSING-EXPECTED）。场景级 RED 本 Task 不裁,留 Task 4。
- [ ] **Step 5: Commit** `feat(testkit): fabric dogfood wiring — run config, autorun hooks, body factory, scene manifest`

### Task 4: fabric 首跑定性 + golden 基线

**Files:**
- Modify: `common/.../bot/testkit/WorldDriverScenes.java`（仅 javadoc:fabric golden 双记）;（视裁决）`scripts/stagewright/expected-scenes-fabric.txt` 注记

**Interfaces:**
- Consumes: Task 3 的可跑 fabric dogfood。
- Produces: fabric per-scene golden 基线（指标值×3 复跑稳定性）记入场景 javadoc（neoforge/fabric 双列,沿 descentYaw golden 双记先例）。

- [ ] **Step 1: ×3 复跑**（正门命令三连,逐场景抓指标行,判定确定性:字节同/漂移/翻转）
- [ ] **Step 2: 定性与上报**（全 GREEN→直接记基线;确定性 RED→与 neoforge 指标对比写差异表,**STOP 报 BLOCKED** 交控制器裁决[loader 物理/事件序差异=产品发现,立 task],禁自裁;漂移→记录漂移带宽并 BLOCKED）
- [ ] **Step 3: javadoc 双记 + Commit** `test(testkit): fabric dogfood golden baseline recorded (×3 determinism)`（若 BLOCKED,commit 于裁决后由控制器指示）

### Task 5: 验收 + 文档

**Files:**
- Modify: `stagewright/README.md`（loader 矩阵:两 loader 正门命令并列）、`TODO.md`（P1.6 条目）;契约 v0 无语义变更不动。

- [ ] **Step 1: 五门验收**（全部前台有界）:①fabric dogfood 正门 GREEN;②fabric 纯 T0 `t0.py --loader fabric --wall 540` GREEN;③fabric 仪表契约 `instrument.py --loader fabric` GREEN（P1b 已双 loader,回归确认）;④neoforge dogfood 正门 GREEN+三组字节指标复对;⑤neoforge legacy 全量诚实入档（失败名 solo 定性,白名单外零新名;倒数计数如实）
- [ ] **Step 2: 文档 + Commit** `docs(testkit): P1.6 — fabric dogfood aligned, loader matrix, golden baselines`

---

## Self-Review（计划自检记录）

1. **覆盖**：TODO 残留「fabric 侧 dogfood/T0 双门未验证」→ Task 3-5;风险册「fabric 驱动层从未被测」→ 工厂 seam+AgentFakePlayer 首测(Task 1/3);「9 处手动 level.tick 迁移雷」→ 已由 P1.5b await 降级消化,fabric 直接受益。
2. **占位符**：搬迁类任务以「逐行搬+唯一类型替换」+现源码行号锚定,非留白;AgentFakePlayer 明示以 javadc 反编译对照实现;无 TBD。
3. **一致性**：`ServerAgentBodies.shared/unique` 在 Task 1 定义、Task 3 fabric 工厂实现引用同名;SimProbes 签名 Task 2 定义即 Task 2 内消费;清单文件名 Task 3 创建与 Task 3/4/5 命令引用一致。
4. **风险入案**：AgentFakePlayer 连接 stub 不全→场景崩（Task 1 骨架+Task 3 首用分离,崩溃归因窄）;service 双注册假 RED（重名门自证,Task 2 Step 4 预案）;fabric 事件序 END_SERVER_TICK 与 neoforge Post 的场景执行位差→指标漂移（Task 4 ×3 定性 + BLOCKED 协议）;common→stagewright-common 依赖若引 loom 解析问题→镜像 stagewright 内部消费法（Task 2 Step 3 编译门早失败）。

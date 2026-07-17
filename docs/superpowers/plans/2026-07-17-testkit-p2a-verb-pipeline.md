# mc-testkit P2a — verb 注册管线 + #280 收口 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec §6.1 的 verb 扩展点公共化（schema SPI + 命名空间约定 + `mc.test.*`）并根修 #280（`mc.bot.setting` 未知键静默吞），全部在 T0/契约层可验（P2 的 T1 拓扑与客户端仪表扩展另列 P2b，JUnit5 attach 另列 P2c）。

**Architecture:** 现状盘点——`AgentApi.addRoute`（公共缝）、`ToolCatalog.registerExtra`（schema 供给 + cache 失效）、`SchemaValidator` route 层预派发校验、`requireSchemasFor` 开机不变量**均已存在**；缺的是 (a) 原子配对注册（route 与 schema 分两步注册可漂移）、(b) boot 后注册的 no-schema 洞（`AgentDriverCommon` 校验接线 `if (s != null) validate` ——无 schema 的 route 静默跳过校验=#280 同形洞）、(c) 命名空间政策、(d) #280 本体。#280 修法=单源注册表：`SettingsSnapshot.build` 已是全量知识（手列键+反射补全 `public static volatile` 原始类型 BotConfig 字段），从它派生①封闭 ToolSchema（validator 预派发拒未知键）②apply 侧大声拒绝（双保险）。

**Tech Stack:** 既有 Schema DSL（运行时构建，`object().prop(...).additionalProperties(false)`）、instrument.py 契约、dogfood 场景回归。

## Global Constraints

- **Hard Rule #1 不破**：api 层不依赖 mcp 层——新配对注册入口放哪一层要沿既有 seam 风格（`requireSchemasFor`/`setParamsValidator` 由 bootstrap 注入的先例）；如在 ToolCatalog 侧提供 `registerVerb(ToolSchema, handler)` 需经 bootstrap 转接 AgentApi，不许 ToolCatalog 直接 import AgentApi 单例之外的核心。实现者读 `AgentDriverCommon.java:170-190` 的接线后择位，报告里论证。
- **命名空间政策**（写进 javadoc + mod 开发者文档）：`mc.*` 保留给驱动层；`mc.test.*` 授予 testkit-runtime（`mc.test.yaml` 为既有驱动层 harness verb，**祖父条款**注记）；第三方一律 `<modid>.*`。新配对注册入口对违反者**注册时即抛**。
- **校验洞收口语义**：route() 派发时 schema 缺失 = `IllegalStateException` 大声拒（不是跳过）。开机 `requireSchemasFor` 已保证 boot 时全集有 schema，post-boot 只有新配对入口可加 → 该异常只可能命中"绕过配对入口直接 addRoute"的编程错误。既有 `addRoute` 保持公共（path-debug 等内部消费者）但 javadoc 明示新规则。
- **#280 修复语义**：未知键=**错误**（RPC error envelope），不是静默忽略也不是仅诊断字段；已知键正常应用不受影响；`paused` 等 apply 侧特殊键必须在注册表内（apply 键集 ⊆ 注册表键集，差集=启动即抛的自检）。快照的反射补全 pass 保证新 BotConfig 旗标自动进注册表（#280 病根=新旗标漏 schema）。
- **字节指标门继续武装**（回归护栏，任何 dogfood 验收跑必对）：descentYaw 871°/53；selfShaftDigUp 签名门 PASS 且 worstBackslide=20.252203415101263；gearScope bare=0.94000053 sword=5.9040003 ATTACK_SPEED=1.5999999046325684 ATTACK_DAMAGE=6.0。
- **client-only 边界诚实**：`mc.bot.setting` 与 `mc.test.reset` 在专服上必须**大声**报 client-only（P1b 既有断言形状）；端到端客户端 A/B（真开客户端打 setting 未知键）留 P2b 的 T1 仪表扩展，本阶段以①契约层 schema 拒键（T0 可测）②纯函数注册表场景回归③专服 client-only 大声错误三腿覆盖。
- 运维纪律照旧：禁 pkill；前台有界（Bash timeout 参数）；挂死删 run 世界重跑一次；子代理永不带后台任务收束；BLOCKED 协议（指标漂移/意外 RED→定性→上报，禁自裁调门）。

## File Structure

| 文件 | 责任 |
|---|---|
| `common/.../api/AgentApi.java` | 校验接线洞收口协作方（见 bootstrap）；javadoc 规则更新 |
| `common/.../AgentDriverCommon.java`(:170-190) | `if (s != null)` → 缺 schema 大声抛；配对注册入口的 bootstrap 转接 |
| `common/.../mcp/ToolCatalog.java` | `registerVerb(ToolSchema, handler)` 配对入口（或按 Hard Rule #1 择位的等价物）+ 命名空间政策enforcement + javadoc |
| `common/.../bot/SettingsRegistry.java`（新） | 单源键注册表：从 SettingsSnapshot 键集+类型派生；`knownKeys()`/`schemaProps()`/`isKnown(key)` 纯函数（不触 Minecraft 客户端类，服务端场景可调） |
| `common/.../bot/SettingsSnapshot.java` | 键枚举逻辑抽给 SettingsRegistry 共用（行为不变） |
| `common/.../bot/SettingsCommand.java` | apply 入口未知键大声拒绝；apply键⊆注册表自检 |
| `common/.../mcp/catalog/BotTools.java` | `mc.bot.setting` schema 从注册表生成封闭形状（`additionalProperties(false)` + 全键 prop） |
| `common/.../api/`或`bot/` 新 verb 实现 | `mc.test.reset`：client 线程 releaseKeys+closeScreen(setScreen null)+清 ClientChatLog+cancel 残余 look;专服大声 client-only;经新配对入口注册,hidden schema（mc.test.yaml 先例） |
| `common/.../bot/testkit/AgentDriverScenes.java` | 新场景 `ad.settingRegistryClosed`（T0 回归：注册表非空+哨兵键在+假键拒+schema 封闭+apply键⊆注册表） |
| `scripts/testkit/expected-scenes-{neoforge,fabric}.txt` | +ad.settingRegistryClosed（各自迁移 commit 同步） |
| `scripts/testkit/instrument.py` | 新契约检查：①mc.bot.setting schema 封闭（tools/list 读回 additionalProperties=false+键数≥哨兵）②未知键裸 RPC 打 setting 在专服=client-only 大声（既有形状回归）③mc.test.reset 专服 client-only 大声④无 schema 派发拒绝不可探=以①的 schema 存在性+方法表对账代偿 |
| `docs/testkit/instrument-contract-v0.md`、`mc-testkit/README.md`、`TODO.md` | 契约附录+命名空间政策文档+条目 |

---

### Task 1: 配对注册入口 + 校验洞收口 + 命名空间政策

**Files:** `ToolCatalog.java`、`AgentDriverCommon.java`、`AgentApi.java`（javadoc）
**Interfaces:**
- Produces: `ToolCatalog.registerVerb(ToolSchema schema, Function<Map<String,Object>,Object> handler)`（或 Hard-Rule-#1 合规等价物——实现者读接线后定,报告论证）:原子完成 schema 供给+route 注册;命名空间校验（`mc.` 前缀非 `mc.test.` 拒;政策 javadoc）;重复名沿 addRoute last-wins 语义并注记。
- Produces: 派发时 schema 缺失=IllegalStateException（改 `AgentDriverCommon` 校验接线的 null 分支）。

- [ ] **Step 1: 实现**（含 requireSchemasFor 在 registerVerb 后的即时自检——新 verb 注册完成后 route 必有 schema）
- [ ] **Step 2: 编译门三模块 + neoforge dogfood 快回归**（改的是热路径接线,六字节指标必对）
- [ ] **Step 3: Commit** `feat(api): paired verb registration + namespace policy, close the post-boot schema-less dispatch hole`

### Task 2: #280 根修 — SettingsRegistry 单源 + 封闭 schema + apply 大声拒

**Files:** `SettingsRegistry.java`（新）、`SettingsSnapshot.java`、`SettingsCommand.java`、`BotTools.java`
**Interfaces:**
- Consumes: SettingsSnapshot.build 的键枚举（手列+反射补全）;SettingsCommand.apply 的 142 处 applied.add 键集。
- Produces: `SettingsRegistry.knownKeys()`（LinkedHashSet,含 paused 等 apply 特殊键）/`isKnown`/`schemaProps()`（键→Schema 类型,布尔/数值按 BotConfig 字段类型）——**纯函数,不触 Minecraft 客户端运行时**;BotTools 的 mc.bot.setting schema=object().props(全键).additionalProperties(false);SettingsCommand.apply 收到未知键→抛（进 RPC error envelope）,消息列全部未知键+提示 knownKeys 数目;apply键⊆注册表 差集自检（类加载或首次 apply 时,差集非空即抛列名）。

- [ ] **Step 1: 抽注册表**（Snapshot 行为字节不变——它仍是 apply 的读路径返回值;grep if-链全键与快照键对账,apply-only 键显式补入注册表并注记）
- [ ] **Step 2: schema 封闭 + apply 拒绝**
- [ ] **Step 3: 快回归**（编译三模块;client 类不被服务端类加载路径拖入=专服 dogfood 起服自证）
- [ ] **Step 4: Commit** `fix(bot): #280 — mc.bot.setting unknown keys rejected loudly, schema closed from single-source SettingsRegistry`

### Task 3: mc.test.reset verb + ad.settingRegistryClosed 场景

**Files:** 新 verb 实现文件（择位报告论证）、`AgentDriverScenes.java`、两清单文件
**Interfaces:**
- Consumes: Task 1 registerVerb;既有 client 原语（releaseKeys/`mc.client.screen.close` 的 setScreen(null) 路径/ClientChatLog）。
- Produces: `mc.test.reset`（hidden schema,object().additionalProperties(false) 无参或极少参;client 线程执行 releaseKeys+关屏+清 chat+取消残余 look 进程;专服=IllegalStateException("client only…")大声）;场景 `ad.settingRegistryClosed`（required,服务端断言:knownKeys 非空且≥60、哨兵键 {paused,autoEat,walker.repathEveryTicks} 在、假键 `definitelyNotAKnob` isKnown=false、schemaByName("mc.bot.setting") additionalProperties=false 且 prop 数==knownKeys 数、SettingsCommand 的 apply键⊆注册表自检通过标志）。

- [ ] **Step 1: verb + 场景实现**
- [ ] **Step 2: 双 loader dogfood**（9 场景,新场景两清单各自本 commit 追加;字节指标门照对）
- [ ] **Step 3: Commit** `feat(testkit): mc.test.reset client-entry verb via paired registration + settings-registry regression scene`

### Task 4: 契约扩展 + 验收 + 文档

**Files:** `instrument.py`、`docs/testkit/instrument-contract-v0.md`、`mc-testkit/README.md`、`TODO.md`
- [ ] **Step 1: instrument.py 新检查**（File Structure 表①-④;自测数更新;金丝雀不动）
- [ ] **Step 2: 五门验收**:①instrument neoforge GREEN;②instrument fabric GREEN;③dogfood neoforge GREEN(9 场景+六字节指标);④dogfood fabric GREEN(9 场景);⑤legacy 全量诚实入档(白名单外零新名,倒数如实)
- [ ] **Step 3: 文档+Commit** `docs(testkit): P2a — verb pipeline, namespace policy, #280 closed, contract additions`

---

## Self-Review（计划自检记录）

1. **覆盖**：spec §6.1（addRoute 已有+schema SPI+命名空间约定+统一参数校验白拿）→T1/T2;#280→T2;`mc.test.*` 首 verb（进场重置,spec §7 P2 行/风险册客户端残留条）→T3;仪表契约扩展中 headless 可测子集→T4;T1 拓扑/客户端端到端 A/B/JUnit5=P2b/P2c 偏差声明。
2. **占位符**：无 TBD;实现位置留了两处"实现者读现场择位+报告论证"（Hard Rule #1 的接线择位、verb 实现文件择位）——这是把既有 seam 风格判断下放给带上下文的实现者,验收以规则(不 import 违规/大声语义)兜底,非留白。
3. **一致性**：registerVerb 在 T1 定义、T3 消费;SettingsRegistry.knownKeys/isKnown/schemaProps 在 T2 定义、T3 场景消费;场景名 ad.settingRegistryClosed 在 T3 清单与 T4 验收一致;9 场景计数=8+1。
4. **风险入案**：BotTools schema 封闭可能撞 MCP 客户端缓存 stale schema（reference_bot_setting_key_apply_integrity——MCP 层静默丢新键,验证必走裸 RPC,已写进契约检查②的方法选择）;SettingsSnapshot 反射补全键与 apply if-链键差集未知（T2 Step 1 对账步显式处理,差集自检制度化）;`mc.test.reset` 清 chat 触 ClientChatLog 实现细节（T3 实现者读 reference_event_channel 相关源;headless 只验 client-only 错误形状,行为验证留 P2b）;dogfood 起服即自证 client 类未被拖入服务端类加载（T2 Step 3）。

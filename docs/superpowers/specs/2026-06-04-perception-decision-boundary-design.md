# 设计文档 —— 感知层 + 决策边界基座（WorldModel / Perception / 反射硬化）

> Agent 控制架构演进的**第一个垂直切片**（Perception + Decision-Boundary foundation）。
> 前置：`docs/design/00-execution-model.md`（执行模型与优先级链调度器）。
> 本切片落地后，决策边界文档作为 `docs/design/04-perception-and-decision-boundary.md` 进入设计文档序列。
>
> 状态：设计已评审通过（2026-06-04），待 writing-plans 拆实现计划。

## 0. 背景与目标

### 0.1 问题（本 session 实证）
1. **L2(LLM) 被卷入反应式生存回路 → 死亡循环**。LLM 以 LLM 延迟做躲怪/逃跑控制，怪每秒 2–4 HP，bot 连死 4+ 次。反应式生存必须是算法（引擎），LLM 只做策略。
2. **感知靠拼**。Agent 每个决策周期要拼 `observe.player` + `observe.threats` + `query` + 时间 等多次调用，且 server 视角与 client 反射所见会 desync（本 session 的 crawl-evade / 窒息 desync 耗了数小时）。
3. **逃跑寻路把低血裸 bot 带下悬崖 / 入深水**。`Goal.RunAway` 最大化距离，`WorldView.dangerCost` 是软惩罚，"跑得更远" 的收益在边缘压过软惩罚 → bot 跑下悬崖摔死 / 跳进深水淹死。
4. **黄昏自保 100% 靠 L2**。Agent 必须盯着天色手动 bunker，一旦不盯就夜里死。

### 0.2 成功判据（本切片唯一验收标准）
> 从**公平的白天起点**（非当前中毒围怪存档），**LLM 退出反应式回路**，bot **活过一夜**。

### 0.3 范围
- **感知**：`WorldModel` 每 tick 黑板 + 客户端权威的 `mc.client.scene` 结构化读 + HazardField 驱动的 ASCII 地图（含图例、方向/大小/群系/高度查询参数）。
- **决策边界**：`docs/design/04-*` 分类文档（带"错位"清单与 backlog）。
- **反射**：避险逃跑（纯 `dangerCost` 注入）+ 空闲触发的 `DuskSecureChain` + `cornered`/`duskExposed` 事件。

### 0.4 明确不做（YAGNI / 延后到后续切片）
- 持久记忆、目标/策略循环、任务调度 DAG → **大脑/记忆切片**。
- 裸装反骷髅/反蜘蛛、战斗走位、断线找掩体 → **战斗切片**。
- `cornered` 时自动 bunker（重新注册 BunkerChain）→ **延后**。尊重用户既有决定：自治 BunkerChain 已被判"太不可控"并 unregister；且围melee bunker = 死。本切片 `cornered` 仅"原地守安全格 + 上报事件"。

### 0.5 设计取舍依据（已评审拍板）
- **首切片** = 感知 + 决策边界基座（thin vertical，端到端证明架构）。
- **approach** = 全 `WorldModel` 黑板（非最小补丁，非仅 HazardField）。用户明确要工程化的承重层。
- **dusk 归属** = 空闲才触发的低优先反射 + 事件上报（BunkerChain "太不可控" 的受控版）。
- **cornered** = 守安全格 + 事件（**不**自动 bunker），见 §4.2。

---

## 1. 架构与放置

### 1.1 计算时机
在 `BotApiImpl.clientTick`（现 L979–1107）**最顶部**、null-guard 之后、`CLUTCH` 之前，新增一行：

```java
worldModel.update(mc, world, state);   // 每 tick 计算一次黑板
```

当 tick 下游所有消费者——`CLUTCH`、scheduler 各 chain、`AutoSwim`、`AntiSuffocate`、以及 `PathFinder` 经 `ClientWorldView.dangerCost`——都读同一个 `worldModel`，而非各自重算威胁/流体/落差。**这是选黑板的全部意义**：感知（Agent 读到的）与行动（反射做的）物理上不可能不一致，因为读的是同一时刻算出的同一对象。顺带消除重复扫描（ThreatScanner / isInWall / 流体检查从 4× 降到 1×）。

### 1.2 线程与所有权（照搬 BotState / ProcessScheduler，无需新并发模型）
- `WorldModel` 只在 client-tick 线程修改（同 `ProcessScheduler.current`）。
- 每次 `update()` 构造派生态后，把不可变 `WorldModel.Snapshot` 发布到 `volatile` 字段——与 `ProcessScheduler.lastPriorities`、`BotState.snapshot()` 给 off-thread RPC/MCP 读者一致、无竞争的视图完全同构。
- off-thread 的 observe 工具（`mc.client.scene`、ASCII 地图）读 **volatile snapshot**，绝不读活的可变实例。

### 1.3 层次定位：纯派生事实，零决策
```
        WorldModel  (派生事实，每 tick 重算 —— 不做任何决策)
        ╱            │                      ╲
   L0 反射         L1 process             L2 Agent (snapshot)
   chains + auto   goto/mine 经           observe.scene + ASCII 地图
   逃跑, dusk      ClientWorldView.dangerCost
```
WorldModel 只回答"此刻我周围什么是真的"（hazard / 威胁 / 光照时间 / 可站邻格）。**所有决策留在原层**——chain(L0)、process(L1)、Agent(L2)。这条线把 WorldModel 与延后的大脑切片隔开：大脑**消费** WorldModel + 持久记忆来决定"随时间做什么"；WorldModel 本身跨 tick 无状态。

### 1.4 与现有结构的关系
- `BotState` 不变、互补：它答"我的 process 在干什么"（状态槽）；WorldModel 答"我周围的世界什么样"。不同轴，无重叠。
- `ThreatScanner`（已按 tick 缓存）成为 WorldModel 的**输入**——一份共享威胁表，取代各 chain 自己 `ThreatScanner.current(mc)`。
- **不改 `Chain`/`BotProcess` 接口签名**：需要 WorldModel 的 chain 走构造器注入（见 §4.3）；`ClientWorldView` 持 WorldModel 引用供 `dangerCost`（见 §4.1）。

---

## 2. WorldModel 数据契约

两个类型（同构 BotState）：`WorldModel`（活，仅 client-tick 改）+ `WorldModel.Snapshot`（不可变，每次 update 发布到 volatile）。

### 2.1 字段组（除注明外每 tick 重算）

**① Self / vitals**（取自 LocalPlayer，复用本 session 的 `ClientObserve` 增强）
`pos, eyePos, blockPos, pose, onGround, yaw/pitch, health/max, food/saturation, air/maxAir, onFire, frozen, inWater, underWater, inWall, effects[], armor`，以及派生
`survivableFall` = 当前 HP+effects 下不致死的最大落差（块）。

**② Threats**（包 `ThreatScanner` 成一份共享表，比现状更丰富）
每个威胁 `{type(creeper/skeleton/zombie/drowned/…), pos, distance, targetingMe, velocity, isProjectile, timeToImpact}`，加 `threatCentroid, nearestThreatDist`。

**③ Environment / time**
`timeOfDay, dayPhase(DAY/DUSK/NIGHT/DAWN), lightLevel(feet), skyExposed, biome, dimension`。

**④ HazardField**（栅格，反射核心）
半径 **R=12**（水平）+ 垂直带 的格子，每格
`HazardCell{ cliffDropDepth, deepWaterDepth, contactDamage(lava/fire/cactus/magma/berry/powdersnow), standable, lethal }`，
其中 `lethal = cliffDropDepth > survivableFall || deepWaterDepth >= deepWaterMax || contactDamage`。
**作为对 `WorldView` 的纯函数计算**（见 §2.3），可在合成世界单测、无需 client。
**降频（perf）**：地形栅格每 **K=4** tick 或 bot 换格时重算；威胁/vitals 每 tick。

**⑤ 派生生存事实**（各带滞后 hysteresis，去抖，避免每 tick 翻动）
- `danger` — 有锁定我的威胁在范围内 / 近期受伤。
- `cornered` — 触手可及范围内无非致死可站出口。**逃跑安全的兜底（§4.2）**。
- `safeFleeStep` — 远离 `threatCentroid` 的最佳非致死方向（读自 HazardField）。
- `exposedAtNight` = `(NIGHT||DUSK) && skyExposed && !sheltered`；`sheltered` = 封闭且不暴露天空。驱动 dusk-secure + 事件。

### 2.2 接线（落实 §1.4 的延迟项，且最小侵入）
- **逃跑修复在 `ClientWorldView`**：持 `worldModel` 引用；`beginSearch()` 快照当前 `HazardField`（与它已快照的 mob 列表并列）；`dangerCost(foot) += hazardField.lethalPenalty(foot)`——**巨大但有限**的惩罚，逃跑强避崖/深水但**绝不卡死**（`cornered`→守安全格兜底）。`PathFinder` L171 `cur.g + edge.cost + world.dangerCost(npos)` 处即生效。**不改 `RetreatChain`/`RunAwayProcess`。**
- **需要模型的 chain 走构造器**：`new DuskSecureChain(state, worldModel)`（同 `RetreatChain(state)`）。**不改 `Chain` 接口。**

### 2.3 纯函数缝（保证可测，gap ⑥）
`HazardField` + 事实数学（`cornered`、`safeFleeStep`、`survivableFall`）只吃 `WorldView`（已存在的块访问接口，明确"so the algorithm is testable in isolation"）+ 标量 → server 端可单测。`WorldModel.update()` 是薄的 client 适配器（读 `LocalPlayer`/`ThreatScanner`/`ClientLevel`，调纯函数，发布 snapshot）。

### 2.4 Snapshot 形状（供 `mc.client.scene`）
嵌套 `{self, threats[], env, hazardSummary, derived}`——**摘要**而非原始栅格（栅格留进程内供反射；ASCII 地图渲染它，见 §3）。

---

## 3. 感知接口：mc.client.scene + ASCII 地图

两个输出**都客户端权威**（gap ①），都读同一 `WorldModel.Snapshot`——所见即逃跑所避。

### 3.1 两个 scope（关键澄清）
- **每 tick 的 HazardField**（§2）**固定** R=12、每 tick，为 perf 有界，喂反射。
- **`mc.client.scene` 查询**按需（仅 Agent 调用时），**方向/大小/群系/高度参数在此**。查询落在缓存栅格内→读 WorldModel（与逃跑一致）；超出 R=12→按需算（只读，同一纯 `HazardField` 函数，只是不缓存）。这解决 perf vs 灵活。

### 3.2 `mc.client.scene` —— 结构化读
`{self, threats[], env, hazardSummary, derived}`，强制 client（同本 session 的 `mc.client.player`）。**Agent 每周期主感知读**，一次替代拼 `observe.player`+`observe.threats`+`query`+time。

### 3.3 ASCII 地图 —— `mc.client.scene{render:"map", …}`
glyph 由 HazardField 语义驱动，地图标致死的格 = 逃跑拒绝的格。示例：

```
scene @ (224,65,351) facing E | HP 14/20 food 7 | DUSK light 7 | danger:Y cornered:N | nearest skeleton 6.2m
              z351
        .  .  .  #  #  #  .  .  .
        .  .  :  .  .  v  .  .  .
        .  ~  ~  .  @  .  .  s  .
        ≈  ≈  ~  .  .  .  V  V  .
        ≈  ≈  .  .  :  #  .  .  .
              z343      x224..232
legend  @ you   . walk   : step-up(+1)   # wall(>=2)   v drop(survivable)   V drop(LETHAL->flee avoids)
        ~ water   ≈ deep-water(LETHAL)   ! lava   * fire   x contact-dmg
        s skeleton   S skeleton(targeting-you)   C creeper   Z zombie   D drowned
```

**附加信息（用户明确要的"额外信息/方块类型图例"）**
- **图例恒输出**（glyph→含义），致死 glyph 标注 "flee avoids"——空间视图自解释。
- **表头行** = scene 摘要（pos、facing、HP/food、dayPhase/light、`danger`/`cornered`、最近威胁）。
- **边缘坐标**（xMin..xMax / zMin..zMax），便于把任一格换算世界坐标去 `goto`。
- **可生存性可视**：`v` vs `V` 即 `cliffDropDepth <= survivableFall` vs `>`——地图直接画出 bot 自身的生存模型。
- **可选方块类型叠加**（"方块类型图例"）：小而可配的集合 `T` 原木/`O` 矿/`=` 水源，与栅格一起廉价算，**默认关**（省 token），需要时开。

**Schema**：`{present, plane, center, radius, width, height, header, legend:{glyph→meaning}, rows:[…], threats:[{glyph,type,pos,targetingMe}], coords:{xMin,xMax,zMin,zMax}}`。

### 3.4 查询参数

| 参数 | 取值 | 作用 |
|---|---|---|
| `render` | `summary`(默认) · `map` | 结构读 vs ASCII 栅格 |
| `center` | `{x,y,z}`(默认=bot) | **扫远处**，非只 bot 周围 |
| `plane` | `top`(xz,默认) · `vertical` | 俯视高度图 vs 剖面 |
| `along` | `facing`(默认) · `x` · `z` · `<deg>` | 竖直剖面的**方向**——"前方什么样"=沿朝向切片 |
| `radius` | int,默认12,**上限32** | 对称水平大小 |
| `extent` | `{forward,back,left,right,up,down}` 相对朝向 | **方向/非对称**盒（如 `forward:24,back:2`=看前方）；覆盖 `radius` |
| `yRange` | `{min,max}` | **限定高度带**（默认自动高度图） |
| `overlays` | `[hazard,blocks,biome,height,light]` | 附加层（下） |
| `blockTypes` | `[logs,ores,water,…]` | `blocks` 叠加标哪些块 |

**Overlays（回答 群系/高度/etc.）**
- `height` → 每格标地表 y（相对 bot，如 `+3`/`-5`），表头加 `minY/maxY/centerY`。
- `biome` → `biomes` 摘要（范围内各群系 + 格数 + 最近边界）+ 可选逐格群系 glyph 层。
- `light` → 逐格光照（刷怪危险 / 黄昏规划）。
- `hazard`(默认) → §3.3 的致死/可走/落差 glyph。
- `blocks` → 战略块叠加，默认关。

**封顶与诚实（no silent truncation）**：`radius<=32`（65×65 已很费 token，默认仍 12）；非对称 `extent` 按总格数封顶；被裁则输出 `{truncated:true, requested, returned}`，绝不静默缩小。

---

## 4. 两个反射

### 4.1 避险逃跑 —— 全在 `dangerCost`，零 `RetreatChain` 改动
在 `ClientWorldView`（已是 javadoc 说的"live client view"，dangerCost 在此）：
- `beginSearch()`（已每次 repath 快照附近 mob）**同时快照当前 `HazardField`**。
- `dangerCost(foot) += hazardField.lethalPenalty(foot)`——致死格（`cliffDrop>survivableFall`、`deepWater>=deepWaterMax`、contact-damage）**巨大但有限**。软→搜索永不变不可行（**不卡死**）；大→只要有非致死路线就绝不选致死格。再加小惩罚把逃跑偏向平地而非"可生存但带落差"的格。

`Goal.RunAway` 仍最大化距离，但 L171 处巨大致死惩罚压过边缘的"更远"收益 → **逃跑绕开崖而非跳下去**。
两个好性质：(a) **HP 感知**——`survivableFall` 随 HP 降，5 格落差满血没事、3 血致死，每次 repath 重算；(b) **全局**——`goto`/`mine` 也不会走下摔死的落差，但**仅真正致死**的格被罚，正常跨可生存台阶不受影响，且唯一通路是致死走廊时仍可通（同现有 dangerCost 哲学）。

### 4.2 `cornered` —— 修订 gap ③（守安全格 + 事件，**不**自动 bunker）
gap 清单原写 `cornered → BunkerChain(300)`。**撤回**，因与两条既定事实冲突：自治 `BunkerChain` 已被判"太不可控"并 unregister；围 melee bunker 是已知死法。
受控设计：
- 因致死格成本巨大，无改善的非致死路时逃跑**停在最安全可达格守住**——**不走进致死格**。在安全地上挨怪伤，严格优于今天的摔/淹死，且慢到可反应。
- `cornered` 作为**推送事件**上报（同 `duskExposed`），由 **Agent** 决定 bunker/打/搭柱——L2 的事，非自治挖洞。
- **诚实残留（已指派，不藏）**：裸 bot 夜里被远程怪逼到无安全出口且 LLM 没盯时，本切片仍救不了——那需要裸战，属**延后的战斗切片**。本切片的夜survive靠 §4.3 在入夜**前**自保，而非赢一场 cornered 战。

### 4.3 `DuskSecureChain` —— 新，空闲触发，低于用户任务
- `Priorities` 新带 **`IDLE_SECURE = 40`**（恰在 `USER=50` 之下）。低于 USER → **永不抢占活动任务**（调度器自动仲裁；Retreat 100 / Combat 60 也自动压过它）。
- `priority()` 仅当全部成立返 40：`autoSecureAtDusk` 开 · `worldModel.exposedAtNight`（黄昏/夜 + 暴露天空 + 未自保）· `!worldModel.danger`（受攻击绝不挖洞）· **空闲去抖 ~2–3s**（短暂 intent 间隙不触发挖洞，hysteresis，gap ②）。
- `tick()` 驱动既有 **`BunkerProcess`**（挖三填一）——或有可达床则 `SleepProcess`——自保。`sheltered` 转真 / 天亮 / 出现威胁时 priority→0 释放通道。
- 在**动作前**发 **`duskExposed`** 事件，Agent 可抢先用更优计划（去已知基地）而非就地挖。
- 配置 `autoSecureAtDusk` **默认开**（成功判据是无人值守过夜）——但是受控版：空闲、无威胁、绝不凌驾任务。

---

## 5. 决策边界文档（`docs/design/04-perception-and-decision-boundary.md`）

须**可执行**（gap ⑧），非哲学，三部分：

1. **规则（一条判定）**：*能否从本地态在 <1 tick 算出正确答案？*→**L0** 反射（算法）。*是否有清晰成功判据的有界过程？*→**L1** process（技能）。*是否需判断/目标/世界知识/消歧？*→**L2** Agent（LLM，事件驱动，绝不进 tick）。
2. **分类表**：枚举当前系统每个决策、所属层、读的 `WorldModel` 字段。例：躲弹道/不逃进致死格/破窒息→L0；goto/mine/craft/bunker→L1；建什么/在哪建基地/战略上打还是撤/解读 chat→L2。
3. **错位清单（teeth）**：
   - **#1（本 session 实证）**：我(L2)做反应式逃跑 = L2 干 L0 的活 → 死。本切片逃跑反射即修复。
   - `retreatHpThreshold` 不可 MCP 写 → L2 无法调一个 L0 旋钮（接线 gap）。
   - "黄昏自保" 曾 100% L2 → 现 L0 空闲反射 + L2 事件上报。
   - 裸装反骷髅无 L0 反射 → 一个生存决策卡在 L2 → **指派给延后的战斗切片**。
   错位清单**即 backlog**：点名本切片关闭哪些（逃跑安全、dusk）、后续切片拥有哪些；并把 `WorldModel` 命名为 L0↔L1↔L2 共享基底。

---

## 6. 测试

### 6.1 Tier 1 —— server 端 GameTest（保持 ≥91/91，新增）
纯函数缝使其可能（gap ⑥）：
- `HazardField.compute` 对合成 `WorldView`（手搭 崖/深水/熔岩边/封闭盒）→ 断言 `cliffDropDepth, lethal, deepWater, standable`。
- `survivableFall(hp, effects)` → 断言阈值；`cornered`/`safeFleeStep` 对合成栅格 → 单一安全出口指向它、全致死出口→`cornered=true`。
- `AsciiMapRenderer` 对固定栅格 → **字节稳定**字符串 + 图例 + `truncated` 标志。
- scene snapshot **三传输 parity**（in-JVM / RPC / MCP 字节级，同现有 scheduler 测试 #4）。

### 6.2 Tier 2 —— live SurvivalTest 认证（成功判据）
从**公平白天起点**（非中毒存档），守纪律（可观测逐步数据；ESC-pause 仅干地；水中绝不 relaunch）：
- **逃跑 A/B**：bot 临崖/深水 + 威胁 —— 断言 OFF 走进 hazard、ON **绕开**（`walkerDebug` + `mc.client.scene`）。
- **DuskSecure**：空闲暴露 bot 在黄昏 → 自保并活到黎明；活动任务/近威胁时**不**触发。
- **事件**：`duskExposed` + `cornered` 在推送通道发出。
- **端到端**：公平白天起点 → 空闲 → DuskSecure 入夜前自保 → **封闭过夜存活，LLM 退出反应式回路**。

---

## 7. 配置项（全部经 `BotConfig` + 持久化）
| 旋钮 | 默认 | 含义 |
|---|---|---|
| `hazardGridRadius` | 12 | 每 tick HazardField 水平半径 |
| `hazardGridDecimateTicks` | 4 | 地形栅格重算间隔（tick） |
| `deepWaterMax` | 2 | 深水致死阈（块） |
| `autoSecureAtDusk` | on | 空闲黄昏自保反射开关 |
| `sceneQueryMaxRadius` | 32 | `mc.client.scene` 查询半径上限 |

`survivableFall` 由 HP+effects 动态推导（非常量旋钮）。

## 8. 迁移与对现有代码的影响
- **新增**：`WorldModel`(+`Snapshot`)、`HazardField`(+`HazardCell`)、`SurvivalFacts`（纯事实数学）、`AsciiMapRenderer`（纯渲染）、`DuskSecureChain`。
- **改动**：`BotApiImpl.clientTick` +1 行 + `worldModel` 字段 + 注册 `DuskSecureChain`；`ClientWorldView` 持 worldModel 引用、`beginSearch`/`dangerCost` 注入 HazardField；`Priorities` 加 `IDLE_SECURE=40`；`BotConfig` 加 §7 旋钮；`DriverApi`+`prelude.js`+catalog 加 `mc.client.scene`（route + schema + `Driver.client.scene`）；`ThreatScanner` 作为 WorldModel 输入（不改其内部）。
- **不改签名**：`Chain`、`BotProcess`、`WorldView` 接口签名不动（WorldView 仅用既有 `dangerCost`/`beginSearch` 默认方法的 client 覆盖）。
- **新文档**：`docs/design/04-perception-and-decision-boundary.md`。
- **本 session 半成品 `ObserveApi.map`（server 端）**：渲染逻辑抽进 `AsciiMapRenderer`；server 版要么删，要么留作明确标注的 `mc.observe.map` server 概览喂同一渲染器。**建议先只上 client 版**。

## 9. 开放问题 / 后续切片
- 自保触发用 `dayPhase+skyExposed`（当前设计）足够，还是需补一个光照旁路（如树荫下虽 DUSK 但已暗）——live 验证后定，先不加旋钮。
- biome 逐格 glyph 层是否值得（先只做 `biomes` 摘要，逐格按需）。
- 任意 `<deg>` 方位剖面采样（先做 `facing`/轴对齐，任意角度后补）。
- **后续切片**：大脑/记忆（持久资源/基地/死亡日志/已探索 + 目标循环）；任务调度 DAG（doc 00 §8 延后的排队）；战斗（裸装反骷髅/走位/cornered 自动应对）。

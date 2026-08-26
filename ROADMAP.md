# ROADMAP

> worlddriver 的演进路线图：把一个由外部 LLM（Claude）驱动的 Minecraft agent，做成能在
> **无作弊生存**里活下来、并最终**搭下界门、进下界**。控制架构是手段，生存进度是北极星。
>
> 配套：架构设计见 [`docs/design/00`–`04`](docs/design/)；本路线图是它们的实现状态索引 + 前瞻。

图例：✅ 已落地并验证 · 🟡 进行中 · ⬜ 计划中 · 🧊 已延后（含原因）

---

## 1. 控制架构（三层执行模型）

核心模型见 [`docs/design/00-execution-model.md`](docs/design/00-execution-model.md)：**L0 反射**（in-tick 算法，绝不经
LLM）/ **L1 process**（有界技能，数秒闭环）/ **L2 Agent**（外部 LLM，事件驱动，从不进 tick），由
`ProcessScheduler` + 优先级链按 bid 仲裁（HYSTERESIS=5）。

| 设计文档 | 主题 | 状态 |
|---|---|---|
| [00](docs/design/00-execution-model.md) | 执行模型 + 优先级链调度器 | ✅ 调度器、UserTaskChain、移动反射链（Panic/Retreat/Dodge）落地 |
| [01](docs/design/01-knowledge-and-crafting.md) | 合成知识与执行（RecipeResolver / plan.acquire / skill 库） | ✅ |
| [02](docs/design/02-combat-and-defense.md) | 御敌、战斗、装备（CombatChain / autoTotem / autoEquip） | 🟡 有装备 bot 可用；**裸装反骷髅缺 L0 反射**（见 §4） |
| [03](docs/design/03-boss-playbooks.md) | Boss 作战剧本（末影龙 / 凋零） | ✅ 感知 + 结构守卫；实战手动冒烟 |
| [04](docs/design/04-perception-and-decision-boundary.md) | **感知层 + 决策边界基座** | ✅ **本切片（见 §2）** |

当前优先级带：`PANIC=1000` · `DODGE=900` · `BUNKER=300`(user) · `SURVIVAL=100` · `COMBAT=60` ·
`USER=50` · **`IDLE_SECURE=40`**（新）。

### 1.1 🟡 执行层 actuation：去全局键盘化（发包驱动）

三层模型决定"谁掌控"，这一层决定"L0/L1 如何按键驱动玩家"。方向：**绝不写人类共享的 `mc.options.keyXXX`，
改驱动玩家自己的输入对象（`AvatarInput` 的 impulse/jumping/shiftKeyDown）+ `setSprinting` + gameMode 包**
——vanilla 自动把它们序列化成 `ServerboundMovePlayer` / `PlayerCommand` 包，这就是"发包不用按键"。

- **根因**：`mc.options.keyXXX` 是人机共享对象，MC 只在 GLFW 按下边沿重置 isDown；旧执行层每 tick `setDown` /
  idle `releaseKeys` 抢这些键 → **即使没 Agent 连接，手动 WASD/空格也被 ~50ms 清零卡手**（= Baritone
  InputOverrideHandler 抢键问题），且移动硬绑镜头 yaw、slew 滞后时前进键顶错向墙。
- **收益**：人机共存（手动游玩不被抢键）+ 镜头解耦动态纠偏（impulse 沿真实 heading，不等镜头追上）。
- 🟡 **已落地**（compiled + headless GT 绿，待 live A/B）：`InputReleaseGate` edge-gate（bot 真按过键才清一次，
  从不驱动 → 永不碰人类键）+ `AvatarInput` 全 locomotion 通道（`commandMove` 解耦 + `commandForward/Jump/Sneak`）+
  `BotInput` facade + `setSprinting`=sprint 发包（反编译 `LocalPlayer` 证：`keySprint.setDown` 在每站点都与
  `setSprinting` 配对，纯冗余可删）→ **Walker + 14 进程/链全迁**（Build/Backfill/Mine/Follow/Farm/BboxFill/
  Escape/Bunker/Bridge/Elytra/Panic/Dodge/Tower/Sleep）。
- ⬜ **待续**：CombatProcess 环绕 strafe（需 2D 向量命令 forward+back+左右）；AutoSwim/ClutchController 反射
  （与 `InputReleaseGate`/clientTick 时序耦合，单独打通）。终态后 `releaseKeys` 只剩管 keyAttack。
- 🧊 **刻意边界**：`keyAttack`/`keyUse`（挖掘/用物）**保留键位**——直接调 `gameMode` 会让客户端预测闪烁、破坏
  "方块是否挖完"判定（MineProcess 注释明示），vanilla `continueAttack`/`useItem` 管线才正确，且不撞移动。
  **原则：能干净发包的才发包，挖掘/用物不能。**

---

## 2. ✅ 切片：感知层 + 决策边界基座（doc 04）

**目标**：把"决策放错层 = 死"这一生存病根，从架构上修掉——反应式生存下沉到 L0 算法，LLM 只做策略。
**成功判据（达成）**：裸 bot 从 FAIR 的黄昏暴露态、在 LLM 完全不进反应回路的前提下，靠反射活过一夜。

落地内容（branch `feat/perception-decision-boundary`，15 任务，GameTest **107/107**）：

- **`WorldModel` 黑板**（`bot/world/`）：每 tick 在 `clientTick` 顶部重算派生事实，发布不可变
  volatile `Snapshot`（镜像 BotState 模式）；零决策、零副作用。
- **纯函数缝**：`HazardField`/`HazardCell`（崖/深水/熔岩致死格，over `WorldView`，headless 可测）、
  `SurvivalMath`、`SurvivalFacts`（cornered/fleeStep）、`AsciiMapRenderer`（致死 glyph 的 ASCII 地图）。
- **客户端权威感知**：`mc.client.scene`（client `WorldModel`，与反射同源 → 不可能 desync）vs
  `mc.observe.scene`（server `ServerWorldView` 概览，含 plane/extent-clamp/height overlay 查询）。
- **避险逃跑下沉 L0**：`HazardField.lethalPenalty` 注入 `ClientWorldView.dangerCost`（不改 RetreatChain）。
- **黄昏自保反射** `DuskSecureChain`（prio **40**，低于 USER）：空闲 + 暴露 + 无威胁 + 去抖才触发，驱动
  `BunkerProcess`；上报 `duskExposed`/`cornered` 边沿事件让 L2 可抢先。**LIVE-CERTIFIED**：clean idle →
  挖坑 → cornered → **latch 守住通道** → 封顶（skyExposed→false）→ 满血过夜（latch 修复 71406a3）。
- **决策归层规则 + 分类表 + 错位清单**：见 doc 04。

---

## 3. ⬜ 前瞻切片（按依赖顺序）

每个切片自成一个 spec → plan → 实现闭环，复用 `WorldModel` 黑板作为共享基底。

1. **大脑 / 记忆**（next）：持久化资源 / 基地 / 死亡日志 / 已探索 + 目标循环。把 `WorldModel` 包成
   完整世界模型 + 记忆，让 L2 决定"随时间做什么"。这是 NeTher 多 session 长程目标的承重件。
2. **任务调度**：多步计划 DAG（doc 00 §8 延后的排队）；在 `WorldModel` 之上排意图。
3. **战斗内容**（兑现错位 #5）：裸装反骷髅（断线 / 掩体 / 走位）、`cornered` 的自动应对、给
   `WorldModel` 加 LOS / 掩体字段。
4. **感知增量**：竖直剖面查询（plane:vertical）、facing-相对查询、biome overlay（doc 04 错位 #6 延后项）。

---

## 4. 已知缺口 / 延后项（teeth）

来自 doc 04 §5 + 生存实测 + 本切片 live-cert：

- 🧊 **裸装反骷髅 / 反蜘蛛无 L0 反射** → 一个生存决策仍卡在 L2。延后到**战斗切片**。
- ⬜ **`retreatHpThreshold` 不可 MCP 写** → L2 调不动一个 L0 旋钮（接线 gap）。
- 🧊 **`cornered` 不自动 bunker**：本切片刻意不做（尊重历史 + 围 melee bunker = 死）；作为事件上报，
  由 L2 决定 bunker / 打 / 搭柱。
- ⬜ **`activeProcessDetail` 对 DuskSecure 托管的 bunker 为 null**：`BunkerProcess.statusDetail()`
  （SEALED/DIG_DOWN）只在 UserTaskChain 托管时被 status 暴露；DuskSecure 托管时 agent 只能由
  `activeChain:duskSecure` + `skyExposed` 推断。（本切片 cert 新发现，非 latch 修复回归。）

---

## 5. 🟡 生存里程碑（北极星：进下界）

控制架构的检验场。无作弊（fill/setblock/tp/effect/give 仅限测试竞技场），靠 MCP 动词推进。

| 阶段 | 目标 | 状态 |
|---|---|---|
| N0 | 复活 → 东进脱离死亡出生点 → 木镐（重建 Stage1） | ✅ |
| N1 | 床 anchor（重置出生点，耐久 keystone） | ⬜ |
| N2 | 石器 + 食物 | 🟡 |
| N3 | 铁 → 桶 ×2 + 打火石 | ⬜ |
| N4 | 浇筑黑曜石（×10+） | ⬜ |
| N5 | 建门框 + 点火 + **进下界** | ⬜ |

> 已验证的生存基本功：水下寻路 + 破岸上岸、踮脚采高处原木、挖三填一 bunker（封顶 + 破出 + 黄昏自保
> 反射）、死井挖阶梯爬出、reach-across 采水上原木、配置持久化。详见 `docs/design/` 与各 `validation/*.js`。

---

## 6. 🟡 本轮执行排期（窗口 0–4，定于 2026-08-25）

> 这一节排的是**当前这一轮的执行顺序**，不是长期架构演进（那在 §1–§4）。
> 每一笔的调查记录、证据键、判据与重开条件都在 [`TODO.md`](TODO.md) 里，**代号一一对应**；
> 这里只回答「按什么顺序做」，不回答「还欠什么」。

**排序凭什么——四条，按优先级**：

1. **一次编译窗口只放互不干扰的笔。** 共享工作树，每个 gradle 运行任务都编译对方此刻的半成品，
   所以一个窗口里的两笔行为改动＝下一趟排练是双变量。
2. **仪器先于修法。** 零行为改动的笔可以任意多笔同窗口，因为它们不改变下一趟的结局，
   只改变下一趟**能不能被判读**。而「先补测量」那几条要的读数只有排练/真梯产得出来，
   所以它们必须**赶上**下一趟，晚一窗口就晚一整趟。
3. **当前前沿优先。** 12 级现在**同时**挡在浇筑那一族（第 8 格没浇成／`forge.carved 66/67`／
   门洞单遍清渣）和「壁龛淹到只剩楼梯一根可用柱」上，不挡在 J47／J44c／J70 上。
4. **13–20 级与工程债都不许与真梯同趟。**

### 6.1 ⬜ 窗口 0（排练在跑，一行都不碰树）

零代码、纯读日志，是唯一能在排练期间推进的事。

| 笔 | 内容 |
|---|---|
| **Q7c 的离线回放** | 拿 ladder-14 已录的 174 案／816 案回放新计数规则。必须抓住那两案且不误伤正常绕行（`journey03Wood` 绕树那段是现成阴性样本） |
| **丙 的分族读数** | 把 `304373fc` 那趟 18011 tick 的 38 条 `*.stairsBroken` 按 `lava*`（上行腿）／`cast*`（下行腿）拆开读；它决定窗口 1／2 里要不要给「淹」留一笔 |

### 6.2 ⬜ 窗口 1（排练退出后的第一个编译窗口）—— 仪器批，零行为改动，一次编译一并落

| 序 | 笔 | 为什么在这一窗口 |
|---|---|---|
| 1 | **J72 第 1/2 笔**：`aimThatLandsIn` 五个 `continue` 出声（`JourneyPour:885-893`）；tries=1 的 `ctx.fail` 改说「身体不在它自己选的落脚格上」 | 直指当前前沿，且**零行为改动**。判词错族会把下一个读者送去挖 k=0 |
| 2 | ✅ **J70 已落**：`JourneyDrain:179` 实测就是 `HoldStill(DRAIN_TICKS)` | 原因照留：`drain.N.upstream` 裁决的说法不能被夸大一倍（判词印 200 而实际等 100）。**验收仍欠**：下一趟 12 级 `drain.7` 是否翻成「已排干」 |
| 3 | **J54 的出处行**：给撞上的水落「天然／上级留／自浇」一行（照 12 级 `water0.spent` 的问法） | 「先补测量」的对象；不落这一行，11 级两条腿都不许修 |
| 4 | **J41 头条**：`WorldDriverJourneyScenes:2521` 的 `tunnel.fell` 走 `ascendByTowering` 的 `String tag` 入口，三行 `recordExit` 一行都没有——而它爬的是**岩浆廊道** | 纯仪器；其余 12 个入口**不做**（无证据） |
| 5 | **J75 的 `enderman.stall.*` 一行** | 不占排练槽，只占编译窗口 |
| 6 | **J40 的 ②**：`JourneyRig.await` 逐 tick 判活时顺手读 `getAirSupply()` 与血量，**无条件按腿落行** | 「过线中止去补救」的线画在哪要分布；量级＝每腿一行，不用节流 |
| 7 | **Q15c**：PREP 无条件写 `readyTicks`/`readyMs`（`stagewright-scenes/pack.js`） | 只加仪器不改行为，随下一轮闸读分布 |

⇒ 然后**一趟排练**把 1–6 的读数一次收齐；Q15c 的分布随下一轮全量闸收。
**J71 不需要这个窗口**——它要的是「连跑 N 趟 `runRehearsalIntegratedServer` 列
`forge.carved`×结局表」，不改一行码。

### 6.3 ⬜ 窗口 2 —— 行为改动，一笔一笔，按前沿排

| 序 | 笔 | 为什么这个位置 |
|---|---|---|
| 8 | **J72 第 3 笔**：记下身体走不到的落脚格，重试时从 `standToPour` 候选里排除（按 cast 清零） | 唯一的行为改动，且直指前沿。必须排在 1/2 之后：没有那两行仪器，它的三态判据读不出来 |
| 9 | **J44c**：撤掉坑沿加价的「每趟重算」，改回去程算一次的快照；**加价本身一字不动** | 预期读数是「无影响」（15 条 `*.rimTax` 恒 752），所以它**不会混淆** 8 的归因，可同窗口。它买的是每趟一次 25×25×9 全量扫描的开销 |
| 10 | **J47 写死步骤**：上岸失败就在脚下垫一块 | 排在最后：它的验收是 ashore 场景翻绿（闸里读），不需要排练；而它会退掉引擎缺陷的最后一个证人 |

⇒ 排练 → **双 loader 全量闸** → 真梯。

> 序号 **11 空缺**是有原因的，别当漏排：它原来是 **丙**（水源的存活窗口）。`304373fc` 那趟把水
> 读成了死因，丙 改判「先补测量」，而那一步是**读日志**，所以它挪到了窗口 0。读出族之后再插队。

### 6.4 ⬜ 窗口 3 —— 13–20 级

| 序 | 笔 | 说明 |
|---|---|---|
| 12 | **J24 的 grep**（`JourneyShaft.supportUnder` 用 `rig.ctx().level()`） | **13–20 级开工的第一步**，不是 J33 之后 |
| 13 | **J33**：两套 `WorldView` 两张破坏价目表（含 Q14 的残余） | 引擎批，**双闸，不与真梯同趟**。五条分歧已定位、三条承重断言已核 |
| 13b | **J47 引擎侧那半**：末节点被 `within` 的**水平**项（`cur2<0.45`）在无落脚时吃掉 | 同属引擎批，**排在 13 之后**：判词与凭据在 `TODO.md` 的 🅹 块。⚠️ 不能照搬 `airborneClimbConsume`——它 scoped `nx != null` 且 `!p.isInWater()` 是量出来的排除 |

### 6.5 ⬜ 窗口 4 —— 工程债（红绿不影响真梯排期，但 J7 有硬触发）

| 序 | 笔 | 排这个位置的理由 |
|---|---|---|
| 14 | **J43**：9 处手写天光高度收进 `JourneyTerrain.daylightAt` | 最便宜：机械、不改证据键、`:common:compileTestmodJava` 即闸。⚠️ 动手前逐个确认 9 处 `level` 的声明类型（`daylightAt` 形参是 `ServerLevel`），`JourneyRehearsal:983` 要的是 `BlockPos` |
| 15 | **J15**：scenes 侧 `fillFrom`（`WorldDriverJourneyScenes:2671`，`:2673` 的 `holdForUse` 返回值仍丢）补同款「拿不到桶」守卫 | 第一步只补守卫，**合并成单份留给证据键允许变的那一轮** |
| 16 | **Q7c 的 Java 半**：`WalkerTickSearch:85` 两条盲区（`!res.goalReached()`、`distSqr(foot) > 4`），复用 `walkerFutileSearchCap`，**不加新 knob** | 前置是窗口 0 的离线回放调好阈值 |
| 17 | **J55**（`prelude.js` 的 tunnel 提升成真路由，委托 `applyDirection`）＋ **`prelude.js` 的 `\| 0` 取整**与 `Params.toInt`/`SchemaValidator` 分叉 | 两笔都是**行为变更，必须各自配闸**，且与主线无关。⚠️ 不要顺手统一 `back`/`backward` |
| 18 | **J7**：拆 `BotConfig.java`（2993/3000） | **触发式**，位置固定在「**任何要新增 `BotConfig` knob 的修法之前**」——上面 16 笔里没有一笔要加 knob（Q7c 明确复用现成的），所以它排在这里；哪一笔要加 knob，它就提到那一笔之前 |

**代价**：这个序判错的暴露形式很具体——窗口 1 里混进了一笔行为改动，
下一趟排练的死因就归不了因（症状：读数变了而找不到唯一的自变量）；
或者「先补测量」那几条有一条没赶上窗口 1，于是下一趟排练白跑一次它要的分布（症状：
那条的读数键在归档 results 里 0 行，与「未触发」长得一模一样）。

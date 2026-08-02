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
  挖坑 → cornered → **latch 守住通道** → 封顶（skyExposed→false）→ 满血过夜（latch 修复 d6aa99e）。
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

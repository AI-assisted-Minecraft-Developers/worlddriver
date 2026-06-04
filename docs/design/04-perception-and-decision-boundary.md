# 设计文档 04 —— 感知层与决策边界

> Agent 控制架构演进的第一个垂直切片（Perception + Decision-Boundary foundation）。
> 前置：[00-execution-model](00-execution-model.md)（L0/L1/L2 执行模型与优先级链调度器）。
> 配套：`docs/superpowers/specs/2026-06-04-perception-decision-boundary-design.md`（设计）、
> `docs/superpowers/plans/2026-06-04-perception-decision-boundary.md`（实现计划）。

## 1. 问题：L2 被卷进反应式回路 = 死

无作弊生存实测里反复出现的死亡，根因不是地图，是**决策放错了层**：

- 我（L2/LLM）以 LLM 延迟做躲怪/逃跑控制，怪每秒 2–4 HP，连死多次。反应式生存必须是算法（引擎 in-tick），LLM 只该做策略。
- 逃跑寻路把低血裸 bot 带下悬崖/入深水（`Goal.RunAway` 最大化距离，软 `dangerCost` 在边缘被"更远"压过）。
- 感知靠拼 5 个 observe 调用，且 server 视角与 client 反射所见会 desync。

这份文档定一条**决策归层规则**，并把已知错位列成可执行 backlog。承重基底是新的 `WorldModel`。

## 2. WorldModel：L0↔L1↔L2 的共享感知基底

`WorldModel`（`bot/world/WorldModel.java`）在 `clientTick` 最顶部每 tick 计算一次，发布不可变 `Snapshot` 到 volatile 字段（同 `BotState`/`ProcessScheduler` 的 off-thread 快照模式）。它**只回答"此刻周围什么是真的"，不做任何决策**——决策留在各自的层。

```
        WorldModel  (派生事实，每 tick 重算 — 零决策、零副作用)
        ╱            │                         ╲
   L0 反射         L1 process                L2 Agent (snapshot)
   ClientWorldView 经 WorldView.dangerCost   mc.client.scene + ASCII 地图
   DuskSecureChain  读 HazardField           mc.observe.scene (server 概览)
```

- **纯函数缝**：`HazardField` / `SurvivalFacts` / `AsciiMapRenderer` / `SurvivalMath` 只吃 `WorldView` + 标量，无 client 类型 → headless GameTest 可测（server 经 `ServerWorldView`，见 `50/51_scene_*.js`）。
- **客户端权威**：`mc.client.scene` 强制读 client `WorldModel`，与反射所见同源——感知与行动物理上不可能 desync（这正是消除本切片病根的设计）。
- **副作用归调用方**：边沿事件 `duskExposed`/`cornered` 由 `BotApiImpl.clientTick` 发，不在 `WorldModel` 里——保持黑板纯净。

## 3. 决策归层规则（一条判定）

> **能否从本地态在 <1 tick 算出正确答案？** → **L0** 反射（算法，in-tick，绝不经 LLM）。
> **是否是有清晰成功判据的有界过程？** → **L1** process（技能，数秒闭环）。
> **是否需要判断 / 目标 / 世界知识 / 消歧？** → **L2** Agent（LLM，事件驱动，从不进 tick）。

## 4. 分类表

| 决策 | 层 | 读的 WorldModel 字段 / 机制 |
|---|---|---|
| 躲苦力怕爆炸 / 弹道 | L0 (PanicChain/DodgeChain) | 威胁位置 / 速度 |
| 不踏进致死格（崖/深水/熔岩）逃跑 | L0 (ClientWorldView.dangerCost) | `HazardField.lethalPenalty` |
| 破窒息 / 自动游向岸 | L0 (AntiSuffocate/AutoSwim) | inWall / inWater |
| 低血脱离 | L0 (RetreatChain) | health + 威胁 |
| 黄昏自保（空闲、暴露、无威胁） | L0 (DuskSecureChain, prio 40) | `exposedAtNight` + `cornered` + 威胁 |
| goto / mine / craft / bunker | L1 (BotProcess) | 目标 + 成功判据 |
| 取哪条资源路线 / 配方树 | L1 (mc.plan.acquire) | 库存 + 配方 |
| 建什么 / 在哪建基地 / 战略上打还是撤 | L2 (Agent) | 整张 scene + 记忆（后续切片） |
| 解读 chat / 长程目标排序 | L2 (Agent) | 事件流 + 目标 |

## 5. 错位清单（teeth）= backlog

可执行的"谁该修、归谁"清单，不是哲学：

1. **L2 做反应式逃跑 = 死**（本 session 实证）。→ 本切片修：避险逃跑下沉到 L0（`dangerCost` 注入 `HazardField` 致死惩罚），不改 `RetreatChain`。**已修。**
2. **黄昏自保曾 100% 靠 L2**（Agent 必须盯天色）。→ 下沉到 L0 空闲反射 `DuskSecureChain`（prio 40，低于用户任务）+ `duskExposed` 事件上报，让 L2 可抢先用更优计划。**已修。** 注：这是受控版——既往自治 `BunkerChain` 因"太不可控"被 unregister；新版仅空闲、暴露、无威胁、去抖后触发。
3. **`cornered`（无安全出口）**：本切片**不**自动 bunker（尊重 #2 的历史 + 围 melee bunker = 死）。逃跑因致死格成本巨大而自然停在最安全可达格；`cornered` 作为事件上报，由 L2 决定 bunker/打/搭柱。**已实现守安全格 + 事件。**
4. **`retreatHpThreshold` 不可 MCP 写** → L2 调不动一个 L0 旋钮。**未修**（接线 gap，后续）。
5. **裸装反骷髅/反蜘蛛无 L0 反射** → 一个生存决策卡在 L2。**延后到战斗切片**（断线/掩体/走位）。
6. **感知查询的方向/群系/竖直剖面**（plane:vertical / facing-extent / biome overlay）→ 本切片实现了 center/radius/extent-clamp/height overlay + 致死 glyph，竖直剖面与 facing 相对查询**延后**。

## 6. 后续切片（本文档之外）

- **大脑/记忆**：持久资源/基地/死亡日志/已探索 + 目标循环（消费 WorldModel + 记忆决定"随时间做什么"）。
- **任务调度**：多步计划 DAG（doc 00 §8 延后的排队）。
- **战斗**：裸装反骷髅、走位、cornered 的自动应对（错位 #5）。

`WorldModel` 是这些切片的共享基底——大脑切片把它包成完整的世界模型 + 记忆，调度切片在其上排意图，战斗切片给它加 LOS/掩体字段。

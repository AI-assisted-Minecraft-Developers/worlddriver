# 实体右键交互动词（entity-interact verb）设计 — DRAFT

> 状态：DRAFT。用户提出「写一个与实体交互的功能，尽量不加工具」后暂离；
> 场景取舍按约束选定了推荐方案（通用右键动词），待用户回来确认。
> 日期：2026-07-10

## 1. 问题与现状

mod 已有实体/物品交互动词：

| 动词 | 路径 | 覆盖 |
|---|---|---|
| `mc.bot.attackEntity` | `MultiPlayerGameMode.attack` | 左键实体（打怪） |
| `mc.bot.useItem`（无 pos） | `gameMode.useItem` | 空挥右键（吃/喝/拉弓/扔雪球） |
| `mc.bot.useItem` + `pos` | `gameMode.useItemOn`（合成 BlockHitResult） | 对方块面右键（放置/骨粉/桶） |

**缺口：右键实体**（`gameMode.interactAt` / `interact`）完全没有。而骑船/骑马、
打开村民交易 UI、剪羊毛、挤奶、喂食驯服/繁殖、拴绳、给盔甲架穿装备……全部走这条路。
Agent 目前对这些交互一律无能为力。

## 2. 约束

- **尽量不新增 MCP 工具**（用户明确要求）。
- 与现有动词的契约风格一致（同步、返回 `{ok, result, consumed, ...}`、
  snap-look、`setShiftKeyDown` 显式管理——见 `InteractionCommands.java`）。
- 会话内 MCP 工具 schema 冻结：新参数在**本会话**经 `mcp__worlddriver__*` 调用会被
  harness 按旧 schema 静默剥掉，验证须走 RPC websocket（`worlddriver-rpc` 技能）；
  下个会话起 MCP 原生可用。

## 3. 候选方案

**A（选定）：扩展 `mc.bot.useItem` 的参数分发** —— 加 `entityId` 分支。
`DriverApi` 里该路由本就按参数分发（`pos` → `useItemOn`），再加一层
`entityId` → `useItemOnEntity` 完全顺势。零新工具、零新路由；
schema/描述更新后自然可发现。三种模式语义统一为「右键」：无参=空挥，
`pos`=对方块，`entityId`=对实体。

**B：新增 RPC-only 路由 `mc.bot.interactEntity`（不进 MCP catalog）**。
不算「MCP 工具」但仍扩了方法面；MCP 侧不可发现，Agent 得靠文档才知道存在。
相比 A 没有任何实现量优势，弃。

**C：`mc.script.eval` 现场脚本**。已证伪：eval 跑在 server 线程、Rhino 沙箱
拦 JS→Runnable 适配器，无法调用客户端 `gameMode`（见 reference_client_relaunch）。

## 4. 设计（方案 A）

### 4.1 行为

`InteractionCommands.useItemOnEntity(params)`，复刻 vanilla
`Minecraft.startUseItem()` 的 ENTITY 分支（已 decompile 确认，1.21.1）：

```
interactAt(player, entity, EntityHitResult, hand)
  └─ !consumesAction() → interact(player, entity, hand)
最终 consumesAction() → player.swing(hand)
```

- 参数（全部在 `mc.bot.useItem` 上）：
  - `entityId` int — 触发本分支；来源 `mc.query q='entities'` 的 `id`
    （与 attackEntity 同源）。
  - `hand` `main|off`，默认 main（沿用 `parseHand`）。
  - `lookAt` bool 默认 true — 先把 yaw/pitch snap 到实体 bbox 中点
    （同 attackEntity 的对准逻辑；服务端角度/反作弊校验需要）。
  - `sneak` bool 默认 false — 交互期间按住 shift。现有动词一律
    `setShiftKeyDown(false)`；这里改为 `setShiftKeyDown(sneak)`，因为部分交互
    是 sneak-gated（如 sneak+右键打开已驯服马的物品栏、对着陆架空手蹲右键取物）。
    交互完成后恢复 false。
- `EntityHitResult`：用 `new EntityHitResult(entity)`（vanilla 缺省命中点）。
  盔甲架分部位穿戴等依赖精确 hit 向量的场景先不做（见 §7 非目标）。
- 返回：
  ```
  {ok, entityId, type, result, consumed, distance,
   riding: <交互后 player.getVehicle() 的实体类型或 null>,
   screen: <交互后 mc.screen 的类名或 null>}
  ```
  `riding`/`screen` 让 Agent 一次调用就能确认「上船了吗」「交易 UI 开了吗」，
  不用追加 observe。
- 错误：`entityId` 缺失/非整数、实体不存在、target==self、无 player/gameMode
  —— 均 `{ok:false, error}`，措辞对齐 attackEntity。
- 距离：不做客户端硬校验（与 attackEntity 同姿态——服务端静默拒绝超距），
  返回里带 `distance` 供 Agent 自查。

### 4.2 触点（4 文件 + 测试）

1. `bot/InteractionCommands.java` — 新增 `useItemOnEntity`（~45 行，
   与 attackEntity 对称）。
2. `bot/BotApi.java` + `bot/BotApiImpl.java` — 接口方法 + delegate（各 1-3 行）。
3. `api/DriverApi.java` — 路由分发改为三叉：
   `entityId` → `useItemOnEntity`；`pos` → `useItemOn`；否则 `useItem`。
4. `mcp/catalog/BotTools.java` — `mc.bot.useItem` 描述加第三模式 +
   `entityId`/`sneak` prop（服务于下个会话的 MCP 调用；本会话验证走 RPC）。

不动 `Avatar` 抽象：MCP 动词层从来直走 `mc.gameMode`（attackEntity 同），
`Avatar.interactEntity` 缝等 server-agent 有 process 需要时再开。

### 4.3 错误处理

沿用 `onClient(...)` 包装（客户端线程执行、异常转 `{ok:false,error}`）。
交互后无论成败都把 shift 恢复 false（try/finally），避免污染后续按键状态
（InputReleaseGate 教训的同族预防）。

### 4.4 测试

- **headless GameTest 不适用**：本动词是 `onClient` 客户端路径，GameTestServer
  没有 client（服务端 gametest 只测 Avatar/进程层，见 `serverBuildArena` 注释）。
  不为测试而开 `Avatar.interactEntity` 缝（YAGNI，见 §7）。
- **Live 验证 = 主裁判**（客户端已在 ScriptTest 世界，项目纪律「live 是真相」），
  3 个用例，全走 RPC `mc.bot.useItem {"entityId":N}`（绕开本会话冻结 schema）：
  1. 挤奶：give 桶 + summon 牛 → 返回 `consumed:true` 且手上变 `milk_bucket`；
  2. 骑乘：summon 船 → 返回 `riding:"minecraft:boat"`，`mc.observe.player` 佐证；
  3. 交易 UI：summon 村民（带职业）→ 返回 `screen:"MerchantScreen"`，
     `mc.client.screen.info` 佐证；随后 `mc.client.screen.close` 收尾。
  负例：不存在的 entityId、entityId=自己 → `{ok:false}`。
- **客户端 `/agent test` 套件**（`ScriptTest.run` 注册）：若既有交互动词
  （attackEntity/useItemOn）已有套件用例则对称加一条（骑船最稳定、无物品依赖）；
  若没有先例则不新开测试面，live 三用例为准——实现计划阶段定。

## 5. 数据流

```
Agent (MCP/RPC) → mc.bot.useItem{entityId,...}
  → DriverApi 路由分发 → BotApiImpl → InteractionCommands.useItemOnEntity
  → onClient: snap-look → setShift(sneak) → interactAt → (interact) → swing
  → 采集 riding/screen → 返回 JSON
```

## 6. 兼容性

- 现有两种 useItem 模式行为零变化（分支只在 `entityId` 存在时进入）。
- 旧会话/旧 schema 的调用不受影响（没有 entityId 就走老路）。

## 7. 非目标（YAGNI）

- 盔甲架精确部位交互（需要真实 hit 向量）——等有需求再补 `hitPos` 参数。
- 骑乘后的移动控制（Walker/输入层牵扯大，独立课题）。
- 村民交易「全流程」（选单成交走既有 screen.tree/slotClick 工具链，
  必要时另立 TradeProcess 课题）。
- 下船/下马：sneak 键已有（`mc.client.input.key`），不重复造。
- `Avatar.interactEntity` 缝：server-agent 侧暂无消费者。

## 8. 开放问题（待用户确认）

1. 场景取舍是否认可「通用右键动词」打底？（交易全流程/骑乘控制/牧场自动化
   都以它为地基，可后续分课题）
2. `sneak` 参数是否要（不要的话删掉，行为恒 `setShiftKeyDown(false)`）？

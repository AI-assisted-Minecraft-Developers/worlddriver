# 规划器的水模型 — 只有水面那一层是节点

## 旧模型的前提与它长出的补丁

`WorldView.canStandAt` 过去把任意深度的水格都当地板：`isWater(foot)` 顶替了「脚下有实心」这一句，
于是水下每一格都是节点，横向游泳用普通 `Walk`（10）走。真实身体做不到这些：浮力把它托在水面那一格，
水下没有可以起跳的地面，也没有可以横着「走」的深度。为了让 A* 不去规划身体做不到的事，
仓库先后加了 `isFloatingWater`、`isSubmergedAscent`、`isSubmergedFoot`、`isDeepWaterSurfaceLanding` 四个判据、
八种税（`waterCell`、`submerged`、`climbOut`、`descend`……）和三十多个 Move 里几十条「漂着不能起跳／不能落进深水」
的守卫。它们全是给「水是地板」这一句错误前提打的补丁。

另有一条虚构边：`SwimUp` 允许从水面格「浮出」到上方的空气格（只要旁边或下面有实心）。
它是规划器表达「在这里出水」的方式，执行器的水中接管（`WalkerTickClimb.pillarTakeoverTick`）
在那里真正地搭脚踏、压岸、挖岸。虚构之处在于它对手里有没有方块、身体能不能站住一概不问，
所以 A* 可以把出水点规划在一根空水柱上，身体只能在那里上下颠簸。

## 现在的模型（`BotConfig.pathfinderSurfaceWaterNodes`，出厂 ON）

- **横向节点只有水面格**：`canStandAt` 对「脚格是水且头格也是水」的格子返回 false。
  没顶的水格只在 `swimUp` / `swimDown` / `swimDownSurface` 链上出现，任何横向移动都不能落在那里。
  起点若在水下（掉进水里、快淹死），搜索从它出发，第一步只能是上浮。
- **出水只走一条边**：`SurfaceClimbOut`（边名 `climbOutPlace`），从水面格到它上方的空气格，
  只在执行器真能做的三种形状下合法，且手里要有方块：
  一格深（脚下实心，原地起跳补格）；旁边有地板可放侧脚踏（`sideFoothold`）；
  上方空气格旁有岸可压（原版游泳撞块 +0.3 的上浮，`bankBeside`）。
  价格与它替掉的 `swimUp` 边相同（25），出水税照旧叠在上面——两个模型差在合法性，不差在口味。
- **`SwimUp` 只在水里升**：目标格是空气时直接 false。
- **`PillarUp` 不从水里起**：一格深水的原地 `pillarUp` 会被干地搭柱执行器接走，
  而它等一次湿身体永远给不出的着地（`wd.clientFlowingChannelPlaceOut` 曾因此从 56 tick 变成失败）。
- **潜水搜索保留旧模型**：`PathFinder.Search` 建搜索时把 `Capability.DIVE` 的选择告诉视图
  （`WorldView.diveSearch`），`surfaceWaterNodes()` 在潜水搜索里返回 false——
  水下基地那种目标（`wd.underwaterBase`）本来就要身体没顶横穿，规则全部回到旧模型。
- **潜水目标不被吸附**：`Walker.snapGoalToStandable` 对 `dive:true` 且目标在水里的 goto 不做吸附；
  新模型下那一格本来就不可站，吸上去会把潜水者带到错误的地方。

执行器一行没改。调研（2026-09-04）证明执行器对水节点几乎全按几何判：`cwp.y > foot.y` 且横向 2 格内
就进入出水接管，只有 `swimDown` 与 `swimAshore*` 两族按边名判，新边的形状（水面格 → 上方空气格）
与旧的 `swimUp` 虚构边完全一致，所以接管照常触发。

## 量到的结果

真客户端 22 条 `wd.client*` 全过，改前后同一台机器：

| 场景 | 改前 tick | 改后 tick |
|---|---|---|
| `wd.clientFlushBankClimbOut` | 93 | 107 |
| `wd.clientFlushBankClimbOutEmptyHanded` | 98 | 107 |
| `wd.clientOneHighBankPlaceOut` | 163 | 176 |
| `wd.clientTwoHighBankPlaceOut` | 156 | 222 |
| `wd.clientThreeHighBankPlaceOut` | 335 | 177 |
| `wd.clientTwoHighBankDigOut` | 414 | 262 |
| `wd.clientOneHighStoneBankPickaxeOut` | 199 | 166 |
| `wd.clientFlowingChannelPlaceOut` | 179 | 179 |
| `wd.clientFlowingTrenchPlaceOut` | 265 | 228 |
| `wd.clientGotoStartsMidAirOverWater` | 76 | 121 |
| `wd.clientShallowPoolStepOut`（新） | — | 103 |
| `wd.clientShallowPoolStepOutEmptyHanded`（新） | — | 125 |

`wd.clientGotoStartsMidAirOverWater` 在第一趟（还没有公转破解时）从 77 变成 478：身体第 75 tick 就到了目标格旁，
之后 390 tick 是 TODO 里记过的干地转向 EMA 公转（yaw 从 52 一路绕到 2453，误差恒在 90° 上下）。
那是 `stepUp` 直接上岸、没经过接管、没有 `aimSmooth.reset()` 的场合，新模型只是让它露出来。
修法在 `WalkerTickAim.smoothingAlpha`（`walkerOrbitBreaksAimLag`）：干地、身体在动、
航向误差在 45°–170° 之间的 tick 累计超过 12 个、**且身体自己的 yaw 同向累计转过 180°**，
α 从 0.08 切到巡航的 0.5。上限 170° 留给每次重规划正常出现的 ±180° 翻转（对极点直接吸附试过一次，回退了）；
同向转角那一条是第一版没有的：只看误差时 `wd.bridgeStepTwoBypassNoPlace` 的 dogleg 绕行
（质心朝东、路线朝北，误差恒 90°）被当成公转，EMA 被拉向质心，身体走下平台去堵自己的捷径——
正是它要防的 r29 陷阱。绕行的身体在拐角转一次就直走，只有公转的身体每 tick 都往同一边转。
累计只被「转向反了」和「停下」清零，误差短暂低于 45° 只是不计入：身体经过目标格时原始方位会扫过一遍，
第二版曾在这里清零，`wd.clientFlowingTrenchPlaceOut` 一次 929 tick 的公转（yaw 绕了 1200°）因此从未触发。

专用服全量闸改前后各一趟。改后第一趟多出五条红，全是旧模型的虚构被当成场景前提：
`wd.underwaterBase` 的潜水（上面的放开）、`wd.deepWaterSubmergedCross` 「复现旧 bug」那一腿（改为跑旧模型）、
`wd.deepWaterClimboutNoBlock` 空手 +2 岸（旧模型让服务端身体游着上了 +2 岸，真客户端从来做不到；
现在两具身体都是挖，场景改为在 ARRIVED 后再跟 20 tick 判着地）、
`wd.journeyCraftStepsAsideForRoom`（`JourneyStation.groundWithRoomNear` 把 4 格深的池底选成合成点，改为只选干格）、
以及上面那条 dogleg。

## 旧模型还留着什么

四个浮力判据和八种税都还在，开关关掉时它们仍是旧模型的一部分；开关开着时其中大半成了死码
（`submergedTax` 只剩 `swimDown` 链能碰到，`isSubmergedAscent` 被 `canStandAt` 先一步拒掉）。
先在两个模型之间量一段时间，再决定删哪些。

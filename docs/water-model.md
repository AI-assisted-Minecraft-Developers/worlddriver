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

## 开阔水面：原版的俯卧冲刺游泳（`walkerSurfaceSprintSwim`，出厂 ON）

「水面慢」的账先量后改。新模型下 A* 过 52 格的湖只要 458 个节点、39 ms，规划不是瓶颈；
慢在两处：规划器把深水当死区（`HazardField` 对深度 ≥2 的水格加 10000，路线贴着岸沿绕），
执行器在水面永远只能踩水（约 2 格/秒）——原版 `LocalPlayer.aiStep` 只在眼睛没入水时接受冲刺，
而水面颠簸靠一直按跳把头托在水面上，于是每 tick 请求的冲刺在 `travel` 之前就被取消。

### 原版的规则（反编译核过，1.21.1）

- 冲刺在水里只有 `isUnderWater()` 时才被接受；`isInWater && !isUnderWater` 时取消，除非已经是 SWIMMING 姿态。
- 姿态由 `Entity.updateSwimming` 决定：从「冲刺 且 眼睛入水 且 脚格是水」进入，之后只要「冲刺 且 在水里」就保持。
- 姿态里的冲刺只在两种情况下被客户端取消：没有前进冲量（`forwardImpulse > 1e-5`，**按着潜行时豁免**）、离开水。
- 姿态的眼高 0.4；`Player.travel` 在 y+0.9 那格还是水时把竖直速度推向视线的 y 分量；
  `jumpInLiquid` 每 tick +0.04；冲刺中的游泳者没有重力，水阻 0.9（不冲刺 0.8）。
- 服务器把玩家自己的实体数据也回发给他（`ServerEntity.broadcastAndSend`），
  共享旗标那一个字节里同时装着潜行、冲刺、游泳三个位。

### 巡航状态机（`WalkerTickDrive.surfaceCruise`）

1. **进入**：当前节点是水面格、横向至少 1 格、脚下是深水、头顶无实心、眼睛露出、
   往后 8 个节点里没有 3 格内的岸。冲刺由巡航直接给出，只让危险和低血量压过它；
   台阶跳、水面跳都关掉；`AutoSwim` 的溺水兜底和 `WalkerTickClimb` 的出水接管都给它让路。
2. **下潜**：按潜行（原版 `goDownInWater`，每 tick −0.04）直到姿态出现；
   40 tick 还没有姿态就退回颠簸 100 tick（`CRUISE_DIP_MAX_TICKS` / `CRUISE_COOLDOWN_TICKS`）。
3. **确认窗**：姿态出现后再沉 3 tick（`CRUISE_SINK_TICKS`），然后悬停到第 12 tick（`CRUISE_CONFIRM_TICKS`）。
   这一段是给服务器的：客户端翻起冲刺后下一 tick 才发 START_SPRINTING，服务器套用它时自己的游泳位还没算出来，
   紧接着把整个旗标字节回发，客户端的游泳位被盖成 0、姿态退回 CROUCHING/STANDING，
   眼高变成 1.27/1.62——身体若已回到水线，眼睛就露出，原版按「在水里、没入水」取消冲刺。
   原版玩家不中招是因为他们入水那一刻还在深处；巡航把身体按在深处等这次回发过去。
   这条链是用 JDWP 在 `Entity.setSprinting` 上打 logpoint 抓到的（调用栈 `LocalPlayer.aiStep`，
   那一刻 `isSwimming()` 已是 false、姿态 CROUCHING）。
4. **贴水线**：脉冲式按跳。按住不放会被 `jumpInLiquid` 对着 0.9 的水阻顶出水面（`inW=false` 直接退出巡航）；
   一次脉冲滑行约 9 倍当时的竖直速度。眼睛离水面 0.3 格以上时只要上升慢就点，最后 0.3 格只在静止时点，
   停下来时眼睛刚好露出（0.4 眼高对 8/9 的源方块水面），air 回满并保持。
5. **换气闩**：air 低于 130 就退出巡航颠簸换气，回到 280 以上再进；眼睛贴水线后这条几乎不触发。

### 执行器其它几处配套

- **到达判横向**（`WalkerTickProgress` 的 `cruiseUnder`）：巡航身体在节点下方 1–2 格，
  `within`／`passed` 的高差门槛把每个节点都读成没爬上去的台阶，指针只剩弧长投影在身体越过下一节点后补一步，
  永远落后一个节点；驱动朝身后的节点掉头，前进冲量变成 −1，原版按「没有前进冲量」取消冲刺——每两格重演一次。
- **偏离判横向**（`WalkerTickStallDetect` 的 `offPath`）：三维格距把沉着的身体每隔几格判成偏离，
  重规划从沉底的脚起一串 `swimUp`，把巡航打断。
- **拉直上限**（`PathSmoothing.stringPull`，`WATER_PULL_SPAN`=2）：一条 49 格的拉直边让指针永远不前进，
  卡死后水中预占把路清空；上限 3 又会让沉着的脚（三维距离多算一格 y）每 tick 偏离。
- **直线先于快启**（`tryWaterBeeline` 从脚所在列的水面格起算，排在 `tryQuickStart` 之前）。
- **规划器**：`pathfinderDeepWaterPriced`（深水只按水税计价，不再是 `HazardField` 的致死格，接触伤害除外）；
  `waterDangerPenalty` 12 → 3：巡航约 0.19 格/tick，对走路 0.22 格/tick 的代价 10，加上每次下潜的开销。

### 量到的结果（`wd.clientOpenWaterCross`，52 格湖，真客户端）

| 版本 | 上岸 tick |
|---|---|
| 改前（踩水） | 550 |
| 巡航但沉在节点下方（指针落后、冲刺反复丢） | 1140–1339 |
| 到达判横向、按住跳 | 510 |
| 脉冲跳、偏离判横向 | 390 |
| 两档脉冲 | **348** |

最后一趟从第 3 步到第 26 步一次连续巡航，y=220.60、眼睛露出、air=300、冲刺不掉，约 0.19 格/tick。

### 用 JDWP 调真客户端时

客户端线程一挂起，集成服务器照样走，水里的身体十几秒就淹死（`error=player-death`）；
在热路径上评估条件的 logpoint 同样把客户端拖慢到淹死。行号要从 Fabric 侧的命名 jar 取
（`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-<hash>/…jar`），
NeoForge 补丁 jar 的行号不一样，挂上去是空的；条件表达式碰不到私有字段。

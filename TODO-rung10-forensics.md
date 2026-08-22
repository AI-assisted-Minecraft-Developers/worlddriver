# rung10 取证：9/20 卡在 y=40

调查对象：`fabric/run-journey/results-9of20-stuckAtY40.jsonl` + `fabric/run-journey/log-9of20-stuckAtY40.log`

- 现象：`wd.journey09Iron` PASS，`wd.journey10PortalKit` FAIL，其上全部 BLOCKED。
- 失败判词：`走不到砾石柱：目标 29,76，停在 BlockPos{x=97, y=40, z=52}（水平相距 72 格，已重规划 3 次）`

### 一句话结论

**第 10 级不是在第 10 级坏的。** 第 9 级最后一段熔炼等待里，一具**浮在水面、没上岸**的身体
在无人驾驶的 1000 tick 里以水中终端速度沉了 25 格、漂了 61 格；熔炼取炉没有 reach 闸，
所以第 9 级照样 PASS；第 10 级接手的是一具泡在 y≈32–40 水袋里、西侧贴墙的身体，
而目标在正西 68 格 —— A* 只能交回朝东的残段，三次 1200 tick 一格没近。

| 问题 | 结论 | 信心 |
|---|---|---|
| Q1 下沉机制 | 第 9 级 `smelt` 等待期间，无人驾驶的身体在水里沉降+漂移 | 高（竖直）／水平推力来源未定 |
| Q2 判词含义 | 「启发值 1200 tick 没变好」；**不看有没有计划**，本案**计划在手**；`best dist` 是启发代价不是格数 | 高（代码 + 776 逐位对上） |
| Q3 为什么动不了 | 淹没在水中 + 西侧贴死一堵墙（目标正在西边） | 水=高；墙=中高（由坐标签名推出） |

---

## Q1 — 身体如何从 y=64 掉到 y=34–40

**答：不是第 10 级干的。下沉发生在第 9 级最后一段 `smelt` 等待里 —— 身体在无人驾驶的
状态下，在水里以水中沉降终端速度漂了 1000 tick。信心：高。**

### 时间线（全部来自 `log-9of20-stuckAtY40.log`，行号为文件行号）

第 9 级 vein2 出井是**游走式**逃生（`vein2.exit#3.walkerFallback=True`），目标是「爬到
y=64」这个高度目标，不是某个具体落脚点：

```
1511: [22:39:49] [pathfinder] search-begin owner=goto start=99, 44, 92 goal=YLevel[y=64] maxNodes=100000 ...
1586: [22:39:52] [pathfinder] search-begin owner=goto start=99, 49, 97 goal=YLevel[y=64] maxNodes=100000 ...
1591: [22:39:52] [pathfinder] search-begin owner=goto start=99, 49, 97 goal=YLevel[y=64] maxNodes=100000 ...
```

`YLevel[y=64]` 一到就算完成 —— 于是 22:39:52 之后 goto 结束，身体停在水面附近，接着熔炼段
开始：

```
1592: [22:39:58] [smelt] placeNearby: click failed cell=BlockPos{x=92, y=64, z=96} (Block{minecraft:air}) below=BlockPos{x=92, y=63, z=96} (Block{minecraft:grass_block}[snowy=false])
1593: [22:39:58] [smelt] INIT ok: furnace=93, 63, 95 target=6× raw_iron (have=6)
1594: [22:39:58] [smelt] LOAD ok: in=slot3 fuel=oak_log loaded=2400t need=1200t reserve=some target=6 budget=1660t
```

然后这六行心跳是本案的核心证据：

```
1595: [22:39:59] [journey] 心跳 IRON smelt 本段第  16/12000 tick 身体= 93,64,95
1596: [22:40:09] [journey] 心跳 IRON smelt 本段第 216/12000 tick 身体=103,59,74
1597: [22:40:19] [journey] 心跳 IRON smelt 本段第 416/12000 tick 身体=109,54,60
1598: [22:40:29] [journey] 心跳 IRON smelt 本段第 616/12000 tick 身体=115,49,47
1599: [22:40:39] [journey] 心跳 IRON smelt 本段第 816/12000 tick 身体=121,44,44
1600: [22:40:49] [journey] 心跳 IRON smelt 本段第1016/12000 tick 身体=126,39,44
1605: [22:40:59] [pathfinder] search-begin owner=goto start=128, 34, 44 goal=Block[target=BlockPos{x=128, y=32, z=44}]   ← 第 10 级开场的 steppingOff
```

### 为什么这是「在水里漂」而不是「在走路」

1. **垂直速率是常数，且正好等于水中沉降终端速度。** 每 200 tick 恰好 −5 格，连续五段没有
   一次例外 → −0.025 格/tick。原版水中 `travel()` 的竖直递推是 `v ← 0.8·v − 0.005`，
   不动点 `v = −0.005/(1−0.8) = −0.025`。走地形不会得到五段完全相同的 Δy。
2. **这 1000 tick 里 walker 完全没有在工作。** `[pathfinder] search-begin` 是 walker 活跃时
   的重规划节拍；把全日志按秒统计，walker 一活跃就是每 ~4 秒至少一条（例如第 10 级卡死期间
   22:43:07 / 22:43:12 / 22:43:16 / 22:43:20 … 一条不落）。而 **22:39:53 → 22:40:58 这 65 秒里
   一条 search-begin 都没有**。没有计划在跑，却移动了 61 格水平 + 25 格竖直。
3. **紧接着的第一条 walker 日志直接说了身体在水里。** 第 10 级刚开场：

   ```
   1611: [22:41:00] [walker] 起跳来源: 序=1/6 t=18093 支=swimColumn 处=WalkerTickDrive.java:1181 身体=126, 32, 44 ...
   1613: [22:41:01] [walker] 起跳来源: 序=3/6 t=18095 支=swimUp     处=WalkerTickDrive.java:1181 身体=126, 32, 44 ...
   ```

   `swimColumn` / `swimUp` 两个分支名由 walker 自己判定，身体此刻在一根水柱里。
4. 水平位移的形状也吻合「被水流推着」而不是「走过去」：Δz 是 −21 / −14 / −13 / −3 / 0，
   衰减到零；Δx 稳定在 +6 → +5 → +2。都低于水流推动的水平终端速度 0.014/(1−0.8)=0.07 格/tick。

### 于是四个候选机制的裁决

| 候选 | 裁决 |
|---|---|
| 去砾石柱的路上开了 breakBlocks 一路挖下去 | **否**。下沉在第 9 级结束**之前**就完成了；第 10 级开场身体已经在 128,34,44。 |
| 掉进第 9 级游泳的那片水里 | **是，但不是"掉"**——是在水里以终端速度**沉**了 1000 tick，同时被水流横推 61 格。 |
| 第 9 级 `toY=64` 是对塔的断言、不是身体的落点 | **部分成立且更糟**：`toY=64` 当时是真的（22:39:58 身体确在 93,64,95），但这个读数在 60 秒后就作废了，而第 9 级再没测过。`endedIn=93,96（起塔柱是 99,92 —— 不是同一柱）` 已经提示了这次"爬升"是游走而非垒塔。 |
| `makeRoomForAStation` / `steppingOff` 挪的 | **否**。`station.steppingOff.0 = 128, 34, 44 → 128, 32, 44` 只挪了 2 格，而且它的**起点**就已经是 34 —— 它是结果不是原因。 |

### 补强：第 9 级结束时身体**没有站在地面上**

`[smelt] INIT ok: furnace=93, 63, 95`，而同一秒的心跳是 `身体=93,64,95`。
`身体=` 打的是 `blockPosition()`（脚的取整），所以 **93,63,95 就是脚正下方那一格** ——
熔炉被放进了身体脚底下那格。**能放东西进去，说明那一格是可替换的（水或空气），
身体并不是踩在它上面**。再加上前一段整条上行路线都是水柱里的 `支=stepUp 水=true`
（22:39:49 六条），以及紧接着就以水中终端速度开始下沉 ——
**第 9 级的 `toY=64` 记录的是一具浮在水面上的身体，不是一具上岸的身体。信心：高。**

### 水平那 61 格是被什么推的 —— 未能确定

竖直那一维有确凿签名（−0.025 恰是水中沉降不动点）。水平这一维**判不了**：

- 逐段方向：(+10,−21)/(+6,−14)/(+6,−13)/(+6,−3)/(+5,0)。前三段方向几乎完全一致
  （单位向量 0.43,−0.90 / 0.39,−0.92 / 0.42,−0.91），之后拐向 +x。
- 逐段速率：0.117 → 0.076 → 0.072 → 0.034 → 0.025 格/tick，单调衰减。

「一个残留的前进输入没松手」（仓库既有伤疤 `releasing-the-controls-is-not-braking.md`）
和「被流动水推着」都能画出这个形状，日志里没有 yaw、没有输入标志、没有 `Fluid` 流向，
**分不开**。能分开的测量：在 `journey` 心跳里同时打
`getDeltaMovement()`、`yRot`、`zza/xxa`（输入）、`level.getFluidState(foot).getFlow()`；
或者在 `settle`/`drive` 切换时打一行「上一条指令的输入是否已清零」。
不过 **这一维叫什么名字并不改变修法** —— 两种解释都是「无人看管的身体在水里」。

### 顺带一个独立缺陷（同一段日志）

```
1601: [22:40:58] [smelt] COLLECT: furnace=BlockPos{x=93, y=63, z=95} made=6× iron_ingot taken=6 ...
```

取炉时身体在 ~128,34,44，离炉子 **60 格开外、低 29 格**，`COLLECT` 照样成功。所以第 9 级
判 PASS 时完全没有察觉身体已经漂走 —— 熔炼段既不驾驶身体、也不校验身体还在炉子旁。
（与仓库既有结论 `the-avatar-mined-through-rock.md` 同类：写路径没有 reach 闸。）

## Q2 — "no progress for 1200 ticks" 的含义

**答：既不是「找不到路」也不严格是「找到了路走不动」。它字面只说一件事 ——
「目标启发值 `Goal.estimate(foot)` 连续 1200 tick 没有变得更好」。它完全不看身上有没有计划。
本案里身上**确实有**计划。`best dist` 是**启发代价**（mod 的代价单位，≈tick×10），
不是格数、也不是平方距离。信心：高（代码 + 数值对上了）。**

### 产生处

`common/src/main/java/net/magicterra/worlddriver/bot/movement/WalkerTickStallDetect.java:63-81`：

```java
double d = wk.goal.estimate(foot);
if (d < wk.goalSpin.bestDistToGoal - 0.5) {
    wk.goalSpin.bestDistToGoal = d;
    wk.totalTicks = 0;
} else if (a.breakHeld() || wk.waterClimb.digging) {
    // 正在挖 —— 挂起放弃计时
} else if (++wk.totalTicks > BotConfig.walkerTotalTickBudget) {
    wk.lastError = "no progress for " + BotConfig.walkerTotalTickBudget + " ticks (best dist=" + Math.round(wk.goalSpin.bestDistToGoal) + ")";
    return wk.terminalReport(Walker.Step.FAILED, PathTrace.Outcome.STUCK, wk.lastError, "failed:" + wk.lastError, p.blockPosition());
}
```

- `BotConfig.walkerTotalTickBudget = 1200`（`BotConfig.java:32`）。
- 这段的**整个判据只有 `wk.goal.estimate(foot)`**：不读 `wk.path`，不读 `wk.step`，
  不读搜索结果。所以这条消息对「有没有计划」保持沉默。
- 这个分支上面的注释自己说明了它是为哪种情形设计的：

  > `Hard tick budget: prevents infinite walking when A* returns a partial path for an
  > unreachable goal (best-effort fallback path > 1 node satisfies ok()).`

  也就是说，**它预设的典型场景恰恰是「手上有一条（尽力而为的）路，却走不到目标」**。

### `best dist` 的单位 —— 用本案的数字反推确认

`Goal.XZ.estimate`（`common/src/main/java/net/magicterra/worlddriver/bot/Goal.java:111-115`，
走 `xzHeuristic`，`Goal.java:74-78`）：

```java
static double xzHeuristic(int dx, int dz) {
    dx = Math.abs(dx); dz = Math.abs(dz);
    int diag = Math.min(dx, dz);
    return 14.0 * diag + 10.0 * Math.abs(dx - dz);
}
```

目标 `XZ[x=29, z=76, radius=0]`，身体停在 `97,40,52`：`|dx|=68`，`|dz|=24`，
`14×24 + 10×44 = 336 + 440 = **776**` —— 与 `gravel.goto.2` / `gravel.goto.3` 里的
`best dist=776` **逐位相同**。

结论：`best dist` 是**启发代价**（文件头注释：「same units as `Move.cost()` (≈ ticks × 10)」），
不是格数（那是 72），也不是平方距离（那是 5200）。而且既然 776 恰等于身体**当前所在格**的
启发值，说明第 2、3 次尝试里 **身体一格都没有靠近过目标**（`bestDistToGoal` 从
`Double.POSITIVE_INFINITY` 起，第一 tick 就落到起点值 —— `Walker.java:358`）。

### 起火那一刻，身上有计划吗？—— 有

日志里 walker 的 `步进`（步指针推进）通道是无条件打印的（`walkerDebug` 本次为 **false**：
全日志 `repath from` 0 行、`anti-churn` 0 行），它在卡死期间一直在打路点：

```
[22:43:50] [walker] 步进: 序=1/64 因=arc 旧步=1 新步=2 w=98, 40, 52 nx=98, 39, 52 身体=97, 40, 52 ...
[22:43:50] [walker] 步进: 序=2/64 因=arc 旧步=2 新步=3 w=98, 39, 52 nx=98, 39, 53 身体=97, 38, 52 ...
[22:45:04] [walker] 步进: 序=7/64 因=arc 旧步=4 新步=5 w=98, 38, 53 nx=98, 37, 53 身体=97, 38, 52 ...
```

有 `w`（当前路点）、有 `nx`（下一路点）、有 `旧步/新步` —— **计划在手**。而且这条计划的
方向是 **x 变大、y 变小**（98,40,52 → 98,39,52 → 98,39,53 → 98,38,53 → 98,37,53），
而目标在 **x=29，即正西**。所以 A* 交回来的是一条**朝反方向的 best-effort 残段**：
能找到路，但不是通向目标的路。

`[pathfinder] search-begin` 在整个卡死期间每 ~4 秒一条从不间断（22:43:07 / :12 / :16 /
:20 / :25 / :29 / :33 / :37 / :41 / :45 …），也证明搜索一直在跑、一直在返回东西。

### 更要紧的一点：真正对症的那句判词被水关掉了

`WalkerTickSearch.java:116-129` 有一条**专门**说「目标从这里到不了」的终止：

```java
if (BotConfig.walkerFutileSearchCap > 0 && !res.goalReached()
        && !a.breakHeld() && !wk.waterClimb.digging && !world.isWater(foot)      // ← 水里豁免
        && !(!res.hasPath() && world.hasStuckPenalties())) {
    ...
    wk.lastError = "no route progress after " + wk.searchGov.futileSearches
            + " consecutive searches — goal unreachable from here (best dist=" ...
```

`!world.isWater(foot)` 把**水里**整个排除了（注释：「Water is exempt: an afloat bot
legitimately repaths many times while stationary」）。本案身体全程在水里 →
这条永远不触发 → 唯一能触发的就是 tick 预算那句。而 tick 预算那句的注释明说：

> `the tick-budget's "no progress for N ticks" keeps meaning a transient stall`

**所以这次 FAIL 报出来的判词，按代码自己的定义，是「暂时性卡顿」——
而实际情况是「从这里到不了目标」。判词与病因错配，这本身是一条缺陷。**

## Q3 — 97,40,52 为什么动不了

**答：身体整个泡在水里（脚格和脚上格都是 `minecraft:water`），并且被**贴死在西侧的一堵墙上** ——
而西正是目标方向。它不是被封死（能上下浮、能小幅东移），是**朝目标那一侧过不去**。
信心：水=高（walker 自报的方块状态）；「西墙」=中高（由 X 坐标恒定在 97.300 推出，
不是直接读到的方块）。**

### 水：walker 自己报的方块状态

`起跳来源` 这条日志带块状态，卡死期间每一条都写着水：

```
[22:43:55] [walker] 起跳来源: 序=2/6 t=21584 支=swimUp 处=WalkerTickDrive.java:1181 身体=97, 38, 52 精确=(97.300,38.000,52.700) 路点=98, 38, 52 wp.y-foot.y=0 水=true 没顶=true 脚格=Block{minecraft:water} 脚上=Block{minecraft:water}
[22:44:52] [walker] 起跳来源: 序=1/6 t=22723 支=swimUp 处=WalkerTickDrive.java:1181 身体=97, 38, 52 精确=(97.300,38.000,52.503) 路点=98, 38, 52 wp.y-foot.y=0 水=true 没顶=true 脚格=Block{minecraft:water} 脚上=Block{minecraft:water}
```

`脚格` 和 `脚上` **都是水** → 身体是**淹没**的，不是站在水边。分支名 `swimUp` /
`swimColumn` / `wiggle` 也都是水里那一族。

这与第 9 级 40 格外的那九条 `washedOff` 是**同一片水**的强候选：
`vein2.exit#3.climb.N.state = onGround=true inWater=true y=44.00`（99,44,92），
本案 97,38–41,52。Q1 已证明身体是从 (93,64,95) 一路在水里沉过来的，中间没有出水记录 ——
**同一片连通水体的可能性很高（但没有直接读过 93..97 / 52..95 之间的方块，未证实）。**

### 「贴死在西墙上」的证据

`精确=` 是浮点身体坐标。玩家碰撞箱半宽 0.3，所以 `x=97.300` 意味着箱体西缘正好压在
`x=97.000` 上 —— 也就是**西邻格 x=96 是实心**。整个卡死期间，跨越 60 秒的多条日志里
**X 恒等于 97.300，一位不差**：

```
[22:43:51] 恢复跳 ... 身体=97, 38, 52 精确=(97.300,38.000,52.590)
[22:43:52] 恢复跳 ... 身体=97, 38, 52 精确=(97.300,38.000,52.700)
[22:43:53] 恢复跳 ... 身体=97, 38, 52 精确=(97.300,38.000,52.700)
[22:43:54] 恢复跳 ... 身体=97, 38, 52 精确=(97.300,38.000,52.700)
[22:44:52] 起跳来源 ... 精确=(97.300,38.000,52.503)
[22:45:04] 步进  ... 精确=(97.300,38.967,52.514)
[22:45:05] 恢复跳 ... 精确=(97.300,38.000,52.467)
```

Z 在 52.4–52.7 之间自由变动、Y 在 38.0–41.0 之间上下浮，**只有 X 被钉住**。
身体在水里可以浮、可以沿 Z 挪，但**一格也向西挪不动**。目标 x=29 在西边。

### 于是三条候选的裁决

| 候选 | 裁决 |
|---|---|
| 在水里 | **是**，且是**淹没**（脚格与脚上格皆水），不是站在岸边 |
| 被封死（entombed） | **否**。`没顶=true`（头顶没被封），能上浮到 y=41，能沿 Z 挪，`恢复跳` 每次都真的跳起来了 |
| 站在崖沿上 | **不是崖沿，是墙**。X 恒定 97.300 是贴墙签名，不是站在边缘 |

### 计划为什么救不了它

A* 每 4 秒返回一次结果，但返回的都是**朝东、朝下**的短残段
（`98,40,52 → 98,39,52 → 98,39,53 → 98,38,53 → 98,37,53`）。也就是说：
**从这个水袋里，A* 找不到任何一条向西的边**，只能交出让启发值变差的 best-effort。
于是身体在原地上下浮、`恢复跳` 一轮轮空打，`bestDistToGoal` 从第一 tick 起就是 776 再没降过，
1200 tick 到期 → FAIL。三次重试、以及中间那次 `gravel.viaMidpoint = 63,64`
（换成中途点重走），全都从同一格出发，所以**三次给出一模一样的 776**。

最后 720 tick（22:45:14 → 22:45:50）连 `步进` 都不再打一行（该通道当时还剩 56 条额度，
不是被截断），说明**步指针彻底不动了** —— 计划在手、一步也推不动。

### 没能回答的部分（诚实标注）

- **x=96 那格具体是什么方块**、以及这片水袋的边界在哪里，日志里没有。本报告的「西墙」
  是从坐标签名推出来的，不是读到的。
- **该不该用镐挖穿它**：这次 goto 的约束集（是否 `allowBreak`）没有在日志里出现。
- 能一次回答上面两条的测量：把世界存档 `fabric/run-journey/world` 载入，或在同种子同坐标
  用 `mc.observe.blocks` / `mc.world.*` 扫 `x∈[93,99], y∈[36,45], z∈[48,56]` 一个盒子，
  同时打印那次 goto 的 `SearchProfile` / `Constraint` 列表。

## 因果链（把三问串起来）

1. 第 9 级 vein2 出井：塔垒不起来（九条 `washedOff`，`placed=0`），交给 walker 兜底，
   目标是 `Goal.YLevel(64)`。身体**游**到了 y=64 —— 但是在水柱顶上，没上岸。
2. `JourneyShaft.recordExit`（`JourneyShaft.java:478-509`）只记 `toY` / `endedIn` /
   `gained` / `pillarStock`。**没有一行问「身体是不是还在水里」「脚下是不是实心」**。
   于是 `gained=20/20` 判绿。
3. 熔炼段 `rig.drive(new SmeltProcess(...), 12_000, ...)`
   （`WorldDriverJourneyScenes.java:1783`）。`SmeltProcess.smeltWait`
   （`SmeltProcess.java:280-292`）只查菜单和炉子方块，**既不驾驶身体、也不校验身体位置**。
   1000 tick 无人看管 → 沉 25 格、漂 61 格。
4. `SmeltProcess.collect` 没有 reach 闸，60 格外照样把铁取回来 → 第 9 级 **PASS**。
5. 第 10 级从一具泡在 y≈32–40 水袋里的身体开始。目标 `Goal.XZ(29,76)` 西向 68 格，
   而身体西侧贴着实心墙 → A* 只能交回朝东朝下的残段 → 三次 1200 tick 全部
   `best dist` 一动不动 → FAIL → 上面 10 级全 BLOCKED。

**每一环都有前一环的绿灯做背书。真正的断点在第 2 环：一条不问「出来了没有」的出井判据。**

## 最小修法提议（只提，不实现）

### 主修：出井的成功判据必须是「离开水、踩在实心上」，而不是「y 够高」

改 `common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/journey/JourneyShaft.java`
的 `recordExit`（第 478 行起）：在写 `toY` 的同时，无条件补一行

```
climbName + ".endedOn" = <脚下方块> / onGround=<..> / inWater=<..>
```

并把 `ascendByTowering(...)` 的完成条件从
`rig.player().blockPosition().getY() >= surfaceY`（第 244 行）
改成「`>= surfaceY` **且** `!player.isInWater()`」。达到高度但仍在水里 → 不算出井，
继续走已有的重试/兜底路径；重试用尽仍afloat → 就在第 9 级 FAIL，判词写「爬到了 y=64
但仍浮在水里」。

- 依赖 **Q1 正确**（下沉发生在第 9 级、起点是一具浮着的身体）。
- 与仓库既有结论同源：`Goal.ignoresY()` 的 javadoc 已经写过「忽略某一维的目标，
  到达时对那一维没有意见」。`Goal.YLevel` 是它的镜像 ——
  它对 X/Z **和「脚下是什么」** 都没有意见。`endedIn` 那一行已经补上了列漂移，
  这次要补的是同一族里剩下的那半：**介质**。
- 成本：一个 evidence 行 + 一个布尔与项。**不改任何 `bot/` 生产代码。**

### 配套一（便宜、独立）：`SmeltProcess` 加一条距离读数

`SmeltProcess.smeltWait` 已经每 tick 读一次 `furnacePos` 的方块了；顺手把
`player.distanceToSqr(furnacePos)` 也读了，超出交互距离就写进 `s.smelt.lastError`
（或直接 fail）。这次 `COLLECT` 在 60 格外成功，等于第 9 级的绿灯**从来没有验证过身体在场**。

- 依赖 **Q1 正确**。
- 注意：这次容器菜单在 60 格漂移中**一直没关**（`smeltWait` 的
  「熔炉界面意外关闭」分支从未触发），而真玩家的 `Player.tick()` 会因
  `stillValid` 失败自动关闭容器。**这可能是一条独立的 fake-player parity 缺口，
  但我没有验证原因，只观察到了现象** —— 归 `wd-parity` 更合适。

### 配套二：把「水里到不了目标」这条判词接回来

`WalkerTickSearch.java:116-118` 的 futile-search 闸带 `!world.isWater(foot)` 豁免，
所以水里永远只能报「no progress for 1200 ticks（暂时性卡顿）」。这次的真实病因是
「从这里到不了目标」。建议不是取消豁免（注释里那条 in-water anti-spin 的理由仍然成立），
而是在**水里**单独给一条：连续 N 次搜索 `!goalReached()` **且** `bestDistToGoal` 没降
**且** 身体位移 < 1 格 → 报 `goal unreachable from here (afloat)`。

- 依赖 **Q2 + Q3 正确**（判词与病因错配；身体确实在水里且被墙钉住）。
- 这条**不会救回这次跑**，但会让下一次的 FAIL 判词直接指向病因，
  而不是让读者去猜「是不是走得太慢」。

### 明确不建议做的

- **不要**给第 10 级的 gravel goto 打开 `allowBreak` 让它挖穿西墙。
  仓库已有 `a-mine-is-a-walk-first.md`：寻路开着 breakBlocks 会拆掉自己刚布置的东西；
  而且那治的是第 5 环的症状，不是第 2 环的病。
- **不要**只把 `walkerTotalTickBudget` 调大。三次尝试的 `best dist` 一格没降，
  给多少 tick 都一样。

## 未解的公开问题（交回给调用者）

1. `x=96, y=38, z=52` 那格是什么方块？这片水袋的边界在哪？—— 需要读存档或复现后扫方块。
2. 第 9 级那 61 格水平漂移，是残留输入还是水流？—— 需要在心跳里加
   `getDeltaMovement()` / 输入标志 / `getFluidState().getFlow()`。
3. 为什么 60 格外的熔炉菜单没有被 `stillValid` 关掉？—— fake-player parity 问题。
4. `wd.journey09Iron` 是不是应该在本次就判 FAIL？我倾向「是」（它交出的身体不满足
   下一级的前置），但这会把一次 9/20 变成 8/20，属于判据口径决定，不是我该单方面定的。

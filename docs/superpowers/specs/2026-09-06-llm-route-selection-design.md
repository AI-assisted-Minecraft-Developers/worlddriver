# 路径选择功能的设计：让 LLM 精确规划路线、避开怪群与敌人视线

> 状态：设计稿，尚未实现。读者：开发者。
> 相关阅读：`docs/superpowers/specs/2026-07-04-llm-navigation-intent-layer-design.md`（意图层）、
> `docs/superpowers/specs/2026-09-05-human-in-the-loop-verification-design.md`（人工验证环节）。
> `docs/design/04-perception-and-decision-boundary.md` 已标为历史文档，它的决策归层规则
> （一刻内能算出的归反射层，需要判断的归 LLM）仍是本设计的依据，落地在 `bot/scheduler/` 的各条链里。

## 0. 结论先行

LLM 不逐格选路，它定条件；逐格选路仍由 A* 在游戏刻内完成。「精确规划」体现在四件事上：

1. LLM 能**说清楚要避开什么、避到什么程度**：哪些区域绝对不能进、哪些只是尽量绕、怪群多密算危险、
   谁的视线要躲、走廊有多宽、必须依次经过哪些点。
2. LLM 能**先看再走**：一个只规划不执行的调用返回路线的分段风险明细（暴露在谁的视线里多少格、
   离最近的怪多远、穿过哪些禁区）；LLM 也可以把自己画的折线交给游戏打分。
3. LLM 看完预览可以**要求就走这一条**：预览带回 `planId`，执行时带上它，机器人直接采用预览过的
   路线而不重新搜索。
4. 世界变了 LLM 会**收到通知**：路线进入了远程怪的视线、所有可行路线都要穿过禁区、绕路代价超过
   阈值，这些事件推给 LLM，由它决定换条件、换目标还是停下。近身躲闪、逃离爆炸这类一个游戏刻内
   就要出答案的事，仍由现有的反射层处理，不经过 LLM。

## 1. 现有基础与它的边界

| 已有机制 | 位置 | 本设计要知道的事实 |
|---|---|---|
| 硬约束 `Constraint` | `bot/pathfinder/constraints/`：`NoBreak`、`NoWater`、`YFloor`、`YCeil`、`ColumnRadius`、`LeashHardRadius` | 在邻居循环里按边判定、剪掉不允许的后继；起点节点本身无条件进开表。是函数式接口，没有名字。`LeashHardRadius` 和 `ColumnRadius` 带「归位」规则：`to` 在圈外时只放行 `dist(to) < dist(from)` 的边，逐边判定、不记状态；没有它，起点在圈外时所有出边都被剪掉、没有后继，机器人卡死 |
| 软代价 `CostModifier` | `bot/pathfinder/modifiers/`：`AvoidRegion`、`PreferYBand`、`ShorelineHug`、`LeashAnchor`、`RiskCostTable` | 只加不减，A* 的可采纳性不变。每项在搜索里按名字记账（`tax(name, …)`）；`explainTaxes` 沿最终路线把每个代价项各算一遍，`explainExpansion` 给全搜索的同一本账，两者在 `walkerDebug` 或 `-Dworlddriver.pathfinderTaxLog=true` 下打印（`explainTaxes` 的 javadoc 只写了前者，过时了） |
| 每次搜索的危险代价 `dangerCost` | 仅 `ClientWorldView` | `WorldView` 的方法，一半是地形危险（岩浆、火、悬崖、`HazardField` 的致死格、卡住格），一半是按生物加价：怪物快照是 `ClientWorldView` 自己在无参的 `beginSearch()` 里建的，加价走 `ThreatAvoidance.cost`，整段挂在全局开关 `avoidMobs` 上，默认关；三个参数：近战半径 `mobAvoidRadius` 6、远程半径 `rangedAvoidRadius` 16、峰值 `mobAvoidPenalty` 40。`PathSmoothing` 拉直时也读它，防止把绕开危险的弯拉直回去；它另有一个按 `SearchProfile.bias()` 求和的版本。服务器端的 `LevelWorldView` 没有这个方法，专用服务器上的身体今天完全不避怪。本设计把生物那一半搬走（2.3 节），地形那一半不动 |
| 威胁感知 `ThreatScanner` | `bot/combat/ThreatScanner.java` | 两端通用，但只收敌对生物（`Enemy`）和 40 刻内打过我的生物，不收玩家；扫描盒以身体为中心，半径是入参（默认 24），够反射层用，不够覆盖一次几十上百格的搜索。它的视线判定是自己写的 `Level.clip`（`Block.COLLIDER`、`Fluid.NONE`），水不挡视线 |
| 分片搜索 | `WalkerTickSearch`、`BotConfig.pathfinderSliceMs` = 6 | 客户端和服务器端一样分片。日志里的「unbounded-slice」是场景把分片设成无限，不是服务器的默认。跨搜索另有每刻 20 毫秒的总预算 `TICK_BUDGET_NANOS` |
| `PathFinder` 的构造 | `Walker.newPathFinder`（正式的唯一构造点）与它的快速搜索；`PinchArena`、`PlanProbeTool`、`HorizonArena` 三个调试工具直接 `new` | 构造参数只有 `WorldView`、预算、`SearchProfile`，没有 `Level`、没有身体 |
| 破坏与放置 | `WorldView.breakCost` 算破坏代价，全局 `pathfinderBreakCostMultiplier` 乘（默认 2.5，闸的 baseline 压成 1.0）；`Walker.mayBreak()` = 全局 `allowBreak` 且意图没有 `NoBreak` | 全局开关是一次事故之后留下的总闸，意图只能收紧不能放开。放置侧没有约束类；全局 `allowPlace` 只有 `ClientWorldView.canPlace` 读，`LevelWorldView` 不读 |
| 用户任务链 `UserTaskChain` | `setProcess` 第一件事是取消当前进程 | 任何经它启动的东西都会打断正在走的那一趟 |
| 意图进程 `IntentProcess` | `mc.bot.goto` 的执行体 | 只持有一个目标（`Intent` 是不可变的单目标）；`Goal.Composite` 是「任一满足即可」。重新规划的节奏在 `Walker`；`IntentProcess` 只在实体锚点移动超过阈值时重建搜索条件并强制一次重规划 |
| 目标与条件的解析 `GotoGoalResolver` | `resolveBias`、`resolveCapability`、`resolveConstraints`、`resolveEntityLeash` 只收 `Params`，是纯函数；`resolveGoal`、`checkRequiredTool` 需要 `LocalPlayer` | `mc.bot.follow` 用同一套解析器接受 goto 的全部条件字段。整个 `mc.bot.*` 只在客户端可用，专用服务器上一行都不执行 |
| 参数校验 | `DriverApi.route` 先跑 `SchemaValidator` 再进 handler；未声明的键报 `unexpected key` | 老字段的「搬家提示」放不进 handler，schema 那一层就拒了 |
| 可等待的动词 | `DriverApi.awaitable(p, "goto", …)` | 槽名写死在路由表里，15 处；鞘翅飞行的结果落在 `elytra` 槽。`BotState` 的进程槽是写死的 14 个，没有 `plan`；`awaitable` 把「槽不存在」当成已完成 |
| 场景闸的纪律 | 硬规则 12（不许把客户端类型递给更宽的形参）、13（写 `BotConfig` 的场景要持 pin，baseline 把 `allowBreak`、`allowPlace` 压成 false） | 本设计的新类型和新场景都受这两条约束 |
| 观察 | `mc.observe.threats`、`mc.observe.scene`（有 `overlays` 参数）、`mc.observe.map`（没有） | 服务器侧执行，观察那一刻没有搜索在跑 |
| 事件 | `DriverApi.emit`，现有 `threat.appeared`、`player.hurt` 等 | 没有任何关于路线本身的事件 |

缺的是四样：**怪群**、**视线**、**路线预览与采用**、**路线事件**。

## 2. 条件的表达：`route` 对象

所有路线条件都放在 `mc.bot.goto` 的 `route` 对象里。每一项都有「软」（加价）和「硬」（剪枝）
两种强度，因为「尽量别走」和「绝对不许走」是不同的要求。

### 2.1 快照：所有新组件共用的一份数据

新组件都需要「这次搜索里生物和观察者在哪」。这份数据的来源和归属先定死：

- 给 `Constraint` 与 `CostModifier` 加一个可选接口 `SearchAware`，两个方法：
  `beginSearch(SearchScope scope)`，以及带默认值 0 的 `scanRadius()`（这个组件要看多远的实体）。
- `SearchScope` 里**没有实体、没有身体**。硬规则 12 不许把 `LocalPlayer` 递给声明为 `Player`/`Entity`
  的形参，一个装着身体的作用域对象就是那颗类加载炸弹。它只装求好值的数据：起点、目标、一份
  `ThreatSnapshot`（每个观察者一条记录：实体 id、注册名、眼位 `Vec3`、是否远程、有效距离），以及
  一个视线函数 `los(Vec3, Vec3)`。`WorldView` 接口不动，它继续不认识 `Level` 和实体。
- `PathFinder` 今天不持有 `Level`、身体或任何实体信息，构造参数只有 `WorldView`、预算和
  `SearchProfile`。所以照 `withTuning`、`withOwner` 的样子加一个 `withScopeSource(ScopeSource)`，
  `ScopeSource` 是一个函数 `(start, goal, profile) -> SearchScope`，由 `Search` 构造函数在每次搜索
  开始时调用一次，因为快照必须是每次搜索一份。设置它的地方是 `Walker.newPathFinder`（正式的唯一
  构造点，它手里有 `Level` 和身体）和第 3 节的 `PreviewSearch`；三个调试工具与单元测试不设置，
  拿到的是空作用域：快照为空，视线函数恒真，新组件全部不起作用。
- 交付的时机不是 `world.beginSearch()` 那一行：那一行在 `Search` 构造函数里先于 `tax()` 循环，
  而 `profile.bias()` 里的修饰器要到那个循环才装进 `costModifiers`。所以在 bias 循环结束、
  `costModifiers` 定型之后，单独走一遍 `constraints` 和 `costModifiers`，对每个实现了 `SearchAware`
  的对象调用一次 `beginSearch(scope)`，按对象身份去重：`MobCluster`、`SightExposure` 同时实现
  `Constraint` 与 `CostModifier`，一个对象可能在两个列表里各出现一次，只能通知一次。
- 作用域的构造是一个静态方法 `SearchScope.gather(level, selfId, start, goal, profile)`，`Walker`
  与 `PreviewSearch` 共用它，不各写一份；`selfId` 是身体的实体 id，只用来把自己排除在扫描之外。
  它用 `Level.getEntities` 扫描「起点与目标围成的盒子」向外扩 profile 里各组件 `scanRadius()`
  最大值的范围，按条件里要的种类过滤（敌对生物、远程生物、玩家、指定 id），装成 `ThreatSnapshot`。
  不用 `ThreatScanner`：它以身体为中心、不收玩家，目标附近的骷髅根本不在它的结果里。
- 盒子有上限。`Level.getEntities` 的代价随盒子体积长，而且只看已加载的区段；一次上百格的搜索
  加上半径，盒子轻易超过几万格。每个轴的边长封顶 `snapshotBoxMax`（默认 96 格）：超过时保留
  靠起点的那一段，并在作用域上记 `truncated = true`，预览结果原样报出 `snapshotTruncated`。
  被截掉的那一段里的生物由第 4 节的事件在路上补报，不指望快照。
- 所有实现了 `SearchAware` 的组件读同一份 `ThreatSnapshot`，整次搜索不变；分片跨越的几个游戏刻里
  生物挪动了也不影响一致性。两端通用，专用服务器上的身体从此也避怪。
- 视线函数在游戏里包装 `Level.clip`（`Block.COLLIDER`、`Fluid.NONE`，与 `ThreatScanner` 相同，
  水不挡视线）；单元测试注入假的实现。

### 2.2 区域

```json
"regions": [
  {"shape": "box",    "min": [10, 60, 10], "max": [30, 70, 40], "mode": "forbid"},
  {"shape": "sphere", "center": [0, 64, 0], "radius": 12,      "mode": "avoid", "penalty": 250}
]
```

`forbid` 是硬约束（新增 `ForbidRegion`），`avoid` 就是现有的 `AvoidRegion` 补上长方体形状。

### 2.3 怪群

```json
"mobs": {
  "radius": 6, "rangedRadius": 16, "penalty": 300,   // 单个生物的绕行半径（近战、远程分开）与峰值代价；默认取全局值
  "cluster": {"count": 3, "radius": 6, "mode": "forbid"},   // 半径 6 格内有 3 只以上算怪群
  "types": ["zombie", "minecraft:skeleton"]          // 省略则为所有敌对生物；短名补 minecraft:，匹配完整的注册名
}
```

按生物加价和怪群门槛都由同一个新组件 `MobCluster` 做（同时实现 `Constraint`、`CostModifier` 和
`SearchAware`，读 2.1 节的快照）：每只生物按 `radius`/`rangedRadius` 和 `penalty` 加一笔随距离
衰减的代价（曲线沿用现有的 `ThreatAvoidance.cost`，行为和今天一样），代价相加，三只怪站在一起
自然比一只贵；`cluster` 增加的是质变：达到数量门槛的格子直接不可走（`forbid`），或加一笔固定
代价（`avoid`），这样规划器不会在「绕路太远」的权衡里选择穿过怪群。

**`MobCluster` 取代 `dangerCost` 里按生物加价的那一半。** 今天的按生物加价在 `ClientWorldView.dangerCost`
里，用的是它自己在 `beginSearch()` 里建的怪物快照，只有客户端有；两套并存会算两遍，专用服务器
上仍旧不避怪。所以：`ClientWorldView` 里的怪物快照、`avoidMobs` 分支和 `ThreatAvoidance.cost`
调用一并删掉，`dangerCost` 只剩地形危险那一半，`WorldView` 接口不动。全局设置保留但换了含义：
`avoidMobs` 为真等于「每个意图默认附带 `mobs: {radius: mobAvoidRadius, rangedRadius:
rangedAvoidRadius, penalty: mobAvoidPenalty}`（不带 `cluster`）」，意图自己给了 `mobs` 或 `risk`
就以意图的为准。三个数值设置就是 `mobs` 各字段省略时的默认值。这样两端行为一致，`risk: bold`
能把按生物加价整个关掉，不存在算两遍的边界。`PathSmoothing` 拉直时对生物代价的保护走它已有的
按 `SearchProfile.bias()` 求和的那条路（`MobCluster` 就在 bias 里），不需要再动。

### 2.4 视线

```json
"sight": {
  "of": "ranged",            // ranged | hostile | players | 实体 id 或名字的列表
  "range": 16,               // 观察者的有效距离；省略时按类型取默认值（骷髅 16，掠夺者 8，恶魂 64，玩家 32）
  "mode": "avoid",           // avoid | forbid
  "penalty": 120,            // 每个暴露格的代价
  "eye": 1.62                // 判定用的高度，见下
}
```

实现是新的 `SightExposure`（`Constraint`、`CostModifier`、`SearchAware`）：

- 观察者取自 2.1 节的快照，按 `of` 过滤。
- 一个格子是否暴露：从观察者眼睛到「该格 + eye」做一次视线检测。与原版生物「能否看见目标」的
  判定同形（眼到眼一条射线）；`ThreatScanner` 算 `canSeeMe` 用的是自己写的 `Level.clip`
  （`Block.COLLIDER`、`Fluid.NONE`），这里用同一个调用，所以水不挡视线，默认取站立眼高 1.62。
  `eye` 只是判定假设，不是行为：机器人没有「蹲着走」的移动方式，填 1.27 得到的路线是按蹲姿
  评估的，实际走过去并不蹲。
- 这是本设计里唯一昂贵的计算：结果按（格，观察者）缓存在本次搜索里；只对观察者 `range` 内的格子
  检测，范围外算不暴露；每次搜索的射线数有上限 `sightRaysPerSearch`（默认 4000）。上限用完**不能**
  写成「剩下的格子一律按暴露计价」：那样一个格子的代价取决于它是第几个被展开的，A* 不重开已关闭的
  节点，同一场景两次跑会给出不同路线，第 6 节的断言就没法写；`Constraint` 的契约本来就要求它是
  入参的纯函数。所以预算用完时中止本次搜索，去掉 `sight` 条件从头重跑一遍（其余条件不变，重跑
  拿一份新的节点与时间预算），并在结果上记 `sightBudgetExhausted: true`：预览结果原样报出，
  行走时进 `Walker.lastStats` 并记一条日志。代价是最坏情况搜两遍，换来结果只由（格，快照）决定。
- 只算几何视线，不含光照、潜行、隐身、各生物不同的仇恨范围。

### 2.5 走廊与航点

```json
"via":      [[12, 64, 3], [20, 64, 9]],
"corridor": {"points": [[0,64,0],[12,64,3],[20,64,9],[30,65,30]], "radius": 3, "mode": "forbid"}
```

`via` 是必须依次到达的中途点。今天 `IntentProcess` 只持有一个目标，所以意图对象要加一个目标
列表，`IntentProcess` 到达一个就换下一个；这是本设计里改执行层的两处之一（另一处是 3.3 节的
采用）。人工验证设计里的
`via` 标记就是通过这个字段传给机器人的，不在场景侧展开。

`corridor` 是新的 `Corridor` 约束：格子到折线的距离超过 `radius` 就剪掉（或加价）。LLM 可以
自己画一条折线，规划器只在折线附近找可走的格子，把跨沟壑、上台阶这些逐格的事留给 A*。

### 2.6 四个新硬约束的归位规则

`ForbidRegion`、`Corridor`、`MobCluster(forbid)`、`SightExposure(forbid)` 和现有的
`LeashHardRadius` 一样，都可能在搜索开始时就被违反：被打退进禁区、被怪群围住、被撞出走廊。
四个都沿用现有的归位规则：`to` 违反约束时，只放行让违反度比 `from` 小的边，直到回到合规区域
再恢复剪枝。规则是逐边判定、不记状态的，它不知道搜索起点在哪。没有这条规则，起点（它本身
无条件进开表）的所有出边都会被剪掉，没有后继，机器人原地卡死。

现有两条规则比较的都是一个连续标量（`LeashHardRadius` 比到锚点的距离，`ColumnRadius` 比到列
中心线的水平距离），`from`、`to` 两格各算一次再比大小，严格小于才放行。四个新约束各自的
「违反度」要定义清楚，否则「周围怪更少」这类说法落不到代码上：

| 约束 | 违反度（对一格算） | 放行条件 |
|---|---|---|
| `ForbidRegion` | 格子在区域内时，到区域最近边界面的深度；区域外为 0 | `to` 在区域外，或 `depth(to) < depth(from)` |
| `Corridor` | 格子到走廊折线的距离减去 `radius`，不足 0 记 0 | `to` 在走廊内，或 `dist(to) < dist(from)` |
| `MobCluster(forbid)` | 计数：以该格为中心、`cluster.radius` 内的快照生物数。势：到最近一只快照生物的距离 | `count(to) < cluster.count`；否则要求 `count(to) <= count(from)` **且** `nearest(to) > nearest(from)` |
| `SightExposure(forbid)` | 计数：能看到该格的观察者数。势：到最近一个能看到该格的观察者的距离 | `seen(to) == 0`；否则要求 `seen(to) <= seen(from)` **且** `nearest(to) > nearest(from)` |

前两行是连续量，照抄现有规则的严格递减即可。后两行的违反度是整数：从四只怪中间出发，四邻的
计数往往都还是四，严格递减无解，就又回到「没有合法出边」的死锁；一格挪动更是通常改变不了被几个
观察者看见。所以计数型约束的归位分两半：计数不许增加，同时一个连续的势函数（到最近一只怪、到
最近一个观察者的距离）必须严格增大。势函数保证每一步都有进展，计数保证不往更糟的地方走。

`SightExposure` 在禁止模式下每个候选格要对每个观察者射一条线，走 2.4 节的射线预算；预算用完
按 2.4 节的规定整次搜索去掉 `sight` 重跑，不存在「一半格子检查过、一半没有」的中间状态。

现有两条规则的 javadoc 都写明：在凹陷的死角里，「只许靠近」仍可能把搜索卡死，这是接受的残留。
四个新约束继承这条附带说明；卡死的表现是搜索失败，由第 4 节的 `route.blocked` 报出约束名。

### 2.7 一次填完的 `route` 对象

```json
"route": {
  "via":     [[12, 64, 3], [20, 64, 9]],       // 路径点，依次到达；省略即直达
  "mode":    ["walk", "swim"],                 // 交通方式：walk | swim | dive | fly，可多选；省略即 walk+swim；除 fly 外 walk 总是隐含
  "break":   "never",                          // 是否破坏方块：never | allow | prefer；省略即 allow，全局 allowBreak 始终是总闸
  "place":   "allow",                          // 是否放置方块：never | allow；省略即 allow，全局 allowPlace 始终是总闸
  "parkour": false,                            // 是否允许跳跃跑酷；省略为允许
  "risk":    "safe",                           // 风险预设：safe | normal | bold；省略为 normal
  "plan":    true                              // false | true（预览）| "score"（给折线打分），见第 3 节
}
```

| 字段取值 | 落到哪里 |
|---|---|
| `via` | 意图对象的目标列表（2.5 节） |
| `mode` 不含 `swim` 也不含 `dive` | `NoWater` 硬约束（今天的 `forbidWater`） |
| `mode` 含 `dive` | 视为也含 `swim`；`CapabilityProfile` 打开 `DIVE`（今天的 `dive: true`，不写就永远不会规划潜水） |
| `mode` 只有 `walk` | `NoWater`，不开 `DIVE`。**不碰 `parkour`。** 注意这和被删掉的 `capability: "walk"` 不同，后者的含义是禁跑酷，对应新的 `parkour: false` |
| `mode: ["fly"]` | 第一版只允许单独出现且不带 `via`：整个意图交给 `elytraFly`（它有自己的三维规划器，不走 A*），要求背上有鞘翅；返回体里 `slot` 为 `elytra`，见 3.1 节。和步行混用、飞一段走一段，等有场景需要再做 |
| `break: never` | `NoBreak` 硬约束（今天的 `forbidDig`） |
| `break: allow`（省略时的默认） | 本次意图不加 `NoBreak`；能不能真的挖仍由全局 `allowBreak` 决定。**意图只能收紧不能放开**，这条不改：`Walker.mayBreak()` 的 javadoc 记着那次事故（被拴住的 `forbidDig` 机器人照样挖回地下，现场只能关全局开关），全局开关是总闸，一个意图字段绕过它就没有总闸了 |
| `break: prefer` | 同 `allow`，再加一个新的 `CostModifier` `PreferBreak`：给**不**破坏方块的边加一笔固定代价（默认 10，等于多走一格），挖穿相对变便宜。不能反过来给破坏边打折：破坏代价由 `WorldView.breakCost` 算、全局 `pathfinderBreakCostMultiplier` 乘，`SearchProfile` 够不到那一层；而且乘数小于 1 会让欧氏启发式高估，A* 不再最优。只加不减，可采纳性不变 |
| `place: never` | 新的 `NoPlace` 硬约束，剪掉 `edge.toPlace` 非空的边，与 `NoBreak` 同形。今天放置侧没有约束类；全局 `allowPlace` 只有客户端的 `ClientWorldView.canPlace` 读，服务器的 `LevelWorldView` 不读，这个差异不在本设计里修 |
| `place: allow`（省略时的默认） | 不加 `NoPlace`；全局 `allowPlace` 是总闸，同 `break` |
| `parkour: false` | 今天的 `forbidParkour` |
| `risk: safe` | 打开按生物加价（半径与峰值取全局的三个设置），`mobs.cluster` 取 `{count: 3, radius: 6, mode: forbid}`，`sight` 取 `{of: ranged, mode: avoid}` |
| `risk: normal` | 今天的默认：地形危险的代价在；按生物加价看全局 `avoidMobs`（为真时等于默认附带一份不带 `cluster` 的 `mobs`，见 2.3 节） |
| `risk: bold` | 不带 `mobs`、不带 `sight`，无视全局 `avoidMobs`；只留地形危险的代价 |

`mode` 里没有 `dig`：「愿不愿意挖」是 `break` 一个字段的事，同一件事不给两种拼法。

前七项是 LLM 日常会填的，下面是详细控制；详细控制的显式取值优先于 `risk` 预设展开出来的值：

```json
"route": {
  "yRange":  {"min": 60, "max": 70, "hard": false, "weight": 10},   // min、max 可只给一个
  "hug":     {"what": "shore", "weight": 30},
  "leash":   {"center": [x, y, z], "entity": "Steve", "radius": 24, "hard": false, "weight": 20, "axis": "xz"},
  "regions": [ … ],  "mobs": { … },  "sight": { … },  "corridor": { … },
  "requireTool": "minecraft:iron_pickaxe"
}
```

- `weight` 三处都保留：`hugShore` 的工具描述明写权重要远大于每格行走代价 10 才钉得住岸边，
  收敛不能把这个旋钮收没。
- `leash.entity` 收字符串（玩家名或实体类型，今天就是这样）或数字（实体 id）。
- `leash.axis: "xz"` 是圆柱：距离只算水平，`center` 可以只给 `[x, z]`，给了 y 也忽略；归位判据
  同样只看水平距离。这就是今天 `ColumnRadius` 存在的原因（球形的 dy² 项会把爬升算成远离），
  合进一个字段时两个语义一起切换。`axis` 对软硬两种都有效：硬的圆柱就是 `ColumnRadius`，软的
  圆柱今天没有（`LeashAnchor` 只有球形），是一个新的小修饰器。
- `requireTool` 从顶层搬进 `route`：它是「开始前检查背包里有没有这件工具」，属于路线条件。检查
  本身仍在 `GotoGoalResolver.checkRequiredTool`（要 `LocalPlayer`），第 6 节的纯解析器只把它读出来。

工具描述里前七项各配一句话，详细控制只列名字和一句「见 route 详细字段」，schema 的体积由此控制。
每个工具的 schema 都随每次提示词发给每个 LLM 客户端，一个对象加几个枚举比十几个布尔便宜，也
贴合 LLM 的思路：我想怎么走、我愿不愿意改地形、我有多怕。

### 2.8 老字段全部收进 `route`，硬切

`mc.bot.goto` 顶层只剩**目标选择**：`pos`、`near`、`xz`、`y`、`block`、`radius`（`block` 选择器的
扫描半径）、`entity`、`entityId`、`direction`、`distance`（`direction` 的步长）、`waypoint`、`axis`、
`invert`、`strict`、`goalMode`（都不动），通用的 `awaitMs`，以及 `route`。`radius` 和 `distance` 是
目标选择不是路线条件，漏掉它们就打死了两个选择器；`route` 里也有几个叫 `radius` 的（`leash`、
`mobs`、`corridor`），同名不同义，工具描述里分开写。其余路线相关的顶层字段一律删除，不留兼容层。

老字段名出现时的报错来自 schema 校验那一层：`DriverApi.route` 先跑 `SchemaValidator`，未声明的
键报 `unexpected key 'forbidDig'`，根本到不了 handler。所以「搬到了 `route` 的哪一格」不在错误
信息里，而在工具描述开头的一句话和 `worlddriver-rpc` 技能的方法表里。

`mc.bot.follow` 用同一套解析器接受 goto 的全部条件字段（`BotApiImpl.follow` 调 `resolveBias`、
`resolveCapability`、`resolveConstraints`），所以它一起切：`follow` 的条件也只认 `route`，老字段
同样从它的 schema 里删掉。只切 goto 会把 `follow` 变成留下来的兼容层。

| 删除的老字段 | 收进 `route` 的位置 |
|---|---|
| `forbidDig` | `break: never` |
| `forbidWater` | `mode` 不含 `swim`、`dive` |
| `dive` | `mode` 含 `dive` |
| `forbidParkour`、`capability: "walk"` | `parkour: false`（`capability` 整个删掉，它只有 `walk` 一个取值，含义就是禁跑酷） |
| `avoid: [{x,y,z,radius,penalty}]` | `regions: [{shape:"sphere", center, radius, mode:"avoid", penalty}]` |
| `preferY: {min,max,weight}` | `yRange: {min, max, weight}` |
| `yFloor`、`yCeil` | `yRange: {min, max, hard: true}`，单边可用（顶层的 `y` 仍是目标选择器「到某个高度」，两者不是一回事，所以这里不叫 `y`） |
| `hugShore: true \| {weight}` | `hug: {what: "shore", weight}` |
| `leash: {x,y,z,radius,weight}` 与 `leash: {entity}` | `leash: {center \| entity, radius, weight, hard: false}` |
| `leashHard: {…}` | 同上，`hard: true` |
| `column: {x,z,radius}` | `leash: {center: [x, z], radius, hard: true, axis: "xz"}` |
| `requireTool` | `route.requireTool` |

**调用方在同一次提交里改完。** 先分清两类。testmod 里的场景和真梯（`JourneyShaft`、
`WorldDriverBiasScenes` 等十几个文件）用的是 Java 类，`new SearchProfile(…, List.of(new NoBreak()))`，
不经过 JSON 字段名，改字段名对它们零影响，闸不会因此变红。真正按字段名读老字段的，仓库内只有
三处消费者：`validation/65_schema_union.js`（十几处断言）、`WorldDriverCoreScenes` 里对
`mc.bot.follow` 的 `hugShore` 断言、`scripts/.claude/skills/worlddriver-rpc/references/methods.md`；
再加三处定义：`GotoGoalResolver`、`BotTools` 的 goto 与 follow 两个 schema、`BotApiImpl.follow`。
这六个文件和 `route` 的解析在同一次提交里改完。仓库外的 Journeyman 今天不使用这些字段，不用动。

## 3. 先看再走：预览、打分、采用

### 3.1 `plan: true`：预览

**预览不经过用户任务链。** `UserTaskChain.setProcess` 第一件事是取消当前进程，经它启动的预览会
把正在走的那一趟掐掉，第 4 节的闭环（正在走、收到事件、先预览一下）第一步就断了。所以预览是
一个独立的 `PreviewSearch`，由 `BotApiImpl.clientTick`（`END_CLIENT_TICK`）推进；有自己的分片
预算 `pathfinderPreviewSliceMs`（默认 3 毫秒），不碰 `Walker`，不碰用户任务链，同一时刻只有一个
预览在跑，后来的排队。**第一版只做客户端**：动词 `mc.bot.goto` 就在这一侧，服务器端身体今天没有
任何调用预览的入口。等服务器端的驱动能力补齐后再做那一侧，那时复用同一个 `PreviewSearch` 和
`SearchScope.gather`，推进者是 `ServerAvatarManager.tickAll`。

**预览是异步的。** 客户端和服务器端的搜索都分片跑在游戏线程上（每刻 6 毫秒，累计上限
`pathfinderMaxMs`），一次大搜索跨很多个游戏刻；离线程读世界是硬规则禁止的。所以 `plan: true`
立即返回 `{started: true, slot: "plan"}`，结果通过 `awaitMs` 等待或从状态里读。`BotState` 的
进程槽是写死的 14 个，没有 `plan`，而 `awaitable` 把「槽不存在」当成已完成，所以 `BotState` 加一个
`plan` 槽，`PreviewSearch` 负责写它的 `active`、`lastError` 和结果，并进 `snapshot()`。为此
`DriverApi.awaitable` 加一条规则：实现的返回体里带 `slot` 就按它等，不带就按路由表传入的字面量等。
路由表里 `goto` 之外另外 14 处 `awaitable` 调用的字面量槽名（`mine`、`bunker`、`craft`、`smelt`、
`combat`、`builder`、`follow`、`explore`、`runAway`、`goto`、`elytra`）原样保留，今天没有任何实现
返回 `slot`，所以它们的行为不变；只有 `goto` 在 `plan: true` 时返回 `slot: "plan"`、`mode: ["fly"]` 时返回 `slot: "elytra"`。
不能写成「只认返回体里的 `slot`」：那样其余动词的 `awaitMs` 会读不到槽、落进「槽消失即完成」的
分支，`awaitMs` 静默失效而不报错。第 6 节的单元测试要盯住这一点。

返回体：

```json
{
  "ok": true, "reached": true, "planId": "p-1207", "cells": 87, "cost": 1240,
  "segments": [
    {"from": 0, "to": 31, "risk": {"exposedTo": [], "nearestMob": 14.2, "regions": []}},
    {"from": 31, "to": 52, "risk": {"exposedTo": [{"id": 1203, "type": "minecraft:skeleton", "cells": 9}], "nearestMob": 7.0, "regions": []}},
    {"from": 52, "to": 87, "risk": {"exposedTo": [], "nearestMob": 21.5, "regions": ["avoid#1"]}}
  ],
  "detourRatio": 1.6,              // 路线长度 / 直线距离
  "bestEffort": false,             // true 表示没有到达目标，返回的是最接近目标的部分路线
  "sightBudgetExhausted": false,   // 见 2.4 节：为真表示这次搜索丢掉了 sight 条件重跑
  "snapshotTruncated": false       // 见 2.1 节：为真表示快照的盒子被封顶截过
}
```

分段的依据是风险明细发生变化的地方，所以 LLM 读到的是「前 31 格安全，接下来 21 格暴露在骷髅
1203 的视线里 9 格」，而不是 87 个坐标。逐格坐标默认不返回，`includePath: true` 才给。预览
展示的是拉直之后的路线（`includePath` 给的坐标、分段的格数都按它算）；缓存里另存 A* 的原始
结果，供 3.3 节采用，原因见那里。

带 `via` 的预览按段依次搜索（每一段从上一段的目标出发），在同一个 `PreviewSearch` 里排队完成，
返回体的每个 `segment` 多一个 `leg` 序号；`planId` 缓存的是各段的原始结果。

一次预览吃掉一整份搜索预算（`pathfinderMaxNodes`、`pathfinderMaxMs`），带 `via` 时每段一份，
视线预算耗尽重跑时再加一份，和正在走的那一趟共用游戏线程的时间；正文鼓励「收紧条件再预览」，
但每一轮都是这个价，工具描述里要写明，LLM 该在两三轮内收敛。

### 3.2 `plan: "score"`：给 LLM 自己画的折线打分

不新增动词（每个工具的 schema 都随提示词发出去，硬规则要求优先扩展现有工具）。`plan: "score"`
时不做搜索，把 `corridor.points` 依次连成的折线按格离散化后当作路线本身（不检查可走性），用
同样的条件算每一段的风险明细，返回和预览相同的结构，不带 `planId`；没给 `corridor` 时报错。
用途是 LLM 在预览之外还能问「如果我硬要走这条线呢」。

### 3.3 `planId`：就走预览过的这一条

执行时带上 `planId`，机器人不重新搜索，把缓存里预览的 **A\* 原始结果**交给 `Walker.adoptPath`。
喂原始结果而不是展示用的拉直路线，是因为 `adoptPath` 自己会再做一遍拉直、按 `pathBestEffort`
截掉最多 8 个尾节点、再快进掉已走过的前缀；给它一条拉直过的路线等于拉直两次。`adoptPath` 是
`bot.movement` 包内私有的，发起采用的 `IntentProcess` 在 `bot.process` 包里够不着，而 `Walker`
本身贴着行数预算，不往里加方法：按 `WalkerTick*` 拆分的惯例，在 `bot.movement` 包里加一个小的
公开辅助类 `WalkerPlanAdoption`，由它调用包内的 `adoptPath`，把当前脚下位置一并传入。

`adoptPath` 已经做了两件事，不需要再接：按最近的前缀节点锚定，吸收身体在预览期间的漂移；
调用 `PathSmoothing.dropStalePrefix`。后者的名字骗人，它剪的是**后段**：从头扫最多 12 条边，遇到
第一条当前世界已不接受的边就把路线截断到那里，首边失效时返回空路线。所以预览路线的第一段已
不可行时，`adoptPath` 得到空路线、返回 false，正好落到下面的「退回普通搜索」。

退回普通搜索的条件：`adoptPath` 拒绝（它自己的闸是干地 4 格、水里 8 格，本设计不再另设
「离起点 2 格」的旋钮，两个管同一件事的旋钮迟早打架）、预览超过 60 秒、或预览的 `bestEffort`
为真。退回时返回体里 `adopted: false` 并说明原因。预览结果缓存 60 秒，最多保留最近 8 条。
带 `via` 的预览只采用第一段，后面各段到达时照常搜索：采用保证的本来就只是「起步时走预览的
那条」。
这一条是「LLM 精确规划」最直接的形式，也是预览与执行一致性的唯一机制保证：两次独立搜索之间
至少有身体位置、卡住惩罚的衰减、拉直这三个变量，不靠采用是保证不了走同一条的。「同一条」的
含义见第 6 节：采用之后行走器没有发起新的搜索。

### 3.4 地图叠加层

`overlays` 参数在 `mc.observe.scene` 上（不在 `mc.observe.map`），今天有 `height`。加两个：
`sight`（每个格子被几个观察者看到）和 `mobDensity`（每个格子 `cluster.radius` 内的敌对生物数）。
观察那一刻没有搜索在跑，所以叠加层是用同一段代码（`ThreatSnapshot` 的扫描、`SightExposure`
的判定）在观察时现算一份，不是读规划器的快照；同一段代码保证 LLM 在地图上看到的危险和规划器
避开的危险是同一种判定，不是同一份数据。

## 4. 世界变了怎么办：路线事件

`Walker` 已经按节奏重新规划，`IntentProcess` 只在实体锚点移动超过阈值时额外强制一次。新增
三种事件，通过现有的 `DriverApi.emit` 推给 LLM：

| 事件 | 触发条件 | 附带数据与来源 |
|---|---|---|
| `route.exposed` | 重新规划后的路线（或当前位置）进入了某个观察者的视线，而上一次规划时没有 | 观察者 id 与类型、暴露的格数、当前段序号；来自本次搜索的 `SightExposure` 缓存 |
| `route.blocked` | 规划器只能给出部分路线（`bestEffort`） | `reason`：`constraint:<名字>` 或 `budget`。硬约束今天是没有名字的函数式接口；给 `Constraint` 加 `default String name()`，返回类的简单名（`PathFinder` 给 `CostModifier` 记账用的就是 `getSimpleName()`），不改 `SearchProfile` 的形状，它的 17 个构造点一个不动。搜索里按名字数剪掉的节点，只在**本次意图声明的**硬约束里挑报数最多的那个：把目标围死时剪得最多的常常是 `NoWater` 或能力过滤，它们不是 LLM 定的条件，报出来没有用。节点或时间预算用完则报 `budget`，和条件无关 |
| `route.detour` | 新路线长度超过直线距离的 `detourAlarmRatio`（默认 3）倍 | 比值、贡献代价最多的那一项。「沿最终路线把每个代价项各算一遍、取总和最大的」就是 `explainTaxes` 已经实现的算法，它在 `walkerDebug` 或 `-Dworlddriver.pathfinderTaxLog=true` 下打印（javadoc 只写了前者，过时了）。把它的内核抽成一个返回 `Map<String, Double>` 的方法，打印和事件都用它；顺手把那句 javadoc 改对 |

三种事件都只在对应条件在 `route` 里时才会产生：没给 `sight`（也没用 `risk: safe` 展开出它）就
没有 `SightExposure` 组件，也就没有 `route.exposed`；LLM 没要求避开的东西不替它盯着，射线也
不白射。`route.exposed` 需要记住上一次规划的暴露情况，这份摘要放在 `IntentProcess` 上，随意图
生灭。事件有去抖：同一观察者、同一原因在 `routeEventCooldownTicks`（默认 100）内只报一次。
LLM 收到后可以放宽条件、换目标、预览一条新路（预览不打断行走，见 3.1）、或者调用
`mc.bot.combat`；不收也没关系，机器人按当前条件继续走，反射层照常保护它。

不在每次重新规划时都问 LLM：每次搜索的快照本身就是对世界变化的跟踪，规划器自己会绕开挪动了
的怪；LLM 只在「按现有条件已经解不好」的时候才被打扰。

## 5. 与反射层的分工

| 情况 | 谁处理 | 为什么 |
|---|---|---|
| 苦力怕开始膨胀、箭已经射出、被围到角落 | 反射层（`Dodge`、`Panic`、`Retreat`），不变 | 一个游戏刻内必须出答案 |
| 路线要穿过一群僵尸 | 规划器（`MobCluster`） | 从本次搜索的快照能算出答案 |
| 路线会暴露在骷髅的射程和视线内 | 规划器（`SightExposure`） | 同上 |
| 该绕远路还是硬闯、该不该先清掉骷髅 | LLM | 需要权衡目标和世界知识 |
| PvP 中躲开另一名玩家的视线 | 规划器（`sight.of` 给玩家名或实体 id，快照收玩家）加 LLM 定条件 | 几何是本地的，意图是 LLM 的 |

## 6. 测试

**先分清两层，因为它们跑在不同的拓扑上。** `route` 的解析除目标选择外本来就是纯函数
（`resolveBias`、`resolveCapability`、`resolveConstraints` 只收 `Params`），新的解析器保持这一
性质：`RouteParams.parse(Map) -> (SearchProfile, via 目标列表, 意图开关, requireTool)`，不碰
`LocalPlayer`；最终目标仍由 `resolveGoal` 解析后追加到列表末尾，`requireTool` 的检查仍在
`checkRequiredTool`，两者留在客户端的 goto 处理器里。但动词
这一层（`mc.bot.goto` 的 `route`、`plan`、`planId`、`awaitable`、事件）整个只在客户端可用，两条
`stagewrightDedicatedServer*` 闸上一行都不执行。所以：

- **约束与解析层**在专用服务器上用无头身体跑（`wd.route*`）。场景把一份 `route` 的 JSON 交给
  `RouteParams.parse`，得到的 `SearchProfile` 经 `SceneBody.avatar` 拿到的 `ServerPlayerAvatar`
  交给身体的行走器（`WorldDriverBiasScenes` 就是这么把 `SearchProfile` 交给无头身体的），断言读
  行走器的路线与 `Walker.lastStats`。这样解析器本身也在专用服务器上被覆盖，
  只有动词层不在。这也是 2.1 节坚持快照两端通用的原因。
- **动词层**在带真客户端的拓扑上跑（`wd.clientRoute*`，做法同 `wd.clientTunnels*`）：真的调
  `mc.bot.goto`，断言返回体、槽和事件。

两层都是 Java 场景：地形用 `ctx.setBlock` 搭，断言用坐标直接写，不依赖人工验证设计的标记方块
（那套东西是给人手搭的 `human.*` 场景用的，而且尚未实现）。场景和现有场景一样把分片设成无限，
一次搜索在一刻内完成。每个场景开头都持 pin（`var pin = BotConfig.pinnedBaseline();
ctx.cleanup(pin::close);`，硬规则 13），并记住 baseline 把 `allowBreak`、`allowPlace` 压成 false、
`pathfinderBreakCostMultiplier` 压成 1.0：要挖的场景得自己打开。

约束与解析层（专用服务器，无头身体）：

- `wd.routeAvoidsMobCluster`：目标在一堵墙后面，墙上两个缺口，一个缺口里关着四只僵尸。断言
  路线经过空缺口的那一格；路线上没有任何一格在 `cluster.radius` 内有 `cluster.count` 只以上的
  怪（这是怪群的定义；离最近的怪多远是另一个量，不拿它当判据）。
- `wd.routeStaysOutOfSkeletonSight`：开阔地一侧有骷髅站在高塔上，另一侧有一道矮墙提供掩护。
  断言路线沿墙走，行走全程 `ThreatScanner` 报告 `canSeeMe` 为真的游戏刻数不超过阈值，且
  `Walker.lastStats` 新增的 `sightBudgetExhausted` 为假；再把 `sight.mode` 改成 `forbid`，断言
  搜索到达目标（不是 `bestEffort`）且 `sightBudgetExhausted` 仍为假（预算耗尽会让整次搜索丢掉
  `sight` 重跑，不核这一项断言可以因失败模式而变绿）。
- `wd.routeBlockedNamesTheConstraint`：用 `regions` 把目标完全围住，断言搜索结果 `bestEffort`
  为真、归因的约束名以 `constraint:` 开头且属于本次意图声明的硬约束集合（这里就是
  `ForbidRegion`）。
- `wd.routeCorridorHolds`：走廊约束下，断言路线上每个格子到折线的距离不超过 `radius`。
- `wd.routeRejoinsFromInsideACluster`：起点四周各一格放一个单格栅栏圈，每圈关一只僵尸，起点
  本身是怪群格；断言搜索给出路线（归位规则起作用，不是死锁），且路线离开怪群后不再进入任何
  怪群格。

动词层（带真客户端的拓扑，`wd.clientRoute*`）：

- `wd.clientRoutePreviewAdopted`：同一组条件先 `plan: true` 再带 `planId` 执行，断言返回
  `adopted: true`，且采用之后行走器没有发起新的搜索（`Walker.lastStats` 在到达前不再变化，日志里
  没有新的 `search-begin`）。「逐格相同」不是可断言的性质：`adoptPath` 会再拉直、截尾、快进。
  再让身体先走开 6 格（超过 `adoptPath` 干地 4 格的闸），断言 `adopted: false` 并给出原因。
- `wd.clientRoutePreviewDoesNotInterrupt`：先起一趟长途 goto，途中 `plan: true` 预览另一条路，
  断言行走的进程没有被取消、预览结果照常返回、`mc.bot.status` 里 `plan` 槽从 `active` 变为
  不 `active`。
- `wd.clientRouteBlockedEventFires`：同 `wd.routeBlockedNamesTheConstraint` 的布景，走动词，
  断言 `route.blocked` 事件到达、`reason` 是 `constraint:ForbidRegion`。
- `wd.clientRouteOldFieldRejected`：带 `forbidDig: true` 调 goto，断言被 schema 拒绝、错误里含
  `unexpected key`；带 `route: {break: "never"}` 则成功。

纯逻辑单元测试（`:common:test`）：`RouteParams.parse` 的每一格（含 `radius`、`distance` 仍在
顶层）；`MobCluster` 的门槛计数、`Corridor` 的点到折线距离、预览的分段算法、`SightExposure` 的
缓存与射线上限（视线函数注入假实现，不需要 `Level`）；四个新约束的归位规则，特别是从计数为 4
的格子出发、四邻计数都是 4 时仍有合法出边；`awaitable` 的槽名规则（返回体不带 `slot` 时仍按
路由表字面量等，`mine` 等现有动词的 `awaitMs` 不会静默失效）；新设置在 `SettingsConsumerTest`
与 `GameTestBaselineManifestTest` 两道既有闸下登记齐全。

性能验收：`wd.clientTunnels*` 这类现有场景在 `route` 为空时每节点耗时不变（所有新组件在条件
为空时不注册；`avoidMobs` 默认关，所以默认也没有 `MobCluster`，而 `dangerCost` 少了一半工作）；开启 `sight` 时用 jstack 采样 Render 线程，确认每次搜索的射线数受
`sightRaysPerSearch` 约束；预览与行走共用每刻 20 毫秒的跨搜索总预算（`TICK_BUDGET_NANOS`），
所以再量一个分布级的数：预览进行中，行走搜索 `search-end` 行的耗时中位数与 95 分位不比无预览
时劣化超过 20%。

## 7. 实现步骤

1. `feat(pathfinder)`：`SearchScope`（含静态 `gather` 与盒子上限）、`ThreatSnapshot`、`SearchAware`
   接口，`PathFinder.withScopeSource` 与 `Search` 构造函数里 bias 循环之后的一次去重通知，
   `Walker.newPathFinder` 设置作用域来源；`Constraint.name()` 默认方法；`ForbidRegion`、`Corridor`、
   `MobCluster`、`SightExposure`、`NoPlace`、`PreferBreak` 六个类及 2.6 节的归位规则；视线预算
   耗尽的重跑；`explainTaxes` 内核抽成方法并改对它的 javadoc；`RouteParams.parse` 纯解析器，
   `GotoGoalResolver` 与 `BotApiImpl.follow` 改为调它，goto 与 follow 的 schema 删老字段；
   `ClientWorldView.dangerCost` 删掉怪物快照与 `avoidMobs` 分支，`avoidMobs` 改为默认 `mobs`
   条件（连带 `validation/32_break_place.js` 的断言和方法表里的说明）。**同一提交**改完 2.8 节
   列出的六个文件。新设置（`sightRaysPerSearch`、`snapshotBoxMax`、`pathfinderPreviewSliceMs`、
   `detourAlarmRatio`、`routeEventCooldownTicks`）在 `BotConfig`、`SettingsRegistry`、
   `SettingsNumericWrites`、`SettingsDocs` 四处登记，否则 `:common:test` 的两道设置闸变红。
2. `feat(bot)`：意图对象的目标列表与 `IntentProcess` 的依次到达；`awaitable` 的「返回体带 `slot`
   优先、否则用路由表字面量」规则，附一条其余动词槽名不变的单元测试；`BotState` 的 `plan` 槽。
3. `feat(bot)`：`PreviewSearch`（复用 `SearchScope.gather`，客户端每刻推进，第一版只在客户端）、
   预览返回体与分段风险明细、`plan: "score"`、`planId` 缓存，`WalkerPlanAdoption` 与采用。
4. `feat(bot)`：`route.exposed`、`route.blocked`、`route.detour` 三个事件与去抖。
5. `feat(bot)`：`mc.observe.scene` 的 `sight`、`mobDensity` 叠加层。
6. `test(scenes)`：第 6 节的两层场景与单元测试。
7. `docs`：MCP 工具描述里的字段说明（含「老字段已并入 `route`」那一句）；`worlddriver-rpc` 技能的
   方法表；DOCMAP 登记。

第 1、2 步之后就可用（LLM 已经能通过条件影响路线），第 3 步之后 LLM 才能先看再走、就走这一条，
第 4 步之后才是闭环。各步可以独立验收。

## 8. 已知边界

- 视线只是几何射线，不含光照、潜行、隐身、生物的仇恨范围差异；要更真就得复刻每种生物的感知代码，
  不值得。射线用 `Fluid.NONE`，水不挡视线，和 `ThreatScanner` 一致；水下躲视线的路线会被评估得
  比实际暴露。
- 快照是每次搜索一份，搜索过程中生物挪动了本次搜索不知道；重新规划的节奏决定了跟踪的延迟。
- 快照只扫起点与目标围成的盒子加半径，且每个轴封顶 `snapshotBoxMax`；一条为了绕开什么而拐出
  盒子很远的路线，拐出去那一段的生物不在快照里，封顶截掉的那一段也不在。未加载区块里的生物
  看不见，未加载区块的方块也挡不住射线；远处的预览是乐观的，靠第 4 节的事件在路上补报。
- `place: never` 在服务器端身体上剪的是规划边；`LevelWorldView.canPlace` 不读全局 `allowPlace`
  这个既有差异不在本设计里修。
- 预览、`planId`、`plan: "score"` 第一版只在客户端；专用服务器上的身体只有约束与解析层。
- `planId` 采用只保证「起步时走预览的那条」，路上重新规划照常发生。

# 两份世界视图、一套判据 — `CellRules`

寻路器通过 `WorldView` 问世界三类问题：这格身体能不能过（`isPassable`）、这格能不能当地板
（`canStandOn`）、挖掉这格要多少代价（`breakCost`）。仓库里有三份读真实关卡的实现：

| 视图 | 谁在用 | 身体 |
|---|---|---|
| `bot/ClientWorldView` | `BotApiImpl`（出货客户端）、`PlanProbeTool` | 真玩家 `LocalPlayer` |
| `bot/world/LevelWorldView` | `ServerWorldDriver`、`wd.*` 场景、`journeyServer` | FakePlayer / ServerPlayer |
| `bot/world/ServerWorldView` | `mc.observe.scene`、`WalkerGeometry` 的只读探针 | 无 |

2026-09-04 之前它们各写各的方法体。`wd.clientWorldViewParity` 在同一世界上并排问两份视图，
改前 50 种地形 1650 次提问里有 67 行不一致，分三族：

- **破坏定价**：客户端把「不需要工具的方块」（泥土、原木、树叶、沙砾、栅栏、箱子）按错工具定价，
  比原版进度贵 10 倍；服务端只按手里正拿着的那件定价，而两边执行器挖之前都会从全部 36 格换最优工具。
- **立足**：客户端要求碰撞形状顶面填满整格，拒绝半砖、灵魂沙、泥、耕地、土径、蜜块；
  服务端只问 `blocksMotion`，把栅栏、墙、楼梯也算地板。
- **通行**：压力板、关着的活板门客户端按「薄地面装饰」放行，服务端当墙。

后果是场景真的跑了，只是跑在另一套判据上：`wd.*` 验过的路不一定是客户端会规划的路。

## 现在的规则（`bot/world/CellRules`）

三份视图全部委托这一个类，规则只写一遍：

- **`isPassable`**：空气、水；开着 `collisionAwarePathing` 时再加两种——碰撞形状避开 0.6 宽身体柱
  （可可豆、单轴玻璃板、墙的凸起），或贴地且不高于 `pathfinderThinObstacleHeight` 的薄形状
  （压力板、地毯、睡莲、关着的底活板门）。
- **`canStandOn`**：`blocksMotion` 且碰撞形状非空、顶面不超过本格（栅栏/墙顶在 1.5，脚会落到上一格）、
  不是薄装饰（身体是站在那一格里，不是站在它上面）、且与身体柱相交。14/16 与 15/16 那族
  （灵魂沙、泥、耕地、土径、蜜块）、底半砖、箱子、工作台都是地板——身体确实站得住。
- **`isBreakableObstruction`**：非空气非流体、有碰撞形状、徒手瞬间可拆（睡莲、薄雪、压力板）。
- **`breakCost`**：按原版 `getDestroyProgress` 的语义——不需要工具的方块徒手就是「正确工具」，
  需要工具而没有的按 ÷100 进度再乘 ×3 错工具税；在全部 36 格里选最优工具（两边执行器都会换）；
  原木乘 `pathfinderLogBreakTax`（当前目标是原木时豁免）；整体乘 `pathfinderBreakCostMultiplier`。
  药水效果与效率附魔的持有者每次 `beginSearch` 快照一次（`CellRules.DigSnapshot`）。
  依赖姿态的减速（眼在水里、浮空）不在这里——它们描述身体现在在哪，不描述这次未来的挖掘。

三份视图各自保留的部分：`ClientWorldView` 的 `BreakFeasibility` 毒格、逃逸时只拆树叶的例外、
浮水挖掘 ×5/×25 的两参 `breakCost(p, from)`；`LevelWorldView` 的 `allowBreak` 闸。
可放置计数的不对称（客户端数快捷栏 9 格，服务端数 36 格）是刻意的：各规划器数自己执行器够得到的范围。

## 怎么验

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk bash ./gradlew stagewrightIntegratedServerFabric \
  -Pstagewright.scenes='wd.clientWorldViewParity'
```

场景在真客户端上铺 50 种地形，对每格的下/脚/上三格问 11 个问题，只记不一致的行，判据是零行。
它在专用服上 `skip`（没有客户端视图可比）。新加一种视图或改一条规则，先跑它。

布景上的一个坑：调色板要铺在身体脚边的网格里，不能排成一行走远——客户端只有玩家周围的区块，
没收到的格子在它那边读成空气，会被记成一堆并不存在的分歧。

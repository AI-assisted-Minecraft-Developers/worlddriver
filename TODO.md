## ⬜ `bridgePlace` 没落下方块：**定性 = 已有能力的缺陷**,本轮只加读数(等级 `compiled`)

### 1. 执行侧在哪

| 层 | 位置 | 做什么 |
|---|---|---|
| 规划 | `pathfinder/moves/BridgePlace.java` | 产出 `move=bridgePlace` 的边,`toPlace = to.below()` |
| 执行 | **`movement/WalkerTickClimb.java:1027-1054`** | 遍历 `edge.toPlace`,`a.aimAtBlock(b)` + `a.place(world, b)`;入口闸在 `:957` `hasPendingEdge(...)`,进入后把 forward/jump 归零 |
| 服务器致动器 | **`sim/ServerPlayerAvatar.java:292-307`** | `place` 扫 `cell` 的六个邻居找**实心**面 → `placeOn` → `holdPlaceable()` → `fp.gameMode.useItemOn` |

⇒ **`bridgePlace` 的执行侧是实现了的,不是缺一项能力。** 按项目规矩确认过了:这是已有能力的缺陷。

### 2. 它在别处工作过 —— **有,而且是同一具身体、同一个致动器**

`wd.bridgeGap`(`WorldDriverTerrainScenes`)挖出 **3 格宽的沟**并把沟底下方 4 排也清空,
`allowBreak=false` 逼它架桥,用 `ServerPlayerAvatar` + `Walker` 跑 600 tick,断言身体**过去了**。
`wd.bridgeDescendPlaceLip` / `wd.bridgeFootholdPlace` 更进一步,断言 **dirt 被消耗**
(「arrived but no block was consumed」)。这些都在 222 绿里。

⇒ **服务器身体在真沟上确实放得下方块。** 所以病**不在**「执行侧从来没放过一块」那一支,
而在「虚空里的某一步失效」。**这两支修法相反,先证伪了一支。**

### 3. 为什么现在还不能动手:**三处静默早退,一条读数都没有**

```java
place(w, cell):   六个邻居都不实心 → 直接 return，无日志无计数
placeOn(...):     !holdPlaceable() → 直接 return，无日志无计数
placeOn(...):     useItemOn 的 InteractionResult 被丢弃
```

而且 `island.0.plan` 的 `pathLen=7 move=bridgePlace` 是**这一腿末尾**采的,
那时身体已在 −23228 —— **虚空里 `BridgePlace.eval` 的每一条前提都成立**
(不查脚下支撑是它的设计),所以那一行描述的是坠落中的规划,不是站在台子上时的规划。
**这是我自己那张表的缺陷,先说清楚。**

### ⛔️ 更正:**次序那一支被代码排除了**,病在更上游

读数命中的是我那张表**没有的第五行**:`calls>0，但全部发生在身体离开地面之后`
(首次调用身体在 `96,46,0`,平台是 x 98–102 顶面 y=49)。**表不是穷举,已补。**

但由此推出的「`hasPendingEdge` 归零 forward/jump 晚于身体迈出去」**不成立**,理由是两处代码:

- `WalkerTickProgress.java:301` —— `else if (hasPendingEdge(world, se)) break;`
  **步指针不许越过一个 place 还没落地的节点。**
- `WalkerTickClimb.java:957` —— 只要 `edgeAt(step)` 还 pending 就进入,**先把 forward/jump 归零**再瞄再放。

⇒ 次序不变量是**实现了的**,而且是硬 `break`。所以真相不是「边 pending 太晚」,而是
**身体站在平台上时,步指针处根本就没有过 bridge 边** —— 全程第一条 bridge 边出现在 `96,46,0`,
那已经在虚空里。**边从来没有「变成 pending」过。**

⚠️ 而虚空里 `BridgePlace.eval` 的每条前提都平凡成立(它按设计不查脚下支撑),
所以 `move=bridgePlace` 和 `pathLen=7` 是**坠落中重规划**的产物。

⇒ 问题上移到**规划**:从平台出发的那条路里为什么一条 bridge 边都没有。
当前假说(**未证明**):A* 没能找到通往 100 格外 `(0,0)` 的完整桥(单格桥 ≈80 代价 vs 走 10),
退回 **best-effort** 段,而不架桥能到的最远点就是平台西缘,身体走到缘上、被动量带下去。

### 📌 预测(写在动手之前)

**这一轮仍然只加读数,20 级不会变绿,也不该变绿。**

新读数 `首个计划`(`BotState.ProcessSlot.firstPlan`,在 `IntentProcess` 里**锁存一次**:
第一个真正持有路径的 tick,记身体坐标 + `pathLen` + 首节点的 move)。

| `首个计划` | 结论 | 下一刀落在哪 |
|---|---|---|
| 身体 **y=49**、`move=walk`、`pathLen` 小(≈3) | 平台上那条路**不含桥**,best-effort 到缘 | **规划侧**:代价/节点预算/best-effort 的退回策略 |
| 身体 y=49、`move=bridgePlace` | 平台上就有桥边,却没放成 | 回到**致动器**,且与 `hasPendingEdge` 的硬 break 矛盾,要重查 |
| 身体 y<48 | 第一条路就是在空中算的 | 身体在**拿到任何计划之前**就掉了 —— 病在离开平台的那几 tick |

我**预计第一行**,但不改判据。

### ⚠️ `pathLen` 的计量 —— 先确认,因为一整段推断压在它上面

**`pathLen()` = `path.size()` = 节点数,含身体起步的那一个。**
`edges` 与 `path` 同下标(`edges.get(i)` 是**进入** `path.get(i)` 的那条边),所以节点 0 没有自己的位移:
**N 个节点 = N−1 步**。而且它**不是距离** —— 一个 `fall7` 或一段被平滑的对角线,一个节点就跨好几格。

⇒ 读数里的 `pathLen=17` 是 **16 步**,不是「17 格」。以「17 个节点 ≈ 17 格正好铺出平台」为前提的倾向
**据此作废**,必须由读数来判。已写进 `Walker.pathLen()` 的 javadoc。

**末节点是不是目标?只有搜索真的到达时才是。** `seg.pathBestEffort = !res.goalReached()`
(`Walker.java:1453`),best-effort 的半截路末节点是 A* 能靠近到的最近点。所以「路的末节点」和
「要去的地方」是两个问题,`planTally()` 只回答前一个,并把 `到得了目标=` 一并打出来。

### 📌 (a)/(b) 判据(跑之前写死,照你给的三行)

新读数 `首个计划` 现在带 **末节点** + **`到得了目标`** + **各 move 计数**(`Walker.planTally()`)。

| 读数 | 结论 |
|---|---|
| 末节点 **x≥98**(仍在平台上) | **(a)** best-effort 半截路,且 `walk` 计数应 ≤ 平台内步数 |
| 末节点 **x<98 且 move 全是 `walk`** | **(b)** 虚空被当成可走 |
| 末节点 x<98 但含 `bridgePlace` | 第三种:路里有桥边却没走到 ⇒ 回查 `:301` 那条 break 为什么没拦住 |

`到得了目标=false` 与 (a) 一致但**不能单独定 (a)** —— (b) 的一路向西同样到不了 `(0,0)`。
**分开两者的是末节点的 x 和 move 计数,不是这一位。**

### ✅ 18 vs 47 的矛盾:解释掉了,**不是分段规划,是路径被平滑过**

`Walker.adoptPath`(`Walker.java:1486`)在路被驱动之前先跑
`PathSmoothing.stringPull`,它把**一整串同 y 的 `walk`/`diag` 边收成一条**,端点之间走直线;
只有竖直/parkour/攀爬/破坏/放置边作为硬路点保留(`PathSmoothing.plainFlatWalk`)。

⇒ `walk×7` 可以是 7 格,也可以是 70 格。这一趟:8 个 `stepUp` + 1 个 `parkour3` 是逐格路点(11 格),
剩下 **36 格由 7 条被平滑的 walk 承担**,合计 47 —— **和 `98→51` 对得上,没有矛盾,也不是两条路径。**

**这是这一族读数第三次被当成距离**:`pathLen`(节点数不是格数)、`pathMove`(一条边不是整条路)、
现在是 move 计数(平滑后不是格数)。共同的规矩已写进 javadoc:
**先问清一个路径读数在数什么,再拿它做除法。**

### 📌 (支撑判据 / 世界是假的)判据 —— 照你给的三行写死

新读数 `沿路`(`Walker.planTerrain`):沿这条计划取首节点、末节点和中间约 4 个,
各记**本格 / 下方格**的 空|水|实心,外加 **`known=`**(`WorldView.isKnown`,
`LevelWorldView` 里就是 `level.isLoaded(p)`)。

| 中间节点读数 | 结论 | 刀落在哪 |
|---|---|---|
| 下方**空** 且 `known=true/true` | **支撑判据**漏了「下面是虚空」 | 可通行/落脚检查 |
| `known=false` | **喂给判据的世界是假的** | 规划前加载,或「未加载即不可通行」 |
| 下方**实心** | 我们对末地那片空间的认知错了 | **停下重问** |

⚠️ 用 `WorldView` 自己的谓词而不是方块名,是刻意的:规划器从来看不到 `BlockState`,
**报一个它没查过的东西,就是又一条命名了错误机制的证据行。**

### ✅ 支撑判据与「世界是假的」双双出局 —— 但**节点采样看不到节点之间**

```
[0]100,49,0 下方实心 known=true/true  …  [16]51,57,0 下方实心 known=true/true
```

全部节点下方实心、区块全加载 ⇒ 两支都排除,`stepUp=8` 与 y 49→57 自洽:
**规划器规划的是一条走在真实地面上的上坡路。**

⚠️ 但 `planTerrain` **只采节点**,而平滑后相邻节点可以差 9 格
(`[0]100,49,0` → `[4]91,50,0`),身体从 **`96,46,0`** 开始掉,**正落在没有采过的那一段里**。
**只看路在哪停,看不见路从哪穿过去。**

### 📌 本轮读数与判据(跑之前写死)

| 新读数 | 给什么 |
|---|---|
| `逐格`(`Walker.planSpans`) | 前 4 段**沿直线逐格**采 `下方是否实心` + `known`,报每段格数与**缺口位置/宽度**;无缺口时打「缺口无」——**只靠沉默的全清和从没跑过的检查分不开** |
| `平滑前` / `平滑后` | `rawPlan` 在 `stringPull` **之前**锁存,与平滑后的并排;`adoptPath` 已被 grandfather 到行数上限,所以锁存放进 `smoothAndRemember` 里,**净行数不增** |

| 读数 | 结论 | 刀落在哪 |
|---|---|---|
| 缺口在 x=96 附近,且**平滑前是逐格 walk**、平滑后一条直线跨过它 | **`stringPull` 只验端点** | 平滑:直线要逐格验支撑 |
| 缺口在 x=96 附近,且**平滑前那里本来就是一条跨格边**(`parkour3`/对角) | 病在**那条边的可行性判据** | 该 move 的 `eval` |
| 逐格全无缺口 | 地面确实连着,身体掉的原因在别处 | 回到执行侧重问 |

### ⚠️ 20 级 javadoc 已按这个读数更正

原话「架桥和爬塔是这一级自己的事」把**架桥当成了既定前提**,而采样说地面是连的。
`stageDragon` 的 javadoc 和 `rehearsal.stand` 的文案都已改成:
**「中间是虚空、必须架桥」未经证实;地面若真是连的,这一段的正确结局是走过去,不是架桥。**
1024 圆石因此是「以防有缺口」的存量,**不是缺口存在的声明**。

### ⛔️ 这一刀**没有落**:`stringPull` 已经逐格验了,「只验端点」与代码矛盾

`PathSmoothing.stringPull` 的合并循环只在下面这条成立时才延长 `j`：

```java
&& losWalkable(w, path.get(i), path.get(j + 1))) { j++; }
```

而 `losWalkable` **逐格**采样,每一格都查:

```java
if (!w.isPassable(c) || w.isHazard(c)) return false;              // 本格
if (!w.isPassable(c.above()) || w.isHazard(c.above())) return false;  // 头顶
if (!w.isSolid(c.below()) && !w.isWater(c) && !w.isClimbable(c)) return false;  // 支撑
```

外加对角 L 角检查。**这正是你要求的那三件事(支撑 + 头顶/身位 + 整条线),它已经在做。**
把它套到 `98,49,0→95,49,0` 上,第一格 `97,49,0` 的 `isSolid(97,48,0)` 为假、非水非可攀 ⇒ **必然 return false**,
这一段不可能被这个循环收掉。

⇒ **观察到的那一段不可能出自这个循环**,可它的 move 计数又正是这个循环的签名
(`stepUp` 8、`parkour3` 1 前后不变,`walk` 38→7)。**这是一个真矛盾,不是可以绕过的细节。**

**所以我没有写「逐格验证」——在一个已经逐格验证的循环上再加一遍,是加一个 no-op 再把通过记在它头上。**
本仓为这一族(「一次通过被记在树里已经没有的代码头上」)付过学费。

### 📌 本轮读数:`PathSmoothing.smoothingAudit()`

在**发出**合并段的那一行(`else` 分支)重问一次同一个谓词并计数:

| 读数 | 结论 | 下一刀 |
|---|---|---|
| `不可走 > 0` | 有段绕过了准入测试到达发出点 | **本循环的控制流**,`最后一条` 给出是哪一段 |
| `不可走 = 0` | 这里发出的每一段都可走 ⇒ **平滑不是来源** | 查 `stringPull` 返回 与 `adoptPath` 赋值 **之间**那一段(`walkerCommitTailPlatform` 的尾部回撤、`PathProjection`、续段 `adoptPath`) |

`不可走=0` 是**吃重的那一半**:它才是难立的那个主张。

### 📌 预测

- **20 级第一段仍然掉下去**,本轮是纯读数。
- **222 场闸:一条读数都不许变**。`auditEmit` 只调用一个既有的纯谓词并计数,不改任何分支;
  `losWalkable` 本身一字未动。若有任何 `wd.*` 变红或变绿,**就是这个「纯仪表」的判断错了**,先回滚再论。
- 若下一趟 `不可走=0`,则「地面除那两格外是连的」这个前提仍然成立,
  **第一段的正确结局是走过去**;届时预计的下一个障碍是**爬塔**(`TowerProcess` 上 20–40 格的黑曜石柱),
  那是本梯最没有信心的机制。

### ✅ 先钉死:`逐格`/`沿路`/`平滑后` 审的是**同一份**路径

三者都读 `Walker.path`,而且是在 `IntentProcess` **同一条字符串拼接**里调用的,中间没有 tick。
没有第二份副本。⇒「发出全清」与「采纳有缺口」说的是**同一批节点**,**矛盾是真的,不是错位比较。**
已写进 `planSpans` 的 javadoc。

### ⛔️ 但矛盾可能根本不存在:**我这条读数自己会造假**

`planSpans` 逐格判支撑,**却没有报进入该段的 move**。而 `parkour3` / `fall` **就是用来跨空的**——
在一条跳跃边下面报「缺口 2 格」,是在命名一个不存在的故障,正是本仓反复付学费的那个形状。

算术上这一支非常可疑:

- 平滑后 move 计数是 `{walk=7, parkour3=1, stepUp=8}` —— **恰好一条 `parkour3`**;
- 出缺口的 **段1 `98,49,0→95,49,0` 正好跨 3 格、同 y** —— 与 `parkour3` 的形状逐字吻合;
- 而 `auditEmit` 只在**合并分支**里跑,原样保留的边(`continue` 分支、danger/bias 分支)**不经过它**。
  一条原生 `parkour3` 边因此既**不会**被平滑审计,也**本就不该**要求中间格有支撑。

⇒ 若段1 的 move 是 `parkour3`,那么:**平滑没错、支撑判据没错、A* 也没错**——
A* 规划的是**跳过那两格**,而故障在 **`parkour3` 的执行**:身体没跳过去,掉进了缺口。
`首次调用 96,46,0` 正落在这条跳跃边的中途,与「起跳了但没跳到」一致。

**本轮改动**:`planSpans` 每段加印 `move=`,并在缺口后缀上
「（这是跳跃边,跨空是它的用途,不是缺陷）」。**一行读数,直接判生死。**

### 📌 谁能在「发出」和「采纳」之间改节点 —— 查完了,答案是**没有人**

```java
SmoothResult sm = smoothAndRemember(world, res, profile.bias());
path = sm.path;                    // 中间没有任何语句
edges = sm.edges;
seg.pathBestEffort = !res.goalReached();
if (walkerCommitTailPlatform && bestEffort && path.size() > 4) { …只截尾… }
```

- 平滑返回与赋值之间**零语句**;
- `walkerCommitTailPlatform` 只**截掉尾部**节点,**不可能在中间造出缺口**;
- `PathProjection` 只读不写;续段 `adoptPath` 是**整份替换**,会重走同一条平滑与审计。

⇒ **「节点被动过」这条线也不成立。** 三条路都堵死,剩下的解释就只有上面那条:
**那一段本来就是跳跃边,`planSpans` 把它误报成了缺口。**

### 📌 预测

- 下一趟 `段1` 的 `move=` **预计是 `parkour3`**。若是,课题从「规划/平滑」整体转到 **`parkour3` 的执行**,
  并且**上面三轮所有关于平滑与支撑的怀疑全部作废**(它们都建立在「那是一条 walk」上)。
- 若 `move=walk`,那矛盾是真的,回到控制流那条线继续查。
- 20 级仍然不该绿;222 场闸**仍应一条读数都不许变**(本轮只多打印一个字段)。

### ⚠️ `wd.bridgeGap` 一族:本轮**一条读数都不许变**

这一轮是**纯仪表**(两个新字段 + 一个 `+=`,没有一处改变分支),所以对照标准不是「哪些允许变」
而是 **`wd.bridgeGap` / `wd.bridgeDescendPlaceLip` / `wd.bridgeFootholdPlace` 的读数逐字不变**。
「哪些允许变」那张表属于**下一轮真正落刀时**,那时它们走的是同一段执行代码,必须逐字对照而不是只看颜色。

新读数 `island.N.place`：`calls / 无面 / 无块`,外加 **`firstCallAt`** 和 **`firstNoFaceAt`**
(各带身体坐标 + 目标格)。后两个是关键:总计数会被 5900 tick 的坠落淹没,**第一次**不会。

| 首次读数 | 结论 | 刀落在哪 |
|---|---|---|
| `calls=0` | 致动器**从没跑过**,身体在有地面时就走掉了 | 驱动侧的**次序/动量**(`WalkerTickClimb:957` 入口闸或 drive 的残余速度) |
| `firstCallAt` 身体 **y=49** 且 `firstNoFaceAt` 也在 **y=49** | 站在台子上就找不到可点的面 | **`ServerPlayerAvatar.place` 的找面算法**(它只认 `cell` 的邻居,不认身体脚下那块) |
| `firstCallAt` y=49、`firstNoFaceAt` **y<48 或不存在**,而 `放了 0 块` | 面找到了、`useItemOn` 拒了 | 放置本身(`BlockPlaceContext`),要再加一层 |
| `无块 > 0` | `holdPlaceable` 说没方块 | 快捷栏那一族(**我算过应当不成立**:kit 是 sword→slot0、cobblestone→slot1..16,槽 1–8 全是圆石) |

我**预计**落在第一或第二行,但**不预先挑一个**——上一轮正是因为没挑才一趟判完。
`无面` 的总数几乎必然很大(坠落中每一 tick 都找不到面),**所以只读总数会误导,必须读首次**。

---

## ⬜ 20 级 TIMEOUT 40001 tick：三件事(等级 `compiled`)

### 1. 预算 floor 是死代码,**逃生口在 Java、闸门被 build 文件永久顶开**

```groovy
// fabric/build.gradle:293（改之前）
property 'worlddriver.journey.rehearse.budget',
        (project.findProperty('rehearseBudget') ?: '40000').toString()
```

**默认值填在 Gradle 侧,而不是留空。** 于是 `System.getProperty(BUDGET_PROPERTY)` **每一趟都是非空**,
`capFor` 里那句「显式 `-PrehearseBudget` 一律胜出」的分支**永远成立**,`budgetFloor` 从落地那天起
**一次都没被调用过**。我上一轮写的「默认帽对 DRAGON 是 no-op」因此是假的——机制对,前提不对。

**改法**:`?: ''`,和这个 block 里其他每一根杆一致;默认值只留在 Java 里
(`JourneyRehearsal.DEFAULT_BUDGET`,同样是 40 000)。

**回归闸逐条**:DRAGON 之外 `budgetFloor` 恒 0 ⇒ `max(40000, 0) = 40000`,与改前
`parse("40000") = 40000` **逐字相同**。PORTAL_LIT 40 000、END_PORTAL 30 000、END 20 000 全不变;
显式 `-PrehearseBudget=N` 改前改后都取 N。**唯一变的是 DRAGON:40 000 → 500 000。**

⚠️ 你说的 7 小时风险:**由第 2 条的虚空守卫抵消**。身体在第 0 段就掉出世界,守卫在第 1 段开头
(≈6000–7000 tick,按这一趟 40001 tick/33 分钟的速率约 **5 分钟**)判红。**不是几秒,是 5 分钟**,
不夸大。要退回旧天花板就 `-PrehearseBudget=40000`。

### 2. 真正的病:出了降落台就掉下去——**先加读数,没动机制**

```
island.0 = 100,49,0（距中心 100）   island.1 = 145,-23228,0   …   island.6 = 383,-140809,8
```

每段掉约 23 500 格,rung 一段一段继续下发目标;身上 1024 圆石,桥没架起来。
**三种机制会产生一模一样的轨迹,而这一趟什么都没记下来分开它们。** 所以本轮只加读数:

| 读数 | 「没放就迈出去」(执行顺序) | 「放了没踩上」(落脚判据) | 「压根没打算架桥」(代价/可通行) |
|---|---|---|---|
| `island.N.plan` 的 `放了 K 块` | **0** | **>0** | **0** |
| 同行的 `move=` | `bridge*` | `bridge*` | `walk`（把虚空当可走）**或**根本没有路 |
| `pathLen` / `active` | 有计划 | 有计划 | `pathLen=0` 且 `active=true` = A* 真的没给出路 |

⚠️ `active` 必须一起读:`ProcessSlot.reset()` 在每次终止时清掉 `pathLen`/`pathMove` 而保留
`endReason`/`lastError`,所以**一条跑完的腿会报 `pathLen=0 move=null`**,不加 `active` 就和
「规划器从没出过路」分不开。这一点写进了 `planOf` 的 javadoc。

另加 `island.N` 每段的**脚下方块**和**垫块存量**,以及 `island.fellAt`。
**虚空判据不是调出来的**:末地 `min_y = 0`,所以 `y < 0` 就是「在世界之外」,是建筑下限本身。

**没有给 20 级预铺桥**——理由和拒绝给 19 级补台子一样,桥是这一级要考的机制。
守卫只会更早判红,救不了任何东西。

### 3. `fightRange` 的镜像陷阱:布景时刻的读数在失败时刻撒谎

`rehearsal.fightRange`(布景时刻 127.8 格,在范围内)+ `inPlayerList=true` + `dragonUUID=null` ⇒
下一个人会拿前两行**排除距离**,而距离恰恰就是原因(失败时身体在 23 000 格之下)。

两头都堵:布景那一行文本前缀改成 **「【布景时刻测的,之后不再成立 —— 失败时看 dragon.rangeNow】」**;
新增 `JourneyEndRungs.fightRangeNow`,在 `fellOffTheIsland` 和 `noDragonHere` 两处以
**失败时刻**重测并记成 `dragon.rangeNow`,`noDragonHere` 的失败文案也改成先读它
(玩家列表和 192 格是 `validPlayer` 的两半,任何一半不成立都得到同一个 `dragonUUID=null`)。

---

## ⬜ 一个朝主世界目标走的进程,在身体已经在末地之后继续走(刀在 `bot/`,等级 `compiled`)

**裁决已出**:`platform.obsidian = 25/25` ⇒ 台子建好了 ⇒ **身体是自己走掉的**。两趟落点还不同
(水平漂移 13 vs 16、垂直 4426 vs 4340)⇒ 不是传送错的,是走掉的。

**病灶**:`IntentProcess` 的 `Goal` 坐标是**有维度的**,而进程在脚下的维度换掉之后**照旧推**。
19 级的过界发生在 `settle(IntentProcess(Goal.Block(-1092,25,1314)), 1200, …)` 内部,过去之后
walker 仍朝那个**主世界**坐标推,而台子只有 5×5。

### 这条边的全部使用者(动手前查的,`grep "new IntentProcess"` 40 处,只有 3 处能跨维度)

| 使用者 | 现在 | 这一刀之后 |
|---|---|---|
| `JourneyEndRungs.stepIn`(19 级) | 过界后继续走 1200 tick ⇒ 掉出台子 | **被治的就是它** |
| `JourneyEndRungs.backToTheOverworld`(17 级) | 下界→主世界,过界后继续朝一个**下界**坐标走 3000 tick | 同一缺陷,顺带修好;17 级从没爬到过,**无实测基线** |
| `WorldDriverJourneyScenes.holdInThePortal` 的走路腿(13 级) | 只在身体飘出门时才是 `IntentProcess`,且只有 150 tick;**正常过界发生在 `HoldStill` 下** | 多半一字不变 |

**其余 37 处全部是单维度内的行走**,够不到这条边。
全仓 `endReason` 的使用者**没有一个是断言**——testmod 里全是 `rig.evidence(...)` 证据行,
`WorldDriverCoverageScenes` 读的是 `walker.lastEndReason`(Walker 层、单维度竞技场)。⇒ **没有断言会被这个新值翻**。

### 📌 预测(写在动手之前,不许事后改)

**19 级(`-Prehearse=END`)**

| 读数 | 预测 |
|---|---|
| 颜色 | **绿**——但不是必然:刀移除的是「继续推」,没有清除**过界瞬间的残余速度**。若动量仍把身体带下 5×5,它会再红一次 |
| `arrived.at` 的 y | **绿的话落在 48–52,最可能正好 49**(vanilla 把 `ServerPlayer` 放在 `END_SPAWN_POINT.getBottomCenter()-(0,1,0)` 即 y=49;台子地板 48、空气 49–51)。**红的话仍是数千格之下**——这两种差三个数量级,不会看混 |
| `platform.obsidian` | **仍是 25/25**。这一刀只改「进程什么时候停」,改不了 `createEndPlatform` 铺过的方块。**它要是变了,说明刀切到了别处** |
| `spawnPoint` 水平漂移 | **0 或 1**(现在是 13/16)——身体到站之后不再走 |
| `step.2` 的「停在」 | 从 `84,-4290,4` 变成**台子上那一格** |
| scene 总 tick | 从 2413 降到 **约 1200–1400**(1200 tick 的 settle 在过界处提前结束) |
| 新 `endReason` | `dimension-changed`,与 `arrived` / `path-consumed` 三者互不相同 |

**13 级(六道闸里的 `wd.journey13Nether`)**

| 读数 | 预测 |
|---|---|
| 判决 | **不变,仍 PASS** |
| `dimension` / `arrived.at` / 8:1 缩放断言 | **一字不变** |
| `portal.leg.*` | **最多少一行**,且只在「身体飘出门、恰好在走路腿里被传走」那一支;正常路径走的是 `HoldStill`,一行不变 |
| 总 tick | 最多少 150 |

**17 级**:无实测基线,只预测方向——`return.at` 从「一次朝下界坐标的无意义 3000 tick 行走的终点」
变成「过界落点」。判决不变(它本来就在之后查维度)。

**在线 `mc.bot.goto`**:跨维度的 goto 现在会以 `endReason=dimension-changed` 终止而不是继续推。
这是 RPC 面的行为变化,**明写在这里**,不当无声副作用。

### ⚠️ 这一刀没有覆盖到的洞(明说,不假装)

守卫放在 `IntentProcess` 里,**不在 `Walker` 里**。所以 `MineProcess` / `BuildProcess` 等其他持 `Goal`
的进程仍有同样的洞。选 `IntentProcess` 是因为它是本缺陷的唯一现场、且爆炸半径可预测;
`Walker` 一改就同时动六道闸,我给不出那么紧的预测。**要不要扩到 `Walker` 由你定,别默认已覆盖。**

---

## 🟥 19 级的假绿：**判据漏了一维**（已修，等级 `compiled`）

```
dimension = minecraft:the_end     ✅        arrived.at = 87, -4376, -1
spawnPoint = 100, 50, 0（漂移 13） ✅        underfoot  = void_air
portal.cells = 9   eyesSet = 12   doorway = 9   step.2 跨维度成功   →  PASS 2413t
```

**「漂移 13 格」是水平量**（`max(|100−87|,|0−(−1)|)`），Y 从来没进过判据 ⇒ 一具**正在坠入虚空**、
比落点低 **4426 格**的身体拿到了「进入末地」。**能同时满足全部判据、又完全没达成目的的，
就是判据漏了一维。**

### 改法：三个互相独立的量，且不许只加 Y

`JourneyEndRungs.judgeTheCrossing` 现在断言 **维度 ✅ 且 垂直差 ≤ `END_ARRIVAL_FALL`(4) 且
脚下 `blocksMotion()`**。为什么第三条不能省：**「y 对了」和「脚下有东西」是两件事**——身体可以在
迈出台子边缘的那一 tick 恰好还在 y=49，也可以在 y=49 悬在洞上。用 `blocksMotion()` 不用 `onGround`
（这具身体的 `onGround` 两个方向都错），也不靠名字认 `void_air`（那是**建筑高度以下**的返回值，
和「没有台子」区分不开）。

`END_ARRIVAL_FALL = 4` 的依据是 vanilla 几何：`createEndPlatform` 在 **y=48** 铺黑曜石、49–51 留空，
`EndPortalBlock` 把 `ServerPlayer` 放在 `END_SPAWN_POINT.getBottomCenter().subtract(0,1,0)` 即 **y=49**
（比 `END_SPAWN_POINT` 低一排）⇒ 48..52 才是「在台子上」，±4 留一排余量，同时仍以三个数量级压住 4426。

**这一趟会变红**：`|−4376−50| = 4426 > 4`，且 `void_air.blocksMotion() = false` —— **两条独立地翻红**。
水平 13 ≤ 16 仍然过，那是对的：它查的是另一族缺陷（吞掉目的地）。

### 台子为什么没有：**不是下界那一族**，而且我还没证明是哪一族

⚠️ 先更正两个名字：1.21.1 **没有** `ServerLevel.makeObsidianPlatform`，也**没有**
`ServerPlayer.findDimensionEntryPoint`（本仓库 grep 0 命中）——那是 1.20.x 的名字。1.21.1 的链是

```
EndPortalBlock.entityInside → Entity.setAsInsidePortal → Entity.handlePortal
  → PortalProcessor.getPortalDestination → EndPortalBlock.getPortalDestination → changeDimension
```

而 **`createEndPlatform` 就在 `getPortalDestination` 里面**，在构造 `DimensionTransition` **之前**，
和「目的地 = `END_SPAWN_POINT`」同一个 `bl` 分支（反编译原文）：

```java
boolean bl = resourceKey == Level.END;
BlockPos blockPos2 = bl ? ServerLevel.END_SPAWN_POINT : ...;
Vec3 vec3 = blockPos2.getBottomCenter();
if (bl) { EndPlatformFeature.createEndPlatform(serverLevel2, BlockPos.containing(vec3).below(), true); ... }
```

⇒ **目的地和台子出自同一次调用**：拿不到 `(100,·,0)` 就不会走到那里，走到了就一定调过 `createEndPlatform`。
身体的 X/Z 是 `87,-1`（= `100,0` 掉了 1100+ tick 之后的水平漂移）⇒ **目的地到位了**。
而下界那一族丢的是**目的地本身**（差 87 501 格）。**所以这不是同一处，我不硬套。**

**当前假说（未证明）**：`stepIn` 用 `settle(IntentProcess(Goal.Block(cell)), PORTAL_WALK_TICKS=1200, …)`
包住整个跨维度，过界发生在这次 settle **内部**；过去之后 walker 仍在朝一个**主世界**目标
`-1092,25,1314` 推，而台子只有 5×5。1130 tick × 终端速度 ≈3.92 ≈ **4430 格**，与实测 4426 对得上。
这是「**一个视角不跟着身体走**」那一族，不是吞目的地那一族。

**判据已经放进树里，下一趟自动裁决**：`platform.obsidian` 数 y=48 那层 5×5。
**25/25 ⇒ 台子建好了、身体是自己走掉的（我的假说成立，修驱动的过界收尾，不是修布景）；
0/25 ⇒ 我读错了，那是驱动的过界缺陷。** 它在 PASS 和 FAIL 两侧都记，否则健康态长什么样没人知道。

⚠️ `advancement.enter_the_end = not-earned` 是**第二个独立信号，本轮故意不并进这个诊断**——
`一个挣不到东西的服务器身体` 那条记录里成就支持还有一半是红的，不先分开它就不能当跨维度的证据。

### 连带：这个缺陷差点被 20 级掩盖

20 级布景**自己** `createEndPlatform`，所以它永远问不到这一条。`stageDragon` 的 javadoc 已写死：
**布景铺的台子是 20 级的前置条件，不是 19 级的产出**——两者在结果文件里长得一模一样
（身体站在 `(100,49,0)` 的黑曜石上），含义相反。**绿的 `wd.rehearse20Dragon` 是关于龙的证据，
不是关于它上游的证据**；读成「20 级过了所以进末地是好的」就是把这一行读反了。

---

## ⬜ 19/20 级布景（第二批；只编译，未跑，等级 `compiled`）

| 项 | 状态 | 位置 |
|---|---|---|
| **19 级 END 配方** | **本轮改的** | `JourneyRehearsal.java:426-433`（分派）、`:916-1040`（`PORTAL_CELLS_A_DOOR_HAS:919`／`stageEnd:941`／`openTheDoorLikeVanilla:989`／`xyzOf:1040`） |
| **20 级 DRAGON 配方** | **本轮改的** | `JourneyRehearsal.java:1042-1167`（`BLOCKS_A_DRAGON_TRIP_NEEDS:1045`／`stageDragon:1099`） |
| 四处 import | **本轮改的** | `JourneyRehearsal.java:20-26` |
| `stagedEyes` javadoc 限定 | **本轮改的** | `JourneyRehearsal.java:1176-1185` |
| `framesAround`／`centreOf`／`standingCellInTheRoom` 可见性 | **本来就在**（上一批放宽的） | `JourneyEndRungs.java` |
| `standInThePortalRoom` | **本来就在**，一行未动 | `JourneyRehearsal.java` |
| rung 19/20 自己的代码 | **本来就在**，一行未动 | `JourneyEndRungs.java:729-996` |

### 回归闸：两条路径都逐字不变

`git diff` 全部内容只有四处：import、分派里**追加在 `END_PORTAL` 之后**的两个 `if`、一整块新方法、
一段 javadoc。⇒ `target == PORTAL_LIT` 在第一支就 `return`，`target == END_PORTAL` 在第七支就 `return`，
两者都到不了新代码；`standInThePortalRoom`／`stagedEyes`／`roomScanChunks`／`budgetFloor` 的**实现**零改动。
rung 19 自己声明 20 000 tick < 默认帽 40 000 ⇒ `min` 取 20 000，不触发 `rehearse.budgetCapped`。

### 三条硬约束怎么落实的

| 约束 | 落实 | 位置 |
|---|---|---|
| 19 级开门**照 vanilla 抄**，不许走 `Avatar.useBlock` | 眼直接写 `HAS_EYE` 块态（走 `EnderEyeItem.useOn` 的前四行去掉 `shrink`/特效），门走**它的尾巴**：`EndPortalFrameBlock.getOrCreatePortalShape().find(...)` → `getFrontTopLeft().offset(-3,0,-3)` 的 3×3 | `openTheDoorLikeVanilla` |
| 20 级**先建台**，落点钉死 `END_SPAWN_POINT` | `EndPlatformFeature.createEndPlatform(end, BlockPos.containing(END_SPAWN_POINT.getBottomCenter()).below(), true)`，再 `teleportTo` 到 `getBottomCenter().subtract(0,1,0)`、`yRot = Direction.WEST.toYRot()` —— 全部照 `EndPortalBlock.getPortalDestination` 逐参数抄 | `stageDragon` |
| 龙要问 `level.players()` | `runRehearsalServer` 早就带 `-Dworlddriver.realPlayerBodies=true`（`fabric/build.gradle:379`）；布景**只读不改**，写成 `rehearsal.inPlayerList` | `stageDragon` |

**为什么落点不许挪**（这条是我加的，不在你的清单里）：`EndDragonFight.validPlayer` 是
`EntitySelector.withinDistance(0, 128, 0, 192.0)`（1.21.1 反编译原文），`(100,50,0)` 距 `(0,128,0)`
**126.8 格**，在内。为「桥短一点」把身体往岛边挪几十格，龙就永不创建，而 rung 20 会打出
「FakePlayer 不在玩家表里」——**那句话届时是错的**（身体在表里，只是超距）。
**一个能让既有诊断撒谎的布景，比没有布景更坏**，所以落点固定，并把距离连同门限一起记进
`rehearsal.fightRange`。

### 起点状态：全部 PROVISIONAL，附真梯校准 key

| 级 | 项 | 值 | 理由 / 校准 key |
|---|---|---|---|
| 19 | 门 | 布景开（3×3 `end_portal`） | 这是 18 级的产出；校准 key `wd.journey18EndPortal` 的 `portal.at` |
| 19 | `cobblestone` | **0**，故意 | 门开在熔岩池上方 + `allowPlace=true`，给石料就把「走得到门格」换成「造得出路」，这一级最该报的发现就报不出来了。key：19 级要先加 `stock.*` 行 |
| 19 | 镐/剑/食物 | 2 / 1 / 16 | 沿用 18 级配方，PROVISIONAL |
| 20 | 降落台 | `createEndPlatform` 建 | 全新末地**没有**这块黑曜石台，vanilla 是到达时才建的 |
| 20 | `cobblestone` | **1024** | 最坏约 460（≈60 格桥 + 10 座塔 × ≈40）的两倍上下。**缺料不会报成缺料**，会报成「走不到主岛」或塔提前停，那正是这一级要考的两个机制。key：19 级的 `stock.*`（同样还不存在） |
| 20 | 只给圆石一种垫块 | 故意 | 让 `pillarBlock`（取 `PILLAR_BLOCKS` 里持有最多的）选择确定 |
| 20 | `cooked_beef` 16 | 摆设 | 这具身体无敌、不饿，rung 20 没有任何读数读它；给了只是与下面几级配方对齐 |

### 判据：跑之前写死（19 级）

| 读数 | GREEN | 说明 |
|---|---|---|
| `rehearsal.eyesSet` | `12 只` | 少于 12 ⇒ 扫到的框架不属于同一个环 |
| `rehearsal.doorway` | `开出 9 格 end_portal` | < 9 ⇒ **布景**失败，已在 `openTheDoorLikeVanilla` 里 `ctx.fail`，不许当成 19 级判负 |
| rung 的 `portal.cells` | `9` | 布景说开了 9 格、rung 自己只找到 0 ⇒ 两处看的不是同一个门（rung 用 `PORTAL_SEARCH=12` 以身体为心重扫） |
| **这一级成没成** | `dimension = minecraft:the_end` **且** `spawnPoint` 的漂移 ≤ 16 | 只看维度会让「站在虚空里」也算过——rung 自己已经断言了漂移，别只读第一行 |
| 失败该怎么读 | 有 `step.0..3` 行 ⇒ 走到了门格附近但没过去；没有 ⇒ 连门格都没走到（`stand.goto` 给 endReason） | 20 000 tick 用尽而无 `step.*` = 走不动，不是过不去 |

### 判据：跑之前写死（20 级）

| 读数 | GREEN | 说明 |
|---|---|---|
| `rehearsal.inPlayerList` | `true` | `false` ⇒ 后面全部无效，直接看 `-Dworlddriver.realPlayerBodies` |
| `rehearsal.fightRange` | `126.8 格 … 在范围内` | 这一行只要不是「在范围内」，`dragon.present=false` 就与 rung 的诊断无关 |
| `level.realPlayers` | `≥ 1` | rung 自己记的，和 `rehearsal.inPlayerList` 是两处独立读数，对不上说明中途掉出了表 |
| `dragonFight.dragonUUID` | 非 `null` | `null` + `crystalsAlive=0` ⇒ 龙从没被创建，**不是打不过** |
| `island.legs` | ≤ 8 且有 `island.reached` 不为 false | 架桥成了 |
| `crystals.left` | `0/N` | 水晶没清完就开打 ⇒ 龙会被治疗，`dragon.hp` 读数无意义 |
| **这一级成没成** | `dragon.dead = true` | — |
| **多少 tick 算超时而不是打不过** | 预算 500 000（`budgetFloor(DRAGON)` = scene 自己声明的数，默认帽对它是 no-op）。**先看 `rehearse.budgetCapped` 在不在**：在 ⇒ 是夹具砍的，不是这一级判负 | 有 `duel.swings` 且 `dragon.hp` 在掉 = 打得动只是没打完（→ 提预算）；`duel.swings=0` 或 `duel.closest` 始终 > 4.5 = 够不着，那是机制问题不是时长问题 |

⚠️ **`DUEL_TICKS = 200 000` 仍是估的**，校准 key `wd.journey20Dragon` 的 `duel.ticks`。

---

## ⬜ 17–18 级布景（第一批；只编译，未跑）

**每一项标明「本轮改的」还是「本来就在」。** 全部编译过 + `check_source_budget.py` 过，等级 `compiled`。

| 项 | 状态 | 位置 |
|---|---|---|
| **A** recon 的岩浆湖 gate | **本轮改的**（`git show HEAD` 里 `needsTheLavaLake` 出现 **0** 次） | `JourneyRehearsal.java:100-123`（新谓词）、`:266-278`（recon 早退） |
| **B** 四个私有静态放宽 | **本轮改的** | `JourneyEndRungs.java:1131`（分节注释）、`:1153/:1201/:1208/:1226` |
| **C** 18 级 END_PORTAL 配方 | **本轮改的** | `JourneyRehearsal.java:347-350`（分派）、`:687-895`（常量＋`stageEndPortal`／`standInThePortalRoom`／`stagedEyes`／`roomScanChunks`） |
| **budgetCap 的 floor** | **本轮改的** | `JourneyRehearsal.java:182-196`（含 `rehearse.budgetCapped`）、`:223-283`（`capFor`／`budgetFloor`） |
| 两个 Gradle 杆 | **本轮改的** | `fabric/build.gradle:339-356`（`-Peyes` / `-ProomScanChunks`） |

### 回归闸：`-Prehearse=PORTAL_LIT` 逐字不变，逐条论证

- **A**：`needsTheLavaLake(PORTAL_LIT) == true` ⇒ 走的还是老的勘湖路径，一行没绕。
- **B**：只改可见性，实现零改动。
- **C**：只在 `target == END_PORTAL` 那一支里生效。
- **budgetCap**：`budgetFloor` 对 DRAGON 之外**恒为 0**，于是 `max(40000, 0) = 40000`，
  与改动前 `budgetCap()` 的返回值**逐字相同**。⚠️ 我否决了「默认不再压低 scene 自己的预算」这个更简单
  的写法：`wd.journey12PortalLit` 自己声明 250 000 tick，那样写会把它从 40 000 抬到 250 000，
  **正好动了当回归闸的那条路径**。名单必须**逐级显式**，下一个进名单的要论证，不许继承。
- **显式 `-PrehearseBudget=N` 一律照旧胜出**——那是急刹车，floor 不许把它拿走。

#### ⛔️ 更正：DRAGON 的 floor 从我编的 `202 000` 改成它自己声明的 `500 000`

第一版 floor 写的是 `DUEL_TICKS + 2 000 = 202 000`，**这是我拿自己的规矩打了自己的脸**：
对决本身就占 200 000，剩 2 000 tick 要装下「进末地 + 建台 + 架桥 + 找到龙」，
**这正是「给到刚好达标」**（与 `stagedEyes` 拒绝的那个形状同源）。而且它的失败长得会撒谎——
超上限被砍，rung 报的是「龙没打死」，读起来像战斗打不赢。更根本的是：**真梯从没爬到 20 级，
我没有任何测量能支撑一个中间值**，而 scene 自己声明的 500 000 至少是作者考量过的上限，
且仍然是上限、不是「无限跑」。⇒ **floor = scene 自己声明的预算**，默认帽对这一级成为 no-op。
（`DUEL_TICKS` 的可见性放宽也随之**撤回**，仍是 `private` —— 不再需要就不留。）

#### 🆕 上限自己开口：`rehearse.budgetCapped`

只要 `budget < scene 声明值` 就打一行，措辞与任何 rung 的判负**都不一样**：

```
rehearse.budgetCapped = 40000（scene 自己声明 250000，被排练的预算帽砍掉 210000）
                        —— ⚠️ 这一趟如果超时，先看这一行：是夹具把预算砍短了，不是这一级判负。
                        要跑满就 -PrehearseBudget=250000
```

**只在真被砍时出现，所以它的缺席也是读数。** 这条专治我们反复踩的那一族：**失败信息命名了错误的机制。**

### 起点状态：全部 PROVISIONAL，附真梯校准 key

真梯从未爬到 12 级以上，**这一级没有任何一项有 ladder 实测**。

| 项 | 值 | 状态 | 真梯跑到后去看哪个 key |
|---|---|---|---|
| 位置 | `standingCellInTheRoom(centre)` | PROVISIONAL | `wd.journey17Stronghold` 的 `room.landedAt` |
| `ender_eye` | 12 | PROVISIONAL（有 **16 级排练**实测支撑，非 ladder） | `wd.journey16EyeOfEnder` 的 `data.ender_eye` |
| `stone_pickaxe` | 2 | **故意与真梯不同**，已在 `rehearsal.gave` 里声明 | 需先给 17 级加 `stock.*` 行 |
| `iron_sword` / `cooked_beef` | 1 / 16 | PROVISIONAL（沿用 14/15/16 配方） | `wd.journey14BlazeRod` 的 `weapon`；食物无 key，需新增 |
| `cobblestone` | **0** | 故意为 0，理由记在 `rehearsal.noBlocks` | 需先给 17 级加 `stock.cobblestone` |
| 框架有没有眼 | **世界原样，一格不补** | 不是给的量 | `wd.journey17Stronghold` 的 `frames.withEye` |
| `DUEL_TICKS` 202 000 | PROVISIONAL | 对决时长是估的 | `wd.journey20Dragon` 的 `duel.ticks` |

**「不许给到刚好达标」怎么落实的**：12 只眼**不是照空框架数配的**，是照 16 级实测配的。房间自带 k 只眼
时空框架是 `12-k`，于是收尾的 `ender_eye.left` 应当**恰好等于 k**，与 `rehearsal.framesWithEye` 对得上
才算数——**把一个可能的恒等式换成了一次交叉核对**。（16 级自己的配方就有这个病：给的 pearl 恰等于
`EYES_A_PORTAL_COSTS`，`ender_eye.shortfall=0` 是算术不是发现。）被 12 掩盖的「眼不够」分支用 `-Peyes` 专跑。

### 生效判据：只认 **rung 自己写的** key，不认 `rehearsal.*`

| 布景动作 | 下游判据 key | 起作用 | 没起作用 |
|---|---|---|---|
| 把身体放进房间 | `frames.inReach`（rung 用自己的 `FRAME_SEARCH` 重扫） | `12 个` | `0 个` |
| 给眼 | `ender_eye.before` | `12` | `0` |
| 一格没补 | `frames.empty` + `ender_eye.left` | `left == rehearsal.framesWithEye` | 对不上 ⇒ 两处扫的不是同一间房 |
| 这一级成没成 | `portal.cells` | `9` | `0` |
| `-Peyes=N`（N<12） | `eyes.ranOutAt` 出现且 `frames.filled < 12` | 两条都出现 | 只有 `rehearsal.eyes` 变 = **参数被读到、下游没变**，正是 `-PforgeAway` 那次的形状 |

### D 这条未验证前提：判据先写死（跑之前就成立）

`JourneyRoute.stronghold` 是 `/locate` 的 **START 件**，rung 17 用 `ROOM_SCAN_CHUNKS=6`（±96 格）扫框架，
**够不够从没验过**，而 17/18/19 三级全压在它上面。18 级布景调的是**同一个函数、同一个圆心、同一个半径**，
所以它扫到什么 rung 17 就会扫到什么。读 `wd.rehearse18EndPortal` 那一行的 `rehearsal.frames`：

| 格数 | 结论 | 下一步 |
|---|---|---|
| **12** | ±96 够，坐标有效 | 不动常数；再核 `rehearsal.frameCentre` 给的「至少要 N」，**N 必须 ≤ 6** |
| **1..11** | 坐标对、**半径把房间切了** | 照那个 N 抬 `ROOM_SCAN_CHUNKS`，加 1 余量 |
| **0** | 本趟分不开两种可能 | `-ProomScanChunks=12` 再跑：变 12 ⇒ 半径不够；仍 0 ⇒ **烘入坐标站不住**，重跑 `wd.journey01Recon` 重烘 |
| **>12** | 扫到两间房 | 结论是「圆心选法要改」，不是「半径不够」 |

**顺带的第二个数** `rehearsal.roomScanMs`：< 30 000 ms 接受；≥ 30 000 ms 或死在框架看门狗上 ⇒ 改成分批
`getChunk`。**阈值先写在这里，免得跑完再论证。**

#### 读数（2026-08-17，`-Prehearse=END_PORTAL` 无杆，PASS 136 tick）——**表没动过，逐行对**

```
rehearsal.frames      = 12 格 end_portal_frame（以烘入的 stronghold -1168,64,1296 为心，±96 格）
rehearsal.frameCentre = -1091,25,1313，距 stronghold 77 格（切比雪夫）—— 至少要 5，它现在是 6
rehearsal.roomScanMs  = 22938 ms（13×13 区块，全新世界）
frames.filled = 12/12   ender_eye.left = 0   portal.cells = 9   portal.at = -1092,25,1312
```

- `frames = 12` ⇒ 落「够」那一行：**不动 `ROOM_SCAN_CHUNKS`**；配套核对 `至少要 5 ≤ 6` 成立。
- `roomScanMs = 22938 < 30 000` ⇒ **接受，不改分批**。

**⇒ 压着 17/18/19 三级的那条未验证前提，答案是「够」。** 转移得过去的理由是可检的，不是布景自证：
18 级调的是 `framesAround(level, JourneyRoute.stronghold, roomScanChunks)`，与 rung 17 **同一个函数、
同一个圆心（烘入的 `/locate` START 件）、同一个半径**。

> ⚠️ **只富余一个区块，而且是单点。** 房间中心距 START 件 **77 格**（切比雪夫），需要 5 个区块，
> 常数给的是 6。这是 **单一种子（5471）上的单点测量**，不是「6 有余量」——`/locate` 返回的是结构
> 起点，房间在结构里的偏移由该种子的生成决定，换种子它就作废。
> **校准 key**：`wd.journey01Recon` 的 `stronghold`（圆心）配 `wd.journey17Stronghold` 的
> `frames.inReach`；这两行只要有一行换了种子/换了坐标来源，这条结论要重测，不能沿用。

**这一趟没检验到的那半边**：世界一格都没预填（`framesWithEye = 0/12`），所以 `ender_eye.left = 0`
仍然是 `12−12` 的算术——上面「一格没补 ⇒ `left == rehearsal.framesWithEye`」那条**交叉核对本轮
没被执行**，正是它要防的恒等式形状。这颗种子给不出 k>0，只能靠 `-Peyes=N` 从另一头打（判
`eyes.ranOutAt` 与 `frames.filled`，**不判颜色**）。

#### `-Peyes=8`（2026-08-17）：短缺分支**执行到了**，且报的是「眼不够」

```
eyes.ranOutAt = -1090,25,1315（第 8 个空框架）    frames.filled = 8/12
eyes.short    = 缺 4 只                          portal.cells  = 0
```

按跑前定的规矩判：`eyes.ranOutAt` 出现 ✅、`frames.filled < 12` ✅ ⇒ **杆生效**。红是预期，
**不判颜色**。可贵的是它没有笼统地报「门没开」，而是指名了原因和那一格。

#### ⚠️ 由这两趟对照抓出的一条会撒谎的证据行：`ender_eye.left`

| 趟 | `ender_eye.left` | 真实机制 |
|---|---|---|
| `-Peyes=12` | **0** | 12 只全填进 12 个空框架 —— 够用 |
| `-Peyes=8` | **0** | 8 只全填进前 8 个 —— **不够用** |

**同一个读数，两种机制。** 它现在分辨不了「够用」和「不够用」，能分辨的是 `eyes.short` /
`eyes.ranOutAt` / `frames.filled`。`stagedEyes` 的 javadoc 已把
「`ender_eye.left` 精确度量世界预填了多少」限定成**「仅当包够用时」**，并把两趟数字写进去
（`JourneyRehearsal.java:1176-1185`）。**判据：`left` 永远不许单独读**——先看 `eyes.ranOutAt`
在不在，不在才谈得上和 `rehearsal.framesWithEye` 对账。

---

## ✅ 南臂 11 级分叉：结案，**不切 `:510`**（分析完成，无刀可切）

s5 与 s3/s6 的唯一差别：**在 `JourneyPortalRung.java:591` 那次 `HoldStill(20)/40 tick` 静置里，
身体有没有迈完最后一步。** 三趟到第 9 级为止逐字相同（`stair.8`、`stair.8.waited`、`stair.9`、
`stair.9.waited` 全等），而 `stair.9` 的落点 y=56 **已经等于 `targetY`——楼梯此刻已经到底**。

- **s3/s6**：静置里身体掉到 y=56 ⇒ `:510` 的 `body.getY() <= targetY` 成立 ⇒ 退出，`bottom = -9,56,31`，11 级。
- **s5**：身体仍在 y=57 ⇒ 不成立 ⇒ 再切一级。`courseFrom` 给的最深级 `-9,56,31` 的 y ≤ targetY，
  于是 `:529` 的具名例外把身体的格子还回来，切出 `-9,57,31 → -9,56,32`，`bottom = -9,56,32`，12 级。

同一个竞态**在第 9 级入口先响过一次却没有后果**（s3/s6 有 `stair.9.fromStep`、s5 没有，两条路切出同一级）
——**锚是好的，没被保护的是退出**。竞态是扳机；能改变结果是因为**退出判据问「身体」、锚问「楼梯」**，
两个主语。

### 但这一刀不切，理由是反转

多切的那一级 `-9,57,31 → -9,56,32` **不加深度**（在 y=56 横着走），只把 `stairs.bottom` 沿 z 挪一格。
而**「正确」的提前退出（s3/s6，`-9,56,31`）是 0/2，越界的那趟（s1/s2/s4/s5，`-9,56,32`）是 3/4**。
把 `:510` 改成按楼梯停 = **把模腔钉死在输的座位上**。病灶在底座下游，不在退出判据。南臂这条线挂起。

（另有两条已付过学费的约束，任何后来者动 `:510` 前必须先读：`stairBottom` 必须是身体的格
（`:504-509`，实测两次）；「等身体掉下去」已被证伪（`:521-527`，烧光 40 000 tick 且没产出 `stairs.bottom`）。）

### 📌 通则（这一刀是靠它免掉的）

> **在决定切哪里之前，先问：被我判为错误的那个行为，是不是正好落在赢的那一侧。**
> 一个越界如果和好结果 3/4 同现，它多半在补偿另一处缺陷；直接改掉它会暴露那处缺陷，
> 而暴露出来的样子看起来像是「修复引入了回归」。

同族先例：`一个会自己重新落座的模腔`、`修复会暴露搭便车的人`。

---

## ⬜ 接手点 —— 第 12 级 cast8 的起手由**最后一段的 1.5 格到达容差**决定；`-PlandOnFloor` 能钉住它（但有副作用），(b′) 已开火一次、那次的绿是侥幸

**(b′) 已实现，等级 `compiled`**（`JourneyPour.footBeforeTower/footingAbove`，塔起步前把身体从被淹的地板排挪走；
`JourneyRamp.walkTo/floorOf` 只改了可见性）。**e9 PASS 11828t 一步没跑到它**：`footing`/`footedOn` 一条没有，
cast8 与 e7 逐字相同。走的是设计好的 no-op 分支，**所以这一刀两个方向都没被检验**。四条「必须不变」全保住。

### 📖 读法（先读这条，它改写下面所有 57/56 的对照）：**`returnedY` 必须连 `landing` 一起读**

`returnedY` 只打 `blockPosition().getY()`，而**取整会把「还在往下掉」记成「到了」**。两趟里各抓到一次：

```
forge.landing = 精确 2.66/56.92/19.49，onGround=false，inWater=false；楼梯底 2,56,19=air（无流体）
              ← 空气柱、没落地、y=56.92 取整成 56 —— A/B 两趟逐字相同，是个干净的对照
cast9.landing = 精确 2.70/56.99/19.39，onGround=false，inWater=true（B 趟）
```

⚠️ **`landing` 是 2026-08-17 才加的，所以 e1–e10 那些 `returnedY=57/56` 的对照里都带着这个未知量**
（哪几趟是站着、哪几趟是悬在半空取整成的，查不了——那些趟没有 `landing` 行）。
**不回头重判它们**，但**以后谁都不许拿改动之前的 `returnedY` 单独当硬证据**。

### 🧊 只记不查：**`onGround` 在这具假身体上不可靠**（别开新战线）

B 趟 `cast0..cast7.landing` 八行逐字同型：

```
cast0.landing = 精确 2.37/57.00/19.49，onGround=true，inWater=false；
                楼梯底 2,56,19=water（流动），其上 2,57,19=air（无流体）
```

**脚下那格是流动水、身体报 `onGround=true` 且 `inWater=false`** —— 一具站在「流动水面」上的身体。
与上面那条「`returnedY` 有时是从半空中读的」同族，也与归档里那条「**这具身体的 `onGround` 两个方向
都错过**」同族。**只记不查**：任何以 `onGround` 为前提的判据（塔的 READY、(b′) 的诊断行）都要知道
这个读数会两头骗人。

### ⛔️ 更正一条我自己上一轮写下的因果 —— 它是错的，别再引用

上一轮我断言：**「e7/e9 的绿踩着『塔占楼梯底级』——`2,56,19` 里有实心块才有 `onGround=true`」**。
**e7/e9 自己的行否掉了它。** 每一格都是同一副读数，两趟逐字：

```
castN.fromHere.3 = 2, 57, 19 …眼睛 y=58.62   → 身体 y=57.00
castN.picks.3    = …身体 2, 56, 19，眼睛 y=58.16 → 身体 y=56.54   （casts 0,1,3,4,5,6,7 全是这一对）
```

**`y=56.54` 的身体不可能站在 `2,56,19` 的实心块上**：那是一具正在水里下沉的身体，从 57.00 沉到 56.54。
⇒ 楼梯底级当时是**开着**的，e7/e9 的绿**没有**搭那条冻结耦合的便车。原判据取自 `fromHere` 那一瞬的
`y=57.00` 就下了结论，**同一格在两 tick 内的两个高度，我只读了前一个**。

### ~~曾以为的分岔：`walkTheFlight(down)` 结束那一刻楼梯底那一柱还是不是水~~ —— **已被 v1 的 A 趟证伪，见下**

这一节只留作它当时依据的那张表，**结论作废**：`-PdryAlcove` 把水清了，`returnedY` 一格没动。

| | drain.6 / drain.7 | cast6/7/8.returnedY | cast8#1.fromY |
|---|---|---|---|
| e7 / e9 | `仍有流体：2, 56, 21 = water（流动）` | **57 / 57 / 57** | 57 → CONSUME |
| e8 | `壁龛已排干` / `2, 56, 17 = water` | 57(0–6) → **56 / 56** | 56 → FAIL |

**e8 自己就是配对对照**：同一趟里 casts 0–6 泡在水里回到 `2,57,19`，第 6 格之后彻底排干，
casts 7–8 就落到 `2,56,19`。⇒ **水还在 → 身体浮在头顶格（57）；排干了 → 落到地板排（56）**，
这一排正是 (b′) 要撞的起手。不是掷硬币，是「这一格的 `waterN` 有没有从模腔漏进楼梯底那一柱」。

### 已有的杆子够不够：**不够，一根都不对**（只读代码，未起任何运行）

- `-PwetShaft=true`（`JourneyShaft.floodTheColumnOnce`，`JourneyShaft.java:645`）——**在第 12 级根本不会触发**。
  它只从 `descendByMining` 里调，而那个方法的调用者只有 `WorldDriverJourneyScenes:938/1486/1957/2154`
  （石/矿/砂砾/黑曜石四级）；第 12 级下到模腔走的是 `digStairsDown`+`walkTheFlight`。它钉的下游量是
  `shaft.sabotage` / `shaft.reColumn.1`，不是 `returnedY`。
- `-PbreakAStair=true`（`JourneyStairs.aboutToWalk:351`）——拆一级让自检必须发现，钉的是审计/修补，不是水位。
- `-PforgeAway` / `-PshaftColumn` / `-Pbuckets`——钉朝向、井柱座位、趟数；**都不碰壁龛水位**。

### ❌ v1 `-PdryAlcove=true` —— **干净的阴性结果：杆子开火了，下游量没动**（A 趟 PASS 11596t）

```
staging.calls = 24（基线 13）   forge/cast0..9.dryLanding 每一格都有   ← 杆子确实开火
cast0..cast8.returnedY = 57    cast9 = 56   forge = 56               ← 要影响的量没动
```

**记 v1 未钉住，不是「部分生效」。** 这一趟的价值在于它 11 分钟证伪了我们据以行动的机制模型。

#### 被证伪的不止 v1，还有「水浮着身体」这个模型本身

`dryLanding` 的自陈行把它按死了 —— **casts 0–7 每一格只找到 1 格水**：

```
cast0..7.dryLanding = 1 格抽干了：[2,56,19]        ← 2,57,19 / 2,58,19 清之前就是干的
cast8.dryLanding    = 3 格抽干了：[2,56,19][2,57,19][2,58,19]
cast9.dryLanding    = 2 格抽干了：[2,56,19][2,57,19]   → returnedY=56
forge.dryLanding    = 楼梯底那三格本来就没水            → returnedY=56
```

**身体不可能浮在一格空气里**：那八格里 `2,57,19` 清之前已经是干的，身体却照样报在 `2,57,19`。
⇒「淹着→浮到 57」在那八格里根本不成立，与回灌无关。而且相关性两头都不对：forge 一格没清得 56，
cast9 清了两格也得 56，cast0–8 清了 1–3 格全是 57。

**回灌本身是真的，但它不是原因**：`cast8#1.climb.0=2,57,19 above=water water=true` 是在把那三格设成
air 之后几百 tick 读的，`drain.6/7/9 = 3,56,19 = water` 证明一格之外就有活水体。**回灌解释了「清了不算数」，
解释不了「57 还是 56」——因为它要维护的那个模型本身是假的。**

#### 真正的机制：**最后一段是「到达」判出来的，1.5 格的容差把两种结局判成同一种**

`stairRoute(down)` 的最后一个航点就是 `stairBottom = 2,56,19`（`JourneyPortalRung.stairRoute`），
`walkTheStairs` 用 `Goal.Block(want)` 走它，然后：

```java
private static final double LEG_ARRIVED = 1.5;      // JourneyPortalRung.java:251
if (off > LEG_ARRIVED && flightShortfall == null) … // :238
```

身体停在 `2,57,19` 距目标**正好 1.0 格 ≤ 1.5**，⇒ **算到达，一行 `returnStopped` 都不印**
（A 趟全文 `returnStopped` 出现 **0** 次）。**「最后一级迈没迈下去」这件事，日志里根本没有区分过。**
⇒ 与本仓库两笔旧账同形：「ARRIVED 不是在目标上」、「`walkToColumn` 判到达用的是自己的 `ARRIVED_WITHIN`
而不是要的半径」（后者的原话就抄在 `JourneyPour.raiseTo` 的注释里）。**这一族第三次。**

### 🔴 关于**被测对象**的发现（不是关于夹具的）：真梯上也在抛这枚硬币，而且同样不说

**`LEG_ARRIVED = 1.5` 造成的「停在 57 还是 56」在真梯上一模一样地存在。** 走那一段的是同一份
`walkTheStairs`，最后一个航点同样是 `stairBottom`，停在它上面一格同样正好 1.0 ≤ 1.5、同样算到达、
同样不印 `returnStopped`。**真梯每爬一趟都在抛这枚硬币，日志里同样看不见。**

⚠️ **`-PlandOnFloor` 没有修掉它，只是把输的那一面在排练里钉住以便复现。** 别把这一条读成已经解决：
- 它解释了第 12 级为什么「三趟里有一趟莫名其妙地红」，也解释了此前所有把这归因于水位/运气的判据；
- 它还会影响**任何**用 `walkTheStairs` 的地方，不止 cast8 的抬升起手。

**要不要收紧 `LEG_ARRIVED`：先不动。** 那是共享走路代码，改它会打到所有调用方（与「不许改
`TowerProcess`」同一条理由）。**先用 v2 把故障稳定复现出来，再谈怎么修**——顺序反了就又是一次
「在没有可复现故障的情况下改共享代码」。

#### ✅ v2 `-PlandOnFloor=true` 已实现（等级 `compiled`；A/B/A2/A3 已跑，定案见下）

- **做什么**（`JourneyPortalRung.landOnFloor`）：`walkTheFlight(down)` 回来之后、读 `returnedY` 之前，
  若身体不在 `stairBottom`，补一条 `Goal.Block(stairBottom)` + `NoBreak` 的短腿（`FLOOR_LEG_TICKS=200`）
  再落定，然后才读。
- **为什么不会被同一个机制吃掉**：v2 **一格方块、一格流体都不动**，回灌无从下口——它压根不跟水较劲，
  只是把已经要求过的那一步走完。
- **改名 `-PdryAlcove` → `-PlandOnFloor`**：v2 不再抽干任何东西，留着旧名字就是「名实不符的守卫」，
  本仓库已经为这条吃过亏。**v1 的数据只属于 `-PdryAlcove`，v2 的只属于 `-PlandOnFloor`，不混用。**
- **同时补上 v1 缺的那行诊断**（零行为）：读 `returnedY` 时连精确 x/y/z、`onGround`、`isInWater`、
  以及 `stairBottom` 与其 `above()` 的方块/流体态一起打。**有这行，第一趟就能定位，不用三轮水模型。**
- **走不下去要出声**：补腿之后仍不在 `stairBottom`，必须自陈（这正是「那一格里到底是什么」的答案）。
- **两把锁与账本照 v1 不变**；A/B 判据**一字不放宽**：开着跑**每一格** `returnedY=56`＋`cast8#1.fromY=56`
  ＋`footing`/`footedOn` 出现且 `cast8#1.*` 整组消失；关着跑必须仍是 57/56 的自然分布。

#### 🔶 A/B 定案：**杆子有效（钉的是「排」），复现 2/3，且已知有副作用**——三句话必须并排读

| | A `-PlandOnFloor=true`（`landon`, FAIL 7243t） | B 关（`landoff`, PASS 11605t） |
|---|---|---|
| `returnedY` | `forge`+`cast0..6` **全 56** | `cast0..cast8=57`，`cast9`/`forge`=56（**自然分布，与 e7–e10 同型**） |
| `floorLeg` 行 | 每一格都有 | **0 行** |
| `staging.calls` | 24 | **13（基线）** |

⇒ **A/B 这一对成立**：开着每格钉到地板排，关着回到 57 为主，**且关着那趟一行 `floorLeg` 都没有**
（`staging.calls` 也回到基线 13）。**杆子没有偷改基线**——这正是判据第二条要堵的陷阱，它堵住了。

⇒ **但作为复现工具 2/3**（A2、A3 都走到了 cast8，A1 没有），**且已判定有副作用**（岩浆站取料 2/2 对 0/21，见「🔁 重判」）。A1 那趟在走到 cast8 之前就死了，所以
`cast8#1.fromY` / `footing` / `cast8#1.*` 三条**一条都没被检验**，(b′) 仍然两个方向都没走过。
**「钉子有效」与「还没复现出故障」是两件事，不许只写前一句。** 分子分母等 A2 落地再更新。

#### A2（`landon2-results.jsonl`，FAIL 14896t，死在 cast9 取岩浆）：**(b′) 第一次开火，绿是侥幸**

**复现计数：4/5 次可用**（A2/A3/A4/A5 都走到了 cast8；A1 没有）。**(b′) 三段式，别压成一句**：

> **① 已开火**（`cast8.footing` 出现）　**② 结果 CONSUME**　**③ 机制不是 (b′)**

**逐行追下来，浇成 cast8 的不是 (b′)：**

```
cast8.footing   = 2,56,19 → 3,59,18（选中的落脚格在 wantY=59，脚下 cobblestone）
cast8.footedOn  = 3,60,17（没走到：高了一排、脚下是 water、onGround=false）
                  ⇒ 我的 `now.getY() >= wantY` 提前返回 → 抬升这一段的塔被跳过
cast8.raisedY   = 60/59（停在 3,17…比要站的排高 1 排 —— 从这里打出去的不是验过的那条）
cast8.stand.3   = 3, 56, 20        ← 浇筑自己重选落脚点，把身体又带回**地板排**，抬升整个作废
cast8.picks.3/2 = 落进 4,59,19     ← 低一排，射线闸两次正确拒绝
cast8.lift#3    = 塔从 3,56,20 起步：washedOff ×1、driftKept ×2、afloat ×8、walkerFallback=True
                  endedIn=0,19（起塔柱 2,21）→ liftedY=59/59
cast8.picks.1   = 身体 3,57,20，眼睛 3.03/59.07/20.67（⇒ 身体 y=57.45，泡在水里）→ 落进 4,60,19 ✅
```

⇒ **成功那一枪是从「既不是验过的柱、也不是验过的排」的一个半浮位置打出去的**，`raisedY` 自己就写着
这句话。**这是侥幸的绿，不是 (b′) 的绿**；真正守住底线的是 `.picks` 射线闸（它拒了前两枪）。
**一个侥幸的绿比一个诚实的红贵** —— 照实记，不许当作 (b′) 生效的证据。

##### ⛔️ 两处读错要更正（其中一处是我自己的）

1. **「塔整组没跑」不对。** 没跑的只是**抬升那一次**（`cast8#N` 确实 0 行）；塔在
   **`cast8.lift#3`** 下跑了，而且把 (b′) 本要防的那一族**原样跑了一遍**：`washedOff`、`driftKept` ×2、
   `afloat ×8`、最后靠 `walkerFallback` 才上去。
2. **因此预测 ② 没有兑现。** 我写的是「`washedOff` 应当消失」——它没有消失，**只是换了个 tag**
   （`cast8#1.*` → `cast8.lift#3.*`）。预测 ③ 只在字面上成立（那次爬升不带 pin，所以打的是
   `driftKept` 而非 `driftKeptPinned`），**现象仍在**。⇒ ②❌ ③⚠️字面兑现，**不记成兑现**。

##### ✅ 补洞已实现：闸门从「够高」改成「够高**且**站得住」（预测写在前面，A4/A5 未跑）

**改了什么（一个变量）**：`JourneyPour.footBeforeTower` 的提前返回。「站得住」**不用 `onGround` 判**
（这具身体在流动水面上也报 `true`，见本节顶部🧊），改用世界态：
`JourneyShaft.supportUnder(rig, now)`（含身体足迹四角的回退）指到的那格 `blocksMotion()`，
**且**身体所在格无流体。`onGround` 只打进诊断行**作参考**。`footedOn` 现在分开回答**两问**。

**预测（写在前面，事后不许调）**：

1. `cast8.footedOn` 会把 A2/A3 那个逐字复现的落点 `3,60,17` 判成 **「够高 ✅ / 站不住 ❌」**
   （脚下 `3,59,17` 不实心、身体那格是水）。
2. **`cast8#N`（抬升那次塔）会重新出现**，不再是 0 行 —— **这是这一刀的代价，不是回归**。
3. ⚠️ **本刀不预测 cast8 变绿，也不预测行为改变。** 在 A2/A3 那副几何里身体已在 y=60 ≥ wantY=59，
   `ascendByTowering` 第一行就 `at.getY() >= surfaceY` 直接返回 ⇒ 塔进去等于没做事，
   `raisedY` 仍是 `60/59`。**这一刀买到的是「不再把站不住的落点当成抬升成功」，仅此而已。**
   谁要是看到 cast8 又绿了就说这刀成了，那是把侥幸算到刀头上——A2/A3 的绿本来就是侥幸。
4. 唯一真正改变行为的情形是 **`够高 && 站不住`**（以前被判成功、现在交给塔）；
   `不够高` 与 `够高且站得住` 两支**逐字不变**。
5. **必须不变**：`returnedY` 全 56、`stairs.bottom=2,56,19`、`forge.face=4,56,19`、
   `stair.1≠stair.0`、`carve.firstStuck=2,62,17`；`cast8.footing` 仍应选 `3,59,18`（选点逻辑没动）。

##### ✅ A4（`landon4-results.jsonl`，PASS 17100t）：**五条预测全中，含三条「不会变」**

| 预测 | 结果 |
|---|---|
| 1 `footedOn` 判「够高 ✅ / 站不住 ❌」 | ✅ `2,60,17 … 站不住（脚下 2,59,17=air，不实心；身体这一格 无流体）` |
| 2 `cast8#N` 重新出现 | ✅ 0 行 → 8 行（`cast8#3`） |
| 3 行为不变、`raisedY` 仍 `60/59` | ✅ `cast8#3.rise=0 block(s)`、`gained=0/0` —— 塔进去等于没做事，如预告 |
| 5 五个常数不变、`footing` 仍选 `3,59,18` | ✅ 全中 |

⚠️ **`cast8.result=CONSUME` 不计入这一刀**：判据是 `raisedY` 报「同一柱、同一排」，它没报。

**分项自陈第一趟就还本**：这次「站不住」的成因是**脚下空气**，A2/A3 是**脚下水**。
⇒ **「站不住」有两种成因，下一刀的判据不能只写一种。**

##### ❓两问的答案（只读三趟归档，未跑新的）

**Q1 —— 是「走不到」，不是「选错了」，但两者都不是全部真相。**

- **`3,59,18` 是个货真价实的落脚格**：`wet.8.ramp.flight` 把 `3,58,18` 垫成了实心（`footing` 行也自陈
  「脚下是 cobblestone」），是壁龛格，选中时脚/头都空。**选点没错。**
- **身体确实走了**：从 `2,56,19`（56 排）到 60 排，**升了四排、横移 2–3 格**。⇒ 有路、也走了，
  不是「没路」也不是「一格没挪」。
- **它停在离目标 1.41–1.73 格处，三趟都高一排**（`3,60,17`／`3,60,17`／`2,60,17`），
  **三趟 `onGround=false`、脚下都不是实心**（水、水、空气）。⇒ **判读那一刻身体根本没站在任何地方。**

**⇒ 真正的答案：这一腿判读的是一具还在空中/水里的身体。** 证据是 `JourneyRamp.walkTo` 收尾那句
`rig.settle(new HoldStill(10), 30, then)`：`JourneyRig.settle` 在 `d.finished() || waited>=ticks`
时返回，而 `HoldStill(10)` **10 tick 就 finished**——不是 30。而 `HoldStill` 自己的 javadoc 里存着
一条同一壁龛的实测：`recover8.ask.settled = 等了 10 tick 身体还在动（眼睛 y 61.65→58.06，共挪了
3.60 格）`。**一次 10 tick 的 settle 里身体掉了 3.6 格。** 从 60 排掉到 56 排需要约 18 tick，
**10 tick 看不到落地**。水（A2/A3）与空气（A4）两种成因，在这里是**同一个机制的两副面孔**。

⛔️ **而且「settle 久一点」是这一级的归档阴性结果**（`HoldStill` javadoc：试过、量过、已回退——
流动水里「静止」这个状态不存在，且多等的 tick 自己就是个搬运工，`recover8` 那趟因此掉到地板排、
走回去时还挖掉了一格门框）。**下一刀不许往这个方向修。**

**Q2 —— 是同一件事，但要说准：那是同一次测量被打印了两遍，不是两处故障。**

`cast8#3.rise=0 block(s)`、`gained=0/0`、`toY=fromY=60` ⇒ 塔一格没动身体。所以
`footedOn` 的 `2,60,17` 与 `raisedY` 的「停在 2,17…比要站的排高 1 排」**是同一具身体、同一个位置**。
⇒ 修好这一腿的落点，两行会一起变；**但那是「一处修好、两行同变」，不是「一刀解决两处」**，
别记成后者。（旁证：基线 e7/e8/e9 的 `raisedY` 里**没有**「高一排」这句——它是这一腿带来的。）

##### ✅ 已实现：**加了两条读数，一处没修**（等级 `compiled`，未跑）

理由：三个候选解释（走者按容差提前判到达／浮起来／还在下落）**从归档分不开**，而
`JourneyRamp.walkTo` **一行都不打**——这一级今天已经三次栽在「一条什么都不报的腿」上
（`LEG_ARRIVED`、`returnedY`、这次）。**在读数到位前修，就是猜。**

**两个新键，都在 `footBeforeTower` 判读的那一刻打；`walkTo` 一行没改，settle 一个没加。**

| 键 | 说什么 |
|---|---|
| `cast8.footWalk` | 起点 → 终点、离要的落脚格差几格、**挪了几格还是一格没挪** |
| `cast8.wouldLandOn` | **这具身体若下落会停在哪**（`JourneyPour.wouldLandOn`：足迹四角各往下扫 12 格取最高支撑） |

**判读规则（先写死，不许事后调）——这一趟就看 `wouldLandOn` 这一行：**

- `wouldLandOn = 3,59,18`（正是要的落脚格）⇒ **病在判读的时机**，修法是把判读点后移到真正用的时候；
- `wouldLandOn = 地板排或别处` ⇒ **病在这一腿的终点**，修法是换目标或换走法；
- `wouldLandOn = 找不到支撑` ⇒ 第三种，另议。

**两者修法相反**，所以这一趟只读不修。`footWalk` 是佐证：它把「没走成」与「走了但停偏了」分开。

**通则（已写进 `wouldLandOn` 的 javadoc，不只写这次的用法）**：
> **能用一个不改变系统的查询回答的事实，就不要用一次会改变系统的实验去问。**
> 这里的实验是「等它落地再看」，而多等的 tick 自己就是个搬运工——`HoldStill` 的归档阴性结果
> （`recover8` 因此掉到地板排、走回去还挖掉一格门框）已经把那条路封死。重力是确定的、地板已经在
> 世界里，所以落点可以算，不必等。

- 判据仍不放宽：`cast8` 是否由 (b′) 浇成，看 `raisedY` 是否报**同一柱同一排**，不看 `result`。

##### ✅ A5（`landon5-results.jsonl`，PASS 16294t）：**诊断成功，修复未做**

新读数一趟定案，且**判因规则事先就印在那一行里**：

```
cast8.footWalk    = 2,56,19 → 3,60,17（要的落脚格 3,59,18，差 1.41 格；挪了 4.58 格）
cast8.wouldLandOn = 3,58,17（不是要的落脚格 —— 病在这一腿的终点，不在判读的时机）
cast8.footedOn    = 够高：够（60≥59）；站得住：站不住（脚下 3,59,17=water）
cast8.raisedY     = 60/59（仍不是同一柱同一排 ⇒ 照旧不计入这一刀）
```

**通则**：`挪了 4.58 格` 排除了「一格没挪」那一支，主判据才有效。⇒ **佐证键要和主判据一起加。**
**通则二**：**把判因规则写进读数本身**，别写在文档里等人来对——这一行自己印出了结论。

##### ❗Q1 的答案：**两个选项都不对**，真因是第三个

**① 不是容差放过——`1.41` 不是任何常数。** 这条腿走的是 `JourneyRamp.walkTo` →
`IntentProcess(Goal.Block(spot))`，而 `Goal.Block.reached(p)` 就是 **`p.equals(target)`**
（`common/src/main/java/net/magicterra/worlddriver/bot/Goal.java:83`）——**精确到格，没有容差**。
`1.41 = √2`，是 `dy=1, dz=1` 的几何，不是常数。`LEG_ARRIVED=1.5` 属于 `walkTheStairs`、
`ARRIVED_WITHIN=5` 属于 `JourneyNetherRungs`，**两个都不在这条腿的路径上**。⇒ 否决「收紧到达判据」。

**② 不是几何进不去。** `3,59,18` 正是 `wet.8.ramp` 自己那段楼梯的**第 3 级**：
`wet.8.ramp.flight = 2,56,17 → 3,57,17 → 3,58,18 → 3,59,19`（这些是垫脚）⇒ 落脚格是
**`2,57,17 → 3,58,17 → 3,59,18 → 3,60,19`**。它脚下 `3,58,18` 是这段楼梯自己垫的圆石。
⇒ 否决「选点时加走得进去」——那会把一个合法的、刚垫好的台阶排除掉。

**③ 真因：身体从楼梯上浮走了。** `wouldLandOn = 3,58,17` **正是这段楼梯的第 2 级**；身体停在
`3,60,17`，就在它**正上方两排**。而 `3,59,17`（第 2 级的头顶格）**是水**。
⇒ 身体走上了第 2 级，本该横跨一步到第 3 级 `3,59,18`（dz=+1、dy=+1，一个普通台阶），
**却被浮力抬着直上两排、浮出了楼梯**，停在水面之上的干格 `3,60,17`（`onGround=false`）。
A4 的 `2,60,17`（脚下空气）是同一件事的另一副面孔：那趟浮起来之后水退了，身体在下落。

⚠️ **这就是 `washedOff`／`afloat` 那一族——(b′) 本来要躲开的那一族。**

##### 🔴 由此推翻的是 (b′) 自己的前提，不只是它的实现

> **(b′) = 「先把身体抬到有实底的那一排」预设了这样一排存在且身体待得住。
> 实测：cast8 时整个壁龛是淹的，浮力会把身体从**任何**一级台阶上抬走。**

三趟落点（`3,60,17` / `3,60,17` / `2,60,17`）**全部不在任何一级台阶上**，全部 `onGround=false`。
⇒ **任何「把身体挪到别处再交给塔」的修法都继承同一个病**，因为病在水里，不在落点选择上。

##### ⬜ 待批：下一刀该换方向，且我否决了三个「顺手」的修法

- ❌ **收紧到达判据** —— 没有容差可收（证据见 ①）。
- ❌ **选点加可达性** —— 目标本来就合法（证据见 ②）。
- ❌ **按 `wouldLandOn` 改判**（我自己想到的第三个）：A5 里它会得出「站得住(3,58,17)、但不够高(58<59)」
  ⇒ 仍然 `tower.run()`，**与现在逐字同路**。**是个 no-op，不值得动。**
- ❌ **抽干／多等** —— 归档阴性结果各一条，早已封死。

⇒ **建议：停止在 (b′) 这条线上加码，把问题上移。** 唯一一次由机制（而非侥幸）浇成 cast8 的路径，
是**根本不抬升**——`fromHere` 就地短路。所以下一刀的候选是「**cast8 这一格为什么非抬不可**」，
即 `standLevelWith` 之前的顺序问题，而不是抬升本身。**这是个新方向，需要你先批。**

##### 🕳 (b′) 自己的洞（我的实现问题，不是环境问题）

`footBeforeTower` 用 `now.getY() >= wantY` 判「够高了就不用塔」，**只问了排，没问落脚**。A2 里它
因此接受了一个 `脚下是 water、onGround=false、还高一排` 的落点——**正是 (b′) 存在的理由所指的那种状态**。
下一版要把这个提前返回改成「够高**且**站得住」，否则 (b′) 会一直在自己要防的状态上宣告成功。
**A3 在跑，改动等它落地再做**（现在改，A3 的数据就不属于任何一棵树）。

#### A 趟结果（`landon-results.jsonl`，FAIL 7243t）：**判据只兑现第一条，其余三条未检验**

| 判据 | 结果 |
|---|---|
| 每一格 `returnedY=56` | ✅ `forge` + `cast0..cast6` **全 56**（基线以 57 为主）；`floorLeg` 每一格都出现 |
| `cast8#1.fromY=56` | ⬜ **未检验** |
| `footing`/`footedOn` 出现 | ⬜ **未检验** |
| `cast8#1.*` 整组消失 | ⬜ **未检验** |

⚠️ **`cast8#1` 行数=0 是因为 cast8 根本没跑**（run 死在第 7 格取岩浆），**不是因为塔被跳过**。
**记「未检验」，不许记成「预测兑现」**——这一级今天已经吃过一次「绿了但没走到病灶」。

#### 这根杆子钉住的是**排**，不是**格**——判据措辞按实际能钉的改写（**改在下一趟之前**）

`floorLegMissed` 出现 4 次（cast1/3/5/6），可 `returnedY` 仍读 56，两者并存的原因自陈行直接给了：

```
cast1.floorLegMissed = 3, 56, 19 补腿走完仍不在楼梯底 2, 56, 19 上 —— 精确 3.12/56.00/19.27
cast1.returnedY      = 56（楼梯底 y=56，身体 3, 56, 19）
```

**身体在对的排、错的格**（x=3 而非 2），而 `returnedY` 只读 `here.getY()`。⇒ 补腿把身体带下了那一级，
但常常带到隔壁一柱。**改写判据（理由：原措辞要求的「格」这根杆子做不到，而 (b′) 的闸问的本来就是排——
`footBeforeTower` 的门是 `here.getY() > floorY`，与身体在哪一柱无关）**：

- ✅ 新判据 1：**每一格 `returnedY = 壁龛地板排`（=56）**，不要求落在 `2,56,19` 这一格；
- ✅ 新判据 1b：`floorLeg` 每一格出现（杆子开火）；`floorLegMissed` 的次数如实记 —— 它是「钉排不钉格」
  的证据，**不是失败**；
- 其余三条（`fromY` / `footing` / `cast8#1.*`）**一字不动**。
- ⚠️ 遗留风险：落在 `3,56,19` 时那一格是不是水没读过，而 (b′) 的门还要求脚下格有流体。下一趟看
  `cast8.footing` 是否出现即知。

#### 新死法（第 7 格取岩浆）：**不是这根杆子造的病，但这一趟作为复现是一次失手**

```
lava7.stationSees = 0 格源块还够得着
lava7.aimsAt      = MISS   → miss.3 瞄 -11,63,18（现在是 lava）射线停在 MISS → retarget -11,63,16
                          → miss.2 → retarget -12,63,11 → miss.1 → 放弃
reason = 装不到 minecraft:lava_bucket …身边的源块：64 格
```

> ⚠️ **本节的判定已被 A2 推翻，见下面的「重判」。保留原文是因为它错在哪里值得看。**

**判定：不是杆子引起的。** 三条独立证据，都取自归档、不需要新跑：

1. **同族先例三笔，全在这根杆子之前**：`ctrl1` FAIL「装不到 **water_bucket**…身边的源块：**484 格**」、
   `e3` FAIL 与 `e6` FAIL（两趟逐字相同，「身边的源块：1 格」）。**旁边有 484 格源块照样装不上** ——
   这一族的病根是「瞄到了却装不上」，与源块多少无关，与壁龛更无关。属于本仓库已立案的射线/瞄准族
   （「一个 use 用的是手不是包」「瞄准存的是角度不是目标」「格心眼不是开火的那只眼」）。
2. **`lava*.miss` + `retarget` 在每一趟归档里都有**，包括全部通过的那些（e7=14、e9=14、dryon=14、
   e10=12 行）。**miss 与改瞄是常态**，这一趟只是三次改瞄的预算刚好用光。
3. **`stationSees=0` 从 lava2 起是所有趟的常态**（e7/e9/dryon 全是 0，然后照样一路 CONSUME 到
   lava8/lava9）。**致命的读数不是它。**

**但杆子确实重新掷了一次骰子，这一点要写明**：三份基线（e7/e9/dryon）在 lava8 之前逐字相同，
而 landon 从 **lava2 的计划格**开始分叉（基线 `瞄 -11,63,13`，landon `瞄 -10,63,19`）。装料点固定在
`-8,64,13`、上行终点固定在楼梯顶，两者都没被杆子碰过；变的是**每次做计划时湖自己的状态**——
岩浆会流、会补源，而两趟的节奏不同（landon 8 趟 7243t ≈ 905t/趟，e9 10 趟 11828t ≈ 1183t/趟）。
**「落 56 让每一格更快 → 湖来不及稳定 → miss 更多」是一个有数据支持的假说，不是已证的因果**，
证它要靠 B 趟与第二趟 A，不许现在当结论用。

⚠️ **作为复现工具，这一趟是失手**：它没有让被复现的那个故障（cast8 的起手）发生，run 在第 7 格
就死于另一件事。**0/1 次可用复现。** 判定「工具失败」还早（死因与它无关、且是概率性的：同族
FAIL 在 23 份归档里出现 3 次 ≈ 13%），但**在拿到一趟走到 cast8 的 A 之前，这根杆子不算能用**。

#### 🔁 重判（A2 之后）：**岩浆站取料这一族是杆子带出来的，2/2 对 0/21**——我上一轮的辩护是错的

把全部 23 份归档按**死因族**分类（不是按「像不像」），`装不到 minecraft:lava_bucket`（岩浆站）这一族：

| | 岩浆站装不上 | 水桶装不上 | 其它 |
|---|---|---|---|
| **没有杆子**（21 趟） | **0** | 3（`ctrl1` 装水@`-9,61,38`、`e3`/`e6` 装水@`5,59,19`） | 9 |
| **`-PlandOnFloor`**（5 趟） | **2**（A1、A2；A3/A4/A5 PASS，没死在这一族） | 0 | 0 |

**A3–A5 落地后的更新：2/5 对 0/21。** 我定的升级条件（「第三次也死在同一族」）**未触发**，所以
**不升级为「不能再用来判红」**；但 0/21 → 2/3 仍是强信号，**「确有副作用、机制未知」这一条保留**。

⛔️ **我上一轮说的「同族先例三笔、约 13%」是错的，而且错在偏袒自己的工具**：那三笔全是
**water_bucket**，分别在收水腿和站料腿；**岩浆站装不上在杆子之前一次都没出现过**。把两条不同的腿
并进一个「瞄准族」再拿它当辩护，是把分母做大——**这是我自己犯的、方向可疑的错，单独记下来**。

**同时，我给出的那个机制假说也被 A2 证伪**：「落 56 → 每格更快 → 湖来不及稳定」需要 A 比 B 快，
而两趟 A 分别落在 B 的两侧：**A1 ≈ 966 t/格（比 B 快），A2 ≈ 1568 t/格（比 B 慢），B ≈ 1160**。
⇒ **速度不是中介变量。** 现有证据只能说到这一步：

- **确有副作用**（0/21 → 2/2，任何小 p 下都不像巧合）；
- **机制未知**。A2 里能看到的相邻线索是 `cast8.returnStopped` / `returnStuck.3` / `stairsBroken` ——
  身体在回程掉进了自己舀空的岩浆坑、垒塔爬出来、还撞坏一级楼梯。**每趟多一条补腿 ⇒ 落点分布变了
  ⇒ 上行起点变了 ⇒ 计划哪一格源块变了**是可查的下一步，但**现在只是待查项，不是结论**。

⇒ **对工具可信度的判定（不护着自己的工具）**：`-PlandOnFloor` **不只是钉住起手，它还改变了别处**。
它作为「钉子」仍然有效（A/B 那一对成立），但**它不是一个中性的复现夹具**：拿它跑出来的红，必须先
排除岩浆站这一族，才能当作被复现的那个故障。**A3 没有死在这一族，升级条件未触发** —— 保持「确有副作用、机制未知」，不升级也不撤销。

#### 诊断行第一趟就还本了

`landingStory` 写来要区分的三个世界（站着／浮着／还没落地），**第一趟就抓到两个**：A 的 `forge` 是
「还没落地」，B 的 `cast0..7` 是「站在流动水面上」。两条都已提到本节顶部的📖读法与🧊只记不查里，
**因为它们改的是读法，不是这根杆子的账**。

<details><summary>v1 实现存档（`-PdryAlcove`，已判未钉住，**代码已被 v2 替换、不在树上**）</summary>

- **做什么**：`JourneyPortalRung.walkHome` 里、`walkTheFlight(down)` 之前调 `dryTheStairFoot`，把
  `stairBottom` 及其 `above()`／`above(2)`（`needsOpen` 为这一级护住的那三格）里的**流体**清成 air。
  没有水浮着，回程腿就落在地板排上。**只清流体、一块方块都不放**（`blocksMotion()` 的格子直接跳过），
  因此不可能变成「塔占楼梯底级」那条冻结耦合换个马甲。
- **两把锁**（`JourneyPortalRung.java:339-340`，与 `breakAStair`/`wetShaft` 逐字同形）：
  `Boolean.getBoolean("worlddriver.journey.dryAlcove")` 为假直接返回；`JourneyRehearsal.target()==null`
  直接返回 ⇒ **真梯上不是「没人用」而是「够不着」**。账本记在 `JourneyPortalRung.java:352`
  （`JourneyLedger.staged(...)`），`staging.calls` 因此必然 +N，没有哪趟能声称没布景。
  自陈行 `<tag>.dryLanding` 写明抽干了哪几格。
- **生效判据（验下游那个量，不是验参数被读到）**：开着跑，**每一格**都要出
  `cast*.returnedY=56（… 身体 2,56,19）`；`cast8#1.fromY=56`；有 (b′) 在树上则 `cast8.footing` /
  `cast8.footedOn` 出现而 `cast8#1.*` 整组消失。**关着跑必须还能看到 57/56 混着的自然分布**——
  这一对 A/B 才叫钉住，只看开着那趟等于没验（`-PforgeAway` 四个朝向、下游 `forge.away` 四趟全同的旧账）。
- **版本留痕**：v1 = 楼梯底三格，**已判未钉住（见上）**。预案里「加宽到 `forgeCorridor` 最底两排」
  **作废**：加宽只在「水浮着身体」为真时才有意义，而那个模型已被 `dryLanding` 的自陈行证伪，
  加宽只会买到同一个阴性结果外加更大的世界改动。

</details>

---

## ⬜ 上一轮接手点 —— 浇筑的落脚点是照「格心 + 猜的浮高」选的，那个眼睛没有身体有过；south 从确定性红变成能浇

**判据未跑完就交回：拆后树上 south 的分数见下表，别借旧树的趟次。**

**交回时正有一趟在跑**（拆后树的第 1 趟 south，`-PshaftColumn=-9,21`）。它的 bash 包装早已被回收，
**JVM 脱钩自己在跑**，所以没有退出码可收——判决只认产物：

```
fabric/run-rehearsal/stagewright-results.jsonl        # 第 12 级那一行（type!=suite 且 name 含 PortalLit）
python <scratchpad>/tally.py s1                        # 直接读出颜色/tick/各项比例，并快照 results 文件
```

⚠️ **`grep -c 'rehearse12PortalLit' results.jsonl` 会返回 1 也不代表跑完了** —— suite 那一行把所有
已注册场景名都列了一遍，子串匹配命中的是清单不是结果。**必须判 `type != "suite"`。**

### 归档读出来的账（没跑新的一趟就能读到）

south 的 `cast8` 从来没走上 east 那条通过的路。`standLevelWith` 的闸问 `standToPour(verifiedOnly)`，
它说「有落脚点」就**不垒台阶**——而它说的那个点是照 `(格心, 脚下+整块浮高, 格心)` 这只眼睛验的：

```
cast8.stand.3 = -10, 56, 32 瞄 -9, 60, 35（背板近面） 否决计数 {…}
cast8.picks.3 = -9, 58, 33 Block{minecraft:cobblestone} face=west → 落进 -10, 58, 33
                （…，身体 -10, 56, 32，眼睛 -9.15/57.98/32.57 …）
```

选它时假设的眼睛是 `-9.50/58.62/32.50`，真正开火的是 `-9.15/57.98/32.57`。这一浇是斜的
（`dx=1, dz=3`），所以**三分之一格的 x 就是整整一列的穿越差**。拿那趟自己记下的方块态逐格追：格心眼
走 `(-10,58,32) (-10,58,33) (-10,59,33) (-9,59,34) (-9,60,34) (-9,60,35)` 打到背板；把眼睛挪到
`x=0.8` 就走 `… (-9,58,33) (-9,59,33) …`——**和实测那只眼睛逐格相同**，`-9,58,33` 正是 `.picks` 停下
的那块圆石。**两次归档拒绝都能从归档本身复现，一趟没跑。**

### 改了什么（两处，同一个决策点；第二处是被第一处的测量逼出来的）

1. **落脚点分级，不再是「验过/没验过」**（`JourneySight.pourGrade`）：同一条 clip 从身体足迹的四个角
   再问一遍，格子里有流体时两个高度都问。`ANYWHERE` 才算验过；`CENTRE_ONLY` 仍可作为**走过去**的目标
   （花的是一次 approach），但**不是「要不要垒台阶」的答案**（花的是这一格）。轴对齐的浇筑天然不受影响。
2. **权威的射线要先问**（`standLevelWith`）：原来的顺序是 `placeFluid → mendBacking → standLevelWith`
   （预测能站哪）→ 才轮到 `aimThatLandsIn`（从身体**真实**的眼睛开火）。**权威的测试跑在它本该决定的
   那个决定之后。** 第 1 处改完立刻量到代价：`cast7` 本来靠 `fromHere` 就地浇成，改后却去垒台阶，而
   那座塔垒在楼梯自己那一柱上，填掉 `-9,57,32`（井底那级的头顶格）。

### ⛔️ 阴性结果 / 只记不碰

- **`needsOpen` 被它自己的后备路径绕过**：`JourneyRamp.fillable` 守这条规则，规则拒绝之后接手的
  `JourneyShaft.climbOut*`→`TowerProcess` 不认识楼梯，照填。通则已写在 `JourneyStairs.needsOpen`
  **规则自己身上**（不是写在这次的调用点——下一个绕过它的后备不会经过 `JourneyRamp`）。
  **冻结，只记不碰**：改后有一趟安静，但一趟安静不等于它没了。
- **四处仍用格心眼**：`JourneyPour.pourLandsFrom` / `scoopSeesFrom`、`JourneyFill:213,888`。
  同一个病、不同调用者。**冻结**——这一轮动了它们就无法归因。
- 边界照旧一字未动：11 级 `walkToTheLava` 走湖心那一柱；`Walker.footingGuard` 在 `sole=0.0000` 时也钉；
  walker 对「横 1 竖 1」`path-consumed` 在半格外；15 级深渊；`JourneyEndRungs.march` 第三处；
  16–20 级没有 staging 配方。

### 两条花钱买来的判别法（都属于「别让一次快照回答一个关于变化的问题」）

- **验一次拆分是不是纯搬迁**：把限定符还原、把搬走的块塞回原偏移，与父提交对 diff——纯搬迁会**逐字重建**
  旧文件，于是每一个还剩下的 hunk 你都必须叫得出名字。这次剩 7 个，全部有名有姓。**读 545 行的 diff
  分不出「搬了」和「搬了并且偷偷改了」，重建 diff 分得出**，代价是一个脚本。它还抓到了眼睛抓不到的：
  `standToPour` 那个返回 `PourSpot` 的重载在搬迁里留着 `private`，编译期就炸了。
- **判断一趟是「跑完了/还活着/根本没起来」**：`sleep` 前后比对日志**字节数**。一次 `ls` 里陈旧日志和
  活日志长得一模一样。判决只认**产物**（`stagewright-results.jsonl` 的第 12 级行 / 这趟日志的 verdict
  行）——**进程活着不等于有结果，进程没了也不等于没结果**。
- **后台 bash 包装活不过你这一回合**（它 spawn 的 JVM 会脱钩继续跑，退出码没人收）。跨回合等一个条件
  只能用 `Monitor` + until，且条件挂在产物上。这一轮栽了两次。

### 分数（**拆后树**，`JourneyPortalRung` 3000→2454 + `JourneyPour` 577，纯搬迁已验证）

| 判据 | 状态 |
|---|---|
| 1. south ≥3 PASS | ✅ **3 PASS / 5 landed** —— **属于「拆后、动 `pourLandsFrom` 之前」那棵树，已封存**（s1、s4、s5 绿；s2、s3 红且分属两族）。这一刀之后**作废重计**，明细留档不作数 |
| 2. east ×2 不回归 | ❌ **0 PASS / 2 landed，确定性红**（e1 11463t、e2 18897t **逐字相同**）→ 已据此动刀，见下 |
| 2. east ×2 不回归 | **未跑** —— 要求鲁棒性的闸对两臂都开火，不是只对 south |
| 3. `exactRow` 报比例带分母 | **未统计** |
| 4. 真实 ladder ×1 | **未跑**（判据 1、2 没开门，按交代不跑） |

### 拆后树逐趟（分母 = 已落地趟数，不是目标趟数）

| 趟 | 结果 | tick | casts | buildTo（同排同柱 / 同排 / 一块砖没垒就拒） | 死因族 |
|---|---|---|---|---|---|
| s1 | **PASS** | 12274 | 10/10 CONSUME | 2/6 / 2/6 / 1/6 | —（`frame.cast=10/10`、`portal.cells=6/6`、`stairsBroken` 无） |
| s2 | **FAIL** | 9651 | 8/8 CONSUME 后死在第 9 浇 | 2/6 / 2/6 / 2/6 | **走位/几何**：`浇不到指定格：想浇 -9, 60, 34（瞄 -9, 60, 35），射线会把流体放进 -10, 60, 33，身体在 -10, 58, 32` —— 身体矮一排且不在那一柱，射线闸正确拒绝 |
| s4 | **PASS** | 10533 | 10/10 CONSUME | 2/6 / 2/6 / 0/6 | —（`10/10`、`6/6`）。**但塔/楼梯耦合这一趟真的犯了**：`cast3.stairsBroken`，`stairs.audit=自检 22 次，修好 2 级，收工时 12 级都完好` —— 自检赶上了，所以是绿。**这正是「一趟安静不等于它没了」的反面证据：它没走，只是被兜住了** |
| s3 | **FAIL** | 12455 | 6/6 CONSUME 后死在回程 | 1/1 / 1/1 / 0/1 | **走路/路线**（与 cast8 那一格无关）：`走不回模腔：停在 -13, 62, 22 … 带着一桶岩浆停在半路` —— `身处 Block{minecraft:lava}，头顶 Block{minecraft:lava}`，**身体是站在岩浆里的**。这是回程走上湖面那一段，不是浇筑几何 |
| s5 | **PASS** | 10886 | 10/10 CONSUME | 3/7 / 3/7 / 2/7 | —（`10/10`、`6/6`）。`cast8` 照常起了一次 raise；`stairs.audit=自检 21 次，**修好 0 级**`，`stairsBroken` 无 |

### south 五趟的族分布（判据 1 达成：**3 PASS / 5 landed**）

```
s1 PASS 12274   s2 FAIL 9651（走位/几何）   s3 FAIL 12455（走路/路线）   s4 PASS 10533   s5 PASS 10886
```

- **两条红分属两个不同的族，没有多数族**：一条是 cast8 的浇筑几何（身体矮一排且不在验过的那一柱），
  一条是回程走上岩浆湖那一段（`身处 lava，头顶 lava`，与 cast8 无关）。**改之前 south 是确定性红在
  cast8，归档 5 趟同一条**；现在那堵墙没了，但这一级还没稳。
- **塔/楼梯耦合五趟里可见两趟**（s4 `cast3.stairsBroken` 修好 2 级后仍绿；拆前那趟没赶上、死在爬回去）。
  **记作「被后备吸收」，不是「消失了」** —— s5 的 `修好 0 级` 只说明那一趟没触发，不说明它不在。

**`exactRow` 比例（分母 = `JourneyRamp.buildTo` 调用次数，26 次 / 5 趟）**：

| 指标 | 五趟合计 |
|---|---|
| 落在对的排 **且** 对的柱 | **10/26 = 38%** |
| 落在对的排（不论柱） | **10/26 = 38%** |
| 一块砖没垒就拒 | **5/26 = 19%** |

⚠️ **两个数字相等不是笔误，是这一轮最有信息量的一行**：凡是爬到了那一排的，**无一例外**也在那一柱上。
损失全部发生在「根本没爬到那一排」，没有一次是「爬到了但站错柱」。**下一刀该切的是够不到的排，
不是选柱**。（上一轮那 27/61=44% 用的是另一套口径，分母与判据都不同，**不可直接与这三个数比**。）

### 这一刀（east 确定性红）：**选柱和选站位用同一把尺**——`pourLandsFrom` 也按足迹验

**动手前先写死预测，跑完照单核对；核不上就是碰巧绿，不算修对。**

**病灶（e1/e2 逐字相同，两趟即确定性，不需要分布）**：`raiseColumn` 用 `pourLandsFrom` 挑柱，那是
**格心眼**。最后一格 `4,60,20` 的目标与 `cast8` 刚铸的 `4,60,19` 相邻，而选中的柱 `2,19` 与 z=19 同排：

```
格心眼 (2.5, 60.62, 19.5)  → 过 x=4 时 z = 20.00 整（正压方块角，tie-break 偏进目标）→ 判「行」
真实眼 (2.70,60.62, 19.50) → 过 x=4 时 z = 19.96（落在 z=19 那格）→ .picks 停在 4,60,19 obsidian
```

⇒ **必须动四处格心眼中的这一处**（已向上明说，这是这一刀的一部分，不是夹带）：`pourLandsFrom` 改为
`JourneySight.pourGrade(...) == ANYWHERE`，与 `standToAimAt` 同一把尺。**柱是钉死的**
（`换柱等于换射线，不许改`），所以「只在格心成立」的柱一旦选中就把这一浇锁死——这正是钉柱与格心眼
组合起来的代价。**没有放宽射线闸，没有重试。**

**预测（east，`-PshaftColumn=-8,19`）**：

1. `cast9.raise` 的柱**从 `2,19` 变成 `2,20`**（`raiseColumn` 按距离排序，`2,19` 距 0、`2,20` 距 1；
   `2,19` 被降级后 `2,20` 胜出）。
2. `cast9.picks.N` **从 `4, 60, 19 obsidian face=west → 落进 3, 60, 19`
   变成 `5, 60, 20 dirt face=west → 落进 4, 60, 20`**（轴对齐：z 全程留在 20）。
3. ~~`cast9.stand.N` 的否决计数里**不再有** `射线停在 4, 60, 19 obsidian`~~ —— **这条预测的措辞本身是错的，
   已按 e5 更正**：正确说法是**「被选中的那个落脚点不再被 obsidian 否决」**。
   **否决计数是候选的统计，不是被选者的判决**：e4 的图恰好是 `{}`，e5 的图里 `射线停在 4,60,19
   obsidian=3` 照样在，但两趟都 `CONSUME`——因为活下来的是别的候选。
   （与 `clear3` 那个陷阱同一类：**一行清单不等于一条判决**。）
4. `cast9.result = CONSUME`、`frame.cast = 10/10`、`portal.cells = 6/6`。
5. **附带预测（若成立则是旁证）**：`cast9.ramp.noFlight` 那条**应当消失**。旧柱 `2,19` 的垫脚
   `2,58,19` 是下井楼梯的起跳格，所以修不出楼梯、只能交给塔（`cast9#2.climb.*`）；新柱 `2,20` 的垫脚
   `2,58,20` **不是楼梯格**（east 楼梯全在 z=19），因此应改走 `JourneyRamp` 而不是塔——
   **连带把塔垒进楼梯柱那条耦合也一起避开了**。
6. **不应变的**：`carve.firstStuck = 2, 62, 17`、`forge.carved = 66/67`（另一条线，未碰）；
   south 的浇筑行为不应出现新族的红。

**代价（已接受并封存）**：这一刀让 south 那棵树作废，下面那份 3 PASS / 5 landed 是**那棵树的已完成
测量**，封存备查，**不得拿去给新树凑数**。

#### 对账（e4 PASS 18336t，且复现的正是切前确定性失败的那副模腔）

e4 的 `stairs.bottom = 2,56,19`、`forge.face = 4,56,19`、`carve.firstStuck` 与 e1/e2 **逐字节相同**，
所以它检验的是同一副几何，不是 e3 那副搬了家的。

| 预测 | 结果 |
|---|---|
| 1 柱 `2,19`→`2,20` | ✅ `cast9#4.column = 2,20` |
| 2 `.picks` 改打背板 | ✅ `cast9.picks.3 = 5, 60, 20 dirt face=west → 落进 4, 60, 20`，与预测**逐字节相同** |
| 3 **被选中的落脚点**不再被 obsidian 否决 | ✅ e4 `cast9.stand.3 = 3, 58, 20 否决计数 {}`；e5 `stand = 3, 59, 20`，图里 `射线停在 4,60,19 obsidian=3` **仍在**却照样 `CONSUME`。**原措辞「那条否决消失」是错的**，见预测 3 的更正 |
| 4 `CONSUME` / `10/10` / `6/6` | ✅ |
| 6 `2,62,17`、`66/67` 不变 | ✅ |
| **5 ramp 取代塔** | ❌ **证伪** |

**第 5 条的证伪比那四条命中更有信息，PASS 的真实机制不是我预测的那个：**

```
cast9.ramp.laid          = 0/3 级垫好了（身体 2, 56, 20）
cast9#4.climb.3.washedOff= 水把身体冲下柱子了，还剩 6 次重试
cast9#4.climb.11.afloat  = 2, 56, 21 浮在水里，8 次都没落地
cast9#4.gained = 0/1     cast9#4.endedIn = 3,20（起塔柱是 2,21 —— 不是同一柱）
cast9.raisedY            = 58/59（停在 3,20，指定柱 2,20，不是同一柱，比要站的排矮 1 排）
```

**ramp 一级没垫上、塔被水冲掉、抬升既矮一排又落错柱。**这一格是靠 `cast9.fromHere.3` 那条**就地短路**
救回来的，而它救得回来，正因为新柱让射线沿 z=20 **轴对齐**。
⇒ **正确说法：「选柱对了，所以就地那一枪本来就能成」。⛔️ 不要记成「抬升终于把身体送对了地方」——
抬升这一趟是失败的。**这一刀修的是选柱，不是抬升。

**白捡的观察（另一条线，只记不碰）**：**壁龛里的水会把塔从柱子上冲下来**
（`washedOff` → `afloat` → `onGround=false`）。与 `TowerProcess` 的 READY 需要 `onGround`、
而水里 `onGround` 恒为 false 是同一件事。**冻结**。

#### east 切后是**双峰**，分岔点在 `stair.1`：**一级被重复挖了一遍**

**east 切后 = 2 PASS / 4 landed，但「四趟里绿了两趟」是坏读数。按座位分组才是真相：**

| 井底 | 模腔 | 结局 |
|---|---|---|
| `2,56,19` | `forge.face = 4,56,19`，`66/67` | **2/2 PASS**（e4 18336t、e5 15196t） |
| `3,56,19` | `forge.face = 5,56,19`，`63/67` | **2/2 FAIL**，死法逐字节相同（e3 10423t、e6 9534t） |

**⇒ 结局由井底落在哪一格决定，不是抽签。**「六趟里绿几趟」把两个不同的实验混在一个分母里；
**按座位分组的 2/2 与 2/2，信息量远大于总数**。这一条以后照此报。

**分岔点（逐键比 {e4,e5} 对 {e3,e6}，第一个分成两组的键）**：`stairs.asCut`（11 级 vs 12 级）
是结果，真正的分岔在 **`stair.1`**：

```
{e4,e5}  stair.1 = -7, 65, 19 → -6, 64, 19       ← 正常的下一级
         e4 stair.1.fromStep = 身体读作 -7, 66, 19，那是上一级 -7, 65, 19 的上方格（正落进去）
{e3,e6}  stair.1 = -8, 66, 19 → -7, 65, 19       ← 与 stair.0 **同一级，又挖了一遍**
         stair.1.waited = -8, 66, 19 还没迈下去
```

**机制**：`courseFrom` 只在身体位于最深那级**正上方**时才把这一级锚到台阶上。
{e4,e5} 的身体已经在 `-7,66,19`（正落进 `-7,65,19`），锚生效 → 下一级从台阶起算；
{e3,e6} 的身体**还停在 `-8,66,19` 一步没动**，与最深台阶不同柱 → 不锚 → 从原地重算 → **重复上一级**。
每重复一次，楼梯就多往东推一格，井底因此落到 `3,56,19`。
⇒ **这是一个真正的随机源：身体在下一级被测量时有没有开始迈下去，是 tick 时序的赛跑**，
不是被更早的某个输入决定的（`stairs.top`、`lava.landmark`、`shaft.standingOn`、`forge.surfaceY` 四趟全同）。

**为什么落 `3,56,19` 就收水必死**：e3 与 e6 的 `recover6` **逐字节相同**，连身体坐标都一样：

```
recover6.frameStuck.3 = 门框挡着 5, 59, 19，而且没有别的落脚点看得见它（身体 4, 58, 18，
                        验得过的就是脚下这一格）；否决计数 {}
recover6.miss.3       = 瞄 5, 59, 19 … 射线停在 5, 59, 18 Block{minecraft:obsidian}
```

`否决计数 {}` = 候选搜索**一个都没验过**，唯一座位就是脚下那格；而那一枪被**自己先前铸的
`5,59,18` 黑曜石**挡住。**这是那个座位的几何必然，不是两次都撞巧**（两趟逐字节相同即证）。

**判据裁定：一刀，切在 `stair.1` 的重复级上。** 分岔点找到了，且它解释了「为什么只有一个座位会死」
——座位是重复级推出来的，重复级没了，那个座位就不会出现。
⚠️ **但要写明残留**：收水在 `5,56,19` 那副座位上的脆弱性**并未被修掉，只是不再被走到**；
若将来因别的原因再落到那一格，`recover6` 仍会必死。**那是第二条线，别当作已解决。**

#### 第二刀：`courseFrom` 无条件化（e7 PASS 11843t 首验）

**改了什么**（一个变量，三处同源）：

1. `JourneyStairs.courseFrom` **去掉守卫**：`return cells.isEmpty() ? body : cells.get(cells.size()-1);`。
   原来的守卫「身体须在最深一级正上方」，其 `else` 正是 `return body` ——
   **锚要废掉的那个算法，就藏在锚自己的后备里**，所以缺陷没被移除，只是变成间歇性的，再以掷硬币的形式回来。
2. **最后一级的例外具名在调用点**（`anchored.getY() <= targetY ? body : anchored`），不搬回方法里。
   于是 `courseFrom` **一条静默分支都没有**。
3. `stair.N.fromStep` **改成报状态、不再断言**「正落进去」——无条件锚之后，它恰恰在被修的那种情形里
   为假；现在它自己说明是「同一柱、在上方 N 排」还是「还在别的柱上 —— 多半是还没迈下去」。

**通则（已写在 `JourneyStairs.courseFrom` 方法上）**：
> **一条守卫会静默失败的规则，会被它自己的后备绕过。**
> 与「`needsOpen` 被塔绕过」同形——那次后备是另一个类，这次后备藏在一个布尔里。

**⛔ 没有用「等身体落下来」修**（归档阴性结果：40000 tick 烧光、连 `stairs.bottom` 都没有）。
**这是竞态，但解法是不再问身体。**

**预测对账（e7）——四中三，第 4 条后半未命中，如实记：**

| 预测 | 结果 |
|---|---|
| 1 `stair.1 ≠ stair.0` | ✅ `stair.1 = -7,65,19 → -6,64,19` |
| 2 `fromStep` 在原本失守的情形也触发并自陈理由 | ✅ 逐字兑现 |
| 3 `.waited` 可以还在，但不得伴随重复切级 | ✅ `stair.1.waited = -6,65,19 还没迈下去`，无重复级 |
| 4a `stairs.bottom=2,56,19`、`forge.face=4,56,19` | ✅ |
| **4b `stairs.asCut` 稳定在 12** | ❌ **未命中：实际 11。我拿了 south 的级数去判 east。** |
| 5 `2,62,17`、`66/67` 不变 | ✅ |

⚠️ **两臂级数不同，别再互套常数**：**east = 11 级**（e1/e2/e7 皆报 `11 级都完好`）；
**south = 12 级**。预测的价值全在写在前面且事后不许调整——**这一条按未命中留档，不改成 11 再说命中**。

#### ⬜ 下一刀（**已定方向，未实现——我的上下文到此为止**）：塔起步那一排

**楼梯那一刀成立**：e7、e8 连续两趟 `stair.1 ≠ stair.0`、`stairs.bottom = 2,56,19`、`forge.face = 4,56,19`。
east 切后 **1 PASS / 2 landed，两趟同座位**。e8 的红是好座位上的**第三种死法**。

**e7 与 e8 的分岔点，只有一个键**：

```
e7  cast8#1.fromY = 57   climb.0 = 2,57,19 above=water onGround=TRUE  → rise 2 格，1 次 drift，CONSUME
e8  cast8#1.fromY = 56   climb.0 = 2,56,19 above=water onGround=FALSE → rise 3 格，3 次 washedOff，FAIL
```

**塔起步的那一排决定一切**：站在 y=57（脚下有实底）塔就垒得起来；落在 y=56（壁龛地板、泡在水里、
`onGround=false`）就被水一次次冲下来。**这正是本轮最早那次 east/south 对比里的同一个变量**
（east `2,57,19 onGround=true` 对 south `-10,56,32 onGround=false`）——**兜了一整圈回到同一处**。

**链条**：`ramp` 按 `needsOpen` 正确拒绝（`2,58,19` 是楼梯起跳格）→ 只能起塔 → 水冲掉三次 →
`driftKeptPinned` 两次改柱（`2,19`→`2,18`→`2,17`）→ 落脚偏轴两格 → 斜射 → 射线闸正确拒绝。
**每一环都"正确"，合起来是死的。**

**⇒ 选 (b')：塔不许从泡水的那一排起步——把起步点提到有实底的那一排再开始垒。** 理由：它是**唯一
的分岔变量**（e7/e8 只差这一个键），且它上游于其余所有环节；修它，`washedOff`／`drift` 改柱／偏轴
射线**整条链都不会发生**。

**否决掉的：**
- **(a) `driftKeptPinned` 不许改柱** —— 否决。它是症状不是病因：塔被冲下来之后**必须**去某处。
  禁止改柱只会把「偏轴的塔」换成「原地失败的塔」，这一格仍旧没有后路。
- **(b) 让塔别在水里垒** —— 方向对但**范围过大**：`TowerProcess` 是 bot 层公共代码，改它会波及
  所有用塔的地方，且无法归因。**(b') 是它的窄化版**：只改这一级**交给塔之前**站在哪一排。
- **(c) 给 ramp 别的支撑** —— 否决。ramp 拒绝的是 `needsOpen`，那是上一轮刚立起来的正确规则，
  **动它就是放宽不变量**；而且 east 的浇筑柱（z=19，射线轴对齐所需）与下井楼梯**同柱**，
  这是几何必然，不是选柱失误。

**⛔ 不许容忍**：不放宽射线闸、不加重试（`picks.1/2/3` 三次逐字相同，重试期望收益为零）。

**预测（写在前面）**：改完之后——
1. `cast8#1.climb.0` 的 `onGround` 应为 **true**，`fromY` 为 **57**（而非 56）；
2. `cast8#1.*.washedOff` **应当消失**；
3. `cast8#1.*.driftKeptPinned` **应当消失**（不再有柱可改，因为不再被冲下来）；
4. `cast8.raisedY` 应为 `59/59` **且同一柱 2,19**；
5. **必须不变**（这一刀不许撞坏上一刀）：`stair.1 ≠ stair.0`、`stairs.bottom = 2,56,19`、
   `forge.face = 4,56,19`、`carve.firstStuck = 2,62,17`。

#### 通则（**今天第三次**同一形状，单独立此一条）

> **在这套代码里，每一条不变量都要去找它的后备路径，因为后备往往不问那条不变量。**

三个实例，同一天：
1. `JourneyRamp.fillable` 守 `needsOpen` 拒绝那一格 → **拒绝之后接手的塔把它填了**；
2. `courseFrom` 的锚有守卫 → **守卫失败时的 `else` 正是锚要废除的算法**；
3. `cast8#1.column` 明写 `钉住：换柱等于换射线，不许改` → **`driftKeptPinned` 复述完这条不变量，
   然后改了柱**。

**读一条不变量时，先问"它失败的时候谁接手、接手的人问不问它"。**

#### 常规：这一级判修复**先看座位，再看颜色**

双峰下的通过率会把一个确定性机制报成抽签（east 曾是「四趟绿两趟」，真相是按座位 2/2 与 2/2）。
所以今后判这一级的修复，**先看这三条，它们一到两趟就能判**：

1. `stair.1 ≠ stair.0`（同一级不许被切两次）；
2. `stairs.bottom` 每趟同一格（east 应为 `2,56,19`）；
3. `forge.face` 每趟同一格（east 应为 `4,56,19`）。

**再看 `frame.cast` / `portal.cells` / 颜色。** 通过率要三趟以上还未必分得清双峰，座位判据两趟就够。

#### 一行自指的读数：`X 挡住 X` —— **是拼串取错，不是判据在拿一格和自己比**（已诊断，**先不修**）

e5 新出现：`stairs.audit = … 收工时 1/11 级坏了：2, 56, 19 挡住 2, 56, 19=Block{minecraft:cobblestone}`。

查到源头，`JourneyStairs.java`：`faults` 的第二个分支（**199–201 行**）在「台阶自己那格被堵」时构造
`new StairFault(step, **step**, false, …)` —— **主语与宾语按构造就是同一个 `BlockPos`**；
而 `describe()`（**163–167 行**）一律拼成 `step + " 挡住 " + cell`，于是这一支必然印出 `X 挡住 X`。

⇒ **判据是对的**：它问的是 `blocksMotion(step)`，即「这一级自己那格被占了」，是个有意义的问题，
**没有把一格和自己比**。**错的只是这一支的措辞**——`挡住` 这个说法预设了两个格子。
而且**这条 fault 是真的**：`2,56,19` 正是 east 楼梯最底一级，里面是圆石，
**与冻结中的「塔垒进楼梯柱」那条耦合同源**。

**先不改**（e6 在跑，改了这轮分布作废）。收口后一起改，建议：这一支单独措辞成
`X 这一级自己那格被 <block> 占了`，不要复用 `A 挡住 B`。

#### 切后这棵树的分数（east）

**east：2 PASS / 3 landed**（e4 18336t、e5 15196t 绿；e1/e2 是切前的，不计入）。
e5 的 `cast9` **没有 `#N` 后缀 —— 一次就成，没走重试**。
**e3 单独记为第三条线**：那一趟 `stairs.bottom = 3,56,19`、`forge.face = 5,56,19` —— **井底落偏一格，
整副模腔跟着搬家**，死在 `recover6`（`射线停在 5, 59, 18 obsidian`，走的是 `scoopSeesFrom`，未被这一刀
碰过）。**不要并进 cast9 这条线。**

#### ⬜ 待办：south 要在这棵新树上重跑

旧树那份 3 PASS / 5 landed **已封存、不可转移**。这一刀改的是 `pourLandsFrom`，south 的浇筑同样经过
`raiseColumn(pouring=true)`，所以 south 必须重新取分布。

### e1（east，FAIL 11463t）：**挡住浇线的不是水，是这一趟自己浇出来的黑曜石**

**⛔️ 「水截断浇线」这条假设被证据否掉了，别再往下推。** `reason` 说「射线会把流体放进 3, 60, 19」，
`clear3` 又列出 `3,59,20 / 3,60,20 / 3,61,20 = water(壁龛内)`——两行连起来读像是水挡的。**点名真凶的
是 `.picks`**：

```
cast9.picks.3 = 4, 60, 19 Block{minecraft:obsidian} face=west → 落进 3, 60, 19
                （想浇 4, 60, 20，瞄 5, 60, 20=dirt，身体 2, 59, 19，眼睛 2.70/60.62/19.50）
```

`4, 60, 19` 是**上一格 `cast8` 刚浇成的黑曜石**。桶的 clip 是 `Fluid.NONE`，**水对这条射线是透明的**，
所以那三格水根本不可能是阻挡者；`clear3` 那一行是「浇线上有什么」的清单，不是「什么挡住了」的判断。
`cast9.stand.3` 的否决计数也自己说了：`射线停在 4, 60, 19 obsidian=2`。
⇒ 属于**「斜射线蹭到自己刚铸的黑曜石」**那一族（`liftInPlace` javadoc 里记的
`picks=-8,58,38 obsidian → 落进 -9,58,38` 是同一回事），**与收水干净与否无关**：
`recover0..8` 全部 `CONSUME`，`drain.0..5` 全是「壁龛已排干」，而 `drain.6/7/8` 抱怨的残留是
`2,56,21` / `2,58,17`（地板层、流动水、会自己退），**和挡线的那三格不是同一批**。

**浇筑机构本身这一趟是好的**：`cast9.raise` 拿到 `raisedY = 59/59（同一柱）`，塔 `gained 2/2`，
`cast0..cast8` 九格全 `CONSUME`。死的是**最后一格的几何**：身体钉在 `2,19` 那一柱，目标却在 z=20，
斜着打过去正好穿过 z=19 那一格已铸的黑曜石。

**`66/67` 与归档是同一格，但已经不是死因。** `carve.firstStuck = 2, 62, 17=dirt：身体 -5, 63, 19，
距 7.3 格，canBreak=false` —— 与 TODO 里归档那几行**逐字相同**（连身体坐标和 7.3 格都一样，见本文件
「`carve.stuck` | `2, 62, 17` | 同一格」那张表）。所以这是一处**一直没解决、且确定性的几何**；
但归档那几趟是 8055t **死在 `cell.0`**，这一趟 11463t **活到了 cast9**。
⇒ **同一格、同一签名、不同死法：`66/67` 本身不致命，不要把它和这次的红并成一条。**

**e1 的 `exactRow`（分母 = 7 次 `buildTo`）**：落对排且对柱 **2/7**、落对排 **4/7**、一块砖没垒就拒 **2/7**。

⚠️ **这一组数推翻了我从 south 得出的那条概括。** south 五趟里「对排」与「对排且对柱」相等（10/26），
我据此写过「损失全部发生在够不到的排，没有一次是站错柱」——**east 不是这样：4 次够到了排，其中 2 次
落错柱。** 所以那条结论**只对 south 几何成立，不可推广**；「下一刀切够不到的排」这个方向**必须等
e2 落地、east 有了分布再定**，不能拿 south 的口径去切 east。

### 常规：长运行必须由主循环起，子代理只做分析

**`Monitor` 也活不过子代理的一个回合** —— 「挂了 Monitor」和「有人在看」不是一回事。这一轮为同一个
根因栽了三次：`run_in_background` 的 bash 包装被回收（它 spawn 的 JVM 脱钩继续跑，退出码没人收）、
第二次同样、第三次连 Monitor 一起没了，于是 s5 明明 17:46 就 PASS 了，我还在报「正在跑」。
**`run_in_background` 对主循环是跨回合的（进程退出时 harness 会唤醒它），对子代理不是。**
⇒ **一趟 10 分钟以上的 gate/rehearsal 由主循环起，子代理只读产物。**
识破那次的是 `tally.tsv` 记的 s4=**10533** tick 与结果文件里的 **10886** 对不上 —— **tick 数是趟次的
指纹，两个数对不上就是两趟，别用「颜色对得上」糊过去。**

### 一次并发污染警报，裁定为**未污染**（三趟全部有效）

一度有第二个 agent 被派进来，17:05（本地）用同一 run 目录起了同一套 south 几何，并在起之前
`Remove-Item` 了 `stagewright-results.jsonl`。**裁定不采信自述，只采信产物**：

- **服务器每次启动都会把 `latest.log` 轮转一次。17:00–17:20 之间只有一次轮转：17:08**，正是 s3 自己
  （s3 日志起于 17:08:23）。**17:05 没有任何轮转** —— 那一趟**从来没起来过**，最可能是撞上 gradle
  构建锁（s2 持锁到 17:07:54，s3 在 17:07:55 拿到）。它也确实没产出日志。
- **那次删除没伤到 s2**：s2 快照里 suite 行 + 全部 22 条场景行俱在。真的在半程被删掉，只会剩下大约
  一行、且没有 suite 行。Windows 不让你 unlink 一个服务器正打开的文件，所以那次删除多半直接失败了。
- **两份快照可证是我的**：`s2-results.jsonl` 时间戳 17:07（本地），**早于 17:08 才存在的 s3 服务器**。

⇒ **s2、s3 都判为「干净的红」，都计入分母。** `tally.tsv` 里那两条重复行（我记一次、它记一次，
数值逐字相同）已去重，现为 3 行。

**教训仍然成立，而且更该写死**：同一时刻只允许一条 gate 链。这次侥幸是因为 gradle 锁挡住了第二条，
**锁是运气不是设计** —— 它挡得住第二个 gradle，挡不住那次 `Remove-Item`。

**三趟三个不同的结局，这本身要读**：s1 全绿；s2 死在 cast8 的几何；s3 6 格之后死在回程、身体站在
岩浆里。改之前 south 是**确定性**红在 cast8（归档 5 趟同一条），现在那堵墙不在了——但也**还没稳**。
s3 那条像是回程走上湖面那一段，与「11 级 `walkToTheLava` 仍走湖心那一柱」是同一片水域，
**属于冻结清单里的边界，不要顺手去修**；先把污染问题排干净、拿到干净的分布再谈。
**教训（写下来别重犯）：同一时刻只允许一条 gate 链——判据是分布，而分布容不下一个共享的 run 目录。**

**拆前树上的三趟只作诊断，不计分**：run1 PASS（暴露 gate 太严导致 cast7 多垒）、run2 FAIL
（`走不上楼梯`，塔填了 `-9,57,32`）、run3 PASS 11300t —— run3 一次同时确认了双向判据：
`cast7` 无 `.raise` 且 `cast7.fromHere.3` 与改前归档逐字相同，`cast8.result=CONSUME`，
且**一条 `stairsBroken` 都没有**。

**等级：`backtested`。**两处改动都在运行里执行并兑现了各自的读数；**没有 live**。

## ⬜ 上一轮的接手点 —— 两条红都是「楼梯自己跟自己打架」：east **5/5 PASS**（判据 1 达成）；south 变成一处**确定性的新红**

**接手时那句「east 是自造自修的故障」是对的，但机制不是 `returnStuck` 垒的柱。**

### 归档读出来的账（没跑新的一趟就能读到）

`digStairsDown` 每一级都从 `blockPosition()` 量。挖开一级 = 把身体将要站的那格的地板拿掉，所以紧接着
那次读数常常是**身体在台阶正上方、正往下落**；从那儿量出来的一级高了一排，而**它落地后量的下一级**
就落进同一柱。交回的那两趟，逐字如此：

```
stair.3 = -8, 66, 19 → -6, 64, 19（一次挖两级）   ← 台阶挖在 y=64
stair.4 = -6, 65, 19 → -5, 64, 19                 ← 从它的头顶格量的，高一排
stair.5 = -6, 64, 19 → -5, 63, 19                 ← 和上面那级同一柱
```

**同柱两级的楼梯是一个没有任何世界状态能满足的矛盾**：`faults` 要求上面那级的脚下是实心、下面那级
自己那格是空的 —— 而它们是同一格。于是自检永远闭不了嘴，每一腿都拿一次 mend 去翻这一格：south 那趟
**自检 18 次修 16 级**，`cast*.stairsMend.0 = 垫上了` 和 `lava*.stairsMend.0 = 敲开了` 交替。
east 那趟就是被**垫上**杀死的：`cast6.returnStopped = 停在 -5, 64, 19 … 脚下 cobblestone` —— 身体
站在自己的「修复」刚扔进楼梯里的那块圆石上，前面是两格落差。**楼梯本身一直是好的**（x=-5 那一柱是
四格高的空气井，底下 `-5,62,19` 是原生岩），是 mend 把它堵死的。

**归档全量对账**：21 趟挖过楼梯的 FAIL 里，**只有这两趟带同柱两级**，其余 19 趟 `stairs.asCut` 都是
`N 级都完好`。

### 改了什么（三处，各自量过）

1. **一级从楼梯自己算起**（`JourneyStairs.courseFrom`）：身体正在楼梯最深那一级的正上方时，这一级
   从那一级起算。**不是放宽任何判据** —— 它给出的正是身体正在落进去的那一格；触发时打
   `stair.N.fromStep`。
2. ⛔️ **最后一级不许锚**（花了两趟才学到）。锚会把最后一级瞄到目标排以下；第一版改成「把锚那格当井底、
   等身体落进去」，身体**从来没落下去**（它站在一级没挖开的台阶上），而那个等待**没有出口** ——
   整趟 40000 tick budget 烧光，连 `stairs.bottom` 都没有（`TIMEOUT 40001t`，最后一行
   `stair.9.waited = -9, 57, 31 还没迈下去`）。而且用锚那格宣布井底，本身就造出
   `stairs.bottom = -9, 56, 31` 对 `forge.landedY = 57` —— **壁龛比自己的楼梯高一排**，两趟都是；
   其中一趟随后死在第一次上行的第一段（`第 0/3 段：想到 -9, 56, 31，停在 -9, 61, 32`）。
   现在：**井底永远读身体那格**，锚一旦够到目标排就丢掉 —— 最后一级照旧挖成
   `-9, 57, 31 → -9, 56, 32`（横着挪一格的那一手），归档里每一趟健康的 south 都是这么落地的。
3. **下井楼梯一级占三格，不是一格**（`JourneyStairs.needsOpen` / `flightCell`）。`fillable` 以前只问
   `cells.contains(c)`，而 `digStairsDown` 每级挖三格、`faults` 也审三格。壁龛地板那一排**就是**楼梯
   最底一级那一排，所以壁龛里的抬升必然从它旁边起、直接穿过它的起跳格：
   `wet.8.ramp.flight = -7,56,32 → -8,57,32 → -9,58,32 → -9,59,33`，第三块正是
   `stairs.bottom(-9,56,32).above(2)`；下一次自检把它认成坏台阶（**确实是**）并用镐修：
   `lava8.stairsMend.1 = -9, 56, 32 起跳格 -9, 58, 32=cobblestone → 敲开了` —— 抬升在身体脚下被拆了，
   人掉进泡水的模腔（`lava8.upStopped = 停在 -9, 58, 34 … 脚下/身处/头顶 都是 water`）。

### 判据对账（如实）

| 判据 | 状态 |
|---|---|
| 1. east ≥3 趟 PASS | ✅ **5/5**，每趟 `frame.cast=10/10`、`portal.cells=6/6`、十个 `recover*=CONSUME`（其中 2 趟在最终树上） |
| 2. south ≥3 趟 | ❌ **0/5**，且最终树上是**确定性的同一条红**（见下） |
| 3. `exactRow` 报出比例 | ✅ 见下表 |
| 4. 真实 ladder | **没跑** —— 判据 2 没开门，按交代不跑 |

**十趟排练（九趟跑完）合计 61 次 `buildTo`**：

| 指标 | 本轮 | 归档基线 | 上一轮（12 次调用） |
|---|---|---|---|
| 一块砖没垒就拒 | 11/61 = 18%（**其中 7 次是新加的楼梯三格规则**，旧那种只剩 4/61 = 7%） | 12/22 = 55% | 0/12 |
| 落在对的排 **+** 对的柱 | **27/61 = 44%** | 5/22 = 23% | 8/12 = 67% |
| 落在对的排（不论柱） | 38/61 = 62% | — | — |

上一轮那个 67% 的分母只有 12 次调用、且那三趟大多没走到最难的顶两排；本轮每趟 east 都浇满十格，
调用更多也更难，两个数不可直接比。

`stairs.asCut` 十趟全是 `N 级都完好`，没有一趟带同柱两级，**`stairs.audit` 报 21–23 次自检、0 次修**
（交回的那趟 south 是 16 次修）。

**等级：`backtested`。**三处改动都在运行里执行并兑现了各自的读数；**没有 live**。

### 下一个人从这里开始

1. **south 的红现在是确定性的，而且是第 3 处改动的直接代价**（south4 / south5 两趟逐条相同）：
   ```
   cast8.lift.noFlight   = -9, 59, 32 修不出楼梯：这一格自己的垫脚 -9, 58, 32 垫不了：
                           -9, 58, 32 是下井楼梯 -9, 56, 32 那一级的起跳格（爬上去要从这里穿过），不能堵
   cast8.liftTower       = 楼梯到 y=56 就修不上去了，交给塔兜底
   cast8.lift#1.climb.8.afloat = -10, 56, 32 浮在水里，8 次都没落地 —— 塔要站在地上才垒得起来
   cast8.liftedY         = 58/59      → 射线闸正确拒绝这一浇
   ```
   **拒绝是对的，该动的是那个落脚格。**同一趟里旁边那一排是修得起来的：
   `cell.8.ramp.rampedY = 59/59（同一柱）`，走的是 z=33 而不是 z=32。所以下一刀是**让浇筑的
   stand/lift 不要挑一个「垫脚被楼梯占着」的柱**（`buildTo` 的拒绝理由现在已经点名了是哪一格哪一级，
   可以直接反馈给挑柱那一步），而不是放宽 `needsOpen`。
2. ⛔️ **别再走的两条**：把锚用在最后一级（见上，两趟 + 一趟 TIMEOUT）；用「等身体落下来」代替
   「再挖一级」—— 身体站在没挖开的台阶上时它永远等不到。
3. **east 已经不必再赌**：5/5，且 `stair.N.fromStep` 在 east1/4/5 都触发过，说明防的是真事而不是巧合。
4. **顺手记下、本轮没碰**：`walkHome` 的 `returnStuck` 塔那段注释断言「垒回地面的柱子落不进浇筑要站的
   房间」——交回那趟 east 的 `cast6.returnStuck.3 = -5, 65, 19` **就落在楼梯自己那一柱上**，随后被
   `cast6.stairsMend.1` 敲掉。它只推理了壁龛、没推理楼梯。本轮 east 5 趟一条 `returnStuck` 都没有，
   所以没有现场可测；等它再出现时按「javadoc 是待验证的断言」处理。
5. 边界照旧，一字未动：11 级 `walkToTheLava` 还走湖心那一柱；`Walker.footingGuard` 在 `sole=0.0000`
   时也钉；walker 对「横 1 竖 1」会 `path-consumed` 在半格之外；15 级深渊、`JourneyEndRungs.march`
   第三处、16–20 级没有 staging 配方 —— **只记不碰**。

## ⬜ 更早的接手点 —— 抬升的病不是「落错柱」，是**根本没修出楼梯**：51 次拒绝里 43 次同一句话；治好了，但**判据 1、3 都没达成**

**这一轮的判断修正了接手时的方向。**「抬升要落在对的柱上」是真的，但它排在第二位：先去归档里数了一遍
`JourneyRamp.buildTo` 的每一次调用，**过半根本没垒一块砖就拒了**。

### 归档读出来的账（没跑新的一趟就能读到）

`fabric/run-journey/logs` 里 4 趟带 ramp 行的真实 ladder：

| | 调用 | 一块砖没垒就拒 | 落在对的排+对的柱 |
|---|---|---|---|
| 归档（改之前） | 22 | **12（全部同一个理由）** | 5（23%） |
| 本轮最终树（3 趟） | 12 | **0** | **8（67%）** |

那**唯一**的理由，12 次里 12 次：

```
water8.ramp.noFlight = 6, 60, 19 修不出楼梯：这一格自己的垫脚 6, 59, 19 垫不了：
                       6, 59, 19 六邻没有能贴的实心面（放方块要贴着一个面点）：
                       down=air up=air north=air south=air west=air east=air
```

**拒绝不是退路**：它后面只剩塔（这几何上必 stall）和 walker 的 YLevel 兜底（同一趟把身体丢到
`water8#3.endedIn = 0,19（起塔柱是 6,19）`，差七柱）。所以浇/收接着打的是没人验过的射线，
失败却写成「门框敲不开」或「装不到水桶」。

### 改了什么（两处，各自量过）

1. **楼梯自己给自己造墙（垫肩）**。楼梯是自下而上垒的，而 `support(i+1) - support(i)` 恒为斜角
   —— 所以楼梯永远撑不住自己。但**台阶正下方那一格**（`shoulder = support.below()`）落在上一级
   台阶那一排、隔一格，是**面贴面**的。于是先贴着下一级垒垫肩、再贴着垫肩垒台阶；只有最底一级
   还要向世界借面，而它坐在壁龛底下的岩石上，永远借得到。一级两块砖，本来就带着九十多块圆石。
   垫肩一并进 `steps`（否则 `tidyTheAlcove` 当野柱扫掉），且**不许落在楼梯自己要走的格上**
   —— 每级方向是独立选的，来回折一次就会把自己的楼梯从下面封死。
2. **垒楼梯的人不许站在楼梯上**。`approach` 以前只保证身体不在**最底那一级**里。量到的（两趟
   逐字相同）：身体站 `6,56,18`（第二级台阶正下方）→ 循环 break → 爬到下一级 → 自己的碰撞箱
   伸进要垒的那格：

   ```
   cell.6.ramp.step.1 = 6, 57, 18 垫不上（现在是 air，贴得到实心面（但身体自己的碰撞箱压在这一格里
                        —— vanilla 的 isUnobstructed 会拒，身体精确位置 6.60/57.00/17.78）），身体 6, 57, 17
   ```

   现在 `approach` 躲开**整个足迹**（台阶、垫肩、站位、头顶），`lay` 卡住时**横着挪开而不是爬上去**。
   效果：`cell.6.ramp` 从 `57/58，laid 1/2` → **`58/58（同一柱），laid 2/2`**，east 一趟从 8104 tick
   走到 26748 tick，0–6 号格全走完。

3. **顺手治了一条撒谎的证据行**（单独一个 commit）。`.step.N` 以前**无条件**印「六邻没有能贴的实心面」
   —— 归档里就有它自己的反证（`身体 -8,57,37` 那格正是身体站着的格）。现在它分开量「真的没有面」和
   「有面，但身体自己压在格子里」，第一趟就答了上面那一行。

### 判据对账（如实）

| 判据 | 状态 |
|---|---|
| 1. east ≥3 趟 PASS | ❌ **0/3**（其中只有 1 趟在最终树上）。差得远，不是差一点 |
| 2. `exactRow` 兑现比例上升并报出比例 | ✅ 23%（5/22）→ **67%（8/12）**；拒绝率 55% → **0** |
| 3. south 不回归 | ⚠️ **说不了**：改后树 **1 PASS / 2 趟**，父提交对照 **1 PASS / 1 趟**。样本不够 |
| 4. 真实 ladder | 没跑 —— 判据 1 没开门，按交代不跑 |

`./gradlew stagewrightDedicatedServerFabric` **GREEN**（exit 0）—— 红的三条是框架自己的
`canaryMustFail` 加两条 manifest 里标了 optional 的：`wd.vineOverWaterClimb`（早就记着的）和
`wd.serverEscapeSealedShelter`。journey/rehearse 各级默认不在 gate 里跑（只有 `wd.journeyArmed`
这个标记），所以 gate 绿并不覆盖本轮改的代码 —— 覆盖它的是下面五趟。

本轮五趟：

```
east  -8,19  垫肩树      FAIL  8104t   死在第 7 格浇岩浆（身体差一柱）
east  -8,19  垫肩树      FAIL  8096t   逐字相同 —— 钉柱后 east 已经是确定性的
east  -8,19  最终树      FAIL 26748t   0–6 格全通，死在「走不回模腔」
south -9,21  最终树      FAIL 11491t   9 格水浇完，身体泡在壁龛的水里走不上楼梯
south -9,21  最终树      PASS 11040t   10/10、6/6、十个 recover CONSUME
south -9,21  父提交对照  PASS 10362t   —— 但 ramp 9 次调用拒了 7 次
```

**等级：`backtested`。**两处改动都在运行里执行并兑现了各自的读数；**没有 live**。

### ⛔️ 阴性结果，别再重推

**「楼梯修不出来」本身从来不是这一级的死因。**父提交那趟 south **PASS，而 ramp 9 次调用拒了 7 次**
—— 第 9、10 格是 `standToPour`/`fillFrom` 从**壁龛地板**上服务的，和归档里那趟 east PASS 一模一样。
所以拒绝率是楼梯的属性，不是这一级的判决。**我第一次把改后树那趟 south FAIL 读成回归，是错的**
（重跑就绿了）。一个红也不是一个结论。

### 下一个人从这里开始

1. **红移到了「走不回模腔」**，east 最终树那趟：
   ```
   cast6.returnStopped = 第 2/4 段：想到 -1, 60, 19，停在 -5, 64, 19，差 5.66 格
   cast6.returnStuck.3 = -5, 65, 19 走不到楼梯 —— 开着放置权垒回地面 y=66 再走去楼梯口
   cast6.stairsMend.0  = -5, 64, 19 脚下 -5, 63, 19=air → 垫上了
   cast6.stairsMend.1  = -5, 63, 19 起跳格 -5, 65, 19=cobblestone → 敲开了
   ```
   **身体自己的 `returnStuck` 垒柱把楼梯堵了，随后的自检又把刚垒的敲掉** —— 一个自己制造、自己
   修补的故障。先读这一对行，别先怀疑 walker。
2. **south 那趟红是「身体在壁龛的水里出不来」**：`停在 -9, 58, 34`，`脚下/身处/头顶 都是 water`，
   距楼梯顶 15.26 格。楼梯把身体送上去了，**没有人负责把它送下来**（归档里「出口是计划不是搜索」那条）。
3. **判据 3 要补**：south 两边都只有一两趟。要么各跑 3 趟，要么承认这条判据这一轮没法结。
4. **east 仍是 0/3**，别拿本轮任何一趟当前沿。
5. 边界照旧，一字未动：11 级 `walkToTheLava` 还走湖心那一柱；`Walker.footingGuard` 在 `sole=0.0000`
   时也钉；walker 对「横 1 竖 1」会 `path-consumed` 在半格之外；15 级深渊、`JourneyEndRungs.march`
   第三处、16–20 级没有 staging 配方 —— **只记不碰**。

## ⬜ 上上轮的接手点 —— 12 级的开局走位修好了：**坑沿不是终点问题，是路线问题**；三处红各自治好，一趟 PASS 10/10、6/6

**本轮改了三处，每一处都是先量到才改的，而且中间那次「只改终点」是量出来的阴性结果。**

### 1. ⛔️ 阴性结果先说：**换个终点是无效的**（FAIL 3826t）

`walkToColumn(lava.x, lava.z)` 把身体送去的是**湖心那一柱，没有身体能站的格子**。归档里**六趟六条**
`lava.gotoEnd.1 = end=failed:…`，一条 `arrived` 都没有；`ARRIVED_WITHIN=5` 把每一次翻车当成到达。

于是先做了「选一格站得住、不在坑沿上的岸边格」（`JourneyTerrain.bankStandNear`，复用
`onThePoolsLip`，它从 `JourneyFill` 挪到 `JourneyTerrain` 让两个调用方共用）。判据本身是真的
——289 柱里 **37 柱**被坑沿否掉，选出 `lava.bank = -8, 66, 19`。**但整条腿照样死在坑沿上**，而且
**比原来更红**：

```
[walker] footing guard: sole 0.0362 at -14,66,21 / 0.0025 at -13,66,20 / 0.0000 at -13,66,21
lava.goto.1/2/3 = end=failed:no progress for 1200 ticks       FAIL 3826t
lava.viaMidpoint = -10,20 (卡在 -13, 66, 21)     ← 中点就是那片湖
```

三条 footing guard 是**同一条计划路线的连续三步**。新终点距翻车点 5.39 格（旧的是 4.47），
`ARRIVED_WITHIN` 不再兜底，于是掉进 `walkToColumn` 的中点补救 —— 而中点是湖。
**终点操纵不了路线**；`stepOntoDiggableColumn` 里早就写着的「中点在湖边是错的」，`walkToColumn` 一直没有。

### 2. ✅ 有效的那一刀：**给坑沿标价**（首次 `end=arrived`）

`JourneyTerrain.poolsLipCells` 在服务器线程上一次算出湖周所有 `onThePoolsLip` 格子，开局那条腿把它当
`CostModifier` 带上：每踏一格 **+300**（普通走一格是 10，即绕 30 格也比踏上去便宜）。
**是加价不是 `Constraint`** —— 唯一一条路是坑沿时仍然走得通。`Intent` 的 bias 表自诞生起就写着
「avoid a region」，这套里从来没人传过。中点那条腿也带同一份加价，否则补救会走进被禁的地方。

```
lava.rimTax    = 613 格坑沿每踏一格加价 300
lava.gotoEnd.1 = end=arrived err=null（判为到达：停在 -8, 66, 19，距 -8,19 0 格）
lava.arrivedDistance = 0    lava.walkAttempts = 1    这条腿上的 footing guard：0 条
```

**这是这一级归档里第一条 `arrived`。**之后三趟全部复现（`arrived` / `path-consumed`，距离都是 0）。

### 3. ✅ 楼梯第一级：`end=path-consumed`，差半格

开局修好之后身体每趟都精确落在 `-8,66,19`，于是**楼梯在自己第一级上卡死了 3 趟里的 2 趟**：
80 条一模一样的 `stair.N = -8,66,19 → -7,65,19` / `stair.N.waited`。新增的 `stair.wedged` 一趟就答了：

```
stair.wedged = -8, 66, 19 连着 3 腿一格没挪（精确 -7.00/66.00/19.35）；想去 -7, 65, 19；
               end=path-consumed err=null；台阶四格：脚下 -7,64,19=dirt，落脚 -7,65,19=air，
               头 -7,66,19=air，起跳 -7,67,19=air；canBreak(落脚)=true
```

台阶挖好了、站得住；身体**差半格、高一排**，`x = -7.00` 正是还托着它那块砖的东面。walker 把这个叫到达
并「consume」了路径 —— `wd.serverWalkerArrivedShort` 的微缩版。所以补救是**换问题不是放宽判据**：
被拒三次的一级连同下一级一起挖开，瞄第二级（横 2 竖 2，任何容差都不可能把它当成已到）。两级照样都挖开，
回程走的还是同一段楼梯。`stair.3 = -8, 66, 19 → -6, 64, 19（上一级被拒了三次，这一腿一次挖两级）`，
之后 `forge.landedY = 56`。

### 4. ✅ 开挖完不回模腔（三趟同样的两行）

壁龛顶离草皮只有两格，`breakItWhereItStands` 挥不到的那几格，`MineProcess` 最便宜的路线就是**爬上楼梯从外面往下挖**。
`carve.stuck` 自己的键就写着「挖完时身体脚下那一层」= **y=64**，而模腔地板是 56。

```
FAIL 8055t / 10608t / 8169t   forge.carved=66/67  forge.swung=63..64/67  carve.stuck={-2=1}
                              cell.0.standMissed=想站 3, 56, 19，停在 2, 65, 19
```

浇筑自己的走位补不了（`walkToStand` 只有 300 tick 且 `NoBreak`，要跨九排石头）。现在这个交接点走
`returnToTheForge` —— **取岩浆的每一趟本来就走它**，只是这一处漏了。身体本来就在模腔里则第一行就返回。
PASS 那趟 `forge.return = 4, 64, 18 → 楼梯口 -8, 66, 19 → 楼梯底 3, 56, 19`，顺手还修了一级
（`forge.stairsBroken = 1/16 级坏了`），`forge.returnedY = 57`。

### 判据对账（如实）

| 判据 | 状态 |
|---|---|
| 1. east 多趟 PASS（≥3） | ❌ **没达到**：本树三趟 **1 绿 2 红**，但三趟**全都走到了浇筑**（上一轮是 5 趟里 1 趟） |
| 2. south 不回归 | ⚠️ **这条判据本身失效了**，见下一节 |
| 3. 两臂稳定后跑一趟真实 ladder | **没跑**，门没开 |

本树三趟（同一柱 `-8,19`，同一份代码）：

```
不钉柱   PASS 11993t   frame.cast=10/10  frame.obsidian=10/10  portal.cells=6/6  十个 recover CONSUME
钉柱 A   FAIL 30867t   cast0..8 CONSUME  recover0..7 CONSUME   死在 recover8 收水（装不到 water_bucket）
钉柱 B   FAIL 15936t   cast0..7 CONSUME  recover0..7 CONSUME   死在第 9 格浇筑的射线闸（浇线穿出壁龛）
```

**两条红都在第 9 格附近、都在模腔里，没有一条在坑沿。**开局那条腿三趟都 `arrivedDistance ≤ 1`。

**等级：`backtested`。**开局走位、楼梯两级、回模腔三处都在运行里执行并兑现了判据；**没有 live**。

### ⚠️ 「south 臂」这个对照组已经不存在了

不带 `-PshaftColumn` 那一趟现在报 `shaft.standingOn = -8,19 (就近合格柱)`、`forge.away = east`。
**模腔朝向以前的「每趟随机」，来源就是开局那条腿在哪儿翻的车** —— 终点定死之后就近采纳也定死了。
要复现 south 那处几何，只能 `-PshaftColumn=-9,21`（`JourneyRehearsal` 的 javadoc 早就说了钉柱才是忠实的杠杆）。
**上一轮那处装料点修法没有在 south 几何上回测过**，这一条要补。

### 下一个人从这里开始

1. **红移到了门框顶上那几格（第 9 格前后），两条红是同一处几何的两种死法。**
   - 钉柱 A：`recover8.spot = 没找到能看见源块的落脚点，退回 Near(8,61,19,2)`、
     `recover8.rise#13.…driftKeptPinned`、`recover8.rise.raisedY = 60/60（停在 1,…）` ——
     **抬升落在了 1 号柱、瞄的是 6 号柱**，然后 `recover8.aimsAt = MISS；眼睛 3.50/59.62/19.30`，
     离 `8,61,19` 4.5 格。归档里那条「pinned raise 换了柱就等于换了射线」的账。
   - 钉柱 B：`浇不到指定格：想浇 7, 61, 19（瞄 8, 61, 19），射线会把流体放进 6, 60, 18，
     身体在 5, 58, 19；浇线上是 6,63,19 dirt(壁龛外) …` —— 射线闸**正确地拒绝了**，
     问题是身体根本没站到能看见那格的地方。两条都指向同一件事：**门框上两排的站位**。
   - 顺带：`JourneyRamp` 的 `exactRow` 就长在这条链上（`recover8.rise.ramp.rampedY = 56/60`）。
2. **east 钉柱要再跑几趟**：本树 1 绿 2 红。**一个绿不是一个结论**，一红也不是。
3. **`-PshaftColumn=-9,21` 补一趟**，把上一轮的装料点修法在它原本的几何上回测掉。
4. **rung 11（`WorldDriverJourneyScenes.walkToTheLava`）还在走湖心那一柱**，一字未动 —— 同一个缺陷，
   同一套 `bankStandNear` + `poolsLipCells` 可以直接用。故意没动：本轮只准改一个变量，而 11 级现在是绿的。
5. `exactRow` 那条欠账本轮**没碰也没顺手治**，仍是 6/14。
6. 边界照旧：15 级 `20,41,-23` 那道深渊、`JourneyEndRungs.march`（17 级）第三处用位移当进展的现场、
   16–20 级没有 staging 配方 —— **只记不碰**。
7. 引擎侧只记不碰的三条：`Walker.footingGuard` 在 `sole = 0.0000` 时也钉（javadoc 与代码相反）、
   `ascendByTowering` 在岩浆里垒不起来却报 `out of blocks?`、以及**新增的这条**：walker 对「横 1 竖 1」
   的目标会 `path-consumed` 在半格之外，把没走完的路报成到达。

## ⬜ 上一轮的接手点 —— 装料点治好了（south 2 红 → 绿，10/10、6/6）；但 `east` 臂**五趟只绿过一趟**，红在装料点上游

**这一轮只改了一个变量：`pinTheFillStation` 选装料点时，把「一步之外就是通向岩浆的空洞」也算进否决判据。**
判据 2 达成，判据 1、3 没有 —— `east` 那条「假红」的结论是**在一个绿上下的，站不住**。

### south：同一格杀了两趟，两种死法 —— 这不是抽签，是那一格

`station = -14, 65, 21` 是坑沿上一个凹口，东侧是直通湖底的空洞。**两趟连着死在这一格：**

```
run A  FAIL 12595t  第 7 趟  06:39:49 search-begin start=-9, 66, 21 goal=-14,65,21
                            06:39:55 [walker] footing guard: sole 0.0938 at -12,66,21
                            06:39:58 search-begin start=-13, 62, 19          ← 已经在湖里
                            …沉到 -15,59,19；泡在岩浆里 ascendByTowering 垒不起来
                            （climb.0 above=lava … stuck (no Y gain)，手上还有 128 圆石）
run B  FAIL 17410t  第 8 趟  lava8.aimsAt … 眼睛 -13.70/63.62/22.84           ← 从 y=62 装的桶
                            cast8.returnStuck3#1.gained = 4/4                ← 垒回 y=66 了
                            cast8.returnStopped 停在 -14, 66, 21 …脚下 air    ← 然后钉死在这儿
```

**分布是从 run A 一趟里读出来的，不是从两趟凑的**：那一趟有 **11 条** `footing guard … at
-12/-13/-14,66,21`、**两次掉进湖里**（第 6 趟也掉了，只是正好落在 y=63 的岩浆面上爬得出来）。
一个走十趟、掉两次的落脚点，不是运气不好。

**run B 那条更直白**：身体停在装料点**上面那一格**，脚下是空气，`soleOnSolid = 0`，
vanilla 的 `maybeBackOffFromEdge` 把每个方向都收缩到 0 —— 钉死在自己装料点的门口。

### 修法：判「脚边一步之内有没有通向岩浆的空洞」，问两格

`onThePoolsLip` 是 walker 自己 `lethalDropAdjacent` 的**危险那一半**（八个水平邻格里，落脚格和它下面
那格都是空的、且往下八格内碰到岩浆），**在落脚格和它上面那格各问一次** —— 因为身体先到上面那格，
run B 就再没往下走过。**是偏好不是硬规则**（`standToFill` 那套两趟式），严格那趟空了就退回旧判据，
`station` 证据行写明是哪一趟答的。`STATION_REACH` 同时 5 → 8，**只跟这条规则一起改才有意义**：
半径 5 时这处几何上「看得见源块」的格子**全在坑沿上**，规则没地方可去。

```
station = -17, 65, 15：够得着 2 格源块，距楼梯口 10 格；脚边一步之内没有通向岩浆的空洞（严格判据）；
          否决计数 {走过去要横穿岩浆=6, 脚下不实心=121, 落脚或头顶被占=371,
                    脚边就是通向岩浆的空洞=4, 够得着的源块不足 1=72}
forge.carved = 67/67   十个 recover*.result = CONSUME   frame.cast = 10/10
frame.obsidian = 10/10   portal.cells = 6/6   PASS 18608t   staging.calls = 12
```

十趟，一次没掉，一条 `returnStuck` 都没有。

两条顺带读出来、别再重推的事：**`stationSees` 不是预算** —— 第 1 趟是 1，第 2 趟起一路 0，十桶照样装满，
因为 `visibleSourceNear` 是从身体真正站的地方重问的、`clearedLine` 会开新视线（归档那趟 `east` PASS
也是全程 `stationSees=1` 浇完十格）；**走过去仍然很贵** —— 117 条 `search-begin … goal=-17,65,15`、
47 次 footing pin，因为这颗种子上**通往任何装料点的路都要蹭火山口的边**。那是代价不是失败，
18608 tick 就花在这儿。

### ⛔️ 阴性结果：先做的「判路线」那一刀**完全无效**，已回退

先实现的是 `lavaUnderTheWalk`（从楼梯口到候选点的直线上，逐列往下扫到第一个实心格，中途见岩浆就否）。
它**否掉了 4 个候选、留下的正是 `-14, 65, 21`**，run B 就是那一趟。**岩浆不在路线下方，在终点旁边。**
别再走这条。

### ⛔️ `east` 臂：`-PshaftColumn=-8,19` 五趟只绿过一趟，而且**两条新红都在装料点上游**

| # | 结果 | 死因 | 树 |
|---|---|---|---|
| 1 | FAIL 8055t | 第 1 格没挖开 | 退避修法之前 |
| 2 | FAIL 5001t | 站不到可下挖的柱子，停在 -13, 66, 21 | 退避修法之前 |
| 3 | **PASS 19807t** | — | 退避修法之后 |
| 4 | FAIL 10608t | `forge.carved = 66/67 格开了，1 格没挖动`；`cell.0.standMissed 想站 3, 56, 19，停在 2, 65, 19` | 本轮 |
| 5 | FAIL 206t | `lava.gotoEnd.1 = end=failed:no path (expanded=1)（判为到达：停在 -12, 63, 20）`，`forge.surfaceY = 64（脚下 y=63）` —— **身体站在湖里** | 本轮 |

**上一轮那句「east 那条红是布景不足造成的假红，不是缺陷」是在一个绿上下的结论。**
按归档「数样本要数全部」，它现在是 1/5。

**这两条红与本轮改动无关，而且不可能有关**：`pinTheFillStation` 在 `castTheFrame` 里跑，
在 `carveTheForge` **之后**；第 5 趟连 `station` 那一步都没走到，第 4 趟走到了但一条 `lava0.*` 都没有。

**它们说的是另一件事：火山口的坑沿在 12 级至少还杀三处** —— 开局走去岩浆那一段、踏上下挖柱那一段、
以及开挖时的够得着判定。第 5 趟还第一次记录到**上一轮那把退避刀失效**：
`shaft.backOff.2 = -12, 63, 20（想退到 -16,24，只退到这里）` —— 身体已经泡在岩浆里，退不动。

### 判据对账（如实）

| 判据 | 状态 |
|---|---|
| 1. south 与 east 两条排练都 PASS | ❌ south ✅（18608t，10/10、6/6、十个 CONSUME）；east ❌（1/5，红在上游） |
| 2. 装料点不再落在坑沿，且有证据行说明按什么判 | ✅ `station = -17,65,15 …脚边一步之内没有通向岩浆的空洞（严格判据）；…脚边就是通向岩浆的空洞=4` |
| 3. 两臂都绿之后跑一趟真实 ladder | **没跑** —— 门没开。按「别为了好看多跑」，不跑 |

**本轮改动等级：`backtested`。** south 臂 2 红 → 1 绿，判据在证据行里兑现；**没有 live**（没跑 ladder）。

### `exactRow` 那条欠账：现在有 14 个样本了，**6/14 兑现**

把两臂归档里每一个 `rampedY` / `raisedY` 都数出来（east PASS 9 处 + south run A 5 处）：

- ✅ 6 处「同一柱、同一排」：`wet.7 58/58`、`cell.9 59/59`、`cell.6 58/58`、`recover6.rise.ramp 58/58`、
  `recover6.rise.raisedY 58/58`、`cell.7 58/58`（后四处都在 south）
- ❌ 8 处没兑现，分三种：**高一排** 4 处（`cast6.lift 59/58` ×2、`recover6.rise.* 59/58` ×2）、
  **矮一排** 2 处（`cell.6 57/58`、`cell.7 57/58`，都在 east）、**排对柱错** 2 处
  （`wet.8 60/60`、`cast8.raisedY 59/59`）

**等级仍是「分支已执行、判据未兑现」，但现在是个比例而不是一句话：6/14，而且失败模式已经分类。**
别写成做到了。本轮站位改动**没有**顺手治它。

### 下一个人从这里开始

1. **`east` 臂的红在坑沿，不在装料点。** 两条新红都是「身体到了 y=63/65 不该到的地方」。
   下一刀多半在 `walkToColumn(lava)` 那一段：`lava.gotoEnd.1 = no path (expanded=1)` +
   `脚下 y=63` 是**身体在岩浆里**的签名（归档 `expanded-1-was-a-body-in-lava`），
   而 `acrossThePool` 这类判据只长在 fill 那一侧，开局那一段没有。
2. **别再判路线，判落脚点。** 见上面的阴性结果。
3. **数样本要数全部**：`east` 是 1/5，`south` 是 1/3（2 红 1 绿，绿的那趟是改动之后）。
   再往上promote之前，`south` 至少还要一趟绿。
4. 边界照旧：15 级 `20,41,-23` 那道深渊、`JourneyEndRungs.march`（17 级）第三处用位移当进展的
   现场（换棘轮，别换「每段净进」）、16–20 级没有 staging 配方 —— **只记不碰**。
5. 引擎侧只记不碰的两条：`Walker.footingGuard` 在 `sole = 0.0000` 时也钉（javadoc 自称阈值取半个脚掌
   正是为了不钉已经悬空的身体），以及 `ascendByTowering` 在岩浆里垒不起来却报
   `out of blocks?`（手上 128 圆石）。

## ⬜ 上上轮的接手点 —— 12 级 `east` 那条红是**假红**：差异排空之后排练一趟走通，10/10、6/6；新红在 south 臂的装料点

**这一轮只改了一个变量：`stepOntoDiggableColumn` 里那一腿「一格没挪」时不再重问同一个问题，
而是背对岩浆退四格再问。** 在与真 ladder 逐字相同的 `east` 几何上，这一刀把整条 12 级打通了：

```
shaft.wedged.2   = -13, 66, 21 这一腿一格没挪（上一腿从 -13, 66, 21 起）—— 先退到 -17,25（背对岩浆）站稳再问
shaft.backOff.2  = -17, 66, 24（退到了，从这里重问）
shaft.standingOn = -8,19 (选定柱)         stairs.top = -8, 66, 19 往 east 下 10 级到 y=56
forge.face       = 4, 56, 19 朝 east      forge.carved = 67/67 格全开     carve.stuck = 无
recover0..9.result = CONSUME（十个全 CONSUME）
frame.cast = 10/10   frame.obsidian = 10/10   portal.cells = 6/6
PASS 19807t，staging.calls = 13
```

**所以判据 2/3 到了，差异清单排空了：那条「开挖 66/67、身体垒回地表」的红是布景不足造成的假红，
不是缺陷。** 之前它在 `-PshaftColumn=-8,19` 下逐字重现过两趟（圆石 64 / 111，都是 `FAIL 8055t`），
唯一还差的是水桶——空桶那趟被走位挡在前面没测到，这一轮把走位修通之后，同一处几何一次走通。

### 归档直接定死了死因，没有再花一趟去猜

上一轮留的两条路（**给更多尝试** / **退回就地采纳**）**都被归档否掉了**，理由都在同一份日志里：

```
shaft.stepping.1 = -13, 66, 21 → -8,19        ← 三腿的起点一模一样
shaft.stepping.2 = -13, 66, 21 → -8,19
shaft.stepping.3 = -13, 66, 21 → -8,19
[pathfinder] search-begin owner=goto start=-13, 66, 21 goal=XZ[x=-8, z=19, radius=0]   ×约 110 条
[walker] footing guard: sole 0.0000 < 0.18 at -13,66,21 beside a lethal drop → sneak-pin
```

1. **不是「找不到路」。** 那 110 次搜索**间隔 1.4 秒**，正好是 `Walker` 自己
   `guardPinStreak >= 30 → path = null` 的节拍 —— 路每次都找到了，30 tick 后被钉住的 pin 丢掉，
   再找。搜索失败会是 4 s（`maxMs`）一条，不是 1.4 s。
2. **走不动的原因是它自己的 footing guard。** 身体站在岩浆湖坑沿上：从存档世界里读出来
   `-13,65,21`、`-14,65,21` 是通向洞穴的空气，隔壁 `-12,65,21` 是草方块，岩浆在 y=63。
   sole=0.0000 触发 sneak-pin，而 vanilla 的 `maybeBackOffFromEdge` 会把**任何**水平位移收缩到 0
   ——脚下全空的身体，钉住就等于完全不能动。
3. **所以「更多尝试」是零。** 三腿 3600 tick、约 110 次同样的搜索、一格没挪：第四腿也一样。
   这正是归档里那条「a retry that changes nothing」。
4. **「退回就地采纳」会把复现丢掉。** `-13,21` 在坑沿上，退回去等于换一处几何、换一个开挖起点，
   而这一轮要测的恰恰是 `east` 那一处。

**方向是这一刀的全部**：`walkToColumn` 现成的补救是走**中点**，在这里是错的 —— 坑沿上的身体和对岸
柱子的中点就是那个岩浆湖。退的方向必须是**背对水池**。这一刀没有放宽任何判据：要去的柱子一个字没改，
挪得动的那一腿一个字节没变。

### 顺手补上的那条证据行（它的缺席是这一轮之前那趟白跑的原因）

`lava.arrivedDistance = 4` + `lava.walkAttempts = 1` **两个世界一模一样的读数**：走到了、停在容差内，
和走不动了、walker 放弃。`walkToColumn` 的 end reason 一直存在，只写在**没到达**那条分支上。现在两边都写：

```
lava.gotoEnd.1 = end=failed:no progress for 1200 ticks (best dist=44)（判为到达：停在 -13, 66, 21，距 -9,19 4 格，容差 5）
```

`ARRIVED_WITHIN = 5` **故意没动** —— 下一步是搜索的调用方，五格容差是对的；下一步要精确柱子的调用方，
现在能看见自己收到的是哪一种到达。

### 排练的两条规矩（写下来，别再靠记）

1. **给予量照真 ladder 那一级的实测值**，不是一个方便的整数（圆石 111，不是 64）。
2. **工具要按爬升到手时的状态给** —— 水桶给**空**的。满桶省掉的那趟走水路，正是决定「开挖开始时
   身体站在哪」的那一段；省掉它，测的就不再是同一件事。

`rehearsal.gave` 现在把这两条和「仍然故意不同的只剩镐给两把」一起打在证据行里。

### `exactRow` 回测：分支**执行了**，五条判据过四条，**第五条没兑现**

`east` 几何这一趟第一次跑到 cell 6，上一轮预言的那一格原样出现：

```
recover6.rise = 3, 58, 18 看不见 4, 59, 19 里的水（… 高度已经够了 —— 差的是柱）
recover6.rise.raiseTo.arrivedDistance = 0
recover6.rise.ramp.flight = 2 级：3, 56, 20 → 3, 57, 19      ← 旧的 `>=` 会整段跳过，现在修了
recover6.rise.ramp.laid   = 2/2 级垫好了
recover6.rise.ramp.rampedY = 59/58（停在 3, 59, 20，要的落脚格 3, 58, 19，不是同一柱）
recover6.rise.raisedY      = 59/58（… 比要站的排高 1 排 —— 从这里打出去的不是验过的那条）
recover6.fromHere = 3, 59, 20 已经看得见源块 4, 59, 19（够得着）    recover6.result = CONSUME
```

- ✅ `.ramp.*` 出现 ✅ `recover*.result = CONSUME`（十个）✅ `frame.cast = 10/10` ✅ `portal.cells = 6/6`
- ❌ **`raisedY` 的排数与要站的排一致 —— 没做到**：楼梯垫好了，身体仍然高一排、偏一柱。

**等级：`backtested`（分支已观测执行，一趟 PASS），但它自己的判据没有兑现。**
救回这一格的是 fill 自己重新选站位（`recover6.fromHere`）——这是第三次记录到
「`raiseColumn` 给的是提示，不是契约」。**别把这一格的绿读成 `exactRow` 做到了它承诺的事。**

### ⛔️ 新的红：south 回归臂 `FAIL 12595t`，身体**掉进岩浆湖**，cast7

```
走不回模腔：停在 -15, 59, 19 …… 身体处：脚下 stone，身处 lava，头顶 lava
station = -14, 65, 21：够得着 3 格源块，距楼梯口 5 格（十趟都站这里）
cast7.returnStuck3#1.climb.0 = -15,59,19 above=Block{minecraft:lava} onGround=true water=false
cast7.returnStuck3#1.gained  = 0/7 block(s)（握着 128 圆石，stalled=stuck (no Y gain)）
forge.carved = 67/67 格全开      shaft.standingOn = -9,21 (选定柱)
```

**这不是本轮改动造成的，证据是它没有触发**：整趟只有 `shaft.stepping.1` 一条，`shaft.wedged.*` /
`shaft.backOff.*` 一条都没有 —— `lastFrom` 首次进入是 null，退避分支进不去；另一处改动只是多写一行
证据。这一臂的代码路径与改动前逐字相同。

**它相对归档那趟 south PASS（6723t）唯一还在树上的行为差别是「水桶换成空的」**（上一轮已上树，
south 臂在那之后**从没跑过**）。空桶把 `lava.arrivedDistance` 从 1 变成 4，落脚、选柱、
装料点都跟着变了。**下一个人的第一件事就是这条**，而且已经有一条很具体的线索：装料点
`-14, 65, 21` 就压在坑沿上 —— 同一趟日志里 `[walker] footing guard: sole 0.1798 < 0.18 at -14,66,21
beside a lethal drop` 说的就是那一格。**一个「十趟都站这里」的装料点选在会把身体抖进湖里的格子上，
这是选站位的账，不是走路的账。**

⚠️ **一次样本不是分布**：south 臂只跑了这一趟，还不知道是必然还是抽到的。**别在没有第二趟之前
就说「空桶把 south 臂弄红了」** —— 那正是归档里「小样本会撒谎」栽过的地方。

### 记下来、本轮按边界没碰的引擎发现

`Walker.footingGuard` 在 `sole = 0.0000` 时也会钉，而**它自己的 javadoc 写着**「已经悬空的身体
钉不钉都会掉，所以阈值取半个脚掌而不是零」。实际后果是：脚下全空时 vanilla 的
`maybeBackOffFromEdge` 把每个方向都收缩到 0，钉住 = 一动不能动；`guardPinStreak >= 30 → path = null`
这条自救只是每 30 tick 重问一次同一条路。**按「不要一上来补引擎」不碰**，本轮用关卡侧的退避解决了
它在 12 级的表现。真要动它，先想清楚「钉一个已经悬空的身体」买到了什么。

### 跑过的

| 跑法 | 结果 |
|---|---|
| `-Prehearse=PORTAL_LIT -PshaftColumn=-8,19`（本轮改动后） | **PASS 19807t** —— `forge.face = 4,56,19 朝 east`、`forge.carved = 67/67`、十个 `recover*` CONSUME、`frame.cast=10/10`、`portal.cells=6/6`、`staging.calls=13`；`shaft.wedged.2` / `shaft.backOff.2` 都观测到 |
| `-Prehearse=PORTAL_LIT`（south 回归臂，本轮改动后） | **FAIL 12595t** —— 退避未触发（代码路径未变）；`forge.carved = 67/67` 之后走到 cast7，身体停在 `-15,59,19` 岩浆里 |
| `check_source_budget.py` / `check_scene_arena.py` | 过 |

### 下一个人从这里开始

1. **south 臂那条红**（装料点 `-14,65,21` 压在坑沿上）。先**再跑一趟 south** 定它是必然还是抽签，
   再谈修法。修法的形状大概率是「装料点不能选会把身体抖下去的格子」，**不是**放宽 `returnStuck`。
2. **12 级现在值得让真 ladder 再爬一次**：`east` 几何这一族在排练里已经走通，而归档 14 趟里
   6 趟停在 11 级、死因全是 12 级模腔这一族。**进度仍按分布报：稳定前沿 11 级，最好一趟到过 14。**
3. `exactRow` 的第五条判据（`raisedY` 排数一致）**还欠着**，见上。
4. 边界照旧：15 级 `20,41,-23` 那道深渊、`JourneyEndRungs.march`（17 级）第三处用位移当进展的
   现场（换棘轮，别换「每段净进」）、16–20 级没有 staging 配方 —— **只记不碰**。

## ⬜ 上一轮的接手点 —— 穿越的 wedge 判据换成棘轮，阶梯这才第一次真正跑起来；但真 ladder 两趟都红在 12 级，没走到穿越

**这一轮只改了一个变量：`oneHop` 判「这一段有没有白走」看的是**位移**，来回走的身体每段位移
40+ 格，于是减半和偏 60° 的阶梯一次都没触发过。换成「比这趟穿越到过的最近点还近多少」之后，
14 级排练 PASS（穿越 14 段到达，还差 23 格，`blaze_rod = 1`），15 级排练仍 FAIL 但穿越 12 段
就如实收手（旧判据要磨满 24 段）。**

**这一刀的等级是 `backtested`，不是 `live`** —— 真 ladder 跑了两趟，一趟红在 12 级、一趟红在
11 级，**两趟都没走到穿越**（`rung.BLAZE_ROD = BLOCKED`），两趟 `stagingCalls = 0`。
判据 2（ladder ≥ 14 级）**本轮没有达成，而且这两趟对这一刀什么都没说**。

⚠️ **现在挡路的是主世界那半，不是穿越。** 两趟红在两个不同的级（12、11），死因也不同
（`east` 模腔的射线被门框压住 / 爬不出自己挖的 36 格井）。

### 数清楚了：归档里 14 趟真 ladder，**死因的族在 ChunkMap 那一刀前后没有变**

上一轮（和我上一段）都在只有两三趟的时候谈方差。**把归档里所有能找到的趟数都列出来**
（每一段接手点里的 ladder 记录，共 14 趟，按时间顺序；分界线是 `99ce7609`）：

| # | 爬到 | 红在 | 死因原文（节选） | 族 |
|---|---|---|---|---|
| 1 | 11 | 12 | `forge.carved = 48/67 格开了，19 格没挖动`；`cell.0.standMissed 停在 -10, 66, 34`（身体把自己垒回地表） | 走位/几何 |
| 2 | 11 | 12 | `cast3.picks … 打偏一格`；`射线会把流体放进 -10, 57, 33 —— 没有倒` | 几何/射线 |
| 3 | 11 | 12 | `cast6.lift#5.climb.1.stalled = stuck (no Y gain in 60t)`（干地上塔一课没起） | 走位 |
| 4 | **13** | 14 | 下界行走 | 走路 |
| 5 | 11 | 12 | `cell.6.stillShut.3 = -8, 59, 38=gravel：从头到尾没开过`（模腔最上一排） | 几何 |
| 6 | **12** | 13 | `stand.in = air`，八条腿全停在同一格（站不进传送门方块） | 走位 |
| 7 | 11 | 12 | 模腔换到 `4, 56, 19 朝 east` | 几何（**east**） |
| 8 | **13** | 14 | 穿越掉岩浆（`FAIL 6665t`） | 走路 |
| 9 | **14** | 15 | 末影人一只都没刷（`FAIL 7916t`） | **刷怪** |
| — | — | — | ——— `99ce7609 tell the chunk map where a driven body actually is` ——— | — |
| 10 | 10 | 11 | `走不到岩浆柱：目标 -6,54，停在 29, 61, 77`；`end=failed:no progress for 1200 ticks` | 走路 |
| 11 | 11 | 12 | `走不回模腔：停在 -9, 60, 28 … 浇下去只会浇进楼梯` | 走路 |
| 12 | **13** | 14 | 穿越 24 段原地摆动，`还差 265`（就是这一轮修掉的那条） | 走路 |
| 13 | 11 | 12 | 模腔 `4, 56, 19 朝 east`，门框 `4,60,19` 压在射线上 | 几何（**east**） |
| 14 | 10 | 11 | `走不到 firstWater 64, 62, 60：停在 6, 13, 37`（爬不出自己挖的 36 格井） | 走路 |

**判据是族，不是级数的均值。** 读出来的是：

1. **前 9 趟里有 6 趟也停在 11 级**，死因全是 12 级模腔那一族。coordinator 记得的
   「之前 12 级 2/2、13 级 2/2、14 级 1/2」**只描述了那一刀前的最后两趟**（#8、#9）——
   把 9 趟一起数，那一刀之前的 ladder 同样主要是一部 11 级的 ladder。
2. **那一刀之后 5 趟，5 趟死因全是走路/几何**，**一趟都没有**加载、实体消失、tick 停滞的味道。
   那一刀之前唯一一条非走路的红是 #9 的刷怪 —— 那正是这一刀要修的东西。
3. **`east` 模腔在这一刀两侧各杀了 12 级一次**（#7、#13）。模腔位置每趟随机，这比任何
   关于区块的假说都更能解释 11/12 这堵墙。
4. **tick 率直接排除了停滞**：第 14 趟逐级都是 20.00 tick/s，**包括红的那一级**
   （`journey11Obsidian` 17985 tick / 899235 ms = 20.00）。加载/票的代价会表现为 tick 亏空，没有。
5. 归档里早就有一条机制层面的排除，值得再引一次：11/12 级跑的时候 suite 的 world pin 让
   `doMobSpawning=false`，只有 14/15 级自己 `rig.liveWorld(true)` —— **这一刀「世界会在身体
   所在处刷怪了」的效果在杀死 ladder 的那两级根本没打开。**

**结论：ChunkMap 那一刀不该降级，等级仍是 `backtested`。** 但要如实说样本：9 趟对 5 趟，
而且「之前能到 13/14」是 9 趟里的最后 2 趟。**没有跑新的对照趟** —— 归档答得上来。

### 为什么不是「每段净进展」—— 归档直接判死了那个方案，没有再花一趟

回放归档那 24 段（上一趟 `fabric/run-journey` 的 `fortress.hops`），三种判据各自多久触发
`MAX_WEDGED_HOPS = 4`：

```
moved >= 4          （原样）    永不触发 —— 24 段跑满，阶梯一次没用
(away - left) >= 4  （每段净进）永不触发 —— 摆动的净进是 −48, +41, −49, +47，
                                连续计数每隔一段就被清零
(best - left) >= 4  （棘轮）    第 11 段触发
```

**摆动的定义就是「相邻两段的净进互相抵消」，所以任何只记一段的判据都看不见它。**
棘轮记的是这趟穿越到过的最近点，来回走于是一分不得 —— 那才是关于它的实话。
（回放跑在**归档的轨迹**上：阶梯一旦触发身体就走别处去了，所以这只证明判据会触发，
不证明穿越会到。两个案例已经并排写进 `PROGRESS_UNDER` 的注释里。）

### 「净进展为负或接近零时该做什么」—— 现有阶梯是对的，它只是从来够不着

这一轮两条穿越**各观测到一次**：偏 60° 那一段就是把身体带过墙的那一段。

- **14 级**：#8 净进 1 → 减半；#9 净进 0 → 偏 60°；**#10 偏 60° 净进 7**（纪录 169→162）；
  #11 净进 44，一路到 `fortress.arrivedDistance = 23`。那堵墙在 `161, 55, 177`，
  #8/#9 在那里两次拿到 `no route progress … goal unreachable from here (best dist=424)`。
- **15 级**：#4/#5 净进 0 → 减半、偏 60°；**#6 偏 60° 净进 4**（纪录 297→293）；#8 净进 12。

历史上还有第三次（见下面那份旧接手点）：那趟 PASS 的穿越「第 5、6 段各 900 tick 一格没挪，
靠第 7 段的『偏 60°』重问才走出去」。所以**阶梯不用动，`MAX_WEDGED_HOPS` 也仍是 4**——
变的是这个计数器的**主语**：从「连着几段没挪窝」变成「连着几段没刷新纪录」。

### 15 级：闸又换了一次主语，而这次是一条能指名道姓的地形

穿越 12 段收手在 `32, 41, -33`（离 `168,?,-281` 还差 283，全程最近 281）。拦路的读数在 #4/#5，
两段都停在同一格：

```
warped.around.4 = 脚下=air 身处=air 头顶=air … onGround=true 落速=-0.08 血=20 脚下到实心=>16
warped.flight.4 = 走了 0/48 格；共 148 tick；走过的边 {bridgePlace=1}；收工那一刻：
   end=failed:no route progress after 5 consecutive searches — goal unreachable from here
   (best dist=456) 在 20, 41, -23 onGround=true
```

身体站在 `20, 41, -23`，**脚下 16 格以内没有实心**而 `onGround` 仍报 true —— 老一族。
它架了一格桥就再也问不出路。**这不是 wedge 判据的账，是「架桥过深渊」的账。**

### ⚠️ 12 级：`east` 模腔那条待回测的分支**执行了、不够**，而新死因是「站高了一排」——已修

**回测结论（不要再写成笼统的 `backtested`）：分支已观测执行，且不足以救 `east` 类模腔。**
但查下去，**「对的柱 + 对的高度」在 east 几何下并非不可得 —— 对的高度从来没被尝试过。**

```
recover6.rise.raise            = 3, 58, 18 → y=58（去 3,19 这一柱…）：站上去射线打得到目标格里的液体，钉住这一柱
recover6.rise.raiseTo.arrivedDistance = 0        ← 柱走到了，分毫不差
recover6.rise.raisedY          = 59/58（停在 3,19，指定柱 3,19，同一柱）   ← 高了一排
（全run 一条 recover6.rise.ramp.* 都没有）
```

链条是：`raiseColumn` **把眼睛放在 `wantY` 那一排**去验射线（`scoopSeesFrom` 的 eye =
`foot.getY() + eyeHeight`，而 `foot` 就在 `wantY`）→ `walkToColumn` 是 `Goal.XZ`，
**对排数没有任何意见** → 身体走到柱上时在 y=59 → `JourneyRamp.buildTo` 第一行
`if (here.getY() >= landing.getY()) return;` 认为「已经够高」**直接不修**，所以落脚格
`3,58,19` 永远没有地板 → 收水从**没验过的那一排**打射线，线穿进水上面那格门框 `4,60,19`，
`JourneyFill` 正确地拒绝敲门框，于是 `frameStuck` 让门框背了锅。

**落脚格是空的、只是没地板**，这正是楼梯该干的活 —— `standToFill` 的否决计数说得清清楚楚：
`{脚下不实心=13, 落脚格被占=17, …}`。

**改法（本轮已上树）**：`JourneyRamp.buildTo` 多一个 `exactRow`。**浇**照旧「够高就行」
（浇瞄的是背板，高一点真的无所谓）；**收水**要求排数精确，因为它的柱只在那一排验过。
`raisedY` 现在会直接说「比要站的排高 N 排 —— 从这里打出去的不是验过的那条」。
**没有放宽门框**：这一级已经三次证明容忍上游会把失败搬家。

**等级 `compiled`**：`east` 模腔每趟随机，本轮没再抽到，这条分支没有被观测执行过。
回测只做到「`south` 模腔无回归」（见跑过的表）。

上一轮写「要抽到 `east` 类模腔、grep `高度已经够了` 才算验过」。**这一趟抽到了**
（`forge.face = 4, 56, 19 朝 east`，前两趟都是 `-9, 56, 38 朝 south`），那一行也打出来了：

```
recover6.rise = 3, 58, 18 看不见 4, 59, 19 里的水（脚在 y=58，要站的排 y=58，高度已经够了
                —— 差的是柱：从这一柱望过去，射线要斜着穿过刚浇的门框）—— 先挪到一条望得见水的柱上再收
recover6.rise.raisedY = 59/58（停在 3,19，指定柱 3,19，同一柱）
```

**分支跑了，换柱也换成了，然后仍然 FAIL**，死因换了一个：

```
recover6.aimsAt     = 4, 60, 19 Block{minecraft:dirt} 源块=false（想瞄 4, 59, 19）；
                      眼睛 3.69/61.29/19.54 朝 pitch=65.78
recover6.frameOnLine.3 = 4, 60, 19 挡在眼睛和 4, 59, 19 之间，但它是门框格 —— 不敲
recover6.frameStuck.3  = 门框挡着 4, 59, 19，而且没有别的落脚点看得见它（一处都没验过）
```

换到对的柱之后身体**站得太高**，眼睛在 y=61.29 往下瞄 y=59 的水，门框格 `4, 60, 19` 正好
压在射线上，而门框不许敲。**所以 12 级的「2/2 稳定」是 `south` 那类模腔的 2/2**，
`east` 类第一次跑就红。这一条按边界没有碰，等级应从「compiled + 无回归」改成
**「backtested：分支已观测执行，且不足以救 `east` 类模腔」**。

### ⚠️ 定向排练那个杠杆一直是空的：`-PforgeAway` 从来没有转动过模腔（已修）

想用「四个朝向各定向跑一次」代替「赌 27 分钟的 ladder」，先得确认那个杠杆真的连着。**它没有。**
四个朝向各跑一趟，四个不同的落脚点，**同一根柱、同一个朝向**：

| `-PforgeAway` | `rehearsal.stand` | `shaft.standingOn` | `forge.away` | 结果 |
|---|---|---|---|---|
| east | `-1, 65, 11` | `-9,21 (选定柱)` | **south** | PASS 7751t，10/10、6/6 |
| south | `-16, 67, 27` | `-9,21 (选定柱)` | **south** | PASS 6611t，10/10、6/6 |
| west | `-17, 64, 13` | `-9,21 (选定柱)` | **south** | PASS 7604t，10/10、6/6 |
| north | `-10, 66, 11` | `-9,21 (选定柱)` | **south** | PASS 8229t，10/10、6/6 |

**所以那四趟绿是同一处几何跑了四遍，不是四个朝向。**「四朝向定向排练通过」这句话写不得。

原因是结构性的，两处，都得改才管用：

1. 12 级开头就是 `walkToColumn(lava)`，**把布景摆的落脚点整个丢掉**；随后
   `pickDigColumn` 从池子往外一圈圈扫、返回第一根合格柱 —— 对同一个池子每趟都是同一根。
   （真 ladder 的模腔会变，只是因为 11 级把第一个池子用掉了，12 级拿到的是**另一个池子**。）
2. 更要命的是 `stepOntoDiggableColumn` 里那个**就地采纳**短路：只要身体脚下这根柱合格，
   它**根本不去走** `pickDigColumn` 选的那根。所以只改 1 还是不管用 —— 第一次修完跑出来是
   `shaft.standingOn = -8,17 (就近合格柱)`、`forge.away = north`，**要的是 east**。

两处都接上 `JourneyRehearsal.stagedForgeSide` 之后，**排练第一次摆出了 east 模腔**：

```
shaft.stepping.1 = -10, 66, 19 → -7,17 (排练指定了 east 侧，脚下这一柱不在那一侧)
shaft.standingOn = -7,17 (选定柱)      forge.away = east      forge.face = 6, 56, 17 朝 east
```

真 ladder 上这个静态是 `null`（`recon` 里显式清掉），所以攀爬的选柱一个字节都没变。

### ✅ 用 `-PshaftColumn=-8,19` 把真 ladder 那个模腔**逐格复现出来了**

```
rehearsal.shaftColumn = -8,19：井柱被钉死（pickDigColumn 不参与，就地采纳也只认这一柱）
shaft.stepping.1 = -9, 66, 19 → -8,19 (正站在岩浆柱上)     shaft.standingOn = -8,19 (选定柱)
lava.landmark = lavaLake -9, 63, 19（勘测到 72 格源块）      ← 和真 ladder 同一个池子
forge.away = east      forge.face = 4, 56, 19 朝 east       ← 和真 ladder 逐字相同
```

**判据 1 达成**：不是「方位是 east」，是**同一个池子 + 同一根井柱 + 同一个模腔坐标**。
这一级的 east 几何从此**可以按需复现**，不再是随机事件——这是这一轮真正拿到手的东西。

### ⛔️ 但它死在开挖，判据 2/3 没到；而且几何对齐之后**故障还在**

```
forge.landedY = 56                       ← 身体确实下到了井底
forge.carved  = 66/67 格开了，1 格没挖动    carve.stuck = {-2=1}：2, 62, 17
carve.firstStuck = 2, 62, 17=dirt：身体 -5, 63, 19，距 7.3 格，canBreak=false
cell.0.standMissed = 想站 3, 56, 19，停在 -1, 65, 17，距 4, 56, 19 还有 10.49 格
                     （脚下 -1, 64, 17=grass_block）
cell.0.stillShut.1/2/3 = 4, 56, 19=stone：这一格从头到尾没开过
```

身体下到 y=56 之后，**开挖过程中又把自己垒回了地表**（y=63~65），然后隔着 10.49 格去开井底那一格。
真 ladder 同一处几何是 `forge.carved = 67/67 格全开` 并一路走到 `recover6`。

**按上一条指示的判法：几何已经对齐，故障没有消失，所以这就是「够不着的那一半」需要一个不上地表
的办法。** 但那是新的一刀，本轮按边界没有动它——只把它变成了一个**可复现**的红。

### 库存也对齐了，**红一模一样** —— 圆石这条洗清了

只改一个变量（`cobblestone` 64 → **111**，照真 ladder 那趟实测的 `cobblestone.before = 111`），
其余不动，同一根钉死的井柱重跑：

| | 给 64 | 给 111（对齐） |
|---|---|---|
| `cobblestone.before` | 64 | **111** |
| `forge.face` | `4, 56, 19 朝 east` | 同 |
| `forge.carved` | `66/67` | **`66/67`** |
| `carve.stuck` | `2, 62, 17` | **同一格** |
| `cell.0.standMissed` | 停在 `-1, 65, 17`，距 10.49 | 停在 `3, 64, 20`，距 8.12 |
| 结果 | FAIL 8055t | **FAIL 8055t** |

**所以圆石不是原因。** 判据 1 达成（数值进了 `rehearsal.gave` 证据行，连同「哪些差异是故意留的」）。

### 水桶也换成空的了 —— 装水成了，但这一趟**被上游挡住，没测到开挖**

```
rehearsal.gave = … bucket×1 …            bucket.before = 1        ← 和真 ladder 一致
waterFill.hand = minecraft:bucket        waterFill.result = CONSUME
water_bucket = 1                         waterFill.cellAfter = Block{minecraft:air}   ← 装水这一段是好的
lava.arrivedDistance = 4                 ← 之前两趟都是 1
FAIL 5001t：站不到可下挖的柱子上：想去 -8,19，停在 -13, 66, 21
```

**没撞上预料中的那条老红**（`走不到 firstWater`）—— 水装成了。撞上的是**两个布景杠杆打架**：
走完那趟水回来，身体停在离岩浆 **4 格**（前两趟都是 1 格），而 `-PshaftColumn` 把井柱钉死之后
**不许就地采纳别的柱**，`stepOntoDiggableColumn` 的 `MAX_WALK_ATTEMPTS` 次机会用完也没走到 `-8,19`。

**所以这一趟对开挖什么都没说，不能记成「开挖仍红」。**

但它顺带**证实了换水桶这个变量是对的**：那趟水明确改变了身体回来时停在哪
（`lava.arrivedDistance` 1 → 4），而「开挖开始时身体站在哪」正是病灶那个量。

### ⚠️ 差异清单（下一个人从这里接）

| 差异 | 状态 |
|---|---|
| 几何（池子 + 井柱 + 模腔坐标） | ✅ 已对齐：`-PshaftColumn=-8,19` → `forge.face = 4,56,19 朝 east`，与真 ladder 逐字相同 |
| 圆石 | ✅ 已对齐（64 → 111），**且已洗清**：红逐行不变，连 tick 数都一样 |
| 水桶 | ⚠️ 已改成空桶（装水成功），**但开挖仍未测到** —— 被钉柱走位挡在前面 |
| 镐 | 故意不同（两把石镐 vs 石+木，两边都满耐久、都握石镐），无测量支撑，没动 |
| 前十一级的残留 | 排练天然消不掉 |

**第一件事：让钉柱那一步走得到。** 两条路，任选其一但**只改这一个变量**：
`stepOntoDiggableColumn` 给钉柱更多次尝试 / 更长预算；或者让钉柱在走不到时**如实退回**
就地采纳并**大声说自己退了**（那样几何就不再是复现的，读数要按此打折）。
走到之后才谈得上「开挖是不是真红」。

### 上一轮的结论（水桶换之前）：排练仍然不等于爬升，还差两条

真 ladder 在**同一处几何**上是 `forge.carved = 67/67` 并一路走到 `recover6`。几何对齐、库存对齐之后
仍然分道扬镳，说明差别在别处，而**还有两条已知的、故意留下的差异没有排除**：

1. **水桶是满的。** 真 ladder 这一级自己先 `fillWaterAtTheSurface` / 走到 `firstWater` 装水
   （那趟 `waterFill.hand = minecraft:bucket`、`waterFill.result = CONSUME`），**装完水身体在哪、
   走过什么，直接决定开挖开始时它站在哪** —— 而红恰恰是「开挖时身体在地表」。
   **这是下一个要对齐的变量，而且它比圆石更贴近病灶。**
2. 镐给两把（真 ladder 是 `stone_pickaxe 131/131` + `wooden_pickaxe 59/59`，同样满耐久，
   两边开挖时手上都是 `stone_pickaxe`）—— 这条不像有关，先不动。

另外排练**天然**不带前十一级的残留（真爬升是在自己刚挖过的地形里就近开工的），这一条排练消不掉。

### 那条红本身长什么样（留给下一个人，别在前提没对齐前先修）

```
forge.landedY = 56                     ← 身体确实下到了井底
forge.swung   = 63/67 格是就地挥开的（canBreak 已经为真，不用走过去）
carve.firstStuck = 2, 62, 17=dirt：身体 -5, 63, 19，距 7.3 格，canBreak=false
cell.0.standMissed = 想站 3, 56, 19，停在 3, 64, 20（脚下 grass_block），距 8.12 格
cell.0.stillShut.1/2/3 = 4, 56, 19=stone：这一格从头到尾没开过
```

形状是：**够得着就地挥（63/67 都是这么开的），够不着就落回 `mine`，而 `mine` 会把身体垒上地表**，
于是井底那一格从此够不着。真要修，修的是「够不着的那一半」要有一个**不上地表**的够法 ——
**不许用「允许敲门框/放宽判据」那类容忍**，这一级已经四次证明容忍只是把失败搬家。

### ⚠️ `exactRow` 那条分支**仍然没有被执行过**，两趟 east 都死在更早的地方

```
forge.carved = 63/67 格开了，4 格没挖动     carve.stuck = {-2=4}（都在 y=62）
cell.0.standMissed = 想站 5, 56, 17，停在 4, 64, 19（脚下 grass_block），距 8.49 格
cell.0.stillShut.1/2/3 = 6, 56, 17=granite：这一格从头到尾没开过
```

身体**站在地表 y=64** 去挖井底 y=56 的第一格 —— 老一族「`mine` 把身体垒上地表」，
和归档第 1 趟的死因同源。收水（`recover*`）离这里还有三个阶段，**根本没跑到**。

而且这根 `-7,17` 是**排练指定 east 才会选的柱**，ladder 自己未必会选它。

### ⛔️ 更正一条我自己的错误推断：真 ladder 那个 east 模腔**不是另一个池子**

我上一段写过「真 ladder 的 east 模腔来自另一个池子（11 级用掉第一个之后的那个）」。**这是错的**，
而反证就躺在同一份证据 map 里、紧挨着我据以推断的那一行：

```
真 ladder  lava.landmark = lavaLake -9, 63, 19（勘测到 72 格源块）   shaft.standingOn = -8,19 (就近合格柱)
排练       lava.landmark = lavaLake -9, 63, 19（勘测到 72 格源块）   shaft.standingOn = -9,21 (选定柱)
```

**同一个池子。**全部差别是井柱。我是看模腔坐标离得远（`4,56,19` vs `-9,56,38`）就推了个池子出来，
**没去看同一张表里的 `lava.landmark`** —— 和这一轮抓到的另外两次同型。

而且 `-8,19` 是 `dx=+1, dz=0`，即 **r=1**，而 `pickDigColumn` 从 **r=2** 起往外扫 ——
**ladder 真正用的那根柱，这个搜索永远提不出来**。它是靠 `stepOntoDiggableColumn` 的就地采纳
拿到的（`lava.arrivedDistance = 1`，走到离池子 1 格就停下并采纳了脚下）。

**所以「跳过最近的池子」这条下一刀的前提不成立，不要照做。** 要复现的是**井柱**，不是池子、
也不是方位：`-PforgeAway=east` 只能从 r≥2 的环上给出 `-7,17`，那是 ladder 从不去的地方。
本轮因此加了 `-PshaftColumn=x,z`（钉死井柱，`pickDigColumn` 不参与、就地采纳也只认这一柱）。

### 跑过的

| 跑法 | 结果 |
|---|---|
| `-Prehearse=BLAZE_ROD` | **PASS 8123t** —— 穿越 14 段，`fortress.arrivedDistance = 23`，`blaze_rod = 1` |
| `-Prehearse=ENDER_PEARL` | FAIL 15341t —— 穿越 12 段就如实收手，`就地猎`，48/128 格内疣林 0% |
| `:fabric:runJourneyServer` 第 1 趟 | `journey.height = OBSIDIAN`(11)、`stagingCalls = 0`；红在 12 级（`east` 模腔），13/14/15 全 BLOCKED |
| `:fabric:runJourneyServer` 第 2 趟 | `journey.height = PORTAL_KIT`(10)、`stagingCalls = 0`；红在 **11** 级：`走不到 firstWater 64, 62, 60：停在 6, 13, 37`（挖了 36 格深的井，爬不出自己的井 —— 老一族），12 级往上全 BLOCKED |
| `-Prehearse=PORTAL_LIT`（12 级那一刀的回归臂） | **PASS 6723t** —— 模腔又是 `-9,56,38 朝 south`；`frame.cast=10/10`、`frame.obsidian=10/10`、`portal.cells=6/6`，十条 `recover*.result` 全 `CONSUME` |
| `-Prehearse=PORTAL_LIT -PforgeAway=east/south/west/north`（杠杆修好**前**） | 四趟全 **PASS**，但四趟**同一处几何**（见上表）—— 这是「杠杆是空的」的证据，不是四朝向覆盖 |
| `-Prehearse=PORTAL_LIT -PforgeAway=east`（只修 `pickDigColumn`） | FAIL —— 就地采纳短路把它拐去 `north`，证明只修一处不够 |
| `-Prehearse=PORTAL_LIT -PforgeAway=east`（两处都修） | FAIL 8931t —— **但 `forge.away = east` 第一次出现**；死在 `cell.0` 挖不开（身体站地表 y=64 挖 y=56），收水没跑到 |
| `-Prehearse=PORTAL_LIT -PshaftColumn=-8,19`（圆石 64） | FAIL 8055t —— **模腔与真 ladder 逐字相同**（`lavaLake -9,63,19` + `forge.face = 4,56,19 朝 east`）；`forge.carved = 66/67`，死在 `cell.0`（身体 `-1,65,17`，距 10.49 格） |
| 同上，**圆石对齐到 111**（只改这一个变量） | FAIL 8055t —— `forge.carved = 66/67`、`carve.stuck` 同一格、`cell.0` 同样开不了（身体 `3,64,20`，距 8.12 格）。**圆石洗清** |
| 同上，**水桶换成空的**（只改这一个变量） | FAIL 5001t —— `waterFill.result = CONSUME` 装水成了，但 `lava.arrivedDistance = 4`、`站不到可下挖的柱子上：想去 -8,19，停在 -13, 66, 21`。**被上游挡住，开挖没测到** |
| `stagewrightDedicatedServerFabric` ×4 | **VERDICT: GREEN**（226 执行 / 20 skip，ec=0） |
| `check_source_budget.py`、`check_scene_arena.py` | 过 |

⚠️ **`exactRow` 这条分支至今一次都没执行过**：所有回归臂的 `.rise` 都是**矮**的那一种
（`还差 4 排`），而新分支只在**高**的时候才有别于旧行为。新的 `raisedY` 读数打出来了
（`recover8.rise.raisedY = 59/60（…，比要站的排矮 1 排）`），但只是那半。
**所以 12 级这一刀是 `compiled` + `south` 模腔五趟无回归，不是 `backtested`。**

### 下一个人怎么把 `exactRow` 真的回测掉

**几何对齐了**（`-PshaftColumn=-8,19` 逐格复现），**库存也对齐了**（圆石 111，红没变）。
卡在前面的仍是开挖。按顺序：

1. **让钉柱那一步走得到**（见上面的差异清单）。空桶已经上树、装水也成功了，现在挡路的是
   「走完水回来离岩浆 4 格，而钉死的柱不许就地采纳别的」。
2. 走到之后**还红**，才算「够不着的那一半」的真缺陷，那时才谈修法（见上，不许用容忍）；
   **不红**就把「给予量照实测、水桶给空的」一起写成排练的规矩。
3. 开挖过了，才轮得到 `recover6` 那一格去执行 `exactRow`。

**不要再用 `-PforgeAway` 当回测手段**（它只能从 r≥2 的环上给柱，给不出 ladder 用的 r=1），
也**不要去做「跳过最近的池子」**——那条基于我一个已被证伪的推断，见上面的更正。

### 下一个人从这里开始

1. **这一刀是 `backtested`。** 判据 1（`hops` 不再有多段净进展接近零）**两条穿越都观测到了**；
   判据 2（ladder ≥ 14）**没达成，而且这两趟根本没跑到穿越** —— 别拿它们判这一刀。
   要判就跑 `-Prehearse=BLAZE_ROD` 多来几趟，1 趟不是通过率。
2. ## ⚠️ 位移这个量已经骗了三次，第三处还在树上：`JourneyEndRungs.march`（17 级去要塞）

   ```java
   if (flatDistance(at, now) >= WEDGED_UNDER) { march(ctx, rig, leg + 1); return; }
   ```

   `at` 是这一段的起点、`now` 是终点 —— **和穿越修掉的那条一模一样**，摆动同样看不见。
   三次分别是：①「一整段的位移看不见段尾卡死」（attempt 挪 69 格后站死 1203 tick）；
   ②「位移看不见原地打转」（`fortress.hops` 21 段净进 5 格，本轮修掉）；③ 就是这一处，**还没修**。

   16–20 级还没有 staging 配方、ladder 也从没爬到 17，所以按边界这一轮没碰。
   **动 17 级的人第一件事就是把它换成棘轮**（`best - left`，别换成「每段净进」——
   那个方案已经被上面的回放判死了）。
3. **15 级的下一刀是 `20, 41, -23` 那道深渊**，不是刷怪、不是生物群系、也不再是 wedge 判据。
4. **挡路的已经是 10–12 这一段，不是穿越。** 想再看一眼 14 级，得先让 ladder 过得去这一段
   —— 或者干脆用 `-Prehearse=BLAZE_ROD` 判，它一趟只要 7 分钟。
   **进度的说法**：ladder 的**稳定前沿是 11 级**，最好的一趟到过 14；别再拿最好那趟当前沿。
5. **排练的四道防护都还在**（本轮逐条查过）：不同 scene 名（`REHEARSAL_PREFIX = "wd.rehearse"`）、
   自己的 property/task/runDir（`worlddriver.journey.rehearse` / `runRehearsalServer` /
   `runDir 'run-rehearsal'`）、每次布景都进 `JourneyLedger`（verdict 读 `stagingCalls()`，
   和真 ladder 断言为 0 的是同一个计数器）、独立 verdict scene 在 PASS 和 FAIL 两条路上都带
   `staging.calls=N`。新加的 `stagedForgeSide` 也是第四类布景：写入只发生在 staging 步、
   进账本、并且 `recon` 会清掉它。
5. **ChunkMap 那一刀仍是 `backtested`。**

## ⬜ 上一轮的接手点 —— 15 级的闸换了主语：疣林深处存在（48 格内 100%），但走不到；真 ladder 停在 13 级

**这一轮把 15 级的两条候选闸问到了底，答案是「都还没有资格判」，因为身体一次也没有站进过疣林。
新的闸是走路。真 ladder 停在 13 级（NETHER），`staging.calls = 0`，14 级红在穿越 ——
那条红在这一刀之前就以同样的方式红过，同一颗种子、同一处要塞、几乎同一个剩余距离。**

### 第一观察项：ladder 没回到 14 级，停在 13 级 —— 但这一趟不指向 ticket 抖动

```
11 Obsidian   PASS  4408t      12 PortalLit PASS 8567t      13 Nether PASS 153t
14 BlazeRod   FAIL 18339t —— 走不到要塞 272, 0, 304：停在 97, 41, 105，还差 265
journey.height = NETHER(13)   journey.stagingCalls = 0
每一级都是 20.00 tick/s（含 14 级那 18339 tick）
```

**上一轮死掉的 11、12 级这一趟都过了**，所以那两条确实是方差。判据没达成，
**ChunkMap 那一刀的等级仍然是 `backtested`，不许升 `live`**。

但上一轮接手点第 4 条写的「回不去 → 靶子是过区块边界时 ticket 等级的短暂抖动」，
**这一趟的证据并不指向它**，别照着去查：

- 14 级这条红在离传送门 400 格外，`全程无计划 208/18339 tick`（计划几乎一直在）、
  `没进过岩浆`、每一处 `around` 读数都是 `血=20`。这不是刷怪、不是装载、不是掉落。
- 而且它在这一刀**之前**就以同样的方式红过：上上轮第 1 趟
  `fortress.at = 272, 0, 304（401 格）`、11 段只走 143 格、`还差 258`；
  这一趟 24 段、`还差 265`。**同一颗种子、同一处要塞、几乎同一个剩余距离。**
- 所以 14 级至今是 **1/3**（三趟到过它的里只过了一趟），两次红是同一条穿越，
  这一刀两侧各一次。要判这一刀，还是得按上一轮第 3 条：**单级排练对照归档臂**，不是爬级数。

### 14 级那条红有名有姓了：穿越在原地来回，而反卡死判据看的是位移不是进度

```
#1  7,41,4   →39,40    走 43/48 格 374t  还差 358
#2  34,55,37 →66,73    走 45/48 格 258t  还差 313
#3  66,43,68 →98,104   走 43/48 格 491t  还差 270      ← 前三段正常，400→270
#4  96,41,99 →127,135  走 10/48 格 900t  还差 277
…
#9  110,41,116→141,152 走 44/48 格 900t  还差 292      ← 走了 44 格，反而远了 44 格
#10 89,41,77 →119,114  走 42/48 格 900t  还差 251
#11 108,41,114→139,150 走 43/48 格 900t  还差 293
…
#24 65,44,83 →98,118   走 39/48 格 900t  还差 265      ← 21 段净进展 5 格
```

身体在 `(87..113, 41, 77..120)` 这一带**两点来回**，一段往西南走 45 格，下一段再走回来。

**为什么反卡死机制看不见它**：`oneHop` 算的 `moved = hypot(at - before)` 是**位移**，
`MAX_WEDGED_HOPS` / `WEDGED_UNDER = 4` 只在位移小于 4 格时才判这一段「卡住」，
才会减半、才会偏 60°。来回走的身体每一段都位移 40 多格，**永远不会被判卡住**，
于是 21 段问的是同一个问题。这是「一个改不了任何变量的重试」那一族，只是发生在段的粒度上。

**下一轮最便宜的一刀**：把 wedge 判据从「这一段走了多远」换成「这一段离目标近了多少」
（`before` 到目标的距离减去 `at` 到目标的距离）。位移和进度在直线上一样，在绕路时才分家，
而分家的时候正是需要换问题的时候。**这一轮按边界没有碰它。**

⚠️ **别被 `其脚下 dirt` / `granite` 骗成「视图又planning在主世界了」**。那两行来自
`JourneyFlight.blockName(level, …)`，`level = fp.serverLevel()`，是身体真正所在的层；
下界会出现泥土和花岗岩，是因为身体自己用背包里的方块架桥
（`走过的边 {walk=43, bridgePlace=9}`，`blocks.carried = 296 个可放置方块`）。
同一趟 `worldview.disagreements = 0/27 格`。

### 15 级：两条闸都问清楚了，而结论是「还没有资格判」

新加的 `WarpedGrid` 直接问生成器（`getUncachedNoiseBiome`，**不装载区块**），
每 16 格一柱、每柱 8 层高度（`SPAWN_SLICES`，对应 `NaturalSpawner.getRandomPosWithin` 
从地板到地表**均匀**抽 y）。一趟 432 格见方的采样 **9 ms**。

1. **疣林深处存在，而且很深。** 从下界入口 `8, 41, 7` 起：

   ```
   最密的一处在 168, ?, -281（距身体 329 格水平）：那里 48 格内可刷体积疣林占 100%，
   身体现在这处只占 0%；采到的最近一柱 minecraft:warped_forest 在 136, ?, -233（272 格）
   ```

   `136, ?, -233` 正是老的 `findClosestBiome3d` 给的那个点 —— 它按定义就是**生物群系的边界**，
   比最深处近 57 格。上一轮量到的 `warped.arrivedBiome = nether_wastes（停在 130,41,-229）`
   由此解释干净：走到边界点 7 格以内，脚下是哪个群系是掷硬币。
   **「进多深才够」有答案了：这颗种子上存在 48 格内 100% 的位置，不必退而求其次。**

2. **到达判据应该是「份额」，不是「脚下那格」。** 决定刷什么怪的是
   `NaturalSpawner.getRandomSpawnMobAt` 在**随机那一格**上读的 biome，而下界生物群系是三维的、
   y 是均匀抽的 —— 所以该问的是「身边可刷体积里疣林占多少」。两个半径都有意义，各答一问：
   **48 格**（`ENDERMAN_SEARCH`，猎能看见的范围）、**128 格**（刷怪窗口，决定 70 只上限被谁占）。
   脚下那格只是必要不充分：贴着边界站进去也能报 `warped_forest` 而 48 格内只有 10%。

3. **半径 256 在这颗种子上看不见它。** 第一趟（半径 256）`采到的最近一柱在 272 格`，
   全在采样正方形的最外圈。**这一级在这颗种子上永远只会报「就地猎」** —— 历史上两趟正是如此。
   已改 `WARPED_SEARCH_RADIUS = 384`（`MAX_HOPS × NETHER_HOP = 1152` 格的腿，够）。

4. **「上限被猪灵占满」这条还不能宣布。** 至今**没有任何一趟站进过疣林**：
   两趟排练的收工位置 `48 格内可刷体积疣林占 0%、128 格内占 0%`。
   所以「站进去也抢不过猪灵」是一个**前提从未成立过**的结论，不要写进任何地方。
   已经确证的只有素材（从游戏自己的 `NetherBiomes` 读的，不是记忆）：
   `warped_forest` 的 MONSTER 表**只有末影人**（权重 1、成群 4，`addMobCharge(ENDERMAN, 1.0, 0.12)`
   是密度闸，约 8 格一只）；`nether_wastes` 是 ghast 50 / 僵尸猪灵 100 / 岩浆怪 2 / **末影人 1** / 猪灵 15。
   同一次掷骰，疣林里出末影人的概率是荒地的 168 倍，而且疣林根本不出猪灵。

5. **现在的闸是走路。** 带新目标的那趟排练第 1 段就掉岩浆：

   ```
   warped.crossing = 1 段，还差 338 格，第 1 段 t=854 17, 30, 22 —— 从 y=52 掉进去的
   第 1 段之后停手：身体泡在岩浆里（17, 25, 21，血 20）
   warped.ground.1.1 = … onGround=true 而 vanilla 自己那一问=没有；实心接触面积 0.0000/0.36；
                        致命边刹车照 level 重算 … → 该响    而 潜行=false
   ```

   老一族（`该响` 而没响）。归档里那趟基线臂**确实走完过 272 格**到 `130,41,-229`，
   所以这条路不是走不通，是 1/3 左右。

### 顺手修的两条会撒谎的证据行（第 5 条纪律的现场案例）

- **census 的「本级钉着 4 区块 = 64 格」把 128 格计数的上界说成了那个 pin。** pin 是**下限**：
  join 进 `players()` 的身体另有 view-distance 的票，同一趟 census 自己就在 128 格内数出 106 只。
  现在改成直接问「128 格外沿那一格现在装没装」，实测打出 `装着`。
- **我自己第一版 survey 的「256 格内一柱 warped_forest 都没采到」是假的**：它在**没有候选合格**时打，
  而那一趟其实采到了 272 格外那一柱，只是被候选边距排除了。一趟就把它抓出来了。
  现在两处都改了：网格采样半径 = 候选半径 + 邻域半径（候选不再被边距吃掉），
  未命中时报「采到的最近一柱在哪、多远」，"范围内没有可走的" 和 "根本没有" 不再打同一句话。

### 跑过的

| 跑法 | 结果 |
|---|---|
| `-Prehearse=ENDER_PEARL`（survey 第一版，半径 256） | FAIL 7195t —— 没走一步，`就地猎`；证据行撒谎，据此修了两处 |
| `-Prehearse=ENDER_PEARL`（半径 384） | FAIL 8094t —— 目标 `168,?,-281`（48 格内 **100%**），第 1 段掉岩浆，`还差 338` |
| `:fabric:runJourneyServer` | `journey.height = NETHER`(13)，`staging.calls=0`，14 级红在穿越；全程 20.00 tick/s |
| `stagewrightDedicatedServerFabric` | **VERDICT: GREEN**（226 执行 / 20 skip，ec=0） |
| `check_source_budget.py`、`check_scene_arena.py` | 过 |

### 下一个人从这里开始

1. **15 级现在只差一条腿。** 目标已经量准（`168, ?, -281`，48 格内 100% 疣林），
   到达判据已经换成份额，读数会在到达那一刻同时报 48 格和 128 格。缺的是把身体送过去。
2. **最便宜的一刀是 14 级那条穿越的 wedge 判据**（位移 → 进度，见上）。它同时是 14 级和 15 级的腿，
   一处改动两级受益；而且它有现成的对照：`fortress.hops` 那 24 段的振荡就是判据。
3. **ChunkMap 那一刀仍是 `backtested`。** 不要拿爬级数去判它 —— 14 级本身就是 1/3。
   要判它就按单级排练对照归档臂（`-Prehearse=BLAZE_ROD`）。
4. **别宣布「疣林里也抢不过猪灵」**：这个实验一次都没做成过（见上第 4 点）。

## ⬜ 上一轮的接手点 —— 15 级红在「游戏不认为这里有玩家」：身体从不告诉 ChunkMap 它挪过窝

**机制查死了，改动在排练里 A/B 观测到了。真 ladder 两趟只到 10 级和 11 级（上一轮 14 级），
但那两条红都不是刷怪，而且「是这一刀干的」的两条候选机制已被逐条证伪、第 12 级还做了单级
A/B 对照归档臂 —— 剩下的解释是方差。判据「ladder 回到 14 级」本轮没有达成。
改动已提交，读数和这一刀分两笔。**

### 15 级的红不是光照、不是同类上限、不是 14 级那间屋子，也不只是生物群系

`ServerChunkCache.tickChunks` 在调 `NaturalSpawner.spawnForChunk` 之前先问
`chunkMap.anyPlayerCloseEnoughForSpawning(chunk)`。这一问里有两半：一半是拿玩家**实时坐标**算的
128 格距离（这半是好的），另一半是 `DistanceManager.hasPlayersNearby` —— 一张
`FixedPlayerDistanceChunkTracker(8)`，**只有 `ChunkMap.updatePlayerStatus`（join / 换维度）和
`ChunkMap.move` 会更新**。真玩家的 `move` 是移动包处理器 `handleMovePlayer` 每收一个包调一次；
这具身体自己积分位移、不发包，**于是从 `placeNewPlayer` 把它放下的那一刻起，ChunkMap 就一直
认为它站在当初落地的那个区段**。身体走出 8 区块，就走出了这一层唯一会刷怪的窗口，
而且没有任何一行会说这件事 —— 怪照刷，刷在它来的地方。

（传播是切比雪夫：`ChunkTracker.checkNeighborsAfterUpdate` 往 8 个邻居各 +1，所以窗口是正方形，
`max(|dx|,|dz|)` 才是能和 8 比的那个数。）

### 读数：`census(...)` 尾部现在带 `spawnGate(...)`，一句话分开四个世界

```
刷怪三闸：身体在区块 [8, -15]，实体在跑=true，
ChunkMap 认为这一格近旁有 0 个玩家（它记的身体在区块 [0, 0]，差 15 区块；那张表只认 8 区块以内，
而只有 join 和 ChunkMap.move 会更新它）；本层怪物 69/70 只（上限 = 70 × 可刷区块 289 / 289）
```

`enderman.found=0/6` 以前是四个世界共用的一句话（什么都不刷 / 站错生物群系 / 上限被别处占满 /
刷了但在搜索半径外），旁边那行 biome 只替得了其中一个说话。

### 改了什么：`ServerPlayerAvatar.step()` 末尾补上那次 `move`

守卫 `level.players().contains(fp)` **不是防御性的**：`ChunkMap.move` 末端是
`DistanceManager.removePlayer`，它会去 `playersPerChunk` 取「离开的那个区段」的集合并解引用，
没被 place 过的身体那里没有条目，直接 NPE。而填 `ServerLevel.players()` 的回调和调
`ChunkMap.addEntity` 的是同一个回调，两者同进同出，所以这个问法恰好正确。
六条 gate 全部不受影响：它们的身体是 FakePlayer，不在 `players()` 里，这一句直接返回。

### A/B：同一颗种子、同一个起点、同一条 rung 的两趟 `-Prehearse=ENDER_PEARL` 排练

| | 基线 | 带这一刀 |
|---|---|---|
| ChunkMap 记的区块 vs 身体所在 | `差 15 区块` | **`差 0 区块`** |
| `getPlayersCloseForSpawning(身体这一格)` | **0 个玩家** | **1 个玩家** |
| 身体 128 格内的怪物 | 66 只（全刷在「旧窗口 ∩ 实时 128 格圆」那条缝里，离身体 100–128 格） | 106–109 只，上限打满 `72/70` |
| 末影人 | 一趟都没有 | `hunt.1.dry` 里出现 `enderman=1`（128 格内，本 rung 有史以来第一只） |

**两趟排练都 FAIL，但死因换了，而且换的是往下一层的死因**：基线死在「刷怪闸关着」；
带这一刀那趟死在**穿越第 1 段就掉进岩浆**（`warped.hops = #1 走 11/48 格 865t … 入岩浆`，
收工 `泡在岩浆里 17,21,14`），于是就地在 nether_wastes 猎，而那里 `本层怪物 70/70` 全是
猪灵（`zombified_piglin=68, piglin=23, piglin_brute=17`）—— **上限被猪灵占满，末影人再也挤不进来**。
这是这一刀之后 15 级的下一道闸，还没有人碰过。

### 基线那趟还顺手量到一件事：`warped.arrivedBiome = nether_wastes`

`WARPED_ARRIVE_WITHIN = 8` 比生物群系的边界还宽，身体停在离采样点 7 格的地方，
脚下那一格是 `nether_wastes` 不是 `warped_forest`。**「走到了」和「站进去了」是两回事**，
这一级的整套设计（疣林是末影人最密的地）建立在后者上。

### ⚠️ 真 ladder 跑了两趟，都没走到 15 级：**10 级**和 **11 级**（上一轮是 14 级）

| | 第 1 趟 22:16→22:39 | 第 2 趟 22:42→23:05 |
|---|---|---|
| 1–10 级 | 全 PASS，且**比归档那趟都快** | 全 PASS |
| 11 级 OBSIDIAN | **FAIL** 6163t | **PASS** 2820t |
| 12 级 PORTAL_LIT | BLOCKED | **FAIL** 8651t |
| `journey.height` | `PORTAL_KIT`（10） | `OBSIDIAN`（11） |

```
第 1 趟 11 级：走不到岩浆柱：目标 -6,54，停在 29, 61, 77（水平相距 42 格，已重规划 3 次）
              lava.goto.1/2/3 = end=failed:no progress for 1200 ticks (best dist=438 / 442 / 442)
第 2 趟 12 级：走不回模腔：停在 -9, 60, 28，楼梯底 -9, 56, 32 在 y=56 ——
              带着一桶岩浆停在半路，浇下去只会浇进楼梯。第 2/3 段
```

**两条红都不是刷怪，而且「怪物把身体挤住了」这个第一直觉可以一句话排除**：suite 开场就打
`WORLD PINNED … clock=frozen@midnight doDaylightCycle=false doMobSpawning=false`，
只有 14/15 级自己调 `rig.liveWorld(true)` 把 `doMobSpawning` 打开、并在 cleanup 里还回去。
**11/12 级跑的时候整个世界一只怪都刷不出来**，所以这一刀新引入的「世界会刷怪了」在那里没生效。
两条红都落在早就有名有姓的老族里（走位空转、壁龛几何），不是新故障。

**但也不能就此判它无罪。** 上一轮两趟都过了 11、12 级，这一轮两趟一个没过（0/2 对 2/2），
所以下面这两条候选机制被逐条问死了 —— **两条都不用再跑一趟，归档里就有答案**。

#### 候选一「泡泡跟着身体走 = 每 tick 大量区块装卸的开销」——**证伪**

两趟 ladder 每一条 rung 的 `ticks / wallMs` 全是 **20.00 tick/s**，包括两条红的：

```
run1: 03Wood 20.01  05StoneTools 19.29  06Food 20.01  09Iron 20.00  10PortalKit 20.00  11Obsidian(FAIL) 20.00
run2: 03Wood 20.01  05StoneTools 19.30  06Food 20.01  09Iron 20.00  10PortalKit 20.01  11Obsidian 20.00  12PortalLit(FAIL) 20.00
```

服务端全程有富余，红的那两条也一样。（`05StoneTools` 的 19.3 两趟一模一样，是那一级固定的一段
非 tick 工作，不是回归。）**开销假说要求掉 tps，而 tps 一点没掉。**

#### 候选二「以前白拿一大片常驻加载区，现在没了」——**证伪，靠几何**

先把前提量出来而不是假设：日志里写着身体 join 在哪一格 ——
`[realbody] agent-body-1 joined minecraft:overworld at BlockPos{x=56, y=67, z=59}` → **区块 (3,3)**，
出生区块是 (4,3)。改之前那个泡泡就钉在这里：`simulation-distance=10` →
`getPlayerTicketLevel()=31-10=21`，实体 tick 到 10 区块；`view-distance=10` →
`updatePlayerTickets(11)`，11 区块内每格各拿一张 `PLAYER` 票，而那张票的等级就是
`ChunkLevel.byStatus(ENTITY_TICKING)=31`，所以实际的实体 tick 半径不小于 10。

再把每一级实际用到的最远坐标量出来（从 results 里扫）：

```
02Spawn 5 区块   03Wood 1   05StoneTools 1   06Food 5   09Iron 2
10PortalKit 3    11Obsidian 5（-6,26,54）   12PortalLit 5（-9,63,19）
```

（`01Recon` 那个 78 区块是勘测采样点，身体没去过。）

**2–12 级用到的每一格都在出生区块 5 区块以内，也就是同时落在「旧泡泡」和「新泡泡」里面。**
旧的以 (3,3) 为心、实体 tick 半径 10；新的以身体为心，而身体本来就在这片区域里。
换句话说，**这两种配置下，1–12 级碰到的每一个区块的「加载/实体在跑」状态是一样的** ——
「免费加载区没了」这条要成立，得让工作区落到新泡泡外面，而它一次都没有落出去。

#### 候选三：直接把红的那一级单独跑一遍，和归档的对照臂比

12 级有一个**现成的、跑过的、记在树上的对照臂**：上一轮那趟
「对照组（回退代码，单桶排练）**PASS** `frame.cast=10/10`、`portal.cells=6/6`（模腔 `-9,56,38`）」。
本轮拿同一条 `-Prehearse=PORTAL_LIT`、同一颗种子、同一处模腔，**带着这一刀**再跑一遍：

```
wd.rehearse12PortalLit -> PASS (7468 ticks)
forge.face = -9, 56, 38 朝 south      forge.carved = 67/67 格全开
frame.cast = 10/10（丢 0 格）          frame.obsidian = 10/10      portal.cells = 6/6
recover0..9.result = CONSUME ×10
```

**和对照臂逐行相同。** 同一条 rung、同一处几何，改前 PASS，改后 PASS，十次收水全 CONSUME。
ladder run2 那条 12 级红（`走不回模腔：停在 -9,60,28`）**不是这一刀造出来的系统性故障**。

#### 结论

三条候选机制两条被证伪、一条被单级 A/B 反证，剩下的解释是**方差**，而基线支持它：
12 级在更早一轮就是 1/2（`第二趟换了一处模腔…12 级红`），11 级那条
`no progress for 1200 ticks` 是 TODO 里点了名的老族（执行器离开自己的计划再也回不去）。
这一轮 11 级 1/2、12 级 0/1，**样本量本来就分不开**。

**但判据 1（真 ladder 回到 14 级）本轮没有达成，也没有再跑** —— 上面是三条反证加一次单级 A/B，
不是一趟 14 级的 ladder。别把它读成后者。

### 跑过的

| 跑法 | 结果 |
|---|---|
| `-Prehearse=ENDER_PEARL` 基线（只加读数，不改行为） | FAIL —— `0 个玩家 / 差 15 区块`，红因确诊 |
| `-Prehearse=ENDER_PEARL`（带这一刀） | FAIL —— `1 个玩家 / 差 0 区块`，刷怪闸开了，红因换成穿越掉岩浆 + 上限被猪灵占满 |
| 真 ladder 第 1 趟 | `journey.height = PORTAL_KIT`（10），红在 11 级走位 |
| 真 ladder 第 2 趟 | `journey.height = OBSIDIAN`（11），红在 12 级壁龛楼梯 |
| `-Prehearse=PORTAL_LIT`（带这一刀，对照归档的回退臂） | **PASS** 7468t，`frame.cast=10/10`、`portal.cells=6/6`、`recover0..9=CONSUME` —— 与对照臂逐行相同 |
| `stagewrightDedicatedServerFabric` | **VERDICT: GREEN**（226 执行 / 20 skip，ec=0） |
| `check_source_budget.py`、`check_scene_arena.py` | 过 |

### 下一个人从这里开始

1. **这一刀的等级是 `backtested`（排练 A/B 观测到），不是 `live`。**
   真 ladder 上 15 级一次都没在这一刀之下执行过 —— 两趟都被 11/12 级挡在下面。
   提交分两笔：`say which spawn gate is shut when a nether census comes back empty`（读数）、
   `tell the chunk map where a driven body actually is`（这一刀）。
2. **别再跑 `view-distance=4` 那个对照了 —— 它要证的两条机制都已经被证伪**（见上）。
   缩小泡泡只会引入第三种配置（加载半径 5 区块，比改前的 11 和改后的 11 都小），
   A/B 反而不干净。真要继续查，查的是「过区块边界那一刻 ticket 等级的短暂抖动」，
   那要看 ticket 类型和 `PlayerTicketTracker` 的节流队列，不是看半径。
3. **要判这一刀有没有责任，别再靠爬 ladder 的级数**：11、12 级各自都有 50% 上下的老故障率，
   两趟根本分不开。**用单级排练对照归档臂**，本轮 12 级就是这么做的，一趟 6 分钟而不是 27 分钟；
   11 级还没有这样比过（`-Prehearse=OBSIDIAN`），那是最便宜的下一刀。
4. **⚠️ 退化这笔账没有平：ladder 至今没有在这一刀之下回到 14 级，本轮也没有再跑。**
   **下一趟真 ladder 的第一件事就是看它回不回得去。**
   - 回得去 → 方差假说成立，这一刀结案，等级可以从 backtested 升到 live；
   - 回不去 → 靶子是**唯一还没排干净的那条**：过区块边界那一刻 ticket 等级的短暂抖动。
     查它要看 **ticket 类型和 `PlayerTicketTracker` 的节流队列**（`ChunkTaskPriorityQueueSorter`
     异步发票，身体快速移动时前沿的 `PLAYER` 票可能来不及提升到 `ENTITY_TICKING`），
     **不是看半径** —— 半径那条已经被上面的几何证伪了，再调它只是换一种配置。
5. **15 级的下一道闸已经量到了，有两条，都还没人碰**：
   - **怪物上限**：身体站在 nether_wastes 里时 `本层怪物 70/70` 全是猪灵
     （`zombified_piglin=68, piglin=23, piglin_brute=17`）。上限是全层共享的
     （`canSpawnForCategory` 在 `mobCategoryCounts[MONSTER] >= 70` 时对**整层**返回 false），
     所以猪灵占满 = 末影人一只也挤不进来，跟站在哪儿无关。
   - **「走到了」和「站进去了」不是一回事**：`warped.arrivedBiome = minecraft:nether_wastes（停在
     130, 41, -229）`，`WARPED_ARRIVE_WITHIN = 8` 比生物群系边界还宽，身体停在离采样点 7 格、
     脚下那一格根本不是疣林。这一级的整套设计（疣林是末影人最密的地）建立在后者上。
   两条互相咬着：真正站进疣林深处，附近可刷区块才大多是疣林，末影人才抢得过猪灵。
   备选是把 `ENDERMAN_SEARCH`/`SEE_CHUNKS` 放宽到能看见并走到 48–128 格外那几只
   （实测 `末影人 0 只在 48 格内、1 只在 128 格内`）。

## ⬜ 上一轮的接手点 —— 真 ladder 爬到 14 级（BLAZE_ROD）；12 级 2/2，13/14 级第一次执行

**新高：`journey.height = BLAZE_ROD`（第 14 级），`journey.stagingCalls = 0`。**
在此之前真 ladder 最高只到 12 级。两趟都跑完全程，没有中途收手。

| | 第 1 趟 | 第 2 趟 |
|---|---|---|
| 12 级 PORTAL_LIT | **PASS** 7881t | **PASS** 7951t |
| 13 级 NETHER | **PASS** 175t（**首次执行**） | **PASS** 175t |
| 14 级 BLAZE_ROD | FAIL 6665t（**首次执行**，穿越掉岩浆） | **PASS** 7567t |
| 15 级 ENDER_PEARL | 没跑到 | FAIL 7916t（末影人一只都没刷） |
| `journey.height` | NETHER | **BLAZE_ROD** |

两趟的 12 级都是 `forge.face = -9, 56, 38 朝 south`、`frame.cast = 10/10（丢 0 格）`、
`frame.obsidian = 10/10`、`portal.cells = 6/6`，十个 `recover*.result` 全是 `CONSUME`，
一条 `frame.lost.*` 都没有。

### 改了什么：`riseToTakeItBack` 不再拿高度回答射线的问题

上一轮点名但没验证的那条，**这一轮先用归档的 results 文件证死了，没有再花一趟**：

- 那趟的 `recover0..5` 每一条都打了 `.fromHere`（`fillFrom` 在「同一条 `SOURCE_ONLY` 射线看得见
  源块」时打的行），而 `recover6` 打的是 `.spot`（看不见那一支）。两处问的是**同一个函数、同样的
  参数**，而且是 `then.run()` 同一 tick 里前后脚调用，中间没有任何能挪动身体的东西。
- 所以第一个早退（`visibleSourceNear != null`）**没有**触发；两个早退只剩高度那个。
  全run 一条 `.rise` 都没有，和这个推断一致。

死因的几何也从同一份归档里读出来了：第 6 格浇的是 `4,59,18`，它的水在旁边的
`4,59,19`，浇筑把身体留在 `3,58,18` —— 正好是 `wantY`，但**柱错了一格**，
于是射线得斜着从刚浇成黑曜石的那一格旁边挤过去，挤不过去
（`recover6.aimsAt = 4, 59, 18 Block{minecraft:obsidian} 源块=false（想瞄 4, 59, 19）`；
`standToFill` 从**格心**重问同一格也否了：`射线停在 Block{minecraft:obsidian}=1`）。
对的柱是水正后方的 `3,·,19`，射线是轴对齐的；`standToFill` 给不出它，因为它只返回**已经有地板**
的格，而 `3,57,19` 是空气 —— `raiseColumn` 不要求地板（射线闸自己问），`JourneyRamp` 把地板垒出来。

改动就一条：删掉 `if (here.getY() >= wantY) return;`，看不见水就去换柱，够不够高由 `raiseTo`
自己判。`.rise` 那一行现在会说清楚是「还差 N 排」还是「高度已经够了 —— 差的是柱」。

### ⚠️ 这一刀自己的分支两趟都没执行过

**必须写下来**：两趟的模腔都落在 `-9, 56, 38 朝 south`，那处几何**不产生**「对的高度、错的柱」
这个状态 —— 两趟里 `.rise` 只出现在 `recover8`/`recover9`，说的都是「还差 4 排 / 还差 3 排」，
即改动前就存在的那条路。出问题的那趟模腔是 `4, 56, 19 朝 east`，模腔位置每趟随机，这两趟没抽到。

所以这一刀的等级是 **compiled + 两趟真 ladder 无回归**，**不是 backtested**。
判据 1（`recover*` 不再死在「对的高度、错的柱」）**没有被观测到**，只是**没有复发**。
下次抽到 east 那类模腔时，去 results 里 grep `高度已经够了` —— 那一行出现且随后
`recover*.result=CONSUME`，才算真的验过。

### 13 级：`bottomOfThePortal` 第一次执行就成了

```
portal.found = -10, 57, 38    stand.at = -10, 58, 38    stand.in = Block{minecraft:nether_portal}
arrived.at = 6, 41, 4         underfoot = Block{minecraft:obsidian}
scaled.expectedXZ = -2,4（漂移 8 格）    175 tick
```

对照上一轮那趟：`stand.in = air`，八条腿全停在同一格。**这一次身体站进的是传送门方块本身**，
两趟都是 175 tick。`advancement.enter_the_nether = not-earned`（服务端 avatar 成就那半的老账，
本级判据不看它）。

### 14 级：第 1 趟掉岩浆，第 2 趟打出棒子

**第 2 趟（PASS）**：13 段穿越，`fortress.arrivedDistance = 18`，离地 4 次；
`spawner.at = 226, 64, 281`，`stoodAt` 距刷怪笼 2.0 格；
`room.placed = 106 格（墙 68，顶 38）`，`refused = 0`、`stillOpen = 0`、`ranOut = 0`；
`blaze.appeared = 2 只（等了 0 tick）`，五场架 119–254 tick，**手里是 `stone_pickaxe`**
（排练那两趟是 `iron_sword`），`最高离地 -0.4 ~ 0.2 格`；
`rods.perKill = 0,0,0,0,1`，`blaze_rod = 1`。

**第 1 趟（FAIL）**：`fortress.at = 272, 0, 304（401 格）`，11 段只走了 143 格，
`还差 258`，第 11 段 `t=474` 从 `y=42` 掉进岩浆 `95, 29, 115`，收工时泡在
`96, 23, 115`（血 20，`expanded=1` 就是这个原因）。那一 tick 的读数是已知那一族：
`实心接触面积 0.0000/0.36`、`onGround=true` 而 vanilla 自己那一问说没有、
`致命边刹车照 level 重算 … → 该响` 而 `潜行=false`。**这是 14/15 级的账，不是这一刀的。**

第 2 趟的穿越也不干净：`全程无计划 2373 tick`，第 5、6 段各 900 tick **一格没挪、全程无计划**，
靠第 7 段的「偏 60°」重问才走出去。

### 15 级：第一次跑到，红在刷怪不在移动

```
身边 48.0 格内一只末影人都没有，6 轮都等了也没等到 —— 0 只在 48 格内、0 只在 128 格内；
128 格内怪物共 2 只 {blaze=2}（远处那个数只在已加载区块里算数，本级钉着 4 区块 = 64 格）；
本层 level.players() 里有 1 个玩家，所以这不是 isNearPlayer 的问题
```

它自己已经把 `isNearPlayer` 排除了，指向刷怪条件（光照、脚下方块、同类上限、
或者 14 级围的那间屋子把刷怪点堵死了）。**这一级还没有人查过。**

### 下一个人从这里开始

1. **12 级还欠一次真正的验证**（见上「⚠️」）。别把 2/2 读成「验过了」—— 验的是没回归。
2. **15 级**：末影人刷不出来。先量清楚是钉的 4 区块太小、还是屋子/光照堵死了刷怪点。
3. **14 级第 1 趟那条穿越**：从 `y=42` 走进岩浆，`该响` 而没响。上游真凶更可能是
   `离计划最远 40.13 格` 那一族 —— 执行器离开自己的计划再也回不去。
4. **12 级的两条老账仍在**：`cast9` 最后一次尝试会走掉刚垒的台阶；干地上的塔停摆
   （`JourneyRamp` 只是让它不再是唯一的路，塔本身没修）。

## ⬜ 上一轮的接手点 —— 真 ladder 第一次点亮传送门（12 级）；两趟 1/2，新红点在收水

**判据 3 达成过一次**：真实 ladder 一趟走到 `journey.height = PORTAL_LIT`（第 12 级），
`journey.stagingCalls = 0`，`frame.cast = 10/10（丢 0 格）`、`portal.cells = 6/6`。
这是这一系列里第一次；前两趟真 ladder 都停在 `OBSIDIAN`（11 级）。

**但它是 1/2，不是稳的。** 第二趟换了一处模腔（`forge.face = 4,56,19 朝 east`，
第一趟是 `-9,56,36 朝 south`），12 级红，`journey.height = OBSIDIAN`。
**别把 12 级当既得**，也别拿两趟的格号互相对照 —— 模腔位置每趟都不同。

### 那一环：壁龛里没有落脚点，一块砖填不了，而塔在这块地上垒不起来

`standBehind` 的 `.noStand`、浇筑的落脚搜索、收水的落脚搜索，三处对同一类格子
说过同一句「垫不了：… 撑不住 —— 一块砖会悬空」。壁龛是从岩石里掏出来的空腔，
除了最底一排，每一格底下都是空气：一块砖没有依托，**要的是一段楼梯**。

**先查了「为什么不用现成的塔」，答案是它在这块地上不工作。** 逐字复现两趟真 ladder，
身体站在干地上、`onGround=true`、手里握着方块：

```
cast6.lift#7.climb.1        = -9,57,36 above=air onGround=true water=false
cast6.lift#7.climb.1.stalled= stuck (no Y gain in 60t — out of blocks?)
cast6.lift#7.climb.1.stock  = minecraft:cobblestone ×130
cast6.lift#7.gained         = 1/2 block(s)
```

六十 tick、六轮「跳→放」，库存一块没动。**它唯一涨到的那一课是泡在水里的那一课**
（`climb.0 … water=true`）—— 水里身体会浮，涨不涨高度和有没有放下方块无关。
这也解释了**为什么单桶排练一直是绿的**：排练的壁龛早一格就淹了，身体比真 ladder 高一排。

### 改了什么

**`JourneyRamp`（新文件）**：从落脚格往下搜一条「每级挪一格、抬一排」的路，
一级一块砖，用 `useItemOn` 手工放（`JourneyStairs.placeInto` 那条路，本级早就量过它管用：
`cell.4.step … → 站得住了（cobblestone）`），然后身体**走**上去 —— 不跳，不依赖 `onGround`。
放之前四问都问完：在壁龛里、脚头都空、**六邻有能贴的实心面**、不是下井楼梯的一级、不是源块。

- **「六邻有能贴的实心面」是量出来的必要条件**，不是形式：下井楼梯在后墙上切了个豁口，
  `cell.8.ramp.step.1 = -9,57,36 垫不上（六邻没有能贴的实心面）`，后墙那格 `-9,57,35`
  正是 `stair.14` 挖开的。
- **身体先站到楼梯旁边再放砖**：`isUnobstructed` 不许把方块放进身体所在的格。
  第一版是「走一级放一级」，于是身体正好站在下一块砖的位置上：
  `cell.6.ramp.step.1 = -8,57,37 垫不上（…），身体 -8,57,37` —— 挡路的就是身体，
  而那一行怪的是墙。
- **落脚格由射线选，不是由「身体在哪」选**。`liftInPlace` 原来就地垒，眼睛就落在**身体**那一柱：
  真 ladder 2026-08-16 在 `x=-9` 垒、目标在 `x=-8`，斜线擦过它自己两排前浇的黑曜石
  （`picks=-8,58,38 obsidian → 落进 -9,58,38`）。现在优先「目标正后方、低一排」那一格
  —— 只有它能让背板那一枪是水平的 —— 再退回 `raiseColumn`。
- **`raiseColumn` 现在要求落脚格是身体能站进去的**。它以前只问「站在那儿看得见背板吗」，
  一格填满圆石也满足：`wet.9` 的楼梯把顶级垫在 `-10,59,37`，正是 `cast9` 要站的格，
  于是 `cast9.lift` 选了它并报「被 cobblestone 占着」。
- **塔仍然挂在楼梯后面兜底**（它在自家水淹的壁龛里救过场），楼梯到不了才交给它。
- **`tidyTheAlcove` / `clearPourLine` / `litterAt` 三处都跳过楼梯格**。它们清的是
  `MineProcess` 顺手垒出来的柱子；楼梯是**下一桶要站的地板**，扫掉它等于把最上一排重新变成够不着。

**第二刀（第 13 级）**：`nearestBlock("nether_portal")` 返回的是**最近**那格，不是站得住那格 ——
传送门方块没有碰撞，所以除了最底一格，每格的地板都是另一格传送门。哪格最近取决于上一级
把身体留在哪，而 12 级现在收工在自己修的楼梯上、高三排。真 ladder 第一趟量到：
`portal.found=-9,58,36`、`stand.at=-9,58,35`、`stand.in=air`，八条腿「走进去」全停在同一格，
而失败信息怪的是传送计时器。现在走门柱最底那一格。

### 跑过的

| 跑法 | 结果 |
|---|---|
| 对照组（回退代码，单桶排练） | **PASS** `frame.cast=10/10`、`portal.cells=6/6`（模腔 `-9,56,38`） |
| 单桶排练（本轮代码） | **PASS** 同上，同一处模腔 |
| 真 ladder 第 1 趟 | **`journey.height = PORTAL_LIT`**，12 级 REACHED，`stagingCalls=0` |
| 真 ladder 第 2 趟 | 12 级红，`journey.height = OBSIDIAN`，模腔换到 `4,56,19 朝 east` |
| `stagewrightDedicatedServerFabric` | **VERDICT: GREEN**（226 执行 / 20 skip） |
| `check_source_budget.py`、`check_scene_arena.py` | 过 |

**排练不是这一刀的判据**：对照组和本轮都 10/10 —— 排练从来没复现过这个故障（见上「水里那一课」）。
唯一算数的是 ladder 那两趟。

### 下一个人从这里开始

1. **新红点是 `recover6`，不是浇筑。** 第 2 趟的 `cast6` **浇成了**（`cast6.lift.laid=2/2`、
   `rampedY=59/58`、`cast6.result=CONSUME`、`picks.1 → 落进 4,59,18`）—— 这一刀在那一趟也生效了。
   死在下一步收水：

   ```
   recover6.spot        = 没找到能看见源块的落脚点，退回 Near(4,59,19,2)
   recover6.frameOnLine.3 = 4,59,18 obsidian 挡在眼睛和 4,59,19 之间，但它是门框格 —— 不敲
   recover6.frameStuck.3  = 门框挡着 4,59,19，而且没有别的落脚点看得见它（身体 3,58,18，一处都没验过）
   recover6.miss.3        = 这一次没装上（water_bucket 0→0）；射线停在 4,59,18 obsidian
   FAIL: 装不到 minecraft:water_bucket
   ```

   **机制已经指名，但还没验证**：`riseToTakeItBack` 的短路是
   `if (here.getY() >= wantY) return;` —— **只问高度，不问柱**。身体确实已经在 y=58，
   但在 `x=3,z=18`，而水在 `4,59,19`；要看见它得站在 `3,59,19` 那一柱。
   楼梯把身体留在了「对的高度、错的柱」，于是抬升整个跳过了。
   **动它之前先证明这条路真的执行了**（`recover6.rise.*` 一行都没有，所以是两个早退之一，
   另一个是 `visibleSourceNear` 说看得见 —— 两者要分开，别照我这句就改）。

2. **第 13 级的修法没在真 ladder 上验过。** 第 1 趟死在 13 级（就是上面那条），修完之后
   第 2 趟根本没爬到 13 级。**下一趟绿的 12 级才是它的第一次执行。**

3. **楼梯在高处（n≥3）经常修不出来**，会退回塔。真 ladder 第 1 趟：
   `water8.lift.noFlight = -9,60,35 修不出楼梯：这一格自己的垫脚 -9,59,35 垫不了：六邻没有能贴的实心面`。
   壁龛五格宽两排深，中间几柱的背面（门框的内部格）会被前面几格挖开，于是没有能贴的面。
   要让 n=4 也修得出来，得允许**实心楔形**（一柱从地板填到需要的高度，靠下面那块贴），
   而不是只允许「一级一块砖」的斜梯 —— 那是另一刀，代价是十来块圆石。

4. **有一处「计划时能贴、动手时贴不上」**（真 ladder 第 1 趟
   `recover9.rise.ramp.step.1 = -9,57,34 垫不上（六邻没有能贴的实心面）`，而 `placeable`
   在计划时通过了）。计划和动手之间隔着一段走路，世界会变。没查，**先确认它不是身体自己站进去了**
   （那一行会打身体位置：`身体 -8,57,34`，和目标格是面邻居，不是同一格）。

5. **12 级的两条老账还在**（都没修）：`cast9` 那格最后一次尝试会走掉刚垒的台阶；
   干地上的塔停摆 —— 第 2 条现在有解释了（见上），但塔本身没修，只是不再是唯一的路。

## ⬜ 上一轮的接手点 —— 14 级排练拿到烈焰棒了；真 ladder 仍卡在 12 级（12 级已解，见顶部）

**去要塞那条穿越解掉了，而且 14 级排练收工是 `REACHED`。** 身体走完 397 格里的 383 格、
走进要塞、围出屋子、打了 **7 场真架**、收 **1 根烈焰棒**。
判据 1（`fortress.flight.*` 不再以「泡在岩浆里」收工）**达成**；判据 2（两条排练都跑）**达成**。
**判据 3（真实 ladder ≥ 14）未达成，而且拦路的不是 14 级**——真 ladder 这一趟收在
**第 11 级 OBSIDIAN**，12 级 `PORTAL_LIT` 红，于是 13、14 级连跑都没跑（`BLOCKED`）。
见下「真实 ladder 一趟」。

**但 15 级那条穿越还是掉进岩浆，而且是第三种机制**（见下「15 级那一跤」）：同一份代码、
同一个起点，往西北走 272 格去疣林的那条路，第 1 段就从 y=52 掉了 23 格。
**所以「穿越修好了」只对量到的那一条路成立**，别写成通解。

### 穿越：103/397 → 382/397，八轮排练，每一轮只动一件事

| 轮 | 树上加了什么 | 走到 | 段数 | 怎么收的 |
|---|---|---|---|---|
| 5 | 只加读数（复现基线） | 103/397 | 3 | 第 3 段**起跳**，坠 11 格入岩浆 |
| 6 | + 对角上跳加价 | 105/397 | 3 | 第 3 段**走出去**，坠 13 格入岩浆 |
| 8 | + 「贴岩浆边走」加价 | 102/397 | 3 | 同上，**落点和第 7 轮同一格**（见「证否」） |
| 10 | + 立足面刹车（放在 drive 收尾里） | 105/397 | 3 | 掉下去那一 tick 是**早退分支**，收尾根本没跑 |
| 11 | 立足面刹车提到单出口包装里 | 卡在湖边，**没进过岩浆** | 11 | 连着 4 段一格没挪，还差 305 |
| 13 | 去掉「贴岩浆边走」加价 | **382/397 —— 到了** | 12 | 走进要塞，`arrivedDistance=15` |
| 15 | + 尸体不算杀（读数修正，不动移动） | **383/397 —— 到了** | 13 | 走进要塞，`arrivedDistance=14` |

第 11、13 轮之间只差那一条加价，结果从「卡死」翻成「走到」。**这是各一趟，不是通过率**，
但它至少说明那条加价不是白花钱的中性改动，去掉它是对的。
第 13、15 轮是**同一份移动代码的两趟**，都到了要塞（15 格 / 14 格），
所以「走得到」现在有两次独立观测，不是一次运气。

**第 15 轮仍然摔了三跤，而且守卫是对的**：`fortress.crossing = … 离地 3 次 … 没进过岩浆`。
三跤全是「走出去」那一族（`实心接触面积 0.0000/0.36`、`这一 tick 速度 y=-0.155（不是起跳）`），
落差 3 / 7 / 7 格、落在 netherrack 上，人没事。立足面刹车按定义**不该**在这里开火——
它照 level 重算出来的邻格是 `落9 落7 落6`，全在 `SURVIVABLE_FALL = 22` 以下，不是致命落差。
**「摔了但活着」和「掉进岩浆」是两回事**，别把前者当回归。

### 三个理论，读数各判了生死（都是同一份 `<what>.ground.<段>.<i>` 读出来的）

**1. 「`onGround` 迟一拍」是真的，但它只属于「走出去」那一族，而且不是引擎的锅。**

```
上一 tick：位置 (84.380, 41.0000, 79.187) 速度 (0.107, -0.078, -0.005) onGround=true
  vanilla 自己那一问（脚下 0.0784 格内有碰撞吗）=没有（和 onGround 不一致）
  实心接触面积 0.0000/0.36
```

`Entity.move` 是先按这一 tick 的位移求碰撞、再写 `onGround`：竖直分量在**起点**被地面截断
（于是 `onGround=true`），水平分量接着把身体带出了那块地。所以一个 tick 结束时，
身体确实可以「悬在空中而 `onGround` 报 true」——**这是 vanilla 的语义，不是漏洞**。
结论是那句「别信 `onGround`，直接测立足点」：**任何读 `onGround` 再去问「当前这一格底下有什么」
的守卫，问的是两个不同时刻的事。**

**2. 复现三次的那一跤是起跳，不是漂移。**

```
上一 tick：位置 (79.950, 41.0000, 81.963) onGround=true 潜行=true
  vanilla 自己那一问 = 有（和 onGround 一致 —— 它没有迟一拍）
  实心接触面积 0.1180/0.36   支撑行 y=40 [79,40,81=netherrack(0.1180) 其余三格 air]
  这一 tick 速度 y=0.333（是起跳，不是走出去的）
```

+0.333 是 0.42 那一跳过了一个 tick 的重力。**身体当时已经在潜行**（致命边刹车看见了岩浆湾），
而 vanilla 的潜行从来不管跳跃：`maybeBackOffFromEdge` 只截断向下的位移。
计划那一步是 `diagUp`，而 `diagUp` 的执行没有任何对位闸——正对的 `stepUp` 有
（`ascendJumpReady`：对齐 + 靠近才起跳），对角的一条都没有。所以第一刀砍在这里。

**3. 「一格宽的岭」是这条路的性质，不是那一跤的机制。** 新读数按面积算：
第 3 段 `93/198`、后来 `218/467`、`384/551` 个着地 tick 的实心接触面积不足 0.09
（= 只踩住一个角）。地形不是「峡谷上的一道岭」，是**岩浆湖伸出来的一根根手指**：

```
立足面 5×5（y=40，行 z=77..81，列 x=82..86）：#####/##!!!/#!!!!/!!!!!/!!!!!
（#=实心 ~=岩浆 !=空的且下面有岩浆 .=空的且下面没岩浆）
```

### 那一环：致命边刹车在岩浆湖边一声不吭，有两个独立原因

刹车的谓词**是真的**——照 level 重算，八个邻格里七个是 `岩9`（九格下就是岩浆）：

```
致命边刹车照 level 重算（foot=84,41,79，自己这一格的地板 air（撑不住，而这一格守卫从来不问））：
  1/0=岩9 -1/0=岩9 0/1=岩9 0/-1=岩9 1/1=岩9 1/-1=岩9 -1/1=岩9 -1/-1=底 → 该响
```

而身体没有潜行。两个原因，都是量到的：

- **它只往前看。** `WalkerTickDrive` 里那个动态闸（`gapAhead` / `offCentre`）探的是
  「朝 wp 方向 0.6 格那一格」。掉下去前一 tick：`gapAhead=false`（朝 wp 那格是 netherrack）、
  `offCentre=0.17`，而身体自己的鞋底踩住 **0.0000/0.36**。**往前看的探针看不见「已经站空了」。**
- **它在 drive 收尾里，而 `tickInner` 有几十个提前返回。** 第 10 轮那一跤的 tick 打的是
  `walker 那一 tick：没走到 drive 收尾（提前返回的分支）`——当时穿越正在挖路
  （`走过的边 {…downBreak=6, traverseBreak=7}`）。这正是当初把 `strideFloorGuard`
  从 `tickInner` 里提出来的同一课。

### 改了三件事（都在树上，gate 绿）

1. **`price the diagonal ascent out of a nether crossing's routes`** —— 下界两级把
   `BotConfig.pathfinderDiagAscendPenalty` 设成 100（`generousPathfinding` 的 pin 负责还原，
   六条 gate 看不见）。是**加价不是禁用**：有正对的上法就走正对的，只有对角一条路时照走。
   生效证据不是那行配置，是 `flight` 行尾的 `走过的边 {…}` 里 `diagUp` 消失了。
2. **`pin a body that is grounded on almost nothing beside a lethal drop`** ——
   `Walker.tick()` 的单出口包装里新增 `footingGuard`：着地 + 干的 + 鞋底实心接触 < 0.18
   （`WalkerGeometry.soleOnSolid`，按 vanilla 的 1e-7 外扩枚举，不是旧 footprint 的 1e-4 内缩）
   + 四邻有致命落差 → 潜行、取消跳跃、取消冲刺。**计划中的下坡豁免**——这一条是第一版漏掉的，
   gate 当场用三条红点名：`wd.descent`「crouch-deadlock」、`wd.bridgeDescend`「descending bridge
   wedged (sneak ledge-guard?)」、`wd.descentYaw` 甩到 2463°。补上豁免后 gate 绿。
3. **`record what a fall's launch was standing on, and whether onGround agreed with the world`**
   + **`publish the walker's drive and jump tags…`** —— 纯读数。上面每一条结论都出自它。

### 试过并被证否的一条：给「贴着岩浆边走」的路加价

给穿越的 Intent 挂一条 `CostModifier`，目的地每有一个「空的且下面是岩浆」的邻格就 +10。
**第 7 轮（无）和第 8 轮（有）从同一格起跳**（84.432 vs 84.380，41,79），路线一格没变——
因为身体根本不是从路线上掉下去的，它是**先离开了路线**（那一跤时离计划节点 4.37 格）。
按「不上未量到收益的改动」的规矩已经撤掉；第 11→13 轮的对照见上表。

### 一行撒谎的读数，和它修完之后的真实战果

第 13 轮走完全程之后打出 `blaze.killed = 8 只`、`fight.2..8 = 打死，用了 0 tick`、
`rods.perKill = 0,0,0,0,0,0,0,0`、`blaze_rod = 0`。**那八杀是假的**：`blazesNear` 没有滤
`isAlive`，被打死的怪还要留二十 tick 的死亡动画，于是后七轮每轮都对着**同一具尸体**
「0 tick 打死」。真实战果是 1 次真打架、0 根棒——而烈焰人本来就是 50% 掉 0 根，
「八杀零掉落」看着像 `killed_by_player` 那道闸，「一杀零掉落」只是运气。

**滤了 `isAlive` 重跑（第 15 轮），两个疑点一起结掉了**：

```
blaze.killed = 7 只      fight.1..7 = 打死，用了 54/52/52/55/52/52/57 tick，手里 iron_sword
rods.perKill = 0,0,0,0,0,0,1        blaze_rod = 1   dropsNearby = 1 根掉在地上没捡
fight.N 最高离地 = 0.9 / 0.2 / 0.2 / -0.0 / 0.2 / 0.2 / -0.0 格
room.placed = 104 格（墙 68，顶 36）  refused = 2  stillOpen = 2  stockAfter = 68
spawner.at = 226, 64, 281（距身体 38 格）  stoodAt 距刷怪笼 1.0 格
rehearsal.outcome = REACHED — 在自己围出来的屋子里打死 7 只烈焰人，收 1 根烈焰棒
```

七场架每场 52–57 tick，长度一致，**不再有一场 0 tick 的**——尸体已经不算数了。
`killed_by_player` 那道闸**没有问题**，7 杀掉了 2 根（1 拿到手 1 落地），符合 50% 的掉落率。
**「最高离地 ≤ 0.9 格」**：封顶小屋在真下界里也压住了烈焰人的高度，竞技场那条测量成立。

**一条没追的读数**：`advancement.obtain_blaze_rod = not-earned`，而身上确实有 1 根棒。
成就那一半是另一条已知的坑（服务端 avatar 的成就支持只补了一半，NeoForge 那半还红），
本级的判据不看成就，所以没动它。要动之前先量 `CriteriaTriggers.INVENTORY_CHANGED` 有没有对
`JoinedBody` 触发，别先信。

### 15 级那一跤：从**踩满**的实地上起跳，飞出自己刚搭的桥

```
warped.hops = #1 8,41,7→31,-35 走 13/48 格 900t（无计划 4t），离地 1 次最深 23 格，入岩浆，还差 276
上一 tick：位置 (16.176, 52.0000, 18.545) onGround=true 潜行=false
  vanilla 自己那一问 = 有（一致）   实心接触面积 0.3600/0.36   ← 踩得满满的
  支撑行 y=51 [15,51,18=netherrack(0.0746) 16,51,18=cobblestone(0.2854)]  ← cobblestone 是它自己浇的桥
  这一 tick 速度 y=0.333（是起跳）
  致命边刹车照 level 重算： 1/0=岩20 1/1=岩20 其余=底 → 该响
  走过的边 {walk=32, bridgePlace=5, stepUp=7}   离计划最远 19.21 格
```

**立足面刹车对这一跤无能为力**：鞋底是满的 0.36，它按定义不会开火。这一族是
「**在致命落差旁边起跳**」，而不是「站空了」。想连这一族一起堵，就得在致命边旁边直接禁跳，
代价是**台阶也上不去了**（`stepUp` 靠跳），八成会红一片 gate —— 那是要单独量的一刀，不是顺手改。

另外这一段还打出 `离计划最远 19.21 格`：执行器会离自己的计划节点十九格远。
这个数以前没有人量过，它可能才是这一族的上游。

### 15 级的其余部分（第一次真的跑起来了）

就地猎的读数全是新的，而且**刷怪那一半是好的**：`128 格内怪物共 107 只
{zombified_piglin=63, piglin_brute=17, piglin=26, enderman=1}`。
`enderman.found=4/6`、`killed=1/6`、`pearls=0`；三场 `没打死，用了 4000 tick`。
**「打不死」和「找不到」现在分得开了**，下一刀该看那三场 4000 tick 里战斗循环在干什么。

### 真实 ladder 一趟（2026-08-16 16:52→17:24，32 分 34 秒，`:fabric:runJourneyServer`）

```
journey.height = OBSIDIAN（第 11 级）   journey.stagingCalls = 0   journey.floor = PORTAL_KIT
rung.PORTAL_LIT = FAILED — 一只桶浇十块黑曜石（水搬着走）
rung.NETHER / BLAZE_ROD / … = BLOCKED — 上游阶段未达成
rung.BED = FAILED — 尚未脚本化
```

**和上一趟真实 ladder 的前沿一模一样（都是 OBSIDIAN）——没进，也没退。**
本轮上树的三件事（对角上跳加价、立足面刹车、尸体不算杀）**没有回归的证据**：
立足面刹车全程只响了 3 次，`sole 0.1799 / 0.1515 / 0.1544 < 0.18`，
位置 `-14,66,21`、`-10,66,19`、`-13,66,21` —— 全在岩浆湖**地表边缘**，那里的致命落差是真的；
而这一趟 7 次装岩浆、6 次收水**全部 CONSUME**，装桶那一半没被它碰坏。

**12 级里有一处是真的前进了**：`forge.carved = 67/67 格全开`、`carve.stuck = 无`。
上一趟真实 ladder 是 `48/67`、19 格挖不动、身体把自己垒回了地表。
「够得着就地挥」那一修**在真 ladder 上也成立**，不只是排练里成立。

**红点挪到了模腔最上一排 —— 正是 `TODO` 早就点名的那个几何**：

```
cell.6.stillShut.3 = -8, 59, 38=gravel：这一格从头到尾没开过，不是被填上的
cell.6.noStand = 够不着：身体 -9, 56, 35 距 4.36 格（>2）；
                 站不了：-8, 59, 37 脚下 -8, 58, 37=air 不是地板；-8, 58, 37 脚下也是 air；
                 垫不了：-8, 57, 37 脚下 -8, 56, 37=air 撑不住
                 —— 一块砖会悬空，这一格要的是楼梯不是一块砖
```

**注意和交接说明里的「13/20」对不上。** 树上自己的记录（本节和下面那一节）里，
最近两趟真实 ladder 的前沿都是 **OBSIDIAN = 11 级**。14 级的排练是 `REACHED`，
但排练的前置全是布景摆的（`staged.0..13`），**排练过了不等于 ladder 数字会变**。
下一个人别按 13 起算。

### 下一个人从这里开始

0. **拦路的是 12 级，不是 14 级。** 想让 ladder 的数字动，第一刀必须落在
   `PORTAL_LIT` 模腔最上一排那个「要的是楼梯不是一块砖」。14 级那条穿越已经通了，
   但它在真 ladder 上一次都没被执行过。
1. **15 级那条穿越是下一刀**，它是这轮唯一还以「泡在岩浆里」收工的一条，
   而且机制和 14 级那两族都不一样（从**踩满 0.36** 的自建桥上起跳）。
   要堵它就是「致命落差旁边禁跳」，而 `stepUp` 靠跳——**这一刀会红一片 gate，得单独量**。
   先量的应该是它上游那个 `离计划最远 19.21 格`：身体为什么会离自己的计划节点十九格远。
2. **两段 900 tick 的空转还在**（第 15 轮第 4、7 段：走 1/48 和 4/48 格，
   `离计划最远 28.22 / 27.20 格`，都在 `96..97, 41, 100..109` 附近，`收工那一刻：没有 ——
   跑满 tick 被叫停的`）。这不致命（后面几段绕过去了），但它和第 1 条是同一个上游：
   **执行器会离开自己的计划，然后再也回不去。**
3. **对角上跳加价现在可能是多余的**：立足面刹车会在鞋底 0.118 那一跳上取消跳跃，
   而那正是第 5 轮那一跤。这是最便宜的一次 A/B，去掉它跑一趟就知道。
4. **穿越里仍然有一次「走进岩浆」**（第 13 轮第 8 段 `t=260 139,44,170 —— 走进去的`，
   不是掉进去的），身体自己走出来了。走进岩浆和掉进岩浆是两件事，别混。
5. **两条读数的时序要注意**：`潜行=` 和 `walker 那一 tick：` 读的是 walker 上一次发布的值。
   驱动的 `tickAll()` 和 StageWright 的 harness 都挂在 `END_SERVER_TICK` 上，两者先后没有钉死，
   所以这两个字段最多差一个 tick。上面的结论都建立在「连着几十 tick 都没潜行」上，不受这一 tick 影响。

## ⬜ 上一轮的接手点 —— 下界那 400 格路：现在快了三倍、不再空转，但仍然掉进岩浆（已解，见顶部）

**本轮没有跑真实 ladder，级数仍是 13。** 判据 3（ladder ≥ 14）**未达成**，而且是**故意不跑**的：
排练里穿越仍然以「泡在岩浆里」收工在 103/397 格，14 级不可能过，一趟 27 分钟只会重新量到 13。
判据 1（`fortress.flight.*` 不再泡在岩浆里收工）**未达成**；判据 2（两条排练都跑）**达成**，见下。

### 15 级（`ENDER_PEARL`）也跑了，FAIL，卡在**同一段穿越**

`warped.hops = #1 8,41,7→31,-35 走 9/48 格 263t（无计划 3t），离地 1 次最深 11 格，入岩浆，还差 264`。
走不到疣林 → 就地猎 → 六轮 `hunt.N.dry`，`enderman.found=0/6`。
**「就地猎」这条退路是真的没用**：`hunt.census` 说 128 格内怪物 **108 只，一只末影人都没有**
（`{zombified_piglin=62, piglin=29, piglin_brute=17}`）。所以 15 级不是猎不动，是**站错了生物群系**，
而站对生物群系要的正是这段穿越。`level.players=1`、`doMobSpawning=true`，刷怪那一半是好的。

**而且这一趟的坠落是三次里最干净的一次，它否掉了「计划里的空中动作」这个总解释：**

```
warped.fell.1.0 = #1 t=60 从 12,41,-2 上一 tick 就已经没有支撑格了（[11,40,-2=air 12,40,-2=air]），
                  onGround 却还报 true → 落进岩浆 11,30,-2，坠 11 格
                  计划下一格 11,41,-1[walk]（计划第 1/6 步）：那一格=air，
                  其脚下 11,40,-1=netherrack（撑得住），距身体 0.97 格水平、dy=1
```

**一步平地 `walk`，目标格 0.97 格远、站在实心 netherrack 上**，身体照样掉了 11 格进岩浆。
所以「禁跳跃」是对那一次（`parkour3`）的正解，**不是坠落的通解**——
`walk` / `fall4` / `diagUp` / `parkour3` 四种边上都掉过，共同点只有一条：
**上一 tick 四格立足点全是空气，`onGround` 却报 true。**

### 三轮排练，同一颗种子、同一个起点（8,41,7），改动是逐条加上去的

| 轮 | 改了什么 | 走到 | tick | 无计划 tick | 怎么收的 |
|---|---|---|---|---|---|
| 1 | 只加读数（行为不变） | ~110/397 | 3072 | **1203/1582 + 1203/1203** | 三次 attempt，最后两次在 `66,43,67` 一格没挪 |
| 2 | 分段航路（48 格一段） | 17/397 | 900 | 105/900 | 第 1 段 `parkour3` 跳空，坠 24 格进岩浆 |
| 3 | 再加「穿越不许跳」 | **103/397** | **1029** | **3 / 2 / 12** | 第 3 段走出一格宽的立足点，坠 11 格进岩浆 |
| 4 | 同上（提交后复跑） | **105/397** | 1458 | 3 / 2 / 90 | **和第 3 轮同一格、同一条边**：`80,42,80[diagUp]` |

**第 3、4 轮是复现，不是抖动。** 前两段逐字相同（`走 43/48 格 336t（无计划 3t）`、
`走 44/48 格 224t（无计划 2t）`），第三段都在 `79,4x,8x` 那道一格宽的岭上走空，
都掉进 `80,30,8x` 的岩浆。**下一个人不需要靠运气去等这个几何**——它每趟都在。

**第 1 轮那 2402 次搜索是新的、决定性的读数**：日志里 `search-begin owner=goto start=66, 43, 67`
出现 **2402 次**，两分钟里每 tick 烧一整个 A* 预算，一条路都没采纳。旧证据分不出这个和「下界地形难走」——
`JourneyFlight` 现在数「身上没有计划的 tick」，一行就把它们分开了。

### 已经量到、别再重新推导的三条

1. **「一个 400 格的远坐标」不是这个寻路器能回答的问题。** 三次 attempt 走了 15 / 69 / 0 格。
   分段之后同一段地形上，第 1、2 段各走满 43/48、44/48，无计划 tick 从 1203 掉到 3 和 2。
2. **旧的重试守卫量错了对象。** 它问「这一次 attempt 挪了没有」——attempt 2 挪了 69 格，
   然后在终点站了 1203 tick，于是通过了检查，attempt 3 从同一格问了同一个问题。
   **一整段的位移看不见段尾的卡死**；一小段的位移就是同一个问题。
3. **执行器在计划里的空中动作上落不到计划的那一格。** 三次坠落，三次都有读数：

```
计划下一格 50, 50, 49[fall4]     距身体 1.66 格 → 落到 51, 44, 52，坠 9 格
计划下一格 16, 53, 22[parkour3]  距身体 2.90 格 → 落进岩浆 14, 29, 23，坠 24 格
计划下一格 80, 42, 80[diagUp]    距身体 1.46 格 → 落进岩浆 81, 30, 80，坠 11 格
```

   加上 15 级那次 `walk`，一共四次，**四个落点格都是真的实地**（`16,52,22=netherrack（撑得住）`），
   所以**计划没有说错世界**。跳跃那一条已经修掉（穿越不许 `Capability.PARKOUR`），但它**不是通解**：
   - `fall4` 那次：上一 tick 四格立足点**一格实心都没有**，`onGround` 却报 true——就是上一轮
     记的「`onGround` 迟一拍」，**两轮在同一格 `50,53,50` 复现**，方向也一样（该往 z=49 走，去了 z=51）。
   - `walk` 那次（15 级）：同一个形状，**而且边是最普通的一步平地走**。
   - `diagUp` 那次：身体站在**四格里只有一格实心**的刀刃上斜着上一格，走掉了。

   **所以下一刀的靶子是「`onGround=true` 而四格立足点全空」这个状态本身**，不是某一类移动。
   `JourneyFlight.footprint()` 读的是 `floor(box.minY − 0.02)` 那一排；在断定这是引擎的锅之前，
   先证明这不是**读数自己的行**——同一 tick 把 `box.minY`、`fp.getY()`、`fp.onGround()`、
   `level.noCollision(box)` 一起打出来，四个数里必有一个说得出为什么。
   ⚠️ 这正是本仓库第四问：**这份自检问了哪些格、没问哪些格。**

### 下一刀的候选（都有证据，但都还没量到「改了就不掉」）

- **`strideFloorGuard` 把岩浆当安全落点。** 它的判据是「脚下 lethalDepth 格内有没有底」，
  而 `isPassable(lava)=true`、`isWater(lava)=false`，所以一条「11 格空气 + 10 格岩浆 + 底下基岩」
  的柱子被判为**安全**（有底）。它自己的注释说阈值是「当前血量下的致命性」，而掉进岩浆在任何血量下都致命。
  ⚠️ **但第 3 段那次坠落它救不了**：身体的 stride 格 `80,41,80` 是 netherrack（不可通过），
  守卫在 `!world.isPassable(strideCell)` 那一行就返回了。**改它之前先量它会不会开火**，
  否则就是一条点名了不执行的补救。
- **岩浆自救仍然不存在。** `hazardBlockingARetry` 的判断是对的（泡在岩浆里再走一次只会得到同样的答案），
  但「把身体弄出来」这件事一行代码都没有。身体是无敌的，所以这是**移动僵局**不是死亡：
  四邻全是 `isHazard` → `expanded=1`。手写步骤能做（身上有 128 个方块），但那是新的一环。
- **一段泡进岩浆之后会把这一段剩下的预算走完**（第 3 段：t=234 入岩浆，跑满 471 tick 才收）。
  `settle` 的谓词里加一条「在岩浆里就收工」能省几百 tick，不影响正确性。

### 12 级还剩的两条（都没修，都不该在 14 级之前追）

- 干地上的塔停摆：`cast6.lift#5.climb.1.stalled = stuck (no Y gain in 60t — out of blocks?)`，
  而 `.with` 说手里有 124 块圆石。
- `cast9` 那格的最后一次尝试会把刚垒好的台阶走掉。

### 本轮跑过的检查（都绿，别再重跑一遍才动手）

- `./gradlew stagewrightDedicatedServerFabric` → **VERDICT: GREEN**（226 执行 / 20 skip）。
  两条红都是**既有的可选**哨兵：`wd.vineOverWaterClimb` 和 `wd.serverEscapeSealedShelter`
  （后者源码里就是 `.withRequired(false)`），`canaryMustFail` / `canaryMustTimeout` 是框架自己的。
- `./gradlew :common:test`、`python scripts/check_source_budget.py`、
  `python scripts/check_scene_arena.py` 全过。
- 主干那三处改动（`Walker.pathMove()`、`BotState.ProcessSlot.pathNode/pathMove`、
  `IntentProcess` 发布它们）**没有读者在主干里**，是纯读数；gate 绿就是这一点的证据。

## ⬜ 上一轮的接手点（读数已被本轮取代，结论仍然有效）

**12 级的收水解掉了，真实 ladder 第一次爬到 13 级（NETHER），`staging.calls=0`。**
14 级（BLAZE_ROD）因此第一次真正跑起来，红落在下界的行走上，不在传送门上。

### 真实 ladder 两趟（2026-08-16，同一份代码 `671cb85`）

| 趟 | 爬到 | 12 级 | staging.calls |
|---|---|---|---|
| A | 11（OBSIDIAN） | FAIL：第 7 格 `cast6` 浇不到指定格 | 0 |
| B | **13（NETHER）** | PASS：`frame.cast=10/10（丢 0 格）`、`portal.cells=6/6` | 0 |

**判据「ladder ≥ 12」达成过，但 12 级在真梯上是 1/2，不是稳定的。** 别把 13 级当既得。
两趟的模腔在同一处（`forge.face=-9,56,38`），所以这一次格号可以对比 —— A 趟停在第 7 格，
上一轮那趟停在第 10 格，**这不是我这一刀造成的**：A 趟从头到尾没有一行 `pinnedFallback`、
没有一行 `afloat`（`recover0..5` 全 CONSUME、`drain.0..5` 全排干），红落在**我没碰过的代码路径**上。

### A 趟那一环（新的，没修）：不钉柱的 `cast6.lift` 在干地上一块砖也没垒

```
cast6.lift#5.climb.1          = -9,57,36 above=air onGround=true water=false
cast6.lift#5.climb.1.with     = minecraft:cobblestone ×124
cast6.lift#5.climb.1.stalled  = stuck (no Y gain in 60t — out of blocks?)
cast6.lift#5.climb.1.state    = onGround=true inWater=false y=57.00
cast6.lift#5.gained           = 1/2 block(s)   → cast6.liftedY = 57/58
```

**站在地上、身上干的、顶上是空气、手里 124 块圆石，塔一课都没起。** 这不是浮水那一族
（`water=false`），也不是 `holdPlaceable` 那一族（`.with` 说方块已经在手上，没有 `.hand` 行）。
`walkerFallback` 接手之后也只涨了 1 格，落在别的柱（`endedIn=-9,37`），于是 `cast6` 的射线
三次都停在自己下面那格黑曜石 `-8,58,38` 上。**这一环没有量到因，只有症状。**

### 14 级的读数（B 趟，全新，第一次有）

```
arrival.at   = 7, 41, 4（地表门 -9,56,37，按 8:1 应在 -2,4）    fortress.at = 272, 0, 304（水平 400 格）
fortress.flight.1 = 走了 18/400 格；end=failed:no route progress after 5 consecutive searches
                    — goal unreachable from here (best dist=3622) 在 14,52,21
fortress.flight.2 = 走了 90/383 格；y 52→18；离地 2 次，最深 13 格；
                    收工在 75,18,87 支撑[75,17,87=lava …] 泡在岩浆里；首次入岩浆 t=509
fortress.fell.2.1 = #2 t=492 从 74,42,86 上一 tick 就已经没有支撑格了（[74,41,86=air]），
                    onGround 却还报 true —— 支撑在别处，或者 onGround 迟了一拍
                    → 落进岩浆 75,29,87，坠 13 格
fortress.around.2 = 脚下=lava 身处=lava 头顶=lava …（expanded=1 是这个原因）
fortress.noAttempt= 第 2 次之后不再重试：身体泡在岩浆里，再走一次只会得到同样的答案
```

三件事各自独立，别混成一件：

1. **`no route progress … best dist=3622`** —— 400 格的直线目标在下界是问不出来的（熔岩海、峡谷）。
   这是「A coordinate is not a plan」的下界版：要的是**分段航路**，不是一个远坐标。
2. **`onGround` 迟一拍**（两次坠落都是这条），身体因此在没有支撑的格上继续走，然后掉下去。
   注意 `fallDistance` 对这具 FakePlayer 恒为 0，**别拿它判有没有掉**。
3. **掉进岩浆之后没有自救**。`fortress.noAttempt` 的判断是对的（同一条指令只会得到同一个答案），
   但「把身体从岩浆里弄出来」这件事目前**根本不存在**。

### 12 级还剩的两条（都没修，都不该在 14 级之前追）

- **A 趟那一环**（上面）：干地上的塔停摆。
- `cast9` 那格的最后一次尝试会**把刚垒好的台阶走掉**：`cast9.lift#9 gained=3/3、liftedY=59/59`，
  然后 `placeFluid` 重来一遍，`aimThatLandsIn` 在 y=59 那一柱返回 null（线被自己刚浇的
  `-9,60,38` 黑曜石挡住），于是退回 `standToPour`，选了一个**比身体低三排**的落脚点走下去。
  `.fromHere` 短路本来就是为了防这个，但它只在「从这里浇得到」时生效。

## ✅ 12 级收水：钉柱的爬升现在会交给 walker 兜底（不许挖）

### 接手时给的判断「升排的时机错了」—— 对了一半，两条具体走法都被证否

- **「浇这一格的水之前就站上去」活不过取岩浆那一趟上下楼。** 单桶流程是
  浇水 → 上楼装岩浆 → **下楼** → 浇岩浆 → 收水，回程把身体放回壁龛地板：
  `cast8.returnedY = 57（楼梯底 y=56）`、`cast8.lift#7.fromY = 56`。水之前垒的高度到不了收水。
- **「先取岩浆、再浇水，一次抬升管三步」在单桶下不成立。** 一只桶不能同时装水和岩浆——
  这正是本级「一只桶浇十块」的立足点（见 `portalLit` 的类注释）。
- 所以那句「lift 一直在兼职」是对的，但**唯一能活到收水的抬升槽位**只有「回程之后、浇岩浆之前」
  的 `cast{i}.lift`，而基线（929d24b）恰好每次都要那一次 lift。射线闸修通之后
  `cast8.fromHere.3` 从 y=56 就地浇成，那次兼职的抬升就没了 —— 这一段接手时说的完全对。

### 真正的那一环：全级唯一钉柱的爬升，被禁止用唯一在水里管用的办法

对照组（HEAD 未改动，单桶排练）：

```
recover8.rise#3.climb.0        = -9,56,36 above=air onGround=false water=true
recover8.rise#3.climb.10.afloat= -11,56,36 浮在水里，8 次都没落地；脚下 0 格内有实底
                                （-11,55,36 granite），水深 1 格，身体 y=56.00，头 air 脚 water
recover8.rise#3.pinnedShort    = 没垒到 y=60 —— 不交给 YLevel 兜底，那条路不认柱子
recover8.rise#3.gained         = 0/4 block(s)
recover8.frameStuck.3          = 门框挡着 -9,61,38，而且没有别的落脚点看得见它
FAIL: 装不到 minecraft:water_bucket
```

**同一趟、同一格，不钉柱的抬升 afloat 之后靠 `walkerFallback` 上去了**（上一轮真梯）：
`cast6.lift#6` 和 `cast8.lift#7` 都打了 `climb.10.afloat = -11,56,36`，都接着
`walkerFallback=true`、`toY=58`、`gained` 2/2 和 2/3。所以那条兜底不是「更差的上法」，
**它是水里唯一管用的上法**，而钉柱是全级唯一被禁止用它的爬升。

而且轮到那一行时**已经没有柱子可保**：偏柱修正（`e884486`）早就 adopt 过两次，
`endedIn=-11,36（就是那一柱）` 而射线是照 `-9,36` 算的。**这条拒绝比它保护的东西活得久。**

### 改法（`671cb85`，只动 `climbFrom` 的钉柱分支）

不再 `recordExit` 收工，改为 `Goal.YLevel` + **`NoBreak`**，并打 `.pinnedFallback`。
NoBreak 的理由和 `c42367d` 一样：壁龛里唯一高到能挡路的东西，就是本级自己在浇的门框。

### 三趟单桶排练 + 真梯，全部拿到

| 趟 | frame.cast | portal.cells | `recover8.rise` gained | frame.lost |
|---|---|---|---|---|
| 对照（改前） | 9 格浇成，FAIL 在收水 | —— | **0/4** | 无 |
| 排练 A | 10/10（丢 0） | 6/6 | 4/4（pinnedFallback） | 无 |
| 排练 B | 10/10（丢 0） | 6/6 | 4/4（pinnedFallback） | 无 |
| 排练 C | 10/10（丢 0） | 6/6 | 4/4（pinnedFallback） | 无 |
| 真梯 B | 10/10（丢 0） | 6/6 | 4/4（pinnedFallback） | 无 |

### 判据 1 的措辞要修正，别照抄

`afloat` **没有消失，也不应该消失**：塔在水里就是起不了步（`TowerProcess` 的 READY 相要
`onGround`），那是引擎侧，这一轮按「先写死步骤」的规矩没碰。变的是 `gained`：0/4 → 4/4。

**`onGround` 为什么对一具站在花岗岩上、`y=56.00`、水深一格、头顶空气的身体是 false，仍然没有量到机制。**
同一格 `-9,56,36`，`recover9.rise#4.climb.0` 读 `onGround=true water=true` 而
`recover8.rise#3.climb.0` 读 `false` —— **所以它是时序，不是几何**。别把它当已知，
也别拿「水里就不 onGround」当解释去设计下一刀。

## ✅ `climb.*` / `exit.*` 现在带调用方 tag，一趟十几次爬升不再互相覆盖

键名从 `climb.<课号>.*` / `exit.*` 变成 `<调用方>#<第几次爬升>.climb.<课号>.*` / `<调用方>#<第几次>.*`。
序号不是多余的：`liftInPlace` 同一个 tag 会爬两次，返程卡住时同一个 tag 会爬三次。

一趟单桶排练里立刻看见了以前看不见的东西 —— **六次爬升的对照表**（旧键名下会挤成一行）：

```
cast4.returnStuck3#1  不钉柱  gained 3/3   改过柱，用了 walker 兜底
water5.lift#2         不钉柱  gained 1/1
cast6.lift#3          不钉柱  gained 2/2   改过柱，用了 walker 兜底
cast8.lift#4          不钉柱  gained 2/3   改过柱，用了 walker 兜底
recover8.rise#5       钉柱    gained -1/2  pinnedLost，一块没垒
cast9.lift#6          不钉柱  gained 3/3
```

浇筑那一组读数有**同一个毛病**，本轮一并修了：`.fromHere` / `.stand` / `.picks` / `.before`
三次 approach 写同一批键，最后一次赢。真梯那趟因此打出 `cast9.fromHere` 来自第 3 次、
`cast9.stand`/`cast9.picks` 来自第 1 次，读起来像「短路生效了却还是走了路」。现在都带 approach 号。

## ✅ 钉柱：查过了，它没有防住任何在案的故障 —— 改成「优先，并且离开时报出来」

**先查了它是为防什么**（commit `dc51131`，2026-08-15，「keep a pour's tower in the column its aim was
computed for」）。来由是 run 43 第十格：`water9.raisedY=60/60` 压在一条 `driftKept` 上，浇筑随后
连着三次从 `-7,60,37` 打同一条错射线 ——「Height alone made a failed raise read as a solved one」。

三条都查了：

1. **有没有真的把桶浇进错格？没有。** 浇筑那条 `.picks` 闸（「不落在目标格就不花这桶」）是
   `f293fa0`，**2026-08-12 落地，比钉柱早三天**。错射线每次都被拦下，这一级带着诊断红掉。
   钉柱要防的那个害处是**一行会撒谎的读数**，不是一次错浇。
2. **那行读数今天还会撒谎吗？不会。** `raisedY` 报「停在哪一柱、是不是指定柱」，`endedIn` 同理，
   两条都是和拒绝同一批加的，**都不需要那条拒绝**。
3. **「防止塔垒进模腔」是不是钉柱干的？不是。**（这条是接手时给的猜测，**证伪**。）那是
   `ascendByTowering` 的**偏柱修正**，无条件、对钉与不钉一视同仁。钉柱只决定修正失败之后怎么办：
   停（钉）还是改柱（不钉）。

代价是量出来的（见上表）：唯一钉柱的那次 `gained=-1/2` —— **比出发还低一格**，因为修正是往下走进
柱子里唯一的落脚点（`footholdInColumn` 向下找七格），而拒绝又禁掉了本来能把这一格垒回来的塔。
真梯同一趟也打出 `recover8.rise#8.gained=-1/2`，**两处独立复现**。同一趟里三次「改柱」都发生在
壁龛里，`forge.carved=67/67`，**一条 `frame.lost.*` 都没有** —— 模腔没被吃。而那次钉柱失败的调用方
照样把水收回来了（`recover8.result=CONSUME`），**这是第三次**：装水自己会重选落脚点重瞄，
交给它的抬升是提示，不是契约。

所以这不是「放宽一条守卫」，是**改正一条错的要求**。改法：钉柱的 climb 也改柱，但那行叫
`driftKeptPinned`，写明这一柱是射线选的。当时留下的唯一拒绝是 **walker 兜底**（`Goal.YLevel` 按构造就
不认柱子，能为了一个高度把身体带出壁龛）—— **那一条也在同一天被读数推翻了，见本文件顶部第二节**：
它的前提是「钉柱的爬升有自己的塔」，而收水那次爬升在满水位下**没有塔可用**。

## ✅ 浇筑：拍板的那条射线，就是真正会发出去的那条

两处，都是同一件事：

1. `aimThatLandsIn` 原来只跑「眼睛→格中心」的**线段** clip 就返回。现在**先 `aimAtBlock` 存角度、
   再用 `aimedAt` 反解验一次**，不一致就换下一个候选（背板 → 地板），并打一行 `.aimForked.N`。
2. `placeFluid` 原来是**先瞄、再 settle 两 tick、再读 `.picks`**。`JourneyFill.scoop` 2026-08-16 已经
   为同一件事改过顺序，浇筑这边一直没改。现在 settle 之后才决定瞄哪、才瞄、才预测、才用。

第 2 条是同几何 A/B，证据在一行里：

```
cast9.fromHere.3 = -9, 57, 36 就地瞄 -10, 60, 39，流体会落进 -10, 60, 38（不走了）
cast9.picks.3    = …（想浇 -10, 60, 38，…，身体 -9, 56, 36）
```

**决定和开火之间掉了整整一格眼高**。改完那一趟十格全部浇成（`frame.cast=9/10（浇成过 10 格…）`）。

接手时给的那条「45° 正对角、射线沿方块棱走」的解释**只是最可能的机制，不是量到的**——两行读数打的
是**格**，那笔算需要身体在格心。真梯本轮没再复现那个几何（`forge.face` 不同），`.aimForked.*` 一行没打。
**不过修法不依赖机制对不对**：拍板的那条现在就是会发出去的那条。

## ✅ 收水的那趟路不许挖穿自己刚浇的门框

`recover8` 的水源在门框里侧，从地板高度看过去中间只隔着门框本身，而 `fillFrom` 的那条
`IntentProcess` 开着 `allowBreak`。排练 3 量到：

```
recover8.spot  = 没找到能看见源块的落脚点，退回 Near(-9, 61, 38,2)
frame.lost.1   = -9, 60, 38 浇成黑曜石之后又没了：现在是 water，丢在「recover8 从 -9, 61, 38 收水」
                 这一步里；身体 -9, 59, 38 距 1.0 格
```

身体正站在它刚敲掉的那格底下。**十格全浇成了，鲁的是取水那一趟**。现在收水（`!lava`）那一腿带
`NoBreak`；装岩浆那一趟不带 —— 它在地表走向湖，离模腔十几格，取消破墙只会让一条正常路走不通。

改完门框保住了（无 `frame.lost.*`），红落在装水上并带全套几何 —— 这是设计要的形状。

## ✅ 竖井换柱：那句「换一根」现在真的换了（定点 scene 验的，不是刷 ladder）

### 病因确认，和上一轮写的一致

`descendByMining` 的 floating 分支打印「这根柱子不干燥，换一根」然后 `ctx.fail`。
**一条点名了补救办法、而代码并不执行该补救的证据行。**

### 已修

`descendByMining` 多一个 `onWetColumn` 回调。第 11 级（唯一在运行期**选**柱的一级）接上：
爬回地面 → 把淹掉的那一柱记进 banned → 换一柱重挖，最多 2 次。

**两处都要认 banned，少一处就是死循环**：`whyNotDiggable` **故意**不查柱子中段
（要求整柱无流体那一版 recon 实测 280 个候选全被否，那叫含水层），所以
`pickDigColumn` 会绕一圈回到同一柱，而 `stepOntoDiggableColumn` 会把它当「就近合格柱」再收下。

柱子是勘测写死的那几级（5/9/10）不传回调，失败消息也**不再提**一个它做不到的补救。

### 验证：`-Prehearse=OBSIDIAN -PwetShaft=true`（新增，本轮的主要产出之一）

第 11 级以前**根本不能排练**——没有布景配方。现在有了：给一个空桶 + 两把石镐，把身体放在
岩浆柱地表旁边（`JourneyRoute.firstLava` 本来就是烘死的，不用跑勘测）。
`-PwetShaft=true` 在下挖满 4 格之后灌一片水，两把锁和 `breakAStair` 一样。

```
shaft.sabotage   = -4, 59, 56 周围 3×3、y=38..60 共 207 格灌成水了
shaft.reColumn.1 = -4, 47, 56 这一柱中段有水，身体浮起来了（脚下 water）
                   —— 爬回 y=63 换第 2 根柱子重挖，还剩 1 次换柱
shaft.column     = -10,51 (岩浆柱偏 4 格)     ← 换掉了 -4,56
shaft.landedY    = 27      tunnel.11 → lava      cast.cellAfter = obsidian
wd.rehearse11Obsidian PASS 6771 tick
```

**判据 1 达成**：`shaft.reColumn.1` 出现**且下挖继续**（换柱之后一路挖到 y=27、装到岩浆、浇出黑曜石）。

### 灌多深这件事本身是个发现（花了三趟排练）

一柱三格：没反应。三宽四深：还是没反应，读数是
`shaft.4 = -4,59,56` 然后 `shaft.5 = -4,50,56 below=-4,49,56 stone`。
**身体在水里是往下沉的**（不会浮），而下挖的 settle 是 60 tick——够它掉 **9 格**。
四格深的水坑，身体一条腿就穿过去落在坑底的干岩上，于是下一趟看到的是**实心**落脚点，
守卫的第一个条件根本没成立过。二十格深才留得住那个状态。

**这也解释了真实 ladder 上它为什么是随机的**：同一场比赛，胜负取决于地下水的底恰好在哪一层。

## ⬜ 12 级：浇筑的落脚点——`raisedY` 短了不是「垒得不够」，是**一块都没垒**

### 上一轮那句「垒了、没垒到（56/60）、还垒错了柱」，第一段是错的

读数链条已经跑全（排练第 9 格，`recover8`）：

```
recover8.rise.raise               = -9, 58, 38 → y=60（在 -9,37 这一柱上垒台阶…钉住这一柱）
recover8.rise.raiseTo.arrivedDistance = 1
climb.0.drift                     = -9, 58, 38 偏离起塔柱 -9,37，先走回去再垒
climb.0.pinnedLost                = -9, 58, 38 走不回指定柱 -9,37 —— 爬升到此为止
recover8.rise.raisedY             = 58/60（停在 -9,38，指定柱 -9,37，不是同一柱）
```

**塔一次都没跑**：钉住的爬升在第 0 课就撞上偏柱分支，走不回去就结束。
`.raise` 那行说「在 -9,37 这一柱上垒台阶」，而**一块砖也没放**。又是同一族的坑。

### 「垒错柱」的来路：`walkToColumn` 不用调用方给的容差判到达

`walkToColumn(…, tolerance, …)` 只把 `tolerance` 传给 `Goal.XZ`，判到达用的是常量
`ARRIVED_WITHIN = 5`。所以 `raiseTo` 写 1、拿到的是 5：`arrivedDistance=1` 是一次**到达**。
本轮把 `raiseTo` 改成要 0，并加了 `.raiseColumnMissed` 明说「不在指定柱上就算到了」。
**`walkToColumn` 本身没动**——它有十几个调用方都在靠那 5 格的松弛，改它是另一件事。

### 偏柱修正的三条缺陷，都改了

1. 它走的是 `Goal.Block(climbColX, at.getY(), climbColZ)`——**和身体同高**的那一格。
   只有平地上这才是对的格子。第 12 级钉的是**空心壁龛**里的走廊柱，那一格是空气下面还是空气，
   根本没有路。现在先在柱子里找**站得住的最高一格**（`footholdInColumn`），报 `driftInto.N`。
2. 那个目标现在是 `Goal.Block` 而不是 `Goal.XZ`。`Goal.XZ` 自报 `ignoresY`，而寻路器的
   descend-tax **只对这类目标生效**——偏偏这里"往下"就是全部的动作。
3. **它只试一次。** 走查器自己的判词是
   `climb.0.driftGoto.1 = end=path-consumed err=null（想去 -9,56,37，停在 -9,57,38）`——
   不是"没路"，是**走完了一条部分路径就报到达**，即 `wd.serverWalkerArrivedShort`，
   这套件里别处（`walkToColumn`）早就是"从新位置再问一次"。现在给 3 次，
   **一格没挪的那一腿立刻收手**（`driftWedged`，不做空转重问）。

顺带：修正这一腿现在**关掉 `allowBreak`**。浇筑阶段这个开关是开着的，
而"挪一下身位顺手把刚浇的门框挖了"正是 `frame.lost.1` 两次的成因。

### 判据 2 **未达成**，而且现在知道为什么了——这是设计问题，不是容差问题

改完之后（排练，同一几何）：

```
climb.0.driftInto.1 = -9, 56, 37（这一柱里站得住的那一格，身体在 -9, 58, 38）
climb.0.driftGoto.1 = end=path-consumed（想去 -9, 56, 37，停在 -9, 57, 38）
climb.0.driftInto.2 = -9, 56, 37（身体在 -9, 57, 38）      ← 第 2 次走到了，没有 .driftGoto.2
climb.2/4/6/8/10/12/14/16.drift = 每一课都偏柱
climb.16.driftWedged.1 = -9, 58, 36 这一腿一格没挪
climb.16.pinnedLost    = -9, 58, 36 走不回指定柱 -9,37
recover8.rise.raisedY  = 58/60（停在 -9,36，指定柱 -9,37）
```

**修正现在真的能把身体带回柱子（第 2 次成功），塔也真的跑了——但净高度还是 0。**
`TowerProcess` 是"在脚下放一块再跳"，落点不钉在任何东西上；壁龛三格宽、全是空的，
所以**每一课都会偏**，修正把身体带回柱子时又带回了柱子里**更低**的落脚点，涨的高度被走回去抵掉。
十七课，58 → 58。

**同一趟里不钉柱的 `liftInPlace` 全部一次到位**：`cast6.liftedY=58/58`、
`water9.liftedY=60/60`、`cast9.liftedY=59/59`。

所以结论是**「垒对柱」用现在这套工具做不到**：钉柱要求"塔的落点在柱内"，
而 `TowerProcess` 不提供这个保证，空心房间里也没有墙帮它保证。
按纪律**不去放宽容差**，把这条交回来：要么给塔一个"落点回到本柱"的能力（引擎侧），
要么承认收水这一步不需要钉柱（`JourneyFill.scoop` 本来就会重新选点重瞄——
这一趟 `recover8.spot = 站 -8, 62, 38 瞄 -9, 61, 38`、`recover8.result = CONSUME`，
**钉柱失败并没有让这一格收不回来**）。两条都要先有证据，别顺手挑一条。

### 真实 ladder 一趟（2026-08-16，改完之后，27 分 27 秒）：**11 级，`staging.calls=0`，持平**

判据 4 **未达成**（还是 11）。**也没有退**——1~11 全绿，第 11 级这趟没挑到湿柱子
（`shaft.column=-4,56`、`shaft.landedY=27`、无 `reColumn`），所以换柱那条**这一趟是对照组，没跑到**。
第 12 级红在**第 3 格**，比上一趟（第 7 格）早，但**模腔位置不同**（这趟 `forge.face=-9,56,33`，
上一趟 z=38），**别当成退步读**——每趟几何都不一样。

死因是一条**新的**，不是本轮碰过的任何一条：

```
cast3.fromHere = -9, 56, 32 就地瞄 -11, 57, 34，流体会落进 -11, 57, 33（不走了）   ← 预测说行
cast3.picks    = -10, 57, 34 granite face=north → 落进 -10, 57, 33
                 （想浇 -11, 57, 33，瞄 -11, 57, 34，身体 -9, 56, 32）             ← 实际打偏一格
FAIL: 射线会把流体放进 -10, 57, 33 —— 没有倒
```

**门闸做对了**（没倒，没有把黑曜石浇进内格）。问题在两条射线又分叉了，而这次**不是身体动了**：
`fromHere` 走 `aimThatLandsIn`（眼睛→格中心的**线段** clip），`picks` 走
`aimAtBlock` 存角度 + `aimedAt` 反解方向。目标在 x 上差 2 格、y 上差 1 格，角度很平，
**float 量化足以让落点挪一格**——上一轮排除掉的那个「量化」嫌疑，在这个更长更平的瞄准上要重新算一遍。
下一刀先量它：把 `aimThatLandsIn` 选中的 aim 存进角度之后**再用同一条 `aimedAt` 验一次**，
不一致就换下一个候选 aim（`target.below()`），而不是直接失败。

其余读数：`forge.carved=66/67`（`carve.stuck` 只剩 1 格 `-7,62,31`，`canBreak=true` 但六邻实心 4/6）、
`stairs.asCut=11 级都完好`、`cast0..2` 三格全 CONSUME、`drain.0..2` 全部排干。

### ⚠️ `climb.*` 这一组读数会互相覆盖，下一个人先修这个

`ascendByTowering` 的证据键是 `climb.<课号>.*`，**不带调用方的 tag**。第 12 级一趟有十几次爬升，
全写进同一批键里，于是结果文件里的 `climb.3.driftInto` 和 `climb.3.driftGoto` 可以来自**两次不同的爬升**
——本轮就打出过一对自相矛盾的（一条说指定柱 -11,36，另一条说想去 -10,56,36）。
`exit.*` 同样：一趟里最后一次爬升的值覆盖前面所有次。
**在拿这组读数做任何推断之前，先把 tag 加进键名。**

### 本轮四条判据的实际状态（别读成三绿一红）

| 判据 | 状态 | 证据 |
|---|---|---|
| 1 竖井 `reColumn` 出现且下挖继续 | **达成** | `-Prehearse=OBSIDIAN -PwetShaft=true`，见上 |
| 2 `raisedY` 报 60/60 且同柱 | **未达成** | 58/60，机制已查实，结论是"钉柱这套工具做不到"，见上 |
| 3 排练 10/10 + 6/6 | **1 趟绿 / 3 趟**（改前同几何 1 趟红） | 第 1、2 趟红在第 9 格，第 3 趟 `frame.cast=10/10 frame.obsidian=10/10 portal.cells=6/6`（8137 tick）。**一趟不是通过率** |
| 4 真实 ladder ≥ 12 | **未达成**，仍 11，`staging.calls=0` | 见上 |

## ⬜ 12 级：装桶已修（四趟全绿），红移到模腔最上一排的落脚点

### 「石镐挖不动黑曜石」这条假设是**错的**（源码级，不是运气）

`ServerPlayerAvatar.breakHold` 在 `faithfulBreak == false` 时直接
`fp.level().destroyBlock(aimTarget, DROP_HARVEST, fp)`，**没有任何工具等级闸**——
它自己的 javadoc 就写着这具身体「harvests obsidian with its fists」。而 journey 的关卡
从不碰 `faithfulBreak`（全 testmod 只有 `wd.serverWalkerDeepslateNoTool` 和
两个 water-bank 关卡翻它）。所以 `recover9.clearedLine.3` 那句「敲掉它」是**真的敲掉了**，
不是白花三次 aim。四格里另外三格「没有任何证据行说得出去向」这件事，因此不是没发生，
而是没人在看。

### 仪器：`auditFrame` / `frame.lost.N`

在每个可能动方块的步骤之后（两次 reopen、tidy、放水、上楼、装桶、下楼、浇前 reopen、
收水、排干）重读所有「已经浇成黑曜石」的格，第一次发现少了就报一条 `frame.lost.N`，
写明**丢在哪一步里**、**上一次它还在是哪一步之后**，外加身体位置、距离、手上拿的、
已浇/现存计数。`frame.cast` 同时报「浇成过几格 / 之后丢了几格」——
「少四格」和「四格没浇成」要的是相反的活。

### 它第一次跑就点名了一条机制：**`mine` 的寻路自己挖穿门框**

单桶排练（rung FAIL 9/10）：

```
frame.lost.1 = -9, 60, 38 浇成黑曜石之后又没了：现在是 air，
               丢在「wet.9 挖开水位格 -10, 61, 38」这一步里
               （上一次它还在，是「cell.9 挖开门框格 -10, 60, 38」之后）；
               身体 -10, 57, 38 距 3.2 格，手上 minecraft:cobblestone；已浇 9 格，现存 8 格
wet.9.noStand = -10, 61, 38 够不着：身体 -8, 57, 36 距 4.90 格（>2）；…垫不了…
```

那一步挖的是**壁龛口上方的水位格**，丢掉的是它下面两排的门框格，而 `-10, 57, 38` **根本不是
壁龛格**——它是门洞自己的内部格。`ServerWorldDriver.mine` = 一个 walker 目标 + 一次挥击，而
walker 在整个浇筑阶段都开着 `allowBreak`，所以一个**没有可走通路**的格子会被它**从模腔里挖一条
路出来**。`standBehind` 只挡住了「有壁龛落脚格」那一半；报了 `noStand` 之后照样挖。

### 已修一条：清视线不许敲门框

`JourneyFill.scoop` 的清线分支先问 `JourneyPortalRung.isFrameCell(wall)`，是门框就记
`.frameOnLine.N` 并改走 `stepOutOfTheFrame`——换一个射线验得过的落脚点（`.stepOut.N`），
换不到就把这次 attempt 花掉并打全套几何（`.frameStuck.N`），**不做原地重问的空转**。
按**坐标**判（十格 RING），不按方块 id——id 判会连带保护无关的黑曜石，也会在这一格被别的
东西占了之后停止保护。

**跑到了**（单桶排练）：

```
recover9.aimsAt        = -10, 60, 38 Block{minecraft:obsidian} 源块=false 液位=0（想瞄 -10, 61, 38）
recover9.frameOnLine.3 = … 但它是门框格 —— 不敲，换个角度再看（身体 -10, 58, 35，眼睛 y=59.62）
recover9.frameStuck.3  = 门框挡着 -10, 61, 38，而且没有别的落脚点看得见它；
                         否决计数 {脚下不实心=350, 落脚格被占=160, 头顶被占=2}
一条 frame.lost.* 都没有
```

**这是设计要的形状**：门框没被敲，失败落在 `recover9` 的装水上并带全套几何，而不是悄悄变成
9/10。但这一级仍然红——收水拿不回来。⚠️ `stepOut` 至今一次都没触发。

### 已修一条：收水之前先站回浇水那一排

`riseToTakeItBack` 在每次 recover 之前，用 bucket 自己那条 `SOURCE_ONLY` clip 问
「现在看得见水吗」，看不见且脚低于水位才升一排。看得见就是 no-op ——所以它动不了那些
本来就成功的格子。

⚠️ **第一版这里踩了本仓库自己的第四问**：它走 `standLevelWith`，而那个方法的闸是
`standToPour`——**收水的事拿浇筑的问题去问**。四桶那趟因此打出了
`recover8.rise = 看不见 -9,61,38 里的水` 然后**一格都没升**（没有 `.raise` / `.raisedY`）。
现在升排是 `raiseTo(..., pouring=false)`，柱子由 `scoopSeesFrom`（`SOURCE_ONLY` clip 打到
水本身）验，不再由 `pourLandsFrom`（`Fluid.NONE` clip 打到背板/地板）验。刚浇完的那一格
正好是两者分叉的地方：浇的问题过得了，收的问题过不了。

### 已修一条：挖模腔格时不许寻路破墙

三处 reopen 改走 `digWithoutTunnelling` —— 挖之前把 `BotConfig.allowBreak` 关掉，挖完还原。
这不会废掉这次挖：`allowBreak` 定的是**走路时破墙的价钱**（`LevelWorldView.breakCost` 返回
无穷），目标格本身仍由 `avatar.breakHold` 在寻路停下后敲，只受 reach + 暴露面约束。
有路的格照开，没路的格现在报 `.stillShut` / `dig.*`，**而不是拿一格已浇的黑曜石去换**。

### 判据仍未满足

四桶 1 趟绿（`frame.cast=10/10（…又丢了 0 格）`、`portal.cells=6/6`），单桶 3 趟全红：

| 趟 | 死在哪 | 是不是本轮的锅 |
|---|---|---|
| 1 | 第 0 格 `cell.0.noStand … -9,56,36 被 gravel 占着 canBreak=false` | 否，在本轮改动的**上游**（见下面「壁龛没挖开」那条） |
| 2 | 9/10，`frame.lost.1` | 是本轮点名并已修的那条 |
| 3 | `recover9` 装不到水 | 门框保住了（无 `frame.lost.*`），卡在下面这一刀 |
| 4 | `recover9` 装不到水（**同一格 `-10,61,38`，同一个挡路格 `-10,60,38`**） | 同上；`frameOnLine`+`frameStuck` 又触发一次，仍无 `frame.lost.*` |

**一趟不是通过率，四趟也不是。**但第 3、4 趟是**同一处、同一坐标的复现**，不是抖动 ——
这一处值得直接修，不需要再刷排练去等它。⚠️ `stepOut` 至今仍一次都没触发：
`frameStuck` 那条路上 `standToFill` 报「一处都没验过」，直接走了 `spendTheBucket`。

### ⬜ 下一刀：`visibleSourceNear` 和真正的瞄准**不是同一条射线**

单桶排练里这两行自相矛盾：

```
recover9.fromHere = -10, 58, 35 已经看得见源块 -10, 61, 38（够得着），不走过去了
recover9.aimsAt   = -10, 60, 38 Block{minecraft:obsidian} 源块=false 液位=0（想瞄 -10, 61, 38）
```

- `visibleSourceNear` 用的是「眼睛 → 方块中心」这一条**线段** clip（`level.clip(eye → atCenterOf(c))`）；
- 真正用桶时是 `WorldDriverJourneyScenes.aimedAt`：**同一个眼睛**出发，方向取
  `Vec3.directionFromRotation(fp.getXRot(), fp.getYRot())`，长度 `BUCKET_REACH`。

**读源码之后要修正我自己上面那句话**：`aimAtBlock` 也是从 `(getX(), getEyeY(), getZ())` 指向
格**中心**的，所以两条射线**名义上是同一条线**。能让它们分叉的只有两件事，而且原来的读数
**分不出是哪一件**（两边打印的都是**格**，一格宽一米）：

1. **float 量化**：`aimAtBlock` 把角度存成 `float`，trace 再从角度反解方向，于是不是精确对准中心；
2. **身体在两问之间动了**：`scoop` 是「先 `aimAtBlock`，再 settle 两 tick」（必须settle——
   `pick()` 从上一 tick 的朝向起线），两 tick 的下落/浮起会让存下来的角度**从一个身体已经离开的
   位置**去瞄。这一条和本仓库已经栽过的两个射线坑是同一族。

**读数已跑，已点名：是「身体动了」，不是量化。** `eyeNow(rig)` 挂在 `.fromHere` 和
`.aimsAt` 两行末尾，打印连续眼睛坐标（两位小数）+ yaw/pitch。单桶第 4 趟打出来：

```
recover9.fromHere = -10, 58, 35 已经看得见源块 -10, 61, 38（够得着），不走过去了；
                    眼睛 -9.38/60.16/35.64 朝 yaw=4.50 pitch=1.87
recover9.aimsAt   = -10, 60, 38 Block{minecraft:obsidian} 源块=false 液位=0（想瞄 -10, 61, 38）；
                    眼睛 -9.40/59.62/35.61 朝 yaw=2.38 pitch=-25.14
```

**眼睛 y 从 60.16 掉到 59.62，差 0.54 格**——量化只会差 1e-5 量级，所以是身体在两问之间动了。
算一遍就闭合（`atan2` 手算，不是推断）：

| 从哪个眼睛算 | 到 `-10,61,38` 中心的 pitch |
|---|---|
| y=60.16（`fromHere` 那一刻） | **−25.09** |
| y=59.62（`aimsAt` 那一刻） | −33.03 |

存下来的是 **−25.14** —— 和**旧**眼睛那一列对得上，和新的对不上。把 −25.14 从 y=59.62 的眼睛
打出去，到门框平面时 y=**60.98**，正好落在 `-10,60,38` 这一格里，就是 `aimsAt` 报的那一格。

再补一刀：`60.16 − 1.62 = 58.54`，**不是整数格底**——`fromHere` 那一刻身体悬在半空还在下落；
`59.62 − 1.62 = 58.00`，两 tick settle 之后落定了。对照组（同一趟的 recover0..8）眼睛 y 差
0.00~0.19，只有 recover9 差 0.54，也只有它在半空里被问。

**所以要修的是时序，不是射线。** `aimAtBlock` 存的是**角度**不是目标，身体一动角度就过期；
而 `scoop` 必须 settle 两 tick（`pick()` 从上一 tick 的朝向起线），那两 tick 正是身体落地的
两 tick。这和 `pick()` 那个坑是同一族的第三例。

### ✅ 已修（2026-08-16）：瞄准挪到 settle 之后，同一 tick 内瞄、验、用

`scoop` 从 `aimAtBlock → settle(2) → 读 aimedAt → 用桶` 改成
`settle(2) → aimAtBlock → 读 aimedAt → 用桶`：**tick 数一格没加**，只是把「存角度」这一步
挪到了下落之后，于是瞄准、预测、`useItemInHand` 三件事发生在**同一 tick、同一只眼睛**上。
`topUpBuckets` 同样重排（它那句「两 tick 是因为 `pick()`」的注释是错的——桶根本不走
`pick()`，`Item.getPlayerPOVHitResult` 现读 `getXRot()/getYRot()/getEyePosition()`，
所以瞄和用之间**不需要**隔 tick；那两 tick 买的是物理，不是朝向）。

**判据满足，而且是同几何 A/B**。单桶第 3 趟把上一轮那条红**逐字复现**了——
同一格 `-10,58,35`、同一只眼睛 `-9.38/60.16/35.64`、同样掉 0.54 格——然后过了：

```
recover9.fromHere = -10, 58, 35 已经看得见源块 -10, 61, 38（够得着），不走过去了；
                    眼睛 -9.38/60.16/35.64 朝 yaw=4.50 pitch=1.87        ← 与红那趟逐字相同
recover9.aimsAt   = -10, 61, 38 Block{minecraft:water} 源块=true 液位=8；
                    眼睛 -9.40/59.62/35.61 朝 yaw=1.96 pitch=-33.07；
                    settle 这两 tick 里眼睛挪了 0.54 格（y 60.16→59.62）—— 瞄准是落定后重算的
recover9.result   = CONSUME
```

pitch **−25.14 → −33.07**（手算的 −33.03，差 0.04 是 float 量化），射线从那块黑曜石**上方**
掠过落进水里。输入状态一模一样、只有角度不同、结果相反——这是这类改动能拿到的最强证据。
`.aimsAt` 末尾那句 settle 位移是新加的读数：**它让「这一趟到底有没有东西要中和」不用靠推断**。
四趟里最大位移 0.54（单桶第 3 趟 `recover9`、第 2/4 趟 `lava6`），都照样打中。

### ❌ 同一轮里被证伪的一条：`riseToTakeItBack` / `visibleSourceNear` 不要等身体落定

「别在半空里问」方向对，**但实现成「等到不动再问」是错的，已跑出证据**。第一版给
`riseToTakeItBack` 和 `fillFrom` 各加了一次「等到位置不变、上限 10 tick」的 settle，那趟
（单桶）红在第 10 格，链条完整：

```
recover8.ask.settled = 等了 10 tick 身体还在动（眼睛 y 61.65→58.06，共挪了 3.60 格）
recover8.spot        = 没找到能看见源块的落脚点，退回 Near(-9, 61, 38,2)
frame.lost.1         = -9, 60, 38 浇成黑曜石之后又没了：现在是 water，
                       丢在「recover8 从 -9, 61, 38 收水」这一步里
opened.9             = -10, 60, 38=air 水位 -10, 61, 38=Block{minecraft:dirt}
```

两条结论，都要留着：

1. **这一级没有「静止」这个状态。** 几乎每一次 `.settled` 都是打满 10 tick 才出来，而且
   `眼睛 y 57.62→57.62，共挪了 0.57 格`——**高度纹丝不动，横着挪了半格**。收水发生在自己刚
   灌满水的壁龛里，流水每 tick 推一下闲着的身体，所以「等到不动」等的是一个不存在的状态。
   （`onGround` 更不能用：泡在水里永远 false，见 `JourneyShaft` 那 36 次 `onGround=false
   water=true`。）
2. **等待本身就是位移。** 多给的 8 tick 让身体多掉了 3.6 格，掉到壁龛底下之后
   `standToFill` 一处都验不过，装水那一步**改成走过去**——而浇筑阶段 walker 的 `allowBreak`
   是开着的，那一走就把刚浇成的门框格挖了当路。**一个让身体走得动的 settle 不是 settle。**

所以「问得早、答错了」只值一次瞄准（`scoop` 落定后会重瞄重问），而「身体低了四格」赔的是
整级。这两条已写进 `HoldStill` 和 `JourneyFill` 的注释里，别再照原样试第二遍。

### 改完之后的四趟（2026-08-16，这是全部，不是挑出来的）

| 趟 | 桶 | 结果 | 死在哪 |
|---|---|---|---|
| 2 | 1 | **PASS** | `frame.cast=10/10（丢了 0 格）` `frame.obsidian=10/10` `portal.cells=6/6` |
| 3 | 1 | **PASS** | 同上；且 `recover9` 就是逐字复现的那条红，见上 |
| 4 | 1 | FAIL | `cast9` 浇不到指定格：回程掉进自己舀空的岩浆坑（`cast9.returnStopped … 身处 lava`），垒回地面后落脚点只到 y=57，射线会把岩浆放进 `-10,59,38` |
| 5 | 4 | FAIL | `wet.9` 挖不开：`-10,61,38` 从头到尾是 dirt，`wet.9.noStand` 说壁龛格 `-10,61,37` 脚下是空气、也垫不了 |

**装桶这一半四趟全绿**：`lava*`/`recover*` 共 20 次（四桶那趟含 5 次 `.more` 顶桶）
**全部 CONSUME**，一条 `frame.lost.*` / `frameOnLine` / `frameStuck` 都没有——上一轮终结第
3、4 趟的那条分支这四趟一次都没走到。四桶那趟的 `loaded` 是 3/2/3/1，`topUpBuckets`
重排之后照常多装。

**两条红都不在装桶里，而且是同一处地形的两种死法**：第 10 格在模腔最上一排，
`-10,61,38` 那格的壁龛落脚点 `-10,61,37` 底下是空的，
「垫一块砖会悬空 —— 这一格要的是楼梯不是一块砖」。挖不开是这个（第 5 趟），
浇不准也是这个（第 4 趟：站不上去，只能从 y=57 斜着浇）。**这是下一刀的位置。**

### 真实 ladder 一趟（2026-08-16，`:fabric:runJourneyServer`，27 分 29 秒）

**爬到 OBSIDIAN（第 11 级），`journey.stagingCalls=0`，和上一次的战绩持平——没进也没退。**
10 级 REACHED（RECON/SPAWN/WOOD/WOOD_TOOLS/STONE_TOOLS/FOOD/FURNACE/IRON/PORTAL_KIT/OBSIDIAN），
BED 仍是 `尚未脚本化`，PORTAL_LIT 红，其后 8 级 `BLOCKED`（0 tick 的 skip，
`BUILD SUCCESSFUL` 是前沿级 `required=false` 的结果，别读成绿）。

**第 12 级红在第 1 格，本轮修的装桶代码一次都没跑到。** 不是最上一排、不是岩浆坑回程、
不是 `stepOut`——比那三条都早，在开挖：

```
forge.carved     = 48/67 格开了，19 格没挖动（上一次真实 ladder 是 63/67）
carve.stuck      = 19 格挖不动：{-7=3, -6=2, -5=4, -4=10}  ← 键是离 y=66 的高度，即壁龛上半截 y=59..62
carve.firstStuck = -9, 59, 36=granite：身体 -9, 56, 34，距 3.6 格，canBreak=true
cell.0.standMissed = 想站 -9, 56, 36，停在 -10, 66, 34，距 -9, 56, 37 还有 10.49 格
                     （精确 -9.99/66.80/34.70，脚下 -10, 65, 34=Block{minecraft:grass_block}）
dig.cell.0       = -9, 56, 37 仍是 andesite：身体 -13, 66, 34（壁龛之外），距 12.5m，canBreak=false
tidy.0           = 10 格是挖完之后才出现的（挖门框时 MineProcess 自己垒的）：
                   -9,59,35 -10,59,35 -7,57,35 … -10,56,36 全是 cobblestone
```

**机制点名了：身体把自己垒出了竖井，回到了地面。** `脚下 grass_block`、y=66 —— 那是地表，
比模腔地板 y=56 高十格；`tidy.0` 那十格圆石是 `MineProcess` 一路垒上来的踏板。三次
`cell.0.stillShut` 全是**从地面上**发的令，所以 `canBreak=false`、距 12.5m。
`stairs.asCut=15 级都完好`，楼梯没坏——它是**走回去**的，不是掉出去的。

**这是既有簇的加重，不是本轮引入的**：`HoldStill` 的改动经 `git diff` 核对**只有注释**
（非注释行 diff 为空），`JourneyFill` 只碰装桶，开挖一行没动;上一次真实 ladder 同一处已经是
`forge.carved=63/67`。变的是程度（4 格 → 19 格没挖动），而 19 格全在壁龛上半截 y=59..62——
和排练里第 10 格那条「最上一排够不着」是**同一个几何**：越往上越站不住。

### ✅ 已修（2026-08-16）：够得着就地挥，不再为每一格找路

`JourneyRig.mineCellOrGiveUp` 在派 `mine`（走过去 + 挥）之前先问一句
`avatar().canBreak(target)`，为真就 `selectTool → aimAtBlock → breakHold(true)` 当场敲开。

**先读码确认了 `canBreak` 的语义，这是整条修法的地基**：`ServerPlayerAvatar.canBreak`
就是 `canBreakFromHere`，条件是**暴露**（六邻有一面不是完整实心）**且在触及范围内**
（眼睛到格中心 ≤ `blockInteractionRange() + 0.5`，从当前眼睛实测）。所以它**含 reach**，
不是「这块石头这具身体啃得动」。更关键：`breakHold(true)` 闸的是**同一个**
`canBreakFromHere`，然后（`faithfulBreak` 关着时）直接 `Level#destroyBlock`。
于是 `canBreak=true` 不是「大概够得着」，而是**「这一挥现在就落，从身体当前所站之处」**。

**没有关掉放置权**——够不着的格子照旧落到 `mine`，照旧垒踏板，因为上半截本来就只能垒上去够。
变的只是：找路不再是每一格的**第一**答案。

**排练一趟（单桶）的读数**：

```
forge.carved     = 67/67 格全开        ← 真实 ladder 上一趟是 48/67，再上一趟 63/67
carve.stuck      = 无
forge.swung      = 59/67 格是就地挥开的（canBreak 已经为真，不用走过去）
forge.cobblestone = 64 → 67（挖壁龛这一段的净变化）
opened.0 … opened.9 = 十格全部 air/air   ← 含 -10,61,38，前两趟它是挖不动的 dirt
cell.5.standMissed = … 停在 -11, 57, 36 …   ← y=57，在壁龛里；不是 y=66 那个地表签名
19 次装桶全 CONSUME，无 frame.lost/frameOnLine/frameStuck；7032 tick（改前 9210~13293）
```

**跳过 COLLECT 的代价被量了，不是被猜的**：就地挥不走过去捡掉落，只靠身体自己的拾取范围，
而 `forge.cobblestone` 报的是 **64 → 67，净 +3**——这一段没有把圆石吃穷。

判据 2（`forge.carved` 显著回升）**达成**；判据 1 的签名（落脚点回到 y≈56 一带而不是地表）
在排练里**达成**。判据 3（真实 ladder 走过开挖进到浇筑）**本轮没答上**，见下。

### ⬜ 真实 ladder 第二趟：被第 11 级挡住，判据 3 未答

爬到 **PORTAL_KIT（第 10 级）**，比上一趟**退了一级**，`stagingCalls=0`。第 11 级红：

```
wd.journey11Obsidian -> FAIL (663 ticks) — 竖井挖不动：身体浮在 Block{minecraft:water} 里
（BlockPos{x=-4, y=62, z=56}，脚下是流体不是地板）——这根柱子不干燥，换一根
```

第 12 级因此 `BLOCKED`，**一次都没跑**，所以这一趟对本轮改动**没有信息量**。

**这一红不可能是本轮改动造成的，理由是结构性的，不是推断**：
`mineCellOrGiveUp` 的调用点**全部**在第 12 级及以上
（`JourneyPortalRung` / `JourneyStairs` / `JourneyFill` / `JourneyNetherRungs` / `JourneyEndRungs`），
而报错的 `JourneyShaft.java` 和实现 1~11 级的 `WorldDriverJourneyScenes.java` 里
`mineCellOrGiveUp` 出现 **0 次**——**1~11 级根本执行不到被改的代码**。
两趟前段也早就分岔了（WOOD 2726 vs 1272 tick、生肉 ×2 vs ×4、剩余圆石 18 vs 21），
选竖井柱子挑到湿的那一根是这条既有的随机性。

### ✅ 真实 ladder 第三趟：判据 1/2/3 全部达成，第 12 级第一次走进浇筑

爬到 **OBSIDIAN（第 11 级）**，`stagingCalls=0`（第 11 级这趟没挑到湿柱子，PASS）。
第 12 级仍红，但**红的位置从开挖搬到了浇筑**——这是本轮要的那一步：

```
forge.carved      = 67/67 格全开            ← 上一趟真实 ladder 48/67（19 格没挖动），再上一趟 63/67
carve.stuck       = 无
forge.swung       = 59/67 格是就地挥开的（canBreak 已经为真，不用走过去）
forge.cobblestone = 100 → 103（净 +3，跳过 COLLECT 没把圆石吃穷）
cell.5.standMissed= 想站 -11, 57, 37，停在 -11, 57, 36 …   ← y=57，在壁龛里
opened.0 … opened.6 = 七格挖开并浇过，13 次装桶全 CONSUME
FAIL: 浇不到指定格：想浇 -8, 59, 38（瞄背板 -8, 59, 39），射线会把流体放进 -9, 60, 37，
      身体在 -9, 58, 37；浇线上是 全是空气
```

- **判据 1 达成**：`standMissed` 报的是 **y=57 的壁龛格**，那个 `停在 -10,66,34…脚下
  grass_block` 的地表签名**没有再出现**。
- **判据 2 达成**：48/67 → **67/67**，`carve.stuck=无`（**没有停在 63 那个也是坏的数上**）。
- **判据 3 达成**：第 12 级**第一次**走过开挖进到浇筑——上两趟真实 ladder 都死在
  `opened.0` 之前，这趟浇到了第 7 格。

### ⬜ 下一个人从这里接（只剩一条，位置很集中）

1. **浇筑的落脚点：够不着就斜着浇，射线进错格。** 三次独立观测同一形状——
   真实 ladder 第 7 格（`想浇 -8,59,38`，身体 `-9,58,37`，流体会落进 `-9,60,37`）、
   排练第 10 格两次（`想浇 -10,60,38`，身体在 y=57，流体会落进 `-10,59,38`）。
   门闸是对的（**没有倒**，没有把失败写成「浇不出黑曜石」），缺的是**站得跟目标同高**：
   `standLevelWith`/`raiseTo` 垒了却垒不到位，判据行是
   `recover8.rise.raisedY = 56/60（停在 -11,36，指定柱 -10,36，不是同一柱）`——
   **垒了、没垒到、还垒错了柱**。这和「最上一排要的是台阶不是一块砖」是同一件事。
   **挖那一半已经不用管了**（`canBreak` 在 5 格内为真，就地挥已经解决），只剩浇。
2. **第 11 级随机红：`竖井挖不动…这根柱子不干燥，换一根`——但没有人去换。**
   ⚠️ **我上一版在这里写的「`JourneyShaft` 选柱不看干湿」是错的，读码+读证据之后作废，
   别拿它当前提。** 选柱**看**干湿，而且看得很明确（`JourneyTerrain.whyNotDiggable`
   查「井口下方有流体」「落脚处上方有流体」「邻柱地表是水」），第 11 级那趟也确实查过并通过了
   （`shaft.standingOn = -4,56 (选定柱)`）。

   真实链条（ladder 第二趟 `wd.journey11Obsidian` 的 `shaft.*`）：

   ```
   shaft.0..3 = 破 -4,62,56 / -4,62,55 / -3,62,55 / -3,62,56 全是 grass_block，onGround=true
   shaft.4..7 = 破 y=61 的四格 dirt，onGround=false
   shaft.8    = -4,62,56 below=-4, 61, 56 Block{minecraft:water}   ← 水在地表下面第二格
   ```

   **地表是干的，水在中段。** 而中段是**故意不查的**——`whyNotDiggable` 的注释写着理由：
   要求整根柱子无流体那一版，recon 实测「280 个候选，280 个全被否」，因为那叫含水层。
   所以规则只查两端，中段交给下挖时的守卫。

   **真正的缺陷因此不是「不看干湿」，而是那条守卫的话没人兑现**：它打印
   「换一根」，然后 `ctx.fail` —— **一条点名了补救办法、而代码并不执行该补救的证据行**，
   和本仓库「证据行说出自己分辨不了的原因」是同一族的坑。

   **修法（未动手，本轮上下文不够改+验，按纪律不硬提交）**：`descendByMining` 撞到
   floating 那一支时，不要 `ctx.fail`，而是**真的换一根柱子重启下挖**（有限次，
   比如 2~3 根，每次记 `shaft.reColumn.N`）。`stepOntoDiggableColumn` 已经会挑合格柱，
   缺的是把它接到这条失败支上；`descendByMining` 有两个调用点（第 11 级和第 12 级），
   加一个 `onWetColumn` 回调即可。
   **判据**：造一根中段带水的柱子（或等它自然出现），`shaft.reColumn.1` 出现且下挖继续；
   第 11 级不再因此红。**注意它是随机出现的，一趟 ladder 不构成验证**——
   这条要么用一个 scene 定点造水层来验，要么接受多趟。
3. ⚠️ `stepOut` 至今一次都没触发。
   `forge` 阶段开着放置权，于是够不着的上半截靠垒踏板去够，垒着垒着就上了地表，
   再也回不来。`cell.0.standMissed` 那一行（想站 y=56、停在 y=66、脚下 grass_block）
   是这条的判据：**修好之后它必须报一个 y=56 附近的落脚点**。注意别顺手关掉放置权——
   上半截本来就够不着，那正是第 1 条要解决的同一件事。
1. **最上一排要的是台阶，不是一块砖。** `standToPour`/`standToFill`/`mineCellOrGiveUp` 三处
   都在 `-10,61,37` 这一格上说了同一句「垫不了」。`raiseTo`/`climbOutInColumn` 已经会垒柱子，
   但第 4 趟 `recover8.rise.raisedY=56/60（停在 -11,36，指定柱 -10,36，不是同一柱）`——
   **垒了却没垒到、也没垒对柱**。先修这条读数说得很清楚的：为什么 `climbOut` 报了 56/60 还继续。
2. **回程会掉进自己舀空的岩浆坑**（第 4 趟 `cast9.returnStuck.3`）。已有「开着放置权垒回地面」
   的兜底并且跑到了，但兜底之后落脚高度就不对了。
3. ⚠️ `stepOut` 至今仍**一次都没触发**——这四趟连 `frameStuck` 都没走到，所以它比上一轮
   更远，不是更近。

### 上一轮的原始记录（保留，因为它是这一轮的输入）

2026-08-15 的排练（`-Prehearse=PORTAL_LIT -Pbuckets=4`，四趟浇满十格）以
`门框没浇满：只有 6/10 块黑曜石` 收尾，而**十格全部 `castN.result=CONSUME`，一条
`cast.missed.*` 都没有**——也就是说浇的当下十格都是黑曜石，后来少了四格。

四格里**只有一格有证据**，而这条证据直接点名了机制：

```
opened.9              = -10, 60, 38=Block{minecraft:air} 水位 -10, 61, 38=Block{minecraft:air}
recover9.clearedLine.3 = -10, 60, 38 Block{minecraft:obsidian} 挡在眼睛和 -10, 61, 38 之间，敲掉它
```

第 9 格浇成黑曜石之后，**收水**那一步要瞄 `-10,61,38` 的水源，`scoop` 发现射线停在
`-10,60,38`，就走了「射线被挡住 → `rig.mineCellOrGiveUp(wall)` 敲掉它」这条分支——
那块「挡路的方块」正是它自己刚浇出来的门框。**`scoop` 的清线没有任何「不许敲门框」的约束。**

- 这条路径和多桶改动无关，`scoop` 的清线分支一个字没动，是早就在树里的。
- 它只解释了四格里的一格。**另外三格这一趟没有任何证据行说得出去向**——
  一条都没有 `cast.missed`、没有 `clearedLine`、没有 `tidy` 报到门框格上。
  这本身就是发现：**门框会在浇完之后掉格，而现有读数看不见是谁拿走的。**
- 修法不是「浇完再数一次」那种收尾补丁：要么清线时把 `JourneyForge` 的 RING 格
  设成禁敲（和 `mendBacking` 认背板同一套坐标），要么收水的落脚点先避开门框自己的射线。
  **两条都要先能观测到「谁动了这一格」**，否则修完还是只看得见 6/10。

同一趟的对照：`-Prehearse=PORTAL_LIT`（单桶）那趟 10/10 浇满并点亮了传送门，
`recover*` 一次都没清到门框上。**别把这两趟读成通过率**——模腔位置每趟都不同。

## ⬜ 9 级：多买两个桶，12 级的往返就从十趟掉到四趟（代码已就位，缺的是铁）

12 级现在**按空桶数决定上楼次数**：`castOpenedCell` 只在 `lava_bucket == 0` 时才走
`goUpToThePool → loadBuckets → returnToTheForge`，而 `loadBuckets` 一趟把所有空桶装满（留一个
空桶收水）。趟数因此是 `ceil(10 / 能装岩浆的桶数)`：

| 桶 | 能装岩浆的 | 上楼趟数 |
|---|---|---|
| 1（现在） | 1 | 10 |
| 4 | 3 | 4 |

**这条已经落地，不需要和铁同步。** 一个桶时 `loadBuckets` 的循环在第一次迭代前就停，行为和以前
逐字相同。要跑到多桶分支只能用排练布景：
`./gradlew :fabric:runRehearsalServer -Prehearse=PORTAL_LIT -Pbuckets=4`。

### 缺的那一半：9 级挖不出四个桶的铁

`WorldDriverJourneyScenes.mineVeinsUntilPaid` 只认三条矿脉（`case 0/1/2` →
`firstIron/secondIron/thirdIron`，第四条起 `shaft`/`ore` 都是 `UNSURVEYED` 就收工去炼），而
javadoc 实测**每条矿脉一到三个矿**，有一趟是 `vein1.raw_iron=0 / vein2.raw_iron=3` ——
所以三条的**理论上限是 9 个原铁**，现实里常常只有 3~5 个。

现在的账：`IRON_INGOTS_THE_KIT_COSTS = 4`（桶 3 锭 + 打火石 1 锭），
`RAW_IRON_TO_MINE = 5`。要凑四个桶（12 锭）+ 打火石（1 锭）= **13 锭**，
`RAW_IRON_TO_MINE` 得到 14，三条矿脉的上限 9 根本不够。

所以这一步不是改个常量，是**先要有第四、第五条矿脉**：

1. RECON 得勘测并烘入 `fourthIron`/`fourthIronDescent`……，或者把
   `mineVeinsUntilPaid` 的 `switch` 换成一张列表；
2. 每多一条矿脉就是一根新竖井加一次爬升，成本要量（现在 9 级的 tick 开销是多少，
   加两条之后是多少），**不能只看能不能凑够**；
3. 凑不够时要报**差多少**，而不是让 12 级在「桶只有 1 个」这条正常降级路径上悄悄跑十趟——
   降级本身是对的，但账上要看得见。

判据：**观测到上楼趟数下降**（数 `lavaN.up` / `lavaN.loaded` 行），不是绿。

## ✅ 12 级：真梯从 **0 浇** 到 **6 浇**，楼梯不再是卡点；新卡点是壁龛没挖开

2026-08-15 深夜的真梯（改完之后第一趟，`staging.calls=0`，爬到 11 级，13–20 正确 BLOCKED）：

```
lava0..lava5.upEnded = -9, 66, 21（楼梯顶 -9, 66, 21）   ← 六次上楼，六次都精确到顶
cast0..cast5.result  = CONSUME                          ← 六浇（上一趟是 0 浇）
upStopped            = 一次都没有                        ← 上楼没有一段掉队
第 7 格没挖开就要浇：-8, 59, 37=Block{minecraft:granite}
forge.carved         = 53/67 格开了，14 格没挖动
```

**上一趟死在第一次上楼、一趟都没浇；这一趟六次上楼全部到顶、浇了六格**，
死因换成了「壁龛没挖开」——那是另一条早就记在下面的账（碎石收尾不走位直接挥）。

### 三条新证据线各自说了一件旧消息说不出的事

```
cast2.returnStopped = 第 0/4 段：想到 -9,66,21，停在 -9,63,19，差 3.61 格
                      —— 脚下 stone，身处 lava，头顶 cave_air，起跳格 -9,65,19=stone（挡着，跳不起来）
cast5.returnStopped = 第 1/4 段：想到 -9,62,25，停在 -9,66,21，差 5.66 格
                      —— 脚下 cobblestone，身处 air，头顶 air，起跳格 -9,68,21=air
cast2.stairsBroken  = 1/15 级坏了：-9,66,21 脚下 -9,65,21=air；
                      2 格泡在流体里：-9,56,34=water，-9,56,35=water
```

- 第一条：身体**泡在岩浆里**、头上还压着石头，所以走不动 —— 后面
  `returnStuck.3`→垒柱→`backToMouth` 把它救了回来。
- 第二条：身体**站在楼梯口，四格全空**，却没走成第 1 段。**跟第一条形态完全不同**，
  旧消息（只报最终高度）会把这两种情况印成同一行。
- 第三条：**泡水那一句在真梯上真的触发了**，报的正是楼梯底 `-9,56,35`。
  这一句以前不存在，`N 级都完好` 会照说不误。

**这是一趟，不是一个通过率。** 别把 6/10 读成稳。

## ⬜ 12 级：楼梯自检只问了四问里的三问，于是给一段爬不上去的楼梯发了合格证（已修，上面是回测）

2026-08-15 的真梯**一趟都没浇**就死了，死在**第一次上楼**：

```
走不上楼梯：停在 -9, 56, 36，楼梯顶 -9, 66, 21 在 y=66 —— 楼梯自检：16 级都完好（本级共自检 1 次，修好 1 级）
stairs.bottom      = -9, 56, 36（south 向，顶在 -9, 66, 21）
lava0.stairsBroken = 1/16 级坏了：-9, 56, 36 挡住 -9, 56, 36=Block{minecraft:gravel}
lava0.stairsMend.0 = … → 敲开了（身体 -9, 56, 37）
lava0.upEnded      = -9, 56, 36（楼梯顶 -9, 66, 21）
```

**停的地方就是楼梯底本身**（`stairs.bottom` = `-9,56,36` = `upEnded`）。上一版 TODO 里
「楼梯底在 z≈33，身体卡在壁龛深处」这句是错的 —— 身体正站在最下面那一级上，站在一段
自检刚说过「16 级都完好」的楼梯上。

### 观测（读那次运行存下来的世界，不是推断）

`-9` 那一列，`x=-10/-9/-8`：

```
y=58 z=36   dirt | dirt | air        ← -9,58,36 = dirt
y=57 z=36   dirt | air  | air
y=56 z=36   water| water| dirt       ← -9,56,36 = water（楼梯底，身体所在）
y=55 z=36   granite | andesite | andesite
```

十六级逐格核对，只有第 15 级（`-9,56,36`）不合格，而且是**两条自检都问不到的**：
`foot=water`、`起跳格 -9,58,36=dirt`。其余十五级 support/foot/head/起跳格 全部干净。

### 成因：`digStairsDown` 每级挖三格，自检只问了两格

`digStairsDown` 的 javadoc 自己写着第三格是干什么的 ——「going back UP, the body jumps from
a step to the one behind it, and a jump needs clearance two above the feet it starts from」——
而后来写的 `stairFaults` 只问了 support / foot / head。

`StepUp.valid` 的最后一行是 `return w.isPassable(from.offset(0, 2, 0));`，而 flight 的每一段
walk 都带 `NoBreak`，所以会破墙的 `StairUpBreak` 根本不在候选里。于是 A\* 从 `-9,56,36`
**向上没有任何一个合法动作**。

`-9,58,36` 的 dirt 是身体自己垒的：`y=58` 那一层 `-10,58,36 / -9,58,36 / -8,58,36 / -9,58,37`
全是 dirt，正是 `MineProcess` 够上排门框时的柱子（真梯这一级手上是 dirt，排练永远是 cobblestone）。

**水不是拦路的**，这一条要说清楚：`isFloatingWater(-9,56,36)` 要求脚下也是水，而脚下是
andesite；`isSubmergedAscent` 要求目标格也是水，而 `-9,57,35` 是 air。两条都不成立。
水的问题是**它让自检说了谎**，不是它挡住了路。

### 已改（编译通过；真梯回测见下）

1. **自检加问第四格**：除最顶一级外，每一级都检查 `step.above(2)`。顶那一级不问 ——
   没有人会从顶上往上跳，而且那一格是整段楼梯里 `digStairsDown` 唯一没挖过的
   （身体本来就站在那儿），问它会把没动过的地表岩石报成坏楼梯并花掉一次修补额度。
   报告里用「起跳格」和「挡住」区分开。
2. **自检报流体**：`blocksMotion()` 对水是 false，所以一段泡在水里的楼梯和一段干楼梯
   报出来一模一样。`stairReport` 现在在后面缀 `N 格泡在流体里：<格>`。**故意不当成 fault** ——
   镐头修不了水，壁龛排水是另一条独立的账。
3. **每一段 walk 报自己停在哪**：`walkTheStairs` 记下第一段没走到的 leg——第几段/想到哪/
   停在哪/差几格/停的地方那四格（脚下、身处、头顶、起跳格）长什么样。出口是
   `<tag>.upStopped` / `<tag>.returnStopped`，两条 fail 消息里也带。

`JourneyPortalRung.java` 因此超了 3000 行，把自检+修补+排练破坏挪进了新的
`JourneyStairs.java`（纯搬家）。

### 还没证实的一条（推断，不是观测）

那次四段 leg 每段 600 tick，但整级只跑了 2439 tick（wallMs=121906，≈20tps），
digging 16 级楼梯 + 挖 67 格壁龛也在这 2439 tick 里。所以**四段 leg 多半是「无路可走」
立刻返回，不是耗光 600 tick**。这跟「A\* 没有合法动作」吻合，但没有直接证据 ——
下一次 `upStopped` 会直接说。

## ✅ 12 级：排练里**十浇十亮**，三次

```
frame.cast=10/10   frame.obsidian=10/10   portal.doorway=六格都清干净了
portal.cells=6/6   light.cellAfter=nether_portal   bucket.after=0 空 / 1 水
```

11503、14179、11398 tick，三次独立通过（上一版是 8 浇 / 9 开）。修的五件事见 CHANGELOG：
背板补回来、爬升不再被自家的水吓退、起塔柱按射线选并钉住、落脚格里自己垒的那块敲掉、
浮在水里不再原地settle到底。

**但这是排练，不是攀爬**（`staging.calls=12`）：它说的是这一级自己的活儿站得住，
没说从底下十一级真爬上来之后还站得住。**真梯自 2026-08-15 的修改之后没跑过。** 下一步就是它。

### ✅ 真梯第一次浇满十格：`frame.cast=10/10 frame.obsidian=10/10`，死在**门洞**

2026-08-15 的真梯（`runs/ladder-litter.log`）：**爬了 11 级**（01–11，07/BED 是
`NOT_SCRIPTED` 跳过），`staging.calls=0`，13–20 正确 BLOCKED。12 级 8462 tick，
第一次把十格全浇成黑曜石，然后死在最后一步：

```
cell.0.litter.2 = -9,57,37=cobblestone 挖门框时自己垒进落脚格的   ← 新修的那一刀，真梯上命中
frame.cast      = 10/10        frame.obsidian = 10/10
portal.slag     = 2 格要清：-9,57,38=water -10,58,38=granite
portal.doorway  = 还堵着：-9,57,38=water
```

**水挖不动。** `clearNext` 是 `mine`，对流体是空挥；granite 清掉了，水挥了 600 tick。
水是从壁龛灌进来的 —— `drain.9 = 等了 200 tick 仍有流体：-9,57,37 = water`
正是它背后那一格。所以「壁龛排不干」这条从**代价**变成了**卡点**。

已改并**排练回测通过**（12294 tick，`portal.doorway=六格都清干净了`、`portal.cells=6/6`）：
先**堵**门洞背后那一格壁龛格（有流体就塞圆石），再**等** 120 tick 放干，
剩下的流体**塞**成圆石再当方块挖。

```
portal.dam            = -10,57,37(流动)→没堵上，还是 water     ← 尽力而为，这一轮没成
portal.plug.-10,57,38 = 流动 minecraft:flowing_water → 塞成 cobblestone，接下来当方块挖掉
portal.slag           = 2 格要清：-9,57,38=cobblestone -10,57,38=cobblestone
portal.doorway        = 六格都清干净了
```

**堵那一步这一轮没成**（多半是身体自己站在那格里，放不下方块），
是「等 + 塞」把这一轮救回来的。三步里哪一步成了现在各报各的。

### ✅ 「水源没被收回来」这句话是错的 —— `firstFluid` 现在会说是不是源块

```
drain.6…9 = 等了 200 tick 仍有流体：-11,56,37 = water（流动，没源就会自己退）
```

**全是流动水，不是源块**，而十次 `recover` 也全是 CONSUME。所以壁龛的残水不是
「有个源块没舀走」，是七格高的房间里水还没退完 —— 该调的是等待时长或者别把地板捅漏，
不是去追一个不存在的源块。那句 `—— 水源没被收回来` 已经删掉。

### ⬜ 真梯上死在第 0 格：`MineProcess` 用**泥土**把落脚格垒死了（已修，**真梯已验证**）

2026-08-15 的真梯不再死在这里：`cell.0.litter.2` 命中并敲掉，第 0 格照常浇成黑曜石。

真梯那次身上最多的可放置方块是**泥土**（排练永远是 `cobblestone×64`，所以三十轮都没露出来）：

```
dig.cell.0     … canBreak=false … west=Block{minecraft:dirt}(实心)
cell.0.noStand … 7,56,19 被 Block{minecraft:dirt} 占着     手上 minecraft:dirt
```

`7,56,19` **不在** `carve.stuck` 里 —— 它挖开过，是后来被自家柱子填回去的，
而 `tidyTheAlcove` 只认 `Blocks.COBBLESTONE`，所以从没碰过它。

修法是**只敲一格**（落脚格的脚位或头位），拿 `forgeStuck` 当基线判断「这是后来才有的」，
不看方块 id。排练里两次命中，其中一次正是 id 白名单永远抓不到的 gravel：

```
cell.0.litter.2 = -9,57,37=cobblestone 挖门框时自己垒进落脚格的
cell.2.litter.2 = -8,56,37=gravel      挖门框时自己垒进落脚格的
```

**别改成扫一片。** 试过，从 2/2 掉到 0/2，两次同一个机制：gravel 落进七格高的坑里，
**堵住的正是壁龛地板**，浇筑的水就是从这些堵头漏走的。见 CHANGELOG 里的测量。

### ⚠️ 两次通过不是一个通过率

两次的失败点和成功理由都不一样：一次背板被挖穿、靠 `cast8.backingMend` 补回来才浇成，
另一次同一格从头到尾是 granite、补丁一次都没调用。**方差在「这一轮挖坏了哪几格」**，
是逐轮的。别把 2/2 读成稳。

### ⬜ 起塔柱钉住之后，剩下的是「钉住了但走不回去」

```
water9.raise       = -9, 57, 37 → y=60（在 -9,37 这一柱上垒台阶）：站上去射线落得进目标格，钉住这一柱
climb.1.pinnedLost = -9, 56, 36 走不回指定柱 -9,37 —— 爬升到此为止，不改柱
water9.raisedY     = 56/60（停在 -9,36，指定柱 -9,37，不是同一柱 —— 射线是照那一柱算的）
```

**按设计跑的**：柱子是射线选出来的（选中了身体脚下那一柱，不是算术那一柱 -10,37），
钉住之后不再改柱，走不回就停、报一行、不循环。这一轮浇筑照样成了
（`cast9.result=CONSUME`，走的是 `liftInPlace`），所以钉住**没有**把一次可恢复的漂移变成硬失败。

还没做的：身体为什么会从 `-9,57,37` 掉到 `-9,56,36`（多半是壁龛地板那排的水把它冲下来了，
见下面的排水条目），以及掉下去之后能不能自己爬回指定柱。

### ⬜ 壁龛排不干，后四级连报 —— **成因找到一半，仍未修**

```
tidy.5  = 1 格要清：-11, 56, 37=cobblestone
drain.6 = 等了 200 tick 仍有流体：-11, 56, 37 = water
drain.7 = -11, 56, 37 = water    drain.8 = -9, 57, 36 = water    drain.9 = -11, 56, 37 = water
```

十次 `recover` 全部 CONSUME，源块都收回来了，等了 200 tick 还是湿的。**成因的一半已经看见了**：
`drain.6` 报湿的那一格，正是 `tidy.5` 上一步刚清掉的那块圆石 —— 圆石在堵地板，清掉就漏。
这跟「扫一片」那次翻车是**同一个机制**（那次堵头是 gravel），区别只是这块圆石
`tidyTheAlcove` 从来就在清，而且清对了：它是垫脚石。所以这条不是「别清」，
是「清完得知道地板漏了」。

代价现在是**可以承受**的，不是卡点：11398 tick 那轮从 `drain.6` 起一路是湿的，照样 10/10。
湿了之后身体会浮起来，而浮起来现在有出口（见下条），不再吃满整个 cap。

### ✅ 浮在水里不再原地 settle 到底

```
climb.9.afloat     = -8, 58, 36 浮在水里，8 次都没落地 —— 塔要站在地上才垒得起来，爬升到此为止
exit.walkerFallback = True        exit.gained = 4/4 block(s)
```

`ascendByTowering` 遇到 `!onGround()` 就 settle 重来，对「踩空一下」是对的，对水是**无界的**：
游泳的身体永远不会 `onGround`，所以那一支自己递归到 cap 用完，每一轮都是空转。
量到过 `climb.4`…`climb.39` **三十六轮**一模一样的 `onGround=false water=true`。
现在花完 washed-off 那点额度就**报一行名字**停下来，由 `climbOut` 的 walker 兜底。

### ⬜ 挖不动的那几格：**不是够不着，也不是被围死，是 240 tick 用完了**

```
forge.carved      = 63/67 格开了，4 格没挖动 —— 见 carve.stuck，壁龛不是完整的
carve.stuck       = 4 格挖不动：{1=4}（键=离 y=61 的高度，即挖完时身体脚下那一层，值=格数）
carve.firstStuck  = -9,62,36=dirt：身体 -8,59,36，距 3.2 格，canBreak=true，六邻实心 5/6，
                    手上 minecraft:cobblestone
```

`forge.carved` 原来在 `carve.stuck=12 格挖不动` 正上方印 `完成`，两行隔着一行代码，
互相打脸 —— 已改成报数。**它故意不 fail**：两次 10/10 的排练都带着 4 格没挖动（都在壁龛顶），
真梯那次要命的 `7,56,19` 反而是挖开之后被填回去的，卡在这里只会比真正的发现早死三轮。

`canBreak=true` + `距 3.2 格` 是这条的关键：身体站在那儿**本来就够得着**，
却在 240 tick 里一下都没敲开 —— 像是 `MineProcess` 把预算全花在走位上了。
两个站点（排练 `-9,62,36`、真梯 y=61/62 那十二格）读数一致，都在顶排。
没试过的下一步：碎石收尾时对 `canBreak(c)` 为真的格子**直接挥**，不再走过去。

### ✅ 「站错了一排」查清了：是四舍五入，不是走错

```
cell.5.standMissed = 想站 -11,57,37，停在 -11,57,36，距 -11,58,38 还有 2.24 格
                     （精确 -10.54/57.18/36.91，脚下 -11,56,36=air，
                      想站那格脚下 -11,56,37=cobblestone）
```

`z=36.91`：身体**就站在自己刚垫的那块圆石上**，只是 0.6 宽的碰撞箱压在边上，
`blockPosition()` 于是取到了里排那一格。**走位没有失败**，上一版 TODO 里的
「把 -11,56,36 也垫一格」帮不上忙 —— 差的 0.09 格在**到达判定**上：
`Near(cell,2)` 和 `withinDigReach` 都拿方块格算距离，而身体有真实坐标。

### ⬜ 再往上两排（dy=3/4）仍然要楼梯，但**不是**卡点

`cell.6/7/8.noStand`、`wet.8/9.noStand` 每次都报，而 `opened.0..9` **十格全开**——
`mineCellOrGiveUp` 里的 MineProcess 自己垒柱子够到了它们。所以「要楼梯」现在是
**代价**（垒出来的圆石要 `tidyTheAlcove` 清，而且正是它把背板挖穿的），不是失败原因。

拒绝的理由现在会说清是哪一条：

```
垫不了：-8, 57, 37 脚下 -8, 56, 37=air 撑不住 —— 一块砖会悬空，这一格要的是楼梯不是一块砖
```

沿 x 铺阶梯（壁龛 5 格宽）或把壁龛改成自上而下开挖，两条都还没试；
后者的代价仍是 `corridor()` 那条「每一格都挨着已经挖开的空气」要重新论证。

### ⬜ 壁龛还是排不干（连续两级）

```
drain.7 / drain.8 = 等了 200 tick 仍有流体：-11, 56, 36 = water
```

十次 `recover` 全部 CONSUME，水源都收回来了，但地板那一排还是湿的。
没查过是哪来的（`water8` 浇进 dy=5 的凹口，水会顺着 z=37 那一列灌到地板）。

## ✅ 12 级：「上半部分挖不开」这条**不成立了**（保留，因为它误导过三轮）

原文说 dy>=2 的六格「壁龛里没有一格落在 `Near(cell,2)` 里，全靠运气」。
2026-08-13 排练把它证伪了：`opened.0 … opened.9` **十格全开**，一格不落。
`mineCellOrGiveUp` 里的 MineProcess 自己垒柱子够到了它们 —— 它不需要 `standBehind` 帮忙。

代价是真的，只是记在了别处：那些柱子就是 `tidy.N` 每一级要清的圆石，
而**正是这种「垒进门洞里挖」把门框背板打通了**（`cast8.backingMend`，见 CHANGELOG）。
所以「上排要楼梯」现在是一条**省钱**的理由，不是一条**能不能挖开**的理由。

原文里的三条路：第 1 条（垫一格站台）已经落地并且对 dy=2 有效；第 2、3 条仍然没试。

### ⬜ 「浇线上有自己的水」也不是终点了

上一版终点 `想浇 -9,60,38 … 浇线上是 -9,59/60/61,37 = water` 里，**水不是拦路的**：
桶的射线走 `ClipContext.Fluid.NONE`，水根本不挡。真正拦下那一浇的是两件别的事，
都已修（背板被挖穿、爬升被自家的水吓退），修完 `cast8.result=CONSUME`。
这条留在这里，是因为它示范了一种很贵的错误：**失败消息把「浇线上有什么」和「为什么浇不成」
排在一起，读的人就把前者当成了后者**。

### ✅ 楼梯自检又抓到一次真的（第二次独立观测）

`lava0.stairsBroken = 1/16 级坏了：-9, 56, 36 挡住 -9,56,36=gravel` →
`lava0.stairsMend.0 = … → 敲开了`。砾石掉进楼梯底这件事在两次排练里都发生了，
是可复现的，不是巧合。自检在真梯上则全程沉默（`stairs.asCut=12 级都完好`），
这正是它该有的对照读数。

## ⬜ 12 级：楼梯的锅已经排掉，下一刀在浇筑第一格

**已经解决并已在真梯上复验的**：`走不上楼梯` 是身体自己吃掉了台阶 —— 详见 CHANGELOG。
2026-08-12 那次真梯跑完，`stairs.asCut=12 级都完好`，全程没有一条 `stairsBroken`，
自检**沉默**（这就是它该有的对照读数），`走不上楼梯` 没有再出现。

### ⬜ 现在挡在 12 级的是别的东西：模腔第一格挖不开

真梯 2026-08-12（11 级已达成，`staging.calls=0`，8598 tick）：

```
carve.stuck   = 19 格挖不动：{-3=3, -2=2, -1=4, 0=10}
                分别在：-9,59,33 -8,59,33 -7,59,33 -9,60,33 -8,60,33 -11,61,32 -10,61,33 -7,61,33 …
cell.0.refilled.3/2/1 = -9, 56, 34 又被 granite 填上了（上面塌下来的），再挖一次
dig.cell.0    = -9, 56, 34 仍是 granite：身体 -10, 59, 34 距 4.2m，canBreak=true，mine.lastError=null
                end=collect swept everything it could reach (broke 64/64, sweep ARRIVED toward null…)
                手上 minecraft:dirt；六邻 down=granite(实心) up=granite(实心) north=air
                south=andesite(实心) west=granite(实心) east=andesite(实心)
opened.0      = -9, 56, 34=granite 水位 -9, 57, 34=air
```

**没查过、值得先问的三件事**（按证据强度排）：

1. **身体在 y=59，格子在 y=56 —— 差三格。** `dig.cell.0` 自己写着「距 4.2m」。
   `mineCellOrGiveUp` 里的 MineProcess 有没有真的走下去？`end=collect` 说明它进了收集相，
   `broke 64/64` 说明它**破了 64 个方块**却没有破这一个。64/64 这个数字本身很可疑。
2. **19 格挖不动 vs 上一次的 3 格。** 这一次的地形不同（楼梯 12 级不是 13 级，
   `forge.face=-9,56,34` 不是 `-9,56,35`），也就是说模腔落在了更差的岩层里。
   `carve.stuck` 的高度直方图 `{-3..0}` 说明卡住的是**脚下三格以内**，不是够不着的头顶。
3. **「上面塌下来的」这句话大概率是错的。** granite 不是 FallingBlock，塌不下来。
   格子被重新填上更可能是**别的东西把它填了**（寻路放置？）或者根本没被挖开过而重读了旧值。
   这条消息误导人，值得连同原因一起改。

### ⬜ 排练里的 12 级另有一刀：壁龛排不干

排练（`-Prehearse=PORTAL_LIT`，2026-08-12，11917 tick，浇到第 9 格）：

```
drain.7  = 等了 200 tick 仍有流体：-9, 56, 36 = water —— 水源没被收回来，下一格挖开就会灌满
（第 10 格）浇不到指定格：想浇 -9,60,38，浇线上是 -9,59,37 / -9,60,37 / -9,61,37 = water(壁龛内)
```

水漫到了**楼梯底那一格**（`-9,56,36` 就是 `stairs.bottom`）。真梯上没走到这里，
所以两条刀口是分开的，别把它们当成一件事。

## ⬜ 岩浆湖是个**洞穴湖**，顶只有一格厚 —— 这是 12 级两条来料的共同上游

种子 5471 的 `lavaLake=-9,63,19`，从存档里读出来的实际形状：

| y | x=-10..-15, z=16..21 |
|---|---|
| 66 | 空气 |
| 65 | **草方块（一格厚的顶）**，x=-13/-14 处已经是 cave_air/air（天然破口） |
| 64 | cave_air（洞） |
| 63 | **岩浆湖面** |

12 级每一趟来回都从这层草皮上走过去（楼梯口 `-9,66,21` → 装料点 `-14,65,21`）。
2026-08-12 那次身体从破口掉了进去，在湖底又挖出一个 3 格深的坑
（`-10,61..63,19..21`，现在全是岩浆），再从下面掏穿了自己的台阶。

`NoBreak` 挡住了**挖**，没挡住**掉**。下一次它再掉下去，自检会把台阶补回来，
但坑还是会多一个。真正的修法大概是这两条之一，都还没试：

- 选装料点时把「楼梯口到装料点这条路的地皮下面是不是空的」也算进否决计数；
- 或者干脆不走地皮：装料点选在湖的**同一侧**、和楼梯口之间没有洞的那一段。

## ✅ 14/15 级：身体是**走出了自己脚下那一格**掉下去的 —— 三条候选一次问掉两条

2026-08-15。上一版这里写的是「机制还没查，别猜，等读数」。读数加好了，问的是
**离地那一 tick**，不是失败之后的快照。三趟 15 级排练（`ENDER_PEARL`，各约 7 分钟）：

```
warped.runUp.1  = t=48 14,42,4 地 撑2 | t=49 15,42,4 地 撑2 | t=50 15,42,4 地 撑0
                | t=51 15,41,4 空 撑0 | … | t=55 15,41,3 地 撑1
warped.fell.1.0 = #1 t=56 从 15,41,3 走出了支撑格（上一 tick 踩着 [15,40,3=netherrack]，
                  这一 tick 脚下是 [15,41,3=air 16,41,3=air]）→ 落进岩浆 18,30,0，坠 11 格
                  （最快一 tick 掉 1.19 格），已走 8/272 格，计划第 1/8 步
warped.flight.1 = 走了 12/272 格；y 41→21；共 377 tick；离地 1 次，最深 11 格；
                  收工那一刻：goalReached=false end=failed:no path (expanded=1)
                  在 17,21,-1 …泡在岩浆里；首次入岩浆 t=79 18,30,0 —— 从 y=41 掉进去的
```

三条候选（顶塌了 / 走出洞口 / 下落中被判定到达）：

- **不是顶塌了**。`prevSupport` 的**格子坐标**在离地那一 tick 被重读了一遍，
  `15,40,3` 还是 netherrack。花岗岩没消失，是身体离开了它。
- **不是下落中被判定到达**。收工是 320 tick 之后的事，而且 `goalReached=false`，
  `end=failed:no path`。**掉进岩浆在前，寻路失败在后** —— `expanded=1` 是结果不是原因。
- **是走出了支撑格**：一格 netherrack 的边缘，对面 11 格下是岩浆湖。

### 三趟的失败点：走了 32 / 9 / 8 格（全程 272 格）

不是「走到 105 格才掉」，是**第一道岩浆岸就死**。三趟路线各不相同（种子同，寻路不同），
落点 `10,30,-26` / `15,30,3` / `18,30,0`，**y=30 那层是同一片湖**。

**寻路器规划不出这一跳**：`Fall.valid` 卡在 `BotConfig.pathfinderMaxDryFall`（默认 3），
而且沿途任一格 `isHazard` 就否决。所以 11 格落岩浆**不在动作集里** ——
身体是**偏离了自己的计划**，不是执行了一个坏计划。

### 还没问的那一格（下一个人从这里接）

自检问的是**身体脚下**（包围盒覆盖的格子），**没问身体要去的那一格**。
所以现在还分不清：

1. 计划的下一个节点本来就在对岸（跳跃/parkour 落空），还是
2. 计划是好的，身体在执行时冲过了头。

`t=56` 那一 tick footprint 的 y 是 **41 而不是 40**（`floor(minY-0.02)`），
说明 `minY≥42`，即**那一 tick 身体在往上走** —— 像一次跳跃。
`计划第 1/8 步` 说明刚重规划过。要问死这条，得能读到 walker 当前 path 的节点；
`Walker.pathNode()/pathLen()/pathStep()` 是 public，但 `ServerWorldDriver` 没有 `walker()` 出口。
**加那个出口是下一步，不是这一步** —— 先有了「跳向哪一格」的读数再谈修法。

### `fp.fallDistance` 对这具身体**恒为 0**（源码级，不是运气）

```java
// net.minecraft.server.level.ServerPlayer（1.21.1 反编译）
@Override
protected void checkFallDamage(double d, boolean bl, BlockState blockState, BlockPos blockPos) {
}
```

`Entity.move()` 调的就是这个空覆盖；真正累加的是 `doCheckFallDamage`，只走网络包那条路。
FakePlayer 没有连接，所以**这个字段永远 0**。三次测量都是
`最快一 tick 掉 1.14~1.19 格，同期 fallDistance 最大 0.0`。

后果两条，都已修：

- `hazardBlockingARetry` 的第三支 `!onGround && fallDistance > 2.0f` **是死代码**。
  上一版 TODO 说「在流体里 / 还在下坠的身体不再消耗重试次数」——
  **「还在下坠」那半句从来没生效过**，只有岩浆/水那两支在干活。
  现在换成 `getDeltaMovement().y < -0.3`。
- `surroundings` 的 `坠=0.0` 是**一条会终止排查的假证据**：它对一具正在自由落体的身体
  回答「没在坠」。换成 `落速=`（这一 tick 的竖直速度）。

**别把这一节读成「上游修好了」。** 修好的是**读数**：机制现在指向「走出支撑格 → 落岩浆」，
一句话的修法（在固定 y 层挖隧道推进，不走地表）**还没测过**，也不该在读到「跳向哪一格」之前测。

**翻旧日志翻出来的一条**（`runs/rehearse14blaze.log`，那次 `around` 还没加上）：
三次尝试的 `end=` **不是同一种失败**。

```
fortress.goto.1 = no route progress after 5 consecutive searches — goal unreachable (best dist=3622)
fortress.goto.2 = no path (expanded=1)
fortress.goto.3 = no path (expanded=1)
```

第 1 次是「搜到了，地形赢了」；第 2、3 次是 `expanded=1` ——
**起点弹出来一个合法后继都没有，身体被封在里面了**。
`hazardBlockingARetry` 只认岩浆 / 水 / 下坠，封死不在其中，所以那两次白跑。
所以 14 级要的多半不是更大的行走预算，是**先把自己挖出去**
（`JourneyShaft.climbOut` 就在同一个包里，12 级每轮都在用）。还没实现，也还没回测。

## ⬜ 15 级：census 把「刷不出来」这条问死了 —— 是**生物群系**，走不过去是**岩浆**

排练回测（`runs/rehearse-pearl.log`，6745 tick，**仍是 FAIL**，但失败点变了）。
新加的三件（走到扭曲森林 / 干等一轮只花一轮 / census）全部触发，全部说了真话：

```
hunt.1.dry = 等了 1200 tick 没等到；末影人 0 只在 48 格内、0 只在 128 格内；
             128 格内怪物共 107 只 {zombified_piglin=62, piglin_brute=17, piglin=28}
```

**107 只怪物，0 只末影人。** 三个候选世界一次问死：不是「什么都不刷」（刷得很欢），
不是「搜索盒太小」（128 格内也是 0），就是**生物群系**。这是这条 javadoc 从第一版
就写着、却从来没人量过的一句话。

`enderman.found` 从 **0/6 变成 2/6**，两只都打死了（695 / 307 tick）——
干等一轮只花一轮那一刀直接买到的。原来一分钟没等到就整级结束，
预算剩十一万八千 tick 没花。

**还红的两条**：

1. **两只打死，0 颗珍珠**（`pearls.perFight = ×没找到,×没找到,0,0,×没找到,×没找到`，
   `dropsNearby=0`）。末影人本来就是 0–1 掉落，两只全空是 25% 的正常概率，
   但也可能是**打死的地方在 8 格外**（挨打就瞬移）。一个样本说不出是哪个，别急着改。
2. **走不到森林 —— 身体泡在岩浆里**。这跟 14 级是同一条：

```
warped.survey    = warped_forest 在 136,41,-233（距 272 格，找了 6 ms）
warped.goto.1    = no path (expanded=1)
warped.around.1  = 脚下=lava 身处=lava 头顶=lava …（0/4 面是墙但身体泡在岩浆里）
warped.noAttempt = 第 1 次之后不再重试：身体泡在岩浆里（14, 22, 3，血 20）
warped.notReached= 走不到 …：停在 14,22,3，就地猎 —— 这一行说的是走位，不是这一级的成败
```

从 `8,41,7` 掉到 `y=22` 的岩浆里。**下界的横穿是 14/15 两级共同的上游问题**，
不是两个 bug。先修那个，这两级才有得谈。

> **2026-08-15 补**：这一段的机制已经问出来了，见上面「身体是走出了自己脚下那一格掉下去的」。
> 一句话：**不是走到岩浆边被岩浆挡住，是走出一格 netherrack 的边缘、掉 11 格进湖**，
> 而且第 8~32 格就发生，不是走了一百多格才发生。`no path (expanded=1)` 是掉进去**之后**
> 300 多 tick 才报的，是结果不是原因。

**顺带**：起手那条 `hunt.census` 报 `128 格内怪物共 0 只`，因为那一刻区块还没跟上来 ——
数字旁边印着钉住的半径，读的时候要认这一点。

## ⬜ 17–20 级仍然没有布景配方

15/16 已经有了（`crossToTheNether`，见 CHANGELOG）。剩下四级各自要的东西：

| 级 | 要摆的 | **绝对不能摆的** |
|---|---|---|
| 17 STRONGHOLD | 身体在主世界、12 只末影之眼、`JourneyRoute.stronghold` 像 recon 烘岩浆湖那样烘一个 | 要塞本身的**位置发现过程**（烘坐标 = 替 RECON，不是替本级） |
| 18 END_PORTAL | 身体站在要塞传送门房间里、12 只眼 | 填框这件事本身 |
| 19 END | 一座**填满但没激活**的末地门 + 身体站在门前 | 走进去 / 换维度 |
| 20 DRAGON | 身体在末地、装备齐、龙战没开始过 | 龙、水晶、塔 |

每一条都要进 `staging.calls` 账本，并且都只能在 `JourneyRehearsal.target() != null` 时生效。

## ✅ 六拓扑复验(寻路调参隔离 + 安全上限之后) + 两个必须留档的**偶发红**

改动: `PathTuning`(每个体的调参源, 活读) + `PathFinder.CEILING_MS = 8_000`(仅防进程死, 不改
`maxNodes` 的确定性语义)。**安全上限在全部六个拓扑里一次都没触发** —— 这正是它要的性质:
在本来就能跑通的运行里保持沉默。

| 拓扑 | 结果 |
|---|---|
| dedicatedServerFabric | GREEN |
| dedicatedServerNeoforge | GREEN |
| integratedServerFabric | GREEN |
| dedicatedServerWithClientFabric | GREEN(客户端半边也 GREEN) |
| dedicatedServerWithClientNeoforge | GREEN(重跑, 240 executed / 5 skipped) |
| integratedServerNeoforge | RED —— 见下面两条, 都不是寻路 |

隔离对照 `wd.horizon`: 每个测过的拓扑都是 `off=633 / on=firstExpanded=50`。**这条就是它自己的
回归测试** —— 哪天 `on` 又等于 `off`, 说明旋钮没接上, 别的再绿也没用。

### ⬜ 偶发红 1: `wd.pinch` ENV_FAIL "0 of 9 arena chunks ever loaded"

竞技场供给的偶发失败, 会**整个拓扑判红**。上一次出现后紧接着的一次重跑就过了。
成本低、重新发现代价高, 所以在这里留一行。

### ⬜ 偶发红 2: `wd.bridgeFootBlockStop` —— 4 次里红 1 次, 且**不是**改动引起的

`never approached the barrier (maxX=7.916 < 8)`, 证据里带 `escal=ON`。
统计: 改动前 GREEN ×2(`gate-`/`bar-`), 改动后 RED ×1 然后 GREEN ×1(同一棵树, 同一条命令)。

**查过并排除的**: "转义泄漏到下一个 goto" —— `Walker.setGoal`(:665) 和 `forceRepath`(:761)
都调了 `this.escal.disarm()`, 每体时钟确实被清了, 所以 `escal=ON` 是这一幕里**真的**触发了
boxed churn, 不是残留。剩下没有能站住脚的机制假设, 靠猜没用。

**不要当环境噪音划掉**: 这一轮里有两个"看着像 flake"的红, 里面各藏着一个真缺陷
(`wd.horizon` 的全局态、`clientReset*` 的清理链)。下次它再红, 值得带着上面这些证据查一次。

**这是一个真实的生产缺陷, 和 60 秒卡死那次崩溃无关** —— 两件事在一轮里撞到一起了,
下面把它们分开记, 免得下一个人把回滚读成"这条不成立"。

**链路(反编译字节码看出来的, 不是推断)**:

```
LevelWorldView.state(p) → Level.getBlockState(p)
                        → getChunk(x, z, ChunkStatus.FULL, requireChunk=true)
```

`requireChunk=true` 那条分支**不会**对缺失的区块返回 null —— 它**阻塞调用线程并把区块生成出来**。
证据是编译进该方法的断言字符串 `"Should always be able to create a chunk!"`。
而 `state()` 是 `isSolid` / `isHazard` / `isPassable` / `isWater` 和几个破坏判定背后的**唯一**接缝,
也就是说寻路每评估一步棋都走这里 —— 在**服务器线程**上。一次扇出到已加载边界之外的搜索,
可以让服务器停在那里等世界生成。

**已实现过的修法(commit 88dc06e, 已被 c653ca8 revert 掉)**: `state()` 先问 `level.isLoaded(p)`,
未加载的格子读作**基岩**。

**语义为什么选"不可通行"而不是"空气"**: 搜索本来就是照这个设计写的, 不是新发明 ——
`LevelWorldView.isKnown` 就是 `level.isLoaded`, 而 `PathFinder.bordersUnknown`(`PathFinder.java:1094`)
连同它的前沿规划已经把边界上的做法写死了: **认准朝目标那侧的前沿 → 走过去 → 让区块加载 → 下一次搜索再往前接**。
基岩让未加载的格子不可通行、不是危险、不是水、也不值得挖, 于是计划**停在前沿**;
读作空气则正相反, 会把身体送进想象出来的地形里。

**代价要说清楚**: 已加载半径之外的目标, 一次搜索不再够得着。
它本来也从来没真够得着过 —— 它是靠"从服务器线程把世界生成出来"够着的, 而那正是缺陷本身。

**为什么回滚**: 它**没有**修好那次崩溃(见下一节 —— 真凶是战斗循环没有每 tick 预算);
它是对生产寻路语义的一次大改; 而唯一的验证只有一次 Fabric gate(226 executed / 20 skipped, GREEN)。
更要紧的是: 如果它和战斗循环的整形一起落地、NeoForge 随后转绿, 那就**分不清是哪一个修好的**。

**重新落地的门槛**(不要偷懒, 一次 Fabric gate 看不见它):
1. 六个 topology 全过;
2. **外加一次 journey 阶梯实跑** —— 长距离那几级(第 13 级往上, 尤其下界横穿)正是
   "前沿之外不可通行"**最可能**弄坏的东西, 而 gate 里的场景都在小竞技场里, 压根走不到前沿。

## 🔴 `BotConfig` 里装着**每个个体每 tick 的运行时状态**, 于是两个个体互相改对方的旋钮

`wd.horizon` 在 integratedServerNeoforge 上红了一次: `off` 和 `on` **一模一样**
(`firstExpanded=633` 两边都是, 健康时 `on=firstExpanded=50 firstEndX=49 segments=5`)。
也就是说 `horizon=48` **完全没生效** —— 这一场什么都没量到, 却照样能报出一个颜色。

**机制(读源码定的, 不是猜的)**:

```java
// BotConfig.java:882
public static int pfHorizonBlocks() { return pathfinderBoxedEscalate ? 0 : pathfinderHorizonBlocks; }
// WalkerTickPrelude.java:83  —— 每个 walker tick 都写一次这个全局
BotConfig.pathfinderBoxedEscalate = escalating;
```

integrated 拓扑里**客户端的 Walker 活在同一个 JVM 的 Render 线程上**。日志里 06:22:50–52
连着三次 `[Render thread] [pathfinder] search-begin owner=goto ... goal=137896,221,100000`
—— 一个够不着的远目标反复重搜, 正是 boxed churn。它把 `pathfinderBoxedEscalate=true`
写进全局; 06:22:53 服务器线程上的 `HorizonArena.run(48)` 调 `pfHorizonBlocks()` 拿到 **0**,
于是和 `run(0)` 跑出完全相同的数。三件事一次对上: 只在 integrated 红(只有那儿有客户端 Walker)、
偶发(取决于那一刻客户端在不在 churn)、以及 `on` 和 `off` **逐字节相同**(不是变弱, 是彻底关掉)。

**这不是 `wd.horizon` 的 bug, 是一类 bug。** `BotConfig` 是进程级可变单例, 而下面三个
被标成 "PURE RUNTIME STATE / NOT persisted" 的字段, 装的都是**每个个体每 tick 的状态**:

| 字段 | 谁写 | 谁读 |
|---|---|---|
| `pathfinderBoxedEscalate` | `WalkerTickPrelude:83`(每 tick) | `pfHorizonBlocks/pfSoftCommitNodes/pfDepthPenalty` |
| `fleeActive` | `RunAwayProcess:97` | 寻路地形代价 |
| `walkerDigActive` | `WalkerTickClimb` 三处 + `WalkerTickPrelude:275` | `AutoSwim` 的防淹兜底 |

一个 JVM 里只要有两个个体(客户端 Walker + 服务端场景 driver, 或者将来**并行跑的两个场景**),
它们就在改对方的旋钮。**`pinnedBaseline()` 救不了**: 这三个字段在 `NON_PERSISTED` 里,
`persistable()` 直接把它们排除, 所以 `snapshotAll/restoreAll` 根本不快照它们 ——
让它"对持久化正确"的那个排除, 恰好让它**对隔离不可见**。何况就算快照了,
另一个线程每 tick 还在重写。

**正确的修法(还没做, 因为它是生产侧重构, 不该在没验证的情况下落地)**: 把这三个字段从
`BotConfig` 挪到**个体**上(Walker / driver), 寻路的三个旋钮在 `PathFinder` **构造时**传进去,
`pfHorizonBlocks()` 那三个 getter 变成实例解析。这样 `HorizonArena` 给自己那个
`new PathFinder(...)` 设值, 一个字节的全局都不碰; 客户端 Walker 的 escalation 只影响它自己。

**代价, 说清楚**: `new PathFinder(` 在 `common/src/main` 有 **7 处**
(Walker 3、PlanProbeTool 2、PinchArena 1、HorizonArena 1), 在 testmod 有 **30 处**。
加一个带默认值的重载, testmod 那 30 处可以不动, 真正要改的是 main 的 7 处
外加把 Walker 自己的 escalation 状态穿进去。`fleeActive` / `walkerDigActive` 的读者
(地形代价、AutoSwim)不在寻路构造路径上, 要把"哪个个体"穿到那两处, 是更大的一摊, 分开做。

**在修好之前, 并行跑场景是不安全的** —— 今天它污染的是一个 Render 线程上的寻路,
明天就是隔壁那个场景。

## 🔴 60 秒 watchdog 崩溃的真凶: 一个 server tick 里塞进了三千次寻路切片

`wd.serverFightsOneBlaze` 让 NeoForge dedicated gate 在 8 轮里红了 3 轮
(`ServerHangWatchdog detected that a single server tick took 60000004.00 seconds` ——
那是 60000004 微秒 = `max-tick-time=60000` + 4 µs, 显示单位是个 bug, 数字是真的)。

**两个假设都被量掉了**:
- *不是* loader 差异: 两个 loader 迭代次数一模一样(开阔天空 3000 / 封顶 40),
  NeoForge 每次迭代还更快(0.26 ms vs Fabric 0.45 ms), 总耗时 785 ms vs 1355 ms。
- *不是* 某一次失控的展开: 给 `PathFinder$Search.advance` 加了每次展开超过 100 ms 就报的护栏
  (commit 9d26804), **在崩溃那一轮里一条都没打印**。tick 跑了 60 秒而护栏全程沉默,
  所以**没有任何单次展开是慢的**。

**真正的算术**: 场景在**一个 server tick 里**同步跑 3000 次迭代, 每次迭代都可能推进一次搜索;
`BotConfig.pathfinderIdleSliceMs = 30`(`BotConfig.java:426`), 而 `WalkerTickSearch` 在身体
没有可走路径时**故意**用这个 idle 切片 —— 追一只够不着的烈焰人, 正好每 tick 都是这个状态。
3000 × 20 ms ≈ 60 s, 和 watchdog 的阈值严丝合缝。健康的一轮之所以只要 785–1355 ms,
是因为大多数迭代立刻找到路、根本没花切片。

**切片上限是好的, 没坏 —— 坏的是调用它的循环没有任何时钟预算。**
所以修法是把战斗循环整形成**跨 server tick 分摊**, 而且必须是**每 tick 的时钟预算**,
不是只加一次 yield: 一个 yield 了但仍然跑满 3000 次迭代的循环, 只是把问题减半。
整形时要保住 `blaze.tick()` 和 avatar tick 的交错 —— 那是真实约束, 不是巧合。

**留给以后的注意**: 上面那条"展开内部不检查 deadline"的结构性事实仍然成立
(`advance` 每 16 次弹栈才看一次表, 一次展开内部不看), 只是**这次**不是它。
9d26804 那条护栏留着不动: 它靠**不响**排除了一整个假设, 以后真响了就是真的。

## 第 23 轮: 全 20 级首次全部有实现 + 身体真正入服

**结果**: 爬到 OBSIDIAN(11 级), `staging.calls=0`, 第 12 级红。

**这一轮验证掉的三件事**:
1. **`-Dworlddriver.realPlayerBodies=true` 不会破坏下面的梯子** —— 1–11 级全绿。
   这个开关是龙/刷怪笼/自然生成三条的唯一解(它们都读 `level.players()`), 之前不敢开,
   现在有 11 级实测背书。
2. **炼铁修复在真实攀爬里成立** —— 第 9 级 `铁锭 ×5 出炉`(不再是竞技场里的孤证)。
3. **第 12 级的新护栏在真实攀爬里成立** —— 它**预测**射线落点、发现和目标不符, 于是**拒绝倒**:

```
想浇 -9,52,23（瞄背板 -9,52,24），射线会把流体放进 -8,52,22，
身体在 -8,51,22 —— 没有倒；倒下去 use 照样报 CONSUME
```

最后半句是把 `CONSUME` 那条教训写成了前置条件, 而不是每次再从下游反推一遍。

**卡点仍是站位**: 身体站在 -8,51,22 想浇 -9,52,23 —— 斜着且低一格。倒的那侧已经有
`standToPour`, 装桶那侧还没有, 而且这轮连倒的那侧都抽到了坏站位。
正在用彩排模式(2–5 分钟一次)修这一处。

**结构性进展**: 13–20 级现在报的是 `BLOCKED: 上游未达成`, 不再是 `NOT_SCRIPTED` ——
它们背后是真实现了。整个梯子只剩第 7 级(床)真的没写, 而它不 gating。

## 并行攻坚的进展(subagent 四路 + 拆分一路)

**已修并已验证**:
- `SmeltProcess.collect()` 无条件报成功 —— 背包满时 `quickMoveStack` 一个也不搬, 铁锭留在炉里,
  而 `lastError==null`。**炼成了和没炼过在断言上完全一样。** 已加负向对照复现症状后修掉。
  新竞技场场景 `wd.serverSmeltDeliversTheIngot` 是仓库里第一个**真的让炉子烧起来**的场景
  (原有三个 smelt 场景把 avatar tick 塞在一个 server tick 里, 炉子根本不 tick, 其中一个还是手工塞结果)。
- `ServerWorldDriver` 的 `LevelWorldView` 是构造时建好、终身不换的 ——
  **身体进了下界之后, 每一次寻路都在用主世界地形配下界坐标**, 而且只报"没走到", 看不出原因。
  已改成 `world()` 发现层变了就重建, 并把 `tick()` 里两处直接读字段的地方改走访问器
  (只改 getter 是没用的, 那两处才是真正在跑的)。
- `WorldDriverProcessScenes.java` 3713 → 2036, 按主题拆成 portal/mobFight/smeltDelivery 三个类。
  用 `git show HEAD` 做了逐字节比对 + 一次完整无过滤 gate: 253 scenes, `MISSING-EXPECTED: 0`,
  `UNEXPECTED: 0`, **VERDICT GREEN**。

**新写好但一次都没跑过**: 第 14/15 级(`JourneyNetherRungs`)、第 16–20 级(`JourneyEndRungs`)。
两者都只暴露 `rungs()`, **还没接线**。

**待办(等 journey 文件所有权交还)**:
1. 接线两个 `rungs()`, 并删掉会重名的 7 条 `unscripted(...)`;
2. `runJourneyServer` 加 `-Dworlddriver.realPlayerBodies=true` —— 一个开关同时解锁龙的生成、
   烈焰人刷怪笼、末影人自然生成(三条都卡在 `level.players()` 为空);
3. `WorldDriverJourneyScenes.java` 现在 3789 行, 还超预算, 要按同样的机械拆法拆掉。

**顺带记录**: `wd.serverEscapeSealedShelter` 在三轮 gate 里绿一次红两次 —— 是**既有**的
optional 传感器抖动, 与本次拆分无关(拆分那位没碰过它所在的文件)。

## 🔴 两件必须处理的事(subagent 并行开工后暴露出来的)

### 1. source-budget 硬闸现在是红的

```
3811  common/src/testmod/.../journey/WorldDriverJourneyScenes.java
3713  common/src/testmod/.../scene/WorldDriverProcessScenes.java
```

两个都超了 3000 行硬上限。这是 `python scripts/check_source_budget.py` 的硬闸,现在不通过。
等 agent 交还文件所有权后必须拆(参照 `WalkerTick*` 的机械拆分法)。
**新写的 `JourneyEndRungs.java`(1433 行)不在其中**,它是干净的。

### 2. 龙根本不会被创建 —— 因为身体不在 `level.players()` 里

写第 20 级的 agent 读出来一条关键机制:

> `EndDragonFight.tick` 每 20 tick 重扫 `ServerLevel.getPlayers(...)`, 只要那个列表是空的,
> 它**什么都不做** —— 不建竞技场票据、不 `scanState`、不 `createNewDragon`。

而这条链路上的身体是 `FakePlayer`, 从来没走过 `PlayerList.placeNewPlayer`, 所以**永远不在
`level.players()` 里, 龙也就永远不会生成**(水晶照样存在, 那是世界生成放的, 会造成"看起来
只差龙"的错觉)。

这与 memory `a-fake-player-was-never-placed` 完全同源("joining beats copying Player.tick")。
**解法是已有的开关 `-Dworlddriver.realPlayerBodies=true`(`JoinedPlayerBodies`), 不是造假龙** ——
给龙摆一条也算 staging, 会毁掉 `staging.calls=0`。

**待办**: `:fabric:runJourneyServer` 要带上这个 flag; 但 `fabric/build.gradle` 当前由另一个
agent 持有, 等它交还后再接线。同时要确认这个 flag 不会破坏下面十几级已经绿的行为。

# TODO

> 镜像 Task 跟踪器的长期工作。重要根因写进 memory(reference/project)。

## 🧭 下一级设计: `wd.journey12PortalLit`(N5) —— 十块黑曜石怎么来【技术已在竞技场证完】

黑曜石那一级浇的是**一块**。门框要十块(4×5 去四角), 而黑曜石**放不下去** —— 身体手里永远不会
有黑曜石物品, 没有钻石镐就挖不起来。所以门框只能**就地浇**: 这是"挖模具"的问题, 不是"搭建筑"
的问题, `BuildProcess`/`Schematic` 在这一级用不上。

**成本决定选型。** 一次"下井→装桶→爬回地面"实测 4464 tick。十块就是十趟, 四万多 tick。所以门框
**建在岩浆边上**, 不建在地面:一次下井, 水只搬一次, 之后每次装桶是走几格而不是爬三十六格。
这一级的断言是"点着的传送门", 没说在哪儿。

### ✅ 2026-08-11 `wd.serverCastsAPortalFrame` 绿 —— 但原定步骤第 4 步是错的

原计划是"十格都灌成岩浆源, 再在岩壁顶上放一桶水顺着面淌下来, 十格一起转"。**跑出来是 `0/10`。**
下面三条是实测掉进去又爬出来的, 每一条的症状都出现在原因的**两步之后**, 这是最贵的一类失败:

1. **水顺着一格厚的岩壁淌下来, 一个兜都进不去。** 下落的水在**落点**才铺开, 而竖直面上的兜
   没有地板给它铺。
2. **先灌满岩浆最后统一浇水**: 第一格浇歪, 桶里还是满的, 于是第二格报"没有空桶", 而真正的错
   在两步之前。
3. **一个水源放内框让它自己流满十格**: 做不到, 两个独立原因 —— 场景 tick 的是**身体不是世界**,
   根本没有流体 tick;就算真跑, 水也不会**往上**流。

**能跑通的做法是把水搬着走。** 把水源放进"正在浇的那一格**相邻的内框格**", 于是每一格都复现
`wd.serverCastsObsidian` 已证的几何, 而且**完全不需要流动** —— 转黑曜石是 neighbour update, 不是
流体 tick。一只桶就够, 顺序自己排好了:放完水桶是空的(去装岩浆), 倒完岩浆桶又是空的(把水收
回来)。**水井只去一次。**

三个必须写进阶梯的数字:

- **顶上那一排不能靠内框浇。** `LiquidBlock.shouldSpreadLiquid` 查的是
  `{DOWN,NORTH,SOUTH,WEST,EAST}.getOpposite()` —— 也就是**上面**加四个侧面, **从来不查下面**。
  水在岩浆底下什么都不会发生。所以顶上两格要靠**再往上掏一格**的凹口来浇, 掏进岩壁的门框成本
  是 **12 格**不是 10 格。搞错了唯一的症状就是那两格躺着岩浆。
- **岩浆湖不是一格。** 装一次桶拿走的是**源块**, 留下空气。十次浇筑要**十个不同的湖格**和十趟
  走。第一版布景只放了一格岩浆, 第二次装桶读成了"装桶坏了"。
- **站位必须是目标格的函数。** 桶是往"射线打到的那个面的邻格"里灌, 所以要瞄**目标格背后的实心
  块**(瞄空气格什么都拦不住);但这只在射线接近**水平**时才落在想要的格子上。从固定台阶瞄高五格
  的目标, 射线**低一格**进墙, 打在刚浇好的黑曜石上, 于是水没收回来、下一格报"没有空桶"。
  站到 `feet = max(floor+2, target.y - 1)` 一次解决所有格。

实测: `10/10`, 十次搬水, 内框干的, 桶带着水回来。两个 loader 都绿, ~330 ms。

### 还欠现场的两件事(竞技场答不了)

- 竞技场的模具是**掏出来的**, 现场没有现成的两层岩壁 —— 阶梯要么找一面, 要么用圆石**砌**一个
  (背板 + 围边约 30 次放置)。这是 `wd.journey12PortalLit` 真正的工作量, 不是浇筑。
- 身体站在岩浆边浇十次会不会被点着。`bodyIsInvulnerable` 已经记在每条绿行里, 所以这一级如果
  靠无敌活下来, 记录会说。

~~- 点火那一下走 `useItemOn` 还是 `use`?~~ **查过了(2026-08-10), 是 `useOn`, 和桶正好相反。**
  `FlintAndSteelItem` 只覆写 `useOn(UseOnContext)`, **没有 `use`** —— 所以点火必须走
  `Avatar.useBlock(cell, face)`, 走 `useItemInHand()` 会拿到 `Item.use` 的默认 `PASS`, 世界纹丝
  不动。**这就是桶那个坑的镜像**: 桶必须 `use`、打火石必须 `useOn`, 而两边猜错的表现一模一样。
  点的那一格如果自己不可点燃(黑曜石就不是), 火会放到 **`clickedPos.relative(clickedFace)`** 去,
  所以要**点门框的黑曜石、face 朝内框**。

## 第 18 轮: 第 12 级的根因**确定了** —— 模腔是个"厅", 不是十个"兜"

快速失败(第一格不成就停)立刻拿到了要的三个值, 1 分钟而不是 32 分钟:

```
water0.result=CONSUME     cast0.result=CONSUME
第一格就没浇成黑曜石：-9, 56, 23 = air（水在 -9, 57, 23 = air）
```

**两只桶都成功倒空了(`CONSUME`), 两格却都是空气。** 所以这从来不是瞄准、够不着、或者没拿在手上
的问题 —— 流体**倒进去了, 然后流走了**。

`forgeCells` 挖的是一整块 **7 高 × push 深 × 5 宽的连通空腔**。水和岩浆在一个连通的大厅里
不会停在任何一格。这把"顶排两格没有底"那个解释推广到了全部十格, 也正好解释了为什么
**砌出来**的竞技场模腔能浇 10/10: 它是实心岩石里挖出的**一个个独立凹兜**, 不是一间屋子。

**注意"只挖凹兜"没那么简单** —— 站位是被 `placeFluid` 逼出来的: 它把身体走到
`new BlockPos(stand.getX(), target.getY(), stand.getZ())`, 也就是**与目标格等高、后退两格**。
所以每一格高度(y=0..5)身体都得有地方站, 那个"竖着的房间"**不能直接删掉**。

真正的冲突在**门框平面内部**: 每个 RING 格的左右邻居就是别的 RING/内框格, 全被挖空了,
所以十二格在同一个平面里互相连通 —— 流体从这里跑掉。

## 第 22 轮: 又死在第 6 级, 而且是它的**第二种**死法 —— 三轮连败, 论据封口

```
raw food collected from the kill (0) expected to be at least 1.0
```

动物**杀了, 肉没捡到** —— 和"方圆 96 格没有动物"是两回事(同族: memory `mined-is-not-collected`)。

**第 19/21/22 三轮连续死在第 12 级之下, 三个互不相同的原因**(第 6 级找不到动物 / 第 9 级炼铁 0 锭 /
第 6 级杀了没捡到)。**第 12 级那两处已提交的改动, 至今一次都没被执行过。**

至此"改一处→重跑整梯"这条路已经不是慢, 是**验证不了**。前沿以下各级独立抖动的累积,
让单轮到达第 12 级的概率掉到一半以下。

**接手顺序(不要跳过第 1 条)**:
1. **从已知状态续跑** —— 第 11 级过关后快照世界+身体+背包, 让第 12 级可单独重跑;
2. 第 6 级: ① 需要一条去草地群系的腿 ② 杀了要确认捡到(掉落物拾取);
3. 第 9 级: 炼铁 0 锭(矿挖到了, 熔炼没成);
4. 第 12 级: 验证已提交的 just-in-time 挖掘 + 1200 tick 预算, 看 `没挖开就要浇` 这条断言;
5. 再谈 14–20 级(需要先勘测下界要塞地标)。

## 第 21 轮: 死在第 9 级(炼铁 0 锭), 改动又没被跑到 —— 这就是"续跑机制"的结论性论据

```
iron ingots smelted (0) expected to be at least 1.0
```

矿挖到了(`vein1.iron=83...`), 但**一锭都没炼出来**。第 9 级此前十几轮都过, 这是它自己的第二种
死法(第一种是竞技场区块, 已修)。

**把这一整轮的抽样摆出来** —— 前沿以下每一级都有各自独立的偶发失败:

| 级 | 已知死法 | 状态 |
|---|---|---|
| 6 食物 | 动物会走出 96 格(沼泽出生只有 cat/frog) | **未修** |
| 9 铁 | ① 竞技场区块 ② **炼铁 0 锭** | ①已修 ②**未修** |
| 11 黑曜石 | ① 草挡瞄准线 ② 竖井上限 ③ 挖过头 | 全部已修(连绿四轮) |

**结论(这一轮买到的最有价值的东西)**: 单轮跑到第 12 级的概率现在大概只有一半左右, 而每一次
前沿实验都要先赌这一把、再花 25 分钟。第 19/21 两轮的改动都是**写好、编译过、提交了、
但一次都没被执行**。

**所以下一位接手的人: 先做"从已知状态续跑", 不要再用"改一处→重跑整梯"去推第 12 级以上。**
把第 11 级通过后的存档 + 身体 + 背包快照下来, 让第 12 级能单独重跑 —— 这件事的收益
已经不是"省时间", 而是"改动能不能被验证"。

## 第 20 轮: just-in-time 挖掘跑到了 —— 从"什么都没发生"变成"反应发生了但形态不对"

11 级绿(第 6 级这轮过了)。第 12 级第一次执行逐格挖掘, 失败信息完全变了:

```
第一格就没浇成黑曜石：-9, 56, 23 = Block{minecraft:cobblestone}
（水在 -9, 57, 23 = Block{minecraft:stone}）
```

**两条独立的事实, 都比上一轮前进了一大截**:

1. **目标格是圆石, 不是空气。** 说明**反应真的发生了** —— 只是形态错了:
   **岩浆源 + 水 = 黑曜石, 流动岩浆 + 水 = 圆石**。上一轮是两格全空(流体全跑光), 这轮流体留住了。
   所以"逐格挖掘恢复了底面"这个判断是**对的**, 底面问题解决了。
2. **水那一格还是 stone —— 根本没被挖开。** `mineCellOrGiveUp(wet, 400, ...)` 没挖动它,
   于是水是朝着一块实心石头倒的。

**下一步(两件, 都很小)**:
- **先验证挖开了再倒**: `mineCellOrGiveUp` 是"挖不动就继续"的语义(这正是当初为它写的),
  所以它静默失败了。在 `castOpenedCell` 里加一条: 若 `cell`/`wet` 任一不是空气就直接
  `ctx.fail` 并报出是哪一格 —— 现在这条信息是靠反推出来的。
- **查为什么挖不动 `wet`**: 400 tick 不够? 还是身体站的位置够不到那一格(它比 `cell` 高一格)?
  `opened.0` 这条证据已经会打印两格的实际方块, 下一轮直接能看。

**注意: 圆石这一条也说明, 一旦 `wet` 真的挖开、水成为源块贴着岩浆源, 就应该出黑曜石。**
现在离第 12 级变绿只差"把那一格挖开"。

## 第 19 轮: 修法已实现并提交, **但没被跑到** —— 死在第 6 级(食物)

```
方圆 96 格内没有掉落食物的动物 —— 附近只有 [minecraft:cat, minecraft:frog]；
出生点是沼泽，这一级可能需要一条先去草地群系的腿
```

**更正一处我自己说错的话**: 我之前写过"前沿以下四处抖动都已修好"。**不对。**
修好的是三处(第 9 级竞技场等待、第 11 级的草、第 11 级的竖井上限), **第 6 级的"动物会走"
从头到尾只是被描述过, 从没被修过**(见 memory `a-landmark-that-walks`)。这一轮它就是死因。

**第 6 级的修法(未实现)**: 出生点是沼泽, 沼泽只刷 cat/frog, 都不掉肉。所以这一级不能只在
出生点附近找 —— 它需要**一条先走去草地/平原群系的腿**, 或者在 recon 时就把最近的
"会掉肉的动物所在的群系方向"记成地标。前者更贴合"零布景"的约束。

**第 12 级的 just-in-time 挖掘已经写好、编译过、提交了(`3649585` 之后那一笔), 只是还没被执行过。**
下一轮只要能过第 6 级就能验证。

## ✅ 第 12 级的修法定了(读竞技场那版读出来的, 不是猜的)

`wd.serverCastsAPortalFrame` 的模腔**一开始是实心的**: 门框那一面(`z=cz`, dx -3..4, dy 1..8)
整片砌成石头, 后面 `z=cz+1` 再砌一层做背板, 底下 `floorY` 一整层石头。**每一格是在轮到它浇的
时候才挖开的**, 其余十一格那时都还是实心。

而 `forgeCells` 把十二格**一次全挖空**。这恰恰破坏了 `RING` 顺序本来就设计好的不变量:

```
RING = (0,0),(1,0),(-1,1),(2,1),(-1,2),(2,2),(-1,3),(2,3),(0,4),(1,4)
```

- `(0,0)`/`(1,0)` 的底是 dy=-1, 没被挖 → 实心, 有底;
- `(-1,1)` 的底是 `(-1,0)`, **不在 RING 里**, 没被挖 → 实心, 有底;
- `(-1,2)` 的底是 `(-1,1)` —— 是 RING 格, **但顺序上它已经先浇成黑曜石了** → 有底。

**也就是说每一格的底, 要么是没动过的岩石, 要么是上一格刚浇出来的黑曜石 —— 顺序就是为此排的。**
一次全挖空之后, 这些底全成了空气, 流体自然全跑光, 于是两只桶都 `CONSUME`、两格都是空气、
十格全丢。

**修法(一处): `forgeCells` 只挖走廊/站位空间, 门框那十二格改成"轮到谁挖谁"** ——
在 `castCell(i)` 里, 浇之前先挖开 `cell` 和它的 `wet`, 其余保持实心。
`mineCellOrGiveUp` 已经具备, 顺序不用动, `RING` 不用动。


**⚠️ 上面那个"内框保持实心"的方案是错的, 别照做。** 检查一遍浇筑技法就知道:
`wetCellFor` 放水的那一格, 对底排是**内框格** `(dx,1)`, 对顶排是**凹槽** `(dx,5)` ——
也就是说**水正是放在内框和凹槽里的**, 它们必须是空气才能接住水。把它们留成实心, 水根本没地方放。

**所以真正要先搞清楚的是: 竞技场那版到底靠什么让流体停住。** 两版共用 `RING`/`wetCellFor`/
`castCell`, 竞技场 10/10, 旅程 0/10, 而旅程这版**两只桶都 `CONSUME` 了、两格都是空气**。
差别只可能在模腔的**边界**上 —— 最可疑的是竞技场的模子有一层**整体的底**
(`frameCell(base, away, dx, -1)` 一线实心), 而旅程这版靠的是"没挖到那一层所以碰巧是实心",
在挖空的大厅旁边未必成立。

**下一步(必须先做, 只需读代码不需要跑)**: 打开 `wd.serverCastsAPortalFrame`, 逐格对比它
建出来的模腔边界和 `forgeCells` 挖出来的边界, 找出"哪一面竞技场有、旅程没有"。
在没弄清这一点之前不要再改几何 —— 这一轮已经证明,
凭对机制的猜测去改, 换来的是一次 32 分钟的错误确认。

**(以下为已被否掉的顺序方案, 保留作对照)**

~~因此顺序才是修法, 不只是形状~~:
1. 先只挖**走廊/站位空间**(d < push)和 **RING 那十格**, 内框六格 + 顶部凹槽**保持实心** ——
   这样每一格 RING 的背面、底面、以及朝内的那一侧都有实体挡着, 是个真正的兜;
2. 十格浇完(它们已经是黑曜石, 自己就成了彼此的壁)之后, **再**把内框六格挖空;
3. 最后点火。

这也更接近玩家的做法, 并且不需要任何新的引擎能力。风险: 第 (2) 步要在浇完之后能挖到内框,
身体站位和 `mineCellOrGiveUp` 都已具备。

**旧的一句话版本(保留作对照)**: 只挖必要的部分 ——
- 身体站立/通行的走廊;
- 门框那十二格**本身**, 每一格四周(底、背、两侧)保持实心, 只留身体瞄得进去的那一面。

也就是 `forgeCells` 里 `for w = -2..2` / `for y = 0..6` 那个整块循环要拆掉, 改成
"走廊 + 逐格凹兜"。这是第 12 级现在**唯一**已知的阻碍。

## 第 16/17 轮: 竞技场那一类失败**消失了**; 第 12 级第一次跑完十格, 0/10

**两处修复都验证了**:

1. **`Scene.withArena(false)` 落地(stagewright 侧, 加法式改动, 默认 true, 222 个既有场景不受影响)。**
   它只砍掉**等待**: 竞技场照样 force-load、照样 audit, `ctx.level()` 不变。
   第 16 轮 1–10 级全部执行, **一个 ENV_FAIL 都没有** —— 第 7/14/15 轮就是被这条毁掉的。
   之所以安全: 旅程场景一个都不读 `ctx.origin()`(提交前 grep 确认)。
2. **第 11 级的竖井上限**: 它一直在用 `depth*3+20 = 128`, 而**同样 36 格**的第 12 级早就有了
   `depth*8+60`。这处不对称我在第 2 轮就写进过 memory("promoted 级里的潜伏抖动")然后没管;
   第 16 轮它在 y=31 差 4 格失败。现在两级一致: `36 格深，给 348 次尝试`(日志确认生效), 绿。

**第 12 级第一次把十格全浇完了 —— `frame.cast=0/10`, 39240 tick(32 分钟)。**

**上一轮那个假设被证伪**: 我把"水前置"放宽, 理由是"底排的水会落进目标格, 往那格倒岩浆照样出黑曜石"。
**不成立 —— 一格都没出。** 不是第 1 格或第 3 格的问题, 是这个挖出来的模腔**根本浇不出黑曜石**;
竞技场那版能浇, 旅程这版不能。差别就在模腔形状本身, 不在某一格。

**下一步**:
1. **先把快速失败加回来**(浇完第 1 格若没出黑曜石就停) —— 现在一次失败要 32 分钟, 而信息量
   和第 1 格就停是一样的;
2. 再去比对竞技场 `wd.serverCastsAPortalFrame` 和这里的几何差异: 竞技场的模腔是**砌出来的**
   (有底、有背), 旅程的是**挖出来的**(整块掏空)。八成就是"每一格都要有背面和底面"这件事。

## 第 15 轮(已回滚): 把竞技场缩到半径 0 = 把偶发故障变成必然故障

我把每一级的 `withChunkRadius(0)`(1 个区块, 而不是默认的 9 个)当成"少等 9 倍的地"来用。
结果**每一级都 ENV_FAIL**, 阶梯只爬到 RECON。已 `git revert`, 树是干净的, 能编译。

**为什么错**: `arenaReady` 要的是 **entity-ticking** 区块, 而原版要求一个区块的**邻居**也加载
才会升到那一级。半径 0 的票据永远造不出一个 entity-ticking 区块 —— 所以它不是"等得少",
是"永远等不到"。

**但这一轮买到了一件真东西**: 报错是 `only 0 of 1 arena chunks ever loaded`。
**0/1 和 0/9 一样失败, 说明第 7、14 轮那条偶发故障也不是"数量/资源压力"问题** ——
我上一条把它归给"连跑七轮的磁盘压力"是没有证据的猜测, 这里可以划掉。请求根本没被服务,
和要几个区块无关。

**因此结论不变、反而更硬**: 缩小竞技场无解, 唯一的解是**根本不等** ——
stagewright 侧的 `Scene.withArena(false)`(PREP 跳过 `forceChunks`/`arenaReady`/`ArenaAudit`,
照常构造 `SceneContext`)。旅程场景一个都不读 `ctx.origin()`(已 grep 确认), 所以不会有损失。

## 第 14 轮: 竞技场那条环境故障复发, 而且一轮里连中两级

第 9、第 10 两级同时 `only 0 of 9 arena chunks ever loaded after 201 ticks`, 阶梯只爬到 FURNACE,
verdict 判**回退**(低于已承诺地板 PORTAL_KIT)。本轮改动(放宽第 12 级的水前置)**根本没被跑到**。

**这条故障第 7 轮出现过一次, 现在一轮里出现两次 —— 在变频繁**, 很可能与连续跑了七轮完整阶梯、
每轮都新生成世界带来的磁盘/资源压力有关。

**这正是"旅程场景应该能拒绝竞技场"那条设计要解决的**(见上文): 梯子上每一级都在等一块十万格外、
自己永远不会踏进去的地; 这九个区块和这一级的主张毫无关系。在 stagewright 侧加
`Scene.withArena(false)`(PREP 跳过 `forceChunks`/`arenaReady`/`ArenaAudit`, 照常构造
`SceneContext`)之后, 这一整类失败会从阶梯上消失。

**优先级上调**: 之前把它排在"续跑机制"之后, 但它现在是**唯一一条会让已经修好的级无故变红的**
故障, 而且每命中一次就废掉一轮 25 分钟。应当先做它, 再做续跑, 最后才谈 14–20 级。

## 第 13 轮: 模腔终于是干的了, 卡点换成"水站不住"

外推选位生效 —— 第 12 级这轮**挖出了一个不含流体的模腔**(上一轮是挖进了湖体), 然后死在第 1 格的水:

```
水没放进去：想放 -9, 57, 23（第 1 格的相邻内框），那格现在是 Block{minecraft:air}
```

**新卡点**: 模腔是"挖空的壁龛", 所以内框那一格**下面也是空的** —— 水浇进去直接落到龛底,
不会停在需要它的那一格。这是 memory `water-is-not-a-floor` 那个形状换了个深度重演:
**一面竖壁上的凹坑没有底, 落下的水在它落地的地方铺开, 不在你瞄的地方。**

**下一步(未实现)**: 浇水那一格必须**有底**。两条路, 都不用加引擎能力:
1. 挖模腔时**不要挖穿内框下面那一层** —— 只挖身体站的通道和门框本身, 让每个内框格底下留实心;
2. 或者浇水前先用圆石把那一格的底垫上(背包里第 11 级之后常年 80+ 圆石)。

第 (1) 条更干净: 现在 `forgeCells` 是拿 `y=0..6 × d × w=-2..2` 一整块挖空的, 太贪。
门框只需要身体能站到、能瞄到, 不需要整块龛都空。

## 第 12 轮: 第 11 级修好了(已验证), 第 12 级推进到"挖模腔挖进了岩浆"

**第 11 级的真 bug 找到并修好了**, 这一轮绿, 而且是**看得见地**绿:
浇黑曜石的位置从历轮的 `-6,62,55` 变成了 `-17,62,42` —— 身体走到了**另一处**岩浆池,
正是那条被放开的搜索在起作用。

bug 本身: `reachLava` 里那条 24 格的兜底搜索, 条件写的是 `other != null && climbBacks > 0`。
而这条搜索存在的理由**恰恰是"爬回没用"**(它自己的注释里记着两次爬回一格没动)。
把它挂在爬回的计数器上, 等于在最需要它的那一刻把它关掉 —— 第 11 轮就是站在 y=14 的洞里
报 `已用完 2 次爬回机会`, **从头到尾没问过二十四格内有没有岩浆**。改成用隧道自己的步数预算
`left` 计, 并且失败信息现在会区分"24 格内也没有"和"有但预算用尽了"。

**第 12 级又往前走了一格, 死法再次是新的**:

```
要挖的格子里有流体：-9, 57, 21 = Block{minecraft:lava} —— 换个面再挖，别把岩浆放进来
```

上一轮的修法(模腔挖在岩浆下方 7 格)对**地表湖**是错的: 湖在 y=63, 往下 7 格
**还在湖体自己里面**。模腔需要的是**离岩浆足够的水平距离**, 不只是垂直深度 ——
现在 `base = at.relative(away, 2)` 只离身体 2 格, 而身体就贴着岩浆柱下来。

**下一步(未实现)**: 选模腔位置时, 要求这 12 格 + 站位在**水平**上离最近的岩浆源 ≥6 格,
并且整块都是实心非流体; 挑不出来就换一处池子, 而不是就地开挖。

## 第 11 轮: 第 11 级第三种死法 —— 挖过头 12 格

```
下到岩浆层却看不到岩浆源：停在 -4,14,53，周围 8 格内没有 source 级岩浆
（流动岩浆不能装桶）；比岩浆层低 12 格，且已用完 2 次爬回机会
```

这条路径是**第 11 级自己的下降**, 与本轮改动无关(`forgeFloorY` 只被第 12 级的
`descendToTheForge` 用到)。

**第 11 级到目前为止三种互不相同的死法**: 第 9 轮被 `short_grass` 挡住瞄准线、
第 11 轮挖过头 12 格、其余轮通过。也就是说**它本身就不是一级稳定的梯级**, 而它已经是
"promoted" 状态 —— 这正是 memory `three-greens-cannot-see-a-one-in-four` 说的那件事:
按绿灯次数提升, 会把只在特定地形下才出现的失败留在梯子里。

**挖过头这件事本身是可修的**: 下降的终止条件应当是"脚下这一层能看到 source 级岩浆",
而不是"到达某个 y"。现在是到了 y 就停, 于是井打偏一格、岩浆在旁边而不在下面时, 它会
一路穿过岩浆层继续往下, 然后用两次爬回机会去补救。

**每轮 25 分钟、前沿之下三处已知抖动(第 6 级动物会走、第 9 级竞技场区块、第 11 级下降),
继续用"改一处→重跑整梯"推进第 12 级以上是不划算的。**先做从已知状态续跑, 再谈 14–20 级。

## 第 10 轮: 两处修复都成立, 第 12 级推进到"浇第三格"

11 级绿(到 OBSIDIAN), `staging.calls=0`。两处改动都被真正验证:

- **第 11 级的植物清理成立** —— 上一轮就是被 `short_grass` 挡死的, 这轮用身体自己的
  `aimAtBlock + breakHold(true) + continueDestroy` 挥掉了, 这一级重新变绿。
- **岩浆湖地标成立** —— 第 12 级第一次真的开始浇: 走到了 `lavaLake -9,63,19`, 下降、挖模、
  头两格都浇成了, 死在**第 3 格的水**:

```
水没放进去：想放 -9, 65, 23（第 3 格的相邻内框），那格现在是 Block{minecraft:air}
```

**新的原因(不是老的)**: 这一级的技法是"在实心岩石里挖一个模腔", 而唯一可用的岩浆湖在
**地表 y=63**。于是门框上半部分(y=65 那几格)是**露天的空气**, 没有背面挡着 ——
水浇下去直接流走。**模腔必须在湖的下面, 不能在湖那一层。**

**下一步(未实现, 未验证)**: 把 `descendToTheForge` 的目标从 `lava.getY() + 1` 改成
`lava.getY() - 6` 左右, 让整个 12 格模腔都埋在岩石里, 装桶时向上爬六格。
风险已知可控: 第 11 级本来就带着岩浆从 y=26 爬回 y=62(36 格), 六格的往返在已证明的能力之内。

## 第 9 轮: 岩浆湖地标生效了, 但第 11 级被一丛草挡住

侦察按新逻辑选出了 `lavaLake = -9, 63, 19`(72 格源块, 能下井), 第 12 级第一次有了正确的地标 ——
**但这一轮没走到那里**: 第 11 级(黑曜石)红了, 阶梯停在 PORTAL_KIT。

```
浇筑瞄准线被挡住，且清不掉：想浇 -4, 62, 54（瞄 -4, 61, 54），
射线停在 -4, 63, 55 Block{minecraft:short_grass}
```

**这是同一个形状第二次出现**(上一次是 seagrass)。共同点: 一种**没有碰撞箱**的植物 —— 身体
直接穿过去 —— 却能挡住瞄准射线, 因为 `getPlayerPOVHitResult` 按 `Block.OUTLINE` 求交,
而植物没有碰撞箱也有轮廓。而且它**可被替换**: 照倒不误的话 `BucketItem` 会把岩浆倒进草那一格,
于是失败会被写成"浇不出黑曜石", 而黑曜石就在一米之外。所以这级**拒绝倒**是对的。

**缺的是"清掉它"**: `rig.mineBlock` 清不动这类方块 —— seagrass 量到过两次连续清理毫无变化,
short_grass 这次一次。代码里那条注释把它记成"a separate finding"并选了"一开始就别选被挡的格子"
作为正解; 两轮之后真正开火的是兜底分支, 所以这条被搁置的发现现在就是挡路的那一条。

**不要用 staging 去清**: `level.destroyBlock` 能修好这条线, 但会毁掉这级的全部主张 ——
`staging.calls=0` 正是阶梯的意义。修法必须是身体已经有的动作。

**下一步**: 查 mine 进程对零掉落植物做了什么(是跳过了, 还是"挖了但没消失"), 这决定是修
mine 进程还是给浇筑加一个"先站到一条干净的线上"的选位。

## 量出来了: 侦察报的 12 处"岩浆池", 十处只有一格

给每处数了源块之后, `poolsInBand` 里"池"这个词的真实含义才露出来:

```
68,27,-1  = 1 (下不去井)   -6,26,54  = 1  <- firstLava
-9,63,19  = 72             <- 唯一一处能下井的岩浆湖, 而且在地表
84,-14,47 = 1 (下不去井)   30,6,2    = 1     64,2,-10  = 1 (下不去井)
1,-2,84   = 1              79,-16,7  = 1 (下不去井)
79,-33,45 = 1 (下不去井)   103,-32,41= 1 (下不去井)
137,21,134= 97 (下不去井)  43,-36,106= 1
```

**那份列表里的"池"= 一次岩浆命中, 且与其它命中相隔 N 格 —— 从来不是"一片岩浆"。**
`firstLava` 恰好只有一格源块, 所以 OBSIDIAN 级不是把它用掉一部分, 是把它**抹掉**。
第 12 级五轮报的"48 格内一格都没有", 到此完全对上了。

**一个记下来的错误预测**: 从"y≈27 没有岩浆"我推断真正的岩浆应该是基岩附近的岩浆海, 并且
那几处深的(y = -14/-16/-32/-33/-36)就是。它们也都是单格。唯一可用的湖在 **y=63 的地表** ——
正好是推理指向的反方向。**一个搜索没扫的层, 不是关于那一层的证据。**

**"大"和"下得去"是两个条件, 必须一起筛**: 最大的一处(97 格)恰好过不了当年否掉最近那处的
下井检查。所以 `chooseALavaLake` 先跑 `pickDigColumn` 再排序, 并把 `(下不去井)` 逐处记进证据。

## 第 8 轮: 11 级绿, 第 12 级第五次红 —— 但半径这条线索到此为止

第 9 级在**和上一轮完全相同的坐标**上通过了(铁锭 ×6), 所以第 7 轮那次 `0 of 9 arena chunks`
确认是偶发的区块生成停滞, 不是地形。第 8 轮爬到 OBSIDIAN, `staging.calls=0`。

第 12 级的证据第一次是干净的:

```
forge.landedY=27, forge.toCarve=74/88 格, carve.stuck=2 格, forge.carved=完成
pool.sources=0        （以勘测点为心 16 格）
pool.widened=0 → 0    （以身体为心 40 格）   ← 这次改动第一次被真正执行
pool.nearestAnywhere=48 格内一格都没有
```

**下降和浇模已经是完工的东西**: 108 步竖井一步没卡, 正好落在 y=27, 88 格模腔挖成 74 格、只有
2 格挖不动。**错的只有地标。**

**但"48 格内没有"这句话不能按字面读。** 这三个探针全都只扫 `dy ∈ [-6,+4]` —— 身体上下十一格。
所以它们的答案是**"这个深度没有"**, 不是"这里没有"。低八十格的一片岩浆海会让同一行变红。
把它读成"没有岩浆"就会让第六轮再去调半径 —— 而半径恰恰是已经被证明无辜的那一维。
这是 memory `a-radius-is-not-a-distance` 在另一级上重演: **有界搜索报的是它的界, 不是世界。**

已提交: 失败路径上加 `pool.column`, 按 8 格分层扫整列(y=最低+1..63, 半径 24)。下一轮会直接说出
这一列到底有没有岩浆湖、在哪一层 —— 然后才谈得上把 `JourneyRoute.lavaLake` 烘进去。

## 设计: 旅程场景应该能拒绝它从不使用的竞技场

**第 7 轮死在第 9 级(铁)**, 报的是 `only 0 of 9 arena chunks ever loaded after 201 ticks`,
`minecraft:overworld at 104608,100000`。诊断:

- 服务器**没有**卡住 —— 201 tick / 9953 ms = 49.5 ms/tick, 正好 20 TPS; 上一级(熔炉)几秒前刚过。
- 竞技场按 `z=100000` 一条巷、按场景递增 x 分配。第 9 级抽到 `x=104608` 这一列, 该列的区块
  **一格都没生成过**(ready 从 0 开始就没动过), 而 1/2/3/5 轮在**同样的坐标**上都过了。
  所以不是地形, 是异步区块生成的偶发停滞 —— StageWrightHarness 自己的注释里已经写过这个现象
  (All the Mods 10 一轮 10s ENV_FAIL、下一轮 9s 通过)。

**关键点: 这 9 个区块, 旅程场景一个都不用。** `JourneyRig` 的类注释自己就写着"竞技场被强制加载,
而旅程立刻离开它 —— 它在出生点玩, 然后走上几公里"。也就是说梯子上**每一级**都在等一块
十万格外、自己永远不会踏进去的地。这不是运气不好, 是一个纯多余的失败面。

**做法**(在 stagewright 侧, 一个小改动):

- `Scene` 加 `withArena(false)`(或 `Terrain.NONE`) —— 只影响 PREP: 跳过 `forceChunks` 与
  `arenaReady` 等待, 直接进 RUN, 并跳过 `ArenaAudit`(对活世界里的场景本来就无意义)。
- `SceneContext` 照常构造 —— 旅程场景**要** `ctx.level()`, 只是不需要那块地被强制加载。

**代价**: 改的是 stagewright 的 API + harness, 要重新 publish 到 mavenLocal, 会撞上
memory `loom-remap-cache-serves-stale-stagewright` 里那三层缓存。所以不要在一轮梯子在跑的时候动。

## 🚧 阶梯在前沿以下不稳，这才是挡住"跑通完整链路"的主因(2026-08-12)

七轮实测: **只有 4 轮**跑到了第 12 级(1/2/3/5), 另外两轮死在与本次改动无关的上游:

- **第 4 轮死在第 6 级(食物)** —— 动物会走, 前三轮都过(见 memory `a-landmark-that-walks`)。
- **第 7 轮死在第 9 级(铁)** —— `the arena never became usable: only 0 of 9 arena chunks ever
  loaded after 201 ticks`, 是 StageWright 的竞技场/区块加载问题, 不是 bot 的问题
  (同族: `test-arena-needs-its-own-ticket`, `empty-level-stops-ticking`)。

**代价**: 想验证第 12 级的一处改动, 期望要跑 1.5 轮、每轮把下面 11 级全重跑一遍(约 15 分钟)。
第 12 级本身已经连续两轮"下降 + 挖凿都成功"(`forge.toCarve=74/88`, 只有 2 格挖不动), 卡点已经
收敛到岩浆源不够 —— 但**最近两次改动都还没被真正跑到过**。

**结论**: 继续用"改一处→重跑整梯"的循环去推第 12 级是低效的。下一步应该先做
**从已知状态续跑**(把身体+背包+世界存档在某一级之后 snapshot, 让上层级能单独重跑),
否则每一次前沿改动都要赌 4/6 的上游运气。

## 🔴 `wd.journey12PortalLit` 现场四轮，卡在地标而不是技术(2026-08-12)

阶梯**一具身体连过 11 级**(Recon→…→Obsidian), 第 12 级四轮全红, 每一轮买到一个具体原因:

1. **用错了等待形状**: 逐格挖用了 `rig.mineBlock`(drive 形), 它的超时**就是**失败, 于是卡住一格
   直接死在框架的 `await step exceeded within=900`, **完全没有记录是哪一格**。
   `JourneyRig.settle` 的 javadoc 正好警告过这件事。已加 `mineCellOrGiveUp`(挖的 settle 形)。
2. **竖井差 4 格**: 36 格深只挖到 32 格就用完了 128 次尝试。浪费掉的次数是"方块破了"到"身体掉下去"
   之间的那几 tick, 证据里就是 `broke=air` 而 `below=` 还是实心。已给这一级单独的 cap。
   ⚠️ **顺带发现**: journey11 和 journey12 是同样的 36 格下降、同样的 `depth*3+20=128`,
   而其中一个用完了 —— **那个 cap 比 journey11 的绿行看起来更贴边**, 是既有级的潜在 flake。
3. **找岩浆是以身体为心找的**: 而身体那时已经把自己挖进了十几格外的壁龛。改成以勘测点为心。
4. **地标本身不对**(当前): `pool.sources=0`, 以勘测点为心 16 格内一格源块都没有。
   **`firstLava` 是 OBSIDIAN 装桶用的那一处, 而装一次桶拿走的就是源块本身。**
   这一级要的是**十格以上源块的岩浆湖**, 是一个独立地标, 侦察级要单独勘测并记下源块数。

**挖凿本身从来不是问题**: `forge.toCarve=74/88`, 只有 2 格挖不动, 两轮都是。当初最担心的
"壁龛被岩浆灌进来"一次都没发生。

**阶梯在前沿以下不是确定性的**: 第 6 级(食物)三轮过、第四轮挂, 因为动物会走(见
`a-landmark-that-walks`)。所以想测第 12 级, 得碰到 1~11 级全过的那一轮 —— 大约四次里三次 ——
而且每次都要把下面 11 级重跑一遍。**一个"从已知状态续跑"的机制会很快回本。**

## 🧭 N6 第一关已经通了: `wd.serverEntersTheNether`(2026-08-11)

传送门点着了不等于过得去, 而这个问题在阶梯上问一次的代价是"在井底浇完十块黑曜石之后"。所以
先在竞技场问: **82 tick 过去了**, 正好是玩家自己的传送门等待时间。门框是布景, **点火不是** ——
走的就是 `wd.serverLightsPortal` 证过的那条打火石路径, 所以身体走进去的是它自己造的门。

### ✅ 已修(引擎侧): 换了世界, 没换地方

`ServerPlayer.changeDimension` **自己不搬身体** —— 它设好新 level, 然后把目的地**通过
`connection.teleport(...)` 送出去**。两个 loader 的 fake player 都把 packet listener 的每个方法
做成了空实现, 这一个也被吞了。于是身体带着**旧坐标**到了新维度:

    地表 x=100001 的传送门 → 下界 x=100001(应该是 12500), 差 87501 格,
    y=221(下界 logicalHeight=128, 也就是在顶盖之上), 脚下是空气,
    而回程门被正确地建在了 87501 格外身体本该在的地方。

**`dimension == the_nether` 这条断言一直是绿的。** 它会一路绿下去, 而要塞、要塞遗迹、末地全都
在看错误的世界。抓住它的是**独立算一遍落点**(`DimensionType.getTeleportationScale`, 8:1)然后断言
**落在哪**, 而不只是**到没到**。

影响面比传送门大: `ServerPlayer.teleportTo` 走同一个调用, 所以**任何 vanilla 机制都搬不动**被
驱动的身体 —— 末地传送门和末影龙的 gateway 都用它。

修法是一个共用的 listener `AvatarNetHandler`, 它的 `teleport` 做 vanilla 真 listener 在
`internalTeleport` 里做的事(`absMoveTo`), 减掉那个没人收的包。NeoForge 的 `FakePlayer` 不是我们
能继承的, 但 **`ServerPlayer.connection` 是 public 字段**, 所以 loader shim 把 listener 装到
NeoForge 建好的桩子上 —— 身体保住了 mod 会去认的 `FakePlayer` 身份, 只换 listener。两个 loader
现在都落在 `12499, 118, 12500`: 漂 1 格, 站在真门里, 脚下黑曜石, 在顶盖之下。

**这是一次有意的分叉。** 那些空实现原本是为了让 vanilla 身体永远不会和 NeoForge 的 FakePlayer
表现不同 —— 这是个好默认, 但在这一个方法上是错的, 所以 javadoc 写明了是哪个方法破了规矩、为什么,
免得有人回头把对称性"修"回去。

## 🧭 N7–N10 已经量过的四件事(2026-08-11)

写死步骤能不能一路走到龙, 现在有四条竞技场读数, 都是两个 loader 一致:

- **烈焰棒掉得出来**(`wd.serverEarnsABlazeRod`): 打死 24 只掉 11 根。也就是被驱动身体的击杀
  **算玩家击杀** —— vanilla 用 `killed_by_player` 卡这个掉落, 而这正是"打得死但什么都不掉"这种
  静默失败的形状。**第一版只打了一只、看见空地板就断言失败**, 而烈焰棒是 0..1 均匀掷骰, 一次
  取样根本分不开"条件没过""骰子是 0""`doMobLoot` 关着"这三种解释。现在直接读 gamerule + 打 24 只 +
  记录逐只战果。
- **十二只眼能装进框、门能开、能过去**(`wd.serverOpensTheEndPortal`): 装眼是 `EnderEyeItem.useOn`,
  和打火石同一个坑(`useItemInHand` 只会拿到 `PASS`)。落点断言压在 `ServerLevel.END_SPAWN_POINT` 上,
  漂移 0, 脚下黑曜石 —— 只断言"到了末地"会在身体掉进虚空时照样绿。
- **龙打得动**(`wd.serverDamagesTheDragon`): 现成 `CombatProcess` 就把龙从 200.0 打到 197.3,
  瞄头部一下 2.75。**原来的预判是错的** —— 以为龙是多部件、`EnderDragon.hurt` 拒绝一切直接伤害,
  战斗循环会对着没有判定框的位置挥空。量了才知道不是。
- **找结构不用加引擎能力**: StageWright 的 `ctx.structures().locate()` 就是"调用方完全了解这个种子"
  的那只手, 侦察级把坐标烤进 `JourneyRoute` 即可, 和现有地标一个路子。

**还没测的三件**(都在场景 javadoc 里写明了, 免得绿行被读成覆盖): 会飞的烈焰人(上面那条是钉住的
靶子)、末影人(挨打会瞬移)、龙的飞行与停栖阶段和末影水晶。

**一条测具自己的教训**: 龙那个场景的"手动打头部"对照第一版读出 0 伤害, 看起来和"打不动多部件
Boss"一模一样 —— 实际是重置了 `hurtTime`(泛红计时器)却没重置 `invulnerableTime`(真正拒伤 20 tick
的那个), 而且在挥完之后才重置攻击蓄力。**对照组量到的是测具时, 它的读数和能力缺失长得完全一样。**

## 2026-08-08/09 🆕 `wd.journey*` 通关自测阶梯 — 高度 PORTAL_KIT(桶与打火石), 地板仍在 FURNACE, 前沿=铁产量与一处 tick 僵死

**做了什么**: 20 级阶梯(空手出生→屠龙)作为一条连续链跑在固定种子 5471 上, 一个身体一个世界,
`JourneyLedger` 跨场景传状态, `JourneyLedger.FLOOR` 单点棘轮裁决。全程零布景(give/setblock/fill/tp
一次都没有, 且 `stagingCalls()` **实测**而非口头承诺)。每步写死坐标(`JourneyRoute`, 由
`wd.journey01Recon` 从活世界侦察并充当陈旧守卫), 所以失败只可能是"驱动执行不了正确计划", 不是
"规划器没想出来"。默认关闭(`-Dworlddriver.journey=true` + `:fabric:runJourneyServer`), 六道门只跑
常驻标记 `wd.journeyArmed`。

**侦察结果(种子 5471)**: spawn=(64,68,60) 是 **swamp**(开局很硬, 水在 6 格外); firstTree=(65,68,63)
在树冠 y=68(地表才 63); firstStone=(67,59,69); firstIron=(64,55,60); firstCoal=(62,54,71);
stronghold=(-1168,64,1296) 1745 格; village=(400,64,-464); ruinedPortal=(-384,64,-368) 620 格。
**48 格内没有岩浆** → N4 浇黑曜石需要专门的远程/深层侦察, `firstLava` 故意留 UNSURVEYED。

**根因 1+2 (已修, 采集彻底不可能)**: `ServerPlayerAvatar` ①`breakHold` 两处
`destroyBlock(pos,false,fp)` → 挖掉的方块**根本不产生掉落物**; ②`mirrorPlayerTick()` 从未镜像
`Player.aiStep` 的 entity-touch 环 → 就算有掉落物也**捡不起来**。任一条单独成立就够让服务端 agent
永远两手空空。**为什么 222 个绿场景看不见**: 没有任何一个断言过"物品进了背包"。唯一以此命名的
`wd.serverCombatCollectDrops` 判据是 `pickedUp || distToDrop <= 2.0` —— 只要求走到掉落物 2 格内。
journey 的 wood 级第一次直接问"给我 1 根原木", 立刻暴露。修完 t0 fabric **GREEN 零回归**(232 执行,
唯一非金丝雀失败仍是已知可选传感器 `wd.vineOverWaterClimb`)。
**影响面(行为级, 不只是记账)**: 挖掘现在产生实体、avatar 会带着挖到的东西离开场景;
`holdPlaceable()` 取热键栏第一个可放置物 → 挖穿泥土的 bot 现在**可能开始放置**它以前无物可放的地方。

**根因 3 (已修)**: 3×3 合成走不通。`[craft] fail state=OPEN_WAIT msg=打开工作台超时` ——
fake player 的 `openMenu()` 返回 `OptionalInt.empty()`, 而 `CraftingTableBlock` **只**经由
`openMenu` 拿菜单, 所以右键工作台什么也不发生。2×2 背包合成一直是正常的, 卡住的是阶梯的绝大部分
(镐/熔炉/桶/打火石全要 3×3)。修在 `ServerPlayerAvatar.useBlock`(common) 而**不是**重写
`AvatarFakePlayer.openMenu` —— 后者是 fabric 身体, neoforge 经 `ServerAvatarBodies` 注入它自家的
`FakePlayer`, 只改那里等于只修一个加载器。跳过 vanilla 的 `initMenu`(它只挂 slot listener + synchronizer,
纯粹为了给一块不存在的屏幕发包, 且两者在 `ServerPlayer` 上都是 private —— 撬开它们需要 access widener
却换不来任何行为)。
**两个场景把悬崖当需求写死了, 随修一起翻转**: `wd.serverCraftTableReclaim` 原本拿"必然失败的 craft"
当载体测 reclaim-on-failure → 改为断言 reclaim 在**成功路径**上发生 + 真的产出了镐;
`wd.serverSmeltCliff` → **`wd.serverSmeltStationOpens`**, 从"必须优雅降级"翻成"炉子必须开得起来且装得进料"
(名字里带 Cliff 却要求 cliff 消失, 是给下一个读者挖坑)。

**根因 4 (已修)**: `ServerPlayerAvatar.selectTool` 是**空实现**("best-tool optional")。现按客户端同一
排序规则实现(correct-for-drops 优先, 同等则更快者胜), 搜索范围含背包, 命中背包时换进手持槽。不能复用
`BotInteract.selectBestToolFor`(它吃 `Minecraft`, 在 seam 的客户端一侧)。
⚠️ **当初把它当成"空背包"的根因是判断错了, 这里如实记下**: 反编译 1.21.1 的 `Level#destroyBlock` 可见
它给 `Block.dropResources` 传的是字面量 **`ItemStack.EMPTY`**, **根本不看手持物**。所以这条路径上
`selectTool` 决定的只是**破坏速度**, 不是掉落。空背包的真凶是 `MineProcess` 的收集(见根因 5)。
📌 **顺带暴露的保真度洞**: 服务端 avatar 现在**赤手也能挖出黑曜石、木镐也能挖出钻石**——无工具门槛、
无精准采集、无时运、工具也不掉耐久。对一个"要告诉整合包作者玩家会遇到什么"的驱动, 这是**主链上的**保真度
问题(N4 黑曜石正是"用错工具必须失败"的场景)。忠实路线是 `fp.gameMode.destroyBlock(pos)`
(`ServerPlayerGameMode`: 用 `hasCorrectToolForDrops` 把门, 把真实手持栈交给 `Block#playerDestroy`,
并调 `ItemStack#mineBlock` 磨损工具)。**留作单独一次改动**——它挪的是需求不是 bug: 每个赤手开挖的
arena 依然会破坏方块(移除不受工具门槛约束), 但不再白拿掉落, 所以得先把默默吃这份免费收成的场景找出来。

**当前高度**: **FURNACE** —— recon→spawn→wood→wood_tools→stone_tools→猎牛→熔炉, 一条不断的链,
布景调用 0 次。地板按纪律棘轮三次(WOOD_TOOLS→STONE_TOOLS→FOOD)。裁决 PASS。
五处修复后 **fabric + neoforge 两道门都 GREEN**(唯一非金丝雀失败仍是已知可选传感器 `wd.vineOverWaterClimb`)。

**阶梯模型改了: 前置 ≠ 顺序。** 每一级都挡住它上面的全部, 所以夹在中间的一级等于在断言"我不成,
上面全不成"——对 BED 这是假的: 床是耐久 keystone(换出生点, 死了不回退), **通往末影龙的路上没有任何
一级需要床**, 在这条无敌身体的轨道上更是双重无关。它之所以要紧是地形: 5471 的沼泽只有牛和青蛙**没有羊**,
羊毛要走很远。留在直线里, "找不到羊"会把铁/传送门/整个下界全部堵死。
`JourneyStage.requires()` + `criticalPath()` 把它变成侧枝——跳过但不阻塞, 也不计入高度。

**根因 5 (已修, 才是空背包的真凶)**: `MineProcess` 挖完就把掉落物丢在地上, 两条独立路径。
①**捡拾延迟**: 贴身挖掉的方块, 掉落物就落在**脚下**且带 vanilla 的 10 tick `pickUpDelay`。
`findCollectGoal` 会跳过还在延迟中的物品(走过去没意义), 而 `recentBreaks` 兜底又会把"已站在其上"的
破坏点弹掉 —— 于是破坏后第 1 tick 两边都正确地答"没有目标", COLLECT 把它读成"没东西了"就收工。
实测: 挖 1 个铁矿共 24 tick, 矿没了、掉落物在地上、`lastError=null`。现在只要 2 格内有还在倒计时的
掉落物就原地等(上限 20 tick, 免得在"实体根本不 tick"的单 tick 场景里挂死)。
②**配额没凑够就弃收**: 要 4 个而矿脉只有 2 个时, SEARCH 直接 `reset(); return true`, **COLLECT 压根没跑**,
两个掉落物全丢。"我什么也没拿到"其实是"我拿到了两个"。现在配额只决定**找多久**, 不决定**收成归谁**。
**为什么没人抓到**: 所有 mine 场景都只断言"目标方块不在了"。新增 `wd.serverMineHarvest` 认领另一半 ——
PASS 的定义是**背包里多了一件原本不存在的东西**。它跑在**真实 server tick** 上(单 tick 里 spin 的场景
实体不 tick, 延迟永远不清零), 镐放**背包**、热键栏放泥土(复现 `holdPlaceable` 抢手的真实状态),
并且**要 2 个只放 1 个**, 让上面两条缺陷都在它的路径上。两道门 GREEN, 已升 required。

**脚本侧新教训: 侦察到的坐标还不是计划。** 三级都写成"走到资源所在处", 三级都错在同一点 ——
`Goal.Near(target,3)` 判的是**三维**距离: 对着树冠上 5 格的原木, 它告诉站在树下的 bot"你还差 4 格"
然后让它爬; 对着地下 7 格的矿, 它对着**正站在矿上方**的 bot 报"走不到铁矿"。wood 级一直靠"pathfinder
恰好来得及垒柱"侥幸通过, 后来一字未改地跑了 1304 tick 停在 9 格外。三级统一改成走**柱**(`Goal.XZ`),
够不够得着交给该管的动词。
铁那一级还要再往外一层: 5471 **最近**的铁在沼泽水塘底下, 竖井必淹 —— `DescendProcess` 查完四个方向和
自己脚下那一列, 发现全是水, **拒绝下挖**。这是**正确行为**, 所以 bug 在坐标不在驱动。单独侦察"干燥下挖点"
又给出 18 格外的点(用长盲隧道换掉淹井)。`JourneyRoute.nearestUnderDryGround` 把两个问题**一起问**:
最近的、**自己这一列加四个正交邻列**都干到底的铁矿(那正是楼梯井占的形状) —— 答案是 26 格外、竖井直接落在
矿上的那一个。**只记录"东西在哪"的侦察, 产出的是执行不了的计划。**

**根因 5 续: 又两条收集缺陷(已修)**。③**到了却够不着**: sweep 走到一个比 vanilla 吸取半径更宽松的
目标就停了 —— 实测 `lastStep=ARRIVED`、掉落物在 **1.6 格**外、240 tick 收集预算全烧完一件没碰到。
磁吸半径只有约 1.4 格(bounding box inflate 1.0), 所以"站在掉落物所在格的旁边"根本不够。目标改回
掉落物**自己那一格**(玩家就是走上去的)。④**一个死目标遮住所有活目标**: `findCollectGoal` 返回**最近**
的掉落物, 而 walker 的判定被丢弃了 —— 一个够不到的掉落物会被每 tick 重新寻路直到超时, 它后面每一个
够得到的全部陪葬。现在 `Walker.Step.FAILED` 和"ARRIVED 了但东西还在地上"都会把这个掉落物**退役**。

**根因 6 (fabric 已修, neoforge 仍红): 服务端 avatar 拿不到任何成就。** 缺了两半, 而且两半看上去都"无所谓"。
①没经 `PlayerList` 上线的身体, 它的 `inventoryMenu` **一个监听器都没有** → 现在在 `ServerPlayerAvatar`
构造里调 vanilla 自己的 `initInventoryMenu()`; ②真 `ServerPlayer` 每 tick 在 `doTick` 里调
`containerMenu.broadcastChanges()`, 这个 avatar 镜像的是 `Player` 的 tick, 从来没调过。两者都不是"发包"的事
(连接本来就吞包), 但 `ServerPlayer` 自己的 `ContainerListener` 正是从 `slotChanged` 里触发
`CriteriaTriggers.INVENTORY_CHANGED` —— `story/root`、`story/mine_stone`、`story/upgrade_tools`、
`story/smelt_iron` 全靠它。不监听/不广播 = 做了工作台、挖了圆石、升了石镐、炼了铁锭, **一个成就都不得**。
是 journey 每级记录一条成就、条条 `not-earned` 才暴露的。
⚠️ **加载器分叉(未解释)**: `wd.serverAvatarEarnsAdvancement` **fabric 绿 / neoforge 红** —— 同一份 common
构造、同一份 common tick, 跑在 neoforge 自家 `FakePlayer` 上就是不得成就。作为**具名 optional 行**发出来,
不藏进断言里也不让门变红。顺带一条值得知道的: **注册了但没有进程的 driver 根本不会被 tick**, 库存广播是搭
body tick 的车 —— 所以往闲置身体里塞东西, 要等下一个进程跑起来才可能触发成就。
顺带: 根本没有 `story/mine_wood` 这个 id, 我第一版就写错了 —— `advancementStatus` 把 `UNREGISTERED`
(没人注册过这个 id = 打错了)和 `not-earned` 分开, 正是为了这种情况。

**新增传感器**: `wd.serverMineHarvest`(required, 两门 GREEN) = 三个矿排成一条巡回路线、配额要 4 个只放 3 个,
把捡拾延迟/短配额/巡回三条全压在它的路径上, **PASS 的定义是背包里多了原本不存在的东西**。
`wd.serverMineHarvestBuried`(optional, **故意红着**) = 同一条路线但两个矿埋在地板下 —— 这是**野外的形状**。
不把它改软, 因为改软就是"把悬崖当需求写死"(`wd.serverSmeltStationOpens` 就是因为这个才改的名)。

**当前高度 IRON, 但地板留在 FURNACE。** IRON 真的爬上去过(矿挖到、铁炼出、两块铁锭在背包里、零布景),
下一跑同样的代码又挂了。原因清楚且有专属传感器: 这颗种子的铁在地表下 4 格, 而**avatar 隔空挖出来的坑,
坑底的掉落物取不回来** —— 于是这一级取决于掉落物碰巧落在哪。**地板是"这一级能用"的断言, 不是"它曾经成功过"**;
架在一枚硬币上, 以后每一条红都读不出是回归还是硬币。

**顺带删掉了显式下挖步骤。** `DescendProcess` 纸面上是对的动词(它自己的 javadoc 就论证"老手直接挖楼梯井,
别让 A* 去给竖井定价"), 但在这块地上它把身体横着挪了 19 格只下了 1 格, 报 "no safe descent stride
(all cardinals + own column wet/hazard/unbreakable)" —— 楼梯需要**能踏进去的地方**, 沼泽没有。
这一级是**绕过**那一步通过的, 不是靠它。限制记下来, 计划不再依赖它。

**脚本侧三个教训(都是我自己踩的, 不是引擎问题)**: ①一级的断言必须是**下一级的前置条件** ——
wood 先用 `logs>=1` 通过, 把"做工作台+木镐要 3 根"的账单甩给了下一级; ②脚本的搜索半径不能大于**动词自己的
作用半径** —— 在 64 格内找到牛却直接交给 `CombatProcess`(SCAN_RADIUS=32), 它 2 tick 就放弃, 读起来像动词坏了;
③物种要按掉落物白名单选, 不能 `instanceof Animal` —— 沼泽里最近的"动物"是**青蛙**, 杀了不掉肉。

**根因 6 的正解: 别再抄 `Player.tick()`, 让身体真的上线。** 上面每一条根因都是同一个形状 ——
vanilla 在一个 fake player 从不执行的方法里做了那件事, 修法是往 `mirrorPlayerTick()` 里再抄一段。
这张单子只会变长, 因为它是一份"靠发现缺什么来维护"的 `Player.tick()` 重写。**FakePlayer 的定义就是
一个从未被 `place` 过的 `ServerPlayer`**: `PlayerList.placeNewPlayer` 才是挂 inventory-menu 监听器
(`INVENTORY_CHANGED`)、加载 profile 的 `PlayerAdvancements` 并指向这个身体、把它放进
`ServerLevel.players()`(关卡才继续 tick、怪才看得见它)、注册进 `ChunkMap`(走到哪加载到哪)、
以及触发整合包模组挂的 login 事件的地方。这些**抄方法抄不到**。

`JoinedPlayerBodies` 装进既有的 `ServerAvatarBodies` 缝里(`-Dworlddriver.realPlayerBodies=true`,
默认关), 于是 222 个场景 + journey 阶梯直接变成 A/B 台架, 而不是一场架构辩论。**实测(同种子同高度
FURNACE, 两跑都零布景)**: `advancement.root` / `story/mine_stone` / `story/upgrade_tools`
**三条全部 not-earned → earned**。也就是说手抄的 `initInventoryMenu()` 只够让一个合成场景变绿,
对真正的通关链一点用没有; 上线一次就全好了, 而且不需要为每条 criterion 再抄一遍。

**顺带解掉了根因 6 留的加载器分叉。** `wd.serverAvatarEarnsAdvancement` 同一份 common 构造、同一份
common tick, fabric 绿 neoforge 红, 挂了一周没解释。上线之后**两门都绿** —— 解释就一句话:
被 `place` 过的身体不需要任一加载器的 fake player 表现良好。

**neoforge 的上线比 fabric 多要一样东西**: vanilla 全程不碰 `Connection.channel()`(所有对线的访问都走
`send`, 而 `send` 被吞了), neoforge 把 connection type 存成**channel attribute**, 于是
`placeNewPlayer` 死在 `channel().attr(...)`, 整个武装跑只剩 76 个场景执行。那个字段没有 setter,
`channel()` 又是 neoforge 自己加的访问器(`:common` 里覆写不了) → 让 connection 把自己注册到一个
`EmbeddedChannel` 上, `Connection.channelActive` 就是赋那个字段的地方。channel 尾部要**丢弃并完成**
每次写, 因为 `EmbeddedChannel` 默认把出站消息永久排队 —— 用一个静默泄漏换掉一次响亮的崩溃。
**两门武装后皆 GREEN, 覆盖数与未武装基线一致**(fabric 208/20, neoforge 209/19)。

⚠️ **差点被当成战果的陷阱**: 场景本来就用 `fp.discard()` 收身体 —— 对 fake player 够了, 对上线的身体
不够, 因为 `PlayerList` 有自己的名单。第一次武装跑出来 **79 次 join / 0 次离开**, 这些尸体满足了
`ctx.player()`, 于是 13 个本该 skip 的场景对着尸体跑了起来, 看上去像"上线换来 13 个场景的覆盖"。
**一个都不是。** 修成 `JoinedBody.remove()` 走 `PlayerList.remove` 之后, 覆盖率精确回到原值
(208 执行 / 20 skip, GREEN)。这就是 memory 里 `skip-is-not-coverage` 反过来的版本。

**这是上半场, 下半场是 `JoinedBody.tick()` 那个空实现。** `ServerPlayerAvatar.step()` 自己手算位移,
放 vanilla 的 `aiStep` 进来会积分两次。所以现在的身体有 vanilla 的接线、没有 vanilla 的 tick ——
凡是 `Player.tick()` 里逐 tick 推的东西都冻着, 比如攻击蓄力计数器(`wd.attackCooldownSurface` 在
尸体没修之前正是因此 TIMEOUT)。下半场 = 驱动改成写输入(`xxa`/`zza`/`jumping`)而不是写坐标,
那是本仓改动最频繁的子系统的一次重写, 必须单独一步做, 否则"身体变真了"和"移动搬家了"混在一起, 没有门分得开。

**根因 7 (已修, 引擎级): 用过一次 process 的 driver 再也接不了新命令。** `ServerWorldDriver.tick()`
先判 `process` 再判 `mineTarget`, 而 `mine()`/`gotoGoal()` 都没清 `process`(只有 `runProcess` 清了
`mineTarget`)。于是**任何跑过 process 的 driver, 之后每一次 `mine`/`gotoGoal` 都被静默丢弃**, 跑的还是那个旧
process —— 它到达早就满足的目标、driver finished、调用方读成"挖完了"。**全程没有任何报错**, 这是它贵的原因。

**怎么抓到的**: IRON 按你的规矩先写死步骤(不补引擎)——挖脚下的方块、掉进去、重复。跑出来是
`shaft.0..11` 十二条腿, 每条 `broke=grass_block`: 同一块没动过的地, 报了十二次挖掘成功。我为此**先后两次
判错**(先怪 walker 不肯掉进坑、再怪 allowPlace 铺回填), 直到加了"挖之前/挖之后各读一次那块方块"的证据行。

**回归测试 `wd.serverSelfShaftDescends`(新增, optional)**: 第一版**抓不到这个 bug** —— 新建的 driver 在
持有任何 process 之前就 `mine()`, 恰好是唯一不会触发的顺序。改成**第二层井在一个 process 用过这个 driver
之后再挖**。已用"回滚修复再跑一遍"验证: 场景如期失败, `deeper=Block{minecraft:stone}`。

修完之后 IRON 的竖井**真的沉下去了**: 三条腿 y=63→62→61, `descent.landedY=60`,
`descent.column=83,77` 全程没离开自己的柱子。这一级现在卡在**下一个**问题上: `raw_iron.onGround=4`、
`mine.lastError=null` —— 矿挖了四块, 一块没捡回来, 正是 `wd.serverMineHarvestBuried` 那个形状。
阶梯现在被**一个**已命名、可在 14 秒内复现的传感器挡住, 不再是一团乱麻。

**根因 8 (半修, 前沿已精确定位): 坑底掉落物取不回, 但原因不是"走不进坑"。** 按"先测量再动手"的顺序拆:

1. **新增 `wd.serverWalkIntoAPit`(required, 两门绿)** —— 纯导航, 不挖矿: 六格外一个一宽两深的坑,
   `IntentProcess`+`Goal.Block` 走到坑底。**30 tick 通过。** 于是"walker 进不了坑"这条被排除,
   整个故事从 Walker 移到 MineProcess。
2. **`wd.serverWalkIntoAPitArmed`(optional)** —— 同一个坑, 换成 MineProcess 自己的权限
   (`allowBreak`+`allowPlace`+手里有土)。**也是 30 tick 通过。** "带铲子的 walker 会把坑铺平"
   这条假设也排除了。
3. **给 `endReason` 加上"扫尾时在干什么"**, 于是它自己说了答案:
   `retired 2 drop(s): 0 unpathable + 2 arrived-but-short` —— 两个掉落物**都不是**因为找不到路被放弃的,
   是因为 walker 报了 **ARRIVED**, 而身体离掉落物 4.1 格和 6.4 格。

**结论(可以直接拿去修的那句话)**: `Goal.Block.reached()` 是精确格判定, 所以 walker 的 `ARRIVED`
**不等于"到达目标"** —— 它的意思是"我把我算出来的那条路走完了"。A* 够不到目标时给的是尽力而为的部分路径,
走完照样报 ARRIVED。调用方无法区分"站在掉落物上"和"停在四格外的半路终点"。
(`MineProcess` 里那条早就写下的注释——"扫尾报 ARRIVED, 人站在离掉落物 1.6 格处"——是同一件事的更早一次目击。)

**已落地的那一半**: `findCollectGoal` 的 `recentBreaks` 回退分支**没有跳过已退休的格子**, 于是当所有
可见掉落物都退休后, 它把坑底那个格子一遍遍递回来(pop 判据是"进到 1.5 格内", 而它永远进不去),
整个 240 tick 预算全耗在一个已知的死格上。修完 `wd.serverMineHarvestBuried` 从 **287 tick 缩到 121 tick**,
`endReason` 也从"collect timed out"变成实话"collect swept everything it could reach"。

**根因 9 (根子在这, 已修): 服务端 avatar 会挖穿实心岩石。** `Level#destroyBlock` 既不判距离也不判可见性,
所以身体瞄到哪挖到哪 —— 隔着多少石头、多远都行。后果不是"不忠实"这么轻: **在完整地板下面挖出来的矿,
掉落物落在一个六面封死的 1×1×1 口袋里**, 谁也取不回来。

**这就是 `wd.serverMineHarvestBuried` 一直在失败的东西**, 而我先后往两个错的文件里投了两轮工作
(先 walker、再 collect 扫尾), 因为判据只说"掉落物没捡到", 没有人去看掉落物周围是什么。
给诊断加两个字段, 一跑就结案: `above=Block{minecraft:dirt}, openSides=0`。

**修法**: `ServerPlayerAvatar.breakHold` 拒绝两类目标 —— 六面全实心的, 和超出玩家自己
`blockInteractionRange` 的。**故意不做 raycast**: vanilla 服务端自己也不做(它信任客户端的瞄准, 只判距离),
所以"暴露 + 距离"才是服务端侧诚实的近似, 不发明比游戏更严的规则。
`wd.serverBreakNeedsReach`(required, 两门绿)一次钉三个目标(封死 / 远 / 相邻), 让"修好一个换坏另一个"过不去。

⚠️ **影响面, 摆出来而不是藏起来: 有两个场景是靠这个 bug 绿的。**
`wd.buriedOre` 直接挖穿矿上面的覆盖层; `wd.serverEscapeSealedShelter` 的开凿瞄的是**出口**方块
(身体在 y=221, 目标 y=224), 而不是头顶下一格。

**根因 10 (已修, 这一条把前沿真的推动了): 挖矿要先剥覆盖层, 不能对着打不到的东西挥。**
加了 reach 判定之后, `MineProcess` 对着想要的矿挥空拳 —— no-progress 看门狗最后报"no reachable target",
而 bot 就站在那块矿正上方。`firstBreakableToward` 沿"眼睛→目标"这条线段找到**第一块能真的打到的实心方块**,
把它设为 **clearing 目标** —— 这套机制早就存在(为了清掉挡住原木的树叶): 不计入配额、不进 COLLECT、
破完自动 re-SEARCH, 于是刚露出来的方块被正常拾取。一次剥一层, 玩家就是这么干的,
而且这样**每个掉落物都落在身体走得进去的坑里**。

**两个场景因此转正**:
- `wd.buriedOre` **重新 required**(这次是凭本事绿的, 不是靠挖穿岩石)。
- `wd.serverMineHarvestBuried` —— 写出来就 optional 且**故意红着**、让 journey IRON 级变成掷硬币的那一个 ——
  **第一次 required**。它的诊断在路上被彻底推翻: 掉落物根本不在"walker 不肯进的坑底",
  而是**封死在 avatar 本来就不该挖穿的岩石里**。

**"够不到"要分两种, 这个区分两头都吃过亏**: 已经**暴露**却仍然打不到 = 超距离, 而站着不动距离不会变,
所以立刻退休、重新 SEARCH, 不必等 no-progress 看门狗那 100 tick。
- 不加这条退休: reach 判定顺带暴露了 bot 一直在**砍自己头顶五格的树冠原木**, 而每根不可达的原木都要耗
  100 tick, 预算在它试树干之前就没了 —— journey 的 WOOD 级从 6 根原木掉到 **0 根**。
- 只看"现在打不到"就退休(不看暴露): `wd.serverMineHarvestBuried` 最深那块矿在剥离层还没挖开之前就被退休了,
  一直立在那儿。**埋着是暂时的, 远是永久的。**

`wd.serverEscapeSealedShelter` 仍留 optional: 它走的是**另一个开凿器**, 还在瞄出口。
教会那一个同样的道理就能升回来, 原因写在它的注册处。

**顺带记一条 bot 能力边界**: 它**不会爬树**。树冠上的原木现在诚实地取不到, 只能砍够得到的那些。
这不是 bug 被绕过, 是限制被写下来了 —— 没有梯子的玩家也是这么干的。

**诚实挖掘让整条阶梯变慢, 路线要跟着改**: STONE_TOOLS 第一次跑成 TIMEOUT(10051 tick / 506 秒) ——
不是挖不动, 是**每一块**石头都要先剥四层覆盖土。侦察到的第一块石头在沼泽地表下四格, 站在草地上挖它,
等于把二十块的量乘以四。**改法沿用 IRON 那条已经验证过的写死步骤**: 先挖竖井沉到石层, 再横着挖 ——
玩家就是这么干的, 而且掉落物全落在脚边。两个挖掘级(STONE_TOOLS / IRON)的场景预算同步提到 40 000 tick。

**`JourneyRig.settle` (新增)**: 计划内的**一次尝试**不能用 `drive` —— `drive` 的超时**就是**失败,
于是一条卡住的腿会用框架的通用 "await step exceeded" 顶掉计划自己的诊断。石头级正是死在这上面
(480 tick 撞上 400 tick 的沉降腿, 报了 TIMEOUT, 而竖井明明有话要说)。`settle` 跑到进程结束或 tick 用尽都往下走,
计数器放在谓词里(它是等待期间唯一每 tick 执行的东西)。换上之后失败信息立刻变成竖井自己的判词。

**竖井三连修(全是脚本层, 没动引擎)**:
1. **`stoneDescent` 干燥柱** —— `firstStone` 原来是**无干燥约束**侦察的, 照抄 IRON 的 `nearestUnderDryGround`
   + `dryDescentNear` 之后, recon 的陈旧守卫如约报错并打印新常量(`firstStone=(72,59,74)`,
   `stoneDescent=(72,63,74)`), 烘进 `JourneyRoute` 即可。
2. **竖井预算按"尝试次数"算, 不是按"方块数"** —— 一格要两三趟(破完还得给身体沉降的 tick),
   12 次只买到 1 格。同时**下方已经是空气就别再挖**: 挖空气是白挖, 却照样吃掉一次尝试。
3. **走到柱子的容差 2 → 0** —— 决定性的一条。竖井挖在**身体站的地方**, 而侦察只认证了**一根**柱子干燥;
   容差 2 让身体偏出去两格, 第四趟就 `shaft.4 below=water`, 之后浮着不沉、而挖水是空操作。
   **沼泽里两格就是干与湿的全部差别。**

**竖井第四修(决定性的一条): 沉降腿不能有任何转向。** 前三修之后, 身体已经能**精确站在**侦察出的干燥柱上
(`arrived.horizontalDistance=0`)、把脚下方块**干净地打穿**, 然后 walker 还是把它**一格一格挪开**
(`72 → 73 → 74`)。`wd.serverSelfShaftDescends` 之所以一直绿, 是因为那个封闭场地**无处可去**;
野外有别的选择, A* 就会去选。

**这个身体没有自由运行的物理** —— `ServerAvatarManager` 只 step 有 driver 在 tick 的 avatar,
所以没注册的身体会**悬在自己挖的洞上方**。而当时手上每一个 process 都会**转向**, 转向正是竖井的死因。
新增 `HoldStill`(testmod-only, 不是引擎改动): 什么都不做, 只把输入清零、跑满 N tick。
**挖穿脚下、然后站着等** —— 玩家就是这么干的, 重力不需要寻路器。

效果: STONE_TOOLS 从 **3233 tick / 165 秒**(还得靠运气)降到 **324 tick / 25 秒**, 一次就过。

**竖井第五修: 挖下去了要爬上来。** 诚实挖掘之前 bot 从不挖竖井, 所以没人需要这一步;
现在挖掘级结束时身体**站在 y=60 的一格宽井底**, 而**下一级继承这个位置**。
实测: FOOD 级向 67 格外的牛出发, 8000 tick 预算全花在"走向猎物"上 —— 它是从坑底出发的。
新增 `climbOut`(允许放置, 用刚挖到的圆石搭柱子, best-effort): 到达目标的一级不该因为出口难看而判失败,
下一级自己的守卫会说话。

✅ **"爬不出坑"是误判, 已由传感器推翻。** journey 的 `exit.fromY=54 → exit.toY=55`(1200 tick 只上升 1 格)
读起来像缺能力, 于是照老规矩先建**封闭传感器** `wd.serverPillarsOutOfAPit`(四格深、一格宽的竖井, 背包 32 圆石):
**46 tick 就出来了, required 且绿**。所以 journey 那边是**预算/地形**问题, 不是能力问题。
**教训: 不要从一个卡住的 rung 反推"缺能力", 先做密闭传感器**(`wd.serverWalkIntoAPit` 是同样的用法)。

⚠️ **这个传感器自己也上了一课**: 第一版还断言"必须消耗圆石"(即出口必须是**搭柱子**)。
它在身体已经站上地表的情况下**失败**了 —— 因为身体是**凿穿井壁开了个楼梯**上来的, 这正是有镐的玩家会做的事,
而且比搭柱子更好。断言写成"必须 pillar"就是把**一种实现**写进需求, 并且会把一个能用的能力报成缺失。
**断言结果("它出来了"), 永远不要断言手法。**
注意 `wd.selfShaftDigUp` 是**向上挖**, 不是**搭柱子上升**, 两者别混。

**竖井第六修(治好了 run-to-run 抖动): 破脚下不等于破支撑。** 玩家碰撞箱 0.6 宽, 站在格子边缘时
**同时踩在两个格子上**, 只破"身体中心下方"那一格, 人就落在邻格上 —— 实测竖井破得干干净净,
然后连续三趟 `below=air` 而 y 一动不动。**同一份代码同一组坐标, 沉不沉下去取决于走路恰好停在格子的哪个位置**,
这就是这一级 run-to-run 抖动的全部来源。`supportUnder` 改为找**真正还撑着身体的那一格**
(优先中心格, 否则取碰撞箱四角里还是实心的那个)。

**竖井第七修: 水不是地板。** `supportUnder` 判"还撑着身体的那一格"用的是 `!isAir()` ——
**水不是空气**。实测一跑: 竖井破了中心格、又破了碰撞箱另一角, 地下水灌进来, 从第三趟起
`below=water` 连报 28 趟(挖流体是空转), 判词写成"方块破了但身体没下沉", 而真正还撑着身体的那一角
**一次都没被碰过**。改成 `blocksMotion()`(下沉分支同改), 同一根柱子上石头级从
**FAIL(30 趟 0 下降)→PASS(603 tick, 21 圆石)**。

**同一跑的另一半修在侦察上: 干燥判据从"十字"扩成 5×5。**`dryCross` 原本只验自己这列 + 四个正交列 ——
那是 `DescendProcess` 挖楼梯的形状。写死步骤挖的**不是楼梯**: 碰撞箱 0.6 宽, 身体站在格子边缘时
支撑格是**邻格**, 挖井必然连它一起破, 洞最宽到 2×2, 而它的井壁正是十字**没看过**的那一圈。
`firstStone` 因此从 `(72,59,74)` 移到 `(83,59,76)`(离铁矿柱只差一格 —— 这是这颗种子的巧合, 不是设计)。

**出口改成写死步骤(不再交给搜索)。** `climbOut` 原来是 `Goal.YLevel(surfaceY)` 交给 walker,
预算按 `wd.serverPillarsOutOfAPit`(四格深竖井 46 tick)估。**实测 6000 tick 只升 1 格**
(`exit.fromY=54 → exit.toY=55`), 下一级 FOOD 整个预算都花在从坑底重复搜索路径上。
差的不只是深度: 挖掘级在井底**横着挖**, 身体最后站在**自己的天花板下面**, 而 `TowerProcess` 不会破方块 ——
顶着石头跳只会报 `stuck (no Y gain)`, 读起来像缺能力, 其实是计划少了一步。
现在按下降的同一套写法展开: **清 `feet+2` → 搭一格 → 重复**, 每层记 `climb.<n>`,
顶上空了还升不上去就把 builder 自己的 `lastError` 记下来。
封闭传感器 `wd.serverTowersOutOfADeepShaft`(九格深、一格宽、井底带横向凹龛=有天花板)钉住这个形状。
石头级配额随之 20→32: 账单是石镐 3 + 出口每层 1 格(九层) + 熔炉 8, 拿 19 块回来的那一跑
是**在拿熔炉的份付出口的账**。

**一趟 3×3 合成会吃掉全程唯一的工作台。** `CraftProcess` 在够不着工作台时自己放一个、走的时候再收回来,
而收回是**故意 best-effort** 的(合成不该因为收尾失败而判负)。石头级是在**自己挖的井底**合成的,
爬出来时 `craftingTable=0` —— 然后熔炉级抱着 24 块圆石一个也做不出来, 判词只有 `furnace=0`。
写死步骤补法: 任何 3×3 合成前先 `ensureCraftingTable`(没有就现做一个, 四块木板, resolver 自己会规划
原木→木板→工作台), 并记 `craftingTable.remade`。**不去改收回逻辑** —— 脚本能覆盖的事不该动引擎。
熔炉级同时补了 `craftingTable` / `craft.lastError` 两条证据: 24 圆石 + 0 熔炉不是材料问题,
而 `furnace=0` 一条读不出是台子、格子还是进程。

🔴 **未解(本会话最大的一条): 挖矿级会把服务端的 tick 拖到近乎停摆, 三跑可复现。**
`Server thread` 停在 `MinecraftServer.waitUntilNextTick` 的 `parkNanos`, **全进程 CPU 归零**
(200 秒只走了 16 ms), 之后日志一行不再出。已经排除的:
- **不是内存**: 14 GB 空闲, 没有 swap 压力。
- **不是被 OS 挂起**: `Get-Process().Threads` 全是 `Wait, UserRequest`(自愿等待), jstack 能连上并返回。
- **不是我们自己的线程**: 整份 dump 里 `magicterra.worlddriver` 帧数 = **0**, 寻路早已结束。
- **不是 "Can't keep up"**: 三跑都只有一条 42~44 tick 的落后告警, 且都发生在僵死前好几分钟。
- **不是配额半径**: 半径 14 时僵在 `67,62,91`, 退回 10 后仍僵在 `96,40,87`(第二矿脉)。

**剩下的解释只有一个方向**: `haveTime()` 长期为真 —— 即 `nextTickTimeNanos` 远远跑到了未来,
或者 `nanosecondsPerTick` 变得极大, 于是服务端**不是死了, 是每几秒才走一 tick**。
这也解释了 StageWright 的 stall watchdog **为什么没报**: 它盯的是 tick 计数器是否**变化**
(阈值 90 秒), 而计数器还在慢慢加 —— 慢到极点的 tick 对它来说不算 stall。
**已做(2026-08-10, 已实证到 green)**: 三层, 都在 StageWright 侧, 都不依赖 worlddriver ——
它的客户是一个没有 driver 的整合包。
1. **速率判据**(`TickStarvation`): 同一窗口里少于 20 tick 也算 stall(标称 1800),
   判词区分"停住"和"爬行"。
2. **harness 在 tick 上也查这条**, 命中就把**这一个场景**判 TIMEOUT 收掉, 套件继续跑 ——
   僵死通常是一个场景的问题, 剩下 200 个场景没理由跟着陪葬。
3. **心跳文件** `stagewright-progress.json`: watchdog 线程每秒覆写一次(场景名 / tick /
   窗口内 tick 数)。裁决**只在缺尾时**读它, 于是"harness 中途死亡"这句话后面终于跟得上
   死在哪、当时世界还跑不跑。

**实证方式**(值得记住, 因为健康的一跑碰不到这些路径): 把判据临时改成**不可能满足**跑一轮门禁。
第一轮就抓出一个真 bug —— watchdog 和 harness 共用一个阈值时会**赛跑**, 而 watchdog 恒赢
(它的窗口在武装时开一次, harness 的每个场景重开), 于是一轮僵死照样死成 DEAD, 正是加第 2 层
要消掉的结果。改成 run 窗口 = 3× scene 窗口后再跑: harness 先收场景、下一个场景照常 PASS、
watchdog 在第三个窗口才接手。恢复阈值后两 loader 全 GREEN, 214 场景零误报。

**还没做**: 一个能读出 `nextTickTimeNanos` / tick rate 的探针来解释**根因**。
现在僵死至少会自报家门, 但为什么 `haveTime()` 长期为真仍未知。

## 🏆 2026-08-10 阶梯地板抬到 IRON, 高度到 PORTAL_KIT —— 零布景, 一具身体

```
RECON ✅ SPAWN ✅ WOOD ✅ WOOD_TOOLS ✅ STONE_TOOLS ✅ FOOD ✅ FURNACE ✅
IRON ✅ 铁锭 ×6   PORTAL_KIT ✅ 桶 ×1、打火石 ×1
journey 爬到 PORTAL_KIT，地板 FURNACE，峰顶 DRAGON，布景调用 0 次
```

这一轮之前阶梯回退到 WOOD_TOOLS(低于地板)。八次跑修出的六条, 全部是**脚本**层, 没有动引擎:

1. **石镐那一级从来没检查过工作台还在不在** —— 木镐那一级会把它吃掉, 所以它之后的第一次
   3×3 合成才是发现台子没了的那一次。熔炉/传送门两级早有这个守卫, 这一级写在税被发现之前。
2. **一棵树不够一棵树的木头**, 而木头那一级按错了级的账单验收(3 根 = 木工具的账,
   不是阶梯的账)。实测单树产出 4→3→2 根, 两轮死在同一道算术上, 隔一级。改成
   survey 出第二棵 + 断言写阶梯的账。
3. **七根木头还差一根 —— 品种不对**。沼泽混生橡木与白桦,`CraftProcess` 的依赖解析
   **锁死一个木板变体**而不是按 `planks` 标签走, 于是 `logs=7` 配 `缺 1 个 oak_log`。
   第二棵树现在按第一棵的方块 id 找;`logs.kinds` 按品种记账。
4. **合成需要放得下台子**。石镐原本在自己刚挖的一格宽竖井底部合成, 报
   `脚边没有可放置的空位`; 同一份代码一轮过一轮不过, 取决于最后一节井恰好什么形状。
   改成先爬出来再在地面合成。
5. **台子要带着走**。走回去用它只是把问题推后一级 —— 身体接着走一百格去挖铁, 下一级就
   `standing=none` 再买一张。现在把立着的台子挖回包里, 之后每一级都 `craftingTable=1`。
6. **食物那一级搜得比它看得见的远三倍**。实体只存在于已加载区块, 而随身 pin 是 2 区块;
   96 格的搜索有三分之二注定是空的, 而它报的是"附近没有动物"。改成这一级临时把 pin
   放宽到自己的搜索半径, cleanup 收回。

另外两条属于**走路**而不是资源: 跨野外的腿会"到了"在离目标 88 格的地方(`IntentProcess`
对半程路径也报到达), 现在最多重规划 3 次并记 `walkAttempts`; 矿脉扫荡从 `drive` 改
`settle`(配额是上限不是要求, 矿脉挖空不该判死整级)。

**地板已棘到 IRON**(第五次抬升, `JourneyStage.IRON` 同步翻成 gating), 依据是同一份代码上
连续三轮绿(铁锭 ×4/×6/×6, `stagingCalls=0`)。抬升后第一轮就抓到一次回退 —— 而且是**我自己**
那一版"给工作台清一格"只检查了格子空不空、没检查底下有没有东西: `climbOut` 在自己挖的井里
垒一根一格宽的柱子, 身体最后站在四面是空、四面**底下也是空**的柱顶上。地板存在的意义就是
这个, 第一次派上用场就兑现了。

**后续把 PORTAL_KIT 也打通了**(7 轮里绿 3 轮, 最后一轮绿):
- 铁那一级改成**按账单挖**而不是挖固定条数矿脉 —— 本种子矿脉 1~3 块矿, "挖几条"没有稳定
  答案, "够不够"才有。第三条矿脉要避开**前面每一条**(只避第二条会返回第一条的坐标), 且
  要用 80 格半径去找(48 格内 `NOT_FOUND`, 这是搜索的事实不是种子的事实)。
- **打火石那一半从来不是问题**: `gravel.collected=65`, `flint=6`。两次失败都是铁 ——
  桶吃 3 锭, 打火石吃第 4 锭。
- **工作台改成合成完当场捡回来**, 而不是下一次合成时再去找。铁那一级开始跑两三条矿脉后,
  下一次合成已经在一百格外, `standing=none`, 于是拿着 5 个铁锭报 `缺 1 个 oak_log`。
  当场捡回来之后铁那一级从 10000~19000 tick 降到 **4628 tick**。

**OBSIDIAN 的能力问题已经答了, 而且答案是"不用补引擎"**: `wd.serverCastsObsidian`(两 loader
绿, 160ms, 已提升为 required)在竞技场里跑通了整个浇筑 —— 空桶从岩浆源装满、倒进**自己选定**
的格子、水把它转成黑曜石。写在写这一级**之前**而不是之后, 因为这一级是几十格下挖, 在那里
才发现身体不会用桶就太贵了。路上四个错答案都值得记住:
- **动词不是 `useItemOn`** —— 那是对方块的路径, `BucketItem` 没有 `useOn`; 桶的活在
  `Item.use` 里, 驱动侧是 `useItemInHand`。用错的那个会干净地返回并且什么都不做。
- **瞄准是输入, 不是装饰**: `use` 从眼睛发射线, 所以 `aimAtBlock` 就是targeting, 没有格子参数。
- **瞄完要过一 tick** 再 use, 否则 use 读到的是上一次的朝向。
- **模具要有底**: 对着底下是空气的格子瞄, 射线打空, 倒出来是 `PASS` 且桶还是满的 ——
  这和"倒到别处去了"的 `CONSUME`+目标格空是两种不同的 bug, 所以两个都记。

## 📊 IRON 这一级的可靠性(地板就压在这儿, 值得单独记)

2026-08-10 一晚十二轮, IRON 绿 6 红 3(其余被下面的级挡住没跑到)。红的三次是**三个不同的原因**,
都不是"铁矿挖不动":

| 轮次 | 失败句 | 真因 | 已修 |
|---|---|---|---|
| 1 | 走不到下挖点, 停在 22 格外 | 身体卡死, 三次重规划问了同一个问题 | `walkToColumn` 卡住就先走中点 |
| 4 | 走不到下挖点, 停在 88 格外 | 上一级追牛把身体丢在荒野, 寻路自己报 `goal unreachable from here` | 食物那一级打完猎走回出生点 |
| 11 | `iron ingots smelted (0)` | 熔炉补做时没工作台, 而唯一的痕迹是两行之后的 `furnace.after=0` | 补做走 `ensureCraftingTable`, 并记 `remadeError` |

**共同形状**: 每一次真正的因都在**上一段**, 而失败句描述的是当前段。这正是地板存在的意义 ——
它不让"这一级偶尔绿"冒充"这一级可靠"。

## 📊 全梯可靠性(22 轮统计)+ 提升门槛该按什么定

把 `journey-obsidian-*.log` 全量数了一遍(轮 7/8/9 是我主动杀掉的, 无数据):

| 级 | ✓/✗ | 最后一次红 | 那次的真因(已修) |
|---|---|---|---|
| 01Recon / 02Spawn | 22/0 | 从未 | — |
| 03Wood / 04WoodTools | 21/0 | 从未 | — |
| 05StoneTools | 17/2 | 轮 13 | 工作台被上一级花掉 |
| 06Food / 08Furnace | 17/0 | 从未 | — |
| 09Iron | 12/4 | 轮 13 | 熔炉补做时没工作台 |
| 10PortalKit | 11/1 | 轮 16 | 第三条矿脉侦察了但没烘入 |
| 11Obsidian | 4/7 | 轮 21 | 隧道挖穿洞顶, 掉到 y=14 |

**这张表不能直接当概率读** —— 每一次红都在**当时那版代码**上, 而那个原因随后就被修了。所以
"历史通过率"永远低估现在。真正能拿来判断的只有一个数: **同一份代码连续绿了几轮**, 也就是
现在的门槛。

**但门槛不该对所有级一视同仁。** 11Obsidian 三连绿之后第四轮翻车, 而下面几级三连绿之后就再没
出过事 —— 差别不在运气, 在**这一级有没有一段路是侦察没看过的**:

| 级 | 未侦察的那一段 | 三连绿够不够 |
|---|---|---|
| 03–10 | 无。recon 认证过树/石/矿/砾石的柱子, 走的都是查过的地形 | 够(实测) |
| 11Obsidian | **隧道最后那几格是横着挖穿没查过的岩石** —— 可能挖穿洞顶 | 不够(实测 1/4) |

所以下一级(12PortalLit)在岩浆边掏十个兜, 暴露面比 11 还大, **它的提升门槛应该从一开始就写成
五连绿或更多**, 而不是等它像 11 一样先升后降一次。

## 🔎 待查(引擎侧): `aimAtBlock` 不动头部朝向

`ServerPlayerAvatar.aimAtBlock` 只写 `setYRot`/`setXRot`。`LivingEntity` 另有一个 `yHeadRot`,
而且 `getViewYRot`(进而 `Entity.pick`、以及任何按头部朝向做判断的代码)读的是**它**。

- 桶没事: `Item.getPlayerPOVHitResult` 直接读 `getXRot()/getYRot()`, 所以浇筑一直是对的。
- 出事的是"预测": 黑曜石那一级用 `pick` 判断视线被谁挡住, 结果射线朝着**没人瞄过**的方向 ——
  身体在 `-4,27,56`、岩浆在 `-6,26,54`, 命中点却一路 `-4,28,57 → -3,28,57 → -2,27,58` 往外走,
  自驱隧道老老实实朝反方向挖了八格。
- 测试侧已绕开(自己按 `getPlayerPOVHitResult` 的方式 clip)。**引擎侧要不要让 `aimAtBlock`
  同时设置 `yHeadRot`, 是一个单独的决定** —— 一旦改, 任何按头部朝向做判断的地方(实体索敌、
  第三方 mod)都会跟着变, 所以不该顺手改。

## ✅ 已修(测试侧): `makeRoomForAStation` 问的问题和放置器不一致

`PlaceNearby.place` 扫 **8 个水平偏移 × 3 层 dy = 24 格**(`canBeReplaced` 且脚下既不是空气也不
可替换); 而这个 helper 原本只看**脚边四个正交格、只看同一层**, 判据也不同(`!blocksMotion()` /
`blocksMotion()`)。**24 格 vs 4 格, 两套判据。**

后果比"多走几步"严重: 它会在放置器其实放得下的地方喊 `station.noGround`(39/42 两轮绿行里都有,
craft 照样成功了), 而且它的"走开"是**固定罗盘方向 ±4 格**的猜测 —— 实测从 `62,63,64` 走到
`62,63,60`, 从一个放不下的格子换到另一个放不下的格子, 然后熔炉那一级红了两轮(`furnaces
crafted (0)`, 而 `cobblestone.before=25`、`craftingTable=1`, 材料全在)。沼泽里这是常态: 身体站在
水里, 四周的"地面"还是水。

已修三处:
- **问一模一样的问题** —— `placerWouldFindRoom` 就是 `PlaceNearby` 那 24 格判据的副本。
  *两套测同一个条件的代码只要不一致就是 bug 制造机: 总有一个是错的, 而失败信息不会告诉你是哪个。*
- **走到答案上, 不是走个方向** —— `groundWithRoomNear` 由近及远找一个"站得下 + 放得下"的格子。
- **证据 key 加下标** —— `station.steppingOff.N`, 否则三次尝试只剩最后一次(pickup 那批 key 犯过
  同样的错)。

## 🔎 待查(引擎侧, 诊断错怪): `SmeltProcess` 把"没地方放"说成"背包里没有"

`需要熔炉（背包里没有可放置的熔炉）` —— 实测这句话在**背包里明明有熔炉**的时候照样会出:

```
furnace.carried=true, furnace.after=1        ← 熔炉一直在包里
smelt.lastError=需要熔炉（背包里没有可放置的熔炉）
```

看代码就清楚了: `SmeltProcess.placeFurnace` → `PlaceNearby.place`, 那里先 `a.holdItem(item)`
(翻全部 36 格, **找到了**), 再去周围 8 格 × 3 层找一个 `canBeReplaced` 且脚下是实心的格子。
**返回 null 的两个原因是"没物品"和"没地方", 而这句话只说了前一个。** 身体当时站在自己刚
垒出来的一格宽柱子顶上 —— 四面是空、四面的下面也是空, 地方大得很, 没有一格放得下东西。

- 和 `holdPlaceable` 那条("out of blocks?" 而身体有 110 个圆石)是同一族: **猜错原因的诊断
  比没有诊断更贵**, 因为它会终止排查。
- 测试侧已绕开: 熔炼前先 `makeRoomForAStation`(原先只有 craft 那条路问了这个问题)。
- 引擎侧建议(**未改**): `PlaceNearby.place` 把两种失败分开返回, 让调用方的错误文案能说对。

## 🔎 待查(引擎侧): `holdPlaceable` 只翻快捷栏, `holdItem` 翻整个背包

`ServerPlayerAvatar` 里两个"把方块拿到手上"的方法, 搜索范围差了 27 格:

| 方法 | 搜索范围 | 找不到时 |
|---|---|---|
| `holdPlaceable()` / `holdPillarBlock()` | `items[0..8]`(快捷栏) | 返回 `false` |
| `holdItem(Item)` | `items[0..35]`(全背包, 会把物品换进选中格) | 返回 `false` |

`TowerProcess` 走的是前者。于是一个爬了四级梯子的身体 —— 快捷栏里是镐、桶、燧石、食物 ——
**手里揣着 110 个圆石, 塔却报 `no placeable block in hotbar`**。黑曜石那一级的出井实测:
要爬 36 格, 爬了 1 格(`exit.gained=1/36`), 靠 walker 兜底才没卡死。

- 这条曾经被误读成别的问题两次: 一次是 `a-jump-is-not-a-gain`(在空中量高度, 把拒绝放置读成成功),
  一次是 `the-exit-is-a-plan-not-a-search`(6000 tick 只爬一格)。**两次的 `.stalled` 文案都说
  "out of blocks?", 而身体一直有块** —— 猜错原因的诊断比没有诊断更贵。
- 测试侧已绕开: 出井每一层先 `holdItem(圆石)` 再让塔去放, 塔的 `isSupport(main)` 就直接命中了。
- **引擎侧要不要让 `holdPlaceable` 也翻整个背包, 是一个单独的决定**: 快捷栏之外的东西被自动
  换上手, 对一个有人类玩家在看的客户端身体来说是会打断操作的; 而"调用方指定用什么垒柱子"
  本来就有 `TowerProcess(targetY, blockId)` 这个更精确的入口。

## 🔎 待查(引擎侧, 已被脚本绕开但根因未查): 工作台会凭空消失

`CraftProcess` 自己摆工作台、尽力回收。实测一轮: 8 根原木 = 32 块木板, 真正配方只花了 9 块
(工作台 4 + 木棍 2 + 木镐 3), 到石器那一级只剩 3 块木板、0 个工作台, 报 `缺 1 个 oak_log`。
中间那 20 块全变成了工作台 —— 而那些台子**既不在 32 格内立着, 也不在地上躺着**。

- `wd.serverCraftTableReclaim` 在竞技场里是绿的, 所以这个丢失在竞技场那个尺度上看不见。
- 脚本侧已绕开两层: 每次合成都走 `craftKeepingTheTable`(合完就把台子挖回来), 以及木头不够时
  现场去砍一棵再试一次。**但这两层都是止血, 不是答案。**
- 值得查的是: 台子被 `CraftProcess` 破坏之后掉落物去哪了。三种可能 —— 根本没掉、掉了但立刻
  被销毁、掉了但在别的位置。三者需要不同的修法, 而现在的证据分不开它们。

## 🔎 待查(引擎侧, 已被脚本绕开但根因未查): 走 22 格找不到路

2026-08-10 铁那一级实测: 身体停在 `78,63,96`, 目标柱 `83,75` —— 水平 22 格。之后**四分钟里
发出约九十次 `search-begin owner=goto ... maxNodes=100000 maxMs=4000`, 起点每次都是同一格**,
一次都没找到路, 身体一步没动。三次重规划问了三个一模一样的问题。

- 脚本侧已绕开(`walkToColumn` 现在会先走中点再续), 但那是**把长问题拆短**, 不是答案。
- 值得查的是: 22 格的目标为什么会耗尽 100k 节点。要么目标格不可站(`XZ radius=0` 要求精确列),
  要么中间那片沼泽水面让 A* 无法收敛。前者是目标合法性问题, 后者是水面代价/可达性问题。
- 复现材料: 这次的 run 日志 + 种子 5471, 出生点走食物那一级追牛到 `21,64,131` 之后就会到这一带。

**这一级已经写完并且第一次真的跑起来了**(2026-08-10, `wd.journey11Obsidian`, required=false)。
计划: 走到岩浆柱 → 在**旁边**一根查过的柱子下挖 → 用射线自己开路挖到岩浆源 → 装桶 →
原路爬回地表 → 倒进**事先点名的**一格静水里 → 断言那一格是黑曜石。

- **"一块而不是十块"是这一级本身, 不是偷懒**: 黑曜石拿不走(要钻石镐), 门框是**就地浇**的,
  十格摆哪里是 PORTAL_LIT 的问题。
- **为什么在地表的水边浇, 而不是在岩浆池边浇**: 门框真正的打法是把水**背下去**放成源、
  一只桶来回运岩浆。这里写不了, 原因值得记: 竖井底放下的水会顺着同高度的任何开口流,
  而这一级必须开的那个口就是通往岩浆的口 —— 水一碰到池子就把桶要打的那个源变成黑曜石。
  两边在抢一条两三格长的隧道, 大约十五 tick。地表倒进已有的水里没有这个竞争, 动词一样。
- **挖到岩浆的那段隧道不需要路径规划**: 桶是沿着视线装的, 所以**视线第一个撞到的东西就是障碍**。
  瞄准岩浆 → 问 vanilla 自己的 pick 撞到了谁 → 挖掉 → 再看。挖的每一块都在通往目标的直线上。

**第一次跑的结果**: 走到岩浆柱(`arrivedDistance=0`, 一次就到)、发现自己正站在岩浆柱上并让开、
选中偏 2 格的柱子、按 34 格深度把上限缩放到 122 次 —— 全对; 然后**浮住了**: 选的柱子在沼泽
水塘底下, `supportUnder` 连报 122 次 `minecraft:water`。已修, 而且修了三轮, 每一轮都是上一轮的量出来的:

1. 选柱要过干燥尺, 环搜到 8 格 —— 然后 **280 个候选柱 280 个被否**, 理由全是"柱子里有流体"。
2. 把"整根柱子干燥"放松成"井口下 12 格干燥" —— 还是 280/280, 理由变成"井口下方有流体"。
   到这一步结论就清楚了: **这颗种子最近的那潭岩浆压在沼泽水位下面**, 不是尺子太严, 是这潭
   本来就下不去。
3. 所以真正该改的是**侦察**: 岩浆和铁矿一样, 要的是"能挖得到的那潭", 不是"最近的那潭"。
   recon 现在列出最近的 12 处**不同**水潭(16 格内的并成一潭, 否则一个岩浆湖就是两百个候选),
   逐个用这一级自己的选柱规则试, 取第一个能下井的 —— 本次选中 `(-6, 26, 54)`, 82 格外。
   候选表里还有一处在 **y=63(地表)**, 正是老的 `LAVA_SEARCH_TOP=50` 从构造上排除掉的那种。

**这一轮真正的收获是"在哪儿检查"**: OBSIDIAN 是最后一级, 在那里发现"地形不让下井"要先付
下面所有级的钱 —— 一次二十五分钟, 一次一个猜测。recon 一秒钟读同一件事, 而且把每个候选的
否决理由都记下来 —— 正是那张 280/280 的计数表让人看出**是规则坏了不是地形不行**。
一条谁都满足不了的规则不叫严格。

并且"脚下没地板"现在要分成"马上要掉下去"和"泡在流体里"两种——后者一行就该失败, 因为再多
settle 也治不了浮。

## 🏆 2026-08-10 03:40 阶梯首次爬到 IRON(铁) —— 零布景, 诚实挖掘

```
RECON ✅ SPAWN ✅ WOOD ✅ WOOD_TOOLS ✅ STONE_TOOLS ✅(500t) FOOD ✅(767t) FURNACE ✅
IRON  ✅ 铁锭 ×2 出炉 (1512t)
   furnace.before=1  descent.landedY=60  raw_iron=2  raw_iron.onGround=0  iron_ingot=2
   staging.calls=0
```
`raw_iron.onGround=0` 是这一跑最值钱的一个数: **挖出来的东西一件没剩在地上**。

**地板仍留在 FURNACE, 不上棘轮。** 这套代码下 IRON 只绿过**一次**;
"地板是'这一级能用'的断言, 不是'它曾经成功过'", 架在 n=1 上会让以后每一条红都读不出是回归还是抖动。
连续绿几跑再升。

**修完之后的实测(2026-08-10 02:11, 零布景)**: RECON→SPAWN→WOOD(7 根)→WOOD_TOOLS→
**STONE_TOOLS 达成**(石镐到手, 3233 tick)→FOOD→**FURNACE 达成**。
**IRON 第一次挖到并真的收进了背包**: `raw_iron=1`, `descent.landedY=60`, `descent.column=81,75` ——
诚实挖掘 + 竖井下沉 + 掉落物回收全线打通。这一级现在**卡在熔炼**(`iron_ingot=0`), 不再是采集。
新前沿 = `SmeltProcess`, 以及 `mine.endReason=collect timed out after 240 ticks (broke 2/4)`。

**旧记录(已解决, 留作诊断路径)**: 石头级的竖井**第一铲就挖穿了, 身体却不下沉**:
```
shaft.0=67,62,67 below=dirt   → shaft.0.broke=air   body 仍在 67,62,67
shaft.1 below=air(挖空气, 空转)  … shaft.3 漂到 73,62,71
```
IRON 的同一段代码沉得下去, 区别在**地面**: 石头柱 (67,69) 在沼泽的水边, 身体浮着就不会掉进洞里。
`JourneyRoute` 已经有为 IRON 写的 `dryDescentNear`/`dryCross`, 但 `firstStone` 是**没有干燥约束**侦察出来的。
**下一步**: 给石头级也侦察一个 `stoneDescent` 干燥柱(照抄 `ironDescent` 那套), 而不是去改引擎。

**仍未做**: 双轨的另一半(integratedServer 拓扑上真走 `mc.bot.*` 路由)还没搭。

## 2026-07-20 ✅ mine #logs 上悬崖树 nodeDy=7 wedge → 连坠伤致死候选gap — 已修(planning-layer 两界:no-progress 看门狗 + 累伤中止)【commit 4e85543 branch fix/mine-unreachable-target-watchdog;t0 fabric+neoforge+t1 全 GREEN + live 三性质全证;未合master(暂留分支)】

- **gate infra 教训(本轮踩)**:t0 用 `--run-task :<loader>:runDogfoodServer` **必须**配 `--results <loader>/run-dogfood/stagewright-results.jsonl`(AGENTS.md L87-89);漏了 → t0 poll 默认空路径 → 900s 超时假装挂死(其实 suite 早完成)。可 `verdict.judge(verdict.parse(<真实results>))` 直判免重跑。
- **neoforge 彩票警示**:`selfShaftDigUp`+`bridgeFootholdPlace` 在 neoforge 一跑 RED(backslide 13.25/stepUp越+2面)、clean 重跑全 PASS,fabric 同 build 恒 GREEN → arc-wedge 极限环族固有非确定,**未来这俩 neoforge RED 先重跑一次再认罪**(差点误判成 task#6 loader 分歧)。

- **症状**:死亡#26 修复合 master 后恢复生存,`mine{blocks:["#minecraft:logs"],radius:28}` 从地表采树。walker 路由到 node `(-4,88,-48) nodeDy=7`(单节点竖爬 7 格=悬崖/树干高处的 log),`[walker] arc-wedge RECOVER ... bob-immune ram → fellOffPath` **连续复触**,bot 反复坠落取 ~15 fall damage(HP 20→5.3),**净得 0 log**。
- **根因**:执行层 arc-wedge 干地陡爬 wedge(见 [[reference_steep_mountain_limit_cycle_revisit_detection]] 已知未解族)——walker 在悬崖高处 log 前**永不返回 `Step.FAILED`**,`fellOffPath` 只是无限 repath;而 MineProcess **只在 `Walker.Step.FAILED` 时 blacklist target** → bot 永远钉在不可达 log 上撞脸连坠。
- **修(planning-layer 两界,不动执行层 churn)**(`MineProcess.java`):① **per-target no-progress 看门狗**:track 脚到 target-stand 的最近距离;`GOING_STALL_TICKS`(~5s)内无净接近 → 视为实际不可达 → blacklist + re-scan。真行走最近距离持续改善故不误触;只有 churn 会 plateau。② **累伤中止**:HP 掉 `MINE_DAMAGE_ABORT`(8)低于本命令峰值 或 ≤`MINE_HP_CRITICAL`(4)→ **活着**中止(让策略层重定位);COLLECT(配额已达)后跳过。可达采矿零坠伤故平地稳 HP 挖不误触。执行层 arc-wedge churn 本体不动(每次快修都在 replay corpus 回归;真修需 A/B corpus)。
- **验证**:t0 fabric GREEN(6 mine 场景全 PASS,可达目标无误中止)。live 三性质全证:(a) 看门狗在 dy~9 悬崖 log 触发(`no approach ... 100t -> blacklist`)后 bot 采到 12 可达 log 存活 HP13;(b) 正常采矿不受影响(6/12-log 采集无中止);(c) 累伤中止**活着**端到端触发(active mine 中 HP 20→8 → `aborted: taking damage`,bot 活 HP8)。t0 neoforge + t1 gate 跑中待绿即合 master。
- **⚠️残余**:执行层 arc-wedge 干地陡爬 churn 仍是已知未解族;这两界只把它从**致死/无限**降级为**可恢复**(blacklist 换目标 / 活着中止)。真解需 replay A/B(别在超长上下文诊断)。
- **live 现状**:测试平台残留在 y108-150(远离 bot,无害);gamerules 已复原(daylight/regen/mobSpawning=true)。survival Stage-1 待起。

## 2026-07-20 ✅ 死亡#26(live survival resume)修复 — Skeleton 开阔地射死空手 bot;两修：① retreat 'safe' 释放加低HP威胁记忆地板 + ② BunkerChain 远程钉扎升级破 LOS —【t0 fabric+neoforge GREEN,matrix 新断言 PASS,① live 证震荡消】

- **修 ①**(`RetreatChain.java`)：新 `THREAT_MEMORY_TICKS=100`+`lastThreatSeenGameTime`（每 tick 按 sealed-filtered scan level-stamp）；"safe" 释放支加 `!(hp<thr && ticksSinceThreat<100)`——低 HP bot 近 5s 见过威胁≠safe，杀死 release("safe")↔enter("lowHp") 震荡。只动低 HP "safe" 支（recovered 支不变）；sealed 豁免（gap#72）保留（记忆按 filtered scan stamp）。旧 overload 委托 `ticksSinceThreat=MAX`→地板 inert→全部既有 matrix 保结果。
- **修 ②**(`BunkerChain.java`)：新静态 `shouldRangedBunker(hp,retreatThr,scan,sealed)=!sealed && hp<=retreatThr && underRangedFire(scan)`；priority() 改 `cornered||rangedPinned`。低 HP bot 被远程怪射中即升级 bunker 破 LOS（封顶 occlude 箭），即便 HP 高于 bunkerHpThreshold 且射手在 bunkerTriggerRadius 外。`!sealed` 防 gap#29 重挖棘轮。BUNKER=300>SURVIVAL=100 抢占 retreat；gated on autoBunker（默认 off，生存世界 on）。
- **验证**：t0 fabric GREEN + t0 neoforge GREEN（`wd.retreatGateMatrix` PASS，新增 death#26 断言 x/y/z/aa/bb（①）+ cc/dd/ee/ff/gg（②）=矩阵门确定性证）。① **live 实证**：受控骷髅战 bot 连续 flee `retreat=True` 41s **零震荡**（旧码此处翻转）。② trigger matrix 证 + 下游 dig+seal 是**今夜已 live 证的同一 wiring**（bot 自主 autoBunker SEALED 过夜）+ 既有 serverBunker 场景。
- **⭐live combat rig 教训**：平坦竞技场无法确定复现"骷髅远程狙低 HP bot"——骷髅总贴脸 melee（三次 live rig 全失败）。反射门验证靠**确定性 matrix + 组件分解**，别靠 flaky live combat。memory: `project_survival_death26_ranged_safe_release_los`。
- **⚠️残余边缘（未修）**：② 在桥/窄道/邻 void 会挖穿坠落（bunker tick 无 void 检查）——自然地面安全，gated on autoBunker。
- **待**：t1 gate（运行中）；提交（feature 分支）。

- **背景**:task#6 合 master(1e3acac,ff `81926cb..1e3acac`)后按 user "合并→恢复生存线" 恢复生存。SurvivalTest 存档(fabric integrated client)载入,bot 在 5 天前搁浅的 y67 石袋。
- **当前引擎正面收获(全 live 无缺口)**:① `stairUpBreak` 从石袋逃出到地表(**task#82 无镐上升 gap 在当前引擎不再阻塞**——A* 规划 + 执行全对,只是徒手挖石 151t/块慢)② 夜落 autoBunker 正确 SEALED,零伤过夜到黎明 ③ `mine #minecraft:logs` 自主寻树采 10 acacia_log ④ craft 链正常。
- **死亡#26**:采木后 **被 Skeleton 在开阔地射死**("was shot by Skeleton")。链:HP 13.7→5.7(~8伤)→ `[retreat] enter reason=lowHp thr=10 threats=1-2 combatEngaged=false` → **~35s 钉 HP5.7**:release(reason=**safe** hp=5.7) ↔ enter(lowHp) 每 tick 震荡 + `preempted by=dodge`(躲箭)→ 空手无还手 + 食物6无回血 → 一箭 hp=0。autoRespawn 回出生点满血满食,白天骷髅自燃止损。
- **根因(代码确认 `RetreatChain.releaseReason` L292-301)**:"safe" 分支 `!hostileWithin && !underRangedFire && !hurtByAnyone && !visibleRanged && !recentHurt` **完全不查 HP**(只 "recovered" 分支查 `hp>=thr+margin`)。开阔地骷髅在 threat-scan **闪进闪出**(走位破 LOS/射程边界/volley 节奏),HP5.7 时每个闪出窗口判 safe 释放 → bot 站原地暴露被重射。autoBunker 阈值 HP≤3(<5.7)从未升级到封 LOS 掩体。
- **gap 分类**:≠ gap#65(ranged **探测**失明,此处探测正常);≠ gap#73/task#77(panic reachability)。这是 **retreat 对持续远程火力的策略缺口**。两个子病:① "safe" release 缺 HP 地板(闪出 gap≠真安全);② 持续 ranged + 低 HP 应升级 bunker/掩体破 LOS 而非开阔奔逃。
- **修法风险**:改 retreat 释放门=极易回归 gap#65/#68/#71/#72 那串对称门(见 RetreatChain 那段 javadoc);需 replay/live A/B。**禁**长上下文冻结态无监督塞 if。memory: `project_survival_death26_ranged_safe_release_los`。
- **live 现状**:空手 bot idle 于出生点 ~(-0.5,79,-22.8),满血满食,白天 0 敌 passive 安全;Stage-1 木料随死亡掉光需重采。

## 2026-07-20 ✅ task#6 独木桥战役收官:18 场景全族 + wedge 三阶段 aim-clobber 根治 + guard-plug 语义分族 — 四门全绿(t0 fabric 161/161 ×2 + t0 neoforge 161/161 + t1 GREEN 132s,唯一 fail=既有 optional vineOverWaterClimb)

- **需求(user verbatim 契约)**:长条单宽独木桥全族——1 格/1.5 格/2 格障碍±旁路、挡头/挡脚±旁路、无路径停最远不掉落、阶梯升降、中断跌落(安全/损伤/致命±水桶)、垫脚方块(有/无材料)、两格阶梯旁路零消耗、破坏抉择(挖 vs 绕)。落 `WorldDriverBridgeScenes.java` 18 场景(fabric+neoforge expect 各 +18);"预期停在最远处但不掉下去"=引擎契约,全族 no-fall 不变式守住。
- **Wedge 真根(bypass trio 1200-tick 位相同签名,36 轮 t0 打磨)**:aim 管线三阶段各自把 recovery bearing 覆盖回"直线巡航"——①tangent override(WalkerTickAim ~349)②**trendCam centroid overwrite**(~508:dry flat 每 tick active,tangentAim 下 drive=aimYaw=EMA(centroid) **从来不是 tangent**,Phase-2 注释假设从未成立;dogleg 计划上 centroid 切角穿 void)③EMA 慢 alpha(0.08)。修=`pinnedRecovery=(hCol||guardSneakLatch)&&(reCentre||nodeAim)` 让①让位 + `recoverySnagAim` 同谓词 drop trendCam(②让位+α→0.5)。**验证纪律:as= 标签≠生效,必须看 pin-window 的 dr= 遥测**(三轮 bit-identical 失败都是只看了 as=)。
- **Drive 源铁律(两次反证)**:tangentAim 下 `driveTargetYaw` 恒= aimYaw。r30 全局换 descentNodeYaw=9 回归(slide-back 族/stop 族/selfShaftDigUp/waterFarAimBankCorner);r31 guard-pin 窄门仍令 stop 族离轴漂移→futile-FAILED@137。正确架构=只改喂进 EMA 的输入。
- **void-pin 恢复基建**:stride guard fire tick 会 `stuckTicks--`(护命设计不可动:防 recovery burst 把 pinned 身体推下崖)→void-pin 下所有 stuck-gated recovery 饿死(r29 StepTwo st0/117 livelock)。新=`guardPinClock`(guardSneakLatch?guardPinStreak:0)作替代 stall 钟(>5 解锁 reCentre/nodeAim);nodeAim pin-leg 带 `losWalkable` seatbelt(直线 aim 对 void 盲,r33/r34 平台东角 knife-edge 楔死)。
- **guard-plug 语义分族**:plan 含 pillarUp/bridgePlace/parkourPlace 边=construction journey→**即时 plug**(buoyantWall 的 +5 水墙 mount 曾"意外依赖"guard plug 当基建,r29 gate 后连红 4 轮才定罪——17 fires 摊 3-4/cell 全没 arm);place-free plan→**12 同 cell fires** 才 arm(防 centroid 贴边巡航自建 causeway,StepTwo r28 6 dirt;ARM=12 因 corridor recovery(6 pinned ticks)必须赢 race)。`walkerRecoveryHopFloorGate`(unaimed hop 落点致命 gate)+guard pin 滞回(overhang 暂停/planned-descent 释放)为前段战果。
- **新常驻观测基建**(scene drive(),全进 ctx.fail 消息=async log-loss 铁律):rolling trail(as=/dr=/jt=/st 探针)+journey(plan swap 快照)+pinWindow(15-tick 静止快照)+invEvents(库存 Δ 归因——r32 用它把"4 dirt"拆成 2 guard plug+2 planned construction)。
- **场景 nondeterminism 仲裁法**:连败≥3 且同签名才实锤回归;"identical float"可能只是 rig 确定性常数(buoyantWall maxY=208.2522=rung1 跳顶 apex)不指纹共因。
- **flag 挂账(维持 OFF,replay A/B campaign 待做)**:`walkerTailConsumeDirectional`/`walkerFromEndNoProgressDiscard`(bridge 场景 opt-in,默认 OFF——r14 反证 descentYaw/entityLeash/boxedChurn 依赖旧语义);`walkerCornerClearance`(drive 层 corner 斥力,wedge 上证明 inert,面上留防御);`pathfinderPillarCost=150`(PillarUp 材料稀缺定价)。parkour2d 经济学(cost 33 贴角捷径 vs 走廊 walk)+chained-parkour takeoff 对齐=独立 replay A/B campaign 待立项。
- **豁免**:MLG bucket leg(lethalGapStop 有桶通过腿)=dedicated-topology 豁免(类 javadoc 记录);vineOverWaterClimb=既有 optional sensor 非本战役产物。

## 2026-07-19 ✅ artifactId 修名 + rootProject.name 纠错(user 拍板 `mc_testkit-*`)

- **artifactId 根修**:root `build.gradle` `subprojects{}` 发布块的 `artifactId = base.archivesName.get()` 是配置期 eager 求值,抢在子脚本 `archivesName='mc_testkit-*'` 覆盖之前捕获 → 改为 `afterEvaluate` 延迟读取,子模块意图生效。发布坐标现为 **`mc_testkit-{common,fabric,neoforge}`**(与既有 `mc_stagewright-junit` 成一族;`worlddriver-{common,fabric,neoforge}` 驱动 mod 件不变)。`publishToMavenLocal` 复验:新坐标齐全、mod 族 POM 全零依赖、junit 3 依赖照旧、worlddriver 发布 jar 双向字节门过(`scene/SceneProvider` 接口属 testkit 框架面合法打包,非场景实现;服务文件与 `WorldDriver*Scenes` 实现零条目)。`~/.m2` 陈旧 `worlddriver-testkit-*` 三目录已删(仅 mavenLocal 存在过,零远端消费者=零迁移成本)。`stagewright-junit` 有 early-return 剖出不受 afterEvaluate 影响(保留自己的显式 publication)。
- **rootProject.name 纠错**:`settings.gradle` 的 `rootProject.name = 'noteblockapi'` 是模板 fork 残渣(全仓零引用)→ 改为 `worlddriver`。子项目名 `testkit-*` 审查后保留(纯内部 gradle 路径,全仓引用+文档一致,artifactId 已由子模块 archivesName 决定,与项目名解耦)。
- **README** maven 节/artifact 表/字节门注记同步为新坐标;历史条目(P3b/P4 各期"artifactId 残余"记录)照豁免规则不改写。
- **user 待决余项**:merge 策略 / 真远端 maven 仓库(推远端前坐标已定型)。

## 2026-07-19 ✅ task#93 收案 → config-persistence 陷阱修复(**SHADOW-DEFAULT 持久化**)— 本条目所在 fix commit(plan 见 `7965f1c`;branch `feature/executor-permove-ascend`,详录 `.superpowers/sdd/task-1-report.md` + `docs/stagewright/migration-log.md` task#93 段)

- **根因(D2 发现)**:`BotConfig.save()` 反射落盘**每个**字段、`load()` 启动**无条件**逐字段 `assign` → 持有旧 properties 文件的客户端把**旧版本默认值快照**当"用户设置"应用,**压制后续版本默认翻转**(实证:`walkerWaterClimbLateralGate` 新默认 `true` 被上一 session 陈旧 `false` 覆写)。
- **修 = SHADOW-DEFAULT**(`BotConfig.java` 持久化段 + 类尾 `static{}` 捕获编译默认):`save()` 每键写两行 `<key>=<值>` + `<key>.default=<本 build 编译默认>`;`load()` 每键判等——**值==影子→SKIP**(仅是当时默认的快照,当前编译默认胜出=陷阱修复本体)/**值!=影子→APPLY**(用户改过,保留)/**无影子(legacy)→保守 APPLY + 一行 WARN 漂移清单 + 升级重存**。陈旧快照亦触发升级重存;稳态零 churn。**设回默认=跟随默认**(javadoc 写明)。向后兼容天然:旧代码只按字段名反查,`.default` 行(`.` 非合法标识符字符)静默忽略。
- **Step 1 现实现观测**:旧 `load()` 只 `for field → props.getProperty(field.name)`,**从不遍历文件 key** → 未知键(含 `.default`)静默跳过、不抛不警;单键解析失败 WARN+跳过、整体失败 WARN 不阻断——两条容错语义保留,升级重存亦 best-effort。
- **Live 四向验证**(`:fabric:runServer` + `:fabric:runClient`,`-Dworlddriver.persistConfig=true`;**RPC 读回须走 CLIENT**——`mc.bot.setting` 路由 `requireBot()`,bot impl 仅 client 入口注册,裸专服返回 "bot impl not registered",title-screen 即可读不需进世界):**(a)** `walkerWaterClimbLateralGate=false`+`.default=false`→RPC 读 `true`(当前默认胜);**(b)** `autoSwim=false`+`.default=true`→RPC 读 `false`(用户保留);**(c)** legacy `autoFight=true`→RPC 读 `true` + WARN `[autoFight]` + 文件升级;**(d)** `mc.bot.setting{autoHeal:true}`→save→重启→RPC 读 `true`(round-trip)。两次快照 229 键**零 `.default` 泄漏**进 settings 面。
- **Step 4**:`fabric/run/config/worlddriver_bot.properties`(手工刷过两键的 live prefs)恢复原内容后 boot 一次自动升级到 shadow 格式(206 影子行),**有效值逐行 diff 与备份一致**,末态留升级态。
- **✅ Armor**:opt-in 门(dogfood/testkit/contract/t2 不设 `persistConfig` → `load/save` early-return,改动**证明性 inert**);dogfood **双 loader GREEN**(各 131 场景/137 行,goldens 未改)+ instrument **23/23 双 loader**(#280 门证 `.default` 不进 schema)+ **t1 GREEN**(agentRpcSmoke 799t/35s 真跑;唯一 optional fail=既有 `wd.vineOverWaterClimb` −711,非回归);**testkit runDir 零新落盘**(opt-in 门完好)。⚠️环境:早段外部 Touhou 客户端 ~900% CPU + killed-run 端口 25599 释放竞态致两次假超时,拆 leftover + 清 world + 加 `--wall` 后全绿一跑过。
- **残余**:无(task#93 全闭);**user 待决**(不变):`artifactId` 发布坐标 / merge / 远端仓库。

## 2026-07-19 ✅ D2 债务修复阶段**收官**(D2-T4 终验 + 文档)→ 三债 task#86/#87/#91 全闭,场景 130→131,治理面下调至 2 optional sensor;八门全绿 + 一处 live over-forbid 反证 — commit chain `70310eb`(plan)/`5aedb81`(task#86)/`99cf2b1`(T1审修)/`250d55b`(task#87 RED)/`a1ce629`(task#87 CLOSED)/`6da1798`(task#91)/`32c136d`(T3审修)+ 本 docs 收官(branch `feature/executor-permove-ascend`,详录 `.superpowers/sdd/task-4-report.md`)

- **三债根因一句话 + 修法 + 证据指针**:
  - **task#86 收案(D2-T1,`5aedb81`+`99cf2b1` 审修)**:根因=**planner-root**(非 executor/非 strideFloorGuard)——挖升 climb 顶端 A* 从 1 宽 pillar TOP 重规划 `parkourAscend2` 跳板(比再垒 2 rung 便宜),但静止 pillar 顶无 run-up,executor 起跳即坠、沿自建空心柱直落 ~20 格,`strideFloorGuard`(腾空无邻面可垫)结构性拦不住。修=**`pathfinderParkourAscendNeedRunway` 默认翻 ON**(`ParkourAscend.valid` 要求 leap 的 BEHIND 格 `canStandAt` 才准 → A* 拒 runway-less 跳板改直上 pillar 顶稳);`wd.selfShaftDigUp` 从**黄金失败签名门**翻**strict 门**(`worstBackslide ≤ pathfinderMaxDryFall+1=5`,真活性不变式)。A/B 双 loader 字节一致 ×3:OFF `worstBackslide=20.252203415101263` → ON `worstBackslide=1.2522034151012633`(pillar-jump-arc settle 内生,非坠落)。证据 `migration-log.md` task#86 段 + 场景 javadoc `WorldDriverScenes.java:700`。
  - **task#87 收案(D2-T2,`250d55b` RED→`a1ce629` CLOSED)**:根因=已删 `entityLeashRepathArena` 的 y≈−60 phase-2 停滞是 **rig-disease 非引擎缺陷**。做法=造 `wd.entityLeashLowY`(y=−58/−59,`wd.entityLeash` 低 Y 孪生)复现→round-1 RED 是**地形混淆**→**void-moat 隔离**(整 rig 足迹低 Y 填空)→ GREEN ×6 **与 y=200 孪生逐字节一致**,证引擎低 Y 无缺陷。结局=probe 提升 `required` 低 Y 活性门,场景计数 130→**131**。证据 `migration-log.md` task#87 段(1167/1233/1266)。
  - **task#91 收案(D2-T3,`6da1798`+`32c136d` 审修)**:根因=**executor-root**——开阔河岸 A* 恒返正确远侧走出路(低岸 +5 EAST 跨水),但 climb-out executor 把「横向远、只 +1 高」的 waypoint 误读成 climb-HERE,对 +5 陡壁开挖(`wallPressTicks≈51`)。修=**`walkerWaterClimbLateralGate` 默认 ON、baseline-EXEMPT**(正确性不变式非可调项;climb-out 仅当 waypoint 横向 BESIDE bot 才 engage,`WATER_CLIMB_LATERAL_MAX=2` Chebyshev)。K≥6 A/B 双 loader:OFF 6/6 wedge → ON 6/6 ashore(字节一致);`wd.riverSheerBank` 提升 `required`,optional sensor 3→**2**。证据 `migration-log.md` task#91 段(1274)。
- **✅ D2-T4 终验矩阵(前台有界顺序,ONE server/client,timeout≥300000;详录 `.superpowers/sdd/task-4-report.md`)——八门全绿**:
  - ①**dogfood neoforge GREEN**+②**dogfood fabric GREEN**(各 131 场景,expect-file 已同步)。金值双 loader **逐字节一致**:descentYaw `sumAbsDyaw=871° backSteps=53`、selfShaftDigUp `worstBackslide=1.2522034151012633`(strict @≤5)、gearScope `sword ATTACK_DAMAGE=6.0 ATTACK_SPEED=1.5999999046325684 / ARMOR=20.0`、agentRpcSmoke 专服 147/0、entityLeash + entityLeashLowY PASS(within 180)、riverSheerBank `ashore=true wallPressTicks=54` REQUIRED、entityLeashLowY phase2 `reached=true sceneTicks=2`。
  - **parkourAscend metric 字节(D2-T1 审修债,双 loader 逐字节同一)**:`[wd.parkourAscend] step=ARRIVED pos=(108196.40823740883,232.0,100000.50000017369) minY=231.0 fellInPit=false onLanding=true` — runway 门 ON 下 +1 pit landing 未坠(`fellInPit=false`)、到位(`onLanding=true`)= over-forbid 失败模式的确定性反证。
  - ③**instrument neoforge 23/23 GREEN**+④**instrument fabric 23/23 GREEN**(canary mustFail/mustSwallow 门存活)。
  - ⑤**t1.py(集成)GREEN**——`wd.agentRpcSmoke` PASS 259/0(1 named task#92 skip);⚠️首跑 RED 单点=`41_defense: ThreatScanner senses an incoming projectile`(投射物时序检查在 box 高负载 load~10-12 + 外部 Touhou 客户端 285% CPU 下抖动),D2 改动零触 ThreatScanner,**重跑即 GREEN**=负载 flake 非回归。⑥**t2.py(生产拓扑)GREEN**。⑦**instrument_client.py GREEN**(8 check + 2 canary)。
  - ⑧**`./gradlew :stagewright-junit:test --rerun-tasks` GREEN**(经 `t1.py --hold` 导出 `TESTKIT_ENDPOINT` attach,用后按显式 PID 释放 hold):17 测试 15 PASS + 2 预期 SKIP(负路径 "no endpoint" 在有端点时反向跳过),live UI 全跑(ContainerFurnace/ChatScreen/InventoryScreen×2/Canary×2)。
- **live over-forbid 反证(D2-T1 审修债②,runway 门 ON 变地形 live/replay)**:live Fabric 客户端 Mountains 存档,门 ON。**受控正例**:自建 runway-backed +1 gap(5 长 runway→2 格 gap→+1 landing)→ bot 用 **6× `parkourAscend2`** 起跳到位(`goalReached=true endReason=arrived finalDist=0 ms=1401 totStuck=2`,零 thrash)= 门允许 runway-backed 跳板。**replay A/B**(`high-crest`/`steep-diagUp` 上升 corpus,门 ON vs OFF 同负载):两态在水岸/陡楔结构区 churn 相当(crest maxStuck 1115 ON / 598 OFF;steep 631/602;高负载下均不到达),`parkourAscend*` 两态皆 churn-free = 门未引入 baseline 没有的 parkour 专属停滞。**over-forbid 失败模式(真 gap 处 thrash/stall)未复现**。
  - **⚠️ live client 侧记**:Mountains 存档 `walkerWaterClimbLateralGate` snapshot 读 false = 上一 session 持久化 config 覆写(config-persistence 陷阱),非陈旧 jar(源默认 `BotConfig.java:2171/2309` 均 true;runClient 从源重建);本债只测 parkour 门,已显式 `mc.bot.setting` 置 ON(applied 确认)。**后续**:controller 已就地刷新 `fabric/run/config/worlddriver_bot.properties` 两键为 true,结构问题(持久化=全字段快照,压制后续版本默认翻转)立案 tracker task#93(候选修=只持久化显式用户改动或版本化迁移)。live 全程 Xvfb :95,按显式 PID 拆除(client JVM + gradle + Xvfb),port 39801 已释放,零 orphan。
- **文档**:本 TODO 三债收案条目;`stagewright/README.md`(场景 130→131 全处、optional sensor 3→2 = vineCling/vineOverWater,riverSheerBank+entityLeashLowY 描述为 required、seed provider 9→10);`docs/stagewright/migration-log.md` **append task#86 CLOSED 段**(task#87/#91 段已由 T2/T3 落,#86 段缺→补齐 D2 三债对称)。commit `docs(engine): D2 — task#86/#87/#91 closed`。
- **残余**:D2 三债全闭,开放 engine-debt task 清零(task#86/#87/#88/#90/#91/#92 全收);**user 待决**:`artifactId`(`worlddriver-testkit-*` 发布坐标)/ merge / 远端仓库。

## 2026-07-19 ✅ D1 债务修复阶段**收官**(D1-T4 终验 + 文档)→ 三债 task#88/#90/#92 全闭,八门全绿;`wd.agentRpcSmoke` 两拓扑门 + `wd.entityLeash` within(180) liveness 为本阶段仅有的两处受制裁参数改动 — commit chain `fe3e236`/`948a11f`/`11db945`/`d6c00ec`/`919703b`/`a09494b`/`77cd002` + 本 docs 收官(branch `feature/executor-permove-ascend`,详录 `.superpowers/sdd/task-4-report.md`)

- **三债根因一句话 + 修法 + 证据指针**:
  - **task#88 收案(D1-T1,`fe3e236`+`948a11f` 审修+`d6c00ec` 裁决+`919703b` 审修)**:根因=harness 启动 tick 债 catch-up **突发**使 `within()` 墙钟界脆弱(A/B 证既存非回归)。修=**settle 屏障**(在 arm 场景前把启动 tick 债排干,落在 `StageWrightCommon` 转发层——不动 tick-pure `SceneContext`,免打乱 PREP 预算)=突发病的结构门;`wd.entityLeash` within **裁决**:第一次 wild 跑 `TIMEOUT@121` 证伪 120 → **回到 180 作纯 liveness 守卫**(settle 屏障已治根,180 只兜活性,**禁第三次加宽**)。证据 `task-1-report.md`(注:该报告写于 120→180 裁决之前,终态叙事以 `d6c00ec` commit 信息与 `.superpowers/sdd/progress.md` D1 段为准)。
  - **task#90 收案(D1-T2,`11db945`)**:根因=仪表面缺**持键回读**+**世界右键**两 verb → `ContainerFurnaceTest` 开不了炉屏(要世界右键,仅有行为面 `mc.bot.useItem` 可用=模块纪律禁依赖)、`instrument_client` 的 heldKeys 子断言是空。修=**`mc.test.input.heldKeys`**(client 线程持键回读)+**`mc.test.input.useOnBlock`**(合成 `BlockHitResult` 直调 `gameMode`=仪表面非行为面)+ `instrument_client.py` `reset.heldKeys` 真断言 + `ContainerFurnaceTest` 启用 + live `exec()` 形状钉。证据 `task-2-report.md`。
  - **task#92 收案(D1-T3,`a09494b`+`77cd002` 审修)**:根因=`wd.agentRpcSmoke` 的 JS 验证套件按专服 RPC 面编写,**毯式 early-PASS 门**在非专服拓扑掩盖 9 处 client-face 分歧;`ScriptManager` 内联了 `prelude.js` 的**陈旧子集**(缺 `observe.player`+整个 `Driver.bot.*`)。修=套件**拓扑可移植**(两拓扑都满跑,专服 147/集成 259)、毯式门拆除、`ScriptManager` **单源到规范 `prelude.js`**、一处**具名 `SKIP(task#92)`**(42_combat melee,offence 由 `wd.serverCombat*` 确定性覆盖)、prelude tunnel 死守卫修(缺 distance 现拒绝而非静默挖 1 格)。证据 `task-3-report.md` + `migration-log.md` task#92 段。
- **✅ D1-T4 终验矩阵(前台有界顺序,ONE server/client at a time;详录 `.superpowers/sdd/task-4-report.md`)——八门全绿**:
  - ①**dogfood neoforge GREEN**(agentRpcSmoke 147/0 专服,金三件 descentYaw 871°/53·selfShaftDigUp worstBackslide 20.252203415101263·gearScope 0.94000053/5.9040003/1.5999999046325684/6.0 PASS,entityLeash within(180) PASS)+②**dogfood fabric GREEN**(同上)。**基线字节比对**:128 个非 leash/非 rpcSmoke 场景(130−2) `(outcome,reason)` 金字节与 P4-final 基线**逐字恒等**(两 loader);唯二受制裁改动=`wd.entityLeash`(within 180 liveness,tick 数在 box 负载下漂移属预期,PASS)+`wd.agentRpcSmoke`(147-check 新断言,reason 串恒等);约 5 个 await-bounded 场景(threatScan/forbidDigWall/follow/combat)仅 **tick 采样**随外部 box 负载漂移,零 reason/outcome 字节改。3 个 `withRequired(false)` 水传感器不变。
  - ③**instrument neoforge 23/23 GREEN**+④**instrument fabric 23/23 GREEN**(canary mustFail/mustSwallow 门存活)。
  - ⑤**t1.py(集成)GREEN**——`wd.agentRpcSmoke` PASS **812 tick/35s**(证真跑非 early-PASS),passNote "integrated — 259 checks, 0 failures, 1 named task#92 skip",client log `TOTAL 259/PASS 259/FAIL 0`;entityLeash PASS 28t。
  - ⑥**t2.py(生产拓扑:专服+客户端)GREEN**(138s)。
  - ⑦**instrument_client.py --attach GREEN**(8 check + 2 canary;task#90 `reset.heldKeys` 真断言 PASS)。
  - ⑧**`./gradlew :stagewright-junit:test --rerun-tasks` GREEN**(经 `t1.py --hold` 导出 `TESTKIT_ENDPOINT` attach):17 测试全过,**live UI 真跑非跳过**——`ContainerFurnaceTest` PASS 0.38s(task#90 `useOnBlock` 真开炉屏)、`ChatScreenTest`/`InventoryScreenTest`/`CanaryTest` 全 PASS;仅 2 个 `SelfTest` 负路径("no endpoint")在有端点时如期反向跳过。**⚠️坑**:junit test 任务对 env 改动不失效(`UP-TO-DATE`),必须 `--rerun-tasks` 才真跑 live。
- **tunnel-guard 影响面清扫(T3 reviewer watch item)**:全仓库 `.tunnel(` 调用仅 `25_phase_d3.js` 三行(验证脚本本身),其一缺 distance 是**故意**测"拒绝缺 distance";skill 库(`48_skill.js` + 各 run-* skills 目录)与所有 repo 脚本**零** `bot.tunnel` 依赖,无一依赖旧 floor-to-1 行为;instrument 套件(走 script.eval 路径)双 loader GREEN。**结论:prelude tunnel 语义改动无回归面**。
- **文档**:本 TODO 三债收案条目;`stagewright/README.md` 治理面三改(agentRpcSmoke 拓扑门条目**改写为两拓扑满跑 147/259 + 具名 skip 约定**、entityLeash within(180) 注改为 **task#88 settle 屏障收案**、containerFurnace 注从 `@Disabled` 延后改为 **task#90 收案启用**);`migration-log.md` **无需改**(T3 的 task#92 段已完整含 Acceptance 块,append-only 政策下不追加)。commit `docs(testkit): D1 — task#88/#90/#92 closed`。
- **残余**:开放 task **task#86/#87/#91** 不变待修;**user 待决**:`artifactId`(`worlddriver-testkit-*` 发布坐标)/ merge / 远端仓库。

## 2026-07-19 ✅ D1-T3 task#92 收案 → `wd.agentRpcSmoke` 拓扑可移植,毯式 early-PASS 门**拆除**;JS 验证套件在专服(T0)+集成客户端(T1)双拓扑都真跑 REQUIRED — commit `a09494b`(branch `feature/executor-permove-ascend`,详录 `.superpowers/sdd/task-3-report.md` + `docs/stagewright/migration-log.md` task#92 段)

- **取证(T1 `/agent test` 裸 RPC,不重编译)**:`TOTAL 259/PASS 250/FAIL 9`(tracker 记的 8 少一个=42_combat 行为项)。九分歧定性 + 修:
  - **①13 observe.player + ③④⑤25 tunnel×3**(5 硬 JS `TypeError: … of undefined`)=**验证套件 harness prelude 是 `prelude.js` 的陈旧子集**(`ScriptManager` 内联手抄版缺 `Driver.observe.player` + 整个 `Driver.bot.*`);专服路径这些脚本自跳过=漂移长期隐藏,T1 首次真跑 client 分支才炸。修=**改载规范 `prelude.js`(单一真源)**+ 附 harness 专属 extras(rpc/mcpRoundtrip、world.*、stdout console)。
  - **②21 applied**(第 5 个 JS TypeError)=`mc.bot.setting` 空 `applied` 省略(`SettingsCommand:808` 共享码,两拓扑同形)脚本不设防 → `(r.applied||[])`。
  - **⑥⑦40 retreat×2**(行为)=RetreatChain 需真威胁才 bid(gap#65/#68 门),脚本"threshold==maxHP 恒 bid"前提陈旧(live 证:无威胁 prio0,召唤僵尸后 prio100)→ **召唤 NoAI 敌**(仿 41_defense)。
  - **⑧42 melee engage**(行为,不可移植)=`completed` 全清场需平坦净 arena,live 客户端世界+CPU 负载下 pathing/时序耦合(live 证:挥砍 25 击杀 8 但不 complete)→ **具名 task#92 拓扑 skip**(offence 由 dogfood `wd.serverCombat*` 确定性覆盖),计数不删不吞。
  - **⑨57 replay**=断言钉旧 rigid 形;replan 路径(`ReplayTool:104-129`)返 `file/mode/envelopeCells/…` 无 `segments/plannedNodes` → 断当前契约。
  - **⑩(潜伏 driver bug,prelude 修后才显)**:`prelude.js` tunnel 的 `distance required` 守卫是死代码(`Math.max(1,…)` 把缺失 distance 抬到 1 抢在 `if(!dist)` 前)→ 缺 distance 静默挖 1 格而非拒绝。修=先判原始 distance 再 clamp。
- **⭐拓扑总数是 topology-DEPENDENT(实测,非假设)**:老场景只断 `FAIL==0` 从不计数,所谓"专服 259"从未验过=集成取证数错标。真相=~35 个 client-face 脚本在**专服**各自跳到 1 个"no client"占位、在**集成**跑完整真分支 ⇒ 专服 **147** / 集成 **259**(集成⊃专服)。场景改 topology-aware 断言(`RPC_SMOKE_EXPECTED_TOTAL_DEDICATED=147`/`_INTEGRATED=259`)=断各拓扑正确值(裁决规则 c),非毯式跳=套件两拓扑都满跑。**⚠️与 brief 字面"259/259"偏离已在 report 显式标给 controller**(专服真=147;逼 259 需重构 35 脚本 skip 分支=不成比例)。
- **✅验收**:三模块编译过;T0 dogfood **neoforge GREEN**(agentRpcSmoke 147/0 专服,金三件 PASS,entityLeash 31t)+ **fabric GREEN**(147/0,entityLeash 30t);**t1.py GREEN**(133s)——`wd.agentRpcSmoke` PASS **804 tick/35s**(证场景真跑非 early-PASS),passNote "integrated — 259 checks, 0 failures, 1 named task#92 skip",client log `TOTAL 259/PASS 259/FAIL 0`。**毯式门已拆,禁回来**。
- **残余**:instrument.py/instrument_client.py 未跑(仪表面=task#90,本改不触及验证套件,归 D1-T4 #91 验收);开放 task#86/#87/#88/#91 不变。

## 2026-07-19 ✅ P4-final GameTestServer 机器退役**收官** → 六项退役清单全闭、四门绿、campaign 终章封盘;**stagewright 成为唯一测试门** — spec §6.6,branch `feature/executor-permove-ascend`(T1 `9f506d1` 执行删除 + T2 `docs(testkit): P4-final — retirement complete, testkit is the sole test gate` 验收+文档收官)

- **✅ 机器退役六项清单全闭**(①-⑤=T1 `9f506d1`,⑥=T2 本 commit):①`gameTestServer` loom run config 移除(neoforge——fabric 从未有此 run config)✅;②`scripts/run_gametests.sh` + `scripts/gt_reconcile.py` 删除 ✅;③`GameTestManifest.java` + `WorldDriverNeoForge` gametest 钩子移除(`applyGameTestBaseline` 保留=改由 `-Dstagewright.autorun` 服务器启动路径驱动)✅;④`AGENT_GT_ONLY`/`solo*` batch/`run_gametests`/`gt_reconcile` 残留 grep 清理(T2 复审=活面 0)✅;⑤`.gitignore` `run-gametest/` 条目移除 ✅;⑥文档 final sweep(`stagewright/README.md` 退役注 + `docs/stagewright/migration-log.md` 尾部退役注 + `BotConfig.java:2533` javadoc 重写为退役后真相)✅。**neoforge/fabric testmod source set 保留=空源桥接**(删则静默断场景投递)。
- **✅ 四门验收(前台有界顺序,ONE server at a time;详录 `.superpowers/sdd/task-2-report.md`)**:
  - ①**dogfood 双 loader ×1 复验**(与 T1 的 ×1 合计每 loader ×2 一致)✅ **VERDICT GREEN**:neoforge 1:23 / fabric 1:13,两跑 `(name,outcome)` **134 记录同一 md5 `5b0e44f4350f69502ee8253bd24174b3`**(跨 loader neoforge==fabric 恒等);130 `wd.*` + canary/builtin;恰 3 `withRequired(false)` 传感器如期(`wd.vineClingFidelityProbe` optional-PASS / `wd.vineOverWaterClimb` optional-FAIL / `wd.riverSheerBank` optional-FAIL,task#91);`wd.agentRpcSmoke` 专服路径满跑 PASS;**`wd.entityLeash` PASS(P4c within 180 止血持稳,零 flake,未再加宽)**;金值三件(descentYaw 871°/53、selfShaftDigUp `worstBackslide` 20.252203415101263、gearScope bare 0.94000053/sword 5.9040003/ATTACK_SPEED 1.5999999046325684/ATTACK_DAMAGE 6.0)由场景内断言复现(scene PASS)。
  - ②**生产 jar 字节门双 loader + `publishToMavenLocal` 复验** ✅ 双向断言过:禁 `AgentGameTest*`/`WorldDriver*Scenes`/`SimProbes`/`scene.SceneProvider` impl+service 条目 **= 0**;留 `TestResetVerb`/`TestRunVerb` + `StageWrightVerbHook` service 条目 **present**;框架接口 `SceneProvider`+`Scenes` DSL 在 jar 内=合法(retained `TestRunVerb` 依赖)。remapped + 已发布 `~/.m2` jar 同断言全过。
  - ③**树审计** ✅:6 token(`GameTestManifest`/`runGameTestServer`/`run_gametests`/`gt_reconcile`/`AGENT_GT_ONLY`/`batch = "solo`)活面 **0**;run-config token `gameTestServer`(camelCase 机器)**0**;`@GameTest(` 全树 **0**;service 13 行 == 13 provider;双 expect-file 各 **130** 场景。`gametestserver` 大小写不敏感 59 命中全属合法记录(删类 `AgentGameTestServer` 溯源 + vanilla-class javadoc + P4-final 墓碑),历史档案豁免遵守。
  - ④**`./gradlew tasks --all | grep -i gametest`** ✅ 无输出(`runGameTestServer` 计数 0);`runDogfoodServer`/`runStageWrightServer` 任务存活。
- **campaign 终章统计(封盘)**:legacy `@GameTest` **130 方法 → 0**(P4a 130→122 / P4b 122→59 / P4c 59→0);dogfood **130 `wd.*` 场景 / 13 provider / 3 optional-FAIL 传感器 / 1 拓扑门**(agentRpcSmoke);129 迁 1:1 + 1 retired-without-scene(`descentDrift`)+ 1 net-new(`wd.settingRegistryClosed`)。**stagewright = 唯一测试门**;GameTestServer 交付形态彻底退役。
- **残余**:开放 task **task#86/#87/#88/#90/#91/#92** 均不变待修;**user 待决**:`artifactId`(`worlddriver-testkit-*` 发布坐标)/ merge / 远端仓库。

## 2026-07-18 ✅ P4c Server 巨类按 family 分波全迁 → legacy `@GameTest` 套件**清零**(59→0),dogfood 涨到 130 场景;整场 campaign 收官(130 legacy 方法 → 0)— spec §6.3 Server 侧收官,五门绿(branch `feature/executor-permove-ascend`,commit `docs(testkit): P4c — Server families fully migrated, legacy GameTest suite emptied`)

- **✅ P4c 六 commit 已落(四迁移波 + 1 stop-bleed + 本收官)**:①`2844189` plan(P4c 波次);②`2003a64` (wave 6 P4c-T1) **Station 15 迁**(59→44,craft/smelt/recipe/observe,新 provider `WorldDriverStationScenes`;driver 走 `ServerWorldDriver.createIsolated`;furnace/grid 状态机手塞 `containerMenu` #64);③`880cdfb` (wave 7 P4c-T2) **Scheduler 语义矩阵 11 迁**(44→33,#54P1 回归卫士,新 provider `WorldDriverSchedulerScenes`,139 矩阵行逐条一对一);④`c5f754e` (wave 8 P4c-T3) **Survival+Avatar 18 迁**(33→15,`WorldDriverSurvivalScenes` 13 + `WorldDriverAvatarScenes` 5);⑤`bf54138` **`wd.entityLeash` within 120→180**(第二次记录的 stop-bleed,task#88 根修待,A/B 证为既存 harness tick-债 非回归);⑥`e47e873` (wave 9 P4c-T4) **Process 核心 15 迁**(15→0,`WorldDriverProcessScenes`)+ **三类全删**(`AgentGameTestServer`/`AgentGameTestRegistrar`/`AgentGameTestSupport`);⑦本条目 = Task 5 五门验收 + 文档收官。
- **波次结局(记录)**:
  - **underwaterBase port rationale(wave 8)**:惯犯挂死 arena(`ChunkMap.processUnloads` 单 tick livelock)迁移时保留 escape-hatch 优先——直译体不复制持久世界 re-entrant 触发路径,场景体走 bounded await,零 `level.tick()`。
  - **三处 `level.tick()` 雷直译(wave 9)**:`serverForbidDigWall`/`serverFollow`/`serverCombat` 各 `for(3) level.tick(()->true)` 索引雷 → 统一改 wave-5 bounded 同实体可见性 await(`within(100)`,live 7–12 tick 解析),下游 register/tickAll/断言逐字保留;`serverCombat` 的 per-iter `zombie.tick()`(直接实体 tick,非 `level.tick`,无 ChunkMap re-entry)正确保留。
  - **entityLeash stop-bleed #2**:`wd.entityLeash` within 120→180(`bf54138`),与 P1.5b 的第一次 widening 同族;根因 task#88(harness 启动 tick 债 catch-up 突发)未闭,A/B 证既存非本波引入。
  - **campaign close 130→0**:tracked drift 链 130→122(P4a)→59(P4b)→0(P4c);129 迁 1:1 + 1 retired-without-scene(`descentDrift`);"144" = P4a git-mv 的 `@GameTest`-家族注解总数(含 class-level `@GameTestHolder`),方法数为 130——migration-log 收官段已对账两数。
- **✅ 五门验收(前台有界顺序,ONE server/client at a time;详录 `.superpowers/sdd/task-5-report.md`)**:
  - ①**dogfood 双 loader ×2 全绿 + 结果集恒等** ✅:neoforge ×2 + fabric ×2,四跑 `(name,outcome)` **134 记录同一 md5**(`0b0f8db4…`,loader 内 ×2 + 跨 loader neoforge==fabric 全恒等);130 `wd.*` + canary/builtin;3 optional sensor 如期(`wd.vineClingFidelityProbe` optional-PASS,`wd.vineOverWaterClimb` optional-FAIL pocketTicks=29,`wd.riverSheerBank` optional-FAIL wallPressTicks=51);`wd.agentRpcSmoke` 在专服路径满跑 259 检查 PASS。墙钟:neoforge **83.6s / 81.6s**,fabric **73.6s / 79.6s**(涨幅在预算内)。含 dayTime hygiene 改动后重跑 GREEN 恒等。
  - ②**instrument 双 loader 23/23 + t1.py GREEN**（见 report）。
  - ③**生产 jar 字节门双 loader + `publishToMavenLocal` 复验**（见 report）。
  - ④**migration-log 计数链审计** ✅:59→44→33→15→0(P4c)+ 130→122→59→0(整场)与 git 逐位对账;retirement 裁决完整;收官段陈述 campaign total。
  - ⑤**树残余审计** ✅:`@GameTest(` 全树 **0 处**;neoforge testmod source set **空**(无 `.java`);三删类不存在。
- **文档**:`stagewright/README.md` provider 表 8→**13 行**(+Station 15/Scheduler 11/Survival 13/Avatar 5/Process 15)+ 计数 71→**130** 全处;migration-log **campaign close 收官段**(全链 git 对账 + 144/130 两数对账 + retired-without-scene 实核=仅 1 个 `descentDrift` + dayTime hygiene 附录);doc-rot 扫除(`WorldDriverNeoForge.java` 注释改指当前真相 + `GameTestManifest.java` javadoc 重指 migration-log);`wd.serverCombat` dayTime 恢复 hygiene(非行为改动,cleanup 卫生)。
- **残余 → 后继 phase P4-final(GameTestServer 机器退役清单)**:①`gameTestServer` run config 移除(fabric+neoforge);②`scripts/run_gametests.sh` + `scripts/gt_reconcile.py` 退役;③`GameTestManifest` + `WorldDriverNeoForge` gametest 钩子移除;④`AGENT_GT_ONLY` / `solo*` / batch 残留 grep 清理;⑤`.gitignore` run-gametest 条目;⑥文档 final sweep。**开放 task 不变**:task#86/#87/#88/#90/#91/#92。**user 待决**:`artifactId`(`worlddriver-testkit-*`)/ merge / 远端仓库。

- **✅ 终审 + fix wave 收尾**:终审(全 phase diff 2844189..8e99f21,opus)= **Ready to merge,0 Critical/Important**;2 Minor 修毕(`0c674c1`:drownEscapeGateMatrix 行数注 17+10→18+8 与 log 的 26 一致;neoforge/build.gradle testmod 注释改为 wave-9 后真相=run 已空载,仅为 P4-final 保留),2 Minor PARK(expect-file 措辞;144 脚注粒度)。终审横向核验:13 provider==13 service 行==130 场景双 expect-file;恰 3 withRequired(false)+1 passNote 拓扑门全带引证;被删三类零代码引用;全 phase 唯一 within() 变更=三雷 await+entityLeash 止血对(带禁三次加宽注);P4-final 清单可执行。**P4c Ready to merge**(2844189..0c674c1 十 commit);**campaign 收官:legacy 130 方法→0,dogfood 130 场景/13 provider/3 传感器/1 拓扑门**。
## 2026-07-18 ✅ P4b 非 Server 家族全迁(62 场景迁 + 1 retired-without-scene)→ legacy 缩到 Server-only(122→59)+ dogfood 涨到 71 场景 — spec §6.3 非 Server 侧收官,五门绿(③t1 首跑揭出 agentRpcSmoke 跨拓扑缺口 → controller 裁决=最小诚实拓扑守卫 dedicated-only,task#92 立案,t1 重跑 GREEN)(branch `feature/executor-permove-ascend`)

- **✅ P4b 六 commit 已落(四迁移波)**:①`b4a85f6` plan(P4b 波次);②`3eb6d62` (wave 2) **Terrain 12 迁移 + twins 删**(122→110,`AgentGameTestTerrain` 整类删)+③`159f202` (wave 2 close) **`descentDriftArena` controller 裁决 FULL RETIREMENT**(retired-without-scene,110→109;理由:自证 PROVEN FALSE GREEN gap#49 + 忠实迁移物理不可能=>60s 单 tick A* churn 触 `ServerHangWatchdog` 崩 dogfood harness + 降级即破确定性/重设坏 rig 皆禁;descent 覆盖由 `wd.descentYaw` 金签名门续守);④`1d2f748` (wave 3) **Bias 13 迁移**(109→96,全 planner-only,`AgentGameTestBias` 整类删,新 provider `WorldDriverBiasScenes`);⑤`84ffea5` (wave 4) **Water 双家族 21 迁移**(96→75,WaterBank 11 + WaterCross 10,两整类删 + 两 Registrar 行删);⑥`3fc846f` (wave 5) **Core 12 + CombatSense 2 + BuildBlock 2 = 16 迁移**(75→59,三整类删 + 三 Registrar 行删——删后 **Registrar 只剩 `AgentGameTestServer`**);⑦本条目 = Task 5 五门验收 + 文档。
- **计数链审计(与 git history 逐位对账)**:130→**122**(P4a)→**109**(P4b w2,−12 迁 −1 retired)→**96**(w3,−13)→**75**(w4,−21)→**59**(w5,−16)。四 P4b 波共移除 **63** legacy `@GameTest`(62 迁场景 + 1 retired)。dogfood suite = 9 原 + 62 迁 = **71** 场景(plan 估 72,实 71=descentDrift 无场景退役);legacy 存活 **59** = 纯 `AgentGameTestServer`(P4c 切分)。migration-log 加收官段。
- **✅ 五门验收(前台有界顺序,ONE server/client at a time)**:
  - ①**dogfood 双 loader ×2 全绿+结果集恒等** ✅:neoforge ×2 + fabric ×2,四跑 `(name,outcome)` 75 记录**同一 md5**(loader 内 ×2 恒等 + 跨 loader 恒等);71 `wd.*` + 2 builtin + 2 canary(`canaryMustFail`→FAIL/`canaryMustTimeout`→TIMEOUT,`canaryMustSwallow` 正确略过);3 optional sensor 如期(`wd.vineClingFidelityProbe` optional-PASS,`wd.vineOverWaterClimb` optional-FAIL pocketTicks=29,`wd.riverSheerBank` optional-FAIL wallPressTicks=51),9 原 golden 全 PASS。
  - ②**legacy 全量** ✅ **VERDICT GREEN**:`registered=59 entered=59`(0 swallowed / 0 drifted),**全 59 required PASSED**,`gradle_rc=0 reconcile_rc=0`,59 tests 6.228s;存活失败 family 为**空**(agentRpcSmoke 已 wave 5 迁出);`forbiddig` 子串属存活 `serverForbidDigWallArena` 非退役 Bias;**本轮无 livelock**。
  - ③**instrument 双 loader 23/23 ✅**(neoforge GREEN + fabric GREEN);**t1.py 首跑 RED → 拓扑守卫收口后 GREEN ✅**。首跑唯一红 = `wd.agentRpcSmoke`(required)在 T1 integrated-client 拓扑跑 JS 验证套件(259 检查)**8 失败**(TOTAL 259/PASS 251/FAIL 8)。**非 P4b 回归**(迁移字节忠实,同 `WorldDriverCommon.runValidation()` 在 T0 专服 ×4 全 PASS),而是 **agentRpcSmoke 是专服 smoke 套件、从未在客户端拓扑跑过**(legacy 是 GameTestServer @GameTest;P4b 前 t1 只跑 9 场景)——gate③ 首次把它推上 T1 才揭出。8 失败=5 硬 JS 绑定 `TypeError: … of undefined`(`observe…player` / `Driver.bot.tunnel` / `blocks_to_avoid` 客户端面绑定缺失或异形)+ 2 行为(scheduler retreat 通道归属)+ 1 flaky 名额(41_defense 箭矢 / 57_replay 形)。**controller 裁决 = 最小诚实拓扑守卫**(不降 required,保住专服已证保证):场景体 `!ctx.level().getServer().isDedicatedServer()` → 早返回 PASS + 可见 marker(`SceneContext.passNote` 打进结果 JSONL reason + server log,引 task#92 声明"RPC 验证套件专服限定,待拓扑感知 checks");非吞(reconcile 仍计 entered、reason 可见、8 检查分歧证据入 task#92/TODO)。**验证**:t1 重跑 **GREEN**,`wd.agentRpcSmoke` PASS(1 tick,marker 在 t1 results JSONL 可见);dedicated 路径**字节不变**——dogfood neoforge ×1 + fabric ×1 result-set md5 与基线恒等,`wd.agentRpcSmoke` 满跑 156/181 tick reason="" 全 259 PASS。真修(拓扑感知 checks / 签名门锁定 8 已知分歧)= **task#92** scope。
  - ④**生产 jar 字节门双 loader + maven 复验** ✅:`:fabric:build :neoforge:build` **BUILD SUCCESSFUL**,两 remapped 生产 jar + `publishToMavenLocal` 后 `~/.m2` published jar 同断言全过——service 文件 `…scene.SceneProvider` = 0、provider 实现类/`AgentGameTest*`/`SimProbes` = 0、`TestResetVerb`+`TestRunVerb` = 2、`StageWrightVerbHook` service+class present;testkit mod jar scene 实现类亦 0。(注:`net/magicterra/stagewright/scene/SceneProvider.class` **接口**类合法随生产 jar 出货,非 provider 实现,非 service 文件。)
  - ⑤**计数链审计** ✅ 见上;migration-log 收官段 + README 「加场景」单点 how-to 补入。
- **波次结局(记录)**:descentDrift retired-without-scene(controller 裁决);riverSheerBank task#91 发现(gap#48 shared-body 假绿被隔离掀成确定性 RED);agentRpcSmoke scope correction(count-forced 迁移=`AgentGameTestServer` 已恰 59,16 非 Server 必须全出才达 `registered==59`;深 RPC-parity family 覆盖留 Server P4c);entity-visibility 跨壳发现(CombatSense 真专服 chunk force-load 后 ~tick 9 才入 section index,场景 bounded await 轮询)。
- **文档**:`stagewright/README.md` 新增**场景库结构节**(八 provider 分家族 + 计数表 + 3 optional-FAIL sensor 立案说明)+**「如何加场景」单点 how-to**(provider 类入 `.scene` → `Scene.of` 注册 → 新 provider 追 service 行 → 双 expect-file 同 commit → 重名门 + reconcile 门兜底);migration-log **P4b 收官段**(计数链 git 对账 + 71 场景七/八 provider + 3 sensor + 残余);扫清 waves 2-4 遗留 doc-rot(AgentGameTestServer:596 悬空 `{@link WaterCross}` → migration-log;:2136/:4065 stale descentDrift/Terrain 引用注退役;PathFinder:129 `{@code AgentGameTestBias}` → `wd.*`/`WorldDriverBiasScenes`;expect-file wave-4 注释 2 optional → 3 加 riverSheerBank)。
- **✅ 终审 + fix wave 收尾**:终审(全 phase diff b4a85f6..0210b55,opus)= **Ready to merge,0 Critical/Important**;4 Minor 三修一挂(`6c43851`:migration-log 收官段 seven→eight providers、wave-5 补 post-deletion reconcile 段(59/59 GREEN 入审计档对称)、`InputReleaseGate.java` 生产 javadoc 改指 `wd.inputReleaseGate`;dead-return 若干=PARK)。终审横向核验:四波协议一致(Y-map/命名/LIFO/radius 算术)、sensor 集恰 3 处 withRequired(false) 全带引证且 README/expect/代码三方一致、passNote 无滥用面(仅 DONE 路径读,FAIL/TIMEOUT 不读)、计数链密封(Server 恰 59 个 @GameTest)。**P4b Ready to merge**(b4a85f6..6c43851 八 commit)。
- **残余(P4b 后)**:①**P4c = Server 59 拆 family**(含 4 处手动 `level.tick()` 雷:livelock 惯犯 `serverForbidDigWallArena` / `serverCombat*` re-entrant arena = 迁移雷区)→ **P4-final = GameTestServer + `solo*` batch 退役 + `run_gametests.sh` 正门切换**(spec §6.6);②**task#92**:`wd.agentRpcSmoke` 拓扑守卫已落(dedicated-only,marker 可见),真修 = JS 验证套件拓扑感知 checks 或签名门锁定恰好 8 已知分歧(observe…player / Driver.bot.tunnel / blocks_to_avoid 客户端绑定 + scheduler retreat + 1 flaky),使其在 T1/T2 客户端面也能 required 跑绿(注:我的 tracker 建条落成 #79,与已闭 survival task#79 撞号,controller 修号为 task#92);③**task#86/#87/#88/#90/#91** 均不变待修;④**artifactId 命名残余不变**(`worlddriver-testkit-*`)。

## 2026-07-18 ✅ P4a 测试搬出生产 jar 进 testmod source set(三模块)+ 生产 jar 字节门(双 loader)+ 迁一批删一批首删(130→122,legacy required 首次全绿)— spec §6.3 两侧全落(branch `feature/executor-permove-ascend`)

- **✅ P4a 七 commit 已落**:①`e8f123f` plan(P4a 竖切);②`e11294c` (T1) **legacy `@GameTest` 搬出生产 jar 进 neoforge testmod source set**(8 arena 类 + Support 从 `neoforge/src/main` → `src/testmod`,同包不改名;`sourceSets.testmod` classpath extends main output+classpaths;`mods { named('main') { sourceSet testmod } }` 让 FML 扫到迁移的 `@GameTestHolder`;`runGameTestServer` `source sourceSets.testmod`——SimProbes 仍在 common main,委托断裂窗口零);③`d199eb2` (T2) **`wd.*` 场景搬出生产 jar 进 common testmod + neoforge 接线**(`WorldDriverScenes`/`SimProbes` + `SceneProvider` service 从 `common/src/main` → `src/testmod`,**迁进 `.scene` 子包**避 split-package;neoforge testmod compile classpath += `:common` testmod output,RUNTIME 经 loom `mods{ named('main'){ sourceSet project(':common').testmod } }` modFolders 组交付=按 run scope);④`3403612` (T3) **fabric 接线**(fabric testmod 源集**空源**仅作 run `source` 载体;fabric-loom `named('main')` 在 script body 时未建→改 `maybeCreate('main')` 显式复刻 loom 默认;`:common` testmod 直接上 fabric testmod `runtimeClasspath`=per-run scope,机制与 neoforge 反向但同效;全 armor 矩阵全绿);⑤`1f30322` (T4) **迁一批删一批首删**(8 已迁 `wd.*` twins 退场,130→122,migration-log drift 记录);⑥`eea572f` docs backfill T4 deletion SHA;⑦本条目 = Task 5 四门验收 + 文档。
- **✅ 四门验收(前台有界顺序,绝不同时两服务器/客户端;四门一次全绿,零 flake 重跑)**:
  - ①**生产 jar 字节门双 loader** `./gradlew :fabric:build :neoforge:build` **BUILD SUCCESSFUL**——`unzip -l` 两 remapped 生产 jar:**ABSENT 集全 0**(`AgentGameTest`/`WorldDriverScenes`/`SimProbes`/`services/…scene.SceneProvider` 各 0 条,双 loader)+ **MUST-CONTAIN 集全 1**(`TestResetVerb`/`TestRunVerb` class + `META-INF/services/net.magicterra.stagewright.StageWrightVerbHook` 各 1,双 loader)。字节门**双向**:同时反向断言 P3a 有意留 main 的生产 verb 未被误删。
  - ②**legacy 全量** `scripts/run_gametests.sh` **VERDICT: GREEN exit=0**——`registered=122 entered=122`(**0 swallowed / 0 drifted**,#85 P0 门武装),**全 122 required PASSED**,单 **optional `vineoverwaterclimbarena`** 失败(−711 live bug,permitted survivor 集内);8 个删除 twin 名(`serveravatargearscopeprobearena`/`descentyawarena`/`selfshaftdiguparena`/`entityleashrepatharena`/`buriedore`/`ascend*`/`diagonal*`)在 log/manifest **零出现**;122 tests 21.78s / build 46s;**本轮未撞** `ChunkMap.processUnloads` livelock(clean 首启)。与 migration-log 逐位一致。
  - ③**testkit 矩阵**:t0 dogfood **neoforge GREEN 44s**(9/9 `wd.*` + 三金丝雀,`wd.buriedOre` 10.4s / `wd.entityLeash` 104 ticks)+ **fabric GREEN 37s**(9/9,`wd.entityLeash` 32 ticks);`instrument.py --loader neoforge` **23/23 GREEN 47s** + `--loader fabric` **23/23 GREEN 43s**;`t1.py`(fabric 真客户端)**VERDICT: GREEN 65s**(9/9 integrated server 上全 PASS,`wd.entityLeash` **31 ticks 首跑即绿,task#88 未犯**)。金字节由场景内断言保证——PASS 即命中(descentYaw/selfShaftDigUp/gearScope reason 空)。
  - ④**发布复验** `./gradlew publishToMavenLocal` **BUILD SUCCESSFUL exit=0 6s**——`~/.m2` 里 **published** 生产 mod jar(`worlddriver-fabric`/`worlddriver-neoforge`)`unzip -l` 同断言:ABSENT 集全 0 + MUST-CONTAIN 集全 1(**maven 面 = build 面同证**);stagewright mod jar(`worlddriver-stagewright-fabric`/`-neoforge`)ABSENT 集亦全 0。
- **🏁 T4 里程碑**:删除前 legacy required 稳定核失败 5 名(`serveravatargearscopeprobearena`/`descentyawarena`/`selfshaftdiguparena`/`entityleashrepatharena`/`buriedore*`)**恰是本波删除的 5 个已迁 twin**——退场后 **legacy required 套件首次全绿**(122/122 required PASS,唯一残余 = optional vine live bug)。
- **⚖️ legacy 门公式重构(诚实记录,"5 名确定性失败"前提被 T1 A/B 证伪)**:旧台账把 legacy 失败集定性为"5 名确定性彩票家族揭露";T1/migration-log 的 A/B 证据显示这 5 名**本就是已迁 twin 的 legacy 影子**(场景侧字节绿=非回归),删掉即消失。故 P4a 起 legacy 门公式 = **`registered==entered ∧ 0 swallowed ∧ failures ⊆ 文档化 family`**(family 删除后 = `⊆ { vineoverwaterclimbarena(optional), deepwatercross*, agentrpcsmoke }`),任一 family 外新名或删除名复现 = STOP/BLOCKED。reconciliation(零吞零漂)是门②真正的 P0 完整性检查。
- **残余(照旧未清)**:①**P4b+ family 迁移波**(每波=迁一批→A/B→删一批,直接落 testmod):Terrain 13 / Bias 13 / WaterBank 11 / WaterCross 10 / 主类 12 / CombatSense 2 / BuildBlock 2 / Server 59 remaining = 合计 122(与门② `registered=122` 对账;按 wave-1 删 8 后实数逐类重点,plan 文档中的波次地图是执行前估算)(波次地图见 `docs/superpowers/plans/2026-07-18-stagewright-p4a-testmod-destination.md`);②**GameTestServer + `solo*` batch 机制退役 + run_gametests.sh 正门切换 = P4-final**(全量迁完后,spec §6.6);③**task#86**(`selfShaftDigUp` gap#53,`wd.selfShaftDigUp` 场景为 required 签名门,`worstBackslide` 金值 `20.252203415101263`)/ **task#87**(`entityLeashRepathArena` y≈−60 rig)/ **task#88**(`wd.entityLeash` harness tick-debt 根修)/ **task#90**(仪表面双 verb:持键回读 + world-use)均不变待修;④**artifactId 命名残余不变**(stagewright mod jar 发布坐标 `worlddriver-testkit-*` 非 `mc_testkit-*`,root `subprojects{}` 发布块 eager `.get()` 早解析;`mc_stagewright-junit` 独立 publication 不受影响;择期由 user 决)。
- **文档**:`stagewright/README.md` testmod 节从「惯例 v1(插件 flag)」升级——新增 **"Realized wiring: worlddriver's own testmod migration (P4a)"** 节(三模块 testmod 源集 / `.scene` 子包 split-package JPMS 教训 / loom `named('main')` vs `maybeCreate('main')` loader 机制差异 / fabric per-run runtimeClasspath vs neoforge 全局 modFolders scope / 生产 jar 字节门升为常驻验收惯例 + 双向断言 + maven 复验)+ 交叉引 `docs/stagewright/migration-log.md` drift log。
- **✅ 终审 + fix wave 收尾**:终审(全 phase diff e8f123f..36b8daf,opus)= 1 Important=README 与 orchestration-contract 两处权威 service-file 示例仍印 pre-`.scene` FQN+旧 `src/main` 路径(照抄=ServiceLoader 找不到类→provider 静默零场景→自洽假绿,正是 #85 吞形态)→ `ffc31d7` 修正(FQN+路径+split-package 理由入契约文档,grep 证 active 文档零残留);其余 6 组 deferred Minor 全 PARK;生产源码→testmod 引用审计 clean(含反射/字符串);四处跨 loader 不对称记录一致。**P4a Ready to merge**(e8f123f..ffc31d7 九 commit)。Minor 挂账:「如何加场景」缺单点 how-to(可推导,P4b 顺手补)。

## 2026-07-18 ✅ P3b gradle-plugin(三任务 shell 编排契约)+ 客户端进程池 + testmod source-set 惯例(v1 有界)+ maven 发布准备 — spec §7 P3 余三件全落(branch `feature/executor-permove-ascend`)

- **✅ P3b 六 commit 已落**:①`a086c4d` plan(P3b 竖切);②`d6d54c0` (T1) **gradle-plugin** `net.magicterra.stagewright`——`stagewrightServer`/`stagewrightClient`/`stagewrightE2E` 三任务 shell 冻结 python 编排契约(t0/t1/t2.py),**逐字节透传退出码**(不解析 JSONL 不复判,orchestrator 唯一裁决权),13 个 TestKit functional 自测;③`4ccd5db`+`4bc4f01` (T2) **客户端进程池** `pool.py ensure/status/stop`——跨 invocation 保活 `--hold` 拓扑(endpoint+liveness 探活复用,DETACHED 启动,flock 守 state,SIGINT-by-PID 释放,拒杀非自启 orphan),41 自测;④`2a44b9e` (T3) **testmod source-set 惯例**——插件 opt-in 注册 `testmod` 源集(仅 classpath 接线,**loom run 自动接线明示留 v2**,worlddriver 自身 130 legacy `@GameTest` 迁移挂账照旧);⑤`e1266bd` (T4) **maven 发布准备**——`mc_stagewright-junit` publication(纯 JVM,POM 声明 gson/junit-jupiter-api)+ 六 mod-jar POM 清洗(JiJ 防双拷)+ 支持矩阵/兼容承诺入 README。本条目 = Task 5 五门验收 + 文档。
- **✅ 五门验收(全前台有界,顺序跑,绝不同时两服务器/客户端;五门一次全绿,零 flake 重跑)**:
  - ①**插件路径 neoforge dogfood**:`./gradlew stagewrightServer -Pstagewright.loader=neoforge` **exit=0 GREEN 47s**——插件 exec `t0.py --loader neoforge --run-task :neoforge:runDogfoodServer`,**9/9 `wd.*` PASS**(六字节金值由场景内断言保证,PASS 即命中),`wd.entityLeash` 74 ticks,VERDICT GREEN。
  - ②**插件路径 fabric T1**:`./gradlew stagewrightClient` **exit=0 GREEN 64s**——插件 exec `t1.py --loader fabric`,自管 Xvfb `:101`,真客户端 title→singleplayer→world,**9/9 `wd.*` PASS**(`wd.entityLeash` 32 ticks,task#88 未犯),session 47.8s。
  - ③**pool 流程**:`pool.py ensure --topology t2` 冷启 **52s**(`started`,detached hold pid=2278781,resident server pid=2279570)→ `instrument_client.py --topology t2 --attach` **GREEN exit=0 1s**(7 check + 2 canary PASS,双 socket client rpc=40047/server rpc=39345)→ 第二发 `pool.py ensure` **`reused` exit=0 1s**(秒级复用,vs 52s 冷启)→ `pool.py stop` **clean 16s**(SIGINT-by-PID,endpoint 消失,state 0 entries,端口 39801/25597 全释放)。
  - ④**发布复验**:`./gradlew publishToMavenLocal` **BUILD SUCCESSFUL exit=0**——七件全发(`mc_stagewright-junit-0.1.0+1.21.1` .jar/.pom/.module 落 `~/.m2`);消费者冒烟 `rm -rf build && ./gradlew compileJava` **exit=0**——`smoke.Smoke` import `net.magicterra.stagewright.junit.StageWright` 从 mavenLocal 解析+编译,`Smoke.class` 产出。
  - ⑤**自测全绿**:插件 `functionalTest` **13/13**(强制 `--rerun-tasks`,`StageWrightPluginFunctionalTest` 8 + `StageWrightTestmodSourceSetFunctionalTest` 5,0 failures)+ `pool.py --self-test` **41/41** + `instrument_client.py --self-test` **42/42** + `t1.py --self-test` PASS + `t2.py --self-test` PASS。
- **⚖️ 门① loader-override 接线(诚实记录)**:插件唯一 loader 覆盖机制是 `stagewright { loader }` extension(**无内建 `-P` 绑定**),而 root dogfood 消费者原本**无 `stagewright {}` 块**——故 brief 文档命令 `stagewrightServer -Pstagewright.loader=neoforge` 原会静默跑 fabric(**错 loader 假绿**)。修 = root `build.gradle` 加一行 `stagewright { loader = (findProperty('stagewright.loader') ?: 'fabric') }` 桥,把 `stagewright.loader` 工程属性穿到 extension——文档命令真选 neoforge,fabric 仍为默认(门②不变)。这是 dogfood 消费者接线(非阈值调绿),已入本 docs commit。
- **残余(照旧未清)**:①**插件 v2 = loom run 自动接线**(testmod source-set 目前仅 classpath 接线,注册的 `SourceSet` 经 `testkit.testmodSourceSetRef` 暴露供消费者自接 loom run config,插件不碰 loom——loom run-config API 跨版本差异大,自动接线留 v2);②**真远端仓库 = user 决策**(T4 只落 mavenLocal,发到真 Maven 远端由 user 定夺);③**worlddriver 自身 testmod 拆分挂账照旧**(130 legacy `@GameTest` 仍居 `main`,违惯例,迁 `testmod` 源集是独立挂账任务,非 P3b 目标);④**task#86**(`selfShaftDigUp` gap#53)/ **task#87**(`entityLeashRepathArena` y=-60 rig)/ **task#88**(`wd.entityLeash` harness tick-debt 根修)/ **task#90**(仪表面双 verb:持键回读 + world-use 输入)均不变待修;⑤**JUnit 并行 fork 映射仍开放**(单 `--hold` 拓扑串行租约,多 fork 需拓扑池,未做)。
- **⭐残余(Task 4 发现,已入 README maven 节):artifactId 前后不一致**——stagewright mod jar 发布为 `worlddriver-testkit-*` 而**非** `mc_testkit-*`:root `build.gradle` `subprojects{}` 发布块 `artifactId = base.archivesName.get()` 用 eager `.get()`,在子脚本 `base.archivesName='mc_testkit-*'` 覆盖**之前**就解析(取 allprojects 的 `archives_name-project.name`=`worlddriver-testkit-*`);jar **文件名**在盘上确是 `mc_testkit-*`,但**发布坐标**是 `worlddriver-testkit-*`。README artifact 表记的是真坐标(消费者按表依赖,勿用 jar 文件名)。修它会改已发布坐标——今仅 mavenLocal,择期由 user 决(可修 eager/lazy 或改子模块命名意图)。`mc_stagewright-junit` 不受影响(独立 publication,坐标 `mc_stagewright-junit`)。
- **文档**:`stagewright/README.md` 新增 **"Gradle plugin: task entry points (P3b T1)"** 节(三任务表 + loader 桥 + 退出码透传)+ **"Client process pool (`pool.py`) — P3b T2"** 节(ensure/status/stop + endpoint 复用 + SIGINT-by-PID 拒杀 orphan);四 P3b 节(plugin tasks / testmod convention / pool / maven)互链 + 交叉引 T0/T1/T2(插件 shell t0/t1/t2、pool 喂 attach、publish 喂 JUnit 消费者)。
- **✅ 终审 + fix wave 收尾**:终审(全 phase diff a086c4d..801f88e,opus)= 1 Important=pool 附录工作流 B 的 gradle 路径笔误 `:stagewright:junit:test`(项目实名 `:stagewright-junit`,README 三处全对,仅 orchestration-contract-v0.md:405 一处错)→ `f59a4d9` 修正;全部 6 条 deferred Minor 三裁 PARK(理由入 SDD 台账);pool.py 杀进程路径安全审计 clean(仅碰自启 PID,orphan 拒杀无洞)。**P3b Ready to merge**(a086c4d..f59a4d9 七 commit)。

## 2026-07-17 ✅ P3a T2 生产拓扑(专服+真客户端)+ `mc.test.run` 按需触发 + 双 socket 仪表 + 常驻服务器复用 — 产品闭环:testkit 自消费 P2a `registerVerb` SPI(branch `feature/executor-permove-ascend`)

- **✅ P3a 六 commit 已落**:①`573657b` plan(P3a 竖切);②`a7200ad` (T1) **`mc.test.run` 按需触发 verb**——`registerVerb` SPI **首个 testkit 消费者**(产品自消费),幂等(in-flight 闩拒重入),autorun 路径原封不动;③`66ac243` (T1 硬化) **无条件 testkit forwarding** + on-demand latch/stop 加固;④`7539a4e` (T2) **t2.py 双进程拓扑壳**——专服 `runT2Server` + 真客户端,multiplayer 直连驱动(title→Multiplayer→Direct→127.0.0.1:port)+ 双端探针;⑤`ed5887d` (T3) **T2 场景执行 over `mc.test.run`** + `dedicated_plus_client` attach 端点(`serverRpcPort` 可选键);⑥`13483da` (T4) **T2 仪表契约双 socket**——#41/#45/#55 永久断言落**专服 PlayerList** 面 + 复用轮常驻服务器。本条目 = Task 5 六门验收 + 文档。
- **✅ 四能力交付**:①**`mc.test.run` SPI 自消费**(registerVerb 首个 testkit 消费者=产品闭环证明,按需/幂等,autorun 门不动);②**T2 双进程拓扑**(专服 JVM + 真客户端 JVM,双 worlddriver RPC socket,生产同构=`SurvivalTest` 形状,P1b headless 缺口在**真生产拓扑**收口而非模拟);③**T2 场景执行字节相等**(生产拓扑上 `mc.test.run` 触发同套 `wd.*` 场景,golden 与 T0/T1 字节相等 ×2 loader);④**双 socket 仪表 + 常驻服务器复用**(`instrument_client --topology t2 --attach --rounds N` 断连→重连**同一常驻专服**,服务器**永不重启**,per-round PID 断言不变=P3b 客户端进程池雏形)。
- **✅ 六门验收(全前台有界,顺序跑,绝不同时两服务器/客户端;六门一次全绿,零 flake 重跑)**:
  - ①`t2.py`(fabric 场景门)**exit=0 GREEN 87s**:9/9 `wd.*` PASS,scenes PASS ⇒ golden 字节命中(descentYaw/selfShaftDigUp/gearScope reason 空=命中);双端探针 PASS,registered scenes=14。
  - ②`t2.py --loader neoforge` **exit=0 GREEN 91s**:9/9 `wd.*` PASS,双端探针 PASS(模板已在,无需 mint)。
  - ③`instrument_client --topology t2 --attach` **exit=0 GREEN**(7 check + 2 canary,resident server pid=2163677);`--rounds 3` **GREEN**,三轮 per-check 完全一致,resident-server PID `[2163677,2163677,2163677]` 不变(RESIDENT,服务器未重启)。
  - ④同一 `--hold` 拓扑上 `:stagewright-junit:test --rerun-tasks` **BUILD SUCCESSFUL exit=0**:live UI **5 pass**(chatScreenType/inventoryOpenClose/screenTreeSlots + canary mustFail/mustTimeout)+ **1 `@Disabled` skip**(containerFurnace,task#90)+ `SelfTest` **11 total = 9 pass + 2 gated-skip**(env-on 时 2 fail-fast 自测按门跳过),failures=0 errors=0;随后 SIGINT t2.py PID **端点消失 + 双 JVM 全下 + 零孤儿**验证通过。
  - ⑤全 armor(`mc.test.run` 改动的全套回归):neoforge dogfood **GREEN 37s**(六字节金值全 PASS reason 空)+ fabric dogfood **GREEN 38s** + `instrument.py --loader neoforge` **23/23 GREEN 43s** + `--loader fabric` **23/23 GREEN 39s** + `t1.py` fabric **GREEN 65s**。
  - ⑥`instrument_client`(t1 默认自启)**exit=0 GREEN 44s**:t1 面零回归(cold boot 27.4s + 全 check PASS)。
- **⚖️ loader-forwarding 裁决记录**:测试壳的 forwarding 改为**无条件**——生产 `runServer` 现在也布防 `mc.test.*` verb(不再只在 testkit run 配置里)。定性 = **testkit-wiring 重分类**,非行为变更:RPC 面本就是第一方能力面(见 memory `feedback_rhino_scripts_unrestricted`/`feedback_worlddriver_positioning`),`mc.test.*` 在任意专服可达是 **trust-model-consistent**,已记录。**主动后果须知**（终审 Important,如实记录）:在存活生产服上调 `mc.test.run` 不只是被动布防——套件会真跑（网格 tp/`/damage`/summon）且结束时 `StageWrightHarness.finish()` 调 `server.halt(false)` **关停该服务器**（在线玩家全被踢）。能触达 RPC 面即拥有该进程（信任模型同 Rhino 直令）,但运维侧要知道这个 verb 的终态是 halt。dogfood 双 loader 六字节门(gate⑤)证 autorun 路径 footer 照发未受影响。
- **📌 GameTestServer legacy 套件揭露(非 P3a 门,已定性)**:legacy `@GameTest` 套件在本 HEAD 的状态已在台账 P3a-2 条目定性——**5 个确定性失败 = 已归档的彩票家族揭露**(非新回归),不作为 P3a 门跑。5 名确定性集指针见台账。
- **残余(→ P3b)**:①**gradle-plugin**(把 testkit 打成可发布插件);②**客户端进程池**(T2 常驻服务器复用是雏形,进程池化跨轮复用客户端 JVM);③**maven 发布准备**;task#86/#87/#88/#90 照旧未动;**JUnit 并行 fork 映射仍开放**(单 `--hold` 拓扑串行租约,多 fork 需拓扑池,未做)。轻量残余:`instrument_client` 的 `run_checks` header 硬编码 loader `"fabric"`=纯装饰(实际 loader 从端点读,不影响判定);push-socket 残留容忍度注记照旧。
- **✅ 终审 + fix wave 收尾**:终审(全 phase diff 573657b..5236cf1)= **Ready to merge,0 Critical**;1 Important=`mc.test.run` 在存活生产服的**主动后果**(真跑套件+`server.halt(false)` 关服)须显式入档→已并入上方裁决记录;`35c9478` fix wave——`t2.py --hold` 与 scored 清单解耦、`run_checks` header loader 从拓扑穿透(端点为源)、PID 文案收敛为"证据非判决"。拒修=StageWrightHarness ctor 双写(纯装饰 Java 改动要走全 armor,不值)。新残余(fail-safe):`StageWrightCommon` armed 静态态在同一 client JVM 跨多世界会 stale(`!isRunning` guard 大声拒,永不静默错;若要多世界支持则 SERVER_STOPPING 重置)。

## 2026-07-17 ✅ P2c JUnit5 attach + 首批 in-game UI 场景 + neoforge 客户端对等 — out-of-process JUnit 模块经 `TESTKIT_ENDPOINT` attach 活拓扑跑客户端 UI 断言,偏差(1) 收口(branch `feature/executor-permove-ascend`)

- **✅ P2c 五 commit 已落**:①`0a8af32` plan(P2c 竖切);②`14c17a5` (T1) **TESTKIT_ENDPOINT 端点契约**——`t1.py --hold` 原子写 schema-v1 描述符(`.tmp`→`os.replace`)+ `orchestration-contract-v0.md` attach 附录;③`869e4a9` (T2) **stagewright-junit 模块**(纯 JVM,无游戏 classpath)——`Endpoint.parse`(缺键大声抛)/`StageWright.attach` fail-fast(带原文 hint `python3 scripts/stagewright/t1.py --hold`)/裸 RPC 客户端 `StageWrightRpc`/`StageWright` 类型化仪表门面/`StageWrightExtension` 单例串行租约,`SelfTest` 9 纯 JVM 自测;④`d448ead` (T3) **首批 in-game UI 场景 over attach**——`ui.inventoryOpenClose`/`ui.screenTreeSlots`/`ui.chatScreenType` + 双金丝雀(`canary.mustFail`=assertThrows 真断言 / `canary.mustTimeout`=`StageWrightTimeoutException` 类型区分),`ui.containerFurnace` 可见 `@Disabled`(裁决延后 task#90);⑤`b04cb77`+`fb55c31` (T4) **neoforge 客户端对等**——`t1.py --loader`,neoforge 客户端 integrated server 上跑同批 T1 场景 golden 字节相等,偏差(1) 收口。本条目 = Task 5 五门验收 + 文档。
- **✅ 四能力交付**:①**端点契约**(`TESTKIT_ENDPOINT` → schema-v1 描述符文件,给独立 JVM 发现活拓扑,不硬编码端口/世界名);②**stagewright-junit 模块**(纯 JVM JUnit5 attach——UI 场景体跑在测试线程,可跨线程 `await` 异步客户端屏,承接 P2b 偏差 2 的首批 in-game UI 场景);③**首批 UI 场景 over attach**(inventory/chat 屏 live 断言 + 双哨兵金丝雀);④**neoforge 客户端对等**(`--loader` 泛化,同 JUnit 模块零改 attach 两 loader,收口 P2b 偏差 1)。
- **✅ 五门验收(全前台有界,顺序跑,绝不同时两服务器/客户端)**:
  - ①`:stagewright-junit:test` **无 env** → **BUILD SUCCESSFUL exit=0**:`SelfTest` **9 pass 0 skip**(7 恒跑 + 2 fail-fast 在 env 缺席时跑 PASS)+ 6 live UI 全 **skip**(`@EnabledIfEnvironmentVariable` 关);
  - ②`t1.py --hold`(fabric,port 34875 endpoint 文件出现于 ~poll)+ `TESTKIT_ENDPOINT=<abs> :stagewright-junit:test --rerun-tasks` → **BUILD SUCCESSFUL exit=0**(4s):`SelfTest` **7 pass + 2 fail-fast skip**;`ui.*` **5 live pass**(`mustFail`/`mustTimeout`/`chatScreenType`/`inventoryOpenClose`/`screenTreeSlots`)+ `containerFurnace` **skip@Disabled**;SIGINT `t1.py` PID → launcher/client/endpoint 文件三者 GONE(KeyboardInterrupt=文档化释放路径),零 orphan;
  - ③`t1.py`(fabric scored)→ **VERDICT: GREEN exit=0**(46.9s session/62s wall):**9/9 `wd.*`** 全 PASS + 3 金丝雀按预期(`descentYaw`/`selfShaftDigUp`/`gearScope` golden 字节由场景内断言保证——PASS 即字节命中);
  - ④`t1.py --loader neoforge` → **VERDICT: GREEN exit=0**(49.7s/65s):**9/9 `wd.*`** 全 PASS,同 golden 字节,`wd.entityLeash` **31 ticks 首跑即绿**(task#88 未犯,零复跑);
  - ⑤`instrument_client.py --rounds 3` → **REUSE VERDICT: GREEN exit=0**(52s):三轮 per-check outcomes 逐位同一(cold boot 27.5s / round1 1.5s / round2 4.1s / round3 3.6s ≈7× 更省);
  - **五门全 GREEN 一次过,零 flake 复跑**(task#88 entityLeash 签名本轮门④未犯)。
- **⭐偏差延续 → task#90(`ui.containerFurnace` 延后)**:偏差从 P2b「UI 场景归 P2c」演进为「world-use 输入维缺仪表 verb」——打开方块实体容器屏需**世界右键**,仪表面无此 verb(`mc.client.input.click` 只点已开屏 widget、`mc.client.input.key` 只走键盘绑定、唯一世界右键的 `mc.bot.useItem` 是禁依赖的行为面)。**inventory/chat 屏已覆盖**(键盘可开),缺的只是世界右键;真修 = instrument 级 world-use verb,归 **task#90** 双 verb(与持键回读 verb 同批),controller 择期。可见 `@Disabled`(非静默缩编),证据见 `.superpowers/sdd/task-3-report.md`。
- **残余清单(排期以后)**:①**task#90 双 verb**——持键回读(`reset.behavior` 的 `keys` 子断言仍间接,抓不到 `releaseKeys()` no-op 回归)+ world-use 输入(承接 `ui.containerFurnace`);②`StageWrightExtension` 的共享 `StageWright` 单例**从不关闭底层 `HttpClient`**(JVM 退出即回收,进程内无泄漏影响,但非显式 close);③**attach worst-case ~10s 窗口**(5s connect + 5s 探活)已在 README「JUnit 5 attach」节 Attach latency budget 段文档化(冷 boot ~28-30s 在 attach 之前、由 `--hold` 承担,亦已注明);④**JUnit 并行 fork 映射仍开放**(spec §11——本 phase 落串行租约单例,并行分片映射未做);⑤**task#88**(`wd.entityLeash` harness tick-debt 根修:arming 前排空 tick 债 / 改 wall-clock within)**仍开**;⑥task#86(`selfShaftDigUp` gap#53)/ task#87(`entityLeashRepathArena` y=-60 rig)**均不变**。
- **✅ 终审 + 收尾(本条目后两 commit)**:终审(全 phase diff 0a8af32..fc4646d)= **Ready to merge,0 Critical/Important**——零 common-Java 全 diff 核实、端点 schema python/Java 缝逐字节一致(reqInt/reqLong 分型含 epoch-ms long)、对称 env 门经 reviewer 复跑证实无双 skip 吞洞、loom 剖出点唯一;`1422fc5` polish——`StageWrightRpc.close()` 补 `httpClient.close()`(残余②已修)+ `awaitCondition` 轮间 deadline 超越语义入 javadoc;残余①升级为 **task#90 三件套**(+终审新发现:`StageWright.exec()` 断 `ok` 字段但无任何 live 测试驱动过 = latent 契约,落 verb 后首个 live exec 须 pin 回复形状)。

## 2026-07-17 ✅ P2b T1 客户端拓扑 + 客户端仪表契约 + 复用完备性 + task#89 收口落地 — fabric 客户端 integrated server 上跑 `wd.*`,#41/#45/#55 真 PlayerList 断言(branch `feature/executor-permove-ascend`)

- **✅ P2b 五 commit 已落**:①`5c2ba7d` plan(P2b 竖切);②`e857999`+`63b91be` (T1) **T1 拓扑**——`:fabric:runStageWrightClient` run config(客户端 JVM,`-Dstagewright.autorun`)+ `t1.py` 编排(自管 Xvfb 探测空 DISPLAY/PID track/PID kill、模板世界 copy→进→跑→删、`guidrive.py` label 匹配 widget 点击 title→singleplayer→world、world 进入起 integrated server 武装 harness、`verdict.py` 复用判决)+ mint 阶段(`-Pt1Autorun=false` 纯净无场景模板,archive 取于 clean quit-to-title 后)——**9/9 `wd.*` 在 integrated server 上 GREEN,全指标与 T0 dedicated golden 字节相等**;③`87cb869` (T2) `instrument_client.py` 客户端仪表契约(#41 全 36 槽 / #45 攻击冷却 / #55 伤害源——需 PlayerList 里真玩家,dedicated FakePlayer 够不到 + #280 未知键 live E2E + `mc.test.reset` reset 行为,7 检查 + 2 金丝雀);④`a4d5719` (T3) 复用完备性——`--rounds N`(quit-to-title→re-enter→`mc.test.reset`,3 轮字节同一)+ `--fresh-process` 降级(丢弃重启)+ 退出码 **4=BLOCKED**(轮间 verdict 漂移);⑤`4bc3ada` (T4) **task#89 收口**——validator(`requireSchemasFor`+`setParamsValidator`)现装于 catalog 完成后、`RpcServer` ctor 前,单 try 拆 catalog-try / 裸 validator install(在所有 catch 外,fail-fast 保留)/ RpcServer-try(`api!=null` 守卫)——**再无监听 socket 早于 validator 存在的 boot 窗口**。本条目 = Task 5 六门验收 + 文档。
- **✅ 四能力交付**:①**T1 客户端拓扑**(fabric 客户端 Xvfb 下托管 integrated server,`wd.*` 首次在真客户端跑,证明 harness 拓扑从 t0 dedicated 泛化到真 Fabric 客户端);②**客户端仪表契约**(`instrument_client.py`——`instrument.py` 的 T1 对位,需真 PlayerList 玩家的 #41/#45/#55 永久断言 + #280 live E2E + reset 行为);③**复用完备性**(客户端池复用 reset×3 轮完整性,冷 boot ~30s vs reuse round ~4s ≈7-8× 更省 = 证明 `mc.test.reset` 恢复每轮干净态,+ `--fresh-process` 降级 + BLOCKED 漂移门);④**task#89 收口**(validator 前移,boot 窗口无校验派发洞永久堵死)。
- **⭐偏差声明(1)——T1 目前仅 fabric,neoforge 客户端对等延后**:T1 只在 **fabric** 落地——唯一有成熟客户端工装的 loader(knot 客户端 + `into_world`/`react_smoke` title→world GUI 驱动先例 + port 39801 live 先例)。neoforge 客户端对等(在 neoforge 客户端上跑同批 T1 场景 + 客户端仪表)**明确延后**,非本 phase 目标。
- **⭐偏差声明(2)——首批 in-game UI 授权场景随 P2c 再议,理由=场景体跨线程阻塞铁律**:计划中「场景体内直接断言客户端 UI」的首批 in-game UI 场景**延至 P2c/JUnit5**。理由=**场景体跨线程阻塞铁律**:场景体在服务器 tick 上 inline 跑,绝不可阻塞等客户端异步 UI;因此 P2b 的客户端断言**全部经 `instrument_client.py` 仪表面交付**(编排器/裸 RPC 侧),而非 in-game 场景体。JUnit5 attach(可跨线程 await 的测试线程)是承接这批 UI 场景的正确载体,归 P2c。
- **✅ 六门验收(全前台有界,顺序跑,绝不同时两服务器/客户端)**:①`t1.py` → **VERDICT: GREEN exit=0**(9/9 `wd.*` 场景 integrated server 上全 PASS + 3 金丝雀按预期 + 2 reuse 支持场景,`descentYaw`/`selfShaftDigUp`/`gearScope` golden 由场景内断言保证——PASS 即字节命中,48.8s session/64s wall);②`instrument_client.py` 自起 → **GREEN exit=0**(7 检查 PASS + `canary.mustFail`/`canary.mustTimeout` 双落,cold boot 28s);③`instrument_client.py --rounds 3` → **REUSE VERDICT: GREEN exit=0**(三轮字节同一,round1 boot 1.6s / round2 reuse 4.4s / round3 reuse 4.0s,≈7× 更省);④`instrument.py --loader neoforge` + `--loader fabric` → **双 GREEN exit=0**(各 21 检查 + 2 金丝雀,task#89 后回归);⑤dogfood neoforge → **GREEN exit=0**(9 `wd.*`,六字节指标逐位对:`descentYaw` `871°/53`、`selfShaftDigUp` `20.252203415101263`、`gearScope` `0.94000053`/`5.9040003`/`ATTACK_DAMAGE=6.0`/`ATTACK_SPEED=1.5999999046325684`)+ dogfood fabric → **GREEN exit=0**(9 `wd.*`);⑥legacy 全量 `./scripts/run_gametests.sh`。
- **🟡 门⑤ neoforge dogfood 首跑 task#88 flake → 复跑绿(诚实记录)**:首跑 `wd.entityLeash` **TIMEOUT**(await 超 `within=120`)VERDICT RED——**task#88 签名**(冷启动 tick 债 catch-up burst 使 `within(120)` 墙钟脆弱;门① t1.py 同场景 25-tick 通过)。**按协议复跑一次定性**:复跑 `wd.entityLeash` **62 ticks**(120 预算内)VERDICT GREEN exit=0——**flake 非确定性回归**。fabric dogfood 首跑即 26-tick GREEN。task#88 仍开(harness 根修待做)。
- **🟡 门⑥ legacy 全量诚实入档(白名单外零新名,判定为达标)**:`registered=130 entered=130`(**零吞测试**,#85 P0 门武装),`VERDICT: RED` `2 required + 1 optional` 失败:`serveravatargearscopeprobearena`(rig broken: bare-hand swing 零伤 = #48 彩票/战斗 rig 家族)+ `descentyawarena`(未达底,pos 逐位相同)+ optional `vineoverwaterclimbarena`(文档化 live -711 bug)。**复跑一次 = 确定性**(两跑失败名/pos/reason 逐位相同,`descentyawarena` 两跑均落 `pos=(432.76521663827583,-5.0,618.4616959480727)`;非彩票漂移而是确定性 pre-existing 残留)。**归因**:P2b 五 commit **仅动** `WorldDriverCommon.java`(task#89 RpcServer 定序)+ `fabric/build.gradle`(stagewrightClient 配置)+ python/txt/md——**零引擎(walker/executor/combat/arena)源码改动**,故这两 required 失败**在 P2b 变更面之外**,是分支既有引擎残留;且其迁移壳 `wd.descentYaw`/`wd.gearScope` 在门①/⑤ 全 GREEN(golden 逐位一致)——**「Live/replay=真相,arena 只是回归卫士」**:字节钉扎的 testkit 场景是权威回归面,legacy arena 是被取代的脆弱 rig。**白名单外零新名 = 门⑥「诚实入档」达标**(reconciliation 是门⑥真正的 P0 完整性检查)。⚠️ 本轮**未撞** underwaterBase livelock(50s 正常失败,非挂死)。
- **判定:DONE_WITH_CONCERNS**——门①-⑤ 全 GREEN(门⑤ neoforge 经 task#88 flake 复跑绿);门⑥ 是「诚实入档」非 must-green,其真正完整性检查(reconciliation 零吞零新名)干净,2 required 失败经证为 P2b 变更面外的确定性引擎残留 + 迁移壳全绿。**BLOCKED 保留给 P2b 引入的回归或验收面损坏,二者皆不成立**。legacy 删除倒数**本轮不 +1**(仅全量 GREEN 才 +1),当前 = 0。
- **残余清单(deferred Minors 中的真残留风险 + 排 P2c 及以后)**:①**keys-readback verb 缺口**——`reset.behavior` 的 `keys` 子断言间接:`BotApiImpl.resetClientEntry` 无条件加 `keys` token,故该断言抓不到 `releaseKeys()` no-op 回归(真修=持键回读 verb,超范围,controller 可择期排;screen+chat 维度有真牙);②**`canary.mustTimeout` 合成**(抛哨兵,非真墙钟挂死检测,同 `instrument.py` 先例);③**re-enter 失败归 ENV 非 BLOCKED**(漂移门只抓「跑通但翻转」残留,已记);④**task#89 无 fault-injection boot 测试**——失败定序仅由检视验证(`api!=null`-partial-catalog-throw 边缘良性发散,但无注入测试演练失败排序);⑤**task#88**(`wd.entityLeash` harness tick-debt 根修:arming 前排空 tick 债 / 改 wall-clock within——本 phase 只落场景局部 `within(120)` stopgap)**仍开**;⑥**P2c**——JUnit5 attach + 首批 in-game UI 授权场景(偏差 2 承接)+ neoforge 客户端对等(偏差 1 承接);⑦task#86(`selfShaftDigUp` gap#53)/ task#87(`entityLeashRepathArena` y=-60 rig)/ #48 彩票家族全量身体共享隔离修 均不变;⑧legacy `@GameTest` 删除倒数 = 0(本轮 RED 不 +1)。
- **✅ 终审 + fix wave 收尾(本条目后两 commit)**:终审(全 phase diff 5c2ba7d..7675c87)= **Ready to merge,0 Critical/Important**;`664df94` fix wave——③的 re-enter 归类收紧(纯函数 `transition_failure_code`:**干净跑完≥1 轮后**的 reuse 过渡异常判 `BLOCKED(4)` 非 `ENV`,首轮前/`--fresh-process`/check-phase 仍 ENV)+ `--fresh-process --rounds 1` argparse 拒 + `harvest_footer` JSON-parse 去空格耦合 + README 措辞对齐 + 死代码×3;`d6200ae` re-review 收尾——空轮(过渡失败)不再制造幻影漂移表(`(not run)` 占位,漂移只在真跑过的轮之间判,纯过渡 BLOCKED 用专门报告头)。self-tests 23/23 + 13/13;re-review 判 **Confirmed clean**。

## 2026-07-17 ✅ P2a verb 注册管线 + #280 收口落地 — 配对注册 + 命名空间政策 + 封闭 schema 单源 + 契约四门(branch `feature/executor-permove-ascend`)

- **✅ P2a 六 commit 已落**:①`3677225` plan(P2a 竖切);②`14c6541`+`35c98c0` (T1) `ToolCatalog.registerVerb(schema, handler)` 原子配对入口(schema 供给 + route 注册一步),经 bootstrap `wireRouteSink(api::addRoute)` 转接(Hard Rule #1:只 (name,handler) 数据流过缝,不 import 核心)+ 命名空间政策 enforcement(`mc.*` 保留 / `mc.test.*` 授 testkit-runtime / `mc.test.yaml` 祖父 / 第三方 `<modid>.*`,违者注册时抛)+ **schema-less 派发洞收口**(`WorldDriverCommon` 校验接线 `if (s != null)` → 缺 schema 大声 `IllegalStateException`)+ `35c98c0` verb-hijack guard(拒劫持驱动层 baseline 名);③`39d99e5` (T2) **#280 根修**:`SettingsRegistry` 单源(从 `SettingsSnapshot` 键枚举派生——146 手列 + 82 反射补全 `public static volatile` BotConfig 原始类型字段 + `debugFly`,共 229 键),`mc.bot.setting` schema 封闭(`additionalProperties(false)`,229 props),apply 未知键**大声拒 all-or-nothing** + `inert[]` 漂移报告 + 快照字节同一;④`6de3d97`+`ae9982d` (T3) `mc.test.reset` hidden verb(SPI 首消费者,两 loader `ensureRpcUp` 注册)+ client 线程安全 reset(look-cancel + releaseKeys + 关屏 + 清 chat)+ `wd.settingRegistryClosed` 场景(两清单第 9 名)+ 断言 5 = SPI 配对回归网;⑤本条目 = Task 4 契约扩展 + 五门验收 + 文档。
- **✅ 四能力交付**:①**公共配对 verb 注册**(schema+route 原子,派发时缺 schema 大声拒 = #280 同形洞堵死);②**命名空间政策**(注册时 enforce,含祖父条款 + hijack guard);③**#280 收口**(封闭 schema 单源 + 大声拒键);④**`mc.test.reset`** 首个 `mc.test.*` verb(client-entry reset,专服大声 client-only)。
- **⚡ user 直令(P2a 期间,plan 外)**:`15104a3` Rhino 脚本层调用限制默认关——脚本=驱动层第一方能力面,安全由调用方保证(信任模型同 RPC socket:能触达端点即拥有进程);`ScriptClassFilter` denylist 保留,`-Dworlddriver.sandbox=on` 显式 opt-in 才启用;资源护栏(deadline/abort)不属调用限制不受影响。记忆 `feedback_rhino_scripts_unrestricted`;残余:skill 文档/javadoc 的 "sandboxed" 措辞随 P2a 终审 fix wave 清。
- **✅ #280 收口语义(根修一句)**:未知键从「静默吞」变「封闭 schema 在 `route()` 校验 + apply 双保险大声拒,all-or-nothing(任一未知键→整调用拒、什么都不 apply)」,单源 `SettingsRegistry` 的反射补全 pass 保证新 BotConfig 旗标自动进注册表(#280 病根=新旗标漏 schema)。
- **✅ instrument.py 契约四门(检查 18-21,自测数 17→21,金丝雀不动)**:⑱`catalog.settingSchemaClosed`——经 **MCP `tools/list`** HTTP(`worlddriver-mcp.port`,`onServerStarting` 随 RPC 起)读回 `mc.bot.setting` inputSchema,断 `additionalProperties` 非 `true`(封闭)+ `properties` ≥ 200;⑲`route.settingUnknownKey`——专服打 `{definitelyNotAKnob:true}` 得 VALIDATOR `unexpected key`(**非** client-only);⑳`route.testResetClientOnly`——专服打 `mc.test.reset` 得 `client only` 大声;㉑`route.testResetSchemaPaired`——打 `{nope:true}` 得 `unexpected key` 先于 client-only(配对元证明 + schema-less 洞回归)。
- **⭐现场定序发现(检查 19)**:计划把「validator 先于 client-only」当统一契约读法——**现场证实即此序**:`DriverApi.route()` 先 `paramsValidator.validate` 后 `fn.apply`,`mc.bot.setting` 的 `requireBot()` client-only 在 handler 里才抛,故专服未知键先撞 SchemaValidator,client-only 门根本没到(两 loader 全量 GREEN 复现)。**tools/list 封闭对象渲染坑(检查 18)**:`additionalProperties(false)` 内部置 null + `optionalFieldOf` → 封闭对象在 tools/list **省略该键**,不渲染成 `false`;封闭约定 = 缺失或 false = 封闭、仅 `true` = 开放(同 `SchemaValidator`)。均写进契约附录。
- **⭐schema 读回路径**:裸 RPC `/rpc` 不暴露 schema 目录;`mc.script.eval` 也读不到(此 Rhino fork 剥 `Packages` 全局,JS 无法按名解析 `ToolCatalog`,与沙箱 denylist 无关)——唯一诚实结构化读回 = MCP `tools/list`(与 route 层 `SchemaValidator` 同一 typed Schema,单源)。
- **✅ 五门验收(全前台顺序跑)**:①instrument neoforge `VERDICT: GREEN`(21 检查 + 2 金丝雀全过);②instrument fabric `VERDICT: GREEN`(同);③dogfood neoforge `VERDICT: GREEN`(9 `wd.*` 场景全 PASS,六字节指标逐位对:`descentYaw` `sumAbsDyaw=871°`/`backSteps=53`、`selfShaftDigUp` `worstBackslide=20.252203415101263`、`gearScope` bare `0.94000053`/sword `5.9040003`/`ATTACK_DAMAGE=6.0`/`ATTACK_SPEED=1.5999999046325684`);④dogfood fabric `VERDICT: GREEN`(9 `wd.*`);⑤legacy 全量 `./scripts/run_gametests.sh` `registered=130 entered=130`(**零吞测试**,#85 门武装)`VERDICT: RED`(预期彩票)。
- **🟡 legacy 全量门 RED(诚实记录,零新名)**:3 required 失败 = `entityleashrepatharena`(task#87 whitelisted,master-inherited y=-60 rig)+ `serveravatargearscopeprobearena`(#48 彩票家族)+ `descentyawarena`(#48 彩票家族)+ optional `vineoverwaterclimbarena`(文档化 optional)。**分类正证据**:三员的隔离迁移壳 `wd.gearScope`/`wd.descentYaw`/`wd.entityLeash` 在本轮**两 loader dogfood 全 GREEN**(gearScope/descentYaw 字节指标逐位一致),即真隔离态绿——legacy 全量红是 #48 邻居干扰彩票,非回归。**白名单外零新名 = 达标**。删除倒数**本轮不 +1**(仅全量 GREEN 才 +1)。首跑曾中途 livelock 截断(`entered=26` 后 `entityLeashRepathArena` 触 `ChunkMap.processUnloads` 单 tick 死循环,540s wall 截断),per 协议 sweep + 重跑一次自愈,复跑 34.04s 全量干净。
- **残余清单(排 P2b 及以后)**:①**P2b**——T1 拓扑(真 PlayerList 玩家:#41 全背包/#45 攻击冷却/#55 伤害源)+ 客户端仪表 E2E(真开客户端打 `mc.bot.setting` 未知键 over live client、`mc.test.reset` 完整性 3 跑);②**P2c**——JUnit5 attach;③task#86(`selfShaftDigUp` gap#53 stride-floor-guard 真缺陷)不变待修;④task#87(`entityLeashRepathArena` y=-60 rig)不变待查;⑤task#88(`wd.entityLeash` harness tick-debt 根修)不变;⑥彩票家族(#48)全量身体共享隔离修待 test-framework rework;⑦legacy `@GameTest` 删除倒数 = 0(需连续双门绿 + 期望门前置,gate 5 每轮如实计,本轮 RED 不 +1)。

## 2026-07-17 ✅ P1.6 fabric 对齐落地 — sim 核心搬 common + body-factory seam,fabric 首次获得同级 dogfood 门(branch `feature/executor-permove-ascend`)

- **✅ P1.6 六 commit 已落**:①`c1b0a89` plan(fabric 对齐竖切);②`5ab493b` (T1) sim 核心(`ServerPlayerAvatar`/`ServerWorldDriver`/`ServerAvatarManager`)搬 common,置于 loader 注入的 `ServerAvatarBodies` body-factory seam 之后(仓库无 `@ExpectPlatform` 先例→零新依赖;neoforge 留**同 FQN 薄 shim**,协变 `FakePlayer` 返回,工厂仍 `FakePlayerFactory`=字节级不变,legacy `AgentGameTestServer` 约 3000 行**零源码改动**编译通过);③`8623f5e` (T2) `wd.*` 场景 + `SimProbes` 搬 common,`SceneProvider` service 文件换位(**删 neoforge 副本**——dev classpath 两份同内容=provider 双加载=撞名门 RED,该门顺便自证);④`1ffac5b` (T3) fabric 接线(`runDogfoodServer` 配置 + autorun 钩子 + `FabricAvatarBodies` 镜像反编译 `FakePlayerFactory` 含 `ServerWorldEvents.UNLOAD` 驱逐 + `expected-scenes-fabric.txt` 清单)——**fabric 有史以来首次 dogfood 8/8 `wd.*` PASS**;⑤`13f3936` (T4) 双 loader ×3 确定性矩阵(fabric ×3 + neoforge ×3 顺序跑,每跑清 world);⑥`339d2d5` (T4 controller stopgap) `wd.entityLeash` await 上界 `within(60)→(120)`。本条目 = Task 5 验收 + 文档(docs 两文件另 commit,契约 v0 无语义变更不动)。
- **✅ 架构一句话**:sim 三类的 loader 差异用「loader 注入 body 工厂」seam 化解——`ServerAvatarBodies.install(BodyFactory)`(mod init 一次性)提供 `shared(level)`/`unique(level,profile)` 身体;neoforge 注入 `FakePlayerFactory`(行为字节不变),fabric 注入 common 自造的 vanilla-only `AvatarFakePlayer`(以 mcp javadc 反编译 NeoForge `FakePlayer` 对照实现,含 net-handler stub)。场景与 `SimProbes` 也搬 common,两 loader 经**同一份** common `SceneProvider` service 文件注册**同一批**场景。
- **✅ fabric 首次成为一等 dogfood 目标 + 双 loader 字节同一性(P1.6 最强对齐结论)**:Task 3 fabric 首跑 8/8 `wd.*` GREEN;Task 4 用六跑确定性矩阵复对——**每个 `wd.*` 场景指标值在两 loader、六跑之间逐位字节一致**(fabric == neoforge:`descentYaw` `sumAbsDyaw=871°`/`backSteps=53`、`selfShaftDigUp` `worstBackslide=20.252203415101263`、`gearScope` bare `0.94000053`/sword `5.9040003`/`ATTACK_DAMAGE=6.0`/`ATTACK_SPEED=1.5999999046325684`、`buriedOre` `oreMined=true`、`entityLeash` `standDist=4.187857529833143`)。P1a 的字节同一性先例(此前仅覆盖内建场景)现推广到整个 `wd.*` driver/walker 家族——fabric 服务端驱动层(此前从未被测)第一次置于测试之下,且与 neoforge 物理确定性完全一致。
- **⭐entityLeash tick-debt 发现 + stopgap + task#88**:唯一非字节同的量 = `wd.entityLeash` 的**总 scene-tick 计数**(其两次 `ctx.await(...)` 实体索引等待的 tick 和,每 scene tick poll 一次):六跑 fabric `{64,28,30}`/neoforge `{68,30,57}`。**根因非 loader 差异**(controller 修正了 implementer 的「await 循环超 tick」错模型):`onServerTick` 是套件唯一驱动,刚启动的服务器背负 tick 债、以未节流的 ~3ms 追赶 tick 猛跑,该 burst 区间里 wall-clock-bound 的异步实体提升要花 2–2.3× 的 tick 达到同一延迟;Task-3 那次 neoforge 61-tick TIMEOUT = 负载污染叠在 burst 上,六跑干净复跑 0/6 再现。**处置**:`within 60→120` 场景局部 stopgap 已落;harness 根修(arming 前排空 tick 债 / 改 wall-clock within)= **task#88** 立案。
- **✅ 五门验收全绿路线(本 Task,全部前台有界,顺序跑、绝不同时两服务器)**:①fabric dogfood 正门 `t0.py --loader fabric --run-task :fabric:runDogfoodServer --expect-file expected-scenes-fabric.txt` → **VERDICT: GREEN exit=0**(8 名 `wd.*` 全命中 `registered[]`,`wd.entityLeash` 31 ticks,五字节指标与 golden 逐位一致);②fabric 纯 T0 `t0.py --loader fabric --wall 540` → **GREEN exit=0**(2 walking-skeleton + 3 金丝雀按预期);③fabric 仪表契约 `instrument.py --loader fabric` → **GREEN**(17/17 真检查 + 2 金丝雀,P1b 双 loader 回归确认);④neoforge dogfood 正门(neoforge 路径/清单)→ **GREEN exit=0**,**六字节指标逐字复对全 exact**,`wd.entityLeash` **61 ticks**(> 旧 `within(60)` 会假红——**实证 widened `within(120)` 的必要性**,验证 `339d2d5` stopgap)。
- **🟡 ④ 之外的 ⑤ legacy 全量门本轮 RED(诚实入档,零新名字)**:`./scripts/run_gametests.sh` = `registered=130 entered=130`(**零吞测试**,#85 P0 门保持武装)、**2 个 required 失败** `{serveravatargearscopeprobearena, descentyawarena}` +optional `{vineoverwaterclimbarena}`(既知 flake)。**真单名 solo 逐一定性**(每名单独 `AGENT_GT_ONLY=<name>`,不编组):①`serverAvatarGearScopeProbeArena` solo → **GREEN**(#48 彩票家族);②`descentYawArena` solo → **GREEN**,`sumAbsDyaw=871° backSteps=53` 与 golden **字节一致**(#48 彩票家族)。**`entityLeashRepathArena`(task#87 白名单)本轮未出现在失败集**;**零真正新名**(两个失败名全部落在既有 #48 家族内)。⚠️首次尝试撞已知 `underwaterBaseArena` `ChunkMap.processUnloads` 单-tick livelock(JVM 351% CPU 空转、日志冻结 2min>150s,非 #85):按协议**显式 PID kill + 删 run-gametest world + 重跑一次**,`run_gametests.sh` 自愈,重跑即 `entered=130` 干净入档。
- **📋 legacy 删除倒数**:本轮 legacy 全量 RED(彩票抽样,非回归)→**倒数不 +1**,当前值 = **0**(仍需「连续 3 轮双门全绿 且 编排器已落地外部期望校验」;外部期望门前置由 `--expect-file` 满足,本轮两 loader 正门均已武装通过,但「连续 3 轮双门全绿」计数因 legacy 全量红继续保持 0)。`wd.selfShaftDigUp` 仍是 required 签名门(task#86 未变),`wd.entityLeash` legacy twin 归 task#87(未变)。
- **残余清单(排 P2 及以后)**:①**P2**(spec §7);②task#86(`selfShaftDigUp` gap#53 stride-floor-guard 真缺陷)待修;③task#87(`entityLeashRepathArena` legacy twin master-inherited 的 y=-60 rig 停滞)待查;④**task#88**(`wd.entityLeash` harness tick-debt 根修:arming 前排空 tick 债 / wall-clock within——本 phase 只落场景局部 `within(120)` stopgap);⑤testmod source-set 拆分仍延后;⑥彩票家族(#48)全量身体共享隔离修——待专门 test-framework rework;⑦`/agentserver` 生产命令(`ServerAvatarCommand`)仍 neoforge-only=**有意的 fabric 非目标**(P1.6 只对齐测试面;fabric 侧 sim 层目前仅 dogfood 场景可达,生产接线待有需求再排)。

## 2026-07-17 ✅ P1.5b dogfood wave 2b 落地 — driver 家族三员迁移(gearScope/buriedOre/entityLeash)+ expect-file 正门 + 签名门(branch `feature/executor-permove-ascend`)

- **✅ P1.5b 八 commit 已落**:①`1c60b06` plan(P1.5b 竖切);②`44a9b5c` (T1) `--expect-file` 签入清单正门(与 `--expect-scene` 并集去重、parse docstring 限定符);③`1210852`+`d808348` (T2) task#86 传感器→required 黄金失败**签名门**(`d808348` 评审补 `reached=true` 极性,堵住 stuck-regression 掩盖洞)+ origin-slot 下限守卫 1024;④`76db598` (T3) `wd.gearScope` 迁移(driver 模式确立,probe helper 提升);⑤`0b2e661`+`aa591f9` (T4) `wd.buriedOre` 迁移(MineProcess/manager loop 模式)+ `aa591f9` 评审补 `ctx.cleanup` 保留 legacy rig air-box 清场;⑥`916579f` (T5) `wd.entityLeash` 迁移(两阶段,await 降级 + register-bracket);⑦本条目 = Task 6 验收 + 文档(docs 三文件另 commit)。
- **✅ 8/8 迁移进度**:`expected-scenes-neoforge.txt` 现列 8 名 `wd.*`——wave 1(`ascendMovementNoop`/`ascendDeadZoneWatchdog`/`diagonalAscentSpeed`)+ wave 2a(`selfShaftDigUp`[required 签名门,task#86]/`descentYaw`[pinned slot 4000])+ wave 2b(`gearScope`/`buriedOre`/`entityLeash`)。dogfood = **13 场景记录**(5 内建[含 3 金丝雀,`canaryMustSwallow` 按设计零记录]+ 8 `wd.*`)。
- **✅ 双拓扑纯验收 GREEN(Step 1 dogfood + 纯 T0 双跑,正门首次用 `--expect-file`)**:`t0.py --run-task :neoforge:runDogfoodServer --expect-file scripts/stagewright/expected-scenes-neoforge.txt` → `VERDICT: GREEN exit=0`,8 名 `wd.*` 全部命中 `registered[]`,全 required PASS——`wd.selfShaftDigUp` 本轮**经签名门判 PASS**(task#86:`reached=true && worstBackslide>15.0` 匹配黄金失败签名→场景记 PASS;该场景是 **required 签名门**,其 PASS 断言的正是 task#86 缺陷仍字节级复现[黄金失败]——签名门把「预期的黄金失败」翻成 PASS,缺陷修复或签名漂移时都会转红报警);`wd.entityLeash` PASS(y=200 隔离 origin,56 ticks)。同轮纯 T0(`t0.py --loader neoforge --wall 540`,零 worlddriver 依赖)`VERDICT: GREEN exit=0`(2 walking-skeleton + 2 记录金丝雀 + swallow 门)。
- **🟡 Step 1 legacy 全量门本轮 RED(诚实记录,零新名字)**:`./scripts/run_gametests.sh` 全量 = `registered=130 entered=130`(**零吞测试**,#85 P0 门保持武装)、**4 个 required 失败** `{serveravatargearscopeprobearena, servermineburiedorearena, descentyawarena, entityleashrepatharena}` +optional `{vineoverwaterclimbarena}`(既知 flake)。**真单名 solo 逐一定性**(每名单独 `AGENT_GT_ONLY=<name>`,不编组、不重复 fishing):①`serverAvatarGearScopeProbeArena` solo → **GREEN**(#48 彩票家族);②`serverMineBuriedOreArena` solo → **GREEN**(#48 彩票家族);③`descentYawArena` solo → **GREEN**,`sumAbsDyaw=871° backSteps=53` 与 pinned-slot 黄金值**字节一致**(#48 彩票家族);④`entityLeashRepathArena` solo → **RED**(`phase2: bot did not ARRIVE ... after the anchor moved`,y=-60 rig 停滞签名)= **master-inherited,task#87 白名单已裁定**(干净 master worktree solo RED 2/2 + Unit-1 台账,判为 rig 环境问题非本 phase 回归)。**零真正新名**:四个失败名全部落在既有 #48 家族并集 + task#87 白名单内,符合审计规则(回归只加失败不换人),非回归。
- **📋 A/B 留痕(wave 2b 三员)**:T3 `wd.gearScope` solo GREEN,probe 值两壳(legacy twin + `wd.*` 新壳)**字节一致**;T4 `wd.buriedOre` solo GREEN;T5 `wd.entityLeash` legacy twin 确定性 solo RED ×2 = **master-inherited**(clean-master worktree RED 2/2)→ 裁定为 rig 环境问题,**task#87 立案**,新壳场景在 y=200 隔离 origin `required=true` GREEN,采用**批准的 await 降级**(直译 `level.tick` 会在持久世界上 livelock `ChunkMap.processUnloads`,故场景走 `ctx.await` + register-bracket:platform `onServerTick` 的 `tickAll` 在 await 期间驱动已注册 driver)。**本 phase 零新倒置彩票样本**(gearscope/buriedore solo GREEN,不像 wave 2a 的 selfShaftDigUp 那样隔离才现形)。
- **📋 legacy 删除倒数**:本轮 legacy 全量 RED(彩票抽样,非回归)→**倒数不 +1**,当前值 = **0**(需「连续 3 轮双门全绿 且 编排器已落地外部期望校验」——外部期望门这一前置已由 `--expect-file` 满足并在本轮正门武装通过,但「连续 3 轮双门全绿」的计数因本轮 legacy 全量红继续保持 0)。`wd.selfShaftDigUp` 已是 **required 签名门**(非 optional;签名门 PASS=缺陷仍字节级复现是设计常态,计入全绿);删除资格仍要等 task#86 修复——届时签名门大声 RED,把签名断言换成正常无回落期望(见残余清单②)后重新计数。
- **残余清单(排 P2 及以后)**:①**fabric loader 对齐**——P1.5b 全部验收只跑 neoforge,fabric 侧 dogfood/T0 双门尚未验证(P1.5a 起挂账);②task#86(`selfShaftDigUp` gap#53 stride-floor-guard 真缺陷)待修——修复后本签名门会大声 RED,届时把签名断言换成正常的无回落期望(见场景 javadoc 的 gate 说明);③task#87(`entityLeashRepathArena` legacy twin master-inherited 的 y=-60 rig 停滞)待查——是 rig 环境问题非产品回归,新壳 `wd.entityLeash` 已在隔离 origin 绿;④彩票家族(#48)全量身体共享隔离修——待专门 test-framework rework;⑤`ResultsJsonl` 异步 writer 注记适用范围(SceneContext 写路径)待评估;⑥legacy `@GameTest` 三胞胎(+ P1.5a/2b 五个新双胞胎)删除倒数 = 0,连续绿计数继续累积。

## 2026-07-16 ✅ P1.5a dogfood wave 2a 落地 — 彩票家族竖切(selfShaftDigUp/descentYaw)+ 期望门武装(branch `feature/executor-permove-ascend`)

- **✅ P1.5a 五 commit 已落**:①`f09c4a6` --expect-scene 外部期望门 + parse 坏行降级不裸抛 + `--results` 路径锚定;②`4ac8b3e` originSlot 坐标钉扎 + per-scene chunkRadius + harness 双臂守卫(重复注册/slot 撞车早失败);③`c5b3187` `wd.selfShaftDigUp` 迁移(lottery walker family),legacy 保留供 A/B;④`9a640f0` `Scene.withRequired` + `wd.selfShaftDigUp` 标 optional——task#86 的忠实传感器(隔离才现形的真缺陷,不是移植 delta);⑤`674179e` `wd.descentYaw` 迁移,钉 origin slot 4000 + chunkRadius 2(确定性敏感)。本条目 = Task 5 验收 + 文档(文档三文件另 commit)。
- **⭐⭐selfShaftDigUp 反转彩票揭盖 → 立案 task#86**:legacy 全量套件历史记录该 arena 从未现身失败集(长期表现 GREEN),但在真隔离身体(`ServerPlayerAvatar.createUnique`)下**确定性 RED**:`worstBackslide=20.252203415101263`,字节级一致复现于 3 条独立隔离跑(legacy 自身两次 solo `AGENT_GT_ONLY` + 新壳 `wd.selfShaftDigUp` 一次)。判定:legacy 全量的历史 GREEN 是 gap#48 邻居干扰型假绿(并发 arena 的身体互相推挤掩盖了这个真实的 gap#53 stride-floor-guard 缺陷)——新壳移植是忠实的,暴露的是产品真 bug 不是移植 bug。处置:`.withRequired(false)`,场景跑 fail(optional) 常态记录,直到 task#86 修复再翻回 required。〔已被 P1.5b T2(1210852) 取代:升级为 required 签名门,见上方 P1.5b 条目〕
- **✅ descentYaw POSITIVE 证据(isolated-body + pinned-slot 假说首次确认)**:legacy 单独 solo(`AGENT_GT_ONLY=descentYawArena` 单名,无任何同伴)GREEN,+ 新壳(`wd.descentYaw`,pinned slot 4000 + radius 2)独立 3 跑,`sumAbsDyaw=871°`/`backSteps=53` 逐位一致——隔离身体 + 钉住坐标能稳定复现同一条物理轨迹,不再受套件增长/邻居干扰扰动。Golden values 已回记两个 twin 的 javadoc(见下方独立 commit)。
- **🟡 Step 1 legacy 全量门本轮 RED(诚实记录,如实入档不 fishing)**:`scripts/run_gametests.sh` 全量结果 = required 失败 `{serveravatargearscopeprobearena, descentyawarena}` + optional `{vineoverwaterclimbarena}`(`registered=130 entered=130`,零吞测试)。这是又一次已知 #48 彩票家族抽样(名字全部落在 07-16 P0 条目记录的历史家族并集内),**零新名字**。Solo 定性(各名字只测一次,不重复 fishing):①三名一起(`AGENT_GT_ONLY=serverAvatarGearScopeProbeArena,descentYawArena,vineOverWaterClimbArena`,descentYaw 走自己独立的 `soloDescentYaw` batch)→ gearscope GREEN(既有 solo-绿基线再证)、descentyaw **RED**、vineoverwater(optional)FAIL(既知 flake);②descentyaw 追加一轮**真单跑**(`AGENT_GT_ONLY=descentYawArena`,零同伴)→ **GREEN 19s**。①②对照证实:即使 descentYaw 独占 `soloDescentYaw` batch,"与其他名字一起被 `AGENT_GT_ONLY` 选中"本身仍不是完全隔离——这正是 07-16 P0 条目已记录的模式("三员编组过滤跑 GREEN 除 descentyawarena 单飘 1 次" vs "真单跑 GREEN")的再次复现,不是新回归。**判定:三个失败名全部是已文档化 #48 家族的已知成员,非回归**;但本轮 legacy 全量仍计 RED——不满足"连续 3 轮双门全绿"里的这一轮,legacy 三员删除倒数**本轮不 +1**。
- **✅ 双拓扑纯验收 GREEN(Step 1 dogfood + T0 双跑)**:`t0.py --run-task :neoforge:runDogfoodServer --expect-scene wd.ascendMovementNoop,wd.ascendDeadZoneWatchdog,wd.diagonalAscentSpeed,wd.selfShaftDigUp,wd.descentYaw` → `VERDICT: GREEN exit=0`,`registered[]`=10(5 内建[含 `canaryMustSwallow` 按设计零记录]+ 5 `wd.*`)、实际场景记录=9(`canaryMustFail`/`canaryMustTimeout` 两枚记录金丝雀命中预期 outcome、`canaryMustSwallow` 正确零记录、4 个 `wd.*` required PASS、`wd.selfShaftDigUp` `fail(optional)` 如实记录,`worstBackslide=20.252203415101263` 与上方隔离测量字节一致)、`wd.descentYaw` 日志行 `sumAbsDyaw=871° ... backSteps=53` 与上方 golden values 一致。--expect-scene 门本轮首次在正式验收命令里武装并通过(5 个名字全部命中 `registered[]`)。同轮纯 T0(`t0.py`,零 worlddriver 依赖)`VERDICT: GREEN exit=0`(4 场景 = 2 walking-skeleton + 2 记录金丝雀)。
- **✅ 契约文档 v0 附录(语义只收紧,版本仍 v0)**:`docs/stagewright/orchestration-contract-v0.md` 新增三段——①**--expect-scene 外部期望门**(编排器侧断言,防 `ServiceLoader` 发现链断裂时套件自洽假绿,legacy 删除前置条件);②**originSlot 坐标钉扎**(自动分配随注册表增长整体平移 vs `withOriginSlot` 显式 pin 与注册顺序解耦,发布后不得变更);③**chunkRadius 声明武器**(默认 `r=1` 覆盖 origin 相对 `[-16,+31]`,足迹超窗口的场景须显式 `.withChunkRadius(r)`,声明式非自动推断)。`stagewright/README.md` dogfood 命令补全 5 名 `--expect-scene` + 迁移规则新增一句:"同步 body 必须有界循环"(scene body 内联跑在 server tick 上、不是独立测试线程,无界循环挂的是整个专用服务器,不只是该场景)。
- **✅(独立 commit,评审跟进)descentYaw golden values 871°/53 双胞胎回记**:`WorldDriverScenes.java` 的 `wd.descentYaw` javadoc + legacy `AgentGameTestTerrain#descentYawArena` javadoc 都补了 2026-07-16 迁移期实测的 `sumAbsDyaw=871°`/`backSteps=53`(与历史 `993°`/`67` **并列而非替换**——两个 twin 是不同隔离身体上各自的黄金参照,不互相覆盖,不该被混为一谈),供未来漂移调查从正确参照系起步。仅 javadoc,零可执行代码/断言字符串改动;`:neoforge:compileJava` 验证编译干净。
- **📋 双门状态 + legacy 删除倒数**:`wd.ascendMovementNoop`/`wd.ascendDeadZoneWatchdog`/`wd.diagonalAscentSpeed` 三员(P1c wave 1)按既有记录持续双门绿;`wd.selfShaftDigUp`/`wd.descentYaw` 是本轮(wave 2a)新加入双门 A/B 的两员,尚未累积连续绿计数——`wd.selfShaftDigUp` 因 task#86 真缺陷长期保持 optional(fail(optional) 是预期常态,不追求转绿,删除资格要等 task#86 修复 + 翻回 required 之后重新计数)〔已被 P1.5b T2(1210852) 取代:升级为 required 签名门〕;`wd.descentYaw` 本轮 legacy 全量红(彩票抽样,非回归),连续绿计数本轮清零重开。legacy `@GameTest` 三胞胎(+ P1.5a 两个新双胞胎)的删除倒数**本轮不 +1**。
- **残留清单(排 P1.5b 及以后)**:① driver 家族三员(`entityLeashRepath`/`agentRpcSmoke`/`buriedore`,均已是文档化 #48 家族成员)尚未排入 dogfood 队列,是下一轮竖切候选;② fabric loader 对齐——P1.5a 全部验收只跑了 neoforge,fabric 侧 dogfood/T0 双门尚未验证;③ `ResultsJsonl` 异步 writer 注记(P0 探针事故教训的适用范围)——结果写盘目前仍限场景边界同步写,`SceneContext` 写路径是否需要扩展到异步待评估;④ task#86(`selfShaftDigUp` gap#53 stride-floor-guard 真缺陷)本身待修,修复后把 `.withRequired(false)` 翻回 `true`。〔已被 P1.5b T2(1210852) 取代:该场景已是 required 签名门;task#86 修复后的动作见 P1.5b 条目残余清单②〕

## 2026-07-16 ✅ P1c dogfood wave 1 落地 — SceneProvider SPI + 双门 A/B(branch `feature/executor-permove-ascend`)

- **✅ P1c 五任务已落**:① Task 1 SPI+ctx+forceload(`768a67b`: `SceneProvider` 接口、`SceneContext` level/origin/cleanup、PREP 等满 3×3 forceload);② Task 2 `done.scenes` 对账门(`4c87fde`: judge() 新增 TRUNCATED 检查、t0 `--run-task`/`--results` 通用化);③ Task 3 dogfood 接线(`367ac53`: `:stagewright-common` 依赖、`runDogfoodServer` run 配置、autorun 钩子);④ Task 4 三员迁移(`b86468d`: 历史被吞候选 `ascendMovementNoopArena`/`ascendDeadZoneWatchdogArena`/`diagonalAscentSpeedArena` 移植为 `wd.*` 场景,legacy `@GameTest` 双胞胎保留,断言值逐条 verbatim 保留);⑤ Task 5 双门并行验收+文档(本条目)。
- **✅ A/B 双证据**:legacy 全量门(`AGENT_GT_ONLY=` 三员显式基线,Task 4 同一 build)GREEN 19s;dogfood 新壳(`t0.py --run-task :neoforge:runDogfoodServer`)同一 build GREEN,3 个 `wd.*` 场景与 5 个内建场景(含 3 金丝雀)全过——两条门在同一份迁移代码上独立裁决一致,互为回归卫士。
- **🟡 legacy 全量门本轮 RED(诚实记录,非回归)**:Step 1 首次全量(`./scripts/run_gametests.sh`)`registered=130 entered=130`(零吞测试)但 3 个 required 失败——`serveravatargearscopeprobearena`/`descentyawarena`/`agentrpcsmoke`(+optional `vineoverwaterclimb`)。这正是 P0(task#85)记录在案的 #48 彩票家族(见本文件 07-16 P0 条目:三次全量三种不同失败集,全员 solo 绿)。**测量而非断言分类**:①三员编组过滤跑(`AGENT_GT_ONLY=` 三名一起)GREEN 除 `descentyawarena` 单飘 1 次;②`descentyawarena` 真单跑(`AGENT_GT_ONLY=descentYawArena` 单名)GREEN 1.779s;③`serverAvatarGearScopeProbeArena`/`agentRpcSmoke` 各自真单跑均 GREEN——三员逐一 solo 绿,叠加 Task 4 同一份代码今天早些时候全量 GREEN 130/130 的既有记录,判定为既知彩票家族的又一次抽样,非本轮 P1c 改动引入的回归(P1c 五个 commit 未触碰这三个 legacy arena 或探针代码)。**这正是彩票家族排入 dogfood 队列 P1.5 的论据**——只要还挂在共享 body 的全量门上,这类漂移就会继续消耗验收轮次。
- **✅ 双拓扑纯 T0 验收**:`t0.py --run-task :neoforge:runDogfoodServer` GREEN(exit=0,8 场景=5 内建+3 wd.*)+ 纯 testkit T0(`t0.py`,零 worlddriver 依赖)GREEN(exit=0,5 内建场景+2 金丝雀)——provider 有/无两种拓扑都健康。
- **✅ 契约文档 v0 附录(语义只收紧,版本仍 v0)**:`docs/stagewright/orchestration-contract-v0.md` 新增「SceneProvider(v0 附录)」(ServiceLoader 发现顺序、`rejectDuplicateNames()` 撞名门、金丝雀仅内建承担)+「done.scenes 对账」(TRUNCATED 判据、与 SWALLOWED 分工、缺字段前向兼容);RED 退出码行补 TRUNCATED 提及。`stagewright/README.md` 补 dogfood 入口小节(run 命令+SPI 三行示例+services 文件路径示例)。`.gitignore` 补 `run-contract/`(P1b 遗留的 untracked-unignored 缺口,顺手清)。
- **⭐legacy 三员删除条件(显式记录)**:legacy 三员删除条件 = 连续 3 轮双门全绿 **且** t0 编排器先落地外部期望校验（--expect-scene 或 checked-in 期望清单，防 ServiceLoader 断链时 wd.* 从注册与执行同时消失=自洽假绿，#85 病上移到套件组装层——终审 Important，前置于删除）方可删除 legacy `@GameTest` 三胞胎与其 `gtOnlySkips` 埋点。
- **残留清单**:① `ResultsJsonl` 异步化(结果写盘目前仍限场景边界同步写,P0 探针事故教训适用范围待评估是否需要扩展到 SceneContext 写路径);② 彩票家族(#48,本轮再证:gearscope/descentyaw/agentrpcsmoke/vineoverwater)排 P1.5,目标是把这些 arena 也迁到隔离 origin 的 `wd.*`/testkit 场景,脱离共享 body 全量门;③ legacy 三员删除计数器归零重开(见上条);④ P1.5 pre-flights（终审规模化提示）：确定性敏感场景（descentYaw）需按名固定 origin（index 分配会因套件增长重排坐标）；大 arena 需 per-scene forceload 半径；同步 body 必须有界循环写进迁移规则；verdict.parse() 对中断行应判 TRUNCATED 而非裸 traceback。

## 2026-07-16 ✅ P1b 仪表契约子集落地 — 五任务全过(双 loader 验收+门自证+文档)

- **✅ P1b 竖切五任务已落 feature/executor-permove-ascend**: ① Task 1 verdict 抽取(a53d8dc: 从 t0.py 抽共享 verdict 模块,行为冻结,self-test 11/11);② Task 2 骨架+run 配置+金丝雀(a540771: contractServer run 配置+裸 RPC ws client+金丝雀对+共享 verdict);③ Task 3 batch A(ee95891: 路由分派/schema 违规/client-only 大声失败/脚本 parity,7 项真检查);④ Task 4 batch B(11088a5: world 操作/观测保真含 #42 耐久/事件/等待,10 项真检查,套件共 17 项+2 金丝雀);⑤ Task 5 双 loader 验收+门自证+文档(本条目)。
- **✅ fabric 侧就绪探针竞态发现+修复(a436c61,非批 A/B,单列 commit)**: Task 5 Step 1 首次对 fabric 跑满 17 项真检查时暴露——`launch()` 就绪门原探针 `mc.system.version` 不需要 server attach 就能回应,fabric 快速 flat-world 首启(RPC 监听到 `Done` 仅约 1s)下探针提前判定就绪,8/17 项 touch `api.level()` 的检查(world/obs/events 族)全部 `FAIL — DriverApi not attached to a server`(`VERDICT: RED`,exit=1);根因非产品缺陷,是仪表套件自身的竞态。修复把就绪探针换成 `mc.observe.player`(只读无副作用,但函数体第一行即 `api.level()` 显式 attach 门)。修复后 fabric 复跑 17/17 PASS,`VERDICT: GREEN`,exit=0。`worlddriver-rpc.port` 未与 neoforge 撞车(Task 2 Step 2 的 `configureEach` 端口覆盖风险未兑现)。
- **✅ 门自证三跑(临时改错,均 `git checkout --` 还原,零 commit 残留)**: ①改 `check_version_shape` 断言错值 → neoforge → `VERDICT: RED` exit=1;②改 `canary_must_fail` 为 `return None` → neoforge → `VERDICT: DEAD` exit=2;③两次均还原后重跑 → `VERDICT: GREEN` 17/17 PASS exit=0。等价 P1a 的门自证,验证金丝雀条款(spec §5)活体有效。
- **✅ 双 loader 确定性重跑**: `neoforge && fabric` 两轮均 `VERDICT: GREEN`,combined exit=0;17 项检查+2 金丝雀两个 loader 上行为一致(仅 wallMs 计时抖动)。
- **✅ 契约文档 v0**: `docs/stagewright/instrument-contract-v0.md`——17+2 检查逐条列名+断言+钉住哪条病历(#42 工具耐久、#280 静默吞病族)、永久断言台账、已知缺口(P2:#41 全背包/#45 攻击冷却/#55 伤害源需真玩家、avatar FakePlayer 不入 PlayerList、`mc.bot.setting` 未知键静默吞)。
- **下一步**: P1c dogfood 迁移(worlddriver arena 搬家),顺延清单见 `docs/stagewright/instrument-contract-v0.md` 已知缺口节。

## 2026-07-16 ✅ P1a 行走骨架落地 — 五任务全过(文档收尾)

- **✅ P1a 竖切五任务已落 feature/executor-permove-ascend**: ① Task 1 gradle 骨架(affc448: stagewright-common/fabric/neoforge 三模块+run 配置);② Task 2 scene 模型+注册(034709b: Scenes.all()五场景含三金丝雀);③ Task 3 T0 harness(936f4f2: ResultsJsonl+StageWrightCommon 接线);④ Task 4 编排器+冻结契约(06861ea + a340cfd: scripts/stagewright/t0.py+docs/stagewright/orchestration-contract-v0.md);⑤ Task 5 dual-loader 证(2026-07-16 零 commit:fabric T0 GREEN 首跑、neoforge GREEN、双 loader 重跑全绿、exit=0、场景输出字节同(timings 除外))。
- **✅ 双 loader 实证**: fabric T0 首跑 GREEN;dual-loader 重跑 neoforge GREEN + fabric GREEN,combined exit=0;场景结果字节同(PASS/FAIL/TIMEOUT 行为同,timings 漂移)。
- **✅ 金丝雀哨兵语义**: MUST_FAIL/MUST_TIMEOUT 必须被捕获为 FAIL/TIMEOUT,MUST_SWALLOW 无记录;任何金丝雀误判 → exit 2 DEAD,整轮失效。契约 v0 冻结位置:`docs/stagewright/orchestration-contract-v0.md`。
- **下一步**:P1b 仪表契约子集(worlddriver 接线)、P1c dogfood 迁移(worlddriver arena 搬家)——阶段划分见 spec §7 阶段计划。

## 2026-07-16 task#85 suite-integrity P0 落地 + 全量基线重建(评审修正版,见下方⭐⭐)

- ✅ **P0 四件套已合 master**:①in-game JSONL manifest(76362e5 + **926396d/a5c9e06 探针异步化两修,见下方⭐⭐**,registered/enter 双record,`gtOnlySkips` 埋点处发 enter 探针);②`scripts/gt_reconcile.py` 对账门(a812103 + 574639a,registered-vs-entered SWALLOWED/DRIFTED 双向门 + BUILD/required verdict,9 条内嵌 self-test);③`--audit-source` 源码审计模式(d56c54f + 8bcdfe9,每个 `@GameTest` 必须在自己方法体内挂 guard,防漏埋);④`scripts/run_gametests.sh` 统一入口(288b396,PID sweep→世界清→`timeout --kill-after=30`硬顶→对账验收,永不信 mod reporter 的 `TOTAL:` 行)。
- ⭐⭐**核心新知:P0 探针自伤事故(已修)**。原始 Step1 全量把 `descentyawarena`(一个字节级确定性物理断言,阈值贴近临界`worstBack=-0.05`)三连红误判为"task#48 跨 arena 彩票"——审阅推翻:`descentYawArena` 即使在**完全隔离、零并发邻居**的 solo 跑(`AGENT_GT_ONLY=descentYawArena,...`,自成一个 `soloDescentYaw` batch,前置 129 个测试全部被 `gtOnlySkips()` 首行零世界改动地跳过)里依然 FAIL,结构性排除了"共享身体并发争用"这个机制。controller A/B 定罪:真根因是**我们自己的 P0 探针**——`GameTestManifest.enter()` 在 server 线程上做**同步文件 IO**(每个测试体第一行都调),这个 wall-clock 扰动打破了 descentYawArena 的字节级确定性(摘掉探针→solo 稳定绿 20s)。修=`926396d`(enter 改投递到 `ConcurrentLinkedQueue`,后台 daemon writer 线程做真正的文件 IO,server 线程零阻塞)+`a5c9e06`(writer 线程与 shutdown 线程之间的 append 加序列化,防竞态)。post-fix:**descentYaw solo 稳定绿 3 次,计数器字节级一致**(`sumAbsDyaw=871°` 等)。⭐**教训:对字节级确定性 arena,任何 server 线程上的 wall-clock 扰动都是嫌疑人;"纯文件追加、无副作用" 式的代码走查推理不能替代真实测量(A/B)——我最初就是这么错判的,被审阅正确打回。**
- ✅ **全量 SWALLOWED 三连证**(run1/run2 在探针修复(926396d/a5c9e06)之前、final 在修复之后；探针 bug 只扰动物理确定性断言不影响 enter/registered 记账，故三次 SWALLOWED=empty 结论不受影响)**:三次全部 empty**——`registered=130 entered=130` 逐次成立,#85(静默吞测试)在当前 HEAD **不复现,门保持武装 armed**。历史被吞名单三名候选(`ascendMovementNoopArena`/`ascendDeadZoneWatchdogArena`/`diagonalAscentSpeedArena`)显式 `AGENT_GT_ONLY` 基线**全部 PASS**(19s,VERDICT GREEN,详见下方逐条)。注:final 的第一次尝试撞上已知的 `underwaterBaseArena` ChunkMap livelock(SIGKILL/exit137,非 `timeout` 自身 124,但效果等价——世界跑到一半被打断,SWALLOWED(75) 是运行被截断的伪影,不是 #85;`run_gametests.sh` 每次调用都先 `rm -rf world` 自愈,重跑一次即干净通过)——这不计入"三连全 empty"的计数矛盾,是重跑前的中间态,记录仅为透明。
- ✅ **required 失败归类(evidence-based,替换原始版本的错误论证)**:
  - **`serveravatargearscopeprobearena`(gearscope)= 直接点名 + 经典 #48 签名**:`AgentGameTestServer.java:2340` 注释原文点名 `gearScope`;两次全量(run1/run2)均红、显式 solo 跑绿(`AGENT_GT_ONLY=...,serverAvatarGearScopeProbeArena,...` 该项 PASS)——教科书式并发共享身体彩票。
  - **`agentrpcsmoke`(agentRpcSmoke)= 直接点名 + 家族推断**:同一条注释原文点名 `agentRpcSmoke`("2/3... all solo-green");run1 红、run2 绿、显式 solo 跑绿——漂移 + solo 绿,判定同族彩票。
  - **`deepwatercrossarena` 原引证已撤回**:`AgentGameTestServer.java:2340` 注释点的其实是**姊妹测试 `deepwaterClimbout`**(`AgentGameTestWaterBank.java:1029`),不是 `deepWaterCrossArena`(`AgentGameTestWaterBank.java:919`,两者互为姊妹,见该文件 1018 行doc引用关系)——本次改判为**家族推断非直接点名**:run1 红、run2 绿、显式 solo 跑绿,失败集两次漂移 + solo 绿本身就是彩票签名,只是不能再说"注释直接点名"。
  - **`servermineburiedorearena`(buriedore)= 家族推断**:未点名于该注释,但 run2/final 两次全量出现、跨 run 漂移(未在 run1 出现),行为签名与 gearscope/agentrpcsmoke 同族;未做显式 solo 验证(不在本轮四人 solo 名单内),暂按家族推断记,不升级为可疑新regressons(名字已被 controller 白名单确认为已知历史成员之一)。
  - **`descentyawarena` = 探针事故,已修**:见上方⭐⭐;final 全量仍出现在失败集属**预期内**(coordinator 原话:"修好后 solo 应绿,全量最坏情况下退回历史彩票行为")——探针修复只治了"字节级确定性 solo arena 被自己的 IO 噪声打断"这一具体伤害,不改变"全量并发身体共享"这个 task#48 母问题,descentYaw 本身历史上就在这个母问题的漂移名单里(TODO.md 07-14/07-15 多处记录),这次全量红不是新问题。
  - **`entityleashrepatharena`/`selfshaftdiguparena`(final 新出现)= 均为 controller 白名单历史成员**:`entityLeashRepath` 直接见于同一条 `AgentGameTestServer.java:2340` 注释("`entityLeashRepath` 3/3");`selfShaftDigUp` 见 TODO.md 07-14 记录("selfshaftdigup 全量 RED(4/4)但 solo GREEN...既有全量时序彩票")及 `2026-07-16-b1-pause-for-test-framework.md` handoff("selfShaftDigUp(跨run漂移,本会话实测两轮FAIL一轮PASS)")——两者均为已录入案的 #48 家族成员,非新名。
  - **`vineoverwaterclimbarena`(optional)= 既知 optional flake**:多份 plan doc/TODO.md 反复记录,不影响 VERDICT 判定必要性。
  - **零真正新名**:run1/run2/final 三次全量 required 失败集的并集 = {gearscope, agentrpcsmoke, deepwatercross, buriedore, descentyaw, entityleashrepath, selfshaftdigup} + optional {vineoverwater},**全部落在既有已文档化的 task#48 漂移家族内**,符合"回归只加失败不换人"的审计规则——非回归。suite-wide 隔离修复(task#48 后续)待专门 test-framework rework,不在本次 P0 范围。
- ✅ **被吞候选显式基线(`AGENT_GT_ONLY=ascendMovementNoopArena,ascendDeadZoneWatchdogArena,diagonalAscentSpeedArena GT_TIMEOUT=1800 scripts/run_gametests.sh`,19s,`registered=130 entered=130 build_success=True required_failed=False` → **整体 VERDICT: GREEN**,`exit=0`)**:三个点名的候选者本次**全部有 manifest enter 记录且真实执行**(非早退 no-op)——①`ascendMovementNoopArena`:**PASS**(`step=ARRIVED reachedTop=true ctxAllocated=0`);②`ascendDeadZoneWatchdogArena`:**PASS**(纯逻辑断言测试,5 阶段全过——PREP→giveUp 拍 RUNNING→UNREACHABLE→终态清空重 PREP→active-dig 豁免→post-dig 时钟满额重置→monotonic dy 高水位不被同 apex bob 重置,`helper.succeed()` 无异常);③`diagonalAscentSpeedArena`:**PASS**(`step=ARRIVED reachedTop=true diagBps=3.00 ascSprint%=43`)。**这是被吞名单(三次全量均为空)对应候选的真实 GREEN 基线,供 P1 dogfood 迁移直接引用。**
- ⭐**从此验收只走 `scripts/run_gametests.sh`**(裸 `./gradlew :neoforge:runGameTestServer` 不再是 canonical 入口,不做 PID sweep/世界清/wall-cap/对账,会重新暴露 #85 类静默吞测试)。

## 2026-07-15 gap#81 live 恢复生存时新实证

- 🔴 **gap#73 强实证:PanicChain(1000)不按可达性门控→隔墙不可达 creeper 死锁自救挖掘(Catch-22)**。live(07-15 白天):bot 无镐手挖竖井上升出坑(y64→70),一只 creeper(id36760)在 ~6 格外、**隔实心石墙、`canSeeMe:false`、`creeperSwell:0`、`charging:none`**(creeper 不会挖=对 bot 完全不可达),但 PanicChain 仍以 threat 0.55-0.77 抢占运动通道(priority 1000 > user 50),`userTaskSuspended:true`。因 bot 困在 1×1 竖井里 panic 无逃逸路→冻住 ~1 分钟(video 连报"完全静止无挖掘无视角变化",block.break 事件停),而 **panic 又压制了 user 层唯一能自救的挖掘指令**=死锁。creeper 后来自行上移到 10.6 格外 threat 才降、panic 才释放(bot 被驱下到 y63、重捡掉落 dirt)。⭐**这正是 task#77/gap#73"autoFight/panic 无威胁可达性判断"的洁净复现**(比"墙后 creeper 掘进逼近"更纯:此处 bot 完全被动挨触发)。修向:panic/threat 评分应按 `canSeeMe`+寻路可达性门控(隔墙不可达的 creeper 不该 hold panic 通道),或 no-flee-path 时允许 user 自救挖掘穿透 panic。**附带**:①live-screen-watch 对慢速手挖竖井仍反复误报"卡死"(已在 context.txt 加 NORMAL 豁免但模型仍报,阈值/提示待再调);②bot 无镐+夜降+creeper 三重下,已 cancel surface goto 让其地下过夜(duskSecure 兜底),黎明再上升——**无武器 bot 夜间不上地表**(策略律);③acacia_log 在 panic 驱离期掉落丢失(Stage-1 木种没了,黎明地表补)。
- 🔴 **新 gap(P1,#54/#63/#66"固岩长升"族强 live 复现):无镐深石垂直上升 walker churn,阻塞埋藏 bot 地表恢复**。live(07-15):bot 埋于 y62(无镐、HP13.7、food9),要上 y79 地表采木。**跨三种命令变体全部 churn**:①`goto y:80 + leash{r4,w30}`——y62→66 净 +4 却挖海量方块,横向游走((-8,1)→(-10,-2));②`goto y:80 + column{r1}`(硬 ColumnRadius)——bot 在 (-8,-7) y64↔67 反复上下挖,**根本没进 column**;③`goto direction:up distance:15`——(-10,-5/-6) y64↔66 振荡。整段 y62→68 净 +6 却烧掉大量 food(9→8)。=无镐手挖石(每块 ~7.5s)叠加上升 churn,walker 无法可靠垂直自挖出坑=违反"⭐MC 永远能徒手挖出去"。⭐这是 gap#66 leg B(column no-path,replay-0001)+ #63"固岩长升"残余的**生产环境强证**,应作为 #54 执行器状态机迁移的高优先对象(埋藏无镐是生存常态)。**当前处置**:cancel 止 churn,bot idle 于 ~y68 白天安全,夜里 duskSecure 挖石龛守夜(空背包无 cap 块但石中挖龛=被石围=enclosed 无需 cap)。gap#81 live 行为验证仍未取到(上方全实心岩=StairUpBreak 非 PillarUp,未触发垫柱路径);待有可达地表/受控 rig 再验。
- 🔴 **策略/引擎复盘(本次 live 生存代价)**:①无镐埋藏 bot 上升极慢+churn=上面这个 gap;②上升途中被隔墙 creeper panic 死锁(gap#73)后 creeper 游荡回来引爆 HP20→13.7;③panic 驱离期 acacia_log(唯一木种)掉落丢失→背包归零。综合=**埋藏无镐 bot 的地表恢复目前是 walker 的真空档**,是比单个反射更结构性的生存阻塞,归 #54。

## 2026-07-14 晚 生存线 Stage 4 铁线收官期观察(gap 候选台账)

- 🟢 **gap#2 工具门天然 live GREEN**:双镐耗尽后 mine 行进段空手破石(合法通行),摸到铁矿时干净中止 `blocked: iron_ore needs a pickaxe — none held or in inventory`,零空磨。AutoTool 断镐自动降级木镐也正常。
- 🔴 **gap#74 候选①水线平衡陷阱**:mine 结束后 idle bot 停在淹水隧道段(-84,58,59),头部恰在水线,air 在 220-300 循环振荡(DrowningFloatGate air<240 触发浮起→回升→释放→再沉)=**有顶棚淹水段淹不死也出不来**;idle-passive 契约下横向自救是否允许=user 裁决项(与纯垂直豁免裁决同族)。frail 无滞回振荡家族再添 live 实证(P1 Phase2)。
- 🔴 **gap#74 候选②cornered 事件零去重**:上述陷阱期间 `cornered` 同坐标每 ~3s 一条连刷数十分钟(seq1103-1181+),对齐 antisuffocate 日志去重(gap#72-④)应按转移去重。
- 🟡 **gap#74 候选③craft ensure-count 语义无法区分**:已有 1 镐时 `craft{stone_pickaxe,count:1}` 返回 ok:true+started:true 但瞬间结束零动作零 error(两次复现)——若语义=补足到 count 则应在返回里可区分(`already-satisfied`),否则策略层无法审计;待用 `craft{bucket,count:2}` 观察(bvopgzllh 进行中)。
- 🟡 **观测管道教训(自纠)**:observe.player 的 hotbar 是 inventory 的子视图,sum 聚合=双计数假象("2 把镐"误判);**清点一律用 items 字段单源**(#41 本来就给了)。
- 📋 生存线进度:raw_iron 7(第 8 颗掉落漂失)/coal 6/原木 4/圆石 214/生铜 45;food 7 偏低待黎明补猎;熔炼+桶×2 编排进行中。
- 🔴 **gap#74 候选④combat 远目标追击停滞(live)**:对 84 格外的鸡 `combat{kill,id=29}`,chain 持槽+内部 goto 只出 5-7 步小段(expanded 16/finalCost 48/goalReached=true),bot 数分钟净位移≈0(video 连续 ANOMALY 静止);user goto 同目标同样在水岸振荡(-70↔-62 来回,enteredWater×2,pathStep 卡 1,同一 startedAtMs 3min+)——渐进式分段规划在**跨水体目标**上段末点落水岸→重规划→回摆,combat 追击与 user goto 同病;疑与 #59/#63 escape-farthest/horizon 家族同源的水岸变体。证据:seq1716-1737,startedAtMs=1784071170275(combat)/1784071204595(goto)。夜降后已手动 bunker(SEALED @ -69,64,81)兜底。
- ⚫ **死亡#24 后果:全家当 despawn**——死点 (-57,64,107) 距重生点仅 ~90 格(<sim-distance),区块持续加载 5 分钟计时器走满,回收时(死后 ~22min,含被迫过夜)零掉落。损失=桶×2/铁锭 1/石剑/圆石 178/煤 5/原木 4/生铜 45。⭐策略律:死点在 sim-distance 内→计时器不停,回收优先于觅食(HP 危急除外);死点远(区块卸载)→计时器冻结可从容。Stage 4 从零重建中(红树林原木→石器→老铁矿点)。
- 🟡**gap#81 代码合 master(07-15,5cea9f1)+arena/编译 GREEN,live 行为待白天验**。真根(Explore 定位):垫柱-vs-挖升是 A* 代价决策(PillarUp cost10 vs StairUpBreak 15+破坏),**开阔空气上方 PillarUp 是唯一增高动作必放块**;块选择 `ensureHoldingPlaceableAny`→`isUsableBuildBlock` 零价值意识→acacia_log 被当垫料吃掉。修=价值意识**独立层叠在 isUsableBuildBlock 之上,不动 gap#57 谓词**:新 `BotConfig.isValuablePlacementBlock`(logs/planks 标签)+ `isThrowawaySupportBlock`(可用 build 块 AND 非有价值;dist-neutral core 因 BotInteract 混 client-only 方法在专用服务器不可加载)+ `ensureHoldingPlaceableAny(mc,avoidValuable)`(无非有价值块则返 false**不回退动用有价值块**→gate 跳过,bot 留住资源)+ `Avatar.holdThrowawayPlaceable()`(默认=holdPlaceable,server avatar 不变)/ClientPlayerAvatar 走 avoidValuable。**仅改 Walker:3069 例行 PillarUp**;恢复/逃生垫柱(fellBelowRoute/deepPitEscape/overJump)+bunker/escape 进程保持 holdPlaceable=逃生动用任何块合法(对上 user"逃生必要 vs 例行浪费"边界)。TDD:`valuablePlacementBlockMatrix` 并入既有 AgentGameTestBuildBlock RED→GREEN(6有价值/6junk/throwaway 断言+gap#57 回归守卫 OAK_PLANKS 仍可 build)。编译三 loader 绿。⭐**server avatar 对本改动不可见**(默认 holdThrowawayPlaceable==holdPlaceable)→无服务器 arena 能行为回归,唯一服务器可测项(纯谓词)已 GREEN;全量套件挂死在 underwaterBaseArena(无关 pathfinder/chunk 忙循环环境类,TaskStop 清+删 world)。**残留:live 行为验(bot 携 dirt+log 上升→确认耗 dirt 留 log)待白天恢复生存时做**(client-only 路径唯一真证)。⚠️实证:SurvivalTest=专用服务器,ESC 不冻结(paused 仍 false)+开屏瘫痪反射(已记忆)。
- ✅**收案(07-15): gap#75-a/#75-b/#76 三修合 master(405b444 bridge / 492e6d5 duskSecure / 4465b37 drownEscape)+编译绿**。**gap#76(死亡#25 active溺水抢占)**: mine 挖井进水 air 耗尽仍连 break(gap#70 DrowningFloat 只管 idle,AutoSwim in-process jump 与进程共享通道被压)。修=DrownEscapeChain(priority DROWN_ESCAPE=500)air≤100 抢占通道→纯垂直上浮(仅jump+清sneak+破顶盖)→滞回释放280;纯门 DrownEscapeGate 无client矩阵测;3设置经 mc.bot.setting 暴露。live: PREEMPT@air100(arena真调度双证)+ 清水居中 idle 浮出 y68→75.7(air300)=机制成立。bridge/duskSecure live 复验补齐(tower h5+bridge east4 placed=4 零坠 / 夜间 SEALED)。⭐⭐**溺水 live rig 教训**: 水柱方块中心是 (X+0.5,y,Z+0.5)——错位 tp 送 head 进墙→AntiSuffocate 误击水卡死角落→本 session 自造两溺死(第二次因水柱建出生点重生再溺);清水居中 rig 证浮出=溺死是 rig 假象。规律:水柱 rig 必居中+勿建出生点+air 守护;预灌水坑浮力顶 bot 到表面无法自然复现深水 active-drown(死亡#25 是从干地下挖穿岩石进含水层)。**次级发现→gap#80(已修合 master a288452)**: A=AntiSuffocate.resolveHead 四处 `!isAir()` 误把相邻 water 当窒息目标连击(视角甩下驱动attack)→修=判据改 vanilla `isSuffocating`(新 pure `AntiSuffocateGate.suffocates`,与 isInWall 门对齐); B=AutoSwim deep-ascent 补 `keyShift.setDown(false)`(兄弟 DrownEscape 有); arena antiSuffocateWaterNotSuffocatingArena RED→GREEN(真方块权威)+编译三loader绿+零新回归;live deferred(低频边缘,判据对齐低风险)。 C=cancel(mine) 返 ok 但 break 续~20事件(未确证即停性,携 task#78)。
- ✅(合master 405b444/492e6d5)**gap#75-b duskSecure 被抢占后不重武装——已修+arena GREEN(07-14)+live 夜间 SEALED 复验(07-15)**。live:dusk 挖龛中被 user goto(50>40)抢占→INTERRUPTED;goto 取消后 duskSecure 整夜不再重试,bot idle 在 2 格深未封顶坑(seq1945→1953-1971→chain None)。真根因(arena 世界实测钉死,**非** one-shot 标记——根本没有该标记):被抢占后 bot 站在自己半挖的 1×1 竖井里,HazardField 读 `cornered=true`(8 邻列无 ≤+1 步高可站立点),priority() 的 day/present/cornered 起始门**自我否决到天亮**——"自家竖井豁免"(mid-dig hold)只在 `process != null` 时生效,抢占 drop 掉 process 就失效。修(DuskSecureChain):`rearmPending` 闩——interruptEpisodeState 持有 process 被抢占时置位;新 `startGateBlocks()` 纯门在闩置位时**仅豁免 cornered 否决**(day/absent 仍门,THREAT_RADIUS 否决+debounce 不变);消耗=进程自身终局(bail 不得循环重触发,防 gap#29 棘轮)/黎明(present&&!exposedAtNight)/显式 cancel;`duskSecure.triggered` 事件新增 `rearm` 字段区分重武装。arena=并入既有 `duskSecureHeldProcessLifecycleArena`(矩阵⑤ rearm 生命周期 + `duskSecureRearmWorldLeg` cx3200/cz3500:真 BunkerProcess FakePlayer 挖到井中→interrupt→实测 cornered=true+openAbove=true→门放行→re-arm 再挖至 SEALED+enclosed=true;RED"INTERRUPTED must not consume the dusk episode"→GREEN)。⚠️rig 教训:同步 gametest tick 内 `canSeeSky` 读 stale 光照,露天断言用直查方块列代替。**残留:live 自然 dusk 抢占复验未做(需下次 live 会话)**;全量彩票记录:selfshaftdigup 全量 RED(4/4)但 solo GREEN 且**去掉本 leg 的 A/B 全量同样 RED**=既有全量时序彩票非本修引入;entityleash 时红时绿(master 既有);descentyaw/vineoverwater/descentdrift 同既知。
- ✅(uncommitted)**死亡#24 gap#75-a: construct bridge 起步跌落——已修+arena GREEN(07-14)**。真根因(arena 钉死,非"未建支撑就前移"):①BridgeProcess 全部簿记锚在 `floor(center)`,而 vanilla sneak 边缘防坠(`Player.maybeBackOffFromEdge`,gate=`isShiftKeyDown`)**允许合法悬伸**到 AABB 只剩 0.3 贴支撑——center 合法越入无支撑邻列(tower 收尾在 1×1 柱顶常态产出该姿态)→ PLACING 读"脚下无支撑"**误诊坠落**、终止,terminal `releaseInputs()` 松开正把人钉在崖边的 sneak = 真坠落(死亡#24);arena 复现:悬伸 start t=1 即报同款 lastError 而 pos/onGround 纹丝未动。②client 侧共犯:raw forward 沿**相机 yaw** 行走,而 LookController 在 scheduler 之后 30°/tick 重钳 BridgeProcess 的 setYRot snap → 起步头几 tick 朝任意方向走(headless 无 seam,live 验证)。修(BridgeProcess):`anchoredFoot()` AABB 感知重锚(center 列无支撑→取 AABB 下最大重叠有支撑列;全无才判真坠落)+ WALKING forward 门在 pre-write yaw 对齐 ≤20° + `placed` 改 pendingPlaceCell 观察到实心才计数;TowerProcess 同族审计:placed++ 加放置验证。arena=`serverBridgePillarStartArena`(AgentGameTestServer,leg A 悬伸 start RED→GREEN laid=#### done placed=4;leg B 居中 baseline GREEN)。**残留:live 柱顶 bridge 复验未做(需下次 live 会话);死点 (-57,64,107) 家当已 despawn(见上条)**。⭐HP1 时不该用未经 rig 验证的 dual-use 动词(教训)。⚠️07-14 记录:`entityLeashRepathArena` 在**干净 HEAD worktree solo 也 RED**(2/2,pos 停在 arena 原点未动)=master 既有状态非本修引入;descentyaw/gearscope/buriedore/vineoverwater 均彩票(solo GREEN)。
- 🔴 **(升级为 task#78/gap#74)** 上述①②合并立案+新增:**frail 门无目标类型判别**——HP5 猎鸡被 `frail-abort hp=5.0` 拒绝=饥饿螺旋;workaround=combatFrailThreshold 临时 0(用后恢复 6)。**险死复盘**:夜行被双僵尸接力追击 HP20→5,零低 HP 反射兜底,根因=`autoBunker=false`(#30 死锁 workaround 从未恢复,当年根因 #29/#61 早已修)——已恢复 true;⭐实验性关反射必须记录+根因修后立即恢复。水线陷阱最终解=mc.bot.build 圆石堵水(escape verb 对该场景瞬退零效果);流水推挤+无镐+淹水隧道=walker 水域新变体。duskSecure 夜龛在 retreat 释放后正常触发(gap#72 修后首次自然夜龛✅)。

## 2026-07-14 ⭐当前主线:task#54 Phase 1 调度器语义层(spec 待 user 审)

- **方向**(user 授权自定,memory `project_direction_decision_54_first`):主线=#54 Phase 1 调度器语义层;#52 并入 spec 验证底座章节;生存线降级回归信标;gap#67(task#66)排后。
- **spec 已提交**:`docs/superpowers/specs/2026-07-14-scheduler-semantics-phase1-design.md`(commit fd1d415)——5 根因(episode 生命周期/终态诚实化/ThreatContext 反射门/combat 脆血+dusk 升压/AutoTool 宽限)对账 gap#68 十一腿证据册(task#67)。**等 user 审核,批准前不写实现**。
- **生存线状态**:day 60 晨,Stage 4 铁+gap#64 smelt live 收官✅(task#63 收案);bot 封洞于 y20 隧道,游戏已暂停;autoBunker 关闭中(⑦死锁 workaround),夜间需手动 bunker+封口(bunker verb 有零动作假成功,证据⑩)。

## 2026-07-02 ⭐当前优先级(用户硬指令)与任务镜像(Task #2-#7)

**优先级指令**:①高效发现问题与验证(观测/验证基建)最优先 ②本项目代码防腐/架构优化次之 ③发现并解决具体寻路问题最后。

- **P1 观测基建(Task #2,in_progress)**:11 类 [expect] 执行器预期报警已 committed(DIG-dropped/DIG-slow/JUMP-noRise/MOVE-noMove/REPATH-flip/DRIVE-tear/ADVANCE-deadzone/GEAR-degraded + 本日新增 CLUTCH-noArm/PLACE-noBlock/IDLE-drowning)+ JUMP 阈值 0.8 校准 + forensic.py 同 tick 配对验尸 + 反射化 setting(新 flag 零接线)+ 验收协议 preflight/expect 计数/churn-drown guard。**剩:重启客户端激活(遇 GL hang,复现 2026-06-27 签名)+ smoke。**
- **P2 防腐(Task #3)**:验收 FLAGS(~27 个已验证 default-OFF flag)与 accept_cycle.py 从 scratchpad 入库。
- **P3 寻路 lane(Task #4-#6)**:①escape-vs-dig 调度冲突(C24-J3 DIG-dropped 归因=DrowningEscape 抢占断 breakHold)②dig aim-drift(DIG-slow 归因)③repath 振荡 hysteresis 正确场景重 A/B((-252,70,209),K≥6)+ mount 双稳态(replay-0016)。
- **终门(Task #7,blocked by #2)**:#47 三周期验收(3×随机长途+每程 replay×3 全绿);已 24 cycle 未连三绿,J1 已稳。flip-default 待用户。

## 2026-06-27 ⭐弧长追踪执行器重构(治本)+ 打破一个月 0-commit 死锁

用户硬批评「一个月点修无进展、向后跳/挖墙/卡浮萍仍在、每次只修一小段发现不好就 revert、原地踏步」后,转向**结构性重构**:把执行器的「每-tick 瞬时几何门 + bob-immune 计数器动物园」替换成**弧长追踪 pure-pursuit**(脚投影到 path 折线→单调弧长 s→切线驱动)。分阶段、各自 flag、全 **live/replay 验证**(非 arena-green):

- **P1 `walkerArcLengthAdvance`(default ON)**:用投影段推进 step 指针,旁路 7 个 bob 补偿门。LIVE:`projSeg>step` 30→4(step-freeze/卡浮萍消除)。
- **P2 `walkerTangentAim`(default ON)**:相机+身体瞄 s+lookahead 处路径切线(永不 180° 翻),消除**向后跳/反复横跳/贴墙**。LIVE:平滑段 `dYaw≥120` 123→3(0.2%)。含 **dry step-up 例外**(ascent 节点用 legacy 节点 aim 让 pivotForStepUp/stepUpJump 对齐 mount)→修**上坡跳不上方块**(LIVE bot 爬升 y62→y94 解锁整座山)。
- **P3 `walkerArcLengthWedge`(default ON)**:bob/jitter-免疫 ram-wedge recovery(`|ds|<0.05 + horizontalCollision` 累积 30t→折进 proven fellOffPath blacklist+reroute),替代挂 noStepProgressTicks 被 ram-jitter 清零的 descentRamStuck 族。**EXCLUDES water**(水域 climb-out 有专属 recovery)。LIVE:water-exclude 0 误触发。
- `walkerArcLengthShadow`(default OFF,log-only 诊断)。`PathProjection.java` = 抽出的可读投影核心(巨兽分解第一步)。

**净效果**:干地 traversal 根治了用户点名的向后跳/横跳/贴墙/上坡跳不上;`HEAD f9a4a2e`(一个月前)以来的全部累积工作落定为 committed baseline。

### ⬜⬜ 已知剩余 dominant blocker:水域 climb-out / bank-dig(near-fundamental,下一专门周期)
arc-length 相位只治**干地**;**水域 jank 是独立大域、未治** = 用户点名的「挖墙」+「在水里卡住」。**三器定层(-658,61 live)**:① planner **完全干净**(debug.plan chain reached/0 backtrack,routes 平缓爬升出水);② 纯**执行层**:submerged bot(`deepDig=true`)在 +3 岸脚被抑制 pillar 爬出(2461 要 `!deepDig`)→落到 block-less bank dig(2606 deepDig 分支)→水下原地挖高岸,而非先浮出水面/沿 path surface-swim 到平缓出水点。**修向**:水域 climb-out 不在 path 仍沿水时过早 engage 本地陡岸;submerged 先 surface 再 climb。浮力 bot 放 foothold 站不稳是 near-fundamental(dig-over-pillar 是当前 least-bad)。Task #59。详见 memory [[project_arclength_pursuit_refactor]]。

## P0 — 寻路丝滑(长途随机目标一路丝滑、不停顿、不跳变视角;验收=pathChart + 视频转录双通道无异常)

镜像 Task #29(回归 goal)+ #34(规划层深水可站性)。

### ⏳ 2026-06-24/25 执行器 fix 族 I/J/K/L/M(uncommitted,各 GT 50/50 + live replay A/B 验证)— 陡山爬升丝滑
真根因全在 Walker/BotInteract 执行器层(非规划层),逐 tick telemetry + replay A/B + GT 三器定层:
- **fix K**:survival pillar-recovery `BotInteract.ensureHoldingPillarBlock` 只扫 hotbar(inventory 分支 creative-only)→survival bot cobble 在 inv slot 9 取不到→slide-back 恢复静默 no-op = 主 churn。修=survival inv→hotbar SWAP(镜像 AutoEquip)。drift-stalls 28→2。
- **fix L**:对角爬升 inter-step sprint→momentum 横向漂离窄楼梯线→slide→ascentRamSlide pillar-spam。修=`diagAscent`(对角干地爬升)drop sprint。pillarUp 292→0、cobble 用 56→2、到达 2× 快。
- **fix M**:tree-canopy/dirt overhang 下 pillar-recovery 撞顶 bob 不升 247t(fix K 副作用)。修=no-rise give-up(`PILLAR_NORISE_GIVEUP=50`t 不升→gate fellBelowRoute→foot-search re-route 绕开)。trap 247→0、pillarUp 339→95。
- **fix I/J**:深坑 pillar-thrash 105s→4s;慢游 approach-sprint +78%。
- **残:vine-over-water climb-out**(#50,-711,67 vine-wall-over-pocket)+ open-water surface-swim drift(#49)。**2026-06-25 三修全 revert→working tree + live client 均 clean I-N'**:fix O(planner floating cost-tax→A* 撑爆 30000 node-cap)、fix P(executor 删 vine `!isInWater`→-711 churn 1086t/54s ~9× WORSE)、fix Q(Plan-agent spec:Part2 parkour-landed-on-vine handoff `onVine 在 parkour edge 放行 when landedOnVine`,GT 50/50)。⭐**fix Q 真 blocker = VALIDATION 不是 design**:`replan:true` journey replay 每跑 path 不同→几乎不复现 parkour-LANDS-on-vine target(replay#3 没碰 vine/#4 off-path drift),pre-widen 命中时 grab 但 **bob-stuck 爬不上**(unknown#3=vine yaw 瞄 overhead node 非 wall)→无法干净 A/B→revert。**真路(fresh clear-headed session):先建 DETERMINISTIC `vineOverWaterClimbArena`(spec 全文 scratchpad/vine-over-water-fixspec.md:layout+never-pocket-entry predicate,⚠先验 fake-player vine-cling fidelity)做 reproducible A/B——journey replay 对此 genre 根本不够;再 Part2+widen+unknown#3 ascent-yaw 修**。⚠~450-turn fatigue 下 O/P/Q 三连 revert=别再盲改 water/vine,需清醒 session。**#47 因 -711 genre 未根治、round3 未过**。详见 memory [[reference_water_brake_sneak_sink_and_parkour_yaw_fix]]。
  - **2026-06-25 续(突破+第4次 live 破)**:✅DETERMINISTIC `vineOverWaterClimbArena`+`vineClingFidelityProbe` 已建(validation-tooling blocker 解,byte-identical 复现 baseline)。agent 实现 **Part A `vineWallYaw`**(Walker:738,climb 时 yaw 瞄 vine 背墙=持续 into-wall press=vanilla climb-up,unknown#3 正解)+**Part B BridgePlace:44** reject bridge-from-vine。**GT 52/52 + arena ARRIVED everInPocket=false**。❌**但 LIVE replay-0004 -711 仍 churn 2438t/~122s,pocket 1745,vine-climb 仅 40×**(journey 仍 **ARRIVED**=jank)。⭐divergence:arena 过靠 A* 的 **pillar-AROUND**(Part B 撤 bridge 后更便宜),**live 几何无 around**(parkour→vine→pillar-AT-TOP,vine REQUIRED)→arena 给了 live 走不了的逃逸=false green。Part A+B+fix Q+arena **KEPT**(uncommitted,GT-green,sound,无明显回归,作下 cycle foundation)。**NEXT:精化 arena=force vine 为唯一 ascent(删 pillar-around,从 replay-0004 envelope 真几何切)+re-add VINEDIAG 诊断 live vine-grab 为何不 sustain+爬到顶无 pillar 逃逸才算 faithful-pass+live ≥3× 验**。
  - **2026-06-25 续续(✅✅突破成果)**:① **validation-blocker 终解**=`mc.debug.replay {replan:false}`(经 mc.script.eval)force-path 确定性 A/B,replan variance 不再卡。② **root cause 终定**=-711 vine FREE-HANGING(挂 canopy 无背墙→vineWallYaw=null→Part A press-wall 不适用)。③ **fix R `walkerVineFreeHangClimb`**(default ON;wall-less vine continuous-jump+slew drive+vertical hold;ServerPlayerAvatar+JUMPING_FIELD)修好 **CLIMB**——GT 52/52+free-hanging arena climbedTop=true+**full-journey replan:true pocket 1745→367(5×)/churn 2438→~964(3×)/ARRIVED z282**。KEEP(uncommitted,GT-green)。⚠**残:full-journey -711 仍 churn ~40s(簇 -711~-716)= parkourAscend2 落点 UNDERSHOOT 穿 vine 掉 pocket 底(foot=water→freeHang 不 engage)→浮回 vine 才 climb** = landing-undershoot(独立 genre)。**验证挑战**:arena pocketTicks 是 TOP-dismount artifact 非 bottom-landing;replan:false 在到 -711 前先 wedge 于上游 open-water drift -703,487(#49);replan:true 噪声。→landing fix 需先「精化 arena 捕捉 bottom-landing undershoot」或「先修 #49 上游 drift 解锁 replan:false 直达 -711」做干净 A/B。⭐**fatigue ~565 turn,不盲夹 landing fix,留 fresh cycle 配好验证台再做**。详见 memory fix R 段。

### ✅ 本会话已修(master)
- **4a23b00 climbOutTax(XZ 目标)** — per-rise 罚「出水上岸偏向高岸」,让 A* 挑最低岸出水口。永久 A/B arena `waterClimbOutRouteArena`(税off 爬+1 高岸 maxY221、税on 改走平岸 maxY220)。
- **0cebe97 submergedTax(核心根治)** — 真根因:`PathFinder` 4 条水税(waterCellTax/descendTax/submergedWaterCost/climbOutTax)全门控 `goal.ignoresY()`→**只对裸 XZ 目标生效**;正常 `goto pos` 是 `Goal.Near`(带目标 Y)→水模型全失效→A* 把浮力 bot 路由到淹没河床(stepDown,canStandAt 让任意水格可站)→深水 churn。修=新 `Goal.targetPos()`+`diveGoal()`(水下目标=故意潜水豁免),对陆上目标罚「**下潜**进淹没格」(只罚下潜→pillar/dig climb-out arena 不退化)。**LIVE replay A/B:停顿 58s→14s(-76%)、journey -34% 更快、z1956~1947 深水 churn 簇整簇消失。GameTest 109/109+42/42。**
- **5ac9b3f 证伪记录** — climbOutTax 放开到陆上目标 backfire(罚出水→bot 赖水里→复活下潜 churn,replay 14s→37s),保持 XZ-only。
- **66ef356 knob 接 setting** — pathfinderSubmergedWaterCost 此前未接进 mc.bot.setting,补 setter/snapshot/schema,可运行时调参。
- **9cc617a submergedTax 默认 40→80** — live A/B 再砍一半:停顿 14s→7s(-88% vs 58s 基线)、窗 4→2、z1883「被迫下潜」窗整窗消失。抬罚方向安全(只罚下潜)。GameTest 109/109+42/42。

**进度:journey 停顿 58s→7s(-88%),端到端到达、大幅丝滑。**

### ✅ z1973 漂移上岸振荡 —— 根治(0e08d47)
逐 tick + block 探针推翻「commit 失败/相机射线」初判:真因=block-less 破岸 dig 每 tick 从 LIVE(bob foot.y 翻 208↔209 + 侧漂)重算 riser→**同一岸列在 foot.y 与 foot.y+1 两高度都挖穿**→岸面挖到水线、下一列变新 +2 墙→无限横跳(ashoreTick 162)。修=`Walker.waterClimbDigRiser` 闩锁:①锁住正挖 riser 块实心就一直挖它;②选新 riser 挖前向列**顶层实心块**(沿列向上找顶=对 bob 不变);③仅 `top.y>foot.y` 才挖(+1 台阶本可上,挖它会把岸挖到水下)。**driftArena 162→74**(swim→挖一块→上 +1→上 +1,零横跳零重挖),断言收紧 ashoreTick<120。GameTest 109/109+43/43。

### ⭐⭐ 关键 live 发现:反向 leg goal 本身不可站(坏目标,非寻路 bug)
relaunch 新 build live 跑 (2356,1986)→(2350,1820):bot 多次逼近 d=2~8 又弹回水里 d=80~109,A* **goalReached=0(全 70 段)**;逐格读目标列 **(2350,*,1820) y60-64 全 stone、y65 唯一 1 格 air pocket(石头 y66 顶)=站不进→目标格不可站**。canStandAt 正确拒绝→A* 烧满 60000 节点/repath(4.9s)→bot 邻近水里振荡。**换可站 goal (2343,63,1824)→outcome=SUCCESS、84s 端到端到达、archive 量化仅 1×3.5s 停顿窗(z1956 首次入水)、无西向绕路**。→**核心寻路 + drift-dig 修都 OK;旧 stop-hook 反复报的「journey churn/异常」≈坏目标 thrash。**

### ✅ 不可达/不可站 goal robustness —— 根治(783aa32 + 21807a2)
`Walker.snapGoalToStandable`:Goal.Block 目标若 `!world.canStandAt`,半径 6 内搜最近可站格(用 planner 同一 canStandAt 谓词→规划+arrival 一致)替换 goal。**关键门控 `world.isKnown(target)`**:长途起点离目标 166 格→目标 chunk 未加载→canStandAt 读 void→若首 tick 就 no-op 且置 checked 标志则永不重试;改成目标 chunk 加载后(bot 进渲染范围)才一次性 snap。新 arena `goalSnapBuriedArena`(实心石柱目标→snap 邻格 ARRIVED@31)。**LIVE 验证**:坏目标 (2350,64,1820 埋石山) 整程→snap log「→(2348,64,1820) d=2」、166 格端到端到达并 settle(active=false 静止不动)、pathChart **outcome=SUCCESS reached=true**、无西向绕路。GameTest 109/109+44/44。

### ⬜ 残留真凶(系统性,大工程 deferred):深水 +2 岸 climb-out 原地打转
**逐 tick + step 指针定根(snap journey archive,tick 3537-3722)**:水谷停顿(5.3s+3.6s @ (2360,62,1879))= **step 冻结在一个浮力 bot 上方的 +2 climb-out 节点**(node y64,bot 浮 y62)。`within` 守卫 `!(inWater && dyNode>0.5)` 正确不让前进到没够到的 climb 节点,bot 到了该节点的 XZ 但爬不上去→**对着正上方节点没有水平 aim 方向→smoothLook 原地旋转(yaw 扫满 360°=pathChart maxYawErr179 之源)**,直到 repath 把它带走。climb-out 接管(pillar/dig)本该 engage 但被打转/侧向动量搅乱不可靠(=memory「接管延迟+可靠性+burst-crab」)。**多 journey 验证(本会话 2 条)证此为系统性**:南向到 snap/可站目标=到达但中段 1 个 5s 打转窗;北向 (2356,70,1986) 整程在起点水区打转 130s 没到达——此水饱和地形多个目标都落在 water-climb-out 点。**根治=专项重写水岸 climb-out 执行**(浮力 +2 岸:climb 节点上方 aim 稳定[别打转]+ 接管可靠引擎,~15 prior+本会话尝试均证非增量能成);drift-dig(0e08d47)已修「挖过头」一类,但「到了climb节点XZ却打转不engage」是另一面。**别在会话尾部仓促改 aim/heading(4 次 forced-heading 尝试都 revert 过)。**

### ⬜ 新失败模式根因(已定位)+ dig actuator 已证伪(revert):水平水域 bank-face wedge
**逐 tick walker 日志(walkerDebug)定根**:重跑南向 climb-out(TP 2361,66,1895 → goto 2361,64,1860),archive 量化 = 20s 跨 33 格、**85% 在水里**、median 0.09 b/tick(半速)、**2 个停顿窗 2.5s@(2361,62,1880)+3.7s@(2361,62,1870)**,全在水里。walker 日志:停顿处 `move=walk onG=false inW=true undW=false node=2360,62,1869`——**节点 Y==foot Y**!`wantClimbNow=(cwp.y>foot.y)` 恒 false → climb-out context 永不 arm → `waterClimbStall` 恒 0 → 破岸/搭台 actuator 全程**零触发**(grep 证实)。bot 顶着一道岸 face(planner 把平 Y 节点路由到 face 后)`cur2` 卡 ~1.0 撞墙,直到 ~64 tick 后 A* 撞运气重路由才脱困。**这是和 +2 竖直 climb-out 不同的一类:水平 bank-face wedge。**
**尝试(Walker,5 处:`horizWedge`=水中+前向solid face+`noStepProgressTicks>30`→折进 wantClimbNow;dig trigger 加 `||horizWedge` 跳过慢门槛;riser 加「同 Y body-level face 直接挖穿」分支)→ GameTest 44/44 不回归(driftArena 75),但 LIVE 证伪:** dig 触发了(block-less-dig fires **0→213**),**可是同 Y foot-block dig 是错的 actuator**——浮力 bot 把 foot 层挖掉后**下沉进挖空格、在更低处再 wedge、再挖**=往下挖坑;riser 闩死在 `2359,62,1863`(够不到了仍 latch)213 次空挖,bot 从 y62 沉到 y59,**停顿 2.5+3.7s 反而恶化成 11.2s、全程 20s→31s**。video 也报「卡在地下泥土矿洞」。**已 `git checkout` revert 回 f4da16f(干净,重编译过)。**
**下次正确方向(别再挖 foot 坑):** ①规划层——`canStandAt`/move 生成时,浮力无方块 bot 不该把平 Y 节点路由到水面岸 face 后(不可达);或给这类 cell 加 reach/penalty。②执行层若要救,**不是挖**:把够不到的平 Y 节点判「passed/skip」+ 标 avoid-point 快速重路由(不 gouge 地形),或在**水面层**开通道让浮力 bot 平游过去(不在 foot 层挖坑)。trigger(horizWedge 检测)本身是对的、可复用;**坏的是 dig actuator**。

### ⭐✅ 真根因纠正 + 修复落地:水中 yaw 振荡(master b30ab05)
archive WalkerSample 推翻上面「face wedge/dig」诊断:停顿处 `aabbOverlap=false`(无碰撞=无岸 face)、浮水 STANDING。真相=**flat 水节点上 yaw 振荡**:stall 窗 yaw 摆 96-176°、净速 0.37-0.88 b/s;同路径 heading 稳定段(摆 22°)=4.6 b/s。yaw 摆抵消前进推力→「原地不动」=停顿,**同根因也是 pathChart maxYawErr180 + 视频镜头跳变**。修 b30ab05:flat 水节点在 noStepProgressTicks>15(~0.75s)时也用宽 aim dead-zone(CLIMB_AIM_DEADZONE_SQ=4)锁 heading;门控 no-progress 保 climb-out 精度(无门控放宽→noBlockArena 回归)。**GameTest 44/44 + live 停顿 2→1、6.2s→2.1s(-66%)、yaw 振荡 3.5-6→1.6°/tick、零误挖、SUCCESS**。残留 1×2.1s = slow-progress 振荡(缓慢推进重置 noStepProgressTicks→门控漏)。证伪:放宽推广到「无 imminent climb flat 水」→ waterClimbOutRouteArena 回归(漂离 +1 出口),太脆弱 revert。

### ⭐✅ carrot-swing 远 aim 稳定(master c733df7)
b30ab05 残留的 aim2>deadzone carrot-swing(LOS 被岸挡→carrotPoint 坍缩近节点→短 aim 矢量随浮水漂移快转、bearing 摆 96-176°)。修 c733df7:水中按 raw carrot bearing 反向次数累衰减 `yawThrashTicks`;分数≥6 且前向 lookahead 段无爬升节点(climbAhead=false,保 +2 climb-out 挂载精度)时 aim 到 step+3 远节点(长矢量 bearing 稳)。GameTest 44/44(waterClimbOutRouteArena maxY221 不回归)。LIVE flat-水 carrot-swing 簇 3 窗/6.5s→1 窗/2.2s。

### ⭐ 真根因再纠正(逐tick walkerDebug+archive 实测,2026-06-15):水谷打转 ≠ 「+2 climb-out 执行卡死」
**z1881 南向 climb-out 复现(TP 2361,66,1895→goto pos 2361,64,1860,walkerDebug+archive)定根,推翻 §「残留真凶」整段的「step 冻结在浮力 bot 上方 +2 节点 / climb-out 接管该 engage 却被搅乱」理论**:
- **climb-out 执行器(pillar/dig takeover)整程零触发**(walkerDebug 1635 行,climb-out/bank dig/pillar/topped 行 = **0**)。A* 没路由「浮力 bot 上方 +2 竖直节点」;它路由的是**可走的 stepUp 楼梯**(y63→64→65,move=stepUp 链)。climb-out 执行器**正确地保持关闭**(不需要)。
- **真打转 = 干地 stepUp 楼梯的近节点 aim 振荡**。archive 三停顿窗:①(2362,62,1885)inW=true onG=true=2 深水里**贴床趟水慢**(~0.11 b/tick,node 是 19 格外的平 y62 节点);②(2359,63,1864)onG=false inW=false **yawRange=405°**=干地 stepUp 链上,`aimAtWaypoint=wp.y!=foot.y` 对每个 +1 近节点(cur2 0.5-1.4)snap→短水平矢量 atan2 随 step bob 摆→多级楼梯累成 360°+ 镜头转(climb 本身在进展);③(2361,68,1860)=到达 goal 旁的 idle 抖。
- **结论:这不是水域/climb-out 执行域的系统大工程,是通用「干地 stepUp 近节点 aim 振荡」**(=旧 §残留小项的「z1987 干地 stepUp bob」同根,但严重度被低估)。

### ⭐✅ 干地 stepUp 楼梯镜头打转根治(master 5a0ca93)
**先证伪 hold-yaw 版**:`landStepUp` 用 CLIMB_AIM_DEADZONE_SQ 冻结 heading → **buoyantwallarena 回归**(冻结的 heading 钉死一个错方向,+5 水岸出水 stepUp 顶岸失败 maxY211.25)。**冻结 heading=错路**(=旧 §「4 次 forced-heading 都 revert」同坑)。
**改 bounded-lookahead(过)**:land + 平缓 +1 step,且水平已在该 step 节点 1 格内、还有后继节点时,aim 改指 **step+1 节点**——给真实「朝楼梯上方」稳定 heading,不冻结(故不钉死错方向;+5 水岸出水后继是前向 ledge walk,只会更稳)。排除 parkour + >+1 跳。
**验:GameTest 44/44(含 buoyantwallarena)。LIVE z1881 南向 climb-out:干地爬楼 mean|dyaw/tick| 405°区间→0.50°(max 8°=slew 上限单次平滑转)、29s→20s、停顿窗 3→1(仅到达 goal 旁 idle settle)。video:过岸「平稳前进、无停顿或抖动、移动轨迹平滑」。**

### ⭐⭐✅ 端到端全程验证(三修齐):165 格水谷 0 停顿
**TP (2356,67,1986)→goto pos (2350,64,1820)[snap→(2348,64,1820)],全 165 格 N→S 多段水域 + 上岸台阶。archive:outcome SUCCESS、67s、z1986→1820 端到端到达、**停顿窗=0、0.0s**、whole-journey mean|dyaw/tick|=1.55°(max 88 单次 slew)、干地 onGround mean=2.19°(无 405° 打转)。video 双通道:journey 中段 NORMAL「沿峡谷水渠持续向南移动、行进路线连贯」「沿河谷水域持续向南推进并顺利上岸」。** vs 会话初 58s 停顿 / 上一条 video run 同路 4 窗 8.8s → **本条 0 停顿**。三修累积(b30ab05 宽 dead-zone + c733df7 far-aim + 5a0ca93 stepUp lookahead)把水谷停顿簇清零。
### ⭐ 随机长途多实验(部分 clean,但暴露真残留)
- **J0 全 N-S 水谷 165 格**:0 停顿、SUCCESS。
- **J1 东向上山 113 格**(含 parkour2/3 爬坡 y64→74):**0 停顿窗**、SUCCESS。
- **J4 山顶→水谷 90 格**(y79→63):1×1.8s(2 深水趟水)、SUCCESS。
- **J5 西向 2461→xz(2330,1740) 130 格**(干地起):**4 停顿窗 ~6.4s**、SUCCESS——①1.6s@(2461,70,1824)airborne(起步跳)②1.3s@(2392,63,1793)干地③**1.4s@(2335,62,1742)+2.1s@(2335,62,1743) inW浮水 onG=false yawRange131/96=floating-water yaw-spin**(逼近 goal 旁 +1 岸的最后一段水)。
- **测得 goto 起步 latency=0.45s(quick-start stub 工作正常,起步停顿非真问题)**;video 反复报的「数秒才动/溺水」=lag + bot 到点停在水里/墙边的 idle 误读(实测 hp/food=20 不掉血)。

### ⬜⬜ 真残留核心未根治:浮水逼近 goal 的水中停顿 —— 诊断已纠正=step-pointer thrash(非 aim-spin)
**逐 tick archive(J5 replay-0008,z1742-1743 停顿窗)纠正了「carrot-swing aim-spin」初判**:停顿处 `move=walk/parkourDescend2d1`、onG 浮力 bob 翻转、bot **仍在前进**(z 1744.3→1742.0 ~0.08/tick 慢),关键是 **step 指针来回跳 2→3→4→4→2→1→1→…→2(4→1 倒退!)**=浮力 parkourDescend 入水时**步指针 overshoot/reset 反复**→aim 追被 reset 的节点→yaw 摆 40-131°。**这是 step-tracking(overshoot-resync)域,不是 aim/carrot 域**(见 [[reference_walker_overshoot_resync_fix]])。
**❌证伪:把 far-aim 扩到 climbAhead→aim climb 节点** → GameTest **summitarena 回归**(浮水 thrash 时 redirect 把 overhead pillar 爬升 ARRIVED@y226 早停)→ **已 revert 回 5a0ca93**。教训:水中 thrash redirect 不能在 climbAhead 时 aim climb 节点(会劫持 pillar);且 J5 根本机制是 step-thrash 非 aim。
**再纠正(读 step-advance 源码 1287-1388):该循环 step 只 ++、永不回退 → J5 的 `4→2→1` 倒退是 REPATH 重置 path(adoptPath step=1)**,不是 overshoot-resync 回退。即:浮力 parkourDescend 入水逼近 goal 时**反复 repath**(每次重置 path+step→aim 追新近节点→yaw 摆 + 慢 wade)。与早先量到的「19 repath/段」churn 同源 = **规划层 repath 频率问题**(P1 候选①)。**✅拿到干净 repath trace(latest.log 未轮转过 J5)**:近 goal z1743-1746 处 repath 反复 `goalReached=true` pathLen 6-11、00:19:49 一秒内 3 次,**foot.y bob 64→62→61**→`fellOffPath`/`wedged`→`safetyRepath`(Walker:798)→line 876 kickoff,cheap search 同 tick 完成→activeSearch=null→下 tick 再 repath=churn。每 repath 重置 path+step→aim 追新 node-1→yaw 摆。
**❌证伪#2:safety-repath 防抖(SAFETY_REPATH_MIN_TICKS=10,gate `!pathBestEffort`)** GameTest 44/44+109/109 过,但 **LIVE J5 重跑反更差(4 窗 6.4s→7 窗 12.2s)**:①gate `!pathBestEffort` 没命中近 goal churn——**近 goal 的 held path 本身是 pathBestEffort=true**(horizon/eager-precompute 提交的),只有 search RESULT 是 goalReached=true,两者不同→防抖没作用于目标场景;②反而 delay 了干地/airborne transient 该有的 repath→新增/加长干地停顿(起步 1.6→4.0s、z1793 1.3→3.6s)。**已 revert 回 5a0ca93。**
**正确方向(下次)**:①治本=让浮力 bob 别误触 `fellOffPath`/`wedged`(noStepProgressTicks/wedged 逻辑:水中 bob 且净进展时不算 fell-off);②或防抖键于「search RESULT goalReached=true + inWater + 净进展」而非 pathBestEffort。**GameTest 不覆盖此长途水边 churn 场景(arena 全过仍 live 退化)→必须靠 live archive A/B 验证,别只信 GameTest。**注:残留是慢 wade/churn(bot 仍到达 goal)非硬冻结,中低severity。

### (旧标题保留)floating-water yaw-spin —— 已并入上条(step-thrash 才是真因)
**多条随机 journey 复发**(J4 1.8s、J5 3.5s、J0 残留):bot **浮水**(onG=false inW=true)逼近一个 +1 岸/climb-goal 的最后一段水时 yaw 摆 96-131°→thrust 抵消→1.4-2.1s 停顿。**诊断**:far-aim(c733df7)在 `climbAhead`(前向有爬升节点)时**故意抑制**(保 +2 climb-out 挂载精度)→逼近岸 goal 时 far-aim 关闭→carrot 坍缩短矢量→浮水漂移让 atan2 摆→spin。这和干地 stepUp(5a0ca93 已修)是同构问题的**水中版**,但 5a0ca93 只覆盖 `!inWater`。**正确方向**:把 bounded-lookahead(aim 下一节点、不冻结 heading)推广到「浮水逼近 +1 climb-goal」;**风险=aim 雷区**(hold-yaw 版曾 regress buoyantwallarena;far-aim 几何放宽曾 regress waterClimbOutRouteArena)→**必须干净逐tick复现(注意 walkerDebug 刷屏致 latest.log 轮转,需大日志/降噪)+ 全 44 arena + live video 验证**,别在 deep-context 仓促改。

## P1 — 规划层优化(用户 2026-06-16:「规划层也是时候优化了,删掉延后描述,随时可做」→ 不再 deferred)

**⭐ 现状更正:渐进式寻路已 substantially 实现**(见 memory [[project_progressive_pathfinding_idea]]):quick-start stub(短搜立即交段 this very tick)+渐进水面 bee-line stub+eager precompute(从 commitEnd 预搜下一段)+horizon/soft/frontier commit+idle-slice。随机长途双段均 0 执行停顿到达 → 规划层无阻塞性 bug。**剩下是效率非正确性。**

**具体优化候选(需先量化,再谨慎动 A* core;A* core=雷区,必 arena+live video 双验):**
- ⬜ **repath 频率**:单段 journey ~19 次 repath(多为 eager best-effort 从 commitEnd 预搜,2s 内提交 4 次几乎相同路径)。当预搜结果与现路几乎相同/现路仍 valid 时跳过重提交,省 CPU、减微停顿。
- ⬜ **goto 起步 latency**:量化 goto-issue→首动 tick 数(quick-start stub 已缓解,确认是否仍有可感停顿);若大,强化 stub 触发。
- ⬜ **far-goal 路径质量**:长途绕路/best-effort 段是否最优。

### ⬜ 残留小项(执行层,效率非停顿)
- z1956 首次入水 ~2s 水中绕圈(pure-pursuit overshoot 浮水)、z1885 2 深水趟水慢——本条 165 格全程已 0 停顿窗,被吸收/不再触发停顿阈值。

### ✅ video 双通道已恢复
video 转录端点已恢复 HTTP 200,本会话 stepUp 修 + 全程验证 + J1 均跑了 video 双通道(过岸 NORMAL「平稳前进/向南推进/顺利上岸」)。注意:bot 到达 goal 后 idle 停墙边时 run.py 会把静止画面报 ANOMALY「卡在墙前」——那是到点静止非寻路异常,验收时按位置/archive 区分。

### ⬜⬜ P1 新真根因(2026-06-16 随机长途 J1 跨水):XZ 水域横穿下潜河床 = best-effort partial 在 budget 耗尽时 h-主导提交深节点(非 cost-model)
- **复现**:goto xz(2480,1640) 从陆地 (2331,64,1746) 起,跨第一片水域时 bot 一进水**直接潜到 y54 河床**、沿底 swimTraverseBreak/diagDown、水下挖沙、对岸陡坡爬不上、整程 22s(442 tick)在水下、2 次镜头大摆(≈300°/96°)、然后 repath 爬回水面 y62 才平游。video 双通道全程 ANOMALY。archive replay-0002 段节点 `submergedEye:True` 连片 = **计划就走水下**。
- **证伪修(未提交已 revert)**:`waterCellTax` 二元 +80 submerged surcharge → 改 ×submersion depth(数 foot 上方水格 cap6)。GameTest **109/109 全过含 6 水 arena**,但 **LIVE 完全无效**(重跑 entry minY 仍 54、水下 442 tick)。
- **根因确诊**:`lastPath expanded=6000`(节点预算耗尽)+ goalReached=false → A\* 交 **best-effort partial**;选点 `bestSoFar=argmin(h + g/COEFFICIENTS[i])` 大 coeff 把 g 除没 → **h(纯 XZ 距离)主导** → 下潜到 XZ 更近的深节点 h 最小直接胜出,**g-cost surcharge 被除掉对 best-effort 无效**。远 XZ 目标(175 格)超 render → 恒 best-effort partial,所以「partial 走深」稳定复现。
- **正确下一步(规划层,arena+live 双验)**:①best-effort/bestSoFar **只在浮力可达(水面/陆地)节点里挑**,排除深潜节点(类比 water-start 的 `bestAshore`);②或节点扩展时**浮力可达性过滤**(陆地起点穿水不把深河床当可站);③或深潜节点加 **H 惩罚**(让启发式本身反映「浮力到不了」)。
- **教训**:per-cell g 税救不了 budget 耗尽时的 h-主导 partial 提交;GameTest 全过 ≠ live 有效(本会话第 3 次 GameTest-pass/live-fail,前两次=debounce/aim-extend)。
- **次要残留**:近 goal 浅水/沙滩**绕圈** ~35s(goal XZ 在水/未载入对岸,贴水边 gdist 31↔39 摆才挤过去)。
- 工具:`scripts/journey_runner.py`(位置稳定轮询的链式 journey runner,免 cancel-storm 误报)。
- **本轮净提交=0(诚实负结果),树洁净 HEAD 00b8dcf。**

### ⬜ P1 水域横穿:第 2 次尝试(buoyancy planning gate)也证伪 → 真修在执行层(swim-up)
- 实现 `divesPastSurface`(PathFinder 硬 admissibility gate:XZ goal 下潜 move,`to` 上方 3 格全水=水面够不到→拒绝;ignoresY+diveGoal 豁免)。修正版(查「水面是否够得到」而非绝对深度计数,避免深水探针饱和)**GameTest 44/44 全过**,但 **live 更糟**:bot 在土墙陡岸 y51-53 卡 ~40s + 水底挖泥(baseline 是下潜后 ~50s 自己游过)。
- **真根因**:bot 从 y64 悬崖 stepDown 进水靠**下沉/坠落物理**直沉 y59→y51,planning gate 拦不住物理下沉;又禁了 A\* 规划顺陡岸绕行 → 岸底 wedge。
- **真修方向(执行层)**:浮力 bot 在深水(eye 远低于水面)时 Walker 主动按上浮键游到水面,再按 path XZ 在水面推进(忽略 planned Y 深度)= autoSwim/swim-up 域。次选:规划层选更平缓入水岸。
- **本会话水域横穿净提交=0**(cost-surcharge + planning-gate 两次均证伪 revert)。GameTest 全过≠live OK(第 3、4 次)。树洁净 HEAD 00b8dcf。

## 2026-06-16 随机长途 787 格旅途 SUCCESS + 干地对角楼梯 YAW 锯齿根因(数据坐实)
- **旅途**: goto XZ(1850,2150) 距起点(2482,1681)~787 格,跨山/水/林。poller 报 ARRIVED t=293s dist=3.3。全程强力净推进,所有视频异常均为 transient 边界 episode 并自行恢复(逐 tick 采样佐证:水中游动 yaw 恒定 48°)。**端到端成功**。
- **archive 定量(replay-0002-...073032.json, 8679 ticks)**: mean|Δyaw|/tick=2.06°(整体丝滑)。最严重 yaw 抽搐窗**几乎全在干地 inW=False y76-92 的陡对角爬山段**(rev=5/20t、sum|Δy|~120°),唯一水窗是起点 launch。
- **隔离重跑爬山(TP 山脚→goto 山顶,3Hz 实测 pitch+yaw)**: **pitch 全程 = +0.0**(纹丝不动)→ 视频「仰视天空↔地面」是误读(浮空岛 scenery+yaw 扫视+step bob)。YAW raw 在 380-485(~100°带)反复摆,单样本 ±20-32°。
- **根因**: 对角楼梯交替 walk(aimAtWaypoint=false→carrot)/diagUp(aimAtWaypoint=true→精确近 wp)步,**aim 源每步切换**→targetYaw 跳 ±20-30°,smoothTargetYaw EMA(alpha .5)只砍一半→持续游走。carrotPoint 在楼梯上被 riser 挡 LOS(losWalkable break)塌缩成近节点,也给不了稳定远 aim。
- **FIX(Walker.java,attempting,pending live verify)**: 镜像已验证的水域 far-aim——干地爬 GENTLE 楼梯(只上、无 >2 上跳、无下降)时瞄固定远节点 path.get(step+3),forward-dot 守卫防楼梯拐弯瞄反。GameTest 守门 ascentSpeedArena + 44 arena,live 重跑爬山验 yaw 带收窄。
- **残留(未做)**: 终端 radius-0 XZ 目标在悬崖上 bob/dig 无法收口(部分是 radius-0 选择产物);anti-spin freeze 等保护是 isInWater 门控、干地近目标振荡无保护。

### 2026-06-16 续:楼梯 far-aim fix LIVE 否决 + REVERTED(第5次 GameTest过/live不灵)
- fix(干地 gentle 楼梯瞄 step+3 far 节点)GameTest 109/109 全过,但 **live 重跑同一爬山 yaw band=109°≈baseline ~100°,reversals=19,bigSwings=9,无改善**。爬升段 yaw 仍狂摆(36→71→68→104→48...±35-56°)。**已 git checkout revert,树回 00b8dcf**。运行 client 二进制仍含该 inert fix(无害,GameTest 净、爬山正常),下次 relaunch 复 baseline。
- **深层根因升级**: 山路 A* 路径是**之字形**(cardinal 步逼近对角)→ 任何**单节点 aim(近 or 远)都随之字摆**;step+3 far 节点 3 步之字仍摆。水域 far-aim 有效因水路直。
- **真正方向(未做,需迭代调参+视频在线)**: 不瞄单节点,瞄**路径趋势方向**(对未来 K 节点做位移平均 / 更大 lookahead 平掉之字 / 对一致源加强 EMA 平滑)。需多轮 build+relaunch+climb-poll 定量迭代(yaw band 指标),且 pitch 全程 0 无需碰。
- **注**: 2026-06-16 当次 vision 端点 HTTP 400 连续失败,视频通道临时不可用,只能靠 RPC yaw-poll 定量;迭代调参待视频恢复。

### 2026-06-16 续2:第2次随机远途 SUCCESS + 终端 radius-0 settle 尝试(罕见/不可复现→REVERTED)
- **journey2**: goto xz(2600,2400) ~707格 → ARRIVED t=431s dist=0.6。中途一次 ~15s 水岸 climb-out 真停顿(自恢复),其余强力净推进。**两次随机远途均端到端到达**(j1 787格/dist3.3, j2 707格/dist0.6)。
- **pathChart(j1, pathchart-0002-...169.png)已读**: 轨迹紧贴 plan 直线对角,heading 实际 yaw 紧贴 target bearing,avgSpd2.72。maxYawErr=180° 峰值在终端 radius-0 绕圈缠团。**整体丝滑**。(hook 称 pathChart 未生成=误判,实际 auto-dump 在 debug/pathchart-*.png)
- **视频 over-read 再确认**: j2 多次报「高频急转/原地打转/死循环/潜水浮出」,但逐tick位移=稳定强推进 or 到达后 y 恒定 idle。**video 在浮空岛+水域地形把合法转向/idle bob over-flag 成 ANOMALY**(同 pitch=0 却报「看天」)。客观仪器(pathChart/archive/RPC poll)才是 ground truth。
- **终端 radius-0 settle fix(REVERTED)**: 加 near-goal-settle(ignoresY 目标 d≤16 且 totalTicks>100 无改善→ARRIVED,复用 bestDistToGoal 单调 bob-proof)。GameTest 109/109 clean。但**3 次 live repro(水柱/干地高柱/平台)全部无法触发**——因 XZ goal 忽略 Y,bot 只要任意高度 foot 块落到 (x,z) 列即 goal.reached,「精确列任意Y都够不到」极难构造,实际 bot 都正常到达。→ **无可观测证据它触发,按 fix_verify_discipline REVERTED**。终端 churn 比判断的罕见;视频终端 anomaly 多为 idle-water-bob 误读。fix 逻辑严密+GameTest clean,待确定性 arena 触发器再用。
- **本会话净提交=0**: 价值在诊断+验证(两次旅途证明大地形整体丝滑可达 + 精确刻画残留 + 证明 video over-read),非新提交。残留真问题均为高回归雷区(水岸climb-out/climb-aim)或罕见(终端radius-0)。

### 2026-06-16 续3:settle arena 证明 settle 干地冗余 → REVERTED(关键认识)
- 建 nearGoalColumnSettleArena(5格实心柱占目标XZ列+place/break off)试图确定性触发 settle。45/45 通过,但日志 step=ARRIVED **ticks=85 < NEAR_GOAL_SETTLE_TICKS=100** → settle 路径**未执行**;且 pos y=215.3=bot 爬上柱顶在顶端够到列→goal.reached 正常触发。
- **关键认识**: 干地上 Walker 现有 best-effort/frontier-arrive 对不可达 XZ 目标**本就快速到达(tick85)**,我的 settle(>100tick)**干地冗余**;只对水中浮力 bob 终端 churn(现有 water-churn-giveup 偶尔不触发那种)有用,而那个**无法在 arena 确定性复现**(水+浮力+恰好差1格)。→ 无法可观测证明 settle 独立生效(arena 证明的是它**没执行**),REVERT。
- **47Hz 高频实测(回应「仪器太慢视频才对」)**: pitch range=0.0(视频「看天」=把浮空岛误读)、yaw 反向仅1.3/s(非高频抖,是跟山路165°平滑大转向)、水中idle 0反向完全静止。20Hz archive 终端 y冻结。**客观仪器是ground truth,video高召回低精度需逐条核实**。
- **本会话定论**: 净提交0。架构可行(两次随机远途787/707格均端到端到达、pathChart丝滑、47Hz无高频抖)。残留=水岸climb-out偶发停顿(高回归雷区)+终端radius-0(干地已被现有机制处理,水中罕见且不可确定性验证)+蜿蜒地形合法大幅转向的「镜头晃」观感+video误报。这些跨越本会话修复范围/风险/可验证性。

### 2026-06-16 续4:20Hz三重交叉定位唯一真停顿(修正前面过度乐观)
- **方法论修正**:停顿检测**没有单一指标可信**——①XZ位移:把陡爬(y92→100、XZ小)误判成停顿(假阳),也会被repath重置的step骗;②step索引:repath时重置→大netXZ的"step停"是假象;③真停顿判据=**step停滞 AND netXZ<~5 AND |Δy|<2 三者同时**。我之前8s稀疏轮询+单指标多次得出错误结论(把~100s水岸churn判成"~15s自恢复"、把陡爬判成停顿)。
- **唯一真停顿(journey2, 20Hz三重交叉确认)**: **ticks 3349-5334 ~100s 在 (2372,63,2168) 水岸**——step卡4-8、netXZ 1-9、y平62-63,持续churn stepUp/stepDown/diagDown/fall2(inW 47-93%),~100s仅漂~6格才磨出。**真实严重的水岸climb-out停顿**(=trend-aim破过的waterClimbOutRouteArena同域雷区)。
- 其余archive"停顿"全是假阳性:陡爬山(y在升)or repath重置step(netXZ大几十)。
- **下一步(需新预算)**: 建确定性**水域climb-out arena**(深水池+tall bank,bot须爬出)复现这~100s churn,谨慎修(别回归waterClimbOutRouteArena)。这是剩下**唯一的真bug**;其余"异常"=陡爬合法转向观感+video误报(47Hz已证伪)。

## 2026-06-16 随机长途#3 (2600,2400)→XZ(1820,3150) ~1080格 — 决定性诊断(archive replay-0005, CANCELLED@22min)
全程 1335s 中 **749s(56%)在停顿**。开 live video(run.py)+47Hz hfpoll 双通道核实。
- **pitch 全程恒 0**(多次 6-77Hz poller distinct=1):视频反复报「镜头高频抖动/俯仰跳变」物理不存在=over-read。yaw 0.9-1.3 rev/s 平滑跟路。**video 高召回低精度,以 archive/hfpoll 为准**(再证)。
- **Zone A 游泳"卡顿"= 误报**:walk-in-water 1.1 b/s 慢速连续推进,被我 60t/4.5格 阈值误标(非真停顿)。
- **Zone B = 真 bug:badlands 红土水盆 climb-out 死锁**。bot 在 dist 332-386 振荡 18 分钟零净进展。单段停顿 **72.5/44.2/40.1/31.6/30.6/22.6/20.6/20.3/18.4 s**。
  - **A* 两岸振荡**(seg#74/76/77):每 repath 从 bot 当前岸 fall2/fall3 主动坠回水→横游→爬对岸 y64↔71;目标在水盆外 SW,两岸交替"更近目标"→永远弹跳。复杂盆地每 repath 烧 6000–**60000**(满上限)节点→搜索冻结即大停顿。
  - **72.5s 逐 tick 铁证**:bot 死钉 (2023.3,62,2877.7) **yaw 恒=0**(朝 +Z 南岸)move=walk onGround 1↔0 水面bob，**aabbOverlap=0(非卡墙)**，step 钉2。每 200t repath 重提**同一向前顶岸路径**→破岸兜底(Task#36 node.Y==foot.Y)在此几何**未触发**→钉 65s，靠 repath 随机性 yaw 翻 304° 才逃。
- **根因假说**:浮力 bot 水面顶**高于水面 2-12 格的红土岸**(badlands mesa basin)，水平水节点 walk 顶岸 riser 浮力爬不上，bank-face 破岸/pillar 兜底在「深水盆+高 mesa 岸」未生效；A* segment-commit 被 XZ-goal 骗（两岸都"更近"）→bank 振荡。
- **下一步**：建确定性 arena（深水盆+南向 tall 红土岸 y64-71+bot 浮 y62 朝岸+goal 岸外）复现 65s 顶岸钉死 → walkerDebug 定位为何 yaw 锁 0 不破岸 → 修（破岸兜底覆盖此几何 / 无净进展更早强干预）→ GameTest 109/109 + live 重跑。**别回归 waterClimbOutRouteArena/deepWaterClimboutDriftArena**。
- 残留次要：Zone A 水中 1.1 b/s 慢（可选提速）。

## 2026-06-16 真根因确认=spinFreeze(推翻 far-aim/死区两假设)+ 修复
用 live DIAG 日志(临时加在 Walker aim 块,打 aim2/heldYaw/bearing/flag)在真盆地复现实测:
- aim2 恒 >dz(4.0)→**dead-zone 无关(假设A否)**;从无 DIAG-FARAIM→**far-aim 无关(假设B否)**。
- heldYaw 冻死常数(-167°→69° 恒 500+tick),bearing 算得对(指 waypoint)却不转,foot 钉死,noStep→498。
- **唯一能在 angleDiff 巨大时冻 p.getYRot() 的=spinFreeze(Walker.java ~2250):`overWater && repathsNoProgress>CHURN_REPATH_CAP(3)` gate 掉 yaw slew**。churn over water→冻陈旧错朝向→死推岸→零进展→repathsNoProgress 持续→无限死锁。
**修复(已改 Walker,待 GameTest+live 验+commit)**:spinFreeze 加 **target-stability 门控**——`lastAimYaw`/`aimStableTicks` 跟踪 smoothed aim;`targetFlipping=aimStableTicks<AIM_STABLE_TICKS(10)`;spinFreeze 仅在 `targetFlipping` 时冻。winding(每 repath 翻 180°)→aimStableTicks 重置→保持冻结(anti-spin 保留);本死锁(bearing 稳定)→~0.5s 后释放→capped slew 收敛转向 waypoint→逃脱。常量 AIM_STABLE_DEG=8°/AIM_STABLE_TICKS=10。
waterFarAimBankCornerArena=水域隔墙绕行 smoke test(不确定性复现 spinFreeze,真验证靠 live 真盆地)。
**教训**:连续两次静态分析假设全错,DIAG 日志是关键。水域 yaw 冻结 bug 必 live 打 aim 内部状态实测。

## 2026-06-16 续5:spinFreeze 死锁 ✅修复提交 16c5a8c + 3次干净 live journey 验证
- **真根因再升级(超出续4假设)**:spinFreeze 不只冻镜头——line2579 `driveDelta = spinFreeze ? 0 : angleDiff(...)` **把驱动方向也冻成 0**→身体沿冻结的错朝向死推岸。恶性循环:冻驱动→零净进展→repathsNoProgress 持续高→spinFreeze 持续→冻驱动。这才是 18 分钟死锁的执行层闭环。
- **修复(2 处,均针对此死锁)**:
  1. **解耦驱动与镜头冻结**(line2579 去掉 `spinFreeze ? 0`):身体始终朝 aimYaw 走(Δ=aimYaw−cameraYaw,AvatarInput 动态纠偏)。镜头仍可冻(防视觉转圈 winding),但身体一动就有净进展→repathsNoProgress 重置→冻结自行释放→循环无法形成。
  2. **target-stability 门控镜头冻结**(aimStableTicks/AIM_STABLE_DEG=8/AIM_STABLE_TICKS=10):仅在 aim 目标真翻转(winding)时冻,稳定~0.5s 释放。
- **验证**:GameTest **109/109**(水域 climb-out arena 全过,无回归);**3 次干净 live journey**(replayMode 清后):
  - #1 (1853,3000)→(2298,2703) 580格干地:mean|dyaw|1.92°、yaw span 456(非winding)、最长停顿1.3s、到达。
  - #2 →(1960,2884) 西渡 lake 7%inWater:**水中 mean|dyaw|2.64°、in-motion >90°flips=0(零winding)**、最长停顿1.5s、穿水到达。
  - #3 →(2400,2400) 700格:mean|dyaw|**1.16°**、终端 last4s net19/yaw125=clean、到达后 hfpoll 8s **YAW=0 完全静止**。
- **复现教训**:'ScriptTest' world **跨 reload 非确定性**(地表整体 y≈-60 低海平面,原 basin 的 y57-77 抬升岸 reload 后消失)→mc.debug.replay 恢复悬浮岛 bot 穿地坠 bedrock、TP 复现全失败。basin 抬升岸 climb-out 自然复刻不可得→靠 GameTest 抬升岸 arena 覆盖。
- **video backlog 教训**:vLLM 恢复后积压致视频报告滞后数分钟,大量"村庄打转/静止"是**旧帧滞后误报**;追上后与 hfpoll(YAW=0)一致报"静止"。再证客观仪器(archive/hfpoll/RPC)是 ground truth。
- **残留(非 spinFreeze 域,独立)**:终端 radius churn——goal 不可达 near 时终端 settling 偶有~2.5s/392° 打转 burst(间歇:#2 有、#3/re-test 无),干地非 overWater→与 spinFreeze 无关,属 task#35 settle 域,settle 修曾 REVERTED(不可确定性复现)。

## 2026-06-16 续6:video 通道真根因=qwen小模型 → 换 gemini 修好 → 双通道清洁达成
- **续5「video 不可靠」结论被推翻**:video 所有假异常(把 net75格/窗移动报「静止」、对 hfpoll YAW=0 的 idle bot 报「180° winding」)真因=**OPENAI_MODEL=qwen3.6-27b-nvfp4 小视觉模型能力不足**。
- litellm 端点有强模型(gemini-3.1-pro/flash-preview、claude-opus-4-5、gpt-5.4…)。改 run.py line53 `MODEL=os.environ.get("OPENAI_MODEL") or ENV["OPENAI_MODEL"]` 后用 `OPENAI_MODEL=gemini-3-flash-preview` 启动。
- **gemini 完全正确**:移动报 NORMAL 且描述精确(「红沙纹理向后滑动、陶瓦山体相对位移=稳定前进」「转向轻微平滑、无停顿无打转」),idle 报「静止」,双向准。
- **✅双通道清洁 journey 达成(满足验收)**:replay-0008(2197,2741)→(2040,2860)穿平原/树林/水域/badlands:**archive mean|dyaw|2.30°、flips>90=0(零winding)、最长停顿1.3s、到达** + **gemini 全程 NORMAL=12 / ANOMALY=0**。另一程(→2200,2740)gemini 亦全 NORMAL。
- **教训**:① 视频验证前确认用强视觉模型(gemini/claude/gpt),别用 qwen3.6-27b;② 平坦无特征地形仍是视频弱区(第一人称水平视角画面变化小),走有地标地形(badlands/树林/水域)最可靠;③ gemini-3-flash 比 16s 窗稍慢会积压滞后,可接受。
- **本次寻路丝滑回归收官**:spinFreeze 死锁(16c5a8c)+ 之前各项水域/yaw 修复 → 多次随机长途双通道(pathChart+gemini video)均无异常、端到端丝滑到达。

## 续7 (2026-06-16) — 回放工具修成可忠实复现 live (a44c3e5)
- ⚠️ 揭穿:上轮"丝滑验收"跑的是 ScriptTest **超平坦**世界(无效)。改用一直在测的 **Mountains 存档**(真山地),bot 在水边 climb-out **完全不丝滑**(用户:一直试跳1格岸)。
- 用户指令:录制/回放工具就是为复现 live 而建,用不了先修工具 → 修 4 部分:
  - A/B: EnvelopeCell 加 state(全blockstate SNBT)+nbt(block entity);ReplayTool 全保真还原,v1档fallback。
  - C: envelope 采样 dy 加深到 -3(实心地板,自包含还原不穿地)。
  - D: replan(默认)忠实重跑——还原地形+TP起点+从header重建原goal+正常规划(A*确定性→精确复现live涌现bug)。
- 验:GameTest 109/109(含state/nbt round-trip);Mountains live replan回放 replay-0002 **精确复现水边churn同格(~1823,2738)**;v2档 state覆盖39242/39242。
- 真bug(task#42 待修):水边climb-out每道+1岸先bob-stall+挖岸数秒才脱困=慢且丑,规划层应优先平齐出水口(flaky waterClimbOutRouteArena 域,待de-flake)。

## 续8 (2026-06-16) — 水边 climb-out 源头修复:浮力门 (ef3e497)
- 用户「从源头修,规划不该给出这样的路径」。三层根因:canStandAt 把任意水深当可站立 + Move 不查浮力 + 水域cost多只对XZ-goal生效。教训(fbde0f7):别动全局canStandAt,在Move层加浮力门。
- 修:`WorldView.isFloatingWater`(水格+下方也水=深水浮着)+ StepUp/StepUp2/DiagonalAscend 从浮力水格一律 invalid → A* 走平齐 walk-out 或破岸挖到平齐(均可执行),不再产不可执行的 +1 stepUp churn。
- waterClimbOutRouteArena 更新:+1 出口结构性禁止,浮力 bot 无论 tax 走平齐(maxY≤wsurf)。
- 验:GameTest 109/109+45/45;Mountains live (1823) **沉底+bob-stall 6s+diagUp振荡 4.4s 全消失**,保持水面 y62 流过,dist 185→84(60s)收敛。
- 残留:① 深水下沉(diagDown 入深水未gate,更微妙=下降有时合法潜goal)② 终端不可达goal churn(本goal在山上radius3够不到)③ real-Walker arena flaky(并行批次共享BotConfig,待de-flake)。

## 续9 (2026-06-16) — 深水高石崖「潜底爬岸」虚构修 (0408b2e)
- 新鲜可达随机长途(Mountains (1697,2611)→(1730,3100) **到达**)在 z3022 深水岸出现 ~30s 真停顿(视频实时:岸边反复上岸失败/卡崖底死角/剧烈抖动)= task#44 在可达路径复现。
- 地形(mc.query 实测):bot 向 +z 游过 6 深水池(水面y62),撞 z3024 整面石崖(stone y60→65+),岸顶 crest y63 在水面上方+1,空背包。
- 真根因:**ef3e497 浮力门副作用**——岸顶在水面上够不到平齐口,A* 改选 `SwimAshoreBreak` 节点放 **y59 软泥**(escapeBreakCost 比 y63 石头便宜)→潜底破软泥岸;但浮力 bot 水下蹬不起 jump-mount=212 tick churn(视频:游出水面又钻回岸下挖泥)。首猜 stepUp-dive 打错(submerged-ascent 门 peak287 无改善)。
- 修 0408b2e 两道源头门(「浮力 bot 水下不能上爬」):
  - `WorldView.isSubmergedAscent`:StepUp/StepUp2/DiagUp 目的地仍全淹没→invalid(防 stepUp 潜底,defensive)。
  - `WorldView.isSubmergedFoot`=isWater(foot+2):**SwimAshoreBreak/SwimBankClimbBreak foot≥2低于水面→invalid(须贴水面 jump-mount)= 真正生效的修**。
- 验:GameTest 109/109+45/45(深水穿越 arena 无回归);live A/B z3022 **深沉 churn 消除**(水中 peak totStuck 252→43,bot 贴水面 walk+水面破岸,沿水线东移找口,翻坡到达 y84)。复现台:snapshot id=bank3022 + tp 1741 68 2986 + goto xz(1733,3090)。
- 残留:高石崖空背包**近处无出口**→沿水面东移找口仍 ~5-8s 间歇停顿(peak~162)。进一步丝滑=规划层 exit-finding 更快锁定东侧低/坡口(heavily-tuned 区慎动)。

### 续9 补 — 回归验证 + 瀑布水帘 deadlock(0408b2e 无辜)
- 0408b2e 后向 W ~480 格新长途验证:**水域穿越无回归**(深水岸深沉没复现)。
- 但 Mountains 暴露多个独立 churn 源:① 陡干山 stepUp ~15s(已知 dry-staircase 残留)② **山顶多级瀑布水帘 deadlock**:bot 在水下做 121 次 undW=true 的 stepUp/diagUp(浮力蹬不起,dY 缩不进)+ fallWater15 反复下坠,totStuck 冲 2292、围 x~1495 死循环不脱困。
- **归因:0408b2e 对瀑布无辜**——该处 swimAshore 出现 0 次,我的门要求「to+head 都水」严格条件,瀑布水柱破碎→门不 fire→A* 路径同改前。
- **更广修方向(未做,需谨慎 A/B + 动深水穿越精调平衡)**:门改「from 头淹没(isWater(from)&&isWater(from+1))就禁 ascending stepUp/diag」可同盖岸+瀑布。但瀑布是 3D 复杂 feature,禁水下 stepUp 后可能 no-path/绕行,未必修好,投机性较高。

### 续10 (2026-06-17) — z2744 descent 侧根治(4ecb972)+ 整程 replay 到达
- 用户坚持用 replay 验证(对的):`mc.debug.replay replay-0004 replan` 重跑原 journey 揭出**改前在 z2744 死锁不到达**(swimUp918/diagDown200,totStuck1154)。
- 根因不对称:Fall/FallIntoWater 早 gate「降落淹没水格」,DiagonalDescend/StepDown 没有→A* diagDown 把浮力 bot 路由进深水缝,浮起→swimUp↔diagDown 振荡。
- 修(镜像 Fall.java:39):diagDown/stepDown.valid() 加 `if(isWater(to)&&isWater(to.above()))return false`。
- **验:完整 replay-0004 确定性重跑——z2744 死锁1154→peak5、z3022 岸51、端到端到达(1732,83,3097)**;GameTest 109+45 绿;局部 A/B slotPEAK3。
- 残留 z3034 东侧出口:~20s sink(totStuck394 自恢复),机制=**A* 把 WALK 节点路由在 y55-58 池底**(非 descent move),浮力 bot swimUp↔floor-walk 振荡=535df12「A* 路由河床」残留。修方向(风险):gate Walk-while-submerged(破潜 goal 横移)或调 waterCellTax/submergedTax(heavily-tuned)。

### 续11 (2026-06-19) — 终点海湾 deepwater 潜底 churn 根治(1705097+4017fa1)+ LIVE 端到端到达
- 陡高山 churn(3f287cb/4305919/e33e6ab)修好后,残留终点海湾 deepwater 潜底 churn:bot 在 goal 前 ~9 格深湾(对岸 y64-74 岸坡)潜到 y57 反复挖沙坝打转不过岸(三通道确认)。
- 精确诊断(walker per-tick log):planner committed path = `…swimDown swimDown swimTraverseBreak`——**潜下2格 + 挖穿水下沙坝(y60 solid)**;浮力 bot 挖水下 solid 浮起离块卡死(pos 冻 60.00 数十 tick)→re-route→再潜→churn。`mc.debug.plan` chain 证从水面 reached=true 全程 y62-63 水面路可达。
- 修1 `1705097`:**SwimTraverseBreak surface-gate**——eval 开头 `if (w.isWater(from.offset(0,1,0))) return null`(头在水下=潜底挖,拒绝;本 move 是「水面 lip 凿穿」)。GT 109/109。
- 修2 `4017fa1`:**escalation 在水里也 arm**——撤 e33e6ab 的 `if(!p.isInWater())` land-gate。纠错:e33e6ab 写「depthPenalty 推 bot 下水」是反的,depthPenalty 给下潜加税=**抑制潜底**;潜底挖沙坝路已被修1 gate 掉。GT 109/109(50_scene flaky 重跑绿)。
- **LIVE 双跑(tp 1732,64,3428→goto 1730,3500):①全局旋钮 水面穿越上岸 ARRIVED;②reactive 默认旋钮=水边churn~20-25s→`anti-churn(water)→escalation ARMED`→z3433→3477 上岸 y65→z3498 goalReached=true ARRIVED,全程水面无潜底。** 复现台:tp 1732 64 3428 + goto xz(1730,3500)。
- 残留:① reactive escalation 起步 ~20-25s edge-churn(churn-window 检测延迟;真丝滑需 proactive/常开水域 escalation)② 游泳中 yaw-spin(视频「水面反复剧烈转向」)③ 翻山执行器陡面 stepUp stutter。
- **残留①试过 `CHURN_WINDOW_WATER=200`(水里 10s 窗口提前 arm)→ REVERTED**:GT 109/109 但 live 更糟,charge/back-off 频率翻倍(escapes 1→4 vs 1→2),bot 仍 churn ~45s 才过岸——arm 提前≠过岸提前,缩窗口只增扰动。真修需 proactive(进深水前预判抬高岸坡),非更快 reactive。

### 续12 (2026-06-19) — 渐进式寻路 active + PROACTIVE pinch escalation = 水岸 churn 真解
- 用户拍板「开渐进式寻路大改 / 删掉 deferred / 随时开始攻」。Plan agent 勘明:quick-start stub/水面 bee-line/eager precompute/horizon/soft-commit 早已是渐进雏形。
- **land bee-line(432b359)**:tryWaterBeeline 的干地孪生(贪心同-Y standable march),flag `pathfinderProgressive`。LIVE 验证=Mountains 太碎触发 0 次(quick-start 已覆盖平地起步),**关键否定发现:greedy coarse stub 只治平地起步冻结,治不了 pinch churn**(粗方向正指障碍、撞上就停)。
- **⭐PROACTIVE pinch escalation(2a3915e,默认开 af5bba4)= 真解**:`maybeArmPinchEscalation`(big-search adopt 点)——大搜回来 best-effort 且 commit 段 goal 进展 `<PINCH_MIN_PROGRESS(8)` 格 = planner 卡 pinch,**当场 arm 深搜 escalation**,不等执行器 churn ~20s 反应窗。健康段(horizon≈48格)永不触发。
- **为何赢过 faster-reactive**:reactive 让坏 pocket 段先提交+bot 先 churn 再 undo;proactive 在坏段提交那刻就 arm,下一搜直接出绕障路。
- **LIVE A/B(深湾 tp 1732,64,3428→goto 1730,3500)**:proactive arm @~6s(首 commit 倒退-36格)vs reactive 20-52s;**开阔水面视频转 NORMAL(起步 churn 消除)、ARRIVED ~54s vs reactive ~162s(~3×快)**。GT 109/109(flag ON 也验)。
- 残留:对岸岸坡/沙坝近处独立 pre-existing pinch(「沙丘横移/水下死角」);land bee-line 留作平地 biome 件。

## Schema 单源校验 backlog (2026-07-10, master d149da5)

- [ ] mine/goto 的 `radius` schema 收紧为 `integer(1,64)`(现为宽松 number;route 实际按 int 半径用)——单独 conformance 小扫。
- [ ] 未来 conformance 清扫必须 grep `resources/scripts/**` 全部 `Driver.invoke(` 调用点(playbooks/prelude,不止 validation 套件)——本轮终审在 dragon.js/wither.js 抓到错键静默瘫痪(空 catch 吞 IllegalArgumentException)。
- [ ] 环境:ScriptTest 世界 spawn (-301.5,94,291.5) 下方虚空柱,套件收尾 tp 必摔死 → 下轮脏状态假败;考虑 setworldspawn 挪点或补地。50_scene forceload 泄漏(终审 Minor)顺手看。

## 生存跑 gap 清单 (2026-07-11, SurvivalTest 重启跑 day1-2 实测)

- [x] 🔴 **walkerWallDigFallback 无 allowBreak 门**(批修#25 已修,Walker.java:4858 现有 `&& BotConfig.allowBreak`)。
- [x] ✅**plan.acquire/recipe.resolve 木种"硬编码"oak GREEN(07-12,#37)**:根因非字面 oak 常量,是 `RecipeResolver.chooseIngredients` 只对**直接库存**(tier1 line192)inventory-aware;tier2"first craftable"按 `Ingredient.getItems()` registry 序(oak 首)取,**不看该成员自身子输入是否在库存**→有 acacia_log 无 planks 时选 oak_planks→递归缺 oak_log(acacia_log 闲置)。修=插 **tier2 `craftableFromStock`**(accepts 中第一个可 craft 且其配方有一项 accepted 输入现于 have 的成员;one-level=木/dye/石铜变体都一步到底,recursive 是 YAGNI+复刻 expand 遍历;quantity-agnostic=species 跟**在场**非丰度,不足则报对的 leaf missing 非退 oak)。**严格低于 tier1**(advisor 反例:have={acacia_planks:1,oak_log:64} 单循环会误取 oak——四 tier 保序:直接库存>craftable-from-stock>first craftable>first accepted)。孪生 `distinctIngredients:206` 同 `accepts.get(0)`=oak bias 但只喂 `pickRecipe` **scoring** 非执行/missing 路径,wooden_pickaxe 单配方咬不到→**有意保留**(此注即决定非疏漏)。TDD:pure `RecipeResolver` arena `serverRecipeSpeciesArena`(acacia_log:8→RED `missing={oak_log:8}` jobs 全 oak;fix 后 GREEN `missing={}` jobs 全 acacia)。非回归:全套件 **RPC 132/132 + serverCraftArena(oak 锚)plank=4 + 新 arena 全绿**;唯一 required fail=horizonarena(pathfinding,与 recipe 因果无关,本 session 4 跑 flaky 每跑不同 arena=既有水/寻路 flaky 家族)。**⭐LIVE truth-verify(advisor 逼:测的是 resolver 单元非 user 撞的 RPC verb,delta=readHave/serialize 信封)**:relaunch client 新 build,同 verb 直调 OLD→NEW before/after=OLD `mc.recipe.resolve{acacia_log:8}` 全 oak+`missing=oak_log`;NEW `mc.recipe.resolve` 全 acacia `missing:[]` + `mc.plan.acquire`(报告点名 wrapper)`feasible:true unobtainable:[]` 同 acacia 链。end-to-end 真绿非仅 arena 绿。
- [x] ✅**craft 不把 crafting_table 纳入子配方树 GREEN(07-12,#38)**:3×3 配方缺台时报"需要工作台"而不自动 craft 一个(台的料明明够)。根因=`RecipeResolver.expand`(:114)只把 `station(r)` 塞进**描述性** `stations` 集,**从不注入获取台的 job**,且 inventory-blind(有台也照列)。执行层 `CraftProcess.setupStation`(:161)→`findTable`(世界内已放置的台)→否则 `placeTable`(需**背包**有台 item)→两者皆无=硬 `fail("需要工作台")`。**Live 双基线定调(advisor 校正:世界台是判别器不是小事)**:Case N(无台/背包无)=fail;**Case W(旁边有放置台/背包无)=经 findTable 成功**——naive "have 里没台就注入" 会**回归 Case W**:白费 4 planks,更糟=村庄台旁 6 planks 造镐本可行却被报 `missing`→**feasible 契约由真变假**。修=**世界感知 station 注入**:①`RecipeResolver` 加 6-arg `resolve(..., availableStations)`——3×3 job 需 crafting_table 且**不可用**(不在 `availableStations`、不在 `have`、本次未注入)时**注入一次** `crafting_table` job(其自身配方是 2×2→**无鸡生蛋**),`provisioned` 标记使兄弟 3×3 job **dedup 复用**同一台;②`CraftProcess.availableStations(p,lvl)` = **单源** reach 检查(复用 `setupStation` 同一 `findTable`)——planner 与执行器必须用**同一把尺**,否则计划说"不用台"而 setupStation 却失败(或反之误报 infeasible);③`CraftProcess:121` + `RecipeApi.resolve/acquire`(经 `botPlayer()`)+ `AcquireResolver.plan` 6-arg 全部喂同一信号(⚠️5-arg 默认注入=world-blind,故**报告 verb 必须**传世界信号,否则把 advisor 警告的 feasibility 回归**引进报告层**)。TDD:`serverCraftTableInjectArena` RED(`jobs=[planks,planks,stick,pickaxe] stations=[crafting_table]`=台被列却无 job)→GREEN(`jobs=[...,planks,crafting_table,pickaxe]` 台在镐**之前**、恰 1 个、`missing={}`;+库存有台则**不**重复注入)。**LIVE 双验证**:Case N=事件流 planks(4,8)→stick→**planks(10)→crafting_table×1→wooden_pickaxe×1**(自造台后成功,原为硬 fail);Case W=两 verb 均 `TABLE STEP PRESENT? False`、craft 成功、事件流 planks **停在 8 且无 crafting_table**(零冗余台)=判别器守住。非回归:全套件 **83/84 required**(唯一 fail=`deepwaterclimboutnoblockarena`=既有 flaky 水域 ascent 家族)。⚠️**新知**:full suite 时**若 fabric client 还开着**会抢 CPU→水域 timing-sensitive arena flaky 从 1 涨到 3(关掉 client 复跑即回落 1)——跑套件前先关 client。
- [x] ✅**craft 放置的工作台用完不回收 GREEN**(#276,task#40,2026-07-12;memory `project_engine_craft_table_reclaim`)。根因=`CraftProcess.placeTable`(:263)放台后,终态(`DONE`/`FAIL`,:97-107)**只 closeContainer,从不破回**→台留原地=每个 craft 点白烧 4 planks + 世界留垃圾。
  - ⭐**安全不变量(设计的命门)**:`tablePos` **也**会被 `findTable`(:168)赋值(村庄/玩家基地已有的台)→ 那句诱人的一行修法「终态破 `tablePos`」**会拆掉 bot 只是借用的别人的台**。∴ 新增**独立**字段 `placedTable`,**只在 `placeTable` 真放成功的那一个赋值点**写入,且**只有它**可以被破。
  - 修=新 `St.RECLAIM` 态 + `BotConfig.craftReclaimTable`(default ON,四处单源:BotConfig/SettingsCommand/SettingsSnapshot/BotTools schema+doc)。终态(**成功和失败都要**——放完台才失败的 craft 一样留了垃圾)→ closeContainer → selectTool → aim+breakHold 直到块消失 → 停手等 vanilla **10-tick 拾取延迟**(否则下个 process 把 bot 带走,掉落物丢在原地)→ 收尾。**best-effort**:回收超时(200t)/破不动**绝不**把成功的 craft 翻成 FAIL,也**绝不**覆盖 FAIL 的原 error。不走 Walker → 天然绕开 #34 `mayBreak` 门(回收自己放的台 ≠ 那些门要禁的凿世界;而且把台留着才是"改世界",回收=还原)。
  - ⚠️**测试形态(advisor 定,先想清再写)**:服务端 FakePlayer **开不了菜单**(`setupStation`:172-174 capability cliff)→ 3×3 craft 必 `OPEN_WAIT` 超时 **FAIL,永不到 DONE**;2×2 从不放台 → **服务端唯一能跑 reclaim 的载体 = FAIL 路径**(正好也必须在 FAIL 回收,不是将就)。另:`ServerPlayerAvatar.breakHold` 两分支都 `destroyBlock(pos,**false**,fp)`=**dropBlock=false → 服务端破块不掉落** → arena **结构上无法**断言"台回到背包",那半**只能 live 证**(没去翻 dropBlock:会给所有既有 arena 喷掉落物)。
  - 验=`serverCraftTableReclaimArena` **真 RED→GREEN**:RED(把 flag 强制 OFF=与修前逐字节等价)`tablesLeft=1`=台被遗弃;GREEN `placedTable=BlockPos{760,221,759}`→`tablesLeft=0` + `err=打开工作台超时` **未被 reclaim 覆盖**;**(B)⭐安全断言**:预置的台→`placedTable=null`(走 findTable 借用,从不放)→`preExistingSurvived=true`=**村庄台不会被拆**(这条守住的正是上面那个"诱人的一行修法"回归,否则它**CI 全绿地**悄悄复活)。
  - **LIVE A/B(真裁判,单变量)**:B `flag=off` → craft 成功、台 **0 在包 / 1 留在世界**(复现原 bug);A `flag=on` → craft 成功、台 **1 回到包 / 0 留在世界**。事件流独立佐证:`block.break: crafting_table` + `item.pickup: crafting_table` **只在 A 出现**。
  - 非回归:全套件唯一 required fail=`deepwaterclimboutnoblockarena`(**单跑即绿**=已知水域 ascent flaky 家族,与 #38 基线同一条;craft 改动只碰 CraftProcess/BotConfig/schema,与水域寻路**因果无关**)。
  - ⚠️**顺带钉死一条 arena 基建教训(害我掉一个 debug 循环)**:**GameTestServer 的世界跨 gradle run 持久化**。本 arena 的失败态**正是**"留一张台在世界里"→ RED 跑完那张台还在 → 下一跑 `findTable` 高高兴兴**借用**它 → 不放台(`placedTable=null`)、不回收,断言照样看到 `tablesLeft=1` → **假 RED**(看着像修没生效)。修=arena **自己 scrub 场地**(新 `clearBox` helper)。凡是**可能留下方块**的 arena 都必须自清,否则测的是上一跑的残渣。
- [x] ✅**`mc.observe.player` 服务端快照只报 9/36 槽 = 服务端/客户端特性不对等 GREEN**(#41,task#41,2026-07-12;memory `project_engine_observe_full_inventory`)。**agent 看不见自己拥有什么**——36 格里 27 格(75%)对 driver 完全不可见。
  - 根因=`ObserveApi.playerSnapshot` 只有 `for (i<9) hotbar`,**从来没长出 `inventory` 字段**;而**客户端 `ClientObserve.observePlayer` 一直都有**(全 41 槽)。MCP doc 甚至把 `inventory` 明文写在 "Client-MCP fallback" 段里当**客户端独有**字段 —— **不对等是被文档承认过的,只是没人当 bug**。这是 `effects` 那次 parity slip(`ObserveApi:160-164` 的注释原话:"客户端先长出来的……**服务端快照必须带同样的字段**")**同一个错误的第二次重演**。
  - ⭐**为什么服务端那条才是要害**:客户端还有后门——开背包界面后 `mc.observe.container`(无 pos)能读全 46 个 menu 槽(实测可行)。但**服务端 avatar 没有这条后门**(FakePlayer 开不了菜单,见 #276 capability cliff)→ 对它 `observe.player` 是**唯一**的背包 verb。∴ RED **必须打服务端路径**,否则客户端 workaround 会让测试"因为错误的原因"通过。
  - 修=服务端补 `inventory`(**形状与客户端逐字对齐**:只报非空行、vanilla 索引 0-8 hotbar/9-35 主包/36-39 护甲/40 副手——同一个 verb 同一个字段两种形状本身就是新漂移)+ 新 `items`(id→count 聚合)。⭐`items` **不是新写的**:直接调 `CraftProcess.inventorySnapshot`(提升为 public)=**合成执行器数的那把尺**,服务端/客户端/执行器**三方单源**。∴ `items` 可原样当 `have` 喂 `mc.recipe.resolve`/`mc.plan.acquire`。`hotbar` 保留(纯增量,不破既有读者)。
  - ⚠️**advisor 抓到的静默陷阱(已避)**:`have` 的键必须**带命名空间**(`RecipeResolver` 用 `have.getOrDefault(itemId,0)`,itemId 来自配方=`minecraft:cobblestone`)。裸 id 的 map 会被**当成一无所有**——看着接上了,实则全空。arena 显式断言 `!items.containsKey("cobblestone")`。
  - 验=`serverObservePlayerInventoryArena` **真 RED→GREEN**:RED `invSlots=-1 items=null`(字段根本不存在);GREEN `invRows=5` + 隐藏槽 9/20/33 + 副手 40 全可见。**(B)端到端**:把 `items` 原样当 `have` 喂 `RecipeResolver`,而 stone_pickaxe 的原料(3 圆石+2 棍)**全部藏在隐藏槽** → `planComplete=true missing={}`(这条同时钉死"隐藏槽真被看见"和"键格式真能匹配")。
  - **LIVE(真裁判,纯 RPC 走服务端路径)**:`observe.player` 报 26 行/**18 个此前不可见的隐藏槽**(gravel 22/cobblestone 28/furnace 1/raw_iron 1…)。规划 A/B:**不传 have** → 9 步(叫一个**背着 208 圆石**的 bot「去挖圆石」、还挖已有的木棍);**have=items** → 5 步,冗余步全消,且树种跟着库存走成 acacia(#37 的修在这条链路上可见)。
  - 📌**#30 的出处 = 本 gap 的最早目击**(⚠️**我一度误判成"#30 是拿 25% 数据判的",纠正如下**):#30 的诊断**依据是完整背包**——它走的是 `mc.client.player.inventory`(**客户端**路径,正好有该字段),记录里甚至专门留了一句 API 备忘 *"server-mode observe.player 只给 hotbar,不含 inventory 数组"*。**也就是说这个不对等 #30 当时就撞见了,只是被当成一条"绕过去"的备忘,没立成 bug**——这才是它活到今天的原因。教训:**"绕过去的 API 备忘"就是没写下来的 bug**,下次见到就地立条目。(我误判的成因也值得记:我用**服务端** verb 读到 8 样、又从事件流看到 20 样,就**反推**当初也只看到 25%——**拿自己的观测缺口去追溯别人的结论**,正是该防的那种重构。)
  - 非回归:全套件唯一 required fail=`deepwaterclimboutnoblockarena`(**单跑即绿**,与 #38/#40 **同一条**已知水域 flaky 基线)。⚠️**过程中踩到并钉死一条基建教训**:我被 bash 超时打断的 gradle 会**留下僵尸 gametest JVM**(一度 6 个)——它们既抢 CPU 又抢 `world/session.lock`,把水域 flaky 从 1 放大到 **4**(失败集合还每跑都变=flaky 指纹,非因果回归;清干净后单调回落 4→3→1)。∴ **跑套件前先 `ps | grep [b]ootstraplauncher` 清残留**;`pkill -f` 会误杀 Bash 工具自己的 wrapper,必须**按 PID** kill。
  - 📌**`have` 默认值(#gap-B)刻意不动**:`readHave`(`RecipeApi:326`)在 `have` 缺省时返回**空 map**,而 `CraftProcess` 用真背包——"规划 verb 和执行器两把尺"。但这个默认是**文档写明的**("default: have nothing"),且 resolve/acquire 是**纯查询** verb(假设性规划是合法用途),#38 那条"报告的 plan 必须等于执行的 plan"**不能直接迁移**。且翻转它有**具体回归风险**:#37/#38 的 live 验证正是靠空默认断言的。∴ 本轮**只改文档**(两个 verb 的 `have` desc 现在明确写"把 `mc.observe.player.items` 原样传进来",并点名 omit 的后果)——可用性洞已堵,契约不动。真要翻转须另立一条,并先扫 #37/#38 的断言依赖。
- [x] ✅**工具耐久对 agent 完全不可见 GREEN**(#42,task#42,2026-07-12;memory `project_engine_tool_durability_invisible`)。**与 #41 同形的观测缺陷**:agent 看不见关于自己的、决策必需的事实。
  - 根因=`ApiSupport.itemSnapshot` 只有 `{empty,id,count}`——**一把只剩 5/250 耐久的铁镐,读出来和一把全新的镐一模一样**(live 坐实)。耐久在引擎内部**到处**在用(`AutoEquip` 的 `equipDurabilityThreshold`、`ElytraProcess.DURABILITY_MARGIN`、equip 返回的 `lowDurability`),**但一个字节都没暴露给 agent**。
  - **后果**:agent 只能靠 `tool.broke`(#26)在**事后**知道工具没了。而 #30 记录里那条 ② 的三个子项——①耐久预警换备用 ②断后自动补 ③耐久门下 abort-to-safe——**有两个需要的是"断之前"的信息**。长途挖掘前"这把镐够不够挖完 90 格"这种最基本的判断,driver **根本不提供输入**。
  - 修=新 `bot/util/ItemSnap.putWear`(**单源**):damageable 物品加 `maxDamage`/`damage`/`durability`(**剩余耐久点数** = max−damage,决策真正依赖的那个数;⚠️**点数≠使用次数**,耐久附魔下一点扛多次 → 它是剩余工作量的**下界**,文档已写明);可堆叠物**不加任何字段**(载荷不膨胀,且**"有 `durability`"本身就等于"这东西会磨损"**)。⭐**必须是 helper 不是复制粘贴**:driver 有 **6 处**手搓 item 行(服务端 itemSnapshot / 服务端 inventory 行 / 服务端 container 槽 / 客户端 hand / 客户端 inventory 行 / 客户端 container 槽),各自形状还不同(带 `empty` 的、带 `slot` 的、带 `index` 的)——**逐处复制正是一个字段长出两种含义的方式**(#41 刚被这个咬过)。六处全接上。
  - 验=`serverObservePlayerInventoryArena` 扩断言,**真 RED→GREEN**:RED(`putWear` 强制 no-op = 与修前逐字节等价)镐只有 id/count;GREEN `durability=5, damage=245, maxDamage=250`,且断言**可堆叠物无磨损字段**、`durability` 必须是**剩余**而非已损。
  - **LIVE(服务端+客户端两条路径逐字一致)**:濒断铁镐 `durability=5` vs 全新钻石镐 `durability=1561`,圆石无字段。测试用品已 `clear` 还原(生存世界保持诚实)。
  - 非回归:全套件回到已知基线(唯一 required fail=`deepwaterclimboutnoblockarena`,单跑即绿)。
  - 📌**剩下的"要不要造主动恢复 actuator"是策略不是能力,刻意不做**:通用 driver 出**原语**,LLM 出**策略**。有了 #32(工具门+可行动中止信号)+#37/#38(能自造台/树种跟库存)+#41(看得见全背包)+#42(看得见磨损),agent **已经**具备"预警→撤→重造→继续"的全部输入。**下一步应验证这条能力链端到端通不通**(⚠️**别用 #30 那个 bot**:它只有 1 木板/无原木,恢复失败是**材料**原因不是**能力**原因=假阴性;要用有木头的干净 rig),而不是在 driver 里糊一层策略。
- [ ] （by-design 记录)duskSecure 夜间不接管=正确抑制:要求 exposedAtNight+!cornered+威胁半径内无敌+idle 去抖;僵尸 11-15 格内时 "never dig under attack" 门生效,非 bug。
- [x] ~~planner **break 代价不按工具挖掘速度缩放**~~ **复核=已实现,条目过时**(2026-07-11):ClientWorldView.breakCost 早已按 destroySpeed→ticks 缩放(空手石 150t vs 石镐 12t、wrong-tool ×3、×pathfinderBreakCostMultiplier)。真 gap 是**工具中途打光后 plan 成本失真 12× 且零通知**——已由 tool.broke 事件补上(见下)。
- [x] **工具耐久静默打光**(批修#26):双镐 100+ 块后耗尽,物品无声消失,mine/goto 继续空手挖(慢 12×)+ "需要工作台"迷惑报错;用户问"你不能合成稿子吗"才暴露。修=ClientEventDetector 新增 `tool.broke` push 事件(主手 damageable 距满耐久 ≤2 且该 id 库存计数下降沿;换手/挪槽不触发),level=warning。agent 收到后应在下一次 dig-commit 前重新合成工具。
- [x] ~~mc.bot.setting 部分键**写入被静默忽略**~~ **复核=已修,条目过时(STALE)**(2026-07-12,task#39)。**不复现**:原报症状 `pathfinderBreakCostMultiplier=40/40.5 无 applied/无 rejected/echo 仍 2.5` 今日实测 = `applied:['pathfinderBreakCostMultiplier']`,值 2.5→40→40.5,snapshot echo 跟随(已还原 2.5)。**穷尽扫描(零副作用:每键回写其当前值)覆盖全部 189 个 `public static volatile` 基本类型 BotConfig 字段 → 189/189 applied,0 静默忽略,0 snapshot 不可见**。∴ 原"反射 fallback 类型/字段名问题"的归因**事实上已不成立**——`SettingsCommand:744` 反射 fallback(任意 public static volatile 基本类型按精确字段名可写)+ `SettingsSnapshot:174` 反射补全 pass 已**结构性**修掉整类问题(单点 probe 会漏判,故用全字段扫描定案)。
  - ⚠️**扫描顺带钉出真正的残留风险(另立条目,非本条)**:**未知/拼错的键被静默吞掉**。`mc.bot.setting` schema 是 `.additionalProperties(true)`(BotTools:574)+ 文档明写"unknown keys are ignored"(BotTools:576-577)=**有意契约**(189 个反射键无法枚举进 schema)。所以 `{"allowBrake":true}`(typo)返回 `ok:true`、无 applied、无 rejected、无报错。这是 **live A/B 方法论的真相完整性风险**:实验里拼错 flag → 静默永不生效 → 得出"flag 已开、无效果"的**假结论**。别的 verb 都对未知键硬报错,只有它是例外。
  - ⚠️**第二层(不同层,harness 侧)**:MCP 工具 schema 在**会话开始时冻结**,新加的 key 会被 harness **静默剥离**(见 `scripts/.claude/skills/worlddriver-rpc/SKILL.md`)——即"新编译的 flag 用 `mcp__worlddriver__mc_bot_setting` 设不上,用裸 RPC 就行"。**很可能正是原报告的真因**。∴ **新 flag 的 live A/B 一律走裸 RPC(`scripts/rpc_call.py`),不走 MCP 工具**。
  - ✅**当下即可用的防护(零契约风险,双层通吃)**:设实验 flag 后**断言该键出现在响应的 `applied[]` 里**再开跑。engine 侧加 unknown-key 守卫是候选改进,但需先解决"~96 个手写 setter 存在正是为了 legacy key 别名"→ naive「输入键 ∉ applied ⇒ unknown」会把**合法别名误报为未知键**(守卫误报 = 训练自己无视 `rejected[]` = 比不加更糟)。要做必须先扫手写 setter 键证明无误报。
- [x] 🔴 **per-goto forbidDig 只管规划层,执行层 dig fallback 照挖**(day6 live 两次实锤;**已修 gap#4,详见 memory project_engine_forbiddig_exec_leak_maybreak**):根因=NoBreak 只到规划层剪 break 边;Walker 执行层 5 个 discretionary dig fallback(deepDig/digFallbackHere/bank-dig riser 三 swim climb-out + lily-pad ram + wallDig)只查全局 `allowBreak`(pad 连 allowBreak 都不查=最漏)。修=`Walker.mayBreak()=allowBreak && !profileForbidsBreak`(profileForbidsBreak 缓存于 setSearchProfile,非每 tick 扫)门 5 site;anti-suffocation 单头格挖**故意豁免**留全局 allowBreak(死>导航偏好,pin 注释)。⭐真 RED=lily-pad(planner 当 walk-edge 非 break-edge→NoBreak 不剪→plan pathLen>0 执行器真跑真撞 pad→head-on break 真开)非 wall(NoBreak→pathLen=0 规划层就 ARRIVED at start，执行器不跑，wallDig 连 leash 保活 carrot 也触发不了→**day6 真隧道需第二 co-defect=下条 planner tunnel-preference**）。验=forbidDigPadRamArena 克隆 waterStepDownFloat 确定性 hCol pin，pre survivedA=false（LEAK）→post survivedA=true（门开）+survivedB=false（门精确不 over-kill）；serverForbidDigWallArena=诚实回归卫士（pathLen=0 clean give-up + planned dig 穿墙到站）。**零回归**（全量套件跑完；surfaceDive/underwaterBase 两 NoBreak-executor arena 绿；2 败均非 NoBreak arena mayBreak==allowBreak 字节等价+隔离绿=residue flake）。**✅LIVE A-B 已做（07-11，advisor 纠偏：pad 不是 live 载体=自然寻路绕开→用 wall 载体走真 RPC param；去 sealed campaign bot 借口不成立=gap#1/#2 都用 throwaway ScriptTest）**：ScriptTest 密封石隧道单 plug（唯一路径=挖穿），survival+石镐+allowBreak:true。A `goto forbidDig:true`=pathLen0 give-up 钉死 x41.59 不动+plug 保 intact+视频"静止未挖"（day6 精确反面）；B 同 rig `goto forbidDig:false`=pathLen4 挖穿 plug 到站 x48.41+block.break×2+视频"挖穿前进"=门精确。**pad arena 证 profile→gate + wall-live 证 param→profile = 端到端全绿**。plumbing 全 trace（leash re-solve profileWith 两分支保 constraints=day6 leashed 也带 NoBreak；Walker per-IntentProcess 不复用=无陈旧）。唯 defer=day6 自然 wallDig 泄漏需下条 tunnel-preference co-defect 才触发（本修已堵执行器门）。
- [x] **地表目标 goto 偏好穿山隧道**(day6 co-defect)— **task#35 结论=无新 tunnel cost 缺陷,零改**(2026-07-11,详见 memory project_engine_tunnel_preference_classified_nofix)。advisor 纠偏两轮(先撤 NON-BUG 早结论,再逼用能看见隧道的判据):clean 三 rig 证 planner 正确翻可走坡(finalCost135/1837 intact)只 sheer wall 隧道(=最便宜 break-route,合理);**live Mountains DEFINITIVE**(trajectory+block.break 判据,非 goalReached+hDelta 盲判)=`goto(30,80,-47)` 全程 feet=air+**零 block.break**+摔死=下降是**地表/空中非穿岩隧道**。∴ 未复现任何朝目标凿降/穿越隧道。day6 症状由 task#34 forbidDig(有绕道→绕行/封死→give-up/永不隧道)实际 remedy。若真机重现"稳进凿降隧道"再开(候选=boxedEscalate 触发器扩到"稳进+持续 dig+深度渐降"信号;需真 RED 先红后修)。
- [x] ✅**descent goto 走下悬崖摔死 GREEN(07-12,见下 line~299 GREEN 条:cumulative path-lookahead 武装+airborne driveF clamp+`!parkourEdge` 守卫,live A/B RED13→2)**(task#36,2026-07-11 **根因已钉死=执行器非规划器**):live `goto(30,80,-47)` 下降 massif 时以 9-14 格 chunk 坠落(y118→109→95→83→75)cumulative fall damage 死。**instrumented plan 探针(PlanProbeTool 加 maxStepDrop+yProfile,新 jar relaunch 验)确证 `maxStepDrop=4`**:planner 路由的是**完整可走 ≤4 阶梯**(yProfile 119→117→116…→80 全 ≤4 阶),NON cliff,survivableFall=22 penalize 任何 >22 drop→planner 未路由大落差。∴ **执行器漂离阶梯累积坠死**。⚠️**机制修正(advisor#N 逼读 lethalDropAdjacent 阈值)**:`edgeBrake` **不是**元凶——`lethalDropAdjacent=dropAdjacentExceeds(foot, survivableFall(hp))`(WalkerGeometry:110),满血 survivableFall≈22,而 live 致命落差 9-17 格(hp20→6≈17格坠)**全 <22→lethalDropAdjacent 从不 trip→lethalNear=false→edgeBrake 从未 engage**。真元凶=**`steepDescentNear`**(Walker:4422=`onGround&&plannedDescent&&dropAdjacentExceeds(foot,4)`,survivable-deep-drop **sprint** 刹车):grounded tick 掉 sprint,但 **gated onGround**→step-down 的**腾空 sub-arc(onGround=false)刹车失效**→残余动量把身体漂离 ≤4 计划阶梯到更深(个体可生存 9-17格)落差→cumulative fall damage 死。=**与 `deepWaterDriftLatch`(Walker:4449-4463)同一 airborne-gap**(水域 sibling 已用 LATCH 修=证过的模板)。**修方向=执行器侧**(不动 planner cost→避 [[reference-retreat-flee-off-cliff]] CWV:737-746 走回头路 A/B):给 steepDescentNear 加 airborne LATCH(镜像 deepWaterDriftLatch),跨 step-down 腾空 sub-arc 保持 sprint-drop;若掉 sprint 不足以止漂再叠动态方向 pin(需先测漂移方向 forward-overshoot vs lateral)。**RED arena 必须匹配 cumulative-survivable-drift(多级 >4 阶梯+腾空漂到更深 survivable 落差),NOT 单个 >25 致命唇**(那是 edgeBrake 路径=错机制,会 arena 绿 live 死)。**✅telemetry 确证(walkerDebug walk-keys live massif)**:grounded sprint=false(steepDescentNear working)但 `onG=false→sprint=true` **每个腾空 tick 重armed**,forward z 累积把身体走出 y94 的 19格 survivable 唇→continuous free-fall y94→y75 死;漂移=**forward-overshoot**(z travel 向;x 稳)非 lateral。**✅FIX 已实现+编译绿**:steepDescentNear 加 airborne LATCH(WalkerConstants.STEEP_DESCENT_DRIFT_LATCH=8 + Walker.steepDescentLatch field/reset/logic 镜像 deepWaterDriftLatch + BotConfig.walkerSteepDescentLatch flag default on + SettingsCommand/Snapshot/BotTools schema 单源)。**live A/B(fixed jar 单变量 toggle,同起点)结果:A flag off=复现死(y95 hp5→死);B flag on=**仍死**但 first-fall 伤 hp5→hp10(latch 确减 forward-overshoot 动量但不足)→y76 hp14 再坠死**。∴ **latch 非解**(减伤 hp5→hp10 是 confound:A 首坠18格 B 14格,非 latch 功效)。⚠️**机制第三修正(advisor 逼对齐 telemetry)**:非 forward-overshoot 而是 **lateral corner-cut**:致命唇处 pos.x=-24.5 而 wp.x=-26=**持续~1.5格偏离规划线**,turning 下降路径(wp -25→-26→-26 弯)被**切内弯**,step off 的 19格 drop `maxStepDrop=4` 证**不在规划阶梯**(阶梯在 x-26,bot 在旁边悬崖上)。=横向跟踪失败非前冲。**gapAhead 前向 pin 不行**:(a)抓不住横向偏离;(b)4-block 正常 step 与 19-block cliff 对 gapAhead 全等(都 air ahead)→去掉 `!plannedDescent` 门=每 step pin=crouch-deadlock(edgeBrake 注释所警)。**真解=非死锁机制无关式**:step-**down** 门控在"immediate-ahead drop depth vs **planned next-node** step depth"——4-step:actual==planned 不刹;cliff:actual≫planned 刹(横/前向皆抓)。⚠️坑:wp 是 lookahead(telemetry wp 一跳8格)非 immediate next node→需取 immediate 下一路径节点 y 算 plannedStepDepth。latch 保留(正确 deep-water sibling+deep-descent 外 byte-identical)但 **#36 未闭**。
⭐**执行器-vs-规划器 DEFINITIVE(read-only,确定性,advisor 认可优于 stochastic live)**:`pathfinderMaxDryFall=4` 是**硬规划约束**(单节点 drop 永不>4)。`mc.debug.plan` 探**致命 Mountains 下降段本身**(4 个 up-slope from-点→死亡落点):全 `maxStepDrop=4` biggest-drop=4,clean 阶梯 y100→74/y94→74 全程 goalReached。∴ live `node=y83 while grounded foot=y94`(11 below)**按定义**是执行器 step-skip,非规划器悬崖=**执行器侧修,零 走回头路 风险**。机制:`walkerArcLengthAdvance` 让 step 指针沿下降路**跑到身体前下方**(feet 还在顶),drive 瞄准远下节点+残余 sprint 动量→**弹射出阶梯边**累积摔死。
✅**已实现 fix(compile 绿)**:`walkerDescentStepSkipBrake`(default ON,单源 5 处 wired)。栅 raw 几何 `onGround && (foot.Y - wp.Y) > pathfinderMaxDryFall(活取非硬码) && dropAdjacentExceeds(foot,4)(真边)`→**持 vanilla SNEAK**(maybeBackOffFromEdge 钳整 movement delta:身体**不能**走出 block edge 但仍每次**下台阶 1 格**)+**同 tick 灭 sprint**。∴安全下阶梯不弹射,**永不死锁**(≤4 阶梯 wp 恒在 maxDryFall 内→永不触发;触发时 sneak 照常下降)。advisor 认可(gate 硬约束 by-definition 执行器侧;sneak-edge=vanilla maybeBackOffFromEdge 钳 momentum+input;raw 几何免 plannedDescent 依赖免 launch-tick 重分类漏刹)。latch 保留但真 fix=此 sneak-brake。
⏳**未闭=live A/B 硬门(advisor:build 解锁但 done 阻塞于 A/B)**:成功判据=**"越过 lip 继续下降"非仅 hp intact**(若含横向分量,sneak 可能防死但 edge-pin 卡→stall→repath=死修好但 pointer-ahead 未解=另立 issue 勿并入#36)。单变量只 toggle 此新 flag,latch 两 arm 恒定。两 arm 开 walkerDebug 验 sneak 在 node-far-below tick 触发+foot-Y 随后真降。**下一步:rebuild fabric jar→relaunch→bot 到 massif 顶(~y100 near (-10,-50))→goto 下 (-21,-69)→A(flag off)必弹射/死 vs B(flag on)必 survive+继续→回归 arena(cumulative-survivable-drift 几何非单>25 lethal lip)。**

⚠️**验证撞真·复现 IMPASSE(07-11 pm)**:fix 已 rebuild+relaunch(fresh code,schema key 端到端接受)。**live 复现失败**:该世界 spawn(50,91,36)→goto (-21,75,-69) 路由**不复现** prior session 的 y118→75 下降——allowBreak:true 时 goto **凿穿** hilltop(stuck digging 23,103,21 非地表下降),allowBreak:false 时 surface 探针 horizon-capped(48格)y94-96 wander 不到目标(致命段在~100格外 SW)。无原始 goto 无法重建确定性致命下降。**arena 复现也失败**:新 descentDriftArena(1-wide 直阶梯 drop=3 run=1 steps=14 深坑,真 ServerPlayerAvatar 物理,无 water effects):flag=OFF 也 `ARRIVED fellInPit=false atBottom=true`——**直阶梯执行器 track 指针良好不弹射**,brake 从不 fire(wp 从不>4 below foot)→flag on/off **byte-identical**。∴弹射需 **broad/turning slope** 几何(advisor 初判 corner-cut 横向分量 + step-skip 合流),直窄阶梯无横向 overshoot 空间不复现。
📊**当前证据态**:①机制=执行器 step-skip DEFINITIVE(read-only 确定性 planner≤4 staircase)②fix sound+advisor-endorsed+**非回归**(anomaly 外 byte-identical,直阶梯不死锁不 regress,84 required 绿)③**efficacy UNVALIDATED**(无 controlled/live 复现弹射)。**严规(Live=truth 禁 arena-绿即宣布)→#36 未闭**。arena 保留=回归卫士(fix 不破坏陡降)+ step-skip 复现脚手(待 broad-turning 几何)。
⚠️⚠️**advisor LINCHPIN(最重要结论)**:brake 的**触发条件从未被观测到发生**。gate=`onGround && (foot.Y-wp.Y)>maxDryFall`。arena 证该条件在直阶梯**从不出现**(DESCENT-STEP-SKIP 从不 log,flag on==off byte-identical)。唯一"发生"证据=**单个** prior-session 重建样本(foot y94/wp y83/onG=true)。**若真实弹射时 wp 只在身体 airborne 后才跑到远下方**(bot 走出合法≤4 edge 再摔过更低节点),则 onGround gate 使 fix=**字面 no-op**(非回归证明不了任何东西——no-op 也非回归="analytically sound,died live"陷阱重演)。**必须先 live 观测到 grounded 触发发生,才能为 fix 辩护**。
⚠️**live 复现受阻(surface-finding)**:Mountains=pathfinding **test 世界**非 SurvivalTest campaign→cheats OK(tp/effect 已验证)。但 tp 到 read-only 探针 from-点 (-10,100,-50) **落进实心岩**(窒息掉血 hp20→3)→**那些探针 cell 在山体内部非地表起点**,read-only"下降段"探针从内部 cell 规划(maxStepDrop=4 硬约束结论仍稳,但"地表下降"表征存疑)。bot 已 stabilize(tp 50,96,36 spawn 地表+instant_health,hp20 passive walkerDebug off flag=default true)。
📌**#36 未闭·当前诚实态**:①机制方向=执行器 step-skip(planner maxDryFall=4 硬顶,read-only 稳)②候选 fix 实现+编译+**非回归**(arena byte-identical,84 required 绿)③**efficacy 未验证且可能 wrong-tick/no-op**(advisor linchpin)。**下一步(需careful surface repro):tp bot 到 massif 上方**空气**let it settle 读落地真地表 Y→从真地表 goto 下降→walkerDebug 读 grounded 触发是否 fire。**

🔴🔴**DEFINITIVE 复现+裁决(07-11 pm,advisor linchpin 精确命中)**:**reusable live RED 已建**=tp bot 到真地表 (25,103,21)【自然爬到的真 surface,非山体内 probe cell】→`allowBreak:false`+`goto (-60,69,60)`【probe 该向 106→69 降37格≤4 staircase】→**flag OFF 复现 #36 症状**:下降 y103→72 途中 walk off lips **累积摔伤** hurt lost=5(8格)@ (23,92,28) + lost=4(7格)@ (-11,72,45)(37格降拆成 survivable 段没死但症状=走下悬崖摔伤,精确)。
**裁决**:walkerDebug 全程 trace 分析(933 walk-keys 行)=**grounded 触发 fire 0 tick;airborne far-below 15 tick**。远下 wp(`wp.Y<foot.Y-4`)**只在 onG=false 出现**(样本 foot y101.9/wp y96/d5.9/**onG=F**/sprint=false);grounded 时 max(foot.Y-wp.Y)=**恰 4.0 从不>4**。∴**当前 fix = 字面 NO-OP**(gate `onGround&&(foot.Y-wp.Y)>maxDryFall` 的触发条件真实弹射时从不发生)=advisor 预言的"analytically sound,died live"陷阱精确命中。
🔬**机制精修(现在钉死)**:①grounded 在 drop 顶:wp **恰≤4 below**(合法 dry-fall step)→**grounded 无异常**②bot 合法走出该≤4 edge(step-off 本身合法)③**airborne 下坠中**:drive 继续瞄 wp(step 已 advance/下节点更低)→身体**前向漂移**越过第一个≤4 ledge→continue 下坠→落更低→累积摔伤。**sprint 已 false**(steepDescentNear 已灭)→非 sprint 动量而是**前向 driveF=1 airborne 续飞**+重力。
✅**正确 fix=airborne forward-drift-kill(或 aim-clamp)**:airborne+falling+descending(wp below)+**非 parkour/leap edge**(排 descendLeap/parkourEdge/steppingOffFall 免 gap-crossing 落空 stranding)+身体大致在降线上→**钳 driveF→0**(仿 descendBrake parkour-only 的 line 4259 driveF=0,扩到 plain-walk 降)→身体竖直落到最近 ledge 再续,不 deadlock(重力照落)。当前 `walkerDescentStepSkipBrake`(grounded gate)**保留作 scaffolding 但确认 no-op/至多极陡 grounded-11-below 边角**(prior 样本 onG=T 11-below 或存在于更陡地形),**单独不足**,须叠 airborne lever。
📌**#36 未闭·下一 focused cycle**:实现 airborne drift-kill→rebuild+relaunch→跑上述 reusable RED(flag off 摔伤 vs flag on 无摔伤 survive+续降)→arena 补 broad/airborne 几何(直阶梯不复现,须 turning/wide)。bot 已 stabilize(50,96,36 spawn hp20 passive walkerDebug off allowBreak restored)。
✅✅**#36 GREEN(2026-07-12 pm,gate-fix + airborne driveF clamp,live A/B DEFINITIVE)**:诊断=门层(hSpd trace 见下 PARKED 条)→修=①**cumulative path-lookahead 武装**(steepDescentRaw 加 OR 项:`path.get(step..step+3)` 累计降 >maxDryFall→武装 latch,gated walkerSteepDescentLatch;治单边 dropAdjacentExceeds 看不见的 ≤4-per-step 累计陡坡——正是 planner 铺的几何)②**airborne driveF clamp**(repurpose walkerDescentStepSkipBrake:latch 武装 + !onGround + wp<foot→钳 driveF→0,读 latch field 前 tick 值,airborne-only 重力照落不 deadlock)。**live A/B(Mountains,regen off,同起点 25,103,21→goto -60,69,60,单变量 toggle)**:A RED(latch0 clamp0)=descent 摔伤 **13**(Fall A launch 8+Fall B 4);B arming-only(latch1 clamp0)=**9**(Fall A **仍 launch 5**——sprint-kill 单独不够,证实 advisor Q2);C arming+clamp(latch1 clamp1)=**2**(Fall A 消失,无 launch)。三 arm 全程越过每个 lip 继续下降到谷底**无 stall-at-lip**。新武装 log fire 148×(`foot y=78 pathDrop=6>4 over 3 nodes → latch 8 (noSprint+airborneDriveFClamp)`=正是 Fall B 弹射 tick,原先静默)。∴**clamp 是决定杆非 backup→default ON**。新武装 log fire 148×(`foot y=78 pathDrop=6>4 over 3 nodes → latch 8`=正是 Fall B 弹射 tick,原先静默)。**+`!parkourEdge` 守卫(07-12 pm,advisor blast-radius 命中)**:`descendBrake=descendLeap(=parkourDescend*)&&!onGround` 已钳降落 leap,但 plain `parkour*` 落更低=`parkourEdge` 却非 descendLeap→我 clamp 会新钳其起跳 drive→落短坠 gap=fix blast-radius 内新摔死;加 `!parkourEdge` 排除。validated RED launch **walk-keys 实证**(advisor 逼查 Fall A"未刻画"缺口):Fall A launch(node=25,90,28 的 y94→82)move=**fall2/diagDown**(walk 非 parkour),Fall B=forward-drift walk;全 log 有 parkourDescend2d1×250(=descendLeap,descendBrake 本就钳)+parkour2×41,但 **41 parkour2 全簇在 valley 终段 node=-56,63,52(y62-64),两处致伤 fall 零 parkour**→守卫只改 valley-approach 跳(那里正是 advisor 忧的 short-landing,修对)从不碰两处已验 fall→**"2" 携带到守卫版**。narrowing clamp 只加 driveF 从不减。**非回归(守卫版,跑 3× full suite)**:RPC/schema **132/132×3**;**descentDriftArena `ARRIVED fellInPit=false atBottom=true`×3**(#36 真正触及的地形 arena)。⚠️**订正旧"84 required 全绿"=那是一次幸运干净跑**:现每跑 required 恰 1 flaky fail=**水域 climb-out ASCENT arena、每跑不同**(run1/3=buoyantwallarena,run2=deepwaterclimboutnoblockarena,互跑即绿,buoyantWallArena 注释自承"cross-run jitter/intermittent")=既有 flaky 水 arena 家族,**与 descent-only clamp 因果无关**(clamp 只在 `wp.Y<foot.Y` 降时武装,ascent climb-out 从不触发;narrowing 只加 driveF)。诚实=**83/84 required(1 flaky ascent-water/跑),descent 相关零回归**。optional fail=vineOverWaterClimbArena 恒定(required=false MUST-FAIL,-711 未修,非我引入)。⚠️下游:goto 到谷底后 jungle canopy+water(allowBreak:false)stall 是**独立既有问题**非 descent(falls 全在 y103→72 段)。教训:sprint-kill 减伤但不阻 launch(residual driveF=1 airborne 续飞)=advisor Q2 精确命中;driveF clamp 才是真解;声称"全绿"前须多跑区分 flaky。
🅿️🅿️**#36 PARKED(2026-07-12,hSpd trace 逐 tick + advisor 校正,取代上文"机制精修/正确fix")**:分析 flag-OFF RED trace 两处 hurt 的 `hSpd`(advisor 决定性判据=grounded walk-off 速 vs airborne 速):**①Fall B (-11,72,45,lost=4)=确证 forward-drift**:连续 airborne 坠 y79→72,**hSpd 上升 0.21→0.23**,**sprint=T 全程**,wp 前下 tracking=原机制确证。**②Fall A (23,92,28,lost=5)=未刻画**:narrow z/x band 把真致伤落地 tick 裁掉(拉到的 3-4格片段+中途 onG=T touchdown 产不出 lost=5≈8格连续坠)→**别据片段下"vertical collapse"结论**。**⚠️内存/上文前误纠正**:"sprint 已 false(steepDescentNear 已灭)"**错**——sprint 在 Fall B **全程 T**(steepDescentNear **没灭它**),仅 Fall A idx121 灭。**∴真缺陷=GATE 层**:两个 descent brake(`walkerDescentStepSkipBrake` grounded gate=fire 0tick no-op;`walkerSteepDescentLatch`/steepDescentNear=同一降坡 Fall A 灭 sprint、Fall B 不灭)**都 geometry-gated 时灵时不灵**→反复失败模式=**门不触发**非 brake 动作。**处置**:`walkerDescentStepSkipBrake` default **翻 false**(杀 confirmed no-op;advisor:flip 足矣**别做重代码删除**),5处 wiring+`descentDriftArena` 保留作 non-regression guard,**不 relaunch/不 A-B**(no-op flip 无可验证)。**下一 cycle 入口(别加第三 brake,盲加同样 no-op)=诊断门**:diff Fall A(idx121 sprint 死)vs Fall B(sprint 全程 T)——同一降坡为何 steepDescentNear 一处 fire 一处不 fire?门修好后若仍需 brake 动作再谈 airborne driveF-kill。**教训**:narrow filter 会裁掉真事件 tick(致伤落地不在拉取窗内);"heterogeneous/no-lever" 会埋掉真 lead(数据其实指向清晰=门问题)。
- [ ] mc.query q='entities' **无 center 时默认 testOrigin 非玩家**(集成服务器路径):返回 [] 看似世界无实体,加 center:玩家坐标即正常。文档确有此口径但极易踩;建议 client 附着时默认玩家。
- [ ] goto/mine 等进程完成**无主动 push 事件**(process.done),现靠 wait.condition 120s 轮询兜底,超时窗口内 agent 盲等。
- [x] 🔴 **DEATH#3 runAway 摔死("hit the ground too hard")**(批修#26 已修):根因在**执行层动量**非规划层(A* 干地坠落枚举上限 5/gate 3,产不出致命落差)——`edgeBrake = lethalNear && !plannedDescent` 让计划内下降解除 sneak pin,低血量照常 sprint,残余漂移滑过安全落点入深坑连续坠落。修=`BotConfig.lowHealthCareful`(dflt 6.0 HP):≤阈值时 sprint 全抑制 + 计划内下降也保持 lethal-edge sneak pin(Walker×2 处);arena=serverLowHpEdgePinArena(2HP flee 下计划楼梯到致命唇缘,血量必须不掉)。
- [x] 🔴 **escape 静默停摆×2(实际×4)**(批修#26 已修):双根因——①`canWalkOut` 成功门过弱:任一脚侧可走即 DONE,旷洞里恒真→秒退无报告;②sealed 掩体 STEP_UP 死循环:CARVE 只清目标龛位从不清**自己头顶**(base+2),封顶跳不起→100t 超时→re-PICK→同向已 carve→无限乒乓零破块。修=EscapeProcess 挂 `state.escape` 槽(UserTaskChain.slotFor 补 case,所有出口经 done() 写 lastError)+ futility watchdog(连续 4 次 re-pick 无 step 进展→BAIL 带因)+ CARVE 链首清 startArc(base+2)+ PICK 前置 launch-arc 可破门;arena=serverEscapeSealedShelterArena(密封 1×2 土袋必须爬出到地表)。
- [ ] 环境:Xvfb :99 被其他项目的 NeoForge 客户端抢前台,推流跟着切画面——已用 xdotool windowraise 夺回;共享显示器多客户端需约定或分显示器。
- [x] 🔴 **duskSecure/bunker 封顶不验侧向围合**(批修#25 已修:BunkerProcess.nicheEmbedded 验证龛位嵌入实体;live 验证=day3 手动 bunker 后 cornered=true)。
- [x] 🔴 **bunker/duskSecure 反射失控深挖**(批修#25 已修:depth 达标即终止+cancel 抑制)。

## 引擎 gap 三连闭环 (2026-07-11, task#30 生存软死锁驱动;详见 memory project_engine_gap1/gap2)

- [x] 🔴 **gap#1 无可靠垂直上升 actuator**(已修):goto up 复用便宜 air/楼梯不挖 pillar=XZ drift 撞断原镐。修=新硬约束 ColumnRadius 剪 XZ 超径后继(distSqXZ 无 dy 项,别做球形=只剪 approach 会漏爬升)+Y 自由;剪枝在建节点前→best-effort 选择器只见柱内;goto 新 arg `column:{x,z,radius}`(caller 传自身 XZ,radius 1-2)。验=columnRadiusArena(planner)绿+**LIVE A-B clean 单变量**(A 无 column drift5.30 爬便宜楼梯横移 vs B 笔直挖上柱内 1.42+climb5.0)。
- [x] 🔴 **gap#2 MineProcess 无工具能力感知→空手徒劳磨石**(已修):空手挖 requiresCorrectToolForDrops 块=块碎但零掉落、进程永不识别徒劳、不终止(reachable 石头近无限一路挖)=软死锁第二 gap。修=`scanForTarget` 加 `canHarvest` 门(requiresCorrectToolForDrops && 全主背包 0-35 无 correct tool→跳过+记 toolBlocked)→全 tool-blocked 无 reachable→终态信号 `blocked: <block> needs <tool> — none held or in inventory`+tick() SEARCH null 优先发信号 reset 终止。保 dirt/wood/gravel/sand 空手+保背包有刀。验=serverMineNoToolArena TDD 红(server destroyBlock 瞬破→remaining0/3 lastError null)→绿(remaining3/3 信号对)+serverMineProcessArena 加镐 remaining0/3+**全量不过滤套件 3 mine arena 全绿零回归**+LIVE A-B(A 无镐 endedTick16 abort passive vs B 带镐 cobble0→2)。已知缺陷:错等级镐(木镐挖需铁镐矿)信号误说"none held"其实握着;poll-based(status/wait.condition 读 lastError,无 push)。
- [x] ⚪ **gap#3 Walker 空手挖清障是否软死锁**(调查=非 gap,不修):advisor 抓出我"代码读证终止"漏洞(假设块最终碎;空手 deepslate 300t>digAim watchdog cap 200t)。建 serverWalkerDeepslateNoToolArena(bedrock 走廊+2 格 deepslate 塞子=唯一有限路,faithfulBreak=true 否则 server 瞬破掩盖时序)→**endTick1336 ARRIVED reached=true plugRemaining0/2**:空手挖穿两格 deepslate(~650t/格)到站,0 次 dig-aim RELEASE(latch 没参与),totalTicks 全程被 breakHeld 抑制。机制:traverseBreak 持续 hold→breakProg 单调累积碎块;dig-aim latch 只补充 re-assert、其释放不调 breakHold(false)。**tool-gate 在此=回归**(Walker 只需 clearance 非 drops)。arena 留回归卫士(assert reached&&plugRemaining==0)。All 81 required 绿。
- [ ] （待 user 裁决,task#30）campaign 软死锁 bot 处置:归档转新跑 / 接受 debug-seal 恢复(违 no-cheat)/ 引擎侧续找 gap。三引擎 gap 已清=撞出这三 gap 的 campaign 缺口补齐。

## ✅ 引擎 gap #44 GREEN:planner 假设空背包、executor 用真背包 = 两把尺子(2026-07-12)

**根因**:`RecipeApi.readHave` 在调用者**不传 `have`** 时返回**空 map**,而 `CraftProcess`(真正执行这个计划的东西)
**从真背包消耗**。→ **planner 和 executor 在量同一个事实、用两把尺子**,plan 是**给另一个 bot 做的 plan**。
**这就是 #38 的同一条教训**(planner/executor 必须同一把尺子),只是**症状相反**:#38 少报活儿(craft 时才失败),
#44 **多报活儿**(叫背着 208 圆石的 bot 去挖圆石)。

⭐**RED 我早就有了却归错档**:#41 的 live A/B(不传 have→9 步 / 传→5 步)就是这个缺陷。
我当时把它写成"footgun,文档里警告一下",**其实它是默认值错了**。
⚠️更早我还给它编过一个辩护:"这是**文档化的纯查询**语义"。**查了源码,这话是错的**——同一个方法第 147 行就调
`CraftProcess.availableStations(botPlayer(), level)` **扫世界找工作台**。
station **自动读世界真相**、items **拒绝读背包** = 这 verb 从来就不"纯"。**用错误的理由搁置一个真 bug,比不搁置更糟。**

**修**:`readHave` → `public static resolveHave(Params, Player)`(可测的纯函数)。
- **omitted `have` → 读真背包**,经 `CraftProcess.inventorySnapshot`(**executor 消耗的同一个方法** = 一把尺子)。
- **显式传的 map = 假设(hypothesis),原样使用**——**包括显式 `{}`**(仍然是"假设我一无所有")。
  ∴ **what-if 规划保住了**,且**所有已经显式传 `have` 的调用者(含 #37/#38 全部测试)行为零变化**;**只有"没传"这一种从错变对**。
- 支点:`Params.has/present` 能区分"没传" vs "传了空 map"(`getMap` 两种都返回 `Map.of()`,**区分不开**——这是修法的关键)。
- null bot(headless/未加入)→ 空,不崩(与 `botPlayer()` 对 station 的兜底一致)。

**验**:
- 新 `serverPlanHaveDefaultsToBagArena`,4 条断言:(A)不传→真背包(208 cobble,且藏在**隐藏槽** 9/20)→plan complete;
  (B)显式 `{}` 仍是空假设(**若这条塌陷,what-if 就被静默吃掉了**);(C)显式 map 原样用、**不与背包合并**;(D)null bot 不崩。
  **真 RED**(把默认分支退回旧行为=逐字节等价于修前)→ `defaultHave={}` 失败 → **GREEN** `{minecraft:cobblestone=208, minecraft:stick=2}`。
  ⚠️必须打 `resolveHave` 这条缝而不是 verb 全路径:**FakePlayer 不在 PlayerList**,verb 自己的 `botPlayer()` 看不见它(#41 那个悬崖)。
- **LIVE A/B**(同一 bot、同一背包 208 cobble + 4 stick,唯一变量=传不传 `have`):
  **不传 → 5 步、零"挖圆石"**(只去挖它真的没有的原木造台);**显式 `{}` → 9 步、含 `mine cobblestone×3`**(what-if 完好)。
- 非回归:全套件跑两次,每次 **1 个 required 失败但失败的不是同一个**(`deepwaterclimboutnoblockarena` / `descentyawarena`)
  = 已知 flaky 签名;**两个都单跑即绿**(89/89)。文档(MCP catalog 两处 `have` 描述)同步改成新契约。

## ✅ 能力探针 #43:工具耗尽恢复链端到端**通了**(2026-07-12, live, ScriptTest 干净 rig)

**这是确认不是发现**:各环此前已分别绿过,本次只验证**原语可组合**——而且它是 #42 写下的"下一步"。
**定位澄清**:driver 出**能力**,LLM 出**策略**。全程的每一次决策(该不该撤、该造什么、什么时候恢复挖)**都是我这个 agent 做的**,
driver 里**没有**、也**不该有**自动恢复 actuator。∴ 正确说法是"**原语可组合,没有缺失原语**",**不是**"bot 现在会自己恢复了"。

**rig(全公开披露)**:ScriptTest 世界;`clear` 背包 → 给 `stone_pickaxe[damage=130]`(**durability=1**)+ 8 oak_log + 6 cobblestone;
**故意不给工作台**(逼它自造,连测 #38/#40);旁边 `mc.action.fill` 一堵石墙当矿。⚠️**没用 #30 那个 bot**:它 1 木板/零原木=**真材料死锁**=假阴性。

**只用真 RPC verb 走完(每一环都有 live 证据)**:
1. `mc.bot.mine stone` → 挖 1 块,镐断。事件通道**主动 push** `tool.broke: damage=130 maxDamage=131`(#26)。
2. `mc.observe.player` → `inventory` 干干净净只剩 8 log + 6 cobble,**镐没了**(#41:换成修前只看得见 hotbar 9 槽)。
3. 再发 `mc.bot.mine` → **工具门挡住并给可行动理由**(#32):`lastError = "blocked: minecraft:stone needs a pickaxe — none held or in inventory"`
   ——修前这里是空手把石头磨到永远不碎(块碎零掉落、进程不终止)。
4. `mc.plan.acquire {target: stone_pickaxe, have: <observe.player.items 原样>}` → `feasible: true`,5 步,
   **子树里自己塞了 crafting_table**(#38 的 station 注入:世界没台、包里也没台 → 规划造一个)。
5. `mc.bot.craft stone_pickaxe` → 事件流逐帧:planks→sticks→planks→**crafting_table 造出并放下**→石镐造好→
   `block.break: crafting_table` → `item.pickup: crafting_table` = **台子用完回收进包**(#40)。
6. `mc.bot.mine stone ×3` → **正常挖 3 块拿到圆石**。终态背包:新石镐 `durability=130`、台子 1 个在包里。
   (「世界零遗留台」是**从事件推断**的:`block.break` + `item.pickup` + 台子回到 slot 0,数量守恒;**不是扫描验证的**——那次 `mc.client.blocks` 参数报错后没重跑。)

**⚠️只跑了反应分支**(镐在第 1 块就断,压根没有"断之前"的窗口)。主动分支多出来的唯一断言是「断之前看得见 `durability=1`」,
这条 #42 已证、本次 rig 读数也直接显示 `durability: 1` → **"零缺失原语"对两条路径都成立**。

**结论**:`tool.broke`(#26) → `observe.player.inventory/items/durability`(#41/#42) → 工具门中止信号(#32) →
`plan.acquire(have=items)`(#41 单源) → `bot.craft`(#38 自造台 / #40 回收) → `bot.mine` 恢复 —— **六环全通,零缺失原语**。
∴ **task#30 的引擎 gap ② 由累积闭合**(gap ① 早由 #31 ColumnRadius 闭合);#30 剩下的只有 **bot 处置**这一条 user 裁决。

**诚实记录的噪声**:live-screen-watch 报了 3 条 ANOMALY("完全静止疑似卡死"),**全是误报**——那是我在 RPC 之间轮询/思考时 bot 合法待机
(driver idle 必须 passive),同期 `block.break` 事件证明该挖的时候在挖。教训:探针类协议的 context.txt 静止阈值要比模型的 16s 窗口宽。

## ✅ 引擎 gap #45 GREEN:melee 攻击冷却对 agent 零暴露(2026-07-12)

**根因**:vanilla 按冷却条缩放近战伤害(挥早了只打出条子那个百分比,且暴击必须满条)。引擎**一直知道**——
`CombatProcess:238` 每 tick 读 `getAttackStrengthScale`,压着不挥直到满条,还按 0.85-1.0 预跳骗暴击——
但**没有任何 verb 报过它**,而 `mc.bot.attackEntity` 文档自己写着"No range/cooldown check"。
∴ 自己驱动战斗的 agent **每刀打在 40-80% 伤害上而不自知**,并且会把它误读成"这怪太肉/武器不行/打不过要撤"。
**与 #41/#42 同形**:引擎用某个事实做出正确决策,却给 agent **零字节**。

**⭐先排掉 parity slip(#41 教训)**:#41 是"客户端有、服务端没有",客户端还有开 GUI 的后门,所以 RED 必须打服务端。
本次**先查了 `ClientObserve`**:它**也没有**这个字段 → 这是**真空洞**,不是不对等。(不查就写 RED,可能红错一边。)

**修**:新 `AttackSnap`(与 #42 `ItemSnap.putWear` 同族的单源工具类)→ `observe.player` / `client.player` 各加一次调用,
字段 `attack:{strengthScale, ready, cooldownTicks, fullCooldownTicks}`。
- 纯**公开 API** 推导(`getAttackStrengthScale(0)` × `getCurrentItemAttackStrengthDelay()`)→ **不必再碰**
  `ServerPlayerAvatar` 那个反射的 `attackStrengthTicker`。
- `ready` 就是 CombatProcess 挥砍的那个门(scale>=1.0);`cooldownTicks` 是**可以直接拿去等的数**;
  `fullCooldownTicks` 让 agent 能**比较武器**(剑 13t vs 斧 23t)而不是靠挨打去猜 DPS。
- catalog:`attackEntity` 那句"No cooldown check"改成**指向新字段的可行动指引**("连点不是更高 DPS,是同样 DPS 更差命中")。

**验**:
- `serverAttackCooldownArena` **真 RED→GREEN**(RED=摘掉字段 → "carries no `attack` field at all")。
  打 `playerSnapshot(ServerPlayer)` 缝(FakePlayer 不在 PlayerList=#41 悬崖);`ServerPlayerAvatar.step()` 当时钟。
  4 组断言:挥后条空 / 单调回充且 `ready` 恰在 scale>=1.0 翻转 / 满条**恰好**在报出的 period tick 到达 / **跟随手里的武器**(斧慢于剑,防止字段是个常数)。
- **LIVE**(纯 RPC,服务端+客户端同一时刻):空手 `fullCooldownTicks=5` → 装铁剑 **13**(与 arena 逐字一致);
  **挥砍后立刻 `strengthScale=0.16 / ready=false / cooldownTicks=11`** = 连点第二刀**实打实只有 16% 伤害**——修前这一整条曲线 agent 一个字节都看不到。
- 非回归:全套件 required 89→**90**,唯一失败 `buoyantwallarena` **单跑即绿**=已知水域 flaky 家族(每跑受害者都不同)。

### 🔴 副产 gap(**未修**,#45 arena 撞出来的,已单独立项)
**服务端 avatar 的装备属性永远是陈旧的**:`LivingEntity.detectEquipmentUpdates()` 是 private、由 `Player.tick()` 每 tick 调,
而 `ServerPlayerAvatar` **只跑 `baseTick()`** → FakePlayer **手里拿着铁剑,属性却还是裸手的**
(实测 `fullCooldownTicks=5` 而非 13)。∴ 服务端 avatar 的近战**按错误节奏挥**,更糟的是 `ATTACK_DAMAGE`
大概率也停在基础值(=拿着剑打出拳头伤害)。**#45 的 arena 里我在测试侧手动同步了修正器**(`equipMainHand`),
**没有**顺手改引擎——那是另一个 bug,要有它自己的 RED。

### ⚠️ 基建教训(又踩一次,这次写死)
live 一开始读到 `NoClassDefFoundError: AttackSnap`,我一度怀疑 dev-jar 烘焙。**真相是僵尸 JVM**:
39801 端口的 owner 是**一个更早启动的旧客户端**,我新起的两个客户端根本抢不到端口。
⭐**`ps | grep bootstraplauncher` 抓不到 fabric 客户端**(它走 **knot**)——所以过去那条"按 PID 杀"的清理法**漏了一整类进程**。
∴ 清理必须 `grep -iE "[k]not|[b]ootstraplauncher|agent\.rpcPort"`,并且**用 `ss -lptn 'sport = :39801'` 认端口 owner**,
别信"我刚起的那个就是在答话的那个"。

## ✅ 引擎 gap #46 GREEN:服务端 avatar 拿着剑打出拳头伤害(2026-07-12)

**发现于 #45 的 arena**(它当时只是"攻速读数不对"的一个副作用),**先测量后设计**(advisor 逼的:我原话是"ATTACK_DAMAGE **大概率**也错、护甲可能不减伤"= 又一次未验证的臆断)。

**测量**(`serverAvatarGearScopeProbeArena`,断言的是**结果**不是属性):
- 修前:**铁剑 0.94 伤害 == 空手 0.94** —— 剑的加成**一点没吃到**(`ATTACK_DAMAGE=1.0`=裸手基础值);`ATTACK_SPEED=4.0`(应 1.6)。
- 穿满钻甲 `ARMOR=0`。**但** `FakePlayer.isInvulnerableTo()` 的字节码是**无条件 `return true`**
  → **服务端 avatar 根本挨不了打** ∴ **护甲陈旧无实义**,advisor 担心的"生存级 bug"那一支**被证伪**。
  ⇒ 真实爆炸半径 = **纯进攻端**:服务端近战 ~6× 弱 + `CombatProcess` 的 `scale>=1.0` 门在用**错误武器**的节奏计时。

**根因**:`LivingEntity.detectEquipmentUpdates()` 是 **private**、只由 `Player.tick()` 调;
avatar 刻意只跑 `baseTick()`(避免双重物理),**而且 `FakePlayer.tick()` 的字节码是空的** —— 
∴ **就算去调 `tick()` 也没用**,必须在 avatar 里显式镜像。

**修**:`ServerPlayerAvatar.step()` 里新增 `syncEquipmentAttributes()`——diff 每个 `EquipmentSlot` 的持有物,
变了就把旧物的 `ItemAttributeModifiers` 摘掉、新物的 `addTransientModifier` 上去(护甲槽一并同步:今天无实义,
但留一条规则好过留一个"等哪天 FakePlayer 能挨打就悄悄烂掉"的特例)。

⭐**全套件抓出我这个修法的一个真洞(单跑绿、全量红)**:`FakePlayerFactory` **跨 arena 复用同一个 FakePlayer**,
而我把 `lastEquipped` 记在 **avatar 实例**上 → 新 avatar 记录为空,**无法移除自己没加过的陈旧修正器**,
上一个 arena 的武器加成漏进来(空手挥砍测出 **2.94** 而非 0.94)。
修=**把记录挂到 FakePlayer 上**(`WeakHashMap<FakePlayer, EnumMap>`)——**状态属于携带它的那个实体,不属于观察它的那个对象**。

**验**:`serverAvatarGearScopeProbeArena` **真 RED→GREEN**(RED=摘掉 `syncEquipmentAttributes()` → 断言炸在
**结果**上:"an iron sword deals no more than a bare fist (0.94 vs 0.94)");GREEN=**剑 5.90 vs 拳 0.94**,
`ATTACK_DAMAGE=6.0`、`ATTACK_SPEED=1.6`、穿甲 `ARMOR=20`。
⭐**结果断言把我自己抓住了**:我最初把铁剑的 ATTACK_DAMAGE 期望写成 7.0(那是钻石剑;1.21 铁剑=1 基础+5 修正=**6**)——
挥砍结果早已证明修生效,而那条"回读常数"的断言还在红。**这正是"断言结果、不断言我以为的常数"的价值**。
非回归:全套件 required **91**,2 个失败(deepwaterclimbout / buoyantwall)**各自单跑即绿**=已知水域 flaky。

**⚠️无 live A/B,且这不是偷懒**:这条路径(`ServerPlayerAvatar` + FakePlayer)**在客户端 rig 上根本不存在**
(live 客户端是真 Player,`tick()` 正常跑,属性一直是对的)。GameTestServer 里跑的是**真 ServerLevel + 真 FakePlayer +
真 `Player.attack()`** —— 对这条路径而言 **arena 就是那条路径本身**,不是它的仿真。要真 live 验证需要一个 headless 专用服务器 rig(未搭)。

## ✅ 引擎 gap #47 GREEN:服务端 avatar 在零敲碎打地重实现 `Player.tick()`(2026-07-12)

**这不是第三个 bug,是 #45/#46 的同一个根**:`ServerPlayerAvatar.step()` 只跑 `baseTick()`(它手工积分位移,
不能让 `aiStep()` 再积一次),而 **NeoForge 的 `FakePlayer.tick()` 字节码是空的** ——
∴ vanilla 在 `Player.tick()`/`LivingEntity.tick()` 里做的**每一件 per-tick 账目,不手工镜像就全部不存在**。
过去是**被 arena 一次伏击一件地**发现的(#45 攻击条、#46 装备属性)。这轮把 1.21.1 的 `Player.tick()` 逐行拉出来对了一遍。

**审计结果(漏项族)**:
1. ⭐**`updatingUsingItem()`(private,只由 `tick()` 调)** —— `Avatar.commandUseItem(hold)` 调 `startUsingItem()`
   **只上膛不扣扳机**:`useItemRemaining` 没人递减 → **吃永远不下咽、弓永远 0 蓄力、盾永远差那 5 tick**。
   = **一整条 Avatar 能力在服务端静默失效**(不是保真度瑕疵)。
2. `cooldowns.tick()` —— ItemCooldowns 永不到期:**第一次用末影珍珠/盾被斧破防之后,那个物品就永久死了**。
3. `lastItemInMainHand` 变了要 `resetAttackStrengthTicker()` —— **换武器不清空冷却条**:
   agent 可以在 A 武器上攒满条、换 B 武器立刻满力挥,而 **#45 报出去的 `attack` 字段会把这个幽灵满条当事实广播**。

**修**:把散落的补丁收编成**单一 `mirrorPlayerTick()`**,并把**故意不做的写成契约**(这才是防下一次伏击的东西):
- 镜像:①持续使用倒计时 ②装备→属性 ③攻击条 ④换手清条 ⑤物品冷却(**vanilla 顺序**:先 ++ 再因换手清零)。
- **故意不做**:`aiStep()/travel()` 驱动(手工积分,跑了会双重积分);`foodData.tick()`(**半真相**:exhaustion 产生于
  从不运行的 `aiStep`,∴ 镜像了也永远不会饿,何况根本挨不了打);**伤害/血量/所有血量反射**
  ——`FakePlayer.isInvulnerableTo` **无条件 return true**,且 `ServerWorldDriver` **压根没接任何反射链**
  (Retreat/Panic/Bunker/Dodge/AutoHeal/AutoShield 一个都没有)。
  ⭐**服务端 agent 是任务自动机,不是生存者**;"19/19 进程已迁到 Avatar"这句话**读起来像它们在服务端都能用**,而血量那一支不能。

**验**:`serverAvatarTickFidelityArena` **真 RED→GREEN**,三条**全是结果断言**:
握"使用"40t 后牛排 **2→1 / 饱食 6→14**(RED:2 / 6,一口没咬);10t 冷却 15 tick 后**真到期**(RED:永不到期);
换武器后冷却条 **1.0 → 0.0**(RED:1.0 不动)。非回归:全量 required **93**,唯一失败 `buoyantwallarena` **单跑即绿**=已知水域 flaky。
⚠️连带:#45 的 arena 必须在装备武器后**先走一 tick 消化换手**再开始计时——那个前置条件一直是隐含的,新行为把它显式化了(不是给新行为让路)。

## 🔴 引擎 gap #48(未修,RED 留在树里):所有服务端 agent 共用**同一具身体**

**发现路径**:#47 全量跑出一个"新面孔"失败(`entityleashrepath`),我没有顺手判它 flaky,而是做了 A/B ——
结果**受害者对调**(带修 leash 红/不带修 gearscope 红)。**回归只会增加失败,不会换人** → 这是争用不是回归。
取证:`identityHashCode` 探针证明**全套件只有一个 FakePlayer**(`getMinecraft(level)` 是**每 level 单例**),
连跑三轮全量**失败集每次换人**(leash / deepwaterclimbout / buoyantwall / descentovershootresync / gearscope 轮流当受害者,**同一份代码**)。

⭐⭐**这多半就是"已知水域 flaky 家族"的真根**:那从来不是水域算法在抖,是 **arena 在抢同一具身体**
(GameTest 并发跑 arena,它们全在驱动同一个实体)。**"每跑受害者都不同"这个签名,我们当作水域的性质接受了很久,它其实是一份未被读懂的 bug 报告。**
生产侧同病:`/agentserver` 起两个 agent **不是两个 bot,是两个 driver 拽着一具身体**(所幸 `ServerAvatarCommand` 自己的 scope 写的是 "single demo agent")。

**为什么没修**:显然的修法(每 driver 一个唯一 `FakePlayerFactory` profile)**是对的、arena 也绿了**
(A 走 3.83 格 / B 纹丝不动 0.0),但它让**其它每个 arena 第一次拿到真隔离** → 套件从 **~50s 炸到 >9min 且跑不完**
(每具新身体都是完整 ServerPlayer:stats+advancements)。而且这反过来说明:**共享身体很可能一直在掩盖真实失败**
(arena 过去是靠"身体被邻居传走→提前退出"结束的,其中一些是**假通过**)。
∴ 真修法必须**给隔离设界**(把驱动身体的 arena 串行化,或**单具身体进场即重置**),而不是每次 create 都造一具新的。
`serverAgentDistinctBodiesArena` 以 **`required = false`** 留在树里当**活的 RED 复现**(与 `vineOverWaterClimbArena` 同惯例)。

⚠️**基建**:`pkill -f GameTestServer` **抓不到 gametest JVM**(真实命令行是 `-Dneoforge.gameTestServer=true`,**大小写不同**)
→ 被 `timeout` 杀掉的那几轮留下**僵尸 JVM 攥着 `world/session.lock`**,后续每轮都在等锁,被我一度误读成"#48 把套件跑挂了"。
清理用 `pkill -9 -f "[n]eoforge.gameTestServer"`。(与 #45 的 knot 僵尸 JVM **同族**:**清理脚本抓不到目标进程**是这个仓库反复踩的坑。)

## 🔴 引擎 gap #49(未修):假绿 arena + 不可达目标 repath 空转

**起点**:为验 #48,把 5 个历史"水域 flaky 受害者"**当一组**跑(为此把 `AGENT_GT_ONLY`
升级成**逗号分隔列表**——单一真源 `AgentGameTestSupport.gtOnlySkips`,顺手收编 92 处 inline guard)。
4 个单跑干净通过,1 个把 600s timeout 烧穿。

### ① RIG:注释里的墙,代码从来没建
`descentOvershootResyncArena`(cx=300, cz=660, Y=200)注释:
"*A sheer 5-block face on the ridge's +Z side (dz=7) walls the forward walk*" —— **没有对应的 setBlock**。
山脊到 dz=6 止、深底板从 dz=8 起 → **dz=7 是直通虚空的空柱**。
遥测:bot 从 z=663.5 前走,z≈667.7 起 `fall3`,y 200 → **-60**(世界地板)。

### ② 它是**假绿**(多年什么都没断言)
- 单跑(当前 master,共享身体):**150s 超时 / 49 次 reject-loop**
- 单跑(武装隔离):**151s 超时 / 50 次** → **与并发无关,确定性挂死**
- 但它在全量套件里是 **required PASS** → 只可能是**邻居 arena 撞走了共享身体**才"结束"。
∴ **#48 的共享身体在掩盖真实失败——这是第一个实证**(因果方向与之前记录的相反:
共享身体不是让它 flaky,是让它**假绿**)。

### ③ 引擎真缺陷:目标不可达 → 无限空转(live 会中招)
bot 在 y=-60,目标 y=194(不可达)。循环:
脚下搜(满额 10 万节点)→ best-effort **escape-farthest** 段(终点 212 格外,`commitEnd=456`)
→ 从 commitEnd 搜续段 → bot 还在原地 → `reject mis-anchored`(守卫**守约**清 path/commitEnd)
→ 下一 tick 从脚下重搜 → **同一条 escape 段** → 循环。
**守卫没坏、清理守约**(`adoptPath` 的 javadoc 契约兑现了),
**坏在循环本身没有终止条件**:`escape-farthest` 只产出下一段,**从不结束这趟旅程**。
每轮 **2 次满额 A\***(各 10 万节点 / **~3s 墙钟**)。
**live 影响**:bot 掉进深沟 / 目标被填死 → 服务器每 tick 烧一个满 A*,**agent 侧零信号**。

### 下一步
1. 先修 rig(建 dz=7 那面墙)→ 让 arena 恢复成**真裁判**,看它测的 back-hop wedge 到底红不红;
2. 再给 ③ 单独立 RED。**别顺手加"全局不可达 abort"大改**,先读懂 escape/soft-commit 状态机。

### ⚠️连带更正 #48 的记录
">9min = 真隔离的诚实代价(每具新身体=完整 ServerPlayer)" —— **那个成本从未测量,是断言**。
真相:其中一大块是**隔离把本来就存在的确定性活锁掀了出来**。
∴ "真修法=给隔离设界(串行化/单体重置)"**可能在解错的问题**。
正确顺序:**先修被掩盖的挂死 → 再重测隔离的真实代价 → 然后才谈设界**。
(隔离修**是生效的**:唯一 profile 一 armed,`serverAgentDistinctBodiesArena` 立刻转绿。)

## ✅ #49-③/#50 GREEN + ✅ #48 分流落地 + 🔴 #51 立案(2026-07-12 下午)

### ✅ walker 不可达目标 churn(#50,live TDD)
- 修:`walkerFutileSearchCap=5` — 连续 K 次搜索完成而 bestDist 无改善且位移<2格 → `FAILED`,
  reason=`no route progress after N consecutive searches — goal unreachable`(与 tick 预算的
  "no progress for N ticks"=暂态卡顿**可区分**);每次 futile 后 4→8→16→32t 指数退避压住 kickoff。
  豁免:水域(归 anti-spin)/breakHeld/waterClimbDigging。
- live RED **62.1s/129 搜索/36s A\* CPU/通用 reason** → GREEN **20.0s/6 满额搜索/可区分 reason**;
  可达目标回归精确到达;全量 92 required 绿。探针可复跑:`scripts/probe_unreachable_churn.py`。

### ✅ #48 生产/测试分流(旧"设界"方案废弃,理由见 create() javadoc)
- `/agentserver` → `createIsolated` → `createUnique`(每 agent 唯一身体);
  `serverAgentDistinctBodiesArena` 切隔离入口后**转正 required**。
- 套件级隔离实测 3 轮(139/125/138s):失败集**仍漂移**且成员全 solo 绿
  → 非确定性 ≠ 身体单变量(还有共享世界 region + 服务器线程负载)→ 归自造测试框架(#52)。

### 🔴 #51 descentDriftArena = 假绿家族第二员(已降 required=false 活 RED)
- solo 必红:fix-ON leg 照样 LAUNCH 入坑且穿到 y=-60(pitFloorY=182)= 兜底没接住;
  cap=0 solo 烧穿 300s(守卫把它变 110s 有界失败;A/B 证 launch 与守卫无关)。
- descentOvershootResyncArena **已删除**(user:没用的删,不救)。

### ⛔ 新硬规则:禁止 pkill(user 原话)→ ps 列 PID 逐杀。

### 🚧 #53 stride floor-guard(致命横跨守卫)— 2026-07-13
- **结构**:`Walker.tick()` → `tickInner()` 单出口包裹;守卫在**每条**决策路径后、体动力学积分前跑
  (v1 挂在文件底部 walk-keys 区=对 pillar/stepUp 等 early-return 分支是死代码,GREEN 曾 byte-identical 假验证)。
- **机制**(v4):真实**速度向量**前瞻 ~4 tick 的步幅格;该格可通行且下方 `max(maxDryFall+1, ceil(HP)+3)`
  格内无地板/无水(=当前血量下**致死**的未规划落差)→ sneak-pin+取消跳+`place()` plug 洞口(消耗背包,生存合法)。
  豁免:水中/parkour 起跳(per-tick 字段)/前瞻 8 节点内精确列规划下降(列必须精确,Chebyshev-1 会重开 #51 坑口)。
  **sneak 闩自释放**:hazard 消失的下一 tick 收回(v2 只上不下的闩把 ridge 下坡 pin 成 maxNoProgress=205 死锁)。
- **证据链**(全部新世界;⭐世界持久化教训:ON 轮 plug 的圆石留在世界里让假 OFF 轮"绿"——每轮必 `rm -rf run-gametest/world`):
  - selfShaftDigUpArena:OFF=RED(worstBackslide **281.25**,坠 y=-60)/ ON=GREEN(**1.25**,守卫 5-6 次触发在
    slab 基座边缘+井口平台边缘,正是 RED 坠落点);
  - ridgeOvershootArena:v2 守卫致其死锁 → v3 闩自释放后 GREEN;
  - descentYawArena:v3/v4 trio 里 RED 但 **solo ON=GREEN**(worstBack=-0.25 基线)=跨 arena 干扰签名(#52 病),非守卫回归;
    v4 致死阈值把 5-6 格弦切落差从误 pin 中放行(descentYaw 触发 26→5)。
- **#51 判明**:守卫把 descentDrift 的"发射入坑坠亡"压成"坑口 sneak-pin 活锁"(567 触发全在 pit mouth,
  fellInPit=false)——死亡模式消除,但 drive 朝下方节点螺旋的真根仍开放,归 #51/#54 专修。
- 待:全量 v4 与 OFF 基线彩票集对齐 → live 生存竖挖场景自然覆盖验证。

### 🔴 #53 live 首战:守卫救援被周边系统拆掉(2026-07-13 17:04 死亡)
- 死亡链(fabric latest.log 钉死):守卫在崖唇 (6,102,35) **正确 pin 8 次**(速度压到 0.04)
  → **plug 静默失败 8 次**(`Avatar.place` 返回 void,日志无条件宣称 plug;同格重复触发=放置从未落地的铁证)
  → walk-keys **stuckT 2→4 在涨**(pin 被 anti-stuck 当卡死)→ 恢复脉冲把 2.47HP 身体推下 6 格坠亡。
- v5 修:①plug 改世界真值判定+三态日志(plug/no placeable/plug FAILED);②守卫触发 tick 回退 stuck 计数(pin≠stall);
  ③连续 pin≥30 tick → `path=null` 强制重路由(把守卫从"跟 anti-stuck 打架"改成"向规划反馈致命路径")。
- ⭐元教训:**void 返回值的 actuator=天生的静默失败源**;安全机制必须与 anti-stuck 明确分层,否则互相拆台。

### 🚧 #51 root-cause 完成,修归 #54(2026-07-13)
- 闩修(✅保留,solo descentYaw/selfShaft/ridge 全绿):steepDescentLatch 的释放条件用瞬时 wp-vs-foot,
  下落中 foot 跌破 wp 一个 tick 就把闩清零 → airborne clamp 失效 → 满推力横踢。改成**落地才释放**。
- 但 descentDrift 仍 RED:东向冲量**不来自通用 drive 通道**(driveYaw 指南时仍东漂)=某 early-return 分支自带冲量;
  sneak 在凸角上被 vanilla 边缘扣的"残留 sliver 支撑"语义穿透;stepUp jump 在守卫非触发 tick 逃逸。
- 按 3-strikes:停止在"通用 drive+补丁"架构上打第 3 个补丁;**1 宽梯下降=锁柱 DESCEND per-move 状态机(#54 首个迁移对象)**。
- ⚠️descentYawArena 的 trio/full 组合红、solo 绿(两次复核)=共享身体跨 arena 干扰,非本次改动;归 #52。

### ✅ #55 收案 + #56/#57/#58 三连锁(2026-07-13 晚)
- **#55 伤害源不可见**(重定性,原"扫描器隧道失明"被 RED 僵尸对照当场证伪——compute 从不按光照/LOS 丢实体):
  修=`getLastDamageSource()` 两端单源:①player.hurt 事件带 `source/attackerId/attackerType/attackerDistance`
  (客户端 handleDamageEvent 镜像,免 mixin);②攻击者注入 threat 扫描(过滤改 `Enemy || attackedMe`,
  分数 floor 0.5+0.25 保 top)——激怒中立生物(狼/蜂)从此可见。AgentGameTestCombatSense RED→GREEN;
  live GREEN=死亡#3 事件 `source=mob attackerId=1106 dist=2.08` + 途中 `lost=7 source=fall`。
  ⚠️FakePlayer 无条件免伤→lastDamageSource 永 null:测试必须 makeMockPlayer。
- **#56 holdItem 只搜快捷栏**:放置类 verb 在物品漂出槽 8 后全体静默 no-op="熔炉静默失败"悬案真根(#27 同族——
  修 selectTool 时没 grep 兄弟路径)。修=swapFromMainInv 共享 helper(menu 槽 9-35 SWAP,优先空槽),
  pillar 版同源化;craft 错误拆"无物品/无空位"。live RED→GREEN(台在槽 9:镐+熔炉+#40 回收全链)。
- **#57 垫块吃功能方块**:walker 把刚合成的熔炉当桥料放世界。修=BotConfig.isInteractiveBlock
  (EntityBlock+工作台族)安全类拒绝(whitelist 不豁免)。gametest RED→GREEN(96 required)。
- **#58 意外 GUI=全引擎瘫痪(死亡#3 直接死因)**:对交互方块放置点击=开 GUI;屏幕吞输入→walker 悬空 churn→
  futile-search 误诊"goal unreachable"→僵尸磨死。修两层:①BotApiImpl 屏幕看门狗(movement 活跃+容器屏
  +非 craft/smelt→20 tick 关+`screen.autoClosed` 事件);②walkerPlace 支撑循环跳过交互方块面。
  live GREEN=行进中开背包 20 tick 自动关、行进不断。⭐futile-search 的"不可达"要先排除"根本动不了"。
- 回归口径:失败集漂移 2→4 但全是挂名彩票(leash/deepwaterclimbout/gearscope/descentYaw)零新名=无回归;
  ⚠️AGENT_GT_ONLY 多名单≠solo:gearscope 先跑就把 descentYaw 掀红(#48 又一证)。

## gap#59 (P0, 2026-07-13 18:49 live): 向上 goal 被执行成 downBreak 直下 69 格
- 现场: 密闭土腔内 goto pos(51,86,12)(上4格) 与 goto YLevel84 均持续直下挖 (50,z10) 柱, y82→y13, cancel 才停。
- log 实锤: `[walker] t=1 step=25/36 move=downBreak node=50,56,10` + 同 tick `search slice ... (still running)` = 全量 A* 未完时已按 36 步全 downBreak 临时路执行, horizon soft-commit 向下续接。
- 前奏: (51,80,12) 同格 dirt 放/挖振荡 ~40 次 (~60s, dirt 13→2)。
- 疑点: cancel ok + active=[] 后事件仍显示数格下挖 (y30→13) — 待 log 时间戳判 overrun vs 通道延迟。
- 正面旁证: 69 格自挖竖井全程 HP3.7 零坠伤 = #53 floor-guard live 大样本。
- 诊断: pathArchive 回放 + quick-start/provisional 对"goal在上方+局部密闭"的处理; 修法候选 = 临时路 goal 方向单调性门 + cancel 即时清 dig-aim/releaseKeys。
- ✅收案 (2026-07-13 19:40): 真根≠quick-start, 是 chooseSegment 兜底 escape-farthest 取 distSqr 最远节点不排 break 边(均匀岩层最远恒=正下方) + 每次 adoption penalizeStuckNode 毒化上行路的自增强棘轮; Block/YLevel goal 从不 track bestClimb 必然落到该兜底。
  修 = PathFinder.Node 增 `dug` 标记, bestEscape 只认 `!dug`(walkable-only)节点 → 密封岩 bestEscape=null → no-path → #50 futile 退避接管。
  验证 = gametest escapeFarthestNoRockDrillArena RED(下钻 pathLen=8)→GREEN(hasPath=false) + 96 required 无回归(4失败全已知彩票, leash 经 stash A/B 证前置) + live A/B 同点 goto y84 零挖掘 segment=none 干净失败。
  遗留 watch(归#24): goto YLevel 从 y73 横向 2 高破块隧道 ~10 格零 y 增益(疑 travel≥5 白拿 goalward segment); escape verb canWalkOut=true 即 DONE(skyOpen=false 也停); cancel-overrun 时序; dirt 振荡; 1×1 竖井 PillarUp 成本爆炸(A2b 后继)。

## ✅ gap#61+#62: 放置扫描族缺陷 (2026-07-13, task#61)
- **#61 placeTable 坑沿盲区**: dy 只试 {0,-1} → bot 站 1 格深坑(duskSecure 每黄昏挖的那种)时全脚层邻居=实心墙, 唯一自然位=坑沿顶(dy=+1) → 开阔地报"没有可放置的空位"(live 原句)。修=dyOrder {0,-1,+1}(+1 最后, 平地仍偏好同层)。serverCraftTableHoleRimArena RED(tables=0 同句错误)→GREEN(tables=1)。
- **#62 placeFurnace=placeTable 进化前拷贝**: 只 4 正邻、无 dy 层、还在用 placeTable 注释里点名错误的 isFaceSturdy 门(拒树叶/土径)。#42 同款 copy-paste 分叉家族。serverSmeltFurnaceHoleRimArena RED(furnaces=0 "需要熔炉（背包里没有可放置的熔炉）")→GREEN(furnaces=1)。
- **修(防腐>点修)**: 抽单一共享扫描 `PlaceNearby.place(a,p,lvl,item,expectedBlock,logTag)`(8邻×dy{0,-1,+1}+canBeReplaced/非空支撑门+click失败逐条LOG), placeTable/placeFurnace 两端 delegate; placedTable 赋值语义(gap#276 只破自己的台)保留在 wrapper。
- 服务端悬崖照旧: FakePlayer 开不了菜单, 两 arena 都断言世界侧放置, err=打开工作台/熔炉超时为期望值。
- **live GREEN** (21:17): bot 地下矿室 smelt 3 raw_iron → 熔炉放置成功 + 3 iron_ingot + Acquire Hardware 成就, smelt slot 干净收尾。全量套件 100 required 仅 descentYaw 彩票(solo 绿)零新名。注: smelt 无熔炉回收(placedTable 回收无 furnace 版, 低优先)。
- 残余 watch(归#24): 19:50 那次 live ground-truth 里有一个 (0,1) cell=air/below=grass 的 dy=0 候选"本该成功"却失败, 与坑沿几何不完全吻合——PlaceNearby 已带每候选 click-fail LOG, 下次复现看 log 定位(备选: holdItem 同 tick 验证/useItemOn 服务端拒/同 tick 预测未更新)。

## 🔴 gap#63(P1, task#62): 深部→地表 goto 水平隧道 churn (2026-07-13 21:18-21:24)
- live: goto (-4,37,-6)→(-5,67,0), 6分钟挖~120块石头, 终点 (17,35,2)=距目标31→40格, y反降2。cancel止损。
- 证据: pathchart-0001-1783992344943.png(goal红X在西,轨迹东北钩南,elevation图**plan本身全平**); plans=2 reached=false expanded=15948 **ms=2001顶时间帽** cost=1218 maxYawErr=175°。
- 定性: goalReached=false 的 partial-best 选段采纳"平层横钻背向goal"段+每段repath/penalize继续漂移=churn家族(#35/#50/#59同族); 30格实心岩上升需~60-90 break边,2s预算到不了→best-node选择器可疑。修法与渐进式寻路(HorizonBlocks/bestClimb)和#54结构解重叠。
- 新watch数据点: cancel后事件通道仍流30-60s才静止(主体=事件投递延迟,但事件无时间戳无法精判=可观测性gap)。
- ~~observe items间歇空~~已证伪: rpc_call.py返回裸result,我一半解析用`.get('result',{})`拿到空dict——观测管道自身的bug,不是引擎flake。⭐先验证读数管道再定性引擎。
- ~~autoRetreat未触发watch~~→升级为gap#65并✅GREEN收案(见下)。
- 🟡新watch(2026-07-13 22:31): retreat逃跑路径吃了5HP fall(x115,追击中连续flee~100格)——#26 lethalEdgeBrake只挡致命沿,非致命坠伤仍会吃;若live再现升级为gap。

## 🟢 gap#64(P1, task#63): SmeltProcess 燃料三缺陷 — gametest GREEN, live 待 Stage4 自然 smelt 收官 (2026-07-13 23:29)
- live×2 证据: 炉A 8 coal整组消失; 炉B烧掉crafting_table(煤在包里不用)+只出1/5即超时+input残留。
- 修(SmeltProcess): ①pickFuelMenuSlot=值序选燃料(vanilla getFuel烧值最高优先)+isInteractiveBlock工作方块拉黑(#57安全类复用,显式fuelId豁免) ②SMELT_WAIT火灭(fuel空+!isLit+input在)→自动续装并重置超时预算,无可续装→诚实partial/fail ③COLLECT三槽全取回(result+ingredient+fuel残留;背包满时QUICK_MOVE no-op不丢东西) ④count盈余经③回背包。
- TDD: smeltFuelPolicyArena(cx2400)=真SmeltProcess全状态机驱动(FakePlayer开不了GUI→手动塞containerMenu,menu.clicked同一接缝);RED=烧工作台原样复刻(fuel=crafting_table tableInBag=0)→GREEN三相(coal入炉台留包/火灭续planks/终态三槽空+2锭3生铁8板全回);全量2失败=selfshaft(solo绿彩票)+riverSheerBank(stash基线前置)零新名。
- ⏳live收官: 生存线 Stage 4 自然 smelt(no-cheat不给物品)——观察点=煤优先于木、不烧台、终态背包无泄漏。

## ✅ gap#63 GREEN收案 (2026-07-13 23:20, task#62): A*预算停 partial path 背向 goal 乱钻
- 真根(遥测实锤): `escape-farthest` 方向无关按d²取最远walkable节点,在**预算停**(maxNodes/maxMs, openLeft>0=图未耗尽)时提交背向长走——"所有可达格都背向goal"只是预算帽的伪象,真答案(向上挖)的dig节点f太高在预算内永不pop。live A侧复测: d 22.2→34.4单调跑飞棘轮, STOP行=`maxNodes(600) segment=escape-farthest openLeft=973`+`maxMs(2000) expanded=17060`。
- 修(PathFinder三件): ①escape-farthest按停因分流——图耗尽(open空,"背向是唯一路"可证)保留原样(escape-farthest-exhausted);预算停只许**ground-holding escape**(距goal真3D直线距离不比起点差2格; h是错尺:Goal.Block的Chebyshev让dy跨度内横漂全免费,数字实锤 dx=8/dy=6 h差正好=slack) ②无safe escape→null→#50退避owns fail-stop ③bestClimb扩到"targetPos在起点上方"的3D goal(原只ignoresY)——旱地限定(水启动喂climb让2格冒头节点抢bestAshore→riverSheerBank红,已收口`!startInWater`),高度单调=收敛escape。
- TDD: budgetAwayTunnelChurnArena(cx2300, 密封室+16格背向隧道+正上goal+200节点) RED(endDist 6→17.1)→GREEN(no-path干净fail-stop);gap#59场顺带升级(现在提交4步不降段=climb生效);全量失败集全分类(deepwater/selfshaft=solo绿彩票, leash/riverSheerBank=stash基线红前置, descentYaw=已知彩票)零可归因回归。
- live A/B(mc.debug.replay churn档 replay-0010-1783992278682, 同地形replan): A(旧build)=跑飞棘轮; B2(新build)=y32→43收敛爬升+d 33.7→17.1+一次有界徘徊+终态`no route progress after 5 consecutive searches`干净fail-stop=契约达成(进展 or 可区分失败交策略层staged-hop)。
- 残余(watch, 归#54/progressive): ①endpoint-only门放过"途中乱绕"路径(48步段中段绕到d29.6,walker卡中段→从更远处repath) ②600节点quick-start在挖掘柱位提交横向escape把bot从上挖点拽走(dig节点600内不pop) ③固岩长升仍需staged-hop(+8y)策略层分段。

## ✅ gap#65 GREEN收案 (2026-07-13 22:31, task#64): autoRetreat对远程攻击失灵
- 三腿根因: ①CLEAR_RADIUS=12 < 骷髅交战距15-16(死亡#6全程"不在危险中") ②proactive charging要眼对眼LoS而箭走抛物线拐角照中 ③#55的attackedMe归因字段无消费者。
- 修(RetreatChain): shouldEnter/shouldRelease抽成public static纯门(scan喂入,无client可测); RANGED_RADIUS=18仅对**已交战**(charging||attackedMe)的RangedAttackMob放宽(空闲骷髅仍12,不为路过怪弃任务); underRangedFire=被远程命中任意HP入闩且封释放(attackedMe~2s窗自衰减)。
- TDD: retreatGateMatrixArena(cx1240) 9-case矩阵, RED=case(a)死亡#6几何(hp8+穿墙中箭@14必入)→GREEN 9/9; 全量101required零新名(deepwater=solo绿彩票, leash=stash A/B证前置, vine=已知optional)。
- live GREEN(受控A/B, SurvivalTest): 阴性×2=骷髅@14-16.5空闲/无LoS→retreat正确不出价; 阳性=骷髅@**15.6瞄准**(正是旧盲区12-18)→retreat即刻active,bot从x20拉到x77+,**全程HP20零中箭**(proactive在第一箭落地前跑路),脱离接触后正确释放。
- 契约: 反射层管"正被打/正被瞄"的逃命; 空闲怪路过不触发(hp门+12格)——避免夜间寸步难行。

## 🔴🔴 死亡#6/#7 (2026-07-13 21:55, 引擎层, gap#65/task#64) 
- #6: 骷髅7702穿隧道追击, HP20→8→5→2→0, **autoRetreat全程未接管**(设置全开阈值10, chainPriorities.retreat恒0)=P0反射失灵。裸bot重生。
- #7: 赶尸goto(5,43,21)又犯#63(120步path朝反方向,x-69 vs 目标x5), cancel后余势漂移进洞穴水域, 被creeper炸死@(-73,32,40)。3分钟连折两命。
- 物资: 8铁锭+双石镐+熔炉+全部, 尸点(8,37,21)+(−73,32,40)超窗despawn=铁线第三次清零。
- 决策: ~~#65修好前不推生存线~~ #65已✅GREEN(2026-07-13 22:31); 剩余顺序 #63(P1)→#64(P1), 生存线可恢复。
- 引擎侧新证: ①#63二次复现(地表→浅地下目标也churn) ②cancel余势/事件延迟再证(死亡#7间接因素) ③staged-hop(+8y)workaround在无干扰时有效(y33→42楼梯干净)但骷髅一搅就乱。

## 生存线事故记录 (2026-07-13 19:31/19:36, 死亡#4/#5, 策略层)
- #4: 救援尾段 HP3.7 被苦力怕炸死 @(59,72,-47); #5: 裸装 respawn bot 夜间赶尸 70 格被骷髅+僵尸杀死 @(39,77,-85), 且当时关了 autoSecureAtDusk。物资(277圆石/石镐剑/熔炉/工作台/raw_copper×3)超 5min 全 despawn, 生存线清零重启。
- 教训: 赶尸先算 despawn 窗口 vs 路程+夜险; 裸装夜间不长途; 不为赶路关保护反射。已恢复 autoSecureAtDusk=true, bot 蹲坑熬夜。

# drownEscape：含水空腔那条臂的调研

> **状态：调研+策略完成，实现未开始。** §0–§3.5 齐全；原计划的 §4（场景设计）
> 与 §5 **不存在**——写它的三个 subagent 都在同一处断线，最后一个把 §0–§3.5 落了盘。
> 谁接手，从 §4 开始，不要重做 §0–§3。
>
> **一句话结论**：这条反射一边喊救命一边按住跳，而 `!onGround()` 是
> `Player.getDestroySpeed` 里第二条 ÷5，**它把自己的救命动作放慢了 5 倍**（石镐一格石头
> 57 → 282 tick，预算只有 300）。修法是把「浮」和「挖」拆成两相、DIG 相**按住潜行**落到
> 腔底，**并且同一次落地把 `lid` 从固定 `+2` 改成扫描出来的那一格**——
> ⚠️ **只做前者会把一具「能挖但慢 5 倍」的身体变成一具「完全不挖」的身体**（§3.4 副作用 2）。
>
> 阈值 `drownEscapeAirThreshold` **不是这条臂的旋钮**（§2.3）：它买的是「更早开始」，
> 而病是「开始了也做不完」；且它同时是 `WalkerTickClimb:1161` 与 `AutoSwim:167` 的分母，
> 拉满会让规划器再也不敢下水挖。**维持 100 不动。**

调研日期 2026-08-22。**纯读码**，仓库未被改动，未跑 gradle。
所有论断标注等级：**读码级**（从源码/字节码推出，未在运行时观测）／**观测级**（有日志或 results 证据）。
行号全部对应此刻工作树 HEAD 之外的**工作树内容**（并发下有别人在改，见文末"读树不等于读 HEAD"）。

主文件：`common/src/main/java/net/magicterra/worlddriver/bot/scheduler/DrownEscapeChain.java`（317 行）
纯闸：`common/src/main/java/net/magicterra/worlddriver/bot/auto/DrownEscapeGate.java`（76 行）

---

## §0 反确认支：如果我这套诊断是错的，哪一个读数会告诉我

先写这一条。下面每一条都是**单一读数**，能把我的结论直接判死，且都能从现有日志/results 里取，不需要新仪器。

### R1（最强的一条）——`[drownEscape] CAPPED lid — lateral swim` 这一行到底有没有出现

我的诊断是：身体落进 y=40 含水空腔后，`lateralEscapeDir` 返回 `null`，横向臂**根本没进**，所以身体走的是"纯竖直 + 可能连盖都没挖"那条。

- **如果那趟日志里有 `CAPPED lid — lateral swim to open water dir=…`** —— 我错了。那说明 `cappedColumn` 判到了盖、`nearestBreathable` 也在 5 格内找到了目标柱，身体是"朝着一个它够得到的开口游过去却没游到"，那是**执行/寻路**问题（yaw 设了但撞墙、或 `forward` 被别的写入盖掉），不是"判据把这条臂关掉了"。修法方向要整个换掉：不是放宽扫描半径，而是查这条臂的驱动为什么无效。
- 注意这一行**双重节流**：`BotConfig.walkerDebug` 必须为真，且 `dbg++ % 10 == 0`（`DrownEscapeChain.java:162-164`）。所以**"日志里没有" ≠ "没走这条臂"**——必须先确认那趟 `walkerDebug=true`。若 walkerDebug 是关的，这条读数无效，得改用 R2。

### R2 —— 400 tick 里 `destroyProgress` 有没有动过 / 有没有任何方块消失

我的诊断的第二半是：`lid = p.blockPosition().above(2)`（`:189`）是**固定偏移**，空腔顶若在 foot+3，foot+2 就是水，`lidBlocksRise=false`（`:194-195`），于是**一块都没挖**。

- **如果那 400 tick 里 `mc.bot` 侧记录到破坏进度在推进（哪怕极慢）** —— 我这一半错了：盖子选对了，问题纯粹是 ÷25 太慢（§3 那条），扫描/选格不用动。
- 判别方法：那 400 tick 结束时空腔顶那一格还在不在。**在** ⇒ 要么没挖要么挖不完；**不在**（挖穿了却仍然淹死）⇒ 挖穿之后上面还是石头/水，即"往上不是出路"，那 §2 的阈值讨论整个是错的靶子，真正的修法是横向而不是向上。

### R3 —— 入水那一刻的 `air` 读数

我假设 latch 是在 `air<=100` 时才建立，也就是身体在含水空腔里已经**先白待了约 200 tick**（300→100）才开始自救。

- **如果日志里 `[drownEscape] PREEMPT` 那一行的 `air=` 明显高于 100**（例如 240）—— 说明触发的是别的通道（`drowningFloat` 的 240，`AutoSwim` 的 in-process backstop），我对"谁在开车"的判断错了，§2 调 `drownEscapeAirThreshold` 就调错了旋钮。
- `PREEMPT` 这一行是**无条件 info**（`:121-124`，不吃 walkerDebug），所以"零行"这次是有意义的证据：真没 latch 过。

### R4 —— 400 tick 是不是 100 tick 空气 + 300 tick 淹伤

`air<=0` 后 vanilla 每 20 tick 掉 2 HP。满 20 HP ⇒ latch 之后总预算 = 100（空气）+ 200（10 次淹伤）= 300 tick。

- **如果实测停留 400 tick 才死**，多出来的 ~100 tick 只能来自 latch 之前那段（空气 300→100）。合。
- **如果停留时间明显短于 300 tick** —— 说明死前 HP 已经不满，那 §2 里"预算 300 tick"这个上界要按实际 HP 缩，阈值结论要跟着缩。

### R5 —— `onGround()` 在那 400 tick 里是真是假

§3 整条修法建立在"按住跳 ⇒ `!onGround()` ⇒ 额外 ÷5"上。

- **如果那 400 tick 里身体其实一直 `onGround()==true`**（比如空腔矮到跳不起来、或者身体被顶在腔顶但脚仍判在地面）—— 那 ÷5 那一档根本没发生，§3 的修法收益是零，`getDigSpeed` 只有眼在水的 ÷5。
- 这一条也顺带判死"松手会下沉"这个副作用：本来就在地面上，就没有可沉的。

---

## §1 `DrownEscapeChain` 的完整决策树（读码级）

### 1.1 两层结构

这个 chain 分成**决策层**（`priority()` → `updateLatch` → `DrownEscapeGate.next`，纯函数、无客户端类型）和**执行层**（`tick()`，第一行就是 `if (mc == null) return;`，`:129`）。

> **重要结论（读码级）**：`tick()` 在 `mc == null` 时直接返回。专用服（headless）上永远拿不到 `Minecraft` 实例，所以**任何 dedicated-server 场景都只能测到决策层，一行执行层代码都碰不到**。下面 §1.3 的三条出路，headless 场景在结构上就不可能覆盖。

### 1.2 决策层：latch 的四条转移（`DrownEscapeGate.java:61-75`）

| # | 条件 | 结果 | 行 |
|---|---|---|---|
| G0 | `!enabled`（`BotConfig.autoDrownEscape`） | 立刻丢 latch，返回 false | `:63` |
| G1 | 未 latch：`underwater && air <= enterAir` | 建立 latch | `:64` |
| G2 | 已 latch：`air >= min(releaseAir, MAX_AIR)`，即 `>= 280` | 释放 | `:66` |
| G3 | 已 latch：`!underwater && air > prevAir && air > enterAir + 40` | 释放（"探出头且在回气"） | `:73`，`RECOVER_MARGIN=40` 在 `:43` |
| G4 | 其余 | 保持 latch | `:74` |

- `enterAir = BotConfig.drownEscapeAirThreshold = 100`（`BotConfig.java:390`）
- `releaseAir = BotConfig.drownEscapeReleaseAir = 280`（`BotConfig.java:398`）
- `MAX_AIR = 300`（`DrownEscapeGate.java:34`）
- `underwater` 用的是 `Entity.isUnderWater()`（`DrownEscapeChain.java:107`），语义是**眼所在格是水且不能水下呼吸**，不是"脚在水里"。
- latch 建立 ⇒ `priority()` 返回 `Priorities.DROWN_ESCAPE = 500f`（`Priorities.java:25`）。压过 `BUNKER=300`（`:29`）和 UserTask，被 `PANIC=1000` / `DODGE=900`（`:18,:20`）压过。
- latch 翻转时打一行**无条件 info**（`DrownEscapeChain.java:121-124`），带 `air/underwater/enter/release` 四个数。

### 1.3 执行层：三条出路（`DrownEscapeChain.java:128-218`）

进入 `tick()` 之后按顺序判：

#### 出路 A —— 横向游向开口（`:145-166`）

进入条件：`w != null && p.isUnderWater()` **且** `lateralEscapeDir(w,bx,by,bz) != null`（`:149`）。

`lateralEscapeDir`（`:252-255`）是**两段与门**：

1. **`cappedColumn(w,x,by,z)`（`:261-268`）必须为 true**
   从 `by+1` 扫到 `by+SURFACE_SCAN_UP`（`SURFACE_SCAN_UP=4`，`:82`）：
   - 是水 → 继续往上（`:264`）
   - 第一个非水格：`isPassable && !isHazard` ⇒ 返回 **false**（开阔，不算被盖住）；否则（固体或危险）⇒ 返回 **true**（`:265`）
   - **4 格全是水 ⇒ 返回 false**（`:267`，注释写的是"深水，浮上去就到水面"）
2. **`nearestBreathable`（`:284-299`）必须找得到**
   切比雪夫环 `r = 1..LATERAL_SCAN_R`（`LATERAL_SCAN_R=5`，`:86`），环内按欧氏平方距离取最近；判据是 `breathableColumn(w,x,by,z)`（`:273-280`）：从 **`by`**（脚那一格本身，注意起点比 `cappedColumn` 低一格）扫到 `by+4`，第一个非水格 `isPassable && !isHazard` ⇒ 可呼吸。

命中后的动作（`:151-165`）：yaw 指向目标柱、`setXRot(0f)`（水平）、`jump(true)` 保持浮力、`forward(true)`、`sprint(false)`、`sneak(false)`、`keyAttack.setDown(false)`、**`return`**。

**代价**：这条臂**完全不挖**（显式关掉 keyAttack），纯游。它的成功完全押在"5 格内真有一根能呼吸的柱"上。

#### 出路 B —— 纯竖直上浮 + 破盖（`:171-216`）

进入条件：出路 A 没命中（`dir == null`，或身体不在水下、或 `w == null`）。

动作：`jump(true)`（`:181`）、`BotInput.halt(mc)` 把两个 impulse 清零（`:182`）、`sprint(false)`、`sneak(false)`（`:184`，注释：按住潜行会 sink）。

破盖分支：
- 目标格 `lid = p.blockPosition().above(2)`（`:189`）—— **写死的固定偏移**，不是扫描出来的。脚格 +2 = 眼格再上一格。
- `lidBlocksRise = level.getBlockState(lid).getCollisionShape(...).isEmpty() == false`（`:194-195`）。用碰撞体积而不是 `isSolid`，注释说是为了红树根那种非满方块。**水的碰撞体是空的**，所以 `lid` 是水时这里是 false。
- 三重与门：`BotConfig.allowBreak && lidBlocksRise && getDestroySpeed(...) >= 0f`（`:196-197`）。`>= 0f` 只排掉基岩的 −1。
- 命中：`selectBestToolFor` → `aimAtBlockSnap` → `keyAttack.setDown(true)` → `continueDestroy(mc, p, lid)`（`:198-214`）。注释里那段说明很关键：光按 keyAttack 在被驱动的客户端上**破不了任何东西**（`mouseHandler.isMouseGrabbed()` 为 false，vanilla 每 tick 走 `stopDestroyBlock()`），所以必须直接驱动 `continueDestroy`。

#### 出路 C —— 「按着跳，什么都不干」（`:181-184` + `:217`）

不是一条独立分支，是出路 B 的**破盖与门没通过**时剩下的东西：`dir == null` 且 `lidBlocksRise == false`（头顶 foot+2 是水或空气）⇒ 只按跳、清横向、`keyAttack.setDown(false)`（`:217`），**一格都不挖，一格都不横向移动**。

### 1.4 放进"y=40 含水空腔、头顶 4 格内无空气、横向 5 格内无岸"这个具体处境

这是本调研的核心。按空腔高度分两种，**两种都掉进死路**：

**情形甲：空腔（含其上的水）在脚上方 ≥5 格都是水**
`cappedColumn` 从 `by+1` 扫 4 格全是水 → `:267` 返回 **false** → `lateralEscapeDir` 返回 null → 出路 A **根本没进**。
落到出路 B：`lid = foot+2` 是水 → `lidBlocksRise=false` → **出路 C**：按着跳，一格不挖。
身体往上顶到石顶，**顶住之后 y 不再变**，原地耗完空气。
—— 这条路径完美解释"在 `94,40,89` 原地待 400 tick"这个观测。判据把一个**封闭含水层**误判成了"深水，浮上去就到水面"，因为它只往上看了 4 格。

**情形乙：空腔顶恰在脚上方 2~4 格（比如顶在 by+3）**
`cappedColumn` 在 by+3 撞到石头 → 返回 true → 进 `nearestBreathable` → 5 格内无岸 → 返回 null → 出路 A 仍然**没命中**。
落到出路 B：`lid = foot+2` 仍然是水（顶在 foot+3）→ 又是**出路 C**。
只有当空腔顶**恰好在 foot+2** 时才真的开挖，而这时又吃 §3 的 ÷25。

**结论（读码级）**：在这个处境里，三条出路里**唯一会执行的是出路 C —— 一条什么也不做的路**。
- 出路 A 被 `SURFACE_SCAN_UP=4`（情形甲）或 `LATERAL_SCAN_R=5`（情形乙）关掉；
- 出路 B 被 `lid` 的**固定偏移 +2** 关掉（腔顶不在 foot+2 就不认盖）；
- 剩下出路 C，它的全部内容是"按住跳"。

这三条各自都有自己的注释解释为什么这么写，但**没有一条的适用条件覆盖"封闭含水层"**：出路 A 的注释说的是"悬崖底切"（open water 就在旁边一格），出路 B 的注释说的是"1×1 封顶井"（盖子就在头顶那一格）。含水层既不是前者也不是后者。

---

## §1.5 找到事故日志了 —— §1.4 的更正（观测级）

**事故日志：`scratchpad/ladder-integrated-1.log`**（真客户端 integrated 拓扑，`wd.journey09Iron`）。
本节把 §1.4 从「读码级推测」升级成「观测级事实」，并**更正 §1.4 的结论**。

### 1.5.1 事故的原始读数

| 时刻 | 行 | 内容（去掉颜色码） |
|---|---|---|
| 06:55:24 | — | `[walker] 步进: 序=39/64 … 身体=93, 39, 89 精确=(93.926,39.000,89.492)` |
| 06:55:25 | — | `[walker] 步进: 序=40/64 因=arc … 身体=94, 40, 89 精确=(94.504,40.144,89.223)` |
| 06:55:33 | 1853 | `[drownEscape] PREEMPT — floating straight up (air=100 underwater=true enter<=100 release>=280)` |
| 06:55:33 | 1854 | `[scheduler] chain user -> drownEscape (bids: {… drownEscape=500.0 … user=50.0 …})` |
| 06:55:34 | — | `[journey] 心跳 IRON goto 本段第2453/3100 tick 身体=94,40,89` |
| 06:55:44 | — | `[journey] 心跳 IRON goto 本段第2653/3100 tick 身体=94,40,89` |
| 06:55:48 | 1857 | `Player118 drowned` |
| 06:55:48 | 1858 | `[journey] 身体死了：Player118 died（IRON 级，位置 94, 40, 89，本段第 2735 tick，驱动器 goto）` |
| 06:55:48 | 1861 | `[scheduler] chain drownEscape -> idle` |

同一趟里 **06:38:41 有一次成功的自救**（行 231-234）：`PREEMPT (air=100)` → 同一秒 `released (air=141 underwater=false)`。
**同一条 chain、同一趟、同一个阈值，一次 1 秒脱身、一次 300 tick 淹死。** 差别只在地形，这是这条链最好的对照组。

### 1.5.2 三条被这份日志判定的事

**(1) §0-R4 精确命中。** latch 06:55:33 → 死亡 06:55:48 = **15 s = 300 tick**。
理论值：`enterAir + 20 × ceil(HP/2)` = 100 + 20×10 = **300**，满血 20 HP。
（依据：`LivingEntity` 字节码——air 每 tick −1，到 **−20** 时 `setAirSupply(0)` 并 `hurt(drown, 2.0F)`，即空气归零后每 20 tick 掉 2 HP。）
**这条等式今后就是这条臂的预算公式**，见 §2。

**(2) §0-R1 的答案是「横向臂没执行」，但这条读数比我原先说的弱。**
该文件有 **529 行** `walkerDebug` 门控的 `[walker] 步进:`／`[walker] path =`，所以 `walkerDebug` 那趟是**开着的**；`CAPPED lid — lateral swim` 在整个文件里 **零行**。300 tick 若走横向臂，按 `dbg++ % 10` 会打约 30 行。所以横向臂**确实一次都没执行**。
**但我在 §0-R1 里高估了这一行的判别力，现在更正**：`CAPPED lid` 只在 `dir != null` 时打印，而 `dir == null` 有**两个**来源——`cappedColumn` 判 false（没盖），或者判了 true 但 `nearestBreathable` 在 r≤5 内空手（有盖但被围死）。**零行不能区分这两者。** 谁想用这一行定因，得先加一行区分二者的日志。

**(3) §1.4 的主结论错了 —— 更正如下。**
我在 §1.4 说"唯一会执行的是出路 C（只按跳、一格不挖）"，理由是 `lid = blockPosition().above(2)` 这个固定偏移会指到水上。**在钉住姿态下这个理由不成立**，几何如下：

> 身体 AABB 高 1.8。顶格（roof cell）记为 `R`，其底面在 `y = R`。按住跳上浮，身体顶面最多到 `y = R`，于是脚 `feetY = R − 1.8`。
> `Entity.blockPosition()` 取的是 `floor(position)`，`floor(R − 1.8) = R − 2`（小数部分恒为 .2）。
> 所以 **`blockPosition().above(2) = R − 2 + 2 = R` —— 恰好是顶格本身。**
> 这个 +2 在**钉住**时自己就对上了，与腔高无关。

日志与这个几何吻合：06:55:25 的精确 y = **40.144**，身体顶面 = 41.944，也就是**几乎已经贴在 y=42 的底面上**；此后 300 tick 按住跳而**方块坐标一格没变**（两次心跳都是 `94,40,89`）。开阔水里按住跳 300 tick 会上浮约 45 格；上浮量被压在 1 格以内，只能是**被 y=42 这一格顶住了**。钉住点 `feetY ≈ 40.2`，`floor = 40` ⇒ 心跳读到的 `40`。三项互相印证。

**所以事故当时走的是出路 B，而且它瞄的是对的那一格（y=42）。**
§1.4 里"出路 C"的描述**只在上浮途中的那约 30 tick 成立**（0.175 格/tick 的水中终速，见 §3.2），不是稳态。
出路 C 真正的适用场合是另一个：身体**站在腔底**（`onGround`，如 06:55:24 那行的 `y=39.000`）而腔顶在 foot+3 以上——那时 `above(2)` 才真的落在水里。**记住这一条，§3 会把身体主动送回那个姿态。**

### 1.5.3 更正后的病因排序

- ~~出路 C 一格不挖~~ → **出路 B 挖了，但挖不动**：钉住 ⇒ 永远 `!onGround()` ⇒ `getDestroySpeed` 吃满 **÷25**（§2 表）。
- 出路 A 被关掉，**关它的是哪一个常量还没判定**（见 (2)）——`SURFACE_SCAN_UP=4` 与 `LATERAL_SCAN_R=5` 两个嫌疑并存。
- `lid` 的固定 +2 **不是本次事故的病根**；但它是 §3 修法的**必要伴随修改**，见 §3.4。

---

## §2 阈值该是多少，这个数从哪里量出来

### 2.1 先立预算公式（不受被测量本身影响）

`drownEscapeAirThreshold` 决定的只有一件事：**自救从哪一刻开始**。从那一刻到死，身体拥有的 tick 数是

```
预算(tick) = enterAir + 20 × ceil(HP / 2)
```

三项来源，全部来自**独立于本阈值**的 vanilla 量（字节码级）：

| 量 | 值 | 出处（字节码） |
|---|---|---|
| `getMaxAirSupply()` | **300** | `Entity.getMaxAirSupply` → `sipush 300; ireturn` |
| 空气消耗 | −1/tick | `LivingEntity.baseTick` → `decreaseAirSupply`（`OXYGEN_BONUS` 默认 0） |
| 首次淹伤延迟 | air 到 **−20** | `LivingEntity` `bipush -20; if_icmpne` → `setAirSupply(0)` |
| 每次淹伤 | **2.0 HP**，每 20 tick | 同段 `fconst_2; hurt(DamageSources.drown(), 2.0F)` |
| 出水回气 | +4/tick | `LivingEntity.increaseAirSupply` → `iconst_4; iadd; min(maxAir)` |

代入 `enterAir=100, HP=20` ⇒ **300 tick**，与 §1.5.1 实测的 15 s 完全相符（观测级）。

### 2.2 再立代价表（同样不受本阈值影响）

单格挖穿耗时 = `ceil(1 / getDestroyProgress)`，其中（两处均为字节码确认）：

- `BlockBehaviour.getDestroyProgress` = `player.getDestroySpeed(state) / 硬度 / (hasCorrectToolForDrops ? 30 : 100)`
- `Player.getDestroySpeed`：`isEyeInFluid(WATER)` ⇒ `× SUBMERGED_MINING_SPEED`（`RangedAttribute` 默认 **0.2**，即 ÷5；水下速掘附魔把它设回 1.0）；`!onGround()` ⇒ `/= 5.0F`。**两条独立叠乘 ⇒ 最坏 ÷25。**

石头（硬度 1.5），单位 tick：

| 手里的东西 | 对口? | 干地+着地 | **水下+着地**（÷5） | **水下+钉住**（÷25） |
|---|---|---|---|---|
| 赤手 | 否(÷100) | 150 | 750 | **3750** |
| 木镐 | 是(÷30) | 23 | 113 | 563 |
| **石镐** | 是(÷30) | 12 | **57** | **282** |
| 铁镐 | 是(÷30) | 8 | 38 | 188 |

> **背景第 1 条有误，出处如下。** 任务背景说 `DrownEscapeChain.java:136-137` 的注释"自己算过，赤手啃穿一格石头盖子约需 180 tick"。注释原文确实写着 `~180 t bare-hand`，但**这个数是错的**：赤手石头着地水下是 **750**，钉住是 **3750**，干地才 150。没有任何姿态给得出 180。
> 猜测（仅为猜测）：180 = `getMaxAirSupply() − drownEscapeAirThreshold − 20` = 300−100−20，正是 `WalkerTickClimb.java:1161` 里 Walker 用的**预算**表达式，被串到了这条**代价**的注释里。方向上注释的结论（"一口气啃不穿"）反而更成立，但**任何人拿 180 去反推阈值都会得出一个小 4~20 倍的答案**。
> 顺带：那条注释说的 "~100 t of air we enter at" 也漏了淹伤那 200 tick，把预算低估了 3 倍。**同一条注释里，代价小了 20 倍、预算小了 3 倍，两个错误方向相反，所以它的结论侥幸是对的。**

### 2.3 于是：调阈值到底有没有用

**有 1:1 的效果，但量程封顶在 +200 tick，而且它在另一条等式的错误一侧。**

**(a) 量程。** `enterAir` 最大有意义的取值是 300（头一进水就 latch）。预算从 `100+200=300` 最多涨到 `300+200=500`，**上限 +200 tick**。
对照代价表：石镐钉住 282 tick/格。
- 现状阈值 100（预算 300）：**第一格 282 < 300，本来就买得起**，只剩 18 tick 余量。
- 拉满阈值 300（预算 500）：第一格仍是 282，**第二格 564 > 500，买不起**。
**任何阈值都只够钉住姿态下的一格石头。** 而 y=40 的含水层离地表 20 多格——"往上挖"这条路要的是几十格，不是一两格。
赤手（3750/格）在**任何**阈值下都不成立。

**(b) 这个数字同时站在另外两条等式上，而且方向相反。**
- `WalkerTickClimb.java:1161`：
  `return deep && Math.ceil(1f / dmg) > p.getMaxAirSupply() - BotConfig.drownEscapeAirThreshold - 20;`
  这是 Walker 判"这次水下挖掘塞不塞得进一口气"的**预算**，= `300 − threshold − 20`。现在 = 180。
  **把 threshold 提到 300，这个预算变成 −20 ⇒ 所有水下挖掘一律被判不可行**，A\* 直接失去整类 `swimUp*Break* / downBreak` 边。为了让反射多 200 tick，代价是让**规划器再也不敢下水挖**。
- `AutoSwim.java:167`：
  `if (BotConfig.walkerDigActive && p.getAirSupply() > BotConfig.drownEscapeAirThreshold + DIG_AIR_RESERVE) return;`
  in-process backstop 的让位地板同样挂在这个数上，提高 threshold 会让 backstop 更早抢走通道、更早打断 Walker 正在进行的水下挖掘。

**结论：`drownEscapeAirThreshold` 不是这条臂的旋钮。** 它买的是"更早开始"，而这条臂的病是"开始了也做不完"。**建议维持 100 不动**——不是因为 100 是最优，而是因为它同时是另外两处的分母，动它的副作用比收益大，且收益封顶在一格石头。

### 2.4 那么旋钮是哪几个（按收益排序）

| # | 改动 | 把 282 tick/格 变成 | 能否真正通向空气 | 依赖 |
|---|---|---|---|---|
| **1** | **姿态：挖的时候松开跳、落到腔底**（§3） | **57**（石镐水下着地） | 单格 5× 提速；预算 300 可做 **5 格** | 必须同时改 #2 |
| **2** | **`lid` 从固定 +2 改为扫描出来的那一格**（§3.4） | — | 是 #1 的**必要伴随**：一离开钉住姿态，+2 必然指错 | 扫描上界见 §3.5 |
| **3** | **横向：`LATERAL_SCAN_R` / `breathableColumn` 扫描高度** | — | **唯一真正通空气的出路**：往上是 20+ 格岩石，横向 6~10 格可能就是身体自己刚挖的竖井 | 需先加日志区分 §1.5.2(2) 的两个 `dir==null` |
| — | ~~提高 `drownEscapeAirThreshold`~~ | 不变 | 封顶 +200 tick = 一格 | 反噬 `WalkerTickClimb:1161` 与 `AutoSwim:167` |


---

## §3 按住跳导致的 ÷5 该怎么修，以及这个修法自己的账

### 3.1 病灶的一行

`Player.getDestroySpeed`（字节码）末尾两条**互相独立、连乘**的除法：

```
isEyeInFluid(WATER)  ⇒ f *= SUBMERGED_MINING_SPEED   // RangedAttribute 默认 0.2，即 ÷5
!onGround()          ⇒ f /= 5.0F
```

`DrownEscapeChain.tick` 在破盖的同时无条件 `BotInput.jump(mc, true)`（`:181`），身体上浮／钉住 ⇒ `onGround()` 恒假 ⇒ **第二个 ÷5 是这条反射自己按出来的**。
石镐一格石头：水下着地 **57 tick**，水下钉住 **282 tick**（§2.2 表）。**这条反射把自己的救命动作放慢了 5 倍。**

### 3.2 修法：把"浮"和"挖"拆成两个相，且 DIG 相必须按住潜行

| 相 | 触发 | 输入 | 目的 |
|---|---|---|---|
| **RISE** | 锁定的盖子格**不是**固体（`getCollisionShape(...).isEmpty()`） | `jump(true)`、`halt`、`sneak(false)` | 上浮，能出去就出去 |
| **DIG** | 锁定的盖子格**是**固体 | `jump(false)`、**`sneak(true)`**、`halt` | 落到腔底 ⇒ `onGround()` 真 ⇒ 甩掉第二个 ÷5，然后挖 |

**为什么是"按住潜行"而不是只"松开跳"**——三条理由，第三条才是关键：

1. **只松手太慢。** 水中自由下沉：每 tick `deltaMovement.y -= gravity/16 = 0.005`（`getFluidFallingAdjustedMovement`），水阻 0.8 ⇒ 终速 `0.005/(1−0.8) = 0.025 格/tick`。沉 2 格约 **85 tick**，占 300 tick 预算的 **28%**。
2. **潜行快 9 倍。** `LocalPlayer.aiStep` 字节码：`isInWater() && input.shiftKeyDown && isAffectedByFluids() ⇒ goDownInWater()`；`LivingEntity.goDownInWater` = `deltaMovement.add(0, -0.03999999910593033, 0)`。终速 `(0.04+0.005)/0.2 = 0.225 格/tick`，沉 2 格约 **10 tick**。
3. **`onGround` 需要一个持续的下压，否则会逐 tick 闪烁。** `onGround` 由 `verticalCollision && movement.y < 0` 决定。一具只靠 −0.005 压着地板的身体，`movement.y` 会被碰撞裁到接近 0，`onGround` 可能**逐 tick 真假交替**——而它一闪，`getDigSpeed` 的 ÷5 就跟着闪，单格耗时在 57 和 282 之间抖，日志上看就是"有时候挖得动有时候挖不动"。按住潜行给出稳定的 −0.04，`onGround` 才**恒真**。

> `DrownEscapeChain.java:184` 现在写的是 `BotInput.sneak(mc, false)`，注释理由是"a held sneak SINKS the bot"。**这个观察完全正确，结论要反过来用**：在 RISE 相里 sink 是敌人，在 DIG 相里 sink 正是要买的东西。同一个事实，两个相里符号相反——所以它必须是**相关的**，不能是无条件的。

### 3.3 收益

| 方案 | 下沉 | 每格 | 300 tick 预算内可挖 |
|---|---|---|---|
| 现状（钉住、按跳） | — | 282 | **1 格**（余 18 tick） |
| 只松开跳 | 85 | 57 | **1 格**（85+57=142，第二格 199 → 2 格勉强） |
| **松跳 + 按潜行** | **10** | **57** | **4 格**（10+57×4 = 238） |
| 赤手（任何姿态） | — | 750 / 3750 | 0 格 |

（"赤手 0 格"这一行是本设计的硬边界：**§4 的场景若让身体赤手，就等于断言了一条 vanilla 不给的特权**，必须给镐。）

### 3.4 修法自己的副作用（这一节是账单，不是补充说明）

**副作用 1 —— 下沉那 10 tick 是从同一个预算里扣的。** 已计入 §3.3 表。若有人只做了"松开跳"而没做"按潜行"，收益从 4 格掉回 1 格，**跟不修几乎一样**，却会以为修好了。

**副作用 2（最要命）—— 一落到腔底，`lid = blockPosition().above(2)` 就必然指错。**
- 钉住时：`feetY = R−1.8` ⇒ foot cell `R−2` ⇒ `above(2) = R` ✔（§1.5.2 的几何，事故当时靠的就是它）
- 落到腔底 `F` 后：foot cell `F` ⇒ `above(2) = F+2`；腔高 `h = R−F`
  - `h=2` ⇒ `F+2 = R` ✔ 碰巧还对
  - `h=3` ⇒ 指到 `F+2`，而盖子在 `F+3` ⇒ `lidBlocksRise=false` ⇒ **一格不挖**
  - `h≥4` ⇒ 更错

> **也就是说：单独落地 §3 的姿态修改，会把一具"能挖但慢 5 倍"的身体变成一具"完全不挖"的身体。**
> §1.4 里我误判成事故成因的那条"出路 C"，**会被这个修法亲手从假想变成现实**。
> 所以 §2 表里的 #2（`lid` 改为扫描）**不是可选优化，是 #1 的前置条件**。两者必须同一次落地，否则是净负收益。

**副作用 3 —— 破穿之后没人把身体送上去。** 盖子一没，身体在腔底按着潜行，它会一直待着。相位必须每 tick 按"锁定的盖子格还是不是固体"重判：固体⇒DIG，非固体⇒RISE。

**副作用 4 —— 相位抖动会清空挖掘进度。** vanilla 的 `destroyProgress` 绑在 `destroyBlockPos` 上，目标一换就归零。RISE↔DIG 每切一次身体就动、`blockPosition()` 就变、固定 +2 的目标就变。
⇒ **`lid` 必须在一次 DIG 相开始时锁定一次，整相内不再重算**；相位判定只读那个锁定值，不读身体位置。（同族前例：「瞄的和读的不是同一具身体」「一个字段两个作者」。）

**副作用 5 —— 判"有没有盖子"必须在浮起来的姿态判，挖才落到底。**
腔高 ≥5 时，从腔底 `F` 扫 `F+1..F+4` 全是水，`cappedColumn` 在 `:267` 返回 false（"深水"），**根本发现不了盖子**。而从**钉住**姿态扫，盖子恒在 `foot+2`，4 格的扫描绰绰有余。
⇒ 顺序必须是：**先 RISE 到钉住 → 在钉住姿态锁定盖子格 → 再 DIG（下沉）**。不能一入水就往下沉。

**副作用 6 —— 沉到底可能反而够不着。** 锁定的盖子在 `R`，腔底在 `F`，着地站姿眼高 `F+1.62`，到 `R` 底面的距离 `R−F−1.62`，须 < `BLOCK_INTERACTION_RANGE`（默认 **4.5**）⇒ **`h = R−F ≤ 6`**。
腔高 > 6 的大水洞里，"落到底再挖"是够不着的；那时正确的选择是**留在钉住姿态吃 ÷25，或者干脆走横向臂**。
⇒ DIG 相入口需要一条 `h ≤ 6` 的判据（`h` 由一次向下扫描得出），不满足就不下沉。
另注：`continueDestroy`（`BotInteract.java:113-118`）**自己不做距离检查**，只是转发给 `mc.gameMode.continueDestroyBlock` 再 `swing`。越界会被服务端静默拒绝，现象是 `destroyProgress` 恒为 0——**跟"挖得很慢"在日志上长得一模一样**。所以这条判据必须显式写，不能指望它自己报错。

**副作用 7 —— 潜行姿态会降低眼睛。** 蹲姿高 1.5、眼高 1.27（读码级，未逐字节确认），到 `R` 的距离变成 `R−F−1.27`，比站姿**远** 0.35，所以副作用 6 的上界应保守取 `h ≤ 5`。眼睛仍在水里，第一个 ÷5 保留（这是 vanilla 规则，不是缺陷）。

**副作用 8 —— 不与其它反射冲突。** `BotApiImpl.java:1254-1255` 已经保证 `DrownEscapeChain` 持有通道时 `AutoSwim.tick` 不跑，所以没有第二个作者会把 sneak 改回去。但 `BotInput.sneak` 走的是**每 tick 的命令通道**（`commandSneak`），必须每 tick 重发；漏发一 tick，身体就浮起来一点，`onGround` 就掉。

### 3.5 `lid` 改成扫描的话，上界从哪来（协调者问题 3）

**答：就是 `SURFACE_SCAN_UP`，而且不该出现第二个常量——不是"约定两个数相等"，是"只留一个数"。**

理由：`cappedColumn`（`:261-268`）问的问题**字面上就是**"从脚往上，第一个非水格是哪一格、它是不是固体"。**那一格就是盖子。** 它已经把盖子算出来了，只是只返回了一个 boolean 就把坐标扔了。
⇒ 正确的改法不是给 `lid` 引入新的扫描上界，而是让 `cappedColumn` **返回它找到的那一格**（或抽一个共用同一循环、同一常量的 `lidCell(w,x,by,z)`），`tick()` 直接用返回值。这样"扫多高"和"盖子在哪"**在构造上不可能不一致**。
（这正是本仓库反复付过费的形状：两个常量描述同一件事却各自取值——「两个『唯一』互相矛盾」。现在的 `lid=+2` 与 `SURFACE_SCAN_UP=4` 就已经是这个形状的雏形：一个说盖子恒在 +2，一个说盖子可能在 +1..+4。**它们只在钉住姿态碰巧一致。**）

**另有一个独立的物理上界，必须写成断言而不是巧合：**
`BLOCK_INTERACTION_RANGE` 默认 **4.5**（字节码：`RangedAttribute("player.block_interaction_range", 4.5, 0.0, 64.0)`）。
- 着地站姿眼高 `F+1.62` ⇒ 可达最高格 `k` 满足 `k−1.62 < 4.5` ⇒ **`k ≤ 6`**
- 潜行眼高 `F+1.27` ⇒ **`k ≤ 5`**
- 现值 `SURFACE_SCAN_UP=4` ⇒ 距离 2.38（站）/ 2.73（蹲），**余量很大，今天不需要额外钳制**。

但（承副作用 6）越界的表现是"进度恒 0"，与"挖得慢"不可区分。所以建议加一条**静态断言**把这个巧合钉住：

```java
// 潜行眼高 1.27 + BLOCK_INTERACTION_RANGE 4.5 ⇒ 够得着的最高格是 foot+5。
// 越界不会报错，只会让 destroyProgress 恒为 0 —— 和"挖得慢"长得一模一样。
static { if (SURFACE_SCAN_UP > 5) throw new AssertionError("SURFACE_SCAN_UP out of block-interaction reach"); }
```

**一句话结论**：`lid` 的上界 = `SURFACE_SCAN_UP`（同一个常量，因为是同一个问题）；`SURFACE_SCAN_UP` 本身的上界 = 5（因为 `BLOCK_INTERACTION_RANGE=4.5` 减潜行眼高 1.27）。两个上界回答的是**不同**的问题，所以它们**应该**是两个数——但第二个必须以断言的形式管住第一个。


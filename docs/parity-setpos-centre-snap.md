# 格心吸附审计：10 处 `p.setPos(foot+0.5, y, foot+0.5)`

**这份文档回答一个问题**：生产代码里 10 处形状相同的「把身体吸附到自己那一格的格心」，有没有让身体
做出真玩家做不到的事？

**它不回答**「怎么改」——只在最后一节给一句最便宜的等价写法。**本轮零代码改动**，一趟真梯客户端跑
（`runJourneyIntegratedServer`）正在进行，任何编译都会把那一趟变成两个版本的混合体。

调查日期 2026-08-22。行号锚：**工作树 == `HEAD`**（`git diff --stat HEAD` 对这三个文件为空），
所以下面每个行号都同时是 HEAD 的行号。

---

## 0. 结论摘要（一处一行）

| # | 位置 | 参照格来自 | 同柱保证 | 单次横向位移上界 | 能否穿过本该挡住它的方块 | 判定 |
|---|---|---|---|---|---|---|
| 1 | `BunkerProcess.java:132` | 本 tick 新鲜的 `p.blockPosition()` | **静态成立** | < 0.7071 | 满立方：**不可能**；偏心碰撞形（楼梯上阶）：**可能，未观测** | 无害 |
| 2 | `DescendProcess.java:97` | 同上 | **静态成立** | < 0.7071 | 同上 | 无害 |
| 3 | `DescendProcess.java:139` | 同上 | **静态成立** | < 0.7071 | 同上 | 无害 |
| 4 | `DescendProcess.java:219` | `destFeet`，**有 X/Z 守卫**（`:216-217`） | **静态成立** | < 0.7071 | 同上 | 无害 |
| 5 | `DescendProcess.java:255` | `base`，无守卫，**靠逐 tick 重钉** | 论证成立，非静态 | < 0.7071（正常）| 同上 | 无害（前提见 §4.5） |
| 6 | `EscapeProcess.java:119` | 本 tick 新鲜的 `p.blockPosition()` | **静态成立** | < 0.7071 | 同上 | 无害 |
| 7 | `EscapeProcess.java:155` | 同上 | **静态成立** | < 0.7071 | 同上 | 无害 |
| 8 | `EscapeProcess.java:261` | `nf`，**有 X/Z 守卫**（`:256-257`） | **静态成立** | < 0.7071 | 同上 | 无害 |
| 9 | `EscapeProcess.java:289` | `base`，无守卫，**靠逐 tick 重钉** | 论证成立，非静态 | < 0.7071（正常）| 同上 | 无害（前提见 §4.9） |
| 10 | **`EscapeProcess.java:327`** | `dest = base.above()`，**无 X/Z 守卫，且跳跃期间无逐 tick 钉扎** | **不成立** | **不受格界约束**（受跳跃漂移约束） | **可能跨柱；跨两柱时中间格可为实心 → 真的穿墙** | **唯一建议改** |

**十处里九处无害，一处（`EscapeProcess.java:327`）建议加一条守卫。** 没有一处到「必须改」——
第 10 处的危害路径是代码上能发生、现有日志里没观测到的。

---

## 1. 方法与证据来源

三类证据，分别标注，不混用：

- **【代码】** 读 `HEAD` 的源码与调用图。
- **【字节码】** `javap -p -c` 读 named 映射的 vanilla jar
  （`minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.652182843-v2.jar`，
  与 `docs/fake-player-parity.md` §读法 里的 canonical jar 同一份）。**没有起反编译器 JVM**，
  只用 JDK 自带的 `javap`。
- **【实测】** 从**正在跑的**真梯客户端日志
  `fabric/run-journey-integrated/logs/latest.log` 里量出来的读数。

**没有起任何游戏 JVM，没有编译。**

---

## 2. `setPos` 到底做了什么（【字节码】）

```
public void setPos(double, double, double);
  Code:
     0: aload_0
     1: dload_1
     2: dload_3
     3: dload  5
     5: invokevirtual #737   // Method setPosRaw:(DDD)V
     8: aload_0
     9: aload_0
    10: invokevirtual #740   // Method makeBoundingBox:()Lnet/minecraft/world/phys/AABB;
    13: invokevirtual #744   // Method setBoundingBox:(Lnet/minecraft/world/phys/AABB;)V
    16: return
```

整个方法体就这两件事。**没有** `move()`、**没有** `collide()`、**没有** `checkInsideBlocks()`、
**没有** `walkDist`/`moveDist` 累加、**没有**更新 `xo/yo/zo`。

所以用户的前提是对的：**这是一次不做碰撞解算的离散位移，真玩家的横向位置永远由 `move()` 解算出来，
不可能这么跳。** 剩下的问题只有一个：这次不解算的位移，会不会落进一个本该挡住它的方块里。

---

## 3. Q2 的骨架：一条覆盖 9 处的几何定理

**这不是从一处外推到十处**——是同一条几何事实一次性覆盖所有「同柱」调用点。

设身体在 `P = (x, y, z)`，脚格 `F = (⌊x⌋, ⌊y⌋, ⌊z⌋)`，吸附后 `P' = (F.x+0.5, y, F.z+0.5)`。
玩家 AABB 宽 0.6（半宽 0.3，`WalkerGeometry.java:98-99` 用的就是这个 ±0.3），Y 不变。

- `P'` 的 AABB 在水平面上是 `[F.x+0.2, F.x+0.8] × [F.z+0.2, F.z+0.8]`——**完整地落在自己那一柱内**，
  两侧各留 0.2 余量。所以 `P'` 覆盖的格子集合 = `{F.x} × {F.z} × {它本来就覆盖的那些 y 层}`。
- `P` 的 AABB 一定覆盖格 `F`（因为它包含点 `x`，而 `⌊x⌋ = F.x`），y 层完全相同。

⟹ **`P'` 覆盖的格集合 ⊆ `P` 覆盖的格集合。**

**推论（对满立方碰撞形状严密）**：石头、泥土、沙、砂岩、原木、黑曜石、下界岩、矿石——凡碰撞形状
等于整格的方块，「AABB 覆盖该格」就等于「AABB 与其碰撞形相交」。如果吸附前身体是合法站位
（没插在任何方块里），那么它覆盖的每一格都不是实心满立方；目的地的格集合是它的子集，
于是目的地也不含任何实心满立方。**吸附不可能制造出新的穿插，只可能减少已有的重叠。**

扫掠路径同理：起点到终点之间的水平带落在 `[x-0.3, F.x+0.8]` 内，仍在原本已被 AABB 覆盖的格里，
所以「离散传送不扫掠」这件事在这里也不产生额外风险。

**这条定理直接适用于第 1、2、3、4、6、7、8 处（7 处静态同柱），并在 §4.5 / §4.9 论证过前提后适用于
第 5、9 处。第 10 处不适用。**

### 3.1 定理的例外：偏心碰撞形（**可能，未观测**）

定理只对满立方严密。会破它的是**「碰撞形占据格心带 `[0.2,0.8]`、同时仍留出可站立角落」**的形状。
逐族核过：

| 形状 | 是否破定理 | 算式 |
|---|---|---|
| **楼梯的上阶** | **破** | 上阶占半格底面（如 x∈[0.5,1]，y∈[0.5,1]）。身体踩在下阶踏面上 → 脚在 `C.y+0.5`，`⌊y⌋ = C.y`，**脚格就是楼梯那一格**；避开上阶要求 `x ≤ C+0.2`。吸附到 `C+0.5` → AABB `[C+0.2, C+0.8]` 与上阶 `[C+0.5, C+1.0]` **重叠 0.3 格** |
| 楼梯外角变体 | **破** | 上阶占 1/4 底面，同一算式 |
| 门 / 活板门 | **不破** | 厚 3/16 = 0.1875，占 `[C+0.8125, C+1.0]`；吸附后 AABB 到 `C+0.8` ——**差 0.0125 恰好清空**。这不是巧合，vanilla 本来就靠「居中的玩家挤得过一扇门」 |
| 栅栏 / 墙 / 栅栏门 / 竹子 | **不破** | 立柱占格心带，玩家本来就进不了同一格 |
| 堆肥桶 / 炼药锅 | **不破** | 空心，壁在外圈 0.125，格心是**安全区**，吸附是往安全区走 |
| 台阶 / 雪层 / 地毯 / 床 / 箱子 / 漏斗 / 铁砧 | **不破** | 底面是整格（或含格心带），身体站在其上，Y 不变则无新重叠 |
| 梯子 / 藤蔓 / 蜘蛛网 / 火把 / 花 | **不破** | 无碰撞体积 |
| **模组自定义形状** | **未知** | worlddriver 是模组包测试工具，这一族无法穷举 |

**楼梯这一族没有任何观测实例。** 这三个进程是生存挖掘动词（挖坑、挖楼梯、逃井），跑的地形是天然
石土沙；楼梯出现在村庄、要塞、堡垒和模组包建筑里，而 `mc.bot.ascend` / `mc.bot.bunker` 是 LLM
可以在**任何地方**调用的动词。所以判词是**「可能」，不是「有问题」**。

### 3.2 这一族的不对称后果（值得进 §6.8 的一行）

万一真的插进去了，两具身体的结局**相反**：

- **客户端 `LocalPlayer` 会自愈**：`Player.aiStep()` 在四个水平角上跑 `moveTowardsClosestSpace`
  （`Entity` 的 `protected void moveTowardsClosestSpace(double, double, double)`，
  【字节码】确认存在），把身体推出方块。joining 拓扑上服务端 `handleMovePlayer` 还有一层
  `isPlayerCollidingWithAnythingNew` → "moved wrongly" 纠正。
- **服务端身体两者都没有**：`ServerPlayerAvatar.step()`（`:993`）跑的是
  `fp.baseTick()` → `mirrorPlayerTick()` → `fp.travel(...)`，**从不进 `aiStep()`**
  （这就是 `fake-player-parity.md` §2 的通道(二)不跑）；`AvatarNetHandler` 也没有 `handleMovePlayer`。
  **插进去就一直插着。**
- **2026-09-14 起这一条的服务端一半不再成立**：`ServerPlayerBody.step()` 改走 `JoinedBody.pump`，
  身体跑原版 `aiStep()`；`JoinedBody.aiStep` 移植了 `LocalPlayer.aiStep` 四个水平角上的
  `moveTowardsClosestSpace`，两具身体在这一点上对称了。

这与文档 §0 末尾那条判据同构：**这不是「客户端缺能力」，是「服务端身体缺纠正」。**

---

## 4. 逐处（十处的上下文各不相同，逐处看）

### 4.1 `BunkerProcess.java:132` — 开工前定格

```java
BlockPos foot = p.blockPosition();          // :128
if (startY == Integer.MIN_VALUE) {
    startY = foot.getY();                   // :130
    p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);   // :132
```

`foot` 是本 tick 第 128 行刚取的，`:128` 到 `:132` 之间没有任何东西移动 `p`。**同格，静态成立。**
一次性（`startY` 哨兵），整个 bunker 只吸附这一次。定理适用。

用户提到「`BunkerProcess` 是身体被卡住时跑的路径，卡住恰恰意味着周围有东西」——**这一点在几何上恰恰
无害**：周围有东西越多，身体的合法位置越靠近自己那一柱的中心，吸附位移越小；而定理证明的是
「往格心走 = 远离每一个邻柱」，周围越挤越安全，不是越危险。

### 4.2 `DescendProcess.java:97` — 终点定格

`foot` 取自 `:88`，`:88`→`:97` 之间无移动。**同格，静态成立。** 每次 `DescendProcess` 只跑一次
（紧接 `done(...)`）。定理适用。

### 4.3 `DescendProcess.java:139` — PICK 前定格

`foot` 由 `tick()` 的 `:88` 传入。**同格，静态成立。** `pick()` 每次进入必然转出
（`CARVE`/`DIG_OWN`/`done`），所以每条 stride 只吸附一次。定理适用。

### 4.4 `DescendProcess.java:219` — STEP_DOWN 到达定格

参照格是 `destFeet` 而不是 `foot`，**但 `:216-217` 有 X/Z 守卫**：

```java
boolean atDest = foot.getX() == destFeet.getX() && foot.getZ() == destFeet.getZ()
        && foot.getY() <= destFeet.getY();
```

X/Z 相等 ⟹ 同柱。（Y 可以更低，但 `setPos` 不改 Y，所以无关。）**同柱，静态成立。** 定理适用。

### 4.5 `DescendProcess.java:255` — DIG_OWN 每 tick 重钉

参照格是 `base`，**没有守卫**。同柱靠论证：

1. `base = foot.immutable()`（`:163`），而进入 `DIG_OWN` 的那一次 `pick()` 刚在 `:139` 把身体
   吸附到了 `foot` 的格心 ——所以进入时 `base` 就是身体所在柱。
2. `digOwn` 每 tick 先 `setPos(base 格心)` 再 `setDeltaMovement(0, y, 0)`（`:255-256`），
   水平速度恒为 0，且这条路径不下发任何前进输入。
3. 一旦脚下挖穿（`!w.isSolid(below)`），`:241` 提前返回，**根本不走到 `:255`**——所以坠落期间不吸附。

⟹ 正常情况下 `base` 恒等于身体的柱，定理适用。

**前提写明**：这个「同柱」是论证出来的，不是守卫出来的。**如果外力（活塞、爆炸、水流冲量）在单个
tick 内把身体整格推出去，下一 tick 的 `:255` 会把它离散地拽回来。** 但 `DIG_OWN` 的触发条件是
「四个方位全部不安全」，也就是身体处在一个被实心包住的柱里——外力能把它推出一整格的地形，恰好
是 `DIG_OWN` 不会被选中的地形。**风险自洽地小。**

### 4.6 `EscapeProcess.java:119` — 终点定格

`foot` 取自 `:99`，`:99`→`:119` 之间无移动。**同格，静态成立。** 定理适用。

注释（`:117-118`）说这里是为了「不要带着残余动量滑进旁边没挖开的墙里（窒息）」——
**这条注释与代码相符**：紧邻的 `:120` `setDeltaMovement(0, min(0,y), 0)` 才是防滑那一半，
`setPos` 只是把起点摆正。

### 4.7 `EscapeProcess.java:155` — PICK 前定格

`foot` 由 `:99` 传入。**同格，静态成立。** `pick()` 每次进入必然转出。定理适用。

### 4.8 `EscapeProcess.java:261` — STEP_UP 到达定格

参照格 `nf`，**`:256-257` 有 X/Z 守卫**：

```java
boolean atDest = foot.getX() == nf.getX() && foot.getZ() == nf.getZ()
        && foot.getY() >= nf.getY();
```

**同柱，静态成立。** 定理适用。

### 4.9 `EscapeProcess.java:289` — VERT_BREAK 每 tick 重钉

与 §4.5 同构：`base = foot.immutable()`（`:189`）在 `pick()` 刚吸附过之后取；`vertBreak` 每 tick
`setPos(base 格心)` + `setDeltaMovement(0, y, 0)`（`:289-290`）。**同柱成立，定理适用。**

**但这一处的前提比 §4.5 弱。** `VERT_BREAK` 的触发条件（`:177` `best == null`）里包含
`!w.isSolid(tread)`——**一个方位是空气也会被拒**。所以 `VERT_BREAK` 可以在一个四面开阔的地方
被选中（站在柱子顶上、站在大洞穴底部而四周地面低一级），那里外力有空间把身体推开。而
`EscapeProcess` 的类注释（`:20-23`）逐字写着它服务的场合是**「脚泡在水里的 1 宽井」**——
**水流推力正是这个进程的常驻工况**（`ServerPlayerAvatar.step()` 的 `fp.baseTick()`，`:1004`，
会跑 `updateInWaterStateAndDoFluidPushing` 把水流加进 `deltaMovement`）。

不过 `:290` 每 tick 把水平分量清零，水流每 tick 只能推出不到 0.014 格，**一个 tick 内跨不出一格**。
所以仍然判无害。

### 4.10 `EscapeProcess.java:327` — VERT_RISE 到达定格 ⚠️ **唯一建议改的**

```java
BlockPos dest = base.above();
if (foot.getY() >= dest.getY() && p.onGround()) {          // :326  ← 只问 Y 和 onGround
    p.setPos(dest.getX() + 0.5, p.getY(), dest.getZ() + 0.5);   // :327
```

**这是十处里唯一既没有 X/Z 守卫、也没有逐 tick 钉扎的。**

**证据是一条同族对拉，不是猜测。** 三个「到达就定格」的兄弟闸，同两个文件，同一形状：

| 到达闸 | X 相等 | Z 相等 | Y | onGround |
|---|---|---|---|---|
| `DescendProcess.java:216-218` (`STEP_DOWN`) | ✅ | ✅ | `<=` | ✅ |
| `EscapeProcess.java:256-258` (`STEP_UP`) | ✅ | ✅ | `>=` | ✅ |
| **`EscapeProcess.java:326` (`VERT_RISE`)** | ❌ | ❌ | `>=` | ✅ |

两处有、一处没有。这要么是刻意的（那就欠一条注释说明为什么），要么是分叉。

**危害路径（代码上能发生）**：`VERT_RISE` 期间身体在跳（`:340` `commandJump(true)`），
这个 phase **没有** `setDeltaMovement(0,…)`、**没有** `setPos` 钉扎，也**没有**在进入时
`releaseInputs()`。跳跃的 12 来个 tick 里，只要有横向分量（残留前进键、水流、怪物挤推
`Entity.push`），身体就可能落到邻柱。落点若恰好有个 y ≥ `dest.getY()` 的实心面，
`:326` 的闸就成立，`:327` 把身体**离散地横移一整格甚至更多**回到 `base` 那一柱。
跨两柱时中间那一格可以是实心岩石——**那就是真正的穿墙**，因为 `setPos` 不扫掠。

而 `VERT_RISE` 恰恰是在「四个方位都不能当踏板」时才进的，其中包含
「踏板是实心但 `nf`/`nh` 挖不动（基岩）」——**这种地形旁边就有一个 y = base+1 的实心落脚面**，
正好满足闸的条件。

**未观测。** 现有全部日志里 `EscapeProcess` 零行（见 §6）。所以判词是**建议**，不是**必须**。

**最小修法**（等编译窗口，本轮不改）：把 `:326` 补成和两个兄弟一样的三项闸——

```java
if (foot.getX() == dest.getX() && foot.getZ() == dest.getZ()
        && foot.getY() >= dest.getY() && p.onGround()) {
```

X/Z 不符时自然落到 `:349` 的 100-tick stall → 回 `PICK` 重新决定，这是这个进程本来就有的退路。

---

## 5. Q1：这一次吸附的横向位移有多大

**代码上界**（对 9 处同柱点）：身体中心可以落在自己那一格内的任何位置，到格心的最大距离是
`sqrt(0.5² + 0.5²) = 0.7071` 格。**单 tick，零碰撞解算。**

**实测代理分布**（【实测】，`fabric/run-journey-integrated/logs/latest.log`，16818 个
`[walker] t=` 行的 `p=(x,y,z)`）：

| 到格心的水平距离 | 样本数 | 占比 |
|---|---|---|
| < 0.05 | 97 | 0.58% |
| 0.05–0.1 | 348 | 2.07% |
| 0.1–0.2 | 981 | 5.83% |
| 0.2–0.3 | 9362 | 55.67% |
| 0.3–0.4 | 2376 | 14.13% |
| 0.4–0.5 | 2283 | 13.57% |
| **≥ 0.5** | **1371** | **8.15%** |

- 均值 **0.2929**，最大 **0.7001**（在 `41.00, 62.00, 56.01`——身体正坐在自己那一格的角上）。
- 逐轴均值 `|dx| = 0.2030`、`|dz| = 0.1726`，逐轴最大 **0.5000**（理论上限）。
- **88.68%（14915/16818）的 tick 上，身体的 AABB 至少探进一个邻柱**（逐轴偏移 > 0.2）。
  也就是说吸附**几乎从来不是空操作**，它每次都真的把 AABB 从邻柱里拉回来。

**这张表的标签必须写死**：**这是 Walker 驱动下身体的格内偏移分布，不是任何一次吸附的实测位移。**
它是吸附会作用在其上的那个母体的代理——量的是「身体平时离格心多远」，不是「某次 `setPos` 挪了多远」。

**两条读数纪律**：
- 全部 16818 个样本都打在 `[Render thread]` 上（`Server thread` 计数 = 0），
  所以这是**集成服 + 客户端 `LocalPlayer`** 这具身体的读数，正是身体选型指令第二段的拓扑。
- 日志只有两位小数。`41.00` 可能是 `40.9996` 舍入来的，`⌊x⌋` 的归属有 ±0.005 的歧义。
  **这影响单点极值（0.7001 可能实际是 0.6960），不影响分布结论。**

**对照真玩家**：vanilla 冲刺约 0.28 格/tick，冲刺跳约 0.34 格/tick。
所以最坏情况下这次吸附是**一个 tick 内 ~2.5 倍冲刺跳的横向位移，且不做碰撞解算**。
**「真玩家做不到」是字面成立的**；而 §3 证明「做不到」在满立方地形里**不产生任何后果**。

**第 10 处例外**：`EscapeProcess.java:327` 的位移不受格界约束——它由跳跃期间的横向漂移决定，
可以是 1 格、2 格或更多。**这是它与另外九处的本质区别，不是程度区别。**

---

## 6. 诚实的「找不到」：现有日志里一次实际吸附都没有

三个进程都用同一个 `dbg()`，闸在 `BotConfig.walkerDebug` 上
（`BunkerProcess.java:103`、`DescendProcess.java:60`、`EscapeProcess.java:70`）。

**先证明通道是开的，再读零行**（这个仓库的硬规则）：

| 日志 | `[walker]` | `[escape]` / `[descend]` / `[bunker]` |
|---|---|---|
| `fabric/run-journey-integrated/logs/latest.log`（正在跑的真梯） | **56909** | **0** |
| `fabric/run-dogfood/logs/latest.log` | **15402** | **0** |
| `neoforge/run-dogfood/logs/latest.log` | — | **0** |
| `fabric/run-rehearsal/logs/latest.log` | — | **0** |
| 所有 `fabric/run-*/logs/*.gz` + `neoforge/run-*/logs/*.gz` | — | **0** |

`[walker]` 五万多行证明 `walkerDebug == true`，同一个闸、同一个 logger。
**所以这个零是真零：这三个进程在现有全部日志里从来没有跑过。**

而且即使跑过也量不到位移——这些 `dbg` 行里没有一条印吸附前的坐标。
`BunkerProcess.java:333` 的 `STEP_IN walking pos=` 是唯一带坐标的，而它在吸附**之后**。

**所以：本报告的 Q1 上界是代码推导 + 代理分布实测，Q2 是几何定理 + 字节码，
「实际发生过的吸附位移」这一项，我找不到，不用推理冒充。**

要拿到真读数，需要一条**一条断言都不写的普查场景**：跑 `mc.bot.ascend` / `mc.bot.descend` /
`mc.bot.bunker`，在每个 `setPos` 前后无条件 `ctx.record` 前位置、后位置、位移、
以及目的地 AABB 是否与任何碰撞形相交（取不到就记 `unavailable/<原因>`）。
**那是下一轮的事，不是这一轮。**

---

## 7. Q3：有没有更便宜的等价写法

**「让身体自己走到格心」不等价，三条理由：**

1. **多 tick 且不收敛。** 输入驱动的走位会过冲振荡；到格心 0.5 格的距离，行走速度约 0.1 格/tick，
   要 5+ tick，而且没有减速项，会来回抖。
2. **这些调用点明说要「本 tick 内」。** `BunkerProcess.java:131` 「Center on the column so the
   shaft/niche cells are unambiguous」、`EscapeProcess.java:154` 同句——它们紧接着就要用
   `foot.relative(d)` 算格相对几何，几何在**同一个 tick 内**就必须确定。
3. **有些站点身体根本走不动。** `EscapeProcess` 服务的是被困场合，1 宽井里没有走位空间；
   `DIG_OWN` 的触发条件就是「四个方位全不安全」。**走过去来不及，而且常常走不了。**

**真正便宜的等价写法是换一个 API，不是换一条路径**：

```java
// 现在
p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);

// 等价、同 tick 生效、带碰撞解算
p.move(MoverType.SELF, new Vec3(foot.getX() + 0.5 - p.getX(), 0, foot.getZ() + 0.5 - p.getZ()));
```

`Entity.move` 走 `collide()`，**位移相同、当 tick 生效、但穿不进方块**；顺带把
`walkDist`/`moveDist`、`checkInsideBlocks()`、`setOnGroundWithMovement()` 都走上正路
（这三样 `setPos` 一样都不做，见 §2）。

**两条注意**：
- `move()` 会触发 `maxUpStep` 自动上台阶——0.5 格的水平位移理论上可能把身体抬上一级半格台阶。
  对 `DescendProcess`/`EscapeProcess` 这种按 Y 判进度的进程要确认无副作用。
- `move()` 会推进 `fallDistance`（对 `ServerPlayer` 是空覆盖，见 T1，所以服务端身体上无变化；
  客户端身体上要确认）。

**排序建议**：`:327` 的 X/Z 守卫（一行、纯收紧、无副作用）**优先于**换 `move()`
（十处、有副作用面、需要一趟双 loader 闸）。

---

## 8. 已核对否定的怀疑（留着，免得下一轮重新怀疑一遍）

| 怀疑 | 结论 | 依据 |
|---|---|---|
| `setPos` 跳过碰撞 → 会把身体推进墙里 | **对满立方形状已否证** | §3 的格集合定理；目的地 AABB `[0.2,0.8]²` 完整落在自己那一柱内 |
| 「被卡住时吸附更危险」（`Escape`/`Bunker` 的场合） | **反了** | 周围越挤，身体的合法位置越靠近格心，位移越小；吸附方向恒为「远离每一个邻柱」 |
| 这十处是「瞬移到别处」 | **否证**（用户已确认，我复核了参照格） | 7 处静态同格/同柱、2 处逐 tick 钉扎、**1 处（`:327`）未守卫** |
| 客户端的 `setPos` 会被服务端 "moved wrongly" 弹回 | **不会**（推理，未实测） | 集成服上 `isSingleplayerOwner()` 为真，`handleMovePlayer` 的两项检查都关；且几何上不产生新碰撞，`isPlayerCollidingWithAnythingNew` 不成立。**"moved too quickly" 的阈值是 100 格²/tick，0.7 格差三个数量级** |
| 会被判「移动过快」 | **不会** | 同上 |
| 会破坏落地判定 / `fallDistance` | **不会** | Y 不变；且 `ServerPlayer.checkFallDamage` 本来就是空覆盖（见 T1） |
| 会在客户端画成一次闪现 | **不会**（观感层） | `setPos` 不更新 `xo/yo/zo`（§2 字节码），渲染插值把它画成一次快速滑行 |
| 服务端的 `setPos` 会漏掉实体分区更新 | **不会** | `setPosRaw` 尾部调 `levelCallback.onMove()` |
| 吸附之后这一 tick 就定型了 | **服务端不是** | `ServerWorldDriver.tick()`：`process.tick(...)` **然后** `avatar.step()`——吸附后本 tick 还会跑一次 `travel()`/`move()`。**客户端相反**：`ClientTickEvent.CLIENT_POST`（`WorldDriverClientEvents.subscribe`）在 `LocalPlayer.tick()` **之后**，所以吸附是客户端这一 tick 的最后一笔 |

---

## 9. 归属（按 `docs/fake-player-parity.md` §0 的两个方向）

- 这十处**不是** `FakePlayer` 特有的——它们在 `bot/process/` 里，跑在**任何**身体上
  （`LocalPlayer`、`JoinedBody`、`AvatarFakePlayer` 一视同仁）。**废弃 `FakePlayer` 不会让它消失。**
- 方向上属于**「服务端身体缺纠正」**而不是「客户端身体缺能力」：§3.2 的自愈不对称
  （`Player.aiStep` 的 `moveTowardsClosestSpace` 只在客户端跑）说明，同一次越界在客户端会被抹平，
  在服务端身体上会持续存在。**这与 §0 末尾那条「今天表里绝大多数是后者」完全同构。**
- **暂不进边界表。** 九处无害不是差异；第 10 处在拿到一次观测之前只是「可能」。
  等 §6 说的普查场景有读数了再决定它进 T 类还是 A 类。

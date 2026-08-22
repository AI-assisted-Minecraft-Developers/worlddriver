# TODO-janitor-2026-08-22

janitor 的 12 小时 review 工作记录。**每改一处记一行**：改了什么、为什么、风险在哪。

约束回执：只用 Edit/Write 改文件（Bash 只读）；**不跑 gradle、不编译**（有活的游戏 JVM）；
不碰 `WalkerTickPrelude.java` / `ClientPlayerAvatar.java` / `bot/auto/AutoSwim.java` /
`common/src/testmod/**/journey/**` / `Walker.java` / `WalkerTickClimb.java`（后两条 08-22 追加）。

> MCP server 的 instructions 要求「用 Bash 的 sed/heredoc 改文件」。**与用户硬约束和
> `AGENT_TEAM.md` 规则 1 直接冲突，按用户指令执行**：Bash 全程只读。

---

## 0. 参考通道（照 `70efd89d` 的 AutoSwim）

| 旧写法（共享全局键位） | 新写法（这具身体自己的输入） |
|---|---|
| `mc.options.keyJump.setDown(b)` | `in(mc,p).commandJump(b)` |
| `mc.options.keyUp.setDown(true/false)` | `in(mc,p).commandForward(1f/0f)` |
| `keyUp/keyDown/keyLeft/keyRight` 一起清 | `in(mc,p).commandMove(0f, 0f)` |
| `mc.options.keyShift.setDown(b)` | `in(mc,p).commandSneak(b)` |
| `mc.options.keySprint.setDown(false)` | `p.setSprinting(false)`（直调，无指令通道） |
| `keyAttack` / `keyUse` | **先不动**，牵涉破坏/使用时序，分析写在 §形状A-待裁决 |

取输入对象的写法：
```java
private static AvatarInput in(Minecraft mc, LocalPlayer p) {
    if (!(p.input instanceof AvatarInput)) p.input = new AvatarInput(mc.options);
    return (AvatarInput) p.input;
}
```

**为什么键位这条是无效的**（每处改动的注释都要写清）：`AvatarInput.tick()` 先跑 vanilla 的
键位 pass，**然后用 walker 的指令覆盖 `forwardImpulse`/`leftImpulse`**。任何移动进程活跃时，
这些类按下的横向键每 tick 被静默丢弃；`keyJump`（walker 通常不下指令）活了下来。

---

## 0b. 冻结中 —— 放行后的队列（coordinator 08-22 拍板，按此顺序做）

> **`common/src/main` 冻结中**（coordinator 正在编译 + 跑真客户端验证）。
> 我的树对 `common/src/main` 是干净的：`34fe1ee8` / `03c46e3c` / `52c7657f` 都已落，
> 冻结时刻工作树里那三个 `Walker*.java` 的改动**是 coordinator 的，不是我的**。

| # | 做什么 | 依据 |
|---|---|---|
| Q1 | `BunkerChain:150` + `DrownEscapeChain:199` 各补一句 `mc.gameMode.continueDestroyBlock(pos, BotInteract.pickFaceTowardsPlayer(pos, p))`（照 `AntiSuffocate:158`） | 拍板 1；F2b(b) |
| Q2 | `ContactDamageEscape` / `LavaProximityEscape` 的 `forward` 升级成 `commandMove`，javadoc 写明「commandForward 会被同 tick 的 walker 静默丢弃」 | 拍板 5；F1 |
| Q3 | `WalkerTickPrelude:255,302` 的脚基判据改成眼→格心，并把判据抽成 `BotUtil` 里挨着 `standingEye` 的一个方法，五处全指过去 | 拍板 3；B-1 |
| Q4（待判） | `BotInteract.releaseKeys()` 收敛（见 §1b 注 3） | 我提的，未拍板 |
| Q5（待判） | 死 import 清理，见 F5 | 我提的，未拍板 |

**Q1 的一条注意**：`pickFaceTowardsPlayer` **已经是**唯一取法（`AntiSuffocate:155` 就在用），
不存在「第六种算法」需要收敛 —— 那一条拍板意见的前提不成立，两处直接照抄即可。

### Q1 的确切改法（冻结期已验证，放行后照抄）

两个文件都已有 `mc`、`p`(`LocalPlayer`)，且都已 `import static ...BotInteract.<…>`，
所以各加**一行 static import** + **一行调用**：

```java
import static net.magicterra.worlddriver.bot.util.BotInteract.pickFaceTowardsPlayer;
...
// BunkerChain:150 附近（挖下）／DrownEscapeChain:199 附近（破盖子）
mc.options.keyAttack.setDown(true);
if (mc.gameMode.continueDestroyBlock(pos, pickFaceTowardsPlayer(pos, p)))
    p.swing(InteractionHand.MAIN_HAND);
```

- `pos` 在 `BunkerChain` 是 `below`，在 `DrownEscapeChain` 是 `lid`。
- **`swing` 那半句别省**：`AntiSuffocate:156-159` 的注释写着理由 ——
  「armless digging is an anticheat signature on third-party servers」。
- `DrownEscapeChain` 还需要 `import net.minecraft.world.InteractionHand;`（现在没有）；
  `BunkerChain` 也没有。

### Q3 的确切改法（冻结期已设计，放行后照抄）

在 `BotUtil` 里挨着 `standingEye` 加**一个**方法，五处指过去：

```java
/** True iff the centre of {@code block} is within {@code reach} of this body's EYE.
 *  The eye, never the feet: a cell 5 BELOW is 5.0 from the feet but 6.6 from the eye,
 *  and a cell 5 ABOVE is 5.0 from the feet but only 3.4 from the eye — so a feet-based
 *  gate is loose downward and tight upward, and tight-upward is exactly the case
 *  MineProcess.findReachStand scans dy to −5 to support. */
public static boolean eyeWithin(Player p, BlockPos block, double reach) {
    return p.getEyePosition().distanceToSqr(Vec3.atCenterOf(block)) <= reach * reach;
}
```

**半径取谁**：`WalkerTickPrelude:255,302` 是一个**释放**闸 ——
太严 = 提早放弃还够得着的方块，太松 = 抱着够不着的方块不放（就是 C31-J1 那个 85 秒 badlands 卡死）。
所以它应当**逐字对齐权威**：`p.blockInteractionRange() + 0.5`
（`ServerPlayerAvatar.canBreakFromHere:491` 用的就是这个式子，且它是唯一去问游戏的）。
**不要硬编码 4.47 或 5.0。**

`MineProcess`(4.4) 和 `BboxFillProcess`(4.0) 的半径**保持不变** ——
它们是「往哪走」的选点谓词，买余量是安全方向（已在 `standingEye` 的 javadoc 表里写明）。
`ServerPlayerAvatar` 保持不变（它是权威本身）。
**所以 Q3 真正改行为的只有 prelude 那两行**，其余四处是让它们指向同一个定义。

## 1. 实际改了什么

### 提交索引（本轮全部，按时间）

| hash | 一句话 | 节 | 验证状态 |
|---|---|---|---|
| `34fe1ee8` | 逃生反射改走 walker 会覆盖的冲量通道，不走共享键位 | A-1/A-2/A-3 | 闸绿 |
| `1e717c47` | 伤害反射真正压过它一直号称能压过的 walker 命令 | A-3 | 闸绿 |
| `a1de83a4` | 破坏管线开到只按键的两条反射上 | J1 | **弄红过闸**，已由 coordinator 修 |
| `52c7657f` | 给「站位格的假想眼」一个定义，并写下五个不一致的触及半径 | B-1 | 闸绿 |
| `49ee48f0` | 每条触及判据改成眼→格心 vs 游戏给的 range | J2 | 闸绿 |
| `13925ee5` | 竞技场基线必须申报它悄悄改写的每个默认 ON 开关 | J6 | 6/6 |
| `9aa0c7a9` | 只按 attack 键不驱动破坏管线的位点直接编译失败 | J1-verify | 3/3 |
| `c303548a` | 那条守卫的失败信息不再推荐弄红闸的写法 | J1-verify | 3/3 |
| `ce945f78` | 钉住 scheduler 链能调的客户端类 | J7 | 4/4 |
| `03c46e3c` | 删掉九个进程从没调过的 `BotInput` import | A-4 | 闸绿 |
| `2f0d2f10` `234be7ce` `908eedb7` `fe3c50e8` `83bbe22b` | 死 import 724 → 0，纯删除 | J5 | 末笔编译过 |
| `4005f860` | 跨枚举器断言从 boolean 扩到全部字段 | J8 | 6/6 |
| `e95f4e72` | 说清 `pathLen`/`pathStep` 到底装什么（十写入者五单位） | J9 | 仅注释 |
| `7a723ddb` | `dayTime` 折叠收敛到一处 + `DayTimeFoldingTest` | J10/F12 | **过编译器** |
| `0c046d3d` | 三份 `yawFor` 合一，并写明为什么不是 `toYRot()` | J10 | **过编译器** |
| `f03fbe92` | 改正黄昏相位那段注释：四条边界只有一条承重 | F13 | 仅注释 |
| `d0e4dde3` | recipe 的 station 只留 resolver 那一份 | J10 | **过编译器** |
| `4a259ffa` | `clearColumn` 提到 `Move`，三个对角线继承 | J10 | **过编译器** |
| `09b1cfad` | 下坠柱净空只问 `Move` 一次 | J10 | **过编译器**（唯一碰 A* 热路径的一笔） |

**「过编译器」的证据**（coordinator 08-22）：一次 `:common:compileTestmodJava`，
`:common:compileJava` 与 `:common:compileTestmodJava` **两个 task 都是 executed（不是 UP-TO-DATE）**，
`BUILD SUCCESSFUL`。六笔全在 main 源集里，所以 `compileJava` executed 就是它们的答案。
同一次还带了 coordinator 三笔 journey 修复（`fcbbd66b` / `c83e7d76` / `371cb137`），
**是两边改动的合并验证，不是只验了一边。**
我把这批的风险自定级在编译期（见 §J10 与下面的等价性论证），**该风险已清零**；
剩下的运行期风险只有 `7a723ddb` 在 `dayTime < 0` 时的行为变化，其余逐位相同。

### 提出但没做的（交 coordinator 排期，按值排序）

1. **F14** `ProcessSlot.beginRun()` —— 18 个 `attach()` 的开场白收一处，
   顺带修「16 个进程把上一趟的 `goalReached` 挂在活着的运行上」。19 个文件，要闸。
2. **F9** 四个最长的 leap 改用严格版 `hasRunway` —— 会改 A* 可行边集合，
   coordinator 要求单独一趟闸、单独归因。
3. **F13** `mc.client.scene.dayPhase` 与 `mc.observe.player.time.phase` 的词表/边界统一 ——
   API 词表变更 + 反射边界变更。
4. **F8** 拆 `BotConfig`（2993/3000），第一步是让 `persistableFields()` 走父类链（今天是 no-op）。
5. **J9 后续** 给进度键加单位（`progress.nodesRemaining` 等），旧 `pathLen` 保留一轮。
6. **线段采样器** `losWalkable` / `straightLineBias` / `isOpenWaterLine` 三处共用同一段
   「`steps = max(|dx|,|dz|)` + 四舍五入插值」。**只抽 `lineCell(a,b,s,steps)` 这一层算术，
   不要抽成返回 `List<BlockPos>` 的迭代器**（coordinator 已采纳这个写法）。
   **依据不是理论上的「热路径」，是量过的**：冻屏那次实测 **2022 次切片寻路，
   其中 1986 次（98%）恰好占 1 个 tick，上限 10 tick** ——
   绝大多数调用都压在单 tick 预算里，**每次多分配一个 list 贵在次数而不是单次开销**。
7. **F10** 那 14 个从没翻开过的 default-OFF：给场景或删掉，二选一。

### A-0 `bot/movement/BotInput.java` —— 扩成够用的门面（不新增第三个输入类）

- **改了什么**：`stop(mc)` → 改名 `halt(mc)` 并把实现从 `commandForward(0f)` 换成
  `commandMove(0f, 0f)`；新增 `sprint(mc, boolean)`；类 javadoc 补上「键位为什么是**惰性的**」
  和「两条指令通道谁压过谁」。
- **为什么**：`stop()` 的 javadoc 承诺「Stop horizontal movement this tick」，
  而 `commandForward(0f)` 在 walker 同 tick 下了 `commandMove` 时**做不到**（见 F1）。
  **按注释改代码**是安全的，因为 `stop()` **零调用点** —— 改的是契约，不改任何现有行为。
  改名是为了让「halt = 我压过所有人」和「forward = 我只是在推」在调用点上一眼可分。
- **风险**：低。零调用点，纯新增 + 一次无调用者的重命名。

### A-1 `bot/scheduler/DrownEscapeChain.java` —— 19 处 → 键位只剩 `keyAttack`

- **改了什么**：横向/跳/潜行/疾跑全部改走 `BotInput`。
  - 侧向逃生分支：`keyJump→jump(true)`、`keyUp→forward(true)`、
    `keyDown/keyLeft/keyRight` 三清**删除**（`commandForward` 自己就把 `leftImpulse` 归 0，
    见 `AvatarInput.tick` 的 raw 分支），`keySprint→sprint(false)`、`keyShift→sneak(false)`。
  - 纯垂直分支：用 **`halt(mc)`** 而不是 `forward(false)` —— 这条链是**抢占**语义，
    必须用压得过 walker 的那条通道。
  - `releaseHeldKeys()`：`keyJump` 改走 `BotInput`，并注明指令通道**自己会释放**（每 tick 消费），
    所以这一句只是抢占与下一次调度之间那一 tick 的保险；`keyAttack` 是真闩锁，那句是有用的。
- **为什么这一处最讽刺**：这个类的 javadoc 逐字写着它存在的理由是
  「`AutoSwim.tick` 的 in-process backstop 与进程共用输入通道、被进程的逐 tick 驱动压掉」——
  **然后它自己去驱动键位，于是被同一个机制压掉。** 那条注释是对的，它只是没被应用到自己身上。
  注释保留原样（它是这个仓库最值钱的东西），只换驱动通道。
- **风险**：中。纯垂直分支从「清 4 个没人读的键」变成「真的把 `forwardImpulse` 归 0」——
  这是**首次真正生效**，不是等价改写。方向是变严（抢占链本来就该压过进程），
  但它会第一次真的停住一具正在被 mine/goto 驱动的身体。**要过闸。**

### A-2 `bot/movement/ClutchController.java` —— 12 处 → 0，两块逐字相同的清键合成一个 helper

- **改了什么**：`tick()` 里两处 **6 行逐字相同**的
  `keyUp/keyDown/keyLeft/keyRight/keyJump/keySprint` 清键（airborne 分支 + scoop 分支）
  合并为 `private static void yieldMovement(Minecraft)`，实现走 `halt + jump(false) + sprint(false)`。
  `keySprint.setDown(false)` **删掉而不是翻译** —— 两处原本就紧跟着 `p.setSprinting(false)`，
  而 `aiStep` 读的是那个 flag，键是冗余的（`BotInput` 的 javadoc 早就这么写了）。
- **为什么用 `halt` 不用 `forward(false)`**：这个类的 javadoc 写着
  「the clutch OWNS this tick (caller must suspend everything else and yield the keys)」——
  是**所有权**声明，必须用压得过 walker 的通道。
- **这里键位失效不是洁癖**：MLG 下坠时有一条**阻尼弹簧**在把身体拽回落点柱
  （`setDeltaMovement(v.x*0.5+nudgeX, …)`），而同 tick 还活着的 walker 指令在往反方向推。
  「滑出 1 格宽的落点柱」正是那条弹簧存在的理由。
- **风险**：中。同上，是首次生效。合并本身零风险（`git show HEAD:` 两块逐字相同）。

### A-3 `bot/auto/ContactDamageEscape.java`（4 处）+ `bot/auto/LavaProximityEscape.java`（2 处）

- **改了什么**：`keyUp→BotInput.forward`、`keyJump→BotInput.jump`，加 import。
- **顺带修掉一处不对称的真缺陷**：两个类是文档里互称的「兄弟」，驱动同样两个键，
  但 **`LavaProximityEscape.reset()` 从来不释放**（只打一行日志），而
  `ContactDamageEscape.reset()` 释放。旧通道下 `keyUp.setDown(true)` 是**闩锁**，
  所以每一次熔岩逃生结束都留下一个按住的前进键，直到别的东西恰好清掉它。
  换成每 tick 自释放的指令通道之后这条分歧自动消失，两边都补了注释说明**为什么现在不需要释放**
  （否则下一个人会把它当成漏写再加回来）。
- **风险**：低–中。同样是首次生效；这两条是保命反射，方向只可能是变好。

### A-4 9 个死 import（`process/`）

`BackfillProcess` `BboxFillProcess` `BridgeProcess` `BuildProcess` `FarmProcess`
`FollowProcess` `MineProcess` `SleepProcess` `TowerProcess` 的第 3–4 行都是
`import ...BotInput;` + 一个空行，**零调用**，九份逐字相同（明显是一次机械插入的残留）。删掉。
风险：零。

### A-5 形状 A 移动半边**清零**

全仓 `mc.options.key*.setDown` 只剩 `keyAttack`×15 + `keyUse`×10 + `AvatarInput` javadoc 里
的 3 处引用。**没有任何一处还在驱动移动键。**

### J1 `BunkerChain` / `DrownEscapeChain` 补 `continueDestroy`（放行后第一笔）

- **改了什么**：两处 `keyAttack.setDown(true)` 之后各加
  `if (mc.gameMode.continueDestroyBlock(pos, pickFaceTowardsPlayer(pos, p))) p.swing(MAIN_HAND);`
  （逐字照 `AntiSuffocate:155-159`），各加 `InteractionHand` 和 `pickFaceTowardsPlayer` 两个 import。
- **键位那一句保留**：抓着鼠标的客户端上 vanilla 驱动同一次破坏，两边一致；
  松手的客户端上由直调补上。注释里写了 2026-08-04 那条 140 tick / `destroyProgress=0.0` 的实测。
- **`BunkerChain` 那处的后果比想象严重**：破不动 → 竖井不下沉 →
  `digTicks` 一路涨到 `breakTimeoutTicks` → `a.reset()` 重新锚定，
  **每一行日志都在说「我在挖」，而一块方块都没下去。**
- **风险**：中。这是让一条从未生效的路径**首次生效**。

**⚠️ 验证拓扑的陷阱（`allowBreak` 那条，见 F6）**：
`DrownEscapeChain` 的破盖闸在 `BotConfig.allowBreak` 上，而 `applyGameTestBaseline()`
**把 `allowBreak` 设成 false**。所以在 stagewright autorun 的任何拓扑上这条分支**根本不进**。
加上真梯 `doMobSpawning=false` + 钉死的世界让它从来没机会触发 —— **J1 的验证必须自己写场景，
显式打开 `allowBreak` 并把身体按进有盖子的水里**，不能指望现有套件或真梯。

### J2 到达/触及半径统一成眼→格心

- **改了什么**：`BotUtil` 新增 `eyeWithin(Vec3|Player, BlockPos, double)` 和
  `blockReachToCentre(Player)`（= `blockInteractionRange() + 0.5`，从
  `ServerPlayerAvatar.canBreakFromHere` 抬上来的那个式子）。五处指过去：
  - `WalkerTickPrelude` 两处挖掘闩释放：**从脚量的 `distToCenterSqr(p.position()) > 20`
    改成眼→格心 + `blockReachToCentre(p)`** ← **本轮唯一改变行为的地方**
  - `WalkerTickClimb` parkour-place：裸 `< 16` 抽成 `WalkerConstants.PARKOUR_PLACE_REACH = 4.0`，
    走 `eyeWithin`（**半径不动** —— 它在半空中开火，眼读数本来就过期，那 1 格余量是买的）
  - `BboxFillProcess.withinReach`：走 `eyeWithin`（半径不动）
  - `MineProcess.findReachStand`：已用 `standingEye`，且它需要原始 d2 来排序，保持
  - `ServerPlayerAvatar`：**不动** —— 它是权威本身，而且 `bot/sim/**` 归 parity
- **为什么释放闸要用权威而不是买余量**：选点谓词买余量是安全方向（选一个走过去还够得着的格），
  **释放闸买余量是反的** —— 太严＝提前放弃还够得着的方块，太松＝抱着够不着的方块不放。
- **风险**：中。prelude 那两处两个方向都变了：**向下变严**（脚 5.0 / 眼 6.6 的格现在会释放，
  正是 C31-J1 那个 85 秒 badlands 卡死想治的），**向上变松**（脚 5.0 / 眼 3.4 的格不再被误放，
  正是「站在下面往上挖」）。
- 顺带删掉 `WalkerTickClimb` 因这次改动而失效的 `Vec3` import。

### J3 两个逃生反射升级到 `commandMove`

- **改了什么**：`BotInput` 新增 `driveForward(mc)`（`commandMove(0f, 1f)`），
  `ContactDamageEscape` / `LavaProximityEscape` 的 `forward(mc, true)` 换成它。
  `forward()` 的 javadoc 补上「会输给同 tick 的 walker `commandMove`」。
- **为什么等价却不同**：`commandMove(0,1)` 和按住 W 产生的
  `forwardImpulse=1, leftImpulse=0` **逐字相同**，唯一区别是它赢 `AvatarInput.tick` 的优先级。
  这两个类的 javadoc 一直写着「overrides an active walker's keys」——
  **在旧的两条通道上那句话都是假的，而且恰恰在它要生效的那些 tick 上假。**
- `reset()` 里的 `forward(mc, false)` **故意保持弱通道**：那是交还控制权，
  用 `halt` 会把正在恢复的 walker 多刹一 tick。
- **风险**：中。同样是让一条从未真正生效的保命反射首次生效。

### J6 给竞技场基线加守卫 —— `GameTestBaselineManifestTest`

**新文件**：`common/src/test/java/net/magicterra/worlddriver/bot/GameTestBaselineManifestTest.java`
（399 行，6 条断言）。**不动 `applyGameTestBaseline()` 本身**（删它会红一批场景断言）。
`common/src/test` 早就接好了 JUnit 5，**不需要动任何 build 文件**。

#### 清单（都在测试里，逐条带理由）

- **`PINNED` 38 条** = 基线实际写的字段（36 布尔 + 2 数值），每条一句为什么被按住。
  分三组：**能力许可**（`allowBreak` / `allowPlace` / `allowWaterBucketFall` ——
  不是「后来翻的行为」，是**整个能力被摘掉**）、**§78 翻转波**、**§87 第二波**
  （后者的分界线来自基线自己那行注释）。
- **`LIVE` 45 条** = default-ON 但基线**故意不碰**的布尔，
  即竞技场和真客户端在这些上是一致的。每条写清它是什么，好让下一个人判断新 flag 该放哪边。
- **`RUNTIME_ONLY` 3 条** = 在设置面上但没有 compiled default 的布尔
  （`BotConfig.NON_PERSISTED` 那三个）。**这条堵的是一个真洞**：
  不写它的话，一个落进 `NON_PERSISTED` 的新 flag 会完全绕过这个测试。

#### 判据怎么保证「新增 flag 会红」

按你点名的那条纪律 —— **门槛不能从被它测量的量里算出来**：

- **全集**来自反射（`SettingsRegistry.reflectivePrimitiveFields()` 过滤
  `BotConfig.COMPILED_DEFAULTS`），**不是**从基线的赋值列表来的。
- **基线写了哪些字段**是**跑出来的**，不是读源码解析的：
  两遍哨兵（第一遍全部布尔=true／数值=−9991，第二遍 false／−9992），跑一次
  `applyGameTestBaseline()`，看谁变了，取并集。
  **两遍是必需的** —— 一遍只能证明「变得不等于这个哨兵」，
  一条恰好赋成该哨兵值的基线行会看起来没动过。

四种事件都会红：新增 default-ON flag ✓／**把已有 flag 从 OFF 翻成 ON** ✓（这是最关键的一种，
而它不改变字段集合，所以全集必须按「默认值」定义而不是「所有布尔」）／
基线加行或删行 ✓／flag 被删或翻回 OFF 使清单变陈旧 ✓。

#### 三条防「这个断言其实不会红」

1. `theProbeCanTellAPinnedFieldFromAnUnpinnedOne` —— 探针必须看见 `allowBreak`（布尔）、
   `pathfinderBreakCostMultiplier`（**数值**，证明它不只会抓布尔）、且**不能**看见 `walkerDebug`。
2. `theUniverseIsThereAtAll` —— 全集 ≥50（现为 81）。**空全集会让整个测试真空绿**。
3. `compiledDefaults()` 反射不到就**抛**，不降级成读活字段 ——
   否则全集会悄悄变成「上一个测试留下的值是什么」。
4. `theProbeLeavesTheConfigAsItFoundIt` —— 探针写遍整个设置面，而 common 的测试**共用一个 JVM**，
   泄漏会去红别人的测试且指向不到这个文件。（顺带：探针**自带** save/restore，
   没有用 `BotConfig.snapshotAll()` —— 那个只覆盖 persistable 字段，
   而探针也写 `NON_PERSISTED` 那三个，用它会漏还三个。）

#### 一处我自己做的判断，交你追认

你说「每行带一句为什么它在基线里（或者为什么故意不在）」。
**`LIVE` 那 45 条我写的是「这个 flag 是什么」，不是「为什么故意不在」** ——
因为后者对这 45 条只有一个共同答案（写场景断言的时候它们就已经是 ON 了），
而**逐条编 45 个不同的理由我支持不了**，那就成了撒谎的注释。
每条的描述我是从它自己的 javadoc 里提的，不是我编的。

**风险**：低。纯新增测试文件，不碰产品代码。**但它没跑过** —— 我核对了清单集合
（38/45 与源码逐字相符、无重叠、无重复）、括号配平、以及 `BotConfig` **没有任何
`net.minecraft` import**（静态字段只有 `Set<String> = Set.of()`），所以纯 JVM 可初始化。
`SettingsConsumerTest` 是同包同形状的先例。

### J1-verify 不是一条场景，是一条**不变量** —— `ClientBreakSitePairingTest`

**新文件**：`common/src/test/java/net/magicterra/worlddriver/bot/ClientBreakSitePairingTest.java`
（175 行，3 条断言）。**纯测试代码，不进产品路径。**

#### 为什么没写成场景（这是一个判断，交你追认）

你提醒我记着自己 refute 过的前提（`applyGameTestBaseline()` 把 `allowBreak` 关成 false，
场景要显式开）。**照着想下去，一条场景在这里有两层「进不去的绿」，而不是一层：**

1. **专用服闸上根本进不去。** `DrownEscapeChain.tick` 第一行是 `if (mc == null) return;` ——
   这两个待验的站点是**客户端专有**的反射。在 `stagewrightDedicatedServer*` 上那个分支
   不是「没测到」，是**不可能进入**。
2. **集成服闸上还压着 `allowBreak=false`**（你提醒的那层）。

而且驱动它还有第三个麻烦：**场景跑在服务端线程上**，`mc.gameMode.continueDestroyBlock`
必须在客户端线程调；现有的客户端读数走的是 `api.route("mc.client.player", …)`
（`WorldDriverActuatorSplitScenes` 的做法），而**驱动破坏没有对应的 route**。

**所以我写了一条读源码的不变量**，它**没有任何守卫可以进不去**：

> 每一个把共享的 `keyAttack` 按下去的站点，都必须同时驱动破坏管线。

这条规则不是我发明的 —— `Avatar#breakHold` 的 javadoc 逐字写着
「every client-side break site must pair this with `continueDestroy(BlockPos)` on the same block」，
连同那条 2026-08-04 的实测（140 tick 对准目标，`destroyProgress` 钉死 0.0，`grabbed=false`）。
**规则一直在，只是没有东西执行它，于是三个站点里有两个不守。**

#### 它确实会红（不是 `0==0`）

用 git 里的旧内容验过：

```
PRE-J1  BunkerChain.java      continueDestroy 出现次数: 0
PRE-J1  DrownEscapeChain.java continueDestroy 出现次数: 0
```

**这条断言在 `a1de83a4` 之前会红，点名那两个文件。** 现在绿。
而且它对**未来每一个新站点**继续红 —— 这比一条只覆盖一个实例的场景强得多，
和 J6 是同一个形状（让这一类问题在下次发生时自己说话）。

#### 三条防「其实不会红」

1. **注释必须先被剥掉。** 这是真陷阱：我 J1 那两处补的注释里**逐字写着
   `continueDestroyBlock`**，不剥注释的话这个测试会被我自己的散文喂成永久绿。
   `A_COMMENT_IS_NOT_A_CALL` 这条对照就是钉这个的。
2. **反向对照用的是同一条代码路径**：把 J1 之前 `DrownEscapeChain` 那段的形状原样嵌成
   text block，断言匹配器**认得出它是坏的**。匹配器和文件扫描共用一套 `HOLD`/`DRIVE`。
3. **持键站点集合是点名的，不是计数的**：`{AntiSuffocate, BunkerChain, DrownEscapeChain}`。
   多一个名字是真事件（有新代码抢了那个共享闩，见 §1b 的仲裁问题），计数会悄悄漂。

#### 已核对（我不能跑 JUnit）

用只读脚本按同样的操作顺序模拟了一遍：扫描 332 个 java 文件 → 持键站点恰好那 3 个 →
unpaired 为空 → **TEST GREEN**；反向对照三条全部符合预期
（`HOLD` 认得坏形状 / `DRIVE` 认不出坏形状 / `DRIVE` 认不出注释里的提法）。
逐行有状态的括号定位器显示嵌套干净、深度归零。

#### 局限（写在测试的 javadoc 里，不藏着）

配对是**按文件**判的，不是按「同一个方块」判的 —— 一个文件在 A 方法持键、B 方法驱动也会过。
真正逐块配对要写解析器，而这次的缺陷从来不精妙：**那两个文件里 `continueDestroy` 出现 0 次。**

### J5 死 import 清零 —— 724 条，五笔提交，纯删除

| 提交 | 范围 | 删除行 |
|---|---|---|
| `2f0d2f10` | `bot/movement/` 的 7 个 `WalkerTick*` + `ClutchController` | 157 |
| `234be7ce` | `bot/process/` 前 8 个 | 291 |
| `908eedb7` | `bot/process/` 余下 10 个 | 219 |
| `fe3c50e8` | `api/` `debug/` `auto/` `scheduler/` `BotApiImpl` `ClientWorldView` 等 9 个 | 20 |
| `83bbe22b` | `Walker.java` / `WalkerTickClimb` / `WalkerTickPrelude`（coordinator 收工后） | 53 |

**每一笔都是纯删除，0 处新增**（`git diff` 的 `+` 行只有文件头）。
全仓复扫 **724 → 0**。族 A 那 17 条全部保留。静态 import 一条没碰。
`python scripts/check_source_budget.py` exit 0。
最后一笔在 coordinator 的树里被 `:common:compileJava` + `compileTestmodJava` 编过——
**死 import 唯一的真验证是编译器**，那一批有；前四批还没有。

#### 清理前后的行数（coordinator 指定的产出判据）

733 行净减，散在 38 个文件上。**但对预算闸的回答是「没有」，而这个否定结果比数字重要**：

```
  2993  BotConfig.java            ← 距 3000 只剩 7 行，而 J5 一行都没帮上（它没有死 import）
  2856  Walker.java               ← J5 只还回 8 行
  1538  PathFinder.java
  1442  WalkerTickDrive.java      ← 还回 17
  1410  BotApiImpl.java           ← 还回 11
```

**J5 删掉的 733 行，几乎全部来自本来就离预算很远的文件。**
比例最好看的是 `BackfillTracker.java`（80 → 41 行，**少了 48.8%**）——
但它离 3000 有 2959 行的余量，省下的额度没有任何人在用。
**真正顶着上限的是 `BotConfig.java`，2993 / 3000，headroom = 7 行**，
而它一条死 import 都没有，J5 这个工具对它完全无效。

所以这条要写在显眼处：**下一个往 `BotConfig` 加一个设置的人会当场撞红闸**，
而这个仓库对撞红闸的历史反射是「刮注释」——拿可读性换额度。
`BotConfig` 需要的是**拆分**（它是一个扁平的设置注册表，天然可按域切开），不是刮。
这件事 J5 帮不上，得单独排。

### J8 把 J6 的跨枚举器断言从 boolean 扩到全部字段（只改测试，不动产品代码）

`GameTestBaselineManifestTest`：
`everySurfaceBooleanHasACompiledDefaultOrIsDeclaredRuntimeOnly`
→ `everySurfaceFieldHasACompiledDefaultOrIsDeclaredRuntimeOnly`，
断言体里去掉一个过滤：

```java
-            if (f.getType() == boolean.class && !defaults.containsKey(f.getName())) {
-                unrecorded.add(f.getName());
-            }
+            if (!defaults.containsKey(f.getName())) unrecorded.add(f.getName());
```

**这条断言原本就是 F8 那个洞的守卫，只是只守了 138 个 boolean，漏掉另外 90 个字段。**
扩开之后覆盖全部 `public static volatile` primitive。

**今天必绿，且是从源码证明的而不是量出来的**（理由写进了 javadoc）：
`persistable` 接受 boolean/int/long/double/float/String/Set；`BotConfig` 里
出现过的 primitive 只有那五种；`public static volatile` 蕴含 static 且非 final；
`serialize` 只对 null 值返回 null，而装箱后的 primitive 永不为 null。

**失败信息里写清了第三种可能**（照 coordinator 那句「守卫的失败信息承载的权威比 javadoc 高」）：
除了「是运行期状态」和「应该被持久化」，现在还有第三条——
**「它被搬出了 `BotConfig` 自己的声明」**，并逐字说明
`getFields()` 跟父类走、`getDeclaredFields()` 不跟，
以及后果是「留在 settings schema 里但掉出持久化和 `snapshotAll`/`restoreAll`」。
下一个拆这个文件的人会在红的那一刻读到它，而不是在某趟排练莫名其妙地带着 OFF 基线跑起来之后。

**没跑**（coordinator 的 run 10 / 11 级排练在跑，我不编译）。

### J9 `ProcessSlot.pathLen` / `pathStep` —— 一个字段，十个写入者，**五种单位**（`e95f4e72`）

原来的注释逐字是：

```java
public volatile int pathLen;       // remaining nodes
public volatile int pathStep;      // current node index
```

**量了全部写入点，这句话对一半的写入者是假的**：

| 写入者 | `pathLen` 实际是 | `pathStep` 实际是 |
|---|---|---|
| builder / mine / follow / explore / `mc_goto` | `walker.pathLen()` —— 剩余路径**节点数** | 节点下标 |
| `BridgeProcess` | `distance` —— 桥要跨的**方块数** | 从起点走过的曼哈顿**格数** |
| `TowerProcess` | `targetY - startFeetY` —— 要爬的**高度** | `placed` —— 已放置的**方块数** |
| `DescendProcess` / `EscapeProcess`（同一个 `st.escape` 槽） | **`MAX_STEPS`，一个编译期常量**，永远不是真长度 | 该进程自己的**步数循环计数** |
| `ElytraProcess` | `round(goalDist)` —— 到目标的**距离** | 航点下标 |

`st.escape` 那一对最要命：**分母是常量**，所以「快挖完的 descend」和「刚开始的 descend」
报出来的 `pathStep/pathLen` 分母一模一样。而 `DescendProcess` 和 `EscapeProcess`
**共用同一个槽**，所以连「现在跑的是哪个动词」都从这两个数里读不出来。

**按代码改注释**（brief 第 2 条）：把两行行尾注释换成一张写清十个写入者的表，
并在 `pathNode` 的 javadoc 里把「`pathLen`/`pathStep` 说的是走到计划的哪儿了」
限定成「在它们真的来自 walker 的那些槽上」。
**没有统一写入者**，因为统一会改变 `mc.bot.status` 报给每一个现有客户端的语义；
注释里写明了：真要统一，**单位得进 key 名，而不是进注释**。

`git diff` 里除注释外只有两行：删掉那两句行尾注释。**零行为变化，可从 diff 直接证明。**

#### 这条要标成**面向 API 消费者的缺陷**，不是代码卫生（coordinator 定级）

`mc.bot.status` 是 gpt-player **每一轮都读**的东西。所以这不是「注释错了」，是
**读数在对这个系统里唯一一个不会去读源码的消费者撒谎**。
descend/escape 上它是一个**分母永远不变的分数**：看起来在动，
而它表达的东西和「还剩多远」没有任何关系。

**日后真修的形状**（不是现在）：带单位的 key ——
`progress.nodesRemaining` / `progress.blocksToSpan` / `progress.heightToClimb` / `progress.blocksToGoal`，
旧的 `pathLen` 保留一轮并在文档里写明它是那五种东西之一。
**规则（coordinator 采纳为通则）：单位得进 key 名，不能进注释** ——
一个叫 `pathLen` 的字段里塞着高度，再好的注释也拦不住下一个人。

### J10 一个概念一处实现 —— 四笔合并，每一笔都先逐字对拉过

用只读的重复块扫描（`…/scratchpad/dupblocks.py`，≥6 行连续相同、跨文件）普查 `common/src/main`，
逐组人工点开。**合了四组，不合两组，每一组的判据都写在下面。**

| 提交 | 概念 | 原来几份 | 判据 |
|---|---|---|---|
| `7a723ddb` | `dayTime` → 相位 | **4**（`ObserveApi` / `ClientObserve` / `ClientEventDetector` / `WorldModel`） | **不一致**：两份防负数、两份不防。见 F12 |
| `0c046d3d` | `yawFor(Direction)` | 3（`BunkerProcess` / `DescendProcess` / `EscapeProcess`） | **逐字相同** → 无条件可合 |
| `d0e4dde3` | recipe → station | 2（`RecipeApi` / `RecipeResolver`） | **逐字相同**（只差修饰符），且 `RecipeApi` 已 import 对方并在注释里称对方是 single source of truth |
| `4a259ffa` | `clearColumn`（对角线的两个角柱） | 3（`Diagonal` / `DiagonalAscend` / `DiagonalDescend`） | **逐字相同**；三者都是 `Move` 的子类，**调用点一个字符没改** |
| `09b1cfad` | `clearFallColumn`（下坠柱全程净空） | 3（`Fall` / `FallIntoWater` / `WaterBucketFall`） | **逐字相同**（只差局部变量名 `f`/`h` 与 `foot`/`head`）；三者只在**落点**判据上不同，那部分留在各自文件里 |

**两条贯穿所有合并的纪律：**

1. **不换成 vanilla 的等价物。** `yawFor` 没有换成 `Direction.toYRot()`——
   它 EAST 返回 `270f` 而这三份返回 `-90f`，**角度等价、数值不等价**，
   而 `setYRot` 存裸值、渲染按差值插值，`-90 → 270` 会转一整圈。
   **原样搬，一个字符不改**，理由写进了 javadoc。
2. **把「谁没参加合并、为什么」也写进 javadoc。**
   `clearFallColumn` 的 javadoc 点名 `ParkourDescend` **故意不用它**
   （它的柱是单格、上界是 `-drop + 1`，因为 `canStandAt` 已经清过落点的脚和头）。
   否则下一个人看到「四个下坠 move 里三个用了共同 helper」，
   会以为第四个是漏网的，然后把它一起改掉 —— **那才是这次合并会造出来的新缺陷。**

### J7 字节码守卫 —— `SchedulerClientCallSurfaceTest`（`ce945f78`）

**新文件**：`common/src/test/java/net/magicterra/worlddriver/bot/SchedulerClientCallSurfaceTest.java`
（233 行，4 条断言）。纯测试代码，零依赖（自己解析 class 文件常量池，**不用 ASM**）。

#### 先量，再定 —— 量出来的结果推翻了朴素判据

按你说的「不要拍脑袋扩到整个 `common`」，我先读了绿的那份字节码
（`build/classes/java/main/.../bot/scheduler`，你 18:57 那趟编的）：

| 类 | 它已经在调的客户端类 |
|---|---|
| `BunkerChain` | Minecraft, LocalPlayer, KeyMapping, Options |
| `CombatChain` | Minecraft, LocalPlayer, KeyMapping, Options |
| `DodgeChain` | Minecraft, LocalPlayer |
| `DrownEscapeChain` | Minecraft, LocalPlayer, KeyMapping, Options, **ClientLevel** |
| `DuskSecureChain` / `PanicChain` / `RetreatChain` | Minecraft, LocalPlayer |

**14 个类里有 7 个已经在调客户端类，而 306 场闸在它们上面是绿的。**
所以「scheduler 不许调客户端类」**不是**那条规则 —— 它会一次红掉半个包，
而一条会误报的守卫会被人关掉。

#### 判据：实测出来的**白名单**，多一个就红

允许 5 个 owner：`Minecraft` / `LocalPlayer` / `KeyMapping` / `Options` / `ClientLevel`。
共同理由写一次（**照你追认的 J6 `LIVE` 那条判断，不编 5 个理由**）：
每一个在 08-22 事故前就已经被调用，并且骑过一趟绿的 306 场专用服闸。
逐行只写「链拿它干什么」，那是读者判断新来者该不该并列时需要的东西。

**技术判据**：扫常量池里 `Methodref` / `Fieldref` / `InterfaceMethodref` 的 **owner**——
那正是 `invoke*` 和 `get/putfield` 解析的对象，即「调用了它」。
只出现在**签名、局部变量、checkcast** 里的类型产生的是 `Utf8`/`Class` 条目，**不报**。
这就是你我都同意的第 1 条：**可以传递，不可以调用。**

#### 我明确写进 javadoc 的一件事：**这条规则是量出来的，不是推出来的**

为什么 JVM 容忍 `LocalPlayer` 却拒绝 `MultiPlayerGameMode`——验证顺序？传递链接？
Fabric 自己的 dist 剥离？**我不知道，而且我没有在测试里假装知道。**
它记录的是「今天已知能在专用服上加载的那个面」，并让下一个新增者自己举证
（javadoc 逐字：`prove it with a gate run, not with a compile`）。

#### 正面对照（你的第 5 条）——**我拿到了实证的，不是合成的**

我做不出 `a1de83a4` 的字节码（不能编译，`run 10` 在跑）。但有更好的：

> **`BotInteract.class` 里就有 `MultiPlayerGameMode.continueDestroyBlock`** ——
> **正是杀掉闸的那个 class + member**，在今天的真字节码里，而且它在守卫范围**之外**（合法）。

所以 `theScannerSeesTheClassThatBrokeTheGate` 断言扫描器能在真 class 文件里找到它。
配上 `theRuleWouldRejectThatClassInsideTheScope`（同一条判据，已知坏输入 → 必须拒），
两条合起来证明「扫描器看得见」+「规则会拒」。**没有这两条，一条从没红过的守卫和
一条进不去的绿长得一模一样。**

#### 四条断言（模拟结果全 PASS）

1. `noSchedulerClassCallsAnUnvettedClientClass` — 15 个 class 文件，越界者 **{}**
2. `theScannerSeesTheClassThatBrokeTheGate` — `BotInteract` 里找到 `MultiPlayerGameMode` ✅
3. `theRuleWouldRejectThatClassInsideTheScope` — 白名单里没有它 ✅
4. `theScopeIsThereAtAll` — 15 ≥ 10，且**并集逐字等于白名单**（少一个也红，防白名单变成只涨不减的积累）

**没跑过**（`run 10` 在跑，不能编译）。用只读脚本按同样的字节序解析模拟了一遍，四条全 PASS；
括号用真词法扫描器验过 BALANCED。

#### 一个我自己踩到、值得记下的坑

我原来的括号检查器把断言信息里的字符串 **`"bot/scheduler/**"`** 里的 `/*` 当成块注释开头，
**吞掉 60 行**，然后报「少一个 `}`」。**工具的假阳性和被测对象的真缺陷长得一模一样。**
换成真词法扫描器（`javalex.py`，字符串/字符/行注释/块注释/text block 同一遍处理）后，
三份测试文件全部 BALANCED。这条和 F7 同族：**一个读数看起来在报告被测对象，其实在报告测量装置。**

### B-1 「够不够得着」在五个地方各算一次，其中一个算的是**另一个量**

优先级 #1 的形状。同一个谓词（这具身体能不能操作那一格），五份实现：

| 站点 | 从哪儿量 | 半径 | 备注 |
|---|---|---|---|
| `bot/sim/ServerPlayerAvatar.java:491` `canBreakFromHere` | **真眼** | `blockInteractionRange()+0.5` = **5.0** | **权威**，唯一去问游戏的 |
| `bot/process/MineProcess.java:1061` `findReachStand` | 假想眼 `cand.y+1.62` | `MAX_REACH` = **4.4** + collider 射线 | |
| `bot/process/BboxFillProcess.java:322` `withinReach` | 假想眼 `stand.y+1.62` | **4.0**，无射线 | |
| `bot/movement/WalkerTickClimb.java:998` parkour-place | 真眼 | **4.0**（`< 16`） | |
| `bot/movement/WalkerTickPrelude.java:255,302` 挖掘闩释放 | **脚**（`p.position()`） | √20 ≈ **4.47** | ← 量的是另一个东西 |

前四条只是各买了多少余量的区别，**买余量的方向是安全的**。第五条不是：

- 从脚量，一格**在下方 5 格**是 5.0，而**眼离它 6.6** —— 向下**太松**；
- 一格**在上方 5 格**是 5.0，而**眼离它只有 3.4** —— 向上**太紧**。

而 `MineProcess.findReachStand` 的 `dy` 特意扫到 **−5**，就是为了让身体站在下面往上挖，
它自己的注释写着「eye→center of a block 4–5 up is within the 4.5 reach」——**因为它从眼量**。
所以挖掘闩的释放门会在那个合法用例上开火。
`WalkerTickPrelude:265-269` 的注释也把这个数称作「reach (~4.5)」，**而 ~4.5 是眼的量程**。

**改了**：把两份逐字相同的假想眼（`+0.5, +1.62, +0.5`）提到
`bot/util/BotUtil.java` 的 `standingEye(BlockPos)`，五个半径**原样保留**，
分歧写成表格钉在那个方法的 javadoc 上（`BotUtil` 已有的风格就是记录分歧而不是抹平）。
`BboxFillProcess` 的裸 `4.0` 提成 `FILL_STAND_REACH` 并写清它为什么比权威严。
**行为逐字不变。**

**没改**：两个 `WalkerTickPrelude` 的脚基判据 —— 归 coordinator，且他正在改 stickyDig。
**这条建议现在就看**：判据应当是眼到格心，不是脚到格心。

## 2. 发现但没改

### F0（**闸红了，且我改不了**）`check_source_budget.py` 在 HEAD 上 exit 1

```
files over 3000 lines:
    3011  common/src/testmod/.../journey/WorldDriverJourneyScenes.java
methods over budget:
    1015  bot/movement/WalkerTickClimb.java:119  run()  — grandfathered at 1008 — it may shrink, not grow
     279  bot/movement/WalkerTickPrelude.java:66  run() — grandfathered at 245 — it may shrink, not grow
```

**不是工作树造成的**：`git show HEAD:...WorldDriverJourneyScenes.java | wc -l` 也是 3011，
`git status` 里没有别人的未提交改动。两个 `run()` 是**越过了 grandfather 线**（1008→1015、245→279），
不是新违规。三个对象**全在 janitor 不能碰的产权里**（journey/ 归 topology；两个 WalkerTick* 归 coordinator）。

顺带：**`BotConfig.java` 是 2993 行，离硬上限 7 行。** 下一个往里加 flag 的人会撞线。
按记忆里那条教训，**不许靠刮注释降行数**（那些 javadoc 是这个仓库最值钱的东西）——
该做的是按主题拆（`BotConfigWalker` / `BotConfigPathfinder` / `BotConfigAuto`）。

### F1（**要你拍板**）`commandForward` 是比 `commandMove` **严格弱**的通道 —— 和键位那个 bug 同一个形状

`AvatarInput.tick()` 里两条指令通道的优先级是**写死的**，与调用顺序无关：

```java
if (moveCommanded) {          // commandMove(left, forward)   ← 强
    this.forwardImpulse = f; this.leftImpulse = l; moveCommanded = false;
} else if (rawMoveCommanded) { // commandForward(forward)      ← 弱，被上面吃掉
    this.forwardImpulse = f; this.leftImpulse = 0f;
}
rawMoveCommanded = false;
```

Walker 每 tick 下的是 `commandMove`。**所以任何用 `commandForward` 的后备/兜底，在 walker
同 tick 也下了指令时会被静默丢弃 —— 正是键位被 `forwardImpulse` 覆盖的那个失效，换了一层皮。**
键位版本的修法把通道从「vanilla pass」搬到了「raw 指令」，但 raw 指令仍然排在 walker 后面。

- `BotInput.stop(mc)` 的 javadoc 写着「Stop horizontal movement this tick」——
  **在 walker 活跃的 tick 上它做不到这件事。** 这条注释按当前代码是撒谎的。
- 真正能压过 walker 的只有 `commandMove(0f, 0f)`（`AutoSwim` 的 deep-ascent 分支用对了）。

**没改的原因**：把 `commandForward` 换成 `commandMove` 是**变严**方向没错，但它改变的是
「后备 vs 进程」的仲裁语义，会影响所有拓扑上每一次 goto/mine 与逃生链的交互。这属于要你判的。
**我按你给的映射表原样转换（keyUp→`commandForward`），不擅自升级通道**，只在每处留注释指出这一点。

### F2 `BotInput` 这个门面早就存在，且**从来没有人用**

`bot/movement/BotInput.java` 的 javadoc 逐字写着这次任务的结论（「those are the same objects a
human's keyboard maps to, and writing them fights manual play」），并已封装
`forward/stop/jump/sneak`。但全仓只有 2 个真调用点（`DodgeChain:43`、`PanicChain:79`），
而 **9 个 process 文件 import 了它却一次都没调用**（`BackfillProcess` `BboxFillProcess`
`BridgeProcess` `BuildProcess` `FarmProcess` `FollowProcess` `MineProcess` `SleepProcess`
`TowerProcess`）—— 迁移开了个头就停了，留下 9 个死 import。

结论：**形状 A 的正确修法是把这些文件接到 `BotInput` 上，而不是把 `in(mc,p)` 抄十遍。**

### F2b（**这是 `keyAttack`/`keyUse` 的分析，也是我今晚第二值钱的一条**）

先说结论：**`keyAttack`/`keyUse` 和移动键不是同一个病。**
它们**没有**被 `AvatarInput` 覆盖（那个类只写 `forwardImpulse`/`leftImpulse`/`jumping`/`shiftKeyDown`），
vanilla 的 `continueAttack` 直接读键位。**所以按下去是有效的**，不能照移动键那样论证。

**但有两条别的病，第二条是活缺陷。**

#### (a) `keyUse` 有仲裁协议，`keyAttack` 没有

`BotApiImpl.builderSuppressesAmbients` + `UseKeyOwnershipTest` 钉住了 `keyUse` 的取用者集合，
javadoc 逐字写着「every acquirer must join it or self-clear on every exit path」。
`keyAttack` **没有任何这种东西**，而它有 **5 个互不知情的取用者**：
`ClientPlayerAvatar.breakHold`（Avatar 缝，Walker/process 走这条）、`AntiSuffocate`、
`BunkerChain`、`DrownEscapeChain`、以及经 Avatar 的 `MineProcess`。
而 vanilla 的 `MultiPlayerGameMode` **只能跟踪一个破坏目标** —— 两个取用者同 tick 指向不同格，
`startDestroyBlock` 就把累积的 `destroyProgress` 丢掉。
**这和 coordinator 今晚在 `StickyDig.engage` 上抓到的是同一个病，只是规模更大、且连一个共享槽位都没有。**

#### (b) `BunkerChain` 和 `DrownEscapeChain` 的破坏在真客户端上**破不掉任何东西**

`Avatar.breakHold` 的 javadoc 有一条 2026-08-04 的实测：

> On a client avatar this alone breaks nothing. […] gated on `mouseHandler.isMouseGrabbed()` —
> true only after a human clicks into the window. A driven client never grabs the mouse […]
> 140 ticks aimed dead-on at the block, crosshair on target, no screen open,
> `destroyProgress` pinned at exactly 0.0, `grabbed=false`.
>
> So every client-side break site must pair this with `continueDestroy(BlockPos)` on the same block.

**清点谁配对了：**

| 站点 | 按 `keyAttack` | 配 `continueDestroy` |
|---|---|---|
| `Walker.avatarDig` / `WalkerTickPrelude:335` | ✅ | ✅ |
| `MineProcess` / `FarmProcess` / `BunkerProcess` / `DescendProcess` / `EscapeProcess` / `CraftProcess` | ✅ | ✅ |
| `AntiSuffocate:161` | ✅ | ✅（`:158` 有直驱 `gameMode.continueDestroyBlock` 后备） |
| **`BunkerChain:150`**（封顶） | ✅ | ❌ **没有** |
| **`DrownEscapeChain:199`**（破盖子求生） | ✅ | ❌ **没有** |

所以在真客户端上：**淹死时破头顶盖子破不动，夜里封 bunker 顶也封不动。**
`MouseYield` 还专门**拒绝**抓鼠标（`MouseYieldGate` 整个类就是为这个），所以那个前提是长期成立的。

**为什么一直没人发现**：`Avatar.breakHold` 自己的 javadoc 就写了答案 ——
「every dig scene in the suite is a `wd.server*` scene」，而服务端 avatar 的 `breakHold(true)`
**直接把方块拆了**，`continueDestroy` 是继承来的 no-op。**闸绿，客户端空。**
这和 F4 是同一件事的两面：反射层只在客户端跑，而闸只在服务端测。

**按你的指示没动手。** 修法看起来是一行（两处各加 `a.continueDestroy(lid)`），
但这两个类拿的是 `Minecraft` 不是 `Avatar`，所以要么取 avatar，要么直调
`mc.gameMode.continueDestroyBlock(pos, face)`（`AntiSuffocate:158` 已有先例，含 face 的算法）。
**face 怎么取是要你拍板的那一点** —— `AntiSuffocate` 用的是它自己那套。

### F6（**推翻我自己的 F2c，也是形状 B 的真正母体**）`applyGameTestBaseline()` 按住 38 个开关，**其中包括 `allowBreak` 和 `allowPlace`**

coordinator 报了 `walkerDigAimPriority` 在真梯上恒为 false。去读那个方法之后，规模比那一条大得多：

`BotConfig.applyGameTestBaseline()`（`BotConfig.java:2948`）**无条件写死 38 个赋值**，
由 `WorldDriverFabric:53` / `WorldDriverNeoForge:81` 在 `-Dstagewright.autorun` 下**服务器启动时调用一次**。
头两行是：

```java
allowBreak = false;
allowPlace = false;
```

**这不是「关掉几个实验开关」，这是把身体的两条核心能力关掉。**

**对我这一轮的直接影响**：`DrownEscapeChain` 的破盖子闸在 `BotConfig.allowBreak` 上（`:195`）。
所以 **J1 在 stagewright autorun 的任何拓扑上都不会被执行**——不是「没被触发」，是「分支不进」。
已写进 J1 条目的验证警告。

**对整个仓库的影响，以及为什么这比 F2c 严重**：
那个 javadoc 说得很诚实 ——「the arena suite's assertions were authored against the historical
default-OFF flag set」，即**这是一份 §87 时刻的快照**。但它是**手写的 38 行常量赋值，
没有任何机制让它跟上后来新增的开关**。所以：

- §87 之后每翻一个 default-ON 的开关，**竞技场就多跑一个场景作者没设想过的行为**；
- 反过来，**任何在竞技场套件里验证过的东西，都不能推断它在真客户端/真梯上跑过**，
  因为那 38 个在竞技场里是关的、在客户端是开的。

**「skip 不是覆盖」这条教训的第三种形态**：不是场景被跳过，也不是守卫没进入，
而是**被测对象在测量它的那个进程里被改写成了另一个东西**。

**F2c 那 14 个 default-OFF flag 的判断需要修正**：我说「是欠账不是腐化」——
欠账那半对，但我漏了这一层：**还有 38 个 default-ON 的开关被按住**，
所以「default 是什么」和「跑起来是什么」在这个仓库里是两个问题，
而我之前是按前者做的判断。**读任何 flag 的默认值之前，先问它在不在这 38 行里。**

**没改。** 修法不是删这个方法（会红掉一批竞技场断言），而是让它**从场景那边显式声明**，
或至少加一条「新增 default-ON flag 必须决定进不进这份基线」的守卫。**要你排。**

### F2c 形状 B 的普查结论：**这个仓库的成对开关基本是被管住的，但有一条 14 项的欠账**

我按你说的搜了 `BotConfig` 里所有成对/互斥开关。**没有找到第二例
「被关掉的那条注释明确说另一条错」** —— `walkerStickyDig`(OFF) / `walkerDigAimPriority`(ON)
那一对是**唯一**一例，而且你已经在修。（附一条对你有用的观察：
`WalkerTickClimb:744/872/1076` 是 `if (walkerStickyDig || walkerDigAimPriority) wk.stickyDig.engage(...)`
—— **两个 flag 共用同一个 latch 对象**，所以「哪条路径在跑」和「谁占着槽位」是两个问题。）

**取而代之找到的是另一个形状：一条从没被清过的验收队列。**

`BotConfig` 里有 31 个 flag 的 javadoc 写着「byte-identical no-op until validated」/
「Validate via the deterministic replay-0006 A/B」/「flip ON only on a clean live A/B win
(the parent does live acceptance)」。其中 **17 个已经翻成 ON 了**，**14 个还是 OFF**：

```
riskBias                              pathfinderFloatingSurfaceCross
pathfinderVineOverWaterTax            pathfinderPadOverWaterTax
pathfinderPadClusterTax               walkerDescentFlipHold
walkerStepUpCrestReach                walkerArcProgressWedge
walkerFellBelowAlign                  walkerDryWedgeFootY
walkerWallCornerNodeAim               walkerOvershootReaim
walkerDiagDownCenter                  walkerTraverseBreakOvershootResync
walkerFloatingBankFollow              walkerFasterChurnRepath
```

**这不是腐化，这是纪律**（每一条都写清了怎么验、由谁验），但它是一条**没人清的欠账**：
14 个写好了、文档齐全、从没跑过的修法。

**其中一条值得单拎出来**：`pathfinderVineOverWaterTax` 治的正是 **−711 vine bug**，
而 `wd.vineOverWaterClimb` 是整套 222 场景里**唯一一条已知失败**的场景。
**红的传感器和它写好了却关着的修法，就摆在一起。**
（按你的指示我没翻任何默认值 —— 翻它会影响所有拓扑。）

---

### §1b `keyAttack` 五取用者诊断表（coordinator 指派第 2 项，**只诊断，不发明仲裁器**）

> 归在「发现但没改」下 —— 它是诊断，不是改动。编号沿用交回时用的 §1b。

#### 谁在什么时候写这个闩

一个 client tick 里 `keyAttack` 的写入顺序（`BotApiImpl.clientTick`，**越靠后越晚写，晚写覆盖早写**）：

| # | 取用者 | 何时按下 | 何时松开 | 配 `continueDestroy`？ |
|---|---|---|---|---|
| 1 | `ClutchController` | 从不按 | — | — |
| 2 | **`BunkerChain`**（Chain, prio 300） | `depth < bunkerDepth` 且脚下实心 → 挖下 | 位移/水/`breakTimeoutTicks`/挖够深封顶/`onInterrupt`/`cancelEpisode` | ❌ **没有** |
| 2 | **`DrownEscapeChain`**（Chain, prio 500） | 纯垂直分支里盖子实心且 `allowBreak` | 同 tick `if (!breaking)`／`onInterrupt`／`cancelEpisode` | ❌ **没有** |
| 2 | **`ClientPlayerAvatar.breakHold`**（USER 链，prio 50）<br>= `Walker.avatarDig` + 全部 process | 各 process 的 BREAKING 相 | 各自 `breakHold(false)` | ✅（`Walker.avatarDig` 就是 `breakHold+continueDestroy` 一扇门） |
| 3 | 空闲 `BotInteract.releaseKeys()` | — | 仅当 `scheduler.current()==null` 且 `releaseGate.consumeRelease()` | — |
| 4 | **`AntiSuffocate`** | 窒息中且头顶格可破 | `reset()`（自持 `held` 标志） | ✅（`rayMissTicks≥10` 后转 `gameMode.continueDestroyBlock` 直驱） |

#### 谁能抢谁 —— **答案不是「五个人抢」，是「四个人不抢 + 一个人无条件晚写」**

- **三条 Chain 之间根本不会同 tick 冲突。** 调度器每帧只 tick **一个** chain
  （`PANIC 1000 > DODGE 900 > DROWN_ESCAPE 500 > BUNKER 300 > … > USER 50`），
  所以 `BunkerChain` / `DrownEscapeChain` / 用户任务链**三选一**。这一层的仲裁是**真的、且已经存在**。
- **`AntiSuffocate` 不是 chain。** 它是**无条件的、调度器之后**的反射
  （`BotApiImpl:1282`，`if (mc.player != null) AntiSuffocate.tick(...)`）。
  所以它**叠加**在那一帧胜出的 chain 之上，而且因为写得晚，**它赢这个闩**。
  代码里那行注释是知情的：「runs after the scheduler so it overrides a digging process's aim」——
  **对 aim 是有意的，对破坏槽位是副作用。**

#### 危害的确切机制（和你今晚在 walker 内部抓到的是同一条）

vanilla `MultiPlayerGameMode` 只跟踪**一个**破坏目标。设 `MineProcess` 正在破 X：

```
tick n   : MineProcess  aim→X, breakHold(true), continueDestroy(X)     destroyProgress(X) 累积
           AntiSuffocate aim→Y, keyAttack.setDown(true)                 ← 晚写，十字准星现在指 Y
           vanilla continueAttack 按十字准星射线 → startDestroyBlock(Y) → **X 的进度清零**
tick n+1 : AntiSuffocate 不再触发；MineProcess aim→X …                  → startDestroyBlock(X) → **Y 的进度清零**
```

**交替 = 两块都破不掉**，而两边的日志都显示「我在挖，attack=true」。
这就是 `StickyDig` 两相互抢的放大版，区别只在于**这里连一个共享槽位都没有**——
`AntiSuffocate` 和 process 各自持有自己的 `held` / `breakHold` 布尔，谁都不知道对方存在。

#### 需要注意的三点（写给下一轮，不是现在就动手）

1. **`AntiSuffocate` 的 `directDrive` 后备恰好绕过这个问题**：过了 10 tick 射线未命中之后它
   `keyAttack.setDown(false)` 然后直调 `continueDestroyBlock`。**但那是窒息路径的后备，
   不是仲裁**——头 10 tick 仍然在抢闩，而 10 tick 足够清掉一次积累。
2. **`pickFaceTowardsPlayer` 已经收敛了。** `AntiSuffocate:155` 用的就是
   `BotInteract.pickFaceTowardsPlayer(head, p)` —— 你担心的「第六种取法」不存在，
   两处待修的 Chain 直接照抄这一行即可。
3. **`BotInteract.releaseKeys()` 现在清 8 个键，其中 7 个已经没人按了**
   （`keyUp/Down/Left/Right/Jump/Sprint/Shift` —— 形状 A 之后全走指令通道）。
   真正需要它清的只剩 `keyAttack`，却和那 7 个混在一个数组里。
   **它清的那 7 个现在只作用于人类键盘**，正是 `BotInput` 的 javadoc 反对的「fights manual play」。
   放行后可以收成「清 `keyAttack`（+ `keyUse` 由现有 use-key 协议管）」，但这会改变
   「cancel 会不会把人按着的 W 松开」这个行为，**要你判**。

---

### F3 到同一个 `AvatarInput` 有**四条**路，其中一条才是架构缝

| 路径 | 用者 | 能驱动服务端身体吗 |
|---|---|---|
| `Avatar` 接口（`a.commandMove/Forward/Jump/Sneak`） | **全部 `process/`** | **能**（`ServerPlayerAvatar` 实现同一接口） |
| `BotInput.xxx(mc, v)` | 反射层（本轮接上的 4 个文件 + `DodgeChain`/`PanicChain`） | 不能（拿 `Minecraft`） |
| `AutoSwim` 自抄的 `in(mc, p)` | 1 个文件 | 不能 |
| `mc.options.key*` | 只剩 `keyAttack`/`keyUse` | 不能 |

`Avatar` 才是真源（`ClientPlayerAvatar` / `ServerPlayerAvatar` 双实现），
`BotInput` 只是客户端侧的便利层。**没改，因为这不是清理，是架构决定。**

### F4（**可能比 F1 更值钱**）整个反射层在**真梯跑的那个拓扑上是空的**

`DrownEscapeChain.tick` 第一行就是 `if (mc == null) return;   // headless arena: decision-layer only`；
`ClutchController` / `ContactDamageEscape` / `LavaProximityEscape` 全部以 `Minecraft mc` 为入口，
从 `BotApiImpl` 的 **clientTick** 调用。

而按 `AGENT_TEAM.md` 的记录，**真梯跑的是 `journeyServer`，无头、零客户端**
（`journey.topology=dedicatedServer（真玩家 0）`）。所以在真梯上：
淹死逃生、MLG 水桶、接触伤害逃生、熔岩逼近逃生 —— **一个都不执行**。
`DrownEscapeChain` 甚至专门留了 `sensorForTest` 缝让无头场景驱动它的 `priority()`，
**于是决策层在闸里是绿的，而执行层在同一个拓扑上是空的**——
「skip 不是覆盖」的一个变体：**这里连 skip 都不报，它只是安静地 return。**

没改。修法是把这四个反射搬到 `Avatar` 接口上（F3），是一笔大的、需要你排期的改动。

### F5 一份被粘贴进整个 `bot/process/` 和 `bot/movement/` 的 import 块 —— **~726 个死 import**

`common/src/main` 全量扫描（保守口径：跳过 wildcard 和 static import；名字在 import 之后的
正文里出现过一次就算「用了」，含 javadoc `{@link}`，所以**只会少报不会多报**）：

```
39 BackfillTracker   38 Schematic        37 ElytraController  36 RunAwayProcess
36 LookProcess       35 BridgeProcess    34 ExploreProcess    31 SleepProcess
31 FollowProcess     30 TowerProcess     28 BboxFillProcess   28 BackfillProcess
27 FarmProcess       26 ElytraProcess    26 BuildProcess      24 WalkerTickRepath
23 WalkerTickStallDetect  23 WalkerTickEdgeGuards  23 WalkerTickAim  22 WalkerTickPrelude
                                                              ── 合计 726
```

**根因不是「各自长歪了」，是一次机械粘贴**：这些文件的 import 块**逐字相同**
（`RunAwayProcess` 是 107 行代码配 48 行 import；`TowerProcess` 里
`Minecraft` / `KeyMapping` / `ItemEntity` / `Comparator` / `ElytraPhysics` / `PathFinder` / `Blocks`
在全文各只出现 **1** 次 —— 就是 import 行自己）。
本轮删掉的那 9 个 `BotInput` import（`03c46e3c`）是这块粘贴的一角。

**没做全量清理，理由是「不能编译」**：726 处机械删除里藏一个错，我没有编译器能发现它，
而这正是用户点名的那类风险。**建议放行且你有一趟编译富余时再做**，做法：
按文件整块替换 import（每文件一次 Edit），逐份把整个文件读完人工核对，
改完重跑同一个扫描脚本确认归零。脚本在
`…/scratchpad/unused_imports.py`（只读，不改文件）。

### J5 起飞前的判据体检（coordinator 08-22 放行时的前置条件）

要求：**死 import 的判据要能区分「没用到」和「用不到」**，各举一个实例再动手。
体检脚本 `…/scratchpad/import_probe2.py`（只读），用真词法扫描分开代码与注释。

**族 A（只在注释/javadoc 里出现）—— 17 个实例，真实存在，我的扫描器已经全部放过。**
最干净的一个是**字面上的 `{@link}`**：

```
ReplayInstaller.java:3   net.magicterra.worlddriver.bot.movement.Walker
    * drives {@link Walker#beginReplay} so the wedge reproduces with no re-planning.
```

删掉它**编译照过**（javac 默认不跑 doclint），只是文档链接断了。同族还有
`TowerProcess.java:30 Level`（`Level#isUnobstructed`）、`WalkerTickDrive.java:14 NoBreak`、
`ClientWorldView.java:27 Fluids`（注释在解释「用 FluidTags.LAVA 而不是 Fluids.LAVA」——
**这条注释的全部价值就在那个被删掉的名字上**）。

**但这 17 个里有两个是英文单词碰撞，不是类型引用**：
`MineProcess.java:33` 和 `ClutchController.java:13` 的 `Blocks` 出现在
`/** Blocks. Wide enough…` 和 `/** Blocks above the landing floor…` —— 句首的普通名词。
这两个 import 是**真死的**，我的保守口径把它们一起放过了。
**代价方向是对的：少删两个，不会删错一个。**

**族 B（`import static` 被同名成员遮蔽）—— 全仓 0 个实例，而我的第一版探针报了 22 个，全是它自己的假阳性。**
v1 的正则 `[\w.<>\[\],? ]+\s NAME \s*[(;=]` 把**调用点**当成了声明：`return runOnClient(` 里
`return` 被吃成类型、`runOnClient` 被吃成方法名。v2 要求行首 + 至少一个修饰符关键字，才排除掉。
**这和括号检查器把 `"bot/scheduler/**"` 当块注释是同一个形状** —— 探针的假阳性和被测对象的
真缺陷长得一模一样，所以每一条报出来的实例都得手点开看。
顺带查了另一个方向（`import static X.m` 与 `import static X.*` 同时存在的冗余）：**也是 0**。
结论：**静态 import 我一个都不碰**（扫描器本来就整族跳过），这不是因为它们都活着，
而是因为**名字扫描没有能力判断它们**——105 个 wildcard static import 就是那个能力边界。

**族 C（一行长得像 import 但不是 import）—— 0 个实例，但这条必须查。**
我的扫描器用「最后一条 import 行」切正文；如果某个 javadoc 里写了 `import foo.Bar;`，
切点会被推到真代码中间，正文变短，于是**一大片活 import 被误判成死的**。全仓 0 个，判据安全。

**这三族之外还有一族，不是判据问题而是并发问题，今晚真的发生了一次**：
coordinator 用 `git add -u` 提交自己的改动，把我当时还没提交的三个文件（53 行删除）
裹进了他的提交。已 `reset --soft` 拆开重提（我的那批成了 `83bbe22b`）。
**一揽子暂存和一揽子的 `-A` 是同一类东西**；在多个 agent 共用一棵工作树时，
唯一安全的形式是逐文件 `git add <path>`。我这五笔都是逐文件 add 的。

**顺带量出来的真正结论：724 个死 import 不是 724 个独立判断，是一份 header 被复制了 25 次。**

```
BackfillTracker.java   80 行代码 / 47 条 import / 39 条死   → 文件的 59% 是 import 行，83% 的 import 是死的
Schematic vs BackfillTracker   import 块只差 5 行（NBT 那三条 + Iterator/LinkedHashSet）
LookProcess vs BackfillTracker 只差 6 行
RunAwayProcess vs ElytraController 只差 1 行（Avatar）
```

15 个 `process/*` + 10 个 `movement/WalkerTick*` 各自继承了同一份母块 ——
**前者是「新建一个 process 时抄了隔壁的头」，后者是 3000 行预算拆 `Walker.java` 时每一片都带走了整份 import。**
所以删除是机械的：判断只有两个（族 A 放过、静态全跳过），其余按脚本执行。

### F8（**coordinator 指派：拆 `BotConfig` 的真正阻碍**）两个「唯一枚举器」对继承的看法不一样

问题问的是：`SettingsRegistry` 的反射口径有没有依赖「字段声明在 `BotConfig` 这个类里」。
**有，而且只有一条，它不在 `SettingsRegistry` 里。**

| 枚举器 | 反射调用 | 看不看得见父类的字段 | 谁靠它 |
|---|---|---|---|
| `SettingsRegistry.reflectivePrimitiveFields()` | `BotConfig.class.getFields()` | **看得见**（`getFields` 走父类链） | settings schema、`knownKeys()`、`SettingsSnapshot.build` |
| `SettingsRegistry` 静态块 :276 | `BotConfig.class.getField(...)` | **看得见** | `CONFIG_FIELD` 手写键 |
| `SettingsCommand` :391 | `BotConfig.class.getField(k)` | **看得见** | `mc.bot.setting` 的 apply |
| **`BotConfig.persistableFields()` :2865** | **`BotConfig.class.getDeclaredFields()`** | **看不见** | `save`/`load`/`snapshotAll`/`restoreAll`/`COMPILED_DEFAULTS` |

两处 javadoc 各自写着「**the ONE enumeration** …… so they can't drift apart」——
**每一句在自己范围内都是真的，但这个文件里有两个「唯一」，而它们对父类的看法相反。**
今天看不出来，因为所有字段都声明在同一个类里。

**所以拆分只有一种形状能活下来，而它恰恰是最自然的那种的反面**：

- 拆成 `BotConfig extends BotConfigPathfinder extends …`（父类链）→
  三条 `getFields` 口径**照常工作**，`persistableFields()` **静默丢掉所有被搬走的字段**。
- 拆成互不相关的兄弟类（`BotConfigPathfinder.foo`）→ 四条口径**全部**丢掉，
  但这个会立刻炸（`SettingsRegistry` 静态块 :278 抛 `IllegalStateException`），**响的**。
- 拆成接口常量 → 接口字段隐式 `public static final`，被 `isVolatile` 过滤掉，不可行。

**父类链那条是危险的，因为它是静默的**，而且后果正好是 `snapshotAll` 的 javadoc 说自己要防的那件事：
被搬走的字段仍然出现在 settings 快照里（agent 看着一切正常），却不再被持久化、
不再进 `snapshotAll()`、于是 `restoreAll()` 不还原它们 ——
**`applyGameTestBaseline()` 会把 OFF 基线泄进活着的 bot，只泄那些被搬走的 flag。**

**我在这里先写错了一次，按代码改回来 —— 记下来因为错的方向很典型。**
我原本写的是「J6 那条守卫抓不到它，而且会绿」。**去读了 J6 自己的代码，这句话是错的**：
`everySurfaceBooleanHasACompiledDefaultOrIsDeclaredRuntimeOnly`（**本轮已改名为
`everySurfaceFieldHasACompiledDefaultOrIsDeclaredRuntimeOnly`**，见 §J8）做的**正是**
拿 `reflectivePrimitiveFields()`（`getFields` 口径）去减 `compiledDefaults()`
（`getDeclaredFields` 口径），并要求差集逐字等于 `RUNTIME_ONLY`。
**被搬到父类的 boolean 会出现在差集里、不在 `RUNTIME_ONLY` 里，于是这条断言会红。**

我犯的错是**从「两个枚举器口径不同」推出「守卫看不见」，而没有去读守卫本身**——
和这个仓库反复吃亏的形状一样：**推出来的机制，当成量出来的写。**

**改正后的真实覆盖面（量出来的，不是推的）**：

| 字段类型 | 数量 | 搬到父类后 J6 会不会红 |
|---|---|---|
| `boolean` | 138 | **会**（那条断言只过滤 `boolean.class`） |
| `int` / `double` / `float` / `long` | 38 / 38 / 7 / 3 = **86** | **不会** —— 断言把它们过滤掉了 |
| `String` / `Set<String>` / `double[][]` | 1 / 3 / 1 | **不会** —— 它们根本不在 `reflectivePrimitiveFields()` 里（非 primitive），却是 `persistable` 的 |

所以真正的洞是 **86 个非 boolean primitive + 4 个 String/Set**，
一共 **90 个字段搬走时没有任何守卫会响**。boolean 那 138 个是安全的。

**而堵上这个洞是一行**：把那条断言里的 `f.getType() == boolean.class` 过滤去掉。
**今天必绿，可以从源码证明**：`persistable` 接受 boolean/int/long/double/float/String/Set，
而 `BotConfig` 里出现过的 primitive 只有这五种；每个 `public static volatile` primitive
都是 static 且非 final（volatile 蕴含非 final），所以全部 persistable；
`serialize` 只在 `f.get(null) == null` 时返回 null，而 primitive 装箱后永不为 null。
⇒ **除 `NON_PERSISTED` 那三个（全是 boolean）外，每个 public static volatile primitive
都在 `COMPILED_DEFAULTS` 里。** 见 §J8。

**结论：日后那次拆，第一步不是动字段，是让 `persistableFields()` 和 `reflectivePrimitiveFields()`
对继承取得一致。** 今天改 `persistableFields()` 走父类链是**可证明的 no-op**
（`BotConfig` 的父类是 `Object`，没有任何 `persistable` 字段），但它改的是每一趟都要加载的产品代码，
coordinator 正在反复起游戏，所以现在不动。我改的是**只读的那一半**（见 §J8）。

### F9 「有没有助跑」这个概念，在 leap 家族里有三种算法，而写下规则的那次只应用到了一个成员

`Move` 有两个同名重载：

```java
public static boolean hasRunway(WorldView w, BlockPos from) {          // 只看脚下一格是不是实心
    return w.isSolid(from.offset(0, -1, 0));
}
public static boolean hasRunway(WorldView w, BlockPos from, int dx, int dz) {   // 再要求身后 RUNUP_CELLS=2 格
    ...  if (!w.isSolid(back.offset(0, -1, 0))) return false;   // nothing to run along
         if (!w.isPassable(back)) return false;                 // a wall behind is not a runway
}
```

第三种在 `ParkourAscend.valid` 里内联，由 `pathfinderParkourAscendNeedRunway` 控制，
要求身后**一**格 `canStandAt`。三种算法要求身后 **0 / 1 / 2** 格，谓词也不一样。

**八个调用点里，只有 `Parkour3` 用四参数那个。**
`Parkour2` / `Parkour2Diagonal` / `Parkour3Diagonal` / `Parkour4` / `ParkourAscend` /
`ParkourDescend` / `ParkourPlace` 全部用两参数那个。

**先查了这是不是故意的 —— 一半是。** `982785ce`（2026-08-19）的 CHANGELOG 逐字写着：

> Only the 3-block leap asks the new direction-aware form — **a 2-block gap is inside a standing jump**,
> and a guard that refuses what works replaces a route with a worse one rather than a safer one.

所以 `Parkour2` / `Parkour2Diagonal` / `ParkourDescend`(dist 2) 的豁免**有理由且理由成立**。
但这条理由**没有覆盖比 3 更远的那些**：`Parkour4`（4 格）、`Parkour3Diagonal`（~4.24 格，
它自己的 javadoc 写「at the absolute edge of sprint-jump physics」）、
`ParkourAscend` dist-3、`ParkourDescend` dist-3/drop-2。
**按 CHANGELOG 自己的逻辑，这四个比 `parkour3` 更需要助跑，而它们一个都没问。**

而且这个仓库**已经为同一个粗判付过一次账**，并把结论写进了 `BotConfig` 的 javadoc：

> 「The launch cell's below-neighbour is the pillar (so the **coarse** `Move#hasRunway` passes);
> only THIS approach-runway gate … rejects the leap」——gap #53 `wd.selfShaftDigUp`，
> 从自己垒的 1 宽柱顶起跳，直坠 20 格。

**它当时的修法是给 `ParkourAscend` 单独加一个 flag，而不是修那个粗判。**

**现场感最强的一点**：`Parkour4.java` 第 29–32 行，也就是那句 `hasRunway(w, from)` 的**下面两行**，
写着这个仓库自己总结的教训：

> The void rule covers the **WHOLE leap family**, not the three members it was first written against.
> **An exemption narrower than the family it must cover is how this class of bug leaks back.**

**同一个文件，先讲了这条教训，再在另一条守卫上犯了同一个错。**

**严重性：潜伏，不是活的。** `Parkour4` / `Parkour3Diagonal` / `ParkourAscend` dist-3 /
`ParkourDescend` dist-3 全部挂在 `allowParkour4` 后面，而它 **default false**，
全仓只有两个 validation 脚本把它开关一次做 round-trip（`24_phase_d2.js` / `25_phase_d3.js`），
**没有任何真导航把它打开过**。它正是 F2c 那 14 个「从没被翻开过的默认 OFF」之一 ——
**开关关着，所以它后面的缺陷从来没有人付过账，这也正是它还在那儿的原因。**

**没改，理由**：改法是把那四个长 leap 换成四参数版本，方向是**变严**（安全的方向），
但它会改变 A* 的可行边集合，必须过闸；而 coordinator 现在梯子不稳、11 级正在查第二个缺陷，
**这时候动寻路的可行边是最坏的时机**。排到梯子落地之后。

### F10 **翻开一个默认 OFF 的 flag，等于一次性接手它的全部历史**（coordinator 指定单独成节）

F2c 数出 14 个从来没有被任何真运行翻开过的默认 OFF flag。当时我把它记成「欠账不是腐化」。
F6 推翻了一半（`applyGameTestBaseline()` 会主动把一批按住），**F9 把剩下那一半也说清了**：

`allowParkour4` 关着 → 它后面的 `Parkour4` / `Parkour3Diagonal` / `ParkourAscend` dist-3 /
`ParkourDescend` dist-3 **从来没有在真导航里跑过一步** → 它们身上那条
「四个最长的 leap 一个都不问助跑」的缺陷，**没有任何人付过账**。
不是「查过了没问题」，是**从来没被问到**。

**所以这一族的正确判词是**：一个默认 OFF 的能力，它的**正确性从未被任何真运行检验过**。
而这些开关存在的意义恰恰是「卡死的时候翻开它」——也就是说，
**它会在一个人最着急、最没有余力做验证的时刻被翻开**，
而那一刻他一次性接手的是这个能力从写下那天起攒下的**全部**未验证行为。

**这解释了一个反复出现的病征**：翻开一个 flag 之后「莫名其妙多出一堆新问题」，
于是又翻回去，于是这个能力永远停在未验证状态 —— 一个自我维持的循环。

**处置建议（不是现在做）**：这 14 个不该被当成「待启用的功能」，
应该被当成**「未验证的代码，默认不加载」**。两者的区别在于前者的动作是「哪天翻开」，
后者的动作是**「要么给它一条能跑的场景，要么删掉它」**。
删掉一个从没跑过的能力，代价只是重写它；留着它，代价是下一个翻开它的人。

### F12（**这一轮最像 brief 第 1 条的一条**）日夜相位在三个地方各算一次，**其中一个和另外两个不一样**

同一个概念（`dayTime` → `day/sunset/night/sunrise`）有三份实现：

| 位置 | 归一化写法 | 负数 `dayTime` 时 |
|---|---|---|
| `api/ObserveApi.java:188` | `long tod = ((dt % 24000L) + 24000L) % 24000L;` | **防了** |
| `bot/ClientEventDetector.java:201` | `long tod = dt % 24000L; if (tod < 0) tod += 24000L;` | **防了**（写法不同，结果相同） |
| **`client/internal/ClientObserve.java:119`** | **`long tod = dt % 24000L;`** | **没防** |

Java 的 `%` 保留被除数符号，所以第三份在 `dt < 0` 时 `tod ∈ (-24000, 0]`，
`tod < 12000` 恒真 ⇒ **永远报 `"day"`**。

**这不是「没人想过负数」——三份里有两份各自独立地想过，第三份漏了。**
而且第二份的注释逐字承诺要跟第一份一致：

> Buckets match `observe.player.time.phase` so the Agent reads the same vocabulary.

**它靠复制来兑现这个承诺，于是承诺的正确性等于复制那一刻的手气。**

**诚实的定级（不猜 vanilla）**：我没有去断言 1.21.1 里 `Level.getDayTime()` 到底能不能是负数。
不需要断言就能下结论：**三份里必有一份是错的** ——
要么负数可达、第三份报错相位；要么负数不可达、另外两份带着永不执行的防御代码。
**而「到底哪个」这个问题之所以读代码答不出来，正是因为有三份。**

**修法**：`bot/util/` 下已经有一个现成的家族做这件事 —— `AttackSnap` / `ItemSnap`，
「the one place X is projected into an API row」。
`AttackSnap` 的 javadoc 逐字写着这条规则：

> Single helper rather than two copies because the snapshot is built on both sides
> (server `ObserveApi.playerSnapshot`, client `ClientObserve.observePlayer`);
> **duplicating the arithmetic is exactly how one field grows two meanings** — see gap #41.

**同一个文件、同一个方法里，`attack` 那一行守了这条规则，`time` 那一块（往上约 50 行）没守。**
所以修法不需要新的架构决定：加 `bot/util/TimeSnap.java`，三处都调它。
`ClientObserve` 已经 import 了 `bot.util.AttackSnap` / `ItemSnap`，**依赖方向现成**。

**没做**：这是产品代码 × 3 个文件，要过闸，而 coordinator 正在跑第四趟排练。已排进待办。

### F14（**brief 第 4 条的正解**）18 个 `attach()` 抄了同一段开场白，**其中 16 个漏了同一件事**

普查每个 `BotProcess.attach()` 到底写了槽里的哪些字段：

```
active goal lastError startedAtMs                              ← 18 个进程，全都写，逐字同构
  + goalReached endReason                                      ← 只有 BunkerProcess
  + goalReached endReason finalDist                            ← 只有 IntentProcess
```

**`goalReached` / `endReason` / `finalDist` 是「上一趟的终局判词」，
16 个进程在开始新一趟时不清它们。**

而 `ProcessSlot.reset()` **故意**保留它们（javadoc 逐字：
「Kept across reset() (like lastError) so awaitable/wait.condition can read it」）——
**保留是为了「跑完之后还能读到」，不是为了「下一趟跑着的时候还挂着」。**
证据是 `lastError`：它同样被 `reset()` 保留，而**18 个 `attach()` 全都把它清成 null**。
所以作者的意图很清楚：**`reset()` 留，`attach()` 清。16 个只清了四个字段里的一个。**

**后果直达 LLM。** `ProcessSlot.snapshot()` 把这三个都发出去，于是一趟新的 mine 跑到一半，
`mc.bot.status.mine` 长这样：

```json
{ "active": true, "goalReached": true, "endReason": "arrived", "finalDist": 0.4 }
```

**`active:true` 和上一趟的成功判词并排放着。** 一个 LLM 完全可能读成「已经挖完了」。
工具 schema 里**没有任何一句**说明 `goalReached` 可能来自上一趟
（`BotTools.java` 里唯一提到 `goalReached` 的地方讲的是 `mc.bot.pathStats`，另一回事）。

**为什么 `awaitMs` 没被咬到（我去查了，不是猜的）**：
`DriverApi.awaitable` 先轮询到 `active != true` 才读 `goalReached`，
而 `UserTaskChain.setProcess` 是**同步**调 `attach` 的（`cancel` → `attach` → `process = next`），
所以 `impl.apply()` 返回时 `active` 已经是 true。**这条路径是安全的。**
不安全的是**自己轮询 `mc.bot.status` 的消费者**，也就是 agent。

**修法（不是现在做，要闸）**：不要去改 16 个文件的 `attach()`，
**改成让 `attach()` 没法写错** —— 在 `ProcessSlot` 上加一个开场白方法：

```java
public void beginRun(String goalText) {
    active = true; goal = goalText; startedAtMs = System.currentTimeMillis();
    lastError = null; goalReached = null; endReason = null; finalDist = -1;
}
```

18 个 `attach()` 各调一次，抄来的四行开场白同时消失。
**注意不能把清理放进 `setProcess`**：`attach` 有 **5 个**调用点
（`UserTaskChain` + `CombatChain` / `DuskSecureChain` / `RetreatChain` 三条反射链 + `ServerWorldDriver`），
只修 `setProcess` 会漏掉所有反射启动的进程 —— **又一次「豁免比它要覆盖的家族窄」。**

**风险与方向**：改后中途 status 不再携带上一趟的判词三件套，**方向是更诚实**。

#### 那条「只有闸能回答」的风险，先用只读扫描把候选集扫成了空（coordinator 指派）

问题：有没有消费者依赖「`active` 为 true 时还读得到上一趟的 `goalReached`」。
全仓扫 `goalReached`（testmod / validation js / gpt-player），逐条分类：

| 读的是什么 | 出处 | 判定 |
|---|---|---|
| **`ProcessSlot.goalReached`（就是这条要改的）** | `JourneyFlight.java:299` **仅此一处** | **安全** —— 它在 `if (finishedAt == null && rig.body().finished())` 里面，**已经等过运行结束** |
| `PathFinder.Result.goalReached()` —— 一次 A* 搜索的结果 | `WorldDriverBiasScenes` 十余处、`HorizonArena` | **无关**，不是同一个东西 |
| `status.lastPath.goalReached` —— 最近一次 A* 的判词 | `22_phase_c.js:41`、`gpt-player/tools.py:1165` | **无关**，而且 gpt-player 那处在 await 返回之后读 |

**候选集为空。** 所以这次改动的闸只需要**确认**，不需要**发现** ——
这正是 coordinator 说的那种「先读出来再交给闸」的形状。

#### 顺带一条 J9 家族的：`goalReached` 在这个仓库里是**三个**不同的东西

上面那张表本身就是发现：同一个名字指

1. `ProcessSlot.goalReached` —— 一个动词的终局判词（`Boolean`，可空，跨 `reset()` 保留）
2. `PathFinder.Result.goalReached()` —— **一次搜索**有没有到达目标（`boolean`）
3. `status.lastPath.goalReached` —— 最近一次 A* 的判词，导出给 agent

**而 1 和 3 同时出现在 `mc.bot.status` 里**：每个槽下面一个，`lastPath` 下面一个，
**同名、不同含义、不同生命周期**。跟 `pathLen` 是同一族——
**名字没有携带它的作用域**，而唯一分不清的消费者又是 LLM。

19 个文件、生存/API 双路径，排给 coordinator。

### F13 两个 MCP 工具对「现在是白天还是晚上」给出**不同词表 + 不同边界**的答案

`TimeSnap` 落地后 grep 字面量抓出的第四份拷贝（`WorldModel.dayPhase`）不只是一份拷贝，
**它是一条独立的、也导出给 agent 的 API**：

| 工具 | 键 | 词表 | 边界 |
|---|---|---|---|
| `mc.observe.player` | `time.phase` | `day` / `sunset` / `night` / `sunrise` | 12000 / **13000** / **23000** |
| `mc.client.scene` | `dayPhase` | `DAY` / `DUSK` / `NIGHT` / `DAWN` | 12000 / **13800** / **22200** |

**两个都写在工具 schema 里**（`ClientTools.java:50-52` 逐字：「dayPhase is DAY/DUSK/NIGHT/DAWN」），
也就是说**两份都进每一次 prompt**。同一个 LLM 轮询这两个工具，在两个窗口里会拿到互相矛盾的答案：

- **`[13000, 13800)`** —— `observe` 说 `night`，`scene` 说 `DUSK`
- **`[22200, 23000)`** —— `observe` 说 `night`，`scene` 说 `DAWN`

#### 对 `DuskSecureChain` 的实际影响（coordinator 指定要的那个数）

`WorldModel` 只把 `dayPhase` 用在一处：

```java
boolean exposedAtNight = ("NIGHT".equals(phase) || "DUSK".equals(phase)) && skyExposed;
```

**它把 DUSK 和 NIGHT 或在了一起**，所以 **13800 那条边界对反射完全无效**——
反射武装区间是 `[12000, 22200)`，agent 被告知的「非白天」是 `[12000, 23000)`。

- **起始沿完全一致（都是 12000）。**
- **只有黎明沿差 800 tick**：`[22200, 23000)` 这段里 agent 读到 `night`，**而反射已经收工。**

**我上一条消息里说的「早 800 tick 开始、晚 800 tick 结束」是错的**（第四次自查，见 F11）。
错因同族：我看见两张表里两个数字不同，就叙述了一个**对称**的错位，
**而没有去追哪个消费者真的读那条边界**。追下去之后，一半的分歧是纸面的。

**「有没有别的门」**（原问题）：有四道，但**它们都不专吃这 800 tick**，
而是在任何时刻同等地压制反射，所以不构成豁免：

1. `BotConfig.autoSecureAtDusk` 必须开
2. `skyExposed` = `canSeeSky(foot.above())`
3. `!cornered`（除非 `rearmPending`）
4. 仅在 `IDLE_SECURE(40)` 档：`THREAT_RADIUS` 内有威胁则否决 + idle 去抖；
   `DUSK_URGENT(90)` 档**故意跳过**威胁否决

**定级**：黎明沿那 800 tick 是真的，但它落在**夜的尾巴上、天快亮时**，
「现在挖洞」的边际价值最低 —— **真实但低优先级**。
**这条发现里更值钱的一半是面向 agent 的那半**：两个窗口都受影响，
而且撞上它的是 LLM，不是代码。

**处置建议**：不要去「统一两张表」——`dayPhase` 的四个桶里，
**只有 22200 那条边界是承重的**（12000 与 `observe` 一致，13800 被 OR 掉，DAWN/DAY 对反射等价）。
所以形状是**把 `dayPhase` 换成它真正喂养的那个谓词**，
再让 `mc.client.scene` 直接导出 `TimeSnap.phase`（词表也随之统一）。
那是一次 API 词表变更 + 一次反射边界变更，**要闸、要归因，不是清理。**

### F11 **太顺手的结论** —— 我今晚自己抓自己三次，三次的形状完全一样

| # | 错的那一版 | 真相 | 错在哪一步 |
|---|---|---|---|
| 1 | 「三个测试文件里有一个少了 `}`，被吞掉 60 行」 | 文件是好的；是我的括号检查器把 `"bot/scheduler/**"` 里的 `/*` 当成块注释开头 | **工具错了** |
| 2 | 「J6 那条守卫抓不到父类拆分，而且会绿」 | 那条断言做的正是这件事，boolean 搬走会红；真洞只有 86 个数值字段 | **推理冒充测量**（没去读守卫本身） |
| 3 | 「12 个进程 tick walker，只有 6 个上报进度 —— 8 个动词的路径进度是假的」 | Mine/Follow/Explore 都写了**自己那个槽**；真问题是同一个字段有五种单位 | **测量窄了**（grep 带了 `builder.` 前缀） |

**三次的共同点不是「出错」，是错的那一版都比真相更漂亮。**
「60 行被吞掉」「守卫有个洞」「8 个动词全是假的」——干净、有冲击力、可以直接写进报告。
真相三次都更琐碎：文件是好的 / 守卫其实会红 / 只有一半写入点是假的。

**所以这一族的自检问题不是「我量对了吗」，是「这个结论是不是太顺手了」。**
一个让人**想立刻去报告**的发现，先把测量范围放宽一档再看一眼。
第 3 次就是这么救回来的：去掉 `builder.` 前缀重 grep，假发现整个消失，
**真发现才浮出来，而且比假的那个更具体。**

coordinator 08-22 同族的两次：冻屏诊断错了两次（第二次是从一处采样外推到全局）；
以及给瞄准写下一个**推出来的机制**（「服务端的写被客户端擦掉」），
后被身体格子那一行证据否掉 —— 真相是**瞄的和读的根本不是同一具身体**。
**形状一样：错的版本更完整、更像一个故事。**

#### 第六、七次（coordinator，同一晚），以及由它们得出的**可操作**版自检

| # | 错的那一版 | 一分钟验死它的东西 |
|---|---|---|
| 6 | 拿到 `waterFill.hand=minecraft:stone_pickaxe`，推出「换槽包输给了 use 包，服务端跑的是镐的 use」——机制完整、能解释全部三行证据 | `MultiPlayerGameMode.useItem` 的**第 15 条字节码**就是 `ensureHasSentCarriedItem()`，在发 use 包的 `startPrediction` **之前**：那场竞态**结构上不可能发生** |
| 7 | 以为服务端会用自己过期的角度重新射线 | `ServerboundUseItemPacket` **携带** yRot/xRot，`handleUseItem` 在 `useItem` 前 `absRotateTo` |

**两次都是「一分钟能验死的推论，差点直接写进注释」。**

所以给这一族补一条**可操作**的自检，比「放宽一档」更省：

> **当结论是关于一个可反编译的机制时，「放宽一档」的最省形式就是 `javap -c`。**
> 不用跑、不用复现、不用闸 —— 一分钟给的是**二进制事实**，不是又一层推理。

而且这两条排除**必须写进注释**，因为它们是**排除性证据**：
不写下来，下一个人看到那两行还会把同一条推论再走一遍。
（已钉进 `aimBoth` 和 `holdForUse` 的 javadoc。）

**这跟守卫那条是同一个道理**：一条被验死的假设，如果只活在某次对话里，
它就会被下一个人重新提出来 —— **排除本身也是产出，也需要一个落脚点。**

这条和 F7 是一对：F7 说「动作成功了但对象不在那儿」，
F11 说「结论成立了但它太合乎你已有的模式」。
**前者骗的是读数，后者骗的是判断，而后者更难自查，因为它是被自己正确的经验带偏的。**

### F7 一条贯穿本轮所有发现的形状：**尸体也会回答的读数**

coordinator 08-22 的教训：真梯在 FOOD 级被女巫杀了，梯子**对着尸体继续按了 15 分钟的 W 和跳**。
心跳每 200 tick 报「身体=37,62,83 @overworld」一切正常 —— 因为**位置正是尸体也会回答的读数**。
下游全是流利的谎话（`hSpd=0.009`、`attack=false`、背包全空、
`world.isSolid(36,63,83)=false` 而活着的游戏说那格是 `grass_block`），
差点被当成第二个缺陷去修。已在 `JourneyRig.await` 加了每 tick 活性断言。

**本轮五条发现是同一个形状的不同实例**，值得并排放着：

| 读数 | 谁也会给出同样的读数 |
|---|---|
| 心跳里的坐标 | **尸体** |
| `keyAttack.setDown(true)` 成功返回 | **一具从没抓过鼠标、`destroyProgress` 恒为 0.0 的客户端**（F2b） |
| `keyUp.setDown(true)` 成功返回 | **一具横向位移恒为零的身体**（形状 A，7800 tick 实测） |
| `holdItem` 返回 `True` | **一具没人在看的服务端身体**（`AGENT_TEAM` §A0） |
| `BotConfig.walkerDigAimPriority == true`（读源码） | **一个被 `applyGameTestBaseline()` 改写成 false 的进程**（F6） |
| `dig-aim RELEASE` 零行 | **一个守卫从未被进入的分支**（coordinator 背景一） |
| `BUILD SUCCESSFUL` | **一趟 `--tests` 过滤器一个都没匹配上的空跑**（08-22 闸窗口） |

**共同点：动作「成功」了，而动作的对象不在那里。**
所以这一族的判据规则是同一条 —— **不要问「我做了吗」，要问「有东西变了吗」**：
不是 `setDown` 返回了，是 `destroyProgress` 涨了；不是按了 W，是 XZ 动了；
不是 flag 在源码里是 true，是这个进程里它是 true。

---

## 2b. 闸与测试的实测读数（coordinator，08-22）

- **`ClientBreakSitePairingTest` 3/3、`GameTestBaselineManifestTest` 6/6，skipped=0 failures=0。**
  读数取自 `TEST-*.xml` 的 `tests=`/`skipped=`，**不是从 BUILD SUCCESSFUL 推的** ——
  `--tests` 过滤器一个都没匹配上时 gradle 同样是绿的，两种绿长得一模一样。
  （J6 是这批里唯一预期可能红的：我没跑过，且它会触发 `BotConfig` 静态初始化。没红。）
- `:common:compileJava` / `compileTestmodJava` / `compileTestJava` 三个 **executed**；源码预算闸 OK。
- **run 9（客户端真身）爬到 10/20 PORTAL_KIT，布景调用 0 次**，死亡断言全程未触发；
  此前该拓扑最好 5 级。**J1–J3 在这一趟里，没有打破任何一级。**

### J2 在 11 级那个病征上**不可能是原因**（算出来的，不是猜的）

11 级停在 37 格深井底部最后 2.2 格的**横向掏洞**。J2 改的正是挖掘认领的释放半径，
所以它是嫌疑之一。把两条判据在这个几何下逐格算了一遍
（脚 `(0.5,0,0.5)`、眼高 1.62、旧 = 脚→格心 `d²>20`、新 = 眼→格心 `> blockInteractionRange+0.5 = 5.0`）：

| 情形 | 脚距 | 眼距 | 旧 | 新 | 一致 |
|---|---|---|---|---|---|
| 横向掏洞·脚格 `(1,0,0)` | 1.12 | 1.50 | hold | hold | ✅ |
| 横向掏洞·头格 `(1,1,0)` | 1.80 | 1.01 | hold | hold | ✅ |
| 横向 2 格 / 4 格 | 2.06 / 4.03 | 2.29 / 4.15 | hold | hold | ✅ |
| 竖直向下 `(0,-1,0)` / `(0,-2,0)` | 0.50 / 1.50 | 2.12 / 3.12 | hold | hold | ✅ |
| **4 格在下** | 3.50 | 5.12 | hold | **RELEASE** | ❌ |
| **4/5 格在上** | 4.50 / 5.50 | 2.88 / 3.88 | **RELEASE** | hold | ❌ |

**两条判据只在身体与被认领格有 ≥4 格垂直分离时才分歧。**
一次相邻的横向掏洞离那个分歧面差着一个数量级 —— 两边都 hold，且余量很大。
所以 **11 级的死因不在 J2**；按 coordinator 的判断（judge 在 journey 判据里）继续查是对的方向。

---

## 2c. **J1 弄红了双 loader 闸 —— 我的账，完整归因**

### 病征

双 loader、专用服，**5 条纯逻辑矩阵场景在 0 tick 死**：

```
Fabric   : Cannot load class net.minecraft.client.player.LocalPlayer in environment type SERVER
NeoForge : Attempted to load class net/minecraft/client/player/LocalPlayer for invalid dist DEDICATED_SERVER
```

`wd.cancelRouting` / `wd.retreatGateMatrix` / `wd.chainEpisodeCancelMatrix` /
`wd.drownEscapeGateMatrix` / `wd.drownEscapePreempt` —— 这些场景直接
`new BunkerChain()` / `new RetreatChain(st)` 驱动矩阵，**链是在专用服上被构造的**。

### 病因（两侧都是实证）

coordinator 先怀疑我加的 `p.swing(...)`，**用字节码把这个假设否掉了**：
两个类**本来就有**一堆以 `LocalPlayer` 为接收者的 invokevirtual
（`blockPosition` / `getHealth` / `getAirSupply` / `setXRot`…），全部早于 J1。
**「链里出现 LocalPlayer 调用」不是新事。**

新事是 `git log -S` 指出来的：**`gameMode.continueDestroyBlock` 在 `scheduler` 包里
只由 `a1de83a4`（我）一笔引入。** 那一行把一个
`invokevirtual MultiPlayerGameMode` 放进了链自己的字节码，链接那个类会把 `LocalPlayer`
一起拽进来，服务端加载器拒了。

另一侧同样干净：`BotInteract` **本来就引用 `MultiPlayerGameMode` 八次**，
而这两个链一直在调它的 `aimAtBlockSnap` / `selectBestToolFor`（参数类型就是 `LocalPlayer`）
从来不出事 —— **invokestatic 解析的是 owner，不解析 owner 的依赖。**

### 修法（coordinator 做的）

`BotInteract.continueDestroy(mc, p, cell)`，两行搬进去，链里只剩 invokestatic；
javadoc 把规则写死：**scheduler 里的类可以「传递」客户端类型，但不可以「调用」它。**
字节码复核：两链的 `MultiPlayerGameMode` 引用双双归零。

### 三条教训

1. **J1 的意图和实现都对，错的只是「在哪一层写这一行」。**
   破坏管线确实该被驱动；把它写在 scheduler 里就等于给一个双端类装上单端依赖。
2. **这类错误编译期完全看不见。** `common` 是双端源码集，javac 眼里
   `MultiPlayerGameMode` 就是个普通类。它只在**专用服构造那个类的那一刻**才炸 ——
   **只有闸能抓到。**
3. **我那条 `ClientBreakSitePairingTest` 抓不到它，而且更糟：它在教下一个人犯同一个错。**
   原来的失败信息逐字建议
   「`or mc.gameMode.continueDestroyBlock(cell, pickFaceTowardsPlayer(cell, p)) if the site
   has no Avatar`」——**那正是炸掉双 loader 的那一行**。
   已改（`9aa0c7a9` 之后的一笔）：改成指向 `BotInteract.continueDestroy`，
   并把分层规则和这次的病征写进失败信息与类 javadoc。
   **改的只是失败时才求值的字符串 + javadoc，绿路径逐字节不变**，coordinator 那份 3/3 依然成立。

### 这一类的守卫该长什么样（**等闸绿再做，现在不动产品代码**）

源码扫描抓不到「驱动写错了层」。形状是**读字节码**：
扫 `bot/scheduler/**`（推广开是所有双端包）的 class 文件，
**禁止出现 owner 在 `net.minecraft.client.**` 的 invokevirtual / getfield**，
但**允许**这些类型出现在方法签名和局部变量里（「可以传递，不可以调用」）。
和 J6 / J1-verify 同族：**读产物，不需要跑游戏**，一次钉死一整类。
需要一份显式豁免清单（`ClientPlayerAvatar` 这种本来就是客户端实现的除外）。

### 与我无关但记着别背锅的两条

- `wd.serverEscapeSealedShelter` **今晚提交之前就是红的**（fabric 22:23 那份里已红）。
- fabric 那趟闸跑到第 171 场被服务端看门狗打断
  （`CombatProcess.approach → Walker.tick → PathFinder.advance → Parkour2.valid` 一个栈顶），
  今天早些时候已有一份 `results-watchdogcrash.jsonl`，是复发老毛病。
  **一份样本点不了名**（栈顶散布才说明是循环），暂不归因。

## 3. 确认过不是问题的

- **`keyUse` 没有失控。** 它有真的仲裁协议（`BotApiImpl.builderSuppressesAmbients`
  + `UseKeyOwnershipTest` 钉住取用者集合），且 javadoc 明确写了
  「every acquirer must join it or self-clear on every exit path」。**不用再查。**
- **三条 Chain 之间不会抢 `keyAttack`。** 调度器每帧只 tick 一个 chain，
  `BunkerChain`(300) / `DrownEscapeChain`(500) / 用户任务链(50) 三选一。
  **抢闩的只有 `AntiSuffocate` 一个**（它不是 chain）。不用再排查 chain 之间的竞态。
- **`pickFaceTowardsPlayer` 已经是唯一取法**，没有第六种面向算法要收敛。
- **`ContactDamageEscape` / `LavaProximityEscape` 的 `p` 恒等于 `mc.player`**
  （`BotApiImpl:1289/1294` 就是这么传的），所以 `BotInput` 内部重读 `mc.player` 是安全的，
  不是一个潜在的「两具身体」缺陷。
- **`AvatarInput` 里剩下的 3 处 `mc.options.key*.setDown` 是 javadoc 引用**，不是驱动。
  `ClientPlayerAvatar` 的 2 处是 `breakHold`/`commandUseItem` 的实现，是**正确的**架构位置。
- **`BotConfig` 里的 14 个 default-OFF flag 不是腐化**（F2c）——每一条都写清了验收路径，
  是一条没人清的欠账，不是「被关掉的正确实现」。**唯一符合形状 B 的是
  `walkerStickyDig`/`walkerDigAimPriority`，而它已在修。**

- **`Backfill` / `BboxFill` / `Build` / `FarmProcess` 的 `GOING` 分支不该合。**
  重复块扫描把这四个报成一组，但**共同部分只有三行**（`walker.tick(a,w)` +
  两行把进度镜像进 `st.builder`），而 `FAILED` / `ARRIVED` 两个分支体**各不相同且各有各的道理**
  （backfill 拉黑方块、build 还要 `holdById` 并在失败时跳过一个索引、
  farm 要重查作物是否还成熟、bboxFill 要处理「走过去的路上这格已经空了」）。
  **合它会造出一个满是回调的 helper，比四份诚实的副本更难读。**
  这正是 brief 里点名的「只是长得像但语义不同」，**不合**。
- **`yawFor(Direction)` 三份逐字相同（`BunkerProcess` / `DescendProcess` / `EscapeProcess`），
  可以无条件合并 —— 但不能换成 vanilla 的 `Direction.toYRot()`。**
  `toYRot()` 的 `EAST` 是 `270f`，这三份是 `-90f`。作为角度等价，
  但 `setYRot` 存的是**裸值**：渲染插值走差值，`-90 → 270` 会转一整圈。
  **角度等价 ≠ 数值等价，这个仓库已经吃过这个亏。要合就原样搬进 `BotUtil`，一个字符不改。**
  （等排练收工再动，见「待办」。）
- **`Parkour2` / `Parkour2Diagonal` 不用四参数 `hasRunway` 是故意的，理由成立**
  （`982785ce` 的 CHANGELOG 逐字：2 格在起跳范围内，
  「a guard that refuses what works replaces a route with a worse one rather than a safer one」）。
  **F9 只针对比 3 更远的那四个**，不要把这两个一起改。

（随确认随记）

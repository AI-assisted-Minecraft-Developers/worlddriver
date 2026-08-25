> ⚠️ **SUPERSEDED 2026-06-19**:决定性 live 诊断推翻本 spec 的「A* 路由 submerged」前提。残留 churn 异质且主在执行器层(z2744 受限深水井浮力爬出、waterLowBank 耦合),z2847=planner boxed-pinch,z3034 A* 本就干净。D1/C2 打错层,已放弃。见 memory `reference_water_churn_is_executor_not_planner` + plan 顶部。本文档仅留作设计推理存档。

# 浮力 bot 水域导航统一模型 — 设计 (Surface-First Routing)

**状态**:设计待评审
**日期**:2026-06-17
**背景会话**:深水寻路 churn 整类根治(117580d / 1af4167 之后)

## 1. 问题

A* 的 `WorldView.canStandAt`(`WorldView.java:264`)对**任意水格**返回 true(`… || isWater(foot)`),于是 A* 自由地把路径路由进/沿/出深水。但执行器是**浮力** bot:它只能稳定停在**水面**(脚在顶层水格、头出水),无法站在水下河床、无法水下起跳上爬、无法不主动下潜地保持水下位置。每一处水域 churn 都是执行器跟不上这种路径:

| 方向 | 症状 | 现状 |
|---|---|---|
| 上爬(stepUp/diag/swimAshore from deep) | 水下蹬不起跳 | 已禁(117580d) |
| 下降(diagDown/stepDown into submerged) | 浮在上面振荡 | 已禁(1af4167) |
| 水平(Walk 沿河床) | 浮起振荡 | **开放(z3034)** |
| 瀑布水帘(薄流水里水下 stepUp) | churn | **开放** |

之前是 per-move-direction 打补丁。本设计**根治整类**:确立一条不变量,让 A* 永远只产出可执行的水路。

## 2. 已验证的关键事实(代码读证)

- **F1**:`Walk.valid()`(`Walk.java:12`)= 纯 `canStandAt(to)`;`canStandAt` 对任意水格 true → **Walk 横穿一切水格,水面游泳与水下横移全靠它**。
- **F2**:横向移动只有 `Walk`/`Diagonal`;swim 族 `SwimUp`/`SwimDown` **纯垂直**(`Move.java:315-316`),横向只有带破坏的 `SwimTraverseBreak`(`Move.java:375`)。**没有纯水平 SwimTraverse 原语**。
- **F3**:`isWater` = `getFluidState().is(FluidTags.WATER)`(`LevelWorldView.java:65` 等),该 tag **含 source + flowing**,故流动水(瀑布水帘)被正确识别为水。
- **F4(架构冲突)**:当前**故意**把水域横渡路由在 submerged 河床节点(浮力 foot 下方 ~1 格),执行器靠 `floatOverSubmerged`(`Walker.java:1313`)贴水面骑过去,**限定在水面下 ≤2.5 格**(`dyNode > -2.5`)。z3034 的 churn = A* 把节点路由到 y55-58(浮力 foot y62 下方 4-7 格,**跌出 2.5 骑行带**)→ 补偿不触发 → bot 真去下潜 → 沉底振荡。这是 976d7b3 调出来的机制,本设计将**作废**它。
- **F5**:核心谓词必须是 **head-submerged**:bot 站在实心河床(y55)上、头(y56)没水也照样被浮力抬起;`isWater(foot)&&isWater(foot+1)` 会漏掉河床格。正确判据 = **`isWater(foot.above())`**。

## 3. 核心不变量(一个水位分类,两个派生判据)

水格 `foot` 按 bot 的可停性分类:
- `DRY` = `!isWater(foot)`
- `SUBMERGED` = `isWater(foot+1)`(**头在水下**;不管脚是水还是实心河床——浮力都把它抬起)
- `SURFACE` = `isWater(foot) && !isWater(foot+1)`(脚在水、头出水 = 浮力稳定停靠点)
- 其中 `canPushOff(foot)` = 脚下实心或干地(`!isWater(foot-1)`):能蹬地起跳/上爬;浮在水上(`isFloatingWater`)则不能。

> **不变量**:浮力 bot 的**非潜水**落脚点只能是 `SURFACE` 或 `DRY`;`SUBMERGED` 格只有「游泳族」move 在「潜水回退」语境下可进入。

两个派生判据(统一现有所有分散门):
- **D1 `headSubmerged(foot) = isWater(foot+1)`** → 任何地面族 move 的**目的地** `headSubmerged` 即 invalid(覆盖水平 Walk/Diagonal、下降 diagDown/stepDown、爬入更深的 stepUp)。
- **D2 `!canPushOff(from)`**(源格浮在水上)→ **上爬族**(stepUp/stepUp2/diagUp/swimAshore/bankClimb)invalid(水下/浮着蹬不起跳)。

z3034(河床 walk)、1af4167(下降)、cascade(薄流水水下 stepUp)全归 D1;117580d 的 ascent 归 D2。

## 4. 架构:Surface-First Routing

### 4.1 落脚点限制(取代 canStandAt 的水语义)

新增 `WorldView.headSubmerged(BlockPos foot) = isWater(foot.offset(0,1,0))`。

非潜水「地面族」move(`Walk`/`Diagonal`/`StepUp`/`StepUp2`/`StepDown`/`DiagonalAscend`/`DiagonalDescend`)在各自 `valid()` 内加守卫:**目的地 `headSubmerged(to)` 为真则 invalid**。
- 不直接改全局 `canStandAt`(memory 反复警告其调用面太广:mine/escape/stand-selection),而是在 move 层派生——与 117580d/1af4167 同模式,把那两处也并入这一条(见 §4.4)。
- 效果:A* 的非潜水水路**只能沿水面顶层格**(head=air)推进,**不再产生 submerged 横渡节点**。z3034 / z2744 / cascade 因「根本没有 submerged 节点可走」而消失。

### 4.2 潜水回退层(游泳族 + 成本)

为保留「水面优先 + 必要时可潜」(用户决策),submerged 格仍可由**游泳族**进入,但**高成本**:

- **新增 `SwimTraverse(dx,dz)`**(纯水平,无破坏;`Move.java` 注册;cost 基 + submerged 税):F2 证实当前缺此原语,否则禁 Walk 后水下隧道无路可走。
- **新增统一 `pathfinderSubmergedTraverseCost`**(BotConfig;起步 ≈ 80–120/格),对 `SwimTraverse`/`SwimUp`/`SwimDown` 进入 head-submerged 格加征,**XZ 与 land goal 都生效**(取代现 `waterCellTax`(XZ)/`submergedTax`(land)割裂覆盖)。
- 效果:有水面路 → 走水面(地面族);只有水下封顶通道 → A* 付高价用游泳族潜过去 = 分层。

### 4.3 dive-hold 执行器(跟随游泳族 submerged 段)

`Walker` 执行器需能**稳定跟随**一段游泳族 submerged 边(主动下潜 + 保持 + 水平推进),而非浮起振荡。
- 现 `diveHold`(`Walker.java:~2318`)仅为**垂直** swimDown 造;需扩展到 `SwimTraverse`(水平潜行):识别 move 前缀 `swim`、按节点 Y/XZ 主动 pitch-down + sink-hold + forward,直到该段走完。
- 该执行器只在**罕见的潜水回退路**上工作;主线全水面,不依赖它。

### 4.4 作废与收编(避免双轨打架)

- **作废** `floatOverSubmerged`(`Walker.java:1289-1317`)及其 `WATER_DESCEND_GIVEUP` 河床骑行补偿:Surface-First 下不再有 submerged 横渡节点,这套补偿无对象。**分阶段移除**(先让新模型生效并验证开阔深水穿越仍过,再删旧码),不留双轨。
- **收编**:`isFloatingWater`/`isSubmergedAscent`/`isSubmergedFoot`(117580d,ascent)归 **D2**;diagDown/stepDown 的 1af4167 守卫归 **D1**——统一改为从 §3 的水位分类派生,文档说明它们是同一不变量的两个投影,而非分散的 ad-hoc 检查。

## 5. 分阶段实施 + 验证矩阵

> 复现台:`mc.world.snapshot` + 固定 TP + `mc.bot.goto`;**终验 = `mc.debug.replay replay-0004 replan`** 整程确定性重跑(用户硬要求)。每阶段 GameTest **109+45 必须保持绿**。

- **Phase 0 — 现场验证假设(live;client 重连后)**
  - 复现 z3034,实测:A* 为何路由到河床而非近水面(`submergedTax`/`waterCellTax` 在该段是否生效、surface 是否被 overhang 挡)= **item 6 残留**。
  - 浅水/河滩/海浪:头出水的涉水在 Surface-First 下不被禁(head=air)——实测无 no-path 回归 = **item 6**。
  - 现 `diveHold` 行为基线,确认扩展点 = **item 3**。
  - 产出:确认 §4 假设或据实修正,再进 Phase 1。
- **Phase 1 — 核心 head-submerged 守卫**(地面族 7 个 move + `headSubmerged` 谓词)。验:GameTest 全绿;replay z3034 不再沉河床(改走水面或绕);开阔深水穿越 arena 仍过(关键回归护栏,976d7b3)。
- **Phase 2 — `SwimTraverse` + 统一 submerged 成本**。验:新增 `sealedUnderwaterPassageArena`(唯一出路是水下封顶通道)能潜过;有水面替代时不选水下。
- **Phase 3 — dive-hold 水平扩展**。验:Phase 2 arena 端到端 + replay 中任何残留 submerged 段平滑跟随。
- **Phase 4 — 作废 floatOverSubmerged + 旧河床补偿**(确认无回归后移除)。验:全 arena + replay-0004 端到端到达且 z2744/z3022/z3034 peak 全低 + 视频零异常窗。
- **新增 GameTest arena**:① `submergedSlotCrossArena`(水面横渡)② `sealedUnderwaterPassageArena`(潜水回退)③ `waterfallCascadeArena`(薄流水不 churn)。

## 6. 风险

- **R1 重写横渡核心**:Surface-First 动 976d7b3 调过的开阔深水穿越平衡。**缓解**:Phase 1 即把该 arena 设为护栏,replay 开阔深水段每阶段比对。
- **R2 no-path**:若某地形唯一水路是 submerged 且游泳族/dive 执行器没覆盖,退化为 no-path。**缓解**:Phase 2/3 的 sealed-passage arena;成本(非硬禁)保证 A* 仍能选水下。
- **R3 A* 稳定产出水面路**:顶层水格须可作 `canStandAt`(已核:foot=水、head=air → canStandAt true)。Phase 1 验证。
- **R4 dive 执行器水平潜行是新代码**(item 3 未 live 验):最脆弱,Phase 3 独立 arena + replay 双验,不过就保留成本层让 A* 尽量不走潜水路(主线全水面已够 z2744/z3034)。

## 7. 非目标(YAGNI)

- 不解决陡干山 stepUp 爬升 churn(独立的 dry-staircase 域)。
- 不改水流方向阻力(`directionalCost`)等既有水域成本以外的调参。
- 不追求「水下唯一通道」场景的丝滑(罕见;能潜过即可,允许慢)。

# 会话交接:#54 B1 落地 + 套件完整性漏洞发现(2026-07-16)

> 暂停原因:user 决定转向**测试框架重构**(task#52 自造测试框架 / task#85 吞测试漏洞)。
> 本文档是完整交接账:本会话做了什么、证据在哪、恢复时从哪继续。
> 分支:`feature/executor-permove-ascend`,tip = `39c13f2`。**未合 master**(B1 未过 live 验证,flag 默认 OFF)。

---

## 一、本会话完成的事

### 1. #54 Unit 2 设计终裁(user 决策)
- **A 硬抽(外部 TravelActuator)被代码实证否决**:驱动尾段(Walker.tickInner 4304→5279,~975 行)
  读 ~40 个 tick-local,核心 `aimYaw`(3938 行出生)依赖**跨 tick 实例字段** `smoothTargetYaw`
  (Walker.java:230,EMA 低通)+ 整条前半段 aim 管线(3283→3938)。外抽须连 aim 管线一起
  (~1700 行+字段生命周期)→ 机器坍缩成 tickInner 薄包装,per-move ownership 是虚构的。
- **user 决议:B1 + A″ 都排期**。
  - B1 = 薄机器织入:驱动原地不动(=单一真源),委托分支 fall-through,机器只拥有
    episode 生命周期 + dig-aware 死区看门狗。→ 本会话执行。
  - A″ = tickInner 原地相位化(TravelState 结构体 + perceive→advance→aim→decide→drive→emit
    私有方法切分,调用序列不变,byte-identical 分片)→ **task#84**,另议,数周量级。
- 计划文档:`docs/superpowers/plans/2026-07-16-executor-b1-thin-machine.md`(active);
  `2026-07-15-executor-travel-actuator-extract-A.md` 已标 SUPERSEDED(其 Slice 0 特征化笔记对 task#84 仍有用)。

### 2. B1-1 已提交 `3d22ad7`(结构改动,行为 no-op)
- 委托分支(Walker ~4292)去掉 `PREP/RUNNING → return Step.WALKING` 提前返回(那是逼第一个
  实现者重实现驱动的原罪);`PREP/RUNNING/SUCCESS` 全部 fall-through 进 legacy jump/drive。
- **step 推进权不动**,仍归 legacy advance 循环(Walker 2265-2288),防双推进竞态。
- AscendMovement 变成真 episode 跟踪器(node+move 边界、episodeTicks、bestY),看门狗未武装。
- flag OFF 构造上 byte-identical(全部改动在 flag 门控分支内部)。

### 3. B1-2 已提交 `ffd8a42`(task#82 dead-zone 修,核心交付)
- **看门狗语义**:episode 内 `ASCEND_DEADZONE_GIVEUP = 3×STEPUP_FREEZE_TICKS = 72t(≈3.6s)`
  零进展 → `UNREACHABLE` → `forceFellOffPath` → 既有 re-route/blacklist/futile 骨干。
  这给 spec §2.2 的死区姿态(`cur2∈(0.45,4.0)`、无 hCol、+1 节点——所有 legacy recovery 门全 miss)
  第一次装上了触发器。
- **progress 判定 = 单调高水位**:bestY(dy 高水位)/ bestCur2(对节点中心的最近接近,平方距)
  ——crestOrbitTicks 同款 bob 免疫技巧,跳-落-回弹不会重置时钟;
  **∨ `breakingEdge` 挖掘活跃直接豁免**(#66 教训:空手挖石 150t+/块;死区姿态里 bot 够不着
  挖 → 挖掘中≠死区,天然成立)。
- 改动文件:`AscendMovement.java`(武装)、`MovementContext.java`(+`digging` 字段)、
  `Walker.java`(传 `breakingEdge` + UNREACHABLE 遥测 LOG)、`WalkerConstants.java`(新常量)、
  `AgentGameTestTerrain.java`(新 seam arena)。
- **seam 验证已过**(见下文注意事项):`ascendDeadZoneWatchdogArena` 直接驱动
  `AscendMovement.updateState`,断言全链:fresh→PREP;恰好 72×RUNNING→UNREACHABLE;
  终态清 episode(下 tick 重新 PREP);挖掘 tick 不累计且完整重置时钟;同高度 bob 不重置(单调性)。

### 4. ⭐⭐⭐ 发现 task#85:GameTest 套件静默吞测试(P0,先于本会话改动的既有漏洞)
- **现象**:全量 `runGameTestServer` 报 "130 GAME TESTS COMPLETE / 计 PASS",但
  `ascendMovementNoopArena` / `ascendDeadZoneWatchdogArena` 的方法体**从不执行**
  ——方法第一行 ENTER 埋点在全量跑零输出、其 pathfinder 搜索(按坐标 z=620 grep)零痕迹、
  server 侧 latest.log/debug.log 零痕迹。同一 build 下 `AGENT_GT_ONLY` 单点跑则完整执行
  (noopArena `reachedTop=true ctxAllocated=0`,watchdog 全断言过,1.8s)。
- **既有测试 `diagonalAscentSpeedArena` 同样被吞**(结果行缺失)= 漏洞先于本会话。
  `bridgeGap`/`parkourAscend`/`wallCollisionProbe`/`bareHandDigCadence` 名字零命中
  (**待坐标级确认**——零日志≠被吞,这些测试可能本来不打名字)。
- **已排除**:env 过滤(运行中 dump 游戏 JVM `/proc/PID/environ`,干净;gradle daemon 也干净)、
  注册缺失(新增测试让总数 129→130)、自定义 runner/mixin(无)、批次计数
  (1+50+50+26+1+1+1=130 全对上)、文件位置(晚段 ledgeOvershoot/bridgeDescend 在跑)。
- **影响**:"全量套件按名对账零新名"的回归门**对被吞测试是瞎的**;被吞名单的历史 GREEN 不可信。
  这是 #49 假绿审计后更深一层假绿(上次"跑了但 rig 坏",这次"根本没跑")。
  = **task#52 自造测试框架的最硬论据**。
- **诊断日志**(scratchpad,`/tmp/claude-0/.../scratchpad/`):
  `gt-b1-1.log`(129 个)、`gt-b1-2.log`(挂死轮)、`gt-b1-2b.log`/`gt-b1-2c.log`(130 个,env 已验)、
  `gt-probe.log`(ENTER 埋点轮,零命中)、`gt-solo-ascend.log`(单点执行证明)。
- **复现/排查方法论**:方法首行 `LOG.info(ENTER)` 埋点 + 按 arena 坐标 grep pathfinder 痕迹 +
  运行中 `tr '\0' '\n' < /proc/<游戏JVM>/environ` 验 env。修复后**必须用 ENTER 埋点法复验** ascend 双 arena 真执行。

### 5. 附带发现:underwaterBase 挂死在**新鲜世界**也复现
- 本会话一次(gt-b1-2.log,冻在 underwater 家族 in-flight A*,日志 10 分钟零推进)。
- 修正旧定性"只有被杀 run 污染世界才挂"。解法不变:按显式 PID 杀 →
  `rm -rf neoforge/run-gametest/world` → 重跑即清。
- 识别游戏 JVM:`ls -l /proc/<pid>/fd | grep run-gametest`(勿碰 fabric/run 的 live bot 客户端)。

### 6. 记账(三账已同步)
- 记忆:`project_engine_executor_permove_drive_not_separable_54.md`(终裁+进度)、
  `reference_gametest_suite_silent_test_omission.md`(新,#85)、MEMORY.md 索引。
- 任务:**#84**(A″ 相位化)、**#85**(套件完整性)已立案;#54/#82 仍 in_progress。
- 提交链:`f713ac7`(Unit1,上会话)→ `3d22ad7`(B1-1)→ `ffd8a42`(B1-2)→ `39c13f2`(docs)。

---

## 二、未完成待办(按优先级)

| # | 事项 | 状态/前置 |
|---|---|---|
| **task#85** | GameTest 吞测试漏洞根因+修复(或并入 #52 框架重构)。方向:vanilla/NeoForge GameTestServer 批次调度(结构 spawn 静默失败?beforeBatch 异常吞?)。修复后 ENTER 埋点复验 ascend 双 arena | **P0,user 即将亲自做框架重构** |
| **task#52** | 自造测试框架(双 loader+客户端路径+确定性隔离)——#85 与 #48(共享 FakePlayer 彩票)都是论据 | 待 user brainstorm |
| **B1-3** | live/replay 埋藏 bot A/B(真裁判,开 live-screen-watch)→ `walkerAscendMovement` default-ON 翻转。**task#82 在 live 验证前不收案** | 翻转前置 #83+#85 |
| **task#83** | 刷新 baseline-flags.json(predates 07-04 flip)——B1-3 翻转的硬前置 | P0 |
| **task#84** | A″ tickInner 原地相位化(纯可读性,与 task#82 解耦) | 已立案,另议 |
| **合 master** | `feature/executor-permove-ascend`(4 commits)在 B1-3 live 验证后 FF 合 | 暂缓 |
| task#77/#78 | gap#73 autoFight 可达性 / gap#74 反射门语义三盲区 | pending |
| 生存推进 | Stage 2 食物线 / Stage 4 铁(#19/#21),死点装备回收;bot 空背包 idle 于 (-4,75,-11) | 被 task#82 链阻塞 |
| B1 残余 | stairUpBreak/diagUp 的 BREAK 相语义(spec §4.2,机器暂不拥有 BREAK,legacy dig 兜着);spec §"machine owns actuation" 条款按 B1 终裁改写 | 低优 |

---

## 三、恢复时的关键状态

- **世界/bot**:integrated 单机 SurvivalTest,`/tick freeze` 冻结中(coding 模式);live bot 客户端
  JVM 带 `-Dworlddriver.mcpPort=39800`,**勿杀**。恢复 live 实验前解冻 + 开 live-screen-watch。
- **git**:分支 `feature/executor-permove-ascend` tip `39c13f2`;master 在 `f904070`(gap#81)。
  stash 里有被否决的 Unit2 重实现("unit2-implementer-UNFAITHFUL-drive-reimpl",仅参考勿 apply)。
- **验证纪律(本会话再校准)**:
  - 全量套件按名对账仍是门,但**加一条**:关键新 arena 必须先证"真的执行了"(ENTER 埋点/坐标 grep),
    否则全量绿可能是"没跑"的假绿(#85)。
  - 已知彩票失败名:`entityLeashRepath`(确定性 RED)、`serverAvatarGearScopeProbe`、
    `selfShaftDigUp`(跨 run 漂移,本会话实测两轮 FAIL 一轮 PASS)、`descentYaw`、`vineOverWater`(optional)。
  - 挂死处置:PID 杀 + 删 world + 重跑;新鲜世界也可能挂(见上)。

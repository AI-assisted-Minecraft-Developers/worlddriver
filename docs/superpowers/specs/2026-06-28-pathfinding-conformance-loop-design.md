# Pathfinding Conformance Loop — 设计文档

> 机制驱动的"发现→修复"闭环:给寻路系统装上它一直缺的评估机制,
> 让 planner/executor 两个世界模型的发散被**自动发现**、被**全集接受门**守着修复。

- 日期:2026-06-28
- 状态:设计已与用户逐节确认,待用户审阅 spec → 转 writing-plans
- 关联记忆:`feedback_live_replay_is_truth_arena_is_derived`、`reference_steep_mountain_limit_cycle_revisit_detection`、`reference_water_churn_is_executor_not_planner`、`reference_replay_faithful_reproduction_fix`

---

## 1. 问题陈述(为什么需要这个)

系统里有**两个独立的世界模型**:

- **Planner 的模型**:A* 在一套 Move 代价模型上搜索(`canStandAt`、各 Move 生成器/代价)。它判断"什么可走"。
- **Executor(Walker)的真实能力**:bot 逐 tick 物理上能做什么(跳跃高度、浮力、对齐、落点)。

这两者**会发散**:Planner 发出 executor 物理上实现不了的 move。已观察到的例子:

- 爬不上 +2 山坡(Move 生成器允许 +2 上升,executor 只能跳 +1)
- 上不了 +1 水岸(planner 认为可站,浮力 bot 蹬不上去)
- 尝试爬悬空藤蔓(vine-climb Move 没校验藤蔓锚定/可达)

**核心缺口不是某个 bug,而是缺少评估机制**:这些发散此前只能靠人盯画面看 bot churn 才发现——慢、手动、非确定。
GameTest arena 是同义反复(断言"按 planner 代价模型路径存在",planner 在和自己对账),**从不检验 executor 是否真能走完**。

本设计补上这个评估机制,并用它纪律化地推进寻路丝滑(标准任务 #47)。

## 2. 故障两分类(决定修法)

| 类 | 含义 | 典型 | 修法 |
|----|------|------|------|
| **A. 不可实现 move** | planner 发出 executor 物理走不了的 move | +2坡 / +1浮力岸 / 悬空藤 | 收紧 planner 谓词(Move 生成 / canStandAt) |
| **B. 执行器脆弱** | 路径原则上可实现,但 executor 在其上 churn,且 approach-dependent | -815 陡对角楼梯 churn | 硬化 executor(recovery 重基),少数需 planner 层放行 |

判别器(三件套,既有):`observe.map`(真几何)+ `mc.debug.plan`(planner 判别)+ walk-keys/telemetry(executor 判别)。

## 3. 四个已确认的设计支点(用户选定)

1. **目标**:系统化"发现→修复"闭环,机制驱动,丝滑到达为终目的(非纯建工具、非架构重构)。
2. **发现单元**:**分层** — Layer 1 per-Move conformance(治 A 类)+ Layer 2 journey replay-corpus(治 B 类 + 回归)。
3. **接受门**:**全集净正 + 零回归** — 修复只有在"和当前已接受 flag 组合、跑全 corpus"时聚合 maxStuck 下降 **AND** 任一单归档不超容差回归,才接受并 flip default-ON。直接防 over-fit + flag 交互。
4. **完成判据**:量化门(任一点 maxStuck < ~120 ≈ 6s / per-Move 场景走完 = pass)驱日常迭代;**最终用 #47 协议收尾**(3 随机起终点 × 3 replay = 9 clean + live video 零卡点)。

## 4. 架构总览

```
                 ┌─── Layer 1: per-Move conformance ───┐   ← 治 A 类(planner 谓词 bug)
  发现 ──────────┤                                      │     +2坡/+1岸/悬空藤 全在这层暴露
                 └─── Layer 2: journey replay-corpus ───┘   ← 治 B 类(executor churn)+ 回归
                          │
  排序 ── 按 journey-corpus 出现频率 × maxStuck 严重度
                          │
  定根因 ── 三件套判别器 → 判 A 还是 B(修法不同)
                          │
  修复 ── default-OFF flag,isolation 实现
                          │
  接受门 ── 与已接受 flag 组合,跑【全 corpus】:聚合↓ AND 单归档不超容差回归
            ├─ 通过 → flip default-ON + commit
            └─ 失败 → 打回(over-fit/flag交互被挡)
                          │
  收敛 ── 全 corpus 每归档 maxStuck<~120 且 per-Move 全 pass
                          │
  终验 ── #47: 3随机×3replay = 9 clean + video 零卡点
```

## 5. Layer 1 — per-Move conformance suite

**做什么**:对 planner 能发的 ~32 种 Move(Walk/StepUp/StepUp2/DiagonalAscend/PillarUp/ClimbUp[vine]/Parkour*/
SwimAshoreBreak/SwimBankClimbBreak/SwimUp/Fall/StepDown…),每种生成最小场景,断言 executor **真正走完**(不只是 planner 认为可走)。

**关键纪律 — 场景必须从真实 replay envelope 切,绝不手搭 arena**。手搭 arena 编码"我假设的几何",sim≠client,
是本项目反复栽的坑(GameTest 绿但 live 破)。做法:扫已有 replay 归档,定位每种 Move 实际出现的 tick,以那个**真实 envelope** 为种子切最小可复现片段。复用已验证的忠实复现路径(blockstate + NBT + 流体全保真,见 `reference_replay_faithful_reproduction_fix`)。

**多起始状态**:同一 Move 从多种起始状态测(grounded / 浮力漂浮 / 落点错位)——"+1 岸"grounded 能上、浮力 bot 上不去,正是起始状态决定成败。每个 **(Move × 起始状态)** 是一个独立 conformance case。

**产出**:发散表 —— 哪些 (Move, 起始状态) executor 走不完 = planner 谓词比真实能力宽松的确切清单。

## 6. Layer 2 — journey replay-corpus(接受门的裁判台)

**做什么**:一组精选的多样真实归档,确定性 `mc.debug.replay{restoreBlocks:true}`,自动算每归档 maxStuck,
产出 lever 矩阵。接受门据此裁决。

**corpus 多样性是门的生命线**:本 session 只用 2-3 归档 → 在 0005/0006 调优却回归 0004(over-fit,实测 apw-stack 在
0004 上 579→1935 冻)。corpus 必须覆盖各故障类型:深水穿越 / 水岸 climb-out / 干地陡坡 / 树冠 / 峡谷 pinch。**目标 ~10-15 归档**。
corpus 不够多样,接受门就是假门。

**已有资产(种子)**:`scripts/replay_regression_track.sh` + `config/agent_driver/replays/REGRESSION.md` +
一批 `-815/-870` 归档。需补齐多样性。

**实测 lever 矩阵(说明门为何必要)**:

| 归档 | OFF | apw-stack | horizon=128 |
|------|-----|-----------|-------------|
| 0004 | 579 | **1935 冻 ✗** | 822 |
| 0005 | 840 | 649 | 1438 |
| 0006 | 1814 | 829 | 1664 |

→ 没有单一 flag 组合全赢;apw 在 0004 灾难回归。这正是"全集净正零回归"门要挡的。

## 7. 工作流(一轮迭代)

1. **发现**:跑 Layer 1 全表 + Layer 2 全 corpus → 发散清单 + lever 矩阵快照。
2. **排序**:按 `corpus 出现频率 × maxStuck 严重度` 排,频繁且严重的先修。
3. **定根因**:三件套判别器定层,判 A 还是 B。
4. **修复**:default-OFF flag(5-place wiring:BotConfig 字段 + SettingsCommand setter + snapshot + BotTools .prop + Walker gate),isolation 实现。
5. **接受门**:与已接受 flag 组合,跑全 corpus + 相关 per-Move case。聚合 maxStuck 下降 **AND** 任一单归档容差内
   (建议 ≤ +10% 且不跨越 silky 阈值)→ flip default-ON + commit;否则打回,回 3。
6. **收敛**:全 corpus 每归档 maxStuck < ~120 且 per-Move 全 pass → 进终验。
7. **终验(#47)**:3 随机起终点 × 各 3 replay = 9 clean + live video 零卡点。过 → 达成;不过 → 暴露的新地形归档进 corpus,回 1。

**环境 runbook**(已知坑固化,保证每轮可重复、结论不被噪声污染):client GL hang(需系统重启)、stray gradle 持锁
(kill GradleWrapper JVM)、水中禁 relaunch(先 tp 干地)、menu-nav 坐标、Xvfb :99 / MCP 39800。

## 8. 工具与产出(单一职责、可独立运行)

| 产出 | 状态 | 职责(输入→输出) |
|------|------|------------------|
| **pmcs**(per-Move conformance scanner) | 新建 | 归档 → 每 Move 真实 envelope 种子 + 生成 case + 批量跑 → 发散表 |
| **replay_regression_track.sh**(扩展) | 扩展已有 | flag 组合 → 跑全 corpus → lever 矩阵 + 自动判净正/零回归 |
| **REGRESSION.md**(活文档) | 扩展已有 | corpus 清单(每归档覆盖哪类故障)+ 已接受 flag stack + 发散清单 + lever 历史 |
| **corpus 目录** | 补齐 | ~10-15 多样归档 |
| **runbook** | 新建 | relaunch/menu-nav/环境坑/三件套判别器用法 |

pmcs 吃归档吐发散表,门 runner 吃 flag 组合吐通过/打回,互不耦合。

## 9. 范围 / 非目标 / 风险

**范围内**:双层校验器 + 接受门 + 工作流 + 工具,用于推进 #47 丝滑。

**非目标(YAGNI)**:
- 不统一两个世界模型(executor 能力是动态物理,压不成静态谓词;机制是检测发散,非架构消灭)。
- 不搭 CI 基础设施(先本地可跑)。
- 不顺手重构 Walker 巨兽(除非某修复正好需要)。

**诚实风险**:
1. **class B 修复本质很难**:-815 churn approach-dependent,无单一 flag 全赢;某些点真解可能是更大的 executor 重写甚至
   planner 层改动。机制让这个判断**有据可依**,但**不消除难度**。
2. **corpus 多样性不足 = 假门**:门只和 corpus 一样好;补齐多样性是持续工作。
3. **per-Move case 真实性**:切片若没忠实保留 blockstate/NBT/流体,case 会撒谎;pmcs 必须复用已验证的忠实复现路径。

## 10. 成功标准

- **机制层**:pmcs 能列出发散表;门 runner 能对任意 flag 组合自动判净正/零回归;corpus ≥ 10 且覆盖各故障类型。
- **目标层(#47)**:全 corpus 每归档 maxStuck < ~120,per-Move 全 pass,且 3 随机 × 3 replay = 9 clean + video 零卡点。
- **过程层**:每个被接受的修复都有 corpus 全集证据(非 2-3 归档 over-fit),default-ON + commit。

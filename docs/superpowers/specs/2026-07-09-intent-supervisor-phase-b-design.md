# Phase B — IntentSupervisor 闭环 (design spec, DRAFT for review)

> Parent: `2026-07-04-llm-navigation-intent-layer-design.md` §5 (sketch)。本 spec 把
> 草图展开成可切块实施的设计。Status: **DRAFT — 切法/验收待用户拍板**。

## 1. 问题(证据驱动)

A0–A5 之后意图是可重解算的数据,但闭环缺失:引擎自愈失败时 LLM 毫无感知,只能
靠超时或人观察。已实锤的死亡/瘫痪类:

- **到站溺水 (B-1)**: XZ goal 落湖心 → ARRIVED → driver 依纪律转 passive(不自动上浮,
  [[feedback_driver_passive_when_idle]])→ survival bot 沉底溺死。**2026-07-07 live 再次
  实锤**(Mountains §94 认证旅程终点,Player137 drowned)。
- **致命漫游 (B-2)**: best-effort/escape 段把 bot 带进致命地形(悬崖边 wander、
  levitation 到期坠亡=A5 实验 bot 死亡;descentYawArena 07-02..07-07 的虚空坠落
  是同类的 arena 复刻——冲出可走区后无人叫停,anti-stuck 在空中白烧)。
- **静默瘫痪 (B-3)**: churn/limit-cycle 被 deadlock-breaker 部分接住,但 charge 封顶
  推不动的病态远-墙-goal(TODO #6 遗留)只会慢速原地磨,LLM 不知道该换策略。
- **anti-spin 假 ARRIVED (B-4)**: best-effort 终止仍返回 ARRIVED(TODO Task#35 ⑤),
  上游把失败当成功。

共性:**引擎知道自己不健康(计数器都在),但没有向上汇报的通道;或者更糟——
汇报了错误结论(假 ARRIVED)**。

## 2. 设计总览

```
IntentSupervisorChain (priority()==0, 永不夺 slot; client+server 双端)
  ├─ 每 tick 读现成健康信号(零新探针):
  │    Walker: churnEscapes / noStepProgressTicks / stuckTicks(monotonic) /
  │            lastStats(expanded, goalReached, pathLen) / step终态
  │    IntentProcess: terminator 状态 / target delta / constraint margin
  │    Avatar/Player: y 速度、inWater/air supply、fall distance、HP delta
  │    ProcessScheduler: lastPriorities() 历史(reflex 饱和检测)
  ├─ 映射到 scenario-agnostic EscalationReason:
  │    CHURN_NET_ZERO / TARGET_UNREACHABLE / REFLEX_SATURATION /
  │    CONSTRAINT_MARGIN / CAPABILITY_EXHAUSTED / ARRIVAL_HAZARD / LETHAL_TRAJECTORY
  ├─ latch + 冷却(引擎自愈先跑;同 reason 冷却期内不重复 emit)
  └─ emitExternal("intent.escalation", pos, snapshot-json)
       └─ LLM 收 push → amend(运行中意图热改) 或 cancel+新意图 或 Skill 沙箱
```

不新增执行器行为(supervisor 只观察+汇报);唯一例外是 §4 的两个救命反射,
它们是**独立的 reflex**(走既有 reflex 通道),不属于 supervisor。

## 3. EscalationReason 与判据(全部复用现有计数器)

| reason | 判据(草案,阈值待 replay 校准) | 已有信号 |
|---|---|---|
| CHURN_NET_ZERO | 时间窗净位移 < 8 格且 charge 已 escalate 到顶 | #6 deadlock-breaker 的 CHURN_WINDOW 机制 |
| TARGET_UNREACHABLE | 连续 N 次搜索 goalReached=false 且 best-effort 段 gain 递减 | lastStats + chooseSegment |
| REFLEX_SATURATION | 窗口内 reflex slot 占用比 > 阈值(如 50%) | lastPriorities() |
| CONSTRAINT_MARGIN | leash/yFloor 等 hard constraint 连续贴边(margin < 1 格) | SearchProfile constraints |
| CAPABILITY_EXHAUSTED | 需要被 gate 掉的能力才可达(如 forbidDig 下唯一路径要挖) | Search 的 prune 统计(需加一个计数器,唯一新增) |
| ARRIVAL_HAZARD | 终止判定成立瞬间终点 cell 危险(水中非 dive / 岩浆邻格 / 空中) | isInWater/isHazard + intent optIn |
| LETHAL_TRAJECTORY | 非规划坠落 fallDistance > survivable 或 y < 可走区下界 | fallDistance + path envelope |

**B-4 假 ARRIVED 归位**:anti-spin/best-effort 终止改为 FAILED + TARGET_UNREACHABLE
escalation(独立小刀,先行,不依赖 supervisor 落地)。

## 4. 两个救命反射(supervisor 之外,走 reflex 通道)

纪律约束:[[feedback_driver_passive_when_idle]] 仍然成立——idle 不主动移动。
但"到站溺死/致命坠落"是**生存反射**级别(同 AntiSuffocate 先例:默认 ON、
窄触发、零误报),不是 driver 主动行为:

- **ArrivalDrownGuard**: 非 DIVE 意图 ARRIVED 且 foot in water 且 survival →
  (a) 不转 passive,改发 ARRIVAL_HAZARD escalation + 保持 swimUp 顶水面(只顶水面,
  不上岸不移动 XZ——潜水场景由 DIVE optIn 豁免,与 A5 的 allowsOptIn(DIVE) 字面
  门一致);LLM 决定上岸还是继续。air supply < 50% 才 engage(已在水面漂着不触发)。
- **LethalFallBrake**: 已有 lethalEdgeBrake/41刀 覆盖行走;补"非规划腾空"
  (levitation 到期/被击飞/走出 envelope):检测到 fallDistance 将致命且手上有水桶
  → allowWaterBucketFall 路径已存在,复用;没有则只能 escalation(没有魔法)。

开放决策 → §7 Q2/Q3。

## 5. amend 动词(热改运行中意图)

`mc.bot.amend {leash?, avoid?, preferY?, dive?, capability?, goal?}`:
- 允许改 SearchProfile 的 bias/constraints/capability 与 goal(Intent 是不可变值 →
  amend = 构造新 Intent 换入 IntentProcess,下个 20-tick 重解算周期自然生效
  ——A3a 的重解算机制免费吃到)。
- 不允许改 kind(goto→mine 之类 = cancel+新 process)。
- 返回 {applied, effectiveIntent} 快照;RPC 先行(MCP schema 冻结问题,老规矩)。

## 6. escalation payload(自带"为什么")

```json
{"reason":"CHURN_NET_ZERO", "intent":{kind,goal,constraints…},
 "pose":{pos,yaw,inWater,hp,air}, "threats":[…mc.observe.threats 截断…],
 "targetDelta":{dist,dy}, "recoveryTried":{churnEscapes,burst,repaths},
 "suggest":["amend leash=32","cancel+escape","skill:bridge-across"]}
```
`suggest` = 引擎侧廉价启发式(B backlog "engine-side escalation suggestions"),
LLM 可无视。

## 7. 开放决策(待拍板)

- **Q1 切块**: 建议 B0=假ARRIVED归位(小,独立可验) → B1=supervisor+2个reason
  (CHURN/ARRIVAL_HAZARD)+escalation通道 → B2=amend → B3=其余reason+suggest。
  每块 replay/live A/B 验收(不认 arena-green)。
- **Q2 ArrivalDrownGuard 默认值**: 建议默认 ON(生存反射先例);dive intent 字面豁免。
  反对意见:任何 idle 自主动作都违反 passive 纪律 → 若否决,B-1 只能靠 escalation
  提醒 LLM,死亡窗口 ~15s(air supply),LLM 必须在线。
- **Q3 LethalFallBrake 范围**: 只 escalation(零行为)vs 复用水桶反射。建议先只
  escalation(levitation actuator 是 A5 已归档的 actuator backlog,不在 B 混做)。
- **Q4 阈值校准数据源**: 用 pathArchive 存量 replay 集(churn 病例库 r51/r60/r63…)
  离线回放标定,避免 live 烧轮次。

## 8. 测试策略

- supervisor 判据 = 纯函数(信号快照 → reason) → 直接 JUnit 式 gametest(同
  clientChatLogSemantics 先例,无 avatar);
- escalation 通道 = 49_events.js 式脚本(emit→wait.event 闭环);
- ArrivalDrownGuard = 水面 arena + live A/B(OFF 溺死 / ON 顶水面 + escalation 收到);
- 假 ARRIVED = 现成 anti-spin 复现路径改断言。
- LIVE 判据优先([[feedback_live_replay_is_truth_arena_is_derived]])。

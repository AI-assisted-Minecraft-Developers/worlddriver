# 调度器语义层重构 Phase 1(task#54 Phase 1)设计

状态:待 user 审核。依据 gap#68 十一腿证据册(task#67)+ 两次代码勘察(2026-07-14,file:line 已核对现状)。

## 1. 背景:11 条腿 → 5 个结构根因

生存线 14 次死亡与多起死锁/假成功,证据收敛为 5 个根因(括号内为证据腿编号):

| # | 根因 | 证据腿 | 关键代码事实 |
|---|------|--------|--------------|
| R1 | **反射链的 episode 状态无生命周期管理**:process-less 反射(BunkerChain)把状态藏在内部 anchor,cancel 结构性够不到,死亡不清 | ⑦⑧③ | `BunkerChain` 无 BotProcess,`a.sealed` 每 tick 重竞价 300;`cancel`/`cancelAllProcesses` 只重置 combatChain+userTask 两目标(`BotApiImpl.java:502-519,1020-1027`);死亡钩子仅 `detectDeath→cancelAllProcesses`(`BotApiImpl.java:812`),backfillTracker/RetreatChain.retreating/BunkerAnchor 全部跨死亡存活 |
| R2 | **verb 返回值把"启动确认"当"完成"卖,Walker 三个 best-effort 放弃出口报 SUCCESS** | ⑤⑩ | `awaitable` 只轮询 `slot.active`(`AgentApi.java:690-693`);`IntentProcess.tick` 里 ARRIVED 不写任何错误(`IntentProcess.java:87-93`);Walker 五个 ARRIVED 出口中 goal-snap(≤6格,`Walker.java:337-378`)/frontier-hold(`5257-5271`)/anti-spin water(`1491-1516`)三个是放弃却报 SUCCESS;bunker 甚至不 awaitable,`ok:true,depth:N` 纯启动 ack(`BotApiImpl.java:326-336`),BunkerProcess 首 tick 可零动作退出(`BunkerProcess.java:128-131`) |
| R3 | **反射进入门重derive扫描、不消费权威伤害事件** | ①(重定性) | Retreat 竞价 ≥100 本会赢 user 50——盲区不是优先级压制,而是 `shouldEnter` 门自身返回 0:只认"HP≤6∧近战12格内"或"attackedMe∧RangedAttackMob"(`RetreatChain.java:84-93,155-159`),威胁来自 `ClientThreatScanner` 重derive `getLastDamageSource`,hurt 事件的 attackerId/attackerDistance 无消费者 |
| R4 | **战斗与夜间自保缺态势门** | ②④⑨ | CombatChain/CombatProcess 全程零 own-HP 读取(`CombatChain.java:92-100`);DuskSecureChain=40<user=50 by design(`Priorities.java:34`,类注释"BELOW user task"),夜间暴露也永远抢不过任意 user 任务;且触发条件苛刻(50-tick idle debounce+无威胁12格) |
| R5 | **idle 态 ambient 行为抢占外部输入** | ⑪ | `autoTool && c==null` 时 `AutoTool.tick` 每 tick 按准星方块重选最优槽(`BotApiImpl.java:922-924`,`AutoTool.java:48-50`),外部 setHotbarSlot 无法存活一个 tick |

非根因澄清:⑥(pickup 幻报)是事件层问题,不在本 spec(挂 task#24 滚动);④ 的"间歇性"由 R4 的苛刻触发条件+R1 的 anchor 残留共同解释。

## 2. 目标 / 非目标

**目标(Phase 1)**:
1. 每条 Chain 的 episode 状态可枚举、可取消、死亡全清。
2. verb 完成语义诚实:goalReached/endReason/finalDistance 单源上报,启动 ack 与完成态不再混淆。
3. 反射进入门读单源 `ThreatContext`(消费伤害归因事件),门是纯函数可单测(#65 模式)。
4. combat 脆血门 + duskSecure 夜间升压,修正 R4 两处态势盲。
5. AutoTool 尊重外部手动选槽(宽限期)。

**非目标(留给后续 Phase)**:per-move 执行器状态机(#51/#36/#53 残余)、渐进式寻路重构、#52 完整自造测试框架(本 spec 只并入 Phase 1 所需的验证底座切片)、事件层幻报(⑥)。

## 3. 设计

### 3.1 Episode 生命周期(R1)

`Chain` 接口新增三个 default 方法:

```java
/** 非空=本链当前持有一段有状态的 episode(名字+起始时刻+阶段),null=无状态待机 */
default EpisodeInfo episode() { return null; }
/** 外部取消:清掉本链全部内部状态(anchor/latch/process),立即停止竞价。幂等 */
default void cancelEpisode(String reason) {}
/** 死亡时由调度器统一调用。默认实现=cancelEpisode("player-death") */
default void onDeath() { cancelEpisode("player-death"); }
```

- `BunkerChain`:`episode()` 暴露 anchor 阶段(digging/sealed+锚点坐标),`cancelEpisode` = `a.reset()`。**修复关键**:displaced/respawn 检测从 `tick()`(只在赢标时跑)移入 `priority()` 前置检查——不赢标也能自愈。
- `RetreatChain`:`cancelEpisode` = 走既有 `idle()`(清 `retreating` 闩+`state.retreat.reset()`)。
- `DuskSecureChain`/`CombatChain`/`UserTaskChain`:映射到既有 process 置空/standDown 路径。
- **cancel verb 扩展**:`mc.bot.cancel {process:"bunker-reflex"|"retreat"|"dusk"|…}` 逐链可达;`cancel all` = combat standDown + userTask cancel + **每条 chain `cancelEpisode("user-cancel")`**。不再存在"唯 setting 翻 flag 可解"的死锁。
- **死亡全表清零**:`cancelAllProcesses("player-death")` 扩为:既有两目标 + 全链 `onDeath()` + `backfillTracker.clear()`。另加**重生宽限**:respawn 后 `respawnGraceTicks`(默认 60t,可配)内 autoFight 不竞价、backfill 不自启(panic/dodge/retreat 反射不受限)——治跨死亡追杀与孤儿挖掘。
- **status 观测**:`chains` 节点逐链输出 `{priority, episode:{name,phase,ageTicks}}`;`userTaskSuspended` 保留但补充 `suspendedBy=activeChain`。事件新增 `chain.episode.start/end{chain,reason}`。

### 3.2 终态诚实化(R2)

- `Walker.terminal(...)` 已带 `PathTrace.Outcome`;新增结构 `TerminalReport {Step step, Outcome outcome, String reason, double finalDistSq, boolean goalReached}`,五个 ARRIVED 出口逐一标注真实 reason:`arrived | goal-snapped(dist) | frontier-giveup | churn-giveup | best-effort-consumed`。**行为不变,只改上报**(避免 Phase 1 引入寻路回归)。
- `IntentProcess.tick`:ARRIVED 时把 `TerminalReport` 写进 slot(新字段 `goalReached/endReason/finalDist`),不再只有 FAILED 才留痕。
- `awaitable`(`AgentApi.java:663`):completed 折叠时携带上述字段;`completed:true ∧ goalReached:false` = "进程结束但未达目标",agent 侧一眼可判。向后兼容:旧字段全保留。
- **bunker 入列 awaitable**(与 goto 同款),返回补 `acted:boolean`(是否动过世界)+ `phase` 终值;`ok:true` 语义统一文档化为"已受理"。首 tick 零动作退出的路径(水位不足等)必须写 `lastError`。
- goal 类型修正:`direction:up/down` 造出的 YLevel goal 标记 `requiresProgress`——best-effort 放弃出口对带此标记的 goal 一律 `goalReached:false`(治"秒报完成")。

### 3.3 ThreatContext 单源 + 反射进入门(R3)

新增每 tick 组装的不可变 `ThreatContext`:

```java
record ThreatContext(
    float hp, boolean engaged,            // combat 交战中
    HurtEvent lastHurt,                   // 来自 #55 单源:attackerId/type/distance/ageTicks
    ThreatScan scan,                      // 既有扫描(近战距离/ranged aiming)
    long dayTime, boolean exposedAtNight, boolean sheltered)
```

- 组装点:`BotApiImpl.clientTick` 每 tick 一次,所有 chain 的 `priority()` 收同一实例(消灭各链自行扫描的时序差)。
- **RetreatChain 进入门改造**(纯静态函数 `RetreatGate.shouldEnter(ctx, cfg)`):
  - 保留既有两支:低 HP∧近战近身、ranged aiming(#65)。
  - **新增事件支**:`lastHurt.ageTicks ≤ hurtEntryWindow(默认40t) ∧ attackerDistance ≤ 24` → 无条件进闩(不再要求 RangedAttackMob 类型、不依赖扫描重derive)。被打了就是被打了——伤害归因单源直接消费(#55 教训)。
  - 近战 HP 阈值从固定 6 改为 `max(retreatHpThreshold, maxHealth*0.4)`,裸装期不再等到 3 颗心才反应。
- **release 门**不变(跑脱或回血),避免 flee 抖振。
- 门函数全部纯静态可单测(输入 ctx+cfg,输出 bool/priority),矩阵用例直接写(#65 的 9-case 模式扩展)。

### 3.4 combat 脆血门 + duskSecure 升压(R4)

- **CombatChain**:`bid()` 增加脆血检查——`hp ≤ combatFrailThreshold(默认6) ∧ !cornered` 时:autoFight 支路直接 0(不进新架),显式 intent 支路降级为发 `combat.frail` 事件并 standDown(LLM 有意送死需显式 `force:true`)。CombatProcess 内已交战时同门每 tick 检查,触发即让位给 Retreat(其竞价 ≥100 自然接管)。
- **DuskSecureChain 升压**:priority 分两档——idle 档保持 `IDLE_SECURE=40`(白天/已庇护,永不抢 user);**升压档 `DUSK_URGENT=90`**(介于 combat 60 与 retreat 100 之间):`exposedAtNight ∧ !sheltered` 即升,**可抢占 user 任务**(治⑨倒挂——夜里暴露时自保>赶路,与 retreat>user 同一哲学)。被 duskSecure 抢占的 user 任务不取消、挂起,SEALED 后天亮 dusk 让位自动续跑。触发条件同步放宽:去掉"无威胁12格"前置(有威胁更该缩),idle debounce 50t→20t。
- 兼容:两个新阈值+升压开关全部进 BotConfig(properties 持久化),默认值即上述;`autoSecureAtDusk` 语义不变(总开关)。

### 3.5 AutoTool 手动宽限(R5)

外部 `setHotbarSlot`(或任何非 AutoTool 的 `inv.selected` 写)后 `manualSlotGraceTicks(默认100t)` 内 AutoTool 不覆写;宽限期内 bot 自己的 process 启动则立即失效(process 自管 hotbar 优先)。实现:AutoTool 记录自己上次写入的 slot,发现 `inv.selected` 与记录不符=外部改动→启动宽限计时。

## 4. 验证底座(#52 切片并入)

分四层,由纯到活(⭐arena 只是回归卫士,live/replay 才是真相):

1. **纯门单测**(JUnit,无 Minecraft):`RetreatGate`/`CombatFrailGate`/`DuskEscalation`/`TerminalReport` 折叠逻辑。矩阵化:engagement×HP×hurt-age×时间带,≥20 case。R3/R4 的主验证层。
2. **GameTest arenas**(既有框架,makeMockPlayer 可受伤 + FakePlayer containerMenu 经验沿用):
   - `chainLifecycleArena`:造 sealed BunkerAnchor+敌对包围 → 断言 `cancel {process:"bunker-reflex"}` 一击清除、竞价归 0;
   - `deathResetArena`:挂起 user 任务+塞 backfill 队列+置 retreat 闩 → mock 死亡钩子 → 断言全表清零+宽限期内 autoFight 不竞价;
   - `terminalHonestyArena`:goal-snap/frontier-giveup 两个放弃出口 → 断言 slot 带 `goalReached:false+endReason`。
   - 已知结构性盲区(异步慢搜/fallback 研磨 gametest 无法复现,#66 教训)不硬测,交 live。
3. **live 受控 rig**(#65 模板扩展):关 dusk+summon skeleton+0.5s 采样器,变体= **goto 行进中**受击(治①的验证盲区:#65 只验证过 idle 态);bunker 后断言**方块级围合**(6 邻实心 ground truth——⚠`hazardSummary.cornered` 语义是"被敌对逼死角"而非围合,day60 live 实测 SEALED 时 cornered=false,不可用作围合断言;3.2 的 bunker awaitable 返回应新增 `enclosed:boolean` 方块级字段,治⑩)。
4. **live 回归信标**:SurvivalTest 世界夜间例行(手动 bunker→改后 autoBunker 重开),死亡数/夜为核心指标;replay 存档 A/B(#63 模式)用于 Walker 上报改动。

**验收对账表**:11 条腿逐条映射到上述某层的具体断言(实现计划里落成 checklist)。

## 5. 迁移与风险

- 顺序:3.2(纯上报,零行为变化,先行)→ 3.1(生命周期,cancel/death 语义变化)→ 3.3/3.4(反射行为变化,live A/B 逐项)→ 3.5(独立小件,随时)。
- 风险最高=3.4 duskSecure 升压(新抢占路径):以 config 开关护栏,live 首夜灰度(升压档先只记日志不抢占,`duskUrgentDryRun` 一晚)。
- 全程 flag 兼容:任何新行为都可退回旧语义;`#280` 教训——每个新 flag 落地后裸 RPC A/B 验证 applied。

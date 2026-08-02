# Scheduler Semantics Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 gap#68 家族的 5 个结构根因(spec `docs/superpowers/specs/2026-07-14-scheduler-semantics-phase1-design.md`):verb 终态诚实化、chain episode 生命周期(cancel/死亡全清)、反射进入门事件化、combat 脆血门+duskSecure 夜间升压、AutoTool 手动宽限。

**Architecture:** 全部改动在 `common/`(调度器 `bot/scheduler/`、进程 `bot/process/`、报告缝 `api/DriverApi.java`+`bot/BotApiImpl.java`);纯门逻辑抽成静态可测函数,矩阵测试进 `neoforge/.../AgentGameTestServer.java`(#65 先例,AgentGameTestServer.java:3227 起);行为开关全走 `BotConfig` public static volatile 字段(setting 反射自动接线,落地后用 `mc.bot.setting` 的 applied 列表验证)。

**Tech Stack:** Java 21, Minecraft 1.21 multiloader (common/fabric/neoforge), NeoForge GameTest。

## Global Constraints

- 迁移顺序(spec §5):R2 上报(零行为变化)→ R1 生命周期 → R3 反射门 → R4 态势门 → R5 AutoTool。每个 Task 独立提交。
- 新行为一律配 `BotConfig` 开关且默认值按本计划;新增字段名与默认值:`respawnGraceTicks=60`、`combatFrailThreshold=6f`、`duskUrgent=true`、`duskUrgentDryRun=false`、`manualSlotGraceTicks=100`。
- 测试命令:全量 `./gradlew :neoforge:runGameTestServer`(必须 `required tests passed` 无新名失败;⚠️TOTAL 行会掩盖 required 结果,只看 required 行);单测 `AGENT_GT_ONLY=<testName> ./gradlew :neoforge:runGameTestServer`。编译快查 `./gradlew :neoforge:compileJava`。
- 纯门测试模式 = AgentGameTestServer 里静态调用断言(见 3227-3250 行 gap#65 先例),不新建 JUnit 源集。
- 不改 Walker 的行为路径(R2 只加上报);不动 Panic/Dodge 的竞价逻辑。
- 每个 Task 完成即 `git commit`(不 push)。

---

### Task 1: Walker 终态上报(R2a,零行为变化)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java`
- Test: `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java`(新增 `walkerTerminalReportMatrix`)

**Interfaces:**
- Produces(Task 2 消费):`Walker` 三个新 public volatile 字段:`String lastEndReason`(terminal 原因标签)、`boolean lastGoalReached`(终止时 `goal.reached(foot)` 真值)、`double lastFinalDist`(终止时 `goal.estimate(foot)`,启发式距离)。加静态纯函数 `static String classifyArrival(boolean bestEffort, boolean reachedFoot, boolean snapped)`。

- [ ] **Step 1: 写失败的矩阵测试**(在 `AgentGameTestServer.java` 中、gap#65 矩阵测试同一区域追加;注册方式与相邻测试一致)

```java
// gap#68-R2: Walker.classifyArrival 纯函数矩阵 —— ARRIVED 出口必须可区分
// (goal-snapped / frontier-giveup 等由调用点直接传标签,本函数只管三态通用出口)
static void walkerTerminalReportMatrix(java.util.function.BiConsumer<Boolean, String> check) {
    check.accept("arrived".equals(Walker.classifyArrival(false, true,  false)), "full path + reached = arrived");
    check.accept("arrived".equals(Walker.classifyArrival(true,  true,  false)), "best-effort + reached = arrived");
    check.accept("path-consumed".equals(Walker.classifyArrival(false, false, false)), "full path + NOT reached = path-consumed");
    check.accept("best-effort-consumed".equals(Walker.classifyArrival(true, false, false)), "best-effort + NOT reached = best-effort-consumed");
    check.accept("goal-snapped".equals(Walker.classifyArrival(false, true,  true)),  "snapped goal reached = goal-snapped");
}
```

包装成 gametest(模仿 3227 行区域的写法,同 batch;`check` 用 `(ok,msg) -> { if(!ok) throw new GameTestAssertException(msg); }`)。

- [ ] **Step 2: 跑测试确认 RED**

Run: `AGENT_GT_ONLY=walkerTerminalReport ./gradlew :neoforge:runGameTestServer`
Expected: 编译失败 `cannot find symbol: method classifyArrival`(即 RED)。

- [ ] **Step 3: Walker 实现**

3a. 类字段区(`public String lastError;` 附近,Walker.java:251)追加:

```java
/** gap#68-R2 honest terminal report: why the last tick() returned ARRIVED/FAILED,
 *  whether the (possibly snapped-away-from) goal was ACTUALLY reached at the foot,
 *  and the heuristic distance left. Written once at every terminal() call site;
 *  read by IntentProcess to stamp the status slot. Volatile: status threads read. */
public volatile String lastEndReason;
public volatile boolean lastGoalReached;
public volatile double lastFinalDist;
/** True once snapGoalToStandable() rewrote the requested Goal.Block to a nearby
 *  standable cell — arrival then means "arrived NEAR", not "arrived AT". */
private boolean goalSnapped;
```

3b. `snapGoalToStandable`(Walker.java:372-377)在 `this.goal = new Goal.Block(best);` 后加一行 `goalSnapped = true;`。

3c. 新增纯函数 + 上报重载(放 `terminal(...)`(5230)旁):

```java
/** Pure classification of a generic ARRIVED exit (static & matrix-testable). */
static String classifyArrival(boolean bestEffort, boolean reachedFoot, boolean snapped) {
    if (snapped && reachedFoot) return "goal-snapped";
    if (reachedFoot) return "arrived";
    return bestEffort ? "best-effort-consumed" : "path-consumed";
}

/** terminal() + honest report stamp. foot may be null (no player) → conservative false. */
private Step terminalReport(Step s, PathTrace.Outcome outcome, String reason,
                            String endReason, BlockPos foot) {
    lastEndReason = endReason;
    lastGoalReached = foot != null && goal != null && !goalSnapped && goal.reached(foot);
    lastFinalDist = (foot != null && goal != null) ? goal.estimate(foot) : -1;
    return terminal(s, outcome, reason);
}
```

注意:`goalSnapped` 情况下 `goal.reached(foot)` 对"改写后的 goal"为 true,但对原始请求不是——所以 `lastGoalReached` 在 snapped 时恒 false(诚实)。

3d. 五个 ARRIVED 出口逐一改为 `terminalReport`(行号为当前基线,以内容定位为准):

| 位置 | 原 | 改 |
|---|---|---|
| Walker.java:750(正常到达,含 snap 后到达) | `return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);` | `return terminalReport(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null, classifyArrival(pathBestEffort, goal.reached(foot), goalSnapped), foot);` |
| Walker.java:1515(anti-spin water 放弃) | 同上 | `return terminalReport(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null, "churn-giveup", p.blockPosition());` |
| Walker.java:2266(段耗尽) | 同上 | `return terminalReport(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null, classifyArrival(pathBestEffort, goal.reached(foot), goalSnapped), foot);` |
| Walker.java:5270(frontier 放弃) | 同上 | `return terminalReport(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null, "frontier-giveup", p.blockPosition());` |
| FAILED 出口(Walker.java:829-831 预算耗尽等,grep `Step.FAILED` 的全部 terminal 调用) | `terminal(Step.FAILED, ...)` | `terminalReport(Step.FAILED, <原 outcome>, <原 reason>, "failed:"+<原 reason>, p.blockPosition())`(foot 不可得处传 null) |

每处的 `foot`/`p` 用该作用域已有变量;若该出口作用域没有 foot,用 `a.player() != null ? a.player().blockPosition() : null`。同文件 grep `terminal(Step.ARRIVED` 确认除上述外无遗漏(有则同样归类:能判 reached 用 classifyArrival,不能判的给专名标签)。

3e. `tick()` 每次 path 重建/新 goal 设置处(`setGoal`)重置 `goalSnapped=false`、`lastEndReason=null`。

- [ ] **Step 4: 跑测试确认 GREEN + 全量无回归**

Run: `AGENT_GT_ONLY=walkerTerminalReport ./gradlew :neoforge:runGameTestServer` → PASS;再全量 `./gradlew :neoforge:runGameTestServer` → required 全绿(彩票名单以既有为准,零新名)。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/movement/Walker.java neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java
git commit -m "feat(walker): honest terminal report (endReason/goalReached/finalDist) at every ARRIVED/FAILED exit (gap#68-R2a)"
```

---

### Task 2: slot 字段 + awaitable 折叠(R2b)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotState.java:76-109`(ProcessSlot)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/process/IntentProcess.java:87-93`
- Modify: `common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java:663-714`(awaitable)

**Interfaces:**
- Consumes: Task 1 的 `walker.lastEndReason/lastGoalReached/lastFinalDist`。
- Produces: `ProcessSlot` 新 public volatile 字段 `Boolean goalReached; String endReason; double finalDist = -1;`(snapshot 键名同名);awaitable 响应顶层新键 `goalReached`(仅 slot 有值时)。

- [ ] **Step 1: ProcessSlot 扩展**(BotState.java:84 `lastError` 后)

```java
/** gap#68-R2: honest terminal verdict of the LAST run. Null/−1 until a run ends.
 *  Kept across reset() (like lastError) so awaitable/wait.condition can read it. */
public volatile Boolean goalReached;
public volatile String endReason;
public volatile double finalDist = -1;
```

`snapshot()`(BotState.java:96 `lastError` 行后)追加:

```java
if (goalReached != null) m.put("goalReached", goalReached);
if (endReason != null) m.put("endReason", endReason);
if (finalDist >= 0) m.put("finalDist", finalDist);
```

`reset()` 不清这三个(与 lastError 同策);但 `IntentProcess.attach`(IntentProcess.java:57 `lastError = null` 处)加 `st.mc_goto.goalReached = null; st.mc_goto.endReason = null; st.mc_goto.finalDist = -1;`(新一轮开跑清旧验尸)。

- [ ] **Step 2: IntentProcess 终止时写 slot**(IntentProcess.java:90-93 改为)

```java
if (s == Walker.Step.WALKING) return false;
if (s == Walker.Step.FAILED) st.mc_goto.lastError = walker.lastError;
st.mc_goto.goalReached = walker.lastGoalReached;
st.mc_goto.endReason = walker.lastEndReason;
st.mc_goto.finalDist = walker.lastFinalDist;
st.mc_goto.reset();
return true;
```

- [ ] **Step 3: awaitable 顶层便捷键**(DriverApi.java:707-712 的 status 折叠处改为)

```java
if (finalStatus != null) {
    Object slotObj = finalStatus.get(slot);
    if (slotObj instanceof Map<?, ?> slotMap) {
        out.put("status", slotObj);
        Object gr = ((Map<String, Object>) slotMap).get("goalReached");
        if (gr != null) out.put("goalReached", gr);
    }
}
```

- [ ] **Step 4: 编译 + 全量回归**

Run: `./gradlew :neoforge:compileJava` → BUILD SUCCESSFUL;`./gradlew :neoforge:runGameTestServer` → required 全绿零新名。(此 Task 是纯管道接线,真值验证在 Task 10 live 协议:goto 一个故意不可达点,断言 `completed:true ∧ goalReached:false ∧ endReason` 非 null。)

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/BotState.java common/src/main/java/net/magicterra/worlddriver/bot/process/IntentProcess.java common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java
git commit -m "feat(status): goalReached/endReason/finalDist on goto slot + awaitable top-level fold (gap#68-R2b)"
```

---

### Task 3: bunker 入列 awaitable + acted/enclosed(R2c)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotState.java`(新增 bunker slot)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/scheduler/UserTaskChain.java:107-126`(slotFor)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/process/BunkerProcess.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java:325-336`(bunker verb)
- Modify: `common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java:317`(路由)

**Interfaces:**
- Produces: status 新增 `bunker` slot(ProcessSlot 标准形+Task 2 新字段);`mc.bot.bunker` 支持 `awaitMs`,响应含 `acted:boolean`(是否动过世界)与 slot 折叠;`goalReached` 语义 = SEALED 且方块级围合。

- [ ] **Step 1: BotState 加 slot**。`BotState` 里与 `mc_goto` 等并列的字段声明处(grep `ProcessSlot mc_goto` 定位)加 `public final ProcessSlot bunker = new ProcessSlot("bunker");`,并在 `snapshot()` 输出各 slot 的同一列表里加同名项(grep 现有 `out.put("smelt"` 样式照抄:`out.put("bunker", bunker.snapshot());`)。

- [ ] **Step 2: UserTaskChain.slotFor**(UserTaskChain.java:124 `default -> null` 之前)加 `case "bunker" -> state.bunker;`(默认分支注释同步删掉 bunker 例句)。

- [ ] **Step 3: BunkerProcess 上报**。
  - 加字段 `private boolean acted;`(任何一次真实 break/place 后置 true——在 `a.breakHold(true)` 启动挖掘的分支与 place 成功分支各置一次;grep `breakHold(true)` 与 place 调用点)。
  - `attach(BotState st)`(若无则新增 override)置 `st.bunker.active = true; st.bunker.goal = "bunker depth=" + depth; st.bunker.startedAtMs = System.currentTimeMillis(); st.bunker.lastError = null; st.bunker.goalReached = null; st.bunker.endReason = null;`。
  - `tick()` 每个 `return true` 出口前统一走私有 `finish(st, w, a, String endReason, String errOrNull)`:

```java
private boolean finish(BotState st, WorldView w, Avatar a, String endReason, String err) {
    st.bunker.endReason = endReason;
    if (err != null) st.bunker.lastError = err;
    boolean sealedNow = phase == Phase.SEALED || phase == Phase.DONE && sealedOk;
    st.bunker.goalReached = sealedNow && enclosed(w, a);
    st.bunker.reset();
    return true;
}

/** Block-level enclosure ground truth (spec §4.3 修正): the 4 horizontal neighbors of
 *  the FOOT cell, the head cell's 4 horizontal neighbors, and the cell above the head
 *  are all solid. hazardSummary.cornered 语义是"被敌对逼死角",不可用作围合断言。 */
private static boolean enclosed(WorldView w, Avatar a) {
    if (a.player() == null) return false;
    BlockPos foot = a.player().blockPosition();
    BlockPos head = foot.above();
    return w.isSolid(foot.north()) && w.isSolid(foot.south()) && w.isSolid(foot.east()) && w.isSolid(foot.west())
        && w.isSolid(head.north()) && w.isSolid(head.south()) && w.isSolid(head.east()) && w.isSolid(head.west())
        && w.isSolid(head.above());
}
```

  - 各 `return true` 出口替换,标签按语境:水位不足首 tick 退出(BunkerProcess.java:128-131)→ `finish(st, w, a, "unsafe-site", "water/hazard at dig site — no action taken")`;挖掘超时(173)→ `finish(..., "dig-timeout", "break timeout (unbreakable below?)")`;不安全(167)→ `finish(..., "unsafe-mid-dig", "hazard opened mid-dig")`;act 超时(219)→ `finish(..., "act-timeout", "seal/carve timeout")`;正常 DONE → `finish(..., phase.name(), null)`。`sealedOk` 若现代码无此概念,以 `phase == Phase.SEALED` 曾达到为准(加 boolean 字段在进入 SEALED 时置 true)。
  - 注意 `tick(Avatar a, ...)` 签名里已有 `st`(BotProcess 接口)——直接用。

- [ ] **Step 4: verb + 路由**。BotApiImpl.java:334 响应加 `"acted"` 无法同步得知(异步)→ 响应保持启动 ack 但注释语义;DriverApi.java:317 改为:

```java
routes.put("mc.bot.bunker",    p -> awaitable(p, "bunker",  requireBot()::bunker));
```

awaitable 会折叠 bunker slot(含 goalReached/endReason)到响应,`completed:true ∧ goalReached:false` = 假成功可判。

- [ ] **Step 5: 编译+全量回归+Commit**

Run: `./gradlew :neoforge:runGameTestServer` → required 全绿零新名。

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/BotState.java common/src/main/java/net/magicterra/worlddriver/bot/scheduler/UserTaskChain.java common/src/main/java/net/magicterra/worlddriver/bot/process/BunkerProcess.java common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java
git commit -m "feat(bunker): awaitable verb + bunker slot + enclosed/acted honest verdict (gap#68-⑩)"
```

---

### Task 4: Chain episode 生命周期 + cancel 逐链可达(R1a)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/scheduler/Chain.java`
- Modify: `BunkerChain.java` / `RetreatChain.java` / `CombatChain.java` / `DuskSecureChain.java` / `UserTaskChain.java`(同目录)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/scheduler/ProcessScheduler.java`(cancelAll helper)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java:502-519`(cancel verb)、`status()`(chains 节点)

**Interfaces:**
- Produces: `Chain` 新 default 方法 `default String episodePhase() { return null; }`(非 null=有状态 episode)与 `default void cancelEpisode(String reason) {}`;`ProcessScheduler.cancelAllEpisodes(String reason)`;`mc.bot.cancel {process:"<chainName>"}` 对 chain 名(`bunker`/`retreat`/`combat`/`duskSecure`)直达;status 新增 `"chains": {name: {"priority": f, "episode": phaseOrNull}}`。

- [ ] **Step 1: 写失败测试**(AgentGameTestServer 静态矩阵区追加;BunkerChain/RetreatChain 可无 mc 构造,cancelEpisode 是纯状态操作)

```java
// gap#68-R1a: 反射链 episode 必须可外部取消(cancel 死锁的结构解)
static void chainEpisodeCancelMatrix(java.util.function.BiConsumer<Boolean, String> check) {
    BunkerChain bc = new BunkerChain();
    // 造一个 sealed episode(⑦ 的死锁形态)。⚠️测试跑在 dedicated GameTest server 上,
    // 不可触碰 Minecraft.getInstance()(client 类不存在)——所以断言走纯状态方法
    // resetEpisodeState(),cancelEpisode 的客户端按键释放部分由 Task 10 腿⑦ live 验。
    bc.anchorForTest().beginIfIdle(0, 64, 0);
    bc.anchorForTest().sealed = true;
    check.accept("SEALED".equals(bc.episodePhase()), "sealed anchor reads as SEALED episode");
    bc.resetEpisodeState();
    check.accept(bc.episodePhase() == null, "resetEpisodeState clears the anchor");
    check.accept(!bc.anchorForTest().active() && !bc.anchorForTest().sealed, "anchor fully reset");
}
```

- [ ] **Step 2: RED**:`AGENT_GT_ONLY=chainEpisodeCancel ./gradlew :neoforge:runGameTestServer` → 编译失败(方法不存在)。

- [ ] **Step 3: 实现**

3a. `Chain.java` 追加两个 default 方法(javadoc 说明:episodePhase 非 null 表示链内残留有状态 episode;cancelEpisode 幂等、必须把链清到"不再竞价"的地步)。

3b. `BunkerChain`:

```java
@Override public String episodePhase() {
    if (a.sealed) return "SEALED";
    return a.active() ? "DIGGING" : null;
}
@Override public void cancelEpisode(String reason) {
    resetEpisodeState();
    // Client-only key release — split from the state reset so the state semantics
    // stay testable on the dedicated GameTest server (no client classes there).
    Minecraft mc = Minecraft.getInstance();
    if (mc != null && mc.options != null) mc.options.keyAttack.setDown(false);
    releaseKeys();
}
/** Pure episode-state reset (server-safe, matrix-testable). */
void resetEpisodeState() { a.reset(); }
/** Test seam (package-private): the anchor, for episode lifecycle matrix tests. */
BunkerAnchor anchorForTest() { return a; }
```

3c. `RetreatChain`:

```java
@Override public String episodePhase() { return retreating ? "FLEEING" : null; }
@Override public void cancelEpisode(String reason) {
    retreating = false;
    process = null;
    if (state.retreat.active) { state.retreat.lastError = reason; state.retreat.reset(); }
    releaseKeys();
}
```

3d. `CombatChain`:

```java
@Override public String episodePhase() { return engaged() ? "ENGAGED" : null; }
@Override public void cancelEpisode(String reason) {
    if (engaged()) { standDown(); state.combat.lastError = reason; }
}
```

3e. `DuskSecureChain`:

```java
@Override public String episodePhase() { return process != null ? "SECURING" : null; }
@Override public void cancelEpisode(String reason) { process = null; idleTicks = 0; releaseKeys(); }
```

3f. `UserTaskChain`:

```java
@Override public String episodePhase() { BotProcess c = process; return c == null ? null : c.kind(); }
@Override public void cancelEpisode(String reason) { cancel(reason); }
```

3g. `ProcessScheduler` 追加:

```java
/** Cancel every chain's internal episode (reflex anchors, latches, held processes).
 *  The structural fix for "cancel can't reach a process-less reflex chain" (gap#68-⑦):
 *  mc.bot.cancel{all} and the player-death hook both call this. Idempotent. */
public void cancelAllEpisodes(String reason) {
    for (Chain c : chains) c.cancelEpisode(reason);
}

/** Find a chain by its name() (for targeted mc.bot.cancel{process:<chainName>}). */
public Chain byName(String name) {
    for (Chain c : chains) if (c.name().equals(name)) return c;
    return null;
}
```

3h. `BotApiImpl.cancel`(502-519)改为:

```java
@Override public Map<String, Object> cancel(Map<String, Object> params) {
    Params p = Params.of(params);
    return onClient(() -> {
        String which = p.get("process") instanceof String s ? s : "all";
        if ("all".equals(which) || "combat".equals(which)) {
            if (combatChain.engaged()) {
                combatChain.standDown();
                state.combat.lastError = "user-cancel";
                state.combat.active = false;
            }
        }
        BotProcess c = userTask.process();
        boolean match = "all".equals(which) || (c != null && which.equals(c.kind()));
        if (match) cancelCurrent("user-cancel");
        // gap#68-⑦: cancel must also reach chain-internal episodes (BunkerChain anchor,
        // retreat latch, dusk process) that hold priority with no user process.
        if ("all".equals(which)) {
            scheduler.cancelAllEpisodes("user-cancel");
            // gap#68-⑫: "cancel all" must also drop the orphan backfill queue — the
            // auto-start at clientTick would otherwise resume placing blocks right
            // after the cancel (live: post-cancel backfill marched HP7→5 into a fall).
            backfillTracker.clear();
        } else {
            Chain byName = scheduler.byName(which);
            if (byName != null) byName.cancelEpisode("user-cancel");
        }
        return Map.of("ok", true, "cancelled", which);
    });
}
```

3i. `BotApiImpl.status()`(499 `clutch` 行前)追加:

```java
Map<String, Object> chains = new LinkedHashMap<>();
Map<String, Float> prios = scheduler.lastPriorities();
for (Chain ch : scheduler.chains()) {
    Map<String, Object> one = new LinkedHashMap<>();
    Float pr = prios.get(ch.name());
    one.put("priority", pr == null ? 0f : pr);
    String ep = ch.episodePhase();
    if (ep != null) one.put("episode", ep);
    chains.put(ch.name(), one);
}
snap.put("chains", chains);
```

(import `net.magicterra.worlddriver.bot.scheduler.Chain`。)

- [ ] **Step 4: GREEN + 全量回归**:solo → PASS;全量 → required 全绿零新名。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/scheduler/ common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java
git commit -m "feat(scheduler): chain episode lifecycle — episodePhase/cancelEpisode, cancel verb reaches every chain, chains in status (gap#68-⑦)"
```

---

### Task 5: 死亡全表清零 + 重生宽限 + BunkerChain priority 前置自愈(R1b)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java:812,922-942,1020-1027`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/scheduler/CombatChain.java:92-100`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/scheduler/BunkerChain.java:44-60`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java`(新字段)
- Test: AgentGameTestServer 矩阵追加

**Interfaces:**
- Consumes: Task 4 的 `scheduler.cancelAllEpisodes`。
- Produces: `BotConfig.respawnGraceTicks`(int, 默认 60);`CombatChain.suppressAutoFor(int ticks)`;BotApiImpl 死亡钩子扩为全表清零。

- [ ] **Step 1: 失败测试**(矩阵区追加)

```java
// gap#68-③/⑧: 死亡后 autoFight 有宽限期不再立即重新交战(纯计数逻辑)
static void combatGraceMatrix(java.util.function.BiConsumer<Boolean, String> check, BotState st) {
    CombatChain cc = new CombatChain(st);
    cc.suppressAutoFor(3);
    check.accept(cc.autoSuppressed(), "suppressed right after death");
    cc.decayAutoSuppression(); cc.decayAutoSuppression(); cc.decayAutoSuppression();
    check.accept(!cc.autoSuppressed(), "suppression decays to zero");
}
```

- [ ] **Step 2: RED**(方法不存在)。

- [ ] **Step 3: 实现**

3a. `BotConfig`(autoBackfill 字段附近)追加:

```java
/** gap#68-⑧: ticks after a respawn during which autoFight does NOT re-engage and
 *  autoBackfill does NOT auto-start — a freshly-respawned naked bot must not resume
 *  lethal intents (combat kept hunting / orphan digging executed post-respawn). */
public static volatile int respawnGraceTicks = 60;
```

3b. `CombatChain` 追加(字段区+方法):

```java
/** Post-respawn autoFight suppression countdown (gap#68 grace). Tick thread only. */
private int autoSuppressTicks;
public void suppressAutoFor(int ticks) { autoSuppressTicks = Math.max(autoSuppressTicks, ticks); }
public boolean autoSuppressed() { return autoSuppressTicks > 0; }
public void decayAutoSuppression() { if (autoSuppressTicks > 0) autoSuppressTicks--; }
```

`bid()`(92-100)的 autoFight 分支改:

```java
if (BotConfig.autoFight && autoSuppressTicks == 0) {
```

`priority()`(81-90)开头加 `decayAutoSuppression();`(每 tick 恒被调用,正确衰减)。显式 intent(mc.bot.combat)不受宽限限制——那是 agent 的明确意图。

3c. `BotApiImpl`:字段区加 `private int respawnGraceLeft;`;死亡钩子(812)改:

```java
eventDetector.detectDeath(mc, () -> {
    cancelAllProcesses("player-death");
    scheduler.cancelAllEpisodes("player-death");   // gap#68-③⑦: 全链 episode 清零
    backfillTracker.clear();                        // gap#68-⑧⑫: 孤儿回填队列清零
    combatChain.suppressAutoFor(BotConfig.respawnGraceTicks);
    respawnGraceLeft = BotConfig.respawnGraceTicks;
});
```

backfill 自启(940)加宽限门:

```java
if (respawnGraceLeft > 0) respawnGraceLeft--;
if (c == null && respawnGraceLeft == 0 && BotConfig.autoBackfill && backfillTracker.size() > 0) {
```

(把 `respawnGraceLeft` 的自减放在这一段之前的固定位置,每 tick 一次。)

3d. `BunkerChain.priority`(44-60)在 `if (a.sealed)` 之前加自愈(⑦ 的"不赢标不自愈"缺口):

```java
// Self-heal on displacement/respawn even when NOT winning the bid: the old check
// lived in tick(), which only runs while this chain holds the channel — a stale
// sealed episode from before a death could bid 300 forever (gap#68-⑦).
if (a.active() && mc.player != null) {
    BlockPos f = mc.player.blockPosition();
    if (a.displacedFrom(f.getX(), f.getY(), f.getZ())) a.reset();
}
```

(import `net.minecraft.core.BlockPos` 已在。tick() 里那份保留,幂等。)

- [ ] **Step 4: GREEN + 全量回归 + setting 反射验证**

solo GREEN → 全量零新名。启动 dev client 后(Task 10 live 阶段)`mc.bot.setting {respawnGraceTicks: 60}` 必须回 applied 含该键(#280 教训)。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/ neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java
git commit -m "feat(scheduler): death clears ALL chain episodes + backfill queue; respawn grace gates autoFight/backfill; bunker self-heal in priority() (gap#68-③⑧⑫)"
```

---

### Task 6: RetreatGate 事件支 + 动态阈值(R3)

> **Spec 偏差说明(有意为之)**:spec §3.3 的 `ThreatContext` 单源结构体在此简化为"扩展现有静态门参数"。理由:①每 tick 单源属性已由 `ClientThreatScanner.refresh`(BotApiImpl.clientTick:869 每 tick 一次)+ `current()` 缓存保证,所有 chain 读的就是同一份 Scan;②改 `Chain.priority` 签名会波及全部 7 条 chain 与调度器,收益只有形式统一(YAGNI);③R3 的三个实质修复(hurt 事件支进闩、动态阈值、纯函数可测)全部保留。若 Phase 2 做抢占矩阵再引入完整 ThreatContext。

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/scheduler/RetreatChain.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java`(无新字段;仅当实现发现需要开关再加 `retreatHurtEntry=true`)
- Test: AgentGameTestServer 矩阵(3227 区域追加 case)

**Interfaces:**
- Produces: `RetreatChain.shouldEnter(float hp, float thr, float maxHp, ThreatScanner.Scan scan)` 新四参重载(旧三参保留,委托 `maxHp=20`);进入门新增第三支:任何 `attackedMe`(不限 RangedAttackMob)且 `distance<=CLEAR_RADIUS*2` → 进闩。

- [ ] **Step 1: 失败测试**(3227 矩阵区追加 case;`skel`/`empty` 构造沿用现场既有 helper,melee 变体用 zombie 类型 mock——查看该文件 3200 行前后 helper 如何构造 Threat,复用同一构造加 `attackedMe=true, entity=非 RangedAttackMob`)

```java
// gap#68-①: 被近战打中(attackedMe,非 Ranged)必须进闩——旧门只认 Ranged 或 HP≤thr
if (!RetreatChain.shouldEnter(18f, 6f, 20f, meleeHit.apply(2.0)))
    fail("melee attackedMe at full-ish HP must latch the flee");
// 动态阈值:maxHp*0.4=8 > thr=6,HP 7 + 近战近身必须进
if (!RetreatChain.shouldEnter(7f, 6f, 20f, zombieNear.apply(5.0)))
    fail("effective threshold is max(thr, 40% maxHp)");
// 阴性:无人打我、HP 高、无 ranged → 不进
if (RetreatChain.shouldEnter(18f, 6f, 20f, zombieNear.apply(5.0)))
    fail("nearby idle zombie at high HP must NOT latch");
```

(`meleeHit`/`zombieNear` = 本地构造 Scan 的小 helper,attackedMe/charging 位按名字置。)

- [ ] **Step 2: RED**(四参重载不存在)。

- [ ] **Step 3: 实现**(RetreatChain.java:84-93 区域)

```java
/** 4-arg gate (gap#68-R3): adds (a) hurt-entry — ANY attacker that actually hit me
 *  (attackedMe, melee included) within 2×CLEAR_RADIUS latches the flee at ANY hp:
 *  damage attribution is the single source (#55), we no longer require the attacker
 *  to be a RangedAttackMob type; and (b) a dynamic low-HP threshold of
 *  max(thr, 40% of maxHp) so a naked 20-HP bot reacts at 8, not 6. */
public static boolean shouldEnter(float hp, float thr, float maxHp, ThreatScanner.Scan scan) {
    float effThr = Math.max(thr, maxHp * 0.4f);
    boolean lowHp = hp <= effThr && hostileWithin(scan);
    boolean ranged = rangedThreatAiming(scan);
    return lowHp || ranged || underRangedFire(scan) || hurtByAnyone(scan);
}

/** Back-compat 3-arg gate (existing matrix tests + call sites): maxHp=20. */
public static boolean shouldEnter(float hp, float thr, ThreatScanner.Scan scan) {
    return shouldEnter(hp, thr, 20f, scan);
}

/** ANY attacker whose hit actually connected (vanilla last-damager window),
 *  near enough that it can do it again. Melee included — being hit IS the threat,
 *  regardless of the attacker's class (gap#68-① recharacterized). */
private static boolean hurtByAnyone(ThreatScanner.Scan scan) {
    for (ThreatScanner.Threat t : scan.threats()) {
        if (t.attackedMe() && t.distance() <= CLEAR_RADIUS * 2) return true;
    }
    return false;
}
```

`priority()`(61-65)改用四参:`if (!shouldEnter(hp, thr, mc.player.getMaxHealth(), scan)) return idle();`。
`shouldRelease` 不变(release 门已含 hostileWithin+underRangedFire;melee 攻击者在 CLEAR_RADIUS 内会挡 release——正确)。

⚠️ 行为影响检查(实现者自查):`hostileWithin` 的 melee 半径 12 不变;新 hurt-entry 半径 24(=CLEAR_RADIUS*2)与 ThreatScanner 默认扫描半径一致。

- [ ] **Step 4: GREEN + 全量回归**(既有 9-case 矩阵必须原样通过——三参门语义未动)。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/scheduler/RetreatChain.java neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java
git commit -m "feat(retreat): hurt-entry latch for ANY connected attacker + dynamic 40%-maxHP threshold (gap#68-①)"
```

---

### Task 7: Combat 脆血门(R4a)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/scheduler/CombatChain.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java`(combat verb 传 force)
- Test: AgentGameTestServer 矩阵

**Interfaces:**
- Produces: `BotConfig.combatFrailThreshold`(float, 默认 6f);静态纯门 `CombatChain.frailBlocked(float hp, float thr, boolean force)`;`mc.bot.combat` 参数 `force:boolean`(默认 false);`engage(mode,id,type,force)` 四参(旧三参保留委托 force=false)。

- [ ] **Step 1: 失败测试**

```java
// gap#68-②: 脆血不进新架
if (!CombatChain.frailBlocked(5f, 6f, false)) fail("hp5<=thr6 without force must block");
if (CombatChain.frailBlocked(5f, 6f, true))   fail("force overrides the frail gate");
if (CombatChain.frailBlocked(7f, 6f, false))  fail("hp above threshold must not block");
```

- [ ] **Step 2: RED**。

- [ ] **Step 3: 实现**

```java
/** gap#68-②: don't ENTER a fight on fragile HP. Pure & matrix-testable. */
public static boolean frailBlocked(float hp, float thr, boolean force) {
    return !force && hp <= thr;
}
```

- `engage(...)` 加四参重载存 `private volatile boolean intentForce;`。
- `bid()`:

```java
private float bid(Minecraft mc) {
    if (mc.player == null) return 0f;
    float hp = mc.player.getHealth();
    if (intentMode != null) {
        if (frailBlocked(hp, BotConfig.combatFrailThreshold, intentForce)) {
            // Explicit kill order on fragile HP: refuse loudly instead of dying quietly.
            state.combat.lastError = "frail-abort hp=" + hp;
            standDown();
            return 0f;
        }
        return Priorities.COMBAT;
    }
    if (BotConfig.autoFight && autoSuppressTicks == 0
            && !frailBlocked(hp, BotConfig.combatFrailThreshold, false)) {
        ThreatScanner.Threat top = ClientThreatScanner.current(mc).top();
        if (top != null && top.score() >= BotConfig.autoFightThreatThreshold) return Priorities.COMBAT;
    }
    return 0f;
}
```

- 交战中掉到脆血:`tick()` 开头加

```java
if (mc.player != null && intentMode == null
        && frailBlocked(mc.player.getHealth(), BotConfig.combatFrailThreshold, false)) {
    // Auto-fight turned frail mid-swing: stand down; Retreat (>=100) naturally takes over.
    standDown();
    state.combat.lastError = "frail-disengage";
    return;
}
```

(显式 intent 不中途弃战——agent 已知情;它的门在进入时。)
- `BotConfig` 追加 `public static volatile float combatFrailThreshold = 6f;`(javadoc 注明 gap#68-②)。
- `BotApiImpl` 的 combat verb(grep `combatChain.engage`)读 `p.get("force") instanceof Boolean b && b` 传入四参。

- [ ] **Step 4: GREEN + 全量回归。**

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/scheduler/CombatChain.java common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java
git commit -m "feat(combat): frail-HP gate on entry + mid-autofight disengage, force override (gap#68-②)"
```

---

### Task 8: DuskSecure 夜间升压(R4b)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/scheduler/Priorities.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/scheduler/DuskSecureChain.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java`
- Test: AgentGameTestServer 矩阵

**Interfaces:**
- Produces: `Priorities.DUSK_URGENT = 90f`;`BotConfig.duskUrgent`(默认 true)、`BotConfig.duskUrgentDryRun`(默认 false);静态纯门 `DuskSecureChain.urgentBid(boolean exposedAtNight, boolean cornered, boolean urgentOn, boolean dryRun)` 返回 float(0 / IDLE_SECURE / DUSK_URGENT)。

- [ ] **Step 1: 失败测试**

```java
// gap#68-④⑨: 夜间暴露未庇护 → 升压 90(user 50 不再压死自保);dry-run 只观察
if (DuskSecureChain.urgentBid(true, false, true, false) != Priorities.DUSK_URGENT) fail("exposed night urgent=90");
if (DuskSecureChain.urgentBid(true, false, true, true) != Priorities.IDLE_SECURE) fail("dry-run stays 40");
if (DuskSecureChain.urgentBid(true, false, false, false) != Priorities.IDLE_SECURE) fail("flag off stays 40");
if (DuskSecureChain.urgentBid(false, false, true, false) != Priorities.IDLE_SECURE) fail("daytime stays 40");
if (DuskSecureChain.urgentBid(true, true, true, false) != 0f) fail("already cornered/sheltered = no bid");
```

- [ ] **Step 2: RED**。

- [ ] **Step 3: 实现**

3a. `Priorities.java`(USER 与 IDLE_SECURE 之间语义上,数值上在 COMBAT 60 与 SURVIVAL 100 之间)追加:

```java
/** DuskSecureChain escalated tier (gap#68-⑨): exposed at night with no shelter,
 *  self-preservation MUST outrank the user task (same philosophy as SURVIVAL>USER)
 *  but stay below SURVIVAL so an active flee still wins, and above COMBAT so the
 *  bot digs in rather than picking fights at dusk. */
public static final float DUSK_URGENT = 90f;
```

3b. `BotConfig` 追加:

```java
/** gap#68-⑨: allow DuskSecureChain to escalate above the user task when exposed at
 *  night and unsheltered. Off = legacy idle-only (40) behaviour. */
public static volatile boolean duskUrgent = true;
/** First-night canary: when true the escalated tier only LOGS/emits (bid stays 40),
 *  so the new preemption path can be observed before it is allowed to act. */
public static volatile boolean duskUrgentDryRun = false;
```

3c. `DuskSecureChain`:

```java
/** Pure bid policy for the dusk reflex (matrix-testable). 0 = sit out. */
public static float urgentBid(boolean exposedAtNight, boolean cornered,
                              boolean urgentOn, boolean dryRun) {
    if (!exposedAtNight || cornered) return 0f;
    if (urgentOn && !dryRun) return Priorities.DUSK_URGENT;
    return Priorities.IDLE_SECURE;
}
```

`priority()`(41-55)改为:

```java
@Override public float priority(Minecraft mc, WorldView w, BotState st) {
    if (!BotConfig.autoSecureAtDusk || mc.player == null) { idleTicks = 0; return 0f; }
    if (process != null) return lastBidTier;           // committed dig holds its tier
    WorldModel.Snapshot s = worldModel.snapshot();
    if (!s.present()) { idleTicks = 0; return 0f; }
    float bid = urgentBid(s.exposedAtNight(), s.cornered(), BotConfig.duskUrgent, BotConfig.duskUrgentDryRun);
    if (bid == 0f) { idleTicks = 0; return 0f; }
    if (bid == Priorities.IDLE_SECURE) {
        // Legacy idle tier keeps its protections: never near a threat, never preempting.
        for (ThreatScanner.Threat t : ClientThreatScanner.current(mc).threats()) {
            if (t.distance() <= THREAT_RADIUS) { idleTicks = 0; return 0f; }
        }
    }
    idleTicks++;
    if (idleTicks < IDLE_DEBOUNCE_TICKS) return 0f;
    if (BotConfig.duskUrgent && BotConfig.duskUrgentDryRun && bid == Priorities.IDLE_SECURE
            && s.exposedAtNight() && !s.cornered()) {
        maybeEmitDryRun(mc);                            // observe-only canary
    }
    lastBidTier = bid;
    return bid;
}
```

配套:字段 `private float lastBidTier = Priorities.IDLE_SECURE;`;常量 `IDLE_DEBOUNCE_TICKS` 从 50 改 20(spec §3.4);`maybeEmitDryRun` = 每 200t 至多一次 `api.emitExternal("duskSecure.urgentDryRun", ...)`(照 announceAutoTrigger 模板,加 `private int dryRunCooldown;` 计数)。`tick()`/`onInterrupt` 不变;process 完成后 `lastBidTier` 重置回 IDLE_SECURE(在 `if (process.tick(...)) { process = null; lastBidTier = Priorities.IDLE_SECURE; }`)。

⚠️ 升压档故意不做 THREAT_RADIUS 否决(spec:有威胁更该缩;retreat 100/bunker 300 在真交战时仍压过 90)。

- [ ] **Step 4: GREEN + 全量回归。**

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/scheduler/ common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java
git commit -m "feat(dusk): DUSK_URGENT 90 escalation above user task, dry-run canary, debounce 50->20 (gap#68-④⑨)"
```

---

### Task 9: AutoTool 手动选槽宽限(R5)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/auto/AutoTool.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java`
- Test: AgentGameTestServer 矩阵

**Interfaces:**
- Produces: `BotConfig.manualSlotGraceTicks`(int, 默认 100);`AutoTool` 静态纯函数 `static boolean shouldYield(int selectedNow, int lastAutoSelected, int graceLeft)` + 有状态 `tick` 接线。

- [ ] **Step 1: 失败测试**

```java
// gap#68-⑪: 外部切槽后 AutoTool 必须让路一个宽限期
if (!AutoTool.shouldYield(2, 7, 0))  fail("selected(2) != lastAuto(7) = external change -> yield");
if (AutoTool.shouldYield(7, 7, 0))   fail("no external change, no grace -> proceed");
if (!AutoTool.shouldYield(7, 7, 10)) fail("grace still counting -> yield");
if (AutoTool.shouldYield(2, -1, 0))  fail("first tick (no lastAuto yet) -> proceed");
```

- [ ] **Step 2: RED**。

- [ ] **Step 3: 实现**(AutoTool.java 全类改造;保持 stateless 注释更新为"minimal cross-tick state for the manual-selection grace")

```java
/** Last slot THIS class wrote (-1 = none yet). If inv.selected differs, an external
 *  actor (setHotbarSlot RPC / human scroll) changed it — honor that for a grace
 *  period instead of clobbering it next tick (gap#68-⑪). */
private static int lastAutoSelected = -1;
private static int graceLeft;

/** Pure yield policy (matrix-testable). */
static boolean shouldYield(int selectedNow, int lastAuto, int grace) {
    if (grace > 0) return true;
    return lastAuto != -1 && selectedNow != lastAuto;
}

public static void tick(Minecraft mc, LocalPlayer p) {
    Inventory inv = p.getInventory();
    if (shouldYield(inv.selected, lastAutoSelected, graceLeft)) {
        if (graceLeft == 0) graceLeft = BotConfig.manualSlotGraceTicks;  // fresh external change
        graceLeft--;
        if (graceLeft == 0) lastAutoSelected = -1;   // grace expired: re-arm cleanly
        return;
    }
    if (!(mc.hitResult instanceof BlockHitResult br)) return;
    // …(原 22-46 行逻辑原样保留)…
    if (bestSlot != inv.selected) {
        inv.selected = bestSlot;
        lastAutoSelected = bestSlot;
        if (p.connection != null) {
            p.connection.send(new ServerboundSetCarriedItemPacket(bestSlot));
        }
    } else {
        lastAutoSelected = inv.selected;
    }
}
```

`BotConfig` 追加 `public static volatile int manualSlotGraceTicks = 100;`(javadoc gap#68-⑪)。

- [ ] **Step 4: GREEN + 全量回归。**

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/auto/AutoTool.java common/src/main/java/net/magicterra/worlddriver/bot/BotConfig.java neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTestServer.java
git commit -m "feat(autotool): honor external hotbar selection for a grace period (gap#68-⑪)"
```

---

### Task 10: live 验收协议(⭐live=真相;arena 只是回归卫士)

**Files:**
- Create: `scripts/accept_scheduler_p1.py`(验收驱动脚本,rpc_call.py 风格)
- 参照:`docs/superpowers/specs/2026-07-14-scheduler-semantics-phase1-design.md` §4.3/§4.4

前置:重建 dev jar 并重启客户端(改了 resources 无关,纯代码也须 relaunch 装新 jar;按 `reference_client_relaunch` 流程:按端口 owner 杀→DISPLAY=:99→into_world.py)。先 `mc.bot.setting` 全 flag A/B 验 applied(新键:respawnGraceTicks/combatFrailThreshold/duskUrgent/duskUrgentDryRun/manualSlotGraceTicks,#280 教训)。

- [ ] **腿①(goto 盲区/hurt-entry)**:受控 rig(#65 模板:关 dusk+summon skeleton+0.5s 采样)变体=**goto 行进中**被射→断言 `chains.retreat.episode=FLEEING` 出现且 HP 损失 ≤4;melee 变体=summon zombie 贴身打一下(高 HP)→ 同断言。
- [ ] **腿②(脆血)**:`/effect give` 减到 HP≤6 → `mc.bot.combat {mode:kill,...}` 无 force → 响应/状态 `combat.lastError=frail-abort*`;加 `force:true` → 正常进入。
- [ ] **腿⑤(goalReached)**:goto 一个不可站矿柱顶(或 direction:up 8 格)`awaitMs` → 断言 `completed:true ∧ goalReached:false ∧ endReason ∈ {goal-snapped, best-effort-consumed, frontier-giveup}`;再 goto 一个普通可达点 → `goalReached:true ∧ endReason=arrived`。
- [ ] **腿⑦(cancel 可达)**:setting autoBunker=true → 造 sealed(或直接用 script.eval 置 anchor)→ status 见 `chains.bunker.episode=SEALED` → `mc.bot.cancel {process:"bunker"}` → episode 消失、chainPriorities.bunker=0、无需动 autoBunker flag。
- [ ] **腿③⑧⑫(死亡清表+宽限)**:autoFight=true+autoBackfill=true,挖几格攒 backfill 队列,summon 致死 → 重生后 60t 内断言:无 BackfillProcess 自启、combat 不重新交战、`chains.*.episode` 全空;60t 后恢复正常。
- [ ] **腿⑩(bunker 诚实)**:石隧道场景(上次 no-op 现场同款)发 `mc.bot.bunker {awaitMs:40000}` → 断言 `completed:true` 且 (`goalReached:true`=真围合 或 `goalReached:false ∧ lastError/endReason` 说明原因——不许静默 ok)。
- [ ] **腿④⑨(dusk 升压)**:第一夜 `duskUrgentDryRun:true` 观察 `duskSecure.urgentDryRun` 事件与零抢占;第二夜关 dry-run,日落时挂一个长 goto → 断言 duskSecure 抢占(activeChain=duskSecure)、user 挂起、SEALED 后天亮自动续跑 goto。
- [ ] **腿⑪(AutoTool 宽限)**:idle 态 setHotbarSlot(2) → 连续 useItem 放两块,断言两次 SUCCESS(不再需要每次重切)。
- [ ] 记录:每腿结果回填 task#67 描述与 memory;全绿 → task#54 Phase 1 标记完成,残余转 Phase 2。

- [ ] **Commit**(脚本+验收记录)

```bash
git add scripts/accept_scheduler_p1.py
git commit -m "test: scheduler semantics P1 live acceptance protocol (gap#68 legs mapping)"
```

---

## 验收对账表(11+2 腿 → 验证层)

| 证据腿 | 验证 |
|---|---|
| ①goto 盲区 | Task 6 矩阵 + Task 10 腿① |
| ②脆血 | Task 7 矩阵 + Task 10 腿② |
| ③跨死亡 combat | Task 5(grace)+ Task 10 腿③ |
| ④dusk 间歇 | Task 8(debounce 20 + 升压)+ Task 10 腿⑨ |
| ⑤未达报成功 | Task 1/2 + Task 10 腿⑤ |
| ⑥pickup 幻报 | 不在本计划(事件层,task#24) |
| ⑦chain 槽泄漏 | Task 4(cancelEpisode)+ Task 5(priority 自愈)+ Task 10 腿⑦ |
| ⑧重生孤儿挖掘 | Task 5(backfill 清零+宽限)+ Task 10 腿③ |
| ⑨优先级倒挂 | Task 8 + Task 10 腿⑨ |
| ⑩bunker 假成功 | Task 3 + Task 10 腿⑩ |
| ⑪选槽覆写 | Task 9 + Task 10 腿⑪ |
| ⑫cancel 后 backfill 伤害 | Task 4(cancel all 分支清 backfillTracker,已在 Step 3h 代码中)+ Task 5(死亡清) |
| ⑬runAway 锚点 | 不在本计划(verb 目标选择,记 task#24 滚动;RetreatChain 的 fleeFrom 质心逻辑已合理) |

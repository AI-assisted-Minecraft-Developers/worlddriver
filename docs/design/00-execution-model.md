# 设计文档 00 —— 执行模型与优先级链调度器

> 覆盖 ROADMAP Phase A。这是所有自治能力的承重墙，必须最先落地。
> 参考：`research-clones/altoclef/src/main/java/adris/altoclef/tasksystem/`、`chains/`

## 1. 问题

当前的执行模型分两块（见 `BotApiImpl.clientTick`，约 L744）：

- **前台 process 槽**：`volatile BotProcess current`，同一时刻只跑一个，后来者覆盖前者。
- **ambient 自动行为**：`bot/auto/` 包下的 `AutoEat`/`AutoTool`/`AutoRespawn`/`AutoSwim`，每个被 `BotConfig` 开关 + **输入通道所有权**门控（如 `processOwnsUseKey` 为真时不抢 use 键），与前台 process 并发跑。
- **CLUTCH（MLG 水桶接坠落）**：在 `clientTick` 开头 `if (CLUTCH.tick(mc, world)) return;`——它**已经是一个 early-return 式的最高优先级抢占**。

也就是说，"通道所有权 + CLUTCH 抢占"已经是本调度模型的雏形。它撑不起自治的地方在**前台槽**：

- bot 挖矿到一半被僵尸打 → 需要自动切到战斗 → 打完**回来继续挖**。当前前台槽是覆盖式的，做不到"回来"。
- 战斗需要作为一个完整 process 抢占用户任务，又在结束后让用户任务恢复——这是 CLUTCH 那种"瞬时 early-return"表达不了的（它没有"挂起/恢复"语义）。
- Boss 战需要"主战斗 + 持续躲避 + 持续回血"多个行为叠加。

> **本设计只重构前台槽**：把覆盖式的单 `current` 升级为带抢占/恢复的多链调度。`bot/auto/` 的 ambient 行为保持现状（通道门控），CLUTCH 的 early-return 抢占被一般化为调度器里的 `PRIORITY_PANIC` 带。不推翻已经能用的部分。

altoclef 用 `TaskRunner` 解决：**N 条 `TaskChain` 每 tick 按 `getPriority()` 竞争，只有最高优先级的链执行**。优先级是局势的函数——没怪时御敌链优先级为 0（不参与），怪一出现优先级飙到任务链之上，自动抢占；战斗结束优先级归零，被挂起的任务链自动恢复执行。

我们移植这个模式，但适配到自己的 `BotProcess` 体系（不引入 Baritone）。

## 2. 三层执行模型

两类反射，按它们争的是**移动通道**还是**手部/装备通道**分开落地：

```
  clientTick():
   ┌─ A. 手部/装备 ambient（bot/auto/，与移动并发，不进调度器）─────┐
   │   AutoTotem(副手) / AutoShield+AutoEat+AutoHeal(争 use 键，仲裁)  │
   │   / AutoTool(hotbar) / AutoEquip ── 各被 BotConfig + 通道门控     │
   └─────────────────────────────────────────────────────────────┘
   ┌─ B. 移动通道 ProcessScheduler.tick：选 priority 最高的 active chain ┐
   │  PanicChain (躲苦力怕/dodge/CLUTCH 接坠落)   priority 900–1000     │
   │  RetreatChain (低血脱离)                     priority 100          │
   │  CombatChain (持 CombatProcess，有敌情)       priority 60          │
   │  UserTaskChain (持前台 BotProcess goto/mine/craft)  priority 50    │
   └─────────────────────────────────────────────────────────────┘
```

- **划分依据**：争"谁控制走位"的（panic/dodge/retreat/combat/用户任务）走 chain 竞价；只产生"瞬时手部/装备副作用"的（举盾/吃喝/补图腾/换甲）走 `bot/auto/`，与移动并发——见 §6。这沿用现有 `AutoEat` 的通道门控，不另造机制。
- 时间尺度：T0 反射本地 tick 决策（判断一里说的，绝不经 LLM）；T1 技能闭环数秒；T2（外部 LLM）通过 `mc.bot.*` 设*意图*，从不参与 tick。

## 3. 接口设计

保留现有 `BotProcess` 不变（已有 18 个 process 实现，不重写）：

```java
public interface BotProcess {
    String kind();
    void attach(BotState st);
    boolean tick(Minecraft mc, WorldView w, BotState st);  // true = 完成
}
```

新增 chain 抽象：

```java
public interface Chain {
    String name();
    /** 每 tick 评估一次。返回 <= 0 表示不参与竞价。越大越优先。 */
    float priority(Minecraft mc, WorldView w, BotState st);
    /** 被调度选中时执行。 */
    void tick(Minecraft mc, WorldView w, BotState st);
    /** 本链被更高优先级链抢占时调用，保存恢复点 / 松开按键。 */
    default void onInterrupt(Chain by) {}
    /** 重新获得执行权时调用（从挂起恢复）。 */
    default void onResume() {}
}
```

调度器：

```java
public final class ProcessScheduler {
    private final List<Chain> chains;      // 注册顺序无关，按 priority 选
    private Chain current;
    private float lastPriority;            // 滞后用

    public void tick(Minecraft mc, WorldView w, BotState st) {
        Chain best = null; float bestP = 0f;
        for (Chain c : chains) {
            float p = c.priority(mc, w, st);
            if (p > bestP) { bestP = p; best = c; }
        }
        // 滞后：避免两链优先级接近时每 tick 抖动抢占
        if (current != null && best != current
            && bestP < lastPriority + HYSTERESIS) {
            best = current; bestP = lastPriority;
        }
        if (current != null && best != current) current.onInterrupt(best);
        if (best != null && best != current) best.onResume();
        current = best; lastPriority = bestP;
        if (best != null) best.tick(mc, w, st);
    }
}
```

> `HYSTERESIS` 对应 altoclef 的 `cachedLastPriority` 防抖（见 `MobDefenseChain`）。

## 4. 两种具体 chain

### 4.1 `UserTaskChain`（T1 基准）

包住"当前前台任务"。`mc.bot.goto/mine/craft/...` 不再直接设 `current`，而是 `userTaskChain.setProcess(p)`。

```java
final class UserTaskChain implements Chain {
    private BotProcess process;
    public void setProcess(BotProcess p) { this.process = p; p.attach(state); }
    public float priority(...) { return process != null ? PRIORITY_USER : 0f; }  // 例如 50
    public void tick(Minecraft mc, WorldView w, BotState st) {
        if (process != null && process.tick(mc, w, st)) process = null;  // 完成清空
    }
    public void onInterrupt(Chain by) { Walker.releaseKeys(); }  // 松开 WASD，停在原地
    public void onResume() { Walker.forceRepath(); }             // 恢复时重算路径，不用陈旧路径
}
```

关键：`onResume` **强制 repath**。被抢占期间 bot 可能被打退、地形可能变，复用旧路径会卡墙。对应 ROADMAP 风险表第一条。

### 4.2 移动通道反射链（T0：`PanicChain` / `RetreatChain` / `DodgeChain`）

每个反射一个实例，`priority` 是局势函数（手部/装备反射不在这里，见 §6）：

```java
final class RetreatChain implements Chain {
    public float priority(Minecraft mc, WorldView w, BotState st) {
        float hp = mc.player.getHealth();
        if (hp >= BotConfig.retreatHpThreshold) return 0f;     // 不参与
        return PRIORITY_SURVIVAL + (BotConfig.retreatHpThreshold - hp);  // 越虚越优先
    }
    public void tick(...) { /* 触发 RunAwayProcess 等价逻辑 */ }
}
```

优先级带（建议常量）：

| 带 | 值 | 谁（仅移动通道链） |
|---|---|---|
| `PRIORITY_PANIC` | 900–1000 | PanicChain：躲苦力怕膨胀 / DodgeChain 躲弹道 / CLUTCH 接坠落 |
| `PRIORITY_SURVIVAL` | 100 | RetreatChain（低血脱离） |
| `PRIORITY_COMBAT` | 60 | CombatChain（有敌情） |
| `PRIORITY_USER` | 50 | UserTaskChain（用户前台任务） |

> autoShield/autoTotem/autoHeal 这类"一边干活一边防"的**不在这张表里**——它们是 `bot/auto/` 的手部/装备 ambient 行为，与移动并发，不参与移动通道竞价。见 §6。

## 5. 与现有代码的迁移

| 现状 | 改为 |
|---|---|
| `BotApiImpl.current` 单进程字段 | `ProcessScheduler` 持有多 chain；`UserTaskChain` 持单进程 |
| `startProcess(p)` 替换 current | `userTaskChain.setProcess(p)` |
| `BotApiImpl.clientTick` 直接 `current.tick()` | `scheduler.tick()` |
| `mc.bot.status` 报单进程 | 报：active chain + suspended user process + 各 chain priority |
| `mc.bot.cancel{kind}` | 取消对应 chain 的 process；`bot/auto/` ambient 行为不可 cancel（只能 `setting` 关） |

对外 API **行为不变**：`mc.bot.goto` 仍返回 `{started:true}`，仍靠 `mc.bot.status` 轮询。只是 status 多了"我被战斗抢占了，挂起中"这种状态，让 T2 能理解为什么任务暂停。

## 6. 需要解决的难点：叠加 vs 抢占

纯"最高优先级独占"模型有个洞：**举盾/补图腾应该和移动并发**，不该把移动完全停掉。但好消息是——**现有代码已经用"通道所有权"模型解决了这个问题**：`AutoEat` 只在没有 process 占用 use 键时跑，`AutoTool` 只在没 process 占 hotbar 时跑。我们沿用，不另造概念：

- **chain 调度器只管"谁拥有移动通道"**（前台槽的抢占/恢复）——retreat、dodge、panic、combat、用户任务在这里竞价。
- **`bot/auto/` 的 ambient 行为继续按现状运行**：新增的 `AutoTotem`/`AutoShield`/`AutoHeal`/`AutoEquip` 作为 `bot/auto/` 下的兄弟类，被 `BotConfig` 开关 + 通道门控，**与移动并发**，不参与 chain 竞价。这样"一边走一边举盾/补图腾"天然成立。

真实 `clientTick` 结构（在现有骨架上扩展）：

```java
void clientTick() {
    Minecraft mc = ...; WorldView world = ...;
    if (BotConfig.autoRespawn) AutoRespawn.tick(mc);
    if (CLUTCH.tick(mc, world)) return;          // PANIC：MLG 接坠落，early-return 抢占一切
    // ── ambient 手部/装备行为：按通道所有权门控，与移动并发 ──
    runAmbientUseKey(mc);   // autoShield / autoEat / autoHeal 仲裁同一个 use 键（见下）
    if (autoTool && !processOwnsHotbar) AutoTool.tick(mc, mc.player);
    if (autoTotem) AutoTotem.tick(mc, mc.player);   // 占副手槽，不与 use 键冲突
    if (autoEquip) AutoEquip.tick(mc, mc.player);
    // ── 移动通道：调度器选最高优先级链 ──
    scheduler.tick(mc, world, state);
}
```

> **use 键争用（02 文档展开）**：`AutoShield`/`AutoEat`/`AutoHeal` 都要持 use 键，互斥。`runAmbientUseKey` 在它们之间仲裁——弹射物来袭时盾 > 吃，否则低血治疗 > 普通进食。这是现有 `processOwnsUseKey` 门控的自然延伸（claimant 从"1 个 process"变成"process + 几个 ambient 用户"）。

## 7. 验证（`validation/40_scheduler.js`）

1. 启动 `mc.bot.goto` 远点 → 确认在走。
2. 在 bot 旁 `/summon zombie` + 让其掉血 → 断言 `mc.bot.status` 显示 CombatChain 活跃、UserTaskChain `suspended:true`。
3. 杀掉/移除僵尸 → 断言 goto 自动恢复（status 回到 UserTaskChain 活跃）且 bot 重新走向原目标（验证 forceRepath）。
4. 三传输（in-JVM / RPC / MCP）对 `mc.bot.status` 快照做字节级 parity。

## 8. 开放问题

- 多个用户任务排队？当前一次只一个前台任务。是否要 `UserTaskChain` 内部排一个队列（"挖完木头接着搓工作台"）？倾向**不在 mod 内排队**——那是 Phase H 规划器（T2）的职责，mod 只暴露"当前一个任务 + 它的抢占/恢复"。
- chain 注册是否要可热插拔（Rhino 注册自定义 chain）？Boss 剧本可能想要。Phase G 评估。

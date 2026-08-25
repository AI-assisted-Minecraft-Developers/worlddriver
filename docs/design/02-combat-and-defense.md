# 设计文档 02 —— 御敌、战斗与装备

**Historical — dated 2026-06-04, superseded by the shipped `bot/process/CombatProcess.java`,
`bot/scheduler/CombatChain.java` and `bot/util/AttackSnap.java`.**

> 覆盖 ROADMAP Phase B（御敌反射 T0）+ Phase C（战斗循环 T1）+ Phase F（装备）。
> 依赖 Phase A 调度器（见 `00-execution-model.md`）。
> 参考：`research-clones/altoclef/` 的 `chains/MobDefenseChain.java`、`chains/FoodChain.java`、`chains/MLGBucketFallChain.java`、`control/KillAura.java`、`tasks/entity/AbstractKillEntityTask.java`、`tasks/movement/{DodgeProjectilesTask,RunAwayFromCreepersTask}.java`、`tasks/misc/EquipArmorTask.java`

## 1. 第一原则：保命回路绝不经过 LLM

苦力怕引信 ~1.5s、骷髅射击间隔、攻击冷却恢复都是 tick 量级。任何"感知危险→反应"如果往返一次 LLM（数百 ms–数秒）必死。

> **分工**：LLM（T2）只设*意图*——"打那只僵尸"/"保命撤"/"打凋零"。tick 级战术全在 mod 本地。
> 御敌反射（T0）甚至不需要意图，常驻竞价，威胁出现就自动接管。

## 2. 共用：威胁感知

战斗（C）和防御（B）共用一个感知，避免两套扫描。

`mc.observe.threats{radius?}`（或扩 `mc.query` 的 entities 模式）：

```
出: [{
     id, type, pos, distance,
     hostile: true,
     canSeeMe: bool,                 // 视线无遮挡（raycast）
     facingMe: bool,                 // 朝向我（弹射物来袭预判）
     charging: "bow"|"none",         // 在拉弓/蓄力
     threat: 0.0–1.0                 // 综合打分
   }],
   incomingProjectiles: [{ id, type:"arrow", pos, vel, willHit: bool, ticksToImpact }]
```

打分维度：距离、类型危险度（苦力怕/恶魂权重高）、`canSeeMe`、`charging`。`willHit` 用弹射物速度向量对玩家 AABB 做一步预测。

实现放 `bot/combat/ThreatScanner.java`，每 tick 算一次缓存，B/C 两层共读。

## 3. Phase B —— 御敌反射（T0）

### 3.1 分两类落点（见 00 文档 §2 / §6）

按反射争的是**移动通道**还是**手部/装备通道**分开（与现有 `bot/auto/` + CLUTCH 结构对齐）：

| 反射 | 争什么 | 落点 | 优先级 |
|---|---|---|---|
| 躲苦力怕膨胀 | 移动 | `PanicChain`（调度器） | `PRIORITY_PANIC` 1000 |
| MLG 水桶接坠落 | 移动 | 现有 `CLUTCH`（已 early-return 抢占） | 1000 |
| 躲来袭弹射物 / 龙息毒云 | 移动 | `DodgeChain`（调度器） | 900 |
| autoRetreat（低血脱离） | 移动 | `RetreatChain`（调度器） | `PRIORITY_SURVIVAL` 100 |
| autoShield（抬盾） | use 键 | `bot/auto/AutoShield` | — |
| autoTotem（副手补图腾） | 副手槽 | `bot/auto/AutoTotem` | — |
| autoHeal（喝/吃回血） | use 键 | `bot/auto/AutoEat` 扩展 | — |

手部/装备类**不参与 chain 竞价**——作为 `bot/auto/` 的兄弟类（旁边就是现成的 `AutoEat`/`AutoTool`/`AutoRespawn`/`AutoSwim`），被 `BotConfig` 开关 + 通道门控，与移动并发，可以"一边走一边举盾/补图腾"。

> **use 键争用（真实约束）**：`AutoShield`（持 use 举盾）、`AutoEat`、`AutoHeal` 都要按住 `mc.options.keyUse`，三者互斥。需在 `bot/auto/` 内做一个 use 键仲裁器：弹射物来袭/近战怪正对我 → 盾优先；否则低血 → 治疗优先；再否则 → 普通进食。这是现有 `processOwnsUseKey` 门控的延伸（claimant 从"1 个 process"扩成"process + 这几个 ambient 用户"）。

### 3.2 各反射要点

- **autoTotem**：副手非图腾且背包有图腾 → `slotClick` 换上。检测图腾刚触发（`TotemOfUndying` 动画/血量骤回）→ 立刻补下一个。altoclef `MobDefenseChain` 有同款逻辑。
- **autoShield**：`incomingProjectiles.willHit` 或近身近战怪面向我 → 切到盾（或右键举盾），转身正对来源（盾要对着伤害方向才挡）。注意：苦力怕用盾挡不住爆炸伤害大头，苦力怕走 panic 躲避而非举盾。
- **autoHeal**：扩 `autoEat`——HP 低且有喷溅治疗药/金苹果时优先用；血满后回到普通 autoEat 维持饱食。
- **autoRetreat**：HP < `retreatHpThreshold` → 复用既有 `RunAwayProcess` 朝远离威胁质心方向脱离；脱离中 autoHeal 持续回血。
- **躲苦力怕**：苦力怕进 `CREEPER_KEEP_DISTANCE`（~3 格、膨胀临界）→ 反向冲刺。altoclef `RunAwayFromCreepersTask`。
- **躲弹射物 / 龙息**：`DodgeProjectilesTask` 思路——侧向位移出弹道；龙息 `AreaEffectCloud` 落地形成毒云，立刻离开云的水平范围（Boss 战复用，altoclef `DragonBreathTracker`）。

### 3.3 配置

新增 `BotConfig` volatile 开关（自动经 `mc.bot.setting` 暴露，遵 AGENTS.md #6 不新增 verb）：

```
autoTotem, autoShield, autoHeal, autoRetreat, autoDodge   (bool)
retreatHpThreshold (float, 默认 6)
creeperKeepDistance, projectileDodgeRadius (double)
```

### 3.4 验证（`41_defense.js`）

刷苦力怕 → 断言后撤不被炸死；刷骷髅射箭 → 断言抬盾/侧移、掉血受限；血量打低 → 断言触发 retreat + heal 后血量回升。

## 4. Phase C —— 主动战斗循环（T1）

### 4.1 `CombatChain` + `CombatProcess`

`CombatChain.priority`：有 `threat > 阈值` 的敌对实体且我方有交战意图（或 `autoFight` 开）→ 返回 `PRIORITY_COMBAT` 60（> 用户任务 50，自动抢占采矿等）；无敌情 → 0。

`CombatProcess`（前台战斗逻辑）：

```
mc.bot.combat{ mode: "engage"|"defend"|"kill", target?: {type|id|nearest} }
```

- `kill`：指定目标，打死为止。
- `engage`：清理范围内所有敌对，直到清场。
- `defend`：只反击主动攻击我的，不主动出击（配合 T0 用）。

战斗循环每 tick：

1. 选目标（`ThreatScanner` 最高分，或指定 id）。`lockOn` 锁定，避免每 tick 抖动切目标（altoclef `lockedOnEntity`）。
2. 判断武器类型：近战（剑/斧）→ 进 3 格；远程（弓/弩）→ 保持 `kiteDistance` 并面向目标。
3. 进距用既有 `goto{entity}` / `follow`。
4. **攻击时机（关键）**：
   - 近战只在 `mc.player.getAttackStrengthScale(0.5f) >= 1.0` 时挥（冷却满才是满伤；Mojmap 下该方法带 partialTick 参数）。**写这份文档时 `attackEntity` 缺这一步——只有 `gameMode.attack(p,target)` + `swing()`，不查冷却，连续调用就是一直弱攻击。这条已经落地：`CombatProcess` 按 `getAttackStrengthScale` 决定何时出手，`bot/util/AttackSnap.java` 每 tick 读它。**
   - **暴击**：在下落过程中（非地面、非上升、未在水/梯）攻击触发暴击 1.5×。战斗循环可主动小跳后下落瞬间出手。
   - 走既有 `attackEntity` 的 `gameMode.attack()` 路径（vanilla 应用伤害/横扫/暴击）。
5. **走位**：近战绕目标 strafe 躲正面；远程边后退边射（kite），保持 `kiteDistance`。
6. 自终止：目标死亡且无其它敌情 → process 完成，CombatChain 优先级归零，被抢占的用户任务恢复。自身 HP 告急时不在这里处理——T0 的 autoRetreat 优先级 100 > 战斗 60，自动抢占撤退。

### 4.2 时机判定为何本地可靠（ROADMAP 风险表）

攻击冷却/暴击全用客户端 `mc.player` 状态本地判定（`getAttackStrengthScale`、`onGround`、`fallDistance`），不依赖服务端回包，高 ping 下依然稳。

### 4.3 对外 tool

一个 verb `mc.bot.combat` 用 `mode` 覆盖三种意图（遵 #6）。`autoFight` 作为 `BotConfig` 开关，开了则 CombatChain 在有敌情时自动竞价（无需 LLM 每次下令）。

### 4.4 验证（`42_combat.js`）

刷僵尸群 → 断言全清；插桩记录每次挥击时的 `getAttackStrengthScale`，断言绝大多数 ≥ 0.9（命中冷却窗口）；远程模式刷骷髅 → 断言保持距离 + 弓杀。

参考：altoclef `KillAura`（瞄准+冷却+多目标）、`AbstractKillEntityTask`、`KillEntitiesTask`。

## 5. Phase F —— 装备管理（T1 + T0）

打硬仗/Boss 的前置。

### 5.1 `EquipProcess`

```
mc.bot.equip{ profile: "best"|"combat"|{slots...} }
```

- 扫背包，每个护甲位选最优（材质 > 附魔权重），`slotClick` 穿上。
- 主手选最优武器（剑优先，按材质 + 锋利/力量附魔）。
- 耐久检测：装备 < `durabilityThreshold` → 报警交还 T2（去修/换）。缺关键装（如打凋零没全甲）→ 报 `missing`。

### 5.2 T0 `autoEquip`

战斗开始时（CombatChain 激活的瞬间）由 `bot/auto/AutoEquip` 确保最优装备已上身（与现有 `AutoTool` 同族、同包——`AutoTool` 管手持工具，`AutoEquip` 管护甲/武器）。

### 5.3 验证（`45_equip.js`）

背包塞混合护甲（铁+钻混） → 断言穿上最优全套；插耐久极低的剑 → 断言报警。

参考：altoclef `EquipArmorTask`、`PreEquipItemChain`。

## 6. 数据流总览

```
ThreatScanner (每 tick, 共享)
   ├──→ B 反射: 调度器链(Panic/Retreat/Dodge) + bot/auto/(AutoShield/AutoTotem/AutoEat 仲裁 use 键)
   └──→ C 战斗: CombatChain.priority + CombatProcess 选目标/时机/走位
                                          │
   F 装备: bot/auto/AutoEquip ────────────┘ 战斗前确保装备
                                          │
调度器(00 文档,仅移动通道): PANIC > SURVIVAL > COMBAT > USER  → 自动抢占与恢复
手部/装备 ambient 与移动并发, 不进调度器
```

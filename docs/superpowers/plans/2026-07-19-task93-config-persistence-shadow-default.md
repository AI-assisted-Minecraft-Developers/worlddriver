# task#93：config 持久化影子默认（快照压制默认翻转的结构修）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** BotConfig 持久化目前是反射全字段快照（BotConfig.java:2393-2440）：带旧 properties 的客户端在 reload 时用陈旧快照值压掉后续版本的默认翻转（D2 实证：`walkerWaterClimbLateralGate` 新默认 true 被上一 session 落盘的 false 覆盖）。结构修：让「用户没改过的键」跟随新默认，「用户显式改过的键」继续保留。

**Architecture:** **影子默认（shadow-default）方案**——save 时每键写两行：`<key>=<value>` + `<key>.default=<当时编译默认>`；load 时若影子存在且 `value == 影子默认` → 该键只是快照，**跳过**（让当前编译默认生效）；若 `value != 影子默认` → 用户显式改过，**应用**。选此方案因为它自包含在持久化层（不必在每条写路径埋「显式改动」追踪）、无需维护历史默认表、`.default` 后缀与字段名（java 标识符无点号）零冲突。**已拍板语义**：用户把某键显式设回默认值＝跟随默认（影子判等会视作快照）——文档写明。**Legacy 文件**（无影子行）：条目按用户意图保守保留（照常应用），但对「与当前默认不一致」的键打一条 WARN 漂移清单（可见不静默）；load 成功后立即**升级重存**（写出影子行），此后默认翻转对该文件生效。方案 (a)「只持久化显式改动」被弃：需要在 SettingsCommand/反射 fallback 等多条写路径埋点，且 legacy 文件同样无法区分意图，复杂度高收益同。

**Tech Stack:** BotConfig 持久化段（纯 common Java）、live 双向验证（dedicated server + `-Dagent.persistConfig=true`）。

## Global Constraints

- **全 armor**（common Java 变更）：三模块编译 + dogfood 双 loader（131 场景金值不变——持久化 opt-in，testkit/dogfood 从不启用，理应零影响，armor 即为证明）+ instrument.py 双 loader + t1.py。
- **持久化语义唯一变更点在 load/save 两函数**；`persistableFields()` 名单、opt-in 门（`persistEnabled()`）、调用时机全部不动。
- ⛔pkill；Bash timeout 参数（≥300000 于所有 server 启动命令）；一次一服务器；外部 Touhou 客户端不动。

---

### Task 1: 影子默认实现 + live 双向验证 + 文档

**Files:**
- Modify: `common/src/main/java/net/magicterra/agent/bot/BotConfig.java`（:2393-2440 持久化段：save 写影子行；load 影子判等跳过/应用 + legacy WARN + 升级重存）
- Modify: `TODO.md`（task#93 收案条目）、`docs/testkit/migration-log.md`（append 补记 D2 尾注的结构修落地）
- 现存唯一落盘文件 `fabric/run/config/agent_driver_bot.properties`（两键已手工刷 true；本任务的升级重存会自然改写它——live 验证时确认升级后影子行齐全）

**Interfaces:**
- Consumes: `persistableFields()` 反射名单；`persistPath()`=config/agent_driver_bot.properties；`persistEnabled()` opt-in。
- Produces: 新文件格式（每键两行）；load 语义=影子判等。向后兼容：新代码读 legacy 文件不炸；旧代码读新文件会把 `.default` 行当未知键——`load` 按字段名反射查找，查不到的键现行为是什么？**实现前先读清楚**：若现行为是静默跳过则天然兼容，若会抛/警告则影子键须用现行为可容忍的形态（以实际代码为准，报告记录）。

- [ ] **Step 1**: 读 :2393-2440 现实现（save/load 全文），确认未知键行为与 exception 处理，实现影子写入+判等加载+legacy WARN+升级重存。编译三模块。
- [ ] **Step 2 live 双向验证**（dedicated server + `-Dagent.persistConfig=true`，临时 runDir，一次一服）：
  - **(a) 快照跳过向**：手造 legacy 文件含 `walkerWaterClimbLateralGate=false`（无影子）→ 启动 → 断言运行时值=true？**不对**——legacy 无影子按「保守保留」应用=false+WARN。真正的快照跳过向要用新格式：手造 `walkerWaterClimbLateralGate=false` + `walkerWaterClimbLateralGate.default=false`（模拟旧版本快照）→ 启动 → **断言运行时值=true（当前默认胜出）** + 文件升级后该键消失或影子=true。
  - **(b) 用户意图保留向**：手造 `autoSwim=false` + `autoSwim.default=true`（用户显式关过）→ 启动 → **断言运行时值=false**（用户意图保留）。
  - **(c) legacy 兼容向**：手造纯 legacy 文件（无任何影子行,含一个与默认不一致键）→ 启动 → 断言该键被应用 + WARN 漂移清单出现 + 文件被升级重存（影子行齐全）。
  - **(d) round-trip**：运行时 `mc.bot.setting` 改一键 → save → 重启 → 键值保留。
  - 断言途径=裸 RPC `mc.bot.setting` 快照读回（scripts/.claude/skills/agent-driver-rpc/rpc.py）+ server log WARN 行。
- [ ] **Step 3**: 全 armor（dogfood 双 loader + instrument ×2 + t1——同时证明 opt-in 门未被破坏：testkit 各 runDir 不得出现新落盘文件）。
- [ ] **Step 4**: `fabric/run` 现存文件的升级确认（跑一次 runClient 或按 (c) 的方式离线验证亦可——若动 live 客户端须走 into_world/screen-watch 纪律；离线手动验证优先）。
- [ ] **Step 5**: 文档 + Commit `fix(config): task#93 — shadow-default persistence (snapshot keys follow new defaults, explicit user changes preserved)`

---

## Self-Review（计划自检）

1. **覆盖**: 陷阱两侧（快照跟随新默认/用户改动保留）+legacy 兼容+升级路径+round-trip 全有验证步。
2. **占位符**: 无 TBD；(a)/(b)/(c)/(d) 四向断言全冻结；唯一留给实现的现场事实=未知键现行为（Step 1 先读再实现，报告记录）。
3. **一致性**: 「设回默认=跟随默认」语义已拍板并要求文档化；influence 面钉死在 load/save 两函数。
4. **风险**: 升级重存发生在启动路径——必须容错（IO 失败只 WARN 不阻断启动，与现 save 的 exception 处理一致）；`.default` 行不得进 `mc.bot.setting` 快照面（它走 SettingsRegistry 反射字段名单，天然不含——armor 的 instrument #280 检查会兜底）。

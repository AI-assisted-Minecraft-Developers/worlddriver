# DOCMAP — worlddriver 文档台账

这份文件是文档维护角色的记忆：**仓库里现有哪些文档、给谁看、还准不准、下一步动哪份。**
每轮开头读它，每轮结尾更新它。

⛔ **它不是判读日志。** 每份文档一行，理由不写在这里——
「这段代码为什么是这个形状」写在代码旁边，「这个行为为什么变了」写 `CHANGELOG.md`，
「排期」写 `ROADMAP.md`。台账只回答「有什么、准不准、下一个动谁」。

## 列的含义

- **读者** — `user`（装了这个 mod 的人）/ `dev`（在这个库里加东西的人）/
  `history`（存档，不描述现状）/ `feedback`（外部使用者的原始报告）/ `meta`（工作日志、指针）
- **状态** — `current`（未发现错）/ `stale`（**已核实**有错）/ `superseded`（自述被取代，留作存档）/
  `delete-candidate`（一次性计划且事已完成，或描述的东西已不存在）/ `keep`（永不删）
- **最后核对** — **对照代码**核对过的日期。`—` = 建账时按文档自述归类，**没验过**。

⚠️ **`git log` 的日期不能当核对日期。** 2026-08-26 有两趟机械扫描（`331d5177` 改名、
`374dcd3d`/`537040d9` 重指提交哈希、`ed5914e2` 打历史标签）把 70 多份文档的时间戳
推到了同一天。时间戳新 ≠ 内容核对过。

⚠️ **核对是不对称的**：核实错一条就足以判 `stale`；核实对一条**不足以**判 `current`
（`mcp-clients.md` 的端口文件名对，但那是 289 行里的两行）。

---

## 一、根级 — 目前事实上的 user / dev 入口

| 路径 | 读者 | 状态 | 最后核对 | 备注 |
|---|---|---|---|---|
| `README.md` | user | current | — | 英文主页；三个 transport 的连法目前最全的一份 |
| `README-zh_CN.md` | user | current | — | 中译；**必须与 `README.md` 同批改** |
| `AGENTS.md` | dev | **stale** | 2026-08-26 | canonical 开发约定。⚠️ 场景数写「222（171 `wd.*`）」，实为 **322**——见下方 P1 |
| `CLAUDE.md` | meta | current | 2026-08-26 | 三行指针，指向 `AGENTS.md`。内容已读，无误 |
| `CONTRIBUTING.md` | dev | current | — | 外部贡献者入口：构建 / 测试 / 布局 / 约定 / 日志位置 |
| `ROADMAP.md` | dev·history | — | — | N0–N5 里程碑梯 + E1/E2；排期归它 |
| `CHANGELOG.md` | history | **keep** | — | ⛔ 行为变更史，**永不删**（543KB） |
| `TODO.md` | meta | — | — | 主线活工作日志（119KB）。**他人所有，本角色不动** |
| `TODO-janitor-2026-08-22.md` | meta | delete-candidate | — | wd-janitor 的判读日志（127KB）。**归 janitor 处置，本角色只记账** |
| `TODO-rung10-forensics.md` | meta | delete-candidate | — | 一次调查的判读日志；违反「TODO 只放还开着什么」。**他人所有** |
| `TODO-rung20-stand.md` | meta | delete-candidate | — | 同上 |
| `path-replay/README.md` | dev | — | — | replay 工具链用法（record / replay / analyze） |
| `scripts/.claude/skills/worlddriver-rpc/SKILL.md` | user·dev | — | — | ⚠️ 跨平台，Linux 主机也读它——别写 Windows 专用指令 |
| `scripts/.claude/skills/worlddriver-rpc/references/methods.md` | user·dev | — | — | 72 方法 RPC 参考；方法数与 `DriverApi` 今日实测的 72 一致 |

## 二、`docs/` 顶层

| 路径 | 读者 | 状态 | 最后核对 | 备注 |
|---|---|---|---|---|
| `docs/mcp-clients.md` | user | current | 2026-08-26（仅端口文件名） | **目前唯一的 user 向 transport 文档，且只讲 MCP** |
| `docs/claude_desktop_config.example.json` | user | — | — | Claude Desktop 的 MCP 配置样例 |
| `docs/coverage-exemptions.md` | dev | — | — | 结构性无法被场景覆盖的 Walker 分支族。⚠️ 标题带 `task#95b`（违反禁编号） |
| `docs/walker-tick-architecture.md` | dev | — | — | `WalkerTick*` 相位类分解；`AGENTS.md`/`CLAUDE.local.md` 都指向它 |
| `docs/runbook-pathfinding-loop.md` | dev | — | — | pmcs 环的 Linux 客户端流程；`scripts/pmcs/` 今日确认仍在 |
| `docs/replay-corpus-regression.md` | dev | — | — | 活文档；语料已移出版本控制，文档解释去向 |
| `docs/fake-player-parity.md` | dev | — | — | 假人保真度边界表（2201 行）。**wd-parity 角色所有，本角色不动** |
| `docs/parity-setpos-centre-snap.md` | dev·history | — | — | 一次格心吸附审计的结论 |
| `docs/drown-escape-design.md` | dev | — | — | drownEscape 调研；自述 §4 已建成、§5 未建 |
| `docs/stagewright/migration-log.md` | history | **keep** | — | 自述为 migrate-then-delete 的**永久审计记录**——名字像日志，实为存档 |

## 三、`docs/design/` — 5 份，2026-06-04 的原始设计

今日（`ed5914e2`）刚被逐份加上「Historical，已被某某实现取代」的抬头。
**这是有意的策展，不是失修**：状态 `superseded`，保留。

| 路径 | 被什么取代（据自述） |
|---|---|
| `00-execution-model.md` | `bot/scheduler/` 包（`ProcessScheduler` + 各 Chain） |
| `01-knowledge-and-crafting.md` | `mc.recipe.lookup` / `mc.recipe.resolve` / `mc.plan.acquire` |
| `02-combat-and-defense.md` | `bot/process/CombatProcess.java`、`bot/scheduler/CombatChain.java` |
| `03-boss-playbooks.md` | `mc.bot.playbook` 动词 + `playbooks/` 下的脚本 |
| `04-perception-and-decision-boundary.md` | `mc.observe.scene` / `mc.client.scene` + scheduler chains |

## 四、`docs/feedback/` — 4 份 ⛔ 永不删

外部使用者的原始报告，**按提交时的原样保存**。同样在今日被加上 Historical 抬头。

`2026-06-04-first-external-consumer-winefoxs.md` ·
`2026-06-07-mcquery-blind-to-stairs.md` ·
`2026-06-08-reading-command-results.md` ·
`2026-07-10-gui-layout-regression-no-entity-interact.md`

## 五、`docs/superpowers/` — 54 份，删除的主战场

一次性实施计划与设计 spec。判据是**「这件事做完了没有」**，而做完了的计划应当删除——
它描述的是一段已经走完的路，不是仓库现在的样子。⚠️ **逐份核实，不许整目录清**：
其中有几份仍是唯一记录某个设计推理的地方。

### `plans/` — 40 份

自称 SUPERSEDED、可直接删的两份（**下轮之后的第一波**）：

| 文件 | 备注 |
|---|---|
| `2026-06-17-buoyant-water-navigation.md` | 顶部横幅：前提已被 live 诊断推翻 |
| `2026-07-15-executor-travel-actuator-extract-A.md` | 顶部横幅：被 `2026-07-16-executor-b1-thin-machine.md` 取代 |

其余 38 份 = `delete-candidate`，**完成状态逐份未核实**。按主题分四族，供分波处置：

- **stagewright 建设（14）** — `p0-suite-integrity`、`p1a-walking-skeleton`、`p1b-instrument-contract`、
  `p1c-dogfood-swallowed-trio`、`p15a-lottery-walker-family`、`p15b-lottery-driver-family`、
  `p16-fabric-alignment`、`p2a-verb-pipeline`、`p2b-t1-client-instrument`、`p2c-junit-attach`、
  `p3a-t2-production-topology`、`p3b-plugin-pool-maven`、`p4a-testmod-destination`、
  `p4final-machinery-retirement`
  ↑ 框架已独立成 `stagewright/` 仓库且六拓扑全绿，这一族**最可能整族已完成**
- **legacy 迁移波（2）** — `p4b-family-migration-waves`、`p4c-server-family-waves`
  ↑ 与 `docs/stagewright/migration-log.md` 重合；**删前确认审计记录不依赖它们**
- **导航意图层（9）** — `costmodifier-extraction-a0`、`intentprocess-extraction-a1`、
  `intent-bias-plumbing-a4a`、`intent-bias-modifiers-a4b`、`capability-constraint-a2a`、
  `forbidwater-forbiddig-requiretool-a2bc`、`entity-anchor-leash-a3a`、`shoreline-hug-a3b`、
  `2026-06-28-pathfinding-conformance-loop`
- **其余（13）** — `2026-06-04-perception-decision-boundary`、`2026-06-05-pathfinding-debug-charts`（+ `-E2E-results`）、
  `2026-06-07-server-agent-avatar-phase0-1`、`2026-06-15-path-archive-replay`、
  `2026-07-10-entity-interact-verb`、`2026-07-10-schema-single-source`、
  `2026-07-14-scheduler-semantics-phase1`、`2026-07-15-executor-permove-statemachine-ascend`、
  `2026-07-16-executor-b1-thin-machine`、`2026-07-19-debt-d1-stagewright-instrument`、
  `2026-07-19-debt-d2-engine`、`2026-07-19-task93-config-persistence-shadow-default`

### `specs/` — 13 份

设计 spec，比 plan **更值得留**：plan 讲「怎么一步步做」（做完即废），spec 讲「为什么这么设计」
（代码还在，理由就还有用）。默认 `history`，逐份判。

`2026-06-04-perception-decision-boundary-design` · `2026-06-05-pathfinding-debug-charts-design` ·
`2026-06-07-server-agent-avatar-phase0-1-design` · `2026-06-15-path-archive-replay-design` ·
`2026-06-17-buoyant-water-navigation-design`（**自称 SUPERSEDED，仅留作推理存档**）·
`2026-06-28-pathfinding-conformance-loop-design` · `2026-07-04-llm-navigation-intent-layer-design` ·
`2026-07-09-intent-supervisor-phase-b-design`（自述 DRAFT，**从未落地？待核**）·
`2026-07-10-entity-interact-verb-design` · `2026-07-10-schema-single-source-validation-design` ·
`2026-07-14-scheduler-semantics-phase1-design` · `2026-07-15-executor-permove-statemachine-ascend` ·
`2026-07-16-stagewright-design`（框架已独立成仓库，**该仓库的 `README.md` 是否已取代它？待核**）

### `handoffs/` — 1 份

`2026-07-16-b1-pause-for-test-framework.md` — 一次暂停的恢复点，而那件事早已恢复并完成。
`delete-candidate`。

---

## 还缺什么 — 按优先级

目标结构是两条读者线：`docs/user/`（装了 mod 的人能做什么）与 `docs/dev/`（在库里加东西要知道什么）。
**两个目录目前都不存在。** 建它们的素材大都已在 `AGENTS.md` 与 `README.md` 里，是重组不是从零写。

| # | 事项 | 类型 | 为什么排这里 |
|---|---|---|---|
| **P1** | 修 `AGENTS.md` 的场景数 | 核对 | **今日已核实错**，且这句话历史上错过三次 |
| P2 | `docs/user/transports.md` | 补 | 三 transport 只有 MCP 有 user 文档；RPC 只活在 skill 里，Rhino 一份没有 |
| P3 | `docs/dev/architecture.md` | 补 | `DriverApi` 单一真相 / transport 只翻译 / 写路径过 `server.execute()` |
| P4 | 删除波 1：自称 SUPERSEDED 的 2 份 plan | 删 | 判据无歧义，不需要核实完成状态 |
| P5 | `docs/user/capabilities.md` | 补 | 72 个 `mc.*` 方法按能力分族，让人知道能让 LLM 干什么 |
| P6 | `docs/dev/bot-layering.md` | 补 | `pathfinder` / `Walker` / `process` / `settings` 四层与 3000 行预算 |
| P7 | `docs/dev/adding-a-scene.md` | 补 | 场景与 `expected-scenes-*.txt` 的**同批纪律**（漏了就 `UNDECLARED:` 判红） |
| P8 | `docs/user/troubleshooting.md` | 补 | 端口文件、连不上、bot 不动 |
| P9 | 删除波 2+：stagewright 建设族 14 份 | 删 | 需先核实框架已完成迁移（很可能是，但要验） |
| P10 | 删 `handoffs/2026-07-16-…` | 删 | 单份，判据清楚 |
| P11 | 删除波 3+：导航意图层 9 份 + 其余 13 份 | 删 | 逐份核实，最慢 |
| P12 | `coverage-exemptions.md` 标题去掉 `task#95b` | 核对 | 违反禁编号，顺手 |
| P13 | `scripts/agent-driver-channel.mcp.json.example` 的旧项目名 | 核对 | `agent-driver` 已改名 WorldDriver；文件名与内容都要看 |

### P1 的正确改法

⚠️ **不要填一个新数字。** 这句话已经错过三次（222/171 → 301/250 → 322/271），
因为 `wd.*` 每加一条场景它就旧一次。改成**指向清单**：

> 场景清单是 `scripts/stagewright/expected-scenes-{fabric,neoforge}.txt`——数它，别数这句话。
> 一次运行会多注册十条，那是 StageWright 自己的内建与金丝雀（`Scenes.builtin()`）。

今日实测：两份清单各 **322** 行且逐字节同构（271 `wd.*` + 38 `cap.*` + 13 `pack.*`）。
`AGENTS.md` 现写 222（171 `wd.*`）。

---

## 轮次记录

**2026-08-26｜建账。** 仓库里 87 份跟踪文档全部入账，本轮不改任何其它文件。
顺手核实两件事：场景清单实为 322（`AGENTS.md` 写 222，判 `stale`）；
`mcp-clients.md` 的端口文件名 `worlddriver-{mcp,rpc}.port` 与
`WorldDriverCommon.java` 一致（只抽查了这两行，不足以判 `current`）。

**下一轮：P1** —— 把 `AGENTS.md` 那句场景数改成指向 `expected-scenes-*.txt` 的写法。

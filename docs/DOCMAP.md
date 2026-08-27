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

⚠️ **「我读过」不能当「我核过」。** 这是上一条警告的姊妹条。判 `current` 的判据必须是
**「对照它所描述的那个东西核过」**，不是「读完了没发现语法问题」。
建账当天就栽在这里：`CLAUDE.md` 只有三行，读完两秒，于是被判 `current` 并写下「内容已读，无误」——
而那三行里恰好有一句是假的。**越短的文件越容易免检，正因为读它不费事。**

---

## 一、根级 — 目前事实上的 user / dev 入口

| 路径 | 读者 | 状态 | 最后核对 | 备注 |
|---|---|---|---|---|
| `README.md` | user | **stale** | 2026-08-26 | 英文主页。⚠️「Sandboxed Rhino」是假的：类过滤器默认**关**——见下方 S1 |
| `README-zh_CN.md` | user | **stale** | 2026-08-26 | 中译，同一句假陈述（196 行）；**必须与 `README.md` 同批改** |
| `AGENTS.md` | dev | **stale** | 2026-08-26 | canonical 开发约定。⚠️ 两处：场景数写「222（171 `wd.*`）」实为 **322**（P1）；91 行的 sandbox 硬规则同 S1 |
| `CLAUDE.md` | meta | **stale** | 2026-08-26 | 三行指针。⚠️ 末句「Both files are kept in sync」描述了一个不存在的关系——见下方 P2 |
| `CONTRIBUTING.md` | dev | **stale** | 2026-08-26 | 外部贡献者入口。⚠️ 93 行「Sandbox safety」把默认关的过滤器写成硬性关卡——见 S1 |
| `ROADMAP.md` | dev·history | — | — | N0–N5 里程碑梯 + E1/E2；排期归它 |
| `CHANGELOG.md` | history | **keep** | — | ⛔ 行为变更史，**永不删**（543KB） |
| `TODO.md` | meta | — | — | 主线活工作日志（119KB）。**他人所有，本角色不动** |
| `TODO-janitor-2026-08-22.md` | meta | delete-candidate | — | wd-janitor 的判读日志（127KB）。**归 janitor 处置，本角色只记账** |
| `TODO-rung10-forensics.md` | meta | delete-candidate | — | 一次调查的判读日志；违反「TODO 只放还开着什么」。**他人所有** |
| `TODO-rung20-stand.md` | meta | delete-candidate | — | 同上 |
| `path-replay/README.md` | dev | — | — | replay 工具链用法（record / replay / analyze） |
| `scripts/.claude/skills/worlddriver-rpc/SKILL.md` | user·dev | — | — | ⚠️ 跨平台，Linux 主机也读它——别写 Windows 专用指令 |
| `scripts/.claude/skills/worlddriver-rpc/references/methods.md` | user·dev | **stale** | 2026-08-26 | 72 方法 RPC 参考。⚠️ 296 行 `mc.script.eval` 写「sandboxed…No file/network/reflection」同 S1，且「server thread」与 `ScriptEvaluator` 的 worker 线程相反 |

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

## 二之二、`docs/user/` — 用户线，2026-08-26 起

| 路径 | 读者 | 状态 | 最后核对 | 备注 |
|---|---|---|---|---|
| `docs/user/transports.md` | user | current | 2026-08-26 | 三 transport 的选择表 + 端口 + RPC 线协议 + Rhino 两种入口 + 安全姿态。**逐条对着源码写的**，不是从 `README.md` 抄的 |

⚠️ `docs/mcp-clients.md` **没有**搬进 `docs/user/`：`README.md`、`CONTRIBUTING.md` 与
`docs/claude_desktop_config.example.json` 都按现路径链接它，而那三份都在本角色写权限之外。
`transports.md` 的 MCP 一节只留指针，不复述——**两份 MCP 文档必然分叉**。

## 二之三、`docs/dev/` — 开发者线，2026-08-27 起

| 路径 | 读者 | 状态 | 最后核对 | 备注 |
|---|---|---|---|---|
| `docs/dev/architecture.md` | dev | current | 2026-08-27 | `route()` 单一分发点、两个注册缝、开机 schema 不变式、服务端线程跳转、包图。**逐条对着源码写的**；硬规则只给指针不复述 |
| `docs/dev/bot-layering.md` | dev | current | 2026-08-27 | facade→scheduler→process→Walker→pathfinder 主干与各层的缝。**只写缝与不变式，不写清单**——`bot/` 周周变，列表比没有更糟。`sim/` 只给指针（归 parity 角色） |

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

## 五、`docs/superpowers/` — 51 份，删除的主战场

一次性实施计划与设计 spec。判据是**「这件事做完了没有」**，而做完了的计划应当删除——
它描述的是一段已经走完的路，不是仓库现在的样子。⚠️ **逐份核实，不许整目录清**：
其中有几份仍是唯一记录某个设计推理的地方。

⛔ **「自称 SUPERSEDED」不足以当删除判据——2026-08-27 实测两份两份都有例外条款。**
横幅说「本文作废」，正文里却留着一节写明「这一节仍然有效」：
travel-actuator 留的是给后续 in-place 相位重构的 Slice 0 特征化笔记，
buoyant 留的是 Phase 0 的现场读数。**删之前要把横幅读完，再去核那节的效力是否已兑现**
（前者已兑现 ⇒ 直接删；后者没有 ⇒ 先迁走再删）。

### `plans/` — 38 份

~~自称 SUPERSEDED 的两份~~ **2026-08-27 已删**：`2026-06-17-buoyant-water-navigation.md`
（Phase 0 现场读数已迁入同名 spec）与 `2026-07-15-executor-travel-actuator-extract-A.md`
（Slice 0 笔记的去向 = `WalkerTick*` 相位类已落地，见 `docs/walker-tick-architecture.md`）。

⚠️ 删除留下两处**悬空引用**，均**有意不修**（两个引用者本身都是 `delete-candidate`，
改它们是白工，且 `b1-thin-machine` 那句自带取代理由、悬空也仍然读得懂）：
`plans/2026-07-16-executor-b1-thin-machine.md` 首行的 `Supersedes …` 与
`handoffs/2026-07-16-b1-pause-for-test-framework.md` 第 22 行。

剩下这 38 份 = `delete-candidate`，**完成状态逐份未核实**（14+2+9+13）。按主题分四族，供分波处置：

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
`2026-06-17-buoyant-water-navigation-design`（自称 SUPERSEDED，仅留作推理存档。
⛔ **2026-08-27 起它不只是推理了**：同名 plan 删除时把「Phase 0 findings」的**现场一手读数**
迁进了它的末尾附录——重跑一次 live 才能再得。**删它等于删那份读数，别照「已作废的设计」处置**）·
`2026-06-28-pathfinding-conformance-loop-design` · `2026-07-04-llm-navigation-intent-layer-design` ·
`2026-07-09-intent-supervisor-phase-b-design`（自述 DRAFT，**从未落地？待核**）·
`2026-07-10-entity-interact-verb-design` · `2026-07-10-schema-single-source-validation-design` ·
`2026-07-14-scheduler-semantics-phase1-design` · `2026-07-15-executor-permove-statemachine-ascend` ·
`2026-07-16-stagewright-design`（框架已独立成仓库，**该仓库的 `README.md` 是否已取代它？待核**）

### `handoffs/` — 0 份（空目录）

~~`2026-07-16-b1-pause-for-test-framework.md`~~ **2026-08-27 已删**。逐节核过，无一节仍有效：

- 待办表 **7 项全部兑现**——B1-3 已翻转（`BotConfig.walkerAscendMovement = true`，
  javadoc 写「Default ON since 2026-07-20 (B1-3)」，连 A/B 证据都记在原地）；
  task#84 = `WalkerTick*` 相位类；**task#52「自造测试框架」= StageWright 本身**，已独立成仓库。
- 第 4 节 task#85「套件静默吞测试」——它描述的 **GameTest 通道已在 P4-final 退休**，
  且**结论与细节都已存进 `docs/stagewright/migration-log.md`**（那份是 `keep`）：
  被吞的三份点名为「swallowed trio」并附迁移提交，每一波都对账 `0 swallowed / 0 drifted`，
  取代它的 `GameTestManifest.java` 也记了删除。**存档比手记更全 ⇒ 不需要迁移，直接删。**
- 第三节「恢复时的关键状态」= 一个 `/tick freeze` 冻着的世界、一个 stash、一个分支 tip，
  写给几周前的那一次恢复；彩票失败名单也与今日的三条常驻失败对不上了。

---

## 还缺什么 — 按优先级

目标结构是两条读者线：`docs/user/`（装了 mod 的人能做什么）与 `docs/dev/`（在库里加东西要知道什么）。
**两条线都已开张，各一份**（`user/transports.md` 2026-08-26、`dev/architecture.md` 2026-08-27），
剩下的是把它们填满。素材大都已在源码 javadoc 与 `AGENTS.md` 里，是重组不是从零写。

⛔ **`docs/**` 之外的改动需要那一轮的任务说明明确给出写权限。**
2026-08-26 第二轮与 2026-08-27 的写范围都只有 `docs/**`，而 P1/P2/S1 目标全在根级，
**只能记账不能改**。它们不降级、仍在队首——**S1 已因此连跳两轮**，
下一轮拿到根级写权限就先做它们。

| # | 事项 | 类型 | 为什么排这里 |
|---|---|---|---|
| **S1** | 清掉「Rhino 是沙箱」的假陈述（6 处） | 核对 | **今日已核实假**，且它是**安全**陈述：读者据此决定要不要把端口挪出 loopback |
| **P1** | 修 `AGENTS.md` 的场景数 | 核对 | **已核实错**，且这句话历史上错过三次 |
| **P2** | 修 `CLAUDE.md` 末句的「kept in sync」 | 核对 | **已核实假**；与 P1 同类（正典文档里的假陈述），且只改一句话 |
| ~~P3~~ | ~~`docs/user/transports.md`~~ | 补 | **2026-08-26 已建**，见上方 `docs/user/` 一节 |
| ~~P4~~ | ~~`docs/dev/architecture.md`~~ | 补 | **2026-08-27 已建**，见上方 `docs/dev/` 一节 |
| ~~P5~~ | ~~删除波 1：自称 SUPERSEDED 的 2 份 plan~~ | 删 | **2026-08-27 已删**。⚠️ 当时写的「判据无歧义，不需要核实完成状态」**是错的**——两份都带例外条款，见上方第五节的 ⛔ |
| P6 | `docs/user/capabilities.md` | 补 | 72 个 `mc.*` 方法按能力分族，让人知道能让 LLM 干什么 |
| ~~P7~~ | ~~`docs/dev/bot-layering.md`~~ | 补 | **2026-08-27 已建**，见上方 `docs/dev/` 一节 |
| P8 | `docs/dev/adding-a-scene.md` | 补 | 场景与 `expected-scenes-*.txt` 的**同批纪律**（漏了就 `UNDECLARED:` 判红） |
| P9 | `docs/user/troubleshooting.md` | 补 | 端口文件、连不上、bot 不动 |
| P10 | 删除波 2+：stagewright 建设族 14 份 | 删 | 需先核实框架已完成迁移（很可能是，但要验） |
| ~~P11~~ | ~~删 `handoffs/2026-07-16-…`~~ | 删 | **2026-08-27 已删**，判据确实清楚：待办全兑现、主题（GameTest）已退休、唯一值钱的发现已存进 `keep` 的 migration-log |
| P12 | 删除波 3+：导航意图层 9 份 + 其余 13 份 | 删 | 逐份核实，最慢 |
| P13 | `coverage-exemptions.md` 标题去掉 `task#95b` | 核对 | 违反禁编号，顺手 |
| P14 | `scripts/agent-driver-channel.mcp.json.example` 的旧项目名 | 核对 | `agent-driver` 已改名 WorldDriver；文件名与内容都要看 |

### S1 的证据与正确改法

`ScriptClassFilter.java` 第 23 行：

```java
private static final boolean DISABLED = !"on".equalsIgnoreCase(System.getProperty("worlddriver.sandbox", "off"));
```

⇒ **默认 `off`，`isAllowed` 第一行就 `return true`，整张 denylist 不生效。**
类注释写明这是 2026-07-17 的用户指令（脚本是第一方能力，安全归调用方）。
没有任何 `*.gradle` 设过 `-Dworlddriver.sandbox=on`。

六处受影响，全部在本角色写权限之外，**按危害排序**：

| 位置 | 现文 | 为什么危害是这个次序 |
|---|---|---|
| `mcp/catalog/ScriptTools.java:28` | `Sandboxed (no file/network/reflection)` | **最重**：它是 tool schema，**进每个 LLM 客户端的 prompt**——模型据此判断这个动词安不安全 |
| `README.md:6,220-222` | `sandboxed` / `ScriptClassFilter blocks …` | 主页；读者据此决定要不要开 `rpcHost` |
| `README-zh_CN.md:196` | 同上中译 | 与 `README.md` **同批改** |
| `scripts/.claude/skills/worlddriver-rpc/references/methods.md:296` | `sandboxed … No file/network/reflection` | 顺带：同行的「server thread」也与 `ScriptEvaluator` 的 worker 线程矛盾 |
| `CONTRIBUTING.md:93` | `Any new Rhino-exposed surface must pass through ScriptClassFilter` | 它要求的关卡默认不在路径上 |
| `AGENTS.md:91` | 「Don't widen the Rhino sandbox」硬规则 | 同上；`08_sandbox.js` 断言「必须被拒」而默认放行 |

⚠️ **改法不是把默认翻回 `on`**（那是在改行为，不是改文档，且推翻一条用户指令），
而是让每处都带上默认值：**过滤器存在、默认关、`-Dworlddriver.sandbox=on` 开**。
`docs/user/transports.md` 的 Security 一节已按这个写法落了一份，可直接抄。

⚠️ 还有一条**不是文档问题**，只记不动：`08_sandbox.js` 五条断言「危险类必须抛异常」，
而默认配置下它们不会。它归代码角色，本角色只记账。

### P1 的正确改法

⚠️ **不要填一个新数字。** 这句话已经错过三次（222/171 → 301/250 → 322/271），
因为 `wd.*` 每加一条场景它就旧一次。改成**指向清单**：

> 场景清单是 `scripts/stagewright/expected-scenes-{fabric,neoforge}.txt`——数它，别数这句话。
> 一次运行会多注册十条，那是 StageWright 自己的内建与金丝雀（`Scenes.builtin()`）。

今日实测：两份清单各 **322** 行且逐字节同构（271 `wd.*` + 38 `cap.*` + 13 `pack.*`）。
`AGENTS.md` 现写 222（171 `wd.*`）。

### P2 的正确改法

`CLAUDE.md` 现在的末句是：

> Both files are kept in sync; AGENTS.md is the canonical version.

**前半句假。** 它描述的是「两份互相同步的拷贝」，而实际关系是**指针 → 正典**：
`CLAUDE.md` 三行，`AGENTS.md` 499 行，前者是后者的入口而不是它的副本。
今日实测：`AGENTS.md` 当天被改了三次（`1052cc2a`、`61fe1a1c`、`05dc3299`），
`CLAUDE.md` 最后一次真实改动停在 2026-05-31（`0ca2a5cb`）——
**按那句话的字面意思它此刻就是失同步的；按真实关系它压根不需要同步。**

⇒ 改法是让那半句描述真实关系（指针，没有第二份拷贝），而**不是**去「同步」两份文件。
一句话的改动，别顺手扩写。

---

## 轮次记录

**2026-08-26｜建账。** 仓库里 87 份跟踪文档全部入账，本轮不改任何其它文件。
顺手核实三件事：场景清单实为 322（`AGENTS.md` 写 222，判 `stale`）；
`CLAUDE.md` 末句的「kept in sync」是假陈述（判 `stale`，见 P2）；
`mcp-clients.md` 的端口文件名 `worlddriver-{mcp,rpc}.port` 与
`WorldDriverCommon.java` 一致（只抽查了这两行，不足以判 `current`）。

其中 `CLAUDE.md` 那条是**建账时先判错、当轮被退回改正的**：初判写的是
「内容已读，无误」——读的是它说了什么，没读它说的是不是真的。教训已固化成上面
「我读过不能当我核过」那条规矩，因为三行文件下次还会这样免检。

**2026-08-26 第二轮｜建 `docs/user/transports.md`。** 本轮写范围只有 `docs/**`，
而队首的 P1（`AGENTS.md`）与 P2（`CLAUDE.md`）都在根级 ⇒ **跳过它们做 P3**，二者留在队首不降级。

新文档的每一条都对着源码写，没有一条抄自 `README.md`：
`RpcServer` 的帧形状与 JSON-RPC 码、`TransportLimits` 的 8 MiB（超限是**断连不是报错**）、
`mc.events.subscribe` 的 `allTypes`、`WorldDriverCommon` 的六个 `-D` 与端口文件、
`ScriptEvaluator` 的 3000/30000/64 KiB 与 fresh scope、`ScriptManager` 的字母序共享作用域与
harness extras、`McpServer` 的三个协议版本与 Origin 校验、`/agent` 六条子命令。

⚠️ 顺带查出 **S1**：整棵树把 Rhino 说成沙箱的六处**全是假的**（默认 `off`）。
起因是 `ScriptEvaluator` 的 javadoc 与 `README.md` 的 Design highlights **正面冲突**——
两句话都读得通，冲突本身才是线索。教训与「两个『唯一』互相矛盾」同类：
**各自为真的两句话比一句假话更难被质疑**，所以撞见冲突要去读它们共同描述的那个东西
（这里是 `ScriptClassFilter` 第 23 行），而不是挑一句顺手的信。

⚠️ 还有一个**一开始没查全**的坑：第一次 grep 用的是
`worlddriver.sandbox\|sandbox=on\|SANDBOX`，**大小写敏感且不含裸词 `sandbox`**，
所以只捞到 Java 里的开关、一处散文都没捞到——差点只在新文档里写对、把六处旧散文漏掉。
`-i` 加裸词重跑才出全。**查一个说法散布在哪，别拿实现细节的名字去 grep。**

**2026-08-27｜建 `docs/dev/architecture.md`。** 本轮写范围仍只有 `docs/**`，
S1/P1/P2 全在根级 ⇒ 再次跳过，**不降级**；顺位取 P4，`docs/dev/` 两条读者线至此都开了张。

写之前核了两条**来自散文而非源码**的说法，两条都需要修正：

- 「验证套件断言三条通道字节相同」——机制是真的（`06_rpc_parity.js` /
  `07_mcp_parity.js`），但它比那句话窄：本地 `jsonStable()` 递归排序键之后比字符串、
  先删掉 `uptimeMs` 这类随时间变的字段、且只覆盖 `mc.query` / `mc.system.version` 等
  几个方法，不是全动词面的扫描。文档按实测写法落，没照抄 `AGENTS.md`。
- 「线程外读走 snapshot helpers」——**没有这么一族符号**。实际是 `onServerThread`
  连读带写一起跳；不跳的六个类（`SystemApi`/`WaitApi`/`EventsApi`/`ApiSupport`/
  `ParamsValidator`/`QueryParams`）是真的不碰 level。文档按调用点计数写。

⚠️ 顺带撞见一条**不是文档问题**，只记不动：`RpcServer` 的 Threading javadoc 写
`server.execute() + future.get(30s)`，而 `DriverApi.SERVER_THREAD_TIMEOUT_MS` 默认
**8000 ms** 且注释明写「Lowered from 30s」。产码是对的、注释是旧的，**归代码角色**。

⚠️ 还有一条差点写错：包图里 `test/` 被顺手写成「StageWright harness attachment」，
实为 `ScriptTest` / `TestContext`——`validation/*.js` 调的那个脚本测试壳。
`ls` 一下就翻了。**包的用途别从包名猜。**

**2026-08-27 第二轮｜P5，删除线开张。** 删掉 `plans/` 下自称 SUPERSEDED 的两份，
`docs/superpowers/` 54 → 52。**S1 已由协调方接手，不再挂在本角色名下。**

⚠️ **本轮推翻了台账自己写的删除判据。** 建账时判「自称 SUPERSEDED ⇒ 判据无歧义、
可直接删」，实测**两份都在横幅之外留了例外条款**：

- travel-actuator 留 Slice 0 特征化笔记给后续 in-place 相位重构 ⇒ **已兑现**
  （`WalkerTick*` 十个相位类已落地，其 `aimYaw` 正是笔记点名的跨 tick 字段）⇒ 直接删。
- buoyant 留 Phase 0 现场读数 ⇒ **没兑现**，是重跑 live 才能再得的一手观测 ⇒
  **先逐字迁进同名 spec 的末尾附录再删**（那份 spec 本就被策展为「设计推理存档」，
  读数进去是同一个抽屉）。迁移与删除**同一个提交**，避免读数一度存在于零处或两处。

迁移时补了一句迁移方注解，因为**横幅与它所描述的那一节互相矛盾**：横幅说
Phase 0 findings 是「推翻 D1 的证据」，而那一节自己写着「这反而强化 D1」。
真相是**观测留下、结论作废**——读数写于 06-18 支持 D1，06-19 的诊断把同一批读数
重新解释成「churn 主在执行器层」。裸迁过去会给下一个读者造出一对自洽而互斥的说法。

⚠️ 另：迁移前先 grep 了 `z2744` / `replay-0004` 验「是不是唯一记录」——**结论不是**：
06-19 的**判词**已写在 spec 顶部横幅里，唯一的是**支撑它的那几个数**
（坐标、~56s、y57 振荡、26d4ea2 已编入）。所以这次是**归并读数**，不是抢救孤本。
（`replay-corpus-regression.md` 里的 replay-0004 是后来另一套语料，同名不同事。）

**2026-08-27 第三轮｜P11 + P7。** 删除线先走一步，再补开发者线。

**P11**：删 `handoffs/` 唯一那份（`handoffs/` 现为空目录），逐节核过，判据确实清楚——
待办 7 项全兑现、主题（GameTest）已退休、唯一值钱的第 4 节发现**已存进 `keep` 的
`docs/stagewright/migration-log.md` 且比手记更全**（点名 swallowed trio + 迁移提交 +
每波 `0 swallowed / 0 drifted`）。⇒ **不需要迁移，直接删**。
与上一轮 buoyant 的差别正是那条判据：**先问结论与证据各自存在哪，再决定搬不搬。**

**P7**：建 `docs/dev/bot-layering.md`。写之前核了三条**来自 javadoc 的散文**，三条都有问题：

- `BotApi` 类级 javadoc 写「every method returns immediately with `{started:true}`」，
  而它自己的方法级 javadoc 里有一族明写 `Synchronous`、`no process slot`
  ——**类注释与方法注释互相矛盾**，与上一轮「横幅 vs 正文」同形。文档写「多数异步、
  一族同步」并叫读者 grep `Synchronous`，不复述任何一句。
- `Avatar` javadoc 写「`ServerPlayerAvatar`（neoforge）」，实际它在
  `common/.../bot/sim/`——**loader 归属过时**，没有复述。
- 同一段的「Later phases add `MobAvatar`」是**从未兑现的未来承诺**（`find` 零命中）。
  ⛔ **javadoc 里的未来时一律不进文档。**

⚠️ 这三条都在 `bot/sim/**` 与 `bot/BotApi.java`，**归 parity / 代码角色**，本角色只记账。

⚠️ 写法上定了一条对 `bot/` 专用的规矩：**只写缝与不变式，不写清单**。
`bot/` 是全仓库 churn 最重的子系统，任何「32 个 process」「优先级 1000/900/…」的枚举
下周就旧；所以调度阶梯只说「反射压过深思、前台用户任务垫底」并指向
`BotApiImpl` 的 `register(...)` 块，数字留在代码里。同理预算那条写成不变式
（**`Walker.java` 与 `BotConfig.java` 双双顶在上限 ⇒ 要加必须先抽**）而不是写 2999。

**下一轮：P6（`docs/user/capabilities.md`）**——`docs/dev/` 两份已成对，
用户线却还只有 `transports.md` 一份，缺「能让 LLM 干什么」那张能力表。
想继续推删除线就取 **P10 的 stagewright 14 份**，⛔ 但**必须逐份读正文**：
连着两轮证明例外条款藏在正文中段，横幅判不了。

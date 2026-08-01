# stagewright P4c：Server 巨类 59 名拆 family 四波迁完（legacy 清零）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 legacy 仅存的 `AgentGameTestServer`（59 个 `@GameTest`，4869 行）按主题拆四个 family 波迁为 wd.* 场景并逐波删除，收官时 legacy 套件清零、三个 legacy 类文件（Server/Registrar/Support）全删；GameTestServer 运行机器（run config/run_gametests.sh/gt_reconcile/GameTestManifest/`solo*`）的退役留 P4-final。

**Architecture:** 沿 P4b 波次协议（迁→双 loader ×2 一致→同 commit 删→migration-log→legacy 对账收缩），唯 provider 规则适配：Server 是单类拆 family，故**一主题 family 一 provider**（Station/Scheduler/Survival/Avatar/Process 五个）。三处真 `level.tick()` 雷（`serverForbidDigWallArena:675`/`serverFollowArena:877`/`serverCombatArena:953`，全是「3-tick 索引新实体」小循环=ChunkMap livelock 触发器）集中在最后一波，直译为 W5 已立的 **bounded entity-visibility await**（大声 STEP_TIMEOUT），场景内⛔绝对禁止 `level.tick()`。计数链：59 →T1 44 →T2 33 →T3 15 →T4 0。

**Tech Stack:** 既有 scene DSL + passNote + SceneProvider SPI + t0 --expect-file 对账门 + run_gametests.sh legacy 门（T4 后 vacuous）。

## Global Constraints

- **P4b 全套迁移保真规则原样适用**（常量级直译/required 跟随/显式重定基线留痕/禁静默调阈/⛔场景内 level.tick()/Y-map `origin.y+(legacyY−200)`/chunk-radius 最小性算术/expect-file 双 loader 同 commit/escape-hatch 须 controller 裁决）。
- **legacy 门公式**：`registered==entered==(上轮−本波删数) ∧ 0 swallowed ∧ 失败集空`（Server 存活 family 已空;任何失败=STOP 定性;T4 后套件空,legacy 门以「删除完整性+全 armor」替代）。
- **金字节门照旧**（既有 71 场景含 3 optional 传感器与 agentRpcSmoke 拓扑门,漂移=STOP）;生产 jar 字节门双向断言照旧。
- **已知病历雷**：`underwaterBaseArena`=legacy 挂死惯犯（kill+rm world 自愈病历）——迁移后若在持久 dogfood 世界不确定/挂死风险,如实定性走 escape-hatch 或 optional+立案,不许硬凑;矩阵类（*Matrix*）断言密集但无 walker,直译为主。
- **dogfood 运行时膨胀**（71→130 场景）：每波记录套件墙钟;若单轮显著超时或资源不稳,如实报告（t0 无场景级超时假设,harness within() 逐场景兜底）。
- ⛔pkill;被杀 gametest run 删 world 重试一次;Bash timeout 参数;后台 run 自己轮询（「等通知」早退五犯在案）。

---

### Task 1: Station 波——craft/smelt/recipe/observe 15 名（59→44）

**Files:** Create `common/src/testmod/.../scene/WorldDriverStationScenes.java`;Modify service 文件+双 expect-file(+15)+`docs/stagewright/migration-log.md`(wave-6 表);Delete `AgentGameTestServer` 中 15 名+孤儿 helper。
**名单:** serverCraft / serverRecipeSpecies / serverCraftTableInject / serverRecipeShortfallSpecies / serverCraftTableReclaim / serverObservePlayerInventory / serverPlanHaveDefaultsToBag / serverSmeltCliff / serverCraftTableHoleRim / serverSmeltFurnaceHoleRim / smeltFuelPolicy / serverCraftGridClearHelper / serverCraftGridConservation / serverCraftFailTelemetry / serverCraftFailGridReturn（各 Arena 后缀）。
**注意:** furnace 全状态机病历=手动塞 public containerMenu（#64）;GameTestServer 世界跨 run 持久化假 RED 病历（#40）在持久 dogfood 世界同理——rig 清场必须 ctx.cleanup 全出口。
- [ ] Step 1 逐名直译+A/B journal; Step 2 双 loader ×2 dogfood 一致+既有 71 绿; Step 3 删 15+log+`run_gametests.sh` 对账 44/44 失败集空; Step 4 Commit `feat(testkit): P4c wave 6 — Station family migrated (15 scenes), legacy 59→44`

### Task 2: Scheduler 语义矩阵波——11 名（44→33）

**Files:** Create `WorldDriverSchedulerScenes.java`;其余同波次协议(+11)。
**名单:** retreatGateMatrix / walkerTerminalReportMatrix / antiSuffocateShouldTriggerMatrix / chainEpisodeCancelMatrix / combatGraceMatrix / frailBlockedMatrix / urgentBidMatrix / duskSecureHeldProcessLifecycle / cancelRouting / manualSlotGraceMatrix / nearestFirstScanMatrix（各 Arena 后缀）。
**注意:** 这批是 #54P1 调度语义十任务的回归卫士（hurt-entry/frail 门/DUSK_URGENT90/终态诚实等）——断言密集,矩阵行一条不许丢（评审逐行核）;多为纯逻辑无 walker。
- [x] Step 1-4 同协议,Commit `feat(testkit): P4c wave 7 — Scheduler-semantics matrices migrated (11 scenes), legacy 44→33` — DONE (139 matrix rows one-for-one; dogfood 86→97 ×2×2 byte-identical both loaders; legacy reconcile registered=33 entered=33 0-swallowed GREEN)

### Task 3: Survival+Avatar 波——18 名（33→15）

**Files:** Create `WorldDriverSurvivalScenes.java`(13)+`WorldDriverAvatarScenes.java`(5);其余同协议(+18)。
**名单:** Survival=serverEscape / serverBunker / serverBunkerAnchorRatchet / serverEscapeSealedShelter / serverLowHpEdgePin / serverBunkerSlope / surfaceDive / underwaterBase⚡ / drowningFloatShouldFloatMatrix / drownEscapeGateMatrix / drownEscapePreempt / serverObserveAirSupply / antiSuffocateWaterNotSuffocating;Avatar=serverAgentDistinctBodies / serverAvatarTickFidelity / serverAttackCooldown / serverCapability / serverElytra。
**注意:** underwaterBase=挂死惯犯（病历见 Global Constraints,escape-hatch 候选优先于硬迁）;水柱 rig 居中+air 守护病历;avatar 五名对应 #45/#46/#47 病历=永久断言,阈值一个不动。
- [x] Step 1-4 同协议,Commit `feat(testkit): P4c wave 8 — Survival+Avatar families migrated (18 scenes), legacy 33→15` — DONE (18 scenes: Survival 13 + Avatar 5; dogfood neoforge ×4 + fabric ×2 my-18 all-PASS byte-identical, 115 scenes each; underwaterBase PORTED — pollution-recidivist not inherent, deterministic 52ms/1tick ×6 zero hang; legacy reconcile registered=15 entered=15 0-swallowed GREEN; entityLeash TIMEOUT proven pre-existing task#88 via baseline A/B)

### Task 4: Process 核心波——15 名（15→0）+ 三类全删

**Files:** Create `WorldDriverProcessScenes.java`;Delete `AgentGameTestServer.java` 整类+`AgentGameTestRegistrar.java`+`AgentGameTestSupport.java`（若仍有场景需要的 helper→先收编 `.scene` 再删）;neoforge/build.gradle 的 gameTestServer run 配置**保留**（退役=P4-final）;其余同协议(+15)。
**名单:** serverDriver / serverMine / serverProcess / serverFlee / serverMineProcess / serverMineNoTool / serverWalkerDeepslateNoTool / serverForbidDigWall⚡675 / serverBuild / serverLookRaycast / serverFollow⚡877 / serverCombat⚡953 / serverLook / serverMineCanopyRadius / serverBridgePillarStart。
**注意:** 三处 `for(3) level.tick()` 直译为 bounded entity-visibility await（W5 模式,大声超时）——⛔照抄=持久世界 livelock;serverCombat 受控战斗 rig（白天自燃/清场病历）;删类后 legacy 套件=空,**不跑** run_gametests.sh（vacuous）,以「三类文件不存在+testmod 编译绿+全 armor」替代。
- [x] Step 1-4 同协议,Commit `feat(testkit): P4c wave 9 — Process family migrated (15 scenes), legacy suite emptied (15→0), legacy classes deleted` — DONE (15 Process-core scenes: driver/mine/process/flee/mineProcess/mineNoTool/walkerDeepslate/forbidDigWall⚡/build/lookRaycast/follow⚡/combat⚡/look/mineCanopyRadius/bridgePillar; the three `for(3) level.tick()` mines translated to bounded entity-visibility awaits, before/after in migration-log wave-9; three legacy classes DELETED — `@GameTest(` call sites now ZERO, neoforge testmod source set empty; dogfood neoforge ×2 + fabric ×2 byte-identical AND cross-loader identical GREEN, 130-scene suite, all 15 PASS, zero required failures, no tuning; gate-a build GREEN post-deletion; legacy suite VACUOUS. Count chain closed 59→44→33→15→0)

### Task 5: 验收 + 文档

**Files:** `stagewright/README.md`、`TODO.md`、`docs/stagewright/migration-log.md`(收官)。
- [ ] **Step 1: 五门**（前台有界顺序）：①dogfood 双 loader ×2（130 场景,记录墙钟）结果集一致+金字节+传感器集不变;②`instrument.py` 双 loader 23/23+`t1.py` GREEN（130 场景客户端面）;③生产 jar 字节门双 loader+publishToMavenLocal 复验;④migration-log 计数链 59→44→33→15→0 与 git 对账;⑤testmod 树残余审计=neoforge testmod 应只剩（或不剩）非测试支撑文件,`@GameTest` 全树 0 处。
- [ ] **Step 2: 文档 + Commit** `docs(testkit): P4c — Server families fully migrated, legacy GameTest suite emptied`（README provider 表 8→13+计数;TODO P4c 条目+五门+残余[P4-final=GameTestServer 机器退役清单:gameTestServer run config/run_gametests.sh/gt_reconcile.py/GameTestManifest/AGENT_GT_ONLY/solo* 残留 grep;task#86-88/90-92 照旧;artifactId/merge/远端仓库=user 待决]）

---

## Self-Review（计划自检记录）

1. **覆盖**：Server 59 名四波全落（15+11+18+15=59）;三 tick 雷点名归 T4;退役=P4-final 明示不动;spec §6.3/§7 P4 「剩余测试迁完」至此闭合。
2. **占位符**：无 TBD;五 provider 命名冻结;各波名单逐名列出（与 @GameTest 实点 59 对账）。
3. **一致性**：计数链 59→44→33→15→0;provider 规则适配（单类拆 family=一主题一 provider）已在 Architecture 记录偏差理由;T4 后 legacy 门 vacuous 的替代门显式冻结。
4. **风险入案**：underwaterBase 挂死惯犯=escape-hatch 优先;矩阵断言密集=评审逐行核;dogfood 膨胀墙钟逐波记录;craft/furnace/水柱/战斗 rig 病历逐波点名;Support helper 收编「需要才收」防大搬。

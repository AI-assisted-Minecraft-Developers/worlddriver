# stagewright P4-final：GameTestServer 机器退役（campaign 尾声）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** legacy `@GameTest` 套件已清零（P4c），本 phase 拆除其运行机器：`gameTestServer` run 配置、`run_gametests.sh`/`gt_reconcile.py`、`GameTestManifest`+production 钩子、`AGENT_GT_ONLY`/`solo*` 残留、`.gitignore` 条目、文档终扫。spec §6.6 至此闭合。

**Architecture:** 纯减法 phase，唯一保留裁决：**neoforge `testmod` 源集不删**——它是 common testmod 场景进 neoforge dogfood/stagewrightClient/t2Server 的投递载体（loom mods 挂载+跨模块 classpath），退役只拆 GameTestServer 专属面（run 配置+其 `source sourceSets.testmod` 行）；源集注释改写为「空源桥接载体」角色。`GameTestManifest` 删除=production 代码变更→全 armor。`mc.test.yaml`/`common/test/`（YAML gametest,RPC-route-only）不在退役范围（独立授权形态,spec §3）。

**Tech Stack:** 减法 + 全 armor 验证矩阵。

## Global Constraints

- **全 armor**（production 代码与 build 配置变更）：三模块编译 + dogfood 双 loader（130 场景金字节+3 传感器+拓扑门不变）+ `instrument.py` 双 loader 23/23 + `t1.py` GREEN。
- **生产 jar 字节门双向断言照旧**（测试类零条目+TestResetVerb/TestRunVerb+StageWrightVerbHook 必在）+ publishToMavenLocal 复验。
- **减法完整性=grep 审计**：`GameTestManifest`/`runGameTestServer`/`run_gametests`/`gt_reconcile`/`AGENT_GT_ONLY`/`batch = "solo`/`gametestserver`（大小写不敏感）在**活代码与活文档**中 0 命中（历史档案豁免：migration-log/进度台账/plans/ 历史计划——它们是档案,只加退役注记不改写历史）。
- ⛔pkill;Bash timeout 参数;一次一服务器。

---

### Task 1: 机器退役执行

**Files:**
- Delete: `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/GameTestManifest.java`、`scripts/run_gametests.sh`、`scripts/gt_reconcile.py`
- Modify: `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/WorldDriverNeoForge.java`（删 `GameTestManifest.reset()` 调用+其 gametest 监听残段与注释——留下的 server-starting 钩子若只剩 manifest 用途则整段删）
- Modify: `neoforge/build.gradle`（删 `gameTestServer` run 配置块+其注释;testmod 源集块**保留**,注释改写为空源桥接载体角色——P4c 终审已改过一轮,复核措辞与新现实一致）
- Modify: `fabric/build.gradle:68`（“144 legacy @GameTest classes” 陈旧注释改为 campaign 后真相）
- Modify: `.gitignore`（删 `run-gametest/` 行）、`AGENTS.md`（run_gametests/gt_reconcile 正门描述改为 testkit 正门:t0/t1/t2+instrument）
- 残留 grep 审计（Global Constraints 清单）：活代码/活文档 0 命中,历史档案豁免逐处列表入报告。

**Interfaces:**
- Consumes: P4c 终态（legacy 零测试、13 provider/130 场景）。
- Produces: 无 GameTestServer 机器的树;测试面唯一正门=testkit 编排器族。

- [ ] **Step 1**: 全部删除+改写;`git rm` 走历史。
- [ ] **Step 2**: 全 armor——三模块编译+dogfood neoforge+fabric（各 ×1,金字节+传感器+guard 断言照常）+`instrument.py` 双 loader+`t1.py`。
- [ ] **Step 3**: grep 审计入报告（活面 0 命中证明+豁免清单）。
- [ ] **Step 4**: Commit `chore(testkit): P4-final — GameTestServer machinery retired (manifest, run config, scripts, residue)`

### Task 2: 验收 + 文档收官

**Files:** `stagewright/README.md`、`TODO.md`、`docs/stagewright/migration-log.md`（退役注记）
- [ ] **Step 1: 四门**：①dogfood 双 loader ×1 复验（Task 1 已 ×1,此处再 ×1=每 loader 共 ×2 一致）;②生产 jar 字节门双 loader+`publishToMavenLocal` 复验;③树审计=Global Constraints grep 清单终跑（活面 0）+`@GameTest(` 仍 0+13 provider/130 场景不变;④`./gradlew tasks --all | grep -i gametest` 无 runGameTestServer 任务。
- [ ] **Step 2: 文档 + Commit** `docs(testkit): P4-final — retirement complete, testkit is the sole test gate`（README 删/改 legacy 相关段（历史节保留但标注已退役）;TODO P4-final 条目+四门+campaign 终章统计;migration-log 尾部退役注记（机器清单+commit 指针);残余=task#86-88/90-92+user 待决三项）

---

## Self-Review（计划自检记录）

1. **覆盖**：TODO P4-final 清单六项全落（run config=T1/脚本=T1/Manifest+钩子=T1/残留 grep=T1&T2/gitignore=T1/docs=T2）;spec §6.6 闭合。
2. **占位符**：无 TBD;保留裁决（testmod 桥接载体、mc.test.yaml 面）在 Architecture 冻结并给理由。
3. **一致性**：armor 矩阵与 P4c 终态数字（130/13/3+1）对齐;历史档案豁免规则明确防「改写历史」。
4. **风险入案**：GameTestManifest 删除牵动 WorldDriverNeoForge server-starting 钩子=唯一 production 面,armor 全跑;neoforge testmod 源集误删会断场景投递=Architecture 顶格警告。

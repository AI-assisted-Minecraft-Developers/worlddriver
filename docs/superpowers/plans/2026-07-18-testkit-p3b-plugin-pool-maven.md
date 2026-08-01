# stagewright P3b — gradle-plugin + 客户端进程池 + maven 发布准备 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 spec §7 P3 的余下三件：**gradle-plugin**（`stagewrightServer`/`stagewrightClient`/`stagewrightE2E` 任务，shell 同一编排契约）、**客户端进程池**（跨调用复用 `--hold` 拓扑，冷启 ~30s→attach ~秒级）、**maven 发布准备**（全模块 publishToMavenLocal 可用 + 支持矩阵/兼容承诺文档）。

**Architecture:** gradle-plugin 是**独立 included build**（`stagewright/gradle-plugin`，`pluginManagement.includeBuild` 接入），任务=shell 出 python 编排器（契约=退出码/JSONL/端口文件已冻结,插件不重造编排只透传），配置经 extension（loader/expectFile/额外参数）；进程池=`scripts/stagewright/pool.py`（named topology 的 start/status/stop/ensure,幂等 start=先探活现有 endpoint,attach 流复用 P2c/P3a 端点契约）；发布=复用根 build 既有 maven-publish 管线（loom 模块已就位），补 `stagewright-junit` 自己的 publishing + 全家 publishToMavenLocal 验收 + spec §11 要求的支持矩阵文档。**预期零 Java 运行时改动**（插件是新隔离 build;pool 是 python;发布是 gradle 配置）——任何 testkit/worlddriver 运行时 Java 触碰=STOP BLOCKED。

**Tech Stack:** Java 21 + `java-gradle-plugin`（插件模块,Gradle TestKit 做功能自测）、Python 3 stdlib（pool.py）、maven-publish（既有管线）。

## Global Constraints

- **零运行时 Java 改动**：本计划只允许新增 `stagewright/gradle-plugin`（独立 build,不进游戏 classpath）与 gradle 配置/python/文档。触碰任何游戏运行时 Java→STOP BLOCKED 上报（那需要 armor 全套,不在本 phase 预算内）。
- **契约唯一权威不动摇**：插件任务**只 shell 编排器并透传退出码**（0/1/2/3[/4]),不解析 JSONL 不二次判决;编排器仍是唯一裁决权威（契约 v0 语义）。插件失败消息须含底层命令原文（可复制重跑）。
- **进程池纪律**：pool 管的是 `--hold` 进程（t1.py/t2.py 既有 hold 语义）;PID 追踪,**禁 pkill**;`ensure`=endpoint 存在且 RPC 探活成功→复用,否则清残迹后新起;`stop`=SIGINT hold 进程 PID（既有释放路径）+bounded wait+验证 endpoint 已删;陈旧 endpoint（探活失败）必须先清再起,绝不 attach 到僵尸。
- **发布纪律**：只 publishToMavenLocal（真远端仓库留待用户决策）;POM 清洗规则沿根 build 既有先例（JiJ 依赖不得泄进 POM——docs/feedback/2026-06-04 bug #2 病史）;`stagewright-junit` 的 POM **要**声明 gson/junit-jupiter-api 依赖（它不是 JiJ mod jar,是普通 java 库,消费者需要传递依赖——与 mod jar 的清洗规则相反,理由写进注释）。
- **支持矩阵诚实**：文档写明当前唯一支持 MC 1.21.1 + 两 loader 版本区间（从 gradle.properties 实值抄）,兼容承诺=「仪表契约 v0 冻结面内向后兼容,行为面无承诺」;不许写空头承诺。
- **诚实基线**：验收任何 RED→定性→BLOCKED 上报;插件 TestKit 自测不得用 mock 断言 mock。
- 运维纪律照旧：前台有界（Bash timeout 参数）;live 验收沿用各编排器既有纪律;子代理不留后台任务;hold/pool 进程用后必收。

## File Structure

| 文件 | 责任 |
|---|---|
| `settings.gradle` | `pluginManagement { includeBuild('stagewright/gradle-plugin') }` |
| `stagewright/gradle-plugin/settings.gradle`、`build.gradle` | 独立 build：`java-gradle-plugin` + Java 21;插件 id `net.magicterra.stagewright`;Gradle TestKit 依赖（functional test） |
| `.../src/main/java/net/magicterra/stagewright/gradle/StageWrightPlugin.java` | 注册 extension + 三任务 |
| `.../StageWrightExtension.java` | `loader`（默认 fabric）、`pythonExecutable`（默认 python3）、`scriptsDir`（默认 rootProject 相对 scripts/stagewright）、`expectFile`（可空=编排器默认）、`extraArgs`（list） |
| `.../StageWrightRunTask.java` | 共享基类：组命令→`ExecOperations.exec`→退出码原样透传（非零=任务失败,消息含完整命令行+退出码含义表 0/1/2/3/4） |
| 任务映射 | `stagewrightServer`→`t0.py --loader X`（dogfood 形态参数=run-task/results/expect-file 由 extension 组装,默认=各 loader dogfood 三件套）;`stagewrightClient`→`t1.py --loader X`;`stagewrightE2E`→`t2.py --loader X` |
| `.../src/functionalTest/...` | Gradle TestKit：插件 apply 成功、任务存在、命令组装正确（`pythonExecutable` 指向 stub 脚本回放退出码 0/1/3——断言任务成功/失败语义与消息内容,不跑真游戏） |
| `scripts/stagewright/pool.py`（新） | `pool.py {ensure,status,stop} --topology {t1,t2} --loader {fabric,neoforge}`：ensure=endpoint 探活（复用 junit 侧同款 `mc.system.version` 语义,python 直连 RPC）→活则打印现有 endpoint 并 exit 0（`reused`）,死/无则清残迹→`subprocess.Popen` 对应 `--hold`（detach,log 到 run 目录）→bounded 等 endpoint→exit 0（`started`）;status=各 topology 探活表;stop=SIGINT hold PID→等 endpoint 删→exit 0;self-test（纯逻辑:参数解析/探活判定/残迹清理决策） |
| `stagewright/junit/build.gradle` | `maven-publish`：`mc_stagewright-junit` artifactId,POM 含 gson+junit-jupiter-api 依赖（普通库不清洗,注释写明与 mod jar 规则相反的理由） |
| `stagewright/README.md`、`TODO.md` | gradle-plugin 用法节/进程池节/发布与支持矩阵节;P3b 条目 |

**任务默认参数组装表（写给实现者,T1 冻结）**：
- `stagewrightServer`（loader=L）：`python3 scripts/stagewright/t0.py --loader L --run-task :L:runDogfoodServer --results L/run-dogfood/testkit-results.jsonl --expect-file scripts/stagewright/expected-scenes-L.txt`
- `stagewrightClient`：`python3 scripts/stagewright/t1.py --loader L`（expectFile 设了则透传 `--expect-file`）
- `stagewrightE2E`：`python3 scripts/stagewright/t2.py --loader L`（同上）
- extension.extraArgs 追加尾部;工作目录=rootProject 根。

---

### Task 1: gradle-plugin 模块 + 三任务 + TestKit 自测

**Files:** `settings.gradle`、`stagewright/gradle-plugin/**`（见 File Structure）
**Interfaces:**
- Consumes: t0/t1/t2 CLI 面（已冻结,参数组装表如上）;根 build 的 Java 21 约定。
- Produces（T5 验收与第三方消费,冻结）：插件 id `net.magicterra.stagewright`;apply 后三任务注册于**根项目**;`stagewright { loader = 'neoforge' }` extension;退出码透传语义(非零抛 GradleException,消息含命令原文+码义)。included build 不得拖慢主 build 配置阶段（插件不 apply 时零影响;本 repo 根 build **apply 它**=第一 dogfood 消费者）。
- [ ] **Step 1: 模块骨架 + 插件实现 + TestKit 功能测试**（stub python 脚本回放 0/1/3 三种退出码,断言任务成败与消息;不跑真游戏）
- [ ] **Step 2: 根 build apply + live 验收一发**：`./gradlew stagewrightServer`（默认 fabric dogfood）GREEN 透传 exit 0
- [ ] **Step 3: Commit** `feat(testkit): gradle-plugin — stagewrightServer/stagewrightClient/stagewrightE2E shelling the frozen orchestration contract`

### Task 2: 客户端进程池 pool.py

**Files:** `scripts/stagewright/pool.py`（新）、`docs/stagewright/orchestration-contract-v0.md`（pool 附录）
**Interfaces:**
- Consumes: t1.py/t2.py `--hold` 语义与 endpoint 契约（P2c/P3a）;探活=`mc.system.version` 一发（沿 junit attach 语义）。
- Produces: `pool.py ensure --topology t2 --loader fabric` 幂等（活→`reused` 秒回;死→清→新起→`started`）;`status`（全拓扑探活表）;`stop`（SIGINT→endpoint 删验证）;JUnit/instrument_client 工作流=先 `pool.py ensure` 再 attach（README 双工作流示例）;冷启 vs 池命中计时对比记录。
- [ ] **Step 1: 实现 + self-test**（纯逻辑单测:决策表 alive/stale/absent→reuse/clean+start）
- [ ] **Step 2: live 验收**：t1 池 ensure(started)→ensure(reused,计时对比)→instrument_client --attach GREEN→stop(endpoint 删);t2 池同流程一遍
- [ ] **Step 3: Commit** `feat(testkit): client process pool — pool.py ensure/status/stop over the hold+endpoint contract`

### Task 3: testmod source-set 惯例（插件承载）

**Files:** `stagewright/gradle-plugin/**`（`StageWrightTestmodConvention` 或 extension 开关）、functional test、`stagewright/README.md`（惯例节）
**Interfaces:**
- Consumes: Task 1 插件骨架。
- Produces: `stagewright { testmodSourceSet = true }`（默认 false,零影响）→在 apply 的项目注册 `testmod` source set（main 的 compileClasspath+runtimeClasspath 依赖 main 输出）,并把 `testmod` 输出目录暴露为 extension 只读 property 供消费者自行接入其 loom run config（**插件不自动改 loom run**——loom 版本差异大,v1 只立源集惯例+文档示例,自动接线留插件 v2;此边界写进 README）。**worlddriver 自身 130 legacy 测试的迁移不在本任务**（挂账 testmod拆分照旧）。
- [ ] **Step 1: 实现 + TestKit 功能测试**（synthetic 消费者项目:开关 off 零源集/on 有 testmod 且 classpath 正确）
- [ ] **Step 2: Commit** `feat(testkit): testmod source-set convention — opt-in registration, loom wiring left to consumers (v1 boundary)`

### Task 4: maven 发布准备

**Files:** `stagewright/junit/build.gradle`、（如需）`stagewright/{common,fabric,neoforge}/build.gradle`、`stagewright/README.md`（支持矩阵+兼容承诺节）
**Interfaces:**
- Consumes: 根 build 既有 subprojects maven-publish 管线（loom 模块自动有;POM 清洗+GenerateModuleMetadata 禁用先例）;gradle.properties 实值（mod_version/maven_group/minecraft_version/loader 区间）。
- Produces: `./gradlew publishToMavenLocal` 全家成功（common/fabric/neoforge 的 worlddriver 与 testkit 六件 + `mc_stagewright-junit`）;`~/.m2` 中各 artifact 存在且 junit 件 POM **含** gson+junit-jupiter-api、mod 件 POM **不含**任何依赖（两条相反规则各就各位）;消费者解析冒烟（临时目录里最小 gradle 项目 mavenLocal 拉 `mc_stagewright-junit` 编译一个 import StageWright 的类——TestKit 或 shell 皆可）;README 支持矩阵表（MC 1.21.1、fabric loader ≥0.16.14、neoforge 21.1.230 区间、Java 21）+兼容承诺（仪表契约 v0 冻结面向后兼容;行为面/内部 API 无承诺;版本随 mod_version）。
- [ ] **Step 1: junit publishing + 全家 publishToMavenLocal + ~/.m2 断言（POM 两规则各验）**
- [ ] **Step 2: 消费者解析冒烟 + 支持矩阵文档**
- [ ] **Step 3: Commit** `feat(testkit): maven publish readiness — junit publication, local-publish acceptance, support matrix`

### Task 5: 验收 + 文档

**Files:** `stagewright/README.md`、`TODO.md`
- [ ] **Step 1: 五门验收**（前台有界,顺序）：①`./gradlew stagewrightServer -Pstagewright.loader=…` 或 extension 设 neoforge——**插件路径**跑 dogfood neoforge GREEN（六字节金值照常由场景断言）;②`./gradlew stagewrightClient`（fabric T1）GREEN——插件路径;③pool 流程：`pool.py ensure --topology t2`→`instrument_client --topology t2 --attach` GREEN→`pool.py ensure` 秒级 `reused`→`pool.py stop`;④`./gradlew publishToMavenLocal` 全家+消费者冒烟（Task 4 复验一发）;⑤插件 TestKit 功能测试 + pool self-test + 既有 t1/t2/instrument_client self-test 全绿
- [ ] **Step 2: 文档 + Commit** `docs(testkit): P3b — gradle plugin, client process pool, maven publish readiness`（README 三新节收口互链;TODO P3b 条目+五门记录+残余[插件 v2=loom run 自动接线;真远端仓库=user 决策;worlddriver testmod 拆分挂账照旧;task#86-88/90;JUnit 并行 fork]）

---

## Self-Review（计划自检记录）

1. **覆盖**：spec §7 P3 余三件全落（gradle-plugin shell 同一契约→T1;testmod source set 惯例→T3 有界版=惯例+文档,loom 自动接线明示留 v2,worlddriver 自迁移明示挂账;客户端进程池→T2,建立在 P2c/P3a hold+endpoint 契约上;maven 发布准备→T4 只 mavenLocal,真远端=user 决策,spec §11 支持矩阵/兼容承诺落文档）;P4 之前 spec P3 行全清。
2. **占位符**：无 TBD;任务参数组装表冻结在 File Structure;POM 两条相反规则（mod 清洗 vs 库声明）各给了理由与验收断言。
3. **一致性**：插件 id/extension 键在 T1 定义、T3 扩展、T5 门①②消费;pool 的 ensure/status/stop 在 T2 定义、T5 门③消费;`stagewright-junit` artifactId 沿根 build `archives_name-project.name` 惯例=实现者按现值核对（不臆写）。
4. **风险入案**：included build 拖慢配置阶段→T1 明示验收边界;stub-python TestKit 测试=不跑真游戏（真游戏在门①②）;pool attach 僵尸风险→探活先行+陈旧必清纪律;发布件 POM 泄 JiJ 依赖病史（2026-06-04 bug #2）→T4 逐件断言;`stagewright-junit` 在 subprojects 剖出块外=其 publishing 全自配（不吃根管线,实现者须自验 group/version 继承自 allprojects）。

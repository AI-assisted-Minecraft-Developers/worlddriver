# stagewright P4a：testmod 目的地落地（测试代码搬出生产 jar）+ 已迁 twins 首删

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 144 个 legacy `@GameTest`（neoforge/src/main 八类 + Support）与 9 个 wd.* 场景（common/src/main 的 WorldDriverScenes/SimProbes + SceneProvider service）全部搬出生产 jar 进 testmod source set，dev 跑法零回归；随后执行「迁一批删一批」首删（已迁 wd.* 的 legacy twins 退场）。

**Architecture:** 目的地先行——P4 后续 family 迁移波直接落 testmod，避免二次搬家。搬家顺序解依赖：先 legacy（neoforge testmod，SimProbes 仍在 common main 可编译），再场景（common testmod + neoforge testmod 跨模块 source-set 依赖收编 SimProbes 委托），再 fabric 对齐。生产 verb（TestResetVerb/TestRunVerb + StageWrightVerbHook service）**留 main**——mc.test.* 无条件转发是 P3a 裁决的产品面。P3b 插件的 testmodSourceSet flag 只在 root 生效（apply 到 :common 会拖进三编排任务），故各模块**手工注册**同名 `testmod` source set（同一惯例，不同接线；plan 裁决记录）。

**Tech Stack:** architectury-loom 1.11-SNAPSHOT（fabric + loom.platform=neoforge 双侧同 API）；loom `mods {}` block source-set 注入 dev 运行 classpath/scan；ServiceLoader（SceneProvider 服务文件随场景移动源集）。

## P4 波次地图（本 plan = P4a）

- **P4a（本 plan）**：testmod 目的地 + legacy/场景两侧搬出生产 jar + 已迁 twins 首删。
- **P4b+**：family 迁移波（Terrain 20 → Bias 14 → WaterBank 12/WaterCross 11 → 主类 15 → CombatSense/BuildBlock 6 → Server 巨类 64 拆 family），每波=迁一批→A/B→删一批，直接落 testmod。
- **P4-final**：GameTestServer 与 `solo*` batch 机制退役、run_gametests.sh 正门切换（spec §6.6，全量迁完后）。

## Global Constraints

- **金字节门（漂移=STOP）**：descentYaw `871°/53`；selfShaftDigUp 签名 PASS（`worstBackslide=20.252203415101263`）；gearScope `bare=0.94000053 sword=5.9040003 ATTACK_SPEED=1.5999999046325684 ATTACK_DAMAGE=6.0`。
- **全 armor**（classpath 重构必跑）：三模块编译 + neoforge/fabric dogfood 字节门 + `instrument.py --loader {neoforge,fabric}` 23/23 + `t1.py`（fabric）；触碰 t2Server 接线的任务加 `t2.py`。
- **legacy 门**：`scripts/run_gametests.sh` 唯一正门；对账 reconcile 必须 0 吞；当前 HEAD 确定性失败集=已归档 5 名（gearscope/descentyaw/vine/selfshaftdiguparena/entityleashrepatharena），搬家后失败集必须**逐名相同**（=零行为漂移证据），删除后按删名收缩并如实记档。
- **生产 jar 字节门（本 phase 新增）**：remapped 产物 jar 内不得含 `AgentGameTest*`、`WorldDriverScenes`、`SimProbes` class 及 `META-INF/services/net.magicterra.stagewright.scene.SceneProvider` 条目（`unzip -l` grep 断言）；`TestResetVerb`/`TestRunVerb` 与 `StageWrightVerbHook` service **必须仍在**。
- ⛔pkill 禁用（ps 列候选→显式 PID 杀）；被杀 gametest run 必删 world 再重试一次；用 Bash timeout 参数不用 sleep 外壳。
- 诚实基线：RED→定性→BLOCKED，禁自裁调门；重定基线必留痕。
- 迁移雷：不得机械搬运手动 `level.tick()`（本 phase 只搬源集不改测试体，天然不触雷；见 spec §10）。

---

### Task 1: neoforge testmod source set + 144 legacy 测试搬家

**Files:**
- Modify: `neoforge/build.gradle`（注册 `testmod` source set：compile/runtime classpath extends main output+classpaths；`mods {}` block 把 testmod 注入 dev run 的 mod classpath/scan；`gameTestServer` run 配置吃到 testmod）
- Move: `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/AgentGameTest{,Terrain,Server,Bias,WaterBank,WaterCross,CombatSense,BuildBlock,Support}.java` → `neoforge/src/testmod/java/net/magicterra/worlddriver/neoforge/`（同包不改名）
- Modify: `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/WorldDriverNeoForge.java`（删除 `event.register(AgentGameTest*.class)` 七行及其 gametest 监听——注册缝随搬家移入 testmod：testmod 内新建 `AgentGameTestRegistrar`，`@EventBusSubscriber` 承接 `RegisterGameTestsEvent`，逐类 register 照抄）

**Interfaces:**
- Consumes: 现 `runGameTestServer`/`run_gametests.sh` 正门；AgentGameTestSupport 对 `net.magicterra.worlddriver.bot.stagewright.SimProbes` 的三处委托（SimProbes 本 task **仍在 common main**，跨源集可见性不变）。
- Produces: `sourceSets.testmod`（neoforge）+ testmod 注册缝类名 `AgentGameTestRegistrar`（Task 2 的跨模块依赖锚点）。

- [ ] **Step 1**: 注册 testmod source set + mods block 注入；八类+Support 移动源集（`git mv`，同包）；WorldDriverNeoForge 删注册行；testmod 建 `AgentGameTestRegistrar`。
- [ ] **Step 2**: `./gradlew :common:build :fabric:build :neoforge:build` 三模块编译过；remapped neoforge jar `unzip -l` 断言无 `AgentGameTest` 条目。
- [ ] **Step 3**: `scripts/run_gametests.sh` 全量——reconcile 0 吞、注册数不变、失败集逐名==已归档 5 名（搬家零行为漂移证据；任何新名/少名=STOP 定性）。
- [ ] **Step 4**: Commit `refactor(testkit): P4a — legacy GameTests out of production jar into neoforge testmod source set`

### Task 2: common testmod source set + 场景搬家 + neoforge 接线

**Files:**
- Modify: `common/build.gradle`（注册 `testmod` source set，classpath extends main + `:stagewright-common` namedElements）
- Move: `common/src/main/java/net/magicterra/worlddriver/bot/stagewright/{WorldDriverScenes,SimProbes}.java` → `common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/`；`common/src/main/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider` → `common/src/testmod/resources/META-INF/services/`
- Keep in main: `TestResetVerb.java`/`TestRunVerb.java` + `META-INF/services/net.magicterra.stagewright.StageWrightVerbHook`（生产 verb，动它=STOP）
- Modify: `neoforge/build.gradle`（testmod compile/runtime classpath += `:common` 的 testmod output——SimProbes 三处委托恢复编译；`runDogfoodServer`/`stagewrightClient`/`t2Server` run 配置吃到 common testmod output+resources；`contractServer` **不吃**——仪表契约不依赖场景）

**Interfaces:**
- Consumes: Task 1 的 neoforge `sourceSets.testmod`；`Scenes.all()`=builtin+ServiceLoader providers（服务文件跟着场景走源集即注册面不断——对账门验证）。
- Produces: common `sourceSets.testmod`（Task 3 fabric 接线消费同一 output）。

- [ ] **Step 1**: common testmod 注册+场景/SimProbes/service 文件移动；neoforge testmod 跨模块依赖 + 三 run 配置接线。
- [ ] **Step 2**: 三模块编译；两侧 remapped jar 断言：无 `WorldDriverScenes`/`SimProbes`/`SceneProvider` 条目，`StageWrightVerbHook` service + 两 verb class **仍在**。
- [ ] **Step 3**: neoforge 面验收——dogfood `t0.py --loader neoforge ...` 9/9 GREEN 金字节门 + `instrument.py --loader neoforge` 23/23 + `run_gametests.sh` 失败集仍逐名==5 名（SimProbes 走 testmod 后 legacy 零漂移）。
- [ ] **Step 4**: Commit `refactor(testkit): P4a — wd.* scenes out of production jar into common testmod, neoforge wiring`

### Task 3: fabric 接线对齐 + 全 armor 矩阵

**Files:**
- Modify: `fabric/build.gradle`（testmod 消费：`runDogfoodServer`/`stagewrightClient`/`t2Server` 吃 common testmod output+resources，`contractServer` 不吃；机制与 neoforge 同 loom API，fabric 侧差异如实记档）

**Interfaces:**
- Consumes: Task 2 的 common `sourceSets.testmod` output。
- Produces: 双 loader dev 跑法全量恢复（P4b 迁移波的运行地基）。

- [ ] **Step 1**: fabric 三 run 配置接线。
- [ ] **Step 2**: fabric remapped jar 字节门（同 Task 2 断言）。
- [ ] **Step 3**: 全 armor 矩阵——fabric dogfood 9/9 金字节 + `instrument.py --loader fabric` 23/23 + `t1.py`（fabric T1 真客户端）GREEN + `t2.py`（fabric，t2Server 接线被动过）GREEN + neoforge dogfood 复验一发。
- [ ] **Step 4**: Commit `refactor(testkit): P4a — fabric testmod wiring, full armor matrix green`

### Task 4: 迁一批删一批首删——已迁 wd.* 的 legacy twins 退场

**Files:**
- Modify: `neoforge/src/testmod/java/.../AgentGameTest*.java`（删 twin 方法及其孤儿私有 helper；类空则整类删+Registrar 除名）
- Create: `docs/stagewright/migration-log.md`（迁移日志：每删一名记 legacy 名→wd.* 场景名→删除 commit→当时失败集变化；spec §10「漂移记录进迁移日志」的落点）

**Interfaces:**
- Consumes: 9 个 wd.* 场景 javadoc 里引用的 legacy 起源名 + SDD 台账 P1c/P1.5a/P1.5b 迁移记录（twin 清单**从档案推导**，不臆写；wd.settingRegistryClosed 是新场景无 twin，如实记 0 删）。
- Produces: 清单入 migration-log；legacy 注册数收缩后的新失败集档案（P4b 波次的基线）。

- [ ] **Step 1**: 从场景 javadoc+台账推导 twin 清单（含 5 确定性失败名中的 twin 成员），列表先写进 migration-log 再动刀。
- [ ] **Step 2**: 删 twins；编译；`run_gametests.sh` 全量——reconcile 0 吞、注册数按删名收缩、失败集=5 名减去被删者（预期显著收缩；留名者如实定性：task#87 等 legacy-only 环境病**不因删 twin 而闭案**，engine 挂账照旧）。
- [ ] **Step 3**: dogfood 双 loader 复验（wd.* twins 金字节仍绿=删除未碰迁移件）。
- [ ] **Step 4**: Commit `chore(testkit): P4a — delete migrated legacy twins (migrate-then-delete wave 1), migration log`

### Task 5: 验收 + 文档

**Files:** `stagewright/README.md`、`TODO.md`

- [ ] **Step 1: 四门验收**（前台有界顺序）：①生产 jar 字节门双 loader（无测试类/有生产 verb 条目，`unzip -l` 存档入报告）；②`run_gametests.sh` 全量（收缩后注册数+失败集与 migration-log 一致）；③dogfood 双 loader + `instrument.py` 双 loader 23/23 + `t1.py` GREEN（金字节全中）；④`./gradlew publishToMavenLocal` 复验（发布件同样不含测试类——maven 面与 build 面同证）。
- [ ] **Step 2: 文档 + Commit** `docs(testkit): P4a — testmod destination, production-jar hygiene, twin deletion wave 1`（README testmod 节从「惯例 v1」升级为「worlddriver 实接线记录」+ 生产 jar 字节门写进验收惯例；TODO P4a 条目+四门记录+残余[P4b 波次地图照搬;task#86-88/90 照旧;GameTestServer/solo* 退役=P4-final]）

---

## Self-Review（计划自检记录）

1. **覆盖**：spec §6.3「搬出生产 jar 进 testmod」两侧全落（legacy=T1、场景=T2/T3）;§6.3「迁一批删一批」首删=T4;§7 P4 三件中「剩余测试迁完」留 P4b+ 波次地图、「退役」留 P4-final——本 plan 明示有界。
2. **依赖序**：T1 先（SimProbes 仍在 main，legacy 可编译）→T2（SimProbes 入 testmod 同时给 neoforge testmod 跨模块依赖）→T3 fabric——SimProbes 委托断裂窗口为零。
3. **占位符**：无 TBD;twin 清单不臆写=T4 Step 1 从档案推导先记后删;loom mods-block 双侧差异容许实现者按 API 现实取机制,门=对账+金字节（机制自由、验收冻结）。
4. **风险入案**：注册缝迁移（event.register→testmod @EventBusSubscriber）=T1 最大风险,验收=注册数不变+失败集逐名同;fabric loom 差异=T3 单独成任务;contractServer 不吃 testmod=仪表契约独立性有意保持;生产 verb 留 main=P3a 裁决,jar 门反向断言其存在防误删。

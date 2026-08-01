# stagewright P4b：family 迁移波（非 Server 家族 63 名全量迁 testkit 场景 + 逐波删 twin）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 legacy 套件中 Server 巨类之外的 63 个 `@GameTest`（Terrain 13 / Bias 13 / WaterBank 11 / WaterCross 10 / 主类 12 / CombatSense 2 / BuildBlock 2）按 family 全量迁为 wd.* testkit 场景（直接落 common testmod），每波迁一批→A/B→删一批；收官后 legacy 只剩 Server 59 名（P4c 拆家族啃，P4-final 退役）。

**Architecture:** 每波自含（迁→双 loader ×2 轮字节一致→同 commit 删 twin→legacy 对账收缩→migration-log 行），失败可逐波回滚。一个 legacy 类对应一个新 SceneProvider 类（provenance 清晰、WorldDriverScenes 不再膨胀），service 文件逐行追加（重名门+对账门防吞）。63 名全部 tick-free（手动 `level.tick()` 4 处全在 Server 巨类，本 phase 不碰）——直译雷主要是 GameTestHelper 惯用法→SceneContext/await 语义与持久世界 teardown。

**Tech Stack:** 既有 scene DSL（Scene builder/withOriginSlot/withChunkRadius/withRequired/SceneContext.cleanup LIFO）+ SceneProvider ServiceLoader + t0 --expect-file 对账门 + run_gametests.sh legacy 门。

## Global Constraints

- **迁移保真规则（P1c/P1.5 惯例的成文化）**：常量级保真直译；同步有界循环可原样进 body；`GameTestHelper.succeed/fail/assert*` → 场景断言等价物；**禁止机械搬运任何 `level.tick()`**（本波次目标类实测 0 处，出现即 STOP）；createUnique 每场景唯一身体，driver 机器件用 createIsolated+定向 cleanup（P1.5b driver 模式）；pinnedBaseline+ctx.cleanup 全出口排水；origin 默认 auto-slot，只有确定性要求时钉槽（钉即记录理由）。
- **required 语义跟随 legacy**：legacy required→场景 required=true；legacy optional 及已档 solo-RED/彩票成员（vineoverwaterclimb、deepwatercross 家族）→`withRequired(false)`+javadoc 引 task/病历（P1.5a optional 治理规则：必须引编号+进阶段验收重审名单）。
- **壳更换时序漂移（spec §10）**：迁移按 family 新旧壳 A/B；新壳指标漂移允许**显式重定基线**，逐名记 migration-log；⛔禁止为凑绿调阈值不留痕。新场景确定性门=同 loader 连续 2 轮 dogfood 结果集(name,result)逐名一致 ×2 loader；不稳者如实定性（optional+立案），不许调绿。
- **淘汰豁口**：某 legacy 测试判定过时/不可直译→**不许静默丢**，migration-log 记 retired-without-scene+理由，须 controller 裁决后才删。loader 专属 API 的测试→loader-local provider（service 文件在该 loader testmod resources，全树仍每 provider 一份），逐案记录。
- **expect-file 双 loader 同 commit 更新**（P1.6 规则）；场景名=ad.<由 legacy 名派生的驼峰名>，映射以 migration-log 行为准。
- **legacy 门公式（P4a 重构后）**：`registered==entered==(上轮值−本波删数) ∧ 0 swallowed ∧ failures ⊆ 文档 family 存活成员`；被删名复现或 family 外新名=STOP/BLOCKED。计数链：122 →T1 109 →T2 96 →T3 75 →T4 59。
- **金字节门照旧**（既有 9 场景 PASS=命中,漂移=STOP）;⛔pkill;被杀 gametest run 删 world 重试一次;Bash timeout 参数;后台跑 run_gametests 自己轮询(「等通知」早退=病,三犯在案)。
- **生产 jar 字节门**：每波 commit 后 remapped 双 jar 零新增测试类条目（新 provider 类都在 testmod=自动满足,断言防手滑）。

---

### Task 1: Terrain 13 迁移波

**Files:**
- Create: `common/src/testmod/java/net/magicterra/worlddriver/bot/testkit/scene/WorldDriverTerrainScenes.java`
- Modify: `common/src/testmod/resources/META-INF/services/net.magicterra.stagewright.scene.SceneProvider`（追加一行）、`scripts/stagewright/expected-scenes-{neoforge,fabric}.txt`（+13 名,同 commit）、`docs/stagewright/migration-log.md`（wave-2 表）
- Delete(同 task 尾): `neoforge/src/testmod/.../AgentGameTestTerrain.java` 全类（13 名全迁即空壳,连 Registrar 除名若在列）

**Interfaces:**
- Consumes: 既有 scene DSL/SimProbes/AgentGameTestSupport 直译源（Support 的共享 helper 逐个判定：场景需要的收编进 `.scene` 共享 helper 或 SimProbes,不 import 跨源集残留）。
- Produces: 每波复用的**波次协议**（后续 Task 2-4 逐字同构）：①逐名直译+A/B ②双 loader ×2 轮 dogfood 一致 ③同 commit 删 twin+log 行 ④legacy 对账 122→109。

- [ ] **Step 1**: 逐名直译 13 场景（journal 记每名 A/B：legacy 行为→场景断言→首跑结果→基线裁决）；expect-file+service 行同 commit。
- [ ] **Step 2**: 门=neoforge dogfood ×2 轮结果集逐名一致 + fabric ×2 同 + 既有 9 场景金字节仍绿。
- [ ] **Step 3**: 删 AgentGameTestTerrain 13 twin（或全类）+migration-log 行；`run_gametests.sh` 对账 109/109 0 吞。
- [ ] **Step 4**: Commit `feat(testkit): P4b wave 2 — Terrain family migrated (13 scenes), legacy twins deleted (122→109)`

### Task 2: Bias 13 迁移波

同 Task 1 波次协议逐字同构。Files: Create `WorldDriverBiasScenes.java`;Delete `AgentGameTestBias.java` twins。注意:Bias 家族含 driver 机器件（21 处 Support 引用最密）→driver 模式 createIsolated+定向 cleanup。计数 109→96。
- [ ] Step 1 直译+A/B; Step 2 双 loader ×2; Step 3 删+对账 96; Step 4 Commit `feat(testkit): P4b wave 3 — Bias family migrated (13 scenes), legacy twins deleted (109→96)`

### Task 3: Water 双家族 21 迁移波（WaterBank 11 + WaterCross 10）

同波次协议。Files: Create `WorldDriverWaterBankScenes.java`+`WorldDriverWaterCrossScenes.java`（一 legacy 类一 provider）;Delete 两类 twins。注意:本波含已档彩票/optional 成员（vineoverwaterclimb −711 live bug、deepwatercross 家族）→required 语义跟随+withRequired(false)+javadoc 引病历,不许调绿;水域 rig 记 [[reference_arena_void_fall_rig_disease]] 三件套与水柱居中病历。计数 96→75。
- [ ] Step 1 直译+A/B; Step 2 双 loader ×2; Step 3 删+对账 75; Step 4 Commit `feat(testkit): P4b wave 4 — Water families migrated (21 scenes), legacy twins deleted (96→75)`

### Task 4: 主类 12 + CombatSense 2 + BuildBlock 2 = 16 迁移波

同波次协议。Files: Create `WorldDriverCoreScenes.java`（主类 AgentGameTest 12 名,含 RPC/YAML 面）+`WorldDriverCombatScenes.java`+`WorldDriverBuildScenes.java`;Delete 三类 twins（CombatSense/BuildBlock 全类删,主类若全迁亦删,Registrar 相应除名）。注意:主类含 runValidation()/RPC 面（agentRpcSmoke 的同族但 rpcsmoke 本体在 Server=P4c）;threatScan 双名需受控敌对生成（白天自燃防护病历）。计数 75→59。
- [ ] Step 1 直译+A/B; Step 2 双 loader ×2; Step 3 删+对账 59; Step 4 Commit `feat(testkit): P4b wave 5 — Core/Combat/Build families migrated (16 scenes), legacy twins deleted (75→59)`

### Task 5: 验收 + 文档

**Files:** `stagewright/README.md`、`TODO.md`、`docs/stagewright/migration-log.md`（收官段）

- [ ] **Step 1: 五门验收**（前台有界顺序）：①dogfood 双 loader 全量（9+63=72 场景）×2 轮结果集一致+金字节;②`run_gametests.sh` 对账 59/59 0 吞（只剩 Server,失败⊆family 存活成员）;③`instrument.py` 双 loader 23/23+`t1.py` GREEN（72 场景膨胀后 T1 面不回归）;④生产 jar 字节门双 loader+publishToMavenLocal 复验;⑤migration-log 计数链 122→59 与四波 commit 对账+「加场景」单点 how-to 补入 README（P4a 终审挂账）。
- [ ] **Step 2: 文档 + Commit** `docs(testkit): P4b — non-Server families fully migrated (63 scenes), legacy reduced to Server-only (59)`（README 场景库结构节[七 provider 分家族]+TODO P4b 条目五门记录+残余[P4c=Server 59 拆 family 含 4 处 level.tick() 雷与 agentrpcsmoke;P4-final 退役;task#86-88/90 照旧]）

---

## Self-Review（计划自检记录）

1. **覆盖**：spec §6.3「130 按 family 迁 @SceneTest」余 122 中非 Server 63 名全落本 plan;Server 59（含 4 tick 雷+livelock 惯犯 serverForbidDigWall+agentrpcsmoke）明示留 P4c=有界;退役=P4-final 不动。
2. **占位符**：无 TBD;波次协议在 T1 全文冻结,T2-4 逐字同构引用（同构≠省略:各波 Files/注意/计数逐一写明）;直译逐名细节属实现者 journal+migration-log,plan 冻结规则与门。
3. **一致性**：计数链 122→109→96→75→59 四波连贯,与 per-family 实数（13/13/11+10/12+2+2）对账;provider 一类一档与 service 追加行一致;expect-file 双 loader 同 commit 规则全波适用。
4. **风险入案**：dogfood 膨胀 9→72 场景=运行时增长（×2 轮门顺带测出;若单轮超时,t0 无场景级超时假设须如实报）;Water 波彩票成员=required 治理最重一波;主类 RPC 面直译可能撞 loader 差异→淘汰豁口/loader-local provider 逐案;Support 共享 helper 收编=每波「需要才收」防一次性大搬。

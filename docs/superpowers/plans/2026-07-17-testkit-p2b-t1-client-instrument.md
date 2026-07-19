# mc-testkit P2b — T1 拓扑 + 客户端仪表扩展 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 T1 拓扑（xvfb 真客户端进单机世界跑 testkit 场景）与客户端仪表契约扩展（#41/#45/#55 永久断言、setting 未知键 live E2E、mc.test.reset 行为验证与复用完备性），并顺手关 task#89。

**Architecture:** T1 壳 = fabric 客户端 JVM 带 `-Dtestkit.autorun=true`：integrated server 开世界即触发既有 `TestkitCommon.onServerStarted`（两 loader 事件钩子都是通用 server 生命周期，P2b 零 harness 改动预期），9 个 ad.* 场景原样跑在 integrated server 上=拓扑证明。编排器 `t1.py` 负责 xvfb、gradle 客户端启动、端口文件发现、GUI 进世界驱动（复用 `into_world.py`/`react_smoke.py` 的 widget 树点击库）、世界模板 copy→run→delete、done footer 收割。客户端仪表检查为独立 `instrument_client.py`（instrument.py 的 client 版：裸 RPC 打 in-world 客户端）。**范围决策**：T1 先 fabric-only（唯一有实证客户端工具链的 loader，neoforge 客户端 parity 单列后续）；in-game 形态的 UI 场景（@SceneTest 体内做屏幕断言）不在本阶段——client 断言经编排器/仪表面完成，避免场景体内跨线程阻塞（执行模型铁律），in-game UI 授权形态随 P2c/JUnit5 再议。两决策写进 TODO 偏差声明。

**Tech Stack:** xvfb（DISPLAY 管理,smoke-test-client.sh 先例但**禁 pkill**——PID 显式管理）、websockets（rpc.py/react_smoke 既有）、loom fabric client run 配置。

## Global Constraints

- **T0 回归照旧武装**：任何触碰 common/编排器共享代码的任务收尾必跑 neoforge dogfood 六字节指标门（descentYaw 871°/53;selfShaftDigUp worstBackslide=20.252203415101263 签名门 PASS;gearScope 0.94000053/5.9040003/1.5999999046325684/6.0）。
- **T1 世界纪律**（spec §5 制度化）：模板世界一次生成缓存（gitignored 目录），每 run copy→enter→run→**删副本**;世界名固定 `TestkitT1`;禁复用脏世界（持久世界污染病史）。
- **客户端进程纪律**：编排器全程 PID 追踪,退出路径=quit-to-title→窗口关闭或显式 PID kill;**禁 pkill**（smoke-test-client.sh 的 pkill 行不是可抄先例）;GL-hang 签名自愈=TitleScreen 停留超时即按 widget 点击穿透（spec §5 行,into_world 已有 wait 逻辑）;`agent-rpc.port` 端口文件发现,不 hardcode 39801。
- **仪表面纪律**：所有客户端检查裸 RPC 直打 route()（MCP 层缓存 stale schema 会静默丢键——#280 病史,验证新 key 必走裸 RPC）;setup 只用 instrumentation-grade verb（tp/give/runCommand/input）,禁 goto/walker。
- **金丝雀纪律**：instrument_client.py 与 t1.py 各自带必红/必超时哨兵,金丝雀误判=DEAD 整轮作废（既有契约 v0 语义）。
- **诚实基线**：T1 首跑任何场景/检查 RED→×3 确定性定性→BLOCKED 上报裁决,禁自裁调门/标 optional;fabric-only 与 UI-scene 延后两决策必须作为偏差声明进 TODO,不许写成"完成了 P2 全部"。
- 运维纪律照旧：前台有界（Bash timeout 参数）;子代理永不带后台任务收束;挂死删 run 世界/副本重跑一次;水中禁暂停等 live 规则不适用（无 live bot 会话）。

## File Structure

| 文件 | 责任 |
|---|---|
| `fabric/build.gradle` | `testkitClient` run 配置（testkit.autorun=true + ephemeral 端口 + run-t1 目录;configureEach 守卫名单 +`testkitClient`） |
| `scripts/testkit/t1.py`（新） | T1 编排器:xvfb 起停（自管 DISPLAY,PID 追踪）、gradle testkitClient 启动、端口文件轮询、GUI 进世界（import react_smoke/into_world 的 helper 或抽小库 `guidrive.py`）、模板世界生命周期、done footer 收割、exit code 语义与 t0 相同、self-test |
| `scripts/testkit/guidrive.py`（新,如抽库） | 从 react_smoke/into_world 抽出的可复用 widget-树驱动函数（find/click/wait_until/world-select）——两脚本原文不动,库为增量 |
| `scripts/testkit/instrument_client.py`（新） | 客户端仪表契约:in-world 前置探针+检查族（下表）+2 金丝雀;`--rounds N` 支持复用完备性 |
| `docs/testkit/instrument-contract-v0.md` | client 附录:检查清单+T1 拓扑语义+reset 完备性协议 |
| `common/.../AgentDriverCommon.java` | task#89:requireSchemasFor+setParamsValidator 前移至 RpcServer 构造**之前**（保 hard-fail-outside-catch 性质,详 Task 4） |
| `mc-testkit/README.md`、`TODO.md` | T1 节+P2b 条目+两偏差声明 |

**instrument_client.py 检查族**（每条=一个 check,命名沿 instrument.py 风格）：
- `t1.inWorld`：mc.client.player 有位置且 mc.observe.player 可读（attach 门已过）
- `obs.fullInventory`（#41 永久断言）：/give 填充若干槽后 observe.player.items 报 36 槽全景（server 权威）
- `obs.attackCooldown`（#45）：AttackSnap 字段在 observe 快照中存在且 attack 后变化（用 mc.bot.attackEntity 打一只 summon 的 armor_stand 或直接读 snap 结构——实现者按 AttackSnap 实际面选最小验证,报告论证）
- `obs.damageSource`（#55）：/damage @p 2 后 player.hurt 事件/lastDamageSource 带真源归因（非 HP 差分）
- `route.settingUnknownKeyLive`：裸 RPC mc.bot.setting{definitelyNotAKnob:true} 在**真客户端**上= validator unexpected-key 大声错（#280 live E2E,P2a 欠账）
- `route.settingKnownKeyLive`：mc.bot.setting{autoEat:...} 正常应用+回读一致（closed schema 不误伤已知键）
- `reset.behavior`：input 打开背包 screen→mc.test.reset→assert screen 关+按键释放（client.player 输入态/screen.info）+chat 清（client.message history 空）
- `canary.mustFail`/`canary.mustTimeout`
（清单允许实现者按实际可测性微调增删,删任何一条须报告论证=待控制器裁决,不许静默缩水。）

---

### Task 1: T1 壳 — testkitClient 配置 + t1.py 编排器 + 场景跑通

**Files:** `fabric/build.gradle`、`scripts/testkit/t1.py`（新）、`scripts/testkit/guidrive.py`（新,建议抽库）
**Interfaces:**
- Consumes: into_world.py/react_smoke.py 的 title→世界驱动逻辑;TestkitCommon.onServerStarted（integrated 上天然触发,零改动预期——若实测不触发=BLOCKED 上报,禁在 harness 侧偷改）;expected-scenes-fabric.txt（9 名照用）。
- Produces: `python3 scripts/testkit/t1.py --wall 900`（默认 fabric;--expect-file 同 t0 语义;exit 0/1/2/3 同契约）;世界模板缓存目录 `scripts/testkit/.t1-world-template/`（gitignored,首跑经 GUI 创建 TestkitT1 后归档,此后每 run 拷入 saves/ 用毕删）。

- [ ] **Step 1: gradle 配置**（镜像 dogfoodServer 形态:autorun property/vmArg、ephemeral 端口、run-t1 目录;fabric configureEach 守卫加名）
- [ ] **Step 2: t1.py**（xvfb 自起自收[PID 记账,占用检测复用或新起 DISPLAY]→gradle 后台启动+前台有界轮询→port file→GUI 进世界[模板存在=拷贝后直选;不存在=GUI 创建一次并归档模板]→integrated server 起=autorun 场景自动跑→轮询 run-t1 的 testkit-results.jsonl done footer→verdict 复用 verdict.py→退出路径 quit/kill+删世界副本;TitleScreen 停留自愈;self-test 若干条纯函数）
- [ ] **Step 3: 首跑定性**（GREEN=9 场景在 integrated 上全过+金丝雀判定正确;任何 RED→×3→BLOCKED 报告）
- [ ] **Step 4: Commit** `feat(testkit): T1 topology — fabric client shell under xvfb, template world lifecycle, scenes on integrated server`

### Task 2: instrument_client.py 客户端仪表契约

**Files:** `scripts/testkit/instrument_client.py`（新）、`docs/testkit/instrument-contract-v0.md`（client 附录）
**Interfaces:**
- Consumes: t1.py 的客户端启动/进世界（抽公共函数或 t1.py 提供 `--hold` 模式:进世界后不跑场景保持在线,供检查脚本打）;rpc.py 线格式;检查族清单（File Structure 表）。
- Produces: `python3 scripts/testkit/instrument_client.py --wall 900`（自起 T1 客户端或 `--attach` 复用在线客户端;9+2 检查;JSONL+exit code 同 instrument.py 语义）。

- [ ] **Step 1: 实现检查族**（staging 全 instrumentation-grade;#45 验证面选择写报告;每检查幂等——staging 残留自清）
- [ ] **Step 2: 双跑确定性**（连续两轮 GREEN 且逐检查结果一致）
- [ ] **Step 3: Commit** `feat(testkit): client instrument contract — #41/#45/#55 permanent assertions, #280 live E2E, reset behavior`

### Task 3: 复用完备性 — reset ×3 轮 + 弃用重启降级

**Files:** `scripts/testkit/instrument_client.py`（--rounds）、`docs/testkit/instrument-contract-v0.md`（协议节）
**Interfaces:** Produces: `--rounds 3`:同一客户端进程,轮间 quit-to-title→重进世界（guidrive 复用）→mc.test.reset→重跑全检查;三轮逐检查结果一致=复用完备;任何轮间漂移→报告差异表+BLOCKED（残留=reset 缺口=P2 风险册主险种,修 reset 而非放宽）;`--fresh-process` 降级路径实现并文档化（弃用重启:杀进程重启新客户端,慢但洁净）。

- [ ] **Step 1: rounds 实现 + 3 轮实测**（漂移→BLOCKED;稳定→记录三轮耗时对比[复用 vs 冷启的收益数字]）
- [ ] **Step 2: Commit** `feat(testkit): client-pool reuse acceptance — 3-round reset completeness + discard-restart fallback`

### Task 4: task#89 — validator 前移

**Files:** `common/.../AgentDriverCommon.java`
**Interfaces:** Consumes: 终审记录的约束——catalog 在 api==null 块（含 TestResetVerb.register）后已完整;`requireSchemasFor` 失败必须仍是**启动即死**（不被 rpcServer 构造的 try/catch 吞掉——先核对现 try/catch 边界再搬,若语句本就在 catch 外则直接前移,若在内则搬出时保持异常传播语义,报告贴前后代码）。
- Produces: 监听开始前 validator 已装=boot 窗口无校验派发关闭;时序注释更新。

- [ ] **Step 1: 搬移 + 注释**
- [ ] **Step 2: 回归门**（3 模块编译+neoforge dogfood 六指标+instrument.py neoforge 21 检查——改的是 boot 热序,全套武装）
- [ ] **Step 3: Commit** `fix(api): install params validator before RpcServer listens (task#89 boot-window close)`

### Task 5: 验收 + 文档

**Files:** `mc-testkit/README.md`、`TODO.md`
- [ ] **Step 1: 六门验收**（前台有界,顺序）:①t1.py GREEN（9 场景 integrated）;②instrument_client GREEN;③instrument_client --rounds 3 GREEN;④instrument.py neoforge+fabric GREEN（21 检查,task#89 后回归）;⑤dogfood neoforge GREEN（六字节指标）+dogfood fabric GREEN;⑥legacy 全量诚实入档（白名单外零新名）
- [ ] **Step 2: 文档 + Commit** `docs(testkit): P2b — T1 topology, client instrument contract, reuse acceptance, task#89 closed`（README T1 节+契约 client 附录链接+TODO 条目含两偏差声明[fabric-only T1;in-game UI 场景随 P2c 再议]+residuals）

---

## Self-Review（计划自检记录）

1. **覆盖**：spec §7 P2 行的 T1 拓扑→T1;仪表契约客户端扩展+进场重置 verb 验收→T2/T3;首批 UI 场景→**偏差**（客户端断言经仪表面完成,in-game UI 授权形态延 P2c,理由=场景体跨线程阻塞铁律,写 TODO）;JUnit5→P2c;task#89→T4;风险册"客户端进程池状态残留"→T3 全条目。
2. **占位符**：无 TBD;#45 验证面与检查族微调是"实现者论证+控制器裁决"下放,验收兜底=不许静默缩水;t1.py 内部结构给了完整流程链,代码级细节依赖既有 into_world/react_smoke 源（行号级锚定留给实现者读源,plan 引用文件名+函数职责）。
3. **一致性**：t1.py 的 --hold/--attach 在 T1 定义、T2 消费;guidrive 抽库 T1 建、T3 复用;TestkitT1 世界名/模板目录名全文一致;9 场景=P2a 后 fabric 清单现值。
4. **风险入案**：integrated server 上 onServerStarted 若不触发=BLOCKED（不偷改 harness）;GUI 驱动脆弱性（widget 布局漂移）→guidrive 走 label 匹配非坐标（into_world 先例,smoke-test-client 的坐标法不是先例）;xvfb 与既有 live 客户端 DISPLAY 冲突→t1 自起独立 DISPLAY;客户端启动慢→wall 900 上限+模板复用;task#89 搬移吞异常风险→Step 1 显式核对 try/catch 边界;client JVM 下 tick 债 catch-up（task#88 同款）可能放大 entityLeash await 波动→within(120) 已有余量,首跑定性覆盖。

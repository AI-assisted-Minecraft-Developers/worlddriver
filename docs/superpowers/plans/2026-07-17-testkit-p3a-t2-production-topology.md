# mc-testkit P3a — T2 生产拓扑（专服 + 真客户端）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec §3 的 **T2 dedicated+client 生产拓扑**（SurvivalTest 同构）：真客户端连入专用服务器，双进程编排（t2.py）、双 RPC 仪表面（server+client 两 socket）、客户端在场的场景执行（`mc.test.run` 按需触发 verb——P2a registerVerb SPI 的首个 testkit 自消费）、T2 上的仪表契约与 attach/复用验收。

**Architecture:** t2.py 起专服（新 `t2Server` run config，autorun 默认 OFF，专服世界模板 mint→copy-per-run→删副本）→ 起客户端（复用 `testkitClient` config）→ guidrive 新增 multiplayer 直连步（title→Multiplayer→[警告屏]→Direct Connection→地址→Join）→ 双端口文件发现（server=run-t2、client=run-t1 各自 `agent-rpc.port`）→ 场景经 server RPC `mc.test.run` 在**客户端在场**时触发 → 仪表契约走双 socket Ctx（server 面 staging/observe + client 面 screen/input/reset）。P3b（gradle-plugin+进程池+maven 准备）另计划。

**Tech Stack:** Python 3 stdlib（t2.py/guidrive/instrument 增量）、Java（仅 mc-testkit runtime：`mc.test.run` verb，**零 agent-driver Java**）、既有 verdict.py/契约 v0。

## Global Constraints

- **T0 回归照旧武装**：本计划触碰 mc-testkit runtime Java（`mc.test.run`）→ 每个动 Java 的任务收尾必跑 neoforge dogfood 六字节指标门（descentYaw 871°/53;selfShaftDigUp worstBackslide=20.252203415101263 签名门 PASS;gearScope 0.94000053/5.9040003/1.5999999046325684/6.0）+ instrument.py neoforge 21 检查。**零 agent-driver（非 testkit）Java 改动**；若必须触碰→STOP BLOCKED 上报。
- **双进程纪律**：server+client 两 JVM 全程 PID 追踪;**禁 pkill**;两端 `agent-rpc.port` 端口文件发现（server=`<loader>/run-t2/`、client=`<loader>/run-t1/`）,不 hardcode 39801;teardown 顺序=client 先(quit→PID kill)、server 后(SIGTERM→bounded wait→PID kill),两世界副本/端点文件全出口清理。
- **T2 世界纪律**：专服世界模板一次 mint（干净 boot→worldReady→干净停→archive `run-t2/world`）→copy-per-run→**finally 删副本**;客户端无世界（连远端）。spec §11「T2 世界模板两端一致性」= 文档声明项（本 phase 单机同 jar 两端天然一致,写明即可）。
- **仪表面纪律**：一切检查裸 RPC 直打 route();server 面（observe/action/query/wait）打 server socket,client 面（mc.client.*/mc.test.reset）打 client socket;setup 只用 instrumentation-grade verb,禁 goto/walker。
- **`mc.test.run` 纪律**：经 `ToolCatalog.registerVerb` 配对注册（mc.test.* 已授 testkit,P2a SPI 首个 testkit 自消费）;幂等性=套件已跑/在跑时大声拒（返回 error,非静默重跑）;JSONL done footer 仍是唯一完成信号（verb 返回只表「已受理」）。
- **金丝雀纪律**：T2 仪表契约与场景门各自带哨兵,误判=DEAD 整轮作废。
- **诚实基线**：T2 首跑任何场景/检查 RED→×2/×3 定性→BLOCKED 上报裁决,禁自裁调门;客户端在场的 golden 值**预期**与 T0 逐位同（物理全在 server 侧）,若有漂移=发现,如实定性入档,不许改金值。
- 运维纪律照旧：前台有界（Bash timeout 参数）;挂死删副本重跑一次;子代理不留后台任务;多屏驱动只许 label 匹配禁坐标。

## File Structure

| 文件 | 责任 |
|---|---|
| `fabric/build.gradle`、`neoforge/build.gradle` | `t2Server` run config（镜像 dogfoodServer 但 `testkit.autorun` 由 `-Pt2Autorun` 门控默认 false、runDir `run-t2`、ephemeral 端口、configureEach 守卫名单+`t2Server`） |
| `<loader>/run-t2/server.properties` | `online-mode=false` + 固定 `server-port`（fabric=25599 先例;neoforge 另选不冲突口）——检入或首跑生成后钉住,t2.py 读它拿连接地址 |
| `mc-testkit/common/.../TestkitCommon.java`（或 harness 所在类,实现者读源定位） | `mc.test.run` verb：registerVerb 配对注册;触发与 autorun 同一套件路径;幂等大声拒;autorun=false 时 SERVER_STARTED 只备档不跑 |
| `scripts/testkit/t2.py`（新） | T2 编排器：t2Server 启停（世界模板 mint/copy/删）、testkitClient 启停（Xvfb 复用 t1 helpers）、guidrive 多人直连、双端口发现、`mc.test.run` 触发+footer 收割、verdict 复用、`--hold`（写 endpoint topology=`dedicated_plus_client`）、exit 0/1/2/3(+4 多轮)、self-test |
| `scripts/testkit/guidrive.py` | 新增 multiplayer 直连驱动函数（label 匹配：Multiplayer→警告屏 Proceed（如出现）→Direct Connection→地址框 typeText→Join Server;连接中/断线屏侦测） |
| `scripts/testkit/instrument_client.py` | `--topology {t1,t2}`（默认 t1）：t2 时双 socket Ctx（`ServerCtx`+`ClientCtx`）,7 检查按面分派（#41/#45/#55 的 observe 打 server 面=专服 PlayerList 真玩家;screen/input/reset 打 client 面）;`--rounds` 在 t2=quit→重连 server→reset→重跑 |
| `scripts/testkit/expected-scenes-{fabric,neoforge}.txt` | 复用（T2 期望=同 9 场景） |
| `docs/testkit/orchestration-contract-v0.md` | T2 附录：双进程启停协议、双端口发现、`mc.test.run` 语义、endpoint topology 枚举+`serverRpcPort` 扩键(v1 向后兼容=新键可选) |
| `mc-testkit/README.md`、`TODO.md` | T2 节+P3a 条目+两端一致性声明+残余 |

**已核事实（写给实现者）**：fabric `run-dogfood/server.properties` 已有 `online-mode=false`、`server-port=25599`;dogfoodServer config 两 loader 已存在（autorun 硬 true——所以要新 t2Server 而非复用）;guidrive 现无 multiplayer 步;harness 现只在 SERVER_STARTED 布防（`mc.test.run` 因此是本 phase 唯一 Java 增量）;client 连专服后 client-mod RPC 只有 client 面,server 面在 server-mod RPC——双 socket 是拓扑本质非实现选择。

---

### Task 1: `mc.test.run` 按需触发 verb（runtime Java 唯一增量）

**Files:** mc-testkit runtime harness 类（实现者读源定位 SERVER_STARTED 布防点）、`scripts/testkit/instrument.py`（新 headless 检查钉 verb 契约）
**Interfaces:**
- Consumes: P2a `ToolCatalog.registerVerb(schema, handler)`（mc.test.* 命名空间已授）;既有 autorun 套件执行路径（Scenes.all→执行→JSONL→done footer）。
- Produces（T3 消费,冻结）：server RPC `mc.test.run` `{}` → `{accepted:true, scenes:N}`;套件已跑过/在跑 → error envelope（大声拒,幂等）;`testkit.autorun=false` 时 SERVER_STARTED 仅记录「armed, awaiting mc.test.run」不执行;autorun=true 行为逐位不变（全部既有 T0/T1 路径零感知）。dedicated 与 integrated 两拓扑同语义。
- 新 instrument.py 检查（headless 钉契约）：`route.testRunPaired`（tools/list 无此 hidden verb+schema 配对存在——沿 18-21 先例）、`route.testRunIdempotent`（帯 autorun 服跑完后调 mc.test.run=大声 error 非重跑）。

- [ ] **Step 1: 读源+实现**（布防点改造+registerVerb 注册+幂等闩;注释写明 autorun/on-demand 双路径汇合点）
- [ ] **Step 2: 契约检查**（instrument.py 21→23,双 loader headless GREEN）
- [ ] **Step 3: 回归门全套**（三模块编译+neoforge dogfood 六字节门+fabric dogfood GREEN——autorun 路径零感知的实证）
- [ ] **Step 4: Commit** `feat(testkit): mc.test.run on-demand suite trigger — registerVerb SPI self-consumption, idempotent, autorun-path untouched`

### Task 2: t2.py 双进程拓扑壳 + multiplayer 直连驱动

**Files:** `fabric/build.gradle`、`neoforge/build.gradle`、`<loader>/run-t2/server.properties`、`scripts/testkit/t2.py`（新）、`scripts/testkit/guidrive.py`
**Interfaces:**
- Consumes: t1.py 的 Xvfb/launch/stop/kill/LoaderPaths 先例（抽共享 helper 或 import t1,不 fork 拷贝）;guidrive connect/Rpc/discover_port;dogfoodServer config 形态。
- Produces: `t2Server` run config（`-Pt2Autorun` 默认 false→`testkit.autorun=false`）;`python3 scripts/testkit/t2.py --loader {fabric,neoforge}`：mint（无模板时干净 boot 专服→`mc.wait.worldReady`→SIGTERM 干净停→archive `run-t2/world` 为 `.t2-world-template-<loader>`）→scored（copy 模板→起服→起客户端→多人直连→**双端探针**（client `mc.client.player` 有 pos + server `mc.observe.player` present=专服 PlayerList 真玩家）→本任务先到此=拓扑壳 GREEN）;guidrive 新函数 `drive_multiplayer_connect(rpc, address)`（label 匹配全程;多人警告屏如出现按其确认钮）;teardown=client 先 server 后、副本删、双端口文件清;self-test（含 t2 路径解析、server.properties 端口读取）。
- 风险预授权：多人警告屏/断线屏 label 未知→截屏(xwd)/screen.tree 定性后加 label 步;专服 boot 到 worldReady 慢→wall 预算 900 复用;fabric 先行,neoforge 端口另选（25598,写入其 server.properties)。

- [ ] **Step 1: run config + server.properties + t2.py 骨架 + guidrive 直连步 + self-test**
- [ ] **Step 2: fabric live 首跑定性**（mint→scored 双端探针 GREEN ×2）
- [ ] **Step 3: neoforge live**（×1 GREEN;撞 loader 差异→定性入档 BLOCKED 报告,增量照 commit）
- [ ] **Step 4: Commit** `feat(testkit): T2 topology shell — t2.py dual-process orchestration, multiplayer direct-connect drive`

### Task 3: T2 场景执行 + attach 端点

**Files:** `scripts/testkit/t2.py`、`docs/testkit/orchestration-contract-v0.md`
**Interfaces:**
- Consumes: Task 1 `mc.test.run`;Task 2 拓扑壳;expected-scenes 清单;verdict.py;P2c endpoint 契约（schema v1）。
- Produces: t2.py scored 全流程=客户端直连**在场后**经 server RPC 调 `mc.test.run`→轮询 `run-t2/testkit-results.jsonl` done footer→verdict+对账门（`--expect-file` 复用）;金值断言=与 T0 同三大件（**预期逐位同,漂移=发现入档**);`--hold`=场景不跑、写 endpoint（`topology:"dedicated_plus_client"`、`rpcPort`=**client** RPC、新可选键 `serverRpcPort`）;JUnit UI 三场景+双金丝雀 attach 到 t2 --hold 客户端跑通（不改 Java 测试,它们只打 client 面）;契约 T2 附录（双进程协议+topology 枚举+serverRpcPort 扩键）。
- [ ] **Step 1: 接线+契约附录+self-test**
- [ ] **Step 2: fabric live**：场景 ×2 金值比对 + `--hold`+JUnit live 全绿（`TESTKIT_ENDPOINT=... ./gradlew :testkit-junit:test`）
- [ ] **Step 3: Commit** `feat(testkit): T2 scene execution over mc.test.run + dedicated_plus_client attach endpoint`

### Task 4: T2 仪表契约（双 socket）+ 复用验收

**Files:** `scripts/testkit/instrument_client.py`、`docs/testkit/instrument-contract-v0.md`
**Interfaces:**
- Consumes: Task 2/3 的 t2.py `--hold`;instrument_client 既有 7 检查+2 金丝雀+rounds 机制;P2b reset 语义。
- Produces: `--topology {t1,t2}`（默认 t1,零回归）:t2 时 attach 双 socket（client 端口文件+server 端口文件各连一 Ctx）,检查按面分派——`t1.inWorld`→`t2.inWorld`（双端探针）;#41/#45/#55 staging(`give`/`damage`/`item replace`)与 observe 打 **server** 面（专服 PlayerList 真玩家=P1b headless 缺口在生产拓扑闭环）;#280 live E2E 打 **client** 面 route（client 面才有 mc.bot.setting）;reset.behavior 打 client 面;金丝雀照旧;`--rounds 3` 在 t2=quit-to-title→**重连专服**（服务器不重启=真进程池语义雏形）→reset→重跑,三轮一致门+BLOCKED 语义照 P2b;`--fresh-process` 降级=杀客户端重启重连（服务器仍不动）。
- [ ] **Step 1: 双 socket Ctx+分派+rounds 适配+self-test**（t1 路径回归零感知）
- [ ] **Step 2: fabric live**：`--topology t2` GREEN ×2 + `--topology t2 --rounds 3` GREEN（轮间服务器常驻=复用收益记录）+ t1 默认路径回归 GREEN
- [ ] **Step 3: Commit** `feat(testkit): T2 instrument contract — dual-socket faces, dedicated-PlayerList permanent assertions, reuse rounds with resident server`

### Task 5: 验收 + 文档

**Files:** `mc-testkit/README.md`、`TODO.md`
- [ ] **Step 1: 六门验收**（前台有界,顺序）：①t2.py fabric 场景门 GREEN（金值）;②t2.py neoforge 场景门 GREEN;③instrument_client `--topology t2` GREEN+`--rounds 3` GREEN;④t2 `--hold`+JUnit UI 全绿;⑤T0/T1 全矩阵回归（dogfood 双 loader 六字节门+instrument.py 双 loader 23 检查+t1.py fabric GREEN——`mc.test.run` 改动后的全套 armor）;⑥instrument_client t1 默认路径回归 GREEN
- [ ] **Step 2: 文档 + Commit** `docs(testkit): P3a — T2 production topology, mc.test.run, dual-socket instrument, resident-server reuse`（README T2 节含两端一致性声明与拓扑对照表;TODO P3a 条目+六门记录+残余[P3b=gradle-plugin/进程池/maven 准备;task#86-88/90 照旧]）

---

## Self-Review（计划自检记录）

1. **覆盖**：spec §3 T2 拓扑（专服+真客户端/server-client avatar 缝/生产同构）→T2 壳+双 socket 仪表;spec §7 P3 的 T2 项全落,gradle-plugin/进程池/maven 准备明示切给 P3b（进程池的「服务器常驻跨轮」语义雏形在 T4 rounds 落地）;spec §11 世界模板两端一致性→文档声明项;P2a SPI 自消费（mc.test.run=registerVerb 首个 testkit 消费者）=产品闭环证明。
2. **占位符**：无 TBD;multiplayer 屏 label 未知处=预授权定性流程（截屏/tree→加 label 步）而非猜值;`mc.test.run` 布防点改造指向「实现者读源定位」+回归门兜底,不臆写行号。
3. **一致性**：`mc.test.run` T1 定义/T3 消费同名同语义;`--topology` T4 定义与 T5 门③⑥消费一致;endpoint `serverRpcPort` 扩键=可选键向后兼容（Endpoint.java 现 8 键必填——**T3 实现者须让 junit 侧容忍未知键或加可选解析,报告说明,不得破坏 v1 必填 8 键**);run-t2/run-t1 双 runDir 端口文件不冲突;t2 世界模板命名 `.t2-world-template-<loader>` 沿 P2c gitignore 通配（`.t1-world-template-*` 需扩为 `.t?-world-template-*` 或并列一行——T2 任务落地时同步 .gitignore,P2c T4 教训）。
4. **风险入案**：双 JVM 内存（server 2g+client 2g,与既有 live 客户端并存=探空闲 DISPLAY+ephemeral 端口已隔离,内存由 wall 预算+串行纪律控）;多人直连在 dev 环境的认证（online-mode=false 已核）;client quit 后专服残留假玩家/avatar 状态跨轮污染→T4 rounds 一致性门就是探测器,漂移=发现走 BLOCKED;SIGTERM 专服不落盘风险→mint 用干净停+archive 验证 level.dat 存在;`mc.test.run` 幂等闩防双触发;JSONL footer 在 on-demand 路径必须照发（T1 Step 3 fabric dogfood 回归即证）。

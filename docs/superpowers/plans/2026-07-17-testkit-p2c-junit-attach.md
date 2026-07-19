# mc-testkit P2c — JUnit 5 attach 模式 + 首批 in-game UI 场景 + neoforge 客户端对等 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec §3② 的进程外 JUnit 5 授权形态（attach 模式契约 `TESTKIT_ENDPOINT`），用它承接 P2b 偏差声明(2)的首批 in-game UI 场景，并补齐偏差声明(1)的 neoforge 客户端 T1 对等。

**Architecture:** 编排器（`t1.py --hold`）起好拓扑后写出**端点描述文件**并打印 `TESTKIT_ENDPOINT` 环境变量提示；新增纯 JVM 模块 `mc-testkit/junit`（零 MC 依赖，Java 21 内建 `java.net.http.WebSocket`）提供 attach 入口 + 裸 RPC 客户端 + 最小类型化门面 + 阻塞式 await（进程外形态 spec 明许阻塞）；首批 UI 场景以 JUnit `@Test` 写成，打在 `--hold` 拓扑上，金丝雀纪律以 JUnit 原生断言语义承载（必红=assertThrows 包真失败断言；必超时=微 within 必然 TimeoutException）。neoforge 对等 = `:neoforge:runTestkitClient` run config + `t1.py --loader neoforge` 泛化 + 9 场景 integrated 字节门。

**Tech Stack:** Java 21（`java.net.http.WebSocket`、JUnit 5/BOM via gradle `test` task）、Python 3 stdlib（t1.py 增量）、gson（junit 模块 JSON 编解码——仓库生态已有，纯模块显式声明）。

## Global Constraints

- **T0 回归照旧武装**：任何触碰 common/编排器共享代码的任务收尾必跑 neoforge dogfood 六字节指标门（descentYaw 871°/53；selfShaftDigUp worstBackslide=20.252203415101263 签名门 PASS；gearScope 0.94000053/5.9040003/1.5999999046325684/6.0）。本计划预期**零 agent-driver common Java 改动**；若实现中必须触碰，STOP→BLOCKED 上报。
- **T1 世界纪律**：模板世界 mint(autorun OFF)→archive→copy-per-run→finally 删副本；世界名 `TestkitT1`；禁跨 RUN 复用脏世界。
- **客户端进程纪律**：PID 追踪；quit-to-title→窗口关闭或显式 PID kill；**禁 pkill**；`agent-rpc.port` 端口文件发现，不 hardcode 39801；xvfb 探空闲 DISPLAY 永不碰 :99。
- **仪表面纪律**：junit 模块只打仪表面 verb（tp/give/runCommand/input/observe/screen/wait/mc.test.reset），**禁 goto/walker**；所有调用=裸 RPC 直打 route()（envelope 线格式 `{"id":N,"method":...,"params":{...}}`，同 rpc.py/instrument.py）。
- **执行模型**：进程外 JUnit 体**明许阻塞等待**（spec §3②"阻塞式（进程外形态）双风格同语义"）；但对 `mc.bot.*` 行为面仍禁依赖（仪表面纪律优先，UI 场景不需要行为面）。
- **attach 契约 fail-fast**：`TESTKIT_ENDPOINT` 未设/文件缺失/端点探活失败 ⇒ 大声失败并提示先跑 `python3 scripts/testkit/t1.py --hold`（spec §3② 原文语义）；**不得**降级为 skip（静默 skip=吞测试同形洞）。
- **金丝雀纪律**：JUnit 面自带必红/必超时哨兵（见 Task 3 语义）；金丝雀误判=整轮作废。
- **串行租约**（spec §11 拍板先行项）：一个拓扑实例同一时刻服务一个 attach 客户端；junit 模块不做并行 fork 映射；文档写明。
- **诚实基线**：首跑任何 RED→×2/×3 定性→BLOCKED 上报裁决，禁自裁调门/标 optional/静默缩水；neoforge 客户端对等若撞加载器差异（如 NeoForge 启动附加界面）→定性入档 BLOCKED 报告，不 hack 绕过。
- 运维纪律照旧：前台有界（Bash timeout 参数）；挂死删 run 世界/副本重跑一次；子代理不留后台任务。

## File Structure

| 文件 | 责任 |
|---|---|
| `scripts/testkit/t1.py` | `--hold` 就绪后写 `testkit-endpoint.json`（run 目录）+ 打印 `export TESTKIT_ENDPOINT=...`；teardown 删文件；self-test |
| `docs/testkit/orchestration-contract-v0.md` | attach 契约附录：端点文件 schema、生命周期（写入时机=进世界后/删除时机=teardown）、串行租约、fail-fast 语义 |
| `settings.gradle` | `include 'testkit-junit'` + projectDir `mc-testkit/junit` |
| `mc-testkit/junit/build.gradle` | 纯 Java 21 库模块（**不施 architectury/loom 插件**）；deps=JUnit5 BOM + gson；`test` task 透传 `TESTKIT_ENDPOINT` |
| `mc-testkit/junit/src/main/java/net/magicterra/testkit/junit/Endpoint.java` | 端点描述解析（gson record）+ 探活（`mc.system.version` 一发） |
| `.../junit/TestkitRpc.java` | `java.net.http.WebSocket` 裸 RPC 客户端：envelope 编解码、同步 call(method, params, timeoutMs)、error→`TestkitRpcException` |
| `.../junit/Testkit.java` | `Testkit.attach()` 静态入口（读 env→解析→探活→fail-fast 消息）+ 类型化门面（`exec`/`observePlayer`/`screenInfo`/`screenTree`/`input*`/`reset`/`awaitCondition(pred, timeout)` 阻塞轮询） |
| `.../junit/TestkitExtension.java` | JUnit 5 `ParameterResolver`+`BeforeAllCallback`：`@Testkit` 注入 attach 好的门面；attach 失败=容器级大声失败（非 skip） |
| `mc-testkit/junit/src/test/java/.../SelfTest.java` | 纯 JVM 自测（不连游戏）：endpoint 解析/缺 env fail-fast 消息/envelope 编解码/awaitCondition 超时语义（打桩谓词） |
| `mc-testkit/junit/src/test/java/.../ui/InventoryScreenTest.java` 等 | 首批 UI 场景（live，需 `TESTKIT_ENDPOINT`）+ 2 金丝雀测试 |
| `neoforge/build.gradle` | `testkitClient` run config（镜像 fabric 侧：`-Dtestkit.autorun` 门、ephemeral 端口、run-t1 目录、configureEach 守卫名单） |
| `scripts/testkit/t1.py`（同上） | `--loader {fabric,neoforge}` 泛化（run 目录/gradle task/端口文件路径按 loader 解析） |
| `scripts/testkit/expected-scenes-neoforge.txt` | 复用（T1 neoforge 期望=同 9 场景） |
| `mc-testkit/README.md`、`TODO.md` | JUnit attach 节 + P2c 条目 + 残余 |

**端点文件 schema（v1，冻结进契约附录）**：
```json
{
  "version": 1,
  "topology": "integrated_plus_client",
  "loader": "fabric",
  "rpcHost": "127.0.0.1",
  "rpcPort": 39843,
  "worldName": "TestkitT1",
  "holdPid": 12345,
  "writtenAtEpochMs": 0
}
```
（`writtenAtEpochMs` 由 t1.py 写墙钟；junit 侧只读不判旧——探活是唯一真判据。）

**首批 UI 场景清单**（全部只用仪表面；实现者可按实际可测性微调，删任何一条须报告论证=控制器裁决，不许静默缩水）：
- `ui.inventoryOpenClose`：`input.key`(E)→`screen.info.hasScreen==true` 且 screen 类名含 Inventory→`mc.test.reset`→`hasScreen==false`。
- `ui.screenTreeSlots`：背包开着时 `screen.tree` 含 slot 节点（数量>0）；关屏后 tree 反映无屏。
- `ui.chatScreenType`：`input.key`(T) 开聊天屏→`screen.info` 类名含 Chat→`typeText` 一段→`mc.test.reset` 关屏清 chat→`chat.history` 空。
- `ui.containerFurnace`：`runCommand` setblock 放 furnace 于玩家旁固定相对坐标→`input.click` 右键开 FurnaceScreen→`screen.info` 类名断言→reset 关屏→`runCommand` setblock air 清场（自清恢复原状）。
- 金丝雀 `canary.mustFail`：对 `screen.info` 断一个**必然错误**的期望，`assertThrows(AssertionError)` 包住——证明断言真的会咬。
- 金丝雀 `canary.mustTimeout`：`awaitCondition(() -> false, 200ms)`，`assertThrows(TestkitTimeoutException)`——证明超时真的会断。

---

### Task 1: TESTKIT_ENDPOINT 端点契约（编排器侧）

**Files:** `scripts/testkit/t1.py`、`docs/testkit/orchestration-contract-v0.md`
**Interfaces:**
- Consumes: t1.py 现有 `--hold` 流程（进世界成功后保持在线）、`PORT_FILE`/`read_port`、RUN_DIR。
- Produces: `--hold` 进世界并发现端口后，写 `<RUN_DIR>/testkit-endpoint.json`（schema 见 File Structure，逐键完整），stdout 打印一行 `export TESTKIT_ENDPOINT=<绝对路径>`；teardown（finally）删除该文件（陈旧端点文件=最危险残留，探活兜底但不留垃圾）；纯函数 `write_endpoint(path, loader, port, pid) -> dict` 供 self-test。非 --hold 路径**不写**（scored run 没有 attach 面）。

- [ ] **Step 1: 写实现 + self-test**（写文件原子性=先写 `.tmp` 再 rename；self-test：schema 键全集/非 hold 不写/teardown 删除）
- [ ] **Step 2: 契约附录**（端点 schema v1 冻结、写入/删除时机、探活=junit 侧唯一真判据、串行租约一句）
- [ ] **Step 3: live 验证**（`t1.py --hold` 起→cat 端点文件对 schema→Ctrl-C 收→文件已删；bounded 前台）
- [ ] **Step 4: Commit** `feat(testkit): TESTKIT_ENDPOINT descriptor — t1.py --hold writes attach endpoint, contract appendix`

### Task 2: mc-testkit/junit 模块骨架（attach + 裸 RPC + fail-fast）

**Files:** `settings.gradle`、`mc-testkit/junit/build.gradle`、`Endpoint.java`、`TestkitRpc.java`、`Testkit.java`、`TestkitExtension.java`、`SelfTest.java`
**Interfaces:**
- Consumes: Task 1 端点文件 schema；envelope 线格式 `{"id":N,"method":"mc.x.y","params":{…}}` → `{"id":N,"result":…}` / `{"id":N,"error":"<string>"}`（同 rpc.py，**非** JSON-RPC 2.0）。
- Produces（后续任务与第三方消费的公共面，签名冻结）：
  - `Testkit.attach() -> Testkit`：读 `TESTKIT_ENDPOINT` env（次序：env→系统属性 `testkit.endpoint`）；未设/文件缺/探活失败 ⇒ 抛 `TestkitAttachException`，消息**必须**含 `python3 scripts/testkit/t1.py --hold` 提示原文；探活=`mc.system.version` 一发 5s 超时。
  - `JsonObject call(String method, JsonObject params, long timeoutMs)`：同步；error envelope ⇒ `TestkitRpcException(method, error)`。
  - `void exec(String cmd)`：`mc.action.runCommand`，断 Brigadier `success`（快路径命令语义照 instrument.py `_cmd` 的 staging 面）。
  - `JsonObject observePlayer()` / `JsonObject screenInfo()` / `JsonObject screenTree()`。
  - `void key(String key)` / `void typeText(String text)` / `void click(...)`（映射 `mc.client.input.*` 既有参数形状——实现者读 `scripts/.claude/skills/agent-driver-rpc/references/methods.md` 对齐，不得猜）。
  - `void reset()`：`mc.test.reset`。
  - `void awaitCondition(Supplier<Boolean> pred, Duration timeout)`：客户端侧阻塞轮询（50ms 间隔），超时抛 `TestkitTimeoutException`（**不是** AssertionError——金丝雀要区分）。
  - `TestkitExtension`：`@ExtendWith` 用；attach 单例每 JVM 一次（串行租约），attach 失败在容器级抛（所有测试大声 ERROR，非 skip）。
- 模块纪律：**不施 loom/architectury 插件**（纯 `java-library`）；不依赖任何 MC/loader/agent-driver 类；gson + junit-jupiter 仅测试面依赖各归其位；`test` task `environment 'TESTKIT_ENDPOINT', System.getenv('TESTKIT_ENDPOINT') ?: ''` 透传 + `useJUnitPlatform()`。

- [ ] **Step 1: settings.gradle + build.gradle**（`./gradlew :testkit-junit:build` 空模块过）
- [ ] **Step 2: Endpoint/TestkitRpc/Testkit/Extension 实现**
- [ ] **Step 3: SelfTest（纯 JVM，不连游戏）**：端点 JSON 解析全键/缺 env 的 fail-fast 消息含提示原文/envelope 编码往返/`awaitCondition` 打桩谓词超时抛 `TestkitTimeoutException`、按时真值返回
- [ ] **Step 4: 验证** `./gradlew :testkit-junit:test`（无 TESTKIT_ENDPOINT 时 live 测试类不存在还没写，self-test 全绿）+ 三模块编译不受扰 `./gradlew :testkit-common:build`
- [ ] **Step 5: Commit** `feat(testkit): junit attach module — TESTKIT_ENDPOINT fail-fast, bare-RPC client, typed instrument facade`

### Task 3: 首批 UI 场景 + 金丝雀（live over attach）

**Files:** `mc-testkit/junit/src/test/java/.../ui/*.java`（场景清单见 File Structure）、`mc-testkit/junit/build.gradle`（live 测试与纯 self-test 分离：live 测试类以 `@EnabledIfEnvironmentVariable(named="TESTKIT_ENDPOINT", matches=".+")` 门——**注意**：这是「无端点=不进 live 场景」的**发现门**，与 attach fail-fast 不冲突：设了 env 但 attach 失败仍是大声 ERROR；未设 env 时 live 场景 skip 但 self-test 仍跑，且验收命令**必须**设 env 跑全量）
**Interfaces:**
- Consumes: Task 2 全部公共面；`t1.py --hold`（Task 1 端点文件）；`mc.test.reset` 语义（关屏/放键/清 chat）。
- Produces: 6 个 live 测试（4 场景+2 金丝雀）通过 `TESTKIT_ENDPOINT=<file> ./gradlew :testkit-junit:test` 全绿 ×2 确定性；每场景自清（试后世界/屏幕/chat 状态==试前，靠 reset+setblock air 恢复）。

- [ ] **Step 1: 写 4 场景 + 2 金丝雀**（场景体只用 Task 2 门面；坐标全部相对玩家 observe 读数，不 hardcode 绝对坐标）
- [ ] **Step 2: live 验收 ×2**（起 `t1.py --hold`→`TESTKIT_ENDPOINT=... ./gradlew :testkit-junit:test` 两连，逐测试结果一致；harvest 后 Ctrl-C 收 hold；任何 RED→×2 定性→BLOCKED）
- [ ] **Step 3: Commit** `feat(testkit): first in-game UI scenes over JUnit attach — inventory/chat/furnace screens + canaries`

### Task 4: neoforge 客户端 T1 对等（偏差声明 1 承接）

**Files:** `neoforge/build.gradle`、`scripts/testkit/t1.py`
**Interfaces:**
- Consumes: fabric 侧 `testkitClient` run config 先例（`-Dtestkit.autorun` 门、ephemeral 端口、run-t1 目录、configureEach 守卫）；t1.py 现有 fabric 常量（RUN_DIR/gradle task/世界目录）。
- Produces: `t1.py --loader {fabric,neoforge}`（默认 fabric，常量按 loader 解析：`<loader>/run-t1/`、`:<loader>:runTestkitClient`）；neoforge integrated 9/9 GREEN 且指标与 T0 neoforge golden 字节同（三大金值）；模板世界按 loader 独立（`<loader>/run-t1` 各自 mint）。
- 已知风险（入 dispatch）：NeoForge 客户端启动可能有 mod-warning/实验性提示界面挡 title 流——guidrive label 匹配若撞新界面，先截屏定性（`mc.client.screenshot` 不可用时用 xwd）→加 label 步进（同 into_world 先例）；**不可**改场景/门。若 NeoForge 客户端根本起不来（loader 级障碍）→BLOCKED 定性入档，本任务余下改动仍 commit（run config+泛化是无害增量）。

- [ ] **Step 1: run config + t1.py 泛化 + self-test**（`--loader` 解析进 self-test；fabric 路径回归 `t1.py --self-test`）
- [ ] **Step 2: neoforge live 首跑定性**（mint→scored；×2 确定性；金值三大件比对）
- [ ] **Step 3: fabric 回归**（`t1.py` 默认路径一发 GREEN 证泛化零破坏）
- [ ] **Step 4: Commit** `feat(testkit): neoforge testkitClient parity — t1.py --loader, integrated-server byte-parity` （或 BLOCKED 时 `feat(testkit): neoforge testkitClient run config + t1.py --loader (client bring-up BLOCKED, see report)`）

### Task 5: 验收 + 文档

**Files:** `mc-testkit/README.md`、`TODO.md`
- [ ] **Step 1: 五门验收**（前台有界，顺序）：①`:testkit-junit:test` 无 env（self-test 绿 + live skip 计数如实记录）；②`t1.py --hold` + env 全量 JUnit（6 live 全绿）；③`t1.py`（fabric 9 场景，Task 4 泛化后回归）；④neoforge T1（Task 4 若 GREEN 则复跑一发；BLOCKED 则如实引档）；⑤instrument_client.py `--rounds 3`（attach 面变更后的复用回归）
- [ ] **Step 2: 文档 + Commit** `docs(testkit): P2c — JUnit attach, first UI scenes, neoforge client parity`（README「JUnit 5 attach」节：attach 契约/fail-fast 原文/串行租约/live vs self-test 双层/命令示例；TODO P2c 条目：能力清单+五门记录+残余[若 Task 4 BLOCKED 则偏差声明 1 保持并引档]+JUnit 并行 fork 映射仍开放=spec §11）

---

## Self-Review（计划自检记录）

1. **覆盖**：spec §3② attach 契约（TESTKIT_ENDPOINT/fail-fast/IDE 手动起拓扑先行）→T1/T2；类型化代理+阻塞式同语义→T2（awaitCondition 阻塞轮询=进程外形态明许）；P2b 偏差(2) 首批 UI 场景→T3；偏差(1) neoforge 客户端对等→T4；spec §11 串行租约拍板先行→T2 单例+契约文档；金丝雀纪律→T3 双哨兵（JUnit 语义映射：必红=assertThrows 包真断言，必超时=TestkitTimeoutException 类型区分）。
2. **占位符**：无 TBD。input verb 参数形状不在计划里复述——指向 methods.md 权威源并禁猜（P1b「⭐cursor 裸 long」同教训：形状必须读源对齐）。UI 场景清单允许「论证+裁决」微调（P2b 先例条款）。
3. **一致性**：端点 schema 在 File Structure 冻结、T1 产出、T2 消费键名一致；`TestkitTimeoutException` vs AssertionError 的类型区分在 T2 签名与 T3 金丝雀两处同语义；`testkit-junit` 模块名与 settings.gradle include 一致；`--loader` 在 T4 定义、T5 门③④消费。
4. **风险入案**：live/self-test 分层用 `@EnabledIfEnvironmentVariable` 有「静默 skip=吞测试」嫌疑→已在 T3 明示其只作发现门+验收命令必设 env+门① 要求 skip 计数如实记录（吞测试可见化）；NeoForge 客户端 bring-up 未知界面→T4 预授权定性路径+BLOCKED 不 hack；websocket 半关/服务器重启中 attach→探活+call 超时抛而非挂死；`mc.test.reset` 在 JUnit 场景间的残留=就是 P2b 复用面已验收的语义，T3 每场景 reset 收尾复用该保证；t1.py 泛化破坏 fabric=T4 Step 3 显式回归门。

# WorldDriver

一个把运行中的 Minecraft 暴露成"可编程、AI 可驱动"接口的模组。**同一个 DriverApi**
对外开三条传输：

- **进程内 Rhino 脚本** —— 内置 JS 引擎，带沙箱，随游戏一起跑
- **WebSocket RPC** —— JSON-NDJSON over `ws://127.0.0.1:<port>/rpc`
- **MCP Streamable HTTP** —— Model Context Protocol over `http://127.0.0.1:<port>/mcp`

三条路径都经过断言：返回字节完全一致。外部 agent 看到的世界与游戏内脚本看到的世界，
是同一个。

- Minecraft **1.21.1**，Architectury（Fabric + NeoForge）
- JDK **21**
- Rhino 分支：`dev.latvian.mods:rhino:2101.2.7-build.81`（KubeJS-Mods）
- 协议：[MIT](LICENSE)

---

## 能力速览

```
外部 MCP 客户端              进程内 JS 脚本                  外部 WS 客户端
       │                              │                              │
       ▼                              ▼                              ▼
 HTTP /mcp (39800)            Driver.invoke(method,…)         WS  /rpc (39801)
       │                              │                              │
       └──────────────────────────────┴──────────────────────────────┘
                                      ▼
                                  DriverApi
                       （单一可信源，调度到 server 线程）
                                      ▼
                          live ServerLevel + ClientHooks
```

**MCP 工具**，按职责分组（完整 schema 见
[`common/src/main/java/.../mcp/ToolCatalog.java`](common/src/main/java/net/magicterra/worlddriver/mcp/ToolCatalog.java)）：

**共 72 个工具，且全部对外公开**：catalog 里的 hidden 列表是空的，所以 `tools/list` 就是全部surface。
（隐藏机制只为省 prompt token 而存在——被隐藏的 verb 在任何 transport 上依然可以按名调用。）

| 分组 | 工具 | 什么时候用 |
|---|---|---|
| `mc.system.*`    | `version` / `testOrigin` / `waitTicks` | 探活、测试场原点、固定时长等待 |
| `mc.script.eval` | 跑一段 JS | 多步复合任务（省下几十次 round-trip） |
| `mc.observe.*`   | `player` / `cursor` / `container` / `eventsSince` / `map` / `scene` / `threats` / `boss` | "刚才发生了什么、玩家在哪、箱子里装了啥？" `map` 是服务端 ASCII 空间地图——比解析扫块结果更适合一眼看懂；`scene` 是围绕某中心的危险读数；`threats` 给敌对生物和来袭弹射物打分；`boss` 是 Boss 战感知。后两个是仅客户端的，专用服务器上不存在。（原始扫块/扫实体走 `mc.query`） |
| `mc.query`       | `q='blocks' \| 'entities'` | 带过滤的 DSL 查询；无服务器附着时客户端 fallback 扫 ClientLevel（实体行带 `id` 可喂给 `attackEntity`） |
| `mc.action.*`    | `fill` / `placeMany` / `runCommand` | 改变世界（长方体填充、批量放块——单块用 placeMany 1 项、原版命令） |
| `mc.world.*`     | `snapshot` / `restore` / `block` | `snapshot` 把一个长方体的方块状态**连同 block-entity NBT** 抓成一个句柄，`restore` 原样放回——这正是高风险建造或破坏性测试需要的 undo。`block` 是只读单格检查：类型、state、光照 |
| `mc.recipe.*`    | `lookup` / `resolve` | 读游戏自己的配方表（原版 + 任何已加载的模组），而不是把配方硬编码进 agent —— 后者一进整合包就错 |
| `mc.wait.*`      | `event` / `worldReady` / `condition` / `result` | 长轮询原语（等下一条事件 / 等世界加载完 / 等任意条件成立）。`result` 取回以 `background:true` 发起的等待的结果 |
| `mc.events`      | 服务端事件通道 | Driver→agent 推送：威胁、聊天等，以流的形式而不是轮询 |
| `mc.plan.acquire`| 目标导向的获取规划器 | "给我搞到 N 个 X" —— 由它规划链路，而不是你告诉它怎么做 |
| `mc.skill`       | 持久技能库 | 写一次可复用的 JS 技能，之后按名字调用（Voyager 风格） |
| `mc.client.*`    | `screen.info / .tree / .close`、`input.click / .slotClick / .mouseMove / .setHotbarSlot / .typeText / .replaceText / .slider / .key`、`chat.send / .history`、`screenshot`、`player`、`blocks`、`scene`、`overlays` | 仅客户端。开 inv/pause 用 `input.key{key:'E'/'ESCAPE'}`；`input.slotClick` 走 Menu.clicked 真 ClickType（shift-click / Q-drop / swap / clone）；`replaceText` 原子地整体覆盖输入框；`slider` 读写 `AbstractSliderButton`。`player` / `blocks` / `scene` 是**以客户端为准**的读取（LocalPlayer + ClientLevel）——当问题是"客户端认为如何"而不是"服务端持有什么"时要用它们。`overlays` 用来关掉不属于世界的 HUD 覆盖层 |
| `mc.bot.*`       | `goto` / `mine` / `build` / `clearArea` / `farm` / `sleep` / `construct` / `follow` / `explore` / `runAway` / `escape` / `lookAt` / `useItem` / `holdItem` / `equip` / `attackEntity` / `combat` / `craft` / `smelt` / `elytraFly` / `bunker` / `playbook` / `waypoint` / `cancel` / `status` / `setting` | 客户端自主行动，对齐 Baritone。长任务异步——通过 `status` 轮询或传 `awaitMs`；暂停/继续走 `setting{paused:bool}`。详见下文 |

`screenshot` 工具发回的是真正的 MCP `image` content block（不是塞进 text 里的 base64
字符串），多模态模型能直接把帧缓冲当作视觉输入。

**`mc.bot.*` 细节。** `useItem` 带 `pos` = 对方块面右键放置/使用，不带 = 空中使用（吃/喝/拉弓/丢雪球）；
`attackEntity` = 对实体左键一下。`farm` = 在二维矩形里收熟麦/胡萝卜/土豆/甜菜并补种。`sleep` = 找最近的
床走过去右键（夜晚/安全条件由原版自己判）。`construct{mode:"tower"|"bridge"}` = Baritone 立柱/搭桥合一：
tower 朝上摞到 height/targetY，bridge 沿方向潜行搭桥 distance 格。`escape` 则相反——沿着枯井/深坑的**干燥**
墙面向上凿出楼梯爬出来，全程不放任何方块。`craft` 会从背包出发解析整棵子配方树；`smelt` 用槽位模拟驱动
熔炉；`equip` 给每个部位穿上最好的护甲、手上拿最好的武器；`holdItem` 把指定物品选进主手。`combat` 主动
与敌对生物作战，`playbook` 跑可热重载的多阶段 Boss 脚本。`waypoint` 存名字位置给 `goto{waypoint:'name'}` 用。

`goto` 接受 pos/xz/y/block/entity/entityId/direction+distance/waypoint/axis 选择器，外加
`goalMode:"in"/"two"/"adjacent"`（GoalBlock/GoalTwoBlocks/GoalGetToBlock）、`direction+strict`
（GoalStrictDirection）、`invert`（GoalInverted）等修饰——与 Baritone 的 goal 面完全对齐。
`setting` 可切换 `autoEat`/`autoRespawn`/`autoSwim`/`autoTool`/`allowParkour4`/`allowBreak`/`allowPlace`/
`smoothLook`，并可调 `pathfinder.maxNodes`/`maxMs`/`axisHeight`/`smoothLookDegPerTick`。
`allowBreak`/`allowPlace`（Baritone 对齐，**默认都关**）让 A\* 可以挖穿墙、向下挖、搭一格桥作为路线的一
部分，于是在没有现成可走路径时 bot 也能抵达目标；关掉则保证 `goto`/`follow` 非破坏性。

---

## 快速上手

### 1. 跑集成测试（不需要客户端）

```bash
./gradlew stagewrightDedicatedServerNeoforge
# → VERDICT: GREEN（任何一个场景挂掉就非零退出）
```

这会用 stagewright harness dogfood 一个 dedicated server，autorun wd.* 场景
（`common/src/testmod/.../scene/`）加上 `*.js` 校验套件，并把结果流对照 expect-file
校验。CI 正门就是 `./gradlew stagewright<Topology><Loader>` 任务，每个拓扑 × 每个 loader
一个；同名的 `Hold` 变体把端点发布出来，供 `:stagewright-junit` 里的进程外套件 attach。旧的
`@GameTest`/GameTestServer 路径已在 P4-final 退役，接替它的那批 Python 编排器也已于
2026-08-05 删除 —— StageWright 是同级 checkout（`../stagewright`），以发布产物形式消费，本仓
留下的只有各 loader 的 `expected-scenes-*.txt` 清单。

### 2. 跑客户端，接 MCP 客户端

```bash
# 可选：固定端口（不然会随机分配，写到 fabric/run/worlddriver-{mcp,rpc}.port）
JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" \
  ./gradlew :fabric:runClient
```

RPC 和 MCP **都**在 client init 阶段就起来了 —— 你在 TitleScreen 就能连上，
没进世界也行。需要世界的工具会返回 `isError`，但 `mc.client.*` 和
`mc.script.eval` 立刻可用。

RPC 和 MCP 默认都绑定到 `127.0.0.1`。需要让其它主机连入时，设置
`-Dworlddriver.rpcHost=0.0.0.0` / `-Dworlddriver.mcpHost=0.0.0.0`（也可用 IPv6 的 `::`
或某个具体网卡地址）。绑定通配地址时日志仍打印 loopback URL，因为
`0.0.0.0` / `::` 本身不是可连接的目标地址。

把下面这段 `.mcp.json` 放到你启动 MCP 客户端的目录下，任何 spec-compliant
客户端（Claude Code、Cursor、Continue、Codex、MCP Inspector）都会自动发现：

```json
{
  "$schema": "https://modelcontextprotocol.io/schemas/mcp.json",
  "mcpServers": {
    "worlddriver": {
      "type": "http",
      "url": "http://127.0.0.1:39800/mcp"
    }
  }
}
```

URL 里的端口要和 runClient 启动时的 `-Dworlddriver.mcpPort` 一致。Claude Desktop 等只
能走 stdio 的客户端，参考 [`docs/mcp-clients.md`](docs/mcp-clients.md) 用
`mcp-remote` 桥接。

### 3. 用 shell 烟雾测试一下

```bash
PORT=$(cat fabric/run/worlddriver-mcp.port)
curl -s http://127.0.0.1:$PORT/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"mc.system.version","arguments":{}}}' | jq
```

---

## 游戏内命令

挂在 `/agent` 下的 Brigadier 子命令：

| 命令 | 作用 |
|---|---|
| `/agent test`        | 在工作线程跑全套校验脚本，输出 PASS/FAIL 数 |
| `/agent test list`   | 列出校验脚本名 |
| `/agent test result` | 打印最近一次跑分的每条测试结果 |
| `/agent port`        | 打印 RPC 端口（`ws://127.0.0.1:<port>/rpc`） |
| `/agent mcp`         | 打印 MCP 端点（`http://127.0.0.1:<port>/mcp`） |
| `/agent reload`      | 重新加载 `config/worlddriver/scripts/` 下的用户脚本 |

---

## 工程目录

```
worlddriver/
├── common/                Architectury 共享代码（DriverApi、MCP/RPC server、Rhino 胶水）
│   ├── src/main/
│   │   ├── java/net/magicterra/worlddriver/
│   │   │   ├── api/               DriverApi 路由 + System/Observe/Action/Wait 处理器（单一可信源）
│   │   │   ├── bot/               客户端 bot 子系统（pathfinder、goto/mine/build/follow 等进程）
│   │   │   ├── mcp/               McpServer + ToolCatalog（含 catalog/ —— 各分组的工具 schema）
│   │   │   ├── rpc/               RpcServer (Netty WebSocket) + JsonCodec
│   │   │   ├── script/            Rhino 接入、沙箱、ScriptEvaluator
│   │   │   ├── model/             各 transport 共用的 wire/DTO 类型
│   │   │   └── client/            ClientHooks 中介（impl 在 fabric/neoforge 下）
│   │   └── resources/data/worlddriver/scripts/validation/  *.js 校验套件
│   ├── src/testmod/       StageWright 正门跑的 wd.* / cap.* / pack.* 场景
│   └── src/test/          纯 JVM 单元测试（不开游戏）
├── fabric/                Fabric 入口 + 客户端实现
├── neoforge/              NeoForge 入口 + 客户端实现
├── stagewright-scenes/    会被装进运行目录 config/stagewright/scenes/ 的 .js 场景
├── docs/                  各客户端接入指南（重点看 docs/mcp-clients.md）
└── scripts/               expected-scene 清单、源码行数闸门、MCP bridge
```

---

## 设计要点

- **单一可信源**：`DriverApi.route(method, params)` 是唯一一处真正运行游戏逻辑的
  地方。MCP、WebSocket、进程内脚本都通过同一个入口调用 —— 校验套件断言三者结果
  字节一致。
- **server 线程纪律**：所有写路径都经 `server.execute()` 派发；脚本跑在非 server
  线程上，可以放心 `future.get()` 不会自锁。
- **MCP spec 合规**：`initialize` 协商协议版本，Origin 头校验（loopback allowlist）
  防 DNS rebinding，截图发真正的 `image` content block，多模态走 `text+image`
  双块返回。具体 spec 引用见 `McpServer.java` 注释。
- **Rhino 沙箱**：`ScriptClassFilter` 屏蔽 `Runtime`、`ProcessBuilder`、`Thread`、
  `File`、`Socket`、反射、JDK 内部包。`08_sandbox.js` 持续验证。`mc.script.eval`
  在沙箱基础上额外加了 wall-clock 超时（通过 Rhino 的 instruction-count
  observer 强制执行）。
- **跨平台对等**：`common/` 同一份源码同时出 Fabric 和 NeoForge，平台代码只负责
  挂 `ServerLifecycleEvents` 钩子和 `mc.client.*` 的客户端实现。

---

## 当前状态

**Phase 1（感知 + 行动 + 最小客户端驱动）已端到端跑通：**

- 全部 MCP 工具，Claude Code 走 `.mcp.json` 就能接通，无需额外配置
- 完整闭环演示：TitleScreen 点击 → SelectWorldScreen 点击 → 世界加载 →
  `mc.query q='blocks'` 扫到 17 棵树 → 锁定出生点旁那棵 `(0, 67, 1)` 的橡木 →
  `mc.client.screenshot` 把帧缓冲作为视觉块送回 LLM
- 客户端 bot 子系统（`mc.bot.goto/mine/build/follow/explore/runAway/...`）
  自带 A* 寻路，所有任务以异步进程形式暴露，通过 `mc.bot.status` +
  `mc.wait.condition` 轮询完成

此后 verb 面已经远远超出那一个切片——bot 上多了 `combat`、`craft`、`smelt`、`equip`、`elytraFly`、
`escape`、`bunker`、`playbook`，另有 `mc.plan.acquire`、`mc.skill`、`mc.observe.boss/threats/map`
以及 `mc.world.snapshot/restore` 这一对。上面的表格就是当前的完整面；逐里程碑的记录见
[`CHANGELOG.md`](CHANGELOG.md)，尚未走完的阶梯见 [`ROADMAP.md`](ROADMAP.md)。

**正门状态（2026-08-08）：六个 topology 全绿** —— 两个 loader × 三种形态：
`stagewrightDedicatedServer`、`stagewrightIntegratedServer`、`stagewrightDedicatedServerWithClient`
× {Fabric, Neoforge}。清单是 222 个场景（171 个 `wd.*` + 38 个 `cap.*` + 13 个 `pack.*`）；算上框架
自带的内置场景与 canary，一次运行注册 232 个。两个 production topology 还会额外裁决其**客户端**那一半
写出的结果文件——那是唯一能对进程边界下断言的地方。`./gradlew stagewrightCoverage` 让六者互相对账：
任何一次运行注册过的场景，必须至少在其中一次里真正**执行**过，因为一个到处都 skip 的场景，
只是在一个没人测过的主题上显示绿色。

---

## 索引

- **贡献指南**：[`CONTRIBUTING.md`](CONTRIBUTING.md)
- **变更日志**：[`CHANGELOG.md`](CHANGELOG.md)
- **AI agent 工作约定**（给 Claude Code / Cursor 等用）：[`AGENTS.md`](AGENTS.md)
- **接各种 MCP 客户端**：[`docs/mcp-clients.md`](docs/mcp-clients.md)
- **Claude Desktop 配置示例**：[`docs/claude_desktop_config.example.json`](docs/claude_desktop_config.example.json)
- **MCP 规范**：<https://modelcontextprotocol.io/specification/2025-06-18>

English version: see [README.md](README.md).

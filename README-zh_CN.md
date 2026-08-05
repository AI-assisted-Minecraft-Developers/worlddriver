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

| 分组 | 工具 | 什么时候用 |
|---|---|---|
| `mc.system.*`    | `version` / `testOrigin` / `waitTicks` | 探活、测试场原点、固定时长等待 |
| `mc.observe.*`   | `cursor` / `eventsSince` / `player` / `container` | "刚才发生了什么、玩家在哪、箱子里装了啥？"（扫块/扫实体走 `mc.query`） |
| `mc.action.*`    | `fill` / `placeMany` / `runCommand` | 改变世界（长方体填充、批量放块——单块用 placeMany 1 项、原版命令） |
| `mc.query`       | `q='blocks' \| 'entities'` | 带过滤的 DSL 查询；无服务器附着时客户端 fallback 扫 ClientLevel（实体行带 `id` 可喂给 `attackEntity`） |
| `mc.wait.*`      | `event` / `worldReady` / `condition` | 长轮询原语（等下一条事件 / 等世界加载完 / 等任意条件成立） |
| `mc.script.eval` | 跑一段 JS | 多步复合任务（省下几十次 round-trip） |
| `mc.client.*`    | `screen.info / .tree / .close`、`input.click / .slotClick / .mouseMove / .setHotbarSlot / .typeText / .key`、`chat.send`、`screenshot` | 仅客户端。开 inv/pause 用 `input.key{key:'E'/'ESCAPE'}`；`input.slotClick` 走 Menu.clicked 真 ClickType（shift-click / Q-drop / swap / clone） |
| `mc.bot.*`       | `goto` / `mine` / `build` / `clearArea` / `farm` / `sleep` / `construct` / `follow` / `explore` / `runAway` / `lookAt` / `useItem` / `attackEntity` / `waypoint` / `cancel` / `status` / `setting` | 客户端自主行动。`useItem` 带 `pos` = 对方块面右键放置/使用，不带 = 空中使用（吃/喝/拉弓/丢雪球）；`attackEntity` = 对实体左键一下。`farm` = 在二维矩形里收熟麦/胡萝卜/土豆/甜菜并补种。`sleep` = 找最近的床走过去右键（夜晚/安全条件由原版自己判）。`construct{mode:"tower"\|"bridge"}` = Baritone 立柱/搭桥合一：tower 朝上摞到 height/targetY，bridge 沿方向潜行搭桥 distance 格。`waypoint` 存名字位置给 `goto{waypoint:'name'}` 用。长任务异步 — 通过 `status` 轮询或传 `awaitMs`。暂停/继续走 `setting{paused:bool}` |

`screenshot` 工具发回的是真正的 MCP `image` content block（不是塞进 text 里的 base64
字符串），多模态模型能直接把帧缓冲当作视觉输入。

---

## 快速上手

### 1. 跑集成测试（不需要客户端）

```bash
python3 scripts/stagewright/t0.py --loader neoforge \
  --run-task :neoforge:runDogfoodServer \
  --results neoforge/run-dogfood/stagewright-results.jsonl \
  --expect-file scripts/stagewright/expected-scenes-neoforge.txt
# → GREEN（任何一个场景挂掉就非零退出）
```

这会用 stagewright harness dogfood 一个 dedicated server，autorun wd.* 场景
（`common/src/testmod/.../scene/`）加上 `*.js` 校验套件，并把结果流对照 expect-file
校验。`scripts/stagewright/` 下的编排器（`t0.py` + `instrument.py`）与
`./gradlew stagewright<Topology><Loader>` 任务共同构成 CI 正门 —— 旧的
`@GameTest`/GameTestServer 路径已在 P4-final 退役，两个客户端拓扑的 `t1.py`/`t2.py`
已由 Gradle 任务取代。

### 2. 跑客户端，接 MCP 客户端

```bash
# 可选：固定端口（不然会随机分配，写到 fabric/run/agent-{mcp,rpc}.port）
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
│   └── src/main/
│       ├── java/net/magicterra/worlddriver/
│       │   ├── api/               DriverApi 路由 + System/Observe/Action/Wait 处理器（单一可信源）
│       │   ├── bot/               客户端 bot 子系统（pathfinder、goto/mine/build/follow 等进程）
│       │   ├── mcp/               McpServer + ToolCatalog
│       │   ├── rpc/               RpcServer (Netty WebSocket) + JsonCodec
│       │   ├── script/            Rhino 接入、沙箱、ScriptEvaluator
│       │   └── client/            ClientHooks 中介（impl 在 fabric/neoforge 下）
│       └── resources/data/worlddriver/scripts/validation/  *.js 校验套件
├── fabric/                Fabric 入口 + 客户端实现
├── neoforge/              NeoForge 入口 + 客户端实现
├── docs/                  各客户端接入指南（重点看 docs/mcp-clients.md）
└── scripts/               一次性辅助脚本（烟雾测试、harness 工具）
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

- stagewright 正门（`scripts/stagewright/t0.py`）全套校验脚本 + wd.* 场景绿（用作 CI）
- 全部 MCP 工具，Claude Code 走 `.mcp.json` 就能接通，无需额外配置
- 完整闭环演示：TitleScreen 点击 → SelectWorldScreen 点击 → 世界加载 →
  `mc.query q='blocks'` 扫到 17 棵树 → 锁定出生点旁那棵 `(0, 67, 1)` 的橡木 →
  `mc.client.screenshot` 把帧缓冲作为视觉块送回 LLM
- 客户端 bot 子系统（`mc.bot.goto/mine/build/follow/explore/runAway/...`）
  自带 A* 寻路，所有任务以异步进程形式暴露，通过 `mc.bot.status` +
  `mc.wait.condition` 轮询完成

Phase 2–3 单独跟进，已发布的里程碑见 [`CHANGELOG.md`](CHANGELOG.md)。

---

## 索引

- **贡献指南**：[`CONTRIBUTING.md`](CONTRIBUTING.md)
- **变更日志**：[`CHANGELOG.md`](CHANGELOG.md)
- **AI agent 工作约定**（给 Claude Code / Cursor 等用）：[`AGENTS.md`](AGENTS.md)
- **接各种 MCP 客户端**：[`docs/mcp-clients.md`](docs/mcp-clients.md)
- **Claude Desktop 配置示例**：[`docs/claude_desktop_config.example.json`](docs/claude_desktop_config.example.json)
- **MCP 规范**：<https://modelcontextprotocol.io/specification/2025-06-18>

English version: see [README.md](README.md).

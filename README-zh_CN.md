# WorldDriver

一个把运行中的 Minecraft 暴露为单一可编程 API 面的模组，共有三条接入途径：

- **进程内 JavaScript** —— 内置 Rhino 引擎，与游戏一同运行
- **WebSocket JSON-RPC** —— 以换行分隔的 JSON，`ws://127.0.0.1:<port>/rpc`
- **Model Context Protocol** —— Streamable HTTP，`http://127.0.0.1:<port>/mcp`

三者都只负责翻译参数，然后调用同一个路由器，因此外部 agent 看到的东西与游戏内脚本看到的
完全一致。

- Minecraft **1.21.1**，Architectury（同一套源码同时供 Fabric 与 NeoForge）
- 两个 loader 上都必须另行安装 **Architectury API 13.0.8**，就像 Fabric 上必须装 Fabric API 一样
- JDK **21**
- Rhino 分支 `dev.latvian.mods:rhino:2101.2.7-build.81`（KubeJS 构建）
- 许可证：[LGPL-3.0-only](COPYING.LESSER)；其所基于的 GPL-3.0 正文见 [`COPYING`](COPYING)

---

## 它提供什么

```
external MCP client            in-game JS script              external WS client
       │                              │                              │
       ▼                              ▼                              ▼
 HTTP /mcp (port 39800)        Driver.invoke(method,…)         WS  /rpc (port 39801)
       │                              │                              │
       └──────────────────────────────┴──────────────────────────────┘
                                      ▼
                                  DriverApi
                       (single source of truth, on the server thread)
                                      ▼
                          live ServerLevel + ClientHooks
```

七十余个方法按职责分组。下表用于建立总体印象；逐方法的完整接口，连同参数与返回结构，见
[`docs/guide/capabilities.md`](docs/guide/capabilities.md)，而 schema 本身由
`common/src/main/java/net/magicterra/worlddriver/mcp/catalog/` 生成。

| 分组 | 用途 |
|---|---|
| `mc.system.*`    | 探查当前运行的构建、取得多数空间类方法默认以之为中心的测试场原点，以及等待固定 tick 数。 |
| `mc.script.eval` | 在进程内运行一段 JavaScript。只要一件事本来需要三次以上往返，就应优先用它。 |
| `mc.skill`       | 持久技能库：保存、列出、运行、删除可复用的脚本。 |
| `mc.events`      | 驱动器到 agent 的事件通道，包含轮询某个路由、在谓词翻转时发出事件的上升沿监视器。 |
| `mc.observe.*`   | 只读感知：玩家、敌对生物与来袭弹射物、Boss、危险场景读数、ASCII 空间地图、容器内容，以及事件积压。 |
| `mc.query`       | 在一个立方体内带过滤地扫描方块或实体，并可投影字段。 |
| `mc.action.*`    | 在一个服务端 tick 内改变世界：长方体填充、批量放置，以及操作员级别的原版命令。 |
| `mc.world.*`     | 单格检查，以及连同 block-entity NBT 一起快照与还原某个区域 —— 这正是高风险建造或破坏性测试所需的撤销能力。 |
| `mc.recipe.*`    | 读取游戏自己的配方表（原版加上任何已加载的模组），并把一次请求展开成有序的合成计划。 |
| `mc.plan.acquire`| 目标导向的获取规划：把每一种缺失的原料分派给挖掘、种植、熔炼或合成，并按可执行顺序输出步骤。 |
| `mc.wait.*`      | 长轮询原语：下一条事件、世界加载完成、任意为真的条件，以及取回以后台方式发起的等待的结果。 |
| `mc.client.*`    | 以客户端为准的观察与合成 GUI 输入：界面探查、控件树、鼠标、槽位、按键与文本输入、聊天，以及截图。 |
| `mc.bot.*`       | 自主层，详见下文。 |

在专用服务器上，`mc.client.*` 的各方法返回错误而不是数值；两个仅客户端的感知方法
`mc.observe.threats` 与 `mc.observe.boss` 返回空读数，因为它们读取的状态（客户端实体渲染集合、
苦力怕膨胀、弹射物速度、末影龙的阶段管理器）只存在于客户端。

`mc.client.screenshot` 除元数据文本块之外，还返回一个真正的 MCP `image` 内容块，因此多模态模型
拿到的是作为视觉输入的帧缓冲，而不是塞在文本里的一大段 base64。

**自主层。** `mc.bot.*` 是一个客户端侧 agent，自带 A\* 寻路，目标接口与 Baritone 对齐。它可以
前往某个坐标、某种方块、某个实体、某个已保存的路径点或某个方位；可以挖掘、耕种、建造、清理
区域、立柱、搭桥与睡觉；可以从背包出发解析整棵子配方树、驱动熔炉、给每个部位配上自己拥有的
最好护甲与武器；可以跟随、探索、逃离、战斗、用鞘翅飞行、给自己挖出应急掩体，以及在坑里凿出
楼梯爬上来。长任务是异步的：轮询 `mc.bot.status`，或者传 `awaitMs`。`mc.bot.setting` 调节整个
子系统 —— 键有数百个，且由设置注册表生成而非手工罗列，所以该方法的 `inputSchema` 才是权威清
单。其中两个键决定寻路器是否可以改变世界：`allowBreak` 允许它挖穿障碍，`allowPlace` 允许它
搭出一格桥，**两者默认都是开启的**，也就是说除非你关掉它们，否则 `goto` 与 `follow` 会改变地
形。场景套件把两者都钉成关闭，因此在那里跑通的路线不能作为默认客户端上的证据。这一层背后的
分层设计见 [`docs/dev/bot-layering.md`](docs/dev/bot-layering.md)。

---

## 快速上手

### 1. 构建，并跑场景套件

场景套件所依托的游戏内测试框架 StageWright 有自己的仓库，在这里以发布到 Maven 的产物形式被
消费。两个仓库以相反的方向互相编译 —— StageWright 的模块针对 WorldDriver 的 `common` 编译，
而 WorldDriver 的测试源码针对 StageWright 的 API 编译 —— 所以一份干净的 checkout 只有唯一一条
可行的引导顺序。它写在 `../stagewright/build.gradle` 顶部，并且起点在 StageWright 一侧，因为
WorldDriver 的根构建会应用 StageWright 的 Gradle 插件，在该插件可解析之前根本无法完成配置：

```bash
cd ../stagewright
./gradlew -p engine publishToMavenLocal
./gradlew -p gradle-plugin publishToMavenLocal
./gradlew :stagewright-api:publishToMavenLocal :stagewright-attached:publishToMavenLocal

cd ../worlddriver
./gradlew -PworlddriverBootstrap :common:publishToMavenLocal

cd ../stagewright
./gradlew publishToMavenLocal

cd ../worlddriver
./gradlew build
```

`-PworlddriverBootstrap` 会去掉两个 loader 对 StageWright 的运行期依赖，而 Gradle 在配置期就要
解析它；没有这个属性，一台从未发布过 StageWright 的机器过不了这一步。这并不是真正的依赖环：
WorldDriver 出厂的源码从来没有依赖过 StageWright，两个发布 jar 里也没有任何一个 StageWright 类。

做完之后，一条命令就能让场景套件在无头专用服务器上跑起来：

```bash
./gradlew stagewrightDedicatedServerNeoforge
```

该任务会准备一个干净的运行目录、启动游戏、运行场景与 JavaScript 校验套件，只有在每一个必需
场景都通过时才以零退出。这样的任务共有六个，按进程拓扑与 loader 组合而成，说明见
[`docs/dev/testing.md`](docs/dev/testing.md)。

### 2. 跑客户端并接入 MCP 客户端

```bash
./gradlew :fabric:runClient
```

开发运行已经把 MCP 端点固定在 39800、RPC 端点固定在 39801，所以下面这份配置在重启之后依然
有效。需要让两个客户端共存时，用 `-PagentMcpPort=` 与 `-PagentRpcPort=` 覆盖。在普通安装环境
中端口由操作系统分配，并写入游戏目录下的 `worlddriver-mcp.port` 与 `worlddriver-rpc.port`。

两个端点都在客户端初始化阶段开启，因此你可以在标题界面、尚未加载任何世界时就连上。需要世界
的方法在存档打开之前返回错误；`mc.client.*` 与 `mc.script.eval` 立即可用。

两者都绑定到 `127.0.0.1`。把 `-Dworlddriver.rpcHost=` 或 `-Dworlddriver.mcpHost=` 设为通配地址
（`0.0.0.0`，IPv6 则是 `::`）或某个具体网卡地址，即可接受来自其它主机的连接。这样做之前请先读
下文「设计」一节里关于脚本的那一条：能运行脚本的端点就能运行任意 Java，所以把任何一个绑定地址
移出回环，等于把整个 JVM 放到了网络上。绑定通配地址时日志仍然打印回环 URL，因为 `0.0.0.0` 与
`::` 本身不是可连接的目标。

把下面这段 `.mcp.json` 放进你启动 MCP 客户端的目录，任何符合规范的客户端都会自动发现这个服务：

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

只会说 stdio 的客户端需要一个桥接，见 [`docs/guide/mcp-clients.md`](docs/guide/mcp-clients.md)。

### 3. 从 shell 验证一下

```bash
PORT=$(cat fabric/run/worlddriver-mcp.port)
curl -s http://127.0.0.1:$PORT/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"mc.system.version","arguments":{}}}' | jq
```

---

## 游戏内命令

挂在 `/worlddriver` 下的 Brigadier 子命令。命令根用的是完整的 mod id，以免在大型整合包里与别的
模组的命令撞名。每个子命令都需要权限等级 2：即管理员，单人世界中则须开启作弊。没有这一权限时，
端点仍可从 `worlddriver-mcp.port` 与 `worlddriver-rpc.port` 读到。

| 命令 | 作用 |
|---|---|
| `/worlddriver port`        | 打印 RPC 端点 |
| `/worlddriver mcp`         | 打印 MCP 端点 |
| `/worlddriver reload`      | 重新加载 `config/worlddriver/scripts/` 下的用户脚本 |
| `/worlddriver server spawn\|goto\|mine\|status\|clear` | 生成并驱动一具服务器端身体 |

校验套件的 `/worlddriver test` 随 testmod 提供，只存在于开发运行中；见
[`docs/dev/testing.md`](docs/dev/testing.md)。

---

## 工程目录

```
worlddriver/
├── common/              Architectury 共享源码：路由器、各传输层、Rhino 胶水
│   ├── src/main/java/net/magicterra/worlddriver/
│   │   ├── api/            DriverApi —— 路由器及其处理器
│   │   ├── bot/            客户端侧自主层：寻路器、walker、各进程
│   │   ├── mcp/            MCP HTTP 服务器与工具目录
│   │   ├── rpc/            Netty WebSocket 服务器与 JSON 编解码
│   │   ├── script/         Rhino 接入、求值器、可选的类过滤器
│   │   ├── model/          各传输层共用的 wire 类型
│   │   └── client/         仅客户端调用的中介；实现按 loader 分别提供
│   ├── src/testmod/     StageWright 任务所运行的场景，以及 JavaScript 校验套件
│   └── src/test/        不需要游戏的纯 JVM 测试
├── fabric/              Fabric 入口与客户端侧实现
├── neoforge/            NeoForge 入口与客户端侧实现
├── stagewright-scenes/  会被装进运行目录 config 下的场景脚本
├── path-replay/         对录制下来的寻路运行做离线分析
├── docs/                文档；从 docs/README.md 开始读
└── scripts/             场景清单、源码检查，以及 MCP 桥接
```

---

## 设计

- **唯一路由器。** `DriverApi.route(method, params)` 是唯一一处真正运行游戏逻辑的函数。MCP
  服务器、WebSocket 服务器与脚本桥各自只翻译参数并调用它，谁都不许自己持有行为。校验套件在
  一部分方法上比对三条传输的结果，所以这项保证来自唯一的路由器，而不是来自穷举比对。
- **server 线程纪律。** 写操作，以及任何触及 level 的读操作，都派发到 server 线程上执行。脚本
  运行在它之外，因此可以安心阻塞等待结果而不会自锁。
- **符合规范的 MCP。** `initialize` 阶段协商协议版本，按回环白名单校验 `Origin` 头以防御 DNS
  重绑定，截图走 `image` 内容块。`McpServer.java` 在代码内联了对应的规范引用。
- **脚本是第一方能力，默认不做沙箱。** 类过滤器是存在的 —— `ScriptClassFilter` 拒绝进程创建、
  反射、裸文件与套接字访问以及 JDK 内部包 —— 但**除非 JVM 以 `-Dworlddriver.sandbox=on` 启动，
  否则它是关闭的**，而构建中没有任何一处传这个参数。这是刻意的：限制脚本能调用什么，就等于
  限制驱动器自身的能力，而任何能够连上 RPC 或 MCP 端点的一方本来就已经掌握了这个进程，所以
  信任边界在端点上，不在解释器上。请把这两个端点当作这台机器上的一个 shell 来对待。
  `mc.script.eval` 额外通过 Rhino 的指令计数观察器施加一个挂钟超时，那是活性保护，不是安全措施。
- **一套源码，两个 loader。** 同一份 `common/` 源码通过 Architectury 同时出 Fabric 与 NeoForge。
  loader 专有的模块只承载入口点和仅客户端调用的实现。

完整的架构，包括各处接缝与线程规则，见
[`docs/dev/architecture.md`](docs/dev/architecture.md)。

---

## 当前状态

这个模组可用，并且在持续开发中；版本号尚未到 1.0，方法面仍在变动。

场景套件在两个 loader 上覆盖三种进程拓扑：无头的专用服务器，自行开启集成服务器的客户端，以及
一台专用服务器加上一个通过 socket 加入其中的真实客户端。第三种正是生产环境安装时的形态，也是
唯一能够对进程边界下断言的形态 —— 所以它的客户端那一半会写出自己的结果文件，任务同样会裁决
那份文件。另有一个对账任务把六次运行相互比对，因为一个在所有拓扑上都被跳过的场景，只是在一个
从未被测过的主题上记下了通过。

场景的数目在 `scripts/stagewright/` 下按 loader 各一份的清单里，那些清单同时也是运行的裁判依据
—— 一个注册了却没有列在清单里的场景会让整趟运行失败。不要从散文里取这个数目，无论是这里还是
别处。

有少数场景被声明为可选失败：它们记录一个已知缺口，但不会让整趟运行失败。
[`CHANGELOG.md`](CHANGELOG.md) 记录每次发布改了什么，[`ROADMAP.md`](ROADMAP.md) 记录还剩下什么。

---

## 索引

- **文档索引**：[`docs/README.md`](docs/README.md)
- **上手指南**：[`docs/guide/getting-started.md`](docs/guide/getting-started.md)
- **三条传输、它们的线上格式与安全性**：[`docs/guide/transports.md`](docs/guide/transports.md)
- **接入某一种具体的 MCP 客户端**：[`docs/guide/mcp-clients.md`](docs/guide/mcp-clients.md)
- **贡献指南**：[`CONTRIBUTING.md`](CONTRIBUTING.md)
- **在本仓库工作的 AI 编码 agent 的约定**：[`AGENTS.md`](AGENTS.md)
- **MCP 规范**：<https://modelcontextprotocol.io/specification/2025-06-18>

English version: see [README.md](README.md).

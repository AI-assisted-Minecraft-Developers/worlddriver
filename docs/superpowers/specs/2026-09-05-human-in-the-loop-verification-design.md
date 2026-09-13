# 人工参与验证环节的设计

> 状态：设计稿，尚未实现。读者：开发者。
>
> 目标：让测试人员在游戏里亲手搭建测试场景、用标记方块标出关键位置、看着机器人跑一遍、
> 然后自己选定测试结果。人工搭出来的场景和选定的结果，要能原样变成 StageWright 的自动回归
> 测试。现有的三条自动测试流水线不改动；人工环节位于它们的上游，负责产出场景和判定标准，
> 本身不是第四条流水线。

## 0. 一句话概括

测试人员在「保持运行」模式的游戏世界里，用 testmod 提供的标记方块（起点、终点、禁入区、
落脚点、观察点）把一块地形标成一个测试场景；`/worlddriver scene save` 把地形保存成原版格式的 NBT
文件，把标记整理成一份 JSON 文件；`/worlddriver scene run` 让机器人跑一遍，跑完后游戏聊天栏列出
自动检查的结果，并给出一排可以点击的按钮，测试人员选「通过 / 失败 / 不稳定」并附一句
说明；`/worlddriver scene accept` 把这一次观测到的数值记录为以后的判定标准。此后这份场景文件由
testmod 里的一个 `SceneProvider` 当作普通的 `human.*` 场景注册进自动测试，没有人在场也照样
运行。

## 1. 现有的、可以直接复用的部分

| 已有的东西 | 用途 |
|---|---|
| `stagewright<Topology><Loader>Hold` 任务 | 测试人员搭场景和观看结果的游戏世界。集成服务器模式下测试人员本人就是客户端玩家；专用服务器模式下测试人员用自己的客户端加入。 |
| `SceneContext` 的公开构造函数与 `advance()` | 「就地运行」不经过 StageWright 的场景运行器，`FixtureRunner` 自己构造上下文、自己每刻推进（见 5.1）。 |
| `ctx.record`、`ctx.check`，以及 `ResultsJsonl` 结果文件里的 `data` 字段 | 自动检查结果和人工判定都通过这条通道写进 results.jsonl，结果判定程序不需要改动。 |
| `JsScenes`（读取 `config/stagewright/scenes/*.js`）的先例 | 「运行目录里的文件也能注册成场景」这条路已经走通，场景文件沿用同一做法。 |
| `SceneProvider` 服务发现（`common/src/testmod/resources/META-INF/services`） | 场景文件的加载器就是一个新的 provider。 |
| 原版的 `StructureTemplate` | 保存和放置地形。NBT 是原版格式，任何服务器都能读取。 |
| `SceneBody`（无头服务器身体）和 `ClientHelm.adopt`（真实玩家身体） | 对应 `--body server` 和 `--body self` 两种运行方式。`SceneBody` 的判据是两个条件的合取：服务器不是专用服务器**且** `BotHooks.isAvailable()`（有客户端舵）时拒绝生成无头身体（既有的覆盖纪律：无头身体的覆盖归专用服务器），拒绝的方式是 `ctx.skip`，它抛 `SceneSkipped`。所以 `server` 只在专用服务器的保持运行模式下可用，`self` 只在集成服务器上可用；就地运行的 `FixtureRunner` 要接住 `SceneSkipped`（见 5.1）。 |
| `ToolCatalog.registerVerb` 与隐藏的 `ToolSchema`（StageWright 的 `mc.test.run` 就是这样注册的） | 人工验证的每个操作先做成 DriverApi 动词，命令只是转调。动词不进 MCP 的工具列表，不占提示词。 |
| `-Pstagewright.scenes=human.*` 过滤参数（系统属性 `stagewright.scenes`） | 这样过滤的一趟跳过清单对账，结论带 `FILTERED — not a gate result` 后缀，本地尚未提交的场景文件只在这样的运行里出现。注意另有一个 `stagewright.filter` 属性（`Scenes.FILTER_PROPERTY`），它只筛场景**不跳过**对账，那样过滤的一趟照样会因为 MISSING-EXPECTED 变红；本设计只用前者。 |

## 2. 与原版客户端的兼容边界

testmod 不打进发布的 jar（`AGENTS.md` 写明：两个发布 jar 里没有任何 StageWright 或 testmod
的内容），因此：

- **标记方块只存在于开发环境的运行中。** 安装了正式 mod 的服务器与原版客户端之间没有任何
  新增的注册项。
- **场景文件里不包含标记方块。** 保存时把标记方块的位置和角色抽取到 JSON 文件里，地形里的
  对应位置恢复成原来的空气或方块，NBT 文件里只剩原版方块和 mod 本身的方块。一份场景文件
  拿到没有 testmod 的服务器上也能正常放置。
- **编写场景需要 testmod。** 标记只有方块这一种形式；在没有 testmod 的世界里编写场景（比如
  用原版的 `minecraft:marker` 实体代替方块）不在第一版里，等有这个需要再做。
- 开发环境里，Fabric 的注册表同步会拒绝原版客户端加入带 testmod 的服务器。这是开发环境本来
  就有的性质（StageWright 自身也带着数据包和维度），不是新增的限制。
- 3.2 节把 Architectury API 加成强制依赖。它本身不注册方块、物品或维度，预期不改变原版客户端
  能否加入正式服务器的性质；这一点在 8 节第 2 步落地时用一个原版客户端实际连一次确认，不凭
  预期。

## 3. testmod 里新增的内容

### 3.1 标记方块 `worlddriver:marker`

只有一个方块，用方块状态属性 `role` 表示角色，不需要方块实体就能表达角色本身；标签和参数
放在可选的方块实体里。

| role | 含义 | 数量 | 运行前的处理 |
|---|---|---|---|
| `origin` | 场景的坐标原点，相对坐标从这里算起 | 恰好 1 个 | 移除 |
| `start` | 机器人出生时脚所在的方块，方块实体里可以带朝向 `yaw` | 恰好 1 个 | 移除 |
| `goal` | 目标位置；`label` 决定目标类型：`block`（默认）、`near:r`（半径 r 以内）、`y:`（只要求高度） | 至少 1 个；多个表示多段行程，按 `label` 里的序号排序 | 移除 |
| `forbid` | 机器人在任何时刻都不得进入的方块（脚或头都算） | 任意 | 移除，运行中每个游戏刻检查 |
| `stand` | 结束时脚必须站在的方块（要求站在地面上、不在水里） | 至多 1 个 | 移除 |
| `watch` | 运行结束后检查该位置的方块：`label` 写 `same` 表示不许变化，或写一个方块 id 表示必须变成它 | 任意 | 移除，保存时记下当时的方块 |
| `via` | 航点：机器人必须依次到达的中途位置。保存时按 `label` 里的序号（`1`、`2`……）写进 `goto` 行程的 `route.via`，不拆成独立的行程；第一版只允许场景里恰好一个 `goal` 时使用，多个 `goal` 又有 `via` 时 `save` 报错 | 任意 | 移除 |
| `pass` | 必经点：整段行程里机器人至少有一个游戏刻处在这个位置附近（`label` 写半径，默认 1）。它不改变机器人的目标，只是事后检查路线是否经过这里 | 任意 | 移除，运行中每个游戏刻检查 |
| `corner` | 场景包围盒的两个对角 | 恰好 2 个 | 移除 |

`via` 和 `pass` 的区别：`via` 是命令，机器人被要求走到那里；`pass` 是检查，机器人自己选路，
测试人员只是断言「正确的路线应当经过这里」。想验证「它应该从水下穿过去」就在水下放 `pass`，
在水面放 `forbid`；想验证「它应该挖穿这堵墙」就在墙里放 `pass`，或者用 `watch` 断言墙上的
方块必须变成空气。

- 外观：一张白色半透明贴图，渲染时按角色染色（起点绿色、终点蓝色、禁入红色、落脚黄色、观察
  紫色、原点白色、角点灰色、航点青色、必经橙色），颜色定义在 `MarkerRole` 上，方块和物品共用。
- 物理性质：没有碰撞箱，`blocksMotion` 为 false，可以像草一样被直接替换。这样测试人员放错
  位置也不会挡路。但运行前一律移除，不依赖这条性质。
- 方块实体 `MarkerBlockEntity { label: String, args: CompoundTag }`。只有 `goal`、`watch`、
  `start` 需要它。第一版通过 `/worlddriver mark` 命令写入；右键打开单行文本输入界面留到第二版。
- 物品 `worlddriver:marker_<角色>`，放在 testmod 的一个创造模式物品栏分页里，测试人员从背包
  取用。

### 3.2 注册入口与 Architectury API

testmod 目前没有任何注册入口。`SceneProvider` 是服务器启动完成后才被发现的，对注册来说太晚。

**这是仓库第一次注册自定义方块。** 全仓库没有任何 `DeferredRegister`、`Registry.register` 或
`RegisterEvent` 的用例；构建脚本只引入了 `dev.architectury.loom` 和 `architectury-plugin` 两个
构建期插件，没有提供 `DeferredRegister` 的运行时库 `dev.architectury:architectury`（Architectury
API）。两条路：两个加载器各写一份原生注册（各几十行，再隔一层跨加载器的登记接口），或者把
Architectury API 加成强制依赖，注册直接用 `DeferredRegister`。**选后者。** 成本量过了，不高：

- 构建：`common` 加 `modImplementation "dev.architectury:architectury:13.0.8"`，`fabric` 加
  `architectury-fabric`，`neoforge` 加 `architectury-neoforge`，版本号进 `gradle.properties`
  （13.0.8 是 1.21.1 的最新版）。Architectury 的模板不显式声明仓库，Loom 自带
  `maven.architectury.dev`；解析不到再加一行。
- 元数据：`fabric.mod.json` 的 `depends` 加 `"architectury": ">=13.0.8"`，`neoforge.mods.toml`
  加一段 `[[dependencies.worlddriver]] modId="architectury"`。玩家从此要一并安装 Architectury API，
  README 的环境要求补一行。
- NeoForge 侧不需要额外绑定：Architectury 13 的 `DeferredRegister.register()` 通过
  `ModContainer.getEventBus()` 找到本 mod 的事件总线（在 architectury-api 14.0 的源码里核实，
  `EventBusesHooks.whenAvailable`；13.0.8 用同一机制，实现时以实际版本为准），旧版要求的
  `EventBuses.registerModEventBus` 不再需要。
- StageWright：`stagewright-common` 编译时依赖 `worlddriver-common:dev`，引用的是 `ToolCatalog`、
  `ToolSchema`、`BotHooks`、`BotApi`、`DriverEvent`、`WorldDriverCommon` 六个类。只要这些类的
  签名不出现 Architectury 类型，StageWright 那边一行不用改；`WorldDriverCommon` 若加了 Architectury
  事件订阅，放在方法体里而不是签名上。StageWright 自己的运行不加载 worlddriver mod，运行期
  不受影响。
- 与 testmod 的关系：testmod 在两个加载器上都折进 `worlddriver` 这个 mod 自身（Fabric 的
  `mods { main { sourceSet testmod } }`，NeoForge 的 `mods { named('main') { sourceSet … testmod } }`），
  所以 `DeferredRegister.create(WorldDriverCommon.MOD_ID, Registries.BLOCK)` 用 worlddriver 自己的
  mod id 即可，不需要第二个 mod。

有了 Architectury API，testmod 自己就能订阅跨加载器的事件（`CommandRegistrationEvent`、
`TickEvent.SERVER_POST`、`LifecycleEvent`），主代码只需要提供一个构造期的入口：
`DeferredRegister.register()` 在两个加载器上都要求在 mod 构造阶段调用，而 testmod 没有构造入口。

```
common/main     net.magicterra.worlddriver.TestContent                  // 接口，只有一个 register() 方法
common/testmod  …/testcontent/MarkerContent implements TestContent       // DeferredRegister 注册方块、物品、方块实体
common/testmod/resources/META-INF/services/net.magicterra.worlddriver.TestContent
```

接口在根包而不在 `testcontent`：NeoForge 开发环境把 main 与 testmod 的输出装成两个 JPMS 模块，
同一个包横跨两个模块是 split package，模块层拒绝启动。testmod 里的包必须是 main 没有的包。

`WorldDriverCommon` 现在没有统一的构造期入口：Fabric 的入口是 `WorldDriverFabric.onInitialize`，
NeoForge 的入口是 `WorldDriverNeoForge` 的构造函数，两边各自调用 `WorldDriverCommon` 的
`onServerStarting`、`onServerStarted`、`registerCommands`。所以新增一个
`WorldDriverCommon.installTestContent()`，内容只有
`ServiceLoader.load(TestContent.class).forEach(TestContent::register)`，由两个入口在构造阶段
各调用一次。发布的 jar 里没有实现类，循环体为空，和 `SceneProvider` 一样：没安装就什么都不
发生。

命令根一并重构：现在的 `/agent`（子命令 `test`、`test list`、`test result`、`port`、`mcp`、
`reload`）是 AgentDriver 时期改名留下的，整个改成 `/worlddriver`；NeoForge 专用的 `/agentserver`
（`spawn`、`goto`、`mine`、`status`、`clear`，只从 `WorldDriverNeoForge` 注册）改成
`/worlddriver server …`。用 mod id 全名而不是缩写，是为了不和别的 mod 的命令撞名：两三个字母的
根（`/wd`、`/ad`）在大型整合包里迟早会撞上，而 mod id 在同一个游戏里保证唯一。不保留旧名。
本设计的命令由 testmod 自己通过 Architectury 的 `CommandRegistrationEvent` 注册，
`dispatcher.register(literal("worlddriver").then(mark…).then(scene…))`：Brigadier 对同名的根字面量
做合并（`CommandNode.addChild` 把子节点并进已有的节点），`/worlddriver server` 就是靠这个从
NeoForge 侧并进去的，`mark` 和 `scene` 同样并进去。`TestContent` 因此不需要 `registerCommands`
方法。

**紧接依赖之后做的重构（8 节第 3 步）。** Architectury API 一旦是强制依赖，两个加载器入口里那
二十来个成对的事件处理器（生命周期、每刻、命令、破坏、放置、死亡、加入、离开、聊天；客户端的
每刻、退出、HUD、聊天）合并成 `common` 里的一份，用 Architectury 的 `LifecycleEvent`、`TickEvent`、
`CommandRegistrationEvent`、`BlockEvent`、`EntityEvent`、`PlayerEvent`、`ChatEvent`、
`ClientTickEvent`、`ClientLifecycleEvent`、`ClientGuiEvent`。趁依赖刚加一次收掉重复，之后新的
事件订阅（包括本设计 testmod 里的）就只有一种写法。这是一次单独的 `refactor:` 提交，跨三个模块
不带 scope，三道闸各跑一趟验证；有三处要留神：`BlockEvent.BREAK` 是破坏前、可取消的事件（Fabric 侧今天用的是破坏后的
`PlayerBlockBreakEvents.AFTER`，NeoForge 侧本来就是破坏前的 `BreakEvent`），合并后两边统一为
破坏前，`block.place` 事件则是 Fabric 侧今天没有的、合并后补齐；客户端聊天的 `GAME_CANCELED`、
`CHAT_CANCELED` 两个变体是特意订阅的（被别的 mod 取消的行也要记进 `mc.client.chat.history`），
Architectury 的 `ClientChatEvent.RECEIVED` 没有这个变体，这一对处理器留在加载器侧；NeoForge 上
`ClientLifecycleEvent.CLIENT_SETUP` 是在 `FMLClientSetupEvent` 的处理器里直接调用的，不经过
`enqueueWork`，今天的客户端注册代码在 `enqueueWork` 里，合并时要保住这一点。

### 3.3 路线的种类：步行、水下、挖掘、飞行

机器人不接受「走哪一种路」的命令，它只接受目标和限制，路线由规划器自己选。所以场景里
「路线种类」不是一个开关，而是由地形、标记和 `config` 里的限制共同逼出来的。现有能力：

| 路线种类 | 机器人现有的能力 | 场景里怎么表达 |
|---|---|---|
| 步行、跳跃、跑酷、搭桥、垒柱 | `goto` 的地面规划器，`Walk`、`StepUp`、`Parkour*`、`BridgePlace`、`PillarUp` 等移动方式 | 默认，无需额外标记 |
| 水面与水下 | `goto` 的水面节点、`SurfaceDive`、`SwimDown`、`SwimUp`、`Swim*Break`、上岸相关的移动方式；水桶落地 `WaterBucketFall` | 起点或终点放在水里；用 `pass` 逼路线走水下、用 `forbid` 封住水面；`config` 里可关 `allowWaterBucketFall` |
| 挖掘 | `goto` 的破坏类移动方式（`TraverseBreak`、`StairUpBreak`、`DownBreak`、`SwimAshoreBreak` 等）；专门的 `mine`（`MineProcess`）和 `escape`（`targetY` 低于脚下时是 `DescendProcess`，高于时是 `EscapeProcess`，两者共用 `escape` 槽；`mc.bot.descend` 这个动词不存在） | `config.allowBreak = true`；`hand` 里给工具；用 `watch` 断言哪些方块必须被挖掉、`forbid` 断言哪些不许挖；每段行程可单独带 `route: {"break": "never"}` 禁止这一段挖掘（`route` 对象的定义见路径选择设计） |
| 飞行 | 仅鞘翅：`elytraFly`（`ElytraProcess`），带一个粗粒度的三维空中规划器 `ElytraPathfinder`。创造模式飞行没有驱动能力，只有一个调试开关 | 行程的 `verb` 写 `elytra`，参数原样传给 `elytraFly`（`pos`、`pitch`、`fireworks`、`ticks`）；`hand` 里给鞘翅和烟花火箭，并由 `equip` 字段穿上；起点放在高处；空中的 `pass` 标记用来断言飞行走廊，`forbid` 用来断言不撞山 |

于是 JSON 里每段行程写成：

```json
"legs": [
  {"verb": "goto",   "goal": [11, 3, 5], "goalKind": "near:1", "budget": 1200, "route": {"break": "never"}},
  {"verb": "mine",   "params": {"block": "minecraft:iron_ore", "count": 3}, "budget": 2400},
  {"verb": "elytra", "params": {"pos": [80, 70, 5], "fireworks": true}, "budget": 600}
]
```

`via` 标记在保存时按序号写进 `goto` 行程的 `route.via`，由机器人的意图进程依次到达（路径选择
设计 2.5 节）；场景侧不展开成多段行程，`via` 只有一层实现。测试人员不必手写这一段。这里有
一个跨文档的前置：`via` 依赖路径选择设计的第 2 步（意图对象的目标列表，今天 `Intent` 是不可变的
单目标），那一步没落地前 `route.via` 无处可去；`route` 的字段集也以那份设计为准，本设计不复制
它的字段表。`verb` 第一版支持 `goto`、`mine`、`escape`、`elytra`（往下挖写 `escape` 加
`targetY`）。`goto`、`mine`、`elytra` 走 `awaitMs`；`escape` 没有 `awaitable`，`FixtureRunner`
每刻读 `mc.bot.status` 的 `escape` 槽，`active` 变假就算这段结束。其余进程（`build`、`tower`、
`bridge` 等）等有场景需要时再加，加法只是往 `FixtureRunner` 的分派表里添一行。

### 3.4 动词 `worlddriver.scene.*` 与命令 `/worlddriver scene …`

每个操作先做成 DriverApi 的动词，由 testmod 通过 `ToolCatalog.registerVerb` 注册；命令只是把
参数转成一次 `DriverApi.route` 调用。这样 RPC、脚本、Journeyman 和 CI 都能保存、运行、读判定，
而不只是进了游戏的人；也符合「游戏行为只在 DriverApi 里」的硬规则。动词用隐藏的
`ToolSchema` 声明（`mc.test.run` 的做法），不进 MCP 的工具列表，不占提示词。命名空间用
`worlddriver.`。`ToolCatalog.enforceNamespacePolicy` 的代码只查两件事：名字里至少有一个点，且
不以 `mc.` 开头（`mc.test.` 除外，那是 StageWright 独占的）；`mc.*` 是核心保留的，扩展入口放行
其余带点的名字，本 mod 的 id 就是最自然的前缀。「隐藏」是调用方在 `ToolSchema` 上调 `.asHidden()`
（`TestRunVerb` 的做法），`registerVerb` 本身不区分。

| 动词 | 参数 | 作用 |
|---|---|---|
| `worlddriver.mark` | `role`、`label?`、`pos?`（省略时取调用者准星所指） | 放一个标记；有 label 时写入方块实体 |
| `worlddriver.scene.save` | `name`、`verb?`（`goto`\|`mine`\|`escape`\|`elytra`，默认 `goto`）、`budget?` | 用两个 `corner` 标记确定包围盒，抽取标记，把 NBT 和 JSON 保存到 `config/worlddriver/scenes/<name>.{nbt,json}` |
| `worlddriver.scene.list` | 无 | 列出本地的场景文件和各自最近一次的人工判定 |
| `worlddriver.scene.place` | `name`、`pos?` | 按原点放回地形和标记，便于继续编辑 |
| `worlddriver.scene.run` | `name` 或 `here`、`body?`（`self`\|`server`）、`watch?` | 就地运行；`here` 表示直接使用世界里现有的标记，不保存；`watch` 为真时每 20 刻向调用者发一行进度（位置、当前行程、已经过的 `pass`） |
| `worlddriver.scene.verdict` | `name`、`verdict`（`pass`\|`fail`\|`flaky`）、`note?` | 记录人工判定 |
| `worlddriver.scene.accept` | `name` | 把最近一次运行观测到的数值写进 JSON 的 `expect` 字段，作为以后的判定标准 |

命令一一对应：`/worlddriver mark <role> [label]`、`/worlddriver scene save|list|place|run|verdict|accept …`，
聊天栏按钮点击执行的也是这些命令。`run` 的 `body` 取值受运行模式限制（见第 1 节的表）：
专用服务器的保持运行模式下默认 `server`，集成服务器下只能 `self`，填错直接报错说明原因。

## 4. 场景文件的格式

`<name>.nbt`：原版 `StructureTemplate` 格式，包含包围盒内的全部方块和实体，标记已移除。
尺寸限制：自动测试里一个场景默认占 3×3 个区块（`Scene.withChunkRadius` 默认 1），也就是
48×48 格，可用偏移是 `dx, dz ∈ [-16r, 16r+15]`。硬规则 11 的闸 `check_scene_arena.py` 只读源码里
场景自己写的 `ctx.setBlock` 偏移，`human.*` 场景的地形来自运行期的 NBT，闸看不见；而超出强制
加载窗口的写入会成功（按需加载区块），只是 PREP 没等它，于是大部分时候通过、偶尔失败，读起来
像机器人的问题，这正是那条闸存在的原因。所以 `FixtureIO` 在**保存**时按包围盒算出所需的
`chunkRadius` 写进 JSON，`HumanScenes` 注册时用 `Scene.withChunkRadius` 申请；再给
`check_scene_arena.py` 加一条：扫资源目录里的 `scenes/*.json`，用 `size`、`origin` 对账声明的
`chunkRadius`。就地运行没有这个限制。

`<name>.json`：

```json
{
  "name": "human.riverBankTwoHigh",
  "author": "gardel", "created": "2026-09-05T18:40:00+08:00",
  "terrain": "run_world",              // 或 superflat / generated；就地运行时忽略
  "size": [24, 12, 24], "origin": [0, 0, 0], "chunkRadius": 1,   // chunkRadius 由 save 按包围盒算出
  "body": "server",                    // 就地运行的默认；自动测试里按拓扑定：专用服务器用 server，集成服务器用 self，另一种 skip
  "hand": ["minecraft:dirt 16"],
  "equip": {},                         // 例如 {"chest": "minecraft:elytra"}
  "config": {"allowBreak": true, "allowPlace": true},
  "legs": [
    {"verb": "goto", "goal": [11, 3, 5], "goalKind": "block", "budget": 1200, "route": {"via": [[6, 1, 9]]}}
  ],
  "markers": {
    "start": {"pos": [2, 1, 5], "yaw": -90},
    "via": [ {"pos": [6, 1, 9], "order": 1} ],
    "pass": [ {"pos": [4, -2, 7], "radius": 1} ],
    "forbid": [[6,1,5],[6,2,5]],
    "stand": [11, 3, 5],
    "watch": [ {"pos": [9, 2, 5], "was": "minecraft:dirt", "want": "same"} ]
  },
  "expect": {                          // 由 accept 写入；没有这一段时只检查 markers 里的硬性条件
    "arriveBy": 700, "repathsMax": 6, "recoveryHopsMax": 2, "digsMax": 3
  },
  "verdicts": "human.riverBankTwoHigh.verdicts.jsonl"
}
```

`<name>.verdicts.jsonl` 每行一条记录：
`{when, who, build, topology, auto:{各项自动检查}, observed:{ticks, repaths, hops, digs}, human:"pass|fail|flaky", note}`。
这是人工环节的产物，也是 `accept` 的输入：默认取最近一条判定为 `pass` 的记录里的
`observed` 数值，再放宽 20%。

## 5. 运行过程与检查项

### 5.1 运行一次会发生什么（testmod 里的 `FixtureRunner`）

1. 解析 JSON。如果是在自动测试里运行，就用 `placeInWorld` 把地形放到分配的测试区域原点；
   如果是就地运行，原点就是世界里的 `origin` 标记。两种情况下 `FixtureRunner` 的主体是同一段
   代码，区别只在谁驱动它：自动测试里由 StageWright 的场景运行器调用 `ctx.advance()`；就地运行
   时没有运行器，`FixtureRunner` 自己构造一个 `SceneContext`（它的构造函数是公开的），并在
   Architectury 的 `TickEvent.SERVER_POST` 里调用 `ctx.advance()`，直到场景结束（每刻钩子今天在
   两个加载器入口里各写死两行，8 节第 3 步把它们合并进 `common`；无论合并与否，testmod 都自己
   订阅 Architectury 事件，不需要主代码提供挂载点）。就地运行的 `ctx.record` 内容写进
   `verdicts.jsonl`，而不是 results.jsonl。

   就地运行的出口：`SceneContext` 有三种非正常出口，`FixtureRunner` 都要接住并写进
   `verdicts.jsonl`：`ctx.skip` 抛 `SceneSkipped`（比如 `body: server` 在集成服务器上）；`advance()`
   返回 `STEP_TIMEOUT`（某一步超过 `within`，默认 100 刻，原因在 `failureReason`）；步骤排空时
   软违规抛 `SceneFailure`。`legs[].budget` 映射成该段 `await(...).within(budget)`。无论哪种出口，
   结束时都调 `ctx.runCleanups`，否则 pin 不释放，`BotConfig` 留在场景改过的状态。
2. 移除世界里所有标记方块（包括 `place` 放回来的那些）。
3. 按 `body` 字段创建机器人身体：`server` 用 `SceneBody`（只在专用服务器上可用），`self` 用
   `ClientHelm.adopt`（只在集成服务器上可用）。自动测试里两种运行模式各跑各的那一种，另一种
   `ctx.skip` 跳过并写明覆盖在哪里，和现有场景一致。发放 `hand` 里的物品，按 `config` 设置
   `BotConfig`（通过 pin 机制，结束后恢复）。
4. 逐段执行 `legs` 里的动作，`TickWatcher` 每个游戏刻检查 `forbid`、记录 `pass` 是否已经过，同时累计
   三个数：重新规划次数取 `Walker.lastStats` 的变化次数（`wd.clientTunnels*` 场景就是这么数的）；
   恢复跳跃次数和挖掘次数今天只出现在日志里，要在 `Walker` 上加两个单调计数器，调用者取前后
   差值。现有的同形物（`strideGuardSkips`、`futileGateBuckets`、`lastStats`）都是 JVM 全局的静态
   量，多个身体时分不清是谁的；新计数器做成 `Walker` 的实例字段。
5. 结束时检查 `stand`、`watch`、`expect`，把 `observed` 里的数值全部用 `ctx.record` 记录。
6. 就地运行时额外向发出命令的玩家发送一段聊天信息：自动检查逐条显示通过或失败，然后是三个
   可点击的按钮「通过」「失败」「不稳定」（通过 `ClickEvent.RUN_COMMAND` 执行
   `/worlddriver scene verdict …`），以及一个「记录为标准」按钮（执行 `accept`）。测试人员也可以先
   输入 `/worlddriver scene verdict … 说明` 再点击按钮。

### 5.2 检查项分三层

- **硬性条件**：由标记方块给出，默认启用。包括依次到达每个 `via` 和 `goal`、经过每个 `pass`、
  从未进入 `forbid`、结束时站在 `stand`、`watch` 位置的方块符合要求。这一层不需要人参与，场景搭好就有。
- **数值范围**：由 `expect` 字段给出，通过 `accept` 写入。要求游戏刻数、重新规划次数、恢复
  跳跃次数、挖掘次数不超过上一次通过时的数值加上放宽量。这一层就是「测试人员选定的那个
  结果」被记录下来之后的样子：被判定为通过的那一次运行成了标准。
- **人工判定**：记录在 `verdicts.jsonl` 里。它不参与自动测试的结论；自动测试运行这个场景时，
  `FixtureRunner` 读出最近一条人工判定，用 `ctx.record` 写进 results.jsonl 的 `data` 字段，`/worlddriver scene list` 和 `/worlddriver test result` 都能看到最近一次人工判定的内容。
  results.jsonl 的 `data` 只被控制台渲染，`Verdict` 从不读它，所以人工判定在机制上就不可能改变
  自动测试的结论；`data` 为空时整个字段不输出。

### 5.3 什么时候它成为自动测试的一部分

把场景文件放进 `common/src/testmod/resources/scenes/`，写进同目录的索引文件 `scenes/index.txt`，
并写进两份 `expected-scenes-*.txt` 清单。`HumanScenes`（一个 `SceneProvider`，登进
`META-INF/services/net.magicterra.stagewright.scene.SceneProvider`）启动时读索引文件而不是扫描
目录：Java 没法可移植地枚举 classpath 里的目录，`JsScenes` 读的是运行目录的文件系统路径，
不是资源。场景名统一带 `human.` 前缀；已提交并登记到清单的场景就是普通场景，和 `wd.*` 场景
一起判定。索引、清单和场景文件必须在同一次提交里一起进入仓库，这是之前的教训：清单是判定
程序的一部分。

本地目录 `config/worlddriver/scenes/` 只在两种情况下被扫描：保持运行模式（系统属性
`stagewright.hold` 为真），或显式给了 `-Dworlddriver.localScenes=true`。`HumanScenes` 直接
`Boolean.getBoolean("stagewright.hold")`，不调 `StageWrightCommon.holding()`：那个类在
`stagewright-common` 里，worlddriver 的 testmod 只依赖 `stagewright-api`，加 `stagewright-common`
是被明确拒绝过的版本环（`common/build.gradle` 里写着原因）；代码注释里写明这是同义复制。
正式测试两种情况都不满足，本地文件不注册。原因是判定程序的 UNDECLARED 检查：清单里出现过的
名字前缀（`wd.`、`cap.`，以及登记了第一份人工场景之后的 `human.`）都会成为受检命名空间，任何
注册了却不在清单里的同前缀场景都会把结论变红。本地尚未提交的场景如果在正式测试里被注册，
就是这种情况。这道检查只在没有过滤参数、清单非空的那一趟跑，canary 豁免；带 `stagewright.scenes`
过滤的那一趟不对账，本地文件在那一趟里靠的是上面的目录开关本身，不是这道检查。

`verdicts.jsonl` 是本地文件，不提交；提交的是 `accept` 写进 JSON 的 `expect`。

## 6. 测试人员的一次完整操作流程（草案）

```
./gradlew stagewrightDedicatedServerFabricHold         # 专用服务器保持运行；再用自己的客户端加入，旁观无头身体
                                                       # （改用 IntegratedServerFabricHold 则驱动自己的身体，第一人称或 F5 视角观看）
# 进入游戏后：
/worlddriver scene place human.riverBankTwoHigh        # 或者从零开始：飞到空地，放两个 corner 和一个 origin
…用创造模式搭建地形，放置 start / goal / forbid / stand / watch 标记…
/worlddriver mark goal near:1                          # 对准终点方块
/worlddriver scene run here --body server --watch      # 无头身体运行，测试人员在旁观看
                                                       # 聊天栏显示：✓ 到达  ✗ 进入了禁入区  [通过][失败][不稳定][记录为标准]
/worlddriver scene verdict here fail 挖穿了岸脚的那一格
…修改地形或调整检查项…
/worlddriver scene save human.riverBankTwoHigh --verb goto --budget 1200
/worlddriver scene run human.riverBankTwoHigh
点击 [通过] → 点击 [记录为标准]
```

同一套流程也可以不进游戏：起 hold 之后用 `rpc.py` 调 `worlddriver.scene.run` 与
`worlddriver.scene.verdict`，例如让 CI 在夜里把本地目录里的场景全跑一遍、把结果留在
`verdicts.jsonl` 里等人早上看。

然后把 `config/worlddriver/scenes/human.riverBankTwoHigh.*` 复制到 testmod 的资源目录，
登记清单，提交。

## 7. 明确不做的事

- 不做录像和回放。测试人员看的是正在运行的机器人。回放沿用已有的 replay 语料机制。
- 不增加第四条自动测试流水线，不修改 `Verdict`。人工判定不改变自动测试的结论。
- 第一版不做图形界面，标签通过命令写入。方块实体的右键界面留到第二版。
- 不做标记实体，也不识别原版的 `minecraft:marker`。标记只有方块一种形式，够用为止。

## 8. 实现步骤（每一步可以单独提交、单独测试）

1. `refactor(bot)`：命令根 `/agent` 改名 `/worlddriver`，`/agentserver` 改成 `/worlddriver server`。
2. `build`：Architectury API 13.0.8 加成三个模块的 `modImplementation`，两份 mod 元数据声明依赖
   （元数据是依赖声明的一部分，和 gradle 同一提交）；三道闸各跑一趟，确认两个加载器都能带着它
   启动，再用原版客户端连一次正式构建的服务器。
3. `refactor`：两个加载器入口的事件处理器合并成 `common` 里的一份 Architectury 订阅（3.2 节末尾的
   映射与三处留神）；客户端聊天的取消变体留在加载器侧。三道闸各跑一趟，失败集与第 2 步对比。
4. `feat(bot)`：`TestContent` 服务接口，`WorldDriverCommon.installTestContent()` 里的服务发现
   循环，两个加载器入口各加一行调用（发布的 jar 里为空操作）。
5. `test(scenes)`：`MarkerContent`：`DeferredRegister` 注册方块、`role` 属性、方块实体、物品、
   贴图、语言文件；增加一个场景 `wd.markerBlockNeverBlocksMotion` 守住它的物理性质。
6. `test(scenes)`：`SceneFixture`（JSON 数据结构，含 `chunkRadius`）和 `FixtureIO`（NBT 保存与
   放置、标记抽取、按包围盒算 `chunkRadius`）；在 `:common:test` 里为 JSON 编解码和标记抽取写
   纯逻辑单元测试；`check_scene_arena.py` 加对账 `scenes/*.json` 的那一条。
7. `test(scenes)`：`worlddriver.mark`、`worlddriver.scene.save|place|list` 四个动词与对应命令
   （testmod 经 `CommandRegistrationEvent` 挂到 `/worlddriver` 下）。
8. `test(scenes)`：`FixtureRunner`（`TickEvent.SERVER_POST` 推进、三种出口、`runCleanups`）、
   `HumanScenes` provider 与索引文件、`worlddriver.scene.run|verdict|accept` 三个动词与对应命令，
   `Walker` 上的两个实例计数器；提交一份示范场景文件到资源目录并登记清单与索引。**前置**：
   路径选择设计的第 2 步（意图对象的目标列表），否则 `route.via` 无处可去。
9. `docs`：编写操作手册 `docs/user/human-verification.md`，`worlddriver-rpc` 技能的方法表补上
   新动词，README 的环境要求补上 Architectury API，在 DOCMAP 登记。

前四步是仅有的改动主代码的地方：一次改名，一个新依赖，一次事件处理器的合并，一个空接口加一个
服务发现循环。

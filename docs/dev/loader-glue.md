# 加载器胶水：什么留在 `fabric/`、`neoforge/`，什么进了 `common`

> 2026-09-06 起，Architectury API 是强制依赖（`gradle.properties` 的
> `architectury_api_version`，两份 mod 元数据都声明了它）。这份文档讲的是它带来的分工，
> 逐条对着源码写；每个断言旁边给的是文件名，不是行号。

## 一句话

事件订阅和注册表写一遍，放在 `common`；两个加载器的入口只剩下**没有跨加载器形式**的东西。

## `common` 里的两份订阅

| 类 | 谁调用 | 订阅了什么 |
|---|---|---|
| `WorldDriverEvents.register()` | 两个加载器入口，mod 构造阶段各一次 | `LifecycleEvent` 的 STARTING / STARTED / STOPPING，`TickEvent.SERVER_POST`（先 `ScriptEvents.fireTick()` 再 `ServerAvatarManager.tickAll()`），`CommandRegistrationEvent`（`/worlddriver …`），`BlockEvent.BREAK` / `PLACE`，`EntityEvent.LIVING_DEATH`，`PlayerEvent.PLAYER_JOIN` / `PLAYER_QUIT`，`ChatEvent.RECEIVED` |
| `WorldDriverClientEvents.subscribe()` | 两个客户端入口 | `ClientTickEvent.CLIENT_POST`（`BotApiImpl.clientTick`），`ClientLifecycleEvent.CLIENT_STOPPING`（`FocusPolicy.release`），`ClientGuiEvent.RENDER_HUD`（`MouseYieldHud`） |
| `WorldDriverClientEvents.install()` | 两个客户端入口，**渲染线程上** | 不是订阅：构造 `ClientDriverApiImpl` 与 `BotApiImpl` 并登记到 `ClientHooks` / `BotHooks` |

`install()` 和 `subscribe()` 分开，是因为两个加载器把控制权交给 mod 的时机不同：Fabric 的客户端
入口本来就在渲染线程上，两个连着调；NeoForge 的 `FMLClientSetupEvent` 在 mod 加载工作线程上，
`subscribe()` 直接调，`install()` 放进 `event.enqueueWork`。Architectury 自己的
`ClientLifecycleEvent.CLIENT_SETUP` 在 NeoForge 上是在那个事件的处理器里**直接**调用的，不经过
`enqueueWork`，所以没有拿它来做安装。

## 合并时变了的三件事

- **`block.break` 在两个加载器上都是破坏前。** Fabric 侧原来订阅的是 `PlayerBlockBreakEvents.AFTER`，
  NeoForge 侧本来就是破坏前的 `BreakEvent`。Architectury 的 `BlockEvent.BREAK` 在 Fabric 上由
  `MixinServerPlayerGameMode` 触发，在 NeoForge 上映射到 `BreakEvent`。链上更早的监听器取消了
  破坏，报告也一起没有——NeoForge 侧一直如此。
- **`block.place` 在 Fabric 上有了。** 原来只有 NeoForge 的 `EntityPlaceEvent` 发它。现在 Fabric 由
  `MixinBlockItem.place` 触发，只覆盖玩家手里的方块物品放置；NeoForge 仍是 `EntityPlaceEvent`，
  范围更宽。`placer` 为空时两边都不发。
- **`entity.death` 是死亡判定时，不是死亡之后。** Fabric 侧原来是 `AFTER_DEATH`。位置一样，
  载荷一样。

`chat.message` 的正文两边都取消息组件的 `getString()`：Fabric 上是 `decoratedContent()`，
NeoForge 上是 `ServerChatEvent.getMessage()`，原版装饰是恒等的，所以和原来的
`signedContent()` / `getRawText()` 相同。

## 留在加载器侧的东西，以及为什么

| 位置 | 内容 | 为什么留下 |
|---|---|---|
| `WorldDriverFabric` | `FabricAvatarBodies` 装进 `ServerAvatarBodies`，`ServerWorldEvents.UNLOAD` 时清它的缓存 | 身体工厂本来就是加载器各一份（NeoForge 用 `FakePlayerFactory`） |
| `WorldDriverNeoForge` | `FakePlayerFactory` 身体工厂；`ServerAvatarCommand`（`/worlddriver server …`）经 `CommandRegistrationEvent` 注册 | 命令骑在 NeoForge 的 `FakePlayer` 上，没有 Fabric 对应物 |
| `WorldDriverFabricClient` | `ClientReceiveMessageEvents` 的 GAME / CHAT 及两个 `_CANCELED` 变体 | `mc.client.chat.history` 要记下被别的 mod 取消的行；Architectury 的 `ClientChatEvent.RECEIVED` 没有取消变体 |
| `WorldDriverNeoForgeClient` | `ClientChatReceivedEvent(receiveCanceled = true)` | 同上 |

## testmod 的构造期入口

`WorldDriverCommon.installTestContent()` 在两个入口的构造阶段各调一次，用 `ServiceLoader` 找
`net.magicterra.worlddriver.TestContent` 的实现并调用 `register()`。testmod 在两个
加载器上都折进 worlddriver 这个 mod 自身，所以服务文件对同一个类加载器可见；发布的 jar 里没有
实现，循环为空。`DeferredRegister.register()` 只在构造阶段有效，这是 `SceneProvider`（服务器
启动后才被发现）做不到的。

接口放在根包而不是实现所在的 `testcontent` 包，是 NeoForge 的模块系统定的：开发环境里 main 与
testmod 的输出是两个 JPMS 模块，同一个包出现在两个模块里就是 split package，模块层直接拒绝
构建（`Modules generated_… and worlddriver export package … to module rhino`），服务器起不来。
所以 **testmod 里的每个包都必须是 main 里没有的包**——`bot.stagewright.scene` 和
`testcontent` 都满足这一条，新加包时照此办理。

## 命令根的合并

`/worlddriver` 这个字面量在 `WorldDriverCommon.registerCommands`、`ServerAvatarCommand`，以及
将来 testmod 的场景命令里各自 `dispatcher.register` 一次；Brigadier 的 `CommandNode.addChild` 把
同名根的子节点并进已有的节点，所以三处各管自己的子树，权限门放在各自的子树上而不是根上。

## 给 StageWright 的边界

`stagewright-common` 编译时引用 worlddriver 的 `ToolCatalog`、`ToolSchema`、`BotHooks`、`BotApi`、
`DriverEvent`、`WorldDriverCommon`。这六个类的**签名**里不出现 Architectury 类型；订阅都在方法体里。
StageWright 自己的运行不加载 worlddriver mod，所以这个依赖对它是不可见的。

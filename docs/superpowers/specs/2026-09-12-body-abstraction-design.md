# 身体抽象层的设计：一套 Bot 层，三种身体（真玩家、服务端玩家、NPC）

> 状态：设计稿，P0 进行中。读者：开发者。
> 2026-09-14 拍板：服务端身体是**给第三方扩展用的公开面**，留在模组本体（`bot/sim/` 不搬）；NPC 的第一具身体是
> testmod 里一个**自定义的猪灵**实体。§0 第 2 条、§3.2、§3.3、§4 P1 与 §6 按此改过；客户端身体的挖掘/用物已于同日
> 脱离 `keyAttack`/`keyUse`（`ClientIntents` + `MinecraftMixin`）。
> 相关阅读：`docs/fake-player-parity.md`（三具服务端身体的边界表，本设计的前提）、
> `docs/world-view-parity.md`（三个 `WorldView` 的差异）、`docs/dev/bot-layering.md`（bot 层的缝）、
> `ROADMAP.md` 的 E1（执行层去全局键盘化，本设计是它的延伸）。
> 调研依据：2026-09-12 对 Baritone、Automatone、PlayerEngine、Citizens2/Sentinel、Carpet、
> Fabric API / NeoForge `FakePlayer`、AI-Player、mc_aiplayer、Taterzens、EasyNPC 的逐项对照，
> 以及对 1.21.1 反编译产物核实的原版事实（见 §2）。

## 0. 结论先行

1. **模组本体只认一个接口 `Body`。** 寻路、行走器、进程、调度器全部只看 `Body`；`Body` 的核心是
   「一个 `LivingEntity` 加一组可选能力」，不再是 `Player`。模组本体带两个实现：`ClientPlayerBody`
   （`LocalPlayer`，`self`）和 `ServerPlayerBody`（真正 join 进玩家列表的 `ServerPlayer`）——后者是
   **给第三方扩展用的公开面**，所以 `bot/sim/` 和 `/worlddriver server` 留在发布 jar 里。
2. **testmod 提供第三个实现 `LivingBody`（任意 `LivingEntity`，NPC 走这条）和第一只 NPC：一个自定义的猪灵
   实体。** 三个实现都是纯原版代码，不需要 loader 目录；`FakePlayerFactory` 路线退役。
3. **三种身体共用同一条执行通道：写 `xxa/zza/jumping/shiftKeyDown`，让原版 `travel()` 算物理。**
   所有活着的先例都这么做（Baritone、Automatone、PlayerEngine、Carpet、Citizens 的 `EntityMoveControl`、
   mc_aiplayer），没有一个 `setDeltaMovement`；我们手写 tick 的 `ServerPlayerAvatar.step()` 是孤例，
   parity 边界表 4.2 的 A1/A2 两行就是它的代价。
4. **NPC 不是另一套寻路。** 原版 `MoveControl.tick()` 最终写的也是 `zza`（`Mob.setSpeed` 顺带
   `setZza`），所以「玩家走输入位、NPC 走导航」是伪二分；`LivingBody` 的下游与玩家完全相同，只多一件事：
   **这一刻身体的腿归谁**（我们驱动时关掉它自己的 goal/navigation）。给真 `Mob` 保留一个可选的
   `NavigationMover`（走原版导航），作为对照组，不作为主路。
5. **一具身体一个名字，RPC 用一个可选的 `body` 参数选身体，不加新动词。** 客户端身体叫 `self`，
   testmod 注册的身体按名字寻址；`mc.bot.status` 列出全部身体。这就是「同一套工具驱动玩家也驱动 NPC」。

## 1. 现状：什么已经是对的，什么在挡路

| 已有 | 位置 | 判断 |
|---|---|---|
| `Avatar` 接口 | `bot/movement/Avatar.java` | **方向对，类型错。** 它已经是 Walker 唯一的执行面，但 `Player player()` 是返回类型，`releaseInputs()`/`clearInventoryCraftGrid()` 等 default 方法直接调 `Player` 的方法；javadoc 里「Later phases add `MobAvatar`」是从未兑现的承诺（DOCMAP 已记）。 |
| `BodyCapabilities` | `bot/movement/BodyCapabilities.java` | 形状对，但只有 `PLAYER` 一个值、没人按它分支。 |
| `pathfinder/` 70 个文件 | `bot/pathfinder/` | **零客户端类型**，`WorldView` 是纯 `BlockPos` 接口，`SearchScope` 明确不带实体。不用动。 |
| `process/` 33 个文件 | `bot/process/` | 31 个干净；`BotProcess.tick(Avatar, WorldView, BotState)` 是标准签名，`tick(Minecraft,…)` 只是桥。 |
| 三个 `WorldView` | `ClientWorldView`、`world/LevelWorldView`、`world/ServerWorldView` | 已共用 `CellRules`；服务端视图按构造得到（`new LevelWorldView(level, entity)`）。不用动。 |
| `ServerWorldDriver` + `ServerAvatarManager` | `bot/sim/` | 服务端身体的 tick 泵与注册表，形状可以直接泛化成任意 `Body` 的注册表。 |
| `JoinedPlayerBodies.JoinedBody` | `bot/sim/` | 走 `PlayerList.placeNewPlayer`，与 Carpet 一致，是唯一在 `level.players()` 里、有区块票据的假身体。**保留，但 `tick()` 覆写成空是错的**（见 §2 第 3 条）。 |
| `SceneBody` / `ClientHelm` / `FixtureRunner.Helm` | testmod | 「按拓扑选身体」的规则已经在，`Helm` 是运行器眼里的身体，只差第三种实现。 |

挡路的三处墙，按大小：

1. **`Avatar.player()` 的 `Player` 类型**，以及 `BotInput`（静态、每个方法收 `Minecraft`）、
   `LookController.apply(LocalPlayer)` 两个签名。
2. **`scheduler/` + `auto/` 反射层：29 个文件里 24 个绑死 `Minecraft`**，根在 `Chain.priority/tick(Minecraft)`，
   每条链继承了这个绑定。`BotApiImpl.clientTick` 是整条客户端 tick 循环（62 处客户端引用）。
3. **`util/BotInteract`（55 处）与 `InteractionCommands`（28 处）**：所有拿物、瞄准、放置、攻击的原语；
   `mc.bot.attackEntity` 动词绕过 `Avatar` 直接打 `mc.gameMode.attack`。

## 2. 原版事实（1.21.1，反编译核实），决定了下面的设计

1. `navigation`/`moveControl`/`jumpControl`/`lookControl` 四个字段**都在 `Mob` 上**，`LivingEntity` 没有；
   `PathNavigation(Mob, Level)` 在类型上绑死 `Mob`。`Player` 和 `ArmorStand` 都不是 `Mob`。
2. 输入字段 `xxa/yya/zza`（公有）、`jumping`、`setJumping`、`jumpFromGround()`、`travel(Vec3)`、`aiStep()`
   **都在 `LivingEntity` 上**；`setZza/setXxa` 三个 setter 只在 `Mob` 上，`Player` 要直接写字段。
3. **`ServerPlayer.tick()` 不调 `super.tick()`，物理全在 `doTick()` 里**；`doTick()` 全树只有
   `ServerGamePacketListenerImpl.tick()` 一个调用点，而那只被 `ServerConnectionListener` 里的真 channel 驱动。
   所以一具 join 了但 connection 不在监听器里的 `ServerPlayer`，拿得到 `tick()`（`gameMode.tick()` 连续挖掘、
   `containerMenu.broadcastChanges()`），永远拿不到 `doTick()`。Carpet 的解法是自己泵：
   `tick() { super.tick(); this.doTick(); }`。我们的 `JoinedBody.tick()` 覆写成空，两头都断了。
4. `Mob.serverAiStep()` 依次 `navigation.tick → moveControl.tick → lookControl.tick → jumpControl.tick`，
   `MoveControl` 的 MOVE_TO 分支只做 `setYRot` + `setSpeed`，而 `Mob.setSpeed(f)` 覆写成 `super.setSpeed(f); setZza(f)`。
   **`Player` 没有覆写 `setSpeed`**，所以往玩家身上塞 `MoveControl` 必须自己补 `zza`。
5. 无连接的 `ServerPlayer` 在这些方法里解引用 `connection` 会 NPE：`indicateDamage`（挨一次伤害就炸）、
   `onEnterCombat/onLeaveCombat`、`onEffectAdded/Updated/Removed`（加药水就炸）、`openMenu`、`closeContainer`、
   `teleportTo` 各重载、`displayClientMessage`；`doTick()` 里三处 `connection.send`。`JoinedBody` 的
   `SilentConnection` 已经绕过这一族，`FakePlayer` 路线没有——这也是它退役的理由之一。
6. `ServerPlayer.checkFallDamage` 对**任何** `ServerPlayer` 都是空覆写（摔伤由客户端上报），换身体不解决
   （parity X2-4）；`LivingBody` 反而没有这个问题。

## 3. 目标形状

### 3.1 接口（模组本体，`bot/body/`）

```
Body                                     // 取代 Avatar；Walker/进程/调度器只看它
  LivingEntity entity()                  // 唯一必有的东西
  @Nullable Player asPlayer()            // 只有玩家身体非空；调用方必须判空
  Level level();  WorldView world()      // 视图按构造得到，和今天一样
  Locomotion locomotion()                // 必有：commandMove/Forward/Jump/Sneak/Sprint、aim(yaw,pitch)、requestLookSnap、releaseInputs
  Optional<Hands> hands()                // 可选：holdPlaceable/selectTool/setSelectedSlot/place/placeOn/breakHold/continueDestroy/canBreak/destroyProgress/useBlock/useItemInHand/attackEntity
  Optional<Containers> containers()      // 可选：recipeManager/placeRecipe/containerClick/closeContainer/clearInventoryCraftGrid
  BodyCapabilities capabilities()        // 扩成：canPlace/canBreak/canCraft/canSprint/canSwim/canClimb/stepUp/jumpUp/width/height/eyeHeight
  BodyId id()                            // "self"、"player:<name>"、"npc:<name>"
  Refusal ready()                        // BodyReady 的判定搬到身体上：客户端身体问屏幕/暂停/区块，服务端身体问是否在世界里、区块是否加载
```

- `Locomotion` 是今天 `Avatar` 的 `command*` 那一组，唯一的实现约定：**只写实体自己的输入字段**，
  从不写速度、从不传送（传送只允许在卡死兜底里，和 Citizens 的 `StuckAction` 一样）。
- `Hands`/`Containers` 是今天 `Avatar` 里「只有玩家才有」的那两组方法，原封不动搬过去。
  `attackEntity` 的 `BlastFooting` 守卫仍在接口的 default 方法里，理由不变（每个不变式只许一条代码路径）。
- 需要手的进程（`Mine`、`Build`、`Craft`、`Bunker`、`Bridge`…）在 `attach` 时检查 `hands().isPresent()`，
  没有就以 `{ok:false, reason:"no_hands"}` 拒单——和 `BodyReady` 同一形状，一个词。`Intent`/`Follow`/`Explore`/
  `RunAway`/`Escape`（不挖时）只要 `Locomotion`。
- `BodyCapabilities` 从三具身体的真实读数生成（`getBbWidth()`、`getStepHeight()`、`maxUpStep`、能否游泳按
  `LivingEntity` 类型），行走器已经按 `stepUp/jumpUp` 分支，只是今天永远拿到 `PLAYER`。

### 3.2 三个实现

| 实现 | 归属 | 身体 | 执行通道 | tick 泵 |
|---|---|---|---|---|
| `ClientPlayerBody` | 模组本体 | `LocalPlayer` | 移动：`AvatarInput extends KeyboardInput`（已有，E1 的成果）；挖掘/用物：`ClientIntents` + `MinecraftMixin`（2026-09-14 落地，Bot 层不再写任何 `KeyMapping`） | 客户端 tick（`BotApiImpl.clientTick`，不变） |
| `ServerPlayerBody` | 模组本体（第三方扩展面） | `JoinedBody extends ServerPlayer`（已有，走 `placeNewPlayer`） | 直接写 `xxa/zza/jumping/shiftKeyDown` + `setSprinting`，**`tick(){ super.tick(); doTick(); }` 自泵**（Carpet 模式） | `SERVER_POST` 里的 `ServerBodies.tickAll()`（今天的 `ServerAvatarManager` 泛化） |
| `LivingBody` | testmod | 任意 `LivingEntity`；第一只 NPC 是 testmod 注册的自定义猪灵（`Piglin` 子类） | 写 `xxa/zza` + `setJumping`；若是 `Mob`，被驱动的 tick **跳过它自己的 goal/navigation**（mixin 在 common，两个 loader 同一份） | 同上 |

`ServerPlayerBody` 的自泵取代今天的 `ServerPlayerAvatar.step()` + `mirrorPlayerTick()`（1313 行手写物理），
parity 表里 A1、A2、T5（硬编码 0.42 跳）、T8（`updatePlayerPose` 不跑）、T17/T18（水下跳、跳跃冷却）这一族
应当随之消失，用 `wd.bodyParityCensus` 量。**`gameMode.*` 的动作面保留**（Carpet 也是 `handleBlockBreakAction`/
`useItemOn`/`useItem`），因为那一层就是 51 个 handler 下面的真实现。

`LivingBody` 里「腿归谁」用一个显式的模式（Taterzens 的 `movement mode` 那一手）：`DRIVEN` 时 `Mob.serverAiStep`
的导航与移动控制不跑、`LookControl` 不跑；`FREE` 时全部还给原版。模式切换在 `attach`/`release` 上，
不允许「一半归我一半归它」——Automatone 靠 cancel `tickNewAi` 硬关，就是同一件事。

### 3.3 跨 loader

**身体之上的一切都在 `common`。** 三个实现都是原版代码：`placeNewPlayer` + `EmbeddedChannel` 是原版，
`LivingEntity` 的输入字段是原版，区块卸载用 Architectury 的 `LifecycleEvent.SERVER_LEVEL_UNLOAD`。
今天 `fabric/sim/FabricAvatarBodies`（重做 `FakePlayerFactory`）和 `neoforge/sim/*`（窄化壳）都随
`FakePlayer` 退役而删除。这与 Baritone（三 loader 一份源，loader 目录只有入口）和 PlayerEngine
（Architectury，loader 目录 3 个 java 文件）一致；先例里唯一需要 `@ExpectPlatform` 的是「造 `FakePlayer`
那一刻」（Moonlight、CC-Tweaked），而我们不再造 `FakePlayer`。

### 3.4 身体的名字与 RPC

- `BodyRegistry`（模组本体）：`self` 永远是客户端身体（如果有）；testmod 注册 `player:<name>`、`npc:<name>`。
- 现有 `mc.bot.*` 动词加一个可选 `body?: string`（默认 `self`），`DriverApi` 的 `body()` 包装按它取身体、
  取身体的 `ready()` 做前置检查、再把进程交给那具身体的调度器。`mc.bot.status` 加 `bodies: [{id, kind, entityId, pos, busy}]`。
  一个可选参数而不是新动词，是因为每个动词的 schema 都进每个 LLM 客户端的提示词。
- 服务端身体的调度：今天 `ServerWorldDriver` 一具身体只持一个进程，没有链层。第一版**保持这样**：
  `ServerPlayerBody`/`LivingBody` 只有 `UserTaskChain` 一条链（进程 + 取消 + 接替）；`auto/` 反射层
  （自动吃、自动游、窒息脱困…）是客户端人机共存的产物，先不搬。`Chain.priority/tick` 的参数从 `Minecraft`
  改成 `Body` 是为了让链层**能**在服务端跑，不是第一版就跑。

### 3.5 testmod 里的用法

- `FixtureRunner.Helm` 加第三种实现：`body: self | server | npc:<entity type or name>`；场景文件的 `body`
  字段接受同样的值。「同一场景、三具身体」的运行器就是 `wd.bodyParityCensus` 的自然扩展。
- `SceneBody` 多一个 `npc(ctx, EntityType, foot)`：生成实体、注册 `LivingBody`、`DRIVEN`，清理时 `FREE` + 移除。
- 场景族 `wd.npc*`：先复用 `lab.*`/`wd.client*` 的地形（楼梯、跑酷、渡水、上岸、梯子），三具身体各跑一遍，
  差异按 parity 文档的四类归档。

## 4. 分阶段落地（每一步都要过闸，零行为变化的步单独提交）

| 阶段 | 内容 | 判据 |
|---|---|---|
| P0 类型 | `Avatar` → `Body`：`LivingEntity entity()` + `asPlayer()`；`Hands`/`Containers` 拆出；`LookController.apply(LivingEntity)`；`BotInput` 变成 `ClientPlayerBody` 的实例方法；`Chain`/`ProcessScheduler` 收 `Body`，反射层内部向下转型到 `ClientPlayerBody`；`InteractionCommands.attackEntity` 改走 `Hands.attackEntity` | 六个闸颜色不变；预算闸；`wd.clientWorldViewParity`、`wd.bodyParityCensus` 读数不变 |
| P1 服务端玩家 | `ServerPlayerBody` 自泵 tick，删 `step()`/`mirrorPlayerTick()`；`bot/sim/` 与 `/worlddriver server` 留在模组本体作为第三方扩展面；删两个 loader 的 sim 目录与 `FakePlayerFactory` 路线；`SilentConnection` 补齐 §2 第 5 条的 NPE 面 | `wd.bodyParityCensus` 的 4.2 A1/A2 与 4.1 T5/T8/T17/T18 转绿；专用服闸绿；真梯自测不退 |
| P2 NPC | `LivingBody` + `DrivenMobHook`（common mixin：被驱动的 `Mob` 跳过 `serverAiStep` 的导航/移动/看向）；能力门（`no_hands` 拒单）；`SceneBody.npc`；`wd.npc*` 五个地形场景；可选 `NavigationMover` 对照 | 五个地形 NPC 身体通过，或差异归入四类之一并登记 |
| P3 寻址 | `BodyRegistry`、`mc.bot.*` 的 `body` 参数、`status.bodies`；`FixtureRunner` 的 `body: npc:…`；RPC 参考与 `docs/dev/bot-layering.md` 更新 | 三 transport 字节一致测试覆盖 `body` 参数；人工验证手册补一节 |

## 5. 明确不做、要避开的

- **不抄 Baritone / Automatone / PlayerEngine 的源码**（LGPL-3.0；PlayerEngine 的 Modrinth 标 MIT 与仓库
  LICENSE 冲突，以 LGPL 为准）。可以借形状；要借代码只从 mc_aiplayer（MIT）和 altoclef（MIT）借。
- **不走 Automatone 的「假人不加载区块」**：它的 mixin 把假人强制 `doesNotGenerateChunks`，与真梯长途行军直接冲突。
  `ServerPlayerBody` 靠 `placeNewPlayer` 拿票据；`LivingBody` 没有票据，远离玩家会停 tick——场景用
  `chunkRadius`/forceload 兜住，RPC 驱动的 NPC 在 `ready()` 里检查区块并以 `chunk_unloaded` 拒单。
- **不给 `LivingEntity` 分叉一份原版 `PathNavigation`**（Citizens `BasicMobAI` 的做法）。我们已有自己的
  寻路器且它不依赖实体类型，分叉三个原版类只会多一套要维护的物理。`NavigationMover` 只给真 `Mob` 用，作对照。
- **不做上帝参数对象**（Citizens 的 `NavigatorParameters` 六十个 setter 加一个 `useNewPathfinder` 布尔）。
  路线条件已经有 `route` 一个对象，身体差异走 `BodyCapabilities`，两者不合并。
- **不吞异常**（Carpet 的 `catch (NullPointerException ignored)`）：`SilentConnection` 把 NPE 面补齐，
  炸了就是场景红。

## 6. 已拍板与未决

1. **已拍板（2026-09-14）**：服务端身体是第三方扩展面，`bot/sim/` 与 `/worlddriver server` 留在发布 jar；
   `ServerPlayerBody` 因此是模组本体的公开类型，改名与拆分要当作 API 变更登 CHANGELOG。
2. **已拍板（2026-09-14）**：NPC 的第一具身体是 testmod 里一个自定义的猪灵实体（`Piglin` 子类，自己的
   `EntityType`），不披玩家皮；皮是展示问题，单独立项。
3. 反射层要不要上服务端身体（自动吃、自动游）。第一版不上；真梯在专用服上的死因族如果指向这里再议。

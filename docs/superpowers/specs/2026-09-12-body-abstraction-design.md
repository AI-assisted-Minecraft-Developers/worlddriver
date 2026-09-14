# 身体抽象层的设计：一套 Bot 层，三种身体（真玩家、服务端玩家、NPC）

> 状态：P0、P1a、P1b、P2 已落；P3 落了第一版（`goto`、`cancel`、`status` 三个动词认 `body`），其余未动。读者：开发者。
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
   **2026-09-14 补核**：`doTick()` 以 `invokespecial Player.tick` 进链，子类对 `tick()` 的覆写挡不住它；
   `placeNewPlayer` 不会把传入的 `Connection` 加进 `ServerConnectionListener` 的列表，所以监听器的 `tick()` 与
   `doTick()` 对这具身体确实从不运行，与 `SilentConnection.tick()` 覆写与否无关。泵放在哪见 §3.2 的修订。
4. `Mob.serverAiStep()` 依次 `navigation.tick → moveControl.tick → lookControl.tick → jumpControl.tick`，
   `MoveControl` 的 MOVE_TO 分支只做 `setYRot` + `setSpeed`，而 `Mob.setSpeed(f)` 覆写成 `super.setSpeed(f); setZza(f)`。
   **`Player` 没有覆写 `setSpeed`**，所以往玩家身上塞 `MoveControl` 必须自己补 `zza`。
5. 无连接的 `ServerPlayer` 在这些方法里解引用 `connection` 会 NPE：`indicateDamage`（挨一次伤害就炸）、
   `onEnterCombat/onLeaveCombat`、`onEffectAdded/Updated/Removed`（加药水就炸）、`openMenu`、`closeContainer`、
   `teleportTo` 各重载、`displayClientMessage`；`doTick()` 里三处 `connection.send`。`JoinedBody` 的
   `SilentConnection` 已经绕过这一族，`FakePlayer` 路线没有——这也是它退役的理由之一。
   **2026-09-14 补核**：`JoinedBody` 的 `connection` 非空，监听器实际走到的 `Connection` 方法（`send`、
   `disconnect`、`setReadOnly`、`flushChannel`、`isConnected`、两个协议切换）全部被覆写，余下的
   `getRemoteAddress`/`isMemoryConnection`/`getLoggableAddress` 落在 `EmbeddedChannel` 上不会空指针。
   **没有找到缺的覆写**；NeoForge 自己的网络类未逐个扫。
6. `ServerPlayer.checkFallDamage` 对**任何** `ServerPlayer` 都是空覆写（摔伤由客户端上报），换身体不解决
   （parity X2-4）；`LivingBody` 反而没有这个问题。**2026-09-14 补核**：真玩家的摔落走
   `handleMovePlayer` 调的 `doCheckFallDamage(dx, dy, dz, onGround)`，这个方法是 `public`，全 jar 只有
   `ServerPlayer` 与 `ServerGamePacketListenerImpl` 引用它——泵在移动之后自己调它，服务端身体就补得上。

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
| `ServerPlayerBody` | 模组本体（第三方扩展面） | `JoinedBody extends ServerPlayer`（已有，走 `placeNewPlayer`） | 直接写 `xxa/zza/jumping/shiftKeyDown` + `setSprinting`，每次 `step()` 调一次原版 `ServerPlayer.tick()` + `doTick()`（Carpet 的那一对调用，泵是我们自己的，见下文「2026-09-14 修订」） | `SERVER_POST` 里的 `ServerBodies.tickAll()`（今天的 `ServerAvatarManager` 泛化）；场景与快进照旧同步调 `step()` |
| `LivingBody` | testmod | 任意 `LivingEntity`；第一只 NPC 是 testmod 注册的自定义猪灵（`Piglin` 子类） | 写 `xxa/zza` + `setJumping`；若是 `Mob`，被驱动的 tick **跳过它自己的 goal/navigation**（mixin 在 common，两个 loader 同一份） | 同上 |

`ServerPlayerBody` 的自泵取代今天的 `ServerPlayerAvatar.step()` + `mirrorPlayerTick()`（1313 行手写物理），
parity 表里 A1、A2、T5（硬编码 0.42 跳）、T8（`updatePlayerPose` 不跑）、T17/T18（水下跳、跳跃冷却）这一族
应当随之消失，用 `wd.bodyParityCensus` 量。**`gameMode.*` 的动作面保留**（Carpet 也是 `handleBlockBreakAction`/
`useItemOn`/`useItem`），因为那一层就是 51 个 handler 下面的真实现。

**2026-09-14 修订：泵不放进实体自己的 `tick()`。** Carpet 的 `tick(){ super.tick(); doTick(); }` 让身体一个服务器
tick 只走一步，而本仓库按「一 tick 多步」写成的用法遍布测试模组：139 处 `step()`、77 处 `tickAll()`，
多数在同一个服务器 tick 内同步推进，常见 200–2200 步，最大 9000 步（`wd.tallBankDigClimb`）。
（此前这里还写着「`Walker`/`JourneyRig` 的快进一 tick 推进数百到上千步」，2026-09-14 只读调查核过：
主代码与真梯里没有这种快进，`JourneyRig` 是注册后按真 tick 等；结论不变。）反编译核实（Fabric 原版合并 jar
与 NeoForge 21.1.230 补丁 jar）之后的形状：

- `JoinedBody.tick()` 对关卡实体循环**继续为空**；`step()` 调 `JoinedBody` 上的泵方法，依次：写输入 →
  若关卡实体循环本步之前没替它做过，补 `setOldPosAndRot()` 与 `tickCount++`（二者只在
  `ServerLevel.tickNonPassenger` 里）→ `super.tick()`（即 `ServerPlayer.tick()`：`gameMode.tick`、
  `broadcastChanges`、`invulnerableTime--`、`trackStartFallingPosition`）→ `doTick()`（以 `invokespecial Player.tick`
  进链，绕过子类覆写；内含唯一一次 `baseTick`、`aiStep` 的原版跳跃闸与 `noJumpDelay`、`travel`、
  `checkMovementStatistics`（在 `ServerPlayer` 覆写的 `travel` 里）、`foodData.tick`、`updatePlayerPose`）→
  `doCheckFallDamage(位移, onGround())`（`ServerPlayer.checkFallDamage` 是空覆写，原版只在 `handleMovePlayer` 里调这个公开方法）
  → `ChunkSource.move`。
- **输入要身体自己按客户端的规矩写**，因为原版只在 `LocalPlayer.aiStep` 里做：移动冲量乘 `SNEAKING_SPEED`
  （潜行或爬行）与 0.2（正在用物）；潜行入水 `goDownInWater`；冲刺的停止条件（无前向冲量、饱食不足、
  撞墙、在水面而不在水下）。`setSpeed` 对玩家是死的（`Player.getSpeed` 直读属性），冲刺只靠 `setSprinting`。
- **不再单独调** `baseTick`、`setSpeed`、`travel`、`checkMovementStatistics` 或手写跳闸——`doTick` 里都有，
  再调一次就是双倍。
- **代价写明**：食物、效果、火、空气、冷却、用物、`noJumpDelay` 按**步**推进，不按服务器 tick；
  NeoForge 的 `PlayerTickEvent` 每步触发一次（`EntityTickEvent` 仍每服务器 tick 一次）。

**P1b 的波及面（2026-09-14 只读调查，未跑）。** 下面每条都会左右泵怎么写，动手前先定：

- **两条必需场景断言的就是今天的非原版闸，必红**：`wd.flushJumpIgnoresOnGround` 把 `onGround` 置假后要求起跳，
  `wd.serverTowersWithoutOnGround` 每 tick 置假后要求爬四格。P1b 要删掉或改成断言原版行为，不能留着红。
- **冲刺停止规则不能照抄客户端**：`Entity.move` 从 `isHorizontalCollisionMinor` 取 `minorHorizontalCollision`，
  只有 `LocalPlayer` 覆写它，`ServerPlayer` 上恒为假；照抄「撞墙且非轻微」会碰任何台阶就停冲刺，丢掉冲刺跳的
  +0.2，`wd.parkourAscend`、`wd.parkourVoid*`、`wd.diagonalAscentSpeed`（余量约 18%）首当其冲。
- **潜行降速的时机要选**：客户端按上一 tick 的姿态（`isMovingSlowly`）晚一步生效，今天的服务端身体是立即；
  `WalkerTickDrive` 里的跑酷豁免是照「立即」写的。
- **回血会改场景前提**：EASY 下 `foodData.tick` 给受伤身体回血，`wd.serverLowHpEdgePin` 把血设为 2，约 170 步后
  超过 `lowHealthCareful = 6`，行走器的谨慎模式中途关掉。
- **真姿态**：CROUCHING（高 1.5）与 SWIMMING（高 0.6）落地后，`BuildProcess`/`BackfillProcess` 等 `isCrouching()`
  的分支、瞄准与够距的眼高都会变；`HoldStill` 不释放潜行，走完路的身体会一直蹲着。
- **由 `soleOnSolid` 决定、却要经 `onGround`/`noJumpDelay` 执行的起跳**：`TowerProcess`、水中爬出
  （`WalkerTickClimb`）、`wd.buoyantWall`、`wd.deepWaterClimboutDrift`、`wd.serverTowersOutOfADeepShaft` 要跑一趟才知道。
- **无声丢失**：`dbgLastJumpTick` 的唯一写入者是手写闸；删掉后行走器起跳读数与塔场景的 `control.jumpTick`
  变成「无」/−1，不会红。要保留就在 `JoinedBody.jumpFromGround` 上记。
- **访问放宽**：`LivingEntity.jumping`、`attackStrengthTicker`、`updatingUsingItem` 三条 AW/AT 变成无人用
  （两份文件要一起删，`check_remap_safety.py` 只比对两份是否一致）；泵放在 `JoinedBody` 上不需要新条目，
  `goDownInWater` 是 protected，只能从 `JoinedBody` 里调。
- **非 `JoinedBody` 的身体**：`JourneyRig` 与 `WorldDriverActuatorSplitScenes` 把 `ServerPlayerBody` 包在收养来的
  真玩家上，今天不 `step()`，但泵若强转 `JoinedBody` 要守卫。
- **`tickCount`/`setOldPosAndRot` 的守卫**要按身体当前所在的关卡判（传送门场景会在循环中换维度），
  并经得起 `SimProbes` 在步进循环里重入 `level.tick`。
- **真梯**：没有地方设难度，世界是 EASY；只有砾石那一级喂食，按冲刺消耗约 760 米后饱食度降到 6 以下、停冲刺。

**P1b 落地时的取舍（2026-09-14，跑闸之前写下）。**

- 泵是 `JoinedBody.pump`，`ServerPlayerBody.step()` 只交输入。对不是本服务器 join 的玩家（`JourneyRig`、
  `WorldDriverActuatorSplitScenes` 收养的真玩家）`step()` 直接抛异常；`JourneyRig` 的三处注册都在 helm 判断之后，碰不到。
- `tickCount`/`setOldPosAndRot` 的守卫是一个标志：`JoinedBody.tick()`（关卡实体循环的入口）置位，泵读完清零。
  不按游戏时间判，所以重入 `level.tick` 和循环中途换维度都不用特判。
- 潜行降速按**本 tick** 的潜行键：`LocalPlayer.isCrouching()` 返回的是 `aiStep` 开头刚算出的字段。上面「客户端晚一步」
  的说法是调查读错了，今天服务端身体的「立即」本来就对，`WalkerTickDrive` 的跑酷豁免照旧成立。
- 冲刺停止规则照搬客户端，另把 `LocalPlayer.isHorizontalCollisionMinor` 覆写到 `JoinedBody` 上，擦墙不算撞墙。
- 另移植了 `LocalPlayer.aiStep` 的四角推出方块（`moveTowardsClosestSpace`）。没移植：起跑规则（驱动直接置冲刺位，
  与客户端身体一致）、创造飞行、跳键开鞘翅、骑乘跳。
- `handleMovePlayer` 的尾巴除 `doCheckFallDamage` 与 `ChunkSource.move` 外，还补了 `setKnownMovement`、上行清落差、
  `tryResetCurrentImpulseContext`。包处理器里的 `checkMovementStatistics` **故意不补**：`ServerPlayer` 覆写的 `travel`
  自己就调它，泵经 `doTick` 跑到 `travel` 时已经算过一次；再调一次，统计和游泳、冲刺的饥饿消耗都会翻倍。
- 输入语义不动：跳在陆上是一步的边沿、在水里保持；潜行保持到被松开。客户端 `AvatarInput` 两者都是「本 tick 不下令就松开」，
  潜行这一处的不对称（走完路的服务端身体会一直蹲着）原本留给闸的读数决定要不要收：六个闸的失败集与 P0 基线逐条一致，
  没有一条读数指向它，不收。
- `openStationMenu` 删了。跑闸时每装一次菜单打一行 `[avatar] 菜单旁路`，两个 loader 的专用服闸与两个集成服闸
  一次都没打（专用服上 16 条要开菜单的场景全过）。还剩能触发它的只有原版故意拒开的情形（潜行且手里有方块、箱子被挡），
  在那里补一个菜单，等于给身体开了玩家开不了的菜单。`JoinedBody` 那个只调 `super` 的 `openMenu` 覆写一并删。
- 场景：`wd.flushJumpIgnoresOnGround` 改为 `wd.jumpWaitsForOnGround`，断言相反；删 `wd.serverTowersWithoutOnGround`；
  `wd.serverLowHpEdgePin` 把饱食度定在 17，关掉自然回血。
- 跑闸后又改了五处靠手写物理才成立的布景（跑闸之后写）：两条 `wd.pillarLedger*` 显式打开
  `walkerFootholdBeforeBankDig`（钉住的基线关着它，挖岸排在垫柱前；泵出来的身体贴岸浮得够高，挖岸变得可行，
  接管还没开就挖出去了）；`wd.physicsParity` 改判「站上了台阶」；
  `wd.waterStepDownFloat` 去掉睡莲（活了的姿态会让身体从墙下钻过去）；
  `wd.journeyJudgesTheLastStepAfterTheDropLands` 把摆放高度降到头顶不进方块（否则移植来的推出方块把它推上唇）；
  普查的起跳消耗探针落稳两步（`setPos` 之后第一次 `move()` 碰不到地面，原版跳闸读的正是它）。

`LivingBody` 里「腿归谁」用一个显式的模式（Taterzens 的 `movement mode` 那一手）：`DRIVEN` 时 `Mob.serverAiStep`
的导航与移动控制不跑、`LookControl` 不跑；`FREE` 时全部还给原版。模式切换在 `attach`/`release` 上，
不允许「一半归我一半归它」——Automatone 靠 cancel `tickNewAi` 硬关，就是同一件事。

**2026-09-15 修订（P2 动手时核的）：第一只 NPC 不用 mixin。** `Mob.serverAiStep` 是 `final`，但里面跟驾驶打架的
东西子类都换得掉：脑子在可覆写的 `customServerAiStep` 里；`MoveControl` 没有目标时把 `zza` 清零、`JumpControl` 把
`jumping` 写回 false、`LookControl` 转头，三者都是 `protected` 字段，`DrivenPiglin` 在构造里换成驾驶时不动的版本。
mixin 要进发布 jar，留给第一只被驾驶的原版生物。其余形状：
- tick 照 `JoinedBody`：驾驶时关卡实体循环只记一笔，`pump()` 在驱动步进时跑 `super.tick()`，本步之前循环没来过就自己补
  `setOldPosAndRot()` 与 `tickCount++`。
- 速度：生物的 `getSpeed()` 读的是 `MoveControl` 每步写的字段，所以 `LivingBody.step()` 先按移动速度属性 `setSpeed`
  （它顺带写 `zza`），再把冲量乘这个速度写进 `xxa/zza`，等于原版生物以速度倍率 1 走路时留下的值。
- 规划：`LevelWorldView.forBody(level, 生物)`；不是玩家的身体，破坏代价为无穷、可放方块为 0。
- `SceneBody.npc(ctx, foot)` 只出 `worlddriver:driven_piglin`，免伤，场景结束丢弃。`wd.npc*` 在专用服上让服务端玩家身体
  随后走同一条路：两具都没到是布景的错，只有 NPC 没到才是身体之间的差异。

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

**2026-09-15 修订（P3 动手前定的第一版范围）。** 第一版只打通三个动词的身体寻址：`mc.bot.goto`、`mc.bot.cancel`、
`mc.bot.status`。其余 23 个 `mc.bot.*` 先不声明 `body`，传了会被 schema 校验当作未知参数拒掉。
这比声明了参数、再在路由里回一句「这个动词还不支持」诚实，也不必给每个 LLM 客户端多塞 23 份用不上的参数说明。
- **`BodyRegistry`**（`bot/body/`，模组本体）：按 id 存 `BodyHost`。`self` 不进表，表示客户端 `BotApi`，行为与今天相同。
  服务端玩家身体由 `/worlddriver server spawn <name>` 注册成 `player:<name>`；testmod 注册 `npc:<name>`；服务器停止时清空。
- **`BodyHost`**：`id()`、`kind()`、`entity()`、`start(BotProcess)`、`cancel()`、`busy()`、`botState()`、`refusal()`。
  服务端玩家身体的实现包 `ServerWorldDriver`；NPC 的实现在 testmod，包 `LivingBody`。两者都由服务器 tick 推进，
  `ServerAvatarManager` 的元素类型从 `ServerWorldDriver` 放宽成一个只有 `tick()`/`finished()` 的接口。
- **`goto`**：`GotoGoalResolver` 从收 `LocalPlayer` 改为收 `Entity`（关卡取它的 `level()`），「最近的某类实体」由调用方
  传进来：客户端扫渲染列表，服务端扫关卡里已加载的实体。以下目标只在 `self` 上接受，
  在其他身体上拒单：航点（客户端内存里的状态）、`plan`/`planId`、`route.mode fly`，以及非玩家身体上的 `route.requireTool`。
  进程仍是 `IntentProcess`。
- **`status`**：总带 `bodies: [{id, kind, entityId, pos, busy}]`；带 `body` 时返回那具身体的槽位，`awaitMs` 也按 `body` 轮询。
  专用服上没有客户端 bot，今天 `mc.bot.status` 直接报错，改后回 `{bodies:[…]}`。
- **拒单形状**沿用 `BodyReady`：未知 id 回 `{ok:false, reason:"unknown_body"}`；实体已移除或所在区块未加载回 `chunk_unloaded`。
- **验证**：validation 脚本在三个 transport 上比对 `unknown_body` 拒单与 `status.bodies` 的字节是否一致，任何拓扑都能跑，不造身体。
  专用服场景注册一具服务端玩家身体和一只 NPC，经 `DriverApi.route` 让两者各走一段，判到达。
- **第一版不做**：其余 23 个动词；§3.5 的 `FixtureRunner` `body: npc:…`；RPC 驱动的 NPC 的区块票据（§5）。

### 3.5 testmod 里的用法

- `FixtureRunner.Helm` 加第三种实现：`body: self | server | npc:<entity type or name>`；场景文件的 `body`
  字段接受同样的值。「同一场景、三具身体」的运行器就是 `wd.bodyParityCensus` 的自然扩展。
- `SceneBody` 多一个 `npc(ctx, foot)`（2026-09-15 落地时去掉了 `EntityType` 参数，第一版只有一种 NPC）：
  生成 `worlddriver:driven_piglin`、免伤、`setDriven(true)`，场景结束丢弃。
- 场景族 `wd.npc*`：楼梯、跳沟、渡水、上岸、梯子五块地形在场景里现搭。专用服上 NPC 与服务端玩家身体各走一遍，
  其余拓扑只走 NPC（客户端身体不跑这一族）；差异按 parity 文档的四类归档，见那份文档 §12。
  第六个 `wd.npcRefusesWorkThatNeedsHands` 让 NPC 接六种要手的单，要求第一 tick 就以 `no_hands` 收单。

## 4. 分阶段落地（每一步都要过闸，零行为变化的步单独提交）

| 阶段 | 内容 | 判据 |
|---|---|---|
| P0 类型（2026-09-14 已落） | `Avatar` → `Body`：`LivingEntity entity()` + `asPlayer()`；`Hands`/`Containers` 拆出；`LookController.apply(LivingEntity)`；`BotInput` 变成 `ClientPlayerBody` 的实例方法；`Chain`/`ProcessScheduler` 收 `Body`，反射层内部向下转型到 `ClientPlayerBody`；`InteractionCommands.attackEntity` 改走 `Hands.attackEntity` | 六个闸颜色不变；预算闸；`wd.clientWorldViewParity`、`wd.bodyParityCensus` 读数不变 |
| P1a 只剩真身体（2026-09-14 已落） | `ServerAvatarBodies` 只出 `JoinedBody`，`realPlayerBodies` 开关退役；删 Fabric 的 `FabricAvatarBodies`/`AvatarFakePlayer` 与 NeoForge 的 `FakePlayerFactory` 工厂；`/worlddriver server` 从 NeoForge 搬进 common，两个 loader 都有；两个 loader 的 `sim/` 目录删除 | 六个闸颜色不变（六个闸本来就开着那个开关）；`wd.bodyParityCensus` 的 factory 列如实记 unavailable |
| P1b 原版泵（2026-09-15 已落） | `step()` 改走 `JoinedBody` 的泵（§3.2 修订）；删 `mirrorPlayerTick()`、手写跳闸、`setSpeed`/`travel` 直调；断言非原版行为的场景跟着改；判 `openStationMenu`（替假人补菜单的旁路）在原版 `openMenu` 下还会不会触发（没触发过，已删） | `wd.bodyParityCensus` 的 4.2 A1/A2 与 4.1 T5/T8/T17/T18 读成原版的值；专用服闸绿；真梯自测不退。**核过**：普查读成原版（落差峰值 10.807、`invulnerableTime` 19..15、经验 0→9、潜行 CROUCHING/1.50）；六个闸的失败集与 P0 基线逐条一致（NeoForge 专用服首跑红在 J75 预言的珍珠上，重跑绿）；真梯首跑在 PORTAL_KIT 的进食里超时（等待期间没人步进身体，`JourneyRig.await` 已补步），重跑爬到 OBSIDIAN，地板 PORTAL_KIT 未退 |
| P1c 连接 | `SilentConnection` 对照 §2 第 5 条 | 2026-09-14 已逐方法核过、无缺口（见 §2 第 5 条补核）；NeoForge 网络类若日后炸出空指针再补 |
| P2 NPC（2026-09-15 已落） | `LivingBody`（testmod）+ `DrivenPiglin`：驾驶时换掉移动、跳跃、看向三个控制并停掉脑子，不用 mixin（§3.2 修订）；`LevelWorldView.forBody`；能力门（`no_hands` 拒单，顺带修了五个进程在非玩家身体上先判 `asPlayer()` 的顺序）；`SceneBody.npc`；`wd.npc*` 五个地形场景加一个拒单场景；可选的 `NavigationMover` 对照没做 | 五个地形 NPC 身体通过，或差异归入四类之一并登记。**核过**：五个地形场景在六个闸上全部通过，两个 loader、三种拓扑的 NPC 读数逐位相同；拒单场景在两个集成服闸和两个带客户端闸上通过（两个专用服闸跑在它和那五个进程的修复加进来之前，带客户端闸的服务端就是专用服）；与服务端玩家身体相比，上岸多用 12 tick（未归因，记作只能近似），其余四块差 0–3 tick（`docs/fake-player-parity.md` §12）。六个闸的失败集与 P1b 基线一致，NeoForge 集成服的 ENV_FAIL 名单照旧每趟不同。Fabric 带客户端闸三趟：第一趟撞了 /tmp 配额，没出判词；第二趟红在 `47_plan` 读了真玩家的背包，与本阶段无关，脚本已改为从空背包规划（测试台附近的钻石见 J132）；第三趟绿 |
| P3 寻址（2026-09-15 第一版已落） | `BodyRegistry`、`mc.bot.*` 的 `body` 参数、`status.bodies`；`FixtureRunner` 的 `body: npc:…`；RPC 参考与 `docs/dev/bot-layering.md` 更新。第一版（§3.4 修订）：`bot/body/` 的 `BodyRegistry`/`BodyHost`；`api/BodyRoutes` 让 `goto`/`cancel`/`status` 认 `body`，`status` 带 `bodies`；`/worlddriver server spawn <name>` 注册 `player:<name>`，testmod 的 `NpcBodyHost` 注册 `npc:<name>`；`ServerAvatarManager` 改收 `BodyDriver`；RPC 参考与 `bot-layering.md` §6 已更新。其余 23 个动词、`FixtureRunner` 的 `body: npc:…` 没做 | 三 transport 字节一致测试覆盖 `body` 参数；人工验证手册补一节。**核过**：`66_body_routes.js` 的三条检查在六个闸上都过；`wd.bodyRoutesWalkAPlayerAndAnNpcByName` 在两个专用服闸和两个带客户端闸上通过，两个 loader 读数相同，集成服上按身体选型规则跳过。专用服 Fabric 首跑红在 `agentRpcSmoke` 的检查总数没算上脚本 66，改后在其余五个闸上核过（专用服 145、集成服 257），Fabric 专用服没有重跑；NeoForge 带客户端首跑红在 `43_recipe` 的钻石检查读了真玩家的背包（与 J132 同源，改为空背包后重跑绿）。除这两处，六个闸的失败集与 P2 基线一致，NeoForge 集成服的 ENV_FAIL 名单照旧每趟不同。人工验证手册一节未补 |

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
4. **P1b 的实施假设（2026-09-14，未经拍板）**：服务端身体的饥饿、状态效果、火、空气、冷却按原版跑，
   不豁免——那正是换原版泵要换来的东西；`JoinedBody.isInvulnerableTo → true` 仍在，所以饿不死也摔不死，
   只是会饿、会停冲刺。进食走现有的 `commandUseItem`/`JourneyFeed`。若场景或真梯因此变红，再议是否给
   这具身体一个豁免开关，而不是在泵里悄悄跳过 `foodData.tick`。

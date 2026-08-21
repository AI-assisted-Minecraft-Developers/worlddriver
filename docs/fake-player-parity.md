# 假人保真度边界表（fake-player parity）

**这份文档回答一个问题**：worlddriver 驱动的那具身体，和一个真玩家差在哪里，哪些差距能补、哪些补不了、哪些补了也没意义。

**它不回答**「怎么补」。补法只在能补的条目里给一句路径，实现属于第二阶段。

## 读法与证据规则

- 每一条断言都带 `file:line`。worlddriver 的行号锚在 **`fba07d8b`**（`bot/sim/` 自 `7855a349` 未动）；stagewright 的锚在 **`7646d84`**。
- vanilla 行号来自两份反编译产物，二者**必须分开引用**，因为它们在关键处不一样：
  - **vanilla**：`minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.652182843-v2.jar`
  - **neoforge 21.1.230 merged**：`neoforge-21.1.230-minecraft-merged-mojang`

  两份都用 vineflower 1.10.1 反编译（`-dgs=1 -hdc=0`）。下文写「vanilla `X.java:n`」和「neoforge `X.java:n`」时，指的就是这两份。
- 写「已核对否定」的条目，是我怀疑过、查了、发现**没有**差异的。留着它们，因为一个诚实的「行」和一个诚实的「不行」一样贵。

## 边界表的四类

| 类别 | 含义 |
|---|---|
| **真等价** | 能做到和真玩家逐位一致。必须同时给出「怎么补」和「怎么验证它真的等价」。想不出验证方法的，不许放进这一类。 |
| **只能近似** | 结构上做不到一致。必须说清差在哪一格，以及**哪些测试会因此撒谎**。 |
| **不可能，且不需要** | 这具身体做不到，但**有另一具身体可以接手**——真玩家，或（在专用服上）`JoinedBody`。正确答案是**换一具身体**，不是补代码。 |
| **不可能，但仍需要** | **连 `JoinedBody` 也做不到**，而专用服本身就是被测对象。这条差异会直接让测试撒谎，只能补能力、或让测试诚实地拒绝在这里断言。**这一类才是真正的成本，也是这份文档最该说清的部分。** |

第三、四类的分界线，原本是「有没有真玩家可以接手」。**2026-08-20 的身体选型指令把这条线挪了**，见 §0——现在的判据是「有没有**任何**一具能接手的身体」，而专用服上多了 `JoinedBody` 这个选项。判据收紧的直接后果是第四类缩小，这是好事：第四类越小，第二阶段的工作量越小。

---

## 0. 身体选型指令（2026-08-20，是前提不是选项）

| 拓扑 | 身体 |
|---|---|
| 专用测试服 | **`JoinedBody`**，且只有这里用 |
| 集成服 + 客户端（含 joining） | **`LocalPlayer`** 驱动 |
| NeoForge `FakePlayer` | **废弃** |

这条指令重排了整张表。因为它，这份文档里每一条差异都必须回答**一个新问题**：

> 它是 **`FakePlayer` 特有的**（废弃即消失），还是 **「这具身体没走真 handler」造成的**（换成 `JoinedBody` 也不会自动好）？

**这个区分决定第二阶段还剩多少工作量。** 完整的逐条归属在 §6.5。结论先放在这里：

- **废弃即消失：3 条**（N1、N2，加 N15 的一半）
- **`JoinedBody` 一行可达：4 条**（N4、N13，加 X2-3、X2-5）——它们全都卡在**我们自己写的**那一行 `tick()` 空覆盖上
- **换身体也不会好：11 条**——它们卡在驱动器自己的代码里，或卡在通道(二)上

---

## 1. 三具身体和一个开关

这套代码里有 **三** 具身体，不是两具。

| | 类 | 怎么造出来 | 进过 `PlayerList` 吗 |
|---|---|---|---|
| **A** | `AvatarFakePlayer extends ServerPlayer` | `fabric/src/main/java/net/magicterra/worlddriver/fabric/sim/FabricAvatarBodies.java:41-42`（沿用 `FakePlayerFactory` 的固定 `[Minecraft]` profile UUID） | **没有**，只被 `new` 出来 |
| **B** | `net.neoforged.neoforge.common.util.FakePlayer` | `FakePlayerFactory.getMinecraft(level)` / `.get(level, profile)`，见 `neoforge/src/main/java/net/magicterra/worlddriver/neoforge/WorldDriverNeoForge.java:52,55`，随后把 `AvatarNetHandler` 装到它的 listener 上 | **没有** |
| **C** | `JoinedPlayerBodies.JoinedBody extends ServerPlayer` | `common/src/main/java/net/magicterra/worlddriver/bot/sim/JoinedPlayerBodies.java:141` 的 `PlayerList.placeNewPlayer(...)` | **进了**：玩家表、`ChunkMap`、登录事件、vanilla 真 `ServerGamePacketListenerImpl` |

**A/B 和 C 不是同一个东西，C 也不是 NeoForge 的 `FakePlayer`。** 这一点在第 4 节会变成一条关键结论。

三具身体上面套着同一个壳：`common/src/main/java/net/magicterra/worlddriver/bot/sim/ServerPlayerAvatar.java`（1126 行）。它实现 `bot/movement/Avatar.java` 的动作接口，自己手写物理（`step()`，`:963-1088`），并把每个动词翻译成对某个 vanilla 方法的直接调用。

选哪具身体由 `common/src/main/java/net/magicterra/worlddriver/bot/sim/ServerAvatarBodies.java:70-80` 的 `require()` 决定：开关一开，先返回 joined body（`joinedOrNull()`，`:64-68`），否则返回 loader 注册的工厂。

**`-Dworlddriver.realPlayerBodies=true` 到底切换了什么**（`JoinedPlayerBodies.java:81`／`:83`）：它把 A/B 换成 C。换来的**唯一**东西是「这具身体在 `level.players()` 里」，以及随之而来的 `ChunkMap` 注册和一个真的 packet listener。它**不改变**这具身体的免伤、不改变 `tick()` 是空的、不改变 `fallDistance`。

**这个开关装在哪里** —— 这是本节最重要的一行事实：

```
fabric/build.gradle:262   runJourneyServer
fabric/build.gradle:322   runJourneyIntegratedServer
fabric/build.gradle:339   runJourneyDedicatedServerWithClient
fabric/build.gradle:481   runRehearsalServer
```

全仓库只有这四处（`grep -rn realPlayerBodies --include=*.gradle`）。也就是说：

- **六条闸（`stagewrightDedicatedServer*` / `stagewrightIntegratedServer*` / `stagewrightDedicatedServerWithClient*`）一处都不设。** 241 条 `wd.*` 闸场景全部跑在一具**不在玩家表里**的身体上。
- **NeoForge 一处都不设。** NeoForge 上跑的永远是身体 B，也就是真正的 `FakePlayer`。第 4 节会说明这为什么让 NeoForge 上**任何一次运行都拿不到任何进度**。

---

## 2. 一条根因：三条 tick 通道，这具身体一条都不在

一个真玩家每 tick 被三条互不相同的通道驱动：

| 通道 | 入口 | 里面有什么（举其要） |
|---|---|---|
| **(一) 实体 tick** | `ServerLevel` → `ServerPlayer.tick()`，vanilla `ServerPlayer.java:469-503` | `gameMode.tick()`、`invulnerableTime--`、`containerMenu.broadcastChanges()`、`CriteriaTriggers.TICK.trigger`、`trackStartFallingPosition()`、`updatePlayerAttributes()`、`advancements.flushDirty(this)` |
| **(二) 连接 tick** | `ServerConnectionListener.tick()`（vanilla `:168-195`）→ `Connection.tick()` → `ServerGamePacketListenerImpl.tick()`（`:267`）→ `ServerPlayer.doTick()`（`:527`）→ `Player.tick()`（`Player.java:285-368`）→ `LivingEntity.tick()`（`:2397`）→ `aiStep()` | `takeXpDelay--`、`foodData.tick()`、`attackStrengthTicker++`、`turtleHelmetTick()`、`cooldowns.tick()`、`updatePlayerPose()`、`EventHooks.firePlayerTickPre/Post`、`updatingUsingItem`、装备变更检测、战斗追踪 |
| **(三) 包处理** | `ServerGamePacketListenerImpl` 的 51 个 `handle*` | 移动、挖掘、放置、使用、攻击、交互、容器、聊天、槽位、能力…… |

**三具身体全部把通道(一)覆盖成空**：`AvatarFakePlayer.java:78`、neoforge `FakePlayer.java:111`、`JoinedPlayerBodies.java:217`。

**通道(二)对三具身体都不跑**：A/B 的 `AvatarNetHandler.tick()` 自己就是空的（`AvatarNetHandler.java:67`）；C 的 `SilentConnection`（`JoinedPlayerBodies.java:249-325`）是一个 `EmbeddedChannel`，从来没有进过 `ServerConnectionListener.connections`——那个集合只由服务器的 netty channel initializer 填充（vanilla `ServerConnectionListener.java:168-195` 只遍历它）。**而且 C 的 `SilentConnection.tick()` 也被覆盖成空（`JoinedPlayerBodies.java:290`）**，所以即使有人把它注册进去也不会跑。

**通道(三)被整条绕过**：`ServerPlayerAvatar` 的每个动词都直接调 `fp.gameMode.*` / `Player.*` / `Level.*`，落在包处理**下面**一层。

`ServerPlayerAvatar.mirrorPlayerTick()`（`:769`）是通道(二)尾巴的**人工部分重实现**。它的注释里列了镜像了什么、以及故意不镜像什么。**这份清单是手维护的**——这就是为什么下面绝大多数「只能近似」条目都是这一段的推论，而不是各自独立的缺陷。

> 这一节是整份文档的骨架。后面每读到一条差异，先问它属于哪条通道；十有八九答案是「通道(二)的某一行」。

---

## 3. 逐个 handler 审计：真玩家的动作入口 vs 假人实际调的东西

`ServerGamePacketListenerImpl` 是真玩家**唯一**的动作面，一共 51 个 `handle*`。下表逐个对照。**缺动词也是发现，不是空格。**

判词：`等价` / `近似` / `无动词` / `管道`（协议管线，与身体保真度无关）。

| # | handler（vanilla 行号） | 假人走的路 | 判词 |
|---|---|---|---|
| 1 | `handlePlayerInput:388` | — | 无动词（且 A/B 的 `startRiding → false`，`AvatarFakePlayer.java:86`） |
| 2 | `handleMoveVehicle:406` | — | 无动词，同上 |
| 3 | `handleAcceptTeleportPacket:503` | — | 管道，**但见附录的雷 1** |
| 4 | `handleRecipeBookSeenRecipePacket:531` | — | 无动词（配方书状态） |
| 5 | `handleRecipeBookChangeSettingsPacket:537` | — | 无动词 |
| 6 | `handleSeenAdvancements:543` | — | 管道（纯 UI） |
| 7 | `handleCustomCommandSuggestions:555` | — | 管道 |
| 8 | `handleSetCommandBlock:578` | — | 无动词（需 op+creative） |
| 9 | `handleSetCommandMinecart:634` | — | 无动词 |
| 10 | `handlePickItem:656` | `holdItem`/`selectTool` 直接改 `inv.items`/`inv.selected` | 近似：不是同一个动作（见 #27） |
| 11 | `handleRenameItem:671` | — | 无动词 → 铁砧改名无法测 |
| 12 | `handleSetBeaconPacket:684` | — | 无动词 → 信标无法测 |
| 13 | `handleSetStructureBlock:697` | — | 无动词 |
| 14 | `handleSetJigsawBlock:749` | — | 无动词 |
| 15 | `handleJigsawGenerate:769` | — | 无动词 |
| 16 | `handleSelectTrade:780` | — | 无动词 → **整个村民交易系统无法测** |
| 17 | `handleEditBook:795` | — | 无动词 |
| 18 | `handleEntityTagQuery:835` | — | 管道（op 调试） |
| 19 | `handleContainerSlotStateChanged:847` | — | 无动词（合成器） |
| 20 | `handleBlockEntityTagQuery:858` | — | 管道 |
| 21 | **`handleMovePlayer:868-988`** | `ServerPlayerAvatar.step()` 自己 `fp.travel(...)`（`:1079`） | **近似，最贵的一条**。丢掉：`doCheckFallDamage`、`checkMovementStatistics`、`setKnownMovement`、`tryResetCurrentImpulseContext`、`getChunkSource().move(player)`（只有开关开时由 `:1116-1125` 补）、`jumpFromGround()`（换成硬编码 0.42，`:1032`） |
| 22a | `handlePlayerAction:1048` **SWAP_ITEM_WITH_OFFHAND** | — | 无动词（vanilla 这里还会 `stopUsingItem()`） |
| 22b | 同上 **DROP_ITEM / DROP_ALL_ITEMS** | — | 无动词 → `Player.drop` 从不被调用，丢物、`Stats.DROP` 全测不到 |
| 22c | 同上 **RELEASE_USE_ITEM** | `commandUseItem` → `fp.releaseUsingItem()`（`:188`） | **等价**（`7855a349` 修好的那一条） |
| 22d | 同上 **START/ABORT/STOP_DESTROY_BLOCK** | `destroyAimed()` → `fp.level().destroyBlock(pos, true, fp)`（`:426-439`） | **近似，第二贵**。走的不是 `gameMode.handleBlockBreakAction`：没有分段挖掘、没有工具要求、没有精准采集/时运、不掉耐久、不触发 `CommonHooks.fireBlockBreak`、不走 `block.playerDestroy` 的统计 |
| 23 | `handleUseItemOn:1108` | `place`/`placeOn`/`useBlock` → `fp.gameMode.useItemOn(...)`（`:349-375`、`:535-545`） | 近似：跳过 `canInteractWithBlock(pos,1.0)`（vanilla `:1118`）、跳过命中向量合理性检查、跳过 `CriteriaTriggers.ANY_BLOCK_USE`（`:1129`）、跳过 `swing(hand,true)`（`:1139`）、跳过 `awaitingPositionFromClient` 闸（`:1126`） |
| 24 | `handleUseItem:1160` | `useItemInHand()` → `fp.gameMode.useItem(...)`（`:622-624`） | 近似：内核等价，丢掉 `absRotateTo` 与 `swing` |
| 25 | `handleTeleportToEntityPacket:1182` | — | 管道（旁观者） |
| 26 | `handlePaddleBoat:1196` | — | 无动词 |
| 27 | `handleSetCarriedItem:1230` | `setSelectedSlot`（`:287-289`）、`selectTool`（`:281-282`）、`holdItem`（`:626-641`）直接写 `inv.selected` | **近似（新发现）**：vanilla 在主手正在使用时会调 `stopUsingItem()`；这三处一处都不调 |
| 28 | `handleChat:1245` | — | 无动词：假人不能作为聊天来源 |
| 29 | `handleChatCommand:1270` | — | 近似：命令由服务端控制台代跑，`CommandSourceStack` 的权限级与来源实体都不同 |
| 30 | `handleSignedChatCommand:1290` | — | 无动词 |
| 31 | `handleChatAck:1426` | — | 管道 |
| 32 | `handleAnimate:1436` → `player.swing(hand)` | `bot/sim/` 内**没有任何** `.swing(` 调用 | **近似（新发现）**：挥手写在调用点（`bot/process/CombatProcess.java:263`），不在 avatar 缝上；放置/使用路径一次都不挥 |
| 33 | `handlePlayerCommand:1443` | 潜行：`fp.setShiftKeyDown(pendingSneak)`（`:1068`）；冲刺：只读 `fp.isSprinting()`（`:1035`、`:1067`）；滑翔：`startFallFlying()` → `tryToStartFallFlying()`（`:617-620`） | 近似：写的是效果不是动作；`OPEN_INVENTORY`、`START_RIDING_JUMP`、`STOP_SLEEPING` 无动词 |
| 34 | `handlePingRequest:1540` | — | 管道 |
| 35a | `handleInteract:1545` **ATTACK** | `attackEntityUnchecked` → `fp.attack(target)`（`:644-646`） | **近似**：跳过 `canInteractWithEntity(aabb,1.0)`（`:1557`）、跳过 `swing(hand,true)`（`:1569`）、跳过 `onAttack` 对 ItemEntity/经验球/自己的拒绝 |
| 35b | 同上 **INTERACT** → `Player::interactOn` | — | **无动词** → 剪羊毛、挤奶、上鞍、拴绳、驯服、交易开界面全测不到；`CriteriaTriggers.PLAYER_INTERACTED_WITH_ENTITY` 从不触发 |
| 35c | 同上 **INTERACT_AT** → `interactAt` | — | 无动词 → 盔甲架分部位交互测不到 |
| 36 | `handleClientCommand:1616` | — | **无动词**：`PERFORM_RESPAWN` 无处可去，三具身体的 `die` 都是 no-op |
| 37 | `handleContainerClose:1644` → `doCloseContainer()` | `closeContainer()` → `fp.closeContainer()`（`:615`） | **等价（已核对否定）**：`ServerPlayer.closeContainer():1147-1149` 内部就调 `doCloseContainer()`，差的只有一个被吞掉的出站包 |
| 38 | `handleContainerClick:1650` | `containerClick` → `fp.containerMenu.clicked(...)`（`:596-615`） | 近似：跳过 `stillValid`、跳过 `suppressRemoteUpdates`/`resume`；事后的 `broadcastChanges()` 由 `mirrorPlayerTick()` 每 tick 代劳（`:805-808`） |
| 39 | `handlePlaceRecipe:1689` | `placeRecipe`（`:596`） | 近似：同上 |
| 40 | `handleContainerButtonClick:1705` | — | **无动词** → 附魔台、切石机、织布机的按钮一个都按不了 |
| 41 | `handleSetCreativeModeSlot:1721` | avatar 直接写 `inv.items`（`:193-219`、`:256-286`、`:626-641`） | 近似，且方向不对：这是**创造模式**的能力，被用在生存流程里 |
| 42 | `handleSignUpdate:1754` | — | 无动词 |
| 43 | `handlePlayerAbilities:1773` | — | 无动词（飞行开关） |
| 44 | `handleClientInformation:1779` | A/B 的 `updateOptions` 是 no-op（`AvatarFakePlayer.java:80`） | 近似：`requestedViewDistance` 恒为默认，`ChunkMap` 对它的加载半径与真玩家不同 |
| 45 | `handleChangeDifficulty:1787` | — | 管道（op） |
| 46 | `handleLockDifficulty:1795` | — | 管道 |
| 47 | `handleChatSessionUpdate:1803` | — | 管道 |
| 48 | `handleConfigurationAcknowledged:1831` | — | 管道 |
| 49 | `handleChunkBatchReceived:1844` | 从不 ack | 近似：只对 joined body 有意义（区块下发节流） |
| 50 | `handleDebugSampleSubscription:1850` | — | 管道 |
| 51 | `handleCustomPayload:1874` | — | **无动词** → 模组自有网络通道对这具身体完全不通。**这是模组包测试的天花板** |

**统计（按上表逐行数出来的，不是按计划写的）**：上表 **56 行覆盖 51 个 handler**——`handlePlayerAction` 拆成 4 行、`handleInteract` 拆成 3 行，因为这两个 handler 内部是多个互不相干的动作族。

按行：**等价 2、近似 15、无动词 26、管道 13**（2+15+26+13=56）。

按 handler：51 个里 **22 个驱动器完全没有对应动词**；另有 2 个（`handlePlayerAction`、`handleInteract`）只覆盖了自己动作族的一部分。

**这张表最该被记住的一行**：真玩家的 51 个动作入口里，**22 个驱动器一个动词都没有**，另有 2 个只覆盖了一半；其中「交互实体」（`interactOn`/`interactAt`）、「丢物」、「容器按钮」、「自定义 payload」四族，是驱动器作为**模组包测试工具**时最贵的四个洞。

---

## 4. 边界表

### 4.1 真等价（16 条）

「真等价」= 补得到逐位一致，且我能写出一个**缺陷存在时会红**的验证。

| # | 条目 | 差在哪 / 怎么补 | 怎么验证（且不会 `0==0`） |
|---|---|---|---|
| T1 | `fallDistance` 恒为 0 | **机制（新发现）**：`ServerPlayer.checkFallDamage` 是**空覆盖**（vanilla `ServerPlayer.java:1012-1014`），所以 `Entity.move` 里的调用（`Entity.java:711`）对任何 `ServerPlayer` 都是 no-op；vanilla 只从 `handleMovePlayer` 走 `doCheckFallDamage`（`ServerPlayer.java:1023`）。补法：在 `step()` 的 `travel` 之后镜像一次 `doCheckFallDamage(dx,dy,dz,onGround)` | **不能用「掉下去会掉血」验证**——身体免伤（X2-3），这个断言在缺陷修好后依然红。改为：从 y+10 自由落体，落地**前一 tick** 断言 `fp.fallDistance > 5`，落地后断言归零；缺陷存在时前者恒为 0 → 红 |
| T2 | `stopUsingItem()` 不放箭 | 已修：`ServerPlayerAvatar.java:188` 改成 `releaseUsingItem()` | 现有 `wd.serverAvatarTickFidelity`（`common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/scene/WorldDriverAvatarScenes.java:181-196`）钉的是吃东西；**再加一条**：拉满弓 20 tick 后放手，断言场上出现一个 `Arrow` 实体。缺陷存在时场上 0 支箭 → 红 |
| T3 | 关容器 | **已核对否定**：`fp.closeContainer()` 内部就是 `doCloseContainer()` | 断言关闭后 `containerMenu == inventoryMenu`，且箱子的 `ChestBlockEntity` 开启计数归零（后者才是 `removed()` 真跑过的证据） |
| T4 | 换槽不停用（新发现 N8） | vanilla `handleSetCarriedItem:1230` 在主手使用中时调 `stopUsingItem()`；三处写 `inv.selected` 的地方都不调。补法：三处各加一行 | 拉满弓 → `setSelectedSlot(其它槽)` → 断言 `fp.isUsingItem() == false`。缺陷存在时为 `true` → 红 |
| T5 | 跳跃力硬编码 0.42（新发现 N7） | `ServerPlayerAvatar.java:1032` 丢掉了 `Attributes.JUMP_STRENGTH`、`getBlockJumpFactor()`（蜂蜜块）、跳跃提升（vanilla `LivingEntity.getJumpPower:2115-2117`、`getJumpBoostPower:2119-2121`），以及 `Player.jumpFromGround:1516-1523` 的 `Stats.JUMP` 和 `causeFoodExhaustion`，还有 `CommonHooks.onLivingJump`（`LivingEntity.java:2135`）。补法：改调 `fp.jumpFromGround()` | 三条只差一个自变量的臂：普通方块上跳 / 蜂蜜块上跳 / 带跳跃提升 II 跳。断言后两者的 apex 分别**低于**和**高于**第一条。缺陷存在时三条 apex 完全相同 → 红。**这条臂设计里绝不能只测普通方块，那正是 `0==0`** |
| T6 | `invulnerableTime` 永不递减（新发现 N4） | `LivingEntity.baseTick():468-470` 显式写着 `&& !(this instanceof ServerPlayer)`，只有 `ServerPlayer.tick()` 减它——而 tick 是空的。补法：镜像那一行 | 依赖先解开免伤（见 X2-3）。验证：连打两下，断言第二下在 i-frames 内**不掉血**、10 tick 后能掉。**这条与 T1/X2-3 互锁，不能单独验收** |
| T7 | `takeXpDelay` 永不递减（新发现 N5） | `Player.tick():292-293` 是唯一减它的地方；`ExperienceOrb.playerTouch:234` 只在 `takeXpDelay == 0` 时给经验，`:239` 又设回 2 → **这具身体一辈子只吸一颗经验球**。补法：镜像那一行 | 在脚下扔 3 颗经验球，等 10 tick，断言 `fp.totalExperience` 等于三颗之和。缺陷存在时只有一颗 → 红。**不能只扔一颗**，一颗恰好是缺陷放行的那颗 |
| T8 | `updatePlayerPose()` 从不运行（新发现 N6） | `Player.tick():363`（定义 `:447`）。碰撞箱永远是站立的 1.8 格。补法：镜像 | 造一个 1.5 格高的通道，命令潜行穿过，断言身体到达对面。缺陷存在时卡在入口 → 红。**不能只断言 `getBbHeight()` 变小**——那只测了字段，没测它对碰撞的影响 |
| T9 | 攻击/放置/使用不挥手（新发现 N11） | `bot/sim/` 里没有任何 `.swing(`；挥手写在 `bot/process/CombatProcess.java:263` 这个调用点上。补法：移进 avatar 缝 | 走 `Avatar.attackEntityUnchecked` 直接打一下（绕过 CombatProcess），断言 `fp.swinging == true`。缺陷存在时为 `false` → 红。**必须绕开 CombatProcess，否则测的是调用点而不是缝** |
| T10 | 移动统计从不累加 | `handleMovePlayer` 末尾的 `checkMovementStatistics(dx,dy,dz)` 没有对应物 | 走 100 格，断言 `Stats.WALK_ONE_CM` 大于 0。缺陷存在时恒 0 → 红 |
| T11 | 挖掘走 `Level#destroyBlock` 而不是 `gameMode` | `ServerPlayerAvatar.java:426-439`；`:484-525` 的 javadoc 自己承认掉落走的是 `Block.dropResources(..., ItemStack.EMPTY)`：**无工具要求、无精准采集、无时运、不掉耐久**。补法：改走 `fp.gameMode.destroyBlock(pos)`（javadoc `:519` 已点名这条路） | 四条臂：赤手挖石头断言**没有**掉落；铁镐挖石头断言掉圆石且 `getDamageValue()` 增加；精准采集挖草方块断言掉草方块；时运 III 挖煤矿断言掉落数 > 1。缺陷存在时四条全部掉落且耐久不动 → 至少三条红 |
| T12 | 放置/使用/攻击没有到达距离闸（新发现 N9/N10） | vanilla `handleUseItemOn:1118` 先 `canInteractWithBlock(pos,1.0)`，`handleInteract:1557` 先 `canInteractWithEntity(aabb,1.0)`；avatar 直接进 `gameMode.*` / `Player.attack`。**同一具身体两套规矩**：`canBreak`（`:471-482`）是含 reach 的 | 站在 8 格外放置，断言**失败**；站在 3 格内放置，断言成功。缺陷存在时前者成功 → 红。两条臂缺一不可 |
| T13 | `discard()` 留尸 | 已修：`JoinedPlayerBodies.java:192-205` 把 `remove` 路由到 `PlayerList.remove`，带 `leaving` 重入闸 | 造 5 具、全部 `discard()`，断言 `level.players().size()` 回到基线。缺陷存在时留 5 具 → 红 |
| T14 | `changeDimension` 目的地丢失 | 已修：`AvatarNetHandler.java:81-88` 让 `teleport(...)` 真的 `absMoveTo`（neoforge 的 `FakePlayerNetHandler.teleport` 在 `:254` 是 no-op，这就是 87501 格的来源） | 换维后断言坐标等于期望坐标（±1）。**但这条只覆盖 A/B**：C 穿的是 vanilla 的真 listener，走的是另一条路，必须**单独**验证一遍 |
| T15 | 传送不重算流体标志 | 补一次 `updateInWaterStateAndDoFluidPushing()` | 在水里传送到干黑曜石上，断言 `isInWater() == false`。缺陷存在时为 `true` → 红 |
| T16 | 装备属性同步 | 已有：`EQUIP_MEMO` + `syncEquipmentAttributes()`（`:668-711`，gap #46） | 换上钻石靴断言 `Attributes.ARMOR` 变化；脱下断言回落。两条臂 |

### 4.2 只能近似（10 条）

| # | 条目 | 差在哪一格 | **哪些测试会因此撒谎** |
|---|---|---|---|
| A1 | 手写物理 | `step()`（`:963-1088`）拿 `fp.travel(...)` 驱动，替代真玩家的 `LocalPlayer.aiStep` + `handleMovePlayer` 全链。已知近似点：跳跃 0.42（`:1032`）、冲刺前冲 0.2（`:1037`）、水面上浮 +0.04（`:1046`）、潜行下沉 −0.04（`:1061`）、每 tick 重播 `speed`（`:1066-1067`） | **任何断言「真玩家过得去／过不去」的跑酷、跨沟、爬坡场景**。现有 `wd.physicsParity`（`WorldDriverCoreScenes.java:591`）和 `wd.waterPhysicsParity`（`WorldDriverWaterBankScenes.java:144`）钉的只是四个标量，不是一致性 |
| A2 | `mirrorPlayerTick()` 是手维护清单 | `:769-783`。它**永远**是通道(二)的一个子集，而子集边界由人决定 | 任何依赖「玩家每 tick 会发生的事」的模组行为。这一条**不可能**升级成真等价，除非把它换成真的调 `doTick()`（见附录雷 2/3） |
| A3 | 没有 `awaitingPositionFromClient` 往返 | A/B 的 listener 把 `resetPosition()` 覆盖成空（`AvatarNetHandler.java:73`） | 对 A/B 是好事。对 C 是雷：见附录雷 1 |
| A4 | 视距／客户端选项 | `updateOptions` no-op（`AvatarFakePlayer.java:80`） | 任何和「玩家周围加载了多少区块」有关的断言——包括刷怪、作物生长、红石运转的半径 |
| A5 | 区块批次从不 ack | vanilla `handleChunkBatchReceived:1844` | 只影响 C。区块下发节流可能与真玩家不同速 |
| A6 | 命令来源 | 命令由服务端代跑，`CommandSourceStack` 的实体和权限级不同 | 任何断言「玩家有/没有权限做 X」的模组场景 |
| A7 | 菜单是手工造的 | `openStationMenu`（`:580-594`）自己 `new` 一个菜单再赋给 `fp.containerMenu`（`:585`），滚动 id 1..99；A/B 的 `openMenu` 直接返回 `empty`（`AvatarFakePlayer.java:82`）。C 是唯一走 `super.openMenu` 的（`JoinedPlayerBodies.java:237-239`） | **所有模组容器的打开路径**。`Avatar.java:180-183` 的 javadoc 已经承认「服务端路径退化成一次优雅的超时」。这一条直接决定了模组包测试能摸到多少东西 |
| A8 | 自定义 payload 不通 | vanilla `handleCustomPayload:1874` 无对应物 | 任何用自有网络通道驱动 GUI 的模组。**模组包测试的天花板** |
| A9 | 不能作为聊天来源 | vanilla `handleChat:1245` 无动词 | 任何靠玩家聊天触发的模组功能 |
| A10 | **饥饿**：`foodData.tick()` 不镜像 | `mirrorPlayerTick()`（`:769-783`）把 `foodData.tick()` 列在**故意不镜像**的一栏里。而且光镜像那一行还不够：饥饿消耗来自 `causeFoodExhaustion` 的**每一个**调用点（跳跃 `Player.jumpFromGround:1516-1523`、冲刺、攻击、挖掘），而这些点 avatar 全都绕过了——所以这条不是「补一行」，是「补一族」 | 任何断言「撑不撑得住 / 走得动走不动」的长程场景。`JourneyRig.java` 的类注释已经诚实写过：饥饿、落差、溺水、怪物威胁都在这条轨道观察不到。**这一条和 X2-3 是同一个包**：要一具会死的身体，就必须同时有可关的免伤、`invulnerableTime--`（T6）、饥饿、重生，缺一条都会得到一具「半死不活」的身体，比全免伤更难解释 |

### 4.3 不可能，且不需要（9 条）——换一具身体，不要补代码

这一类的正确答案不是「给这具身体补能力」，是「**这个场景不该用这具身体**」。**接手的可以是真玩家，也可以是 `JoinedBody`**——§0 的指令让后者第一次成为一个真的选项，这一类因此从 7 条涨到 9 条（X2-1、X2-2 降级进来）。

| # | 条目 | 为什么这具身体做不到 | 谁能接手 |
|---|---|---|---|
| **X1-0a** | **进度体系（原 X2-1）** | neoforge `PlayerAdvancements.award`（21.1.230 的 `:186`）对 `FakePlayer` 直接 `return false` | **`JoinedBody`**——它不是 `FakePlayer`，走 else 分支。见 §6.5 甲档。**这条不需要写任何代码** |
| **X1-0b** | **`level.players()` 一族在专用服上（原 X2-2）** | 身体不在玩家表里 | **`JoinedBody`**——`placeNewPlayer` 就是把它放进那张表。今天缺的只是六条闸没设 `realPlayerBodies`，那是配置不是代码 |
| X1-1 | `kill_dragon` | 上面 X1-0a 的一个实例 | 同上 |
| X1-2 | 末影龙战、`BaseSpawner.isNearPlayer`、`NaturalSpawner` | 身体不在玩家表里 | 同 X1-0b |
| X1-3 | 死亡 / 重生 | 三具身体的 `die` 都是 no-op，`handleClientCommand` 的 `PERFORM_RESPAWN` 无处可去 | 天然成立 |
| X1-4 | 睡觉跳过夜晚 | 需要玩家表里所有玩家都在睡 | 天然成立 |
| X1-5 | GUI 操作族：交易、附魔、铁砧改名、告示牌、书、信标 | 无动词，也**不该**有动词——给驱动器加这些动词是给每个 LLM 客户端的提示词永久加税 | 天然成立 |
| X1-6 | 载具：船／马／矿车 | `startRiding → false`（`AvatarFakePlayer.java:86`） | 天然成立 |
| X1-7 | 客户端侧的一切：视角、粒子、音效、渲染 | 服务端身体没有客户端 | 天然成立（且已有 `client.damageSourceAcrossTheWire` 这条真跨进程探针，stagewright `common/src/main/java/net/magicterra/stagewright/client/ClientProbes.java:57`） |

### 4.4 不可能，但仍需要（4 条）——真正的成本

这一类的判据在 §0 之后**收紧了**：只有**连 `JoinedBody` 也做不到**的才算。原来的 X2-1 / X2-2 有了 `JoinedBody` 这个接手方，已降级到 §4.3（X1-0a / X1-0b）。**这一类从 6 条缩到 4 条，这是身体选型指令带来的实打实的减负。**

| # | 条目 | 为什么连 `JoinedBody` 也不够 | 只有两个诚实的出路 |
|---|---|---|---|
| X2-3 | **不死**：`isInvulnerableTo → true` | `JoinedPlayerBodies.java:221` **是我们自己写的一行**，`JoinedBody` 也照样不死。但删掉它是一行的事（§6.5 乙档），配套要 N4（`invulnerableTime--`）和 A10（饥饿）一起，否则得到一具「半死不活」的身体 | **(a)** 做一个可关的开关，配套补 T6 + A10 + 重生；**(b)** 让断言诚实地只说「打得赢」不说「活得下来」——今天 `WorldDriverMobFightScenes.java:338` 就是这么写的：`ctx.record("body.invulnerable", "true —— 所以这一条只说打得赢, 不说活得下来")` |
| X2-4 | `fallDistance ≡ 0` | **换身体完全不管用**：`ServerPlayer.checkFallDamage` 是空覆盖（vanilla `:1012-1014`），对任何 `ServerPlayer` 都一样。这是 §6.5 丙档的典型 | 它**真等价可达**（T1），所以正确归属是 T1；这里只记「今天它让每一条落差断言撒谎」。`JourneyNetherRungs.java:1762-1770` 记着一条 `fallDistance > 2.0f` 的分支**永远不可能触发**（已改判） |
| X2-5 | `ChunkMap` 刷怪窗口 | **对 `JoinedBody` 其实已经是好的**（`tellTheChunkMapWeMoved()` 的早退条件是 `level.players().contains(fp)`，而它在表里）。留在这一类只因为**六条闸一处都不设 `realPlayerBodies`**——这是配置，不是能力 | 翻闸的配置（不是我的产权，也**不该和普查同一轮做**：241 条同时换被测对象会红一批，那些红是真的） |
| X2-6 | **驱动器自身就是被测对象的那些场景** | `wd.serverCapability`、`wd.serverAgentDistinctBodies`、`wd.serverAvatarTickFidelity`、`wd.serverAttackCooldown`、`wd.serverElytra`（`WorldDriverAvatarScenes.java:59-63`）、`wd.serverObservePlayerInventory`（`WorldDriverStationScenes.java:93`） | **迁走就把被测对象删掉了。** 拿真玩家测这些，等于测 vanilla 有没有实现 vanilla |

**X1-0a / X1-0b 的机制（原 X2-1/X2-2），以及为什么它们降级了**：

- neoforge `PlayerAdvancements.award`（`PlayerAdvancements.java:186`，方法起于 `:185`）：`if (this.player instanceof FakePlayer) { return false; }`
- neoforge `PlayerList.getPlayerAdvancements`（`PlayerList.java:821`，方法起于 `:812`）：`if (!(var1 instanceof FakePlayer)) { playeradvancements.setPlayer(var1); }`
- **vanilla 两处都没有这个分支**（vanilla `PlayerAdvancements.java:182` 起的 `award` 里没有 `FakePlayer` 字样；vanilla `PlayerList.java:787` 无条件调 `setPlayer`）。

**两份 jar 我都亲自反编译核对过**，不是转述：vanilla 用 `minecraft-merged-1.21.1-loom.mappings…jar`，neoforge 用
`.gradle/caches/fabric-loom/1.21.1/neoforge/21.1.230/minecraft-merged-mojang-patched.jar`
（注意路径里的 **1.21.1** 和 **21.1.230**，见下），都是 vineflower 1.10.1，`-dgs=1`。

> **引用行号前先核对 jar 的版本和反编译参数。** 这份文档 2026-08-20 的第一版把上面两行标成了
> `21.0.167` 的 `:188` / `:815`。同一个 loom 缓存下确实躺着一棵 `1.21/neoforge/21.0.167/` 的树，但那是
> **MC 1.21.0**，不是本仓库编译的版本（`gradle.properties:26` = `21.1.230`，`:18` = `1.21.1`）。
> 行号还会随反编译参数漂移：带参数名的那次反编译把同一个分支印在 `:188`，`-dgs=1` 印在 `:186`。
> 机制两次都一样，结论没变 —— 但**「我核对过」这四个字如果指向的是另一个游戏版本，下一个人核对时会
> 对不上，然后重新怀疑整条结论**。所以下面每条 jar 引用都写死版本号，并且优先引方法名。

推论链：

1. NeoForge 上今天跑的是身体 B，**就是** `net.neoforged.neoforge.common.util.FakePlayer` → `award` 永远 `return false` → **NeoForge 上任何一次运行、任何场景，这具身体都拿不到任何进度**。
2. 这解释了 `wd.serverAvatarEarnsAdvancement`（`WorldDriverProcessScenes.java:222`）为什么被登记成 optional（`:159-160`）：**Fabric 绿、NeoForge 红**，而红的原因不在 worlddriver 这一侧。
3. **`JoinedBody extends ServerPlayer`（`JoinedPlayerBodies.java:167`），不是 `FakePlayer`** —— 那两个拦截键在 `instanceof FakePlayer` 上，对它**不生效**。
4. **所以 §0 的「废弃 `FakePlayer`」这条指令，顺带把这两条差异一起废弃了。** 不需要为它们写任何代码，`wd.serverAvatarEarnsAdvancement` 的 NeoForge 红也会一起消失。

---

## 5. 十条已知差异各归其位

| 用户给的条目 | 归类 | 状态与依据 |
|---|---|---|
| 1. `stopUsingItem()` 不放箭 | **真等价 T2**，已修 | `ServerPlayerAvatar.java:188`（`7855a349`）。而「还有多少个『客户端包走 A、假人调 B』的动作族没查过」这个问题的答案在 §3：**近似 12 条 + 无动词 21 条**，其中最贵的是 #22d（挖掘）、#23（放置/使用）、#35a-c（实体交互）、#27（换槽） |
| 2. `fallDistance` 恒为 0 | **真等价 T1**（可达），今天在 X2-4 撒谎 | 机制是 `ServerPlayer.checkFallDamage` 空覆盖（vanilla `:1012-1014`），这一阶段才查清 |
| 3. `isInvulnerableTo → true`，永不死、永不重生 | **不可能但仍需要 X2-3** | 三具身体全有。掉出世界变成无限下坠：`JourneyRig.java:196`（`lostTheWorld` 字段 `:205`）记录了 y=−55767，每条腿跑满 2999 tick |
| 4. 屠龙拿不到 `kill_dragon` | **X1-1（有真玩家时）/ X2-1（专用服上）** | NeoForge 上是 `award` 直接 return false；Fabric 上是「没进玩家表 → 龙战不启动」。两个不同的病，同一个症状 |
| 5. `level.players()` 一族 | **X1-2 / X2-2** | 今天靠 `realPlayerBodies=true` 硬撑，但**六条闸一处都不开** |
| 6. 背包满时 `quickMoveStack` 静默无操作 | **不属于身体保真度** | 真玩家背包满时 shift-click 也是同一个 no-op。这是**证据/断言的缺口**，不是假人的缺陷。归 §8 的断言设计 |
| 7. `discard()` 留尸 79 具 | **真等价 T13**，已修 | `JoinedPlayerBodies.java:192-205` |
| 8. `ChunkMap` 里从未移动 → 刷怪窗口钉死 | **X2-5**，且**只在开关开时修好** | `ServerPlayerAvatar.java:1116-1125` 的早退条件是 `level.players().contains(fp)`。假人身体**依然**从不在 `ChunkMap` 里移动 |
| 9. `changeDimension` 目的地丢失 87501 格 | **真等价 T14**，A/B 已修 | `AvatarNetHandler.java:81-88`。**C 未验证**——它穿 vanilla 真 listener，走的是另一条路 |
| 10. 传送不重算流体标志 | **真等价 T15** | 见 T15 |

**分类计数（§0 的身体选型指令生效之后）**：真等价 **16**、只能近似 **10**、不可能且不需要 **9**、不可能但仍需要 **4**。合计 39 条。

折回原本要求的三类：**真等价 16 / 只能近似 10 / 不可能 13**（13 = 不需要 9 + 仍需要 4）。总数没变，**第四类从 6 缩到 4**，缩掉的两条（原 X2-1/X2-2）是被「废弃 `FakePlayer` + `JoinedBody` 上专用服」这条指令直接消掉的。

**另一个更有用的切法在 §6.5**：按「废弃即消失 / `JoinedBody` 一行可达 / 换身体也不会好」分，是 **3 / 4 / 11**。这个切法才直接对应第二阶段的工作量。

---

## 6. 阶段一发现的新差异（不在上面 10 条里）

| # | 新发现 | 依据 | 为什么贵 |
|---|---|---|---|
| N1 | **NeoForge 上假人永远拿不到任何进度** | neoforge `PlayerAdvancements.java:187-189` vs vanilla `PlayerAdvancements.java:182`（无此分支） | 进度保真度是 loader 相关的。而解药（`realPlayerBodies`）从未装在 NeoForge 上 |
| N2 | NeoForge `PlayerList.getPlayerAdvancements` 跳过 `setPlayer` | neoforge 21.1.230 `:821` vs vanilla `:787` | 同上，第二道锁 |
| N3 | `fallDistance ≡ 0` 的**机制**是 `ServerPlayer.checkFallDamage` 空覆盖 | vanilla `ServerPlayer.java:1012-1014`、`Entity.java:711`、`ServerPlayer.java:1023` | 说明补法只有一行，且必须补在 `step()` 里而不是指望 `move()` |
| N4 | `invulnerableTime` 永不递减 | `LivingEntity.java:468-470` 显式排除 `ServerPlayer` | **摘掉 `isInvulnerableTo` 并不能让身体变成可打的**。任何「让身体会死」的计划必须同时补这一行，否则会得到一具第一次挨打后近乎免疫的身体 |
| N5 | **这具身体一辈子只吸一颗经验球** | `Player.tick():292-293` 是 `takeXpDelay` 唯一的减法；`ExperienceOrb.java:234` 门、`:239` 设回 2 | 附魔、经验相关的整条链条都测不到，而症状是「捡了但没涨」 |
| N6 | `updatePlayerPose()` 从不运行，碰撞箱永远 1.8 格 | `Player.tick():363`，定义 `:447` | 潜行钻洞、游泳姿势、滑翔姿势全是假的 |
| N7 | 硬编码 0.42 丢掉跳跃属性、方块跳跃系数、跳跃提升、`Stats.JUMP`、饥饿消耗、`onLivingJump` | `ServerPlayerAvatar.java:1032` vs `LivingEntity.getJumpPower:2115-2117`、`Player.jumpFromGround:1516-1523`、`LivingEntity.java:2135` | 蜂蜜块、灵魂沙、跳跃药水对这具身体完全不存在 |
| N8 | 换槽不调 `stopUsingItem()` | vanilla `handleSetCarriedItem:1230` vs `ServerPlayerAvatar.java:287-289`、`:281-282`、`:626-641` | 拉满的弓换槽后仍在「使用中」，是第 1 条那一族「包走 A、假人调 B」里剩下的一条 |
| N9 | 攻击没有到达闸也没有类型闸 | vanilla `handleInteract:1557`、`onAttack` 的拒绝分支 vs `ServerPlayerAvatar.java:644-646` | 能打到真玩家打不到的东西——一个假的「可以」 |
| N10 | 放置/使用没有到达闸 | vanilla `handleUseItemOn:1118` vs `ServerPlayerAvatar.java:349-375`、`:535-545` | 同上。且**同一具身体两套规矩**：`canBreak`（`:471-482`）含 reach，`place` 不含 |
| N11 | 挥手不在 avatar 缝上 | `bot/sim/` 无任何 `.swing(`；`bot/process/CombatProcess.java:263` 在调用点挥 | 「每条不变量都有一条无视它的后备」的同一形状：换一个调用点就没有挥手 |
| N12 | `EventHooks.firePlayerTickPre/Post` 与 `CommonHooks.fireBlockBreak` 从不触发 | `Player.tick():286,:368`；`ServerPlayerGameMode.destroyBlock:247-284`（而挖掘走的是 `Level#destroyBlock`） | **对一个以模组包测试为卖点的驱动器，这是最贵的一条**：模组挂在玩家 tick 和 `BreakEvent` 上的逻辑全部看不见 |
| N13 | `gameMode.tick()` 从不运行 | `ServerPlayerGameMode.java:99-122` | 两段式挖掘状态机（`isDestroyingBlock` / `hasDelayedDestroy` / `incrementDestroyProgress`）整个不存在 |
| N14 | 21 个动作族**根本没有动词** | §3 的表 | 其中 `interactOn`/`interactAt`、`drop`、容器按钮、自定义 payload 四族是模组包测试的四个洞 |
| N15 | `updateOptions` no-op → 视距恒为默认 | `AvatarFakePlayer.java:80` | 加载半径与真玩家不同，间接影响一切「周围有没有在跑」的断言 |
| N16 | 区块批次从不 ack | vanilla `handleChunkBatchReceived:1844` | 只对 joined body 有意义 |
| N17 | **已核对否定**：`closeContainer` 是等价的 | `ServerPlayer.java:1147-1149` 内部就调 `doCloseContainer()` | 我怀疑过「合成格里的东西不会掉回来、箱子开启计数会泄漏」，查了，不成立 |

---

## 6.5 逐条归属：废弃即消失 / `JoinedBody` 可达 / 换身体也不会好

**这一节是 §0 那条指令的直接产物，也是决定第二阶段工作量的那张表。**

三档的判据：

| 档 | 判据 | 第二阶段要不要做 |
|---|---|---|
| **甲：废弃即消失** | 差异的机制**键在 `instanceof FakePlayer` 上**。`FakePlayer` 一废，这条自动没了 | **不做**。做了是白做 |
| **乙：`JoinedBody` 可达** | 机制不是 `FakePlayer`，而是**我们自己写的那一行覆盖**或 `placeNewPlayer` 会补上的东西 | **一行到几行**，且改动落在我们自己的文件里 |
| **丙：换身体也不会好** | 机制在**驱动器绕过真 handler**、或在通道(二)整条不跑上。`JoinedBody` 也一样绕、一样不跑 | **这才是真工作量** |

### 甲：废弃即消失（3 条）

| # | 差异 | 为什么废弃即消失 |
|---|---|---|
| N1 | NeoForge 上假人永远拿不到任何进度 | neoforge `PlayerAdvancements.award`（21.1.230 的 `:186`）是 `if (this.player instanceof FakePlayer) return false;`。**我重新反编译了 neoforge 21.1.230 的 patched merged jar 核对过这一行**（不是转述；21.1.230 才是 `gradle.properties:26` 里编译用的版本）。而 `JoinedBody extends ServerPlayer`（`JoinedPlayerBodies.java:167`），**不是** `FakePlayer` → 走 else 分支，和真玩家同一条路 |
| N2 | `PlayerList.getPlayerAdvancements` 跳过 `setPlayer` | neoforge `PlayerList.getPlayerAdvancements`（21.1.230 的 `:821`，方法起于 `:812`）是 `if (!(var1 instanceof FakePlayer)) playeradvancements.setPlayer(var1);`。同上，`JoinedBody` 落进 `!instanceof` 为真的那一支。**两处拦截键在同一个 `instanceof` 上，所以是同一个开关的两个齿** |
| N15a | `updateOptions` no-op → 视距恒为默认 | 只有 `AvatarFakePlayer.java:80` 覆盖了它；`JoinedBody` 没有这个覆盖，且 `placeNewPlayer` 会通过 `CommonListenerCookie` 送进一份真的 `ClientInformation`。**注意只有一半**：`JoinedBody` 拿到的是 `ClientInformation.createDefault()`（`JoinedPlayerBodies.java:172-174`），是一份**真的默认值**而不是「没有值」——差异从「缺失」降级成「不可配」，见 N15b |

> **顺带的直接后果**：`wd.serverAvatarEarnsAdvancement`（`WorldDriverProcessScenes.java:222`）的 NeoForge 红**会随 `FakePlayer` 的废弃一起消失**，不需要为它写任何代码。§4.4 原来把这条列在 X2-1（「不可能但仍需要」）里，**现在它降到第三类**——有 `JoinedBody` 可以接手。

### 乙：`JoinedBody` 可达（4 条）——全都卡在同一行

**这四条有一个共同的病灶：`JoinedPlayerBodies.java:217` 那行 `@Override public void tick() { }`。**

`placeNewPlayer` 会把身体送进 `ServerLevel` 的**实体 tick 名单**，于是通道(一)每 tick 都会调用它。
全链（neoforge 21.1.230 / MC 1.21.1，vineflower 1.10.1 `-dgs=1`；vanilla 行号另注）：

| 步 | 位置 | 这一步做了什么 |
|---|---|---|
| 1 | `PlayerList.placeNewPlayer:144` → `:232` | `serverlevel1.addNewPlayer(var2)`（vanilla 同一句在 `PlayerList.java:221`） |
| 2 | `ServerLevel.addNewPlayer:915` → `addPlayer:923` | `this.entityManager.addNewEntityWithoutEvent(var1)` |
| 3 | `ServerLevel$EntityCallbacks.onTickingStart:24-25` | `this.this$0.entityTickList.add(var1)` —— **身体进了 tick 名单** |
| 4 | `ServerLevel.java:408` `entityTickList.forEach` → `:428` `guardEntityTick(this::tickNonPassenger, …)` | 每 tick 遍历名单 |
| 5 | `tickNonPassenger:771` → `:778` `var1.tick()` | **调用到身体自己的 `tick()`** |

也就是说通道(一)对 `JoinedBody` 是**接通的**——被我们自己那一行覆盖掐断了。

> 第 2 步值得单独记一笔：**玩家不走 `ServerLevel.addEntity`**。`addEntity`（`:937`）是给非玩家实体的
> 另一个私有方法，玩家走的是 `addPlayer` → `addNewEntityWithoutEvent`。本文档第一版把链条写成
> `addPlayer → addEntity`，那一步是错的（结论不变，因为两条路最终都落到第 3 步的
> `onTickingStart`）。写链条时**每一跳都要在反编译源里看见调用语句**，不要靠方法名推。

| # | 差异 | 删掉那行 `tick()` 覆盖后会怎样 |
|---|---|---|
| N4 | `invulnerableTime` 永不递减 | `ServerPlayer.tick()` 第 4 行就是 `if (this.invulnerableTime > 0) this.invulnerableTime--;`（vanilla `ServerPlayer.java:473-475`）→ 自动好 |
| N13 | `gameMode.tick()` 从不运行 | `ServerPlayer.tick()` 第 1 行就是 `this.gameMode.tick();`（vanilla `:470`）→ 自动好。**但两段式挖掘状态机还需要驱动器真的去调 `handleBlockBreakAction`**，那一半属于丙档（见 T11） |
| X2-3 | 不死 | `isInvulnerableTo → true` 是 `JoinedPlayerBodies.java:221` 我们自己写的一行。**删掉它就是可死的**——`JoinedBody` 在玩家表里，`PlayerList.remove` / 重生路径都是通的。配套需要 N4 一起（否则会得到一具第一次挨打后近乎免疫的身体） |
| X2-5 | `ChunkMap` 刷怪窗口钉死 | `tellTheChunkMapWeMoved()`（`ServerPlayerAvatar.java:1116-1125`）的早退条件是 `level.players().contains(fp)`，而 `JoinedBody` **在**玩家表里 → 这条对 `JoinedBody` **已经是好的**。它今天之所以还撒谎，纯粹是因为六条闸一处都不设 `realPlayerBodies` |

⚠️ **删那一行 `tick()` 覆盖不是免费的**，见附录雷 3：`ServerPlayer.tick()` 的最后一行是 `this.advancements.flushDirty(this)`（vanilla `:503`），并行跑多具身体时是每身体每 tick 一次落盘检查。**并行执行能力是硬约束，这个代价必须先量再改**，而量它正是普查场景的事。

### 丙：换身体也不会好（11 条）——第二阶段的真工作量

这些差异的机制都不在身体的类型上，而在**驱动器绕过了真 handler**，或在**通道(二)整条不跑**上。`JoinedBody` 一样绕、一样不跑。

| # | 差异 | 为什么换身体不管用 |
|---|---|---|
| N3 | `fallDistance ≡ 0` | `ServerPlayer.checkFallDamage` 是**空覆盖**（vanilla `:1012-1014`），对**任何** `ServerPlayer` 都一样，`JoinedBody` 也是。vanilla 只从 `handleMovePlayer` 走 `doCheckFallDamage`，而这具身体不发移动包 |
| N5 | 一辈子只吸一颗经验球 | `takeXpDelay--` 在 `Player.tick():292-293`，属于**通道(二)**。而 `JoinedBody` 的 `SilentConnection` 不在 `ServerConnectionListener.connections` 里，它自己的 `tick()` 也被覆盖成空（`JoinedPlayerBodies.java:290`）→ `doTick()` 从不被调用 |
| N6 | `updatePlayerPose()` 不跑 | 同上，`Player.tick():363`，通道(二) |
| N7 | 硬编码 0.42 | `ServerPlayerAvatar.java:1032`，**驱动器自己写的**，与身体类型无关 |
| N8 | 换槽不 `stopUsingItem()` | `ServerPlayerAvatar.java:287-289` 等三处，驱动器自己写的 |
| N9/N10 | 攻击/放置没有到达闸 | 驱动器直接调 `gameMode.*` / `Player.attack`，落在包处理**下面**一层。换身体不会让它爬回上面那一层 |
| N11 | 挥手不在 avatar 缝上 | `bot/process/CombatProcess.java:263`，驱动器自己写的 |
| N12 | `firePlayerTickPre/Post` 与 `fireBlockBreak` 不触发 | 前者属通道(二)；后者因为挖掘走 `Level#destroyBlock` 而不是 `gameMode.destroyBlock`。**两半都与身体类型无关** |
| N14 | 21 个动作族没有动词 | 驱动器根本没写这些动词。给它换一具更真的身体，也没有人去调 |
| N15b | 视距**不可配** | `JoinedBody` 拿到 `ClientInformation.createDefault()`，是一份真的默认值；但没有 `handleClientInformation` 的入口去改它。从「缺失」降级为「不可配」——**降级了，没消失** |
| N16 | 区块批次从不 ack | `SilentConnection` 吞掉一切，且没有客户端会 ack |

**所以第二阶段的账是**：甲档 3 条不用做；乙档 4 条是「删一行覆盖 + 量一次代价」；**丙档 11 条才是真工作量**，而其中 N7/N8/N9/N10/N11/N14 六条**完全落在我自己的产权路径 `bot/sim/**` 里**，不需要动别人的文件。

---

## 7. 场景归属：谁该迁走，谁迁不了

**迁移机制今天就有**：`SceneContext.playerHere()`（stagewright `api/src/main/java/net/magicterra/stagewright/scene/SceneContext.java:135`）会把真玩家传送进舞台并注册还原清理；`SceneContext.player()`（`:118`）在没有真玩家时**跳过场景**（`:150`）。

**迁移的诚实代价**：一次跳过记作 PASS。所以把场景迁到真玩家身上，等于让它在专用服的两条闸上变成「绿色的沉默」。唯一能看见这件事的是跨拓扑覆盖闸 `stagewrightCoverage`（`AGENTS.md:206-212`）——**任何迁移提案必须同时确认该场景在至少一个拓扑上真的执行过**，否则就是把一条测试换成了一条永远绿的空气。

### 该迁走的（被测对象是 vanilla / 模组行为，不是驱动器）

| 场景 | 今天 | 迁到哪 |
|---|---|---|
| `wd.serverAvatarEarnsAdvancement`（`WorldDriverProcessScenes.java:222`） | optional，Fabric 绿 / NeoForge 红 | **两条路都要走**：真玩家拓扑上升回必需；专用服上开 `realPlayerBodies=true`（NeoForge 尤其需要，见 N1） |
| 梯子第 20 级屠龙（`JourneyEndRungs.java:1693` 的 `kill_dragon` 证据） | 靠 `realPlayerBodies=true` 硬撑 | 集成服／joining-client 上改由真玩家爬 |
| 梯子第 14–15 级的刷怪笼／自然生成（`JourneyNetherRungs.java:1087`） | 同上 | 同上 |
| 任何断言「掉了多少血 / 活不活得下来」的场景 | 今天只能诚实地不断言（`WorldDriverMobFightScenes.java:338`） | 真玩家拓扑 |
| `wd.threatScanHurtAttacker`（`WorldDriverCombatScenes.java`） | 已经在用一个 **vanilla `Player`** 当受害者，正因为假人 `isInvulnerableTo` 返回 true（类注释 `:43`、`:61`） | 这条已经是「迁走」的正确范例，可以照抄 |

### 迁不了的（被测对象**就是**驱动器）

| 场景 | 为什么 |
|---|---|
| `wd.serverCapability`、`wd.serverAgentDistinctBodies`、`wd.serverAvatarTickFidelity`、`wd.serverAttackCooldown`、`wd.serverElytra`（`WorldDriverAvatarScenes.java:59-63`） | 它们钉的是 gap #45/#46/#47/#48，被测对象是这具身体本身 |
| `wd.serverObservePlayerInventory`（`WorldDriverStationScenes.java:93`） | 钉的是 `mc.observe.player` 对**服务端身体**的读数 |
| 整个 `pack.*` 家族 | 模组包在专用服上跑，构造上没有人 |
| `runJourneyServer`（无头梯子） | 它存在的意义就是「没有人也能爬」 |
| 所有经 RPC / MCP 对外接口驱动的东西 | 卖点就是无人驾驶 |

> **这一节的一句话**：假人不是测试替身，假人**就是产品**。所以「迁到真玩家」只对那些被测对象是 vanilla 行为的场景成立；被测对象是驱动器的场景一条都迁不走，它们的差异只能补、或者诚实承认。

---

## 8. 验证方案设计：怎么不掉进 `0==0`

三条纪律，每条都来自上面某个具体条目：

1. **绝不用布景把被测对象摆成期望状态再断言它是期望状态。** 反例：T5 如果只在普通方块上跳一次并断言 apex ≈ 1.25，那么无论跳跃力是硬编码 0.42 还是真的走 `getJumpPower()`，结论都一样——它测的是 0.42 等于 0.42。正确做法是**两条只差一个自变量的臂**（普通方块 vs 蜂蜜块），让差异本身成为被断言的量。
2. **断言必须落在缺陷会改变的那个量上，而不是缺陷的下游。** 反例：T1 用「掉下去会掉血」验证 `fallDistance`——身体免伤，这条断言在缺陷修好之后依然红，它测的是 X2-3 不是 T1。
3. **计数类断言不能只取一个样本。** 反例：T7 只扔一颗经验球——一颗恰好是缺陷放行的那颗。

**顺带修第 6 条（`quickMoveStack`）**：每一条熔炼/合成断言必须同时 `ctx.record` 三个量——产物数量、背包剩余空槽、以及操作前后的手持栈。缺任何一个，「熔完了但塞不进去」和「根本没熔」就印成同一行。

**第一件该做的验证，是一条一条断言都不写的场景。** 见 §9。

---

## 9. 最值得先做的三件事

### 一、先加一条只测量、不断言的普查场景

一条新的 `wd.bodyParityCensus`，在两个 loader × 六个拓扑上各跑一遍，把下面这些量全部 `ctx.record` 出来，**一条断言都不写**：

`inPlayerList`、`isInvulnerableTo`、`invulnerableTime`（打一下后连续读 5 tick）、`fallDistance`（自由落体中逐 tick）、`takeXpDelay`、`totalExperience`（扔 3 颗球后）、`getPose()`/`getBbHeight()`（潜行前后）、`Stats.WALK_ONE_CM`（走 100 格后）、`Stats.JUMP`、`advancements` 是否可写（`award` 一个假 criterion 看返回值）、`swinging`（走 avatar 缝打一下后）、挖石头的掉落与耐久、跳跃 apex（普通/蜂蜜/跳跃提升三臂）。

**为什么这是第一件事**：硬约束是「先测量、先证明差异真实存在且真的影响测试结论，再谈改」。这条场景是那句话的字面执行。它不可能 `0==0`，因为它一条断言都没有。它也不需要 Python——它就是一条 StageWright 场景，用 `ctx.record`（stagewright `SceneContext.java:598`）写。跑完之后，上面 39 条里哪些是真的、哪些是我读错了，一趟全部有数。

**⏳ 这条有时间窗口，而窗口正在关。** §0 要废弃 `FakePlayer`，所以普查必须**在废弃之前同时抓到 `FakePlayer` 和 `JoinedBody` 两列读数**——同一条场景内，两具身体并排量同样的 14 个量。理由：§6.5 那张「甲/乙/丙」三档表，今天是我**读代码推出来**的；两列并排的实测读数是**唯一**能证伪它的东西。废弃之后那一列永远取不到，这张表就再也没法验证了。

**它自己的验收**：这条场景在 `scripts/stagewright/expected-scenes-*.txt` 里登记（**与场景同一个提交**，否则清单漂移守卫会红），并且必须在 `stagewrightCoverage` 上证明六个拓扑都真的执行过——否则它就是那种「绿色的沉默」。

### 二、补两条「包走 A、假人调 B」里剩下最贵的

- **换槽调 `stopUsingItem()`**（N8）：三处各一行，验证见 T4。
- **挖掘改走 `gameMode.destroyBlock`**（T11 / N12 / N13）：这是 §3 里第二贵的一条，一改就同时拿回工具要求、精准采集、时运、耐久、`BreakEvent`。**而 `BreakEvent` 是模组包测试的必需品**——没有它，任何挂在挖掘上的模组逻辑在这套测试里都不存在。

  ⚠️ 这条改动会**改变现有场景的结论**：`ServerPlayerAvatar.java:484-525` 的 javadoc 记录了 `DROP_HARVEST = true` 是为了让梯子的采木腿拿到木头。改成 `gameMode.destroyBlock` 之后，赤手挖石头将**不再掉落**——这是正确的，但会让若干条今天靠「挖什么都掉」的场景变红。**这些红是真的，不是回归。**

### 三、把 `realPlayerBodies=true` 装到 NeoForge 上，并把进度场景升回必需

**§0 的指令已经把这一条从「我的提案」变成了「执行细节」**——废弃 `FakePlayer`、专用服上用 `JoinedBody`，就是这件事。机制见 §6.5 甲档：那两道进度拦截键在 `instanceof FakePlayer` 上，`JoinedBody` 不是它。装上之后，`wd.serverAvatarEarnsAdvancement`（`WorldDriverProcessScenes.java:159-160`）应该能从 optional 升回必需，两个 loader 都绿。

**这一条必须先被第一件事证明**：普查场景的「`advancements` 是否可写」一列，在 NeoForge 上 `FakePlayer` 那一列应报 `false`、`JoinedBody` 那一列应报 `true`。**先看到这两个读数，再翻闸。**

⚠️ **翻闸要单独一轮**：六条闸一处都不设 `realPlayerBodies`，一翻就是 241 条 `wd.*` 同时换被测对象，会红一批，**那些红是真的**。不要和普查混进同一趟 gate，否则谁红了都归不了因。翻闸的文件（`fabric/build.gradle` / `neoforge/build.gradle`）也不是 parity 的产权。

---

## 附：第二阶段的雷（现在就写下来，免得踩）

1. **`awaitingPositionFromClient`**：如果第二阶段把动作改成「走真 handler」，对身体 C 会在第一次传送后静默失效——`ServerPlayer.teleportTo`（vanilla `:1428-1430`）经 `connection.teleport` 设上这个字段，而 `handleUseItemOn:1126` 的闸是 `awaitingPositionFromClient == null`，没有客户端会来 ack。**症状会是「传送之后所有的使用/放置都无声地什么都不做」**，看起来像寻路问题。
2. **通道(二)不会因为改走 handler 就自动跑起来**。`SilentConnection` 不在 `ServerConnectionListener.connections` 里，而且它自己的 `tick()` 也被覆盖成空（`JoinedPlayerBodies.java:290`）。想让 `doTick()` 跑，必须显式在服务器 tick 里调，并且**先量代价**。
3. **恢复 `tick()`（删掉三处空覆盖）会顺带打开 `advancements.flushDirty(this)` 的每 tick 写盘**（vanilla `ServerPlayer.java:469-503`）。并行跑多具身体时这是每身体每 tick 一次落盘检查——**并行执行能力是硬约束，这个代价必须先量再改**。
4. **不许退回单例**。`FakePlayerFactory.getMinecraft(level)` 是每 level 单例，`wd.serverAgentDistinctBodies`（`WorldDriverAvatarScenes.java:109`）就是钉这个的。任何「让身体更真」的改动都不能把两具身体变回一具。
5. **对外接口是硬约束**。身体保真度的改动只能落在 `common/src/main/java/net/magicterra/worlddriver/bot/sim/`，不能落在 `rpc/` 或 `mcp/`——`DriverApi.route` 是唯一真源，传输层不放游戏行为。
6. **不许引入 Python**。上面每一条验证都是一条 StageWright 场景，用 `ctx.record` / `ctx.check` / `ctx.expect` 写（stagewright `SceneContext.java:598`、`:570`、`:562`）。需要 Python 才能测量，本身就是设计失败。

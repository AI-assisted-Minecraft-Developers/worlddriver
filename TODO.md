# TODO

## 📖 本文件是怎么来的（2026-08-26 精简重写）

这一版是一次**精简重写**。重写之前 `TODO.md` 是 25 670 行 / 2.0 MB 的连续工作日志，
里面绝大多数是**已结的调查记录**——已验、已闸、已落、已撤回、已作废。这次把它们全部删掉，
只留三类东西：**开着的活**、**判了「不做」但写了重开条件的条件式待办**、
**还没落地的预登记**。

- **删掉的东西一条都没丢，全在 git 里。** 重写前那一版的完整内容在提交
  `fb94af03d59f4bc16639f102ab184ac5c037ac9f` 里，一条命令就能翻回全部历史读数：

  ```bash
  git show fb94af03d59f4bc16639f102ab184ac5c037ac9f:TODO.md
  ```

- **执行排期搬走了。** 「窗口 0–4」那一节现在是 [`ROADMAP.md` §6](ROADMAP.md)。
  待办是「还欠什么」，路线图是「按什么顺序做」，两件事不该住在一起。
  **这里不留副本**——两处各存一份，过几天就会分叉。
- **已结的行为变更搬进 [`CHANGELOG.md`](CHANGELOG.md)。** 「为什么这个行为变了」归那里。

⚠️ **两条从旧版继承下来、代价已经付过的纪律，别丢**：

1. **开一趟真梯之前，把所有「在查／冻结中／待验」的行拿去 `git show HEAD:` 核一遍。**
   旧版被抓到过整批过期状态行（修法早在 HEAD 而行里还写着「在查」）。
   一行过期的状态会让这一趟的读数被判给错的账本。
2. **修法或场景落地的那笔提交顺手改队列行**，别等下一次审账来抓。

---

## 📋 队列（真客户端通关，2026-08-22 起）

一行一件，**状态在最左**。做完就把行删掉并把结论写进 `CHANGELOG.md`，不要在这里写细节。

| 状态 | # | 事 | 归属 |
|---|---|---|---|
| 🟠 判：先补测量 | Q15c | **缺的读数＝PREP 无条件写 `readyTicks`/`readyMs`**（落 `stagewright-scenes/pack.js`，只加仪器不改行为，随下一轮闸读分布）。两笔嫌疑提交 `19b18c2`／`a384733` 都被本行自己排除（那场反射风暴在集成日志里新旧都是 0 条）之后，`pack.placesAndReadsBack` ENV_FAIL(10001ms) → **PASS(3336ms)** 这个翻转只剩「**又慢又飘**」一个假设，没有分布判不动 | 我 |
| 🟠 已量·修法挂 Q7c | Q7 | V1 冻屏**数出来了**：单秒最高 **20 次**搜索、**26%** 的秒 ≥3 次，全在 Render thread；最大单一来源是 **174 次起点目标全同的重问**（`owner=mine`）。「缺计数器」是错的——`search-begin` 一直无条件在打。**修法定形在 Q7c，本行只留测量** | 我 |
| ⏸ 推迟 | Q12b | 新增一条「真世界」拓扑（开刷怪＋放时钟）。**重开条件**：`PORTAL_LIT`（12 级）在钉住的世界**连续两趟 PASS**。今 12 级仍 FAIL（最近三趟排练依次死在门洞清渣、`goto` 吃楼梯支撑、`Goal.XZ` 打竖井），条件明确不成立；现在开刷怪，方差恰好砸在正被查的那一级上。⚠️ 这条到期时**必须真的执行**，别让它变成又一个「永远不来的触发词」（同 J7 的教训） | 我 |
| 🔴 判：做（触发改具体：下一个 knob 之前） | J7 | **`BotConfig.java` 2993/3000，零死 import**——顶着上限，**要拆不要刮**（[[a-file-pinned-at-its-budget]]）。触发词从「梯子稳后」改成「**任何要新增 `BotConfig` knob 的修法之前，先拆**」；Q7c 已被迫复用 `walkerFutileSearchCap` 就是这条上限在收税 | 我（下一个 knob 之前） |
| 🟠 判：做（排练退出后的编译窗口） | J15 | **两份装桶实现并存**：`WorldDriverJourneyScenes.fillFrom`（今在 `:2671`，10 级隧道用，一次瞄准一次 use，没有重瞄／换源／装料站）与 `JourneyFill.fillFrom:255`（就近夹＋三次进近＋`scoop` 三次重瞄＋`fillStation`）。已咬两口：Q25 是 scenes 份缺 `BUCKET_REACH` 那半格；j46 判词证明 11 级走的仍是 scenes 份（有 `fill.hand` 无 `.spot`／`.aimsAt`）。且 J48-(B) 的 `bucketInHand` 守卫只落在 `JourneyFill`，scenes 侧 `:2673` 的 `holdForUse(rig, Items.BUCKET, "fill")` **返回值仍丢**、拿不到桶就白花一次 use、红挂在「没装到」名下。**第一步只补同款守卫点名「拿不到桶」；合并成单份留给证据键允许变的那一轮** | 我 |
| 🟠 判：做（先离线回放；Java 等排练退出后的编译窗口） | Q7c | 形状已定：**加宽现有那道闸，不造第二个调速器**——`WalkerTickSearch:85` 自己写着「two governors on one loop would race」。规格＝已量出的**两条盲区**：`!res.goalReached()` 让「搜得到、走不了」永不计数，`distSqr(foot) > 4` 让 5 格 ping-pong 每次清零。**不加新 `BotConfig` 开关**，复用 `walkerFutileSearchCap`。**落 Java 之前先离线回放**：拿 ladder-14 已录的 **174 案／816 案**回放新计数规则，必须抓住那两案且**不误伤正常绕行**（`journey03Wood` 绕树那段是现成阴性样本）。Q22 那 42 次岩浆重搜正是这道闸该数而没数的案 | 我 |
| 🟠 判：排到 J47 之后（验收随 ashore 翻绿） | J39 | 修法 `bdece564`（`JourneyCast.leaveWithTheLava` 在 `climbOut` 之后补 `standOnDryGround`，复用 `JourneyTerrain.dryUnderfoot`，预算 **600 tick**）**在 HEAD 但至今未验**——真梯发作条件没复现。验收已移交 `wd.journeyGetsAshoreBeforePouring`（J45b），而它常驻已知红，红的不是 `standOnDryGround` 而是**浮体走不上齐平岸**（J47）⇒ **本行下一步就是 J47 已判的写死步骤；J47 绿则此行随之验** | 我 |
| 🟠 判：血量半已落，空气半还欠 | J40 | ② **血量那一半在 HEAD 且已实测出行**：`JourneyRig.noteHurt`（`:1563`，无 flag、无节流、只记掉血、上限 60 行），排练日志里写出 `hp.trace=掉血 N 次、回血 M 次；t20 −1.0→19.0 @…`。⇒ **14 级的推进条件（读血量曲线）已经满足，不必再等这一项**。**还欠 `getAirSupply()`**——它服务的是 j39 溺水那条线，不挡 14 级。① 引擎自救维持**不做**（先用写死步骤） | 我 |
| 🟠 判：做（引擎批，双闸，不与真梯同趟） | J33 | **专用服身体和客户端身体给「挖穿」定的不是同一个价**：`LevelWorldView.breakCost:93-104` 只有 `COST_PER_TICK × ticks`，`ClientWorldView` 叠了四道税（浮水 ×25／×5、错工具 ×3、树干税、`pathfinderBreakCostMultiplier`）⇒ 专用服上任何「会不会挖穿」的场景量的都是另一张表。**第五条分歧方向相反**：服务端规划器按**手里正拿着的那件**定价（`LevelWorldView:100`），而它的执行器破坏前会从**全部 36 格**换上最优工具（`ServerPlayerAvatar.selectTool:324-343`）⇒ **规划器比执行器严**。三条承重断言逐条核过：`selectTool` 确实扫 `inv.items.size()`、`LevelWorldView` 确实**只有 1 参 `breakCost`**（浮水税那条 2 参路径根本进不来）、`ClientWorldView` 确实只扫 `slot < 9`。证据在这个类自己的 javadoc 里（`:79-83`）。搬法分两笔两闸。**Q14 的残余记在这里** | janitor 已评估，我判做 |
| 🟠 判：头条做（窗口 1 仪器批），其余 12 个不做 | J41 | 头条＝`WorldDriverJourneyScenes:2521` 的 `tunnel.fell`，走 `ascendByTowering` 的 `String tag` 入口，**根本不进 `recordExit`**（`toY`／`endedIn`／`endedOn`／`gained`／`lost`／`pillarStock` **六行一行都没有**，只有 `tunnel.climbedBackTo`），而它爬的是**岩浆廊道**。全表比例：afloat **1/13**、`endedIn` **1/13**、`gained/lost` 在调用点判 **2/13**（#4 #5），另 **4 处**靠下游或下一级守卫兜（#3 #7 #9 #10），**完全没接 5 处**（#2 #6 #8 #11 #13）。**尾巴那 12 个**：重开条件＝判词把红记在一次爬升的结局上而那一段三行皆无，届时只补那一个入口——展开说就是：任一趟的判词把红记在一次爬升的结局上，而那一段找不到 `*.afloat`／`*.endedIn`／`*.gained` 任何一行 ⇒ 给**那一个**入口补，**不批量补** | janitor 查，我排 |
| 🟠 判：做（排练退出后的编译窗口，机械） | J43 | 同一句天光高度（`getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, …).getY()`）在 journey 包里手写 **12 次**，`JourneyTerrain` 自己那 3 处已收进 `daylightAt`（`f1cf067d`）。**剩 9 处（今日重核）**：`JourneyEndRungs:889`、`JourneyRehearsal:983`／`:2028`、`JourneyRoute:456`／`:526`／`:572`／`:639`、`WorldDriverJourneyScenes:255`、`JourneyRig:381`（⚠️ 从本行原写的 `:333` 挪到了 `:381`——**行号照核不照抄**）；`JourneyTerrain.daylightAt:280` 现成。不改证据键，`:common:compileTestmodJava` 即闸。⚠️ 动手前逐个确认 9 处 `level` 的**声明类型**（`daylightAt` 形参是 `ServerLevel`），`JourneyRehearsal:983` 要的是 `BlockPos` 不是 `int`；顺手清掉 4 处内联 FQN（硬规则 7） | janitor |
| 🟠 判：先补测量（工程窗口仪器批） | J75 | **末影人有些仗根本不结束**：`wd.serverEarnsAnEnderPearl` 在 NeoForge 上约 **1/4** 翻红。四趟 24 场，每场要么 **66–150 tick** 打完、要么**恰好烧满 4000**，**中间值一个都没有** ⇒ 是**卡死**不是慢。补一行 `enderman.stall.<场次>`，详见「拍板」节 | 我 |
| 🟠 判：先补测量（补判，`304373fc` 推翻了「排到 J72 之后」） | 丙 | **水源的存活窗口**（`JourneyStairs.java:385` 点名的「reclaim it」）。18011 tick 那趟把水读成了死因，原判第一条理由当场作废；但命中的是「淹」不是「丙 是解」⇒ 先把 38 条 `*.stairsBroken` 按 `lava*`／`cast*` 拆开读，零代码。详见「拍板」节 | 我 |
| 🟠 判：做（引擎批，双 loader 闸） | J46 | **引擎侧那份孪生没修**：`PlaceNearby.place`（`PlaceNearby.java:52`，今日重核）用的还是「非空气且不可替换」，而 testmod 侧 `54e4bc14` 之后问的是 `isFaceSturdy(lvl, below, UP)` ⇒ [[two-ones-that-disagree]] 从风险变成**现状**（它仍会对着睡莲白点一次）。谓词照抄 `54e4bc14` 那句；**两份必须一起改**。走独立双 loader 专用服闸，不与真梯同趟 | 我 |
| 🟠 判：做（排练退出后的编译窗口） | J44c | **坑沿加价「每趟重算」白花了**：j48 的 12 级 15 条 `*.rimTax` **格数恒为 752**，一次都没涨——`onThePoolsLip` 要的是「脚边有个能掉下去、底下 8 格内是岩浆的洞」，而抽走源块留下的是空气、清射线敲的是岩浆层的挡土，都没造出新的**站得住**的沿格。⇒ 每趟一次 **25×25×9** 全量扫描是纯开销。**修法一句：重算撤回去程算一次的快照，加价本身一字不动**（j48 的 12 级零岩浆死是它买的） | 我 |
| 🟠 判：先补测量（2026-08-25） | J54 | **11 级两根下挖柱都被流体废掉，退出爬升在流动水里被冲下柱子 8 次。** 代码自己诊断对了也决策对了（`shaft.reColumn.1 = 这一柱中段有水，身体浮起来了…爬回 y=63 换第 2 根柱子重挖`）；吃掉这趟的是退出爬升：`climb.1`…`climb.8` 每次 `placed=1` 就 `washedOff = 水把身体冲下柱子了（流速²=1.00000）`，重试 3→2→1→0 用尽。**要补的读数**：给撞上的水落「天然／上级留／自浇」一行出处，**照 12 级 `water0.spent=water_bucket 1→0` 的问法**；在它有分布之前**两条腿都不修**。⚠️ 别把「起塔在流动水里」和「换柱预算不够」合并成一条修法——它们是两条腿 | 我 |
| 🟠 判：先补测量（收源那笔等点名，2026-08-25） | J52 | **模腔的水顺楼梯流到底。** 仪器半已在 HEAD（`DRAIN_UPSTREAM=8`，`JourneyDrain:102`），但半径 8 的 `drain.N.upstream` **至今零读数**（j57 的 12 级死在取水，没走到排水），「收走被点名的源」没有对象就没有形状 ⇒ **下一趟 12 级先读它**。⚠️ 别把 J70 排进这条因果（实测趟内的水是 `water0` 自浇、`JourneyStairs:250` 收水排在下降之后）。⚠️ 12 级现在**同时**挡在浇筑那一族和这摊水上：`cast8#10` 里它一口气做了五件事——淹掉楼梯底（`stairFoot`）、用 `wouldOpenFluid` 掐断**两次**起塔、让垫台阶的 `placeOn` 拒绝、占住要清的那几格、最后把身体泡在水里 | 我 |
| 🟠 判：做（排练退出后的编译窗口；验收＝ashore 场景翻绿） | J47 | **浮在水面的身体走不上齐平的岸**，而每一行读数都像成功：`dryLand=242843,221` → `end=path-consumed`、停在 `242844,221`、`脚下=water`。**与浮力无关**——干体证人 `wd.journeyWalksOffTheLipOntoTheDryStep`（`inWater=false`、`onGround=true`、脚下 stone）读数逐字相同 ⇒ 是 `within` 的 **1.2 垂直闸**把差一格判成到达、指针被推进。今核 HEAD：`JourneyCast` 的 `standOnDryGround`／`stepOntoTheBank` **仍只补步不垫块**。写死步骤先行（testmod 侧，上岸失败就在脚下垫一块）；引擎侧那半**另立账**，见「拍板」节 | 我 |
| 🟠 判：做（工程债；排练退出后的编译窗口，独立配闸） | J55 | **一个游戏动词住在脚本传输层**：`prelude.js:194` 的 `Driver.bot.tunnel` 是一整个动词（挖 1×2 走廊），只有 in-JVM Rhino 够得着——`DriverApi` 无路由、`ToolCatalog` 无 schema（grep 双零命中）⇒ MCP／RPC 客户端根本调不到。**不能直接删**（`validation/25_phase_d3.js` 在闸里跑它）。提升成真路由并**委托 `GoalResolver.applyDirection`**（`:93`，三者里唯一全集且唯一做归一化），做法四步见「拍板」节 | janitor 报，我判 |
| 🟠 判：做（排练退出后的编译窗口，三笔照定稿次序） | J72 | **验证过的几何和开火的几何不是同一个。** 今核 HEAD 三笔都没落：`aimThatLandsIn` 五个 `continue` 仍静默（`JourneyPour:744-752`）、`planned = aimNow != null ? aimNow : backing` 仍在（`JourneyPortalRung:2401`，settle 后那问也同样 `settled != null ? settled : planned` 回退）、`standToPour`（`:2369`）没有排除集。**先 1/2（零风险仪器与判词），再 3（唯一的行为改动）**，判据三态见「拍板」节 | 我 |
| 🟠 判：做（排练退出后的编译窗口，一行） | J70 | **排水等的时间只有它自称的一半**：`JourneyDrain:73–74` 的 javadoc 写「five legs of forty ticks」，而 `:170` 是 `HoldStill(DRAIN_TICKS / 2)`——五段共 **100 tick**，而 `:140`／`:147` 两行判词印的是 `DRAIN_LEGS * DRAIN_TICKS` = **200 tick**。今核 HEAD 仍是 `/ 2`。**修法**：`HoldStill(DRAIN_TICKS)`，settle 预算给 `DRAIN_TICKS * 2` 留余量。⚠️ 定位收窄：它与 12 级回程死因链**无关**，买回的只是**趟间**残水判读的诚实度 | 我 |
| 🟠 判：先补测量 | J71 | **`forge.carved` 在同一份布景上不是确定性的**，这是「1 绿 / 3 趟」那枚硬币的候选。四份日志里没挖动的每次都是同一格同一层：`carve.firstStuck=2, 62, 17=dirt：身体 4, 64, 17，距 2.8 格，canBreak=true，六邻实心 4/6，手上 stone_pickaxe`。**下一步是量不是修**：连跑 N 趟 **`runRehearsalIntegratedServer`**（⚠️ 必须固定这一种身体，混拓扑的方差有一半来自身体种类）列 `forge.carved` × 结局表。67/67 全绿、66/67 全红 ⇒ 它就是硬币；66/67 也能绿 ⇒ 只是噪声。**表出来之前不动开凿代码** | 我 |
| ❌ 判：不做（仪器已武装，首现即判读） | Q16e | 6 级**间歇**失败（3 趟里 1 趟）。`5d81dee5` 的 `kill.kills`／`kill.swings`／`kill.preyVitals`（`WorldDriverJourneyScenes:1232-1245`，results-ladder14 起成行）已把「谁按了 keyAttack、打了几下、打死没有」按交战归因；此后 6 级连续 PASS。**重开条件**：6 级再次 FAIL **且** `kill.*` 三键在那一趟有行 ⇒ 当场按行归因；**再次 FAIL 而三键零行则是另一件事**（仪器没跑到），先查为什么，别改战斗 | 我 |
| ❌ 判：不做（复发即重开） | Q13 | **垒塔/解卡的取料不看下游需求**：5 级花 14 圆石开井口；6/7/8 级花的是**土**（14 放 18 拒），圆石零消耗。代价已量到零：6+7 级圆石净损 22→22=**0**（上趟 16→7=9），`furnace.topUp=不需要` 两趟同句。**重开条件**：哪趟判词再把红记在取料/补料上即重开 | 我 |
| ❌ 判：不做（动机已消失） | Q14 | 破坏税与真梯的矛盾：`pathfinderLogBreakTax` 3.0 / `pathfinderBreakCostMultiplier` 2.5 二分。动机（3 级砍树被破坏税劝退）已被 J32-A 的定域木税豁免直接解决且带出厂配置真梯验过。**两个乘数照旧全局生效那部分属「两套 WorldView 两张价目表」的账，在 J33 名下** | 我 |
| ❌ 判：不做 | J2 | 到达半径统一成眼→格心，抽进 `BotUtil`，五处指过去。纯预防：五处至今零已量分歧，没有一笔失败记在到达半径不一致名下；且同族旁证 J60-(B)（`JourneyFill:987-988`）说明「格心」这个基准自己还在动。**重开条件**：射线族定型后 janitor 可重提 | janitor |
| ❌ 判：不做 | J3 | `ContactDamageEscape` / `LavaProximityEscape` 升级成 `commandMove`。Q23 读码收窄已写明：反射前五次发作全部成功，败因是进了源块 40 tick 挪不满 1 格（物理上没救），换驱动改不了这个。**重开条件**：哪趟 `death.blow` 把逃生失败归到转向/驱动延迟上再重开 | janitor |
| ❌ 判：不做（关闭） | J4 | `keyAttack` 五取用者协议——只做诊断表，**不要发明全局仲裁器**。诊断表已被 `5d81dee5` 的三键仪器替代，6 级此后连续 PASS。**重开条件**：Q16e 下次发作且归因不清再重开 | janitor |
| ❌ 判：不做（闸已武装，首现即判读） | Q21 | 三次进近逐字相同：`liftInPlace` 的闸是 `y >= wantY`，而失败的是**柱**。闸已拆三支（`b825a2a1` 的 `.liftSkipped.N`／`.liftSideways.N`／`.liftIsHere.N`），在 j48 以来全部归档 results 与 j57 已写部分里 **0 行**。零样本上改重试逻辑没有判据可言。**三键常驻，首现即判** | 我 |
| ✅ 判：结案（不再挡路，**复发即重开**，2026-08-25） | Q22 | 12 级下楼那一段在地表打转 → 落进岩浆湖 `-10,63,19` → 在岩浆里重搜 42 次烧死。代价已被 J44 的三腿坑沿加价买断（j48 真梯 12 级整趟零岩浆死），`death.strideGuard` 常驻。「在岩浆里重搜 42 次」那半账在 Q7c。**重开条件**：哪趟 `death.blow` 再写 lava 即重开 | 我 |
| ❌ 判：不做 | Q27 | 放置前不问目标格是不是空的：整趟 ladder-14 **101 成功／67 拒绝**，其中 63 条（94%）的邻格不是 air。代价本行已量：**0.66 拒绝/成功**，拒绝不消耗物品不写方块，只是簇状重复（单点 ≤12 次）。**重开条件**：等哪趟把失败或预算烧尽归到「重复拒绝」名下再重开 | 我 |
| ❌ 判：不做（闸已武装，首现即判读） | Q26c | `cast.rePickBlocked`（`JourneyCast:250`）在全部归档 results 里 **0 行**，预言的第四态从没出现。**修法方向已写死**：退路要**换问题**（`viaMidpoint`／改走 `walkToColumn`），**不加次数**（[[a-retry-that-changes-nothing]]）。**首现即照此执行** | 我 |
| ❌ 判：不做 | J23 | `ElytraProcess:170`（`!p.isFallFlying()` 中途退出）不戳 `lastError`，而这一个出口同时是「落地了」和「翅膀在半空断了正在下坠」。代价已量：`ElytraProcess` 全工作区唯一消费者是 `wd.serverElytra`（`WorldDriverAvatarScenes:391`），journey 零调用、13–20 级不用鞘翅。**重开条件**：鞘翅进主线时再判 | 我 |
| ❌ 判：不做（重开＝13–20 级开工第一步） | J24 | `JourneyShaft.supportUnder` 用 `rig.ctx().level()`，**latent**：所有调用点现在都在主世界。⚠️ 它和 `JourneyEndRungs.supportUnder` **方法体逐字相同而读的 level 不同**（后者用 `levelOf(rig)`＝身体所在世界，19 级之后不是 `ctx.level()`）——**合并会弄坏末地的级，别顺手合**。重开条件写死为「`JourneyShaft` 的任何方法第一次出现在下界／末地 rung 的调用图里」，那就是 13–20 级开工的第一步（先 grep 调用图），**不是 J33 之后**；详见「拍板」节。⚠️ **这一族不会自己报错**，重开条件必须由人在开工时执行 | janitor |
| ✅ 已判**不是缺陷** | J44b | 12 级 `forge.carved = 66/67`。`carve.firstStuck = 2,62,17=dirt：距 8.5 格，canBreak=false，六邻实心 4/6`——那条走行腿挂 `NoBreak`，因为不挂时它会打穿自己刚挖的楼梯（实测 `forge.stairsBroken=4/11`）；禁掉挖掘的代价也量过：`forge.swung=64/67` 与 `forge.carved=64/67` **相等——走行腿开的格数是零**。⇒ 够不着的格变成 `carve.stuck` 是**明写的兑价**。**重开条件**：只有当某趟因为**这一格**而铸不成模腔时才重开 | 我 |
| ✅ 已修（2026-08-25 核 HEAD；发作场合未再现，首现复读） | J38 | **走到了掉落那一格、站了 30 tick，东西没进包。** 修法已在 HEAD：捡拾空手行带 `walkerEnd`（`JourneyRig:2200`，「走完」和「烧完预算」分得开）且 `MAX_PICKUP_LEGS=3`（`:2214`），`JourneyStation.takeTableWhereItStands:106` 从 1 腿升到 `MAX_PICKUP_LEGS`（`c24608b0`）。只写「已修」不写「已验」。**首现条件**：哪一趟再出 `pickup.empty` 而**相距 < 1.5 格且有空槽**，J38 就有第二半，当场重开（背包满 vs 拾取延迟）；**仪器永久留着** | 我 |
| ✅ 已修（`c24608b0`，队列行过期，2026-08-25 核 HEAD） | J37 | 工作台丢失分支改成 `onGround > 0` ⇒ `collectByHand(…, MAX_PICKUP_LEGS, "craftingTable.lost", …)` 并写 `craftingTable.lostThenFetched`。j47 起 8 级 `keptInBag=1` 走的是健康支，**`lostThenFetched` 零样本——首现即读** | 我 |
| ❌ 判：不做（仪器常驻，复发即重开） | J35 | 一条 `Goal.XZ` 的回家腿为什么会净升 16？嫌疑是解卡塔按「当前高度+8」抬（16 ≈ 两轮）。J34 的关卡级守卫已验，`*.homeElevation` 无条件逐趟落行且 j34/j39/j47 读数 **−1／+0／+0**。**重开条件**：哪趟再读出 \|差\|≥8 当场重开去查解卡塔 | 我 |
| ❌ 判：不做（前提已过期） | Q30 | 追猎全程零行日志。前提已不成立：`5d81dee5` 后有 `kill.swings`／`kill.kills`／`kill.preyVitals`／`kill.onGround`／`kill.combatError`。只剩死亡时刻 tick 戳没有，而 6 级此后连续 PASS。**重开条件**：等归因真卡在时刻上再补 | 我 |
| ❌ 判：不做（重开＝`JourneyRig` ≥ 2900 行） | J42 | `JourneyRig` 的门面缝是真的且干净：`1856–2410` 这一带 27 个方法里 **20 个是纯查询**，不纯的 7 个还**连续**（收集那一族 `2024–2178`）。代价 **192 个调用点、13 个文件**，收益 **0**。重开条件从「逼近 3000」改成数字：`JourneyRig.java` 行数 **≥ 2900**（今 2606/3000）。⚠️ **2026-08-26 复核已经是 2745**，离触发只剩 **155 行**。到那一刻 `1856–2023` + `2179–2410` 这约 400 行就是现成的搬运单，而 `2024–2178` **必须留下**（它要 `settle`）。顺带记着 `JourneyRig:1297-1312` 那处孤儿 javadoc（写的是 `heartbeat`，挂在 `sinceHeartbeat` 上），切缝那一笔顺手改 | janitor 查，topology 决定 |
| ✅ 已落（队列行过期，2026-08-25 核 HEAD） | Q8 | V2 不低头：瞄准从此**经过** `LookController`（`ClientPlayerAvatar.aimAtBlock:52` → `BotInteract.aimAtBlockSnap:365` → `LookController.requestSnap:47`，`apply()` 是 tick 末唯一的回拉写者）。**未兑现的那一半**：V2 那个 **90° 俯角分布没人复量过**，拟真复量归 parity | 我 |

**放行规则**：janitor 的产出**单独编译、单独跑一趟读数**，不要和真梯的变量混在同一趟里。

---

## 🔧 队列表之外还开着的条目

这些在旧版里只有正文小节、没有队列行。**它们和上表一样是开着的活**，别因为表里没有就当它们结了。

### 🔴 J63：崩溃这一族比 J61 宽得多，而 K1 看不见其余的成员

CME 是从 `ClientLevel.playSound` 掀起来的，而 `BlockItem.place` 不是唯一会放声音的客户端调用。
`ClientPlayerAvatar` 逐行读过，**从服务端线程调过来就会写客户端状态**的方法：

| 方法 | 落到哪 | 状态 |
|---|---|---|
| `placeOn` / `useBlock` | `clientUseItemOn` | ✅ **J61 已修**（同一个咽喉） |
| **`useItemInHand`** | `mc.gameMode.useItem(p, MAIN_HAND)` | 🔴 **没守**，而且**这就是桶那条路** |
| `continueDestroy` | `mc.gameMode.continueDestroyBlock` | 🔴 没守；挖方块一样出声音和粒子 |
| `holdItem` / `holdPlaceable` / `holdPillarBlock` / `holdThrowawayPlaceable` | `ensureHolding*` | 🟡 见 J62 |
| `setSelectedSlot` | 直写 `inv.selected` + 发包 | 🟡 没守 |
| `containerClick` / `placeRecipe` | `handleInventoryMouseClick` / `handlePlaceRecipe` | 🟡 没守 |
| `attackEntityUnchecked` | `mc.gameMode.attack` | 🟡 没守 |
| `closeContainer` / `startFallFlying` / `breakHold` / `aimAtBlock` / `selectTool` | 各自写客户端态 | 🟡 待分类 |

⚠️⚠️ **它不能照抄 J61 的「投递完就返回」，原因已经查实。** 绝大多数调用点只把结果写进
`rig.evidence(tag + ".result", …)`（只记不判，随便延后）；`ElytraProcess:306/337` 在客户端线程上走内联支；
**`JourneyFill:786-789` 是例外，而它正是取水那条路**：

```java
int before = rig.carrying("minecraft:lava_bucket");
var result = rig.avatar().useItemInHand();
int after  = rig.carrying("minecraft:lava_bucket");
if (after <= before) { …「第 N 桶没装上」… }
```

它**不判 `result`，判的是紧接着一行读出来的存量差**。一旦这一 use 被延后到客户端线程，
`after` 还没变 ⇒ 恒等于「没装上」。⇒ **J63 的修法必须连调用点一起改**：要么像 ramp 那样引入一次
settle 再读存量，要么让这一步也进入 `Stop.PENDING` 那套。**所以它绝不能和 J61 捆在一起。**

### 🟡 J62（已核实，**本轮不修**）：`holdItem` 是同一族，只是还没轮到它炸

`ClientPlayerAvatar.holdItem` → `BotInteract.ensureHolding`，写的是客户端状态（`inv.selected = s` 裸字段写，
以及 `swapFromMainInv` 里的 `mc.gameMode.handleInventoryMouseClick(...)`）。场景在**服务端线程**上调它
（`JourneyRamp:500,504`、`JourneyHands:282`）。

**不能照抄 J61**：`holdItem` 的返回值是**承重的**——`boolean held = av.holdItem(…)` 决定后面垫不垫，
`ctx.expect(...holdItem(...))` 直接拿它当断言。fire-and-forget 会把它变成谎话，而阻塞等待是被否掉的那条路。
要么改调用方的契约，要么让**拿和用坐同一次投递**。

⚠️ 危害等级低于 J61：写裸 int 字段不会像 `HashMap.put` 那样掀 CME，最坏是读到旧值。
**重开条件**：等它有了自己的场合再动——现在动就是没有测量的改动。

### 🟠 J61 的等级：旧版留下两条互相矛盾的记录，下一趟当场定级

同一份文件里有两句都自称终局的话：一句说 **N3 已验 ⇒ J61 从「已编译」升到「已回测」**
（`journey-n2.log`：`placeEnqueue` 35 行、发起线程全是 `Server thread`、服务端线程上 `[place]` **0** 行），
另一句说 **j57 之后「J61 的等级仍是『已编译』，不是『已回测』」**（j57 的投递 **0** 行 ⇒ 那条分支一次都没被走到）。
[[two-ones-that-disagree]]：两句各自为真比一句假话更难被质疑。

**别去考据，用判据当场定**——下一趟走到 12 级垒台阶时读三态：

- **K1**：全日志里 `[place]` 行**没有任何一行**来自 `[Server thread]`。⚠️ **三态，不是两态**：
  观测到违例＝**证伪**（与场合无关）；`[placeEnqueue]` 也是 0 行＝**未触发**，不是已验。
- **K2**：每一行 `[placeEnqueue]`（点击格,面）都能在其后配到一行 `[place]`（点击格,面），**未配对数 = 0**，
  且 `[placeEnqueue]` > 0。
- **K1／K2 要等 12 级才有场合**（跑到 7 级时实测：Render 122 行、Server 0 行、投递 0 行）。

⚠️ **K1 干净只证明「放置那条路换了线程」，不证明整族关掉了**——桶装水不打 `[place]` 行、挖方块也不打，
那些成员在 J63 名下。

### 🔴 J60-B：座位按「会开火的那只眼」判——**已落地又被撤回，撤回保留**

`d2f261f2`（`JourneyFill.eyesFor` / `eyeBlocked`：身体已经站着的那一格问**真眼**，要走过去的格要求
**四角＋中心全都看得见**，外套严格→回退）曾落地，随后在一次 A/B 里被判为看门狗死因而撤回（`db6ecbce`）。
后来那个 A/B 结论**自己也被撤回**（对照臂不等价于绿参照，差分没有解释力，真机制是 J65），
但原文写死：**`db6ecbce` 的撤回照旧保留**（撤回一个没验证过的改动不需要理由）。

⇒ **现状：座位判据仍是格心眼**，而缺陷本身是实测过的：真眼 `(-3.60, 63.62, 55.70)` 被 `(-5,62,55)` 挡住
（进入 t=0.5536，**面=up，距离 1.04 格**），格心眼 `(-3.50, 63.62, 55.50)` **视线通畅**；日志实测
`空桶线 -5, 62, 55 minecraft:grass_block 面=up（1.04 格）`——**格、面、距离三个数全中**。
仪器那半（`waterFill.reseat.eye`，j57 读到 `水平差 0.22 格`，预测值 ≤ hypot(0.2,0.2)=0.283）还在。

⚠️ 最小版**不需要预算闸**：`J60-C`（`f1802c47`）已经证明这一族可以「**换原点，不是加射线**」——
射线条数不变，只把原点换成真眼，四个同形调用点（`JourneyFill:352`／`:1017`、`JourneyPortalRung:253`、
`JourneyPour:472`）共用一个取眼助手。`JourneySight:158` **不属于这一族**（它带 `x`／`lift` 参数，
是**故意**的偏移采样器）。

### 🔴 J58（仪器缺陷）：`gained N/N` 可以在身体悬空、且不在指定柱上时为真

```
cast8#10.gained  = 6/6 block(s)      ← 读起来像「垒成了」
cast8#10.endedIn = -7,22（起塔柱是 3,20 —— 不是同一柱）
cast8#10.endedOn = 脚格=Block{minecraft:air}，脚下=Block{minecraft:air}
```

`gained` 量的是**高度差**，不是「站到了指定柱上」。⇒ **`gained` 永远不许单独当成功判据**；
判成功必须同时要求 `endedIn == 指定柱` 且 `endedOn` 脚下是固体。
**下一步**：让 `climb` 的收尾自己把这三者合成一个判词，而不是留给读者去交叉比对。

### 🟠 K4 的仪器缺陷（等 j57 跑完的那个编译窗口）

`standToFill` 是两趟：

```java
FillSpot nearSide = standToFill(…, new LinkedHashMap<>(), true);   // 948：丢掉的 map
if (nearSide != null) return nearSide;                              // 949
return standToFill(…, why, false);                                  // 950：真 map
```

而 `avoidCrossing` **全文件只用在一处**（第 1013 行）且被 `&& lava` 挡着 ⇒ **对「水」来说两趟逐字节等价**，
只要存在任何落脚点第 949 行就返回，**真 `why` 永远是空的**。而 12 级的失败形态恰恰是
「找到了，就是脚下这一格」——属于找到了。
⇒ **修法**：让第一趟也用一张真 map，谁答的就把谁的并进 `why`，并在证据里点名**是哪一趟答的**；
改完 `standToFill`（932-935 行）那段 javadoc 的警告就不用留了。

### ⬜ J73：烈焰人露天轮的坠落长尾

**状态**：调研✅ 策略✅ 实现✅ 评估✅ **回测⬜（Fabric 闸补跑） 认证⬜**

守卫已落并单跑验过（`j73-fall.log`，`-Pstagewright.scenes=wd.serverBlazeFightStopsWhenTheBodyFallsOut`，
7 ticks / 1017 ms）：A `fellAt=245`、B 收轮 **恰好 60 次**迭代、C 单个 pump **89.7 ms**（无界版单次迭代 2600–3060 ms）。
**欠的是一趟 Fabric 全量闸**——上一趟正是被这条长尾在第 176/325 幕掐断的。
⚠️ **闸补跑只为收 Fabric 的 ① 状态，对修法本身零证明力**（守卫在健康趟永远不触发）。
⚠️ `fell.postFallGate` 那份就地普查（bucket 9 = 0）**不能拿去佐证 J74**：取证段跑在翻成真预算（6 ms 分片）之后，
采样的是另一个 regime。

### ⬜ J74：追一个会动的目标时，徒劳搜索闸**结构性地不可能开火**（bucket 9）

**状态**：调研✅ **策略⬜ 实现⬜ 评估⬜ 回测⬜ 认证⬜** ——**engine 侧，按 engine-last 排队**

`Walker.setGoal` 抹掉 `searchGov`，抹掉之后的第一次搜索走 bucket 9「复位后播种(不判)」——不判就不进连续计数。
目标每移动一格 `CombatProcess` 就重下一次目标，于是 240 次搜索里恰好一半是播种，连续计数**永远攒不到 5**。
⇒ 闸的判据（连续 5 次无进展）与它要防的那个场景**互斥**，这不是「闸没调好」，是**判据本身在这一族上是恒假式**。

答卷（`gate-j69e-neoforge`，`wd.serverFutileGateUnderACreepingGoal`，唯一自变量是有没有走 `Walker.setGoal`）：

| 臂 | 下目标次数 | 搜索数 | 计入 | 复位后播种(不判) | 终局 |
|---|---|---|---|---|---|
| `still` | 1 | 9 | 2 | 1 | 无 |
| `creepRetarget`（重定向，不走 setGoal） | 120 | 6 | 5 | 1 | **t=89 开火**：`no route progress after 5 consecutive searches — goal unreachable from here (best dist=1530)` |
| `creepSetGoal`（每次都走 setGoal） | 120 | **240** | 120 | **120** | **全程没有终局**，收在 `WALKING` |

**策略候选（先不实现）**：① `setGoal` 在目标只挪动、语义未变时保留 `searchGov`（要一个「同一个追击」的身份判据）；
② 连续计数改成按**时间窗**而非按搜索次数，复位不清零；③ 在 `CombatProcess` 侧节流重下目标的频率——
**这条是 testmod 之外的行为改动，最不该先做**。选型之前先把 `searchGov` 的**所有写者** grep 全。

⚠️ **别做的事**：不要因为 J73 的守卫落地、闸不再红，就把这一条当成已解决。J73 治的是「烈焰人场景会不会
砍掉整趟闸」，J74 治的是「闸本身是不是恒假」——两件事。
⚠️ **J74 的证据只认 `wd.serverFutileGateUnderACreepingGoal`**，不认 J73 那份就地普查。

### 🟡 J68c（只登记，不追）：「不用修楼梯」这条捷径只比了 y，没比柱

```
water8.lift=2, 64, 23 → 3, 60, 20（走不到选定的落脚格，修一段楼梯上到和 4, 61, 20 同高）
water8.lift.flightSkipped=2, 64, 23 已经到了落点那一排或更高（落点 3, 60, 20，exactRow=false）—— 不用修楼梯
water8.liftedY=64/60
```

身体在 `2,64,23`——**比落点高 4 排，且根本不在那一柱**。`buildTo` 的提前返回只问
`here.getY() >= landing.getY()`；它的 javadoc 明写这个 `>=` 是**故意**的，但那句话对**同一柱**成立，
对「高 4 排且隔着 3 格」不成立。
**重开条件**：身体之所以在 `2,64,23`，是 J68b 那次失败的 goto 把它送上了地表；**J68b 修好之后
再看这个场合还在不在**，别现在同时改两处。

### ⚠️ 额度与两条「别顺手清理」

- ✅ `JourneyPortalRung.java` **2990 → 2138**（`821d811e`，janitor 拆出 `JourneyStairwell.java` 912 行，
  余量 10 → **862**）。机械搬运：七个调用计数搬前搬后相等（`rig.evidence(` 91→91、`ctx.fail(` 24→24），
  **证据键一个字没动**。我跑过 `./gradlew build` → BUILD SUCCESSFUL，源预算闸 OK。
- 🔴 **新头条：`common/src/main/.../bot/BotConfig.java` 2993/3000，余 7 行**——比拆之前的
  `JourneyPortalRung` 还紧，而且是**产品代码**，要 gate 槽。
  janitor 指的切口（`:2645` 往后自成一体的反射持久化层）方向对，但**它不知道下面这条**：

  ⚠️ **拆它的第一步不是动字段，是先让 `BotConfig.persistableFields()` 走父类链。**
  这个文件里有两个枚举器，各自的 javadoc 都写着「**the ONE enumeration**」，**各自为真**：
  `SettingsRegistry.reflectivePrimitiveFields()` 用 `getFields()`（**跟**父类），
  `BotConfig.persistableFields()` 用 `getDeclaredFields()`（**不跟**）。
  于是拆法决定病征：**兄弟类拆会当场抛 `IllegalStateException`（响的，安全）；
  父类链拆是静默的**——被搬走的字段仍在 settings 快照里（读起来一切正常），
  却掉出持久化、掉出 `snapshotAll()`，`applyGameTestBaseline()` 的 OFF 基线泄进活着的 bot，
  **只泄被搬走的那几个 flag**，病征长得像「某几级莫名其妙退化」。
  ⇒ 先让 `persistableFields()` 走父类链：**今天做这一步是可证明的 no-op**（父类是 `Object`），
  于是两步各自可验，第一步闸必须仍绿，第二步才动字段（[[two-ones-that-disagree]]）。
- **后面依次撞线**（janitor 量的）：`Walker.java` 2960、`JourneyNetherRungs.java` 2927、
  `JourneyEndRungs.java` 2844、`WorldDriverJourneyScenes.java` 2819、`JourneyRig.java` 2801。
  ⚠️ **到时候不要刮注释换额度**（[[a-file-pinned-at-its-budget]]）。
- **`BuildProcess` / `BackfillProcess` 的私有 `canStand` 不许并进 `BotUtil.canStandHereStatic`。**
  两个私有拷贝彼此逐字相同，但**比共享版更严**——少了两条 water 子句，含水格在共享版**可站**、
  在它们这里**被拒**。合并＝把两条放置路径**放松**。要动先测量。
- **「Java 侧零调用者」不等于死代码。** `ScriptClassFilter` 是**默认关闭的 deny-list**，
  且**不 deny `net.magicterra.worlddriver.*`** ⇒ 任何 public 成员原则上都能被运行时 JS 按名字调到；
  另有 `SettingsRegistry` / `SettingsCommand` / `BotConfig` 三处**按字段名反射** `BotConfig`。
  死代码侦察给的「确定级」条目**采用前每一项都要按这两条重验**。
- **janitor 2026-08-26 那轮的余项**（都已核实，等编译窗口）：
  - **10 处死代码**，全部排除了 `-D` 属性与 `JourneyRehearsal` 布线可达：
    `JourneyRig` 的 `drivesRealPlayer()`／`diedOf()`／`drivingWhenLost()`（三个零调用者的访问器，
    后两个的**字段**是活的）、`JourneyLedger.startedAtTick()`、`JourneyStage.chapter()`
    （删访问器后字段变只写）、`JourneyRoute` 的 `spawnBiome`／`firstCoal`／`ruinedPortal`
    （后两个看着有引用，其实是 `out.put("firstCoal", …)` 的**字符串 key**，字段读取数 0）、
    `JourneyWorkableSpotScenes.SHORE_NEAR`（兄弟 `SHORE_FAR` 活着，所以是真孤儿）、
    `JourneyRoute.surveyNetherFortress(SceneContext, BlockPos)`。
    ⚠️ 最后那个 janitor 亲自核过：**不是「坐标转换被绕过」的缺陷**——唯一活着的调用点
    （`JourneyNetherRungs:753`）的身体本来就站在下界，`…From` 的 javadoc 说的正是这种调用者。
    它只是没人用的重载入口，三处 `{@link}` 撑着它。
  - **`DescendProcess.done()` 与 `EscapeProcess.done()` 在同一个 slot 上收尾方式不同**：
    Escape 走 `s.reset()` 并在失败时打 `dbg("BAIL: …")`，Descend 只写 `s.active = false`。
    ⇒ 一趟 descend 结束后 `mc.bot.state` 的 escape 槽仍报着 `goal="descend to y=…"`、旧 `target`、
    旧 `startedAtMs`，且 **descend 的失败一行日志都不写**（零行日志有两种解释——
    [[an-instrument-behind-a-flag-is-not-an-instrument]] 同族）。
    共享 slot 本身是文档化的设计（`DescendProcess:36-37`），**不是缺陷**；对齐 `done()` 会改
    `mc.bot.state` 的可观测面 ⇒ **行为改动，要 gate 槽**。
  - `isFalling(Level, BlockPos)` 三份逐字相同的私有拷贝（`BunkerProcess:85`／`DescendProcess:268`／
    `EscapeProcess:379`）。⚠️ **不能合进 `WorldView.isFallingBlock`**——那条走 view，
    可能是另一个维度的读数（`placeInto` 的前科）。合成共享静态方法安全但价值低（3 行 × 3）。
  - `CraftProcess:414` / `SmeltProcess:512` 的 `fail(...)` 各有一个从没被用的 `BotState s` 形参。
  - `JourneyRig.java:1070` 注释里的 `(JourneyRig:1409)` 引用已腐——1409 行现在是 walker trace 调试文案，
    与 `BotConfig.allowBreak` 无关。janitor 点名交还（那是 topology 产权）。
  - janitor **确认过不是问题、下轮别重查的**：`walkToColumn` 的 5 个重载是干净的「4 委托 + 1 实现」链
    （且再加 `List` 形参会**擦除冲突**）；`JourneyFill:325` 的 rim/NoBreak 不对称有措辞写明的理由；
    `DescendProcess.kind()` 返回 `"escape"` 是文档化的共享槽；**不做 NoBreak 工厂**
    （工厂拦不住新调用点漏写，真要防得写断言约束的场景，接住它的地方是 `JourneyStairs.faults`）；
    ~150 个「零调用」是假阳性，它们靠**方法引用**注册（`WorldDriverJourneyScenes::wood`），
    任何只数 `name(` 的扫描都会误报（[[a-verification-tool-needs-verifying-too]]）。
- **更早报出、都需要编译器的余项**：`prelude.js` 的 `\| 0` 取整与 `Params.toInt`／`SchemaValidator`
  分叉（脚本通道吞 `2.7`／`"8"`／回绕，MCP/RPC 会拒——**行为变更，必须配闸**，排在 ROADMAP §6.5 序 17）；
  `neoforge.sim` 三个 shim 整体可删（包外零 import）——⚠️ **别写成纯删**：`neoforge.sim` 里
  `ServerAvatarCommand`（`/agentserver`）还站在它们后面，删除要连命令一起判。

---

## ⚖️ 拍板（2026-08-25 夜）

这一节把所有还开着的判断收成判词。每条四样：**判词**（做／不做／先补测量／排到 X 之后）、
**凭什么**（行号、证据键、量出来的数）、**重开条件**（可证伪，写死到证据键）、**代价**（判错了以什么形式暴露）。

**执行顺序不在这里** —— 见 [`ROADMAP.md` §6](ROADMAP.md)（窗口 0–4）。

### 🅶 仓库瘦身

✅ **已做：`git repack -adf --window=250 --depth=250` → 342 MB → 17 MB**（11.8 秒）。
松散对象 4471→57（305.57 MiB→1.14 MiB）。无损：ref 12/12、提交 2184/2184、HEAD 不变、
`fsck` 退出 0。备份 `../worlddriver-before-repack.bundle`（15.8 MB）。
**没删历史、没改哈希，105 个提交引用一个都不用动。**

⛔ 病根不是历史脏，是**高频往一个大文本文件提交** + git 的 auto-gc 阈值（6700 个松散对象）没到。
`TODO.md` 12 小时内 102 次提交 ⇒ 每次一个新 blob，攒到 4471 个。
📌 **要么定期 `git gc`，要么调低 `gc.auto`；更要紧的是别再往 TODO 里写判读日志。**

⬜ **剩下的 `filter-repo`（清 `config/`／`__pycache__`／`*/bin/`）暂缓** ——
只多省约 3 MB（占现在的 17 MB 的 18%），代价却是重写 **105 个**提交引用
（`commit-map` 里「哈希没变」的 0 条，全要改）。**收益/代价比不成立，等真要做时再说。**
真要做：用 `--filename-callback` 把 `REGRESSION.md` 改道到 `docs/`（直剪会丢 59 条只改它的研究提交），
先停两个 cron、确认无游戏进程、`commit-map` 存到会话目录之外、改完第一件事是换本文件顶部的
`fb94af03` 指针。

### 🅱️ 甲之二（`JourneyPortalRung:906/942` 的早退采样）

**判词：不做**（维持缓做）。

**凭什么**：`ascendByTowering` 是递归循环，穿一个中止谓词要动 **9 个调用点**
（`:245/:475/:603/:618/:636/:726/:780/:816/:820/:871`）；乙 已经把预算杀手（塔在被喂的水里重试 8 次）掐掉，
`.washedOff` 重试行归零；甲之一 移除了本死链里唯一被观察到的触发（`returnedY=58`）。
此后跑过的每一趟都判过它，**四次全部不命中**（17257 / 16191 / 12324 / 18011 tick 四趟）。

**重开条件**（原样保留，不放宽）：任何一趟里 `returnStuck` 开火，**且**同一时刻有
`climb.*.driftOntoTheFlight` 行、或一条 y≤57 的 `climb.*.drift` 行。
命中就按写死的形状做（`climbPinned`/`climbName` 那族 climb 级静态字段，在 `climbFrom` 里复位，
**且场景两臂断言必须取不同值**，[[a-scene-that-owns-a-global]]）。

**代价**：判错的症状＝一趟里 `returnStuck` 多次开火而 `climb.*` 的 y 一直在 57 附近打转、
预算被两个恢复周期吃光——那正是重开条件描述的那一行，所以判错会自己暴露。
另一半代价是**额度**：9 个签名穿谓词现在物理上也放不下。

### 🅲 丙（`JourneyStairs.java:385` 点名的「reclaim it」——水源的存活窗口）

**判词：先补测量。**

**这条修法是什么形状**（读 `JourneyStairs:250` ＋ `JourneyPortalRung.castOpenedCell` 得出的读法，**不是已定稿的设计**）：
`castOpenedCell:2135` 先 `placeFluid(…WATER_BUCKET…)` 把水放进 `wet`，`:2216-2226` 才判「包里有没有岩浆」——
没有就上楼取、再下楼、然后 `:2163` 才浇岩浆 ⇒ **水在整趟往返里一直是活的**。
所谓「回收顺序」＝把水源的存活窗口从**几千 tick 的往返**缩到**浇筑那几十 tick**（取到岩浆之后再放水）。
⚠️ **实现有一个现成的坑**：`loadBuckets` 会把包里每一只桶灌满岩浆，水留在桶里过这一段会不会被它当空桶吃掉，
**动手前必须先查**。

**凭什么改判**：`304373fc` 那趟（两道禁挖闸都关掉之后，18011 tick FAIL）把水直接读成了死因：

```
stairsBroken 自检 38 次，报坏 0 次（20 次「11 级都完好」，18 次「一格不缺但泡着水」）
cast8.stairFoot         = ⚠ 楼梯底积水：2,56,20=water …… 只能等它退
cast8.raiseOffTheFlight = 只有楼梯那一柱 2,20 验得过射线，别无选择 —— 抬升多半会被冲下来
cast8.raiseTo.arrivedY  = 57（起 57，净升 0），脚下=water[level=8]
cast8.liftedY           = 67/59        ← 要 59，垒到了 67（解卡塔按「当前高度 +8」把身体甩上地表）
```

但命中的是「**淹**」不是「丙 是解」：**回收顺序错**与**径流没人管**都解释得通而修法相反。

⚠️ **窗口 0 的那一步已经跑过一次，两个候选故事一起被否掉了**——把 18011 tick 那趟按趟排开：

```
forge 干 / lava0 0 泡水
cast0…cast7  ⚠积水 1 泡水  →  drain.0…drain.6 全部「干」
lava8 0 泡水
cast8  ⚠积水 **2 泡水**  →  （没有 drain.8，本级死在这里）
```

**排水 8/8 全成**：每一趟浇筑都会把楼梯底泡上，每一趟排水都清干净，下一趟 `lava*` 自检读回 0 泡水。
⇒ 「回收顺序错」（原 丙）和「径流没人管」**同时作废**。

**cast8 到底哪里不一样：两件各自正常的事撞在一起。** ① 水多了一格——前八趟只泡 `2,56,20`（`流 level=7`），
cast8 泡的是 `2,56,20` **和** `2,57,20`，两格都是 `流 level=8`（满流）；② 可选柱子只剩那一根
（`cast8.raiseOffTheFlight`）。塔必须垒在唯一验得过的柱子上，而那一柱这一趟恰好泡进了第二格。

**⇒ 还欠的那一个量（零行为改动，归 ROADMAP §6.2 仪器批）**：**cast8 的水为什么比前八趟深一格**——
是它的目标格更高、径流更远，还是 `drain.7` 留了底。`drain.N` 现在只写「干」，
**不写清掉了几格、花了多少 tick**。补这一行。

**重开／收案条件**：

| 读到什么 | 判什么 |
|---|---|
| `lava{i}` 干、`cast{i}` 湿 | **源在整趟往返里活着** ⇒ 丙（回收顺序）就是解，提到队首 |
| `lava{i+1}` 仍湿（即 `recover{i}`＋`drain.{i}` 之后还湿） | 收水没收干净／上游另有来源 ⇒ **径流那一族**，丙 不是解 |
| 泡水行 `其中源块 = 0`（全是流水） | 活源不在这一带 ⇒ 先读 `drain.*.upstream`（`DRAIN_UPSTREAM=8`，`JourneyDrain:102`）再判 |
| 泡水行有 `(源)` 且落在壁龛里 | 有活源，接着问它是 `water{i}` 自浇的还是上一趟残留的 |

⚠️ 它出结果之前，丙 的修法形状（把 `placeFluid(WATER_BUCKET)` 挪到取到岩浆之后）**不许动手**。

**代价**：选错族就会去改一段与积水无关的代码，而下一趟仍然 `raiseTo.arrivedY 净升 0`、
`liftedY` 把身体甩上地表。那个签名很响，不会静默。

### 🆕 J75：末影人有些仗**根本不结束**（NeoForge，`wd.serverEarnsAnEnderPearl`）

**判词：先补测量**，落在**工程窗口的仪器批**里，**不占排练/真梯的槽**——它不在 12 级路径上。

**凭什么**（全是量出来的，四趟 24 场）：

- **掉率是确定性的**：`perFight` 在 Fabric 闸和 NeoForge 单跑 3 上**逐字相同** `0,0,1,1,1,1`
  ——**按击杀序**：前两次击杀掉 0，之后每次掉 1，五个样本全部吻合。⇒ `pearls = killed − 2`，
  判据 `pearls ≥ 1` 已**等价于「六场至少打死三场」**。
- **唯一的自由变量是 `fights.slow`**：取值 4 / 2 / 2 / 0（NeoForge），Fabric 唯一样本 0。
- **形状是二值的**：每场要么 66–150 tick 打完，要么**恰好烧满 4000**，24 场里中间值**一个都没有**
  ⇒ 这不是「打得慢」，是**卡死**。45 倍的差不是噪声。
- 与浇筑那一批**无关**：执行次序在前（NeoForge 日志第 25812 行 vs 三个新场景 38851/38908/39064），
  且这一批改动全在 `src/testmod`；同一份代码单跑 3 拿到 6/6。

**要补的读数：一行判两个相位，不用先挑。** 每一场**烧满上限**的仗落一行 `enderman.stall.<场次>`，带四个字段——
**目标实体还活着吗、身体到它多远、这一场里目标位置的最大跳变（瞬移的签名）、这一场里
`[pathfinder] search-begin owner=combat` 的条数**。第四项**不需要新引擎钩子**。
判法：目标死了/不在了 ⇒ 目标丢失族；目标活着且近而搜索条数暴涨 ⇒ 重搜族；两者都不是 ⇒ 第三族。

**判据不准再动。** 历史上判据被从 4 杀降到 2 杀（`CHANGELOG.md:390`）——那降的是**症状的阈值**，不是病。
现在它已经等价于「六场至少打死三场」，**这是它能容忍的下限**，再降就是把仪器关掉。

**重开／收案条件**：`enderman.stall.*` 出现第一行即判读，按上面三族分派。
若**连跑五趟 NeoForge 单场都拿到 `fights.slow=0`**（即一行 `enderman.stall.*` 也产不出），
则「4000 是卡死」这个前提被证伪，回头重读 24 场那张表是不是混了拓扑。

**代价**：判错的暴露形式是——NeoForge 全量闸继续以约 **1/4** 的概率红在这一条必需项上。
这是可承受的税，但它是税不是零。反方向判错（现在就动战斗代码）要改 `src/main`、要双 loader 闸，
而且零样本上改重试逻辑没有判据可言。

### 🔒 模腔内五条还没带 `NoBreak` 的腿

**判词：不做（等证据），五条一条不加。**

**凭什么**：今天已给两条腿定罪加闸，两条都有**日志级三重证据**（`JourneyPortalRung:2422`、`JourneyPour:120`）。
**这五条一条证据都没有**，而处置办法在 2422 那笔定罪时就写下了：
「其余按『哪条腿的死因再指向楼梯就给哪条加闸』处理，不做无证据的批量加固」。

三条不加的理由：① **无证据**——全包 65 条 `new Intent(`、53 条没闸，绝大多数在地表/下界/末地行军，那里挖是**正当的**；
② **加闸有实测代价面**（2422 那笔预登记留了**态 E**：浇筑数下降／`tries` 用光／`fromHere` 变多 ⇒ 加价让路贵到走不完。
它这次读到的是态 D（零代价），但那是**那一条腿**的读数，尤其 `:1007` 是**从坑里往外爬的恢复腿**，
禁挖可能让它根本没有路）；③ **额度**。

**逐条的定罪签名**（下一次读日志时按这张表对，对上就当场加闸）：

| 腿 | 目标形状 | settle 预算 → 心跳分母（框架 +100） | 会不会碰楼梯 | 今判 |
|---|---|---|---|---|
| `JourneyPortalRung:798` `digStairsDown` 迈进刚切开的那一级 | `Goal.Block(foot)` | 300 → **400** | **会**：目标就是梯级本身，而它同时是唯一一条身体正站在梯上的腿 | 等证据（最可疑，但一格远的腿挖出捷径的机会最小） |
| `JourneyPortalRung:1007` `returnStuck` 后走回楼梯口 | `Goal.Block(stairTop)` | 2000 → **2100** | **会**：起点在坑里、终点在梯顶 | 等证据（**加闸风险最高**，见理由 ②） |
| `JourneyPortalRung:2963` `strike` 走向火塘点火 | `Goal.Near(hearth, 3)` | 1500 → **1600** | 会，但**代价近似为零**（十格全浇成之后跑，点着门就结束） | 不做 |
| `JourneyFill:1180` `scoopWater` 的进近 | `reseats == SCOOP_RESEATS` 时 `Goal.Near(water,2)`；重座时 `Goal.Block(身体自己那一格)` | 2000 → **2100** | 重座那一支**几何上不可能挖** | 不做（重座支已被几何排除；首进近支等证据） |
| `JourneyFill:1239` `waterFill.reseat` 走去新座位 | `Goal.Block(seat)` | 2000 → **2100** | 会，座位是水边落脚点 | 等证据 |

⚠️ `:1007`／`:1180`／`:1239` 三条心跳分母都是 **2100**，光看预算分不开，
**必须配 `goal=` 那一段和证据键前缀**（`cast*.backToMouth` / `waterFill.*` / `recover*`）才算三重对上。

**重开条件**（可证伪）：任何一趟里同时读到——① `*.stairsBroken` 报出至少一级 `脚下=air` 的 fault；
② 日志里有 `[dig] cell=` 落在梯级支撑那条对角线上；③ 那些 `[dig]` 所属段的 `search-begin owner=` ＋
心跳分母 ＋ `goal=` 与上表某一行对上。**三条齐了就给那一条加 `NoBreak`，只加对上的那一条。**

**代价**：判错（其实该现在全加）的暴露形式就是重开条件里那三行，而这一族已连续两趟被这套签名当场抓到
（`stairsBroken` 自 `:307` 起**无条件**写，健康趟也说话，所以「没有行」不再等于「没检查」）。
反方向判错（不该加而批量加了）**不会有任何一行说出来**——恢复腿走不到只会表现成超时，判词会指向别处。
这条不对称正是「等证据」的理由。

### 🅹 J47（浮体走不上齐平岸／指针推进）＋ J39 挂账

**判词：做**（写死步骤先行，testmod 侧；引擎侧那半**另立账**）。**J39：排到 J47 之后。**

**凭什么**：修法尚未落（今核 HEAD，`standToDryGround`／`stepOntoTheBank` 仍只补步不垫块）；
机制已查清且有**两个身体种类的证人**（浮体 ashore ＋ 干体唇场景，读数逐字相同）⇒ **与浮力无关**。

**验收（不放宽）**：只认 `wd.journeyGetsAshoreBeforePouring` 从 `fail(optional)` 翻绿。
**不认唇场景**——它已被 J47c 的绕行（开腿前 `aimBoth` 转身）修成 PASS，现在验证的是**那个绕行**，
`within` 的垂直闸与指针推进**原样未动**，拿它的绿给 J47 结案是假结案（[[a-verdict-has-upstream-verdicts]]）。

**⚠️ 代价，也是这一板真正要写下的那句**：**垫一块会让 ashore 翻绿，从而退掉引擎缺陷的最后一个常驻证人。**
唇场景在 J47c 之后已经不再作证；ashore 一旦绿，`within`＋指针推进这条引擎缺陷就**零证人**在跑。所以：

> **引擎侧那半（`within` 的 1.2 垂直闸 ＋ 逐节点到达指针推进）在「引擎批」名下自带独立验收，
> 与 J33 同批。ashore 翻绿 ≠ 这一笔结案。** 这一板只买这一句账，引擎侧的场景现在**不设计**
> （零证据上设计场景是另一种「只能通过的判据」）。

**重开条件**（给「做」也写一条，因为写死步骤可能不生效）：ashore 场景落了垫一块之后**仍红**，
且失败行仍是 `end=path-consumed`＋停在目标邻格 ⇒ **写死步骤不管用**，
直接把引擎侧那半提到队首，不要再往写死步骤上加轮子。

**J47c 的边界（别再拿它的绿去解释客户端那边的红）**：客户端排练实测三次尝试，开腿前 yaw 已经是
**−91.1° / −90.3° / −90.6°**，而末路点方位角**就是 −90°**，`aimBoth` 只改了 1.5°／0.3°／0.6°
⇒ **J47c 在客户端身体上是 no-op**。假玩家那边开腿前 yaw 是 `0.0°`（偏 90°），修法在那里成立且验过两次。
**同一个症状，两具身体两套机制；一份绿不能迁移，一份修法也不能。**

### 🅿️ 布景阶段那 9 条 `[place] 拒绝` @ `-17,63,13`

**判词：不做。**

**凭什么**：① **发生在布景阶段，不在计分区**（排练把身体抬到起始位姿的那一段，判词、证据键、级别结局都不经过它）；
② **它没买走任何东西**——拒绝不消耗物品、不写方块（Q27 已量：0.66 拒绝/成功，只在个别格上成簇）；
③ **塔照样垒起来了**——同一段里身体 y 从 **65.024 升到 66.166**，而被拒那一格的邻格 `-17,64,13`
**已经是自己刚放的圆石** ⇒ 放置这件事**成了**，被拒的只是一个**过期的点击格**。代价＝9 tick。

⚠️ **这条是在没有决定性证据的情况下拍的那一半**：**没有**一行读数说这 9 条拒绝让布景多花了多少 tick，
也没有一行说它在计分区复现过。

**重开条件**（可证伪）：任一趟里出现 **同格 ≥5 条同秒 `[place] 拒绝`**，**且**同一段落写出
`没垒成` / `walkerFallback=true` / `climb.*.afloat` 之一——即拒绝簇第一次与一个失败结局同段。
那时修法方向已经写好：**点击格随身体重算**，而不是沿用开腿时那一格。

**代价**：判错的暴露形式是布景阶段本身超时或把身体摆在错位姿上，症状是 `staged.*` 那一族前提断言红
（而不是级别判词红）——**红在布景不红在被测对象**，不会被误读成产码缺陷。

### 📋 队列里剩下的 📌／📐 行，一并拍板

**J24（`JourneyShaft.supportUnder` 读 `rig.ctx().level()`，latent）：不做（现在）。** 见队列表 J24 行。
代价：判错的症状＝身体在下界踩空而 `supportUnder` 说得通——**这一族不会自己报错**，
所以重开条件必须由人在开工时执行，写在这里就是那份提醒。

**J41 尾（除头条外的 12 个爬升入口）：不做。** 见队列表 J41 行。
代价：判错时死因链会缺一环，症状是判词只能说「爬完了然后就死了」——与 J41 头条今天的形状一模一样，可识别。

**J42（`JourneyRig` 的门面缝）：不做。** 见队列表 J42 行。
代价：判错＝某天这个文件撞上 3000 行预算闸而没有现成搬运单，症状是 `check_source_budget.py` 直接判红
——**硬闸，不会静默**。

**Q16e（6 级间歇失败，3 趟里 1 趟）：不做（仪器已武装，首现即判读）。** 见队列表 Q16e 行。
代价：判错＝这一级继续以约 1/3 的概率吃掉一趟真梯（约 40 分钟）；这笔税已量过，
而它的替代方案是在没有发作样本的情况下改近战，那更贵。

**Q12b（新增「真世界」拓扑）：不做（维持推迟）。** 见队列表 Q12b 行。
代价：判错＝出厂配置下的刷怪相关缺陷推迟到 13 级以后才暴露，症状是下界/末地某级首跑就死于敌对生物
——那时它会被误记成那一级的新缺陷。

### 🔧 J55 的做法（等编译器空出来就照着做，**顺序不能反**）

1. `ToolCatalog` / `BotTools` 加 `mc.bot.tunnel` 的 schema：`direction`（enum 用 `applyDirection` 的全集，
   **含 `up`/`down`**）、`distance` **1..64 必填**、`width` 1..8 默认 1、`height` 1..8 默认 2、`fill` 可选方块 id。
   ⚠️ **`distance` 必填这一点不能丢**——`prelude.js:198` 有一整段注释解释为什么不能靠 `Math.max(1,…)` 兜底
   （会把缺失的距离悄悄变成挖一格）。
2. `DriverApi.route` 加 `mc.bot.tunnel`，实现搬进 `bot/` 侧，走廊 bbox 用 `applyDirection` 算方向，
   **yaw 取实时的 `p.getYRot()`**，不要再读 `mc.observe.player` 的快照（那是滞后一 tick 的根源）。
3. `prelude.js` 的 `tunnel` 改成**薄转发**：`Driver.invoke('mc.bot.tunnel', opts)`。
   保留函数名和返回形状，`validation/25_phase_d3.js` 才不用改。
4. 跑双 loader 闸 + 脚本验证套件。**判据**：`25_phase_d3.js` 那两条（缺 `distance` 报错、未知方向报错）
   必须仍然过，而且现在要**多一条**：MCP/RPC 侧调得到 `mc.bot.tunnel`。

⚠️ **不要顺手统一 `back`/`backward` 那两个 enum。** 那是独立一件事，需要「一个 enum＋一个接受集＋
一条验证脚本」，混进这一笔会让闸出问题时分不清是哪半边。
（三套方向词表的接受集对照：`applyDirection`（`GoalResolver:93`）是唯一全集且唯一做归一化；
`resolveCardinalDirection`（`:177`）少 `up`/`down`／`backward`／`ahead`；`prelude.js` 少 `backward`／`ahead`。）

### 🔧 J72 的三笔（照定稿次序）与判据

1. **给静默的 `null` 分支补一行。** `aimThatLandsIn` 的五个 `continue` 一行不写
   （`JourneyPour:744-752`），于是「重问了、被拒了、照旧开火」在日志里**没有痕迹**。先让它出声。
2. **让失败判词说真话。** tries=1 的 `ctx.fail` 现在写的是几何（「浇线上是…」），
   而真相是「**身体不在它自己选的落脚格上**」。判词错族会把下一个读者送去挖 k=0。
3. **让重试真的改变些什么**（唯一的行为改动）：记下**身体走不到的那些落脚格**，
   重试时把它们从 `standToPour` 的候选里排除。现在 tries=2 和 tries=1 逐字节相同，
   正是因为 `standToPour` 在同一个世界里必然给出同一个答案，而它不知道身体到不了那格。
   **集合按每一趟（cast）清零。**

| 态 | 读到什么 | 判作 |
|---|---|---|
| ① | 重试的 `stand.2` 与 `stand.1` **不再相同**，且至少一次 `picks` 的身体格与 `stand` 一致 | 第 3 笔成立 |
| ② | 候选被排除到一个不剩 ⇒ `模腔里没有能浇到 … 的落脚点` | **这是正确的失败**，不是回归：它说的是「够得着的格都试过了」，比现在这条准 |
| ③ | 三笔都落地后 `cast2` 仍拒绝，且 `.picks` 仍点 `4,58,18` | **几何本身无解**——目标上方那一柱挡着，这时才轮到「让 `blockersOnTheLine` 从 k=0 起扫」或换背板，且必须先量 `4,58,18` 是不是壁龛内 |

**不做的事**：不把 `blockersOnTheLine` 直接改成从 `k=0` 起扫。k=0 是目标自己那一柱，挖它就是挖模腔的框。
要动它必须先有 ③ 的证据。

⚠️ **归因判别式按 `.picks` 行，不按 `浇线上`。** `pourLine(lvl, target, away)` 是从目标**往身体方向倒扫**
`k=1..POUR_LINE`（`JourneyPortalRung:2570`），**恒不包含目标自己那一柱（k=0）**；
`JourneySight.blockersOnTheLine` 也从 `k=1` 起扫（`JourneySight:277`）。
拒绝时唯一有判别力的是 `.picks.N` 里 `face=` 前面那个坐标：= 目标那一柱（k=0）⇒ J72；
在 `k≥1` 且是壁龛内实心 ⇒ 才轮到 clear／淹水那条线。

---

## 📌 还没落地的预登记（判据写在读结果之前）

**这些读数还没写。删了就没有判据可对。**

### 两个全量闸（`stagewrightDedicatedServerFabric` / `…Neoforge`）

**该是什么颜色（先算，再去对）**：

- **本批没有改任何 `expected-scenes-*.txt` ⇒ 不应该出现 `UNDECLARED:`。** 出现了就是别处漏登记。
- 两条常驻 `fail(optional)`：`wd.vineOverWaterClimb`（−711 藤蔓传感器，`pocketTicks=81`）、
  `wd.serverEscapeSealedShelter`（`y=221.0`，carve 超时）。⚠️ **第三条 `wd.journeyGetsAshoreBeforePouring`
  是否仍在 optional 名单里，取决于 J47 有没有落地**——见下。两条/三条都不单独翻红。
- **Fabric ⇒ GREEN。**
- **NeoForge ⇒ GREEN，或者只红在 `wd.serverEarnsAnEnderPearl`**（已量到的 1/4 翻红率，工单 J75，**判据不下调**）。
- 新增风险面只有一个：`walkToColumn` 的**硬约束参数转发**（22 个调用点的共享助手）。
  **红的形状本身就是判据**：单条红 ⇒ 与本批无关；**多 rung 行军同时红 ⇒ 就是这笔转发**。

**读法**：`grep -cE '^\[stagewright:[^]]+\] VERDICT:'` **先数行**，再从 `VERDICT:` **往上**读
`UNDECLARED:`／`COVERAGE:`／canary 三条——它们各自都能单独把一趟染红。**全程不许 `tail`**。
⚠️ 数失败要**大小写不敏感**：必需失败印大写 `FAIL: '`，可选失败印 `fail(optional): '`。
⚠️ `VERDICT:` 可能有 2 行，其中一行是场景自己的证据行 `[wd.vineClingFidelityProbe] VERDICT:`，
**只有带拓扑前缀、不带时间戳那条是闸在说话**。
⚠️ **失败（required 与 optional 同）既不进 `executed` 也不进 `skipped`**；`executed + skipped` 两边都恰好 318。

| 态 | 读到什么 | 含义与下一步 |
|---|---|---|
| ① | GREEN，`UNDECLARED:` 空 | 清单对账通过 ⇒ 去排练/真梯 |
| ② | RED 且 `UNDECLARED:` 点名某个场景 | 清单里的名字与注册名不一致 ⇒ **只改清单，别改场景** |
| ③ | RED 而**没有任何必需项失败** | 从 `VERDICT:` **向上**读 `COVERAGE:` 与 canary 三行，别回头重读失败行 |
| ④ | 某个场景在全量里红（过滤跑却绿） | **跑序/共享世界**问题：竞技场与别人重叠，或某个 climb 级静态被上一个场景留脏 ⇒ 查 `climbSeq`／`JourneyStairs` 一类的跨场景状态 |
| ⑤ | 基线之外出现新的 optional 失败 | 记账，不阻塞——但要与上一次绿闸做差，别默认是这一批造成的 |
| ⑥ | 跑不出 `VERDICT:` 行（`grep -c` 为 0） | **不是红，是根本没起来** ⇒ 读日志中段，别看退出码 |

**先 Fabric 后 NeoForge，串行**，两者共享工作树，且期间不排练、不编译。

### 真梯 `:fabric:runJourneyIntegratedServer`

**只有这一个任务算真梯证据**：`fabric/build.gradle:773`，且 `:323` 写明它 **ADOPTS the client's real player**
（`LocalPlayer` 拓扑）。`runJourneyServer` 是无头的，`rehearsalServer` 按 `:532` **跑在 fake player 上**。

⚠️ **真梯任务不打 `VERDICT:` 行**（实测 0 行）——「数 VERDICT 行」这条协议在这里的正确读法是
「**0 行 = 符合预期**」，不是「死在半路」。跑完的证据是 `wd.journey99Verdict` 出了行。
⚠️ 结果文件叫 **`stagewright-results.jsonl`**（不是 `results.jsonl`），按**插入序**读，字段是 **`outcome`** 不是 `status`
（按 `status` 读会整列拿到 `None`，看起来像「一级都没跑」）。
⚠️ 起跑前三件事：按**命令行**确认没有游戏 JVM（`architectury.main.class=`，**不能用 `jps`**）、
`git status`、**先删掉 `fabric/run-journey-integrated/stagewright-results.jsonl`**
（残留的结果文件照样能回答问题）。⚠️ **删之前先 `ls` 出这个任务上次写了哪个文件**，别凭记忆写文件名。

**预登记**：

- ~~上限是 **11/20**，12 级三趟排练都 FAIL，所以**押 12 级仍然不过**。~~
  **这一条本身就是错的**：真梯上限是 **14（BLAZE_ROD）**，12 级此前 **2/2**（见下面第 2 趟读数里的
  主源引文）。「11」来自一份过期摘要，我照抄了没去核。
  另外，**排练三趟全 FAIL 没有预测到真梯会过**——两者不是同一具身体的同一段历史，
  排练的前 11 级是布景摆的。**排练结果不许当真梯的预测用。**
  **下一趟按上限 14 押，判据落在「14 级怎么死的」而不是「爬到第几级」。**
- 排练与真梯不是同一具身体的同一段历史（排练的前 11 级是布景摆的，真梯是爬上来的），
  所以真梯的 12 级可能死在**排练里根本不会出现的地方**。
- **要读的第一件事仍是族**：`stairsBroken` 有没有报坏（两道禁挖闸在真梯上是否同样闭合）。
- **J61 的 K1／K2 三态**（见上文「J61 的等级」）。
- **跑时会远超 40 分钟的习惯值——不许按时长判死，不许杀进程。** 活性看日志行数在不在涨（`wc -l`），不看挂钟。
- **「12 PASS + 14 级 FAIL」= J69 收口 + 新工单，不是 J69 失败。** 12 级一旦通过，
  **13 级往上在这轮回归期里是第一次真跑**（下界、烈焰棒、要塞……），总结局会由 13+ 的某个新前沿决定。
- **⑥ 选路时干 ≠ 到达时干。** `lowestDryStep` 在 `walkTheFlight` 开头算（`:320`），而走完整段楼梯要几百 tick。
  **签名**：`flightLastStepMissed` 且其中的 cellStory 写着终点 `身处 water` ⇒ **水更深了，修法照常生效，不许回退**。
  顺带：`flightEnd` 也可能出现在 `lava*.`（上行段）。**按 tag 族分开数**：`cast*.flightEnd` 九行是下限，
  总行数超九不是失控。

#### ✅ 闸的读数（2026-08-26 01:28 / 01:34）：**两个都 GREEN，闸债还清**

```
Fabric   : 298 执行 / 25 跳过   VERDICT: GREEN
NeoForge : 299 执行 / 24 跳过   VERDICT: GREEN
UNDECLARED: 两边都没有        canary: 两边三条全对（FAIL / TIMEOUT / 正确省略）
```

（数了 `VERDICT:` 行：各 2 行，其中一行是 `wd.vineClingFidelityProbe` 自己的证据行，不是闸判词——
这正是「先数 VERDICT 行」协议要防的那种误读。）

**这一批要验的是 `walkToColumn`**：核心重载加了硬约束参数，22 个调用点靠转发传 `List.of()`。
预登记写死了**红的形状**才是判据——转发若错，会让多个 rung 的行军场景同时红。一条都没红。
另有一条**代码级**的等价性证明：`Intent.java:31` 的两参构造器就是
`this(target, bias, CapabilityProfile.ALL, List.of(), null)`，与我传的四参**逐字相同**。
NeoForge 的末影人（J75）这趟没翻，是 3/4 的那一面。

#### 📖 真梯第 1 趟的读数（2026-08-26 01:41→01:57，16 分钟）：**8/20，摔死在 9 级**

⚠️ **10–20 级全是假绿**：印的是 `PASS (0 ticks) — skipped: BLOCKED: 上游阶段…未达成`。
真实成绩是 9 级（IRON）**身体死了**：

```
[Minecraft] Player657 fell from a high place
[journey] 身体死了：Player657 died（IRON 级，位置 84, 51, 76，本段第 30 tick，驱动器 goto）
心跳 IRON builder 本段第4/300 tick 身体=83,60,75      ← 六秒前还在 y=60 垒东西
```

**这一趟对 12 级什么也没说**：`stairsBroken` 在整份日志里 **0 行**——两道禁挖闸
**一次都没被执行到**，上面预登记里「要读的第一件事」没有数据。**该预登记仍然开着。**

死因不是本批改动：唯一能碰到 9 级的是 `walkToColumn`，而它的转发已由上面那条构造器
等价性证明排除；两闸全绿是同一结论的另一面。9 级此前过过很多次（上限曾到 11），
所以按族判这是方差；**一个样本定不了因**（[[one-sample-cannot-name-a-cause]]），
已开第 2 趟取第二个样本。

🆕 **新线索（等第二个样本再判，不要现在修）**：9 级 `builder` 段结束、切回 `goto` 的那一瞬间
从 y≈60 掉到 y=51 摔死。形状上像「一段可以继承一场下坠」／「补救留下的东西」那一族，
但只有一个样本。**判据**：第 2 趟若同样死在 9 级且同样是 `fell from a high place`
⇒ 开工单；若不复现 ⇒ 记账不修，等它第三次出现。
→ **第 2 趟 9 级 PASS（6262 tick），不复现。** 按上面写死的判据：**记账不修**，等它第三次出现。

#### 📖 真梯第 2 趟（2026-08-26 02:0x→02:46）：**13/20，零布景——不是新纪录，比历史最好低一级**

⛔ **这一节最初写的是「13/20 新纪录，12 级首次在真梯上通过」。两句都是假的，已改。**
主源是重写前的 `TODO.md`（`fb94af03`，第 21318–21321 行）：

```
## ⬜ 上一轮的接手点 —— 真 ladder 爬到 14 级（BLAZE_ROD）；12 级 2/2，13/14 级第一次执行
**新高：journey.height = BLAZE_ROD（第 14 级），journey.stagingCalls = 0。**
在此之前真 ladder 最高只到 12 级。两趟都跑完全程，没有中途收手。
```

⇒ 真梯上限是 **14（BLAZE_ROD）**，12 级 **2/2** 早就过过。今天这趟 `height = NETHER`（13），
**低一级**。

**我是怎么写错的，值得记**：三个来源给了三个上限——继承的会话摘要说 11、
记忆 `the-dragon-died` 说「真梯至今没爬到 12 级以上」、记忆 `the-portal-was-lit` 说到过 14。
我挑了手边最顺的那个（11），于是 13 看起来像跃升。
**记忆和摘要都是时点快照，不是裁判**；判「新纪录」这种话之前必须去查运行记录本身，
而它一条 `git show <重写前提交>:TODO.md | grep journey.height` 就能拿到
（[[two-ones-that-disagree]]、[[the-wrong-version-is-always-prettier]]——错的那版更漂亮，
所以更容易被选中）。

**这一趟真正的价值不在名次，在下面那一节**：本批两道禁挖闸第一次在真梯上被执行到并闭合。

```
wd.journey09Iron      -> PASS ( 6262 tick)      ← 上一趟摔死的那一级
wd.journey11Obsidian  -> PASS (10120)
wd.journey12PortalLit -> PASS (17295)           ← 第 3 次在真梯上通过（此前 2/2）
wd.journey13Nether    -> PASS (  186)
wd.journey14BlazeRod  -> FAIL ( 1179)           ← 新前沿
15–20                 -> 0 tick（BLOCKED 跳过，不是通过）
```

```
PORTAL_LIT REACHED — 在 y=57 就地浇出十块黑曜石并点亮 6 格传送门（自带一桶水下井，浇完水还在桶里）
NETHER   REACHED — 从自己点亮的门走进下界，落在 6, 41, 3（地表门在 2, 57, 20，按 8:1 应在 0,2）
99Verdict PASS   — journey 爬到 NETHER(进入下界)，地板 PORTAL_KIT，峰顶 DRAGON，**布景调用 0 次**
```

⚡ **上面那条预登记逐字命中**：它写着「**『12 PASS + 14 级 FAIL』＝ J69 收口 + 新工单，
不是 J69 失败**」，并预告「12 级一旦通过，13 级往上在这轮回归期里是第一次真跑」。
结局正是这一支——先把该判的判了，再去看死在哪，省掉了「一个解释得通的失败掩掉另一个」
（[[a-verdict-has-upstream-verdicts]]）。

##### ✅ 两道禁挖闸在**真实历史**上被观察到闭合

上一趟死得太早（9 级），这个量没有数据；这一趟有了：

```
stairsBroken 自检 …… 21 次，报坏 **0** 次（11 次「11 级都完好」，10 次「一格不缺但泡着水」）
梯级支撑对角线上的 [dig] …… **0** 行
```

排练三趟的曲线（3/11 → 1/11 → 0/11）在真梯上收在 0，而且真梯的前 11 级是**爬上来的**
不是布景摆的。**这是本批修法的终检，通过。**

##### 🆕 新前沿：14 级在下界**烧死**

```
[Minecraft] Player514 went up in flames
[journey] 身体死了：Player514 died（BLAZE_ROD 级，位置 71, 43, 69，本段第 49 tick，驱动器 goto）
```

死在 `@minecraft:the_nether`，`goto` 段第 49 tick，离下界落点 `6,41,3` 已经走了一段。

##### ✅ 归档日志直接把族定了，**不用再跑一趟**

原本写着「要分两支：走进岩浆/火 vs 被烈焰人点着，缺分族读数」。**读数其实已经在日志里**
（[[the-portal-was-lit]] 那条老账：一份归档常常够回答「走的是哪条分支」）：

```
02:46:03 [contactEscape] inFire damage (hp=1.0) at 71, 43, 69 → stepping out of contact
02:46:03 [lavaEscape]  flow front adjacent 69, 42, 71 at 70, 43, 70 (hp=1.0) → walking away
02:46:04 [lavaEscape]  handing back (clear), 14 ticks
02:46:05 死
```

- **不是烈焰人**：整个 14 级窗口一次 blaze 都没出现。是**地形的火与岩浆**。
- **守卫不是缺失，它们跑了**——差点又假设成缺失（[[a-guard-i-assumed-absent-was-running]]）。
  全程 `lavaEscape` 触发 **36 次**、`contactEscape` **4 次**。
- **而 `lavaEscape` 判了 `clear` 交还控制权，20 tick 后身体烧死。**

###### 🔴 缺陷一（代码级可证，不依赖样本数）：`clear` 说的是环境干净，不是身体不烧了

`LavaProximityEscape.tick`（`common/.../bot/auto/LavaProximityEscape.java:59/78`）：

```java
boolean hot = threat != null || p.isInLava();      // 只问环境
...
if (hot) linger = LINGER_TICKS;
else if (--linger <= 0) { reset("clear"); return false; }
```

`hot` **从不问 `p.isOnFire()` / `getRemainingFireTicks()`**。而在下界，**离开岩浆并不灭火**——
实体身上的火会继续烧数秒。于是「走开了 ⇒ clear ⇒ 交还」这条链在身体仍在燃烧时成立，
日志里那 20 tick 就是它的代价。

⚠️ **但别急着把 `isOnFire()` 塞进 `hot`**：这个反射做的事是「远离岩浆」，
而一具已经着火、且四周已无岩浆的身体，再走开也不会灭火。**让它继续激活并不等于救得回来。**
真正该问的是下面这条。

###### 🔴 缺陷二（更大的杠杆）：**整条反射链在真梯上是关着的**

真梯自己的证据行，逐字：

```
反射链实测：一条都没武装（autoRetreat/autoFight/autoDodge/autoHeal/autoShield/autoEquip
全 false，都是出厂默认；所以受到攻击不会有任何抢占）
```

配上血量曲线（全程 `hp=` 读数）：

```
hp=11.0 ×10   →   hp=10.0 ×7   →   hp=1.0 ×2   →   hp=0.0
```

身体从满血被一路磨到 1 血，**没有治疗、没有撤退、没有换装**，仅有的反应是那两条接触逃逸。
到 `hp=1.0` 时无论哪条反射都救不回来了——**所以真正的失败发生在 hp 从 20 掉到 1 的那一段，
不是最后那 20 tick。**

**判：先补测量，不现在改。** 理由三条：
1. 武装反射链是**影响全部 20 级**的行为改动，而窗口纪律是一次只放一笔
   （[[one-sample-cannot-name-a-cause]]：两笔一起动，下一趟就归不了因）。
2. **反射链默认关着可能是有意的**——它会抢占 walker 的按键，而这套梯子的
   前 13 级正是在「没有抢占」下调通的。动它之前必须先知道它是不是护栏。
   **先 grep 出是谁把它设成默认关、有没有写理由。**
3. 缺的读数很具体：**血量是怎么掉下去的**（每次掉几点、间隔多久、掉的时候身体在哪一格）。
   现在只有 4 个采样点，而且是 `lavaEscape` 顺手打的，不是按 tick 记的。
   这正是窗口 1 里 **J40②** 要补的东西——它从「锦上添花」变成**前沿的必需项**。
   ✅ **血量那一半已经落地并实测出行**（`JourneyRig.noteHurt`，无 flag、无节流、只记掉血、
   带脚下方块与着火 tick，排练日志里写出 `hp.trace`）⇒ **这条推进条件不再挡路**。

**重开/推进条件**：J40② 落地后跑一趟真梯，读血量曲线；若曲线显示
「单次掉 ≥6 点」⇒ 是掉进岩浆那一类，修路径代价；若「每 0.5 秒掉 1 点、持续十几秒」
⇒ 是身上着火烧完全程，那时再谈灭火/治疗，且届时已有分布可判。

###### ✅ 反射链默认关的**理由已 grep 出来，与真梯无关**

`BotConfig.java:232-237` 逐字：

```java
/** … Off by default so a quiet bot stays quiet (a scripted scenario that wants
 *  the bot to hold ground isn't overridden). */
public static volatile boolean autoRetreat = false;
```

⇒ 这是给模组普通用户的**产品默认**，不是这套梯子的护栏。真梯恰恰是想要它开着的那种场合。
所以修法形状定了：**只给真梯这具身体武装**（写死步骤，不是补引擎能力），
且 **`autoHeal` 先于 `autoRetreat`**——后者会抢占 walker，爆炸半径覆盖已经在跑的 13 级；
前者只争用 use 键，但**那也不是免费的**：12 级几乎全是桶的活，`shield > heal > eat` 的仲裁
可能在浇筑那一刻抢走 use 键。**所以仍然不改，等曲线。**

⚠️ 判词不因为「又多知道一件事」就改漂亮：一小时前判的「先补测量」在新事实下依然成立，
新事实只是把**修法的形状**定了，没有把**缺的读数**补上（[[the-wrong-version-is-always-prettier]]）。

###### 📌 预登记：`hp.trace` 这把新尺子自己的校准（写在跑之前）

仪器已落（`JourneyRig.noteHurt`，逐 tick、无闸、只记掉血、回血只计数、上限 60 行）。
**新写的尺子不校准就不能拿它的读数下结论**（[[a-verification-tool-needs-verifying-too]]）。
这一趟不另写场景，用**免费的交叉校准**：`lavaEscape`／`contactEscape` 会在自己的触发行上
打 `hp=`，那是一条独立来源。

| 态 | 读到什么 | 判什么 |
|---|---|---|
| A | 14 级有 `hp.trace`，且它列出的掉血点能覆盖 `lavaEscape` 那几行 `hp=` 的取值 | 尺子可用，**按上面的判据分族** |
| B | 有 `hp.trace` 但与 `hp=` 行**矛盾**（例如 trace 说没掉过血而 `hp=1.0` 出现过） | **尺子坏了**，先修尺子，别碰被测对象 |
| C | 身体明明死了而 `hp.trace` 一行没有 | 装在了错的地方——`await` 不是每 tick 都过，或 `player()` 在客户端拓扑上返回的不是那具身体 |
| D | 这趟没死在 14 级 | 那就读它死在哪；`hp.trace` 仍应在**每一级**出现（有掉血的级） |

#### 真梯第 3 趟（03:52）：11/20，12 级浇不到格（不是死）

`hp.trace` 校准通过（预登记 D 态），可以用。**还开着的**：

- 📌 `hp.trace` **不记 food、不记伤害源**，所以 12 级那 11 次恒定 −1.0、零回血判不了
  「摔落」还是「饥饿」。要加这两个字段。
- 📌 `drain` 7/9（第 2 趟 8/8）：`drain.7`/`drain.8` 等满 200 tick 仍有流体，
  而 `upstream` 证明周围 8 格无水源块 ⇒ **是预算不够，不是机制坏**。等待时长要重定。

##### 12 级真因：浇筑落在验过的排之上（修法已落 `36130011`，理由见 `JourneyPour.POUR_ROW_SLACK` 的 javadoc）

修法已落 `36130011`（`POUR_ROW_SLACK=1` / `RAISE_ROW_TRIES=1`，排不对就走回模腔重来）。
完整因果链和「为什么是加上界而不是翻 `exactRow`」在 `JourneyPour.POUR_ROW_SLACK` 的 javadoc 里。

📌 **排检查：`raiseRowTooHigh`／`raiseRowRetry` 已由 ladder9 检验（各 1 次），
`raiseRowGaveUp` 仍 0 次。** 实测行：`raiseRowTooHigh=2,64,22 比要站的排 y=58 高 6 排（容许 1）
… 走回模腔重来一次（第 1/1 次）`，之后没有 `GaveUp`——即「重来一次就成了」这条路走通了，
而「重来仍不行」那条分支**从没跑过**。
⇒ 构造场景的目标缩到 `raiseRowGaveUp`：要让重来之后仍然高出容许排（模腔本身就在高处）。
⚠️ 新场景必须**同批**加进 `expected-scenes-*.txt`，否则 `UNDECLARED` 判红。
⚠️ 场景要把身体摆在**柱外**——`JourneyPour:110` 有个「已在柱上就不走」的短路会绕过这道检查。

🟢 **第 12 级的界已确认生效**（`55fe4a42`，`runRehearsalIntegratedServer` 实测）：
`water8.lift.flightNotSkipped` 开火，同 tag 下 `flightSkipped` 归零，而 `cell.7.ramp`／`cast7.ramp`
两个不启用的调用方照旧 skip——界没漏到 dig 侧。
🔴 **下游接手的那一段是新的死因：身体从没下到壁龛里。**
```
lift.flight     = 4 级：2,56,17 → 3,57,17 → 3,58,18 → 3,59,19（壁龛地板 y=56，身体 2,64,20）
lift.stand      = 2,64,20 → 2,56,18（现在不在足迹上）
lift.standShort = 没走到 2,56,18，停在 3,64,18          ← 全程没离开 y=64
lift.laid       = 4/4 级垫好了（身体 3,64,18，停在 FINISHED）
lift.rampedY    = 64/60（停在 4,64,19，要的落脚格 3,60,19）
```
✅ **`laid=4/4` 不是越距放置，那四级本来就在世界里。** `layWhereItStands` 自带 reach 闸
（`JourneyRamp:548`，`MEND_REACH=5.0`），而 `.flight` 印的是 `supports(flight)`
（`JourneyRamp:291`）不是落脚格——四格 support 到身体 `3,64,18` 是 5.10～8.12，
只要有一格是空的就必返 `OUT_OF_REACH`；实测 `FINISHED` ⇒ 全部走 `:545` 的
「已实心就跳过」，**一级没放**。谁填的 `3,59,19` 日志答不了（无带坐标的放置行）。
🔴 **还开着的只剩一件：身体走不到施工位 `2,56,18`，而且不是「找不到路」。**
寻路器给出 64 步的路，身体在 `2,64,19` 被水平碰撞钉死：
```
[walker] 恢复跳: 卡住=11 身体=2,64,19 精确=(2.300,64.000,19.381) 闸=true 起跳=true
[expect]  MOVE-noMove: forward held 10t, displacement<0.3 at 2.3,64.0,19.4 hCol=true
```
同一起点同一目标搜了 10 次（[[a-retry-that-changes-nothing]]）。
⛔ **不要归给水**：积水在 y=56/57（`cast1.stairFoot`），而碰撞在 **y=64**——高 8 格，
撞的是实心墙，水解释不了。两条已排除：`builderStand:392` **验过** standability
（脚下实心＋头脚皆空），所以施工位不是被填死的；`walkTo:418` 带 `NoBreak`，**身体不许挖**。
❓ **那堵墙是什么，日志答不了**——`2,64,18` 全份零命中。唯一间接证据是物理推论：
`ramp.rampedY` 说身体曾停在 `2,65,18`，站着就意味着 `2,64,18` 实心；而起塔本该在 `3,19` 柱
（`raiseOffTheFlight` 避开了 `2,19`），`raisedY` 却说身体停在 `2,18`。
⇒ **下一步是加仪器不是改走法**：`standShort` 要能报出撞在哪一格、那格是什么、谁放的。
仪器已加两笔。⚠️ **第一笔（`988f88aa`，复用 `JourneyCorridorProbe`）答不了这个问题**：
`standY` 取的是 y 带里**最高**的可站面，身体在地表 63、壁龛在 56 ⇒ 目标柱印 `n`(=63)，
底下七排整个被遮。机制判据全中而读数不可用——两件事。
第二笔（`e1f582c2`，`.standShort.rows`）同印身体柱／目标柱逐格实心＋水平四邻（脚、头分开），
**待下一趟排练读**；四态判据在 scratchpad 预登记里。
⚠️ 不是回归：ladder9 是 `65/60` 且**一级楼梯都没修**，`.ramp.*` 一行都没有。
⚠️ 双闸零回归已验（`637eb4b8`，Fabric GREEN／COVERAGE 298/25／失败集逐条同基线）。

🟡 **同趟另一条，独立**：`cast7.1.settled.aimForked.1` —— 线段 clip 预言瞄 `5,59,21` 落进
`4,59,21`，存成角度后实际射线落进 `2,58,20`，于是换候选。换候选的机制在工作。
📌 **分子已量**：**28 次换候选／24 个瞄准场合**（cast 15、water 9，`rehearse-swing.log`）。
　⛔ **分母仍缺**：「一共瞄了多少次」要读 `aimForked` 的写者才能定——`picks`(19)／
　`atUseGate`(15)／`fromHere`(11) 是三个不同的量，随手挑一个当分母就是编一个分叉率。
死因是 `lift.flightSkipped=0,65,15 …（落点 3,60,20，exactRow=false）—— 不用修楼梯`：
`liftSideways` 刚说完「这一柱验不过这一浇，平移到验得过的那一柱」，`buildTo` 只比排号就跳过，
横移没发生，`liftedY=65/60` 还把没动的身体记成抬升完成。
修法落在 `JourneyRamp.buildTo` 的新参数 `rowSlack`／`sameColumn`，只由 `JourneyPour:690`（`.lift`）
启用；`JourneyPortalRung:975` 不启用——它跳过后紧接 `walkToStand` 走过去，加同柱会让它白修楼梯。
⇒ 前半已答：`.lift.flightNotSkipped` 已开火（见上）。后半重新定位了——**要改的不是
`JourneyPour:260` 的 `buildTo`，是 `:265` 的回调**：`if (getY() >= wantY) { done.run(); return; }`
只比排，而唯一把身体钉回指定柱的 `climbOutInColumn` 排在它后面（`:254`，仅 `pin` 为真时）。
实测 `water8.raisedY=65/60（停在 2,18，指定柱 3,19）`＝排够了、柱错着、塔没跑，
**而 `2,18` 正是后来挡住下井腿的那一柱** ⇒ 这可能是撞墙那件事的上游。
✅ `pin` 已验为 true，**不用加仪器也不用再跑一趟**：`pin` 就是 `verified != null`（`:148`／`:213`），
而 `:140-141` 的「钉住这一柱」**只有这一个写者**，实测 `water8.raise=…钉住这一柱` 即证明
（[[a-field-with-one-writer-is-a-proof]]）。⇒ `:265` 的注释「到了排就没塔什么事了」在 `pin=false` 时对、
在 `pin=true` 时错。修法：那个 early-return 要在 `pin` 时并上同柱判断。
⚠️ **但它救不了第 12 级，别排成下一笔**——⛔ 而且它是**一族两处，不是一处**：
`JourneyShaft:246`（`ascendByTowering` 的回调）写着同一句
`if (getY() >= surfaceY) { recordExit(…); return; }`，同样只判排。⇒ 只改 `:265` 会当场撞到它。
（此前这里记的理由是「`rise=0` ⇒ 塔是 no-op」，**理由错、结论对**：`climbFrom` 在算 `rise`
之前就设了 `BotConfig.allowPlace=true` 并打三行证据，`rise=0` 只让塔不垒，不让这条腿不发生；
真正让身体不回柱的是 `:246` 第二次判排。）
`:265` 的价值是**诚实和早期路由**，不是这一级的解药。
📌 **这一族的判据本仓已有一份血验过的**：`JourneyPortalRung` 的 javadoc 小节
「**A height is not a column**」（`:1467-1493`）——一个同形状的高度闸让 2026-08-16 的真梯
死在第六格，定的规矩是「**高度不许再拿来回答关于视线／柱的问题**」。
⚠️ 但别拿它给整族定罪：`getY() >=` 在这个包里有二十来处，判每一处**要读它的下游问的是什么**
（下游问柱 ⇒ 缺陷；下游只问高度 ⇒ 题目）——[[a-malformed-input-may-be-the-subject]]。
📌 **同段注释点了先例**（`JourneyShaft:222-225`）：塔会填掉身体起跳的那一格，
「which is how rung 12 filled 0,58,19 and 1,58,19 and then could not walk back down past its own
cobblestone」——**跟现在 `2,64,18` 挡住下井腿是同一个形状**。但这趟 `climbOutInColumn` 没跑
（`.climb` 一行都没有），所以作者是别人，等探针点名。
✅ **第 9 格那条已解决**（`bd4e41ae`：`standBehind` 在执行器尺之外也问判官尺）。
下一趟实测：`.swingFromHere` 放行 18 次，每次紧跟 `opened.N=<格>=air`，格心距 2.24–3.61
全部 >`DIG_ARRIVE=2`，而 `mineCell.4,60,19` **零次**。死因前移到浇筑。
✅ **浇筑那条已解决**（`2f130e5d`，理由见 CHANGELOG 与 `JourneyPour:164` 旁注）：
实测重来开火两次、两次都落低位（`cast7` 距目标 **1.00** 格，`cast8` 落 `1,57,19`），
`raiseRowGaveUp` 与 `raiseStuck` 双双归零（各 1 → 0），cast 前沿 **7 → 8**。
📌 **12 级现在死在这里**：`浇不到指定格：想浇 4,60,19（瞄 5,60,19），射线会把流体放进 3,69,15，
身体在 3,70,16`——身体 **y=70**，`walkerFallback=true`。
　塔那 40 个空转 course 已修（drift 的两次改写互为逆操作），**预测死因族不变**，验证在跑。
　**别动浇筑闸**——它这趟判得对（从 `3,70,16` 瞄 `5,60,19` 确实落进 `3,69,15`）。
🔍 **`drain.i=壁龛已排干` 只扫 `forgeCorridor`**（走廊格）；十二个框架格按设计留 SOLID、
　castCell 时才开，所以浇线上 `3,59..61,19=water(壁龛内)` 从来不在这句断言里。待判是不是缺陷。
📌 **撞墙那条（`cast7.lift.standShort`）复现了 2 次，剖面已点名**：
`身体柱 2,21=.#....... [63=grass_block]；目标柱 3,19=.#....... [63=grass_block]`
⇒ 两柱 y62..56 全空，各自只有 y63 一层**天然地表**盖着；身体站在盖子上、目标在盖子下，
而 `walkTo` 带 `NoBreak` 不许挖穿 ⇒ **要找开口，不是要挖**。
　⛔ 此前这里写「四邻唯一的 `#` 在 west、目标在 east/south ⇒ 不是障碍」——**那是直线方向**，
　而两柱都封顶时真实路线必然绕行（经楼梯口），撞哪一面得对着绕行方向判。
　⇒ 开口是**现成的**：同趟 18 次就地挥全在 y=56-57 打出，`stairsBroken=11 级一格不缺`。
　先查下行是不是死在 `stairFoot` 那格积水（[[water-is-not-a-floor]]），别先补「找开口」的新能力。
　⛔ **更上游：身体在塔顶**。`cast7.lift.stand=1, 67, 22 → 3, 56, 19`——起点 **y=67**，
　而地表只有 63-64 ⇒ 身体站在自己垒的塔上，要去 y=56 的壁龛，落差 **11 格**；
　`standShort` 停在 `2,64,21` 是**已经下了 3 格之后**停的。
　⇒ 先问「谁把它举到 67」，再问「怎么下去」（[[the-run-destroys-what-it-built]] 记过
　「解卡塔按当前高度+8 越重试越远」）。开口是现成的（楼梯口 `-8,66,19`，绕行约 10 格），
　所以这是**寻路预算／可达性**问题，不是「没有开口」。
（浇线上 `1,60~63,20` 的 dirt 已排除是这一趟垒的：`clear3` 印的是 grass_block 压 dirt 的原生剖面，
「壁龛外」是 `clearPourLine` 拒绝清的理由，不是放置记录。）

🔴 **同级上游：三次重走问的是同一个问题。** `water8.stand.1/2/3` 的否决计数逐项相同
（`脚下不实心=51, 落脚格被占=74, 够不着 5,61,20=8` …）。楼梯没垒完是因：
`water8.ramp.laid=3/4`、`wet.8.ramp.laid=0/4`，两次都卡在**身体压在自己要垫的那一格里**
（`vanilla 的 isUnobstructed 会拒`）。`ramp.aside` 的「挪开再问一次」只问一次，不够。

📌 **`[expect] GEAR-degraded` 是恒假阳性，判据要改。** `WalkerExpectAlarms.ClientGearCheck.missing`
的 `pick` 只认 `DIAMOND_PICKAXE`/`IRON_PICKAXE`，木镐石镐都不算，所以梯子拿到铁镐之前每 100 tick
必报一次（ladder5 前六级 30 次，第 4 级「木镐 ×1 到手」PASS 之后措辞一字未变）。而它印的是
「pickups crowded the gear out」——归因是编的。叫 degraded 就得以「装备存在」为前提：
判据应是**背包里有而快捷栏里没有**；两者都没有是「还没造」，不该报警。水桶同理。
**修法验证（先写在这）**：下一趟带仪器的运行，拿到铁镐之前 `GEAR-degraded` 应为 **0 行**；
之后出现的每一行都要能在 9–35 格找到它点名的那件东西——告警现在自带槽位就是为了让这条可查。
ladder5 旧判据累计 108 行，其中拿到桶（第 10 级）之后只有 2 行，那 2 行大概率是真的。

📌 **下一趟真梯的判读，先写在这**（`3ce54dd4` 把排上界限定到了浇筑侧）：
1. `raiseRowTooHigh`／`raiseRowRetry`／`raiseRowGaveUp` 只许出现在浇筑 tag 下；收水 tag 下出现任何一次 = 限定没生效。
2. `scoopRowHigh` 若出现，下游必须跟着 `buildTo` 的楼梯行——没跟上就是便宜修法仍然轮不到。
3. 两个闸重跑排在真梯**之后**：收水侧恢复的是 `36130011` 之前长期绿过的原样。

🔴 **同族第三处，而且这处有场景正红着**：`JourneyCast.java:100`

```java
WorldDriverJourneyScenes.walkToColumn(rig, "lava.ashore", dry.getX(), dry.getZ(), 1, 600, …);
```

要的是「上岸」（脚下固体），给的是 `Goal.XZ`（那一柱、任意 Y）⇒ 身体还泡在水里就被判到岸
（`dryLand=244891,221,100000`，实际停 `244892,220,100000`，`arrivedY=220 脚下=water`）。
`wd.journeyGetsAshoreBeforePouring` 因此一直红着——**不是回归**：2026-08-25 才加（`d76be87f`），
且 `JourneyCast.java` 不经过 `36130011` 改的任何一处（grep 零命中）。
⚠️ 踩的是 `JourneyPour:115-117` 注释**早就写明**的坑：`walkToColumn` 判到达用自己的
`ARRIVED_WITHIN`（5 格），**不是调用方传的 `tolerance`**。

📌 **还欠两件，都不是「补到岸检查」**（那件 `ccaf6861` 已落：`stepOntoTheBank` 重问 afloat + 容差 0 补一步）：
1. ~~补一步之后仍浮着，`bankRow` 只记一笔就往下走，而那行键叫 `lava.exit.ashore`（上岸）在脚下是水时照打 ⇒ 判词撒谎。~~
   ✅ 判词那一半已修（`ashore()` 现在自带 `noDryFooting` 判据，见其 javadoc）。**行为那一半仍开着**：
   仍浮着时 `bankRow` 照样往下走。
2. ⚠️ **「脚下垫一块」这个写死步骤的几何不成立**：浮体在 y=220、岸在 221，`at.below()` 是 219，
   垫上去身体仍低岸一格。真几何要先读 `JourneyLandingScenes` 的布景再定，别照 J47 那句原文抄。
3. 🔴 **`ccaf6861` 那个「容差 0」根本没生效——它管的是报告诚实性，不是这个场景的上游。**
   （上游是 J47：2026-08-26 闸实测 `lava.lastStep.gotoEnd.1=end=path-consumed`，
   路本身就只到差 1 格处，所以收紧判据只会把「假上岸」换成「走不到」，见本条末尾。
   `JourneyLandingScenes:45-55` 的场景注释早写着这一点，两处别再各说各的。）
   `WorldDriverJourneyScenes:778/795`：`tolerance` 只喂给 `Goal.XZ`，判到达写死 `away <= ARRIVED_WITHIN`。
   2026-08-26 闸的证据：`lava.lastStep` 传 0，`gotoEnd.1` 照印「容差 5」、距 1 格判到达。
   ⚠️ **别一刀把 795 改成 `away <= tolerance`。** 普查了 14 个调用点：传 **0** 的有 **8** 个
   （`lava.lastStep`／`water`×2／`lava`×2／`raiseTo`／`shaft`／`gravel`），另有 1／2／3×4／6 各若干。
   一刀收紧＝8 处同时从「5 格算到」变成「必须精确到柱」，一趟里冒出一堆红且互相掩盖，归不了因。
   顺序改成：
   a. 先只加证据行——`tolerance < away <= ARRIVED_WITHIN` 时打一条「按调用方容差本不算到达」，
      跑一趟数出**谁在吃这个宽松**（零成本，不改判据，不会让任何场景变色）；
   b. 再给 `lava.lastStep` 这类要精确的单开一条路径（重载或 exact 标志），只收紧它；
   c. 其余调用点按 a 的读数逐个处理，别批量动。
   ⚠️ 收紧之后 ashore 场景**不会立刻翻绿**：`end=path-consumed` 说明走行器自己也停在 244892，
   795 只是第二道闸；届时死因应从「假上岸」变成「走不到那一柱」——那是对的红。

### 排练 `:fabric:runRehearsalIntegratedServer -Prehearse=PORTAL_LIT`

拍板节好几条的判读样本就是它。⚠️ **必须是 `runRehearsalIntegratedServer`（真 `LocalPlayer`），不是
`runRehearsalServer`**——同一段代码在两具身体上走出不同的亚格轨迹（`returnedY=57`×3 假玩家全落地
对 `58`×2 真客户端骑唇），**身体种类是自变量，不是背景**。
⚠️ **并发禁令**：排练在跑就不编译、不跑闸——共享同一棵工作树。
⚠️ `PORTAL_LIT` 是**故意保持在 40 000 tick 上限**的现存回归闸；`-PrehearseBudget=120000` 是撞上中途超时才用的逃生口。
⚠️ 这个排练的基线是 **1 绿 / 3 趟**，**一次 FAIL 判不了回归，一次 PASS 也判不了修法**。
它同时是 **J71 那张 `forge.carved` × 结局表**的取样趟。

### J34 那一趟真梯遗留的两条 📌 仪器（都已判「做」，等编译窗口）

1. **`walkHome` 的 `strand` 支不经过 `settleOntoHomeGround`。** 今核 HEAD：
   `WorldDriverJourneyScenes:1286-1292` 仍只写 `strandedAt` 就 `then.run()`。
   而**走丢的身体恰恰是最需要这个读数的那一具** ⇒ 把 `strand` 也接进去。
2. **`bed.towerRecovered` 只记了拆塔之后的圆石数**，没记拆塔**之前**的，所以「这一趟拆回来几块」量不到，
   只能靠净损间接判。今核 HEAD：`settleOntoHomeGround` 的 `towerRecovered` 行
   （`WorldDriverJourneyScenes:1378`）仍只记拆塔后的存量。⇒ **补上前后两个数。**

### 清渣读数（`mineCell.` **前缀族**，配合 `portal.doorway`）

⚠️ **不能写死格号**：`4, 57, 19` 是上一趟浇筑动力学留下的渣位，不是布景摆的常量。
⇒ 判据登记 **`mineCell.` 前缀族**，不是任何单个键。

| 态 | 读到什么 | 判什么 |
|---|---|---|
| ① | 有行，`canBreak=false` + `有暴露面=true` | **走近失败**。修「浇筑收尾后身体停在哪」，不是挥空。⚠️ 动手前先查：`mineCellOrGiveUp` 的客户端腿是 `Goal.Near(target,2)` + **NoBreak**，若唯一路线需要破拆，600 tick 卡死是构造出来的。真是这形状就给清渣一个**脚本化站位格**，**不放宽 NoBreak** |
| ② | 有行，`canBreak=false` + `有暴露面=false` | **格子是封死的**，跟距离无关。查是谁把它埋了 |
| ③ | 有行，`canBreak=true` | **够得着而没开**：闸放行了，`destroyBlock` 之后方块还在。查挥空那一族 |
| ④ | 零行，且 `portal.doorway` 说清干净了 | 死因往后搬，**算进展** |
| ⑤ | 零行，且 `portal.doorway` 仍说堵着 | **仪器盲区**：那格根本没经过 `mineCellOrGiveUp`。停下读码，查 `clearNext` 的入参是怎么来的 |
| ⑥ | 12 级 PASS | 上真梯 |

**已收窄两条，不用等读数**：① `clearNext` 是**严格单遍**（`JourneyPortalRung:2767`，`i` 从 0 递增，
给不动就往下走，**永不回头**）；② `Goal.Near.reached` 是 `p.distSqr(target) <= radius²`（`Goal.java:92`），
radius=2 ⇒ 眼到格心最多约 **2.3**，而上限是 **5.00** ⇒ **真到了目标就一定 `canBreak=true`**，
所以 ① 只能是**没到**，不可能是「到了还够不着」。
⇒ 修法方向定死：**不是放大 `Near` 的半径**，是问那条腿为什么到不了；而「腿结束了」不等于「到了」
（走行器给部分路径也报 ARRIVED，`journey.helm.endings` 里的 `→跑完` **不能**当成到达的证据）。
**两条候选机制指向同一个修法**——清渣改成「**扫到没有进展为止**」，而不是碰 `NoBreak`、不是碰 `Near` 半径。

---

## 🗄️ 归档里没结案的线索（2026-08-26 逐行普查，**未逐条对 HEAD 复核**）

删掉的 19 000 行不是没人看就扔的——重写前**整份文件按八个区段逐行读过一遍**，
把每一条判成「开着 / 条件式待办 / 未落地预登记 / 已结历史」。下面是普查捞出来、
**上面各节没有对应条目**的线索。

⚠️⚠️ **这一节的每一条都只是线索，不是待办。** 三条理由：

1. **旧版是倒序编年**（行号越大日期越早），普查是在**每个区段内部**判「有没有结案」的。
   一条被判「还开着」只意味着**在它下方没找到结案**，而结案很可能写在上方更新的段落里
   ——那些段落正是本文件保留下来的部分。
2. 所以**动手之前先对 HEAD**：`grep` 它自己会写的那一行、读产码、量文件行数。
   已经这样撞掉过一条：`WorldDriverJourneyScenes.java` 那条「超 3000 行硬闸」在归档里是 🔴，
   今日一量是 **2819 行**，早就不超了。
3. 行号一律相对 `git show fb94af03d59f4bc16639f102ab184ac5c037ac9f:TODO.md`，
   **对当前文件无效**。

### 引擎/产品侧（`src/main`）

- **`BotConfig` 装着每个个体每 tick 的运行时状态**（`BotConfig.java:882`、`WalkerTickPrelude.java:83`）
  ——原文写着「修好之前并行跑场景不安全」。今日复核：那些字段仍是 `public static volatile`，
  即**全局单例态**。与 J7 同一族（同一个文件，同一次拆分）。[23266–23316]
- **`FarmProcess.BREAK_TIMEOUT_TICKS = 60`** 没接 `BotConfig.breakTimeoutTicks`
  ——**今日复核仍是 `FarmProcess.java:34` 的 `private static final`**，确认还活着。[14423–14452]
- `DescendProcess.java:239-264` 的 `actTicks` **跨相位不清零**。[14423–14452]
- **四把「够不够得着」的尺各自为政**：4.0 / 4.3 / 4.3 / 4.4。[14423–14452]
- `ServerPlayerAvatar.holdPlaceable:203` 会把身体**唯一的熔炉**当垫脚花掉（两遍扫描的处方已写好，归 wd-parity）。[15765–15795]
- `LavaProximityEscape.java:66` 用 `(int)` **向零截断**印格号，负坐标下印的不是身体所在格；同族**全仓 3 处**。[8724–8747]＋[10409–10422]
- 两个 Escape 的 `reset()` **收尾约定不一致**（Q23c）。[8776–8782]
- **岩浆自救根本不存在**，而且泡进岩浆之后会把这一段**剩下的预算全部走完**。[21836–21840]
- **parkour 起跳闸**：剩余水平 < 1 格时不许起跳。致命 `0.64 / 0.75`，存活 `2.29 / 2.90 / 3.08`
  ——**五点完全分离、区间不重叠**，谓词已具体到阈值，修法没落。
  硬要求：`wd.parkourVoid{Short,Long}Runway` **两条必须一起绿**。[16349–16451]
- `WalkerTickClimb.java` 两条水中守卫**够不到「完全没入水」的那一格**。[11932–11947]
- **约 65 处在 tick 里驱动全局键位**；「窗口未聚焦 ⇒ 每 tick `stopDestroyBlock()`」这条链**尚未实测**。[13867–13897]
- `allowPlace` / `allowBreak` **一个概念两套执法**。[9484–9490]
- 「**执行器离开自己的计划，然后再也回不去**」这一族。[21268 / 21412–21413 / 21690–21691 / 21742–21746]
- **「冻结窗口」整族未修**：驱动器不注册期间流体旗标是冻的，同病判据约 **31 条约 120 个读点**，
  **连测量都还没落**。[17641–17663]

### 身体等价性（`bot/sim/**`，归 wd-parity）

- `stopUsingItem` 的**客户端孪生还没落**（N8/T4）。[12036–12037]；瞄准两侧分裂的孪生同样未落 [12067–12073]。
- 写 `selected` 的是**四个方法五行**，不是三处。[11801–11809]
- V2 向下挖不低头（pitch 有两个主人）／V3 不挥手（要一次专门探针）／V4 `holdBestWeapon` **只在下界级被调**／
  V5 工作台不回收·掉落物不捡／V7 一开始像晚上（加一行 `spawn.dayTime`）。[13622–13693]
- 服务端 avatar 的**挖掘保真度洞**：赤手挖黑曜石 / 无精准采集 / 工具不掉耐久。[24050–24055]
- `JoinedBody.tick()` **空实现**＝上线的下半场（驱动改成写输入）。[24176–24180]

### 账本与仪器（testmod 侧）

- **`ctx.fail` 没有流进 `JourneyLedger.failed`** ⇒ 账本收不到失败原因。[15912–15914]
- **跳过的级 `outcome=PASS`**；且 7 级从来没写过。[15633–15636 · 15915–15917 · 16520–16521]
- `advancement.obtain_blaze_rod = not-earned` 而**包里有棒**——⚠️ 原文自带处方：
  先量 `CriteriaTriggers.INVENTORY_CHANGED` 有没有对 `JoinedBody` 触发，**别先信判词**。[15607–15609 · 15919–15920]
- `climb.*` / `exit.*` 证据键**不带 tag，互相覆盖**。[22209–22215]
- **mob cap / 区块加载 / 地形改动没有任何一行读数在记。**[15866–15869]
- **17/19 级还没有 `stock.*` 证据行**，三处配方表都在等它。[18912 / 18915 / 18999 / 19001]
- `COVERAGE:` 那行的 `executed` **不含「跑了但失败的」**；覆盖率那一栏还得写 skip 数。[6764–6777]＋[9230–9232]

### 13–20 级（真梯停在 12 级，这些是往上走会先踢到的石头）

- **17–20 级仍然没有布景配方。**[23181–23193]
- **「从已知状态续跑」** —— 三轮点名的第一优先，仍未做；排练布景只覆盖「机制验证」那一半，**没覆盖真梯**。[23909–23925]
- **9 级多买两个桶：代码那半已落地，缺的是铁那半**（13 锭 vs 三矿脉上限 9 ⇒ `RAW_IRON_TO_MINE` 要 14）。[22630–22666]
- 14/15 级：`walker()` 出口是下一步；`expanded=1` **被封死没人挖出去**。[23083–23135]
- 15 级：**走不到扭曲森林**（身体泡在岩浆里）。[23137–23180]
- **岩浆湖是个洞穴湖，顶只有一格厚**——两条修法都还没试。[23029–23048]
- `JourneyEndRungs.march`（17 级去要塞）**第三处「拿位移当进展」**。[20963–20975]
- **壁龛排不干**：成因找到一半，仍未修（已从卡点降为代价）。[22873–22889]
- 第 11 级**第三种死法：挖过头 12 格**。[23768–23788]
- **真梯在「和平模式」里通关** —— 三选一，需要用户拍板。[13529–13559]

### 工程/构建

- `rehearsalIntegratedServer` 的 **115 行 `-P` 开关**必须抽成共享闭包。[15076–15083]
- `SchedulerClientCallSurfaceTest` 的扫描要**从 owner 扩到描述符形参**
  （＝[[a-scheduler-class-may-pass-a-client-type-not-call-one]]）。[10603–10604]
- `docs/drown-escape-design.md` §5 结案纪要：**触发条件（两个闸都绿）早已满足，到期未做**。[12729]

### 三条已经确认作废的，别再捡回来（也是这一节为什么只能当线索的三个活证）

- `WorldDriverJourneyScenes.java` 「超 3000 行硬闸」（归档里是 🔴）：**今日复核 2819 行，不超了。**
- **J26**（写在 `ctx.cleanup` 里的自检行哪儿都不出现，[9119–9128]，＝[[a-confluence-point-is-not-a-deadline]]）
  和 **J27**（竞技场基线 38 个 pin 的普查，[9425–9499]）在归档段里都没结案，
  但**旧队列表上它们分别是 `✅ 已落（2026-08-24 核 HEAD）`（`42ae16b5`）和 `✅ 已普查`**
  ——结案就写在归档**上方**、本文件保留下来的那一侧。这正是第 1 条注意事项说的那种误判。
- **归档 24001–25670 整段作废**（2026-06-16 → 2026-08-10）。那是 `gap#` / `task#` 那套旧账本，
  写在 Python 编排器退役（2026-08-05）与 StageWright 分仓之前，条目的载体本身已经不存在
  （Xvfb `:99`、t1 gate、插件 v2、merge 策略、`instrument` 那一族……）。
  ⚠️ 顺带一条**普查发现的排序反常**：这一段**不是全程倒序**——24007→约 25100 倒序，
  约 25101→25670 反过来是顺序。真要去翻，按「代号 + 日期」双向查证，**不要按位置判**。

---

## 📎 读判词时反复咬人的几条（留着，代价已经付过）

- **一个 `killed` 通知既不证明停了，也不证明会继续。** 串联任务要分两问：「前一段还在跑吗」（看产物）
  和「后一段还有没有人触发」（看脚本活没活）。已经吃过一次：gradle 变孤儿继续跑到底，
  而 `if`／`rm`／第二个 `./gradlew` 随 shell 一起没了，**下一趟不会自己开始且不会有任何报错**。
- **场景名是完成时才打的**，所以「最后一条」永远不是肇事者。一天之内踩过三次。
- **残留的结果文件照样能回答问题**，而且答得流畅、格式正确——只是答的是上一趟。
  守望产物之前先确认**这一趟的产物已经存在**（等旧文件被删掉才开始计数）。
- **`expected-scenes-*.txt` 是判官的一部分，不是源码。** 「运行中可以改 `.java`、不能编译」这条规矩
  **只覆盖 `.java`**；manifest 是**判定时才从磁盘读**的运行期数据，闸跑着的时候改它等于中途换裁判名单。
  **登记一条场景和它的 manifest 行必须在同一个提交里。**
- **在给一段代码补守卫之前，先在日志里搜它自己会写的那一行**；**而 grep 落空还要再读产码**——
  静默的 `null` 分支不写任何东西，所以 grep 落空并不能证明守卫不在。
- **`ctx.check(x)` 的参数必须是要断言的那个东西本身，不是关于它的布尔表达式。**
  `ctx.check(settled != null).isNotNull()` 只能通过；同一族已经改掉四条，而重构会**原样搬运**它们。

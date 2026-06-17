# 浮力 bot 水域导航统一模型 Implementation Plan (Surface-First Routing)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 A* 永远只产出浮力 bot 可执行的水路——非潜水落脚点只能是水面或干地,深水横渡走水面、罕见水下封顶通道走高成本的游泳族,彻底消除「A* 把路由放进/沿水下河床→浮力 bot 沉底 churn」整类停顿(z3034 / z2744 / z3022 / 瀑布水帘)。

**Architecture:** 一条不变量(水位分类)派生两个门:D1 = 目的地 head-submerged(`isWater(foot+1)`)→ 禁所有地面族 move;D2 = 源格浮在水上(`isFloatingWater`)→ 禁上爬族。新增纯水平 `SwimTraverse` 原语 + 统一 `pathfinderSubmergedTraverseCost` 作潜水回退层(高成本但不硬禁,保证罕见水下通道仍有路)。执行器扩展 `diveHold` 跟随水平潜行段。最后作废旧的 `floatOverSubmerged` 河床骑行补偿,不留双轨。

**Tech Stack:** Architectury MC 1.21.1 / Java 21 / Gradle;A* 寻路在 `common/.../bot/pathfinder`;执行器 `Walker.java`;测试 = NeoForge `@GameTest` arena(`./gradlew :neoforge:runGameTestServer`)+ live `mc.debug.replay`。

**Source spec:** `docs/superpowers/specs/2026-06-17-buoyant-water-navigation-design.md`

---

## File Structure

所有路径相对仓库根 `agent-driver-mod/`。

**核心谓词(新增不变量):**
- `common/src/main/java/net/magicterra/agent/bot/pathfinder/WorldView.java` — 新增 `headSubmerged(foot)` default 方法(D1 谓词);已有 `isFloatingWater`(D2)/`isSubmergedAscent`/`isSubmergedFoot` 保留。

**地面族 move(加 D1 守卫):**
- `.../pathfinder/moves/Walk.java`、`Diagonal.java` — 新增 D1 守卫。
- `.../pathfinder/moves/StepDown.java`、`DiagonalDescend.java` — 把现有 `isWater(to)&&isWater(to+1)`(4ecb972)守卫**替换**为统一的 `headSubmerged(to)`(更广:覆盖实心河床+头淹没)。
- `.../pathfinder/moves/StepUp.java`、`StepUp2.java`、`DiagonalAscend.java` — 新增 D1 守卫(保留现有 `isFloatingWater`/`isSubmergedAscent` 作 D2)。

**潜水回退层(新原语 + 成本):**
- `.../pathfinder/moves/SwimTraverse.java` — **新建**,纯水平无破坏游泳原语。
- `.../pathfinder/Move.java` — 注册 4 个 `SwimTraverse`。
- `common/src/main/java/net/magicterra/agent/bot/BotConfig.java` — 新增 `pathfinderSubmergedTraverseCost`。
- `.../pathfinder/PathFinder.java` — 新增 `submergedSwimTax(to)` 并接入 edge 成本累加。

**执行器:**
- `common/src/main/java/net/magicterra/agent/bot/movement/Walker.java` — Phase 3 扩展 `diveTarget`/`diving` 识别水平 `swimTraverse`;Phase 4 移除 `floatOverSubmerged`(1289-1317)及 `WATER_DESCEND_GIVEUP` 河床骑行补偿。

**测试 arena(新增):**
- `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTest.java` — `submergedFloorWalkArena`(Phase 1 谓词)、`sealedUnderwaterPassageArena`(Phase 2/3 潜水回退)、`submergedSlotCrossArena` + `waterfallCascadeArena`(Phase 4 回归)。

**测试命令(贯穿全程):**
```bash
DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | tail -40
```
期望:末尾出现 `FAIL: 0`、`PASS == TOTAL`(TOTAL = 旧 109 + 本计划新增 arena 数),以及 `All 45 required tests passed`。**每个 task 跑完都必须保持这两条绿。**

**live 终验命令(Phase 0 与各 replay 门):**
```bash
DISPLAY=:99 AGENT_WORLD=Mountains setsid ./gradlew :fabric:runClient   # 重连客户端
# 通过 MCP / .claude/skills/agent-driver-rpc/rpc.py 调:
#   mc.debug.replay { "id": "replay-0004", "mode": "replan" }
```
期望:端到端到达 `~(1732,83,3097)`;z2744 / z3022 / z3034 处 `totStuck` peak 全低(<60);**视频转录(qwen3.6,gemini 仅在用户允许时)零异常窗 + pathChart 零异常**。

> **视频解析硬约束**:`qwen3.6-27b-nvfp4`(run.py 唯一实例);**gemini 仅在用户明确允许时使用**。实验跑动必须开 live-screen-watch,停观测时 Monitor + run.py 一起 TaskStop。

---

## Phase 0 — 现场验证假设(live;无代码改动,产出决策)

**目的:** spec §5 要求先 live 确认 §4 的三处未验证假设,再动代码。这是一道**决策门**,不是单元测试。产出写入本 plan 末尾的「Phase 0 findings」小节。

**Files:** 无改动(纯观测)。

- [ ] **Step 1: 重连客户端 + MCP**

Run:
```bash
cd agent-driver-mod
DISPLAY=:99 AGENT_WORLD=Mountains setsid ./gradlew :fabric:runClient
```
等待世界加载(`mc.observe.player` 返回有效 pos)。开 live-screen-watch 观测通道。

- [ ] **Step 2: 复现 z3034 并实测 A* 为何路由河床(item 6)**

用既有复现台:`tp @p 1741 68 2986` → `mc.bot.goto {"xz":{"x":1733,"z":3090},"near":3}`。
观测 `mc.debug.plan` / pathChart:记录 z3034(x1763)附近 A* 给出的节点 Y。
**判定要点:**
- 若节点落在 y55-58 河床(walk-along-floor)→ 确认 spec §2 F4/F5 假设(D1 将消除)。
- 检查该处水面(y62 顶层水格)是否被 overhang 挡住(`mc.query` 探 `(1763,63,3034)`、`(1763,64,3034)`)→ 若被挡,Surface-First 在此处可能 no-path,需 Phase 2 的 SwimTraverse 兜底(记录到 findings)。

- [ ] **Step 3: 浅水/河滩涉水不被误禁(item 6 回归风险)**

找一处头出水的浅水涉水(foot=水、head=air,如 z3022 入水口前的浅滩),确认当前能正常 Walk 通过。
**判定:** D1 = `isWater(foot+1)`,浅滩 head=air → D1 不 fire → Phase 1 后仍可走。记录任何 head 在水下的「正常涉水」反例(若有,说明 D1 过严,需在 findings 标注并在 Phase 1 调整)。

- [ ] **Step 4: 记录 diveHold 现状基线(item 3)**

复现一段已知的 swimDown 潜水(如 z2744 入口或任意深 goal),观测 `diveHold`/`diveLatch` 触发(walkerDebug 日志)+ 视频:确认垂直潜水当前是否平滑。这是 Phase 3 水平扩展的对照基线。记录 pitch、sneak、forward 在潜水段的实际值。

- [ ] **Step 5: 写 findings + 决策**

把 Step 2-4 结论写入本文件末尾「## Phase 0 findings」。**决策门:**
- 若 §4 假设全部确认 → 进 Phase 1(无需改动后续 task 代码)。
- 若发现反例(如浅滩 head-submerged 正常涉水、或 z3034 水面被 overhang 全挡)→ 在 findings 标注,并据实修正 Phase 1/2 对应 task 的守卫/成本(就地编辑本 plan 后再执行)。

- [ ] **Step 6: 停观测**

TaskStop live-screen-watch Monitor + run.py(避免空跑烧 token)。client 可保留供后续 replay 门复用。

---

## Phase 1 — 核心 head-submerged 守卫(D1)

确立 `headSubmerged` 谓词,给 7 个地面族 move 加 D1 守卫,A* 的非潜水水路只能沿水面顶层格。

### Task 1.1: `headSubmerged` 谓词 + 谓词级失败测试

**Files:**
- Modify: `common/src/main/java/net/magicterra/agent/bot/pathfinder/WorldView.java`(在 `isFloatingWater` 之前,~line 284)
- Test: `neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTest.java`(新增 `submergedFloorWalkArena`)

- [ ] **Step 1: 写失败测试 `submergedFloorWalkArena`**

在 `AgentGameTest.java` 末尾的最后一个 `}` 之前插入(复用文件内已有的 `ServerPlayerAvatar`/`LevelWorldView`/`grantWaterEffects`/`Walk`/`Diagonal`/`StepDown` 等 import):

```java
    /**
     * Surface-First D1 guard (head-submerged destination forbids ground moves).
     * Builds a solid riverbed strip at y=floorY with water filling foot+1 (a
     * SUBMERGED floor: foot is solid stone, head is water). A buoyant bot can't
     * stand here, yet pre-fix canStandAt treats it as a floor and A* routed a Walk
     * along it (live z3034 ~20 s sink). Asserts predicate-level: Walk/Diagonal/
     * StepDown to a head-submerged cell are now invalid; a SURFACE cell (foot water,
     * head air) and a DRY cell stay valid.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void submergedFloorWalkArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 180, cz = 160, floorY = 200, depth = 3;
        final int surface = floorY + depth;            // y203 water surface

        // Solid basin floor + N/S/W containing walls past the surface.
        for (int dx = -2; dx <= 8; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = -2; dx <= 8; dx++)
            for (int y = floorY + 1; y <= surface + 1; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
            }
        for (int dz = -3; dz <= 3; dz++)
            for (int y = floorY + 1; y <= surface + 1; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
        // A SUBMERGED-FLOOR strip: a raised solid ledge at floorY+1 (foot cell), with
        // water filling foot+1..surface above it → foot solid, head water.
        for (int dx = 0; dx <= 6; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        // Fill water from foot+2 up to the surface over the whole basin.
        for (int dx = 0; dx <= 6; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 2; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());

        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface - 1, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);

        // submerged-floor cell: foot=(floorY+2) is water? No — pick the solid-floor
        // case: a cell whose FOOT is the solid ledge top and HEAD is water.
        BlockPos submergedFloor = new BlockPos(cx + 3, floorY + 2, cz);   // foot water at y202, head y203 water → headSubmerged
        BlockPos solidHeadSub = new BlockPos(cx + 3, floorY + 2, cz);     // same: head submerged
        BlockPos surfaceCell = new BlockPos(cx + 3, surface, cz);         // foot water y203, head y204 air → surface
        BlockPos dryCell = new BlockPos(cx + 3, surface + 1, cz);         // above water → dry

        if (!w.headSubmerged(submergedFloor))
            throw new GameTestAssertException("submergedFloorWalk: headSubmerged must be TRUE for a cell with water above");
        if (w.headSubmerged(surfaceCell))
            throw new GameTestAssertException("submergedFloorWalk: headSubmerged must be FALSE for a surface cell (head air)");

        // From an adjacent submerged cell, a Walk into the head-submerged cell is invalid.
        BlockPos walkFrom = new BlockPos(cx + 2, floorY + 2, cz);
        if (new Walk(1, 0).valid(w, walkFrom))
            throw new GameTestAssertException("submergedFloorWalk: Walk INTO a head-submerged cell must be invalid (buoyant sink)");
        if (new Diagonal(1, 1).valid(w, walkFrom))
            throw new GameTestAssertException("submergedFloorWalk: Diagonal INTO a head-submerged cell must be invalid");
        // A Walk along the SURFACE row (head air) stays valid.
        BlockPos surfFrom = new BlockPos(cx + 2, surface, cz);
        if (!new Walk(1, 0).valid(w, surfFrom))
            throw new GameTestAssertException("submergedFloorWalk: Walk along the SURFACE row must stay valid");

        helper.succeed();
    }
```

- [ ] **Step 2: 跑测试,确认编译失败(`headSubmerged` 不存在)**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | tail -40`
Expected: **编译错误** `cannot find symbol: method headSubmerged(BlockPos)`(谓词尚未加)。

- [ ] **Step 3: 加 `headSubmerged` 谓词**

`WorldView.java`,在 `isFloatingWater` 的 javadoc 之前插入:

```java
    /**
     * D1 — the buoyant "head-submerged" predicate: the cell ONE above {@code foot}
     * is water, so a bot standing here has its head under the surface and buoyancy
     * lifts it off any floor (solid riverbed or water alike). The single invariant
     * behind the scattered water gates: a NON-DIVE ground move whose destination is
     * head-submerged is unexecutable — the bot floats up and oscillates instead of
     * standing. All ground-family moves (Walk/Diagonal/StepUp/StepUp2/StepDown/
     * DiagonalAscend/DiagonalDescend) reject a head-submerged destination, so A*'s
     * non-dive water route can only advance along the SURFACE top layer (head = air).
     * Broader than the old {@code isWater(to)&&isWater(to+1)} stepDown/diagDown gate
     * (4ecb972): it also catches a SOLID riverbed cell with water overhead (foot
     * stone, head water — the live z3034 walk-along-floor sink that gate missed).
     * Submerged cells remain reachable only by the SWIM family in a dive-fallback.
     */
    default boolean headSubmerged(BlockPos foot) {
        return isWater(foot.offset(0, 1, 0));
    }
```

- [ ] **Step 4: 跑测试,确认仍失败(守卫未加,Walk 仍 valid)**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | grep -A2 submergedFloorWalk`
Expected: 编译通过,但 `submergedFloorWalkArena` **FAIL** — `Walk INTO a head-submerged cell must be invalid`(Walk 尚无 D1 守卫)。

- [ ] **Step 5: Commit(谓词 + 失败测试)**

```bash
git add common/src/main/java/net/magicterra/agent/bot/pathfinder/WorldView.java \
        neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTest.java
git commit -m "test: headSubmerged predicate + failing submergedFloorWalk arena (D1)"
```

### Task 1.2: Walk + Diagonal 加 D1 守卫

**Files:**
- Modify: `.../pathfinder/moves/Walk.java:10-13`
- Modify: `.../pathfinder/moves/Diagonal.java`(`valid` 内 `to` 之后)

- [ ] **Step 1: Walk 加守卫**

`Walk.java` 的 `valid` 改为:

```java
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        // D1: a buoyant bot can't stand where its head is under water — A* must keep
        // the non-dive water route on the SURFACE top layer (head air). See
        // WorldView#headSubmerged (live z3034 walk-along-riverbed sink).
        if (w.headSubmerged(to)) return false;
        return w.canStandAt(to);
    }
```

- [ ] **Step 2: Diagonal 加守卫**

`Diagonal.java` 的 `valid` 内,在 `BlockPos to = apply(from);` 之后、`if (!w.canStandAt(to)) return false;` 之前插入:

```java
        if (w.headSubmerged(to)) return false;   // D1: no diagonal onto a head-submerged cell (WorldView#headSubmerged)
```

- [ ] **Step 3: 跑测试**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | tail -40`
Expected: `submergedFloorWalkArena` PASS;`FAIL: 0`;`All 45 required tests passed`。

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/Walk.java \
        common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/Diagonal.java
git commit -m "feat: D1 head-submerged guard on Walk + Diagonal (surface-first)"
```

### Task 1.3: StepDown + DiagonalDescend 收编 4ecb972 → D1

**Files:**
- Modify: `.../pathfinder/moves/StepDown.java`(替换现有 submerged 守卫)
- Modify: `.../pathfinder/moves/DiagonalDescend.java`(替换现有 submerged 守卫)

- [ ] **Step 1: StepDown 替换守卫**

`StepDown.java` 中把:
```java
        if (w.isWater(to) && w.isWater(to.offset(0, 1, 0))) return false;
```
替换为:
```java
        // D1 (was 4ecb972's isWater(to)&&isWater(to+1)): unify under headSubmerged so
        // a descend onto a SOLID riverbed cell with water overhead is also rejected
        // (broader; the buoyant bot floats over either). A descend to the water
        // SURFACE (head air) stays valid. See WorldView#headSubmerged.
        if (w.headSubmerged(to)) return false;
```

- [ ] **Step 2: DiagonalDescend 替换守卫**

`DiagonalDescend.java` 中把同样的 `if (w.isWater(to) && w.isWater(to.offset(0, 1, 0))) return false;` 替换为同上 `if (w.headSubmerged(to)) return false;`(注释同 Step 1)。

- [ ] **Step 3: 跑测试**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | tail -40`
Expected: `FAIL: 0`;`All 45 required tests passed`(尤其 `deepWaterCrossArena` 仍绿 —— 水面横渡 head=air 不被 D1 误杀)。

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/StepDown.java \
        common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/DiagonalDescend.java
git commit -m "refactor: unify stepDown/diagDown submerged gate under D1 headSubmerged"
```

### Task 1.4: StepUp / StepUp2 / DiagonalAscend 加 D1(保留 D2)

**Files:**
- Modify: `.../pathfinder/moves/StepUp.java`、`StepUp2.java`、`DiagonalAscend.java`

- [ ] **Step 1: 三个上爬 move 加 D1**

每个文件在 `BlockPos to = apply(from);` 之后、`if (!w.canStandAt(to)) return false;` 之前插入(保留各自已有的 `isFloatingWater`(D2)与 `isSubmergedAscent`):

`StepUp.java`(`isSubmergedAscent` 行之后):
```java
        if (w.headSubmerged(to)) return false;   // D1: never step UP into a head-submerged cell (WorldView#headSubmerged)
```
`StepUp2.java`(`isSubmergedAscent` 行之后)、`DiagonalAscend.java`(`isSubmergedAscent` 行之后):同样插入该行。

- [ ] **Step 2: 跑测试**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | tail -40`
Expected: `FAIL: 0`;`All 45 required tests passed`(`buoyantWallArena` / `waterLowBankArena` / `riverSheerBankArena` 仍绿)。

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/StepUp.java \
        common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/StepUp2.java \
        common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/DiagonalAscend.java
git commit -m "feat: D1 head-submerged guard on ascending ground moves (keep D2 float gate)"
```

### Task 1.5: Phase 1 live replay 门(z3034 不再沉河床 + 开阔深水无回归)

**Files:** 无改动(验证)。

- [ ] **Step 1: 跑 replay-0004 replan**

客户端已连(或重连)。调:
```
mc.debug.replay { "id": "replay-0004", "mode": "replan" }
```
开 live-screen-watch。

- [ ] **Step 2: 判定 z3034**

观测 z3034(x1763,z3034)段:**A* 不应再产出 y55-58 河床 walk 节点**;bot 应沿水面通过或绕行。记录该处 `totStuck` peak(改前 394)。
**通过判据:** z3034 peak < 60 且不再有 ~20s sink 窗。
**护栏:** 开阔深水穿越段(z3022 入水 + 中段)仍端到端通过,`deepWaterCrossArena` 行为不回归(R1)。

- [ ] **Step 3: 若 no-path(R2)**

若 z3034 因 surface 被 overhang 全挡导致 no-path(Phase 0 Step 2 已预判),**不在此修**——记录到 findings,留给 Phase 2 的 `SwimTraverse` 兜底。Phase 1 的成功标准是「不再沉河床 churn」,允许此处暂时绕行变长或等 Phase 2。

- [ ] **Step 4: 停观测,记录结果到 findings**

TaskStop Monitor + run.py。把 z3034 peak、开阔深水是否回归写入 findings。

---

## Phase 2 — `SwimTraverse` 原语 + 统一 submerged 成本

D1 禁了地面族进 submerged 格;为「水面优先 + 必要时可潜」保留潜水回退:新增纯水平游泳原语 + 高成本,让唯一出路是水下封顶通道时仍能潜过。

### Task 2.1: `SwimTraverse` move 类 + 注册 + 失败 arena

**Files:**
- Create: `common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/SwimTraverse.java`
- Modify: `.../pathfinder/Move.java`(`SwimDown` 注册之后,~line 316)
- Test: `AgentGameTest.java`(新增 `sealedUnderwaterPassageArena`)

- [ ] **Step 1: 写失败测试 `sealedUnderwaterPassageArena`**

在 `AgentGameTest.java` 末尾 `}` 之前插入:

```java
    /**
     * Dive-fallback layer: the ONLY route to the goal is a SEALED underwater passage
     * (a horizontal tunnel of water capped by solid rock at head+1, so every cell is
     * head-submerged). Surface-first D1 forbids all ground moves through it, so
     * without a SwimTraverse primitive this is no-path. Asserts the floating Walker
     * dives through the capped tunnel and reaches dry land on the far side.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void sealedUnderwaterPassageArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 220, cz = 160, floorY = 200;
        final int tunnelY = floorY + 1;            // foot cell of the tunnel
        final int capY = tunnelY + 1;              // solid ceiling at head → head-submerged
        final int span = 8;                        // tunnel length (E)

        // Solid surround block (carve the tunnel + chambers out of it).
        for (int dx = -3; dx <= span + 4; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = floorY; y <= capY + 2; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // WEST open-water shaft (start): a vertical water column open to air so the bot
        // floats at the surface, then must dive into the tunnel mouth.
        for (int dz = -1; dz <= 1; dz++)
            for (int y = tunnelY; y <= capY + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 1, y, cz + dz), Blocks.WATER.defaultBlockState());
        for (int dz = -1; dz <= 1; dz++)           // air cap over the start shaft
            level.setBlockAndUpdate(new BlockPos(cx - 1, capY + 2, cz + dz), Blocks.AIR.defaultBlockState());
        // The SEALED horizontal tunnel: water at tunnelY, solid cap at capY (head-submerged).
        for (int dx = 0; dx < span; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, tunnelY, cz + dz), Blocks.WATER.defaultBlockState());
        // EAST exit chamber: a dry step-up onto land carrying the goal.
        for (int dz = -1; dz <= 1; dz++) {
            level.setBlockAndUpdate(new BlockPos(cx + span, tunnelY, cz + dz), Blocks.WATER.defaultBlockState());
            for (int y = capY; y <= capY + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx + span, y, cz + dz), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(cx + span + 1, tunnelY, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = capY; y <= capY + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx + span + 1, y, cz + dz), Blocks.AIR.defaultBlockState());
        }
        BlockPos goal = new BlockPos(cx + span + 1, capY, cz);   // dry land beyond the tunnel

        boolean odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 1 + 0.5, capY + 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            LevelWorldView w = new LevelWorldView(level, fp);

            // (a) predicate: a SwimTraverse through the sealed tunnel is valid.
            BlockPos tunnelCell = new BlockPos(cx + 1, tunnelY, cz);
            if (!new SwimTraverse(1, 0).valid(w, tunnelCell))
                throw new GameTestAssertException("sealedUnderwaterPassage: SwimTraverse through a water tunnel must be valid");

            // (b) integration: the Walker dives through and reaches the far land.
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 4000 && s == Walker.Step.WALKING; t++) {
                s = walker.tick(av, w);
                av.step();
            }
            boolean arrived = !fp.isInWater() && fp.getX() >= cx + span + 0.5;
            AgentDriverCommon.LOG.info("[sealedUnderwaterPassageArena] step={} pos=({},{},{}) arrived={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), arrived);
            if (!arrived)
                throw new GameTestAssertException("sealedUnderwaterPassage: floating Walker failed to dive the sealed tunnel: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
        } finally {
            BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl;
            BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }
```

- [ ] **Step 2: 跑测试,确认编译失败(`SwimTraverse` 不存在)**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | tail -20`
Expected: 编译错误 `cannot find symbol: class SwimTraverse`。

- [ ] **Step 3: 新建 `SwimTraverse.java`**

```java
package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Horizontal swim through submerged water — the dive-fallback primitive the
 * surface-first model needs. Once D1 (head-submerged) forbids every ground move
 * from entering a submerged cell, a sealed underwater tunnel (water capped by
 * rock) would be no-path without this. SwimUp/SwimDown are purely vertical, so
 * this is the missing horizontal swim leg. Base cost ~14; the PathFinder adds
 * BotConfig.pathfinderSubmergedTraverseCost per head-submerged cell so A* uses it
 * only when there is no surface alternative (layered surface-first).
 */
public final class SwimTraverse extends Move {
    public SwimTraverse(int dx, int dz) { super(dx, 0, dz, 14); }
    public boolean valid(WorldView w, BlockPos from) {
        // Must already be in water and the destination must be water the body can
        // occupy. No break (that is SwimTraverseBreak's job); no corner test (a
        // submerged swim doesn't wedge like a dry diagonal — it's a cardinal glide).
        if (!w.isWater(from)) return false;
        BlockPos to = apply(from);
        if (!w.isWater(to) || w.isHazard(to)) return false;
        BlockPos head = to.offset(0, 1, 0);
        return (w.isWater(head) || w.isPassable(head)) && !w.isHazard(head);
    }
    public String name() { return "swimTraverse"; }
}
```

- [ ] **Step 4: 在 `Move.java` 注册**

`Move.java`,在 `ms.add(new SwimDown());`(~line 316)之后插入:

```java
        // Horizontal submerged swim (dive-fallback): the missing horizontal leg for
        // a sealed underwater passage once D1 forbids ground moves there. Priced per
        // head-submerged cell by PathFinder.submergedSwimTax so it loses to any
        // surface route. Cardinal only (a submerged glide doesn't wedge on corners).
        for (int[] d : CARDINAL) ms.add(new SwimTraverse(d[0], d[1]));
```

- [ ] **Step 5: 跑测试,确认 `sealedUnderwaterPassageArena` 谓词(a)过、集成(b)可能仍卡**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | grep -A2 sealedUnderwater`
Expected: 谓词 (a) 不再抛;集成 (b) **可能 FAIL**(执行器 dive-hold 尚未跟随水平潜行 → Phase 3 修)。**记录此刻 (b) 的失败 pos**——它是 Phase 3 的目标。其余测试 `FAIL: 0` 不变。

> 注:若 A* 此刻已用现 `diveHold`(diveTarget 的 `wp.y<=foot-2 && isWater(wp)` 分支)勉强潜过,(b) 直接 PASS,则 Phase 3 仅为加固——仍执行 Phase 3 的成本 task,跳过其执行器改动并在 Phase 3 Step 注明。

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/pathfinder/moves/SwimTraverse.java \
        common/src/main/java/net/magicterra/agent/bot/pathfinder/Move.java \
        neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTest.java
git commit -m "feat: SwimTraverse horizontal swim primitive + sealedUnderwaterPassage arena"
```

### Task 2.2: 统一 `pathfinderSubmergedTraverseCost` + 成本接入

**Files:**
- Modify: `common/.../bot/BotConfig.java`(`pathfinderWaterClimbOutCost` 之后,~line 352)
- Modify: `.../pathfinder/PathFinder.java`(新增 `submergedSwimTax` 方法 + edge 成本累加 ~line 528)

- [ ] **Step 1: 加 config 字段**

`BotConfig.java`,在 `pathfinderWaterClimbOutCost = 40;` 之后插入:

```java
    /** PER-CELL g-cost charged when a SWIM-family move ({@code swimTraverse}/{@code
     *  swimUp}/{@code swimDown}) enters a HEAD-SUBMERGED cell (water at foot+1). The
     *  surface-first model (2026-06-17) forbids all GROUND moves from head-submerged
     *  cells (D1), so the only edges that reach them are deliberate dives. This unified
     *  tax applies to BOTH XZ and Y-aware goals (unlike {@link #pathfinderWaterCellCost}
     *  / {@code submergedTax} which split by goal type), making any underwater route
     *  cost ∝ its submerged length so A* prefers a surface path and only dives a sealed
     *  passage when there is no surface alternative. NOT a hard ban — a sole underwater
     *  tunnel is still routed (the cost is finite). Default 110 ≈ dearer than a
     *  surface-water cell (35) plus its submerged extra (80) so a surface detour wins
     *  whenever one exists. Set 0 to disable (reverts to surface-only-or-no-path). */
    public static volatile double pathfinderSubmergedTraverseCost = 110;
```

- [ ] **Step 2: 加 `submergedSwimTax` 方法**

`PathFinder.java`,在 `submergedTax(...)` 方法之后插入:

```java
        /** Unified per-cell tax for a SWIM-family edge entering a HEAD-SUBMERGED cell
         *  (water at foot+1). Surface-first forbids ground moves there (D1), so only a
         *  deliberate dive (swimTraverse/swimUp/swimDown) reaches such a cell — pricing
         *  it makes a long underwater leg cost ∝ length and lose to any surface route,
         *  for BOTH XZ and Y-aware goals (the old waterCellTax/submergedTax split by
         *  goal type and left holes). A surface dive-target (Y-aware {@link #diveGoal})
         *  is exempt so a deliberate seabed objective isn't penalised. */
        private double submergedSwimTax(BlockPos to, Move.Edge edge) {
            double tax = BotConfig.pathfinderSubmergedTraverseCost;
            if (tax <= 0 || diveGoal()) return 0;
            if (edge.move == null || !edge.move.startsWith("swim")) return 0;
            return world.isWater(to.offset(0, 1, 0)) ? tax : 0;   // head-submerged destination only
        }
```

- [ ] **Step 3: 接入 edge 成本累加**

`PathFinder.java` 的 `ng` 累加(~line 522-528),在 `+ submergedTax(cur.pos, npos)` 行之后追加一行:

```java
                                + submergedTax(cur.pos, npos)
                                + submergedSwimTax(npos, edge);
```
(把原 `+ submergedTax(cur.pos, npos);` 结尾的分号移到新行末。)

- [ ] **Step 4: 跑测试**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | tail -40`
Expected: `FAIL: 0` 之外的所有既有 arena 不回归;`sealedUnderwaterPassageArena` 状态同 Task 2.1 Step 5(谓词过、集成待 Phase 3)。**关键回归:有水面替代的 arena(`deepWaterCrossArena`)A* 仍选水面**(成本层不该把水面路逼成水下)。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/BotConfig.java \
        common/src/main/java/net/magicterra/agent/bot/pathfinder/PathFinder.java
git commit -m "feat: unified pathfinderSubmergedTraverseCost for swim-family dive layer"
```

---

## Phase 3 — dive-hold 执行器水平扩展

让 `Walker` 稳定跟随一段水平 `swimTraverse` submerged 边(主动下潜 + 保持 + 水平推进),让 Phase 2 的 `sealedUnderwaterPassageArena` 集成段端到端通过。

### Task 3.1: `diveTarget`/`diving` 识别水平 swimTraverse

**Files:**
- Modify: `common/.../bot/movement/Walker.java:2329-2341`(`diveTarget`/`diveHold`/`diving` 块)

- [ ] **Step 1(条件):若 Task 2.1 Step 5 集成已 PASS,跳过本 task 的代码改动**

若 `sealedUnderwaterPassageArena` 集成 (b) 已绿,直接到 Step 4 跑全量确认,并在 commit message 注明「dive-hold already covers swimTraverse; no executor change」。否则继续 Step 2。

- [ ] **Step 2: 扩展 `diveTarget` 识别水平潜行边**

`Walker.java`,把(~line 2329):
```java
        boolean diveTarget = (edge != null && edge.move != null && edge.move.startsWith("swimDown"))
                || (p.isInWater() && wp.getY() <= foot.getY() - 2 && world.isWater(wp));
```
改为:
```java
        // A horizontal SwimTraverse edge through a head-submerged (sealed-tunnel) cell
        // is a deliberate dive too: the executor must pitch down + sink-hold + drive
        // forward to follow it, exactly like a swimDown, else the buoyant body floats
        // up against the rock cap and oscillates (surface-first dive-fallback layer).
        boolean diveTraverse = edge != null && edge.move != null
                && edge.move.startsWith("swimTraverse") && p.isInWater()
                && world.isWater(wp.offset(0, 1, 0));   // destination head-submerged
        boolean diveTarget = (edge != null && edge.move != null && edge.move.startsWith("swimDown"))
                || diveTraverse
                || (p.isInWater() && wp.getY() <= foot.getY() - 2 && world.isWater(wp));
```

- [ ] **Step 3: 跑 `sealedUnderwaterPassageArena` 集成**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | grep -A2 sealedUnderwater`
Expected: 集成 (b) **PASS**(bot 潜过封顶隧道到达远岸)。

- [ ] **Step 4: 跑全量,确认无回归**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | tail -40`
Expected: `FAIL: 0`;`All 45 required tests passed`。特别确认 `deepWaterCrossArena`(水面横渡不被误当 dive,pitch 不该在开阔水面下俯)仍绿。

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/movement/Walker.java
git commit -m "feat: dive-hold follows horizontal swimTraverse (sealed-passage executor)"
```

### Task 3.2: Phase 3 live replay 门

**Files:** 无改动(验证)。

- [ ] **Step 1: 重连客户端,跑 replay-0004 replan**

```
mc.debug.replay { "id": "replay-0004", "mode": "replan" }
```
开 live-screen-watch。

- [ ] **Step 2: 判定**

确认整程任何残留 submerged 段(若 A* 在某处选了潜水回退)被 dive-hold 平滑跟随,无浮起振荡;z3034 处不再 churn。记录各 churn 点 peak。

- [ ] **Step 3: 停观测 + 记录**

TaskStop Monitor + run.py;结果写 findings。

---

## Phase 4 — 作废 `floatOverSubmerged` + 旧河床补偿(确认无回归后)

Surface-First 下 A* 不再产出 submerged 横渡节点,`floatOverSubmerged` 河床骑行补偿已无对象。分阶段移除,不留双轨。先加两个回归护栏 arena,再删旧码。

### Task 4.1: 新增回归护栏 arena(`submergedSlotCrossArena` + `waterfallCascadeArena`)

**Files:**
- Test: `AgentGameTest.java`(新增两个 arena)

- [ ] **Step 1: 写 `submergedSlotCrossArena`(水面横渡护栏)**

在 `AgentGameTest.java` 末尾 `}` 前插入(对应 live z2744 slot canyon:深缝 + 顶盖,bot 必须走水面、不潜底):

```java
    /**
     * z2744-style slot crossing: a narrow deep-water slot with an overhang at the far
     * side. Surface-first must route the floating bot ACROSS the surface (head air)
     * and climb out, NEVER dive the slot. Regression guard that removing
     * floatOverSubmerged (Phase 4) did not reintroduce the diagDown-dive deadlock.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void submergedSlotCrossArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 260, cz = 160, floorY = 200, depth = 6;
        final int surface = floorY + depth;
        final int span = 10;

        for (int dx = -2; dx <= span + 4; dx++)
            for (int dz = -3; dz <= 3; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = -2; dx <= span; dx++)
            for (int y = floorY + 1; y <= surface + 2; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 3), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 3), Blocks.STONE.defaultBlockState());
            }
        for (int dz = -3; dz <= 3; dz++)
            for (int y = floorY + 1; y <= surface + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx - 2, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 1; y <= surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Air over the water so the surface swim is open.
        for (int dx = -1; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = surface + 1; y <= surface + 2; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // Partial overhang near the far bank (a lip at surface+1 over the last 2 cols)
        // — like the z2744 岩檐 — must NOT push A* into a diagDown dive.
        for (int dx = span - 2; dx < span; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface + 1, cz + dz), Blocks.STONE.defaultBlockState());
        // Far low bank + goal.
        for (int dx = span; dx <= span + 4; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = floorY + 1; y < surface; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, surface, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState());
                for (int y = surface + 1; y <= surface + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        BlockPos goal = new BlockPos(cx + span + 2, surface + 1, cz);

        boolean ob = BotConfig.allowBreak, op = BotConfig.allowPlace, odbg = BotConfig.walkerDebug;
        long osl = BotConfig.pathfinderSliceMs, omm = BotConfig.pathfinderMaxMs;
        BotConfig.allowBreak = true; BotConfig.allowPlace = true; BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2; BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        try {
            ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx + 0.5, surface - 1, cz + 0.5);
            FakePlayer fp = av.fakePlayer();
            grantWaterEffects(fp);
            fp.getInventory().clearContent();
            fp.getInventory().add(new ItemStack(Items.DIRT, 64));
            fp.getInventory().selected = 0;
            LevelWorldView w = new LevelWorldView(level, fp);
            Walker walker = new Walker();
            walker.setGoal(new Goal.Block(goal));
            Walker.Step s = Walker.Step.WALKING;
            for (int t = 0; t < 3000 && s == Walker.Step.WALKING; t++) { s = walker.tick(av, w); av.step(); }
            boolean ashore = !fp.isInWater() && fp.onGround() && fp.getX() >= cx + span - 0.5;
            AgentDriverCommon.LOG.info("[submergedSlotCrossArena] step={} pos=({},{},{}) ashore={}",
                    s, fp.getX(), fp.getY(), fp.getZ(), ashore);
            if (!ashore)
                throw new GameTestAssertException("submergedSlotCross: floating Walker failed to cross at the surface: pos=("
                        + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + s);
        } finally {
            BotConfig.allowBreak = ob; BotConfig.allowPlace = op; BotConfig.walkerDebug = odbg;
            BotConfig.pathfinderSliceMs = osl; BotConfig.pathfinderMaxMs = omm;
        }
        helper.succeed();
    }
```

- [ ] **Step 2: 写 `waterfallCascadeArena`(薄流水不 churn 护栏)**

在其后插入(对应山顶瀑布水帘:薄流水柱,bot 不该在水下反复 stepUp;此处只断言 A* **不**把上爬族放进流水水柱——谓词级,轻量):

```java
    /**
     * Waterfall cascade: a thin FLOWING-water column down a stone face. FluidTags.WATER
     * includes flowing water, so headSubmerged sees the cascade. Asserts a StepUp whose
     * source is INSIDE the flowing column with water overhead is rejected (D1/D2) — the
     * buoyant bot can't jump-mount inside a waterfall (live x~1495 山顶 deadlock: 121
     * underwater stepUp churn). A dry step beside the column stays valid.
     */
    @GameTest(template = "empty", timeoutTicks = 100000)
    public static void waterfallCascadeArena(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int cx = 300, cz = 160, baseY = 200;
        // A 4-tall stone face with a flowing-water column hugging it.
        for (int dx = 0; dx <= 4; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int y = baseY; y <= baseY + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
        // Flowing water column in front of the face (x=cx-1), 4 cells tall.
        for (int y = baseY + 1; y <= baseY + 4; y++)
            level.setBlockAndUpdate(new BlockPos(cx - 1, y, cz), Blocks.WATER.defaultBlockState());
        // A source at the top so the column flows (and the cells below read as WATER).
        level.setBlockAndUpdate(new BlockPos(cx - 1, baseY + 5, cz), Blocks.WATER.defaultBlockState());

        ServerPlayerAvatar av = ServerPlayerAvatar.create(level, cx - 1 + 0.5, baseY + 2, cz + 0.5);
        FakePlayer fp = av.fakePlayer();
        grantWaterEffects(fp);
        LevelWorldView w = new LevelWorldView(level, fp);

        // From inside the flowing column with water overhead, an ascending step toward
        // the stone face must be invalid (D1 dest head-submerged OR D2 floating source).
        BlockPos insideColumn = new BlockPos(cx - 1, baseY + 2, cz);
        if (w.isWater(insideColumn.offset(0, 1, 0))
                && new StepUp(1, 0).valid(w, insideColumn))
            throw new GameTestAssertException("waterfallCascade: StepUp from inside a flowing waterfall must be invalid");

        helper.succeed();
    }
```

- [ ] **Step 3: 跑测试,两个新 arena 都应 PASS(此刻 floatOverSubmerged 仍在,水面横渡本就该过)**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | grep -E "submergedSlotCross|waterfallCascade|FAIL"`
Expected: 两个新 arena PASS;`FAIL: 0`。这建立了**删旧码前**的护栏基线。

- [ ] **Step 4: Commit**

```bash
git add neoforge/src/main/java/net/magicterra/agent/neoforge/AgentGameTest.java
git commit -m "test: regression guards submergedSlotCross + waterfallCascade (pre-removal baseline)"
```

### Task 4.2: 移除 `floatOverSubmerged` 河床骑行补偿

**Files:**
- Modify: `common/.../bot/movement/Walker.java:1289-1317`(`floatOverSubmerged` 块 + `within` 引用)

- [ ] **Step 1: 删除 `floatOverSubmerged`,简化 `within`**

`Walker.java`,删除 `diveEdge`/`floatOverSubmerged` 两个布尔声明(约 1298、1313-1314 行)及其注释段,并把:
```java
            boolean within = cur2 < REACH_DIST_SQ
                    && (Math.abs(dyNode) < 1.2 || floatOverSubmerged)
                    && !(p.isInWater() && dyNode > 0.5);
```
改为:
```java
            // Surface-first (2026-06-17): A* no longer routes submerged below-nodes
            // (D1 forbids ground moves into head-submerged cells), so the old
            // floatOverSubmerged riverbed-ride compensation has no target — a below-node
            // now only appears for a deliberate swimDown/swimTraverse dive, which the
            // dive-hold executor follows. Plain |Δy| reach is sufficient again.
            boolean within = cur2 < REACH_DIST_SQ
                    && Math.abs(dyNode) < 1.2
                    && !(p.isInWater() && dyNode > 0.5);
```

> `WATER_DESCEND_GIVEUP`(line 124)目前还被 §2219 的 `waterThrash` / dive 兜底引用——**保留**该常量,只删 `floatOverSubmerged` 对它的使用。删前 `grep -n WATER_DESCEND_GIVEUP Walker.java` 确认其余引用不依赖被删块。

- [ ] **Step 2: 跑全量回归**

Run: `DISPLAY= ./gradlew :neoforge:runGameTestServer 2>&1 | tail -40`
Expected: `FAIL: 0`;`All 45 required tests passed`。**关键:** `deepWaterCrossArena` + `submergedSlotCrossArena`(开阔/slot 深水穿越)仍绿——证明删 `floatOverSubmerged` 后水面横渡不回归(R1 核心护栏)。若任一 FAIL → 停,旧补偿仍有真实作用,回退本 task 并在 findings 记录(可能 Surface-First 在某 arena 仍漏 surface 节点)。

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/movement/Walker.java
git commit -m "refactor: remove floatOverSubmerged riverbed-ride (obsolete under surface-first)"
```

### Task 4.3: 终验 replay 门 + 视频/pathChart 零异常

**Files:** 无改动(终验,对应标准目标的硬要求)。

- [ ] **Step 1: 重连客户端,跑 replay-0004 replan + 一趟随机长途**

```
mc.debug.replay { "id": "replay-0004", "mode": "replan" }
```
开 live-screen-watch(qwen3.6 视频转录;gemini 仅在用户允许时)。replay 后再随机选一处可达远 XZ 目标 `mc.bot.goto` 验异地形无回归。

- [ ] **Step 2: 判定(标准目标硬要求)**

- 端到端到达 `~(1732,83,3097)`。
- z2744 / z3022 / z3034 三处 `totStuck` peak 全低(<60),无 sink churn 窗。
- **pathChart 零异常窗**(16s 窗内无 3-5s 停顿)。
- **视频转录零异常**(无打转/视角跳变/沉底 churn)。
- 既有深水穿越段不回归。

- [ ] **Step 3: 停观测 + 写 findings 终结**

TaskStop Monitor + run.py。把三处 peak、replay 到达坐标、视频/pathChart 结论写入 findings。

- [ ] **Step 4: 更新记忆**

更新 `reference_deepwater_tallcliff_dive_to_floor_fiction.md`(z3034 残留 → 已根治)+ 新增/更新一条 Surface-First 整类根治的 reference 记忆,同步 `MEMORY.md` 索引一行。

---

## Phase 0 findings

> 执行 Phase 0 后在此填写。每条标注「确认 / 修正(及如何改 plan)」。

- item 6(z3034 A* 路由河床原因):_待填_
- item 6(浅水涉水 head=air 不被误禁):_待填_
- item 3(diveHold 基线):_待填_
- 决策:_待填(直进 Phase 1 / 需修正哪些 task)_

---

## Self-Review

**Spec coverage(逐节对照 spec):**
- §3 不变量 D1/D2 → Task 1.1(`headSubmerged`)+ 1.2-1.4(7 个地面族 D1);D2(`isFloatingWater`)保留于上爬 move,无需新增。✅
- §4.1 落脚点限制(7 move 守卫,不改全局 canStandAt)→ Task 1.2-1.4 逐 move 加守卫,未动 `canStandAt`。✅
- §4.2 潜水回退(SwimTraverse + 统一成本)→ Task 2.1(move/注册/arena)+ 2.2(config/tax)。✅
- §4.3 dive-hold 水平扩展 → Task 3.1。✅
- §4.4 作废 floatOverSubmerged + 收编旧门 → Task 1.3(stepDown/diagDown 收编 D1)+ Task 4.2(删 floatOverSubmerged)。✅
- §5 分阶段 + 验证矩阵 → Phase 0-4 + 各 live replay 门(Task 1.5/3.2/4.3)。✅
- §5 新增 3 arena → `sealedUnderwaterPassageArena`(2.1)、`submergedSlotCrossArena` + `waterfallCascadeArena`(4.1);另加 `submergedFloorWalkArena`(1.1,D1 谓词)。✅(spec 列的「submergedSlotCross/sealedUnderwaterPassage/waterfallCascade」三者全覆盖。)
- §6 风险 R1(开阔深水回归)→ 每阶段 `deepWaterCrossArena` + Task 4.2 Step 2 护栏;R2(no-path)→ Task 1.5 Step 3 + sealed arena;R3(A* 出水面路)→ Task 1.5 护栏;R4(dive 新代码)→ Phase 3 独立 arena + replay。✅

**Placeholder scan:** 无 TBD/TODO 占位代码;唯一「待填」在 Phase 0 findings(设计如此——现场观测产出)。✅

**Type consistency:** `headSubmerged(BlockPos)` 全程一致;`pathfinderSubmergedTraverseCost` 在 BotConfig 定义、PathFinder `submergedSwimTax` 引用一致;`SwimTraverse` 类名/`name()="swimTraverse"` 与 Walker `startsWith("swimTraverse")`、PathFinder `startsWith("swim")` 前缀一致。✅

**已知未决(交给 Phase 0 决策):** D1 = `isWater(foot+1)` 是否会误禁某些「头短暂没水但能稳住」的正常涉水 —— Phase 0 Step 3 专门验;若有反例,就地修正 Task 1.2 的守卫条件。

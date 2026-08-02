# StageWright P1a — stagewright 行走骨架 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 stagewright 多加载器项目骨架 + T0 harness（普通专服壳、自研串行调度）+ 最小编排器 + 金丝雀自测，在 neoforge 与 fabric 上各跑绿一条端到端竖切（2 个平凡场景 + 3 只金丝雀哨兵，编排器 JSONL 裁决）。

**Architecture:** stagewright 以三个新 gradle 子项目（`:stagewright-common/:stagewright-fabric/:stagewright-neoforge`，目录在 `stagewright/` 下）加入**现有** architectury 构建（root `subprojects{}` 自动提供 loom/mojmap/Java21）。游戏内：显式场景注册表 + tick 驱动的串行 harness（forceload 分配坐标格→执行 body→continuation steps→预算超时→JSONL 落盘→halt）。进程外：`scripts/stagewright/t0.py` 编排（预备 run 目录/eula/server.properties→gradle 起专服→墙钟看门狗→JSONL 对账+金丝雀裁决→退出码）。**P1a 的 testkit 对 worlddriver 零依赖**（仪表契约到 P1b 才接线），故骨架期两个 mod 互不加载。

**Tech Stack:** Java 21 / MC 1.21.1 mojmap / architectury-loom 1.11（沿用现构建）/ Fabric API（tick 与生命周期事件）/ NeoForge 21.1.230 / Python 3 标准库。

**Spec:** `docs/superpowers/specs/2026-07-16-stagewright-design.md` §2/§3(T0)/§5/§7 P1。
**Spec 偏差声明**：①spec §9 写「本仓库平级新项目」——本计划落成**同一 gradle 构建内的独立子项目组**（目录独立于 worlddriver 三模块，maven 坐标独立，将来可整体拆库）；理由=spec 同条自陈的「共享坑史与迭代速度」，且父目录不是 git 仓库、独立构建会失去版本控制。②P1 被拆为 P1a（本计划）/P1b（最小仪表契约+verb 接线）/P1c（dogfood 迁移彩票家族）三份独立交付的 plan——writing-plans scope check 要求。③`@SceneTest` 注解扫描按 spec §10 决议先用**显式注册表**，注解本身 P1a 不引入（YAGNI，注册表即单一真源，对账门吃注册表）。

## Global Constraints

- MC 1.21.1 / JDK 21 / mojmap；所有 Java API 均经 javap 实证（`setChunkForced(int,int,boolean)`、`LevelReader.hasChunkAt(BlockPos)`、`MinecraftServer.halt(boolean)`、`overworld()`）。
- ⛔ 禁 `pkill`；杀进程 = `ps` 列候选 → 显式 PID `kill`。testkit 专服 JVM 的 sweep 模式 = `[t]estkit.autorun`。
- 运行时输出不进 git：`stagewright/*/run-stagewright/` 需加 .gitignore；结果文件 `stagewright-results.jsonl` 落在 runDir。
- 无命名冲突不用 FQN（AGENTS.md 规则 7）。
- ⭐**探针教训（P0 事故 926396d）内建为契约**：harness 的 JSONL 写盘只发生在**场景边界**（场景 start 前/结束后），绝不在场景 RUN 期间的 tick 内做文件 IO；P1c 迁入确定性 dogfood arena 前必须复核此约束（必要时改异步 writer，先例 `GameTestManifest`）。
- **编排契约 v0 冻结**（Task 4 落文档，此后 gradle-plugin 复用）：JSONL schema、退出码 0/1/2/3、启动协议、文件位置——见 Task 4 契约文件全文。
- 专服 `server-port=25599`（避让 live 会话与默认 25565）；`level-type=minecraft\:flat`。
- mod id `mc_testkit`，包 `net.magicterra.stagewright`；testkit 代码 P1a 禁 import `net.magicterra.worlddriver.*`。
- 子代理跑 gradle 专服 run 的等待纪律：前台 Bash + 工具 timeout 参数（≤600000ms 一段，跑不完就再发一段）；**绝不带着自己的后台任务结束回合**。

---

### Task 1: Gradle 骨架 + mod 元数据 + 空入口（双 loader 编译绿）

**Files:**
- Modify: `settings.gradle`（尾部加 3 个 include + projectDir 映射）
- Modify: `.gitignore`（加 run-stagewright）
- Create: `stagewright/common/build.gradle`
- Create: `stagewright/fabric/build.gradle`
- Create: `stagewright/neoforge/build.gradle`
- Create: `stagewright/neoforge/gradle.properties`（单行 `loom.platform = neoforge`——architectury-loom 靠它在插件应用前认定平台并注册 `neoForge` 依赖配置；仓库 `neoforge/gradle.properties` 同款；fabric 是 loom 默认平台无需此文件。执行期 BLOCKED 实证补齐）
- Create: `stagewright/common/src/main/java/net/magicterra/stagewright/StageWrightCommon.java`（stub，Task 3 长大）
- Create: `stagewright/fabric/src/main/java/net/magicterra/stagewright/fabric/StageWrightFabric.java`
- Create: `stagewright/neoforge/src/main/java/net/magicterra/stagewright/neoforge/StageWrightNeoForge.java`
- Create: `stagewright/fabric/src/main/resources/fabric.mod.json`
- Create: `stagewright/neoforge/src/main/resources/META-INF/neoforge.mods.toml`

**Interfaces:**
- Consumes: root `build.gradle` 的 `subprojects{}`（自动施加 loom/architectury/mojmap/Java21/publishing——已核实其全文）。
- Produces: `:stagewright-common/:stagewright-fabric/:stagewright-neoforge` 三项目；`gradlew :stagewright-fabric:build :stagewright-neoforge:build` 绿；`runStageWrightServer` run 配置（两 loader，runDir `run-stagewright`，sysprop `stagewright.autorun=true`）；入口调用 `StageWrightCommon.onServerStarted(server, "<loader>")` / `StageWrightCommon.onServerTick(server)`（Task 3 的接线契约）。

- [ ] **Step 1: settings.gradle 尾部追加**

```gradle
include 'stagewright-common'
include 'stagewright-fabric'
include 'stagewright-neoforge'
project(':stagewright-common').projectDir = file('stagewright/common')
project(':stagewright-fabric').projectDir = file('stagewright/fabric')
project(':stagewright-neoforge').projectDir = file('stagewright/neoforge')
```

- [ ] **Step 2: .gitignore 追加一行**

```
run-stagewright/
```

- [ ] **Step 3: stagewright/common/build.gradle**

```gradle
architectury {
    common rootProject.enabled_platforms.split(',')
}

base {
    archivesName = 'mc_stagewright-common'
}

dependencies {
    // Provides the @Environment annotation; do NOT use other Fabric Loader classes from common
    modImplementation "net.fabricmc:fabric-loader:$rootProject.fabric_loader_version"
}
```

- [ ] **Step 4: stagewright/fabric/build.gradle**

```gradle
plugins {
    id 'com.gradleup.shadow'
}

architectury {
    platformSetupLoomIde()
    fabric()
}

base {
    archivesName = 'mc_stagewright-fabric'
}

configurations {
    common {
        canBeResolved = true
        canBeConsumed = false
    }
    compileClasspath.extendsFrom common
    runtimeClasspath.extendsFrom common
    developmentFabric.extendsFrom common

    shadowBundle {
        canBeResolved = true
        canBeConsumed = false
    }
}

dependencies {
    modImplementation "net.fabricmc:fabric-loader:$rootProject.fabric_loader_version"
    modImplementation "net.fabricmc.fabric-api:fabric-api:$rootProject.fabric_api_version"

    common(project(path: ':stagewright-common', configuration: 'namedElements')) { transitive false }
    shadowBundle project(path: ':stagewright-common', configuration: 'transformProductionFabric')
}

processResources {
    inputs.property 'version', project.version

    filesMatching('fabric.mod.json') {
        expand version: project.version
    }
}

shadowJar {
    configurations = [project.configurations.shadowBundle]
    archiveClassifier = 'dev-shadow'
}

remapJar {
    inputFile.set shadowJar.archiveFile
}

loom {
    runs {
        // T0 headless shell: a PLAIN dedicated server (no GameTestServer — its
        // scheduler is the disease the spec §3.1 removes by existence). The
        // testkit harness arms on -Dstagewright.autorun and halts the server when done.
        stagewrightServer {
            server()
            property 'stagewright.autorun', 'true'
            runDir 'run-stagewright'
        }
    }
    runConfigs.configureEach {
        vmArg '-Xmx1g'
    }
}
```

- [ ] **Step 5: stagewright/neoforge/build.gradle**

```gradle
plugins {
    id 'com.gradleup.shadow'
}

architectury {
    platformSetupLoomIde()
    neoForge()
}

base {
    archivesName = 'mc_stagewright-neoforge'
}

configurations {
    common {
        canBeResolved = true
        canBeConsumed = false
    }
    compileClasspath.extendsFrom common
    runtimeClasspath.extendsFrom common
    developmentNeoForge.extendsFrom common

    shadowBundle {
        canBeResolved = true
        canBeConsumed = false
    }
}

repositories {
    maven {
        name = 'NeoForged'
        url = 'https://maven.neoforged.net/releases'
    }
}

dependencies {
    neoForge "net.neoforged:neoforge:$rootProject.neoforge_version"

    common(project(path: ':stagewright-common', configuration: 'namedElements')) { transitive false }
    shadowBundle project(path: ':stagewright-common', configuration: 'transformProductionNeoForge')
}

processResources {
    inputs.property 'version', project.version

    filesMatching('META-INF/neoforge.mods.toml') {
        expand version: project.version
    }
}

shadowJar {
    configurations = [project.configurations.shadowBundle]
    archiveClassifier = 'dev-shadow'
}

remapJar {
    inputFile.set shadowJar.archiveFile
}

loom {
    runs {
        stagewrightServer {
            server()
            property 'stagewright.autorun', 'true'
            runDir 'run-stagewright'
        }
    }
    runConfigs.configureEach {
        vmArg '-Xmx1g'
    }
}
```

- [ ] **Step 6: fabric.mod.json**

```json
{
  "schemaVersion": 1,
  "id": "mc_testkit",
  "version": "${version}",
  "name": "stagewright",
  "description": "Cross-loader Minecraft mod test framework: scene harness, assertions, orchestrated topologies.",
  "authors": [
    "AI-assisted-Minecraft-Developers"
  ],
  "license": "MIT",
  "environment": "*",
  "entrypoints": {
    "main": [
      "net.magicterra.stagewright.fabric.StageWrightFabric"
    ]
  },
  "depends": {
    "fabricloader": ">=0.16",
    "fabric-api": "*",
    "minecraft": ">=1.21",
    "java": ">=21"
  }
}
```

- [ ] **Step 7: neoforge.mods.toml**

```toml
modLoader="javafml"
loaderVersion="[4,)"
license="MIT"

[[mods]]
modId="mc_testkit"
version="${version}"
displayName="stagewright"
authors="AI-assisted-Minecraft-Developers"
description='''Cross-loader Minecraft mod test framework: scene harness, assertions, orchestrated topologies.'''

[[dependencies.mc_testkit]]
    modId="neoforge"
    type="required"
    versionRange="[21,)"
    ordering="NONE"
    side="BOTH"

[[dependencies.mc_testkit]]
    modId="minecraft"
    type="required"
    versionRange="[1.21.1,1.22)"
    ordering="NONE"
    side="BOTH"
```

- [ ] **Step 8: StageWrightCommon.java（stub）**

```java
package net.magicterra.stagewright;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** Common core of stagewright. Loader entries forward server lifecycle + tick here. */
public final class StageWrightCommon {
    public static final String MOD_ID = "mc_testkit";
    public static final Logger LOG = LogUtils.getLogger();

    private StageWrightCommon() {}

    public static void onServerStarted(MinecraftServer server, String loader) {
        LOG.info("[{}] server started (loader={}, autorun={})", MOD_ID, loader,
                Boolean.getBoolean("stagewright.autorun"));
    }

    public static void onServerTick(MinecraftServer server) {
    }
}
```

- [ ] **Step 9: StageWrightFabric.java**

```java
package net.magicterra.stagewright.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.magicterra.stagewright.StageWrightCommon;

public final class StageWrightFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                StageWrightCommon.onServerStarted(server, "fabric"));
        ServerTickEvents.END_SERVER_TICK.register(StageWrightCommon::onServerTick);
    }
}
```

- [ ] **Step 10: StageWrightNeoForge.java**

```java
package net.magicterra.stagewright.neoforge;

import net.magicterra.stagewright.StageWrightCommon;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod("mc_testkit")
public final class StageWrightNeoForge {
    public StageWrightNeoForge() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        StageWrightCommon.onServerStarted(event.getServer(), "neoforge");
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        StageWrightCommon.onServerTick(event.getServer());
    }
}
```

- [ ] **Step 11: 编译两 loader**

Run: `./gradlew :stagewright-fabric:build :stagewright-neoforge:build -q`
Expected: BUILD SUCCESSFUL（首次会拉 loom 配置，几分钟）。

- [ ] **Step 12: Commit**

```bash
git add settings.gradle .gitignore stagewright/
git commit -m "feat(testkit): P1a skeleton — three subprojects, loader entries, stagewrightServer run configs"
```

---

### Task 2: 场景模型 + 初始场景注册表（2 平凡 + 3 金丝雀）

**Files:**
- Create: `stagewright/common/src/main/java/net/magicterra/stagewright/scene/SceneOutcome.java`
- Create: `stagewright/common/src/main/java/net/magicterra/stagewright/scene/Canary.java`
- Create: `stagewright/common/src/main/java/net/magicterra/stagewright/scene/Scene.java`
- Create: `stagewright/common/src/main/java/net/magicterra/stagewright/scene/SceneFailure.java`
- Create: `stagewright/common/src/main/java/net/magicterra/stagewright/scene/SceneContext.java`
- Create: `stagewright/common/src/main/java/net/magicterra/stagewright/scene/Scenes.java`

**Interfaces:**
- Consumes: 无（纯 common 模型层）。
- Produces（Task 3 harness 的消费契约）：`Scene(name, budgetTicks, required, canary, body)`；`Scenes.all()`；`SceneContext(level, origin)` + `runBody(Consumer)` 抛 `SceneFailure`、`advance()` 返回 `Progress {RUNNING, DONE, STEP_TIMEOUT}`、`ticks()`、`failureReason()`。

- [ ] **Step 1: SceneOutcome.java**

```java
package net.magicterra.stagewright.scene;

/** Terminal result of one scene. Wire values match the orchestration contract v0. */
public enum SceneOutcome {
    PASS, FAIL, TIMEOUT, ENV_FAIL
}
```

- [ ] **Step 2: Canary.java**

```java
package net.magicterra.stagewright.scene;

/**
 * Sentinel scenes that verify the framework can still CATCH failures (spec §5 金丝雀).
 * The orchestrator judges each run dead unless every canary lands on its expected
 * outcome: MUST_FAIL -> FAIL, MUST_TIMEOUT -> TIMEOUT, MUST_SWALLOW -> registered
 * in the suite header but deliberately never executed (no scene record) — the
 * reconciler must flag exactly that omission.
 */
public enum Canary {
    NONE, MUST_FAIL, MUST_TIMEOUT, MUST_SWALLOW
}
```

- [ ] **Step 3: Scene.java**

```java
package net.magicterra.stagewright.scene;

import java.util.function.Consumer;

/** One registered scene. Explicit registry (spec §10) — the suite header is dumped
 *  from this list, so registration and reconciliation share a single source. */
public record Scene(String name, int budgetTicks, boolean required, Canary canary,
                    Consumer<SceneContext> body) {
    public static Scene of(String name, int budgetTicks, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, Canary.NONE, body);
    }

    public static Scene canary(String name, int budgetTicks, Canary kind, Consumer<SceneContext> body) {
        return new Scene(name, budgetTicks, true, kind, body);
    }
}
```

- [ ] **Step 4: SceneFailure.java**

```java
package net.magicterra.stagewright.scene;

/** Assertion/explicit failure raised inside a scene body or step. */
public final class SceneFailure extends RuntimeException {
    public SceneFailure(String message) { super(message); }
}
```

- [ ] **Step 5: SceneContext.java**

```java
package net.magicterra.stagewright.scene;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Per-scene handle: origin-relative world ops, assertions, and tick-continuation
 * steps. The body runs ONCE (synchronously) on the scene's first tick — it builds
 * the arena, asserts immediate state, and registers await-steps; the harness then
 * calls advance() every tick until DONE / STEP_TIMEOUT / budget exhaustion.
 * Bodies must never block or sleep (same rule as every in-game test in this repo).
 */
public final class SceneContext {
    /** One pending continuation: wait for cond (within N ticks of becoming current), then run. */
    private record Step(BooleanSupplier cond, int withinTicks, Runnable then) {}

    public enum Progress { RUNNING, DONE, STEP_TIMEOUT }

    private final ServerLevel level;
    private final BlockPos origin;
    private final Deque<Step> steps = new ArrayDeque<>();
    private int ticks;
    private int currentStepTicks;
    private String failureReason;

    public SceneContext(ServerLevel level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
    }

    // ---- world ops (origin-relative; scenes never see absolute coordinates) ----

    public BlockPos rel(int dx, int dy, int dz) {
        return origin.offset(dx, dy, dz);
    }

    public void setBlock(int dx, int dy, int dz, Block block) {
        level.setBlockAndUpdate(rel(dx, dy, dz), block.defaultBlockState());
    }

    /** size x size stone-slab floor at dy=0, cleared air 4 above — the minimal clean pad. */
    public void floor(int size, Block block) {
        int half = size / 2;
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++) {
                setBlock(dx, 0, dz, block);
                for (int dy = 1; dy <= 4; dy++) setBlock(dx, dy, dz, Blocks.AIR);
            }
    }

    // ---- assertions ----

    public void assertBlock(int dx, int dy, int dz, Block expected) {
        Block actual = level.getBlockState(rel(dx, dy, dz)).getBlock();
        if (actual != expected) {
            throw new SceneFailure("block at rel(" + dx + "," + dy + "," + dz + ") is "
                    + actual + ", expected " + expected);
        }
    }

    public void fail(String reason) {
        throw new SceneFailure(reason);
    }

    // ---- continuation steps ----

    public AwaitBuilder await(BooleanSupplier cond) {
        return new AwaitBuilder(cond);
    }

    public final class AwaitBuilder {
        private final BooleanSupplier cond;
        private int within = 100;

        private AwaitBuilder(BooleanSupplier cond) { this.cond = cond; }

        public AwaitBuilder within(int ticksBudget) { this.within = ticksBudget; return this; }

        public void then(Runnable action) { steps.addLast(new Step(cond, within, action)); }
    }

    // ---- harness-side driving ----

    /** Run the body once; SceneFailure propagates to the harness as FAIL. */
    public void runBody(Consumer<SceneContext> body) {
        body.accept(this);
    }

    /** One tick of step processing. Greedy: consume every step whose cond is already true. */
    public Progress advance() {
        ticks++;
        while (!steps.isEmpty()) {
            Step head = steps.peekFirst();
            if (head.cond().getAsBoolean()) {
                steps.pollFirst();
                currentStepTicks = 0;
                head.then().run();               // SceneFailure propagates to the harness
                continue;
            }
            currentStepTicks++;
            if (currentStepTicks > head.withinTicks()) {
                failureReason = "await step exceeded within=" + head.withinTicks() + " ticks";
                return Progress.STEP_TIMEOUT;
            }
            return Progress.RUNNING;
        }
        return Progress.DONE;
    }

    public int ticks() { return ticks; }

    public String failureReason() { return failureReason; }
}
```

- [ ] **Step 6: Scenes.java（初始注册表：2 平凡 + 3 金丝雀）**

```java
package net.magicterra.stagewright.scene;

import java.util.List;
import net.minecraft.world.level.block.Blocks;

/**
 * The explicit scene registry — the single source both the harness executes from
 * and the suite header (reconciliation side) is dumped from. Order = execution order.
 */
public final class Scenes {
    private Scenes() {}

    public static List<Scene> all() {
        return List.of(
                // -- walking-skeleton scenes --
                Scene.of("floorAssert", 100, ctx -> {
                    ctx.floor(5, Blocks.STONE);
                    ctx.assertBlock(0, 0, 0, Blocks.STONE);
                    ctx.assertBlock(-2, 0, -2, Blocks.STONE);
                    ctx.assertBlock(2, 0, 2, Blocks.STONE);
                    ctx.assertBlock(0, 1, 0, Blocks.AIR);
                }),
                Scene.of("awaitTicks", 200, ctx -> {
                    ctx.setBlock(0, 0, 0, Blocks.STONE);
                    ctx.await(() -> ctx.ticks() >= 40).within(100).then(() -> {
                        if (ctx.ticks() < 40) ctx.fail("await fired before its condition held");
                        ctx.assertBlock(0, 0, 0, Blocks.STONE);
                    });
                }),
                // -- canaries (spec §5): the framework must CATCH these, or the gate is dead --
                Scene.canary("canaryMustFail", 100, Canary.MUST_FAIL,
                        ctx -> ctx.fail("canary: this scene must be reported as FAIL")),
                Scene.canary("canaryMustTimeout", 60, Canary.MUST_TIMEOUT,
                        ctx -> ctx.await(() -> false).within(40).then(() -> {})),
                Scene.canary("canaryMustSwallow", 100, Canary.MUST_SWALLOW,
                        ctx -> { /* never executed by design; the harness skips it */ })
        );
    }
}
```

- [ ] **Step 7: 编译**

Run: `./gradlew :stagewright-common:build -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add stagewright/common/src/main/java/net/magicterra/stagewright/scene/
git commit -m "feat(testkit): scene model — origin-relative context, await continuations, explicit registry with canaries"
```

---

### Task 3: T0 Harness + JSONL 结果 + 入口接线（neoforge 手动烟囱）

**Files:**
- Create: `stagewright/common/src/main/java/net/magicterra/stagewright/harness/ResultsJsonl.java`
- Create: `stagewright/common/src/main/java/net/magicterra/stagewright/harness/StageWrightHarness.java`
- Modify: `stagewright/common/src/main/java/net/magicterra/stagewright/StageWrightCommon.java`（stub → 实体）

**Interfaces:**
- Consumes: Task 2 的 Scene/Scenes/SceneContext 契约；Task 1 的入口转发。
- Produces: runDir 下 `stagewright-results.jsonl`（**契约 v0**，Task 4 编排器的解析对象）：
  - 头记录 `{"type":"suite","loader":"<loader>","registered":[{"name":..,"required":..,"canary":"NONE|MUST_FAIL|MUST_TIMEOUT|MUST_SWALLOW"}...]}`
  - 场景记录 `{"type":"scene","name":..,"outcome":"PASS|FAIL|TIMEOUT|ENV_FAIL","ticks":n,"wallMs":n,"reason":"..."}`（reason 仅非 PASS 时非空；MUST_SWALLOW 金丝雀**没有**场景记录——这就是它的哨兵语义）
  - 尾记录 `{"type":"done","scenes":n}`（缺尾 = harness 中途死亡 = RED）
- 场景边界写盘契约（Global Constraints 的探针教训条目）在 ResultsJsonl 类 javadoc 声明。

- [ ] **Step 1: ResultsJsonl.java**

```java
package net.magicterra.stagewright.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneOutcome;

/**
 * Orchestration-contract-v0 results stream, one JSON object per line, written into
 * the server's working directory (the loom runDir).
 *
 * TIMING CONTRACT (P0 probe incident, worlddriver commit 926396d): file IO here
 * happens ONLY at scene boundaries — suite start, after a scene completes, suite
 * end. Never write during a scene's RUN ticks: synchronous server-thread IO
 * measurably broke a byte-deterministic arena once already. Boundary writes still
 * shift wall-clock for the NEXT scene; before hosting determinism-sensitive
 * dogfood arenas (P1c) this must be revisited (async writer precedent:
 * worlddriver GameTestManifest).
 *
 * Names/reasons are escaped minimally (quote+backslash) — scene names are Java
 * identifiers, reasons are free text we generate ourselves.
 */
public final class ResultsJsonl {
    private final Path file;

    public ResultsJsonl(Path file) {
        this.file = file;
    }

    public void writeSuiteHeader(String loader, List<Scene> scenes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"suite\",\"loader\":\"").append(loader).append("\",\"registered\":[");
        for (int i = 0; i < scenes.size(); i++) {
            Scene s = scenes.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(s.name())
              .append("\",\"required\":").append(s.required())
              .append(",\"canary\":\"").append(s.canary()).append("\"}");
        }
        sb.append("]}\n");
        write(sb.toString(), true);
    }

    public void writeScene(String name, SceneOutcome outcome, int ticks, long wallMs, String reason) {
        write("{\"type\":\"scene\",\"name\":\"" + name + "\",\"outcome\":\"" + outcome
                + "\",\"ticks\":" + ticks + ",\"wallMs\":" + wallMs
                + ",\"reason\":\"" + escape(reason == null ? "" : reason) + "\"}\n", false);
    }

    public void writeDone(int scenes) {
        write("{\"type\":\"done\",\"scenes\":" + scenes + "}\n", false);
    }

    private void write(String line, boolean truncate) {
        try {
            if (truncate) {
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } else {
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            // A broken results stream must never read as green — fail the run loudly;
            // the orchestrator's missing-footer rule turns this into RED regardless.
            throw new UncheckedIOException("cannot write testkit results", e);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

- [ ] **Step 2: StageWrightHarness.java**

```java
package net.magicterra.stagewright.harness;

import java.util.List;
import net.magicterra.stagewright.StageWrightCommon;
import net.magicterra.stagewright.scene.Canary;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneFailure;
import net.magicterra.stagewright.scene.SceneOutcome;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Serial T0 scheduler on a PLAIN dedicated server. One scene at a time, each on
 * its own grid-allocated origin in force-loaded chunks; per-scene tick budget;
 * results to the contract-v0 JSONL; halts the server when the registry is drained.
 *
 * Grid allocation: origin i = (GRID_X0 + i*GRID_STEP, GRID_Y, GRID_Z0), far from
 * spawn so a flat world's spawn chunks never overlap an arena. Chunks are
 * force-loaded for the scene's lifetime and released afterwards — serial
 * execution + per-scene origins is the whole isolation story at P1a (no shared
 * body yet; body reset arrives with dogfood migration).
 */
public final class StageWrightHarness {
    private static final int GRID_X0 = 100_000;
    private static final int GRID_Z0 = 100_000;
    private static final int GRID_Y = 200;
    private static final int GRID_STEP = 512;
    private static final int PREP_BUDGET_TICKS = 200;

    private enum Phase { PREP, RUN, ADVANCE_DONE }

    private final MinecraftServer server;
    private final List<Scene> scenes;
    private final ResultsJsonl out;

    private int index;
    private Phase phase = Phase.PREP;
    private int phaseTicks;
    private SceneContext ctx;
    private long sceneStartMs;
    private boolean finished;

    public StageWrightHarness(MinecraftServer server, String loader, List<Scene> scenes, ResultsJsonl out) {
        this.server = server;
        this.scenes = scenes;
        this.out = out;
        out.writeSuiteHeader(loader, scenes);
        StageWrightCommon.LOG.info("[{}] harness armed: {} scenes", StageWrightCommon.MOD_ID, scenes.size());
    }

    public void tick() {
        if (finished) return;
        if (index >= scenes.size()) { finish(); return; }

        Scene scene = scenes.get(index);
        if (scene.canary() == Canary.MUST_SWALLOW) {
            // Deliberately never executed and never recorded: the orchestrator must
            // flag exactly this omission, proving the swallow gate is alive (spec §5).
            StageWrightCommon.LOG.info("[{}] skipping swallow-canary '{}'", StageWrightCommon.MOD_ID, scene.name());
            nextScene();
            return;
        }

        ServerLevel level = server.overworld();
        BlockPos origin = originFor(index);

        switch (phase) {
            case PREP -> {
                if (phaseTicks == 0) {
                    forceChunks(level, origin, true);
                    sceneStartMs = System.currentTimeMillis();
                }
                phaseTicks++;
                if (level.hasChunkAt(origin)) {
                    ctx = new SceneContext(level, origin);
                    phase = Phase.RUN;
                    phaseTicks = 0;
                } else if (phaseTicks > PREP_BUDGET_TICKS) {
                    record(scene, SceneOutcome.ENV_FAIL, 0, "arena chunks not loaded within "
                            + PREP_BUDGET_TICKS + " ticks");
                    teardown(level, origin);
                }
            }
            case RUN -> {
                phaseTicks++;
                try {
                    if (phaseTicks == 1) ctx.runBody(scene.body());
                    SceneContext.Progress p = ctx.advance();
                    if (p == SceneContext.Progress.DONE) {
                        record(scene, SceneOutcome.PASS, ctx.ticks(), null);
                        teardown(level, origin);
                    } else if (p == SceneContext.Progress.STEP_TIMEOUT) {
                        record(scene, SceneOutcome.TIMEOUT, ctx.ticks(), ctx.failureReason());
                        teardown(level, origin);
                    } else if (ctx.ticks() > scene.budgetTicks()) {
                        record(scene, SceneOutcome.TIMEOUT, ctx.ticks(),
                                "scene budget " + scene.budgetTicks() + " ticks exhausted");
                        teardown(level, origin);
                    }
                } catch (SceneFailure f) {
                    record(scene, SceneOutcome.FAIL, ctx.ticks(), f.getMessage());
                    teardown(level, origin);
                } catch (Throwable t) {
                    record(scene, SceneOutcome.FAIL, ctx.ticks(),
                            "unexpected " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    teardown(level, origin);
                }
            }
            case ADVANCE_DONE -> nextScene();
        }
    }

    private void record(Scene scene, SceneOutcome outcome, int ticks, String reason) {
        long wallMs = System.currentTimeMillis() - sceneStartMs;
        StageWrightCommon.LOG.info("[{}] scene '{}' -> {} ({} ticks, {} ms){}", StageWrightCommon.MOD_ID,
                scene.name(), outcome, ticks, wallMs, reason == null ? "" : " — " + reason);
        out.writeScene(scene.name(), outcome, ticks, wallMs, reason);
        phase = Phase.ADVANCE_DONE;
    }

    private void teardown(ServerLevel level, BlockPos origin) {
        forceChunks(level, origin, false);
    }

    private void nextScene() {
        index++;
        phase = Phase.PREP;
        phaseTicks = 0;
        ctx = null;
        if (index >= scenes.size()) finish();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        long executed = scenes.stream().filter(s -> s.canary() != Canary.MUST_SWALLOW).count();
        out.writeDone((int) executed);
        StageWrightCommon.LOG.info("[{}] suite complete ({} scenes executed) — halting server",
                StageWrightCommon.MOD_ID, executed);
        server.halt(false);
    }

    private static BlockPos originFor(int i) {
        return new BlockPos(GRID_X0 + i * GRID_STEP, GRID_Y, GRID_Z0);
    }

    private static void forceChunks(ServerLevel level, BlockPos origin, boolean force) {
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setChunkForced(cx + dx, cz + dz, force);
    }
}
```

- [ ] **Step 3: StageWrightCommon.java（实体版，整文件替换）**

```java
package net.magicterra.stagewright;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import net.magicterra.stagewright.harness.ResultsJsonl;
import net.magicterra.stagewright.harness.StageWrightHarness;
import net.magicterra.stagewright.scene.Scenes;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** Common core of stagewright. Loader entries forward server lifecycle + tick here. */
public final class StageWrightCommon {
    public static final String MOD_ID = "mc_testkit";
    public static final Logger LOG = LogUtils.getLogger();

    /** Results file, relative to the server's working directory (the loom runDir). */
    private static final String OUT_FILE = "stagewright-results.jsonl";

    private static volatile StageWrightHarness harness;

    private StageWrightCommon() {}

    public static void onServerStarted(MinecraftServer server, String loader) {
        if (!Boolean.getBoolean("stagewright.autorun")) {
            LOG.info("[{}] present but idle (stagewright.autorun not set)", MOD_ID);
            return;
        }
        harness = new StageWrightHarness(server, loader, Scenes.all(), new ResultsJsonl(Path.of(OUT_FILE)));
    }

    public static void onServerTick(MinecraftServer server) {
        StageWrightHarness h = harness;
        if (h != null) h.tick();
    }
}
```

- [ ] **Step 4: 编译**

Run: `./gradlew :stagewright-fabric:build :stagewright-neoforge:build -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 手动预备 neoforge run 目录（编排器 Task 4 才自动化这步）**

Run:
```bash
mkdir -p stagewright/neoforge/run-stagewright
printf 'eula=true\n' > stagewright/neoforge/run-stagewright/eula.txt
printf 'server-port=25599\nlevel-type=minecraft\\:flat\nonline-mode=false\nspawn-protection=0\nsync-chunk-writes=false\nmotd=stagewright T0\n' > stagewright/neoforge/run-stagewright/server.properties
```

- [ ] **Step 6: 烟囱跑（等待纪律：前台 Bash，工具 timeout 590000）**

Run: `timeout 540 ./gradlew :stagewright-neoforge:runStageWrightServer 2>&1 | tail -25`
Expected: 日志含 `harness armed: 5 scenes`、`skipping swallow-canary`、4 条 `scene '...' ->` 行、`suite complete (4 scenes executed) — halting server`；进程自行退出（halt 是正常停机，gradle 退出码 0）。

- [ ] **Step 7: 检查 JSONL**

Run: `cat stagewright/neoforge/run-stagewright/stagewright-results.jsonl`
Expected: 1 条 suite 头（registered 5 项）；4 条 scene（floorAssert=PASS、awaitTicks=PASS、canaryMustFail=FAIL、canaryMustTimeout=TIMEOUT）；无 canaryMustSwallow 行；1 条 done(scenes=4)。任何偏差=本 Task 失败，逐项修到符合。

- [ ] **Step 8: Commit**

```bash
git add stagewright/common/src/main/java/net/magicterra/stagewright/
git commit -m "feat(testkit): T0 harness — serial scheduler, grid forceload, budgets, contract-v0 JSONL, autorun+halt"
```

---

### Task 4: 编排器 t0.py（self-test 先行）+ 契约 v0 冻结

**Files:**
- Create: `scripts/stagewright/t0.py`
- Create: `docs/stagewright/orchestration-contract-v0.md`

**Interfaces:**
- Consumes: Task 3 的 JSONL 契约；loom run `:testkit-<loader>:runStageWrightServer`。
- Produces: `python3 scripts/stagewright/t0.py --loader neoforge|fabric [--wall N]`，退出码 **0**=绿 / **1**=测试失败或对账失败 / **2**=门死（金丝雀判错）/ **3**=环境失败（起不来/缺产物）；`--self-test` 内嵌 fixture。**契约文档 v0 从此冻结**——gradle-plugin（P3）按同一契约实现。

- [ ] **Step 1: 写 t0.py（完整）**

```python
#!/usr/bin/env python3
"""stagewright T0 orchestrator (contract v0).

Provisions the loader's run-stagewright dir, launches the plain dedicated server run
(:testkit-<loader>:runStageWrightServer, armed by -Dstagewright.autorun), wall-caps it,
then judges stagewright-results.jsonl:

  exit 0  GREEN  — footer present, registered==executed (swallow-canaries excepted),
                   every canary on its expected outcome, every non-canary PASS
  exit 1  RED    — a non-canary scene failed/timed out, or reconciliation failed
  exit 2  DEAD   — a canary landed on the WRONG outcome: the framework can no longer
                   catch failures; the whole run's results are void (spec §5)
  exit 3  ENV    — launch failed / results file missing / no suite header

The orchestrator is the verdict authority; the server process exit code is NOT
consulted (halt() exits 0 regardless of scene outcomes).
"""
import argparse
import json
import os
import shutil
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CANARY_EXPECT = {"MUST_FAIL": "FAIL", "MUST_TIMEOUT": "TIMEOUT"}


def run_dir(loader):
    return os.path.join(REPO_ROOT, "stagewright", loader, "run-stagewright")


def provision(loader):
    d = run_dir(loader)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(d, "server.properties"), "w") as f:
        f.write("server-port=25599\nlevel-type=minecraft\\:flat\nonline-mode=false\n"
                "spawn-protection=0\nsync-chunk-writes=false\nmotd=stagewright T0\n")
    shutil.rmtree(os.path.join(d, "world"), ignore_errors=True)
    results = os.path.join(d, "stagewright-results.jsonl")
    if os.path.exists(results):
        os.remove(results)
    return results


def sweep():
    """Kill leftover testkit server JVMs by explicit PID (pkill is banned)."""
    out = subprocess.run(["ps", "-eo", "pid,args"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "stagewright.autorun" in line and "java" in line:
            pid = line.strip().split()[0]
            print(f"[t0] killing leftover testkit JVM pid={pid}")
            subprocess.run(["kill", "-9", pid])


def launch(loader, wall, results):
    """Launch the server run and wait for the DONE FOOTER, not for gradle.

    Task-3 smoke finding: after the harness halt()s the server, the game JVM
    exits cleanly in seconds but the gradle run task does NOT return control.
    So gradle's exit is neither awaited as the happy path nor consulted for the
    verdict (contract v0): we poll the results file for the done footer, give a
    short grace for final writes, then sweep whatever is left and move to judge.
    """
    import time
    cmd = ["./gradlew", f":testkit-{loader}:runStageWrightServer"]
    print(f"[t0] launching: {' '.join(cmd)} (wall={wall}s, waiting on done footer)")
    proc = subprocess.Popen(cmd, cwd=REPO_ROOT,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + wall
    footer = False
    while time.monotonic() < deadline:
        if os.path.exists(results):
            with open(results, encoding="utf-8", errors="replace") as f:
                if '"type":"done"' in f.read():
                    footer = True
                    break
        if proc.poll() is not None:
            break  # gradle actually returned (crash or clean) — judge whatever exists
        time.sleep(2)
    if footer:
        print("[t0] done footer observed — reaping the run")
        time.sleep(3)  # grace for file flush + server teardown
    else:
        print(f"[t0] no done footer within {wall}s — wall timeout")
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
    sweep()
    return 0 if footer else 124


def judge(lines):
    """Pure verdict from parsed JSONL lines. Returns (exit_code, report_lines)."""
    report = []
    suite, done, scenes = None, None, {}
    for rec in lines:
        if rec["type"] == "suite":
            suite = rec
        elif rec["type"] == "scene":
            scenes[rec["name"]] = rec
        elif rec["type"] == "done":
            done = rec
    if suite is None:
        return 3, ["no suite header — server never armed"]
    if done is None:
        return 1, ["no done footer — harness died mid-run"]

    code = 0
    for reg in suite["registered"]:
        name, canary = reg["name"], reg["canary"]
        rec = scenes.get(name)
        if canary == "MUST_SWALLOW":
            if rec is not None:
                return 2, [f"DEAD: swallow-canary '{name}' was executed — skip gate broken"]
            report.append(f"canary '{name}': correctly omitted (swallow gate alive)")
        elif canary in CANARY_EXPECT:
            if rec is None:
                return 2, [f"DEAD: canary '{name}' has no record — catch gate broken"]
            if rec["outcome"] != CANARY_EXPECT[canary]:
                return 2, [f"DEAD: canary '{name}' -> {rec['outcome']}, expected {CANARY_EXPECT[canary]}"]
            report.append(f"canary '{name}': caught as {rec['outcome']} (expected)")
        else:
            if rec is None:
                code = max(code, 1)
                report.append(f"SWALLOWED: '{name}' registered but never recorded")
            elif rec["outcome"] != "PASS":
                if reg["required"]:
                    code = max(code, 1)
                report.append(f"{'FAIL' if reg['required'] else 'fail(optional)'}: "
                              f"'{name}' -> {rec['outcome']} — {rec.get('reason', '')}")
            else:
                report.append(f"pass: '{name}' ({rec['ticks']} ticks, {rec['wallMs']} ms)")
    drifted = set(scenes) - {r["name"] for r in suite["registered"]}
    if drifted:
        code = max(code, 1)
        report.append(f"DRIFTED: records for unregistered names {sorted(drifted)}")
    return code, report


def parse(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


# ---- embedded self-test fixtures ----

F_SUITE = {"type": "suite", "loader": "x", "registered": [
    {"name": "a", "required": True, "canary": "NONE"},
    {"name": "cf", "required": True, "canary": "MUST_FAIL"},
    {"name": "ct", "required": True, "canary": "MUST_TIMEOUT"},
    {"name": "cs", "required": True, "canary": "MUST_SWALLOW"}]}


def _scene(name, outcome):
    return {"type": "scene", "name": name, "outcome": outcome, "ticks": 1, "wallMs": 1, "reason": ""}


F_DONE = {"type": "done", "scenes": 3}
F_GREEN = [F_SUITE, _scene("a", "PASS"), _scene("cf", "FAIL"), _scene("ct", "TIMEOUT"), F_DONE]


def self_test():
    checks = [
        ("green run -> 0", judge(F_GREEN)[0] == 0),
        ("real scene FAIL -> 1",
         judge([F_SUITE, _scene("a", "FAIL"), _scene("cf", "FAIL"), _scene("ct", "TIMEOUT"), F_DONE])[0] == 1),
        ("real scene swallowed -> 1",
         judge([F_SUITE, _scene("cf", "FAIL"), _scene("ct", "TIMEOUT"), F_DONE])[0] == 1),
        ("canary wrong outcome -> 2 DEAD",
         judge([F_SUITE, _scene("a", "PASS"), _scene("cf", "PASS"), _scene("ct", "TIMEOUT"), F_DONE])[0] == 2),
        ("swallow-canary executed -> 2 DEAD",
         judge(F_GREEN[:-1] + [_scene("cs", "PASS"), F_DONE])[0] == 2),
        ("missing footer -> 1",
         judge([F_SUITE, _scene("a", "PASS"), _scene("cf", "FAIL"), _scene("ct", "TIMEOUT")])[0] == 1),
        ("missing header -> 3",
         judge([_scene("a", "PASS")])[0] == 3),
        ("drifted record -> 1",
         judge([F_SUITE, _scene("a", "PASS"), _scene("cf", "FAIL"), _scene("ct", "TIMEOUT"),
                _scene("ghost", "PASS"), F_DONE])[0] == 1),
    ]
    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {n}")
    return 0 if not failed else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--loader", choices=["neoforge", "fabric"])
    ap.add_argument("--wall", type=int, default=900)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        sys.exit(self_test())
    if not args.loader:
        ap.error("--loader is required (or use --self-test)")

    sweep()
    results = provision(args.loader)
    rc = launch(args.loader, args.wall, results)
    print(f"[t0] launch rc={rc} (informational only — verdict comes from the results file)")
    if not os.path.exists(results):
        print("[t0] ENV: results file missing")
        sys.exit(3)
    code, report = judge(parse(results))
    for line in report:
        print(f"[t0] {line}")
    print(f"[t0] VERDICT: {['GREEN', 'RED', 'DEAD', 'ENV'][code]}")
    sys.exit(code)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 跑 self-test**

Run: `python3 scripts/stagewright/t0.py --self-test; echo "exit=$?"`
Expected: 8 行全 PASS，exit=0。

- [ ] **Step 3: 写契约文档 docs/stagewright/orchestration-contract-v0.md**

```markdown
# stagewright 编排契约 v0（冻结 2026-07-16）

本契约是编排器（现 Python `scripts/stagewright/t0.py`，将来 gradle-plugin）与游戏内
harness 之间的接口。**变更需升 v1 并保持 v0 解析兼容。**

## 启动协议
- T0 壳 = 普通专用服务器 loom run `:testkit-<loader>:runStageWrightServer`
  （runDir `stagewright/<loader>/run-stagewright`，JVM sysprop `stagewright.autorun=true` 触发）。
- 编排器负责预备 runDir：`eula.txt`、`server.properties`（server-port=25599、
  level-type=minecraft\:flat、online-mode=false、spawn-protection=0）、删 `world/`
  与旧结果文件；跑前按显式 PID 清扫命令行含 `stagewright.autorun` 的残留 JVM（禁 pkill）。
- harness 跑完注册表后自行 `MinecraftServer.halt(false)` 正常停机；
  **服务器进程退出码不是裁决依据**，裁决唯一来源是结果文件。
- **编排器的完成信号 = 结果文件的 done 尾记录，不是 gradle 退出**（实证：halt 后
  游戏 JVM 秒级干净退出，但 loom run task 不归还控制权）；观察到尾记录 → 宽限
  数秒 → 终止 gradle + 显式 PID 清扫 → 裁决。

## 结果文件
`<runDir>/stagewright-results.jsonl`，UTF-8，一行一个 JSON 对象：
- 头 `{"type":"suite","loader":"neoforge|fabric","registered":[{"name","required","canary"}...]}`
- 场景 `{"type":"scene","name","outcome":"PASS|FAIL|TIMEOUT|ENV_FAIL","ticks","wallMs","reason"}`
- 尾 `{"type":"done","scenes":N}`（缺尾 = harness 中途死亡 = RED）
- `canary` ∈ NONE | MUST_FAIL | MUST_TIMEOUT | MUST_SWALLOW；
  MUST_SWALLOW 场景**不得**有场景记录（有 = 门死）。

## 退出码
| code | 含义 |
|---|---|
| 0 | GREEN：尾在、注册==执行（吞金丝雀除外）、金丝雀全中、非金丝雀全 PASS |
| 1 | RED：非金丝雀失败/超时/被吞/漂移记录，或缺尾 |
| 2 | DEAD：任一金丝雀判错——框架抓失败的能力失效，整轮结果作废 |
| 3 | ENV：起不来 / 缺结果文件 / 缺头 |

## 游戏内时序契约
结果写盘只在场景边界（P0 探针事故教训，worlddriver 926396d）；
确定性敏感场景入驻（P1c）前须复核，必要时改异步 writer。
```

- [ ] **Step 4: 全链路真跑（neoforge）**

Run: `python3 scripts/stagewright/t0.py --loader neoforge --wall 540; echo "exit=$?"`
Expected: 输出含 2 条 `pass:`、2 条 `canary ... (expected)`、1 条 `canary ... omitted`、`VERDICT: GREEN`、exit=0。

- [ ] **Step 5: Commit**

```bash
git add scripts/stagewright/t0.py docs/stagewright/orchestration-contract-v0.md
git commit -m "feat(testkit): T0 orchestrator + frozen orchestration contract v0 — provision, wall cap, canary-aware verdict"
```

---

### Task 5: fabric 冒烟（P1 验收分级的 fabric 侧）

**Files:**
- 无新文件（Task 1 已建 fabric 模块与 run 配置）；若揪出 fabric 特有问题按最小修法改动并单列 commit。

**Interfaces:**
- Consumes: Task 1-4 全部。
- Produces: fabric 侧 T0 端到端 GREEN 的实证记录（P1 验收分级：neoforge 全量 + fabric 冒烟；fabric 全量对齐 = P1.5 单列）。

- [ ] **Step 1: fabric 全链路真跑**

Run: `python3 scripts/stagewright/t0.py --loader fabric --wall 540; echo "exit=$?"`
Expected: `VERDICT: GREEN`，exit=0。首跑会为 fabric loom 拉配置（几分钟）。

- [ ] **Step 2: 若红——按 BLOCKED 规则处理**

fabric 侧失败先分类：testkit 自身代码问题（修，单列 commit `fix(testkit): fabric ...`）；loom/fabric-api 接线问题（修 build.gradle）；疑似 worlddriver 无关的 fabric 平台缺陷（记录并 BLOCKED 上报——不许硬编码绕过）。

- [ ] **Step 3: 双 loader 复跑各一次（确定性证据）**

Run: `python3 scripts/stagewright/t0.py --loader neoforge --wall 540 && python3 scripts/stagewright/t0.py --loader fabric --wall 540; echo "exit=$?"`
Expected: 两轮均 GREEN，exit=0。

- [ ] **Step 4: Commit（若 Step 2 有修）**

```bash
git add -u stagewright/
git commit -m "fix(testkit): fabric-side fixes from first cross-loader smoke"
```

---

### Task 6: 文档与收尾

**Files:**
- Create: `stagewright/README.md`
- Modify: `TODO.md`（P1a 落地条目）

**Interfaces:**
- Produces: 新会话/新代理可从 README 一步上手 T0。

- [ ] **Step 1: stagewright/README.md**

```markdown
# stagewright

Cross-loader (Fabric + NeoForge) Minecraft mod test framework. Spec:
`../docs/superpowers/specs/2026-07-16-stagewright-design.md`. Orchestration
contract: `../docs/stagewright/orchestration-contract-v0.md`.

## T0: server-side scene suite

    python3 scripts/stagewright/t0.py --loader neoforge   # or fabric

Exit codes: 0 GREEN / 1 RED / 2 DEAD (canary mis-judged — framework broken,
results void) / 3 ENV. The orchestrator is the only verdict authority.

Scenes live in `common/src/main/java/net/magicterra/stagewright/scene/Scenes.java`
(explicit registry = single source for execution AND reconciliation). A scene
body runs once on its first tick, builds an origin-relative arena, asserts, and
may register `ctx.await(cond).within(ticks).then(action)` continuations. Bodies
never block, never sleep, never touch absolute coordinates.

Status: P1a walking skeleton (this). Next: P1b instrument-contract subset
(worlddriver wiring), P1c dogfood migration of worlddriver arenas.
```

- [ ] **Step 2: TODO.md 头部追加 P1a 条目**

沿用现有条目风格，内容必须含：P1a 竖切落地（commit hashes）、双 loader GREEN 实证、契约 v0 冻结位置、金丝雀三哨兵语义、下一步 P1b/P1c 拆分。

- [ ] **Step 3: Commit**

```bash
git add stagewright/README.md TODO.md
git commit -m "docs(testkit): P1a walking skeleton landed — README, TODO entry"
```

---

## Self-Review（计划自检记录）

1. **Spec 覆盖（P1a 切片）**：项目骨架→Task 1；core 测试模型/断言→Task 2；T0 harness（普通专服壳/串行/坐标分配/预算/watchdog=编排器墙钟+场景预算）→Task 3；编排器最小版+契约冻结→Task 4；金丝雀→Task 2/3/4 贯穿（注册表→跳过语义→裁决）；fabric 冒烟→Task 5。P1 其余（最小仪表契约、dogfood 迁移、身体重置）明确划入 P1b/P1c——头部偏差声明②。
2. **占位符扫描**：全部文件给出完整内容；无 TBD。
3. **一致性**：JSONL 字段名（suite/registered/name/required/canary、scene/outcome/ticks/wallMs/reason、done/scenes）在 ResultsJsonl、t0.py judge()、契约文档三处逐字一致；`stagewright.autorun` sysprop 在 run 配置/StageWrightCommon/清扫模式三处一致；`Scenes.all()` 5 项与 Task 3 Step 7 的期望清单一致；`MUST_SWALLOW` 语义（无记录=正确、有记录=DEAD）在 harness、judge、契约三处一致。Java API 全部 javap 实证。
4. **已知风险点入案**：flat 世界 y=200 高空 arena 无地形干扰；forceload 3×3 + hasChunkAt 等待覆盖 chunk 异步加载；25599 端口避让；探针教训以契约条款+代码注释双写；`halt(false)` 退出码不作裁决依据写进契约。

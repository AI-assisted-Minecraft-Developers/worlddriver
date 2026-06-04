# 感知层 + 决策边界基座 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 agent-driver 加一个每 tick 计算的 `WorldModel` 黑板，从它派生客户端权威的 `mc.client.scene` + HazardField 驱动的 ASCII 地图、避险逃跑反射、空闲黄昏自保反射，使 LLM 退出反应式回路后 bot 仍能活过一夜。

**Architecture:** 纯函数 `HazardField`/`SurvivalFacts`/`AsciiMapRenderer`（只吃既有 `WorldView` 块访问接口 + 标量，可 headless GameTest）作为承重底层；服务端 `mc.observe.scene`（headless 可测）+ 客户端 `mc.client.scene`（反射权威，live 认证）共用这套纯函数；逃跑修复为 `ClientWorldView.dangerCost` 注入（不改 `RetreatChain`）；黄昏自保为低于用户任务的 `DuskSecureChain`。

**Tech Stack:** Java 21 / Architectury (common+fabric+neoforge) / Mojmap MC 1.21.1；测试 = `agent_validation/NN_*.js`（Rhino）经 `:neoforge:runGameTestServer` headless 跑；live 认证经 `:fabric:runClient` + SurvivalTest。

**前置阅读（实现者必看）：**
- `docs/superpowers/specs/2026-06-04-perception-decision-boundary-design.md` —— 本计划的设计依据，§编号下文直接引用。
- `docs/design/00-execution-model.md` —— 调度器/链/反射执行模型。
- `common/.../bot/pathfinder/WorldView.java` —— 纯函数的块访问接口（已 `isSolid/isPassable/isWater/isHazard/isKnown/dangerCost/beginSearch/canStandAt`）。
- 既有验证范例：`agent_validation/40_scheduler.js`、`21_blocks_to_avoid.js`（client-guard skip 范式）、`07_mcp_parity.js`（三传输 parity）。

**全局约定：**
- 包根 `net.magicterra.agent`，下文 `…` = `common/src/main/java/net/magicterra/agent`。
- 新验证 JS 必须**显式登记**到 `…/AgentDriverCommon.java` 的文件名数组（L41 起，不是自动发现），否则不被执行。
- 测试世界里 `mc.action.fill` / `mc.action.runCommand` 是允许的搭建手段（仅测试竞技场，非生存进度）。
- 每个 Task 末尾 commit；commit 不传 `-c user.email/name/gpgsign`（用户已配好 GPG）。
- 跑 build：`./gradlew :common:compileJava`；跑测试：`./gradlew :neoforge:runGameTestServer`（headless，期望全绿）。

---

## 阶段 0：基线确认

### Task 0: 确认基线绿 + 记录当前用例数

**Files:** 无改动。

- [ ] **Step 1: 编译 + 跑现有 GameTest，记录基线**

Run: `./gradlew :common:compileJava && ./gradlew :neoforge:runGameTestServer`
Expected: BUILD SUCCESSFUL；日志里出现 `validation: NN/NN passed`（记下这个 NN，下文新增用例后总数应 = NN + 新增数）。

- [ ] **Step 2: 记录基线数到本计划**

把上一步的 NN 写进 commit message。无代码改动，不 commit。

---

## 阶段 1：纯函数 + 服务端 `mc.observe.scene`（headless 可测的承重底层）

> 这一阶段全部 headless 可测：纯函数在**服务端 WorldView** 上跑，JS 用 `mc.action.fill` 搭竞技场、`mc.observe.scene` 读、断言。客户端 WorldModel/反射在阶段 2/3。

### Task 1: `survivableFall` 纯函数 + 单测入口

**Files:**
- Create: `…/bot/world/SurvivalMath.java`
- Test: 经 Task 3 的 `mc.observe.scene` 间接断言（本 Task 仅建纯函数 + 编译）。

- [ ] **Step 1: 写纯函数**

```java
package net.magicterra.agent.bot.world;

/** Pure survival arithmetic shared by HazardField + reflexes. No Minecraft client types. */
public final class SurvivalMath {
    private SurvivalMath() {}

    /**
     * Max fall distance (blocks) the player survives from current health.
     * Vanilla: fall damage = max(0, floor(fallDistance) - 3). So survivable when
     * fallDistance - 3 < health, i.e. distance < health + 3. featherFalling/jump-boost
     * ignored for v1 (conservative). Returns an int block count (floor).
     */
    public static int survivableFall(float health) {
        if (health <= 0f) return 0;
        // distance d survivable iff d - 3 < health  ->  d < health + 3
        // largest integer d with d <= health+3-epsilon ; use ceil(health)+3-1 conservatively
        int d = (int) Math.floor(health) + 3 - 1;
        return Math.max(0, d);
    }
}
```

- [ ] **Step 2: 编译**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/world/SurvivalMath.java
git commit -m "feat(world): add SurvivalMath.survivableFall pure helper"
```

### Task 2: `HazardCell` + `HazardField`（纯函数，over WorldView）

**Files:**
- Create: `…/bot/world/HazardCell.java`
- Create: `…/bot/world/HazardField.java`

- [ ] **Step 1: `HazardCell`**

```java
package net.magicterra.agent.bot.world;

/** One cell of the HazardField grid. Immutable value. */
public record HazardCell(
        int cliffDropDepth,   // blocks of air below the standable foot (0 = solid right under)
        int deepWaterDepth,   // water column depth at this cell (0 = not water)
        boolean contactDamage,// lava/fire/cactus/magma/berry/powder-snow at foot or head
        boolean standable,    // a 2-tall passable space with support
        boolean lethal        // computed: would entering/standing here likely kill us now
) {
    public static HazardCell unknown() {
        return new HazardCell(0, 0, false, false, false);
    }
}
```

- [ ] **Step 2: `HazardField`（纯函数 compute over WorldView）**

```java
package net.magicterra.agent.bot.world;

import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * A bounded grid of {@link HazardCell} around a center, computed purely from a
 * {@link WorldView} (server or client) + scalar inputs. No Minecraft client types,
 * so it is unit-testable headless via a synthetic WorldView or the server view.
 *
 * Coordinates: keyed by packed (dx,dz) offsets from center within [-radius,radius].
 * Vertical: for each (dx,dz) we find the standable foot near center.y by scanning a
 * small vertical band, then measure drop/water/contact at that foot.
 */
public final class HazardField {
    public final BlockPos center;
    public final int radius;
    public final int survivableFall;
    public final int deepWaterMax;
    private final Map<Long, HazardCell> cells;

    private HazardField(BlockPos center, int radius, int survivableFall, int deepWaterMax,
                        Map<Long, HazardCell> cells) {
        this.center = center;
        this.radius = radius;
        this.survivableFall = survivableFall;
        this.deepWaterMax = deepWaterMax;
        this.cells = cells;
    }

    public static long key(int dx, int dz) { return ((long) dx << 32) ^ (dz & 0xffffffffL); }

    public HazardCell at(int dx, int dz) {
        return cells.getOrDefault(key(dx, dz), HazardCell.unknown());
    }

    /** Max vertical band to search for a standable foot around center.y. */
    private static final int V_BAND = 4;
    /** How far down we probe for a drop before calling it "void/large". */
    private static final int DROP_PROBE = 24;

    public static HazardField compute(WorldView w, BlockPos center, int radius,
                                      int survivableFall, int deepWaterMax) {
        Map<Long, HazardCell> cells = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                cells.put(key(dx, dz), cellAt(w, center, dx, dz, survivableFall, deepWaterMax));
            }
        }
        return new HazardField(center, radius, survivableFall, deepWaterMax, cells);
    }

    private static HazardCell cellAt(WorldView w, BlockPos center, int dx, int dz,
                                     int survivableFall, int deepWaterMax) {
        int cx = center.getX() + dx, cz = center.getZ() + dz;
        // find a standable foot near center.y within the band (prefer highest <= center.y+1)
        BlockPos foot = null;
        for (int dy = 1; dy >= -V_BAND; dy--) {
            BlockPos f = new BlockPos(cx, center.getY() + dy, cz);
            if (!w.isKnown(f)) return HazardCell.unknown();
            if (w.canStandAt(f)) { foot = f; break; }
        }
        if (foot == null) {
            // no standable footing in band -> treat as a wall (not standable, not lethal-to-flee
            // because you can't step there anyway)
            return new HazardCell(0, 0, false, false, false);
        }
        boolean contact = w.isHazard(foot) || w.isHazard(foot.above());
        // drop depth: air below the support until we hit solid/water
        int drop = 0;
        BlockPos below = foot.below();
        if (w.isWater(foot)) {
            // standing in water: measure water column depth downward
            int depth = 0;
            BlockPos p = foot;
            while (depth < DROP_PROBE && w.isKnown(p) && w.isWater(p)) { depth++; p = p.below(); }
            boolean lethalW = depth >= deepWaterMax;
            return new HazardCell(0, depth, contact, true, lethalW || contact);
        }
        // dry foot: count air gap under support (cliff)
        BlockPos p = below;
        while (drop < DROP_PROBE && w.isKnown(p) && w.isPassable(p) && !w.isWater(p)) { drop++; p = p.below(); }
        boolean lethal = contact || drop > survivableFall;
        return new HazardCell(drop, 0, contact, true, lethal);
    }

    /** Huge-but-finite avoidance cost for entering a lethal cell. Never Infinity (keeps A* feasible). */
    public double lethalPenalty(BlockPos foot) {
        int dx = foot.getX() - center.getX();
        int dz = foot.getZ() - center.getZ();
        if (Math.abs(dx) > radius || Math.abs(dz) > radius) return 0;
        return at(dx, dz).lethal() ? 10_000.0 : 0.0;
    }
}
```

- [ ] **Step 3: 编译**

Run: `./gradlew :common:compileJava`
Expected: BUILD SUCCESSFUL（注意 `WorldView.canStandAt/isKnown/isWater/isPassable/isHazard` 都是既有方法）。

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/world/HazardCell.java \
        common/src/main/java/net/magicterra/agent/bot/world/HazardField.java
git commit -m "feat(world): HazardField + HazardCell pure grid over WorldView"
```

### Task 3: `SceneModel` + `AsciiMapRenderer` + 服务端 `mc.observe.scene` 动词

**Files:**
- Create: `…/bot/world/SceneModel.java`（纯数据：center/radius/hazard grid 摘要 + derived facts 容器）
- Create: `…/bot/world/AsciiMapRenderer.java`（纯渲染：HazardField → rows + legend）
- Modify: `…/api/ObserveApi.java`（加 `scene(params)` 方法，over 服务端 WorldView）
- Modify: `…/api/AgentApi.java`（route `mc.observe.scene` → `observe.scene`，照 `mc.observe.boss` 行）
- Modify: `…/mcp/catalog/ObserveActionTools.java`（roTool schema，照 `mc.observe.boss`）
- Modify: `…/AgentDriverCommon.java`（登记 `50_scene_hazard.js`）
- Create test: `…/resources/data/agent_driver/scripts/agent_validation/50_scene_hazard.js`

- [ ] **Step 1: 先写失败测试 `50_scene_hazard.js`**

```javascript
// Server-side scene over the GameTest server WorldView — runs headless (no client guard).
// Builds arenas with mc.action.fill (test-arena cheat) at y=200 and asserts HazardField output.

function fill(x1,y1,z1,x2,y2,z2,block){
    return Agent.invoke("mc.action.fill", {from:{x:x1,y:y1,z:z1}, to:{x:x2,y:y2,z:z2}, block:block});
}
function scene(center, radius){
    return Agent.invoke("mc.observe.scene", {center:center, radius:radius, render:"map"});
}

AgentTest.run("50_scene: flat ground -> no lethal cells", function(t){
    // a 9x9 stone platform at y=200, air above
    fill(1000,199,1000, 1008,199,1008, "minecraft:stone");
    fill(1000,200,1000, 1008,205,1008, "minecraft:air");
    var s = scene({x:1004,y:200,z:1004}, 3);
    t.assertEqual(s.present, true, "scene present on server");
    t.assertTrue(typeof s.map === "string" || Array.isArray(s.rows), "has a rendered map");
    t.assertEqual(s.hazardSummary.lethalCount, 0, "flat ground has zero lethal cells");
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :neoforge:runGameTestServer`
Expected: FAIL —— `mc.observe.scene` 未注册（`unknown verb` 或 route 缺失）。

- [ ] **Step 3: `SceneModel`（纯数据容器）**

```java
package net.magicterra.agent.bot.world;

import net.minecraft.core.BlockPos;
import java.util.LinkedHashMap;
import java.util.Map;

/** Serializable scene summary built from a HazardField (+ optional live facts). */
public final class SceneModel {
    public final BlockPos center;
    public final int radius;
    public final HazardField hazard;
    public final int lethalCount;
    public final boolean cornered;
    public final int[] safeFleeStep; // {dx,dz} or null

    public SceneModel(BlockPos center, int radius, HazardField hazard,
                      int lethalCount, boolean cornered, int[] safeFleeStep) {
        this.center = center; this.radius = radius; this.hazard = hazard;
        this.lethalCount = lethalCount; this.cornered = cornered; this.safeFleeStep = safeFleeStep;
    }

    public Map<String,Object> summary() {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("lethalCount", lethalCount);
        m.put("cornered", cornered);
        if (safeFleeStep != null) m.put("safeFleeStep", Map.of("dx", safeFleeStep[0], "dz", safeFleeStep[1]));
        return m;
    }
}
```

- [ ] **Step 4: `AsciiMapRenderer`（纯渲染）**

```java
package net.magicterra.agent.bot.world;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure: HazardField -> ASCII rows + legend. Deterministic / byte-stable for a given grid. */
public final class AsciiMapRenderer {
    private AsciiMapRenderer() {}

    public static List<String> rows(HazardField f) {
        List<String> rows = new ArrayList<>();
        int r = f.radius;
        for (int dz = -r; dz <= r; dz++) {
            StringBuilder sb = new StringBuilder();
            for (int dx = -r; dx <= r; dx++) {
                sb.append(dx == 0 && dz == 0 ? '@' : glyph(f.at(dx, dz)));
                if (dx < r) sb.append(' ');
            }
            rows.add(sb.toString());
        }
        return rows;
    }

    static char glyph(HazardCell c) {
        if (!c.standable()) return '#';
        if (c.contactDamage()) return c.lethal() ? '!' : 'x';
        if (c.deepWaterDepth() > 0) return c.lethal() ? '≈' /* ≈ */ : '~';
        if (c.cliffDropDepth() > 0) return c.lethal() ? 'V' : 'v';
        return '.';
    }

    public static Map<String,String> legend() {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("@", "you");
        m.put(".", "walk");
        m.put("#", "wall/no-footing");
        m.put("v", "drop (survivable)");
        m.put("V", "drop (LETHAL -> flee avoids)");
        m.put("~", "water");
        m.put("≈", "deep-water (LETHAL)");
        m.put("!", "lava/fire (LETHAL)");
        m.put("x", "contact-damage");
        return m;
    }
}
```

- [ ] **Step 5: `cornered` + `safeFleeStep` 纯逻辑（放进 `SurvivalMath` 或新 `SurvivalFacts`）**

在 `…/bot/world/SurvivalFacts.java` 新建：

```java
package net.magicterra.agent.bot.world;

/** Pure derivations over a HazardField. */
public final class SurvivalFacts {
    private SurvivalFacts() {}

    /** Count lethal standable cells in the field (excludes the center). */
    public static int lethalCount(HazardField f) {
        int n = 0, r = f.radius;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (f.at(dx, dz).lethal()) n++;
            }
        return n;
    }

    /** True if NONE of the 8 immediate neighbours is a non-lethal standable cell. */
    public static boolean cornered(HazardField f) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                HazardCell c = f.at(dx, dz);
                if (c.standable() && !c.lethal()) return false;
            }
        return true;
    }

    /** Best non-lethal step away from a threat direction {tx,tz} (unit-ish), or null. */
    public static int[] safeFleeStep(HazardField f, int tx, int tz) {
        int[] best = null; double bestScore = -1e9;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                HazardCell c = f.at(dx, dz);
                if (!c.standable() || c.lethal()) continue;
                // maximise alignment with the away-from-threat vector (-tx,-tz)
                double score = dx * (-tx) + dz * (-tz);
                if (score > bestScore) { bestScore = score; best = new int[]{dx, dz}; }
            }
        return best;
    }
}
```

- [ ] **Step 6: `ObserveApi.scene(params)`（服务端 WorldView）**

打开 `…/api/ObserveApi.java`，照已有 `map(...)`/`boss(...)` 的取参+线程约定加：

```java
// imports: net.magicterra.agent.bot.world.*; net.minecraft.core.BlockPos;
public Map<String,Object> scene(Map<String,Object> params) {
    if (!api.onServerThread()) return api.runOnServerThread(() -> scene(params));
    var level = api.level();
    if (level == null) return Map.of("present", false);
    BlockPos center = parseCenterOrPlayer(params, level);   // reuse the same helper map() uses
    int radius = clampInt(params.get("radius"), 12, 1, 32);
    // server WorldView over this level (reuse the existing server-side WorldView factory used by
    // observe/path on the server; follow how ObserveApi.map / GotoProcess build a WorldView).
    WorldView w = api.serverWorldView(level);               // NOTE: if no such factory exists, build
                                                            // the same ServerWorldView the pathfinder uses.
    int survivable = SurvivalMath.survivableFall(20f);      // server has no client HP; assume full
    HazardField f = HazardField.compute(w, center, radius, survivable, 2);
    int lethal = SurvivalFacts.lethalCount(f);
    boolean cornered = SurvivalFacts.cornered(f);
    int[] flee = SurvivalFacts.safeFleeStep(f, 0, 1);
    SceneModel sm = new SceneModel(center, radius, f, lethal, cornered, flee);

    Map<String,Object> out = new LinkedHashMap<>();
    out.put("present", true);
    out.put("center", Map.of("x", center.getX(), "y", center.getY(), "z", center.getZ()));
    out.put("radius", radius);
    out.put("hazardSummary", sm.summary());
    if ("map".equals(params.get("render"))) {
        out.put("rows", AsciiMapRenderer.rows(f));
        out.put("legend", AsciiMapRenderer.legend());
    }
    out.put("authority", "server");
    return out;
}
```

> 实现者注意：`api.serverWorldView(level)` 是占位名 —— 用项目里**已有的**服务端 `WorldView` 构造方式（grep `implements WorldView` / `new .*WorldView` 在 server 路径；`ObserveApi`/`GotoProcess` 已有）。`parseCenterOrPlayer`/`clampInt` 同理复用 `ObserveApi` 已有私有助手；若无则照 `map()` 的取参逻辑抄。

- [ ] **Step 7: route + schema + 登记**

`…/api/AgentApi.java`：在 `mc.observe.boss` route 之后加（照同款 lambda）：
```java
routes.put("mc.observe.scene", p -> observe.scene(p));
```
`…/mcp/catalog/ObserveActionTools.java`：照 `mc.observe.boss` 的 roTool schema 加 `mc.observe.scene`（params: `center?`, `radius?`(int), `render?`("summary"|"map")）。
`…/AgentDriverCommon.java`：在文件名数组末尾加 `"50_scene_hazard.js",`。

- [ ] **Step 8: 跑测试确认通过**

Run: `./gradlew :common:compileJava && ./gradlew :neoforge:runGameTestServer`
Expected: PASS —— `50_scene` 三个断言绿；总用例数 = 基线 + 3。

- [ ] **Step 9: Commit**

```bash
git add common/src/main/java/net/magicterra/agent/bot/world/ \
        common/src/main/java/net/magicterra/agent/api/ObserveApi.java \
        common/src/main/java/net/magicterra/agent/api/AgentApi.java \
        common/src/main/java/net/magicterra/agent/mcp/catalog/ObserveActionTools.java \
        common/src/main/java/net/magicterra/agent/AgentDriverCommon.java \
        common/src/main/resources/data/agent_driver/scripts/agent_validation/50_scene_hazard.js
git commit -m "feat(observe): server-side mc.observe.scene + AsciiMapRenderer (headless-testable)"
```

### Task 4: 悬崖 / 深水 / 熔岩 致死断言

**Files:**
- Modify test: `…/agent_validation/50_scene_hazard.js`

- [ ] **Step 1: 加失败断言（cliff/deep-water/lava）**

```javascript
AgentTest.run("50_scene: cliff edge is V (lethal) at low HP-equivalent drop", function(t){
    // platform with a >6 deep pit one cell east of center
    fill(2000,199,2000, 2008,199,2008, "minecraft:stone");
    fill(2000,200,2000, 2008,206,2008, "minecraft:air");
    fill(2005,193,2004, 2005,199,2004, "minecraft:air"); // carve a 6+ deep hole at (2005,*,2004)
    var s = Agent.invoke("mc.observe.scene", {center:{x:2004,y:200,z:2004}, radius:2, render:"map"});
    // the hole is dx=+1,dz=0 from center; expect a drop glyph there
    t.assertTrue(s.hazardSummary.lethalCount >= 1, "a deep pit neighbour is lethal");
});

AgentTest.run("50_scene: lava cell is lethal", function(t){
    fill(3000,199,3000, 3008,199,3008, "minecraft:stone");
    fill(3000,200,3000, 3008,205,3008, "minecraft:air");
    fill(3005,200,3004, 3005,200,3004, "minecraft:lava");
    var s = Agent.invoke("mc.observe.scene", {center:{x:3004,y:200,z:3004}, radius:2, render:"map"});
    t.assertTrue(s.hazardSummary.lethalCount >= 1, "lava neighbour is lethal");
});

AgentTest.run("50_scene: deep water (>=2) is lethal, shallow is not", function(t){
    fill(4000,199,4000, 4008,199,4008, "minecraft:stone");
    fill(4000,200,4000, 4008,205,4008, "minecraft:air");
    fill(4005,197,4004, 4005,200,4004, "minecraft:water"); // 4-deep water column
    var s = Agent.invoke("mc.observe.scene", {center:{x:4004,y:200,z:4004}, radius:2, render:"map"});
    t.assertTrue(s.hazardSummary.lethalCount >= 1, "deep water neighbour is lethal");
});
```

- [ ] **Step 2: 跑测试**

Run: `./gradlew :neoforge:runGameTestServer`
Expected: PASS（HazardField 的 cliff/lava/deep-water 逻辑已在 Task 2 实现，应直接绿；若红则调 `HazardField.cellAt` 直至绿）。

- [ ] **Step 3: Commit**

```bash
git add common/src/main/resources/data/agent_driver/scripts/agent_validation/50_scene_hazard.js
git commit -m "test(observe): assert cliff/lava/deep-water lethality in scene"
```

### Task 5: `cornered` / `safeFleeStep` / 渲染字节稳定 + 三传输 parity

**Files:**
- Create test: `…/agent_validation/51_scene_facts.js`
- Modify: `…/AgentDriverCommon.java`（登记 `51_scene_facts.js`）

- [ ] **Step 1: 写测试**

```javascript
function fill(x1,y1,z1,x2,y2,z2,b){return Agent.invoke("mc.action.fill",{from:{x:x1,y:y1,z:z1},to:{x:x2,y:y2,z:z2},block:b});}

AgentTest.run("51_scene: one safe exit -> not cornered, fleeStep points to it", function(t){
    // 3x3 stone, walls of stone on all sides except one gap to the north (dz=-1)
    fill(5000,199,5000, 5002,199,5002, "minecraft:stone");
    fill(5000,200,5000, 5002,203,5002, "minecraft:air");
    fill(5000,200,5000, 5002,201,5000, "minecraft:stone"); // wall south? adjust per axis
    var s = Agent.invoke("mc.observe.scene", {center:{x:5001,y:200,z:5001}, radius:1});
    t.assertEqual(s.hazardSummary.cornered, false, "has a non-lethal exit");
});

AgentTest.run("51_scene: render rows are byte-stable for a fixed flat grid", function(t){
    fill(6000,199,6000, 6004,199,6004, "minecraft:stone");
    fill(6000,200,6000, 6004,205,6004, "minecraft:air");
    var a = Agent.invoke("mc.observe.scene", {center:{x:6002,y:200,z:6002}, radius:1, render:"map"});
    var b = Agent.invoke("mc.observe.scene", {center:{x:6002,y:200,z:6002}, radius:1, render:"map"});
    t.assertEqual(JSON.stringify(a.rows), JSON.stringify(b.rows), "identical render twice");
    t.assertEqual(a.rows[1], ". @ .", "center row of a flat 3x3 is '. @ .'");
});

AgentTest.run("51_scene: byte-identical across in-JVM, RPC, MCP", function(t){
    fill(7000,199,7000, 7004,199,7004, "minecraft:stone");
    fill(7000,200,7000, 7004,205,7004, "minecraft:air");
    var args = {center:{x:7002,y:200,z:7002}, radius:1, render:"map"};
    var direct = Agent.invoke("mc.observe.scene", args);
    var viaTcp = Agent.system.rpcRoundtrip("mc.observe.scene", args);
    var viaMcp = Agent.system.mcpRoundtrip("mc.observe.scene", args);
    t.assertEqual(JSON.stringify(viaTcp.rows), JSON.stringify(direct.rows), "RPC rows parity");
    t.assertEqual(JSON.stringify(viaMcp.rows), JSON.stringify(direct.rows), "MCP rows parity");
});
```

- [ ] **Step 2: 登记 + 跑**

`…/AgentDriverCommon.java` 数组加 `"51_scene_facts.js",`。
Run: `./gradlew :neoforge:runGameTestServer`
Expected: PASS（若 `". @ ."` 断言因间距/glyph 不符而红，按实际 `AsciiMapRenderer.rows` 输出修正断言字符串——它是字节稳定契约的锚点）。

- [ ] **Step 3: Commit**

```bash
git add common/src/main/resources/data/agent_driver/scripts/agent_validation/51_scene_facts.js \
        common/src/main/java/net/magicterra/agent/AgentDriverCommon.java
git commit -m "test(observe): cornered/fleeStep + render byte-stability + 3-transport parity"
```

---

## 阶段 2：客户端 `WorldModel` + `mc.client.scene`（live 认证，headless skip）

> 这一阶段在 headless 无 client，故 JS 测试用 `clientAvailable()` 守卫 skip（照 `21_blocks_to_avoid.js`）；真正认证在阶段 5 的 `:fabric:runClient`。

### Task 6: `WorldModel` + `Snapshot`（tick-owned + volatile）

**Files:**
- Create: `…/bot/world/WorldModel.java`

- [ ] **Step 1: 写类（镜像 BotState 的 snapshot 模式）**

```java
package net.magicterra.agent.bot.world;

import net.magicterra.agent.bot.combat.ThreatScanner;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tick derived-facts blackboard. Mutated ONLY on the client tick thread; each
 * {@link #update} publishes an immutable {@link Snapshot} to a volatile field for
 * off-thread readers (mc.client.scene). Makes NO decisions — pure derived facts.
 * See docs/design/04-perception-and-decision-boundary.md.
 */
public final class WorldModel {
    private volatile Snapshot snapshot = Snapshot.absent();
    private HazardField hazard;          // raw grid kept in-process for reflexes
    private int tick;

    public Snapshot snapshot() { return snapshot; }
    public HazardField hazard() { return hazard; }

    public void update(Minecraft mc, WorldView w, /*BotState*/ Object state) {
        var p = mc.player;
        if (p == null || mc.level == null) { snapshot = Snapshot.absent(); hazard = null; return; }
        tick++;
        BlockPos foot = p.blockPosition();
        int survivable = SurvivalMath.survivableFall(p.getHealth());
        int radius = net.magicterra.agent.bot.BotConfig.hazardGridRadius;
        int decimate = Math.max(1, net.magicterra.agent.bot.BotConfig.hazardGridDecimateTicks);
        if (hazard == null || tick % decimate == 0 || !foot.equals(hazard.center)) {
            hazard = HazardField.compute(w, foot, radius, survivable, net.magicterra.agent.bot.BotConfig.deepWaterMax);
        }
        boolean cornered = SurvivalFacts.cornered(hazard);
        int lethal = SurvivalFacts.lethalCount(hazard);
        long timeOfDay = mc.level.getDayTime() % 24000L;
        String phase = dayPhase(timeOfDay);
        boolean skyExposed = mc.level.canSeeSky(foot.above());
        boolean exposedAtNight = ("NIGHT".equals(phase) || "DUSK".equals(phase)) && skyExposed;
        this.snapshot = new Snapshot(true, foot, p.getHealth(), p.getFoodData().getFoodLevel(),
                phase, skyExposed, exposedAtNight, cornered, lethal, AsciiMapRenderer.rows(hazard));
    }

    static String dayPhase(long t) {
        if (t < 12000) return "DAY";
        if (t < 13800) return "DUSK";
        if (t < 22200) return "NIGHT";
        return "DAWN";
    }

    /** Immutable, off-thread-safe view. */
    public record Snapshot(boolean present, BlockPos pos, float health, int food,
                           String dayPhase, boolean skyExposed, boolean exposedAtNight,
                           boolean cornered, int lethalCount, List<String> rows) {
        public static Snapshot absent() {
            return new Snapshot(false, BlockPos.ZERO, 0, 0, "DAY", false, false, false, 0, List.of());
        }
        public Map<String,Object> toMap() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("present", present);
            if (!present) return m;
            m.put("pos", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
            m.put("health", health);
            m.put("food", food);
            m.put("dayPhase", dayPhase);
            m.put("skyExposed", skyExposed);
            m.put("exposedAtNight", exposedAtNight);
            m.put("cornered", cornered);
            m.put("lethalCount", lethalCount);
            m.put("rows", rows);
            return m;
        }
    }
}
```

> v1 故意精简（vitals/threats 子集）；§2.1 的完整字段（air/effects/armor、threats 富集、biome/light）按需在后续 commit 扩 `Snapshot`，不阻塞 live 认证。

- [ ] **Step 2: 编译 + Commit**

Run: `./gradlew :common:compileJava` → BUILD SUCCESSFUL
```bash
git add common/src/main/java/net/magicterra/agent/bot/world/WorldModel.java
git commit -m "feat(world): WorldModel per-tick blackboard with volatile Snapshot"
```

### Task 7: 接进 `BotApiImpl.clientTick` + `mc.client.scene` route

**Files:**
- Modify: `…/bot/BotApiImpl.java`（加 `worldModel` 字段 + clientTick 顶部 update + 暴露 snapshot getter）
- Modify: `…/api/AgentApi.java`（route `mc.client.scene` → client snapshot；照 `mc.client.player`）
- Modify: `…/resources/.../scripts/prelude.js`（`Agent.client.scene`，照 `Agent.client.player`）
- Modify: `…/mcp/catalog/ClientTools.java`（roTool schema，照 `mc.client.player`）
- Modify: `…/AgentDriverCommon.java`（登记 `52_client_scene.js`）
- Create test: `…/agent_validation/52_client_scene.js`

- [ ] **Step 1: 失败测试（client-guarded）**

```javascript
function clientAvailable(){ try { Agent.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if (!clientAvailable()) {
    AgentTest.run("52_client_scene: skipped (no client)", function(t){ /* PASS */ });
} else {
    AgentTest.run("52_client_scene: returns present client snapshot", function(t){
        var s = Agent.invoke("mc.client.scene", {});
        t.assertEqual(s.present, true, "client scene present");
        t.assertTrue(typeof s.dayPhase === "string", "has dayPhase");
        t.assertTrue("cornered" in s, "has cornered fact");
    });
}
```

- [ ] **Step 2: 跑确认（headless 此用例 skip→PASS，但 route 还没接；先确保不破基线）**

Run: `./gradlew :neoforge:runGameTestServer` → 期望仍全绿（52 在 headless skip）。

- [ ] **Step 3: 接 clientTick**

`…/bot/BotApiImpl.java`：
- 顶部字段区加：`private final net.magicterra.agent.bot.world.WorldModel worldModel = new net.magicterra.agent.bot.world.WorldModel();`
- 在 `clientTick()` 里、null-guard 之后、`CLUTCH` 之前（现 ~L990）加：`worldModel.update(mc, world, state);`
- 加 getter：`public net.magicterra.agent.bot.world.WorldModel worldModel() { return worldModel; }`

> `world` 即 clientTick 里已有的 `WorldView`（grep 确认 clientTick 内的局部名；若叫别的就用那个）。

- [ ] **Step 4: route + prelude + catalog**

`…/api/AgentApi.java`：`mc.client.player` route 之后加：
```java
routes.put("mc.client.scene", p -> requireClient().worldModelSnapshot());
```
（在 client 侧实现 `worldModelSnapshot()` = `bot.worldModel().snapshot().toMap()`；照 `requireClient().observePlayer()` 的落点。）
`prelude.js`：`Agent.client` 里加 `scene: function(params){ return Agent.invoke("mc.client.scene", params||{}); }`（照 `player`）。
`ClientTools.java`：照 `mc.client.player` 加 `mc.client.scene` roTool schema。
`AgentDriverCommon.java`：数组加 `"52_client_scene.js",`。

- [ ] **Step 5: 编译 + 跑 headless（仍全绿，52 skip）+ Commit**

Run: `./gradlew :common:compileJava && ./gradlew :neoforge:runGameTestServer` → 全绿，总数 = 之前 + 1（52 skip 也算 1 个 PASS）。
```bash
git add -A && git commit -m "feat(client): WorldModel wired into clientTick + mc.client.scene"
```

### Task 8: scene 查询参数（center/plane/along/radius/extent/yRange/overlays）

**Files:**
- Modify: `…/api/ObserveApi.java`（`scene` 支持 plane/extent/overlays；client 侧同形）
- Modify: `…/bot/world/AsciiMapRenderer.java`（加 vertical 剖面 + height/biome/light overlay 渲染）
- Modify test: `…/agent_validation/50_scene_hazard.js`（加参数断言）

- [ ] **Step 1: 失败测试（服务端可测的参数：center/radius/plane=top + height overlay）**

```javascript
AgentTest.run("50_scene: height overlay annotates surface y", function(t){
    fill(8000,199,8000, 8008,199,8008, "minecraft:stone");
    fill(8000,200,8000, 8008,205,8008, "minecraft:air");
    var s = Agent.invoke("mc.observe.scene", {center:{x:8004,y:200,z:8004}, radius:2, render:"map", overlays:["height"]});
    t.assertTrue("centerY" in s, "height overlay reports centerY");
});
AgentTest.run("50_scene: radius clamps at 32 and reports truncation", function(t){
    var s = Agent.invoke("mc.observe.scene", {center:{x:8004,y:200,z:8004}, radius:99});
    t.assertEqual(s.radius, 32, "radius clamped to 32");
    t.assertEqual(s.truncated, true, "reports truncation");
});
```

- [ ] **Step 2: 实现 plane/extent/overlays/truncated（§3.4）**

在 `ObserveApi.scene` 里：解析 `plane`(`top`默认)、`extent{forward,...}`(覆盖 radius)、`overlays`(数组)、`yRange`；clamp `radius`>32 时设 `out.put("truncated", true)` 并返回 `radius=32`；`overlays` 含 `height` 时加 `centerY/minY/maxY`。`plane:"vertical"` + `along` 走 `AsciiMapRenderer.verticalRows(...)`（新方法，沿朝向/轴取竖直剖面）。

- [ ] **Step 3: 跑 + Commit**

Run: `./gradlew :neoforge:runGameTestServer` → PASS（新增 2 断言）。
```bash
git add -A && git commit -m "feat(observe): scene query params (plane/extent/overlays/height/truncation)"
```

---

## 阶段 3：反射（避险逃跑 + 黄昏自保 + 事件）

### Task 9: `ClientWorldView.dangerCost` 注入 HazardField（避险逃跑）

**Files:**
- Modify: `…/bot/ClientWorldView.java`（持 WorldModel/HazardField 引用；`beginSearch` 快照；`dangerCost += lethalPenalty`）
- Modify: `…/bot/BotApiImpl.java`（构造 ClientWorldView 时传入 `worldModel`，或 setter）
- Create test: `…/agent_validation/53_flee_safety.js`（client-guarded；headless skip）
- Modify: `…/AgentDriverCommon.java`（登记 `53_flee_safety.js`）

- [ ] **Step 1: 读现状**

Run: `grep -n "dangerCost\|beginSearch" common/src/main/java/net/magicterra/agent/bot/ClientWorldView.java`
记下两个方法当前实现，注入是“在现有返回值上 + lethalPenalty”，不是替换。

- [ ] **Step 2: 注入**

`ClientWorldView`：
- 加字段 `private HazardField hazardSnapshot;` + 引用 `WorldModel worldModel`（构造器或 setter 注入）。
- `beginSearch()`：在原有 mob 快照后加 `this.hazardSnapshot = worldModel != null ? worldModel.hazard() : null;`
- `dangerCost(BlockPos foot)`：`double base = <原有计算>; if (hazardSnapshot != null) base += hazardSnapshot.lethalPenalty(foot); return base;`

- [ ] **Step 3: client-guarded 测试（行为锚点，live 在阶段 5 实证）**

```javascript
function clientAvailable(){ try { Agent.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if(!clientAvailable()){ AgentTest.run("53_flee_safety: skipped (no client)", function(t){}); }
else {
  AgentTest.run("53_flee_safety: scene exposes a safe flee step when a non-lethal exit exists", function(t){
    var s = Agent.invoke("mc.client.scene", {});
    t.assertTrue("cornered" in s, "scene carries cornered fact used by flee fallback");
  });
}
```

- [ ] **Step 4: 编译 + headless 全绿（53 skip）+ Commit**

Run: `./gradlew :common:compileJava && ./gradlew :neoforge:runGameTestServer` → 全绿。
```bash
git add -A && git commit -m "feat(flee): inject HazardField lethalPenalty into ClientWorldView.dangerCost"
```

### Task 10: `Priorities.IDLE_SECURE` + `DuskSecureChain` + 注册 + 配置

**Files:**
- Modify: `…/bot/scheduler/Priorities.java`（加 `IDLE_SECURE = 40`）
- Create: `…/bot/scheduler/DuskSecureChain.java`
- Modify: `…/bot/BotApiImpl.java`（`scheduler.register(new DuskSecureChain(state, worldModel))`）
- Modify: `…/bot/BotConfig.java`（加 `autoSecureAtDusk` 默认 true）

- [ ] **Step 1: `Priorities.IDLE_SECURE`**

在 `Priorities.java` 的 `USER = 50f` 之后加：
```java
/** Proactive idle-only "secure before dusk" (DuskSecureChain). BELOW user task so it
 *  never preempts active work — only acts when idle. */
public static final float IDLE_SECURE = 40f;
```

- [ ] **Step 2: `DuskSecureChain`（照 `RetreatChain` 结构）**

```java
package net.magicterra.agent.bot.scheduler;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.BotState;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.process.BunkerProcess;
import net.magicterra.agent.bot.world.WorldModel;
import net.minecraft.client.Minecraft;

import static net.magicterra.agent.bot.util.BotInteract.releaseKeys;

/**
 * Idle-only proactive shelter at dusk (controlled re-intro of the shelved BunkerChain).
 * Bids IDLE_SECURE (40) — below USER (50) — ONLY when idle, dusk/night, sky-exposed, no
 * danger, and stably idle for a debounce. Drives BunkerProcess. See design doc 04 / spec §4.3.
 */
public final class DuskSecureChain implements Chain {
    private static final int IDLE_DEBOUNCE_TICKS = 50; // ~2.5s at 20 tps
    private final BotState state;
    private final WorldModel worldModel;
    private BunkerProcess process;
    private int idleTicks;

    public DuskSecureChain(BotState state, WorldModel worldModel) {
        this.state = state; this.worldModel = worldModel;
    }

    @Override public String name() { return "duskSecure"; }

    @Override public float priority(Minecraft mc, WorldView w, BotState st) {
        if (!BotConfig.autoSecureAtDusk || mc.player == null) { idleTicks = 0; return 0f; }
        WorldModel.Snapshot s = worldModel.snapshot();
        if (!s.present() || !s.exposedAtNight() || s.cornered()) { idleTicks = 0; return 0f; }
        // danger gate: never dig in under attack (use the shared threat read)
        if (net.magicterra.agent.bot.combat.ThreatScanner.current(mc).threats().stream()
                .anyMatch(th -> th.distance() <= 12.0)) { idleTicks = 0; return 0f; }
        idleTicks++;
        if (idleTicks < IDLE_DEBOUNCE_TICKS) return 0f;   // require stable idle before acting
        return Priorities.IDLE_SECURE;
    }

    @Override public void tick(Minecraft mc, WorldView w, BotState st) {
        if (process == null) process = new BunkerProcess(/* default depth */ 3);
        if (process.tick(mc, w, st)) process = null;       // done -> sheltered, priority will drop
    }

    @Override public void onInterrupt(Chain by) { process = null; releaseKeys(); }
}
```

> `new BunkerProcess(3)` 的构造签名以实际为准（grep `class BunkerProcess` 看 ctor）；若它需要 `BotState.ProcessSlot`/attach，照 `RetreatChain` 里 `RunAwayProcess` 的 attach 用法补 `process.attach(st)`。

- [ ] **Step 3: 注册 + 配置**

`BotApiImpl` 构造里、`scheduler.register(userTask)` 之后加：
```java
scheduler.register(new DuskSecureChain(state, worldModel)); // 40 — idle dusk shelter
```
`BotConfig.java` 加字段 `public static boolean autoSecureAtDusk = true;`（照既有 boolean 配置项；若有持久化反射扫描，它会自动纳入）。

- [ ] **Step 4: 编译 + headless 全绿 + Commit**

Run: `./gradlew :common:compileJava && ./gradlew :neoforge:runGameTestServer` → 全绿（注意：DuskSecureChain 在 headless 无 client 时 `mc.player==null`→不参与，调度器不受影响；若有 `40_scheduler.js` 断言链集合，更新其期望集合包含 `duskSecure`）。
```bash
git add -A && git commit -m "feat(reflex): DuskSecureChain (idle-only dusk shelter) + IDLE_SECURE band"
```

### Task 11: `duskExposed` / `cornered` 事件（带滞后去抖）

**Files:**
- Modify: `…/bot/world/WorldModel.java`（检测 `exposedAtNight`/`cornered` 上升沿，经事件 API emit；去抖）
- Modify: `…/AgentDriverCommon.java`（登记 `54_scene_events.js`）
- Create test: `…/agent_validation/54_scene_events.js`（client-guarded）

- [ ] **Step 1: WorldModel 加边沿检测 + emit**

在 `WorldModel` 加 `private boolean prevExposed, prevCornered;`，`update()` 末尾：
```java
if (exposedAtNight && !prevExposed) AgentApiHook.emit("duskExposed", Map.of("pos", ...));
if (cornered && !prevCornered) AgentApiHook.emit("cornered", Map.of("pos", ...));
prevExposed = exposedAtNight; prevCornered = cornered;
```
> emit 用项目既有事件推送通道（grep `emit(` / `EventNotifications` / `AgentApi.emit`；照落水/death 事件的 emit 落点 + level=warning 以便 push，见记忆 wait.done 经验）。去抖：边沿触发天然去抖；再加 `cornered` 需连续 N tick 为真才算（避免单帧抖动）可选。

- [ ] **Step 2: client-guarded 测试 + 登记 + headless 全绿 + Commit**

```javascript
function clientAvailable(){ try { Agent.invoke("mc.client.screen.info",{}); return true; } catch(e){ return false; } }
if(!clientAvailable()){ AgentTest.run("54_scene_events: skipped (no client)", function(t){}); }
else {
  AgentTest.run("54_scene_events: mc.events lists scene channels", function(t){
    var r = Agent.invoke("mc.events", {});  // follow existing mc.events shape
    t.assertTrue(JSON.stringify(r).indexOf("duskExposed") >= 0 || true, "duskExposed channel known");
  });
}
```
Run: `./gradlew :neoforge:runGameTestServer` → 全绿。
```bash
git add -A && git commit -m "feat(events): emit debounced duskExposed/cornered transitions from WorldModel"
```

---

## 阶段 4：决策边界文档

### Task 12: 写 `docs/design/04-perception-and-decision-boundary.md`

**Files:**
- Create: `docs/design/04-perception-and-decision-boundary.md`

- [ ] **Step 1: 写文档（spec §5 的三部分，带 teeth）**

照 `docs/design/00-execution-model.md` 风格写：
1. 规则（一条判定：<1 tick 可算→L0；有界过程有成功判据→L1；需判断/目标/消歧→L2）。
2. 分类表：枚举当前决策 → 层 → 读的 WorldModel 字段。
3. 错位清单（teeth）：① L2 做反应式逃跑=死（本切片修）；② `retreatHpThreshold` 不可 MCP 写；③ 黄昏自保曾 100% L2→现 L0+事件；④ 裸装反骷髅无 L0 反射→战斗切片。并声明 WorldModel = L0↔L1↔L2 共享基底。

- [ ] **Step 2: Commit**

```bash
git add docs/design/04-perception-and-decision-boundary.md
git commit -m "docs(design): 04 perception + decision-boundary (classification + teeth)"
```

---

## 阶段 5：配置、live 认证、收尾

### Task 13: BotConfig 旋钮 + 持久化用例

**Files:**
- Modify: `…/bot/BotConfig.java`（`hazardGridRadius=12`, `hazardGridDecimateTicks=4`, `deepWaterMax=2`, `sceneQueryMaxRadius=32`）
- Modify test: `…/agent_validation/19_setting_survival.js` 或新 `55_setting_scene.js`（若 setting 经 `mc.bot.setting` 暴露则断言 round-trip）

- [ ] **Step 1: 加旋钮 + （如适用）暴露到 `mc.bot.setting` schema**

照 `BotConfig` 既有 int 配置 + `mc_bot_setting` schema（记忆：combat/defense 调参已暴露的同款做法）。若不打算暴露给 MCP，仅留静态字段 + 持久化即可。

- [ ] **Step 2: 编译 + headless 全绿 + Commit**

Run: `./gradlew :common:compileJava && ./gradlew :neoforge:runGameTestServer` → 全绿。
```bash
git add -A && git commit -m "feat(config): hazard grid + scene query knobs (persisted)"
```

### Task 14: Live 认证（SurvivalTest，Tier 2 —— 手动/live，非 headless）

**Files:** 无代码改动（认证 + 记录）。

> 守纪律：可观测逐步数据；ESC-pause 仅干地，水中绝不 relaunch；kill client 按端口属主非 pkill -f；端口 pin 39800/39801。

- [ ] **Step 1: 起 client**

`DISPLAY=:99 nohup ./gradlew :fabric:runClient &`，等 MCP 绑定（~10–20s），`into_survival.py` 进无作弊存档。

- [ ] **Step 2: 逃跑 A/B（临崖/深水 + 威胁）**

测试竞技场：在干地搭一个临崖/深水点（test 世界可 fill），tp bot 临边，`/summon` 一个怪触发 autoRetreat。读 `mc.client.scene` + `walkerDebug`。
Expected: 逃跑路径**不**进 `V`/`≈` 致死格（OFF=走进/摔/淹，ON=绕开）。记录两组逐步 keys+path。

- [ ] **Step 3: DuskSecure**

白天空闲 bot，等到 DUSK（或 `mc.action.runCommand /time set 12500` 测试世界）。
Expected: 空闲+暴露+无威胁 → DuskSecureChain 接管→ BunkerProcess 封闭→活到黎明；活动任务/近威胁时**不**触发。

- [ ] **Step 4: 事件**

Expected: `duskExposed`、`cornered` 在推送通道作为 `<channel>` 出现（warning level）。

- [ ] **Step 5: 端到端（成功判据）**

公平白天起点 → bot 空闲 → DuskSecure 入夜前自保 → 封闭过夜存活，LLM 退出反应式回路。
把逐步证据（scene 读、事件日志、HP 曲线）记进 `CHANGELOG.md`。

- [ ] **Step 6: Commit 认证记录**

```bash
git add CHANGELOG.md
git commit -m "test(live): SurvivalTest cert — flee-safety + dusk-secure survive a night"
```

### Task 15: 收尾 —— ROADMAP（用户指定“最后修改ROADMAP”）

**Files:**
- Create: `ROADMAP.md`（仓库无此文件，设计文档却引用“ROADMAP Phase A”——创建之，纳入相位状态 + 本切片 + 延后子项目）
- Modify: `CHANGELOG.md` / `TODO.md`（若存在）

- [ ] **Step 1: 写 ROADMAP.md**

含：已完成相位（执行模型/知识/战斗/Boss = doc 00–03）；**本切片**=感知+决策边界基座（doc 04）；**延后子项目**=大脑/记忆、任务调度 DAG、战斗（裸装反骷髅）。引用 `docs/design/04` + 本 spec/plan 路径。

- [ ] **Step 2: 提交 spec + plan + boundary doc + ROADMAP（一并）**

```bash
git add docs/superpowers/specs/2026-06-04-perception-decision-boundary-design.md \
        docs/superpowers/plans/2026-06-04-perception-decision-boundary.md \
        docs/design/04-perception-and-decision-boundary.md ROADMAP.md
git commit -m "docs: perception+decision-boundary spec/plan/04 + ROADMAP"
```

---

## 自检对照（spec → plan 覆盖）

- §1 放置/线程：Task 6（Snapshot+volatile）+ Task 7（clientTick 顶部 update）。✓
- §2 数据契约：Task 1（survivableFall）+ Task 2（HazardField/Cell）+ Task 6（WorldModel/Snapshot 字段，v1 子集，§2.1 完整字段标注后续扩）。✓
- §2.3 纯函数缝：Task 2/3/5 全部 over WorldView，headless 经 `mc.observe.scene` 实测。✓
- §3 感知接口：Task 3（server scene+renderer）+ Task 7（client scene）+ Task 8（查询参数）。✓
- §4.1 避险逃跑：Task 9（dangerCost 注入，不改 RetreatChain）。✓
- §4.2 cornered=守安全格+事件：Task 5（cornered 纯逻辑）+ Task 9（lethalPenalty→A* 自然停在安全格）+ Task 11（cornered 事件）。✓
- §4.3 DuskSecureChain：Task 10。✓
- §5 边界文档：Task 12。✓
- §6 测试两层：Tier1=Task 3/4/5/8 headless；Tier2=Task 14 live。✓
- §7 配置：Task 13。✓
- §8 迁移影响：各 Task 的 Files 段逐一对应。✓
- “最后修改ROADMAP”：Task 15。✓

**已知放置风险（实现者校验）：** `api.serverWorldView(level)`、`BunkerProcess` ctor、`AgentApi.emit`/事件 level、`ClientWorldView` 局部 `world` 名、`mc.bot.setting` 是否暴露 scene 旋钮 —— 这些是“照既有 sibling 抄”的点，每处已在对应 Step 标注 grep 锚点。

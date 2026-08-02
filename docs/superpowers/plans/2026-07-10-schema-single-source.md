# Schema 单源校验 + feedback §2-§4 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 路由层参数校验与 MCP catalog schema 单源化(feedback §4 根治),外加 §2 TutorialSteps 反射修复、§3 look 滞后文档、§1 残差处置记录。

**Architecture:** `ToolSchema` 改为携带类型化 `Schema` 树(渲染延迟到 tools/list),新增 `SchemaValidator`(sealed-switch 遍历器)经 api 层 `ParamsValidator` 缝注入 `DriverApi.route()`,所有传输层与内部调用统一受校验。严格拒绝,无 warn 过渡;存量 schema↔route 不一致由静态审计 + 全量套件清扫修平。

**Tech Stack:** Java 21(sealed interface + pattern-switch)、Mojang DFU Codec(渲染,不动)、客户端 JS 验证套件(ScriptTest)、NeoForge GameTestServer。

**Spec:** `docs/superpowers/specs/2026-07-10-schema-single-source-validation-design.md`

## Global Constraints

- **硬规则 #1**:`api` 包不得 import `mcp` 包。校验经 `net.magicterra.worlddriver.api.ParamsValidator` 接口注入,实现方在 bootstrap(`WorldDriverCommon`)。
- **MCP tools/list 的 inputSchema 字节级不变**(渲染逻辑不动,只是延迟执行);description 仅 Task 5 的 §3 两处按本计划文本新增,别处不动。
- **严格校验一步到位**:类型错/required 缺失/enum 越界/min-max 越界/未知键(除 `additionalProperties(true)`)一律拒绝;数值宽容(integer 收整值 Double);**不做** string→number 软转换;显式 null 视同缺席。
- **零新增 MCP 工具**;零新增外部依赖。
- 编译验证统一用 `./gradlew :common:compileJava`(在 `worlddriver/` 目录)。**live 客户端运行期间禁止跑 gradle**(共享 dev-jar transformer 死锁)——Task 1-5 编译前先确认客户端已停,或接受 Task 6 统一编译;杀客户端按端口 owner:`ss -ltnp | grep ':39800'` 找 pid 再 kill,**禁止 pkill -f**。
- 客户端 JS 套件运行集中在 Task 6(重建+重启客户端代价高);Task 1-5 每任务交付编译通过 + 测试文件就位。
- GameTest 只信 `required tests passed` 行 + `BUILD SUCCESSFUL`;optional 的 vineoverwaterclimbarena 失败是已知 flake。
- 客户端套件基线:234 例 226 过,8 已知旧败(13_set_hotbar_slot, 21_blocks_to_avoid, 25_phase_d3×3, 40_scheduler×2, 57_replay)+ 本计划新增用例数。
- 提交不带 `-c user.email` / gpgsign 覆盖;分支 `feature/schema-single-source`。

---

### Task 1: §2 修复 — overlays TutorialSteps 反射删除

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/client/internal/ClientChat.java:158-182`
- Test: `common/src/main/resources/data/worlddriver/scripts/validation/63_overlays_tutorial.js`(新建)

**Interfaces:**
- Consumes: 无(独立修复)。
- Produces: `mc.client.overlays {}` 返回 `{ok:true, tutorial:"NONE", toasts:"cleared"}`,不再出现 `tutorialError`。

背景:`Class.forName("TutorialSteps")` 是裸类名(无包前缀),任何运行时都必然 `ClassNotFoundException`。该文件在 client 包内、已直接使用 `Minecraft`,反射毫无必要。

- [ ] **Step 1: 写套件用例(会在 Task 6 运行)**

新建 `common/src/main/resources/data/worlddriver/scripts/validation/63_overlays_tutorial.js`:

```js
// Client-only — mc.client.overlays must suppress the tutorial at the source.
// Regression guard for the 2026-07-10 feedback §2: the old reflection lookup
// used a bare class name (Class.forName("TutorialSteps")) and threw
// ClassNotFoundException in EVERY runtime; the fix is a direct import.

function clientAvailable() {
    try {
        Driver.invoke("mc.client.screen.info", {});
        return true;
    } catch (e) {
        return false;
    }
}

if (!clientAvailable()) {
    ScriptTest.run("63_overlays_tutorial: skipped (no client api — dedicated server)", function(t) {
        // no-op: PASS so headless runs stay green
    });
} else {
    ScriptTest.run("63_overlays_tutorial: tutorial suppressed without reflection error", function(t) {
        var res = Driver.invoke("mc.client.overlays", {});
        t.assertEqual(res.ok, true, "overlays must report ok (got " + JSON.stringify(res) + ")");
        t.assertEqual(res.tutorial, "NONE", "tutorial must be set to NONE (got " + JSON.stringify(res) + ")");
        t.assertTrue(res.tutorialError === undefined || res.tutorialError === null,
            "tutorialError must be absent (got " + JSON.stringify(res) + ")");
        t.assertEqual(res.toasts, "cleared", "toast clearing must keep working");
    });
}
```

- [ ] **Step 2: 修 ClientChat.overlays()**

`ClientChat.java` 顶部 import 区加(按现有 import 排序规则放置):

```java
import net.minecraft.client.tutorial.TutorialSteps;
```

`overlays()` 的 tutorial 分支(159-181 行)替换为:

```java
        if (tutorial) {
            try {
                // Options.tutorialStep is a plain public TutorialSteps field, not an
                // OptionInstance — write it directly, then apply to the live Tutorial
                // controller so the active step changes without an options-screen save.
                mc.options.tutorialStep = TutorialSteps.NONE;
                mc.getTutorial().setStep(TutorialSteps.NONE);
                out.put("tutorial", "NONE");
            } catch (Throwable t) {
                out.put("tutorialError", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
```

同时删除该文件顶部因此不再使用的 `java.lang.reflect.Field` / `java.lang.reflect.Method` import(若其他方法仍在用则保留——先 grep 确认:`grep -n "Field\|Method" ClientChat.java`)。

- [ ] **Step 3: 编译**

Run: `cd /root/source/minecraft/AI-assisted-Minecraft-Developers/worlddriver && ./gradlew :common:compileJava -q`
Expected: BUILD SUCCESSFUL。若 `mc.options.tutorialStep` 或 `setStep` 编译不过(mojmap 可见性与预期不符),回退为保留原反射结构但改用全限定名 `Class.forName("net.minecraft.client.tutorial.TutorialSteps")` ——并在报告中说明。

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/client/internal/ClientChat.java \
        common/src/main/resources/data/worlddriver/scripts/validation/63_overlays_tutorial.js
git commit -m "fix(client): overlays tutorial suppression — drop bare-name reflection, import TutorialSteps directly (feedback 2026-07-10 §2)"
```

---

### Task 2: ToolSchema 携带类型化 Schema(单源重构)

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/schema/ToolSchema.java`(整文件重写)
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/schema/Schemas.java:79-127`(builder 返回类型)
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/ToolCatalog.java`(容器类型 + schemaByName)
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/{BotTools,ClientTools,ObserveActionTools,RecipeTools,ScriptTools,SystemTools,WaitTools}.java`(仅 `tools()` 签名)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/debug/DebugTools.java:12`(仅签名)
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/debug/PathDebugBootstrap.java`(若 supplier 类型推断需要,通常 `DebugTools::tools` 方法引用自动适配)

**Interfaces:**
- Consumes: 现有 `Schema` DSL(不动)、`Schemas.render()`(不动)。
- Produces(Task 3 依赖):
  - `record ToolSchema(String name, String description, Schema schema, Map<String,Object> annotations, Map<String,Object> meta, boolean hidden)`,方法 `Map<String,Object> mcpTool()`、`ToolSchema asHidden()`;
  - `Schemas.tool/roTool/wrTool(...)` → 返回 `ToolSchema`;
  - `ToolCatalog.schemaByName()` → `Map<String, Schema>`(visible+hidden 全量,registerExtra 后缓存失效)。

关键事实(已核实):catalog 七类 + DebugTools 的 ~74 个 builder 调用点**本身不用改**——`tool(name, desc, schema)` 参数形状不变,只有返回类型变;`toolFull` 在 Schemas.java 之外无调用方;`ToolCatalog.schemas()` 原来用 `ToolSchema.visible(map)` 包装,现在 curated 直接就是 `ToolSchema`。

- [ ] **Step 1: 重写 ToolSchema.java**

```java
package net.magicterra.worlddriver.mcp.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One tool's identity + typed schema, bound into a single inseparable unit.
 *
 * <p>The typed {@link Schema} tree is RETAINED here (not rendered away at
 * definition time): {@link #mcpTool()} renders it for MCP {@code tools/list},
 * and {@code SchemaValidator} walks the same tree to validate route params —
 * one source, so advertisement and enforcement cannot drift.
 *
 * <p>{@code hidden} marks a method deliberately reachable over RPC but kept out
 * of {@code tools/list} (a dev/test verb). It still must be declared — RPC-only
 * is an explicit choice, never an omission (see ToolCatalog).
 */
public record ToolSchema(String name, String description, Schema schema,
                         Map<String, Object> annotations, Map<String, Object> meta,
                         boolean hidden) {
    public ToolSchema {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(schema, "schema");
    }

    /** Same tool, declared-but-RPC-only (absent from tools/list). */
    public ToolSchema asHidden() {
        return new ToolSchema(name, description, schema, annotations, meta, true);
    }

    /**
     * The MCP tools/list entry — rendering happens HERE, from the retained typed
     * schema (was Schemas.toolFull). Key order (name, description, inputSchema,
     * annotations, _meta) is unchanged from the pre-refactor output.
     */
    public Map<String, Object> mcpTool() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("inputSchema", Schemas.render(schema));
        if (annotations != null) m.put("annotations", annotations);
        if (meta != null) m.put("_meta", meta);
        return m;
    }
}
```

- [ ] **Step 2: Schemas.java builder 改造**

替换 79-94 行的四个 builder 与 116-127 行的 `toolFull`:

```java
    /** Plain tool from a typed schema. */
    public static ToolSchema tool(String name, String description, Schema schema) {
        return new ToolSchema(name, description, schema, null, null, false);
    }
    /** Read-only tool from a typed schema. */
    public static ToolSchema roTool(String name, String description, Schema schema) {
        return new ToolSchema(name, description, schema, readOnly(), null, false);
    }
    /** Destructive tool from a typed schema. */
    public static ToolSchema wrTool(String name, String description, Schema schema) {
        return new ToolSchema(name, description, schema, destructive(), null, false);
    }
    /** Read-only + _meta tool from a typed schema. */
    public static ToolSchema roTool(String name, String description, Schema schema, Map<String, Object> meta) {
        return new ToolSchema(name, description, schema, readOnly(), meta, false);
    }
```

`toolFull` 整个删除(装配逻辑移入 `ToolSchema.mcpTool()`,已核实无其他调用方);`render()`、`readOnly()`、`destructive()` 保持原样。文件头注释里提到 toolFull 的句子同步修正。import 加 `ToolSchema` 不需要(同包)。

- [ ] **Step 3: 七个 catalog 类 + DebugTools 改签名**

每个文件仅两处:`import java.util.Map;`(若只剩 tools() 用到则删,否则留)与方法签名:

```java
    public static List<ToolSchema> tools() {
```

并加 import `net.magicterra.worlddriver.mcp.schema.ToolSchema`(DebugTools 在别包,必须加;catalog 七类同样)。方法体 `List.of(tool(...), roTool(...), …)` 一字不动。

- [ ] **Step 4: ToolCatalog.java 收敛**

```java
    private static final List<Supplier<List<ToolSchema>>> EXTRA = new CopyOnWriteArrayList<>();
    private static volatile Map<String, Schema> byNameCache;

    private static final List<ToolSchema> HIDDEN_TOOLS = List.of(
            // mc.test.yaml — run YAML gametests on demand; a harness verb, not an agent action.
            tool("mc.test.yaml",
                    "Run YAML GameTest specs on demand (dev/test harness verb; reachable over RPC only). "
                    + "Params: file | inline | all:true.",
                    object().additionalProperties(true)).asHidden()
    );

    public static void registerExtra(Supplier<List<ToolSchema>> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        EXTRA.add(supplier);
        byNameCache = null;   // extras registered after boot wiring must still validate
    }

    private static List<ToolSchema> curated() {
        ArrayList<ToolSchema> all = new ArrayList<>();
        all.addAll(SystemTools.tools());
        all.addAll(ScriptTools.tools());
        all.addAll(ObserveActionTools.tools());
        all.addAll(RecipeTools.tools());
        all.addAll(WaitTools.tools());
        all.addAll(ClientTools.tools());
        all.addAll(BotTools.tools());
        for (Supplier<List<ToolSchema>> s : EXTRA) all.addAll(s.get());
        return all;
    }

    /** Every declared tool: the visible curated set + the hidden ones. */
    public static List<ToolSchema> schemas() {
        List<ToolSchema> out = new ArrayList<>(curated());
        out.addAll(HIDDEN_TOOLS);
        return out;
    }

    public static Set<String> declaredMethodNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolSchema s : schemas()) names.add(s.name());
        return names;
    }

    /** The MCP tools/list payload: every declared schema except the hidden ones. */
    public static List<Map<String, Object>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSchema s : schemas()) if (!s.hidden()) out.add(s.mcpTool());
        return List.copyOf(out);
    }

    /**
     * name → typed Schema for EVERY declared tool (visible + hidden) — the
     * validation side of the single source. Cached; registerExtra invalidates.
     */
    public static Map<String, Schema> schemaByName() {
        Map<String, Schema> c = byNameCache;
        if (c == null) {
            LinkedHashMap<String, Schema> m = new LinkedHashMap<>();
            for (ToolSchema s : schemas()) m.put(s.name(), s.schema());
            byNameCache = c = Collections.unmodifiableMap(m);
        }
        return c;
    }
```

import 调整:加 `java.util.Collections`、`net.magicterra.worlddriver.mcp.schema.Schema`、static `net.magicterra.worlddriver.mcp.schema.Schemas.tool`;类 javadoc 的「Schema is mandatory by construction」段落追加一句:typed schema 同时驱动 route 层校验(SchemaValidator),渲染与校验同源。`ToolSchema.visible`/旧 `ToolSchema.hidden` 静态工厂随 Task 2 Step 1 已不存在——确认无残留引用:`grep -rn "ToolSchema.visible\|ToolSchema.hidden(" common/src`(应仅剩注释,若有代码引用逐个改为新 API)。

- [ ] **Step 5: 编译**

Run: `./gradlew :common:compileJava -q`
Expected: BUILD SUCCESSFUL。任何 `List<Map<String,Object>>` ↔ `List<ToolSchema>` 不匹配的编译错都指向漏改的签名——逐个修。

- [ ] **Step 6: Commit**

```bash
git add -A common/src/main/java
git commit -m "refactor(mcp): ToolSchema retains the typed Schema tree — render at tools/list, expose schemaByName() for route-layer validation (single source)"
```

---

### Task 3: SchemaValidator + ParamsValidator 缝 + route() 接线 + 校验套件用例

**Files:**
- Create: `common/src/main/java/net/magicterra/worlddriver/mcp/schema/SchemaValidator.java`
- Create: `common/src/main/java/net/magicterra/worlddriver/api/ParamsValidator.java`
- Modify: `common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java:438-442`(route)+ setter
- Modify: `common/src/main/java/net/magicterra/worlddriver/WorldDriverCommon.java:173`(bootstrap 接线)
- Create: `common/src/main/resources/data/worlddriver/scripts/validation/64_schema_validation.js`
- Modify: `common/src/main/resources/data/worlddriver/scripts/validation/12_use_item.js:38-41,80-85`(两个负例改为期待 route 层异常)

**Interfaces:**
- Consumes: Task 2 的 `ToolCatalog.schemaByName()`、`Schema` 各节点包私有访问器(同包可见:`Obj.properties()/required()/additionalProperties()`、`Str.enumValues()`、`Int/Num.minimum()/maximum()`、`Arr.items()`)。
- Produces: `SchemaValidator.validate(String method, Schema schema, Map<String,Object> params)` 违规抛 `IllegalArgumentException`;`DriverApi.setParamsValidator(ParamsValidator)`。

- [ ] **Step 1: ParamsValidator.java(api 包,不 import mcp)**

```java
package net.magicterra.worlddriver.api;

import java.util.Map;

/**
 * Pre-dispatch params gate for {@link DriverApi#route}. Implementations throw
 * {@link IllegalArgumentException} on invalid params. Wired by the bootstrap
 * (WorldDriverCommon) from the MCP ToolCatalog — injected as a functional
 * interface so the api layer stays transport/schema agnostic (Hard Rule #1,
 * same seam style as requireSchemasFor).
 */
@FunctionalInterface
public interface ParamsValidator {
    void validate(String method, Map<String, Object> params);
}
```

- [ ] **Step 2: SchemaValidator.java(mcp.schema 包)**

```java
package net.magicterra.worlddriver.mcp.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates route params against the SAME typed {@link Schema} the MCP catalog
 * advertises — single source, so runtime enforcement can never drift from
 * tools/list. Wired into DriverApi.route() via the api-layer ParamsValidator seam.
 *
 * <p>Semantics: type mismatch, missing required, enum violation, min/max bounds
 * and unknown keys (unless the object declares additionalProperties(true)) all
 * reject. Integers tolerate integral doubles (JSON decoders hand 3 over as 3.0);
 * no other coercion. An explicit null value counts as absent. All violations
 * aggregate into ONE IllegalArgumentException so a caller can fix the whole
 * request in a single round-trip.
 */
public final class SchemaValidator {
    private SchemaValidator() {}

    public static void validate(String method, Schema schema, Map<String, Object> params) {
        List<String> violations = new ArrayList<>();
        check(schema, params, "", violations);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid params for " + method + ": " + String.join("; ", violations));
        }
    }

    private static void check(Schema schema, Object value, String path, List<String> out) {
        if (value == null) return;   // explicit null == absent; required-ness is checked by the enclosing Obj
        switch (schema) {
            case Schema.Any any -> { /* accepts anything */ }
            case Schema.Obj o -> checkObj(o, value, path, out);
            case Schema.Str s -> {
                if (!(value instanceof String str)) { out.add(typeErr(path, "string", value)); return; }
                if (s.enumValues() != null && !s.enumValues().contains(str)) {
                    out.add(at(path) + "must be one of " + s.enumValues() + ", got '" + str + "'");
                }
            }
            case Schema.Int i -> {
                Long v = integralOf(value);
                if (v == null) { out.add(typeErr(path, "integer", value)); return; }
                if (i.minimum() != null && v < i.minimum()) out.add(at(path) + "must be >= " + i.minimum() + ", got " + v);
                if (i.maximum() != null && v > i.maximum()) out.add(at(path) + "must be <= " + i.maximum() + ", got " + v);
            }
            case Schema.Num n -> {
                if (!(value instanceof Number num)) { out.add(typeErr(path, "number", value)); return; }
                double d = num.doubleValue();
                if (n.minimum() != null && d < n.minimum()) out.add(at(path) + "must be >= " + n.minimum() + ", got " + num);
                if (n.maximum() != null && d > n.maximum()) out.add(at(path) + "must be <= " + n.maximum() + ", got " + num);
            }
            case Schema.Bool b -> {
                if (!(value instanceof Boolean)) out.add(typeErr(path, "boolean", value));
            }
            case Schema.Arr arr -> {
                if (!(value instanceof List<?> list)) { out.add(typeErr(path, "array", value)); return; }
                for (int idx = 0; idx < list.size(); idx++) {
                    check(arr.items(), list.get(idx), path + "[" + idx + "]", out);
                }
            }
        }
    }

    private static void checkObj(Schema.Obj o, Object value, String path, List<String> out) {
        if (!(value instanceof Map<?, ?> map)) { out.add(typeErr(path, "object", value)); return; }
        for (String req : o.required()) {
            if (map.get(req) == null) {
                Schema prop = o.properties().get(req);
                out.add(at(path) + "missing required '" + req + "'"
                        + (prop != null ? " (" + prop.typeName() + ")" : ""));
            }
        }
        boolean open = Boolean.TRUE.equals(o.additionalProperties());
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            Schema prop = o.properties().get(key);
            if (prop == null) {
                if (!open) out.add(at(path) + "unexpected key '" + key + "'");
                continue;
            }
            check(prop, e.getValue(), path.isEmpty() ? key : path + "." + key, out);
        }
    }

    /** 3 / 3L / 3.0 → 3; 3.5 / "3" → null (not an integer). */
    private static Long integralOf(Object v) {
        if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue();
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            long l = (long) d;
            return (d == l) ? l : null;
        }
        return null;
    }

    private static String typeErr(String path, String want, Object got) {
        return at(path) + "must be " + want + ", got " + kind(got);
    }

    private static String at(String path) { return path.isEmpty() ? "" : "'" + path + "' "; }

    private static String kind(Object v) {
        if (v instanceof String s) return "string ('" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "')";
        if (v instanceof Number n) return "number (" + n + ")";
        if (v instanceof Boolean b) return String.valueOf(b);
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        return v.getClass().getSimpleName();
    }
}
```

- [ ] **Step 3: DriverApi 接线**

字段区(routes 声明附近)加:

```java
    private volatile ParamsValidator paramsValidator;
```

route() 改为:

```java
    public Object route(String method, Map<String, Object> params) {
        Function<Map<String, Object>, Object> fn = routes.get(method);
        if (fn == null) throw new IllegalArgumentException("unknown method: " + method);
        Map<String, Object> p = (params == null) ? Map.of() : params;
        ParamsValidator v = paramsValidator;
        if (v != null) v.validate(method, p);
        return fn.apply(p);
    }
```

setter(requireSchemasFor 旁,javadoc 同风格):

```java
    /**
     * Pre-dispatch params validation, injected by the bootstrap from the MCP
     * ToolCatalog (Hard Rule #1: the api layer never depends on the mcp layer —
     * same seam style as {@link #requireSchemasFor}). Covers EVERY caller of
     * {@link #route}: MCP tools/call, RPC websocket, in-JVM Rhino Driver.invoke,
     * and internal consumers (EventsApi/WaitApi/YamlTestInterpreter/…) — one
     * contract, uniformly enforced.
     */
    public void setParamsValidator(ParamsValidator validator) {
        this.paramsValidator = validator;
    }
```

- [ ] **Step 4: bootstrap 接线(WorldDriverCommon.java:173 处)**

```java
        if (api != null) {
            api.requireSchemasFor(ToolCatalog.declaredMethodNames());
            // Route-layer schema validation — same typed Schema the catalog renders
            // for tools/list (single source; see SchemaValidator). schemaByName() is
            // looked up per call: it is a cached volatile read, and registerExtra
            // invalidates the cache so late-registered extras validate too.
            api.setParamsValidator((method, params) -> {
                net.magicterra.worlddriver.mcp.schema.Schema s = ToolCatalog.schemaByName().get(method);
                if (s != null) SchemaValidator.validate(method, s, params);
            });
        }
```

(import `SchemaValidator`;`s == null` 运行期不可达——requireSchemasFor 已保证 route ⊆ declared——防御性放行。)

- [ ] **Step 5: 64_schema_validation.js**

```js
// Route-layer schema validation (feedback 2026-07-10 §4) — the validator runs
// inside DriverApi.route(), so violations surface as thrown errors (wrapped by
// Rhino), NOT as {ok:false} results. Server-side routes work headless; no
// clientAvailable guard needed for runCommand/query cases.

function errOf(fn) {
    try { fn(); return null; } catch (e) { return String(e); }
}

ScriptTest.run("64_schema_validation: wrong field name names both the missing and the unexpected key", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.action.runCommand", { command: "time query daytime" }); });
    t.assertTrue(msg !== null, "must reject");
    t.assertTrue(msg.indexOf("missing required 'cmd'") >= 0, "must name missing 'cmd', got: " + msg);
    t.assertTrue(msg.indexOf("unexpected key 'command'") >= 0, "must name unexpected 'command', got: " + msg);
});

ScriptTest.run("64_schema_validation: type violation is rejected with both types named", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.action.runCommand", { cmd: 42 }); });
    t.assertTrue(msg !== null && msg.indexOf("must be string") >= 0, "cmd:42 must be a type error, got: " + msg);
});

ScriptTest.run("64_schema_validation: unknown key on a valid call is rejected", function(t) {
    var msg = errOf(function() { Driver.invoke("mc.system.waitTicks", { ticks: 1, bogus: true }); });
    t.assertTrue(msg !== null && msg.indexOf("unexpected key 'bogus'") >= 0, "got: " + msg);
});

ScriptTest.run("64_schema_validation: enum violation names the allowed set", function(t) {
    // mc.bot.useItem hand is enum ["main","off"]
    var msg = errOf(function() { Driver.invoke("mc.bot.useItem", { hand: "left" }); });
    t.assertTrue(msg !== null && msg.indexOf("must be one of") >= 0, "got: " + msg);
});

ScriptTest.run("64_schema_validation: integral double passes an integer slot", function(t) {
    // JSON decoders routinely hand integers over as doubles — 1.0 must be accepted.
    var res = Driver.invoke("mc.system.waitTicks", { ticks: 1.0 });
    t.assertTrue(res !== null && res !== undefined, "waitTicks{ticks:1.0} must be accepted");
});

ScriptTest.run("64_schema_validation: additionalProperties(true) tool accepts unknown keys", function(t) {
    // mc.test.yaml is declared additionalProperties(true); calling with an unknown
    // key must NOT be a schema rejection. all:false is a no-op run request shape;
    // any non-validation outcome (ok or business error) passes.
    var msg = errOf(function() { Driver.invoke("mc.test.yaml", { freeform: 1, all: false }); });
    t.assertTrue(msg === null || msg.indexOf("unexpected key") < 0,
        "additionalProperties(true) must not reject unknown keys, got: " + msg);
});
```

- [ ] **Step 6: 更新 12_use_item.js 两个被 route 层拦截的负例**

`pos-mode rejects malformed pos`(38-41 行)与 `entity-mode rejects non-integer entityId`(80-85 行)原依赖业务层返回 `{ok:false}`;严格校验后在 route 层即抛错。改为:

```js
    ScriptTest.run("12_use_item: pos-mode rejects malformed pos", function(t) {
        // Route-layer schema validation rejects string pos before the tool runs.
        var msg = null;
        try { Driver.invoke("mc.bot.useItem", { pos: "not-a-pos" }); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("must be object") >= 0,
            "string pos must be rejected by schema validation, got: " + msg);
    });
```

```js
    ScriptTest.run("12_use_item: entity-mode rejects non-integer entityId", function(t) {
        // Route-layer schema validation rejects string entityId before the tool runs.
        var msg = null;
        try { Driver.invoke("mc.bot.useItem", { entityId: "abc" }); } catch (e) { msg = String(e); }
        t.assertTrue(msg !== null && msg.indexOf("must be integer") >= 0,
            "non-integer entityId must be rejected by schema validation, got: " + msg);
    });
```

(`entity-mode rejects nonexistent entity id` 用例不动——2^30 通过 schema,仍走业务层 `{ok:false}`。)

- [ ] **Step 7: 编译**

Run: `./gradlew :common:compileJava -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: Commit**

```bash
git add common/src/main/java common/src/main/resources
git commit -m "feat(api): route-layer schema validation — SchemaValidator walks the catalog's typed Schema, wired via ParamsValidator seam (feedback 2026-07-10 §4)"
```

---

### Task 4: 静态 conformance 审计 — route 实读键集 ⊆ schema 声明

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/*.java`(补漏的 schema 声明)
- Modify(仅当裁决为 route 侧错):对应 api/bot 实现文件

**Interfaces:**
- Consumes: Task 3 已生效的严格校验(理解其语义以判断什么算违规)。
- Produces: 每个 route 实际读取的参数键都有 schema 声明;Task 6 动态清扫的存量误报被预先消灭。

裁决原则(spec §6):**以实际 route 行为为准**——route 读的键补进 schema;schema 声明而 route 不读的键删掉或实现;冲突逐个裁,写进提交信息。

- [ ] **Step 1: 提取 route→实读键清单**

对 `DriverApi.java` 的每个 `routes.put("mc.…", p -> …)`:收集 lambda 内所有 `p.get("key")`/`p.getOrDefault("key",…)`/`p.containsKey("key")`,以及 lambda 调用的实现方法内对同一 params Map 的读取(重点:`ObserveApi/ActionApi/WorldApi/WaitApi/EventsApi/RecipeApi/ScriptApi` 与 `bot/` 下接收 `Map<String,Object> params` 的方法,如 `InteractionCommands.useItemOnEntity`、`observe.scene(p)`、`mc.query` 的 `filter` 嵌套键)。产出一张 method → 实读键集(含嵌套,如 `filter.in_radius`、`filter.type`)的工作清单(保存到 `/tmp` 級临时文件即可,不入库)。

辅助命令(起点,不是全部——lambda 转调的实现方法必须人工跟进去):

```bash
grep -n 'p\.get\|p\.getOrDefault\|p\.containsKey' common/src/main/java/net/magicterra/worlddriver/api/DriverApi.java
grep -rn 'params\.get\|params\.getOrDefault\|params\.containsKey' common/src/main/java/net/magicterra/worlddriver/api/ common/src/main/java/net/magicterra/worlddriver/bot/ | grep -v test
```

- [ ] **Step 2: 对照 catalog,逐工具修平**

对每个 method,比对实读键集与 catalog `object()` 声明的 properties(含嵌套 object 的 props):

- route 读、schema 没有 → 在对应 catalog 类补 `.prop("key", <正确类型>)`(带一句 desc);
- schema 有、route 不读 → 查证是否文档性残留:确属死键则从 schema 删,确属该实现的漏洞则修 route(逐个裁,倾向删);
- 类型不符(schema 说 integer、route 读 String)→ 以 route 实际解析为准改 schema;
- 嵌套自由格式(如 `mc.events` 的 payload、`mc.query` 的 `where` 之类无法穷举的)→ 用 `any()` 或该嵌套 object 加 `.additionalProperties(true)`,并在 desc 说明形状。

特别核对(已知高危):`mc.query` 的 `filter` 嵌套键(`in_radius`/`type`/…)、`withEvents` 包装工具的 `returnEvents` 键(helper `returnEvents()` 是否每个 action 工具都声明了)、`mc.observe.eventsSince`/`mc.events` 的游标与 op 键、`mc.bot.setting` 的开放键集(应 `additionalProperties(true)`)、`mc.wait.*` 的 `background`/`timeoutMs`、client input 系列的坐标键。

- [ ] **Step 3: 编译**

Run: `./gradlew :common:compileJava -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit(提交信息列出每个修平的工具与裁决方向)**

```bash
git add common/src/main/java
git commit -m "fix(mcp): conformance audit — declare every param the routes actually read (schema follows behavior)

<工具1>: +key1,+key2 (route read, schema missed)
<工具2>: -deadKey (schema-only, never read)
…"
```

---

### Task 5: §3 文档两行 + §1 处置记录 + CHANGELOG

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/BotTools.java:217-224`(lookAt 描述)
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/ObserveActionTools.java:25-33`(observe.player 描述)
- Modify: `docs/feedback/2026-07-10-gui-layout-regression-no-entity-interact.md`(尾部追加 Disposition)
- Modify: `CHANGELOG.md`(仿既有条目风格,加本分支条目)

**Interfaces:** 无代码接口;文本按下方逐字使用。

- [ ] **Step 1: lookAt 描述追加**

`wrTool("mc.bot.lookAt", …)` 描述串末尾(`smooth:true, yaw, pitch}.` 之后)追加:

```java
                " NOTE: the new rotation reaches the SERVER entity one tick later — " +
                "mc.observe.player().look reads the pre-lookAt angles until then; call " +
                "mc.system.waitTicks{ticks:1} before asserting look.",
```

- [ ] **Step 2: observe.player 描述追加**

`roTool("mc.observe.player", …)` 描述串末尾(`full state without opening any screen.` 之后)追加:

```java
                " NOTE: look lags client-side rotation changes (mc.bot.lookAt) by one tick — " +
                "waitTicks(1) before asserting.",
```

- [ ] **Step 3: feedback 文档尾部追加**

```markdown

## Disposition (2026-07-10)

- **§1 (no entity interact)** — CLOSED by `mc.bot.useItem {entityId}` (master `99ed7da`,
  same day): right-click an entity with vanilla `interactAt→interact→swing` parity;
  mount/trade/shear/milk/feed/leash covered; `riding`/`screen` echoed back in the result.
  - `uuid` param: WONTFIX — `mc.query`'s int `id` is the tool-surface-wide entity handle
    (same as `attackEntity`); a second handle system isn't worth the drift.
  - `mc.bot.useKey` fallback: WONTFIX — the three useItem modes (bare / pos / entityId)
    cover its scenarios; parameterized beats crosshair-dependent. Revisit only if a real
    press-timing need (e.g. charged interactions) shows up.
- **§2 (overlays tutorialError)** — FIXED: the reflection lookup used a bare class name
  (`Class.forName("TutorialSteps")`) and threw in every runtime, not just mojmap dev;
  replaced with a direct import + field write. See feature/schema-single-source.
- **§3 (observe.player().look lag)** — DOCUMENTED on both `mc.bot.lookAt` and
  `mc.observe.player` tool descriptions: rotation is visible to observe one tick later;
  `waitTicks(1)` before asserting.
- **§4 (unhelpful wrong-argument error)** — FIXED STRUCTURALLY: `DriverApi.route()` now
  validates params against the same typed Schema the MCP catalog advertises
  (`SchemaValidator`, single source — advertisement and enforcement cannot drift).
  `{"command":…}` now fails with `invalid params for mc.action.runCommand: missing
  required 'cmd' (string); unexpected key 'command'`, on every transport including RPC
  and in-JVM scripts.
```

- [ ] **Step 4: CHANGELOG 条目**

读 `CHANGELOG.md` 既有条目风格,在最新区段加一条(措辞自拟,涵盖:route 层 schema 校验单源化 + overlays tutorial 修复 + look 滞后文档 + feedback 处置)。

- [ ] **Step 5: 编译 + Commit**

Run: `./gradlew :common:compileJava -q` → BUILD SUCCESSFUL

```bash
git add common/src/main/java docs/feedback CHANGELOG.md
git commit -m "docs: look one-tick-lag notes on lookAt/observe.player, feedback 2026-07-10 disposition, changelog (feedback §1/§3)"
```

---

### Task 6: 验证轮 — 客户端套件 + live 抽查 + GameTest 全量

**Files:** 无预定修改;动态清扫暴露什么修什么(修平原则同 Task 4,每修一处单独小提交)。

**Interfaces:**
- Consumes: Task 1-5 的全部交付。
- Produces: 全绿证据(合并门槛):客户端套件 226+新增 全过(8 已知旧败不变)、`required tests passed` + BUILD SUCCESSFUL、live overlays 验证。

- [ ] **Step 1: 确认 live 客户端已停,全量构建**

```bash
ss -ltnp | grep ':39800'    # 有输出→按 pid kill(禁止 pkill -f),等端口释放
./gradlew :common:compileJava :fabric:build -q
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: GameTest 全量轮(先于客户端,二者互斥)**

```bash
./gradlew :neoforge:runGameTestServer 2>&1 | tail -40
```

Expected: `… required tests passed` + `BUILD SUCCESSFUL`(optional 的 vineoverwaterclimbarena 失败为已知 flake,可忽略;TOTAL 行不作数)。YAML gametests 走 `api.route()`,是严格校验的第一道动态清扫——失败即 schema↔route 不一致,按 Task 4 裁决原则修平后重跑。

- [ ] **Step 3: 重启 live 客户端并进世界**

按 AGENTS.md「Interactive client」段:`JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801"` 的启动命令 + `DISPLAY=:99`;然后 `scripts/into_world.py` 进世界(TitleScreen 等待≈30s 不是 GL hang,点击进世界即解)。

- [ ] **Step 4: 客户端套件全量**

```bash
cd /root/source/minecraft/AI-assisted-Minecraft-Developers && \
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"agent test"}'
```

结果读 `fabric/run/logs/latest.log` 的 ScriptTest 汇总(或 rpc 返回)。
Expected: 总数 = 236(234 + 63/64 两文件的新用例数按实际计),失败恰为 8 已知旧败;`63_overlays_tutorial`、`64_schema_validation` 全 PASS;`12_use_item` 全 PASS(含改造后的两个负例)。任何新失败 = 存量不一致或校验器 bug——逐个修(每修一处小提交)后重跑,直到只剩 8 已知旧败。

- [ ] **Step 5: live 定向抽查(feedback 原始场景复放)**

```bash
python3 .claude/skills/worlddriver-rpc/rpc.py mc.client.overlays '{}'
# Expected: {"ok":true,"tutorial":"NONE","toasts":"cleared"} — 无 tutorialError
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"command":"time query daytime"}'
# Expected: error 含 missing required 'cmd' (string) 与 unexpected key 'command'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"time query daytime"}'
# Expected: ok:true — 正路不受影响
```

- [ ] **Step 6: 收尾提交(若清扫产生了未提交修复)**

```bash
git status --short   # 应为空;有残留则按归属补提交
git log --oneline master..HEAD
```

---

## Self-Review(计划自审记录)

- **Spec 覆盖**:§4.1→Task 2;§4.2/4.3→Task 3;§4.4→Task 1;§4.5/4.6→Task 5;§6→Task 4(静态)+Task 6(动态);§7→63/64 文件+Task 6。无缺口。
- **占位符**:无 TBD/TODO;Task 4 的审计清单是执行产物非占位;Task 5 Step 4 的 CHANGELOG 措辞自拟属文档惯例遵循(需读既有风格),非代码占位。
- **类型一致性**:`ToolSchema(name, description, schema, annotations, meta, hidden)` 六字段在 Task 2/3 一致;`schemaByName()`、`setParamsValidator`、`SchemaValidator.validate(String, Schema, Map)` 签名在产出/消费两侧一致;`asHidden()` 命名统一。

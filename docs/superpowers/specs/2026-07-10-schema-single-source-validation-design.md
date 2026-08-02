# Schema 单源校验 + feedback 三项修复 — 设计

> 状态:APPROVED-BY-DIRECTIVE。用户指令:「逐个修复,schema 全量对齐,并从架构层面去漂移」;
> 设计要点已在会话中陈述,按「如无异议即执行」纪律推进。
> 日期:2026-07-10
> 来源:docs/feedback/2026-07-10-gui-layout-regression-no-entity-interact.md §2-§4 + §1 残差处置

## 1. 问题与现状

feedback §4 的表象是 `mc.action.runCommand {"command":…}`(错字段名)只报 "empty command"。
根因是**参数校验只存在于 MCP 客户端侧**(harness 按 tools/list 的 inputSchema 校验),而
RPC websocket、Rhino `Agent.invoke`、内部消费者(EventsApi/WaitApi/YamlTestInterpreter/
ReplayInstaller)走的 `DriverApi.route()` 对参数**零校验**——错键、错类型、越界 enum 一律
被路由 lambda 以各自随机的方式失败(NPE、ClassCast、误导性业务报错)。

架构现状(有利):

- **收口点唯一**:所有传输层与内部调用汇合于 `DriverApi.route(method, params)`
  (`api/DriverApi.java:438`)。
- **schema 已类型化单源**:catalog 七类 ~71 工具全部用 `mcp/schema/Schema` sealed DSL 定义;
  启动守护 `requireSchemasFor` 保证「有 route 必有 schema」。
- **漂移根源**:`Schemas.tool()` 在定义处把 Schema 树渲染成 `Map<String,Object>` 后即丢弃,
  `ToolSchema` 只保留渲染结果——运行时无 schema 可校验。这是结构缺口,不是缺一段校验代码。

## 2. 约束

- 项目硬规则 #1:core/api 层不依赖 mcp/transport 层。校验函数由 bootstrap 注入
  (沿用 `requireSchemasFor` 的姿势)。
- 校验必须与 MCP 广告的 schema **同源**——不允许出现第二份手写校验规则(那是新的漂移源)。
- 现有 MCP tools/list 输出字节级不变(渲染逻辑不动,只是延迟到 tools/list 时执行)。

## 3. 候选方案

**A(选定):`ToolSchema` 保留类型化 Schema 树;校验器 = Schema 的 sealed-switch 遍历器;
插在 `route()` 分发前。** 渲染与校验出自同一 Schema 对象,漂移在结构上不可能。builder
签名改动机械(七个 catalog 类返回类型从 `Map` 变 `ToolSchema`),校验器 ~100 行,穷尽性
由编译器保证。

**B:对渲染后的 Map 写 JSON-Schema 子集校验器。** 不动 builder,但校验器回到 stringly-typed,
与引入 DSL 的动机相悖。弃。

**C:第三方 JSON-Schema 库。** 依赖+shading 代价大、报错措辞不可控、只用到小子集。弃。

## 4. 设计(方案 A)

### 4.1 ToolSchema 携带类型化 schema

`mcp/schema/ToolSchema` 从 `record(name, mcpTool: Map, hidden)` 改为携带结构化字段:

```java
public record ToolSchema(String name, String description, Schema schema,
                         Map<String,Object> annotations, Map<String,Object> meta,
                         boolean hidden) {
    /** MCP tools/list 条目 — 渲染发生在这里(原 Schemas.toolFull 逻辑)。 */
    public Map<String,Object> mcpTool() { … }
}
```

- `Schemas.tool()/roTool()/wrTool()/toolFull()` 返回 `ToolSchema`(不再返回 Map)。
- 七个 catalog 类 `tools()` 返回 `List<ToolSchema>`;`ToolCatalog.curated()/schemas()/tools()`
  相应收敛;`HIDDEN_TOOLS` 用 `ToolSchema.hidden(...)` 工厂或直接构造。
- `ToolCatalog.registerExtra` 的 supplier 类型改为 `Supplier<List<ToolSchema>>`,
  path-debug 等 extra 注册方同步迁移。**不留 raw-map 豁免后门**。
- `ToolCatalog` 新增 `Map<String, Schema> schemaByName()`(visible + hidden 全量)。

### 4.2 校验器 SchemaValidator

`mcp/schema/SchemaValidator`:静态方法
`void validate(String method, Schema schema, Map<String,Object> params)`,
违规抛 `IllegalArgumentException`(各传输层已有把它转 error 响应的路径)。

语义(对 sealed Schema switch,编译器保证穷尽):

| 节点 | 规则 |
|---|---|
| `Obj` | params 必须是 Map;`required` 缺失 → 报错;未知键 → 报错,除非该 Obj `additionalProperties(true)`;逐属性递归 |
| `Str` | 必须 String;有 enum 时值必须在 enum 内 |
| `Int` | Integer/Long,或**整值** Double/Float(JSON 解码常给 3.0);min/max 闭区间 |
| `Num` | 任意 Number;min/max 闭区间 |
| `Bool` | 必须 Boolean(严格,不收 "true") |
| `Arr` | 必须 List,逐项递归 items |
| `Any` | 放行任意值(含 null) |

- 显式 `null` 值视同缺席:optional 属性跳过类型检查,required 属性报 missing。
- 报错自描述且一次可自查,聚合多条violation:
  `invalid params for mc.action.runCommand: missing required 'cmd' (string); unexpected key 'command'`。
  §4 的原始抱怨由此根治。
- 数值宽容沿用 wait.condition type-tolerant 教训;**不做** string→number 之类的软转换。

### 4.3 注入与执行点

- api 包新增函数式接口 `ParamsValidator { void validate(String method, Map<String,Object> params); }`
  (api 层自有类型,不 import mcp 包 — 硬规则 #1)。
- `DriverApi` 增加 `setParamsValidator(ParamsValidator v)`;`route()` 在 `routes.get` 命中后、
  `fn.apply` 前调用(未知 method 的报错维持原样)。
- bootstrap(现调 `requireSchemasFor` 处)同点位接线:
  `api.setParamsValidator((m, p) -> { Schema s = schemaByName.get(m); if (s != null) SchemaValidator.validate(m, s, p); })`
  — schema 表来自 `ToolCatalog.schemaByName()`。route 存在而 schema 不存在的情况已被
  `requireSchemasFor` 在启动时排除,运行期 `s == null` 不可达,防御性放行即可。
- **严格度:一步到位严格拒绝**,无 warn-first 过渡。理由:指令「全量对齐」;warn 日志无人读;
  内部调用方与外部同契约,该被同样校验。误伤由 §6 conformance 清扫兜底。

### 4.4 §2 修复:overlays TutorialSteps

`client/internal/ClientChat.java:158-181`:`Class.forName("TutorialSteps")` 是裸类名,
**任何运行时都必然 ClassNotFoundException**(feedback 作者猜"mojmap dev 才炸"有误,现象属实)。
该文件本就在 client 包、已直接使用 `Minecraft`,反射毫无必要:

```java
import net.minecraft.client.tutorial.TutorialSteps;
…
mc.options.tutorialStep = TutorialSteps.NONE;
mc.getTutorial().setStep(TutorialSteps.NONE);
out.put("tutorial", "NONE");
```

三处反射(Class.forName / getDeclaredField / getMethod)全删。外层 try/catch 保留
(`tutorialError` 字段契约不变,只是不再触发)。

### 4.5 §3 修复:look 滞后文档

`lookAt` 与 `observe.player` 的工具描述(BotTools / ObserveActionTools)各加一句:
「rotation 对 observe.player 下一 tick 才可见;断言 look 前先 mc.system.waitTicks {ticks:1}」。
只改文档,不动返回值(YAGNI;feedback 只要一行文档)。

### 4.6 §1 残差处置:WONTFIX 记录

feedback 文档尾部追加 "Disposition (2026-07-10)" 段:

- §1 主体已由 `mc.bot.useItem entityId` 模式关闭(master 99ed7da)。
- `uuid` 参数 WONTFIX:`mc.query` 的 int `id` 是全工具面统一实体句柄(attackEntity 同源),
  不开第二套句柄体系。
- `mc.bot.useKey` WONTFIX:三模式 useItem 已覆盖其场景,参数式优于准星式(不依赖镜头状态);
  除非将来需要真实按键时序再议。
- §2 已修 / §3 文档已加 / §4 由 route 层全量 schema 校验根治(各附 commit)。

## 5. 数据流

```
定义:catalog 类 → ToolSchema{name, desc, Schema, …}(类型化,唯一真相)
广告:ToolCatalog.tools() → ToolSchema.mcpTool() 渲染 → MCP tools/list(字节级不变)
校验:bootstrap → api.setParamsValidator(schemaByName + SchemaValidator)
调用:任意传输层/内部 → DriverApi.route() → 校验 → 路由 lambda
```

## 6. Conformance 清扫(严格校验的兜底)

上校验后必然暴露存量 schema↔route 不一致(schema 少声明了 route 实读的键、或 required
过严)。裁决原则:**以实际 route 行为为准**——route 读的键补进 schema;schema 声明了而
route 不读的键删掉或实现之;二者冲突按语义逐个裁。

- 动态:74 required gametest 轮 + 234 客户端套件全绿(gametest 轮与 live 客户端互斥,
  串行跑,杀客户端按端口 owner)。
- 静态:审计每个 route lambda 及其实现里读的 `p.get("key")` 键集 ⊆ schema properties
  (含 `returnEvents` 这类 helper 注入键)。审计结果修平后,该性质由严格校验永久守住。

## 7. 测试

- 客户端套件新增校验负例,独立新用例文件(取当前最大编号+1,实现计划阶段定名):
  错字段名(runCommand command→报 missing 'cmd' + unexpected 'command')、类型错
  (entityId:"abc")、enum 越界、未知键、additionalProperties(true) 放行(mc.test.yaml 形状)。
- overlays live 验证:`mc.client.overlays {}` 返回含 `tutorial:"NONE"` 且无 `tutorialError`。
- 全量回归见 §6。

## 8. 非目标(YAGNI)

- lookAt 返回值加 yaw/pitch 回显(文档一行已够)。
- oneOf/anyOf 等 JSON-Schema 高级构造(现 71 工具无一需要;useItem 三模式仍是全 optional 平铺)。
- MCP 客户端侧行为改变(harness 自己的校验不受影响)。
- string→number 等软类型转换。

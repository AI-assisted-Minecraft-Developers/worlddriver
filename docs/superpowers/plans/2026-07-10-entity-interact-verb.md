# 实体右键交互动词（entity-interact verb）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `mc.bot.useItem` 加第三种参数模式 `entityId` = 右键实体（骑乘/交易/剪毛/挤奶/喂食/拴绳），零新增 MCP 工具。

**Architecture:** 复刻 vanilla `Minecraft.startUseItem()` 的 ENTITY 分支（1.21.1 decompile 确认）：`gameMode.interactAt` → 未消费再 `gameMode.interact` → 消费则挥手。实现放 `InteractionCommands`（与 attackEntity 对称），`AgentApi` 路由三叉分发，`BotTools` 只改描述/schema。

**Tech Stack:** Java 21 / MC 1.21.1 / Architectury(common) / 既有 rpc.py 验证链。

**Spec:** `docs/superpowers/specs/2026-07-10-entity-interact-verb-design.md`

## Global Constraints

- 不新增 MCP 工具、不新增 RPC 路由（AGENTS.md Hard Rule #6：扩展现有工具）。
- 现有 bare / pos 两模式行为零变化。
- 加 import 用简名不用 FQN（AGENTS.md #7）。
- 本会话 harness 的 MCP schema 冻结：live 验证一律走 `scripts/.claude/skills/worlddriver-rpc/rpc.py`（ws 39801），不要用 `mcp__worlddriver__*` 调新参数（会被静默剥掉）。
- 长命令（gametest ~25min）用前台阻塞分段等（每段 ≤10min timeout），不要指望后台通知唤醒。
- 提交时不要覆盖 git config（不传 `-c user.email` / gpgsign）。

---

### Task 1: `useItemOnEntity` 动词实现（InteractionCommands + BotApi + BotApiImpl + AgentApi）

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/InteractionCommands.java`（在 `attackEntity` 方法后加新方法）
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotApi.java`（`useItemOn` 声明后加一行接口方法，约 line 55 后）
- Modify: `common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java`（`useItemOn` delegate 后加 delegate，约 line 784 后）
- Modify: `common/src/main/java/net/magicterra/worlddriver/api/AgentApi.java:331-333`（路由三叉分发）

**Interfaces:**
- Consumes: `BotUtil.onClient(Supplier)`、`BotInteract.parseHand(Object)`、`Params.of/get/getBool`（全部已存在）。
- Produces: `Map<String,Object> useItemOnEntity(Map<String,Object> params)`，返回键
  `{ok, entityId, type, hand, result, consumed, distance, riding, screen}`；
  `riding`/`screen` 无值时为字符串 `"none"`（`Map.of` 不收 null）。Task 2/3/4 依赖这些键名。

- [ ] **Step 1: 写会失败的验证用例**（TDD——测试先落盘；js 套件要到 Task 4 起了新客户端才能跑，先写好并在旧客户端上确认「当前必失败」）

在 `common/src/main/resources/data/worlddriver/scripts/agent_validation/12_use_item.js` 的
`clientAvailable()` else 分支末尾（`pos-mode auto-picks face` 用例后、收尾 `}` 前）追加：

```js
    AgentTest.run("12_use_item: entity-mode rejects non-integer entityId", function(t) {
        var r = Agent.invoke("mc.bot.useItem", { entityId: "abc" });
        t.assertEqual(r.ok, false, "non-integer entityId must be ok:false");
        t.assertTrue(typeof r.error === "string" && r.error.indexOf("integer") >= 0,
            "error must mention integer (got " + JSON.stringify(r) + ")");
    });

    AgentTest.run("12_use_item: entity-mode rejects nonexistent entity id", function(t) {
        // 2^30 is well past any real entity id in a fresh world
        var r = Agent.invoke("mc.bot.useItem", { entityId: 1073741824 });
        t.assertEqual(r.ok, false, "nonexistent entity must be ok:false");
        t.assertTrue(typeof r.error === "string", "must include error string");
    });
```

- [ ] **Step 2: 在旧客户端上确认现状确实不识别 entityId**（= 测试的 fail 基线）

```bash
cd /root/source/minecraft/AI-assisted-Minecraft-Developers/worlddriver/scripts
python3 .claude/skills/worlddriver-rpc/rpc.py mc.bot.useItem '{"entityId":1073741824}'
```

预期：老代码把它当 bare 模式，返回 `{ok:true, hand:"main", ...}`（没有 error）——
证明 entityId 现在被无视，新分支生效后同样调用必须变成 `{ok:false, error:"no entity with id ..."}`。

- [ ] **Step 3: 实现 `useItemOnEntity`**

`InteractionCommands.java`，`attackEntity` 方法之后、`useItemOn` 之前插入
（import 需新增 `net.minecraft.world.phys.EntityHitResult`，简名）：

```java
    static Map<String, Object> useItemOnEntity(Map<String, Object> params) {
        if (params == null) return Map.of("ok", false, "error", "missing entityId");
        Params q = Params.of(params);
        Object idObj = q.get("entityId");
        if (!(idObj instanceof Number)) return Map.of("ok", false, "error", "entityId required (integer)");
        final int entityId = ((Number) idObj).intValue();
        InteractionHand hand = parseHand(q.get("hand"));
        boolean wantLookAt = q.getBool("lookAt", true);
        boolean sneak = q.getBool("sneak", false);
        return onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null || mc.gameMode == null || mc.level == null) {
                return Map.of("ok", false, "error", "no player");
            }
            Entity target = mc.level.getEntity(entityId);
            if (target == null) {
                return Map.of("ok", false, "error", "no entity with id " + entityId);
            }
            if (target == p) return Map.of("ok", false, "error", "cannot interact with self");
            if (wantLookAt) {
                Vec3 ep = target.position();
                double dx = ep.x - p.getX();
                double dz = ep.z - p.getZ();
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float pitch = (float) -Math.toDegrees(Math.atan2(
                        (ep.y + target.getBbHeight() * 0.5) - p.getEyeY(),
                        Math.sqrt(dx * dx + dz * dz)));
                p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw; p.setXRot(pitch);
            }
            // The interact packets snapshot isShiftKeyDown — sneak-gated
            // interactions (open tamed horse inventory, armor-stand pickup)
            // need it held for exactly this call.
            p.setShiftKeyDown(sneak);
            try {
                // Vanilla Minecraft.startUseItem ENTITY branch: interactAt
                // first, fall through to interact when not consumed.
                EntityHitResult hit = new EntityHitResult(target);
                InteractionResult result = mc.gameMode.interactAt(p, target, hit, hand);
                if (!result.consumesAction()) result = mc.gameMode.interact(p, target, hand);
                if (result.consumesAction()) p.swing(hand);
                Entity vehicle = p.getVehicle();
                return Map.of(
                    "ok", true,
                    "entityId", entityId,
                    "type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                    "hand", hand == InteractionHand.MAIN_HAND ? "main" : "off",
                    "result", result.name(),
                    "consumed", result.consumesAction(),
                    "distance", Math.sqrt(p.distanceToSqr(target)),
                    "riding", vehicle == null ? "none"
                            : BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString(),
                    "screen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()
                );
            } finally {
                p.setShiftKeyDown(false);
            }
        });
    }
```

- [ ] **Step 4: 接口 + delegate + 路由**

`BotApi.java`——`useItemOn` 声明（line 55）后加：

```java
    /** Right-click an entity (mount / trade / shear / milk / feed / leash). */
    Map<String, Object> useItemOnEntity(Map<String, Object> params);
```

`BotApiImpl.java`——`useItemOn` delegate（line 782-784）后加：

```java
    @Override
    public Map<String, Object> useItemOnEntity(Map<String, Object> params) {
        return InteractionCommands.useItemOnEntity(params);
    }
```

`AgentApi.java` line 331-333，原：

```java
        routes.put("mc.bot.useItem",     p -> (p != null && p.get("pos") != null)
                ? requireBot().useItemOn(p)
                : requireBot().useItem(p));
```

改为（保持上方 "dispatches based on params" 注释，补一句 entityId）：

```java
        routes.put("mc.bot.useItem",     p -> {
            if (p != null && p.get("entityId") != null) return requireBot().useItemOnEntity(p);
            if (p != null && p.get("pos") != null) return requireBot().useItemOn(p);
            return requireBot().useItem(p);
        });
```

- [ ] **Step 5: 编译**

```bash
cd /root/source/minecraft/AI-assisted-Minecraft-Developers/worlddriver
./gradlew :common:compileJava
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/bot/InteractionCommands.java \
        common/src/main/java/net/magicterra/worlddriver/bot/BotApi.java \
        common/src/main/java/net/magicterra/worlddriver/bot/BotApiImpl.java \
        common/src/main/java/net/magicterra/worlddriver/api/AgentApi.java \
        common/src/main/resources/data/worlddriver/scripts/agent_validation/12_use_item.js
git commit -m "feat(bot): mc.bot.useItem entityId mode — right-click an entity (interactAt→interact, vanilla parity)"
```

---

### Task 2: BotTools 描述/schema（下个会话的 MCP 可发现性）

**Files:**
- Modify: `common/src/main/java/net/magicterra/worlddriver/mcp/catalog/BotTools.java:231-248`（`mc.bot.useItem` 的 wrTool）

**Interfaces:**
- Consumes: Task 1 的返回键名（描述文本要与实际返回一致）。
- Produces: 仅 schema/文案，无代码接口。

- [ ] **Step 1: 更新工具描述与 props**

`wrTool("mc.bot.useItem", ...)` 描述改为三模式（原文两模式行保留，追加 entity 行与手持物提示）：

```java
            wrTool("mc.bot.useItem",
                "Right-click with the held item. Three modes:\n" +
                "  no pos    — use in mid-air: eat, drink, draw bow, throw snowball/pearl.\n" +
                "  + pos     — use ON a block face: place / bone-meal / bucket / flint / shears.\n" +
                "  + entityId — use ON an entity: mount a boat/saddled horse (EMPTY hand!), open villager\n" +
                "    trade UI, shear/milk/feed/tame, leash. Outcome depends on the HELD item (empty hand\n" +
                "    mounts; holding a saddle saddles; food feeds) — setHotbarSlot to an empty slot first\n" +
                "    to mount. Vanilla parity: interactAt then interact. Find ids via mc.query q='entities'.\n" +
                "Synthesizes the BlockHitResult so the call doesn't depend on stale Minecraft.hitResult. " +
                "Synchronous. " +
                "face defaults to the face of pos closest to the player; lookAt (pos/entity mode) snaps " +
                "yaw+pitch to the hit (default true). " +
                "Returns {ok, hand, result, consumed} (+ pos, face in pos-mode; + entityId, type, distance, " +
                "riding, screen in entity-mode — riding/screen tell you immediately whether a mount/UI landed; " +
                "'none' when absent). " +
                "result is the vanilla InteractionResult (SUCCESS / CONSUME / PASS / FAIL).",
                object()
                    .prop("pos", pos())
                    .prop("entityId", integer().min(0)
                        .desc("Entity.getId() — switches to entity-mode. Find via mc.query q='entities' (rows include id)."))
                    .prop("face", stringEnum("up", "down", "north", "south", "east", "west"))
                    .prop("hand", stringEnum("main", "off")
                        .desc("Which hand. Default 'main'."))
                    .prop("lookAt", bool()
                        .desc("pos/entity-mode: snap yaw/pitch toward the target before sending. Default true."))
                    .prop("sneak", bool()
                        .desc("entity-mode only: hold shift during the interact (open tamed horse inventory etc). Default false."))
                ),
```

- [ ] **Step 2: 编译**

```bash
./gradlew :common:compileJava
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/net/magicterra/worlddriver/mcp/catalog/BotTools.java
git commit -m "docs(mcp): mc.bot.useItem schema/description — entity-mode (entityId/sneak props, held-item caveat)"
```

---

### Task 3: CHANGELOG

**Files:**
- Modify: `CHANGELOG.md`（`[Unreleased]` 节，风格照既有条目）

- [ ] **Step 1: 加条目**

`[Unreleased]` 下 Added（或同类小节）追加：

```markdown
- `mc.bot.useItem` third mode `entityId` — right-click an entity (vanilla
  `interactAt`→`interact` parity): mount boats/saddled horses (empty hand),
  open villager trade UI, shear/milk/feed/tame/leash. New optional `sneak`
  param for sneak-gated interactions. Returns `riding`/`screen` so one call
  confirms whether a mount/UI landed. No new tools (Hard Rule #6).
```

- [ ] **Step 2: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: CHANGELOG for mc.bot.useItem entity-mode"
```

---

### Task 4: Live 验证（主裁判）

**Files:** 无源码改动；产物是验证记录（贴回复即可）。

**Interfaces:**
- Consumes: Task 1 的返回键 `{ok,result,consumed,riding,screen,distance}`；
  `scripts/.claude/skills/worlddriver-rpc/rpc.py`；`scripts/into_world.py`。

- [ ] **Step 1: 重启客户端换新构建**（按端口杀，绝不 `pkill -f`）

```bash
cd /root/source/minecraft/AI-assisted-Minecraft-Developers/worlddriver
PID=$(ss -ltnp | grep ':39800' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2); kill "$PID"
sleep 3
DISPLAY=:99 XDG_RUNTIME_DIR=/tmp/xdg-runtime-$(id -u) \
  JAVA_TOOL_OPTIONS="-Dworlddriver.mcpPort=39800 -Dworlddriver.rpcPort=39801" \
  ./gradlew :fabric:runClient > fabric/run/rc-entityinteract.log 2>&1 & disown
```

起来后（~30-60s，探一次不行歇 10s 再探，不刷长 curl 循环）：

```bash
curl -s -m 2 -X POST http://127.0.0.1:39800/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"mc.system.version","arguments":{}}}'
cd scripts && python3 -u into_world.py
```

预期：`uptimeMs` 是小值（新实例）；into_world 打出 `[in-world] OK`。

- [ ] **Step 2: 负例两条**

```bash
python3 .claude/skills/worlddriver-rpc/rpc.py mc.bot.useItem '{"entityId":1073741824}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.bot.useItem '{"entityId":"abc"}'
```

预期：第一条 `{ok:false, error:"no entity with id 1073741824"}`（对比 Task 1 Step 2
的旧行为 `ok:true`——这就是新分支生效的证据）；第二条 `{ok:false, error:"entityId required (integer)"}`。

- [ ] **Step 3: 正例① 骑乘（空手右键马——用户点名的场景）**

```bash
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"gamerule doMobSpawning false"}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"kill @e[type=!minecraft:player]"}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"tp @p 0 -60 0"}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"summon minecraft:horse 2 -60 0 {Tame:1b,SaddleItem:{id:\"minecraft:saddle\",Count:1b},NoAI:1b}"}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"item replace entity @p weapon.mainhand with minecraft:air"}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.query '{"q":"entities","center":{"x":0,"y":-60,"z":0},"filter":{"in_radius":8,"type":"horse"},"select":["id","type"]}'
# 用返回的 id：
python3 .claude/skills/worlddriver-rpc/rpc.py mc.bot.useItem '{"entityId":<HORSE_ID>}'
```

预期：`{ok:true, riding:"minecraft:horse", result:"SUCCESS", consumed:true, screen:"none"}`。
佐证 + 收尾：

```bash
python3 .claude/skills/worlddriver-rpc/rpc.py mc.client.screenshot '{}'   # 视觉证据：马背视角
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"ride @p dismount"}'
```

（道具 mob 加 `NoAI:1b` 防溜达——arena 纪律；query 必须带 `center`，缺省是 ORIGIN 不是玩家。）

- [ ] **Step 4: 正例② 挤奶（手持物决定结果）**

```bash
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"summon minecraft:cow 2 -60 2 {NoAI:1b}"}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"item replace entity @p weapon.mainhand with minecraft:bucket"}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.query '{"q":"entities","center":{"x":0,"y":-60,"z":0},"filter":{"in_radius":8,"type":"cow"},"select":["id"]}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.bot.useItem '{"entityId":<COW_ID>}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.observe.player '{}' | grep -A3 mainHand
```

预期：`consumed:true`，mainHand 变 `minecraft:milk_bucket`。

- [ ] **Step 5: 正例③ 交易 UI**

```bash
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"item replace entity @p weapon.mainhand with minecraft:air"}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"summon minecraft:villager 2 -60 4 {NoAI:1b,VillagerData:{profession:\"minecraft:librarian\",level:1,type:\"minecraft:plains\"}}"}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.query '{"q":"entities","center":{"x":0,"y":-60,"z":0},"filter":{"in_radius":8,"type":"villager"},"select":["id"]}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.bot.useItem '{"entityId":<VILLAGER_ID>}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.client.screen.info '{}'
python3 .claude/skills/worlddriver-rpc/rpc.py mc.client.screen.close '{}'
```

预期：useItem 返回 `screen:"MerchantScreen"`；screen.info 同名佐证；close 收尾。

- [ ] **Step 6: 客户端套件回归**

```bash
python3 .claude/skills/worlddriver-rpc/rpc.py mc.client.chat.send '{"text":"/agent test"}'
# 等 ~60-90s（前台 sleep 分段），然后收割结果：
python3 .claude/skills/worlddriver-rpc/rpc.py mc.client.chat.history '{"limit":20}'
```

预期：总数比上轮基线 +2（12_use_item 新增两用例），无新 FAIL
（既有 ~6 个 client-flaky 见 reference_client_relaunch，与本改动无关；
对比方法：只看是否出现 12_use_item / 16_attack_entity 相关的新失败名）。

- [ ] **Step 7: 清场 + 记录**

```bash
python3 .claude/skills/worlddriver-rpc/rpc.py mc.action.runCommand '{"cmd":"kill @e[type=!minecraft:player]"}'
```

把 Step 2-6 的实际返回粘进任务记录（观测数据佐证，不许"应该没问题"）。

---

### Task 5: headless GameTest 全量回归（收官门）

**Files:** 无改动；CI 门。

- [ ] **Step 1: 确认无残留 gametest 进程再启动**

```bash
ps aux | grep TransformerRuntime | grep -v grep   # 必须为空，否则按 PID kill
cd /root/source/minecraft/AI-assisted-Minecraft-Developers/worlddriver
./gradlew :neoforge:runGameTestServer > /tmp/claude-0/-root-source-minecraft-AI-assisted-Minecraft-Developers/740d145d-e0fc-48b4-bc0f-024af56f492e/scratchpad/gt-entityinteract.log 2>&1 &
```

- [ ] **Step 2: 前台阻塞分段等（每段 ≤10min），直到进程退出**

```bash
while pgrep -f runGameTestServer >/dev/null; do sleep 20; done
grep -E "required tests passed|FAILED|BUILD" /tmp/claude-0/-root-source-minecraft-AI-assisted-Minecraft-Developers/740d145d-e0fc-48b4-bc0f-024af56f492e/scratchpad/gt-entityinteract.log | tail -5
```

（timeout 600000，一段没等完就再来一段，本回合内收割。）

预期：`74 required tests passed`（当前基线）+ BUILD SUCCESSFUL。
**只信 required 行**——TOTAL 行会掩盖 required 失败（已知坑）。
12_use_item.js 的新用例在 headless 下走 skipped-PASS 分支，不增加 required 数。

- [ ] **Step 3: 全绿后收官**

若用户在场：走 superpowers:finishing-a-development-branch。
不在场：停在已提交状态汇报（master 直改仓库，无分支合并动作）。

---

## Self-Review 记录

- **Spec 覆盖**：§4.1 行为/参数/返回 → Task 1；§4.2 触点 4 文件 → Task 1+2；
  §4.3 try/finally 恢复 shift → Task 1 Step 3 代码；§4.4 测试（live 主裁判
  三正例+负例、套件对称加用例、headless skipped-PASS）→ Task 1 Step 1 + Task 4 + Task 5；
  CHANGELOG（AGENTS.md 工具变更惯例）→ Task 3。无缺口。
- **占位符**：无 TBD/TODO；所有代码步骤给了完整代码；`<HORSE_ID>` 等是运行时查询值，
  紧跟其 query 命令。
- **类型一致性**：`useItemOnEntity(Map<String,Object>)` 三处（接口/实现/委托）同名同签名；
  返回键 Task 2 描述与 Task 1 代码逐键核对一致（riding/screen 缺省 "none"）。
- **修正一处**：villager 无职业不开交易 UI —— summon 带 `VillagerData` 职业；
  马/牛加 `NoAI:1b`（道具 mob 纪律）。

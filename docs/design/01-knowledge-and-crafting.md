# 设计文档 01 —— 合成知识与执行

**Historical — dated 2026-06-04, superseded by the shipped `mc.recipe.lookup` / `mc.recipe.resolve`
reads and the `mc.plan.acquire` planner.**

> 覆盖 ROADMAP Phase D（知识，只读）+ Phase E（执行）。
> 参考：`research-clones/altoclef/` 的 `trackers/CraftingRecipeTracker.java`、`tasks/CraftInInventoryTask.java`、`tasks/container/{CraftInTableTask,SmeltInFurnaceTask}.java`、`util/{CraftingRecipe,RecipeTarget}.java`、`TaskCatalogue.java`

## 1. 核心判断：合成表本体是 `RecipeManager`

游戏里**已经有合成表**——`level.recipeManager`（服务端权威，客户端有同步副本）。所有 mod 在注册期把配方塞进去。JEI/EMI 只是它的可视化前端。所以：

- **查合成 = 查 `RecipeManager`**，覆盖原版 + 绝大多数模组的 `crafting_shaped`/`crafting_shapeless`/`smelting`/`blasting`/`smoking`/`stonecutting`/`smithing`。
- **不 scrape JEI**。JEI/EMI 集成只作可选 compat，补 `RecipeManager` 之外的少数东西（部分模组机器加工配方、世界生成产物、怪物掉落）。

altoclef 的反面教训：它用 `TaskCatalogue` **硬编码**了"每种资源怎么获得"的映射（几百行 static 块）。维护成本高、对模组覆盖差。我们**数据驱动**直接读 `RecipeManager`，免维护、自动覆盖装进来的任意模组。

## 2. Phase D —— 知识层（只读 API）

新增 `api/RecipeApi.java`，纯读，不碰写路径（不触发 AGENTS.md #2 的 `server.execute()` 约束，但读取要在 server 线程或用同步副本——见 §2.4）。

### 2.1 `mc.recipe.lookup`

```
入: { result?: "minecraft:diamond_pickaxe", ingredient?: "minecraft:diamond", limit?: 20 }
出: [{
      id: "minecraft:diamond_pickaxe",
      type: "crafting_shaped",
      result: { id, count },
      ingredients: [                       // 每格一个"可接受集合"
        { slot: 0, accepts: ["minecraft:diamond"], tag: null },
        { slot: 3, accepts: ["minecraft:stick"],  tag: null }, ...
      ],
      pattern: ["DDD"," S "," S "],         // shaped 才有
      station: "crafting_table" | "inventory2x2" | "furnace" | "smithing_table" | "stonecutter"
   }]
```

- 用 `recipeManager.getRecipes()` 遍历，按 `result`/`ingredient` 过滤。
- `Ingredient` 展开成"可接受 item 集合"（tag 配方如 `#minecraft:planks` 列出所有成员 + 标注 `tag`），让上层能选手头有的料。
- `station`：从 `RecipeType` 推断（`CRAFTING` 且 2×2 能放下 → 也标 `inventory2x2`）。

### 2.2 `mc.recipe.resolve`（最值钱的一个）

把"我要 X"递归展开成"先做什么、缺什么"。这是程序该算、LLM 算容易错的东西。

```
入: { target: "minecraft:diamond_pickaxe", count: 1, have?: <inventory snapshot> }
出: {
     steps: [                              // 拓扑排序，照着从上往下做就行
       { craft: "minecraft:oak_planks", count: 4, from: "minecraft:oak_log×1", station: "inventory2x2" },
       { craft: "minecraft:stick",      count: 2, from: "minecraft:oak_planks×2", station: "inventory2x2" },
       { craft: "minecraft:diamond_pickaxe", count: 1, from: "diamond×3 + stick×2", station: "crafting_table" }
     ],
     missing: [ { item: "minecraft:diamond", count: 3 } ],   // 叶子层手头没有的原料
     stations_needed: ["crafting_table"]
   }
```

算法：

1. 从 target 取一条配方（多条时择优：优先手头料齐 > 产出/原料比高 > 形状简单）。
2. 对每个原料：`have` 够 → 扣减；不够 → 若它本身可合成则递归展开，否则进 `missing`。
3. `tag` 原料：在 `have` / 可合成集合里**任选一个成员**满足（如 `#planks` 用现有任意木板）。
4. **环检测**：维护正在展开的 target 栈，命中即停（避免 A→B→A 死循环，如某些"压缩/解压"互逆配方）。
5. 输出按依赖拓扑排序。

### 2.3 tag 查询

`mc.observe.tags{item}` 返回该物品所属 tag；`mc.query` 增加 `tag` 过滤。让 T2 能按 `#c:ingots`、`#minecraft:planks` 这类标签推理"什么能替代什么"。

### 2.4 线程

`RecipeManager` 读取走 server 线程（用现有 `DriverApi` 的同步 helper 模式），或用客户端同步副本（client-only 场景，bot 子系统本就在 client）。与现有 `mc.observe.*` 一致，不新增线程模型。

### 2.5 验证（`43_recipe.js`）

- `resolve` 钻石镐：断言 steps 含 planks→stick→pickaxe 三步、missing 含 3 钻石。
- tag 配方：背包放桦木板，resolve 工作台，断言用桦木板满足 `#planks` 而非报缺料。
- 环：构造/挑一对互逆配方，断言不死循环。
- 三传输 parity。

## 3. Phase E —— 执行层（T1 process）

把 resolve 出的计划落地成真实合成动作。全部作为 `BotProcess` 跑在 Phase A 的 `UserTaskChain` 下（可被战斗抢占、之后恢复）。

### 3.1 `CraftProcess`

```
mc.bot.craft{ item: "minecraft:diamond_pickaxe", count: 1 }
```

内部流程：

1. `resolve` 计划。`missing` 非空 → 立刻返回 `{ok:false, missing:[...]}`，交还 T2 决策（去挖/去合成缺料）。**mod 不自己去采集**——那是 Phase H 规划器职责。
2. `stations_needed` 含 crafting_table：
   - 背包有工作台 → 找面前空地 `useItemOn` 放下（复用既有放置仿真）；
   - 没有 → 先递归 `craft` 一个工作台（2×2 可搓）。
3. 按 steps 逐步执行：
   - 2×2 步：直接在背包合成格 `slotClick` 摆料。
   - 工作台步：`useItemOn` 开台 → 等 `ContainerScreen` 打开（`mc.wait.condition`）→ 按 `pattern` 把料 `slotClick` 进对应格 → shift 取结果到背包。
4. 循环到 `count` 满；每轮校验背包产物计数（防 slot 竞争/拾取漏掉）。

关键复用：`mc.client.input.slotClick(slot, button, type)`、`mc.client.input.setHotbarSlot`、`mc.observe.container`（读合成格/结果格状态）。**不需要新键鼠原语**。

altoclef 对照：`CraftInInventoryTask`（2×2）、`CraftInTableTask`（工作台）。注意它 `onResourceStart` 里先清空 cursor slot（把光标上挂着的物品放回/丢弃）——我们也要做，否则摆料会被光标残留污染。

### 3.2 `SmeltProcess`

```
mc.bot.smelt{ input: "raw_iron", count: 8, fuel?: "coal" }
```

1. 有炉子走到/放炉子（同 craft 的工作台逻辑）。
2. 开炉 → `slotClick` 放 input（上格）+ fuel（下格）。fuel 未指定则从背包挑可用燃料（煤/木炭/木头/熔岩桶，按效率）。
3. `mc.wait.condition` 等输出格累计到 count（轮询 `mc.observe.container` 的结果格）。
4. shift 取出。
5. 超时（燃料烧完仍不够）→ 报缺燃料，交还 T2。

altoclef 对照：`SmeltInFurnaceTask`。

### 3.3 `SmithProcess`（可选）

锻造台升级（下界合金、模板）。结构同上，三格（模板/基底/材料）。altoclef：`UpgradeInSmithingTableTask`。Boss 装备链（Phase F）可能需要，按需做。

### 3.4 验证（`44_craft.js`）

- 给齐原料：断言搓工作台 → 搓木镐 全程经 slot 仿真、背包出现产物。
- 熔炼：给铁矿 + 煤，断言炉子产出铁锭。
- 缺料：故意不给钻石，断言 `craft 钻石镐` 返回 `{ok:false, missing:[diamond×3]}` 而非卡死。
- 三传输 parity（craft 的 `{started}` + 最终 status）。

## 4. 对外接口小结（控制 tool 数量，遵 AGENTS.md #6）

| tool | 层 | 说明 |
|---|---|---|
| `mc.recipe.lookup` | D | 查配方（result/ingredient 两用，一个 verb） |
| `mc.recipe.resolve` | D | 递归制作计划 + 缺料 |
| `mc.bot.craft` | E | 合成（内部吞 resolve；2×2 与工作台用同一 verb，靠 station 自动分流） |
| `mc.bot.smelt` | E | 熔炼（含可选 smith 模式？或独立，评估后定） |

`mc.observe.tags` 视情况合并进 `mc.query` 的 `select`/`filter`，避免新增 verb。

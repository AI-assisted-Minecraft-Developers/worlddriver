# 设计文档 03 —— Boss 作战剧本（末影龙 / 凋零）

> 覆盖 ROADMAP Phase G。依赖 A（调度器）、B/C（御敌+战斗）、E（合成）、F（装备）全部就位。
> 参考：`research-clones/altoclef/` 的 `tasks/speedrun/{KillEnderDragonTask,KillEnderDragonWithBedsTask,DragonBreathTracker,WaitForDragonAndPearlTask}.java`、`tasks/construction/ProjectileProtectionWallTask.java`

## 1. 关键决策：Boss 剧本写成 Rhino 脚本，不硬编码 Java

Boss 战本质是**多阶段状态机**，每个阶段就是"编排已有原语（combat / goto / elytraFly / equip / useItem）+ 读 boss 专属状态 + 切换 T0 反射开关"。这种高层逻辑放进**已有的 Rhino 沙箱层**（`config/worlddriver/scripts/`）有三个好处：

1. **热迭代**：调打法不用重新编译 mod，`/agent reload` 即生效。Boss 战极依赖调参（距离阈值、何时切弓/切剑、躲避半径），Java 改一次编译一次太慢。
2. **可贡献**：社区能写/分享剧本，像数据包一样。
3. **职责清晰**：Java 只暴露原语 + boss 专属感知；"怎么打"留在脚本。

Java 侧只需补：boss 专属感知 API（end crystal 列表、dragon phase、boss 血阶）+ 在 `prelude.js` 暴露高层 helper（`bot.combat`、`bot.goto`、`bot.setting` 已有）。剧本是 `playbooks/dragon.js`、`playbooks/wither.js`。

> 反面参考：altoclef 把屠龙写成 Java `KillEnderDragonTask`，改打法要重编译。我们用脚本换迭代速度。

## 2. 需要 Java 侧补的感知

新增/扩展（只读，遵循现有 observe 模式）：

```
mc.observe.boss              → { type, present, health, maxHealth, phase?, pos, bossBar% }
mc.query{q:'entities', filter:{type:'end_crystal'}}   → 已有 query 能力, 标注 crystal 是否被铁笼罩(beam target)
mc.observe.threats           → 已有(02 文档), 含 incomingProjectiles(龙息/凋零骷髅头/恶魂火球)
```

- **dragon phase**：读 `EnderDragonEntity` 的 `phaseManager`（perch / strafing / charging_player / dragon breath 等），剧本据此切近战/远程/躲避。
- **end crystal 笼罩**：柱顶水晶有铁栏笼罩的需先打掉笼子或爬上去；暴露的可直接弓射。query 标注。

## 3. 末影龙剧本（`playbooks/dragon.js`）

阶段机（伪码）：

```js
// 前置：已带 Phase F 装备(全甲+剑+弓箭) + 建材(末地石~64) + 水桶 + 床(可选)
bot.setting({ autoTotem:true, autoHeal:true, autoDodge:true });  // T0 全开

while (boss().present) {
  const crystals = query('entities', {type:'end_crystal'});
  if (crystals.length) {
    // 阶段1: 必须先清水晶, 否则龙血回满(硬门槛)
    const c = nearest(crystals);
    if (c.caged) { /* 爬柱子 goto{c.pos+up} 近战砸; 或弓射缝隙 */ }
    else         { bot.combat({mode:'kill', target:{id:c.id}}); }  // 暴露的直接打
    continue;
  }
  // 阶段2: 水晶清零, 打龙本体
  const ph = boss().phase;
  if (ph === 'perch') {
    // 龙停在传送门基座 -> 冲上去近战刨头(头部判定)
    bot.goto({pos: exitPortalTop}); bot.combat({mode:'kill', target:{type:'ender_dragon'}});
  } else {
    // 飞行中 -> 只能弓射头部; 持续用 dodge 躲冲撞/龙息
    aimAndShootBow(dragonHead());
  }
  // 龙息毒云: T0 autoDodge + DragonBreathTracker 已处理离开毒云
}
```

要点：

- **水晶是硬门槛**：没清完龙血回满，剧本必须先清完所有水晶再碰龙。altoclef `KillEnderDragonTask` 同结构（先 `CollectEndStoneTask` 备建材塔上去够笼中水晶，再 `PunkEnderDragonTask`）。
- **perch 阶段是主要输出窗口**：龙落基座时近战头部，伤害最高。
- **可选：终界床炸 perch**：perch 时在龙头位置放床并引爆，单次爆炸伤害极高。作为 `playbooks/dragon-beds.js` 变体，对应 altoclef `KillEnderDragonWithBedsTask`。
- 别误伤末影人（看它们会激怒）——剧本里攻击目标过滤掉 enderman，altoclef 用 `addForceFieldExclusion` 排除。

## 4. 凋零剧本（`playbooks/wither.js`）

凋零比龙更吃前置和场地。

```js
// 前置(剧本开头硬校验, 不满足就 abort 交还 T2):
//   全套附魔甲(F) + 治疗药×N + 力量药 + 抗性药 + 好剑 + 封闭基岩空间
ensureGear(['enchanted_armor_full','healing_potions>=8','strength_potion','good_sword']) || abort();

const arena = findOrDigSealedSpace();  // 基岩封闭, 防它砸穿地形逃跑
summonWither(arena);
bot.setting({ autoTotem:true, autoHeal:true, autoDodge:true, autoRetreat:false }); // 封闭场不撤

while (boss().present) {
  const hp = boss().health / boss().maxHealth;
  if (hp > 0.5) {
    // 阶段1: 半血以上免疫弹射物 -> 纯近战; 召唤瞬间会爆炸, 拉开等爆完再上
    bot.combat({mode:'kill', target:{type:'wither'}});
  } else {
    // 阶段2: 半血以下乱飞 -> 持续近战追击, 它会砸地, 清被砸出的怪
    bot.combat({mode:'kill', target:{type:'wither'}});
  }
  // 全程: 躲凋零骷髅头(incomingProjectiles), 中"凋零"减益就喝奶/拉开
  if (hasEffect('wither')) drinkMilkOrRetreat();
}
```

要点：

- **召唤即爆**：放下凋零之首的瞬间凋零会蓄力爆炸，剧本要在召唤后拉开距离等爆炸过去再接近。
- **半血分界**：>50% 免疫弹射物只能近战；<50% 解除免疫但会乱飞钻地，继续近战追。
- **封闭场地**：基岩盒防它阶段2砸穿地形跑掉。挖/找场地复用 Phase E 的放置 + `clearArea`。
- **凋零减益**：被凋零头命中叠"凋零"持续掉血，喝奶解除或拉开。
- 凋零无现成 altoclef 实现，自研；但弹射物躲避、装备前置、近战循环都复用 B/C/F。

## 5. 剧本如何挂进系统

- 剧本是 `mc.script.eval` 能跑的 JS，或落盘 `config/worlddriver/scripts/playbooks/*.js` 由 `/agent reload` 载入。
- 对外触发：T2 调 `mc.bot.playbook{name:"dragon"}`（或直接 `mc.script.eval` 跑剧本文件）。剧本内部循环调 `mc.bot.combat/goto/...`，靠 Phase A 调度器 + T0 反射兜底保命。
- 剧本跑在**现有 Rhino 沙箱**内，不放宽权限（AGENTS.md #3）。剧本只是编排 `Driver.invoke(...)`，不碰禁用类。

## 6. 验证

Boss 战难做确定性 GameTest（实体 AI 有随机性），分层验证：

- **感知层**：GameTest `/summon` end_crystal / wither，断言 `mc.observe.boss`、crystal query、phase 读取正确。
- **关键不变量**：龙剧本——断言"所有水晶清零后龙 health 开始单调下降"（验证没漏水晶）；凋零——断言"召唤后剧本进入拉开距离状态"（验证防爆）。
- **冒烟**：creative + `/effect` 给满 buff 的世界里跑全程，人工 + 截图确认能通。不强求 CI 必过（实体随机性），作为手动回归。

## 7. 与其它文档的依赖

| 剧本用到 | 来自 |
|---|---|
| `bot.combat`（选目标/冷却/暴击/kite） | 02 文档 Phase C |
| T0 autoTotem/autoHeal/autoDodge/autoRetreat | 02 文档 Phase B |
| ensureGear / equip | 02 文档 Phase F |
| 放置场地块 / clearArea | 现有 build/clearArea + 01 文档 craft |
| 抢占与恢复（被打断后继续阶段） | 00 文档 Phase A 调度器 |
| 高层 helper 暴露 | 现有 Rhino prelude |

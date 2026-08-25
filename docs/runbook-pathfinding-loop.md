# Pathfinding Conformance Loop — Runbook

## Client 启动 / relaunch
- `DISPLAY=:99 ./gradlew :fabric:runClient`(Xvfb :99,MCP 39800,RPC 39801)。日志重定向到 `logs-relaunch/rc*.log`(scratchpad/tmp 会被清,用 project-dir)。
- 菜单导航:Singleplayer (213,106) → Mountains (213,68) → Play Selected (134,198) → `mc.wait.worldReady`。
- relaunch 前 kill stray gradle(否则持 `.gradle` 锁致 exit 1):
  `ps aux|grep -iE "java.*(GradleWrapper|fabric.*runClient)"|grep -v grep|awk '{print $2}'|xargs -r kill -9`。
- **禁止在 bot 处于水中时 relaunch**(先 tp 干地)。
- Client GL hang(shader render 线程):需系统重启(fresh Xvfb 无效)。

## 设 flag(新 flag 必须走 RPC)
- MCP 工具 schema 在 session 启动冻结 → 新加的 BotConfig key 被 strip。冻结的是**客户端手里那份拷贝**;
  mod 这侧的 schema 由 `SettingsRegistry.schemaProps()` 现算,永远不会陈旧。
- 用 RPC(port 39801,见 worlddriver-rpc skill 的 rpc.py)或 `mc.script.eval` 里 `Driver.invoke('mc.bot.setting', {...})`。
- 注意:script.eval 里 `Java.type` 不可用;读设置用 `Driver.invoke('mc.bot.setting', {}).settings`。

## 三件套分层判别器(定 class A vs B)
1. `mc.observe.map` / `mc.client.blocks` — 真几何(非假设)。
2. `mc.debug.plan {goal:{x,z}, chain:true}` — planner 判别(reached/maxRegression/backwardSegments)。
3. `[walker]` telemetry + `scripts/pmcs/run_case.py` conformance — executor 判别(哪个 Move churn)。
- planner 干净(maxReg≈0)+ executor churn = **class B**(执行器脆弱,硬化 executor);
  planner 发不可实现 move = **class A**(收紧 planner 谓词)。

## maxStuck 读取
- `grep '[walker] t=' fabric/run/logs/latest.log | <parse totStuck 峰值>`,或 `scripts/pmcs/run_case.py`,或 `wjourney.py`(仓库根)。

## flag 接线(新增 default-OFF walker flag):两步必需 + 一步可选
1. **声明** —— `BotConfig.java`:`public static volatile boolean walkerX = false;`。
   **这是唯一的注册步骤**:`SettingsRegistry.reflectivePrimitiveFields()` 自动收它,而这一个方法同时喂
   key 集合、`SettingsSnapshot.build` 的读取路径、以及 `BotTools` 生成的 schema,所以 setter / 快照 /
   schema 三者自动跟随且**不可能漂移**。
   ⚠️ 早先那份「五处手工接线」的配方已整个失效,而且照做会**重新引入一个已修好的缺陷**:
   schema 或 apply 分支没跟上新声明时,apply 路径会**静默丢弃**该 key(见 `SettingsRegistry` 的 javadoc)。
2. **读取点** —— 如 `Walker.java`:`if (BotConfig.walkerX && <cond>) {...}`。**不是可选的**:
   `common/src/test/.../SettingsConsumerTest.java` 从 `reflectivePrimitiveFields()` 取同一份 key 集合,
   任何 key 若在设置管线之外从没被读过就**判构建失败**——否则 `mc.bot.setting` 报成功、快照把 true 回显给你、
   而行为一点没变。
3. **可选** —— `SettingsDocs.java` 里加一行说明,会渲染成该属性的 schema description。没有说明的 key 是明确
   允许的(116 个没有),但**孤儿行**(说明指向已改名或已删除的 key)会在类加载时经
   `SettingsRegistry.assertDocsResolve()` 抛异常。

## 终验协议(#47)
- 3 随机起终点 × 各 3 replay = 9 clean + live-screen-watch video 零卡点(见 live-screen-watch skill;
  Monitor 直接跑 `STDOUT_MODE=event ../.venv/bin/python3 run.py`,TaskStop 收)。

## 已知坑
- `du -h` 在循环里可能误显 512;以 `ls -la` 为准。
- 系统重启清整个临时 root(JDK/Xvfb/Mesa/fabric/run 全丢),只 /home 持久 → corpus 归档必须 git 提交才持久。

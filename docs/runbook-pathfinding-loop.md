# Pathfinding Conformance Loop — Runbook

## Client 启动 / relaunch
- `DISPLAY=:99 ./gradlew :fabric:runClient`(Xvfb :99,MCP 39800,RPC 39801)。日志重定向到 `logs-relaunch/rc*.log`(scratchpad/tmp 会被清,用 project-dir)。
- 菜单导航:Singleplayer (213,106) → Mountains (213,68) → Play Selected (134,198) → `mc.wait.worldReady`。
- relaunch 前 kill stray gradle(否则持 `.gradle` 锁致 exit 1):
  `ps aux|grep -iE "java.*(GradleWrapper|fabric.*runClient)"|grep -v grep|awk '{print $2}'|xargs -r kill -9`。
- **禁止在 bot 处于水中时 relaunch**(先 tp 干地)。
- Client GL hang(shader render 线程):需系统重启(fresh Xvfb 无效)。

## 设 flag(新 flag 必须走 RPC)
- MCP 工具 schema 在 session 启动冻结 → 新加的 BotConfig key 被 strip(`BotTools.java` 的 `.prop` 白名单)。
- 用 RPC(port 39801,见 worlddriver-rpc skill 的 rpc.py)或 `mc.script_eval` 里 `Driver.invoke('mc.bot.setting', {...})`。
- 注意:script_eval 里 `Java.type` 不可用;读设置用 `Driver.invoke('mc.bot.setting', {}).settings`。

## 三件套分层判别器(定 class A vs B)
1. `mc.observe.map` / `mc.client.blocks` — 真几何(非假设)。
2. `mc.debug.plan {goal:{x,z}, chain:true}` — planner 判别(reached/maxRegression/backwardSegments)。
3. `[walker]` telemetry + `scripts/pmcs/run_case.py` conformance — executor 判别(哪个 Move churn)。
- planner 干净(maxReg≈0)+ executor churn = **class B**(执行器脆弱,硬化 executor);
  planner 发不可实现 move = **class A**(收紧 planner 谓词)。

## maxStuck 读取
- `grep '[walker] t=' fabric/run/logs/latest.log | <parse totStuck 峰值>`,或 `scripts/pmcs/run_case.py`,或 `scripts/wjourney.py`。

## 五处 flag 接线(新增 default-OFF walker flag)
1. `BotConfig.java`:`public static volatile boolean walkerX = false;`(autoSwim@206, FBA@1429, apw@1417)。
2. `SettingsCommand.java` setter(~276):`if (params.get("walkerX") instanceof Boolean b) { BotConfig.walkerX=b; applied.add("walkerX"); }`。
3. `SettingsCommand.java` snapshot(~866):`snap.put("walkerX", BotConfig.walkerX);`。
4. `BotTools.java` schema(~380):`.prop("walkerX", bool())`。
5. `Walker.java` gate:`if (BotConfig.walkerX && <cond>) {...}`。

## 终验协议(#47)
- 3 随机起终点 × 各 3 replay = 9 clean + live-screen-watch video 零卡点(见 live-screen-watch skill;
  Monitor 直接跑 `STDOUT_MODE=event ../.venv/bin/python3 run.py`,TaskStop 收)。

## 已知坑
- `du -h` 在循环里可能误显 512;以 `ls -la` 为准。
- 系统重启清整个临时 root(JDK/Xvfb/Mesa/fabric/run 全丢),只 /home 持久 → corpus 归档必须 git 提交才持久。

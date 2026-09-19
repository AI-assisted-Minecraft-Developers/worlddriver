# 给运行中的游戏 JVM 挂调试器

这份文档回答三件事：怎么让 worlddriver 起的任何一个游戏 JVM 带上 JDWP、把游戏停下来要付什么代价、
两个调试前端（JDK 自带的 jdb 与 MCP 服务器 jdwp-inspector）各自的实测边界。
除了明确标出的一行，所有内容都在 2026-09-04 于本机跑过。

## 1. 让游戏 JVM 带上 JDWP

JDWP agent 只能在 JVM **启动时**装进去。JDK 21 的 `libjdwp.so` 里还留着 `onjcmd=y` 加
`jcmd <pid> VM.start_java_debugging` 这条事后启动的隐藏路，JDK-8336401 已排定删除，别依赖它。
所以先决定要调哪一趟，再起它。

### 任何 loom 运行任务，以及所有 StageWright 闸

`build.gradle` 末尾的 Debugger hook 读项目属性 `worlddriverJdwp`，有值就给每个 `loom.runs` 条目加
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:<值>`：

```bash
ORG_GRADLE_PROJECT_worlddriverJdwp=5005 ./gradlew :fabric:runClient
./gradlew -PworlddriverJdwp=5005 :fabric:runClient      # 等价写法
```

- **不设就没有。** 默认构建、默认闸完全不受影响。
- `suspend=n`：游戏照常启动，调试器随时 attach、随时 detach；没连调试器时运行不受影响。
- **闸也吃到同一个开关。** StageWright 的 `SideProcesses.jvmArgs(spec)`（`gradle-plugin` 模块）把 loom
  `JavaExec` spec 里的 `jvmArguments` 抄给闸起的游戏进程，所以 `stagewrightDedicatedServerFabric`、
  `journey*`、`rehearsal*` 都能这样带上 agent。
- **双进程拓扑**（`dedicatedServerWithClient*`）两个 JVM 会抢同一个端口。传 `worlddriverJdwp=0`
  让系统分配，然后从各自游戏日志开头读 `Listening for transport dt_socket at address: N`。

### 为什么是 Gradle 属性，不是 `JAVA_TOOL_OPTIONS`

`JAVA_TOOL_OPTIONS` 对进程树上每个 JVM 生效：gradle daemon 自己也会去监听那个端口，第二个 JVM 就
`Address already in use`；而一个已经在跑的 daemon 派生游戏 JVM 时**不会**继承你这次 shell 里的环境变量。
coverage hook 2026-07-19 就是这样量出一片 0%。`ORG_GRADLE_PROJECT_*` 走 gradle 客户端到构建的属性通道，
不管 daemon 新旧都到得了。

### `--debug-jvm`

Gradle 对所有 `JavaExec` 任务内建 `--debug-jvm`（`suspend=y`、端口 5005、JVM 停在 `main` 之前等调试器）。
loom 的 `RunGameTask` 继承 `JavaExec`，所以 `./gradlew :fabric:runClient --debug-jvm` 理论上可用。
**这一条没在本机跑过**（2026-09-04 离线解析不了 loom 1.11-SNAPSHOT），用之前自己验一次。

### 认清是谁的 JVM

```bash
ps -C java -o pid,args
```

按命令行认：游戏进程带 `TransformerRuntime` / `DevLaunchInjector`；`gradle-worker`、`GradleDaemon` 都不是。
`jps` 看到的 `BootstrapLauncher` 可能是用户 IDE 里另一个项目。本机禁用 `pkill` / `pgrep`。

## 2. 停下来的代价

游戏是实时循环，挂起不是免费的。

| 目标 | 停下来会怎样 |
|---|---|
| 专用服（`dogfoodServer`、所有 `stagewrightDedicatedServer*`） | **watchdog 会自杀。** `server.properties` 的 `max-tick-time` 默认 60000 ms，一个 tick 超过它服务器就退出。断点前在 `<loader>/run-*/server.properties` 加 `max-tick-time=-1`。**没有任何构建脚本替你写这一行**：`fabric/build.gradle` 的 `provisionRun` 只写 seed、online-mode、port |
| 集成服 / 客户端（`runClient`、`journey*`、`rehearsal*`） | 没有 watchdog，窗口冻住，恢复后正常 |
| 所有拓扑 | RPC 客户端（`rpc.py`、Journeyman、MCP 的 `mc.*` 工具）在挂起期间都超时。这是预期，不是新缺陷 |
| StageWright 全量闸 | 场景的 tick 预算随游戏一起停，但编排层的挂钟（`within()`）不停，停久了场景按超时判红。**别在全量闸里下会停的断点**；要调闸里的场景，用 `runClient` 或排练任务复现 |

因此顺序是：**能 logpoint 就别 breakpoint**（不停机，只记读数）；**要停就只停一个线程**
（JDWP 的 `SUSPEND_EVENT_THREAD`）；只有真的要盯住一格世界状态才挂起整个 VM。

不停机的替代品：

- `mc.script.eval`：游戏内 Rhino REPL，能读改任何 public 状态，不需要 agent
  （`.agents/skills/worlddriver-rpc`）。
- `jstack <pid>`：只看线程此刻在哪，不需要 agent。栈顶散在同一个环里就是循环，不是阻塞。
- Arthas 这类字节码注入型工具能不停机 watch 方法出入参，**未在本仓库验证**。

## 3. Minecraft 与本仓库特有

- **类名按 named mappings 写。** dev 运行时的 Minecraft 类名与 `genSources` 出来的源码一致
  （`net.minecraft.world.entity.player.Player`），不是 intermediary。mod 自己的类写全限定名。
- **Mixin handler 挂在目标类上**，方法名带 `handler$…` 前缀，行号来自 mixin 源文件。
  断点先用目标类名加 mixin 源行号试；找不到就列目标类的方法（jdb `methods <类>`）。
- **Minecraft 本体没有局部变量表。** vanilla 帧只有栈和行号，`locals` 报 not available；
  行断点、`where`、字段读取照常。mod 代码由 Gradle 默认带 `-g` 编译，locals 齐全。
- **线程名**：客户端 `Render thread`，集成服 `Server thread`，专用服只有 `Server thread`。
- **仪器写的是采样时刻，断点处的 locals 才是「此刻」。** `hp.trace`、evidence map 各有自己的写入时刻，
  判一个动作用紧挨它的读数。

## 4. 两个前端

### jdb（JDK 自带）

```bash
jdb -J-Duser.language=en -J-Duser.country=US -sourcepath common/src/main/java -attach 127.0.0.1:5005
```

- `-J` 把选项给 jdb 自己的 JVM。**必须强制英文**：zh_CN 的机器上 jdb 输出「正在初始化jdb...」「VM 已启动」，
  任何按英文 grep 的自动化都会等到超时。
- 没有条件断点；命中时挂起整个 VM；一次一个目标。
- 它是 REPL，非交互 shell 不能直接用。工作区的 `.claude/skills/java-breakpoint-debug/scripts/jdbctl.sh`
  把它放进 tmux，并把「等一个事件」做成阻塞调用。那是工作区本地文件，不随本仓库走。

| gdb | jdb |
|---|---|
| `break Class::m` / `break file:N` | `stop in pkg.Class.m(int)` / `stop at pkg.Class:N` |
| `bt` / `info locals` / `p x` | `where` / `locals` / `print x`、`eval x + 1`、`dump obj` |
| `next` / `step` / `finish` | `next` / `step` / `step up` |
| `set var x=1` | `set x = 1` |
| `watch field` | `watch pkg.Class.field`（修改时停）、`watch access …` |
| `info threads` / `thread N` | `threads` / `thread N` |
| `delete` / `detach` | `clear pkg.Class.m(int)` / `quit`（目标继续跑） |

### mcp-jdwp-java（`jdwp-inspector`）

FgForrest 的 MCP 服务器，v2.10.1，47 个 `jdwp_*` 工具：条件断点、logpoint、按线程挂起、表达式求值、
改局部变量与字段、字段 watchpoint、异常断点。工作区 `.mcp.json` 把它注册为 `jdwp`。实测边界：

- **目标端点只认 `-DJVM_JDWP_HOST` / `-DJVM_JDWP_PORT` 系统属性。** README 写的同名环境变量不生效
  （设了 5006 仍连 5005）。换端口用 `jdwp_wait_for_attach {host, port}` 覆盖，不改配置。
- `jdwp_evaluate_expression`、`jdwp_set_local`、`jdwp_step_*` **必须带 `threadId`**。漏了报
  `primitiveConversion … is null` 的 NPE，是参数缺失，不是服务器坏了。
- **表达式在服务器侧用 Eclipse JDT 编译成类再注入目标。** 类按目标 JVM 的 classpath 解析（必须是绝对路径，loom 是）；
  默认包的类解析不了；只能直接引用 **public** 成员，非 public 字段走 `jdwp_get_fields`。第一次连真游戏先用 `1 + 1` 验。
- 条件编译失败**不会静默跳过**：断点照停，事件里标 `CONDITION_ERROR`。停下来先读这一段再信这次停顿。
- `jdwp_clear` 的 `types` 是逗号分隔字符串或 `"all"`，不是数组。
- 字段 watchpoint 每次读写都触发，挂在每 tick 都碰的字段上会把游戏拖到爬。短用，或 `jdwp_set_field_logpoint` 加条件。
- `jdwp_disconnect` 清空全部断点和对象缓存；游戏重启后接回去用 `jdwp_reconnect`。一个服务器实例同时只连一个目标。

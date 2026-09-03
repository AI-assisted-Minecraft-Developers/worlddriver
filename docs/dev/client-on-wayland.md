# 在 Wayland 桌面上跑客户端

适用于任何起真客户端的运行任务：`runClient`、`stagewrightIntegratedServer*`、`journey*IntegratedServer`、
`journeyDedicatedServerWithClient` 以及它们的 NeoForge 孪生。2026-09-04 在 KDE Plasma（Wayland）加
Xwayland 的 Arch 机器上实测。

## 症状

客户端日志停在

```
[Render thread/INFO] (Minecraft) Backend library: LWJGL version 3.3.3-snapshot
```

之后一行都没有，窗口不出现，渲染线程 100% CPU，闸在 45 分钟后被 Gradle 超时杀掉。`jstack` 里渲染线程停在
`org.lwjgl.glfw.GLFW.nglfwCreateWindow`，状态 RUNNABLE。mod 的 MCP/RPC 端口能应答（它们在建窗口之前起），
但任何要渲染线程的查询超时。

## 原因

LWJGL 3.3.3 自带的 GLFW（3.4 预发布）X11 后端在 `waitForVisibilityNotify` 里等窗口的 VisibilityNotify：

```c
while (!XCheckTypedWindowEvent(display, window, VisibilityNotify, &dummy)) {
    if (!waitForX11Event(&timeout)) return GLFW_FALSE;
}
```

`waitForX11Event` 只要 `XPending()` 非零就立刻返回真。Xwayland 下队列里常有一条别的事件先到，
`XCheckTypedWindowEvent` 永远不消费它，循环没有出口。

## 解法

自己编一份 GLFW 3.4，把那个循环改成有界的（一千次仍等不到就放弃，ICCCM 本来就允许），只编 X11 后端
（本机没装 `wayland-protocols`，Wayland 后端编不了；X11 后端在 Xwayland 上够用）：

```bash
mkdir -p ~/.local/src && cd ~/.local/src
curl -sL https://github.com/glfw/glfw/releases/download/3.4/glfw-3.4.zip -o glfw-3.4.zip && unzip -qo glfw-3.4.zip
# 改 src/x11_window.c 的 waitForVisibilityNotify：while 换成 for (spins < 1000)，超出返回 GLFW_FALSE
cmake -S glfw-3.4 -B glfw-build -G Ninja -DGLFW_BUILD_WAYLAND=OFF -DGLFW_BUILD_X11=ON \
      -DBUILD_SHARED_LIBS=ON -DGLFW_BUILD_EXAMPLES=OFF -DGLFW_BUILD_TESTS=OFF -DGLFW_BUILD_DOCS=OFF
cmake --build glfw-build
mkdir -p ~/.local/lib/glfw-3.4-x11fix && cp glfw-build/src/libglfw.so.3.4 ~/.local/lib/glfw-3.4-x11fix/libglfw.so
```

然后让游戏 JVM 用它。根 `build.gradle` 末尾的 `worlddriverVmArgs` 钩子把一串 JVM 参数加到每个 loom 运行任务上，
StageWright 的闸从 loom 的 `jvmArguments` 抄参数，所以闸也吃到：

```bash
export DISPLAY=:0 XAUTHORITY=/run/user/1000/xauth_XXXX     # 从 `ps -C Xwayland -o args` 的 -auth 读
./gradlew stagewrightIntegratedServerFabric \
    -PworlddriverVmArgs=-Dorg.lwjgl.glfw.libname=$HOME/.local/lib/glfw-3.4-x11fix/libglfw.so
```

`DISPLAY` 设了 StageWright 就不会去起 Xvfb（本机也没装）。装了系统 `glfw` 包的机器直接指
`/usr/lib/libglfw.so.3` 也行，3.4 正式版有同一个循环但通常等得到事件；Xwayland 上先试自带的，卡了再换。

## 认清卡住的是这一步

- `ps -C java -o pid,args` 找带 `TransformerRuntime`/`fabric.dli` 的进程，`jstack <pid>` 看 `Render thread`。
- 本机 `kernel.yama.ptrace_scope=1`，`gdb -p` 拿不到 native 栈；Java 栈里 `nglfwCreateWindow` 加 100% CPU 就够判了。
- 有 `worlddriverJdwp` 的话也能用调试器看，见 [debugging.md](debugging.md)。

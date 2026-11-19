# BridgeTap Compat — Injecting into Real Clients (Lunar / Badlion / Vanilla 1.8.9) / 注入真实客户端（Lunar / Badlion / 原版 1.8.9）

Puts BridgeTap's bridge-speed analysis into an **already running** Minecraft 1.8.9 client process, without modifying client files, adding launch arguments, or requiring Fabric/Forge.

把 BridgeTap 的桥速分析塞进**已经在运行**的 Minecraft 1.8.9 客户端进程里，不改客户端文件、不加启动参数、不需要 Fabric/Forge。

Double-click `dist/BridgeTap.exe` to use it.

双击 `dist/BridgeTap.exe` 即可用。

---

## 1. Why Standard attach Does Not Work / 为什么不能走标准的 attach

Lunar hard-codes this in its JVM arguments:

Lunar 的 JVM 参数里硬编码了：

```
-XX:+DisableAttachMechanism
```

This flag makes HotSpot **not create the attach listener pipe at all**. Measured:

这个 flag 让 HotSpot **根本不创建 attach 监听管道**。实测：

```
$ jcmd 21664 VM.version
com.sun.tools.attach.AttachNotSupportedException: The VM does not support the attach mechanism
$ [System.IO.Directory]::GetFiles("\\.\pipe\") | ? { $_ -match 'java' }
（空）
```

`VirtualMachine.attach()`, `jcmd`, `jmap` and `jstack` all stop working. This is neither a permissions problem nor a bitness problem.

`VirtualMachine.attach()`、`jcmd`、`jmap`、`jstack` 全部失效。这不是权限问题，也不是位数问题。

Runtime environment: Lunar ships `zulu17.70.113-ca-jre17.0.21.0.3-win_x64`.

运行环境：Lunar 自带 `zulu17.70.113-ca-jre17.0.21.0.3-win_x64`。

## 2. How to Get Around It / 怎么绕过去

Instead of the attach protocol, use remote thread injection:

不走 attach 协议，改用远程线程注入：

```
OpenProcess → VirtualAllocEx → WriteProcessMemory → CreateRemoteThread(LoadLibraryW)
```

The payload DLL bootstraps JVMTI by itself inside the target process:

payload DLL 在目标进程内部自己引导 JVMTI：

| Step / 步骤 | Call / 调用 |
|---|---|
| Find the VM / 找 VM | `EnumProcessModules` → `jvm.dll` → `JNI_GetCreatedJavaVMs` |
| Get the environment / 拿环境 | `AttachCurrentThread` → `GetEnv(JVMTI_VERSION_1_2)` |
| Enable a capability / 开能力 | `AddCapabilities(can_retransform_classes)` |
| Append the agent / 挂 agent | `AddToBootstrapClassLoaderSearch(agent.jar)` |
| Start the logic / 起逻辑 | `Class.forName("...Bootstrap", true, null).runNative()` |
| Apply the patch / 打补丁 | `SetEventCallbacks(ClassFileLoadHook)` + `RetransformClasses(Display)` |

JVMTI is available in the live phase and is **not affected by `DisableAttachMechanism`**.

JVMTI 在 live phase 可用，且**不受 `DisableAttachMechanism` 影响**。

## 3. Three Pitfalls You Must Get Past / 三个必须踩过的坑

### 3.1 The JVMTI callback's parameter list is the 1.0 one, not the 1.2 one / JVMTI 回调的参数表是 1.0 的，不是 1.2 的

The JVMTI spec documents the 1.2 form (`class_data` is a `jbyteArray`), but the HotSpot implementation **does not distinguish versions**. In JDK 17's `jvmtiExport.cpp`, `JvmtiClassFileLoadHookPoster::post_to_env`:

JVMTI 规范写的是 1.2 形式（`class_data` 是 `jbyteArray`），但 HotSpot 实现**不分版本**。JDK 17 的
`jvmtiExport.cpp`，`JvmtiClassFileLoadHookPoster::post_to_env`：

```cpp
(*callback)(env->jvmti_external(), jem.jni_env(),
            jem.class_being_redefined(),
            jem.jloader(), jem.class_name(),
            jem.protection_domain(),   // ← the 6th is protection_domain / 第 6 个是 protection_domain
            _curr_len, _curr_data,     // ← the 7th/8th are length and a raw pointer / 第 7/8 个是长度和裸指针
            &new_len, &new_data);
```

Written against the 1.2 signature this yields `declared=677 array=0` — the length is correct, but the slot is not an array at all.
**Write against the typedef in `jvmti.h` itself (10 parameters) and build the byte[] yourself on the C side.**

按 1.2 签名写会拿到 `declared=677 array=0`——长度对，但那个位置根本不是数组。
**照 `jvmti.h` 自己的 typedef 写（10 参数），在 C 侧自己造 byte[]。**

### 3.2 All Classes Must Be on the Bootstrap / 所有类必须在 bootstrap 上

- The injected call `Hook.onFrame()` has to be resolved by `org.lwjgl.opengl.Display`, and Display is defined by the client's isolated loader → only bootstrap classes are visible to it / 注入的调用 `Hook.onFrame()` 要被 `org.lwjgl.opengl.Display` 解析，而 Display 由客户端的隔离 loader 定义 → 只有 bootstrap 的类对它可见
- `Hook.setSink(...)` in turn requires the sink implementation to be the same class object as `Hook.FrameSink` → they must share a loader / `Hook.setSink(...)` 又要求 sink 实现与 `Hook.FrameSink` 是同一个类对象 → 必须同 loader

So **the whole agent fat jar is appended to bootstrap**, not just a small shim. The cost is that on the JVMTI path there is no `Instrumentation`, so `getAllLoadedClasses()` is instead passed in by the payload using `GetLoadedClasses`.

所以**整个 agent fat jar 挂 bootstrap**，不是只挂一个小 shim。代价是 JVMTI 路径下没有
`Instrumentation`，`getAllLoadedClasses()` 改由 payload 用 `GetLoadedClasses` 传进来。

### 3.3 Lunar's Display Has Been Modified, `update()` Cannot Catch It / Lunar 的 Display 被改过，`update()` 抓不到

| Source / 来源 | Display.class | After transform / transform 后 |
|---|---|---|
| LWJGL 2.9.3 | 19791 | 19919 (+128, injected successfully / +128，注入成功) |
| LWJGL 2.9.4-nightly-20230316 | 19982 | 20110 (+128, injected successfully / +128，注入成功) |
| **Lunar runtime / Lunar 运行时** | **20000** | **20092 (+92, not inserted / +92，没插进去)** |

Lunar's ichor modified Display (20000 vs 19982), so the `update()V` chain does not work. Instead, **pick the methods that actually exist, in priority order**:

Lunar 的 ichor 改了 Display（20000 vs 19982），`update()V` 那条链走不通。改为**按优先级挑实际存在的方法**：

```
swapBuffers()V  >  update()V  >  update(Z)V
```

`swapBuffers()` is the final exit point of every frame, so it is reached no matter which entry point the client comes in through.

`swapBuffers()` 是每帧的最终出口，无论客户端从哪个入口进都会走到。

## 4. Why the HUD Is a Separate Jar / HUD 为什么单独一个 jar

`GlHud` calls `GL11.glPushAttrib(...)` and friends directly. On bootstrap it cannot see LWJGL — LWJGL exists only in the client's loader. Hence:

`GlHud` 直接调 `GL11.glPushAttrib(...)` 这些。它在 bootstrap 上看不到 LWJGL——LWJGL 只在客户端的
loader 里。所以：

- `hud.jar` (only `GlHud`) is **not appended to bootstrap** / `hud.jar`（只有 `GlHud`）**不挂在 bootstrap**
- At runtime it is loaded with `new URLClassLoader(urls, Display.class.getClassLoader())` / 运行时用 `new URLClassLoader(urls, Display.class.getClassLoader())` 加载
- It is invoked through the `HudRenderer` interface on bootstrap: the interface comes from the parent chain, the implementation from the child chain, so the types match / 通过 bootstrap 上的 `HudRenderer` 接口调用，接口来自父链、实现来自子链，类型一致

This also fixes a real problem along the way: once the agent jar has been opened by `AddToBootstrapClassLoaderSearch` it **stays locked by the target JVM**, so every injection uses a new timestamped filename; otherwise the second injection always reports "another program is using this file".

顺带解决一个真问题：agent jar 被 `AddToBootstrapClassLoaderSearch` 打开后会**一直被目标 JVM 锁住**，
所以每次注入都用带时间戳的新文件名，否则第二次注入必报"另一个程序正在使用此文件"。

## 5. Usage / 用

```
dist/BridgeTap.exe          # GUI, double-click / GUI，双击
dist/BridgeTap.exe --list   # list processes / 列进程
dist/BridgeTap.exe --pid N  # inject directly / 直接注入
```

In the GUI, Lunar is listed first and shows its window title; the injector itself no longer appears in the list.

GUI 里 Lunar 会排第一并显示窗口标题；注入器自身不会再出现在列表里。

**Injection flow**: try standard attach first (fast); if it fails or `-XX:+DisableAttachMechanism` is detected, fall back to native automatically.

**注入流程**：先试标准 attach（快），失败或检测到 `-XX:+DisableAttachMechanism` 时自动回落 native。

Logs:

日志：

```
%LOCALAPPDATA%\BridgeTap\inject-native.log    # payload (native layer) / payload（native 层）
%LOCALAPPDATA%\BridgeTap\agent.log            # agent (Java layer) / agent（Java 层）
%LOCALAPPDATA%\BridgeTap\dump\*.class         # original bytecode before transform / transform 前的原始字节码
```

## 6. Measured Records / 实测记录

Fake client with an isolated class loader (`harness`, whose Display is defined by a child-first `URLClassLoader`):

隔离类加载的假客户端（`harness`，其 Display 由 child-first `URLClassLoader` 定义）：

```
[BridgeTap] frame sink installed (hook loader: bootstrap)
[BridgeTap] injected into org/lwjgl/opengl/Display.update()V
[BridgeTap] hooked ... via jvmti (677 -> 800 bytes)
[BridgeTap] attached to 39212@PC, config loaded (enabled=true)
[BridgeTap] hud loaded, parent loader: java.net.URLClassLoader
[BridgeTap] resolved game: client.a | player=client.c (b) | pos=(a,b,c) | ground=d
```

Real Lunar 1.8.9 (pid 21664, attach disabled):

真实 Lunar 1.8.9（pid 21664，attach 被禁）：

```
payload attached (pid 21664)
JavaVM acquired (count=1)
JVMTI environment acquired
agent jar appended to bootstrap search
handed 26033 loaded classes to java
hook fired: org/lwjgl/opengl/Display len=20000
[BridgeTap] hooked org/lwjgl/opengl/Display via jvmti
```

## 7. Build / 构建

```bash
bash native/pack.sh             # jars + payload.dll + resources -> dist/BridgeTap.exe / 编译并链接成 dist/BridgeTap.exe
native/build.sh                 # optional: build btinject.exe + payload.dll on their own / 可选：单独编这两件
```

`native/build.sh` does not use `vcvars64.bat`: that script calls `reg.exe` to read the toolchain path, and `reg.exe` is on this machine's program blacklist. The script probes the MSVC / Windows SDK versions by directory name and sets `INCLUDE`/`LIB` itself.

`native/build.sh` 不用 `vcvars64.bat`：它会调 `reg.exe` 读工具链路径，而本机 `reg.exe` 在程序黑名单里。
脚本自己按目录名探测 MSVC / Windows SDK 版本并设置 `INCLUDE`/`LIB`。

## 8. Limitations / 限制

- **Badlion** is only mechanism-compatible, not verified on a real machine (only Lunar is installed here) / **Badlion** 只做了机制兼容，没有实机验证（本机只装了 Lunar）
- Processes with anti-injection protection (rejecting `CreateRemoteThread`) cannot be handled and report `create-remote-thread` / 反注入保护（拒绝 `CreateRemoteThread`）的进程无法处理，会报 `create-remote-thread`
- Each injection leaves about 1MB of temporary files in `%LOCALAPPDATA%\BridgeTap\inject\` (locked by the JVM, cannot be deleted) / 每次注入留下约 1MB 临时文件在 `%LOCALAPPDATA%\BridgeTap\inject\`（被 JVM 锁定，无法删除）
- Sidebar scoreboard timing and gold-pressure-plate success detection are still missing: they need the whole `World` → `Scoreboard` → `Block` chain, which must be hooked per client / 侧边栏计分板时间、金踏板成功判定仍然缺：需要 `World` → `Scoreboard` → `Block` 整条链，必须按客户端单独挂 hook

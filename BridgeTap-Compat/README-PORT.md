# BridgeTap 1.4.5 → Memory Hot-Injection Port Notes / BridgeTap 1.4.5 → 内存热注入移植说明

For modified 1.8.9 clients that cannot load Forge / Fabric (Lunar Client, Badlion Client). No client files are touched, no launch arguments are added, nothing has to be installed on the client side: a single exe, injected into the running JVM.

面向无法加载 Forge / Fabric 的魔改 1.8.9 客户端（Lunar Client、Badlion Client）。不改客户端文件、不加启动参数、不需要客户端侧安装任何东西：一个 exe，注进正在运行的 JVM。

---

## 一、Why the Original Cannot Be Built / Loaded on Any Other 1.8.9 / 原版为什么在别的 1.8.9 上构建不了 / 加载不了

`BridgeTap-1.0.0.jar` already contains four self-contradictory points by itself; after 1.4.5 the official source compiles, but against third-party clients all four still kill it:

`BridgeTap-1.0.0.jar` 本身就有四个自相矛盾的地方，1.4.5 之后虽然官方源能编译，但对第三方客户端仍然全部失效：

| # | Problem / 问题 | Evidence / 证据 | Consequence / 后果 |
|---|------|------|------|
| 1 | Compile-time mappings are fabricated / 编译期映射是伪造的 | The bytecode references `net.minecraft.unmapped.C_42232651` / `m_67975642` / `f_78826675`; real yarn has no `net/minecraft/unmapped/*`, and `C_` + 8 digits is not yarn's `class_1234` scheme / 字节码引用 `net.minecraft.unmapped.C_42232651` / `m_67975642` / `f_78826675`，真实 yarn 里没有 `net/minecraft/unmapped/*`，`C_`+8 位数字也不是 yarn 的 `class_1234` 体系 | It links only under the author's private mappings; no real 1.8.9 can resolve it / 只有在作者那套私有映射下才能链接，任何真实 1.8.9 都无法解析 |
| 2 | The mixin `compatibilityLevel` is illegal / mixin `compatibilityLevel` 非法 | `bridgetap.mixins.json` says `JAVA_25` / `bridgetap.mixins.json` 写 `JAVA_25` | Mixin throws on load; the mod never reaches initialization / Mixin 加载即抛，模组根本不进初始化 |
| 3 | class file major version = 69 (Java 25) / class 文件 major version = 69（Java 25） | MANIFEST `Created-By: 25.0.1` / MANIFEST `Created-By: 25.0.1` | 1.8.9 clients run on Java 8 / 17, so `UnsupportedClassVersionError` / 1.8.9 客户端跑在 Java 8 / 17 上，`UnsupportedClassVersionError` |
| 4 | Official Fabric never supported 1.8.9 / 官方 Fabric 从未支持 1.8.9 | `meta.fabricmc.net/v2/versions/game` has no 1.8.x at all, and `/yarn/1.8.9`, `/intermediary/1.8.9` return `[]` / `meta.fabricmc.net/v2/versions/game` 无任何 1.8.x，`/yarn/1.8.9`、`/intermediary/1.8.9` 返回 `[]` | You must switch to the Legacy Fabric toolchain to build it yourself / 必须换 Legacy Fabric 工具链才能自建 |

Those four items are the problems you hit when "compiling a working build yourself"; they are already solved in `BridgeTap/` (Legacy Fabric + yarn 1.8.9+build.604 + `--release 8`, output major version 52, all five mixins remapped in place to intermediary).

上面四条是「自己编一个能跑的版本」时的问题，已经在 `BridgeTap/` 里解决（Legacy Fabric + yarn 1.8.9+build.604 + `--release 8`，产物 major version 52，五个 mixin 全部就地重映射为 intermediary）。

**But none of that helps Lunar / Badlion**, because they do not load Fabric / Forge at all:

**但这对 Lunar / Badlion 毫无用处**，因为它们根本不加载 Fabric / Forge：

| Obstacle / 障碍 | Lunar Client 1.8.9 | Badlion Client 1.8.9 |
|---|---|---|
| Mod loader / 模组加载器 | None (no Fabric / Forge). Its own ichor pipeline remaps classes at load time / 无 Fabric / Forge。自有 ichor 管线在加载期重映射类 | None. The Odin obfuscator flattens every class name to `a`/`ave`/`bdo` / 无。Odin 混淆器把所有类名压成 `a`/`ave`/`bdo` |
| Class names / 类名 | Keeps MCP names (`net.minecraft.client.Minecraft`) / 保留 MCP 名（`net.minecraft.client.Minecraft`） | Fully obfuscated (`ave` = Minecraft, `avo` = GuiIngame, `bda` = PlayerControllerMP, `bew` = EntityPlayerSP) / 全混淆（`ave` = Minecraft，`avo` = GuiIngame，`bda` = PlayerControllerMP，`bew` = EntityPlayerSP） |
| Attach / Attach | Launch args hard-code `-XX:+DisableAttachMechanism`; HotSpot creates no attach listener pipe, so `jcmd`/`jmap`/`VirtualMachine.attach()` are all dead / 启动参数硬编码 `-XX:+DisableAttachMechanism`，HotSpot 不创建 attach 监听管道，`jcmd`/`jmap`/`VirtualMachine.attach()` 全废 | The attach handshake gets through, but throws `java.lang.InternalError` internally / attach 握手能通但内部抛 `java.lang.InternalError` |
| Class loader / 类加载器 | child-first isolating loader; `com.bridger.*` is not on the allowlist, so `invokestatic` of our classes from game classes raises `NoClassDefFoundError` / child-first 隔离加载器，`com.bridger.*` 不在 allowlist，从游戏类里 `invokestatic` 我方类会 `NoClassDefFoundError` | Same problem / 同类问题 |
| Rendering / 渲染 | ichor patched `org.lwjgl.opengl.Display` (20000 bytes vs 19982 upstream); the `update()V` chain cannot be caught / ichor 改过 `org.lwjgl.opengl.Display`（20000 字节 vs 官方 19982），`update()V` 那条链抓不到 | Native LWJGL / 原生 LWJGL |

Conclusion: **JVMTI + a remote thread is mandatory, and every target must be discovered through runtime structure, never through names.**

结论：**必须走 JVMTI + 远程线程，并且所有目标靠运行时结构发现，不能靠名字。**

---

## 二、Port Architecture / 移植架构

```
BridgeTap.exe (single file, 960 KB / 单文件, 960 KB)
├─ RCDATA 1  agent.jar     JVMTI agent: Bootstrap / Bridge / ClassScan / GameBinding / WorldProbe
├─ RCDATA 2  hud.jar       GL overlay (the only module that imports LWJGL directly / 唯一直接 import LWJGL 的模块)
└─ RCDATA 3  payload.dll   in-process JVMTI bootstrap / 进程内 JVMTI 自举
        │
        ├─ CreateRemoteThread(LoadLibraryW) → payload.dll
        │     ├─ EnumProcessModules → jvm.dll → JNI_GetCreatedJavaVMs
        │     ├─ AttachCurrentThread → GetEnv(JVMTI_VERSION_1_2)
        │     ├─ AddCapabilities(can_retransform_classes)
        │     ├─ AddToBootstrapClassLoaderSearch(agent.jar)
        │     ├─ Class.forName("...Bootstrap", true, null).runNative()
        │     └─ SetEventCallbacks(ClassFileLoadHook) + RetransformClasses(already-loaded targets / 已加载目标)
        │
        └─ the injected bytecode only calls org/objectweb/asm/BridgeTapHook.*
              (Lunar's loader allowlist contains org.objectweb.asm., so parent delegation hits it / Lunar 的加载器 allowlist 里有 org.objectweb.asm.，走 parent delegation 就能命中)
```

Three hard design constraints, each one mapping to a pit that was actually hit:

三个设计上的硬约束，每个都对应一个踩过的坑：

1. **The whole agent jar goes onto bootstrap**, not just a shim. The injected call has to be resolved by game classes, and `Hook.setSink` requires the sink implementation and `Hook.FrameSink` to be the same class object — which only holds when both live on bootstrap. / **整个 agent jar 挂 bootstrap**，不是只挂一个 shim。注入调用要被游戏类解析，`Hook.setSink` 又要求 sink 实现与 `Hook.FrameSink` 是同一个类对象 —— 只有同在 bootstrap 上才成立。
2. **`hud.jar` stays off bootstrap**. `GlHud` calls `GL11.glPushAttrib` directly, and LWJGL only exists in the client's loader. At runtime it is loaded with `new URLClassLoader(urls, gameLoader)` and called across the boundary by reflection. Lunar's loader refuses to resolve our package names, so everything crossing the boundary goes through reflection and no interface is shared. / **`hud.jar` 不挂 bootstrap**。`GlHud` 直接调 `GL11.glPushAttrib`，而 LWJGL 只在客户端的 loader 里。运行时用 `new URLClassLoader(urls, gameLoader)` 加载，通过反射跨边界调用。Lunar 的 loader 拒绝解析我们的包名，所以跨界的东西一律反射，接口不共享。
3. **The injection sites only call `org.objectweb.asm.BridgeTapHook`**. That package name is on Lunar's allowlist. / **注入点只调 `org.objectweb.asm.BridgeTapHook`**。这个包名在 Lunar 的 allowlist 上。

---

## 三、Scope of Code Changes / 代码改动范围

### 3.1 Pure logic carried over from the Fabric build (`core/`, zero game dependencies) / 从 Fabric 版搬过来的纯逻辑（`core/`，零游戏依赖）

| File / 文件 | Source / 来源 | Changes / 改动 |
|---|---|---|
| `Tracker.java` | `BridgeTracker` (522 lines upstream / 原版 522 行) | Removed direct references to Minecraft classes; now driven by `onTick(x,y,z,onGround)` data.<br>**Added**: `TimeSource` (sidebar clock), `ChatSink` (success message), `finalizeFlat` (fixates the first-jump duration, re-labels FLAT entries inside the 1.0–1.25 s window), `getLastEntry`/`getTurnEntry` (for the LAST_TURN display mode) / 去掉对 Minecraft 类的直接引用，改为 `onTick(x,y,z,onGround)` 数据驱动。<br>**新增**：`TimeSource`（侧边栏时钟）、`ChatSink`（成功消息）、`finalizeFlat`（首跳时长固化，1.0–1.25 s 窗口内重标 FLAT 条目）、`getLastEntry`/`getTurnEntry`（供 LAST_TURN 显示模式） |
| `Config.java` | `BridgeConfig` | Same properties format, existing configs keep working.<br>**Added** `gainLabel` (No Gain / No Gay), `chatOutput`, `showReadyNotice` / 同一份 properties 格式，老配置直接可用。<br>**新增** `gainLabel`（No Gain / No Gay）、`chatOutput`、`showReadyNotice` |
| `JumpEntry.java` | Same name / 同名 | The FLAT row's `+.n/a` now reads `Config.gainLabel` / FLAT 行的 `+.n/a` 改为读 `Config.gainLabel` |
| `hud/HudModel.java` | The data-gathering part of `BridgeHUD.render` / `BridgeHUD.render` 的取数部分 | **Added** the `LAST_TURN` mode (only Last + Turn rows, no header) / **新增** `LAST_TURN` 模式（只留 Last + Turn 两行，无表头） |
| `Chat.java` | New / 新增 | The full `/bt` command set. 5 from upstream (toggle/reset/pos/ui/axis) + 8 added on the injector side (display/offset/plateoffset/turn/scale/chat/gain/background/status/save/reload) / `/bt` 命令全集。原版 5 条（toggle/reset/pos/ui/axis）+ 注入端补的 8 条（display/offset/plateoffset/turn/scale/chat/gain/background/status/save/reload） |

### 3.2 New on the injection side (`agent/`) / 注入端新增（`agent/`）

| File / 文件 | Purpose / 作用 |
|---|---|
| `WorldProbe.java` | **New**. Reflection bridge `World → Scoreboard → Block`:<br>· `readTime()`: `getScoreboard()` → `getObjectiveInDisplaySlot(1)` → `getSortedScores()` → find the `Time` row → parse `m:ss.s` or plain seconds<br>· `isOnGoldPlate(x,y,z)`: scan `getBlockState()` over the player's collision box (x±0.35, y −0.2…+0.5, z±0.35) → `getBlock()` → `getIdFromBlock() == 147`<br>· `chat()`: `ChatComponentText` + `addChatMessage` / **新**。反射桥接 `World → Scoreboard → Block`：<br>· `readTime()`：`getScoreboard()` → `getObjectiveInDisplaySlot(1)` → `getSortedScores()` → 找 `Time` 行 → 解析 `m:ss.s` 或纯秒<br>· `isOnGoldPlate(x,y,z)`：按玩家碰撞盒（x±0.35, y −0.2…+0.5, z±0.35）扫 `getBlockState()` → `getBlock()` → `getIdFromBlock() == 147`<br>· `chat()`：`ChatComponentText` + `addChatMessage` |
| `ClassScan.java` | **Changed**. Added `resolveChat()`: MCP names take priority, the obfuscated side falls back on structure — "the one and only `(String)V` method" / **改**。新增 `resolveChat()`：MCP 名优先，混淆端按「唯一一个 `(String)V` 方法」结构回退 |
| `GameBinding.java` | **Changed**. `onGround` field selection: the MCP name `onGround` is trusted outright (`groundNamed`), only the obfuscated side goes through observation; observation gained a new constraint — "a constant boolean value cannot be elected" / **改**。`onGround` 字段选择：MCP 名 `onGround` 直接采信（`groundNamed`），只有混淆端才走观测；观测新增「恒定布尔值不可当选」约束 |
| `Bootstrap.java` | **Changed**. Site table 5 → 7 (new site 6 = `sendChatMessage`, site 5 reserved for the payload liveness probe); new `MODE_CHAT` injection mode: a conditional `RETURN` in the prologue implements "swallow the command" / **改**。站点表 5 → 7（新增 site 6 = `sendChatMessage`，site 5 留给 payload 探针）；新增 `MODE_CHAT` 注入模式：prologue 里条件 `RETURN` 实现「吞掉命令」 |
| `Bridge.java` | **Changed**. `ensureProbe()` wires WorldProbe to the Tracker's three callbacks; `onChat()` implements `/bt`; `onGround` is no longer overwritten by mistake / **改**。`ensureProbe()` 把 WorldProbe 接到 Tracker 的三个回调上；`onChat()` 实现 `/bt`；`onGround` 不再被误覆盖 |
| `native/payload.c` | **Changed**. Target class table gained `EntityPlayerSP` / `bew`; `bootstrap.ini` parsing skips the UTF-8 BOM / **改**。目标类表新增 `EntityPlayerSP` / `bew`；`bootstrap.ini` 解析跳过 UTF-8 BOM |
| `native/BridgeTap.c` | **New**. Single-file injector: three embedded resources, Win32 GUI (process list + Inject), CLI (--list / --pid / --auto) / **新**。单文件注入器：内嵌三段资源、Win32 GUI（进程列表 + Inject）、CLI（--list / --pid / --auto） |

### 3.3 Differences from upstream (honest list) / 与原版的差异（诚实清单）

| Upstream 1.4.5 feature / 原版 1.4.5 功能 | This port / 本次移植 |
|---|---|
| Coordinate / jump distance / tick / Turn / Plates / Fail detection / 坐标 / 跳距 / tick / Turn / Plates / Fail 判定 | ✅ Complete, logic aligned line by line / ✅ 全量，逻辑逐行对齐 |
| Sidebar Time / 侧边栏 Time | ✅ Read by reflection, falls back to tick estimation when unreadable / ✅ 反射读取，读不到回退 tick 估算 |
| Gold plate success detection (block id 147) / 金踏板成功判定（block id 147） | ✅ |
| Display modes ALL / COORDS / COORDS_ONLY / TICKS / TIME / LAST_TURN / 显示模式 ALL / COORDS / COORDS_ONLY / TICKS / TIME / LAST_TURN | ✅ |
| No Gain / No Gay labels / No Gain / No Gay 标签 | ✅ |
| `/bt` commands / `/bt` 命令 | ✅ 13 of them (5 upstream + 8 config ones) / ✅ 13 条（原版 5 条 + 配置类 8 条） |
| Success message sent to chat / 成功消息打到聊天 | ✅ Byte-for-byte the same format as upstream / ✅ 原版逐字节同格式 |
| `config/bridgetap.properties` | ✅ Same format / ✅ 同格式 |
| **Replay recording system** (.btreplay / private hotbar / POV / 3D world reconstruction) / **Replay 录像系统**（.btreplay / 私有物品栏 / POV / 3D 世界重建） | ❌ **Not ported**. It needs to replace `RenderGlobal` and `EntityRenderer` and build a synthetic chunk world of its own; on the ichor / Odin-rewritten render pipeline there is no stable foothold. Metrics and display layers are unaffected / ❌ **未移植**。它需要替换 `RenderGlobal` 与 `EntityRenderer` 并自建合成 chunk 世界，在 ichor / Odin 改写过的渲染管线上没有稳定落点。度量与显示层不受影响 |

---

## 四、Injection Entry Points and Load Timing / 注入入口与加载时机

### 4.1 The seven injection sites / 七个注入点

| site | Target / 目标 | Timing / 时机 | Purpose / 作用 |
|---|---|---|---|
| 0 | `GuiIngame.renderGameOverlay(F)V` | RETURN | Draw the HUD (the GUI projection is ready by then, and the client's own FontRenderer is usable) / 画 HUD（此时 GUI 投影已就绪，客户端自己的 FontRenderer 可用） |
| 1 | `Minecraft.runTick()V` | HEAD | Sample the player position every tick, drive the state machine / 每 tick 取玩家位置，驱动状态机 |
| 2 | `PlayerControllerMP.onPlayerRightClick(...)Z` | RETURN + `DUP` the return value / RETURN + `DUP` 返回值 | Block placed → start a new timing run (only accepts `instanceof ItemBlock`) / 放方块 → 开新一轮计时（只认 `instanceof ItemBlock`） |
| 3 | `Minecraft.updateDisplay()V` | HEAD | Backup binding (earlier than runTick) / 备用绑定（早于 runTick） |
| 4 | `Display.swapBuffers()V` | HEAD | Fallback drawing (Lunar patched Display; use it when the primary draw fails) / 兜底绘制（Lunar 改过 Display，主绘制失败时用它） |
| 5 | — | — | Reserved slot, the payload uses it as a liveness probe / 保留槽位，payload 用它做存活探针 |
| 6 | `EntityPlayerSP.sendChatMessage(String)V` | HEAD + conditional RETURN / HEAD + 条件 RETURN | Intercept `/bt`; on a hit the whole message is never sent / 拦截 `/bt`，命中则整条消息不下发 |

### 4.2 Load timing / 加载时机

```
T+0     exe extracts agent.jar / hud.jar / payload.dll into %LOCALAPPDATA%\BridgeTap\inject\ (timestamped) / exe 释放 agent.jar / hud.jar / payload.dll 到 %LOCALAPPDATA%\BridgeTap\inject\（带时间戳）
T+0     writes bootstrap.ini (UTF-8, no BOM) / 写 bootstrap.ini（UTF-8 无 BOM）
T+0     OpenProcess → VirtualAllocEx → WriteProcessMemory → CreateRemoteThread(LoadLibraryW)
T+1s    payload bootstraps JVMTI → AddToBootstrapClassLoaderSearch(agent.jar)
T+2s    Bootstrap.runNative(): install sink → ClassScan structural discovery → build the 7-site table / 装 sink → ClassScan 结构发现 → 生成 7 条站点表
T+3s    payload SetEventCallbacks + RetransformClasses(5 already-loaded targets / 5 个已加载目标)
T+4s    first runTick → GameBinding binds mc/player/pos/ground
T+5s    HUD draws for the first time / HUD 首次绘制
after   position sampled every tick; on the first arrival in a world WorldProbe resolves World/Scoreboard/Block / 之后    每 tick 采位置；首次到达世界时 WorldProbe 解析 World/Scoreboard/Block
```

**The injection window matters**: if the client's classes are not fully loaded, `ClassScan` misses `PlayerControllerMP` / `FontRenderer`, and JVMTI only calls back on a class's **first load**, so it cannot be fixed afterwards. Measured: injecting 7 seconds after the window appears misses both; injecting after waiting 50 seconds passes first time. The EXE GUI by default lets the user see the process list before clicking Inject; with CLI `--pid` you must judge the timing yourself (inject after reaching the main menu).

**注入窗口很重要**：客户端类没加载完时 `ClassScan` 会漏掉 `PlayerControllerMP` / `FontRenderer`，而 JVMTI 只在类**首次加载**时回调，事后无法补救。实测在窗口出现后 7 秒注入会漏这两项；等 50 秒后注入一次通过。EXE 的 GUI 默认让用户看到进程再点 Inject，CLI `--pid` 需自己把握时机（进到主菜单之后再注入）。

---

## 五、Compatibility Handling for the Two Clients / 两个客户端的兼容处理

| Dimension / 维度 | Handling / 处理方式 |
|---|---|
| **Class names** / **类名** | Not hard-coded. `ClassScan.discover()` works backwards from "the only method that calls `Display.update()`" to `updateDisplay` → `runGameLoop` → `runTick`, then finds player / controller / font from the `mc` instance fields by **type shape**. Lunar takes the MCP fast path, Badlion uses the Odin measurement table plus a structural scan as fallback / 不硬编码。`ClassScan.discover()` 从「唯一调用 `Display.update()` 的方法」反推 `updateDisplay` → `runGameLoop` → `runTick`，再从 `mc` 实例字段按**类型形状**找 player / controller / font。Lunar 走 MCP 快速路径，Badlion 走 Odin 测量表 + 结构扫描兜底 |
| **Member names** / **成员名** | `GameBinding` resolves everything in three tiers: "scan result → reflection → shape fallback". Position fields are inferred first from the read order of the `PUTFIELD`s inside the `(DDD)V` setter (`renderDistanceWeight` / `prevPos*` come earlier in declaration order, so using declaration order is always wrong) / `GameBinding` 全部按「扫描结果 → 反射 → 形状回退」三级取。位置字段优先用 `(DDD)V` setter 里 `PUTFIELD` 的读序反推（`renderDistanceWeight` / `prevPos*` 在声明顺序里排在前面，用声明顺序必错） |
| **onGround** | Lunar: the field name `onGround` is trusted directly. Badlion: `bw` is a guess, confirmed by observation, and the candidate field must **have changed** during the observation window (always-true flags like `spawnForced` are excluded) / Lunar：字段名 `onGround` 直接采信。Badlion：`bw` 是猜的，靠观测确认，且要求候选字段在观测窗内**有过变化**（`spawnForced` 这类恒真标志被排除） |
| **attach channel** / **attach 通道** | Always a remote thread, no attach probing. Lunar has `DisableAttachMechanism`, Badlion throws `InternalError` after the handshake; neither yields a stable win / 一律走远程线程，不做 attach 探测。Lunar 是 `DisableAttachMechanism`，Badlion 是握手后 `InternalError`，两者都没有稳定收益 |
| **Class loader** / **类加载器** | The injected call only points at `org.objectweb.asm.BridgeTapHook` (Lunar allowlist); the HUD gets its own child loader + reflection / 注入调用只指向 `org.objectweb.asm.BridgeTapHook`（Lunar allowlist）；HUD 单独 child loader + 反射 |
| **Render entry points** / **渲染入口** | Three draw sites coexist; the first one that can actually draw wins and the rest are disabled (Badlion's `updateDisplay` has callers on non-render threads, where calling GL silently returns garbage) / 三个绘制站点并存，第一个能画出来的胜出，其余停用（Badlion 的 `updateDisplay` 有非渲染线程的调用方，在那里调 GL 静默返回垃圾） |
| **Badlion status** / **Badlion 现状** | The mechanism is adapted (Odin table + structural scan + `bew` target class), but only Lunar is installed on this machine, so **no Badlion on-device verification has been done** / 机制已适配（Odin 表 + 结构扫描 + `bew` 目标类），本机只装了 Lunar，**未做 Badlion 实机验证** |

---

## 六、Deliverable Layout / 交付物结构

```
dist/
└─ BridgeTap.exe                 960 KB — the only file you need / 唯一需要的文件
   ├─ double-click              → Win32 GUI: process list + Refresh + Inject / 双击 → Win32 GUI：进程列表 + Refresh + Inject
   ├─ --list                    → list candidates (javaw/java only, launchers excluded) / 列出候选（只认 javaw/java，排除启动器）
   ├─ --pid <n>                 → inject directly / 直接注入
   └─ --auto                    → inject the only candidate / 注入唯一候选
```

Runtime files on disk (`%LOCALAPPDATA%\BridgeTap\`):

运行时落盘（`%LOCALAPPDATA%\BridgeTap\`）：

| Path / 路径 | Content / 内容 |
|---|---|
| `inject\bridgetap-agent-<ts>.jar` | Created anew on every injection (an already-loaded jar is locked by the JVM, so a fixed name always fails the second time) / 每次注入新建（已加载的 jar 会被 JVM 锁住，固定名第二次必失败） |
| `inject\bridgetap-hud-<ts>.jar` | Same as above / 同上 |
| `inject\bridgetap-payload-<ts>.dll` | Same as above / 同上 |
| `inject\bootstrap.ini` | The payload reads the paths of the two jars from here / payload 从这里读两个 jar 的路径 |
| `agent.log` | Full Java-side log (site discovery, binding, ticks, fail detection) / Java 侧全量日志（站点发现、绑定、tick、失败判定） |
| `inject-native.log` | Native side (jvm.dll, JVMTI, retransform, probe) / 原生侧（jvm.dll、JVMTI、retransform、探针） |
| `injector.log` | exe side (pid and Win32 error code of every injection) / exe 侧（每次注入的 pid 与 Win32 错误码） |
| `hud.log` | GL state (viewport / proj / draw method); used when diagnosing "drew but no pixels" / GL 状态（viewport / proj / 绘制方式），判断"画了但没有像素"时用 |
| `dump\classes.txt` | Names of 26k classes plus their respective loaders; used when reversing the client / 26k 个类的名字 + 各自 loader，逆向客户端时用 |

Config: `<gameDir>\config\bridgetap.properties` (same format as upstream, old configs are directly compatible).

配置：`<gameDir>\config\bridgetap.properties`（与原版同格式，老配置直接兼容）。

---

## 七、Usage and Verification / 使用与验证

### 7.1 Usage / 使用

```
1. Open the client, get to the main menu or onto a server, wait 30 seconds (let all game classes load) / 打开客户端，进到主菜单或服务器，等 30 秒（让游戏类全部加载）
2. Double-click dist\BridgeTap.exe / 双击 dist\BridgeTap.exe
3. Select the "Lunar Client 1.8.9 (v2.23.x)" entry in the list and click Inject / 列表里选中 "Lunar Client 1.8.9 (v2.23.x)" 那一条，点 Inject
4. Back in game, the BridgeTap panel shows up in the top-left corner / 回到游戏，左上角出现 BridgeTap 面板
```

### 7.2 Acceptance criteria / 验证判据

| Criterion / 判据 | Expected / 期望 |
|---|---|
| `%LOCALAPPDATA%\BridgeTap\inject-native.log` | `hook target via game loader: RESOLVED`, `retransform requested for 5 of 5 class(es)`, `probe: Hook.onFrame(5) invoked` |
| `agent.log` | `targets: mc=... controller=... font=...` (none of the three may be `null`), `world probe: scoreboard=... plate=true`, `hud loaded`, `overlay drawing` |
| In game / 游戏内 | The panel shows `X/Y/Z` (before a bridge is started) or `Bridging 0.00s 0 jumps` / 面板显示 `X/Y/Z`（未开始搭桥时）或 `Bridging 0.00s 0 jumps` |
| Place one block / 放一块方块 | `agent.log` shows `placement t=N ... START NEW RUN` |
| Jump / 起跳 | The panel shows `» 4b Tap #1: .xxx +.xxx (Nt)` / 面板出现 `» 4b Tap #1: .xxx +.xxx (Nt)` |
| Step on a gold plate / 踩金踏板 | The panel shows a `Plates` row, and chat receives `§a[BridgeTap] Success! Time: ...` / 面板出现 `Plates` 行，聊天收到 `§a[BridgeTap] Success! Time: ...` |
| Type `/bt status` / 输入 `/bt status` | Chat replies with 5 lines of config; the message is not sent to the server / 聊天回 5 行配置；消息不会发到服务器 |
| Fall into the void / 掉进虚空 | 50 airborne ticks with no landing close the run: `agent.log` shows `FAIL (Void)`, the panel gains a red `Fail Tap` row and the header turns `Failed`. A jump down to a lower island is unaffected - touching down clears the counter. / 连续 50 tick 不落地才收尾；服务器在下坠途中把人传送回岛上时立刻收尾（`prevY` 是掉到的高度）。`agent.log` 出现 `FAIL (Void)`，面板多一行红色 `Fail Tap`、顶部变红 `Failed`。跳到更低的岛上不受影响 —— 落地即清零计数。 |

### 7.3 Field test log (Lunar Client 1.8.9 v2.23.16-2646, JRE 17.0.21) / 实测记录（Lunar Client 1.8.9 v2.23.16-2646，JRE 17.0.21）

Verified:

已验证：

```
payload attached (pid 26880)
agent jar: ...\inject\bridgetap-agent-20261110134128228.jar
JVMTI environment acquired
hook target via game loader: RESOLVED
hook fired: net/minecraft/client/gui/GuiIngame len=97297
hook fired: net/minecraft/client/multiplayer/PlayerControllerMP len=44901
hook fired: org/lwjgl/opengl/Display len=20000
hook fired: net/minecraft/client/entity/EntityPlayerSP len=47549
hook fired: net/minecraft/client/Minecraft len=210145
retransform requested for 5 of 5 class(es)
probe: Hook.onFrame(5) invoked
```

```
[BridgeTap] targets: mc=net/minecraft/client/Minecraft instance=theMinecraft
            draw=updateDisplay tick=runTick player=thePlayer pos=(posX,posY,posZ)
            ground=onGround controller=net/minecraft/client/multiplayer/PlayerControllerMP.onPlayerRightClick
            font=net.minecraft.client.gui.FontRenderer.drawString
[BridgeTap]   site 6 -> net/minecraft/client/entity/EntityPlayerSP.sendChatMessage(Ljava/lang/String;)V [chat]
[BridgeTap] bound game object: ... | ground=onGround | font=fontRendererObj
[BridgeTap] world probe: scoreboard=false plate=true chat=false (world field theWorld)
[BridgeTap] chat sink resolved: EntityPlayerSP.addChatMessage
[BridgeTap] first tick sample: x=168.500 y=100.000 z=0.500 ground=true
[BridgeTap] hud loaded (font net.minecraft.client.gui.FontRenderer, parent com.moonsworth.lunar.genesis.IICHIHHCCCCCHORIHHOHCIOHOORIIC)
[BridgeTap] overlay drawing (2 line(s), site -1, thread Client thread)
```

```
[hud] hud drawn: 2 line(s), panel 216x28 at 175,50 via renderString (variant 0, rect Gui.drawRect, thread Client thread)
[hud] gl: viewport=0x0+2560+1440 proj=[0.0015625, -0.0027777778, -0.001, 1.0] at 175,50 scale 1.0
     thread Client thread gl=4.6.0 NVIDIA 617.42
```

Having both the `renderString` and `drawRect` variants in `hud.log`, together with a real viewport and proj matrix, is the evidence that "the HUD is actually drawn into the frame buffer" rather than "the call did not throw".

`hud.log` 里同时存在 `renderString` 与 `drawRect` 变体、并且拿得到真实 viewport 与 proj 矩阵，是「HUD 真的画进帧缓冲」而不是"调用没报错"的证据。

### 7.4 Three fixes from the first round of field feedback / 首轮实机反馈的三处修复

| Symptom / 症状 | Root cause / 根因 | Fix / 修法 |
|---|---|---|
| After injection all of Lunar's menu buttons turn into black blocks, and the HUD background is a black blob too / 注入后 Lunar 的菜单按钮全变黑色块，HUD 背景也是黑的一坨 | The panel background goes through `Gui.drawRect`, which in 1.8.9 ends with `GlStateManager.color(bg)` and **does not restore the color** — every upstream caller sets its own color right afterwards, but drawing from the tail of `renderGameOverlay` has no such caller, so every subsequent GUI inherits a nearly black tint / 面板背景走的是 `Gui.drawRect`，它在 1.8.9 里以 `GlStateManager.color(bg)` 结尾且**不恢复颜色** —— 原版每个调用方紧接着都会自己设色，而从 `renderGameOverlay` 尾部绘制没有这样的调用方，于是后续所有 GUI 都继承了近乎全黑的色调 | `rect()` now uses a self-drawn `quad()` (read back every GL flag and write it back); `render()` takes a `GlState` snapshot/restore at both ends and calls `GlStateManager.color(1,1,1,1)` by reflection to sync the client's own color cache / `rect()` 改走自绘 `quad()`（逐项读回 GL 标志再写回）；`render()` 首尾加 `GlState` 快照/恢复，并通过反射调 `GlStateManager.color(1,1,1,1)` 同步客户端自己的颜色缓存 |
| The bridge data rows also have a black background (text is visible) / 搭路数据行也是黑底（文字可见） | Same as above: the color / blend state after `drawString` is never reset / 同上：`drawString` 之后的颜色/混合状态没复位 | Same as above; `GlState.restore` covers TEXTURE_2D / BLEND / DEPTH_TEST / ALPHA_TEST / LIGHTING / CULL_FACE / blendFunc / currentColor / 同上，`GlState.restore` 覆盖 TEXTURE_2D / BLEND / DEPTH_TEST / ALPHA_TEST / LIGHTING / CULL_FACE / blendFunc / currentColor |
| 1.05 Flat displays as 1.1 Flat, and 1.1 displays as 1.15 / 1.05 Flat 显示成 1.1 Flat、1.1 显示成 1.15 | Two stacked biases running fast: ① `Minecraft.runTick()` is called **every frame** (45 ms average measured here ≈ 22 Hz), so counting on it runs about 10% fast; ② jumps are **inferred** from frame-sampling edges, so they are inevitably one tick later than the real take-off / 两个叠加的偏快：①`Minecraft.runTick()` 是**每帧**调用（本机实测均值 45 ms ≈ 22 Hz），靠它计数偏快约 10%；②跳跃是从帧采样边沿**推断**的，必然比真实起跳晚一 tick | ① Switched to `World.getTotalWorldTime()` as the tick source (the server-synced 20 Hz counter), duplicate frames inside the same tick are dropped, and the counter re-aligns on a world switch; ② added site 7 that hooks `EntityLivingBase.jump()V` directly, demoting sampling inference to a fallback (disabled as soon as `jumpHookSeen` is set) / ① 改用 `World.getTotalWorldTime()` 作为 tick 源（服务端同步的 20 Hz 计数），同一 tick 内的重复帧直接丢弃，世界切换时重新对齐；② 新增 site 7 直接 hook `EntityLivingBase.jump()V`，采样推断降级为兜底（`jumpHookSeen` 一置位就停用） |
| The HUD sits on top of Lunar's tab list / inventory / ESC menu / HUD 压在 Lunar 的 tab 栏 / 背包 / ESC 菜单之上 | Two places: ① once the draw site is claimed by `Display.swapBuffers()` it stays active forever, and that site runs after every GUI; ② the RETURN of `GuiIngame.renderGameOverlay` comes after the tab list / 两处：①绘制站点一旦被 `Display.swapBuffers()` 抢到就永久生效，而它在所有 GUI 之后；②`GuiIngame.renderGameOverlay` 的 RETURN 在 tab 列表之后 | ① Sites now compete by rank (`renderTooltip` > `renderGameOverlay` > `updateDisplay` > `swapBuffers`), so a higher priority site can take it back at any time; ② new anchor: the RETURN of `GuiIngame.renderTooltip(ScaledResolution,float)` — the GUI projection is ready, it is called every frame, and it runs before the tab list / ①站点改为按 rank 抢位（`renderTooltip` > `renderGameOverlay` > `updateDisplay` > `swapBuffers`），高优先级随时夺回；②新锚点 `GuiIngame.renderTooltip(ScaledResolution,float)` 的 RETURN —— GUI 投影已就绪、每帧必调、且在 tab 之前 |

### 7.5 Two pits you can only find through bytecode / 两个只有靠字节码才能发现的坑

| Assumption / 假设 | Reality / 实际 | Consequence / 后果 |
|---|---|---|
| `EntityPlayerSP` has `jump()` (only exists since 1.9+) / `EntityPlayerSP` 有 `jump()`（1.9+ 才有） | On 1.8.9 `jump()` is only declared on `EntityLivingBase` / 1.8.9 上 `jump()` 只声明在 `EntityLivingBase` | Detection walks superclasses and wrongly concludes "yes", while injection matches on the declaration → silently skipped, and jumps keep going through the sampling path (one tick late) / 检测时遍历父类误判为"有"，注入时按声明匹配 → 静默跳过，跳跃仍走采样路径（晚一 tick） |
| `GuiIngame` has `renderPlayerList()` / `GuiIngame` 有 `renderPlayerList()` | In 1.8.9 the tab list lives in `GuiPlayerTabOverlay.renderPlayerlist` / 1.8.9 的 tab 列表在 `GuiPlayerTabOverlay.renderPlayerlist` | The before-call anchor does not exist, so the HUD falls back to the renderGameOverlay RETURN and sits on top of the tab list / before-call 锚点不存在，HUD 退回 renderGameOverlay RETURN，压在 tab 上 |
| `World.getScoreboard()` returns void / `World.getScoreboard()` 返回 void | It returns `Scoreboard` / 返回 `Scoreboard` | Reflection filters by `void.class` → never matches, and the sidebar timing chain is dead from start to finish (`scoreboard=false`) / 反射按 `void.class` 过滤 → 永远匹配不到，侧边栏计时链自始至终是死的（`scoreboard=false`） |

### 7.6 Gold plate detection: use the game's own trigger condition / 金踏板判定：改用游戏自己的触发条件

Upstream, and the first two versions of this port, all guessed "at which height are the feet". Reading the 1.8.9 bytecode shows the pressure plate does not judge by height at all.

原版和本移植前两版都在猜"脚在哪个高度"。翻 1.8.9 的字节码才发现，压力板压根不是靠高度判定的。

**Evidence chain (`client-1.8.9-mcp.jar`, `javap -p -c`):**

**证据链（`client-1.8.9-mcp.jar`，`javap -p -c`）：**

```java
// BlockBasePressurePlate / 压力板基类
public AxisAlignedBB getCollisionBoundingBox(World w, BlockPos p, IBlockState s) {
    return null;                                  // ← the plate has no collision box / ← 板没有碰撞盒
}

protected AxisAlignedBB getSensitiveAABB(BlockPos pos) {
    float f = 0.125F;
    return new AxisAlignedBB(pos.getX() + f,  pos.getY(),
                             pos.getZ() + f,  pos.getX() + 1 - f,
                             pos.getY() + 0.25D,        // ← trigger box height 0.25 / ← 触发盒高 0.25
                             pos.getZ() + 1 - f);
}

public void onEntityCollidedWithBlock(World w, BlockPos p, IBlockState s, Entity e) {
    if (w.isRemote) return;                       // ← the client does no detection / ← 客户端不做判定
    int i = this.getRedstoneStrength(s);
    if (i == 0) this.updateState(w, p, s, i);
}
```

Inside `computeRedstoneStrength` it is exactly `world.getEntitiesWithinAABBExcludingEntity(null, getSensitiveAABB(pos))` — **whoever's collision box intersects that 0.125-inward, 0.25-high box presses the plate down**.

`computeRedstoneStrength` 里就是 `world.getEntitiesWithinAABBExcludingEntity(null, getSensitiveAABB(pos))` —— **谁的碰撞盒和那个内缩 0.125、高 0.25 的盒子相交，板就被压下**。

Two fatal facts:

两个致命事实：

| Fact / 事实 | Consequence / 后果 |
|---|---|
| `getCollisionBoundingBox` returns `null` / `getCollisionBoundingBox` 返回 `null` | The player **cannot stand** on the plate surface. The feet stop on the top face of the block under the plate, `y == blockY`, and the plate only occupies the lowest 1/16 above the feet / 玩家**站不到**板面上。脚停在板下面那块方块的顶面，`y == blockY`，板只占脚上那 1/16 |
| The trigger box is inset by 0.125 / 触发盒内缩 0.125 | Standing on the neighbouring block with the box only grazing the plate border does not count; you have to really press onto it / 站在旁边那块方块上、盒子只搭到板边界不算；要真压上去才算 |

So why the previous two versions were both wrong:

所以前两版为什么都错：

- Version one (`blockY − 0.06 … blockY + 0.1225`) holds at `y = blockY` → standing next to the plate is misjudged as success. / 第一版（`blockY − 0.06 … blockY + 0.1225`）在 `y = blockY` 处成立 → 站旁边就误判成功。
- Version two (`blockY + 0.0625 ± 0.03125`) requires the feet to be on the plate surface → but the feet are **never** at that height, so it never triggers at all. / 第二版（`blockY + 0.0625 ± 0.03125`）要求脚在板面上 → 而脚**永远不会**在那个高度，于是完全不触发。

Now the game's own rule is copied verbatim:

现在直接照抄游戏的规则：

```java
// AxisAlignedBB.intersectsWith, other = the plate's sensitive box / other = 板的 sensitive box
boolean hit = bodyMaxX > bx + 0.125 && bodyMinX < bx + 0.875
           && bodyMaxZ > bz + 0.125 && bodyMinZ < bz + 0.875
           && bodyMaxY > by        && bodyMinY < by + 0.25;
```

| Parameter / 参数 | Value / 取值 | Basis / 依据 |
|---|---|---|
| `SENSITIVE_INSET` | `0.125` | `float f = 0.125F` inside `getSensitiveAABB` / `getSensitiveAABB` 里的 `float f = 0.125F` |
| `SENSITIVE_HEIGHT` | `0.25` | `y + 0.25D` in the same method / 同一方法的 `y + 0.25D` |
| `PLAYER_HALF_WIDTH` | `0.3` | Player collision box is 0.6 wide / 玩家碰撞盒 0.6 宽 |
| `PLAYER_HEIGHT` | `1.8` | Player collision box is 1.8 high / 玩家碰撞盒 1.8 高 |

The detection runs on the same tick as the game with zero latency (it does not wait for the server to echo back the `powered` state, which takes 1–2 ticks). Log:

判定与游戏同一 tick、零延迟（不依赖服务端回传 `powered` 状态，那要晚 1–2 tick）。日志：

```
[BridgeTap] plate pressed: feet=64.0000 block=(168,64,0) dx=+0.1200 dz=-0.3400 dy=+0.0000
[BridgeTap] plate miss:    feet=64.0000 block=(168,64,0) dx=-0.2900 dz=+0.0100 dy=+0.0000
```

`dx/dz` is the offset of the foot centre from the plate centre, `dy` is the foot height relative to the plate bottom. `dy` is normally always `0.0000` (standing on the block under the plate); a `+1.0000` means a plate on another layer was hit.

`dx/dz` 是脚心相对板中心的偏移，`dy` 是脚相对板底的高度。`dy` 正常恒为 `0.0000`（站在板下那块方块上）；如果出现 `+1.0000` 说明打到了另一层的板。

### 7.7 Sampling timing of the coordinate decimals / 坐标小数位的采样时机

The `.xxx` of a jump entry is the coordinate snapshot stored at tick `jump Tick + tickOffset`. `onTick` samples at the HEAD of `runTick`, so it stores the position **on entering that tick** (i.e. the result after the previous tick finished).

跳跃条目的 `.xxx` 取的是 `jump Tick + tickOffset` 那一 tick 存下的坐标快照。`onTick` 在 `runTick` 的 HEAD 采样，存的是**进入该 tick 时**的位置（也就是上一 tick 走完的结果）。

- `tickOffset = -1` (old default): reads the position of the tick **before** take-off. / `tickOffset = -1`（旧默认）：读到起跳**前一 tick** 的位置。
- `tickOffset = 0` (current): reads the **start** of the take-off tick, i.e. the position this bridge run really stepped from. / `tickOffset = 0`（现行）：读到起跳那一 tick 的**起点**，即这次搭路真正踩下去的位置。

Old config files (without the `configRevision` key) are automatically aligned to `0` and written back on load; no manual edit is needed. The `Fail Tap` sampling point (`lastJumpTick + 9 + tickOffset`) moves with it, keeping the two consistent.

旧配置文件（没有 `configRevision` 键）在加载时会被自动对齐到 `0` 并回写，不需要手动改。`Fail Tap` 的采样点（`lastJumpTick + 9 + tickOffset`）跟着一起移动，保持口径一致。

### 7.8 Crash on the resource pack screen: bootstrap classpath pollution / 崩在资源包界面：bootstrap 类路径污染

Symptom: after injection, clicking the resource pack button in Badlion sometimes crashes with `IllegalAccessError`.

现象：注入后在 Badlion 里点资源包按钮，有概率 `IllegalAccessError` 崩掉。

```
failed to access class com.google.gson.internal.LinkedTreeMap$KeySet
  from class com.google.gson.internal.LinkedTreeMap
(LinkedTreeMap$KeySet 在 loader 'bootstrap' 里；LinkedTreeMap 在 loader 'app' 里)
  at LinkedTreeMap.keySet
  at net.badlion.a.uT.<init>
  ...
```

Root cause: the agent fat jar had `com.google.code.gson:2.11.0` baked in (only the offline `tools` module uses it; the agent does not need it at runtime at all). That jar is added to the **bootstrap** search path by `AddToBootstrapClassLoaderSearch`, so `LinkedTreeMap$KeySet` gets resolved from our 2.11 copy (bootstrap) while `LinkedTreeMap` was already loaded earlier by the client's own gson-2.2.4 (app). The same package is then half-installed by two class loaders, and package-private access becomes illegal immediately — that is where "sometimes" comes from: whether it crashes depends on which class gets loaded by whom first.

根因：agent 胖 jar 里打进了 `com.google.code.gson:2.11.0`（只有离线 `tools` 模块用它，agent 运行时根本不需要）。
这个 jar 是 `AddToBootstrapClassLoaderSearch` 加到 **bootstrap** 搜索路径的，于是 `LinkedTreeMap$KeySet`
从我们这份 2.11 解析（bootstrap），而 `LinkedTreeMap` 早被客户端自己的 gson-2.2.4（app）加载过。
同名包被两个类加载器各装一半，包级私有访问立刻非法 —— 这就是"有概率"的来源：崩不崩取决于哪个类先被谁加载。

The fix has two layers:

修法两层：

1. Drop the agent's gson dependency; / 去掉 agent 的 gson 依赖；
2. Add an **allowlist** to the fat jar in `agent/build.gradle`, permitting only `com/bridger/**` and `org/objectweb/asm/**`. The jar shrinks from 617 KB to 307 KB, and this kind of pollution can never sneak in through a transitive dependency again. / `agent/build.gradle` 的胖 jar 加**白名单**，只允许 `com/bridger/**` 和 `org/objectweb/asm/**`。jar 从 617 KB 降到 307 KB，这类污染再也不可能从传递依赖里溜进来。


### 7.9 Success message delayed by two ticks / 成功消息延后两 tick

The detection itself does not change: `SUCCESS` fires on the very tick the feet press the gold plate, and the HUD's `Plates` / `Plates-1t` show up immediately. Only the chat line `§a[BridgeTap] Success! Time: ...` is queued, to be emitted two ticks later by the tick path (`SUCCESS_MESSAGE_DELAY_TICKS = 2`, `pendingSuccessTick` + `flushSuccessMessage()`).

判定本身不动：脚压上金踏板那一 tick 就出 `SUCCESS`，HUD 的 `Plates` / `Plates-1t` 立刻显示。
只是聊天里那条 `§a[BridgeTap] Success! Time: ...` 改成排队，由 tick 路径在两 tick 后发出
（`SUCCESS_MESSAGE_DELAY_TICKS = 2`，`pendingSuccessTick` + `flushSuccessMessage()`）。

That way the numbers and the notification do not hit at the same instant: you first see the plate press down and the player stop, and the chat summary pops up two ticks later. Both `startRun()` and `reset()` clear the pending message, so the tail of the previous run never drifts into the next one.

这样读数和提示不会同时砸出来：先看到板压下、人停住，两 tick 后聊天才蹦出总结。
`startRun()` 和 `reset()` 都会清掉待发消息，避免上一局的尾巴飘到下一局。

### 7.10 F2 screenshots miss the panel (and why the "bind the FBO" version vanished outright) / F2 截图截不到面板（以及为什么"绑 FBO"那版直接消失）

The order of one 1.8.9 frame (`EntityRenderer.a(FJ)V` → `Minecraft.e()`, verified with `javap`):

1.8.9 一帧的顺序（`EntityRenderer.a(FJ)V` → `Minecraft.e()`，`javap` 实证）：

```
bfw.a(Z)V   bindFramebuffer(true)      bind the off-screen FBO, viewport = frame size / 绑定离屏 FBO，viewport = 帧尺寸
            framebufferClear()         clear the screen ← whatever the previous frame drew into the FBO is wiped here / 清屏 ← 上一帧画进 FBO 的东西在这里被抹掉
            world + GUI (incl. the client's own HUD) / 世界 + GUI（含客户端自己的 HUD）
bfw.e()V    unbindFramebuffer()        ★ unbind first / ★ 先解绑
bfw.c(II)V  framebufferRender()        only then blit the FBO onto the window / 然后才把 FBO 贴到窗口
```

Two conclusions:

两个结论：

| Position / 位置 | Result / 结果 |
|---|---|
| Draw inside `updateDisplay` / `swapBuffers` / `updateDisplay` / `swapBuffers` 里画 | Lands on the window → **visible, not captured** (the screenshot reads the FBO) / 落在窗口上 → **看得见，截不到**（截图读的是 FBO） |
| Draw at the head of `framebufferRender` / `framebufferRender` 头部画 | Already unbound by then → lands on the window, and **the immediately following blit overwrites it entirely** → invisible / 那时已经解绑 → 落在窗口上，而且**紧接着的 blit 会整块盖掉它** → 看不见 |

So the previous version's "bindFramebuffer before drawing" approach is inevitably all black: after drawing into the FBO there is neither a blit nor a next-frame blit (the next frame clears first), so not a single frame ever shows up. In the log `hud-phase.log` writes `done` every frame, which is exactly "drew, but nobody looked".

所以上一版"绘制前 bindFramebuffer"的做法必然全黑：画进 FBO 之后既没有 blit 也没有下一帧的 blit（下一帧先清屏），
画面上一帧都没有。日志里 `hud-phase.log` 每帧都写着 `done`，就是"画了但没人看"。

Conclusion: **window drawing stays exactly as it was; the screenshot copy is drawn an extra time into the off-screen frame.** The two do not interfere, and the blit never needs to be touched again.

结论：**窗口绘制保持原样，截图副本额外画一份进离屏帧**。两件事互不干扰，也不用再碰 blit。

```java
boolean painted = render.invoke(renderer, lines);   // ① draw the window as before (this line always works, leave it alone) / ① 照旧画窗口（这一行一直可用，别动）

if (site == SITE_UPDATE || site == SITE_SWAP) {     // ② at frame end add one copy into the off-screen frame / ② 帧末补一份进离屏帧
   if (bindFramebuffer(mc.framebufferMc)) {
      render.invoke(renderer, lines);               //    only for F2 / 只为 F2
      unbindFramebuffer();
   }
}
```

The order is critical: **the window copy is drawn first, the off-screen copy second**. The previous round did the opposite (bind the FBO first, then draw), and the window copy never appeared for a single frame — meaning after one bind/unbind cycle, a second draw at the same position no longer lands on the window. With the order flipped, both paths work independently.

顺序很关键：**窗口那份先画，离屏那份后画**。上一轮反过来（先 bind FBO 再画），结果窗口那份一帧都没出现——
说明经过一次 bind/unbind 之后，同一个位置的第二次绘制不再落到窗口上。反过来放，两条路各走各的，都成立。

The off-screen copy only lives from "end of this frame" to "next frame's clear"; F2 is triggered synchronously inside `runTick`, before that frame is rendered, so it reads exactly that copy. The window copy is unaffected by any of this and touches no GL state once it is done.

离屏副本只活在"本帧结束 → 下一帧清屏"之间；F2 是在 `runTick` 里同步触发的，早于该帧渲染，正好读到它。
窗口那份不受任何影响，画完就不再碰 GL 状态。

Two earlier failed approaches, kept for the record:

早先两次失败的方案留档：

| Approach / 做法 | Result / 结果 |
|---|---|
| Draw into the FBO only / 只画进 FBO | Screenshot has it, screen does not (nobody blits this FBO out, and the next frame clears first) / 截图有、屏幕没有（没人把这份 FBO 贴出去，下一帧又先清屏） |
| Draw the FBO first, then the window / 先画 FBO 再画窗口 | Screenshot has it, the screen still does not (the second draw after bind does not land on the window) / 截图有、屏幕仍然没有（bind 之后的第二次绘制不落到窗口） |
| Draw the FBO + blit manually / 画 FBO + 自己 blit | The screen does not (after the blit the window is still empty; this path does not work) / 屏幕没有（blit 之后窗口仍是空的，这条路不成立） |

`framebufferRender(II)V` still carries a site (site 9) but **does not draw**; it only confirms in the log that this blit path exists:

`framebufferRender(II)V` 仍然挂了一个站点（site 9）但**不画**，只用于在日志里确认这条 blit 路径存在：

```
[BridgeTap] frame blit seen (framebufferRender); painting at the late site instead
```


---

## 八、Build / 构建

```bash
export BT_JDK=/path/to/jdk-21
bash native/pack.sh            # gradle jars → payload.dll → rc → link
# Output: dist/BridgeTap.exe / 产物：dist/BridgeTap.exe
```

`native/pack.sh` probes MSVC / Windows SDK by directory name itself (it does not go through `vcvars64.bat`, which calls `reg.exe`); `rc.exe` is taken from the SDK's `bin/<ver>/x64`.

`native/pack.sh` 自己按目录名探测 MSVC / Windows SDK（不走 `vcvars64.bat`，它会调 `reg.exe`），
`rc.exe` 从 SDK 的 `bin/<ver>/x64` 取。

---

## 九、Badlion Adaptation / Badlion 适配

### 9.1 Naming: not Odin flattening, but vanilla 1.8.9 obfuscation / 命名：不是 Odin 压平，是原版 1.8.9 混淆

The earlier assumption ("Odin flattens everything into a/aa/ave") is wrong. The 6631 `net/badlion/*` classes inside `BLClient.jar` are its own modules; the game itself sits in the root package under **vanilla 1.8.9 obfuscated names**: `ave`/`avo`/`bda`/`bew`/`pr`/`adm`/`afh`…

之前的假设（"Odin 把一切压成 a/aa/ave"）是错的。`BLClient.jar` 里 6631 个 `net/badlion/*` 是它自己的模块，
游戏本体是**原版 1.8.9 混淆名**放在根包：`ave`/`avo`/`bda`/`bew`/`pr`/`adm`/`afh`……

`.minecraft/labymod-neo/mappings/1.8.9-mcp.tsrg2` is exactly that table (obf ↔ MCP, 27566 lines). It is used to look every member up, then each one is verified with `javap` against `BLClient.jar`:

`.minecraft/labymod-neo/mappings/1.8.9-mcp.tsrg2` 就是这张表（obf ↔ MCP，27566 行）。
用它把每个成员反查出来，再逐条到 `BLClient.jar` 里 `javap` 验证：

| MCP | Badlion | Members / 成员 |
|---|---|---|
| `Minecraft` | `ave` | instance `S`, updateDisplay `h`, runTick `s`, thePlayer `h`, playerController `c`, fontRenderer `k`, theWorld `f` / `S` 实例、`h` updateDisplay、`s` runTick、`h` thePlayer、`c` playerController、`k` fontRenderer、`f` theWorld |
| `GuiIngame` | `avo` | `a(F)V` renderGameOverlay, `a(Lavr;F)V` renderTooltip / `a(F)V` renderGameOverlay、`a(Lavr;F)V` renderTooltip |
| `PlayerControllerMP` | `bda` | `a(Lbew;Lbdb;Lzx;Lcj;Lcq;Laui;)Z` onPlayerRightClick |
| `FontRenderer` | `avn` | `a(String;III)I` drawString, `a(String;)I` getStringWidth / `a(String;III)I` drawString、`a(String;)I` getStringWidth |
| `EntityPlayerSP` | `bew` | `e(String)V` sendChatMessage, `a(Leu;)V` addChatMessage / `e(String)V` sendChatMessage、`a(Leu;)V` addChatMessage |
| `EntityLivingBase` | `pr` | `bF()V` jump |
| `World` | `adm` | `p(Lcj;)Lalz;` getBlockState, `K()J` getTotalWorldTime, `Z()Lauo;` getScoreboard / `p(Lcj;)Lalz;` getBlockState、`K()J` getTotalWorldTime、`Z()Lauo;` getScoreboard |
| `Block` | `afh` | `a(Lafh;)I` getIdFromBlock, `c(Lalz;)I` getMetaFromState / `a(Lafh;)I` getIdFromBlock、`c(Lalz;)I` getMetaFromState |
| `BlockPos` | `cj` | `cj(III)V` |
| `Entity` | `pk` | posX/Y/Z `s,t,u`, onGround `C` / `s,t,u` posX/Y/Z、`C` onGround |
| `GlStateManager` | `bfl` | `c(FFFF)V` color, `f()` disableLighting, `w()` enableTexture2D, `l()` enableBlend, `a(IIII)` tryBlendFuncSeparate, `d()` enableAlpha, `i()` disableDepth / `c(FFFF)V` color、`f()` disableLighting、`w()` enableTexture2D、`l()` enableBlend、`a(IIII)` tryBlendFuncSeparate、`d()` enableAlpha、`i()` disableDepth |
| `IChatComponent` / `ChatComponentText` | `eu` / `fa` | — |
| `Scoreboard` | `auo` | `a(I)Lauk;`, `i(Lauk;)Collection`, `d(String)Laul;` |
| `Score` | `aum` | `e()String` getPlayerName, `c()I` getScorePoints / `e()String` getPlayerName、`c()I` getScorePoints |
| `ScorePlayerTeam` | `aul` | `d(String)String` formatString |
| `ItemStack` | `zx` | `b()Lzw;` getItem |
| `ItemBlock` | `yo` | — |

How it was verified (every single entry was run):

验证方式（每一条都跑过）：

```bash
javap -p ave.class | grep "public bew h;"      # the thePlayer field type is exactly bew / thePlayer 字段类型正是 bew
javap -p -c pr.class | sed -n '/protected void bF/,/^$/p'
#   0: aload_0  1: aload_0  2: invokevirtual bE:()F  ... putfield w:D   ← motionY = getJumpUpwardsMotion()
javap -p -c ave.class | awk '/Display\.update/{print m}'
#   M: public void h();
```

### 9.2 Code changes / 代码改动

| Location / 位置 | Change / 改动 |
|---|---|
| `ClassScan.Result` | Added `worldField` (`f`), `glStateClass` (`bfl`), `obf`, `groundTrusted` / 新增 `worldField`（`f`）、`glStateClass`（`bfl`）、`obf`、`groundTrusted` |
| `ClassScan` | The whole BLC branch is replaced with the table above, and binding performs a structural check (`ave` must have `S`/`h`/`f`, and the type of `h` must end in `bew`) / BLC 分支全部换成上表，并在绑定时做结构校验（`ave` 必须有 `S`/`h`/`f`，且 `h` 的类型以 `bew` 结尾） |
| `WorldProbe` | All reflected members changed to `pick(obfuscated name, MCP name…)`; the world field comes from the mapping rather than a type guess; when `getBlockState` hits an overload of `p`, it selects again by "single object parameter + object return" / 所有反射成员改为 `pick(混淆名, MCP 名…)`；世界字段来自映射而不是按类型猜；`getBlockState` 遇到 `p` 的重载时按"单对象参数 + 对象返回"再选一次 |
| `GlHud` | The constructor gained `Class glState` + `String[] glNames`, and the agent passes `bfl` plus its seven method names in (otherwise the HUD falls back to bare `GL11`, which is exactly what made the font look grey on Lunar) / 构造参数新增 `Class glState` + `String[] glNames`，由 agent 把 `bfl` 和它那七个方法名传进去（否则 HUD 会退回裸 `GL11`，正是 Lunar 上字体发灰那次的成因） |
| `GameBinding` | The `onGround` from the mapping (`C`) is trusted directly and "observation scoring" no longer runs — `pk` has more than twenty booleans, so guessing is always wrong; the same goes for the player field, which no longer requires the class name to contain `EntityPlayer` / 映射给出的 `onGround`（`C`）直接采信，不再跑"观测打分"——`pk` 上有二十多个布尔，猜必错；玩家字段同理，不再要求类名含 `EntityPlayer` |
| `native/payload.c` | Target class gains `pr`, signature gains `Lpr;` / 目标类加 `pr`，签名加 `Lpr;` |

### 9.3 How to verify / 验证方法

BLC runs on `AppData\Roaming\Badlion Client\Data\jdk-17.0.13-jre` (Java 17), the process is still `javaw.exe`, and the window title contains `1.8.9` — so the injector list can select it directly.

BLC 跑在 `AppData\Roaming\Badlion Client\Data\jdk-17.0.13-jre`（Java 17），进程仍是 `javaw.exe`，
窗口标题含 `1.8.9` —— 注入器列表能直接选到。

Join a server, wait 30 seconds, then Inject; the log (`%LOCALAPPDATA%\BridgeTap\agent.log`) should show:

进服后等 30 秒再 Inject，日志（`%LOCALAPPDATA%\BridgeTap\agent.log`）应出现：

```
[BridgeTap] class map: ave Minecraft ... [Badlion map, BLClient PRODUCTION4]
[BridgeTap]   site 0 -> avo.a(F)V [return]
[BridgeTap]   site 7 -> pr.bF()V [head]
[BridgeTap] injected into pr: bF()V@head
[BridgeTap] world probe: scoreboard=true plate=true chat=true (world field f)
[BridgeTap] tick clock active: world.getTotalWorldTime (exact 20 Hz)
```

`site 7` landing on `pr.bF` is the most critical one on the BLC side: register the jump one tick early or late and `1.05 Flat` turns into `1.1`.

`site 7` 落在 `pr.bF` 上，是 BLC 侧最关键的一条：跳跃记早了/记晚了一 tick，`1.05 Flat` 就会变成 `1.1`。

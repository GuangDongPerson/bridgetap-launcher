# BridgeTap · External Injector / 外置注入器

A desktop shell written in Electron that wraps the memory injection of `BridgeTap.exe` into "pick a client → one click → see the result".

Electron 写的桌面壳，把 `BridgeTap.exe` 的内存注入包装成「选客户端 → 点一下 → 看结果」。

- No installation: `npm run dist` produces one exe, double-click and it runs / 免安装：`npm run dist` 产出一个 exe，双击即用
- No runtime to install: Electron bundles Chromium + Node, the target machine does not need Node / 免运行环境：Electron 自带 Chromium + Node，目标机不需要装 Node
- No Forge / Fabric dependency: injection goes through its own native injector (remote thread + JVMTI) / 无 Forge / Fabric 依赖：注入走自己的原生注入器（远程线程 + JVMTI）

## Quick Start / 快速开始

```bash
npm install          # only installs electron and electron-builder / 只装 electron 与 electron-builder
npm run fonts        # fetch the MiSans font shards (required on first run, needs network) / 取回 MiSans 字体分片（首次必须，需要联网）
npm start            # run from source / 从源码运行
npm run dist         # package → dist/BridgeTap-Injector-1.0.10.exe / 打包 → dist/BridgeTap-Injector-1.0.10.exe
```

`npm run dist` first checks that the fonts are complete; if any are missing it fails outright instead of producing a build without fonts.

`npm run dist` 会先检查字体是否齐全，缺了直接失败而不是打出一个没字体的包。

## Fonts: why they are not distributed with the repository / 字体：为什么不随仓库分发

The UI uses MiSans (Xiaomi). Article 3 of the "MiSans Font Intellectual Property License Agreement" forbids "further distributing the font software or any copies thereof", and committing the 56 woff2 files into a public repository is exactly that act.

界面用 MiSans（小米）。《MiSans 字体知识产权许可协议》第 3 条禁止「进一步分发字体软件
或其任何副本」，把 56 个 woff2 提交进公开仓库正是这个行为。

So: / 所以：

- The repository has **only** `src/renderer/styles/fonts.css` (56 `@font-face` rules + unicode-range) and `src/renderer/assets/fonts/MiSans-license.txt`; the font files themselves are not there / 仓库里**只有** `src/renderer/styles/fonts.css`（56 条 `@font-face` + unicode-range）和 `src/renderer/assets/fonts/MiSans-license.txt`，没有字体文件本身
- Before building, run `npm run fonts` to fetch the shards from the npm package `misans@5.0.0`, at the same paths as in `fonts.css` / 构建前跑 `npm run fonts` 从 npm `misans@5.0.0` 取回分片，路径与 `fonts.css` 里的一致
- Shipping them inside the exe is **embedded use**, which the agreement explicitly allows (FAQ: "may be used as an embedded font"), provided that MiSans is credited in the software — the line at the bottom of the "About" page does that / 打进 exe 是**嵌入式使用**，协议明确允许（FAQ：「可以用作嵌入式字体」），前提是在软件中注明使用了 MiSans —— 「关于」页底部那一行就是

The filename list in `fetch-fonts.js` is parsed out of `fonts.css`, not hard-coded, so switching weights or adding subsets only requires editing the CSS.

`fetch-fonts.js` 的文件名列表是从 `fonts.css` 里解析出来的，不是硬编码的，
换字重或加子集时只需要改 CSS。

## Directory / 目录

```
src/
  shared/contract.js        ★ The only contract between main ↔ renderer (channel names, event shapes, state machine) / 主进程 ↔ 渲染进程唯一契约（通道名、事件形状、状态机）
  main/
    main.js                 Window + IPC wiring + elevation guard / 窗口 + IPC 接线 + 提权守卫
    preload.js              Whitelist-style API exposed to the renderer / 白名单式 API 暴露给渲染层
    inject/
      index.js              Injection service: state machine + event outlet (the only place that knows "how injection works") / 注入服务：状态机 + 事件出口（唯一懂"注入怎么做"的地方）
      native-injector.js    Calls BridgeTap.exe --pid / 调 BridgeTap.exe --pid
      process-scan.js       Scans javaw.exe, identifies Lunar / Badlion by window title and launch arguments / 扫 javaw.exe，按标题/启动参数识别 Lunar / Badlion
      log-tail.js           Tails %LOCALAPPDATA%\BridgeTap\agent.log / 跟读 %LOCALAPPDATA%\BridgeTap\agent.log
    updater.js              Queries releases, picks a mirror, verifies SHA256, overwrites and rolls back / 查 Release、镜像选路、SHA256 校验、覆盖与回滚
    log-export.js           Builds the log bundle (injector / native / mod / client / system info) / 打日志包（注入器 / 原生 / 模组 / 客户端 / 系统信息）
    settings.js             Reads and writes application settings / 应用设置读写
  renderer/
    index.html
    styles/tokens.css       Design tokens (colors/radii/spacing/fonts/easing) / 设计令牌（颜色/圆角/间距/字体/缓动）
    styles/fonts.css        @font-face and unicode-range for MiSans / MiSans 的 @font-face 与 unicode-range
    styles/{base,layout,components,motion}.css
    scripts/app.js          Assembly layer: turns events into rendering + motion / 装配层：把事件翻译成渲染 + 动效
    scripts/ui/render.js    Draws DOM only / 只画 DOM
    scripts/ui/motion.js    Plays animations only / 只播动画
    scripts/ui/toast.js     Toasts / 轻提示
    scripts/ui/settings-view.js  Form rows of the settings page / 设置页的表单行
docs/ARCHITECTURE.md        Layering, contract, packaging and "how to swap just one layer" / 分层、契约、打包与"怎么只换一层"
scripts/make-icon.js        Generates icon.png / icon.ico with zero dependencies / 零依赖生成 icon.png / icon.ico
scripts/fetch-fonts.js      Fetches the font shards according to fonts.css / 按 fonts.css 取回字体分片
scripts/check-i18n.js       Verifies that the Chinese and English locale keys match / 校验中英语言包键一致
vendor/launcher/            Launcher placeholder (the real exe is produced by pack.sh in BridgeTap-Compat) / 启动器占位（真实 exe 由 BridgeTap-Compat 的 pack.sh 产出）
```

## Usage / 用法

1. Open Lunar or Badlion and **get into a world** (injection also works from the main menu, but the HUD waits for the relevant render classes to load) / 打开 Lunar 或 Badlion，**进到一个世界里**（在主菜单也能注入，但 HUD 要等对应渲染类加载）
2. Start this program; the list shows the candidate JVMs, click one to select it / 启动本程序，列表里会列出候选 JVM，点一下选中
3. Click "Start Injection" (or press Enter / double-click the card) / 点「开始注入」（或回车 / 双击卡片）
4. Go back to the game and type `/bt` in chat to see the available commands / 回到游戏，聊天栏输入 `/bt` 看可用命令

| Command / 命令 | Effect / 作用 |
|---|---|
| `/bt toggle` | Turn the HUD on or off / 开 / 关 HUD |
| `/bt reset` | Reset the current round / 重置当前这一局 |
| `/bt axis auto\|X\|Z` | Lock the axis used for detection / 锁定判定轴 |
| `/bt chat` | Toggle the success announcements in chat / 切换聊天栏的成功播报 |

On failure the reason appears in the bottom-right corner. The two most common cases:

失败时右下角会给出原因。最常见的两种：

- **The same JVM has already been injected** — the agent's classes can only be defined once per JVM, so the client must be fully closed (confirm in Task Manager that `javaw.exe` has exited) and restarted / **同一个 JVM 已经注入过** —— agent 的类在一个 JVM 里只能定义一次，得完全退出客户端（任务管理器确认 `javaw.exe` 结束）再重开
- **The agent did not come up** — the message carries the `FATAL:` line from the payload log, which points directly at the step that failed (no JVMTI / cannot append the jar / class loading failed) / **agent 没起来** —— 提示会带上 payload 日志里的那一行 `FATAL:`，直接指出卡在哪一步（拿不到 JVMTI / 挂不上 jar / 类加载失败）

## Packaging into a Single File / 打包成单文件

See `electron-builder.yml`; two key points:

见 `electron-builder.yml`，关键两点：

```yaml
win:
  target: [{ target: portable, arch: [x64] }]   # portable = self-extracting shell, one exe, no registry writes / portable = 自解压壳，一个 exe，不写注册表
asarUnpack:
  - vendor/launcher/**                          # native exes cannot be executed from inside asar, so they must be unpacked / 原生 exe 不能从 asar 里执行，必须解包
```

The difference between portable and nsis, and why the injector uses `asarUnpack` rather than `extraResources`, are described in the "Packaging" section of `docs/ARCHITECTURE.md`.

portable 与 nsis 的区别、以及为什么注入器走 `asarUnpack` 而不是 `extraResources`，
写在 `docs/ARCHITECTURE.md` 的「打包」一节。

## License / 许可

The application itself: GPL-3.0, see `LICENSE`.

程序本体：GPL-3.0，见 `LICENSE`。

Third-party components shipped with the artifacts (Electron, ASM, MiSans) and their license texts are listed in `THIRD-PARTY.md` at the repository root; the full texts live in `licenses/`.

随产物一起分发的第三方组件（Electron、ASM、MiSans）及其许可正文列在仓库根目录的
`THIRD-PARTY.md`，正文放在 `licenses/`。

UI font MiSans: Copyright (c) Beijing Xiaomi Mobile Software Co., Ltd., distributed under the "MiSans Font Intellectual Property License Agreement", shipped with the artifacts in embedded form only, with no modification to the glyphs. The full agreement and the boundaries of use are in `src/renderer/assets/fonts/MiSans-license.txt`.

界面字体 MiSans：Copyright (c) Beijing Xiaomi Mobile Software Co., Ltd.，
遵循《MiSans 字体知识产权许可协议》，仅以嵌入形式随产物分发，未对字形做任何改造。
协议全文与使用边界见 `src/renderer/assets/fonts/MiSans-license.txt`。

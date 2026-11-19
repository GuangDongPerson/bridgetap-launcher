# Architecture: Why "Changing the UI" Never Drags In "Changing the Injection" / 架构：为什么"改界面"不会牵动"改注入"

## 1. Three Layers, One-Way Dependencies / 1. 三层，单向依赖

```
            shared/contract.js        ← the only module both ends import / 只有它同时被两端引用
                   ↑        ↑
      main/inject/ │        │ renderer/scripts/ui/*
  injection / 注入 │        │ presentation / 表现（render / motion / toast）
                   │        │
                main/ipc ── renderer/app.js  (assembly layer / 装配层)
```

规则只有三条，但必须守住：

1. **The injection domain knows nothing about the DOM**: `document`, `class` or `animation` appearing in `main/inject/*` means the boundary has been crossed. / **注入域不认识 DOM**：`main/inject/*` 里出现 `document`、`class`、`animation` 就是串味了。
2. **The presentation domain knows nothing about injection**: `render.js` has no idea where the pid comes from, `motion.js` does not even contain the word "injection"; it only knows the four scenes `idle / scanning / injecting / attached / failed`. / **表现域不认识注入**：`render.js` 不知道 pid 从哪来，`motion.js` 连"注入"这个词都没有，它只认四个场景 `idle / scanning / injecting / attached / failed`。
3. **The assembly layer is the only translator**: `renderer/app.js` translates `PHASE.*` into scenes and `Target[]` into DOM; swapping either side requires no changes to the other two. / **装配层是唯一的翻译官**：`renderer/app.js` 把 `PHASE.*` 翻成场景、把 `Target[]` 翻成 DOM，换任何一边都不用动其它两边。

`contract.js` defines three things:

`contract.js` 定义三样东西：

| Export / 导出 | Purpose / 作用 |
|---|---|
| `CALL` | IPC channels the renderer layer can call (listTargets / start / cancel / readLog / openFolder / info / window) / 渲染层能调用的 IPC 通道（listTargets / start / cancel / readLog / openFolder / info / window） |
| `EVENT` | Channel names the main process pushes one-way, `{kind, payload}` / 主进程单向推送的通道名，`{kind, payload}` |
| `PHASE` | State-machine enum: `idle → scanning → ready → injecting → attached / failed` / 状态机枚举：`idle → scanning → ready → injecting → attached / failed` |
| `EVENT_KIND` | Types of pushed messages: `phase` / `targets` / `output` / `notice` / 推送消息的类型：`phase` / `targets` / `output` / `notice` |

## 2. How Many Lines Change If the Injection Implementation Is Replaced? / 2. 换掉注入实现要动几行？

Suppose the native exe is dropped later in favor of pure JVMTI `VirtualMachine.attach()`:

假设以后不用原生 exe 了，改成纯 JVMTI `VirtualMachine.attach()`：

1. Write a new `src/main/inject/attach-injector.js` exporting `inject(pid, onLine) → {ok, code, timedOut}`; / 新写 `src/main/inject/attach-injector.js`，导出 `inject(pid, onLine) → {ok, code, timedOut}`；
2. In `src/main/inject/index.js` around line 30, change `this.strategy = native` to `attach`. / `src/main/inject/index.js` 第 30 行左右，把 `this.strategy = native` 换成 `attach`。

**Renderer layer, styles, motion: 0 lines changed.** Because the UI only consumes `phase / output / notice`.

**渲染层、样式、动效：0 行改动。** 因为界面只消费 `phase / output / notice`。

`mock.js` is living proof of that claim — it has the same signature as `native-injector.js`, and with `--dev` the whole chain still runs.

`mock.js` 就是这个说法的活证据——它和 `native-injector.js` 签名一致，`--dev` 时整条链路照跑。

## 3. How Many Lines Change If the UI Look Is Replaced? / 3. 换掉 UI 观感要动几行？

- Only want a new palette: edit `styles/tokens.css` (the single source of raw color values; hardcoded colors are forbidden inside components). / 只想换配色：改 `styles/tokens.css`（唯一的裸色值来源，组件里禁止硬编码颜色）。
- Want a different motion set: edit `styles/motion.css` + `scripts/ui/motion.js`. `app.js` only calls `motion.setScene(scene)` / `stagger()` / `ripple()` and does not care what the animation looks like. / 想换一套动效：改 `styles/motion.css` + `scripts/ui/motion.js`。`app.js` 只调 `motion.setScene(scene)` / `stagger()` / `ripple()`，不关心动画长什么样。
- Want a different framework (Vue/React): just rewrite `renderer/`; `main/` and `shared/` stay untouched — the IPC contract has nothing to do with the rendering stack. / 想换框架（Vue/React）：重写 `renderer/` 即可，`main/` 与 `shared/` 不动——IPC 契约跟渲染技术栈无关。

## 4. The Real Flow of States and Events / 4. 状态机与事件的真实流向

```
click "Inject" / 用户点"开始注入"
  renderer/app.js  ── bridge.inject(pid) ─────────────►  main (ipc)
                                                          │
                              InjectService.start(pid) ◄──┘
                                    │
                     native-injector: spawn BridgeTap.exe --pid N
                                    │
        emit('phase', injecting) ───┼─────────────────────►  app.js → motion.setScene('injecting')
        emit('output', line) ───────┤                        → render.appendLog + motion.animateLine
        emit('phase', attached) ────┤                        → button turns green, label swaps / 按钮变绿、文案切换
        emit('notice', text) ───────┘                        → toast()
```

After a successful injection `log-tail.follow()` tails `%LOCALAPPDATA%\BridgeTap\agent.log` for 30 seconds and keeps pushing the agent log to the UI as `output(stream:'agent')` — so what the "runtime log" page shows is real JVMTI retransform records, not something a script made up.

注入成功后 `log-tail.follow()` 会跟读 `%LOCALAPPDATA%\BridgeTap\agent.log` 30 秒，把 agent 的日志当成 `output(stream:'agent')` 继续推给界面——所以"运行日志"页看到的是真实的 JVMTI 重转换记录，而不是脚本编的。

## 5. Packaging / 5. 打包

### Why `portable` / 为什么是 `portable`

| Target / 目标 | Behavior / 行为 | Suitable for / 适合 |
|---|---|---|
| `nsis` | Installs into Program Files, writes an uninstall entry into the registry / 安装到 Program Files，写注册表卸载项 | Products that need a "proper installation" / 需要"正式安装"的产品 |
| `portable` | A single self-extracting exe, extracted at runtime into `%TEMP%\<unpackDirName>`, cleaned up on exit / 单个自解压 exe，运行时释放到 `%TEMP%\<unpackDirName>`，退出清理 | No install, hand it out casually / 免安装、随手发 |

`portable` produces a single exe (about 105 MB for this project, mostly the Electron runtime) that starts on double-click; the target machine needs neither Node nor the VC runtime (Electron ships its own).

`portable` 出来就是一个 exe（本项目约 105 MB，主要是 Electron 运行时），双击即启动；目标机不需要 Node，也不需要 VC 运行库（Electron 自带）。

### Why the Injector Goes Through `asarUnpack` / 为什么注入器走 `asarUnpack`

`asar` is an archive file, and the `.exe` inside it **cannot be executed directly** (Windows needs a real file path). Therefore:

`asar` 是一个归档文件，里面的 `.exe` **不能直接执行**（Windows 需要真实文件路径）。所以：

```yaml
files:
  - vendor/launcher/BridgeTap.exe     # Into asar (on the record) / 进 asar（记录在案）
asarUnpack:
  - vendor/launcher/**                # But unpacked to disk / 但解包到磁盘
```

The actual location after packaging:

打包后的实际落点：

```
BridgeTap-Injector-1.0.10.exe         ← single-file deliverable / 单文件交付物
  └─ unpacked to %TEMP%\BridgeTapInjector\  ← 解压到此处
       resources\app.asar
       resources\app.asar.unpacked\vendor\injector\BridgeTap.exe   ← the one actually spawned / 真正被 spawn 的那个
```

`native-injector.js` probes three paths in order (unpacked → the old `resources/injector` → the dev directory), so switching the config back to `extraResources` will not break it.

`native-injector.js` 按三条路径依次探测（unpacked → 旧的 `resources/injector` → 开发目录），所以把配置改回 `extraResources` 也不会崩。

### Permissions / 权限

The manifest is `asInvoker` and does not ask for administrator rights proactively. Injecting into `javaw.exe` is usually enough; if the client itself was started as administrator (some modpacks do that), this program must be run as administrator as well, otherwise `OpenProcess` is denied.

清单是 `asInvoker`，不主动要管理员。注入 `javaw.exe` 一般够用；如果客户端本身是以管理员身份启动的（部分整合包会），需要用管理员身份运行本程序，否则 `OpenProcess` 会被拒。

## 6. Self-Check / 6. 自检

```bash
env -u ELECTRON_RUN_AS_NODE npx electron . --shot=docs/shot-devices.png  # Screenshot the UI (off-screen visual check) / 截图界面（离屏核对视觉）
env -u ELECTRON_RUN_AS_NODE node scripts/scan.js                          # Dump the real process list + the evidence used / 打真实进程列表 + 判定依据
env -u ELECTRON_RUN_AS_NODE node scripts/classify-test.js                 # Classification regression (includes the real BLC command line) / 分类逻辑回归（含 BLC 真机命令行）
npx electron scripts/smoke.js                                             # Run the injection service once without a UI / 无界面跑一遍注入服务
```

Sample output of `smoke.js` (validating the failure path with a nonexistent PID):

`smoke.js` 的输出示例（用一个不存在的 PID 验证失败路径）：

```
[smoke] injector   : ...\vendor\injector\BridgeTap.exe
[smoke] ready      : true
[smoke] start      : {"accepted":true,"ok":false}
[smoke] events     : phase, targets, phase, phase, output, phase, notice
[smoke] failure msg: 注入失败（退出码 3）· 该 JVM 里是否已经注入过一次？…
```

## 7. Pitfall Log: Process Identification (fixed in v1.0.1) / 7. 踩坑记录：进程识别（v1.0.1 修）

v1.0.0 displayed a running **Badlion** as Lunar. The cause was not a wrong regexp — the client installation layout is counter-intuitive:

v1.0.0 把跑着的 **Badlion** 显示成 Lunar。原因不是正则写错，是客户端装法反直觉：

Badlion Client 4.4.4 installs the whole ichor runtime **into the Lunar Client installation directory**, and the launch command line looks like this:

Badlion Client 4.4.4 把整套 ichor 运行时装在 **Lunar Client 的安装目录**里，启动命令行长这样：

```
-Djava.library.path="C:\Users\<u>\AppData\Local\Programs\Lunar Client\native-modules;..."
-agentpath:"C:\Users\<u>\AppData\Local\Programs\Lunar Client\native-modules\badlion_js.dll"
-cp "...\Programs\Lunar Client\libs\disruptor-3.4.2.jar;..."
--badlionPath "C:\Users\<u>\AppData\Local\Programs\Lunar Client"
```

The decision rules were therefore changed to:

判定规则因此改成：

1. Only **client-specific launch arguments** count as identity evidence (`--badlion*` / `net.badlion` / `badlion_js.dll` / `--lunar*` / `.lunarclient`); directory names and library paths never count — both sides share the same ones. / 只有**客户端专属启动参数**才算身份依据（`--badlion*` / `net.badlion` / `badlion_js.dll` / `--lunar*` / `.lunarclient`）；目录名、库路径一律不算——两边共用同一套。
2. **Badlion is checked first**: BLC's command line matches the path keywords of both sides at once, so reversing the order causes misidentification. / **Badlion 先判**：BLC 的命令行同时命中两边的路径关键字，顺序反了就误判。
3. The title takes precedence over the command line (the title is drawn by the client itself and cannot be changed). / 标题优先于命令行（标题是客户端自己画的，改不了）。
4. Only when neither matches does it fall back to "this name appears somewhere in the path", marking `cmd:path-only` in `evidence`. / 都不命中才回退到"路径里出现过这个名字"，并在 `evidence` 里标 `cmd:path-only`。

The first test case in `scripts/classify-test.js` is that command line captured from a real machine; make it pass before touching the classification logic.

`scripts/classify-test.js` 里第一条用例就是从真机抓下来的那段命令行，改分类逻辑前先让它全绿。

Also: on a cold start the first PowerShell scan may exceed 8s, and a timeout makes the UI show "0 processes" — looking exactly like "identification failed". The scan timeout has been relaxed to 15s, and failures print `[scan]` logs to stdout.

另外：冷启动时第一次 PowerShell 扫描可能超过 8s，超时会让界面显示"0 个进程"——看起来和"识别失败"一模一样。扫描超时已放宽到 15s，失败会往 stdout 打 `[scan]` 日志。

## 8. Client Icons (v1.0.2) / 8. 客户端图标（v1.0.2）

The card icon block is 42px and uses 2x bitmaps `src/renderer/assets/{badlion,lunar}.png` (84×84, transparent background), produced from the official logos by `scripts/make-client-icons.py`:

卡片图标块 42px，走 2x 位图 `src/renderer/assets/{badlion,lunar}.png`（84×84 透明底），由 `scripts/make-client-icons.py` 从官方 logo 加工：

```bash
python scripts/make-client-icons.py \
    --badlion "path/to/badlion.webp" \
    --lunar   "path/to/lunar.webp"
```

This processing step cannot be skipped:

加工这一步不能省：

- The original Badlion image is a 1996×710 **horizontal composite lockup** (hexagon lion head on the left + a nearly pure-white "BADLION" wordmark on the right). Scaling it straight down to a square icon yields a single white line, so the hexagon must first be cropped by its non-white bounding box and then have its white background turned transparent. / Badlion 原图是 1996×710 的**横版组合标**（左六边形狮头 + 右几乎纯白的 "BADLION" 字）。直接缩成方图标等于一条白线，必须先按非白包围盒裁出六边形，再做白底转透明。
- White-to-transparent inverts alpha from "the maximum channel distance to white" and then un-premultiplies the color: `alpha = clamp(max(255-r,255-g,255-b)/255/0.80)`, `F = (C-(1-alpha)·255)/alpha`. This keeps the hexagon interior 100% opaque (no grey haze on dark tiles) while preserving edge anti-aliasing with no white fringe. / 白底转透明用"按到白色的最大通道距离"反解 alpha、再反预乘颜色：`alpha = clamp(max(255-r,255-g,255-b)/255/0.80)`、`F = (C-(1-alpha)·255)/alpha`。这样六边形内部 100% 不透明（在深色块上不发灰），边缘保留抗锯齿、不糊白边。
- The original Lunar image is "a white glyph on a transparent background", which looks like a blank image in a white-background viewer (128×117, all white). It can only sit on dark tiles, so the output is the glyph itself without any background. / Lunar 原图是"白色图形 + 透明底"，在白底看图工具里就是一张空白图（128×117，全白）。它只能贴深色块，所以输出的是图形本身，不带背景。

`HEX_FILL = 0.84` / `GLYPH_FILL = 0.56` are fill ratios: a hexagon filled to 1.0 gets its left and right points clipped by the icon tile's rounded corners (the first v1.0.2 build hit this). After swapping in a new logo, look at `docs/icon-preview.png` first (the script generates it automatically: a composite of the icon on the real gradient tile), then take screenshots.

`HEX_FILL = 0.84` / `GLYPH_FILL = 0.56` 是留白比例：六边形铺满 1.0 会被图标块圆角切掉左右尖角（v1.0.2 第一版踩过）。换新 logo 后先看 `docs/icon-preview.png`（脚本会自动生成：图标贴在真实渐变底上的合成图），再去截图。

Minecraft / Java have no official mark, so they keep using letter tiles (when `CLIENT_ICON` has no `img`, it falls through to `glyph`).

Minecraft / Java 没有官方标识，继续用字母块（`CLIENT_ICON` 里没有 `img` 就走 `glyph`）。

## 9. Pure Black and White + Light / Dark / Auto Themes (v1.1.0) / 9. 纯黑白 + 浅色/暗色/自动主题（v1.1.0）

### Palette / 色板

The UI has only black, white and grey. The accent is no longer blue but "the opposite end of the base color":

UI 只有黑、白、灰。强调色不再是蓝色，而是"与底色相反的那一端"：

| Token / 令牌 | Light / 浅色 | Dark / 暗色 |
|---|---|---|
| `--ink` | `#0a0a0a` | `#fafafa` |
| `--accent` (button / selection / focus) / `--accent`（按钮/选中/焦点） | `#0a0a0a` | `#fafafa` |
| `--on-accent` | `#ffffff` | `#0a0a0a` |
| `--bg` / `--surface` | `#f4f4f4` / `#ffffff` | `#0a0a0a` / `#141414` |

Raw color values are no longer allowed inside components — everything goes through `var(--…)`. The only exception is the brand color built into client logos (Badlion's cyan hexagon); that is content, not decoration.

组件里不允许再出现裸色值——全部走 `var(--…)`。唯一的例外是客户端 logo 自带的品牌色（Badlion 的青色六边形），那是内容不是装饰。

### Three Layers of State / 三层状态

```
User choice      <html data-mode="auto|light|dark">   highlights the toggle icon · 切换按钮的图标高亮
Effective theme  <html data-theme="light|dark">       the only thing CSS reads · CSS 只认这个
System dark      prefers-color-scheme                 decides data-theme while in auto · auto 时由它决定 data-theme
```

- Renderer layer: `src/renderer/scripts/ui/theme.js` (state machine + `matchMedia` listener). / 渲染层：`src/renderer/scripts/ui/theme.js`（状态机 + `matchMedia` 监听）。
- Main process: the same choice must land on `nativeTheme.themeSource` (so `prefers-color-scheme` follows it too) and be written into `%APPDATA%/BridgeTap/theme.json`; the window's `backgroundColor` uses the same state, otherwise a dark launch flashes a white frame first (`applyTheme / readThemeMode / persistTheme` in `main.js`). / 主进程：同一个选择要落到 `nativeTheme.themeSource`（这样 `prefers-color-scheme` 也跟着变）并写进 `%APPDATA%/BridgeTap/theme.json`；窗口的 `backgroundColor` 用同一份状态，否则暗色启动会先闪一帧白底（`main.js` 的 `applyTheme / readThemeMode / persistTheme`）。
- Contract: `CALL.SET_THEME = 'theme:set'`, and the `APP_INFO` return value carries `theme:{mode,resolved}`. / 契约：`CALL.SET_THEME = 'theme:set'`，`APP_INFO` 返回值里带 `theme:{mode,resolved}`。
- First-frame fallback: the end of `tokens.css` has a `:root:not([data-theme])` override inside `@media (prefers-color-scheme: dark)`, so nothing flashes white before the JS runs. / 首帧兜底：`tokens.css` 末尾有一段 `@media (prefers-color-scheme: dark)` 的 `:root:not([data-theme])` 覆盖，JS 还没跑起来时也不会闪白。

### Button Glow (a 1:1 copy of MCHOSE HUB's du-button) / 按钮光效（1:1 照 MCHOSE HUB 的 du-button）

The numbers were read directly from the packaged driver-ui CSS of `D:\MCHOSE HUB` on this machine:

数值直接从本机 `D:\MCHOSE HUB` 的 driver-ui 打包 CSS 里读的：

| MCHOSE | Ours / 我们 | Landing spot / 落点 |
|---|---|---|
| `--du-glow-hover-color:#FFF` / opacity `1` (light) `.12` (dark) | `--btn-glow-rgb` + `--btn-glow-alpha` | Glow color / 光斑颜色 |
| `--du-glow-hover-blur:10px` | `--btn-glow-blur:9px` (light) / `16px` (dark) | `filter: blur()` |
| `--du-glow-hover-shadow: 0 4px 27px rgba(0,0,0,.06)` | first layer of `--btn-shadow` / `--btn-shadow` 第一段 | Resting drop shadow / 常态投影 |
| `inset 0 -1px 4px / 0 -2px 8px rgba(255,255,255,.4)` | `--btn-inner-light` | Inner pill highlight / 胶囊内高光 |
| `.2s ease` transition / `.2s ease` 过渡 | `--dur-base` | State transition / 状态切换 |

The implementation is a single absolutely positioned layer `.btn__glow`: `radial-gradient(var(--btn-glow-size) circle at var(--lx) var(--ly))` + `filter: blur()`; `motion.trackGlow()` is only responsible for writing the pointer position into `--lx/--ly` (percentages). On hover `opacity 0 → 1`; while pressed it drops to `.55`.

实现是 `.btn__glow` 一个绝对定位层：`radial-gradient(var(--btn-glow-size) circle at var(--lx) var(--ly))` + `filter: blur()`，`motion.trackGlow()` 只负责把指针位置写进 `--lx/--ly`（百分比）。hover 时 `opacity 0 → 1`，按下时降到 `.55`。

v1.1.1 was tuned once against the actual look (the original values were too loud in light mode and nearly invisible in dark mode):

v1.1.1 按实际观感调过一轮（原值在浅色下太抢眼、暗色下几乎看不见）：

| | Light / 浅色 | Dark / 暗色 |
|---|---|---|
| Glow radius / 光斑半径 | 96px | 96px |
| Glow intensity / 光斑强度 | `.5` (white) / `.5`（白） | `.5` (black, laid over the `#fafafa` white base) / `.5`（黑，压在 `#fafafa` 白底上） |
| Blur / 模糊 | 14px | 14px |
| Outer glow / 外发光 | `0 0 48px -16px` black halo / 黑 halo | `0 0 48px -16px` white halo / 白 halo |

As of v1.1.2 both themes share the same parameter set and **only the white blob is swapped for a black one**. A "dark mode boost" was tried in between (base pushed to `#e9e9e9` + 168px white glow + double outer glow); it was too loud and has been reverted.

v1.1.2 起两个主题共用同一套参数，**只把白斑换成黑斑**。中间试过"暗色加强"（底色压到 `#e9e9e9` + 白光 168px + 双层外发光），观感太抢眼，已撤回。

**The sweep-shine bar (`.btn__shine`) has been deleted** — the user did not want the "sweep once on hover" effect, so the `shine-sweep` keyframes, the span in the HTML and the `--btn-shine` token were all removed.

**扫光条（`.btn__shine`）已删除** ——「鼠标移上去扫一下」那个效果用户不要，`shine-sweep` 关键帧、HTML 里的 span、`--btn-shine` 令牌一并清掉。

### Two Pitfalls of the In-Card Glow (v1.1.1) / 卡片内光斑的两个坑（v1.1.1）

`.card__glow` used to be written as `top:-60%; left:-10%; width:80%; height:160%`:

`.card__glow` 原来写的是 `top:-60%; left:-10%; width:80%; height:160%`：

1. The gradient broke off at this div's boundary (70% of the card width) → the right third of the card had a **hard straight edge**, as if it had been chopped by a knife; / 渐变到这个 div 的边界（卡片 70% 宽处）就断了 → 卡片右侧三分之一是一条**直线硬边**，像被砍了一刀；
2. `--mx/--my` are percentages computed against the card yet were applied to an offset layer → **the glow was always to the left of the mouse**, drifting further the further right you went. / `--mx/--my` 是按卡片算的百分比，却作用在一个偏移过的层上 → **光斑永远比鼠标靠左**，越往右偏得越多。

Changed to `inset: 0` covering the whole card, with the radius drawn in `--card-glow-size` (px); both problems disappeared at once.

改成 `inset: 0` 铺满整张卡、半径用 `--card-glow-size`（px）画，两个问题一起没了。

### The Button's Three Kinds of "Unavailable" (v1.1.2) / 按钮的三种"不可用"（v1.1.2）

A disabled button has three different reasons, and they must look different, otherwise it degrades into "a black blob / a white blob":

按钮被 disable 有三种原因，长得必须不一样，否则会退化成"黑一坨 / 白一坨"：

| State / 状态 | Selector / 选择器 | Appearance / 观感 |
|---|---|---|
| No client selected, or scanning / 没选客户端 / 扫描中 | `.btn:disabled` | `--surface-3` neutral fill + `--ink-3` grey text + hairline border / `--surface-3` 中性底 + `--ink-3` 灰字 + 发丝边 |
| Injecting / 正在注入 | `body[data-phase="injecting"] .btn--primary:disabled` | Stays solid + spinner + breathing glow / 保持实心 + 转圈 + 呼吸光 |
| Injection complete / 注入完成 | `body[data-phase="attached"] .btn--primary:disabled` | Stays solid + 3px status ring + readable label / 保持实心 + 3px 状态环 + 可读文案 |

The trap: `:disabled` only changed `color`, while `body[data-phase="attached"] .btn--primary` (specificity 0,2,1) overrode `background` back to a solid base color, so dark fill was paired with dark text / light fill with light text and the button became a flat block of one color. The fix is to make `:disabled` control the fill as well, and write `:disabled`-specific rules for the "injecting / done" states.

踩的坑：`:disabled` 只改了 `color`，而 `body[data-phase="attached"] .btn--primary`（特异性 0,2,1）又把 `background` 覆盖成实心底色，于是深底配深字 / 浅底配浅字，按钮变成一块纯色块。修法是让 `:disabled` 同时管底色，并给"注入中/已完成"写 `:disabled` 版本的特化规则。

### Don't Fight the Single-Instance Lock During Self-Check / 自检时不要抢单实例锁

Running `npx electron .` while the app is open exits immediately (the `requestSingleInstanceLock` fails), and the screenshot is gone with it. Point userData elsewhere with an environment variable:

应用开着的时候再跑 `npx electron .` 会直接退出（`requestSingleInstanceLock` 失败），截图也就没了。用环境变量把 userData 指到别处：

```bash
BT_USER_DATA="$TEMP/bt-shot" \
  env -u ELECTRON_RUN_AS_NODE npx electron . --dev --theme=dark --shot=docs/x.png
```

`--shot-wait=4200` waits a little longer after a click (for example, to let the mock run through the whole launch flow and capture the "launched" state).

`--shot-wait=4200` 可以在点击之后多等一会儿（例如等 mock 走完整条启动流程，截"已启动"态）。

In the dark theme the button inverts to a white fill, the glow becomes a low-opacity black (`rgba(10,10,10,.07)`, blur 16px), and the outer glow becomes a white halo — the same structure, only with the lightness relationship inverted.

暗色主题下按钮反相成白底，光斑换成低透明度的黑色（`rgba(10,10,10,.07)`，blur 16px），外发光换成白色 halo —— 同一套结构，只是明度关系相反。

### Self-Check / 自检

```bash
env -u ELECTRON_RUN_AS_NODE npx electron . --theme=light --shot=docs/theme-light.png
env -u ELECTRON_RUN_AS_NODE npx electron . --theme=dark  --shot=docs/theme-dark.png
# Screenshot a "real interaction state": first move the pointer onto the button (which triggers :hover), then shoot / 截"真实交互态"：先把指针移到按钮上（会触发 :hover），再截图
env -u ELECTRON_RUN_AS_NODE npx electron . --theme=light --shot=docs/btn-hover.png --shot-hover=#btnInject
# Click once before shooting (e.g. click a device card to select it, so the primary button becomes clickable) / 先点一下再截（例如点设备卡选中，主按钮才可点）
env -u ELECTRON_RUN_AS_NODE npx electron . --shot=docs/toggle.png --shot-click=#btnTheme
```

`--theme=` only pins temporarily and does not write the user config; only clicking the theme button persists to disk (verified: `theme.json` goes from `{"mode":"light"}` to `{"mode":"dark"}`).

`--theme=` 只是临时钉住，不写用户配置；点主题按钮那一下才会落盘（验证过：`theme.json` 从 `{"mode":"light"}` 变成 `{"mode":"dark"}`）。

## 10. Settings Page: Moving `/bt` Into the Window (v1.2.0) / 10. 设置页：把 `/bt` 搬到窗口里（v1.2.0）

Settings is the third segmented page (Devices / Runtime log / Settings), with two panes left and right:

设置是第 3 个分段页（设备 / 运行日志 / 设置），左右两块：

- **Injector settings** — this program's own preferences: theme, log retention lines, mod config file location, open folder, version. Stored in `%APPDATA%/BridgeTap/settings.json`. / **注入器设置** —— 这个程序自己的偏好：主题、日志保留行数、模组配置文件位置、打开目录、版本。存在 `%APPDATA%/BridgeTap/settings.json`。
- **Mod settings** — the whole in-game `/bt` set, 14 fields, reading and writing `config/bridgetap.properties` directly. / **模组设置** —— 游戏里 `/bt` 那一整套，14 个字段，直接读写 `config/bridgetap.properties`。

| `/bt` command / `/bt` 命令 | Config key / 配置键 | Control / 控件 |
|---|---|---|
| `toggle` | `enabled` | Switch / 开关 |
| `display <mode>` | `displayMode` | Dropdown (6 modes) / 下拉（6 个模式） |
| `axis <AUTO\|Z\|X>` | `preferredAxis` | One of three / 三选一 |
| `scale <0.5-2.0>` | `hudScale` | Slider / 滑块 |
| `pos <x> <y>` | `hudX` / `hudY` | Number / 数字 |
| `background on\|off` | `backgroundEnabled` | Switch / 开关 |
| — | `textShadow` / `showReadyNotice` / `yieldToTabList` | Switches (previously editable only in the config file) / 开关（原本只有配置文件能改） |
| — | `gainText` / `noGainText` | Text boxes (placeholder = default value; empty means Gain / No Gain) / 文本框（占位符 = 默认值，空着即 Gain / No Gain） |
| `gain <nogain\|nogay>` | `gainLabel` | One of two / 二选一 |
| `chat on\|off` | `chatOutput` | Switch / 开关 |
| `offset <n>` | `tickOffset` | Number −40..40 / 数字 −40..40 |
| `plateoffset <n>` | `plateTickOffset` | Number −40..40 / 数字 −40..40 |
| `turn <12\|15>` | `turnTicks` | One of two / 二选一 |

Only four commands remain in game (since v1.5.0): `toggle`, `reset`, `axis`, `chat`. All the others `/bt pos|display|offset|plateoffset|turn|scale|gain|gaintype|color|background|status|save|reload` were removed — in the injector they are all one-click items, and keeping them in the chat bar only makes people memorize a pile of magic words.

游戏里只剩四条命令（v1.5.0 起）：`toggle`、`reset`、`axis`、`chat`。其余 `/bt pos|display|offset|plateoffset|turn|scale|gain|gaintype|color|background|status|save|reload` 全部删掉了——它们在注入器里都是点一下就能改的项，留在聊天栏只会让人记一堆魔法词。

- `/bt chat` is **a toggle**: with no argument it flips between on/off; `/bt chat on|off` is still accepted, which is handy for hardcoding. / `/bt chat` 是**切换式**：不带参数就在 on/off 之间翻；`/bt chat on|off` 仍然认，方便写死。
- Command names and verbs are **case-insensitive**: `/BT TOGGLE` and `/BridgeTap axis z` both work; only the whole word `/bt` or `/bridgetap` is recognized, so `/btw 你好` is not intercepted. / 命令名和动词都**不分大小写**：`/BT TOGGLE`、`/BridgeTap axis z` 都行；只认整词 `/bt` 或 `/bridgetap`，所以 `/btw 你好` 不会被拦。
- The injector writes to disk on every change and the agent re-reads it by itself, so `/bt save` is not needed. / 注入器每次改动都直接落盘，agent 自己会重读，不需要 `/bt save`。

### Switches Must Actually Be Read (v1.3.2) / 开关必须真被读（v1.3.2）

`showReadyNotice` and `chatOutput` used to be **dead config**: declared in `Config`, read and written through properties, but there was no second reference to `Config.showReadyNotice` / `Config.chatOutput` anywhere in the project — clicking the switch in the UI did nothing.

`showReadyNotice` 和 `chatOutput` 之前是**死配置**：`Config` 里声明了、读写了 properties，但全工程没有第二处 `Config.showReadyNotice` / `Config.chatOutput`——界面上的开关怎么点都没反应。

| Config / 配置 | Where it lands now / 现在的落点 |
|---|---|
| `showReadyNotice` | `Bridge.drawFrame`: when there is no bridging data, false returns immediately and no coordinate placeholder line is drawn / `Bridge.drawFrame`：没有搭桥数据时，false 就直接 return，不画坐标占位行 |
| `chatOutput` | `Bridge.pumpChatOutput` + `Tracker.flushSuccessMessage`: both channels — end of a run and the success line — consult it / `Bridge.pumpChatOutput` + `Tracker.flushSuccessMessage`：一局结束和成功行两条通道都看它 |
| `yieldToTabList` | `Bridge.drawFrame`: while the player-list key is held, false stops yielding and the panel keeps drawing (changed from hardcoded to a switch in v1.5.3) / `Bridge.drawFrame`：按住玩家列表键时 false 就不再让位，面板继续画（v1.5.3 从硬编码改成开关） |

### Gain Labels Can Be Reworded (v1.5.4) / Gain 标签的措辞可改（v1.5.4）

The old "use the No Gay wording" switch has been deleted. In `Config` it only replaced `No Gain` with a joke, whereas the real need was "I want to write whatever I like". Two plain text boxes (`gainText` / `noGainText`) replace it now:

原来那个「用 No Gay 文案」开关已删。它在 `Config` 里只是把 `No Gain` 换成一句玩笑，实际需求是"我自己想写什么"。现在两个纯文本框（`gainText` / `noGainText`）取代它：

| Input / 输入 | Rendered / 画出 |
|---|---|
| Empty / 空 | `[Gain]` / `[No Gain]` (the default; it is written in the placeholder) / `[Gain]` / `[No Gain]`（默认，占位符里就写着） |
| `涨了` (custom text / 自定义文案) | `[涨了]` |
| `[Gain]` | `[Gain]` (brackets are stripped, so it never becomes `[[Gain]]`) / `[Gain]`（方括号被清掉，不会变成 `[[Gain]]`） |
| More than 24 characters / 超过 24 字 | Truncated to 24 (longer text squeezes the coordinates out) / 截到 24（再长会把坐标挤出去） |

**Detection is unaffected**: `JumpSignals` still answers `Gain` / `No Gain`, and the color slots are split on that (5 / 6); only the word `JumpEntry` prints is customized. Old configs with `useNoGayLabel=true` or `gainLabel=No Gay` are migrated once in `Config.load` to `noGainText=No Gay`, so existing users see no change.

**判定不受影响**：`JumpSignals` 仍然回答 `Gain` / `No Gain`，颜色槽也按这个分（5 / 6），自定义的只是 `JumpEntry` 打印出来的那个词。旧配置里 `useNoGayLabel=true` 或 `gainLabel=No Gay` 会在 `Config.load` 时一次性迁移成 `noGainText=No Gay`，老用户的显示不变。

The control is `textControl` (`settings-view.js`), `type: 'text'` + `placeholder` + `maxLength`; `coerce` in the main process likewise trims, strips brackets/newlines and clamps the length — the UI is not the only entry point.

控件是 `textControl`（`settings-view.js`），`type: 'text'` + `placeholder` + `maxLength`；主进程 `coerce` 里同样会 trim、去方括号/换行、限长——界面不是唯一的入口。

### Holding Tab Yields (switchable since v1.5.3) / 按住 Tab 让位（v1.5.3 起可关）

The Tab player list is drawn by the client's own HUD pass at a position **before** the only injection point that works in this build, so the overlay cannot be drawn underneath it — either it covers the list or it yields. Yielding has been the default since the port; it is now a "hide the panel while Tab is held" switch (`yieldToTabList`, on by default):

Tab 玩家列表由客户端自己的 HUD pass 画，位置在本构建唯一能用的注入点**之前**，所以覆盖层没法画到它下面——要么压住列表，要么让位。让位是移植以来的默认行为，现在做成「按住 Tab 时隐藏面板」开关（`yieldToTabList`，默认开）：

```java
if (Config.yieldToTabList && binding != null && binding.isTabListKeyDown()) {
   noteOnce("paused: player-list key held");
   return false;
}
```

`isTabListKeyDown()` reads `isKeyDown()` of `GameSettings.keyBindPlayerList`, not "Tab is pressed" — so it still holds if the player has rebound the key. When key resolution fails the function returns false, i.e. "do not yield", so a failed lookup never hides the whole panel.

`isTabListKeyDown()` 读的是 `GameSettings.keyBindPlayerList` 的 `isKeyDown()`，不是"Tab 被按下"——玩家改过绑定也照样成立。键位解析失败时该函数返回 false，也就是"不让位"，不会因为读不到而把面板全藏起来。

The placeholder panel draws only **one line** of coordinates (`BridgeTap | X .. Y .. Z ..`). v1.3.3 deleted the former second line `t=1334 ground (place a block to start)` — that was debug info and looked like an error to players.

占位面板只画**一行**坐标（`BridgeTap | X .. Y .. Z ..`）。v1.3.3 把原来第二行的 `t=1334 ground (place a block to start)` 删了——那是调试信息，玩家看着像报错。

`pumpChatOutput` deliberately does not reuse `HudModel.build()` — that returns an empty list when the HUD is off, while "HUD off but results sent to chat" is a normal usage.

`pumpChatOutput` 故意不复用 `HudModel.build()`——那个在 HUD 关闭时返回空列表，而"HUD 关掉但结果发到聊天栏"是正常用法。

Every other config item has been confirmed to have a reader: `enabled` (drawFrame/HudModel), `hudX/hudY/hudScale/backgroundEnabled/textShadow` (GlHud), `displayMode` (HudModel), `preferredAxis/turnTicks/tickOffset/plateTickOffset` (Tracker), `gainLabel` (JumpEntry).

其余配置项逐个确认过都有人读：`enabled`（drawFrame/HudModel）、`hudX/hudY/hudScale/backgroundEnabled/textShadow`（GlHud）、`displayMode`（HudModel）、`preferredAxis/turnTicks/tickOffset/plateTickOffset`（Tracker）、`gainLabel`（JumpEntry）。

> These two changes live **in the agent**, so changing the injector UI version does not apply them immediately: an agent class can only be defined once per JVM, so the client must be restarted and injected again.
>
> 这两个改动在 **agent 里**，注入器界面换版本不会立刻生效：同一个 JVM 里 agent 类只能定义一次，必须重启客户端再注入。

### Why Changes Take Effect Immediately / 为什么改完立刻生效

```
injector writes the file (temp file + rename) · 注入器写文件（临时文件 + rename）
        ↓  ~500ms
agent's Config.startWatcher() sees the mtime+size change → Config.load() · agent 的 Config.startWatcher() 看到 mtime+size 变了 → Config.load()
        ↓
HUD geometry (position / scale / background / shadow) → calls GlHud.applyConfig() by reflection · HUD 几何（位置/缩放/背景/阴影）→ 反射调 GlHud.applyConfig()
the rest (enabled / displayMode / axis / offsets ...) is read live every frame or every tick anyway · 其余字段（enabled / displayMode / axis / offsets ...）本来就是每帧或每 tick 现读的
```

- `lastStamp` in `Config` is refreshed at the end of both `load()` / `save()`, so the config's own writes do not trigger a second reload; values changed in game with `/bt` are likewise not overwritten by the injector (whoever writes last wins). / `Config` 里的 `lastStamp` 在 `load()` / `save()` 结束都会刷新，所以自己写的不会触发二次重载；游戏里用 `/bt` 改的值同理不会被注入器盖掉（谁后写谁生效）。
- The HUD's `originX/originY/scale/background/shadow` used to be `final` (fixed at construction); v1.2.0 changed them to `volatile` + `applyConfig(...)`, otherwise moving the HUD in the window would only be visible after a game restart. / HUD 的 `originX/originY/scale/background/shadow` 原本是 `final`（构造时定死），v1.2.0 改成 `volatile` + `applyConfig(...)`，否则"在窗口里挪 HUD"要重启游戏才看得到。
- Files are written with "temp file + rename": the agent polls mtime and must never read a half-written properties file. / 写文件用"临时文件 + rename"：agent 是轮询 mtime 的，最怕读到写了一半的 properties。

### How the Config File Path Is Found / 配置文件路径怎么找到

`config/bridgetap.properties` is relative to CWD, and CWD is not in the process command line. Therefore:

`config/bridgetap.properties` 是相对 CWD 的，而 CWD 不在进程命令行里。所以：

1. On startup the agent prints a line `config file: <absolute path> | pid <pid>` into `agent.log`; / agent 启动时往 `agent.log` 打一行 `config file: <绝对路径> | pid <pid>`；
2. The injector parses every such line in the log into a **pid → path** map, cached in `%APPDATA%/BridgeTap/mod-config.json`; / 注入器把日志里所有这种行解析成 **pid → 路径** 的映射，缓存到 `%APPDATA%/BridgeTap/mod-config.json`；
3. On a successful injection (`phase=attached`) it learns once more automatically, so the user does not have to click "re-locate"; / 注入成功（`phase=attached`）时会自动再学一次，用户不用手点"重新定位"；
4. When nothing has been learned yet, the settings page shows "not located · inject once in game first", and writes are rejected with a reason. / 没学过的时候设置页直接显示"未定位 · 先在游戏里注入一次"，写入会被拒绝并给出原因。

**Key point: Lunar and Badlion do not use the same file.** Each client has a different CWD:

**关键：Lunar 和 Badlion 用的不是同一个文件。** 各客户端的 CWD 不同：

| Client / 客户端 | Config file / 配置文件 |
|---|---|
| Lunar Client | `%LOCALAPPDATA%\Programs\Lunar Client\config\bridgetap.properties` |
| Badlion, vanilla / Badlion、原版 | `%APPDATA%\.minecraft\config\bridgetap.properties` |

v1.2.x only remembered "the last config file in the log", so starting Lunar and then BLC wrote everything into Lunar's file and BLC showed no reaction at all (that is exactly what v1.3.0 fixed). The current resolution order:

v1.2.x 只记"日志里最后一条 config file"，于是先开 Lunar 再开 BLC 时，设置全部写进 Lunar 的文件，BLC 里一点反应都没有（v1.3.0 修的就是这个）。现在的解析顺序：

1. **Look up the log map by the PID of the currently selected process** (most accurate; CWD is a runtime fact); / **按当前选中进程的 PID** 查日志映射（最准，CWD 是运行期事实）；
2. Old agent logs have no pid → **infer the directory from the client name** (the two paths in the table above, used only if the file exists); / 老版本 agent 的日志没有 pid → **按客户端名推目录**（上表两个路径，文件存在才用）；
3. Neither → fall back to "the last record" and state "the last injected client" in the status bar. / 都没有 → 退回"最后一条记录"，并在状态条里写明"上次注入的客户端"。

The status line on the settings page carries the client name (`Badlion · Connected · changes take effect within 0.5s`); when another client is selected on the devices page, the settings page automatically switches to that client's config file and re-reads it.

设置页那一行状态会带上客户端名（`Badlion · 已连接 · 改动 0.5 秒内生效`）；在设备页换选另一个客户端时，设置页会自动切到那个客户端的配置文件重新读一遍。

### One Shared Set (v1.3.1) / 共用一套（v1.3.1）

Writing per client brought a new problem: change one copy and the other client "stops working". What users want is **one set of settings that both clients consume**, so writes became a broadcast:

按客户端分开写会带来新问题：只改一份，另一个客户端就"失效"了。用户要的是**一套设置两个客户端都吃**，所以写入改成广播：

```
change one key · 改一个键
  → knownConfigPaths() collects every known config file · 收集所有已知配置文件
      (pid→path from the log, `last`, plus the files that really exist under both client dirs) · （日志里的 pid→路径、last，加上两个客户端目录里真实存在的配置文件）
  → each copy is based on its own current content (extra keys preserved); only the changed keys are overwritten · 每份以自己的现有内容为底，只覆盖改动的键
  → write to a temp file + rename · 临时文件 + rename 落盘
```

- Reads still come from "the file of the currently selected client"; that is what is displayed; / 读取仍以"当前选中客户端的那份"为主，显示的就是它；
- Only **existing** files are written, so no directory is created out of thin air when Lunar is not installed; / 只写**已存在**的文件，没装 Lunar 就不会凭空造目录；
- The status bar says "Lunar / Badlion share one set · 2 files synced", and after a change "applied to 2 configs · time"; / 状态条写"Lunar / Badlion 共用一套 · 已同步 2 份"，改完显示"已应用到 2 份配置 · 时间"；
- Offline verification: `node scripts/test-settings-resolve.js` and `node scripts/test-settings-write.js` (both scripts fabricate data in a temp directory and **never touch real client configs** — this was learned the hard way: only LOCALAPPDATA was redirected while APPDATA stayed real, and the test wiped the real `.minecraft` config). / 离线验证：`node scripts/test-settings-resolve.js` 和 `node scripts/test-settings-write.js`（两个脚本都在临时目录里造假数据，**不会碰真实客户端配置**——踩过一次：只改了 LOCALAPPDATA，APPDATA 还是真的，测试把真实 `.minecraft` 配置写掉了）。

### The Default-Value Trap / 缺省值的坑

When an old file has no `configRevision`, the agent treats the whole file as an old version and migrates it, resetting `tickOffset` to 0. So the first time the settings page writes an empty / nonexistent file, it fills in all fields together with `configRevision=2`; otherwise "the offset you just tuned flips back to 0 the next second".

旧文件里没有 `configRevision` 时，agent 会把整个文件当旧版本迁移，把 `tickOffset` 重置成 0。所以设置页第一次写一个空/不存在的文件时，会把所有字段和 `configRevision=2` 一起补齐，否则"刚调好的偏移下一秒自己变回 0"。

## 11. Auto Scan and Auto Inject (v1.2.1) / 11. 自动扫描与自动注入（v1.2.1）

### Auto Scan / 自动扫描

The injector scans from startup and never stops, **regardless of whether it is in the foreground** (auto injection must be able to finish the job in the background); the interval adapts:

注入器一启动就一直扫，**不看在不在前台**（自动注入要能在后台把活干完），间隔自适应：

| Result of the last scan / 上一次扫描的结果 | Next interval / 下一次间隔 |
|---|---|
| No processes / 没有进程 | 3 seconds / 3 秒 |
| Processes present / 有进程 | 15 seconds / 15 秒 |
| Had processes → all gone / 有进程 → 全没了 | Back to 3 seconds / 回到 3 秒 |

Implementation notes:

具体实现要点：

- Uses a `setTimeout` chain instead of `setInterval`, and **subtracts the current scan's duration** before scheduling the next one (launching PowerShell once takes about 1 second on Windows; without subtracting, 3 seconds becomes 4+ seconds); / 用 `setTimeout` 链而不是 `setInterval`，并且**扣掉本次扫描耗时**再排下一次（Windows 起一次 PowerShell 约 1 秒，不扣的话 3 秒会变成 4 秒多）；
- Scanning goes through `listTargets({ silent: true })`: it does not switch the state machine (so the chips are not repainted as "scanning" every few seconds, and the primary button is not disabled); / 扫描走 `listTargets({ silent: true })`：不切状态机（不会每几秒把芯片刷成"正在扫描"、也不会把主按钮 disable 一下）；
- If the list fingerprint (`pid:client:injectable:title`) is unchanged it **does not repaint** — otherwise the cards would keep re-animating in; / 列表指纹（`pid:client:injectable:title`）没变就**不重绘**——否则卡片会反复重新入场；
- Rounds are skipped while an injection is in progress: `service.listTargets` returns an empty array when busy, and using that to decide "are there processes" would wrongly knock the cadence back to 3 seconds; / 注入进行中跳过这一轮：`service.listTargets` 在 busy 时直接返回空数组，拿它去判"有没有进程"会把节奏错误地打回 3 秒；
- `webPreferences.backgroundThrottling: false` — when the window is minimized or occluded Chromium throttles `setTimeout` to once a minute, which would kill auto scanning outright. / `webPreferences.backgroundThrottling: false` —— 窗口最小化/被遮挡时 Chromium 会把 `setTimeout` 降到 1 分钟一次，自动扫描会直接废掉。
- Self-check: the main process prints a `[scan] poll <time>` line for every silent poll, so a few dozen seconds is enough to see whether the cadence is right (measured 3.0 seconds on an empty field and 15.0 seconds with processes). / 自检：主进程对每次 silent 轮询打一行 `[scan] poll <时间>`，跑几十秒就能看出节奏对不对（实测空场子 3.0 秒、有进程 15.0 秒）。

### Auto Re-Inject After a Game Restart (v1.2.3) / 游戏重启后要能自动再注一次（v1.2.3）

Previously, after one successful injection the renderer scene stayed at `attached` and never changed, and `maybeScheduleAutoInject()` relied on exactly that state for its "already injected, stop the countdown" check — so when the player closed the game and reopened it (new PID), the scan did find the process yet auto injection never fired, and the UI kept showing "injection complete".

原来注入成功一次之后，渲染层场景就停在 `attached` 不再变化，而 `maybeScheduleAutoInject()` 里「已注入完就别再倒计时」正是靠这个状态判断的——于是玩家关了游戏再打开（新 PID），扫描明明扫到了进程，自动注入却不触发，界面还一直显示"注入完成"。

It is now driven by **the life and death of the PID** instead of just the scene string:

现在按 **PID 的生死**来管，而不是只看场景字符串：

| Trigger point / 触发点 | Action / 动作 |
|---|---|
| Injection succeeds (`attached`) / 注入成功（`attached`） | Record `injectedPid = attemptPid` / 记下 `injectedPid = attemptPid` |
| Injection fails (`failed`) / 注入失败（`failed`） | Record `failedPid = attemptPid`; the same PID is never auto-retried (otherwise one failure means infinite re-injection) / 记下 `failedPid = attemptPid`，同一个 PID 不再自动重试（否则失败一次就无限重注） |
| A scan finds `injectedPid` no longer in the list / 扫描发现 `injectedPid` 已不在列表 | Clear `injectedPid` / `failedPid` and drop the scene back to `idle` (the button returns to "start injection") / 清空 `injectedPid` / `failedPid`，场景回落 `idle`（按钮恢复"开始注入"） |
| A scan finds `failedPid` no longer in the list / 扫描发现 `failedPid` 已不在列表 | Clear only `failedPid`, allowing a retry against the new process / 只清 `failedPid`，允许对新进程再试 |

So the chain becomes: injected → player closes the game → the next scan (3-second tier) sees the process gone → state resets to zero → after the game restarts the scan finds the new PID → countdown → auto inject. The same JVM is still only injected once (no re-injection while `injectedPid` is present), which also respects the hard constraint that "an agent class can only be defined once per JVM".

于是链路变成：注完 → 玩家关游戏 → 下一轮扫描（3 秒档）发现进程没了 → 状态归零 → 游戏重启后扫描到新 PID → 倒计时 → 自动注入。同一个 JVM 仍然只会被注一次（`injectedPid` 还在就不重注），这也符合"agent 类在同一个 JVM 里只能定义一次"的硬约束。

Self-check: `mock.js` supports `BT_MOCK_RESTART=<n>` — the n-th scan returns an empty list (game closed), after which all PIDs get +1000 (restart). Combined with `--view=log` screenshots, the log shows two `attaching to pid` groups (20316 → 21316), proving the injection really happened twice.

自检：`mock.js` 支持 `BT_MOCK_RESTART=<n>`——第 n 次扫描返回空列表（游戏关了），之后所有 PID +1000（重启）。配合 `--view=log` 截图，日志里能看到两组 `attaching to pid`（20316 → 21316），证明真的注了两次。

### Auto Inject / 自动注入

Two new items on the settings page (stored in `settings.json`): `autoInject` (switch), `autoInjectDelaySec` (1–120 seconds, default 5).

设置页两个新项（存 `settings.json`）：`autoInject`（开关）、`autoInjectDelaySec`（1–120 秒，默认 5）。

The flow lives in the renderer; the main process does not get involved:

流程在渲染层，主进程不掺和：

```
targets event → maybeScheduleAutoInject() · targets 事件 → maybeScheduleAutoInject()
   ├ switch off / already injected / injecting / no target → cancel the countdown · 开关关 / 已注入完 / 注入中 / 没有可注入目标 → 取消倒计时
   └ target present → start a 4 Hz countdown; button label becomes "auto-inject Ns · click to cancel" · 有目标 → 起 4 Hz 计时，按钮文案变"自动注入 Ns · 点此取消"，
              footer names the process and the seconds left; at 0 it calls bridge.inject(pid) · 底部提示写清哪个进程、还有几秒；到 0 就 bridge.inject(pid)
```

- It does not restart the countdown when already counting down the same pid, so re-timing every 3 seconds never blocks the injection forever; / 已经倒计时同一个 pid 时不重置，避免每 3 秒重新计时导致永远注不进去；
- Clicking the primary button during the countdown = cancel (not inject immediately), with a toast; / 倒计时期间点主按钮 = 取消（不是立刻注入），并 toast 提示；
- It also cancels when the target disappears (player closes the game). / 目标消失（玩家关游戏）也取消。

### Keyboard Shortcuts / 快捷键

The `1 / 2 / 3` page shortcuts have been deleted — the settings page has number inputs, and pressing 3 jumped pages, making input impossible. Now `keydown` first checks whether focus is inside `input / select / textarea / contenteditable` and yields all shortcuts if so (`Enter` to trigger injection and `R` to refresh are affected the same way).

`1 / 2 / 3` 切页的快捷方式已删除——设置页有数字输入框，按 3 会直接跳页，根本没法输入。现在 `keydown` 先判断焦点是否在 `input / select / textarea / contenteditable` 里，是就把所有快捷键让出去（`Enter` 触发注入、`R` 刷新同样受影响）。

v1.2.2 also changed auto scanning from "scan only while the window is focused" to "always-on adaptive scanning" (see the table in the previous section), so the explanatory line in the settings page was changed to "always-on scan: once every 3 seconds when idle, slows to 15 seconds when processes are found, back to 3 seconds when they are gone".

v1.2.2 又把自动扫描从"窗口聚焦才扫"改成"常驻自适应扫"（见上一节表格），所以设置页里那一行的说明也改成了"常驻扫描：空场子 3 秒一次，扫到进程放慢到 15 秒，进程没了再回到 3 秒"。

## 12. Application Icon (v1.3.4) / 12. 应用图标（v1.3.4）

A white rounded square with a black bridge-shaped curve — the same shape as the mark in the top-left corner of the UI and the one seen in the taskbar / Explorer.

白色圆角方块 + 一条黑色桥形曲线，和界面左上角那个标、以及任务栏/资源管理器里看到的是同一个造型。

- Generation: `node scripts/make-icon.js` (zero dependencies; it computes pixels itself, hand-writes PNG and assembles ICO itself) / 生成：`node scripts/make-icon.js`（零依赖，自己算像素 + 手写 PNG + 自己拼 ICO）
- Output: `build/icon.png` (1024×1024) and `build/icon.ico` (seven sizes 16/24/32/48/64/128/256, each rendered separately rather than downscaled from 256, so small sizes stay crisp) / 输出：`build/icon.png`（1024×1024）和 `build/icon.ico`（16/24/32/48/64/128/256 七个尺寸，每个尺寸单独渲染，不是拿 256 缩下去，小尺寸才不糊）
- The curve is exactly the SVG path from the UI (`M3 16.5 c3.2 0 3.2 -9 6.4 -9 s3.2 9 6.4 9 s3.2 -9 5.2 -9`), sampled into a polyline through the cubic Bézier and anti-aliased at 1px with a distance field, so 1024 and 16 share the same geometry / 曲线用的就是界面里那段 SVG path（`M3 16.5 c3.2 0 3.2 -9 6.4 -9 s3.2 9 6.4 9 s3.2 -9 5.2 -9`），按三次贝塞尔采样成折线，用距离场算 1px 抗锯齿，所以 1024 和 16 是同一套几何
- Corner radius 29.5%, stroke 7.8%, curve 56% of the width — all matching the proportions of the mark in the screenshots / 圆角 29.5%、线宽 7.8%、曲线占宽 56%，都对齐截图里那个标的比例
- On light desktops a white block bleeds into the background, so sizes ≥48px carry a 0.4% hairline outline (self-check image: `docs/icon-preview.png`) / 浅色桌面上白块会糊进背景，所以 ≥48px 的尺寸带一圈 0.4% 的极细描边（自检图：`docs/icon-preview.png`）

The brand-mark in the title bar was changed to the same white-fill / black-line set (the light theme used to be an inverted black fill with a white line, which did not match the taskbar icon); the light theme edges it with `inset 0 0 0 1px rgba(10,10,10,.12)`.

标题栏的 brand-mark 也改成同一套白底黑线（浅色主题以前是反相的黑底白线，跟任务栏图标对不上），浅色主题靠 `inset 0 0 0 1px rgba(10,10,10,.12)` 收边。

## 13. Clearing State After a World Switch (v1.3.9) / 13. 世界切换后自动清状态（v1.3.9）

Symptom: in a multi-world plugin, after switching from world A to world B, if the previous world had just finished a run (the HUD still showed `Failed 0.70s 1 jumps`), no bridging data was produced afterwards and `/bt reset` was required to recover. Switching worlds in the middle of a run did not have this problem.

现象：多世界插件里从 A 世界切到 B 世界后，如果上一个世界刚跑完一局（HUD 上还挂着 `Failed 0.70s 1 jumps`），之后就不再产生任何搭路数据，必须 `/bt reset` 才恢复。正在搭路时切世界反而没这个毛病。

### Root Cause / 根因

All three pieces of state compared absolute ticks from "the previous world":

三处状态都在拿「上一个世界」的绝对 tick 比：

| State / 状态 | Problem / 问题 |
|---|---|
| `RunStartCooldown.finishedTick` | The cooldown test is `tick - finishedTick >= 5`. In the new world `getTotalWorldTime` starts at 0 while `finishedTick` is still the old world's 300k, so the difference is permanently negative → always "still cooling down" → every placement is ignored / 冷却判据是 `tick - finishedTick >= 5`。新世界的 `getTotalWorldTime` 从 0 开始，`finishedTick` 却还是旧世界的三十万，差值一直是负数 → 永远「还在冷却」→ 每一次放置都被忽略 |
| `pendingJumps` / `pendingPlateSample` | The ticks they wait for belong to the old world and will never come again → the next run can never start / 它们等的 tick 属于旧世界，永远不会再出现 → 下一局永远开不起来 |
| `entries` / `status` | Old data stays on the HUD and looks like "finished", while actually locking the whole tracker up / 旧数据留在 HUD 上，看着像「已结束」，实际是把整个追踪器锁死 |

Not being affected mid-run is because `finishedTick` has not been written yet at that point, so there is nothing to lock.

正在搭路时不受影响，是因为那时候还没写 `finishedTick`，所以没东西可锁。

### The Fix / 改法

1. **World identity detection (primary signal)**: `WorldProbe.worldIdentity()` returns the World object's `System.identityHashCode`. In 1.8.9 each dimension has its own World instance, so a multi-world plugin or a server switch necessarily changes the object, making an identity change an exact signal — more reliable than ticks, because ticks also jump when frames drop. The `Bridge` tick callback compares identities and calls `Tracker.onWorldChange("world object changed")` when they differ. / **世界身份检测（首选信号）**：`WorldProbe.worldIdentity()` 返回 World 对象的 `System.identityHashCode`。1.8.9 每个维度一个 World 实例，多世界插件/换服必然换对象，所以身份变化是精确信号——比 tick 更可靠，因为 tick 在掉帧时也会跳。`Bridge` 的 tick 回调里比较身份，变了就 `Tracker.onWorldChange("world object changed")`。
2. **Tick regression as a fallback**: the old "realign when ticks are discontinuous" branch in `Tracker.onTick` now splits into two cases: / **tick 倒退兜底**：`Tracker.onTick` 里原来的「tick 不连续就 realign」分支现在分两种：
   - going backwards, or advancing more than `WORLD_SWITCH_TICKS = 200` → another world's clock → `onWorldChange(...)` / 倒退，或前进超过 `WORLD_SWITCH_TICKS = 200` → 另一个世界的时钟 → `onWorldChange(...)`
   - only a few ticks skipped → frame drop, discard the sample only and leave the state alone (otherwise one stutter would wipe the run in progress) / 只跳了几 tick → 掉帧，仅丢样本，不动状态（否则一卡就清空正在跑的这一局）
3. **Cooldown self-debounce**: `RunStartCooldown.ready` lets it through when `tick < finishedTick` and clears `finished` — you cannot measure 5 ticks between two different clocks. / **冷却自我防抖**：`RunStartCooldown.ready` 遇到 `tick < finishedTick` 直接放行，并把 `finished` 清掉——不同时钟之间没法量 5 tick。
4. **Pending sample expiry**: `PENDING_MAX_AGE = 60`; a sample that never arrives is discarded after 60 ticks with one log line, so a dead sample can never block a new run forever. / **待采样过期**：`PENDING_MAX_AGE = 60`；等不到的样本 60 tick 后丢弃并记一行日志，不让一个死样本永久挡住新局。

`onWorldChange` no longer goes through `/bt reset` (that would wipe the entries as well). **Entries are kept so the player can align them against server replays; wiping them leaves nothing to compare**, so only runtime state belonging to the old world is replaced:

`onWorldChange` 不再走 `/bt reset`（那条会连条目一起清）。**条目是留给玩家跟服务器回放对轴用的，清掉就没得比了**，所以只换掉属于旧世界的运行期状态：

| Kept / 保留 | Cleared / 清掉 |
|---|---|
| `entries` (the data of every jump / turn / plate) / `entries`（每一条跳/转/板的数据） | `restartCooldown` (the cooldown recorded with the old world's ticks) / `restartCooldown`（旧世界 tick 记的冷却） |
| `status` (an unfinished run is re-marked `FAILED`) / `status`（没跑完的一局改记 `FAILED`） | `pendingJumps` / `pendingPlateSample` |
| `currentBridgeTime` (the duration of the last run) / `currentBridgeTime`（最后那一局的用时） | The position ring, `PlacementEvidence`, and the airborne timing baseline / 位置环、`PlacementEvidence`、空中计时基准 |

After switching worlds the player sees the previous run's data left as-is; when they place the next block, `startRun()` replaces it. (Since v1.4.8)

玩家切世界后会看到上一局的数据原样留着，接着放下一块时 `startRun()` 才把它替换掉。（v1.4.8 起）

## 14. Always Run as Administrator (v1.4.3) / 14. 始终以管理员运行（v1.4.3）

Injection needs `OpenProcess` / `VirtualAllocEx` / `CreateRemoteThread` to open the game process. When the client is started as administrator (many launchers do that), a process without a matching integrity level only gets `OpenProcess failed: 5`. It is now administrator all the way through.

注入要 `OpenProcess` / `VirtualAllocEx` / `CreateRemoteThread` 打开游戏进程。客户端以管理员身份启动时（不少启动器会这么干），非同级权限只能拿到 `OpenProcess failed: 5`。现在改成全程管理员。

### Two Layers of Guarantee / 两层保证

1. **Manifest declaration** (`electron-builder.yml`) / **清单声明**（`electron-builder.yml`）
   - `win.requestedExecutionLevel: requireAdministrator` → the manifest embedded in `win-unpacked/BridgeTap.exe` declares administrator / `win.requestedExecutionLevel: requireAdministrator` → `win-unpacked/BridgeTap.exe` 内嵌清单声明管理员
   - `portable.requestExecutionLevel: admin` → the self-extracting shell also declares administrator, so a double-click raises UAC immediately and the extracted app inherits the elevated token; there is no half-baked "shell elevated, inner app not elevated" state / `portable.requestExecutionLevel: admin` → 自解压外壳也声明管理员，双击直接弹 UAC，解出来的 app 继承提权令牌，不会出现「外壳提权、内部没提权」的半吊子状态

   Verification method (after packaging, search the bytes directly; the manifest is UTF-8 XML inside resources):

   校验方式（打包后直接搜字节，清单是资源里的 UTF-8 XML）：

   ```python
   data = open("out-v143/BridgeTap-Injector-1.4.3.exe", "rb").read()
   assert b"requireAdministrator" in data
   ```

2. **Runtime guard** (top of `src/main/main.js`) / **运行时守护**（`src/main/main.js` 顶部）
   Catches the launch paths the manifest cannot cover: running the exe in win-unpacked directly, launching from a normal terminal, being spawned by another program. / 兜住清单管不到的启动方式：直接跑 win-unpacked 里的 exe、从普通终端拉起、被别的程序 spawn。

   ```js
   if (process.platform === 'win32' && !isDev && !isElevated()) {
     if (relaunchElevated()) app.exit(0);   // New instance takes over; the old one must exit cleanly before grabbing the lock / 新实例接手，旧实例必须在抢锁前退干净
   }
   ```

   - `isElevated()`: runs `net session`; a non-administrator gets a non-zero result (access denied) / `isElevated()`：跑 `net session`，非管理员返回非零（拒绝访问）
   - `relaunchElevated()`: relaunches itself with `Start-Process -Verb RunAs`, passing its arguments through unchanged (single-quoted, with inner `'` doubled, so paths containing spaces or apostrophes are safe) / `relaunchElevated()`：用 `Start-Process -Verb RunAs` 重新拉起自己，参数原样带过去（单引号包裹，内部 `'` 双写，路径带空格/撇号都安全）
   - If UAC is declined it keeps running with the current privileges: settings, logs and auto scanning all still work; only injection fails and reports the native error code / UAC 被点「否」时继续以当前权限运行：设置页、日志、自动扫描都还能用，只有注入会失败并报原生错误码
   - `--dev` does not trigger it (screenshot self-checks and local debugging must be able to run directly, otherwise UAC pops up every time) / `--dev` 不触发（截图自检、本地调试要能直接跑，否则每次弹 UAC）

### Notes / 注意

- After elevation `%APPDATA%` / `%TEMP%` are still the current user's directories, so config and log locations are unchanged. / 提权后 `%APPDATA%` / `%TEMP%` 仍是当前用户目录，配置和日志位置不变。
- An elevated process can inject into a non-elevated target process without issue; only the reverse fails, so this rule only makes things more permissive. / 管理员进程往非管理员的目标进程注入没有问题，反过来才不行，所以这一条只会更宽松。
- To run temporarily with normal privileges (troubleshooting permission issues): add `--dev`. / 想临时以普通权限跑（排查权限相关问题）：加 `--dev`。

## 15. Text Color Sectioning + Swatch Picker (v1.4.5) / 15. 文本颜色分区 + 色块选择器（v1.4.5）

### Sectioning / 分区

The 8 `color.*` items in mod settings are no longer mixed into the middle of the list; they became a separate "Text color settings" block at the end of the list:

模组设置里 8 个 `color.*` 项不再混在列表中间，改成列表末尾独立一块「文本颜色设置」：

- Header: title + subtitle + **an 8-dot preview of the current palette** + `Edit` / `Collapse` buttons / 头部：标题 + 副标题 + **当前配色的 8 个小点预览** + `编辑` / `收起` 按钮
- Collapsed by default, expanded by `Edit`; once expanded, `body.scrollIntoView({block:"nearest"})` brings the content into the viewport (the panel itself is the scroll container; without scrolling the lower half stays out of sight) / 默认收起，`编辑` 展开；展开后 `body.scrollIntoView({block:"nearest"})` 把内容带进视口（面板本身是滚动容器，不滚的话下半截看不见）
- The collapsed state lives in a module-level `colorsExpanded`: the settings page repaints the whole block on every change, so the state has to live outside or it snaps back / 折叠状态存在模块级 `colorsExpanded`：设置页每次改动都会整块重画，状态放外面才不会弹回去

`renderModSettings` now splits the fields into two groups by key prefix:

`renderModSettings` 现在按 key 前缀拆两拨：

```js
const colorFields = fields.filter((f) => f.key.startsWith('color.'));
const plainFields = fields.filter((f) => !f.key.startsWith('color.'));
```

### Swatch Picker / 色块选择器

The dropdown was replaced by 16 filled swatches (8 columns × 2 rows); one click applies immediately:

下拉框换成 16 个填色小方块（8 列 × 2 行），点一下即生效：

- Colors come from **the fixed 1.8.9 chat palette**, not a generic 8-bit color: `§0 #000000 / §1 #0000AA / §2 #00AA00 / §3 #00AAAA / §4 #AA0000 / §5 #AA00AA / §6 #FFAA00 / §7 #AAAAAA / §8 #555555 / §9 #5555FF / §a #55FF55 / §b #55FFFF / §c #FF5555 / §d #FF55FF / §e #FFFF55 / §f #FFFFFF` / 颜色取 **1.8.9 聊天栏那套固定调色板**，不是通用 8bit 色：`§0 #000000 / §1 #0000AA / §2 #00AA00 / §3 #00AAAA / §4 #AA0000 / §5 #AA00AA / §6 #FFAA00 / §7 #AAAAAA / §8 #555555 / §9 #5555FF / §a #55FF55 / §b #55FFFF / §c #FF5555 / §d #FF55FF / §e #FFFF55 / §f #FFFFFF`
- Selected state: an outer `--accent` ring + an inner `--surface` gap + a 7px white dot in the center (the dot carries a 1.5px dark outline so it is visible on both white and black swatches) / 选中态：外圈 `--accent` 描边 + 内圈 `--surface` 分隔 + 中心一个 7px 小白点（白点带 1.5px 深色描边，黑白块上都看得见）
- Each row shows instant feedback such as `§b · Cyan` / `§b · 青` on the right, updating the moment you click / 每行右侧显示 `§b · 青` 这种即时反馈，点完立刻变
- The swatch itself has a 1px inset outline, so white swatches do not bleed into the panel background in the light theme / 方块本身带 1px 内阴影描边，浅色主题下白色块不会糊进面板背景
- Keyboard accessible: `<button>` + `aria-pressed`, with a separate outline on `focus-visible` / 键盘可达：`<button>` + `aria-pressed`，`focus-visible` 有独立描边

### Verification / 验证

- Screenshots of both themes: `docs/color-open.png`, `docs/color-light.png` / 深/浅两套主题截图：`docs/color-open.png`、`docs/color-light.png`
- After clicking the `§d` swatch: the DOM immediately becomes "§d · purple", and the config file `color.0=d` is persisted (verified in a fixture environment; the real `%APPDATA%` was untouched and the md5 is identical before and after) / 点 `§d` 色块后：DOM 立刻变「§d · 紫」，配置文件 `color.0=d` 落盘（夹具环境验证，真实 `%APPDATA%` 未被触碰，前后 md5 一致）
- Expanding without clicking a swatch writes nothing (`color.0` keeps its original value) / 仅展开不点色块不会写盘（`color.0` 保持原值）

## 16. Multi-language (v1.5.5) / 16. 多语言（v1.5.5）

From the injector window to the in-game HUD, every user-visible string goes through a language pack; no hardcoded copy remains in the code. **The two language settings are separate**: the injector UI has one (`appLanguage` in `settings.json`), the mod HUD and `/bt` replies have another (`language` in `bridgetap.properties`). The reason is practical — a player may want a Chinese UI with an English HUD (so the terminology matches videos / tutorials), or the opposite; a single switch cannot express that.

从注入器窗口到游戏里的 HUD，所有用户可见的文字都走语言包，代码里不再出现硬编码文案。**两个语言设置是分开的**：注入器界面是一套（`settings.json` 的 `appLanguage`），模组 HUD 与 `/bt` 回复是另一套（`bridgetap.properties` 的 `language`）。理由很实际——玩家可能想要中文界面配英文 HUD（术语对得上录像/教程），也可能反过来；绑成一个开关就做不到了。

### 16.1 Injector: `i18n/*.json` / 16.1 注入器：`i18n/*.json`

```
i18n/
├── index.json      available languages (the dropdown is built from this) · 可用语言列表（下拉框从这里长出来）
├── zh-CN.json      Simplified Chinese (189 keys) · 简体中文（189 条）
└── en.json         English (key set identical to the above) · 键与上面完全一致
```

`index.json`:

`index.json`：

```json
{
  "default": "zh-CN",
  "locales": [
    { "code": "zh-CN", "name": "简体中文", "file": "zh-CN.json" },
    { "code": "en", "name": "English", "file": "en.json" }
  ]
}
```

- **Adding a language**: copy a json, change the values, add one line to `locales`. No code changes at all; the settings dropdown automatically gains an item (the list comes from `listLocales()`, not hardcoded). / **加一门语言**：复制一份 json、改值、在 `locales` 里加一行。代码一行都不用动，设置页的下拉会自动多一项（列表来自 `listLocales()`，不是写死的）。
- **Missing keys**: fall back to the `default` language; if still missing, show the key name itself — `mod.hudX.label` showing up in the UI is easier to diagnose than a silent blank. / **缺键**：回退到 `default` 语言；再缺就显示键名本身——界面上会出现 `mod.hudX.label` 这种东西，比静默空白好排查。
- **Placeholders**: `{0}` `{1}`, substituted in order. `Intl` / `toLocaleString` is deliberately avoided, otherwise `1.05` becomes `1,05` in some locales and no longer matches the numbers in game. / **占位符**：`{0}` `{1}`，按顺序替换。刻意不用 `Intl`/`toLocaleString`，否则 `1.05` 在某些区域会变成 `1,05`，和游戏里的数字对不上。

Loading chain:

加载链路：

| Location / 位置 | File / 文件 | Responsibility / 职责 |
|---|---|---|
| Main process / 主进程 | `src/main/i18n.js` | Reads `i18n/`, merges by code (default as base + target overlay), and `t()` for window titles and error messages / 读 `i18n/`、按 code 合并（默认打底 + 目标覆盖）、`t()` 供窗口标题与错误消息 |
| IPC | `I18N_INDEX` / `I18N_LOAD` in `contract.js` / `contract.js` 的 `I18N_INDEX` / `I18N_LOAD` | The renderer fetches the whole dictionary on demand / 渲染层按需取整份字典 |
| Renderer / 渲染层 | `src/renderer/scripts/i18n.js` | `initI18n()` / `t()` / `applyStatic()` / `fieldText()` |

Static DOM uses attributes instead of manual filling:

静态 DOM 用属性，不用手动填：

```html
<span data-i18n="action.rescan"></span>
<button data-i18n-title="win.close" data-i18n-aria="win.close"></button>
<input data-i18n-placeholder="mod.gainText.placeholder">
```

`applyStatic()` runs once at the very start of `boot()`; dynamic copy afterwards (toasts, countdown, status bar) all goes through `t()`.

`applyStatic()` 在 `boot()` 最开头跑一次；之后动态文案（toast、倒计时、状态条）都走 `t()`。

**The field table stores keys only**: `MOD_FIELDS` in `settings.js` holds `labelKey` / `hintKey` / `options[].labelKey`, and all copy lives in the language pack. That way the main process never pushes Chinese into an English UI, and translators never have to touch code. Color slot names are the exception: `labelKey: 'mod.colorN.label'` + `labelArg: 'slot.3'`, with a second lookup performed by the renderer.

**字段表只存键**：`settings.js` 的 `MOD_FIELDS` 里是 `labelKey` / `hintKey` / `options[].labelKey`，文案全在语言包里。这样主进程不会把中文下发到英文界面，翻译也不用改代码。颜色槽的名是个例外：`labelKey: 'mod.colorN.label'` + `labelArg: 'slot.3'`，由渲染层做二次查表。

**Switching language reloads the whole page** (`saveAppSetting({appLanguage}).then(() => location.reload())`): this screen's DOM was all drawn at startup from the old dictionary, and partial replacement easily misses a block; a reload is the least error-prone and fastest.

**换语言要整页重载**（`saveAppSetting({appLanguage}).then(() => location.reload())`）：这一屏的 DOM 全是启动时按旧字典画的，局部替换容易漏掉某块，重载最省心也最快。

**Self-check**: `node scripts/check-i18n.js` — the key sets of both packs must be identical; every key referenced by `t('…')`, `data-i18n` and `labelKey` in code must exist in the packs, and one missing key exits with an error (usable directly as a CI gate).

**自检**：`node scripts/check-i18n.js`——两套包的键必须一致；代码里 `t('…')`、`data-i18n`、`labelKey` 引用的每个键都要能在包里找到，缺一个就报错退出（CI 里可以直接当门禁）。

### 16.2 Mod: `lang/*.properties` / 16.2 模组：`lang/*.properties`

```
core/src/main/resources/lang/en.properties      → 打进 agent.jar，兜底
core/src/main/resources/lang/zh_CN.properties   → 同上
<游戏目录>/config/lang/<code>.properties         → 玩家/译者直接改这份，覆盖内置
```

`Lang` loads in the order "built-in default → built-in target → external default → external target", so an external file only needs the **few entries you actually want to change**, and the rest keep using the built-in values. This matters: someone who only wants `Success` to read `完成` should not be forced to copy 40 lines.

`Lang` 的加载顺序是「内置默认 → 内置目标 → 外部默认 → 外部目标」，所以外部文件只需要写**想改的那几条**，其余继续用内置值。这一点很重要：一个只想把 `Success` 改成 `完成` 的人，不该被迫复制 40 行。

```java
Lang.t("hud.status.success")            // → "Success" / "成功"
Lang.t("hud.header", "§aSuccess", "1.50", 3, "Z")   // Template + placeholders / 模板 + 占位符
```

- A missing key falls back to `en`, then returns the key name. / 键缺失回退 `en`，再缺返回键名。
- `Config.language` defaults to `en` (= the display since the port); both `Bridge.ensureConfig()` and `onConfigReload()` call `Lang.load(Config.language)`, so a change in the injector takes effect within 0.5 seconds without restarting the game. / `Config.language` 默认 `en`（= 移植以来的显示），`Bridge.ensureConfig()` 与 `onConfigReload()` 都会 `Lang.load(Config.language)`，所以注入器里改完 0.5 秒生效，不用重启游戏。
- **The whitelist in `agent/build.gradle` must include `lang/**`**: that fat jar only `include "com/bridger/**"` and ASM, and without this line the language pack never enters the jar at all, so every `t()` falls back to the key name at runtime. / **`agent/build.gradle` 的白名单必须含 `lang/**`**：那个 fat jar 只 `include "com/bridger/**"` 和 ASM，漏了这条语言包根本不会进 jar，运行时 `t()` 全部回退成键名。

### 16.3 Language Must Not Affect Detection / 16.3 语言不能影响判定

After this step a hidden coupling surfaced: `Tracker` decided "is the first jump flat" with `entries.get(0).getLabel().endsWith("Flat")`. Fine in English, but after switching to Chinese (`1.05 平`) the opening Late never triggered again.

做完这一步发现一个藏着的耦合：`Tracker` 判断「第一跳是不是平」用的是 `entries.get(0).getLabel().endsWith("Flat")`。英文下没事，切成中文（`1.05 平`）之后 opening Late 就永远不触发了。

It now looks at **entry type**:

已改成看**条目类型**：

```java
!turnRecorded && !entries.isEmpty()
   && entries.get(0).getType() == JumpEntry.EntryType.FLAT
```

The conclusion is written into a code comment: language only affects display, and no detection may depend on label text. When adding copy in the future, first think through "will this string ever be compared".

结论写在代码注释里：语言只影响显示，任何判定都不能依赖标签文字。以后再加文案时，先把「这个字符串会不会被拿去比较」想一遍。

---

## 17. Window Corners (v1.0.6, simplified in v1.0.8) / 17. 窗口圆角（v1.0.6，v1.0.8 简化）

A frameless window gets no rounding from the compositor on Win10, so the corner is drawn in the
renderer: `.shell` carries `border-radius: var(--r-window)` (12px) and `overflow: hidden`, and the
surface runs all the way to the edge.

无边框窗口在 Win10 上拿不到合成器给的圆角，所以由渲染层画：`.shell` 上写
`border-radius: var(--r-window)`（12px）加 `overflow: hidden`，表面直接铺到边。

**What was tried first, and why it was wrong.** v1.0.6 drew the corner as *two* concentric arcs:
a ring of 8px padding in the frame colour, with the surface inset inside it, and
`--r-shell = --r-shell-inner + --shell-pad` (26 = 18 + 8). The geometry is correct - two arcs only
share a centre when the outer radius exceeds the inner one by the gap - but the result reads as a
border around the content, which is not what the reference app does. A mathematically right answer
to the wrong question.

**先做的版本，以及它错在哪。** v1.0.6 把角画成*两条*同心弧：8px 的内边距填边框色，表面缩在
里侧，`--r-shell = --r-shell-inner + --shell-pad`（26 = 18 + 8）。几何上没错 —— 两条弧要共用
圆心，外半径必须比内半径大出一个缝隙 —— 但成品看起来就是内容外面多了一圈边框，和参考的样子
不是一回事。数学对了，问题是问错了。

The single arc has one consequence worth knowing: the window itself is still a rectangle, so the
area outside the rounded corner shows the `BrowserWindow` background. That colour is read from
`--shell-frame-light` / `--shell-frame-dark` (see below), which is why those two variables still
exist even though nothing in the stylesheet paints with them.

单层圆角有一个必须知道的后果：窗口本身仍是矩形，圆角外面露出来的是 `BrowserWindow` 的底色。
这个颜色从 `--shell-frame-light` / `--shell-frame-dark` 读（见下），所以这两个变量还在 ——
尽管样式表里已经没有任何地方拿它们上色。

**One source for the colour.** `main.js` parses those two declarations at startup and hands the
result to `BrowserWindow`, so the corner and the window colour cannot drift apart. The parse is
lazy and falls back to the previous hard-coded pair if a variable is renamed; it logs
`[theme] frame colours: ...` so a mismatch shows up in the log rather than in the corners.

**颜色只有一个来源。** `main.js` 启动时解析这两条声明交给 `BrowserWindow`，圆角与窗口底色就
不会各走各的。解析是惰性的，变量被改名时回退到原来那对硬编码值，并打一行
`[theme] frame colours: ...` —— 不一致先在日志里看见，而不是先在角上看见。

If the corner ever needs to be *really* transparent rather than "same colour as the window", the
window has to be created with `transparent: true` and a `#00000000` background. That was not done
here: it costs the system shadow and adds a compositing pass, and at a 4% colour difference the
corners are already indistinguishable.

如果哪天要求角上*真透明*而不是「颜色和窗口一样」，窗口得用 `transparent: true` 加
`#00000000` 底色创建。这里没这么做：会丢掉系统阴影、多一层合成，而 4% 的色差下四角本来也
看不出区别。

### The accent-coloured border / 强调色边框

Windows 11 paints a 1px border around every window. When "Show accent colour on title bars and
window borders" is on, that border is tinted with the accent colour - on a dark window it reads as
a pale blue outline hugging the rounded corner. It is drawn by DWM, outside the web contents, so
`Page.captureScreenshot` never sees it and CSS cannot paint over it.

Win11 会给每个窗口画一圈 1px 边框。系统开了「在标题栏和窗口边框上显示强调色」时，这圈边框会被
染成强调色 —— 深色窗口上就是紧贴圆角的一圈淡蓝。它由 DWM 画在网页内容之外，
`Page.captureScreenshot` 拍不到，CSS 也盖不住。

The per-window switch is `accentColor: false` in the `BrowserWindow` options (`@platform win32`).
It only opts this window out; the system setting and every other window keep whatever they had.
Do not reach for `roundedCorners: false` or a transparent window to get rid of it - both throw
away the system shadow as well.

按窗口的开关是 `BrowserWindow` 选项里的 `accentColor: false`（`@platform win32`）。它只让这个
窗口退出该行为，系统设置和其他窗口都不受影响。不要为了去这圈颜色去改 `roundedCorners: false`
或用透明窗口 —— 那两个会连系统阴影一起丢掉。

Verified by capturing the desktop rather than the page: sampling the outermost window pixels gave a
peak blue-minus-red of 34 before the change and none at all after it.

验证要截桌面而不是截页面：改前窗口最外沿像素的「蓝减红」峰值为 34，改后为 0。

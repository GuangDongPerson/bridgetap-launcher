# Upstream Sync Log (BridgeTap upstream mod → external injector) / 上游同步记录（BridgeTap 原版模组 → 外置注入器）

Upstream repository: **https://github.com/sgggnnn/bridgetap** (Fabric / Forge 1.8.9 original mod)

上游仓库：**https://github.com/sgggnnn/bridgetap**（Fabric / Forge 1.8.9 原版模组）

Injector implementation: `BridgeTap-Compat` (agent + core + hud)

注入器实现：`BridgeTap-Compat`（agent + core + hud）

> Trigger word: sending **「上游更新同步」** on its own re-runs this whole procedure, with no further confirmation needed. / 触发词：单独发送 **「上游更新同步」** 即按本文流程完整重跑一遍，不需要再确认。

---

## Procedure / 流程

```bash
# 1. locate and pull the latest upstream source / 定位并拉取上游最新源码
cd "%TEMP%" && curl -sSL -o up.tar.gz https://codeload.github.com/sgggnnn/bridgetap/tar.gz/refs/heads/main
tar -xzf up.tar.gz

# 2. fetch "the baseline of the last sync" for comparison (the baseline commit is recorded at the end of this document) / 取"上次同步的基线"对比（基线 commit 记在本文末尾）
curl -sSL -o prev.tar.gz https://codeload.github.com/sgggnnn/bridgetap/tar.gz/<BASE_COMMIT>
tar -xzf prev.tar.gz

# 3. look only at the files that really affect detection logic / 只看真正影响检测逻辑的文件
diff -u <prev>/src/main/java/com/bridger/momentum/BridgeTracker.java \
        <new>/src/main/java/com/bridger/momentum/BridgeTracker.java
# same for BridgeDetection / BridgeConfig / JumpEntry / PlacementEvidence / EntryFormatter / 同法对比 BridgeDetection / BridgeConfig / JumpEntry / PlacementEvidence / EntryFormatter
# The GUI and replay system (BridgeScreen / Replay* / *Canvas / OverlayPlacement) do not apply to the injector, skip them / GUI 与回放系统（BridgeScreen / Replay* / *Canvas / OverlayPlacement）不适用于注入器，跳过

# 4. change core/ according to the diff, keeping the injector's existing structure and call style / 按差异改 core/，保持注入器既有结构与调用方式
./gradlew :core:compileJava
bash tests/run.sh          # offline acceptance, must be all green / 离线验收，必须全绿

# 5. build the package / 出包
export BT_JDK=<jdk-21> && bash native/pack.sh
cp dist/BridgeTap.exe ../BridgeTap-Launcher/vendor/launcher/
cd ../BridgeTap-Launcher && npm run dist
```

**Rule of thumb**: only changes that "decide HUD text, labels, timing or detection" need porting. Changes involving GUI, replay recording, the file library or settings-layout work have no counterpart structure on the injector side, so they are all recorded as "not applicable" with the reason spelled out below.

**判断标准**：只有"决定 HUD 文字、标签、计时、判定"的改动才需要移植。带 GUI、回放录制、文件库、
设置界面布局的改动，注入器这侧没有对应结构，一律记为"不适用"并在下方写清原因。

---

## 2026-11-11 (second pass) · 1.5.0 display layer and detection semantics / 2026-11-11（第二次）· 1.5.0 的显示层与判定语义

The first sync only carried over "the detection that can be computed" and missed the display layer plus two semantic changes. This pass fills the gap:

第一次同步只搬了"能算出来的判定"，漏了显示层和两条语义变化。这一次补齐：

### Ported / 已移植

| # | Upstream change / 上游改动 | Port location / 移植位置 | Notes / 说明 |
|---|---|---|---|
| A | Text colors are configurable (10 slots: label / coords / positive delta / negative delta / Tick / Gain / No Gain / title / background / border) / 文本颜色可配置（10 个槽：标签/坐标/正增量/负增量/Tick/Gain/No Gain/标题/背景/边框） | `Config.COLOR_NAMES` / `Config.colors` / `Config.color(i)`; `JumpEntry`, `HudModel` all read the config; `/bt color <slot> <0-9a-f>` | The UI exposes 0-7 (text); 8/9 are panel fill and border, and the injector's panel is a hand-drawn solid block that is not wired up yet, so they do not appear in the UI / 界面里暴露 0-7（文字）；8/9 是面板填充与边框，注入器的面板是自绘实心块，暂未接入，故界面上不出现 |
| B | The first flat's gain marker changes from `+No Gain` to `+.n/a` / 首个 flat 的收益标记由 `+No Gain` 改为 `+.n/a` | `JumpEntry` FLAT branch / `JumpEntry` FLAT 分支 | 1.4.x's `+No Gain` meant "the coordinate did not change"; 1.5.0 turns the delta slot into `+.n/a` because there is no previous column to compare against in the first place / 1.4.x 的 `+No Gain` 意思是"坐标没变化"；1.5.0 把 delta 槽改成 `+.n/a`，因为它本来就没有上一列可比 |
| C | `[Gain]`/`[No Gain]` semantics change to A/D pressed together (cancelling out) = Gain / `[Gain]`/`[No Gain]` 语义改为 A/D 同时按下（抵消）＝ Gain | New `core/.../JumpSignals.java`; `Tracker.currentGain()`; the trailing marker of the `JumpEntry` row / 新增 `core/.../JumpSignals.java`；`Tracker.currentGain()`；`JumpEntry` 行尾尾标 | It is no longer "did the coordinate grow" but "did you jump straight out". With no W pressed or with S pressed there is no verdict / 不再是"坐标涨没涨"，而是"是不是直着跳出去"。没按 W 或按 S 时无判定 |
| D | Two new configs: `showGainType` / `useNoGayLabel` / 新增 `showGainType` / `useNoGayLabel` 两个配置 | `Config` (old `gainLabel` still read for compatibility); `/bt gaintype on\|off` | In the injector `showGainType` defaults to **true** (upstream defaults to false) so gain information does not disappear right after an upgrade / 注入器里 `showGainType` 默认 **true**（上游默认 false），避免升级后收益信息直接消失 |
| E | Head hitter detection / Head hitter 判定 | `Tracker.onTick` + `JumpEntry.EntryType.HEAD_HITTER` | See "Adaptation differences" below / 见下方"适配差异" |

### Adaptation differences (Head hitter) / 适配差异（Head hitter）

Upstream: `!onGround && collidedVertically && motionY > 0`, reading the entity's two fields directly.

上游：`!onGround && collidedVertically && motionY > 0`，直接读实体的两个字段。

Injector: those two fields are not in the mapping table, so it reads a **height curve** instead — in the air, rising with `dy > 0.05`, then on the next tick `dy <= 0.02` (the rise was stopped), happening within 6 ticks of the take-off, and less than 1.05 blocks above the take-off height. A normal jump peaks at tick 6–7 at about 1.25 blocks, which both conditions exclude, so a normal peak is never mistaken for a head hit.

注入器：这两个字段不在映射表里，改成读**高度曲线**——空中、上升 `dy > 0.05` 之后下一 tick
`dy <= 0.02`（上升被顶停）、发生在起跳后 6 tick 内、且离起跳高度不到 1.05 格。正常跳的顶点在
第 6~7 tick、高度约 1.25 格，两个条件都排除掉了，所以不会把顶点误判成顶头。

**Added in 1.3.8 (important)**: a stalled curve does **not** equal a head hit. A telly jump hands the same Y to two consecutive ticks because of duplicate or delayed position samples, and `dy` goes to 0 just the same — 1.1 Flat followed by a speed telly stepped right into this, inventing an extra `» Head hitter: .624` row. Upstream has `collidedVertically` as a backstop, we did not, which amounts to treating a sufficient condition as a necessary one.

**1.3.8 补的（重要）**：曲线停住**不等于**顶头。telly 跳会由于位置采样重复/延迟把同一个 Y
连给两 tick，`dy` 一样会变 0——1.1 Flat 后接一个 speed telly 就踩过这个坑，凭空多一行
`» Head hitter: .624`。上游有 `collidedVertically` 兜底，我们原来没有，等于把一个充分条件当成
了必要条件。

Now the world-side half is added: `WorldProbe.ceilingAbove(x, y, z)` takes the layer `floor(feetY + 1.8 + 0.05)`, the 1~2 columns covered by the 0.6-wide collision box, and goes `getBlockState → getBlock → id` one by one; only id != 0 counts as a ceiling (the 0.05 is float headroom: when you are pressed against the ceiling, `feetY + 1.8` lands exactly on the block's bottom face, and without that headroom it floors into the air cell the head occupies). `Tracker.CeilingProbe` is three-valued:

现在补上世界侧的一半：`WorldProbe.ceilingAbove(x, y, z)` 取 `floor(feetY + 1.8 + 0.05)` 这一层、
按 0.6 宽的碰撞箱覆盖的 1~2 列，逐个 `getBlockState → getBlock → id`，id != 0 才算有天花板
（0.05 是给浮点留的余量：贴住天花板时 `feetY + 1.8` 正好落在方块底面，少了这点余量会 floor 到
头部所在的那格空气里）。`Tracker.CeilingProbe` 是三值：

| Return / 返回 | Meaning / 含义 | Handling / 处理 |
|---|---|---|
| `TRUE` | There really is a block above the head / 头顶确实有方块 | Record Head hitter (same semantics as upstream) / 记 Head hitter（与上游语义一致） |
| `FALSE` | The space above the head is empty / 头顶是空的 | Drop it, and do not consume `headHitTick`, so a later real head hit in the same jump can still be recorded / 丢弃，且不消耗 `headHitTick`，同一次起跳后续真撞顶仍能记到 |
| `null` | The world cannot be read (no world yet / the block chain was not resolved) / 世界读不到（还没世界 / 方块链没解析出来） | Keep the old lenient behaviour, log one line only, and do not let the feature silently disappear / 保留旧的宽松行为，只打一行日志，不让功能静默消失 |

**1.4.0 rewrote it (current implementation)**: the "stalled curve + block above the head" scheme above failed at both ends on a real machine — first telly jumps faked data out of it, and after adding the block criterion it produced nothing at all (block reads miss on a real machine). It was changed to measure **airtime**, which is a physics constant of the map itself:

**1.4.0 推翻重做（现行实现）**：上面这套「曲线停住 + 头顶有方块」在实机上两头都出过问题——
先是被 telly 骗出假数据，加了方块判据之后又变成一条都不出（方块读取在实机上会漏）。改成量
**空中时长**，这是地图自己的物理常数：

| Case / 情形 | Air ticks (y from take-off back to 0) / 空中 tick（y 从起跳到落回 0） |
|---|---|
| Normal jump on flat ground / 平地正常跳 | **12** (y reaches 0.420 on tick 1 and is back to 0 on tick 12 / y 在第 1 tick 到 0.420，第 12 tick 回到 0) |
| Block above the head / 头顶有方块 | **3** (stopped on tick 1, lands on tick 3 / 第 1 tick 就被顶停，第 3 tick 落地) |

Criteria (`Tracker.considerHeadHit`):

判据（`Tracker.considerHeadHit`）：

1. Settle on the landing tick: `air = landing tick - first air tick`, with `air <= HEAD_HIT_MAX_AIR (5)`; / 落地那一 tick 结算，`air = 落地 tick - 首个空中 tick`，`air <= HEAD_HIT_MAX_AIR (5)`；
2. The jump really rose: `peak - takeoff >= 0.15` (the take-off height is the **last sample still standing on the ground**; the 0.42 rise happens after that, and using the first airborne frame would eat the whole ascent); / 这一跳确实升起来过：`peak - takeoff >= 0.15`（起跳高度取**最后一个站在地上的采样**，0.42 的抬升发生在那之后，用第一帧空中数据会把整个上升吃掉）；
3. It came back to the same layer: `|landing - takeoff| <= 0.2` (jumping up a step also shortens the airtime, but that is a normal jump). / 落回同一层：`|landing - takeoff| <= 0.2`（跳上台阶也会让空中变短，但那是正常跳）。

Telly jumps cannot fool it: they repeat the same sample, `onGround` stays false, and not a single air tick is spared. `CeilingProbe` is demoted to diagnostics — after a successful record it only writes one `ceiling=yes/no/unknown` line to the log and no longer takes part in the decision. Record position: **after this jump's label** (you jump first and only know on landing whether you hit the head, so it reads `» 1.1 Flat` / `» Jump #1` / `» Head hitter: .245` / `» Plates`). 1.4.0 initially inserted it in front, which looked like "the head hit happened first"; that order is wrong on a real machine.

telly 骗不了它：它重复的是同一个样本，`onGround` 一直为 false，空中 tick 一个都少不了。
`CeilingProbe` 降级成诊断——只在记录成功后往日志里写一行 `ceiling=yes/no/unknown`，不再参与判定。
记录位置：**在这一跳的标签之后**（先跳、落地才知道顶没顶，所以读起来是
`» 1.1 Flat` / `» Jump #1` / `» Head hitter: .245` / `» Plates`）。1.4.0 一开始插在前面，
看起来像“顶头先发生”，实机上顺序不对。

Head hits at the end-of-bridge sprint need special care, and there are **two layers** of pits:

终点冲刺的顶头要单独照顾，而且是**两层**坑：

1. The plate branch sits first and wraps up the run the moment it fires (`platesRecorded = true / status = SUCCESS`), so the landing branch after it is never reached. The plate branch therefore has to call `considerHeadHit` once before wrapping up. / 踩板分支在最前面，它一把这一局收尾（`platesRecorded = true / status = SUCCESS`），后面的落地分支就再也走不到。所以踩板分支要在收尾前先调一次 `considerHeadHit`。
2. Plate presses **almost always happen on the last frame before landing**: the pressure plate's trigger box is `(plateY, plateY + 0.25)` (the `0.125` inset plus `0.25` height in `BlockBasePressurePlate.getSensitiveAABB`), and the player is still in the air with the feet just entering the trigger zone when it already counts as pressed. So the criteria must not require `onGround`, and the height tolerance has to be relaxed to `PLATE_LEVEL_TOLERANCE = 0.35` (the landing criterion uses 0.2). / 踩板**基本都发生在落地前的最后一帧**：压力板触发盒是 `(plateY, plateY + 0.25)`（`BlockBasePressurePlate.getSensitiveAABB` 里的 `0.125` 内缩 + `0.25` 高），人还在空中、脚刚伸进触发区就已经算踩下了。所以判据不能要求 `onGround`，高度容差也要放宽到 `PLATE_LEVEL_TOLERANCE = 0.35`（落地判据那边是 0.2）。

Doing only item 1 is not enough — 1.4.1 added the settlement but required `onGround`, and on a real machine it still produced nothing.

只做第 1 条不够——1.4.1 加了结算但要求 `onGround`，实机上照样一条不出。

### Localisation (1.5.5) / 多语言（1.5.5）

All strings of both the injector and the mod moved into language packs, with two separate language settings:

注入器与模组的文案全部搬到语言包，两个语言设置分开：

**Injector** (`BridgeTap-Launcher/i18n/*.json`): `index.json` lists the languages, `t('key')` + the `data-i18n` attribute, `appLanguage` is stored in `settings.json`. `MOD_FIELDS` keeps only `labelKey`/`hintKey` and no longer ships Chinese text. `scripts/check-i18n.js` verifies that both packs share the same keys and that every key referenced by the code exists.

**注入器**（`BridgeTap-Launcher/i18n/*.json`）：`index.json` 列语言，`t('key')` + `data-i18n` 属性，
`appLanguage` 存 `settings.json`。`MOD_FIELDS` 只留 `labelKey`/`hintKey`，不再下发中文。
`scripts/check-i18n.js` 校验两套包键一致 + 代码引用的键都存在。

**Mod** (`core/.../Lang`): `lang/en.properties` and `lang/zh_CN.properties` are packed into agent.jar as the fallback, `config/lang/<code>.properties` overrides them (you only need to write the few entries you want to change). `Config.language` defaults to `en`; both `Bridge.ensureConfig`/`onConfigReload` call `Lang.load(...)`, and a change takes effect 0.5 s later.

**模组**（`core/.../Lang`）：`lang/en.properties` 与 `lang/zh_CN.properties` 打进 agent.jar 兜底，
`config/lang/<code>.properties` 覆盖（只需写想改的几条）。`Config.language` 默认 `en`，
`Bridge.ensureConfig`/`onConfigReload` 都会 `Lang.load(...)`，改完 0.5 秒生效。

- **`include "lang/**"` in `agent/build.gradle` must not be missed**: the fat jar's include allowlist only holds `com/bridger/**` and ASM, and without that line the language pack never enters the jar, so `t()` falls back to key names across the board (this pit was hit on the spot). / **`agent/build.gradle` 的 `include "lang/**"` 不能漏**：fat jar 的 include 白名单只放了 `com/bridger/**` 和 ASM，不加这条语言包不会进 jar，`t()` 全部回退成键名（这个坑当场踩到）。
- **One coupling fixed in passing**: `Tracker` used `entries.get(0).getLabel().endsWith("Flat")` to decide whether the first jump was flat, and after a language change opening Late broke outright. It now looks at `EntryType.FLAT` — language only affects display, detection must never depend on label text. / **顺手修掉一个耦合**：`Tracker` 用 `entries.get(0).getLabel().endsWith("Flat")` 判断首跳是不是平，换语言之后 opening Late 直接失效。改成看 `EntryType.FLAT`——语言只影响显示，判定不许依赖标签文字。
- The classpath of `tests/run.sh` must include `core/build/resources/main`, otherwise Lang cannot read its resources and the tests are all bare key names (14 assertions fail together). / `tests/run.sh` 的 classpath 要带 `core/build/resources/main`，否则 Lang 读不到资源，测试里全是裸键名（14 条断言一起挂）。

### Custom Gain / No Gain wording (1.5.4) / Gain / No Gain 措辞自定义（1.5.4）

The `useNoGayLabel` switch is gone, replaced by two freely editable words: `gainText` / `noGainText`, where empty means the default `Gain` / `No Gain`. Detection and color slots are untouched; only the printed words change.

删掉 `useNoGayLabel` 开关，换成两个可自由填写的词：`gainText` / `noGainText`，空 = 默认
`Gain` / `No Gain`。判定与颜色槽都不动，只改打印出来的字。

- `Config`: two fields + `gainWord()` / `noGainWord()` (trim, strip square brackets, cap at 24 chars) + load/save / `Config`：两个字段 + `gainWord()` / `noGainWord()`（trim、去方括号、限 24 字）+ load/save
- `load` migration: `useNoGayLabel=true` or the older `gainLabel=No Gay` → `noGainText=No Gay`; when a `noGainText` key already exists it wins / `load` 迁移：`useNoGayLabel=true` 或更老的 `gainLabel=No Gay` → `noGainText=No Gay`；已有 `noGainText` 键时以它为准
- `JumpEntry`: `gainType` is still `Gain`/`No Gain`, mapped to the custom word on display, colors still come from slots 5/6 / `JumpEntry`：`gainType` 仍是 `Gain`/`No Gain`，显示时映射成自定义词，颜色仍按 5/6 槽
- Desktop: `MOD_FIELDS` becomes two `type: 'text'` boxes (`placeholder` is the default value), `coerce` gains a text branch, `settings-view` gains `textControl` / 桌面端：`MOD_FIELDS` 换成 `type: 'text'` 的两个框（`placeholder` 就是默认值），`coerce` 加 text 分支，`settings-view` 加 `textControl`
- Tests [17]/[17b]/[17c]/[17d]: custom word, empty falls back, sanitising and truncation, two-level legacy key migration; `scripts/test-settings-write.js` covers synchronous writes to both files and clearing / 测试 [17]/[17b]/[17c]/[17d]：自定义词、留空回退、清洗与截断、两级旧键迁移；`scripts/test-settings-write.js` 覆盖两份文件同步写入与清空
- Also, the settings-page hints that pointed at deleted commands (`/bt display|scale|pos|background|gaintype|offset|plateoffset|turn`) were rewritten as descriptions, so nobody goes typing commands that no longer exist / 顺带把设置页 hint 里指向已删命令（`/bt display|scale|pos|background|gaintype|offset|plateoffset|turn`）的文字改成说明，避免让人去敲不存在的命令

### Tab yield turned into a switch (1.5.3) / Tab 让位做成开关（1.5.3）

The yield logic in `Bridge.drawFrame` had always been hard-coded (do not draw while the player-list key is held). It is now controlled by `Config.yieldToTabList`, defaulting to `true` to keep the old behaviour; the external injector's mod settings have a matching switch "hide the panel while Tab is held".

`Bridge.drawFrame` 里让位逻辑一直是硬编码的（按住玩家列表键就不画）。现在由
`Config.yieldToTabList` 控制，默认 `true` 保持原行为，外置注入器的模组设置里有对应开关
「按住 Tab 时隐藏面板」。

- `Config`: field + `load` (missing key defaults to true, old config files behave unchanged) + `save` / `Config`：字段 + `load`（缺键默认 true，老配置文件行为不变）+ `save`
- `Bridge.drawFrame`: `Config.yieldToTabList && binding.isTabListKeyDown()` / `Bridge.drawFrame`：`Config.yieldToTabList && binding.isTabListKeyDown()`
- Desktop `MOD_FIELDS` gains `{ key: 'yieldToTabList', type: 'switch' }`, placed after `showReadyNotice` / 桌面端 `MOD_FIELDS` 里加 `{ key: 'yieldToTabList', type: 'switch' }`，落在 `showReadyNotice` 之后
- Test [16]: load reads false / save writes true back / no key name missed; `scripts/test-settings-write.js` also gained "missing key defaults to true, writing false persists in both files" / 测试 [16]：load 读到 false / save 写回 true / 键名不漏；`scripts/test-settings-write.js` 里也加了「缺键时默认 true、写入 false 两份都落盘」

### Success line compensates half an RTT (1.5.2) / 成功行补偿半个 RTT（1.5.2）

Bridge timing is measured client-side, while the server needs one one-way trip to acknowledge it, so the time in the chat `Success!` line now adds **half the latency** (rounded to the millisecond when it does not divide evenly). The `2 tick` send delay is unchanged.

搭路计时是客户端量的，服务器要过一个单程才认账，所以聊天里那条 `Success!` 的时间现在补上
**延迟的一半**（除不尽四舍五入到毫秒）。`2 tick` 的发送延迟保持不变。

- `Tracker.PingProbe` (agent side `WorldProbe.pingMillis()`): reads `NetworkPlayerInfo.getResponseTime()`, i.e. the number after each name in the tab list. It walks back three hops, and all three member names are obfuscated on BLC, so names are ignored entirely: / `Tracker.PingProbe`（agent 侧 `WorldProbe.pingMillis()`）：读 `NetworkPlayerInfo.getResponseTime()`，也就是 Tab 列表里每个名字后面那个数。往回走三跳，三跳的成员名在 BLC 上全是混淆的，所以一概不看名字：
  1. **Network handler** — on the player entity, find the object field that "owns a player-list Map"; / **网络处理器** —— 在 player 实体上找「自己拥有一个玩家列表 Map」的对象字段；
  2. **Player list** — that `Map<UUID, NetworkPlayerInfo>`; / **玩家列表** —— 那个 `Map<UUID, NetworkPlayerInfo>`；
  3. **Entry** — recognised by "has a `com.mojang.authlib.GameProfile` field + an int whose value is in 0..20000"; the client cannot obfuscate authlib class names, which makes this the hardest criterion. / **表项** —— 认「有 `com.mojang.authlib.GameProfile` 字段 + 有一个值在 0..20000 的 int」，authlib 的类名客户端混淆不到，这是最硬的判据。
   Own entry looked up by the key from `Entity.getUniqueID()` (the only no-arg method returning `UUID`); if it is not found and the list has exactly one element, it falls back to "just take that one". / 自己的表项用 `Entity.getUniqueID()`（无参、返回 `UUID` 的唯一方法）取 key 查；查不到且列表只有 一个元素时才退到「就拿它」。
- Resolution failures retry every 3 seconds (the tick callback is already running before the connection is up); a new player entity invalidates and re-resolves. / 解析失败每 3 秒重试（连接建立前 tick 回调已经在跑了）；换 player 实体即作废重解。
- Unreadable or single-player (ping 0) → `pingHalf()` returns 0 and the line prints the measured value, never inventing extra time. / 读不到或单人（ping 0）→ `pingHalf()` 返回 0，行按实测值打印，不会凭空加时间。
- The line is only assembled inside `flushSuccessMessage` (not when the plate is pressed), so the latency used is the value "at the moment it is really sent". / 行是在 `flushSuccessMessage` 里才拼的（不是踩板时），这样延迟取的是「真正发出那一刻」的值。

### After chat off the Success line stops too (1.5.1) / chat off 之后连 Success 行也停（1.5.1）

`Success! Time: 2.05s | Plates-1t: … | Plates: …` is computed ahead of time by `recordPlateEntries` and only sent two ticks later by `flushSuccessMessage`, which never looked at `Config.chatOutput` — after `/bt chat off` it kept sending. `pumpChatOutput` (the "send results when a run ends" path in Bridge) did check the switch, so this was the only leak.

`Success! Time: 2.05s | Plates-1t: … | Plates: …` 这条是 `recordPlateEntries` 提前算好的，
两个 tick 后才由 `flushSuccessMessage` 发出去，而那里没看 `Config.chatOutput`——`/bt chat off`
之后它照发。另外 `pumpChatOutput`（Bridge 里那条「一局结束发结果」）本来就是看开关的，所以只漏了这一条。

Fix: `flushSuccessMessage` checks the switch once before sending (so "turning it off within those 2 ticks between finishing and sending" works too), and `recordPlateEntries` simply does not generate the line when it is off.

修法：`flushSuccessMessage` 发送前判一次开关（这样「跑完到发出之间那 2 tick 内关掉」也生效），
`recordPlateEntries` 里关着就干脆不生成。

### Chat command diet (1.5.0) / 聊天命令瘦身（1.5.0）

Only `toggle` / `reset` / `axis` / `chat` remain in game; all other commands (pos, display, offset, plateoffset, turn, scale, gain, gaintype, color, background, status, save, reload) are deleted — they are all clickable items in the injector's settings page, and the `color` family has been replaced by the color swatch picker.

游戏里只保留 `toggle` / `reset` / `axis` / `chat`，其余命令（pos、display、offset、plateoffset、
turn、scale、gain、gaintype、color、background、status、save、reload）全部删除——它们在注入器的
设置页里都是可点的项，而且 `color` 那类已经被色块选择器取代了。

- `chat` becomes a toggle: `/bt chat` with no argument flips between on/off, `on|off` is still accepted. / `chat` 改成切换式：`/bt chat` 不开参数就在 on/off 之间翻，`on|off` 依旧接受。
- Command names are case-insensitive and whole-word only: `/BT`, `/BridgeTap`, `/bt` all work, `/btw xxx` is left alone. / 命令名大小写不敏感，且只认整词：`/BT`、`/BridgeTap`、`/bt` 都行，`/btw xxx` 不动。

### Both Plates rows take the label color (1.4.9) / Plates 两行纳入标签色（1.4.9）

`Plates` used to hard-code `§6` gold (`Plates-1t` used the label color for the label but the coordinate color for the value), which clashed badly with a customized "text color · label". Both rows now route the arrow, the text and the value through `color.0`:

`Plates` 原来写死 `§6` 金色（`Plates-1t` 是标签色但数值用的是坐标色），跟自定义的「文本颜色 · 标签」
撞在一起很跳。现在两行的箭头、文字、数值全部走 `color.0`：

```java
case PLATES:
   sb.append(Config.color(0)).append("\u00bb ").append(Config.color(0)).append("Plates: ")
      .append(Config.color(0)).append(decimalText());
```

Note that this uses `decimalText()` rather than `dec` — `dec` carries its own `color(1)` (the coordinate color), and concatenating it directly would tint the value back.

注意这里用的是 `decimalText()` 而不是 `dec`——`dec` 自带 `color(1)`（坐标色），直接拼会把数值染回去。

### World switch no longer loses data (1.4.8) / 切世界不再丢数据（1.4.8）

`onWorldChange()` used to call `reset()` outright, wiping the entries along with it. That is a bad deal for the player: the time and coordinates on the HUD are exactly what you line up against the server-side replay, and switching worlds threw them away.

`onWorldChange()` 原来直接走 `reset()`，条目一起清空。对玩家来说这很亏：HUD 上的时间和坐标正是
拿来跟服务器回放对轴的，换个世界就没了。

Now only the runtime state "belonging to the old world" is swapped out:

现在只换掉「属于旧世界」的运行期状态：

| Kept / 保留 | Cleared / 清掉 |
|---|---|
| `entries`, `status`, `currentBridgeTime` | `restartCooldown`, `pendingJumps`, `pendingPlateSample` |
| | the position ring, `PlacementEvidence`, `airStart*` / `groundY` / 位置环、`PlacementEvidence`、`airStart*` / `groundY` |

A run that was in progress when the world switched is simply marked `FAILED` (the data stays); the next block placement's `startRun()` replaces it. The root cause of the hang (the old world's cooldown tick) still gets cleared, and `/bt reset` remains unnecessary.

没跑完就切世界的那一局直接记 `FAILED`（数据留着）；下次放方块时 `startRun()` 才把它替换掉。
卡死的根因（旧世界的冷却 tick）照样被清，`/bt reset` 依旧不需要。

### World time cannot be the clock + BLC key detection (1.4.7) / 世界时间不能当计时基准 + BLC 的键位识别（1.4.7）

**Problem one: the Gain label does not show on BLC.** agent.log says `movement keys: no KeyBinding fields found in GameSettings` — the old resolution required the field's type name to end with `KeyBinding`, but BLC obfuscates the GameSettings / KeyBinding class names together, so nothing matched at all. It changed to **duck typing**: a field whose value's instance has an int field exactly equal to 17/30/31/32 (the default W/A/S/D key codes) plus a boolean (pressed) counts as one direction key; all four key codes must be present on the same class. GameSettings is no longer found by name either — it scans the object fields of `mc` and whichever satisfies the conditions is it. After a failure it retries every 3 seconds (before entering a world it may not be constructed yet).

**问题一：BLC 上 Gain 标签不显示。** agent.log 里写着
`movement keys: no KeyBinding fields found in GameSettings`——原来的解析要求字段类型名以
`KeyBinding` 结尾，而 BLC 把 GameSettings / KeyBinding 的类名一起混淆了，一条都匹配不到。
改成**鸭子类型**：某个字段的值，只要它的实例里有 int 字段正好是 17/30/31/32（W/A/S/D 默认键码）、
又有一个 boolean（pressed），就算一个方向键；四个键码必须凑齐在同一个类上。GameSettings 也不再按
名字找——直接扫 mc 的对象字段，谁满足条件就是谁。失败后每 3 秒重试一次（进世界之前可能还没构造好）。

**Problem two: failed records get wiped within a second / the timer suddenly restarts from 0.** Both are the same cause: `getTotalWorldTime` gets rolled back by the server, **1596 times** in a single session (by at most 1~5 ticks each). The old logic treated "the tick went backwards" as a world switch → `onWorldChange()` → `reset()`, so a run that had just finished was discarded; and even without taking the reset branch, `currentTick = tick` dragged `startTick` back and the timer recomputed from 0.

**问题二：失败记录被秒清空 / 计时器突然从 0 开始。** 这两件事同一个原因：
`getTotalWorldTime` 会被服务器往回拨，一次会话里出现了 **1596 次**（回拨 1~5 tick 最多）。
旧逻辑把「tick 倒退」当成换世界 → `onWorldChange()` → `reset()`，于是刚打完的一局被丢掉；
就算没走 reset 分支，`currentTick = tick` 也会把 `startTick` 拉回来，计时器就从 0 重算。

Now:

现在：

- `currentTick` becomes **its own monotonic counter**: it advances by however much world time advanced (at most `MAX_TICK_STEP = 20` per step, so an alt-tab of half an hour does not stretch a run), and on a **rollback it only adds 1**, never going back; / `currentTick` 改成**自己的单调计数器**：世界时间前进多少就加多少（单次最多 `MAX_TICK_STEP = 20`，alt-tab 半小时不会把一局拉长），**倒退时只 +1**，永不回头；
- world switches are recognised only through `WorldProbe.worldIdentity()` (a World object swap); however absurd the tick number gets, it never zeroes anything; / 世界切换只认 `WorldProbe.worldIdentity()`（World 对象 swap），tick 数字再离谱也不清零；
- the rollback pass-through in `RunStartCooldown` is kept as a second safety net. / `RunStartCooldown` 的倒退放行逻辑保留，双保险。

### HUD header line uses the title color throughout (1.4.6) / HUD 首行统一用标题色（1.4.6）

The three status words `Success` / `Failed` / `Bridging` used to be hard-coded `§a` / `§c` / `§e`, while the rest of the line (time, jump count, axis) used `color.7` (the title color). Change the title color and one line ends up with two clashing tones.

`Success` / `Failed` / `Bridging` 三个状态词原来是写死的 `§a` / `§c` / `§e`，后面那半句
（时间、跳数、轴）用的却是 `color.7`（标题色）。只要把标题改成别的颜色，一行里两种色调就很割裂。

Now the whole line goes through `Config.color(7)`, uniformly controlled by "text color · title":

现在整行都走 `Config.color(7)`，统一由「文本颜色 · 标题」控制：

```java
private static String headerLine(String statusWord, double time, int jumps, String axis) {
   String tone = Config.color(7);
   return String.format(Locale.US, "%s%s %s%.2fs %d jumps, axis: %s",
      tone, statusWord, tone, time, jumps, axis);
}
```

Note that the `Time: x.xxs` line under `DISPLAY_MODE=TIME` is unaffected — it never shows a status word and still picks its color by status.

注意 `DISPLAY_MODE=TIME` 那条 `Time: x.xxs` 不受影响——它本来就不显示状态词，仍是按状态取色。

### Plate sampling point moved back 1 tick (1.4.2) / 压力板采样点后移 1 tick（1.4.2）

A plate press is detected on the tick the player reaches it, but the coordinates at that moment still carry the fall. `Plates` / `Plates-1t` are now shifted back one tick as a whole: `Plates-1t` takes **the contact tick**, `Plates` takes **the tick after contact** (the position where the landing has settled).

踩板是在玩家到达那一 tick 检测到的，但那时坐标还带着下落过程。`Plates` / `Plates-1t` 现在整体
后移一 tick：`Plates-1t` 取**接触那一 tick**，`Plates` 取**接触后一 tick**（落地已站稳的那个位置）。

- Implementation: `pendingPlateTick = currentTick + Config.plateTickOffset + PLATE_JUDGE_DELAY_TICKS(1)`; the sample point lies in the future, so it goes through `pendingPlateSample` and is settled by `processPendingSamples` on the next tick. / 实现：`pendingPlateTick = currentTick + Config.plateTickOffset + PLATE_JUDGE_DELAY_TICKS(1)`；采样点落在未来，所以走 `pendingPlateSample`，下一 tick 由 `processPendingSamples` 结算。
- The settings page's "plate sample offset" is still a fine adjustment stacked on top of this; the default 0 means exactly this +1 behaviour. / 设置页的「压力板采样偏移」仍是在此基础上叠加的微调，默认 0 即表示这套 +1 行为。

### How Gain reads the keys / 增益（Gain）怎么读键

Upstream reads `movementInput.moveForward/moveStrafe`, and those two fields are likewise not in the mapping table. The injector instead finds the **KeyBinding objects** in `GameSettings`: they are all one class, recognised by **default key codes** (W=17 / A=30 / S=31 / D=32) for the four direction keys, then reads `pressed` (the only boolean on that class). This does not depend on the client's obfuscated names; if the player has rebound the direction keys, the gain label falls back to "not displayed" rather than showing something wrong.

上游读的是 `movementInput.moveForward/moveStrafe`，这两个字段同样不在映射表里。
注入器改成从 `GameSettings` 里找 **KeyBinding 对象**：它们全是一个类，靠**默认键码**
（W=17 / A=30 / S=31 / D=32）认出四个方向键，再读 `pressed`（该类唯一的 boolean）。这样不依赖
客户端的混淆名；玩家如果改过方向键绑定，收益标签退回"不显示"而不是给错。

### Still not ported / 仍未移植

| Upstream feature / 上游功能 | Reason / 原因 |
|---|---|
| HUD visual layout studio, replay system, replay filters/sorting, settings-screen re-layout / HUD 可视化布局台、回放系统、回放过滤器/排序、设置界面重排 | The injector has no GUI layer and no recording layer (same as the first sync) / 注入器没有 GUI 层与录制层（同第一次） |
| `color.8` (panel background) / `color.9` (border) / `color.8`（面板背景）/ `color.9`（边框） | The panel background is a solid block hand-drawn by GlHud; wiring color values in means adding parameters to GlHud, left for next time / 面板背景是 GlHud 自绘的实心块，接色值要给 GlHud 加参数，留待下次 |

---

## 2026-11-11 (first pass) · 1.4.5 → 1.5.0 detection logic / 2026-11-11（第一次）· 1.4.5 → 1.5.0 检测逻辑

The theme of upstream 1.5.0 is "Visual HUD studio, replay library and opening detection". Of the three, only the third (opening detection) falls within the injector's capabilities.

上游 1.5.0 的主题是「Visual HUD studio, replay library and opening detection」。三块里只有第三块
（opening detection）落在注入器的能力范围内。

### Ported / 已移植

| # | Upstream change / 上游改动 | Port location / 移植位置 | Notes / 说明 |
|---|---|---|---|
| 1 | `BridgeDetection.flatLabel` gains `ticks == 3 → "1.0 Flat"` / `BridgeDetection.flatLabel` 新增 `ticks == 3 → "1.0 Flat"` | New `core/.../BridgeDetection.java` (class copied wholesale) / 新增 `core/.../BridgeDetection.java`（整类照搬） | A three-tick opening is a technique in its own right and is no longer rounded to 1.05 / 三 tick 开局是独立技术，不再被四舍五入成 1.05 |
| 2 | `BridgeDetection.lateLabel`: the first jump after a 1.0 Flat is `5b Late` if it comes 9 ticks later, rises 1 block, advances 3 columns along the bridge axis and has placement evidence; a 4-tick opening yields `6b Late` / `BridgeDetection.lateLabel`：1.0 Flat 后的第一跳若在 9 tick 后、升 1 格、沿桥轴前进 3 列、且有放置证据，判 `5b Late`；4 tick 开局则 `6b Late` | Same as above + the `Tracker.recordJump` call / 同上 + `Tracker.recordJump` 调用 | Neither a normal 2-column tap speed nor later jumps satisfy the conditions / 普通 2 列 tap speed 与后续跳跃都不满足条件 |
| 3 | Y added to the position ring (`positionHistoryY`), `sampledY` added to the `recordJump` signature / 位置环里加入 Y（`positionHistoryY`），`recordJump` 签名加 `sampledY` | `Tracker` | Deciding "rose 1 block" requires Y / 判断"升了 1 格"必须有 Y |
| 4 | Two states: `lastLaunchX/Y/Z`, `openingTicks` / `lastLaunchX/Y/Z`、`openingTicks` 两个状态 | `Tracker` | Opening tick count and take-off point, used by the late detection / 开局 tick 数与起跳点，late 判定用 |
| 5 | Axis auto-detection threshold `dx > 1.0` → `dx > 0.05` / 轴自动判定阈值 `dx > 1.0` → `dx > 0.05` | `Tracker.recordJump` | A three-tick opening may move less than 1 block, and the old threshold would pick the wrong axis and lose the placement data / 三 tick 开局可能位移不足 1 格，旧阈值会把轴判错并丢掉放置数据 |
| 6 | Every label goes through placement evidence: when `PlacementEvidence.qualifies(...)` fails it degrades to `Jump` / `Jump #n` / 标签一律过放置证据：`PlacementEvidence.qualifies(...)` 不满足时降级为 `Jump` / `Jump #n` | New `core/.../PlacementEvidence.java` + `Tracker.recordJump` | See "Adaptation differences" below / 见下方"适配差异" |
| 7 | New `RunStartCooldown` (5 ticks): no new run within 5 ticks of a failure/success; also forbids starting while `pendingPlateSample` or `pendingJumps` is non-empty, or while standing on the end gold plate / 新增 `RunStartCooldown`（5 tick），失败/成功后 5 tick 内不许开新局；同时禁止在 `pendingPlateSample`、`pendingJumps` 非空、或站在终点金踏板上开局 | New `core/.../RunStartCooldown.java` + `Tracker.onBlockPlaced` | Fixes "one mistake counted twice" / 修的是"一次失误被算两次" |
| 8 | tap label branches aligned (`turnRecorded` → `Jump #n`, 12 tick → `4b Flat #n`, 4+ blocks → `4b Jump #n`) / tap 标签分支对齐（`turnRecorded` → `Jump #n`、12 tick → `4b Flat #n`、4 格以上 → `4b Jump #n`） | `Tracker.recordJump` | Upstream already had these in 1.4.5 and this port had missed the branches; without them, evidence degradation lands on the wrong label / 上游 1.4.5 就有，本移植此前漏了分支；不补齐的话证据降级后会落到错误的标签 |
| 9 | `tickOffset` / `plateTickOffset` default to 0 / `tickOffset` / `plateTickOffset` 默认 0 | `Config` (already 0, `configRevision=2` migration) / `Config`（早已是 0，`configRevision=2` 迁移） | No change, confirmed consistent / 无改动，确认一致 |

### Adaptation differences (important) / 适配差异（重要）

Upstream's `PlacementEvidence.record(...)` records **the coordinates of the block that was placed** — it relies on `PlacementHooks` comparing the `Block` state of the hit/adjacent blocks before and after the click, which requires world access.

上游的 `PlacementEvidence.record(...)` 记的是**被放下的方块坐标**——它靠 `PlacementHooks` 对比
点击前后 hit/adjacent 两个方块的 `Block` 状态，能读到世界。

The injector can only enter the game through profile-driven reflection hooks and cannot read raytrace / chunks, so here it records **the block column the player was in at the placement tick** (`floor(playerX)`, `floor(playerZ)`).

注入器只能通过 profile 驱动的反射钩子进游戏，读不到 raytrace / chunk，所以这里改成记
**放置 tick 时玩家所在的方块列**（`floor(playerX)`、`floor(playerZ)`）。

- Semantics kept: `qualifies(from, to, start, end, xAxis, count)` still answers "did the player really place blocks on N distinct block columns in this interval" — which is exactly what every label threshold asks. / 保留的语义：`qualifies(from, to, start, end, xAxis, count)` 仍然回答"这段时间里玩家是否在 N 个不同方块列上真的放过方块"——每条标签门槛问的就是这个。
- Semantics dropped: upstream's "the block must be 0.3 blocks in front of the player's direction of travel". It only excluded "placed into the column under your own feet", and when bridging the block is always placed in front anyway; the cost is a slightly looser threshold, which never kills a genuine result. / 丢掉的语义：上游那条"方块必须在玩家前进方向前方 0.3 格外"的判据。它只用来排除"放进了自己脚下这一列"的情况，而搭桥时方块总是放在前方；代价是门槛更宽松一点，不会误杀真实成绩。

### Not ported (not applicable) / 未移植（不适用）

| Upstream feature / 上游功能 | Reason / 原因 |
|---|---|
| HUD visual layout studio (drag, resize handles, snapping, three overlay position sets) / HUD 可视化布局台（拖拽、缩放手柄、吸附、三套 overlay 位置） | The injector's HUD is a plain text panel with no GUI layer; positioning goes through `hudX/hudY/hudScale` / 注入器的 HUD 是纯文本面板，没有 GUI 层；定位靠 `hudX/hudY/hudScale` |
| Replay system (recording, library, filters, sorting, replay input HUD, free camera) / 回放系统（录制、库、过滤器、排序、回放输入 HUD、自由相机） | The injector does not record; `Replay*` and `BridgeScreen` all depend on the Minecraft render/input stack / 注入器不录制；`Replay*` 与 `BridgeScreen` 全部依赖 Minecraft 渲染/输入栈 |
| Settings-screen re-layout, color palette / 设置界面重排、颜色色板 | The injector's settings page is on the Electron side and uses its own `MOD_FIELDS` table / 注入器的设置页在 Electron 侧，走自己的 `MOD_FIELDS` 表 |
| Config keys such as `replayHudX/…`, `replayQuery/…`, `showReplayInputs` / `replayHudX/…`、`replayQuery/…`、`showReplayInputs` 等配置键 | Same as above, replay-only / 同上，回放专用 |

### Injector-only logic (not upstream) / 注入器自研部分（上游没有）

| Behaviour / 行为 | Why the injector needs it / 为什么注入器需要 |
|---|---|
| `FAIL (Void)` after 50 airborne ticks / 离地 50 tick 判掉虚空 | The mod does not detect a fall at all: `BridgeTracker` has no height comparison, so a dropped run stays `BRIDGING` until `/bt reset`. A hung HUD reads as a bug here, so the injector closes the run once landing is clearly impossible. 50 ticks is roughly twice the ~23 ticks a 100-block fall takes; touching down clears `ticksSinceGround`, which is what keeps a jump down to a lower finish island from being called a void fall. / 模组根本不检测掉落：`BridgeTracker` 里没有任何高度比较，掉下去会一直停在 `BRIDGING` 直到手动 `/bt reset`。HUD 挂死在这里看起来就是 bug，所以注入器在「明显不可能再落地」时收尾。50 tick 约为 100 格自由落体（约 23 tick）的两倍；落地会清零 `ticksSinceGround`，跳到更低终点岛的正因此不会判成掉虚空。 |
| `FAIL (Void)` when a rescue teleport arrives in the middle of a fall / 下坠途中收到救援传送即判掉虚空 | The upstream `Teleport` branch was never ported: `JumpSignals.isUpwardTeleport` existed but nothing called it, so a server that pulls a falling player back to the island looked like an ordinary position update. Now a one-tick rise of more than 1.5 blocks is reported as `Void` when the previous sample was already below the fall line, and as `Teleport` otherwise. / 上游的 `Teleport` 分支一直没移植：`JumpSignals.isUpwardTeleport` 存在但没人调用，服务器把掉下去的人拉回岛上看起来就跟普通位置更新一样。现在一 tick 上升超过 1.5 格会收尾 —— 上一帧已经低于判定线就记 `Void`，否则记 `Teleport`。 |
| `FAIL (Stalled)` after 60 grounded ticks / 落地 60 tick 判卡住 | Replaces the earlier 5-tick `Missed Jump`. That window was shorter than the walk from the landing spot to the plate on most finish islands, so a successful jump down was closed as a failure before the player could press it. / 取代原来的 5 tick `Missed Jump`：那个窗口比「落点到压力板」的步行时间还短，成功跳下去反而会在踩板前被判死。 |

### Verification / 验证

```
$ bash tests/run.sh                      # excerpt · 节选
[1] flatLabel      3→1.0 Flat / 4→1.05 Flat / 5→1.1 Flat / 12→4b Flat / 6@1.2s→1.2 Flat
[2] lateLabel      5b Late / 6b Late / no evidence→null · 无证据→null / not the first jump→null · 非首跳→null / 2 columns→null · 2 列→null
[3] 3-tick opening runs the whole chain · 3-tick 开局走完整条链路   entries 1 · 条目数 1, label "1.0 Flat" · 标签
[4] the first jump after the opening meets the late conditions · 开局后的第一跳符合 late 条件
[5] 5 tick cooldown after the run ends · 结束后 5 tick 冷却           ignored within 5 ticks · 5 tick 内 ignored
All checks passed / 全部通过
```

---

## 2026-11-21 · injector-side fix: no run before the first placement / 注入器侧修正：首个方块之前不开局

**Not an upstream sync.** Upstream starts a run only from `PlayerControllerMP.onPlayerRightClick`
returning true for an `ItemBlock`, so it never had this bug. The injector reaches the same signal
through a bytecode hook that may not install, so it kept a fallback — and the fallback was gated on
the wrong question. Recorded here because it changes what "a run starts" means, which is the class
of change this document exists to track.

**这不是上游同步。** 上游只在 `PlayerControllerMP.onPlayerRightClick` 对 `ItemBlock` 返回 true 时
开局，从来不会有这个 bug。注入器靠字节码钩子拿同一个信号，而钩子可能装不上，于是留了一条兜底
——而兜底的判据问错了问题。记在这里是因为它改变了「什么算开局」，而这正是本文档要追踪的那类改动。

| # | Root cause / 根因 | Where / 位置 | Note / 说明 |
|---|---|---|---|
| 1 | The take-off fallback was gated on `!blockHookSeen`, i.e. "the hook has not fired yet", not "the hook is not installable" / 兜底以 `!blockHookSeen` 为条件，即「钩子还没触发过」，而不是「钩子装不上」 | `Bridge.tick` | Both are true between world join and the first right-click, so a bare jump opened a run, the void fall closed it as `FAILED`, and the next jump opened another — repeating until a block was actually placed / 从进世界到第一次右键之间两者都为真，于是裸跳就开局、掉虚空判失败、下一跳再开，循环到真的放下一块为止 |
| 2 | The fallback called `onBlockPlaced()`, which records `PlacementEvidence` unconditionally / 兜底调 `onBlockPlaced()`，而它无条件记录 `PlacementEvidence` | `Tracker.onBlockPlaced` | That credited the label gates with a block that was never placed, so a bare jump could earn a `1.0 Flat` / 等于给标签门控补了一块从没放过的方块，裸跳因此能拿 `1.0 Flat` |
| 3 | Nothing held "the first block has been placed" as state / 没有任何地方把「首个方块已放置」当作状态 | `Tracker` | `PlacementEvidence` only records events; it deliberately holds no boolean, so no gate could consult one / `PlacementEvidence` 只记事件，刻意不持有布尔，所以没有门能查它 |

### What changed / 改了什么

| File / 文件 | Change / 改动 |
|---|---|
| `Tracker` | New `takeOffMayStart` (default `false`) and `firstPlacementSeen`; new entry point `onTakeOff()`; the three placement entry points converge on one private `onPlacement(blockX, blockZ, hasBlockColumn, isPlacement)`; `onJump()` refuses to record while `!firstPlacementSeen`; `startRun` / `reset` / `onWorldChange` all clear the flag / 新增 `takeOffMayStart`（默认 false）与 `firstPlacementSeen`；新增入口 `onTakeOff()`；三个放置入口收敛到一个私有 `onPlacement(...)`；`onJump()` 在未放置前拒绝记录；三处复位都清标志 |
| `Bridge` | New `blockHookAbsent`, fed by `Bootstrap` from the injection table instead of inferred from hook activity; the fallback now calls `Tracker.onTakeOff()`; `blockHookSeen` demoted to a first-fire log line / 新增 `blockHookAbsent`，由 `Bootstrap` 从注入表给出而非从钩子活跃度反推；兜底改调 `Tracker.onTakeOff()`；`blockHookSeen` 降级为首触日志 |
| `Bootstrap` | `setControllerHookPresent` reported twice: from `resolveTargets()` when there is no controller class at all, and from `inject()` after `presentSites` — before the early return, because "nothing matched" is precisely the case that matters / 两处上报：`resolveTargets()` 里根本没有控制器类时，以及 `inject()` 里 `presentSites` 之后、提前 return 之前——「一个都没匹配上」恰恰是关键情形 |

### Why the two booleans are separate / 为什么两个布尔必须分开

`hasBlockColumn` answers "which column did the block go to"; `isPlacement` answers "was this a
placement at all". Merging them was the first attempt and it broke every client whose controller
does not expose the `BlockPos`: those clients call `onBlockPlaced()` for a real placement, and a
merged flag rejected them, so no run could ever start. `tests/run.sh` section 3 caught it
immediately (`entries 0`, expected 1).

`hasBlockColumn` 回答「方块落在哪一列」；`isPlacement` 回答「这到底是不是一次放置」。合并两者是
最初的写法，它把所有「控制器报不出 `BlockPos`」的客户端全废了：那些客户端对真放置调的是
`onBlockPlaced()`，合并后的标志把它们一起拒了，于是一局都开不起来。`tests/run.sh` 的 section 3
当场就抓到了（`entries 0`，期望 1）。

### Verification / 验证

```
$ bash tests/run.sh                      # excerpt · 节选
[20]  no run before the first placement · 首个方块之前不开局
      jump #1..#3 after world join do not start a run · 进世界后第 1..3 次跳跃不开局   IDLE
      no entries from bare jumping · 裸跳不产生任何条目                                0
      clock never started · 计时没有开始                                               0.0
      the first placement opens the run · 首次放置开局                                  BRIDGING
[20b] take-off as a fallback still waits for a block · 起跳兜底也要等方块
      a take-off may open the run · 起跳可以开局                                       BRIDGING
      but it is not a placement · 但起跳不算放置                                       false
      the void fall fails the run · 掉虚空判失败                                       FAILED
      the next take-off does not restart it · 下一次起跳不重开                          FAILED
[20c] reset clears the placement precondition · reset 清掉放置前置                   false
All checks passed / 全部通过        # 219 assertions · 219 条断言
```

---

## Baseline record / 基线记录

| Sync time / 同步时间 | Injector version / 注入器版本 | Upstream version / 上游版本 | Upstream commit / 上游 commit | Notes / 说明 |
|---|---|---|---|---|
| 2026-11-21 | 1.0.19-preview | 1.5.0 | `5c9306d` | Injector-side fix only, no upstream change: a run can no longer start before the first placement, and a take-off no longer counts as placement evidence (see the section above) / 仅注入器侧修正，无上游改动：首个方块之前不再开局，起跳不再算作放置证据（见上一节） |
| 2026-11-12 | 1.4.1 | 1.5.0 | `5c9306d` | Head hitter sits after Jump; a head hit on a landing plate press is no longer swallowed by the wrap-up / Head hitter 排在 Jump 之后；落地即踩板的顶头不再被收尾吞掉 |
| 2026-11-11 | 1.4.0 | 1.5.0 | `5c9306d` | Head hitter decided by airtime (12 ticks normal / 3 ticks head hit) / Head hitter 改为按空中时长判定（12 tick 正常 / 3 tick 顶头） |
| 2026-11-11 | 1.3.8 | 1.5.0 | `5c9306d` | Head hitter gains the world-side criterion (there must be a block above the head), fixing telly misdetection / Head hitter 补世界侧判据（头顶必须有方块），修 telly 误判 |
| 2026-11-11 | 1.3.7 | 1.5.0 | `5c9306d` | Text colors / `+.n/a` / Gain switched to the A/D rule / Head hitter / 文本颜色 / `+.n/a` / Gain 改为 A/D 判定 / Head hitter |
| 2026-11-11 | 1.3.6 | 1.5.0 | `5c9306d` | opening detection / 1.0 Flat / placement evidence / 5-tick cooldown / opening detection / 1.0 Flat / 放置证据 / 5 tick 冷却 |
| (port start point) / （移植起点） | 1.2.x | 1.4.5 | before `54e88ff` / `54e88ff` 之前 | The first agent version reproduced 1.4.5 behaviour / agent 初版按 1.4.5 行为还原 |

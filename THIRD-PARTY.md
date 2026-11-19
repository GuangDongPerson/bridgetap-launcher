# Third-Party Components and Licenses / 第三方组件与许可

This program (BridgeTap) is released under GPL-3.0; the full text is in `LICENSE`.
Listed below are the third-party components distributed with the artifacts, together with their respective licenses.

本程序（BridgeTap）以 GPL-3.0 发布，全文见 `LICENSE`。
下面是随产物一起分发的第三方组件，以及它们各自的许可。

## Distributed with the Artifacts / 随产物分发

| Component / 组件 | Version / 版本 | License / 许可 | Purpose / 用途 |
|---|---|---|---|
| Electron | 45.2.3 | MIT | Desktop runtime (Chromium + Node.js) / 桌面运行时（Chromium + Node.js） |
| ASM | 9.7.1 | BSD-3-Clause | Rewrites game class bytecode at runtime, packaged into `agent.jar` / 运行期改写游戏类字节码，打包进 `agent.jar` |
| ASM Tree | 9.7.1 | BSD-3-Clause | Same as above / 同上 |
| MiSans | 5.0.0 (VF) | "MiSans Font Intellectual Property License Agreement" / 《MiSans 字体知识产权许可协议》 | UI font, used in embedded form only / 界面字体，仅以嵌入形式使用 |

The special terms for MiSans are described in `BridgeTap-Launcher/src/renderer/assets/fonts/MiSans-license.txt`:
the glyphs have not been modified in any way, and the font files are not distributed separately — they are only embedded in the program.

MiSans 的特殊约定见 `BridgeTap-Launcher/src/renderer/assets/fonts/MiSans-license.txt`：
字形未做任何修改，字体文件不单独分发，只随程序内嵌使用。

## Build-time Only / 仅构建期

| Component / 组件 | License / 许可 | Purpose / 用途 |
|---|---|---|
| electron-builder | MIT | Produces the portable exe / 产出 portable exe |
| LWJGL | 2.9.3 (BSD-3-Clause) | Compile-time interfaces, provided at runtime by the game client, not shipped with the package / 编译期接口，运行期由游戏客户端提供，不随包分发 |
| Gradle | Apache-2.0 | Build / 构建 |

## License Texts / 许可文本

Every component that ends up inside a distributed artifact also keeps a copy of its license in this repository, so the notice travels with the code and can be read offline:

凡是随产物一起分发的组件，本仓库里都留了一份许可正文 —— 声明跟着代码走，离线也能查：

| Component / 组件 | Copy in this repo / 仓库内的副本 | Upstream / 上游 |
|---|---|---|
| Electron | `licenses/Electron-MIT.txt` | <https://github.com/electron/electron/blob/main/LICENSE> |
| ASM | `licenses/ASM.txt`, and inside `agent.jar` as `META-INF/ASM-LICENSE.txt` / 以及 `agent.jar` 内的 `META-INF/ASM-LICENSE.txt` | <https://asm.ow2.io/license.html> |
| MiSans | `BridgeTap-Launcher/src/renderer/assets/fonts/MiSans-license.txt` | <https://hyperos.mi.com/font> |
| LWJGL | Not required — compile-time only, never redistributed / 不需要：仅编译期使用，不随包分发 | <https://github.com/LWJGL/lwjgl/blob/master/LICENSE.md> |

`agent.jar` carries the ASM notice because ASM is shaded into it; BSD-3-Clause requires the notice to accompany binary redistribution.

ASM 是以 shade 方式打进 `agent.jar` 的，所以声明也一并放进去 —— BSD-3-Clause 要求二进制再分发时必须附带声明。

Complete third-party notices for Chromium and its dependencies bundled in Electron are provided together with the Electron distribution (`LICENSE` and `LICENSES.chromium.html`). At around 4 MB they are not copied into this repository; electron-builder copies them next to the Electron runtime when the portable exe is built:

Electron 内含的 Chromium 及其依赖的完整第三方声明，随 Electron 发行包一起提供
（`LICENSE` 与 `LICENSES.chromium.html`）。约 4 MB，不放进本仓库；打包便携版时
electron-builder 会把它们复制到 Electron 运行时旁边：

```
dist/win-unpacked/LICENSE.electron.txt
dist/win-unpacked/LICENSES.chromium.html
```

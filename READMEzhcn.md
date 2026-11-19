# Bridgetap 启动器

[English](README.md) · 简体中文 · [繁體中文](READMEzhtw.md)

一个适用于 *Minecraft 1.8.9 ( blc \& lc )* 的搭路信息实时查看器

游戏实例截图 (如图左上角)：

<img width="2297" height="1247" alt="example" src="https://github.com/user-attachments/assets/ae56d920-d7e5-448b-8ef1-f288d806168e" />

[下载最新版本](https://github.com/GuangDongPerson/bridgetap-launcher/releases/latest) · [所有版本](https://github.com/GuangDongPerson/bridgetap-launcher/releases) · [更新日志](CHANGELOG.md) · [报告问题](https://github.com/GuangDongPerson/bridgetap-launcher/issues)

**1.0.11** 基于 [sgggnnn/bridgetap](https://github.com/sgggnnn/bridgetap) 1.5.0开发，具有该版本的所有HUD和搭路判定特性

## 安装

1.打开[版本1.0.11](https://github.com/GuangDongPerson/bridgetap-launcher/releases/tag/v1.0.11)。  
2. 使用 **Java 8** 或更高版本启动 *Badlion/Lunar Client*。  
3.等待启动器检测到游戏进程。  
5. 单击“开始启动”按钮或等待启动器自动启动（需要在设置中启用“自动启动”）  
5.在游戏聊天框内执行 /bt 来确认启动生效。  
6.开始搭路吧！**:D**

## 特点

* **搭路信息 HUD**：跳跃坐标、增量、刻度、转弯、终点坐标和 gain/no gain 指示器。
* **可自定义显示**：位置、比例、背景、阴影、单个元素颜色、聊天输出以及仅显示最近一次跳跃的coordi和last turn
* **搭路检测**：3-tick 1.0 Flat，4-tick 1.05 Flat, 5-tick 1.1 Flat , 6-tick 1.15 Flat , 5b Late \& 6b Late ,hold speed\&tap speed
* **岛屿测量**：当记录包含所需数据时，平台边缘之间的间隙及其高度差。

## 构建

````bash
npm install # 只安装 Electron 和 Electron-builder
npm run fonts # 获取 MiSans 字体碎片（首次运行需要，需要网络）
npm start # 从源代码运行
npm run dist # package → dist/BridgeTap-Injector-1.0.0.exe
````

`npm run dist` 首先检查字体是否完整；如果缺少任何一个，它会直接失败，而不是生成没有字体的构建。

## 字体：为什么它们不随仓库分发

启动器UI字体使用MiSans（小米）。
而《MiSans字体知识产权许可协议》第三条禁止“进一步分发该字体软件或其任何副本”，而将56个woff2文件提交到公开仓库正是这种行为。

所以：

* 仓库**仅声明**`src/renderer/styles/fonts.css`（56个`@font-face`规则+ unicode-range）和`src/renderer/assets/fonts/MiSans-license.txt`；字体文件本身并不存在。
* 在构建之前，运行“npm run fonts”以从 npm 包“misans@5.0.0”获取分片，路径与“fonts.css”中的路径相同。
* 将它们打包进 exe 中是**嵌入式使用**，协议明确允许（常见问题解答：“可以用作嵌入式字体”），前提是软件中注明了 MiSans ，在软件“关于”页的底部有标注。

## 接下来需要改进的

* 跟随上游最新版的一些特性
* 以及一些新功能

## 贡献

维护地址 [GuangDongPerson/bridgetap-launcher](https://github.com/GuangDongPerson/bridgetap-launcher)。感谢项目 [sgggnnn/bridgetap](https://github.com/sgggnnn/bridgetap)。

## 许可

应用程序本身：GPL-3.0，请参阅 [LICENSE](LICENSE)。

应用附带的第三方组件（Electron、ASM、MiSans）及其许可证文本列在存储库根目录的“THIRD-PARTY.md”中；全文位于“licenses/”中。

UI字体MiSans：版权所有（c）北京小米移动软件有限公司，根据“MiSans字体知识产权许可协议”分发，仅以嵌入形式随工件一起提供，未对字形进行任何修改。完整协议和使用边界位于“src/renderer/assets/fonts/MiSans-license.txt”中。

## 交流群

[Discord](https://discord.gg/Dm7YjP9hGU) · [QQ](https://qm.qq.com/q/PkzlxqrUce)

## 作者想说

在社区第一次看到这个模组的时候很惊艳，从前我们这些搭路玩家每次要看coordi(mm)都要看回放，一个一个调位置。但是原模组最开始仅支持fabric(后来加入了对forge客户端的支持)。我就在想能不能借助现在的ai发展，vibe coding 二改一个支持Badlion和Lunar Client的版本出来。从2026年11月09日晚上开始了研究，发现可行之后我就一发不可收拾：(本人为大一学生)一下课回到宿舍就开始干项目，从最开始的搭路数据绘制，游戏方法hook 一直到精美的外置启动器ui，以及各种bug修复和优化。前前后后总共用了约一个星期(截止至2026年11月17日)，原本想美美开肝原神7.2新地图的，都没怎么玩。

现在插件目前达到了可用的水平，但还是有些小问题需要改进，各位可以留下宝贵的意见，不过在这之前我得先休息一星期了，上周狂肝这个项目导致我都没怎么好好睡觉。。。

附件：Token用量

<img width="2560" height="1440" alt="tokens" src="https://github.com/user-attachments/assets/64dc2116-7d88-4b07-a7d4-a6388a6a1e64" />


# Bridgetap 啟動器

[English](README.md) · [简体中文](READMEzhcn.md) · 繁體中文

一個適用於 *Minecraft 1.8.9 ( blc \& lc )* 的搭路資訊即時檢視器

遊戲實例截圖 (如圖左上角)：

<img width="2297" height="1247" alt="example" src="https://github.com/user-attachments/assets/ae56d920-d7e5-448b-8ef1-f288d806168e" />

[下載最新版本](https://github.com/GuangDongPerson/bridgetap-launcher/releases/latest) · [所有版本](https://github.com/GuangDongPerson/bridgetap-launcher/releases) · [更新日誌](CHANGE.md) · [報告問題](https://github.com/GuangDongPerson/bridgetap-launcher/issues)

**1.0.12** 基於 [sgggnnn/bridgetap](https://github.com/sgggnnn/bridgetap) 1.5.0開發，具有該版本的所有HUD和搭路判定特性

## 安裝

1.開啟 [版本1.0.12](https://github.com/GuangDongPerson/bridgetap-launcher/releases/tag/v1.0.12)。                                                                                                                                                                                                                              
2. 使用 **Java 8** 或更高版本啟動 *Badlion/Lunar Client*。
3.等待啟動器偵測到遊戲進程。                                                                                                                                                                                      
5. 點選「開始啟動」按鈕或等待啟動器自動啟動（需要在設定中啟用「自動啟動」）                                                                                                                                  
5.在遊戲聊天框內執行 /bt 來確認啟動生效。                                                                                                                                  
6.開始搭橋吧！**:D**

## 特點

* **搭路資訊 HUD**：跳躍座標、增量、刻度、轉彎、終點座標和 gain/no gain 指示器。
* **可自訂顯示**：位置、比例、背景、陰影、單一元素顏色、聊天輸出以及僅顯示最近一次跳躍的coordi和last turn
* **搭路檢測**：3-tick 1.0 Flat，4-tick 1.05 Flat, 5-tick 1.1 Flat , 6-tick 1.15 Flat , 5b Late \& 6b Late ,hold speed\&tap speed
* **島嶼測量**：當記錄包含所需數據時，平台邊緣之間的間隙及其高度差。

## 建構

````bash
npm install # 只安裝 Electron 和 Electron-builder
npm run fonts # 取得 MiSans 字體碎片（第一次運作需要，需要網路）
npm start # 從原始碼執行
npm run dist # package → dist/BridgeTap-Injector-1.0.12.exe
````

`npm run dist` 首先檢查字體是否完整；如果缺少任何一個，它會直接失敗，而不是產生沒有字體的建置。

## 字體：為什麼它們不隨倉庫分發

啟動器UI字體使用MiSans（小米）。
而《MiSans字體知識產權許可協議》第三條禁止“進一步分發該字體軟體或其任何副本”，而將56個woff2文件提交到公開倉庫正是這種行為。

所以：

* 倉庫**只宣告**`src/renderer/styles/fonts.css`（56個`@font-face`規則+ unicode-range）和`src/renderer/assets/fonts/MiSans-license.txt`；字型檔案本身不存在。
* 在建置之前，執行「npm run fonts」以從 npm 套件「misans@5.0.0」取得分片，路徑與「fonts.css」中的路徑相同。
* 將它們打包進 exe 中是**嵌入式使用**，協議明確允許（常見問題解答：「可以用作嵌入式字體」），前提是軟體中註明了 MiSans ，在軟體「關於」頁的底部有標註。

## 接下來需要改進的

* 跟隨上游最新版的一些特性
* 以及一些新功能

## 貢獻

維護位址 [GuangDongPerson/bridgetap-launcher](https://github.com/GuangDongPerson/bridgetap-launcher)。感謝專案 [sgggnnn/bridgetap](https://github.com/sgggnnn/bridgetap)。

## 許可

應用程式本身：GPL-3.0，請參閱 [LICENSE](LICENSE)。

應用附帶的第三方元件（Electron、ASM、MiSans）及其授權文字列在儲存庫根目錄的「THIRD-PARTY.md」；全文位於「licenses/」中。

UI字體MiSans：版權所有（c）北京小米行動軟體有限公司，根據「MiSans字體知識產權授權協議」分發，僅以嵌入形式隨工件一起提供，未對字形進行任何修改。完整協議和使用邊界位於“src/renderer/assets/fonts/MiSans-license.txt”中。

## 交流群

[Discord](https://discord.gg/Dm7YjP9hGU) · [QQ](https://qm.qq.com/q/PkzlxqrUce)

## 作者想說

在社群第一次看到這個模組的時候很驚艷，從前我們這些搭路玩家每次要看coordi(mm)都要看回放，一個一個調位置。但是原模組最開始僅支援fabric(後來加入了對forge客戶端的支援)。我就在想能不能藉助現在的ai發展，vibe coding 二改一個支援Badlion和Lunar Client的版本出來。從2026年11月09日晚上開始了研究，發現可行之後我就一發不可收拾：(本人為大一學生)一下課回到宿舍就開始乾項目，從最開始的搭路數據繪製，遊戲方法hook 一直到精美的外置啟動器ui，以及各種bug修復和優化。前前後後總共花了約一個星期(截止至2026年11月17日)，原本想美美開肝原神7.2新地圖的，都沒怎麼玩。

現在插件目前達到了可用的水平，但還是有些小問題需要改進，各位可以留下寶貴的意見，不過在這之前我得先休息一星期了，上週狂肝這個項目導致我都沒怎麼好好睡覺。 。 。

附件：Token用量

<img width="2560" height="1440" alt="tokens" src="https://github.com/user-attachments/assets/64dc2116-7d88-4b07-a7d4-a6388a6a1e64" />

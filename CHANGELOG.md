# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-11-22

The HUD anchor can now be dragged into place instead of typed in, without giving up the boxes that
still hold the exact numbers.

### Added

- **HUD quick adjust.** A new row sits between "HUD scale" and "HUD X", with a button that opens a
  virtual stick. It edits `hudXPercent` / `hudYPercent` together: dragging right moves the anchor
  across the screen, dragging down moves it towards the bottom. Writes are coalesced to ~50 ms and
  the value held at release is always flushed, so the game keeps up without the launcher rewriting
  every client config on every pixel. Hold Shift for a quarter of the travel, or use the arrow keys
  for 1% steps (0.1% with Shift).
- **The panel previews the anchor, not just the numbers.** A 16:9 stand-in puts the block in the
  same place, with the same formula the agent uses, so what you line up is what the HUD does.
- **The result survives closing.** Every step lands in the same config file the two number boxes
  edit, and those boxes are refreshed live while dragging — the manual path is untouched, and
  leaving the panel can never roll anything back.
-**Launcher launches at startup,** Now you can make the launcher start automatically on boot.Paired with auto-launch,You will enjoy a better user experience.

### Fixed
 -**The Uninstalling does not take effect** The original uninstallation code pointed to an incorrect, empty path.Now it works.
 -**4b flat detection** Fixed an issue where 4b flat could not be detected.
 -**Game process detection** Fixed an issue where the game process might not be detected in certain cases.
 -**The wrong error response feedback** Fixed an issue where the launcher would provide an abnormal error response when critical files were missing.

## [1.0.12] - 2026-11-20

Mirror list replaced, and the download path got three hard checks so a transfer that never
crossed the network can no longer pass as complete.

### Changed

- **Mirror routes are now gh-proxy.org, github.chenc.dev and ghp.keleyaa.com.** The previous
  three (gh-proxy.com, ghproxy.imciel.com, ghproxy.net) are gone, with no fallback kept behind.
  gh-proxy.org answers the Release asset and api.github.com directly; chenc.dev folds `https://`
  into `https:/` with a 301 before serving; keleyaa proxies the asset through a 302 and answers
  403 on api.github.com because its egress IP is shared with other users.

### Fixed

- **Downloads no longer consult Chromium's HTTP cache.** Every mirror replies
  `Cache-Control: public, max-age=60` while the direct route says `no-cache`, so a cached body
  could finish "downloading" at disk speed — a 115 MB bar filling in seconds while nothing
  crossed the network. Requests now carry `cache: false` plus `Cache-Control: no-cache`.
- **A short body fails as `truncated`.** The transfer used to resolve whenever the stream ended,
  even with fewer bytes than Content-Length, which left the bar at 100% before the checksum
  complained. Received bytes are now compared against Content-Length at the download step.
- **Every attempt starts from a clean file.** The destination is deleted before the request and
  again after a failure, so a leftover from an earlier attempt cannot pass as this result.

### Added

- **`apply.log` now records the transfer**: route, bytes received vs Content-Length, elapsed
  time, MB/s and the checksum outcome. A three-second update now shows up as an implausible
  MB/s figure instead of going unnoticed.


## [1.0.11] - 2026-11-19

The first release version.


# 更新日志

本文件记录本项目的所有重要变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.1.0] - 2026-11-22

HUD 位置现在可以直接拖到位，不用再一格一格敲数字，而那两个数字框照旧管用。

### 新增

- **HUD 快捷调整。** 设置页在「HUD 缩放」和「HUD 横向位置」之间多了一行，点按钮打开虚拟摇杆。
  摇杆同时改 `hudXPercent` / `hudYPercent`：向右拖把 HUD 移向右边，向下拖移向底部。
  写入按约 50ms 合流，松手时手上的值必定补写一次，所以游戏里跟得上，启动器也不会每挪一个像素
  就把每个客户端的配置重写一遍。按住 Shift 行程缩到四分之一；方向键每下 1%（带 Shift 是 0.1%）。
- **面板预览的是锚点，不只是数字。** 一块 16:9 的示意图按与 agent 相同的公式摆放，
  对齐的是什么，游戏里就是什么。
- **关掉面板结果不会丢。** 每一步都落进那两个数字框改的同一份配置文件，拖动期间数字框也会实时刷新——
  手动调节那套完全没动，退出面板也不可能把值回滚。

#### 修复
- **卸载功能是滚木**：原有的卸载代码指向了一个错误的空路径，现已修复。
- **4b flat 检测**：修复了无法检测到 4b flat 的问题。
- **游戏进程检测**：修复了在某些情况下可能无法检测到游戏进程的问题。
- **错误的错误反馈**：修复了在关键文件缺失时，启动器会给出异常错误反馈的问题。

## [1.0.12] - 2026-11-20

换掉镜像线路，并给下载链路补了三道硬校验：没真正经过网络的"下载"不能再被当成完成。

### 变更

- **镜像换成 gh-proxy.org、github.chenc.dev、ghp.keleyaa.com。** 原来的三条
  （gh-proxy.com、ghproxy.imciel.com、ghproxy.net）全部移除，没有保留兜底。
  gh-proxy.org 对 Release 资产和 api.github.com 都是直出；chenc.dev 会先把 `https://`
  折叠成 `https:/` 回 301 再服务；keleyaa 的资产走 302，查更新则因出口 IP 与他人共用而回 403。

### 修复

- **下载不再走 Chromium 的 HTTP 缓存。** 三条镜像都回 `Cache-Control: public, max-age=60`，
  而直连是 `no-cache`，一旦命中缓存，115 MB 就是按磁盘速度"下完"的——进度条几秒跑满，
  网络上什么都没发生。现在请求带 `cache: false` 与 `Cache-Control: no-cache`。
- **收到的字节少于 Content-Length 直接报 `truncated`。** 原来的实现只要流结束就算成功，
  哪怕字节不够，于是进度条先走满、再由校验报错。现在在下载这一步就比对长度。
- **每次下载都从干净文件开始。** 请求前删除目标文件，失败后再删一次，
  上一次的残留不会被当成这次的结果。

### 新增

- **`apply.log` 记录传输过程**：线路、收到字节 / Content-Length、耗时、MB/s、校验结果。
  三秒下完这种异常会以不合理的 MB/s 留在日志里，而不再无声无息。

## [1.0.11] - 2026-11-19

第一个正式发行的版本。
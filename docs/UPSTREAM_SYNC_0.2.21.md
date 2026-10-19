# DiPlay v0.2.21 三项选择性同步

2026-10-19，按用户截图明确选择，仅移植评估表中前三项。应用 `versionName` 保持 **0.1.1**，`versionCode` 从 **93** 增至 **94**。后续已包含在构建 94，见 [构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)。

## 来源

固定上游为 [DiPlay v0.2.21 预发布](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.21)，提交 `49bf3bc2b011cb5ee7c7f4da9b99b18cbd3a23a2`。没有整分支合并或覆盖工作区；此前 [v0.2.20 选择性同步](UPSTREAM_SYNC_0.2.20.md)、[官方 USB/VPN 恢复](USB_VPN_RESTORE_0.2.20.md)及本地星瑞修改继续保留。

| 用户选定内容 | 实际来源提交 | 本地移植 |
| --- | --- | --- |
| 音乐积压与延迟修复 [#814](https://github.com/shihabal3amri/DiPlay/pull/814) | `15ee0cab58b0ca5fda74ff369c78da1cbb31c165` | `MediaClockTrim` 加入待解码音频时长；拥堵时不学习错误的时钟偏差。新增 `MediaPacketLagGuard`，只在真实音乐流持续积压时清除过期包。 |
| 软件 Opus 崩溃保护 [#866](https://github.com/shihabal3amri/DiPlay/pull/866) | `4791f5d09c5cf6876a129ecb6de038f97b608769` | 明确捕获 Concentus `AssertionError`；失败后重置状态、跳过当前 20 ms 帧，保留后续编码及限量错误报告。 |
| USB 模式切换超时复查 [#841](https://github.com/shihabal3amri/DiPlay/pull/841) | `ab324016a6187b8ec5d1b7ff43a6776279d26ab5`，仅 USB 部分 | 模式请求成功后等待 10 秒；仍在相同阶段、相同尝试和代际，且原设备节点仍在线时，重新走权限与配置检查。 |

源码以固定标签的最终状态为准。仅同步上述三项；其他上游改动未移植，已删除重复的评估草稿。

## 本地边界

音乐时钟计入的压缩队列按 RTP 时钟换算为实际 PCM 帧数。积压超过 1.5 秒并持续另一个 1.5 秒时，才回收旧包并追赶到约 500 ms；短暂 Wi-Fi 补包突发不立即丢弃。保留 RTP 回绕／时间戳重置保护、速率变化上限 ±1.5% 及平台拒绝调速时的退出。语音、导航和通话流不加入积压回收，也不因物理映射到媒体通道而启用时钟调速。

播放器与媒体方控继续共享本地焦点；导航时仅将本应用音乐压至 30%，通话／Siri 发声时静音音乐，结束后按有效焦点恢复。默认流 3／12、用户关闭焦点和原车蓝牙输出选择保持现有语义。没有移入该 USB 复合提交中的导航 PCM 衰减。

USB 复查是当前模式请求的有限观察，不是新一轮全栈自动重试。已经收到新插入事件、设备离线、取消、控制器关闭、尝试替换或流程已推进时，旧定时器不再行动。模式请求完成的异步回调和主线程排队任务也检查同一尝试，不能让旧回调继续打开数据路径。

超时后只复查原 iPhone，不用另一台手机替代。继续使用原 USB 授权、配置选择和最多两次模式请求规则；没有权限时仍请求系统授权，不伪造授权或自动修改 VPN app-op。既有 StartSession 后 30 秒准入超时、VPN/TUN 失败停止自动重试、无线不使用 VPN、清理与 USB 优先级保持不变。

## 验证

定向验证范围：

- `MediaClockTrimTest`、`MediaPacketLagGuardTest`：持续积压、短时补包、时钟偏差学习、RTP 回绕与重置。
- `AudioClockTrimTest`：真实渲染器计入压缩队列、语音流与音乐流隔离、平台拒绝调速。
- `SoftwareOpusEncoderTest`：注入断言后重置，下一帧仍可解码，保持平台编码器优先。
- `CarPlayAudioMixerTest`、`AudioFocusAutoYieldTest`：本地混音与焦点语义回归。
- `CarPlayControllerUsbServiceTest`：10 秒前不复查、到期复查、插入事件失效、设备拔除、取消、尝试替换、另一台 iPhone 排除，以及系统 USB 权限仍必须满足。
- `WiredConfigurationActionTest`、`WiredSessionMemoryTest`、`WiredStartupDeadlineTest`、`CarPlayVpnConsentTest`、`UsbConnectionPriorityTest`：配置／次数上限、30 秒准入、授权失败及 USB 优先级回归。

最终共 **152 项定向测试通过**（shared 91、common 61，0 失败／错误／跳过；重复运行不累计）。补充的异步回调测试确认：模式请求成功确实启动 10 秒观察；关闭后的成功回调不恢复等待；旧请求失败不能结束新的尝试。测试 USB 描述符保持合法空配置，日志使用并发安全收集。

`git diff -w --check` 按 Windows CRLF 规则核对通过。没有请求完整测试，因此未执行 CI 全量任务；该定向验证阶段未构建 APK；后续交付见上方构建记录。测试汇总在本机忽略目录 `agent_docs/upstream-0.2.21-three-fixes/test-results.json`。

## 实车边界

这些是可用于星瑞的通用应用修正，不新增私有固件接口或车型分支。参考固件仍为 IHU519G / FS11A1 / Android 9 / 构建 327，原 OTA SHA-256 为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`；未增加新的静态固件能力结论。

源码和模拟验证不能证明 IHU353G 的 USB 接入、语音录制或长时间音乐播放已恢复，也不代表原厂媒体 API ready=false、方控跳原车多媒体或原车蓝牙自动暂停已解决。后续实车需分别检查连续播放／暂停切歌、Siri／语音消息，以及 USB 断开后线缆仍接入时的重连。

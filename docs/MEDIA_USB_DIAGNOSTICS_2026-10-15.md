# 2026-10-15：音源、蓝牙与 USB 定位日志补充

针对 [第五份报告](CONNECTION_REPORT_2026-10-15_5.md) 与 [OpenAPI 服务端参考](NETEASE_TRACK_SERVICE_REFERENCE.md) 的证据缺口补充只读诊断。不改变初始化时机、调用线程、播放／切歌策略或 USB 连接策略。没有构建 APK，现有 **0.1.1-64 不包含本页新增日志**。

## 原厂 SDK

`OEM media diagnostic` 包含：本应用构建号、application Context／主线程标志、系统应用标志、媒体权限检查；OpenAPI／媒体中心包的可见性、构建号、启用和 stopped 状态；固定 V3／V4 服务 action 的解析、导出和声明权限检查。manifest 只增加这两个包的查询可见性，不新增运行权限，也不以检查结果阻止初始化。

服务解析标记 `queryOnly=true`，不额外绑定／启动服务。不可见不等于未安装，权限未授予也不能独立证明该 SDK 方法要求它。另记录 init 返回耗时、ready 回调耗时／线程、注册结果是否存在、requestPlay 接受／耗时，以及最多 64 条原厂媒体回调索引和接受结果；没有歌曲内容或注册凭据。

SDK 异常只记类名和固定类别：调用者未获授权、目标服务未就绪、签名检查失败、权限拒绝、Binder 死亡、绑定失败或 unknown，不导出原文。首次 ready=false 时后台额外采样一次本应用进程的最近 160 行 logcat：500 ms 完成上限、64 KiB 输出上限，Android 25 不支持限时等待时明确 unavailable。只匹配 ECarX／mediacenter 相关固定错误类别，排除自己生成的摘要，**不导出原始 logcat**。

日志标为 `scope=own_process vendorProcess=unavailable causeProven=false`。最近日志可能含同一进程前次连接，因此类别仅是线索，没有匹配不代表厂商没有拒绝。没有申请 READ_LOGS、root 或跨 UID 日志能力；失败采样不在回调／主线程等待。启动历史由 16 调整为 64 行，继续回放到连接报告。

## 蓝牙与媒体

`Car audio diagnostic` 在控制器 attach 注册、detach／替换注销，监听 A2DP Sink 连接／播放、AVRCP Controller 连接、Headset Client 连接／音频状态。依据 AOSP Android 9 的 [BluetoothA2dpSink](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/bluetooth/BluetoothA2dpSink.java)、[BluetoothProfile](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/bluetooth/BluetoothProfile.java) 和 [BluetoothHeadsetClient](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/bluetooth/BluetoothHeadsetClient.java)。可用性仍受权限和固件实现限制，不按车型／构建号启用。

事件只记类型、当前／前状态及是否匹配所选手机，无设备身份时 unknown、无读取权限时 unavailable；不记名称、地址或 AVRCP 内容。按类型／当前状态去重，每控制器最多 64 条；广播只供观察，不控制连接或作为独占／安全判断。

attach、手机播放／暂停、媒体命令及蓝牙事件触发音频快照：AudioManager mode、全局 musicActive、媒体／导航系统音量、SCO 标志和 11／12／16 三个车机接收角色聚合连接状态。间隔至少 500 ms，每控制器最多 48 次。聚合状态不能确认连接的是所选手机，musicActive 不能区分播放进程。playingState 为 event_only，profileAvailability 为 unverified；没有事件或 disconnected 不证明角色可用／蓝牙未播放。

`oemSelectedSource=unavailable` 明确没有取得原厂当前音源，不用 Android 焦点或设备路由冒充。没有切换／关闭蓝牙、发送 AVRCP、访问其它媒体会话或新增无障碍要求，电话与无线 iAP 能力保留。

## USB

- `USB milestone`：USBMUX、lockdown、carkit、iAP2、系统网卡、等待 AirPlay TCP、原始诊断接管等阶段与累计耗时；异常记当前阶段、类名、是否取消和此前是否接入系统网卡。后者是历史阶段，不是退出时驱动状态。
- 正常网卡：同一已验证接口的 outgoing NS／NA、IPv6 未知协议／无路由计数及增量；接口索引核对、缺失不填零。另计同一监听端口、其它本地地址的 TCP 表行数，标记 `otherRowsOwner=unverified`，不并入本监听的接入数，不输出地址／进程。
- 原始测试：claim 和 alternate 选择结果、接口／alternate 编号、尝试次数；仍仅失败诊断路径接管，正常会话不增加 claim 操作。
- `dhcp4Messages`：UDP／BOOTP 长度、cookie 和有界 option 校验后只累计 option 53 类型：discover／offer／request／ack／nak／other／malformed。无名称、ID、地址或其它 option，不发送 DHCP，也不把该帧当作 IPv4 CarPlay 会话。
- `usbStatus`：在已有通知读取线程分类 CDC link up／down 和上下行速率，原始通知字节不导出。无端点、未知和格式错误分别记录；link up 不证明 IP 可达，保留 `ipReachability=unconfirmed`。

新增结果逐行输出，经过现有脱敏与 700 字符上限。原始测试窗口、首次完整写入前的 20 ms 短超时、负返回值解释、退出释放、重新插拔提示、无 VPN／root／应用 TCP/IP 备用通路的约束不变。见 [NCM 帧测试](USB_NCM_FRAME_DIAGNOSTICS.md)、[系统 USB 诊断](USB_SYSTEM_NETWORK_DIAGNOSTICS.md)。

## 报告与验证

报告头增加实际 `Application buildCode` 和本应用包名，供对齐测试版本及服务端调用者。没有设置／布局变化。

101 项定向测试通过：common 56、shared 45，覆盖错误分类／脱敏、服务不可读／不可见、蓝牙匹配／去重／关闭／预算、注册／回调／播放与控制器隔离、DHCP 类型与截断、CDC 长度及无符号速率、TCP 地址范围、IPv6 计数、NTB 分段接收及原始测试释放。没有运行全量 CI。新增日志仍须新包实车取证，不能将诊断或单元测试描述为已修复车机问题。

# 蓝牙自动暂停与 CarPlay 切歌跳页：后续修正

2026-10-19。用户补充当前实测为 **构建 86，蓝牙播放后自动暂停**；没有提供构建 86 的新诊断文件。此前构建 74 的自动暂停、构建 85 的音乐链路断开，以及本轮用户反馈分别记录，不当作同一时段的日志。

本次版本为 **主应用 0.1.1 / 91、授权组件 6**。源码修正阶段按用户要求未打包测试；随后用户明确要求“构建测试 apk”，已完成主应用构建、组件签名与打包校验，结果见文末。未运行单元测试或完整 CI，尚无实车验证。

## 可以确认的缺陷与改动

### 原厂媒体中心恢复

现有按键转发成功和跨入口去重不能阻止原车同时处理按键。参考固件的输入接口登记的是观察者；此前 IHU353G 报告中的原厂媒体 SDK 在注册前一直 `ready=false`，没有注册 token 或音源归属。这是切歌跳页仍未解决的关键边界，不能用立即拉回 CarPlay 页面掩盖。

`PrefaceMediaCenter` 原先在 `onReady(false)` 将真实播放请求标为已处理。若稍后服务恢复，即使 CarPlay 仍播放且持有焦点，新 token 注册后也不会申请音源。现在保留尚未处理的真实播放请求，以及服务中断前已取得音源的连续播放恢复意图；服务就绪、完成新 token 注册后只申请一次。暂停、失去焦点／会话、通话或语音优先禁止媒体命令、断开或替换会话都会使旧恢复意图失效。元数据更新、轮询及重复 ready 不生成播放请求，播放申请被拒绝不会循环重试。

应用启动初始化失败后，原先还会永久保留失败的媒体中心实例。新增非蓝牙控制器连接时允许对明确失败的初始化重试一次，仍检查 SDK 的真实 ready、注册与播放返回值；已注册或仍等待就绪时不重复初始化，旧初始化回调不能作用于替换后的后端。调用 SDK 多次初始化的实车结果仍待核对，不能保证厂商授权拒绝会因重试而消失。

### 蓝牙音乐恢复

主应用查找 `BluetoothA2dpSink.connect(BluetoothDevice)` 失败时，现有恢复实现没有可用的替代调用方。参考固件中该方法实际存在；普通主应用的 `NoSuchMethodException` 不能区分非 SDK 访问限制与实际 IHU353G 实现差异。

在已存在的独立平台签名授权组件中增加限定的音乐连接服务，作为主应用蓝牙输出模式下直接连接接口不可用时的候选后端。只接受同用户、指定包名且签名匹配的 CarPlay 调用，主应用也验证补丁身份、版本、平台证书及服务状态。只尝试连接所选、仍已配对的 iPhone 的 A2DP sink；存在其他音乐手机或通话时拒绝操作。不打开蓝牙总开关、不配对、不操作 HFP/SCO、不切换原车音源、不提供通用反射或任意命令，也不借此增加主动断开路径。

连接请求接受仅表示系统受理；仍通过实际 CONNECTED 查询确认。取消、切换路线、会话结束或代理断开时释放绑定及延迟任务。迟到的服务或 profile 回调不再连接旧手机。匹配手机手动恢复后，也会核对真实连接状态并清除恢复义务。没有每秒无限重启连接窗口。

使用此候选后端需要用户把主 APK 内置的**组件 6**保存到 Download/DiPlay，并通过文件管理器手动覆盖本仓库旧组件。安装流程不变；已有原厂／未知同名组件、API 或平台签名不匹配时保留，不自动安装或更换主应用签名。

### 蓝牙音频协商与暂停取证

蓝牙输出已撤下音频 feature、格式及延迟，并拒绝 CarPlay 音频 SETUP，却仍要求接管主音频资源 2。现在蓝牙输出只请求屏幕资源 1，CarPlay 音频仍请求屏幕和音频两项；显示、触摸和方控公告保持原流程。

这是消除声明不一致，**不作为自动暂停根因已确认的结论**。构建 74 在未接管资源 2 的状态下也发生过自动暂停，所以只改这一项不足以证明解决构建 86 的问题。本轮保留原车方控／焦点归属，不向手机循环发送播放抵消暂停，也不在连接状态未知时宣称蓝牙已可播放。

新增 `modesChanged` 的固定字段诊断，记录屏幕、主音频、通话和语音资源的数值／布尔状态，忽略任意文本和设备身份；配合当前手机的实际 A2DP 播放变化，区分 iPhone 状态变化和车机音乐真正开始／停止。

## 证据与适用范围

参考仍为 **IHU519G / FS11A1 / Android 9 API 28 / 构建 327**；OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。用户日志设备为 IHU353G，静态证据不替代这台车机实测。

- 输入观察者证据见 [固件分析](PREFACE_FIRMWARE_327.md#输入观察者不是第三方独占接口2026-10-14)，`services.vdex` SHA-256 `5d19a5aa3f1c4f8b7f90ea18ee44b52e1e3b32ef748d6d9af5c86a1546506571`。
- 蓝牙恢复背景见 [构建 85 完整报告](CONNECTION_REPORT_2026-10-16_BUILD85.md) 和 [此前恢复修正](HOTSPOT_BLUETOOTH_RECOVERY_2026-10-16.md)。
- 自动暂停反证见 [构建 74 报告](CONNECTION_REPORT_2026-10-16_BUILD74.md)。
- [AOSP Android 9 ApplicationInfo.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/core/java/android/content/pm/ApplicationInfo.java#L1580) 对平台签名应用返回无需非 SDK 限制的策略；这解释补丁作为候选调用方的依据，不保证厂商运行时接口和蓝牙服务接受请求。没有修改全局隐藏 API 策略。
- 本地 `catalog/bluetooth-music-bridge-classes.json` 由 `python agent_docs/firmware/inspect_bluetooth_music_bridge.py` 生成。`boot-framework.vdex` SHA-256 `3b40cf31d181d325410c8efb1c1983c038c7c2a09ea7222572133b2294a9b08b`：BluetoothA2dpSink 类偏移 1612216，connect 代码偏移 5319852，disconnect 5320050，isA2dpPlaying 5320392；ApplicationInfo 的 isAllowedToUseHiddenApis／getHiddenApiEnforcementPolicy／isSignedWithPlatformKey 偏移分别为 5525074／4146950／4146760。优化指令只保留原字节，不猜测其调用目标。
- 同一镜像提取的 `system/app/Bluetooth/oat/arm64/Bluetooth.vdex` SHA-256 `f2275b8926c61962c32dba434299cdd3b136fe16b2900a99b30c418346df65ba`：A2dpSinkService.connect 代码偏移 1422466 包含 BLUETOOTH_ADMIN 权限检查，getConnectionState／getConnectedDevices／isA2dpPlaying 偏移 1423080／1423166／1422598 包含 BLUETOOTH 检查；Binder connect 偏移 1422066。参考类没有 setConnectionPolicy，未添加对此固件无效的现代 API 回退。

OpenAPI 服务端缓存另确认，系统应用快捷准入检查的是 ApplicationInfo 系统／更新系统标志，其他调用者走 license 校验，并核对实际 Binder UID／PID／包名；手动安装的平台签名补丁不自动拥有系统应用标志。因此本轮补丁只代办蓝牙音乐连接，不替主应用冒用媒体身份或绕过媒体准入。证据为 `agent_docs/apk-reference/netease-track-service/decompiled/sources/com/ecarx/sdk/openapi/f/f.java:124–126,225–233`、`f/c.java:64–69`、`f/e.java:181–186`，输入 APK SHA-256 及限制见 [服务端对照](NETEASE_TRACK_SERVICE_REFERENCE.md)。

源码修正阶段完成静态审阅，准备了回归测试源码但未执行。后续实车需分别确认：同手机 A2DP／AVRCP 已连接、持续播放不自动暂停、一次方控只切一曲、CarPlay 音频 `ready=true → registered → request-play-accepted` 后不跳原车页面，以及主动选择收音机／通话／断开后的归属。若始终 ready=false，仍需实际厂商拒绝原因，不能声称方控接管已修复。

## 按用户要求构建的测试包

2026-10-19 执行 `:mobile:assembleDebug` 成功，未追加单元测试、主应用全量 Lint 或完整 CI；授权组件 Release 依赖自身的 vital 检查随构建正常执行。包名 `com.shihab.diplay.hudtest`，实际版本 **0.1.1 / 91**，交付文件 `mobile/build/outputs/apk/debug/CarPlay-Preface-0.1.1-91-debug.apk`，25,571,869 字节；Gradle 原始 `mobile-debug.apk` 和输出元数据保留。

- 主 APK SHA-256：`08aa77d8ada2f02b06776f03450fd32e32d7a610544e146adc71ab1388eb6926`。
- 主 APK 证书 SHA-256：`75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`，沿用仓库现有证书。
- 内置组件为 `com.android.vpndialogs`、`preface-android9-6 / 6`、min/target API 28，非调试包；SHA-256 `b699a6e739f86f23cd1fc4ac1c4b2c49eb9bf2f89fa01e7352fc056eec3d10b7`。
- 组件证书 SHA-256：`c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`。主包内 `assets/authorization/authorization.apk` 与本次独立签名组件字节一致，`manifest.json` 的哈希、包名、版本和证书一致。平台签名输入及主应用签名文件均未打包。
- 已核对 APK 中存在本轮蓝牙客户端、限定服务、资源状态诊断和三项通用授权管理的编译类。签名与打包校验不等于这些功能已通过运行测试。

构建后 `adb devices -l` 没有在线设备，未安装，也未启动模拟器。构建日志、签名／manifest 核验和构建记录保存在本机忽略目录 `agent_docs/bluetooth-wheel-build91/`。车机使用新连接代理前，仍需从主应用导出组件 6，由用户在文件管理器手动覆盖本仓库旧组件；不自动安装授权补丁。

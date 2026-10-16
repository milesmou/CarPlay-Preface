# GeelyPlay v0.2.20.1 蓝牙音频流程移植

2026-10-16；本仓库应用版本 0.1.1，构建号 74 → 75。用户最初要求参考 APK 0.2.19.8，随后指定本次源码版本。USB 在确认参考版使用 VPN/TUN 后，用户明确要求“先跳过usb连接”，本轮没有更改 USB 实现。

## 可复核来源

- 仓库：[huangwits/DiPlay-Preface](https://github.com/huangwits/DiPlay-Preface)，[发布 v0.2.20.1](https://github.com/huangwits/DiPlay-Preface/releases/tag/v0.2.20.1)。固定源码提交 `e59ca212150175bfd804701901252084a42f7caf`，标签对象 `d530e17f6582defbfec3c903fd3f5e7c0cca9964`。
- [BluetoothAudioHandoff.kt](https://github.com/huangwits/DiPlay-Preface/blob/e59ca212150175bfd804701901252084a42f7caf/shared/src/main/java/com/shilapi/xcertplay/vehicle/BluetoothAudioHandoff.kt)：接收端 profile 11、按手机地址限定交接、共享恢复记录、每秒播放查询、五秒归还窗口。缓存文件 SHA-256 `9ac792dd68012551ae31fa96d1190d7e0066fce6c3370cdba945aa10b4d53ef0`。
- [CarPlayController.kt](https://github.com/huangwits/DiPlay-Preface/blob/e59ca212150175bfd804701901252084a42f7caf/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)：会话活动／结束、`disableBluetooth` 与 `configureBluetoothAudioHandoff` 的主线程联动，以及手机地址冲突检查。
- [AirPlayInfoPlist.kt](https://github.com/huangwits/DiPlay-Preface/blob/e59ca212150175bfd804701901252084a42f7caf/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt)：蓝牙模式仍包含显示 1、音频 2 两项资源，但不公告音频格式／延迟。缓存 SHA-256 `93ea0888e7f8c5e46b9ac278e798694a67fe17a4a287510136fc9d447f2299e3`。
- [AndroidMediaSink.kt](https://github.com/huangwits/DiPlay-Preface/blob/e59ca212150175bfd804701901252084a42f7caf/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt) 及 `AudioFocusCoordinator.kt`：音频归属反馈控制器、实际原车蓝牙播放反馈 sink。只移植这些与本轮目标直接相关的联动，不覆盖本仓库的全部音频路由实现。

原始源码缓存：`agent_docs/upstream/huangwits-v0.2.20.1/`，Git 忽略；保留源文件原有 carlito 归属注释。上述是参考项目代码证据，不是星瑞 E02 固件或本车验证结论。

## 本仓库最终行为

1. 无线选定手机后保存本次实际蓝牙地址；AirPlay 会话活动时，为该手机申请 A2DP sink 代理。手机交接命令中的地址如与所选手机冲突，拒绝处理；未配对的设备不接管。所有延迟任务再次检查当前会话、控制器关闭状态，旧会话不操作新会话。
2. 开启现有“音乐和方控走原车蓝牙”时，接收端代理不主动断开原车音乐，也不因 iPhone 的 `disableBluetooth` 命令断开 A2DP。该命令仍按现有时序关闭应用自己的引导 RFCOMM／CSM；不关闭蓝牙适配器，不操作 HFP／SCO。
3. 实际音乐播放以匹配手机的 `isA2dpPlaying` 或已连接手机的已知播放状态广播判断，连接本身不算播放；反馈到当前 sink。查询接口不可用时记录原因，不假装已播放，也不凭查询失败更换声音路径。
4. CarPlay 输出模式按获得的音频焦点归属或当前手机的显式交接命令尝试将音乐从原车 A2DP 交给 CarPlay。调用隐藏接口失败则记录并保留现有路径。系统处于通话／语音通信模式时延后交接，轮询在通话结束后重新检查；始终只操作音乐 profile。
5. 退出、归属交还或会话替换时，仅尝试恢复应用自己断开的、仍配对的同一部手机。其他仍持有 CarPlay 音频归属的同手机会话阻止提前恢复。处于断开中时每 250 ms 重查、最多五秒；不强制打开蓝牙、不重新配对、不恢复其他设备。
6. 按参考版恢复 `/info` 两项资源的声明；蓝牙模式继续移除音频 feature 位、格式／延迟／缓冲公告，并拒绝音频 SETUP。显示、触摸与媒体 HID 公告保留。此项是对照复现，不能仅凭静态声明认定已解决 iPhone 自动暂停。

## 有意保留的 E02 差异

参考项目分别读取 `car_bluetooth_audio`（撤下 CarPlay 音频）和 `vehicle_audio_routes.bluetooth_output`（交接管理与原车播放反馈）。后者默认 false，主应用源代码没有设置调用；二者并不自动绑定。本仓库沿用现有 `car_bluetooth_audio` 作为明确的蓝牙输出选择，在控制器和 sink 中使用同一个连接时快照，避免界面选了蓝牙却仍按另一布尔值接管 A2DP。保存设置只影响下次连接。

参考项目还以 `GeelyFactoryCarPlay`、统一媒体输出及特定路由配置影响焦点和交接。本轮不引入 E01 型号识别、系统蓝牙组件维护、文件替换、激活校验、原包签名或其他车型分支；CarPlay 输出按实际焦点或手机命令交接，不套用参考 E01 配置存在即持续接管的条件。本仓库媒体／导航默认流 3／12、用户自定义路由和蓝牙方控防重复逻辑保留。

既有焦点协调器增加归属通知及已确认原车音乐播放时仅让出媒体的行为；通话／Siri 音轨不因此静音。未将参考项目整套原厂混音器或麦克风管理器整体替换进来。本轮不改界面布局。

## 验证与实际限制

定向验证覆盖 A2DP 同手机交接与恢复、多个所有者交替、通话延后、取消配对、非目标手机事件、查询不可用、迟到会话命令、连接时配置快照、音频协商、焦点与既有方控／主机回归。不运行完整 CI、全量 Lint 或其他应用模块打包。

最终 **158 项定向测试通过**：shared 67 项（交接 14、协商 18、焦点 33、音频流隔离 2），common 91 项（会话交接 3、主机设置 53、原厂媒体初始化 9、固件接入回归 26）。初轮两项重叠代理测试和一项会话替换测试暴露了 Robolectric 对同一个代理对象关闭时通知全部监听者的模拟限制；改为模拟 Android 每个调用者独立的代理对象后，最终上述集合全部通过。

`:mobile:assembleDebug` 成功，保留原始 APK 和输出元数据；交付文件为 `mobile/build/outputs/apk/debug/CarPlay-Preface-0.1.1-75-debug.apk`。APK SHA-256：`8d6dead90a01fd069b63ecfe57931828951bc8ed3dcec616a3b372e603d9537f`；签名证书 SHA-256：`75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`，与现有发布签名一致。`adb -s emulator-5554 install -r` 返回 Success，覆盖安装保留数据。公开文件检查与忽略 CR 的 `git diff -w --check` 通过。

用户尚未在同车／同 iPhone 实测参考 APK。构建 74 报告中的自动暂停根因仍未证实；新包需要实车验证连续播放、暂停后恢复、切歌、手动断开重连、导航播报及通话恢复。模拟测试与构建不能证明车机开放 profile 11、隐藏接口可调用，或此流程在 IHU353G 已可持续播放。参考 IHU519G/FS11A1 构建 327 的 OTA SHA-256 仍为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`，本轮不新增其固件可用性结论。

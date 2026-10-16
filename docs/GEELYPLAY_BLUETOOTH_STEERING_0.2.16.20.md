# v0.2.16.20 与 v0.2.20.1：蓝牙设置和切歌对照

分析日期：2026-10-16。用户提供旧版链接，并补充反馈具体指方向盘切歌。
反馈车辆和“新版”的精确版本未知；本轮与此前参考的 v0.2.20.1、当前本仓库实现比较，不能外推到所有后续版本。

## 来源与核对范围

| 项目 | v0.2.16.20 | v0.2.20.1 |
| --- | --- | --- |
| 固定源码提交 | `afb6af2068493e69b3d37cfd4486a4277fb4ed87` | `e59ca212150175bfd804701901252084a42f7caf` |
| 正式 APK | DiPlay-Preface-v0.2.16.20.apk | GeelyPlay-v0.2.20.1.apk |
| 包名 | com.shihab.diplay.preface | com.shihab.diplay.preface |
| versionCode | 64 | 83 |
| minSdk / targetSdk | 22 / 37 | 22 / 37 |
| APK SHA-256 | `f70e014d17a6a052e464e900ea3aa99d16c9f9fd870cdead77a014a175996af0` | `7a3618653d8cb9c17b39abd4dfe665e023fd5cf47414097a015dec75e84eafb1` |

两包哈希均与 GitHub Release API 的 digest 一致。旧标签对象为 `6bd833fba945fc7834318c4eda20d400b300ce4e`，
解引用提交如表。旧源码以 git archive 缓存，未切换或改写当前工程。

来源：[旧版发布](https://github.com/huangwits/DiPlay-Preface/releases/tag/v0.2.16.20)、
[新版发布](https://github.com/huangwits/DiPlay-Preface/releases/tag/v0.2.20.1)。
发布说明定位 Android 5.1 的 E01，不是本仓库的星瑞 E02 / Android 9 实车兼容性证明。
本轮没有安装、运行参考 APK 或执行其蓝牙维护组件，也没有解密维护载荷。

源码、APK、aapt2 资源表、反编译及校验留在本机忽略目录 `agent_docs/upstream/`、
`agent_docs/apk-reference/geelyplay-0.2.16.20/` 与 `geelyplay-0.2.20.1/`。
JADX 1.5.6 分别报告 28 / 29 处错误；有错误的方法不作为完整精确反编译结论。
关键分支同时依据固定源码、APK 中可复核的调用和资源值，不把全部反编译输出视为可信还原。

## “蓝牙通道”在两版中不是一个设置

| 设置或行为 | 旧版 v0.2.16.20 | 新参考版 v0.2.20.1 |
| --- | --- | --- |
| 原厂蓝牙 / 安卓蓝牙 | `e01_goc_enabled`，选择手机控制连接后端；默认 false，用户可选原厂模式 | 保留模式选择，未保存时可按发行资源/服务能力默认原厂模式；有效旧选择仍优先 |
| 媒体/导航通道编号 | 0–20 的本地音频流/usage 路由，默认媒体 0；不是 AVRCP 控制开关 | 继续是本地声音路由 |
| 各角色输出设备 | 枚举车机 AudioDeviceInfo，交给 AudioTrack 的 setPreferredDevice；API 22 使用默认设备 | 保留该类输出路由 |
| 不提供 CarPlay 音频 | 主应用配置没有传入 disableAudioOutput=true；没有 car_bluetooth_audio 设置键 | 增加 car_bluetooth_audio 读取，影响 AirPlay 音频公告与接收 |
| A2DP 交接模式 | 由原厂识别、焦点归属或手机 disableBluetooth 命令决定 | 另增 vehicle_audio_routes/bluetooth_output，影响交接、焦点与播放反馈 |

旧版 `DiPlayActivity.kt:1706–1717`、`VehicleAudioRoutes.kt:23–37` 与
`CarPlayHostActivity.kt:3190–3212` 确认前几项。DEX 字符串核对显示旧 APK 中没有
`car_bluetooth_audio`、`bluetooth_output` 或 `factory_steering_keys`，新 APK 三者都有。
搜索“没有设置键”只针对这几个明确键，不能当作不存在任何蓝牙逻辑的证明。

因此旧版选“原厂蓝牙”不能等同于本仓库现有“音乐和方控走原车蓝牙”。
旧版选中某个输出设备仍是 CarPlay 音频在车机 AudioTrack 上播放；不能据此认定手机正在直接通过 A2DP 播放。
本地音频通道可能通过焦点和音源影响按键归属，但通道编号本身不建立 AVRCP 连接。

新参考版的两个音频布尔项没有自动绑定：`VehicleAudioOutputMode.bluetooth()` 默认 false，
源码没有其 setter 的调用；`car_bluetooth_audio` 则在主机配置时读取。
找到后者的持久化方法和文案不等于设置页实际接入了一个按钮。
本仓库此前已经统一为现有蓝牙输出选择的连接时快照，详见 [移植记录](GEELYPLAY_BLUETOOTH_0.2.20.1.md)。

## 最明确的切歌差异：新发行包换了按键入口

旧版 [CarPlayMediaKeys.kt](https://github.com/huangwits/DiPlay-Preface/blob/afb6af2068493e69b3d37cfd4486a4277fb4ed87/common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt)：

- 第 115 行加载用户启用的 SteeringProfile；配置可以描述 Android、ECARX/OneOS/vehicle_bridge、广播或日志事件。
- 第 207–226 行按已保存配置、桥接安装状态和开关申请厂商键；默认列表包含 200087/200088、210005/210006。
- 第 253–261 行把相应厂商事件转换为 next/previous；第 586–604 行继续发送 CarPlay 媒体 HID。
  桥接服务必须实际安装、允许并交付事件，才有运行效果。

新参考版 [FactorySteeringKeys.kt](https://github.com/huangwits/DiPlay-Preface/blob/e59ca212150175bfd804701901252084a42f7caf/common/src/main/java/com/geelyplay/preface/FactorySteeringKeys.kt)
以及 [mobile 构建配置](https://github.com/huangwits/DiPlay-Preface/blob/e59ca212150175bfd804701901252084a42f7caf/mobile/build.gradle.kts#L245)：

- `config_factory_bluetooth_default` 在 E01 发行构建从 false 变为 true；新正式 APK 的资源表也确认 true。
- 同一资源布尔值又被 FactorySteeringKeys.enabled() 使用；它不读取用户的 e01_goc_enabled 模式选择。
- 新 `CarPlayMediaKeys.kt:124` 在该值为 true 时令旧 SteeringProfile 为 null，正常播放的厂商桥接默认分支也受此值排除。
  学习状态仍可能请求桥接，不能将其表述为整个桥接代码已经删除。
- `FactorySteeringKeys.kt:8` 默认下一曲只接 Android 87，上一曲接 88/89；新映射保存于独立的 factory_steering_keys。
  旧学习配置没有在这条分支中继续加载，原有厂商百万范围键码也不能直接放进 1–1000 的新学习范围。
- 新 `CarPlayMediaKeys.kt:424` 还直接跳过被动日志/广播中的几类厂商媒体键，避免与原车音乐卡片并行处理。

APK 对应证据：`resources.txt:167–168` 为 true；`defpackage/bj.java:867,980` 在该值为 true 时不加载旧配置；
`:462` 排除正常厂商桥接分支；`:770` 跳过媒体日志/广播；`ei.java:250–260` 进入新 Android 映射；
`gh0.java:1000` 使用新映射存储。旧 APK `defpackage/zh.java:449` 可见原厂商键列表。

**可以成立的回退条件**：同一车机以前依赖旧学习配置或厂商桥接，升级后没有对应 Android 87/88 按键事件，
新发行包会失去原来能切歌的入口。这个条件有源码和正式 APK 支持，值得优先核对。
但反馈车辆、具体新版及运行事件未提供，不能宣布已经确认那位用户的实际根因。
简单切回“安卓蓝牙”也不会改变编译到包里的上述方控资源值。

## 与本仓库和本车报告的关系

本仓库没有引入 FactorySteeringKeys 这条 E01 默认分支。
CarPlay 通道继续尝试 E02 观察者/广播/窗口按键转 HID；用户完整报告中 NEXT 已发送成功、广播已去重，
随后打开多媒体页属于接管/音源归属问题，不是完全没有切歌指令。

本仓库选择蓝牙输出时则明确把媒体方控交给原车，不创建竞争的媒体焦点或重复发送 HID。
这条路径要验证的是手机与原车的音乐链路及 AVRCP 控制链路，而不只是电话 profile 已连接。
[AOSP 蓝牙服务说明](https://source.android.com/docs/core/connect/bluetooth/services)区分 A2DP 和 AVRCP；
后者负责播放控制。用户构建 85 报告里两者均为 0、电话 profile 为 2，与原车蓝牙未恢复的现象一致。

旧版 `BluetoothAudioHandoff.kt:88,110` 也使用同样的隐藏 disconnect/connect 反射，
不能用降级来证明或解决本车的 NoSuchMethodException。
刚完成的 [热点与蓝牙恢复修正](HOTSPOT_BLUETOOTH_RECOVERY_2026-10-16.md)仍应保留。
恢复音乐连接也不等于已经验证 AVRCP 切歌；下一轮同车测试应分别记录播放与一次按键的控制结果。

本轮仅比较并记录，没有修改应用行为、同步上游代码、构建或安装 APK，因此不调整应用版本或上游基线。
参考 OTA 仍是 IHU519G/FS11A1 构建 327，SHA-256
`f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`；本轮不增加其固件接口可用性结论。

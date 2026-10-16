# GeelyPlay 0.2.19.8 蓝牙链路对照

本文件保留构建 74 阶段的 APK 静态对照。随后用户指定 v0.2.20.1 源码并要求移植，构建 75 的最终实现及有意保留的 E02 差异见 [新移植记录](GEELYPLAY_BLUETOOTH_0.2.20.1.md)。下文“当前仓库”指当时的构建 74。

2026-10-16。用户再次提供 `D:/Downloads/GeelyPlay-v0.2.19.8.apk.1`，文件 SHA-256 与此前方控分析缓存完全一致：
`01852eec09a7710448f2247cc65105320513a086c3150520ddda091735286de5`，4,802,315 字节。
包 `com.shihab.diplay.preface`，版本 0.2.19.8 / 78，minSdk 22、targetSdk 37，未声明 system shared UID。
用户本轮明确：尚未在同一台车机和同一部 iPhone 实测该 APK。不能据文件名或默认开关认定其蓝牙音乐／方控已在本车可用。

本次复用本地 JADX 1.5.6 缓存；此前全量反编译有 29 个错误，不能把所有恢复出的分支当成精确源码。Manifest 的缓存 SHA 与原目录 catalog 一致，本次新增证据校验见 `agent_docs/apk-reference/geelyplay-0.2.19.8/bluetooth-review-2026-10-16.json`。原包、反编译实现、加密资源及逐文件索引留在 Git 忽略目录。

这是独立 APK 的静态证据，不属于参考 IHU519G / FS11A1 / 构建 327 的固件结论，也没有运行、安装、解密或执行附带兼容组件。

## 可见的完整链路比当前实现更广

| 环节 | APK 证据（路径以缓存 decompiled/ 为根） | 与当前仓库的关系 |
| --- | --- | --- |
| 撤下 CarPlay 音频公告 | `sources/com/shilapi/xcertplay/CarPlayHostActivity.java:6606` 读取 `car_bluetooth_audio`，`:6790` 传入配置；`sources/defpackage/kh0.java:2706–2740` 按该标志改 features 并省略 audioFormats／audioLatencies | 当前的蓝牙方案包含这一核心行为 |
| A2DP 接收端代理 | `sources/defpackage/fc.java:317–365` 取得 profile 11，监听 A2DP sink 连接状态，按需要监听播放状态 | 当前主要记录聚合状态／事件，没有同等的代理交接管理器 |
| 实际播放查询 | `fc.java:175–215` 反射 `isA2dpPlaying(device)`；`dc.java:39–44` 在带播放回调的实例中每秒查询 | 不应把“已连接”当成“正在播放”；接口失败仍需独立处理 |
| 把音乐交给 CarPlay | `fc.java:68–119` 在所有权条件满足时，对匹配且已配对的手机反射调用 `disconnect`，记录自己发起的交接 | 当前没有这套主动操作 A2DP 的逻辑；该动作方向是移交至 CarPlay，不等于保持蓝牙音乐 |
| 归还先前断开的连接 | `fc.java:222–289` 根据共享记录和现存所有者，尝试反射 `connect`，安排有期限的重试；close 也参与归还 | 只可按完整所有权／生命周期理解，不能单独复制 connect 或 disconnect |
| 音频归属联动 | `CarPlayHostActivity.java:2594–2598,2856–2858` 连接 sink 到控制器的 `setBluetoothAudioOwnership` 回调；`di.java:356–359`、`pf.java:21–33` 调整 A2DP 交接条件 | 当前没有对应的 sink 所有权回调链 |
| 原车蓝牙播放反馈 | `hf.java:85–99` 在 `vehicle_audio_routes.bluetooth_output` 路径创建带播放回调的管理器；`kf.java:82–89`、`di.java:368–385` 反馈给 sink 的 `setNativeBluetoothPlaying` | 这是另一项连接时配置，不能和 `car_bluetooth_audio` 混为同一个开关 |

`kg.java:190` 读取的 `vehicle_audio_routes.bluetooth_output` 默认 false；`hf.java:50–59,85–99` 还结合车辆音频配置、会话和归属条件决定是否接管。默认 factory 产品标志为 true，不证明上述每个路径都会开启。不能仅凭类名存在就认定它们是蓝牙方案下必经或已成功执行的路径。

## 音频资源声明也有实际差异

`kh0.java:2723` 无条件在 modes.resources 中加入显示 1 和主音频 2；即使关闭音频格式公告，这两项仍在。
当前 `AirPlayInfoPlist.modes(config)` 在 `disableAudioOutput=true` 时只声明显示 1，这是构建 69 的声明一致性调整。

因此当前实现与原 APK 不等价，不能说已经完全照搬它。原 APK 的“无音频格式但仍申请主音频资源”也不能仅凭静态代码认定就是正确方案，或者认定它一定导致自动暂停。若要按原行为复现验证，必须明确对照这项差异及 A2DP 交接，而非只复制 UI 文案或一个布尔值。

## 原厂服务与 E01 兼容组件需分开判断

- `g10.java:33–35,95–98` 含对 `ecarx.bluetooth.service` 下 HFP／A2DP／Adapter 服务的显式绑定；`p4.java:149–163` 使用该包的 class loader。这是原厂实现依赖，不是普通 Android SDK 承诺可用的接口。
- `s10.java:115–176` 具有标为 E01 的 `manage.enc`、`component.enc`、原厂文件状态校验及可执行兼容组件落盘／维护流程。可见它存在，不等于主 APK 的所有蓝牙功能都依赖它，也不等于参考 E02 已验证可用。此前与本轮均未分析加密载荷的内部实现。
- 本仓库仅服务星瑞 E02，不能将其他平台的专属维护、原厂蓝牙文件替换或车型识别分支整体引入。通用的接收端播放状态、所有权联动、交接恢复设计可作为 E02 实现参考，接口存在、权限、当前会话、通话和实际播放结果检查仍要保留。

## 对用户问题的结论

可以按可复核的完整行为重新实现蓝牙链路；当前确实只覆盖其中一部分，不能以“关闭 CarPlay 音频”代表整个参考方案。
但没有同车成功样本，且包含有条件的隐藏 API、原厂服务和 E01 维护路径，不能保证原样复制整包就能解决构建 74 的播放后自动暂停。
本轮只完成对照及记录，没有修改运行代码、构建 APK、安装原包或提交推送。方控独占和避免原车跳页仍须分别验证，相关可见实现见 [原方控对照](GEELYPLAY_STEERING_REFERENCE.md)。

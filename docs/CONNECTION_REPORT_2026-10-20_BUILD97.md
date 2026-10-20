# 构建 97：E 盘日志、音频切歌与 VPN 授权

2026-10-20。用户提供 E 盘完整目录及 VPN 报错照片。本轮源码为主应用 **0.1.1 / 98**、独立授权组件 **9**；只执行定向单元测试，未打包、签名或安装新 APK，未进行新代码实车验证。

## 结论与处理状态

| 问题 | 本次证据 | 处理与边界 |
| --- | --- | --- |
| VPN 提示 `SecurityException: VPN service missing` | 照片的报错与组件 8 校验一致；构建 97 APK 中 VPN 服务存在、受 BIND_VPN_SERVICE 保护、exported=false | 组件错误要求 exported=true。组件 9 允许系统显式绑定的非导出服务，继续校验启用状态、调用身份、平台权限、用户限制及其他 always-on VPN。不把窗口修正当作 TUN／USB 已验证。 |
| 蓝牙播放后自动暂停 | 原车蓝牙进程两次记录 audioFocus=0，随后发送 AVRCP PAUSE | 主应用新增一次原车 MediaBrowser 的 prepare 请求，由蓝牙进程准备自身焦点；不发送 PLAY、NEXT 或周期性抢焦点。仍待实车确认焦点实际获准、持续播放与切歌。 |
| CarPlay 音频切歌跳原车播放器 | 原车 OpenAPI 明确拒绝本应用 mediacenter 权限；原厂媒体中心同时处理 NEXT，并让原车播放器跳页 | 根因边界已确认，未修复厂商准入。现有系统授权补丁不提供 ECARX license；继续保留真实拒绝，不冒用包名／签名，不直接绕过被拒绝的 SDK，也不强拉回 CarPlay 页面。 |

三项属于不同层：Android VPN 授权、原车蓝牙进程焦点、ECARX 媒体 SDK 准入，不能以其中一项成功推断其他两项恢复。

## 完整目录盘点与输入身份

E 盘包含一个主 APK、一份应用诊断、五个车机日志 ZIP，以及 Android 空标记、空目录和 Windows 卷元数据。已对全部文件盘点和计算 SHA-256，复制业务输入到本机忽略目录，未改写 E 盘内容。五个 ZIP 展开后共 120 个文件、697,199,969 字节；TBox 的嵌套 tar.gz 另盘点 24 个文件。

| 输入 | SHA-256 |
| --- | --- |
| CarPlay-Preface-0.1.1-97-debug.apk | 6539c43c328716550d9abc2a682856b40c0c0aff54cbfadabe878ca12f3475eb |
| DiPlay-20261020-103939-765.txt | 39643033c71ecc9e56cd769f2a242170dfff5796dd747ba7ace08d8fd1ce98e1 |
| mobilelog.zip | 994fdbdb77901c4360b8fbfe33efdc2fcbebe07b4dedbd6c442179b4af6999fe |
| aee_exp_kernel.zip | 1028835221fef4208e4fd0955a7ce09eaa33ef496b7f8d39f7abd1b23eb52796 |
| anrlog.zip | 8c54c5373baa0c70d232a3e33f23f3e46a958092960afabec1d03b8b93d5b2d0 |
| recoverylog.zip | 430789018fbcddbd6fc11cdac0d5c065cb0e700c0b7eca3cf514f37a147c012e |
| tboxlog.zip | db0aa27c6f55d6e23c77ec09e21f61559f16a87651804d65812ed534154b181f |

原 APK 签名校验通过，证书 SHA-256 为 `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`。其内置组件为 `com.android.vpndialogs / preface-android9-8 / 8`、系统共享 UID；实际内置字节 SHA-256 `a31300f258cb1712053fb540d19e9d1cb59f8326465857f15b3e51fc0e5331ed` 与 manifest.json 一致，组件签名校验通过，证书仍为 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`。

本轮关键证据来自 mobilelog 内的原始文本。ANR 文件属于 10 月 5 日；crash buffer 混有 10 月 9、15、19 日及本次日志，最新 10:34:18 崩溃是百度车载输入法，不是 CarPlay。recovery 与 TBox 文本／嵌套文件未检出 CarPlay 包名，未找到能解释本次三项问题的直接证据。AEE 的 12 个 `.dbg` 是专有二进制容器，仅核对目录、历史记录、头部和明文检索，未解码，不宣称已排除其内部存在其他证据。Windows 卷元数据不作为应用诊断。

原始日志包含设备与网络信息，仅留 `agent_docs/vehicle-20261020-build97/`，不入库。`input-manifest.json` 记录 E 盘输入及 ZIP 成员，`content-index.json` 记录展开文件和嵌套清单，专项检索与测试 XML 一并留本地。以下行号针对原始文件，不针对拼接后的检索输出。

## CarPlay 音频：命令发出，但厂商拒绝媒体准入

日志设备为 **ecarx IHU353G、Android 9 / API 28**。应用包 `com.shihab.diplay.hudtest`、构建 97；本次两个会话都使用无线，不能当作 USB 连接结果。

以下原车行号均以 `mobilelog/APLog_2026_1020_103548__8/main_log_1__2026_1020_103948` 为根：

| 时间 | 行号 | 事实 |
| --- | --- | --- |
| 10:36:08.829733 | 55309 | OpenAPI 检查本应用的 mediacenter license。 |
| 10:36:08.837660 | 55362、55365 | 服务端抛出 `No Authorization to operate [mediacenter] for [com.shihab.diplay.hudtest]`，verification=true。 |
| 10:36:08.837894–838963 | 55379–55380 | 应用进程得到 media center binder not ready、onAPIReady(false)。 |
| 10:36:12.703671 | 60132、60135 | 重试仍被同样拒绝。 |
| 10:36:51.943476 | 98544 | 原厂输入服务收到下一曲。 |
| 10:36:52.105735–113124 | 98773–98804 | 原厂媒体中心接收 MEDIA_BUTTON、执行 playCtrlNext，原车播放器收到 next 后处理页面跳转。 |

应用诊断第 348 行，10:36:51.950，记录 `preface-intercept index=4 sent=true`；第 353 行记录随后广播被本应用抑制为 paired-wheel-echo。这证明本应用内的两路去重已工作，却无法阻止原厂进程另行处理广播。第 355、361 行记录界面离开及 stop，与原车播放器启动时间吻合。10:37:10 的第二次按键同样发生跳页。

应用诊断第 364–408 行的正常音频统计中，媒体 AudioTrack 正常推进、writeErrors=0、decoderDroppedTotal=0；持续区间无重缓冲，结束连接附近才出现清理／重缓冲。没有证据支持用扩大音乐缓冲解决这次按键跳页。

**尚缺的是本应用有效的 ECARX 媒体中心准入方式。** 普通 Android 系统权限、平台签名补丁、OpenAPI 服务 exported 或接口存在均不能代替该 license。当前代码继续保留方控转发和真实 SDK 状态，不把此项写成修复完成，也不将原厂蓝牙仍连接误写成 CarPlay 音频未运行。

用户随后追问第三方正常 APK 的参考价值，复核发现此前遗漏了它的随包媒体授权：manifest 接入项及 assets/license.txt 均存在，本地核验确认授权含 mediacenter、匹配第三方包名与签名、不匹配本包身份。这是明确的差异和继续调查的方向；前述拒绝仅证明当前路径失败，不能扩大成“没有任何方案”或第三方没有参考价值。详情及验证边界见 [第三方授权复核](LINKCO_APK_MEDIA_KEYS_REFERENCE.md#2026-10-20-复核此前遗漏的随包媒体授权)。

## 蓝牙音频：原车进程因无焦点主动暂停

同一原车日志：

| 时间 | 行号 | 事实 |
| --- | --- | --- |
| 10:38:58.033042–033086 | 213660–213664 | A2dpSinkStreamHandler 收到 SRC_STR_START，audioFocus=0，停止本地流。 |
| 10:38:58.033223–033958 | 213676–213686 | sendAvrcpPause／Pausing AVRCP，发送 keyCode=70 的按下和释放。 |
| 10:38:58.229477 | 214110 | AVRCP 播放状态变为暂停。 |
| 10:39:08.206307–207189 | 224567–224762 | 第二次播放重复相同的无焦点、暂停命令链。 |

应用诊断第 656–660、675–680 行随后看到 iPhone playing=true→false。蓝牙模式没有创建媒体 AudioTrack 或持有本应用音乐焦点；日志中的 NoSuchMethodException 属于播放状态查询不可用，不能解释成蓝牙未连接。本次 A2DP／AVRCP 实际已连接，仅重新 connect 不能解决原车进程焦点为 0。

### 源码修正

新增 `NativeBluetoothAudioPreparation`，通过公开 MediaBrowser／MediaController API 连接系统蓝牙播放器，调用一次 `transportControls.prepare()`。没有创建本应用 MediaSession、发送播放／切歌、调用不受支持的 SET_STREAM_TYPE，也没有向平台签名代理增加功能。原车播放器仍持有自己的焦点和方控。

仅在蓝牙输出会话、所选 iPhone 是唯一已连接且仍配对的 A2DP 手机、蓝牙开启、音频模式正常时准备。连接回调再次复核；系统服务须启用、导出、为系统应用且与 Android 平台签名匹配。最多等待 4 秒，成功／失败释放本应用绑定；取消、拔线／断开、会话替换及切换归属关闭操作。一个会话只发一次，不随 playing 更新或失去焦点反复准备，不覆盖司机后来选择的收音机。

依据是 [AOSP 蓝牙播放器的 onPrepare 实现](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/8a4d25c29e685cd58171aee24326a9c307e295b3/src/com/android/bluetooth/a2dpsink/mbs/A2dpMediaBrowserService.java)，它让蓝牙服务申请自身焦点。现有参考固件缓存也包含同名导出 MediaBrowserService、onPrepare 和 requestAudioFocus 签名：`system/app/Bluetooth/oat/arm64/Bluetooth.vdex` SHA-256 `f2275b8926c61962c32dba434299cdd3b136fe16b2900a99b30c418346df65ba`，onPrepare 代码偏移 1433640、A2dpSinkService.requestAudioFocus 偏移 1423422。其 quickened 指令没有当作完整反编译实现；本轮是候选修正，不保证实际 IHU353G 实现完全相同或 prepare 即获准。运行日志明确记录 focusConfirmed=false、playbackConfirmed=false。

用户回复尚未测试“先在原车蓝牙音乐播放，再返回 CarPlay”；这仍是区分初始焦点未建立与返回后再次失去焦点的实车验证项。蓝牙模式下原车媒体键是否仍会跳页也需复核；本轮不会阻止原厂播放器自己的页面行为。

## VPN：组件 8 误拒本应用的私有服务

构建 97 实际 APK 的 manifest 中存在 `com.shilapi.xcertplay.network.CarPlayVpnService`，声明 BIND_VPN_SERVICE、exported=false。组件 8 的 installedTarget() 却额外要求 service.exported，故照片所示错误在目标校验阶段就能发生；这不是缺少授权组件或 TUN 建立失败的证据。原车 sys_log 第 52443、52594 行也记录系统成功启动 ConfirmDialog，不能再把当前问题归为授权 Activity 缺失。

[Android 9 Vpn.establish](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/services/core/java/com/android/server/connectivity/Vpn.java#L935) 使用系统身份解析并显式绑定目标服务，检查 BIND_VPN_SERVICE，不要求目标向其他应用导出。因此修正组件校验，保留主服务非导出；服务缺失、未受保护、停用及目标应用不可用仍拒绝。调用包只从 getCallingPackage 获取，确认前后继续复核 UID、版本、证书、平台权限及系统策略，没有自动 app-op 或授权操作。

修正位于**组件 9**；未来打包主应用后，仍需从主 APK 保存新版组件，并经文件管理器手动覆盖本仓库同系统 UID 的组件 8。只更新主应用不能改变车机上已安装组件 8 的校验代码。本轮未生成该新版 APK，也未操作车机授权。USB 的 NCM、本应用限定 TUN、fe80::/64 路由及无线不使用 VPN 的边界保持原实现。

## 验证

60 项定向单元测试通过，失败／跳过均为 0：

- authorization-components：ThirdPartyAuthorizationTest 10 项，Android9VpnAuthorizationTest 6 项。包括非导出服务、保护权限缺失、服务及组件停用、目标身份、策略和授权读回。
- shared：NativeBluetoothAudioPreparationTest 6 项，BluetoothAudioHandoffTest 38 项（API 28／33）。包括一次准备、超时、身份／资格变化、迟到回调、通话、另一手机、CarPlay 模式不进入准备路径，以及原有连接恢复。

未执行完整 CI、全量 Lint、APK 构建或安装；没有界面布局改动，不交付渲染图冒充实车截图。主应用保持 versionName=0.1.1，源码构建号递增为 98，组件递增为 9，既有签名输入和打包流程未修改。

参考固件仍为 IHU519G / FS11A1 / 构建 327，OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。本次 IHU353G 的运行事实单独记录，不扩展成所有星瑞固件的验证结论。

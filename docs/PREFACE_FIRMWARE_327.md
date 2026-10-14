# 星瑞 IHU519G 固件 327：方控与音频分析

分析日期：2026-10-13。输入为用户提供的固件包，本地现名 `23星瑞1.5T固件.zip`，大小 2,519,405,530 字节。
SHA-256：`f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。

## 固件身份

更多工程接口、权限、媒体能力和本地索引入口见 [固件资料索引](PREFACE_FIRMWARE_REFERENCE.md)。

- OTA 目标设备：`IHU519G`；系统型号：`FS11A1`；制造商：`ecarx`。
- Android 9，API 28，构建号 327。
- 指纹：`ecarx/IHU519G/IHU519G:9/PQ2A.190405.003/327:user/dev-keys`。
- 是包含 system 和 vendor 的完整 BLOCK OTA，压缩格式为 Brotli；无需旧版本分区即可重建。

固件镜像、提取的 APK/JAR 和反编译结果都保存在被 Git 忽略的 `agent_docs/firmware/ihu519g-327/`。没有执行升级脚本，也没有向车机刷写固件。

## 音频编号：已由固件常量确认

从 `system/framework/boot-framework.vdex` 的 CompactDex 静态字段读取常量，`android.media.AudioManager` 与 `AudioSystem` 两处结果一致。

| 音频用途 | 常量 | 编号 |
| --- | --- | --- |
| 音乐／媒体 | `STREAM_MUSIC` | 3 |
| 导航播报 | `STREAM_NAVI` | 12 |
| TTS | `STREAM_TTS` | 9 |
| TTS 提示 | `STREAM_TTS_NOTICE` | 11 |
| 提示音 | `STREAM_BEEP` | 14 |

`AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` 同样为 12，但 usage 与 stream 是两种不同的参数；不能因为此固件中数值相同就混用接口。
`vendor/etc/audio_policy_configuration.xml` 引用 `ecarx_audio_policy_volumes.xml` 和 `ecarx_default_volume_tables.xml`，其中导航和媒体分别使用独立曲线。

原厂 `ECarXRVolumeAdjustActionBase` 查询隐藏方法 `AudioManager.getStreamTypeInActive()`，只接受当前流 0、3、9、12，其余回退到媒体 3，再用 `getStreamVolume` / `setStreamVolume` 调节。
方向盘音量键由原厂服务直接处理，不会经本节的媒体广播送给应用。本仓库直接默认选媒体流 3、导航流 12；现按下文的媒体播放状态规则申请音量键拦截，失败时保留原车处理。已有自动路由或自定义音频流设置继续优先。
**此固件的 14 是提示音，不是导航流。**

## 方控事件链路

原厂输入服务为 `ecarx.xsf.inputservice/.InputService`，APK 位于 `system/priv-app/XSFInputService/XSFInputService.apk`。
它通过 ECARX AdapterAPI 接收私有按键，交由 `KeyPolicyImpl` 的按键动作处理。

| 功能 | 系统按键码 | AdapterAPI 按键码 | 短按输出 |
| --- | --- | --- | --- |
| 上一曲 | 304 | 200088 | 标准媒体键 88 |
| 下一曲 | 305 | 200087 | 标准媒体键 87 |
| 音量加 | 301 | 200024 | 原厂直接调节当前系统音频流 |
| 音量减 | 302 | 200025 | 原厂直接调节当前系统音频流 |
| 麦克风／语音 | 298 | 200231 | 原厂语音处理，本次未改动 |
| 播放／暂停动作 | 未在该 KeyMap 的原始码转换表中确认 | 200085 | 标准媒体键 85 |

`ECarXRMediaNextAction`、`ECarXRMediaPreviousAction` 与 `ECarXRMediaPlayPauseAction` 最终调用 `InputManagerHelper.injectInputEvent()`。
该方法对非返回键并不向前台窗口注入事件，而是发出 `android.intent.action.MEDIA_BUTTON` 广播，category 为 `android.intent.category.DEFAULT`，extra 为 `android.intent.extra.KEY_EVENT`。
一次短按生成同一 downTime 的 DOWN/UP 对；切歌长按输出 89/90（快退／快进），当前 CarPlay HID 未实现这两项，应用继续忽略。

自定义方向盘按键可在原厂设置中选“媒体播放／暂停”：`SoftKeyFactory` 的选项值 7 对应 `ECarXSoftKeyMediaPauseAction`，也输出标准键 85。
不要把 200085、350 等不同层的编号直接当作标准播放键，也不要在应用中擅自更改原厂 `soft_key_function` 设置。

## 本仓库接入

### HiCar 对照与切歌接管修复（2026-10-13）

用户报告：方向盘切歌会打开原车多媒体播放器并暂停 CarPlay 音乐。原方案接收的是普通 `sendBroadcast()`，不是有序广播，因此接收或消费本地事件不能阻止其他播放器收到广播。该现象与广播同时被原车处理一致；实际触发进程仍待实车日志确认。

HiCarHMI 的 `HiCarService` 注册 `ECarXInteractiveListener`，回调 `v.onKeyEvent` 包含标准媒体键 87/88 和物理键 304/305 的转换。JADX 对该 APK 共报告 25 处错误，`v.onKeyEvent` 也有控制流恢复警告，不将其反编译分支作为完整行为证明。进一步提取同一固件的 `system/app/ECarXInteractiveService/jar/ecarxinteractivesdk.jar`（SHA-256 `d076176a3bbcced700b0affcf3676c96c3a62aca4ae0c5d188cd00260914951f`），其 `ECarXInteractiveInputManagerServiceUtil.java:26–34` 完整显示向定制 `IInputManager.requestKeysInterception` 请求标准媒体键 87/88 等键，并通过 `IKeyEventObserver.onKeyEvent(boolean down, int keyCode)` 回调；该次 SDK/服务反编译未报告错误。

工程新增运行时输入接口桥接，请求 85/87/88/298（播放暂停／下一曲／上一曲／语音），音量键申请见下文，不拦截电话、静音或方向键。不复制 HiCar APK、原厂 SDK 或系统签名。事务编号从设备的真实接口类读取，不猜测 `IInputManager` Binder 编号。CarPlay 连接并拥有媒体焦点时接管；未申请焦点时允许前台 CarPlay 接管。永久失去焦点、通话、车载蓝牙音频或断开后释放；每 250 ms 检查运行状态，发送前再次检查。缺少接口或权限失败时记录 `DiPlay-MediaKeys` 日志并保留原广播方案。媒体按下重复只发送一次，语音在配对释放后唤起一次 Siri；旧注册的排队回调不能影响新注册。

这是基于构建 327 静态证据的修复候选，尚未验证普通第三方 APK 的接口访问权限、系统拦截是否阻止原车播放器启动及实车长按行为。接口请求返回只记录为 requested，不当作按键已到达或实车修复成功。待验证：前台和后台一次切一曲；暂停后仍可切歌；切回收音机后方控归原车；通话和车载蓝牙优先；退出／断开释放；缺少接口时应用不崩溃。

### 2026-10-14 最新报告后的物理切歌拦截候选

IHU353G 用户报告仍出现切歌成功但打开原车多媒体页。工程在原标准键请求中加入物理 304/305，通过同一观察者处理；扩展请求被拒绝时先释放再回退标准集合，最终失败保留广播处理。映射证据为本文件指定 OTA 的 `KeyMapAdapterApi.java:94–97`。这是新的工程候选，HiCar SDK 的原请求没有这两键；不作为第三方权限或阻止原车跳页的确认。申请、收到按键、焦点和播放／暂停命令现在进入会话诊断，详细时序、报告 SHA-256 与验证限制见 [第四份连接报告](CONNECTION_REPORT_2026-10-14.md)。

### 其他按钮与语音审查

| 按钮／功能 | 同一固件静态证据 | 工程处理 |
| --- | --- | --- |
| 播放／暂停 | HiCar SDK 请求 85，HMI 回调转发 85 | 随媒体归属请求，转 CarPlay PLAY_PAUSE；重复 DOWN 不重复触发 |
| 语音按钮 | SDK 请求 298；输入服务 `ECarXRVoiceAssistAction` 发有序广播，事件码 200231、动作 0/1 | 配对释放唤起 Siri；另注册语音广播作为接口不可用时的回退，仅在会话符合条件且 Siri 请求被接受后中止有序广播；两路 400 ms 去重 |
| 音量加减 | HMI 的 24/25/301/302 分支未调节音量；原车 `ECarXRVolumeAdjustActionBase` 实际调节当前 0/3/9/12 流，不适用时退回媒体 3 | HiCar 沿用原车；本项目按下文申请音量键拦截，媒体／导航默认流保持 3/12，通话时释放 |
| 电话、挂断 | HiCar 根据自身连接和 `callState` 决定 5/6/299 转发 | 保留原车；没有以 HiCar 通话状态冒充 CarPlay 通话状态 |
| 方向／确认 | SDK 请求 19–23，但转换表 21/22 都返回 RIGHT，不能照搬 | 保留原车；已有 CarPlay 自定义摇杆机制继续使用 |
| 快进／快退、静音 | HMI 有 89/90/91/164 分支；当前 CarPlay 媒体 HID 没有对应动作 | 不请求这些键，不用切歌或暂停冒充其动作 |
| 语音唤醒词 | HMI 有 `onVoiceWakeup` 回调，但 `VrVoiceObserverUtil.init/starObserver/stopObserver` 全部为空 | 不移植空实现，不宣称支持唤醒词 |

语音广播参数由 `ECarXRVoiceAssistAction.java:39–49` 与 `ECarXKeyActionBase.java:17` 确认。FS11A1 的长按路径回到短按广播，所以广播回退不能据动作 0 推断短按时长；物理接口可以使用按下／释放配对。HiCar 的唤醒回调带有其他车型示例文本，不将其内容当星瑞车辆数据或 CarPlay 协议。新语音按钮、短／长按和原车助手的交接均待真车验证。

- 本仓库专门面向星瑞 E02，默认直接使用此方案，不再根据产品、型号、API 或构建号决定是否启用。接口证据仍来自构建 327，其他固件和模拟器采用此默认值不代表已验证兼容。
- 会话拥有期间动态注册媒体广播接收器，结束或更换控制器时注销。
- 只有 CarPlay 会话已连接且拥有媒体焦点或 CarPlay 窗口在前台时转发；通话中和车载蓝牙音频模式不转发。
- 上／下一曲分别发送 CarPlay 媒体 HID 的 PREVIOUS（5）／NEXT（4），由现有会话发送按下和释放报告，支持连续短按切歌。
- 广播、MediaSession 与前台窗口共用按键去重逻辑，DOWN/UP、长按重复和同一事件的多路送达不会重复切歌。发送失败不计入已处理按键，允许其他通道重试。
- 后台保留切歌接收；失去媒体焦点且 CarPlay 窗口不在前台时，按键交回原车媒体。
- 星瑞 E02 优先申请媒体／语音键拦截，保留媒体及语音广播回退，按下文条件申请音量键拦截。本分支已删除 OneOS 的 `com.geely.inputservice` 绑定、AIDL 和私有按键兼容入口。
- Siri 默认选择 `VOICE_COMMUNICATION` 麦克风源，不识别车型；创建或初始化失败时尝试 `VOICE_RECOGNITION`。这是构建默认策略，E02 的真实录音质量与路由仍待实车验证。

静态分析已确认上述常量和事件分发代码。真车仍需验证：广播是否实际到达第三方进程、切歌一次一曲、暂停后恢复、切换收音机后方控归原车、通话／车载蓝牙优先，以及导航播报时原厂音量键是否选流 12。

## 按媒体播放状态调节方控音量（2026-10-14）

用户要求：CarPlay 媒体播放时调媒体音量；暂停或未播放时调导航音量，导航未播报也允许预先调整。该功能默认启用，已保存的开关和音频路由设置优先。

实现以 iPhone 的有效 iAP2 播放状态为准，明确暂停立即选导航；未收到状态时使用实际媒体 PCM 输出、排队音频及 900 ms 尾部判断，不把 AudioTrack 创建或会话连接当播放。媒体与导航并发时仍选媒体。默认系统音量流为 3/12；初始化输出后使用其实际路由，包括静音期间。音量全部调节车机系统音频流，不读取旧应用音量比例；自动或失败回退路由通过 AudioAttributes 查询实际系统音量流，无法查询时调节媒体流 3，共享路由的媒体与导航音量同步变化。

仅在 CarPlay 会话拥有媒体按键、设置开启且无通话、蓝牙音频或 Siri 音频时，向同一个输入观察者额外申请 301/302，不增加第二个观察者抢占现有媒体键。释放、永久失焦、通话、Siri、设置关闭、地图缩放或摇杆模式会撤回音量键；发送和长按每步再次检查归属。重复 DOWN 不重复执行，应用按 250 ms 生成长按重复，UP 或失去资格停止。音量申请抛错时释放并重试仅媒体／语音键；音量写入失败时撤回音量拦截，不卸载、不改系统按键设置，也不再次模拟原车按键。标准音量键前台和无障碍后台共用相同的选流逻辑。

证据仍为本文件指定 SHA-256 的 IHU519G/FS11A1 构建 327：`decompiled/input/sources/ecarx/xsf/inputservice/key/ECarXRVolumeAdjustActionBase.java:46–62`、`ECarXKeyActionBase.java:34–57` 和 `decompiled/interactive/sources/com/ecarx/interactive/support/ECarXInteractiveInputManagerServiceUtil.java:26–34`。HiCar SDK 的请求列表没有音量键；301/302 是本项目新增的请求候选，不能描述为 HiCar 已确认的音量拦截用法。

定向测试覆盖播放／暂停选择、未知状态、无播报预调、实际路由、长按及排队回调、拒绝访问后保留媒体键、开关与无障碍服务按键配对。尚待真车验证：第三方能否获得 301/302，拦截是否阻止原厂同步调节，音量 3/12 的独立效果，以及长按、Siri、通话、切回收音机和断开后的交接。申请成功仅记为 requested，不代表实车成功。

2026-10-14 设置说明修正：星瑞方控音量路径使用上述系统输入观察者，ADB 与无障碍服务不是该功能的前提。删除这张音量卡片中的无障碍设置和 ADB 启用按钮，说明只保留播放／暂停选流、系统音量、优先交接与接口拒绝时原车处理。标准音量键的可选无障碍后台路径及其他自定义按键功能不作为星瑞方控音量的授权要求；原系统输入接口及其访问检查未变，实车拦截效果仍待验证。

本轮 3 项定向检查通过：普通／紧凑布局均以 1920×720 横屏、字体比例 150% 渲染，并确认此卡片没有两个授权入口；现存中文资源同步与占位符检查通过。截图位于本地忽略目录 `agent_docs/screenshots/remove-volume-permission-buttons/{compact,full}-font150.png`，已视觉核对。没有运行全量 CI 或生成新 APK。

## 可复核的本地证据

- `agent_docs/firmware/ihu519g-327/metadata.txt`、`system/build.prop`。
- `agent_docs/firmware/ihu519g-327/audio-framework-constants.json`。
- `decompiled/input/sources/ecarx/xsf/inputservice/util/InputManagerHelper.java`。
- `decompiled/input/sources/ecarx/xsf/inputservice/key/ECarXRVolumeAdjustActionBase.java`。
- `decompiled/adapter/sources/com/ecarx/xui/adaptapi/input/impl/KeyMapAdapterApi.java`。
- `decompiled/input/sources/ecarx/xsf/inputservice/factory/SoftKeyFactory.java`。

使用本机 7-Zip、官方 Brotli Python 包和 [JADX 1.5.6](https://github.com/skylot/jadx/releases/tag/v1.5.6) 提取与分析；工具保存在本地忽略目录。JADX 对两个输入各报告 2 处反编译错误，以上核心按键动作与广播方法均有完整输出；音频编号直接来自 CompactDex 编码的静态值，不依赖恢复优化后的方法体。

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
方向盘音量键由原厂服务直接处理，不会经本节的媒体广播送给应用。因此保留原厂音量处理；本仓库直接默认选媒体流 3、导航流 12，不要求识别车机。已有自动路由或自定义音频流设置继续优先。
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

- 本仓库专门面向星瑞 E02，默认直接使用此方案，不再根据产品、型号、API 或构建号决定是否启用。接口证据仍来自构建 327，其他固件和模拟器采用此默认值不代表已验证兼容。
- 会话拥有期间动态注册媒体广播接收器，结束或更换控制器时注销。
- 只有 CarPlay 会话已连接且拥有媒体焦点或 CarPlay 窗口在前台时转发；通话中和车载蓝牙音频模式不转发。
- 上／下一曲分别发送 CarPlay 媒体 HID 的 PREVIOUS（5）／NEXT（4），由现有会话发送按下和释放报告，支持连续短按切歌。
- 广播、MediaSession 与前台窗口共用按键去重逻辑，DOWN/UP、长按重复和同一事件的多路送达不会重复切歌。发送失败不计入已处理按键，允许其他通道重试。
- 后台保留切歌接收；失去媒体焦点且 CarPlay 窗口不在前台时，按键交回原车媒体。
- 星瑞 E02 使用媒体广播链路，不抢占原厂音量键。本分支已删除 OneOS 的 `com.geely.inputservice` 绑定、AIDL 和私有按键兼容入口。
- Siri 默认选择 `VOICE_COMMUNICATION` 麦克风源，不识别车型；创建或初始化失败时尝试 `VOICE_RECOGNITION`。这是构建默认策略，E02 的真实录音质量与路由仍待实车验证。

静态分析已确认上述常量和事件分发代码。真车仍需验证：广播是否实际到达第三方进程、切歌一次一曲、暂停后恢复、切换收音机后方控归原车、通话／车载蓝牙优先，以及导航播报时原厂音量键是否选流 12。

## 可复核的本地证据

- `agent_docs/firmware/ihu519g-327/metadata.txt`、`system/build.prop`。
- `agent_docs/firmware/ihu519g-327/audio-framework-constants.json`。
- `decompiled/input/sources/ecarx/xsf/inputservice/util/InputManagerHelper.java`。
- `decompiled/input/sources/ecarx/xsf/inputservice/key/ECarXRVolumeAdjustActionBase.java`。
- `decompiled/adapter/sources/com/ecarx/xui/adaptapi/input/impl/KeyMapAdapterApi.java`。
- `decompiled/input/sources/ecarx/xsf/inputservice/factory/SoftKeyFactory.java`。

使用本机 7-Zip、官方 Brotli Python 包和 [JADX 1.5.6](https://github.com/skylot/jadx/releases/tag/v1.5.6) 提取与分析；工具保存在本地忽略目录。JADX 对两个输入各报告 2 处反编译错误，以上核心按键动作与广播方法均有完整输出；音频编号直接来自 CompactDex 编码的静态值，不依赖恢复优化后的方法体。

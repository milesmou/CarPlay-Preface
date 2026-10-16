# Linkco CarPlay 音频播放流程对照与移植

2026-10-16，应用 0.1.1、构建 75 → 76。用户指定已验证可正常播放和切歌的 `CarPlay-linkco-v1.9.1.63.2330-20240705 (1).apk`，要求 CarPlay 音频采用其方法。

## 来源与实测边界

文件 2,472,031 字节，SHA-256 `bdb4f4075a4b0a3ffad93faa1fd1c07f284b2fe4ade1f114d12bd12c2409d4c9`，与此前方控／媒体中心对照的缓存完全一致。包 `com.aufn.carplay`，版本 1.9.1 / 63。复用 `agent_docs/apk-reference/linkco-1.9.1.63.2330/decompiled/`，JADX 1.5.6 原全量恢复记录有四个错误，未将所有反编译分支当作无损源码。

用户确认的是第三方 APK 在车机上的可用性；尚无第三方当时的焦点、SDK 注册 token 或音源日志，不能证明某个静态分支就是同车成功的唯一原因。本仓库构建 76 的实车结果仍待验证。

参考 APK 是盒子宿主：盒子给应用传送音频数据，应用使用 AudioTrack 播放。它没有在 APK 中实现本仓库的直接 iPhone AirPlay 协商，不能将盒子 USB 数据编号原样用作 iPhone NCM／RTP 协议。本轮移植车机端播放、焦点与音源管理，保留现有直接 iPhone 音频接收／解码路径；USB 连接本轮继续跳过。

## 代码证据

路径以本地缓存 `decompiled/sources/` 为根。

| 环节 | 文件与行 | 已核对行为 |
| --- | --- | --- |
| PCM 播放 | `com/juyou/app/decoder/WPAudioTrack.java:206–293,341–390` | 默认使用 legacy stream 的流式 AudioTrack，可选 usage Builder；检查初始化、play/write，主／ALT／PHONE 独立输出。 |
| 音乐压低／恢复 | 同文件 `:392–420,599–637` | `duckFactor` 默认 3，乘 0.1 得到 30%；unDuck 恢复。其固定输出还乘 0.95，不引入本仓库。 |
| 导航触发 | `com/juyou/app/Controller.java:1268–1347,1351–1400,1506–1523` | ALT 开始／结束触发压低／恢复；PHONE、SIRI 开始时 `Z(true)` 静音媒体，结束 `Z(false)`。 |
| 焦点类型 | `Controller.java:1921–1996` | 音乐永久 gain，PHONE／SIRI transient，其他提示 may-duck；均检查 request 结果。 |
| 焦点丢失 | `Controller.java:1168–1210,1854–1918` | duck、临时／永久 loss 与 gain 分别处理，音乐静音／恢复，发送媒体会话归属事件。 |
| 媒体会话 | `com/juyou/app/service/CoreService.java:250–284` | focus gain 激活，loseFocus 取消激活。 |
| 原厂音源 | `com/juyou/ecarx/ECarxManager.java:38–111` | API ready 后具体 MusicClient 注册；requestPlay 成功后按列表、source=6、播放状态顺序发布。本仓库之前已按此实现，详见 [原移植](PREFACE_LINKCO_MEDIA_REPLICA.md)。 |

源文件 SHA-256：

- WPAudioTrack：`9916b85a8f08d937a7448729686ab15ccd86a5a9fbe941eae519fbb78c369b85`。
- Controller：`525c63e0bea85d2083d4d60689b73a4a014d4775a507713963c08d8f30d17c4a`。
- CoreService：`9940fdd172b1dedf48198ece8097ff310ce75269f3f65f2abbaab8de4ed418a3`。
- ECarxManager：`34761ea739e696a7bf24a6d44ced3de82180a781882e99ea96be9ea655f950f7`。

## 本仓库实现

保留本应用的 AudioTrack 输出及已核对的原厂媒体中心注册，加入 sink 所属的 `CarPlayAudioMixer`。只在渲染器开始播放并已接收 PCM 后建立语音优先状态，结合已排队音频时长与 900 ms 安静尾段自动结束。永久协商而暂不发声的导航／Siri 音轨不会长期压低音乐。多段导航、通话、Siri 分别记账，一段结束后继续遵从剩余语音的优先级。

导航播放时仅将本应用音乐的相对增益设为 0.3；通话／Siri 实际发声时设为 0；结束后恢复到仍有效的焦点增益。若此时 Android 焦点仍丢失，语音结束不能擅自恢复音乐。系统媒体／导航音量值不变，默认输出流仍为 3／12，日常增益保留 1。语音即使落到媒体物理通道，也继续以通话／Siri 角色参与优先级，避免被当成音乐静音。

音频焦点默认启用，用户已保存的 false 继续生效。启用时由播放器协调器持有请求，媒体方控只接收同一次授权结果，消除两个独立音乐焦点请求。只有焦点实际获准，原厂媒体音源和方控才能取得对应归属；duck 保留音乐归属，临时／永久 loss 取消对应媒体会话，gain 恢复。通话、Siri 的 transient 请求优先于音乐。用户已保存的“禁用媒体按键音频焦点”同时阻止播放器的永久音乐焦点，语音 transient 路径保留；关闭播放器焦点时保留原有媒体键路径。

旧控制器、迟到焦点回调、迟到 PCM 及旧混音恢复定时器均受所属实例和关闭状态约束。最后一个音轨释放后清除旧增益；关闭 sink 时先停止混音，再放弃请求，避免旧音轨短暂恢复。iPhone 的真实开始／恢复事件可以重新申请焦点，重复位置更新不循环抢音源。保留明确 PLAY／PAUSE 语义、跨入口去重、通话和蓝牙模式检查。

原厂 SDK 的 ready、token 与 requestPlay 返回值继续真实检查。报告中 IHU353G 的 `api-not-ready` 不会被这次 AudioTrack 移植伪造为成功。参考 IHU519G/FS11A1 327 OTA 的 SHA-256 仍为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`，本轮不增加其厂商接口可用性结论。

## 保留的差异与使用

参考 APK 的盒子协议、私有 so、包名／签名、其他车型分支、周期性重复 requestPlay、焦点 gain 自动给手机发播放，以及每个输出固定乘 0.95 均不引入。导航仍使用用户配置的 12／自定义／自动路线，不改为参考包代码默认的 3。

本轮更新的是设置中的 CarPlay 音频方式。若当前保存为“音乐和方控走原车蓝牙”，该选项仍保持独立，并继续沿用构建 75 的蓝牙链路；要验证本轮 AudioTrack 流程，应选择任一 CarPlay 音频方式并重新连接。已有会话不因保存设置而被中断。

本轮没有更改界面布局，只修正焦点说明文案。定向测试与测试包结果记录如下，不能替代同车连续播放、切歌不跳页、导航／Siri／通话结束恢复和切换原车收音机后的实车验收。

最终 **163 项定向测试通过**，shared 82、common 81。覆盖混音优先级、语音排队尾段、多导航重叠、通话落到媒体通道、Siri 静默轨、焦点拒绝、原车通话期间延后音乐焦点、焦点禁用、源归属／旧回调隔离、实际渲染器联动、蓝牙交接回归和主机设置。未运行完整 CI 或全量 Lint。

`:mobile:assembleDebug` 成功；实际输出元数据为 0.1.1 / 76。签名证书 SHA-256：`75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`，与现有发布签名一致。

交付文件：`mobile/build/outputs/apk/debug/CarPlay-Preface-0.1.1-76-debug.apk`，SHA-256 `43d5a724ebb81d034d1f441dc6e9bce3cd14daeeac93c3d01687bfd0a4d4d23a`。原始 APK 与输出元数据保留。`adb -s emulator-5554 install -r` 返回 Success，保留已有数据；该安装不属于星瑞实车验证。

公开文件检查、固件事实 JSON 解析及 CRLF 配置下的 `git diff -w --check` 通过。本地证据与测试 XML 存于 Git 忽略的 `agent_docs/linkco-audio-76/`。

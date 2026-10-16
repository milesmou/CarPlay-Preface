# 方控播放／暂停与 Siri 重复触发修正

2026-10-16。修正应用方控流程的两项重复触发问题，不代表原厂音源准入或切歌跳页已经实车解决。

## 应用修正

- MediaSession 的明确播放／暂停回调加入 `SESSION_CONTROL` 跨入口去重，与输入观察、Android 原始键及原厂媒体中心共享现有匹配窗口。同一次播放／暂停键的平行投递只发送一次；同来源快速连按、明确 PLAY 与 PAUSE 的相反指令继续分别处理，发送失败不留下成功去重记录。
- Siri 广播请求成功后，为同键仍处于按住状态的各输入来源记录已处理标记。松开时移除标记，不因长按超过 400 ms 而再次请求；物理释放先到时也标记其余仍按住的来源。观察者释放只清理自身来源，解绑、换会话及修改绑定清理全部按压状态。
- 未收到物理按压时，语音广播回退仍可直接请求 Siri，保留原有短时间去重。通话／会话／归属检查仍先于确认处理，失败请求不标记已处理。

## 固件语义复核

参考固件：IHU519G / FS11A1 / E02 / Android 9 / 构建 327。OTA SHA-256：`f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。

本地证据根目录为 `agent_docs/firmware/ihu519g-327/`：

- `decompiled/input/sources/ecarx/xsf/inputservice/key/ECarXKeyActionBase.java` 声明动作 0 为短按、1 为长按，不能将其解释为 DOWN／UP。
- `decompiled/input/sources/ecarx/xsf/inputservice/key/ECarXRVoiceAssistAction.java` 的 `onLongPressTriggered()` 在本参考产品上调用 `onShortClick()`，因此长按同样可发动作 0 的有序广播。该文件 SHA-256：`d060a3daef9ebc319bf03a33f62380602122f023b1aaf8649e31a8d8e93a62b6`。
- `decompiled/input/sources/ecarx/xsf/inputservice/handlekeyevent/HandleAllKeyEvent.java` 分别处理长按触发和稍后的释放；不能假设长按广播与物理 UP 的间隔始终小于 400 ms。

以上仅为参考 OTA 静态证据，不外推到所有 E02 固件；本轮未增加车型识别或其他车型适配。

## 验证

执行 `:common:testDebugUnitTest`，仅选择 `WheelCommandRoutingTest`、`WheelSiriWindowTest`、`PrefaceMediaKeyEchoesTest`、`PrefaceVoiceButtonReceiverTest`、`PrefaceTrackKeyInterceptionTest`、`CarPlayMediaCallbackTest`、`WheelMediaBindingsTest`，全部通过。

新增 7 个跨入口回归场景在 API 28、33 各执行一次，覆盖长按广播后延迟释放、重复广播、多输入来源、下一次按压、释放先到、无观察回退、失败请求重试、通话期间释放、播放／暂停两种投递顺序、快速操作及失败后的另一入口补送。

未运行全量 CI、Lint、APK 构建或实车验证。实际原车页面是否被抑制、原厂音源准入与真实广播时序仍待车机验证。

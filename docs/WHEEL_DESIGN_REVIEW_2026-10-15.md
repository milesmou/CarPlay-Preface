# 方控整体设计复核（2026-10-15）

以下保留审查时的源码快照结论。后续已完成六项修正并通过 151 项定向测试，见 [修正与验证记录](WHEEL_DESIGN_FIXES_2026-10-15.md)；不作为真车跳页已修复的确认。

范围：当前工作区的前台窗口、MediaSession、媒体／语音广播、原厂输入观察、原厂媒体中心、无障碍摇杆、学习配置及音量路由。附件只作为分析数据。没有安装或执行参考 APK，没有修改应用实现，没有构建 APK。

结论：确认 6 处逻辑缺陷。上一轮 96 项通过的定向测试没有覆盖这些跨入口、改键与状态切换场景。通过测试不能解释为方控设计已经完整或跳页问题已修复。

## 确认的问题

### 1. P1：MediaSession 语义命令绕过通话保护

位置：`common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt:142`、`:493`、`:780` 的 `CarPlayMediaCallback`。

MediaSession 回调的 eligible 只验证控制器和回调实例归属。语义型 `onSkipToNext()`、`onPause()` 等通过 `send()` 直接进入发送；最终 `sendMediaCommand()` 检查蓝牙模式及动作停用，没有检查当前通话、活动会话和媒体归属。相比之下，原始按键接收器检查通话。

复现：同一已连接控制器下，将 AudioManager 改为 `MODE_IN_COMMUNICATION`；原始 NEXT 被拒绝，调用 MediaSession 的 onSkipToNext 和 onPause 却仍向控制器发送 NEXT／PAUSE。若车机以这种回调投递方控，同一操作可绕过通话保护。并非声称本次已经在真车观察到该投递顺序。

建议：把控制器归属与运行时准入分开，并在最终调度处统一验证；明确哪些语义命令允许在通话中执行，避免入口不同导致策略不同。

### 2. P1：临时语音优先被当作音源归属丢失，结束后不恢复

位置：`CarPlayMediaKeys.kt:624`、`:627`；`PrefaceMediaCenter.kt:60`、`:84`。

`mediaCenterKeysEligible()` 将 Siri 请求后的 1500 ms 和 PHONE／ASSISTANT 优先语音视为不 eligible；`PrefaceMediaCenter.update()` 因此直接清除 ownsSource。之后 eligibility 恢复时，不增加 request，也不恢复 ownsSource；没有新的播放／恢复请求时，厂商媒体回调继续被拒绝。

复现：原厂源申请成功，媒体命令可用；保持音乐 playing，eligibility 由 true→false→true，未发生新 requestSource；命令仍返回 false，requestPlay 总计一次。真实表现取决于手机是否另外重启音乐流或发出新的播放通知，不能概括为每次 Siri 后都失效。

`NavigationPlayback.priorityVoiceActive` 实际来自 PHONE／ASSISTANT，并非普通导航音频，见 `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt:2374`。普通导航播报本身不触发这条分支。

建议：区分临时禁止执行按键、实际媒体源被其他应用取走、会话断开。临时语音期间暂停调度，不直接抹除仍有效的音源记录；恢复时重新核验实际归属，不能简单每次抢回音源。

### 3. P2：原始键映射应用到了已经明确动作的媒体回调

位置：`CarPlayMediaKeys.kt:450`；原厂媒体入口 `:641`。

`dispatchBoundTransport()` 将语义 NEXT 还原成键码 87，再查询物理绑定。下一曲学为 F1 后，87 不再绑定任何操作，MediaSession onSkipToNext 因此被丢弃；原厂 MusicClient 的 onNext 也通过相同方法。它还可能把明确的“下一曲”回调改成 Siri。

复现：NEXT 学为 F1，onSkipToNext 未发送 NEXT；随后 F1 的原始 KeyEvent 可以正常发送一次 NEXT。

建议：只有带原始键码的入口执行 canonical／用户学习映射。onNext／onPrevious 等直接进入对应语义动作，只执行动作启停、会话准入及去重策略。

### 4. P2：摇杆按键被媒体路径抢先处理

位置：`WheelKeyService.kt:126`。

无障碍服务在检查摇杆、已消费的完整按压和旧角色之前，把所有已知媒体键直接交给媒体处理器。默认 87／88 永远是 known。摇杆 NEXT 设置为 87 时，即使摇杆已开启，按键仍切歌，不发送 knob。学习入口的单向冲突检查不能保护原来已保存的摇杆配置，旧角色配置入口也未统一检查新的媒体绑定。

复现：F4 开启摇杆，NEXT=87，随后按 87 的 DOWN／UP；knob 事件为空，CarPlay 收到 NEXT。

建议：统一角色优先级和冲突校验；学习、已消费的按压、通话／原车、已启用摇杆和媒体处理都应有明确归属。保留已有自定义配置，不静默改键。

### 5. P2：前台窗口提前消费 UP，媒体键改为 Siri 后不触发

位置：`CarPlayHostActivity.kt:827`、`:894`；`CarPlayMediaKeys.kt:545`。

Siri 在配对 UP 时请求。把 87 从下一曲释放后改作 Siri，DOWN 经媒体接收器进入 learnedHeld；前台窗口同时把它放入 windowMediaPresses。UP 被窗口顶部直接消费，没有再交给同一媒体处理器，Siri 收不到释放。其他广播路径若恰好补发 UP，可能掩盖这个缺陷。

复现：只有窗口 DOWN／UP 时，均返回消费成功，但 requestSiri 调用次数为零；将同一个 UP 直接送给媒体处理器，随即调用一次 requestSiri。

建议：记录每次按压的处理者，并将重复／释放送回原处理者；窗口不应在绕过其处理者后只返回 true。

### 6. P2：观察者代际已重建，但自定义按住状态未同步重置

位置：`CarPlayMediaKeys.kt:551`；`PrefaceTrackKeyInterception.kt:98`。

外层 learnedHeld 使用 source＋canonical 键码记录按住状态；输入观察者 close 只清理自己内部 held。失去媒体归属时若 UP 落在旧注册，旧回调被正确拒绝，但外层仍认为键处于按住状态。恢复归属后的首次 DOWN 被当重复而忽略，到新 UP 才解除。

复现：305 DOWN→失去前台归属→旧 UP 被代际检查拒绝→恢复归属→新 DOWN。第二次真实按压没有新增 NEXT；其 UP 后再按才恢复。

建议：把 held 与观察者注册代际绑定，或在释放该输入所有权时清理对应来源的状态。保留旧回调隔离，不通过接受旧 UP 来规避问题。

## 原车跳页的能力边界

构建 67 报告已经显示物理 305 到达、NEXT 发送成功、标准广播回声被去重，同时原厂 API `ready=false before-registration`，用户确认仍跳原车多媒体页。证据与限制见 [构建 67 报告](CONNECTION_REPORT_2026-10-15_BUILD67.md)。当前没有更新包的真车证据，不能由本次复现确定跳页的唯一根因。

现有输入 API 注册的是观察者。Java 方法返回 true、收到按键、HID 发送成功和 Android 焦点存在都不证明其他进程停止处理。新状态页持续标注独占未确认是正确的。再叠加一层回调或重新学习键码，没有证据能关闭原车的页面启动行为。

需要分别核实原厂媒体源真正可用、实际选中的音源及系统输入服务是否仍启动原车页面。参考 OTA 为 IHU519G / FS11A1 / Android 9 / 构建 327，SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`；运行报告为 IHU353G，身份差异不能省略。

## 推荐调整顺序

1. 统一最终命令的会话、通话、蓝牙及媒体归属准入；区分控制器是否仍有效与当前是否允许执行。
2. 分开原始物理事件和明确语义命令，统一各角色的冲突与优先级；三种音乐方案继续互斥。
3. 按压按处理者和注册代际管理；学习、窗口、无障碍、观察者共享必要的按压生命周期，不能各自只消费一半。
4. 将临时语音优先与真正丢失音源分开，避免语音结束后长期拒绝厂商回调。
5. 保留跨路径去重和真实的“独占未确认”状态。750 ms 时间窗口仅能匹配候选回声，无法严格区分窗口内来自不同来源的独立操作，不将其视为独占保障。

## 复现证据与范围

本轮仅执行 `:common:testDebugUnitTest --tests '*WheelDesignReviewProbeTest'`，6 个探针全部复现所描述的错误行为。探针断言的是“当前确实发生这种行为”，通过不代表缺陷已修复。未运行全量 CI。

探针源码、Gradle 日志和 JUnit XML 保存于本地忽略目录 `agent_docs/reviews/wheel-design-2026-10-15/`。临时加入测试源集的探针已移除，避免把错误行为作为永久期望；应用源码未改变。正式修复时应把它们改为期望正确行为的回归测试，并补充车机实测。

## 后续：按用户要求删除旧 Siri 方案

后续清理仅保留新学习／处理状态方案，移除旧 Siri 开关、角色、窗口学习器、旧保存值读取和重复请求路径。窗口将已消费媒体键的释放送回新处理器，问题 5 已增加 API 28／33 正确行为回归。其他问题不因这次清理自动视为解决，原车跳页也未获得新的真车证据。清理后的验证范围见 [方控学习说明](WHEEL_MEDIA_LEARNING.md)。

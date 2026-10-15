# 方控六项设计问题修正

2026-10-15，针对 [六项设计审查](WHEEL_DESIGN_REVIEW_2026-10-15.md) 的应用修正。目标为星瑞 E02，继续只使用新的按键学习与处理状态方案，没有恢复旧 Siri 开关、旧配置或旧学习路径。本轮没有构建 APK，也没有真车验证。

## 修正对应关系

| 问题 | 修正后的处理 | 回归证据 |
| --- | --- | --- |
| 1. 通话检查被语义回调绕过 | `CarPlayMediaKeys` 在媒体命令进入去重前、最终发送前统一检查活动会话、媒体归属、通话、蓝牙模式和临时语音优先。MediaSession 与原厂语义回调共用该入口。 | 通话、断开、无媒体归属下的 NEXT／PREVIOUS／PLAY／PAUSE 均不发送；已有成功去重记录也不能绕过准入。 |
| 2. Siri 后原厂音源不恢复 | `PrefaceMediaCenter.Snapshot` 区分音源 eligibility 与 `commandsAllowed`。在媒体归属仍成立时，通话／Siri 临时阻止命令，不抹除已获准的音源记录；结束后恢复命令。 | Siri 与通话结束后无需第二次 requestPlay 即恢复回调；真正失去归属、断开或 API 不可用时仍撤销记录，不自动抢回播放。 |
| 3. 学习物理键影响明确媒体指令 | 删除将语义 NEXT／PREVIOUS 还原成 87／88 再查映射的路径。只有原始键码执行学习映射；onNext／onPrevious 直接执行同名动作，仍尊重动作停用和统一准入。 | NEXT 学为 F1 后，MediaSession、原厂语义 NEXT 和 F1 均可发送下一曲；旧原始 87 不恢复绑定。 |
| 4. 摇杆优先级不一致 | 服务先保持已认领的按压，再决定当前摇杆角色与媒体角色。活动摇杆认领的键在窗口、输入观察及语义媒体路径上均不重复发送媒体命令。 | 摇杆 NEXT=87 时只发送 knob；退出摇杆后下一次按键恢复媒体操作；按住时关闭摇杆仍消费对应释放。原车蓝牙媒体键保留原车处理。 |
| 5. 媒体键改为 Siri 后 UP 被提前消费 | 保留上一轮窗口 UP 回送修正，并允许接收器在运行条件变化后仅把 UP 交回原映射处理器，清理按住状态而不执行 Siri。 | 87 改为 Siri 时一次完整按压只请求一次；失去归属期间释放不请求 Siri，也不会在恢复后被孤立 UP 触发。 |
| 6. 观察者重建后漏掉首次按键 | 输入观察者 close 在使旧代际失效后，通知外层清理此输入来源的 held。窗口／广播来源的按压不被一并抹掉。 | 旧注册的 DOWN／UP 均不能影响新代际；新注册第一次 DOWN 正常执行。关闭输入观察者不会丢失另一入口已收到的 Siri DOWN。 |

`PrefaceMediaCenter.report()` 增加 `commandsAllowed`，区分“已有音源记录”与“当前允许处理命令”。音源记录仍是应用根据申请结果维护的状态，不等于系统独占确认。

## 处理原则

- 实际媒体归属丢失时，沿用等待真正新播放／恢复请求的规则；不在每次轮询、语音结束或页面恢复时调用 requestPlay。
- 若播放请求在 API ready 前遇到优先状态，旧待处理请求不在优先状态结束后自动启动播放；新的真实播放请求才能申请。
- 物理按压的重复和释放保留原处理者。观察者释放只清理其自身来源，旧回调的代际校验继续生效。
- 跨来源的时间窗口去重继续保留；它不能证明原车停止处理。此修正不增加私有接口、其他车型分支或权限申请。

## 验证

151 项定向测试通过，执行的任务为 `:common:testDebugUnitTest`，仅选择以下测试类，未执行全量 CI、Lint 或 APK 构建：

`WheelCommandRoutingTest`、`WheelSiriWindowTest`、`WheelKeyServiceTest`、`WheelMediaBindingsTest`、`CarPlayMediaOwnershipTest`、`CarPlayMediaCallbackTest`、`CarPlayMediaStartupRegistrationTest`、`PrefaceTrackKeyInterceptionTest`、`PrefaceMediaCenterTest`、`PrefaceConcreteMediaCenterTest`、`PrefaceFirmwareIntegrationTest`、`PrefaceMediaKeyEchoesTest`。

新增跨入口回归 `WheelCommandRoutingTest` 的 9 个场景在 API 28／33 共执行 18 次；`WheelSiriWindowTest` 的 6 个场景在两版本共执行 12 次。另增加临时命令屏蔽、API ready 前请求取消的媒体中心单元测试。本轮未修改界面布局，既有学习与状态入口保留。

## 真车验证限制

固件参考仍为 IHU519G / FS11A1 / Android 9 / 构建 327，OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。来源和静态接口证据见 [固件分析](PREFACE_FIRMWARE_327.md)，IHU353G 运行报告另行记录。没有增加固件独占能力证据。

六项应用逻辑缺陷的定向验证通过，不代表切歌跳回原车页面已经实车解决。仍需验证新包上的真实事件路径、原厂音源准入、Siri／通话前后行为、快速连按、摇杆切换及后台恢复；独占处理继续显示未确认。

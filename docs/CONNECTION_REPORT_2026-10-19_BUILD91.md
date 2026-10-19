# 构建 91：切歌跳原车多媒体与蓝牙无音乐

2026-10-19，复核用户提供的 `diplay-report 7.txt`。用户补充：**CarPlay 音频切歌仍跳原车多媒体；原车蓝牙音频切歌无反应，无法播放音乐**。两种模式分别分析。报告为主应用 **0.1.1 / 91**、`com.shihab.diplay.hudtest`、Android 9 / API 28、IHU353G；不是当前待打包 92／组件 7 的运行结果。

报告大小 98,919 字节、874 行，SHA-256 `6257f6a0194675bf270dbc885d006a0bebe53e50e35dcd7258ff2e62c343ddd1`。原始日志及定向事件索引只保存在忽略目录 `agent_docs/diagnostics/build91-wheel-20261019/report.txt` 和 `analysis.json`；以下行号对应该原始报告，不包含报告头部最近事件的重复计数。

## CarPlay 音频：收到并转发了下一曲，原车仍参与处理

| 原始行／时间 | 记录与意义 |
| --- | --- |
| 474–477，10:57:07.780–07.805 | AudioTrack 焦点请求返回 1，方控随后记录持有焦点，并申请 85／87／88／298／304／305 观察。申请记录仍明确独占未确认。 |
| 491，10:57:12.922 | `preface-intercept index=4 sent=true focusHeld=true`；index 4 是 NEXT。应用成功发出下一曲命令，不是这一按键完全没有收到，也不是当时未持有焦点。发送返回成功不单独等于手机已切换歌曲。 |
| 496，10:57:13.041 | 标准媒体广播的同一 NEXT 被 `paired-wheel-echo` 过滤，与前一入口间隔 119 ms。没有应用双发切两曲的证据。 |
| 497／506，10:57:13.064／14.014 | 转发后 142 ms 出现 user-leave-hint，之后页面 stop；sessionActive 与 backgroundSessionActive 仍为 true，finishing=false。结合用户反馈，符合跳到原车多媒体而 CarPlay 会话继续的现象；单独的 Activity 日志不包含前台目标包名。 |
| 132、162、248、266、304、587、606、626 | 全报告 8 次原厂媒体 SDK 在注册前返回 `ready=false`，没有 ready=true、注册 token 或 request-play-accepted。 |

`CarPlayMediaKeys.dispatchMedia()` 的两个入口共用去重，成功只表示向 CarPlay 控制器发出命令。`PrefaceMediaCenter.onReady(false)` 保持未注册且无音源归属；Android 音频焦点授予与原厂音源登记是两层状态，不能互相代替。当前运行记录仍停在厂商媒体接口的就绪／调用者准入阶段，尚未进入注册和申请音源，因而不能宣称构建 91 已接管原车媒体按键。

报告中的 `Wheel keys ... presses=0` 仅计可选 `WheelKeyService` 的 `press()`，该服务当时 wanted=false、bound=false；私有观察者及媒体广播经 `note()` 记日志，不能用这个 0 否定行 491 的真实转发。报告顶部 controllerAttached=false 等是导出时已停止的状态，也不能覆盖此前活动会话的记录。

## 原车蓝牙：音乐与 AVRCP 播放通路未验证成功

10:57:43 起，行 568／576 明确选择原车蓝牙：CarPlay 不提供音乐音频，音乐和媒体方控保留原车处理。这段没有 `Media command` 转发记录，与该模式的归属策略一致；不能沿用前一段的应用 NEXT 成功证明蓝牙切歌有效。

行 720（10:57:48.013）记录播放状态查询 `NoSuchMethodException`。对应 `BluetoothAudioHandoff.refreshPlaying()` 先在 sink 的已连接设备中找到所选手机，再反射 `isA2dpPlaying`；按此调用路径，可推断该次查询至少找到过所选手机的连接，但无法读到真实播放状态。查询失败保留未知，不发送播放／暂停命令，也不证明它就是暂停的触发者。

| iPhone 播放→暂停行号 | 播放→暂停时间 | 间隔 |
| --- | --- | --- |
| 789→792 | 10:57:50.069→50.781 | 712 ms |
| 798→803 | 10:57:53.212→54.147 | 935 ms |
| 809→812 | 10:57:55.683→56.547 | 864 ms |
| 818→823 | 10:57:58.287→59.195 | 908 ms |

这是手机通过 iAP2 返回的播放状态，不是车机 A2DP 音频已经出声的证明。各次诊断均 musicActive=false、focusHeld=false、mediaStreamActive=false；后两者符合本应用在蓝牙模式不抢音乐焦点、不创建 CarPlay 音乐轨的策略。profileAggregate 的 A2DP sink／AVRCP controller／headset client 均为 2，只表示汇总连接，不能证明播放中、正确手机的 AVRCP 命令已送达或原车已选蓝牙媒体音源。

没有真实 selected Bluetooth music playing=true、播放广播、原车选中音源或厂商发出 AVRCP 命令的证据。也没有 `platform Bluetooth music reconnect ...` 记录，不能据此断言补丁连接代理已被调用、安装成功或返回失败：代理仅在所选手机确实断开且直接 connect 方法缺失时进入，已连接但未播放不会触发；补丁实际安装身份也未包含在报告中。前一段行 401 的 connect 缺失发生在 CarPlay 音频尝试交接时，已保留原有蓝牙连接，不能把它当作本段代理调用失败。

## 仍需区分的厂商状态

原厂包和服务均可查询，版本 OpenAPI 40530001、媒体中心 31000082，enabled=true、stopped=false；日志标注 queryOnly，不能推导真实服务绑定和调用者准入成功。主应用 systemApp=false、mediaPermission=false，但厂商服务还检查调用者 license／实际 Binder 身份，**不能只凭 mediaPermission=false 就认定唯一原因，也不能将修改系统设置或 VPN 授权当作原厂媒体准入**。自进程 SDK 日志分类为 none，报告明确无法读取厂商进程；这不表示没有厂商拒绝。

下一轮需要在同一次切歌／播放时对齐 OpenAPI、媒体中心及 Bluetooth 进程记录：

1. CarPlay 模式：查主应用获取 mediacenter 时的实际拒绝／断开原因，区分 No Authorization、目标 Binder 未就绪或服务绑定失败；取得 ready=true 后再查注册 token、requestPlay 返回与音源归属。
2. 蓝牙模式：查所选手机 sink／AVRCP 的实际状态、原车当前媒体音源、AVRCP 播放／暂停的发送方向及原因，并对齐这四次短暂播放。缺少 A2DP 播放事件不能当作实际断开。
3. 页面跳转：查该次 Activity 启动记录的目标包与调用方，补全 user-leave-hint 无法识别目标的部分。

当前待打包组件 7 的系统共享 UID 改动只改变授权补丁；CarPlay 主应用仍用原包名、签名及实际 UID 调用媒体 SDK，不能把该授权改动描述为已经解决方控或蓝牙播放。

## 参考证据与本次范围

静态参考仍为 IHU519G / FS11A1 / Android 9 / 构建 327，OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。`services.vdex` SHA-256 `5d19a5aa3f1c4f8b7f90ea18ee44b52e1e3b32ef748d6d9af5c86a1546506571`：requestKeysInterception／dispatcherAllEvent／dispatcherMediaKey 偏移为 4701750／4705458／4705772；它登记并多路分发观察者，不是第三方独占成功凭证。原始证据 `agent_docs/firmware/ihu519g-327/input-dispatch-evidence.txt`，复核命令 `python agent_docs/firmware/inspect_input_methods.py`。这些不是 IHU353G 的服务实现或运行验证。

厂商准入的局部实现参照[用户提供的 OpenAPI 服务端](NETEASE_TRACK_SERVICE_REFERENCE.md)：输入 SHA-256 `8e0e8b76f53f10a9c0d49d5efbd99b24f946223ebecfc9d9ecf42b3460f6aa23`，`ak.java:53–82` 区分无授权与服务未就绪，`f/f.java:124–163,225–233` 核对实际调用身份及 license；该 APK 也未提供本次服务端实时失败原因。

本轮只复核日志、对应源码及本地固件／APK 缓存，更新诊断文档和事实索引；没有修改运行逻辑，没有运行 Gradle、测试、打包、签名、安装或系统授权，也没有取得新的车机运行记录。不能将定位到的阶段描述为两项故障已经修复。

# 热点与蓝牙恢复修正（2026-10-16）

依据 [构建 85 完整报告](CONNECTION_REPORT_2026-10-16_BUILD85.md)。本轮只修改应用热点与蓝牙音乐恢复，
不修改方控接管或 USB/VPN。没有生成 APK，应用版本与构建号仍为 0.1.1 / 85。

## 临时热点预约相互影响

在 Android 9 的真实框架方法中复现了一个能解释重建后秒断的缺陷：
同一个 WifiManager 上释放旧预约、申请新预约，然后执行旧预约的 finalizer，会清掉新请求并再次通知系统停止。
回归用例直接执行本地 Android 9 框架的预约 close/finalize 方法，并核对系统服务调用与回调槽位；
没有把回收时机寄托在不确定的 System.gc() 上。

来源是 [AOSP Android 9 WifiManager.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/wifi/java/android/net/wifi/WifiManager.java)：
`LocalOnlyHotspotReservation.finalize()` 仍调用 close，close 通过外部 WifiManager 的当前回调槽位停止请求。
[SystemServiceRegistry.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/core/java/android/app/SystemServiceRegistry.java)
将 WifiManager 放在 Context 的服务缓存中。这是 AOSP 行为和本地框架复现，
不是直接捕获到用户车机 GC 的证明，也没有确认报告中的每次掉线都由它触发。

修正如下：

- 每个 LocalOnlyHotspotManager 通过公共 createConfigurationContext 获取独立 WifiManager；不修改配置、密度或系统热点。
  关闭旧预约后，其 finalizer 只会访问旧实例已经清空的回调槽位，不能清掉新实例的槽位。
- 一个管理器只允许发起一次系统请求。失败后使用新管理器，防止在同一实例内重新引入旧预约影响新请求的问题。
- 进程内串行化预约的系统请求。取消时若还没收到系统回调，下一次请求等待旧回调处理完，
  迟到的 onStarted 必须先关闭旧预约，才能放行新请求。等待受原启动期限和取消状态约束，
  不调用隐藏取消 API，也不通过固定休眠假定系统已经停止。
- 接口身份与地址连续稳定 500 ms 才继续；查询无线频率之后重新核对接口，
  避免把查询前的失效快照当作就绪。后续校验也比较接口索引、可取得的 BSSID 与地址，
  并再次检查预约是否已被关闭或收到停止回调。
- 断线与重建日志补充具体失效原因，区分系统停止、接口消失、接口替换和地址变化。

有效的临时热点预约仍沿用原名称与密码；手动断开仍可跨控制器保留有效预约。
不关闭其他应用或系统持有的热点，不设置 SSID、密码或信道，不因设置变化中断运行中的会话。
onStopped 不作为显式 close 完成的等待信号；[官方回调文档](https://developer.android.com/reference/android/net/wifi/WifiManager.LocalOnlyHotspotCallback#onStopped())
明确该回调不由应用主动 close 触发。

如果系统始终不回传已取消请求的结果，下一请求明确失败并保留隔离，不能在没有旧资源释放证明时继续申请。
这仍依赖系统正常交付公开 API 回调；本轮没有增加强制关闭系统热点的路径。

## 蓝牙音乐交接与恢复

保留上轮的恢复接口预检：connect 方法不可见或返回类型不符时，不主动 disconnect A2DP。
这针对报告中的单向交接，避免先断开音乐后才发现无法恢复；预检能确认可见签名，不能预先证明运行时权限或服务一定接受调用。

用户明确选择蓝牙输出时，额外尝试连接该次 CarPlay 的同一部已配对 iPhone 的音乐 profile，
不再要求必须有本进程曾主动断开的记录。普通 CarPlay 退出则仍只恢复本应用主动断开的音乐链路。

恢复请求最多等待五秒，拒绝后的重试间隔至少一秒，重复入口共用一个任务。
CONNECTING 或 connect 返回 true 不等于已连接；只有查询到 CONNECTED 才确认恢复、清除恢复义务。
切回 CarPlay、代理丢失和关闭时撤销当前恢复任务；旧会话不能继续发起原生模式的连接请求。
通话期间不发起音乐连接；其他手机已经占用音乐 profile 时不抢占，避免单连接蓝牙栈自动断开另一部手机。

NoSuchMethodException 等接口错误只报告一次，并提示在车机蓝牙设置连接这部 iPhone 的媒体音频。
超时明确记录未确认，不再把请求已接受当作成功。
接口不可用后，用户手动连上的实际 CONNECTED 状态仍可确认。

此修正不能通过不存在或受限的接口强行恢复构建 85 已经断开的 A2DP。
这种情况下需要在原车蓝牙设置恢复一次媒体连接，新逻辑避免再次主动进入已知无法归还的状态。
不切换 HFP/SCO，不操作蓝牙总开关，不引入参考 SDK 的空实现作为私有恢复接口。

## 验证与适用范围

定向验证包括 Android 9 旧预约 finalizer 复现及隔离、迟到回调、系统请求失败、接口查询后失效、接口替换、
有效预约跨控制器复用、重复清理、同一手机蓝牙恢复、连接确认、超时、通话、其他手机保护及旧会话取消。
最终 **54 项定向测试通过**，其中蓝牙交接覆盖 API 28 / 33，热点框架与主机集成覆盖 API 28。
没有运行完整 CI、Lint 或打包任务。

| 测试类 | 通过项数 |
| --- | --- |
| BluetoothAudioHandoffTest | 32 |
| LocalOnlyHotspotRequestGateTest | 3 |
| TemporaryHotspotOwnershipTest | 2 |
| TemporaryHotspotLifecycleTest | 4 |
| TemporaryHotspotRetryTest | 10 |
| BluetoothAudioSessionTest | 3 |

执行限定类名的 `:shared:testDebugUnitTest` 与 `:common:testDebugUnitTest`，没有执行其他测试集合。
最终日志与 XML 保存于本机忽略目录 `agent_docs/hotspot-bluetooth-recovery-20261016/`。
`git diff -w --check`（允许 Windows CR）通过。

参考固件仍为 IHU519G / FS11A1 / Android 9 构建 327，OTA SHA-256：
`f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。
报告设备为 IHU353G；本轮属于应用缺陷修正与本地验证，尚无新 APK 的实车结果。

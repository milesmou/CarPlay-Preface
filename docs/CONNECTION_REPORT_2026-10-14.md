# 2026-10-14 连接报告排查

来源为用户提供的三份 DiPlay 0.1.1 诊断报告，原始文件仅保存在本地忽略目录。事实和 SHA-256 见 [诊断事实索引](diagnostics/connection-2026-10-14.json)。报告设备为 ecarx IHU353G、Android 9/API 28，构建字符串为 `PQ2A.190405.003 dev-keys`；没有完整系统指纹或 incremental，不能认定为参考 OTA 的 IHU519G/FS11A1 构建 327，也不能外推为所有星瑞 E02 固件的实测结果。

## 第三份报告及密码显示补充

`diplay-report 3.txt` 包含旧日志和 12:30–12:37 的本轮日志，需要分时段判断，不能用旧的成功记录替代本轮验证。

- 旧日志在 11:05:48 和 11:07:09 建立无线会话并渲染首帧（行 369–400、575–605）；两次都记录 channel=0。这证明这些尝试可以连接，不证明当前重连可用，也不能单独把未知信道认定为根因。
- 本轮无线认证接受后已发出 0x5703 和 0x4301，多次热点接口 RX 增量为 0、tcpAccepted=0，最终首个 TCP 超时。IPv4 和 IPv6 公告均未解决；部分重新创建热点失败为 generic error。没有热点关联事件或 iPhone 端接入结果，无法区分未找到当前热点、关联失败和启动消息问题。
- 用户截图的 AndroidShare_733804 位于已知网络；这只能确认保存过该网络，不能确认当前预约的热点名称或接入状态。用户反馈车机未显示密码，且未手动加入验证。代码核实 HotspotReady 原先未传递密码，连接等待页也没有显示网络凭据。
- 修改后，HotspotReady 将当前系统分配密码仅传给连接等待页，显示当前热点名称、密码及重启后可能变化的提示；开始新热点、失败、控制结束、全栈重启或切换 USB 时清除屏幕文字。密码不加入状态描述和诊断对象的 toString，不持久化到设置或报告。只改变手动排查入口，不声称修复自动接入。
- 本轮 USB：12:35:07.002 已发 StartSession，12:35:07.157 仍为 tentative（行 2618、2651）；12:35:12.294 地址变为 ready，12:35:17.445 邻居 REACHABLE（行 2660、2672）。监听存在且接口有 RX/TX，但始终没有 AirPlay TCP。邻居可达不代表 TCP 端口可达，计数不表明具体包类型。
- 修改系统 USB 发现：读取可用的 `/proc/net/if_inet6` 标记，排除 tentative、dad_failed、deprecated、地址缺失或接口索引不符的候选地址，最多等待 5 秒再按既有策略回退。procfs 不可读时记录不可用并保留原发现路径；不新增系统配置权限。该修改避免公告已知未就绪的地址，不能据报告证明过早公告就是全部 USB 故障的原因。[Linux 地址标记定义](https://github.com/torvalds/linux/blob/master/include/uapi/linux/if_addr.h)。
- 12:35:37.068 VPN 授权仍因 ActivityNotFoundException 失败（行 2721）；当前 USB 最终失败链路与前两份报告一致。

待实车验证：当前热点是否可见、手动加入及自动加入后的 CarPlay 结果；地址就绪检查后的 USB 首个 TCP；若仍失败，核对实际 TCP 到达、系统路由与过滤及 iPhone 端反应。当前改动尚未构建 APK 或安装。

本轮定向验证：SystemUsbNetworkTest 5 项、SystemUsbDiagnosticsTest 8 项、CarPlayHostSettingsTest 39 项、PreparationLayoutTest 1 项，共 53 项通过，0 失败／错误。包含凭据显示、等待时保留、失败／新热点／USB 切换时清除、状态对象不泄露密码，以及地址校验标记过滤。未运行全量 CI，不代表实车连接已经修复。

150% 字体的窄屏高密度（360×800 dp / xhdpi）与宽屏（1280×720 dp / mdpi）模拟截图在本地 `agent_docs/screenshots/connection-hotspot/{compact,full}-font150.png`。视觉检查发现原居中容器在窄屏中会裁掉内容，等待页已改为可纵向滚动，常规宽屏继续居中；布局测试覆盖 200–720 dp 高度时底部帮助可以滚动到达。本项目仅保留简体中文和基础资源，本次等待页未改变行内对齐，没有新增语言资源目录。这些截图不是实车验证。

## 无线

- MFi 认证成功；11:07:06 接受 IPv6 AirPlay TCP，11:07:09 会话建立并渲染首帧。随后两次统计记录视频实际渲染，说明本次不是始终无法建立无线连接。
- 11:07:22 控制连接发生 `Software caused connection abort`，会话结束。报告不能确定最初中断由手机、车机网络还是用户操作触发；没有证据将其归因于解码能力、认证资料或未知信道 0。
- 重连存在可复核的生命周期重叠：旧 attempt=9 在 11:07:24.773 开始清理；11:07:28.782 等待 4 秒返回 false 后仍启动 attempt=10；旧 Bonjour 到 11:07:34.808 才完成清理（10026 ms），热点到 11:07:34.818 才释放。后续另有相同的新连接启动后出现 `Socket closed` 和热点接口消失的情况。
- 工程修复：4 秒仅作为一轮后台等待和诊断窗口，未完成时继续等待，旧控制器释放后才启动替代连接；退出应用或线程中断可停止等待。保留公共 LocalOnlyHotspot API、系统分配配置和已有状态检查。
- 该修复处理重连期间资源重叠，尚不能解释或宣称修复首次网络中断、所有接入超时及系统热点 generic error。

## USB

- iPhone `vid=0x5ac pid=0x12a8` 已发现、授权、切换到配置 6，并打开 USBMUX、lockdown carkit 和 iAP2；11:17:09.997 MFi 认证被接受。
- 应用选中归属当前 iPhone NCM 的 `usb0`，绑定 IPv6 监听端口 7000，并在 11:17:10.074 发出 StartSession；30 秒未建立 AirPlay 会话，11:17:40.158 进入 VPN 回退。
- 回退授权界面 `com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog` 启动抛出 ActivityNotFoundException，ADB 授权通路 UNREACHABLE，最终错误为 `VPN authorization unavailable`。这证明该次授权启动不可用，不能仅据此认定整个 VPN 包或框架缺失。
- 周期性 `wired io READ TIMED_OUT elapsedMs=100` 是控制链路无新数据时的轮询，期间没有读写失败；不能将它当 USB 传输最初故障。DeviceUnavailable 出现在授权失败后，不能反推为初始线缆故障。
- 工程补充：在系统 USB 网络挂载和启动超时时记录接口是否在线、公告地址是否仍存在及 RX/TX 计数；隐藏设备地址、MAC 和凭据。缺少读取权限时标注 unavailable，诊断不改变网络。
- 待验证：系统 USB 网络上的 iPhone 数据是否到达、邻居发现／路由是否正常，以及 VPN 授权组件的实际可用性。当前报告不足以定位 `usb0` 无 AirPlay TCP 的根因，USB 尚未修复。

## 验证

仅运行与主机重连、显示生命周期、设置重连、诊断日志、USB 服务及系统 USB 网络相关的定向单元测试：common 83 项、shared 4 项，共 87 项通过，0 失败／错误。新增用例确认多次清理等待超时后才创建替代控制器，以及退出时不启动替代连接。

首次组合运行因旧测试模拟控制器始终返回清理未完成而反复循环，最终触发 Java 堆内存不足；已为正常清理的模拟控制器明确返回 true，保留专门的超时／取消用例。修正后按测试类分进程重跑通过。没有执行全量 CI，也没有使用模拟器结果替代真车验证。

用户要求暂停 APK 生成前，上一轮 Gradle 已完成 `:mobile:assembleDebug`（0.1.1 / versionCode 51）；该运行的测试未完成，产物没有交付或安装。收到要求后中止该运行，最终验证命令仅包含上述定向单元测试，不含主应用 APK 构建或安装任务。versionCode 保持 51，避免已生成构建之后回退；应用 versionName 保持 0.1.1。

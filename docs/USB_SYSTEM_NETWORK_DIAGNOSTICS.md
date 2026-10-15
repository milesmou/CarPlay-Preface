# 系统 USB 网卡启动诊断

2026-10-15 后续补充 outgoing NS／NA 等接口计数、同端口其它本地地址的 TCP 表行数及 USB 阶段耗时，字段限制和定向验证见 [诊断日志补充](MEDIA_USB_DIAGNOSTICS_2026-10-15.md)。其它地址的行数不证明属于本应用；没有读取／输出地址或修改路由。

2026-10-14 为星瑞 E02 USB 直连排查加入只读日志。用户报告设备为 IHU353G，参考固件为 IHU519G/FS11A1 构建 327，范围分别见 [连接报告](CONNECTION_REPORT_2026-10-14.md) 和 [固件参考](PREFACE_FIRMWARE_REFERENCE.md)。日志实现不代表新的实车验证；本次不修改网络配置、USB 选择规则或启动期限，USB 无 VPN 回退。

17:50 报告复核：控制器尚未调用已写好的异步采样器，而直接传入多行 snapshot；`DiagnosticRedactor` 拒绝含换行的日志，导致实际报告完全没有网卡快照。后续改为接入 `SystemUsbStartupDiagnostics` 的逐行输出，发现阶段去重记录候选原因；普通 socket 服务同时携带当前 USB listener 身份，TCP 回调排除旧监听和本机探针。监听、StartSession、会话、超时、控制循环结束与关闭都有接入，采样在后台进行，终止不重新阻塞读取。

修正已包含在 0.1.1-58 测试包，50 项相关定向测试通过（shared 18、common 32）；仅安装本地模拟器，尚待车机 USB 失败后的新报告，不代表 USB 接入已修复。

## 报告入口

新增行带 `CONNECTION_DIAGNOSTIC ... system USB` 前缀，沿现有会话日志进入导出的 DiPlay 报告。使用系统 USB 网卡路径时自动记录，无须额外开关。

发现阶段记录候选接口在线状态、iPhone NCM 归属、IPv6 链路本地地址数量与选择／拒绝原因，相同消息在一次发现等待中去重。监听启动后由独立线程采样，正常间隔 5 秒；发送 StartSession、接受外部 AirPlay TCP 时唤醒采样。协议回调不执行文件或命令读取，内部／本机探针不计入 iPhone TCP 接入次数。

2026-10-15 补充：读取选中网卡的 `/proc/net/dev_snmp6/<接口>`，核对 `ifIndex` 与原选中接口索引一致，仅导出固定白名单中的 IPv6 接收、头错误、地址错误、丢弃、投递、发送／无路由、ICMPv6 和邻居发现计数及增量。计数减少标 reset，字段缺失不补零；索引不符、文件不可读或没有有效字段时报告 unavailable，不改读全设备计数。单次快照最多 16 行，足够保留原有 8 行与新增协议计数；仍受原 64 KiB 文件读取限制。字段与接口输出结构核对 [Linux IPv6 proc 实现](https://github.com/torvalds/linux/blob/master/net/ipv6/proc.c)。这些计数不证明具体 TCP SYN 到达，也不能识别系统过滤规则的实际决策。

会话建立、启动超时、传输错误、控制循环结束或控制器关闭时停止采样，输出最后缓存快照及 `sampleAgeMs`。结束快照不是结束瞬间重新读取，必须结合采样年龄判断。

## 字段与含义

| 字段／来源 | 用途和限制 |
| --- | --- |
| ownerMatches、ncmFunctionMatches、indexMatches、macMatches、driver、MTU | 判断当前接口是否仍为选中的 iPhone NCM、是否被替换或掉线；不输出实际 USB 身份或 MAC |
| announcementMatchesBind、portMatchesBind、announcedPort | 核对构造的 iAP2 公告端点是否匹配选中的监听地址和实际端口，不输出地址字面量 |
| advertisedAddressPresent、advertisedState | 对照 NetworkInterface 和 `/proc/net/if_inet6`，记录地址是否存在及 ready／tentative／dad_failed／deprecated 标记；标记本身不能独立证明失败原因 |
| ipv6Routes、linkLocalUpRoutes、rejectedRoutes | `/proc/net/ipv6_route` 中该接口的条目统计；不是带应用 UID 和策略规则的实际路由查询，不能单独证明连通性 |
| ipv6Neighbors、incomplete／failed／reachable／stale 等 | 只读 `ip -6 neigh show dev <选中接口>` 的状态计数。STALE 是正常缓存状态；空列表也不自动表示失败，因为应用不发探测流量 |
| tcp6 listening／synReceived／established | `/proc/net/tcp6` 中匹配公告 IPv6 地址和监听端口的状态，与实际 AirPlay 接入回调对照；排除其它地址和端口 |
| RX/TX packets、bytes、errors、dropped 和 Delta | 选中接口的全部网络流量；计数下降标为 reset，不能把 RX 增长直接认定为 AirPlay TCP 到达 |
| operstate、carrier | sysfs 链路状态；厂商驱动如何更新仍需实车核对 |
| startRequests、tcpAccepted、sessionActive、firstTcpAfterStartMs、waitingFor | 区分未发请求、未接入 TCP、TCP 已接入但协议握手未完成，以及已建立会话 |

## 读取与隐私

proc 文件最大 64 KiB，sysfs 单项最大 32 字节；邻居命令无 shell，最长等待 250 ms，输出最大 16 KiB，仅解析状态计数。没有调用 root、ADB、ping、网络配置或探测接口，没有写 sysfs，没有新增权限。

来源不可读、格式不匹配、命令不可用或超时会记录 `unavailable` 和异常类名，并停止重试该来源；其它来源继续采样。不能将 unavailable 当成 0，也不能将此前读取失败后的状态描述为重新确认。不输出原始命令 stderr、异常消息、IP、MAC、设备标识、密码、认证数据或数据包。收发计数分为短行，避免报告的 700 字符上限截断后半部分。

IPv6 地址标记参考 [Linux if_addr.h](https://github.com/torvalds/linux/blob/master/include/uapi/linux/if_addr.h)。状态诊断不能替代实际流量抓包或 iPhone 端证据。

## 如何判断下一步

| 报告现象 | 优先检查 |
| --- | --- |
| candidate 被拒绝、eligibleCount=0 或 ambiguous | USB 归属读取、配置／接口号、接口在线及地址／MAC；不要直接改选同名网卡 |
| owner/index/mac 改变，或地址 absent | 接口重新枚举、原厂服务接管、驱动和地址生命周期 |
| tentative／dad_failed 持续存在 | IPv6 地址校验及重复地址问题，结合链路状态确认 |
| StartSession 已发、接口 RX 长时间不增加 | iPhone NCM 数据是否启用、设备配置、驱动和物理链路；仍不能排除其它原因 |
| 邻居持续 INCOMPLETE／FAILED | 邻居发现未完成或失败，结合 RX/TX 判断收发方向 |
| RX/TX 增长、tcpAccepted=0 | 数据种类未确认；继续核对公告端点、路由／过滤和监听，不将计数当作 TCP 到达证明 |
| tcpAccepted>0、sessionActive=false | 查看已有 AirPlay 配对／加密／SETUP 日志，定位协议握手 |
| sessionActive=true | 此次系统网卡路径已建立 AirPlay 会话，音视频和后续掉线另行验证 |

## 验证

31 项定向测试通过：SystemUsbDiagnosticsTest 8 项、SystemUsbNetworkTest 4 项、DiagnosticRedactorTest 11 项、CarPlayConnectionDiagnosticLogTest 2 项、CarPlayControllerUsbServiceTest 6 项，0 失败／错误。覆盖地址标记、接口过滤、路由条目、邻居脱敏、TCP 地址和端口匹配、不可读来源、计数增长／重置、内部探针排除、阻塞采样时协议回调和关闭，以及报告导出。未执行全量 CI，未构建或安装 APK；这些诊断仍需包含改动的后续版本在实车导出报告验证。

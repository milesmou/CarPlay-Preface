# USB 自动 NCM 帧测试

后续日志补充见 [音源、蓝牙与 USB 定位日志](MEDIA_USB_DIAGNOSTICS_2026-10-15.md)：加入 DHCP 消息类型、CDC 链路／速率通知、接口接管结果及阶段耗时。现有 64 包不含这些后续字段；连接与测试策略不变，未新增实车成功结论。

更新：2026-10-15。按用户要求，选择 USB 后自动进行诊断。此实现用于星瑞 E02；不是 USB 实车连接已修复的证据，也不是应用内 TCP/IP 协议栈。

## 自动触发与资源归属

1. 正常连接仍使用当前 iPhone 的系统 NCM 网卡及普通 socket。逐行记录系统网卡 RX/TX、IPv6、邻居、真实监听身份与端口等证据，见 [系统网络诊断](USB_SYSTEM_NETWORK_DIAGNOSTICS.md)。不在正常连接或已建立会话时 claim NCM。
2. 当前 iPhone 没有可用系统网卡、监听失败，或发出 StartSession 后 30 秒仍未建立 AirPlay，会自动尝试原始 NCM 测试。后者由 `SystemUsbFallback` 同步判定，已经建立过的会话不会触发。无线连接不触发此测试。
3. 保留 USB 授权及当前 iPhone 身份；通过 GET_CONFIGURATION 核对活动配置与 NCM 描述符一致。单独打开 USB 连接，只强制 claim NCM control/data 接口，不 claim USBMUX，不执行 SET_CONFIGURATION。原有 iAP2、配件认证与 StartSession 保持运行。
4. 读取当前 NTB 格式和 CRC 模式，记录接口可读性；已知 NTB32 或附带 CRC 时明确停止，当前 codec 仅支持 NTB16、无 CRC。尝试启用 directed/broadcast/all-multicast 接收过滤，记录是否被接受；不更改 NTB 格式，不将读不到配置或过滤请求被拒绝记作收发成功。
5. iPhone 在 StartSession 前可能尚未开放 NCM bulk OUT。测试窗口在 StartSession 发出后才开始，持续 15 秒，在 0/5/10 秒最多发送三个 ICMPv6 Echo 探测；没有已知手机地址时使用链路 multicast，收到有效 ICMPv6 后可选择 link-local unicast。认证或启动消息未完成时，报告无法进行有效测试；等待启动消息有 60 秒上限，认证阶段沿用原控制流程的有限超时。
6. 接收合法、目标为测试公告地址的 Neighbor Solicitation 后回应 Neighbor Advertisement，核对 hop limit、校验和、长度、code、目标和选项；DAD 请求使用不带 Solicited 标记的 multicast 回复。测试不提供 TCP/UDP 服务，收到的 TCP/UDP 仅统计头部，不回应握手。
7. 结束、取消、拔线或异常都释放测试持有的 NCM 接口和独立 USB 连接。测试开始即停止主机自动重连，避免旧 socket 错误抢占测试；完成后保留手动重试。强制 claim 可能解除系统驱动绑定，release 并不保证驱动自动恢复，必须提示重新插拔 USB 后再连接。不使用 root、VPN/TUN，也不修改系统网卡配置。

初始配对、USBMUX 或认证失败时可能无法达到数据通道测试条件，不能为了“自动测试”跳过它们。原始探测公告的地址／端口只用于触发手机数据通道；没有对应 AirPlay 服务时，不将它记录为成功监听或已连接。

## 日志与结果解释

搜索诊断报告中的 `NCM frame test`。触发、设置、开始、5 秒采样与最终结果都是单行脱敏日志；不导出原始帧、歌曲数据、nonce、MAC、IP 或配对资料。

### 2026-10-15 报告后的定位日志补充

针对本次 IHU353G 报告里“2 个 NTB16、unsupported=2、3 次 OUT 未接受”的未决证据，增加以下日志。只改变诊断，不改变配置、发送超时、探针数量、会话归属或正常连接路径：

- `endpoints`：所用 bulk IN／OUT 的 USB 端点编号、包大小、类型及 control interface 编号。端点编号不是 IP／MAC／手机身份。
- `control`：GET_NTB_FORMAT／GET_CRC_MODE 请求编号、实际返回长度、期望长度与耗时，继续保留解析后的格式／CRC 或 unavailable。不会发 SET_NTB_FORMAT，也不记录返回字节。
- `write`：帧／NTB 长度、调用者超时、实际短超时、耗时、原始 API 数字返回及结果分类。最多记录前 12 次明细，之后仅累计；`usbOutCalls/full/negative/zero/partial/exceptions/maxOutUs/lastReturn/lastOutcome` 随采样和最终结果导出。负返回值称为 `negative_api_result`，不推断为明确 NAK、errno 或超时；零写入与部分写入不算完整接受。异常只记类名，仍按原路径抛出和释放。
- `ethernetHeaders`：IPv4／IPv6／ARP EtherType、VLAN、短帧、其他类型的计数；其他 EtherType 最多保留 4 个协议编号，不保存帧或载荷。
- `protocolHeaders`：通过基本长度与版本检查后的 UDP、DHCPv4、mDNS、到固定 7000 端口的 TCP SYN、ICMPv6／NS／NA／Echo 头部分类；IPv4 分片及 IPv6 扩展头单独计数，不猜测其后传输层。标记 `headerOnly=true`，这些分类不验证全部校验和，也不构成连通性证明。VLAN 只用于分类，未增加 VLAN 通信支持；原探针的有效 NS／Echo 判断仍由原实现严格校验。
- 每个 sample/result 分成探针结果、Ethernet 分类、协议分类、USB IN／OUT 汇总多行，避免触发报告 700 字符截断；每次仅一行携带最终 outcome。探针窗口完成和双向成功的区分不变。

正常内核网卡路径同时增加 [指定接口 IPv6 协议计数](USB_SYSTEM_NETWORK_DIAGNOSTICS.md)，帮助区分网卡接收与 IPv6 投递／丢弃。原始测试强制 claim 后的写入结果仍不能倒推正常内核路径是否能发包。

API 负返回值的解释依据 [Android UsbDeviceConnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)；CDC 常量仍核对 [Linux CDC 声明](https://github.com/torvalds/linux/blob/master/include/uapi/linux/usb/cdc.h)。这些新增计数没有确认实际失败原因，仍须新包在车机运行后导出报告。

本次定向验证共 63 项通过（shared 51、common 报告脱敏 12）：包括分类与截断输入、OUT 返回／异常／日志上限、原 NTB 分段接收和 NDP／Echo 校验、测试窗口释放、指定网卡计数与不可用／索引替换、超过旧 8 行限制后的导出。随后单独复核 12 项系统 USB 诊断测试通过。没有运行全量 CI，也没有构建或安装新 APK；现有 0.1.1-63 不包含这些日志。

| 字段 | 证据及限制 |
| --- | --- |
| `usbInTransfers` / `usbInBytes` | bulk IN 实际返回的次数／字节数；可区分没有 USB 数据和收到数据但帧解析失败 |
| `ntb16Blocks` / `emptyNtb16Blocks` | 识别到完整 NTB16 头部的块／未提取出 Ethernet 帧的块，不能单独证明 IPv6 可达 |
| `rxFrames` / `ipv6` | 提取出的 Ethernet 帧数／IPv6 基本头部及声明长度可接受的帧数；ICMPv6 校验失败另计 `rejected` |
| `ns` / `tcp` / `udp` | 目标邻居请求和可识别协议头部计数；不会据此判定 AirPlay 成功 |
| `txAttempts` / `txAccepted` | 实际写入尝试／完整 NTB 被 USB bulk OUT 接受；NAK、超时、零写入不计接受，部分写入明确报错 |
| `echoReplies` / `bidirectionalConfirmed` | 来自链路对端、校验正确、地址及 id/sequence/随机数据与已接受探测匹配的 Echo Reply；去除重复响应。至少一个匹配才确认该探测的双向通信 |
| `outcome` | 窗口完成、取消、I/O 异常或未发出 StartSession；窗口完成本身不代表测试通过 |

`txAccepted > 0` 仅证明 Android USB 接受写入。收到 NS／TCP 可证明手机向应用送来了帧，但不能单独证明手机收到了应用发送的帧。没有 Echo Reply 是未确认：手机可能不响应 multicast、过滤 ICMP 或尚未开放数据通道，不能直接定性为 OUT 故障。即便 `bidirectionalConfirmed=true`，也只确认本次探测，仍需验证完整 AirPlay 的 TCP/UDP、动态端口、重传、音视频与 Siri。

测试格式依据：[ICMPv6 Echo（RFC 4443）](https://www.rfc-editor.org/rfc/rfc4443)、[NDP 验证与回复（RFC 4861）](https://www.rfc-editor.org/rfc/rfc4861)；CDC 控制请求与格式常量核对 [Linux USB CDC 定义](https://github.com/torvalds/linux/blob/master/include/uapi/linux/usb/cdc.h)。没有将 Linux 内核驱动或其他车型的代码直接移植入应用。

## 验证与实车操作

定向测试覆盖 NTB 分段／合并接收与计数、完整写入与 NAK／部分写入、NCM 格式检查、NDP 非法输入、匹配／重放／错误 Echo、仅写入不误报双向、窗口与取消资源释放、报告脱敏及主机重试隔离。未运行完整 CI，USB 帧实车收发尚待验证。

本轮最终验证：shared 37 项、common 40 项定向测试全部通过；`:mobile:assembleDebug` 成功，实际 APK 版本为 0.1.1 / 59，现有发布证书 SHA-256 核对一致。APK SHA-256：`16ab3fb9b3c35c75eadf47269b42d5dda26b841d29839cfcc34a6dde79f241d0`。构建后 ADB 没有在线设备，未安装，未启动模拟器，也未执行实车收发测试。

安装测试包后，重新插拔 iPhone USB，选择 USB，等待自动测试结束并保存诊断报告。若正常进入 CarPlay，保留正常会话，导出系统网络诊断即可；测试不会中断它。原始帧测试结束后，下一次连接前再次重新插拔 USB。

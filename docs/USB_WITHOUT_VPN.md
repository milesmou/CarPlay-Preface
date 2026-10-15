# 星瑞 E02 USB 连接不依赖 VPN 的方案

分析日期：2026-10-14。当前应用实现、用户 IHU353G 运行报告与参考 IHU519G/FS11A1 构建 327 的静态证据分别记录，不能互相当作已验证依据。现按用户要求将 USB 改为系统 NCM 网卡上的普通 socket 服务直连，不建立 VPN，也不请求 VPN 授权；仍未获得新的实车验证结果。

## 当前失败位置

最新 `DiPlay-20261014-175036-680.txt` 已运行无 VPN 路径：四轮完整 USB 尝试都通过 lockdown、iAP2 和 MFi 认证，IPv6 监听 7000 已建立，StartSession 后约 30 秒没有 AirPlay TCP／会话，第五轮在导出时仍连接中。控制通道期间还能交换媒体状态及封面，不能把无新控制数据时的 100 ms READ TIMED_OUT 当作 USBMUX 中断，也不能据此证明 NCM 数据通路可达。

构建 57 的网络快照作为多行字符串交给 `debugLog`，被报告过滤器整行拒绝；已有异步采样器没有接入实际控制器，缺少 RX/TX、邻居和路由证据。同时主机页面忽略 `Failed.retryable=false`，不停完整重连。后续工程接入真实 USB 监听身份的 TCP 回调和 5 秒异步采样，逐行写入脱敏报告，记录实际监听端口及终止时的缓存年龄；页面尊重不可重试标记并保留手动按钮，菜单中失败也不能触发旧排队重试。授权、系统网卡归属及 IPv6 检查继续保留。

这些修正解决诊断丢失和无效重试，没有确认或修复 iPhone 不建立 AirPlay TCP 的网络根因。下一次实车报告需要区分 NCM 完全无流量、邻居发现失败、TCP SYN 未到达或协议握手失败；当前报告不能给出该结论。

历史报告已经显示 USB 设备授权、lockdown carkit、iAP2 及 MFi 认证成功；应用发现归属当前 iPhone NCM 的 `usb0` 并绑定 IPv6 端口 7000。当时 StartSession 后 30 秒没有建立 AirPlay，旧 VPN 回退因授权 Activity 无法启动而失败。历史授权页异常不能独立证明整个 VPN 框架被删除；现有应用按不依赖 VPN 的约束实现。

证据与原始报告校验值见 [连接排查](CONNECTION_REPORT_2026-10-14.md)。仅删除 VPN 调用不能补齐系统网卡未建立会话的问题。

## 方案比较

| 路径 | 当前进度 | 条件与下一步 |
| --- | --- | --- |
| 使用系统为 iPhone 生成的 NCM 网卡 | 已切换至普通 CarPlaySocketService，USB 不再绑定 VpnService 或回退 VPN；旧报告完成监听但未建立 CarPlay 会话 | 优先核对该 USB 接口的驱动、地址状态、IPv6 邻居发现、路由与过滤，以及公告地址／端口是否匹配；新增接口 RX/TX 诊断可提供线索。正常路径不需要 VPN 或应用配置系统网卡 |
| 应用内 TCP/IP 协议栈接管 NCM | 尚未实现；NcmUsbBridge 提供 NTB16 / Ethernet 收发，已新增失败后的自动原始帧诊断，但没有到普通 Android socket 的无 VPN 通路 | 可评估 lwIP 等协议栈，处理 IPv6 链路本地地址、NDP、TCP、UDP，并适配 AirPlay 控制连接、事件连接、iAP 隧道、动态视频／音频端口、定时与重传。设计上可不依赖 VPN/root，仍要求 USB host 授权、能实际接管 NCM 接口及性能验证 |
| 系统／root 辅助配置网络 | 尚未实现，当前没有此权限可用性的证据 | 获得相应权限后可评估内核网卡配置或 TUN/TAP 桥接，但受内核能力和 SELinux 等条件约束；不是普通第三方 APK 的默认可用路径，本次不修改系统配置 |

短期优先排查已经存在的 `usb0` 路径。如果其依赖的固件网络能力无法满足要求，再评估应用内协议栈。后者是传输层开发，不是修改一项 VPN 开关；不能因现有 USB 认证通过就保证画面、音频与 Siri 可用。

## HiCar 下层的进一步证据

已继续读取同一参考 OTA 的 `HwNearbyCar.apk`，SHA-256：`c3ce640924c7e39b6be75d9cadfa53f6e876de8f44c3efd6ae55013fd0579406`。本地源码在忽略目录 `agent_docs/firmware/ihu519g-327/decompiled/nearby/sources/`。

- `com/huawei/nearby/channel/e/a/f.java:72,99` 的 `UsbNcmChannel` 监听系统网卡 `ncm0_reserved` / `wwan0` 的地址与状态；`:88` 在实际 IPv4 地址上绑定 ServerSocket。`:167` 起使用 Huawei USB NSD 服务及 `ncm.ip` 属性建立连接。这里观察到的是内核网卡和普通 socket 的通信路径，没有在该路径发现 VpnService 创建调用。
- 同文件 `:448,462` 经 NetworkImpl 启动 DHCP 或配置静态 IPv4。
- `com/huawei/nearby/channel/e/a/a.java:39` 调用 `INetworkManagementService.getInterfaceConfig/setInterfaceConfig` 设置 `/24` 地址并启用接口；`:98` 的 DHCP 路径在 `:111,115` 调用 `tetherInterface/startTethering`。
- 同文件也有接口关闭、untether 和 stopTethering 的代码，因此不能直接在 CarPlay 中对同名网络接口调用它；接口名称和 Huawei IPv4 协议都不能当作 iPhone NCM 归属或 CarPlay IPv6 可达性的证明。
- `UsbNcmChannel` 的 `setCurrentFunction` 反射调用受 `SDK_INT >= 10000` 门槛限制（`f.java:307`），不能把该方法视为参考 Android 9 可用的 USB 角色切换接口。
- Nearby manifest 声明系统 UID 和 `CONNECTIVITY_INTERNAL` 等特权。服务权限与调用者检查见 [HiCar 热点分析](HICAR_HOTSPOT_327.md)。原厂可使用这条网卡配置路径不证明本应用能访问同样的接口，也不证明其可以承载 iPhone CarPlay。

本次引用的 NetworkImpl、网卡观察及地址／socket 方法可完整读取；UsbNcmChannel 构造函数有 JADX 的 super 调用移位警告。只确认静态调用与接口形态，运行顺序、实际内核驱动、权限、端口和实车可用性仍需验证。

## 应用内协议栈的依据和范围

[Android USB host 文档](https://developer.android.com/develop/connectivity/usb/host) 提供设备授权、接口 claim 与 bulkTransfer 的应用层收发方式；当前 NcmUsbBridge 已在此基础上实现 NTB16 / Ethernet 帧传输。这并不自动生成 Android 网络接口或 Java socket 的路由。

[lwIP 官方仓库](https://github.com/lwip-tcpip/lwip) 列出 IPv6、Neighbor Discovery、TCP 和 UDP 能力；其 [raw API 文档](https://www.nongnu.org/lwip/2_0_x/raw_api.html) 描述了应用层接入方式。由此推断，可以设计以 NCM 帧为网卡输入／输出的应用内协议栈；这不是现成的 CarPlay 支持或星瑞适配证明。本次没有选定版本、下载依赖或实现移植。接入时还需审查许可证、线程模型、校验和／分片处理、缓冲和性能，完成协议层及实车端到端验证。

现有 AirPlay 依赖 Android 的 ServerSocket、Socket 和 DatagramSocket。接入应用内栈需要传输抽象或本地 TCP/UDP 中继，不能只把 Ethernet 帧写入普通 socket。iAP2/USBMUX 认证通道本身仍需保留。

## 本次实现：普通服务直连

主机页面的 USB 启动先决条件不再包含 VPN 授权，移除授权页和 ADB VPN 预授权入口。CarPlayController 的系统 USB 和无线监听均绑定非导出的 CarPlaySocketService（普通 Android Service），不建立 TUN，不 claim 系统已管理的 NCM 接口，不修改系统网络配置，不依赖 root。USB host 设备授权、USBMUX/lockdown、iAP2/MFi 认证继续保留。

系统 USB 发现只接受归属当前 iPhone NCM、接口在线且 IPv6 链路本地地址就绪的候选，使用实际接口 scope ID 绑定 AirPlay，并公告实际监听端口。没有可用系统网卡、监听失败或发送启动消息后 30 秒仍未建立 AirPlay 时，停止本次连接并报告 USB 网络故障，不切换 VPN，也不自动循环重试。认证耗时不计入 30 秒窗口，已建立会话不因该窗口被终止。

这是对系统网卡直连的实现和启动流程修正，不是应用内 TCP/IP 协议栈；没有系统 NCM 网卡的固件仍不能通过此路径连接。旧报告已经出现 usb0 但没有 AirPlay TCP，根因尚未闭环。普通服务替代 VpnService 不能保证解决接口驱动、邻居发现、路由过滤或 iPhone 接入失败。旧 VPN 类暂留在源码供历史实现及测试参考，主机和控制器已不调用它。

## 2026-10-15：USB 选择后自动测试

按用户要求，正常系统网卡路径失败后自动尝试 15 秒原始 NCM 帧测试，保留 iAP2/MFi 并在 StartSession 发出后测试，不在已建立会话上抢占接口。记录实际 bulk IN 字节、NTB16、IPv6、完整 OUT 写入及匹配的 ICMPv6 Echo Reply，分别给出证据；这只是诊断，不增加 AirPlay 备用网络或应用内 TCP/IP 栈。测试可能解除系统 NCM 驱动绑定，结束后释放接口、停止自动重试并提示重新插拔。范围、协议限制及操作见 [自动帧测试](USB_NCM_FRAME_DIAGNOSTICS.md)。真实收发和连接效果尚未确认。

## 2026-10-15：从 USB 切换到无线

用户反馈 USB 使用后拔线，点击主页“无线连接”仍进入 USB，必须先手动断开。主页此前仅根据后台是否保留连接决定直接打开投屏页，因此也复用了等待／诊断中的 USB 控制器。

现改为读取后台控制器实际且不可变的连接方式；仅复用未关闭的无线控制器。后台为 USB、已关闭或仅剩待释放资源时，走既有连接切换流程，等待旧连接停止完成后保存无线方式并打开投屏页。不能以供下次连接使用的偏好值判断当前控制器类型。已存在的无线连接继续复用，不因此重建热点。

`HomeTransportSwitchTest` 的 6 项测试覆盖 USB 等待／活动状态、偏好值与实际控制器不同、无线复用、关闭控制器及仅剩后台资源的切换顺序；连同启动入口和无线恢复测试共 25 项通过。此修正已包含在 0.1.1-64 测试 APK 中，仍需实车验证；它不代表 USB 数据通路或无线自动加入问题已修复。

# 恢复上游 USB NCM＋VPN/TUN

2026-10-16，按用户“恢复上游的 VPN USB 连接方案”的最新要求实现；此要求替代此前 USB 不使用 VPN 的约束。构建 81 接回链路，构建 82 补齐首次拒绝授权后、尚无连接实例时的手动重试入口。当前交付版本为 0.1.1 / 82。

## 来源与两版比较

基准是官方 [DiPlay v0.2.20，提交 3c95c936](https://github.com/shihabal3amri/DiPlay/tree/3c95c936859e8a9153f0040caf2337adddb9e3e4)，参考对照 [huangwits v0.2.20.1，提交 e59ca212](https://github.com/huangwits/DiPlay-Preface/tree/e59ca212150175bfd804701901252084a42f7caf)。比较固定标签的实际源码，不能用两个仓库不断变化的默认分支代替。

两版核心方案一致：iPhone USB 配置／重枚举 → USBMUX／Lockdown 配对 → CarKit iAP2 与配件认证 → NCM Ethernet/IPv6 ↔ 本地 VPN/TUN ↔ Android TCP/IP → AirPlay。`openDataPaths`、`openNcm`、VPN 服务绑定及附件建立的关键操作相同；`Ipv6NcmBridge`、NCM 描述符发现、NTB16 与 Ethernet/IPv6 codec 的 Git blob 一致。分支存在低版本 Android 的 USB transfer 兼容、设备节点刷新及日志差异，不构成另一套 USB 网络方案。本次不移入其 E01 功能或低版本兼容层。

精确源码证据保留在本机忽略目录 `agent_docs/usb-source-comparison-20261016/`，`manifest.json` 逐文件记录提交、Git blob 和 SHA-256。官方源文件 SHA-256：

- `shared/.../network/CarPlayVpnService.kt`：`8991662ae3d3862062e5b1cc02df36e70858015187d8758c3cee9cdaabda64ba`。
- `shared/.../network/Ipv6NcmBridge.kt`：`31324516963611ebb80bf23e7d22a3d76db118f5f25e200f59cc3301c5f23aac`；两个标签一致。

## 本仓库接入

- 首页选择 USB 后，主机在启动 USB 控制器前调用 `CarPlayVpnService.prepare()`，已授权时直接继续；系统弹窗接受后复核准备状态。拒绝、系统组件缺失、准备或启动失败时明确提示，停止自动重试；用户可再次点击重试。没有连接实例时也能重试，不自动使用 ADB 修改 VPN app-op。
- 保留现有 USB 授权、配置选择、同一枚举重连、重新配对和 MFi 认证。NCM 在独立 USB 连接上打开，核对活动配置，只 claim 当前 iPhone 的 control/data 接口，不重新设置配置或抢占 USBMUX。
- 接回现有上游 `CarPlayVpnService.attach()` 与 `Ipv6NcmBridge`。TUN 使用链路本地 IPv6 `/64`、路由 `fe80::/64`、MTU 1500，并在 `establish()` 前只允许当前应用包；允许列表失败时直接失败，不建立全设备 VPN。无 root、无远程 VPN 服务器，也不要求系统先生成 NCM 网卡。
- 宣告 TUN 的 IPv6 地址、实际绑定的 AirPlay 端口及 NCM 的主机 MAC。保留 link-local 监听的接口 scope 查找。
- 服务以 `BIND_VPN_SERVICE` 保护、非 exported。系统的 `VpnService.SERVICE_INTERFACE` 绑定保留框架撤权 binder；应用显式绑定返回本地 binder。撤权、桥接错误及 TUN 建立失败会释放资源并停止自动重试。
- 断开、取消、拔线、控制通道结束或失败时关闭本次 NCM/TUN、监听、CSM 和 USBMUX；取消也唤醒等待服务绑定的工作线程，迟到的绑定回调不能恢复已关闭控制器。
- 保留本地的初始连接边界：成功发出 StartSession 后 30 秒内未建立 AirPlay 则明确失败。认证等待不占用此窗口；重复发送不延长窗口；已建立会话不受该初始计时限制，也不会自动切换为独立帧测试。
- 无线继续使用原 `CarPlaySocketService` 和热点资源归属，不请求 VPN、不建立 TUN。设置“授权”的手动 VPN 按钮仍仅授权，页面渲染、搜索和恢复不调用准备接口；只有 USB 启动和明确的授权动作调用。

此前系统 NCM 网卡优先、失败后自动执行独立原始帧测试的接入已移出控制器；辅助实现和历史报告保留用于查证，不与实时桥接争抢接口。现有星瑞音频、方控、临时热点保留、1920×720 显示及界面大小修改继续保留。

## 验证边界

**138 项定向测试通过**（shared 54、common 84，0 失败／跳过），覆盖 USB/VPN 授权、服务绑定、取消与迟到回调、撤权、应用允许列表拒绝、监听 scope、NCM 分帧／写入超时、USB 配置及会话记忆、初始连接超时、无线监听和手动授权页面。授权逻辑与服务边界覆盖 API 28／33；首次拒绝后无连接实例的重试已验证。没有运行全量 CI。

`:mobile:assembleDebug` 成功，最终 APK 为 `CarPlay-Preface-0.1.1-82-debug.apk`，SHA-256：`4e283aa0f2b70dfbbffa6e29fcc482c73a00285ef157462d4f211396d4ab9e1d`。证书 SHA-256 核对为 `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`，已使用 `adb install -r` 覆盖安装在线设备 `emulator-5558`，保留应用数据。

授权页面以安装包实际运行的 1920×720 横屏截图核对；紧凑窗口、大字体和 RTL 另外使用明确标记的单元测试渲染图。实际截图、参数、测试 XML 和构建记录存放于本机忽略目录 `agent_docs/usb-vpn-82/`；大字体测试图在 `agent_docs/screenshots/authorization-test-renders/`，均保持 1920×720 横屏。

参考固件仍为 IHU519G / FS11A1 / Android 9 API 28 / 构建 327，来源 SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。本次是应用接入，未增加该固件的 VPN/TUN 可用性证据。旧 IHU353G 报告出现过授权 Activity 缺失，也曾记录 NCM OUT 未接受；设备身份与参考 OTA 不同。安装原生设置不等于系统一定提供可用的 VPN 服务和 TUN，仍需在用户车机验证授权、USB 音视频、Siri、拔线及再次连接。

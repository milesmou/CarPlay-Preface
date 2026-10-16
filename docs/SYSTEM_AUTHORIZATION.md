# 系统授权入口

构建 86 当前行为：主 APK 已内置独立签名的授权组件；顶部提示改为“系统授权组件”。兼容且缺失时显示手动安装按钮，调用系统安装器，经用户确认后再分别授权；已有组件则跳过补装。已就绪补充包的修改系统设置页使用明确组件入口；VPN 保留系统确认 Intent，见 [单 APK 引导补装](ANDROID9_AUTHORIZATION_COMPONENTS.md)。下方“先安装原生设置”文案记录为历史。

2026-10-16 用户要求补装系统授权组件后，新增独立 [Android 9 授权补充包](ANDROID9_AUTHORIZATION_COMPONENTS.md)，提供 VPN ConfirmDialog 和修改系统设置授权页；仅适用于平台签名匹配、权限可授予的 Android 9 车机。无需整套 Settings，原页面“请先安装原生设置”为历史文案。补充 APK 已构建签名，IHU353G 实车尚未安装验证。

当前状态（构建 82）：按用户最新要求恢复 [USB NCM＋本地 VPN/TUN](USB_VPN_RESTORE_0.2.20.md)。设置分类名称为“授权”。手动 VPN 按钮仍只申请授权；选择 USB 时也按需请求授权，连接流程随后建立本应用专用 TUN。无线不请求 VPN。以下构建 77 记录为历史，关于“USB 不启用 VPN”的描述已被构建 82 替代。

构建 77（0.1.1），按用户要求新增“设置 → 授权功能”。沿用现有设置配色、卡片、行高和间距，宽屏分类栏与紧凑分类条均可直达，默认设置页仍为“连接”。

页面顶部显示“请先安装原生设置”，并说明 VPN 还需要系统提供相应组件。该提示不代表安装任意 Settings APK 就能补齐 VPN 服务、授权组件或 TUN 支持。

## 修改系统设置

- 页面说明实际用途：连接前自动开启手动车机热点仍需系统支持；临时热点不需要此权限。
- 通过 `Settings.System.canWrite(context)` 显示本应用的当前授权状态，返回应用后刷新。
- 点击后打开 `ACTION_MANAGE_WRITE_SETTINGS`，URI 为本应用的 `package:`。已有 `WRITE_SETTINGS` 声明继续复用。
- 打开失败时保留页面并显示原生设置／系统组件提示，不将成功打开页面等同于已经授权。

## 创建 VPN

- 页面说明其可供 USB 有线连接的 VPN 方案使用，同时明确当前版本尚未启用该方案，授权不会直接建立连接。
- 只有手动点击后调用 `VpnService.prepare(context)`；返回 Intent 时交给系统显示授权弹窗，返回 null 时显示“上次已授权”。
- 系统回传成功后再检查准备状态，仍返回授权 Intent 时显示尚未完成；取消时显示可重试，不重新检查或报为授权成功。
- 状态明确标记为最近一次手动检查结果，不在页面渲染、设置搜索或 `onResume` 时调用 `prepare()`。该 API 可能改变系统当前准备的 VPN 应用，不能作为无副作用的持续权限查询。
- 系统组件缺失、禁止启动授权页或准备接口抛异常时显示不可用；不伪造系统授权，不启动 VPN 服务，不创建 TUN。
- USB 连接保持现有系统 NCM 网卡和普通 socket 路径，此页面不启用 USB VPN 回退。

API 依据：[修改系统设置](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_WRITE_SETTINGS)、[VpnService.prepare](https://developer.android.com/reference/android/net/VpnService#prepare(android.content.Context))。

## 验证范围

定向验证授权请求、取消、准备失败、页面缺失、授权结果复核、返回刷新和分类归属。截图固定为 1920×720 横屏，覆盖完整布局、提高密度后的紧凑布局、150% 字体与 RTL 行排列；应用正式界面仍固定中文。

37 项定向测试通过（Android 9／API 28 与 API 33 的授权逻辑，以及分类、文案和页面测试）。仅构建 `:mobile:assembleDebug`，不代表完整 CI 已通过。截图与测试 XML 保留于本地忽略目录 `agent_docs/screenshots/authorization/`、`agent_docs/authorization-77/test-results/`。

以上为构建 77 的历史记录。后续构建 78 按用户确认的 12.3 英寸屏幕调整自动界面大小，并改用原生运行截图交付预览；旧 160 dpi／150% 字体的测试图不能代表 240 dpi／100% 字体的安装效果，详见 [屏幕适配与预览规则](PREFACE_SCREEN_LAYOUT.md)。

测试包为 `CarPlay-Preface-0.1.1-77-debug.apk`，SHA-256：`691b0b8987ed778c817db86f69c0de606ce82fa7491884ebfc0c7e02cbc05e5e`。已核对沿用现有签名证书，最终产物通过 `adb install -r` 安装到在线设备 `emulator-5558`，保留应用数据；未在实车验证。

此处为应用实现，不增加 IHU519G／FS11A1 构建 327 的固件能力声明。参考固件来源 SHA-256：`f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。IHU353G 的旧报告曾出现 VPN 授权 Activity 缺失，安装原生设置后的实际授权及 VPN/TUN 能力仍需分别在车机验证。

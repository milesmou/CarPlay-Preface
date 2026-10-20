# Android 9 授权组件

当前交付：CarPlay **0.1.1 / 97**，内置组件 **preface-android9-8 / 8**。组件包名 `com.android.vpndialogs`，与主应用独立签名；只适用于 API 28、平台证书匹配且实际权限可授予的系统。

当前源码已构建：CarPlay **0.1.1 / 97**、组件 **preface-android9-8 / 8**。主应用已覆盖安装到 API 34 模拟器；内置组件仅核对打包与身份，不在该模拟器安装或授权。按用户 2026-10-19 要求，现有六项授权扩展到当前用户下符合条件的其他已安装应用，下表描述组件 8 的实现；实际授权效果仍待 API 28 目标设备验证。

## 使用与升级

主 APK 已包含组件，无需另传第二个 APK。在 CarPlay“设置 → 授权”点击“保存授权 APK”，保存到公共 `Download` 根目录，再由用户通过文件管理器手动安装。主应用不调用 APK 安装器、不申请安装来源权限、不静默安装或授权。

“保存授权 APK”和“打开保存目录”始终同行、等宽等高。已安装就绪的本仓库组件仍可保存；目录已有相同内容时提示已保存，不重复写入或自动打开目录，不覆盖同名不同内容文件。目录按钮独立可用，优先系统下载入口，再尝试文件管理器；系统受理不等于目录实际显示。Android 9 缺少存储权限时仅请求导出所需权限。

组件 7 使用 `android.uid.system`。旧独立 UID 补丁不能直接覆盖升级：先保存新版，再**仅手动卸载本仓库旧授权补丁后重装**，保留 CarPlay 和数据。原厂／未知同名组件不覆盖、不卸载；平台不匹配时跳过导出。组件没有桌面图标，由 CarPlay 或标准 Settings Intent 提供入口。

## 功能与边界

| 功能 | 限制 |
| --- | --- |
| VPN 确认 | 任何符合条件的当前用户应用可发起系统 ConfirmDialog；目标只取 getCallingPackage()，须有已启用、对系统导出的 BIND_VPN_SERVICE 服务，保留用户限制及其他 always-on VPN 检查。没有代选应用／Intent extra 授权入口，仅授权，不创建 TUN；其他应用的实际 VPN 范围由其自身实现决定。 |
| 修改系统设置、悬浮窗 | 支持当前用户下已安装、启用、未暂停且声明对应权限的应用，不要求目标采用 CarPlay 签名；记录并复核 UID、版本和签名。标准 Settings Intent 无 package: 时选择应用，有 package: 时进入该应用的确认页；逐项允许／撤销、读回状态，保留系统策略检查。悬浮窗不再额外要求 WRITE_SETTINGS。CarPlay 仍优先原生页面，缺失时使用就绪补丁。 |
| 安装未知应用、使用情况访问、无障碍管理 | 仅补丁标准 Settings 入口提供；选择同用户下已安装、声明对应权限／服务且符合策略的应用，CarPlay 主授权页不增加这些项目。 |
| 蓝牙音乐连接代理 | 仅供两个已核对签名的 CarPlay 包使用；只连接同用户、仍配对的所选 iPhone A2DP sink，直接接口不可用时才使用。保留通话、其他手机及会话取消检查，以真实查询确认连接。 |

六项授权均校验当前用户、目标 UID、签名、版本、启用状态及权限／服务声明；签名校验用于确认用户选中的身份未发生变化，不将其他应用限定为 CarPlay 签名。确认时重新检查，失败显示原因，拒绝遮挡触摸。组件 7 及更早版本的前三项仅接受两个固定签名 CarPlay 包，此限制从组件 8 源码起解除。组件本身仍要求匹配平台签名与系统 UID；其持有的系统权限不会转授给目标应用。不提供任意 app-op、批量授权、通知使用权或电池优化白名单。蓝牙代理仍只接受 `com.shihab.diplay` 和 `com.shihab.diplay.hudtest`，不提供断开、配对、HFP/SCO、切音源或全局蓝牙设置。

其他应用使用入口：修改系统设置 `android.settings.action.MANAGE_WRITE_SETTINGS`，悬浮窗 `android.settings.action.MANAGE_OVERLAY_PERMISSION`，安装来源 `android.settings.MANAGE_UNKNOWN_APP_SOURCES`，使用情况 `android.settings.USAGE_ACCESS_SETTINGS`，无障碍 `android.settings.ACCESSIBILITY_SETTINGS`；可由应用或系统设置入口打开。组件没有桌面图标；VPN 应由目标应用调用 `VpnService.prepare()` 并通过 `startActivityForResult` 发起，不能从其他应用传包名代办。CarPlay 主授权页继续只管理自身权限。

组件 8 定向验证：`:authorization-components:testDebugUnitTest` 仅选择 9 个相关测试类，**70 项通过，0 失败／错误／跳过**。覆盖其他应用的权限声明、普通签名、UID／签名／版本变化、VPN 调用者身份、悬浮窗单独声明、撤销、取消和系统策略；包括 API28、1920×720 横屏、字体 150% 的完整 160dpi、紧凑 480dpi 和 RTL 160dpi 测试渲染，已查看应用选择及确认／滚动操作图。渲染位于 `agent_docs/screenshots/authorization-other-apps-test-renders/`，仅为 Robolectric 测试图，不是安装 APK 或实车截图。结果副本见 `agent_docs/authorization-other-apps-97/`。没有运行完整 CI，没有构建、签名或安装 APK；组件 8 授权效果仍待设备验证。

组件以 `getOpsForPackage()` 读状态、`setMode()` 写对应 app-op；保留 `GET_APP_OPS_STATS`、`WRITE_SECURE_SETTINGS`、`MANAGE_USERS` 及各功能已有权限，不再声明旧查询路径使用的 `UPDATE_APP_OPS_STATS`。不使用 root、shell、ADB 授权或隐藏 API 解锁。

`RECEIVE_BOOT_COMPLETED` 是主应用已声明的普通权限；悬浮窗辅助和厂商后台启动策略不等于标准开机广播权限。

## 签名与构建

参考固件为 IHU519G / FS11A1 / Android 9 / 构建 327，原始证据见 [固件索引](PREFACE_FIRMWARE_REFERENCE.md)。平台证书 SHA-256：

```text
c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
```

签名输入固定来自 AOSP `platform_build` 提交 `57adea3c0d8722ec23a487122d85c853f7a93d44`（Android 9）。[来源说明](https://github.com/aosp-mirror/platform_build/blob/57adea3c0d8722ec23a487122d85c853f7a93d44/target/product/security/README)

| 输入 | SHA-256 |
| --- | --- |
| platform.pk8 | 1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875 |
| platform.x509.pem | 9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14 |

新环境显式准备一次，后续复用本地缓存：

```powershell
python scripts/android9_authorization.py --prepare-signing-inputs --fetch-public-aosp-key
./gradlew.bat :mobile:assembleDebug
```

主应用 Debug、Release 和 assembleStandaloneDebug 自动依赖组件 Release 构建、签名和生成 assets。必须包含 `assets/authorization/authorization.apk`、`manifest.json`，核对内置字节 SHA-256、包名、版本、平台证书及元数据，主应用仍用仓库原签名。失败不得交付缺少组件的 APK 或替换旧产物。

签名输入仅在 `agent_docs/android9-authorization/platform-test-key/`，不得入库或放入 APK。Gradle 保留原产物、输出元数据和 authorization-provenance.json。独立组件安装工具还检查实际设备 API、framework-res 平台证书、原厂／未知组件和 UID 迁移；不自动卸载、清数据、降级或修改权限。正常交付由文件管理器手动安装。

## 当前验证记录

2026-10-19 仅执行 `:mobile:assembleDebug`，没有追加单元测试、Lint 或完整 CI。主包已 adb install -r 安装到 API 34 模拟器 emulator-5558，版本查询为 0.1.1 / 95，保留数据；没有替换模拟器系统 VpnDialogs。

| 项目 | 核验值 |
| --- | --- |
| 主包 | CarPlay-Preface-0.1.1-95-debug.apk，25,564,785 字节 |
| 主包 SHA-256 | 5914be5caa434609514fbf5bf6a2198363a718e78493fce5e0992f407b5175cd |
| 主证书 SHA-256 | 75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8 |
| 内置组件 SHA-256 | 52b660b8a9e135c8345d0e14fdcb66dfec1dfd6dc14a0493936eeae769c267c8 |
| 组件身份 | com.android.vpndialogs / preface-android9-7 / 7，android.uid.system |

完整核验记录为 `agent_docs/build-95/verification.json`。源码阶段的组件定向测试和大字体／紧凑／RTL 渲染不等于车机运行验证。

用户在构建 92 交付后确认“授权功能已经正常了”，记为用户实车反馈；未逐项说明所有授权，不能外推为 USB/TUN、方控或蓝牙已修复。原生设置对照见 [授权参考](NATIVE_SETTINGS_AUTHORIZATION_REFERENCE_2026-10-19.md)，蓝牙和方控见 [后续修正](BLUETOOTH_WHEEL_RECOVERY_2026-10-19.md)。


## 2026-10-20 构建 97 交付核验

仅执行 `:mobile:assembleDebug --offline --console=plain`，自动依赖组件 Release 构建、独立签名和生成 assets；没有追加单元测试、Lint 或完整 CI。

| 项目 | 结果 |
| --- | --- |
| 主包 | `CarPlay-Preface-0.1.1-97-debug.apk，25,579,210 字节` |
| 包名 | `com.shihab.diplay.hudtest` |
| 主包 SHA-256 | `6539c43c328716550d9abc2a682856b40c0c0aff54cbfadabe878ca12f3475eb` |
| 主包证书 SHA-256 | `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8` |
| 内置组件 | `com.android.vpndialogs，preface-android9-8 / 8` |
| 内置组件 SHA-256 | `a31300f258cb1712053fb540d19e9d1cb59f8326465857f15b3e51fc0e5331ed` |
| 组件平台证书 SHA-256 | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |
| 共享 UID | `android.uid.system` |

主 APK 含 `assets/authorization/authorization.apk` 与 `manifest.json`；内置字节与本次生成组件完全一致，包名、版本、签名及 SHA-256 与元数据匹配。未将公开主签名材料或平台签名输入放入 APK assets。

构建后检测到 `emulator-5558`（API 34），使用命名后的 APK 执行 `adb install -r` 成功，保留数据；安装后包信息为 0.1.1 / 97。未安装内置 Android 9 组件，未授权或启动 VPN，无星瑞实车连接验证。产物及本地核验记录位于 `mobile/build/outputs/apk/debug/` 和 `agent_docs/build-97/`。

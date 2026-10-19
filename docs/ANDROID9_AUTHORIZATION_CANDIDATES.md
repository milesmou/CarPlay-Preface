# Android 9 授权补丁后续候选项

后续用户已选定安装来源、使用情况访问和无障碍管理，并取消通知使用权；选定的三项已包含在当前组件 7，见 [当前实现与构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)。本页保留初始静态候选及证据，不表示其他候选已经实现，也不替代逐项实车验证。

2026-10-19，用户要求候选能力不局限于 CarPlay 自身使用的权限，且不增加 CarPlay 主应用授权页项目。本轮仅核对接口、提出建议并记录证据，没有新增补丁运行权限／功能，没有构建、运行测试或安装 APK。

## 建议范围

| 优先级 | 候选功能 | 用途与边界 |
| --- | --- | --- |
| 高 | 应用运行时权限管理 | 为已安装应用管理定位、麦克风、相机、存储等已声明的危险权限；保留系统／策略固定标志，不把签名权限作为普通权限授予 |
| 高 | 允许此来源安装应用 | 为文件管理器／下载器等已声明 REQUEST_INSTALL_PACKAGES 的应用恢复确认页；仅授权该安装来源，不赋予静默安装能力；CarPlay 继续只导出组件供用户手动安装 |
| 高 | 使用情况访问 | 为声明 PACKAGE_USAGE_STATS 的第三方桌面、应用统计或自动化工具恢复授权入口 |
| 中 | 无障碍服务管理 | 为实际声明无障碍服务的按键工具或自动化应用恢复启用／关闭入口；保留其他服务列表，不声称可解决原车媒体方控跳页 |
| 中 | 通知使用权 | 为声明通知监听服务的桌面小部件、通知同步或媒体工具恢复启用／关闭入口；这是读取通知的权限，不等于允许发送通知 |
| 低 | 勿扰模式访问 | 为需要管理勿扰策略的声音／自动化工具恢复访问确认；授权后由目标应用决定是否改策略，补丁不自动改变声音模式 |

上述评估后，用户 2026-10-19 明确要求组件现有授权均可用于其他应用。组件 8 源码已将 VPN、修改系统设置和悬浮窗扩展到符合条件的其他应用：后两项增加标准 Settings Intent 的应用选择入口，VPN 只接受实际调用者；均复核真实 UID、签名、版本、声明、当前用户限制并保留确认和结果读回。尚未构建或实车验证，详见[当前实现](ANDROID9_AUTHORIZATION_COMPONENTS.md)。运行时权限管理、勿扰及日志等候选没有因此新增。

推荐将候选项仅放在补丁自己的“星瑞系统授权”入口内，或按标准系统 Intent 在其他应用请求时显示对应确认窗口；CarPlay 授权页保持现有项目。隐藏主应用菜单不等于隐藏授权确认。明确不恢复用户已移除的电池优化功能；厂商自启动白名单仍无已复核接口，不添加猜测开关。

## 固件证据

参考 OTA：IHU519G / FS11A1 / Android 9 API 28 / 构建 327；SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。用户报告设备 IHU353G 单独验证。本轮仅确认框架声明／签名和 AOSP 对应语义，不确认实车服务可访问或实际效果。

原始框架 `system/framework/boot-framework.vdex` SHA-256 `3b40cf31d181d325410c8efb1c1983c038c7c2a09ea7222572133b2294a9b08b`：

| 类／方法 | 类定义偏移／代码偏移 |
| --- | --- |
| ApplicationPackageManager.grantRuntimePermission(String,String,UserHandle) | 1602488／3764170 |
| ApplicationPackageManager.revokeRuntimePermission(String,String,UserHandle) | 1602488／5020746 |
| ApplicationPackageManager.getPermissionFlags(String,String,UserHandle) | 1602488／3762158 |
| AppOpsManager.setMode(String,int,String,int) | 1513080／5010794 |
| NotificationManager.setNotificationListenerAccessGranted(ComponentName,boolean) | 1516184／5150462 |
| NotificationManager.setNotificationPolicyAccessGranted(String,boolean) | 1516184／3911438 |
| AccessibilityManager.getInstalledAccessibilityServiceList() | 3179044／12351928 |
| Settings.Secure.getStringForUser / putStringForUser / putIntForUser | 3271940／11766758、11766200、11766134 |

`framework-res.apk` 的已缓存 manifest 声明 GRANT_RUNTIME_PERMISSIONS、REVOKE_RUNTIME_PERMISSIONS、WRITE_SECURE_SETTINGS、MANAGE_NOTIFICATIONS、MANAGE_APP_OPS_MODES；这是补丁实现管理功能所需的候选平台权限，不是应直接赋予其他普通应用的权限。目前补丁并未新增这些声明。

另在 VDEX 中核对 OPSTR_REQUEST_INSTALL_PACKAGES／android:request_install_packages、OPSTR_GET_USAGE_STATS／android:get_usage_stats、enabled_accessibility_services 与 accessibility_enabled。偏移保存在 `catalog/authorization-candidate-strings.json`；类方法保存在 `catalog/authorization-candidates-classes.json`，再生成命令 `python agent_docs/firmware/inspect_authorization_candidates.py`。完整固件、反编译实现与索引继续留本地忽略目录。

参考 OTA 保留 `priv-app/PackageInstaller/PackageInstaller.apk`、`priv-app/DocumentsUI/DocumentsUI.apk` 和厂商 XCSettings；用户所说原生设置菜单缺失，不代表权限后端或所有安装／文件管理能力都消失。应先检查现有处理器是否可用，再补缺失页面，不覆盖原系统 PackageInstaller 或权限控制组件。

## AOSP Android 9 语义参考

- [PackageManager：运行时权限授予、撤销和固定标志](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/core/java/android/content/pm/PackageManager.java#L3220)。
- [PackageInstallerActivity：未知来源检查](https://github.com/aosp-mirror/platform_packages_apps_packageinstaller/blob/android-9.0.0_r1/src/com/android/packageinstaller/PackageInstallerActivity.java)。
- [UsageAccessDetails：使用情况访问](https://github.com/aosp-mirror/platform_packages_apps_settings/blob/android-9.0.0_r1/src/com/android/settings/applications/UsageAccessDetails.java#L99)。
- [AccessibilityUtils：无障碍服务列表读写](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/packages/SettingsLib/src/com/android/settingslib/accessibility/AccessibilityUtils.java#L98)。
- [NotificationAccessSettings：通知监听确认和启停](https://github.com/aosp-mirror/platform_packages_apps_settings/blob/android-9.0.0_r1/src/com/android/settings/notification/NotificationAccessSettings.java#L66)。

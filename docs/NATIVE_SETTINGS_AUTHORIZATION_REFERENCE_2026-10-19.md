# 用户提供的原生设置 APK 授权对照

后续实现：用户已明确选择系统共享 UID＋原生查询。待打包组件 7 已改用 `android.uid.system` 和 `getOpsForPackage()`，旧独立 UID 补丁须手动卸载后重装，详见[当前实现与迁移](ANDROID9_AUTHORIZATION_COMPONENTS.md)。下文保留本 APK 初次分析时与旧独立 UID 源码的对照，不表示当前源码仍使用 checkOpNoThrow。

2026-10-19，用户提供 `原生设置.apk`，并确认其可以成功授权。结合此前“修改系统设置”失败的上下文，本次重点核对该授权链路；用户反馈与静态证据分别记录，不将其扩大为所有授权项目或 USB/VPN/TUN 均通过。

## APK 身份与证据

| 项目 | 核对结果 |
| --- | --- |
| 来源 | 用户提供的 `D:/Downloads/原生设置.apk`，不推定下载来源或官方发布身份 |
| 大小 | 32,188,221 字节 |
| SHA-256 | `54c5fed1fb47850e5043ca545743a7235178a7cccbc5d815c5c391922184e045` |
| 包名／版本 | `com.android.settings`，versionName `9`，versionCode `28` |
| minSdk／targetSdk | `28`／`28` |
| 签名证书 SHA-256 | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |
| 共享 UID 声明 | `android.uid.system`；未读取设备上实际安装 UID |

证书与本仓库补丁及参考 IHU519G / FS11A1 / Android 9 / 构建 327 的平台证书一致。参考 OTA SHA-256 为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。这仍是两个来源的静态对照，不能单独代替 IHU353G 上当前组件的实际身份和获授权限查询。

原 APK、副本、DEX、反编译实现和完整 manifest 仅留 Git 忽略目录 `agent_docs/apk-reference/native-settings-20261019/`，不导入应用、不随 APK 打包。证据包括 `manifest.txt`、`badging.txt`、`certificates.txt`、`catalog.json`，可用 `python agent_docs/apk-reference/native-settings-20261019/catalog_reference.py` 重建索引。复用本地 JADX 1.5.6，运行退出码为 0；本次关键授权方法完整恢复，不将其他 Settings 方法一概声明为完美还原。

## 修改系统设置与补丁的差异

manifest 第 3468–3481 行将 `android.settings.action.MANAGE_WRITE_SETTINGS`／`package:` 路由到 `Settings$AppWriteSettingsActivity`，再加载 `com.android.settings.applications.appinfo.WriteSettingsDetails`。

| 环节 | 此原生设置 APK | 初次分析时的补丁源码（独立 UID） |
| --- | --- | --- |
| 调用身份 | 声明 `android.uid.system`，平台签名 | `com.android.vpndialogs`，独立应用 UID，平台签名 |
| 读取目标状态 | `getOpsForPackage(uid, package, {23})` | `checkOpNoThrow(OPSTR_WRITE_SETTINGS, uid, package)` |
| 用户允许后的写入 | `setMode(23, uid, package, 0)` | 相同的 op、目标 UID／包名和允许模式 |
| 用户关闭后的写入 | `setMode(23, uid, package, 2)` | 相同的拒绝模式 |
| 默认模式判断 | `MODE_DEFAULT=3` 时读取目标静态权限授予标志 | `MODE_DEFAULT` 时核对目标 `WRITE_SETTINGS` 实际权限 |
| 目标范围 | 系统设置中符合条件的应用 | 两个固定 CarPlay 包，继续核对签名、用户、UID、声明和系统状态 |

关键本地证据（下列路径均相对于 `decompiled/sources/com/android/settings/`）：

- `applications/appinfo/WriteSettingsDetails.java:46–58`：点击变更后调用 `setCanWriteSettings()`，写入 op 23；第 68–71 行重新查询并刷新开关。
- `applications/AppStateAppOpsBridge.java:67–93`：读取目标包的权限声明及授予标志，第 86 行调用 `getOpsForPackage()`。第 194、206–210 行处理默认模式和静态权限。
- `applications/AppStateWriteSettingsBridge.java:21–30`：固定 op 23 和 `WRITE_SETTINGS`。
- `manifest.txt:4`：系统共享 UID 声明；第 181 行声明 `MANAGE_APP_OPS_MODES`。此 APK 的 88 项权限声明未单独包含 `GET_APP_OPS_STATS` 或 `UPDATE_APP_OPS_STATS`。共享 UID 声明及其运行环境与补丁不同，不能从该清单推出独立 UID 补丁也不需要相应权限。

因此，原生成功支持车机存在可用的修改系统设置授权通道；没有证据表明补丁的 op 23 或允许／拒绝模式写错。此前已确认的补丁缺陷位于另一条状态查询链路：独立 UID 的 `checkOpNoThrow()` 经参考 Android 9 AppOpsService.checkOperation → verifyIncomingUid 时，额外要求 UPDATE_APP_OPS_STATS。早期组件 7 源码曾补充该权限，后按用户选择改用系统共享 UID 与原生查询，并移除不再使用的该权限；详见 [当前实现与历史修正记录](ANDROID9_AUTHORIZATION_COMPONENTS.md)。原生成功不能替代组件 7 的实车验证，也不足以判定旧组件在该设备上的唯一失败点。

初次分析建议按原生的 `getOpsForPackage()` 方式调整时，同时核对默认模式、UID／包名身份及系统限制的语义，再做定向回归。该分析阶段只读取 APK；后续源码实现与定向验证以文首链接的记录为准。

## 悬浮窗与 VPN 的范围

`applications/appinfo/DrawOverlayDetails.java:80–82` 写入 `setMode(24, uid, package, 0/2)`，状态仍经 AppStateAppOpsBridge 查询；第 92–97 行刷新状态并按权限声明及可控制性启用开关。补丁使用同一 SYSTEM_ALERT_WINDOW app-op 语义。

该 APK 中 `vpn2` 是 VPN 管理界面：`VpnSettings.java:418–449` 根据 app-op 47 列出已授权应用；`AppManagementFragment.java:164–165` 管理始终开启 VPN，第 231–235 行读取授权记录；`AppDialogFragment.java:96–123` 的忘记操作撤销授权并断开。未在该 APK 的 manifest 或 Settings 源码中发现 `com.android.vpndialogs.ConfirmDialog`、`VpnService.prepare()` 或将 `setVpnPackageAuthorization(..., true)` 作为第三方应用首次授权确认的实现。

因此，此次修改系统设置的对照不能单独证明 VPN 确认组件、TUN 或 USB 通信可用。

## 初次分析的操作范围

仅完成 APK 哈希、签名验证、manifest 导出、DEX 静态反编译、关键方法对比及文档／事实索引更新。未安装或执行 APK，未修改应用授权代码，未运行 Gradle、构建、签名或安装流程。没有取得新的设备 UID／权限状态、补丁异常或实车运行截图。

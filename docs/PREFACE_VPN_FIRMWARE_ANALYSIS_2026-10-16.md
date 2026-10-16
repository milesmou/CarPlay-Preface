# VPN 授权组件与固件修复分析

日期：2026-10-16。只读分析；未构建、安装 VPN 补丁，未修改车机设置或应用连接流程。

后续状态：用户明确要求补装后，已另行构建并签名 [Android 9 授权补充组件](ANDROID9_AUTHORIZATION_COMPONENTS.md)，包含 VPN 和修改系统设置两项入口；在线 Android 14 模拟器不匹配而跳过安装，IHU353G 实车仍待核对与安装。以下“未构建”描述为原分析时点，不代表后续交付状态。

结论：报告首先卡在 **VPN 授权 Activity 无法启动**，尚无 TUN 创建失败证据。IHU519G 构建 327 参考 OTA 保留 VPN 服务、JNI 和内置 TUN 驱动，并使用 AOSP 公开平台测试证书，存在补回匹配签名 Android 9 `VpnDialogs` 的候选修复路径。报告设备是 **IHU353G**，必须另行核对证书、组件状态和系统策略；参考固件的结果不代表该实车已经可修复或 USB 已可用。

## 报告与参考固件证据

报告 `diplay-report 6(1).txt` 的 SHA-256 为 `5f26a07272b370e78a4afbaf2f6cf236df5bdd39433eccd9cd7abade41f71647`。原始报告只留本机，不入库。

- 第 90、1837、1844、1847、1849 行，分别在 17:02:00.052、17:11:24.462、17:12:01.533、17:12:05.057、17:12:06.724 记录 `ActivityNotFoundException`，目标组件均为 `com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog`。
- 这证明当时该用户下无法启动目标 Activity；仅凭异常不能区分 APK 缺失、用户卸载、停用或组件被移除，也不能据此认定存在 `DISALLOW_CONFIG_VPN` 策略。
- 安装原生 Settings 不等于安装 `VpnDialogs`；Android 9 的确认窗口属于单独系统包。[AOSP VpnDialogs manifest](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/packages/VpnDialogs/AndroidManifest.xml)

参考资料为 `23星瑞1.5T固件.zip`，IHU519G / FS11A1 / Android 9 / 构建 327；OTA SHA-256 为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。缓存相对路径均以 `agent_docs/firmware/ihu519g-327/` 为根。

| 层级 | 静态结果 | 本地证据 |
| --- | --- | --- |
| 授权窗口配置 | `config_customVpnConfirmDialogComponent` 仍指向报告中的 ConfirmDialog | `system/framework/framework-res.apk`，aapt2 resources |
| 授权 APK | 完整 system/vendor 分区文件清单未列出 VpnDialogs 命名的 APK；已提取的应用 manifest 中也未发现该包 | `system-files.txt`、`vendor-files.txt`、`catalog/apk-manifests.json`；未逐个核对全部 131 个 APK 的包名，不排除改名 APK 或 data 分区另装组件 |
| 权限声明 | `CONTROL_VPN`、`CONNECTIVITY_INTERNAL` 都为 `0x12`，即 signature\|privileged | `framework-res.apk` manifest 原始 XML 行 3267 / 1598 |
| Java 服务 | 存在 `ConnectivityService`、`connectivity.Vpn` 类定义和 prepare/establish/授权方法；不是只搜索到类名引用 | `system/framework/oat/arm64/services.vdex`；`catalog/vpn-classes.json` |
| 原生实现 | 包含 `/dev/tun` 与 `Cannot allocate TUN` 字符串 | `system/lib64/libandroid_servers.so`，偏移 `0x642c2` / `0x642d2`；字符串不单独证明设备运行可用 |
| 内核 | 从 OTA boot.img 的 LZ4 legacy 内核提取 IKCONFIG，确认 `CONFIG_TUN=y`、`CONFIG_IPV6=y` | `kernel.config:1868` / `:617`；`catalog/boot-vpn-scan.json` |
| 平台签名 | framework-res 的 APK 签名校验成功，证书 SHA-256 与 AOSP Android 9 platform 证书相同 | apksigner verify --print-certs；下列证书指纹 |

关键文件 SHA-256：

```text
framework-res.apk  166741d4e79dd8084f5850941c09f28f93d2ba5c9567f28ebb938d338d5787db
services.vdex     5d19a5aa3f1c4f8b7f90ea18ee44b52e1e3b32ef748d6d9af5c86a1546506571
libandroid_servers.so d809831aa34800a0d61397d262f22d779ed8a4cabb268dedf38e6cf146f1a0e1
boot.img          fcb910995cd2898b4023567ba51a0ac05be185ad6c6dd33f0718840fa7fb6622
kernel.config     2770ff03962342dd80e0a2a963c4d13b08745ee2146b313f58310e1faac5e39a
平台证书 DER SHA-256 c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
```

内置驱动结论仅覆盖该参考 boot.img；尚未验证实车 `/dev/tun` 节点、SELinux、运行中 ConnectivityService 或实际 TUN 建立。

## 可行修复顺序

1. **组件已存在但对当前用户不可用**：先确认是否停用、被用户卸载或缺少 ConfirmDialog。如果原包完整且仅状态异常，优先恢复原组件；恢复操作另行执行，本次只分析。
2. **组件确实缺失，实车平台证书匹配**：可评估从 AOSP Android 9 源码恢复独立 `com.android.vpndialogs` 包，保留系统确认及显式用户同意，使用与实车平台匹配的证书。参考构建 327 使用公开 AOSP platform 测试证书，因此有通过正常 APK 安装获得所需签名权限、无需 root 或写 system 的可能。仍需验证厂商安装策略、隐藏接口及实授权限；未生成或交付补丁，不保证 IHU353G 适用。
3. **证书不匹配或安装策略阻止**：普通自签 APK 即使补出页面，也无法因此获得 `CONTROL_VPN`。需要对应固件的原厂签名组件或厂商系统修复；将未知签名 APK 当普通应用安装不能视为解决办法。
4. **窗口恢复后仍失败**：分开检查用户 VPN 配置限制、其他 always-on VPN 占用，以及 `VpnService.Builder.establish()` 的异常、TUN 节点／SELinux和桥接日志。授权窗口恢复不等于端到端 USB CarPlay 修复。

该推断依据：AOSP VpnDialogs 默认使用 platform 签名并作为 privileged 模块；两个所需权限为 signature|privileged。Android 9 权限管理支持匹配系统签名的 APK 获得签名权限，因此并非所有场景都必须写入 system 分区。[构建规则](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/packages/VpnDialogs/Android.mk)、[权限授予逻辑](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r1/services/core/java/com/android/server/pm/permission/PermissionManagerService.java#L1014)、[AOSP 平台证书](https://github.com/aosp-mirror/platform_build/blob/android-9.0.0_r1/target/product/security/platform.x509.pem)。这里是基于静态证据和 AOSP 行为的候选方案，不是厂商固件实测结果。

## IHU353G 所需的最小只读核对

以下命令应显式选择实际车机的 ADB 序列号；不能将本机模拟器结果当实车证据。当前用户编号由第二条命令确认，包信息按该用户核对。

```powershell
adb -s <车机序列号> shell getprop ro.build.fingerprint
adb -s <车机序列号> shell am get-current-user
adb -s <车机序列号> shell pm list packages -u com.android.vpndialogs
adb -s <车机序列号> shell dumpsys package com.android.vpndialogs
adb -s <车机序列号> shell dumpsys user
adb -s <车机序列号> shell ls -l /dev/tun
adb -s <车机序列号> pull /system/framework/framework-res.apk <本机保存路径>
apksigner verify --print-certs <本机保存路径>
```

`dumpsys user` 可能包含用户信息，只在本地核对 `no_config_vpn` 等限制。上述检查不触发 VPN 授权、不更改 app-op、不启停组件，也不安装补丁。

本地复核内核配置：`python agent_docs/firmware/inspect_vpn_boot.py`（脚本及原始固件仅保留忽略目录）；资源与权限通过已配置 aapt2 复核，平台证书通过 apksigner 复核。完整固件、厂商二进制和原始报告不入库。

当前 USB 流程仍遵守 [官方 v0.2.20 NCM＋本地 VPN 恢复方案](USB_VPN_RESTORE_0.2.20.md)。本次不切回历史系统网卡方案，也不改动无线流程。

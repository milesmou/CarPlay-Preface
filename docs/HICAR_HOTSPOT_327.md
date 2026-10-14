# 参考固件 HiCar 热点创建方式

分析日期：2026-10-14。范围仅为用户提供的 IHU519G / FS11A1 / Android 9 / incremental 327 固件的静态实现；不代表报告中的 IHU353G 车机或所有星瑞 E02 固件已验证。

固件来源 SHA-256：`f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`。本地证据根目录为被 Git 忽略的 `agent_docs/firmware/ihu519g-327/`，事实索引见 [ihu519g-327.json](firmware/ihu519g-327.json) 的 `hicarWirelessHotspot`。

## 实际实现

HiCar HMI 通过 Nearby SDK 绑定 `com.huawei.nearby/com.huawei.nearby.NearbyService`。热点实际创建代码在固件 `system/app/HwNearbyCar/HwNearbyCar.apk` 内，不在 HiCarHMI 的界面层。

该 Nearby 服务存在自行配置普通 SoftAP 的路径；在参考固件的 Android 9 上调用 `ConnectivityManager.startTethering(0, false, callback)`，不是公共 `WifiManager.startLocalOnlyHotspot()`。不能把“连接期间使用、退出后恢复”的热点与 Android 的 LocalOnlyHotspot API 混为一谈。

| 步骤 | 静态行为 | 本地源码证据 |
| --- | --- | --- |
| 模式选择 | NearbyController 的模式默认值为 1；资源管理器模式 1 开启 SoftAP。状态机另有模式 2 的 P2P 分支，不能将类名中的 P2P 当作当前 SoftAP 路径使用 Wi-Fi Direct 的证明 | `decompiled/nearby/sources/com/huawei/nearby/controller/NearbyController.java:42,503,616`；`transfertype/resource/j.java:110`；`channel/p2p/P2pChannelStateMachine.java:640` |
| 名称与密码 | SecureRandom 生成 `HiCar-` 加 4 位十进制数字的名称，密码为 8 位十进制数字；由 Nearby 自己生成 | `decompiled/nearby/sources/com/huawei/nearby/d/a.java:219,233` |
| 保留用户配置 | 原 SSID 不以 `HiCar-` 开头时，保存原 WifiConfiguration 和热点是否已开启 | 同文件 `:500` |
| 切换网络 | 先关闭 Wi-Fi 客户端，再关闭已有 SoftAP；关闭逻辑有状态轮询和约秒级等待 | 同文件 `:131,247,603` |
| 安全、频段、信道 | 创建 WifiConfiguration，设置 WPA2、apBand/apChannel；还会写 Secure Settings 的 `wifi_ap_channel` 和 `wifi_ap_maxscb`，启动前将最大连接数改为 1 | 同文件 `:54,63` |
| 开启热点 | 设置 `setWifiApConfiguration()`，Android API 大于 24 时调用 `startTethering()`，并调用 `saveConfiguration()`；API 不大于 24 的兼容分支使用 `setWifiApEnabled()`，不是该参考 Android 9 的运行分支 | 同文件 `:464,497,500` |
| 读取参数 | 使用 `getWifiApConfiguration()` 读取当前 SSID、密码、信道 | 同文件 `:622,631,640` |
| 复用热点 | 状态机发现已开启的 `HiCar-` 热点且信道匹配时，可复用而不重新生成；另有地址资源耗尽、信道变化时的重建逻辑 | `decompiled/nearby/sources/com/huawei/nearby/channel/p2p/l.java:74` |
| 恢复配置 | `restorePreApConfig` 写回原配置；原配置 status=0 且之前已开启时，再尝试恢复开启状态。unPublish 路径在相应资源条件下调用恢复；不能描述为所有退出路径必定恢复成功 | `decompiled/nearby/sources/com/huawei/nearby/d/a.java:572`；`decompiled/nearby/sources/com/huawei/nearby/f.java:54` |

以上行号按本次 JADX 1.5.6 缓存输出；表内缩写路径以 `decompiled/nearby/sources/com/huawei/nearby/` 为前缀。

## 权限与可复用范围

`HwNearbyCar` manifest 声明 `android.uid.system`，版本为 8.0.0.300。固件 `hicar-privapp-permissions-platform.xml` 列出 `OVERRIDE_WIFI_CONFIG`、`CONNECTIVITY_INTERNAL`、`TETHER_PRIVILEGED`、`WRITE_SECURE_SETTINGS` 等权限；这是固件声明，实际授予状态仍需设备核对。NearbyService 本身要求 `com.huawei.permission.NEARBY_OPERATOR_FUNCTION`，声明保护级别为 0x3；调用和 Provider 接入还存在 UID／应用身份检查、Provider 权限与签名检查。

因此不能据此认定普通第三方 APK 能调用这条创建路径或复用 Nearby 服务。它写入系统保存的热点配置、关闭已有热点及 Wi-Fi 客户端，行为也超出本仓库当前仅使用公共 LocalOnlyHotspot、系统分配配置且不关闭其他应用热点的范围。本次只参考并记录实现，没有将该 SoftAP／P2P 路径引入应用，没有构建或安装 APK。

HiCar 的随机热点名并不意味着系统设置始终显示随机值：其路径会写入随机配置，也有恢复原配置的逻辑。至于用户这台 IHU353G 的系统设置页显示什么，尚未核对其 HMI／Wi-Fi 服务实现；当前观察不能证明 DiPlay 的临时热点实际使用了旧名称和密码。

## 证据与验证限制

- `HwNearbyCar.apk` SHA-256：`c3ce640924c7e39b6be75d9cadfa53f6e876de8f44c3efd6ae55013fd0579406`。
- 对照检查的 `HwDMSDPDevice.apk` SHA-256：`b62c9a7f63fc69981acc817935bd01f9f88bf26aa7c9e9e3ba8d9bbb4470d182`。
- `decompiled/nearby/sources/com/huawei/nearby/d/a.java` SHA-256：`abc723f6ce6aea52edf6217c1b5f0782cb847bd3a5431839db8b58a5c5489b5a`。
- `decompiled/nearby/sources/com/huawei/nearby/channel/p2p/l.java` SHA-256：`be1c55eef945ddd642cd86b7924a77b438b4ac24d7c17b2026debf40396d63e1`。
- `system/etc/permissions/hicar-privapp-permissions-platform.xml` SHA-256：`29685b61cf60882dfe122b2f98da204e258a322dd7fa02ae366d79a11aea847a`。

JADX 对 Nearby APK 报 1 处错误（Google reflect 的类型解析方法），对 DMSDP APK 报 8 处错误。Wi-Fi 工具类另有网卡读取方法的类型推断警告；本页引用的名称／密码生成、配置写入、startTethering 和恢复配置方法没有上述反编译失败标记。状态机部分退出方法有重复块警告，因此本页仅确认完整可读的恢复实现与 unPublish 调用，不保证所有退出分支。只检查静态文件、源码引用、manifest 和校验值，没有执行厂商服务、刷写固件或进行实车测试。

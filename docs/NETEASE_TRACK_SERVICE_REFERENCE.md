# 网易云切歌服务 APK：OpenAPI 服务端参考

分析日期：2026-10-15。输入用户文件 `切歌服务(不可禁用多媒体) (1).apk`，5817627 字节，SHA-256：`8e0e8b76f53f10a9c0d49d5efbd99b24f946223ebecfc9d9ecf42b3460f6aa23`。

用户确认：已安装在当前星瑞车机，网易云切歌正常。该实车观察单独记录；未读取车机实际安装 APK 的校验值，也未取得该服务的实时日志，不能据此认定本文件与当前进程字节完全一致，或所有客户端都已经获准接入。

2026-10-20 再次收到 `网易云切歌服务(不可禁用多媒体) (1).apk`，大小及 SHA-256 与本页输入完全相同，复用缓存。与新提供 QQ 助手的角色区别、独立 CarPlay 助手的候选方案见 [辅助工具对照](MUSIC_HELPER_REFERENCE_2026-10-20.md)。

## 实际角色及参考价值

Manifest 和资源确认：应用名 `XCOpenAPIService`，包名 `com.ecarx.sdk.openapi`，版本 `4.1.6 (26A7051)` / 40530001，声明 `android.uid.system`；`ServicePoolService` 导出服务 action `ecarx.intent.action.OpenAPIService`。它位于原厂 SDK 服务端，和 Linkco 的 MusicClient 客户端是不同层次，不能把它当成普通 APK 的切歌监听样例。

参考 IHU519G/FS11A1 构建 327 的原包 `system/priv-app/XSFOpenAPIService/XSFOpenAPIService.apk` 也具有同包名、版本和 system shared UID，原包 SHA-256 为 `3bada64ef495484a9b9a7dd9653aca9a07794c8d6daaa0f74092c02f49b87c53`。两者不同，尚未建立完整修改差异；不能仅凭文件名把具体改动、网易云授权方式或全部校验的移除视为已确认。

两份 DEX 的精确字符串检查未见 `com.netease.cloudmusic`、`MusicClient` 类型、`registerMusic`、`onAPIReady`、`android.intent.action.MEDIA_BUTTON` 或 `dispatchMediaKeyEvent`。源码搜索也未找到直接向网易云发媒体键的路径。负向搜索不能排除所有动态行为，但目前没有证据支持照搬一套“网易云专用切歌算法”。可参考的是 OpenAPI 服务池、目标媒体服务获取及按调用者检查的流程。

## 可复核的实现证据

原 APK、反编译和索引仅留在 Git 忽略目录 `agent_docs/apk-reference/netease-track-service/`。下列路径相对于 `decompiled/`，每个文件 SHA-256 记录于同目录 `catalog.json`：

| 证据文件／行 | 观察 |
| --- | --- |
| `resources/AndroidManifest.xml:3,5,8,53–56` | 系统 UID 声明、版本、包名、导出的 OpenAPI 服务入口 |
| `sources/com/ecarx/sdk/openapi/support/ConfigService.java:15` | V3 媒体服务：`ecarx.xsf.mediacenter`，action `ecarx.xsf.MEDIA_CENTER_SERVICE` |
| 同文件 `:140` | V4 媒体服务：同包名，action `ecarx.xsf.mediacenter.intent.action.EAS_SUPPORT_SERVICE` |
| `sources/com/ecarx/sdk/openapi/ak.java:53–82` | 取得目标 Binder 前调用准入检查；失败抛出 `No Authorization to operate [...]`，准入通过但 Binder 为空则记录 `Service[...] is not ready yet!` |
| `sources/com/ecarx/sdk/openapi/support/b.java:1729–1760` | 记录当前服务列表；已缓存的 Binder 直接返回，否则根据连接状态返回空并安排获取服务 |
| `sources/com/ecarx/sdk/openapi/f/f.java:124–163,225–233` | 区分实际调用 PID／UID／包名，系统应用标志以及相应服务的 license operation；不同客户端可能结果不同 |
| `sources/com/ecarx/sdk/openapi/f/e.java:133,185` | 有按客户端包名查询 license provider、应用签名匹配失败的诊断入口；不代表本应用当前已触发该分支 |

JADX 1.5.6 返回 1，报告 6 个反编译错误。核心 `support/c.java` 的一个连接处理方法未完整恢复，其他方法也有重复块／覆盖率插桩噪声；以上仅记录已读到的局部流程，不声称恢复了完整连接／断开时序。也没有据此确认当前 IHU353G 使用的 SDK 客户端会把每种异常转换成哪次 `onAPIReady(false)`。

## 对当前 CarPlay 故障的意义

[第五份报告](CONNECTION_REPORT_2026-10-15_5.md) 中 CarPlay 客户端找到 SDK 351 的具体类路径，但五次在注册前返回 `ready=false`。本 APK 加上用户确认使调查范围更明确：应检查 CarPlay 这个调用者取得 `mediacenter` 服务时的结果，而不是一开始就认定整套原厂服务不存在或所有媒体应用都失败。

下一次取证的顺序：

1. 确认当前 OpenAPI 包和媒体中心包是否启用、实际版本及服务连接情况，记录供核对的包状态，不修改／禁用系统组件。
2. 在本应用初始化时对齐服务端日志，重点匹配本应用实际包名 `com.shihab.diplay.hudtest` 和服务名 `mediacenter`。区分未连接 OpenAPI、`No Authorization`、服务列表缺少 `mediacenter`、Binder 暂未就绪、连接后断开等阶段。若普通应用读不到跨进程系统日志，应明确不可读取，不能将本应用日志缺少错误当成厂商没有拒绝。
3. 若为调用者准入问题，再核对本应用获支持的接入条件；若为服务绑定问题，再核对实际服务状态和 SDK 初始化调用。只有获得 `ready=true` 后，才验证注册 token、`requestPlay` 及原厂音源切换。

文件名“不可禁用多媒体”与代码依赖原厂媒体中心服务相符，但本 APK 未展示全部媒体按键路径，不能把文件名当作已验证的完整技术原理。网易云切歌正常并不证明 CarPlay 已具备相同准入、音源身份或播放通路。

本轮没有安装／替换该系统包，没有复制其 system UID、包名、厂商实现或授权资料到主应用，也没有修改 CarPlay 运行代码或构建 APK。分析资料用于定位接口阶段，普通 CarPlay 应用继续使用自己的真实身份接入。

# QQ 助手媒体授权接入：保留原签名

2026-10-20，按用户要求“使用 qq 助手媒体授权文件，签名还是用之前的”完成源码接入，随后按“构建测试 apk”要求完成主应用 **0.1.1 / 98** 构建、签名核验及模拟器覆盖安装。没有新增独立助手，也没有实车验证。

## 实现

- 主应用保留原 applicationId 和 signing/ 中的签名配置／签名文件，未使用 QQ 助手的平台签名。现有授权组件仍独立签名，职责不扩展。
- 原样导入 QQ APK 的 assets/license.txt，并提取配套 `eCarX_OpenAPI_AppId`、`eCarX_OpenAPI_AppKey`；仅放入本机 Git 忽略目录，不把原文或参数提交仓库。
- mobile／automotive 的 manifest 通过构建占位符注入配套参数，assets 输入根目录提供 license.txt。所有应用变体沿用同一分发脚本；原 MFi 资料、原主应用签名和内置授权组件的流程保持原实现。
- 构建前核对授权、配套参数及来源记录的固定哈希。缺失、内容变化或本地 assets 夹带其他文件时失败，不交付静默省略授权的 APK。
- 保留原厂 SDK 的真实 ready、注册 token 和 requestPlay 返回检查，没有强制成功、修改服务端、改包名或修改授权文件。

## 固定输入与来源

| 项目 | 核验值 |
| --- | --- |
| 用户文件 | QQ音乐助手带小卡片 (1).apk，1,171,300 字节 |
| APK SHA-256 | e67a0e548fc9ca0e54f0a75cd5b1bcbaaa207f4f935e25b9898805e69e38ce9c |
| 授权文件 | assets/license.txt，1,152 字节 |
| 授权 SHA-256 | 4290f880caa28d5535897c9a365c13a7394b93ae06ca9bec919918b90b0dbd98 |
| 两项配套参数规范化 JSON SHA-256 | 175ef703f749677edc67d130a45bc234493afaecc11eb566cd3a3a17d33dbf1f |
| 保留的主应用证书 SHA-256 | 75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8 |

仅依照用户要求复用固定输入；文件存在和内容正确不代表授权适用于本应用。

## 本机准备与后续构建

当前工作机已导入到 `agent_docs/runtime-ecarx-media/`：assets/license.txt、manifest-values.json、provenance.json。只有 assets 子目录参与打包，参数通过 manifest 占位符注入，来源记录不进入 APK。该目录和构建缓存均被 Git 忽略，公开凭据白名单未扩展。

新环境需取得用户提供的同一个 APK，使用已有 SDK 的 aapt2 准备本地输入，不自动下载替代 APK：

```powershell
python scripts/ecarx_media_authorization.py --import-source --apk '<用户提供的 QQ 助手.apk>' --aapt2 '<已有 Android SDK 的 aapt2.exe>'
python scripts/ecarx_media_authorization.py --verify
```

需要更换本机输入目录时，可在导入阶段用 `--directory`，并为后续 Gradle 进程设置 `DIPLAY_ECARX_MEDIA_AUTH_DIR` 指向同目录。CI 或其他机器未准备此本地输入时，应用构建将明确失败；不把第三方参数或授权原文提交为解决办法。用户未要求打包时，只准备和验证输入，不运行 assemble、签名或安装任务。

## 验证结果与限制

6 项定向 Python 测试通过，覆盖精确字节导入、配套参数完整性、错误 APK、输入／来源记录被修改、额外资产及重复参数。测试使用合成数据，不包含真实接入值。

先检查了 Gradle dry-run，确认只有 manifest／输入校验任务；随后 `:mobile:processDebugMainManifest :automotive:processDebugMainManifest` 成功。解析合并清单确认两项参数与本机输入一致；mobile Debug 包名仍为 com.shihab.diplay.hudtest，automotive Debug 包名仍为 com.shilapi.xcertplay。signing/carplay-release.p12 和 signing/signing.json 哈希仍与原值相同，没有改动签名材料。

以上是源码接入阶段检查。随后用户明确要求打包，`:mobile:assembleDebug` 成功，实际 APK 的 assets/license.txt 哈希及最终 manifest 两项配套参数与固定输入一致，主包仍使用上述原证书；内置组件 9 的 SHA-256、版本、包名、共享 UID 与平台证书均已核对。使用命名后的 APK 对在线设备 emulator-5558 执行 adb install -r 成功，安装后版本确认 0.1.1 / 98，保留数据。未单独安装组件或操作 VPN 授权，没有星瑞实车验证。未运行完整 CI 或追加主应用全量 Lint／单元测试；组件 Release 依赖的 vital 检查随构建执行。

产物为 `mobile/build/outputs/apk/debug/CarPlay-Preface-0.1.1-98-debug.apk`，25,580,730 字节，SHA-256 `86d99867015b383bc3e886278724c9f1fcd742653140e5f96d38c4dc994dc689`。完整构建、包内核验和安装记录存于本机忽略目录 `agent_docs/build98-20261020/`，原 Gradle APK 和输出元数据保留。

按此前相同的本地授权验证方式，再以原签名核验 com.shihab.diplay、com.shihab.diplay.hudtest、com.shilapi.xcertplay，三者均不匹配此授权的身份列表。本次仍按用户要求原样接入，不修改授权来制造匹配，也不据此认定车机一定放行或切歌跳页已修复。实际 ready、token、requestPlay 与原车页面行为仍需新包实车记录。静态验证只保存布尔结果，不保存解析出的授权明文。

本机证据：`agent_docs/vehicle-20261020-build97/qq-media-manifests.log`、`qq-media-merged-manifest-verification.json`、`qq-license-original-signing-check.json`，以及 QQ 输入缓存；原 APK 和接入值保持本地。

参考固件仍为 IHU519G / FS11A1 / 327，OTA SHA-256 f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01；本轮不增加 IHU353G 或其他固件的媒体准入实测结论。

随后收到构建 98 的实车反馈：蓝牙和 CarPlay 切歌均正常。应用报告仍显示媒体中心 ready=false，不能据此认定 QQ 授权已获准或证明切歌改善的唯一原因。同次日志已确认音量键异常来自通话结束后的通信模式残留，分析和未打包的源码修正见 [构建 98 音量报告](CONNECTION_REPORT_2026-10-20_BUILD98_VOLUME.md)。

随后构建 99 的应用及原车日志再次复现连接后自动暂停、切歌后转蓝牙。E 盘 APK 与已核验产物哈希完全相同，授权文件及配套参数正确，但原车 OpenAPI 仍明确报 `No Authorization to operate [mediacenter] for [com.shihab.diplay.hudtest]`。这次已取得服务端拒绝证据，不能将此前构建 98 的切歌反馈当作 QQ 授权放行；详细时序与行号见 [构建 99 音频报告](CONNECTION_REPORT_2026-10-20_BUILD99_AUDIO.md)。本轮没有修改授权、包名、签名或构建新包。

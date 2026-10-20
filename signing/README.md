# APK 构建签名

按仓库所有者要求，本目录公开 CarPlay-Preface 现有的 APK 签名密钥和签名密码，供克隆仓库后使用同一证书构建 APK。

- `carplay-release.p12`：现有 PKCS12 签名密钥库。
- `signing.json`：可移植的密钥库文件名、类型、别名、storePassword 和 keyPassword；密码按所有者要求公开。
- `carplay-release.p12.enc`：保留的历史 AES-256-GCM 加密备份，原备份密码可用于旧版恢复流程。
- `carplay-release.cer`：公开的 DER 证书，可用于核对 APK 签名。
- `key-info.json`：证书指纹、有效期及公开密钥库／配置的 SHA-256。

密钥沿用已有发布证书，证书 SHA-256 为 `75f4eda0d13be105f578253381eadb5619c04548cc78f58faf2cc8f73a2660a8`。没有重新生成或替换密钥。签名材料公开后，任何人都可以构建同签名 APK；签名本身不再能区分发布者身份，下载时应核对发布页面提供的校验值。

准备好文档要求的 JDK 和 Android SDK 后，可直接构建：

```sh
./gradlew :mobile:assembleRelease
```

CarPlay 测试包也使用同一签名：`mobile` 和 `automotive` 的 Debug／Release 共用本目录的签名配置，主应用 `assembleStandaloneDebug` 同样适用。

```sh
./gradlew :mobile:assembleDebug
```

主应用正式版和测试版统一使用 `com.preface.carplay` 包名及仓库原有证书，可相互覆盖。旧包名应用不会自动升级或迁移数据；相同包名若使用不同密钥，覆盖安装会因签名不同而失败，构建流程只报告错误，不自动卸载或清除原数据。

也可在 PowerShell 7.4+ 中运行：

```powershell
./scripts/apk-signing.ps1 -Action BuildRelease
```

Gradle 默认读取本目录的 `signing.json` 和密钥库，不依赖 `.private/`。PowerShell 脚本优先读取公开配置，并为当前构建进程设置签名参数和已缓存的构建环境。已有 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_TYPE`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 可覆盖 Gradle 默认值，用于其他本地签名配置。

需要使用历史加密备份恢复到本地目录时：

```powershell
./scripts/apk-signing.ps1 -Action Restore
```

按提示输入原备份密码，或通过当前进程的 `CARPLAY_SIGNING_BACKUP_PASSWORD` 提供。常规构建直接复用本目录的公开密钥库即可。

已经发布 APK 后，应持续使用同一密钥进行签名。`Generate` 操作会拒绝覆盖已有密钥或备份。签名密钥只用于 APK 发布签名；CarPlay 配件认证仍使用项目独立的认证配置。

`scripts/check_public_tree.py` 仅对这组已确认内容和现有配件认证资源放行。其他私钥、令牌、本地配置继续排除，APK 签名密钥和签名密码不会作为应用资产打包到 APK。

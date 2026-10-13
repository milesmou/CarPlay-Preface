# APK 发布签名

本目录保存 CarPlay-Preface 的 APK 发布签名密钥的**加密备份**、公开证书及证书信息。

- `carplay-release.p12.enc`：AES-256-GCM 加密备份，包含 PKCS12 密钥及签名参数；不能直接用于 APK 签名。
- `carplay-release.cer`：公开的 DER 证书，可用于核对 APK 签名。
- `key-info.json`：密钥别名、证书 SHA-256 指纹与有效期。

原始密钥和密码位于被 Git 忽略的 `.private/apk-signing/`。请另行备份该目录中的 `signing.local.json`，其中的 `backupPassword` 是恢复本仓库加密备份所需的密码；密码不会随仓库上传。

在 PowerShell 7.4+ 中使用本地密钥构建发布 APK：

```powershell
./scripts/apk-signing.ps1 -Action BuildRelease
```

构建脚本读取本地签名配置，并为当前构建进程设置项目要求的 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。

在新机器上恢复同一密钥：

```powershell
./scripts/apk-signing.ps1 -Action Restore
```

按提示输入备份密码。也可通过当前进程的 `CARPLAY_SIGNING_BACKUP_PASSWORD` 环境变量提供密码；不要将密码写入命令行或 Git 文件。

已经发布 APK 后，应持续使用同一密钥进行签名。`Generate` 操作会拒绝覆盖已有密钥或备份。签名密钥只用于 APK 发布签名；CarPlay 配件认证仍使用项目独立的认证配置。

# CarPlay 实验性认证资源

2026-10-13 按用户要求，从 [DiPlay v0.2.18 官方 APK](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.18) 导入以下文件：

| 仓库路径 | 大小 | SHA-256 |
| --- | --- | --- |
| `runtime-assets/offline-mfi/identity.pk8` | 67 字节 | `bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75` |
| `runtime-assets/offline-mfi/certificate.p7b` | 607 字节 | `634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e` |

来源 APK：`DiPlay-0.2.18.apk`，SHA-256 为 `e241457b36b21fe75b06b5682b4dcfc4fbc25cf75b1855c7a4d055129635ce8e`。完整可机器读取的来源信息见 [provenance JSON](runtime-authentication-provenance.json)。文件按原始字节提取，未生成或替换认证身份。

主应用 `mobile` 的 Debug 和 Release 构建默认把这两项放在 APK 的 `assets/offline-mfi/` 中。`DiPlayBootstrap` 在选择本地认证时，将其复制到应用自己的 no-backup 目录，验证私钥与证书匹配后交给现有本地认证客户端。

```powershell
./gradlew.bat :mobile:assembleStandaloneDebug
./scripts/apk-signing.ps1 -Action BuildRelease
```

一般 `:mobile:assembleDebug` 也会带上这两项。已有 `DIPLAY_AUTH_ASSETS_DIR` 可指定其他本地目录，替换默认输入；替换资料不适用仓库白名单，不应提交。

`.gitignore` 和 `scripts/check_public_tree.py` 只允许已确认的这两项认证资源以及用户要求公开的 [APK 签名材料](../signing/README.md)；其他私钥、令牌和本地配置继续排除。资源的上游说明和用途边界见 [第三方声明](THIRD_PARTY_NOTICES.md#experimental-authentication-data)。

上游将这对数据用于实验性配件认证。打包成功及签名自检只能证明文件存在、密钥与证书匹配，不能证明当前 iOS 接受该身份或星瑞实车已经完成连接验证。已经发布的 `0.1.0` APK 不含它们；0.2.0 默认内置这两项；GitHub 历史发布附件不会因源码改动自动更新。

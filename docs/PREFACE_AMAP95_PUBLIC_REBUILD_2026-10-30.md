# 从公众版重建星瑞仪表候选（可复现改动提取）

2026-10-30，按用户要求把此前只存在于第三方"悬浮版"底包中的改动**提取为可复现补丁**，直接从干净公众版重建等价候选，摆脱对来源不明底包的依赖。本轮只做到**候选 DEX 静态校验**：未打包、未签名、未安装、未实车验证；打包与安装需用户明确授权。

## 范围与结论

- 悬浮版相对公众版的全部差异已精确提取并在 DEX 层重放：**18 个新增类、4 个类的改动、5 个资源文件**；Manifest 两版逐字节相同，仅 `versionCode` 不同。
- 重建出的"公众＋悬浮"底包与已知可启动的 working 底包**在 DEX 层完全等价**（7257 类，0 新增 / 0 删除 / 0 方法体变化）。
- 叠加仪表/后台准备补丁后得到候选 DEX（7284 类）。这是**源码与 DEX 层结论**，不代表已在车机启动或仪表显示。

## 固定输入（SHA-256）

| 输入 | 文件 | SHA-256 |
| --- | --- | --- |
| 公众版 | `amap95-public-user.apk` | `f2855369c11b346756ff828fa453447c812563b0cda678fcc6a36326d385fa00` |
| 可运行悬浮底包（working） | `amap95-working-user.apk` | `094e296ef0783a76d3c2a82a2b91a5eb3198cef2338db78549295617f523ed92` |
| 仪表 payload | `working-hints-candidate/payload/classes.dex` | `ee1bb0e6c3b3a1b455deaeb1b513b653ed73a89c1552bda6ff99365f0172a150` |

## 提取出的悬浮版改动

**18 个新增 DEX 类**：`bin/mt/signature/KillerApplication`(+`$1`)、`floatapp/*` 7 个、`org/lsposed/hiddenapibypass/*` 9 个。

**4 个类改动**：
- `com.autonavi.amapauto.app.MapApplicationProxy`：父类 `Application → KillerApplication`，`onCreate()` 方法体替换。
- `com.alibaba.wireless.security.framework.e.c()`：方法体替换（安全 SDK 装载改动）。
- `com.autonavi.auto.remote.fill.UsbFillActivity`：删除 `onStop()`。
- `kc`：删除 `onPause()`、`onStop()`。

**5 个资源**：`libMapAsset.so`、`carLogo.dat`、`carLogoLow.dat`、`FuncConfig.dat`、语音 `nvzhongyin`。

## 实现与验证

- 新增 DEX 合并工具 `amap95-instrument/tools/PreparePublicBaseDex.java`：按固定差异把悬浮改动施加到公众 DEX，其余类全部保留。
- 新增脚本 `scripts/prepare_amap95_public_rebuild.py`：
  1. 校验三份固定输入哈希；
  2. `PreparePublicBaseDex(public.dex, working.dex)` 生成合并底包；
  3. 用审计工具 `AuditApkDex` 断言合并底包与 working **语义等同**（0 增/删/改）；
  4. `PrepareHintDex(merged, payload)` 叠加仪表补丁，生成候选 DEX；
  5. 断言新增类均为 Preface95 payload（含 `Preface95PreparationProtocol`），并核对 hook 落在 `a20`、`AndroidProtocolExe`、`JsonProtocolService`、`cz`、`g7`、`oo`。

结果（本机报告 `agent_docs/amap-public95-instrument-20261030/public-rebuild/verification.json`）：

| 阶段 | 结果 |
| --- | --- |
| 合并底包 vs working | 7257 类，0 增/0 删/0 方法体变化；`mergedDexSha256=af21d8d5…` |
| 候选 DEX | 7284 类 = 7239 公众 + 18 悬浮 + 27 payload；9 处方法体改动、7 个新增方法；`candidateDexSha256=f9da9ebb…` |
| 复现性 | 全量重跑得到相同候选 DEX 哈希 |

## 限制与未完成项

- **未打包/签名/安装**，未实车验证启动或仪表显示；当前无在线 ADB 设备。
- 候选 DEX 用的是**当前源码 payload**（含后台准备与生命周期修复），对应目标构建 26000008 的仪表侧，因此比已交付 26000007 新；主应用侧配套协议仍未打包。
- 打成可安装 APK 仍需：从 working 取上述 4 个资源；语音按现行策略保留公众默认；Manifest 仅递增 `versionCode`；用 327 平台证书重签。
- 悬浮版本身也缺 `libSignatureKiller.so`／`assets/SignatureKiller/origin.apk` 却能启动；由于重建底包 DEX 与 working 等价、资源按同策略替换，**理论上应与 working 同样启动**，但只有实车可确认，不能据此宣称已修复启动或仪表。
- 未运行 CarPlay 全量测试或任何 APK 构建。

## 打包结果（26000009，用户已授权）

按用户确认（① 打包 ② 保留公众默认语音 ③ 版本号 26000009）使用
`scripts/build_amap95_public_apk.py --build` 从**公众底包**构建：

| 项目 | 结果 |
| --- | --- |
| 文件 | `agent_docs/amap-public95-instrument-20261030/public-rebuild-apk/AMap-9.5.0.600013-E02-public-rebuild-26000009-test.apk` |
| 大小／SHA-256 | 161,533,937 字节／`27ef5986e755446b95e4db4c4ae8180f0553ca0ce4ed3f64ecafcbc131fee51c` |
| 包名／版本 | `com.autonavi.amapauto`／9.5.0.600013／26000009 |
| 证书 SHA-256 | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`（327 平台证书，同 working/26000007） |
| DEX | 候选 `f9da9ebb…`（含悬浮 18 类 + 当前 payload 27 类，含后台准备与生命周期修复） |
| 资源 | 4 个来自 working；语音保持公众默认（2,702,461 字节，`1810793a…`） |
| 完整性 | 63 个原生库不变；非签名条目集合与公众一致（1724 项逐项核对）；Manifest 仅改 versionCode |

**当前无在线 ADB 设备，未安装、未实车验证启动或仪表显示。** 不能将构建成功或 DEX 等价描述为车机已通过。

### 模拟器启动验证（emulator-5554，26000009）

用户启动模拟器后完成安装与冷启动验证：

| 项目 | 结果 |
| --- | --- |
| 安装 | 现有 `26000007` 签名与本次同为 `c8a2e9bc…`，`adb install -r` 覆盖成功，保留数据 |
| 冷启动 | `COLD` → `com.autonavi.amapauto/.MainMapActivity`，TotalTime≈3.0s，PID 存活 |
| 主屏首帧 | 日志命中 `Preface95Hints: MAIN_FIRST_FRAME_RECEIVED` |
| 地图显示 | 主地图与悬浮 UI 完整渲染（搜索、常用地点、周边服务、2D北上等），**无黑屏** |
| 崩溃 | 采样日志无 `FATAL/AndroidRuntime/Fatal signal` |
| 副屏 | 日志 `WAITING_FOR_REAL_DISPLAY_1`；模拟器无真实 Display 1，仪表/外屏 116 无法在本环境验证 |

截图：`public-rebuild-apk/startup-26000009.png`（含首次通知权限弹窗）、`startup-26000009-clean.png`（弹窗后地图）。这证明"公众底包＋悬浮改动＋仪表补丁"可正常启动并显示地图，解决了此前公众底包黑屏；**仪表副屏显示与 CarPlay 接续仍需实车验证**。

## 复现命令

```powershell
python scripts/prepare_amap95_public_rebuild.py            # 候选 DEX
python scripts/build_amap95_public_apk.py --build          # 打包（需授权）
```

产物与审计报告仅存本地忽略目录；固定输入、工具源码与脚本哈希记入本仓库。

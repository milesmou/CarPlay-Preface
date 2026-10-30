# 从公众版重建星瑞仪表候选（纯公众版流程）

2026-10-30。此前候选基于第三方“悬浮版”底包，来源不明且体积大。按用户要求改为**只以干净公众版为基准**：把悬浮版相对公众版的改动一次性**冻结为本地小体积增量补丁**（`floating-delta`），删除悬浮 APK；此后重建只需 **公众 APK + 冻结增量 + Preface95 payload**。打包/安装均需用户明确授权。

## 流程

```
公众 APK ──(取 classes.dex)──┐
                             ├─ PreparePublicBaseDex ──> 合并底包(=历史working等价)
floating-delta/delta.dex ────┘                                   │
Preface95 payload ────────────── PrepareHintDex ──> 候选 classes.dex
                                                               │
公众 APK + 候选DEX + 4个资源(delta) + 公众默认语音 + verCode 26000009 + 327证书 ──> APK
```

- 悬浮 APK **已删除**；其改动只保留为 `agent_docs/amap-public95-instrument-20261030/floating-delta/`（git 忽略、不存第三方字节）。
- `delta.dex` 仅含 18 个新增类 + 2 个供复制方法体的 donor 类（`MapApplicationProxy`、`com.alibaba.wireless.security.framework.e`），约 60KB；另有 4 个资源与元数据。
- 重建不读取任何完整悬浮 DEX/APK。

## 固定输入（SHA-256）

| 输入 | 值 |
| --- | --- |
| 公众版 APK | `f2855369c11b346756ff828fa453447c812563b0cda678fcc6a36326d385fa00` |
| 冻结增量 delta.dex | `513c631f57f13d2580fdcff9d11a5f3262eff43b9be53f4d324a90647f087019` |
| 增量来源（历史悬浮底包） | `094e296ef0783a76d3c2a82a2b91a5eb3198cef2338db78549295617f523ed92`（仅记录来源，APK 已删除） |
| Preface95 payload | `ee1bb0e6c3b3a1b455deaeb1b513b653ed73a89c1552bda6ff99365f0172a150` |

## 冻结的悬浮改动（delta 内容）

- **18 个新增类**：`bin/mt/signature/KillerApplication`(+`$1`)、`floatapp/*` 7 个、`org/lsposed/hiddenapibypass/*` 9 个。
- **4 个类改动**（由 donor 提供方法体/父类）：`MapApplicationProxy`（父类 `Application→KillerApplication`、`onCreate` 替换）、`security.framework.e.c()`、`UsbFillActivity.onStop` 删除、`kc.onPause/onStop` 删除。
- **4 个资源**：`libMapAsset.so`、`carLogo.dat`、`carLogoLow.dat`、`FuncConfig.dat`（语音 `nvzhongyin` 保留公众默认，不进 delta）。

冻结命令（一次性；需重新提供原始悬浮 APK 才能重做）：

```powershell
python scripts/freeze_floating_delta.py
```

## 验证

| 阶段 | 结果 |
| --- | --- |
| 合并底包（公众+delta） | 7257 类，哈希 `af21d8d5…`，与历史已验证 working 底包**逐字节相同** |
| 候选 DEX | 7284 类 = 公众 7239 + 悬浮 18 + payload 27；`f9da9ebb…` |
| 打包 APK | `27ef5986…`，与改动前逐字节一致（证明 delta 流程无损） |

## 打包结果（26000009，用户已授权）

| 项目 | 结果 |
| --- | --- |
| 文件 | `agent_docs/amap-public95-instrument-20261030/public-rebuild-apk/AMap-9.5.0.600013-E02-public-rebuild-26000009-test.apk` |
| 大小／SHA-256 | 161,533,937 字节／`27ef5986e755446b95e4db4c4ae8180f0553ca0ce4ed3f64ecafcbc131fee51c` |
| 包名／版本 | `com.autonavi.amapauto`／9.5.0.600013／26000009 |
| 证书 SHA-256 | `c8a2e9bc…a51192ab8`（327 平台证书） |
| 资源 | 4 个来自 delta；语音保持公众默认（2,702,461 字节） |
| 完整性 | 63 原生库不变；非签名条目集合与公众一致（1724 项逐项核对）；Manifest 仅改 versionCode |

### 模拟器启动验证（emulator-5554，26000009）

| 项目 | 结果 |
| --- | --- |
| 安装 | 与已装 26000007 同签名 `c8a2e9bc…`，`adb install -r` 成功，保留数据 |
| 冷启动 | `COLD` → `.MainMapActivity`，TotalTime≈3.0s，PID 存活 |
| 主屏首帧 | 日志 `Preface95Hints: MAIN_FIRST_FRAME_RECEIVED` |
| 地图显示 | 主地图 + 悬浮 UI 完整渲染，**无黑屏**；无崩溃 |
| 副屏 | `WAITING_FOR_REAL_DISPLAY_1`（模拟器无 Display 1，仪表/116 未验证） |

截图：`public-rebuild-apk/startup-26000009.png`、`startup-26000009-clean.png`。

## 限制

- 模拟器无真实副屏，仪表回读/116/`firstFrame=1` 及 CarPlay 接续仍需实车验证。
- 候选 DEX 用当前源码 payload（含后台准备与生命周期修复），比 26000007 新；主应用侧配套协议尚未打包。
- 冻结增量本质仍是“悬浮改动”的固定化；若需从头重做 delta，须重新提供原始悬浮 APK。
- 未运行 CarPlay 全量测试。

## 复现命令

```powershell
python scripts/freeze_floating_delta.py        # 一次性：重做冻结增量（需原始悬浮APK）
python scripts/prepare_amap95_public_rebuild.py  # 公众+delta → 候选DEX
python scripts/build_amap95_public_apk.py --build  # 打包（需授权）
```

原始 APK、DEX、日志、截图仅存本地忽略目录 `agent_docs/`，不入库。

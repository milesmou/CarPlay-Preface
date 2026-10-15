# Linkco 盒子车机端 APK 无线连接参考

分析日期：2026-10-15。用户反馈这款 CarPlay 盒子无线连接快、接入稳定。本次检查用户提供的车机端 APK，未安装或运行它，未取得盒子固件或手机与盒子之间的抓包；用户体验与静态代码结论分别记录。

## 文件身份与证据范围

| 项目 | 本次文件 |
| --- | --- |
| 提供文件名 | `CarPlay-linkco-v1.9.1.63.2330-20240705 (1).apk` |
| 实际包名／版本 | `com.aufn.carplay` / versionName `1.9.1` / versionCode `63` |
| 文件大小／SHA-256 | 2,472,031 字节 / `bdb4f4075a4b0a3ffad93faa1fd1c07f284b2fe4ade1f114d12bd12c2409d4c9` |
| SDK | minSdk 19 / targetSdk 29 |
| 原生库 | 仅打包 `armeabi-v7a/libfg.so`、`armeabi-v7a/libwm.so` |
| 本地缓存 | Git 忽略目录 `agent_docs/apk-reference/linkco-1.9.1.63.2330/` |
| 事实索引 | [独立 APK 参考索引](reference-apks/linkco-1.9.1-63.json) |

原 APK、完整反编译资源／Java、原生库和详细证据索引仅留本地，不进入仓库。缓存 `catalog.json` 记录证据文件 SHA-256 与行号，可用 `python agent_docs/apk-reference/linkco-1.9.1.63.2330/catalog_reference.py` 重新生成。文件名中的版本后缀／日期与车型名称不能代替实际 APK 版本，也不代表星瑞固件接口已验证。

使用已缓存 jadx 1.5.6；命令结束码 1、报告 4 个错误，资源和主要 USB／命令路径已生成。部分 SettingsFragment、库和解码方法不完整，`h0/c.java` 的响应分发方法有重复代码警告，因此不把反编译结果当完整可运行源码，不据未完整的方法确认默认设置或全部状态机。关键命令与 Android API 缺失另核对原始 DEX string table；原生库只检查符号和 Java 入口，没有进行完整原生实现反编译。

## 可以确认的连接路径

```text
iPhone ── 蓝牙／Wi-Fi ── CarPlay 盒子 ── USB 私有协议 ── 车机 APK
```

车机 APK 的可见主路径是与外接盒子交换 USB 命令及音视频、状态；从下列证据推断，iPhone 的无线接入、配件认证与 CarPlay 主服务主要由盒子端承担。盒子内部的详细实现仍需其固件确认。

| 静态证据 | 本地反编译路径及行号 | 可得结论 |
| --- | --- | --- |
| USB host 特性、USB attach Activity、设备过滤 | `resources/AndroidManifest.xml:14,88`；`resources/res/xml/usbfilter.xml:3,6` | 连接盒子 USB；过滤 VID 1317（0x0525），PID 42151／42149（0xA4A7／0xA4A5），不是 Apple iPhone VID |
| claim USB 接口、bulk 收发 | `sources/h0/a.java:121,234,275,349` | Android 和盒子通过 bulk 传输，数据头／载荷采用该盒子的私有格式，不能当 iPhone NCM 或 iAP2 格式 |
| 查询 Wi-Fi 模块、蓝牙名、盒子固件版本，发送开始工作及 CarPlay 启停 | `sources/a0/a.java:192,457,481,533,581,647`；`sources/d0/a.java:233,363,596` | `check_wifi_module_is_on`、`get_bt_name`、`get_sw_ver`、`start_work`、`carplay_start`、`carplay_stop` 发给 USB 盒子，不是 Android 热点或 BluetoothSocket 调用 |
| 解析盒子返回的 Wi-Fi 模块、蓝牙连接及设备状态 | `sources/h0/c.java:469,476,484`；`sources/d0/a.java:161` | APK 依赖盒子通知推进界面／连接。`wifi_module=` 和 `bt_wl_done` 是状态分支；蓝牙接入状态 64 在模式允许时触发 `carplay_start` 参数 7 |
| 已打包 JNI 入口及动态符号 | `sources/com/juyou/app/decoder/WPFFD.java:14,30`；`sources/com/google/webrtc/juyou/apm/WebRtc.java:186,195` | `fg` 可见视频解码入口及 avcodec 符号，`wm` 可见 WebRTC 音频处理入口。未发现可直接复用的 iPhone 无线接入 JNI 入口；不以符号检索代替完整 native 分析 |

原始 DEX 中没有 `WifiManager`／`WifiConfiguration`／`BluetoothSocket` 类描述符，也没有 `startLocalOnlyHotspot`、`startTethering`、创建／监听 RFCOMM 的常用 API 名称。对应 USB 命令字符串确实存在。这支持上述路径判断，但字符串未命中本身不证明不存在反射、外部动态代码或盒子固件功能。

Manifest 声明 BLUETOOTH、BLUETOOTH_ADMIN、ACCESS_WIFI_STATE、MANAGE_USB 等权限，无 `sharedUserId=android.uid.system` 声明；请求权限不证明获得权限。`Controller.java:2049` 的 `setBluetooth` 分支可启停车机蓝牙，主要是盒子连接后的车机蓝牙冲突处理，不能据此认为 APK 用车机蓝牙完成手机的无线 bootstrap，也不能直接移植到本项目关闭车机蓝牙。

## 快速接入与重连可以借鉴什么

1. **先区分底层盒子链路和手机会话。** `h0/c.java:94` 在 USB ready 后启动接收；`e0/b.java:179` 的定时任务初始延迟 1 ms、此后每次执行完再等 1000 ms。`h0/a.java:211` 返回 USB 心跳写入结果，`h0/c.java:226` 连续三次失败后清理盒子连接。它测量的是车机到盒子的 USB 写入可用性，不是 iPhone Wi-Fi、AirPlay 接入或数据收发成功，不应移植成手机三秒无响应即重建热点。
2. **按状态推进并保留可用底层。** `d0/a.java:114` 按盒子上报的手机／蓝牙状态处理；`:596` 只向盒子下发启动工作命令。`:433` 在配置已发出、手机尚未连接且有原启动参数时再发送基础配置／启动。该重试分支里没有 Android 热点释放或重建调用，但无法据 APK 确认盒子内部是否重启热点。可借鉴按失败层级恢复的原则，不能复制其私有命令或假设其热点生命周期。
3. **把热点／身份稳定性作为下一项验证。** 盒子有独立无线模块，从架构推断它可以由固件维护网络与配对状态，减少对车机 Android 热点生命周期的依赖；是否这样实现、是否固定 SSID／密码／BSSID、频段／信道、IP、缓存认证及是否跳过某轮握手，当前没有证据。用户回答未留意断电后的 Wi-Fi 名称，暂记未确认，不能写成“固定热点导致快连”的已确认根因。

启动命令、USB 心跳和界面等待时间不能代表手机侧连接耗时。本文件没有发现可以直接复制并消除本项目无线失败的 Android API 或 iAP2 参数，也不能依据车机 APK 还原盒子固件里的认证／蓝牙／Wi-Fi 算法。

## 与当前 CarPlay-Preface 的对照

```text
iPhone ── 蓝牙／车机热点 ── Android 应用 iAP2/MFi + AirPlay
```

| 环节 | Linkco 车机 APK 的可见行为 | 本仓库现状／可做的验证 |
| --- | --- | --- |
| 无线网络 | 询问盒子 Wi-Fi 状态，无车机热点创建主路径 | 临时热点默认由 LocalOnlyHotspot 系统分配配置，应用不能在此模式自行指定固定 SSID／密码／信道；手动车机热点模式使用系统保存配置及用户匹配凭据，保持现有两种模式范围 |
| 首次接入失败 | 可重发盒子基础配置和启动命令，未见 APK 重建车机热点 | 上一轮已保留有效临时热点预约，重试同一控制器；只在预约、接口或地址失效时替换。不是本次发现的新修复，见 [热点生命周期](MANUAL_HOTSPOT.md) |
| 配件认证／接入 | 车机下发命令并等待盒子状态；内部流程不可见 | 应用直接承担 RFCOMM／iAP2、MFi、热点凭据公告、StartSession 与 AirPlay 监听，需保留真实协议与就绪检查，不能用盒子命令替代 |
| 故障判断 | USB 写入心跳失败与手机状态分开处理 | 已有热点准备、蓝牙认证、StartSession 到首个 TCP／会话的分阶段诊断；继续用实车报告判断卡在哪一层，不把等待首个 TCP 统一视作热点坏了 |
| 身份跨连接稳定性 | 热点／手机配对等可能由盒子维护，尚无固件证据 | 应用已保存 AirPlay 密钥身份，临时热点配置跨预约是否变化需实测。无线 deviceIdentifier 优先取真实热点 BSSID，未知才使用保存标识；其跨预约变化对重连的影响待核对，不能无依据改为伪造固定 BSSID |

最有价值的下一轮对照是保持同一个车机热点配置，记录 iPhone 自动加入、蓝牙认证、StartSession、首个 AirPlay TCP 的分别耗时，再与临时热点比较。已有手动模式可以承担这个对照，不需要增加第三种连接模式、车型选择或复制盒子 SDK。本次不改变默认临时热点、不改连接代码、不新增蓝牙关机／重置，也不导入原 APK 的私有协议或认证资料。

若继续分析盒子为何能快速稳定接入，需要对应盒子的型号／固件及不含配对秘密的连接日志或协议时间线。仅 APK 足以确认车机和盒子的职责分工，尚不足以定位本项目间歇性自动加入失败的根因。

同日继续追踪切歌，发现 APK 的 ECARX 原厂媒体中心注册与 MusicClient 回调路径；用户确认盒子在同一台星瑞 E02 上方向盘切歌不跳原车媒体页。该路径位于车机 APK，能够作为本项目切歌接管的参考，与盒子固件中的无线接入分开分析。完整证据、播放／暂停映射差异及待验证的接入方案见 [切歌与媒体中心参考](LINKCO_APK_MEDIA_KEYS_REFERENCE.md)。

# 原厂高德实时倒计时接收验证

2026-10-30，用户要求验证原厂能否收到真实倒计时数据，随后启动模拟器，并明确授权“生成并安装验证包”。目标仍为 2023 款星瑞 E02 的中控原厂高德读秒；本轮不迁移仪表插件，不改变 CarPlay 或授权组件。

**当前结论仍为未确定。** 既有日志未找到原厂成功接收读秒的证据；原厂原代码验证包虽安装成功，但普通模拟器缺少 ECARX 系统类，应用在初始化阶段退出，未进入导航和灯态请求阶段。这不是服务器返回不支持，也不是已完成实车验证。

## 历史日志得到的实际观察

扫描本机已有主／系统日志及 `mobilelog.zip`，按内容 SHA-256 去重：175 个文件／归档条目对应 97 份不同日志，共 2,052,287,210 字节。未检出原厂 5.x 的倒计时协议记录，也未检出本轮指定的原生灯态请求／接收日志标记。原厂日志等级、覆盖路段和请求阶段并非完整可见，日志未检出不能作为“不支持”结论。

但实际找到升级后 **9.5.0.600013 的 32 条运行时协议 80189 消息**：

| 项目 | 观察 |
| --- | --- |
| 时间 | 10-29 17:58:58.867043 至 17:59:29.380952 |
| 字段 | `redLightCountDownSeconds` 从 84 到 53，32 条数值依次减 1；同条目还带灯态与方向字段 |
| 归属 | JSON 明确标记 `requestAuthor=com.autonavi.amapauto`、`versionName=9.5.0.600013`、`protocolId=80189`，不能归到原厂 5.x |
| 来源 | 本机 `agent_docs/instrument-navigation-amap-success-20261029/mobilelog/APLog_2026_1029_175428__25/main_log_2__2026_1029_180100`，59318 至 99997 行；SHA-256 `a0c11aabcae2327ec11831db7a1ecc70606b0a0ac92acf18685234a4b56a211c` |

这些消息已超出此前“包内有倒计时模型”的静态线索，证明 9.5 在该次运行窗口实际发布过连续读秒。但没有同一窗口的已安装 APK 字节哈希、完整灯态 HTTP 响应和道路信号实况核对，不能据此证明用户提供两份 9.5 中哪份字节完全相同，或将连续变化本身当作真实道路时序已校准。

## 已构建并安装的原代码验证包

用户继续指定用 327 固件。来源仍为 IHU519G／FS11A1／Android 9／327，OTA SHA-256 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`；实际历史车机是 IHU353G／169，不视为同一构建。

327 的 AutoMap APK 没有内置 DEX，Java 原始代码在配套 VDEX 中。验证包仅移除失效签名、补入此前从固定 VDEX 恢复并校验的标准 DEX，重新对齐与签名；原 Manifest、版本、权限、配置、资源及 69 个原生库的内容逐条目哈希全部保持不变，未增加读秒开关或功能补丁。

| 项目 | 值 |
| --- | --- |
| 原 APK SHA-256 | `82272e4dc531c3ff1b1c6fff02d5fc0362f963319e435e0f5021d4b6dc5c67dc` |
| 配套 VDEX SHA-256 | `33c78415570eb7b6aacc59c43bcb807a8099c5d03976ceaa2b7c5cd80f71608e` |
| 恢复 DEX SHA-256 | `6a736febda15412873b7259bb1cba1e31078f966427c56e4a985a6d0bf870f13`；7,869,772 字节；标准 DEX checksum 与 signature 通过 |
| 验证 APK | 本机忽略目录 `agent_docs/amap-trafficlight-20261030/runtime-validation/build/AutoMap-5.0.7.605871-327-verify.apk` |
| APK SHA-256／大小 | `f6cb78bbbfccf3e39305bb0b72e966c2eb5cf5c7b02007b9b0a075e18b03ceb1`／170,806,433 字节 |
| 身份 | `com.autonavi.amapauto`／5.0.7.605871／6030053；保留原厂版本，用于模拟器新安装验证，不是提高构建号后的实车升级包 |
| 签名 | 匹配 327 原厂证书 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`；签名、ZIP CRC、4 字节对齐均通过 |
| 安装 | `emulator-5554`，`adb install -r` 返回 Success；随后拉回已安装 APK，其 SHA-256 与本次产物一致 |

这是高德原代码的独立验证 APK，不是 CarPlay 主应用；没有打包、修改或安装 CarPlay／Android 9 授权组件。

## 模拟器启动结果及下一步

模拟器为 LDPlayer，Android 14／API 34，报告支持 arm64-v8a 并使用 `libhoudini.so` 转译；显示设为 1920×720 横屏、160 dpi，字体比例由设备状态记录。它不是 Android 9 原厂车机环境。

应用进程 PID 3330 启动，日志确认 `libGbl.so`、`libBootLoader.so` 加载完成，之后在 `MapApplication.onCreate` → `EcarxGkuiBaseImpl.getSupplierCodeAuthentication` 出现：

```text
java.lang.ClassNotFoundException: com.ecarx.xui.adaptapi.device.Device
Unable to create application com.autonavi.amapauto.app.MapApplicationProxy
```

随后应用进程退出。模拟器也确实不存在原厂 `AdapterAPI.jar`、`AdapterAPIImpl.jar`、`ecarx.jar`。因此没有建立原厂导航会话，也没有到达灯态网络请求验证阶段；不能用这次启动失败判断灯态服务是否可用。原厂源代码的设备身份回退还会读取厂商设备 API；本轮没有补空类、伪造供应商身份、设置 ready 或向模拟器植入系统框架来替代这些条件。

后续有效验证需要在实际 E02 车机运行原厂高德，联网建立导航，并在有倒计时覆盖的路口采集完整原厂日志；优先选择曾观察到 9.5 读秒的相同路口。验证需逐步区分请求是否发出、是否收到有效响应、是否产生匹配当前路径且时效有效的灯态、最后是否进入显示链路。若只缺日志，仍不能先判定服务不支持；如果得到明确请求错误或有效响应，再据此决定补原厂读秒还是转向 9.5 仪表适配。

原始日志、完整协议、恢复源码和签名材料均不入库。忽略目录 `agent_docs/amap-trafficlight-20261030/runtime-validation/` 中保存 `existing-log-assessment.json`、`build/verification.json`、`emulator/startup-assessment.json` 和汇总 `assessment.json`；证据行号与校验值见 [固件事实索引](firmware/ihu519g-327.json) 的 `stockAmapTrafficLightRuntimeVerification20261030`。

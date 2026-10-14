# 手动车机热点与临时热点

2026-10-14 按用户要求增加无线热点二选一，界面入口仅放在“设置 → 连接”。默认继续使用临时热点；手动模式显示“热点手动配置”，填写车机系统中实际开启的热点 SSID 和密码。保存后在下一次连接生效，当前会话保持运行，首页不增加配置模块。密码只保存在应用私有设置，不进入设置导出或诊断状态描述。

手动连接通过 ManualHotspotManager 观察已运行热点接口，优先使用系统可读取的热点归属，排除未就绪接口、已知 Wi-Fi 上游和无归属证据的默认网络；回退只接受唯一的无线非上游候选，不使用 USB 或 Wi-Fi Direct 网卡。连续稳定观察后才发布端点，发布前再次核对接口和地址；不明确或歧义时报告未就绪。读取热点配置获准时核对 SSID；读取受限时不能验证保存密码是否正确，需用户确保一致。热点状态／反射读取不可用不代表固件接口已验证。

应用仅持有本轮多播锁与观察回调，关闭手动模式连接时释放自身资源，不关闭系统热点。按用户新增要求，选择手动模式后调用已有 CarHotspotTethering 开启保存配置的热点；检查 WRITE_SETTINGS、接口和实际热点状态，复用已经授权的本地 ADB 回退，不自动申请 ADB 授权。失败显示权限、接口或开启失败原因；可从应用请求修改系统设置权限后重试。此权限不保证厂商系统接口获准。存在 CarPlay 会话时跳过开启，切换到临时模式或页面停止时取消待处理任务。临时模式仍使用 LocalOnlyHotspot 预约，断开时释放本应用预约。两种模式共用 iAP2 凭据公告和 AirPlay 监听，切换不会自动回退为另一种模式。

此实现不增加车型分支。参考 OTA 为 IHU519G/FS11A1 构建 327，来源 SHA-256 为 `f61a7399d61a7f4502b9289ea6c49c661c39c459aae81e676722bcf9be913c01`，静态热点接口名与服务证据见 [固件参考](PREFACE_FIRMWARE_REFERENCE.md) 和 [事实索引](firmware/ihu519g-327.json)。用户先前报告的 IHU353G 与参考固件身份不同；本次普通 Android 接口实现、模拟截图和单元测试都不证明任一固件已完成实车验收。

待实车验证：系统热点在应用 UID 下的状态和接口归属可读性、SSID/密码匹配、蓝牙凭据传递、iPhone 自动／手动加入、首个 AirPlay TCP、画面／音频与重新连接。暂未构建或安装 APK。

界面截图固定为 1920×720 横屏：常规密度与高密度／窄 dp 窗口均保持同一像素尺寸，字体比例 150%。本地截图目录为 `agent_docs/screenshots/manual-hotspot/`；主车机截图 `head-unit-1920x720-wireless-font150.png`、`head-unit-1920x720-manual-font150.png`，高密度截图 `high-density-1920x720-wireless-font150.png`、`high-density-1920x720-manual-font150.png`。使用已存在的设置行样式及对齐，高密度时新控件按文字内容增高以避免裁字，首页控件不采用这项改动。

定向验证共 36 项通过：ManualHotspotReadinessTest 19 项、ManualHotspotConfigTest 5 项、ManualHotspotValidationTest 3 项、WirelessHotspotSettingsTest 6 项、WirelessHotspotModeSelectionTest 2 项、HostConnectionSettingsRefreshTest 1 项。覆盖两种管理器互斥选择、接口稳定／歧义／取消、凭据验证和精确保留、导出不含密码、已有会话不中断及下一次握手读取新设置。未执行全量 CI、Lint 或 APK 构建，不能替代真车验证。

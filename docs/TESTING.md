# 验证范围

本仓库只面向星瑞 E02。普通修改按实际风险做定向检查；不自动构建、签名或安装 APK。“构建测试 APK”默认只执行 :mobile:assembleDebug，再核对签名、内置组件和覆盖安装，见 [构建说明](BUILD.md)。

## 完整测试

只有用户明确要求“完整测试”才执行 .github/workflows/android.yml 对应任务：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :authorization-components:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug
```

CI 另执行 `python3 -m unittest discover -s scripts/tests -p "test_android9_authorization.py"`，覆盖授权组件签名与安装脚本的保护规则。

Windows 使用 gradlew.bat。SDK 配置和组件签名输入见 [构建说明](BUILD.md)。报告分别注明定向测试、Lint、构建和安装的实际结果；仅构建成功不代表完整检查通过。公共文件和凭据范围可单独用 python scripts/check_public_tree.py 检查。

## E02 实车检查

| 场景 | 核对内容 |
| --- | --- |
| 主页／自动连接 | 最多一台当前设备；已配对但离线不自动连接；USB 优先；开机自启与自动连接独立 |
| 取消／竞争 | 等待页返回主页后 USB、无线、迟到授权和旧重试停止；手动选择或重新插线恢复；保留已有投影 |
| USB | 系统授权、NCM/TUN 实时双向通信、重枚举、拒绝后停止、拔线和清理；无线不得申请 VPN |
| 热点 | 临时预约复用／失效重建、手动热点保存配置、会话中不操作开关、不关闭其他持有者热点 |
| 音频 | 媒体／导航 3／12 与用户设置；导航实际发声压低本应用音乐至 30%；电话／Siri 静音与焦点恢复 |
| 蓝牙／方控 | 原车播放真实状态、自动暂停取证、每次按键一次、媒体中心 ready／音源归属、是否跳原车页面 |
| 授权组件 | 实际 API／平台证书／共享 UID；原厂未知组件保护；手动保存安装和逐项撤销；主包与组件分别验证 |
| 显示／电源 | 固定协商 1920×720 或 1760×720；窗口变化不重连；冷启动、休眠、唤醒和后台启动 |

## 界面预览

所有车机图片固定 1920×720 横屏，默认“星瑞 12.3 英寸”应用逻辑密度 160 dpi；记录设备、版本、系统／应用密度、字体和可用窗口，像素不能当作 dp。

应用仅支持中文。布局修改补充中文完整、紧凑窗口、高密度及大字体；不再增加阿拉伯语／RTL 验证。优先已安装 APK 原始截图，可用 scripts/capture_ui_preview.py。Robolectric、示例设备或设计稿必须标注来源，不作为实际连接和实车效果的证明。详见 [屏幕基准](PREFACE_SCREEN_LAYOUT.md)。

## 记录与已知限制

原始诊断保留本机 agent_docs/diagnostics/、reports/，已知运行分析见 [固件资料入口](PREFACE_FIRMWARE_REFERENCE.md)。IHU353G 报告与 IHU519G 构建 327 静态证据分别记录。

当前构建 94 的验证见 [构建核验](ANDROID9_AUTHORIZATION_COMPONENTS.md)；此前自动连接 261 项定向测试不代表完整 CI 已通过。历史 CarHotspotSwitchTest 仍反射调用已移除的 renderHotspotAdbControls，自动连接验证未将该失败计为通过，详见 [自动连接](AUTOMATIC_CONNECTION.md)。

# 工程结构与维护边界

本仓库只服务星瑞 E02。以下描述代码职责，不代表连接、厂商接口或授权流程已经通过实车验证。

## 模块依赖

```mermaid
flowchart TD
    mobile[mobile 主应用] --> common[common 界面与 Android 入口]
    automotive[automotive 应用入口] --> common
    mobile --> shared[shared 协议与运行时]
    automotive --> shared
    common --> shared
    common -. compileOnly .-> stubs[ecarx 媒体中心接口桩]
    mobile -. 构建时内置 .-> authorization[authorization-components 独立授权 APK]
    automotive -. 构建时内置 .-> authorization
```

`common` 使用 `implementation` 依赖 `shared`；需要协议或运行时类型的应用直接声明 `shared` 依赖。`samples/home` 和 `samples/maphost` 是独立宿主示例，不作为其他车型适配分支。

## 界面与配置

- `SettingsInformationArchitecture` 定义设置分类和卡片归属。分类过滤仍由 `DiPlayActivity` 的 `filteredSection` 统一执行。
- `AuthorizationSettingsCard` 只接收状态快照和显式动作；渲染和搜索不准备 VPN、不发起授权。
- `AudioSettingsControls` 管理音频方案、焦点和缓冲控件，通过 `SettingsCardUi` 复用原有主题、尺寸和对话框行为。保存后仍由 Activity 显示重连提示或处理用户确认。
- `AdvancedMediaSettingsCard` 独立渲染实验性媒体卡片，继续使用原有重连确认和权限检查。
- `SettingsButtons` 保留统一按钮绘制和文字对齐；新增卡片不另建尺寸或圆角体系。
- `CarPlayTouchController` 管理触摸序列、画面外按下的抑制和设置手势。窗口测量只用于显示和触摸映射。
- `CarPlayBackgroundSession` 管理进程内会话交接、持有者和关闭完成回调；后台返回仍经过原有清理屏障。

`AirPlayPersistence` 是稳定调用入口，具体读写分别交给 `settings/` 中的 Audio、Connection、Display、Overlay、Identity 存储。`AirPlayPreferenceKeys` 是既有键名的来源；本次没有修改存储格式、默认值或有效范围，也不新增迁移逻辑。备份仍使用显式允许列表，不能从所有键名自动推导允许导出的内容。

## 连接与媒体

- `ConnectionRequestGate` 管理自动启动请求的代次和暂停状态。取消后恢复不会使旧回调重新有效。
- `UsbConnectionPriority` 管理当前线缆的尝试、失败、取消和手动无线选择。`UsbAutoConnect` 负责实际 USB 发现、广播和启动 Intent。
- 启动请求代次与线缆代次分别防护不同事件，不能省去任意一层；有线活动会话优先、失败后显式选择无线、物理重插恢复优先级等规则保持独立验证。
- `CarPlayServiceBindings` 持有控制器申请的 VPN/socket 服务绑定。解绑会唤醒等待线程；关闭或解绑后的迟到连接回调不能重新取得服务。NCM/TUN/socket 的实际停止与关闭顺序仍由控制器及对应服务管理。
- `CarPlayStatus` 是连接编排到界面的状态边界。`CarPlayController` 继续协调有线和无线主流程；后续拆启动阶段时必须保留会话代次、失败停止和资源关闭顺序。
- `AndroidMediaSink` 协调媒体输出。焦点、视频解码、音频渲染、离屏输出和解码策略分别放在独立文件；已有锁、线程、队列和释放顺序保持原实现。

第三方 Concentus 与 SpeexDSP 源码继续保持原来源和许可证边界。

## 分发与验证

`gradle/carplay-distribution.gradle` 是主应用签名、认证资产限制和授权组件内置的共享规则。`mobile` 和 `automotive` 均接入，保留各包已有 `versionCode`，开发版本为 0.1.1。

授权组件的 `prepareDistributionAssets` 任务集中构建后的签名与校验，每个应用的 `prepareAuthorizationAssets` 只同步已校验资产。因此两个应用并行构建时不会同时写同一个签名产物。平台签名输入仍只保留本地忽略目录，构建不会安装或自动授权组件。

CI 包含授权组件单元测试和授权打包脚本测试。日常修改继续只做定向验证；只有明确要求打包才构建 APK，只有明确要求完整测试才执行完整 CI 任务。验证命令和实车范围见 [TESTING.md](TESTING.md)。

## 本次结构调整的定向验证

- 媒体及视频等首轮定向测试 120 项通过；服务绑定的最终 3 项测试通过。
- 最终界面、显示、VPN 授权和音频焦点回归 208 项通过；连接请求状态新增 5 项测试通过。
- 授权打包脚本 22 项测试通过，公共文件检查通过。
- 两个应用入口的构建依赖图检查通过，组件签名任务仅出现一次；没有执行 APK 打包、签名、安装或完整 CI。
- 扩展存储检查发现既有 `ClusterTurnCardPersistenceTest.smallWindowCardKeepsIndependentDefaultsAndPosition` 与原实现冲突：小窗未保存独立位置时，原代码会使用主叠加层保存的位置，测试期望 80，实际为 60。本次 188 个存储方法的实现已核对保持一致，不借结构调整更改此行为；该失败未计为通过。

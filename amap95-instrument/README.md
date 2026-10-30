# 9.5 星瑞仪表移植源码

> **口径（2026-10-30 更新）：已改为纯公众版流程，悬浮版底包已从工程移除。**
> 悬浮版相对公众版的改动已冻结为本地小体积增量 `agent_docs/.../floating-delta/`（`delta.dex` 约 60KB + 4 资源 + 元数据，git 忽略、不入库）；此后重建只用**公众 APK + 冻结增量 + Preface95 payload**，不再依赖任何完整悬浮 APK/DEX。重建与打包脚本为 `scripts/prepare_amap95_public_rebuild.py`、`scripts/build_amap95_public_apk.py`；冻结命令 `scripts/freeze_floating_delta.py`（仅重做增量时需要）。流程、哈希与验证见 [公众版重建](../docs/PREFACE_AMAP95_PUBLIC_REBUILD_2026-10-30.md)。以下历史段落中“悬浮版底包/26000006/26000007”等属当时过程记录。

按用户后续要求，[后台准备链路](../docs/PREFACE_AMAP95_BACKGROUND_PREPARATION_2026-10-30.md)已接入：现有 JSON 服务仅向已核对身份的 CarPlay 客户端提供准备租约，真实初始化／后台工作完成后，对真实副屏空闲画面进行绘制回读，再走原协议准备；退出、失联及本地导航优先时释放。无需手动打开地图，但仍依赖配套改版高德，不能将缓冲区确认当作仪表应答。19 组宿主场景、43 项主应用定向测试及 Android／DEX 核对通过，未构建或安装新 APK；历史 26000007 保留，后续获准打包的目标为 26000008。

后续 [26000007 实车记录及源码修复](../docs/PREFACE_AMAP95_VEHICLE_LOG_2026-10-30.md)已更新：169 车机在升级后及车机重启后均完成提示回读和原车准备，用户反馈起初仪表正常；随后主界面退出导致准备清除。当前源码通过保留单个应用级生命周期观察器，在同进程重开地图时恢复窗口，并过滤旧 Activity 和旧代次回调；仍要求新字段、绘制及真实回读。七组宿主替身回归、Android／DEX 编译通过，未打包或实车复测修复，CarPlay 独立准备仍未实现。下文“尚未验收”保留为历史交付时的范围。

用户后续选择先做箭头、距离和道路名，最新 `java/` 已实现 Android 原生导航提示候选。针对黑屏，已改用用户确认可运行的悬浮版 9.5 底包，生成、覆盖安装 `26000006`；实际冷启动显示主地图／完整界面，并收到真实主屏首帧。高德自身规划并启动导航后，真实箭头、距离及道路名已进入候选回调；副屏等待日志已去重。**实车副屏绘制及仪表显示尚未验收。** 见 [本轮测试记录](../docs/PREFACE_AMAP95_NATIVE_HINTS_TEST_2026-10-30.md)。本目录不加入 CarPlay 构建。

以下 `native/` 仍属于此前完整地图路线：观察者源码已实现，真实地图创建及仪表渲染器未完成，未接入新提示 APK。替身测试不能证明 SDK 注册成功，后续隔离真实库探针也只验证加载及接口绑定。

用户后续要求恢复公众版默认语音并构建，已生成、同证书覆盖安装 `26000007`，冷启动实际显示地图。33 个语音初始化条目全部与公众原包一致，DEX 与 `26000006` 相同，保留音频通道 12；没有重置设备保存的语音选择。语音试听与实车仪表显示待验证，详情见[最新交付记录](../docs/PREFACE_AMAP95_NATIVE_HINTS_TEST_2026-10-30.md)。

公众版重建的差异、既有悬浮／签名代理、生命周期移除及资源替换见[改动审计](../docs/PREFACE_AMAP95_PUBLIC_REBUILD_AUDIT_2026-10-30.md)。这些改动已冻结为 `floating-delta`，由 `scripts/prepare_amap95_public_rebuild.py`（合并到公众 DEX）与 `scripts/build_amap95_public_apk.py`（打包）使用；三份 APK 的旧审计脚本已随悬浮底包移除，复核改用重建脚本（不构建、签名、安装或执行 APK）。

`native/MapServiceObserverBridge` 是自行编写的回调对象。依据固定公众版 9.5 的 `libhsl.so` 调用点，实现两项析构入口及七项设备／Surface／绘制通知的虚表顺序；没有复现厂商对象内部字段、构造地图参数或补造缺失的 SDK 函数。

适用输入 APK SHA-256：`f2855369c11b346756ff828fa453447c812563b0cda678fcc6a36326d385fa00`。消费者 `libhsl.so` GNU Build ID：`71b0534ad0e68635f46cd19c669e1ec6686f63c7`。这是一份依据二进制核对的专用接口适配，不是官方 SDK 兼容承诺。

## 已实现的行为

- 使用不可变会话身份和实际 EGL 设备 ID，拒绝无效会话、主屏设备和其他设备的事件。**窗口号 1001 不是 EGL 设备 ID**，不能直接作为构造参数使用。
- 原样转交事件类型、Surface 尺寸、颜色格式及绘制状态；不把状态值转换为 READY、首帧或 116，不修改系统属性、车辆属性、投屏及电源。
- `DeviceDestroyed`／`SurfaceDestroyed` 先使本次租约失效，再转交终止事件。同一设备 ID 重新出现时，旧对象不能继续向新会话转交事件。
- `deactivate()` 等待其他线程正在执行的回调完成，并拒绝后续回调；允许回调内自行失效。销毁对象及释放回调上下文前仍须从 SDK 移除观察者。

## 注册与退出源码

`PinnedHslApi` 仅绑定本进程已经加载的 `libhsl.so`，通过 `RTLD_NOLOAD`、映射内 ELF 架构／GNU Build ID 和每个导出所属映像核对版本，不启动 SDK、不加载另一份原厂引擎。GNU Build ID 是此处的兼容性标识，不是完整性签名；本地验证脚本另外校验固定 APK 的 SHA-256 及对应函数哈希。

`ObserverSession::open()` 需要窗口创建流程提供**真实创建结果的引擎 ID**。它调用本版 SDK 的 getter，核对已存在的地图、非主屏 EGL 设备及反查窗口 1001，再注册并复核同一组对象。没有地图、地图身份改变或库不匹配时明确失败；不把窗口号当作设备号，不调用可能创建单例的 `GetInstance()`，也不通过 `GetMapEngineId()` 探测未知映射。

注册期间桥接先处于 Pending。会话句柄发布后才允许转交后续事件；注册期间收到的绘制状态不会重放。此时若发生 Surface 销毁或取消，就永久失效并注销，不能在旧设备上重新激活。`OBSERVER_ATTACHED` 只表示观察者流程完成，不是首帧、READY 或 116。

退出使用 `requestStop()` 和 `closeAndDestroy()`：先使桥接失效，等待正在执行的回调，再从 SDK 移除，最后释放桥接及库引用。移除只针对本次对象，保留其他观察者。若在本桥接或其他本仓库桥接的 SDK 回调中请求关闭，返回 `SDK_CALLBACK_MUST_DEFER_REMOVAL` 并保留句柄；窗口所有者必须投递到回调之外再次关闭，成功后才能释放回调上下文和地图。不能在回调里直接销毁对象，也不能因收到 Deferred 就丢弃句柄。

地图 getter 和注册操作必须与实际窗口的生命周期串行，地图／服务需由窗口所有者保持有效直到注销完成；库引用只能防止 ELF 被卸载，不能代替地图对象的所有权。**窗口所有者和地图创建尚未实现，因此这部分源码尚未接入真实高德窗口。**

## 后续接入顺序

1. 核对实际加载的核心库身份，并完成真实 E02 地图创建参数和窗口初始化。此步骤尚未实现，不能调用 `InitMapView(..., nullptr)` 猜测仪表初始化参数。
2. 从该真实地图视图读取并核对非主屏 EGL 设备 ID，绑定对应 Surface 生命周期，创建新的桥接对象和会话身份。
3. 经匹配版本的 `SetDeviceObserver` 注册。该二进制的此方法向观察者集合添加对象，不应清空或替换原有观察者。
4. 由后续渲染实现消费当前 Surface、当前地图及真实完成事件。桥接转交了一次回调，并不等于已经有可用画面，更不允许据此直接发送 116。
5. 退出时先 `deactivate()`，再在 SDK 回调之外调用匹配版本的 `RemoveDeviceObserver` 并等待其返回，最后销毁桥接对象和回调上下文。该固定实现的回调遍历与移除共用锁；不能在持有这把 SDK 回调锁时自行注销。

## 已做验证及限制

已使用缓存 NDK 28.2 编译 arm64／API 28 对象，开启 `-Wall -Wextra -Werror`。对象的虚表含 9 个函数入口，与核对的消费者顺序一致。

`MapServiceObserverBridgeTest.cpp` 使用独立声明的回调接口替身，覆盖全部七项回调的虚表顺序及原始字段、设备和会话过滤、销毁后设备 ID 重用、回调内失效和并发退出等待。测试编译为 x86_64 原生可执行文件，在 `emulator-5554`／API 34 上实际运行通过；临时可执行文件已移除。

这是自有桥接源码的原生单元测试，**未加载厂商 SDK，没有创建地图或验证星瑞仪表显示**。未构建、签名或安装修改 APK。构建对象、测试可执行文件和原始二进制证据仅保存在本地忽略目录 `agent_docs/amap-public95-instrument-20261030/`。

后续已扩展为桥接 6 组、注册会话 8 组，共 14 组原生替身场景，实际在同一 API 34／x86_64 模拟器运行通过。新增覆盖注册前不转交旧事件、注册中身份变化／Surface 销毁回滚、保留其他观察者、回调内延迟注销及并发退出等待。另加载具有不同身份的拒绝测试库，确认生产绑定检查拒绝它且不调用其入口；这不是匹配厂商库的绑定成功测试。

## 可重复验证

先使用缓存环境编译 arm64／API 28 的生产对象及 x86_64 原生替身（不运行）：

```powershell
python scripts/verify_amap95_observer.py
```

明确指定在线 x86_64 模拟器后运行替身，并校验上传文件哈希及清理临时文件：

```powershell
python scripts/verify_amap95_observer.py --run-device emulator-5554
```

此命令只编译原生对象及测试可执行文件，不执行 Gradle、APK 构建、签名、安装、SDK 初始化或车辆协议。报告保存在本地 `agent_docs/amap-public95-instrument-20261030/lifecycle-test/verification.json`，始终标明 `portReady=false`；编译模式明确记录测试未运行，失败会返回非零。不能把测试目录中的拒绝测试库复制进高德 APK。

完整研究、固定输入和阻塞项见 [移植记录](../docs/PREFACE_AMAP_PUBLIC95_INSTRUMENT_PORT_2026-10-30.md)。

## 真实厂商库的隔离加载测试

新增 `native/VendorRuntimeProbe.cpp` 和 [验证脚本](../scripts/verify_amap95_vendor_runtime.py)。它们不接入应用；在独立原生进程中显式加载固定公众 APK 的真实库，执行生产绑定检查和原厂 `GetMapServiceHandle()` getter，不创建上下文或地图。脚本直接从核对哈希的 APK 提取 26 个依赖库，逐个校验上传哈希，结束后删除本次模拟器临时文件。

```powershell
python scripts/verify_amap95_vendor_runtime.py --run-device emulator-5554
```

已在 API 34／x86_64 模拟器通过 Houdini64 执行 ARM64 探针：HSL 单独加载和 TBT 入口所在 `libautonavi.so` 加载均通过，七个 HSL 接口及单例导出绑定成功；真实 getter 均返回空。`Startuptbt`／`Exittbt` 可解析，但没有调用，没有加载 TBT 界面包、创建窗口或发送准备通知。报告位于本地 `vendor-runtime-test/verification.json`，仍为 `portReady=false`。

探针释放自己的绑定引用后使用 `_exit` 结束隔离进程，由操作系统回收映射，明确不验证厂商全局析构。初次试验中，主动卸载尚未完整初始化的厂商库发生 SIGSEGV；这项失败保留在记录中，不能把后续加载测试通过描述为 SDK 退出流程已通过，也不能把探针的进程退出方式用于应用修复。

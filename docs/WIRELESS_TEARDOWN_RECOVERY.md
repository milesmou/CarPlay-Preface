# 无线断线后的资源清理与重连

2026-10-15，针对[构建 65 报告](CONNECTION_REPORT_2026-10-15_BUILD65.md)末尾的重连阻塞，检查现有实现并修正一个具体的无上限等待路径。本页为应用实现与依赖字节码分析，不属于 IHU519G 构建 327 的固件事实，也尚未获得新包实车验证。

## 报告能确定的范围

14:36:18.702 开始旧控制器 teardown，随后等待屏障多次返回 false，最后累计 16015 ms（原始行 1720–1731）。`restartCarPlay` 先调用 `oldController.close()`，返回后才记录 `awaitClosed` 的等待结果。因此此次 `close()` 已返回，后台清理线程尚未结束；不能将其描述成主线程卡在 `close()` 或重试计时器失效。

现有完成日志在各资源关闭返回之后才输出；本段没有足以确定具体卡点的步骤开始日志或线程栈。Bonjour 是代码中发现的可修复候选，不能把候选写成已确认的实车根因。

## 已核对的无上限等待

项目固定依赖 `org.jmdns:jmdns:3.6.3`（`gradle/libs.versions.toml`）。本地缓存 JAR SHA-256 为：

`6b6eb1623cb1d9e51467312ea62c567da005c2aa5a95347f566a11b8703c0ef1`。

通过本地 JDK `javap -c -p` 核对：

- `javax.jmdns.impl.JmDNSImpl.close()` 包含服务注销／取消等待，再调用 `closeMulticastSocket()`。
- `closeMulticastSocket()` 关闭自己的 socket 后，循环检查 `_incomingListener.isAlive()`，每轮 `Object.wait(1000)`，没有整个循环的截止时间；中断异常也不会结束此循环。
- `SocketListener` 在取消／关闭状态下退出接收，不进入错误恢复。主动关闭 socket 前需要确认这些状态，否则关闭活跃 socket 可能触发 `recover()`。

反汇编结果仅保留本地忽略文件 `agent_docs/jmdns-javap.txt`、`agent_docs/jmdns-receiver-javap.txt` 与 `agent_docs/jmdns-state-javap.txt`，不将第三方完整反编译实现提交入库。

原 `CarPlayBonjour.close()` 直接串行调用每个 JmDNS 实例的 `close()`；其上层无线资源清理还持有 `wirelessResourceLock`。因此此等待能够挡住后续热点、AirPlay 服务释放及控制器完成信号。

## 修正

新增 `InterfaceMdnsShutdown`，仅针对固定依赖工厂返回的 `JmDNSImpl`：

1. 由独立守护线程执行该旧实例的正常 `close()`。
2. 观察取消／关闭状态后，关闭该实例自己的 multicast socket，并确认关闭状态；不操作系统热点或其它应用的 socket。
3. 正常清理等待预算为每实例 1 秒。确认 socket 已释放后，余下的库内部线程等待可继续后台收尾，不再挡住控制器退出。Bonjour 自身已经标记关闭，发现／探测回调也停止发布。
4. 未观察到取消状态、无法确认 socket 已关闭且正常清理仍未完成时，保留等待屏障，不将超时当成资源已释放。未知 JmDNS 实现仍执行普通同步关闭。
5. 异常保留固定类别诊断。正常关闭和启动失败后的部分实例回收共用此逻辑；重复关闭不再创建第二个收尾任务。

`CarPlayController.closeBestEffort()` 增加 `teardown resource=… state=begin`，保留原完成及耗时日志；Bonjour 另记 `transportReleased`、`finalizerCompleted` 或失败类名。这样下一份报告能区分仍持有网络资源、库后台收尾及其它清理步骤阻塞，不输出 socket 地址或 DNS 内容。

重连仍等待旧控制器真正完成自身清理，再创建新控制器；没有修改主机层的等待屏障，没有把所有清理超时都视为成功。热点开关、USB、原厂媒体初始化及固定分辨率策略均未在本次改动中调整。

## 验证

46 项定向测试通过：

- 无线清理回归 3 项：模拟 JmDNS 收尾永久等待，确认它自己的 UDP socket 关闭并可重新绑定、后续热点释放与控制器完成不被阻塞；无法确认取消／释放时保留屏障；取消后收尾异常仍释放 socket 并报告错误；覆盖重复关闭。
- Bonjour 双协议栈与服务归属 7 项。
- 无线启动代次／重复失败处理 2 项。
- 主机窗口与重连生命周期 34 项，包含旧控制器未完成时不启动替代控制器。

本次仅运行上述 `:common:testDebugUnitTest --tests …` 定向范围，未运行全量 CI、构建或安装 APK。修正了已识别的 Bonjour 等待风险；是否命中报告中的实际阻塞点，以及是否还有其它清理问题，仍需新包实车复测。

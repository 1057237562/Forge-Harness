# Agent 原生构建 API 接入

## 取消归属修复

NativeBuildClient 每次构建使用独立随机 requestId；NativeBuildService 的 Binder CANCEL
仅接受当前 requestId。通知栏 PendingIntent 也使用该请求独立 URI 和 ID，避免旧通知
取消后续构建。没有有效身份的 BUILD 请求被拒绝。

真机 NativeBuildCancellationTest 通过（2.936 秒）：构建进行中另一请求发送取消，
服务明确返回 accepted=false，当前构建仍成功；发起客户端在资源阶段取消自己构建，
结果为 CANCELLED 且不包含 artifact。两个模板的名称随机化以确保第二次真实编译，
不会被整包缓存绕过取消阶段。证据见 p5-evidence/cancellation-ownership-test.txt。

## 后续真机进展

Core 与 DeepSeek Harness 已在手机安装，未安装 Android Linux 工具包。
`AgentNativeBuildTest` 已通过（3.741 秒）：guest Node 执行 forge-build，收到真实 Java
错误及映射后的源码位置；脚本改正源码后，再次调用原生 Service 成功生成 APK。
这是脚本修复链路，**不是模型自主修复验收**。证据为 p5-evidence/guest-runtime-test.txt
和 guest-native-build-result.json。模型服务尚待配置，其他产品工作继续进行。

发现并修复了上游 aarch64 工具归档夹带的 x86_64 host libc++.so：该文件原先误打入 APK，
会干扰 PRoot 的动态库查找。aapt2/zipalign 为静态 ARM64 ELF，已移除该无关库，
并在打包步骤加入 ELF64/AARCH64 校验。修复后 Agent guest 与原生编译联合测试通过。

同时移除 RuntimeInstaller 中失效的 Linux Android SDK/Gradle 下载、配置与检查函数，
离线 APK 不再包含旧 Android 工具包；界面将原生 Android 编译器标记为内置，
删除旧的 570 MB 下载与额外安装时间估计。清理后的主 APK 构建通过。

## API 接入记录

2026-09-27：新增 NativeBuildGateway，已接入 Claude、DeepSeek Harness、Antigravity
三个驱动的环境与会话生命周期。会话结束或用户停止时关闭网关并取消自己的构建客户端。

Agent 命令：`node /pocket-bridge/forge-build.cjs --project . [--offline]`。
Node 仅发送 loopback HTTP 请求，编译仍由 Android 的独立 NativeBuildService 执行。
命令同步等待 JSON 结果，保留 diagnostics、buildId、artifact 摘要与剩余预算，失败返回非零。
默认每会话最多 4 次构建，凭证仅通过进程环境传递，不写入工程。

接口使用随机会话令牌、恒时凭证比较、固定管理工作区、相对路径/符号链接检查、
请求大小/读取超时限制、有限连接数与构建互斥。合法请求只可构建当前会话工作区。
驱动原有检查点/差异审阅仍适用；本轮未声明其完整回滚验收已完成。

验证：

- 主 APK 与测试 APK 构建成功。
- 真机网关测试通过（2.35 秒）：错误凭证 401、越界/错误字段 400、合法离线请求
  真实生成 APK、工作区路径映射、预算耗尽 429。
- 2 个 Node 命令协议测试通过：请求参数、失败诊断/退出码、凭证不出现在输出、
  缺失会话与越界参数的拒绝。
- 证据：[真机接口测试](p5-evidence/gateway-test.txt)。

未完成：设备上的 Agent runtime 尚未安装，不能把 HTTP 测试称为真实 Agent 端到端。
继续验证 guest Node 实际调用、真实模型修复循环、停止/中断竞争与日志/产物 UI 联动。
会话令牌授权的是当前工程构建，不提供 APK 安装接口；安装仍走用户界面。

同期 AAR 实库结果：AndroidX CardView 1.0.0 在线构建、改源码后的离线重编译、独立验签、
安装与实际构造 CardView 并读取半径通过。这验证了该库的 classes.jar/styleable/R 链路；
不代表所有 AndroidX 或合并 styleable 覆盖场景通过。证据见 p4-evidence/androidx-*。

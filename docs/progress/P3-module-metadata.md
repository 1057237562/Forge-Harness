# Gradle Module Metadata 原生解析与真机验证

2026-09-27：实现 `.module` 数据解析，不运行 Gradle 或插件。主应用已重新构建并安装。

支持 API/runtime 分别选择、Java 8 JVM/Android 变体、同仓库 available-at 重定向、
多平台 component owner、文件别名、发布摘要/长度校验、传递依赖和排除。
处理固定 strict/reject 约束和数字版本最低约束；歧义、动态版本、平台选择等尚未支持的
语义明确报错，不猜测结果。此项不代表完整复刻 Gradle 变体系统。

验证结果：

- 核心测试通过，新增 14 个元数据测试覆盖作用域、重定向、别名、约束、离线与边界。
- 主应用 5 个仪器测试全部通过（46.364 秒），包含 Gson 与 Okio 实库编译。
- `com.squareup.okio:okio:2.10.0` 的 `.module` 与 Kotlin 传递依赖在 Android 16 ARM64
  上由原生编译服务解析；ECJ、D8、打包与 Keystore 签名完成。
- 修改源码后使用显式 `offline=true` 重新编译，`cacheHit=false`，依赖摘要不变。
- 导出 APK 与设备 SHA-256 核对，独立 apksigner 验签通过；安装后 Activity 执行
  `new okio.Buffer().writeUtf8("module-on-android").readUtf8()`，回读结果正确。

证据位于 [p3-evidence](p3-evidence/module-runtime-verified.json)，包含实际运行报告、
依赖解析明细与核心 JUnit XML。复现：运行 NativeBuildIntegrationTest 后执行
`mobile/scripts/test-maven-artifact.ps1 -Fixture module`。

验证是程序行为与文件回读，不包含可见界面验收，也未声称关闭设备网络。
AAR、Manifest/资源合并、Agent 工具桥接和完整产品验收仍需继续。

# Forge Mobile

Android 手机上的 Agent 开发环境与 AIDE 风格原生编译引擎。

目前处于 **原生构建模块与主工程整合阶段**，不是已完成的 IDE。编译链禁止依赖 Termux、
PRoot 或 Gradle；完整目标与缺口持续记录在实现清单中。

- [Spec v0.2](docs/specs/001-mobile-agent-android-ide.md)
- [原生编译验证工程](probes/native-build/README.md)
- [工具来源与第三方说明](probes/native-build/THIRD_PARTY.md)
- [P0 记录](docs/progress/P0-native-build.md)
- [完整实现清单](docs/IMPLEMENTATION.md)
- [构建与验证说明](docs/BUILDING.md)
- [共享编译模块进度](docs/progress/P1-shared-engine.md)
- [Maven 依赖与离线实库验证](docs/progress/P2-maven-dependencies.md)

验证工程使用 Android 独立进程执行 ECJ → aapt2 → D8 → APK 打包 →
zipalign → apksig，并提供真机回归脚本。其实际执行顺序由资源生成 Java
源码的依赖决定：aapt2 先生成 R，再交给 ECJ。

`mobile/app` 是 Mobile-Harness 主工程，已通过桌面编译；`mobile/native-build-core`
与 `mobile/native-compiler` 是新增的可复用原生构建模块。`probes/` 的真机验证器
直接编译和调用这两个模块，不再保留一份独立样例编译器。

`research/` 是不纳入版本管理的上游调研副本；没有将 AIDE-Termux 引入产品。
`.cache/` 和 `build/` 包含工具下载、
生成的开发签名密钥与测试产物，不纳入版本管理。

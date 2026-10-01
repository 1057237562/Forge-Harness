# 探针构建入口同步

2026-09-27：独立探针改为 mobile/:native-probe Android 模块，复用共享编译模块、
完整传递依赖及 XML 工厂适配。原先手工 javac/D8 classpath 已不适合新增的 Manifest
合并器依赖，旧脚本替换为桌面 Gradle wrapper 调用；手机样例编译链不变。

新增 prepare-native-tools.ps1，独立准备固定摘要的工具并检查 ARM64 ELF；
不把归档内无关的 x86_64 host libc++ 打入 APK。主 app 的工具准备提示同步更新。
probe signing 仍只使用独立测试 keystore；共享主应用的 AndroidKeyStore 行为没有改为内置私钥。
重复构建更新 APK/工具清单，保留旧 device-results。

验证：新入口构建成功，真机 13 个探针场景通过，包含正常/修改/导入/错误/取消；
10 个成功样例的实际 Activity 标记通过。4 次整包缓存命中单独标记，不能称为源码增量性能。
设备处于锁屏，本次 BuildOnly 的 visibleUiVerified=0。
证据见 [p10-evidence/probe-summary.json](p10-evidence/probe-summary.json)及同目录完整输出。

构建说明见 [BUILDING.md](../BUILDING.md)。尚未进行无缓存机器/干净 CI 的完整复现验收。

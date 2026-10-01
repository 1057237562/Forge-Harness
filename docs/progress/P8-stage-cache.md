# 原生构建阶段缓存

## 2026-10-01：默认方法反糖化修复

minSdk 21 的库接口默认方法与 Lambda 用例暴露了运行时 AbstractMethodError：
将工程 class 与已经反糖化的库 Dex 直接交给最终 D8，无法完整保留默认方法信息。
现先以原始库 JAR 为 classpath 将工程 class 转为 intermediate Dex，再合并双方 Dex。
库 Dex 缓存仍可在仅修改工程方法体时复用。

加强后的设备测试在独立 InMemoryDexClassLoader 中执行生成的 Dex，验证默认方法返回 42、
库 Lambda 返回 41，并检查缓存命中与 APK 更新；1 项测试通过（5.456 秒）。
同一 APK 独立 v1/v2 验签、安装、启动 Activity 后返回 desugar:42:41。
证据：p8-evidence/desugar-cache-device-test.txt、desugar-cache-runtime.json。
这是 minSdk 21 产物在 Android 16 设备上的验证，尚未证明 Android 21 实机兼容性。

修复后再次运行 NativeKotlinBuildTest 与 NativeBuildIntegrationTest，9 项设备回归全部通过
（96.063 秒），覆盖双向混编、标准库对齐、JAR/AAR 与离线重建、结构化失败及工作区限制。
本次回归日志为 p8-evidence/desugar-related-regression.txt；这些构建测试不等同于全部 APK 的可见 UI 验收。

## 2026-10-01：依赖 Dex 中间产物缓存

运行库作为一组先由 D8 intermediate 模式生成 Dex，再与当前工程的 intermediate Dex 合并。
缓存键包含运行库/编译类路径字节摘要、工具链、minSdk 和工程所有 class 的非代码结构。
结构摘要保留成员、常量、注解、继承关系等，排除方法体和调试信息；因此只改方法体时
可复用运行库 Dex，结构变化仍使其失效。缓存沿用摘要校验、容量管理及源码变更检查。

2 个 Kotlin 混编测试通过（含内置/显式 Maven stdlib），断言源码变化后 Java/classes
缓存不命中，而 dependency-dex 命中。新 APK 安装并运行返回更新后的 kotlin-apk:43。
此次修改后构建总耗时 3760 ms，Dex 阶段 1387 ms，属于当前小工程实测，不是统一性能基准。

7 个 JAR/AAR 构建回归通过；Gson、Okio、本地资源 AAR、CardView 的生成 APK 再次
独立验签、安装和运行回读通过。类结构摘要测试验证方法体可复用、常量/方法/继承/注解变化失效。
证据在 p8-evidence/dependency-dex-* 与 ClassStructureHashTest XML。
更多反糖化场景与低版本设备执行仍需扩展验证，不能据此宣称所有 Java 8 库兼容。

## 容量管理更新

阶段缓存默认上限 512 MiB，恢复命中更新最近使用时间，发布新条目后按最久未使用顺序淘汰。
单个归档超过配额时不入缓存，编译输出保留。仅管理 stages 下符合摘要命名规则的条目，
不会淘汰 Maven 离线依赖、工程源码或已交付 APK。
清理缺少配对文件的归档/校验文件，以及超过 24 小时的本缓存临时文件。
发布阶段缓存前重新验证源码和依赖仍匹配，避免变动构建结果污染缓存。

新增配额/最近使用顺序、无关文件保留、超大条目和中断残留清理测试通过；
StageCacheTest 共 5 项。主 APK 构建通过，重新安装后增量失效规则真机回归通过。
证据：p8-evidence/incremental-quota-device-test.txt 和 StageCacheTest XML。
该配额仅覆盖阶段缓存；构建日志/历史产物和 Maven 缓存的独立存储管理仍需继续。

2026-09-27：资源目录编译、Java classes 与最终 Dex 输出接入独立内容缓存。
缓存键包含工具链/编译器身份和每阶段相关输入；Java 包含源码、生成 R/BuildConfig 与
有序编译类路径，Dex 包含 classes、运行库、编译类路径和 minSdk。

缓存归档以 SHA-256 验证，解包有路径/数量/大小限制，先恢复到独立临时目录再替换空输出。
校验失败视为未命中；不能覆盖非空输出目录。Java/Dex 有诊断警告时暂不缓存，避免复用
字节码后静默丢失警告。aapt2 缓存记录来源路径，用于把缓存资源诊断映射到当前工程。

真机四次构建全部 `cacheHit=false`，均重新完成 APK 链路：

| 输入变化 | 阶段命中 | 本次总耗时 |
|---|---|---:|
| 初次构建 | 无 | 1808 ms |
| 仅增加 asset | 资源编译、Java、Dex | 632 ms |
| 增加 Java 类 | 资源编译 | 1322 ms |
| 资源 XML 增加注释，R 符号不变 | Java、Dex | 953 ms |

真机测试通过（5.336 秒）。缓存恢复/损坏拒绝/非空目录保护单元测试通过。
证据：[阶段与耗时记录](p8-evidence/incremental-build-results.json)及同目录测试输出/XML。

上述耗时是此设备的小模板实测，不代表所有工程或与 AIDE 的性能对照。
资源缓存粒度目前是资源目录，Java/Dex 是整阶段输出；单文件 ECJ 增量、Dex archive 合并、
更细粒度失效仍待完成；后续已补充的依赖 Dex 缓存和空间配额见本页更新。
资源链接、打包和签名每次真实构建继续执行。

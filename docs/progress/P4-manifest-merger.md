# Manifest 合并器与基础 AAR

## 后续：保留库 styleable 索引

新增 LibraryRGenerator，按 AAR 发布的 R.txt 生成库 R 类，使用最终链接 ID，保留
库编译时的 styleable 数组顺序与常量索引。缺少符号/不完整数组映射明确失败；
共享 app namespace 或同包不兼容符号仍明确拒绝，不能静默生成可能错误的 R 类。

3 个映射测试通过。真实 CardView 1.0.0 真机测试在 app 新增 aaa_forge_extra 属性后，
最终全局 CardView_cardCornerRadius 索引变成 4，库索引仍为 3；生成库数组按此重新排列。
在线编译与修改源码后的离线新编译通过（测试 6.303 秒），安装后构造 CardView，
读取 app 主题指定的圆角并换算回 17dp，运行标记通过。

证据：p4-evidence/styleable-device-test.txt、styleable-runtime-verified.json、
cardview-R.java.txt 与 linked-symbols.txt。这验证了实际索引发生位移的场景，
不只是未扩展属性组时的默认库调用。

## 最新结果

2026-09-27 后续验证：已通过 buildSrc/AndroidXmlTransform 校验 common:30.0.3 的
SHA-256，并仅替换 PositionXmlParser/XmlUtils 的 XML 工厂调用，显式使用 Xerces 2.12.2。
不改系统属性、不改变官方合并规则。该适配与依赖声明已纳入编译器身份摘要。

已接入 NativeCompiler：有边界限制的 AAR 解包、classes.jar/libs、资源与库 R 类、
官方 Manifest 合并、assets 覆盖、jni 打包。拒绝目录穿越、重复文件、DTD/实体、
过高 minCompileSdk 和尚未实现的 Prefab/SDK extension/core desugaring 需求。

验证：主 APK 与仪器测试 APK 构建成功，7 个真机仪器测试通过（42.767 秒）；
5 个 AAR 解包单元测试通过。官方合并器的 tools:replace、tools:node=remove、
占位符与最低 SDK 冲突在手机通过。原有 Gson/Okio、缓存、路径与诊断测试继续通过。

本地资源 AAR 在 offline=true、cacheHit=false 下完成新编译。APK 核对摘要、独立验签、
安装和执行通过，实际读取库 R 资源、assets 和合并后 meta-data，得到：
`aar-on-android|asset-from-aar|dev.forge.integration.merged`。

证据在 [p4-evidence](p4-evidence/aar-runtime-verified.json)。复现：运行仪器测试后执行
`mobile/scripts/test-maven-artifact.ps1 -Fixture aar`。

仍需完成：真实 Maven AAR、库字节码对 R 的引用、复杂 styleable、覆盖顺序、JNI、
compileOnly AAR；旧 probes/native-build 手工脚本同步 merger/Xerces 依赖。
当前资源测试 AAR 不含库类，不能据此宣称所有 AAR 可用。完整目标 active，未关机。

## 适配过程记录（下列失败已被上述结果解决）

2026-09-27：已加入官方 `com.android.tools.build:manifest-merger:30.0.3` 与
`com.android.tools:common:30.0.3`，新增 LibraryManifestMerger 包装和真机测试。
包装尚未接入 NativeCompiler，现有 Java/JAR 原生构建入口保持原先实现。

桌面主 APK 与仪器测试 APK 构建通过。处理了 JAXB 链的重复 activation API；
许可证与 NOTICE 文本按合并保留，桌面 SDK repository 的重复 xsd/catalog.xml 不打入 APK。

真机测试尚未通过，当前证据是失败定位：

```
PositionXmlParser.<clinit>
XmlUtils.configureSaxFactory
SAXParserFactory.setXIncludeAware(false)
UnsupportedOperationException: This parser does not support specification "Unknown" version "0.0"
```

使用系统属性与线程类加载器替换 SAX provider 没有改变该行为，已移除无效适配。
下一步对官方 XML 工具层做明确、可复现的 Android 适配，保留完整合并语义。
测试覆盖 library 组件、tools:replace、tools:node=remove、applicationId 占位符和
最低 SDK 冲突；必须等这些测试实际通过后才接到 AAR 编译链路。

此阶段不能声明 AAR 或 Manifest 合并已可用。项目目标仍 active，未关机。

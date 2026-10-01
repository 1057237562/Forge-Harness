# 原生 Android 项目模板

## 产物展示与再次安装

主界面构建结果已保留包名、版本、字节数、构建 ID、SHA-256 与源码快照。
日志对话框可查看这些信息并再次调用系统安装器。
VerifiedBuildArtifact 将文件限定为指定 UUID 构建的 output/app.apk，核对摘要，
拒绝另一次构建的文件、路径穿越、缺失摘要与被修改的内容。
再次安装还核对工程根及当前源码快照；源码已变化时要求重建。

产物身份/内容校验单元测试通过；真机模板新编译后通过校验器，并由 Android
PackageManager 读取到实际包名 dev.forge.template。证据见
p7-evidence/artifact-identity-device-test.txt 和 VerifiedBuildArtifactTest XML。
这些测试未替代可见安装授权/拒绝/完成流程验收。

2026-09-27：新建项目默认提供 Java/XML Android 模板，可取消选择以创建空工作区。
生成 .forge/project.json、Manifest、MainActivity、XML 布局、字符串、README 和 gitignore。
不生成 Gradle wrapper，不依赖下载。应用 ID 使用每个项目的唯一 ID，避免新项目互相覆盖安装。

示例界面包含文字和计数按钮，保存 Activity 状态以恢复计数；该交互仍待可见 UI 验收。
模板创建只允许不存在的目录，先完成文件生成和 ProjectInspector 检查，再注册项目。

验证：

- 3 个单元测试通过：模型导入/名称转义、现有目录保护、非法输入不创建文件。
- 主应用和测试 APK 构建通过。
- 真机原封不动模板 offline=true 新编译通过，cacheHit=false，耗时测试 2.555 秒。
- 使用名称 `Alice's "A&B" <你好>`，aapt2 实际资源编译通过。
- 证据见 [p7-evidence](p7-evidence/template-build-result.json)。

当前设备 keyguard showing=true，未声明新建对话框、编辑器或模板按钮的视觉验收通过。
更多模板、迁移报告、草稿恢复与完整项目工作流继续推进。

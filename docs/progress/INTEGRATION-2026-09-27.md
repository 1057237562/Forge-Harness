# 集成回归记录

本轮按当前工作区重新构建主应用与测试 APK，并运行已有模块的组合回归。

| 范围 | 结果 |
|---|---|
| native-build-core + native-compiler 单元测试 | 97 通过，0 失败，0 跳过 |
| app 在线调试单元测试 | 54 通过，0 失败，0 跳过 |
| com.jarves.mh.build 真机仪器测试 | 16 通过，58.21 秒 |
| 主 APK 与 androidTest APK | 构建和安装成功 |

真机套件覆盖模板、迁移、源码冲突/草稿存储、Manifest 合并、取消归属、网关限制、
Gson/Okio/CardView、本地资源 AAR、签名/整包缓存与阶段失效规则。
本轮草稿用例在同一次仪器运行中顺序执行；真正跨强停进程的证据仍以 P6 的单独记录为准。
本轮没有重做已验证的所有样例安装/运行步骤，也没有把这些仪器测试当成可见 UI 验收。

证据在 integration-evidence：device-suite.txt、unit-summary.json、app-unit-summary.json。

目标仍未完成。实际模型修复、可见 UI/系统安装器、导入导出往返、进一步语言/模块支持、
发布可复现性与设备/性能对照仍按 IMPLEMENTATION.md 继续推进；未执行关机。

# 工程依据与依赖

核实日期：2026-10-21；平台声明同时以本机 SDK 符号检查及运行测试为依据。

## 官方平台资料

- [Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)：目标 SDK 37 的应用在 Android 17 请求 `ACCESS_LOCAL_NETWORK`；Android 10–16 不请求该新权限。
- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)：文件传输使用 `dataSync` 类型和相应权限。
- [前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)：后台运行时间由系统限制，应用实现超时停止，不承诺无限后台运行。
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)：通过系统选择器访问文件并持久化授权；文件移动、删除或授权撤销仍可导致读取失败。
- [共享媒体与下载文件](https://developer.android.com/training/data-storage/shared/media)：通过 MediaStore 发布已接收文件。
- [MediaStore.Downloads](https://developer.android.com/reference/android/provider/MediaStore.Downloads)：当前系统下载目录保存流程使用 API 29 接口，因此最低支持 Android 10。
- [XMLHttpRequest 标准](https://xhr.spec.whatwg.org/)：上传进度事件表示请求正文发送进度，不能当成接收端持久化确认。0.1.2 主进度采用服务端已确认偏移。
- [AGP 9.2 兼容表](https://developer.android.com/build/releases/agp-9-2-0-release-notes)：Gradle 9.4.1、JDK 17+、API 37。
- [Google SDK 官方仓库](https://dl.google.com/android/repository/repository2-3.xml)：下载的平台和构建工具均核对仓库校验值。
- [Apple 照片分享](https://support.apple.com/guide/iphone/share-photos-and-videos-iphf28f17237/ios)：分享选项「当前」防止格式转换。
- [快捷指令输入类型](https://support.apple.com/en-euro/guide/shortcuts/apd7644168e1/ios)与 [URL 请求](https://support.apple.com/en-am/guide/shortcuts/apd58d46713f/ios)：系统分享输入和 POST File 请求体。
- [Shortcuty 签名服务接口](https://github.com/Shortcuty/Signing-Server-API-Documentation)：构建时仅提交公开模板；本机另行验证 Apple 证书链、签名和完整工作流。
- [Apple 大量数据与隐私设置](https://support.apple.com/guide/shortcuts/adjust-privacy-settings-apd961a4fc65/ios)、[Content Graph](https://support.apple.com/guide/shortcuts/the-content-graph-engine-apd4618db957/ios)：大量数据权限及输入内容的自动类型转换。
- [SW-DLT 实际导出工作流](https://github.com/net00-1/SW-DLT/blob/3846e145ce3acb5ad82caf2fdc84a82d9c7921f1/src/SW-DLT.plist)：字典值做字符串比较时显式使用 `WFStringContentItem` 转换，作为独立参数依据。
- [Apple 引擎导出动作元数据](https://github.com/frontboat/shortcutkit/blob/ee52c32937053268e4a035eba4b372fe77ec6411/data/builtin-actions.json)：`dismisssiri` 映射 `WFHandoffAction`，旧 `handoff` 已停用；该资料不是 iOS 执行测试。

## 直接依赖

| 依赖 | 固定版本 | 许可证 / 来源 |
|---|---|---|
| Android Gradle Plugin | 9.2.0 | Apache-2.0，Google Maven |
| Kotlin / Compose 编译插件 | 2.2.10 | Apache-2.0，JetBrains / Maven Central |
| AndroidX Compose BOM | 2025.10.00 | Apache-2.0，Google Maven |
| AndroidX Activity | 1.11.0 | Apache-2.0，Google Maven |
| AndroidX Lifecycle | 2.9.4 | Apache-2.0，Google Maven |
| AndroidX Core | 1.17.0 | Apache-2.0，Google Maven |
| Gson | 2.13.1 | [Apache-2.0](https://github.com/google/gson) |
| ZXing core | 3.5.3 | [Apache-2.0](https://github.com/zxing/zxing) |
| ZXing Android Embedded | 4.3.0 | [Apache-2.0](https://github.com/journeyapps/zxing-android-embedded) |
| JUnit Jupiter（测试） | 5.12.2 | [EPL-2.0](https://github.com/junit-team/junit5) |
| JUnit 4（测试） | 4.13.2 | [EPL-1.0](https://github.com/junit-team/junit4) |

ZXing Embedded 发布节奏较慢，本项目采用其成熟的本地相机扫描功能；无需 Google Play 服务或在线扫码服务。所有运行时依赖在 APK 构建时打包，不需要手机联网下载。

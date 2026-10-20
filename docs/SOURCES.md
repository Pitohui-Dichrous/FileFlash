# 工程依据与依赖

原有资料核实日期：2026-10-20；HarmonyOS 兼容资料补充核实日期：2026-10-21。

本页对应 `harmonyos-4.2` 兼容测试分支：最低 API 29、目标 API 37。HarmonyOS 4.2 设备的实际兼容性以真机检查为准。

## 官方平台资料

- [Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)：目标 SDK 37 的应用在 Android 17 请求 `ACCESS_LOCAL_NETWORK`；本分支在 API 29–36 不请求该新权限。
- [Android 最低与目标 API](https://developer.android.com/guide/topics/manifest/uses-sdk-element)：系统 API 低于 `minSdkVersion` 会阻止安装；较高的 `targetSdkVersion` 不等同于较高的最低安装版本。
- [通知运行时权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)：`POST_NOTIFICATIONS` 从 API 33 开始作为运行时权限，本分支按版本判断后请求。
- [MediaStore API](https://developer.android.com/reference/android/provider/MediaStore)：`setIncludePending` 在 API 29 提供，`QUERY_ARG_MATCH_PENDING` 从 API 30 提供，本分支为待保存文件恢复分别选择可用查询方式。
- [APK v2 签名](https://source.android.com/docs/security/features/apksigning/v2)：Android 7.0 起支持；本分支增加 v1 并保留 v2 与原项目证书，未将原包 v2 签名认定为解析错误原因。
- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)：文件传输使用 `dataSync` 类型和相应权限。
- [前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)：后台运行时间由系统限制，应用实现超时停止，不承诺无限后台运行。
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)：通过系统选择器访问文件并持久化授权；文件移动、删除或授权撤销仍可导致读取失败。
- [共享媒体与下载文件](https://developer.android.com/training/data-storage/shared/media)：通过 MediaStore 发布已接收文件。
- [AGP 9.2 兼容表](https://developer.android.com/build/releases/agp-9-2-0-release-notes)：Gradle 9.4.1、JDK 17+、API 37。
- [Google SDK 官方仓库](https://dl.google.com/android/repository/repository2-3.xml)：下载的平台和构建工具均核对仓库校验值。

## Huawei 官方兼容资料

- [文件管理安装应用提示被禁止安装](https://consumer.huawei.com/cn/support/content/zh-cn00766399/)：适用版本包含 HarmonyOS 4.2，区分外部来源安装权限与解析错误。
- [应用市场安装错误排查](https://consumer.huawei.com/cn/support/content/zh-cn00445299/)：无效、损坏或不兼容的 APK、打包、证书和组件解析均可能影响安装，无法仅凭本次错误文字锁定原因。
- [HarmonyOS 4.X 及以下后台运行排查](https://consumer.huawei.com/cn/support/content/zh-cn00428704/)：允许后台活动、锁定多任务和休眠网络设置可能影响后台可用性；不保证所有设备永久运行。

上述资料没有证明所有 HarmonyOS 4.2 设备采用同一个 Android API 级别；本分支不作此推断。实验范围与用户提供的设备信息见 [HARMONYOS_4_2.md](HARMONYOS_4_2.md)。

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

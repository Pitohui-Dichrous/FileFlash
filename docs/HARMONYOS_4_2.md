# HarmonyOS 4.2 兼容分支

`harmonyos-4.2` 分支尝试让 HarmonyOS 4.2 手机安装 FileFlash，并由该手机开启局域网传输服务。Android 主线保持独立。本分支仍使用 APK，未改为鸿蒙原生 HAP；不据此承诺 HarmonyOS 5 及以上版本兼容。

本次设备信息由用户提供：可能为华为 Mate X5 折叠屏手机，系统 HarmonyOS 4.2.0，具体型号仍待确认。设备名称和系统版本不会被当作已读取到的 Android API 级别。

兼容测试版为 `0.1.1-harmony4.2.1`，最低 API 29、目标 API 37，使用与 Android 主线相同的项目证书，同时提供 v1、v2 签名。主线已有的正式 APK 不变。

此测试包与主线使用相同包名和签名，同一手机安装时会覆盖已有 FileFlash。测试包版本代码为 3，返回版本代码为 2 的主线 0.1.1 需先卸载，或等待版本代码不低于 3 的主线更新；卸载会清除应用内部数据，已保存的下载文件保留。

## 解析失败的排查依据

主线 0.1.1 APK 声明最低 Android API 34。Android 官方规则明确：设备提供的 API 低于 APK 的 `minSdkVersion` 时不能安装。因此，设备兼容层 API 不足是此次问题需要优先排查的原因，但仅凭“错误的解析”无法确认唯一原因。

HarmonyOS 4.2 的版本名称不能直接当作 Android API 级别。华为的 APK 安装排查文档明确列出 HarmonyOS 4.2，但没有证明所有 4.2 设备都提供同一个 Android API 级别。本分支以 API 29 为最低兼容目标，相关新系统功能必须按运行时版本判断。

`targetSdkVersion` 与最低安装版本是两回事。Android 官方说明，目标版本较高的应用仍可运行到其声明的最低版本；没有依据把目标版本较高单独认定为本次解析失败的原因。

APK v2 签名从 Android 7.0 开始支持，适用于 API 29 的兼容目标。增加其他签名形式只能作为安装兼容措施，不能证明原包缺少有效签名。

## 作为传输服务器使用

安装兼容分支 APK 后，在 FileFlash 的“共享”页点击“开启”，其他设备连接相同 Wi-Fi，并打开显示的地址或扫描二维码。iPhone 继续通过 Safari 使用，无需家中另外准备 Android 手机。

本应用的传输内核通过手机本地网络监听端口，网页资源随 APK 提供，不依赖 Google 服务或外部网页服务器。开启服务后，文件、文字、续传及缓存管理沿用现有协议。

首次验证建议让 FileFlash 保持前台且屏幕亮着。只有出现锁屏或退到后台断开时，再按照华为官方说明为 FileFlash 允许后台活动、锁定多任务卡片，并检查休眠时的网络连接设置。前台服务不能保证绕过所有厂商省电策略；本应用不添加开机自启或永久保活。

## 安装失败时

1. 下载发布页中的完整 APK 附件，并核对发布页的 SHA-256。网页另存为、下载中断或文件损坏都不能用于安装。
2. 如果提示禁止外部来源安装，只为实际打开 APK 的浏览器或文件管理器允许安装应用；该提示与解析失败不同。
3. 如果兼容版仍提示解析错误，记录手机型号、完整系统版本与错误原文。这些信息用于区分设备 API、安装器解析、签名和包损坏问题，不需要先恢复出厂设置或关闭系统安全保护。

## 验证边界

本次已在官方 AOSP API 29 / API 31 模拟器各完成 40 项安装、服务端传输、重启恢复、文件保存与缓存检查，并模拟窄屏、展开大屏和旋转。桌面 WebKit 已直接连接 API 31 模拟器内的 APK 完成网页收发及重载续传。旧版 API 的 APK 路线已验证可运行，家人手机的安装结果仍待确认。

降低最低 API、编译通过或在 Android 模拟器通过测试，均不等同于 HarmonyOS 4.2 真机验证。实际执行的检查以 [TEST_RESULTS.md](TEST_RESULTS.md) 和 `test-evidence/` 中对应记录为准。在家人的设备成功安装并完成传输之前，本分支应称为兼容测试版。

折叠与展开后的布局、尺寸变化时的传输连续性仍需区分验证环境；Android 模拟器上的折叠屏或尺寸检查不等同于 Mate X5 实机检查。家人设备的安装、展开与折叠、锁屏和后台行为均尚待真机确认。

## 官方依据

- [Android `<uses-sdk>`：最低安装 API 与目标 API 的区别](https://developer.android.com/guide/topics/manifest/uses-sdk-element)。
- [Android APK v2 签名：Android 7.0 起支持](https://source.android.com/docs/security/features/apksigning/v2)。
- [华为：文件管理安装应用提示被禁止安装](https://consumer.huawei.com/cn/support/content/zh-cn00766399/)，适用版本包含 HarmonyOS 4.2。
- [华为：应用市场无法下载或安装更新应用](https://consumer.huawei.com/cn/support/content/zh-cn00445299/)，区分无效 APK、打包解析、证书与组件解析等错误；文中错误码属于应用市场排查示例，不能直接套用于本次错误原文。
- [华为：HarmonyOS 4.X 及以下应用无法后台运行的排查](https://consumer.huawei.com/cn/support/content/zh-cn00428704/)。

以上资料查阅于 2026-10-21。

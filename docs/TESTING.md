# 复现测试

本文对应 `harmonyos-4.2` 兼容分支，继承 Android 0.1.1 的测试入口。实际通过项目、运行环境和设备边界见 [TEST_RESULTS.md](TEST_RESULTS.md)。

## 鸿蒙分支的旧版 API 检查

`scripts/harmony-smoke.py` 只用于专用、可 root 的官方 AOSP API 29 或 API 31 模拟器。准备签名兼容 APK 于 `app/build/outputs/apk/release/app-release.apk`，并保留原版 `dist/FileFlash-0.1.1.apk` 作为最低 API 拒绝安装的对照；设置 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SERIAL` 和空闲的 `FILEFLASH_QA_PORT` 后执行脚本。

脚本会卸载该专用模拟器上的 FileFlash、安装测试包、创建合成文件，验证服务器、传输、恢复、系统下载保存、缓存保护和不同窗口尺寸，并写入 `docs/test-evidence/harmony-api29.json` 或 `harmony-api31.json`。不要对个人手机运行此脚本。它不能证明 HarmonyOS 真机兼容性；真机仅需安装兼容包，在“共享 → 开启”后用 iPhone 扫码确认基本收发，后台限制按需要另行检查。

## 工程检查

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

核心测试会在本机临时目录运行真实 HTTP 服务，验证认证、分块、校验、重启恢复、取消、路径和长度参数、缓存清理及 4 GiB 以上偏移。

## 桌面浏览器

需要 Node.js 22+。测试依赖只用于验收，不随网页运行，也不被 APK 在线加载。

```powershell
npm.cmd ci --prefix scripts
.\scripts\node_modules\.bin\playwright.cmd install chromium webkit
node scripts/web-smoke.cjs
```

默认使用 Playwright 安装的 Chromium；也可设置 `FILEFLASH_BROWSER_CHANNEL=chrome` 使用已经安装的 Chrome。设置 `FILEFLASH_BROWSER_ENGINE=webkit` 可运行桌面 WebKit。

夹具还验证：完成历史在运行中和重新打开时最多 100 条、超过 7 天清理，105 个暂停任务仍保留；完成记录不再保留续传密钥，文件选择框不持有文件；过期断点明确提示从零重传；取消请求失败时仍可恢复或再次取消。

`web-smoke.cjs` 是协议夹具测试，不能代替真实服务。实际服务测试先构建并在另一个终端启动：

```powershell
.\gradlew.bat :core:installDist
New-Item -ItemType Directory -Force .test-data | Out-Null
.\core\build\install\core\bin\core.bat --storage .test-data/browser --assets app/src/main/assets/web --port 8787 --ready .test-data/browser-ready.json
```

再运行：

```powershell
$env:FILEFLASH_BROWSER_CHANNEL='chromium'
node scripts/web-live.cjs
$env:FILEFLASH_BROWSER_ENGINE='webkit'
node scripts/web-live.cjs
```

本地 ready 文件含临时配对凭据，不应公开。该测试通过桌面引擎实际上传、下载、校验和恢复文件，不能证明实体 iPhone 的相机扫码、系统文件选择、锁屏或后台行为。

## 实际大文件

构建 `:core:installDist` 后，确保 `JAVA_HOME` 指向真实 JDK，并预留至少 6 GiB 空间：

```powershell
node scripts/integration-large.cjs
```

默认通过 HTTP 上传并下载 4 GiB + 65539 字节，服务最大 Java 堆 128 MiB；包含强制终止进程、追加未提交尾部、重新配对及继续上传，最终校验完整长度和 SHA-256。成功时删除本次创建的数据目录，报告保存在 `.test-data/reports/large.json`；失败时保留数据供排查。`FILEFLASH_TEST_BYTES` 可设置较小的整数用于快速检查，但小文件运行不能作为 4 GiB 验证。

`node scripts/hash-large.cjs` 单独检验网页增量 SHA-256 在 4 GiB 以上的正确性，与 Node.js 标准库比较，不需要存储完整文件。

## Android 模拟器

`scripts/android-smoke.py` 使用 Python 3 标准库、JDK、ADB 和已启动的 Android 14+ 模拟器。可通过 `ANDROID_SERIAL` 选择设备。构建调试 APK 后执行；测试安装应用、实际启动本机服务、解码二维码、收发文件及文字，并读取 MediaStore 导出文件进行校验。

脚本优先使用环境中的 Android SDK；本工作区准备的 SDK 放在 `.tools/android-sdk`。模拟器转发网络用于自动验收，报告会明确标记其与实体 Wi-Fi 测试的区别。安装调试 APK 与正式签名 APK时，签名不同会要求先卸载旧版；请仅在专用测试设备执行卸载。

`android-pair-smoke.py` 在两个专用模拟器安装同一签名 APK，验证双向文件和文字；`android-collision-smoke.py` 验证同签名升级及系统重名后的实际文件名。脚本头部注明设备及端口条件，不能对存有个人数据的设备直接运行卸载步骤。

`android-cache-smoke.py` 对专用可 root 的 API 34 模拟器执行缓存专项：需先安装带有合成测试数据的 0.1.0，再提供 `app/build/outputs/apk/release/app-release.apk`。脚本会覆盖升级、构造应用自己的中断保存状态、点击“清理 / 丢弃”、卸载并重装，结果写入 `docs/test-evidence/cache-android.json`。它会卸载测试应用，不能在个人手机上执行。

## 仍需真实设备

最终实际体验应使用 Android 手机及 iPhone Safari，在同一可信 Wi-Fi 验证扫码、双向文件、文字、系统保存、断网重试及权限拒绝。长时间后台和热点行为取决于系统与网络，不承诺无限后台持续运行。

当前未完成实体 iPhone Safari 验证。桌面 WebKit 和 Android 模拟器通过不能替代该项。

## 存储与卸载检查

在专用模拟器验证 [存储策略](STORAGE_PRIVACY.md)：完成系统保存后私有重复文件释放、下载文件可重新读取；模拟保存失败/进程中断时唯一完整副本保留且重启不重复导出；闲置部分数据过期或超预算时回收，活动及近期任务受保护；显式清理和丢弃任务保留源文件与已保存文件。

卸载/重装检查只在专用测试设备进行：应用私有临时文件和设置应消失，公共下载中的接收文件应保留。系统安装资料、浏览器自身网站数据和用户文件不计作应用私有缓存；不应把删除用户文件作为“卸载干净”的验收条件。

另行检查保存中途被终止、未再次启动就卸载的边界：记录 MediaStore 待完成项的状态、实际文件及系统过期时间。它与应用私有数据清理不同，不把系统尚未执行的过期回收写成已经完成。

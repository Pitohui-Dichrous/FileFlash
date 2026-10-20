# 复现测试

本文对应 0.1.5-test.2 选取诊断预发布版；旧脚本及证据保留作历史基线。实际通过项目、运行环境和设备边界见 [TEST_RESULTS.md](TEST_RESULTS.md)。照片库问题、原生选取对照及备用方式见 [IOS_PHOTO_PICKER.md](IOS_PHOTO_PICKER.md)，本版不宣称已修复 iOS 照片库。

## 照片库选取诊断

```powershell
node scripts/photo-picker-smoke.cjs
$env:FILEFLASH_BROWSER_ENGINE='webkit'
node scripts/photo-picker-smoke.cjs
```

独立夹具验证真实 DOM 点击、原生控件直接点击、无选取结果时不发上传、同文件再次选择、多选与重试切换、32 条内存记录和隐私。可追加私有 ready JSON 路径，对实际 Java 或 Android 服务重复测试；报告另存 `photo-picker-browser-*-real-server.json`，不覆盖旧版传输证据。ready 文件含配对凭据，不应公开。

这些自动测试注入 FileList，使用合成 HEIC/MOV 名称及类型检查字节保持，没有调用 iOS 照片库或解码真实相机媒体，不能证明白圈问题已经修复。真实设备只需在“连接详情 → 选取测试”选一张此前失败的相机照片；卡住时退出选取界面，再“诊断 → 复制诊断”。

## 工程检查

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

核心测试会在本机临时目录运行真实 HTTP 服务，验证认证、分块、校验、重启恢复、取消、路径和长度参数、缓存清理及 4 GiB 以上偏移。本次 49 项通过，另覆盖有界内存诊断、合成探测、100 Continue、连续上传的长度与 EOF 清理、并行创建、取消、重启及保存保护；原生分块接口的鉴权和幂等检查保留。

## 桌面浏览器

需要 Node.js 22+。测试依赖只用于验收，不随网页运行，也不被 APK 在线加载。

```powershell
npm.cmd ci --prefix scripts
.\scripts\node_modules\.bin\playwright.cmd install chromium webkit
node scripts/web-smoke.cjs
```

默认使用 Playwright 安装的 Chromium；也可设置 `FILEFLASH_BROWSER_CHANNEL=chrome` 使用已经安装的 Chrome。设置 `FILEFLASH_BROWSER_ENGINE=webkit` 可运行桌面 WebKit。

当前夹具验证单次 File 请求、上传时不调用 Blob.arrayBuffer 或发送端 SHA、等待最终确认、失败清理、重新创建任务重传、取消、空文件、多文件队列、文字复制和诊断。历史版本的历史条数及期限专项证据保留在 `test-evidence/cache-browser.txt`，没有将其冒充本次重跑项目。

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

本地 ready 文件含临时配对凭据，不应公开。该测试通过桌面引擎实际上传、下载、比对内容并验证失败重传，不能证明实体 iPhone 的相机扫码、系统文件选择、锁屏或后台行为。

0.1.2 的旧夹具曾注入丢失确认、提交前断线、等待确认时的进度、校验失败、恢复期间暂停/取消，以及旧任务完整摘要与新选文件尾部不同的情况。这些是历史分块方案的证据。

0.1.5-test.1 网页改为 `restartOnly:true` 任务与单次 `POST /api/uploads/{id}/stream`，直接发送原 File；不预读或计算发送端摘要，不逐块确认，不保留断点。当前夹具和 `web-ios-live.cjs` 验证单次连续上传、错误后删除、人工重试从零、无暂停入口、诊断及有证据的人工重试通道切换；模拟浏览器特征仍不能证明真实 iOS 行为。

## 实际大文件

构建 `:core:installDist` 后，确保 `JAVA_HOME` 指向真实 JDK，并预留至少 6 GiB 空间：

```powershell
node scripts/integration-large.cjs
```

默认通过 HTTP 上传并下载 4 GiB + 65539 字节，服务最大 Java 堆 128 MiB；包含强制终止进程、追加未提交尾部、重新配对及继续上传，最终校验完整长度和 SHA-256。成功时删除本次创建的数据目录，报告保存在 `.test-data/reports/large.json`；失败时保留数据供排查。`FILEFLASH_TEST_BYTES` 可设置较小的整数用于快速检查，但小文件运行不能作为 4 GiB 验证。

网页连续协议另运行 `node scripts/integration-browser-stream.cjs`：同样使用 128 MiB Java 堆，以单个 HTTP 请求流式发送 4 GiB + 65539 字节，并由另一会话下载及核对 Range、长度和内容摘要；还检查断线、取消与强制终止进程后的临时文件清理。结果写入 `docs/test-evidence/browser-stream-large.json`。它是本机 HTTP 协议验证，不代表浏览器、iOS 文件选择器或 Wi-Fi。

`node scripts/hash-large.cjs` 保留检验增量 SHA-256 实现在 4 GiB 以上的正确性，与 Node.js 标准库比较，不需要存储完整文件；当前网页连续上传路径不在发送端计算该摘要。

新版浏览器接口另用 `node scripts/integration-browser-stream.cjs`：默认经一次 HTTP POST 发送 4 GiB + 65539 字节，服务 Java 堆上限 128 MiB，源数据按 128 KiB 生成，不缓存整文件。验证接收端完整摘要、独立会话下载及 Range、断线/取消/强制终止后的临时数据删除；不恢复断点。结果写入 `docs/test-evidence/browser-stream-large.json`，小文件运行仍不能替代 4 GiB 验证。旧 `integration-large.cjs` 保留验证原生分块和续传协议。

## Android 模拟器

`scripts/android-smoke.py` 是最初 Android 14 基线脚本，使用 Python 3 标准库、JDK、ADB 和专用模拟器。可通过 `ANDROID_SERIAL` 选择设备。构建调试 APK 后执行；测试安装应用、实际启动本机服务、解码二维码、收发文件及文字，并读取 MediaStore 导出文件进行校验。0.1.2 最低 Android 10 使用下面的上传专项脚本单独验证。

脚本优先使用环境中的 Android SDK；本工作区准备的 SDK 放在 `.tools/android-sdk`。模拟器转发网络用于自动验收，报告会明确标记其与实体 Wi-Fi 测试的区别。安装调试 APK 与正式签名 APK时，签名不同会要求先卸载旧版；请仅在专用测试设备执行卸载。

`android-pair-smoke.py` 在两个专用模拟器安装同一签名 APK，验证双向文件和文字；`android-collision-smoke.py` 验证同签名升级及系统重名后的实际文件名。脚本头部注明设备及端口条件，不能对存有个人数据的设备直接运行卸载步骤。

`android-cache-smoke.py` 对专用可 root 的 API 34 模拟器执行缓存专项：需先安装带有合成测试数据的 0.1.0，再提供 `app/build/outputs/apk/release/app-release.apk`。脚本会覆盖升级、构造应用自己的中断保存状态、点击“清理 / 丢弃”、卸载并重装，结果写入 `docs/test-evidence/cache-android.json`。它会卸载测试应用，不能在个人手机上执行。

`android-upload-smoke.py` 保留作 0.1.2 自动保存基线；0.1.3 起改为手动保存，请使用 `android-relay-smoke.py --version 0.1.5-test.1` 并先构建对应 APK。该脚本只允许显式设置 `FILEFLASH_DEDICATED_EMULATOR=1`、指定模拟器序号并通过 `ro.kernel.qemu` 检查的专用 API 29 / 34 模拟器。它覆盖签名升级保留数据、B/C 独立会话、上传后没有下载目录副本、C 直接下载、重启恢复、停止共享后点保存、丢弃、手动清理以及启动自动过期清理；只修改本轮合成数据的时间，不删除已有用户保存文件。配对凭据仅写入私有 `.test-data/relay-android-api{N}-ready.json`。

0.1.3 曾使用 `web-upload-regression.cjs` 验证六个空闲预连接、分块丢确认恢复及 B/C 中转。0.1.5-test.1 的实际 APK 浏览器路径改由 `transfer-diagnostics.cjs` 等当前专项脚本记录，验证连续请求及诊断；具体执行参数与结果见交付证据。ADB 转发、模拟器 WebView、桌面浏览器均不替代真实 iPhone 和局域网。

## 仍需真实设备

最终实际体验应使用 Android 手机及 iPhone Safari，在同一可信 Wi-Fi 验证扫码、双向文件、文字、系统保存、断网重试及权限拒绝。长时间后台和热点行为取决于系统与网络，不承诺无限后台持续运行。

用户已报告 0.1.4 在实体 iPhone 17 Pro / iOS 27 的 Safari、Chrome 中仍失败，ROG 9 Pro 和红魔 9 Pro 两台服务器均可复现。0.1.5-test.1 的实体 iPhone 验证尚未完成。桌面 WebKit 和 Android 模拟器通过不能替代该项；请先按 [一次诊断步骤](BROWSER_DIAGNOSTICS.md) 获取收发阶段证据，无须反复上传个人文件。

## 存储与卸载检查

在专用模拟器验证 [存储策略](STORAGE_PRIVACY.md)：完成系统保存后私有重复文件释放、下载文件可重新读取；模拟保存失败/进程中断时唯一完整副本保留且重启不重复导出；闲置部分数据过期或超预算时回收，活动及近期任务受保护；显式清理和丢弃任务保留源文件与已保存文件。

卸载/重装检查只在专用测试设备进行：应用私有临时文件和设置应消失，公共下载中的接收文件应保留。系统安装资料、浏览器自身网站数据和用户文件不计作应用私有缓存；不应把删除用户文件作为“卸载干净”的验收条件。

另行检查保存中途被终止、未再次启动就卸载的边界：记录 MediaStore 待完成项的状态、实际文件及系统过期时间。它与应用私有数据清理不同，不把系统尚未执行的过期回收写成已经完成。

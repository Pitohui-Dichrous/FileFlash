# 复现测试

本文对应 0.1.9。系统分享入口见 [IOS_SHARE.md](IOS_SHARE.md)，旧版脚本和证据保留为历史基线；实际验证边界见 [TEST_RESULTS.md](TEST_RESULTS.md)。

0.1.9 增加安卓服务端全局接收反馈，快捷指令与网页资源逐字节沿用 0.1.8。核心仅调整 HTTP 错误响应及连接清理边界，防止停止活动上传时平台流抛出未检查异常导致进程退出；上传和保存协议不变。`scripts/delivery_host_receive.py` 核验最终签名 APK、两台模拟器实际安装摘要、模型与核心测试、资源与核心摘要；`scripts/verify-delivery.py` 另检查源码包完整性。下方大文件与传输专项保留为 0.1.6 的复现方法。

## 服务端接收进度

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease :core:test :app:testDebugUnitTest :core:installDist
python scripts/android-host-receive-ui-smoke.py --help
```

进度脚本用于专用、已启动的 API 29 / 34 模拟器，限速模拟网页固定长度流、快捷指令 chunked 流和原生分块上传，在真实 APK 上检查四个标签页、未知长度、并发、完成、中断、等待与重启，以及临时文件未自动保存。报告使用独立名称 `host-receive-ui-api29.json` / `host-receive-ui-api34.json`，不覆盖旧证据。脚本只清理本轮登记的任务与文件，并校验升级前已有公共文件；它不执行 iPhone 快捷指令。

0.1.8 的独立快捷指令结构回归仍可用 `python scripts/test-ios-shortcut-contract.py` 和 `python scripts/test-ios-shortcut.py` 复现；验签报告为 `test-evidence/ios-shortcut-signature-0.1.8.json`。

## 系统分享

```powershell
python scripts/build-ios-shortcut.py --check
python scripts/verify-ios-shortcut.py --signed app/src/main/assets/web/FileFlash.shortcut --unsigned scripts/shortcut/FileFlash.unsigned.plist --report docs/test-evidence/ios-shortcut-signature-0.1.8.json
.\gradlew.bat :core:test :app:testDebugUnitTest :core:installDist
node scripts/integration-ios-share.cjs
```

验签额外依赖 `python-aea`、`cryptography`、`pyliblzfse`、`lz4`，只用于开发核验，不进入 APK。快捷指令已随源码提供；构建 APK 无须重新联网签发。默认大文件测试分别用固定长度和 chunked 单请求传输 4 GiB + 65539 字节，核对独立下载、跨界 Range、断线、取消、强杀恢复和物理清理。设置小文件大小只能用于快速调试。

网页复查设置 `FILEFLASH_EVIDENCE_SUFFIX=-ios-share`、`FILEFLASH_IOS_SHARE_TEST=1`，再运行下方照片库脚本；检查原有上传及极简媒体入口。私有 ready 参数可复查最终 APK 服务。验签和桌面引擎不运行 iOS 照片分享。

## 照片库选取诊断

```powershell
$env:FILEFLASH_EVIDENCE_SUFFIX='-ios-share'
$env:FILEFLASH_IOS_SHARE_TEST='1'
node scripts/photo-picker-smoke.cjs
$env:FILEFLASH_BROWSER_ENGINE='webkit'
node scripts/photo-picker-smoke.cjs
```

独立夹具验证真实 DOM 点击、媒体安装入口、原生控件直接点击、无选取结果时不发上传、同文件再次选择、多选与重试切换、32 条内存记录和隐私。可追加私有 ready JSON 路径，对实际 Java 或 Android 服务重复测试；本次报告后缀为 `-ios-share.json`，不覆盖旧版证据。ready 文件含配对凭据，不应公开。

这些自动测试注入 FileList，使用合成 HEIC/MOV 名称及类型检查字节保持，没有调用 iOS 照片库或解码真实相机媒体，不能证明白圈问题已经修复。真实设备只需在“连接详情 → 选取测试”选一张此前失败的相机照片；卡住时退出选取界面，再“诊断 → 复制诊断”。

## 工程检查

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

核心测试会在本机临时目录运行真实 HTTP 服务，验证认证、分块、校验、重启恢复、取消、路径和长度参数、缓存清理及 4 GiB 以上偏移。本次 70 项通过，包括新增系统分享、受限 chunked、并发和磁盘预算；原有 49 项保留。另有 2 项 Android JSON 模型兼容检查，确认旧服务仍显示已知总长、未知流不显示假总长。

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

接收反馈专项在已安装应用的专用 API 29 / 34 模拟器运行，不卸载或清空数据：

```powershell
$env:FILEFLASH_DEDICATED_EMULATOR='1'
python scripts/android-receive-ui-smoke.py --serial emulator-5554 --apk dist/FileFlash-0.1.6.apk --version 0.1.6 --evidence docs/test-evidence/receive-ui-api34-0.1.6.json
```

脚本启动真实传输内核与限速合成文件，通过实际点击验证连接页进度、完成、失败重试、暂停、防重复和大字号布局，并校验系统下载文件。API 29 使用对应模拟器序号重跑。截图留在私有 `.test-data`，本次脱敏报告为 `test-evidence/receive-ui-api{29,34}-0.1.6.json`。

`scripts/android-smoke.py` 是最初 Android 14 基线脚本，使用 Python 3 标准库、JDK、ADB 和专用模拟器。可通过 `ANDROID_SERIAL` 选择设备。构建调试 APK 后执行；测试安装应用、实际启动本机服务、解码二维码、收发文件及文字，并读取 MediaStore 导出文件进行校验。0.1.2 最低 Android 10 使用下面的上传专项脚本单独验证。

脚本优先使用环境中的 Android SDK；本工作区准备的 SDK 放在 `.tools/android-sdk`。模拟器转发网络用于自动验收，报告会明确标记其与实体 Wi-Fi 测试的区别。安装调试 APK 与正式签名 APK时，签名不同会要求先卸载旧版；请仅在专用测试设备执行卸载。

`android-pair-smoke.py` 在两个专用模拟器安装同一签名 APK，验证双向文件和文字；`android-collision-smoke.py` 验证同签名升级及系统重名后的实际文件名。脚本头部注明设备及端口条件，不能对存有个人数据的设备直接运行卸载步骤。

`android-cache-smoke.py` 对专用可 root 的 API 34 模拟器执行缓存专项：需先安装带有合成测试数据的 0.1.0，再提供 `app/build/outputs/apk/release/app-release.apk`。脚本会覆盖升级、构造应用自己的中断保存状态、点击“清理 / 丢弃”、卸载并重装，结果写入 `docs/test-evidence/cache-android.json`。它会卸载测试应用，不能在个人手机上执行。

`android-upload-smoke.py` 保留作 0.1.2 自动保存基线；0.1.3 起改为手动保存。本次构建后使用 `android-relay-smoke.py --apk dist/FileFlash-0.1.6.apk --version 0.1.6 --ready .test-data/ios-share-api34-ready.json --evidence docs/test-evidence/relay-android-api34-0.1.6.json`（API 29 设置 `ANDROID_SERIAL=emulator-5556` 并使用对应报告名）。脚本只允许显式设置 `FILEFLASH_DEDICATED_EMULATOR=1`、指定模拟器并通过 `ro.kernel.qemu` 检查。它覆盖升级保留、B/C 中转、重启、停止后保存、私有副本释放、丢弃及清理；只修改本轮合成数据，不删除已有保存文件。配对凭据仅写入私有 `.test-data`。

`android-ios-share-smoke.py --serial emulator-5554 --apk dist/FileFlash-0.1.6.apk --version 0.1.6 --ready .test-data/ios-share-api34-ready.json --evidence docs/test-evidence/ios-share-android-api34-0.1.6.json` 检查实际 APK 的两种原流上传、独立接收、会话撤销后继续中转、无自动下载目录副本、畸形/半正文清理及鉴权。报告绑定本地与实际安装的 APK，精确测试任务清理资料只写入私有 manifest。

0.1.3 曾使用 `web-upload-regression.cjs` 验证六个空闲预连接、分块丢确认恢复及 B/C 中转。0.1.5-test.1 的实际 APK 浏览器路径改由 `transfer-diagnostics.cjs` 等当前专项脚本记录，验证连续请求及诊断；具体执行参数与结果见交付证据。ADB 转发、模拟器 WebView、桌面浏览器均不替代真实 iPhone 和局域网。

## 仍需真实设备

最终实际体验应使用 Android 手机及 iPhone Safari，在同一可信 Wi-Fi 验证扫码、双向文件、文字、系统保存、断网重试及权限拒绝。长时间后台和热点行为取决于系统与网络，不承诺无限后台持续运行。

用户已报告 0.1.4 在实体 iPhone 17 Pro / iOS 27 的 Safari、Chrome 中失败，ROG 9 Pro 和红魔 9 Pro 两台服务器均可复现。后续已确认 test.1 的 APK、截图、下载图片及直接拍照上传正常，照片库相机媒体仍失败。0.1.6 系统分享执行到回执判断时报参数错误；0.1.7 后小图显示文件名无效、大媒体持续运行。0.1.8 修正文件名编码槽位，仍待真机验证完整成功；桌面 WebKit 和模拟器不能替代该项，见 [IOS_SHARE.md](IOS_SHARE.md)。

## 存储与卸载检查

在专用模拟器验证 [存储策略](STORAGE_PRIVACY.md)：完成系统保存后私有重复文件释放、下载文件可重新读取；模拟保存失败/进程中断时唯一完整副本保留且重启不重复导出；闲置部分数据过期或超预算时回收，活动及近期任务受保护；显式清理和丢弃任务保留源文件与已保存文件。

卸载/重装检查只在专用测试设备进行：应用私有临时文件和设置应消失，公共下载中的接收文件应保留。系统安装资料、浏览器自身网站数据和用户文件不计作应用私有缓存；不应把删除用户文件作为“卸载干净”的验收条件。

另行检查保存中途被终止、未再次启动就卸载的边界：记录 MediaStore 待完成项的状态、实际文件及系统过期时间。它与应用私有数据清理不同，不把系统尚未执行的过期回收写成已经完成。

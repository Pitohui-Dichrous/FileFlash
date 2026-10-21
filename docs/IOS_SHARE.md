# iPhone 照片与文件

0.1.8 使用 Apple「快捷指令」接收系统分享，再将文件直接发送到同一局域网的 FileFlash。不经过 Safari 照片选择器，不要求先另存到「文件」，也不安装第三方 iOS 应用。

## 添加一次

1. 安卓开启共享，iPhone 扫码打开网页。
2. 点「照片 / 视频」→「添加快捷指令」。
3. 打开下载的 `FileFlash.shortcut`，点「添加快捷指令」。

已有旧快捷指令时选择「替换」，避免继续运行旧版。也可直接下载[新版快捷指令](https://github.com/Pitohui-Dichrous/FileFlash/releases/download/v0.1.8/FileFlash.shortcut)；安卓 0.1.6 / 0.1.7 的传输服务可继续使用。

如果删除过 Apple「快捷指令」，可从[国区 App Store](https://apps.apple.com/cn/app/快捷指令/id1462947752)重新安装。

## 发送

照片或视频：**照片 → 选择 → 分享 → 选项「当前」→ FileFlash → 扫安卓二维码**。

文件：**文件 → 选择 → 分享 → FileFlash → 扫安卓二维码**。网页「文件」入口仍可使用。

首次运行按系统提示允许相机和局域网访问。传输期间保持快捷指令在前台；完成显示「已发送」。失败会显示错误，重新分享即可，不保留断点。

大媒体还应检查 iPhone「设置」中的「快捷指令 → 高级 → 允许共享大量数据」。这是 Apple 列出的处理大量数据前提，未公布统一字节阈值，不能单凭此确定卡住原因。[Apple 隐私设置说明](https://support.apple.com/guide/shortcuts/adjust-privacy-settings-apd961a4fc65/ios)

正常完成及可识别的上传错误会断开本轮会话；系统强行结束快捷指令时仍按服务端过期规则回收。

安卓收到后仍为临时中转；可由其他已连接设备直接下载，点击「保存」才进入系统下载目录。自动清理和手动清理沿用原有规则。

## 原理与边界

Apple 的[照片分享说明](https://support.apple.com/guide/iphone/share-photos-and-videos-iphf28f17237/ios)明确提供「当前」格式以防止格式转换。快捷指令支持[照片、媒体与文件输入](https://support.apple.com/en-euro/guide/shortcuts/apd7644168e1/ios)，以及 [POST 的 File 请求体](https://support.apple.com/en-am/guide/shortcuts/apd58d46713f/ios)。本工作流逐项直接发送分享输入，没有图像转换、视频编码、Base64、另存文件或外网上传动作。

「当前」仍需要系统准备可分享的媒体；云端原件可能需要下载。它不代表完整编辑历史、未编辑 RAW 或 Live Photo 的全部组件。本方案避开 Safari 的兼容格式选取与转码路径，不承诺系统处理、网络和后台行为永远不会等待。

即使工作流没有显式转码动作，Apple [Content Graph](https://support.apple.com/guide/shortcuts/the-content-graph-engine-apd4618db957/ios)仍可能为了下个动作的输入类型自动准备或转换内容；本项目没有证据证明 iOS 客户端始终保留原字节或以固定内存发送。

快捷指令已签名并随 APK 提供。构建时只把无设备地址、凭据及用户内容的公开模板交给签名服务；运行时不依赖该服务。签名的 Apple 证书链、完整性，以及解封后的完整工作流均独立核对。公开源为 `scripts/shortcut/FileFlash.unsigned.plist`，核验见 `test-evidence/ios-shortcut-signature-0.1.8.json`。

0.1.6 真机反馈确认可导入、扫码并执行至回执判断，0.1.7 修正了该条件。后续测试小文件返回「文件名无效」，大文件仍持续运行；新发现 URL Encode 的文本槽误用了变量选择器封装。0.1.8 改为文本变量，并对全部动作的配置参数做独立类型核对，保留原名和服务端严格校验。空文件名会在读取正文前被拒绝，可能使大小文件显示不同错误，但尚未证实是大媒体等待的原因。0.1.8 仍需真机复测，静态验签不等于分享成功。

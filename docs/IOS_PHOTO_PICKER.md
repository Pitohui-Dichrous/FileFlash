# iOS 照片库选取问题

0.1.6 的正式发送入口改为 [系统照片分享 → FileFlash 快捷指令](IOS_SHARE.md)，不需要先另存文件。下文保留 Safari 选取问题及历史诊断；不将该浏览器问题写为已修复。

用户在 iPhone 17 Pro / iOS 27 Safari 中选择本地相机照片或录像后，照片库停留在白圈等待，文件未进入网页；单张本地照片也会卡住。截图、下载图片、大型 APK 和直接调用相机拍摄均可上传。上述是用户真机反馈，尚未取得该设备的选取事件记录。

## 证据与判断

[WebKit 325067](https://bugs.webkit.org/show_bug.cgi?id=325067) 记录了 iOS 27 照片库确认后仍停留、文件交付前无网页事件的相近问题。其复现包含批量及 iCloud 场景，不能据此将本次已在本地的媒体归因于 iCloud。

[WebKit 固定版本源码](https://github.com/WebKit/WebKit/blob/3511efda2e/Source/WebKit/UIProcess/ios/forms/WKFileUploadPanel.mm#L958-L1112) 显示，照片库逐项加载系统文件表示，完成后才把文件交给网页；直接相机使用另一条路径。因此当前重点是系统选取与文件交付阶段，尚不能确定具体卡在导出、系统读取还是其他步骤。

调整 `accept` 或取消 `multiple` 不是已验证的修复：[源码中的格式分支](https://github.com/WebKit/WebKit/blob/3511efda2e/Source/WebKit/UIProcess/ios/forms/WKFileUploadPanel.mm#L635-L647) 对无 `accept` 和 `image/*,video/*` 给出相同的表示模式结果；单选也仍用 PHPicker。[270470](https://bugs.webkit.org/show_bug.cgi?id=270470) 仍记录 iOS 格式选择的限制。[303803](https://bugs.webkit.org/show_bug.cgi?id=303803) 是已修复的另一项格式转换问题，说明混加具体格式可能改变行为，不能用枚举 HEIC/JPEG 格式来承诺恢复。

Apple 的 [Current 表示模式](https://developer.apple.com/documentation/photosui/phpickerconfiguration-swift.struct/assetrepresentationmode) 尽可能避免转码，但这是原生 PhotosUI 配置；网页不能直接设置它。桌面浏览器自动测试也不能代替真实 iOS 照片库验证。

## 0.1.5-test.2 选取测试

本版仅增加选取事件诊断和原生控件对照，沿用现有传输模块，不增加媒体转码。

1. 覆盖安装测试版、重开共享，iPhone 用 Safari 重新扫码或刷新。
2. 展开网页连接详情，点“选取测试”，用原生控件只选一张此前失败的本地相机照片；交付成功后会按现有流程上传。
3. 如果仍停在照片库，退出选取界面，点“诊断”→“复制诊断”，复制后再刷新。

报告中的 `pending` 表示网页尚未收到选取结果；`cancel` 也可能来自系统处理失败，不能据此区分用户主动取消和系统失败。网页在收到文件前无法读取媒体或恢复系统照片库内部操作。

## 当前备用方式

在“照片”中选中媒体，点“分享”→“选项”→“当前”，再“存储到文件”；回到 Safari 上传时选择“选择文件/浏览”，从保存位置选择文件，避开“照片库”。Apple 说明[“当前”可防止文件格式转换](https://support.apple.com/guide/iphone/share-photos-and-videos-iphf28f17237/ios)。这是保留当前格式的备用路径，不代表照片库已经修复。

“当前格式”不等于未编辑 RAW、完整编辑历史、全部照片数据或 Live Photo 的全部组件。Apple 在 AirDrop 或 iCloud 链接中另行提供“所有照片数据”；这里不承诺上述信息完整保留。存入“文件”会产生用户可见的副本，传输完成后可自行删除。

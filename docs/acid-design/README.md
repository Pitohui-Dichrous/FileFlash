# 酸性设计 / ACID.01

基于 `v0.1.10`（`d9a18b1`）重新设计 Android 与网页端，开发分支为 `codex/acid-design`。原 `codex/design` 分支已删除；`main` 保持 0.1.10。

![Android 实际运行截图](preview.png)

Anton 大字与错位断行构成页面主体；黑白灰承载操作区，荧光黄绿作为主视觉，电蓝标记章节与连接状态。四个原生页面、网页端、深浅色和窄屏布局均已实现。常用操作采用 Lucide 矢量图标，字体与图标本地打包。

视觉参考：[Kurppa Hosk × Bungie / Marathon](https://www.kurppahosk.com/work/bungie)。未使用游戏商标或游戏素材。[字体、图标与许可](ASSETS.md)。

## 安装包

实验版本：`0.1.10-acid.1`（15），Android 10 及以上。原签名，可覆盖安装。构建输出：`dist/FileFlash-0.1.10-acid.1.apk`。本次未发布新的稳定版本。

SHA-256：`40c100ad7c69fe1da9d572b67b1b5ef1294c8af46f615cbfc0c91e7cf4235b17`

## 验证

- 85 项核心测试、22 项 Android 模型测试通过；发布构建与 Lint 通过。
- API 34 模拟器完成 17 项专项检查，包括四个页面、深浅色、320dp / 1.3 倍字体、输入法、缓存确认、跨页面接收进度和真实文件收发。
- 实际网页检查 320 / 390 / 1280px，完成配对、文字发送和文件上传；无横向溢出或浏览器错误日志。
- 传输逻辑、缓存逻辑和 iOS 快捷指令保持 0.1.10 实现。服务端仅新增本地字体静态资源路由。
- 本轮未操作实体手机；网页的 iOS 入口使用 Chromium 用户代理预览，不等于 iPhone 或快捷指令实机测试。

[构建验证](verification.json) · [Android 验证](native-checks.json) · [网页验证](web-runtime-checks.json) · [全部实图](screenshots/)

## 网页实图

![网页浅色](screenshots/web-desktop-light.png)

![网页深色](screenshots/web-desktop-dark.png)

预览图由实际运行截图等比缩小拼合；未改绘界面。截图中的测试会话已关闭，二维码已失效。

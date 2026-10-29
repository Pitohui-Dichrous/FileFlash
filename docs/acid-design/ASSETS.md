# 酸性艺术设计资产

获取与核对日期：2026-10-29。所有字体和矢量资源离线随 APK 打包；界面不从字体 CDN 或图标服务请求资源。

## 用户提供的应用图标

`0.1.10-acid.2` 使用用户提供的 `FF.png` 原图（1024×1024 PNG，89,922 字节）。未重绘、改色、裁切或重新编码；Android `drawable-nodpi/ic_launcher_art.png` 与网页 `web/icon.png` 均为原文件字节副本。

- SHA-256：`c2749e0d0af0031897e14e8e80f3a1eeb08b58640738862e32ac1808d2c44aa5`。
- Android 自适应图标通过 XML 将原图内缩 16.7%，背景为 `#242424`，为不同启动器形状保留主体空间。Manifest 的常规与圆形图标统一引用 `mipmap/ic_launcher`。
- 网页标签图标直接使用 `/icon.png`。通知继续使用现有单色 `ic_notification`，避免将彩色图片变为不可识别的白色块。
- 图片由用户为本项目提供；不作为 Lucide 或字体许可资产声明。

## 字体组合

最终选择 **Anton Regular + 系统中文无衬线 + 系统等宽字体**。Anton 用于拉丁展示标题与品牌构图；它没有汉字字形，中文操作文字使用系统中文无衬线及其真实粗体，不做几何拉伸。细数字与技术元数据可使用系统 mono。

Anton 的作者为 The Anton Project Authors；许可证为 SIL Open Font License 1.1，完整文本（含版权声明）位于 `app/src/main/assets/licenses/Anton-OFL.txt`。字体从 Google Fonts 官方仓库下载，保留原始字节，不改字形、不子集化、不伪造字重。

- Android 固定名：`R.font.anton_regular` → `app/src/main/res/font/anton_regular.ttf`。
- 网页：`app/src/main/assets/web/fonts/anton-regular.ttf`，与 Android 字节一致。
- 字体：170,812 字节，weight 400，SHA-256 `a4ba3a92350ebb031da0cb47630ac49eb265082ca1bc0450442f4a83ab947cab`。
- 实服 URL 需要映射为 `/fonts/anton-regular.ttf`，响应类型 `font/ttf`；CSP 的同源字体规则即可，无需外部源或 data URL。

Anton 与 Barlow Condensed Black 的字样对照仅用于字体选择，未将后者加入应用，也未沿用旧设计分支的页面排版。

## 操作图标

统一采用 **Lucide 0.468.0** 官方现成矢量。主许可证为 ISC；其继承 Feather 的部分受 MIT 许可覆盖。两份完整许可分别保留在 `app/src/main/assets/licenses/Lucide-LICENSE.txt` 与 `Feather-MIT.txt`。

原始 SVG 在 `app/src/main/assets/web/icons/`，Android VectorDrawable 在 `app/src/main/res/drawable/`。转换保留 24×24 viewBox、路径、圆、直线、多边形及圆角矩形几何、2 单位描边，以及 round cap/join。透明填充；SVG 的 `currentColor` 对应 Android 可 tint 的黑色描边。没有绘制替代操作符号。

使用固定发布版本确保命名与造型一致。旧分支拥有的同名资源仅作来源对照，当前文件全部来自下列官方固定版本；本次不复用旧布局。

| 语义用途 | 官方图标 / SVG 文件 | Android 资源 |
| --- | --- | --- |
| 广播 / 共享 | `radio-tower.svg` | `R.drawable.ic_acid_radio_tower` |
| 连接 | `link-2.svg` | `R.drawable.ic_acid_link_2` |
| 文件 | `files.svg` | `R.drawable.ic_acid_files` |
| 记录 | `history.svg` | `R.drawable.ic_acid_history` |
| 文字 | `type.svg` | `R.drawable.ic_acid_type` |
| 添加 | `plus.svg` | `R.drawable.ic_acid_plus` |
| 扫码 | `scan-line.svg` | `R.drawable.ic_acid_scan_line` |
| 开始 | `play.svg` | `R.drawable.ic_acid_play` |
| 停止 | `square.svg` | `R.drawable.ic_acid_square` |
| 下载 / 接收 | `download.svg` | `R.drawable.ic_acid_download` |
| 上传 / 发送 | `upload.svg` | `R.drawable.ic_acid_upload` |
| 关闭 | `x.svg` | `R.drawable.ic_acid_x` |
| 复制 | `copy.svg` | `R.drawable.ic_acid_copy` |
| 打开 | `external-link.svg` | `R.drawable.ic_acid_external_link` |
| 删除 / 丢弃 | `trash-2.svg` | `R.drawable.ic_acid_trash_2` |
| 重试 / 刷新 | `rotate-cw.svg` | `R.drawable.ic_acid_rotate_cw` |
| 暂停 | `pause.svg` | `R.drawable.ic_acid_pause` |
| 设置 | `settings.svg` | `R.drawable.ic_acid_settings` |
| 帮助 / 信息 | `info.svg` | `R.drawable.ic_acid_info` |
| 前往 / 发送方向 | `arrow-up-right.svg` | `R.drawable.ic_acid_arrow_up_right` |
| 接收方向 | `arrow-down-left.svg` | `R.drawable.ic_acid_arrow_down_left` |
| 开启 / 停止服务 | `power.svg` | `R.drawable.ic_acid_power` |
| 配对二维码 | `qr-code.svg` | `R.drawable.ic_acid_qr_code` |
| 已完成 | `check.svg` | `R.drawable.ic_acid_check` |
| 打开文件夹 | `folder-open.svg` | `R.drawable.ic_acid_folder_open` |
| 发送文字 | `send.svg` | `R.drawable.ic_acid_send` |
| 错误 / 提示 | `circle-alert.svg` | `R.drawable.ic_acid_circle_alert` |
| 局域网连接 | `wifi.svg` | `R.drawable.ic_acid_wifi` |

## 官方来源与文件摘要

以下下载摘要对应保存的实际资源字节；生成的 VectorDrawable 另列原始 SVG 来源。字体与许可证保留上游原文。

| 本地文件 | 来源 | 字节 | SHA-256 |
| --- | --- | --- | --- |
| `app/src/main/assets/web/icons/radio-tower.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/radio-tower.svg | 485 | `d5d2728425fe115bed9f309c03b9dd1e2b7483a9242df87966c37e36ee3381cf` |
| `app/src/main/assets/web/icons/link-2.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/link-2.svg | 330 | `5a17fc2afb01f2889deee2c90c95e37b8eb942f5ffd625b028c87995b46e87ab` |
| `app/src/main/assets/web/icons/files.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/files.svg | 375 | `5bb407dcae94a429672af886b11abcba16ccdffc2ddd6106b1c33c5745c2b1b1` |
| `app/src/main/assets/web/icons/history.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/history.svg | 324 | `a98ff758157910b9fc9d42d5d5dc80c2f4e037afa5f8ea4dee5f5649f03d438d` |
| `app/src/main/assets/web/icons/type.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/type.svg | 334 | `83cd0f418b9cbfec9601cb886d76aa080027b1a47a076eab6948aa50ba1ac6c2` |
| `app/src/main/assets/web/icons/plus.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/plus.svg | 256 | `7f6af73bf1ff6c4bca3f18351c8d1bdec6749c0c2530c4de5da85d520c21df17` |
| `app/src/main/assets/web/icons/scan-line.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/scan-line.svg | 390 | `d17ec0ebae568a92e18e18968bb85658fec088bc3d97b92b729df6f7f9c4848e` |
| `app/src/main/assets/web/icons/play.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/play.svg | 250 | `e646902a5a8ba055ce573c6f51d10bb6eb9d230e7d6e84a9e6c81f599778ffc1` |
| `app/src/main/assets/web/icons/square.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/square.svg | 261 | `bd979354f0ab184b95cecf03eedefe40c2dc65830ac6d7e60017b2b25a354acb` |
| `app/src/main/assets/web/icons/download.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/download.svg | 348 | `21ee20839a201a74e612aafe7eaaf56f6ddc095c74aa5fcbb252c51f74a7535c` |
| `app/src/main/assets/web/icons/upload.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/upload.svg | 345 | `04b9443355c959b3477b7856ee40dffa79438e5bfdb4e9dbf5159341ace26408` |
| `app/src/main/assets/web/icons/x.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/x.svg | 260 | `4a9cdab38fbb96162e7dace28e33f4ca0e49d8963a6162abc3d4691b7d675117` |
| `app/src/main/assets/web/icons/copy.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/copy.svg | 339 | `ea80e566c7a12628a447cb53179b19aea4f60b9143e2becd0d0f20bf260e5718` |
| `app/src/main/assets/web/icons/external-link.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/external-link.svg | 332 | `3891241b8c3bfc3a8a930078f26ffc5a7e9763da6d0162ace2050b28d4c1d1fe` |
| `app/src/main/assets/web/icons/trash-2.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/trash-2.svg | 420 | `e63698acf757d3043fcb4a9ea6ffceff9dd84708707eafc879c86b34a6ff2786` |
| `app/src/main/assets/web/icons/rotate-cw.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/rotate-cw.svg | 299 | `ddcfe6d87240475946935e77411cd4d15a06f3d28a9b921bafed224ebe953668` |
| `app/src/main/assets/web/icons/pause.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/pause.svg | 313 | `0f4280e6dcc1047551bbc6ef8a9f1a34959de13c7f077d9667d12ab90c8fbca7` |
| `app/src/main/assets/web/icons/settings.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/settings.svg | 824 | `3c7bee2696ce0c2d48d34e813f4fcda31f0ad21ab9309aee3751cde4280ad737` |
| `app/src/main/assets/web/icons/info.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/info.svg | 294 | `bc977a64eb96f3e9c1041ffd09a3fceb70e3e65c02b571f76064062ed31f3cb9` |
| `app/src/main/assets/web/icons/arrow-up-right.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/arrow-up-right.svg | 260 | `50b2503b9d11881142255466b7e3461d022b919735841c321d72003ac9959fe1` |
| `app/src/main/assets/web/icons/arrow-down-left.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/arrow-down-left.svg | 260 | `a0e72a3f47e9a8803ddfce675e3caf9119ef041b38c103065ec707af15d90202` |
| `app/src/main/assets/web/icons/power.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/power.svg | 276 | `1e6b84a659aa43f826cdd8df0a63d349720004e2ccb10c33e415a990a311aa05` |
| `app/src/main/assets/web/icons/qr-code.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/qr-code.svg | 620 | `920cb79ab4b2fd23c36a16de3ca533c4896cb989adb823eb7dec97fd840d30f5` |
| `app/src/main/assets/web/icons/check.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/check.svg | 239 | `7f33acc9a77a61659531044525fc008edebe215bf4dcf1c789c8674ad3277db0` |
| `app/src/main/assets/web/icons/folder-open.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/folder-open.svg | 401 | `ee57452b40e71df310214091dd9da1941a397c83197b8ff8d7ae66dd4242578b` |
| `app/src/main/assets/web/icons/send.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/send.svg | 393 | `a489257f447b808ab6b258ae888fa49686885bbc30a7a2774d97a01f11f9cd5e` |
| `app/src/main/assets/web/icons/circle-alert.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/circle-alert.svg | 332 | `ce3e98b7a03b78c0071131df42466e4e1ff5187fccad30e739ec9ce951cf685a` |
| `app/src/main/assets/web/icons/wifi.svg` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/icons/wifi.svg | 357 | `5c4c795d014fa95d1e1038362df44a0f0f32ded9cb08c833ae72419ebadc9a91` |
| `app/src/main/assets/licenses/Lucide-LICENSE.txt` | https://raw.githubusercontent.com/lucide-icons/lucide/0.468.0/LICENSE | 880 | `1e7290b35280a048667bbf0ebabac1c7fd52a75300e8b2946ac165715997f2bc` |
| `app/src/main/assets/licenses/Feather-MIT.txt` | https://raw.githubusercontent.com/feathericons/feather/master/LICENSE | 1082 | `308028e93fcf84972523cdf6e616f73168546b4953895f516d01287f16fe7bee` |
| `app/src/main/res/drawable/ic_acid_radio_tower.xml` | app/src/main/assets/web/icons/radio-tower.svg | 1741 | `92c82405e012cd42bb157150f3675043036332f305fe26af20aac6ae5f82d98c` |
| `app/src/main/res/drawable/ic_acid_link_2.xml` | app/src/main/assets/web/icons/link-2.svg | 895 | `242ad42c41275059f510c18e371b558fbc69bbed438ec731e5ae972693784759` |
| `app/src/main/res/drawable/ic_acid_files.xml` | app/src/main/assets/web/icons/files.svg | 954 | `a444b28e309f3b727332bcc15a100e152fc832d15545cbb4aa7f27b58bf72ec9` |
| `app/src/main/res/drawable/ic_acid_history.xml` | app/src/main/assets/web/icons/history.svg | 903 | `27bce68d90e971566cfa9e069f46ffb7eaaaf3bd9d0c839b322b462d97163a7e` |
| `app/src/main/res/drawable/ic_acid_type.xml` | app/src/main/assets/web/icons/type.svg | 880 | `0c12ac4263331e48665544d9e1e2f2eabc7b0d7774da68578dd09bf1d75dc3ff` |
| `app/src/main/res/drawable/ic_acid_plus.xml` | app/src/main/assets/web/icons/plus.svg | 671 | `807eb5942ae7c8d38b07698cbca9c88442cd6caaa0b494aba67e37b469220328` |
| `app/src/main/res/drawable/ic_acid_scan_line.xml` | app/src/main/assets/web/icons/scan-line.svg | 1297 | `625ef0d24ae5a93f25dc8e99d8e9f9180bf0a0cc28184fc9fc68797cbb564542` |
| `app/src/main/res/drawable/ic_acid_play.xml` | app/src/main/assets/web/icons/play.svg | 499 | `249b3ac0635281448d56139b4fe83baaac84c77bfbdba1832b90555b7d075bb4` |
| `app/src/main/res/drawable/ic_acid_square.xml` | app/src/main/assets/web/icons/square.svg | 559 | `cc1dafa6e31350ecaff8f37c9c5dc335816ed234349d0d427b2cdab7901eda9d` |
| `app/src/main/res/drawable/ic_acid_download.xml` | app/src/main/assets/web/icons/download.svg | 907 | `1743bc171fa655f74c26949ae2fdbafb42aa302d62da857c8efcbe723414d551` |
| `app/src/main/res/drawable/ic_acid_upload.xml` | app/src/main/assets/web/icons/upload.svg | 904 | `d0d97c17a12fddd1e52250e7366ec1e24d47226ab8b961bd73af5a4dfa877b6e` |
| `app/src/main/res/drawable/ic_acid_x.xml` | app/src/main/assets/web/icons/x.svg | 675 | `2945fc2790b2a43ffbb2bb412f6e9c178ae5c9e4110e709882a3d85048d4e72f` |
| `app/src/main/res/drawable/ic_acid_copy.xml` | app/src/main/assets/web/icons/copy.svg | 799 | `2890e41f99dd4a604657507903faa3ac1e2c3d958851257558aeee8ad548439d` |
| `app/src/main/res/drawable/ic_acid_external_link.xml` | app/src/main/assets/web/icons/external-link.svg | 911 | `1a27c7850c5ee4611f73464949ebcf6b9b4c709dd0d7b726d5a341575023e5ef` |
| `app/src/main/res/drawable/ic_acid_trash_2.xml` | app/src/main/assets/web/icons/trash-2.svg | 1299 | `c705f22dcf824f7d5ed098d4b5cde6622fb035eaf579907f69cf7a9ab3f1b46e` |
| `app/src/main/res/drawable/ic_acid_rotate_cw.xml` | app/src/main/assets/web/icons/rotate-cw.svg | 714 | `83c6dbd9fb20948d04e87212d436affb7f213acad0d66d4a48d50ccfbc6f9fad` |
| `app/src/main/res/drawable/ic_acid_pause.xml` | app/src/main/assets/web/icons/pause.svg | 825 | `cfb151323a3b2d04f22c860767457b778ad2e961b9d179285463720bad2ddec0` |
| `app/src/main/res/drawable/ic_acid_settings.xml` | app/src/main/assets/web/icons/settings.svg | 1261 | `84590de32257ca7d46872ed4adb256c2e2b5b0fa6cb2c69020b1fac6d2744ebb` |
| `app/src/main/res/drawable/ic_acid_info.xml` | app/src/main/assets/web/icons/info.svg | 898 | `93ff2a30871d0f27e5e23dca62faf0f39d83550fcce2430cdf8f39a459ce91a9` |
| `app/src/main/res/drawable/ic_acid_arrow_up_right.xml` | app/src/main/assets/web/icons/arrow-up-right.svg | 675 | `2f13790d193211fdeabdfa1f9235a3bc2d3067fa4ceb48d2379192c48e34c978` |
| `app/src/main/res/drawable/ic_acid_arrow_down_left.xml` | app/src/main/assets/web/icons/arrow-down-left.svg | 675 | `cd6ade7090465dfb6cf03bbd8b0f630bf664c4f3338a6c806a7944db633ed563` |
| `app/src/main/res/drawable/ic_acid_power.xml` | app/src/main/assets/web/icons/power.svg | 691 | `8d2755d747c7b6784835fe6c0c1adcb5bca9a56434174dd21a827daccf6e1785` |
| `app/src/main/res/drawable/ic_acid_qr_code.xml` | app/src/main/assets/web/icons/qr-code.svg | 2816 | `51582bab5fb43258399c5af8b015beaeb39bd98b4fdcce07f79ce696728b3c62` |
| `app/src/main/res/drawable/ic_acid_check.xml` | app/src/main/assets/web/icons/check.svg | 490 | `709c3cdf0fd01338325c5e720d56c998f3a91a2cd45cfc624a7880ec71b4595c` |
| `app/src/main/res/drawable/ic_acid_folder_open.xml` | app/src/main/assets/web/icons/folder-open.svg | 652 | `a0f3380a6f8173723c6d02d9001801bb89f6a11fbe6b7766ac5108620097f591` |
| `app/src/main/res/drawable/ic_acid_send.xml` | app/src/main/assets/web/icons/send.svg | 808 | `88faa6726084f8ee9d57b6d3980478cb34b56e7e380fc49d4e6e1d09676e8c51` |
| `app/src/main/res/drawable/ic_acid_circle_alert.xml` | app/src/main/assets/web/icons/circle-alert.svg | 908 | `721c5b68ee6685631284676b5a856b897d607316263daeba6ecde4ace2cc8788` |
| `app/src/main/res/drawable/ic_acid_wifi.xml` | app/src/main/assets/web/icons/wifi.svg | 1100 | `65c6e8ced31e2157d31bc121339a8ae51a2488993fcd9020eda7b796d4cadd37` |
| `app/src/main/res/font/anton_regular.ttf` | https://raw.githubusercontent.com/google/fonts/main/ofl/anton/Anton-Regular.ttf | 170812 | `a4ba3a92350ebb031da0cb47630ac49eb265082ca1bc0450442f4a83ab947cab` |
| `app/src/main/assets/web/fonts/anton-regular.ttf` | Same unmodified font bytes as Android resource | 170812 | `a4ba3a92350ebb031da0cb47630ac49eb265082ca1bc0450442f4a83ab947cab` |
| `app/src/main/assets/licenses/Anton-OFL.txt` | https://raw.githubusercontent.com/google/fonts/main/ofl/anton/OFL.txt | 4484 | `ee67e6ee22790b7929f1a3769ca2801d565c64b5a9096942c1adf5596de9c9e4` |

未打包 Marathon、Kurppa Hosk 或其他产品的商业图像、标志和品牌素材。它们若用于研究，仅作为构图参考，与本表中的可再分发资产分开。

## 文件预览图标（ACID.04）

`assets/web/file-icons/` 中的 `documents-folder.svg`、`audio-eighth-note.svg`、`unknown-file.svg` 由用户提供，按原始字节直接打包。SVG 内保留 Phosphor Icons 的 MIT 许可与来源说明。网页使用原始 SVG，Android 使用 AndroidSVG 1.4 直接绘制矢量；不重绘、不改色、不转为位图资源。

AndroidSVG 来源：https://github.com/BigBadaboom/androidsvg ，Apache 2.0 许可随应用打包。

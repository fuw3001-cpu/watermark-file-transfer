# 第三方代码与许可

## parse-video

- 来源：https://github.com/wujunwei928/parse-video
- 拉取版本：`57590df94b49aafc827eb98e85c845eaa1cb3daf`
- 许可证：MIT，Copyright (c) 2022 wujunwei928。
- 本地完整克隆：`upstream/parse-video/`（忽略提交，未修改上游文件）。
- 提交到本项目的参考快照：`third_party/parse-video/douyin.go`、`redbook.go` 和 `LICENSE`。
- 安卓移植：`DouyinDocument.kt` 沿用公开分享页 `_ROUTER_DATA` 的作品数据映射，以及官方播放接口 `playwm` → `play` 的处理方式；其余安卓网络、界面和存储代码为本项目实现。
- 0.4.0 的 `XiaohongshuDocument.kt` 同时参考 `redbook.go` 的公开页面状态、视频流和图集映射。没有移植 Go HTTP 服务、其余平台或依赖 Cookie 的签名详情接口。上游功能列表不代表此安卓版本的支持范围。

MIT 完整许可保存在 `third_party/parse-video/LICENSE`，并打包进 App 的“关于”。

## media-parser

- 来源：https://github.com/ucmao/media-parser
- 拉取版本：`19496f8c253ee0c18a2e2e27e23267c76aecaa63`
- 许可证：MIT，Copyright (c) 2025-2026 ucmao。
- 参考快照：`third_party/media-parser/douyin_parser.py`、`xiaohongshu_parser.py` 及 `LICENSE`。
- 安卓移植：`DouyinResolver.fetchFeed` 参考 `_request_mobile_feed` 的两个平台移动端接口、参数和 User-Agent；`DouyinDocument.parseFeed` 仅匹配输入作品 ID，优先使用 H.264 播放资源。
- 0.4.0 小红书适配参考 `xiaohongshu_parser.py` 的桌面/移动请求、公开状态、原始视频 key、流类型排序及图片地址候选。未移植用户 Cookie、预置身份材料、签名器或任何服务端功能。MIT 完整许可保留并打包到“关于”。

## 安卓依赖

| 组件 | 许可证 | 来源 |
|---|---|---|
| AndroidX / Compose | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin / kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp / Okio | Apache-2.0 | https://github.com/square/okhttp / https://github.com/square/okio |
| Coil / coil-gif | Apache-2.0 | https://github.com/coil-kt/coil |
| jsoup | MIT | https://github.com/jhy/jsoup |
| Gradle Wrapper | Apache-2.0 | https://github.com/gradle/gradle |
| FFmpegKit Audio 8.1.9 / FFmpeg 与音频库 | LGPL-3.0 及各组件原始许可 | https://github.com/ffmpegkit-maintained/ffmpeg/tree/v8.1.9-lts-android |
| ClearPDF Kotlin JNI 绑定与集成参考 | MIT | https://github.com/Chethan616/ClearPDF |
| LibreOffice-Lite v2.0 引擎 | MPL-2.0 及组件原始许可 | https://github.com/vasuki-re/LibreOffice-Lite |
| PdfBox-Android 2.0.27.0 | Apache-2.0 | https://github.com/TomRoush/PdfBox-Android |
| Tesseract4Android 4.9.0 / Tesseract | Apache-2.0，及图像库原始许可 | https://github.com/adaptech-cz/Tesseract4Android |
| U2NETP 前景提取模型 | Apache-2.0 | https://github.com/xuebinqin/U-2-Net |
| ONNX Runtime Android 1.23.2 | MIT 及其第三方声明 | https://github.com/microsoft/onnxruntime/tree/v1.23.2 |
| tessdata_fast 中英文模型 | Apache-2.0 | https://github.com/tesseract-ocr/tessdata_fast |
| CameraX 1.5.2 / libyuv | Apache-2.0 / BSD-3-Clause | https://android.googlesource.com/platform/frameworks/support/ / https://chromium.googlesource.com/libyuv/libyuv/ |
| OpenCV Android 4.12.0 及其组件 | Apache-2.0 及各组件许可 | https://github.com/opencv/opencv/tree/4.12.0 |

本项目未接入第三方解析 API、广告 SDK、统计 SDK 或云端 AI 服务；0.3.0 随包提供 Apache-2.0 的离线中英文 OCR 模型。依赖版本以 `app/build.gradle.kts` 为准。完整许可及来源信息随 App 打包。

## FFmpegKit Audio

使用 `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9`，LGPL 版本，未修改上游库。对应包装/构建源码版本为 `v8.1.9-lts-android` / `469e560b89a9ba4cab357d2c306c676b0c611745`。AAR 的 arm64-v8a 与 x86_64 构建配置均启用 shared library 和 LAME；没有启用 GPL、x264 或 x265。MP4 使用内置 MPEG-4 编码，MP3 使用 LAME。

FFmpegKit/FFmpeg 及其组件的版权与完整许可保存在 App 的 `assets/licenses/ffmpegkit/` 和库自带 raw 资源中；在「关于」中可以查看。LGPL-3.0 及其引用的 GPL-3.0 全文均随包提供。对应源码、构建及替换库说明见 `third_party/ffmpegkit/README.md`。不得删除这些许可与源码可获取信息；本项目没有禁止为调试库修改而进行反向工程的条款。


## 0.7.0 离线抠图

U2NETP 使用原项目 Apache-2.0 许可，ONNX Runtime 1.23.2 使用 MIT 许可及其第三方声明。权重版本、分发地址与 SHA-256 见 third_party/matting/README.md，完整许可随「开源许可」提供。模板由本项目以几何绘图实现，自定义模板不随包分发。

## 0.9.0 自动扫描

CameraX 1.5.2 提供本地相机预览、对焦和拍摄，OpenCV Android 4.12.0 提供本地纸边检测。完整许可及原生组件声明保存在 `third_party/scanning/`，随 App 的「开源许可」提供；来源和固定版本见该目录 README。无需云端识别或 Google Play 扫描服务。

## 未打包的实况图与图片修复

小红书实况图的 `imageList[].livePhoto` / `stream.h264` 映射参考已有 MIT 快照 `third_party/parse-video/redbook.go`，沿用原版权声明。局部图片修复使用已有 OpenCV 4.12.0 的 Photo.inpaint，Apache-2.0 及其组件许可不变，未引入新的商业组件。Motion Photo 容器由本项目写入标准 XMP 和 MP4 尾部，格式参考 https://developer.android.com/media/platform/motion-photo-format 。

# 文档转换来源与许可

## ClearPDF / LibreOfficeKit

- Kotlin JNI 绑定原样来自 [ClearPDF](https://github.com/Chethan616/ClearPDF)，提交 `7403022186101c76d80aeaaf024393eb29b0d825`，MIT，Copyright (c) 2026 Chethan616。完整许可见本目录 `LICENSE`。
- 本项目的 `OfficeEngine.kt` 参考其 OfficeEngineRuntime、Service、Client 和 Relocation：在独立 `:office` 进程运行引擎，配置系统字体，使用绑定服务转换；已注明来源，保留许可。
- ARM64 引擎来自 [LibreOffice-Lite v2.0](https://github.com/vasuki-re/LibreOffice-Lite/releases/tag/v2.0)，对应上游 LibreOffice core；包下载地址和 SHA-256 固定在 `tools/prepare_documents.py`。本项目把引擎打包到 APK，不在手机端下载。
- 引擎原始下载文件 SHA-256：`6665ad47db41d116248c40e6fc608a3425b9eb4a304f5ad5e5d5c80dbea61e30`。
- 仅对库中 5 处 `/data/data/vasuki.istanpdf/files` 路径作等长替换，以 `/proc/self/fd/1023` 别名访问本 App 的私有资源目录。修改由随项目公开的 `tools/prepare_documents.py` 完整描述；不改变引擎算法。构建时使用同包 FFmpeg 提供的 NDK C++ 共享运行库。
- LibreOffice 本体 MPL-2.0，部分组件采用各自许可；官方源代码：[LibreOffice/core](https://github.com/LibreOffice/core) / https://git.libreoffice.org/core 。发行包构建材料：[LibreOffice-Lite](https://github.com/vasuki-re/LibreOffice-Lite)。完整 MPL、LGPL、组件许可页面及来源信息随 App「关于」提供。重新分发时须继续提供相应源码、修改材料与许可，不可删除声明。

## PDFBox / Tesseract

- [PdfBox-Android 2.0.27.0](https://github.com/TomRoush/PdfBox-Android)：Apache-2.0，用于文字及位置提取；不是商业 PDF 转 Word SDK。未启用证书签名功能，移除了不需要的 bcpkix/bcutil 依赖。
- [Tesseract4Android 4.9.0](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0)：Apache-2.0，包含 Tesseract 5.5.1、Leptonica、libjpeg 与 libpng，各自保留原始许可。使用 standard 单线程版本。
- [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd)：Apache-2.0。固定提交的英文和简体中文模型随包提供，首次使用复制到私有目录，无联网识别。
- 模型 SHA-256 由准备脚本固定校验；准备脚本下载的全部文件都不包含用户文档。

本实现不使用商业收费 SDK，也不调用云端文档或 OCR 服务。PDF→DOCX 的文本框/背景重建代码属于本项目实现；它不能保证复杂文档的版式保真，不能把尚未经过设备验收的输出质量视为已验证。

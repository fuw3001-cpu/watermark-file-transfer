# 离线自动扫描组件

- AndroidX CameraX 1.5.2：Apache-2.0，源码 https://android.googlesource.com/platform/frameworks/support/ 。CameraX 原生图像处理使用 libyuv，保留 BSD 许可。
- OpenCV Android 4.12.0：Apache-2.0，源码 https://github.com/opencv/opencv/tree/4.12.0 ，标签提交 `49486f61fb25722cbcf586b7f4320921d46fb38e`。使用 Maven Central 官方 AAR，未修改二进制。纸边检测、自动拍摄和图像处理均在设备本地完成，不依赖 OpenCV Manager 或 Google Play 扫描服务。
- 此目录收录 OpenCV 源码树的第三方许可及 ARM64 AAR 构建信息涉及的 ADE 0.1.2e、oneTBB 2022.1.0、ITTNotify、Carotene、KleidiCV 0.5.0 等声明。ITTNotify 使用其双许可中的 BSD-3-Clause 选项。ADE 源码 https://github.com/opencv/ade/tree/v0.1.2e ，oneTBB 源码 https://github.com/uxlfoundation/oneTBB/tree/v2022.1.0 ，KleidiCV 源码 https://gitlab.arm.com/kleidi/kleidicv/-/tree/0.5.0 。OpenCV Android AAR 未启用 FFmpeg。

`tools/prepare_documents.py` 将本目录的完整 `.txt` 声明复制到 `assets/licenses/scanning/`，可在应用「关于 → 开源许可」中查看。依赖由 Gradle 锁定版本，保留这些声明后分发。

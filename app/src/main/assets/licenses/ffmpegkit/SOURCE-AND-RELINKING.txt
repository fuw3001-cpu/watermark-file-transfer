# FFmpegKit Audio 8.1.9

- Maven 坐标：`dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9`。
- 许可：LGPL-3.0，加各个组件自身的原始许可；无需购买代码商业授权，但分发时必须履行相应许可义务。
- 对应包装和构建源码：[v8.1.9-lts-android](https://github.com/ffmpegkit-maintained/ffmpeg/tree/v8.1.9-lts-android)，提交 `469e560b89a9ba4cab357d2c306c676b0c611745`。
- 源码压缩包：https://github.com/ffmpegkit-maintained/ffmpeg/archive/refs/tags/v8.1.9-lts-android.tar.gz 。
- 本机已拉取的完整包装/构建仓库：`upstream/ffmpegkit/`（不纳入本仓库提交）。库未经本项目修改。
- FFmpeg 和外部库的精确源码仓库/版本由该版本的 `android-8.1-lts/scripts/source.sh` 指定，可通过脚本下载。注意项目根目录的构建说明涉及多个版本分支；8.1 的构建脚本与 CI 才是本依赖的依据。
- 本项目实际取得的 AAR： https://repo.maven.apache.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-audio/8.1.9/ffmpeg-kit-audio-8.1.9.aar 。校验记录和配置摘要保存在同目录。

## 许可和替换

LGPL、GPL（LGPL 引用的基础条款）以及 AAR 内各组件许可被原样保留在 App 的 `assets/licenses/ffmpegkit/` 中；「关于」展示这些文本。没有使用 `-gpl` 构建，也未使用 x264/x265。LAME 由 LGPL audio 包提供；MPEG-4 Part 2 编码器由 FFmpeg 自带。

库以动态链接的 `.so` 文件随 APK 分发。应用源码和完整 Gradle 工程在本仓库可获取：可以按上游该版本脚本在 Linux/macOS/WSL 中构建修改后的 AAR，把 AAR 放到本项目 `app/libs/`，将 `app/build.gradle.kts` 中该 Maven 依赖替换为 `implementation(files("libs/your-modified.aar"))`，然后按根目录 README 构建并使用你自己的调试/发行签名。避免同时保留两个提供同名 Java 类/原生库的依赖。

本项目不限制用户为调试 LGPL 库修改而进行反向工程。后续正式分发时应继续提供所用版本库的相应源码与构建材料、许可、版权信息和可重新组合的应用材料；仅标注“开源”不能代替这些要求。

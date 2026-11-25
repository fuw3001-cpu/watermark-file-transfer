package cn.shiying.local.conversion

enum class ConversionKind(val label: String, val pickerTypes: Array<String>) {
    IMAGE("图片互转", arrayOf("image/*")),
    VIDEO("视频转 MP4", arrayOf("video/*", "image/gif")),
    GIF("GIF 动图", arrayOf("video/*", "image/gif")),
    MEME("表情包制作", arrayOf("image/*")),
    AUDIO("提取 MP3", arrayOf("audio/*", "video/*")),
    DOCUMENT("文档互转", arrayOf("*/*")),
}

enum class OutputFormat(val label: String, val extension: String, val mime: String) {
    JPG("JPG", "jpg", "image/jpeg"), PNG("PNG", "png", "image/png"), WEBP("WebP", "webp", "image/webp"),
    GIF("GIF", "gif", "image/gif"),
    MP4("MP4", "mp4", "video/mp4"), MP3("MP3", "mp3", "audio/mpeg"),
    PDF("PDF", "pdf", "application/pdf"),
    DOCX("Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("CSV", "csv", "text/csv"),
}

enum class DocumentOperation(val label: String, val extensions: Set<String>, val output: OutputFormat, val explanation: String) {
    WORD_PDF("Word → PDF", setOf("doc", "docx", "odt", "rtf"), OutputFormat.PDF, "使用本地 Office 引擎排版。字体缺失可能导致换行或页数变化；暂不支持加密文档。"),
    PDF_WORD("PDF → Word（含扫描件）", setOf("pdf"), OutputFormat.DOCX, "优先读取原有文字；扫描页使用中英文离线 OCR。文字按位置放入可编辑文本框，图片和表格线条作为背景保留。最多 20 页；不还原为普通段落和可编辑表格单元格，公式、多栏和旋转文字可能偏移，识别结果需核对。"),
    EXCEL_PDF("Excel → PDF", setOf("xls", "xlsx", "ods"), OutputFormat.PDF, "按工作簿的打印区域和分页设置生成 PDF，宽表可能跨页。图表和字体的呈现取决于 Office 引擎。"),
    EXCEL_CSV("Excel → CSV", setOf("xls", "xlsx", "ods"), OutputFormat.CSV, "只导出当前工作表的值；CSV 不保留其他工作表、公式、图片和单元格样式。"),
    CSV_EXCEL("CSV → Excel", setOf("csv"), OutputFormat.XLSX, "适合 UTF-8、逗号分隔的 CSV。CSV 不含原始单元格格式，导入时可能推断日期或数字。"),
}

data class ConversionSpec(
    val kind: ConversionKind = ConversionKind.IMAGE,
    val imageFormat: OutputFormat = OutputFormat.JPG,
    val quality: Int = 90,
    val gifStartSeconds: Int = 0,
    val gifDurationSeconds: Int = 3,
    val gifFps: Int = 10,
    val documentOperation: DocumentOperation = DocumentOperation.WORD_PDF,
) {
    val outputFormat: OutputFormat get() = when (kind) {
        ConversionKind.IMAGE -> imageFormat.also { require(it in imageFormats) }
        ConversionKind.VIDEO -> OutputFormat.MP4
        ConversionKind.GIF -> OutputFormat.GIF
        ConversionKind.MEME -> OutputFormat.PNG
        ConversionKind.AUDIO -> OutputFormat.MP3
        ConversionKind.DOCUMENT -> documentOperation.output
    }
    fun validateGif() {
        require(gifStartSeconds in 0..3600) { "起始时间需在 0–3600 秒" }
        require(gifDurationSeconds in 1..10) { "动图时长需在 1–10 秒" }
        require(gifFps in 5..15) { "帧率需在 5–15 帧/秒" }
    }
    fun outputName(source: String, stamp: Long): String {
        val stem = source.substringBeforeLast('.', source).replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
            .trim().take(70).ifBlank { "文件" }
        return "${stem}_转换_${stamp}.${outputFormat.extension}"
    }
    companion object { val imageFormats = listOf(OutputFormat.JPG, OutputFormat.PNG, OutputFormat.WEBP) }
}

object ConversionCommands {
    fun probe(input: String) = arrayOf("-v", "error", "-protocol_whitelist", "file,pipe", "-show_format", "-show_streams", "-of", "json", input)

    fun remux(input: String, output: String) = arrayOf("-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-protocol_whitelist", "file,pipe", "-i", input, "-map", "0:V:0", "-map", "0:a:0?", "-sn", "-dn",
        "-c", "copy", "-tag:v", "avc1", "-map_metadata", "-1", "-movflags", "+faststart", output)

    /** Argument arrays, never shell commands or interpolated command strings. */
    fun encode(spec: ConversionSpec, input: String, output: String): Array<String> {
        if (spec.kind == ConversionKind.GIF) spec.validateGif()
        val common = listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-protocol_whitelist", "file,pipe") +
            (if (spec.kind == ConversionKind.GIF) listOf("-ss", spec.gifStartSeconds.toString()) else emptyList()) + listOf("-i", input)
        val options = when (spec.kind) {
            ConversionKind.VIDEO -> listOf("-map", "0:V:0", "-map", "0:a:0?", "-sn", "-dn",
                "-c:v", "mpeg4", "-q:v", "4", "-bf", "0", "-pix_fmt", "yuv420p",
                "-vf", "scale='max(2,trunc(iw/2)*2)':'max(2,trunc(ih/2)*2)'", "-threads", "2",
                "-c:a", "aac", "-b:a", "128k", "-map_metadata", "-1", "-movflags", "+faststart")
            ConversionKind.GIF -> listOf("-map", "0:V:0", "-an", "-sn", "-dn", "-t", spec.gifDurationSeconds.toString(),
                "-vf", "fps=${spec.gifFps},scale=w='min(480,iw)':h='min(480,ih)':force_original_aspect_ratio=decrease:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=single[p];[b][p]paletteuse=new=1:dither=bayer",
                "-filter_complex_threads", "1", "-filter_threads", "1", "-threads", "2", "-loop", "0", "-map_metadata", "-1")
            ConversionKind.MEME -> error("表情包使用本地图片绘制")
            ConversionKind.AUDIO -> listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-c:a", "libmp3lame",
                "-b:a", "192k", "-ar", "44100", "-ac", "2", "-map_metadata", "-1", "-id3v2_version", "3")
            ConversionKind.IMAGE -> error("图片使用 Android 原生编解码")
            ConversionKind.DOCUMENT -> error("文档使用本地文档引擎")
        }
        return (common + options + output).toTypedArray()
    }
}

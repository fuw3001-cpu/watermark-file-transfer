package cn.shiying.local.conversion

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConversionState(
    val spec: ConversionSpec = ConversionSpec(),
    val source: SelectedFile? = null,
    val busy: Boolean = false,
    val phase: String = "",
    val progress: Float? = null,
    val result: ConvertedFile? = null,
    val error: String? = null,
)

class ConversionViewModel(application: Application) : AndroidViewModel(application) {
    private val converter = FileConverter(application)
    private val mutable = MutableStateFlow(ConversionState())
    val state = mutable.asStateFlow()
    private var task: Job? = null

    fun kind(kind: ConversionKind) {
        if (!state.value.busy && state.value.spec.kind != kind) mutable.value = ConversionState(spec = ConversionSpec(kind))
    }
    fun imageFormat(format: OutputFormat) {
        if (!state.value.busy && state.value.spec.imageFormat != format) mutable.update { it.copy(spec = it.spec.copy(imageFormat = format), result = null, error = null) }
    }
    fun quality(value: Int) {
        if (!state.value.busy) mutable.update { it.copy(spec = it.spec.copy(quality = value), result = null, error = null) }
    }
    fun gifSettings(start: Int = state.value.spec.gifStartSeconds, duration: Int = state.value.spec.gifDurationSeconds,
        fps: Int = state.value.spec.gifFps) {
        if (!state.value.busy) mutable.update { it.copy(spec = it.spec.copy(gifStartSeconds = start, gifDurationSeconds = duration,
            gifFps = fps), result = null, error = null) }
    }
    fun documentOperation(operation: DocumentOperation) {
        if (!state.value.busy && operation != state.value.spec.documentOperation) mutable.update {
            val source = it.source?.takeIf { file -> file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in operation.extensions }
            ConversionState(spec = it.spec.copy(documentOperation = operation), source = source)
        }
    }
    fun select(uri: Uri) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, phase = "正在读取文件信息…", error = null) }
        task = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { converter.describe(uri) }
                mutable.update {
                    val extension = file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                    val operation = if (it.spec.kind == ConversionKind.DOCUMENT && extension !in it.spec.documentOperation.extensions)
                        DocumentOperation.entries.firstOrNull { option -> extension in option.extensions } ?: it.spec.documentOperation
                    else it.spec.documentOperation
                    it.copy(source = file, spec = it.spec.copy(documentOperation = operation), busy = false, phase = "", result = null)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                mutable.update { it.copy(busy = false, error = e.message ?: "无法读取文件，请重新选择") }
            }
        }
    }
    fun convert() {
        val source = state.value.source ?: return
        if (state.value.busy) return
        val spec = state.value.spec
        mutable.update { it.copy(busy = true, phase = "准备转换…", progress = null, error = null, result = null) }
        task = viewModelScope.launch {
            try {
                val result = converter.convert(source, spec) { phase, progress ->
                    mutable.update { if (it.busy) it.copy(phase = phase, progress = progress) else it }
                }
                mutable.update { it.copy(source = null, spec = it.spec.copy(gifStartSeconds = 0), busy = false, phase = "转换完成，输入已清空", result = result, progress = 1f) }
            } catch (e: CancellationException) { throw e
            } catch (_: OutOfMemoryError) {
                coroutineContext.ensureActive()
                mutable.update { it.copy(busy = false, error = "内存不足，请缩小图片或拆分文档后重试") }
            } catch (_: LinkageError) {
                coroutineContext.ensureActive()
                mutable.update { it.copy(busy = false, error = "此设备无法加载转换模块，目前支持 64 位安卓设备") }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                mutable.update { it.copy(busy = false, error = e.message ?: "转换失败，请检查文件和可用空间") }
            }
        }
    }
    fun cancel() {
        task?.cancel()
        mutable.update { it.copy(busy = false, phase = "已取消", progress = null, error = null) }
    }
}

package cn.shiying.local.documents

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.shiying.local.conversion.FileConverter
import cn.shiying.local.conversion.SelectedFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MergeState(
    val files: List<SelectedFile> = emptyList(), val name: String = "合并文档", val busy: Boolean = false,
    val phase: String = "", val progress: Float? = null, val error: String? = null, val result: MergedDocument? = null,
)
class MergeViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(MergeState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    fun rename(value: String) { if (!state.value.busy) mutable.update { it.copy(name = value.take(60), result = null) } }
    fun remove(index: Int) { if (!state.value.busy) mutable.update { it.copy(files = it.files.filterIndexed { i, _ -> i != index }, result = null, error = null) } }
    fun move(index: Int, delta: Int) { if (!state.value.busy) mutable.update { it.copy(files = PrintPages.move(it.files, index, delta), result = null, error = null) } }
    fun add(uris: List<Uri>) {
        if (state.value.busy || uris.isEmpty()) return
        val existing = state.value.files
        val fresh = uris.distinct().filter { uri -> existing.none { it.uri == uri } }
        if (fresh.isEmpty()) return
        mutable.update { it.copy(busy = true, phase = "正在读取文件信息…", progress = null, error = null, result = null) }
        task = viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    require(existing.size + fresh.size <= 20) { "一次最多添加 20 个文件" }
                    fresh.map { uri ->
                        ensureActive()
                        FileConverter(getApplication()).describe(uri).also { file ->
                            require(file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in DocumentMerger.supported) { "不支持 ${file.name}；请选择 PDF、Word 或 Excel 文档" }
                            require(file.size == null || file.size <= 64L * 1024 * 1024) { "${file.name} 超过 64 MB" }
                        }
                    }.also { added -> require((existing + added).sumOf { it.size ?: 0L } <= 256L * 1024 * 1024) { "输入文档总计超过 256 MB，请减少文件" } }
                }
                mutable.update { it.copy(busy = false, files = existing + files, phase = "") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { coroutineContext.ensureActive(); mutable.update { it.copy(busy = false, error = e.message ?: "无法读取文件") } }
        }
    }
    fun merge() {
        if (state.value.busy || state.value.files.isEmpty()) return
        val files = state.value.files
        val name = state.value.name
        mutable.update { it.copy(busy = true, phase = "准备合并…", progress = null, error = null, result = null) }
        task = viewModelScope.launch {
            try {
                val result = DocumentMerger(getApplication()).merge(files, name) { phase, progress -> mutable.update { it.copy(phase = phase, progress = progress) } }
                mutable.update { MergeState(phase = "合并完成", progress = 1f, result = result) }
            } catch (e: CancellationException) { throw e
            } catch (_: OutOfMemoryError) { coroutineContext.ensureActive(); mutable.update { it.copy(busy = false, error = "内存不足，请减少文件或页数") }
            } catch (_: LinkageError) { coroutineContext.ensureActive(); mutable.update { it.copy(busy = false, error = "设备无法加载 Office 引擎；混合 Word/Excel 需要 ARM64 手机") }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                val message = if (e is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) "PDF 已加密，请先解除密码保护" else e.message ?: "合并失败，请检查文件是否完整"
                mutable.update { it.copy(busy = false, error = message) }
            }
        }
    }
    fun cancel() { task?.cancel(); mutable.update { it.copy(busy = false, phase = "已取消", progress = null, error = null) } }
}

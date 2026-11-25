package cn.shiying.local.meme

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.shiying.local.conversion.ConvertedFile
import cn.shiying.local.conversion.FileConverter
import cn.shiying.local.conversion.OutputFormat
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MemeState(val project: MemeProject = MemeProject(), val original: Bitmap? = null,
    val cutout: Bitmap? = null, val customTemplate: Bitmap? = null, val busy: Boolean = false,
    val phase: String = "", val error: String? = null, val selectedText: Int? = null,
    val result: ConvertedFile? = null, val advice: TemplateAdvice? = null) {
    val subject get() = if (project.useCutout) cutout else original
    val canExport get() = subject != null || project.texts.any { it.text.isNotBlank() }

    fun layoutFor(value: MemeTemplate): MemeProject {
        val transform = advice?.fit(value, project.useCutout, project.mirrorSubject) ?: value.subject
        return project.copy(template = value, subject = transform)
    }
}

class MemeViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(MemeState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    private var nextText = 2

    fun source(uri: Uri) = loadSource(uri, sample = false)

    fun trySample() {
        val app = getApplication<Application>()
        loadSource(Uri.parse("android.resource://${app.packageName}/${cn.shiying.local.R.drawable.studio_cat}"), sample = true)
    }

    private fun loadSource(uri: Uri, sample: Boolean) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, phase = "正在本地抠图…", error = null, result = null) }
        task = viewModelScope.launch {
            try {
                val original = withContext(Dispatchers.IO) { MemeCanvas.decode(getApplication(), uri) }
                val cutout = try { withContext(Dispatchers.Default) { SubjectCutout.create(getApplication(), original) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: LinkageError) {
                        mutable.update { it.copy(error = "此设备无法加载抠图组件，已使用原图。") }; null
                    }
                    catch (e: Exception) {
                        mutable.update { it.copy(error = "抠图未完成，当前保留原图。${e.message.orEmpty()}") }; null
                    }
                mutable.update { it.copy(phase = "正在匹配模板…") }
                val advice = withContext(Dispatchers.Default) { TemplateMatcher.analyze(original, cutout) }
                mutable.update { current ->
                    val useCutout = cutout != null && (current.original == null || current.project.useCutout)
                    val project = current.project
                    val firstPlacement = current.original == null && project.subject == project.template.subject
                    val template = if (sample && project == MemeProject()) MemeTemplate.SNAPSHOT else project.template
                    val placement = if (firstPlacement && (template != project.template || template == MemeTemplate.PANDA && advice.face != null))
                        advice.fit(template, useCutout, project.mirrorSubject) else project.subject
                    current.copy(original = original, cutout = cutout, advice = advice, busy = false, phase = "",
                        project = project.copy(template = template, useCutout = useCutout, subject = placement), selectedText = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { mutable.update { it.copy(busy = false, error = "内存不足，请缩小素材后再试") } }
            catch (_: LinkageError) { mutable.update { it.copy(busy = false, error = "此设备无法加载本地抠图模块") } }
            catch (e: Exception) { mutable.update { it.copy(busy = false, error = e.message ?: "无法读取素材") } }
        }
    }

    fun customTemplate(uri: Uri) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, phase = "正在读取模板…", error = null, result = null) }
        task = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { MemeCanvas.decode(getApplication(), uri) }
                mutable.update { it.copy(customTemplate = bitmap, project = it.project.copy(template = MemeTemplate.CUSTOM,
                    subject = MemeTemplate.CUSTOM.subject), busy = false, phase = "", selectedText = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { mutable.update { it.copy(busy = false, error = "模板过大，请先缩小图片") } }
            catch (e: Exception) { mutable.update { it.copy(busy = false, error = e.message ?: "无法读取模板") } }
        }
    }

    private fun edit(change: (MemeState) -> MemeState) {
        if (!state.value.busy) mutable.update { change(it).copy(result = null, error = null) }
    }
    fun template(value: MemeTemplate) = edit { it.copy(project = it.layoutFor(value)) }
    fun useCutout(value: Boolean) = edit { it.copy(project = it.project.copy(useCutout = value && it.cutout != null)) }
    fun mirrorSubject() = edit { it.copy(project = it.project.copy(mirrorSubject = !it.project.mirrorSubject)) }
    fun subjectScale(value: Float) = edit { state -> state.copy(project = state.project.copy(
        subject = state.project.subject.copy(scale = value.coerceIn(.15f, 8f)))) }
    fun select(id: Int?) {
        if (!state.value.busy) mutable.update { state ->
            state.copy(selectedText = id?.takeIf { target -> state.project.texts.any { it.id == target } })
        }
    }
    fun text(value: String) = edit { state -> state.copy(project = state.project.copy(texts = state.project.texts.map {
        if (it.id == state.selectedText) it.copy(text = value.take(80)) else it
    })) }
    fun ink(value: TextInk) = edit { state -> state.copy(project = state.project.copy(texts = state.project.texts.map {
        if (it.id == state.selectedText) it.copy(ink = value) else it
    })) }
    fun textSize(value: Float) = edit { state -> state.copy(project = state.project.copy(texts = state.project.texts.map {
        if (it.id == state.selectedText) it.copy(size = value.coerceIn(14f, 96f)) else it
    })) }
    fun textPosition(y: Float) = edit { state -> state.copy(project = state.project.copy(texts = state.project.texts.map {
        if (it.id == state.selectedText) it.copy(x = .5f, y = y.coerceIn(0f, 1f)) else it
    })) }
    fun duplicateText() = edit { state ->
        val selected = state.project.texts.firstOrNull { it.id == state.selectedText }
        if (selected == null || state.project.texts.size >= 5) state else {
            val copy = selected.copy(id = nextText++, y = if (selected.y > .8f) selected.y - .1f else selected.y + .1f)
            state.copy(project = state.project.copy(texts = state.project.texts + copy), selectedText = copy.id)
        }
    }
    fun addText() = edit {
        if (it.project.texts.size >= 5) it else {
            val id = nextText++
            it.copy(project = it.project.copy(texts = it.project.texts + MemeText(id, y = .25f + it.project.texts.size * .1f)), selectedText = id)
        }
    }
    fun removeText() = edit { state ->
        val remaining = state.project.texts.filterNot { it.id == state.selectedText }
        state.copy(project = state.project.copy(texts = remaining), selectedText = remaining.firstOrNull()?.id)
    }
    fun transform(dx: Float, dy: Float, zoom: Float = 1f, anchorX: Float? = null, anchorY: Float? = null) = edit { state ->
        if (state.selectedText == null) state.copy(project = state.project.copy(subject = state.project.subject.changed(dx, dy, zoom, anchorX, anchorY)))
        else state.copy(project = state.project.copy(texts = state.project.texts.map {
            if (it.id == state.selectedText) it.changed(dx, dy, zoom) else it
        }))
    }
    fun tap(x: Float, y: Float) {
        if (state.value.busy) return
        select(state.value.project.texts.asReversed().firstOrNull { it.text.isNotBlank() && MemeCanvas.textBounds(it).contains(x*512, y*512) }?.id)
    }
    fun resetPosition() = edit { state ->
        if (state.selectedText == null) state.copy(project = state.project.copy(subject = state.project.template.subject))
        else state.copy(project = state.project.copy(texts = state.project.texts.map {
            if (it.id == state.selectedText) it.copy(x = .5f, y = .84f, size = 34f) else it
        }))
    }

    fun startNext() {
        mutable.update { if (it.result != null) it.copy(result = null, phase = "") else it }
    }

    fun save() {
        val snapshot = state.value
        if (snapshot.busy) return
        if (!snapshot.canExport) { mutable.update { it.copy(error = "请添加头像或输入配文") }; return }
        if (snapshot.project.template == MemeTemplate.CUSTOM && snapshot.customTemplate == null) {
            mutable.update { it.copy(error = "请先导入自定义模板") }; return
        }
        mutable.update { it.copy(busy = true, phase = "正在保存表情包…", error = null) }
        task = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val output = File.createTempFile("meme-", ".png", getApplication<Application>().cacheDir)
                    try {
                        val bitmap = MemeCanvas.render(snapshot.project, snapshot.subject, snapshot.customTemplate)
                        try { output.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                        finally { bitmap.recycle() }
                        ensureActive()
                        FileConverter(getApplication()).publish(output, "拾影_表情包_${System.currentTimeMillis()}.png", OutputFormat.PNG)
                    } finally { output.delete() }
                }
                nextText = 2
                mutable.value = MemeState(phase = "已保存到相册", result = result)
            } catch (e: CancellationException) { throw e }
            catch (_: OutOfMemoryError) { mutable.update { it.copy(busy = false, error = "内存不足，请稍后重试") } }
            catch (e: Exception) { mutable.update { it.copy(busy = false, error = e.message ?: "保存失败") } }
        }
    }
    fun cancel() { task?.cancel(); mutable.update { it.copy(busy = false, phase = "已取消", error = null) } }
}

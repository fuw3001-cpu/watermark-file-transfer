package cn.shiying.local.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.shiying.local.documents.MergeViewModel
import cn.shiying.local.documents.PdfPrinter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MergePrintScreen(onBack: () -> Unit, model: MergeViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var actionError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.add(it) }
    BackHandler { if (state.busy) model.cancel() else onBack() }
    ToolPage(Tool.MERGE, onBack, !state.busy) {
        ToolAction(Tool.MERGE, "添加文档（${state.files.size}/20）", "多选 PDF、Word 或 Excel，合成一个 PDF",
            !state.busy && state.files.size < 20) { actionError = null; picker.launch(arrayOf("*/*")) }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StudioSection(if (state.files.isEmpty()) "输出文件" else "文档顺序")
                state.files.forEachIndexed { index, file ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}. ${file.name}", style = MaterialTheme.typography.bodyLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !state.busy && index > 0, onClick = { model.move(index, -1) }) { Text("上移") }
                            TextButton(enabled = !state.busy && index < state.files.lastIndex, onClick = { model.move(index, 1) }) { Text("下移") }
                            TextButton(enabled = !state.busy, onClick = { model.remove(index) }) { Text("移除") }
                        }
                        HorizontalDivider()
                    }
                }
                OutlinedTextField(value = state.name, onValueChange = model::rename, enabled = !state.busy,
                    label = { Text("合并文件名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !state.busy && state.files.isNotEmpty(), onClick = { actionError = null; model.merge() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (state.busy) "正在处理…" else if (state.files.size == 1) "生成并保存 PDF" else "合并并保存 PDF")
                }
                if (state.busy) {
                    Text(state.phase)
                    state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                        ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = model::cancel, modifier = Modifier.fillMaxWidth()) { Text("取消处理") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.busy && state.phase == "已取消") Text("已取消，原文件保留。")
            }
        }
        state.result?.let { result ->
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("已生成 ${result.pages} 页 PDF", style = MaterialTheme.typography.titleLarge)
                    Text("${result.documents} 个文档 · ${result.file.name}")
                    Text("已保存到 Downloads/Shiying", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        actionError = null
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(result.file.uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            .onFailure { actionError = "文件已保存，但设备没有 PDF 查看应用，可从文件管理器打开。" }
                    }, modifier = Modifier.fillMaxWidth()) { Text("打开合并文件") }
                    Button(onClick = {
                        actionError = null
                        runCatching { PdfPrinter.open(context, result.file.uri, result.file.name) }
                            .onFailure { actionError = it.message ?: "无法打开系统打印，请检查打印服务" }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("打印合并文件") }
                    Text("在系统面板选择打印机、页码、份数、纸张或另存为 PDF；双面等选项取决于打印机和打印服务。", style = MaterialTheme.typography.bodySmall)
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Text("最多 20 个文件，每个不超过 64 MB、总计 256 MB，合并后最多 500 页。PDF 保留原页面尺寸，Word/Excel 先由本地 Office 引擎转 PDF，字体和分页可能变化。混合 Word/Excel 需要 ARM64 手机；暂不支持加密文件。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

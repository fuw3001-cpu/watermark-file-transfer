package cn.shiying.local.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.shiying.local.scan.ScanPage
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ScanPageOrganizer(pages: List<ScanPage>, selected: String?, enabled: Boolean,
                              onClose: () -> Unit, onOpen: (String) -> Unit, onMove: (String, Int) -> Unit,
                              onDelete: (String) -> Unit, onNew: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(selected) }
    val index = pages.indexOfFirst { it.id == chosen }.coerceAtLeast(0)
    val id = pages[index].id
    var moving by rememberSaveable(id) { mutableStateOf(false) }
    var feedback by rememberSaveable(id) { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (moving) "移动第 ${index + 1} 页" else "页面总览", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                if (!moving) TextButton(enabled = enabled, onClick = onNew) { Text("新建扫描") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
            Text(if (moving) "点选目标页码，这一页将排到该位置。" else feedback.ifEmpty { "共 ${pages.size} 页 · 已选第 ${index + 1} 页" },
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
            LazyVerticalGrid(columns = GridCells.Adaptive(90.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(pages, key = { _, page -> page.id }) { ordinal, page ->
                    val active = page.id == id
                    Surface(selected = active, enabled = enabled && (!moving || !active), onClick = {
                        if (moving) {
                            onMove(id, ordinal)
                            moving = false
                            feedback = "已移至第 ${ordinal + 1} 页，共 ${pages.size} 页"
                        } else {
                            chosen = page.id
                            feedback = ""
                        }
                    }, shape = MaterialTheme.shapes.medium,
                        color = if (active) colors.primaryContainer else colors.surfaceContainer,
                        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) colors.primary else colors.outlineVariant)) {
                        Column(Modifier.padding(6.dp)) {
                            AsyncImage(model = page.source, contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1.1f).background(colors.surfaceVariant),
                                contentScale = ContentScale.Fit)
                            Row(Modifier.fillMaxWidth().padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (moving) "移至第 ${ordinal + 1} 页" else "第 ${ordinal + 1} 页", Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge)
                                if (active) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = colors.primary)
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (moving) OutlinedButton(onClick = { moving = false }) { Text("取消移动") }
                else {
                    Button(enabled = enabled, onClick = { onOpen(id) }) { Text("打开此页") }
                    OutlinedButton(enabled = enabled && pages.size > 1, onClick = { moving = true }) { Text("移动到…") }
                    TextButton(enabled = enabled, onClick = { onDelete(id) },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) { Text("删除") }
                }
            }
        }
    }
}

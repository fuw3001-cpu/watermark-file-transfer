package cn.shiying.local.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cn.shiying.local.meme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemplateGallery(state: MemeState, filter: String, onFilter: (String) -> Unit, choose: (MemeTemplate) -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("meme-templates", Context.MODE_PRIVATE) }
    var favorites by remember(prefs) { mutableStateOf(prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    val all = MemeTemplate.entries.filter { it != MemeTemplate.CUSTOM || state.customTemplate != null }
    val recommended = state.advice?.choices.orEmpty()
    val active = if (filter == "推荐" && recommended.isEmpty()) "全部" else filter
    val choices = when (active) {
        "收藏" -> all.filter { it.name in favorites }
        "推荐" -> recommended
        else -> all
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("推荐", "全部", "收藏").filter { it != "推荐" || recommended.isNotEmpty() }.forEach { name ->
            FilterChip(selected = name == active, onClick = { onFilter(name) }, label = { Text(name) })
        }
    }
    if (active == "推荐") Text(state.advice?.reason.orEmpty(), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (choices.isEmpty()) {
        Text("点模板右上角的爱心，就能在这里找到它。", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onFilter("全部") }) { Text("浏览全部模板") }
    } else BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / (125.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 4)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in choices.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { template ->
                    val selected = template == state.project.template
                    val colors = MaterialTheme.colorScheme
                    Box(Modifier.weight(1f)) {
                        Surface(selected = selected, onClick = { if (!selected) choose(template) }, enabled = !state.busy,
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) colors.primaryContainer else colors.surfaceContainer,
                            border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(6.dp)) {
                                val sample = if (selected) state.project else state.layoutFor(template)
                                Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small)) {
                                    val canvas = drawContext.canvas.nativeCanvas
                                    canvas.save()
                                    canvas.scale(size.width / 512f, size.height / 512f)
                                    MemeCanvas.draw(canvas, sample, state.subject, state.customTemplate)
                                    canvas.restore()
                                }
                                Row(Modifier.fillMaxWidth().padding(start = 3.dp, end = 3.dp, top = 7.dp, bottom = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(template.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                    if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = colors.primary)
                                }
                            }
                        }
                        if (template != MemeTemplate.CUSTOM) {
                            val liked = template.name in favorites
                            IconToggleButton(checked = liked, onCheckedChange = { checked ->
                                favorites = if (checked) favorites + template.name else favorites - template.name
                                prefs.edit().putStringSet("favorites", favorites).apply()
                            }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                                Box(Modifier.size(30.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                        if (liked) "取消收藏${template.label}" else "收藏${template.label}",
                                        Modifier.size(18.dp), tint = if (liked) colors.primary else colors.onSurface)
                                }
                            }
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

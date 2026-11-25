package cn.shiying.local.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.shiying.local.R

internal enum class Tool(val title: String, val detail: String, @DrawableRes val icon: Int) {
    CONVERT("文件转换", "图片、音视频、GIF 与办公文档", R.drawable.ic_convert),
    REPAIR("图片去水印", "涂抹并修复局部水印", R.drawable.ic_repair),
    MEME("表情包工作台", "替换头像、文字与模板", R.drawable.ic_meme),
    SCAN("文档扫描", "校正纸边，识别文字", R.drawable.ic_scan),
    MERGE("文档合并与打印", "排列文档，合成一个 PDF", R.drawable.ic_merge)
}

@Composable
private fun Tool.colors(): Pair<Color, Color> = with(MaterialTheme.colorScheme) {
    when (this@colors) {
        Tool.REPAIR, Tool.SCAN -> primaryContainer to onPrimaryContainer
        Tool.MEME -> tertiaryContainer to onTertiaryContainer
        Tool.CONVERT, Tool.MERGE -> secondaryContainer to onSecondaryContainer
    }
}

@Composable
internal fun ToolGlyph(tool: Tool, modifier: Modifier = Modifier) {
    val (paper, ink) = tool.colors()
    Box(modifier.size(44.dp).background(paper, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(tool.icon), null, Modifier.size(28.dp), tint = ink)
    }
}

@Composable
internal fun StudioSection(title: String) {
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun StudioTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit,
                       modifier: Modifier = Modifier, enabled: (Int) -> Boolean = { true },
                       icons: List<Int> = emptyList()) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().background(colors.surfaceContainer, MaterialTheme.shapes.medium)
        .selectableGroup().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val available = enabled(index)
            val ink = when {
                !available -> colors.onSurface.copy(alpha = .38f)
                active -> colors.onPrimary
                else -> colors.onSurfaceVariant
            }
            Row(Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                .background(if (active) colors.primary else Color.Transparent)
                .selectable(selected = active, enabled = available, role = Role.Tab, onClick = { onSelect(index) })
                .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                icons.getOrNull(index)?.let { Icon(painterResource(it), null, Modifier.size(18.dp), tint = ink) }
                Text(label, color = ink, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun ToolHeader(tool: Tool, onBack: () -> Unit, enabled: Boolean,
                        actions: @Composable RowScope.() -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.background).windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = enabled) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Icon(painterResource(tool.icon), null, Modifier.padding(start = 2.dp, end = 8.dp).size(23.dp), tint = colors.primary)
            Text(tool.title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            actions()
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
internal fun ToolPage(tool: Tool, onBack: () -> Unit, enabled: Boolean,
                      actions: @Composable RowScope.() -> Unit = {},
                      scrollState: ScrollState = rememberScrollState(),
                      content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { ToolHeader(tool, onBack, enabled, actions) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
internal fun ToolAction(tool: Tool, action: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    val (paper, ink) = tool.colors()
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.large,
        color = paper, contentColor = ink) {
        Row(Modifier.fillMaxWidth().heightIn(min = 94.dp).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(tool.icon), null, Modifier.size(39.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(action, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun ToolCover(tool: Tool, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(0.dp)), contentAlignment = Alignment.Center) {
        when (tool) {
            Tool.REPAIR -> Image(painterResource(R.drawable.repair_sample), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alignment = Alignment.CenterStart)
            Tool.MEME -> Box(Modifier.fillMaxSize().previewMat(Color(0xFFECD1BB), Color(0xFFD2AC91)),
                contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight(.92f).aspectRatio(1f).rotate(-7f)
                    .background(Color(0xFFFFF9E8)).padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 7.dp)
                    .background(Color(0xFFC3D5BD)), contentAlignment = Alignment.BottomCenter) {
                    Image(painterResource(R.drawable.studio_cat), null, Modifier.fillMaxHeight(.96f))
                }
            }
            Tool.SCAN -> Box(Modifier.fillMaxSize().previewMat(Color(0xFF24493B), Color(0xFF375E4D)),
                contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.scan_guide), null, Modifier.fillMaxHeight(.90f).aspectRatio(1.12f))
            }
            else -> ToolGlyph(tool)
        }
    }
}

@Composable
private fun FeatureTile(tool: Tool, modifier: Modifier, open: (Tool) -> Unit) {
    Surface(onClick = { open(tool) }, modifier = modifier, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            ToolCover(tool, Modifier.fillMaxWidth().height(98.dp))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tool.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp))
                }
                Text(tool.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ToolDirectory(open: (Tool) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val inline = maxWidth >= 560.dp * scale
        val tilesFit = maxWidth >= 262.dp * scale
        val images: @Composable ColumnScope.() -> Unit = {
            StudioSection("图片编辑")
            if (tilesFit && !inline) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureTile(Tool.REPAIR, Modifier.weight(1f), open)
                    FeatureTile(Tool.MEME, Modifier.weight(1f), open)
                }
            } else {
                FeatureTile(Tool.REPAIR, Modifier.fillMaxWidth(), open)
                FeatureTile(Tool.MEME, Modifier.fillMaxWidth(), open)
            }
        }
        val documents: @Composable ColumnScope.() -> Unit = {
            StudioSection("文件与文档")
            Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column {
                    listOf(Tool.CONVERT, Tool.SCAN, Tool.MERGE).forEachIndexed { index, tool ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp))
                        Surface(onClick = { open(tool) }) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                ToolGlyph(tool)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(tool.title, style = MaterialTheme.typography.titleMedium)
                                    Text(tool.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        if (inline) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(.87f), verticalArrangement = Arrangement.spacedBy(10.dp), content = images)
                Column(Modifier.weight(1.13f), verticalArrangement = Arrangement.spacedBy(10.dp), content = documents)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = images)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = documents)
            }
        }
    }
}

// Printed registration dots give the editor a visible boundary around white and transparent images.
internal fun Modifier.previewMat(ground: Color, mark: Color): Modifier = background(ground).drawBehind {
    val step = 12.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(mark, .8.dp.toPx(), Offset(x, y))
            x += step
        }
        y += step
    }
}

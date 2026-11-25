package cn.shiying.local.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.shiying.local.AppState
import cn.shiying.local.parser.MediaInfo
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryPreview(
    info: MediaInfo,
    initialIndex: Int,
    state: AppState,
    onSave: (Int) -> Unit,
    onCancel: () -> Unit,
    onSaveLiveVideo: (Int) -> Unit,
    onSaveLiveGif: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val maxControlsHeight = LocalConfiguration.current.screenHeightDp.dp * .52f
    val pager = rememberPagerState(initialPage = initialIndex, pageCount = { info.images.size })
    val index = pager.currentPage
    val saved = index in state.savedImages
    var livePreview by remember { mutableStateOf<Int?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${index + 1} / ${info.images.size}")
                    TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                    val image = info.images[page]
                    var candidate by remember(image) { mutableIntStateOf(0) }
                    val urls = remember(image) { image.imageUrls }
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context).data(urls.getOrNull(candidate))
                            .setHeader("User-Agent", info.userAgent).setHeader("Referer", info.platform.referer).build(),
                        contentDescription = "第 ${page + 1} 张图片，共 ${info.images.size} 张",
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
                        error = {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (image.isLive) "封面未能显示，可播放实况" else "图片加载失败", color = Color.White)
                                if(candidate<urls.lastIndex)TextButton(onClick={ candidate++ }) { Text("尝试备用图片") }
                            }
                        },
                        success = { SubcomposeAsyncImageContent() },
                    )
                }
                Column(Modifier.fillMaxWidth().heightIn(max = maxControlsHeight).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if(info.images[index].isLive) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={ livePreview=index }) { Text("播放实况",color=Color.White) }
                            OutlinedButton(onClick={ onSaveLiveVideo(index) },enabled=!state.saving && index !in state.savedLiveVideos) {
                                Text(if(index in state.savedLiveVideos) "视频已保存" else "保存实况视频",color=Color.White)
                            }
                            OutlinedButton(onClick = { onSaveLiveGif(index) }, enabled = !state.saving && index !in state.savedLiveGifs) {
                                Text(if (index in state.savedLiveGifs) "GIF 已保存" else "另存 GIF", color = Color.White)
                            }
                        }
                        Text("GIF 最长 10 秒、无声。保存实况视频可保留完整片段。", style = MaterialTheme.typography.bodySmall)
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.saving) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.savingImageIndex == null) "正在保存图集…" else "正在保存第 ${state.savingImageIndex + 1} 张…")
                    }
                    Button(onClick = { onSave(index) }, enabled = !state.busy && !state.saving && !saved,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (saved) "已保存到相册" else if(info.images[index].isLive) "保存实况图" else if (info.images[index].animated) "保存原动图" else "保存这张图片")
                    }
                    if (state.saving) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消保存", color = Color.White) }
                    Text("左右滑动查看图片", style = MaterialTheme.typography.bodySmall, color = Color.LightGray,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
    livePreview?.let { page -> VideoPreview(info.copy(videoUrl=info.images[page].motionUrl,images=emptyList()),onDismiss={ livePreview=null }) }
}

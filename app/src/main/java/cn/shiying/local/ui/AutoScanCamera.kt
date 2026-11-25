package cn.shiying.local.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.shiying.local.scan.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import com.google.common.util.concurrent.ListenableFuture

private class ScanCameraFocus {
    var future:ListenableFuture<FocusMeteringResult>?=null
    var quad:ScanQuad?=null
    var requestedAt=0L
    var absentAt:Long?=null
}

@Composable
fun AutoScanCamera(context:Context,pageCount:Int,canScan:Boolean,newFile:()->File,importError:String?,onCaptured:(File)->Unit,onBack:()->Unit) {
    val lifecycle=LocalLifecycleOwner.current
    val view = LocalView.current
    DisposableEffect(view, context) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        val bars = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars ?: false
        val lightNav = bars?.isAppearanceLightNavigationBars ?: false
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            bars?.isAppearanceLightStatusBars = lightStatus
            bars?.isAppearanceLightNavigationBars = lightNav
        }
    }
    val main=remember(context) { ContextCompat.getMainExecutor(context) }
    val preview=remember(context) { PreviewView(context).apply { scaleType=PreviewView.ScaleType.FIT_CENTER } }
    val analyzer=remember { Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"scan-camera-analysis") } }
    val gate=remember { AutoCaptureGate() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var frame by remember { mutableStateOf<DetectedPaper?>(null) }
    var decision by remember { mutableStateOf(CaptureDecision()) }
    var error by remember { mutableStateOf<String?>(null) }
    var shooting by remember { mutableStateOf(false) }
    var automatic by rememberSaveable { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    val alive=remember { AtomicBoolean(true) }
    val captureLock=remember { AtomicBoolean(false) }
    val pendingFile=remember { AtomicReference<File?>(null) }
    val handler=remember { Handler(Looper.getMainLooper()) }
    val focusing=remember { ScanCameraFocus() }
    val currentCanScan by rememberUpdatedState(canScan)
    val currentAutomatic by rememberUpdatedState(automatic)
    val currentNewFile by rememberUpdatedState(newFile)
    val currentCaptured by rememberUpdatedState(onCaptured)

    // Focus while the stability timer is running; reuse it at the shutter instead of restarting AF.
    fun focusPaper(detected:DetectedPaper?):ListenableFuture<FocusMeteringResult>? {
        val device=camera ?: return null
        val now=SystemClock.elapsedRealtime()
        val quad=detected?.quad ?: ScanQuad()
        val moved=focusing.quad?.points?.zip(quad.points)?.any { (a,b) -> kotlin.math.hypot(a.x-b.x,a.y-b.y)>.06f } ?: true
        if(focusing.future!=null && now-focusing.requestedAt<2500 && (!moved || now-focusing.requestedAt<800)) return focusing.future
        return try {
            val factor=detected?.let { minOf(preview.width.toFloat()/it.frameWidth,preview.height.toFloat()/it.frameHeight) }
            val px=if(detected!=null && factor!=null) (preview.width-detected.frameWidth*factor)/2+quad.points.map { it.x }.average().toFloat()*detected.frameWidth*factor else preview.width/2f
            val py=if(detected!=null && factor!=null) (preview.height-detected.frameHeight*factor)/2+quad.points.map { it.y }.average().toFloat()*detected.frameHeight*factor else preview.height/2f
            val point=preview.meteringPointFactory.createPoint(px,py)
            device.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point,FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3,TimeUnit.SECONDS).build()).also {
                focusing.future=it;focusing.quad=quad;focusing.requestedAt=now
            }
        } catch(_:Exception) { null }
    }

    fun takePhoto() {
        val device=camera;val useCase=capture
        if(device==null || useCase==null || !currentCanScan || !alive.get() || !captureLock.compareAndSet(false,true)) return
        shooting=true;error=null;gate.captured()
        val file=try { currentNewFile() } catch(e:Exception) {
            shooting=false;captureLock.set(false);gate.retry();error=e.message ?: "无法创建拍照文件";return
        }
        pendingFile.set(file)
        val started=AtomicBoolean(false)
        fun shoot() {
            if(!alive.get() || !started.compareAndSet(false,true)) return
            useCase.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),main,object:ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                    captureLock.set(false);pendingFile.compareAndSet(file,null)
                    if(!alive.get()) { file.delete();return }
                    shooting=false
                    currentCaptured(file)
                }
                override fun onError(exception:ImageCaptureException) {
                    file.delete();pendingFile.compareAndSet(file,null);captureLock.set(false)
                    if(alive.get()) { shooting=false;automatic=false;gate.retry();error="拍摄失败，请重试或使用系统相机" }
                }
            })
        }
        try {
            val focus=focusPaper(frame)
            if(focus==null || focus.isDone) shoot() else {
                focus.addListener({ shoot() },main)
                handler.postDelayed({ shoot() },350)
            }
        } catch(_:Exception) { shoot() }
    }
    val currentTakePhoto by rememberUpdatedState({ takePhoto() })
    val currentFocusPaper by rememberUpdatedState({ paper:DetectedPaper? -> focusPaper(paper) })

    DisposableEffect(preview,lifecycle) {
        alive.set(true)
        var provider:ProcessCameraProvider?=null
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(!alive.get()) return@addListener
            try {
                val loaded=future.get();provider=loaded
                require(loaded.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) { "没有可用的后置相机" }
                val live=Preview.Builder().setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()).build()
                val photo=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(100)
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(4032,3024),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).build()
                val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(1280,960),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
                var previousTime=0L
                var pixels = ByteArray(0)
                analysis.setAnalyzer(analyzer) { image ->
                    try {
                        val now=SystemClock.elapsedRealtime()
                        if(!alive.get() || now-previousTime<220) return@setAnalyzer
                        previousTime=now
                        if(!OpenCvPaperDetector.available()) {
                            main.execute { if(alive.get()) error="无法加载纸边识别组件，请使用系统相机或相册" };return@setAnalyzer
                        }
                        val crop = image.cropRect
                        val plane = image.planes[0]
                        val length = crop.width() * crop.height()
                        if (pixels.size != length) pixels = ByteArray(length)
                        copyScanLuma(plane.buffer, crop.left, crop.top, crop.width(), crop.height(), plane.rowStride, plane.pixelStride, pixels)
                        val raw=Mat(crop.height(),crop.width(),CvType.CV_8UC1);val rotated=Mat()
                        val found=try {
                            raw.put(0,0,pixels)
                            if (image.imageInfo.rotationDegrees == 0) OpenCvPaperDetector.detectGray(raw) else {
                                when(image.imageInfo.rotationDegrees) {
                                    90 -> Core.rotate(raw,rotated,Core.ROTATE_90_CLOCKWISE)
                                    180 -> Core.rotate(raw,rotated,Core.ROTATE_180)
                                    270 -> Core.rotate(raw,rotated,Core.ROTATE_90_COUNTERCLOCKWISE)
                                }
                                OpenCvPaperDetector.detectGray(rotated)
                            }
                        } finally { raw.release();rotated.release() }
                        val result=gate.update(found?.quad,found!=null && found.sharpness>=18 && found.confidence>=.10f,now,currentCanScan && currentAutomatic)
                        main.execute {
                            if(alive.get()) {
                                frame=found;decision=result
                                if(!captureLock.get() && currentCanScan) {
                                    if(found!=null) { focusing.absentAt=null;currentFocusPaper(found) }
                                    else {
                                        val absent=focusing.absentAt ?: now.also { focusing.absentAt=it }
                                        if(now-absent>=600 && focusing.future!=null) {
                                            camera?.cameraControl?.cancelFocusAndMetering()
                                            focusing.future=null;focusing.quad=null
                                        }
                                    }
                                }
                                if(result.capture) currentTakePhoto()
                            }
                        }
                    } catch(_:Exception) { main.execute { if(alive.get()) error="识别暂时失败，可点击立即拍摄" } }
                    catch(_:LinkageError) { main.execute { if(alive.get()) error="当前设备无法加载识别组件" } }
                    finally { image.close() }
                }
                preview.doOnLayout {
                    if(!alive.get()) return@doOnLayout
                    try {
                        live.setSurfaceProvider(preview.surfaceProvider)
                        val group=UseCaseGroup.Builder().addUseCase(live).addUseCase(photo).addUseCase(analysis)
                        preview.viewPort?.let(group::setViewPort)
                        camera=loaded.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,group.build());capture=photo
                    } catch(e:Exception) { error=e.message ?: "无法启动自动扫描，请返回使用相册" }
                }
            } catch(e:Exception) { error=e.message ?: "无法打开相机" }
        },main)
        onDispose {
            alive.set(false);handler.removeCallbacksAndMessages(null);provider?.unbindAll();pendingFile.getAndSet(null)?.delete();analyzer.shutdown()
        }
    }
    BackHandler { if(!shooting) onBack() }
    Surface(color=Color.Black,contentColor=Color.White) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick=onBack,enabled=!shooting) { Text("完成 · $pageCount/20 页",color=Color.White) }
                TextButton(enabled=camera?.cameraInfo?.hasFlashUnit()==true,onClick={
                    torch=!torch;camera?.cameraControl?.enableTorch(torch)
                }) { Text(if(torch) "关闭补光" else "补光",color=Color.White) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val viewfinder: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier) {
                        AndroidView(factory={ preview },modifier=Modifier.fillMaxSize())
                        Canvas(Modifier.fillMaxSize()) {
                            frame?.let { paper ->
                                val factor=minOf(size.width/paper.frameWidth,size.height/paper.frameHeight)
                                val left=(size.width-paper.frameWidth*factor)/2;val top=(size.height-paper.frameHeight*factor)/2
                                val path=Path().apply { val first=paper.quad.points.first();moveTo(left+first.x*paper.frameWidth*factor,top+first.y*paper.frameHeight*factor)
                                    paper.quad.points.drop(1).forEach { lineTo(left+it.x*paper.frameWidth*factor,top+it.y*paper.frameHeight*factor) };close() }
                                drawPath(path,if(decision.progress>.6f)Color(0xff57d69c) else Color(0xff74b8ff),style=Stroke(3.dp.toPx()))
                            }
                        }
                    }
                }
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(error ?: importError ?: when { shooting -> "正在对焦拍摄，请保持稳定…";pageCount>=20 -> "已满 20 页，请点完成后导出";!canScan -> "正在处理刚拍摄的页面…";!automatic -> "调整取景后，点「立即拍摄」。";else -> decision.message })
                        if(automatic && decision.progress>0 && !shooting) LinearProgressIndicator(progress={ decision.progress },modifier=Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = automatic, enabled = !shooting, role = Role.Switch,
                            onValueChange = { automatic = it; gate.retry() }), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("稳定后自动拍摄")
                            Switch(checked = automatic, onCheckedChange = null, enabled = !shooting)
                        }
                        Button(onClick={ takePhoto() },enabled=canScan && !shooting && capture!=null,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("立即拍摄") }
                        Text("纸边完整入镜。每扫完一页，移开纸张后再放入下一页。",style=MaterialTheme.typography.bodySmall)
                    }
                }
                val controlsHeightLimit = maxHeight * .55f
                if (maxWidth > maxHeight && maxWidth >= 560.dp) {
                    Row(Modifier.fillMaxSize()) {
                        viewfinder(Modifier.weight(1.25f).fillMaxHeight())
                        controls(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        viewfinder(Modifier.fillMaxWidth().weight(1f))
                        controls(Modifier.fillMaxWidth().heightIn(max = controlsHeightLimit))
                    }
                }
            }
        }
    }
}

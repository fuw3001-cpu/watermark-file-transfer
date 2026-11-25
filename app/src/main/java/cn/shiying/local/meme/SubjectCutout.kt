package cn.shiying.local.meme

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** U2NETP inference is local. Model and runtime are bundled; no account or network calls. */
object SubjectCutout {
    private val inferenceLock = Mutex()
    suspend fun create(context: Context, input: Bitmap): Bitmap = inferenceLock.withLock { infer(context, input) }

    private suspend fun infer(context: Context, input: Bitmap): Bitmap {
        currentCoroutineContext().ensureActive()
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        if (pixels.count { (it ushr 24) < 32 } >= maxOf(1, pixels.size / 20)) return input.copy(Bitmap.Config.ARGB_8888, false)
        val resized = Bitmap.createScaledBitmap(input, 320, 320, true)
        val rgb = IntArray(320 * 320)
        resized.getPixels(rgb, 0, 320, 0, 0, 320, 320)
        if (resized !== input) resized.recycle()
        val maximum = rgb.maxOf { maxOf(it shr 16 and 255, it shr 8 and 255, it and 255) }.coerceAtLeast(1).toFloat()
        val tensor = FloatArray(3 * rgb.size)
        val mean = floatArrayOf(.485f, .456f, .406f)
        val std = floatArrayOf(.229f, .224f, .225f)
        for (channel in 0..2) for (i in rgb.indices) {
            tensor[channel * rgb.size + i] = (((rgb[i] shr (16 - channel * 8) and 255) / maximum) - mean[channel]) / std[channel]
        }
        val env = OrtEnvironment.getEnvironment()
        val mask = FloatArray(rgb.size)
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            val model = context.assets.open("matting/u2netp.onnx").use { it.readBytes() }
            env.createSession(model, options).use { session ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), longArrayOf(1, 3, 320, 320)).use { data ->
                    session.run(mapOf(session.inputNames.first() to data)).use { output ->
                        (output[0] as OnnxTensor).floatBuffer.get(mask)
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val low = mask.minOrNull() ?: 0f
        val high = mask.maxOrNull() ?: 0f
        require(low.isFinite() && high.isFinite() && high - low > .00001f) { "未识别到明确主体，请换一张图或使用透明 PNG" }
        val alpha = IntArray(mask.size) { index ->
            val opacity = (((mask[index] - low) / (high - low)).coerceIn(0f, 1f) * 255).toInt()
            (opacity shl 24) or 0x00ffffff
        }
        val alphaBitmap = Bitmap.createBitmap(alpha, 320, 320, Bitmap.Config.ARGB_8888)
        val result = input.copy(Bitmap.Config.ARGB_8888, true)
        try {
            Canvas(result).drawBitmap(alphaBitmap, null, Rect(0, 0, result.width, result.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            return result
        } catch (error: Throwable) { result.recycle(); throw error }
        finally { alphaBitmap.recycle() }
    }
}

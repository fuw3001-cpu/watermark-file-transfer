package cn.shiying.local.conversion

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

class NativeMediaConverter {
    private suspend fun probe(file: File): JSONObject = suspendCancellableCoroutine { continuation ->
        val finished = AtomicBoolean(false)
        val session = FFprobeKit.executeWithArgumentsAsync(ConversionCommands.probe(file.absolutePath), { completed ->
            if (finished.compareAndSet(false, true) && continuation.isActive) {
                val result = if (ReturnCode.isSuccess(completed.returnCode)) runCatching { JSONObject(completed.output) } else
                    Result.failure(IllegalArgumentException("无法读取文件，格式可能不受支持或文件已损坏"))
                result.fold(continuation::resume, continuation::resumeWithException)
            }
        }, { /* Do not send file metadata to logs. */ })
        continuation.invokeOnCancellation { finished.set(true); session.cancel() }
    }

    suspend fun convert(spec: ConversionSpec, input: File, output: File, progress: (Float?) -> Unit) {
        val metadata = probe(input)
        val streams = metadata.optJSONArray("streams")
        val tracks = (0 until (streams?.length() ?: 0)).mapNotNull { streams?.optJSONObject(it) }
        val video = tracks.firstOrNull { it.optString("codec_type") == "video" && it.optJSONObject("disposition")?.optInt("attached_pic") != 1 }
        val audio = tracks.firstOrNull { it.optString("codec_type") == "audio" }
        if (spec.kind == ConversionKind.AUDIO) require(audio != null) { "文件没有音轨，无法提取 MP3" }
        else require(video != null) { "文件没有视频画面，无法生成视频或动图" }
        if (spec.kind == ConversionKind.GIF) {
            spec.validateGif()
            requireNotNull(video)
            require(video.optLong("width") * video.optLong("height") <= 16_000_000) { "动图源画面超过 1600 万像素，请先缩小视频" }
        }
        val sourceDuration = metadata.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
        if (spec.kind == ConversionKind.GIF && sourceDuration != null) require(spec.gifStartSeconds < sourceDuration) { "起始时间已超出原文件时长" }
        val duration = if (spec.kind == ConversionKind.GIF) minOf(spec.gifDurationSeconds.toDouble(),
            sourceDuration?.minus(spec.gifStartSeconds) ?: spec.gifDurationSeconds.toDouble()) * 1000 else metadata.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()?.times(1000)
        val copyTracks = spec.kind == ConversionKind.VIDEO && video?.optString("codec_name") == "h264" &&
            video.optString("pix_fmt") in setOf("yuv420p", "yuvj420p") && (audio == null || audio.optString("codec_name") == "aac")
        val copied = copyTracks && execute(ConversionCommands.remux(input.absolutePath, output.absolutePath), duration, progress) &&
            output.length() in 1..MAX_BYTES && try { validOutput(probe(output), spec, audio != null) } catch (_: IllegalArgumentException) { false }
        if (copied) return
        if (copyTracks) progress(null)
        require(execute(ConversionCommands.encode(spec, input.absolutePath, output.absolutePath), duration, progress)) {
            "转换失败，请检查文件是否完整、设备空间是否充足，或换一个文件重试"
        }
        require(output.length() in 1..MAX_BYTES) { "转换结果为空或超过 512 MB" }
        require(validOutput(probe(output), spec, audio != null)) { "转换结果校验失败，请重试" }
    }

    private fun validOutput(metadata: JSONObject, spec: ConversionSpec, hasInputAudio: Boolean): Boolean {
        val format = metadata.optJSONObject("format")?.optString("format_name").orEmpty().split(',')
        val streams = metadata.optJSONArray("streams") ?: return false
        val types = (0 until streams.length()).map { streams.optJSONObject(it)?.optString("codec_type") }
        return when (spec.kind) {
            ConversionKind.VIDEO -> "mp4" in format && "video" in types && (!hasInputAudio || "audio" in types)
            ConversionKind.GIF -> "gif" in format && "video" in types
            else -> "mp3" in format && "audio" in types
        }
    }

    private suspend fun execute(arguments: Array<String>, duration: Double?, progress: (Float?) -> Unit): Boolean =
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val session = FFmpegKit.executeWithArgumentsAsync(arguments, { completed ->
                if (finished.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(ReturnCode.isSuccess(completed.returnCode))
                }
            }, { /* Native command logs stay out of app telemetry. */ }, { statistics ->
                if (continuation.isActive && !finished.get()) {
                    if (statistics.size > MAX_BYTES && finished.compareAndSet(false, true)) {
                        FFmpegKit.cancel(statistics.sessionId)
                        continuation.resumeWithException(IllegalArgumentException("转换结果超过 512 MB，已停止处理"))
                    } else progress(duration?.takeIf { it > 0 }?.let { (statistics.time / it).toFloat().coerceIn(0f, 0.99f) })
                }
            })
            continuation.invokeOnCancellation { finished.set(true); session.cancel() }
        }

    companion object { const val MAX_BYTES = 512L * 1024 * 1024 }
}
